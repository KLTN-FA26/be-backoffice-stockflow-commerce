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
import com.stockflow.inventory.consumption.StageCommand;
import com.stockflow.inventory.consumption.StagedPick;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
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
 * <p><b>Staging (team plan 4C-5).</b> A task picked to a staging area moves the carton instead ({@link #stage}): the
 * carton and its one row go to the staging area as they are, the allocation on the row with them, and one MOVE line
 * names the pick line. Nothing is deducted until the task moves to dispatch ({@link #consumeStaged}, the same deduction
 * as a pick, from where the carton stands). A cancelled order's staged carton is moved back to a bin
 * ({@link #unstage}, a second MOVE). These are the only moves into or out of a staging area: a person's move is refused
 * there ({@code StockOperationsServiceImpl.requireMovableBetween}), and so is any other ledger line
 * ({@code tg_stock_movement_staging}).</p>
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
        restockPicked(command, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same path as {@link #putBack}, locks and replay included; only the target and the status differ: a
     * QUARANTINE area of the carton's own warehouse, and the row's units QUARANTINE once back in it.</p>
     */
    @Override
    public void putBackToQuarantine(PutBackCommand command) {
        restockPicked(command, true);
    }

    /** A picked carton back into stock whole: AVAILABLE onto a bin, or QUARANTINE into a quarantine area. */
    private void restockPicked(PutBackCommand command, boolean quarantine) {
        LocationId target = new LocationId(command.locationCode());
        // BR-SEC-002: the bin is in a warehouse the caller is assigned to. First, so a replay cannot tell
        // anything about another warehouse.
        WarehouseScope.requireLocation(target.code());
        // The carton the hold emptied, in the order every carton writer locks (V20261020000100): the SKU here, then the
        // carton FOR UPDATE and its row by id in StockPackageContents#restock. The row is read once unlocked to learn
        // the SKU and the carton; a pick line's hold never changes row once consumed.
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
        if (quarantine) {
            // 08 §4.2 step 4: a damaged carton is kept apart from good stock, in its own warehouse (stock never changes
            // warehouse, 11 BR-01).
            if (!locations.isQuarantineArea(target)) {
                throw new BusinessException(ErrorCode.LOCATION_AREA_MISMATCH,
                        "%s is not a QUARANTINE area; a damaged carton goes to one".formatted(target));
            }
            // Both resolved: two unknown places must not pass as "the same warehouse".
            UUID targetWarehouse = locations.warehouseOf(target).orElseThrow(() ->
                    new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + target));
            UUID cartonWarehouse = locations.warehouseOf(rowView.location()).orElseThrow(() ->
                    new IllegalStateException("Carton %s stands at an unknown location %s"
                            .formatted(command.lpn(), rowView.location())));
            if (!targetWarehouse.equals(cartonWarehouse)) {
                throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                        "%s is in another warehouse than carton %s".formatted(target, command.lpn()));
            }
        }
        // Only a whole carton comes back (team plan 4C-4; 07 §4.5 step 14): never loose units - a line picked loose
        // before 4C-1, or a carton some of whose units went to quarantine damaged at packing (R8). V20261113000400
        // stopped on any such put-back still waiting, and fulfillment refuses the second kind first; 4C-2's loose
        // fallback (StockItem#putBackBeside) is gone with them.
        if (rowView.stockPackageId() == null) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("Pick line %s took loose stock; only a "
                    + "whole carton is put back (kltn-docs 07 §4.5 step 14)").formatted(command.pickLineId()));
        }
        StockPackage cartonView = stockPackages.findById(rowView.stockPackageId()).orElseThrow(() ->
                new IllegalStateException("No stock package " + rowView.stockPackageId().value()));
        if (!cartonView.lpn().equalsIgnoreCase(command.lpn().trim())) {
            // tg_pick_line_carton keeps a line's LPN its hold's carton: a mismatch is a caller's bug, not a request.
            throw new IllegalStateException("Pick line %s names carton %s; its hold is in %s"
                    .formatted(command.pickLineId(), command.lpn(), cartonView.lpn()));
        }
        Reservation consumed = rowView.findReservation(ReservationId.of(command.reservationId())).orElseThrow();
        int damagedAtPacking = damageReports.damagedAfterPick(command.reservationId());
        if (consumed.status() != com.stockflow.inventory.internal.domain.ReservationStatus.CONSUMED
                || damagedAtPacking > 0) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("Allocation %s is %s, %d of its units "
                    + "found damaged at packing; only a whole carton taken by the pick goes back").formatted(
                    command.reservationId(), consumed.status(), damagedAtPacking));
        }
        // CONSUMED -> IN_STOCK, sealed, at the bin, its row following it with its units back as the same receipt layer
        // (4C-2's StockPackageContents#restock: STOCK_PACKAGE_TAKEN_WHOLE unless the hold is what was counted into the
        // carton, INVALID_STOCK_PACKAGE_TRANSITION unless CONSUMED). It saves the carton before the row, so the carton
        // stands at its new place before the ledger line names it there (check_stock_movement_package).
        StockItem row = cartons.restock(rowView.stockPackageId(), rowView.id(), target, consumed.quantity().value(),
                quarantine);
        Instant now = clock.instant();
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.PUT_BACK,
                row.sku(), row.lotNumber(), null, target, consumed.quantity().value(), row.status(),
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId(), null, command.actorId(), now)
                .ofLot(row.lotId()).forOrder(consumed.orderId()).arriving(row.stockPackageId()));
        log.info("Pick line {} put back: carton {} ({} x {}) onto {} as {}", command.pickLineId(), cartonView.lpn(),
                consumed.quantity(), row.sku(), target, row.status());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks as a pick does: the SKU, then the carton {@code FOR UPDATE}, then its row by id. The replay is looked up
     * under the SKU lock: the line's first MOVE is its staging (a second is the put-back of {@link #unstage}). The
     * allocation must be the carton's whole content - its only row, all of it held - since the carton moves with
     * whatever is in it and must not take units of nobody's order into the staging area.</p>
     */
    @Override
    public StagedPick stage(StageCommand command) {
        LocationId target = new LocationId(command.stagingLocationCode());
        WarehouseScope.requireLocation(target.code());
        StockItem rowView = stockItems.findIdByReservationId(ReservationId.of(command.reservationId()))
                .flatMap(stockItems::findById)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + command.reservationId()));
        WarehouseScope.requireLocation(rowView.location().code());
        policies.lock(rowView.sku().code());
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.MOVE,
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId());
        if (done.isPresent()) {
            return new StagedPick(command.reservationId(), command.lpn(), rowView.sku().code(),
                    done.get().quantity(), done.get().from().code(), done.get().to().code(), true);
        }
        requireStagingArea(rowView.location(), target);
        if (rowView.stockPackageId() == null) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("Pick line %s holds loose stock; only a "
                    + "whole carton is staged").formatted(command.pickLineId()));
        }
        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(rowView.stockPackageId())).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "No stock package " + rowView.stockPackageId().value()));
        StockItem row = stockItems.findByIdForUpdate(rowView.id()).orElseThrow(() ->
                new IllegalStateException("Stock item %s vanished mid staging".formatted(rowView.id())));
        Reservation hold = row.findReservation(ReservationId.of(command.reservationId())).orElseThrow();
        requireOrderStock(row, hold);
        if (!carton.lpn().equalsIgnoreCase(command.lpn().trim())) {
            // tg_pick_line_carton keeps a line's LPN its hold's carton: a mismatch is a caller's bug, not a request.
            throw new IllegalStateException("Pick line %s names carton %s; its hold is in %s"
                    .formatted(command.pickLineId(), command.lpn(), carton.lpn()));
        }
        if (hold.status() != com.stockflow.inventory.internal.domain.ReservationStatus.HELD) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Allocation %s is %s; only a held one is staged".formatted(command.reservationId(), hold.status()));
        }
        if (row.status() != StockStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                    "Carton %s holds %s stock; only AVAILABLE stock is staged".formatted(carton.lpn(), row.status()));
        }
        int rows = stockItems.findIdsInStockPackage(carton.id()).size();
        if (carton.status() != com.stockflow.inventory.internal.domain.StockPackageStatus.IN_STOCK || rows != 1
                || hold.quantity().value() != row.onHand().value()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("Allocation %s holds %s of carton %s "
                    + "(%s, %d row(s), %s on hand); only an allocation of the whole carton in stock is staged")
                    .formatted(command.reservationId(), hold.quantity(), carton.lpn(), carton.status(), rows,
                            row.onHand()));
        }
        LocationId from = row.location();
        carton.moveTo(target);
        row.followPackageTo(target);
        stockItems.save(row);
        stockPackages.save(carton);
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE, row.sku(),
                row.lotNumber(), from, target, row.onHand().value(), row.status(), row.status(),
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId(), null, command.actorId(), clock.instant())
                .ofLot(row.lotId()).forOrder(hold.orderId()).leaving(carton.id()).arriving(carton.id()));
        log.info("Pick line {} staged carton {} ({} x {}) from {} to {}", command.pickLineId(), carton.lpn(),
                hold.quantity(), row.sku(), from, target);
        return new StagedPick(command.reservationId(), carton.lpn(), row.sku().code(), hold.quantity().value(),
                from.code(), target.code(), false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link #consumeForPick}, after making sure the carton is where this line staged it: a deduction from a bin
     * here would be a pick nobody scanned.</p>
     */
    @Override
    public ConsumedPick consumeStaged(UUID reservationId, UUID pickLineId, UUID actorId) {
        Objects.requireNonNull(pickLineId, "pickLineId");
        StockPackageContents.LockedCartons locked = lockSkusAndCartons(List.of(reservationId));
        StockItem item = holderOf(reservationId);
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        if (ledger.findByReference(StockMovement.MovementType.PICK, StockMovement.ReferenceType.PICK_LINE,
                pickLineId).isPresent()) {
            return toConsumed(item, hold, true);
        }
        Optional<StockMovement> staged = ledger.findByReference(StockMovement.MovementType.MOVE,
                StockMovement.ReferenceType.PICK_LINE, pickLineId);
        if (staged.isEmpty() || !staged.get().to().equals(item.location()) || !locations.isStagingArea(item.location())) {
            throw new IllegalStateException("Pick line %s did not stage hold %s at %s".formatted(pickLineId,
                    reservationId, item.location()));
        }
        requireOrderStock(item, hold);
        deduct(item, reservationId, StockMovement.ReferenceType.PICK_LINE, pickLineId, actorId, locked);
        cartons.settle(locked, clock.instant());
        log.info("Pick line {} consumed staged hold {}: {} x {} off {}", pickLineId, reservationId, hold.quantity(),
                item.sku(), item.location());
        return toConsumed(item, hold, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks as {@link #putBack}: the SKU, the carton {@code FOR UPDATE}, its row. The line's MOVE lines say where it
     * stands: one, it is in the staging area it was moved to; two, it was put back already.</p>
     */
    @Override
    public void unstage(PutBackCommand command) {
        LocationId target = new LocationId(command.locationCode());
        WarehouseScope.requireLocation(target.code());
        StockItem rowView = stockItems.findIdByReservationId(ReservationId.of(command.reservationId()))
                .flatMap(stockItems::findById)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + command.reservationId()));
        policies.lock(rowView.sku().code());
        List<StockMovement> moves = ledger.findAllByReference(StockMovement.MovementType.MOVE,
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId());
        if (moves.isEmpty()) {
            throw new IllegalStateException("Pick line %s never staged its carton".formatted(command.pickLineId()));
        }
        if (moves.size() > 1) {
            return;
        }
        LocationId staging = moves.get(0).to();
        switch (locations.stateOf(target)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + target);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(target));
            case USABLE -> { }
        }
        if (!staging.warehouseCode().equals(target.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s and %s are in different warehouses; stock moves only inside one".formatted(staging, target));
        }
        if (target.equals(staging) || locations.isStagingArea(target)) {
            throw new BusinessException(ErrorCode.STAGING_MOVE_NOT_ALLOWED,
                    "A staged carton is put back onto a bin, not into a staging area (%s)".formatted(target));
        }
        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(rowView.stockPackageId())).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "No stock package " + rowView.stockPackageId().value()));
        StockItem row = stockItems.findByIdForUpdate(rowView.id()).orElseThrow(() ->
                new IllegalStateException("Stock item %s vanished mid put-back".formatted(rowView.id())));
        Reservation hold = row.findReservation(ReservationId.of(command.reservationId())).orElseThrow();
        if (!carton.lpn().equalsIgnoreCase(command.lpn().trim()) || !carton.location().equals(staging)
                || !row.location().equals(staging)) {
            throw new IllegalStateException("Pick line %s staged carton %s at %s; it is %s at %s".formatted(
                    command.pickLineId(), command.lpn(), staging, carton.lpn(), carton.location()));
        }
        if (hold.status() == com.stockflow.inventory.internal.domain.ReservationStatus.HELD) {
            // The cancellation releases the order's allocations before fulfillment cancels the task; still held, the
            // order owns the carton and it stays where its task put it.
            throw new BusinessException(ErrorCode.CONFLICT, ("Carton %s is still held for its order (allocation %s); "
                    + "it leaves the staging area by dispatch").formatted(carton.lpn(), command.reservationId()));
        }
        carton.moveTo(target);
        row.followPackageTo(target);
        stockItems.save(row);
        stockPackages.save(carton);
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE, row.sku(),
                row.lotNumber(), staging, target, row.onHand().value(), row.status(), row.status(),
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId(), null, command.actorId(), clock.instant())
                .ofLot(row.lotId()).forOrder(hold.orderId()).leaving(carton.id()).arriving(carton.id()));
        log.info("Pick line {} put back staged carton {} ({} x {}) from {} onto {}", command.pickLineId(),
                carton.lpn(), row.onHand(), row.sku(), staging, target);
    }

    /**
     * The staging area a carton goes to: a usable STAGING area of the carton's own warehouse. The task named it when it
     * was created; this checks it again, under the SKU lock, as the moment the carton actually moves.
     */
    private void requireStagingArea(LocationId from, LocationId target) {
        switch (locations.stateOf(target)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + target);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it or its warehouse is not active".formatted(target));
            case USABLE -> { }
        }
        if (!locations.isStagingArea(target)) {
            throw new BusinessException(ErrorCode.STAGING_AREA_INVALID, "%s is not a staging area".formatted(target));
        }
        if (!from.warehouseCode().equals(target.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s and %s are in different warehouses; stock moves only inside one".formatted(from, target));
        }
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
