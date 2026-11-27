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
import com.stockflow.inventory.consumption.StagedLine;
import com.stockflow.inventory.consumption.StagedPick;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
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
 * reference {@code PICK_LINE} and the line's id is looked up under the carton's lock before anything
 * changes, and {@code uk_stock_movement_pick_line} refuses a second one should two transactions ever get
 * past that check. The hold's own status could not answer the question on its own — CONSUMED says the
 * hold was used, not by whom — which is how a second pick line pointing at the same hold is told apart
 * ({@code RESERVATION_NOT_ACTIVE}) from the same line confirmed twice (answered as done).</p>
 *
 * <p><b>Locks.</b> Every method reads the carton of its hold unlocked, only to learn the SKU (an allocation never
 * changes carton), takes the SKU's advisory lock, then the carton ({@code SELECT … FOR UPDATE} plus a refresh), and
 * reads the hold again from the locked carton: the lock order every writer of a carton keeps. There is nothing to
 * lock after the carton - it is the stock (ADR-0011). The carton's lock is what serialises a pick against a
 * cancellation releasing the same hold: whichever commits second sees the hold closed. Callers that lock their own
 * rows (fulfillment's pick task, its package) take them before calling in here, and nothing takes a SKU lock and then
 * such a row.</p>
 *
 * <p>A pick takes its carton whole: consuming the hold takes the carton IN_STOCK → CONSUMED in the same step
 * ({@link StockPackage#consumeReservation}); a put-back brings that very carton back IN_STOCK, sealed
 * ({@link StockPackage#restock}).</p>
 *
 * <p><b>Staging (team plan 4C-5).</b> A task picked to a staging area moves the carton instead ({@link #stage}): the
 * carton goes to the staging area as it is - still AVAILABLE, its allocation still HELD - and one MOVE line names the
 * pick line. Nothing is deducted until the task moves to dispatch ({@link #consumeStaged}, the same deduction as a
 * pick, from where the carton stands). A cancelled order's staged carton is moved back to a bin ({@link #unstage}, a
 * second MOVE). These are the only moves into or out of a staging area: a person's move is refused there
 * ({@code StockOperationsServiceImpl.requireMovableBetween}), and so is any other ledger line
 * ({@code tg_stock_movement_staging}).</p>
 *
 * <p>Joins the caller's transaction ({@code REQUIRED}): the pick line and the deduction commit or roll
 * back together.</p>
 */
@Service
@Transactional(propagation = Propagation.REQUIRED)
class PickConsumptionService implements PickConsumption, StockConsumption {

    private static final Logger log = LoggerFactory.getLogger(PickConsumptionService.class);

    private final StockMovementLog ledger;
    private final InventoryEventPublisher events;
    private final LocationDirectory locations;
    private final InventoryPolicyRepository policies;
    private final StockPackageContents cartons;
    private final Clock clock;
    private final com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts;
    private final StockPackageRepository stockPackages;
    private final CartonPlacement placement;

    PickConsumptionService(StockMovementLog ledger, InventoryEventPublisher events,
                           LocationDirectory locations, InventoryPolicyRepository policies,
                           StockPackageContents cartons, Clock clock,
                           com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts,
                           StockPackageRepository stockPackages, CartonPlacement placement) {
        this.stockPackages = stockPackages;
        this.placement = placement;
        this.cycleCounts = cycleCounts;
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
        StockPackage carton = lockHolderOf(reservationId);
        Reservation hold = carton.findReservation(ReservationId.of(reservationId)).orElseThrow();
        requireOrderStock(carton, hold);
        LocationId at = carton.location();
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.PICK,
                StockMovement.ReferenceType.PICK_LINE, pickLineId);
        if (done.isPresent()) {
            return toConsumed(carton, at, hold, true);
        }
        deduct(carton, reservationId, StockMovement.ReferenceType.PICK_LINE, pickLineId, actorId);
        log.info("Pick line {} consumed hold {}: {} x {} off {}", pickLineId, reservationId,
                hold.quantity(), carton.sku(), at);
        return toConsumed(carton, at, hold, false);
    }

    /**
     * The carton of {@code reservationId}, read without a lock: enough to learn its SKU and where it stands before
     * the SKU lock is taken. An allocation is on one carton for as long as it exists.
     */
    private StockPackage cartonOf(UUID reservationId) {
        return stockPackages.findByReservationId(ReservationId.of(reservationId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + reservationId));
    }

    /** The carton {@code id}, locked and read fresh. Call under its SKU's lock. */
    private StockPackage lock(StockPackageId id) {
        return stockPackages.findByIdForUpdate(id).orElseThrow(() ->
                new IllegalStateException("No stock package " + id.value()));
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
        StockPackage carton = lockHolderOf(reservationId);
        Reservation hold = carton.findReservation(ReservationId.of(reservationId)).orElseThrow();
        requireOrderStock(carton, hold);
        deduct(carton, reservationId, StockMovement.ReferenceType.ORDER, hold.orderId(), actorId);
        log.info("Consumed reservation {} by hand: stock deducted for real", reservationId);
    }

    /**
     * A pick deducts an order's goods from a shelf, never goods a legacy print-shop carton still records
     * ({@code IN_PRODUCTION}, review of #109).
     */
    private static void requireOrderStock(StockPackage carton, Reservation hold) {
        if (carton.stockStatus() == StockStatus.IN_PRODUCTION) {
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
     * QUARANTINE area of the carton's own warehouse, and the carton QUARANTINE once back in stock.</p>
     */
    @Override
    public void putBackToQuarantine(PutBackCommand command) {
        restockPicked(command, true);
    }

    /**
     * A picked carton back into stock whole: AVAILABLE onto a bin, or QUARANTINE into a quarantine area. The status
     * is said here, by the operation (ADR-0011): a carton out of stock has none of its own, and a pick only ever took
     * AVAILABLE stock.
     */
    private void restockPicked(PutBackCommand command, boolean quarantine) {
        LocationId target = new LocationId(command.locationCode());
        // BR-SEC-002: the bin is in a warehouse the caller is assigned to. First, so a replay cannot tell
        // anything about another warehouse.
        WarehouseScope.requireLocation(target.code());
        // The carton the hold emptied, in the order every carton writer locks: the SKU here, then the carton FOR
        // UPDATE in StockPackageContents#restock. It is read once unlocked to learn the SKU; a pick line's hold never
        // changes carton.
        StockPackage cartonView = cartonOf(command.reservationId());
        policies.lock(cartonView.sku().code());
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
            UUID cartonWarehouse = locations.warehouseOf(cartonView.location()).orElseThrow(() ->
                    new IllegalStateException("Carton %s stands at an unknown location %s"
                            .formatted(command.lpn(), cartonView.location())));
            if (!targetWarehouse.equals(cartonWarehouse)) {
                throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                        "%s is in another warehouse than carton %s".formatted(target, command.lpn()));
            }
        }
        // Onto a bin, the carton must fit it (kltn-docs 07 BR-12, 06 BR-01); one that fits no bin goes to an OVERFLOW
        // area, which takes any size - and only then. A quarantine area is not judged.
        placement.requirePutBackTarget(target.code(), cartonView.id());
        if (!cartonView.lpn().equalsIgnoreCase(command.lpn().trim())) {
            // tg_pick_line_carton keeps a line's LPN its hold's carton: a mismatch is a caller's bug, not a request.
            throw new IllegalStateException("Pick line %s names carton %s; its hold is in %s"
                    .formatted(command.pickLineId(), command.lpn(), cartonView.lpn()));
        }
        Reservation consumed = cartonView.findReservation(ReservationId.of(command.reservationId())).orElseThrow();
        if (consumed.status() != com.stockflow.inventory.internal.domain.ReservationStatus.CONSUMED) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("Allocation %s is %s; only a whole carton "
                    + "taken by the pick goes back").formatted(command.reservationId(), consumed.status()));
        }
        // CONSUMED -> IN_STOCK, sealed, at the target, the same carton with its lot and FIFO date
        // (StockPackageContents#restock: INVALID_STOCK_PACKAGE_TRANSITION unless CONSUMED). It writes the carton at
        // once, so the carton stands at its new place before the ledger line names it there
        // (check_stock_movement_package).
        StockPackage carton = cartons.restock(cartonView.id(), target, quarantine);
        Instant now = clock.instant();
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.PUT_BACK,
                carton.sku(), carton.lotNumber(), null, target, carton.stockStatus(),
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId(), null, command.actorId(), now,
                carton.id())
                .ofLot(carton.lotId()).forOrder(consumed.orderId()));
        log.info("Pick line {} put back: carton {} ({} x {}) onto {} as {}", command.pickLineId(), carton.lpn(),
                consumed.quantity(), carton.sku(), target, carton.stockStatus());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks as a pick does: the SKU, then the carton {@code FOR UPDATE}. The replay is looked up under the SKU
     * lock: the line's first MOVE is its staging (a second is the put-back of {@link #unstage}). The carton moves
     * with its allocation still HELD and stays AVAILABLE ({@code tg_stock_package_held_available} wants a held carton
     * AVAILABLE; what keeps it out of every promise is where it stands, a staging area being no pickable
     * location).</p>
     */
    @Override
    public StagedPick stage(StageCommand command) {
        LocationId target = new LocationId(command.stagingLocationCode());
        WarehouseScope.requireLocation(target.code());
        StockPackage cartonView = cartonOf(command.reservationId());
        WarehouseScope.requireLocation(cartonView.location().code());
        policies.lock(cartonView.sku().code());
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.MOVE,
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId());
        if (done.isPresent()) {
            // The line records the carton, not a number: what was staged is the carton's standard count (ADR-0011).
            int staged = stockPackages.findAllById(List.of(done.get().stockPackage())).stream().findFirst()
                    .map(StockPackage::nominalQty).orElseThrow(() -> new IllegalStateException(
                            "No stock package " + done.get().stockPackage().value()));
            return new StagedPick(command.reservationId(), command.lpn(), cartonView.sku().code(),
                    staged, done.get().from().code(), done.get().to().code(), true);
        }
        requireStagingArea(cartonView.location(), target);
        StockPackage carton = lock(cartonView.id());
        Reservation hold = carton.findReservation(ReservationId.of(command.reservationId())).orElseThrow();
        requireOrderStock(carton, hold);
        if (!carton.lpn().equalsIgnoreCase(command.lpn().trim())) {
            // tg_pick_line_carton keeps a line's LPN its hold's carton: a mismatch is a caller's bug, not a request.
            throw new IllegalStateException("Pick line %s names carton %s; its hold is in %s"
                    .formatted(command.pickLineId(), command.lpn(), carton.lpn()));
        }
        if (hold.status() != com.stockflow.inventory.internal.domain.ReservationStatus.HELD) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Allocation %s is %s; only a held one is staged".formatted(command.reservationId(), hold.status()));
        }
        // A live hold is on a carton in stock (the aggregate's invariant), so only its stock status is left to ask.
        if (carton.stockStatus() != StockStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                    "Carton %s holds %s stock; only AVAILABLE stock is staged".formatted(carton.lpn(),
                            carton.stockStatus()));
        }
        LocationId from = carton.location();
        carton.moveTo(target);
        stockPackages.save(carton);
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE, carton.sku(),
                carton.lotNumber(), from, target, carton.stockStatus(), carton.stockStatus(),
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId(), null, command.actorId(), clock.instant(),
                carton.id())
                .ofLot(carton.lotId()).forOrder(hold.orderId()));
        log.info("Pick line {} staged carton {} ({} x {}) from {} to {}", command.pickLineId(), carton.lpn(),
                hold.quantity(), carton.sku(), from, target);
        return new StagedPick(command.reservationId(), carton.lpn(), carton.sku().code(), hold.quantity().value(),
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
        return consumeStaged(List.of(new StagedLine(reservationId, pickLineId)), actorId).getFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks once for every line (review M1): the SKUs of all the cartons, sorted, then all the cartons in id order
     * in one call - never per line, which would take the SKU locks in pick-line order (bin, then SKU) and could
     * deadlock with a release holding one of them and asking for another.</p>
     */
    @Override
    public List<ConsumedPick> consumeStaged(List<StagedLine> lines, UUID actorId) {
        java.util.Map<UUID, StockPackage> views = new java.util.LinkedHashMap<>();
        for (StagedLine line : lines) {
            stockPackages.findByReservationId(ReservationId.of(line.reservationId()))
                    .ifPresent(view -> views.put(line.reservationId(), view));
        }
        views.values().stream().map(view -> view.sku().code()).distinct().sorted().forEach(policies::lock);
        java.util.Map<StockPackageId, StockPackage> locked = new java.util.HashMap<>();
        stockPackages.findAllByIdForUpdate(views.values().stream().map(StockPackage::id).toList())
                .forEach(carton -> locked.put(carton.id(), carton));
        java.util.Map<UUID, ConsumedPick> done = new java.util.HashMap<>();
        for (StagedLine line : lines) {
            StockPackage view = views.get(line.reservationId());
            if (view == null) {
                throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + line.reservationId());
            }
            StockPackage carton = locked.get(view.id());
            if (carton == null) {
                throw new IllegalStateException("No stock package " + view.id().value());
            }
            WarehouseScope.requireLocation(carton.location().code());
            Reservation hold = carton.findReservation(ReservationId.of(line.reservationId())).orElseThrow();
            LocationId at = carton.location();
            if (ledger.findByReference(StockMovement.MovementType.PICK, StockMovement.ReferenceType.PICK_LINE,
                    line.pickLineId()).isPresent()) {
                done.put(line.pickLineId(), toConsumed(carton, at, hold, true));
                continue;
            }
            Optional<StockMovement> staged = ledger.findByReference(StockMovement.MovementType.MOVE,
                    StockMovement.ReferenceType.PICK_LINE, line.pickLineId());
            // Review M2: the carton must still be in stock where the line put it. One a count found missing (LOST) or that
            // left otherwise is a shortage of the order's goods (20 §4.9 step 4), reported on the line - never deducted
            // here as if it had shipped.
            if (staged.isPresent() && (!carton.inStock() || !carton.location().equals(staged.get().to()))) {
                throw new BusinessException(ErrorCode.CONFLICT, ("Pick line %s staged carton %s at %s, which is %s there "
                        + "now; report the line short instead of moving it to dispatch").formatted(line.pickLineId(),
                        carton.lpn(), staged.get().to(), carton.status() + " at " + carton.location()));
            }
            if (staged.isEmpty() || !locations.isStagingArea(at)) {
                throw new IllegalStateException("Pick line %s did not stage hold %s at %s".formatted(line.pickLineId(),
                        line.reservationId(), at));
            }
            requireOrderStock(carton, hold);
            deduct(carton, line.reservationId(), StockMovement.ReferenceType.PICK_LINE, line.pickLineId(), actorId);
            log.info("Pick line {} consumed staged hold {}: {} x {} off {}", line.pickLineId(), line.reservationId(),
                    hold.quantity(), carton.sku(), at);
            done.put(line.pickLineId(), toConsumed(carton, at, hold, false));
        }
        return lines.stream().map(line -> done.get(line.pickLineId())).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks as {@link #putBack}: the SKU, the carton {@code FOR UPDATE}. The line's MOVE lines say where it
     * stands: one, it is in the staging area it was moved to; two, it was put back already. The carton never left
     * stock, so it keeps the status it has.</p>
     */
    @Override
    public void unstage(PutBackCommand command) {
        LocationId target = new LocationId(command.locationCode());
        WarehouseScope.requireLocation(target.code());
        StockPackage cartonView = cartonOf(command.reservationId());
        policies.lock(cartonView.sku().code());
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
        // Onto a bin, the carton must fit it; one that fits no bin goes to OVERFLOW, and only then (07 BR-12).
        placement.requirePutBackTarget(target.code(), cartonView.id());
        StockPackage carton = lock(cartonView.id());
        Reservation hold = carton.findReservation(ReservationId.of(command.reservationId())).orElseThrow();
        if (!carton.lpn().equalsIgnoreCase(command.lpn().trim()) || !carton.location().equals(staging)) {
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
        stockPackages.save(carton);
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE, carton.sku(),
                carton.lotNumber(), staging, target, carton.stockStatus(), carton.stockStatus(),
                StockMovement.ReferenceType.PICK_LINE, command.pickLineId(), null, command.actorId(), clock.instant(),
                carton.id())
                .ofLot(carton.lotId()).forOrder(hold.orderId()));
        log.info("Pick line {} put back staged carton {} ({} x {}) from {} onto {}", command.pickLineId(),
                carton.lpn(), carton.unitsInStock(), carton.sku(), staging, target);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<String, List<String>> overflowAreasFor(UUID warehouseId, java.util.Collection<String> lpns) {
        if (lpns.isEmpty()) {
            return java.util.Map.of();
        }
        List<StockPackage> found = stockPackages.findByLpns(lpns);
        java.util.Set<UUID> nowhere = placement.fittingNoBin(warehouseId,
                found.stream().map(carton -> carton.id().value()).toList());
        List<String> areas = nowhere.isEmpty() ? List.of() : placement.overflowAreas(warehouseId);
        java.util.Map<String, List<String>> out = new java.util.HashMap<>();
        if (!areas.isEmpty()) {
            found.stream().filter(carton -> nowhere.contains(carton.id().value()))
                    .forEach(carton -> out.put(carton.lpn(), areas));
        }
        return out;
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
     * <p>One count per carton: a short pick on a carton another count holds - open, or submitted with its variance still
     * pending approval, whatever its trigger (the rule a plan's claim keeps) - answers with that count instead of
     * opening a second count of the same stock, whose variance would correct the books a second time. So does a
     * second short line on the same carton, or the same report again. The line counts the carton the hold is on
     * (kltn-docs 20 §4.2 f12: that very carton at that bin) and expects what the books say of it, as a plan's line
     * does.</p>
     *
     * <p>Reusing an <em>open</em> MANUAL count leaves the carton unfrozen until that count is submitted (only a
     * SHORT_PICK count freezes while open; a pending shortage freezes whatever the trigger): an accepted window in
     * which the doubted free cartons may be promised again (final review C1).</p>
     */
    @Override
    public com.stockflow.inventory.consumption.ShortPickRecount requestRecount(UUID reservationId, UUID pickLineId,
                                                                              int found) {
        Objects.requireNonNull(pickLineId, "pickLineId");
        StockPackage carton = stockPackages.findByReservationId(ReservationId.of(reservationId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No allocation with id " + reservationId));
        WarehouseScope.requireLocation(carton.location().code());
        Reservation hold = carton.findReservation(ReservationId.of(reservationId)).orElseThrow();
        var slot = com.stockflow.inventory.internal.domain.CountSlot.carton(carton.location(), carton.sku(),
                carton.lotNumber(), carton.id());
        var holding = cycleCounts.findHolding(slot);
        if (holding.isPresent()) {
            return new com.stockflow.inventory.consumption.ShortPickRecount(holding.get().id(), holding.get().number(),
                    false);
        }
        UUID warehouse = locations.warehouseOf(carton.location()).orElseThrow(() ->
                new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + carton.location()));
        var day = com.stockflow.common.domain.BusinessCalendar.date(clock.instant());
        boolean book = cycleCounts.booked(slot);
        var recount = cycleCounts.save(com.stockflow.inventory.internal.domain.CycleCount.shortPick(
                cycleCounts.nextNumber(day), warehouse, slot, book,
                "Short pick: line %s found %d of %s".formatted(pickLineId, found, hold.quantity()), day));
        log.info("Short pick at {} ({} {}): recount {} opened", carton.location(), carton.sku(),
                carton.lotNumber() == null ? "" : carton.lotNumber(), recount.number());
        return new com.stockflow.inventory.consumption.ShortPickRecount(recount.id(), recount.number(), true);
    }

    /**
     * The hold becomes a deduction and the carton leaves stock with it; one PICK ledger line, from where the carton
     * stood, in the status it had. The carton is the caller's locked copy.
     */
    private void deduct(StockPackage carton, UUID reservationId, StockMovement.ReferenceType referenceType,
                        UUID referenceId, UUID actorId) {
        Instant now = clock.instant();
        Reservation hold = carton.findReservation(ReservationId.of(reservationId)).orElseThrow();
        StockStatus status = carton.stockStatus();
        LocationId from = carton.location();
        carton.consumeReservation(ReservationId.of(reservationId), now);
        stockPackages.save(carton);
        events.publishEventsOf(carton);
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.PICK, carton.sku(),
                carton.lotNumber(), from, null, status, referenceType,
                referenceId, null, actorId, now, carton.id()).ofLot(carton.lotId())
                .forOrder(hold.orderId()));
    }

    /**
     * The carton holding {@code reservationId}, locked under its SKU's lock. A warehouse-bound caller reaches only
     * holds in their own warehouses (BR-SEC-002); the system is not limited.
     */
    private StockPackage lockHolderOf(UUID reservationId) {
        StockPackage view = cartonOf(reservationId);
        policies.lock(view.sku().code());
        StockPackage carton = lock(view.id());
        WarehouseScope.requireLocation(carton.location().code());
        return carton;
    }

    /** {@code at}: where the carton stood when it was (or is to be) taken; a consumed carton keeps its last place. */
    private static ConsumedPick toConsumed(StockPackage carton, LocationId at, Reservation hold, boolean already) {
        return new ConsumedPick(hold.id().value(), carton.sku().code(), at.code(), carton.lotNumber(),
                hold.quantity().value(), already);
    }
}
