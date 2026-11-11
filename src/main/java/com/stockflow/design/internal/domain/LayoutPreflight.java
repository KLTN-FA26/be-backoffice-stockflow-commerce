package com.stockflow.design.internal.domain;

import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's preflight of a design's layout (kltn-docs 12 BR-05; Design Studio plan D6), run when the customer
 * submits it: every finding blocks - there is no warning level (B18) - and each names its rule and, where it has one,
 * the print area and the element, so the studio can select what is at fault.
 *
 * <ul>
 *   <li>{@code EMPTY_DESIGN} - no surface has a background or a visible element;</li>
 *   <li>{@code ELEMENT_INVALID} - an element the rules below cannot read (a placement, a size, a stroke missing or not
 *       a number);</li>
 *   <li>{@code OUTSIDE_PRINT_AREA} - an image or a drawing reaches past the bleed, or covers none of the trim; text
 *       anchored outside the trim;</li>
 *   <li>{@code CROSSES_SAFE_AREA} - an image or a drawing reaches outside the safe line, where the cut or, on a cup,
 *       the glue flap takes it - unless it covers the whole trim: that is a background, which reaches into the bleed
 *       by design (BR-05 keeps text and logos inside the safe area, and everything inside the print area and its
 *       bleed); text anchored outside the safe line;</li>
 *   <li>{@code DPI_BELOW_MIN} - an image placed so large that it falls under the template's minimum resolution,
 *       counted from the pixel size the server measured, not the one the browser sent;</li>
 *   <li>{@code ASSET_INVALID} - an image that is not one of this design's, or no longer of a type or size the
 *       template takes;</li>
 *   <li>{@code FONT_NOT_ALLOWED} - text in a font the template does not allow.</li>
 * </ul>
 *
 * <p>The geometry is the studio's own ({@code geometry/element.ts}, {@code preflight/rules/area.ts}), ported so that an
 * element is checked where the customer saw it. What it cannot check: the extent of text. Text is laid out by the
 * browser with its fonts; without their metrics the server cannot know how far a line reaches, so only the point a text
 * element is anchored at is checked, and its extent is left to the studio's own preflight and the Sales Manager's eye.
 * Hidden elements are not printed and are not checked.</p>
 *
 * <p>Each finding carries, besides its English message, the numbers behind it ({@code values}: the DPI reached and the
 * minimum), so the studio can word it in the customer's language.</p>
 *
 * <p>Works on the plain values {@code CanonicalJson.parse} returns.</p>
 */
public final class LayoutPreflight {

    /** Outline points are tested this far inside the outline, so an element laid exactly on a boundary is not "over". */
    private static final double EDGE_TOLERANCE_MM = 0.001;
    /** Segments each straight edge is cut into when sampled: even, so every edge's midpoint is tested. */
    private static final int EDGE_SEGMENTS = 10;
    /** Points sampled along each of the top and bottom edges of an element wrapped round a cup. */
    private static final int WRAP_OUTLINE_SAMPLES = 16;
    private static final double MM_PER_INCH = 25.4;

    private LayoutPreflight() {
    }

    /** What the server knows of an uploaded image: its stored type and size, and its measured size in pixels. */
    public record Asset(String contentType, long sizeBytes, int widthPx, int heightPx) {
    }

    /**
     * One finding. {@code surfaceCode} and {@code elementId} are null for a finding about the whole design;
     * {@code values} holds the numbers behind it, empty when there are none.
     */
    public record Issue(String rule, String surfaceCode, String elementId, String message, Map<String, Object> values) {
        public Issue {
            values = Map.copyOf(values);
        }

        public Issue(String rule, String surfaceCode, String elementId, String message) {
            this(rule, surfaceCode, elementId, message, Map.of());
        }
    }

