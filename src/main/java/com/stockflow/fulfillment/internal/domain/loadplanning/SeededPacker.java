package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.List;

/**
 * The restarting packers of {@code solver_3d_kit.py}: {@code _pack_seeded} (loose cartons around pinned cargo) and
 * {@code _place_kits} with {@code _trim_to_complete_sets} (a vehicle's share of sets) - the latter with the kit-bypass
 * fix. Every restart draws from the original's seeds shifted by the caller's.
 */
final class SeededPacker {

    private final WorkClock clock;
    private final long seedOffset;
    private final SpaceChoice spaceChoice;

    SeededPacker(WorkClock clock, long seedOffset, SpaceChoice spaceChoice) {
        this.clock = clock;
        this.seedOffset = seedOffset;
        this.spaceChoice = spaceChoice;
    }

    /** What seeding a Mersenne Twister costs, in candidate evaluations (its 624-word state, a few times over). */
    static final long RNG_SEED_COST = 2_000;

    /** Cartons placed in one vehicle and, for each, the index of the line it came from. */
    record Packed(List<BoxPlacement> placements, List<Integer> index) { }

    /** Every fixed seed of the original, shifted by the caller's seed: seed 0 draws exactly what Python drew. */
    PyRandom rng(long seed) {
        return new PyRandom(seed + seedOffset);
    }

    // ------------------------------------------------------------------ packing primitives

    static double minDim(List<Box> boxes) {
        double m = Double.POSITIVE_INFINITY;
        for (Box b : boxes) {
            m = Math.min(m, Math.min(b.w, Math.min(b.h, b.d)));
        }
        return m;
    }

    static double weightOf(List<BoxPlacement> placements) {
        double kg = 0.0;
        for (BoxPlacement p : placements) {
            kg += p.weightKg();
        }
        return kg;
    }

    static double volume(List<BoxPlacement> pl) {
        double v = 0.0;
        for (BoxPlacement p : pl) {
            v += p.volume();
        }
        return v;
    }

    /**
     * {@code _pack_seeded}: GRASP-fill {@code bulk} into the space left after {@code seeds} (pinned cargo and reserved
     * corners), restarting with fresh seeds until every carton is in or the budget is spent; keeps the fullest.
     *
     * <p>BR-10: the pinned cargo's weight counts against the payload (the original passed no cap).</p>
     */
    Packed packSeeded(Bin bin, List<Box> bulk, List<BoxPlacement> seeds, double budget, long seed) {
        if (bulk.isEmpty()) {
            return new Packed(List.of(), List.of());
        }
        int[] totals = new int[bulk.size()];
        int totalUnits = 0;
        for (int i = 0; i < bulk.size(); i++) {
            totals[i] = Math.max(0, bulk.get(i).quantity);
            totalUnits += totals[i];
        }
        if (totalUnits == 0) {
            return new Packed(List.of(), List.of());
        }
        double mind = minDim(bulk);
        SupportGrid baseGrid = new SupportGrid();
        List<double[]> baseSpaces = new ArrayList<>();
        baseSpaces.add(new double[] {0.0, 0.0, 0.0, bin.w, bin.h, bin.d});
        for (BoxPlacement p : seeds) {
            baseGrid.register(p);
            baseSpaces = BlockFiller.update(baseSpaces, p.region(), mind);
            clock.evaluated(6L * baseSpaces.size() + 1);
        }
        double baseWeight = weightOf(seeds);
        // A restart copies the pinned cargo's grid and seeds a fresh generator: counted, because a vehicle that is
        // already full makes each restart cheap to search and a budget counted in candidates alone would allow
        // thousands of them.
        long restartCost = baseGrid.size() + baseSpaces.size() + RNG_SEED_COST;

        List<BoxPlacement> bestPl = new ArrayList<>();
        List<Integer> bestIdx = new ArrayList<>();
        double bestV = -1.0;
        double start = clock.now();
        int restart = 0;
        do {
            clock.evaluated(restartCost);
            PyRandom r = rng(seed + restart * 7919L);
            double alpha = restart == 0 ? 0.0 : r.uniform(0.02, 0.5);
            restart++;
            List<BoxPlacement> pl = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            BlockFiller.recreate(bin, bulk, r, alpha, mind, new ArrayList<>(baseSpaces), baseGrid.copy(), pl, idx,
                    totals.clone(), KitSolver.SUPPORT_MIN, bin.payloadKg, new double[] {baseWeight}, clock, spaceChoice);
            double v = 0.0;
            for (BoxPlacement p : pl) {
                v += p.volume();
            }
            if (v > bestV) {
                bestPl = pl;
                bestIdx = idx;
                bestV = v;
            }
        } while (clock.now() - start < budget && bestPl.size() < totalUnits);
        return new Packed(bestPl, bestIdx);
    }

