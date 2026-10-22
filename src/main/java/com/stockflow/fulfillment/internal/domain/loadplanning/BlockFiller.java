package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Port of the GRASP block engine's primitives in {@code solver_3d.py} that the kit solver builds on:
 * Empty-Maximal-Space arithmetic ({@code _ems_intersects}, {@code _ems_contained}, {@code _ems_diff},
 * {@code _ems_update}) and the randomised greedy block filler ({@code _grasp_recreate}).
 *
 * <p>A free space is {@code {x, y, z, w, h, d}}. The filler repeatedly offers, for every free space, every type with
 * units left and every orientation, the largest block of identical cartons that fits the space (and the remaining
 * payload); keeps the candidates whose volume is within {@code alpha} of the best; and places one of them at random.
 * {@code alpha} 0 is pure greedy.</p>
 *
 * <p>One deliberate change: the original compared spaces with a 1 mm tolerance, which on integer millimetres let a
 * block overlap a neighbour, or stand out of the vehicle, by exactly 1 mm. Every size here is a whole millimetre, so
 * a half-millimetre tolerance is exact and BR-10's "inside the cargo space, no overlap" holds without slack.</p>
 */
final class BlockFiller {

    /** The original's {@code _BTOL} was 1.0; see the class comment. */
    static final double TOL = 0.5;
    /** The original's {@code _EPS}. */
    static final double EPS = 1e-9;

    private BlockFiller() {
    }

    static boolean intersects(double[] s, double[] r) {
        return s[0] < r[0] + r[3] - TOL && s[0] + s[3] > r[0] + TOL
                && s[1] < r[1] + r[4] - TOL && s[1] + s[4] > r[1] + TOL
                && s[2] < r[2] + r[5] - TOL && s[2] + s[5] > r[2] + TOL;
    }

    static boolean contained(double[] s, double[] t) {
        return t[0] <= s[0] + TOL && s[0] + s[3] <= t[0] + t[3] + TOL
                && t[1] <= s[1] + TOL && s[1] + s[4] <= t[1] + t[4] + TOL
                && t[2] <= s[2] + TOL && s[2] + s[5] <= t[2] + t[5] + TOL;
    }

    /** Split free space {@code s} by the occupied region {@code r} into up to six maximal sub-spaces. */
    static List<double[]> diff(double[] s, double[] r) {
        double sx = s[0], sy = s[1], sz = s[2], sw = s[3], sh = s[4], sd = s[5];
        double rx = r[0], ry = r[1], rz = r[2], rw = r[3], rh = r[4], rd = r[5];
        List<double[]> out = new ArrayList<>(6);
        if (rx > sx + TOL) {
            out.add(new double[] {sx, sy, sz, rx - sx, sh, sd});
        }
        if (rx + rw < sx + sw - TOL) {
            out.add(new double[] {rx + rw, sy, sz, sx + sw - (rx + rw), sh, sd});
        }
        if (ry > sy + TOL) {
            out.add(new double[] {sx, sy, sz, sw, ry - sy, sd});
        }
        if (ry + rh < sy + sh - TOL) {
            out.add(new double[] {sx, ry + rh, sz, sw, sy + sh - (ry + rh), sd});
        }
        if (rz > sz + TOL) {
            out.add(new double[] {sx, sy, sz, sw, sh, rz - sz});
        }
        if (rz + rd < sz + sd - TOL) {
            out.add(new double[] {sx, sy, rz + rd, sw, sh, sz + sd - (rz + rd)});
        }
        return out;
    }

    /** {@code _ems_update}: carve {@code r} out of every space, drop slivers thinner than {@code mind}, keep maximal. */
    static List<double[]> update(List<double[]> spaces, double[] r, double mind) {
        List<double[]> next = new ArrayList<>();
        for (double[] s : spaces) {
            if (intersects(s, r)) {
                next.addAll(diff(s, r));
            } else {
                next.add(s);
            }
        }
        List<double[]> uniq = new ArrayList<>();
        Set<SpaceKey> seen = new HashSet<>();
        for (double[] s : next) {
            if (s[3] >= mind - TOL && s[4] >= mind - TOL && s[5] >= mind - TOL) {
                SpaceKey k = new SpaceKey(Math.rint(s[0]), Math.rint(s[1]), Math.rint(s[2]), Math.rint(s[3]),
                        Math.rint(s[4]), Math.rint(s[5]));
                if (seen.add(k)) {
                    uniq.add(s);
                }
            }
        }
        List<double[]> out = new ArrayList<>(uniq.size());
        for (int i = 0; i < uniq.size(); i++) {
            boolean inside = false;
            for (int j = 0; j < uniq.size() && !inside; j++) {
                inside = j != i && contained(uniq.get(i), uniq.get(j));
            }
            if (!inside) {
                out.add(uniq.get(i));
            }
        }
        return out;
    }

    private record SpaceKey(double x, double y, double z, double w, double h, double d) { }

