package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.assertSound;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.carton;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.dc20;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.dc40;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.hc40;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.many;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.reasons;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.truck5t;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The planner's contract: kits, reasons, modes, fill, payload, BR-12, budget, and the kit-bypass regression. */
class LoadPlannerTest {

    private final LoadPlanner planner = new LoadPlanner(LoadPlanAssertions.TEST_RATE);

    private LoadPlanResult plan(LoadPlanRequest request) {
        LoadPlanResult result = planner.plan(request);
        assertSound(request, result);
        return result;
    }

    private static LoadPlanRequest request(List<CargoPackage> cartons, List<VehicleSpec> types, SelectionMode mode,
                                           List<KitSpec> kits) {
        return new LoadPlanRequest(cartons, types, mode, kits, KitDistribution.AUTO, false, null, 0L,
                Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ the kit-bypass bug

    @Test
    @DisplayName("bypass bug: a list that is only one kit A+B loads like the same list without the kit")
    void kitOnlyListIsSearchedLikeLooseCartons() {
        List<CargoPackage> cartons = new ArrayList<>(many("A", 20, 1000, 1200, 800, "10", true, "A"));
        cartons.addAll(many("B", 20, 400, 600, 500, "5", true, "B"));
        KitSpec ab = new KitSpec("AB", Map.of("A", 1, "B", 1));

        LoadPlanResult loose = plan(request(cartons, List.of(dc20(5, 0)), SelectionMode.SINGLE_TYPE, List.of()));
        LoadPlanResult kit = plan(request(cartons, List.of(dc20(5, 0)), SelectionMode.SINGLE_TYPE, List.of(ab)));

        // The original split this into two 20DC at 32.5 % each: its kit parts got one greedy pass and no search.
        assertThat(loose.vehicles()).hasSize(1);
        assertThat(kit.vehicles()).hasSize(1);
        assertThat(kit.unplaced()).isEmpty();
        assertThat(kit.vehicles().get(0).volumeFillPct()).isEqualByComparingTo(loose.vehicles().get(0).volumeFillPct());
        assertThat(kit.meta().kitBalance()).containsExactly(Map.of("A", 20, "B", 20));
        assertThat(kit.meta().setsTotal()).isEqualTo(20);
    }

    @Test
    @DisplayName("kit A+B of one each fills one vehicle with both parts")
    void smallestKit() {
        List<CargoPackage> cartons = List.of(carton("a", 1000, 1200, 800, "10", true, "A"),
                carton("b", 400, 600, 500, "5", true, "B"));
        LoadPlanResult r = plan(request(cartons, List.of(truck5t(1, 100)), SelectionMode.SINGLE_TYPE,
                List.of(new KitSpec("AB", Map.of("A", 1, "B", 1)))));
        assertThat(r.vehicles()).hasSize(1);
        assertThat(r.placedCount()).isEqualTo(2);
        assertThat(r.meta().kitBalance()).containsExactly(Map.of("A", 1, "B", 1));
    }

    @Test
    void kitPlusLooseCartons() {
        List<CargoPackage> cartons = new ArrayList<>(many("mb", 6, 1590, 1590, 90, "18", true, "T-MB"));
        cartons.addAll(many("cb", 12, 730, 730, 800, "9", true, "T-CB"));
        cartons.addAll(many("x", 40, 600, 400, 400, "7", true, "LOOSE"));
        cartons.addAll(many("y", 25, 800, 600, 500, "12", false, "LOOSE2"));
        LoadPlanResult r = plan(request(cartons, List.of(dc40(3, 150)), SelectionMode.SINGLE_TYPE,
                List.of(new KitSpec("Table", Map.of("T-MB", 1, "T-CB", 2)))));
        assertThat(r.unplaced()).isEmpty();
        assertThat(r.meta().setsTotal()).isEqualTo(6);
        int mb = 0;
        int cb = 0;
        for (Map<String, Integer> b : r.meta().kitBalance()) {
            mb += b.getOrDefault("T-MB", 0);
            cb += b.getOrDefault("T-CB", 0);
            assertThat(b.getOrDefault("T-CB", 0)).isEqualTo(2 * b.getOrDefault("T-MB", 0));
        }
        assertThat(mb).isEqualTo(6);
        assertThat(cb).isEqualTo(12);
    }

    @Test
    void severalKitsAcrossSeveralVehicles() {
        List<CargoPackage> cartons = new ArrayList<>(many("pa", 30, 900, 1100, 700, "20", true, "P-A"));
        cartons.addAll(many("pb", 30, 600, 800, 500, "8", true, "P-B"));
        cartons.addAll(many("q1", 12, 600, 600, 600, "15", true, "Q-1"));
        cartons.addAll(many("q2", 24, 500, 400, 300, "4", true, "Q-2"));
        List<KitSpec> kits = List.of(new KitSpec("P", Map.of("P-A", 1, "P-B", 1)),
                new KitSpec("Q", Map.of("Q-1", 1, "Q-2", 2)));
        for (KitDistribution d : KitDistribution.values()) {
            LoadPlanRequest req = new LoadPlanRequest(cartons, List.of(dc20(6, 150)), SelectionMode.SINGLE_TYPE,
                    kits, d, false, null, 0L, Duration.ofSeconds(5));
            LoadPlanResult r = plan(req);
            assertThat(r.unplaced()).as("distribution %s", d).isEmpty();
            assertThat(r.vehicles().size()).isGreaterThanOrEqualTo(2);
            assertThat(r.meta().setsTotal()).isEqualTo(42);
        }
    }

    @Test
    @DisplayName("a kit beyond the fleet: whole sets stay behind, NO_VEHICLE_AVAILABLE, no vehicle holds half a set")
    void kitBeyondTheFleet() {
        List<CargoPackage> cartons = new ArrayList<>(many("a", 40, 1200, 1000, 1100, "30", true, "A"));
        cartons.addAll(many("b", 40, 1200, 1000, 1100, "30", true, "B"));
        LoadPlanResult r = plan(request(cartons, List.of(dc20(1, 0)), SelectionMode.SINGLE_TYPE,
                List.of(new KitSpec("AB", Map.of("A", 1, "B", 1)))));
        assertThat(r.vehicles()).hasSize(1);
        assertThat(r.unplaced()).isNotEmpty();
        assertThat(r.unplaced()).allSatisfy(u -> assertThat(u.reason()).isEqualTo(UnplacedReason.NO_VEHICLE_AVAILABLE));
        Map<String, Integer> balance = r.meta().kitBalance().get(0);
        assertThat(balance.get("A")).isEqualTo(balance.get("B"));
    }

    @Test
    @DisplayName("a kit part too large for every vehicle: that part TOO_LARGE, its mates load loose with a warning")
    void kitPartThatCanNeverLoad() {
        List<CargoPackage> cartons = new ArrayList<>(many("a", 4, 800, 600, 500, "10", true, "A"));
        cartons.add(carton("huge", 7000, 2000, 2000, "50", true, "B"));
        LoadPlanResult r = plan(request(cartons, List.of(dc20(2, 0)), SelectionMode.SINGLE_TYPE,
                List.of(new KitSpec("AB", Map.of("A", 1, "B", 1)))));
        assertThat(reasons(r)).containsExactly(Map.entry("huge", UnplacedReason.TOO_LARGE));
        assertThat(r.placedCount()).isEqualTo(4);
        assertThat(r.meta().kitWarnings()).extracting(KitWarning::type)
                .containsExactly(KitWarning.Type.FEWER_THAN_TWO_PARTS);
    }

    @Test
    void surplusPartsLoadLooseWithAWarning() {
        List<CargoPackage> cartons = new ArrayList<>(many("a", 5, 800, 600, 500, "10", true, "A"));
        cartons.addAll(many("b", 3, 800, 600, 500, "10", true, "B"));
        LoadPlanResult r = plan(request(cartons, List.of(dc20(2, 0)), SelectionMode.SINGLE_TYPE,
                List.of(new KitSpec("AB", Map.of("A", 1, "B", 1)))));
        assertThat(r.unplaced()).isEmpty();
        assertThat(r.meta().setsTotal()).isEqualTo(3);
        assertThat(r.meta().kitWarnings()).containsExactly(
                new KitWarning("AB", KitWarning.Type.SURPLUS_PARTS, "A", 2));
    }

    // ------------------------------------------------------------------ reasons and edge cases

    @Test
    void tooLargeAndTooHeavy() {
        List<CargoPackage> cartons = List.of(
                carton("big", 7000, 1000, 1000, "10", true, null),
                carton("heavy", 1000, 1000, 1000, "30000", true, null),
                carton("ok", 1000, 1000, 1000, "10", true, null));
        LoadPlanResult r = plan(request(cartons, List.of(dc20(1, 150)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(reasons(r)).containsOnly(Map.entry("big", UnplacedReason.TOO_LARGE),
                Map.entry("heavy", UnplacedReason.TOO_HEAVY));
        assertThat(r.placedCount()).isEqualTo(1);
    }

    @Test
    void exhaustedFleetIsNoVehicleAvailable() {
        List<CargoPackage> cartons = many("c", 30, 1200, 1000, 1100, "40", true, null);
        LoadPlanResult r = plan(request(cartons, List.of(dc20(1, 0)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.vehicles()).hasSize(1);
        assertThat(r.unplaced()).isNotEmpty()
                .allSatisfy(u -> assertThat(u.reason()).isEqualTo(UnplacedReason.NO_VEHICLE_AVAILABLE));
    }

    @Test
    void typeWithNoVehicleLeftIsNoVehicleAvailable() {
        LoadPlanResult r = plan(request(List.of(carton("c", 500, 500, 500, "5", true, null)),
                List.of(dc20(0, 0)), SelectionMode.FEWEST_VEHICLES, List.of()));
        assertThat(reasons(r)).containsExactly(Map.entry("c", UnplacedReason.NO_VEHICLE_AVAILABLE));
        assertThat(r.vehicles()).isEmpty();
    }

    @Test
    void cartonThatMayNotTurnKeepsItsOrientation() {
        // Fits only when turned (its height exceeds the truck's), so it must be left out, not laid on its side.
        LoadPlanResult r = plan(request(List.of(carton("tall", 1000, 1000, 2300, "20", false, null),
                carton("free", 1000, 1000, 2300, "20", true, null)), List.of(truck5t(1, 0)),
                SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(reasons(r)).containsExactly(Map.entry("tall", UnplacedReason.TOO_LARGE));
        Placement free = r.vehicles().get(0).placements().get(0);
        assertThat(free.h()).isLessThan(2300);
        assertThat(free.orientation()).isNotEqualTo(Orientation.LWH);
    }

    @Test
    void exactFitFillsTheVehicle() {
        VehicleSpec box = new VehicleSpec(LoadPlanAssertions.TRUCK, "CUBE", VehicleKind.TRUCK, 2000, 1000, 1000,
                new BigDecimal("1000"), 1, 0);
        LoadPlanResult r = plan(request(many("c", 8, 500, 500, 1000, "10", false, null), List.of(box),
                SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.unplaced()).isEmpty();
        assertThat(r.vehicles().get(0).volumeFillPct()).isEqualByComparingTo("100.00");
        assertThat(r.vehicles().get(0).weightFillPct()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("the corner clearance is per type: the same full-height carton fits one type and not the other")
    void gussetPerVehicleType() {
        CargoPackage wall = carton("wall", 5898, 2352, 2393, "100", false, null);
        assertThat(plan(request(List.of(wall), List.of(dc20(1, 0)), SelectionMode.SINGLE_TYPE, List.of()))
                .placedCount()).isEqualTo(1);
        assertThat(reasons(plan(request(List.of(wall), List.of(dc20(1, 150)), SelectionMode.SINGLE_TYPE,
                List.of())))).containsExactly(Map.entry("wall", UnplacedReason.TOO_LARGE));
    }

    @Test
    void payloadSplitsHeavyCargo() {
        LoadPlanResult r = plan(request(many("steel", 12, 600, 600, 400, "1000", true, "S"),
                List.of(truck5t(4, 0)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.unplaced()).isEmpty();
        assertThat(r.vehicles()).hasSize(3);
        r.vehicles().forEach(v -> assertThat(v.totalWeightKg()).isLessThanOrEqualTo(new BigDecimal("5000")));
    }

    @Test
    void singleTypePicksOneTypeAndFewestVehiclesMayMix() {
        List<CargoPackage> cartons = new ArrayList<>(many("c", 120, 1200, 1000, 1000, "50", true, "C"));
        cartons.addAll(many("d", 30, 600, 400, 400, "10", true, "D"));
        List<VehicleSpec> fleet = List.of(dc20(10, 150), dc40(2, 150), hc40(1, 150));
        LoadPlanResult single = plan(request(cartons, fleet, SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(single.vehicles()).extracting(PlannedVehicle::vehicleCode).containsOnly(
                single.vehicles().get(0).vehicleCode());
        LoadPlanResult fewest = plan(request(cartons, fleet, SelectionMode.FEWEST_VEHICLES, List.of()));
        assertThat(fewest.unplaced()).isEmpty();
        assertThat(fewest.vehicles().size()).isLessThanOrEqualTo(single.vehicles().size());
    }

    @Test
    @DisplayName("fill tops up opened vehicles and never opens one (docs 09 'Ưu tiên + hàng chèn')")
    void fillNeverOpensAVehicle() {
        List<CargoPackage> cartons = new ArrayList<>(many("main", 10, 1200, 1000, 1000, "50", true, "M"));
        for (int i = 0; i < 400; i++) {
            cartons.add(new CargoPackage("fill" + i, null, 500, 400, 300, new BigDecimal("6"), true, "F", null, null, true));
        }
        LoadPlanResult r = plan(request(cartons, List.of(dc20(5, 150)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.vehicles()).hasSize(1);
        assertThat(r.vehicles().get(0).placements()).anySatisfy(p -> assertThat(p.fill()).isTrue());
        assertThat(r.unplaced()).isNotEmpty().allSatisfy(u -> {
            assertThat(u.ref()).startsWith("fill");
            assertThat(u.reason()).isEqualTo(UnplacedReason.NO_SPACE);
        });

        LoadPlanResult onlyFill = plan(request(cartons.subList(10, 20), List.of(dc20(5, 150)),
                SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(onlyFill.vehicles()).isEmpty();
        assertThat(onlyFill.unplaced()).hasSize(10);
    }

    @Test
    void targetVehicleCountIsReportedWhenUnreachable() {
        LoadPlanRequest req = new LoadPlanRequest(many("c", 60, 1200, 1000, 1000, "50", true, "C"),
                List.of(dc20(10, 0)), SelectionMode.SINGLE_TYPE, List.of(), KitDistribution.FIRST, false, 1, 0L,
                Duration.ofSeconds(5));
        LoadPlanResult r = plan(req);
        assertThat(r.vehicles().size()).isGreaterThan(1);
        assertThat(r.meta().targetUnreachable()).isTrue();
        assertThat(r.meta().minVehicles()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void equalizeKeepsTheCountAndEvensTheFill() {
        List<CargoPackage> cartons = new ArrayList<>(many("c", 40, 1200, 1000, 1000, "50", true, "C"));
        cartons.addAll(many("s", 80, 500, 400, 300, "5", true, "S"));
        LoadPlanRequest plain = new LoadPlanRequest(cartons, List.of(dc20(5, 0)), SelectionMode.SINGLE_TYPE,
                List.of(), KitDistribution.FIRST, false, null, 0L, Duration.ofSeconds(5));
        LoadPlanRequest even = new LoadPlanRequest(cartons, List.of(dc20(5, 0)), SelectionMode.SINGLE_TYPE,
                List.of(), KitDistribution.FIRST, true, null, 0L, Duration.ofSeconds(5));
        LoadPlanResult a = plan(plain);
        LoadPlanResult b = plan(even);
        assertThat(b.vehicles()).hasSameSizeAs(a.vehicles());
        assertThat(spread(b)).isLessThanOrEqualTo(spread(a));
    }

    private static double spread(LoadPlanResult r) {
        double max = 0;
        double min = 100;
        for (PlannedVehicle v : r.vehicles()) {
            max = Math.max(max, v.volumeFillPct().doubleValue());
            min = Math.min(min, v.volumeFillPct().doubleValue());
        }
        return max - min;
    }

    @Test
    void refsComeBackUnchanged() {
        List<CargoPackage> cartons = List.of(carton("0192f5a4-7c1e-7b3a-9d2e-5f6a7b8c9d0e", 600, 400, 400, "5", true,
                null), carton("estimate-line-17#3", 600, 400, 400, "5", true, null));
        LoadPlanResult r = plan(request(cartons, List.of(dc20(1, 0)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.vehicles().get(0).placements()).extracting(Placement::ref)
                .containsExactlyInAnyOrder("0192f5a4-7c1e-7b3a-9d2e-5f6a7b8c9d0e", "estimate-line-17#3");
    }

    @Test
    void rejectsDuplicateRefsButNeverMeasuredData() {
        assertThatThrownBy(() -> planner.plan(request(List.of(carton("x", 1, 1, 1, "1", true, null),
                carton("x", 1, 1, 1, "1", true, null)), List.of(dc20(1, 0)), SelectionMode.SINGLE_TYPE, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        // A declared set whose part comes in two sizes is not kept - a warning, the cartons load loose, no exception.
        LoadPlanResult r = plan(request(List.of(carton("a1", 500, 500, 500, "1", true, "A"),
                carton("a2", 600, 500, 500, "1", true, "A"), carton("b", 500, 500, 500, "1", true, "B")),
                List.of(dc20(1, 0)), SelectionMode.SINGLE_TYPE, List.of(new KitSpec("AB", Map.of("A", 1, "B", 1)))));
        assertThat(r.unplaced()).isEmpty();
        assertThat(r.meta().kitWarnings()).containsExactly(
                new KitWarning("AB", KitWarning.Type.PARTS_OF_DIFFERENT_SIZES, "A", 0));
    }

    @Test
    @DisplayName("a tiny budget still returns a sound plan, promptly")
    void tinyBudget() {
        List<CargoPackage> cartons = LoadPlannerPropertyTest.randomCartons(new java.util.Random(3), 500, true);
        LoadPlanRequest req = new LoadPlanRequest(cartons, List.of(dc20(40, 150), dc40(20, 150), hc40(20, 150)),
                SelectionMode.FEWEST_VEHICLES, LoadPlannerPropertyTest.KITS, KitDistribution.AUTO, false, null, 0L,
                Duration.ofMillis(50));
        long t0 = System.nanoTime();
        LoadPlanResult r = new LoadPlanner().plan(req);
        assertSound(req, r);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1_050 + 2_000);
    }

    // ------------------------------------------------------------------ review round 1 (PR #156)

    private static CargoPackage part(String ref, String part, String group, int h) {
        return new CargoPackage(ref, null, 1000, 1000, h, new BigDecimal("10"), false, part, group, null, false);
    }

    /** A box that holds exactly one set: a 600 mm part under a 400 mm part. */
    private static VehicleSpec oneSetBox(int available) {
        return new VehicleSpec(LoadPlanAssertions.TRUCK, "ONE-SET", VehicleKind.TRUCK, 1000, 1000, 1000,
                new BigDecimal("100"), available, 0);
    }

    @Test
    @DisplayName("review #3: a set's parts come from one physical kit group (repro a1/g1, a2/g2, b2/g2, b1/g1)")
    void partsOfAPlacedSetComeFromOneKitGroup() {
        List<CargoPackage> cartons = List.of(part("a1", "A", "g1", 600), part("a2", "A", "g2", 600),
                part("b2", "B", "g2", 400), part("b1", "B", "g1", 400));
        LoadPlanResult r = plan(request(cartons, List.of(oneSetBox(2)), SelectionMode.SINGLE_TYPE,
                List.of(new KitSpec("AB", Map.of("A", 1, "B", 1)))));
        assertThat(r.vehicles()).hasSize(2);
        for (PlannedVehicle v : r.vehicles()) {
            assertThat(v.placements()).extracting(Placement::kitGroup).containsOnly(v.placements().get(0).kitGroup());
        }
        // With one vehicle the other set stays behind whole.
        LoadPlanResult one = plan(request(cartons, List.of(oneSetBox(1)), SelectionMode.SINGLE_TYPE,
                List.of(new KitSpec("AB", Map.of("A", 1, "B", 1)))));
        assertThat(one.vehicles().get(0).placements()).extracting(Placement::kitGroup).containsOnly("g1");
        assertThat(one.unplaced()).extracting(UnplacedPackage::ref).containsExactlyInAnyOrder("a2", "b2");
    }

    @Test
    @DisplayName("kit groups nobody declared are kept together all the same")
    void kitGroupsWithoutADeclaredKit() {
        List<CargoPackage> cartons = new ArrayList<>();
        for (int g = 0; g < 6; g++) {
            cartons.add(part("a" + g, "TOP", "set" + g, 600));
        }
        for (int g = 5; g >= 0; g--) {
            cartons.add(part("b" + g, "BASE", "set" + g, 400));
        }
        LoadPlanResult r = plan(request(cartons, List.of(oneSetBox(6)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.vehicles()).hasSize(6);
        assertThat(r.meta().setsTotal()).isEqualTo(6);
    }

    @Test
    @DisplayName("review #1: a fill carton never rests on less than 98 % of its base")
    void fillRestsOnNinetyEightPercent() {
        List<double[]> floor = List.of(new double[] {0, 0, 0, 1000, 100, 1000});
        assertThat(EmsFill.rests(520, 100, 0, 1000, 1000, floor, 98)).as("48 %").isFalse();
        assertThat(EmsFill.rests(30, 100, 0, 1000, 1000, floor, 98)).as("97 %").isFalse();
        assertThat(EmsFill.rests(20, 100, 0, 1000, 1000, floor, 98)).as("98 %").isTrue();
        assertThat(EmsFill.rests(0, 0, 0, 1000, 1000, List.of(), 98)).as("on the floor").isTrue();
    }

    @Test
    @DisplayName("review #2: the budget is a hard cap - 300 ms on 800 cartons of 120 sizes")
    void budgetIsAHardCap() {
        java.util.Random rnd = new java.util.Random(11);
        List<int[]> sizes = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            sizes.add(new int[] {200 + rnd.nextInt(1301), 200 + rnd.nextInt(1001), 150 + rnd.nextInt(851)});
        }
        List<CargoPackage> cartons = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            int[] s = sizes.get(rnd.nextInt(sizes.size()));
            cartons.add(new CargoPackage("c" + i, null, s[0], s[1], s[2], BigDecimal.valueOf(500 + rnd.nextInt(30000), 3),
                    rnd.nextInt(5) > 0, "SKU" + i % 120, null, null, i % 7 == 0));
        }
        for (SelectionMode mode : SelectionMode.values()) {
            LoadPlanRequest req = new LoadPlanRequest(cartons, List.of(dc20(40, 150), dc40(20, 150), hc40(20, 150)),
                    mode, List.of(), KitDistribution.AUTO, false, null, 0L, Duration.ofMillis(300));
            long t0 = System.nanoTime();
            LoadPlanResult r = new LoadPlanner().plan(req);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertSound(req, r);
            assertThat(ms).as("%s took %d ms on a 300 ms budget", mode, ms).isLessThan(300 + 1_000);
        }
    }

    @Test
    @DisplayName("gussets: whole millimetres on an odd length; the 45-foot posts only on a container")
    void gussetsOnOddLengthsAndOnlyOnContainers() {
        VehicleSpec odd45 = new VehicleSpec(LoadPlanAssertions.HC, "45HC-ODD", VehicleKind.CONTAINER, 13555, 2352,
                2698, new BigDecimal("27700"), 1, 150);
        assertThat(Gussets.of(new Bin(odd45))).extracting(g -> g.z).containsExactly(761.0, 12644.0, 761.0, 12644.0);
        LoadPlanResult r = plan(request(many("c", 40, 1200, 1000, 1000, "50", true, "C"), List.of(odd45),
                SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.unplaced()).isEmpty();
        VehicleSpec trailer = new VehicleSpec(LoadPlanAssertions.TRUCK, "TRAILER-13.6", VehicleKind.TRUCK, 13600,
                2450, 2700, new BigDecimal("30000"), 1, 150);
        assertThat(Gussets.of(new Bin(trailer))).extracting(g -> g.z).containsExactly(0.0, 0.0);
    }

    @Test
    @DisplayName("sets of different make-up sharing part codes never draw on each other's cartons")
    void setsOfDifferentMakeUpStayWhole() {
        List<CargoPackage> cartons = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            int parts = 2 + k % 2;
            for (int p = 1; p <= parts; p++) {
                cartons.add(new CargoPackage("s" + k + "/" + p, null, 900, 700, 600, new BigDecimal("12"), true,
                        "TABLE/" + p, "set-" + k, null, false));
            }
        }
        cartons.addAll(many("loose", 30, 600, 400, 400, "6", true, "LOOSE"));
        LoadPlanResult r = plan(request(cartons, List.of(truck5t(6, 120)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.meta().setsTotal()).isEqualTo(8);
        assertThat(r.meta().kitBalance()).allSatisfy(b -> assertThat(b.keySet())
                .allSatisfy(k -> assertThat(k).startsWith("TABLE/").doesNotContain(LoadPlanner.SET_MARK)));
    }

    // ------------------------------------------------------------------ doors (LP3-DOOR)

    private static VehicleSpec doorTruck(int available, Integer doorWidth, Integer doorHeight) {
        return new VehicleSpec(LoadPlanAssertions.TRUCK, "TRUCK-DOOR", VehicleKind.TRUCK, 6200, 2150, 2200,
                new BigDecimal("5000"), available, 0, doorWidth, doorHeight);
    }

    @Test
    @DisplayName("door: a carton that cannot turn and is taller than the door is TOO_LARGE")
    void doorBlocksTallCartons() {
        LoadPlanResult r = plan(request(List.of(carton("tall", 1000, 900, 2000, "20", false, null)),
                List.of(doorTruck(1, 2000, 1800)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(reasons(r)).containsExactly(Map.entry("tall", UnplacedReason.TOO_LARGE));
    }

    @Test
    @DisplayName("door: a carton that may turn goes in lying down")
    void rotationLetsACartonThroughTheDoor() {
        LoadPlanResult r = plan(request(List.of(carton("tall", 1000, 900, 2000, "20", true, null)),
                List.of(doorTruck(1, 2000, 1800)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.unplaced()).isEmpty();
        Placement p = r.vehicles().get(0).placements().get(0);
        assertThat(p.w()).isLessThanOrEqualTo(2000);
        assertThat(p.h()).isLessThanOrEqualTo(1800);
    }

    @Test
    @DisplayName("door: null means no limit")
    void noDoorMeansNoLimit() {
        LoadPlanResult r = plan(request(List.of(carton("tall", 1000, 900, 2000, "20", false, null)),
                List.of(doorTruck(1, null, null)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.unplaced()).isEmpty();
        assertThat(r.vehicles().get(0).placements().get(0).h()).isEqualTo(2000);
    }

    @Test
    @DisplayName("door: in a mixed fleet a carton blocked by one type's door rides only in the types it passes")
    void mixedFleetRoutesCartonsThroughTheDoorsTheyPass() {
        List<CargoPackage> cartons = new ArrayList<>(many("tall", 6, 1000, 900, 2000, "20", false, "TALL"));
        cartons.addAll(many("small", 30, 600, 400, 400, "6", true, "SMALL"));
        VehicleSpec lowDoor = new VehicleSpec(LoadPlanAssertions.TRUCK, "LOW-DOOR", VehicleKind.TRUCK, 6200, 2150,
                2200, new BigDecimal("5000"), 3, 0, 2000, 1500);
        LoadPlanResult r = plan(request(cartons, List.of(lowDoor, dc20(3, 0)), SelectionMode.FEWEST_VEHICLES,
                List.of()));
        assertThat(r.unplaced()).isEmpty();
        for (PlannedVehicle v : r.vehicles()) {
            if (v.vehicleCode().equals("LOW-DOOR")) {
                assertThat(v.placements()).allSatisfy(p -> assertThat(p.h()).isLessThanOrEqualTo(1500));
            }
        }
        LoadPlanResult lowOnly = plan(request(cartons, List.of(lowDoor), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(lowOnly.unplaced()).hasSize(6)
                .allSatisfy(u -> assertThat(u.reason()).isEqualTo(UnplacedReason.TOO_LARGE));
    }

    // ------------------------------------------------------------------ review round 2 (PR #156)

    @Test
    @DisplayName("review r2 #1: two sets of the same item, one part measured 1 mm larger, both stay whole")
    void setsOfTheSameItemWithAMillimetreDifference() {
        List<CargoPackage> cartons = List.of(
                new CargoPackage("t1", null, 1200, 800, 600, new BigDecimal("20"), true, "TABLE/1", "set-1", null, false),
                new CargoPackage("b1", null, 730, 730, 800, new BigDecimal("9"), true, "TABLE/2", "set-1", null, false),
                new CargoPackage("t2", null, 1201, 800, 600, new BigDecimal("20"), true, "TABLE/1", "set-2", null, false),
                new CargoPackage("b2", null, 730, 730, 800, new BigDecimal("9"), true, "TABLE/2", "set-2", null, false));
        for (List<KitSpec> kits : List.of(List.<KitSpec>of(), List.of(new KitSpec("T", Map.of("TABLE/1", 1,
                "TABLE/2", 1))))) {
            LoadPlanResult r = plan(request(cartons, List.of(truck5t(2, 0)), SelectionMode.SINGLE_TYPE, kits));
            assertThat(r.unplaced()).isEmpty();
            assertThat(r.meta().setsTotal()).isEqualTo(2);
            assertThat(r.meta().kitBalance()).allSatisfy(b -> assertThat(b.keySet())
                    .containsOnly("TABLE/1", "TABLE/2"));
        }
    }

    @Test
    @DisplayName("review r2 #1: odd but real data never throws - a set without part codes, a fill carton in a set")
    void oddSetDataIsLoadedNotRefused() {
        List<CargoPackage> cartons = List.of(
                new CargoPackage("p1", null, 600, 400, 400, new BigDecimal("5"), true, null, "set-x", null, false),
                new CargoPackage("p2", null, 500, 400, 300, new BigDecimal("4"), true, null, "set-x", null, false),
                new CargoPackage("f1", null, 400, 300, 300, new BigDecimal("2"), true, "F", "set-y", null, true),
                new CargoPackage("m1", null, 600, 400, 400, new BigDecimal("5"), true, "M", "set-y", null, false));
        LoadPlanResult r = plan(request(cartons, List.of(truck5t(1, 0)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.unplaced()).isEmpty();
    }

    @Test
    @DisplayName("review r2 #2: cartons the search had no time for are SEARCH_CUT_OFF, with budgetExhausted")
    void cutOffCartonsSaySo() {
        java.util.Random rnd = new java.util.Random(5);
        List<CargoPackage> cartons = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            cartons.add(carton("c" + i, 200 + rnd.nextInt(1200), 200 + rnd.nextInt(900), 150 + rnd.nextInt(800),
                    "3", true, "SKU" + i % 300));
        }
        LoadPlanRequest req = new LoadPlanRequest(cartons, List.of(dc20(200, 150), dc40(100, 150)),
                SelectionMode.FEWEST_VEHICLES, List.of(), KitDistribution.AUTO, false, null, 0L, Duration.ofMillis(5));
        LoadPlanResult r = new LoadPlanner().plan(req);
        assertSound(req, r);
        assertThat(r.meta().budgetExhausted()).isTrue();
        assertThat(r.unplaced()).isNotEmpty().allSatisfy(u -> assertThat(u.reason())
                .isEqualTo(UnplacedReason.SEARCH_CUT_OFF));
    }

    // ------------------------------------------------------------------ review round 3 (PR #156)

    /** A truck that holds exactly one 2-box unit: two 2000 x 2000 x 1100 boxes stacked. */
    private static VehicleSpec oneUnitTruck(int available) {
        return new VehicleSpec(LoadPlanAssertions.TRUCK, "ONE-UNIT", VehicleKind.TRUCK, 2100, 2100, 2300,
                new BigDecimal("5000"), available, 0);
    }

    private static CargoPackage unitBox(String ref, String part, String group, int length) {
        return new CargoPackage(ref, null, length, 2000, 1100, new BigDecimal("100"), false, part, group, null, false);
    }

    @Test
    @DisplayName("review r3: three same-size 2-box units, five one-unit trucks - one unit per truck, nothing left")
    void moreSetsInAShareThanFitAreLaidSetBySet() {
        List<CargoPackage> declared = new ArrayList<>();
        for (int u = 0; u < 3; u++) {
            declared.add(unitBox("u" + u + "/1", "UNIT/1", null, 2000));
            declared.add(unitBox("u" + u + "/2", "UNIT/2", null, 2000));
        }
        LoadPlanResult r = plan(new LoadPlanRequest(declared, List.of(oneUnitTruck(5)), SelectionMode.SINGLE_TYPE,
                List.of(new KitSpec("UNIT", Map.of("UNIT/1", 1, "UNIT/2", 1))), KitDistribution.FIRST, false, null,
                0L, Duration.ofSeconds(5)));
        assertThat(r.unplaced()).isEmpty();
        assertThat(r.vehicles()).hasSize(3);
        assertThat(r.meta().kitBalance()).allSatisfy(b -> assertThat(b).containsOnly(Map.entry("UNIT/1", 1),
                Map.entry("UNIT/2", 1)));

        List<CargoPackage> grouped = new ArrayList<>();
        for (int u = 0; u < 3; u++) {
            grouped.add(unitBox("g" + u + "/1", "UNIT/1", "unit-" + u, 2000));
            grouped.add(unitBox("g" + u + "/2", "UNIT/2", "unit-" + u, 2000));
        }
        LoadPlanResult g = plan(new LoadPlanRequest(grouped, List.of(oneUnitTruck(5)), SelectionMode.SINGLE_TYPE,
                List.of(), KitDistribution.FIRST, false, null, 0L, Duration.ofSeconds(5)));
        assertThat(g.unplaced()).isEmpty();
        assertThat(g.vehicles()).hasSize(3);
    }

    @Test
    @DisplayName("review r3: three sets whose parts were measured a millimetre apart, one per truck")
    void setsWithSlightlyDifferentPartsAreLaidSetBySet() {
        List<CargoPackage> cartons = new ArrayList<>();
        for (int u = 0; u < 3; u++) {
            cartons.add(unitBox("s" + u + "/1", "UNIT/1", "set-" + u, 2000 + u));
            cartons.add(unitBox("s" + u + "/2", "UNIT/2", "set-" + u, 2000 - u));
        }
        LoadPlanResult r = plan(new LoadPlanRequest(cartons, List.of(oneUnitTruck(5)), SelectionMode.SINGLE_TYPE,
                List.of(), KitDistribution.FIRST, false, null, 0L, Duration.ofSeconds(5)));
        assertThat(r.unplaced()).isEmpty();
        assertThat(r.vehicles()).hasSize(3);
    }

    @Test
    @DisplayName("review r3: a fill carton naming a set loads as plain fill, with a warning")
    void fillInASetIsPlainFill() {
        List<CargoPackage> cartons = List.of(
                new CargoPackage("m1", null, 600, 400, 400, new BigDecimal("5"), true, "M/1", "set-1", null, false),
                new CargoPackage("m2", null, 600, 400, 400, new BigDecimal("5"), true, "M/2", "set-1", null, false),
                new CargoPackage("f1", null, 400, 300, 300, new BigDecimal("2"), true, "F", "set-1", null, true));
        LoadPlanResult r = plan(request(cartons, List.of(truck5t(1, 0)), SelectionMode.SINGLE_TYPE, List.of()));
        assertThat(r.unplaced()).isEmpty();
        assertThat(r.meta().kitWarnings()).contains(new KitWarning("set-1", KitWarning.Type.FILL_IN_SET, "F", 1));
    }
}
