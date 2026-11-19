package com.stockflow.design.internal.domain;

import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A bag's flat sheet against the example of FE request 2026-11-18 §3.2 - W 260, g 120, H 320, T 40, G 20, B 72, b 3
 * gives P 280, Wt 780, Ht 392 and a 786 x 398 mm bleed box - which the storefront pins in its own test. The literals
 * are the point: if either side changes the arithmetic, one of the two suites fails, before the server starts refusing
 * every bag the studio renders. Never replace them with values computed by {@link BagSheet}.
 */
class BagSheetTest {

    private static final BagModel BAG = new BagModel(260, 120, 320, 40, 20, 72, "twisted", "kraft-brown");

    private static List<PrintArea> sides(double bleedFront, double bleedOthers) {
        return List.of(
                new PrintArea("FRONT", "Mặt trước", 0, 0, 260, 280, 5, bleedFront, new PrintAreaShape.Rect(), "front"),
                new PrintArea("BACK", "Mặt sau", 0, 0, 260, 280, 5, bleedOthers, new PrintAreaShape.Rect(), "back"),
                new PrintArea("LEFT", "Hông trái", 0, 0, 120, 280, 5, bleedOthers, new PrintAreaShape.Rect(), "left"),
                new PrintArea("RIGHT", "Hông phải", 0, 0, 120, 280, 5, bleedOthers, new PrintAreaShape.Rect(),
                        "right"));
    }

    @Test
    void theRequestsExampleSheet() {
        BagSheet sheet = BagSheet.of(BAG, sides(3, 3));

        assertThat(sheet.printedHeightMm()).isEqualTo(280);
        assertThat(sheet.trimWidth()).isEqualTo(780);
        assertThat(sheet.trimHeight()).isEqualTo(392);
        assertThat(sheet.bleedMm()).isEqualTo(3);
        assertThat(sheet.flatWidth()).isEqualTo(786);
        assertThat(sheet.flatHeight()).isEqualTo(398);
        assertThat(sheet.trimBounds()).isEqualTo(new PrintSurface.Box(3, 3, 780, 392));
    }

    @Test
    void theSidesLieAfterTheGlueFlapFrontRightBackLeft() {
        BagSheet sheet = BagSheet.of(BAG, sides(3, 3));

        assertThat(sheet.face("FRONT")).isEqualTo(new PrintSurface.Box(20, 40, 260, 280));
        assertThat(sheet.face("RIGHT")).isEqualTo(new PrintSurface.Box(280, 40, 120, 280));
        assertThat(sheet.face("BACK")).isEqualTo(new PrintSurface.Box(400, 40, 260, 280));
        assertThat(sheet.face("LEFT")).isEqualTo(new PrintSurface.Box(660, 40, 120, 280));
        assertThatThrownBy(() -> sheet.face("BOTTOM")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFoldsAreAtTheSidesEdgesAndTheGussetsMiddles() {
        BagSheet sheet = BagSheet.of(BAG, sides(3, 3));

        assertThat(sheet.verticalFolds()).containsExactly(20d, 280d, 340d, 400d, 660d, 720d);
        assertThat(sheet.horizontalFolds()).containsExactly(40d, 320d);
    }

    @Test
    void theBleedIsTheLargestOfTheFourSidesAndThePrintedHeightIsRoundedFirst() {
        assertThat(BagSheet.of(BAG, sides(2, 4)).flatWidth()).isEqualTo(788);

        // round2(320.004) - round2(39.996) = 320.00 - 40.00, not round2(280.008) = 280.01.
        BagSheet rounded = BagSheet.of(new BagModel(260, 120, 320.004, 39.996, 20, 72, "flat", "kraft-white"),
                sides(3, 3));
        assertThat(rounded.printedHeightMm()).isEqualTo(280);
    }

    @Test
    void aBagWithoutGlueFlapOrBottomFoldIsDrawnWithTheDefaults() {
        BagSheet sheet = BagSheet.of(new BagModel(260, 120, 320, 40, "twisted", "kraft-brown"), sides(3, 3));

        assertThat(sheet.glueFlapMm()).isEqualTo(20);
        assertThat(sheet.bottomFoldMm()).isEqualTo(72);
        assertThat(sheet.trimHeight()).isEqualTo(392);
    }

    @Test
    void onlyABagsFourSidesMakeASheet() {
        assertThatThrownBy(() -> BagSheet.of(BAG, sides(3, 3).subList(0, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
