package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.domain.PutawayRanker.Bin;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovement.MovementType;
import com.stockflow.inventory.internal.domain.StockMovement.ReferenceType;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.warehouse.api.PutawayCandidate;
import com.stockflow.warehouse.api.PutawaySource;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.PutawayTrips;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reading putaway tasks (SCRUM-92): a task with what is still to put away and ranked bins to put it into, and a list
 * of tasks. The warehouse module keeps the tasks; what is left, where the goods stand and where they could go are
 * facts of the stock, so they are read here.
 *
 * <p>A task has one of two sources (kltn-docs 05 BR-01): a carton of a confirmed receipt, INBOUND at the receiving
 * area, or a carton a warehouse manager released from quarantine (team plan 4D-1), QUARANTINE at the quarantine area;
 * putting either away makes it AVAILABLE (05 BR-14). A release names no receipt line, so its lot is read from the
 * carton itself, and finishing it announces no receipt's putaway - {@link PutawayCompleted} is for the receipt's
 * closing - but {@code QuarantineStockReleased}: the carton is stock to promise again.</p>
 *
 * <p>Every task is one carton, put away whole (kltn-docs 05 BR-08; team plan 4C-2). Where a task's goods stand now
 * (its <i>source</i>) is derived, never stored: the carton's current bin, wherever a hand move has taken it. A task
 * without a carton cannot exist ({@code ck_putaway_task_source}, {@code ck_putaway_task_receipt_carton}), and the
 * ledger lines of loose stock such a task was once located by went with V20261231000400. Scope is checked on the
 * source before anything else is said about a task, so a task of another warehouse is 403 and not 404 or a
 * status.</p>
 *
 * <p>{@link #confirm} puts goods away in one transaction, in the order the spec fixes: the task row first (as the pick
 * row comes first when picking), then the scope of its source, the scan, the bin the clerk chose, the move, the
 * task's own bookkeeping and last the event. Everything a bin must satisfy is checked here again for a bin the
 * clerk picked by hand, because the suggestions are only advice.</p>
 *
 * <p>Lock order of {@link #confirm}: task row, then the target bin's advisory lock (taken before the bin's occupancy
 * is read: occupancy is bin-wide across SKUs, so only a lock on the bin serialises two putaways of different SKUs
 * into it), then the SKU advisory lock and the carton row, as in the move itself.</p>
 *
 * <p>Suggestions are computed on {@link #get} only, from the bins and the stock of this moment, and never stored: a
 * suggestion is stale as soon as someone else puts goods away.</p>
 *
 * <p>Sources are read in a constant number of queries however many tasks there are: one read of the tasks' own
 * cartons by id, and one for the locations of the warehouse filter. Lots, remaining quantities of tasks in progress and target codes are read for the returned
 * page only, one read per task in progress and one per distinct target.</p>
 */
@Service
public class PutawayService {

    private static final Logger log = LoggerFactory.getLogger(PutawayService.class);

    /** The ledger lines a putaway writes: a move into the bin, or a status change where the goods already stood. */
    private static final Set<MovementType> PUT_AWAY = Set.of(MovementType.MOVE, MovementType.STATUS_CHANGE);

    private final PutawayTasks tasks;
    private final WarehouseService warehouses;
    private final StockPackageRepository stockPackages;
    private final StockMovementLog ledger;
    private final BinOccupancyQuery occupancy;
    private final StockOperations operations;
    private final ApplicationEventPublisher events;
    private final PutawayTrips trips;
    private final BinSuggestionInputs inputs;

    public PutawayService(PutawayTasks tasks, WarehouseService warehouses, StockPackageRepository stockPackages,
                          StockMovementLog ledger, BinOccupancyQuery occupancy,
                          StockOperations operations, ApplicationEventPublisher events,
                          PutawayTrips trips, BinSuggestionInputs inputs) {
        this.trips = trips;
        this.inputs = inputs;
        this.tasks = tasks;
        this.warehouses = warehouses;
        this.stockPackages = stockPackages;
        this.ledger = ledger;
        this.occupancy = occupancy;
        this.operations = operations;
        this.events = events;
    }

    /**
     * @param lotNumber          of the receipt line's goods; null when the line has none
     * @param quantity           what the task was opened for
     * @param left               what is still to put away
     * @param sourceLocationCode where the goods stand now
     * @param warehouseId        the warehouse of the source location: the task's own, since stock never changes
     *                           warehouse; null only when that location is unknown
     * @param lpn                the carton's
     * @param targetLocationCode the bin (or OVERFLOW area) of the latest putaway; null until something was put away
     * @param suggestions        ranked bins; filled by {@link #get} only
     * @param overflowLocationCodes filled by {@link #get} only, when no bin takes the carton: the usable OVERFLOW
     *                           areas of its warehouse, where it may go instead (kltn-docs 05 BR-13)
     * @param source             why the task exists; a {@code QUARANTINE_RELEASE} has no receipt or receipt line
     */
    public record TaskView(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, String lotNumber,
                           int quantity, int left, String sourceLocationCode, UUID warehouseId, String lpn,
                           String status, String targetLocationCode, List<PutawayRanker.Suggestion> suggestions,
                           List<String> overflowLocationCodes, PutawaySource source) {
    }

    /** Where a task's goods stand now. */
    private record Source(String locationCode, String lpn) {
    }

    /** What the reads of one request have already fetched, so a receipt line shared by tasks is read once. */
    private static final class Reads {
        final Map<UUID, Optional<StockMovement>> receiptLine = new HashMap<>();
        final Map<UUID, String> locationCodes = new HashMap<>();
        final Map<String, Optional<UUID>> locationWarehouses = new HashMap<>();
        final Map<UUID, Optional<String>> cartonLot = new HashMap<>();
    }

    /**
     * @throws BusinessException {@code PUTAWAY_TASK_NOT_FOUND}, and 403 for a task standing in a warehouse the
     *         caller is not assigned to
     */
    @Transactional(readOnly = true)
    public TaskView get(UUID taskId) {
        PutawayTaskView task = tasks.find(taskId).orElseThrow(PutawayService::notFound);
        Reads reads = new Reads();
        Source source = locate(List.of(task), false).get(task.id());
        WarehouseScope.requireLocation(source.locationCode());
        TaskView view = view(task, source, reads);
        if (view.left() <= 0) {
            return view;
        }
        StorageLocationView sourceLocation = warehouses.findLocation(source.locationCode()).orElse(null);
        if (sourceLocation == null) {
            return view;
        }
        List<PutawayRanker.Suggestion> suggestions = suggest(sourceLocation.warehouseId(), task, view.lotNumber());
        List<String> overflow = suggestions.isEmpty() ? inputs.overflowAreas(sourceLocation.warehouseId()) : List.of();
        return new TaskView(view.id(), view.goodsReceiptId(), view.goodsReceiptLineId(), view.sku(), view.lotNumber(),
                view.quantity(), view.left(), view.sourceLocationCode(), view.warehouseId(), view.lpn(), view.status(),
                view.targetLocationCode(), suggestions, overflow, view.source());
    }

    /**
     * The bins of the warehouse that take the task's carton now, best first: it fits them dimension by dimension and
     * their free volume and load take it (kltn-docs 06 §4.4). The carton is left out of every bin's occupancy, so one
     * somebody already moved into a bin by hand still finds that bin. Empty bins are ranked by the rest of the lot
     * (05 §4.2 step 4).
     */
    private List<PutawayRanker.Suggestion> suggest(UUID warehouseId, PutawayTaskView task, String lot) {
        return suggest(warehouseId, task, lot, inputs.heldByTrips(warehouseId, task.id()));
    }

    /**
     * {@link #suggest(UUID, PutawayTaskView, String)} with the reserved space given by the caller: a putaway trip
     * planning its cart passes the other trips' holds plus what it has already given to the cartons before this one.
     */
    List<PutawayRanker.Suggestion> suggest(UUID warehouseId, PutawayTaskView task, String lot,
                                           List<BinSuggestionInputs.Reserved> reserved) {
        List<Bin> bins = inputs.bins(warehouseId);
        Map<String, Occupancy> occupied = inputs.occupancy(bins.stream().map(Bin::locationCode).toList(),
                Set.of(new BinOccupancyQuery.SkuLot(task.sku(), lot)), List.of(task.stockPackageId()), reserved);
        Need need = needOf(task);
        return PutawayRanker.rank(need, restOfLot(warehouseId, task, lot, need), bins, occupied);
    }

    /**
     * The rest of the task's lot (05 §4.2): the cartons of that lot with an open putaway task (PENDING or IN_PROGRESS)
     * standing in the same warehouse, this one included. A carton without a lot is a lot of its own.
     */
    private PutawayRanker.Lot restOfLot(UUID warehouseId, PutawayTaskView task, String lot, Need need) {
        if (lot == null) {
            return PutawayRanker.Lot.of(need);
        }
        Set<StockPackageId> open = new java.util.HashSet<>();
        open.add(new StockPackageId(task.stockPackageId()));
        tasks.openTasks().stream().map(PutawayTaskView::stockPackageId).filter(java.util.Objects::nonNull)
                .forEach(id -> open.add(new StockPackageId(id)));
        List<StockPackage> cartons = stockPackages.findAllById(List.copyOf(open));
        Set<String> inWarehouse = warehouses.findLocations(cartons.stream().map(c -> c.location().code())
                        .collect(Collectors.toSet())).stream()
                .filter(location -> warehouseId.equals(location.warehouseId()))
                .map(StorageLocationView::locationCode).collect(Collectors.toSet());
        List<UUID> sameWarehouse = cartons.stream().filter(c -> inWarehouse.contains(c.location().code()))
                .map(c -> c.id().value()).toList();
        if (sameWarehouse.isEmpty()) {
            return PutawayRanker.Lot.of(need);
        }
        PutawayRanker.Lot rest = occupancy.lotOf(sameWarehouse, lot);
        return rest.volumeM3().signum() == 0 ? PutawayRanker.Lot.of(need) : rest;
    }

    /** The lot of a task's goods, read on its own (a putaway trip asks for each carton of its cart). */
    String lotOfTask(PutawayTaskView task) {
        return lotOf(task, new Reads());
    }

    private Need needOf(PutawayTaskView task) {
        return inputs.needsOf(List.of(task.stockPackageId())).getOrDefault(task.stockPackageId(), Need.UNMEASURED);
    }

    /**
     * Puts goods away: the task's carton moves whole into {@code locationCode} and its units turn AVAILABLE, in one
     * step (kltn-docs 05 BR-08) - from INBOUND for a receipt's task, from QUARANTINE for a quarantine release's (05
     * BR-14) - and the task is DONE; a receipt's task publishes {@link PutawayCompleted}, a quarantine release's
     * {@code QuarantineStockReleased}.
     *
     * <p>{@code locationCode} is a bin, or an OVERFLOW area of the same warehouse when no bin takes the carton - the
     * warehouse is full or the carton fits no bin (05 BR-13). Goods in OVERFLOW are put away all the same: AVAILABLE,
     * the task DONE; a manager moves them up to a bin when one has room (11).</p>
     *
     * <p>A retried request is answered by the idempotency filter; without a key a repeat finds the task DONE.</p>
     *
     * @param lpn the scanned carton
     * @throws BusinessException {@code PUTAWAY_TASK_NOT_FOUND}; 403 for a task standing in another warehouse;
     *         {@code PUTAWAY_TASK_DONE}; {@code VALIDATION_FAILED} for a cancelled task; {@code PUTAWAY_LPN_MISMATCH}; and the location's refusals:
     *         {@code LOCATION_NOT_FOUND}, {@code PUTAWAY_TARGET_NOT_BIN}, {@code MOVE_ACROSS_WAREHOUSES},
     *         {@code LOCATION_NOT_USABLE}, {@code CARTON_DOES_NOT_FIT_BIN}, {@code LOCATION_CAPACITY_EXCEEDED},
     *         {@code PUTAWAY_OVERFLOW_NOT_ALLOWED}
     */
    @Transactional
    public TaskView confirm(UUID taskId, String locationCode, String lpn, UUID actorId) {
        // A carton on a trip's cart: the trip row is locked before the task row, the order every trip step keeps.
        trips.lockTripCarrying(taskId);
        PutawayTaskView task = tasks.lockForUpdate(taskId);
        Reads reads = new Reads();
        Source source = locate(List.of(task), false).get(task.id());
        WarehouseScope.requireLocation(source.locationCode());
        if ("DONE".equals(task.status())) {
            throw new BusinessException(ErrorCode.PUTAWAY_TASK_DONE, "This putaway task is already done");
        }
        if ("CANCELLED".equals(task.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "This putaway task was cancelled");
        }
        int left = leftOf(task);
        int units = scannedUnits(task, source, lpn);

        StorageLocationView target = requireTarget(locationCode, source, task, reads);

        boolean release = task.source() == PutawaySource.QUARANTINE_RELEASE;
        operations.reclassifyStockPackages(new ReclassifyStockPackagesCommand(source.lpn(), target.locationCode(),
                StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, task.id(), actorId, null, release));
        PutawayTaskView after = tasks.recordPutaway(task.id(), units, left, target.id());
        // The carton left its cart, whichever screen put it away; the trip is COMPLETED with its last one.
        trips.taskPutAway(task.id());
        if ("DONE".equals(after.status()) && !release) {
            events.publishEvent(new PutawayCompleted(task.goodsReceiptId(), task.goodsReceiptLineId(), task.id()));
        } else if ("DONE".equals(after.status())) {
            // No receipt closes on a quarantine release, but its carton is stock to promise again: the orders waiting
            // for it - first the one a returned shipment's carton came back for - hold it now (WF-05 fix plan).
            events.publishEvent(new com.stockflow.contracts.QuarantineStockReleased(task.id(), source.lpn(),
                    target.locationCode()));
        }
        return view(after, locate(List.of(after), false).get(after.id()), reads);
    }

    /** What the scan means in units: the whole task, when the scan is the task's own carton. */
    private static int scannedUnits(PutawayTaskView task, Source source, String lpn) {
        if (lpn == null || lpn.isBlank() || !StockPackageView.normalLpn(lpn).equals(source.lpn())) {
            throw new BusinessException(ErrorCode.PUTAWAY_LPN_MISMATCH,
                    "The scanned LPN is not the carton of this task");
        }
        return task.quantity();
    }

    /**
     * The hard rules for the location the clerk scanned, the same ones that keep a bin out of the suggestions; a bin
     * outside the suggestions is allowed (05 BR-07). A bin that is not among the putaway targets at all (not flagged,
     * or not usable down to its shelf) is {@code LOCATION_NOT_USABLE}. An area is refused unless it is an OVERFLOW
     * area and no bin takes the carton (05 BR-13, decided 2026-11-05: OVERFLOW only when the suggestions are empty).
     */
    private StorageLocationView requireTarget(String locationCode, Source source, PutawayTaskView task, Reads reads) {
        StorageLocationView target = warehouses.findLocation(locationCode).orElseThrow(() ->
                new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + locationCode));
        boolean overflow = target.kind() == StorageLocationView.Kind.AREA
                && target.areaType() == StorageLocationView.AreaType.OVERFLOW;
        if (target.kind() != StorageLocationView.Kind.BIN && !overflow) {
            throw new BusinessException(ErrorCode.PUTAWAY_TARGET_NOT_BIN,
                    target.locationCode() + " is not a bin; goods are put away into bins, or into OVERFLOW");
        }
        StorageLocationView from = warehouses.findLocation(source.locationCode()).orElseThrow(() ->
                new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + source.locationCode()));
        if (!from.warehouseId().equals(target.warehouseId())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s is in another warehouse than the goods".formatted(target.locationCode()));
        }
        if (!target.usable()) {
            throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE, target.locationCode() + " cannot take stock now");
        }
        String lot = lotOf(task, reads);
        if (overflow) {
            // No bin lock: the suggestions are advice read at this moment, and OVERFLOW has no volume to protect.
            List<PutawayRanker.Suggestion> bins = suggest(target.warehouseId(), task, lot);
            if (!bins.isEmpty()) {
                throw new BusinessException(ErrorCode.PUTAWAY_OVERFLOW_NOT_ALLOWED,
                        "%s still takes this carton; OVERFLOW is for a carton no bin takes".formatted(
                                bins.get(0).locationCode()),
                        List.of(), Map.of("locationCode", bins.get(0).locationCode()));
            }
            return target;
        }
        PutawayCandidate candidate = warehouses.putawayCandidate(target.warehouseId(), target.locationCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                        target.locationCode() + " is not a putaway target"));
        Need need = needOf(task);
        // Before the occupancy is read, so two putaways into one bin cannot both see it as it was.
        occupancy.lockBin(target.locationCode());
        Occupancy occupied = occupancy.occupancyOf(List.of(target.locationCode()), task.sku(), lot,
                task.stockPackageId()).getOrDefault(target.locationCode(), Occupancy.EMPTY);
        PutawayRanker.Refusal refusal = PutawayRanker.refusal(need, BinSuggestionInputs.binOf(candidate), occupied);
        if (refusal == PutawayRanker.Refusal.DOES_NOT_FIT) {
            throw CartonPlacement.doesNotFit(source.lpn(), target.locationCode());
        }
        if (refusal == PutawayRanker.Refusal.NO_ROOM) {
            throw new BusinessException(ErrorCode.LOCATION_CAPACITY_EXCEEDED,
                    "Carton %s does not fit in what is left of %s".formatted(source.lpn(), target.locationCode()));
        }
        return target;
    }

    /**
     * Tasks of a receipt, or the open ones of a warehouse; no suggestions. Every task of a receipt is scope-checked
     * before the status filter, so the filter cannot be used to probe a task the caller may not see.
     *
     * @param warehouseId open tasks standing in this warehouse (the caller must be assigned to it); or null
     * @param receiptId   every task of this goods receipt; or null. It wins when both are given
     * @param status      only tasks in this status; or null
     * @throws BusinessException {@code VALIDATION_FAILED} when neither is given
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskView> list(UUID warehouseId, UUID receiptId, String status, int page, int size) {
        Pages.of(page, size);
        Reads reads = new Reads();
        List<PutawayTaskView> found;
        Map<UUID, Source> sources;
        if (receiptId != null) {
            List<PutawayTaskView> ofReceipt = tasks.tasksOfReceipt(receiptId);
            sources = locate(ofReceipt, true);
            found = ofReceipt.stream().filter(task -> sources.containsKey(task.id())).toList();
            sources.values().forEach(source -> WarehouseScope.requireLocation(source.locationCode()));
        } else if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
            List<PutawayTaskView> open = tasks.openTasks();
            sources = locate(open, true);
            Map<UUID, Source> all = sources;
            knowWarehouses(all.values().stream().map(Source::locationCode).toList(), reads);
            Set<String> inWarehouse = all.values().stream().map(Source::locationCode)
                    .filter(code -> reads.locationWarehouses.get(code).filter(warehouseId::equals).isPresent())
                    .collect(Collectors.toSet());
            found = open.stream().filter(task -> all.containsKey(task.id())
                    && inWarehouse.contains(all.get(task.id()).locationCode())).toList();
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Give a receiptId or a warehouseId");
        }
        List<PutawayTaskView> matching = found.stream()
                .filter(task -> status == null || status.isBlank() || task.status().equalsIgnoreCase(status.trim()))
                .toList();
        int from = (int) Math.min((long) page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        Map<UUID, Source> located = sources;
        knowWarehouses(matching.subList(from, to).stream().map(task -> located.get(task.id()).locationCode())
                .toList(), reads);
        return PageResponse.of(matching.subList(from, to).stream()
                .map(task -> view(task, located.get(task.id()), reads)).toList(), page, size, matching.size());
    }

    private TaskView view(PutawayTaskView task, Source source, Reads reads) {
        return new TaskView(task.id(), task.goodsReceiptId(), task.goodsReceiptLineId(), task.sku(),
                lotOf(task, reads), task.quantity(), leftOf(task), source.locationCode(),
                warehouseOf(source.locationCode(), reads), source.lpn(), task.status(),
                task.targetLocationId() == null ? null : locationCodeOf(task.targetLocationId(), reads), List.of(),
                List.of(), task.source());
    }

    /**
     * The warehouse a task belongs to, read from where its goods stand: a task has no warehouse column, and stock
     * never changes warehouse. Read from the location rather than from its code's prefix, which is only a naming
     * convention.
     */
    private UUID warehouseOf(String locationCode, Reads reads) {
        return reads.locationWarehouses.computeIfAbsent(locationCode, code -> warehouses.findLocation(code)
                .map(StorageLocationView::warehouseId)).orElse(null);
    }

    /** The warehouses of a page's locations in one read, so a list does not look them up row by row. */
    private void knowWarehouses(Collection<String> locationCodes, Reads reads) {
        Set<String> unknown = locationCodes.stream().filter(code -> !reads.locationWarehouses.containsKey(code))
                .collect(Collectors.toSet());
        if (unknown.isEmpty()) {
            return;
        }
        unknown.forEach(code -> reads.locationWarehouses.put(code, Optional.empty()));
        warehouses.findLocations(unknown).forEach(location -> reads.locationWarehouses.put(location.locationCode(),
                Optional.ofNullable(location.warehouseId())));
    }

    /**
     * The lot of a task's goods: its receipt line's, or - for a quarantine release, which has no line - that of the
     * carton itself (a carton is of one lot).
     */
    private String lotOf(PutawayTaskView task, Reads reads) {
        if (task.goodsReceiptLineId() == null) {
            return reads.cartonLot.computeIfAbsent(task.stockPackageId(),
                    carton -> stockPackages.findById(new StockPackageId(carton)).map(StockPackage::lotNumber))
                    .orElse(null);
        }
        return receiptLine(task.goodsReceiptLineId(), reads).map(StockMovement::lotNumber).orElse(null);
    }

    /**
     * PENDING needs no ledger read, DONE and CANCELLED have nothing left: only a task in progress asks the ledger. A
     * task is one carton, so it is either all left or, once the ledger has its putaway line, none of it.
     */
    private int leftOf(PutawayTaskView task) {
        return switch (task.status()) {
            case "PENDING" -> task.quantity();
            case "DONE", "CANCELLED" -> 0;
            default -> putAway(task) ? 0 : task.quantity();
        };
    }

    /**
     * Whether the task's carton was put away: a move into a bin, or a status change where it already stood in it (a
     * carton somebody moved there by hand before the putaway).
     */
    private boolean putAway(PutawayTaskView task) {
        return ledger.countByReference(PUT_AWAY, ReferenceType.PUTAWAY_TASK, task.id()) > 0;
    }

    private String locationCodeOf(UUID locationId, Reads reads) {
        if (!reads.locationCodes.containsKey(locationId)) {
            reads.locationCodes.put(locationId,
                    warehouses.findLocation(locationId).map(StorageLocationView::locationCode).orElse(null));
        }
        return reads.locationCodes.get(locationId);
    }

    /** The receipt line's first RECEIPT ledger line: its cartons share one lot, so any of them gives its number. */
    private Optional<StockMovement> receiptLine(UUID receiptLineId, Reads reads) {
        return reads.receiptLine.computeIfAbsent(receiptLineId, id -> ledger.findByReference(MovementType.RECEIPT,
                ReferenceType.GOODS_RECEIPT_LINE, id));
    }

    /**
     * Where each task's carton stands. {@code lenient} is for lists: a task that cannot be resolved (its carton is
     * gone) is left out with a warning, because one broken task must not hide every other task of every warehouse. A
     * single task ({@link #get}, {@link #confirm}) is strict.
     */
    private Map<UUID, Source> locate(List<PutawayTaskView> found, boolean lenient) {
        Map<UUID, StockPackage> cartons = stockPackages.findAllById(found.stream()
                        .map(task -> new StockPackageId(task.stockPackageId())).distinct().toList()).stream()
                .collect(Collectors.toMap(carton -> carton.id().value(), Function.identity()));

        Map<UUID, Source> out = new HashMap<>();
        for (PutawayTaskView task : found) {
            StockPackage carton = cartons.get(task.stockPackageId());
            if (carton == null) {
                if (!lenient) {
                    throw notFound();
                }
                log.warn("Putaway task {} names carton {} that does not exist; left out of the list", task.id(),
                        task.stockPackageId());
                continue;
            }
            out.put(task.id(), new Source(carton.location().code(), carton.lpn()));
        }
        return out;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.PUTAWAY_TASK_NOT_FOUND, "No such putaway task");
    }
}
