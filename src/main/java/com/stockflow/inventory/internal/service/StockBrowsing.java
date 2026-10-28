package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the back office reads about stock beyond one SKU (SCRUM-462): the holds — which order holds
 * what, where, until when — and every stock row of a warehouse in any status, page by page. A
 * warehouse-bound caller sees only their own warehouses (BR-SEC-002).
 */
public interface StockBrowsing {

    record Reservation(UUID reservationId, UUID orderId, UUID stockItemId, String sku, String locationCode,
                       String lotNumber, int quantity, ReservationStatus status, Instant reservedAt,
                       Instant expiresAt, ReleaseReason releaseReason, Instant closedAt) {
    }

    /** @param warehouse a warehouse prefix ({@code HCM}); null for every warehouse the caller may see */
    record ReservationQuery(UUID orderId, String sku, List<ReservationStatus> statuses, String warehouse, int page,
                            int size) {
    }

    /**
     * {@code available} is what may still be promised: on hand less held, and zero unless sellable and unexpired.
     * The three quantities are null, and {@code hiddenDuringCount} true, for a row of a slot a cycle count holds when
     * the reader may not see the books (SCRUM-146).
     */
    record StockRow(UUID stockItemId, String sku, String locationCode, String lotNumber, LocalDate expiryDate,
                    Integer onHand, Integer reserved, Integer available, StockStatus status, Instant receivedAt,
                    boolean hiddenDuringCount) {

        public StockRow hidden() {
            return new StockRow(stockItemId, sku, locationCode, lotNumber, expiryDate, null, null, null, status,
                    receivedAt, true);
        }
    }

    /** @param location the start of a location code ({@code HCM-A01}); it must lie in the warehouse */
    record StockQuery(String location, String sku, String lotNumber, List<StockStatus> statuses,
                      boolean includeEmpty, int page, int size, String sort) {
    }

    PageResponse<Reservation> reservations(ReservationQuery query);

    /** RESERVATION_NOT_FOUND when there is no such hold; OUT_OF_DATA_SCOPE when it sits in another warehouse. */
    Reservation reservation(UUID reservationId);

    /** WAREHOUSE_NOT_FOUND for an unknown warehouse; OUT_OF_DATA_SCOPE when it is not the caller's. */
    default PageResponse<StockRow> warehouseStock(UUID warehouseId, StockQuery query) {
        return warehouseStock(warehouseId, query, BookVisibility.FULL);
    }

    /** As above; rows of slots under a count show no quantity to a reader who may not see the books. */
    PageResponse<StockRow> warehouseStock(UUID warehouseId, StockQuery query, BookVisibility visibility);

    /**
     * Which of these stock rows lie in a slot a cycle count holds (open, or its variance pending approval): the ones
     * whose quantity a reader without {@code inventory-cycle-counts:APPROVE} must not see (SCRUM-146).
     */
    Set<UUID> underCount(Collection<UUID> stockItemIds);
}
