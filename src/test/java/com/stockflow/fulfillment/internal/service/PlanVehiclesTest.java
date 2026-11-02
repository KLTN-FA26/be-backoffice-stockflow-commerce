package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.LoadPlanStatus;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanVehicleJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.LoadPlanJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanStore;
import com.stockflow.fulfillment.internal.repository.LoadPlanVehicleJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The plan behind a full-truckload load (kltn-docs 09 §4.2 step 5, BR-14, BR-15): only a confirmed plan's vehicles are
 * loaded, the plan is DISPATCHED when every vehicle that still carries something has left, and an order cancelled
 * while its goods are on a vehicle calls the vehicle off when nothing is left to carry.
 */
class PlanVehiclesTest {

    private final LoadPlanJpaRepository plans = mock(LoadPlanJpaRepository.class);
    private final LoadPlanStore planStore = mock(LoadPlanStore.class);
    private final LoadPlanVehicleJpaRepository vehicles = mock(LoadPlanVehicleJpaRepository.class);
    private final LoadPlanPackageJpaRepository planMembers = mock(LoadPlanPackageJpaRepository.class);
    private final DispatchLoadJpaRepository loads = mock(DispatchLoadJpaRepository.class);
    private final DispatchLoadShipmentJpaRepository loadMembers = mock(DispatchLoadShipmentJpaRepository.class);
    private final DispatchLoadPackageJpaRepository loaded = mock(DispatchLoadPackageJpaRepository.class);
    private final DispatchLoadStore loadStore = mock(DispatchLoadStore.class);
    private final PlanVehicles planVehicles = new PlanVehicles(plans, planStore, vehicles, planMembers, loads,
            loadMembers, loaded, loadStore);

    private FulfillmentFixtures.LeftBehindPlan scenario;
    private LoadPlan plan;
    private LoadPlan.Vehicle vehicle;
    private LoadPlanJpaEntity header;

    @BeforeEach
    void aConfirmedPlanWithOneVehicle() {
        scenario = FulfillmentFixtures.confirmedPlanLeavingOneBehind();
        plan = scenario.plan();
        vehicle = plan.activeVehicles().getFirst();
        header = new LoadPlanJpaEntity(plan.snapshot());
        when(vehicles.findById(vehicle.id())).thenReturn(Optional.of(
                new LoadPlanVehicleJpaEntity(vehicle.id(), plan.id(), vehicle.seq(), vehicle.spec())));
        when(plans.findById(plan.id())).thenReturn(Optional.of(header));
        when(plans.lockById(plan.id())).thenReturn(Optional.of(header));
        when(planStore.load(header)).thenReturn(plan);
    }

    private DispatchLoadJpaEntity load(LoadStatus status, UUID vehicleId) {
        DispatchLoadJpaEntity load = mock(DispatchLoadJpaEntity.class);
        when(load.getStatus()).thenReturn(status);
        when(load.getLoadPlanVehicleId()).thenReturn(vehicleId);
        when(load.getCode()).thenReturn("LD-" + status);
        when(load.getId()).thenReturn(UUID.randomUUID());
        return load;
    }

    @Nested
    @DisplayName("finding the plan of a vehicle")
    class Finding {

        @Test
        @DisplayName("the vehicle's plan is read with its row locked, and names the vehicle")
        void lockReturnsThePlanAndTheVehicle() {
            PlanVehicles.PlanOfLoad ctx = planVehicles.lock(vehicle.id());

            assertThat(ctx.plan()).isSameAs(plan);
            assertThat(ctx.vehicle().id()).isEqualTo(vehicle.id());
            assertThat(ctx.placedHere()).isEqualTo(scenario.placed());
        }

        @Test
        @DisplayName("a vehicle that does not exist is LOAD_PLAN_VEHICLE_NOT_FOUND; one whose plan is gone is LOAD_PLAN_NOT_FOUND")
        void unknowns() {
            UUID unknown = UUID.randomUUID();
            when(vehicles.findById(unknown)).thenReturn(Optional.empty());
            assertThat(refusal(() -> planVehicles.lock(unknown)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_PLAN_VEHICLE_NOT_FOUND);

            when(plans.lockById(plan.id())).thenReturn(Optional.empty());
            assertThat(refusal(() -> planVehicles.lock(vehicle.id())).errorCode())
                    .isEqualTo(ErrorCode.LOAD_PLAN_NOT_FOUND);
        }

        @Test
        @DisplayName("the plan of a full-truckload load is read from the load's vehicle")
        void readFromALoad() {
            DispatchLoadJpaEntity load = load(LoadStatus.PLANNED, vehicle.id());

            assertThat(planVehicles.read(load).plan()).isSameAs(plan);
        }

        @Test
        @DisplayName("a vehicle the plan no longer has gives a context without a vehicle: nothing placed here")
        void vehicleRetiredFromThePlan() {
            UUID gone = UUID.randomUUID();
            when(vehicles.findById(gone)).thenReturn(Optional.of(
                    new LoadPlanVehicleJpaEntity(gone, plan.id(), 9, vehicle.spec())));

            PlanVehicles.PlanOfLoad ctx = planVehicles.lock(gone);

            assertThat(ctx.vehicle()).isNull();
            assertThat(ctx.placedHere()).isEmpty();
        }
    }

