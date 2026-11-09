package com.stockflow.inventory.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Whether a carton fits a bin, dimension by dimension (kltn-docs 06 BR-01, decided 2026-11-05): its length, width
 * and height must each be at most the bin's length, width and its level's clear height. A carton whose item is
 * {@code rotatable} may be turned on the horizontal - length for width - and fitting either way is enough; its height
 * is always compared with the clear height, since a carton is never laid on its side.
 *
 * <p><b>Which side faces which.</b> The docs write "D × R × C of the carton ≤ width × length of the bin × clear
 * height" (06 BR-01, length x width x height against width x length x height). Read literally that pairs the carton's
 * length with the bin's width. This reads it physically instead: the carton's length against the bin's length, its
 * width against the bin's width, and "turned on the horizontal" swaps the two. For a rotatable carton - the default -
 * both readings give the same answer, since either orientation passes; only a carton that may not be turned can come
 * out differently, and for it the physical pairing is the one a person loading the shelf would apply. The database
 * trigger pairs the axes the same way. Recorded as an open question in the 5-4 PR.</p>
 *
 * <p>The rule of every move of a carton into a bin: putaway (05 BR-04), put-back (07 BR-12) and a move by hand,
 * from {@code OVERFLOW} too (11 BR-10). The database states it again ({@code tg_stock_package_fits_bin}).</p>
 *
 * <p>Pure on purpose, and with plain numbers: the carton's measures are inventory's (its carton unit, docs 01), the
 * bin's are the warehouse map's, and this class needs neither module. Units differ as they do in the data: a carton
 * is measured in centimetres, the map in metres.</p>
 *
 * <p>What is not known is not checked. A carton without a full size cannot be judged and is let through, as a bin
 * on a level without a clear height has no height limit (legacy data, 06 §4.4): refusing them would leave a carton
 * nowhere to go but {@code OVERFLOW} for want of a number, not of space.</p>
 */
public final class CartonFit {

    private static final BigDecimal CM3_PER_M3 = BigDecimal.valueOf(1_000_000);

    private CartonFit() {
    }

    /** A carton as its carton unit declares it, in centimetres; any measure may be unknown. */
    public record Carton(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, boolean rotatable) {

        public static final Carton UNMEASURED = new Carton(null, null, null, true);

        public boolean measured() {
            return lengthCm != null && widthCm != null && heightCm != null;
        }

        /** length x width x height in m³ (06 BR-19: what it takes of a bin); zero when not measured. */
        public BigDecimal volumeM3() {
            if (!measured()) {
                return BigDecimal.ZERO;
            }
            return lengthCm.multiply(widthCm).multiply(heightCm).divide(CM3_PER_M3, 6, RoundingMode.HALF_UP);
        }
    }

    /** The inside of a bin in metres: its footprint and its level's clear height (null on a legacy level). */
    public record Space(BigDecimal widthM, BigDecimal lengthM, BigDecimal usableHeightM) {
    }

    public static boolean fits(Carton carton, Space space) {
        if (!carton.measured() || space == null) {
            return true;
        }
        BigDecimal length = metres(carton.lengthCm());
        BigDecimal width = metres(carton.widthCm());
        if (space.usableHeightM() != null && metres(carton.heightCm()).compareTo(space.usableHeightM()) > 0) {
            return false;
        }
        return within(length, space.lengthM()) && within(width, space.widthM())
                || carton.rotatable() && within(width, space.lengthM()) && within(length, space.widthM());
    }

    private static boolean within(BigDecimal carton, BigDecimal bin) {
        return bin == null || carton.compareTo(bin) <= 0;
    }

    private static BigDecimal metres(BigDecimal centimetres) {
        return centimetres.movePointLeft(2);
    }
}
