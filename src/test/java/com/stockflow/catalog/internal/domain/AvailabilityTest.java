package com.stockflow.catalog.internal.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kltn-docs 13 §4.4 step 4: in stock above the threshold, low at or under it, out at zero or below - counted in whole
 * cartons for a customer's SKU (BR-12, D28).
 */
class AvailabilityTest {

    @ParameterizedTest(name = "ATP {0}, threshold {1} -> {2}")
    @CsvSource({
            "6,  5, IN_STOCK",
            "5,  5, LOW_STOCK",      // the threshold itself is already "low"
            "1,  5, LOW_STOCK",
            "0,  5, OUT_OF_STOCK",
            "-3, 5, OUT_OF_STOCK",   // an over-reservation never reads as stock
            "1,  0, IN_STOCK",       // threshold 0 never warns
            "0,  0, OUT_OF_STOCK",
    })
    void indicatorFollowsTheThreshold(int atp, int threshold, Availability expected) {
        assertThat(Availability.of(atp, threshold)).isEqualTo(expected);
    }

    @Test
    void aNegativeThresholdIsAConfigurationMistake() {
        assertThatThrownBy(() -> Availability.of(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Availability.ofCartons(10, 10, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} pieces, {1} a carton, threshold {2} cartons -> {3}")
    @CsvSource({
            "60,  10, 5, IN_STOCK",      // 6 cartons
            "59,  10, 5, LOW_STOCK",     // 5 whole cartons and 9 pieces: the 9 promise nothing
            "50,  10, 5, LOW_STOCK",
            "10,  10, 5, LOW_STOCK",
            "9,   10, 5, OUT_OF_STOCK",  // less than one carton is nothing a customer can order
            "0,   10, 5, OUT_OF_STOCK",
            "-30, 10, 5, OUT_OF_STOCK",  // an over-reservation never reads as stock
            "10,  10, 0, IN_STOCK",      // threshold 0 never warns
            "7,   1,  5, IN_STOCK",
    })
    void onlyWholeCartonsCount(int pieces, int unitsPerCarton, int thresholdInCartons, Availability expected) {
        assertThat(Availability.ofCartons(pieces, unitsPerCarton, thresholdInCartons)).isEqualTo(expected);
    }

    @Test
    void aCartonHoldsAtLeastOnePiece() {
        assertThatThrownBy(() -> Availability.ofCartons(10, 0, 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