    @Test
    @DisplayName("LP2-1: a plan that is not CONFIRMED does not book, load or hand over a vehicle")
    void onlyAConfirmedPlansVehiclesAreLoaded() {
        PlanVehicles.requireConfirmed(planVehicles.lock(vehicle.id()));

        plan.reopen("truck came with another size", false, UUID.randomUUID(), NOW);

        assertThat(plan.status()).isEqualTo(LoadPlanStatus.PLANNED);
        assertThat(refusal(() -> PlanVehicles.requireConfirmed(planVehicles.lock(vehicle.id()))).errorCode())
                .isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);
    }

    @Test
    @DisplayName("the live load of a vehicle is the one not cancelled")
    void liveLoadsSkipCancelledOnes() {
        DispatchLoadJpaEntity cancelled = load(LoadStatus.CANCELLED, vehicle.id());
        DispatchLoadJpaEntity live = load(LoadStatus.LOADING, vehicle.id());
        when(loads.findByLoadPlanVehicleIdIn(List.of(vehicle.id()))).thenReturn(List.of(cancelled, live));

        assertThat(planVehicles.liveLoads(plan)).containsOnlyKeys(vehicle.id())
                .containsEntry(vehicle.id(), live);
    }

    @Nested
    @DisplayName("where the plan placed a scanned parcel (09 edge: a parcel of another vehicle is refused)")
    class Placement {

        private UUID placedParcel() {
            return vehicle.placements().getFirst().packageId();
        }

        @Test
        @DisplayName("a parcel placed on this vehicle is here")
        void here() {
            DispatchLoad.Placement placement = planVehicles.placement(planVehicles.lock(vehicle.id()), placedParcel());

            assertThat(placement.here()).isTrue();
            assertThat(placement.planCode()).isEqualTo(plan.code());
            assertThat(placement.vehicleSeq()).isEqualTo(vehicle.seq());
            assertThat(placement.loadCode()).isNull();
        }

        @Test
        @DisplayName("a parcel held by no vehicle of the plan reports the other plan that holds it")
        void heldByAnotherPlan() {
            UUID parcel = UUID.randomUUID();
            UUID otherPlanId = UUID.randomUUID();
            LoadPlanPackageJpaEntity member = mock(LoadPlanPackageJpaEntity.class);
            when(member.getLoadPlanId()).thenReturn(otherPlanId);
            when(planMembers.findByPackageIdInAndActiveTrue(List.of(parcel))).thenReturn(List.of(member));
            LoadPlanJpaEntity other = mock(LoadPlanJpaEntity.class);
            when(other.getCode()).thenReturn("LP-OTHER");
            when(plans.findById(otherPlanId)).thenReturn(Optional.of(other));

            DispatchLoad.Placement placement = planVehicles.placement(planVehicles.lock(vehicle.id()), parcel);

            assertThat(placement.here()).isFalse();
            assertThat(placement.planCode()).isEqualTo("LP-OTHER");
            assertThat(placement.vehicleSeq()).isNull();
        }

        @Test
        @DisplayName("a parcel in no plan has no placement at all")
        void inNoPlan() {
            DispatchLoad.Placement placement = planVehicles.placement(planVehicles.lock(vehicle.id()), UUID.randomUUID());

            assertThat(placement).isEqualTo(new DispatchLoad.Placement(false, null, null, null));
        }
    }

    @Nested
    @DisplayName("BR-15: the plan is DISPATCHED when every vehicle that carries something has left")
    class Completing {

        @Test
        @DisplayName("when its only live vehicle is DISPATCHED the plan turns DISPATCHED and is saved")
        void everyVehicleLeft() {
            DispatchLoadJpaEntity theLoad = load(LoadStatus.DISPATCHED, vehicle.id());
            when(loads.findByLoadPlanVehicleIdIn(List.of(vehicle.id()))).thenReturn(List.of(theLoad));

            assertThat(planVehicles.completeIfDispatched(header, plan, NOW)).isTrue();

            assertThat(plan.status()).isEqualTo(LoadPlanStatus.DISPATCHED);
            verify(planStore).save(plan, header, NOW);
        }

