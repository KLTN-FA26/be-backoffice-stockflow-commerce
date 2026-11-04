package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentLocks;
import com.stockflow.order.api.OrderSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * kltn-docs 09 §4.2 step 3, edge "kiện không xếp được ... để lại khu DISPATCH cho chuyến sau" (SCRUM-392): parcels a
 * confirmed plan left behind become a new shipment for a later trip; every label whose n/N changed is reprinted.
 */
class LeftBehindParcelsTest {

    private final ShipmentJpaRepository shipments = mock(ShipmentJpaRepository.class);
    private final ShipmentLocks locks = mock(ShipmentLocks.class);
    private final PackageJpaRepository packages = mock(PackageJpaRepository.class);
    private final ShipmentTimeline timeline = mock(ShipmentTimeline.class);
    private final LoadPlanParcels parcels = mock(LoadPlanParcels.class);
    private final LeftBehindParcels leftBehind = new LeftBehindParcels(shipments, locks, packages, timeline, parcels);
    private final UUID by = UUID.randomUUID();

    private FulfillmentFixtures.LeftBehindPlan scenario;
    private ShipmentJpaEntity row;
    private List<PackageJpaEntity> cartons;

    @BeforeEach
    void aConfirmedPlanThatLeftOneParcelBehind() {
        scenario = FulfillmentFixtures.confirmedPlanLeavingOneBehind();
        Shipment shipment = Shipment.restore(new Shipment.Snapshot(scenario.shipmentId(), "SO-0042-S1", UUID.randomUUID(),
                null, FulfillmentFixtures.WAREHOUSE, UUID.randomUUID(), DeliveryMode.FULL_TRUCKLOAD, 3,
                java.math.BigDecimal.ZERO, false, null, ShipmentStatus.READY_TO_DISPATCH, NOW, null, null, null, null,
                null, null, null, null, null));
        row = ShipmentJpaEntity.of(shipment);
        when(locks.lock(Set.of(scenario.shipmentId()))).thenReturn(List.of(row));
        // The parcels the plan knows, in package number order: its ids carry the plan's placed / left-behind split.
        List<UUID> ids = new java.util.ArrayList<>(scenario.placed());
        ids.add(scenario.leftBehind());
        cartons = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            PackageJpaEntity parcel = new PackageJpaEntity(ids.get(i), UUID.randomUUID(), i + 1, "PKG-" + (i + 1));
            parcel.apply(scenario.shipmentId(), com.stockflow.fulfillment.internal.domain.PackageType.CARTON,
                    PackageStatus.SEALED, new java.math.BigDecimal("100"), new java.math.BigDecimal("100"),
                    new java.math.BigDecimal("100"), new java.math.BigDecimal("100"), null, true, false, false, false,
                    NOW, null, null, null);
            cartons.add(parcel);
        }
        when(packages.findByShipmentIdOrderByPackageNoAsc(scenario.shipmentId())).thenAnswer(call -> List.copyOf(cartons));
        when(shipments.countByOrderId(row.getOrderId())).thenReturn(1L);
        when(parcels.ordersOf(any())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("the left-behind parcel moves to a new shipment waiting for dispatch, the rest stays with the old one")
    void splitsTheLeftBehindParcelOff() {
        leftBehind.splitOff(scenario.plan(), by, NOW);

        assertThat(row.getPackageCount()).as("the shipment keeps what was placed").isEqualTo(2);
        ArgumentCaptor<ShipmentJpaEntity> saved = ArgumentCaptor.forClass(ShipmentJpaEntity.class);
        verify(shipments, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        ShipmentJpaEntity rest = saved.getAllValues().get(1);
        assertThat(rest.getPackageCount()).isEqualTo(1);
        assertThat(rest.getStatus()).isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
        assertThat(rest.isPartial()).isTrue();
        assertThat(rest.getPartialNote()).contains(scenario.plan().code());
        assertThat(rest.getOrderId()).isEqualTo(row.getOrderId());
        PackageJpaEntity moved = cartons.stream().filter(p -> p.getId().equals(scenario.leftBehind())).findFirst()
                .orElseThrow();
        assertThat(moved.getShipmentId()).isEqualTo(rest.getId());
    }

    @Test
    @DisplayName("the new shipment's code continues the order's numbering: <order number>-S<count + 1>")
    void newShipmentCodeContinuesTheNumbering() {
        OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), null);
        when(parcels.ordersOf(any())).thenReturn(Map.of(row.getOrderId(), order));
        when(shipments.countByOrderId(row.getOrderId())).thenReturn(2L);

        leftBehind.splitOff(scenario.plan(), by, NOW);

        ArgumentCaptor<ShipmentJpaEntity> saved = ArgumentCaptor.forClass(ShipmentJpaEntity.class);
        verify(shipments, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(1).getCode()).isEqualTo(order.orderNumber() + "-S3");
    }

