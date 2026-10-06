package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Inventory Level of one SKU in one warehouse — a computed roll-up, not an editable record. */
@Schema(name = "StockLevel", description = "Stock of one SKU in one warehouse, summed over its locations")
public record StockLevelResponse(

        @Schema(example = "SOFA-3S-GREY")
        String sku,

        @Schema(description = "Warehouse code, the prefix of every location code in it", example = "HN")
        String warehouseCode,

        @Schema(description = "Every physical unit, whatever its status")
        int onHand,

        @Schema(description = "Units in AVAILABLE status, before subtracting reservations")
        int available,

        @Schema(description = "Units held for open orders")
        int reserved,

        @Schema(description = "Units assigned to a pick; 0 until fulfillment records allocations")
        int allocated,

        @Schema(description = "Available to promise: available minus reserved")
        int atp
) {
}
