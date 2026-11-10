package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageLineJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.LoadNumbers;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageLineJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentLocks;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A full truckload's vehicle signing (kltn-docs 09 §4.2 step 5, BR-14, BR-15; SCRUM-392): every parcel the plan placed on
 * the vehicle is on board, a shipment is handed over at its LAST vehicle's signature, the order is told that goods left,
 * and the plan is DISPATCHED when the last vehicle has left. Every order ships this way (09 BR-08); the rest of dispatch
 * is {@code DispatchServiceTest}'s.
 */
class DispatchVehicleHandoverTest {

    private final DispatchLoadJpaRepository loads = mock(DispatchLoadJpaRepository.class);
    private final DispatchLoadStore store = mock(DispatchLoadStore.class);
    private final ShipmentJpaRepository shipments = mock(ShipmentJpaRepository.class);
    private final ShipmentLocks locks = mock(ShipmentLocks.class);
    private final PackageJpaRepository packages = mock(PackageJpaRepository.class);
    private final PackageLineJpaRepository packageLines = mock(PackageLineJpaRepository.class);
    private final CarrierJpaRepository carriers = mock(CarrierJpaRepository.class);
    private final ShipmentTimeline timeline = mock(ShipmentTimeline.class);
    private final OrderService orders = mock(OrderService.class);
    private final PlanVehicles planVehicles = mock(PlanVehicles.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final DispatchService service = new DispatchService(loads, mock(DispatchLoadShipmentJpaRepository.class),
            mock(DispatchLoadPackageJpaRepository.class), store, mock(LoadNumbers.class), shipments, locks, packages,
            packageLines, mock(PickJpaRepository.class), carriers, timeline, mock(ShipmentContext.class), orders,
            warehouses, mock(IdentityService.class), planVehicles, mock(LeftBehindParcels.class),
            new Waybills(loads, mock(com.stockflow.fulfillment.internal.repository.WaybillChangeJpaRepository.class),
                    mock(jakarta.persistence.EntityManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS)),
            mock(com.stockflow.fulfillment.internal.repository.WaybillChangeJpaRepository.class),
            mock(com.stockflow.fulfillment.internal.repository.CarrierBookingJpaRepository.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final CurrentUser actor = FulfillmentFixtures.user();
    private CarrierJpaEntity ghn;

    @BeforeEach
    void aCarrier() {
        ghn = new CarrierJpaEntity(UUID.randomUUID(), "GHN");
        ghn.apply("GHN", "GHN Express", null, null, "ACTIVE");
        when(carriers.findById(ghn.getId())).thenReturn(Optional.of(ghn));
        when(shipments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(store.save(any(), any())).thenAnswer(call -> {
            DispatchLoadJpaEntity saved = call.getArgument(1);
            saved.apply(call.<DispatchLoad>getArgument(0).snapshot());
            return saved;
        });
    }

    /** One vehicle of a plan, with its load LOADING and the parcels the plan placed on it, scanned or not. */
    private record Vehicle(LoadPlan.Vehicle vehicle, DispatchLoad load, DispatchLoadJpaEntity header) { }

    private Vehicle vehicleLoad(LoadPlan plan, LoadPlan.Vehicle vehicle, UUID shipmentKey, boolean scanAll) {
        DispatchLoad load = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-V" + vehicle.seq(), UUID.randomUUID(),
                WAREHOUSE, vehicle.id());
        load.addShipment(new DispatchLoad.ShipmentFacts(shipmentKey, "SHP-" + shipmentKey.toString().substring(0, 4),
                WAREHOUSE, ShipmentStatus.READY_TO_DISPATCH, null), actor.userId(), NOW);
        load.book(ghn.getId(), "51C-" + vehicle.seq(), "An", null, "WB-" + vehicle.seq(), actor.userId(), NOW);
        load.startLoading(actor.userId(), NOW);
        if (scanAll) {
            for (LoadPlan.Placed placed : vehicle.placements()) {
                load.scan(UUID.randomUUID(), new DispatchLoad.ParcelFacts(placed.packageId(), "PKG-" + placed.packageId(),
                        PackageStatus.SEALED, new DispatchLoad.ShipmentFacts(shipmentKey, "SHP", WAREHOUSE,
                        ShipmentStatus.READY_TO_DISPATCH, null), null,
                        new DispatchLoad.Placement(true, plan.code(), vehicle.seq(), null)), actor.userId(), NOW);
            }
        }
        DispatchLoadJpaEntity header = new DispatchLoadJpaEntity(load.snapshot());
        header.apply(load.snapshot());
        when(loads.lockById(load.id())).thenReturn(Optional.of(header));
        when(store.load(header)).thenReturn(load);
        var key = mock(DispatchLoadJpaRepository.LoadKey.class);
        when(key.getWarehouseId()).thenReturn(WAREHOUSE);
        when(key.getLoadPlanVehicleId()).thenReturn(vehicle.id());
        when(loads.findKeyById(load.id())).thenReturn(Optional.of(key));
        when(loads.findById(load.id())).thenReturn(Optional.of(header));
        LoadPlanJpaEntity planHeader = new LoadPlanJpaEntity(plan.snapshot());
        planHeader.apply(plan.snapshot(), NOW);
        var ctx = new PlanVehicles.PlanOfLoad(planHeader, plan, vehicle);
        when(planVehicles.lock(vehicle.id())).thenReturn(ctx);
        when(planVehicles.read(header)).thenReturn(ctx);
        return new Vehicle(vehicle, load, header);
    }

    private List<PackageJpaEntity> sealedParcels(LoadPlan plan, UUID shipmentId) {
        List<PackageJpaEntity> out = new ArrayList<>();
        int no = 1;
        for (LoadPlan.Member m : plan.members()) {
            PackageJpaEntity parcel = new PackageJpaEntity(m.packageId(), UUID.randomUUID(), no, "PKG-" + no++);
            parcel.apply(shipmentId, com.stockflow.fulfillment.internal.domain.PackageType.CARTON, PackageStatus.SEALED,
                    new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"), null, true,
                    false, false, false, NOW, null, null, null);
            out.add(parcel);
        }
        org.mockito.Mockito.doAnswer(call -> {
            java.util.Collection<UUID> ids = call.getArgument(0);
            return out.stream().filter(p -> ids.contains(p.getId())).toList();
        }).when(packages).findAllById(any());
        org.mockito.Mockito.doReturn(out).when(packages).findByShipmentIdIn(any());
        return out;
    }

    private Dispatching.Handover signed(DispatchLoadJpaEntity header) {
        return new Dispatching.Handover(header.getVersion(), "Mr Hung", null, null, "sig", true, null);
    }

    private OrderSummary orderAllowingParts(UUID orderId, ShipmentJpaEntity row) {
        var shipment = new OrderSummary.Shipment(row.getId(), OrderShipmentStatus.HANDED_OVER, BigDecimal.ZERO, NOW, null, null, List.of(), null);
        var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(shipment), true);
        return new OrderSummary(orderId, "SO-0001", UUID.randomUUID(), OrderStatus.SHIPPED, Money.vnd(100_000), List.of(),
                NOW, null, null, null, null, null, null, null, null, WAREHOUSE, NOW, null, lifecycle);
    }

    @Nested
    @DisplayName("an order's full truckload")
    class OrderTruckload {

        private FulfillmentFixtures.PlannedPlan planned;
        private LoadPlan plan;
        private ShipmentJpaEntity row;

        private void confirmedPlan(int cubes, int trucks) {
            planned = FulfillmentFixtures.plannedPlan(cubes, trucks);
            plan = planned.plan();
            plan.confirm(planned.dispatcher(), NOW, planned.cartons());
            Shipment s = Shipment.ready(planned.shipmentId(), "SO-0001-S1", UUID.randomUUID(), UUID.randomUUID(), WAREHOUSE,
                    null, cubes, null, actor.userId(), NOW);
            row = FulfillmentFixtures.row(s);
            when(locks.ordersOf(any())).thenReturn(List.of(row.getOrderId()));
            when(locks.lockOfOrders(any())).thenReturn(List.of(row));
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(orderAllowingParts(row.getOrderId(), row)));
            when(orders.recordHandover(eq(row.getOrderId()), any())).thenReturn(orderAllowingParts(row.getOrderId(), row));
            when(shipments.findByIdIn(any())).thenReturn(List.of(row));
            when(shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId())).thenReturn(List.of(row));
            when(planVehicles.liveLoads(any())).thenReturn(new HashMap<>());
        }

        private void sealedWithContents() {
            List<PackageJpaEntity> parcels = sealedParcels(plan, planned.shipmentId());
            when(packageLines.findByPackageIdIn(any())).thenReturn(parcels.stream()
                    .map(p -> new PackageLineJpaEntity(UUID.randomUUID(), p.getId(), UUID.randomUUID(), 3)).toList());
        }

        @Test
        @DisplayName("a stale version is OPTIMISTIC_LOCK: check the manifest again before signing")
        void staleVersion() {
            confirmedPlan(2, 1);
            sealedWithContents();
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), true);

