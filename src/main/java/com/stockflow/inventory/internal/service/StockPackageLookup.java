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
import com.stockflow.inventory.internal.domain.StockPackageBalance;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.repository.StockPackageSearch;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The carton lookup (SCRUM-473, #125 item 11, the carton lookup screen of #138): a carton by the LPN on its label,
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

    private static final SortWhitelist SORT = SortWhitelist.of(StockPackageSearch.SORTABLE.toArray(String[]::new))
            .withDefault("lpn", Sort.Direction.ASC);

    /**
     * @param location one exact location code; null for any the caller may see
     * @param statuses null or empty for the live ones (RECEIVING, IN_STOCK); null entries are ignored
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
    public StockPackageBalance.Detail byLpn(String lpn) {
        String normal = StockPackageView.normalLpn(lpn);
        StockPackageBalance carton = (normal == null ? null : search.byLpn(normal).orElse(null));
        if (carton == null) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No carton " + normal);
        }
        WarehouseScope.requireLocation(carton.locationCode());
        return new StockPackageBalance.Detail(carton, search.contentsOf(carton.id()));
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
     * Always narrowed to the caller's warehouses; naming a location in another is {@code OUT_OF_DATA_SCOPE}, not an
     * empty page, as every scoped filter in inventory answers. An unknown SKU or location is an empty page: they are
     * filters.
     */
    public PageResponse<StockPackageBalance> cartons(CartonQuery query) {
        String location = normalCode(query.location());
        if (location != null) {
            WarehouseScope.requireLocation(location);
        }
        String sku = normalCode(query.sku());
        // A blank ?status= binds as a null entry: ignored, not a 500 out of List.copyOf.
        List<StockPackageStatus> asked = query.statuses() == null ? List.of()
                : query.statuses().stream().filter(Objects::nonNull).distinct().toList();
        List<StockPackageStatus> statuses = asked.isEmpty() ? LIVE : asked;
        var criteria = new StockPackageSearch.CartonCriteria(location, sku, statuses,
                WarehouseScope.restriction().map(AssignedWarehouses::prefixes).orElse(null));
        return Pages.toResponse(search.cartons(criteria, Pages.of(query.page(), query.size(), SORT.parse(query.sort()))));
    }

    /** Location codes and SKUs are stored upper-case; a client may send either. Blank is absent. */
    private static String normalCode(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
