package com.stockflow.product.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One packing unit of a SKU: a carton specification - the pieces it holds ({@code factor}), its size and full weight.
 * The unit the pieces are counted in is the item's own and is not listed (V20261226000200).
 */
public record UomConversionResponse(
        UUID id,
        String sku,
        String unitOfMeasure,
        int factor,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        boolean active,
        long version
) {

    /**
     * Kept for the FE: true on the item's active carton, false on a retired unit. There is no default shipping mark any
     * more (V20261226000300): an item has one active carton (01 BR-03) and ships in it.
     */
    @Deprecated
    @JsonProperty("defaultShipping")
    public boolean defaultShipping() {
        return active;
    }

    /**
     * Constant false, kept for the FE: no unit listed is a base unit any more - the base unit has no row
     * (V20261226000200) - so a screen that hid or locked the base row finds none.
     */
    @Deprecated
    @JsonProperty("base")
    public boolean base() {
        return false;
    }

    /**
     * Constant false, kept for the FE until FE issue 220; removed with the gated Task 6. The default purchase unit is
     * gone; it was {@code true} for the base unit only.
     */
    @Deprecated
    @JsonProperty("defaultPurchase")
    public boolean defaultPurchase() {
        return false;
    }
}
