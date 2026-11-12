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
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
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
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReceivingPackagePlan;
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
import com.stockflow.inventory.internal.repository.SlotsUnderCount;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private final SlotsUnderCount underCount;
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
    private final CycleCountRepository countLines;
    private final CartonPlacement placement;
    /** Asked whether a carton is on its way into a bin before it is written off (team plan 4D-1). */
    private final com.stockflow.warehouse.api.PutawayTasks putawayTasks;

    StockOperationsServiceImpl(StockItemRepository stockItems, StockAdjustmentRepository adjustments,
                               StockAdjustmentSearch adjustmentSearch, StockMovementLog ledger,
                               StockLedgerSearch ledgerSearch, LocationDirectory locations, Clock clock,
                               InventoryPolicyRepository policies, StockLots lots, StockPackageContents cartons,
                               InventoryEventPublisher events, StockPackageRepository stockPackages,
                               UomConversionRepository units, SlotsUnderCount underCount,
                               CycleCountRepository countLines, CartonPlacement placement,
                               com.stockflow.warehouse.api.PutawayTasks putawayTasks) {
        this.placement = placement;
        this.putawayTasks = putawayTasks;
        this.underCount = underCount;
        this.countLines = countLines;
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

    /**
     * What every move of stock needs of its two ends, loose or in a carton: two different locations, in one
     * warehouse (docs 11 BR-01: stock never changes warehouse - a Production Order delivers straight to the warehouse
     * that ships), and a destination that can take stock. Only the destination is checked (issue
     * #67): taking stock out of a blocked bin or a shelf in maintenance is how it gets emptied, and an adjustment
     * at a bin blocked for counting must still post.
     *
     * <p>Neither end is a staging area (kltn-docs 11 BR-09, 20 BR-20, team plan 4C-5): a carton there was picked for an
     * order and leaves only when its task moves to dispatch or, the order cancelled, by the put-back - both picking's
     * own moves ({@code PickConsumptionService}), never this one. {@code tg_stock_movement_staging} says the same of the
     * ledger.</p>
     */
    /**
     * Review L5 (team plan 4C-5; kltn-docs 20 BR-20): stock enters a staging area only by picking, always carrying an
     * order's allocation. A count or an adjustment may write stock there down - a carton missing is a shortage - but
     * never add to it: found units would be nobody's, out of available to promise, unpickable and unmovable, stranded
     * until written down again. {@code tg_stock_movement_staging} refuses the same ledger line.
     */
    private void requireNoSurplusInStaging(LocationId location, int delta) {
        if (delta > 0 && locations.isStagingArea(location)) {
            throw new BusinessException(ErrorCode.STAGING_MOVE_NOT_ALLOWED, ("%s is a staging area: a count or an "
                    + "adjustment writes stock there down, never up; stock enters it only by picking").formatted(location),
                    List.of(), Map.of("locationCode", location.code()));
        }
    }

    private void requireMovableBetween(LocationId from, LocationId to) {
        if (from.equals(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A move needs two different locations");
        }
        if (!from.warehouseCode().equals(to.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s and %s are in different warehouses; stock moves only inside one".formatted(from, to));
        }
        for (LocationId end : List.of(from, to)) {
            if (locations.isStagingArea(end)) {
                throw new BusinessException(ErrorCode.STAGING_MOVE_NOT_ALLOWED, ("%s is a staging area: stock enters it "
                        + "only by picking for an order, and leaves it by dispatch or put-back").formatted(end),
                        List.of(), Map.of("locationCode", end.code()));
            }
        }
        requireUsable(to);
    }

    // ------------------------------------------------------------------ a carton, whole

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-package-move", resourceId = "#command.referenceId()")
    public List<StockPackageView> moveStockPackages(MoveStockPackagesCommand command) {
        return relocatePackages(command.lpns(), command.toLocationCode(), null, command.reference(),
                command.referenceId(), command.actorId(), null, false);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-package-move", resourceId = "#command.referenceId()")
    public List<StockPackageView> reclassifyStockPackages(ReclassifyStockPackagesCommand command) {
        return relocatePackages(command.lpns(), command.toLocationCode(),
                StockStatus.valueOf(command.disposition().name()), command.reference(), command.referenceId(),
                command.actorId(), blankToNull(command.reason()), command.quarantineRelease());
    }

    /**
     * Cartons to {@code toCode} with their rows, {@code target} null for a plain move (the status travels) or the
     * status QC gives them. The rows are changed in place, never split off (a carton is not opened by a decision
     * about it), so each keeps its id and holds; one MOVE ledger line per row names its carton on both sides.
     *
     * <p>One pass for the whole list: a document moves a line's cartons together, and a call per carton read,
     * locked and wrote the same things once per carton. Lock order, as for one carton: the SKUs (sorted), the
     * cartons, then their rows, each set in id order. The cartons are read unlocked first only to learn their SKUs;
     * everything decided about them is read again under the lock, and every carton is checked before any moves. No
     * replay lookup for a document's move: a second call finds a carton already at the destination and is refused as
     * any move to the same place is.</p>
     *
     * <p>A move a person asks for ({@code MOVE_TASK}, kltn-docs 11) is different in three ways. It is answered again
     * for the same request id ({@code referenceId}), from the ledger, rather than refused (11 BR-07: sending the same
     * request does not move twice). It never takes a carton with units held for an order (11 BR-05: the picker would
     * walk to an empty bin; the hold moves only by picking). And it does not take a carton a cycle count holds
     * (11 BR-06, 20 BR-10, SCRUM-146): the count would be of books that changed under it. A count is of cartons, each
     * by its LPN (team plan 4C-3), so a carton moved in beside them changes no counted line's books: the bin is not
     * held against arrivals (20 §4.2 b4: the warehouse keeps working while it is counted). The count's cartons are
     * asked through {@code claim} after the cartons are locked and before their rows are, which also takes their
     * advisory locks, so a count planned at the same moment either sees this move committed or waits for it.</p>
     *
     * <p>Into a bin, whatever the reference, every carton that changes place must fit it dimension by dimension
     * (kltn-docs 06 BR-01, 11 BR-10; {@link CartonPlacement}): one that does not refuses the whole move with
     * {@code CARTON_DOES_NOT_FIT_BIN}. Into an area, size is not checked. Neither the bin's volume nor its load is
     * weighed here: only a putaway does that (11 §4.1, open question C24).</p>
     *
     * <p>One exception: a putaway ({@code PUTAWAY_TASK}, to AVAILABLE) of a carton somebody already moved by hand into
     * the chosen bin. It has nowhere to move to, so it is reclassified where it stands, and its ledger line is a
     * {@code STATUS_CHANGE} from the bin to the bin ({@code MOVE} names two different places). Every other move to
     * the place a carton is in is still refused.</p>
     *
     * <p>A putaway takes INBOUND rows to AVAILABLE; the putaway of a carton released from quarantine
     * ({@code quarantineRelease}, team plan 4D-1) takes QUARANTINE rows, and only those, so neither task can put away
     * the other's goods.</p>
     */
    private List<StockPackageView> relocatePackages(List<String> lpns, String toCode, StockStatus target,
                                                    MoveReference reference, UUID referenceId, UUID actorId,
                                                    String reason, boolean quarantineRelease) {
        WarehouseScope.requireLocation(toCode);
        Map<String, StockPackage> seen = stockPackages.findByLpns(lpns).stream()
                .collect(Collectors.toMap(StockPackage::lpn, Function.identity()));
        for (String lpn : lpns) {
            StockPackage carton = seen.get(lpn);
            if (carton == null) {
                throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + lpn);
            }
            WarehouseScope.requireLocation(carton.location().code());
        }
        Map<UUID, UomConversion> unitsById = units.findAll(seen.values().stream().map(StockPackage::uomConversionId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(UomConversion::id, Function.identity()));
        seen.values().stream()
                .map(carton -> Optional.ofNullable(unitsById.get(carton.uomConversionId())).orElseThrow(() ->
                        new IllegalStateException("Stock package %s is in no known unit".formatted(carton.lpn()))))
                .map(UomConversion::sku).distinct().sorted()
                .forEach(policies::lock);

        boolean byHand = reference == MoveReference.MOVE_TASK;
        if (byHand) {
            List<StockMovementLog.MovedCarton> moved = ledger.cartonsMovedBy(StockMovement.ReferenceType.MOVE_TASK,
                    referenceId);
            if (!moved.isEmpty()) {
                // The same request only: the same cartons to the same place. Anything else reusing the id is another
                // request, refused rather than answered with a move it did not ask for.
                Set<StockPackageId> asked = seen.values().stream().map(StockPackage::id).collect(Collectors.toSet());
                Set<StockPackageId> made = moved.stream().map(StockMovementLog.MovedCarton::carton)
                        .collect(Collectors.toSet());
                LocationId to = new LocationId(toCode);
                if (!asked.equals(made) || moved.stream().anyMatch(m -> !m.to().equals(to))) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                            "Move request %s already moved other cartons, or to another place; send a new request id"
                                    .formatted(referenceId));
                }
                log.info("Move request {} was already served; answering it again", referenceId);
                return views(stockPackages.findAllById(List.copyOf(made)), unitsById);
            }
        }

        List<StockPackage> cartons = stockPackages.findAllByIdForUpdate(
                seen.values().stream().map(StockPackage::id).toList());
        if (cartons.size() != seen.size()) {
            throw new IllegalStateException("A stock package vanished mid-move");
        }
        LocationId to = new LocationId(toCode);
        boolean putAway = target == StockStatus.AVAILABLE && reference == MoveReference.PUTAWAY_TASK;
        Map<StockPackageId, LocationId> from = new LinkedHashMap<>();
        for (StockPackage carton : cartons) {
            if (carton.status() != StockPackageStatus.IN_STOCK) {
                throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                        "%s is %s; only a carton in stock is moved".formatted(carton.lpn(), carton.status()));
            }
            if (putAway && carton.location().equals(to)) {
                requireUsable(to);
            } else {
                requireMovableBetween(carton.location(), to);
            }
            from.put(carton.id(), carton.location());
        }
        // Into a bin, every carton must fit it (kltn-docs 06 BR-01, 11 BR-10): one that does not refuses the move.
        placement.requireFits(toCode, cartons.stream().filter(carton -> !carton.location().equals(to))
                .collect(Collectors.toMap(carton -> carton.id().value(), StockPackage::lpn)));

        List<StockItemId> rowIds = stockItems.findIdsInStockPackages(from.keySet());
        if (byHand) {
            requireNotUnderCount(cartons, rowIds, to);
        }
        List<StockItem> rows = new ArrayList<>();
        for (StockItemId id : rowIds) {
            rows.add(stockItems.findByIdForUpdate(id).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-move".formatted(id))));
        }
        if (byHand) {
            Map<StockPackageId, String> lpnOf = cartons.stream()
                    .collect(Collectors.toMap(StockPackage::id, StockPackage::lpn));
            for (StockItem row : rows) {
                if (!row.reserved().isZero()) {
                    throw new BusinessException(ErrorCode.STOCK_PACKAGE_HELD,
                            "%s is held for an order; it moves only by picking".formatted(
                                    lpnOf.get(row.stockPackageId())),
                            List.of(), Map.of("lpn", lpnOf.get(row.stockPackageId())));
                }
            }
            if (target == null) {
                requireQuarantineByDecision(rows, lpnOf, to);
            }
        }
        if (target != null) {
            Map<StockPackageId, String> lpnOf = cartons.stream()
                    .collect(Collectors.toMap(StockPackage::id, StockPackage::lpn));
            for (StockItem row : rows) {
                if (row.status() != StockStatus.INBOUND && row.status() != StockStatus.QUARANTINE) {
                    throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                            "%s holds %s; only INBOUND or QUARANTINE stock takes a receiving decision"
                                    .formatted(lpnOf.get(row.stockPackageId()), row.status()));
                }
                StockStatus putAwayFrom = quarantineRelease ? StockStatus.QUARANTINE : StockStatus.INBOUND;
                if (target == StockStatus.AVAILABLE && row.status() != putAwayFrom) {
                    throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                            "%s holds %s; this putaway takes %s stock to AVAILABLE"
                                    .formatted(lpnOf.get(row.stockPackageId()), row.status(), putAwayFrom));
                }
                // Quarantined stock leaves quarantine by its release's putaway or an approved adjustment only (20
                // BR-21; ck_stock_movement_quarantine_exit says it again).
                if (target != StockStatus.AVAILABLE && row.status() == StockStatus.QUARANTINE
                        && target != StockStatus.QUARANTINE) {
                    throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                            "%s is in quarantine; it is released or written off, not %s"
                                    .formatted(lpnOf.get(row.stockPackageId()), target));
                }
            }
        }

        for (StockPackage carton : cartons) {
            if (!carton.location().equals(to)) {
                carton.moveTo(to);
                stockPackages.save(carton);
            }
        }
        Instant now = clock.instant();
        Map<StockPackageId, Integer> onHand = new HashMap<>();
        for (StockItem row : rows) {
            StockStatus before = row.status();
            row.followPackageTo(to);
            if (target == StockStatus.AVAILABLE) {
                row.putAwayInPackage(quarantineRelease ? StockStatus.QUARANTINE : StockStatus.INBOUND);
            } else if (target != null) {
                row.reclassifyInPackage(target);
            }
            stockItems.save(row);
            onHand.merge(row.stockPackageId(), row.onHand().value(), Integer::sum);
            if (row.onHand().value() > 0) {
                StockMovement.MovementType type = from.get(row.stockPackageId()).equals(to)
                        ? StockMovement.MovementType.STATUS_CHANGE : StockMovement.MovementType.MOVE;
                ledger.append(new StockMovement(Identifiers.newId(), type, row.sku(),
                        row.lotNumber(), from.get(row.stockPackageId()), to, row.onHand().value(), before,
                        row.status(), StockMovement.ReferenceType.valueOf(reference.name()), referenceId, reason,
                        actorId, now)
                        .ofLot(row.lotId()).leaving(row.stockPackageId()).arriving(row.stockPackageId()));
            }
        }
        log.info("Moved {} carton(s) to {} ({} row(s){})", cartons.size(), to, rows.size(),
                target == null ? "" : ", now " + target);
        return cartons.stream()
                .sorted(Comparator.comparing(StockPackage::lpn))
                .map(carton -> view(carton, unitsById.get(carton.uomConversionId()),
                        onHand.getOrDefault(carton.id(), 0)))
                .toList();
    }

    /**
     * A carton moved by hand keeps its stock status, so a hand move must not do what only a decision does (kltn-docs
     * 20 §4.8, BR-21; team plan 4D-1): sellable stock carried into a QUARANTINE area would sit there still on sale,
     * and quarantined stock carried out to a bin would stand on a pickable shelf undecided. The first is the
     * manager's quarantine, the second the putaway of a release; moves inside quarantine stay free.
     */
    private void requireQuarantineByDecision(List<StockItem> rows, Map<StockPackageId, String> lpnOf, LocationId to) {
        boolean intoQuarantine = locations.isQuarantineArea(to);
        for (StockItem row : rows) {
            if (row.onHand().isZero()) {
                continue;
            }
            if (intoQuarantine && row.status() == StockStatus.AVAILABLE) {
                throw new BusinessException(ErrorCode.LOCATION_AREA_MISMATCH,
                        "%s is on sale; it goes into quarantine by being quarantined, not moved".formatted(
                                lpnOf.get(row.stockPackageId())), List.of(),
                        Map.of("lpn", lpnOf.get(row.stockPackageId())));
            }
            if (!intoQuarantine && row.status() == StockStatus.QUARANTINE) {
                throw new BusinessException(ErrorCode.STOCK_PACKAGE_QUARANTINED,
                        "%s is in quarantine; it leaves by its release's putaway, not a hand move".formatted(
                                lpnOf.get(row.stockPackageId())), List.of(),
                        Map.of("lpn", lpnOf.get(row.stockPackageId())));
            }
        }
    }

    // ------------------------------------------------------------------ quarantine (team plan 4D-1)

    /**
     * Kltn-docs 20 §4.8 step 1, BR-21: a warehouse manager quarantines a whole carton found faulty after it was
     * received. It moves to {@code toCode}, which must be a usable QUARANTINE area of its own warehouse, and its units
     * become QUARANTINE in place, keeping their rows and the carton sealed; one {@code STATUS_CHANGE} ledger line per
     * row records both, from where it stood to the area (BR-16), referencing the carton itself
     * ({@code STOCK_PACKAGE}), with the reason. No four eyes: nothing changes in quantity (decided 2026-11-05).
     *
     * <p>Only a free carton (BR-21): a carton with units held for an order is refused, {@code STOCK_PACKAGE_HELD} -
     * its hold is released or reallocated first (20 §6, "thùng lỗi nằm trong phần đang giữ"). The table says the same
     * ({@code ck_stock_item_quarantine_unheld}). Its stock must be AVAILABLE, or INBOUND (found faulty while being put
     * away; closing its putaway task is the caller's, {@code CartonQuarantineService}); a carton already quarantined
     * is {@code STOCK_PACKAGE_QUARANTINED}. A carton a cycle count holds is refused as a hand move is
     * ({@code STOCK_SLOT_UNDER_COUNT}): the count would be of books that changed under it.</p>
     *
     * <p>Checkout's SKU reservations (on no carton) are not looked at: a quarantine can leave fewer sellable units
     * than live SKU reservations, exactly as a write-off can, and the shortfall shows when the order is released
     * (INSUFFICIENT_STOCK, then AWAITING_STOCK). Refusing the quarantine would keep a faulty carton on sale.</p>
     *
     * <p>Lock order as for any carton write: the SKU, the carton, its rows by id.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-package-quarantine", resourceId = "#lpn")
    public StockPackageView quarantine(String lpn, String toCode, String reason, UUID actorId) {
        String normal = StockPackageView.normalLpn(lpn);
        WarehouseScope.requireLocation(toCode);
        StockPackage seen = stockPackages.findByLpns(List.of(normal)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + normal));
        WarehouseScope.requireLocation(seen.location().code());
        LocationId to = new LocationId(toCode);
        requireUsable(to);
        if (!locations.isQuarantineArea(to)) {
            throw new BusinessException(ErrorCode.LOCATION_AREA_MISMATCH,
                    "%s is not a QUARANTINE area; a carton is quarantined into one".formatted(to));
        }
        if (!seen.location().warehouseCode().equals(to.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s is in another warehouse than %s".formatted(to, normal));
        }
        UomConversion unit = units.findAll(List.of(seen.uomConversionId())).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Stock package %s is in no known unit".formatted(normal)));
        policies.lock(unit.sku());

        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(seen.id())).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Stock package %s vanished".formatted(normal)));
        if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; only a carton in stock is quarantined".formatted(normal, carton.status()));
        }
        List<StockItemId> rowIds = stockItems.findIdsInStockPackages(List.of(carton.id()));
        requireNotUnderCount(List.of(carton), rowIds, to);
        List<StockItem> rows = new ArrayList<>();
        for (StockItemId id : rowIds) {
            rows.add(stockItems.findByIdForUpdate(id).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-quarantine".formatted(id))));
        }
        List<StockItem> live = rows.stream().filter(row -> row.onHand().value() > 0).toList();
        if (live.isEmpty()) {
            throw new IllegalStateException("Stock package %s is in stock with nothing in it".formatted(normal));
        }
        if (rows.stream().anyMatch(row -> !row.reserved().isZero())) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_HELD,
                    "%s is held for an order; release or reallocate it before it is quarantined".formatted(normal),
                    List.of(), Map.of("lpn", normal));
        }
        if (live.stream().allMatch(row -> row.status() == StockStatus.QUARANTINE)) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_QUARANTINED, "%s is already in quarantine"
                    .formatted(normal), List.of(), Map.of("lpn", normal));
        }
        for (StockItem row : rows) {
            if (row.status() != StockStatus.AVAILABLE && row.status() != StockStatus.INBOUND) {
                throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                        "%s holds %s stock; only AVAILABLE or INBOUND stock is quarantined".formatted(normal,
                                row.status()));
            }
        }

        LocationId from = carton.location();
        if (!from.equals(to)) {
            carton.moveTo(to);
            stockPackages.save(carton);
        }
        Instant now = clock.instant();
        int onHand = 0;
        for (StockItem row : rows) {
            StockStatus before = row.status();
            row.followPackageTo(to);
            row.quarantineInPackage();
            stockItems.save(row);
            if (row.onHand().value() > 0) {
                onHand += row.onHand().value();
                ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.STATUS_CHANGE,
                        row.sku(), row.lotNumber(), from, to, row.onHand().value(), before, StockStatus.QUARANTINE,
                        StockMovement.ReferenceType.STOCK_PACKAGE, carton.id().value(), reason, actorId, now)
                        .ofLot(row.lotId()).leaving(carton.id()).arriving(carton.id()));
            }
        }
        log.info("Quarantined carton {} ({} x {}) from {} into {}", LogSafe.text(normal), onHand, unit.sku(), from,
                LogSafe.text(to));
        return view(carton, unit, onHand);
    }

    /** Cartons as they stand now, by LPN. */
    private List<StockPackageView> views(List<StockPackage> cartons, Map<UUID, UomConversion> knownUnits) {
        Map<UUID, UomConversion> unitsById = new HashMap<>(knownUnits);
        units.findAll(cartons.stream().map(StockPackage::uomConversionId).filter(id -> !unitsById.containsKey(id))
                .distinct().toList()).forEach(unit -> unitsById.put(unit.id(), unit));
        Map<StockPackageId, Integer> onHand = stockPackages.onHandOf(cartons.stream().map(StockPackage::id).toList());
        return cartons.stream()
                .sorted(Comparator.comparing(StockPackage::lpn))
                .map(carton -> view(carton, unitsById.get(carton.uomConversionId()),
                        onHand.getOrDefault(carton.id(), 0)))
                .toList();
    }

    /**
     * Kltn-docs 11 BR-06, 20 BR-10 for cartons moved by hand: a carton a count holds stays where it is. Held is exactly
     * what {@code claim} calls busy (open count, or submitted with its variance still pending), so the two cannot
     * disagree. Whoever asks, whatever they may do: this protects the count, it is not a visibility rule.
     */
    private void requireNotUnderCount(List<StockPackage> cartons, List<StockItemId> rowIds, LocationId to) {
        Map<StockPackageId, StockItem> rowOf = new HashMap<>();
        for (StockItemId id : rowIds) {
            stockItems.findById(id).ifPresent(row -> rowOf.putIfAbsent(row.stockPackageId(), row));
        }
        Map<CountSlot, StockPackage> out = new LinkedHashMap<>();
        for (StockPackage carton : cartons) {
            StockItem row = rowOf.get(carton.id());
            if (row != null) {
                out.put(CountSlot.carton(carton.location(), row.sku(), row.lotNumber(), carton.id()), carton);
            }
        }
        Map<CountSlot, String> busy = countLines.claim(out.keySet());
        for (Map.Entry<CountSlot, StockPackage> entry : out.entrySet()) {
            String count = busy.get(entry.getKey());
            if (count != null) {
                CountSlot slot = entry.getKey();
                throw new BusinessException(ErrorCode.STOCK_SLOT_UNDER_COUNT,
                        "%s of %s is under count %s; %s cannot be moved until the count is done".formatted(
                                slot.location(), slot.sku(), count, entry.getValue().lpn()),
                        List.of(), Map.of("locationCode", slot.location().code(), "cycleCountNumber", count));
            }
        }
    }

    // ------------------------------------------------------------------ receipt

    /**
     * Always new stock layers (one per receipt, V20260930001000): the receipt time tells them apart from
     * every other layer of the lot at the location, so nothing is merged into an older receipt.
     *
     * <p>Every unit goes into one of the line's RECEIVING cartons (kltn-docs 03 BR-12; team plan 4C-2) - one INBOUND
     * row per carton holding its standard count, the carton IN_STOCK and sealed, which is exactly what the commit-time
     * check of V20261020000200 wants of it. Nothing goes loose: cartons holding less than the line received are
     * refused ({@code RECEIPT_LINE_NOT_IN_CARTONS}), as {@code ck_stock_movement_receipt_in_carton} refuses a receipt
     * ledger line naming no carton. The cartons are locked after the SKU, as every carton write locks them, and
     * checked before anything is written, so a refusal leaves no lot opened.</p>
     *
     * <p>A replay is recognised by the line's first RECEIPT ledger row, which is the move returned the first time:
     * the first carton's.</p>
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
        // Every unit carries a lot (20 BR-22): a receipt line that names none receives lot UNTRACED.
        String lot = StockLots.orUntraced(command.lotNumber());

        List<StockPackage> packed = lockCartonsOf(command.receiptLineId()).stream()
                .filter(carton -> carton.status() == StockPackageStatus.RECEIVING)
                .toList();
        packed.forEach(StockPackage::requireStandardCount);
        int inCartons = packed.stream().mapToInt(carton -> Objects.requireNonNullElse(carton.receivedQty(), 0)).sum();
        // The invariant stated twice: procurement refuses such a count first (design 3, rule 5).
        if (inCartons > command.quantity()) {
            throw new BusinessException(ErrorCode.RECEIPT_PACKAGES_EXCEED_LINE,
                    "The cartons of receipt line %s hold %d; the line received %d".formatted(command.receiptLineId(),
                            inCartons, command.quantity()));
        }
        if (inCartons < command.quantity()) {
            throw new BusinessException(ErrorCode.RECEIPT_LINE_NOT_IN_CARTONS,
                    "Receipt line %s received %d, and its %d carton(s) hold %d; every unit is received in a carton"
                            .formatted(command.receiptLineId(), command.quantity(), packed.size(), inCartons));
        }
        for (StockPackage carton : packed) {
            // A carton's rows sit where it is (check_stock_package_contents); counting the line moves its cartons.
            if (!carton.location().equals(location)) {
                throw new BusinessException(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION,
                        "%s is at %s, not at %s; count the line again".formatted(carton.lpn(), carton.location(),
                                location));
            }
        }

        // SCRUM-142: the receipt opens the lot - one per receipt and (SKU, lot number) - so a lot number
        // another receipt used before is a different lot, and the trace knows the documents.
        UUID lotId = command.origin() == null ? null : lots.openForReceipt(new StockLots.ReceiptLot(command.sku(), lot,
                command.receivedAt(), command.origin().goodsReceiptId(),
                command.origin().goodsReceiptNumber(), command.origin().warehouseId(), command.origin().productionOrderId(),
                command.origin().productionOrderCode()));
        Instant now = clock.instant();

        StockMove first = null;
        for (StockPackage carton : packed) {
            carton.putInStock();   // refuses a carton never counted, or not at its standard count, before its row
            int counted = carton.receivedQty();
            stockItems.save(StockItem.receiveInboundInto(carton.id(), command.sku(), location, lot,
                    Quantity.of(counted), command.receivedAt(), lotId));
            stockPackages.save(carton);
            StockMovement line = receiptLine(command, lot, location, counted, lotId, now).arriving(carton.id());
            ledger.append(line);
            if (first == null) {
                first = toMove(line);
            }
        }
        log.info("Received {} x {}{} into {}: {} carton(s)", command.quantity(), command.sku(), lotSuffix(lot),
                location, packed.size());
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
                .map(entry -> new ReceivingPackagePlan.Wanted(entry.lpn()))
                .toList());
        Instant now = clock.instant();
        if (unit == null) {
            // No cartons asked for: an empty list names nothing to recount or label, so the plan only voids.
            voidCartons(plan, now);
            log.info("Receipt line {}: {} carton(s) voided", command.receiptLineId(), plan.voided().size());
            return List.of();
        }
        List<StockPackage> live = new ArrayList<>();
        for (StockPackage carton : plan.recounts()) {
            // Every carton of a line is in the line's unit (design 3, rule 7): a new unit voids the old cartons.
            if (!carton.uomConversionId().equals(unit.id())) {
                throw new BusinessException(ErrorCode.RECEIPT_LINE_HAS_PACKAGES,
                        "%s was labelled in another unit; leave it out to void it".formatted(carton.lpn()));
            }
            carton.count();
            if (!carton.location().equals(location)) {
                carton.moveTo(location);
            }
            live.add(stockPackages.save(carton));
        }
        if (plan.newCartons() > 0) {
            UUID itemId = units.itemId(command.sku().code()).orElseThrow(() -> new BusinessException(
                    ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + command.sku()));
            // One block for the line's new cartons: each number drawn alone cost a transaction of its own.
            List<String> lpns = stockPackages.nextLpns(BusinessCalendar.date(now), plan.newCartons());
            for (int i = 0; i < plan.newCartons(); i++) {
                StockPackage carton = StockPackage.receiving(new StockPackageId(Identifiers.newId()), lpns.get(i),
                        itemId, unit.id(), unit.factor(), location, command.receiptLineId());
                carton.count();
                live.add(stockPackages.save(carton));
            }
        }
        voidCartons(plan, now);
        log.info("Receipt line {}: {} carton(s) recounted, {} labelled, {} voided", command.receiptLineId(),
                plan.recounts().size(), plan.newCartons(), plan.voided().size());
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
                carton.nominalQty(), quantity, carton.receivedQty(), carton.location().code(), carton.status().name());
    }

    private void requireUsable(LocationId location) {
        switch (locations.stateOf(location)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + location);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(location));
            case USABLE -> { }
        }
    }

    // ------------------------------------------------------------------ returns

    // ------------------------------------------------------------------ adjustments

    /**
     * By LPN only: a carton written off whole ({@link #requestCartonWriteOff}). Stock is kept in whole cartons (team
     * plan 4C-1/4C-2, {@code ck_stock_item_whole_cartons}), so there is no loose stock to adjust - a request naming
     * none is {@code STOCK_NOT_IN_CARTON} (review P-3; the loose path went with it). A carton found is restocked by a
     * count of it; a carton is adjusted either way by {@link #requestPackageAdjustment}.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#result?.adjustmentId()")
    public StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command) {
        // Said here so a typed-in request hears why; the table would refuse it without a count line
        // (ck_stock_adjustment_count_variance), which reaches the client as a 500.
        if (command.reason() == StockAdjustmentReason.COUNT_VARIANCE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A count variance comes from a cycle count");
        }
        // Cups and packaging have no expiry (kltn-docs 01 BR-17, 20 BR-13): EXPIRED stays only on adjustments made
        // before team plan 5-1. tg_stock_adjustment_no_new_expired refuses it in the table as well.
        if (command.reason() == StockAdjustmentReason.EXPIRED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Stock does not expire; write it off as DAMAGED, LOST or OTHER");
        }
        // Scope before anything about the stock named (BR-SEC-002): a request at another warehouse's location hears
        // OUT_OF_DATA_SCOPE, never what is or is not there.
        if (command.locationCode() != null && !command.locationCode().isBlank()) {
            WarehouseScope.requireLocation(command.locationCode());
        }
        if (command.lpn() == null || command.lpn().isBlank()) {
            throw new BusinessException(ErrorCode.STOCK_NOT_IN_CARTON, ("%s at %s: name the carton by its LPN; stock is "
                    + "kept in whole cartons").formatted(command.sku(), command.locationCode()));
        }
        return requestCartonWriteOff(command);
    }

    /**
     * A person asks for a whole carton to be written off, named by its LPN (kltn-docs 20 BR-14: adjusted whole, never
     * in part; §4.8 step 3: a quarantined carton that cannot be used leaves stock as an approved {@code DAMAGED}
     * adjustment - BR-21, team plan 4D-1). The delta must be minus what the carton holds
     * ({@code STOCK_PACKAGE_TAKEN_WHOLE} otherwise); a location or SKU given with the LPN must be the carton's. The
     * adjustment names the carton, so its approval drains and consumes the carton whole
     * ({@link StockPackageContents#adjust}). A carton with an open putaway task (a release, or a receipt's carton not
     * yet put away) is already decided the other way: {@code STOCK_PACKAGE_DECISION_PENDING}.
     */
    private StockAdjustmentSummary requestCartonWriteOff(RequestAdjustmentCommand command) {
        AdjustmentReason reason = AdjustmentReason.valueOf(command.reason().name());
        if (!reason.askableByHand()) {
            // Review P-2: said first, so SCRAP or SAMPLE hears why instead of reaching tg_stock_adjustment_whole_carton.
            throw new BusinessException(ErrorCode.STOCK_ADJUSTMENT_REASON_NOT_FOR_CARTON,
                    "A carton is adjusted for DAMAGED, LOST, FOUND, DATA_CORRECTION or OTHER, not " + reason);
        }
        String normal = StockPackageView.normalLpn(command.lpn());
        StockPackage carton = stockPackages.findByLpns(List.of(normal)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + normal));
        WarehouseScope.requireLocation(carton.location().code());
        if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; there is nothing in it to write off".formatted(normal, carton.status()));
        }
        if (command.locationCode() != null && !command.locationCode().isBlank()
                && !new LocationId(command.locationCode()).equals(carton.location())) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION,
                    "%s is at %s, not %s".formatted(normal, carton.location(), command.locationCode()));
        }
        UomConversion unit = units.findAll(List.of(carton.uomConversionId())).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Stock package %s is in no known unit".formatted(normal)));
        Sku sku = new Sku(unit.sku());
        if (command.sku() != null && !command.sku().equals(sku)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "%s holds %s, not %s".formatted(normal, sku,
                    command.sku()));
        }
        // Under the SKU lock, as a release and an approval take it: a release opened at the same moment either is
        // seen below or sees this request pending.
        policies.lock(unit.sku());
        int onHand = stockPackages.onHandOf(List.of(carton.id())).getOrDefault(carton.id(), 0);
        if (command.quantityDelta() != -onHand) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE,
                    "%s holds %d; it is written off whole (quantityDelta %d), never in part".formatted(normal, onHand,
                            -onHand), List.of(), Map.of("lpn", normal));
        }
        requireNoOpenPutaway(carton);
        String lot = stockItems.findIdsInStockPackage(carton.id()).stream()
                .map(stockItems::findById).flatMap(Optional::stream)
                .filter(row -> row.onHand().value() > 0).map(StockItem::lotNumber).filter(Objects::nonNull)
                .findFirst().orElse(null);
        Instant now = clock.instant();
        StockAdjustment adjustment = StockAdjustment.request(
                        adjustments.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), carton.location(), sku, lot,
                        command.quantityDelta(), AdjustmentReason.valueOf(command.reason().name()), command.note(),
                        command.requestedBy(), now)
                .inPackage(carton.id());
        log.info("Write-off of carton {} ({} x {}, {}) asked", normal, onHand, sku, command.reason());
        return toSummary(adjustments.save(adjustment));
    }

    /**
     * A carton with an open putaway task is on its way into a bin: a release from quarantine, or a receipt's carton
     * not yet put away. Writing it off as well would leave the task pointing at a carton that is gone, and the release
     * already decided the carton is usable (kltn-docs 20 §4.8 step 3: release or write off, one of the two).
     */
    private void requireNoOpenPutaway(StockPackage carton) {
        putawayTasks.openTaskOf(carton.id().value()).ifPresent(task -> {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_DECISION_PENDING,
                    "%s has an open putaway task (%s); put it away, or write it off once that is done"
                            .formatted(carton.lpn(), task.source()), List.of(), Map.of("lpn", carton.lpn()));
        });
    }

    /**
     * The carton is the one submission judged, under its lock, and the assignee and the count's state were checked by
     * the caller. One adjustment of the whole carton (kltn-docs 20 §4.2 c8): a carton not found written off, a LOST
     * carton found restocked; approval changes the carton's row and state once ({@link StockPackageContents#adjust}).
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "cycle-count-line", resourceId = "#cycleCountLineId")
    public StockAdjustmentSummary requestCountVariance(UUID cycleCountLineId, CountSlot slot, int delta,
                                                       UUID requestedBy, String note) {
        Objects.requireNonNull(cycleCountLineId, "cycleCountLineId");
        if (delta == 0 || !slot.isCarton()) {
            throw new IllegalArgumentException("A count variance is a whole carton found against the books");
        }
        WarehouseScope.requireLocation(slot.location().code());
        requireNoSurplusInStaging(slot.location(), delta);
        policies.lock(slot.sku().code());
        // A count's claim leaves out a carton with a pending adjustment (CountBusy.PENDING_ADJUSTMENT); said again here,
        // with its own code, rather than as the unique index's bare conflict.
        adjustments.findPendingForPackage(slot.stockPackageId()).ifPresent(pending -> {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_ADJUSTMENT_PENDING,
                    "Adjustment %s of this carton is waiting for approval".formatted(pending.number()));
        });
        Instant now = clock.instant();
        StockAdjustment adjustment = StockAdjustment.requestCarton(
                adjustments.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), slot.stockPackageId(),
                slot.location(), slot.sku(), slot.lotNumber(),
                delta < 0 ? AdjustmentDirection.WRITE_OFF : AdjustmentDirection.RESTOCK, Math.abs(delta),
                AdjustmentReason.COUNT_VARIANCE, note, cycleCountLineId, requestedBy, now);
        log.info("Count line {}: carton {} {} asked", cycleCountLineId, slot.stockPackageId().value(),
                delta < 0 ? "write-off" : "restock");
        return toSummary(adjustments.save(adjustment));
    }

    /**
     * Kltn-docs 20 §4.3: a carton, by its LPN, written off whole (it must be in stock, held for no order) or restocked
     * whole (it must be LOST), for one of BR-04's reasons. Nothing changes until another person approves (BR-05).
     *
     * <p>Lock order of every carton writer: the SKU, the carton's slot (the advisory lock a count's claim takes), then
     * the carton. A count planned at the same moment waits for that lock and then finds this request pending, which
     * makes the carton busy for it ({@code CountBusy.PENDING_ADJUSTMENT}): it is left out of the plan, or refused as a
     * found line. A carton with an adjustment already pending is decided once ({@code STOCK_PACKAGE_ADJUSTMENT_PENDING},
     * said before the count check, since that adjustment also makes the carton busy), and a carton a count holds
     * (BR-10) is the count's to correct ({@code STOCK_SLOT_UNDER_COUNT}); the database refuses a second pending one
     * ({@code uk_stock_adjustment_pending_package}).</p>
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#result?.adjustmentId()")
    public StockAdjustmentSummary requestPackageAdjustment(PackageAdjustmentRequest request) {
        AdjustmentReason reason = AdjustmentReason.valueOf(request.reason().name());
        if (!reason.askableByHand()) {
            // Said first, so a typed-in COUNT_VARIANCE or a reason of the history hears why before any lookup.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason == AdjustmentReason.COUNT_VARIANCE
                    ? "A count variance comes from a cycle count"
                    : "A carton is adjusted for DAMAGED, LOST, FOUND, DATA_CORRECTION or OTHER, not " + reason);
        }
        String lpn = StockPackageView.normalLpn(request.lpn());
        StockPackage seen = (lpn == null ? Optional.<StockPackage>empty() : stockPackages.findByLpn(lpn))
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + lpn));
        WarehouseScope.requireLocation(seen.location().code());
        StockItem row = stockItems.findIdsInStockPackage(seen.id()).stream().map(stockItems::findById)
                .flatMap(Optional::stream).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                        "%s has never held stock".formatted(seen.lpn())));
        policies.lock(row.sku().code());
        CountSlot slot = CountSlot.carton(seen.location(), row.sku(), row.lotNumber(), seen.id());
        String count = countLines.claim(List.of(slot)).get(slot);
        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(seen.id())).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Stock package %s vanished".formatted(seen.lpn())));
        requireNoPendingAdjustment(carton);
        if (count != null) {
            throw new BusinessException(ErrorCode.STOCK_SLOT_UNDER_COUNT,
                    "%s is on count %s; the count corrects it".formatted(carton.lpn(), count),
                    List.of(), Map.of("locationCode", carton.location().code(), "cycleCountNumber", count));
        }
        StockItem locked = stockItems.findByIdForUpdate(row.id()).orElseThrow();
        switch (request.direction()) {
            case WRITE_OFF -> {
                if (carton.status() != StockPackageStatus.IN_STOCK) {
                    throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                            "%s is %s; only a carton in stock is written off".formatted(carton.lpn(), carton.status()));
                }
                if (!locked.reserved().isZero()) {
                    throw new BusinessException(ErrorCode.STOCK_PACKAGE_HELD,
                            "%s is held for an order; release or reallocate it before writing it off"
                                    .formatted(carton.lpn()), List.of(), Map.of("lpn", carton.lpn()));
                }
                // Team plan 4D-1 (20 §4.8 step 3): a quarantined carton released to a putaway task is not also written off.
                requireNoOpenPutaway(carton);
            }
            case RESTOCK -> {
                if (carton.status() != StockPackageStatus.LOST) {
                    throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                            "%s is %s; only a LOST carton is restocked".formatted(carton.lpn(), carton.status()));
                }
            }
        }
        Instant now = clock.instant();
        StockAdjustment adjustment = StockAdjustment.requestCarton(
                adjustments.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), carton.id(), carton.location(),
                locked.sku(), locked.lotNumber(), request.direction(), carton.nominalQty(), reason, request.note(),
                null, request.requestedBy(), now);
        log.info("Adjustment {} asked: {} of {} ({})", adjustment.number(), request.direction(), carton.lpn(), reason);
        return toSummary(adjustments.save(adjustment));
    }

    /** One adjustment of a carton waits for approval at a time ({@code uk_stock_adjustment_pending_package}). */
    private void requireNoPendingAdjustment(StockPackage carton) {
        adjustments.findPendingForPackage(carton.id()).ifPresent(pending -> {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_ADJUSTMENT_PENDING,
                    "Adjustment %s of %s is waiting for approval".formatted(pending.number(), carton.lpn()),
                    List.of(), Map.of("lpn", carton.lpn(), "adjustmentNumber", pending.number()));
        });
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "stock-adjustment", resourceId = "#adjustmentId")
    public StockAdjustmentSummary approve(UUID adjustmentId, UUID approverId) {
        StockAdjustment adjustment = load(adjustmentId);
        policies.lock(adjustment.sku().code());
        requireNotCounter(adjustment, approverId);
        if (adjustment.stockPackageId() != null) {
            return approveCarton(adjustment, approverId);
        }
        // An adjustment of no carton was asked against loose stock, which holds nothing since stock is kept in whole
        // cartons (ck_stock_item_whole_cartons): there is nothing to post. Rejected, it is decided (review P-3).
        throw new BusinessException(ErrorCode.STOCK_NOT_IN_CARTON, ("Adjustment %s names no carton; stock is kept in "
                + "whole cartons, so reject it").formatted(adjustment.number()));
    }

    /**
     * A carton's adjustment, under the SKU lock the caller took: {@link StockPackageContents#adjust} locks the carton
     * and then its row, writes it off or restocks it whole and changes the carton once (#125 lock order). A write-off
     * for LOST or a count's shortage leaves the carton LOST, so it can be found again (20 §4.6 step 7); any other
     * write-off CONSUMED. Decided first, so a requester approving their own request hears that rather than a stock
     * refusal; a refusal while posting rolls the decision back. One ledger line, naming the carton on the side that
     * has it - the side whose location the loose path names.
     */
    private StockAdjustmentSummary approveCarton(StockAdjustment adjustment, UUID approverId) {
        Instant now = clock.instant();
        adjustment.approveForCarton(approverId, now);
        if (adjustment.reason() != AdjustmentReason.COUNT_VARIANCE && adjustment.quantityDelta() < 0) {
            // Asked through requestPackageAdjustment or requestCartonWriteOff; a release may have been opened since (under
            // this SKU lock, so it is seen here or waits for this write-off).
            stockPackages.findAllById(List.of(adjustment.stockPackageId())).stream().findFirst()
                    .ifPresent(this::requireNoOpenPutaway);
        }
        // A count's shortage of a carton the order still holds is handled as a shortage (20 §4.2 step 8): when its
        // pick line is already reported SHORT, the hold is given up with the carton and the line waits, as it does,
        // for the warehouse manager's reallocation. Any other adjustment keeps refusing a held carton.
        List<StockPackageContents.RowChange> changes = cartons.adjust(adjustment.stockPackageId(),
                adjustment.quantityDelta(), adjustment.reason().leavesCartonLost(),
                adjustment.reason() == AdjustmentReason.COUNT_VARIANCE, now);
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
            // The hold a short line stood on, if the write-off gave it up: announced after the row is saved.
            events.publishEventsOf(row);
        }
        log.info("Posted adjustment {}: {} {} in carton {} at {}", adjustment.number(), adjustment.quantityDelta(),
                adjustment.sku(), adjustment.stockPackageId().value(), adjustment.location());
        return toSummary(saved);
    }

    /**
     * A count variance is decided by someone other than whoever counted its line (spec §6 four eyes, SCRUM-146): the
     * requester check of the aggregate only sees the submitter, who is not always the one who entered the figure
     * (an approver may count a line on an assigned count). A line's counter is fixed once the count is submitted.
     */
    private void requireNotCounter(StockAdjustment adjustment, UUID decider) {
        if (adjustment.cycleCountLineId() != null) {
            adjustment.requireNotCountedBy(decider, countLines.countedByOf(adjustment.cycleCountLineId()).orElse(null));
        }
    }

    /** The ledger's reason for a posted adjustment: its reason code, and the note when there is one. */
    private static String ledgerReason(StockAdjustment adjustment) {
        return adjustment.reason() + (adjustment.note() == null ? "" : ": " + adjustment.note());
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "stock-adjustment", resourceId = "#adjustmentId")
    public StockAdjustmentSummary reject(UUID adjustmentId, UUID approverId, String reason) {
        StockAdjustment adjustment = load(adjustmentId);
        requireNotCounter(adjustment, approverId);
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
    public Optional<StockAdjustmentSummary> findAdjustment(UUID adjustmentId, BookVisibility visibility) {
        // Out of the caller's warehouses reads as not found: its existence is not theirs to know. So does the
        // adjustment of a slot under a count, for a reader blind to its books.
        return adjustments.findById(adjustmentId)
                .filter(adjustment -> inScope(adjustment.location().code()))
                .filter(adjustment -> !visibility.hidesCountedSlots() || !underCount.adjustmentUnderCount(adjustmentId))
                .map(StockOperationsServiceImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                           int page, int size, String sort,
                                                           BookVisibility visibility) {
        var criteria = new StockAdjustmentSearch.Criteria(
                status == null ? null : AdjustmentStatus.valueOf(status.name()), sku, location,
                visibility.hidesCountedSlots());
        return Pages.toResponse(adjustmentSearch.search(criteria, Pages.of(page, size, ADJUSTMENT_SORT.parse(sort)))
                .map(StockOperationsServiceImpl::toSummary));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LedgerLine> ledger(String sku, String location, StockMovement.MovementType type,
                                           int page, int size, BookVisibility visibility) {
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));
        return Pages.toResponse(ledgerSearch.search(new StockLedgerSearch.Criteria(sku, location, type,
                        visibility.hidesCountedSlots()), pageable)
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
