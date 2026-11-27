package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link SelectionMode#KEEP_LINES} ("Giữ nguyên dòng", kltn-docs 09 §4.2): every carton of one line rides in one
 * vehicle. The original's {@code keep_line_together} packed whole lines first fit; this is the same drive on top of
 * the planner itself, so each vehicle it returns is a plan of the planner - same packing, same rules (BR-10), same
 * checks - and nothing of the search is forked.
 *
 * <p>The drive, per fleet: lines largest first; a line joins the first open vehicle that still takes everything it
 * holds plus the whole line, else opens a vehicle of its own - the smallest type that holds the whole line, else the
 * type that holds most of it, and the part that does not fit is left out (docs 09 edge case "Chế độ Giữ nguyên dòng":
 * "Phần vượt được báo là không xếp được"). The fit tests are the planner's quick pass (one construction); a line that
 * does not fit an empty vehicle also gets a full search, with its share of the budget, before its excess is given up.
 * </p>
 *
 * <p>The fleets tried are each usable type alone and, with two types or more, all of them mixed; the plan kept is the
 * best by the docs' order of merit (fewest cartons left, fewest vehicles, least empty space), as the other modes do.
 * The work budget is shared out so the full searches never spend more than the request's budget; the real time cap
 * (SOLVER-CAP) holds for the whole drive: when it passes, the lines not yet loaded are left out as
 * {@link UnplacedReason#SEARCH_CUT_OFF} and the best plan so far is returned.</p>
 */
final class LineKeeper {

    static final String ALGORITHM = "KEEP_LINES";

    private final LoadPlanner quick;
    private final LoadPlanner full;
    private final LoadPlanner clockSource;

    LineKeeper(LoadPlanner planner) {
        this.quick = planner.quickOnly();
        this.full = planner.withRequestWallLimit();
        this.clockSource = planner;
    }

    /** One line: its cartons, in request order. */
    private record Line(List<CargoPackage> cartons, long volumeMm3, BigDecimal weightKg) {
        static Line of(List<CargoPackage> cartons) {
            long volume = 0;
            BigDecimal weight = BigDecimal.ZERO;
            for (CargoPackage c : cartons) {
                volume += (long) c.lengthMm() * c.widthMm() * c.heightMm();
                weight = weight.add(c.weightKg());
            }
            return new Line(List.copyOf(cartons), volume, weight);
        }
    }

    /** A vehicle the drive opened: the cartons it holds and the planner's layout of them. */
    private static final class Open {
        final VehicleSpec spec;
        List<CargoPackage> cartons;
        PlannedVehicle layout;

        Open(VehicleSpec spec, List<CargoPackage> cartons, PlannedVehicle layout) {
            this.spec = spec;
            this.cartons = cartons;
            this.layout = layout;
        }

        long freeVolume() {
            return spec.innerVolumeMm3() - layout.usedVolumeMm3();
        }

        BigDecimal freeWeight() {
            return spec.maxPayloadKg().subtract(layout.totalWeightKg());
        }
    }

    /** One fleet's plan. */
    private record Drive(List<Open> vehicles, Map<String, UnplacedReason> unplaced, boolean cutOff, long ticks) {

        long waste() {
            long w = 0;
            for (Open v : vehicles) {
                w += v.freeVolume();
            }
            return w;
        }
    }

    private static final Comparator<Drive> DOCS_ORDER = Comparator
            .<Drive>comparingInt(d -> d.unplaced().size())
            .thenComparingInt(d -> d.vehicles().size())
            .thenComparingLong(Drive::waste);

    LoadPlanResult plan(LoadPlanRequest request, long startNanos, long deadline) {
        List<CargoPackage> cartons = request.cartons();
        boolean ignorePayload = request.ignorePayload();

        // Cartons no usable vehicle can ever take are reported, not offered - as the planner does.
        Map<String, UnplacedReason> never = new LinkedHashMap<>();
        for (CargoPackage c : cartons) {
            UnplacedReason r = LoadPlanner.classifyAlone(c, request.vehicleTypes(), ignorePayload);
            if (r != null) {
                never.put(c.ref(), r);
            }
        }
        // A line: the cartons of one source (an estimate line, a sheet row), or of one kit group - a physical set is
        // one vehicle's anyway, and its cartons are separate parcels with a source each.
        Map<String, List<CargoPackage>> byKey = new LinkedHashMap<>();
        for (CargoPackage c : cartons) {
            if (!never.containsKey(c.ref())) {
                String key = c.kitGroup() != null ? "kit:" + c.kitGroup() : "line:" + c.sourceRef();
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        }
        List<Line> lines = new ArrayList<>();
        byKey.values().forEach(l -> lines.add(Line.of(l)));
        // Largest first; equal lines keep the request's order (the sort is stable).
        lines.sort(Comparator.comparingLong(Line::volumeMm3).reversed());

        List<VehicleSpec> usable = request.vehicleTypes().stream().filter(t -> t.availableCount() > 0).toList();
        List<List<VehicleSpec>> fleets = new ArrayList<>();
        usable.forEach(t -> fleets.add(List.of(t)));
        if (usable.size() > 1) {
            fleets.add(usable);
        }
        Duration perFleet = request.budget().dividedBy(Math.max(1, fleets.size()));
        Duration perLine = perFleet.dividedBy(Math.max(1, lines.size()));
        if (perLine.isZero()) {
            perLine = Duration.ofNanos(1);
        }

        Drive best = null;
        for (List<VehicleSpec> fleet : fleets) {
            if (best != null && clockSource.now() >= deadline) {
                break;
            }
            Drive d = drive(request, lines, fleet, perLine, deadline);
            if (best == null || DOCS_ORDER.compare(d, best) < 0) {
                best = d;
            }
        }
        if (best == null) {
            best = new Drive(List.of(), new LinkedHashMap<>(), false, 0);
            for (Line line : lines) {
                for (CargoPackage c : line.cartons()) {
                    best.unplaced().put(c.ref(), UnplacedReason.NO_VEHICLE_AVAILABLE);
                }
            }
        }

        List<PlannedVehicle> vehicles = new ArrayList<>();
        for (Open v : best.vehicles()) {
            PlannedVehicle l = v.layout;
            vehicles.add(new PlannedVehicle(l.vehicleTypeId(), l.vehicleCode(), vehicles.size() + 1, l.placements(),
                    l.usedVolumeMm3(), l.totalWeightKg(), l.volumeFillPct(), l.weightFillPct()));
        }
        List<UnplacedPackage> left = new ArrayList<>();
        for (CargoPackage c : cartons) {
            UnplacedReason r = never.containsKey(c.ref()) ? never.get(c.ref()) : best.unplaced().get(c.ref());
            if (r != null) {
                left.add(new UnplacedPackage(c.ref(), c.sourceRef(), r));
            }
        }
        long end = clockSource.now();
        long elapsed = (end - startNanos) / 1_000_000;
        LoadPlanMeta meta = new LoadPlanMeta(ALGORITHM, 0, minVehicles(lines, usable), null, false,
                request.distribution().name(), List.of(), List.of(), null, false, best.cutOff(), best.ticks(),
                elapsed, elapsed);
        LoadPlanResult result = new LoadPlanResult(vehicles, left, meta);
        LoadPlanner.checkPieces(cartons, result);
        return result;
    }

    /** The first-fit drive over one fleet. */
    private Drive drive(LoadPlanRequest request, List<Line> lines, List<VehicleSpec> fleet, Duration perLine,
                        long deadline) {
        boolean ignorePayload = request.ignorePayload();
        List<Open> open = new ArrayList<>();
        Map<UUID, Integer> used = new HashMap<>();
        Map<String, UnplacedReason> unplaced = new LinkedHashMap<>();
        boolean cutOff = false;
        long ticks = 0;
        for (Line whole : lines) {
            // What this fleet can never take is out at once and does not hold its line back.
            List<CargoPackage> offered = new ArrayList<>();
            for (CargoPackage c : whole.cartons()) {
                UnplacedReason r = LoadPlanner.classifyAlone(c, fleet, ignorePayload);
                if (r == null) {
                    offered.add(c);
                } else {
                    unplaced.put(c.ref(), r);
                }
            }
            if (offered.isEmpty()) {
                continue;
            }
            if (clockSource.now() >= deadline) {
                cutOff = true;
                offered.forEach(c -> unplaced.put(c.ref(), UnplacedReason.SEARCH_CUT_OFF));
                continue;
            }
            Line line = Line.of(offered);

            boolean joined = false;
            for (Open v : open) {
                if (v.freeVolume() < line.volumeMm3()
                        || !ignorePayload && v.freeWeight().compareTo(line.weightKg()) < 0) {
                    continue;
                }
                List<CargoPackage> both = new ArrayList<>(v.cartons);
                both.addAll(line.cartons());
                LoadPlanResult r = one(quick, request, both, v.spec, perLine, deadline);
                if (r == null) {
                    break;
                }
                ticks += r.meta().workTicks();
                if (r.unplaced().isEmpty() && r.vehicles().size() == 1) {
                    v.cartons = both;
                    v.layout = r.vehicles().getFirst();
                    joined = true;
                    break;
                }
            }
            if (joined) {
                continue;
            }

            List<VehicleSpec> free = new ArrayList<>();
            for (VehicleSpec t : fleet) {
                if (used.getOrDefault(t.typeId(), 0) < Math.min(t.availableCount(), KitSolver.MAX_BINS)) {
                    free.add(t);
                }
            }
            if (free.isEmpty()) {
                line.cartons().forEach(c -> unplaced.put(c.ref(), UnplacedReason.NO_VEHICLE_AVAILABLE));
                continue;
            }
            // Smallest type first (stable: the fleet's order among equals); the first that holds the whole line wins.
            free.sort(Comparator.comparingLong(VehicleSpec::innerVolumeMm3));
            VehicleSpec chosen = null;
            LoadPlanResult placed = null;
            for (VehicleSpec t : free) {
                LoadPlanResult r = one(quick, request, line.cartons(), t, perLine, deadline);
                if (r == null) {
                    break;
                }
                ticks += r.meta().workTicks();
                if (placed == null || r.placedCount() > placed.placedCount()) {
                    chosen = t;
                    placed = r;
                }
                if (r.unplaced().isEmpty()) {
                    break;
                }
            }
            if (placed != null && !placed.unplaced().isEmpty()) {
                // Too big for one vehicle: a full search may load more of it before the rest is given up.
                LoadPlanResult better = one(full, request, line.cartons(), chosen, perLine, deadline);
                if (better != null) {
                    ticks += better.meta().workTicks();
                    if (better.placedCount() > placed.placedCount()) {
                        placed = better;
                    }
                }
            }
            if (placed == null) {
                cutOff = true;
                line.cartons().forEach(c -> unplaced.put(c.ref(), UnplacedReason.SEARCH_CUT_OFF));
                continue;
            }
            boolean late = clockSource.now() >= deadline;
            cutOff |= late && !placed.unplaced().isEmpty();
            for (UnplacedPackage u : placed.unplaced()) {
                // The excess of a line kept whole: room is what it lacks (a carton that fits no vehicle of the fleet
                // was taken out above).
                unplaced.put(u.ref(), late ? UnplacedReason.SEARCH_CUT_OFF : UnplacedReason.NO_SPACE);
            }
            if (placed.vehicles().isEmpty()) {
                continue;
            }
            PlannedVehicle layout = placed.vehicles().getFirst();
            Map<String, CargoPackage> byRef = new HashMap<>();
            line.cartons().forEach(c -> byRef.put(c.ref(), c));
            List<CargoPackage> in = new ArrayList<>();
            layout.placements().forEach(p -> in.add(byRef.get(p.ref())));
            open.add(new Open(chosen, in, layout));
            used.merge(chosen.typeId(), 1, Integer::sum);
        }
        return new Drive(open, unplaced, cutOff, ticks);
    }

    /**
     * The cartons planned into one vehicle of the type, with what is left of the real time; null when none is left.
     */
    private LoadPlanResult one(LoadPlanner planner, LoadPlanRequest request, List<CargoPackage> cartons,
                               VehicleSpec type, Duration budget, long deadline) {
        long left = deadline - clockSource.now();
        if (left <= 0) {
            return null;
        }
        VehicleSpec single = new VehicleSpec(type.typeId(), type.code(), type.kind(), type.innerLengthMm(),
                type.innerWidthMm(), type.innerHeightMm(), type.maxPayloadKg(), 1, type.gussetMm(),
                type.doorWidthMm(), type.doorHeightMm());
        LoadPlanRequest sub = new LoadPlanRequest(cartons, List.of(single), SelectionMode.SINGLE_TYPE, request.kits(),
                request.distribution(), false, null, request.seed(), budget, Duration.ofNanos(left),
                request.ignorePayload());
        return planner.plan(sub);
    }

    /** The volume floor: the cargo packed solid into the largest usable type. */
    private static int minVehicles(List<Line> lines, List<VehicleSpec> usable) {
        long volume = 0;
        for (Line l : lines) {
            volume += l.volumeMm3();
        }
        long largest = 0;
        for (VehicleSpec t : usable) {
            largest = Math.max(largest, t.innerVolumeMm3());
        }
        return volume == 0 || largest == 0 ? 0 : (int) ((volume + largest - 1) / largest);
    }
}
