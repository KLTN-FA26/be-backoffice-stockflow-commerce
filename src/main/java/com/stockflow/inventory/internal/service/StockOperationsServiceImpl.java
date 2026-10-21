package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.IssueReservedStockResult;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.internal.domain.AdjustmentReason;
import com.stockflow.inventory.internal.domain.AdjustmentStatus;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockAdjustment;
import com.stockflow.inventory.internal.domain.StockAdjustmentRepository;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.StockAdjustmentSearch;
import com.stockflow.inventory.internal.repository.StockLedgerSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Moves stock between locations and posts approved adjustments, writing the ledger as it goes.
 *
 * <p>Every change of {@code onHand} made here leaves exactly one {@code inventory.stock_movement}
 * line, in the same transaction — the history screen and the stock figure can never disagree.</p>
 */
@Service
@Transactional
class StockOperationsServiceImpl implements StockOperations {

    private static final Logger log = LoggerFactory.getLogger(StockOperationsServiceImpl.class);

    /** Document numbers follow the business's calendar, not UTC: ADJ-20261008 is the 8th in Saigon. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final SortWhitelist ADJUSTMENT_SORT = SortWhitelist.of("createdAt", "status", "sku")
            .withDefault("createdAt", Sort.Direction.DESC);

    private final StockItemRepository stockItems;
    private final StockAdjustmentRepository adjustments;
    private final StockAdjustmentSearch adjustmentSearch;
    private final StockMovementLog ledger;
    private final StockLedgerSearch ledgerSearch;
    private final LocationDirectory locations;
    private final Clock clock;
    private final InventoryPolicyRepository policies;
    /** Taking from a carton opens it and the last unit closes it; its lock comes between the SKU's and the rows'. */
    private final StockPackageContents cartons;

    StockOperationsServiceImpl(StockItemRepository stockItems, StockAdjustmentRepository adjustments,
                               StockAdjustmentSearch adjustmentSearch, StockMovementLog ledger,
                               StockLedgerSearch ledgerSearch, LocationDirectory locations, Clock clock,
                               InventoryPolicyRepository policies, StockPackageContents cartons) {
        this.cartons = cartons;
        this.stockItems = stockItems;
        this.adjustments = adjustments;
        this.adjustmentSearch = adjustmentSearch;
        this.ledger = ledger;
        this.ledgerSearch = ledgerSearch;
        this.locations = locations;
        this.clock = clock;
        this.policies = policies;
    }

    // ------------------------------------------------------------------ move

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.requestId()")
    public StockMove move(MoveStockCommand command) {
        StockMovement.ReferenceType referenceType = StockMovement.ReferenceType.valueOf(command.reference().name());
        UUID referenceId = command.referenceId() != null ? command.referenceId() : command.requestId();
        return relocate(command.sku(), command.lotNumber(), command.receivedAt(), command.fromLocation(),
                command.toLocation(), command.quantity(), null, referenceType, referenceId, command.actorId(), null);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.referenceId()")
    public StockMove reclassify(ReclassifyStockCommand command) {
        return relocate(command.sku(), command.lotNumber(), command.receivedAt(), command.fromLocation(),
                command.toLocation(), command.quantity(), StockStatus.valueOf(command.disposition().name()),
                StockMovement.ReferenceType.valueOf(command.reference().name()), command.referenceId(),
                command.actorId(), blankToNull(command.reason()));
    }

    /**
     * The one implementation of moving stock between two locations, for a plain move ({@code target}
     * null: the status travels with the goods) and for a reclassification (the goods arrive as
     * {@code target}). {@code receivedAt} picks the stock layer when the source holds several.
     */
    private StockMove relocate(Sku sku, String lotNumber, Instant receivedAt, String fromCode, String toCode,
                               int qty, StockStatus target, StockMovement.ReferenceType referenceType,
                               UUID referenceId, UUID actorId, String reason) {
        // BR-SEC-002 (SCRUM-457): both ends in a warehouse the caller is assigned to. First, so a
        // replay cannot reveal a move made in someone else's warehouse.
        WarehouseScope.requireLocation(fromCode);
        WarehouseScope.requireLocation(toCode);
        // Match policy edits/reservations: take the SKU lock before any stock-row locks.
        policies.lock(sku.code());

        // Replay first: a retried move must not move the goods again.
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.MOVE, referenceType, referenceId);
        if (done.isPresent()) {
            return toMove(done.get());
        }

        LocationId from = new LocationId(fromCode);
        LocationId to = new LocationId(toCode);
        if (from.equals(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A move needs two different locations");
        }
        // Docs 11 BR-01: stock leaves its warehouse only on a transfer order (docs 10), which records the
        // dispatch, the transit and the receipt. A plain move between warehouses skipped all of that.
        if (!from.warehouseCode().equals(to.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s and %s are in different warehouses; use a transfer order".formatted(fromCode, toCode));
        }
        // Only the destination is checked (issue #67): taking stock out of a blocked bin or a shelf in
        // maintenance is how it gets emptied, and an adjustment at a bin blocked for counting must
        // still post.
        switch (locations.stateOf(to)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + to);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(to));
            case USABLE -> { }
        }
        Quantity quantity = Quantity.of(qty);

        StockItem sourceView = findLayer(sku, from, lotNumber, receivedAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ITEM_NOT_FOUND,
                        "No %s%s at %s".formatted(sku, lotSuffix(lotNumber), from)));
        Optional<StockItem> destinationView = findLayer(sku, to, lotNumber, sourceView.receivedAt())
                .filter(item -> java.util.Objects.equals(item.receivedAt(), sourceView.receivedAt()));

