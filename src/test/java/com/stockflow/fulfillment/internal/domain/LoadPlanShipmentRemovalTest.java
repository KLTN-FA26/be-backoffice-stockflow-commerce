package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.KitDistribution;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanRequest;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanResult;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanner;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleKind;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Taking one shipment out of a PLANNED plan so that only the vehicles that carried it wait to be recalculated (09 §4.2
 * step 9; RTM 09A-158), as an order cancelled out of a plan does - not the whole plan back to DRAFT.
 */
class LoadPlanShipmentRemovalTest {

    private static final Instant NOW = Instant.parse("2026-11-18T08:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID DISPATCHER = UUID.randomUUID();
    private static final UUID TRUCK_TYPE = UUID.randomUUID();
    /** A 2 x 1 x 1 m truck: room for two 1 m cubes along its length. */
    private static final VehicleSpec TRUCK = new VehicleSpec(TRUCK_TYPE, "TRUCK-TEST", VehicleKind.TRUCK, 2000, 1000,
            1000, new BigDecimal("1000"), 3, 0);

    private final UUID shipmentA = UUID.randomUUID();
    private final UUID shipmentB = UUID.randomUUID();
    private final UUID shipmentC = UUID.randomUUID();
    private final Map<UUID, CargoPackage> cartons = new LinkedHashMap<>();
    private final Map<UUID, UUID> shipmentOf = new LinkedHashMap<>();

    private UUID cube(UUID shipment) {
        UUID id = UUID.randomUUID();
        cartons.put(id, new CargoPackage(id.toString(), null, 1000, 1000, 1000, new BigDecimal("100"), true, "CUBE",
                null, shipment.toString(), false));
        shipmentOf.put(id, shipment);
        return id;
    }

    private static ErrorCode code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static LoadPlan draft(SelectionMode mode) {
        return LoadPlan.draft(UUID.randomUUID(), "LP-20261118-0001", UUID.randomUUID(), HCM, mode, null);
    }

    /** The plan calculated by the planner, as the service does. */
    private LoadPlan planned() {
        LoadPlan plan = draft(SelectionMode.SINGLE_TYPE);
        plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, DISPATCHER, NOW);
        LoadPlanResult result = new LoadPlanner().plan(new LoadPlanRequest(List.copyOf(cartons.values()),
                List.of(TRUCK), SelectionMode.SINGLE_TYPE, List.of(), KitDistribution.AUTO, false, null, 0L,
                Duration.ofSeconds(2)));
        plan.applyCalculation(result, Map.of(TRUCK_TYPE, TRUCK), UUID::randomUUID, DISPATCHER, NOW, cartons);
        return plan;
    }

    private static LoadPlan.Vehicle vehicleOf(LoadPlan plan, UUID packageId) {
        return plan.activeVehicles().stream()
                .filter(v -> v.placements().stream().anyMatch(p -> p.packageId().equals(packageId)))
                .findFirst().orElseThrow();
    }

    // ---- one shipment taken out (09 §4.2 step 9) -------------------------------------------------------------------

