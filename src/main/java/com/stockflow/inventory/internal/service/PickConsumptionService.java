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
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deducts picked cartons and books them back whole when the order is cancelled, writing the ledger as it goes
 * (SCRUM-267/275, team plan 4C-4; kltn-docs 07 Output, BR-05, §4.5 step 14).
 *
 * <p><b>Why the ledger is the idempotency key.</b> A pick line deducts once: the {@code PICK} row with
 * reference {@code PICK_LINE} and the line's id is looked up under the stock row's lock before anything
 * changes, and {@code uk_stock_movement_pick_line} refuses a second one should two transactions ever get
 * past that check. The hold's own status could not answer the question on its own — CONSUMED says the
 * hold was used, not by whom — which is how a second pick line pointing at the same hold is told apart
 * ({@code RESERVATION_NOT_ACTIVE}) from the same line confirmed twice (answered as done).</p>
 *
 * <p><b>Locks.</b> Every method takes the SKU's advisory lock, then the carton (stock package) the hold's
 * row is in if it is in one, then the stock row it changes ({@code SELECT … FOR UPDATE} plus a refresh): the
 * lock order every writer of a carton keeps (V20261020000100). The row lock is what serialises a pick against
 * a cancellation releasing the same hold: whichever commits second sees the hold closed. A put-back locks the
 * carton the pick emptied and its one row. Callers that lock their own rows (fulfillment's pick task, its package)
 * take them before calling in here, and nothing takes a SKU lock and then such a row.</p>
 *
 * <p>A pick takes its carton whole and closes it CONSUMED in the same transaction ({@link StockPackageContents}); a
 * put-back brings that very carton back IN_STOCK, sealed ({@link StockPackage#restock}).</p>
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
    private final StockPackageContents cartons;
    private final Clock clock;
    private final com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts;
    private final com.stockflow.inventory.internal.domain.StockDamageReportRepository damageReports;
    private final StockPackageRepository stockPackages;

    PickConsumptionService(StockItemRepository stockItems, StockMovementLog ledger, InventoryEventPublisher events,
                           LocationDirectory locations, InventoryPolicyRepository policies,
                           StockPackageContents cartons, Clock clock,
                           com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts,
                           com.stockflow.inventory.internal.domain.StockDamageReportRepository damageReports,
                           StockPackageRepository stockPackages) {
        this.stockPackages = stockPackages;
        this.cycleCounts = cycleCounts;
        this.damageReports = damageReports;
        this.stockItems = stockItems;
        this.ledger = ledger;
        this.events = events;
        this.locations = locations;
        this.policies = policies;
        this.cartons = cartons;
        this.clock = clock;
    }

    @Override
    public ConsumedPick consumeForPick(UUID reservationId, UUID pickLineId, UUID actorId) {
        Objects.requireNonNull(pickLineId, "pickLineId");
        StockPackageContents.LockedCartons locked = lockSkusAndCartons(List.of(reservationId));
        StockItem item = holderOf(reservationId);
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        requireOrderStock(item, hold);
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.PICK,
                StockMovement.ReferenceType.PICK_LINE, pickLineId);
        if (done.isPresent()) {
            return toConsumed(item, hold, true);
        }
        deduct(item, reservationId, StockMovement.ReferenceType.PICK_LINE, pickLineId, actorId, locked);
        cartons.settle(locked, clock.instant());
        log.info("Pick line {} consumed hold {}: {} x {} off {}", pickLineId, reservationId,
                hold.quantity(), item.sku(), item.location());
        return toConsumed(item, hold, false);
    }

    /**
     * The first two locks of the order every carton writer keeps: the SKUs of these holds' rows (sorted), then the
     * cartons those rows are in. The rows themselves are locked after, one by one, by the caller. A hold is moved
     * from row to row only under its SKU lock, so the rows are read again once that lock is held.
     */
    private StockPackageContents.LockedCartons lockSkusAndCartons(java.util.Collection<UUID> reservationIds) {
        java.util.function.Function<UUID, Optional<StockItem>> row = id ->
                stockItems.findIdByReservationId(ReservationId.of(id)).flatMap(stockItems::findById);
        reservationIds.stream().map(row).flatMap(Optional::stream).map(item -> item.sku().code())
                .distinct().sorted().forEach(policies::lock);
        return cartons.lockFor(reservationIds.stream().map(row).flatMap(Optional::stream).toList());
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
        StockPackageContents.LockedCartons locked = lockSkusAndCartons(List.of(reservationId));
        StockItem item = holderOf(reservationId);
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        requireOrderStock(item, hold);
        deduct(item, reservationId, StockMovement.ReferenceType.ORDER, hold.orderId(), actorId, locked);
        cartons.settle(locked, clock.instant());
        log.info("Consumed reservation {} by hand: stock deducted for real", reservationId);
    }

    /**
     * A pick deducts an order's goods from a shelf, never goods a legacy print-shop row still records
     * ({@code IN_PRODUCTION}, review of #109).
     */
    private static void requireOrderStock(StockItem item, Reservation hold) {
        if (item.status() == StockStatus.IN_PRODUCTION) {
            throw new BusinessException(ErrorCode.PRODUCTION_HOLD_NOT_PICKABLE,
                    "Hold %s is not an order's goods on a shelf; a pick does not close it".formatted(hold.id().value()));
        }
    }

    @Override
    public void putBack(PutBackCommand command) {
        LocationId target = new LocationId(command.locationCode());
        // BR-SEC-002: the bin is in a warehouse the caller is assigned to. First, so a replay cannot tell
        // anything about another warehouse.
        WarehouseScope.requireLocation(target.code());
        // The carton the hold emptied, in the order every carton writer locks (V20261020000100): the SKU, then the
        // carton FOR UPDATE, then its row by id. The row is read once unlocked to learn the SKU and the carton; a pick
        // line's hold never changes row once consumed.
        StockItem rowView = stockItems.findIdByReservationId(ReservationId.of(command.reservationId()))
                .flatMap(stockItems::findById)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + command.reservationId()));
        policies.lock(rowView.sku().code());
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
        if (rowView.stockPackageId() == null) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("Pick line %s took loose stock; only a "
                    + "whole carton is put back (kltn-docs 07 §4.5 step 14)").formatted(command.pickLineId()));
        }
        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(rowView.stockPackageId())).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "No stock package " + rowView.stockPackageId().value()));
        StockItem row = stockItems.findByIdForUpdate(rowView.id()).orElseThrow(() ->
                new IllegalStateException("Stock item %s vanished mid put-back".formatted(rowView.id())));
        Reservation consumed = row.findReservation(ReservationId.of(command.reservationId())).orElseThrow();
        if (!carton.lpn().equalsIgnoreCase(command.lpn().trim())) {
            // tg_pick_line_carton keeps a line's LPN its hold's carton: a mismatch is a caller's bug, not a request.
            throw new IllegalStateException("Pick line %s names carton %s; its hold is in %s"
                    .formatted(command.pickLineId(), command.lpn(), carton.lpn()));
        }
        // The whole carton came off the shelf on this hold, and all of it is still here: none of it went to quarantine
        // damaged at packing (R8). Anything else is not a carton any more and cannot go back as one.
        int damagedAtPacking = damageReports.damagedAfterPick(command.reservationId());
        if (consumed.status() != com.stockflow.inventory.internal.domain.ReservationStatus.CONSUMED
                || carton.receivedQty() == null || consumed.quantity().value() != carton.receivedQty()
                || damagedAtPacking > 0) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("Allocation %s is %s %s of carton %s "
                    + "(%s counted in), %d of them found damaged at packing; only a whole carton taken by the pick goes "
                    + "back").formatted(command.reservationId(), consumed.status(), consumed.quantity(), carton.lpn(),
                    carton.receivedQty(), damagedAtPacking));
        }
        Quantity quantity = consumed.quantity();
        // CONSUMED -> IN_STOCK, sealed, at the bin (4C-2's transition; INVALID_STOCK_PACKAGE_TRANSITION otherwise); the
        // row follows it and takes its units back as the layer they left - same lot and receipt time, so FIFO still
        // sees them in their place. Rejoining their own receipt, not replenishment: a FIFO SKU's policy trigger refuses
        // the raised on_hand unless the row is named as a same-receipt merge (V20261017000450). The carton's contents
        // are checked against it at commit (V20261020000200): IN_STOCK, unopened, holding what was counted in.
        carton.restock(target);
        row.followPackageTo(target);
        row.putBack(quantity);
        stockItems.saveMerged(row);
        stockPackages.save(carton);

        Instant now = clock.instant();
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.PUT_BACK,
                row.sku(), row.lotNumber(), null, target, quantity.value(), StockStatus.AVAILABLE,
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId(), null, command.actorId(), now)
                .ofLot(row.lotId()).forOrder(consumed.orderId()).arriving(carton.id()));
        log.info("Pick line {} put back: carton {} ({} x {}) onto {}", command.pickLineId(), carton.lpn(), quantity,
                row.sku(), target);
    }

    /**
     * {@inheritDoc}
     *
     * <p>One count per slot: a short pick on a slot another count holds - open, or submitted with its variance still
     * pending approval, whatever its trigger (the rule a plan's claim keeps) - answers with that count instead of
     * opening a second count of the same stock, whose variance would correct the books a second time. So does a
     * second short line on the same bin, SKU and lot (or the same carton), or the same report again. A row of a
     * carton is counted as that carton: counted as a loose slot its book would read 0 and the whole carton would come
     * out as surplus. Either way the line expects the slot's book quantity - every loose layer of the bin, SKU and
     * lot, or the carton's rows - as a plan's line does, not the on-hand of the one layer the pick drew on.</p>
     *
     * <p>Reusing an <em>open</em> MANUAL count leaves the slot unfrozen until that count is submitted (only a
     * SHORT_PICK count freezes while open; a pending shortage freezes whatever the trigger): an accepted window in
     * which the doubted free units may be promised again (final review C1).</p>
     */
    @Override
    public com.stockflow.inventory.consumption.ShortPickRecount requestRecount(UUID reservationId, UUID pickLineId,
                                                                              int found) {
        Objects.requireNonNull(pickLineId, "pickLineId");
        StockItem item = stockItems.findIdByReservationId(ReservationId.of(reservationId))
                .flatMap(stockItems::findById)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No allocation with id " + reservationId));
        WarehouseScope.requireLocation(item.location().code());
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        var slot = item.stockPackageId() == null
                ? com.stockflow.inventory.internal.domain.CountSlot.loose(item.location(), item.sku(),
                        item.lotNumber())
                : com.stockflow.inventory.internal.domain.CountSlot.carton(item.location(), item.sku(),
                        item.lotNumber(), item.stockPackageId());
        var holding = cycleCounts.findHolding(slot);
        if (holding.isPresent()) {
            return new com.stockflow.inventory.consumption.ShortPickRecount(holding.get().id(), holding.get().number(),
                    false);
        }
        UUID warehouse = locations.warehouseOf(item.location()).orElseThrow(() ->
                new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + item.location()));
        var day = com.stockflow.common.domain.BusinessCalendar.date(clock.instant());
        int book = cycleCounts.bookQuantity(slot);
        var recount = cycleCounts.save(com.stockflow.inventory.internal.domain.CycleCount.shortPick(
                cycleCounts.nextNumber(day), warehouse, slot, book,
                "Short pick: line %s found %d of %s".formatted(pickLineId, found, hold.quantity()), day));
        log.info("Short pick at {} ({} {}): recount {} opened", item.location(), item.sku(),
                item.lotNumber() == null ? "" : item.lotNumber(), recount.number());
        return new com.stockflow.inventory.consumption.ShortPickRecount(recount.id(), recount.number(), true);
    }

    private void deduct(StockItem item, UUID reservationId, StockMovement.ReferenceType referenceType,
                        UUID referenceId, UUID actorId, StockPackageContents.LockedCartons locked) {
        Instant now = clock.instant();
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        StockStatus status = item.status();
        locked.tookFrom(item);
        item.consumeReservation(ReservationId.of(reservationId), now);
        stockItems.save(item);
        events.publishEventsOf(item);
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.PICK, item.sku(),
                item.lotNumber(), item.location(), null, hold.quantity().value(), status, referenceType,
                referenceId, null, actorId, now).ofLot(item.lotId()).forOrder(hold.orderId())
                .leaving(item.stockPackageId()));
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
