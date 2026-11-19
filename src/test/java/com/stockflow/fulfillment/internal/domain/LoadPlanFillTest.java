package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanRequest;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanner;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleKind;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The main / filler split of "Ưu tiên + hàng chèn" on a load plan (kltn-docs 09 §3, §4.2; owner answer RTM-LPGROUP,
 * 19/11: ticked by order line when the plan is made, main cargo by default): an input of the plan like its parcels,
 * types and mode.
 */
class LoadPlanFillTest {

    private static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");
    private static final UUID DISPATCHER = UUID.randomUUID();
    private static final UUID TRUCK_TYPE = UUID.randomUUID();
    private static final VehicleSpec TRUCK = new VehicleSpec(TRUCK_TYPE, "TRUCK-TEST", VehicleKind.TRUCK, 2000, 1000,
            1000, new BigDecimal("1000"), 3, 0);

    private final UUID shipmentA = UUID.randomUUID();
    private final UUID shipmentB = UUID.randomUUID();
    private final Map<UUID, CargoPackage> cartons = new LinkedHashMap<>();
    private final Map<UUID, UUID> shipmentOf = new LinkedHashMap<>();

    private UUID cube(UUID shipment) {
        UUID id = UUID.randomUUID();
        cartons.put(id, new CargoPackage(id.toString(), null, 1000, 1000, 1000, new BigDecimal("100"), true, "CUBE",
                null, shipment.toString(), false));
        shipmentOf.put(id, shipment);
        return id;
    }

    private static LoadPlan draft(SelectionMode mode) {
        return LoadPlan.draft(UUID.randomUUID(), "LP-20261119-0001", UUID.randomUUID(), UUID.randomUUID(), mode,
                null);
    }

    private static ErrorCode code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("PRIORITY_FILL: the filler parcels are an input - choosing or changing them sends a PLANNED plan back "
            + "to DRAFT; the same choice again, or none named, changes nothing; untouched parcels are main cargo")
    void fillerIsAnInput() {
        UUID main = cube(shipmentA);
        UUID filler = cube(shipmentB);
        LoadPlan plan = draft(SelectionMode.PRIORITY_FILL);
        plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, Set.of(filler), DISPATCHER, NOW);
        assertThat(plan.fillPackageIds()).containsExactly(filler);
        assertThat(plan.members()).filteredOn(m -> m.packageId().equals(main)).singleElement()
                .satisfies(m -> assertThat(m.fill()).as("main cargo by default").isFalse());

        plan.applyCalculation(new LoadPlanner().plan(LoadPlanRequest.of(List.copyOf(cartons.values()), List.of(TRUCK),
                SelectionMode.PRIORITY_FILL)), Map.of(TRUCK_TYPE, TRUCK), UUID::randomUUID, DISPATCHER, NOW, cartons);
        assertThat(plan.status()).isEqualTo(LoadPlanStatus.PLANNED);
        assertThat(plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, Set.of(filler), DISPATCHER, NOW)).isEmpty();
        assertThat(plan.status()).as("the same choice").isEqualTo(LoadPlanStatus.PLANNED);
        plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, null, DISPATCHER, NOW);
        assertThat(plan.status()).as("none named keeps the choice").isEqualTo(LoadPlanStatus.PLANNED);
        assertThat(plan.fillPackageIds()).containsExactly(filler);

        plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, Set.of(), DISPATCHER, NOW);
        assertThat(plan.status()).as("another choice").isEqualTo(LoadPlanStatus.DRAFT);
        assertThat(plan.fillPackageIds()).isEmpty();
    }

    @Test
    @DisplayName("09 §3: the split is the PRIORITY_FILL mode's input only - refused under another mode, dropped when "
            + "the plan leaves that mode")
    void fillerOnlyWithItsMode() {
        cube(shipmentA);
        UUID filler = cube(shipmentB);
        LoadPlan single = draft(SelectionMode.SINGLE_TYPE);
        Throwable thrown = catchThrowable(() -> single.setInputs(shipmentOf, List.of(TRUCK_TYPE), null,
                Set.of(filler), DISPATCHER, NOW));
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(((BusinessException) thrown).fieldErrors()).extracting(e -> e.code())
                .containsExactly("LOAD_PLAN_FILL_NOT_IN_MODE");

        LoadPlan plan = draft(SelectionMode.PRIORITY_FILL);
        plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, Set.of(filler), DISPATCHER, NOW);
        plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), SelectionMode.KEEP_LINES, null, DISPATCHER, NOW);
        assertThat(plan.mode()).isEqualTo(SelectionMode.KEEP_LINES);
        assertThat(plan.fillPackageIds()).as("another mode has no filler").isEmpty();
    }

    @Test
    @DisplayName("filler cargo is cargo of the plan; a filler parcel whose shipment leaves the plan is no longer filler")
    void fillerOfThePlan() {
        cube(shipmentA);
        UUID filler = cube(shipmentB);
        LoadPlan plan = draft(SelectionMode.PRIORITY_FILL);
        assertThat(code(() -> plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, Set.of(UUID.randomUUID()),
                DISPATCHER, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);

        plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, Set.of(filler), DISPATCHER, NOW);
        Map<UUID, UUID> onlyA = new LinkedHashMap<>(shipmentOf);
        onlyA.remove(filler);
        plan.setInputs(onlyA, List.of(TRUCK_TYPE), null, null, DISPATCHER, NOW);
        assertThat(plan.fillPackageIds()).isEmpty();
    }
}
