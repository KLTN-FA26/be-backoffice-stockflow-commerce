package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read side of the carton lookup (SCRUM-473, #125 item 11): a carton by its label, what one location holds split into
 * whole cartons, opened cartons and loose units, and cartons page by page. Each method is one SQL statement, whatever
 * the number of cartons. Outside the domain port for the same reason as {@link StockBrowseSearch}: paging is Spring
 * Data's vocabulary.
 */
public interface StockPackageSearch {

    /**
     * One carton as {@code inventory.stock_package_balance} shows it. {@code lotNumber} and {@code expiryDate} are those
     * of its rows holding stock - a carton holds one lot at most - and null when it holds nothing.
     */
    record CartonRow(UUID id, String lpn, String sku, String unitOfMeasure, int nominalQty, Integer receivedQty,
                     String locationCode, StockPackageStatus status, boolean unopened, String lotNumber,
                     LocalDate expiryDate, int onHand, int reserved, UUID goodsReceiptLineId, Instant closedAt) {
    }

    /** One stock row inside a carton. */
    record ContentRow(String lotNumber, LocalDate expiryDate, Instant receivedAt, StockStatus status, int onHand,
                      int reserved) {
    }

    /** What one location holds of one SKU, lot and stock status. */
    record LocationLine(String sku, String lotNumber, LocalDate expiryDate, StockStatus status,
                        int wholeCartons, int wholeCartonUnits, int openedCartons, int openedCartonUnits,
                        int looseUnits) {
    }

    /**
     * @param locationCode      one exact location code; null for any
     * @param sku               null for any
     * @param statuses          never empty: the caller decides the default
     * @param warehousePrefixes the warehouses a limited caller may see; null when not limited, empty matches nothing
     */
    record CartonCriteria(String locationCode, String sku, List<StockPackageStatus> statuses,
                          Set<String> warehousePrefixes) {
    }

    Optional<CartonRow> byLpn(String lpn);

    /** Its stock rows holding something, earliest expiry first, then oldest receipt. */
    List<ContentRow> contentsOf(UUID stockPackageId);

    /** Grouped by SKU, lot, expiry and stock status; rows holding nothing are left out. */
    List<LocationLine> atLocation(String locationCode);

    /** Sortable by {@code lpn}, {@code locationCode} and {@code sku}. */
    Page<CartonRow> cartons(CartonCriteria criteria, Pageable pageable);
}
