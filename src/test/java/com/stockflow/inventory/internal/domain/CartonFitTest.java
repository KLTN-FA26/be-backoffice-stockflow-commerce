package com.stockflow.inventory.internal.domain;

import com.stockflow.inventory.internal.domain.CartonFit.Carton;
import com.stockflow.inventory.internal.domain.CartonFit.Space;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kltn-docs 06 BR-01 (decided 2026-11-05): a carton fits a bin when its length, width and height are each at most the
 * bin's length, width and its level's clear height; a rotatable carton may be turned on the horizontal, length for
 * width, and fitting either way is enough. Cartons in centimetres, bins in metres, as the data has them.
 */
class CartonFitTest {

    private static Carton carton(String length, String width, String height, boolean rotatable) {
        return new Carton(new BigDecimal(length), new BigDecimal(width), new BigDecimal(height), rotatable);
    }

    private static Space bin(String width, String length, String usableHeight) {
        return new Space(new BigDecimal(width), new BigDecimal(length),
                usableHeight == null ? null : new BigDecimal(usableHeight));
    }

    @ParameterizedTest(name = "{0}x{1}x{2} cm, rotatable {3}, into {4} wide x {5} long x {6} clear: {7}")
    @CsvSource({
            // length, width, height (cm), rotatable | bin width, length, clear height (m) | fits
            "50, 40, 30, true,  0.40, 0.50, 0.30, true",    // exactly, straight
            "50, 40, 30, false, 0.40, 0.50, 0.30, true",
            "50, 40, 30, true,  0.50, 0.40, 0.30, true",    // only turned: length along the bin's width
            "50, 40, 30, false, 0.50, 0.40, 0.30, false",   // may not be turned
            "50, 40, 30, true,  0.45, 0.45, 0.30, false",   // too long either way
            "50, 40, 30, true,  1.00, 1.00, 0.299, false",  // too tall: never laid on its side
            "50, 40, 30, true,  0.399, 0.50, 0.30, false",  // a millimetre short
            "50.5, 40, 30, true, 0.40, 0.505, 0.30, true"   // centimetres with a decimal
    })
    void fitsDimensionByDimension(String length, String width, String height, boolean rotatable, String binWidth,
                                  String binLength, String clearHeight, boolean fits) {
        assertThat(CartonFit.fits(carton(length, width, height, rotatable), bin(binWidth, binLength, clearHeight)))
                .isEqualTo(fits);
    }

    @Test
    @DisplayName("a level without a clear height (legacy) does not limit the height")
    void noClearHeight() {
        assertThat(CartonFit.fits(carton("50", "40", "300", false), bin("0.4", "0.5", null))).isTrue();
        assertThat(CartonFit.fits(carton("50", "40", "300", false), bin("0.3", "0.5", null))).isFalse();
    }

    @Test
    @DisplayName("a carton not fully measured is not judged, and takes no volume")
    void unmeasured() {
        Carton partly = new Carton(new BigDecimal("500"), null, new BigDecimal("30"), false);
        assertThat(CartonFit.fits(partly, bin("0.1", "0.1", "0.1"))).isTrue();
        assertThat(partly.volumeM3()).isZero();
        assertThat(CartonFit.fits(Carton.UNMEASURED, bin("0.1", "0.1", "0.1"))).isTrue();
    }

    @Test
    @DisplayName("a carton's volume is length x width x height in m³ (06 BR-19)")
    void volume() {
        assertThat(carton("50", "40", "30", true).volumeM3()).isEqualByComparingTo("0.06");
        assertThat(carton("33.3", "21.7", "12.5", true).volumeM3()).isEqualByComparingTo("0.009033");
    }

    @Test
    @DisplayName("an area (no space) takes any carton")
    void area() {
        assertThat(CartonFit.fits(carton("500", "400", "300", false), null)).isTrue();
    }
}