            assertThat(refusal(() -> service.handOver(v.load().id(), new Dispatching.Handover(99L, "Mr Hung", null, null,
                    null, true, null), actor)).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            assertThat(refusal(() -> service.handOver(v.load().id(), new Dispatching.Handover(null, "Mr Hung", null, null,
                    null, true, null), actor)).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("an unsigned manifest does not leave: HANDOVER_NOT_SIGNED, no order told")
        void notSigned() {
            confirmedPlan(2, 1);
            sealedWithContents();
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), true);

            assertThat(refusal(() -> service.handOver(v.load().id(), new Dispatching.Handover(v.header().getVersion(),
                    "Mr Hung", null, null, null, false, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.HANDOVER_NOT_SIGNED);
            verify(orders, never()).recordGoodsLeft(any(), any(), any());
            verify(orders, never()).recordHandover(any(), any());
        }

        @Test
        @DisplayName("a plan that is no longer CONFIRMED (reopened) does not let its vehicle leave")
        void planMustBeConfirmed() {
            confirmedPlan(2, 1);
            sealedWithContents();
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), true);
            plan.reopen("changed", false, actor.userId(), NOW);

            assertThat(refusal(() -> service.handOver(v.load().id(), signed(v.header()), actor)).errorCode())
                    .isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);
        }