    @Test
    @DisplayName("09 §4.2 step 9: a shipment taken out of a PLANNED plan releases its parcels (INPUTS_CHANGED); the "
            + "vehicle that carried them and keeps others waits to be recalculated, the others keep their layout, "
            + "and the plan stays PLANNED")
    void onlyTheAffectedVehicleWaits() {
        UUID a1 = cube(shipmentA);
        UUID b1 = cube(shipmentB);
        UUID c1 = cube(shipmentC);
        UUID c2 = cube(shipmentC);
        LoadPlan plan = planned();
        assertThat(plan.activeVehicles()).hasSize(2);
        LoadPlan.Vehicle withB = vehicleOf(plan, b1);
        LoadPlan.Vehicle other = plan.activeVehicles().stream().filter(v -> v.seq() != withB.seq()).findFirst()
                .orElseThrow();
        Set<UUID> sharing = new java.util.HashSet<>();
        withB.placements().forEach(p -> sharing.add(p.packageId()));
        assertThat(sharing).as("two cubes per truck: B shares its truck").hasSize(2);

        Set<UUID> released = plan.removeShipment(shipmentB, NOW);

        assertThat(released).containsExactly(b1);
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.PLANNED);
        assertThat(plan.members()).filteredOn(m -> m.packageId().equals(b1)).singleElement().satisfies(m -> {
            assertThat(m.active()).isFalse();
            assertThat(m.releaseReason()).isEqualTo(LoadPlanReleaseReason.INPUTS_CHANGED);
        });
        LoadPlan.Vehicle after = plan.requireVehicle(withB.seq());
        assertThat(after.needsRecalculation()).isTrue();
        assertThat(after.placements()).extracting(LoadPlan.Placed::packageId).doesNotContain(b1).hasSize(1);
        LoadPlan.Vehicle untouched = plan.requireVehicle(other.seq());
        assertThat(untouched.needsRecalculation()).isFalse();
        assertThat(untouched.placements()).isEqualTo(other.placements());
        assertThat(code(() -> plan.confirm(DISPATCHER, NOW, cartons)))
                .isEqualTo(ErrorCode.LOAD_PLAN_NEEDS_RECALCULATION);
        assertThat(List.of(a1, c1, c2)).allSatisfy(id -> assertThat(plan.activePackageIds()).contains(id));
    }

    @Test
    @DisplayName("a vehicle that carried only the shipment leaves the plan; one that keeps other parcels waits")
    void anEmptiedVehicleLeaves() {
        cube(shipmentA);
        cube(shipmentB);
        cube(shipmentB);
        LoadPlan plan = planned();
        assertThat(plan.activeVehicles()).hasSize(2);
        Set<UUID> ofB = Set.copyOf(shipmentOf.entrySet().stream().filter(e -> e.getValue().equals(shipmentB))
                .map(Map.Entry::getKey).toList());
        List<Integer> onlyB = plan.activeVehicles().stream()
                .filter(v -> v.placements().stream().allMatch(p -> ofB.contains(p.packageId()))).map(v -> v.seq())
                .toList();
        List<Integer> mixed = plan.activeVehicles().stream().map(v -> v.seq()).filter(seq -> !onlyB.contains(seq))
                .toList();
        assertThat(onlyB).as("three cubes, two per truck: one truck holds B alone").isNotEmpty();
        List<Integer> lostSome = plan.activeVehicles().stream().filter(v -> mixed.contains(v.seq()))
                .filter(v -> v.placements().stream().anyMatch(p -> ofB.contains(p.packageId()))).map(v -> v.seq())
                .toList();

        plan.removeShipment(shipmentB, NOW);

        assertThat(plan.activeVehicles()).extracting(v -> v.seq()).containsExactlyElementsOf(mixed);
        for (LoadPlan.Vehicle v : plan.activeVehicles()) {
            assertThat(v.needsRecalculation()).as("vehicle %d", v.seq()).isEqualTo(lostSome.contains(v.seq()));
        }
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.PLANNED);
    }

    @Test
    @DisplayName("only a PLANNED plan has a shipment taken out (a confirmed one is reopened first, BR-14), and only a "
            + "shipment it holds")
    void whenAShipmentIsTakenOut() {
        cube(shipmentA);
        cube(shipmentB);
        LoadPlan draft = draft(SelectionMode.SINGLE_TYPE);
        draft.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, DISPATCHER, NOW);
        assertThat(code(() -> draft.removeShipment(shipmentA, NOW))).isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);

        LoadPlan plan = planned();
        assertThat(code(() -> plan.removeShipment(UUID.randomUUID(), NOW)))
                .isEqualTo(ErrorCode.LOAD_PLAN_SHIPMENT_NOT_IN_PLAN);
        plan.confirm(DISPATCHER, NOW, cartons);
        assertThat(code(() -> plan.removeShipment(shipmentA, NOW))).isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);
        plan.reopen("xe đến khác loại", false, DISPATCHER, NOW);
        assertThat(plan.removeShipment(shipmentA, NOW)).hasSize(1);
    }
}
