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
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
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

    StockOperationsServiceImpl(StockItemRepository stockItems, StockAdjustmentRepository adjustments,
                               StockAdjustmentSearch adjustmentSearch, StockMovementLog ledger,
                               StockLedgerSearch ledgerSearch, LocationDirectory locations, Clock clock,
                               InventoryPolicyRepository policies, StockLots lots, StockPackageContents cartons,
                               InventoryEventPublisher events, StockPackageRepository stockPackages,
                               UomConversionRepository units, SlotsUnderCount underCount,
                               CycleCountRepository countLines) {
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

    // ------------------------------------------------------------------ loose stock: returns only

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.referenceId()")
    public StockMove reclassify(ReclassifyStockCommand command) {
        return relocate(command.sku(), command.lotNumber(), command.receivedAt(), command.fromLocation(),
                command.toLocation(), command.quantity(), StockStatus.valueOf(command.disposition().name()),
                StockMovement.ReferenceType.valueOf(command.reference().name()), command.referenceId(),
                command.actorId(), blankToNull(command.reason()));
    }

    /**
     * The last loose move: a customer return's decision ({@code ReturnRequestService}), whose goods
     * {@link #receiveReturn} still counts in loose. Nothing else moves loose units since team plan 4C-2 - a person
     * moves cartons whole ({@link #moveStockPackages}), putaway puts cartons away - and this goes too when returns are
     * received by carton (team plan 4D-2, kltn-docs 20 §4.4). {@code receivedAt} picks the stock layer when the source
     * holds several.
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

        // Replay first: a retried move must not move the goods again. Loose lines only - this moves loose stock,
        // and a carton moved whole under the same reference is not this move's earlier attempt (SCRUM-473).
        Optional<StockMovement> done = ledger.findLooseByReference(StockMovement.MovementType.MOVE, referenceType,
                referenceId);
        if (done.isPresent()) {
            return toMove(done.get());
        }

        return moveLayer(sku, lotNumber, receivedAt, new LocationId(fromCode), new LocationId(toCode), qty, target,
                referenceType, referenceId, actorId, reason);
    }

    /** What {@link #relocate} does once scope, the SKU lock and the replay lookup are behind it. */
    private StockMove moveLayer(Sku sku, String lotNumber, Instant receivedAt, LocationId from, LocationId to,
                                int qty, StockStatus target, StockMovement.ReferenceType referenceType,
                                UUID referenceId, UUID actorId, String reason) {
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
                sku, source.lotNumber(), from, to, quantity.value(), before, arriving,
                referenceType, referenceId, reason, actorId, now).ofLot(source.lotId());
        ledger.append(line);
        log.info("Moved {} x {}{} from {} to {} ({} -> {})", quantity, sku, lotSuffix(lotNumber), from, to,
                before, arriving);
        return toMove(line);
    }

    /**
     * What every move of stock needs of its two ends, loose or in a carton: two different locations, in one
     * warehouse (docs 11 BR-01: stock never changes warehouse - a Production Order delivers straight to the warehouse
     * that ships), and a destination that can take stock. Only the destination is checked (issue
     * #67): taking stock out of a blocked bin or a shelf in maintenance is how it gets emptied, and an adjustment
     * at a bin blocked for counting must still post.
     */
    private void requireMovableBetween(LocationId from, LocationId to) {
        if (from.equals(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A move needs two different locations");
        }
        if (!from.warehouseCode().equals(to.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s and %s are in different warehouses; stock moves only inside one".formatted(from, to));
        }
        requireUsable(to);
    }

    // ------------------------------------------------------------------ a carton, whole

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-package-move", resourceId = "#command.referenceId()")
    public List<StockPackageView> moveStockPackages(MoveStockPackagesCommand command) {
        return relocatePackages(command.lpns(), command.toLocationCode(), null, command.reference(),
                command.referenceId(), command.actorId(), null);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-package-move", resourceId = "#command.referenceId()")
    public List<StockPackageView> reclassifyStockPackages(ReclassifyStockPackagesCommand command) {
        return relocatePackages(command.lpns(), command.toLocationCode(),
                StockStatus.valueOf(command.disposition().name()), command.reference(), command.referenceId(),
                command.actorId(), blankToNull(command.reason()));
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
     * <p>One exception: a putaway ({@code PUTAWAY_TASK}, to AVAILABLE) of a carton somebody already moved by hand into
     * the chosen bin. It has nowhere to move to, so it is reclassified where it stands, and its ledger line is a
     * {@code STATUS_CHANGE} from the bin to the bin ({@code MOVE} names two different places). Every other move to
     * the place a carton is in is still refused.</p>
     */
    private List<StockPackageView> relocatePackages(List<String> lpns, String toCode, StockStatus target,
                                                    MoveReference reference, UUID referenceId, UUID actorId,
                                                    String reason) {
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
            List<StockPackageId> moved = ledger.cartonsMovedBy(StockMovement.ReferenceType.MOVE_TASK, referenceId);
            if (!moved.isEmpty()) {
                log.info("Move request {} was already served; answering it again", referenceId);
                return views(stockPackages.findAllById(moved), unitsById);
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
                if (target == StockStatus.AVAILABLE && row.status() != StockStatus.INBOUND) {
                    throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                            "%s holds %s; only INBOUND stock is put away as AVAILABLE"
                                    .formatted(lpnOf.get(row.stockPackageId()), row.status()));
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
            if (target != null) {
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

        // SCRUM-142: the receipt opens the lot - one per receipt and (SKU, lot number, expiry) - so a lot number
        // another receipt used before is a different lot, and the trace knows the supplier and the documents.
        UUID lotId = command.origin() == null ? null : lots.openForReceipt(new StockLots.ReceiptLot(command.sku(), lot,
                command.expiryDate(), command.receivedAt(), command.origin().goodsReceiptId(),
                command.origin().goodsReceiptNumber(), command.origin().purchaseOrderId(),
                command.origin().purchaseOrderNumber(), command.origin().supplierId(), command.origin().supplierName(),
                command.origin().warehouseId()));
        Instant now = clock.instant();

        StockMove first = null;
        for (StockPackage carton : packed) {
            carton.putInStock();   // refuses a carton never counted, or not at its standard count, before its row
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

    /**
     * SCRUM-260: a returned parcel's goods into quarantine (OQ-12: never straight into sellable stock). The lines of one
     * parcel are counted at one time, and two of them may carry the same SKU and lot (two designs printed on one blank,
     * a wrong item that is another line's SKU): they share the layer the first one opened, as the natural key
     * (SKU, location, lot, receipt time) says they must.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-return-receipt", resourceId = "#command.returnLineId()")
    public StockMove receiveReturn(com.stockflow.inventory.api.ReceiveReturnCommand command) {
        WarehouseScope.requireLocation(command.locationCode());
        policies.lock(command.sku().code());
        // One ledger line per parcel of the line: a retried parcel finds the line's total already reached.
        long booked = ledger.sumQuantityByReference(List.of(StockMovement.MovementType.RETURN_IN),
                StockMovement.ReferenceType.RETURN_REQUEST_LINE, command.returnLineId());
        if (booked >= command.lineTotal()) {
            return toMove(ledger.findByReference(StockMovement.MovementType.RETURN_IN,
                    StockMovement.ReferenceType.RETURN_REQUEST_LINE, command.returnLineId()).orElseThrow());
        }
        if (booked + command.quantity() != command.lineTotal()) {
            throw new IllegalStateException("Return line %s has %d unit(s) booked; %d more do not make %d".formatted(
                    command.returnLineId(), booked, command.quantity(), command.lineTotal()));
        }
        LocationId location = new LocationId(command.locationCode());
        switch (locations.stateOf(location)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + location);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(location));
            case USABLE -> { }
        }
        // Goods returned with no lot are of lot UNTRACED, as every unit carries a lot (20 BR-22).
        String lot = StockLots.orUntraced(command.lotNumber());
        Quantity quantity = Quantity.of(command.quantity());
        Optional<StockItem> layer = findLayer(command.sku(), location, lot, command.receivedAt());
        if (layer.isPresent()) {
            StockItem item = stockItems.findByIdForUpdate(layer.get().id()).orElseThrow();
            requireLoose(item);
            if (item.status() != StockStatus.QUARANTINE) {
                throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                        "%s holds %s%s as %s; returned goods arrive in quarantine".formatted(location, command.sku(),
                                lotSuffix(lot), item.status()));
            }
            item.moveIn(quantity);
            // The same receipt, not a replenishment: the policy trigger lets a FIFO layer grow only when told so.
            stockItems.saveMerged(item);
        } else {
            stockItems.save(StockItem.receive(command.sku(), location, lot, null, quantity, command.receivedAt(), null));
        }
        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.RETURN_IN,
                command.sku(), lot, null, location, quantity.value(), StockStatus.QUARANTINE,
                StockMovement.ReferenceType.RETURN_REQUEST_LINE, command.returnLineId(), "Returned by the customer",
                command.actorId(), clock.instant()).forOrder(command.orderId());
        ledger.append(line);
        log.info("Returned {} x {}{} into {}", quantity, command.sku(), lotSuffix(lot), location);
        return toMove(line);
    }

    // ------------------------------------------------------------------ adjustments

    /**
     * Loose stock only (rows in no carton): what a customer return still asks for its returned goods (scrap, sent
     * back), until returns are received by carton (team plan 4D-2/4D-3). A carton is adjusted whole, by LPN
     * ({@link #requestPackageAdjustment}); the lookup reaches no carton's row ({@link #findLayer}).
     */
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
                StockLots.orUntraced(command.lotNumber()), command.quantityDelta(),
                AdjustmentReason.valueOf(command.reason().name()), command.note(), command.requestedBy(), now)
                .atLayer(command.receivedAt());
        return toSummary(adjustments.save(adjustment));
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
        policies.lock(slot.sku().code());
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
     * <p>Lock order of every carton writer: the SKU, the carton's slot (the advisory lock a count's claim takes, so a
     * count planned now sees this request or waits for it), then the carton. A carton a count holds (BR-10) is the
     * count's to correct, and a carton with an adjustment already pending is decided once: both are refused, as the
     * database refuses the second pending one ({@code uk_stock_adjustment_pending_package}).</p>
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
        if (count != null) {
            throw new BusinessException(ErrorCode.STOCK_SLOT_UNDER_COUNT,
                    "%s is on count %s; the count corrects it".formatted(carton.lpn(), count),
                    List.of(), Map.of("locationCode", carton.location().code(), "cycleCountNumber", count));
        }
        adjustments.findPendingForPackage(carton.id()).ifPresent(pending -> {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_ADJUSTMENT_PENDING,
                    "Adjustment %s of %s is waiting for approval".formatted(pending.number(), carton.lpn()),
                    List.of(), Map.of("lpn", carton.lpn(), "adjustmentNumber", pending.number()));
        });
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

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "stock-adjustment", resourceId = "#adjustmentId")
    public StockAdjustmentSummary approve(UUID adjustmentId, UUID approverId) {
        StockAdjustment adjustment = load(adjustmentId);
        policies.lock(adjustment.sku().code());
        requireNotCounter(adjustment, approverId);
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
            // Legacy (BR-PRD-10, while in-house production existed): a cancelled order's printed goods were kept under
            // its hold until this decision, so nobody could take them. They leave the hold as they leave stock - once:
            // the hold closes here, and a second approval is refused by the adjustment's own state. Nothing asks for
            // such an adjustment any more; one still pending is decided as it was asked.
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
     * and then its row, writes it off or restocks it whole and changes the carton once (#125 lock order). A write-off
     * for LOST or a count's shortage leaves the carton LOST, so it can be found again (20 §4.6 step 7); any other
     * write-off CONSUMED. Decided first, so a requester approving their own request hears that rather than a stock
     * refusal; a refusal while posting rolls the decision back. One ledger line, naming the carton on the side that
     * has it - the side whose location the loose path names.
     */
    private StockAdjustmentSummary approveCarton(StockAdjustment adjustment, UUID approverId) {
        Instant now = clock.instant();
        adjustment.approveForCarton(approverId, now);
        List<StockPackageContents.RowChange> changes = cartons.adjust(adjustment.stockPackageId(),
                adjustment.quantityDelta(), adjustment.reason().leavesCartonLost(), now);
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

    /**
     * {@link #findOne}, or the one loose layer received at {@code receivedAt} when it is given. Loose stock only:
     * a move, a reclassification or an adjustment named by (SKU, location, lot, receipt time) cannot tell one
     * carton of a receipt from another, so stock in a carton is moved and adjusted through its carton.
     */
    private Optional<StockItem> findLayer(Sku sku, LocationId location, String lotNumber, Instant receivedAt) {
        if (receivedAt == null) {
            return findOne(sku, location, lotNumber);
        }
        String lot = StockLots.orUntraced(lotNumber);
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
        String lot = StockLots.orUntraced(lotNumber);
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
