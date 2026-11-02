package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.LoadPlanStatus;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PackageType;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.VehicleTypeJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.LoadNumbers;
import com.stockflow.fulfillment.internal.repository.LoadPlanJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanNumbers;
import com.stockflow.fulfillment.internal.repository.LoadPlanPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanStore;
import com.stockflow.fulfillment.internal.repository.LoadPlanVehicleJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageLineJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentLocks;
import com.stockflow.fulfillment.internal.repository.VehicleTypeJpaRepository;
import com.stockflow.inventory.api.TransferDeliveryMode;
import com.stockflow.inventory.api.TransferFulfillment;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.OTHER_WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.TRUCK_TYPE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Load planning for full-truckload shipments (kltn-docs 09 §4.2, BR-08 to BR-15): choosing parcels and vehicle types,
 * calculating, the plan's lock at confirmation, reopening and cancelling with the dispatch loads that follow. The
 * placement algorithm is {@code LoadPlannerTest}'s and the plan's own rules are {@code LoadPlanTest}'s; this is the
 * orchestration, its guards and what is written when.
 */
class LoadPlanServiceTest {

    private final LoadPlanJpaRepository plans = mock(LoadPlanJpaRepository.class);
    private final LoadPlanPackageJpaRepository members = mock(LoadPlanPackageJpaRepository.class);
    private final LoadPlanVehicleJpaRepository planVehicles = mock(LoadPlanVehicleJpaRepository.class);
    private final LoadPlanStore store = mock(LoadPlanStore.class);
    private final LoadPlanNumbers numbers = mock(LoadPlanNumbers.class);
    private final LoadPlanParcels parcels = mock(LoadPlanParcels.class);
    private final PackageJpaRepository packages = mock(PackageJpaRepository.class);
    private final ShipmentJpaRepository shipments = mock(ShipmentJpaRepository.class);
    private final ShipmentLocks shipmentLocks = mock(ShipmentLocks.class);
    private final VehicleTypeJpaRepository vehicleTypes = mock(VehicleTypeJpaRepository.class);
    private final DispatchLoadJpaRepository loads = mock(DispatchLoadJpaRepository.class);
    private final DispatchLoadStore loadStore = mock(DispatchLoadStore.class);
    private final LoadNumbers loadNumbers = mock(LoadNumbers.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final LoadPlanEstimateComparison estimates = mock(LoadPlanEstimateComparison.class);
    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final PackageLineJpaRepository packageLines = mock(PackageLineJpaRepository.class);
    private final DispatchLoadShipmentJpaRepository loadMembers = mock(DispatchLoadShipmentJpaRepository.class);
    private final TransferFulfillment transfers = mock(TransferFulfillment.class);
    private final PackJpaRepository packs = mock(PackJpaRepository.class);
    private final LoadPlanService service = new LoadPlanService(plans, members, planVehicles, store, numbers, parcels,
            packages, shipments, shipmentLocks, vehicleTypes, loads, loadStore, loadNumbers, warehouses, estimates, picks,
            packageLines, loadMembers, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(2), transfers, packs);

    private final CurrentUser actor = FulfillmentFixtures.user();
    private FulfillmentFixtures.PlannedPlan planned;
    private LoadPlan plan;
    private LoadPlanJpaEntity header;
    private VehicleTypeJpaEntity truckType;

    @BeforeEach
    void aPlanReadFromItsRows() {
        planned = FulfillmentFixtures.plannedPlan(2, 1);
        // The store writes the rows and copies the plan's state onto its header, as LoadPlanStore does.
        when(store.save(any(), any(), any())).thenAnswer(call -> {
            LoadPlanJpaEntity saved = call.getArgument(1);
            saved.apply(call.<LoadPlan>getArgument(0).snapshot(), call.getArgument(2));
            return saved;
        });
        when(store.insert(any(), any())).thenAnswer(call -> {
            LoadPlan inserted = call.getArgument(0);
            LoadPlanJpaEntity created = new LoadPlanJpaEntity(inserted.snapshot());
            created.apply(inserted.snapshot(), NOW);
            return created;
        });
        useAsStoredPlan(planned.plan());
        truckType = new VehicleTypeJpaEntity(TRUCK_TYPE, "TRUCK-TEST");
        truckType.apply("Truck", "TRUCK", 2000, 1000, 1000, 0, null, null, new BigDecimal("1000"), 1, null, "ACTIVE");
        when(vehicleTypes.findAllById(any())).thenReturn(List.of(truckType));
        when(parcels.of(any())).thenAnswer(call -> parcelsOf(call.getArgument(0)));
        when(loads.findByLoadPlanVehicleIdIn(any())).thenReturn(List.of());
    }

    private void useAsStoredPlan(LoadPlan stored) {
        plan = stored;
        header = new LoadPlanJpaEntity(plan.snapshot());
        header.apply(plan.snapshot(), NOW);
        when(plans.lockById(plan.id())).thenReturn(Optional.of(header));
        when(plans.findById(plan.id())).thenReturn(Optional.of(header));
        when(store.load(header)).thenReturn(plan);
    }

    /** The parcels the plan's cartons stand for: sealed, 1 m cubes of 100 kg. */
    private Map<UUID, LoadPlanParcels.Parcel> parcelsOf(java.util.Collection<UUID> ids) {
        Map<UUID, LoadPlanParcels.Parcel> out = new LinkedHashMap<>();
        int no = 1;
        for (UUID id : ids) {
            PackageJpaEntity row = new PackageJpaEntity(id, UUID.randomUUID(), no, "PKG-" + no++);
            row.apply(planned.shipmentId(), PackageType.CARTON, PackageStatus.SEALED, new BigDecimal("100"),
                    new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"), null, true, false, false, false,
                    NOW, null, null, null);
            out.put(id, new LoadPlanParcels.Parcel(row, null, null, LoadPlanParcels.carton(row, "CUBE"), "CUBE"));
        }
        return out;
    }

    @Nested
    @DisplayName("the plan's lock and the version the dispatcher saw (09 BR-14)")
    class Locking {

        @Test
        @DisplayName("a plan that does not exist is LOAD_PLAN_NOT_FOUND")
        void unknown() {
            UUID unknown = UUID.randomUUID();
            when(plans.lockById(unknown)).thenReturn(Optional.empty());
            when(plans.findById(unknown)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.get(unknown)).errorCode()).isEqualTo(ErrorCode.LOAD_PLAN_NOT_FOUND);
            assertThat(refusal(() -> service.calculate(unknown, 0L, actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_PLAN_NOT_FOUND);
        }

        @Test
        @DisplayName("a plan of another warehouse is OUT_OF_DATA_SCOPE")
        void outOfScope() {
            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () -> {
                assertThat(refusal(() -> service.get(plan.id())).errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
                assertThat(refusal(() -> service.cancel(plan.id(), 0L, "x", actor)).errorCode())
                        .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
            });
        }

        @Test
        @DisplayName("a change without the version read, or with a stale one, is OPTIMISTIC_LOCK")
        void staleVersion() {
            assertThat(refusal(() -> service.confirm(plan.id(), null, actor)).errorCode())
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            assertThat(refusal(() -> service.reopen(plan.id(), 7L, "x", actor)).errorCode())
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            assertThat(refusal(() -> service.acknowledgeUnplaced(plan.id(), 7L, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            verify(store, never()).save(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("creating a plan (09 §4.2 step 1)")
    class Creating {

        private LoadPlans.CreatePlan request(String source, List<UUID> shipmentIds, List<UUID> typeIds,
                                             List<UUID> transferIds) {
            return new LoadPlans.CreatePlan(UUID.randomUUID(), WAREHOUSE, shipmentIds, typeIds, "SINGLE_TYPE", "note",
                    source, transferIds);
        }

        @BeforeEach
        void aWarehouse() {
            when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(
                    new WarehouseView(WAREHOUSE, "HCM", "HCM warehouse", "1 Le Loi", true)));
            when(numbers.next(any())).thenReturn("LP-20261022-0009");
        }

        @Test
        @DisplayName("the same request id again answers with that plan; for another warehouse it is CONFLICT")
        void replay() {
            UUID requestId = plan.snapshot().requestId();
            when(plans.findByRequestId(requestId)).thenReturn(Optional.of(header));

            var again = service.create(new LoadPlans.CreatePlan(requestId, WAREHOUSE, null, null, null, null), actor);

            assertThat(again.loadPlanId()).isEqualTo(plan.id());
            verify(store, never()).insert(any(), any());
            assertThat(refusal(() -> service.create(new LoadPlans.CreatePlan(requestId, OTHER_WAREHOUSE, null, null, null,
                    null), actor)).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("an unknown warehouse is WAREHOUSE_NOT_FOUND; another warehouse than the caller's is OUT_OF_DATA_SCOPE")
        void warehouse() {
            UUID unknown = UUID.randomUUID();
            when(warehouses.findWarehouse(unknown)).thenReturn(Optional.empty());
            assertThat(refusal(() -> service.create(new LoadPlans.CreatePlan(UUID.randomUUID(), unknown, null, null, null,
                    null), actor)).errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);

            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.create(request(null, null, null, null), actor)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a source type other than ORDER or TRANSFER, or a selection mode the planner does not know, is VALIDATION_FAILED")
        void sourceAndModeAreValidated() {
            var thrown = refusal(() -> service.create(request("PALLET", null, null, null), actor));
            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("sourceType");

            var badMode = refusal(() -> service.create(new LoadPlans.CreatePlan(UUID.randomUUID(), WAREHOUSE, null, null,
                    "CHEAPEST", null, null, null), actor));
            assertThat(badMode.fieldErrors()).extracting(f -> f.field()).containsExactly("selectionMode");
        }

        @Test
        @DisplayName("an empty plan is a DRAFT: no parcels, no types, saved as a new plan with the next number")
        void emptyDraft() {
            var detail = service.create(request(null, null, null, null), actor);

            assertThat(detail.status()).isEqualTo("DRAFT");
            assertThat(detail.code()).isEqualTo("LP-20261022-0009");
            assertThat(detail.sourceType()).isEqualTo("ORDER");
            verify(numbers).lockRequest(any());
            verify(store).insert(any(), eq(NOW));
        }

        @Test
        @DisplayName("an ORDER plan takes shipments only and a TRANSFER plan one transfer only: LOAD_PLAN_SOURCE_MISMATCH")
        void oneSourcePerPlan() {
            assertThat(refusal(() -> service.create(request("ORDER", null, null, List.of(UUID.randomUUID())), actor))
                    .errorCode()).isEqualTo(ErrorCode.LOAD_PLAN_SOURCE_MISMATCH);
            assertThat(refusal(() -> service.create(request("TRANSFER", List.of(UUID.randomUUID()), null, null), actor))
                    .errorCode()).isEqualTo(ErrorCode.LOAD_PLAN_SOURCE_MISMATCH);
        }

        private ShipmentJpaEntity fullTruckload(ShipmentStatus status, DeliveryMode mode, UUID warehouse) {
            var s = com.stockflow.fulfillment.internal.domain.Shipment.ready(UUID.randomUUID(), "SO-1-S1", UUID.randomUUID(),
                    UUID.randomUUID(), warehouse, null, mode, 2, BigDecimal.ZERO, null, actor.userId(), NOW);
            if (status == ShipmentStatus.CANCELLED) {
                s.cancel("x", actor.userId(), NOW);
            }
            return FulfillmentFixtures.row(s);
        }

        private List<PackageJpaEntity> sealedParcelsOf(ShipmentJpaEntity row, int count) {
            java.util.ArrayList<PackageJpaEntity> list = new java.util.ArrayList<>();
            for (int i = 1; i <= count; i++) {
                list.add(FulfillmentFixtures.parcel(row.getId(), i, "100", "100", "100", "100"));
            }
            when(packages.findByShipmentIdOrderByPackageNoAsc(row.getId())).thenReturn(list);
            return list;
        }

        @Test
        @DisplayName("a shipment that is not found, not waiting, or not a full truckload of this warehouse is refused")
        void shipmentGuards() {
            UUID unknown = UUID.randomUUID();
            when(shipmentLocks.lock(any())).thenReturn(List.of());
            assertThat(refusal(() -> service.create(request(null, List.of(unknown), List.of(TRUCK_TYPE), null), actor))
                    .errorCode()).isEqualTo(ErrorCode.SHIPMENT_NOT_FOUND);

            ShipmentJpaEntity cancelled = fullTruckload(ShipmentStatus.CANCELLED, DeliveryMode.FULL_TRUCKLOAD, WAREHOUSE);
            when(shipmentLocks.lock(any())).thenReturn(List.of(cancelled));
            assertThat(refusal(() -> service.create(request(null, List.of(cancelled.getId()), List.of(TRUCK_TYPE), null),
                    actor)).errorCode()).isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);

            ShipmentJpaEntity parcelMode = fullTruckload(ShipmentStatus.READY_TO_DISPATCH, DeliveryMode.PARCEL, WAREHOUSE);
            when(shipmentLocks.lock(any())).thenReturn(List.of(parcelMode));
            assertThat(refusal(() -> service.create(request(null, List.of(parcelMode.getId()), List.of(TRUCK_TYPE), null),
                    actor)).errorCode()).isEqualTo(ErrorCode.SHIPMENT_NOT_FOR_LOAD);

            ShipmentJpaEntity elsewhere = fullTruckload(ShipmentStatus.READY_TO_DISPATCH, DeliveryMode.FULL_TRUCKLOAD,
                    OTHER_WAREHOUSE);
            when(shipmentLocks.lock(any())).thenReturn(List.of(elsewhere));
            assertThat(refusal(() -> service.create(request(null, List.of(elsewhere.getId()), List.of(TRUCK_TYPE), null),
                    actor)).errorCode()).isEqualTo(ErrorCode.SHIPMENT_NOT_FOR_LOAD);
        }

        @Test
        @DisplayName("BR-09: a shipment with a parcel in another live plan is LOAD_PLAN_PACKAGE_TAKEN, naming that plan")
        void parcelHeldByAnotherPlan() {
            ShipmentJpaEntity row = fullTruckload(ShipmentStatus.READY_TO_DISPATCH, DeliveryMode.FULL_TRUCKLOAD, WAREHOUSE);
            when(shipmentLocks.lock(any())).thenReturn(List.of(row));
            List<PackageJpaEntity> sealed = sealedParcelsOf(row, 2);
            UUID otherPlan = UUID.randomUUID();
            LoadPlanPackageJpaEntity held = mock(LoadPlanPackageJpaEntity.class);
            when(held.getPackageId()).thenReturn(sealed.getFirst().getId());
            when(held.getLoadPlanId()).thenReturn(otherPlan);
            when(members.findByPackageIdInAndActiveTrue(any())).thenReturn(List.of(held));
            LoadPlanJpaEntity other = mock(LoadPlanJpaEntity.class);
            when(other.getCode()).thenReturn("LP-OTHER");
            when(plans.findById(otherPlan)).thenReturn(Optional.of(other));

            var thrown = refusal(() -> service.create(request(null, List.of(row.getId()), List.of(TRUCK_TYPE), null),
                    actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.LOAD_PLAN_PACKAGE_TAKEN);
            assertThat(thrown.getMessage()).contains("LP-OTHER");
        }

        @Test
        @DisplayName("BR-09: a parcel without sizes or weight is LOAD_PLAN_PACKAGE_UNMEASURED")
        void parcelNotMeasured() {
            ShipmentJpaEntity row = fullTruckload(ShipmentStatus.READY_TO_DISPATCH, DeliveryMode.FULL_TRUCKLOAD, WAREHOUSE);
            when(shipmentLocks.lock(any())).thenReturn(List.of(row));
            PackageJpaEntity unmeasured = FulfillmentFixtures.parcel(row.getId(), 1, null, null, null, null);
            when(packages.findByShipmentIdOrderByPackageNoAsc(row.getId())).thenReturn(List.of(unmeasured));
            when(parcels.describe(any())).thenReturn(Map.of(unmeasured.getId(), new LoadPlanParcels.Parcel(unmeasured, row,
                    null, null, "X")));

            assertThat(refusal(() -> service.create(request(null, List.of(row.getId()), List.of(TRUCK_TYPE), null), actor))
                    .errorCode()).isEqualTo(ErrorCode.LOAD_PLAN_PACKAGE_UNMEASURED);
        }

        @Test
        @DisplayName("a vehicle type that does not exist is VEHICLE_TYPE_NOT_FOUND; an INACTIVE one VEHICLE_TYPE_INACTIVE")
        void vehicleTypes() {
            ShipmentJpaEntity row = fullTruckload(ShipmentStatus.READY_TO_DISPATCH, DeliveryMode.FULL_TRUCKLOAD, WAREHOUSE);
            when(shipmentLocks.lock(any())).thenReturn(List.of(row));
            List<PackageJpaEntity> sealed = sealedParcelsOf(row, 1);
            when(parcels.describe(any())).thenReturn(Map.of(sealed.getFirst().getId(), new LoadPlanParcels.Parcel(
                    sealed.getFirst(), row, null, LoadPlanParcels.carton(sealed.getFirst(), "X"), "X")));
            UUID unknownType = UUID.randomUUID();
            when(vehicleTypes.findAllById(any())).thenReturn(List.of());
            assertThat(refusal(() -> service.create(request(null, List.of(row.getId()), List.of(unknownType), null), actor))
                    .errorCode()).isEqualTo(ErrorCode.VEHICLE_TYPE_NOT_FOUND);

            truckType.apply("Truck", "TRUCK", 2000, 1000, 1000, 0, null, null, new BigDecimal("1000"), 1, null, "INACTIVE");
            when(vehicleTypes.findAllById(any())).thenReturn(List.of(truckType));
            assertThat(refusal(() -> service.create(request(null, List.of(row.getId()), List.of(TRUCK_TYPE), null), actor))
                    .errorCode()).isEqualTo(ErrorCode.VEHICLE_TYPE_INACTIVE);
        }

        @Test
        @DisplayName("a valid plan takes the shipment's free parcels whole and the chosen types, locking the order's task first")
        void createsWithInputs() {
            ShipmentJpaEntity row = fullTruckload(ShipmentStatus.READY_TO_DISPATCH, DeliveryMode.FULL_TRUCKLOAD, WAREHOUSE);
            when(shipmentLocks.lock(any())).thenReturn(List.of(row));
            when(shipmentLocks.ordersOf(any())).thenReturn(List.of(row.getOrderId()));
            List<PackageJpaEntity> sealed = sealedParcelsOf(row, 2);
            Map<UUID, LoadPlanParcels.Parcel> described = new LinkedHashMap<>();
            sealed.forEach(p -> described.put(p.getId(), new LoadPlanParcels.Parcel(p, row, null,
                    LoadPlanParcels.carton(p, "CUBE"), "CUBE")));
            when(parcels.describe(any())).thenReturn(described);

            var detail = service.create(request(null, List.of(row.getId()), List.of(TRUCK_TYPE), null), actor);

            assertThat(detail.packageCount()).isEqualTo(2);
            assertThat(detail.status()).isEqualTo("DRAFT");
            verify(picks).lockByOrderId(row.getOrderId());
        }

        @Test
        @DisplayName("a unique-index violation on a parcel taken meanwhile is LOAD_PLAN_PACKAGE_TAKEN; the placement trigger SHIPMENT_STATE_CONFLICT; anything else passes through")
        void racesAreRefereedByTheDatabase() {
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException("x",
                    new RuntimeException("duplicate key value violates unique constraint \"uk_load_plan_package_active\""))).when(store).insert(any(), any());
            assertThat(refusal(() -> service.create(request(null, null, null, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_PLAN_PACKAGE_TAKEN);

            org.mockito.Mockito.doThrow(new DataIntegrityViolationException("x",
                    new RuntimeException("tg_load_plan_package_check failed"))).when(store).insert(any(), any());
            assertThat(refusal(() -> service.create(request(null, null, null, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);

            org.mockito.Mockito.doThrow(new DataIntegrityViolationException("x",
                    new RuntimeException("something else"))).when(store).insert(any(), any());
            assertThatThrownBy(() -> service.create(request(null, null, null, null), actor))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("a transfer plan carries one transfer order: two is VALIDATION_FAILED on 'transferOrderIds'")
        void oneTransferOnly() {
            var thrown = refusal(() -> service.create(request("TRANSFER", null, List.of(TRUCK_TYPE),
                    List.of(UUID.randomUUID(), UUID.randomUUID())), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("transferOrderIds");
        }

        private TransferFulfillment.TransferView transfer(UUID id, UUID from, String status, boolean packed) {
            return new TransferFulfillment.TransferView(id, "TO-0001", from, OTHER_WAREHOUSE, status,
                    TransferDeliveryMode.FULL_TRUCKLOAD, packed ? NOW : null, null, null, List.of());
        }

        @Test
        @DisplayName("a transfer that does not exist is TRANSFER_ORDER_NOT_FOUND; one leaving another warehouse LOAD_PLAN_SOURCE_MISMATCH; one not packed TRANSFER_NOT_PACKED")
        void transferGuards() {
            UUID id = UUID.randomUUID();
            when(transfers.find(id)).thenReturn(Optional.empty());
            assertThat(refusal(() -> service.create(request("TRANSFER", null, List.of(TRUCK_TYPE), List.of(id)), actor))
                    .errorCode()).isEqualTo(ErrorCode.TRANSFER_ORDER_NOT_FOUND);

            when(transfers.find(id)).thenReturn(Optional.of(transfer(id, OTHER_WAREHOUSE, "PICKING", true)));
            assertThat(refusal(() -> service.create(request("TRANSFER", null, List.of(TRUCK_TYPE), List.of(id)), actor))
                    .errorCode()).isEqualTo(ErrorCode.LOAD_PLAN_SOURCE_MISMATCH);

            when(transfers.find(id)).thenReturn(Optional.of(transfer(id, WAREHOUSE, "PICKING", false)));
            assertThat(refusal(() -> service.create(request("TRANSFER", null, List.of(TRUCK_TYPE), List.of(id)), actor))
                    .errorCode()).isEqualTo(ErrorCode.TRANSFER_NOT_PACKED);
            when(transfers.find(id)).thenReturn(Optional.of(transfer(id, WAREHOUSE, "APPROVED", true)));
            assertThat(refusal(() -> service.create(request("TRANSFER", null, List.of(TRUCK_TYPE), List.of(id)), actor))
                    .errorCode()).isEqualTo(ErrorCode.TRANSFER_NOT_PACKED);
        }

        @Test
        @DisplayName("a transfer without a free sealed parcel is LOAD_PLAN_PACKAGE_TAKEN")
        void transferWithoutParcels() {
            UUID id = UUID.randomUUID();
            when(transfers.find(id)).thenReturn(Optional.of(transfer(id, WAREHOUSE, "PICKING", true)));
            when(packages.findByTransferOrderIdIn(any())).thenReturn(List.of());

            assertThat(refusal(() -> service.create(request("TRANSFER", null, List.of(TRUCK_TYPE), List.of(id)), actor))
                    .errorCode()).isEqualTo(ErrorCode.LOAD_PLAN_PACKAGE_TAKEN);
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("an unknown status is VALIDATION_FAILED on 'status'; another warehouse OUT_OF_DATA_SCOPE")
        void filters() {
            var thrown = refusal(() -> service.list(null, "DONE", null, 0, 20));
            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("status");
            WithCurrentUser.run(FulfillmentFixtures.userOf(WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.list(OTHER_WAREHOUSE, null, null, 0, 20)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a row counts the shipments, parcels and unplaced parcels the plan holds now")
        void rows() {
            when(plans.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                    any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(header),
                            org.springframework.data.domain.PageRequest.of(0, 20), 1));
            UUID shipment = UUID.randomUUID();
            LoadPlanPackageJpaEntity placed = mock(LoadPlanPackageJpaEntity.class);
            when(placed.isActive()).thenReturn(true);
            when(placed.getShipmentId()).thenReturn(shipment);
            LoadPlanPackageJpaEntity unplaced = mock(LoadPlanPackageJpaEntity.class);
            when(unplaced.isActive()).thenReturn(true);
            when(unplaced.getShipmentId()).thenReturn(shipment);
            when(unplaced.getUnplacedReason()).thenReturn(com.stockflow.fulfillment.internal.domain.LoadPlanUnplacedReason
                    .NO_VEHICLE_AVAILABLE);
            LoadPlanPackageJpaEntity released = mock(LoadPlanPackageJpaEntity.class);
            when(members.findByLoadPlanIdOrderByAddedAtAscIdAsc(plan.id())).thenReturn(List.of(placed, unplaced, released));

            var page = service.list(WAREHOUSE, "planned", " LP ", 0, 20);

            assertThat(page.items()).singleElement().satisfies(row -> {
                assertThat(row.shipmentCount()).isEqualTo(1);
                assertThat(row.packageCount()).isEqualTo(2);
                assertThat(row.unplacedCount()).isEqualTo(1);
                assertThat(row.status()).isEqualTo("PLANNED");
            });
        }
    }

    @Nested
    @DisplayName("calculating (09 §4.2 step 2, BR-09, BR-12)")
    class Calculating {

        @Test
        @DisplayName("only a DRAFT or PLANNED plan is calculated")
        void statusGate() {
            planned.plan().acknowledgeUnplaced(planned.dispatcher(), NOW, null, planned.cartons());
            planned.plan().confirm(planned.dispatcher(), NOW, planned.cartons());

            assertThat(refusal(() -> service.calculate(plan.id(), 0L, actor)).errorCode())
                    .isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);
        }

        @Test
        @DisplayName("a plan with no parcel or no vehicle type is LOAD_PLAN_EMPTY")
        void needsParcelsAndTypes() {
            LoadPlan empty = LoadPlan.draft(UUID.randomUUID(), "LP-EMPTY", UUID.randomUUID(), WAREHOUSE,
                    com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode.SINGLE_TYPE, null);
            useAsStoredPlan(empty);

            assertThat(refusal(() -> service.calculate(empty.id(), 0L, actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_PLAN_EMPTY);
        }

        @Test
        @DisplayName("an unmeasured parcel blocks the calculation: LOAD_PLAN_PACKAGE_UNMEASURED")
        void parcelsMustBeMeasured() {
            org.mockito.Mockito.doAnswer(call -> {
                Map<UUID, LoadPlanParcels.Parcel> bad = new LinkedHashMap<>();
                for (UUID id : (java.util.Collection<UUID>) call.getArgument(0)) {
                    PackageJpaEntity row = new PackageJpaEntity(id, UUID.randomUUID(), 1, "PKG-X");
                    bad.put(id, new LoadPlanParcels.Parcel(row, null, null, null, "X"));
                }
                return bad;
            }).when(parcels).of(any());

            assertThat(refusal(() -> service.calculate(plan.id(), 0L, actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_PLAN_PACKAGE_UNMEASURED);
        }

        @Test
        @DisplayName("a vehicle type switched off since is VEHICLE_TYPE_INACTIVE")
        void typesMustBeActive() {
            truckType.apply("Truck", "TRUCK", 2000, 1000, 1000, 0, null, null, new BigDecimal("1000"), 1, null, "INACTIVE");

            assertThat(refusal(() -> service.calculate(plan.id(), 0L, actor)).errorCode())
                    .isEqualTo(ErrorCode.VEHICLE_TYPE_INACTIVE);
        }

        @Test
        @DisplayName("the planner places the parcels, the plan is PLANNED and the solver's figures are kept on the plan")
        void calculates() {
            var detail = service.calculate(plan.id(), 0L, actor);

            assertThat(detail.status()).isEqualTo("PLANNED");
            assertThat(detail.placedCount()).isEqualTo(2);
            assertThat(detail.unplacedCount()).isZero();
            assertThat(detail.vehicles()).singleElement().satisfies(v -> {
                assertThat(v.packageCount()).isEqualTo(2);
                assertThat(v.volumeFillPct()).isEqualByComparingTo("100.00");
                assertThat(v.usedWeightKg()).isEqualByComparingTo("200");
            });
            assertThat(header.getSolverMeta()).containsKeys("algorithm", "budgetExhausted", "elapsedMillis");
            verify(store).save(any(), eq(header), eq(NOW));
        }

        @Test
        @DisplayName("more parcels than the vehicles hold leave the rest unplaced, with its reason")
        void leavesTheRestOnTheTray() {
            planned = FulfillmentFixtures.plannedPlan(3, 1);
            useAsStoredPlan(planned.plan());

            var detail = service.calculate(plan.id(), 0L, actor);

            assertThat(detail.placedCount()).isEqualTo(2);
            assertThat(detail.unplacedCount()).isEqualTo(1);
            assertThat(detail.packages()).filteredOn(p -> p.unplacedReason() != null).singleElement()
                    .satisfies(p -> assertThat(p.unplacedReason()).isEqualTo("NO_VEHICLE_AVAILABLE"));
        }
    }

    @Nested
    @DisplayName("editing a vehicle (09 BR-11)")
    class Editing {

        @Test
        @DisplayName("an orientation the planner does not know is VALIDATION_FAILED, naming the box")
        void orientationIsValidated() {
            UUID box = planned.cartons().keySet().iterator().next();

            var thrown = refusal(() -> service.editPlacements(plan.id(), new LoadPlans.PlacementEdit(0L, List.of(
                    new LoadPlans.VehicleBoxes(1, List.of(new LoadPlans.BoxPosition(box, 0, 0, 0, 1000, 1000, 1000,
                            "XYZ")))), List.of()), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly(box.toString());
        }

        @Test
        @DisplayName("moving a box to the tray leaves it unplaced and the plan PLANNED")
        void movesABoxToTheTray() {
            List<UUID> ids = List.copyOf(planned.cartons().keySet());
            LoadPlan.Placed keep = plan.activeVehicles().getFirst().placements().stream()
                    .filter(p -> p.packageId().equals(ids.get(0))).findFirst().orElseThrow();

            var detail = service.editPlacements(plan.id(), new LoadPlans.PlacementEdit(0L, List.of(
                    new LoadPlans.VehicleBoxes(1, List.of(new LoadPlans.BoxPosition(keep.packageId(), keep.x(), keep.y(),
                            keep.z(), keep.w(), keep.h(), keep.d(), keep.orientation() == null ? null
                            : keep.orientation().name())))), List.of(ids.get(1))), actor);

            assertThat(detail.placedCount()).isEqualTo(1);
            assertThat(detail.unplacedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("only a PLANNED plan recalculates a vehicle")
        void recalculationNeedsAPlannedPlan() {
            planned.plan().acknowledgeUnplaced(planned.dispatcher(), NOW, null, planned.cartons());
            planned.plan().confirm(planned.dispatcher(), NOW, planned.cartons());

            assertThat(refusal(() -> service.recalculateVehicle(plan.id(), 1, 0L, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.INVALID_LOAD_PLAN_TRANSITION);
        }

        @Test
        @DisplayName("a parcel offered to a vehicle must come from the tray: 'LOAD_PLAN_PACKAGE_NOT_ON_TRAY'")
        void onlyTrayParcelsAreAdded() {
            UUID placedAlready = planned.cartons().keySet().iterator().next();

            var thrown = refusal(() -> service.recalculateVehicle(plan.id(), 1, 0L, List.of(placedAlready), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.code()).containsExactly("LOAD_PLAN_PACKAGE_NOT_ON_TRAY");
        }

        @Test
        @DisplayName("a vehicle that is not in the plan is refused")
        void unknownVehicle() {
            assertThatThrownBy(() -> service.recalculateVehicle(plan.id(), 9, 0L, null, actor))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("recalculating one vehicle keeps its parcels and saves the plan")
        void recalculatesTheVehicle() {
            var detail = service.recalculateVehicle(plan.id(), 1, 0L, null, actor);

            assertThat(detail.placedCount()).isEqualTo(2);
            verify(store).save(any(), eq(header), eq(NOW));
        }
    }

    @Nested
    @DisplayName("confirming (09 BR-13, BR-14) and the loads that follow")
    class Confirming {

        private ShipmentJpaEntity shipmentRow(ShipmentStatus status) {
            var s = com.stockflow.fulfillment.internal.domain.Shipment.ready(planned.shipmentId(), "SO-1-S1",
                    UUID.randomUUID(), UUID.randomUUID(), WAREHOUSE, null, DeliveryMode.FULL_TRUCKLOAD, 2, BigDecimal.ZERO,
                    null, actor.userId(), NOW);
            if (status == ShipmentStatus.CANCELLED) {
                s.cancel("x", actor.userId(), NOW);
            }
            return FulfillmentFixtures.row(s);
        }

        @Test
        @DisplayName("a plan with something unplaced must be acknowledged first: LOAD_PLAN_UNPLACED_NOT_ACKNOWLEDGED")
        void unplacedMustBeAcknowledged() {
            planned = FulfillmentFixtures.plannedPlan(3, 1);
            useAsStoredPlan(planned.plan());
            when(shipmentLocks.lock(any())).thenReturn(List.of(shipmentRow(ShipmentStatus.READY_TO_DISPATCH)));

            assertThat(refusal(() -> service.confirm(plan.id(), 0L, actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_PLAN_UNPLACED_NOT_ACKNOWLEDGED);
        }

        @Test
        @DisplayName("a shipment of the plan that no longer waits for dispatch is SHIPMENT_STATE_CONFLICT: take it out of the plan")
        void shipmentsMustStillWait() {
            when(shipmentLocks.lock(any())).thenReturn(List.of(shipmentRow(ShipmentStatus.CANCELLED)));

            assertThat(refusal(() -> service.confirm(plan.id(), 0L, actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
            verify(loadStore, never()).insert(any());
        }

        @Test
        @DisplayName("confirmed: the plan is locked and one PLANNED dispatch load per vehicle opens with its shipments on the manifest")
        void confirmOpensTheVehiclesLoads() {
            ShipmentJpaEntity row = shipmentRow(ShipmentStatus.READY_TO_DISPATCH);
            when(shipmentLocks.lock(any())).thenReturn(List.of(row));
            when(shipments.findAllById(any())).thenReturn(List.of(row));
            when(loadNumbers.next(any())).thenReturn("LD-20261022-0001");

            var detail = service.confirm(plan.id(), 0L, actor);

            assertThat(detail.status()).isEqualTo("CONFIRMED");
            ArgumentCaptor<DispatchLoad> opened = ArgumentCaptor.forClass(DispatchLoad.class);
            verify(loadStore).insert(opened.capture());
            assertThat(opened.getValue().status()).isEqualTo(LoadStatus.PLANNED);
            assertThat(opened.getValue().mode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
            assertThat(opened.getValue().activeShipments()).containsExactly(planned.shipmentId());
            assertThat(opened.getValue().loadPlanVehicleId()).isEqualTo(plan.activeVehicles().getFirst().id());
        }

        @Test
        @DisplayName("a shipment already on a parcel load cannot also be on a vehicle: SHIPMENT_ON_ANOTHER_LOAD")
        void shipmentOnAnotherLoad() {
            ShipmentJpaEntity row = shipmentRow(ShipmentStatus.READY_TO_DISPATCH);
            when(shipmentLocks.lock(any())).thenReturn(List.of(row));
            when(shipments.findAllById(any())).thenReturn(List.of(row));
            when(loadNumbers.next(any())).thenReturn("LD-20261022-0001");
            UUID otherLoad = UUID.randomUUID();
            var onLoad = new com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity(UUID.randomUUID(),
                    otherLoad, row.getId(), DeliveryMode.PARCEL);
            when(loadMembers.findByShipmentIdInAndActiveTrue(any())).thenReturn(List.of(onLoad));
            DispatchLoadJpaEntity otherHeader = mock(DispatchLoadJpaEntity.class);
            when(otherHeader.getId()).thenReturn(otherLoad);
            when(otherHeader.getCode()).thenReturn("LD-PARCEL");
            when(loads.findByIdIn(any())).thenReturn(List.of(otherHeader));

            var thrown = refusal(() -> service.confirm(plan.id(), 0L, actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SHIPMENT_ON_ANOTHER_LOAD);
            assertThat(thrown.getMessage()).contains("LD-PARCEL");
        }

        @Test
        @DisplayName("LP-4: an order that may not ship in parts, with parcels of it left on the tray, refuses the plan")
        void orderThatMayNotShipInParts() {
            planned = FulfillmentFixtures.plannedPlan(3, 1);
            useAsStoredPlan(planned.plan());
            planned.plan().acknowledgeUnplaced(planned.dispatcher(), NOW, null, planned.cartons());
            OrderSummary order = FulfillmentFixtures.order(UUID.randomUUID(), List.of());
            var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, false, true);
            OrderSummary strict = new OrderSummary(order.orderId(), "SO-0777", order.customerId(), OrderStatus.PACKED,
                    order.total(), order.lines(), NOW, null, null, null, null, null, null, null, null, null, WAREHOUSE, NOW,
                    null, null, lifecycle);
            org.mockito.Mockito.doAnswer(call -> {
                Map<UUID, LoadPlanParcels.Parcel> described = new LinkedHashMap<>();
                parcelsOf(call.getArgument(0)).forEach((id, p) -> described.put(id, new LoadPlanParcels.Parcel(p.row(),
                        null, strict, p.carton(), p.partName())));
                return described;
            }).when(parcels).of(any());

            var thrown = refusal(() -> service.confirm(plan.id(), 0L, actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.message()).containsExactly("SO-0777");
        }

        @Test
        @DisplayName("acknowledging what stays behind records it; the same acknowledgement again changes nothing")
        void acknowledges() {
            planned = FulfillmentFixtures.plannedPlan(3, 1);
            useAsStoredPlan(planned.plan());

            var detail = service.acknowledgeUnplaced(plan.id(), 0L, "next truck", actor);

            assertThat(detail.unplacedAcknowledgement()).isNotNull();
            assertThat(detail.unplacedAcknowledgement().note()).isEqualTo("next truck");
        }
    }

    @Nested
    @DisplayName("reopening and cancelling (09 BR-14, LP-11)")
    class ReopeningAndCancelling {

        private DispatchLoadJpaEntity vehicleLoad(LoadStatus status) {
            DispatchLoadJpaEntity load = mock(DispatchLoadJpaEntity.class);
            when(load.getStatus()).thenReturn(status);
            when(load.getId()).thenReturn(UUID.randomUUID());
            when(load.getLoadPlanVehicleId()).thenReturn(plan.vehicles().getFirst().id());
            return load;
        }

        private void confirmed() {
            planned.plan().confirm(planned.dispatcher(), NOW, planned.cartons());
        }

        @Test
        @DisplayName("reopening a confirmed plan sends it back to PLANNED and cancels its vehicles' loads that are not loading")
        void reopensAndCancelsTheLoads() {
            confirmed();
            DispatchLoadJpaEntity loadHeader = vehicleLoad(LoadStatus.VEHICLE_BOOKED);
            when(loads.findByLoadPlanVehicleIdIn(any())).thenReturn(List.of(loadHeader));
            DispatchLoad load = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-V1", UUID.randomUUID(), WAREHOUSE,
                    plan.vehicles().getFirst().id());
            load.book(UUID.randomUUID(), "51C", null, null, actor.userId(), NOW);
            when(loads.lockById(loadHeader.getId())).thenReturn(Optional.of(loadHeader));
            when(loadStore.load(loadHeader)).thenReturn(load);

            var detail = service.reopen(plan.id(), 0L, " truck came smaller ", actor);

            assertThat(detail.status()).isEqualTo("PLANNED");
            assertThat(load.status()).isEqualTo(LoadStatus.CANCELLED);
            verify(loadStore).save(load, loadHeader);
        }

        @Test
        @DisplayName("BR-14: once a vehicle is loading the plan is not reopened")
        void noReopenAfterLoadingStarted() {
            confirmed();
            DispatchLoadJpaEntity loading = vehicleLoad(LoadStatus.LOADING);
            when(loads.findByLoadPlanVehicleIdIn(any())).thenReturn(List.of(loading));

            assertThat(refusal(() -> service.reopen(plan.id(), 0L, "x", actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_PLAN_LOADING_STARTED);
            verify(loadStore, never()).save(any(), any());
        }

        @Test
        @DisplayName("a plan is cancelled with its reason and its not-yet-loading loads; cancelling again changes nothing")
        void cancels() {
            DispatchLoadJpaEntity loadHeader = vehicleLoad(LoadStatus.PLANNED);
            when(loads.findByLoadPlanVehicleIdIn(any())).thenReturn(List.of(loadHeader));
            DispatchLoad load = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-V1", UUID.randomUUID(), WAREHOUSE,
                    plan.vehicles().getFirst().id());
            when(loads.lockById(loadHeader.getId())).thenReturn(Optional.of(loadHeader));
            when(loadStore.load(loadHeader)).thenReturn(load);

            var detail = service.cancel(plan.id(), 0L, "customer cancelled", actor);

            assertThat(detail.status()).isEqualTo("CANCELLED");
            assertThat(detail.cancelReason()).isEqualTo("customer cancelled");
            assertThat(load.status()).isEqualTo(LoadStatus.CANCELLED);

            org.mockito.Mockito.clearInvocations(store);
            service.cancel(plan.id(), header.getVersion(), "again", actor);
            verify(store, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("today a plan with a vehicle already loading is refused cancellation outright: LOAD_PLAN_LOADING_STARTED")
        void noCancelAfterLoadingStarted() {
            confirmed();
            DispatchLoadJpaEntity left = vehicleLoad(LoadStatus.DISPATCHED);
            when(loads.findByLoadPlanVehicleIdIn(any())).thenReturn(List.of(left));

            assertThat(refusal(() -> service.cancel(plan.id(), 0L, "x", actor)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_PLAN_LOADING_STARTED);
        }

        @Test
        @Disabled("Possible bug: a plan whose vehicle is loading cannot be cancelled at all, although the controlled "
                + "unloading back to the DISPATCH area is allowed (kltn-docs 09 §6 BR-14; LoadPlan.java cancel, "
                + "requireNotLoading); see PR body")
        @DisplayName("BR-14: once loading started the plan is cancelled in a controlled way, parcels unloaded back to DISPATCH")
        void cancelAfterLoadingStartedIsControlled() {
            confirmed();
            DispatchLoadJpaEntity loading = vehicleLoad(LoadStatus.LOADING);
            when(loads.findByLoadPlanVehicleIdIn(any())).thenReturn(List.of(loading));

            assertThat(service.cancel(plan.id(), 0L, "truck broke down", actor).status()).isEqualTo("CANCELLED");
        }
    }

    @Nested
    @DisplayName("an order or a transfer cancelled out of a plan, and parcels a plan holds")
    class Releases {

        @Test
        @DisplayName("a parcel a live plan holds is PACKAGE_IN_LOAD_PLAN, naming the plan; none held, nothing happens")
        void requireNotPlanned() {
            UUID parcel = UUID.randomUUID();
            service.requireNotPlanned(List.of());
            when(members.findByPackageIdInAndActiveTrue(List.of(parcel))).thenReturn(List.of());
            service.requireNotPlanned(List.of(parcel));

            LoadPlanPackageJpaEntity held = mock(LoadPlanPackageJpaEntity.class);
            when(held.getLoadPlanId()).thenReturn(plan.id());
            when(members.findByPackageIdInAndActiveTrue(List.of(parcel))).thenReturn(List.of(held));

            var thrown = refusal(() -> service.requireNotPlanned(List.of(parcel)));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.PACKAGE_IN_LOAD_PLAN);
            assertThat(thrown.getMessage()).contains(plan.code());
        }

        @Test
        @DisplayName("an order cancelled out of a plan drops its parcels; a plan left with a PLANNED state cancels its vehicles' loads")
        void releaseShipment() {
            planned.plan().acknowledgeUnplaced(planned.dispatcher(), NOW, null, planned.cartons());
            planned.plan().confirm(planned.dispatcher(), NOW, planned.cartons());
            LoadPlanPackageJpaEntity held = mock(LoadPlanPackageJpaEntity.class);
            when(held.getLoadPlanId()).thenReturn(plan.id());
            when(members.findByShipmentIdAndActiveTrue(planned.shipmentId())).thenReturn(List.of(held, held));
            DispatchLoadJpaEntity loadHeader = mock(DispatchLoadJpaEntity.class);
            when(loadHeader.getStatus()).thenReturn(LoadStatus.PLANNED);
            when(loadHeader.getId()).thenReturn(UUID.randomUUID());
            when(loads.findByLoadPlanVehicleIdIn(any())).thenReturn(List.of(loadHeader));
            DispatchLoad load = DispatchLoad.planFullTruckload(UUID.randomUUID(), "LD-V1", UUID.randomUUID(), WAREHOUSE,
                    plan.vehicles().getFirst().id());
            when(loads.lockById(loadHeader.getId())).thenReturn(Optional.of(loadHeader));
            when(loadStore.load(loadHeader)).thenReturn(load);

            service.releaseShipment(planned.shipmentId(), NOW);

            assertThat(plan.status()).isEqualTo(LoadPlanStatus.PLANNED);
            assertThat(plan.activePackageIds()).isEmpty();
            assertThat(load.status()).isEqualTo(LoadStatus.CANCELLED);
            verify(store, org.mockito.Mockito.times(1)).save(eq(plan), eq(header), eq(NOW));
        }

        @Test
        @DisplayName("a shipment in no plan releases nothing")
        void releaseShipmentInNoPlan() {
            when(members.findByShipmentIdAndActiveTrue(any())).thenReturn(List.of());

            service.releaseShipment(UUID.randomUUID(), NOW);

            verifyNoInteractions(store);
        }

        @Test
        @DisplayName("a transfer whose picking was cancelled cancels the plan that holds it, with its loads")
        void releaseTransfer() {
            UUID transferId = UUID.randomUUID();
            LoadPlanPackageJpaEntity held = mock(LoadPlanPackageJpaEntity.class);
            when(held.getLoadPlanId()).thenReturn(plan.id());
            when(members.findByTransferOrderIdAndActiveTrue(transferId)).thenReturn(List.of(held));

            service.releaseTransfer(transferId, "picking cancelled", NOW);

            assertThat(plan.status()).isEqualTo(LoadPlanStatus.CANCELLED);
            verify(store).save(eq(plan), eq(header), eq(NOW));
        }
    }

    @Nested
    @DisplayName("the piece check (reconciliation)")
    class Reconciling {

        @Test
        @DisplayName("parcels in = placed + unplaced, counted per shipment and per order, and balanced")
        void balanced() {
            PackageJpaEntity a = FulfillmentFixtures.parcel(planned.shipmentId(), 1, "100", "100", "100", "100");
            ShipmentJpaEntity row = ShipmentJpaEntity.of(com.stockflow.fulfillment.internal.domain.Shipment.ready(
                    planned.shipmentId(), "SO-1-S1", UUID.randomUUID(), null, WAREHOUSE, null,
                    DeliveryMode.FULL_TRUCKLOAD, 2, BigDecimal.ZERO, null, actor.userId(), NOW));
            when(shipments.findAllById(any())).thenReturn(List.of(row));
            when(parcels.ordersOf(any())).thenReturn(Map.of());
            when(packages.findByShipmentIdIn(any())).thenReturn(List.of(a));

            var reconciliation = service.reconciliation(plan.id());

            assertThat(reconciliation.pieceCheck().packagesIn()).isEqualTo(2);
            assertThat(reconciliation.pieceCheck().placed()).isEqualTo(2);
            assertThat(reconciliation.pieceCheck().unplaced()).isZero();
            assertThat(reconciliation.pieceCheck().balanced()).isTrue();
            assertThat(reconciliation.shipments()).singleElement().satisfies(s -> assertThat(s.code()).isEqualTo("SO-1-S1"));
        }
    }

    @Test
    @DisplayName("a transfer is a manifest line waiting for dispatch while it is packed and picking; a cancelled picking and a transfer past it are not")
    void transferAsAManifestLine() {
        assertThat(LoadPlanService.transferFacts(new TransferFulfillment.TransferView(UUID.randomUUID(), "TO-1", WAREHOUSE,
                OTHER_WAREHOUSE, "PICKING", TransferDeliveryMode.FULL_TRUCKLOAD, NOW, null, null, List.of())).status())
                .isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
        assertThat(LoadPlanService.transferFacts(new TransferFulfillment.TransferView(UUID.randomUUID(), "TO-1", WAREHOUSE,
                OTHER_WAREHOUSE, "PICKING", TransferDeliveryMode.FULL_TRUCKLOAD, null, null, null, List.of())).status())
                .isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(LoadPlanService.transferFacts(new TransferFulfillment.TransferView(UUID.randomUUID(), "TO-1", WAREHOUSE,
                OTHER_WAREHOUSE, "IN_TRANSIT", TransferDeliveryMode.FULL_TRUCKLOAD, NOW, null, null, List.of())).status())
                .isEqualTo(ShipmentStatus.HANDED_OVER);
    }
}
