package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Port of {@code fill_ems.py} and of {@code fill_into_container} in {@code solver_3d.py}: seat lower-priority cartons
 * (docs 09 "hàng chèn") in the empty maximal spaces the main cargo left, each dropped onto the surface below it,
 * lowest, then deepest, then leftmost first.
 *
 * <p>Two changes, both from BR-10. The original treated support as advisory here ({@code support_min=0}, matching its
 * manual editor); a top-up carton must rest on at least 98 % of its base like any other. And its payload check
 * started from the weight the caller passed, which the kit solver passed as 0; here it starts from the vehicle's real
 * load.</p>
 */
final class EmsFill {

    /** The original's tolerance; exact on whole millimetres. */
    static final double EPS = 0.5;
    static final int MAX_SPACES = 1500;
    /** A pocket thinner than this on any axis can never hold a carton. */
    static final double MIN_DIM = 50.0;

    private EmsFill() {
    }

    /** One carton to seat: its source line and its sizes. */
    record Unit(int source, double w, double h, double d, double weight, boolean rotatable) { }

    /** A carton seated: its source line, corner and placed sizes. */
    record Seated(int source, double x, double y, double z, double w, double h, double d) { }

    /** {@code _orientations}: distinct permutations in the original's loop order (not sorted, unlike the block filler). */
    static List<double[]> orientations(double w, double h, double d, boolean rotatable) {
        List<double[]> out = new ArrayList<>();
        if (!rotatable) {
            out.add(new double[] {w, h, d});
            return out;
        }
        double[] dims = {w, h, d};
        // The original's two loops (a, then b != a, c the third) visit the permutations in this order.
        int[][] perms = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] p : perms) {
            double[] k = {dims[p[0]], dims[p[1]], dims[p[2]]};
            boolean dup = false;
            for (double[] o : out) {
                dup |= o[0] == k[0] && o[1] == k[1] && o[2] == k[2];
            }
            if (!dup) {
                out.add(k);
            }
        }
        return out;
    }

    static boolean intersects(double[] s, double[] p) {
        return s[0] < p[0] + p[3] - EPS && s[0] + s[3] > p[0] + EPS
                && s[1] < p[1] + p[4] - EPS && s[1] + s[4] > p[1] + EPS
                && s[2] < p[2] + p[5] - EPS && s[2] + s[5] > p[2] + EPS;
    }

    static boolean contained(double[] a, double[] b) {
        return a[0] >= b[0] - EPS && a[1] >= b[1] - EPS && a[2] >= b[2] - EPS
                && a[0] + a[3] <= b[0] + b[3] + EPS && a[1] + a[4] <= b[1] + b[4] + EPS
                && a[2] + a[5] <= b[2] + b[5] + EPS;
    }

    static List<double[]> pruneContained(List<double[]> spaces) {
        List<double[]> ordered = new ArrayList<>(spaces);
        ordered.sort(Comparator.comparingDouble(s -> -s[3] * s[4] * s[5]));
        List<double[]> kept = new ArrayList<>();
        for (double[] s : ordered) {
            boolean inside = false;
            for (double[] k : kept) {
                if (contained(s, k)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                kept.add(s);
            }
        }
        return kept;
    }

    static List<double[]> splitByBox(List<double[]> spaces, double[] p) {
        List<double[]> next = new ArrayList<>();
        for (double[] s : spaces) {
            if (!intersects(s, p)) {
                next.add(s);
                continue;
            }
            double[][] cs = {
                {s[0], s[1], s[2], p[0] - s[0], s[4], s[5]},
                {p[0] + p[3], s[1], s[2], s[0] + s[3] - (p[0] + p[3]), s[4], s[5]},
                {s[0], s[1], s[2], s[3], p[1] - s[1], s[5]},
                {s[0], p[1] + p[4], s[2], s[3], s[1] + s[4] - (p[1] + p[4]), s[5]},
                {s[0], s[1], s[2], s[3], s[4], p[2] - s[2]},
                {s[0], s[1], p[2] + p[5], s[3], s[4], s[2] + s[5] - (p[2] + p[5])},
            };
            for (double[] c : cs) {
                if (c[3] >= MIN_DIM && c[4] >= MIN_DIM && c[5] >= MIN_DIM) {
                    next.add(c);
                }
            }
        }
        List<double[]> pruned = pruneContained(next);
        return pruned.size() > MAX_SPACES ? new ArrayList<>(pruned.subList(0, MAX_SPACES)) : pruned;
    }

    static List<double[]> computeEmptySpaces(double w, double h, double d, List<double[]> obstacles) {
        List<double[]> spaces = new ArrayList<>();
        spaces.add(new double[] {0.0, 0.0, 0.0, w, h, d});
        for (double[] p : obstacles) {
            spaces = splitByBox(spaces, p);
        }
        return spaces;
    }

    static double restingY(double x, double z, double w, double d, double ceilY, List<double[]> placed) {
        double y = 0.0;
        for (double[] p : placed) {
            double top = p[1] + p[4];
            if (top <= ceilY + EPS && top > y
                    && x < p[0] + p[3] - EPS && x + w > p[0] + EPS
                    && z < p[2] + p[5] - EPS && z + d > p[2] + EPS) {
                y = top;
            }
        }
        return y;
    }

    static boolean overlapsAny(double x, double y, double z, double w, double h, double d, List<double[]> placed) {
        for (double[] p : placed) {
            if (x < p[0] + p[3] - EPS && x + w > p[0] + EPS
                    && y < p[1] + p[4] - EPS && y + h > p[1] + EPS
                    && z < p[2] + p[5] - EPS && z + d > p[2] + EPS) {
                return true;
            }
        }
        return false;
    }

    static double supportPct(double x, double y, double z, double w, double d, List<double[]> placed) {
        if (y <= EPS) {
            return 1.0;
        }
        double base = w * d;
        if (base <= 0) {
            return 1.0;
        }
        double covered = 0.0;
        for (double[] p : placed) {
            if (Math.abs(p[1] + p[4] - y) > EPS) {
                continue;
            }
            double ox = Math.min(x + w, p[0] + p[3]) - Math.max(x, p[0]);
            double oz = Math.min(z + d, p[2] + p[5]) - Math.max(z, p[2]);
            if (ox > EPS && oz > EPS) {
                covered += ox * oz;
            }
        }
        return covered / base;
    }

    /**
     * {@code fill_into_spaces}: greedily seat {@code units} (in the caller's order) into the free spaces left by
     * {@code obstacles}. Returns the seated cartons; {@code usedWeight[0]} receives the weight they add.
     */
    static List<Seated> fillIntoSpaces(double w, double h, double d, List<double[]> obstacles, List<Unit> units,
                                       double weightLimit, double baseWeight, double supportMin,
                                       double[] usedWeight) {
        List<double[]> placed = new ArrayList<>(obstacles);
        List<double[]> spaces = computeEmptySpaces(w, h, d, placed);
        List<Seated> out = new ArrayList<>();
        double weight = baseWeight;
        List<Unit> remaining = new ArrayList<>(units);
        boolean progress = true;
        while (progress && !remaining.isEmpty() && !spaces.isEmpty()) {
            progress = false;
            spaces.sort(Comparator.<double[]>comparingDouble(s -> s[1]).thenComparingDouble(s -> s[2])
                    .thenComparingDouble(s -> s[0]));
            for (int i = 0; i < remaining.size(); i++) {
                Unit unit = remaining.get(i);
                if (weightLimit > 0 && weight + unit.weight() > weightLimit + EPS) {
                    continue;
                }
                double[] hit = null;
                for (double[] s : spaces) {
                    for (double[] o : orientations(unit.w(), unit.h(), unit.d(), unit.rotatable())) {
                        double ow = o[0], oh = o[1], od = o[2];
                        if (ow > s[3] + EPS || oh > s[4] + EPS || od > s[5] + EPS) {
                            continue;
                        }
                        double y = restingY(s[0], s[2], ow, od, s[1], placed);
                        if (y + oh > h + EPS) {
                            continue;
                        }
                        if (overlapsAny(s[0], y, s[2], ow, oh, od, placed)) {
                            continue;
                        }
                        if (supportPct(s[0], y, s[2], ow, od, placed) + EPS < supportMin) {
                            continue;
                        }
                        hit = new double[] {s[0], y, s[2], ow, oh, od};
                        break;
                    }
                    if (hit != null) {
                        break;
                    }
                }
                if (hit != null) {
                    out.add(new Seated(unit.source(), hit[0], hit[1], hit[2], hit[3], hit[4], hit[5]));
                    placed.add(hit);
                    spaces = splitByBox(spaces, hit);
                    weight += unit.weight();
                    usedWeight[0] += unit.weight();
                    remaining.remove(i);
                    progress = true;
                    break;
                }
            }
        }
        return out;
    }

    /** The result of {@link #fillIntoContainer}: what was seated, and the fill lines that were not. */
    record Topped(List<BoxPlacement> extra, List<Box> leftover, double usedWeight) { }

    /**
     * {@code fill_into_container}: top a packed vehicle up with fill cartons, routing around its cargo and its
     * reserved corners. Never opens a vehicle (docs 09: the main cargo decides how many vehicles there are).
     */
    static Topped fillIntoContainer(Bin bin, List<BoxPlacement> occupied, double baseWeightKg, List<Box> fillBoxes,
                                    WorkClock clock) {
        if (fillBoxes.isEmpty()) {
            return new Topped(List.of(), List.copyOf(fillBoxes), 0.0);
        }
        clock.tick();
        List<double[]> obstacles = new ArrayList<>();
        for (BoxPlacement p : occupied) {
            obstacles.add(p.region());
        }
        for (BoxPlacement g : Gussets.of(bin)) {
            obstacles.add(g.region());
        }
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < fillBoxes.size(); i++) {
            Box b = fillBoxes.get(i);
            for (int k = 0; k < Math.max(0, b.quantity); k++) {
                units.add(new Unit(i, b.w, b.h, b.d, b.type.weightKg, b.type.rotatable));
            }
        }
        units.sort(Comparator.<Unit>comparingDouble(u -> -(u.w() * u.h() * u.d()))
                .thenComparingDouble(u -> -Math.max(u.w(), Math.max(u.h(), u.d()))));
        double[] used = {0.0};
        List<Seated> seated = fillIntoSpaces(bin.w, bin.h, bin.d, obstacles, units, bin.payloadKg, baseWeightKg,
                KitSolver.SUPPORT_MIN, used);
        List<BoxPlacement> extra = new ArrayList<>();
        int[] placedPerSource = new int[fillBoxes.size()];
        for (Seated s : seated) {
            Box b = fillBoxes.get(s.source());
            extra.add(new BoxPlacement(s.x(), s.y(), s.z(), s.w(), s.h(), s.d(), b.type, true));
            placedPerSource[s.source()]++;
        }
        List<Box> leftover = new ArrayList<>();
        for (int i = 0; i < fillBoxes.size(); i++) {
            int left = fillBoxes.get(i).quantity - placedPerSource[i];
            if (left > 0) {
                leftover.add(fillBoxes.get(i).withQuantity(left));
            }
        }
        return new Topped(extra, leftover, used[0]);
    }
}
