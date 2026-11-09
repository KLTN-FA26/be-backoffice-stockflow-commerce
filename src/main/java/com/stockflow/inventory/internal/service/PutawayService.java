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
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>Every task is one carton, put away whole (kltn-docs 05 BR-08; team plan 4C-2). Where a task's goods stand now
 * (its <i>source</i>) is derived, never stored: the carton's current bin, wherever a hand move has taken it. Loose
 * tasks are history - opened before receipts put every unit into a carton, and finished or cancelled before
 * V20261111000110 boxed the stock - so one only stands where it put its goods (its target) or, cancelled, where its
 * receipt line set them down; it is never confirmed again. Scope is checked on the source before anything else is
 * said about a task, so a task of another warehouse is 403 and not 404 or a status.</p>
 *
 * <p>{@link #confirm} puts goods away in one transaction, in the order the spec fixes: the task row first (as the pick
 * row comes first when picking), then the scope of its source, the scan, the bin the clerk chose, the move, the
 * task's own bookkeeping and last the event. Everything a bin must satisfy is checked here again for a bin the
 * clerk picked by hand, because the suggestions are only advice.</p>
 *
 * <p>Lock order of {@link #confirm}: task row, then the target bin's advisory lock (taken before the bin's occupancy
 * is read: occupancy is bin-wide across SKUs, so only a lock on the bin serialises two putaways of different SKUs
 * into it), then the SKU advisory lock, the carton row and the stock rows by id, as in the move itself.</p>
 *
 * <p>Suggestions are computed on {@link #get} only, from the bins and the stock of this moment, and never stored: a
 * suggestion is stale as soon as someone else puts goods away.</p>
 *
 * <p>Sources are read in a constant number of queries however many tasks there are: one read of the tasks' own
 * cartons by id, one ledger read of the loose RECEIPT lines of historical loose tasks, and one for the locations of
 * the warehouse filter. Lots, remaining quantities of tasks in progress and target codes are read for the returned
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

    public PutawayService(PutawayTasks tasks, WarehouseService warehouses, StockPackageRepository stockPackages,
                          StockMovementLog ledger, BinOccupancyQuery occupancy,
                          StockOperations operations, ApplicationEventPublisher events) {
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
     * @param lpn                the carton's, null for a loose task
     * @param targetLocationCode the bin (or OVERFLOW area) of the latest putaway; null until something was put away
     * @param suggestions        ranked bins; filled by {@link #get} only
     * @param overflowLocationCodes filled by {@link #get} only, when no bin takes the carton: the usable OVERFLOW
     *                           areas of its warehouse, where it may go instead (kltn-docs 05 BR-13)
     */
    public record TaskView(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, String lotNumber,
                           int quantity, int left, String sourceLocationCode, String lpn, String status,
                           String targetLocationCode, List<PutawayRanker.Suggestion> suggestions,
                           List<String> overflowLocationCodes) {
    }

    /** Where a task's goods stand now. */
    private record Source(String locationCode, String lpn) {
    }

    /** What the reads of one request have already fetched, so a receipt line shared by tasks is read once. */
    private static final class Reads {
        final Map<UUID, Optional<StockMovement>> receiptLine = new HashMap<>();
        final Map<UUID, Optional<StockMovement>> looseReceiptLine = new HashMap<>();
        final Map<UUID, String> locationCodes = new HashMap<>();
    }

    /**
     * @throws BusinessException {@code PUTAWAY_TASK_NOT_FOUND}, and 403 for a task standing in a warehouse the
     *         caller is not assigned to
     */
    @Transactional(readOnly = true)
    public TaskView get(UUID taskId) {
        PutawayTaskView task = tasks.find(taskId).orElseThrow(PutawayService::notFound);
        Reads reads = new Reads();
        Source source = locate(List.of(task), reads, false).get(task.id());
        WarehouseScope.requireLocation(source.locationCode());
        TaskView view = view(task, source, reads);
        if (view.left() <= 0 || task.stockPackageId() == null) {
            return view;
        }
        StorageLocationView sourceLocation = warehouses.findLocation(source.locationCode()).orElse(null);
        if (sourceLocation == null) {
            return view;
        }
        List<PutawayRanker.Suggestion> suggestions = suggest(sourceLocation.warehouseId(), task, view.lotNumber());
        List<String> overflow = suggestions.isEmpty() ? overflowAreas(sourceLocation.warehouseId()) : List.of();
        return new TaskView(view.id(), view.goodsReceiptId(), view.goodsReceiptLineId(), view.sku(), view.lotNumber(),
                view.quantity(), view.left(), view.sourceLocationCode(), view.lpn(), view.status(),
                view.targetLocationCode(), suggestions, overflow);
    }

    /**
     * The bins of the warehouse that take the task's carton now, best first: it fits them dimension by dimension and
     * their free volume and load take it (kltn-docs 06 §4.4). The carton is left out of every bin's occupancy, so one
     * somebody already moved into a bin by hand still finds that bin. Empty bins are ranked by the rest of the lot
     * (05 §4.2 step 4).
     */
    private List<PutawayRanker.Suggestion> suggest(UUID warehouseId, PutawayTaskView task, String lot) {
        List<PutawayCandidate> candidates = warehouses.putawayCandidates(warehouseId);
        Map<String, Occupancy> occupied = occupancy.occupancyOf(
                candidates.stream().map(PutawayCandidate::locationCode).toList(), task.sku(), lot,
                task.stockPackageId());
        Need need = needOf(task);
        return PutawayRanker.rank(need, restOfLot(warehouseId, task, lot, need),
                candidates.stream().map(PutawayService::binOf).toList(), occupied);
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

    /** The usable OVERFLOW areas of a warehouse, by location code. */
    private List<String> overflowAreas(UUID warehouseId) {
        return warehouses.areasOf(warehouseId, StorageLocationView.AreaType.OVERFLOW).stream()
                .filter(StorageLocationView::usable)
                .map(StorageLocationView::locationCode)
                .toList();
    }

    private Need needOf(PutawayTaskView task) {
        return occupancy.needsOf(List.of(task.stockPackageId())).getOrDefault(task.stockPackageId(), Need.UNMEASURED);
    }

    private static Bin binOf(PutawayCandidate candidate) {
        return new Bin(candidate.locationCode(), CartonPlacement.spaceOf(candidate.size()),
                candidate.capacityVolumeM3(), candidate.maxWeightKg());
    }

    /**
     * Puts goods away: the task's carton moves whole into {@code locationCode} and its units turn AVAILABLE, in one
     * step (kltn-docs 05 BR-08), and the task is DONE and publishes {@link PutawayCompleted}.
     *
     * <p>{@code locationCode} is a bin, or an OVERFLOW area of the same warehouse when no bin takes the carton - the
     * warehouse is full or the carton fits no bin (05 BR-13). Goods in OVERFLOW are put away all the same: AVAILABLE,
     * the task DONE; a manager moves them up to a bin when one has room (11).</p>
     *
     * <p>A retried request is answered by the idempotency filter; without a key a repeat finds the task DONE.</p>
     *
     * @param lpn the scanned carton
     * @throws BusinessException {@code PUTAWAY_TASK_NOT_FOUND}; 403 for a task standing in another warehouse;
     *         {@code PUTAWAY_TASK_DONE}; {@code VALIDATION_FAILED} for a cancelled task or a loose one (history,
     *         see the class); {@code PUTAWAY_LPN_MISMATCH}; and the location's refusals:
     *         {@code LOCATION_NOT_FOUND}, {@code PUTAWAY_TARGET_NOT_BIN}, {@code MOVE_ACROSS_WAREHOUSES},
     *         {@code LOCATION_NOT_USABLE}, {@code CARTON_DOES_NOT_FIT_BIN}, {@code LOCATION_CAPACITY_EXCEEDED},
     *         {@code PUTAWAY_OVERFLOW_NOT_ALLOWED}
     */
    @Transactional
    public TaskView confirm(UUID taskId, String locationCode, String lpn, UUID actorId) {
        PutawayTaskView task = tasks.lockForUpdate(taskId);
        Reads reads = new Reads();
        Source source = locate(List.of(task), reads, false).get(task.id());
        WarehouseScope.requireLocation(source.locationCode());
        if ("DONE".equals(task.status())) {
            throw new BusinessException(ErrorCode.PUTAWAY_TASK_DONE, "This putaway task is already done");
        }
        if ("CANCELLED".equals(task.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "This putaway task was cancelled");
        }
        if (task.stockPackageId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A loose putaway task is history; goods are put away by carton");
        }
        int left = leftOf(task);
        int units = scannedUnits(task, source, lpn);

        StorageLocationView target = requireTarget(locationCode, source, task, reads);

        operations.reclassifyStockPackages(new ReclassifyStockPackagesCommand(source.lpn(), target.locationCode(),
                StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, task.id(), actorId, null));
        PutawayTaskView after = tasks.recordPutaway(task.id(), units, left, target.id());
        if ("DONE".equals(after.status())) {
            events.publishEvent(new PutawayCompleted(task.goodsReceiptId(), task.goodsReceiptLineId(), task.id()));
        }
        return view(after, locate(List.of(after), reads, false).get(after.id()), reads);
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
        String lot = receiptLine(task.goodsReceiptLineId(), reads).map(StockMovement::lotNumber).orElse(null);
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
        PutawayRanker.Refusal refusal = PutawayRanker.refusal(need, binOf(candidate), occupied);
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
            sources = locate(ofReceipt, reads, true);
            found = ofReceipt.stream().filter(task -> sources.containsKey(task.id())).toList();
            sources.values().forEach(source -> WarehouseScope.requireLocation(source.locationCode()));
        } else if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
            List<PutawayTaskView> open = tasks.openTasks();
            sources = locate(open, reads, true);
            Map<UUID, Source> all = sources;
            Set<String> inWarehouse = warehouses.findLocations(all.values().stream()
                            .map(Source::locationCode).collect(Collectors.toSet())).stream()
                    .filter(location -> warehouseId.equals(location.warehouseId()))
                    .map(StorageLocationView::locationCode).collect(Collectors.toSet());
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
        return PageResponse.of(matching.subList(from, to).stream()
                .map(task -> view(task, located.get(task.id()), reads)).toList(), page, size, matching.size());
    }

    private TaskView view(PutawayTaskView task, Source source, Reads reads) {
        String lot = (task.stockPackageId() != null ? receiptLine(task.goodsReceiptLineId(), reads)
                : looseReceiptLine(task.goodsReceiptLineId(), reads)).map(StockMovement::lotNumber).orElse(null);
        return new TaskView(task.id(), task.goodsReceiptId(), task.goodsReceiptLineId(), task.sku(), lot,
                task.quantity(), leftOf(task), source.locationCode(), source.lpn(), task.status(),
                task.targetLocationId() == null ? null : locationCodeOf(task.targetLocationId(), reads), List.of(),
                List.of());
    }

    /** PENDING needs no ledger read, DONE and CANCELLED have nothing left: only a task in progress asks the ledger. */
    private int leftOf(PutawayTaskView task) {
        return switch (task.status()) {
            case "PENDING" -> task.quantity();
            case "DONE", "CANCELLED" -> 0;
            default -> Math.max(0, task.quantity() - putAway(task));
        };
    }

    /**
     * Units a task has put away: a move into a bin, or a status change where the goods already stood in it (goods
     * somebody moved there by hand before the putaway).
     */
    private int putAway(PutawayTaskView task) {
        return ledger.sumQuantityByReference(PUT_AWAY, ReferenceType.PUTAWAY_TASK, task.id());
    }

    private String locationCodeOf(UUID locationId, Reads reads) {
        if (!reads.locationCodes.containsKey(locationId)) {
            reads.locationCodes.put(locationId,
                    warehouses.findLocation(locationId).map(StorageLocationView::locationCode).orElse(null));
        }
        return reads.locationCodes.get(locationId);
    }

    /** The receipt line's RECEIPT ledger line, carton or loose: they share one lot, so any gives its number. */
    private Optional<StockMovement> receiptLine(UUID receiptLineId, Reads reads) {
        return reads.receiptLine.computeIfAbsent(receiptLineId, id -> ledger.findByReference(MovementType.RECEIPT,
                ReferenceType.GOODS_RECEIPT_LINE, id));
    }

    /** The loose RECEIPT ledger line: it names the lot whose INBOUND rows are the loose goods. */
    private Optional<StockMovement> looseReceiptLine(UUID receiptLineId, Reads reads) {
        return reads.looseReceiptLine.computeIfAbsent(receiptLineId, id -> ledger.findLooseByReference(
                MovementType.RECEIPT, ReferenceType.GOODS_RECEIPT_LINE, id));
    }

    /**
     * Where each task's goods stand. {@code lenient} is for lists: a task that cannot be resolved (its carton is gone,
     * or no ledger line says where its loose goods are) is left out with a warning, because one broken task must not
     * hide every other task of every warehouse. A single task ({@link #get}, {@link #confirm}) is strict.
     */
    private Map<UUID, Source> locate(List<PutawayTaskView> found, Reads reads, boolean lenient) {
        List<UUID> looseLines = found.stream().filter(task -> task.stockPackageId() == null)
                .map(PutawayTaskView::goodsReceiptLineId).filter(line -> !reads.looseReceiptLine.containsKey(line))
                .distinct().toList();
        Map<UUID, StockMovement> looseReceipts = ledger.findLooseByReferences(MovementType.RECEIPT,
                ReferenceType.GOODS_RECEIPT_LINE, looseLines);
        looseLines.forEach(line -> reads.looseReceiptLine.put(line, Optional.ofNullable(looseReceipts.get(line))));

        Map<UUID, StockPackage> cartons = stockPackages.findAllById(found.stream()
                        .filter(task -> task.stockPackageId() != null)
                        .map(task -> new StockPackageId(task.stockPackageId())).distinct().toList()).stream()
                .collect(Collectors.toMap(carton -> carton.id().value(), Function.identity()));

        Map<UUID, Source> out = new HashMap<>();
        for (PutawayTaskView task : found) {
            if (task.stockPackageId() != null) {
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
                continue;
            }
            // A historical loose task: where it put its goods, or where its receipt line set them down.
            StockMovement receipt = looseReceiptLine(task.goodsReceiptLineId(), reads).orElse(null);
            String location = task.targetLocationId() == null ? null : locationCodeOf(task.targetLocationId(), reads);
            if (location == null && receipt != null && receipt.to() != null) {
                location = receipt.to().code();
            }
            if (location == null) {
                if (!lenient) {
                    throw notFound();
                }
                log.warn("Putaway task {} has no known place for its loose goods; left out of the list", task.id());
                continue;
            }
            out.put(task.id(), new Source(location, null));
        }
        return out;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.PUTAWAY_TASK_NOT_FOUND, "No such putaway task");
    }
}