    /** Every finding of {@code document} drawn on {@code template}, in print-area and z order; empty when it passes. */
    public static List<Issue> check(Object document, PrintConfig template, Map<String, Asset> assets) {
        Map<?, ?> surfaces = document instanceof Map<?, ?> root && root.get("surfaces") instanceof Map<?, ?> s
                ? s : Map.of();
        List<Issue> issues = new ArrayList<>();
        boolean content = false;
        for (PrintArea area : template.printAreas()) {
            if (!(surfaces.get(area.code()) instanceof Map<?, ?> surface)) {
                continue;
            }
            if (surface.get("background") instanceof Map<?, ?> background && !"none".equals(background.get("kind"))) {
                content = true;
            }
            PrintSurface flat = PrintSurface.of(template.model(), area);
            List<?> elements = surface.get("elements") instanceof List<?> list ? list : List.of();
            for (Object item : elements) {
                if (!(item instanceof Map<?, ?> element) || Boolean.TRUE.equals(element.get("hidden"))) {
                    continue;
                }
                content = true;
                issues.addAll(elementIssues(area.code(), flat, element, template, assets));
            }
        }
        if (!content) {
            issues.add(0, new Issue("EMPTY_DESIGN", null, null, "The design has nothing to print"));
        }
        return issues;
    }

    private static List<Issue> elementIssues(String code, PrintSurface surface, Map<?, ?> element,
                                             PrintConfig template, Map<String, Asset> assets) {
        String id = element.get("id") instanceof String text ? text : null;
        String type = element.get("type") instanceof String text ? text : "";
        List<Issue> issues = new ArrayList<>();
        try {
            Placement placement = Placement.of(element.get("placement"));
            switch (type) {
                case "image" -> {
                    double widthMm = positive(element.get("widthMm"));
                    double heightMm = positive(element.get("heightMm"));
                    Asset asset = element.get("assetId") instanceof String assetId ? assets.get(assetId) : null;
                    if (asset == null || !template.allowedFileFormats().contains(asset.contentType())
                            || asset.sizeBytes() > template.maxFileSizeMb() * 1024L * 1024L) {
                        issues.add(new Issue("ASSET_INVALID", code, id,
                                "The image is not one of this design's, or not of a type or size the template takes"));
                    } else {
                        double dpi = Math.min(effectiveDpi(asset.widthPx(), widthMm * placement.scale()),
                                effectiveDpi(asset.heightPx(), heightMm * placement.scale()));
                        if (dpi < template.minDpi()) {
                            issues.add(new Issue("DPI_BELOW_MIN", code, id, "The image reaches only "
                                    + Math.round(dpi) + " DPI where it is placed; the template needs "
                                    + template.minDpi(), Map.of("dpi", Math.round(dpi), "minDpi", template.minDpi())));
                        }
                    }
                    Box box = new Box(-widthMm / 2, -heightMm / 2, widthMm, heightMm);
                    Flip flip = new Flip(Boolean.TRUE.equals(element.get("flipX")),
                            Boolean.TRUE.equals(element.get("flipY")));
                    areaIssue(code, id, surface, placement, box, flip, Boolean.TRUE.equals(element.get("wrap")))
                            .ifPresent(issues::add);
                }
                case "drawing" -> areaIssue(code, id, surface, placement, strokeBox(element.get("strokes")),
                        new Flip(false, false), false).ifPresent(issues::add);
                case "text" -> {
                    Object font = element.get("fontCode");
                    List<String> allowed = template.allowedFontCodes();
                    if (!(font instanceof String fontCode)) {
                        throw new IllegalArgumentException("fontCode");
                    }
                    if (allowed != null && !allowed.isEmpty() && !allowed.contains(fontCode)) {
                        issues.add(new Issue("FONT_NOT_ALLOWED", code, id, "The template does not allow this font"));
                    }
                    anchorIssue(code, id, surface, placement).ifPresent(issues::add);
                }
                default -> throw new IllegalArgumentException("type");
            }
        } catch (IllegalArgumentException unreadable) {
            return List.of(new Issue("ELEMENT_INVALID", code, id, "The element cannot be read: " + unreadable.getMessage()));
        }
        return issues;
    }

    // ---- geometry: the studio's, in the same order of operations ----

