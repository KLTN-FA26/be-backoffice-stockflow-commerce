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
 * <p>Deliberate deviations from the original:</p>
 * <ol>
 *   <li>The original compared spaces with a 1 mm tolerance, which on integer millimetres let a block overlap a
 *       neighbour, or stand out of the vehicle, by exactly 1 mm. Every size here is a whole millimetre, so a
 *       half-millimetre tolerance is exact and BR-10's "inside the cargo space, no overlap" holds without slack.</li>
 *   <li>SCRUM-496: the planner does not draw among every space's blocks. It takes the deepest, then lowest, then
 *       leftmost space that offers a block, and draws only among that space's blocks ({@link SpaceChoice#IMPROVED},
 *       which says why). {@link SpaceChoice#PYTHON_PARITY} keeps the original's draw for the parity test.</li>
 * </ol>
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

    /**
     * {@code _ems_update}: carve {@code r} out of every space, drop slivers thinner than {@code mind}, keep maximal.
     *
     * <p>The original tested every space against every other for containment. Only the pieces split off this step
     * can be contained in something: the spaces {@code r} did not touch were maximal before and cannot sit inside a
     * piece, which lies inside a space they were not inside either. So only the new pieces are tested - the same
     * list in the same order, without the quadratic pass that dominated large fills.</p>
     */
    static List<double[]> update(List<double[]> spaces, double[] r, double mind) {
        List<double[]> next = new ArrayList<>();
        java.util.Set<double[]> pieces = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (double[] s : spaces) {
            if (intersects(s, r)) {
                List<double[]> d = diff(s, r);
                next.addAll(d);
                pieces.addAll(d);
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
            if (pieces.contains(uniq.get(i))) {
                for (int j = 0; j < uniq.size() && !inside; j++) {
                    inside = j != i && contained(uniq.get(i), uniq.get(j));
                }
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
    static boolean blockSupported(List<BoxPlacement> supporters, double sx, double sy, double sz, double w, double d,
                                  int nx, int nz, double supportMin) {
        double whole = SupportGrid.support(supporters, sx, sy, sz, nx * w, nz * d);
        if (whole < supportMin) {
            return false;
        }
        if (whole >= 1.0 || nx * nz == 1) {
            return true;
        }
        for (int a = 0; a < nx; a++) {
            for (int c = 0; c < nz; c++) {
                if (SupportGrid.support(supporters, sx + a * w, sy, sz + c * d, w, d) < supportMin) {
                    return false;
                }
            }
        }
        return true;
    }

    /** One block candidate of {@code _grasp_recreate}. */
    private record Candidate(double volume, int index, double w, double h, double d, int nx, int ny, int nz,
                             double sx, double sy, double sz) { }

    /** How many units of type {@code i} a block may hold now: what is left, and what the payload still allows. */
    private static int cap(Box box, int remaining, double weightLimit, double weightAcc) {
        if (remaining <= 0) {
            return 0;
        }
        int cap = remaining;
        if (weightLimit > 0 && box.type.weightKg > EPS) {
            int room = (int) ((weightLimit - weightAcc + EPS) / box.type.weightKg);
            if (room <= 0) {
                return 0;
            }
            cap = Math.min(cap, room);
        }
        return cap;
    }

    /** The original's inner loop for one space and one type: a block per orientation that fits, in orientation order. */
    private static List<Candidate> candidates(double[] s, int i, Box box, int cap, SpaceCache cache, SupportGrid grid,
                                              double supportMin, Bin bin) {
        List<Candidate> out = null;
        int uncapped = 0;
        double sx = s[0], sy = s[1], sz = s[2], sw = s[3], sh = s[4], sd = s[5];
        if (box.type.rotatable && !cache.mayHold(box)) {
            cache.uncapped[i] = 0;
            return List.of();       // no orientation fits: the sorted sizes already do not
        }
        for (double[] o : box.orientations()) {
            double w = o[0], h = o[1], d = o[2];
            if (w > sw + TOL || h > sh + TOL || d > sd + TOL || !bin.passesDoor(w, h)) {
                continue;       // the door (LP3-DOOR): only orientations that pass it can be loaded
            }
            int nx = (int) Math.floor((sw + TOL) / w);
            int ny = (int) Math.floor((sh + TOL) / h);
            int nz = (int) Math.floor((sd + TOL) / d);
            uncapped = (int) Math.max(uncapped, Math.min(Integer.MAX_VALUE, (long) nx * ny * nz));
            // The original shrinks z first, then y, then x, one unit at a time until the block fits the cap; the same
            // result in closed form.
            if ((long) nx * ny * nz > (long) cap) {
                nz = (int) Math.max(1, Math.min(nz, cap / ((long) nx * ny)));
                if ((long) nx * ny * nz > (long) cap) {
                    ny = (int) Math.max(1, Math.min(ny, cap / (long) nx));
                    if ((long) nx * ny * nz > (long) cap) {
                        nx = Math.max(1, Math.min(nx, cap));
                    }
                }
            }
            if ((long) nx * ny * nz > (long) cap) {
                continue;
            }
            if (sy > TOL && !blockSupported(cache.supporters(grid), sx, sy, sz, w, d, nx, nz, supportMin)) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(2);
            }
            out.add(new Candidate(nx * ny * nz * w * h * d, i, w, h, d, nx, ny, nz, sx, sy, sz));
        }
        cache.uncapped[i] = uncapped;
        return out == null ? List.of() : out;
    }

    /**
     * What one free space offers, kept between steps of one fill. A space's candidates change only when the space is
     * new, when a carton lands with its top at the space's floor under its footprint (its support changes), or when a
     * type's cap changes enough to shrink one of its blocks. Everything else is reused, and the space keeps the
     * largest and smallest block volume it offers, so a step finds the best block without visiting every candidate.
     * The draw still sees exactly the original's candidate list, in the original's order.
     */
    private static final class SpaceCache {
        final double[] space;
        final List<List<Candidate>> byType;
        final int[] capUsed;
        /** Per type: the largest block an orientation would make before the cap shrinks it. */
        final int[] uncapped;
        final double[] typeMax;
        final double[] typeMin;
        double spaceMax = Double.NEGATIVE_INFINITY;
        double spaceMin = Double.POSITIVE_INFINITY;
        boolean dirty = true;
        /** New, or its support changed: every type must be looked at again. */
        boolean stale = true;
        List<BoxPlacement> supporters;

        /** The space's sizes, smallest first, for {@link #mayHold}. */
        final double[] sorted;

        SpaceCache(double[] space, int types) {
            this.space = space;
            this.sorted = new double[] {space[3], space[4], space[5]};
            java.util.Arrays.sort(this.sorted);
            this.byType = new ArrayList<>(types);
            for (int i = 0; i < types; i++) {
                byType.add(null);
            }
            this.capUsed = new int[types];
            this.uncapped = new int[types];
            this.typeMax = new double[types];
            this.typeMin = new double[types];
        }

        /** Whether some orientation of a turnable carton could fit: its sizes, smallest first, each fit. */
        boolean mayHold(Box box) {
            double[] b = box.sortedSizes();
            return b[0] <= sorted[0] + TOL && b[1] <= sorted[1] + TOL && b[2] <= sorted[2] + TOL;
        }

        List<BoxPlacement> supporters(SupportGrid grid) {
            if (supporters == null) {
                supporters = grid.supporters(space[0], space[1], space[2], space[3], space[5]);
            }
            return supporters;
        }

        void invalidate() {
            supporters = null;
            for (int i = 0; i < byType.size(); i++) {
                byType.set(i, null);
            }
            stale = true;
            dirty = true;
        }

        /** Bring type {@code i} up to date for this cap; returns the work it took. */
        long refresh(int i, Box box, int cap, SupportGrid grid, double supportMin, Bin bin) {
            List<Candidate> c = byType.get(i);
            if (c != null && capUsed[i] == cap) {
                return 1;
            }
            if (c != null && cap >= uncapped[i] && capUsed[i] >= uncapped[i]) {
                capUsed[i] = cap;    // no block of this type was or will be shrunk by the cap: same candidates
                return 1;
            }
            c = candidates(space, i, box, cap, this, grid, supportMin, bin);
            byType.set(i, c);
            capUsed[i] = cap;
            double max = Double.NEGATIVE_INFINITY;
            double min = Double.POSITIVE_INFINITY;
            for (Candidate k : c) {
                max = Math.max(max, k.volume);
                min = Math.min(min, k.volume);
            }
            typeMax[i] = max;
            typeMin[i] = min;
            dirty = true;
            return 1 + box.orientations().size();
        }

        /** Recompute the space's largest and smallest block over the types still in play. */
        long aggregate(int[] caps) {
            if (!dirty) {
                return 0;
            }
            spaceMax = Double.NEGATIVE_INFINITY;
            spaceMin = Double.POSITIVE_INFINITY;
            for (int i = 0; i < caps.length; i++) {
                if (caps[i] > 0 && byType.get(i) != null && !byType.get(i).isEmpty()) {
                    spaceMax = Math.max(spaceMax, typeMax[i]);
                    spaceMin = Math.min(spaceMin, typeMin[i]);
                }
            }
            dirty = false;
            return caps.length;
        }

        /** A block {@code r} was placed: does it change what this space rests on? */
        boolean supportChangedBy(double[] r) {
            return Math.abs((r[1] + r[4]) - space[1]) <= SupportGrid.EPS
                    && Math.min(space[0] + space[3], r[0] + r[3]) - Math.max(space[0], r[0]) > SupportGrid.EPS
                    && Math.min(space[2] + space[5], r[2] + r[5]) - Math.max(space[2], r[2]) > SupportGrid.EPS;
        }
    }

    /**
     * Add one space's blocks that reach {@code threshold} to {@code rcl}, in the original's order (type, then
     * orientation); returns the work: one per type and one per block looked at.
     */
    private static long collect(SpaceCache cache, int[] caps, double threshold, List<Candidate> rcl) {
        long work = 0;
        for (int i = 0; i < caps.length; i++) {
            work++;
            if (caps[i] <= 0 || cache.byType.get(i).isEmpty() || cache.typeMax[i] < threshold) {
                continue;
            }
            for (Candidate k : cache.byType.get(i)) {
                work++;
                if (k.volume >= threshold) {
                    rcl.add(k);
                }
            }
        }
        return work;
    }

    /**
     * {@code _grasp_recreate}: fill {@code spaces} with blocks of the remaining cartons until nothing fits, or the real
     * budget runs out - the blocks placed so far stand, so a cut-off fill is still a valid partial load. Mutates
     * {@code placements}, {@code placedIndex}, {@code grid} and {@code remaining}; returns the updated spaces.
     *
     * @param weightAcc the load already in the vehicle, updated in place. The original honoured a cap only when one
     *                  was given and the kit solver never gave one; BR-10 makes the payload a cap on every call
     * @param choice    where the next block goes: the original's draw, or deviation 2 of the class comment
     */
    static List<double[]> recreate(Bin bin, List<Box> boxes, PyRandom rng, double alpha, double mind,
                                   List<double[]> spaces, SupportGrid grid, List<BoxPlacement> placements,
                                   List<Integer> placedIndex, int[] remaining, double supportMin, double weightLimit,
                                   double[] weightAcc, WorkClock clock, SpaceChoice choice) {
        clock.construction();
        int types = boxes.size();
        java.util.IdentityHashMap<double[], SpaceCache> caches = new java.util.IdentityHashMap<>();
        int[] caps = new int[types];
        int[] previousCaps = new int[types];
        java.util.Arrays.fill(previousCaps, -1);
        while (!clock.expired()) {
            long work = 0;
            for (int i = 0; i < types; i++) {
                caps[i] = cap(boxes.get(i), remaining[i], weightLimit, weightAcc[0]);
            }
            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < types; i++) {
                if (caps[i] != previousCaps[i]) {
                    changed.add(i);
                }
            }
            java.util.IdentityHashMap<double[], SpaceCache> live = new java.util.IdentityHashMap<>();
            List<SpaceCache> order = new ArrayList<>(spaces.size());
            double smax = Double.NEGATIVE_INFINITY;
            double smin = Double.POSITIVE_INFINITY;
            for (double[] s : spaces) {
                SpaceCache cache = caches.get(s);
                if (cache == null) {
                    cache = new SpaceCache(s, types);
                }
                live.put(s, cache);
                order.add(cache);
                if (cache.stale) {
                    for (int i = 0; i < types; i++) {
                        if (caps[i] > 0) {
                            work += cache.refresh(i, boxes.get(i), caps[i], grid, supportMin, bin);
                        }
                    }
                    cache.stale = false;
                    cache.dirty = true;
                } else {
                    for (int i : changed) {
                        if (caps[i] > 0) {
                            work += cache.refresh(i, boxes.get(i), caps[i], grid, supportMin, bin);
                        } else {
                            cache.dirty = true;     // the type left play
                        }
                    }
                }
                work += cache.aggregate(caps);
                if (cache.spaceMax != Double.NEGATIVE_INFINITY) {
                    smax = Math.max(smax, cache.spaceMax);
                    smin = Math.min(smin, cache.spaceMin);
                }
            }
            System.arraycopy(caps, 0, previousCaps, 0, types);
            caches = live;
            if (smax == Double.NEGATIVE_INFINITY) {
                clock.evaluated(work + spaces.size());
                break;
            }
            List<Candidate> rcl = new ArrayList<>();
            if (choice == SpaceChoice.IMPROVED) {
                // Deviation 2 (see the class comment): the space first, then the original's draw among its blocks.
                SpaceCache at = null;
                for (SpaceCache cache : order) {
                    work++;
                    if (cache.spaceMax != Double.NEGATIVE_INFINITY && (at == null
                            || SpaceChoice.before(cache.space, at.space))) {
                        at = cache;
                    }
                }
                java.util.Objects.requireNonNull(at, "some space offers a block: smax is finite");
                work += collect(at, caps, at.spaceMax - alpha * (at.spaceMax - at.spaceMin), rcl);
            } else {
                // The candidates are not gathered into one list: the original's list (space, type, orientation
                // order) is walked only where a block can reach the threshold, which picks out the restricted
                // candidate list.
                double threshold = smax - alpha * (smax - smin);
                for (SpaceCache cache : order) {
                    work++;
                    if (cache.spaceMax == Double.NEGATIVE_INFINITY || cache.spaceMax < threshold) {
                        continue;
                    }
                    work += collect(cache, caps, threshold, rcl);
                }
            }
            clock.evaluated(work + 6L * spaces.size());
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
            double[] region = {pick.sx, pick.sy, pick.sz, pick.nx * pick.w, pick.ny * pick.h, pick.nz * pick.d};
            for (SpaceCache cache : caches.values()) {
                if (cache.supportChangedBy(region)) {
                    cache.invalidate();
                }
            }
            spaces = update(spaces, region, mind);
            remaining[pick.index] -= n;
            weightAcc[0] += n * box.type.weightKg;
        }
        return spaces;
    }
}
