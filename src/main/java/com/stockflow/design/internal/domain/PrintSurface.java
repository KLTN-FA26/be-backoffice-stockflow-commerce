package com.stockflow.design.internal.domain;

import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.ProductModel;

/**
 * One print area unrolled flat, as the storefront's studio unrolls it ({@code surfaces/*.ts}): the same geometry the
 * customer laid the design out on, so the server's preflight sees an element where the customer saw it. Flat
 * coordinates are millimetres from the top-left of the bleed box; {@code u} runs across the print area and {@code v}
 * down from its top.
 *
 * <p>Regions nest: bleed contains trim contains safe. On a cup the glue flap is part of the trim but not of the safe
 * area - it is hidden under the overlap once the cup is assembled.</p>
 */
public sealed interface PrintSurface permits PrintSurface.Rect, PrintSurface.Sector {

    /** Nested print regions. */
    enum Region { BLEED, TRIM, SAFE }

    /** A point on the flat surface, in millimetres. */
    record Point(double x, double y) {
    }

    /** A flat point and the direction that reads as "up" on the product there, in radians. */
    record Placed(double x, double y, double uprightRad) {
    }

    /** An axis-aligned box on the flat surface, in millimetres from the bleed box's top-left. */
    record Box(double x, double y, double width, double height) {
    }

    /** The bleed box's size: the page the studio renders the print area on. */
    double flatWidth();

    double flatHeight();

    /** The trim's bounding box: what is left once the bleed is cut away (the PDF's TrimBox). */
    Box trimBounds();

    /** Whether this is a cone's annular sector rather than a rectangle. */
    boolean sector();

    double uFrom();

    double uTo();

    double vFrom();

    double vTo();

    /** Surface coordinates to the flat point. */
    Placed toFlat(double u, double v);

    boolean contains(Point p, Region region);

    /**
     * Points just inside the trim's outline, around all of it: an element whose outline encloses every one covers the
     * whole trim - a background, which may reach into the bleed.
     */
    java.util.List<Point> trimSamples();

    /** How far inside the trim's outline its samples lie, so a boundary hit is never a coin flip. */
    double SAMPLE_INSET_MM = 0.01;

    /**
     * The surface of {@code area} of a product of {@code model}: a cup's wall (a sector, or a rectangle when the wall is
     * all but straight), or a bag's face.
     */
    static PrintSurface of(ProductModel model, PrintArea area) {
        return switch (model) {
            case CupModel cup -> cup(cup, area);
            case BagModel bag -> new Rect(area.widthMm(), area.heightMm(), area.bleedMm(), area.safeMarginMm(), 0, 0);
        };
    }

    /** Below this top/bottom radius difference the wall is unrolled as a cylinder (the studio's spec §4.3). */
    double CYLINDER_TAPER_MM = 0.5;

    private static PrintSurface cup(CupModel cup, PrintArea area) {
        if ((cup.topDiameterMm() - cup.bottomDiameterMm()) / 2 < CYLINDER_TAPER_MM) {
            double slant = Math.hypot(cup.heightMm(), (cup.topDiameterMm() - cup.bottomDiameterMm()) / 2);
            return new Rect(Math.PI * cup.topDiameterMm() + cup.seamMm(),
                    slant - cup.bandTopOffsetMm() - cup.bandBottomOffsetMm(), area.bleedMm(), area.safeMarginMm(),
                    cup.bandTopOffsetMm(), cup.seamMm());
        }
        return Sector.of(cup, area.bleedMm(), area.safeMarginMm());
    }