    /** An element's placement: where its centre sits on the surface, its turn in degrees, its scale. */
    private record Placement(double u, double v, double rotationDeg, double scale) {
        static Placement of(Object value) {
            if (!(value instanceof Map<?, ?> placement)) {
                throw new IllegalArgumentException("placement");
            }
            return new Placement(number(placement.get("u")), number(placement.get("v")),
                    number(placement.get("rotationDeg")), positive(placement.get("scale")));
        }
    }

    /** An element's box in its own frame, before scale. */
    private record Box(double x, double y, double width, double height) {
    }

    private record Flip(boolean x, boolean y) {
    }

    /**
     * {@code OUTSIDE_PRINT_AREA} when the element reaches past the bleed or covers none of the trim; otherwise
     * {@code CROSSES_SAFE_AREA} when any of it is outside the safe line.
     */
    private static java.util.Optional<Issue> areaIssue(String code, String id, PrintSurface surface,
                                                       Placement placement, Box box, Flip flip, boolean wrap) {
        boolean wrapped = wrap && surface.sector();
        List<PrintSurface.Point> outline = pulledIn(outline(surface, placement, box, flip, wrapped));
        // On a sector the inner arc is concave: the middle of a straight edge leaves the area before its corners do.
        List<PrintSurface.Point> points = surface.sector() && !wrapped ? alongEdges(outline) : outline;
        boolean pastBleed = points.stream().anyMatch(p -> !surface.contains(p, PrintSurface.Region.BLEED));
        if (pastBleed || !overlapsTrim(surface, outline)) {
            return java.util.Optional.of(new Issue("OUTSIDE_PRINT_AREA", code, id,
                    "The element lies outside the print area and would be cut off"));
        }
        // A background covers all of the trim and reaches into the bleed on purpose, so the cut leaves no white edge.
        if (surface.trimSamples().stream().allMatch(p -> inPolygon(p, outline))) {
            return java.util.Optional.empty();
        }
        if (points.stream().anyMatch(p -> !surface.contains(p, PrintSurface.Region.SAFE))) {
            return java.util.Optional.of(new Issue("CROSSES_SAFE_AREA", code, id,
                    "The element crosses the safe line and may be cut or hidden by the seam"));
        }
        return java.util.Optional.empty();
    }

    /**
     * Where a text element is anchored: inside the trim, and inside the safe line. How far the text then reaches is
     * the studio's to check - it has the fonts.
     */
    private static java.util.Optional<Issue> anchorIssue(String code, String id, PrintSurface surface,
                                                         Placement placement) {
        PrintSurface.Placed anchor = surface.toFlat(placement.u(), placement.v());
        PrintSurface.Point point = new PrintSurface.Point(anchor.x(), anchor.y());
        if (!surface.contains(point, PrintSurface.Region.TRIM)) {
            return java.util.Optional.of(new Issue("OUTSIDE_PRINT_AREA", code, id,
                    "The text is anchored outside the print area"));
        }
        if (!surface.contains(point, PrintSurface.Region.SAFE)) {
            return java.util.Optional.of(new Issue("CROSSES_SAFE_AREA", code, id,
                    "The text is anchored outside the safe line"));
        }
        return java.util.Optional.empty();
    }

    /**
     * Whether the element covers any of the trim: an edge runs through it, or the element encloses it. A full-bleed
     * background has every corner in the bleed strip yet covers the whole trim, so corners alone are not enough.
     */
    private static boolean overlapsTrim(PrintSurface surface, List<PrintSurface.Point> outline) {
        PrintSurface.Placed centre = surface.toFlat((surface.uFrom() + surface.uTo()) / 2,
                (surface.vFrom() + surface.vTo()) / 2);
        return alongEdges(outline).stream().anyMatch(p -> surface.contains(p, PrintSurface.Region.TRIM))
                || inPolygon(new PrintSurface.Point(centre.x(), centre.y()), outline);
    }

