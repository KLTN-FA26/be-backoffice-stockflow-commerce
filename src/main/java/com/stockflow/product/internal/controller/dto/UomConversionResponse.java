package com.stockflow.product.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One packing unit of a SKU: a carton specification - the pieces it holds ({@code factor}), its size and full weight.
 * The unit the pieces are counted in is the item's own and is not listed (V20261226000200).
 * {@code defaultShipping}: the unit load planning estimates this SKU's cartons in; one per item.
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
        long version,
        boolean defaultShipping
) {

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
