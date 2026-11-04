package com.stockflow.inventory.api;

import java.math.BigDecimal;

/**
 * What a packing unit is declared or replaced with. On the base unit only {@code defaultPurchase} (and
 * {@code active}, which must stay true) count: its code, factor, size and weight are the item's
 * logistics and are ignored here.
 *
 * <p>{@code defaultShipping}: marking a unit takes the mark from the item's previous default shipping unit
 * in the same transaction (the 409-free choice, as for the default purchase unit); a retired unit cannot
 * carry it. Unmarking leaves the item without one: the resolver's fallback chain applies.</p>
 */
public record UomConversionDraft(
        String unitOfMeasure,
        int factor,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        boolean defaultPurchase,
        boolean active,
        boolean defaultShipping
) {
    /** A draft that does not ask for the default shipping mark. */
    public UomConversionDraft(String unitOfMeasure, int factor, BigDecimal weightKg, BigDecimal lengthCm,
                              BigDecimal widthCm, BigDecimal heightCm, boolean defaultPurchase, boolean active) {
        this(unitOfMeasure, factor, weightKg, lengthCm, widthCm, heightCm, defaultPurchase, active, false);
    }
}
