package com.stockflow.inventory.api;

import java.math.BigDecimal;

/**
 * A SKU's carton spec (kltn-docs 01 §3 "Quy cách thùng", BR-03): its carton unit - the one packing unit whose factor is
 * the standard count per carton - with what the warehouse manager declared for that carton, its size and its full
 * weight, and the item's may-rotate flag. Every carton of a SKU holds the standard count, so every one
 * has this size and weight (08 §1): the check station puts them on a shipping carton and its label (08 §4.3 step 5,
 * BR-07), and load planning reads them from there (09).
 *
 * <p>Measures are null while not declared; a declared one is positive. A spec that is not {@link #measured()} prints no
 * shipping label (08 BR-07): nothing is guessed, the manager completes it on the item.</p>
 *
 * @param unitOfMeasure  the carton unit's code, e.g. {@code CARTON}
 * @param unitsPerCarton the standard count of base units in one carton (the unit's factor)
 */
public record CartonSpec(String sku, String unitOfMeasure, int unitsPerCarton, BigDecimal lengthCm,
                         BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg, boolean rotatable) {

    /** Size and full weight are all declared: a label can carry them (08 BR-07). */
    public boolean measured() {
        return positive(lengthCm) && positive(widthCm) && positive(heightCm) && positive(weightKg);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
