package com.stockflow.inventory.api;

import java.math.BigDecimal;

/**
 * The physical data of one SKU's inventory item (docs 01 BR-08): what a unit weighs and measures and how it
 * ships. It used to sit
 * on the product row; it is per SKU because two variants of one product (a 12oz and a 16oz cup) weigh
 * and pack differently.
 *
 * <p>Nullable measures mean "not known yet"; a known one is positive. {@code packageCount} is how
 * many boxes one unit ships as, {@code packSize} how many units one inner pack holds.</p>
 *
 * <p>{@code rotatable}: whether a package of this SKU may be turned any of 6 ways when a truck or
 * container is loaded (docs 01 §3 "Cho phép xoay khi xếp xe", default on, E13). It is the default for
 * the package's own flag at packing (08) and is read by load planning (09). Putaway, put-back and moves
 * read it too: a rotatable carton may be turned on the horizontal to fit a bin, length for width (06 BR-01).</p>
 *
 * <p>No storage class, adult-signature flag or shipping-restriction note any more (kltn-docs 06, storage class removed
 * 2026-11-05; team plan 5-4): which bin takes a carton is decided by its size alone, and nothing read
 * the other two.</p>
 */
public record ItemLogistics(
        String unitOfMeasure,
        String barcode,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        BigDecimal packageWeightKg,
        BigDecimal packageLengthCm,
        BigDecimal packageWidthCm,
        BigDecimal packageHeightCm,
        int packageCount,
        int packSize,
        boolean rotatable
) {
    /** Logistics without an opinion on rotation: the documented default, rotatable (docs 01, E13). */
    public ItemLogistics(String unitOfMeasure, String barcode, BigDecimal weightKg, BigDecimal lengthCm,
                         BigDecimal widthCm, BigDecimal heightCm, BigDecimal packageWeightKg,
                         BigDecimal packageLengthCm, BigDecimal packageWidthCm, BigDecimal packageHeightCm,
                         int packageCount, int packSize) {
        this(unitOfMeasure, barcode, weightKg, lengthCm, widthCm, heightCm, packageWeightKg, packageLengthCm,
                packageWidthCm, packageHeightCm, packageCount, packSize, true);
    }
}
