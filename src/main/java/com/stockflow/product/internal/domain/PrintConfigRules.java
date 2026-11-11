package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.ProductModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What makes a customization template valid (kltn-docs 01 §4.10, BR-21), and the print areas it computes.
 *
 * <p>A Sales Manager supplies the product's sizes and, per area, only its name, safe margin and bleed. Where each area
 * lies and what shape it has follow from the model: a cup has one wall, {@code BODY}, an annular sector (a rectangle if
 * the wall is straight); a bag has four sides, {@code FRONT} and {@code BACK} as wide as the bag, {@code LEFT} and
 * {@code RIGHT} as deep as its gusset, all as tall as the bag less its top fold. Accepting a shape from the client would
 * let the stored outline disagree with the geometry the browser and the preflight compute from the same model.</p>
 *
 * <p>Pure and stateless; every refusal is {@code PRINT_CONFIG_INVALID} with the reason.</p>
 */
public final class PrintConfigRules {

    /** The MIME types a design asset can be ({@code FileCategory.DESIGN_ASSET}); a template narrows them. */
    public static final Set<String> ASSET_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    public static final Set<String> COLOR_MODES = Set.of("CMYK", "RGB");
    public static final Set<String> CUP_MATERIALS = Set.of("paper-white", "kraft", "pet-clear");
    public static final Set<String> BAG_MATERIALS = Set.of("kraft-brown", "kraft-white");
    public static final Set<String> BAG_HANDLES = Set.of("twisted", "flat", "none");
    /** A design asset is at most a design render's size; no template may allow more. */
    public static final int MAX_FILE_SIZE_MB = 50;
    /** The print resolution a template defaults to (open question B34). */
    public static final int DEFAULT_MIN_DPI = 300;

    /** Below this a cone's apex is so close that the unrolled band's geometry stops meaning anything. */
    static final int MIN_CUP_DIAMETER_MM = 10;
    static final int MAX_SEAM_MM = 100;

    private static final List<String> BAG_SIDES = List.of("FRONT", "BACK", "LEFT", "RIGHT");
    private static final String CUP_WALL = "BODY";

    private PrintConfigRules() {
    }

    /** What a Sales Manager supplies for one area; the rest is computed. */
    public record AreaSpec(String code, String name, double safeMarginMm, double bleedMm) {
    }

    /** The template's own limits. */
    public record Limits(int minDpi, String colorMode, List<String> allowedFileFormats, int maxFileSizeMb,
                         Integer maxColors, List<String> allowedFontCodes) {
    }

    /** {@code limits} checked; the file formats deduplicated in the order given. */
    public static Limits requireValid(Limits limits) {
        if (limits.minDpi() < 72 || limits.minDpi() > 2400) {
            throw invalid("The minimum resolution must be between 72 and 2400 dpi");
        }
        if (!COLOR_MODES.contains(limits.colorMode())) {
            throw invalid("The colour mode must be CMYK or RGB");
        }
        if (limits.allowedFileFormats() == null || limits.allowedFileFormats().isEmpty()) {
            throw invalid("A template must allow at least one file type");
        }
        // An immutable list refuses contains(null) with an exception of its own; look element by element.
        if (limits.allowedFileFormats().stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("A file type cannot be empty");
        }
        List<String> formats = new ArrayList<>(new java.util.LinkedHashSet<>(limits.allowedFileFormats()));
        for (String format : formats) {
            if (!ASSET_TYPES.contains(format)) {
                throw invalid("Allowed file types are image/png, image/jpeg and image/webp, not " + format);
            }
        }
        if (limits.maxFileSizeMb() < 1 || limits.maxFileSizeMb() > MAX_FILE_SIZE_MB) {
            throw invalid("The largest upload must be between 1 and " + MAX_FILE_SIZE_MB + " MB");
        }
        if (limits.maxColors() != null && (limits.maxColors() < 1 || limits.maxColors() > 12)) {
            throw invalid("The number of ink colours must be between 1 and 12, or unlimited");
        }
        List<String> fonts = limits.allowedFontCodes();
        if (fonts != null) {
            if (fonts.isEmpty()) {
                throw invalid("List at least one font, or allow every font");
            }
            for (String font : fonts) {
                if (font == null || font.isBlank() || font.length() > 64) {
                    throw invalid("A font code must be 1 to 64 characters");
                }
            }
            fonts = List.copyOf(new java.util.LinkedHashSet<>(fonts));
        }
        return new Limits(limits.minDpi(), limits.colorMode(), List.copyOf(formats), limits.maxFileSizeMb(),
                limits.maxColors(), fonts);
    }

