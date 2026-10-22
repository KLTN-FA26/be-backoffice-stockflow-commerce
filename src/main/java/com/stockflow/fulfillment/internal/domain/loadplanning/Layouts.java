package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Turns a planner's answer into the viewer's {@link Layout} contract (FE #71). The one mapper of the estimate (PR EST)
 * and of the real load plan (PR D), so the two can never drift apart: what a carton is called ({@code label}) is the
 * only thing a caller supplies.
 *
 * <ul>
 *   <li>{@code gussets} are the reserved corner blocks of each vehicle type, exactly where the planner kept them
 *       clear ({@link Gussets}): the viewer draws them, it does not compute them.</li>
 *   <li>{@code sourceRef} is null when it only repeats {@code ref} (a packed parcel), as the viewer expects.</li>
 *   <li>{@code partName} is the part code the planner was given, or the label when it had none.</li>
 *   <li>Cartons left out are grouped per source line, part, size and reason with their count and refs: the
 *       frontend's {@code UnplacedLineSchema} (label, partName, origMm, quantity).</li>
 * </ul>
 */
public final class Layouts {

    private Layouts() {
    }

    /** The vehicles in plan order and the cartons left out, grouped, in the order the planner first reported them. */
    public record Mapped(List<Layout> vehicles, List<Layout.UnplacedLine> unplaced) {

        public Mapped {
            vehicles = List.copyOf(vehicles);
            unplaced = List.copyOf(unplaced);
        }
    }

    /**
     * @param request the request the result answers: its vehicle types give the inner sizes and gussets, its cartons
     *                the label of each placed or unplaced ref
     * @param label   the short code printed on a carton (a SKU); null answers fall back to the part code, then the ref
     */
    public static Mapped of(LoadPlanRequest request, LoadPlanResult result, Function<CargoPackage, String> label) {
        Objects.requireNonNull(label, "label");
        Map<UUID, VehicleSpec> types = new HashMap<>();
        request.vehicleTypes().forEach(t -> types.put(t.typeId(), t));
        Map<String, CargoPackage> cartons = new HashMap<>();
        request.cartons().forEach(c -> cartons.put(c.ref(), c));

        List<Layout> vehicles = new ArrayList<>();
        for (PlannedVehicle v : result.vehicles()) {
            VehicleSpec spec = types.get(v.vehicleTypeId());
            if (spec == null) {
                throw new IllegalStateException("vehicle " + v.seq() + " is of a type the request did not offer");
            }
            vehicles.add(vehicle(v, spec, cartons, label));
        }
        // Grouped by source line, part, size and reason, in the order the planner reported them first.
        Map<List<Object>, List<String>> groups = new LinkedHashMap<>();
        Map<List<Object>, Layout.UnplacedLine> heads = new HashMap<>();
        for (UnplacedPackage u : result.unplaced()) {
            CargoPackage c = carton(cartons, u.ref());
            String source = sourceRef(c.ref(), c.sourceRef());
            String shortCode = labelOf(c, label);
            String part = partName(c.partCode(), c, label);
            List<Object> key = Arrays.asList(source, shortCode, part, dims(c), u.reason());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c.ref());
            heads.putIfAbsent(key, new Layout.UnplacedLine(source, shortCode, part, dims(c), u.reason(), 0,
                    List.of()));
        }
        List<Layout.UnplacedLine> unplaced = new ArrayList<>();
        groups.forEach((key, refs) -> {
            Layout.UnplacedLine head = heads.get(key);
            unplaced.add(new Layout.UnplacedLine(head.sourceRef(), head.label(), head.partName(), head.origMm(),
                    head.reason(), refs.size(), refs));
        });
        return new Mapped(vehicles, unplaced);
    }

    /** The reserved corner blocks of a vehicle type, as the planner keeps them clear. */
    public static List<Layout.Block> gussets(VehicleSpec spec) {
        List<Layout.Block> out = new ArrayList<>();
        for (BoxPlacement g : Gussets.of(new Bin(spec))) {
            out.add(new Layout.Block(mm(g.x), mm(g.y), mm(g.z), mm(g.w), mm(g.h), mm(g.d)));
        }
        return out;
    }

    private static Layout vehicle(PlannedVehicle v, VehicleSpec spec, Map<String, CargoPackage> cartons,
                                  Function<CargoPackage, String> label) {
        List<Layout.Box> boxes = new ArrayList<>();
        for (Placement p : v.placements()) {
            CargoPackage c = carton(cartons, p.ref());
            if (p.orientation() == null) {
                throw new IllegalStateException("carton " + p.ref() + " has placed sizes that are not its own");
            }
            boxes.add(new Layout.Box(p.x(), p.y(), p.z(), p.w(), p.h(), p.d(), p.ref(),
                    sourceRef(p.ref(), p.sourceRef()), labelOf(c, label), partName(p.partName(), c, label),
                    p.kitGroup(), new Layout.Dims(p.lengthMm(), p.widthMm(), p.heightMm()), p.orientation().code(),
                    p.rotatable(), p.weightKg(), p.loadSequence()));
        }
        return new Layout(v.seq(), v.vehicleCode(), v.vehicleTypeId(),
                new Layout.Dims(spec.innerLengthMm(), spec.innerWidthMm(), spec.innerHeightMm()), spec.maxPayloadKg(),
                gussets(spec), boxes, v.usedVolumeMm3(), v.totalWeightKg(), v.volumeFillPct(), v.weightFillPct());
    }

    private static CargoPackage carton(Map<String, CargoPackage> cartons, String ref) {
        CargoPackage c = cartons.get(ref);
        if (c == null) {
            throw new IllegalStateException("the plan names carton " + ref + ", which the request did not have");
        }
        return c;
    }

    private static String sourceRef(String ref, String sourceRef) {
        return sourceRef == null || sourceRef.equals(ref) ? null : sourceRef;
    }

    private static String labelOf(CargoPackage c, Function<CargoPackage, String> label) {
        String given = label.apply(c);
        if (given != null && !given.isBlank()) {
            return given;
        }
        return c.partCode() != null ? c.partCode() : c.ref();
    }

    private static String partName(String partCode, CargoPackage c, Function<CargoPackage, String> label) {
        return partCode != null && !partCode.isBlank() ? partCode : labelOf(c, label);
    }

    private static Layout.Dims dims(CargoPackage c) {
        return new Layout.Dims(c.lengthMm(), c.widthMm(), c.heightMm());
    }

    private static int mm(double v) {
        return (int) Math.round(v);
    }
}
