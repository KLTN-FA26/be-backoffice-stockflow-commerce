package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.carton;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.many;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.truck5t;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * LP2-9 (trial estimate): the payload is skipped only when the request says {@code ignorePayload}, as an option of
 * that one request. Every other request - the real estimate, the real plan - keeps BR-10's cap exactly as before.
 */
class LoadPlannerIgnorePayloadTest {

    private final LoadPlanner planner = new LoadPlanner(LoadPlanAssertions.TEST_RATE);

    private static LoadPlanRequest request(List<CargoPackage> cartons, List<VehicleSpec> types, boolean ignorePayload) {
        return new LoadPlanRequest(cartons, types, SelectionMode.SINGLE_TYPE, List.of(), KitDistribution.AUTO, false,
                null, 0L, Duration.ofSeconds(5), ignorePayload);
    }

    @Test
    @DisplayName("a carton heavier than the payload of every type is TOO_HEAVY, and loads once the payload is ignored")
    void heavyCartonLoadsOnlyInTrialMode() {
        List<CargoPackage> cartons = List.of(carton("big", 1000, 1000, 1000, "6000", true, "BIG"));
        List<VehicleSpec> types = List.of(truck5t(1, 0));

        LoadPlanResult real = planner.plan(request(cartons, types, false));
        assertThat(real.vehicles()).isEmpty();
        assertThat(real.unplaced()).singleElement().satisfies(u -> assertThat(u.reason())
                .isEqualTo(UnplacedReason.TOO_HEAVY));

        LoadPlanResult trial = planner.plan(request(cartons, types, true));
        assertThat(trial.unplaced()).isEmpty();
        assertThat(trial.placedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a vehicle may carry more than its payload in trial mode, and never does in a real plan")
    void totalAbovePayload() {
        // 30 cartons of 400 kg = 12 000 kg; they all fit the truck's volume, the 5 t payload is what stops them.
        List<CargoPackage> cartons = many("c", 30, 600, 500, 400, "400", true, "C");
        List<VehicleSpec> types = List.of(truck5t(1, 0));

        LoadPlanResult real = planner.plan(request(cartons, types, false));
        assertThat(real.vehicles()).hasSize(1);
        assertThat(real.vehicles().get(0).totalWeightKg()).isLessThanOrEqualTo(new BigDecimal("5000"));
        assertThat(real.unplaced()).isNotEmpty();

        LoadPlanResult trial = planner.plan(request(cartons, types, true));
        assertThat(trial.unplaced()).isEmpty();
        assertThat(trial.vehicles()).hasSize(1);
        assertThat(trial.vehicles().get(0).totalWeightKg()).isGreaterThan(new BigDecimal("5000"));
    }

    @Test
    @DisplayName("the default request is not a trial: the 9-argument constructor keeps the payload")
    void defaultKeepsThePayload() {
        LoadPlanRequest plain = new LoadPlanRequest(List.of(carton("a", 100, 100, 100, "1", true, "A")),
                List.of(truck5t(1, 0)), SelectionMode.SINGLE_TYPE, List.of(), KitDistribution.AUTO, false, null, 0L,
                Duration.ofSeconds(1));
        assertThat(plain.ignorePayload()).isFalse();
        assertThat(LoadPlanRequest.of(List.of(carton("a", 100, 100, 100, "1", true, "A")), List.of(truck5t(1, 0)),
                SelectionMode.SINGLE_TYPE).ignorePayload()).isFalse();
    }

    @Test
    @DisplayName("size still holds in trial mode: a carton larger than every vehicle is TOO_LARGE")
    void sizeIsStillChecked() {
        List<CargoPackage> cartons = List.of(carton("huge", 9000, 9000, 9000, "1", true, "HUGE"));
        LoadPlanResult trial = planner.plan(request(cartons, List.of(truck5t(1, 0)), true));
        assertThat(trial.vehicles()).isEmpty();
        assertThat(trial.unplaced()).singleElement().satisfies(u -> assertThat(u.reason())
                .isEqualTo(UnplacedReason.TOO_LARGE));
    }
}
