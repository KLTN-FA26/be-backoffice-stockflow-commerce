package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The print areas a template's model computes, against the numbers the storefront's mock templates were computed with
 * (its {@code design-templates.ts}: the 12 oz cup's sector 230.04 / 327.05 / 0.84894 and box 279.67 x 120.85), and the
 * models and margins a Sales Manager may not save.
 */
class PrintConfigRulesTest {

    private static final CupModel CUP_12OZ = new CupModel(90, 60, 110, 3, 8, 6, 8, "paper-white");
    private static final CupModel CUP_8OZ = new CupModel(80, 56, 92, 3, 8, 6, 8, "kraft");
    private static final BagModel BAG_M = new BagModel(260, 120, 320, 40, "twisted", "kraft-brown");
    private static final List<PrintConfigRules.AreaSpec> WALL =
            List.of(new PrintConfigRules.AreaSpec("BODY", "Thân ly", 3, 2));
    private static final List<PrintConfigRules.AreaSpec> SIDES = List.of(
            new PrintConfigRules.AreaSpec("FRONT", "Mặt trước", 5, 3),
            new PrintConfigRules.AreaSpec("BACK", "Mặt sau", 5, 3),
            new PrintConfigRules.AreaSpec("LEFT", "Hông trái", 5, 3),
            new PrintConfigRules.AreaSpec("RIGHT", "Hông phải", 5, 3));

    @Test
    void aConicalCupUnrollsToTheSectorTheStorefrontComputes() {
        PrintArea wall = PrintConfigRules.areas(CUP_12OZ, WALL).get(0);

        assertThat(wall.code()).isEqualTo("BODY");
        assertThat(wall.meshPart()).isEqualTo("body");
        var sector = (PrintAreaShape.AnnularSector) wall.shape();
        assertThat(sector.innerRadiusMm()).isCloseTo(230.04, within(0.005));
        assertThat(sector.outerRadiusMm()).isCloseTo(327.05, within(0.005));
        assertThat(sector.sweepRad()).isCloseTo(0.84894, within(0.000005));
        assertThat(sector.seamMm()).isEqualTo(8);
        assertThat(wall.widthMm()).isCloseTo(279.67, within(0.005));
        assertThat(wall.heightMm()).isCloseTo(120.85, within(0.005));

        var small = (PrintAreaShape.AnnularSector) PrintConfigRules.areas(CUP_8OZ, WALL).get(0).shape();
        assertThat(small.innerRadiusMm()).isCloseTo(224.49, within(0.005));
        assertThat(small.outerRadiusMm()).isCloseTo(303.26, within(0.005));
        assertThat(small.sweepRad()).isCloseTo(0.81266, within(0.000005));
    }

    @Test
    void aStraightWalledCupUnrollsToARectangleOfItsCircumferenceAndBand() {
        var cylinder = new CupModel(80, 79.5, 100, 3, 8, 5, 5, "pet-clear");

        PrintArea wall = PrintConfigRules.areas(cylinder, WALL).get(0);

        assertThat(wall.shape()).isInstanceOf(PrintAreaShape.Rect.class);
        assertThat(wall.widthMm()).isCloseTo(Math.PI * 80 + 8, within(0.005));
        assertThat(wall.heightMm()).isCloseTo(Math.hypot(100, 0.25) - 10, within(0.005));
    }

    @Test
    void aBagHasFourSidesAsTallAsTheBagLessItsTopFold() {
        List<PrintArea> sides = PrintConfigRules.areas(BAG_M, SIDES);

        assertThat(sides).extracting(PrintArea::code).containsExactly("FRONT", "BACK", "LEFT", "RIGHT");
        assertThat(sides).extracting(PrintArea::widthMm).containsExactly(260d, 260d, 120d, 120d);
        assertThat(sides).extracting(PrintArea::heightMm).containsOnly(280d);
        assertThat(sides).extracting(PrintArea::meshPart).containsExactly("front", "back", "left", "right");
        assertThat(sides).allSatisfy(side -> assertThat(side.shape()).isInstanceOf(PrintAreaShape.Rect.class));
    }

    @Test
    void anAreaTheModelDoesNotHaveOrAMissingOneIsRefused() {
        refused(() -> PrintConfigRules.areas(CUP_12OZ, SIDES), "one print area, BODY");
        refused(() -> PrintConfigRules.areas(BAG_M, SIDES.subList(0, 3)), "four print areas");
        refused(() -> PrintConfigRules.areas(BAG_M, List.of(SIDES.get(0), SIDES.get(0), SIDES.get(1), SIDES.get(2))),
                "listed once");
    }

    @Test
    void impossibleSizesAreRefused() {
        refused(() -> PrintConfigRules.areas(new CupModel(60, 90, 110, 3, 8, 6, 8, "kraft"), WALL),
                "base cannot be wider");
        refused(() -> PrintConfigRules.areas(new CupModel(90, 60, 10, 3, 8, 10, 10, "kraft"), WALL), "no band");
        refused(() -> PrintConfigRules.areas(new CupModel(90, 60, 110, 3, 8, 6, 8, "glass"), WALL), "material");
        refused(() -> PrintConfigRules.areas(new CupModel(0, 60, 110, 3, 8, 6, 8, "kraft"), WALL), "top diameter");
        refused(() -> PrintConfigRules.areas(new CupModel(Double.NaN, 60, 110, 3, 8, 6, 8, "kraft"), WALL),
                "top diameter");
        refused(() -> PrintConfigRules.areas(new BagModel(260, 120, 40, 40, "twisted", "kraft-brown"), SIDES),
                "top fold");
        refused(() -> PrintConfigRules.areas(new BagModel(260, 120, 320, 40, "rope", "kraft-brown"), SIDES),
                "handle");
    }

    @Test
    void aConeTooSteepForItsFlapOrBleedIsRefusedBeforeAnyGeometryIsComputed() {
        refused(() -> PrintConfigRules.areas(new CupModel(80, 1e-6, 110, 3, 8, 6, 0, "kraft"), WALL), "at least 10 mm");
        refused(() -> PrintConfigRules.areas(new CupModel(90, 60, 110, 3, 150, 6, 8, "kraft"), WALL), "glue flap");
        // Rim 200 mm, base 10 mm, 10 mm tall: the band unrolls to almost a full turn around an apex 5 mm away.
        refused(() -> PrintConfigRules.areas(new CupModel(200, 10, 10, 0, 100, 0, 0, "kraft"), WALL), "overlap");
        refused(() -> PrintConfigRules.areas(new CupModel(200, 10, 10, 0, 0, 0, 0, "kraft"),
                List.of(new PrintConfigRules.AreaSpec("BODY", "x", 0, 6))), "bleed reaches past");
    }

    @Test
    void marginsAreCheckedAsTheColumnsStoreThem() {
        // A 10.004 mm gusset is stored as 10.00: a 5 mm margin each side then leaves nothing, as the CHECK would say.
        refused(() -> PrintConfigRules.areas(new BagModel(260, 10.004, 320, 40, "flat", "kraft-white"), List.of(
                new PrintConfigRules.AreaSpec("FRONT", "a", 5, 3),
                new PrintConfigRules.AreaSpec("BACK", "b", 5, 3),
                new PrintConfigRules.AreaSpec("LEFT", "c", 5, 3),
                new PrintConfigRules.AreaSpec("RIGHT", "d", 5, 3))), "safe margin");

        PrintArea side = PrintConfigRules.areas(BAG_M, List.of(
                new PrintConfigRules.AreaSpec("FRONT", "a", 5.004, 3.005),
                new PrintConfigRules.AreaSpec("BACK", "b", 5, 3),
                new PrintConfigRules.AreaSpec("LEFT", "c", 5, 3),
                new PrintConfigRules.AreaSpec("RIGHT", "d", 5, 3))).get(0);
        assertThat(side.safeMarginMm()).isEqualTo(5.0);
        assertThat(side.bleedMm()).isEqualTo(3.01);
    }

    @Test
    void marginsThatLeaveNothingToPrintOnAreRefused() {
        refused(() -> PrintConfigRules.areas(BAG_M, List.of(
                new PrintConfigRules.AreaSpec("FRONT", "a", 5, 3),
                new PrintConfigRules.AreaSpec("BACK", "b", 5, 3),
                new PrintConfigRules.AreaSpec("LEFT", "c", 60, 3),
                new PrintConfigRules.AreaSpec("RIGHT", "d", 5, 3))), "safe margin");
        refused(() -> PrintConfigRules.areas(CUP_12OZ, List.of(new PrintConfigRules.AreaSpec("BODY", "x", 3, 25))),
                "bleed");
        refused(() -> PrintConfigRules.areas(CUP_12OZ, List.of(new PrintConfigRules.AreaSpec("BODY", " ", 3, 2))),
                "name");
    }

    @Test
    void limitsAreCheckedAndFormatsDeduplicated() {
        var limits = PrintConfigRules.requireValid(new PrintConfigRules.Limits(300, "CMYK",
                List.of("image/png", "image/jpeg", "image/png"), 20, 4, List.of("ROBOTO", "ROBOTO")));

        assertThat(limits.allowedFileFormats()).containsExactly("image/png", "image/jpeg");
        assertThat(limits.allowedFontCodes()).containsExactly("ROBOTO");

        refused(() -> PrintConfigRules.requireValid(new PrintConfigRules.Limits(300, "CMYK", List.of("PNG"), 20, null,
                null)), "image/png");
        refused(() -> PrintConfigRules.requireValid(new PrintConfigRules.Limits(300, "CMYK",
                List.of("image/svg+xml"), 20, null, null)), "image/png");
        refused(() -> PrintConfigRules.requireValid(new PrintConfigRules.Limits(300, "LAB", List.of("image/png"), 20,
                null, null)), "colour mode");
        refused(() -> PrintConfigRules.requireValid(new PrintConfigRules.Limits(300, "CMYK", List.of("image/png"), 51,
                null, null)), "largest upload");
        refused(() -> PrintConfigRules.requireValid(new PrintConfigRules.Limits(10, "CMYK", List.of("image/png"), 20,
                null, null)), "resolution");
        refused(() -> PrintConfigRules.requireValid(new PrintConfigRules.Limits(300, "CMYK", List.of("image/png"), 20,
                null, List.of())), "font");
        refused(() -> PrintConfigRules.requireValid(new PrintConfigRules.Limits(300, "CMYK",
                java.util.Arrays.asList("image/png", null), 20, null, null)), "empty");
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(reason)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.PRINT_CONFIG_INVALID);
    }
}