    /**
     * The print areas of {@code model}, one per {@code specs} entry, in the order the model defines them.
     *
     * @throws BusinessException {@code PRINT_CONFIG_INVALID} for a model with impossible sizes, an area the model does
     *         not have, a missing or repeated one, or margins that leave nothing to print on
     */
    public static List<PrintArea> areas(ProductModel model, List<AreaSpec> specs) {
        Map<String, AreaSpec> byCode = new LinkedHashMap<>();
        for (AreaSpec spec : specs == null ? List.<AreaSpec>of() : specs) {
            if (spec.code() == null || byCode.put(spec.code(), spec) != null) {
                throw invalid("Each print area is listed once, by its code");
            }
            if (spec.name() == null || spec.name().isBlank() || spec.name().length() > 255) {
                throw invalid("Print area " + spec.code() + " needs a name of at most 255 characters");
            }
            if (!(spec.safeMarginMm() >= 0) || !(spec.bleedMm() >= 0) || spec.bleedMm() > 20
                    || spec.safeMarginMm() > 500) {
                throw invalid("Print area " + spec.code() + ": margins are 0 or more, bleed at most 20 mm");
            }
            // Checked as stored: the columns keep two decimals, so a value valid only before rounding is not valid.
            byCode.put(spec.code(), new AreaSpec(spec.code(), spec.name(), mm(spec.safeMarginMm()), mm(spec.bleedMm())));
        }
        return switch (model) {
            case CupModel cup -> cupAreas(cup, byCode);
            case BagModel bag -> bagAreas(bag, byCode);
        };
    }

    private static List<PrintArea> cupAreas(CupModel cup, Map<String, AreaSpec> byCode) {
        requireCup(cup);
        requireCodes(byCode, List.of(CUP_WALL), "A cup has one print area, BODY");
        AreaSpec wall = byCode.get(CUP_WALL);
        double slant = CupGeometry.slant(cup);
        double bandHeight = slant - cup.bandTopOffsetMm() - cup.bandBottomOffsetMm();
        PrintArea area;
        if (CupGeometry.isCylindrical(cup)) {
            double width = mm(Math.PI * cup.topDiameterMm() + cup.seamMm());
            requireRoom(wall, Math.PI * cup.topDiameterMm(), bandHeight);
            requireRoom(wall, width, mm(bandHeight));
            area = new PrintArea(CUP_WALL, wall.name(), 0, 0, width, mm(bandHeight), wall.safeMarginMm(),
                    wall.bleedMm(), new PrintAreaShape.Rect(), "body");
        } else {
            CupGeometry.Sector sector = CupGeometry.sector(cup);
            if (!(sector.sweepRad() + sector.seamRad() < 2 * Math.PI)) {
                throw invalid("The glue flap is too long for this cup: the band would overlap itself");
            }
            if (!(sector.trimInnerMm() - wall.bleedMm() > 0)) {
                throw invalid("Print area BODY: the bleed reaches past the centre of the unrolled band");
            }
            requireRoom(wall, sector.sweepRad() * sector.trimInnerMm(), bandHeight);
            // The trimmed band's bounding box, as every area's size is its trim; the bleed is added where it is drawn.
            CupGeometry.Box box = CupGeometry.bleedBox(sector, 0);
            requireRoom(wall, mm(box.width()), mm(box.height()));
            area = new PrintArea(CUP_WALL, wall.name(), 0, 0, mm(box.width()), mm(box.height()), wall.safeMarginMm(),
                    wall.bleedMm(), new PrintAreaShape.AnnularSector(sector.trimInnerMm(), sector.trimOuterMm(),
                            sector.sweepRad(), cup.seamMm()), "body");
        }
        return List.of(area);
    }

