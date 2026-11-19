package com.stockflow.product.internal.domain;

import com.stockflow.common.api.FieldError;
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
 * <p>Pure and stateless; every refusal is {@code PRINT_CONFIG_INVALID} with the reason, and names the request field it
 * is about in {@code fieldErrors} (FE request 2026-11-18 BE-12): {@code model.seamMm}, {@code printAreas[1].bleedMm} -
 * the paths of {@code CustomizationTemplateRequest}, a print area by its index in the request - so the back office puts
 * the refusal next to the input that caused it. Each field error carries the English reason as its message and the
 * rule's {@code code} ({@code Range}, {@code OneOf}, {@code NoSafeArea}, ...); a rule about two fields names both.</p>
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
    /** A bag's glue flap is a strip of the sheet, not another side: at most as long as a cup's seam. */
    static final int MAX_GLUE_FLAP_MM = 100;
    /** A rule code: a number outside the range its field allows. */
    private static final String RANGE = "Range";
    /** A rule code: a value outside the set its field allows. */
    private static final String ONE_OF = "OneOf";

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
            throw invalid("minDpi", RANGE, "The minimum resolution must be between 72 and 2400 dpi");
        }
        if (!COLOR_MODES.contains(limits.colorMode())) {
            throw invalid("colorMode", ONE_OF, "The colour mode must be CMYK or RGB");
        }
        if (limits.allowedFileFormats() == null || limits.allowedFileFormats().isEmpty()) {
            throw invalid("allowedFileFormats", "NotEmpty", "A template must allow at least one file type");
        }
        // An immutable list refuses contains(null) with an exception of its own; look element by element. The request's
        // own list, before deduplication, so the index names the entry the client sent.
        for (int i = 0; i < limits.allowedFileFormats().size(); i++) {
            String format = limits.allowedFileFormats().get(i);
            if (format == null) {
                throw invalid("allowedFileFormats[" + i + "]", "NotNull", "A file type cannot be empty");
            }
            if (!ASSET_TYPES.contains(format)) {
                throw invalid("allowedFileFormats[" + i + "]", ONE_OF,
                        "Allowed file types are image/png, image/jpeg and image/webp, not " + format);
            }
        }
        List<String> formats = new ArrayList<>(new java.util.LinkedHashSet<>(limits.allowedFileFormats()));
        if (limits.maxFileSizeMb() < 1 || limits.maxFileSizeMb() > MAX_FILE_SIZE_MB) {
            throw invalid("maxFileSizeMb", RANGE,
                    "The largest upload must be between 1 and " + MAX_FILE_SIZE_MB + " MB");
        }
        if (limits.maxColors() != null && (limits.maxColors() < 1 || limits.maxColors() > 12)) {
            throw invalid("maxColors", RANGE, "The number of ink colours must be between 1 and 12, or unlimited");
        }
        List<String> fonts = limits.allowedFontCodes();
        if (fonts != null) {
            if (fonts.isEmpty()) {
                throw invalid("allowedFontCodes", "NotEmpty", "List at least one font, or allow every font");
            }
            for (int i = 0; i < fonts.size(); i++) {
                String font = fonts.get(i);
                if (font == null || font.isBlank() || font.length() > 64) {
                    throw invalid("allowedFontCodes[" + i + "]", "Size", "A font code must be 1 to 64 characters");
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
        // Where each area is in the request: a refusal names printAreas[i], the entry the client sent.
        Map<String, Integer> indexOf = new java.util.HashMap<>();
        List<AreaSpec> listed = specs == null ? List.of() : specs;
        for (int i = 0; i < listed.size(); i++) {
            AreaSpec spec = listed.get(i);
            String at = "printAreas[" + i + "]";
            if (spec.code() == null || byCode.put(spec.code(), spec) != null) {
                throw invalid(at + ".code", spec.code() == null ? "NotNull" : "Duplicate",
                        "Each print area is listed once, by its code");
            }
            indexOf.put(spec.code(), i);
            if (spec.name() == null || spec.name().isBlank() || spec.name().length() > 255) {
                throw invalid(at + ".name", "Size",
                        "Print area " + spec.code() + " needs a name of at most 255 characters");
            }
            String margins = "Print area " + spec.code() + ": margins are 0 or more, bleed at most 20 mm";
            if (!(spec.safeMarginMm() >= 0) || spec.safeMarginMm() > 500) {
                throw invalid(at + ".safeMarginMm", RANGE, margins);
            }
            if (!(spec.bleedMm() >= 0) || spec.bleedMm() > 20) {
                throw invalid(at + ".bleedMm", RANGE, margins);
            }
            // Checked as stored: the columns keep two decimals, so a value valid only before rounding is not valid.
            byCode.put(spec.code(), new AreaSpec(spec.code(), spec.name(), mm(spec.safeMarginMm()), mm(spec.bleedMm())));
        }
        return switch (model) {
            case CupModel cup -> cupAreas(cup, byCode, indexOf);
            case BagModel bag -> bagAreas(bag, byCode, indexOf);
        };
    }

    private static List<PrintArea> cupAreas(CupModel cup, Map<String, AreaSpec> byCode,
                                            Map<String, Integer> indexOf) {
        requireCup(cup);
        requireCodes(byCode, List.of(CUP_WALL), "A cup has one print area, BODY");
        AreaSpec wall = byCode.get(CUP_WALL);
        String at = "printAreas[" + indexOf.get(CUP_WALL) + "]";
        double slant = CupGeometry.slant(cup);
        double bandHeight = slant - cup.bandTopOffsetMm() - cup.bandBottomOffsetMm();
        PrintArea area;
        if (CupGeometry.isCylindrical(cup)) {
            double width = mm(Math.PI * cup.topDiameterMm() + cup.seamMm());
            requireRoom(wall, at, Math.PI * cup.topDiameterMm(), bandHeight);
            requireRoom(wall, at, width, mm(bandHeight));
            area = new PrintArea(CUP_WALL, wall.name(), 0, 0, width, mm(bandHeight), wall.safeMarginMm(),
                    wall.bleedMm(), new PrintAreaShape.Rect(), "body");
        } else {
            CupGeometry.Sector sector = CupGeometry.sector(cup);
            if (!(sector.sweepRad() + sector.seamRad() < 2 * Math.PI)) {
                throw invalid("model.seamMm", "SeamOverlaps",
                        "The glue flap is too long for this cup: the band would overlap itself");
            }
            if (!(sector.trimInnerMm() - wall.bleedMm() > 0)) {
                throw invalid(at + ".bleedMm", "BleedPastCentre",
                        "Print area BODY: the bleed reaches past the centre of the unrolled band");
            }
            requireRoom(wall, at, sector.sweepRad() * sector.trimInnerMm(), bandHeight);
            // The trimmed band's bounding box, as every area's size is its trim; the bleed is added where it is drawn.
            CupGeometry.Box box = CupGeometry.bleedBox(sector, 0);
            requireRoom(wall, at, mm(box.width()), mm(box.height()));
            area = new PrintArea(CUP_WALL, wall.name(), 0, 0, mm(box.width()), mm(box.height()), wall.safeMarginMm(),
                    wall.bleedMm(), new PrintAreaShape.AnnularSector(sector.trimInnerMm(), sector.trimOuterMm(),
                            sector.sweepRad(), cup.seamMm()), "body");
        }
        return List.of(area);
    }

    private static List<PrintArea> bagAreas(BagModel bag, Map<String, AreaSpec> byCode,
                                            Map<String, Integer> indexOf) {
        requireBag(bag);
        requireCodes(byCode, BAG_SIDES, "A bag has four print areas: FRONT, BACK, LEFT and RIGHT");
        double printable = mm(bag.heightMm() - bag.topFoldMm());
        List<PrintArea> areas = new ArrayList<>(4);
        for (String code : BAG_SIDES) {
            AreaSpec side = byCode.get(code);
            double width = mm(code.equals("FRONT") || code.equals("BACK") ? bag.widthMm() : bag.gussetMm());
            requireRoom(side, "printAreas[" + indexOf.get(code) + "]", width, printable);
            areas.add(new PrintArea(code, side.name(), 0, 0, width, printable, side.safeMarginMm(), side.bleedMm(),
                    new PrintAreaShape.Rect(), code.toLowerCase(java.util.Locale.ROOT)));
        }
        return List.copyOf(areas);
    }

    private static void requireCup(CupModel cup) {
        requirePositive(cup.topDiameterMm(), "model.topDiameterMm", "top diameter");
        requirePositive(cup.bottomDiameterMm(), "model.bottomDiameterMm", "bottom diameter");
        requirePositive(cup.heightMm(), "model.heightMm", "height");
        requireNonNegative(cup.rimMm(), "model.rimMm", "rim");
        requireNonNegative(cup.seamMm(), "model.seamMm", "seam");
        String small = "A cup's diameters and height are at least " + MIN_CUP_DIAMETER_MM + " mm";
        if (cup.topDiameterMm() < MIN_CUP_DIAMETER_MM) {
            throw invalid("model.topDiameterMm", RANGE, small);
        }
        if (cup.bottomDiameterMm() < MIN_CUP_DIAMETER_MM) {
            throw invalid("model.bottomDiameterMm", RANGE, small);
        }
        if (cup.heightMm() < MIN_CUP_DIAMETER_MM) {
            throw invalid("model.heightMm", RANGE, small);
        }
        if (cup.seamMm() > MAX_SEAM_MM) {
            throw invalid("model.seamMm", RANGE, "A cup's glue flap is at most " + MAX_SEAM_MM + " mm");
        }
        requireNonNegative(cup.bandTopOffsetMm(), "model.bandTopOffsetMm", "band top offset");
        requireNonNegative(cup.bandBottomOffsetMm(), "model.bandBottomOffsetMm", "band bottom offset");
        if (cup.bottomDiameterMm() > cup.topDiameterMm()) {
            throw invalid("model.bottomDiameterMm", "BaseWiderThanRim", "A cup's base cannot be wider than its rim");
        }
        if (cup.bandTopOffsetMm() + cup.bandBottomOffsetMm() >= CupGeometry.slant(cup)) {
            throw invalidTogether("NoBand", "The unprinted offsets leave no band to print on", "model.bandTopOffsetMm",
                    "model.bandBottomOffsetMm");
        }
        if (cup.material() == null || !CUP_MATERIALS.contains(cup.material())) {
            throw invalid("model.material", ONE_OF, "A cup's material is paper-white, kraft or pet-clear");
        }
    }

    private static void requireBag(BagModel bag) {
        requirePositive(bag.widthMm(), "model.widthMm", "width");
        requirePositive(bag.gussetMm(), "model.gussetMm", "gusset");
        requirePositive(bag.heightMm(), "model.heightMm", "height");
        requireNonNegative(bag.topFoldMm(), "model.topFoldMm", "top fold");
        if (bag.topFoldMm() >= bag.heightMm()) {
            throw invalid("model.topFoldMm", "TopFoldTooLong", "A bag's top fold must be shorter than the bag");
        }
        if (!(bag.glueFlapMm() >= 0) || bag.glueFlapMm() > MAX_GLUE_FLAP_MM) {
            throw invalid("model.glueFlapMm", RANGE, "A bag's glue flap is 0 to " + MAX_GLUE_FLAP_MM + " mm");
        }
        // The bottom is folded from the gusset's depth: a fold deeper than the gusset would overlap the opposite side.
        if (!(bag.bottomFoldMm() >= 0) || bag.bottomFoldMm() > bag.gussetMm()) {
            throw invalid("model.bottomFoldMm", RANGE, "A bag's bottom fold is 0 mm to its gusset");
        }
        if (bag.handle() == null || !BAG_HANDLES.contains(bag.handle())) {
            throw invalid("model.handle", ONE_OF, "A bag's handle is twisted, flat or none");
        }
        if (bag.material() == null || !BAG_MATERIALS.contains(bag.material())) {
            throw invalid("model.material", ONE_OF, "A bag's material is kraft-brown or kraft-white");
        }
    }

    /** A wrong set of areas is about the list as a whole: the field is {@code printAreas}. */
    private static void requireCodes(Map<String, AreaSpec> byCode, List<String> expected, String message) {
        if (!new HashSet<>(expected).equals(byCode.keySet())) {
            throw invalid("printAreas", "PrintAreaSet", message);
        }
    }

    /**
     * The safe area - the trim less the margin on every side - must keep some room in both directions.
     *
     * @param at the area's request path, {@code printAreas[i]}
     */
    private static void requireRoom(AreaSpec area, String at, double trimWidth, double trimHeight) {
        if (2 * area.safeMarginMm() >= Math.min(trimWidth, trimHeight)) {
            throw invalid(at + ".safeMarginMm", "NoSafeArea",
                    "Print area " + area.code() + ": the safe margin leaves nothing to print on");
        }
    }

    private static void requirePositive(double value, String field, String what) {
        if (!(value > 0) || value > 2000) {
            throw invalid(field, RANGE, "The " + what + " must be more than 0 and at most 2000 mm");
        }
    }

    private static void requireNonNegative(double value, String field, String what) {
        if (!(value >= 0) || value > 2000) {
            throw invalid(field, RANGE, "The " + what + " must be 0 or more and at most 2000 mm");
        }
    }

    /** Millimetres as the print area columns keep them: two decimals. */
    static double mm(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * {@code PRINT_CONFIG_INVALID} for {@code reason}, naming the request field at fault and the rule it broke. Public:
     * the web mapper and the service refuse the parts of a request this class never sees (the model's kind, a missing
     * measurement, the template's code and name) with the same shape.
     *
     * @param field the request path ({@code model.seamMm}, {@code printAreas[0].bleedMm})
     * @param rule  the rule's code, stable for a client to branch on; the English {@code reason} is its message
     */
    public static BusinessException invalid(String field, String rule, String reason) {
        return new BusinessException(ErrorCode.PRINT_CONFIG_INVALID, reason,
                List.of(new FieldError(field, reason, rule)));
    }

    /** The same for a rule about several fields at once: each is named, with the one reason. */
    private static BusinessException invalidTogether(String rule, String reason, String... fields) {
        return new BusinessException(ErrorCode.PRINT_CONFIG_INVALID, reason,
                java.util.Arrays.stream(fields).map(field -> new FieldError(field, reason, rule)).toList());
    }
}
