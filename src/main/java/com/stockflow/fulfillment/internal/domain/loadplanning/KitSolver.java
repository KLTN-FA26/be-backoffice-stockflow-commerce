package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of {@code solver_3d_kit.py}: load cartons into vehicles keeping every declared set's parts in equal numbers per
 * vehicle, with the GRASP block filler of {@code solver_3d.py} doing the packing.
 *
 * <p>Each Python function has a method of the same name in camel case; the PR body maps them one to one. The
 * departures from the original are each marked where they happen and are of three kinds: the hard rules of docs 09
 * (BR-10 payload and support on every packing call, BR-12 vehicle counts), the kit-bypass fix in
 * {@link #placeKitShare}, and the work clock that makes the run reproducible ({@link WorkClock}).</p>
 */
final class KitSolver {

    private final WorkClock clock;
    private final SeededPacker packer;
    private final TailPasses tails;
    /** Stop a search as soon as it cannot improve (see the early stops); off only to compare with the original. */
    private final boolean earlyStop;

    KitSolver(WorkClock clock, long seedOffset, boolean earlyStop) {
        this.clock = clock;
        this.packer = new SeededPacker(clock, seedOffset);
        this.tails = new TailPasses(clock, packer);
        this.earlyStop = earlyStop;
    }


    static final double SUPPORT_MIN = 0.98;
    /** The original's runaway guard on vehicles in one drive. */
    static final int MAX_BINS = 64;
    /** The original's cap on copies of one type in a mixed fleet. */
    static final int MAX_PER_TYPE_IN_FLEET = 32;

    record EqualizeStats(boolean applied, int moves, double spreadBefore, double spreadAfter) {
        static final EqualizeStats NONE = new EqualizeStats(false, 0, 0.0, 0.0);
    }

    /** The original's {@code meta}. */
    record Meta(String algorithm, int setsTotal, int minVehicles, Integer targetVehicles, boolean targetUnreachable,
                String distribution, List<Map<String, Integer>> kitBalance, List<KitWarning> warnings,
                String combo, EqualizeStats equalize) { }

    /** The original's {@code Nesting3DResult}, minus what the planner derives itself. */
    record Outcome(List<BinLoad> placed, List<Box> leftover, Meta meta) {

        int leftoverUnits() {
            int n = 0;
            for (Box b : leftover) {
                n += b.quantity;
            }
            return n;
        }

        double utilization() {
            double used = 0.0;
            double capacity = 0.0;
            for (BinLoad l : placed) {
                used += l.usedVolume();
                capacity += l.bin().volume();
            }
            return capacity > 0 ? used / capacity * 100.0 : 0.0;
        }
    }

    // ------------------------------------------------------------------ sequential drive

    record Drive(List<BinLoad> placed, List<Box> leftover) { }

    /**
     * {@code _sequential_pack}: fill vehicles one at a time - reserved corners and the vehicle's share of sets first,
     * then the loose cartons around them - and carry what is left to the next.
     *
     * @param bins    a fixed fleet, packed in this order; null to clone {@code container}
     * @param maxBins BR-12: the most vehicles the drive may open when cloning (the original opened up to 64, whatever
     *                the vehicle count)
     */
    Drive sequentialPack(Bin container, List<Box> bulkBoxes, List<KitSets.Expanded> expanded, List<int[]> perContSets,
                         double perBinBudget, long seed, List<Bin> bins, int maxBins) {
        List<BinLoad> placed = new ArrayList<>();
        List<Box> remaining = new ArrayList<>();
        for (Box b : bulkBoxes) {
            if (b.quantity > 0) {
                remaining.add(b);
            }
        }
        List<int[]> plans = new ArrayList<>();
        for (int[] r : perContSets) {
            plans.add(r.clone());
        }
        int ci = 0;
        while (ci < maxBins) {
            if (bins != null && ci >= bins.size()) {
                break;
            }
            boolean hasKits = ci < plans.size() && KitSets.sum(plans.get(ci)) > 0;
            boolean hasBulk = KitSets.units(remaining) > 0;
            if (!hasKits && !hasBulk) {
                break;
            }
            Bin one = bins != null ? bins.get(ci) : container;
            if (bins != null && !remaining.isEmpty() && ci + 1 < bins.size()) {
                List<Bin> later = bins.subList(ci + 1, bins.size());
                List<Box> sorted = new ArrayList<>(remaining);
                sorted.sort(Comparator.comparingInt(b -> {
                    for (Bin c : later) {
                        if (KitSets.fitsIn(b, c)) {
                            return 1;
                        }
                    }
                    return 0;
                }));
                remaining = sorted;
            }
            List<BoxPlacement> gussets = Gussets.of(one);
            List<BoxPlacement> kitPl = List.of();
            if (ci < plans.size() && KitSets.sum(plans.get(ci)) > 0) {
                SeededPacker.KitShare share = packer.placeKitShare(one, expanded, plans.get(ci), gussets, perBinBudget);
                kitPl = share.placements();
                int[] rollover = share.rollover();
                if (KitSets.sum(rollover) > 0) {
                    int[] row = plans.get(ci);
                    for (int j = 0; j < row.length; j++) {
                        row[j] -= rollover[j];
                    }
                    if (ci + 1 < plans.size()) {
                        int[] nextRow = plans.get(ci + 1);
                        for (int j = 0; j < nextRow.length; j++) {
                            nextRow[j] += rollover[j];
                        }
                    } else {
                        plans.add(rollover);
                    }
                }
            }
            List<BoxPlacement> seeds = new ArrayList<>(kitPl);
            seeds.addAll(gussets);
            SeededPacker.Packed bulk = packer.packSeeded(one, remaining, seeds, perBinBudget, seed + ci * 31L);
            List<BoxPlacement> placements = new ArrayList<>(kitPl);
            placements.addAll(bulk.placements());
            if (placements.isEmpty()) {
                if (bins != null) {
                    ci++;
                    continue;
                }
                break;
            }
            placed.add(BinLoad.of(one, placements));
            remaining = KitSets.take(remaining, bulk.index());
            ci++;
        }
        if (ci < plans.size()) {
            for (int j = 0; j < expanded.size(); j++) {
                int pending = 0;
                for (int r = ci; r < plans.size(); r++) {
                    pending += j < plans.get(r).length ? plans.get(r)[j] : 0;
                }
                if (pending <= 0) {
                    continue;
                }
                KitSets.Expanded e = expanded.get(j);
                for (Map.Entry<String, Box> m : e.members().entrySet()) {
                    remaining.add(m.getValue().withQuantity(pending * e.spec().members().get(m.getKey())));
                }
            }
        }
        return new Drive(placed, remaining);
    }

    /** The docs' order of merit (09 §4.2): fewest cartons left, then fewest vehicles, then least empty space. */
    static Comparator<Outcome> docsOrder() {
        return Comparator.<Outcome>comparingInt(Outcome::leftoverUnits)
                .thenComparingInt(o -> o.placed().size())
                .thenComparingDouble(KitSolver::waste);
    }

    static double waste(Outcome o) {
        double w = 0.0;
        for (BinLoad l : o.placed()) {
            w += l.bin().volume() - l.usedVolume();
        }
        return w;
    }

    /**
     * {@code solve_3d_kit}. One type is the classic drive (cloned up to its vehicle count, BR-12); several types are a
     * fixed fleet ({@link #solveKitFleet}).
     *
     * @param types       one bin per vehicle type, with its count in {@code spec.availableCount()}
     * @param targetN     the vehicle count wanted, or null for the fewest that fit
     * @param fillScoring fill cartons, used by the fleet driver only to rank candidates by how much fill they take
     */
    Outcome solve(List<Bin> types, List<Box> boxes, List<KitSpec> kits, Integer targetN, double budget, int attempts,
                  KitDistribution distribute, List<Box> fillScoring, boolean equalize) {
        List<Bin> fleet = new ArrayList<>();
        for (Bin b : types) {
            if (b.spec.availableCount() > 0) {
                fleet.add(b);
            }
        }
        if (fleet.isEmpty() || boxes.isEmpty()) {
            return new Outcome(new ArrayList<>(), new ArrayList<>(boxes), new Meta("GRASP_KIT", 0, 0, targetN, false,
                    distribute.name(), List.of(), List.of(), null, EqualizeStats.NONE));
        }
        if (fleet.size() > 1) {
            List<Bin> bins = new ArrayList<>();
            for (Bin c : fleet) {
                for (int i = 0; i < Math.min(c.spec.availableCount(), MAX_PER_TYPE_IN_FLEET); i++) {
                    bins.add(c);
                }
            }
            return solveKitFleet(bins, boxes, kits, Math.max(4.0, budget), distribute, fillScoring, equalize);
        }
        Bin container = fleet.get(0);

        if (distribute == KitDistribution.AUTO) {
            double half = Math.max(4.0, budget / 2);
            List<Outcome> cand = new ArrayList<>();
            for (KitDistribution p : List.of(KitDistribution.FIRST, KitDistribution.EVEN)) {
                Outcome o = solve(types, boxes, kits, targetN, half, attempts, p, fillScoring, false);
                cand.add(o);
                // Early stops (not in the original): FIRST already left nothing and used the volume floor, and EVEN
                // can at best tie it - ties keep FIRST anyway; or there are no sets, and FIRST and EVEN are the same
                // drive with the same seeds.
                if (earlyStop && (o.leftoverUnits() == 0 && o.placed().size() <= o.meta().minVehicles()
                        || o.meta().setsTotal() == 0)) {
                    break;
                }
            }
            // The original ranked by the number of leftover LINES; the docs count cartons.
            cand.sort(Comparator.<Outcome>comparingInt(Outcome::leftoverUnits)
                    .thenComparingInt(o -> o.placed().size())
                    .thenComparingDouble(o -> -o.utilization()));
            Outcome best = cand.get(0);
            Meta m = best.meta();
            best = new Outcome(best.placed(), best.leftover(), new Meta(m.algorithm(), m.setsTotal(), m.minVehicles(),
                    m.targetVehicles(), m.targetUnreachable(), "AUTO->" + m.distribution(), m.kitBalance(),
                    m.warnings(), m.combo(), m.equalize()));
            if (equalize) {
                Set<String> skus = new LinkedHashSet<>();
                best.meta().kitBalance().forEach(b -> skus.addAll(b.keySet()));
                skus.addAll(KitSets.kitSkus(KitSets.splitKitsAndBulk(boxes, kits).expanded()));
                TailPasses.Equalized eq = tails.equalizeBins(new ArrayList<>(best.placed()), skus,
                        Math.min(10.0, Math.max(4.0, budget * 0.3)));
                m = best.meta();
                best = new Outcome(eq.placed(), best.leftover(), new Meta(m.algorithm(), m.setsTotal(),
                        m.minVehicles(), m.targetVehicles(), m.targetUnreachable(), m.distribution(),
                        KitSets.balance(eq.placed(), skus), m.warnings(), m.combo(), eq.stats()));
            }
            return best;
        }

        KitSets.Split split = KitSets.splitKitsAndBulk(boxes, kits);
        int sets = 0;
        for (KitSets.Expanded e : split.expanded()) {
            sets += e.sets();
        }
        double contVol = container.volume();
        double totalVol = KitSets.totalVolume(boxes);
        int nFloor = Math.max(1, (int) Math.ceil(totalVol / contVol));
        int nTarget = targetN != null ? Math.max(nFloor, targetN) : nFloor;
        int cap = Math.min(MAX_BINS, container.spec.availableCount());
        double perBinBudget = Math.max(1.5, budget / Math.max(1, attempts * (nTarget + 2)));

        Drive best = null;
        double[] bestKey = null;
        double deadline = clock.now() + budget;
        for (int a = 0; a < Math.max(1, attempts); a++) {
            if (a > 0 && clock.now() >= deadline) {
                break;
            }
            PyRandom r = packer.rng(20260728L + a * 104729L);
            List<Box> order = new ArrayList<>(split.bulk());
            if (a == 1) {
                order.sort(Comparator.comparingDouble(b -> -(b.w * b.h * b.d)));
            } else if (a == 2) {
                order.sort(Comparator.comparingDouble(b -> b.w * b.h * b.d));
            } else if (a >= 3) {
                r.shuffle(order);
            }
            List<int[]> perCont = KitSets.planSets(split.expanded(), nTarget, distribute);
            Drive d = sequentialPack(container, order, split.expanded(), perCont, perBinBudget, 1000 + a * 7L, null,
                    cap);
            int leftUnits = KitSets.units(d.leftover());
            List<Double> fills = new ArrayList<>();
            for (BinLoad l : d.placed()) {
                fills.add(l.usedVolume() / contVol);
            }
            double early = 0.0;
            if (fills.size() > 1) {
                fills.sort(Comparator.reverseOrder());
                for (int i = 0; i < fills.size() - 1; i++) {
                    early -= fills.get(i);
                }
            }
            double[] key = {leftUnits, d.placed().size(), early};
            if (bestKey == null || compare(key, bestKey) < 0) {
                bestKey = key;
                best = d;
                clock.improved();
            }
            // Early stop (not in the original): nothing left and the volume floor reached - no later order can
            // use fewer vehicles, so the rest of the budget could only reshuffle the same plan.
            if (earlyStop && bestKey[0] == 0 && bestKey[1] <= nFloor) {
                break;
            }
        }
        java.util.Objects.requireNonNull(best, "the first attempt always runs");
        List<BinLoad> placed = tails.consolidateTail(new ArrayList<>(best.placed()), split.expanded(), 8.0);
        Set<String> skus = KitSets.kitSkus(split.expanded());
        EqualizeStats eqStats = null;
        if (equalize) {
            TailPasses.Equalized eq = tails.equalizeBins(placed, skus, Math.min(8.0, Math.max(3.0, budget * 0.25)));
            placed = eq.placed();
            eqStats = eq.stats();
        }
        boolean unreachable = targetN != null && placed.size() > targetN;
        return new Outcome(placed, best.leftover(), new Meta("GRASP_KIT", sets, nFloor, targetN, unreachable,
                distribute.name(), KitSets.balance(placed, skus), split.warnings(), null,
                eqStats == null ? EqualizeStats.NONE : eqStats));
    }

    private static int compare(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            int c = Double.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private record Combo(String tag, List<Bin> seq, List<int[]> plan, List<Box> order) { }

    private record Scanned(double[] score, int combo, Drive drive) { }

    /**
     * {@code _solve_kit_fleet}: a fixed mixed fleet. Screens every (vehicle order x distribution x carton order) combo
     * with a shallow search, re-runs the best two with the real budget, then consolidates the tail and salvages
     * leftovers.
     *
     * <p>Ranking follows the docs (09 §4.2: fewest cartons left, fewest vehicles, least empty space). The original
     * ranked by leftover volume, then leftover count, then loaded volume, which never preferred fewer vehicles.</p>
     */
    Outcome solveKitFleet(List<Bin> bins, List<Box> boxes, List<KitSpec> kits, double budget,
                          KitDistribution distribute, List<Box> fillScoring, boolean equalize) {
        KitSets.Split split = KitSets.splitKitsAndBulk(boxes, kits);
        List<KitSets.Expanded> expanded = split.expanded();
        int sets = 0;
        for (KitSets.Expanded e : expanded) {
            sets += e.sets();
        }
        Set<String> skus = KitSets.kitSkus(expanded);

        double totalVol = KitSets.totalVolume(boxes);
        List<Double> descVols = new ArrayList<>();
        for (Bin c : bins) {
            descVols.add(c.volume());
        }
        descVols.sort(Comparator.reverseOrder());
        double acc = 0.0;
        int nFloor = bins.size();
        for (int i = 0; i < descVols.size(); i++) {
            acc += descVols.get(i);
            if (acc >= totalVol) {
                nFloor = i + 1;
                break;
            }
        }

        List<Bin> bigFirst = new ArrayList<>(bins);
        bigFirst.sort(Comparator.comparingDouble(c -> -c.volume()));
        List<Map.Entry<String, List<Bin>>> binOrders = new ArrayList<>();
        binOrders.add(Map.entry("big", bigFirst));
        Set<Double> distinctVols = new LinkedHashSet<>(descVols);
        if (distinctVols.size() > 1) {
            List<Bin> rev = new ArrayList<>(bigFirst);
            Collections.reverse(rev);
            binOrders.add(Map.entry("rev", rev));
        }
        // With no sets the two distributions are the same drive; screening both only doubles the work (not in the
        // original, which screened both regardless).
        List<KitDistribution> dists = distribute == KitDistribution.AUTO && (!earlyStop || sets > 0)
                ? List.of(KitDistribution.FIRST, KitDistribution.EVEN) : List.of(distribute == KitDistribution.AUTO
                ? KitDistribution.FIRST : distribute);
        List<Combo> combos = new ArrayList<>();
        List<Box> bulk = split.bulk();
        for (Map.Entry<String, List<Bin>> bo : binOrders) {
            for (KitDistribution dist : dists) {
                List<int[]> pcs = KitSets.plansFor(expanded, bo.getValue(), dist);
                PyRandom r = packer.rng(97 + combos.size() * 13L);
                List<Box> shuffled = new ArrayList<>(bulk);
                r.shuffle(shuffled);
                List<Box> big = new ArrayList<>(bulk);
                big.sort(Comparator.comparingDouble(b -> -KitSets.vol(b)));
                List<Box> small = new ArrayList<>(bulk);
                small.sort(Comparator.comparingDouble(KitSets::vol));
                String prefix = bo.getKey() + "/" + dist.name().toLowerCase(java.util.Locale.ROOT) + "/";
                combos.add(new Combo(prefix + "nat", bo.getValue(), pcs, new ArrayList<>(bulk)));
                combos.add(new Combo(prefix + "big", bo.getValue(), pcs, big));
                combos.add(new Combo(prefix + "small", bo.getValue(), pcs, small));
                combos.add(new Combo(prefix + "shuf", bo.getValue(), pcs, shuffled));
            }
        }

        double deadline = clock.now() + budget;
        double salvageBudget = Math.min(3.0, budget * 0.12);
        double perBinA = Math.max(0.35, (budget * 0.4) / Math.max(1, combos.size() * bins.size()));
        List<Scanned> scanned = new ArrayList<>();
        for (int k = 0; k < combos.size(); k++) {
            if (!scanned.isEmpty() && clock.now() >= deadline - budget * 0.5 - salvageBudget) {
                break;
            }
            Combo c = combos.get(k);
            Drive d = sequentialPack(c.seq().get(0), c.order(), expanded, c.plan(), perBinA, 1000 + k * 7L,
                    c.seq(), MAX_BINS);
            double[] s = fleetScore(d);
            scanned.add(new Scanned(s, k, d));
            // The original stopped at the first combo that left nothing behind, which can miss a combo using fewer
            // vehicles. Keep comparing - by cartons left, then vehicles (fleetScore) - while the budget lasts; stop
            // early only when nothing is left on the volume floor, which no combo can beat.
            if (!earlyStop ? s[0] == 0 && fillScoring.isEmpty()
                    : s[0] == 0 && s[1] <= nFloor && fillScoring.isEmpty()) {
                break;
            }
        }
        scanned.sort((a, b) -> compare(a.score(), b.score()));

        List<Scanned> top = scanned.subList(0, Math.min(2, scanned.size()));
        Drive best = null;
        double[] bestKey = null;
        String bestTag = "";
        for (int rank = 0; rank < top.size(); rank++) {
            Scanned sa = top.get(rank);
            Combo c = combos.get(sa.combo());
            List<Map.Entry<double[], Drive>> cand = new ArrayList<>();
            cand.add(Map.entry(sa.score(), sa.drive()));
            double tLeft = deadline - salvageBudget - clock.now();
            if (tLeft > 0.8 * bins.size()) {
                double perBinB = Math.max(0.8, tLeft / Math.max(1, top.size() - rank) / Math.max(1, bins.size()));
                Drive db = sequentialPack(c.seq().get(0), c.order(), expanded, c.plan(), perBinB,
                        5000 + sa.combo() * 11L, c.seq(), MAX_BINS);
                cand.add(Map.entry(fleetScore(db), db));
            }
            for (Map.Entry<double[], Drive> e : cand) {
                double[] s = e.getKey();
                double[] ext = {s[0], s[1], -fillGain(e.getValue().placed(), fillScoring), s[2]};
                if (bestKey == null || compare(ext, bestKey) < 0) {
                    bestKey = ext;
                    best = e.getValue();
                    bestTag = c.tag();
                    clock.improved();
                }
            }
        }
        List<BinLoad> placed = best == null ? new ArrayList<>() : new ArrayList<>(best.placed());
        List<Box> leftover = best == null ? bulk : best.leftover();
        placed = tails.consolidateTail(placed, expanded, 8.0);
        Drive salvaged = tails.salvageLeftovers(placed, leftover, skus, salvageBudget);
        placed = new ArrayList<>(salvaged.placed());
        leftover = salvaged.leftover();
        EqualizeStats eqStats = EqualizeStats.NONE;
        if (equalize) {
            TailPasses.Equalized eq = tails.equalizeBins(placed, skus, Math.min(8.0, Math.max(3.0, budget * 0.25)));
            placed = eq.placed();
            eqStats = eq.stats();
        }
        return new Outcome(placed, leftover, new Meta("GRASP_KIT_FLEET", sets, nFloor, null, false,
                distribute.name(), KitSets.balance(placed, skus), split.warnings(), bestTag, eqStats));
    }

    private static double[] fleetScore(Drive d) {
        double waste = 0.0;
        for (BinLoad l : d.placed()) {
            waste += l.bin().volume() - l.usedVolume();
        }
        return new double[] {KitSets.units(d.leftover()), d.placed().size(), waste};
    }

    /**
     * {@code fill_gain}: how much of the fill pool the plan's free pockets take. Unlike the original, it does not
     * count vehicles the plan leaves empty: fill never gets a vehicle of its own (docs 09 "hàng phụ chỉ chèn vào chỗ
     * trống còn lại").
     */
    private double fillGain(List<BinLoad> placed, List<Box> fill) {
        if (fill.isEmpty()) {
            return 0.0;
        }
        List<Box> pool = new ArrayList<>(fill);
        double gain = 0.0;
        for (BinLoad l : placed) {
            if (pool.isEmpty()) {
                break;
            }
            EmsFill.Topped t = EmsFill.fillIntoContainer(l.bin(), l.placements(), l.usedWeight(), pool, clock);
            pool = t.leftover();
            for (BoxPlacement p : t.extra()) {
                gain += p.volume();
            }
        }
        return gain;
    }
}
