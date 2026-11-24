package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * What the API returns for one stock row.
 *
 * <p>A separate type from both {@code StockItem} and {@code StockAvailability}, which looks like
 * duplication and is not. Returning the aggregate would publish its internal shape as the API
 * contract, so renaming a private field becomes a breaking change for the frontend. Returning
 * {@code StockAvailability} would tie the wire format to another module's view type.</p>
 *
 * <p>Quantities are in pieces and again in cartons (kltn-docs 01 BR-09). A stock row is the contents of one carton.</p>
 */
@Schema(name = "StockItem", description = "Stock on hand for one SKU at one location")
public record StockItemResponse(

        @Schema(description = "Stock item identifier")
        UUID id,

        @Schema(example = "LY-GIAY-DEMO-12OZ-XANH")
        String sku,

        @Schema(description = "Bin code", example = "HCM-A-01-02-B")
        String locationCode,

        @Schema(description = "Lot number; null when the product is not lot-tracked")
        String lotNumber,

        @Schema(description = "Pieces in one carton of this SKU; null when it has no carton unit", nullable = true,
                example = "1000")
        Integer unitsPerCarton,

        @Schema(description = "Physical units present, including reserved ones; null while hiddenDuringCount",
                nullable = true)
        Integer onHand,

        @Schema(description = "Units promised to open orders; null while hiddenDuringCount", nullable = true)
        Integer reserved,

        @Schema(description = "Available to promise: onHand minus reserved, zero if not sellable; null while "
                + "hiddenDuringCount", nullable = true)
        Integer available,

        @Schema(description = "onHand in cartons; null while hiddenDuringCount, when there is no carton unit, or when "
                + "it is not a whole number of cartons", nullable = true)
        Integer onHandCartons,

        @Schema(description = "reserved in cartons; null as for onHandCartons", nullable = true)
        Integer reservedCartons,

        @Schema(description = "available in cartons; null as for onHandCartons", nullable = true)
        Integer availableCartons,

        @Schema(example = "AVAILABLE")
        String status,

        @Schema(description = "A cycle count holds this slot and the caller lacks inventory-cycle-counts:APPROVE: the "
                + "quantities are withheld so the count stays blind")
        boolean hiddenDuringCount
) {

    /**
     * The same row with its book quantities withheld (SCRUM-146). The cartons go too: one carton or none is the book
     * the counter must not see. The carton's standard count is master data and stays.
     */
    public StockItemResponse hidden() {
        return new StockItemResponse(id, sku, locationCode, lotNumber, unitsPerCarton, null, null, null,
                null, null, null, status, true);
    }
}
