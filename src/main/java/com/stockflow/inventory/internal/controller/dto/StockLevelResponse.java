package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Inventory Level of one SKU in one warehouse — a computed roll-up, not an editable record.
 *
 * <p>Each quantity is in pieces and again in cartons (kltn-docs 01 BR-09): the warehouse handles only whole cartons,
 * and the pieces say how many units those cartons hold.</p>
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

        @Schema(description = "onHand in cartons; null when there is no carton unit or it is not a whole number of "
                + "cartons", nullable = true)
        Integer onHandCartons,

        @Schema(description = "available in cartons; null as for onHandCartons", nullable = true)
        Integer availableCartons,

        @Schema(description = "reserved in cartons; null as for onHandCartons", nullable = true)
        Integer reservedCartons,

        @Schema(description = "allocated in cartons; null as for onHandCartons", nullable = true)
        Integer allocatedCartons,

        @Schema(description = "atp in cartons; null as for onHandCartons", nullable = true)
        Integer atpCartons
) {
}
