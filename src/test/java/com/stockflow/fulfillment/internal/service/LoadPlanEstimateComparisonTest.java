package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleKind;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec;
import com.stockflow.fulfillment.internal.entity.LoadEstimateJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.repository.LoadEstimateJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageLineJpaRepository;
import com.stockflow.order.api.OrderSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plan against the current estimates (design v2 §5 {@code vsEstimate}): differences flagged in a single-order plan;
 * in a multi-order plan no vehicle flag (each estimate planned its order alone) and only the estimated orders' parcels
 * compared (review of #161: no false flags).
 */
class LoadPlanEstimateComparisonTest {

    private final LoadEstimates estimates = mock(LoadEstimates.class);
    private final LoadEstimateJpaRepository rows = mock(LoadEstimateJpaRepository.class);
    private final PackageLineJpaRepository lines = mock(PackageLineJpaRepository.class);
    private final LoadPlanEstimateComparison comparison = new LoadPlanEstimateComparison(estimates, rows, lines);
    private static final VehicleSpec TRUCK = new VehicleSpec(UUID.randomUUID(), "TRUCK-5T", VehicleKind.TRUCK, 5200,
            2050, 2050, new BigDecimal("5000"), 10, 0);
    private static final LoadPlan.Vehicle PLAN_TRUCK = new LoadPlan.Vehicle(UUID.randomUUID(), 1, true, TRUCK,
            List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);

    @BeforeEach
    void noEstimateByDefault() {
        when(rows.findBySourceTypeAndSourceIdAndStatus(anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(lines.findByPackageIdIn(any())).thenReturn(List.of());
    }

    private static OrderSummary order(UUID id) {
        OrderSummary o = mock(OrderSummary.class);
        when(o.orderId()).thenReturn(id);
        return o;
    }

    private static LoadPlanParcels.Parcel parcel(OrderSummary order, int lengthMm, String weight) {
        UUID id = UUID.randomUUID();
        PackageJpaEntity row = new PackageJpaEntity(id, UUID.randomUUID(), 1, "PKG-T-1");
        return new LoadPlanParcels.Parcel(row, null, order, new CargoPackage(id.toString(), null, lengthMm, 500, 500,
                new BigDecimal(weight), true, "SKU-A", null, null, false), "SKU-A");
    }

    /** A current estimate of the order: {@code boxes} boxes of 500 mm cubed, 10 kg, on {@code trucks} TRUCK-5T. */
    private UUID estimate(UUID orderId, int boxes, int trucks, boolean incomplete) {
        when(rows.findBySourceTypeAndSourceIdAndStatus("ORDER", orderId, LoadEstimateJpaEntity.CURRENT))
                .thenReturn(Optional.of(mock(LoadEstimateJpaEntity.class)));
        List<Layout> vehicles = new ArrayList<>();
        for (int i = 0; i < trucks; i++) {
            vehicles.add(new Layout(i + 1, "TRUCK-5T", TRUCK.typeId(), new Layout.Dims(5200, 2050, 2050),
                    new BigDecimal("5000"), List.of(), List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        LoadEstimates.EstimateLine line = new LoadEstimates.EstimateLine(UUID.randomUUID(), "SKU-A", UUID.randomUUID(),
                boxes, "ITEM_BOX", "PCS", 1, boxes, null, new Layout.Dims(500, 500, 500), new BigDecimal("10"), true, 1);
        UUID estimateId = UUID.randomUUID();
        when(estimates.current(LoadEstimates.SourceType.ORDER, orderId)).thenReturn(new LoadEstimates.LoadEstimateView(
                estimateId, LoadEstimates.SourceType.ORDER, orderId, UUID.randomUUID(), "CURRENT",
                SelectionMode.SINGLE_TYPE, List.of(), List.of(line), List.of(), incomplete, List.of(), vehicles,
                List.of(), null, Instant.now(), "test"));
        return estimateId;
    }

    @Test
    @DisplayName("single-order plan: bigger and more parcels than estimated on more vehicles - MORE_VEHICLES, "
            + "MORE_PARCELS, VOLUME_ABOVE, WEIGHT_ABOVE; an incomplete estimate (LP2-3) is ESTIMATE_INCOMPLETE")
    void aSingleOrderPlanComparesEverything() {
        OrderSummary order = order(UUID.randomUUID());
        UUID estimateId = estimate(order.orderId(), 2, 1, true);
        List<LoadPlanParcels.Parcel> parcels = List.of(parcel(order, 1000, "25"), parcel(order, 1000, "25"),
                parcel(order, 1000, "25"));
        LoadPlans.EstimateComparison c = comparison.compare(List.of(order), parcels, List.of(PLAN_TRUCK, PLAN_TRUCK));

        assertThat(c.estimateIds()).containsExactly(estimateId);
        assertThat(c.notEstimated()).isEmpty();
        assertThat(c.vehiclesCompared()).isTrue();
        assertThat(c.vehiclesByType()).containsExactly(new LoadPlans.VehicleCount("TRUCK-5T", 1, 2));
        assertThat(c.volumeMm3().estimated()).isEqualByComparingTo("250000000");
        assertThat(c.volumeMm3().actual()).isEqualByComparingTo("750000000");
        assertThat(c.weightKg().deltaPct()).isEqualByComparingTo("275.00");
        assertThat(c.flags()).containsExactlyInAnyOrder("ESTIMATE_INCOMPLETE", "MORE_VEHICLES", "MORE_PARCELS",
                "VOLUME_ABOVE", "WEIGHT_ABOVE");
    }

    @Test
    @DisplayName("multi-order plan, both estimated, each estimate one truck of its own, the plan one shared truck: no "
            + "vehicle flag (not FEWER_VEHICLES), vehicles reported per order for information")
    void sharedTrucksAreNotFlagged() {
        OrderSummary a = order(UUID.randomUUID());
        OrderSummary b = order(UUID.randomUUID());
        estimate(a.orderId(), 1, 1, false);
        estimate(b.orderId(), 1, 1, false);
        LoadPlans.EstimateComparison c = comparison.compare(List.of(a, b),
                List.of(parcel(a, 500, "10"), parcel(b, 500, "10")), List.of(PLAN_TRUCK));

        assertThat(c.vehiclesCompared()).isFalse();
        assertThat(c.vehiclesByType()).isEmpty();
        assertThat(c.orders()).extracting(LoadPlans.OrderEstimate::orderId).containsExactly(a.orderId(), b.orderId());
        assertThat(c.orders()).allSatisfy(o -> assertThat(o.vehiclesByType())
                .containsExactly(new LoadPlans.VehicleCount("TRUCK-5T", 1, 0)));
        assertThat(c.flags()).isEmpty();
    }

    @Test
    @DisplayName("multi-order plan, one order estimated: only its parcels are compared (the other order's big parcels "
            + "raise no MORE_PARCELS / VOLUME_ABOVE / WEIGHT_ABOVE); the other is listed in notEstimated")
    void onlyEstimatedOrdersAreCompared() {
        OrderSummary estimated = order(UUID.randomUUID());
        OrderSummary other = order(UUID.randomUUID());
        estimate(estimated.orderId(), 2, 1, false);
        List<LoadPlanParcels.Parcel> parcels = new ArrayList<>(List.of(parcel(estimated, 500, "10"),
                parcel(estimated, 500, "10")));
        for (int i = 0; i < 5; i++) {
            parcels.add(parcel(other, 2000, "80"));
        }
        LoadPlans.EstimateComparison c = comparison.compare(List.of(estimated, other), parcels,
                List.of(PLAN_TRUCK, PLAN_TRUCK));

        assertThat(c.notEstimated()).containsExactly(other.orderId());
        assertThat(c.volumeMm3().actual()).isEqualByComparingTo("250000000");
        assertThat(c.weightKg().actual()).isEqualByComparingTo("20");
        assertThat(c.flags()).containsExactly("NO_ESTIMATE");
    }

    @Test
    @DisplayName("no order has an estimate: only NO_ESTIMATE, nothing compared")
    void nothingToCompare() {
        OrderSummary order = order(UUID.randomUUID());
        LoadPlans.EstimateComparison c = comparison.compare(List.of(order), List.of(parcel(order, 500, "1")),
                List.of());
        assertThat(c.flags()).containsExactly("NO_ESTIMATE");
        assertThat(c.volumeMm3()).isNull();
        assertThat(c.notEstimated()).containsExactly(order.orderId());
    }
}
