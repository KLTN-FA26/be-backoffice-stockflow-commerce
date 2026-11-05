package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Builders and the full set of checks every planner test runs on a result. */
final class LoadPlanAssertions {

    static final UUID C20 = new UUID(0, 20);
    static final UUID C40 = new UUID(0, 40);
    static final UUID HC = new UUID(0, 41);
    static final UUID TRUCK = new UUID(0, 5);

    /**
     * The tests' work rate: a tenth of the planner's before SCRUM-496 raised it (3 million), so the suite stays quick
     * and every run finishes well inside its budget (reproducible), with enough search for the behaviour under test.
     * Kept, not tied to the planner's rate, so a slow CI runner does not cut the searches off.
     */
    static final double TEST_RATE = 300_000.0;

    private LoadPlanAssertions() {
    }

    static VehicleSpec dc20(int available, int gusset) {
        return new VehicleSpec(C20, "20DC", VehicleKind.CONTAINER,
                5898, 2352, 2393, new BigDecimal("28000"), available, gusset);
    }

    static VehicleSpec dc40(int available, int gusset) {
        return new VehicleSpec(C40, "40DC", VehicleKind.CONTAINER,
                12032, 2352, 2393, new BigDecimal("26500"), available, gusset);
    }

    static VehicleSpec hc40(int available, int gusset) {
        return new VehicleSpec(HC, "40HC", VehicleKind.CONTAINER,
                12032, 2352, 2698, new BigDecimal("26500"), available, gusset);
    }

    /** A 5-tonne box truck: not a container, so nothing container-specific may apply to it (LP-6). */
    static VehicleSpec truck5t(int available, int gusset) {
        return new VehicleSpec(TRUCK, "TRUCK-5T", VehicleKind.TRUCK,
                6200, 2150, 2200, new BigDecimal("5000"), available, gusset);
    }

    static CargoPackage carton(String ref, int l, int w, int h, String kg, boolean rotatable, String part) {
        return new CargoPackage(ref, null, l, w, h, new BigDecimal(kg), rotatable, part, null, null, false);
    }

