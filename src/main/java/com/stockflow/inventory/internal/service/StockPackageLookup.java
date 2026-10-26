package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.repository.StockPackageSearch;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The carton lookup (SCRUM-473, #125 item 11, the "Tra cứu thùng" screen of #138): a carton by the LPN on its label,
 * what one location holds split into whole cartons, opened cartons and loose units, and cartons page by page.
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

    private static final SortWhitelist SORT = SortWhitelist.of("lpn", "locationCode", "sku")
            .withDefault("lpn", Sort.Direction.ASC);

    /**
     * @param nominalQty what the carton was made to hold: its packing unit's factor then
     * @param lotNumber  of the stock it holds (one lot at most); null when it holds nothing
     * @param onHand     what is left in it, the sum of its rows; 0 for a carton with none yet (RECEIVING)
     * @param reserved   of that, what orders hold
     * @param contents   its rows holding stock, earliest expiry first
     */
    public record Detail(String lpn, String sku, String unitOfMeasure, int nominalQty, Integer receivedQty,
                         String lotNumber, LocalDate expiryDate, int onHand, int reserved, String status,
                         boolean unopened, String locationCode, UUID goodsReceiptLineId, Instant closedAt,
                         List<Content> contents) {
    }

    public record Content(String lotNumber, LocalDate expiryDate, Instant receivedAt, String status, int onHand,
                          int reserved) {
    }

    /** One SKU, lot and stock status at a location; {@code totalUnits} is the three unit columns added. */
    public record LocationLine(String sku, String lotNumber, LocalDate expiryDate, String status, int wholeCartons,
                               int wholeCartonUnits, int openedCartons, int openedCartonUnits, int looseUnits,
                               int totalUnits) {
    }

    public record Carton(String lpn, String sku, String unitOfMeasure, int nominalQty, Integer receivedQty,
                         String lotNumber, LocalDate expiryDate, int onHand, int reserved, String status,
                         boolean unopened, String locationCode, UUID goodsReceiptLineId) {
    }

    /**
     * @param location one exact location code; null for any the caller may see
     * @param statuses null or empty for the live ones (RECEIVING, IN_STOCK)
     */
    public record CartonQuery(String location, String sku, List<StockPackageStatus> statuses, int page, int size,
                              String sort) {
    }

    private final StockPackageSearch search;
    private final LocationDirectory locations;

    public StockPackageLookup(StockPackageSearch search, LocationDirectory locations) {
        this.search = search;
        this.locations = locations;
    }

    /**
     * @throws BusinessException {@code STOCK_PACKAGE_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} for a carton in a warehouse
     *         the caller is not assigned to, whatever its status
     */
    public Detail byLpn(String lpn) {
        String normal = StockPackageView.normalLpn(lpn);
        StockPackageSearch.CartonRow carton = (normal == null ? null : search.byLpn(normal).orElse(null));
        if (carton == null) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No carton " + normal);
        }
        WarehouseScope.requireLocation(carton.locationCode());
        List<Content> contents = search.contentsOf(carton.id()).stream()
                .map(row -> new Content(row.lotNumber(), row.expiryDate(), row.receivedAt(), row.status().name(),
                        row.onHand(), row.reserved()))
                .toList();
        return new Detail(carton.lpn(), carton.sku(), carton.unitOfMeasure(), carton.nominalQty(),
                carton.receivedQty(), carton.lotNumber(), carton.expiryDate(), carton.onHand(), carton.reserved(),
                carton.status().name(), carton.unopened(), carton.locationCode(), carton.goodsReceiptLineId(),
                carton.closedAt(), contents);
    }

    /**
     * One exact location, a bin or an area - not a prefix: {@code HCM-A0} would silently span shelves.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a blank code; {@code OUT_OF_DATA_SCOPE};
     *         {@code LOCATION_NOT_FOUND}
     */
    public List<LocationLine> atLocation(String locationCode) {
        String code = normalCode(locationCode);
        if (code == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "location is required");
        }
        WarehouseScope.requireLocation(code);
        if (locations.stateOf(new LocationId(code)) == LocationDirectory.LocationState.UNKNOWN) {
            throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + code);
        }
        return search.atLocation(code).stream()
                .map(line -> new LocationLine(line.sku(), line.lotNumber(), line.expiryDate(), line.status().name(),
                        line.wholeCartons(), line.wholeCartonUnits(), line.openedCartons(), line.openedCartonUnits(),
                        line.looseUnits(),
                        line.wholeCartonUnits() + line.openedCartonUnits() + line.looseUnits()))
                .toList();
    }

    /**
     * Always narrowed to the caller's warehouses; naming a location in another is {@code OUT_OF_DATA_SCOPE}, not an
     * empty page, as every scoped filter in inventory answers. An unknown SKU or location is an empty page: they are
     * filters.
     */
    public PageResponse<Carton> cartons(CartonQuery query) {
        String location = normalCode(query.location());
        if (location != null) {
            WarehouseScope.requireLocation(location);
        }
        String sku = normalCode(query.sku());
        List<StockPackageStatus> statuses = query.statuses() == null || query.statuses().isEmpty()
                ? LIVE : List.copyOf(query.statuses());
        var criteria = new StockPackageSearch.CartonCriteria(location, sku, statuses,
                WarehouseScope.restriction().map(AssignedWarehouses::prefixes).orElse(null));
        return Pages.toResponse(search.cartons(criteria, Pages.of(query.page(), query.size(), SORT.parse(query.sort()))),
                row -> new Carton(row.lpn(), row.sku(), row.unitOfMeasure(), row.nominalQty(), row.receivedQty(),
                        row.lotNumber(), row.expiryDate(), row.onHand(), row.reserved(), row.status().name(),
                        row.unopened(), row.locationCode(), row.goodsReceiptLineId()));
    }

    /** Location codes and SKUs are stored upper-case; a client may send either. Blank is absent. */
    private static String normalCode(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
