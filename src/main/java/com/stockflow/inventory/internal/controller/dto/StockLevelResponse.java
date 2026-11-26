package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Inventory Level of one SKU in one warehouse — a computed roll-up, not an editable record.
 *
 * <p>Each quantity is in pieces and again in cartons (kltn-docs 01 BR-09): the warehouse handles only whole cartons,
 * and the pieces say how many units those cartons hold. The cartons are counted, not divided out of the pieces
 * (ADR-0011, step 3e), so a SKU with cartons of two counts, or with no carton unit, still has them.</p>
 */
@Schema(name = "StockLevel", description = "Stock of one SKU in one warehouse, summed over its locations")
public record StockLevelResponse(

        @Schema(example = "LY-GIAY-DEMO-12OZ-XANH")
        String sku,

        @Schema(description = "Warehouse code, the prefix of every location code in it", example = "HN")
        String warehouseCode,

        @Schema(description = "Pieces in one carton of this SKU; null when it has no carton unit", nullable = true,
                example = "1000")
        Integer unitsPerCarton,

        @Schema(description = "Every physical unit, whatever its status")
        int onHand,

        @Schema(description = "Units in AVAILABLE status, before subtracting reservations")
        int available,

        @Schema(description = "Units held here and not allocated: older checkout holds")
        int reserved,

        @Schema(description = "Units allocated to a bin and lot for picking, held until picked; not counted in reserved")
        int allocated,

        @Schema(description = "Available to promise: available minus reserved minus allocated, capped system-wide")
        int atp,

        @Schema(description = "The cartons in stock, counted (cartons of every count)")
        Integer onHandCartons,

        @Schema(description = "Of onHandCartons, those in AVAILABLE status")
        Integer availableCartons,

        @Schema(description = "Cartons held here and not allocated")
        Integer reservedCartons,

        @Schema(description = "Cartons allocated for picking; each allocation is one carton")
        Integer allocatedCartons,

        @Schema(description = "Cartons still to promise, worked out per carton count as a hold is promised")
        Integer atpCartons
) {
}
