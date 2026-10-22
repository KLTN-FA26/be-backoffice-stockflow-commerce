package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@code solver_3d_kit.py} does to a plan once its vehicles are packed: dissolve a sparse last vehicle into the
 * others ({@code _consolidate_tail}), even out the fill ({@code _equalize_bins}), and seat leftovers in free pockets
 * ({@code _salvage_leftovers}). Set parts never move.
 */
final class TailPasses {

    private final WorkClock clock;
    private final SeededPacker packer;

    TailPasses(WorkClock clock, SeededPacker packer) {
        this.clock = clock;
        this.packer = packer;
    }


    /**
     * {@code _equalize_bins}: even out the fill across the vehicles without changing their number, by moving the
     * smallest loose cartons from the fullest vehicle to the emptiest. Each round is all or nothing, so it never
     * makes the plan worse; set parts never move.
     */
    record Equalized(List<BinLoad> placed, KitSolver.EqualizeStats stats) { }

    /**
     * {@code _consolidate_tail}: dissolve a sparse, set-free last vehicle into the earlier ones - first into their free
     * pockets with their cargo pinned, then by re-packing one earlier vehicle from scratch with it. All or nothing.
     */
    List<BinLoad> consolidateTail(List<BinLoad> placed, List<KitSets.Expanded> expanded, double budget) {
        Set<String> kitSkus = KitSets.kitSkus(expanded);
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
            List<Box> moving = KitSets.aggregate(last.placements());

            double p1Deadline = Math.min(deadline, clock.now() + budget * 0.4);
            List<Map.Entry<Integer, List<BoxPlacement>>> adds = new ArrayList<>();
            List<Box> remaining = new ArrayList<>(moving);
            for (int i = 0; i < placed.size() - 1; i++) {
                if (remaining.isEmpty() || clock.now() >= p1Deadline) {
                    break;
                }
                BinLoad one = placed.get(i);
                SeededPacker.Packed got = packer.packSeeded(one.bin(), remaining, KitSets.concat(one.placements(), Gussets.of(one.bin())),
                        Math.min(1.0, Math.max(0.3, p1Deadline - clock.now())), 777 + i * 13L);
                if (!got.placements().isEmpty()) {
                    adds.add(Map.entry(i, got.placements()));
                    remaining = KitSets.take(remaining, got.index());
                }
            }
            if (remaining.isEmpty()) {
                for (Map.Entry<Integer, List<BoxPlacement>> add : adds) {
                    BinLoad one = placed.get(add.getKey());
                    placed.set(add.getKey(), BinLoad.of(one.bin(), KitSets.concat(one.placements(), add.getValue())));
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
                List<Box> pool = KitSets.aggregate(KitSets.concat(one.placements(), last.placements()));
                int n = KitSets.units(pool);
                SeededPacker.Packed got = packer.packSeeded(one.bin(), pool, Gussets.of(one.bin()),
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

    Equalized equalizeBins(List<BinLoad> placed, Set<String> kitSkus, double budget) {
        if (placed.size() < 2 || budget <= 0) {
            return new Equalized(placed, KitSolver.EqualizeStats.NONE);
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
            SeededPacker.Packed add = packer.packSeeded(p.bin(), KitSets.aggregate(moving), KitSets.concat(p.placements(), Gussets.of(p.bin())),
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
            SeededPacker.Packed re = packer.packSeeded(r.bin(), KitSets.aggregate(rest), Gussets.of(r.bin()),
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
            placed.set(poor, BinLoad.of(p.bin(), KitSets.concat(p.placements(), add.placements())));
            moves++;
            applied = true;
            fails = 0;
            banned.clear();
        }
        double[] f1 = fills(placed);
        return new Equalized(placed, new KitSolver.EqualizeStats(applied, moves, spreadBefore, round2(max(f1) - min(f1))));
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
    KitSolver.Drive salvageLeftovers(List<BinLoad> placed, List<Box> leftover, Set<String> kitSkus, double budget) {
        List<Box> todo = new ArrayList<>();
        List<Box> keep = new ArrayList<>();
        for (Box b : leftover) {
            if (b.quantity <= 0) {
                continue;
            }
            (kitSkus.contains(b.partName()) ? keep : todo).add(b);
        }
        if (todo.isEmpty() || budget <= 0 || placed.isEmpty()) {
            return new KitSolver.Drive(placed, leftover.stream().filter(b -> b.quantity > 0).toList());
        }
        double deadline = clock.now() + budget;
        for (int i = 0; i < placed.size(); i++) {
            if (todo.isEmpty() || clock.now() >= deadline) {
                break;
            }
            BinLoad one = placed.get(i);
            SeededPacker.Packed got = packer.packSeeded(one.bin(), todo, KitSets.concat(one.placements(), Gussets.of(one.bin())),
                    Math.min(0.8, Math.max(0.3, deadline - clock.now())), 31337 + i * 13L);
            if (!got.placements().isEmpty()) {
                placed.set(i, BinLoad.of(one.bin(), KitSets.concat(one.placements(), got.placements())));
                todo = KitSets.take(todo, got.index());
            }
        }
        List<Box> out = new ArrayList<>(keep);
        out.addAll(todo);
        return new KitSolver.Drive(placed, out);
    }
}
