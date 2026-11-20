package com.stockflow.product.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One unit of measure of a SKU; for the base unit, factor 1 and the item's own size and weight.
 * {@code defaultShipping}: the unit load planning estimates this SKU's cartons in; one per item.
 */
public record UomConversionResponse(
        UUID id,
        String sku,
        String unitOfMeasure,
        int factor,
        boolean base,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        boolean active,
        long version,
        boolean defaultShipping
) {

    /**
     * Constant, kept for the FE until FE issue 220; removed with the gated Task 6. The default purchase unit is gone:
     * this is {@code true} for the base unit and {@code false} otherwise, worked out here from {@code base}, never stored.
     */
    @Deprecated
    @JsonProperty("defaultPurchase")
    public boolean defaultPurchase() {
        return base;
    }
}