    /** The four corners through the element's matrix, or, wrapped round a cup, its top and bottom edges as strips. */
    private static List<PrintSurface.Point> outline(PrintSurface surface, Placement placement, Box box, Flip flip,
                                                    boolean wrapped) {
        double right = box.x() + box.width();
        double bottom = box.y() + box.height();
        List<PrintSurface.Point> points = new ArrayList<>();
        if (!wrapped) {
            PrintSurface.Placed at = surface.toFlat(placement.u(), placement.v());
            double[] m = multiply(translate(at.x(), at.y()), multiply(
                    rotate(at.uprightRad() + placement.rotationDeg() * Math.PI / 180),
                    scale(placement.scale() * (flip.x() ? -1 : 1), placement.scale() * (flip.y() ? -1 : 1))));
            for (double[] corner : new double[][] {{box.x(), box.y()}, {right, box.y()}, {right, bottom},
                    {box.x(), bottom}}) {
                points.add(apply(m, corner[0], corner[1]));
            }
            return points;
        }
        for (double y : new double[] {box.y(), bottom}) {
            List<PrintSurface.Point> edge = new ArrayList<>();
            for (int i = 0; i < WRAP_OUTLINE_SAMPLES; i++) {
                double xc = box.x() + box.width() * i / (WRAP_OUTLINE_SAMPLES - 1);
                edge.add(apply(wrapStrip(placement, surface, xc), xc, y));
            }
            if (y == bottom) {
                java.util.Collections.reverse(edge);
            }
            points.addAll(edge);
        }
        return points;
    }

    /**
     * The strip of a wrapped element whose source column is centred at local {@code xc}: placed at the arc offset of
     * that column and turned to its own upright, so the strips follow the circumference. Rotation is ignored.
     */
    private static double[] wrapStrip(Placement placement, PrintSurface surface, double xc) {
        PrintSurface.Placed at = surface.toFlat(arcOffsetU(surface, placement, xc), placement.v());
        double s = placement.scale();
        return multiply(translate(at.x(), at.y()), multiply(rotate(at.uprightRad()),
                multiply(scale(s, s), translate(-xc, 0))));
    }

    /** Surface {@code u} of the point {@code localX} mm right of a wrapped element's centre: same arc length per mm. */
    private static double arcOffsetU(PrintSurface surface, Placement placement, double localX) {
        double u = placement.u();
        double v = placement.v();
        PrintSurface.Placed a = surface.toFlat(u, v);
        PrintSurface.Placed b = surface.toFlat(u + 1, v);
        double dPhi = b.uprightRad() - a.uprightRad();
        double chord = Math.hypot(b.x() - a.x(), b.y() - a.y());
        double perFlatMm = dPhi == 0 || chord == 0 ? 1 : 2 * Math.sin(dPhi / 2) / (dPhi * chord);
        return u + localX * placement.scale() * perFlatMm;
    }

    /**
     * A drawing's box: the bounds of its stroke points grown by how far the brush reaches, {@code 0.75 x size} at full
     * pressure plus a hair for the round cap - the studio's {@code strokeReachMm}.
     */
    private static Box strokeBox(Object value) {
        if (!(value instanceof List<?> strokes)) {
            throw new IllegalArgumentException("strokes");
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Object item : strokes) {
            if (!(item instanceof Map<?, ?> stroke) || !(stroke.get("points") instanceof List<?> points)) {
                throw new IllegalArgumentException("stroke");
            }
            double reach = 0.75 * positive(stroke.get("sizeMm")) + 0.01;
            for (Object point : points) {
                if (!(point instanceof List<?> xy) || xy.size() < 2) {
                    throw new IllegalArgumentException("stroke point");
                }
                double x = number(xy.get(0));
                double y = number(xy.get(1));
                minX = Math.min(minX, x - reach);
                minY = Math.min(minY, y - reach);
                maxX = Math.max(maxX, x + reach);
                maxY = Math.max(maxY, y + reach);
            }
        }
        return minX == Double.POSITIVE_INFINITY ? new Box(0, 0, 0, 0)
                : new Box(minX, minY, maxX - minX, maxY - minY);
    }

