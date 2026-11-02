package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One damage report in a list; the photos and decisions are on the detail. */
@Schema(name = "DamageReportRow")
public record DamageReportRowResponse(
        UUID id,
        String number,
        String origin,
        @Schema(description = "OPEN while units wait for a decision; AWAITING_SUPPLIER while units decided for the "
                + "supplier are still in quarantine; CLOSED after") String status,
        UUID warehouseId,
        String sku,
        String lotNumber,
        String sourceLocationCode,
        String damageLocationCode,
        int quantity,
        int decidedQuantity,
        int pendingQuantity,
        String reason,
        UUID orderId,
        UUID reportedBy,
        Instant reportedAt,
        @Schema(description = "Units decided for the supplier, still in quarantine to be handed over")
        int awaitingSupplierQuantity
) {
}