        @Test
        @DisplayName("a parcel the plan placed on the vehicle and not scanned is LOAD_NOT_FULLY_LOADED: the plan placed them on this vehicle")
        void everyPlacedParcelMustBeOnBoard() {
            confirmedPlan(2, 1);
            sealedWithContents();
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), false);

            var thrown = refusal(() -> service.handOver(v.load().id(), signed(v.header()), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.LOAD_NOT_FULLY_LOADED);
            assertThat(thrown.getMessage()).contains("PKG-1").contains("the plan placed them on this vehicle");
            verify(orders, never()).recordGoodsLeft(any(), any(), any());
        }

        @Test
        @DisplayName("the only vehicle leaves: the order is told goods left, then the units, the carrier and the carriage "
                + "of the shipment, which is handed over; then the plan is DISPATCHED")
        void lastVehicleHandsTheShipmentOver() {
            confirmedPlan(2, 1);
            sealedWithContents();
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), true);
            when(planVehicles.completeIfDispatched(any(), eq(plan), any())).thenReturn(true);

            var detail = service.handOver(v.load().id(), signed(v.header()), actor);

            assertThat(v.load().status()).isEqualTo(LoadStatus.DISPATCHED);
            assertThat(detail.status()).isEqualTo("DISPATCHED");
            verify(orders).recordGoodsLeft(eq(row.getOrderId()), eq(NOW), any());
            ArgumentCaptor<ShipmentHandover> told = ArgumentCaptor.forClass(ShipmentHandover.class);
            verify(orders).recordHandover(eq(row.getOrderId()), told.capture());
            assertThat(told.getValue().lines()).hasSize(2).allSatisfy(l -> assertThat(l.quantity()).isEqualTo(3));
            assertThat(told.getValue().carriage().shipmentCode()).isEqualTo(row.getCode());
            assertThat(told.getValue().carriage().carrierName()).isEqualTo("GHN Express");
            assertThat(told.getValue().carriage().packageCount()).isEqualTo(2);
            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.HANDED_OVER);
            assertThat(row.getCarrierId()).isEqualTo(ghn.getId());
            verify(timeline).record(any(Shipment.class), eq(ShipmentStatus.READY_TO_DISPATCH), any(OrderSummary.class),
                    eq(null), eq("GHN Express"), eq("LD-V1"));
            verify(planVehicles).completeIfDispatched(any(), eq(plan), eq(NOW));
        }

        @Test
        @DisplayName("a vehicle already DISPATCHED answers as it is: nothing is told to any order again")
        void signedTwice() {
            confirmedPlan(2, 1);
            sealedWithContents();
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), true);
            Dispatching.Handover signature = signed(v.header());
            service.handOver(v.load().id(), signature, actor);
            org.mockito.Mockito.clearInvocations(orders);

            assertThat(service.handOver(v.load().id(), signature, actor).status()).isEqualTo("DISPATCHED");

            verify(orders, never()).recordGoodsLeft(any(), any(), any());
            verify(orders, never()).recordHandover(any(), any());
        }

        @Test
        @DisplayName("the order's refusal of the handover reaches the dispatcher with its code, naming the shipment")
        void orderRefusalOfTheHandoverIsNamed() {
            confirmedPlan(2, 1);
            sealedWithContents();
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), true);
            org.mockito.Mockito.doThrow(new com.stockflow.common.error.BusinessException(
                    ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED, "part of the order stays behind"))
                    .when(orders).recordHandover(any(), any());

            var thrown = refusal(() -> service.handOver(v.load().id(), signed(v.header()), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(thrown.getMessage()).startsWith("Shipment " + row.getCode() + ": part of the order stays behind");
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).contains("shipmentCode");
            assertThat(thrown.getCause()).isInstanceOf(com.stockflow.common.error.BusinessException.class);
        }

        @Test
        @DisplayName("a shipment whose parcels record no contents leaves as the whole order")
        void wholeOrderHandover() {
            confirmedPlan(2, 1);
            sealedParcels(plan, planned.shipmentId());
            when(packageLines.findByPackageIdIn(any())).thenReturn(List.of());
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), true);
            when(orders.markShipped(eq(row.getOrderId()), any(), any()))
                    .thenReturn(orderAllowingParts(row.getOrderId(), row));

            service.handOver(v.load().id(), signed(v.header()), actor);

            verify(orders).markShipped(row.getOrderId(), NOW, actor.userId());
            verify(orders, never()).recordHandover(any(), any());
            assertThat(row.isWholeOrderHandover()).isTrue();
            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.HANDED_OVER);
        }

        @Test
        @DisplayName("LP-3: a shipment on two vehicles stays waiting at the first signature and leaves at the second")
        void shipmentLeavesAtItsLastVehicle() {
            confirmedPlan(4, 2);
            sealedWithContents();
            LoadPlan.Vehicle first = plan.activeVehicles().get(0);
            LoadPlan.Vehicle second = plan.activeVehicles().get(1);
            Vehicle one = vehicleLoad(plan, first, planned.shipmentId(), true);
            Vehicle two = vehicleLoad(plan, second, planned.shipmentId(), true);
            Map<UUID, DispatchLoadJpaEntity> live = new HashMap<>();
            live.put(first.id(), one.header());
            live.put(second.id(), two.header());
            when(planVehicles.liveLoads(any())).thenReturn(live);

            service.handOver(one.load().id(), signed(one.header()), actor);

            assertThat(one.load().status()).isEqualTo(LoadStatus.DISPATCHED);
            assertThat(row.getStatus()).as("its other vehicle has not left").isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
            verify(orders).recordGoodsLeft(eq(row.getOrderId()), any(), any());
            verify(orders, never()).recordHandover(any(), any());

            service.handOver(two.load().id(), signed(two.header()), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.HANDED_OVER);
            verify(orders).recordHandover(eq(row.getOrderId()), any());
        }

        @Test
        @DisplayName("a shipment on the manifest no longer waiting for dispatch is PACKAGE_NOT_READY: it cannot leave")
        void shipmentMustStillWait() {
            confirmedPlan(2, 1);
            sealedWithContents();
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), true);
            ShipmentJpaEntity cancelled = FulfillmentFixtures.rowIn(ShipmentStatus.CANCELLED);
            org.springframework.test.util.ReflectionTestUtils.setField(cancelled, "id", planned.shipmentId());
            when(locks.lockOfOrders(any())).thenReturn(List.of(cancelled));

            assertThat(refusal(() -> service.handOver(v.load().id(), signed(v.header()), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_NOT_READY);
        }

        @Test
        @DisplayName("the order's gate (hold, unpaid, partial) refuses the vehicle, naming the shipment, before anything is handed over")
        void orderGateNamesTheShipment() {
            confirmedPlan(2, 1);
            sealedWithContents();
            Vehicle v = vehicleLoad(plan, plan.activeVehicles().getFirst(), planned.shipmentId(), true);
            org.mockito.Mockito.doThrow(new com.stockflow.common.error.BusinessException(
                    ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED, "units stay outside the plan"))
                    .when(orders).recordGoodsLeft(any(), any(), any());

            var thrown = refusal(() -> service.handOver(v.load().id(), signed(v.header()), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(thrown.getMessage()).startsWith("Shipment " + row.getCode() + ": units stay outside the plan");
            verify(orders, never()).recordHandover(any(), any());
        }

        @Test
        @DisplayName("loading starts only on a confirmed plan's booked vehicle, asking the order whether the plan carries the rest")
        void startLoadingAsksTheOrder() {
            confirmedPlan(2, 1);
            sealedWithContents();
            LoadPlan.Vehicle vehicle = plan.activeVehicles().getFirst();
            DispatchLoad load = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-V1", UUID.randomUUID(), WAREHOUSE,
                    vehicle.id());
            load.addShipment(new DispatchLoad.ShipmentFacts(planned.shipmentId(), row.getCode(), WAREHOUSE,
                    ShipmentStatus.READY_TO_DISPATCH, null), actor.userId(), NOW);
            load.book(ghn.getId(), "51C", null, null, "WB-1", actor.userId(), NOW);
            DispatchLoadJpaEntity header = new DispatchLoadJpaEntity(load.snapshot());
            header.apply(load.snapshot());
            when(loads.lockById(load.id())).thenReturn(Optional.of(header));
            when(store.load(header)).thenReturn(load);
            var key = mock(DispatchLoadJpaRepository.LoadKey.class);
            when(key.getWarehouseId()).thenReturn(WAREHOUSE);
            when(key.getLoadPlanVehicleId()).thenReturn(vehicle.id());
            when(loads.findKeyById(load.id())).thenReturn(Optional.of(key));
            LoadPlanJpaEntity planHeader = new LoadPlanJpaEntity(plan.snapshot());
            when(planVehicles.lock(vehicle.id())).thenReturn(new PlanVehicles.PlanOfLoad(planHeader, plan, vehicle));
            when(planVehicles.read(any())).thenReturn(new PlanVehicles.PlanOfLoad(planHeader, plan, vehicle));
            when(packageLines.findByPackageIdIn(any())).thenReturn(List.of());

            service.startLoading(load.id(), actor);

            verify(orders).requirePlanCarriesTheRest(eq(row.getOrderId()), any());
            assertThat(load.status()).isEqualTo(LoadStatus.LOADING);
        }
    }
}