    /**
     * A rectangle: a bag's face, or a cylindrical cup's wall with its glue flap at the right end of the trim, kept out
     * of the safe area.
     *
     * @param widthMm   trim width, the flap included
     * @param heightMm  trim height
     * @param vOffsetMm {@code v} at the trim's top edge (a cup's band starts below the rim)
     * @param seamMm    the glue flap's width; 0 for none
     */
    record Rect(double widthMm, double heightMm, double bleedMm, double safeMarginMm, double vOffsetMm,
                double seamMm) implements PrintSurface {

        @Override
        public boolean sector() {
            return false;
        }

        @Override
        public double uFrom() {
            return 0;
        }

        @Override
        public double uTo() {
            return widthMm;
        }

        @Override
        public double vFrom() {
            return vOffsetMm;
        }

        @Override
        public double vTo() {
            return vOffsetMm + heightMm;
        }

        @Override
        public Placed toFlat(double u, double v) {
            return new Placed(u + bleedMm, v - vOffsetMm + bleedMm, 0);
        }

        @Override
        public double flatWidth() {
            return widthMm + 2 * bleedMm;
        }

        @Override
        public double flatHeight() {
            return heightMm + 2 * bleedMm;
        }

        @Override
        public Box trimBounds() {
            return new Box(bleedMm, bleedMm, widthMm, heightMm);
        }

        @Override
        public boolean contains(Point p, Region region) {
            return switch (region) {
                case BLEED -> in(p, 0, 0, widthMm + 2 * bleedMm, heightMm + 2 * bleedMm);
                case TRIM -> in(p, bleedMm, bleedMm, widthMm, heightMm);
                case SAFE -> in(p, bleedMm + safeMarginMm, bleedMm + safeMarginMm,
                        widthMm - seamMm - 2 * safeMarginMm, heightMm - 2 * safeMarginMm);
            };
        }

        private static boolean in(Point p, double x, double y, double width, double height) {
            return p.x() >= x && p.x() <= x + width && p.y() >= y && p.y() <= y + height;
        }

        @Override
        public java.util.List<Point> trimSamples() {
            double left = bleedMm + SAMPLE_INSET_MM;
            double top = bleedMm + SAMPLE_INSET_MM;
            double right = bleedMm + widthMm - SAMPLE_INSET_MM;
            double bottom = bleedMm + heightMm - SAMPLE_INSET_MM;
            java.util.List<Point> points = new java.util.ArrayList<>();
            for (int i = 0; i <= 10; i++) {
                double fx = left + (right - left) * i / 10;
                double fy = top + (bottom - top) * i / 10;
                points.add(new Point(fx, top));
                points.add(new Point(fx, bottom));
                points.add(new Point(left, fy));
                points.add(new Point(right, fy));
            }
            return points;
        }
    }

    /**
     * A cone's wall unrolled into an annular sector about the cone's apex; radii from the apex, angles from straight up.
     * A region spans, at radius R, the angles {@code [from + inset / R, to - inset / R]}, so a margin keeps a constant
     * width along the radial edges (bleed is a negative inset).
     */
    final class Sector implements PrintSurface {

        /** Angles sampled along the bleed arcs when measuring the flat bounding box. */
        private static final int BOUNDS_SAMPLES = 256;

        private record Band(double inner, double outer, double from, double to, double insetMm) {
        }

        private final double outerRadiusMm;
        private final double midRadiusMm;
        private final double half;
        private final double seamRad;
        private final double sweepRad;
        private final double trimOuterMm;
        private final double trimInnerMm;
        private final double cx;
        private final double cy;
        private final double flatWidth;
        private final double flatHeight;
        private final Box trimBounds;
        private final Band bleed;
        private final Band trim;
        private final Band safe;

        private Sector(double outerRadiusMm, double innerRadiusMm, double sweepRad, double trimOuterMm,
                       double trimInnerMm, double seamRad, double bleedMm, double safeMarginMm) {
            this.outerRadiusMm = outerRadiusMm;
            this.midRadiusMm = (outerRadiusMm + innerRadiusMm) / 2;
            this.sweepRad = sweepRad;
            this.half = sweepRad / 2;
            this.seamRad = seamRad;
            this.trimOuterMm = trimOuterMm;
            this.trimInnerMm = trimInnerMm;
            double seamEnd = half + seamRad;
            this.bleed = new Band(trimInnerMm - bleedMm, trimOuterMm + bleedMm, -half, seamEnd, -bleedMm);
            this.trim = new Band(trimInnerMm, trimOuterMm, -half, seamEnd, 0);
            this.safe = new Band(trimInnerMm + safeMarginMm, trimOuterMm - safeMarginMm, -half, half, safeMarginMm);
            // The apex placed so the bleed box starts at the flat origin.
            double[] box = bounds(trimOuterMm + bleedMm, trimInnerMm - bleedMm, bleedMm / trimInnerMm);
            this.cx = -box[0];
            this.cy = -box[1];
            this.flatWidth = box[2] - box[0];
            this.flatHeight = box[3] - box[1];
            double[] cut = bounds(trimOuterMm, trimInnerMm, 0);
            this.trimBounds = new Box(cut[0] + cx, cut[1] + cy, cut[2] - cut[0], cut[3] - cut[1]);
        }

