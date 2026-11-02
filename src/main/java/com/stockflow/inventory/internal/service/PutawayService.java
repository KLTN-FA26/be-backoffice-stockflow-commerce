package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.domain.Sku;
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
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovement.MovementType;
import com.stockflow.inventory.internal.domain.StockMovement.ReferenceType;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ItemRow;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * cartons by id, one ledger read of the loose RECEIPT lines of all the lines, one read of where each lot's loose INBOUND
 * rows wait (a projection that loads no reservations), and one for the locations of the warehouse filter. A loose
 * task whose lot has no INBOUND units left (it is done) stands at its latest target; failing that, where the loose
 * RECEIPT ledger line says the goods arrived ({@code to}). Lots, remaining quantities of tasks in progress and target
 * codes are read for the returned page only, one read per task in progress and one per distinct target.</p>
 */
@Service
public class PutawayService {

    private static final Logger log = LoggerFactory.getLogger(PutawayService.class);

    /** The ledger lines a putaway writes: a move into the bin, or a status change where the goods already stood. */
    private static final Set<MovementType> PUT_AWAY = Set.of(MovementType.MOVE, MovementType.STATUS_CHANGE);

    private final PutawayTasks tasks;
    private final WarehouseService warehouses;
    private final StockPackageRepository stockPackages;
    private final StockItemRepository stockItems;
    private final StockMovementLog ledger;
    private final InventoryPolicyRepository items;
    private final BinOccupancyQuery occupancy;
    private final StockOperations operations;
    private final ApplicationEventPublisher events;

    public PutawayService(PutawayTasks tasks, WarehouseService warehouses, StockPackageRepository stockPackages,
                          StockItemRepository stockItems, StockMovementLog ledger, InventoryPolicyRepository items,
                          BinOccupancyQuery occupancy, StockOperations operations, ApplicationEventPublisher events) {
        this.tasks = tasks;
        this.warehouses = warehouses;
        this.stockPackages = stockPackages;
        this.stockItems = stockItems;
        this.ledger = ledger;
        this.items = items;
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
        Source source = locate(List.of(task), reads, false).get(task.id());
        WarehouseScope.requireLocation(source.locationCode());
        TaskView view = view(task, source, reads);
        if (view.left() <= 0) {
            return view;
        }
        ItemRow item = itemOf(task.sku()).orElse(null);
        StorageLocationView sourceLocation = warehouses.findLocation(source.locationCode()).orElse(null);
        if (item == null || sourceLocation == null) {
            return view;
        }
        StorageLocationView.StorageClass storageClass = StorageLocationView.StorageClass
                .valueOf(item.logistics().storageClass().name());
        List<PutawayCandidate> candidates = warehouses.putawayCandidates(sourceLocation.warehouseId(), storageClass);
        Map<String, Occupancy> occupied = occupancy.occupancyOf(
                candidates.stream().map(PutawayCandidate::locationCode).toList(), task.sku(), view.lotNumber());
        BigDecimal unitWeight = unitWeightOf(item);
        // A carton goes in whole; loose units are put away in portions, so the first bin only has to take one.
        Need need = new Need(task.stockPackageId() != null ? task.quantity() : 1, unitWeight);
        List<PutawayRanker.Suggestion> suggestions = PutawayRanker.rank(need, candidates.stream()
                .map(c -> new Bin(c.locationCode(), c.capacityUnits(), c.maxWeightKg())).toList(), occupied);
        return new TaskView(view.id(), view.goodsReceiptId(), view.goodsReceiptLineId(), view.sku(), view.lotNumber(),
                view.quantity(), view.left(), view.sourceLocationCode(), view.lpn(), view.status(),
                view.targetLocationCode(), suggestions);
    }

