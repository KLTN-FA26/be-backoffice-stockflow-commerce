package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "WarehouseStockItem", description = "One stock row of a warehouse, in any status (SCRUM-462)")
public record WarehouseStockItemResponse(
        UUID stockItemId,
        String sku,
        @Schema(example = "HCM-A01-2-B") String locationCode,
        String lotNumber,
        LocalDate expiryDate,
        @Schema(description = "Physical units present, including reserved ones; null while hiddenDuringCount",
                nullable = true) Integer onHand,
        @Schema(description = "Units held for orders; null while hiddenDuringCount", nullable = true) Integer reserved,
        @Schema(description = "onHand minus reserved; zero unless AVAILABLE and not expired; null while "
                + "hiddenDuringCount", nullable = true) Integer available,
        @Schema(description = "INBOUND, AVAILABLE, QUARANTINE, BLOCKED, DAMAGED or EXPIRED") String status,
        Instant receivedAt,
        @Schema(description = "A cycle count holds this slot and the caller lacks inventory-cycle-counts:APPROVE: the "
                + "quantities are withheld so the count stays blind")
        boolean hiddenDuringCount
) {
}
