package com.stockflow.warehouse.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The inside of a bin, in metres (the map unit, {@code MapUnit.M}): what a carton must fit within dimension by
 * dimension (kltn-docs 06 BR-01) and what its volume is computed from (BR-19).
 *
 * <p>{@code width} and {@code length} are the bin's own footprint on its shelf level; {@code usableHeight} is the
 * level's clear height, null on a legacy level declared without one - such a bin has no volume and its height is not
 * checked (06 §4.4, §4.6, decided 2026-11-05).</p>
 */
public record BinSize(BigDecimal width, BigDecimal length, BigDecimal usableHeight) {

    /** width x length x usableHeight in m³, six decimals; null without a clear height. */
    public BigDecimal volumeM3() {
        if (width == null || length == null || usableHeight == null) {
            return null;
        }
        return width.multiply(length).multiply(usableHeight).setScale(6, RoundingMode.HALF_UP);
    }
}