    /**
     * Puts goods away: a carton task moves its carton whole, a loose task moves {@code quantity} of the receipt
     * line's loose units; both land in {@code locationCode} as AVAILABLE. A task is confirmed in as many parts as
     * the clerk likes while it has units left, and publishes {@link PutawayCompleted} when the last one is in.
     *
     * <p>No replay lookup: all parts of a task share one ledger reference, so a lookup by it would take the second
     * part for a repeat of the first. A retried request is answered by the idempotency filter; without a key a
     * repeat is a second putaway, which {@code left} (summed from the ledger under the task lock) bounds.</p>
     *
     * @param lpn      the scanned carton; required for a carton task, forbidden for a loose one
     * @param quantity units to put away; required for a loose task, forbidden for a carton task
     * @throws BusinessException {@code PUTAWAY_TASK_NOT_FOUND}; 403 for a task standing in another warehouse;
     *         {@code PUTAWAY_TASK_DONE}; {@code VALIDATION_FAILED} for a cancelled task or a scan of the wrong
     *         kind; {@code PUTAWAY_LPN_MISMATCH}; {@code PUTAWAY_QUANTITY_EXCEEDED}; and the bin's refusals:
     *         {@code LOCATION_NOT_FOUND}, {@code PUTAWAY_TARGET_NOT_BIN}, {@code MOVE_ACROSS_WAREHOUSES},
     *         {@code LOCATION_NOT_USABLE}, {@code LOCATION_STORAGE_CLASS_MISMATCH},
     *         {@code LOCATION_CAPACITY_EXCEEDED}
     */
    @Transactional
    public TaskView confirm(UUID taskId, String locationCode, String lpn, Integer quantity, UUID actorId) {
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
        boolean carton = task.stockPackageId() != null;
        int left = leftOf(task);
        int units = scannedUnits(task, source, carton, left, lpn, quantity);

        ItemRow item = itemOf(task.sku()).orElseThrow(() ->
                new BusinessException(ErrorCode.VALIDATION_FAILED, "Item " + task.sku() + " is unknown"));
        // A carton is judged by what it holds now: a count may have changed it since its task was opened, and the
        // move takes the carton as it is. The task still records its own quantity, since it puts the carton away whole.
        int moving = carton ? stockPackages.onHandOf(List.of(new StockPackageId(task.stockPackageId())))
                .getOrDefault(new StockPackageId(task.stockPackageId()), 0) : units;
        StorageLocationView bin = requireTarget(locationCode, source, item, task, moving, reads);

        if (carton) {
            operations.reclassifyStockPackages(new ReclassifyStockPackagesCommand(source.lpn(), bin.locationCode(),
                    StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, task.id(), actorId, null));
        } else {
            UUID lotId = looseReceiptLine(task.goodsReceiptLineId(), reads).map(StockMovement::lotId)
                    .orElseThrow(PutawayService::notFound);
            operations.putAwayLoose(new Sku(task.sku()), lotId, bin.locationCode(), units, task.id(), actorId);
        }
        PutawayTaskView after = tasks.recordPutaway(task.id(), units, left, bin.id());
        if ("DONE".equals(after.status())) {
            events.publishEvent(new PutawayCompleted(task.goodsReceiptId(), task.goodsReceiptLineId(), task.id()));
        }
        return view(after, locate(List.of(after), reads, false).get(after.id()), reads);
    }

    /** What the scan means in units, refusing a scan of the wrong kind or more than the task has left. */
    private static int scannedUnits(PutawayTaskView task, Source source, boolean carton, int left, String lpn,
                                    Integer quantity) {
        if (carton) {
            if (quantity != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A carton task is confirmed by its LPN, not by a quantity");
            }
            if (lpn == null || lpn.isBlank() || !StockPackageView.normalLpn(lpn).equals(source.lpn())) {
                throw new BusinessException(ErrorCode.PUTAWAY_LPN_MISMATCH,
                        "The scanned LPN is not the carton of this task");
            }
            return task.quantity();
        }
        if (lpn != null && !lpn.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A loose task is confirmed by a quantity, not by an LPN");
        }
        if (quantity == null || quantity < 1 || quantity > left) {
            throw new BusinessException(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED,
                    "Put away between 1 and %d units, not %s".formatted(left, quantity));
        }
        return quantity;
    }