        /** The studio's {@code cupSector}: the unrolled cone of a cup. */
        static Sector of(CupModel cup, double bleedMm, double safeMarginMm) {
            double rt = cup.topDiameterMm() / 2;
            double rb = cup.bottomDiameterMm() / 2;
            double slant = Math.hypot(cup.heightMm(), rt - rb);
            double outer = rt * slant / (rt - rb);
            double inner = outer - slant;
            double trimInner = inner + cup.bandBottomOffsetMm();
            return new Sector(outer, inner, 2 * Math.PI * rt / outer, outer - cup.bandTopOffsetMm(), trimInner,
                    cup.seamMm() / trimInner, bleedMm, safeMarginMm);
        }

        /**
         * Min x, min y, max x, max y about the apex of the band between {@code outer} and {@code inner}, over the trim's
         * angles widened by {@code widen} each side, sampled - plus the axis directions inside the range, which
         * sampling can step over. With the bleed radii and widening, the studio's bleed box.
         */
        private double[] bounds(double outer, double inner, double widen) {
            double lo = -half - widen;
            double hi = half + seamRad + widen;
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            java.util.List<Double> angles = new java.util.ArrayList<>();
            for (int i = 0; i < BOUNDS_SAMPLES; i++) {
                angles.add(lo + (hi - lo) * i / (BOUNDS_SAMPLES - 1));
            }
            for (long k = (long) Math.ceil(lo / (Math.PI / 2)); k * (Math.PI / 2) <= hi; k++) {
                angles.add(k * (Math.PI / 2));
            }
            for (double phi : angles) {
                for (double r : new double[] {outer, inner}) {
                    minX = Math.min(minX, r * Math.sin(phi));
                    minY = Math.min(minY, -r * Math.cos(phi));
                    maxX = Math.max(maxX, r * Math.sin(phi));
                    maxY = Math.max(maxY, -r * Math.cos(phi));
                }
            }
            return new double[] {minX, minY, maxX, maxY};
        }

        @Override
        public double flatWidth() {
            return flatWidth;
        }

        @Override
        public double flatHeight() {
            return flatHeight;
        }

        @Override
        public Box trimBounds() {
            return trimBounds;
        }

        @Override
        public boolean sector() {
            return true;
        }

        @Override
        public java.util.List<Point> trimSamples() {
            java.util.List<Point> points = new java.util.ArrayList<>();
            double outer = trimOuterMm - SAMPLE_INSET_MM;
            double inner = trimInnerMm + SAMPLE_INSET_MM;
            for (double r : new double[] {outer, inner}) {
                double from = trim.from() + SAMPLE_INSET_MM / r;
                double to = trim.to() - SAMPLE_INSET_MM / r;
                for (int i = 0; i <= 32; i++) {
                    points.add(at(r, from + (to - from) * i / 32));
                }
            }
            for (int i = 0; i <= 8; i++) {
                double r = inner + (outer - inner) * i / 8;
                points.add(at(r, trim.from() + SAMPLE_INSET_MM / r));
                points.add(at(r, trim.to() - SAMPLE_INSET_MM / r));
            }
            return points;
        }

        private Point at(double r, double phi) {
            return new Point(cx + r * Math.sin(phi), cy - r * Math.cos(phi));
        }

        @Override
        public double uFrom() {
            return 0;
        }

        @Override
        public double uTo() {
            return (sweepRad + seamRad) * midRadiusMm;
        }

        @Override
        public double vFrom() {
            return outerRadiusMm - trimOuterMm;
        }

        @Override
        public double vTo() {
            return outerRadiusMm - trimInnerMm;
        }

        @Override
        public Placed toFlat(double u, double v) {
            double phi = u / midRadiusMm - half;
            double r = outerRadiusMm - v;
            return new Placed(cx + r * Math.sin(phi), cy - r * Math.cos(phi), phi);
        }

        @Override
        public boolean contains(Point p, Region region) {
            Band band = switch (region) {
                case BLEED -> bleed;
                case TRIM -> trim;
                case SAFE -> safe;
            };
            double dx = p.x() - cx;
            double dy = cy - p.y();
            double r = Math.hypot(dx, dy);
            if (r < band.inner() || r > band.outer()) {
                return false;
            }
            double phi = Math.atan2(dx, dy);
            double margin = band.insetMm() / r;
            return phi >= band.from() + margin && phi <= band.to() - margin;
        }
    }
}
