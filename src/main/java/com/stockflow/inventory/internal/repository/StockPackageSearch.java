package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.StockPackageBalance;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read side of the carton lookup (SCRUM-473, #125 item 11): a carton by its label, what one location holds split into
 * whole cartons, opened cartons and loose units, and cartons page by page. Each method is one SQL statement (the list
 * two: its total and its page), whatever the number of cartons. Outside the domain port for the same reason as
 * {@link StockBrowseSearch}: paging is Spring Data's vocabulary.
 */
public interface StockPackageSearch {

    /**
     * What {@link #cartons} sorts by. The service's whitelist is built from this list and the adapter refuses to start
     * unless it has a column for each, so a property cannot pass the whitelist and then fail in the query.
     */
    List<String> SORTABLE = List.of("lpn", "locationCode", "sku", "receivedAt");

    /**
     * @param locationCode      one exact location code; null for any
     * @param sku               null for any
     * @param statuses          never empty: the caller decides the default
     * @param warehousePrefixes the warehouses a limited caller may see; null when not limited, empty matches nothing
     * @param warehousePrefix   one warehouse (its location prefix); null for any
     * @param lotNumber         one exact lot; null for any
     * @param lpnPart           part of the LPN, upper case; null for any
     * @param stockStatuses     cartons with a stock row holding something in one of these; null or empty for any
     */
    record CartonCriteria(String locationCode, String sku, List<StockPackageStatus> statuses,
                          Set<String> warehousePrefixes, String warehousePrefix, String lotNumber, String lpnPart,
                          List<StockStatus> stockStatuses) {

        public CartonCriteria(String locationCode, String sku, List<StockPackageStatus> statuses,
                              Set<String> warehousePrefixes) {
            this(locationCode, sku, statuses, warehousePrefixes, null, null, null, null);
        }
    }

    Optional<StockPackageBalance> byLpn(String lpn);

    /** Its stock rows holding something, oldest receipt first. */
    List<StockPackageBalance.Content> contentsOf(UUID stockPackageId);

    /** Grouped by SKU, lot and stock status; rows holding nothing are left out. */
    List<StockPackageBalance.LocationLine> atLocation(String locationCode);

    /** Sortable by {@link #SORTABLE}. */
    Page<StockPackageBalance> cartons(CartonCriteria criteria, Pageable pageable);

    /**
     * The orders holding units of each of these cartons: its stock rows' live holds ({@code HELD}), summed per order.
     * One statement for a whole page; a carton nothing holds is absent.
     */
    Map<UUID, List<StockPackageBalance.Hold>> holdsOf(Collection<UUID> stockPackageIds);
}
