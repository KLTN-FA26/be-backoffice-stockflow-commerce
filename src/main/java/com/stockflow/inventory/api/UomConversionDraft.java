package com.stockflow.inventory.api;

import java.math.BigDecimal;

/**
 * What a packing unit is declared or replaced with. {@code active} false retires it (BR-10); an item has one active
 * unit, its carton (01 BR-03).
 */
public record UomConversionDraft(
        String unitOfMeasure,
        int factor,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        boolean active
) {
}
