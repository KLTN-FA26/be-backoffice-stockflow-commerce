package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(name = "StockPackageLocationLine",
        description = "What one location holds of one SKU, lot and stock status, by carton (SCRUM-473)")
public record StockPackageLocationLineResponse(
        String sku,
        String lotNumber,
        LocalDate expiryDate,
        @Schema(description = "INBOUND, AVAILABLE, QUARANTINE, BLOCKED, DAMAGED or EXPIRED") String status,
        @Schema(description = "Unopened cartons") int wholeCartons,
        @Schema(description = "Units in the unopened cartons") int wholeCartonUnits,
        @Schema(description = "Opened cartons still holding something") int openedCartons,
        @Schema(description = "Units left in the opened cartons") int openedCartonUnits,
        @Schema(description = "Units in no carton") int looseUnits,
        @Schema(description = "wholeCartonUnits + openedCartonUnits + looseUnits") int totalUnits
) {
}
