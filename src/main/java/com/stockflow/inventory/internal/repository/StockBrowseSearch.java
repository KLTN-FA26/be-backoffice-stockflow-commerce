package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side for the back-office stock screens (SCRUM-462): holds, and every carton in stock of a
 * warehouse whatever its stock status (a "stock row" of the screen is one carton, ADR-0011; the field named
 * {@code stockItemId} carries the carton's id, the HTTP contract kept its name). Both are limited to the caller's warehouses (BR-SEC-002) through
 * the location code, as the ledger is. Outside the domain port for the same reason as
 * {@link StockLedgerSearch}: paging is Spring Data's vocabulary.
 */
public interface StockBrowseSearch {

    record ReservationRow(UUID reservationId, UUID orderId, UUID stockItemId, String sku, String locationCode,
                          String lotNumber, int quantity, int cartons, ReservationStatus status, Instant reservedAt,
                          ReleaseReason releaseReason, Instant closedAt, String orderNumber) {
    }

    /** @param warehousePrefix the warehouse the held stock sits in, by prefix ({@code HCM}); null for all */
    record ReservationCriteria(UUID orderId, String sku, List<ReservationStatus> statuses, String warehousePrefix) {
    }

    record StockRow(UUID stockItemId, String sku, String locationCode, String lotNumber, int onHand, int reserved, StockStatus status, Instant receivedAt) {
    }

    /**
     * @param locationPrefix the start of a location code inside the warehouse ({@code HCM-A01})
     * @param includeEmpty   also cartons that are out of stock (picked, written off, lost), shown with nothing on
     *                       hand and no status; they are left out by default
     */
    record StockCriteria(String warehousePrefix, String locationPrefix, String sku, String lotNumber,
                         List<StockStatus> statuses, boolean includeEmpty) {
    }

    Page<ReservationRow> reservations(ReservationCriteria criteria, Pageable pageable);

    Optional<ReservationRow> reservation(UUID reservationId);

    Page<StockRow> stock(StockCriteria criteria, Pageable pageable);
}
