package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /** {@code available} is what may still be promised: on hand less held, and zero unless sellable and unexpired. */
    record StockRow(UUID stockItemId, String sku, String locationCode, String lotNumber, LocalDate expiryDate,
                    int onHand, int reserved, int available, StockStatus status, Instant receivedAt) {
    }

    /** @param location the start of a location code ({@code HCM-A01}); it must lie in the warehouse */
    record StockQuery(String location, String sku, String lotNumber, List<StockStatus> statuses,
                      boolean includeEmpty, int page, int size, String sort) {
    }

    PageResponse<Reservation> reservations(ReservationQuery query);

    /** RESERVATION_NOT_FOUND when there is no such hold; OUT_OF_DATA_SCOPE when it sits in another warehouse. */
    Reservation reservation(UUID reservationId);

    /** WAREHOUSE_NOT_FOUND for an unknown warehouse; OUT_OF_DATA_SCOPE when it is not the caller's. */
    PageResponse<StockRow> warehouseStock(UUID warehouseId, StockQuery query);
}
