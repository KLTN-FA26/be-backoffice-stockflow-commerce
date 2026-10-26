package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.domain.PutawayRanker.Bin;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovement.MovementType;
import com.stockflow.inventory.internal.domain.StockMovement.ReferenceType;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ItemRow;
import com.stockflow.warehouse.api.PutawayCandidate;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
 * <p>Where a task's goods stand now (its <i>source</i>) is derived, never stored. A carton task: the carton's current
 * bin, wherever a hand move has taken it. A loose task: the bin of the first loose INBOUND row of the receipt line's
 * lot; once nothing is INBOUND any more (the task is done) the bin of the latest putaway, the task's target. Scope is
 * checked on the source before anything else is said about a task, so a task of another warehouse is 403 and not
 * 404 or a status.</p>
 *
 * <p>Suggestions are computed on {@link #get} only, from the bins and the stock of this moment, and never stored: a
 * suggestion is stale as soon as someone else puts goods away.</p>
 *
 * <p>Sources are read in batches by kind: one read of the cartons of all the lines, and for each loose task's receipt
 * line one ledger read and one stock read (there is no batch read of the ledger by reference, and adding one for this
 * screen would be a second way to read the same lines). A warehouse's list resolves the source of every open task to
 * filter by warehouse, so its cost grows with the open loose tasks, not with the page; lots, remaining quantities and
 * target codes are read for the requested page only.</p>
 */
@Service
public class PutawayService {

    private final PutawayTasks tasks;
    private final WarehouseService warehouses;
    private final StockPackageRepository stockPackages;
    private final StockItemRepository stockItems;
    private final StockMovementLog ledger;
    private final InventoryPolicyRepository items;
    private final BinOccupancyQuery occupancy;

    public PutawayService(PutawayTasks tasks, WarehouseService warehouses, StockPackageRepository stockPackages,
                          StockItemRepository stockItems, StockMovementLog ledger, InventoryPolicyRepository items,
                          BinOccupancyQuery occupancy) {
        this.tasks = tasks;
        this.warehouses = warehouses;
        this.stockPackages = stockPackages;
        this.stockItems = stockItems;
        this.ledger = ledger;
        this.items = items;
        this.occupancy = occupancy;
    }

    /**
     * @param lotNumber          of the receipt line's goods; null when the line has none
     * @param quantity           what the task was opened for
     * @param left               what is still to put away
     * @param sourceLocationCode where the goods stand now
     * @param lpn                the carton's, null for a loose task
     * @param targetLocationCode the bin of the latest putaway; null until something was put away
     * @param suggestions        ranked bins; filled by {@link #get} only
     */
    public record TaskView(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, String lotNumber,
                           int quantity, int left, String sourceLocationCode, String lpn, String status,
                           String targetLocationCode, List<PutawayRanker.Suggestion> suggestions) {
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
        Source source = locate(List.of(task), reads).get(task.id());
        WarehouseScope.requireLocation(source.locationCode());
        TaskView view = view(task, source, reads);
        if (view.left() <= 0) {
            return view;
        }
        ItemRow item = items.findBySkus(List.of(task.sku())).stream().findFirst().orElse(null);
        StorageLocationView sourceLocation = warehouses.findLocation(source.locationCode()).orElse(null);
        if (item == null || sourceLocation == null) {
            return view;
        }
        StorageLocationView.StorageClass storageClass = StorageLocationView.StorageClass
                .valueOf(item.logistics().storageClass().name());
        List<PutawayCandidate> candidates = warehouses.putawayCandidates(sourceLocation.warehouseId(), storageClass);
        Map<String, Occupancy> occupied = occupancy.occupancyOf(
                candidates.stream().map(PutawayCandidate::locationCode).toList(), task.sku(), view.lotNumber());
        BigDecimal unitWeight = item.logistics().weightKg() == null ? BigDecimal.ZERO : item.logistics().weightKg();
        // A carton goes in whole; loose units are put away in portions, so the first bin only has to take one.
        Need need = new Need(task.stockPackageId() != null ? task.quantity() : 1, unitWeight);
        List<PutawayRanker.Suggestion> suggestions = PutawayRanker.rank(need, candidates.stream()
                .map(c -> new Bin(c.locationCode(), c.capacityUnits(), c.maxWeightKg())).toList(), occupied);
        return new TaskView(view.id(), view.goodsReceiptId(), view.goodsReceiptLineId(), view.sku(), view.lotNumber(),
                view.quantity(), view.left(), view.sourceLocationCode(), view.lpn(), view.status(),
                view.targetLocationCode(), suggestions);
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
            found = tasks.tasksOfReceipt(receiptId);
            sources = locate(found, reads);
            sources.values().forEach(source -> WarehouseScope.requireLocation(source.locationCode()));
        } else if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
            List<PutawayTaskView> open = tasks.openTasks();
            sources = locate(open, reads);
            Map<UUID, Source> all = sources;
            Set<String> inWarehouse = warehouses.findLocations(all.values().stream()
                            .map(Source::locationCode).collect(Collectors.toSet())).stream()
                    .filter(location -> warehouseId.equals(location.warehouseId()))
                    .map(StorageLocationView::locationCode).collect(Collectors.toSet());
            found = open.stream().filter(task -> inWarehouse.contains(all.get(task.id()).locationCode())).toList();
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
                task.targetLocationId() == null ? null : locationCodeOf(task.targetLocationId(), reads), List.of());
    }

    /** PENDING needs no ledger read, DONE has nothing left: only a task in progress asks the ledger. */
    private int leftOf(PutawayTaskView task) {
        return switch (task.status()) {
            case "PENDING" -> task.quantity();
            case "DONE" -> 0;
            default -> Math.max(0, task.quantity()
                    - ledger.sumQuantityByReference(MovementType.MOVE, ReferenceType.PUTAWAY_TASK, task.id()));
        };
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

    private Map<UUID, Source> locate(List<PutawayTaskView> found, Reads reads) {
        Map<UUID, StockPackage> cartons = stockPackages.findByReceiptLines(found.stream()
                        .filter(task -> task.stockPackageId() != null).map(PutawayTaskView::goodsReceiptLineId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(carton -> carton.id().value(), Function.identity()));

        Map<UUID, Source> out = new HashMap<>();
        for (PutawayTaskView task : found) {
            if (task.stockPackageId() != null) {
                StockPackage carton = cartons.get(task.stockPackageId());
                if (carton == null) {
                    throw notFound();
                }
                out.put(task.id(), new Source(carton.location().code(), carton.lpn()));
                continue;
            }
            StockMovement receipt = looseReceiptLine(task.goodsReceiptLineId(), reads).orElse(null);
            String location = null;
            if (receipt != null && receipt.lotId() != null) {
                location = stockItems.findLooseInboundByLot(new Sku(task.sku()), receipt.lotId()).stream()
                        .findFirst().map(row -> row.location().code()).orElse(null);
            }
            if (location == null && task.targetLocationId() != null) {
                location = locationCodeOf(task.targetLocationId(), reads);
            }
            if (location == null && receipt != null && receipt.to() != null) {
                location = receipt.to().code();
            }
            if (location == null) {
                throw notFound();
            }
            out.put(task.id(), new Source(location, null));
        }
        return out;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.PUTAWAY_TASK_NOT_FOUND, "No such putaway task");
    }
}
