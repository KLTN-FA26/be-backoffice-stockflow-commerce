package com.stockflow.inventory.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.IssueReservedStockResult;
import com.stockflow.inventory.api.ReserveProductionStockCommand;
import com.stockflow.inventory.api.LowStockItem;
import com.stockflow.inventory.internal.domain.StockThresholds;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.OrderHold;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;

import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockAvailability;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.internal.domain.InventoryItemDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockLevelLine;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.stream.Collectors;

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
class InventoryServiceImpl implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);

    private final StockItemRepository repository;
    private final InventoryEventPublisher events;
    private final StockOperations operations;
    private final InventoryItemDirectory items;
    private final Clock clock;
    private final InventoryControlService controls;
    private final InventoryPolicyRepository policies;

    /** How long a hold lasts while its order waits for payment (kltn-docs 14: set by the business). */
    private final java.time.Duration reservationTtl;

    InventoryServiceImpl(StockItemRepository repository, InventoryEventPublisher events,
                         StockOperations operations, InventoryItemDirectory items, Clock clock,
                         InventoryControlService controls, InventoryPolicyRepository policies,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${stockflow.inventory.reservation-ttl:PT30M}") java.time.Duration reservationTtl) {
        if (reservationTtl == null || reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalArgumentException("stockflow.inventory.reservation-ttl must be positive");
        }
        this.reservationTtl = reservationTtl;
        this.repository = repository;
        this.events = events;
        this.operations = operations;
        this.items = items;
        this.clock = clock;
        this.controls = controls;
        this.policies = policies;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LowStockItem> lowStockBatch(String afterSku, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        List<LowStockItem> low = new ArrayList<>();
        String cursor = afterSku == null ? "" : afterSku;
        while (low.size() < limit) {
            // A scan page is every SKU with a threshold, low or not, so keep reading until enough are low.
            var page = policies.thresholdPage(cursor, Math.max(limit, 200), today);
            for (var row : page) {
                if (StockThresholds.isLow(row.usableOnHand(), row.reorderPoint(), row.safetyStock())) {
                    low.add(new LowStockItem(row.sku(), row.usableOnHand(), row.reorderPoint(), row.safetyStock(),
                            Boolean.TRUE.equals(StockThresholds.reorderRequired(row.usableOnHand(), row.reorderPoint())),
                            Boolean.TRUE.equals(StockThresholds.belowSafetyStock(row.usableOnHand(), row.safetyStock()))));
                    if (low.size() == limit) {
                        return low;
                    }
                }
                cursor = row.sku();
            }
            if (page.size() < Math.max(limit, 200)) {
                break;
            }
        }
        return low;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepareReservation(Set<Sku> skus) {
        policies.lockReservationStock(skus.stream().map(Sku::code).collect(Collectors.toSet()));
    }

    /** Delegated: moves and adjustments write the ledger, which {@link StockOperations} owns. */
    @Override
    public StockMove move(MoveStockCommand command) {
        return operations.move(command);
    }

    @Override
    public StockMove receive(ReceiveStockCommand command) {
        return operations.receive(command);
    }

    @Override
    public StockMove reclassify(ReclassifyStockCommand command) {
        return operations.reclassify(command);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryItemPolicy> itemPolicy(UUID inventoryItemId) {
        return items.policyOf(inventoryItemId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryItemPolicy> itemPolicy(Sku sku) {
        return items.policyOf(sku.code());
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, InventoryItemPolicy> itemPolicies(java.util.Collection<UUID> inventoryItemIds) {
        return items.policiesOf(inventoryItemIds);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, String> skusOf(java.util.Collection<UUID> inventoryItemIds) {
        return items.skusOf(inventoryItemIds);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<String, UUID> itemIdsOf(java.util.Collection<String> skus) {
        return items.itemIdsOf(skus);
    }

    @Override
    public void deleteUnusedItems(java.util.Collection<String> skus) {
        policies.deleteItems(skus);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<String> skusInUse(java.util.Collection<String> skus) {
        return policies.skusInUse(skus);
    }

    @Override
    public StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command) {
        return operations.requestAdjustment(command);
    }

    @Override
    @Transactional(readOnly = true)
    public int availableToPromise(Sku sku) {
        return Math.toIntExact(
                availableQuantities(Set.of(sku.code())).getOrDefault(sku.code(), 0L));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> availableQuantities(Set<String> skus) {
        var normalized =
                skus.stream()
                .map(code -> new Sku(code).code())
                .collect(Collectors.toSet());
        if (normalized.isEmpty()) return Map.of();
        return repository.availableQuantities(normalized, BusinessCalendar.date(clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Sku, Integer> availableToPromise(Collection<Sku> skus) {
        Set<Sku> wanted = new LinkedHashSet<>(skus);
        Map<Sku, Integer> found = repository.sumAvailableBySkus(wanted, BusinessCalendar.date(clock.instant()));
        Map<Sku, Integer> result = new LinkedHashMap<>();
        // Every SKU asked for gets an answer: no stock is an answer (0), not a missing key.
        wanted.forEach(sku -> result.put(sku, found.getOrDefault(sku, 0)));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public int availableToPromise(Sku sku, String warehouseCode) {
        String wanted = new LocationId(warehouseCode).code();
        return levelsOf(sku).stream()
                .filter(level -> level.warehouseCode().equals(wanted))
                .mapToInt(StockLevel::atp)
                .sum();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockAvailability> availabilityOf(Sku sku) {
        var today = BusinessCalendar.date(clock.instant());
        // Use the configured picking order and one business date for the entire result.
        return repository.findAvailableBySku(sku).stream()
                .filter(item -> notExpired(item, today))
                .sorted(Comparator.comparing(item ->
                                        new StockAllocator.Candidate(
                                                item.id(),
                                                item.location(),
                                                item.lotNumber(),
                                                item.expiryDate(),
                                                item.status(),
                                                item.available(),
                                                item.receivedAt()),
                                StockAllocator.comparator(controls.policy(sku).removalStrategy())))
                .map(this::toAvailability)
                // BR-SEC-002: a warehouse-bound caller sees the stock of their own warehouses only.
                .filter(row -> com.stockflow.common.security.WarehouseScope.restriction()
                        .map(allowed -> allowed.prefixes().contains(
                                com.stockflow.common.security.WarehouseScope.prefixOf(row.locationCode())))
                        .orElse(true))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockLevel> levelsOf(Sku sku) {
        record Totals(int onHand, int available, int reserved, int atp) {
            static Totals of(StockLevelLine line, LocalDate today) {
                boolean usable = line.status() == StockStatus.AVAILABLE
                                && (line.expiryDate() == null
                                        || !line.expiryDate().isBefore(today));
                int sellable = usable ? line.onHand() : 0;
                return new Totals(line.onHand(), sellable, line.reserved(),
                        usable ? line.onHand() - line.reserved() : 0);
            }

            Totals plus(Totals other) {
                return new Totals(onHand + other.onHand, available + other.available,
                        reserved + other.reserved,
                        atp + other.atp);
            }
        }
        Map<String, Totals> byWarehouse = new TreeMap<>();
        var today = BusinessCalendar.date(clock.instant());
        for (StockLevelLine line : repository.findLevelLinesBySku(sku)) {
            byWarehouse.merge(line.location().warehouseCode(), Totals.of(line, today), Totals::plus);
        }
        return byWarehouse.entrySet().stream()
                // BR-SEC-002: a warehouse-bound caller sees their own warehouses' levels only.
                .filter(entry -> com.stockflow.common.security.WarehouseScope.restriction()
                        .map(allowed -> allowed.prefixes().contains(entry.getKey()))
                        .orElse(true))
                .map(entry -> new StockLevel(sku.code(), entry.getKey(),
                        entry.getValue().onHand(), entry.getValue().available(),
                        entry.getValue().reserved(), 0,
                        entry.getValue().atp()))
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
        Optional<ReserveStockResult> alreadyServed = replayOf(command.requestId(), command.sku(), command.orderId());
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
                command.sku().code(), candidates, Quantity.of(command.quantity()),
                        controls.policy(command.sku()).removalStrategy(),
                        BusinessCalendar.date(now));

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
                    now,
                    reservationTtl);
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
     * {@inheritDoc}
     *
     * <p>The steps of {@link #reserve}: replay first, plan over a projection, lock in id order, re-check
     * under the lock. The plan draws only on the production order's warehouse.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ReserveStockResult reserveForProduction(ReserveProductionStockCommand command) {
        Instant now = clock.instant();
        String warehouse = new LocationId(command.warehouseCode()).code();
        Optional<ReserveStockResult> alreadyServed = replayOf(command.requestId(), command.sku(), null);
        if (alreadyServed.isPresent()) {
            return alreadyServed.get();
        }
        List<StockAllocator.Candidate> candidates = repository.findAvailabilityBySku(command.sku()).stream()
                .filter(candidate -> candidate.location().warehouseCode().equals(warehouse))
                .toList();
        List<StockAllocator.AllocationLine> ordered = StockAllocator.plan(
                        command.sku().code(), candidates, Quantity.of(command.quantity()),
                        controls.policy(command.sku()).removalStrategy(), BusinessCalendar.date(now))
                .stream()
                .sorted(Comparator.comparing(line -> line.stockItemId().value().toString()))
                .toList();
        List<StockReservation> reservations = new ArrayList<>();
        for (StockAllocator.AllocationLine line : ordered) {
            StockItem locked = repository.findByIdForUpdate(line.stockItemId()).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-reservation".formatted(line.stockItemId())));
            Reservation reservation = locked.reserveForProduction(command.productionOrderId(), command.requestId(),
                    perStockItemRequestId(command.requestId(), line.stockItemId()), line.quantity(), now);
            repository.save(locked);
            reservations.add(toApiReservation(locked, reservation));
        }
        log.info("Held {} units of {} in {} for production order {} across {} stock item(s)", command.quantity(),
                command.sku(), warehouse, command.productionOrderId(), reservations.size());
        return new ReserveStockResult(null, reservations, command.quantity(),
                availableToPromise(command.sku(), warehouse), now);
    }

    @Override
    public int releaseProductionHolds(UUID productionOrderId) {
        Instant now = clock.instant();
        int released = 0;
        // SKU locks first, rows second: the order every move and checkout takes them in (review of #109, item 4).
        // Locking the rows first and a move of the same transaction taking the SKU lock after (a completion
        // releasing unissued blanks, then handing the goods to packing) can deadlock against a checkout (40P01).
        productionHoldViews(productionOrderId).stream().map(com.stockflow.inventory.api.HoldView::sku).distinct()
                .sorted().forEach(policies::lock);
        for (StockItem item : repository.findWithReservationsForProductionOrder(productionOrderId)) {
            if (item.status() != StockStatus.AVAILABLE) {
                continue;   // issued, received for production, or finished: production closes those itself
            }
            List<Reservation> live = item.reservations().stream()
                    .filter(r -> r.status().isActive() && productionOrderId.equals(r.productionOrderId()))
                    .toList();
            for (Reservation hold : live) {
                item.releaseReservation(hold.id(), ReleaseReason.PRODUCTION_CANCELLED, now);
                released += hold.quantity().value();
            }
            if (!live.isEmpty()) {
                repository.save(item);
            }
        }
        if (released > 0) {
            log.info("Released {} unit(s) held for production order {}", released, productionOrderId);
        }
        return released;
    }

    /**
     * Read without locking anything (review of #109, item 4). Every caller then moves or releases some of these holds
     * through a path that takes the SKU lock first and the stock rows second; locking the rows here, before that SKU
     * lock, inverted the order every checkout and move use, and two such transactions could deadlock (40P01). The
     * production order's own row lock is what keeps two steps of one order apart.
     */
    @Override
    public List<StockReservation> productionHolds(UUID productionOrderId) {
        return liveHolds(repository.findHeldReservationIdsOfProductionOrder(productionOrderId)).stream()
                .sorted(java.util.Comparator.comparing(hold -> hold.stockItemId().toString()))
                .toList();
    }

    /** Delegated: the hold moves with its goods and the ledger records it, see {@link StockOperations}. */
    @Override
    public IssueReservedStockResult issueReserved(IssueReservedStockCommand command) {
        return operations.issueReserved(command);
    }

    @Override
    public StockReservation holdReceived(com.stockflow.inventory.api.HoldReceivedStockCommand command) {
        return operations.holdReceived(command);
    }

    @Override
    public StockMove consumeHeld(com.stockflow.inventory.api.ConsumeHeldStockCommand command) {
        return operations.consumeHeld(command);
    }

    @Override
    public IssueReservedStockResult unissueHeld(IssueReservedStockCommand command) {
        return operations.unissueHeld(command);
    }

    @Override
    public IssueReservedStockResult finishHeld(IssueReservedStockCommand command) {
        return operations.finishHeld(command);
    }

    @Override
    public StockAdjustmentSummary writeOffHeld(com.stockflow.inventory.api.WriteOffHeldStockCommand command) {
        return operations.writeOffHeld(command);
    }

    @Override
    public Optional<StockAdjustmentSummary> scrapCancelledPrint(UUID reservationId, UUID orderId, String note,
                                                                UUID requestedBy) {
        return operations.scrapCancelledPrint(reservationId, orderId, note, requestedBy);
    }

    @Override
    public StockMove returnHeld(com.stockflow.inventory.api.ReturnHeldStockCommand command) {
        return operations.returnHeld(command);
    }

    @Override
    public void handHoldToOrder(UUID reservationId, UUID productionOrderId, UUID orderId) {
        ReservationId id = ReservationId.of(reservationId);
        StockItem item = holderOf(id);
        Reservation hold = item.findReservation(id).orElseThrow();
        if (hold.status().isActive() && orderId.equals(hold.orderId())) {
            return;
        }
        if (!hold.status().isActive() || !productionOrderId.equals(hold.productionOrderId())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s is not a live hold of production order %s".formatted(reservationId, productionOrderId));
        }
        item.handHoldToOrder(id, productionOrderId, orderId);
        repository.save(item);
        log.info("Hold {} of production order {} is now order {}'s", reservationId, productionOrderId, orderId);
    }

    @Override
    public void reduceHold(UUID reservationId, UUID orderId, UUID productionOrderId, int quantity) {
        ReservationId id = ReservationId.of(reservationId);
        StockItem item = holderOf(id);
        Reservation hold = item.findReservation(id).orElseThrow();
        if (!hold.isHeldFor(orderId, productionOrderId) || !hold.status().isActive()
                || item.status() == StockStatus.IN_PRODUCTION || quantity < 1
                || hold.quantity().value() < quantity) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s cannot give up %d unit(s) here".formatted(reservationId, quantity));
        }
        item.reduceHold(id, Quantity.of(quantity), ReleaseReason.SUBCONTRACTED, clock.instant());
        repository.save(item);
        log.info("Hold {} gave up {} unit(s) for subcontracting", reservationId, quantity);
    }

    @Override
    public List<StockReservation> liveHolds(Collection<UUID> reservationIds) {
        List<StockReservation> live = new ArrayList<>();
        for (UUID id : new java.util.LinkedHashSet<>(reservationIds)) {
            repository.findIdByReservationId(ReservationId.of(id)).flatMap(repository::findById).ifPresent(item ->
                    item.findReservation(ReservationId.of(id)).filter(r -> r.status().isActive())
                            .ifPresent(r -> live.add(toApiReservation(item, r))));
        }
        return live;
    }

    /**
     * Rebuilds the original result when this request has already been served.
     *
     * <p>Reads the quantities from the stored reservations rather than from the command, so a
     * partially-applied earlier attempt reports what is actually held rather than what was asked
     * for.</p>
     */
    private Optional<ReserveStockResult> replayOf(UUID requestId, Sku sku, UUID orderId) {
        List<StockItem> holders = repository.findWithReservationsForRequest(requestId)
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
            for (Reservation reservation : item.activeReservationsFor(requestId)) {
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
        return Optional.of(new ReserveStockResult(orderId, reservations, quantityHeld,
                availableToPromise(sku), earliest));
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

    /**
     * {@inheritDoc}
     *
     * <p>{@code findWithReservationsForOrder} locks each stock row, the same lock the expiry sweep
     * takes, so a hold is either pinned here or expired there — never both.</p>
     */
    @Override
    public int pinReservations(UUID orderId) {
        int pinned = 0;
        for (StockItem item : repository.findWithReservationsForOrder(orderId)) {
            int here = item.pinReservationsOf(orderId);
            if (here > 0) {
                repository.save(item);
                pinned += here;
            }
        }
        if (pinned > 0) {
            log.info("Pinned {} hold(s) of order {}: it no longer waits for payment", pinned, orderId);
        }
        return pinned;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderHold> holdsOf(UUID orderId) {
        return repository.findHeldForOrder(orderId).stream()
                .map(held -> new OrderHold(held.sku(), held.locationCode(), held.quantity()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.stockflow.inventory.api.HoldView> productionHoldViews(UUID productionOrderId) {
        List<UUID> ids = repository.findHeldReservationIdsOfProductionOrder(productionOrderId);
        Map<UUID, com.stockflow.inventory.api.HoldView> byId = holds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(com.stockflow.inventory.api.HoldView::reservationId,
                        java.util.function.Function.identity()));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.stockflow.inventory.api.HoldView> holds(Collection<UUID> reservationIds) {
        return repository.findHolds(Set.copyOf(reservationIds)).stream()
                .map(hold -> new com.stockflow.inventory.api.HoldView(hold.reservationId(), hold.sku(),
                        hold.locationCode(), hold.lotNumber(), hold.quantity(),
                        com.stockflow.inventory.api.HoldView.Status.valueOf(hold.status().name()), hold.expiresAt(), hold.expiryDate()))
                .toList();
    }

    @Override
    public int rearmReservations(UUID orderId) {
        Instant deadline = clock.instant().plus(reservationTtl);
        int rearmed = 0;
        for (StockItem item : repository.findWithReservationsForOrder(orderId)) {
            int here = item.rearmReservationsOf(orderId, deadline);
            if (here > 0) {
                repository.save(item);
                rearmed += here;
            }
        }
        if (rearmed > 0) {
            log.info("Order {} waits for payment again: {} hold(s) expire at {}", orderId, rearmed, deadline);
        }
        return rearmed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A hold whose goods are in the print shop is <b>not</b> released here (SCRUM-401, Q3): an order cancelled
     * after its blanks were issued releases its holds in its own transaction, before production knows. The goods
     * stay held, unsellable, until production stops the job and decides unit by unit — printed goods written
     * off, unprinted blanks returned ({@link #returnHeld}). Releasing here would leave production unable to tell
     * the printed units from the blank ones.</p>
     */
    @Override
    public void release(UUID reservationId, String reason) {
        ReleaseReason releaseReason = parseReason(reason);
        StockItem item = holderOf(ReservationId.of(reservationId));
        if (item.status() == StockStatus.IN_PRODUCTION) {
            log.info("Hold {} is in the print shop; production closes it, not a {} release", reservationId,
                    releaseReason);
            return;
        }
        // BR-PRD-10: printed for this order, waiting in packing - packed with it, or scrapped (scrapCancelledPrint),
        // never freed for someone else to buy.
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        if (operations.isFinishedCustomGoods(item, hold)) {
            log.info("Hold {} is printed goods in packing; it is packed or scrapped, not a {} release", reservationId,
                    releaseReason);
            return;
        }

        item.releaseReservation(ReservationId.of(reservationId), releaseReason, clock.instant());
        repository.save(item);
        events.publishEventsOf(item);

        log.info("Released reservation {} ({})", reservationId, releaseReason);
    }

    /**
     * The stock item holding {@code reservationId}, locked. A warehouse-bound caller reaches only
     * holds in their own warehouses (BR-SEC-002); the system — an event listener, the sweeper — is
     * not limited.
     */
    private StockItem holderOf(ReservationId reservationId) {
        StockItem item = repository.findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + reservationId.value()));
        WarehouseScope.requireLocation(item.location().code());
        return item;
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
                .getBytes(StandardCharsets.UTF_8));
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
            log.warn("Unknown release reason '{}', recording as MANUAL_OVERRIDE", LogSafe.text(reason));
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

    private static boolean notExpired(StockItem item, LocalDate today) {
        return item.expiryDate() == null || !item.expiryDate().isBefore(today);
    }
}
