package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.IssueReservedStockResult;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.MoveStockPackageCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.ReclassifyStockPackageCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.internal.domain.AdjustmentReason;
import com.stockflow.inventory.internal.domain.AdjustmentStatus;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.CountVariance;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReceivingPackagePlan;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockAdjustment;
import com.stockflow.inventory.internal.domain.StockAdjustmentRepository;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockLots;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.StockAdjustmentSearch;
import com.stockflow.inventory.internal.repository.StockLedgerSearch;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final StockLots lots;
    private final InventoryEventPublisher events;
    /** Taking from a carton opens it and the last unit closes it; its lock comes between the SKU's and the rows'. */
    private final StockPackageContents cartons;
    /** The cartons labelled while receiving (SCRUM-473); taken from through {@link #cartons}. */
    private final StockPackageRepository stockPackages;
    private final UomConversionRepository units;

    StockOperationsServiceImpl(StockItemRepository stockItems, StockAdjustmentRepository adjustments,
                               StockAdjustmentSearch adjustmentSearch, StockMovementLog ledger,
                               StockLedgerSearch ledgerSearch, LocationDirectory locations, Clock clock,
                               InventoryPolicyRepository policies, StockLots lots, StockPackageContents cartons,
                               InventoryEventPublisher events, StockPackageRepository stockPackages,
                               UomConversionRepository units) {
        this.stockPackages = stockPackages;
        this.units = units;
        this.cartons = cartons;
        this.events = events;
        this.stockItems = stockItems;
        this.adjustments = adjustments;
        this.adjustmentSearch = adjustmentSearch;
        this.ledger = ledger;
        this.ledgerSearch = ledgerSearch;
        this.locations = locations;
        this.clock = clock;
        this.policies = policies;
        this.lots = lots;
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
        requireMovableBetween(from, to);
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
                referenceType, referenceId, reason, actorId, now).ofLot(source.lotId());
        ledger.append(line);
        log.info("Moved {} x {}{} from {} to {} ({} -> {})", quantity, sku, lotSuffix(lotNumber), from, to,
                before, arriving);
        return toMove(line);
    }

    /**
     * What every move of stock needs of its two ends, loose or in a carton: two different locations, in one
     * warehouse (docs 11 BR-01: stock leaves its warehouse only on a transfer order, which records the dispatch,
     * the transit and the receipt), and a destination that can take stock. Only the destination is checked (issue
     * #67): taking stock out of a blocked bin or a shelf in maintenance is how it gets emptied, and an adjustment
     * at a bin blocked for counting must still post.
     */
    private void requireMovableBetween(LocationId from, LocationId to) {
        if (from.equals(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A move needs two different locations");
        }
        if (!from.warehouseCode().equals(to.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s and %s are in different warehouses; use a transfer order".formatted(from, to));
        }
        requireUsable(to);
    }

    // ------------------------------------------------------------------ a carton, whole

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-package-move", resourceId = "#command.lpn()")
    public StockPackageView moveStockPackage(MoveStockPackageCommand command) {
        return relocatePackage(command.lpn(), command.toLocationCode(), null, command.reference(),
                command.referenceId(), command.actorId(), null);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-package-move", resourceId = "#command.lpn()")
    public StockPackageView reclassifyStockPackage(ReclassifyStockPackageCommand command) {
        return relocatePackage(command.lpn(), command.toLocationCode(),
                StockStatus.valueOf(command.disposition().name()), command.reference(), command.referenceId(),
                command.actorId(), blankToNull(command.reason()));
    }

    /**
     * A carton to {@code toCode} with its rows, {@code target} null for a plain move (the status travels) or the
     * status QC gives it. The rows are changed in place, never split off (a carton is not opened by a decision
     * about it), so each keeps its id and holds; one MOVE ledger line per row names the carton on both sides.
     *
     * <p>Lock order: the SKU, the carton, then its rows by id. The carton is read unlocked first only to learn
     * its SKU; everything decided about it is read again under the lock. No replay lookup: a second call finds
     * the carton already at the destination and is refused as any move to the same place is.</p>
     */
    private StockPackageView relocatePackage(String lpn, String toCode, StockStatus target,
                                             MoveReference reference, UUID referenceId, UUID actorId, String reason) {
        WarehouseScope.requireLocation(toCode);
        StockPackage seen = stockPackages.findByLpn(lpn).orElseThrow(() ->
                new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + lpn));
        WarehouseScope.requireLocation(seen.location().code());
        UomConversion unit = units.findAll(List.of(seen.uomConversionId())).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Stock package %s is in no known unit".formatted(lpn)));
        Sku sku = new Sku(unit.sku());
        policies.lock(sku.code());

        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(seen.id())).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + lpn));
        if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; only a carton in stock is moved".formatted(lpn, carton.status()));
        }
        LocationId from = carton.location();
        LocationId to = new LocationId(toCode);
        requireMovableBetween(from, to);

        List<StockItem> rows = new ArrayList<>();
        for (StockItemId id : stockItems.findIdsInStockPackage(carton.id()).stream()
                .sorted(Comparator.comparing(id -> id.value().toString())).toList()) {
            rows.add(stockItems.findByIdForUpdate(id).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-move".formatted(id))));
        }
        if (target != null) {
            for (StockItem row : rows) {
                if (row.status() != StockStatus.INBOUND && row.status() != StockStatus.QUARANTINE) {
                    throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                            "%s holds %s; only INBOUND or QUARANTINE stock takes a receiving decision"
                                    .formatted(lpn, row.status()));
                }
            }
        }

        carton.moveTo(to);
        stockPackages.save(carton);
        Instant now = clock.instant();
        int onHand = 0;
        for (StockItem row : rows) {
            StockStatus before = row.status();
            row.followPackageTo(to);
            if (target != null) {
                row.reclassifyInPackage(target);
            }
            stockItems.save(row);
            onHand += row.onHand().value();
            if (row.onHand().value() > 0) {
                ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE, row.sku(),
                        row.lotNumber(), from, to, row.onHand().value(), before, row.status(),
                        StockMovement.ReferenceType.valueOf(reference.name()), referenceId, reason, actorId, now)
                        .ofLot(row.lotId()).leaving(carton.id()).arriving(carton.id()));
            }
        }
        log.info("Moved carton {} from {} to {} ({} row(s){})", lpn, from, to, rows.size(),
                target == null ? "" : ", now " + target);
        return view(carton, unit, onHand);
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
                "Hold " + hold.id().value(), command.actorId(), now)
                .ofLot(source.lotId()).leaving(source.stockPackageId());
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
                && (ledger.handedOverFromProduction(hold.id(), item.location())
                        // G4-Q2: a cancelled order's consumed printed goods, brought back only to be scrapped.
                        || ledger.bookedBackForScrap(hold.id(), item.location()));
    }

    /**
     * Decision G4-Q2: under the SKU lock, then the row's, as every hold step. The hold must be live finished goods of
     * {@code orderId} that production handed to a packing area - never free stock somebody moved there, never blanks.
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#reservationId")
    public StockMove consumeFinishedGoods(UUID reservationId, UUID orderId, UUID productionOrderId, UUID actorId) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(productionOrderId, "productionOrderId");
        ReservationId id = ReservationId.of(reservationId);
        policies.lock(holder(id).sku().code());
        StockItem view = holder(id);
        WarehouseScope.requireLocation(view.location().code());
        StockPackageContents.LockedCartons locked = cartons.lockFor(List.of(view));
        StockItem item = stockItems.findByIdForUpdate(view.id()).orElseThrow();
        Reservation hold = owned(item, id, orderId, null);
        if (!hold.status().isActive()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Hold %s is already %s".formatted(reservationId, hold.status()));
        }
        if (!isFinishedCustomGoods(item, hold)) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH, ("Hold %s (%s at %s) is not finished goods "
                    + "production handed to a packing area").formatted(reservationId, item.status(), item.location()));
        }
        Instant now = clock.instant();
        StockStatus status = item.status();
        locked.tookFrom(item);
        item.consumeReservation(id, now);
        stockItems.save(item);
        cartons.settle(locked, now);
        events.publishEventsOf(item);
        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.CONSUMPTION, item.sku(),
                item.lotNumber(), item.location(), null, hold.quantity().value(), status,
                StockMovement.ReferenceType.PRODUCTION_ORDER, productionOrderId, "Hold " + reservationId, actorId, now)
                .ofLot(item.lotId()).forOrder(orderId).leaving(item.stockPackageId());
        ledger.append(line);
        log.info("Production order {} completed: hold {} ({} x {}) consumed at {}", productionOrderId, reservationId,
                hold.quantity(), item.sku(), item.location());
        return toMove(line);
    }

    /**
     * BR-10 for goods consumed at completion (G4-Q2). Each hold is taken under the SKU lock and its row's, in stock
     * row id order, so two cancellations sharing rows never lock them in opposite orders.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#command.orderId()")
    public List<StockAdjustmentSummary> scrapCancelledPrint(com.stockflow.inventory.api.ScrapCancelledPrintCommand command) {
        List<StockAdjustmentSummary> asked = new java.util.ArrayList<>();
        int remaining = command.quantity();
        List<UUID> ordered = command.reservationIds().stream().distinct()
                .sorted(Comparator.comparing((UUID id) -> stockItems.findIdByReservationId(ReservationId.of(id))
                        .map(item -> item.value().toString()).orElse("")).thenComparing(UUID::toString))
                .toList();
        for (UUID reservationId : ordered) {
            if (remaining <= 0) {
                break;
            }
            ReservationId id = ReservationId.of(reservationId);
            Optional<StockItem> first = stockItems.findIdByReservationId(id).flatMap(stockItems::findById);
            if (first.isEmpty()) {
                continue;
            }
            policies.lock(first.get().sku().code());
            StockItem view = holder(id);
            WarehouseScope.requireLocation(view.location().code());
            StockItem item = stockItems.findByIdForUpdate(view.id()).orElseThrow();
            Reservation hold = item.findReservation(id).orElseThrow();
            if (!command.orderId().equals(hold.orderId())) {
                continue;
            }
            if (isFinishedCustomGoods(item, hold)) {
                // Handed over before G4-Q2 and never consumed: scrapped whole, as before.
                scrapCancelledPrint(reservationId, command.orderId(), command.note(), command.requestedBy())
                        .ifPresent(asked::add);
                remaining -= hold.quantity().value();
                continue;
            }
            if (hold.status() != com.stockflow.inventory.internal.domain.ReservationStatus.CONSUMED
                    || !ledger.consumedAtCompletion(id, item.location())) {
                continue;   // never printed, picked, or released: not printed goods of this order
            }
            UUID requestId = UUID.nameUUIDFromBytes((command.requestId() + ":" + reservationId)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Optional<Reservation> standing = item.reservations().stream()
                    .filter(r -> r.requestId().equals(requestId)).findFirst();
            if (standing.isPresent()) {
                // The same cancellation or closure again: its write-off as it stands, nothing brought back twice.
                adjustments.findLatestForHold(standing.get().id().value()).map(StockOperationsServiceImpl::toSummary)
                        .ifPresent(asked::add);
                remaining -= standing.get().quantity().value();
                continue;
            }
            if (command.requestedBy() == null) {
                log.warn("Hold {}: printed goods of cancelled order {} were consumed at completion; nobody to ask "
                        + "their SCRAP write-off in the name of, nothing booked back", reservationId, command.orderId());
                continue;
            }
            Instant now = clock.instant();
            Optional<Reservation> back = item.bookBackForScrap(id, command.orderId(), requestId,
                    Quantity.of(remaining), now);
            if (back.isEmpty()) {
                continue;   // everything this hold consumed is already back for scrap
            }
            Reservation scrapHold = back.get();
            int quantity = scrapHold.quantity().value();
            stockItems.save(item);
            ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.PUT_BACK, item.sku(),
                    item.lotNumber(), null, item.location(), quantity, StockStatus.AVAILABLE,
                    StockMovement.ReferenceType.ORDER, command.orderId(), "Hold " + scrapHold.id().value(),
                    command.requestedBy(), now).ofLot(item.lotId()).forOrder(command.orderId()));
            StockAdjustment adjustment = StockAdjustment.request(adjustments.nextNumber(LocalDate.ofInstant(now,
                            BUSINESS_ZONE)), item.location(), item.sku(), item.lotNumber(), -quantity,
                            AdjustmentReason.SCRAP, command.note(), command.requestedBy(), now)
                    .atLayer(item.receivedAt()).forHold(scrapHold.id().value());
            StockAdjustmentSummary saved = toSummary(adjustments.save(adjustment));
            asked.add(saved);
            remaining -= quantity;
            log.info("Hold {}: {} x {} printed for order {} and consumed at completion booked back at {} under hold {}; "
                    + "SCRAP write-off {} asked for", reservationId, quantity, item.sku(), command.orderId(),
                    item.location(), scrapHold.id().value(), saved.number());
        }
        return asked;
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
                .ofLot(item.lotId()).leaving(item.stockPackageId());
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
                "Unprinted, from hold " + hold.id().value(), command.actorId(), now)
                .ofLot(source.lotId()).leaving(source.stockPackageId());
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
                .filter(item -> item.isSameGoodsAs(source))
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
     * Always new stock layers (one per receipt, V20260930001000): the receipt time tells them apart from
     * every other layer of the lot at the location, so nothing is merged into an older receipt.
     *
     * <p>SCRUM-473: the goods go into the line's RECEIVING cartons first - one INBOUND row per carton holding what
     * was counted into it, the carton IN_STOCK and still unopened, which is exactly what the commit-time check of
     * V20261020000200 wants of it - and only the remainder goes loose. The cartons are locked after the SKU, as
     * every carton write locks them, and checked before anything is written, so a refusal leaves no lot opened.</p>
     *
     * <p>A replay is recognised by the line's first RECEIPT ledger row, which is the move returned the first time:
     * the loose line is written before the cartons' and ledger ids grow with time, so the first row is the loose
     * line when there is one, and the first carton's otherwise.</p>
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
        requireUsable(location);
        String lot = blankToNull(command.lotNumber());

        List<StockPackage> packed = lockCartonsOf(command.receiptLineId()).stream()
                .filter(carton -> carton.status() == StockPackageStatus.RECEIVING)
                .toList();
        int inCartons = packed.stream().mapToInt(carton -> Objects.requireNonNullElse(carton.receivedQty(), 0)).sum();
        // The invariant stated twice: procurement refuses such a count first (design 3, rule 5).
        if (inCartons > command.quantity()) {
            throw new BusinessException(ErrorCode.RECEIPT_PACKAGES_EXCEED_LINE,
                    "The cartons of receipt line %s hold %d; the line received %d".formatted(command.receiptLineId(),
                            inCartons, command.quantity()));
        }
        for (StockPackage carton : packed) {
            // A carton's rows sit where it is (check_stock_package_contents); counting the line moves its cartons.
            if (!carton.location().equals(location)) {
                throw new BusinessException(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION,
                        "%s is at %s, not at %s; count the line again".formatted(carton.lpn(), carton.location(),
                                location));
            }
        }

        // SCRUM-142: the receipt opens the lot - one per receipt and (SKU, lot number, expiry) - so a lot number
        // another receipt used before is a different lot, and the trace knows the supplier and the documents.
        UUID lotId = command.origin() == null ? null : lots.openForReceipt(new StockLots.ReceiptLot(command.sku(), lot,
                command.expiryDate(), command.receivedAt(), command.origin().goodsReceiptId(),
                command.origin().goodsReceiptNumber(), command.origin().purchaseOrderId(),
                command.origin().purchaseOrderNumber(), command.origin().supplierId(), command.origin().supplierName(),
                command.origin().warehouseId()));
        Instant now = clock.instant();

        StockMove first = null;
        int loose = command.quantity() - inCartons;
        if (loose > 0) {
            stockItems.save(StockItem.receiveInbound(command.sku(), location, lot, command.expiryDate(),
                    Quantity.of(loose), command.receivedAt(), lotId));
            StockMovement line = receiptLine(command, lot, location, loose, lotId, now);
            ledger.append(line);
            first = toMove(line);
        }
        for (StockPackage carton : packed) {
            carton.putInStock();   // refuses a carton never counted, before a row is written for it
            int counted = carton.receivedQty();
            stockItems.save(StockItem.receiveInboundInto(carton.id(), command.sku(), location, lot,
                    command.expiryDate(), Quantity.of(counted), command.receivedAt(), lotId));
            stockPackages.save(carton);
            StockMovement line = receiptLine(command, lot, location, counted, lotId, now).arriving(carton.id());
            ledger.append(line);
            if (first == null) {
                first = toMove(line);
            }
        }
        log.info("Received {} x {}{} into {}: {} loose, {} carton(s)", command.quantity(), command.sku(),
                lotSuffix(lot), location, loose, packed.size());
        return first;
    }

    private static StockMovement receiptLine(ReceiveStockCommand command, String lot, LocationId location,
                                             int quantity, UUID lotId, Instant now) {
        return new StockMovement(Identifiers.newId(), StockMovement.MovementType.RECEIPT,
                command.sku(), lot, null, location, quantity, StockStatus.INBOUND,
                StockMovement.ReferenceType.GOODS_RECEIPT_LINE, command.receiptLineId(), null,
                command.actorId(), now).ofLot(lotId);
    }

    // ------------------------------------------------------------------ cartons while receiving

    /**
     * The pure part - which carton is recounted, which is new and which is voided - is {@link ReceivingPackagePlan};
     * this applies it under the lock order of every carton write: the SKU, then the line's cartons in id order. A
     * RECEIVING carton holds no stock row, so there is no third lock.
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "receipt-line-packages",
            resourceId = "#command.receiptLineId()")
    public List<StockPackageView> countReceivingPackages(CountReceivingPackagesCommand command) {
        WarehouseScope.requireLocation(command.locationCode());
        LocationId location = new LocationId(command.locationCode());
        requireUsable(location);
        UomConversion unit = command.packages().isEmpty() ? null : packingUnit(command);
        policies.lock(command.sku().code());

        List<StockPackage> current = lockCartonsOf(command.receiptLineId());
        ReceivingPackagePlan plan = ReceivingPackagePlan.of(current, command.packages().stream()
                .map(entry -> new ReceivingPackagePlan.Wanted(blankToNull(entry.lpn()), entry.quantity()))
                .toList());
        Instant now = clock.instant();
        if (unit == null) {
            // No cartons asked for: an empty list names nothing to recount or label, so the plan only voids.
            voidCartons(plan, now);
            log.info("Receipt line {}: {} carton(s) voided", command.receiptLineId(), plan.voided().size());
            return List.of();
        }
        List<StockPackage> live = new ArrayList<>();
        for (ReceivingPackagePlan.Recount recount : plan.recounts()) {
            StockPackage carton = recount.carton();
            // Every carton of a line is in the line's unit (design 3, rule 7): a new unit voids the old cartons.
            if (!carton.uomConversionId().equals(unit.id())) {
                throw new BusinessException(ErrorCode.RECEIPT_LINE_HAS_PACKAGES,
                        "%s was labelled in another unit; leave it out to void it".formatted(carton.lpn()));
            }
            carton.count(recount.quantity());
            if (!carton.location().equals(location)) {
                carton.moveTo(location);
            }
            live.add(stockPackages.save(carton));
        }
        if (!plan.newCounts().isEmpty()) {
            UUID itemId = units.itemId(command.sku().code()).orElseThrow(() -> new BusinessException(
                    ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + command.sku()));
            LocalDate day = BusinessCalendar.date(now);
            for (int quantity : plan.newCounts()) {
                StockPackage carton = StockPackage.receiving(new StockPackageId(Identifiers.newId()),
                        stockPackages.nextLpn(day), itemId, unit.id(), unit.factor(), location,
                        command.receiptLineId());
                carton.count(quantity);
                live.add(stockPackages.save(carton));
            }
        }
        voidCartons(plan, now);
        log.info("Receipt line {}: {} carton(s) recounted, {} labelled, {} voided", command.receiptLineId(),
                plan.recounts().size(), plan.newCounts().size(), plan.voided().size());
        return live.stream()
                .sorted(Comparator.comparing(StockPackage::lpn))
                .map(carton -> view(carton, unit, carton.receivedQty()))
                .toList();
    }

    private void voidCartons(ReceivingPackagePlan plan, Instant now) {
        for (StockPackage carton : plan.voided()) {
            carton.cancel(now);
            stockPackages.save(carton);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<StockPackageView>> receivingPackagesOf(Collection<UUID> receiptLineIds) {
        List<StockPackage> found = stockPackages.findByReceiptLines(receiptLineIds).stream()
                .filter(carton -> carton.status() != StockPackageStatus.VOID)
                .sorted(Comparator.comparing(StockPackage::lpn))
                .toList();
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UomConversion> unitsById = units.findAll(found.stream().map(StockPackage::uomConversionId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(UomConversion::id, Function.identity()));
        // A carton being received holds no row yet: what was counted into it is its quantity.
        Map<StockPackageId, Integer> onHand = stockPackages.onHandOf(found.stream()
                .filter(carton -> carton.status() != StockPackageStatus.RECEIVING).map(StockPackage::id).toList());
        return found.stream().collect(Collectors.groupingBy(StockPackage::goodsReceiptLineId, LinkedHashMap::new,
                Collectors.mapping(carton -> view(carton, unitsById.get(carton.uomConversionId()),
                                carton.status() == StockPackageStatus.RECEIVING
                                        ? carton.receivedQty() : onHand.getOrDefault(carton.id(), 0)),
                        Collectors.toList())));
    }

    /** The command's unit: required once there is a carton, a unit of the SKU's item, and not the base unit. */
    private UomConversion packingUnit(CountReceivingPackagesCommand command) {
        if (command.uomConversionId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Cartons need the packing unit they are in");
        }
        UomConversion unit = units.find(command.sku().code(), command.uomConversionId()).orElseThrow(() ->
                new BusinessException(ErrorCode.UOM_CONVERSION_NOT_FOUND,
                        "No unit %s on %s".formatted(command.uomConversionId(), command.sku())));
        if (unit.base()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Cartons are labelled in a packing unit, not in the base unit " + unit.unitOfMeasure());
        }
        return unit;
    }

    /** Every carton ever labelled on the line, VOID ones included, locked in id order. Call under the SKU lock. */
    private List<StockPackage> lockCartonsOf(UUID receiptLineId) {
        return stockPackages.findAllByIdForUpdate(stockPackages.findByReceiptLines(List.of(receiptLineId)).stream()
                .map(StockPackage::id).toList());
    }

    private static StockPackageView view(StockPackage carton, UomConversion unit, Integer quantity) {
        return new StockPackageView(carton.id().value(), carton.lpn(), carton.goodsReceiptLineId(),
                unit == null ? null : unit.sku(), carton.uomConversionId(), unit == null ? null : unit.unitOfMeasure(),
                carton.nominalQty(), quantity, carton.receivedQty(), carton.location().code(), carton.status().name(),
                carton.unopened());
    }

    private void requireUsable(LocationId location) {
        switch (locations.stateOf(location)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + location);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(location));
            case USABLE -> { }
        }
    }

    // ------------------------------------------------------------------ adjustments

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#result?.adjustmentId()")
    public StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command) {
        // Said here so a typed-in request hears why; the table would refuse it without a count line
        // (ck_stock_adjustment_count_variance), which reaches the client as a 500.
        if (command.reason() == StockAdjustmentReason.COUNT_VARIANCE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A count variance comes from a cycle count");
        }
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

    /**
     * The slot is the one submission chose, so the (SKU, location, lot) ambiguity {@link #findOne} refuses never
     * arises here, and the assignee and the count's state were checked by the caller. A loose variance is split over
     * the slot's receipt layers as they stand now, under the SKU lock ({@link CountVariance#split}: a shortage from
     * free units first, then held ones, each in FEFO order; a surplus on the newest layer), one adjustment per layer,
     * because approval posts an adjustment onto one stock row. A carton's is one adjustment naming the carton: its approval changes the carton's state once.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "cycle-count-line", resourceId = "#cycleCountLineId")
    public List<StockAdjustmentSummary> requestCountVariance(UUID cycleCountLineId, CountSlot slot, int delta,
                                                             UUID requestedBy, String note) {
        Objects.requireNonNull(cycleCountLineId, "cycleCountLineId");
        if (delta == 0) {
            return List.of();
        }
        WarehouseScope.requireLocation(slot.location().code());
        policies.lock(slot.sku().code());
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, BUSINESS_ZONE);
        if (slot.isCarton()) {
            StockAdjustment adjustment = StockAdjustment.request(adjustments.nextNumber(day), slot.location(),
                            slot.sku(), slot.lotNumber(), delta, AdjustmentReason.COUNT_VARIANCE, note, requestedBy, now)
                    .inPackage(slot.stockPackageId()).forCountLine(cycleCountLineId);
            return List.of(toSummary(adjustments.save(adjustment)));
        }
        List<CountVariance.Layer> layers = stockItems.findLooseBySkuAndLocation(slot.sku(), slot.location()).stream()
                .filter(item -> Objects.equals(blankToNull(item.lotNumber()), slot.lotNumber()))
                .map(item -> new CountVariance.Layer(item.id(), item.receivedAt(), item.expiryDate(),
                        item.onHand().value(), item.reserved().value()))
                .toList();
        List<StockAdjustmentSummary> asked = new ArrayList<>();
        for (CountVariance.Share share : CountVariance.split(delta, layers)) {
            StockAdjustment adjustment = StockAdjustment.request(adjustments.nextNumber(day), slot.location(),
                            slot.sku(), slot.lotNumber(), share.delta(), AdjustmentReason.COUNT_VARIANCE, note,
                            requestedBy, now)
                    .atLayer(share.receivedAt()).forCountLine(cycleCountLineId);
            asked.add(toSummary(adjustments.save(adjustment)));
        }
        log.info("Count line {}: variance {} of {}{} at {} asked as {} adjustment(s)", cycleCountLineId, delta,
                slot.sku(), lotSuffix(slot.lotNumber()), slot.location(), asked.size());
        return asked;
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "stock-adjustment", resourceId = "#adjustmentId")
    public StockAdjustmentSummary approve(UUID adjustmentId, UUID approverId) {
        StockAdjustment adjustment = load(adjustmentId);
        policies.lock(adjustment.sku().code());
        if (adjustment.stockPackageId() != null) {
            return approveCarton(adjustment, approverId);
        }
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
        int delta = adjustment.quantityDelta();
        // A configured FIFO SKU's layer is never raised (inventory.enforce_sku_policy: "reconcile via a new stock
        // layer"): loose units found have no provable receipt, and putting them on an old layer would make them as
        // old as it and first out. They land on a new layer beside it, received now; the adjustment still names the
        // layer it was asked against. FEFO and unconfigured SKUs write the named layer up, as before.
        StockItem target = delta > 0 && policies.replenishesByNewLayer(adjustment.sku().code())
                ? StockItem.newLayerBeside(item, now)
                : item;
        adjustment.approveAndPost(approverId, target, now);
        stockItems.save(target);
        StockAdjustment saved = adjustments.save(adjustment);

        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.ADJUSTMENT,
                adjustment.sku(), adjustment.lotNumber(),
                delta < 0 ? adjustment.location() : null, delta > 0 ? adjustment.location() : null,
                Math.abs(delta), target.status(), StockMovement.ReferenceType.STOCK_ADJUSTMENT, adjustment.id(),
                ledgerReason(adjustment), approverId, now).ofLot(target.lotId()));
        log.info("Posted adjustment {}: {} {} at {}", adjustment.number(), delta, adjustment.sku(), adjustment.location());
        return toSummary(saved);
    }

    /**
     * A carton's adjustment, under the SKU lock the caller took: {@link StockPackageContents#adjust} locks the carton
     * and then its rows, spreads the units over them and changes the carton once (#125 lock order, spec section 6).
     * Decided first, so a requester approving their own request hears that rather than a stock shortage; a refusal
     * while posting rolls the decision back. One ledger line per row changed, naming the carton on the side that
     * has it - the side whose location the loose path names.
     */
    private StockAdjustmentSummary approveCarton(StockAdjustment adjustment, UUID approverId) {
        Instant now = clock.instant();
        adjustment.approveForCarton(approverId, now);
        List<StockPackageContents.RowChange> changes = cartons.adjust(adjustment.stockPackageId(),
                adjustment.quantityDelta(), adjustment.reason() == AdjustmentReason.COUNT_VARIANCE, now);
        StockAdjustment saved = adjustments.save(adjustment);
        String reason = ledgerReason(adjustment);
        for (StockPackageContents.RowChange change : changes) {
            StockItem row = change.row();
            boolean down = change.delta() < 0;
            StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.ADJUSTMENT,
                    row.sku(), row.lotNumber(), down ? row.location() : null, down ? null : row.location(),
                    Math.abs(change.delta()), row.status(), StockMovement.ReferenceType.STOCK_ADJUSTMENT,
                    adjustment.id(), reason, approverId, now).ofLot(row.lotId());
            ledger.append(down ? line.leaving(adjustment.stockPackageId()) : line.arriving(adjustment.stockPackageId()));
        }
        log.info("Posted adjustment {}: {} {} in carton {} at {}", adjustment.number(), adjustment.quantityDelta(),
                adjustment.sku(), adjustment.stockPackageId().value(), adjustment.location());
        return toSummary(saved);
    }

    /** The ledger's reason for a posted adjustment: its reason code, and the note when there is one. */
    private static String ledgerReason(StockAdjustment adjustment) {
        return adjustment.reason() + (adjustment.note() == null ? "" : ": " + adjustment.note());
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
                a.decidedBy(), a.decidedAt(), a.rejectionReason(), a.postedAt(), a.withdrawnAt(), a.version(),
                a.stockPackageId() == null ? null : a.stockPackageId().value(), a.cycleCountLineId());
    }
}
