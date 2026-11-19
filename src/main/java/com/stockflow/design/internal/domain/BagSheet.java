package com.stockflow.design.internal.domain;

import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.PrintArea;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A paper bag's print file as the workshop prints it: one flat sheet, die-cut, folded into a tube, glued along its
 * glue flap and folded at the bottom (FE request 2026-11-18 §3.2, BE-9). The customer still designs each side on its
 * own {@link PrintSurface}; the storefront lays the four sides onto this sheet when it renders the print file, and the
 * server checks and stamps the file against the same numbers - so this arithmetic is the storefront's, figure for
 * figure, and pinned by the same example in both test suites. A sheet that disagreed by more than half a millimetre
 * would refuse every bag the studio renders.
 *
 * <p>With {@code W} the bag's width, {@code g} its gusset, {@code T} its top fold, {@code G} its glue flap, {@code B}
 * its bottom fold, {@code P = round2(H) - round2(T)} the printed height of a side (each rounded first, as the template
 * keeps them) and {@code b} the largest bleed of the four print areas:</p>
 * <ul>
 *   <li>the trim (the die-cut rectangle) is {@code Wt = G + 2W + 2g} by {@code Ht = T + P + B};</li>
 *   <li>the bleed box is {@code (Wt + 2b) x (Ht + 2b)}, the trim at {@code (b, b)} inside it;</li>
 *   <li>the sides, left to right after the glue flap, are FRONT {@code (G, T)}, RIGHT {@code (G + W, T)}, BACK
 *       {@code (G + W + g, T)} and LEFT {@code (G + 2W + g, T)}, each {@code W x P} or {@code g x P};</li>
 *   <li>the vertical folds are at {@code G, G+W, G+W+g/2, G+W+g, G+2W+g, G+2W+1.5g} (each gusset folds in at its
 *       middle), the horizontal ones at {@code T} and {@code T + P}.</li>
 * </ul>
 *
 * <p>Sheet coordinates are millimetres from the trim's top-left, {@code y} downwards, as the studio's; the boxes
 * {@link #trimBounds()} and {@link #flatWidth()} speak of are from the bleed box's top-left, as
 * {@link PrintSurface}'s are. The print areas stored for a bag stay its four sides: only the print file is one sheet.</p>
 *
 * @param glueFlapMm      {@code G}
 * @param widthMm         {@code W}
 * @param gussetMm        {@code g}
 * @param topFoldMm       {@code T}
 * @param printedHeightMm {@code P}
 * @param bottomFoldMm    {@code B}
 * @param bleedMm         {@code b}
 */
public record BagSheet(double glueFlapMm, double widthMm, double gussetMm, double topFoldMm, double printedHeightMm,
                       double bottomFoldMm, double bleedMm) {

    /** The sides in the order they lie on the sheet after the glue flap. */
    public static final List<String> SIDES = List.of("FRONT", "RIGHT", "BACK", "LEFT");

    /**
     * The sheet of {@code bag} whose sides are {@code areas}.
     *
     * @throws IllegalArgumentException when {@code areas} are not a bag's four sides - a template the product module
     *         never computes for a bag
     */
    public static BagSheet of(BagModel bag, List<PrintArea> areas) {
        Map<String, PrintArea> byCode = areas.stream()
                .collect(Collectors.toMap(PrintArea::code, Function.identity(), (a, b) -> a));
        if (!byCode.keySet().containsAll(SIDES) || byCode.size() != SIDES.size()) {
            throw new IllegalArgumentException("A bag's print areas are FRONT, BACK, LEFT and RIGHT, not "
                    + byCode.keySet());
        }
        double bleed = areas.stream().mapToDouble(PrintArea::bleedMm).max().orElse(0);
        return new BagSheet(bag.glueFlapMm(), bag.widthMm(), bag.gussetMm(), bag.topFoldMm(),
                round2(bag.heightMm()) - round2(bag.topFoldMm()), bag.bottomFoldMm(), bleed);
    }

    /** {@code Wt = G + 2W + 2g}: the die-cut sheet's width. */
    public double trimWidth() {
        return glueFlapMm + 2 * widthMm + 2 * gussetMm;
    }

    /** {@code Ht = T + P + B}: the die-cut sheet's height. */
    public double trimHeight() {
        return topFoldMm + printedHeightMm + bottomFoldMm;
    }

    /** The bleed box's width, {@code Wt + 2b}: the print file page's BleedBox. */
    public double flatWidth() {
        return trimWidth() + 2 * bleedMm;
    }

    /** The bleed box's height, {@code Ht + 2b}. */
    public double flatHeight() {
        return trimHeight() + 2 * bleedMm;
    }

    /** The die-cut rectangle inside the bleed box (the page's TrimBox), from the bleed box's top-left. */
    public PrintSurface.Box trimBounds() {
        return new PrintSurface.Box(bleedMm, bleedMm, trimWidth(), trimHeight());
    }

    /**
     * Where side {@code code} lies on the sheet, from the trim's top-left: its top-left corner at {@code y = T}, as
     * wide as the bag ({@code FRONT}, {@code BACK}) or its gusset ({@code LEFT}, {@code RIGHT}), {@code P} tall.
     *
     * @throws IllegalArgumentException for a code that is not a side of a bag
     */
    public PrintSurface.Box face(String code) {
        double x = switch (code) {
            case "FRONT" -> glueFlapMm;
            case "RIGHT" -> glueFlapMm + widthMm;
            case "BACK" -> glueFlapMm + widthMm + gussetMm;
            case "LEFT" -> glueFlapMm + 2 * widthMm + gussetMm;
            default -> throw new IllegalArgumentException("Not a side of a bag: " + code);
        };
        double width = code.equals("FRONT") || code.equals("BACK") ? widthMm : gussetMm;
        return new PrintSurface.Box(x, topFoldMm, width, printedHeightMm);
    }

    /** The vertical folds, left to right, as distances from the trim's left edge. */
    public List<Double> verticalFolds() {
        double g = glueFlapMm;
        double w = widthMm;
        double s = gussetMm;
        return List.of(g, g + w, g + w + s / 2, g + w + s, g + 2 * w + s, g + 2 * w + 1.5 * s);
    }

    /** The horizontal folds, top to bottom - the top fold's and the bottom's - from the trim's top edge. */
    public List<Double> horizontalFolds() {
        return List.of(topFoldMm, topFoldMm + printedHeightMm);
    }

    /** Millimetres to hundredths, half up, as the template's columns and the studio's {@code round2} keep them. */
    private static double round2(double mm) {
        return Math.round(mm * 100) / 100.0;
    }
}
