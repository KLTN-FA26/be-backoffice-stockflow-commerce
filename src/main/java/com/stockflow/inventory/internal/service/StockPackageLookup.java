package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.ReturnRequestNumbers;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackageBalance;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.StockPackageSearch;
import com.stockflow.inventory.spi.CartonWork;
import com.stockflow.inventory.spi.ReceiptLineReceipts;
import com.stockflow.inventory.spi.SkuNames;
import com.stockflow.warehouse.api.PutawayTasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The carton lookup (SCRUM-473, #125 item 11, the carton lookup screen of #138): a carton by the LPN on its label,
 * what one location holds split into whole cartons, opened cartons and loose units, and cartons page by page -
 * filtered by warehouse, lot, part of the LPN and stock status, each row readable on its own (#454; kltn-docs 20
 * §4.1).
 *
 * <p>Read only. Marking a carton LOST or found is not here on purpose: a carton that still holds stock is lost only by
 * writing that stock off, which is an approved count's adjustment ({@link StockPackageContents#adjust}), never a
 * direct change (V20261020000700 gives staff no UPDATE for that reason).</p>
 *
 * <p>A carton or location in a warehouse the caller is not assigned to is refused before anything else is said about
 * it - whether it exists included, where that can be told apart - as {@link StockPackageLabels} does.</p>
 */
@Service
@Transactional(readOnly = true)
public class StockPackageLookup {

    /** Live cartons: a CONSUMED one keeps the location it was emptied at, and listing it there would look like stock. */
    static final List<StockPackageStatus> LIVE = List.of(StockPackageStatus.RECEIVING, StockPackageStatus.IN_STOCK);

    private static final SortWhitelist SORT = SortWhitelist.of(StockPackageSearch.SORTABLE.toArray(String[]::new))
            .withDefault("lpn", Sort.Direction.ASC);

    /** The shortest part of an LPN {@code q} searches by: shorter would match most of a day's labels. */
    static final int MIN_LPN_PART = 3;

    /**
     * @param location     one exact location code; null for any the caller may see
     * @param statuses     null or empty for the live ones (RECEIVING, IN_STOCK); null entries are ignored
     * @param warehouseId  one warehouse; null for any the caller may see
     * @param lotNumber    one exact lot; null for any
     * @param q            part of the LPN, any case, at least {@link #MIN_LPN_PART} characters; null for any
     * @param stockStatuses cartons holding a stock row in one of these (#454); null or empty for any
     */
    public record CartonQuery(String location, String sku, List<StockPackageStatus> statuses, int page, int size,
                              String sort, UUID warehouseId, String lotNumber, String q,
                              List<StockStatus> stockStatuses) {

        public CartonQuery(String location, String sku, List<StockPackageStatus> statuses, int page, int size,
                           String sort) {
            this(location, sku, statuses, page, size, sort, null, null, null, null);
        }
    }

    private final StockPackageSearch search;
    private final LocationDirectory locations;
    private final ObjectProvider<ReturnRequestNumbers> returnNumbers;
    private final WarehouseDirectory warehouses;
    private final PutawayTasks putawayTasks;
    private final ObjectProvider<SkuNames> skuNames;
    private final ObjectProvider<ReceiptLineReceipts> receipts;
    private final ObjectProvider<CartonWork> cartonWork;

    public StockPackageLookup(StockPackageSearch search, LocationDirectory locations,
                              ObjectProvider<ReturnRequestNumbers> returnNumbers, WarehouseDirectory warehouses,
                              PutawayTasks putawayTasks, ObjectProvider<SkuNames> skuNames,
                              ObjectProvider<ReceiptLineReceipts> receipts, ObjectProvider<CartonWork> cartonWork) {
        this.search = search;
        this.locations = locations;
        this.returnNumbers = returnNumbers;
        this.warehouses = warehouses;
        this.putawayTasks = putawayTasks;
        this.skuNames = skuNames;
        this.receipts = receipts;
        this.cartonWork = cartonWork;
    }

    /**
     * @throws BusinessException {@code STOCK_PACKAGE_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} for a carton in a warehouse
     *         the caller is not assigned to, whatever its status
     */
    public StockPackageBalance.Detail byLpn(String lpn) {
        String normal = StockPackageView.normalLpn(lpn);
        StockPackageBalance carton = (normal == null ? null : search.byLpn(normal).orElse(null));
        if (carton == null) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No carton " + normal);
        }
        WarehouseScope.requireLocation(carton.locationCode());
        return new StockPackageBalance.Detail(readable(List.of(carton)).getFirst(), search.contentsOf(carton.id()));
    }

    /**
     * One exact location, a bin or an area - not a prefix: {@code HCM-A0} would silently span shelves.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a blank code; {@code OUT_OF_DATA_SCOPE};
     *         {@code LOCATION_NOT_FOUND}
     */
    public List<StockPackageBalance.LocationLine> atLocation(String locationCode) {
        String code = normalCode(locationCode);
        if (code == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "location is required");
        }
        WarehouseScope.requireLocation(code);
        if (locations.stateOf(new LocationId(code)) == LocationDirectory.LocationState.UNKNOWN) {
            throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + code);
        }
        return search.atLocation(code);
    }

    /**
     * Always narrowed to the caller's warehouses; naming a location or a warehouse outside them is
     * {@code OUT_OF_DATA_SCOPE}, not an empty page, as every scoped filter in inventory answers. An unknown SKU, lot or
     * location is an empty page: they are filters.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a {@code q} shorter than {@link #MIN_LPN_PART};
     *         {@code OUT_OF_DATA_SCOPE}; {@code WAREHOUSE_NOT_FOUND}
     */
    public PageResponse<StockPackageBalance> cartons(CartonQuery query) {
        String location = normalCode(query.location());
        if (location != null) {
            WarehouseScope.requireLocation(location);
        }
        String warehouse = null;
        if (query.warehouseId() != null) {
            WarehouseScope.requireWarehouse(query.warehouseId());
            warehouse = warehouses.prefixOf(query.warehouseId()).orElseThrow(() -> new BusinessException(
                    ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + query.warehouseId()));
        }
        String lpnPart = normalCode(query.q());
        if (lpnPart != null && lpnPart.length() < MIN_LPN_PART) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "q must be at least " + MIN_LPN_PART
                    + " characters", List.of(new FieldError("q", "must be at least " + MIN_LPN_PART + " characters",
                    "Size")));
        }
        String sku = normalCode(query.sku());
        String lot = query.lotNumber() == null || query.lotNumber().isBlank() ? null : query.lotNumber().strip();
        // A blank ?status= binds as a null entry: ignored, not a 500 out of List.copyOf.
        List<StockPackageStatus> asked = query.statuses() == null ? List.of()
                : query.statuses().stream().filter(Objects::nonNull).distinct().toList();
        List<StockPackageStatus> statuses = asked.isEmpty() ? LIVE : asked;
        List<StockStatus> stockStatuses = query.stockStatuses() == null ? List.of()
                : query.stockStatuses().stream().filter(Objects::nonNull).distinct().toList();
        var criteria = new StockPackageSearch.CartonCriteria(location, sku, statuses,
                WarehouseScope.restriction().map(AssignedWarehouses::prefixes).orElse(null), warehouse, lot, lpnPart,
                stockStatuses);
        PageResponse<StockPackageBalance> page = Pages.toResponse(search.cartons(criteria,
                Pages.of(query.page(), query.size(), SORT.parse(query.sort()))));
        return new PageResponse<>(readable(page.items()), page.page(), page.size(), page.totalElements(),
                page.totalPages(), page.hasNext(), page.hasPrevious());
    }

    /**
     * The cartons as a person reads them (#454): the orders holding them, their warehouse, what their SKU is called,
     * the receipt they arrived on, the open work they are in, and the return they came back on. One question per kind
     * of fact for the whole page - each to the module that knows it - never one per carton. Without a module (a module
     * test) its facts are left out.
     */
    private List<StockPackageBalance> readable(List<StockPackageBalance> cartons) {
        if (cartons.isEmpty()) {
            return cartons;
        }
        List<UUID> held = cartons.stream().filter(c -> c.reserved() > 0).map(StockPackageBalance::id).toList();
        Map<UUID, List<StockPackageBalance.Hold>> holds = held.isEmpty() ? Map.of() : search.holdsOf(held);
        Map<String, UUID> warehouseOf = warehouses.warehousesOf(cartons.stream()
                .map(StockPackageBalance::locationCode).filter(Objects::nonNull).distinct().toList());
        SkuNames names = skuNames.getIfAvailable();
        Map<String, SkuNames.ItemNames> itemNames = names == null ? Map.of()
                : names.itemNamesOf(cartons.stream().map(StockPackageBalance::sku).distinct().toList());
        List<UUID> lines = cartons.stream().map(StockPackageBalance::goodsReceiptLineId).filter(Objects::nonNull)
                .distinct().toList();
        ReceiptLineReceipts receiptsOf = lines.isEmpty() ? null : receipts.getIfAvailable();
        Map<UUID, ReceiptLineReceipts.Receipt> receiptOf = receiptsOf == null ? Map.of() : receiptsOf.receiptsOf(lines);
        Map<UUID, com.stockflow.warehouse.api.PutawayTaskView> putaway = putawayTasks.openTasksOf(
                cartons.stream().map(StockPackageBalance::id).toList());
        CartonWork work = cartonWork.getIfAvailable();
        Map<String, CartonWork.Work> picking = work == null ? Map.of()
                : work.openWorkOf(cartons.stream().map(StockPackageBalance::lpn).toList());
        List<StockPackageBalance> readable = cartons.stream().map(carton -> {
            SkuNames.ItemNames item = itemNames.get(carton.sku());
            ReceiptLineReceipts.Receipt receipt = carton.goodsReceiptLineId() == null ? null
                    : receiptOf.get(carton.goodsReceiptLineId());
            return carton.withHeldFor(holds.getOrDefault(carton.id(), List.of())).named(
                    warehouseOf.get(carton.locationCode()), WarehouseScope.prefixOf(carton.locationCode()),
                    item == null ? null : item.productName(), item == null ? null : item.variantName(),
                    receipt == null ? null : receipt.id(), receipt == null ? null : receipt.number(),
                    openTask(putaway.get(carton.id()), picking.get(carton.lpn())));
        }).toList();
        return named(readable);
    }

    /** A cycle count is read with the carton itself; otherwise a putaway task, then a pick or a check. */
    private static StockPackageBalance.OpenTask openTask(com.stockflow.warehouse.api.PutawayTaskView putaway,
                                                         CartonWork.Work work) {
        if (putaway != null) {
            return new StockPackageBalance.OpenTask(StockPackageBalance.TaskType.PUTAWAY, putaway.id(), null);
        }
        if (work != null) {
            return new StockPackageBalance.OpenTask(work.kind() == CartonWork.Kind.PICK
                    ? StockPackageBalance.TaskType.PICK : StockPackageBalance.TaskType.CARTON_CHECK, work.taskId(),
                    null);
        }
        return null;
    }

    /**
     * The cartons that came back on a return request, naming it by its RMA number (audit round 3, RAD-4): one read for
     * the page. Without the order module (its slice test) they keep only the id.
     */
    private List<StockPackageBalance> named(List<StockPackageBalance> cartons) {
        List<UUID> requests = cartons.stream().map(StockPackageBalance::returnRequestId).filter(Objects::nonNull)
                .distinct().toList();
        ReturnRequestNumbers numbers = requests.isEmpty() ? null : returnNumbers.getIfAvailable();
        if (numbers == null) {
            return cartons;
        }
        Map<UUID, String> rma = numbers.rmaNumbers(requests);
        return cartons.stream().map(carton -> carton.returnRequestId() == null ? carton
                : carton.withReturnRmaNumber(rma.get(carton.returnRequestId()))).toList();
    }

    /** Location codes and SKUs are stored upper-case; a client may send either. Blank is absent. */
    private static String normalCode(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