    static List<CargoPackage> many(String prefix, int n, int l, int w, int h, String kg, boolean rotatable,
                                   String part) {
        List<CargoPackage> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(carton(prefix + i, l, w, h, kg, rotatable, part));
        }
        return out;
    }

    /**
     * Everything a plan must satisfy whatever the input: the piece check, BR-10 on every vehicle (re-validated from
     * the output alone), BR-12, orientations that round-trip, a valid load order, and equal whole sets per vehicle.
     */
    static void assertSound(LoadPlanRequest request, LoadPlanResult result) {
        Map<String, CargoPackage> byRef = new HashMap<>();
        request.cartons().forEach(c -> byRef.put(c.ref(), c));

        Set<String> seen = new HashSet<>();
        result.vehicles().forEach(v -> v.placements().forEach(p -> assertThat(seen.add(p.ref())).isTrue()));
        result.unplaced().forEach(u -> {
            assertThat(seen.add(u.ref())).isTrue();
            assertThat(u.sourceRef()).isEqualTo(byRef.get(u.ref()).sourceRef());
        });
        assertThat(seen).as("piece check: in = placed + unplaced").isEqualTo(byRef.keySet());
        assertThat(result.placedCount() + result.unplaced().size()).isEqualTo(request.cartons().size());

        Map<UUID, Integer> perType = new HashMap<>();
        Map<UUID, VehicleSpec> types = new HashMap<>();
        request.vehicleTypes().forEach(t -> types.put(t.typeId(), t));
        int seq = 1;
        for (PlannedVehicle v : result.vehicles()) {
            assertThat(v.seq()).isEqualTo(seq++);
            VehicleSpec spec = types.get(v.vehicleTypeId());
            perType.merge(spec.typeId(), 1, Integer::sum);
            List<PlacementValidator.Item> items = new ArrayList<>();
            BigDecimal weight = BigDecimal.ZERO;
            Set<Integer> sequence = new HashSet<>();
            for (Placement p : v.placements()) {
                CargoPackage c = byRef.get(p.ref());
                assertThat(c).isNotNull();
                assertThat(p.orientation().extents(c)).containsExactly(p.w(), p.h(), p.d());
                assertThat(Orientation.of(c, p.w(), p.h(), p.d())).isEqualTo(p.orientation());
                assertThat(new int[] {p.lengthMm(), p.widthMm(), p.heightMm()})
                        .containsExactly(c.lengthMm(), c.widthMm(), c.heightMm());
                if (!c.rotatable()) {
                    assertThat(new int[] {p.w(), p.h(), p.d()})
                            .containsExactly(c.widthMm(), c.heightMm(), c.lengthMm());
                }
                assertThat(p.fill()).isEqualTo(c.fill());
                assertThat(p.sourceRef()).isEqualTo(c.sourceRef());
                assertThat(p.partName()).isEqualTo(c.partCode());
                assertThat(p.kitGroup()).isEqualTo(c.kitGroup());
                assertThat(p.rotatable()).isEqualTo(c.rotatable());
                assertThat(p.weightKg()).isEqualByComparingTo(c.weightKg());
                items.add(new PlacementValidator.Item(c, p.x(), p.y(), p.z(), p.w(), p.h(), p.d()));
                weight = weight.add(c.weightKg());
                assertThat(sequence.add(p.loadSequence())).isTrue();
            }
            assertThat(PlacementValidator.validate(spec, items)).as("BR-10 in vehicle %d", v.seq()).isEmpty();
            assertThat(v.totalWeightKg()).isEqualByComparingTo(weight);
            assertThat(weight).isLessThanOrEqualTo(spec.maxPayloadKg());
            assertLoadOrder(v);
            assertWholeSets(request.kits(), v, request);
        }
        perType.forEach((type, n) -> assertThat(n).as("BR-12 for %s", types.get(type).code())
                .isLessThanOrEqualTo(types.get(type).availableCount()));

        Map<String, Integer> groupVehicle = new HashMap<>();
        for (PlannedVehicle v : result.vehicles()) {
            for (Placement p : v.placements()) {
                if (p.kitGroup() != null && !p.fill()) {
                    Integer before = groupVehicle.putIfAbsent(p.kitGroup(), v.seq());
                    assertThat(before == null ? v.seq() : before).as("set %s stays in one vehicle", p.kitGroup())
                            .isEqualTo(v.seq());
                }
            }
        }
    }

    /** Nothing is loaded before a carton it rests on. */
    private static void assertLoadOrder(PlannedVehicle v) {
        for (Placement top : v.placements()) {
            for (Placement under : v.placements()) {
                boolean rests = under.y() + under.h() == top.y()
                        && Math.min(top.x() + top.w(), under.x() + under.w()) > Math.max(top.x(), under.x())
                        && Math.min(top.z() + top.d(), under.z() + under.d()) > Math.max(top.z(), under.z());
                if (rests) {
                    assertThat(under.loadSequence()).isLessThan(top.loadSequence());
                }
            }
        }
    }

    /**
     * Every vehicle holds whole sets: for each declared set whose parts are only ever set parts, each part's count is
     * the same multiple of its count per set.
     */
    private static void assertWholeSets(List<KitSpec> kits, PlannedVehicle v, LoadPlanRequest request) {
        for (KitSpec kit : kits) {
            Map<String, Integer> counts = new HashMap<>();
            v.placements().forEach(p -> {
                if (p.partName() != null && kit.members().containsKey(p.partName())) {
                    counts.merge(p.partName(), 1, Integer::sum);
                }
            });
            if (!exactMultiples(kit, request)) {
                continue;
            }
            Integer sets = null;
            for (Map.Entry<String, Integer> m : kit.members().entrySet()) {
                int n = counts.getOrDefault(m.getKey(), 0);
                assertThat(n % m.getValue()).as("part %s in vehicle %d", m.getKey(), v.seq()).isZero();
                if (sets == null) {
                    sets = n / m.getValue();
                } else {
                    assertThat(n / m.getValue()).as("sets of %s in vehicle %d", kit.name(), v.seq()).isEqualTo(sets);
                }
            }
        }
    }

    /** The list holds exactly whole sets of this kit (no surplus parts that would load loose). */
    private static boolean exactMultiples(KitSpec kit, LoadPlanRequest request) {
        Map<String, Integer> have = new HashMap<>();
        request.cartons().forEach(c -> {
            if (c.partCode() != null) {
                have.merge(c.partCode(), 1, Integer::sum);
            }
        });
        Integer sets = null;
        for (Map.Entry<String, Integer> m : kit.members().entrySet()) {
            int n = have.getOrDefault(m.getKey(), 0);
            if (n % m.getValue() != 0) {
                return false;
            }
            if (sets != null && sets != n / m.getValue()) {
                return false;
            }
            sets = n / m.getValue();
        }
        return true;
    }

    static Map<String, UnplacedReason> reasons(LoadPlanResult r) {
        Map<String, UnplacedReason> out = new HashMap<>();
        r.unplaced().forEach(u -> out.put(u.ref(), u.reason()));
        return out;
    }
}
