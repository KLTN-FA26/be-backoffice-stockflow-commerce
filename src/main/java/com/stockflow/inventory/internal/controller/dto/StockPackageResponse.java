package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "StockPackage", description = "One carton in a list of cartons (SCRUM-473)")
public record StockPackageResponse(
        @Schema(example = "LPN-20261026-000001") String lpn,
        String sku,
        @Schema(example = "CARTON") String unitOfMeasure,
        @Schema(description = "What it was made to hold: its unit's factor then") int nominalQty,
        @Schema(description = "What was counted into it at receipt; null until counted") Integer receivedQty,
        String lotNumber,
        LocalDate expiryDate,
        @Schema(description = "What is left in it") int onHand,
        @Schema(description = "Of onHand, the units held for orders") int reserved,
        @Schema(description = "RECEIVING, IN_STOCK, CONSUMED, LOST or VOID") String status,
        boolean unopened,
        String locationCode,
        UUID goodsReceiptLineId
) {
}
