package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The set and line accounting of {@code solver_3d_kit.py}: how many whole sets the cartons build, how sets are spread
 * over vehicles, which placed parts make whole sets, and the small line arithmetic the drivers share. Stateless.
 */
final class KitSets {

    private KitSets() {
    }

    /** A declared set the list can build: how many, and the carton line of each part. */
    record Expanded(KitSpec spec, int sets, LinkedHashMap<String, Box> members) { }

    record Split(List<Expanded> expanded, List<Box> bulk, List<KitWarning> warnings) { }

    static double vol(Box b) {
        return b.w * b.h * b.d;
    }

    static int units(List<Box> boxes) {
        int n = 0;
        for (Box b : boxes) {
            n += b.quantity;
        }
        return n;
    }

    static int sum(int[] a) {
        int s = 0;
        for (int v : a) {
            s += v;
        }
        return s;
    }

    /**
     * The keeping half of {@code _trim_to_complete_sets}, for a share that fitted whole: the parts listed set by set,
     * part by part, as the original returned them. The order matters - the loose cartons' free spaces are carved in
     * it - so it is kept even though nothing is dropped. Each set takes its own cartons of a shared part (the
     * original handed every set the same first ones).
     */
    static List<BoxPlacement> bySet(List<Expanded> expanded, int[] row, List<BoxPlacement> kitPl) {
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

    static int completeSets(List<Expanded> expanded, int[] row, List<BoxPlacement> pl) {
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
        // The original compared sorted sizes; with a door (LP3-DOOR) the orientation matters, so each allowed one is
        // tried against the box and the door - the same answer when there is no door.
        for (double[] o : b.orientations()) {
            if (o[0] <= c.w && o[1] <= c.h && o[2] <= c.d && c.passesDoor(o[0], o[1])) {
                return true;
            }
        }
        return false;
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

    static List<int[]> plansFor(List<Expanded> expanded, List<Bin> seq, KitDistribution policy) {
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

    static Set<String> kitSkus(List<Expanded> expanded) {
        Set<String> s = new LinkedHashSet<>();
        for (Expanded e : expanded) {
            s.addAll(e.members().keySet());
        }
        return s;
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

    /** Remove the placed units ({@code index} into {@code boxes}) from the lines. */
    static List<Box> take(List<Box> boxes, List<Integer> index) {
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

    static List<BoxPlacement> concat(List<BoxPlacement> a, List<BoxPlacement> b) {
        List<BoxPlacement> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    static double totalVolume(List<Box> boxes) {
        double v = 0.0;
        for (Box b : boxes) {
            v += vol(b) * b.quantity;
        }
        return v;
    }

    // ------------------------------------------------------------------ drivers

    static List<Map<String, Integer>> balance(List<BinLoad> placed, Set<String> kitSkus) {
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
}
