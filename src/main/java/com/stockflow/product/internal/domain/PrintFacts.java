package com.stockflow.product.internal.domain;

import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.ProductModel;
import com.stockflow.product.api.TemplateModel3d;

import java.util.List;

/**
 * What a customer's exported files are drawn on: the facts of a template whose change moves its print version
 * ({@code PrintConfig#printVersion}, product owner 2026-11-20, decision 1). Two templates with equal facts give the same
 * files from the same layout, so a design drawn on one is still current on the other.
 *
 * <p>The list follows what reads a template when files are made and checked: the studio draws the flat print areas
 * and the 3D preview from the model, the areas and the {@code .glb} with its mesh map; the preflight
 * ({@code LayoutPreflight}) checks placement against each area's trim, safe margin and bleed, every image against the
 * file formats, the largest upload and the minimum resolution, and every text against the fonts; the print file is
 * stamped with the area's boxes; the production instruction prints the colour mode; and the snapshot freezes the colour
 * count with the rest. Left out on purpose: the template's name and an area's name (labels), whether the template is
 * active (who may start a design, not what is printed), and the reference die-line, which only sits beside the design
 * for comparison and goes into no exported file.</p>
 *
 * <p>Pure: no Spring, no JPA. {@link #next} holds the print version's one rule, stated again by
 * {@code ck_customization_templates_print_version}.</p>
 */
public record PrintFacts(int minDpi, String colorMode, List<String> allowedFileFormats, int maxFileSizeMb,
                         Integer maxColors, List<String> allowedFontCodes, ProductModel model, List<Area> areas,
                         String modelFileChecksum, List<TemplateModel3d.Surface> surfaces) {

    /** A print area as the files see it: everything but its name. */
    public record Area(String code, double xMm, double yMm, double widthMm, double heightMm, double safeMarginMm,
                       double bleedMm, PrintAreaShape shape, String meshPart) {
    }

    public static PrintFacts of(PrintConfig config) {
        return new PrintFacts(config.minDpi(), config.colorMode(), config.allowedFileFormats(),
                config.maxFileSizeMb(), config.maxColors(), config.allowedFontCodes(), config.model(),
                config.printAreas().stream().map(PrintFacts::area).toList(), config.model3d().fileChecksum(),
                config.model3d().surfaces());
    }

    private static Area area(PrintArea a) {
        return new Area(a.code(), a.xMm(), a.yMm(), a.widthMm(), a.heightMm(), a.safeMarginMm(), a.bleedMm(),
                a.shape(), a.meshPart());
    }

    /** The print version after {@code current}; a print version is never negative. */
    public static long next(long current) {
        if (current < 0) {
            throw new IllegalStateException("A print version is never negative, got " + current);
        }
        return Math.addExact(current, 1);
    }
}
