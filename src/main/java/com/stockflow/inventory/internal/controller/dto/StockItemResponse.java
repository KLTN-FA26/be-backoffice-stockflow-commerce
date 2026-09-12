package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What the API returns for one stock row.
 *
 * <p>A separate type from both {@code StockItem} and {@code StockAvailability}, which looks like
 * duplication and is not. Returning the aggregate would publish its internal shape as the API
 * contract, so renaming a private field becomes a breaking change for the frontend. Returning
 * {@code StockAvailability} would tie the wire format to another module's view type.</p>
 */
@Schema(name = "StockItem", description = "Stock on hand for one SKU at one location")
public record StockItemResponse(

        @Schema(description = "Stock item identifier")
        UUID id,

        @Schema(example = "SOFA-3S-GREY")
        String sku,

        @Schema(description = "Bin code", example = "HCM-A-01-02-B")
        String locationCode,

        @Schema(description = "Lot number; null when the product is not lot-tracked")
        String lotNumber,

        LocalDate expiryDate,

        @Schema(description = "Physical units present, including reserved ones")
        int onHand,

        @Schema(description = "Units promised to open orders")
        int reserved,

        @Schema(description = "Available to promise: onHand minus reserved, zero if not sellable")
        int available,

        @Schema(example = "AVAILABLE")
        String status
) {
}
