package com.stockflow.catalog.internal.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Docs 13 §4.2 step 4: in stock above the threshold, low at or under it, out at zero or below. */
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
    }
}