    @Test
    @DisplayName("without the order, the number is the old code without its -S suffix")
    void orderNumberFallsBackToTheCode() {
        leftBehind.splitOff(scenario.plan(), by, NOW);

        ArgumentCaptor<ShipmentJpaEntity> saved = ArgumentCaptor.forClass(ShipmentJpaEntity.class);
        verify(shipments, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(1).getCode()).isEqualTo("SO-0042-S2");
    }

    @Test
    @DisplayName("every label of the shipment is marked outdated: the moved parcel's and those whose n/N changed")
    void labelsAreReprinted() {
        leftBehind.splitOff(scenario.plan(), by, NOW);

        assertThat(cartons).allSatisfy(p -> assertThat(p.getLabelOutdatedAt()).isEqualTo(NOW));
        verify(packages, org.mockito.Mockito.times(3)).save(any(PackageJpaEntity.class));
    }

    @Test
    @DisplayName("the new shipment is announced from nothing to READY_TO_DISPATCH")
    void newShipmentIsRecorded() {
        leftBehind.splitOff(scenario.plan(), by, NOW);

        ArgumentCaptor<Shipment> rest = ArgumentCaptor.forClass(Shipment.class);
        verify(timeline).record(rest.capture(), eq(null), eq(null), eq("SO-0042"), eq(null), eq(null));
        assertThat(rest.getValue().packageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a parcel already moved (no longer SEALED here) is not moved again: running it twice changes nothing more")
    void splitIsIdempotent() {
        leftBehind.splitOff(scenario.plan(), by, NOW);
        // After the first run the moved parcel belongs to another shipment: this one lists two parcels only.
        cartons.removeIf(p -> p.getId().equals(scenario.leftBehind()));
        org.mockito.Mockito.clearInvocations(shipments, packages, timeline);

        leftBehind.splitOff(scenario.plan(), by, NOW);

        verify(shipments, never()).saveAndFlush(any());
        verifyNoInteractions(timeline);
    }

    @Test
    @DisplayName("a shipment whose every sealed parcel was left behind is not split: nothing would stay")
    void nothingStaysNothingMoves() {
        cartons.removeIf(p -> !p.getId().equals(scenario.leftBehind()));

        leftBehind.splitOff(scenario.plan(), by, NOW);

        verify(shipments, never()).saveAndFlush(any());
        verifyNoInteractions(timeline);
    }

    @Test
    @DisplayName("a plan that left nothing behind touches nothing, not even a lock")
    void nothingLeftBehind() {
        LoadPlan plan = org.mockito.Mockito.mock(LoadPlan.class);
        when(plan.placedPackageIds()).thenReturn(Set.of());
        when(plan.members()).thenReturn(List.of());

        leftBehind.splitOff(plan, by, NOW);

        verifyNoInteractions(locks, shipments, packages, timeline);
    }

    @Test
    @DisplayName("the left-behind parcels of a shipment with nothing placed leave with no one: it is not split")
    void shipmentNotLeavingIsNotSplit() {
        // The plan places nothing of this shipment: all its parcels were left behind, so the shipment does not leave.
        UUID other = UUID.randomUUID();
        LoadPlan plan = org.mockito.Mockito.mock(LoadPlan.class);
        when(plan.placedPackageIds()).thenReturn(Set.of());
        when(plan.members()).thenReturn(List.of(new LoadPlan.Member(other, scenario.shipmentId(), false, null,
                NOW, by, NOW, com.stockflow.fulfillment.internal.domain.LoadPlanReleaseReason.LEFT_BEHIND)));

        leftBehind.splitOff(plan, by, NOW);

        verifyNoInteractions(locks, shipments);
    }

}
