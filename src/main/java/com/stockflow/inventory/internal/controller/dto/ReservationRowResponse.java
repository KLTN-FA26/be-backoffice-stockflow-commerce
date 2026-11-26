package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "StockReservationRow", description = "One hold, for the back office (SCRUM-462); stockItemId is the id of the "
        + "carton an allocation took, null for a hold on no carton yet")
public record ReservationRowResponse(
        UUID reservationId,
        UUID orderId,
        UUID stockItemId,
        String sku,
        @Schema(example = "HCM-A01-2-B") String locationCode,
        String lotNumber,
        int quantity,
        @Schema(description = "HELD, CONSUMED, RELEASED or EXPIRED") String status,
        Instant reservedAt,
        @Schema(description = "Why it was released: ORDER_CANCELLED, PAYMENT_FAILED, RESERVATION_EXPIRED, MANUAL_OVERRIDE")
        String releaseReason,
        Instant closedAt,
        @Schema(description = "The order's number, snapshotted when the hold was written; null only for a hold "
                + "written before its order row existed", example = "SO-20261125-0001", nullable = true)
        String orderNumber
) {
}
