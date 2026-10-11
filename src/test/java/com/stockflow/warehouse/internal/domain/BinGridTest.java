package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.internal.domain.BinGrid.GeneratedBin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.stockflow.warehouse.internal.domain.BinNamingScheme.ROW_LETTER_COLUMN;
import static com.stockflow.warehouse.internal.domain.BinNamingScheme.SEQUENTIAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BR-14: a shelf face split into rows x columns of equal bins. */
class BinGridTest {

    private static List<GeneratedBin> grid(String width, String length, int rows, int columns,
                                           BinNamingScheme scheme) {
        return BinGrid.generate(new BigDecimal(width), new BigDecimal(length), rows, columns, scheme);
    }

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    @Nested
    @DisplayName("naming")
    class Naming {

        @Test
        @DisplayName("SEQUENTIAL numbers row by row from 01")
        void sequential() {
            assertThat(grid("3", "2", 2, 3, SEQUENTIAL)).extracting(GeneratedBin::code)
                    .containsExactly("01", "02", "03", "04", "05", "06");
        }

        @Test
        @DisplayName("ROW_LETTER_COLUMN gives each row a letter and numbers its columns")
        void rowLetterColumn() {
            assertThat(grid("3", "2", 2, 3, ROW_LETTER_COLUMN)).extracting(GeneratedBin::code)
                    .containsExactly("A01", "A02", "A03", "B01", "B02", "B03");
        }

        @Test
        @DisplayName("pads every number to the same width, never fewer than two digits")
        void padsToTheWidestNumber() {
            List<String> codes = grid("15", "10", 10, 15, SEQUENTIAL).stream()
                    .map(GeneratedBin::code).toList();

            assertThat(codes).hasSize(150).startsWith("001", "002").endsWith("150");
            assertThat(grid("1", "1", 1, 1, SEQUENTIAL)).extracting(GeneratedBin::code)
                    .containsExactly("01");
        }

        @Test
        @DisplayName("every generated code is a valid code part")
        void codesAreValidParts() {
            grid("10", "10", 26, 7, ROW_LETTER_COLUMN).forEach(bin ->
                    assertThat(CodePart.of(bin.code(), CodePart.CODE_MAX_LENGTH).value())
                            .isEqualTo(bin.code()));
        }
    }

    @Nested
    @DisplayName("geometry")
    class Geometry {

        @Test
        @DisplayName("splits the shelf evenly: columns along the width, rows along the length")
        void splitsEvenly() {
            List<GeneratedBin> bins = grid("3", "2", 2, 3, SEQUENTIAL);

            assertThat(bins.get(0).footprint()).isEqualTo(footprint("0", "0", "1", "1"));
            assertThat(bins.get(2).footprint()).isEqualTo(footprint("2", "0", "1", "1"));
            assertThat(bins.get(3).footprint()).isEqualTo(footprint("0", "1", "1", "1"));
        }

        @Test
        @DisplayName("rounds sizes down, so 1.000 over three columns is 0.333 and the last bin stays inside")
        void roundsDown() {
            List<GeneratedBin> bins = grid("1", "1", 1, 3, SEQUENTIAL);

            assertThat(bins).allSatisfy(b ->
                    assertThat(b.footprint().width()).isEqualByComparingTo("0.333"));
            assertThat(bins.get(2).footprint().x()).isEqualByComparingTo("0.666");
            assertThat(bins).allSatisfy(b ->
                    assertThat(b.footprint().fitsWithin(BigDecimal.ONE, BigDecimal.ONE)).isTrue());
        }

        @Test
        @DisplayName("a full 1 x 200 grid fits the shelf and no two bins overlap")
        void largestGridIsConsistent() {
            List<GeneratedBin> bins = grid("10", "1", 1, 200, SEQUENTIAL);

            assertThat(bins).hasSize(200).allSatisfy(b -> {
                assertThat(b.footprint().rotation()).isZero();
                assertThat(b.footprint().fitsWithin(BigDecimal.TEN, BigDecimal.ONE)).isTrue();
            });
            for (int i = 1; i < bins.size(); i++) {
                assertThat(bins.get(i - 1).footprint().overlaps(bins.get(i).footprint())).isFalse();
            }
        }

        private Footprint footprint(String x, String y, String width, String length) {
            return new Footprint(new BigDecimal(x), new BigDecimal(y), new BigDecimal(width),
                    new BigDecimal(length), 0);
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {

        @Test
        @DisplayName("refuses fewer than one row or column")
        void refusesEmptyGrid() {
            assertThat(errorOf(() -> grid("1", "1", 0, 1, SEQUENTIAL))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(errorOf(() -> grid("1", "1", 1, 0, SEQUENTIAL))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("refuses a 27th row when rows are lettered A-Z")
        void refusesRowsPastZ() {
            assertThat(grid("1", "26", 26, 1, ROW_LETTER_COLUMN)).last()
                    .extracting(GeneratedBin::code).isEqualTo("Z01");
            assertThat(errorOf(() -> grid("1", "27", 27, 1, ROW_LETTER_COLUMN)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("refuses more than 200 bins on a level")
        void refusesMoreThan200Bins() {
            assertThat(errorOf(() -> grid("10", "1", 1, 201, SEQUENTIAL)))
                    .isEqualTo(ErrorCode.SHELF_CAPACITY_EXCEEDED);
        }

        @Test
        @DisplayName("refuses a split finer than a millimetre")
        void refusesSubMillimetreBins() {
            assertThatThrownBy(() -> grid("0.001", "1", 1, 2, SEQUENTIAL))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
