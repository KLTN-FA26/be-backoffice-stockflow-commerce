package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The orientation contract shared with the 3D viewer (FE #71): the code names the measured size along z, x and y; the
 * index is the original solver's permutation index. A carton of length 300, width 100, height 200.
 */
class OrientationCodeTest {

    private static final CargoPackage C = CargoPackage.of("c", 300, 100, 200, BigDecimal.ONE, true);

    @ParameterizedTest
    @CsvSource({
        "LWH, 0, 100, 200, 300",
        "HWL, 1, 100, 300, 200",
        "LHW, 2, 200, 100, 300",
        "WHL, 3, 200, 300, 100",
        "HLW, 4, 300, 100, 200",
        "WLH, 5, 300, 200, 100"})
    void codeIndexAndPlacedSizesRoundTrip(String code, int index, int w, int h, int d) {
        Orientation o = Orientation.ofCode(code);
        assertThat(o.index()).isEqualTo(index);
        assertThat(Orientation.ofIndex(index)).isEqualTo(o);
        assertThat(o.extents(C)).containsExactly(w, h, d);
        assertThat(Orientation.of(C, w, h, d)).isEqualTo(o);
        assertThat(o.code()).isEqualTo(code);
        // the code's letters, read as z, x, y, name the measured sizes that land there
        int[] measured = {'L', 'W', 'H'};
        int[] size = {C.lengthMm(), C.widthMm(), C.heightMm()};
        int[] alongZxy = {d, w, h};
        for (int k = 0; k < 3; k++) {
            int letter = code.charAt(k);
            for (int m = 0; m < 3; m++) {
                if (measured[m] == letter) {
                    assertThat(alongZxy[k]).isEqualTo(size[m]);
                }
            }
        }
    }
}
