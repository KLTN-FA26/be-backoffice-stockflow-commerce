package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks one vehicle's placements against the hard loading rules (kltn-docs 09 BR-10). A manual move on the 3D model
 * must pass it before it is accepted (BR-11), and the planner runs it on its own output before returning, so a plan
 * that breaks a rule is a defect, never a result.
 *
 * <p>Exact integer arithmetic throughout: positions and sizes are whole millimetres, so "98 % of the base" is
 * {@code 100 * covered >= 98 * base}, with no tolerance to argue about.</p>
 */
public final class PlacementValidator {

    /** Base support required, in percent (BR-10). */
    public static final int SUPPORT_PERCENT = 98;

    /** A carton at a corner, with the sizes it occupies along {@code x} ({@code w}), {@code y} ({@code h}), {@code z} ({@code d}). */
    public record Item(CargoPackage carton, int x, int y, int z, int w, int h, int d) {
        public Item {
            Objects.requireNonNull(carton, "carton");
        }

        public static Item of(CargoPackage carton, int x, int y, int z, Orientation orientation) {
            int[] e = orientation.extents(carton);
            return new Item(carton, x, y, z, e[0], e[1], e[2]);
        }
    }

    private PlacementValidator() {
    }

    /** Every rule each carton breaks, at most once per carton and rule, in the order of the items. */
    public static List<PlacementViolation> validate(VehicleSpec vehicle, List<Item> items) {
        return validate(vehicle, items, false);
    }

    /**
     * As {@link #validate(VehicleSpec, List)}; {@code ignorePayload} leaves out {@link ViolationType#OVERWEIGHT} and
     * is for the trial estimate only (LP2-9), whose cartons carry a placeholder weight.
     */
    public static List<PlacementViolation> validate(VehicleSpec vehicle, List<Item> items, boolean ignorePayload) {
        Set<PlacementViolation> out = new LinkedHashSet<>();
        long w = vehicle.innerWidthMm();
        long h = vehicle.innerHeightMm();
        long l = vehicle.innerLengthMm();
        List<BoxPlacement> gussets = Gussets.of(new Bin(vehicle));
        BigDecimal load = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String ref = it.carton().ref();
            load = load.add(it.carton().weightKg());
            Orientation o = Orientation.of(it.carton(), it.w(), it.h(), it.d());
            if (o == null || !o.allowedFor(it.carton())) {
                out.add(new PlacementViolation(ref, ViolationType.ROTATION_NOT_ALLOWED));
            }
            if (it.x() < 0 || it.y() < 0 || it.z() < 0 || it.x() + (long) it.w() > w || it.y() + (long) it.h() > h
                    || it.z() + (long) it.d() > l) {
                out.add(new PlacementViolation(ref, ViolationType.OUT_OF_BOUNDS));
            }
            if (!vehicle.passesDoor(it.w(), it.h())) {
                out.add(new PlacementViolation(ref, ViolationType.DOOR));
            }
            for (BoxPlacement g : gussets) {
                if (overlaps(it, (long) g.x, (long) g.y, (long) g.z, (long) g.w, (long) g.h, (long) g.d)) {
                    out.add(new PlacementViolation(ref, ViolationType.GUSSET));
                }
            }
            for (int j = 0; j < items.size(); j++) {
                Item other = items.get(j);
                if (j != i && overlaps(it, other.x(), other.y(), other.z(), other.w(), other.h(), other.d())) {
                    out.add(new PlacementViolation(ref, ViolationType.OVERLAP));
                    break;
                }
            }
            if (!supported(it, items)) {
                out.add(new PlacementViolation(ref, ViolationType.UNSUPPORTED));
            }
        }
        if (!ignorePayload && load.compareTo(vehicle.maxPayloadKg()) > 0) {
            out.add(new PlacementViolation(null, ViolationType.OVERWEIGHT));
        }
        return new ArrayList<>(out);
    }

    /**
     * The plan-wide rule: the cartons of one physical set (the same {@link CargoPackage#kitGroup()}) travel in one
     * vehicle. A carton breaks it when its set has cartons in another vehicle; every such carton is reported. Run it
     * over all vehicles of a plan after any move between vehicles. Fill cartons are not counted: fill is never a set
     * part (see {@link LoadPlanner}).
     */
    public static List<PlacementViolation> validateKitGroups(List<List<Item>> vehicles) {
        java.util.Map<String, Set<Integer>> where = new java.util.HashMap<>();
        for (int v = 0; v < vehicles.size(); v++) {
            for (Item it : vehicles.get(v)) {
                String group = it.carton().fill() ? null : it.carton().kitGroup();
                if (group != null) {
                    where.computeIfAbsent(group, k -> new java.util.HashSet<>()).add(v);
                }
            }
        }
        List<PlacementViolation> out = new ArrayList<>();
        for (List<Item> vehicle : vehicles) {
            for (Item it : vehicle) {
                String group = it.carton().fill() ? null : it.carton().kitGroup();
                if (group != null && where.get(group).size() > 1) {
                    out.add(new PlacementViolation(it.carton().ref(), ViolationType.KIT_SPLIT));
                }
            }
        }
        return out;
    }

    private static boolean overlaps(Item a, long x, long y, long z, long dx, long dy, long dz) {
        return a.x() < x + dx && x < a.x() + (long) a.w()
                && a.y() < y + dy && y < a.y() + (long) a.h()
                && a.z() < z + dz && z < a.z() + (long) a.d();
    }

    /** On the floor, or at least 98 % of the base on tops that are exactly at its bottom. */
    static boolean supported(Item it, List<Item> items) {
        if (it.y() == 0) {
            return true;
        }
        long base = (long) it.w() * it.d();
        long covered = 0;
        for (Item other : items) {
            if (other == it || (long) other.y() + other.h() != it.y()) {
                continue;
            }
            long ox = Math.min((long) it.x() + it.w(), (long) other.x() + other.w()) - Math.max(it.x(), other.x());
            long oz = Math.min((long) it.z() + it.d(), (long) other.z() + other.d()) - Math.max(it.z(), other.z());
            if (ox > 0 && oz > 0) {
                covered += ox * oz;
            }
        }
        return 100 * covered >= SUPPORT_PERCENT * base;
    }
}
