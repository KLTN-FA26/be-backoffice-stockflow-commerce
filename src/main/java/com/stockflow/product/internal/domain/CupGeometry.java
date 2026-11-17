package com.stockflow.product.internal.domain;

import com.stockflow.product.api.CupModel;

import java.util.ArrayList;
import java.util.List;

/**
 * A cup wall unrolled flat: the same arithmetic as the storefront's {@code engine/surfaces/cup.ts} ({@code cupSector},
 * {@code isCylindricalCup} and the bleed box), so the print area the server stores is the one the browser draws.
 *
 * <p>The wall is a truncated cone. Cut along a seam and laid flat it becomes an annular sector about the cone's apex:
 * the rim edge at radius {@code R = rt * slant / (rt - rb)}, the base edge one slant length inside it, spanning the
 * angle that makes the rim's arc as long as its circumference. The printed band is that sector less the unprinted
 * offsets at rim and base, plus a glue flap past one end. A wall whose radii differ by less than half a millimetre is
 * unrolled as a rectangle instead: its apex would be metres away and the sector numerically meaningless.</p>
 */
public final class CupGeometry {

    /** Below this top/bottom radius difference the wall is unrolled as a cylinder. */
    static final double CYLINDER_TAPER_MM = 0.5;
    /** Angles sampled along the bleed arcs when measuring the flat bounding box, as the storefront samples them. */
    static final int BOUNDS_SAMPLES = 256;

    private CupGeometry() {
    }

    /** The unrolled band; radii from the cone's apex. */
    public record Sector(double slantMm, double outerRadiusMm, double innerRadiusMm, double sweepRad,
                         double midRadiusMm, double trimOuterMm, double trimInnerMm, double seamRad) {
    }

    /** A box in millimetres. */
    public record Box(double x, double y, double width, double height) {
    }

    /** Whether the wall is unrolled as a rectangle rather than a sector. */
    public static boolean isCylindrical(CupModel model) {
        return (model.topDiameterMm() - model.bottomDiameterMm()) / 2 < CYLINDER_TAPER_MM;
    }

    /** The length of the wall, rim to base, along its surface. */
    public static double slant(CupModel model) {
        return Math.hypot(model.heightMm(), (model.topDiameterMm() - model.bottomDiameterMm()) / 2);
    }

    /** The unrolled band of a conical cup. Meaningless for a cylindrical one: check {@link #isCylindrical} first. */
    public static Sector sector(CupModel model) {
        double rt = model.topDiameterMm() / 2;
        double rb = model.bottomDiameterMm() / 2;
        double slant = Math.hypot(model.heightMm(), rt - rb);
        double outer = rt * slant / (rt - rb);
        double inner = outer - slant;
        double trimInner = inner + model.bandBottomOffsetMm();
        return new Sector(slant, outer, inner, 2 * Math.PI * rt / outer, (outer + inner) / 2,
                outer - model.bandTopOffsetMm(), trimInner, model.seamMm() / trimInner);
    }

    /**
     * The bounding box of the band's bleed region, relative to the apex: the bleed radii, the trim angles widened by
     * {@code bleed / trimInner}, sampled along the arcs and at every axis direction inside them (where an arc reaches
     * its extreme), exactly as the storefront measures it.
     */
    public static Box bleedBox(Sector s, double bleed) {
        double widen = bleed / s.trimInnerMm();
        double lo = -s.sweepRad() / 2 - widen;
        double hi = s.sweepRad() / 2 + s.seamRad() + widen;
        if (!(hi - lo < 2 * Math.PI)) {
            // PrintConfigRules refuses such a cup first; this keeps the axis loop below to a handful of turns.
            throw new IllegalArgumentException("A band spanning more than one turn has no flat bounding box");
        }
        List<Double> angles = new ArrayList<>(BOUNDS_SAMPLES + 4);
        for (int i = 0; i < BOUNDS_SAMPLES; i++) {
            angles.add(lo + (hi - lo) * i / (BOUNDS_SAMPLES - 1));
        }
        for (int k = (int) Math.ceil(lo / (Math.PI / 2)); k * (Math.PI / 2) <= hi; k++) {
            angles.add(k * (Math.PI / 2));
        }
        double[] radii = {s.trimOuterMm() + bleed, s.trimInnerMm() - bleed};
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double phi : angles) {
            for (double r : radii) {
                double x = r * Math.sin(phi);
                double y = -r * Math.cos(phi);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return new Box(minX, minY, maxX - minX, maxY - minY);
    }
}
