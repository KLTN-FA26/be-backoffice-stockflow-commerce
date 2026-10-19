package com.stockflow.product.internal.controller.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** One unit of measure of a SKU; for the base unit, factor 1 and the item's own size and weight. */
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
        boolean defaultPurchase,
        boolean active,
        long version
) {
}
