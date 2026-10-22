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

    /** The original's work rate: same search, a fraction of the time, so the suite stays quick and reproducible. */
    private final LoadPlanner planner = new LoadPlanner(8.0);

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
        VehicleSpec box = new VehicleSpec(LoadPlanAssertions.TRUCK, "CUBE", 2000, 1000, 1000,
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
    void rejectsDuplicateRefsAndMixedKitParts() {
        assertThatThrownBy(() -> planner.plan(request(List.of(carton("x", 1, 1, 1, "1", true, null),
                carton("x", 1, 1, 1, "1", true, null)), List.of(dc20(1, 0)), SelectionMode.SINGLE_TYPE, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.plan(request(List.of(carton("a1", 500, 500, 500, "1", true, "A"),
                carton("a2", 600, 500, 500, "1", true, "A"), carton("b", 500, 500, 500, "1", true, "B")),
                List.of(dc20(1, 0)), SelectionMode.SINGLE_TYPE, List.of(new KitSpec("AB", Map.of("A", 1, "B", 1))))))
                .isInstanceOf(IllegalArgumentException.class);
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
}