        @Test
        @DisplayName("a vehicle still loading holds the plan back")
        void aVehicleStillLoads() {
            DispatchLoadJpaEntity theLoad = load(LoadStatus.LOADING, vehicle.id());
            when(loads.findByLoadPlanVehicleIdIn(List.of(vehicle.id()))).thenReturn(List.of(theLoad));

            assertThat(planVehicles.completeIfDispatched(header, plan, NOW)).isFalse();

            assertThat(plan.status()).isEqualTo(LoadPlanStatus.CONFIRMED);
            verify(planStore, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("a plan whose vehicles all have cancelled loads is not dispatched: at least one must have left")
        void noVehicleLeft() {
            DispatchLoadJpaEntity theLoad = load(LoadStatus.CANCELLED, vehicle.id());
            when(loads.findByLoadPlanVehicleIdIn(List.of(vehicle.id()))).thenReturn(List.of(theLoad));

            assertThat(planVehicles.completeIfDispatched(header, plan, NOW)).isFalse();
        }

        @Test
        @DisplayName("a plan that is not CONFIRMED is left as it is")
        void onlyAConfirmedPlan() {
            plan.reopen("again", false, UUID.randomUUID(), NOW);

            assertThat(planVehicles.completeIfDispatched(header, plan, NOW)).isFalse();
            verify(loads, never()).findByLoadPlanVehicleIdIn(any());
        }

        @Test
        @DisplayName("the plans of the given vehicles are locked and completed, each once")
        void completePlansOfVehicles() {
            when(vehicles.findAllById(Set.of(vehicle.id()))).thenReturn(List.of(
                    new LoadPlanVehicleJpaEntity(vehicle.id(), plan.id(), vehicle.seq(), vehicle.spec())));
            DispatchLoadJpaEntity theLoad = load(LoadStatus.DISPATCHED, vehicle.id());
            when(loads.findByLoadPlanVehicleIdIn(List.of(vehicle.id()))).thenReturn(List.of(theLoad));

            planVehicles.completePlansOf(Set.of(vehicle.id()), NOW);

            assertThat(plan.status()).isEqualTo(LoadPlanStatus.DISPATCHED);
        }

        @Test
        @DisplayName("no vehicles, no work")
        void nothingToComplete() {
            planVehicles.completePlansOf(Set.of(), NOW);

            verify(plans, never()).lockById(any());
        }
    }

    @Nested
    @DisplayName("an order cancelled while its goods are on a vehicle (09 edge)")
    class Withdrawing {

        private final UUID shipmentId = UUID.randomUUID();

        private DispatchLoad openLoadWith(UUID shipment) {
            DispatchLoad load = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-0001", UUID.randomUUID(), WAREHOUSE,
                    vehicle.id());
            load.addShipment(new DispatchLoad.ShipmentFacts(shipment, "SO-1-S1", WAREHOUSE, DeliveryMode.FULL_TRUCKLOAD,
                    ShipmentStatus.READY_TO_DISPATCH, null, null), BigDecimal.ZERO, UUID.randomUUID(), NOW);
            return load;
        }

        private DispatchLoadJpaEntity stored(DispatchLoad load) {
            DispatchLoadJpaEntity row = new DispatchLoadJpaEntity(load.snapshot());
            when(loadMembers.findByShipmentIdInAndActiveTrue(List.of(shipmentId))).thenReturn(List.of(
                    new DispatchLoadShipmentJpaEntity(UUID.randomUUID(), load.id(), shipmentId,
                            DeliveryMode.FULL_TRUCKLOAD)));
            when(loads.lockById(any())).thenReturn(Optional.of(row));
            when(loadStore.load(row)).thenReturn(load);
            return row;
        }

        @Test
        @DisplayName("its load is left with nothing to carry and is called off, with the reason of the system")
        void emptyLoadIsCalledOff() {
            DispatchLoad load = openLoadWith(shipmentId);
            DispatchLoadJpaEntity row = stored(load);
            when(vehicles.findAllById(Set.of(vehicle.id()))).thenReturn(List.of(
                    new LoadPlanVehicleJpaEntity(vehicle.id(), plan.id(), vehicle.seq(), vehicle.spec())));

            planVehicles.withdraw(shipmentId, NOW);

            assertThat(load.status()).isEqualTo(LoadStatus.CANCELLED);
            assertThat(load.activeShipments()).isEmpty();
            verify(loadStore).save(load, row);
        }

        @Test
        @DisplayName("a load that does not carry the shipment is left untouched")
        void notOnTheLoad() {
            DispatchLoad load = openLoadWith(UUID.randomUUID());
            DispatchLoadJpaEntity row = stored(load);

            planVehicles.withdraw(shipmentId, NOW);

            assertThat(load.status()).isNotEqualTo(LoadStatus.CANCELLED);
            verify(loadStore, never()).save(load, row);
        }

        @Test
        @DisplayName("a full truckload still carrying another shipment is not called off")
        void anotherShipmentRemains() {
            DispatchLoad load = openLoadWith(shipmentId);
            load.addShipment(new DispatchLoad.ShipmentFacts(UUID.randomUUID(), "SO-2-S1", WAREHOUSE,
                    DeliveryMode.FULL_TRUCKLOAD, ShipmentStatus.READY_TO_DISPATCH, null, null), BigDecimal.ZERO,
                    UUID.randomUUID(), NOW);
            stored(load);

            planVehicles.withdraw(shipmentId, NOW);

            assertThat(load.status()).isNotEqualTo(LoadStatus.CANCELLED);
            assertThat(load.activeShipments()).hasSize(1);
        }

        @Test
        @DisplayName("a parcel load emptied by a cancellation is left to the dispatcher")
        void parcelLoadsAreNotCalledOff() {
            DispatchLoad parcelLoad = DispatchLoad.openParcel(UUID.randomUUID(), "LD-0002", UUID.randomUUID(), WAREHOUSE,
                    UUID.randomUUID(), null, null, null, UUID.randomUUID(), NOW);

            assertThat(PlanVehicles.callOffIfEmpty(parcelLoad, NOW)).isFalse();
            assertThat(parcelLoad.status()).isEqualTo(LoadStatus.LOADING);
        }

        @Test
        @DisplayName("an empty full truckload is called off once; again it changes nothing")
        void callOffIsOneShot() {
            DispatchLoad load = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-0003", UUID.randomUUID(), WAREHOUSE,
                    vehicle.id());

            assertThat(PlanVehicles.callOffIfEmpty(load, NOW)).isTrue();
            assertThat(PlanVehicles.callOffIfEmpty(load, NOW)).isFalse();
        }

