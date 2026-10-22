package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.KitDistribution;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanRequest;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanResult;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanner;
import com.stockflow.fulfillment.internal.domain.loadplanning.Orientation;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleKind;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The load plan of kltn-docs 09 §5 (SCRUM-287 PR D): DRAFT → PLANNED → CONFIRMED → DISPATCHED / CANCELLED, inputs that
 * send a plan back to DRAFT, the manual edit checked as a whole (BR-11, KIT_SPLIT), BR-13 acknowledgement, BR-14 no
 * reopen or cancel once a vehicle loads, and an order cancelled out of a plan.
 */
class LoadPlanTest {

    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID DISPATCHER = UUID.randomUUID();
    private static final UUID TRUCK_TYPE = UUID.randomUUID();
    /** A 2 x 1 x 1 m box truck carrying 1 t: room for two 1 m cubes side by side along its length. */
    private static final VehicleSpec TRUCK = new VehicleSpec(TRUCK_TYPE, "TRUCK-TEST", VehicleKind.TRUCK, 2000, 1000,
            1000, new BigDecimal("1000"), 3, 0);

    private final UUID shipmentA = UUID.randomUUID();
    private final UUID shipmentB = UUID.randomUUID();
    private final Map<UUID, CargoPackage> cartons = new LinkedHashMap<>();
    private final Map<UUID, UUID> shipmentOf = new LinkedHashMap<>();

    private UUID cube(UUID shipment, String kitGroup) {
        UUID id = UUID.randomUUID();
        // A kit's parts have their own part codes (SKU n/N), as LoadPlanParcels names them.
        cartons.put(id, new CargoPackage(id.toString(), null, 1000, 1000, 1000, new BigDecimal("100"), true,
                kitGroup == null ? "CUBE" : "KIT " + (cartons.size() + 1), kitGroup, shipment.toString(), false));
        shipmentOf.put(id, shipment);
        return id;
    }

    private static ErrorCode code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static LoadPlan draft() {
        return LoadPlan.draft(UUID.randomUUID(), "LP-20261022-0001", UUID.randomUUID(), HCM, SelectionMode.SINGLE_TYPE,
                null);
    }

    private LoadPlanResult solve(int available) {
        VehicleSpec type = new VehicleSpec(TRUCK_TYPE, "TRUCK-TEST", VehicleKind.TRUCK, 2000, 1000, 1000,
                new BigDecimal("1000"), available, 0);
        return new LoadPlanner().plan(new LoadPlanRequest(List.copyOf(cartons.values()), List.of(type),
                SelectionMode.SINGLE_TYPE, List.of(), KitDistribution.AUTO, false, null, 0L, Duration.ofSeconds(2)));
    }