    /**
     * Whether a block's bottom layer rests well enough. The original checked only the block's whole base, so a block
     * 98 % supported overall could leave the carton at its edge on far less - BR-10 asks it of every carton. The
     * original's check comes first, and a block resting fully needs no other; only a partly resting block has each
     * bottom carton checked on its own.
     */
    static boolean blockSupported(SupportGrid grid, double sx, double sy, double sz, double w, double d, int nx,
                                  int nz, double supportMin) {
        double whole = grid.support(sx, sy, sz, nx * w, nz * d);
        if (whole < supportMin) {
            return false;
        }
        if (whole >= 1.0 || nx * nz == 1) {
            return true;
        }
        for (int a = 0; a < nx; a++) {
            for (int c = 0; c < nz; c++) {
                if (grid.support(sx + a * w, sy, sz + c * d, w, d) < supportMin) {
                    return false;
                }
            }
        }
        return true;
    }

    /** One block candidate of {@code _grasp_recreate}. */
    private record Candidate(double volume, int index, double w, double h, double d, int nx, int ny, int nz,
                             double sx, double sy, double sz) { }

    /**
     * {@code _grasp_recreate}: fill {@code spaces} with blocks of the remaining cartons until nothing fits. Mutates
     * {@code placements}, {@code placedIndex}, {@code grid} and {@code remaining}; returns the updated spaces.
     *
     * @param weightAcc the load already in the vehicle, updated in place. The original honoured a cap only when one
     *                  was given and the kit solver never gave one; BR-10 makes the payload a cap on every call
     */
    static List<double[]> recreate(List<Box> boxes, PyRandom rng, double alpha, double mind, List<double[]> spaces,
                                   SupportGrid grid, List<BoxPlacement> placements, List<Integer> placedIndex,
                                   int[] remaining, double supportMin, double weightLimit, double[] weightAcc,
                                   WorkClock clock) {
        clock.tick();
        while (true) {
            List<Candidate> cands = new ArrayList<>();
            for (double[] s : spaces) {
                double sx = s[0], sy = s[1], sz = s[2], sw = s[3], sh = s[4], sd = s[5];
                for (int i = 0; i < boxes.size(); i++) {
                    if (remaining[i] <= 0) {
                        continue;
                    }
                    Box box = boxes.get(i);
                    int cap = remaining[i];
                    if (weightLimit > 0 && box.type.weightKg > EPS) {
                        int room = (int) ((weightLimit - weightAcc[0] + EPS) / box.type.weightKg);
                        if (room <= 0) {
                            continue;
                        }
                        cap = Math.min(cap, room);
                    }
                    for (double[] o : box.orientations()) {
                        double w = o[0], h = o[1], d = o[2];
                        if (w > sw + TOL || h > sh + TOL || d > sd + TOL) {
                            continue;
                        }
                        int nx = (int) Math.floor((sw + TOL) / w);
                        int ny = (int) Math.floor((sh + TOL) / h);
                        int nz = (int) Math.floor((sd + TOL) / d);
                        while ((long) nx * ny * nz > cap) {
                            if (nz > 1) {
                                nz--;
                            } else if (ny > 1) {
                                ny--;
                            } else if (nx > 1) {
                                nx--;
                            } else {
                                break;
                            }
                        }
                        if ((long) nx * ny * nz > cap) {
                            continue;
                        }
                        if (sy > TOL && !blockSupported(grid, sx, sy, sz, w, d, nx, nz, supportMin)) {
                            continue;
                        }
                        cands.add(new Candidate(nx * ny * nz * w * h * d, i, w, h, d, nx, ny, nz, sx, sy, sz));
                    }
                }
            }
            if (cands.isEmpty()) {
                break;
            }
            double smax = Double.NEGATIVE_INFINITY;
            double smin = Double.POSITIVE_INFINITY;
            for (Candidate c : cands) {
                smax = Math.max(smax, c.volume);
                smin = Math.min(smin, c.volume);
            }
            double threshold = smax - alpha * (smax - smin);
            List<Candidate> rcl = new ArrayList<>();
            for (Candidate c : cands) {
                if (c.volume >= threshold) {
                    rcl.add(c);
                }
            }
            Candidate pick = rng.choice(rcl);
            Box box = boxes.get(pick.index);
            for (int a = 0; a < pick.nx; a++) {
                for (int b = 0; b < pick.ny; b++) {
                    for (int c = 0; c < pick.nz; c++) {
                        BoxPlacement bp = new BoxPlacement(pick.sx + a * pick.w, pick.sy + b * pick.h,
                                pick.sz + c * pick.d, pick.w, pick.h, pick.d, box.type, false);
                        placements.add(bp);
                        placedIndex.add(pick.index);
                        grid.register(bp);
                    }
                }
            }
            int n = pick.nx * pick.ny * pick.nz;
            spaces = update(spaces, new double[] {pick.sx, pick.sy, pick.sz, pick.nx * pick.w, pick.ny * pick.h,
                    pick.nz * pick.d}, mind);
            remaining[pick.index] -= n;
            weightAcc[0] += n * box.type.weightKg;
        }
        return spaces;
    }
}