        @Test
        @DisplayName("a parcel of the shipment on a signed vehicle means the order's cancellation can no longer void it")
        void partlyGone() {
            DispatchLoadPackageJpaEntity onBoard = new DispatchLoadPackageJpaEntity(UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), shipmentId);
            when(loaded.findByShipmentIdInAndActiveTrue(List.of(shipmentId))).thenReturn(List.of(onBoard));
            DispatchLoadJpaEntity theLoad = load(LoadStatus.DISPATCHED, null);
            when(loads.findByIdIn(Set.of(onBoard.getLoadId()))).thenReturn(List.of(theLoad));

            assertThat(planVehicles.partlyGone(shipmentId)).isTrue();

            DispatchLoadJpaEntity stillLoading = load(LoadStatus.LOADING, null);
            when(loads.findByIdIn(Set.of(onBoard.getLoadId()))).thenReturn(List.of(stillLoading));
            assertThat(planVehicles.partlyGone(shipmentId)).isFalse();
        }

        @Test
        @DisplayName("a shipment with no parcel loaded has not gone anywhere")
        void nothingLoaded() {
            when(loaded.findByShipmentIdInAndActiveTrue(List.of(shipmentId))).thenReturn(List.of());

            assertThat(planVehicles.partlyGone(shipmentId)).isFalse();
            verify(loads, never()).findByIdIn(any());
        }
    }

    @Test
    @DisplayName("lists show each vehicle's plan code, sequence number and vehicle code")
    void describeVehicles() {
        LoadPlanVehicleJpaEntity row = new LoadPlanVehicleJpaEntity(vehicle.id(), plan.id(), vehicle.seq(), vehicle.spec());
        when(vehicles.findAllById(Set.of(vehicle.id()))).thenReturn(List.of(row));
        when(plans.findByIdIn(Set.of(plan.id()))).thenReturn(List.of(header));

        Map<UUID, PlanVehicles.VehicleRef> described = planVehicles.describe(Set.of(vehicle.id()));

        assertThat(described.get(vehicle.id())).isEqualTo(new PlanVehicles.VehicleRef(plan.id(), plan.code(),
                vehicle.seq(), row.getVehicleCode()));
        assertThat(planVehicles.describe(Set.of())).isEmpty();
    }
}