    /**
     * The hard rules for a bin the clerk chose, the same ones that keep a bin out of the suggestions; a bin outside
     * the suggestions is allowed. A location that is not among the putaway targets of its class at all (not flagged,
     * or not usable down to its shelf) is {@code LOCATION_NOT_USABLE}.
     */
    private StorageLocationView requireTarget(String locationCode, Source source, ItemRow item, PutawayTaskView task,
                                              int units, Reads reads) {
        StorageLocationView bin = warehouses.findLocation(locationCode).orElseThrow(() ->
                new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + locationCode));
        if (bin.kind() != StorageLocationView.Kind.BIN) {
            throw new BusinessException(ErrorCode.PUTAWAY_TARGET_NOT_BIN,
                    bin.locationCode() + " is not a bin; goods are put away into bins");
        }
        StorageLocationView from = warehouses.findLocation(source.locationCode()).orElseThrow(() ->
                new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + source.locationCode()));
        if (!from.warehouseId().equals(bin.warehouseId())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s is in another warehouse than the goods; use a transfer order".formatted(bin.locationCode()));
        }
        if (!bin.usable()) {
            throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE, bin.locationCode() + " cannot take stock now");
        }
        StorageLocationView.StorageClass itemClass = StorageLocationView.StorageClass
                .valueOf(item.logistics().storageClass().name());
        if (bin.storageClass() != itemClass) {
            throw new BusinessException(ErrorCode.LOCATION_STORAGE_CLASS_MISMATCH,
                    "%s is %s; %s is kept %s".formatted(bin.locationCode(), bin.storageClass(), task.sku(), itemClass));
        }
        PutawayCandidate candidate = warehouses.putawayCandidate(bin.warehouseId(), itemClass, bin.locationCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                        bin.locationCode() + " is not a putaway target"));
        String lot = (task.stockPackageId() != null ? receiptLine(task.goodsReceiptLineId(), reads)
                : looseReceiptLine(task.goodsReceiptLineId(), reads)).map(StockMovement::lotNumber).orElse(null);
        // Before the occupancy is read, so two putaways into one bin cannot both see it as it was.
        occupancy.lockBin(bin.locationCode());
        Occupancy occupied = withoutOwnGoods(occupancy.occupancyOf(List.of(bin.locationCode()), task.sku(), lot)
                .getOrDefault(bin.locationCode(), Occupancy.EMPTY), alreadyIn(bin, source, task, units, reads),
                unitWeightOf(item));
        if (!PutawayRanker.fits(new Need(units, unitWeightOf(item)),
                new Bin(candidate.locationCode(), candidate.capacityUnits(), candidate.maxWeightKg()), occupied)) {
            throw new BusinessException(ErrorCode.LOCATION_CAPACITY_EXCEEDED,
                    "%d units of %s do not fit in %s".formatted(units, task.sku(), bin.locationCode()));
        }
        return bin;
    }

    /**
     * Units of this putaway that already stand in the bin, because somebody moved the goods there by hand: a carton
     * whose current bin it is, or the loose INBOUND units of the receipt line's lot in it (never more than are put
     * away now). The bin's occupancy counts them already.
     */
    private int alreadyIn(StorageLocationView bin, Source source, PutawayTaskView task, int units, Reads reads) {
        if (task.stockPackageId() != null) {
            return source.locationCode().equals(bin.locationCode()) ? units : 0;
        }
        UUID lotId = looseReceiptLine(task.goodsReceiptLineId(), reads).map(StockMovement::lotId).orElse(null);
        if (lotId == null) {
            return 0;
        }
        int inBin = stockItems.findLooseInboundByLot(new Sku(task.sku()), lotId).stream()
                .filter(row -> row.location().code().equals(bin.locationCode()))
                .mapToInt(row -> row.onHand().value()).sum();
        return Math.min(units, inBin);
    }

    /**
     * The bin's occupancy without the goods being put away that it already holds. Counted twice, a carton that
     * fills a bin exactly would not fit in the bin it is already standing in.
     */
    private static Occupancy withoutOwnGoods(Occupancy occupied, int own, BigDecimal unitWeight) {
        if (own == 0) {
            return occupied;
        }
        // An unknown unit weight is 0 in the occupancy read, so there is nothing of it to take off.
        BigDecimal weight = unitWeight == null ? occupied.weightKg()
                : occupied.weightKg().subtract(unitWeight.multiply(BigDecimal.valueOf(own)));
        return new Occupancy(Math.max(0, occupied.units() - own), weight.max(BigDecimal.ZERO), occupied.holdsSku(),
                occupied.holdsLot());
    }

    private Optional<ItemRow> itemOf(String sku) {
        return items.findBySkus(List.of(sku)).stream().findFirst();
    }

    /** Null when the item master records none: {@link PutawayRanker#fits} then does not weigh the goods at all. */
    private static BigDecimal unitWeightOf(ItemRow item) {
        return item.logistics().weightKg();
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
                task.targetLocationId() == null ? null : locationCodeOf(task.targetLocationId(), reads), List.of());
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
        Map<UUID, String> waiting = stockItems.looseInboundLocationByLot(looseReceipts.values().stream()
                .map(StockMovement::lotId).filter(Objects::nonNull).distinct().toList());

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
            StockMovement receipt = looseReceiptLine(task.goodsReceiptLineId(), reads).orElse(null);
            String location = receipt == null || receipt.lotId() == null ? null : waiting.get(receipt.lotId());
            if (location == null && task.targetLocationId() != null) {
                location = locationCodeOf(task.targetLocationId(), reads);
            }
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