    /** Every point moved {@value #EDGE_TOLERANCE_MM} mm towards the outline's centre. */
    private static List<PrintSurface.Point> pulledIn(List<PrintSurface.Point> outline) {
        double cx = outline.stream().mapToDouble(PrintSurface.Point::x).sum() / outline.size();
        double cy = outline.stream().mapToDouble(PrintSurface.Point::y).sum() / outline.size();
        List<PrintSurface.Point> pulled = new ArrayList<>(outline.size());
        for (PrintSurface.Point p : outline) {
            double distance = Math.hypot(cx - p.x(), cy - p.y());
            if (distance <= EDGE_TOLERANCE_MM) {
                pulled.add(p);
            } else {
                double k = EDGE_TOLERANCE_MM / distance;
                pulled.add(new PrintSurface.Point(p.x() + (cx - p.x()) * k, p.y() + (cy - p.y()) * k));
            }
        }
        return pulled;
    }

    /** Each vertex of a closed outline and evenly spaced points on the edge that starts there. */
    private static List<PrintSurface.Point> alongEdges(List<PrintSurface.Point> outline) {
        List<PrintSurface.Point> points = new ArrayList<>(outline.size() * EDGE_SEGMENTS);
        for (int i = 0; i < outline.size(); i++) {
            PrintSurface.Point a = outline.get(i);
            PrintSurface.Point b = outline.get((i + 1) % outline.size());
            for (int k = 0; k < EDGE_SEGMENTS; k++) {
                points.add(new PrintSurface.Point(a.x() + (b.x() - a.x()) * k / EDGE_SEGMENTS,
                        a.y() + (b.y() - a.y()) * k / EDGE_SEGMENTS));
            }
        }
        return points;
    }

    /** Even-odd ray casting; a polygon needs three vertices. */
    private static boolean inPolygon(PrintSurface.Point p, List<PrintSurface.Point> polygon) {
        if (polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        PrintSurface.Point previous = polygon.get(polygon.size() - 1);
        for (PrintSurface.Point current : polygon) {
            boolean crosses = current.y() > p.y() != previous.y() > p.y();
            if (crosses && p.x() < (previous.x() - current.x()) * (p.y() - current.y())
                    / (previous.y() - current.y()) + current.x()) {
                inside = !inside;
            }
            previous = current;
        }
        return inside;
    }

    /** The resolution an image reaches once placed: pixels over inches. */
    private static double effectiveDpi(int naturalPx, double placedMm) {
        return naturalPx / (placedMm / MM_PER_INCH);
    }

    // ---- 2D affine matrices, canvas setTransform(a, b, c, d, e, f) order ----

    private static double[] multiply(double[] m1, double[] m2) {
        return new double[] {
                m1[0] * m2[0] + m1[2] * m2[1],
                m1[1] * m2[0] + m1[3] * m2[1],
                m1[0] * m2[2] + m1[2] * m2[3],
                m1[1] * m2[2] + m1[3] * m2[3],
                m1[0] * m2[4] + m1[2] * m2[5] + m1[4],
                m1[1] * m2[4] + m1[3] * m2[5] + m1[5]};
    }

    private static double[] translate(double x, double y) {
        return new double[] {1, 0, 0, 1, x, y};
    }

    private static double[] rotate(double rad) {
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[] {cos, sin, -sin, cos, 0, 0};
    }

    private static double[] scale(double sx, double sy) {
        return new double[] {sx, 0, 0, sy, 0, 0};
    }

    private static PrintSurface.Point apply(double[] m, double x, double y) {
        return new PrintSurface.Point(m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]);
    }

    // ---- reading the layout ----

    private static double number(Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("a number is missing");
    }

    private static double positive(Object value) {
        double number = number(value);
        if (number <= 0) {
            throw new IllegalArgumentException("a size is not positive");
        }
        return number;
    }

    /** The issues as plain maps for an API response, leaving out what an issue does not have. */
    public static List<Map<String, Object>> asMaps(List<Issue> issues) {
        List<Map<String, Object>> maps = new ArrayList<>(issues.size());
        for (Issue issue : issues) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rule", issue.rule());
            if (issue.surfaceCode() != null) {
                map.put("surfaceCode", issue.surfaceCode());
            }
            if (issue.elementId() != null) {
                map.put("elementId", issue.elementId());
            }
            map.put("message", issue.message());
            if (!issue.values().isEmpty()) {
                map.put("values", issue.values());
            }
            maps.add(map);
        }
        return maps;
    }
}
