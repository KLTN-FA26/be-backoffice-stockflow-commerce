package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockAvailability;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.common.domain.Sku;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only implementation of {@link InventoryService}, and the module's transaction boundary.
 *
 * <p><b>What the application layer is for.</b> It orchestrates: load the aggregates, call the
 * domain method, save, publish. It contains no business rules — every "may I?" and "how much?"
 * question is answered by {@link StockItem} or {@link StockAllocator}. If a rule appears in this
 * class it is in the wrong place, and {@code ArchitectureTest} is what keeps that honest.</p>
 *
 * <p><b>Package-private class, public interface.</b> Spring injects it by the interface. Nothing
 * outside this package can name the implementation, so nobody can bypass the port by
 * autowiring the concrete class and calling a method the interface does not expose.</p>
 *
 * <p><b>Clock is injected.</b> Reservation expiry is time-dependent, and
 * {@code Instant.now()} scattered through the code makes that untestable. With a {@code Clock}
 * bean the sweeper test fast-forwards 31 minutes instead of sleeping for them.</p>
 */
@Service
@Transactional
class InventoryServiceImpl implements InventoryService, StockConsumption {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);

    private final StockItemRepository repository;
    private final InventoryEventPublisher events;
    private final Clock clock;

    InventoryServiceImpl(StockItemRepository repository, InventoryEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public int availableToPromise(Sku sku) {
        return repository.findAvailableBySku(sku).stream()
                .mapToInt(item -> item.available().value())
                .sum();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockAvailability> availabilityOf(Sku sku) {
        // Earliest expiry first, matching the order the allocator would draw from, so what a
        // warehouse user sees on screen is the order the system will actually pick in.
        return repository.findAvailableBySku(sku).stream()
                .sorted(Comparator.<StockItem, java.time.LocalDate>comparing(
                                StockItem::expiryDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item -> item.location().code()))
                .map(this::toAvailability)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>This method is the whole argument for the monolith.</b> It joins the caller's
     * transaction ({@code Propagation.REQUIRED}, the default, stated explicitly here because it is
     * load-bearing rather than incidental). {@code order} calls it while saving the order; one
     * commit covers both. If the order insert then violates a constraint, this reservation
     * disappears with it, automatically.</p>
     *
     * <p>The microservices version of this same step needed: an outbox row, a Kafka topic, a saga
     * orchestrator holding the in-flight state, a compensating {@code ReleaseStock} command for
     * when the order failed after the reservation succeeded, a reservation timeout in case the
     * compensation itself was lost, and an idempotent consumer so a redelivered command did not
     * reserve twice. All of that existed to approximate what one database transaction gives for
     * free — see {@code docs/adr/0005-modular-monolith.md}.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ReserveStockResult reserve(ReserveStockCommand command) {
        Instant now = clock.instant();

        // 0. REPLAY CHECK, BEFORE ANY PLANNING. This has to come first, and the reason is not
        //    obvious: the first attempt's own holds have already reduced availability, so a
        //    replanned retry would pick DIFFERENT lots, derive different per-item keys, and hold
        //    the stock a second time - or throw "insufficient stock" for a request that already
        //    succeeded. Answering from the existing holds sidesteps both.
        Optional<ReserveStockResult> alreadyServed = replayOf(command);
        if (alreadyServed.isPresent()) {
            log.info("Request {} was already served; returning the original {} hold(s)",
                    command.requestId(), alreadyServed.get().reservations().size());
            return alreadyServed.get();
        }

        // 1. PLAN. Which lots, in what order, for how much. Built from a projection rather than
        //    from aggregates so that planning puts nothing in the persistence context: an entity
        //    read here would be returned again by the locked read in step 3, which would then be
        //    checking availability against pre-lock data. (Step 0 above can load aggregates only
        //    because it returns immediately when it finds any - it never reaches step 3.)
        List<StockAllocator.Candidate> candidates = repository.findAvailabilityBySku(command.sku());
        List<StockAllocator.AllocationLine> plan = StockAllocator.plan(
                command.sku().code(), candidates, Quantity.of(command.quantity()));

        // 2. ORDER THE LOCKS. Two checkouts touching the same two stock items in opposite orders
        //    deadlock; sorting by id gives every transaction the same acquisition order.
        List<StockAllocator.AllocationLine> ordered = plan.stream()
                .sorted(Comparator.comparing(line -> line.stockItemId().value().toString()))
                .toList();

        List<StockReservation> reservations = new ArrayList<>();

        for (StockAllocator.AllocationLine line : ordered) {
            // 3. LOCK AND RE-READ. The plan's numbers are already stale - another checkout may have
            //    taken the same units in between. findByIdForUpdate takes a write lock AND forces a
            //    reload, so the aggregate re-checks availability against what is true right now and
            //    throws if it no longer holds.
            StockItem locked = repository.findByIdForUpdate(line.stockItemId()).orElseThrow(() ->
                    new IllegalStateException(
                            "Stock item %s vanished mid-reservation".formatted(line.stockItemId())));

            Reservation reservation = locked.reserve(
                    command.orderId(),
                    command.requestId(),
                    // One derived key per stock item. Reusing the caller's single id across the
                    // loop would violate uk_stock_reservation_request on the second lot, so any
                    // line spanning two lots would fail outright.
                    perStockItemRequestId(command.requestId(), line.stockItemId()),
                    line.quantity(),
                    now);
            repository.save(locked);
            events.publishEventsOf(locked);

            reservations.add(toApiReservation(locked, reservation));
        }

        log.info("Reserved {} units of {} for order {} across {} stock item(s)",
                command.quantity(), command.sku(), command.orderId(), reservations.size());

        return new ReserveStockResult(command.orderId(), reservations, command.quantity(),
                // Across every location, not only the lots just drawn from: a caller shown
                // "3 left" when 30 sit in the next aisle would reasonably call that a bug.
                availableToPromise(command.sku()), now);
    }

    /**
     * Rebuilds the original result when this request has already been served.
     *
     * <p>Reads the quantities from the stored reservations rather than from the command, so a
     * partially-applied earlier attempt reports what is actually held rather than what was asked
     * for.</p>
     */
    private Optional<ReserveStockResult> replayOf(ReserveStockCommand command) {
        List<StockItem> holders = repository.findWithReservationsForRequest(command.requestId())
                .stream()
                // Same id ordering as the reserve loop. These loads take write locks too, and two
                // replays of different requests touching the same pair of stock items in opposite
                // orders would deadlock exactly as two fresh checkouts would.
                .sorted(Comparator.comparing(item -> item.id().value().toString()))
                .toList();
        if (holders.isEmpty()) {
            return Optional.empty();
        }

        List<StockReservation> reservations = new ArrayList<>();
        Instant earliest = null;
        for (StockItem item : holders) {
            for (Reservation reservation : item.activeReservationsFor(command.requestId())) {
                reservations.add(toApiReservation(item, reservation));
                if (earliest == null || reservation.reservedAt().isBefore(earliest)) {
                    earliest = reservation.reservedAt();
                }
            }
        }
        if (reservations.isEmpty()) {
            return Optional.empty();
        }

        int quantityHeld = reservations.stream().mapToInt(StockReservation::quantity).sum();
        return Optional.of(new ReserveStockResult(command.orderId(), reservations, quantityHeld,
                availableToPromise(command.sku()), earliest));
    }

    private static StockReservation toApiReservation(StockItem item, Reservation reservation) {
        return new StockReservation(
                reservation.id().value(),
                item.id().value(),
                item.location().code(),
                item.lotNumber(),
                // The reservation's own quantity, never the plan's: on a replay the two can differ.
                reservation.quantity().value());
    }

    @Override
    public void release(UUID reservationId, String reason) {
        ReleaseReason releaseReason = parseReason(reason);
        StockItem item = repository.findByReservationIdForUpdate(ReservationId.of(reservationId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No reservation with id " + reservationId));

        item.releaseReservation(ReservationId.of(reservationId), releaseReason, clock.instant());
        repository.save(item);
        events.publishEventsOf(item);

        log.info("Released reservation {} ({})", reservationId, releaseReason);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reached from {@code POST /reservations/{id}/consumption}, which {@code fulfillment} calls
     * when a pick is confirmed. Not on the published {@link InventoryService} port — see
     * {@link StockConsumption} for why.</p>
     */
    @Override
    public void consume(UUID reservationId) {
        ReservationId id = ReservationId.of(reservationId);
        StockItem item = repository.findByReservationIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No reservation with id " + reservationId));
        item.consumeReservation(id, clock.instant());
        repository.save(item);
        events.publishEventsOf(item);

        log.info("Consumed reservation {}: stock deducted for real", reservationId);
    }

    /**
     * Same inputs, same id, every time.
     *
     * <p>Type 3 (name-based) UUID semantics, derived from the caller's request id and the stock
     * item it applies to. Two properties matter: it differs per stock item, so the unique
     * constraint on {@code request_id} is not violated when one line spans several lots; and it is
     * stable across processes and restarts, so a retried checkout is recognised as a replay rather
     * than held a second time.</p>
     */
    private static UUID perStockItemRequestId(UUID requestId, StockItemId stockItemId) {
        return UUID.nameUUIDFromBytes((requestId + ":" + stockItemId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Free-text reason in, enum out. The string arrives from another module's call or from JSON,
     * so it is validated here at the boundary — nothing downstream ever sees an unparsed reason.
     */
    private ReleaseReason parseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return ReleaseReason.MANUAL_OVERRIDE;
        }
        try {
            return ReleaseReason.valueOf(reason.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown release reason '{}', recording as MANUAL_OVERRIDE", reason);
            return ReleaseReason.MANUAL_OVERRIDE;
        }
    }

    private StockAvailability toAvailability(StockItem item) {
        return new StockAvailability(
                item.id().value(),
                item.sku().code(),
                item.location().code(),
                item.lotNumber(),
                item.expiryDate(),
                item.onHand().value(),
                item.reserved().value(),
                0,
                item.available().value());
    }
}
