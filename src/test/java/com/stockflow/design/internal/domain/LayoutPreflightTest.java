package com.stockflow.design.internal.domain;

import com.stockflow.common.json.CanonicalJson;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.ProductModel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The server's preflight (kltn-docs 12 BR-05, plan D6) on the studio's geometry: a bag face, a tapered cup unrolled to
 * a sector, and a straight cup unrolled to a rectangle with its glue flap; every finding blocks.
 */
class LayoutPreflightTest {

    private static final String LOGO = "0190f3a1-0000-7000-8000-000000000001";
    private static final Map<String, LayoutPreflight.Asset> ASSETS = Map.of(LOGO,
            new LayoutPreflight.Asset("image/png", 2048, 1200, 1200));
    private static final CupModel CUP = new CupModel(90, 60, 110, 3, 8, 6, 8, "paper-white");

    private static PrintConfig template(ProductModel model, List<PrintArea> areas, List<String> fonts) {
        return new PrintConfig(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SKU", "T", "Template", 300,
                "CMYK", List.of("image/png", "image/jpeg"), 20, null, fonts, true, model, areas, null, null, 0);
    }

    /** A bag face: trim 200 x 300, bleed 3, safe 5. */
    private static PrintConfig bag() {
        return template(new BagModel(200, 80, 300, 40, "twisted", "kraft-brown"),
                List.of(new PrintArea("FRONT", "Mặt trước", 0, 0, 200, 300, 5, 3, new PrintAreaShape.Rect(), "front")),
                null);
    }

    private static PrintConfig cup(CupModel model) {
        // The surface is computed from the model; the area contributes its code, bleed and safe margin.
        return template(model, List.of(new PrintArea("BODY", "Thân ly", 0, 0, 280, 104, 3, 2,
                new PrintAreaShape.Rect(), "body")), null);
    }

    private static Object layout(String code, String elements) {
        return CanonicalJson.parse("{\"schemaVersion\":1,\"templateCode\":\"T\",\"sku\":\"SKU\",\"surfaces\":{\""
                + code + "\":{\"background\":{\"kind\":\"none\"},\"elements\":[" + elements + "]}}}");
    }

    private static String image(String id, double u, double v, double width, double height, double scale) {
        return "{\"id\":\"" + id + "\",\"type\":\"image\",\"assetId\":\"" + LOGO + "\",\"widthMm\":" + width
                + ",\"heightMm\":" + height + ",\"placement\":{\"u\":" + u + ",\"v\":" + v
                + ",\"rotationDeg\":0,\"scale\":" + scale + "}}";
    }

    private static List<String> rules(List<LayoutPreflight.Issue> issues) {
        return issues.stream().map(LayoutPreflight.Issue::rule).toList();
    }

    @Test
    void aDesignWithNothingToPrintIsRefused() {
        assertThat(rules(LayoutPreflight.check(layout("FRONT", ""), bag(), ASSETS))).containsExactly("EMPTY_DESIGN");
        // A background is something to print; a hidden element is not.
        Object coloured = CanonicalJson.parse("{\"surfaces\":{\"FRONT\":{\"background\":{\"kind\":\"color\","
                + "\"color\":\"#FF0000\"},\"elements\":[]}}}");
        assertThat(LayoutPreflight.check(coloured, bag(), ASSETS)).isEmpty();
        String hidden = image("a", 100, 150, 20, 20, 1).replace("\"type\"", "\"hidden\":true,\"type\"");
        assertThat(rules(LayoutPreflight.check(layout("FRONT", hidden), bag(), ASSETS))).containsExactly("EMPTY_DESIGN");
    }

    @Test
    void onABagFaceAnImageMustStayInsideTheSafeLine() {
        assertThat(LayoutPreflight.check(layout("FRONT", image("in", 100, 150, 40, 40, 1)), bag(), ASSETS)).isEmpty();

        // u, v from the trim's top-left: the safe line is 5 mm in.
        var crossing = LayoutPreflight.check(layout("FRONT", image("edge", 8, 150, 20, 20, 1)), bag(), ASSETS);
        assertThat(crossing).singleElement().satisfies(issue -> {
            assertThat(issue.rule()).isEqualTo("CROSSES_SAFE_AREA");
            assertThat(issue.surfaceCode()).isEqualTo("FRONT");
            assertThat(issue.elementId()).isEqualTo("edge");
        });
        assertThat(rules(LayoutPreflight.check(layout("FRONT", image("past", 0, 150, 20, 20, 1)), bag(), ASSETS)))
                .containsExactly("OUTSIDE_PRINT_AREA");
        // Entirely in the bleed strip, beyond the trim: it would be cut away.
        assertThat(rules(LayoutPreflight.check(layout("FRONT", image("strip", -1.6, 150, 2, 2, 1)), bag(), ASSETS)))
                .containsExactly("OUTSIDE_PRINT_AREA");
        // Turned 45 degrees, a square near the edge reaches further than its unturned box.
        String turned = image("turned", 17, 150, 20, 20, 1).replace("\"rotationDeg\":0", "\"rotationDeg\":45");
        assertThat(LayoutPreflight.check(layout("FRONT", image("square", 17, 150, 20, 20, 1)), bag(), ASSETS)).isEmpty();
        assertThat(rules(LayoutPreflight.check(layout("FRONT", turned), bag(), ASSETS)))
                .containsExactly("CROSSES_SAFE_AREA");
    }

