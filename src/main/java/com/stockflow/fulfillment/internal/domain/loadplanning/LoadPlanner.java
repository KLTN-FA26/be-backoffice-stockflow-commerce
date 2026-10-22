package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * The 3D load planner (kltn-docs 09 §4.2, SCRUM-391): turns cartons and vehicle types into vehicles with every carton's
 * position, or a reason it was left out.
 *
 * <p>The search is the kit solver ported from the lead's earlier Python tool ({@link KitSolver}); this class is the
 * part the original did not have: the docs' input and output (opaque {@code ref}s, unplaced reasons, fill
 * percentages, the load order), the selection modes, the fill top-up, and the guarantees - every carton is placed or
 * unplaced exactly once, and every vehicle passes {@link PlacementValidator} before the plan is returned.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class LoadPlanner {

    /**
     * Candidate evaluations per budget-second ({@link WorkClock.Unit#EVALUATION}): how the original's budget
     * proportions turn into work. A laptop under load evaluates 56-130 million a second, so a budget-second is 25-55 ms
     * of real time: the original's minimum budgets (1.5 s a vehicle, 8 s for the tail, 4 s for a fleet) add up to more
     * budget-seconds than the caller's budget, and this rate keeps that inside the real budget with room for a slower
     * server. Measured 22/10 on 100-800 cartons with up to 150 sizes (LoadPlannerBenchmarkTest).
     */
    static final double TICKS_PER_SECOND = 3_000_000.0;

    /** The original's number of carton orders tried per vehicle type in the single-type drive. */
    static final int ATTEMPTS = 6;

    /**
     * The quick pass's rate: so slow that one tick outlasts any budget, so every loop of the original that waits on the
     * clock runs its first round and stops - one construction per vehicle, one carton order, no polishing.
     */
    static final double QUICK_TICKS_PER_SECOND = 1e-9;

    private final double ticksPerSecond;

    public LoadPlanner() {
        this(TICKS_PER_SECOND);
    }

    /** For the benchmark: the same search at another work rate. */
    LoadPlanner(double ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
    }

    /** What a carton type is keyed by: cartons equal on all of these pack alike (see {@link #plan}). */
    private record TypeKey(String partKey, int lengthMm, int widthMm, int heightMm, boolean rotatable,
                           boolean fill) {
        static TypeKey of(CargoPackage c, String partKey) {
            return new TypeKey(partKey, c.lengthMm(), c.widthMm(), c.heightMm(), c.rotatable(), c.fill());
        }
    }

    /** Separates a grouped carton's part code from its set's composition in an internal part key. */
    static final String SET_MARK = "\u00a7";

    /** One solve: the outcome and the vehicle types its unplaced cartons are judged against. */
    private record Solved(KitSolver.Outcome outcome, List<Bin> scope) { }

    public LoadPlanResult plan(LoadPlanRequest request) {
        long startNanos = System.nanoTime();
        List<CargoPackage> cartons = request.cartons();
        checkUnique(cartons, request.vehicleTypes());
        long deadline = startNanos + request.budget().toNanos();
        double budget = request.budget().toNanos() / 1e9;

        List<Bin> usable = new ArrayList<>();
        for (VehicleSpec t : request.vehicleTypes()) {
            if (t.availableCount() > 0) {
                usable.add(new Bin(t));
            }
        }

        // Cartons no usable vehicle can ever take are reported, not offered: they would only cost search time, and a
        // set part that can never load must not hold its set-mates back (they load loose instead).
        Map<String, UnplacedReason> unplaced = new LinkedHashMap<>();
        for (CargoPackage c : cartons) {
            UnplacedReason r = classifyAlone(c, request.vehicleTypes());
            if (r != null) {
                unplaced.put(c.ref(), r);
            }
        }

        // Cartons that pack alike share a type: same part, sizes, rotatable flag and priority. Weight is left out on
        // purpose - measured cartons all weigh a little differently, and splitting on it would turn every carton
        // into a type of its own and the block filler into a one-by-one filler. The type carries its heaviest
        // carton's weight, so the payload check during the search can only be stricter than the real one.
        // Physical sets: a carton in a kit group packs under a part key that also names its set's composition, so
        // sets of different make-up never draw on each other's cartons, and each composition is one declared set.
        List<KitWarning> warnings = new ArrayList<>();
        Map<String, List<Integer>> groups = kitGroups(cartons, unplaced, warnings);
        Map<Integer, String> partKeys = partKeys(cartons, groups);
        Map<TypeKey, ItemType> typesByKey = new LinkedHashMap<>();
        Map<TypeKey, BigDecimal> heaviest = new LinkedHashMap<>();
        for (int i = 0; i < cartons.size(); i++) {
            CargoPackage c = cartons.get(i);
            if (!unplaced.containsKey(c.ref())) {
                heaviest.merge(TypeKey.of(c, partKeys.get(i)), c.weightKg(), BigDecimal::max);
            }
        }
        Map<Integer, ItemType> typeOf = new HashMap<>();
        for (int i = 0; i < cartons.size(); i++) {
            CargoPackage c = cartons.get(i);
            if (unplaced.containsKey(c.ref())) {
                continue;
            }
            String key = partKeys.get(i);
            ItemType t = typesByKey.computeIfAbsent(TypeKey.of(c, key), k -> new ItemType(typesByKey.size(), key,
                    heaviest.get(k).doubleValue(), c.rotatable(), c.widthMm(), c.heightMm(), c.lengthMm()));
            t.cartons.add(i);
            typeOf.put(i, t);
        }
        List<Box> main = new ArrayList<>();
        List<Box> fill = new ArrayList<>();
        Map<ItemType, Boolean> fillType = new HashMap<>();
        typesByKey.forEach((k, t) -> {
            fillType.put(t, k.fill());
            (k.fill() ? fill : main).add(t.box(t.cartons.size()));
        });
        List<KitSpec> kits = consistentKits(declaredKits(request.kits(), main), main, warnings);
        kits.addAll(kitsFromGroups(groups, partKeys));

        // Anytime: a quick pass (one construction wherever the original would restart) gives a complete plan
        // whatever the load; the full search then has what is left of the budget to beat it. A full search that
        // finishes inside the budget is what gets returned, and is reproducible.
        WorkClock quickClock = new WorkClock(WorkClock.Unit.EVALUATION, QUICK_TICKS_PER_SECOND, deadline);
        Solved quick = solve(request, usable, main, fill, kits, budget, quickClock);
        WorkClock clock = new WorkClock(WorkClock.Unit.EVALUATION, ticksPerSecond, deadline);
        Solved chosen = quick;
        if (!clock.expired()) {
            Solved full = solve(request, usable, main, fill, kits, budget, clock);
            if (KitSolver.docsOrder().compare(full.outcome(), quick.outcome()) <= 0) {
                chosen = full;
            }
        }
        if (chosen == quick) {
            clock.improvedAt(quickClock.lastImprovementNanos());
        }
        KitSolver.Outcome outcome = chosen.outcome();
        List<Bin> scope = chosen.scope();

        // "Ưu tiên + hàng chèn": fill only tops up vehicles the main cargo opened.
        List<BinLoad> loads = new ArrayList<>(outcome.placed());
        List<Box> fillLeft = new ArrayList<>(fill);
        for (int i = 0; i < loads.size() && !fillLeft.isEmpty(); i++) {
            BinLoad l = loads.get(i);
            EmsFill.Topped t = EmsFill.fillIntoContainer(l.bin(), l.placements(), l.usedWeight(), fillLeft, clock);
            if (!t.extra().isEmpty()) {
                List<BoxPlacement> all = new ArrayList<>(l.placements());
                all.addAll(t.extra());
                loads.set(i, BinLoad.of(l.bin(), all));
            }
            fillLeft = t.leftover();
        }

        // Whether the search was cut off, fixed now: what follows (binding, checks) is bookkeeping, not search.
        boolean cutOff = clock.expired();
        Binding binding = new Binding(cartons, typesByKey.values(), groups);
        List<PlannedVehicle> vehicles = new ArrayList<>();
        List<List<PlacementValidator.Item>> items = new ArrayList<>();
        Map<UUID, Integer> usedByType = new HashMap<>();
        for (BinLoad l : loads) {
            List<PlacementValidator.Item> vehicleItems = new ArrayList<>();
            vehicles.add(vehicle(l, vehicles.size() + 1, binding, vehicleItems));
            items.add(vehicleItems);
            usedByType.merge(l.bin().spec.typeId(), 1, Integer::sum);
        }
        Set<String> grouped = new HashSet<>();
        groups.values().forEach(g -> g.forEach(i -> grouped.add(cartons.get(i).ref())));
        List<List<PlacementValidator.Item>> setItems = new ArrayList<>();
        for (List<PlacementValidator.Item> vehicle : items) {
            setItems.add(vehicle.stream().filter(it -> grouped.contains(it.carton().ref())).toList());
        }
        List<PlacementViolation> splits = PlacementValidator.validateKitGroups(setItems);
        if (!splits.isEmpty()) {
            throw new IllegalStateException("the plan splits a physical set over vehicles: " + splits);
        }
        boolean fleet = request.mode() == SelectionMode.FEWEST_VEHICLES && usable.size() > 1;
        for (int idx : binding.left()) {
            CargoPackage c = cartons.get(idx);
            UnplacedReason r = classifyLeftover(c, fillType.get(typeOf.get(idx)), scope, usedByType, fleet);
            // Room was left but the search ran out of time before it could use it: say so, not NO_SPACE.
            unplaced.put(c.ref(), r == UnplacedReason.NO_SPACE && cutOff ? UnplacedReason.SEARCH_CUT_OFF : r);
        }
        List<UnplacedPackage> left = new ArrayList<>();
        for (CargoPackage c : cartons) {
            UnplacedReason r = unplaced.get(c.ref());
            if (r != null) {
                left.add(new UnplacedPackage(c.ref(), c.sourceRef(), r));
            }
        }

        LoadPlanResult result = new LoadPlanResult(vehicles, left, meta(outcome, clock, startNanos, warnings, cutOff));
        checkPieces(cartons, result);
        return result;
    }

    private Solved solve(LoadPlanRequest request, List<Bin> usable, List<Box> main, List<Box> fill,
                         List<KitSpec> kits, double budget, WorkClock clock) {
        KitSolver solver = new KitSolver(clock, request.seed(), true);
        if (main.isEmpty() || usable.isEmpty()) {
            return new Solved(new KitSolver.Outcome(List.of(), new ArrayList<>(main), new KitSolver.Meta("GRASP_KIT",
                    0, 0, request.targetVehicleCount(), false, request.distribution().name(), List.of(), List.of(),
                    null, KitSolver.EqualizeStats.NONE)), usable);
        }
        if (usable.size() == 1) {
            return new Solved(solver.solve(usable, main, kits, request.targetVehicleCount(), budget, ATTEMPTS,
                    request.distribution(), fill, request.equalizeFill()), usable);
        }
        KitSolver.Outcome fleet = null;
        double singleBudget = budget;
        if (request.mode() == SelectionMode.FEWEST_VEHICLES) {
            // The original's minimize: a mixed fleet. A single-type plan is a mixed plan too, and the fleet search
            // spreads its screening over every vehicle on offer, so on a large fleet it can end above the best single
            // type; the single types are tried as well, with half the budget, and the best plan by the docs' order
            // of merit wins. Fewest vehicles can then never mean more vehicles than one type alone would need.
            fleet = solver.solve(usable, main, kits, request.targetVehicleCount(), budget / 2, ATTEMPTS,
                    request.distribution(), fill, request.equalizeFill());
            clock.improved();
            singleBudget = budget / 2;
        }
        // SINGLE_TYPE: the original's same_type - each type alone, the best by the docs' order of merit.
        KitSolver.Outcome best = null;
        Bin bestBin = null;
        double each = singleBudget / usable.size();
        for (Bin type : usable) {
            KitSolver.Outcome o = solver.solve(List.of(type), main, kits, request.targetVehicleCount(), each,
                    ATTEMPTS, request.distribution(), fill, request.equalizeFill());
            if (best == null || KitSolver.docsOrder().compare(o, best) < 0) {
                best = o;
                bestBin = type;
                clock.improved();
            }
        }
        if (fleet != null) {
            if (KitSolver.docsOrder().compare(fleet, best) <= 0) {
                return new Solved(fleet, usable);
            }
            return new Solved(best, usable);
        }
        return new Solved(best, List.of(bestBin));
    }

    // ------------------------------------------------------------------ physical sets

    /**
     * The cartons of each kit group with two or more offered main cartons, in request order.
     *
     * <p>A fill carton is never part of a set: fill only tops up the space the main cargo leaves, in whatever vehicle
     * has room, so it cannot promise to ride with its set-mates. A fill carton that names a kit group loads as plain
     * fill, with a {@link KitWarning.Type#FILL_IN_SET} warning, and the kit-group rule ({@code KIT_SPLIT}) does not
     * count it.</p>
     */
    private static Map<String, List<Integer>> kitGroups(List<CargoPackage> cartons,
                                                       Map<String, UnplacedReason> unplaced,
                                                       List<KitWarning> warnings) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < cartons.size(); i++) {
            CargoPackage c = cartons.get(i);
            if (c.kitGroup() == null) {
                continue;
            }
            if (c.fill()) {
                warnings.add(new KitWarning(c.kitGroup(), KitWarning.Type.FILL_IN_SET, c.partCode(), 1));
            } else if (!unplaced.containsKey(c.ref())) {
                groups.computeIfAbsent(c.kitGroup(), k -> new ArrayList<>()).add(i);
            }
        }
        groups.values().removeIf(g -> g.size() < 2);
        return groups;
    }

    /**
     * Each carton's part key: its part code; for a carton in a kit group, its part code with its measured sizes and
     * rotatable flag, and its set's make-up in the same terms (e.g. {@code "T-CB@730x730x800r§T-CB@730x730x800r*2+..."}).
     * Sealed cartons are measured one by one, so two sets of the same item can differ by a millimetre: the sizes in
     * the key keep each make-up its own set instead of one part code covering two sizes. A carton with no part code
     * is keyed by its sizes alone.
     */
    private static Map<Integer, String> partKeys(List<CargoPackage> cartons, Map<String, List<Integer>> groups) {
        Map<Integer, String> keys = new HashMap<>();
        for (int i = 0; i < cartons.size(); i++) {
            keys.put(i, cartons.get(i).partCode());
        }
        for (List<Integer> group : groups.values()) {
            Map<String, Integer> parts = new java.util.TreeMap<>();
            for (int i : group) {
                parts.merge(member(cartons.get(i)), 1, Integer::sum);
            }
            StringBuilder signature = new StringBuilder();
            parts.forEach((part, n) -> signature.append(signature.length() == 0 ? "" : "+").append(part).append('*')
                    .append(n));
            for (int i : group) {
                keys.put(i, member(cartons.get(i)) + SET_MARK + signature);
            }
        }
        return keys;
    }

    /** A set member as the solver must tell it apart: part code, measured sizes, rotatable flag. */
    private static String member(CargoPackage c) {
        return (c.partCode() == null ? "" : c.partCode()) + "@" + c.lengthMm() + "x" + c.widthMm() + "x"
                + c.heightMm() + (c.rotatable() ? "r" : "f");
    }

    /** One declared set per set composition among the kit groups: what keeps each physical set whole. */
    private static List<KitSpec> kitsFromGroups(Map<String, List<Integer>> groups, Map<Integer, String> partKeys) {
        Map<Map<String, Integer>, String> byComposition = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> g : groups.entrySet()) {
            Map<String, Integer> members = new java.util.TreeMap<>();
            for (int i : g.getValue()) {
                members.merge(partKeys.get(i), 1, Integer::sum);
            }
            if (members.size() >= 2) {
                byComposition.putIfAbsent(members, g.getKey());
            }
        }
        List<KitSpec> out = new ArrayList<>();
        byComposition.forEach((members, first) -> out.add(new KitSpec("kit-group:" + first, members)));
        return out;
    }

    /**
     * The caller's declared sets, for the cartons outside kit groups (estimated cartons, which have no physical set
     * yet). A declared set none of whose parts is among them is left out: its sets, if any, are kit groups.
     */
    private static List<KitSpec> declaredKits(List<KitSpec> declared, List<Box> main) {
        Set<String> loose = new HashSet<>();
        main.forEach(b -> loose.add(b.partName()));
        List<KitSpec> out = new ArrayList<>();
        for (KitSpec k : declared) {
            if (k.members().keySet().stream().anyMatch(loose::contains)) {
                out.add(k);
            }
        }
        return out;
    }

    /**
     * Hands the cartons of the request out to the solver's placements, vehicle by vehicle. The solver places types,
     * not cartons; any carton of a type would do, except that a physical set must stay in one vehicle - so a vehicle
     * first takes whole kit groups that fit what it holds, then loose cartons. The original handed cartons out in
     * request order and could put one set's top in one vehicle and its base in another.
     */
    private static final class Binding {
        private final Map<ItemType, Deque<Integer>> loose = new HashMap<>();
        private final Map<String, List<Integer>> groups;
        private final Map<Integer, ItemType> typeOf = new HashMap<>();
        private final Set<String> taken = new HashSet<>();
        private final List<CargoPackage> cartons;

        Binding(List<CargoPackage> cartons, java.util.Collection<ItemType> types, Map<String, List<Integer>> groups) {
            this.cartons = cartons;
            this.groups = groups;
            Set<Integer> grouped = new HashSet<>();
            groups.values().forEach(grouped::addAll);
            for (ItemType t : types) {
                Deque<Integer> pool = new ArrayDeque<>();
                for (int i : t.cartons) {
                    typeOf.put(i, t);
                    if (!grouped.contains(i)) {
                        pool.add(i);
                    }
                }
                loose.put(t, pool);
            }
        }

        /** The cartons for one vehicle's placements, index for index. */
        int[] bind(List<BoxPlacement> placements) {
            Map<ItemType, Deque<Integer>> slots = new LinkedHashMap<>();
            for (int k = 0; k < placements.size(); k++) {
                slots.computeIfAbsent(placements.get(k).type, t -> new ArrayDeque<>()).add(k);
            }
            int[] out = new int[placements.size()];
            for (Map.Entry<String, List<Integer>> g : groups.entrySet()) {
                if (taken.contains(g.getKey()) || !fits(g.getValue(), slots)) {
                    continue;
                }
                taken.add(g.getKey());
                for (int carton : g.getValue()) {
                    out[slots.get(typeOf.get(carton)).pollFirst()] = carton;
                }
            }
            for (Map.Entry<ItemType, Deque<Integer>> e : slots.entrySet()) {
                Deque<Integer> pool = loose.get(e.getKey());
                for (int slot : e.getValue()) {
                    Integer carton = pool == null ? null : pool.pollFirst();
                    if (carton == null) {
                        carton = breakGroupFor(e.getKey());
                    }
                    out[slot] = carton;
                }
            }
            return out;
        }

        private boolean fits(List<Integer> group, Map<ItemType, Deque<Integer>> slots) {
            Map<ItemType, Integer> need = new HashMap<>();
            for (int carton : group) {
                need.merge(typeOf.get(carton), 1, Integer::sum);
            }
            for (Map.Entry<ItemType, Integer> e : need.entrySet()) {
                Deque<Integer> free = slots.get(e.getKey());
                if (free == null || free.size() < e.getValue()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Only when the solver placed set parts outside whole sets, which it never does: a carton from a set not yet
         * placed. The kit-group check after binding then reports the split instead of shipping it.
         */
        private int breakGroupFor(ItemType type) {
            for (Map.Entry<String, List<Integer>> g : groups.entrySet()) {
                if (taken.contains(g.getKey())) {
                    continue;
                }
                for (int carton : g.getValue()) {
                    if (typeOf.get(carton) == type) {
                        g.getValue().remove(Integer.valueOf(carton));
                        return carton;
                    }
                }
            }
            throw new IllegalStateException("the solver placed more cartons of " + type + " than exist");
        }

        CargoPackage carton(int index) {
            return cartons.get(index);
        }

        /** The cartons no vehicle took. */
        List<Integer> left() {
            List<Integer> out = new ArrayList<>();
            loose.values().forEach(out::addAll);
            groups.forEach((g, cartons) -> {
                if (!taken.contains(g)) {
                    out.addAll(cartons);
                }
            });
            out.sort(Comparator.naturalOrder());
            return out;
        }
    }

    // ------------------------------------------------------------------ input checks

    private static void checkUnique(List<CargoPackage> cartons, List<VehicleSpec> types) {
        Set<String> refs = new HashSet<>();
        for (CargoPackage c : cartons) {
            if (!refs.add(c.ref())) {
                throw new IllegalArgumentException("carton " + c.ref() + " is listed twice");
            }
        }
        Set<UUID> ids = new HashSet<>();
        for (VehicleSpec t : types) {
            if (!ids.add(t.typeId())) {
                throw new IllegalArgumentException("vehicle type " + t.code() + " is listed twice");
            }
        }
    }

    /**
     * A declared set's part must be one kind of carton: the solver lays parts as one line, so two sizes under one part
     * code would put the wrong carton in a slot. (The original kept the last line's sizes for all of them.) Measured
     * data can be like that, so it is not refused: the set is not kept, its cartons load loose, and a warning says
     * why. Kit groups never get here - their key carries the sizes.
     */
    private static List<KitSpec> consistentKits(List<KitSpec> kits, List<Box> main, List<KitWarning> warnings) {
        Map<String, Integer> lines = new HashMap<>();
        for (Box b : main) {
            lines.merge(b.partName() == null ? "" : b.partName(), 1, Integer::sum);
        }
        List<KitSpec> out = new ArrayList<>();
        for (KitSpec k : kits) {
            String mixed = null;
            for (String part : k.members().keySet()) {
                if (lines.getOrDefault(part, 0) > 1) {
                    mixed = part;
                    break;
                }
            }
            if (mixed == null) {
                out.add(k);
            } else {
                warnings.add(new KitWarning(k.name(), KitWarning.Type.PARTS_OF_DIFFERENT_SIZES, mixed, 0));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ reasons

    /**
     * Whether an empty vehicle of this type can hold the carton, through its door (LP3-DOOR) and clear of the reserved
     * corners. On the floor, a carton
     * that fits anywhere also fits pushed left and to the front until it meets a wall or a corner block, so only those
     * positions need trying.
     */
    static boolean fitsEmpty(CargoPackage c, VehicleSpec t) {
        List<BoxPlacement> corners = Gussets.of(new Bin(t));
        for (Orientation o : Orientation.values()) {
            if (!o.allowedFor(c)) {
                continue;
            }
            int[] e = o.extents(c);
            if (e[0] > t.innerWidthMm() || e[1] > t.innerHeightMm() || e[2] > t.innerLengthMm()
                    || !t.passesDoor(e[0], e[1])) {
                continue;
            }
            List<Double> xs = new ArrayList<>(List.of(0.0));
            List<Double> zs = new ArrayList<>(List.of(0.0));
            for (BoxPlacement g : corners) {
                xs.add(g.x + g.w);
                zs.add(g.z + g.d);
            }
            for (double x : xs) {
                for (double z : zs) {
                    if (x + e[0] > t.innerWidthMm() || z + e[2] > t.innerLengthMm()) {
                        continue;
                    }
                    boolean clear = true;
                    for (BoxPlacement g : corners) {
                        clear &= !(x < g.x + g.w && g.x < x + e[0] && e[1] > g.y && z < g.z + g.d && g.z < z + e[2]);
                    }
                    if (clear) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean takes(CargoPackage c, VehicleSpec t) {
        return fitsEmpty(c, t) && c.weightKg().compareTo(t.maxPayloadKg()) <= 0;
    }

    /** The reason a carton can never load, whatever else is in the plan; null when some usable type takes it. */
    private static UnplacedReason classifyAlone(CargoPackage c, List<VehicleSpec> types) {
        boolean fits = false;
        boolean light = false;
        for (VehicleSpec t : types) {
            if (fitsEmpty(c, t)) {
                fits = true;
                if (c.weightKg().compareTo(t.maxPayloadKg()) <= 0) {
                    light = true;
                    if (t.availableCount() > 0) {
                        return null;
                    }
                }
            }
        }
        return !fits ? UnplacedReason.TOO_LARGE : !light ? UnplacedReason.TOO_HEAVY
                : UnplacedReason.NO_VEHICLE_AVAILABLE;
    }

    /** The reason a carton the solver was offered ended up out, against the types the plan could use. */
    private static UnplacedReason classifyLeftover(CargoPackage c, boolean isFill, List<Bin> scope,
                                                   Map<UUID, Integer> usedByType, boolean fleet) {
        boolean fits = false;
        boolean anyFree = false;
        boolean eligible = false;
        for (Bin b : scope) {
            VehicleSpec t = b.spec;
            if (fitsEmpty(c, t)) {
                fits = true;
            }
            if (takes(c, t)) {
                eligible = true;
                int cap = Math.min(t.availableCount(), fleet ? KitSolver.MAX_PER_TYPE_IN_FLEET : KitSolver.MAX_BINS);
                if (usedByType.getOrDefault(t.typeId(), 0) < cap) {
                    anyFree = true;
                }
            }
        }
        if (!eligible) {
            return fits ? UnplacedReason.TOO_HEAVY : UnplacedReason.TOO_LARGE;
        }
        if (isFill) {
            return UnplacedReason.NO_SPACE;
        }
        return anyFree ? UnplacedReason.NO_SPACE : UnplacedReason.NO_VEHICLE_AVAILABLE;
    }

    // ------------------------------------------------------------------ output

    private static int mm(double v) {
        long r = Math.round(v);
        if (r != v) {
            throw new IllegalStateException("the solver produced a coordinate off the millimetre grid: " + v);
        }
        return (int) r;
    }

    private static PlannedVehicle vehicle(BinLoad load, int seq, Binding binding, List<PlacementValidator.Item> items) {
        VehicleSpec spec = load.bin().spec;
        List<Boolean> fills = new ArrayList<>();
        int[] bound = binding.bind(load.placements());
        for (int k = 0; k < load.placements().size(); k++) {
            BoxPlacement p = load.placements().get(k);
            CargoPackage c = binding.carton(bound[k]);
            items.add(new PlacementValidator.Item(c, mm(p.x), mm(p.y), mm(p.z), mm(p.w), mm(p.h), mm(p.d)));
            fills.add(p.fill);
        }
        List<PlacementViolation> violations = PlacementValidator.validate(spec, items);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("the solver broke a loading rule in vehicle " + seq + ": " + violations);
        }
        int[] sequence = loadSequence(items);
        List<Placement> placements = new ArrayList<>();
        long volume = 0;
        BigDecimal weight = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            PlacementValidator.Item it = items.get(i);
            CargoPackage c = it.carton();
            placements.add(new Placement(c.ref(), c.sourceRef(), c.partCode(), c.kitGroup(), it.x(), it.y(), it.z(),
                    it.w(), it.h(), it.d(), c.lengthMm(), c.widthMm(), c.heightMm(),
                    Orientation.of(c, it.w(), it.h(), it.d()), c.rotatable(), c.weightKg(), sequence[i], fills.get(i)));
            volume += (long) it.w() * it.h() * it.d();
            weight = weight.add(c.weightKg());
        }
        placements.sort(Comparator.comparingInt(Placement::loadSequence));
        BigDecimal volumePct = BigDecimal.valueOf(volume).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(spec.innerVolumeMm3()), 2, RoundingMode.HALF_UP);
        BigDecimal weightPct = weight.multiply(BigDecimal.valueOf(100))
                .divide(spec.maxPayloadKg(), 2, RoundingMode.HALF_UP);
        return new PlannedVehicle(spec.typeId(), spec.code(), seq, List.copyOf(placements), volume, weight,
                volumePct, weightPct);
    }

    /**
     * The order to load a vehicle in: a carton only after every carton it rests on, and among those free to go, the
     * one nearest the front wall, then the lowest, then the leftmost. The original returned cartons in the order the
     * search placed them, which is not a physical order: a block filler places whole blocks wherever they fit.
     */
    public static int[] loadSequence(List<PlacementValidator.Item> items) {
        int n = items.size();
        List<List<Integer>> above = new ArrayList<>();
        int[] below = new int[n];
        for (int i = 0; i < n; i++) {
            above.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            PlacementValidator.Item a = items.get(i);
            for (int j = 0; j < n; j++) {
                PlacementValidator.Item b = items.get(j);
                if (i == j || b.y() + b.h() != a.y()) {
                    continue;
                }
                boolean ox = Math.min(a.x() + a.w(), b.x() + b.w()) > Math.max(a.x(), b.x());
                boolean oz = Math.min(a.z() + a.d(), b.z() + b.d()) > Math.max(a.z(), b.z());
                if (ox && oz) {
                    above.get(j).add(i);
                    below[i]++;
                }
            }
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>(Comparator
                .<Integer>comparingInt(i -> items.get(i).z())
                .thenComparingInt(i -> items.get(i).y())
                .thenComparingInt(i -> items.get(i).x())
                .thenComparingInt(i -> i));
        for (int i = 0; i < n; i++) {
            if (below[i] == 0) {
                ready.add(i);
            }
        }
        int[] seq = new int[n];
        int next = 1;
        while (!ready.isEmpty()) {
            int i = ready.poll();
            seq[i] = next++;
            for (int k : above.get(i)) {
                if (--below[k] == 0) {
                    ready.add(k);
                }
            }
        }
        return seq;
    }

    private static LoadPlanMeta meta(KitSolver.Outcome o, WorkClock clock, long startNanos,
                                     List<KitWarning> plannerWarnings, boolean cutOff) {
        KitSolver.Meta m = o.meta();
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
        long best = clock.lastImprovementNanos() == 0 ? elapsed
                : (clock.lastImprovementNanos() - startNanos) / 1_000_000;
        return new LoadPlanMeta(m.algorithm(), m.setsTotal(), m.minVehicles(), m.targetVehicles(),
                m.targetUnreachable(), m.distribution(), plainBalance(m.kitBalance()), plainWarnings(plannerWarnings, m.warnings()), m.combo(),
                m.equalize().applied(), cutOff, clock.ticks(), elapsed, Math.max(0, best));
    }

    private static String plain(String partKey) {
        if (partKey == null) {
            return null;
        }
        int at = partKey.indexOf(SET_MARK);
        if (at < 0) {
            return partKey;
        }
        String member = partKey.substring(0, at);       // part code @ sizes, for a kit group's carton
        return member.substring(0, member.lastIndexOf('@'));
    }

    /** Kit balance by the part codes the caller sent, not the internal set-composition keys. */
    private static List<Map<String, Integer>> plainBalance(List<Map<String, Integer>> balance) {
        List<Map<String, Integer>> out = new ArrayList<>();
        for (Map<String, Integer> vehicle : balance) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            vehicle.forEach((k, n) -> counts.merge(plain(k), n, Integer::sum));
            out.add(counts);
        }
        return out;
    }

    private static List<KitWarning> plainWarnings(List<KitWarning> plannerWarnings, List<KitWarning> warnings) {
        List<KitWarning> out = new ArrayList<>(plannerWarnings);
        for (KitWarning w : warnings) {
            out.add(new KitWarning(w.kitName(), w.type(), plain(w.partCode()), w.count()));
        }
        return out;
    }

    /** The piece check: every carton is placed or unplaced, exactly once. */
    private static void checkPieces(List<CargoPackage> cartons, LoadPlanResult result) {
        Set<String> seen = new HashSet<>();
        for (PlannedVehicle v : result.vehicles()) {
            for (Placement p : v.placements()) {
                if (!seen.add(p.ref())) {
                    throw new IllegalStateException("carton " + p.ref() + " is placed twice");
                }
            }
        }
        for (UnplacedPackage u : result.unplaced()) {
            if (!seen.add(u.ref())) {
                throw new IllegalStateException("carton " + u.ref() + " is both placed and unplaced");
            }
        }
        if (seen.size() != cartons.size()) {
            throw new IllegalStateException("piece check failed: " + cartons.size() + " in, " + seen.size() + " out");
        }
    }
}
