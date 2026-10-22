package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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

    static final double SUPPORT_MIN = 0.98;
    /** The original's runaway guard on vehicles in one drive. */
    static final int MAX_BINS = 64;
    /** The original's cap on copies of one type in a mixed fleet. */
    static final int MAX_PER_TYPE_IN_FLEET = 32;

    /** A declared set the list can build: how many, and the carton line of each part. */
    record Expanded(KitSpec spec, int sets, LinkedHashMap<String, Box> members) { }

    record Split(List<Expanded> expanded, List<Box> bulk, List<KitWarning> warnings) { }

    /** Cartons placed in one vehicle and, for each, the index of the line it came from. */
    record Packed(List<BoxPlacement> placements, List<Integer> index) { }

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

    private final WorkClock clock;
    private final long seedOffset;
    /** Stop a search as soon as it cannot improve (see the two early stops); off only to compare with the original. */
    private final boolean earlyStop;

    KitSolver(WorkClock clock, long seedOffset, boolean earlyStop) {
        this.clock = clock;
        this.seedOffset = seedOffset;
        this.earlyStop = earlyStop;
    }

    /** Every fixed seed of the original, shifted by the caller's seed: seed 0 draws exactly what Python drew. */
    private PyRandom rng(long seed) {
        return new PyRandom(seed + seedOffset);
    }

    private static double vol(Box b) {
        return b.w * b.h * b.d;
    }

    private static int units(List<Box> boxes) {
        int n = 0;
        for (Box b : boxes) {
            n += b.quantity;
        }
        return n;
    }

    // ------------------------------------------------------------------ packing primitives

    private static double minDim(List<Box> boxes) {
        double m = Double.POSITIVE_INFINITY;
        for (Box b : boxes) {
            m = Math.min(m, Math.min(b.w, Math.min(b.h, b.d)));
        }
        return m;
    }

    private static double weightOf(List<BoxPlacement> placements) {
        double kg = 0.0;
        for (BoxPlacement p : placements) {
            kg += p.weightKg();
        }
        return kg;
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
        }
        double baseWeight = weightOf(seeds);

        List<BoxPlacement> bestPl = new ArrayList<>();
        List<Integer> bestIdx = new ArrayList<>();
        double bestV = -1.0;
        double start = clock.now();
        int restart = 0;
        do {
            PyRandom r = rng(seed + restart * 7919L);
            double alpha = restart == 0 ? 0.0 : r.uniform(0.02, 0.5);
            restart++;
            List<BoxPlacement> pl = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            BlockFiller.recreate(bulk, r, alpha, mind, new ArrayList<>(baseSpaces), baseGrid.copy(), pl, idx,
                    totals.clone(), SUPPORT_MIN, bin.payloadKg, new double[] {baseWeight}, clock);
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
        }
        int[] remaining = new int[kitBoxes.size()];
        for (int i = 0; i < kitBoxes.size(); i++) {
            remaining[i] = Math.max(0, kitBoxes.get(i).quantity);
        }
        List<BoxPlacement> placed = new ArrayList<>();
        BlockFiller.recreate(kitBoxes, r, alpha, mind, spaces, grid, placed, new ArrayList<>(), remaining,
                SUPPORT_MIN, bin.payloadKg, new double[] {0.0}, clock);
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
     * does not fit, the sets that did are laid again on their own, so every kept part rests on what is really
     * there (BR-10).</p>
     */
    private KitShare placeKitShare(Bin bin, List<Expanded> expanded, int[] row, List<BoxPlacement> obstacles,
                                   double budget) {
        int[] target = row.clone();
        while (sum(target) > 0) {
            List<Box> boxes = kitMemberBoxes(expanded, target);
            int want = units(boxes);
            List<BoxPlacement> best = constructKits(bin, boxes, obstacles, rng(4242), 0.0);
            int bestSets = completeSets(expanded, target, best);
            double start = clock.now();
            int restart = 1;
            while (best.size() < want && clock.now() - start < budget) {
                PyRandom r = rng(4242 + restart * 7919L);
                restart++;
                List<BoxPlacement> cand = constructKits(bin, boxes, obstacles, r, r.uniform(0.02, 0.5));
                int sets = completeSets(expanded, target, cand);
                if (sets > bestSets || (sets == bestSets && volume(cand) > volume(best))) {
                    best = cand;
                    bestSets = sets;
                }
            }
            if (best.size() == want) {
                int[] rollover = new int[row.length];
                for (int j = 0; j < row.length; j++) {
                    rollover[j] = row[j] - target[j];
                }
                return new KitShare(bySet(expanded, target, best), rollover);
            }
            int[] fitted = completeRow(expanded, target, best);
            if (sum(fitted) >= sum(target)) {
                fitted = target.clone();
                for (int j = fitted.length - 1; j >= 0; j--) {
                    if (fitted[j] > 0) {
                        fitted[j]--;
                        break;
                    }
                }
            }
            target = fitted;
        }
        return new KitShare(List.of(), row.clone());
    }

    private record KitShare(List<BoxPlacement> placements, int[] rollover) { }

    /**
     * The keeping half of {@code _trim_to_complete_sets}, for a share that fitted whole: the parts listed set by set,
     * part by part, as the original returned them. The order matters - the loose cartons' free spaces are carved in
     * it - so it is kept even though nothing is dropped. Each set takes its own cartons of a shared part (the
     * original handed every set the same first ones).
     */
    private static List<BoxPlacement> bySet(List<Expanded> expanded, int[] row, List<BoxPlacement> kitPl) {
        Map<String, List<BoxPlacement>> bySku = new LinkedHashMap<>();
        for (BoxPlacement p : kitPl) {
            bySku.computeIfAbsent(p.partName(), k -> new ArrayList<>()).add(p);
        }
        Map<String, Integer> cursor = new HashMap<>();
        List<BoxPlacement> keep = new ArrayList<>();
        for (int j = 0; j < expanded.size(); j++) {
            int planned = j < row.length ? row[j] : 0;
            if (planned <= 0) {
                continue;
            }
            Expanded e = expanded.get(j);
            for (String sku : e.members().keySet()) {
                int from = cursor.getOrDefault(sku, 0);
                int n = planned * e.spec().members().get(sku);
                keep.addAll(bySku.getOrDefault(sku, List.of()).subList(from, from + n));
                cursor.put(sku, from + n);
            }
        }
        return keep;
    }

    private static double volume(List<BoxPlacement> pl) {
        double v = 0.0;
        for (BoxPlacement p : pl) {
            v += p.volume();
        }
        return v;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) {
            s += v;
        }
        return s;
    }

    /** The counting half of {@code _trim_to_complete_sets}: whole sets per declared set among the placed parts. */
    static int[] completeRow(List<Expanded> expanded, int[] planRow, List<BoxPlacement> kitPl) {
        Map<String, Integer> bySku = new HashMap<>();
        for (BoxPlacement p : kitPl) {
            bySku.merge(p.partName(), 1, Integer::sum);
        }
        int[] out = new int[expanded.size()];
        for (int j = 0; j < expanded.size(); j++) {
            int planned = j < planRow.length ? planRow[j] : 0;
            if (planned <= 0) {
                continue;
            }
            Expanded e = expanded.get(j);
            int complete = Integer.MAX_VALUE;
            for (String s : e.members().keySet()) {
                complete = Math.min(complete, bySku.getOrDefault(s, 0) / e.spec().members().get(s));
            }
            out[j] = Math.min(complete, planned);
        }
        return out;
    }

    private static int completeSets(List<Expanded> expanded, int[] row, List<BoxPlacement> pl) {
        return sum(completeRow(expanded, row, pl));
    }

    // ------------------------------------------------------------------ kit accounting

    /**
     * {@code _split_kits_and_bulk}: how many whole sets the list can build, and the loose cartons left over.
     *
     * <p>Fixed: the original measured every set against the full stock of a part, so two sets sharing a part both
     * counted the same cartons and the plan held cartons that do not exist. Each set here draws from what the
     * previous ones left.</p>
     */
    static Split splitKitsAndBulk(List<Box> boxes, List<KitSpec> kits) {
        Map<String, Box> bySku = new HashMap<>();
        Map<String, Integer> avail = new LinkedHashMap<>();
        for (Box b : boxes) {
            bySku.put(b.partName(), b);
            avail.merge(b.partName(), Math.max(0, b.quantity), Integer::sum);
        }
        List<KitWarning> warnings = new ArrayList<>();
        Map<String, Integer> consumed = new HashMap<>();
        List<Expanded> expanded = new ArrayList<>();
        for (KitSpec spec : kits) {
            LinkedHashMap<String, Integer> members = new LinkedHashMap<>();
            spec.members().forEach((s, c) -> {
                if (c != null && c > 0 && s != null && !s.isEmpty() && avail.containsKey(s)) {
                    members.put(s, c);
                }
            });
            if (members.size() < 2) {
                warnings.add(new KitWarning(spec.name(), KitWarning.Type.FEWER_THAN_TWO_PARTS, null, 0));
                continue;
            }
            int sets = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> m : members.entrySet()) {
                sets = Math.min(sets, avail.get(m.getKey()) / m.getValue());
            }
            if (sets == 0) {
                warnings.add(new KitWarning(spec.name(), KitWarning.Type.NO_COMPLETE_SET, null, 0));
                continue;
            }
            LinkedHashMap<String, Box> memberBoxes = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> m : members.entrySet()) {
                String s = m.getKey();
                int used = sets * m.getValue();
                consumed.merge(s, used, Integer::sum);
                avail.merge(s, -used, Integer::sum);
                if (avail.get(s) > 0) {
                    warnings.add(new KitWarning(spec.name(), KitWarning.Type.SURPLUS_PARTS, s, avail.get(s)));
                }
                memberBoxes.put(s, bySku.get(s));
            }
            expanded.add(new Expanded(new KitSpec(spec.name(), members), sets, memberBoxes));
        }
        List<Box> bulk = new ArrayList<>();
        for (Box b : boxes) {
            int left = Math.max(0, b.quantity) - consumed.getOrDefault(b.partName(), 0);
            if (left > 0) {
                bulk.add(b.withQuantity(left));
            }
        }
        return new Split(expanded, bulk, warnings);
    }

    /** {@code _kit_member_boxes}: the part lines for {@code row[j]} sets of each declared set. */
    static List<Box> kitMemberBoxes(List<Expanded> expanded, int[] row) {
        List<Box> out = new ArrayList<>();
        for (int j = 0; j < expanded.size() && j < row.length; j++) {
            int k = row[j];
            if (k <= 0) {
                continue;
            }
            Expanded e = expanded.get(j);
            for (Map.Entry<String, Box> m : e.members().entrySet()) {
                out.add(m.getValue().withQuantity(k * e.spec().members().get(m.getKey())));
            }
        }
        return out;
    }

    /** {@code _distribute}: sets per vehicle - all in the first, or spread evenly. */
    static int[] distribute(int sets, int vehicles, KitDistribution policy) {
        if (vehicles <= 0) {
            return new int[0];
        }
        int[] out = new int[vehicles];
        if (policy == KitDistribution.FIRST) {
            out[0] = sets;
            return out;
        }
        int base = sets / vehicles;
        int extra = sets % vehicles;
        for (int i = 0; i < vehicles; i++) {
            out[i] = base + (i < extra ? 1 : 0);
        }
        return out;
    }

    /** {@code _distribute_weighted}: sets per vehicle in proportion to volume, largest remainder first. */
    static int[] distributeWeighted(int sets, double[] vols, KitDistribution policy) {
        int n = vols.length;
        if (n <= 0) {
            return new int[0];
        }
        int[] base = new int[n];
        if (policy == KitDistribution.FIRST) {
            base[0] = sets;
            return base;
        }
        double tot = 0.0;
        for (double v : vols) {
            tot += v;
        }
        if (tot == 0.0) {
            tot = 1.0;
        }
        double[] quotas = new double[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            quotas[i] = sets * vols[i] / tot;
            base[i] = (int) quotas[i];
            assigned += base[i];
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> -(quotas[i] - base[i])));
        for (int k = 0; k < sets - assigned && k < order.size(); k++) {
            base[order.get(k)]++;
        }
        return base;
    }

    /** {@code _fits_in}: whether the carton can possibly sit in this vehicle in a legal orientation. */
    static boolean fitsIn(Box b, Bin c) {
        if (b.type.rotatable) {
            double[] bd = {b.w, b.h, b.d};
            double[] cd = {c.w, c.h, c.d};
            java.util.Arrays.sort(bd);
            java.util.Arrays.sort(cd);
            return bd[0] <= cd[0] && bd[1] <= cd[1] && bd[2] <= cd[2];
        }
        return b.w <= c.w && b.h <= c.h && b.d <= c.d;
    }

    /** {@code _plan_sets}: {@code result[ci][j]} sets of declared set {@code j} in vehicle {@code ci}. */
    static List<int[]> planSets(List<Expanded> expanded, int vehicles, KitDistribution policy) {
        List<int[]> perSpec = new ArrayList<>();
        for (Expanded e : expanded) {
            perSpec.add(distribute(e.sets(), vehicles, policy));
        }
        List<int[]> rows = new ArrayList<>();
        for (int ci = 0; ci < vehicles; ci++) {
            int[] r = new int[expanded.size()];
            for (int j = 0; j < expanded.size(); j++) {
                r[j] = perSpec.get(j)[ci];
            }
            rows.add(r);
        }
        return rows;
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
    Drive sequentialPack(Bin container, List<Box> bulkBoxes, List<Expanded> expanded, List<int[]> perContSets,
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
            boolean hasKits = ci < plans.size() && sum(plans.get(ci)) > 0;
            boolean hasBulk = units(remaining) > 0;
            if (!hasKits && !hasBulk) {
                break;
            }
            Bin one = bins != null ? bins.get(ci) : container;
            if (bins != null && !remaining.isEmpty() && ci + 1 < bins.size()) {
                List<Bin> later = bins.subList(ci + 1, bins.size());
                List<Box> sorted = new ArrayList<>(remaining);
                sorted.sort(Comparator.comparingInt(b -> {
                    for (Bin c : later) {
                        if (fitsIn(b, c)) {
                            return 1;
                        }
                    }
                    return 0;
                }));
                remaining = sorted;
            }
            List<BoxPlacement> gussets = Gussets.of(one);
            List<BoxPlacement> kitPl = List.of();
            if (ci < plans.size() && sum(plans.get(ci)) > 0) {
                KitShare share = placeKitShare(one, expanded, plans.get(ci), gussets, perBinBudget);
                kitPl = share.placements();
                int[] rollover = share.rollover();
                if (sum(rollover) > 0) {
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
            Packed bulk = packSeeded(one, remaining, seeds, perBinBudget, seed + ci * 31L);
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
            remaining = take(remaining, bulk.index());
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
                Expanded e = expanded.get(j);
                for (Map.Entry<String, Box> m : e.members().entrySet()) {
                    remaining.add(m.getValue().withQuantity(pending * e.spec().members().get(m.getKey())));
                }
            }
        }
        return new Drive(placed, remaining);
    }

    /** Remove the placed units ({@code index} into {@code boxes}) from the lines. */
    private static List<Box> take(List<Box> boxes, List<Integer> index) {
        int[] took = new int[boxes.size()];
        for (int j : index) {
            took[j]++;
        }
        List<Box> out = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            int left = boxes.get(i).quantity - took[i];
            if (left > 0) {
                out.add(boxes.get(i).withQuantity(left));
            }
        }
        return out;
    }

    /** {@code _agg_lines} / {@code _aggregate}: placements back into lines, by type and placed sizes. */
    static List<Box> aggregate(List<BoxPlacement> pls) {
        Map<List<Object>, Box> agg = new LinkedHashMap<>();
        Map<List<Object>, Integer> counts = new LinkedHashMap<>();
        for (BoxPlacement p : pls) {
            List<Object> key = List.of(p.type, p.w, p.h, p.d);
            agg.putIfAbsent(key, new Box(p.type, p.w, p.h, p.d, 0));
            counts.merge(key, 1, Integer::sum);
        }
        List<Box> out = new ArrayList<>();
        agg.forEach((k, b) -> out.add(b.withQuantity(counts.get(k))));
        return out;
    }

    private static Set<String> kitSkus(List<Expanded> expanded) {
        Set<String> s = new LinkedHashSet<>();
        for (Expanded e : expanded) {
            s.addAll(e.members().keySet());
        }
        return s;
    }

    private static List<BoxPlacement> concat(List<BoxPlacement> a, List<BoxPlacement> b) {
        List<BoxPlacement> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * {@code _consolidate_tail}: dissolve a sparse, set-free last vehicle into the earlier ones - first into their free
     * pockets with their cargo pinned, then by re-packing one earlier vehicle from scratch with it. All or nothing.
     */
    List<BinLoad> consolidateTail(List<BinLoad> placed, List<Expanded> expanded, double budget) {
        Set<String> kitSkus = kitSkus(expanded);
        double deadline = clock.now() + budget;
        while (placed.size() > 1 && clock.now() < deadline) {
            BinLoad last = placed.get(placed.size() - 1);
            boolean hasKit = false;
            for (BoxPlacement p : last.placements()) {
                hasKit |= kitSkus.contains(p.partName());
            }
            if (last.placements().isEmpty() || last.usedVolume() > last.bin().volume() * 0.5 || hasKit) {
                break;
            }
            List<Box> moving = aggregate(last.placements());

            double p1Deadline = Math.min(deadline, clock.now() + budget * 0.4);
            List<Map.Entry<Integer, List<BoxPlacement>>> adds = new ArrayList<>();
            List<Box> remaining = new ArrayList<>(moving);
            for (int i = 0; i < placed.size() - 1; i++) {
                if (remaining.isEmpty() || clock.now() >= p1Deadline) {
                    break;
                }
                BinLoad one = placed.get(i);
                Packed got = packSeeded(one.bin(), remaining, concat(one.placements(), Gussets.of(one.bin())),
                        Math.min(1.0, Math.max(0.3, p1Deadline - clock.now())), 777 + i * 13L);
                if (!got.placements().isEmpty()) {
                    adds.add(Map.entry(i, got.placements()));
                    remaining = take(remaining, got.index());
                }
            }
            if (remaining.isEmpty()) {
                for (Map.Entry<Integer, List<BoxPlacement>> add : adds) {
                    BinLoad one = placed.get(add.getKey());
                    placed.set(add.getKey(), BinLoad.of(one.bin(), concat(one.placements(), add.getValue())));
                }
                placed.remove(placed.size() - 1);
                clock.improved();
                continue;
            }

            boolean merged = false;
            List<Integer> byFill = new ArrayList<>();
            for (int i = 0; i < placed.size() - 1; i++) {
                byFill.add(i);
            }
            byFill.sort(Comparator.comparingDouble(i -> placed.get(i).usedVolume()));
            for (int i : byFill.subList(0, Math.min(2, byFill.size()))) {
                if (clock.now() >= deadline) {
                    break;
                }
                BinLoad one = placed.get(i);
                List<Box> pool = aggregate(concat(one.placements(), last.placements()));
                int n = units(pool);
                Packed got = packSeeded(one.bin(), pool, Gussets.of(one.bin()),
                        Math.min(4.0, Math.max(1.0, deadline - clock.now())), 4111 + i * 17L);
                if (got.placements().size() == n) {
                    placed.set(i, BinLoad.of(one.bin(), got.placements()));
                    placed.remove(placed.size() - 1);
                    clock.improved();
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                break;
            }
        }
        return placed;
    }

    /**
     * {@code _equalize_bins}: even out the fill across the vehicles without changing their number, by moving the
     * smallest loose cartons from the fullest vehicle to the emptiest. Each round is all or nothing, so it never
     * makes the plan worse; set parts never move.
     */
    record Equalized(List<BinLoad> placed, EqualizeStats stats) { }

    Equalized equalizeBins(List<BinLoad> placed, Set<String> kitSkus, double budget) {
        if (placed.size() < 2 || budget <= 0) {
            return new Equalized(placed, EqualizeStats.NONE);
        }
        double[] f0 = fills(placed);
        double spreadBefore = round2(max(f0) - min(f0));
        double deadline = clock.now() + budget;
        int fails = 0;
        int moves = 0;
        boolean applied = false;
        Set<Integer> banned = new LinkedHashSet<>();
        while (clock.now() < deadline) {
            double[] f = fills(placed);
            int poor = 0;
            for (int i = 1; i < f.length; i++) {
                if (f[i] < f[poor]) {
                    poor = i;
                }
            }
            List<Integer> richOrder = new ArrayList<>();
            for (int i = 0; i < placed.size(); i++) {
                if (i != poor && !banned.contains(i)) {
                    richOrder.add(i);
                }
            }
            richOrder.sort(Comparator.comparingDouble(i -> -f[i]));
            if (richOrder.isEmpty()) {
                break;
            }
            int rich = richOrder.get(0);
            if (f[rich] - f[poor] <= 6.0) {
                break;
            }
            BinLoad r = placed.get(rich);
            BinLoad p = placed.get(poor);
            double usedAll = 0.0;
            double capAll = 0.0;
            for (BinLoad l : placed) {
                usedAll += l.usedVolume();
                capAll += l.bin().volume();
            }
            double target = usedAll / capAll * 100.0;
            double excess = (f[rich] - target) / 100.0 * r.bin().volume();
            double deficit = (target - f[poor]) / 100.0 * p.bin().volume();
            double want = Math.max(0.0, Math.min(excess, deficit)) / (1 + fails);
            List<BoxPlacement> movable = new ArrayList<>();
            for (BoxPlacement b : r.placements()) {
                if (!kitSkus.contains(b.partName())) {
                    movable.add(b);
                }
            }
            movable.sort(Comparator.comparingDouble(BoxPlacement::volume));
            List<BoxPlacement> moving = new ArrayList<>();
            double acc = 0.0;
            for (BoxPlacement b : movable) {
                if (acc >= want || moving.size() >= 60) {
                    break;
                }
                moving.add(b);
                acc += b.volume();
            }
            if (moving.isEmpty()) {
                banned.add(rich);
                fails = 0;
                continue;
            }
            Set<BoxPlacement> moveIds = Collections.newSetFromMap(new IdentityHashMap<>());
            moveIds.addAll(moving);
            Packed add = packSeeded(p.bin(), aggregate(moving), concat(p.placements(), Gussets.of(p.bin())),
                    Math.min(2.0, Math.max(0.5, deadline - clock.now())), 60601 + moves * 17L);
            if (add.placements().size() < moving.size()) {
                fails++;
                if (fails >= 2) {
                    banned.add(rich);
                    fails = 0;
                }
                continue;
            }
            List<BoxPlacement> rest = new ArrayList<>();
            for (BoxPlacement b : r.placements()) {
                if (!moveIds.contains(b)) {
                    rest.add(b);
                }
            }
            Packed re = packSeeded(r.bin(), aggregate(rest), Gussets.of(r.bin()),
                    Math.min(3.0, Math.max(0.8, deadline - clock.now())), 70707 + moves * 13L);
            if (re.placements().size() < rest.size()) {
                fails++;
                if (fails >= 2) {
                    banned.add(rich);
                    fails = 0;
                }
                continue;
            }
            placed.set(rich, BinLoad.of(r.bin(), re.placements()));
            placed.set(poor, BinLoad.of(p.bin(), concat(p.placements(), add.placements())));
            moves++;
            applied = true;
            fails = 0;
            banned.clear();
        }
        double[] f1 = fills(placed);
        return new Equalized(placed, new EqualizeStats(applied, moves, spreadBefore, round2(max(f1) - min(f1))));
    }

    private static double[] fills(List<BinLoad> placed) {
        double[] f = new double[placed.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = placed.get(i).usedVolume() / placed.get(i).bin().volume() * 100.0;
        }
        return f;
    }

    private static double max(double[] a) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : a) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static double min(double[] a) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : a) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** {@code _salvage_leftovers}: seat loose leftovers in any vehicle's free pockets; set parts stay out. */
    Drive salvageLeftovers(List<BinLoad> placed, List<Box> leftover, Set<String> kitSkus, double budget) {
        List<Box> todo = new ArrayList<>();
        List<Box> keep = new ArrayList<>();
        for (Box b : leftover) {
            if (b.quantity <= 0) {
                continue;
            }
            (kitSkus.contains(b.partName()) ? keep : todo).add(b);
        }
        if (todo.isEmpty() || budget <= 0 || placed.isEmpty()) {
            return new Drive(placed, leftover.stream().filter(b -> b.quantity > 0).toList());
        }
        double deadline = clock.now() + budget;
        for (int i = 0; i < placed.size(); i++) {
            if (todo.isEmpty() || clock.now() >= deadline) {
                break;
            }
            BinLoad one = placed.get(i);
            Packed got = packSeeded(one.bin(), todo, concat(one.placements(), Gussets.of(one.bin())),
                    Math.min(0.8, Math.max(0.3, deadline - clock.now())), 31337 + i * 13L);
            if (!got.placements().isEmpty()) {
                placed.set(i, BinLoad.of(one.bin(), concat(one.placements(), got.placements())));
                todo = take(todo, got.index());
            }
        }
        List<Box> out = new ArrayList<>(keep);
        out.addAll(todo);
        return new Drive(placed, out);
    }

    // ------------------------------------------------------------------ drivers

    private static List<Map<String, Integer>> balance(List<BinLoad> placed, Set<String> kitSkus) {
        List<Map<String, Integer>> out = new ArrayList<>();
        for (BinLoad l : placed) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (BoxPlacement p : l.placements()) {
                if (kitSkus.contains(p.partName())) {
                    counts.merge(p.partName(), 1, Integer::sum);
                }
            }
            out.add(counts);
        }
        return out;
    }

    private static double totalVolume(List<Box> boxes) {
        double v = 0.0;
        for (Box b : boxes) {
            v += vol(b) * b.quantity;
        }
        return v;
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
                // Early stop (not in the original): FIRST already left nothing and used the volume floor, and EVEN
                // can at best tie it - ties keep FIRST anyway.
                if (earlyStop && o.leftoverUnits() == 0 && o.placed().size() <= o.meta().minVehicles()) {
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
                skus.addAll(kitSkus(splitKitsAndBulk(boxes, kits).expanded()));
                Equalized eq = equalizeBins(new ArrayList<>(best.placed()), skus,
                        Math.min(10.0, Math.max(4.0, budget * 0.3)));
                m = best.meta();
                best = new Outcome(eq.placed(), best.leftover(), new Meta(m.algorithm(), m.setsTotal(),
                        m.minVehicles(), m.targetVehicles(), m.targetUnreachable(), m.distribution(),
                        balance(eq.placed(), skus), m.warnings(), m.combo(), eq.stats()));
            }
            return best;
        }

        Split split = splitKitsAndBulk(boxes, kits);
        int sets = 0;
        for (Expanded e : split.expanded()) {
            sets += e.sets();
        }
        double contVol = container.volume();
        double totalVol = totalVolume(boxes);
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
            PyRandom r = rng(20260728L + a * 104729L);
            List<Box> order = new ArrayList<>(split.bulk());
            if (a == 1) {
                order.sort(Comparator.comparingDouble(b -> -(b.w * b.h * b.d)));
            } else if (a == 2) {
                order.sort(Comparator.comparingDouble(b -> b.w * b.h * b.d));
            } else if (a >= 3) {
                r.shuffle(order);
            }
            List<int[]> perCont = planSets(split.expanded(), nTarget, distribute);
            Drive d = sequentialPack(container, order, split.expanded(), perCont, perBinBudget, 1000 + a * 7L, null,
                    cap);
            int leftUnits = units(d.leftover());
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
        List<BinLoad> placed = consolidateTail(new ArrayList<>(best.placed()), split.expanded(), 8.0);
        Set<String> skus = kitSkus(split.expanded());
        EqualizeStats eqStats = null;
        if (equalize) {
            Equalized eq = equalizeBins(placed, skus, Math.min(8.0, Math.max(3.0, budget * 0.25)));
            placed = eq.placed();
            eqStats = eq.stats();
        }
        boolean unreachable = targetN != null && placed.size() > targetN;
        return new Outcome(placed, best.leftover(), new Meta("GRASP_KIT", sets, nFloor, targetN, unreachable,
                distribute.name(), balance(placed, skus), split.warnings(), null,
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
        Split split = splitKitsAndBulk(boxes, kits);
        List<Expanded> expanded = split.expanded();
        int sets = 0;
        for (Expanded e : expanded) {
            sets += e.sets();
        }
        Set<String> skus = kitSkus(expanded);

        double totalVol = totalVolume(boxes);
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
        List<KitDistribution> dists = distribute == KitDistribution.AUTO
                ? List.of(KitDistribution.FIRST, KitDistribution.EVEN) : List.of(distribute);
        List<Combo> combos = new ArrayList<>();
        List<Box> bulk = split.bulk();
        for (Map.Entry<String, List<Bin>> bo : binOrders) {
            for (KitDistribution dist : dists) {
                List<int[]> pcs = plansFor(expanded, bo.getValue(), dist);
                PyRandom r = rng(97 + combos.size() * 13L);
                List<Box> shuffled = new ArrayList<>(bulk);
                r.shuffle(shuffled);
                List<Box> big = new ArrayList<>(bulk);
                big.sort(Comparator.comparingDouble(b -> -vol(b)));
                List<Box> small = new ArrayList<>(bulk);
                small.sort(Comparator.comparingDouble(KitSolver::vol));
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
            if (s[0] == 0 && fillScoring.isEmpty()) {
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
        placed = consolidateTail(placed, expanded, 8.0);
        Drive salvaged = salvageLeftovers(placed, leftover, skus, salvageBudget);
        placed = new ArrayList<>(salvaged.placed());
        leftover = salvaged.leftover();
        EqualizeStats eqStats = EqualizeStats.NONE;
        if (equalize) {
            Equalized eq = equalizeBins(placed, skus, Math.min(8.0, Math.max(3.0, budget * 0.25)));
            placed = eq.placed();
            eqStats = eq.stats();
        }
        return new Outcome(placed, leftover, new Meta("GRASP_KIT_FLEET", sets, nFloor, null, false,
                distribute.name(), balance(placed, skus), split.warnings(), bestTag, eqStats));
    }

    private static List<int[]> plansFor(List<Expanded> expanded, List<Bin> seq, KitDistribution policy) {
        double[] vols = new double[seq.size()];
        for (int i = 0; i < vols.length; i++) {
            vols[i] = seq.get(i).volume();
        }
        List<int[]> perSpec = new ArrayList<>();
        for (Expanded e : expanded) {
            perSpec.add(distributeWeighted(e.sets(), vols, policy));
        }
        List<int[]> rows = new ArrayList<>();
        for (int ci = 0; ci < seq.size(); ci++) {
            int[] r = new int[expanded.size()];
            for (int j = 0; j < expanded.size(); j++) {
                r[j] = perSpec.get(j)[ci];
            }
            rows.add(r);
        }
        return rows;
    }

    private static double[] fleetScore(Drive d) {
        double waste = 0.0;
        for (BinLoad l : d.placed()) {
            waste += l.bin().volume() - l.usedVolume();
        }
        return new double[] {units(d.leftover()), d.placed().size(), waste};
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
