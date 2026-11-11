package com.stockflow.product.api;

/**
 * The outline of a print area when laid flat: a rectangle (bag sides, a straight-walled cup) or the annular sector a
 * conical cup wall unrolls to. Computed from the {@link ProductModel}; never accepted from a client.
 */
public sealed interface PrintAreaShape permits PrintAreaShape.Rect, PrintAreaShape.AnnularSector {

    /** The discriminator written to {@code shape_json} and sent to the storefront. */
    String type();

    /** A rectangle as wide and tall as its print area. */
    record Rect() implements PrintAreaShape {
        public static final String TYPE = "rect";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /**
     * A conical band unrolled about the cone's apex.
     *
     * @param innerRadiusMm the trimmed band's radius at the base edge
     * @param outerRadiusMm the trimmed band's radius at the rim edge
     * @param sweepRad      the angle the rim's circumference spans
     * @param seamMm        the glue flap past the sweep, measured along the inner radius
     */
    record AnnularSector(double innerRadiusMm, double outerRadiusMm, double sweepRad, double seamMm)
            implements PrintAreaShape {
        public static final String TYPE = "annular-sector";

        @Override
        public String type() {
            return TYPE;
        }
    }
}