        // Lock in id order, as reserve() does: two moves crossing the same pair in opposite
        // directions would otherwise deadlock.
        List<StockItem> toLock = destinationView.isPresent()
                ? List.of(sourceView, destinationView.get()) : List.of(sourceView);
        StockItem source = null;
        StockItem destination = null;
        for (StockItem view : toLock.stream()
                .sorted(Comparator.comparing(item -> item.id().value().toString())).toList()) {
            StockItem locked = stockItems.findByIdForUpdate(view.id()).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-move".formatted(view.id())));
            if (locked.id().equals(sourceView.id())) {
                source = locked;
            } else {
                destination = locked;
            }
        }
        Objects.requireNonNull(source, "source");

        if (target != null && source.status() != StockStatus.INBOUND && source.status() != StockStatus.QUARANTINE) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                    "%s%s at %s is %s; only INBOUND or QUARANTINE stock takes a receiving decision".formatted(
                            sku, lotSuffix(lotNumber), from, source.status()));
        }
        StockStatus arriving = target != null ? target : source.status();
        if (destination != null && !destination.canReceiveFrom(source, arriving)) {
            throw new BusinessException(
                    destination.status() == arriving
                            ? ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT
                            : ErrorCode.STOCK_STATUS_MISMATCH,
                    "%s holds %s%s as %s; this stock would arrive as %s".formatted(to, sku,
                            lotSuffix(lotNumber), destination.status(), arriving));
        }

        StockStatus before = source.status();
        requireLoose(source);
        source.moveOut(quantity);
        stockItems.save(source);
        if (destination == null) {
            stockItems.save(StockItem.arrivedAs(source, to, quantity, arriving));
        } else {
            destination.moveIn(quantity);
            stockItems.saveMerged(destination);
        }

        Instant now = clock.instant();
        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE,
                sku, blankToNull(lotNumber), from, to, quantity.value(), before, arriving,
                referenceType, referenceId, reason, actorId, now);
        ledger.append(line);
        log.info("Moved {} x {}{} from {} to {} ({} -> {})", quantity, sku, lotSuffix(lotNumber), from, to,
                before, arriving);
        return toMove(line);
    }

    // ------------------------------------------------------------------ issue held goods

    /**
     * The hold moves whole, with its goods, under the same locks a move takes: the SKU lock first, then
     * both stock rows in id order. Only this method changes which stock row a hold is on, and it runs under
     * the SKU lock, so once the lock is held the hold's row read next is current. Nothing is released and
     * reserved again: the units are never free, so no checkout can take them on the way and
     * available-to-promise does not move.
     *
     * <p>A hold already at the destination is answered as it stands, without a second ledger line — the
     * replay of a scan whose first attempt committed.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.reservationId()")
    public IssueReservedStockResult issueReserved(IssueReservedStockCommand command) {
        // INBOUND: a subcontractor's goods, held for the child the moment they were counted in (holdReceived),
        // go on to the QUALITY_CONTROL area for production QC. Only a production hold can be on an INBOUND layer.
        return moveHold(command, java.util.EnumSet.of(StockStatus.AVAILABLE, StockStatus.INBOUND),
                StockStatus.IN_PRODUCTION);
    }

    /**
     * A hold and its goods from where they are to {@code command.toLocation()}, arriving as {@code arriving};
     * the goods must be {@code expected} now. Shared by the issue to the print shop and the hand-over of
     * finished goods to packing, which differ only in the two statuses.
     */
    private IssueReservedStockResult moveHold(IssueReservedStockCommand command, java.util.Set<StockStatus> expected,
                                              StockStatus arriving) {
        ReservationId reservationId = ReservationId.of(command.reservationId());
        StockItem first = holder(reservationId);
        WarehouseScope.requireLocation(command.toLocation());
        policies.lock(first.sku().code());

        // Current under the SKU lock: a concurrent issue of this hold has committed or not started.
        StockItem sourceView = holder(reservationId);
        WarehouseScope.requireLocation(sourceView.location().code());
        LocationId from = sourceView.location();
        LocationId to = new LocationId(command.toLocation());
        if (from.equals(to)) {
            StockItem here = stockItems.findByIdForUpdate(sourceView.id()).orElseThrow();
            Reservation hold = issuable(here, reservationId, command);
            return new IssueReservedStockResult(hold.id().value(), here.id().value(), here.sku().code(),
                    here.lotNumber(), to.code(), to.code(), hold.quantity().value(), false, null, clock.instant());
        }
        if (!from.warehouseCode().equals(to.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s and %s are in different warehouses; use a transfer order".formatted(from, to));
        }
        requireNotExpired(sourceView, to);
        switch (locations.stateOf(to)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + to);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(to));
            case USABLE -> { }
        }
        // The hold arrives as loose stock: never into a carton's row, whose counted contents it would change.
        Optional<StockItem> destinationView = sameLayerAt(sourceView, to);
        // A checkout hold may sit in a carton: it leaves it, which opens or empties it. Its lock comes before the rows'.
        StockPackageContents.LockedCartons lockedCartons = cartons.lockFor(List.of(sourceView));

        List<StockItem> toLock = destinationView.isPresent()
                ? List.of(sourceView, destinationView.get()) : List.of(sourceView);
        StockItem source = null;
        StockItem destination = null;
        for (StockItem view : toLock.stream()
                .sorted(Comparator.comparing(item -> item.id().value().toString())).toList()) {
            StockItem locked = stockItems.findByIdForUpdate(view.id()).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-issue".formatted(view.id())));
            if (locked.id().equals(sourceView.id())) {
                source = locked;
            } else {
                destination = locked;
            }
        }
        Objects.requireNonNull(source, "source");
        Reservation hold = issuable(source, reservationId, command);
        if (!expected.contains(source.status())) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH, "%s%s at %s is %s; this step takes %s stock"
                    .formatted(source.sku(), lotSuffix(source.lotNumber()), from, source.status(), expected));
        }
        if (destination != null && destination.status() != arriving) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH, "%s holds %s%s as %s; this stock arrives as %s"
                    .formatted(to, source.sku(), lotSuffix(source.lotNumber()), destination.status(), arriving));
        }
        if (destination == null) {
            destination = StockItem.emptyLayerOf(source, to, arriving);
        }
        lockedCartons.tookFrom(source);
        source.handOverReservation(reservationId);
        destination.takeOverReservation(source, hold);
        stockItems.saveHandOver(source, destination, reservationId);

        Instant now = clock.instant();
        cartons.settle(lockedCartons, now);
        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE,
                source.sku(), source.lotNumber(), from, to, hold.quantity().value(), source.status(), arriving,
                StockMovement.ReferenceType.PRODUCTION_ORDER, command.productionOrderId(),
                "Hold " + hold.id().value(), command.actorId(), now).leaving(source.stockPackageId());
        ledger.append(line);
        log.info("Issued hold {} ({} x {}{}) from {} to {} for production order {}", hold.id().value(),
                hold.quantity(), source.sku(), lotSuffix(source.lotNumber()), from, to, command.productionOrderId());
        return new IssueReservedStockResult(hold.id().value(), destination.id().value(), source.sku().code(),
                source.lotNumber(), from.code(), to.code(), hold.quantity().value(), true, line.id(), now);
    }

    /**
     * Review of #109, item 8: goods past their expiry land nowhere new - not in the print shop, not back in storage,
     * not in packing. The policy trigger would refuse the layer with a generic "Invalid receipt/expiry date"; this
     * says what happened and what to do instead, before anything is written: write them off (EXPIRED), which
     * production does for unprinted blanks it gives back, and QC does for printed goods it fails.
     */
    private void requireNotExpired(StockItem source, LocationId to) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
        if (com.stockflow.inventory.internal.domain.ExpiryPolicy.isExpired(source.expiryDate(), today)) {
            throw new BusinessException(ErrorCode.STOCK_EXPIRED, ("%s%s expired on %s and cannot move to %s; write it off "
                    + "instead (adjustment reason EXPIRED)").formatted(source.sku(), lotSuffix(source.lotNumber()),
                    source.expiryDate(), to));
        }
    }

    /**
     * BR-PRD-10 under the locks every write-off takes - the SKU's, then the row's. Asked once per hold: a standing
     * write-off is answered as it is. With nobody to ask in the name of (a cancellation by the system), the goods
     * stay held and isolated - {@link #isFinishedCustomGoods} keeps every release off them - and nothing is asked.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#reservationId")
    public Optional<StockAdjustmentSummary> scrapCancelledPrint(UUID reservationId, UUID orderId, String note,
                                                                UUID requestedBy) {
        ReservationId id = ReservationId.of(reservationId);
        Optional<StockItem> first = stockItems.findIdByReservationId(id).flatMap(stockItems::findById);
        if (first.isEmpty()) {
            return Optional.empty();
        }
        policies.lock(first.get().sku().code());
        StockItem view = holder(id);
        WarehouseScope.requireLocation(view.location().code());
        StockItem item = stockItems.findByIdForUpdate(view.id()).orElseThrow();
        Reservation hold = item.findReservation(id).orElseThrow();
        if (!Objects.equals(orderId, hold.orderId()) || !isFinishedCustomGoods(item, hold)) {
            return Optional.empty();
        }
        Optional<StockAdjustment> asked = adjustments.findLiveForHold(reservationId);
        if (asked.isPresent()) {
            return asked.map(StockOperationsServiceImpl::toSummary);
        }
        if (requestedBy == null) {
            log.warn("Hold {}: {} x {} printed for cancelled order {} kept in {}, out of sale; nobody to ask the "
                    + "write-off in the name of", reservationId, hold.quantity(), item.sku(), orderId, item.location());
            return Optional.empty();
        }
        Instant now = clock.instant();
        StockAdjustment adjustment = StockAdjustment.request(adjustments.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)),
                        item.location(), item.sku(), item.lotNumber(), -hold.quantity().value(), AdjustmentReason.SCRAP,
                        note, requestedBy, now)
                .atLayer(item.receivedAt()).forHold(reservationId);
        StockAdjustmentSummary saved = toSummary(adjustments.save(adjustment));
        log.info("Hold {}: {} x {} printed for cancelled order {} kept in {}; SCRAP write-off {} asked for", reservationId,
                hold.quantity(), item.sku(), orderId, item.location(), saved.number());
        return Optional.of(saved);
    }

    /**
     * Only a hold a cancellation already asked to scrap is asked again here: inventory cannot tell a cancelled order
     * from a live one, and a live order's goods are nobody's to scrap. That first write-off - which only
     * {@link #scrapCancelledPrint} writes against a hold - is the proof.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#reservationId")
    public StockAdjustmentSummary askScrapAgain(UUID reservationId, String note, UUID requestedBy) {
        StockAdjustment last = adjustments.findLatestForHold(reservationId).orElseThrow(() -> new BusinessException(
                ErrorCode.CONFLICT, "Hold %s was never asked to be scrapped; only a cancelled order's printed goods are"
                        .formatted(reservationId)));
        // BR-SEC-002 before anything is answered: another warehouse's write-off is not this caller's to read.
        WarehouseScope.requireLocation(last.location().code());
        if (!last.status().isClosed() || last.status() == com.stockflow.inventory.internal.domain.AdjustmentStatus.POSTED) {
            return toSummary(last);
        }
        UUID orderId = holder(ReservationId.of(reservationId)).findReservation(ReservationId.of(reservationId))
                .map(Reservation::orderId).orElse(null);
        return scrapCancelledPrint(reservationId, orderId, note, requestedBy).orElseThrow(() -> new BusinessException(
                ErrorCode.CONFLICT, "Hold %s is no longer printed goods waiting in packing".formatted(reservationId)));
    }

    /**
     * Printed goods of the order waiting in packing: a live order hold on AVAILABLE goods in a PACKING area that
     * production handed there (audit of #109: by location alone, free stock somebody moved into a packing area and a
     * checkout then held would be kept from its release for ever, and never scrapped either).
     */
    @Override
    public boolean isFinishedCustomGoods(StockItem item, Reservation hold) {
        return hold.status().isActive() && hold.orderId() != null && hold.productionOrderId() == null
                && item.status() == StockStatus.AVAILABLE && locations.isPackingArea(item.location())
                && ledger.handedOverFromProduction(hold.id(), item.location());
    }

    /** The same hand-over the other way: finished goods, still held, from the print shop to packing. */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.reservationId()")
    public IssueReservedStockResult finishHeld(IssueReservedStockCommand command) {
        return moveHold(command, java.util.EnumSet.of(StockStatus.IN_PRODUCTION), StockStatus.AVAILABLE);
    }

    /** Unprinted goods of a cancelled print job, still held for their order, back to storage with the hold. */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.reservationId()")
    public IssueReservedStockResult unissueHeld(IssueReservedStockCommand command) {
        return moveHold(command, java.util.EnumSet.of(StockStatus.IN_PRODUCTION), StockStatus.AVAILABLE);
    }

    /**
     * The receipt's own loose layer, found by its identity (SKU, location, lot, receipt time), locked after the SKU
     * lock as a move takes it; the hold is pinned and named by the caller's request id, so a retried receipt event
     * holds nothing twice. A receipt counted into cartons (#143) names its stock rows instead.
     */
    @Override
    public com.stockflow.inventory.api.StockReservation holdReceived(com.stockflow.inventory.api.HoldReceivedStockCommand command) {
        policies.lock(command.sku().code());
        LocationId location = new LocationId(command.locationCode());
        String lot = blankToNull(command.lotNumber());
        StockItem view = stockItems.findLooseBySkuAndLocation(command.sku(), location).stream()
                .filter(item -> Objects.equals(item.lotNumber(), lot)
                        && command.receivedAt().equals(item.receivedAt()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ITEM_NOT_FOUND,
                        "No %s%s received at %s at %s".formatted(command.sku(), lotSuffix(lot), location,
                                command.receivedAt())));
        StockItem item = stockItems.findByIdForUpdate(view.id()).orElseThrow();
        Reservation hold = item.holdArrival(command.productionOrderId(), command.requestId(), command.requestId(),
                Quantity.of(command.quantity()), clock.instant());
        stockItems.save(item);
        log.info("{} x {} received at {} held for production order {}", command.quantity(), command.sku(), location,
                command.productionOrderId());
        return new com.stockflow.inventory.api.StockReservation(hold.id().value(), item.id().value(),
                item.location().code(), item.lotNumber(), hold.quantity().value());
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.reservationId()")
    public StockMove consumeHeld(com.stockflow.inventory.api.ConsumeHeldStockCommand command) {
        ReservationId reservationId = ReservationId.of(command.reservationId());
        policies.lock(holder(reservationId).sku().code());
        StockItem view = holder(reservationId);
        WarehouseScope.requireLocation(view.location().code());
        StockPackageContents.LockedCartons locked = cartons.lockFor(List.of(view));
        StockItem item = stockItems.findByIdForUpdate(view.id()).orElseThrow();
        Reservation hold = owned(item, reservationId, command.orderId(), command.productionOrderId());
        if (!hold.status().isActive() || hold.quantity().isLessThan(Quantity.of(command.quantity()))) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE, "Hold %s (%s, %s) cannot give %d"
                    .formatted(reservationId.value(), hold.status(), hold.quantity(), command.quantity()));
        }
        requireInPrintShop(item);
        Instant now = clock.instant();
        locked.tookFrom(item);
        item.consumeFromHold(reservationId, Quantity.of(command.quantity()), now);
        stockItems.save(item);
        cartons.settle(locked, now);
        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.CONSUMPTION, item.sku(),
                item.lotNumber(), item.location(), null, command.quantity(), item.status(),
                StockMovement.ReferenceType.PRODUCTION_ORDER, command.productionOrderId(),
                command.note() == null ? "Used by the subcontractor" : command.note(), command.actorId(), now)
                .leaving(item.stockPackageId());
        ledger.append(line);
        log.info("{} x {} consumed at {} out of hold {}", command.quantity(), item.sku(), item.location(),
                hold.id().value());
        return toMove(line);
    }

    /**
     * The units leave the hold in the same transaction that asks for their write-off, under the SKU lock
     * and the row lock: between the two nobody could have used them, and after it they are covered by no hold
     * and not sellable (IN_PRODUCTION), so the approval later finds them, deducts them once, and a rejection
     * puts nothing back on sale.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#result?.adjustmentId()")
    public StockAdjustmentSummary writeOffHeld(com.stockflow.inventory.api.WriteOffHeldStockCommand command) {
        ReservationId reservationId = ReservationId.of(command.reservationId());
        policies.lock(holder(reservationId).sku().code());
        StockItem view = holder(reservationId);
        WarehouseScope.requireLocation(view.location().code());
        StockItem item = stockItems.findByIdForUpdate(view.id()).orElseThrow();
        Reservation hold = owned(item, reservationId, command.orderId(), command.productionOrderId());
        requireHeldForProduction(item);
        Quantity quantity = Quantity.of(command.quantity());
        Instant now = clock.instant();
        takeFromHold(item, hold, quantity, ReleaseReason.SCRAPPED, now);
        stockItems.save(item);
        StockAdjustment adjustment = StockAdjustment.request(adjustments.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)),
                        item.location(), item.sku(), item.lotNumber(), -quantity.value(),
                        AdjustmentReason.valueOf(command.reason().name()), command.note(), command.requestedBy(), now)
                .atLayer(item.receivedAt());
        log.info("{} x {} in {} out of hold {}; {} write-off {} asked for", quantity, item.sku(), item.location(),
                hold.id().value(), command.reason(), adjustment.number());
        return toSummary(adjustments.save(adjustment));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.reservationId()")
    public StockMove returnHeld(com.stockflow.inventory.api.ReturnHeldStockCommand command) {
        ReservationId reservationId = ReservationId.of(command.reservationId());
        WarehouseScope.requireLocation(command.toLocation());
        policies.lock(holder(reservationId).sku().code());
        StockItem sourceView = holder(reservationId);
        WarehouseScope.requireLocation(sourceView.location().code());
        LocationId from = sourceView.location();
        LocationId to = new LocationId(command.toLocation());
        if (from.equals(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A move needs two different locations");
        }
        if (!from.warehouseCode().equals(to.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s and %s are in different warehouses; use a transfer order".formatted(from, to));
        }
        requireNotExpired(sourceView, to);
        switch (locations.stateOf(to)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + to);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(to));
            case USABLE -> { }
        }
        Optional<StockItem> destinationView = sameLayerAt(sourceView, to);
        StockPackageContents.LockedCartons lockedCartons = cartons.lockFor(List.of(sourceView));
        StockItem[] locked = lockPair(sourceView, destinationView);
        StockItem source = locked[0];
        StockItem destination = locked[1];
        Reservation hold = owned(source, reservationId, command.orderId(), command.productionOrderId());
        requireInPrintShop(source);
        if (destination != null && destination.status() != StockStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH, "%s holds %s%s as %s; blanks come back AVAILABLE"
                    .formatted(to, source.sku(), lotSuffix(source.lotNumber()), destination.status()));
        }
        Quantity quantity = Quantity.of(command.quantity());
        Instant now = clock.instant();
        takeFromHold(source, hold, quantity, ReleaseReason.RETURNED_FROM_PRODUCTION, now);
        lockedCartons.tookFrom(source);
        source.moveOut(quantity);
        stockItems.save(source);
        if (destination == null) {
            stockItems.save(StockItem.arrivedAs(source, to, quantity, StockStatus.AVAILABLE));
        } else {
            destination.moveIn(quantity);
            stockItems.saveMerged(destination);
        }
        cartons.settle(lockedCartons, now);
        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE, source.sku(),
                source.lotNumber(), from, to, quantity.value(), StockStatus.IN_PRODUCTION, StockStatus.AVAILABLE,
                StockMovement.ReferenceType.PRODUCTION_ORDER, command.productionOrderId(),
                "Unprinted, from hold " + hold.id().value(), command.actorId(), now).leaving(source.stockPackageId());
        ledger.append(line);
        log.info("Returned {} unprinted x {} from {} to {} (hold {})", quantity, source.sku(), from, to,
                hold.id().value());
        return toMove(line);
    }

    /**
     * The loose layer of the same goods (SKU, lot, receipt, serial) at {@code location}, if there is one. Goods
     * arriving somewhere arrive loose: a carton's row is never a destination, or its counted contents would change.
     */
    private Optional<StockItem> sameLayerAt(StockItem source, LocationId location) {
        return stockItems.findLooseBySkuAndLocation(source.sku(), location).stream()
                .filter(item -> item.isLayer(source.lotNumber(), source.serialNumber(), source.receivedAt()))
                .findFirst();
    }

    /** Locks the source and, if any, the destination in id order; returns {source, destination-or-null}. */
    private StockItem[] lockPair(StockItem sourceView, Optional<StockItem> destinationView) {
        List<StockItem> toLock = destinationView.isPresent()
                ? List.of(sourceView, destinationView.get()) : List.of(sourceView);
        StockItem[] locked = new StockItem[2];
        for (StockItem view : toLock.stream()
                .sorted(Comparator.comparing(item -> item.id().value().toString())).toList()) {
            StockItem item = stockItems.findByIdForUpdate(view.id()).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-move".formatted(view.id())));
            locked[item.id().equals(sourceView.id()) ? 0 : 1] = item;
        }
        return locked;
    }

    /** The hold, whatever its status, provided it belongs to the owner named. */
    private static Reservation owned(StockItem item, ReservationId reservationId, UUID orderId, UUID productionOrderId) {
        Reservation hold = item.findReservation(reservationId).orElseThrow(() -> new BusinessException(
                ErrorCode.RESERVATION_NOT_FOUND, "No reservation with id " + reservationId.value()));
        if (!hold.isHeldFor(orderId, productionOrderId)) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s belongs neither to this order nor to this production order".formatted(reservationId.value()));
        }
        return hold;
    }

    /**
     * In the print shop or at a subcontractor (IN_PRODUCTION): units leaving a hold there stay out of sale while
     * their write-off waits for approval. Never INBOUND — free INBOUND units can be put away and sold.
     */
    private static void requireHeldForProduction(StockItem item) {
        if (item.status() != StockStatus.IN_PRODUCTION) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                    "%s at %s is %s, not held for production".formatted(item.sku(), item.location(), item.status()));
        }
    }

    private static void requireInPrintShop(StockItem item) {
        if (item.status() != StockStatus.IN_PRODUCTION) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                    "%s at %s is %s, not in the print shop".formatted(item.sku(), item.location(), item.status()));
        }
    }

    /**
     * {@code quantity} units out of a live hold in the print shop. A hold there is closed only by these steps
     * (an order's cancellation does not release it, see {@code InventoryServiceImpl#release}), so the hold always
     * says exactly how many of the layer's units are still this job's.
     */
    private static void takeFromHold(StockItem item, Reservation hold, Quantity quantity, ReleaseReason reason,
                                     Instant now) {
        if (!hold.status().isActive()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s is %s".formatted(hold.id().value(), hold.status()));
        }
        if (hold.quantity().isLessThan(quantity)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Hold %s covers %s unit(s), not %s"
                    .formatted(hold.id().value(), hold.quantity(), quantity));
        }
        item.takeFromHold(hold.id(), quantity, reason, now);
    }

    private StockItem holder(ReservationId reservationId) {
        return stockItems.findIdByReservationId(reservationId)
                .flatMap(stockItems::findById)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + reservationId.value()));
    }

    /**
     * A hold may be issued when it is live, belongs to the owner named, and no longer waits for payment: an
     * order's hold that could still expire would let the sweeper free goods already on the press.
     */
    private static Reservation issuable(StockItem item, ReservationId reservationId, IssueReservedStockCommand command) {
        Reservation hold = item.findReservation(reservationId).orElseThrow(() -> new BusinessException(
                ErrorCode.RESERVATION_NOT_FOUND, "No reservation with id " + reservationId.value()));
        if (!hold.status().isActive()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s is %s".formatted(reservationId.value(), hold.status()));
        }
        if (!hold.isHeldFor(command.orderId(), command.productionOrderId())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s belongs neither to this order nor to this production order".formatted(reservationId.value()));
        }
        if (!hold.isPinned()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s still waits for its order's payment".formatted(reservationId.value()));
        }
        return hold;
    }

    // ------------------------------------------------------------------ receipt

    /**
     * Always a new stock layer (one per receipt, V20260930001000): the receipt time tells it apart from
     * every other layer of the lot at the location, so nothing is merged into an older receipt.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-receipt", resourceId = "#command.receiptLineId()")
    public StockMove receive(ReceiveStockCommand command) {
        WarehouseScope.requireLocation(command.locationCode());
        policies.lock(command.sku().code());
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.RECEIPT,
                StockMovement.ReferenceType.GOODS_RECEIPT_LINE, command.receiptLineId());
        if (done.isPresent()) {
            return toMove(done.get());
        }
        LocationId location = new LocationId(command.locationCode());
        switch (locations.stateOf(location)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + location);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(location));
            case USABLE -> { }
        }
        String lot = blankToNull(command.lotNumber());
        Quantity quantity = Quantity.of(command.quantity());
        stockItems.save(StockItem.receiveInbound(command.sku(), location, lot, command.expiryDate(), quantity,
                command.receivedAt()));

        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.RECEIPT,
                command.sku(), lot, null, location, quantity.value(), StockStatus.INBOUND,
                StockMovement.ReferenceType.GOODS_RECEIPT_LINE, command.receiptLineId(), null,
                command.actorId(), clock.instant());
        ledger.append(line);
        log.info("Received {} x {}{} into {}", quantity, command.sku(), lotSuffix(lot), location);
        return toMove(line);
    }

    // ------------------------------------------------------------------ adjustments

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#result?.adjustmentId()")
    public StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command) {
        WarehouseScope.requireLocation(command.locationCode());
        LocationId location = new LocationId(command.locationCode());
        // The stock item must exist now; FOUND stock adds to a lot already recorded at the location.
        findLayer(command.sku(), location, command.lotNumber(), command.receivedAt())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ITEM_NOT_FOUND,
                        "No %s%s at %s".formatted(command.sku(), lotSuffix(command.lotNumber()), location)));
        Instant now = clock.instant();
        StockAdjustment adjustment = StockAdjustment.request(
                adjustments.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), location, command.sku(),
                blankToNull(command.lotNumber()), command.quantityDelta(),
                AdjustmentReason.valueOf(command.reason().name()), command.note(), command.requestedBy(), now)
                .atLayer(command.receivedAt());
        return toSummary(adjustments.save(adjustment));
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "stock-adjustment", resourceId = "#adjustmentId")
    public StockAdjustmentSummary approve(UUID adjustmentId, UUID approverId) {
        StockAdjustment adjustment = load(adjustmentId);
        policies.lock(adjustment.sku().code());
        StockItem view = findLayer(adjustment.sku(), adjustment.location(), adjustment.lotNumber(),
                        adjustment.receivedAt())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ITEM_NOT_FOUND,
                        "The stock of adjustment %s no longer exists".formatted(adjustment.number())));
        StockItem item = stockItems.findByIdForUpdate(view.id()).orElseThrow();
        Instant now = clock.instant();
        // A decided adjustment is refused by approveAndPost as it stands, before its hold is looked at.
        if (adjustment.reservationId() != null
                && adjustment.status() == com.stockflow.inventory.internal.domain.AdjustmentStatus.PENDING_APPROVAL) {
            // BR-PRD-10: the cancelled order's printed goods were kept under its hold until this decision, so nobody
            // could take them. They leave the hold as they leave stock - once: the hold closes here, and a second
            // approval is refused by the adjustment's own state.
            ReservationId held = ReservationId.of(adjustment.reservationId());
            Reservation hold = item.findReservation(held).filter(r -> r.status().isActive())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                            "The hold of adjustment %s is no longer live".formatted(adjustment.number())));
            if (hold.quantity().value() != -adjustment.quantityDelta()) {
                throw new BusinessException(ErrorCode.CONFLICT, "Adjustment %s takes %d unit(s); its hold covers %s"
                        .formatted(adjustment.number(), -adjustment.quantityDelta(), hold.quantity()));
            }
            item.releaseReservation(held, ReleaseReason.SCRAPPED, now);
        }
        requireLoose(item);
        adjustment.approveAndPost(approverId, item, now);
        stockItems.save(item);
        StockAdjustment saved = adjustments.save(adjustment);

        int delta = adjustment.quantityDelta();
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.ADJUSTMENT,
                adjustment.sku(), adjustment.lotNumber(),
                delta < 0 ? adjustment.location() : null, delta > 0 ? adjustment.location() : null,
                Math.abs(delta), item.status(), StockMovement.ReferenceType.STOCK_ADJUSTMENT, adjustment.id(),
                adjustment.reason() + (adjustment.note() == null ? "" : ": " + adjustment.note()),
                approverId, now));
        log.info("Posted adjustment {}: {} {} at {}", adjustment.number(), delta, adjustment.sku(), adjustment.location());
        return toSummary(saved);
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "stock-adjustment", resourceId = "#adjustmentId")
    public StockAdjustmentSummary reject(UUID adjustmentId, UUID approverId, String reason) {
        StockAdjustment adjustment = load(adjustmentId);
        adjustment.reject(approverId, reason, clock.instant());
        return toSummary(adjustments.save(adjustment));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "stock-adjustment", resourceId = "#adjustmentId")
    public StockAdjustmentSummary withdraw(UUID adjustmentId, UUID userId) {
        StockAdjustment adjustment = load(adjustmentId);
        adjustment.withdraw(userId, clock.instant());
        log.info("Adjustment {} withdrawn by its requester", adjustment.number());
        return toSummary(adjustments.save(adjustment));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockAdjustmentSummary> findAdjustment(UUID adjustmentId) {
        // Out of the caller's warehouses reads as not found: its existence is not theirs to know.
        return adjustments.findById(adjustmentId)
                .filter(adjustment -> inScope(adjustment.location().code()))
                .map(StockOperationsServiceImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                           int page, int size, String sort) {
        var criteria = new StockAdjustmentSearch.Criteria(
                status == null ? null : AdjustmentStatus.valueOf(status.name()), sku, location);
        return Pages.toResponse(adjustmentSearch.search(criteria, Pages.of(page, size, ADJUSTMENT_SORT.parse(sort)))
                .map(StockOperationsServiceImpl::toSummary));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LedgerLine> ledger(String sku, String location, StockMovement.MovementType type,
                                           int page, int size) {
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));
        return Pages.toResponse(ledgerSearch.search(new StockLedgerSearch.Criteria(sku, location, type), pageable)
                .map(StockOperationsServiceImpl::toLine));
    }

    // ------------------------------------------------------------------ helpers

    private StockAdjustment load(UUID adjustmentId) {
        StockAdjustment adjustment = adjustments.findById(adjustmentId).orElseThrow(() ->
                new BusinessException(ErrorCode.STOCK_ADJUSTMENT_NOT_FOUND, "No stock adjustment " + adjustmentId));
        WarehouseScope.requireLocation(adjustment.location().code());
        return adjustment;
    }

    private static boolean inScope(String locationCode) {
        return WarehouseScope.restriction()
                .map(allowed -> allowed.prefixes().contains(WarehouseScope.prefixOf(locationCode)))
                .orElse(true);
    }

    /**
     * {@link #findOne}, or the one loose layer received at {@code receivedAt} when it is given. Loose stock only:
     * a move, a reclassification or an adjustment named by (SKU, location, lot, receipt time) cannot tell one
     * carton of a receipt from another, so stock in a carton is moved and adjusted through its carton.
     */
    private Optional<StockItem> findLayer(Sku sku, LocationId location, String lotNumber, Instant receivedAt) {
        if (receivedAt == null) {
            return findOne(sku, location, lotNumber);
        }
        String lot = blankToNull(lotNumber);
        return stockItems.findLooseBySkuAndLocation(sku, location).stream()
                .filter(item -> Objects.equals(item.lotNumber(), lot) && receivedAt.equals(item.receivedAt()))
                .findFirst();
    }

    /**
     * A move or an adjustment named by (SKU, location, lot, receipt time) reaches loose stock only ({@link #findLayer});
     * stated here as well, so a lookup changed later cannot take units out of a carton without opening it (#141).
     */
    private static void requireLoose(StockItem item) {
        if (!item.isLoose()) {
            throw new IllegalStateException("Stock row %s is in stock package %s; it is changed through its carton"
                    .formatted(item.id(), item.stockPackageId()));
        }
    }

    private Optional<StockItem> findOne(Sku sku, LocationId location, String lotNumber) {
        String lot = blankToNull(lotNumber);
        var matches =
                stockItems.findLooseBySkuAndLocation(sku, location).stream()
                .filter(item -> Objects.equals(item.lotNumber(), lot))
                        .toList();
        if (matches.size() > 1) {
            // The existing move/adjustment request has no receipt/serial selector. Never pick
            // an arbitrary layer now that canonical policy permits several at one location.
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        return matches.stream()
                .findFirst();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String lotSuffix(String lot) {
        return lot == null || lot.isBlank() ? "" : " lot " + lot;
    }

    private static StockMove toMove(StockMovement m) {
        return new StockMove(m.id(), m.sku().code(), m.lotNumber(), m.from() == null ? null : m.from().code(),
                m.to() == null ? null : m.to().code(), m.quantity(), m.occurredAt());
    }

    private static LedgerLine toLine(StockMovement m) {
        return new LedgerLine(m.id(), m.type(), m.sku().code(), m.lotNumber(),
                m.from() == null ? null : m.from().code(), m.to() == null ? null : m.to().code(),
                m.quantity(), m.status() == null ? null : m.status().name(),
                m.toStatus() == null ? null : m.toStatus().name(), m.referenceType().name(), m.referenceId(), m.reason(), m.actorId(), m.occurredAt());
    }

    static StockAdjustmentSummary toSummary(StockAdjustment a) {
        return new StockAdjustmentSummary(a.id(), a.number(), a.location().code(), a.sku().code(), a.lotNumber(),
                a.quantityDelta(), StockAdjustmentReason.valueOf(a.reason().name()), a.note(),
                StockAdjustmentStatus.valueOf(a.status().name()), a.requestedBy(), a.requestedAt(),
                a.decidedBy(), a.decidedAt(), a.rejectionReason(), a.postedAt(), a.withdrawnAt(), a.version());
    }
}
