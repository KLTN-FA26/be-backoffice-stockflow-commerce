package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.List;

/**
 * The reinforced ceiling corners a vehicle keeps clear (BR-10 "chừa các góc gia cố trên trần thùng"): port of
 * {@code all_gussets} in {@code solver_3d_kit.py}, with {@code kit_gussets} and {@code solver_3d._gusset_placements}.
 * The layout is exactly the original's; only the size now comes from the vehicle type (decision LP-12).
 *
 * <ul>
 *   <li>A cargo box longer than 12.5 m (a 45-foot container) gets four cubes at the two 40-foot corner-post lines -
 *       12 032 mm apart, centred in the length - left and right, and nothing at the extreme corners.</li>
 *   <li>Every other one gets two cubes at the top of the front wall, left and right.</li>
 * </ul>
 */
final class Gussets {

    /** The real internal span between a 40-foot box's corner posts, whatever the clearance trimmed off the length. */
    static final double SPAN_40 = 12032.0;
    /** A cargo box longer than this is a 45-foot one (40 ft is about 12 000, 45 ft 13 450-13 556). */
    static final double LENGTH_45_MIN = 12500.0;

    private Gussets() {
    }

    static List<BoxPlacement> of(Bin bin) {
        double size = bin.gussetMm;
        if (size <= 0) {
            return List.of();
        }
        List<BoxPlacement> posts = postLines(bin, size);
        return posts.isEmpty() ? frontTop(bin, size) : posts;
    }

    /** {@code _gusset_placements}: the four 45-foot corner-post cubes, or none. */
    private static List<BoxPlacement> postLines(Bin bin, double size) {
        double w = bin.w;
        double h = bin.h;
        double d = bin.d;
        if (w < 2 * size || h < size || d < 2 * size) {
            return List.of();
        }
        double inset = (d - SPAN_40) / 2.0;
        if (d < LENGTH_45_MIN || inset <= 0 || d - 2 * (inset + size) <= 0) {
            return List.of();
        }
        double y0 = h - size;
        List<BoxPlacement> out = new ArrayList<>();
        for (double x0 : new double[] {0.0, w - size}) {
            for (double z0 : new double[] {inset, d - inset - size}) {
                out.add(new BoxPlacement(x0, y0, z0, size, size, size, null, false));
            }
        }
        return out;
    }

    /** {@code kit_gussets}: the two cubes at the top of the front wall, or none when the box is too small. */
    private static List<BoxPlacement> frontTop(Bin bin, double size) {
        if (bin.w < 2 * size || bin.h < size || bin.d < size) {
            return List.of();
        }
        double y0 = bin.h - size;
        return List.of(
                new BoxPlacement(0.0, y0, 0.0, size, size, size, null, false),
                new BoxPlacement(bin.w - size, y0, 0.0, size, size, size, null, false));
    }
}