    @Test
    void anImageMustReachTheMinimumResolutionWherePlaced() {
        // 1200 px over 100 mm is 304.8 DPI; scaled up by 1.1 it is 277 - under the template's 300.
        assertThat(LayoutPreflight.check(layout("FRONT", image("ok", 100, 150, 100, 100, 1)), bag(), ASSETS))
                .isEmpty();
        var low = LayoutPreflight.check(layout("FRONT", image("big", 100, 150, 100, 100, 1.1)), bag(), ASSETS);
        assertThat(low).singleElement().satisfies(issue -> {
            assertThat(issue.rule()).isEqualTo("DPI_BELOW_MIN");
            assertThat(issue.message()).contains("277", "300");
        });
    }

    @Test
    void anImageMustBeOneOfTheDesignsOfATypeAndSizeTheTemplateTakes() {
        String unknown = image("x", 100, 150, 20, 20, 1).replace(LOGO, UUID.randomUUID().toString());
        assertThat(rules(LayoutPreflight.check(layout("FRONT", unknown), bag(), ASSETS)))
                .containsExactly("ASSET_INVALID");
        var webp = Map.of(LOGO, new LayoutPreflight.Asset("image/webp", 2048, 1200, 1200));
        assertThat(rules(LayoutPreflight.check(layout("FRONT", image("x", 100, 150, 20, 20, 1)), bag(), webp)))
                .containsExactly("ASSET_INVALID");
        var huge = Map.of(LOGO, new LayoutPreflight.Asset("image/png", 21L * 1024 * 1024, 1200, 1200));
        assertThat(rules(LayoutPreflight.check(layout("FRONT", image("x", 100, 150, 20, 20, 1)), bag(), huge)))
                .containsExactly("ASSET_INVALID");
    }

    @Test
    void textMustBeInAnAllowedFontAndAnchoredInsideTheSafeLine() {
        String text = "{\"id\":\"t\",\"type\":\"text\",\"content\":\"Cà phê A\",\"fontCode\":\"COMIC\","
                + "\"placement\":{\"u\":100,\"v\":150,\"rotationDeg\":0,\"scale\":1}}";
        PrintConfig anyFont = bag();
        PrintConfig roboto = template(anyFont.model(), anyFont.printAreas(), List.of("ROBOTO"));

        assertThat(LayoutPreflight.check(layout("FRONT", text), anyFont, ASSETS)).isEmpty();
        assertThat(rules(LayoutPreflight.check(layout("FRONT", text), roboto, ASSETS)))
                .containsExactly("FONT_NOT_ALLOWED");
        // Only the anchor is checked - the server has no font metrics - but that much it can.
        assertThat(rules(LayoutPreflight.check(layout("FRONT", text.replace("\"u\":100", "\"u\":2")), anyFont,
                ASSETS))).containsExactly("CROSSES_SAFE_AREA");
        assertThat(rules(LayoutPreflight.check(layout("FRONT", text.replace("\"u\":100", "\"u\":-500")), anyFont,
                ASSETS))).containsExactly("OUTSIDE_PRINT_AREA");
    }

    /** Review of 2F-5: a background - an image or drawing covering the whole trim - reaches into the bleed by design. */
    @Test
    void aBackgroundCoveringTheWholeTrimMayReachIntoTheBleed() {
        // The bag face's bleed box is 206 x 306 mm: an image of exactly that size, centred.
        assertThat(LayoutPreflight.check(layout("FRONT", image("photo", 100, 150, 206, 306, 1)), bag(),
                Map.of(LOGO, new LayoutPreflight.Asset("image/png", 2048, 2500, 3700)))).isEmpty();
        // Larger than the bleed box, it is cut off: still refused.
        assertThat(rules(LayoutPreflight.check(layout("FRONT", image("photo", 100, 150, 220, 320, 1)), bag(),
                Map.of(LOGO, new LayoutPreflight.Asset("image/png", 2048, 2700, 3900)))))
                .containsExactly("OUTSIDE_PRINT_AREA");

        PrintSurface wall = PrintSurface.of(CUP, cup(CUP).printAreas().get(0));
        double uMid = (wall.uFrom() + wall.uTo()) / 2;
        double vMid = (wall.vFrom() + wall.vTo()) / 2;
        double wrapWidth = (wall.uTo() - wall.uFrom()) * 1.004;
        String wrapAround = image("wrap", uMid, vMid, wrapWidth, wall.vTo() - wall.vFrom() + 2, 1)
                .replace("\"type\"", "\"wrap\":true,\"type\"");
        assertThat(LayoutPreflight.check(layout("BODY", wrapAround), cup(CUP),
                Map.of(LOGO, new LayoutPreflight.Asset("image/png", 2048, 6000, 2000)))).isEmpty();
    }