    /** One greedy construction of {@code _place_kits}: the parts laid lowest-then-deepest around the obstacles. */
    private List<BoxPlacement> constructKits(Bin bin, List<Box> kitBoxes, List<BoxPlacement> obstacles, PyRandom r,
                                             double alpha) {
        double mind = minDim(kitBoxes);
        SupportGrid grid = new SupportGrid();
        List<double[]> spaces = new ArrayList<>();
        spaces.add(new double[] {0.0, 0.0, 0.0, bin.w, bin.h, bin.d});
        for (BoxPlacement p : obstacles) {
            grid.register(p);
            spaces = BlockFiller.update(spaces, p.region(), mind);
            clock.evaluated(6L * spaces.size() + 1);
        }
        int[] remaining = new int[kitBoxes.size()];
        for (int i = 0; i < kitBoxes.size(); i++) {
            remaining[i] = Math.max(0, kitBoxes.get(i).quantity);
        }
        List<BoxPlacement> placed = new ArrayList<>();
        BlockFiller.recreate(bin, kitBoxes, r, alpha, mind, spaces, grid, placed, new ArrayList<>(), remaining,
                KitSolver.SUPPORT_MIN, bin.payloadKg, new double[] {weightOf(obstacles)}, clock, spaceChoice);
        return placed;
    }

    /**
     * {@code _place_kits} followed by {@code _trim_to_complete_sets}, as one step: lay one vehicle's share of sets
     * ({@code row[j]} sets of declared set {@code j}) and report the sets that did not fit, which roll over.
     *
     * <p><b>The kit-bypass fix.</b> The original laid the parts with a single greedy pass (fixed seed, no restart)
     * and left the restarting search to the loose cartons alone. When every carton belongs to a set there are no loose
     * cartons, so the search never ran at all: the one pass left a part out, the trim dropped its whole set into the
     * next vehicle, and nothing afterwards could merge it back (the tail consolidation skips vehicles holding sets).
     * Here the parts get the same restarting search as loose cartons, first try identical to the original's pass, and
     * stop as soon as every part is in.</p>
     *
     * <p>A second defect is fixed on the way: the original trimmed an incomplete set by dropping placements after the
     * fact, which could remove a carton another part was resting on. Here only whole sets are ever laid: when a share
     * does not fit, it is laid set by set ({@link #setBySet}), so every kept part rests on what is really there
     * (BR-10) and the vehicle keeps as many complete sets as fit.</p>
     */
    KitShare placeKitShare(Bin bin, List<KitSets.Expanded> expanded, int[] row, List<BoxPlacement> obstacles,
                                   double budget) {
        if (KitSets.sum(row) == 0) {
            return new KitShare(List.of(), row.clone());
        }
        List<Box> boxes = KitSets.kitMemberBoxes(expanded, row);
        List<BoxPlacement> whole = bestLayout(bin, boxes, obstacles, budget, expanded, row);
        if (whole.size() == KitSets.units(boxes)) {
            return new KitShare(KitSets.bySet(expanded, row, whole), new int[row.length]);
        }
        return setBySet(bin, expanded, row, obstacles, budget);
    }

    /**
     * The share does not fit whole: lay it one set at a time, each around the sets already laid, and keep every set
     * that went in complete. A set that does not fit ends that declared set's share; the rest roll over.
     *
     * <p>Trimming the whole share's layout (the original's way, and this port's first) could end with no complete set
     * at all - a greedy pass that stacks one part on another (A on A) leaves every B out - and roll every set to the
     * next vehicle, which then does the same: nothing planned. Set by set, a vehicle always keeps as many complete sets
     * as it can hold, whatever the parts' sizes.</p>
     */
    private KitShare setBySet(Bin bin, List<KitSets.Expanded> expanded, int[] row, List<BoxPlacement> obstacles,
                              double budget) {
        List<BoxPlacement> laid = new ArrayList<>();
        int[] placed = new int[row.length];
        double perSet = budget / Math.max(1, KitSets.sum(row));
        for (int j = 0; j < row.length; j++) {
            for (int k = 0; k < row[j]; k++) {
                int[] one = new int[row.length];
                one[j] = 1;
                List<Box> boxes = KitSets.kitMemberBoxes(expanded, one);
                List<BoxPlacement> seeds = new ArrayList<>(obstacles);
                seeds.addAll(laid);
                List<BoxPlacement> got = bestLayout(bin, boxes, seeds, perSet, expanded, one);
                if (got.size() != KitSets.units(boxes)) {
                    break;
                }
                laid.addAll(KitSets.bySet(expanded, one, got));
                placed[j]++;
            }
        }
        int[] rollover = new int[row.length];
        for (int j = 0; j < row.length; j++) {
            rollover[j] = row[j] - placed[j];
        }
        return new KitShare(laid, rollover);
    }

    /**
     * The original's {@code _place_kits} pass (seed 4242, pure greedy), then restarts until every part is in or the
     * budget is spent; the layout with the most complete sets, then the most volume.
     */
    private List<BoxPlacement> bestLayout(Bin bin, List<Box> boxes, List<BoxPlacement> obstacles, double budget,
                                          List<KitSets.Expanded> expanded, int[] row) {
        int want = KitSets.units(boxes);
        List<BoxPlacement> best = constructKits(bin, boxes, obstacles, rng(4242), 0.0);
        int bestSets = KitSets.completeSets(expanded, row, best);
        double start = clock.now();
        int restart = 1;
        while (best.size() < want && clock.now() - start < budget) {
            clock.evaluated(RNG_SEED_COST);
            PyRandom r = rng(4242 + restart * 7919L);
            restart++;
            List<BoxPlacement> cand = constructKits(bin, boxes, obstacles, r, r.uniform(0.02, 0.5));
            int sets = KitSets.completeSets(expanded, row, cand);
            if (sets > bestSets || (sets == bestSets && volume(cand) > volume(best))) {
                best = cand;
                bestSets = sets;
            }
        }
        return best;
    }

    record KitShare(List<BoxPlacement> placements, int[] rollover) { }
}
