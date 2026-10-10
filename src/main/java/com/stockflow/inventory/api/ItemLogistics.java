package com.stockflow.inventory.api;

import java.math.BigDecimal;

/**
 * The physical and handling data of one SKU's inventory item (docs 01 BR-08): what a unit weighs and
 * measures, how it ships, how it is stored and whether receiving sends it through QC. It used to sit
 * on the product row; it is per SKU because two variants of one product (a 12oz and a 16oz cup) weigh
 * and pack differently.
 *
 * <p>Nullable measures mean "not known yet"; a known one is positive. {@code packageCount} is how
 * many boxes one unit ships as, {@code packSize} how many units one inner pack holds.</p>
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
        StorageClass storageClass,
        boolean requiresAdultSignature,
        String shippingRestrictionNote,
        boolean qcRequired
) {
}
