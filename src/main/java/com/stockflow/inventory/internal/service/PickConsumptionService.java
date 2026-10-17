package com.stockflow.inventory.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.consumption.ConsumedPick;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.consumption.PutBackCommand;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deducts picked stock and books put-backs, writing the ledger as it goes (SCRUM-267/275, docs 07
 * Output and BR-05).
 *
 * <p><b>Why the ledger is the idempotency key.</b> A pick line deducts once: the {@code PICK} row with
 * reference {@code PICK_LINE} and the line's id is looked up under the stock row's lock before anything
 * changes, and {@code uk_stock_movement_pick_line} refuses a second one should two transactions ever get
 * past that check. The hold's own status could not answer the question on its own — CONSUMED says the
 * hold was used, not by whom — which is how a second pick line pointing at the same hold is told apart
 * ({@code RESERVATION_NOT_ACTIVE}) from the same line confirmed twice (answered as done).</p>
 *
 * <p><b>Locks.</b> Every method locks the stock row it changes ({@code SELECT … FOR UPDATE} plus a
 * refresh), which is what serialises a pick against a cancellation releasing the same hold: whichever
 * commits second sees the hold closed. A put-back also takes the SKU's advisory lock first, as moves
 * do, and locks two stock rows in id order. Callers that lock their own rows (fulfillment's pick task)
 * take them before calling in here.</p>
 *
 * <p>Joins the caller's transaction ({@code REQUIRED}): the pick line and the deduction commit or roll
 * back together.</p>
 */
@Service
@Transactional(propagation = Propagation.REQUIRED)
class PickConsumptionService implements PickConsumption, StockConsumption {

    private static final Logger log = LoggerFactory.getLogger(PickConsumptionService.class);

    private final StockItemRepository stockItems;
    private final StockMovementLog ledger;
    private final InventoryEventPublisher events;
    private final LocationDirectory locations;
    private final InventoryPolicyRepository policies;
    private final Clock clock;

    PickConsumptionService(StockItemRepository stockItems, StockMovementLog ledger, InventoryEventPublisher events,
                           LocationDirectory locations, InventoryPolicyRepository policies, Clock clock) {
        this.stockItems = stockItems;
        this.ledger = ledger;
        this.events = events;
        this.locations = locations;
        this.policies = policies;
        this.clock = clock;
    }

    @Override
    public ConsumedPick consumeForPick(UUID reservationId, UUID pickLineId, UUID actorId) {
        Objects.requireNonNull(pickLineId, "pickLineId");
        StockItem item = holderOf(reservationId);
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        requireOrderStock(item, hold);
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.PICK,
                StockMovement.ReferenceType.PICK_LINE, pickLineId);
        if (done.isPresent()) {
            return toConsumed(item, hold, true);
        }
        deduct(item, reservationId, StockMovement.ReferenceType.PICK_LINE, pickLineId, actorId);
        log.info("Pick line {} consumed hold {}: {} x {} off {}", pickLineId, reservationId,
                hold.quantity(), item.sku(), item.location());
        return toConsumed(item, hold, false);
    }

    @Override
    public List<ConsumedPick> consumeForPack(UUID packId, UUID orderId, java.util.Collection<UUID> reservationIds,
                                             UUID actorId) {
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(orderId, "orderId");
        boolean packed = ledger.findByReference(StockMovement.MovementType.PICK, StockMovement.ReferenceType.PACKAGE,
                packId).isPresent();
        // Stock rows in id order: two packages sharing rows never lock them in opposite orders.
        List<UUID> ordered = reservationIds.stream().distinct()
                .sorted(Comparator.comparing((UUID id) -> stockItems.findIdByReservationId(ReservationId.of(id))
                        .map(item -> item.value().toString()).orElse("")).thenComparing(UUID::toString))
                .toList();
        List<ConsumedPick> consumed = new java.util.ArrayList<>();
        for (UUID reservationId : ordered) {
            StockItem item = holderOf(reservationId);
            Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
            requireOrderStock(item, hold);
            if (!orderId.equals(hold.orderId())) {
                throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                        "Hold %s is not order %s's".formatted(reservationId, orderId));
            }
            if (!packed && !locations.isPackingArea(item.location())) {
                // A package takes the printed goods waiting in packing - never blanks still on a storage bin.
                throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH, "Hold %s is at %s, not in a packing area"
                        .formatted(reservationId, item.location()));
            }
            if (packed) {
                consumed.add(toConsumed(item, hold, true));
                continue;
            }
            deduct(item, reservationId, StockMovement.ReferenceType.PACKAGE, packId, actorId);
            consumed.add(toConsumed(item, hold, false));
            log.info("Package {} consumed hold {}: {} x {} off {}", packId, reservationId, hold.quantity(), item.sku(),
                    item.location());
        }
        return consumed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not idempotent by itself: the hold's status is what refuses a second call. The ledger row names
     * the order, the only document an operator's call has.</p>
     */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "stock-reservation", resourceId = "#reservationId")
    public void consume(UUID reservationId, UUID actorId) {
        StockItem item = holderOf(reservationId);
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        requireOrderStock(item, hold);
        deduct(item, reservationId, StockMovement.ReferenceType.ORDER, hold.orderId(), actorId);
        log.info("Consumed reservation {} by hand: stock deducted for real", reservationId);
    }

    /**
     * A pick deducts an order's goods from a shelf. A production order's hold (a sample's or reprint's blanks) and
     * goods in the print shop are production's to close - issue, finish, write off - never a pick's (review of #109).
     */
    private static void requireOrderStock(StockItem item, Reservation hold) {
        if (hold.productionOrderId() != null || item.status() == StockStatus.IN_PRODUCTION) {
            throw new BusinessException(ErrorCode.PRODUCTION_HOLD_NOT_PICKABLE,
                    "Hold %s belongs to production; production closes it, not a pick".formatted(hold.id().value()));
        }
    }

    @Override
    public void putBack(PutBackCommand command) {
        LocationId target = new LocationId(command.locationCode());
        // BR-SEC-002: the bin is in a warehouse the caller is assigned to. First, so a replay cannot tell
        // anything about another warehouse.
        WarehouseScope.requireLocation(target.code());
        policies.lock(command.sku());
        if (ledger.findByReference(StockMovement.MovementType.PUT_BACK, StockMovement.ReferenceType.PICK_LINE,
                command.pickLineId()).isPresent()) {
            return;
        }
        switch (locations.stateOf(target)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + target);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(target));
            case USABLE -> { }
        }
        StockItem sourceView = stockItems.findByReservationIdForUpdate(ReservationId.of(command.reservationId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + command.reservationId()));
        // The goods come back as the layer they left: same SKU, lot, expiry and receipt time, so FEFO and
        // FIFO still see them in their place. At the bin they left, that layer is the source row itself
        // (one row per SKU, location, lot, serial and receipt); elsewhere it may already exist too.
        Optional<StockItem> destinationView = sourceView.location().equals(target)
                ? Optional.of(sourceView)
                : stockItems.findBySkuAndLocation(sourceView.sku(), target).stream()
                        .filter(item -> Objects.equals(item.lotNumber(), sourceView.lotNumber())
                                && Objects.equals(item.receivedAt(), sourceView.receivedAt())
                                && Objects.equals(item.serialNumber(), sourceView.serialNumber()))
                        .findFirst();
        List<StockItem> toLock = destinationView.isPresent() && !destinationView.get().id().equals(sourceView.id())
                ? List.of(sourceView, destinationView.get()) : List.of(sourceView);
        StockItem source = null;
        StockItem destination = null;
        for (StockItem view : toLock.stream()
                .sorted(Comparator.comparing(item -> item.id().value().toString())).toList()) {
            StockItem locked = stockItems.findByIdForUpdate(view.id()).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid put-back".formatted(view.id())));
            if (locked.id().equals(sourceView.id())) {
                source = locked;
            }
            if (destinationView.isPresent() && locked.id().equals(destinationView.get().id())) {
                destination = locked;
            }
        }
        Objects.requireNonNull(source, "source");
        Quantity quantity = Quantity.of(command.quantity());
        if (destination == null) {
            destination = StockItem.arrivedAs(source, target, quantity, StockStatus.AVAILABLE);
        } else {
            destination.putBack(quantity);
        }
        stockItems.save(destination);

        Instant now = clock.instant();
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.PUT_BACK,
                source.sku(), source.lotNumber(), null, target, quantity.value(), StockStatus.AVAILABLE,
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId(), null, command.actorId(), now)
                .ofLot(source.lotId())
                .forOrder(source.findReservation(ReservationId.of(command.reservationId()))
                        .map(Reservation::orderId).orElse(null)));
        log.info("Pick line {} put back: {} x {} onto {}", command.pickLineId(), quantity, source.sku(), target);
    }

    private void deduct(StockItem item, UUID reservationId, StockMovement.ReferenceType referenceType,
                        UUID referenceId, UUID actorId) {
        Instant now = clock.instant();
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        StockStatus status = item.status();
        item.consumeReservation(ReservationId.of(reservationId), now);
        stockItems.save(item);
        events.publishEventsOf(item);
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.PICK, item.sku(),
                item.lotNumber(), item.location(), null, hold.quantity().value(), status, referenceType,
                referenceId, null, actorId, now).ofLot(item.lotId()).forOrder(hold.orderId()));
    }

    /**
     * The stock item holding {@code reservationId}, locked. A warehouse-bound caller reaches only holds in
     * their own warehouses (BR-SEC-002); the system is not limited.
     */
    private StockItem holderOf(UUID reservationId) {
        StockItem item = stockItems.findByReservationIdForUpdate(ReservationId.of(reservationId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + reservationId));
        WarehouseScope.requireLocation(item.location().code());
        return item;
    }

    private static ConsumedPick toConsumed(StockItem item, Reservation hold, boolean already) {
        return new ConsumedPick(hold.id().value(), item.sku().code(), item.location().code(), item.lotNumber(),
                hold.quantity().value(), already);
    }
}
