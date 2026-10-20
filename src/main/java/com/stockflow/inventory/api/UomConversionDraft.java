package com.stockflow.inventory.api;

import java.math.BigDecimal;

/**
 * What a packing unit is declared or replaced with. On the base unit only {@code defaultPurchase} (and
 * {@code active}, which must stay true) count: its code, factor, size and weight are the item's
 * logistics and are ignored here.
 */
public record UomConversionDraft(
        String unitOfMeasure,
        int factor,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        boolean defaultPurchase,
        boolean active
) {
}