    @Test
    void aDrawingIsCheckedByItsInkAndAnElementThatCannotBeReadIsRefused() {
        String drawing = "{\"id\":\"d\",\"type\":\"drawing\",\"strokes\":[{\"mode\":\"draw\",\"sizeMm\":2,"
                + "\"points\":[[-10,0,0.5],[10,0,0.5]]}],\"placement\":{\"u\":100,\"v\":150,\"rotationDeg\":0,"
                + "\"scale\":1}}";
        assertThat(LayoutPreflight.check(layout("FRONT", drawing), bag(), ASSETS)).isEmpty();
        // The ink reaches 0.75 x 2 mm past its points: drawn 6 mm from the edge, it crosses the 5 mm safe line.
        assertThat(rules(LayoutPreflight.check(layout("FRONT", drawing.replace("\"u\":100", "\"u\":16")), bag(),
                ASSETS))).containsExactly("CROSSES_SAFE_AREA");

        String noPlacement = "{\"id\":\"p\",\"type\":\"image\",\"assetId\":\"" + LOGO + "\",\"widthMm\":10,"
                + "\"heightMm\":10}";
        assertThat(LayoutPreflight.check(layout("FRONT", noPlacement), bag(), ASSETS)).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.rule()).isEqualTo("ELEMENT_INVALID");
                    assertThat(issue.elementId()).isEqualTo("p");
                });
    }

    @Test
    void onATaperedCupTheWallIsASectorAndTheGlueFlapIsOutsideTheSafeArea() {
        PrintSurface surface = PrintSurface.of(CUP, cup(CUP).printAreas().get(0));
        assertThat(surface.sector()).isTrue();
        double uMid = (surface.uFrom() + surface.uTo()) / 2;
        double vMid = (surface.vFrom() + surface.vTo()) / 2;
        // The wall's numbers, as the studio computes them (cupSector): 6 mm below the rim to 8 mm above the base.
        assertThat(surface.vFrom()).isCloseTo(6, within(1e-9));
        assertThat(surface.vTo() - surface.vFrom()).isCloseTo(Math.hypot(110, 15) - 14, within(1e-9));

        assertThat(LayoutPreflight.check(layout("BODY", image("front", uMid, vMid, 30, 30, 1)), cup(CUP), ASSETS))
                .isEmpty();
        // On the flap at the end of the wall: in the trim, but hidden under the overlap.
        double onFlap = surface.uTo() - 2;
        assertThat(rules(LayoutPreflight.check(layout("BODY", image("flap", onFlap, vMid, 2, 10, 1)), cup(CUP),
                ASSETS))).containsExactly("CROSSES_SAFE_AREA");
        // Between the trim's top edge and the safe line, 3 mm below it.
        assertThat(rules(LayoutPreflight.check(layout("BODY", image("rim", uMid, surface.vFrom() + 3.5, 30, 4, 1)),
                cup(CUP), ASSETS))).containsExactly("CROSSES_SAFE_AREA");
    }

    @Test
    void aWideLogoWrappedRoundTheCupFollowsTheArcWhereARigidOneLeavesIt() {
        PrintSurface surface = PrintSurface.of(CUP, cup(CUP).printAreas().get(0));
        double uMid = (surface.uFrom() + surface.uTo()) / 2;
        // Its top edge half a millimetre inside the safe line under the rim. Wrapped, the top follows the rim's arc;
        // rigid, a 150 mm straight top edge has its corners well above it.
        double v = surface.vFrom() + 3.5 + 4;
        String rigid = image("rigid", uMid, v, 150, 8, 1);
        String wrapped = rigid.replace("\"type\"", "\"wrap\":true,\"type\"");
        var banner = Map.of(LOGO, new LayoutPreflight.Asset("image/png", 2048, 4000, 400));

        assertThat(rules(LayoutPreflight.check(layout("BODY", rigid), cup(CUP), banner)))
                .containsAnyOf("OUTSIDE_PRINT_AREA", "CROSSES_SAFE_AREA");
        assertThat(LayoutPreflight.check(layout("BODY", wrapped), cup(CUP), banner)).isEmpty();
    }

    @Test
    void aStraightCupUnrollsToARectangleWithItsFlapAtTheRightEnd() {
        CupModel straight = new CupModel(80, 80, 100, 3, 8, 5, 5, "paper-white");
        PrintSurface surface = PrintSurface.of(straight, cup(straight).printAreas().get(0));
        assertThat(surface.sector()).isFalse();
        assertThat(surface.uTo()).isCloseTo(Math.PI * 80 + 8, within(1e-9));

        assertThat(LayoutPreflight.check(layout("BODY", image("mid", 120, 50, 30, 30, 1)), cup(straight), ASSETS))
                .isEmpty();
        assertThat(rules(LayoutPreflight.check(layout("BODY", image("flap", surface.uTo() - 3, 50, 4, 4, 1)),
                cup(straight), ASSETS))).containsExactly("CROSSES_SAFE_AREA");
    }
}
