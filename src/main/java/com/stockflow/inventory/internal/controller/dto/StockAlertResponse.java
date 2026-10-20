package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "StockAlert", description = "One episode of a low-stock or expiry condition")
public record StockAlertResponse(

        UUID id,

        @Schema(description = "REORDER (at or below the reorder point), SAFETY (below the safety stock), "
                + "EXPIRING (within the warning window) or EXPIRED", example = "REORDER")
        String kind,

        @Schema(example = "SOFA-3S-GREY")
        String sku,

        @Schema(description = "Null for REORDER and SAFETY: those thresholds are per SKU for the whole system")
        UUID warehouseId,

        @Schema(description = "The warehouse prefix", example = "HCM")
        String warehouseCode,

        String lotNumber,

        LocalDate expiryDate,

        @Schema(description = "expiryDate minus today (business calendar); negative once expired")
        Long daysToExpiry,

        @Schema(description = "Usable on-hand of the SKU (REORDER, SAFETY) or on-hand of the lot in the warehouse (expiry kinds)")
        int observedQty,

        @Schema(description = "The reorder point or safety stock; null for expiry kinds")
        Integer thresholdQty,

        @Schema(description = "An EXPIRED lot that still holds a customer's reservation and so could not be blocked")
        boolean reservationImpaired,

        @Schema(description = "OPEN, ACKNOWLEDGED (seen, condition still true) or RESOLVED")
        String status,

        Instant openedAt,
        Instant lastObservedAt,
        UUID acknowledgedBy,
        String acknowledgedByName,
        Instant acknowledgedAt,
        String ackNote,
        Instant resolvedAt,

        @Schema(description = "RECOVERED, UNCONFIGURED, NOT_PURCHASABLE (REORDER/SAFETY of a SKU no longer bought), STOCK_GONE or EXPIRED_OUT; null while open")
        String resolution,

        @Schema(description = "Send back when acknowledging")
        long version
) {
}
