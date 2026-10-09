package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rectangle every shelf, bin and area occupies. BR-06 (inside the map) and BR-07 (no overlap)
 * are decided by {@link Footprint#fitsWithin} and {@link Footprint#overlaps}, so their edge cases are
 * pinned here rather than in every aggregate that uses them.
 */
class FootprintTest {

    private static Footprint at(String x, String y, String width, String length) {
        return at(x, y, width, length, 0);
    }

    private static Footprint at(String x, String y, String width, String length, int rotation) {
        return new Footprint(new BigDecimal(x), new BigDecimal(y), new BigDecimal(width),
                new BigDecimal(length), rotation);
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest
        @ValueSource(ints = {0, 90, 180, 270})
        @DisplayName("accepts a quarter turn")
        void acceptsQuarterTurns(int rotation) {
            assertThat(at("0", "0", "1", "1", rotation).rotation()).isEqualTo(rotation);
        }

        @ParameterizedTest
        @ValueSource(ints = {45, -90, 360, 1})
        @DisplayName("refuses any other rotation")
        void refusesOtherRotations(int rotation) {
            assertThatThrownBy(() -> at("0", "0", "1", "1", rotation))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("refuses a negative position")
        void refusesNegativePosition() {
            assertThatThrownBy(() -> at("-0.001", "0", "1", "1"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("refuses a zero width or length")
        void refusesEmptySize() {
            assertThatThrownBy(() -> at("0", "0", "0", "1")).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> at("0", "0", "1", "0")).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("refuses more than three decimals rather than rounding them away")
        void refusesFinerThanMillimetres() {
            assertThatThrownBy(() -> at("0.0001", "0", "1", "1"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("refuses a value the NUMERIC(10,3) column cannot hold")
        void refusesValuesTheColumnCannotHold() {
            assertThatThrownBy(() -> at("10000000", "0", "1", "1"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("normalises every measure to scale 3, so equal shapes are equal records")
        void normalisesScale() {
            assertThat(at("1", "2", "3", "4")).isEqualTo(at("1.000", "2.0", "3.00", "4"));
        }
    }

    @Nested
    @DisplayName("rotation")
    class Rotation {

        @Test
        @DisplayName("a quarter turn swaps the effective width and length")
        void quarterTurnSwapsSides() {
            Footprint turned = at("0", "0", "4", "1", 90);

            assertThat(turned.effectiveWidth()).isEqualByComparingTo("1");
            assertThat(turned.effectiveLength()).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("a half turn keeps them")
        void halfTurnKeepsSides() {
            Footprint turned = at("0", "0", "4", "1", 180);

            assertThat(turned.effectiveWidth()).isEqualByComparingTo("4");
            assertThat(turned.effectiveLength()).isEqualByComparingTo("1");
        }
    }

    @Nested
    @DisplayName("overlap (BR-07)")
    class Overlap {

        @Test
        @DisplayName("two rectangles sharing area overlap, both ways round")
        void sharedAreaOverlaps() {
            Footprint a = at("0", "0", "2", "2");
            Footprint b = at("1", "1", "2", "2");

            assertThat(a.overlaps(b)).isTrue();
            assertThat(b.overlaps(a)).isTrue();
        }

        @Test
        @DisplayName("separate rectangles do not overlap")
        void separateDoNotOverlap() {
            assertThat(at("0", "0", "1", "1").overlaps(at("5", "5", "1", "1"))).isFalse();
        }

        @Test
        @DisplayName("touching along an edge is not overlapping - shelves stand back to back")
        void touchingEdgesDoNotOverlap() {
            assertThat(at("0", "0", "2", "1").overlaps(at("2", "0", "2", "1"))).isFalse();
            assertThat(at("0", "0", "2", "1").overlaps(at("0", "1", "2", "1"))).isFalse();
        }

        @Test
        @DisplayName("one rectangle inside another overlaps it")
        void containedOverlaps() {
            assertThat(at("0", "0", "10", "10").overlaps(at("4", "4", "1", "1"))).isTrue();
        }

        @Test
        @DisplayName("a quarter turn changes the answer: 4x1 laid flat misses, turned upright it hits")
        void rotationChangesOverlap() {
            Footprint neighbour = at("0", "2", "1", "1");

            assertThat(at("0", "0", "4", "1", 0).overlaps(neighbour)).isFalse();
            assertThat(at("0", "0", "4", "1", 90).overlaps(neighbour)).isTrue();
        }
    }

    @Nested
    @DisplayName("containment (BR-06)")
    class Containment {

        @Test
        @DisplayName("a footprint flush with the frame's far edges fits")
        void flushWithEdgesFits() {
            assertThat(at("38", "24", "2", "1").fitsWithin(new BigDecimal("40"), new BigDecimal("25")))
                    .isTrue();
        }

        @Test
        @DisplayName("one millimetre past the edge does not fit")
        void pastTheEdgeDoesNotFit() {
            assertThat(at("38.001", "0", "2", "1").fitsWithin(new BigDecimal("40"), new BigDecimal("25")))
                    .isFalse();
        }

        @Test
        @DisplayName("fit is judged on the rotated shape")
        void fitUsesRotatedShape() {
            Footprint upright = at("0", "0", "4", "1", 90);

            assertThat(upright.fitsWithin(new BigDecimal("2"), new BigDecimal("4"))).isTrue();
            assertThat(upright.fitsWithin(new BigDecimal("4"), new BigDecimal("2"))).isFalse();
        }

        @Test
        @DisplayName("contains a footprint lying entirely inside, edges included")
        void containsInside() {
            Footprint frame = at("1", "1", "4", "4");

            assertThat(frame.contains(at("1", "1", "4", "4"))).isTrue();
            assertThat(frame.contains(at("2", "2", "1", "1"))).isTrue();
            assertThat(frame.contains(at("0.999", "1", "1", "1"))).isFalse();
            assertThat(frame.contains(at("4", "4", "1.001", "1"))).isFalse();
        }
    }
}
