package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "StockReservationRow", description = "One hold on one stock row, for the back office (SCRUM-462)")
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
        @Schema(description = "Null once the order no longer waits for payment (the hold is pinned)") Instant expiresAt,
        @Schema(description = "Why it was released: ORDER_CANCELLED, PAYMENT_FAILED, RESERVATION_EXPIRED, MANUAL_OVERRIDE")
        String releaseReason,
        Instant closedAt
) {
}
