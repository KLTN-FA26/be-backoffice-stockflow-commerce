package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;
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
import com.stockflow.inventory.api.TransferFulfillment;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.OTHER_WAREHOUSE;
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
 * Dispatch of parcels to a carrier (kltn-docs 09 §4.1 step 2-3, BR-01, BR-03, BR-04): a load is opened when the driver
 * arrives, parcels are scanned onto the vehicle, both sides sign and each shipment leaves, telling its order in the
 * same transaction. The load's own rules are {@code DispatchLoadTest}'s.
 */
class DispatchServiceTest {

    private final DispatchLoadJpaRepository loads = mock(DispatchLoadJpaRepository.class);
    private final DispatchLoadShipmentJpaRepository members = mock(DispatchLoadShipmentJpaRepository.class);
    private final DispatchLoadPackageJpaRepository loaded = mock(DispatchLoadPackageJpaRepository.class);
    private final DispatchLoadStore store = mock(DispatchLoadStore.class);
    private final LoadNumbers numbers = mock(LoadNumbers.class);
    private final ShipmentJpaRepository shipments = mock(ShipmentJpaRepository.class);
    private final ShipmentLocks locks = mock(ShipmentLocks.class);
    private final PackageJpaRepository packages = mock(PackageJpaRepository.class);
    private final PackageLineJpaRepository packageLines = mock(PackageLineJpaRepository.class);
    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final CarrierJpaRepository carriers = mock(CarrierJpaRepository.class);
    private final ShipmentTimeline timeline = mock(ShipmentTimeline.class);
    private final ShipmentContext context = mock(ShipmentContext.class);
    private final OrderService orders = mock(OrderService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final PlanVehicles planVehicles = mock(PlanVehicles.class);
    private final LeftBehindParcels leftBehind = mock(LeftBehindParcels.class);
    private final TransferFulfillment transfers = mock(TransferFulfillment.class);
    private final DispatchService service = new DispatchService(loads, members, loaded, store, numbers, shipments, locks,
            packages, packageLines, picks, carriers, timeline, context, orders, warehouses, identities, planVehicles,
            leftBehind, Clock.fixed(NOW, ZoneOffset.UTC), transfers);

    private final CurrentUser actor = FulfillmentFixtures.user();
    private CarrierJpaEntity ghn;
    private DispatchLoad load;
    private DispatchLoadJpaEntity header;

    @BeforeEach
    void aCarrierAndAnOpenLoad() {
        ghn = new CarrierJpaEntity(UUID.randomUUID(), "GHN");
        ghn.apply("GHN", "GHN Express", null, null, "ACTIVE");
        when(carriers.findById(ghn.getId())).thenReturn(Optional.of(ghn));
        when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(
                new WarehouseView(WAREHOUSE, "HCM", "HCM warehouse", "1 Le Loi", true)));
        load = DispatchLoad.openParcel(UUID.randomUUID(), "LD-20261022-0001", UUID.randomUUID(), WAREHOUSE, ghn.getId(),
                "51C-123.45", "Driver Binh", "0900000009", actor.userId(), NOW.minusSeconds(600));
        header = new DispatchLoadJpaEntity(load.snapshot());
        header.apply(load.snapshot());
        when(loads.lockById(load.id())).thenReturn(Optional.of(header));
        when(loads.findById(load.id())).thenReturn(Optional.of(header));
        var key = mock(DispatchLoadJpaRepository.LoadKey.class);
        when(key.getWarehouseId()).thenReturn(WAREHOUSE);
        when(loads.findKeyById(load.id())).thenReturn(Optional.of(key));
        when(store.load(header)).thenReturn(load);
        // The store writes the rows and copies the load's state onto its header, as DispatchLoadStore.save does.
        when(store.save(any(), any())).thenAnswer(call -> {
            DispatchLoadJpaEntity saved = call.getArgument(1);
            saved.apply(call.<DispatchLoad>getArgument(0).snapshot());
            return saved;
        });
        when(store.insert(any())).thenAnswer(call -> {
            DispatchLoad inserted = call.getArgument(0);
            DispatchLoadJpaEntity created = new DispatchLoadJpaEntity(inserted.snapshot());
            created.apply(inserted.snapshot());
            return created;
        });
        when(shipments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(shipments.findByIdIn(any())).thenReturn(List.of());
    }

    private ShipmentJpaEntity shipmentRow(String cod) {
        Shipment s = Shipment.ready(UUID.randomUUID(), "SO-" + (int) (Math.random() * 9000 + 1000) + "-S1",
                UUID.randomUUID(), UUID.randomUUID(), WAREHOUSE, UUID.randomUUID(), DeliveryMode.PARCEL, 1,
                new BigDecimal(cod), null, actor.userId(), NOW.minusSeconds(3600));
        return FulfillmentFixtures.row(s);
    }

    private PackageJpaEntity parcelOf(ShipmentJpaEntity shipment, int no) {
        PackageJpaEntity parcel = FulfillmentFixtures.parcel(shipment.getId(), no, "40", "30", "25", "5");
        return parcel;
    }

    private void shipmentsLocked(ShipmentJpaEntity... rows) {
        List<UUID> ids = java.util.Arrays.stream(rows).map(ShipmentJpaEntity::getId).toList();
        when(locks.lock(any())).thenReturn(List.of(rows));
        when(locks.ordersOf(any())).thenReturn(java.util.Arrays.stream(rows).map(ShipmentJpaEntity::getOrderId).toList());
        when(locks.lockOfOrders(any())).thenReturn(List.of(rows));
        when(shipments.findByIdIn(any())).thenReturn(List.of(rows));
        when(shipments.findAllById(any())).thenReturn(List.of(rows));
        assertThat(ids).isNotEmpty();
    }

    private void putOnLoad(ShipmentJpaEntity row) {
        shipmentsLocked(row);
        service.addShipments(load.id(), List.of(row.getId()), actor);
    }

    private OrderSummary orderWithShipment(ShipmentJpaEntity row, String cod, boolean allowPartial, int quantity) {
        var shipment = new OrderSummary.Shipment(row.getId(), OrderShipmentStatus.HANDED_OVER, BigDecimal.ZERO,
                BigDecimal.ZERO, NOW, null, null, List.of(), new BigDecimal(cod), null);
        var line = new OrderSummary.LineSummary(UUID.randomUUID(), "SKU-A", quantity, Money.vnd(10_000), Money.vnd(100_000),
                List.of());
        var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(shipment), BigDecimal.ZERO,
                BigDecimal.ZERO, allowPartial, true);
        return new OrderSummary(row.getOrderId(), "SO-0001", UUID.randomUUID(), OrderStatus.SHIPPED, Money.vnd(100_000),
                List.of(line), NOW, null, null, null, null, null, null, null, null, null, WAREHOUSE, NOW, null, null,
                lifecycle);
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("a load that does not exist is LOAD_NOT_FOUND; one of another warehouse is OUT_OF_DATA_SCOPE")
        void getRefusals() {
            assertThat(refusal(() -> service.get(UUID.randomUUID())).errorCode()).isEqualTo(ErrorCode.LOAD_NOT_FOUND);
            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.get(load.id())).errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("an unknown status or mode filter is UNSUPPORTED_PARAMETER; another warehouse is OUT_OF_DATA_SCOPE")
        void listFilters() {
            assertThat(refusal(() -> service.list(null, "DONE", null, null, null, 0, 20)).errorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);
            assertThat(refusal(() -> service.list(null, null, "BOAT", null, null, 0, 20)).errorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);
            WithCurrentUser.run(FulfillmentFixtures.userOf(WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.list(OTHER_WAREHOUSE, null, null, null, null, 0, 20)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a page of loads shows each one's carrier name, counts and COD")
        void listRows() {
            when(loads.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                    any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(header),
                            org.springframework.data.domain.PageRequest.of(0, 20), 1));
            when(carriers.findAllById(any())).thenReturn(List.of(ghn));

            var page = service.list(WAREHOUSE, "loading", "parcel", ghn.getId(), "LD-", 0, 20);

            assertThat(page.items()).singleElement().satisfies(row -> {
                assertThat(row.carrierName()).isEqualTo("GHN Express");
                assertThat(row.status()).isEqualTo("LOADING");
                assertThat(row.mode()).isEqualTo("PARCEL");
                assertThat(row.vehiclePlate()).isEqualTo("51C-123.45");
            });
        }

        @Test
        @DisplayName("the detail of an open load lists its shipments with their parcels, which are loaded, and the COD figures")
        void detailOfAnOpenLoad() {
            ShipmentJpaEntity row = shipmentRow("250000");
            PackageJpaEntity parcel = parcelOf(row, 1);
            putOnLoad(row);
            when(packages.findByShipmentIdIn(any())).thenReturn(List.of(parcel));
            when(context.currencies(any())).thenReturn(Map.of(row.getOrderId(), "VND"));

            DispatchLoadJpaEntity current = header;
            var detail = service.get(load.id());

            assertThat(current.getCode()).isEqualTo(detail.code());
            assertThat(detail.shipments()).singleElement().satisfies(s -> {
                assertThat(s.labelCodAmount()).isEqualByComparingTo("250000");
                assertThat(s.packageCount()).isEqualTo(1);
                assertThat(s.loadedCount()).isZero();
                assertThat(s.currency()).isEqualTo("VND");
            });
            assertThat(detail.expectedPackageCount()).isEqualTo(1);
            assertThat(detail.carrierName()).isEqualTo("GHN Express");
        }
    }

    @Nested
    @DisplayName("opening a load when the driver arrives (09 §4.1 step 3)")
    class Opening {

        @Test
        @DisplayName("the same request id again answers with that load; for another carrier or warehouse it is CONFLICT")
        void replay() {
            UUID requestId = load.snapshot().requestId();
            when(loads.findByRequestId(requestId)).thenReturn(Optional.of(header));

            var again = service.open(new Dispatching.OpenLoad(requestId, WAREHOUSE, ghn.getId(), null, null, null, null),
                    actor);

            assertThat(again.loadId()).isEqualTo(load.id());
            verify(store, never()).insert(any());
            assertThat(refusal(() -> service.open(new Dispatching.OpenLoad(requestId, WAREHOUSE, UUID.randomUUID(), null,
                    null, null, null), actor)).errorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(refusal(() -> service.open(new Dispatching.OpenLoad(requestId, OTHER_WAREHOUSE, ghn.getId(), null,
                    null, null, null), actor)).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("an unknown warehouse is WAREHOUSE_NOT_FOUND; an unknown carrier CARRIER_NOT_FOUND; an INACTIVE one CARRIER_INACTIVE")
        void warehouseAndCarrierMustExist() {
            UUID unknownWarehouse = UUID.randomUUID();
            when(warehouses.findWarehouse(unknownWarehouse)).thenReturn(Optional.empty());
            assertThat(refusal(() -> service.open(new Dispatching.OpenLoad(UUID.randomUUID(), unknownWarehouse,
                    ghn.getId(), null, null, null, null), actor)).errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);

            assertThat(refusal(() -> service.open(new Dispatching.OpenLoad(UUID.randomUUID(), WAREHOUSE,
                    UUID.randomUUID(), null, null, null, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.CARRIER_NOT_FOUND);

            ghn.apply("GHN", "GHN Express", null, null, "INACTIVE");
            assertThat(refusal(() -> service.open(new Dispatching.OpenLoad(UUID.randomUUID(), WAREHOUSE, ghn.getId(),
                    null, null, null, null), actor)).errorCode()).isEqualTo(ErrorCode.CARRIER_INACTIVE);
        }

        @Test
        @DisplayName("a warehouse the caller is not assigned to is OUT_OF_DATA_SCOPE")
        void outOfScope() {
            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.open(new Dispatching.OpenLoad(UUID.randomUUID(), WAREHOUSE,
                            ghn.getId(), null, null, null, null), actor)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("without a list it takes the shipments chosen for that carrier that are on no load yet")
        void defaultsToTheShipmentsWaitingForTheCarrier() {
            ShipmentJpaEntity free = shipmentRow("0");
            ShipmentJpaEntity taken = shipmentRow("0");
            shipmentsLocked(free);
            when(numbers.next(any())).thenReturn("LD-20261022-0002");
            when(shipments.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                    any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(free, taken));
            when(members.findByShipmentIdInAndActiveTrue(any())).thenReturn(List.of(
                    new DispatchLoadShipmentJpaEntity(UUID.randomUUID(), UUID.randomUUID(), taken.getId(),
                            DeliveryMode.PARCEL)));

            service.open(new Dispatching.OpenLoad(UUID.randomUUID(), WAREHOUSE, ghn.getId(), null, "51C-1", "An", null),
                    actor);

            ArgumentCaptor<DispatchLoad> inserted = ArgumentCaptor.forClass(DispatchLoad.class);
            verify(store).insert(inserted.capture());
            assertThat(inserted.getValue().code()).isEqualTo("LD-20261022-0002");
            assertThat(inserted.getValue().status()).isEqualTo(LoadStatus.LOADING);
            assertThat(inserted.getValue().activeShipments()).containsExactly(free.getId());
        }

        @Test
        @DisplayName("PACK-PARTIAL: by default a shipment that would hand over part of an order that may not ship in parts stays at dispatch")
        void defaultSkipsSplittingShipments() {
            ShipmentJpaEntity part = shipmentRow("0");
            shipmentsLocked(part);
            when(numbers.next(any())).thenReturn("LD-20261022-0003");
            when(shipments.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                    any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(part));
            PackageJpaEntity parcel = parcelOf(part, 1);
            UUID orderLine = UUID.randomUUID();
            when(packages.findByShipmentIdIn(any())).thenReturn(List.of(parcel));
            when(packageLines.findByPackageIdIn(any())).thenReturn(List.of(
                    new PackageLineJpaEntity(UUID.randomUUID(), parcel.getId(), orderLine, 6)));
            when(orders.findById(part.getOrderId())).thenReturn(Optional.of(
                    orderWithShipment(part, "0", false, 10)));

            service.open(new Dispatching.OpenLoad(UUID.randomUUID(), WAREHOUSE, ghn.getId(), null, null, null, null), actor);

            ArgumentCaptor<DispatchLoad> inserted = ArgumentCaptor.forClass(DispatchLoad.class);
            verify(store).insert(inserted.capture());
            assertThat(inserted.getValue().activeShipments()).isEmpty();
        }

        @Test
        @DisplayName("PACK-PARTIAL: naming such a shipment is ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED, listing its code")
        void namedSplittingShipmentIsRefused() {
            ShipmentJpaEntity part = shipmentRow("0");
            shipmentsLocked(part);
            when(numbers.next(any())).thenReturn("LD-20261022-0004");
            PackageJpaEntity parcel = parcelOf(part, 1);
            when(packages.findByShipmentIdIn(any())).thenReturn(List.of(parcel));
            when(packageLines.findByPackageIdIn(any())).thenReturn(List.of(
                    new PackageLineJpaEntity(UUID.randomUUID(), parcel.getId(), UUID.randomUUID(), 6)));
            when(orders.findById(part.getOrderId())).thenReturn(Optional.of(orderWithShipment(part, "0", false, 10)));

            var thrown = refusal(() -> service.open(new Dispatching.OpenLoad(UUID.randomUUID(), WAREHOUSE, ghn.getId(),
                    List.of(part.getId()), null, null, null), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.message()).contains(part.getCode());
        }
    }

    @Nested
    @DisplayName("the manifest's list")
    class ManifestList {

        @Test
        @DisplayName("a shipment is put on an open load once, and the load is saved")
        void addsAShipment() {
            ShipmentJpaEntity row = shipmentRow("120000");
            shipmentsLocked(row);

            var detail = service.addShipments(load.id(), List.of(row.getId()), actor);

            assertThat(load.activeShipments()).containsExactly(row.getId());
            assertThat(detail.shipmentCount()).isEqualTo(1);
            verify(store).save(load, header);
        }

        @Test
        @DisplayName("adding a shipment already on it, or nothing, saves nothing")
        void idempotent() {
            ShipmentJpaEntity row = shipmentRow("0");
            putOnLoad(row);
            org.mockito.Mockito.clearInvocations(store);

            service.addShipments(load.id(), List.of(row.getId()), actor);
            service.addShipments(load.id(), List.of(), actor);

            verify(store, never()).save(any(), any());
        }

        @Test
        @DisplayName("a shipment that cannot be found is SHIPMENT_NOT_FOUND")
        void unknownShipment() {
            when(locks.lock(any())).thenReturn(List.of());

            assertThat(refusal(() -> service.addShipments(load.id(), List.of(UUID.randomUUID()), actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("a shipment chosen for another carrier is SHIPMENT_OTHER_CARRIER")
        void otherCarrier() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.assignCarrier(UUID.randomUUID());
            ShipmentJpaEntity row = FulfillmentFixtures.row(s);
            shipmentsLocked(row);

            assertThat(refusal(() -> service.addShipments(load.id(), List.of(row.getId()), actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_OTHER_CARRIER);
        }

        @Test
        @DisplayName("a shipment already on another open load is SHIPMENT_ON_ANOTHER_LOAD, naming that load")
        void onAnotherLoad() {
            ShipmentJpaEntity row = shipmentRow("0");
            shipmentsLocked(row);
            UUID other = UUID.randomUUID();
            when(members.findByShipmentIdInAndActiveTrue(any())).thenReturn(List.of(
                    new DispatchLoadShipmentJpaEntity(UUID.randomUUID(), other, row.getId(), DeliveryMode.PARCEL)));
            DispatchLoadJpaEntity otherHeader = mock(DispatchLoadJpaEntity.class);
            when(otherHeader.getId()).thenReturn(other);
            when(otherHeader.getCode()).thenReturn("LD-OTHER");
            when(loads.findByIdIn(any())).thenReturn(List.of(otherHeader));

            var thrown = refusal(() -> service.addShipments(load.id(), List.of(row.getId()), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SHIPMENT_ON_ANOTHER_LOAD);
            assertThat(thrown.getMessage()).contains("LD-OTHER");
        }

        @Test
        @DisplayName("a vehicle of a load plan changes only through its plan: LOAD_MANAGED_BY_PLAN")
        void planManagedLoadsAreUntouchable() {
            DispatchLoad vehicle = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-V1", UUID.randomUUID(), WAREHOUSE,
                    UUID.randomUUID());
            DispatchLoadJpaEntity vehicleHeader = new DispatchLoadJpaEntity(vehicle.snapshot());
            when(loads.lockById(vehicle.id())).thenReturn(Optional.of(vehicleHeader));
            when(store.load(vehicleHeader)).thenReturn(vehicle);

            assertThat(refusal(() -> service.addShipments(vehicle.id(), List.of(UUID.randomUUID()), actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_MANAGED_BY_PLAN);
            assertThat(refusal(() -> service.removeShipment(vehicle.id(), UUID.randomUUID(), actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_MANAGED_BY_PLAN);
            assertThat(refusal(() -> service.cancel(vehicle.id(), "x", actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_MANAGED_BY_PLAN);
        }

        @Test
        @DisplayName("a shipment is taken off the manifest before the handover; not on it, nothing changes")
        void removesAShipment() {
            ShipmentJpaEntity row = shipmentRow("0");
            putOnLoad(row);
            org.mockito.Mockito.clearInvocations(store);

            service.removeShipment(load.id(), row.getId(), actor);
            assertThat(load.activeShipments()).isEmpty();
            verify(store).save(load, header);

            org.mockito.Mockito.clearInvocations(store);
            service.removeShipment(load.id(), row.getId(), actor);
            verify(store, never()).save(any(), any());
        }

        @Test
        @DisplayName("a load called off keeps its record; calling it off again changes nothing")
        void cancelsAParcelLoad() {
            service.cancel(load.id(), "the driver did not come", actor);
            assertThat(load.status()).isEqualTo(LoadStatus.CANCELLED);
            verify(store).save(load, header);

            org.mockito.Mockito.clearInvocations(store);
            service.cancel(load.id(), "again", actor);
            verify(store, never()).save(any(), any());
        }
    }

    @Nested
    @DisplayName("scanning parcels onto the vehicle")
    class Scanning {

        private ShipmentJpaEntity row;
        private PackageJpaEntity parcel;

        @BeforeEach
        void aShipmentOnTheLoad() {
            row = shipmentRow("0");
            parcel = parcelOf(row, 1);
            putOnLoad(row);
            when(packages.findByLabelCode("PKG-1")).thenReturn(Optional.of(parcel));
            when(shipments.findById(row.getId())).thenReturn(Optional.of(row));
        }

        @Test
        @DisplayName("a parcel of a shipment on the manifest is loaded and the load saved")
        void loadsTheParcel() {
            var detail = service.scan(load.id(), UUID.randomUUID(), " PKG-1 ", actor);

            assertThat(detail.loadedPackageCount()).isEqualTo(1);
            assertThat(load.activeParcels()).extracting(DispatchLoad.Loaded::packageId).containsExactly(parcel.getId());
        }

        @Test
        @DisplayName("a label typed in lower case is found by its upper-case form")
        void caseInsensitiveLabel() {
            when(packages.findByLabelCode("pkg-1")).thenReturn(Optional.empty());

            assertThat(service.scan(load.id(), UUID.randomUUID(), "pkg-1", actor).loadedPackageCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a label no parcel has is PACKAGE_NOT_FOUND")
        void unknownLabel() {
            when(packages.findByLabelCode(any())).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.scan(load.id(), UUID.randomUUID(), "PKG-9", actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("a parcel moved to another shipment since its label was printed is LABEL_OUTDATED: reprint it first")
        void outdatedLabel() {
            parcel.markLabelOutdated(NOW.minusSeconds(10));

            assertThat(refusal(() -> service.scan(load.id(), UUID.randomUUID(), "PKG-1", actor)).errorCode())
                    .isEqualTo(ErrorCode.LABEL_OUTDATED);
        }

        @Test
        @DisplayName("a parcel already loaded on another load is PACKAGE_ALREADY_LOADED, naming it")
        void alreadyOnAnotherLoad() {
            UUID otherId = UUID.randomUUID();
            DispatchLoadPackageJpaEntity elsewhere = new DispatchLoadPackageJpaEntity(UUID.randomUUID(), otherId,
                    parcel.getId(), row.getId());
            when(loaded.findByPackageIdAndActiveTrue(parcel.getId())).thenReturn(Optional.of(elsewhere));
            DispatchLoadJpaEntity otherHeader = mock(DispatchLoadJpaEntity.class);
            when(otherHeader.getCode()).thenReturn("LD-OTHER");
            when(loads.findById(otherId)).thenReturn(Optional.of(otherHeader));

            var thrown = refusal(() -> service.scan(load.id(), UUID.randomUUID(), "PKG-1", actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.PACKAGE_ALREADY_LOADED);
            assertThat(thrown.getMessage()).contains("LD-OTHER");
        }

        @Test
        @DisplayName("the same scan id on the same label answers as before; on another label or load it is LOAD_SCAN_ID_CONFLICT")
        void scanIdReplay() {
            UUID scanId = UUID.randomUUID();
            DispatchLoadPackageJpaEntity recorded = new DispatchLoadPackageJpaEntity(UUID.randomUUID(), load.id(),
                    parcel.getId(), row.getId());
            when(loaded.findByScanId(scanId)).thenReturn(Optional.of(recorded));
            when(packages.findById(parcel.getId())).thenReturn(Optional.of(parcel));

            assertThat(service.scan(load.id(), scanId, "pkg-1", actor).loadedPackageCount()).isZero();

            assertThat(refusal(() -> service.scan(load.id(), scanId, "PKG-2", actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_SCAN_ID_CONFLICT);
        }

        @Test
        @DisplayName("a parcel can be taken off the vehicle again; not loaded, nothing changes")
        void unscans() {
            service.scan(load.id(), UUID.randomUUID(), "PKG-1", actor);

            assertThat(service.unscan(load.id(), parcel.getId(), actor).loadedPackageCount()).isZero();
            org.mockito.Mockito.clearInvocations(store);
            service.unscan(load.id(), parcel.getId(), actor);
            verify(store, never()).save(any(), any());
        }
    }

    @Nested
    @DisplayName("the signature: each shipment leaves and tells its order (09 §4.1 step 3, BR-03, BR-04)")
    class Handover {

        private ShipmentJpaEntity row;
        private PackageJpaEntity parcel;
        private Dispatching.Handover signed;

        @BeforeEach
        void aLoadedShipment() {
            row = shipmentRow("80000");
            parcel = parcelOf(row, 1);
            putOnLoad(row);
            when(packages.findByShipmentIdIn(any())).thenReturn(List.of(parcel));
            when(packageLines.findByPackageIdIn(any())).thenReturn(List.of(
                    new PackageLineJpaEntity(UUID.randomUUID(), parcel.getId(), UUID.randomUUID(), 3)));
            when(shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId())).thenReturn(List.of(row));
            when(orders.findById(any())).thenReturn(Optional.empty());
            signed = new Dispatching.Handover(header.getVersion(), "Mr Hung", null, null, "sig-1", true, null);
        }

        private void parcelScanned() {
            load.scan(UUID.randomUUID(), new DispatchLoad.ParcelFacts(parcel.getId(), parcel.getLabelCode(),
                    PackageStatus.SEALED, new DispatchLoad.ShipmentFacts(row.getId(), row.getCode(), WAREHOUSE,
                            DeliveryMode.PARCEL, ShipmentStatus.READY_TO_DISPATCH, null, null), null), actor.userId(), NOW);
        }

        @Test
        @DisplayName("a stale version is OPTIMISTIC_LOCK: check the manifest again before signing")
        void staleVersion() {
            parcelScanned();

            assertThat(refusal(() -> service.handOver(load.id(), new Dispatching.Handover(header.getVersion() + 1,
                    "Mr Hung", null, null, null, true, null), actor)).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            assertThat(refusal(() -> service.handOver(load.id(), new Dispatching.Handover(null, "Mr Hung", null, null,
                    null, true, null), actor)).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("an unsigned manifest does not leave: HANDOVER_NOT_SIGNED")
        void notSigned() {
            parcelScanned();
            when(locks.lockOfOrders(any())).thenReturn(List.of(row));

            assertThat(refusal(() -> service.handOver(load.id(), new Dispatching.Handover(header.getVersion(), "Mr Hung",
                    null, null, null, false, null), actor)).errorCode()).isEqualTo(ErrorCode.HANDOVER_NOT_SIGNED);
            verify(orders, never()).recordHandover(any(), any());
        }

        @Test
        @DisplayName("a parcel not scanned onto the vehicle is LOAD_NOT_FULLY_LOADED, naming its label")
        void unscannedParcel() {
            var thrown = refusal(() -> service.handOver(load.id(), signed, actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.LOAD_NOT_FULLY_LOADED);
            assertThat(thrown.getMessage()).contains(parcel.getLabelCode());
            verify(orders, never()).recordHandover(any(), any());
        }

        @Test
        @DisplayName("a shipment no longer waiting for dispatch is PACKAGE_NOT_READY: take it off the manifest")
        void shipmentNotWaiting() {
            parcelScanned();
            ShipmentJpaEntity cancelled = FulfillmentFixtures.rowIn(ShipmentStatus.CANCELLED);
            org.springframework.test.util.ReflectionTestUtils.setField(cancelled, "id", row.getId());
            when(locks.lockOfOrders(any())).thenReturn(List.of(cancelled));

            assertThat(refusal(() -> service.handOver(load.id(), signed, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_NOT_READY);
        }

        @Test
        @DisplayName("the load leaves: the order is told the units, the carrier and the carriage, and fixes the shipment's COD")
        void handsOver() {
            parcelScanned();
            when(orders.recordHandover(eq(row.getOrderId()), any())).thenReturn(
                    orderWithShipment(row, "80000", true, 3));
            when(carriers.findById(ghn.getId())).thenReturn(Optional.of(ghn));

            var detail = service.handOver(load.id(), signed, actor);

            assertThat(load.status()).isEqualTo(LoadStatus.DISPATCHED);
            assertThat(detail.status()).isEqualTo("DISPATCHED");
            ArgumentCaptor<ShipmentHandover> told = ArgumentCaptor.forClass(ShipmentHandover.class);
            verify(orders).recordHandover(eq(row.getOrderId()), told.capture());
            assertThat(told.getValue().lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(3));
            assertThat(told.getValue().carriage().carrierName()).isEqualTo("GHN Express");
            assertThat(told.getValue().carriage().packageCount()).isEqualTo(1);
            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.HANDED_OVER);
            assertThat(row.getCodAmount()).isEqualByComparingTo("80000");
            assertThat(row.getCarrierId()).isEqualTo(ghn.getId());
            verify(timeline).record(any(Shipment.class), eq(ShipmentStatus.READY_TO_DISPATCH), any(OrderSummary.class),
                    eq(null), eq("GHN Express"), eq(load.code()));
            assertThat(load.codTotal()).isEqualByComparingTo("80000");
        }

        @Test
        @DisplayName("BR-04: a COD the order fixes that is not the one on the label is LABEL_OUTDATED, naming the shipment")
        void codMustMatchTheLabel() {
            parcelScanned();
            when(orders.recordHandover(eq(row.getOrderId()), any())).thenReturn(
                    orderWithShipment(row, "95000", true, 3));
            when(carriers.findById(ghn.getId())).thenReturn(Optional.of(ghn));

            var thrown = refusal(() -> service.handOver(load.id(), signed, actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.LABEL_OUTDATED);
            assertThat(thrown.getMessage()).contains(row.getCode()).contains("take it off the manifest");
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).contains("shipmentCode");
            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
        }

        @Test
        @DisplayName("the order's own refusal reaches the dispatcher with its code, naming the shipment and its fields")
        void orderRefusalIsNamed() {
            parcelScanned();
            when(carriers.findById(ghn.getId())).thenReturn(Optional.of(ghn));
            when(orders.recordHandover(any(), any())).thenThrow(new BusinessException(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED,
                    "part of the order stays behind"));

            var thrown = refusal(() -> service.handOver(load.id(), signed, actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(thrown.getMessage()).startsWith("Shipment " + row.getCode() + ": part of the order stays behind");
            assertThat(thrown.getCause()).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("a shipment whose parcels record no contents leaves as the whole order, with the order's whole-order COD")
        void wholeOrderHandover() {
            parcelScanned();
            when(packageLines.findByPackageIdIn(any())).thenReturn(List.of());
            OrderSummary base = orderWithShipment(row, "0", true, 3);
            var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(),
                    BigDecimal.ZERO, new BigDecimal("80000"), true, true);
            OrderSummary shipped = new OrderSummary(base.orderId(), "SO-0001", base.customerId(), OrderStatus.SHIPPED,
                    base.total(), base.lines(), NOW, null, null, null, null, null, null, null, null, null, WAREHOUSE, NOW,
                    null, null, lifecycle);
            when(orders.markShipped(eq(row.getOrderId()), any(), any())).thenReturn(shipped);
            when(carriers.findById(ghn.getId())).thenReturn(Optional.of(ghn));

            service.handOver(load.id(), signed, actor);

            verify(orders).markShipped(row.getOrderId(), NOW, actor.userId());
            verify(orders, never()).recordHandover(any(), any());
            assertThat(row.isWholeOrderHandover()).isTrue();
            assertThat(row.getCodAmount()).isEqualByComparingTo("80000");
        }

        @Test
        @DisplayName("a load already DISPATCHED answers as it is: nothing is told to any order again")
        void signedTwice() {
            parcelScanned();
            when(orders.recordHandover(any(), any())).thenReturn(orderWithShipment(row, "80000", true, 3));
            when(carriers.findById(ghn.getId())).thenReturn(Optional.of(ghn));
            service.handOver(load.id(), signed, actor);
            org.mockito.Mockito.clearInvocations(orders);

            service.handOver(load.id(), signed, actor);

            verify(orders, never()).recordHandover(any(), any());
        }
    }

    @Nested
    @DisplayName("a load plan's vehicle")
    class Vehicle {

        private DispatchLoad vehicle;
        private DispatchLoadJpaEntity vehicleHeader;

        @BeforeEach
        void aPlannedVehicle() {
            vehicle = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-V1", UUID.randomUUID(), WAREHOUSE,
                    UUID.randomUUID());
            vehicleHeader = new DispatchLoadJpaEntity(vehicle.snapshot());
            vehicleHeader.apply(vehicle.snapshot());
            var key = mock(DispatchLoadJpaRepository.LoadKey.class);
            when(key.getWarehouseId()).thenReturn(WAREHOUSE);
            when(key.getLoadPlanVehicleId()).thenReturn(vehicle.loadPlanVehicleId());
            when(loads.findKeyById(vehicle.id())).thenReturn(Optional.of(key));
            when(loads.lockById(vehicle.id())).thenReturn(Optional.of(vehicleHeader));
            when(store.load(vehicleHeader)).thenReturn(vehicle);
        }

        private PlanVehicles.PlanOfLoad confirmedPlan() {
            var scenario = FulfillmentFixtures.confirmedPlanLeavingOneBehind();
            var ctx = new PlanVehicles.PlanOfLoad(new com.stockflow.fulfillment.internal.entity.LoadPlanJpaEntity(scenario.plan().snapshot()), scenario.plan(), scenario.plan().activeVehicles().getFirst());
            when(planVehicles.lock(vehicle.loadPlanVehicleId())).thenReturn(ctx);
            when(planVehicles.read(any())).thenReturn(ctx);
            return ctx;
        }

        @Test
        @DisplayName("a parcel load is not booked: only a plan's vehicle is (INVALID_LOAD_TRANSITION)")
        void parcelLoadsAreNotBooked() {
            assertThat(refusal(() -> service.book(load.id(), new Dispatching.Booking(ghn.getId(), null, null, null), actor))
                    .errorCode()).isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
        }

        @Test
        @DisplayName("a load that does not exist is LOAD_NOT_FOUND on every change")
        void unknownLoad() {
            UUID unknown = UUID.randomUUID();
            when(loads.findKeyById(unknown)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.book(unknown, new Dispatching.Booking(ghn.getId(), null, null, null), actor))
                    .errorCode()).isEqualTo(ErrorCode.LOAD_NOT_FOUND);
            assertThat(refusal(() -> service.startLoading(unknown, actor)).errorCode()).isEqualTo(ErrorCode.LOAD_NOT_FOUND);
            assertThat(refusal(() -> service.cancelBooking(unknown, "x", actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_NOT_FOUND);
        }

        @Test
        @DisplayName("a vehicle is booked with an active, declared carrier only")
        void bookingNeedsAnActiveCarrier() {
            confirmedPlan();
            assertThat(refusal(() -> service.book(vehicle.id(), new Dispatching.Booking(UUID.randomUUID(), null, null,
                    null), actor)).errorCode()).isEqualTo(ErrorCode.CARRIER_NOT_FOUND);

            ghn.apply("GHN", "GHN Express", null, null, "INACTIVE");
            assertThat(refusal(() -> service.book(vehicle.id(), new Dispatching.Booking(ghn.getId(), null, null, null),
                    actor)).errorCode()).isEqualTo(ErrorCode.CARRIER_INACTIVE);
        }

        @Test
        @DisplayName("a shipment chosen for another carrier on the vehicle blocks the booking: SHIPMENT_OTHER_CARRIER")
        void bookingRespectsChosenCarriers() {
            confirmedPlan();
            Shipment s = FulfillmentFixtures.readyShipment(DeliveryMode.FULL_TRUCKLOAD, 3, "0");
            s.assignCarrier(UUID.randomUUID());
            ShipmentJpaEntity row = FulfillmentFixtures.row(s);
            vehicle.addShipment(new DispatchLoad.ShipmentFacts(row.getId(), row.getCode(), WAREHOUSE,
                    DeliveryMode.FULL_TRUCKLOAD, ShipmentStatus.READY_TO_DISPATCH, null, null), BigDecimal.ZERO,
                    actor.userId(), NOW);
            when(shipments.findByIdIn(any())).thenReturn(List.of(row));

            var thrown = refusal(() -> service.book(vehicle.id(), new Dispatching.Booking(ghn.getId(), "51C", null, null),
                    actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SHIPMENT_OTHER_CARRIER);
            assertThat(thrown.fieldErrors()).extracting(f -> f.message()).contains(row.getCode());
        }

        @Test
        @DisplayName("a confirmed plan's vehicle is booked and saved; the plan must be CONFIRMED")
        void books() {
            confirmedPlan();
            when(shipments.findByIdIn(any())).thenReturn(List.of());

            var detail = service.book(vehicle.id(), new Dispatching.Booking(ghn.getId(), "51C-9", "An", "0900"), actor);

            assertThat(vehicle.status()).isEqualTo(LoadStatus.VEHICLE_BOOKED);
            assertThat(detail.carrierName()).isEqualTo("GHN Express");
            verify(store).save(vehicle, vehicleHeader);
        }

        @Test
        @DisplayName("a vehicle whose plan was reopened is not booked: INVALID_LOAD_PLAN_TRANSITION")
        void planMustBeConfirmedToBook() {
            var ctx = confirmedPlan();
            ctx.plan().reopen("again", false, actor.userId(), NOW);

            assertThat(refusal(() -> service.book(vehicle.id(), new Dispatching.Booking(ghn.getId(), null, null, null),
                    actor)).errorCode()).isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);
        }

        @Test
        @DisplayName("loading cannot start before a vehicle is booked: INVALID_LOAD_TRANSITION")
        void loadingNeedsABooking() {
            confirmedPlan();
            when(shipments.findByIdIn(any())).thenReturn(List.of());

            assertThat(refusal(() -> service.startLoading(vehicle.id(), actor)).errorCode())
                    .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
            verify(leftBehind, never()).splitOff(any(), any(), any());
        }

        @Test
        @DisplayName("BR-14: loading a booked vehicle splits off the parcels its plan left behind, once")
        void startingToLoadSplitsTheLeftBehind() {
            var ctx = confirmedPlan();
            vehicle.book(ghn.getId(), "51C-9", null, null, actor.userId(), NOW);
            when(shipments.findByIdIn(any())).thenReturn(List.of());

            service.startLoading(vehicle.id(), actor);

            assertThat(vehicle.status()).isEqualTo(LoadStatus.LOADING);
            verify(leftBehind).splitOff(ctx.plan(), actor.userId(), NOW);

            org.mockito.Mockito.clearInvocations(leftBehind);
            service.startLoading(vehicle.id(), actor);
            verify(leftBehind, never()).splitOff(any(), any(), any());
        }

        @Test
        @DisplayName("a booking that the carrier cancelled is recorded; the load keeps showing its booking to cancel outside")
        void cancelsABooking() {
            confirmedPlan();
            vehicle.book(ghn.getId(), "51C-9", null, null, actor.userId(), NOW);

            var detail = service.cancelBooking(vehicle.id(), "truck broke down", actor);

            assertThat(vehicle.status()).isEqualTo(LoadStatus.PLANNED);
            assertThat(detail.bookingCancelReason()).isEqualTo("truck broke down");
        }

        @Test
        @DisplayName("an unloaded parcel needs a label that exists: PACKAGE_NOT_FOUND")
        void unloadNeedsAKnownLabel() {
            confirmedPlan();
            when(packages.findByLabelCode(any())).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.unload(vehicle.id(), "PKG-X", actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("a parcel not on the list to unload is PACKAGE_NOT_IN_LOAD")
        void unloadOfAParcelNotToBeUnloaded() {
            confirmedPlan();
            PackageJpaEntity parcel = FulfillmentFixtures.parcel(UUID.randomUUID(), 1, "40", "30", "25", "5");
            when(packages.findByLabelCode("PKG-1")).thenReturn(Optional.of(parcel));

            assertThat(refusal(() -> service.unload(vehicle.id(), "PKG-1", actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_NOT_IN_LOAD);
        }
    }

    @Nested
    @DisplayName("the printable manifest (09 §3 Output)")
    class Manifest {

        @Test
        @DisplayName("a manifest that does not exist is LOAD_NOT_FOUND")
        void unknown() {
            assertThat(refusal(() -> service.manifest(UUID.randomUUID())).errorCode()).isEqualTo(ErrorCode.LOAD_NOT_FOUND);
        }

        @Test
        @DisplayName("lists each shipment with its recipient, parcels, weight and the COD to collect, and flags a COD changed since the label")
        void printsTheManifest() {
            ShipmentJpaEntity row = shipmentRow("80000");
            PackageJpaEntity parcel = parcelOf(row, 1);
            putOnLoad(row);
            load.fixCod(row.getId(), new BigDecimal("95000"));
            when(packages.findByShipmentIdIn(any())).thenReturn(List.of(parcel));
            var address = new OrderSummary.AddressSummary(null, "0911", "12 Nguyen Hue", null, "W1", "Ben Nghe", "P1", "HCM",
                    "VN", "700000");
            OrderSummary base = orderWithShipment(row, "0", true, 3);
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(new OrderSummary(row.getOrderId(), "SO-0042",
                    base.customerId(), OrderStatus.PACKED, base.total(), base.lines(), NOW, null, null, null, "Ms Lan", null,
                    "0900", address, null, null, WAREHOUSE, NOW, null, null, base.lifecycle())));

            var manifest = service.manifest(load.id());

            assertThat(manifest.loadCode()).isEqualTo(load.code());
            assertThat(manifest.warehouseName()).isEqualTo("HCM warehouse");
            assertThat(manifest.carrierName()).isEqualTo("GHN Express");
            assertThat(manifest.packageCount()).isEqualTo(1);
            assertThat(manifest.totalWeightKg()).isEqualByComparingTo("5");
            assertThat(manifest.shipments()).singleElement().satisfies(line -> {
                assertThat(line.orderNumber()).isEqualTo("SO-0042");
                assertThat(line.recipientName()).isEqualTo("Ms Lan");
                assertThat(line.recipientPhone()).isEqualTo("0911");
                assertThat(line.deliveryAddress()).isEqualTo("12 Nguyen Hue, Ben Nghe, HCM");
                assertThat(line.codAmount()).isEqualByComparingTo("95000");
                assertThat(line.codChangedSinceLabel()).isTrue();
            });
        }
    }
}
