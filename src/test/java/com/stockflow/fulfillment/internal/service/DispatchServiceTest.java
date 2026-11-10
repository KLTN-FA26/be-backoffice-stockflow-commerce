package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.LoadNumbers;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.ParcelContents;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentLocks;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.OTHER_WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dispatch of a load plan's vehicles (kltn-docs 09 §4.2 step 5, BR-03, BR-14): reading loads, scanning parcels onto a
 * vehicle, its booking and the start of loading, unloading, and the printable manifest. Every order ships as a full
 * truckload (09 BR-08), so every load here is a plan's vehicle, except the history of the parcel shipping that was
 * removed. The load's own rules are {@code DispatchLoadTest}'s; a vehicle's signature is
 * {@code DispatchVehicleHandoverTest}'s.
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
    private final ParcelContents packageLines = mock(ParcelContents.class);
    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final CarrierJpaRepository carriers = mock(CarrierJpaRepository.class);
    private final ShipmentTimeline timeline = mock(ShipmentTimeline.class);
    private final ShipmentContext context = mock(ShipmentContext.class);
    private final OrderService orders = mock(OrderService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final PlanVehicles planVehicles = mock(PlanVehicles.class);
    private final LeftBehindParcels leftBehind = mock(LeftBehindParcels.class);
    private final com.stockflow.fulfillment.internal.repository.WaybillChangeJpaRepository waybillChanges =
            mock(com.stockflow.fulfillment.internal.repository.WaybillChangeJpaRepository.class);
    private final DispatchService service = new DispatchService(loads, members, loaded, store, numbers, shipments, locks,
            packages, packageLines, picks, carriers, timeline, context, orders, warehouses, identities, planVehicles,
            leftBehind, new Waybills(loads, waybillChanges, mock(jakarta.persistence.EntityManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS)), waybillChanges, mock(com.stockflow.fulfillment.internal.repository.CarrierBookingJpaRepository.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final CurrentUser actor = FulfillmentFixtures.user();
    private CarrierJpaEntity ghn;
    private DispatchLoad load;
    private DispatchLoadJpaEntity header;

    @BeforeEach
    void aCarrierAndAVehicleBeingLoaded() {
        ghn = new CarrierJpaEntity(UUID.randomUUID(), "GHN");
        ghn.apply("GHN", "GHN Express", null, null, "ACTIVE");
        when(carriers.findById(ghn.getId())).thenReturn(Optional.of(ghn));
        when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(
                new WarehouseView(WAREHOUSE, "HCM", "HCM warehouse", "1 Le Loi", true)));
        load = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-20261022-0001", UUID.randomUUID(), WAREHOUSE,
                UUID.randomUUID());
        load.book(ghn.getId(), "51C-123.45", "Driver Binh", "0900000009", actor.userId(), NOW.minusSeconds(900));
        load.startLoading(actor.userId(), NOW.minusSeconds(600));
        header = new DispatchLoadJpaEntity(load.snapshot());
        header.apply(load.snapshot());
        when(loads.lockById(load.id())).thenReturn(Optional.of(header));
        when(loads.findById(load.id())).thenReturn(Optional.of(header));
        when(store.load(header)).thenReturn(load);
        // The store writes the rows and copies the load's state onto its header, as DispatchLoadStore.save does.
        when(store.save(any(), any())).thenAnswer(call -> {
            DispatchLoadJpaEntity saved = call.getArgument(1);
            saved.apply(call.<DispatchLoad>getArgument(0).snapshot());
            return saved;
        });
        when(shipments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(shipments.findByIdIn(any())).thenReturn(List.of());
    }

    private ShipmentJpaEntity shipmentRow() {
        Shipment s = Shipment.ready(UUID.randomUUID(), "SO-" + (int) (Math.random() * 9000 + 1000) + "-S1",
                UUID.randomUUID(), UUID.randomUUID(), WAREHOUSE, UUID.randomUUID(), 1, null, actor.userId(),
                NOW.minusSeconds(3600));
        return FulfillmentFixtures.row(s);
    }

    private PackageJpaEntity parcelOf(ShipmentJpaEntity shipment, int no) {
        return FulfillmentFixtures.parcel(shipment.getId(), no, "40", "30", "25", "5");
    }

    private void shipmentsFound(ShipmentJpaEntity... rows) {
        when(shipments.findByIdIn(any())).thenReturn(List.of(rows));
        when(shipments.findAllById(any())).thenReturn(List.of(rows));
    }

    /** The shipment on the vehicle's manifest, as the plan's confirmation puts it there. */
    private void onManifest(ShipmentJpaEntity row) {
        shipmentsFound(row);
        load.addShipment(new DispatchLoad.ShipmentFacts(row.getId(), row.getCode(), WAREHOUSE,
                ShipmentStatus.READY_TO_DISPATCH, null), actor.userId(), NOW.minusSeconds(300));
    }

    private OrderSummary orderWithShipment(ShipmentJpaEntity row, boolean allowPartial, int quantity) {
        var shipment = new OrderSummary.Shipment(row.getId(), OrderShipmentStatus.HANDED_OVER, BigDecimal.ZERO, NOW, null, null, List.of(), null);
        var line = new OrderSummary.LineSummary(UUID.randomUUID(), "SKU-A", quantity, Money.vnd(10_000), Money.vnd(100_000),
                List.of());
        var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(shipment), allowPartial);
        return new OrderSummary(row.getOrderId(), "SO-0001", UUID.randomUUID(), OrderStatus.SHIPPED, Money.vnd(100_000),
                List.of(line), NOW, null, null, null, null, null, null, null, null, WAREHOUSE, NOW, null,
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
        @DisplayName("an unknown status filter is UNSUPPORTED_PARAMETER; another warehouse is OUT_OF_DATA_SCOPE")
        void listFilters() {
            assertThat(refusal(() -> service.list(null, "DONE", null, null, 0, 20)).errorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);
            WithCurrentUser.run(FulfillmentFixtures.userOf(WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.list(OTHER_WAREHOUSE, null, null, null, 0, 20)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a page of loads shows each one's carrier name, status and plate")
        void listRows() {
            when(loads.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                    any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(header),
                            org.springframework.data.domain.PageRequest.of(0, 20), 1));
            when(carriers.findAllById(any())).thenReturn(List.of(ghn));

            var page = service.list(WAREHOUSE, "loading", ghn.getId(), "LD-", 0, 20);

            assertThat(page.items()).singleElement().satisfies(row -> {
                assertThat(row.carrierName()).isEqualTo("GHN Express");
                assertThat(row.status()).isEqualTo("LOADING");
                assertThat(row.vehiclePlate()).isEqualTo("51C-123.45");
            });
        }

        @Test
        @DisplayName("the detail of an open load lists its shipments with their parcels and which are loaded")
        void detailOfAnOpenLoad() {
            ShipmentJpaEntity row = shipmentRow();
            PackageJpaEntity parcel = parcelOf(row, 1);
            onManifest(row);
            when(packages.findByShipmentIdIn(any())).thenReturn(List.of(parcel));

            DispatchLoadJpaEntity current = header;
            var detail = service.get(load.id());

            assertThat(current.getCode()).isEqualTo(detail.code());
            assertThat(detail.shipments()).singleElement().satisfies(s -> {
                assertThat(s.packageCount()).isEqualTo(1);
                assertThat(s.loadedCount()).isZero();
            });
            assertThat(detail.expectedPackageCount()).isEqualTo(1);
            assertThat(detail.carrierName()).isEqualTo("GHN Express");
        }
    }

    @Nested
    @DisplayName("scanning parcels onto the vehicle")
    class Scanning {

        private ShipmentJpaEntity row;
        private PackageJpaEntity parcel;

        @BeforeEach
        void aShipmentOnTheLoad() {
            row = shipmentRow();
            parcel = parcelOf(row, 1);
            onManifest(row);
            when(packages.findByLabelCode("PKG-1")).thenReturn(Optional.of(parcel));
            when(shipments.findById(row.getId())).thenReturn(Optional.of(row));
            when(planVehicles.placement(any(), any())).thenReturn(
                    new DispatchLoad.Placement(true, "LP-20261022-0001", 1, null));
        }

        @Test
        @DisplayName("a parcel the plan placed on the vehicle, of a shipment on the manifest, is loaded and the load saved")
        void loadsTheParcel() {
            var detail = service.scan(load.id(), UUID.randomUUID(), " PKG-1 ", actor);

            assertThat(detail.loadedPackageCount()).isEqualTo(1);
            assertThat(load.activeParcels()).extracting(DispatchLoad.Loaded::packageId).containsExactly(parcel.getId());
            verify(store).save(load, header);
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
    @DisplayName("a load of the parcel shipping that was removed (history)")
    class History {

        private DispatchLoad history(LoadStatus status) {
            boolean dispatched = status == LoadStatus.DISPATCHED;
            DispatchLoad old = DispatchLoad.restore(new DispatchLoad.Snapshot(UUID.randomUUID(), "LD-20261020-0001",
                    UUID.randomUUID(), WAREHOUSE, ghn.getId(), null, "51C-1", "An", null, status, NOW.minusSeconds(7200),
                    actor.userId(), dispatched ? NOW.minusSeconds(3600) : null, dispatched ? actor.userId() : null,
                    dispatched ? "Mr Hung" : null, null, null, dispatched ? null : NOW.minusSeconds(3600),
                    dispatched ? null : actor.userId(), dispatched ? null : "the driver did not come", 3, List.of(),
                    List.of()));
            DispatchLoadJpaEntity oldHeader = new DispatchLoadJpaEntity(old.snapshot());
            oldHeader.apply(old.snapshot());
            var key = mock(DispatchLoadJpaRepository.LoadKey.class);
            when(key.getWarehouseId()).thenReturn(WAREHOUSE);
            when(loads.findKeyById(old.id())).thenReturn(Optional.of(key));
            when(loads.lockById(old.id())).thenReturn(Optional.of(oldHeader));
            when(store.load(oldHeader)).thenReturn(old);
            return old;
        }

        @Test
        @DisplayName("dispatched, it answers as it is; cancelled, it is never handed over (INVALID_LOAD_TRANSITION); "
                + "nothing is told to any order")
        void neverHandedOverAgain() {
            DispatchLoad dispatched = history(LoadStatus.DISPATCHED);
            DispatchLoad cancelled = history(LoadStatus.CANCELLED);
            Dispatching.Handover signed = new Dispatching.Handover(3L, "Mr Hung", null, null, "sig-1", true, null);

            assertThat(service.handOver(dispatched.id(), signed, actor).status()).isEqualTo("DISPATCHED");
            assertThat(refusal(() -> service.handOver(cancelled.id(), signed, actor)).errorCode())
                    .isEqualTo(ErrorCode.INVALID_LOAD_TRANSITION);
            verify(planVehicles, never()).lock(any());
            verify(orders, never()).recordHandover(any(), any());
            verify(store, never()).save(any(), any());
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
        @DisplayName("lists each shipment with its recipient, parcels and weight")
        void printsTheManifest() {
            ShipmentJpaEntity row = shipmentRow();
            PackageJpaEntity parcel = parcelOf(row, 1);
            onManifest(row);
            when(packages.findByShipmentIdIn(any())).thenReturn(List.of(parcel));
            var address = new OrderSummary.AddressSummary(null, "0911", "12 Nguyen Hue", null, "W1", "Ben Nghe", "P1", "HCM",
                    "VN", "700000");
            OrderSummary base = orderWithShipment(row, true, 3);
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(new OrderSummary(row.getOrderId(), "SO-0042",
                    base.customerId(), OrderStatus.PACKED, base.total(), base.lines(), NOW, null, null, null, "Ms Lan", null,
                    "0900", address, null, WAREHOUSE, NOW, null, base.lifecycle())));

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
            });
        }
    }
}