    private LoadPlan planned(int available) {
        LoadPlan plan = draft();
        plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, DISPATCHER, NOW);
        plan.applyCalculation(solve(available), Map.of(TRUCK_TYPE, TRUCK), UUID::randomUUID, DISPATCHER, NOW, cartons);
        return plan;
    }

    @Test
    @DisplayName("DRAFT → PLANNED → CONFIRMED → DISPATCHED; a dispatched plan is neither reopened nor cancelled")
    void theHappyPath() {
        cube(shipmentA, null);
        cube(shipmentA, null);
        LoadPlan plan = draft();
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.DRAFT);
        assertThat(code(() -> plan.applyCalculation(solve(1), Map.of(), UUID::randomUUID, DISPATCHER, NOW, cartons)))
                .as("nothing chosen yet").isEqualTo(ErrorCode.LOAD_PLAN_EMPTY);
        assertThat(plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, DISPATCHER, NOW))
                .containsExactlyInAnyOrderElementsOf(cartons.keySet());
        assertThat(code(() -> plan.confirm(DISPATCHER, NOW, cartons))).isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);

        plan.applyCalculation(solve(1), Map.of(TRUCK_TYPE, TRUCK), UUID::randomUUID, DISPATCHER, NOW, cartons);
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.PLANNED);
        assertThat(plan.activeVehicles()).hasSize(1);
        assertThat(plan.placedPackageIds()).containsExactlyInAnyOrderElementsOf(cartons.keySet());
        assertThat(plan.activeVehicles().getFirst().usedWeightKg()).isEqualByComparingTo("200");
        assertThat(plan.activeVehicles().getFirst().volumeFillPct()).isEqualByComparingTo("100.00");

        List<LoadPlan.Vehicle> loads = plan.confirm(DISPATCHER, NOW, cartons);
        assertThat(loads).hasSize(1);
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.CONFIRMED);
        assertThat(code(() -> plan.editPlacements(List.of(), List.of(), cartons, NOW)))
                .as("BR-14: locked once confirmed").isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);
        assertThat(code(() -> plan.setInputs(Map.of(), List.of(), null, DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);

        plan.markDispatched(NOW);
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.DISPATCHED);
        assertThat(code(() -> plan.reopen("x", false, DISPATCHER, NOW))).isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);
        assertThat(code(() -> plan.cancel("x", false, DISPATCHER, NOW))).isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);
    }

    @Test
    @DisplayName("09 §5: changing the parcels, types or mode of a PLANNED plan sends it back to DRAFT with no vehicle; "
            + "the same inputs again change nothing; a parcel taken out is released INPUTS_CHANGED")
    void inputsSendThePlanBackToDraft() {
        UUID a = cube(shipmentA, null);
        UUID b = cube(shipmentB, null);
        LoadPlan plan = planned(1);
        assertThat(plan.setInputs(Map.copyOf(shipmentOf), List.of(TRUCK_TYPE), SelectionMode.SINGLE_TYPE, DISPATCHER,
                NOW)).isEmpty();
        assertThat(plan.status()).as("same inputs").isEqualTo(LoadPlanStatus.PLANNED);

        plan.setInputs(Map.of(a, shipmentA), List.of(TRUCK_TYPE), null, DISPATCHER, NOW);
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.DRAFT);
        assertThat(plan.activeVehicles()).isEmpty();
        assertThat(plan.activePackageIds()).containsExactly(a);
        assertThat(plan.members()).filteredOn(m -> m.packageId().equals(b)).singleElement()
                .satisfies(m -> assertThat(m.releaseReason()).isEqualTo(LoadPlanReleaseReason.INPUTS_CHANGED));
    }

    @Test
    @DisplayName("BR-13: what does not fit is unplaced with its reason and must be acknowledged before confirmation; "
            + "at confirmation it is released LEFT_BEHIND; a new calculation clears the acknowledgement")
    void unplacedMustBeAcknowledged() {
        for (int i = 0; i < 3; i++) {
            cube(shipmentA, null);
        }
        LoadPlan plan = planned(1);
        assertThat(plan.unplacedPackageIds()).hasSize(1);
        assertThat(plan.members()).filteredOn(m -> m.unplacedReason() != null).singleElement()
                .satisfies(m -> assertThat(m.unplacedReason()).isEqualTo(LoadPlanUnplacedReason.NO_VEHICLE_AVAILABLE));
        assertThat(code(() -> plan.confirm(DISPATCHER, NOW, cartons)))
                .isEqualTo(ErrorCode.LOAD_PLAN_UNPLACED_NOT_ACKNOWLEDGED);

        assertThat(plan.acknowledgeUnplaced(DISPATCHER, NOW, "next truck", cartons)).isTrue();
        assertThat(plan.acknowledgeUnplaced(DISPATCHER, NOW, "again", cartons)).as("idempotent").isFalse();
        plan.applyCalculation(solve(1), Map.of(TRUCK_TYPE, TRUCK), UUID::randomUUID, DISPATCHER, NOW, cartons);
        assertThat(plan.acknowledgedAt()).as("a new calculation asks again").isNull();

        plan.acknowledgeUnplaced(DISPATCHER, NOW, null, cartons);
        UUID left = plan.unplacedPackageIds().iterator().next();
        plan.confirm(DISPATCHER, NOW, cartons);
        assertThat(plan.activePackageIds()).doesNotContain(left).hasSize(2);
        assertThat(plan.members()).filteredOn(m -> m.packageId().equals(left)).singleElement()
                .satisfies(m -> assertThat(m.releaseReason()).isEqualTo(LoadPlanReleaseReason.LEFT_BEHIND));
    }

    @Test
    @DisplayName("BR-11: a manual edit is checked whole - a box hanging in the air (UNSUPPORTED), a turn the sizes do "
            + "not allow, overlapping boxes - refused with each package and rule and nothing changed; a valid move to "
            + "the tray is accepted, unplaced MANUAL, and clears the acknowledgement")
    void manualEditIsCheckedWhole() {
        UUID a = cube(shipmentA, null);
        UUID b = cube(shipmentA, null);
        LoadPlan plan = planned(1);
        long before = plan.activeVehicles().getFirst().usedVolumeMm3();

        Throwable broken = catchThrowable(() -> plan.editPlacements(List.of(new LoadPlan.VehicleEdit(1, List.of(
                new LoadPlan.Placed(a, 0, 0, 0, 1000, 1000, 1000, null),
                new LoadPlan.Placed(b, 0, 10, 500, 1000, 1000, 1000, null)))), List.of(), cartons, NOW));
        BusinessException refused = (BusinessException) broken;
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.PLACEMENT_RULE_VIOLATION);
        assertThat(refused.fieldErrors()).extracting(f -> f.field() + ":" + f.code())
                .contains(b + ":UNSUPPORTED", b + ":OVERLAP", b + ":OUT_OF_BOUNDS");
        assertThat(plan.activeVehicles().getFirst().usedVolumeMm3()).as("nothing changed").isEqualTo(before);

        assertThat(code(() -> plan.editPlacements(List.of(new LoadPlan.VehicleEdit(1, List.of(
                new LoadPlan.Placed(a, 0, 0, 0, 1000, 1000, 1000, null)))), List.of(), cartons, NOW)))
                .as("every package listed once").isEqualTo(ErrorCode.VALIDATION_FAILED);

        plan.acknowledgeUnplaced(DISPATCHER, NOW, null, cartons);
        plan.editPlacements(List.of(new LoadPlan.VehicleEdit(1, List.of(
                new LoadPlan.Placed(a, 0, 0, 1000, 1000, 1000, 1000, Orientation.LWH)))), List.of(b), cartons, NOW);
        assertThat(plan.placedPackageIds()).containsExactly(a);
        assertThat(plan.members()).filteredOn(m -> m.packageId().equals(b)).singleElement()
                .satisfies(m -> assertThat(m.unplacedReason()).isEqualTo(LoadPlanUnplacedReason.MANUAL));
        assertThat(plan.activeVehicles().getFirst().placements().getFirst().z()).isEqualTo(1000);
        assertThat(plan.activeVehicles().getFirst().usedWeightKg()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("LP-3: a kit's parts travel on one vehicle - moving one part to another vehicle, or to the tray alone, "
            + "is KIT_SPLIT")
    void aKitStaysTogether() {
        UUID p1 = cube(shipmentA, "kit-1");
        UUID p2 = cube(shipmentA, "kit-1");
        UUID loose = cube(shipmentB, null);
        UUID other = cube(shipmentB, null);
        LoadPlan plan = planned(2);
        assertThat(plan.activeVehicles()).hasSize(2);
        int kitSeq = plan.activeVehicles().stream().filter(v -> v.placements().stream()
                .anyMatch(p -> p.packageId().equals(p1))).findFirst().orElseThrow().seq();
        int otherSeq = kitSeq == 1 ? 2 : 1;

        Throwable split = catchThrowable(() -> plan.editPlacements(List.of(
                new LoadPlan.VehicleEdit(kitSeq, List.of(new LoadPlan.Placed(p1, 0, 0, 0, 1000, 1000, 1000, null),
                        new LoadPlan.Placed(loose, 0, 0, 1000, 1000, 1000, 1000, null))),
                new LoadPlan.VehicleEdit(otherSeq, List.of(new LoadPlan.Placed(p2, 0, 0, 0, 1000, 1000, 1000, null),
                        new LoadPlan.Placed(other, 0, 0, 1000, 1000, 1000, 1000, null)))), List.of(), cartons, NOW));
        assertThat(((BusinessException) split).errorCode()).isEqualTo(ErrorCode.PLACEMENT_RULE_VIOLATION);
        assertThat(((BusinessException) split).fieldErrors()).extracting(f -> f.field() + ":" + f.code())
                .containsExactlyInAnyOrder(p1 + ":KIT_SPLIT", p2 + ":KIT_SPLIT");

        Throwable halfOnTray = catchThrowable(() -> plan.editPlacements(List.of(
                new LoadPlan.VehicleEdit(kitSeq, List.of(new LoadPlan.Placed(p1, 0, 0, 0, 1000, 1000, 1000, null))),
                new LoadPlan.VehicleEdit(otherSeq, List.of(new LoadPlan.Placed(loose, 0, 0, 0, 1000, 1000, 1000, null),
                        new LoadPlan.Placed(other, 0, 0, 1000, 1000, 1000, 1000, null)))), List.of(p2), cartons, NOW));
        assertThat(((BusinessException) halfOnTray).fieldErrors()).extracting(f -> f.code()).containsOnly("KIT_SPLIT");
    }

    @Test
    @DisplayName("BR-14: reopening and cancelling need a reason and no vehicle loading; reopened → PLANNED, cancelled → "
            + "every parcel released PLAN_CANCELLED; cancelling twice changes nothing")
    void reopenAndCancel() {
        cube(shipmentA, null);
        LoadPlan plan = planned(1);
        plan.confirm(DISPATCHER, NOW, cartons);
        assertThat(code(() -> plan.reopen("truck changed", true, DISPATCHER, NOW)))
                .isEqualTo(ErrorCode.LOAD_PLAN_LOADING_STARTED);
        assertThat(code(() -> plan.cancel("x", true, DISPATCHER, NOW))).isEqualTo(ErrorCode.LOAD_PLAN_LOADING_STARTED);
        assertThat(code(() -> plan.reopen(" ", false, DISPATCHER, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);

        plan.reopen("truck changed", false, DISPATCHER, NOW);
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.PLANNED);
        assertThat(plan.activeVehicles()).hasSize(1);

        assertThat(plan.cancel("customer called off", false, DISPATCHER, NOW)).isTrue();
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.CANCELLED);
        assertThat(plan.activePackageIds()).isEmpty();
        assertThat(plan.members()).allSatisfy(m -> assertThat(m.releaseReason())
                .isEqualTo(LoadPlanReleaseReason.PLAN_CANCELLED));
        assertThat(plan.cancel("again", false, DISPATCHER, NOW)).isFalse();
    }

    @Test
    @DisplayName("09 edge: an order cancelled out of a confirmed plan - its parcels leave, the plan goes back to "
            + "PLANNED when nothing loads; while a vehicle loads it stays CONFIRMED")
    void anOrderCancelledOutOfAPlan() {
        UUID a = cube(shipmentA, null);
        UUID b = cube(shipmentB, null);
        LoadPlan plan = planned(1);
        plan.confirm(DISPATCHER, NOW, cartons);
        assertThat(plan.dropShipment(UUID.randomUUID(), false, NOW)).as("not in the plan").isFalse();

        assertThat(plan.dropShipment(shipmentB, false, NOW)).isTrue();
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.PLANNED);
        assertThat(plan.placedPackageIds()).containsExactly(a);
        assertThat(plan.members()).filteredOn(m -> m.packageId().equals(b)).singleElement()
                .satisfies(m -> assertThat(m.releaseReason()).isEqualTo(LoadPlanReleaseReason.ORDER_CANCELLED));

        plan.confirm(DISPATCHER, NOW, cartons);
        assertThat(plan.dropShipment(shipmentA, true, NOW)).as("loading: stays").isFalse();
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.CONFIRMED);
        assertThat(plan.activePackageIds()).isEmpty();
    }

    @Test
    @DisplayName("one vehicle recalculated: what fits stays, what does not goes to the tray with its reason")
    void oneVehicleRecalculated() {
        UUID a = cube(shipmentA, null);
        UUID b = cube(shipmentA, null);
        LoadPlan plan = planned(1);
        plan.editPlacements(List.of(new LoadPlan.VehicleEdit(1, List.of(
                new LoadPlan.Placed(a, 0, 0, 1000, 1000, 1000, 1000, null)))), List.of(b), cartons, NOW);
        List<CargoPackage> both = new ArrayList<>(cartons.values());
        LoadPlanResult again = new LoadPlanner().plan(new LoadPlanRequest(both, List.of(TRUCK),
                SelectionMode.SINGLE_TYPE, List.of(), KitDistribution.AUTO, false, null, 0L, Duration.ofSeconds(2)));
        plan.applyVehicleRecalculation(1, again, cartons, NOW);
        assertThat(plan.placedPackageIds()).containsExactlyInAnyOrder(a, b);
        assertThat(plan.unplacedPackageIds()).isEmpty();
        assertThat(code(() -> plan.applyVehicleRecalculation(9, again, cartons, NOW)))
                .isEqualTo(ErrorCode.LOAD_PLAN_VEHICLE_NOT_FOUND);
    }
}