    private static List<PrintArea> bagAreas(BagModel bag, Map<String, AreaSpec> byCode) {
        requireBag(bag);
        requireCodes(byCode, BAG_SIDES, "A bag has four print areas: FRONT, BACK, LEFT and RIGHT");
        double printable = mm(bag.heightMm() - bag.topFoldMm());
        List<PrintArea> areas = new ArrayList<>(4);
        for (String code : BAG_SIDES) {
            AreaSpec side = byCode.get(code);
            double width = mm(code.equals("FRONT") || code.equals("BACK") ? bag.widthMm() : bag.gussetMm());
            requireRoom(side, width, printable);
            areas.add(new PrintArea(code, side.name(), 0, 0, width, printable, side.safeMarginMm(), side.bleedMm(),
                    new PrintAreaShape.Rect(), code.toLowerCase(java.util.Locale.ROOT)));
        }
        return List.copyOf(areas);
    }

    private static void requireCup(CupModel cup) {
        requirePositive(cup.topDiameterMm(), "top diameter");
        requirePositive(cup.bottomDiameterMm(), "bottom diameter");
        requirePositive(cup.heightMm(), "height");
        requireNonNegative(cup.rimMm(), "rim");
        requireNonNegative(cup.seamMm(), "seam");
        if (cup.topDiameterMm() < MIN_CUP_DIAMETER_MM || cup.bottomDiameterMm() < MIN_CUP_DIAMETER_MM
                || cup.heightMm() < MIN_CUP_DIAMETER_MM) {
            throw invalid("A cup's diameters and height are at least " + MIN_CUP_DIAMETER_MM + " mm");
        }
        if (cup.seamMm() > MAX_SEAM_MM) {
            throw invalid("A cup's glue flap is at most " + MAX_SEAM_MM + " mm");
        }
        requireNonNegative(cup.bandTopOffsetMm(), "band top offset");
        requireNonNegative(cup.bandBottomOffsetMm(), "band bottom offset");
        if (cup.bottomDiameterMm() > cup.topDiameterMm()) {
            throw invalid("A cup's base cannot be wider than its rim");
        }
        if (cup.bandTopOffsetMm() + cup.bandBottomOffsetMm() >= CupGeometry.slant(cup)) {
            throw invalid("The unprinted offsets leave no band to print on");
        }
        if (cup.material() == null || !CUP_MATERIALS.contains(cup.material())) {
            throw invalid("A cup's material is paper-white, kraft or pet-clear");
        }
    }

    private static void requireBag(BagModel bag) {
        requirePositive(bag.widthMm(), "width");
        requirePositive(bag.gussetMm(), "gusset");
        requirePositive(bag.heightMm(), "height");
        requireNonNegative(bag.topFoldMm(), "top fold");
        if (bag.topFoldMm() >= bag.heightMm()) {
            throw invalid("A bag's top fold must be shorter than the bag");
        }
        if (bag.handle() == null || !BAG_HANDLES.contains(bag.handle())) {
            throw invalid("A bag's handle is twisted, flat or none");
        }
        if (bag.material() == null || !BAG_MATERIALS.contains(bag.material())) {
            throw invalid("A bag's material is kraft-brown or kraft-white");
        }
    }

    private static void requireCodes(Map<String, AreaSpec> byCode, List<String> expected, String message) {
        if (!new HashSet<>(expected).equals(byCode.keySet())) {
            throw invalid(message);
        }
    }

    /** The safe area - the trim less the margin on every side - must keep some room in both directions. */
    private static void requireRoom(AreaSpec area, double trimWidth, double trimHeight) {
        if (2 * area.safeMarginMm() >= Math.min(trimWidth, trimHeight)) {
            throw invalid("Print area " + area.code() + ": the safe margin leaves nothing to print on");
        }
    }

    private static void requirePositive(double value, String what) {
        if (!(value > 0) || value > 2000) {
            throw invalid("The " + what + " must be more than 0 and at most 2000 mm");
        }
    }

    private static void requireNonNegative(double value, String what) {
        if (!(value >= 0) || value > 2000) {
            throw invalid("The " + what + " must be 0 or more and at most 2000 mm");
        }
    }

    /** Millimetres as the print area columns keep them: two decimals. */
    static double mm(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.PRINT_CONFIG_INVALID, reason);
    }
}
