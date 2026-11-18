package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.assertSound;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.dc20;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.truck5t;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The docs' selection modes (kltn-docs 09 §4.2 "Chế độ chọn xe"; WF4-TRIAL-EST) on small inputs whose answer is known:
 * 1 m cubes, so a 5-tonne truck (6200 x 2150 x 2200, no corner clearance) takes exactly 6 x 2 x 2 = 24 and a 20DC
 * (5898 x 2352 x 2393) 5 x 2 x 2 = 20.
 */
class LoadPlannerModesTest {

    private static final int CUBE = 1000;

    private final LoadPlanner planner = new LoadPlanner(LoadPlanAssertions.TEST_RATE);

    private LoadPlanResult plan(LoadPlanRequest request) {
        LoadPlanResult result = planner.plan(request);
        assertSound(request, result);
        return result;
    }

    private static LoadPlanRequest request(List<CargoPackage> cartons, List<VehicleSpec> types, SelectionMode mode) {
        return new LoadPlanRequest(cartons, types, mode, List.of(), KitDistribution.AUTO, false, null, 0L,
                Duration.ofSeconds(5));
    }

    /** {@code n} cubes of one line: the line is their {@code sourceRef}, as a sheet row's boxes are. */
    private static List<CargoPackage> line(String line, int n, String kg, boolean fill) {
        List<CargoPackage> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new CargoPackage(line + "#" + i, line, CUBE, CUBE, CUBE, new BigDecimal(kg), true, line, null,
                    null, fill));
        }
        return out;
    }

    private static List<CargoPackage> concat(List<List<CargoPackage>> lines) {
        List<CargoPackage> out = new ArrayList<>();
        lines.forEach(out::addAll);
        return out;
    }

    /** The vehicles (by seq) each line's placed cartons ride in. */
    private static Map<String, Set<Integer>> vehiclesByLine(LoadPlanResult r) {
        Map<String, Set<Integer>> out = new HashMap<>();
        for (PlannedVehicle v : r.vehicles()) {
            for (Placement p : v.placements()) {
                out.computeIfAbsent(p.sourceRef(), k -> new HashSet<>()).add(v.seq());
            }
        }
        return out;
    }

    private static long unplaced(LoadPlanResult r, UnplacedReason reason) {
        return r.unplaced().stream().filter(u -> u.reason() == reason).count();
    }

    // ------------------------------------------------------------------ every mode

    @ParameterizedTest
    @EnumSource(SelectionMode.class)
    @DisplayName("every mode gives a sound plan of the same list (piece check, BR-10, BR-12) and the same plan twice")
    void everyModeIsSoundAndReproducible(SelectionMode mode) {
        List<CargoPackage> cartons = concat(List.of(line("A", 14, "100", false), line("B", 14, "100", false),
                line("C", 6, "50", true)));
        LoadPlanRequest req = request(cartons, List.of(truck5t(3, 0), dc20(2, 0)), mode);

        LoadPlanResult first = plan(req);
        LoadPlanResult second = plan(req);

        assertThat(first.meta().budgetExhausted()).isFalse();
        assertThat(second.vehicles()).isEqualTo(first.vehicles());
        assertThat(second.unplaced()).isEqualTo(first.unplaced());
    }

    @Test
    @DisplayName("only SINGLE_TYPE and FEWEST_VEHICLES are stored by plans and order estimates; 'Chỉnh tay' is no mode")
    void storableModes() {
        assertThat(SelectionMode.values()).extracting(SelectionMode::name).containsExactly("SINGLE_TYPE",
                "FEWEST_VEHICLES", "KEEP_LINES", "PRIORITY_FILL", "OVER_CAPACITY");
        // WF4-LP-MODES: a plan and an estimate store every mode but PRIORITY_FILL (docs issue #40, question 4).
        assertThat(SelectionMode.values()).filteredOn(SelectionMode::storable)
                .containsExactly(SelectionMode.SINGLE_TYPE, SelectionMode.FEWEST_VEHICLES, SelectionMode.KEEP_LINES,
                        SelectionMode.OVER_CAPACITY);
    }

    // ------------------------------------------------------------------ Giữ nguyên dòng

    @Test
    @DisplayName("KEEP_LINES: each line rides in one vehicle; one type alone splits a line over two")
    void keepLinesKeepsEveryLineWhole() {
        // 14 + 14 + 4 cubes: two trucks either way, but one type alone fills the first truck to 24 and splits B.
        List<CargoPackage> cartons = concat(List.of(line("A", 14, "100", false), line("B", 14, "100", false),
                line("C", 4, "100", false)));
        List<VehicleSpec> fleet = List.of(truck5t(3, 0));

        LoadPlanResult loose = plan(request(cartons, fleet, SelectionMode.SINGLE_TYPE));
        LoadPlanResult kept = plan(request(cartons, fleet, SelectionMode.KEEP_LINES));

        assertThat(loose.vehicles()).hasSize(2);
        assertThat(vehiclesByLine(loose).values()).anySatisfy(seqs -> assertThat(seqs).hasSize(2));

        assertThat(kept.unplaced()).isEmpty();
        assertThat(kept.vehicles()).hasSize(2);
        assertThat(kept.vehicles()).extracting(PlannedVehicle::seq).containsExactly(1, 2);
        assertThat(vehiclesByLine(kept)).hasSize(3).allSatisfy((ln, seqs) -> assertThat(seqs).hasSize(1));
        // First fit, largest line first: C joins A's truck (14 + 4 <= 24) rather than opening a third.
        assertThat(vehiclesByLine(kept).get("C")).isEqualTo(vehiclesByLine(kept).get("A"));
        assertThat(kept.meta().algorithm()).isEqualTo(LineKeeper.ALGORITHM);
    }

    @Test
    @DisplayName("KEEP_LINES: the payload decides too - two lines that fit one truck by volume but not by weight split")
    void keepLinesHonoursThePayload() {
        // 10 + 10 cubes fit one truck (24), but 2 x 10 x 300 kg = 6 000 kg is over its 5 000 kg payload.
        List<CargoPackage> cartons = concat(List.of(line("A", 10, "300", false), line("B", 10, "300", false)));

        LoadPlanResult r = plan(request(cartons, List.of(truck5t(2, 0)), SelectionMode.KEEP_LINES));

        assertThat(r.unplaced()).isEmpty();
        assertThat(r.vehicles()).hasSize(2);
        assertThat(r.vehicles()).allSatisfy(v -> assertThat(v.totalWeightKg()).isEqualByComparingTo("3000"));
        assertThat(vehiclesByLine(r)).allSatisfy((ln, seqs) -> assertThat(seqs).hasSize(1));
    }

    @Test
    @DisplayName("KEEP_LINES: a line too big for one vehicle loads what fits; the excess is NO_SPACE, not a 2nd truck")
    void keepLinesReportsTheExcessOfALine() {
        LoadPlanResult r = plan(request(line("A", 30, "100", false), List.of(truck5t(2, 0)),
                SelectionMode.KEEP_LINES));

        assertThat(r.vehicles()).singleElement().satisfies(v -> assertThat(v.placements()).hasSize(24));
        assertThat(r.unplaced()).hasSize(6).allSatisfy(u -> {
            assertThat(u.reason()).isEqualTo(UnplacedReason.NO_SPACE);
            assertThat(u.sourceRef()).isEqualTo("A");
        });
    }

    @Test
    @DisplayName("KEEP_LINES: no vehicle left for a line - the whole line is NO_VEHICLE_AVAILABLE (BR-12)")
    void keepLinesWithNoVehicleLeft() {
        List<CargoPackage> cartons = concat(List.of(line("A", 14, "100", false), line("B", 14, "100", false)));

        LoadPlanResult r = plan(request(cartons, List.of(truck5t(1, 0)), SelectionMode.KEEP_LINES));

        assertThat(r.vehicles()).singleElement().satisfies(v -> assertThat(v.placements()).hasSize(14));
        assertThat(r.unplaced()).hasSize(14).allSatisfy(u -> assertThat(u.reason())
                .isEqualTo(UnplacedReason.NO_VEHICLE_AVAILABLE));
        assertThat(r.unplaced()).extracting(UnplacedPackage::sourceRef).containsOnly(
                r.vehicles().getFirst().placements().getFirst().sourceRef().equals("A") ? "B" : "A");
    }

    @Test
    @DisplayName("KEEP_LINES: a carton no vehicle can take is TOO_LARGE and does not hold its line back")
    void keepLinesTooLargeCarton() {
        List<CargoPackage> cartons = new ArrayList<>(line("A", 4, "100", false));
        cartons.add(new CargoPackage("A#big", "A", 9000, CUBE, CUBE, new BigDecimal("100"), true, "A", null, null,
                false));

        LoadPlanResult r = plan(request(cartons, List.of(truck5t(1, 0)), SelectionMode.KEEP_LINES));

        assertThat(r.vehicles()).singleElement().satisfies(v -> assertThat(v.placements()).hasSize(4));
        assertThat(r.unplaced()).singleElement().satisfies(u -> {
            assertThat(u.ref()).isEqualTo("A#big");
            assertThat(u.reason()).isEqualTo(UnplacedReason.TOO_LARGE);
        });
    }

    @Test
    @DisplayName("KEEP_LINES keeps the real time cap: past it, the lines not loaded are SEARCH_CUT_OFF")
    void keepLinesKeepsTheWallLimit() {
        // Every read of the clock moves it 13 s: the 12 s cap has passed before the first line is tried.
        AtomicLong now = new AtomicLong();
        LoadPlanner slow = new LoadPlanner(LoadPlanAssertions.TEST_RATE, SpaceChoice.IMPROVED, null,
                () -> now.addAndGet(13_000_000_000L));
        LoadPlanRequest req = new LoadPlanRequest(line("A", 10, "100", false), List.of(truck5t(1, 0)),
                SelectionMode.KEEP_LINES, List.of(), KitDistribution.AUTO, false, null, 0L, Duration.ofSeconds(5),
                Duration.ofSeconds(12));

        LoadPlanResult r = slow.plan(req);

        assertSound(req, r);
        assertThat(r.meta().budgetExhausted()).isTrue();
        assertThat(r.unplaced()).hasSize(10)
                .allSatisfy(u -> assertThat(u.reason()).isEqualTo(UnplacedReason.SEARCH_CUT_OFF));
    }

    // ------------------------------------------------------------------ Ưu tiên + hàng chèn

    @Test
    @DisplayName("PRIORITY_FILL: the main cargo decides the vehicles; fill only tops up and never opens one")
    void priorityFillNeverOpensAVehicle() {
        // 20 main cubes need one truck; 30 fill cubes would need a second, which is on offer but never opened.
        List<CargoPackage> cartons = concat(List.of(line("M", 20, "100", false), line("F", 30, "10", true)));

        LoadPlanResult r = plan(request(cartons, List.of(truck5t(3, 0)), SelectionMode.PRIORITY_FILL));

        assertThat(r.vehicles()).singleElement().satisfies(v -> {
            assertThat(v.placements()).hasSize(24);
            assertThat(v.placements()).filteredOn(Placement::fill).hasSize(4)
                    .allSatisfy(p -> assertThat(p.sourceRef()).isEqualTo("F"));
        });
        assertThat(r.unplaced()).hasSize(26).allSatisfy(u -> {
            assertThat(u.sourceRef()).isEqualTo("F");
            assertThat(u.reason()).isEqualTo(UnplacedReason.NO_SPACE);
        });
    }

    @Test
    @DisplayName("PRIORITY_FILL picks vehicles as SINGLE_TYPE does: one type, never a mix")
    void priorityFillUsesOneType() {
        List<CargoPackage> cartons = concat(List.of(line("M", 40, "100", false), line("F", 4, "10", true)));

        LoadPlanResult r = plan(request(cartons, List.of(truck5t(3, 0), dc20(3, 0)), SelectionMode.PRIORITY_FILL));

        assertThat(r.vehicles()).extracting(PlannedVehicle::vehicleCode).hasSizeGreaterThanOrEqualTo(2)
                .containsOnly(r.vehicles().getFirst().vehicleCode());
        assertThat(r.unplaced()).extracting(UnplacedPackage::sourceRef).doesNotContain("M");
    }

    // ------------------------------------------------------------------ Vượt sức chứa

    @Test
    @DisplayName("OVER_CAPACITY: more than the fleet holds - every vehicle on offer is used, the rest is reported")
    void overCapacityLoadsTheMostTheFleetHolds() {
        // 60 cubes; on offer 1 truck (24) and 1 20DC (20): the most that fits is 44.
        List<CargoPackage> cartons = line("A", 60, "100", false);
        List<VehicleSpec> fleet = List.of(truck5t(1, 0), dc20(1, 0));

        LoadPlanResult single = plan(request(cartons, fleet, SelectionMode.SINGLE_TYPE));
        LoadPlanResult over = plan(request(cartons, fleet, SelectionMode.OVER_CAPACITY));

        assertThat(single.placedCount()).isEqualTo(24);
        assertThat(over.placedCount()).isEqualTo(44);
        assertThat(over.vehicles()).extracting(PlannedVehicle::vehicleCode)
                .containsExactlyInAnyOrder("TRUCK-5T", "20DC");
        assertThat(over.unplaced()).hasSize(16);
        assertThat(unplaced(over, UnplacedReason.NO_VEHICLE_AVAILABLE)).isEqualTo(16);
    }
}
