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
     * Greedy constructions per budget-second: how the search's budget proportions, kept from the original, turn into
     * work ({@link WorkClock}). The original sustained about 8 a second in Python; this port builds one in well under a
     * millisecond, so 8 would leave most of the budget unused. At 256 the port matched the original's 30 s results on
     * every benchmark fixture (LoadPlannerBenchmarkTest, 22/10), while a 500-carton plan still ends inside a few
     * seconds of wall time.
     */
    static final double TICKS_PER_SECOND = 256.0;

    /** The original's number of carton orders tried per vehicle type in the single-type drive. */
    static final int ATTEMPTS = 6;

    private final double ticksPerSecond;

    public LoadPlanner() {
        this(TICKS_PER_SECOND);
    }

    /** For the benchmark: the same search at another work rate. */
    LoadPlanner(double ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
    }

    public LoadPlanResult plan(LoadPlanRequest request) {
        long startNanos = System.nanoTime();
        List<CargoPackage> cartons = request.cartons();
        checkUnique(cartons, request.vehicleTypes());
        WorkClock clock = new WorkClock(ticksPerSecond, request.budget().toNanos());
        KitSolver solver = new KitSolver(clock, request.seed(), true);
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
        Map<List<Object>, ItemType> typesByKey = new LinkedHashMap<>();
        Map<List<Object>, BigDecimal> heaviest = new LinkedHashMap<>();
        for (CargoPackage c : cartons) {
            if (!unplaced.containsKey(c.ref())) {
                heaviest.merge(typeKey(c), c.weightKg(), BigDecimal::max);
            }
        }
        for (int i = 0; i < cartons.size(); i++) {
            CargoPackage c = cartons.get(i);
            if (unplaced.containsKey(c.ref())) {
                continue;
            }
            List<Object> key = typeKey(c);
            ItemType t = typesByKey.computeIfAbsent(key, k -> new ItemType(typesByKey.size(), c.partCode(),
                    heaviest.get(k).doubleValue(), c.rotatable(), c.widthMm(), c.heightMm(),
                    c.lengthMm()));
            t.cartons.add(i);
        }
        List<Box> main = new ArrayList<>();
        List<Box> fill = new ArrayList<>();
        Map<ItemType, Boolean> fillType = new HashMap<>();
        typesByKey.forEach((k, t) -> {
            boolean isFill = (Boolean) k.get(5);
            fillType.put(t, isFill);
            (isFill ? fill : main).add(t.box(t.cartons.size()));
        });
        checkKitParts(request.kits(), main);

        KitSolver.Outcome outcome;
        List<Bin> scope;
        if (main.isEmpty() || usable.isEmpty()) {
            outcome = new KitSolver.Outcome(List.of(), new ArrayList<>(main), new KitSolver.Meta("GRASP_KIT", 0, 0,
                    request.targetVehicleCount(), false, request.distribution().name(), List.of(), List.of(), null,
                    KitSolver.EqualizeStats.NONE));
            scope = usable;
        } else if (request.mode() == SelectionMode.FEWEST_VEHICLES || usable.size() == 1) {
            outcome = solver.solve(usable, main, request.kits(), request.targetVehicleCount(), budget, ATTEMPTS,
                    request.distribution(), fill, request.equalizeFill());
            scope = usable;
        } else {
            // SINGLE_TYPE: the original's same_type - each type alone, the best by the docs' order of merit.
            KitSolver.Outcome best = null;
            Bin bestBin = null;
            double each = budget / usable.size();
            for (Bin type : usable) {
                KitSolver.Outcome o = solver.solve(List.of(type), main, request.kits(), request.targetVehicleCount(),
                        each, ATTEMPTS, request.distribution(), fill, request.equalizeFill());
                if (best == null || KitSolver.docsOrder().compare(o, best) < 0) {
                    best = o;
                    bestBin = type;
                    clock.improved();
                }
            }
            outcome = best;
            scope = List.of(bestBin);
        }

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

        Map<ItemType, Deque<Integer>> pools = new HashMap<>();
        typesByKey.values().forEach(t -> pools.put(t, new ArrayDeque<>(t.cartons)));
        List<PlannedVehicle> vehicles = new ArrayList<>();
        Map<UUID, Integer> usedByType = new HashMap<>();
        for (BinLoad l : loads) {
            vehicles.add(vehicle(l, vehicles.size() + 1, cartons, pools));
            usedByType.merge(l.bin().spec.typeId(), 1, Integer::sum);
        }
        boolean fleet = request.mode() == SelectionMode.FEWEST_VEHICLES && usable.size() > 1;
        for (Map.Entry<ItemType, Deque<Integer>> e : pools.entrySet()) {
            for (int idx : e.getValue()) {
                CargoPackage c = cartons.get(idx);
                unplaced.put(c.ref(), classifyLeftover(c, fillType.get(e.getKey()), scope, usedByType, fleet));
            }
        }
        List<UnplacedPackage> left = new ArrayList<>();
        for (CargoPackage c : cartons) {
            UnplacedReason r = unplaced.get(c.ref());
            if (r != null) {
                left.add(new UnplacedPackage(c.ref(), c.sourceRef(), r));
            }
        }

        LoadPlanResult result = new LoadPlanResult(vehicles, left, meta(outcome, clock, startNanos));
        checkPieces(cartons, result);
        return result;
    }

    // ------------------------------------------------------------------ input checks

    private static List<Object> typeKey(CargoPackage c) {
        return java.util.Arrays.asList(c.partCode(), c.lengthMm(), c.widthMm(), c.heightMm(), c.rotatable(), c.fill());
    }

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
     * A set's part must be one kind of carton: the solver lays parts as one line, so two sizes under one part code
     * would put the wrong carton in a slot. (The original kept the last line's sizes for all of them.)
     */
    private static void checkKitParts(List<KitSpec> kits, List<Box> main) {
        Set<String> parts = new HashSet<>();
        kits.forEach(k -> parts.addAll(k.members().keySet()));
        Map<String, Integer> lines = new HashMap<>();
        for (Box b : main) {
            if (parts.contains(b.partName())) {
                lines.merge(b.partName(), 1, Integer::sum);
            }
        }
        lines.forEach((part, n) -> {
            if (n > 1) {
                throw new IllegalArgumentException("set part " + part
                        + " has cartons of different sizes or rotatable flags");
            }
        });
    }

    // ------------------------------------------------------------------ reasons

    /**
     * Whether an empty vehicle of this type can hold the carton, clear of the reserved corners. On the floor, a carton
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
            if (e[0] > t.innerWidthMm() || e[1] > t.innerHeightMm() || e[2] > t.innerLengthMm()) {
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

    private static PlannedVehicle vehicle(BinLoad load, int seq, List<CargoPackage> cartons,
                                          Map<ItemType, Deque<Integer>> pools) {
        VehicleSpec spec = load.bin().spec;
        List<PlacementValidator.Item> items = new ArrayList<>();
        List<Boolean> fills = new ArrayList<>();
        for (BoxPlacement p : load.placements()) {
            Deque<Integer> pool = pools.get(p.type);
            if (pool == null || pool.isEmpty()) {
                throw new IllegalStateException("the solver placed more cartons of " + p.type + " than exist");
            }
            CargoPackage c = cartons.get(pool.pollFirst());
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
    static int[] loadSequence(List<PlacementValidator.Item> items) {
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

    private static LoadPlanMeta meta(KitSolver.Outcome o, WorkClock clock, long startNanos) {
        KitSolver.Meta m = o.meta();
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
        long best = clock.lastImprovementNanos() == 0 ? elapsed
                : (clock.lastImprovementNanos() - startNanos) / 1_000_000;
        return new LoadPlanMeta(m.algorithm(), m.setsTotal(), m.minVehicles(), m.targetVehicles(),
                m.targetUnreachable(), m.distribution(), m.kitBalance(), m.warnings(), m.combo(),
                m.equalize().applied(), clock.expired(), clock.ticks(), elapsed, Math.max(0, best));
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
