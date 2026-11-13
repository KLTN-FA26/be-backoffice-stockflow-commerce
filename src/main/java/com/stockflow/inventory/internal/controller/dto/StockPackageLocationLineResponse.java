package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;


@Schema(name = "StockPackageLocationLine",
        description = "What one location holds of one SKU, lot and stock status, by carton (SCRUM-473)")
public record StockPackageLocationLineResponse(
        String sku,
        String lotNumber,
        @Schema(description = "INBOUND, AVAILABLE, QUARANTINE, BLOCKED, DAMAGED or EXPIRED") String status,
        @Schema(description = "Cartons (every carton in stock is whole)") int wholeCartons,
        @Schema(description = "Units in those cartons") int wholeCartonUnits,
        @Schema(description = "Units in no carton: a put-back, a return or a damage report not yet in cartons")
        int looseUnits,
        @Schema(description = "wholeCartonUnits + looseUnits") int totalUnits
) {
}
