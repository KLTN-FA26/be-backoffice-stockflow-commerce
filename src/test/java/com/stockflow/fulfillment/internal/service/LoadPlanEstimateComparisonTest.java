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
 * The plan against the current estimates (design v2 §5 {@code vsEstimate}): more and bigger parcels than estimated,
 * an incomplete estimate, an order with no estimate.
 */
class LoadPlanEstimateComparisonTest {

    private final LoadEstimates estimates = mock(LoadEstimates.class);
    private final LoadEstimateJpaRepository rows = mock(LoadEstimateJpaRepository.class);
    private final PackageLineJpaRepository lines = mock(PackageLineJpaRepository.class);
    private final LoadPlanEstimateComparison comparison = new LoadPlanEstimateComparison(estimates, rows, lines);
    private static final VehicleSpec TRUCK = new VehicleSpec(UUID.randomUUID(), "TRUCK-5T", VehicleKind.TRUCK, 5200,
            2050, 2050, new BigDecimal("5000"), 10, 0);

    private static OrderSummary order(UUID id) {
        OrderSummary o = mock(OrderSummary.class);
        when(o.orderId()).thenReturn(id);
        return o;
    }

    private static LoadPlanParcels.Parcel parcel(int lengthMm, String weight) {
        UUID id = UUID.randomUUID();
        PackageJpaEntity row = new PackageJpaEntity(id, UUID.randomUUID(), 1, "PKG-T-1");
        return new LoadPlanParcels.Parcel(row, null, null, new CargoPackage(id.toString(), null, lengthMm, 500, 500,
                new BigDecimal(weight), true, "SKU-A", null, null, false), "SKU-A");
    }

    @Test
    @DisplayName("bigger and more parcels than estimated on more vehicles: MORE_VEHICLES, MORE_PARCELS, VOLUME_ABOVE, "
            + "WEIGHT_ABOVE; an incomplete estimate (LP2-3) is ESTIMATE_INCOMPLETE; an order without one NO_ESTIMATE")
    void flagsTheDifferences() {
        UUID estimated = UUID.randomUUID();
        UUID notEstimated = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        when(rows.findBySourceTypeAndSourceIdAndStatus(anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(rows.findBySourceTypeAndSourceIdAndStatus("ORDER", estimated, LoadEstimateJpaEntity.CURRENT))
                .thenReturn(Optional.of(mock(LoadEstimateJpaEntity.class)));
        Layout vehicle = new Layout(1, "TRUCK-5T", TRUCK.typeId(), new Layout.Dims(5200, 2050, 2050),
                new BigDecimal("5000"), List.of(), List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        LoadEstimates.EstimateLine line = new LoadEstimates.EstimateLine(lineId, "SKU-A", UUID.randomUUID(), 2,
                "ITEM_BOX", "PCS", 1, 2, null, new Layout.Dims(500, 500, 500), new BigDecimal("10"), true, 1);
        UUID estimateId = UUID.randomUUID();
        when(estimates.current(LoadEstimates.SourceType.ORDER, estimated)).thenReturn(new LoadEstimates.LoadEstimateView(
                estimateId, LoadEstimates.SourceType.ORDER, estimated, UUID.randomUUID(), "CURRENT",
                SelectionMode.SINGLE_TYPE, List.of(), List.of(line), List.of(), true, List.of(), List.of(vehicle),
                List.of(), null, Instant.now(), "test"));
        when(lines.findByPackageIdIn(any())).thenReturn(List.of());

        List<LoadPlanParcels.Parcel> parcels = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            parcels.add(parcel(1000, "25"));
        }
        LoadPlan.Vehicle v = new LoadPlan.Vehicle(UUID.randomUUID(), 1, true, TRUCK, List.of(), 0, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false);
        LoadPlans.EstimateComparison c = comparison.compare(List.of(order(estimated), order(notEstimated)), parcels,
                List.of(v, v));

        assertThat(c.estimateIds()).containsExactly(estimateId);
        assertThat(c.ordersWithoutEstimate()).containsExactly(notEstimated);
        assertThat(c.vehiclesByType()).containsExactly(new LoadPlans.VehicleCount("TRUCK-5T", 1, 2));
        assertThat(c.cartonsByLine()).containsExactly(new LoadPlans.LineCount(lineId, "SKU-A", 2, 0));
        assertThat(c.volumeMm3().estimated()).isEqualByComparingTo("250000000");
        assertThat(c.volumeMm3().actual()).isEqualByComparingTo("750000000");
        assertThat(c.weightKg().deltaPct()).isEqualByComparingTo("275.00");
        assertThat(c.flags()).containsExactlyInAnyOrder("ESTIMATE_INCOMPLETE", "NO_ESTIMATE", "MORE_VEHICLES",
                "MORE_PARCELS", "VOLUME_ABOVE", "WEIGHT_ABOVE");
    }

    @Test
    @DisplayName("no order has an estimate: only NO_ESTIMATE, nothing compared")
    void nothingToCompare() {
        when(rows.findBySourceTypeAndSourceIdAndStatus(anyString(), any(), anyString())).thenReturn(Optional.empty());
        UUID orderId = UUID.randomUUID();
        LoadPlans.EstimateComparison c = comparison.compare(List.of(order(orderId)), List.of(parcel(500, "1")),
                List.of());
        assertThat(c.flags()).containsExactly("NO_ESTIMATE");
        assertThat(c.volumeMm3()).isNull();
        assertThat(c.ordersWithoutEstimate()).containsExactly(orderId);
    }
}
