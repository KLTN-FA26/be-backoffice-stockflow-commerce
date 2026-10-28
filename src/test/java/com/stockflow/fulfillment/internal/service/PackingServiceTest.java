package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.audit.AuditEntry;
import com.stockflow.common.audit.AuditTrail;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.DesignVerification;
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.DesignVerificationResult;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PackageType;
import com.stockflow.fulfillment.internal.domain.PackingSession;
import com.stockflow.fulfillment.internal.domain.PickLineStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.DesignVerificationJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.DesignVerificationJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageScanJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackingSessionStore;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickLineJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderHoldState;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.OTHER_WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The packing station (kltn-docs 08, 09 BR-04): who may work a session, that packing follows the order's status, the
 * label and its COD, completing packing into a shipment (full or partial), the design check of printed goods
 * (BR-DSG-003, 08 BR-02) and the cancellation of an order (08 BR-05). The parcel rules are {@code PackingSessionTest}'s.
 */
class PackingServiceTest {

    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final PickLineJpaRepository pickLines = mock(PickLineJpaRepository.class);
    private final PackJpaRepository packs = mock(PackJpaRepository.class);
    private final PackageJpaRepository packages = mock(PackageJpaRepository.class);
    private final ShipmentJpaRepository shipments = mock(ShipmentJpaRepository.class);
    private final DesignVerificationJpaRepository verifications = mock(DesignVerificationJpaRepository.class);
    private final PackingSessionStore store = mock(PackingSessionStore.class);
    private final PackageScanJpaRepository scans = mock(PackageScanJpaRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final InventoryControlService itemControls = mock(InventoryControlService.class);
    private final DesignService designs = mock(DesignService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final ShipmentTimeline timeline = mock(ShipmentTimeline.class);
    private final DispatchLoadShipmentJpaRepository loadMembers = mock(DispatchLoadShipmentJpaRepository.class);
    private final DispatchLoadJpaRepository loads = mock(DispatchLoadJpaRepository.class);
    private final DispatchLoadStore loadStore = mock(DispatchLoadStore.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final LoadPlanReleases loadPlans = mock(LoadPlanReleases.class);
    private final PlanVehicles planVehicles = mock(PlanVehicles.class);
    private final PackingService service = new PackingService(picks, pickLines, packs, packages, shipments,
            verifications, store, scans, orders, inventory, itemControls, designs, identities, warehouses, timeline,
            loadMembers, loads, loadStore, Clock.fixed(NOW, ZoneOffset.UTC), 6000, audit, loadPlans, planVehicles,
            mock(PickListService.class));

    private final UUID packer = UUID.randomUUID();
    private final CurrentUser actor = new CurrentUser(packer, "packer", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
            DataScope.ALL, Set.of(), Set.of());
    private final CurrentUser manager = new CurrentUser(UUID.randomUUID(), "manager", Set.of(Role.WAREHOUSE_MANAGER),
            Set.of(PermissionCode.of(FulfillmentPermissions.PACKING, Action.APPROVE)), DataScope.ALL, Set.of(), Set.of());

    private final UUID orderId = UUID.randomUUID();
    private final UUID lineId = UUID.randomUUID();
    private PickJpaEntity pick;
    private PackJpaEntity pack;
    private PackingSession session;
    private List<PackingSession.Line> lastFacts;

    @BeforeEach
    void aPickedTaskAtThePackingTable() {
        pick = new PickJpaEntity(UUID.randomUUID(), null, orderId, "SO-0001", WAREHOUSE, PickStatus.PENDING);
        pick.assign(packer, NOW.minusSeconds(300));
        pick.start(packer, NOW.minusSeconds(240));
        pick.complete(packer, NOW.minusSeconds(120));
        pack = new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.PENDING, null);
        session = sessionOf(PackStatus.PENDING, null, 10, 10);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(picks.findById(pick.getId())).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(pack));
        when(orders.findById(orderId)).thenReturn(Optional.of(order(OrderStatus.PICKING, false, true, null)));
        when(store.load(any(), anyString(), anyList())).thenAnswer(call -> {
            lastFacts = call.getArgument(2);
            return session;
        });
        when(itemControls.logistics(any())).thenAnswer(call -> new InventoryItemLogistics("SKU-A", 0, new ItemLogistics(
                "PCS", "8930000000017", null, null, null, null, null, null, null, null, 1, 1, StorageClass.NORMAL, false,
                null, false, true)));
        when(shipments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(shipments.findByPackIdOrderByReadyAtAscIdAsc(any())).thenReturn(List.of());
        when(designs.verifyForFulfillment(any(), any())).thenAnswer(call -> new DesignVerification(call.getArgument(0),
                call.getArgument(1), call.getArgument(1), true));
    }

    @AfterEach
    void noLeftoverSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private PackingSession sessionOf(PackStatus status, UUID startedBy, int wanted, int available) {
        var line = new PackingSession.Line(lineId, "SKU-A", "8930000000017", wanted, available, false, null, Map.of(),
                Set.of(), true, false, 1);
        return new PackingSession(pack.getId(), "SO-0001", status, startedBy == null ? null : NOW, startedBy, null,
                null, List.of(line), List.of(), List.of());
    }

    private OrderSummary order(OrderStatus status, boolean allowPartial, boolean printReady, HoldReason hold) {
        var line = new OrderSummary.LineSummary(lineId, "SKU-A", 10, Money.vnd(10_000), Money.vnd(100_000), List.of());
        var lifecycle = new OrderSummary.Lifecycle(hold, null, null, null, null, null, null, null, null, BigDecimal.ZERO,
                new BigDecimal("100000"), new BigDecimal("100000"), BigDecimal.ZERO, List.of(), List.of(), BigDecimal.ZERO,
                BigDecimal.ZERO, allowPartial, printReady);
        return new OrderSummary(orderId, "SO-0001", UUID.randomUUID(), status, Money.vnd(100_000), List.of(line), NOW,
                null, null, null, "Ms Lan", null, "0900000001", null, null, null, WAREHOUSE, NOW, null, null, lifecycle);
    }

    private void orderIs(OrderStatus status, boolean allowPartial, HoldReason hold) {
        when(orders.findById(orderId)).thenReturn(Optional.of(order(status, allowPartial, true, hold)));
    }

    private void packing(UUID by) {
        session = sessionOf(PackStatus.PACKING, by, 10, 10);
        pack.apply(PackStatus.PACKING, NOW, by, null, null);
    }

    private PackingSession.Parcel sealedParcel(int units) {
        PackingSession.Parcel parcel = session.openPackage(UUID.randomUUID(), PackageType.CARTON, NOW);
        session.scan(UUID.randomUUID(), parcel.id(), "SKU-A", null, null, units, null, packer, NOW);
        session.seal(parcel.id(), new PackingSession.Measures(new BigDecimal("8"), new BigDecimal("40"),
                new BigDecimal("30"), new BigDecimal("25")), null, null, null, 6000, packer, NOW);
        return parcel;
    }

    @Nested
    @DisplayName("who may work and when (08 step 1, §5)")
    class Access {

        @Test
        @DisplayName("a task whose picking is not complete is PACKING_NOT_READY")
        void pickingMustBeDone() {
            PickJpaEntity pending = new PickJpaEntity(pick.getId(), null, orderId, "SO-0001", WAREHOUSE, PickStatus.PICKING);
            when(picks.lockById(pick.getId())).thenReturn(Optional.of(pending));

            assertThat(refusal(() -> service.start(pick.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_NOT_READY);
        }

        @Test
        @DisplayName("a task cancelled with its order is PICK_TASK_STATE_CONFLICT")
        void cancelledTask() {
            pick.cancel();

            assertThat(refusal(() -> service.start(pick.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a task that does not exist is NOT_FOUND; one of another warehouse is OUT_OF_DATA_SCOPE")
        void unknownAndOutOfScope() {
            assertThat(refusal(() -> service.start(UUID.randomUUID(), actor)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.start(pick.getId(), actor)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a task with no pack row is NOT_FOUND")
        void noPack() {
            when(packs.findByPickId(pick.getId())).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.start(pick.getId(), actor)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a cancelled order closes the task: PICK_TASK_STATE_CONFLICT")
        void cancelledOrderClosesTheTask() {
            orderIs(OrderStatus.CANCELLED, false, null);

            assertThat(refusal(() -> service.start(pick.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("an order on hold cannot be packed, and the refusal names the hold")
        void heldOrder() {
            orderIs(OrderStatus.ON_HOLD, false, HoldReason.PAYMENT);

            var thrown = refusal(() -> service.start(pick.getId(), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
            assertThat(thrown.getMessage()).contains("ON_HOLD").contains("PAYMENT");
        }

        @Test
        @DisplayName("an order still printed is packed ahead of its prints only when Sales allowed partial shipments")
        void packEarlyNeedsSalesApproval() {
            orderIs(OrderStatus.IN_PRODUCTION, false, null);
            assertThat(refusal(() -> service.start(pick.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_PARTIAL_NOT_ALLOWED);

            orderIs(OrderStatus.IN_PRODUCTION, true, null);
            assertThat(service.start(pick.getId(), actor).packStatus()).isEqualTo("PACKING");
        }

        @Test
        @DisplayName("starting takes the session for the packer and saves it; starting again changes nothing")
        void startIsIdempotent() {
            Packing.Session started = service.start(pick.getId(), actor);

            assertThat(started.packStatus()).isEqualTo("PACKING");
            assertThat(started.packerId()).isEqualTo(packer);
            verify(store).save(session, pack);

            org.mockito.Mockito.clearInvocations(store);
            service.start(pick.getId(), actor);
            verify(store, never()).save(any(), any());
        }

        @Test
        @DisplayName("another packer is PACKING_TAKEN; a warehouse manager may take over")
        void anotherPackerIsRefused() {
            packing(UUID.randomUUID());

            assertThat(refusal(() -> service.start(pick.getId(), actor)).errorCode()).isEqualTo(ErrorCode.PACKING_TAKEN);
            assertThat(service.start(pick.getId(), manager).packStatus()).isEqualTo("PACKING");
        }

        @Test
        @DisplayName("the session shows what is wanted, what reached packing, what is packed and the payment due first")
        void sessionView() {
            when(orders.findById(orderId)).thenReturn(Optional.of(orderWithPaymentDue("700")));

            Packing.Session view = service.session(pick.getId(), actor);

            assertThat(view.paymentDue().amountDueBeforeHandover()).isEqualByComparingTo("700");
            assertThat(view.paymentDue().currency()).isEqualTo("VND");
            assertThat(view.lines()).singleElement().satisfies(l -> {
                assertThat(l.wanted()).isEqualTo(10);
                assertThat(l.available()).isEqualTo(10);
                assertThat(l.remaining()).isEqualTo(10);
            });
        }

        private OrderSummary orderWithPaymentDue(String due) {
            var line = new OrderSummary.LineSummary(lineId, "SKU-A", 10, Money.vnd(10_000), Money.vnd(100_000), List.of());
            var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                    BigDecimal.ZERO, new BigDecimal("100000"), new BigDecimal("100000"), new BigDecimal(due), List.of(),
                    List.of());
            return new OrderSummary(orderId, "SO-0001", UUID.randomUUID(), OrderStatus.PICKING, Money.vnd(100_000),
                    List.of(line), NOW, null, null, null, null, null, null, null, null, null, WAREHOUSE, NOW, null, null,
                    lifecycle);
        }

        @Test
        @DisplayName("no money due before the handover shows no warning")
        void noPaymentDueNoWarning() {
            assertThat(service.session(pick.getId(), actor).paymentDue()).isNull();
        }
    }

    @Nested
    @DisplayName("working the parcels")
    class Parcels {

        @BeforeEach
        void started() {
            packing(packer);
        }

        @Test
        @DisplayName("opening a parcel locks the order's delivery mode (LP-1) and saves the session")
        void openingLocksTheDeliveryMode() {
            Packing.Parcel parcel = service.openPackage(pick.getId(), "bag", actor);

            assertThat(parcel.packageType()).isEqualTo("BAG");
            assertThat(parcel.labelCode()).isEqualTo("PKG-SO-0001-1");
            verify(orders).recordPackingStarted(orderId);
            verify(store).save(session, pack);
        }

        @Test
        @DisplayName("an unknown package type is VALIDATION_FAILED on 'packageType'")
        void unknownType() {
            var thrown = refusal(() -> service.openPackage(pick.getId(), "TRUNK", actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("packageType");
        }

        @Test
        @DisplayName("a session not in progress cannot change its parcels")
        void sessionMustBeInProgress() {
            session = sessionOf(PackStatus.PENDING, null, 10, 10);

            assertThat(refusal(() -> service.openPackage(pick.getId(), null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
            verify(orders, never()).recordPackingStarted(any());
        }

        @Test
        @DisplayName("another packer's session is PACKING_TAKEN")
        void anotherPackersSession() {
            packing(UUID.randomUUID());

            assertThat(refusal(() -> service.openPackage(pick.getId(), null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_TAKEN);
        }

        @Test
        @DisplayName("scanning, sealing and removing units go through the session; an over-scan is refused")
        void scanSealRemove() {
            UUID parcelId = service.openPackage(pick.getId(), null, actor).packageId();

            Packing.Session scanned = service.scan(pick.getId(), parcelId,
                    new Packing.Scan(UUID.randomUUID(), "SKU-A", null, null, 6, null), actor);
            assertThat(scanned.lines().getFirst().packed()).isEqualTo(6);
            assertThat(refusal(() -> service.scan(pick.getId(), parcelId,
                    new Packing.Scan(UUID.randomUUID(), "SKU-A", null, null, 5, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_QUANTITY_EXCEEDS_ORDER);

            Packing.Session removed = service.removeUnits(pick.getId(), parcelId, lineId, null, null, 2, null, actor);
            assertThat(removed.lines().getFirst().packed()).isEqualTo(4);

            Packing.Parcel sealed = service.seal(pick.getId(), parcelId, new Packing.Seal(new BigDecimal("8"),
                    new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("25"), null, null, null), actor);
            assertThat(sealed.status()).isEqualTo("SEALED");
            assertThat(sealed.dimWeightKg()).isEqualByComparingTo("5.000");
        }

        @Test
        @DisplayName("a scan id already recorded for another package is PACK_SCAN_ID_CONFLICT, before anything else is checked")
        void scanIdOfAnotherPackage() {
            UUID parcelId = service.openPackage(pick.getId(), null, actor).packageId();
            UUID scanId = UUID.randomUUID();
            var recorded = mock(com.stockflow.fulfillment.internal.entity.PackageScanJpaEntity.class);
            when(recorded.getPackageId()).thenReturn(UUID.randomUUID());
            when(scans.findById(scanId)).thenReturn(Optional.of(recorded));

            assertThat(refusal(() -> service.scan(pick.getId(), parcelId,
                    new Packing.Scan(scanId, "SKU-A", null, null, 1, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_SCAN_ID_CONFLICT);
        }

        @Test
        @DisplayName("the same scan id on the same package is a replay: the session is answered as it is, nothing scanned again")
        void scanReplay() {
            UUID parcelId = service.openPackage(pick.getId(), null, actor).packageId();
            UUID scanId = UUID.randomUUID();
            var recorded = mock(com.stockflow.fulfillment.internal.entity.PackageScanJpaEntity.class);
            when(recorded.getPackageId()).thenReturn(parcelId);
            when(scans.findById(scanId)).thenReturn(Optional.of(recorded));
            when(packages.findById(parcelId)).thenReturn(Optional.of(new PackageJpaEntity(parcelId, pack.getId(), 1, "PKG-1")));

            Packing.Session replay = service.scan(pick.getId(), parcelId,
                    new Packing.Scan(scanId, "SKU-A", null, null, 3, null), actor);

            assertThat(replay.lines().getFirst().packed()).isZero();
        }

        @Test
        @DisplayName("a parcel a load plan holds is neither re-opened nor voided: the plan's refusal reaches the packer")
        void plannedParcelsAreKept() {
            PackingSession.Parcel parcel = sealedParcel(4);
            org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.PACKAGE_IN_LOAD_PLAN)).when(loadPlans)
                    .requireNotPlanned(any());
            when(packages.findById(parcel.id())).thenReturn(Optional.of(new PackageJpaEntity(parcel.id(), pack.getId(),
                    1, parcel.labelCode())));

            assertThat(refusal(() -> service.reopen(pick.getId(), parcel.id(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_IN_LOAD_PLAN);
            assertThat(refusal(() -> service.voidPackage(pick.getId(), parcel.id(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_IN_LOAD_PLAN);
        }

        @Test
        @DisplayName("a sealed parcel not in any plan is re-opened and its units can change; voiding takes it apart")
        void reopenAndVoid() {
            PackingSession.Parcel parcel = sealedParcel(4);
            when(packages.findById(parcel.id())).thenReturn(Optional.empty());

            assertThat(service.reopen(pick.getId(), parcel.id(), actor).status()).isEqualTo("OPEN");
            Packing.Session afterVoid = service.voidPackage(pick.getId(), parcel.id(), actor);

            assertThat(afterVoid.lines().getFirst().packed()).isZero();
            assertThat(afterVoid.packages()).singleElement().satisfies(p -> assertThat(p.status()).isEqualTo("VOIDED"));
        }

        @Test
        @DisplayName("a kit of an item that ships as several boxes opens one part per box")
        void kitParts() {
            session = new PackingSession(pack.getId(), "SO-0001", PackStatus.PACKING, NOW, packer, null, null,
                    List.of(new PackingSession.Line(lineId, "SKU-A", null, 4, 4, false, null, Map.of(), Set.of(), true,
                            false, 3)), List.of(), List.of());

            Packing.Kit kit = service.openKit(pick.getId(), new Packing.OpenKit(lineId, null, null), actor);

            assertThat(kit.packages()).hasSize(3);
            assertThat(kit.partCount()).isEqualTo(3);
            verify(orders).recordPackingStarted(orderId);
        }

        @Test
        @DisplayName("KIT-FORCE: the packer's reason is audited after the commit, escaped, with the parcel's label")
        void kitOverrideReasonIsAudited() {
            TransactionSynchronizationManager.initSynchronization();

            Packing.Parcel parcel = service.openPackage(pick.getId(), "carton", "customer said \"no kit\"\nthanks", actor);

            assertThat(parcel.kitOverrideReason()).isEqualTo("customer said \"no kit\"\nthanks");
            verify(audit, never()).record(any());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit).record(entry.capture());
            assertThat(entry.getValue().resourceType()).isEqualTo("fulfillment-package");
            assertThat(entry.getValue().resourceId()).isEqualTo(parcel.packageId().toString());
            assertThat(entry.getValue().details()).isEqualTo(
                    "{\"kitOverrideReason\":\"customer said \\\"no kit\\\"\\nthanks\",\"labelCode\":\"PKG-SO-0001-1\"}");
        }
    }

    @Nested
    @DisplayName("the label (09 BR-04: label, manifest and order agree on the COD)")
    class Labels {

        private PackingSession.Parcel sealed;

        @BeforeEach
        void aSealedParcel() {
            packing(packer);
            sealed = sealedParcel(10);
            when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(
                    new WarehouseView(WAREHOUSE, "HCM", "HCM warehouse", "1 Le Loi", true)));
        }

        @Test
        @DisplayName("an open parcel has no label yet; a voided one's label is void (08 BR-05)")
        void onlySealedParcelsHaveLabels() {
            PackingSession.Parcel open = session.openPackage(UUID.randomUUID(), PackageType.CARTON, NOW);
            assertThat(refusal(() -> service.label(pick.getId(), open.id(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);

            session.voidPackage(sealed.id(), packer, NOW);
            var thrown = refusal(() -> service.label(pick.getId(), sealed.id(), actor));
            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
            assertThat(thrown.getMessage()).contains("voided");
            assertThat(refusal(() -> service.label(pick.getId(), UUID.randomUUID(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("a parcel not yet in a shipment gets a provisional label with the COD the order would fix now")
        void provisionalLabel() {
            when(orders.codFor(eq(orderId), any(), any())).thenReturn(new BigDecimal("40000"));

            Packing.Label label = service.label(pick.getId(), sealed.id(), actor);

            assertThat(label.provisional()).isTrue();
            assertThat(label.codAmount()).isEqualByComparingTo("40000");
            assertThat(label.shipmentId()).isNull();
            assertThat(label.packageNo()).isEqualTo(1);
            assertThat(label.packageCount()).isEqualTo(1);
            assertThat(label.currency()).isEqualTo("VND");
            assertThat(label.returnWarehouseName()).isEqualTo("HCM warehouse");
            assertThat(label.recipient().recipientName()).isEqualTo("Ms Lan");
            assertThat(label.recipient().phone()).isEqualTo("0900000001");
            assertThat(label.weightKg()).isEqualByComparingTo("8");
        }

        @Test
        @DisplayName("the recipient is the shipping address's, falling back to the order's contact for a missing name or phone")
        void recipientFromTheShippingAddress() {
            OrderSummary base = order(OrderStatus.PICKING, false, true, null);
            var address = new OrderSummary.AddressSummary(null, null, "12 Nguyen Hue", null, "W1", "Ben Nghe", "P1",
                    "HCM", "VN", "700000");
            when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, base.orderNumber(),
                    base.customerId(), base.status(), base.total(), base.lines(), base.placedAt(), null, null, null,
                    "Ms Lan", null, "0900000001", address, null, null, WAREHOUSE, NOW, null, null, base.lifecycle())));
            when(orders.codFor(any(), any(), any())).thenReturn(BigDecimal.ZERO);

            Packing.Address recipient = service.label(pick.getId(), sealed.id(), actor).recipient();

            assertThat(recipient.recipientName()).isEqualTo("Ms Lan");
            assertThat(recipient.phone()).isEqualTo("0900000001");
            assertThat(recipient.line1()).isEqualTo("12 Nguyen Hue");
            assertThat(recipient.wardName()).isEqualTo("Ben Nghe");
            assertThat(recipient.provinceName()).isEqualTo("HCM");
        }

        private ShipmentJpaEntity shipmentOfTheParcel(ShipmentStatus status, String cod) {
            Shipment s = Shipment.ready(UUID.randomUUID(), "SO-0001-S1", orderId, pack.getId(), WAREHOUSE,
                    UUID.randomUUID(), DeliveryMode.PARCEL, 1, new BigDecimal(cod), null, packer, NOW);
            if (status == ShipmentStatus.HANDED_OVER) {
                s.handOver(UUID.randomUUID(), UUID.randomUUID(), null, false, packer, NOW);
            }
            ShipmentJpaEntity row = FulfillmentFixtures.row(s);
            session.ship(new PackingSession.Completion(List.of(sealed), Map.of(lineId, 10), false, List.of()), row.getId(),
                    packer, NOW);
            when(shipments.findById(row.getId())).thenReturn(Optional.of(row));
            return row;
        }

        @Test
        @DisplayName("printing the label of a shipment waiting for dispatch records the COD the carrier would collect")
        void printingRecordsTheCod() {
            ShipmentJpaEntity row = shipmentOfTheParcel(ShipmentStatus.READY_TO_DISPATCH, "10000");
            when(orders.codFor(eq(orderId), any(), any())).thenReturn(new BigDecimal("25000"));

            Packing.Label label = service.label(pick.getId(), sealed.id(), actor);

            assertThat(label.provisional()).isFalse();
            assertThat(label.shipmentCode()).isEqualTo("SO-0001-S1");
            assertThat(label.codAmount()).isEqualByComparingTo("25000");
            assertThat(row.getLabelCodAmount()).isEqualByComparingTo("25000");
            assertThat(row.getCodAmount()).isEqualByComparingTo("25000");
            verify(shipments).saveAndFlush(row);
        }

        @Test
        @DisplayName("a shipment already handed over prints the COD it left with, whatever the order says now")
        void handedOverKeepsItsCod() {
            ShipmentJpaEntity row = shipmentOfTheParcel(ShipmentStatus.HANDED_OVER, "10000");

            Packing.Label label = service.label(pick.getId(), sealed.id(), actor);

            assertThat(label.codAmount()).isEqualByComparingTo("10000");
            verify(orders, never()).codFor(any(), any(), any());
            verify(shipments, never()).saveAndFlush(row);
        }

        @Test
        @DisplayName("reprinting a label outdated by a shipment split makes it current again")
        void reprintClearsOutdated() {
            when(orders.codFor(any(), any(), any())).thenReturn(BigDecimal.ZERO);
            PackageJpaEntity row = new PackageJpaEntity(sealed.id(), pack.getId(), 1, sealed.labelCode());
            row.markLabelOutdated(NOW.minusSeconds(30));
            when(packages.findById(sealed.id())).thenReturn(Optional.of(row));

            service.label(pick.getId(), sealed.id(), actor);

            assertThat(row.getLabelOutdatedAt()).isNull();
            verify(packages).save(row);
        }
    }

    @Nested
    @DisplayName("completing packing into a shipment (08 BR-01, step 9; PACK-PARTIAL)")
    class Completing {

        @BeforeEach
        void started() {
            packing(packer);
            when(orders.markPacked(eq(orderId), any())).thenAnswer(call -> order(OrderStatus.PACKED, false, true, null));
            when(orders.codFor(eq(orderId), any(), any())).thenReturn(new BigDecimal("30000"));
        }

        @Test
        @DisplayName("everything packed: the order is marked packed and a READY_TO_DISPATCH shipment is made, numbered S1")
        void fullCompletionMakesAShipment() {
            sealedParcel(10);
            when(shipments.countByOrderId(orderId)).thenReturn(0L);

            PackingService.Completed done = service.complete(pick.getId(), PackingCompletion.whole(), actor);

            ArgumentCaptor<ShipmentJpaEntity> saved = ArgumentCaptor.forClass(ShipmentJpaEntity.class);
            verify(shipments).saveAndFlush(saved.capture());
            ShipmentJpaEntity shipment = saved.getValue();
            assertThat(done.shipmentId()).isEqualTo(shipment.getId());
            assertThat(shipment.getCode()).isEqualTo("SO-0001-S1");
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
            assertThat(shipment.getCodAmount()).isEqualByComparingTo("30000");
            assertThat(shipment.getPackageCount()).isEqualTo(1);
            assertThat(shipment.isPartial()).isFalse();
            assertThat(shipment.getDeliveryMode()).isEqualTo(DeliveryMode.PARCEL);
            assertThat(session.status()).isEqualTo(PackStatus.PACKED);
            verify(orders).markPacked(orderId, packer);
            verify(timeline).record(any(Shipment.class), eq(null), any(OrderSummary.class), eq(null), eq(null), eq(null));
            verify(store).save(session, pack);
        }

        @Test
        @DisplayName("the shipment's number continues the order's count: the second one is S2")
        void shipmentNumbering() {
            sealedParcel(10);
            when(shipments.countByOrderId(orderId)).thenReturn(1L);

            service.complete(pick.getId(), PackingCompletion.whole(), actor);

            ArgumentCaptor<ShipmentJpaEntity> saved = ArgumentCaptor.forClass(ShipmentJpaEntity.class);
            verify(shipments).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getCode()).isEqualTo("SO-0001-S2");
        }

        @Test
        @DisplayName("the shipment travels as the order does: a full truckload order makes a full-truckload shipment")
        void deliveryModeFollowsTheOrder() {
            sealedParcel(10);
            OrderSummary base = order(OrderStatus.PACKED, false, true, null);
            var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, false, true, com.stockflow.order.api.DeliveryMode.FULL_TRUCKLOAD,
                    true);
            when(orders.markPacked(eq(orderId), any())).thenReturn(new OrderSummary(orderId, "SO-0001", base.customerId(),
                    OrderStatus.PACKED, base.total(), base.lines(), NOW, null, null, null, null, null, null, null, null,
                    null, WAREHOUSE, NOW, null, null, lifecycle));
            when(orders.codFor(any(), any(), any())).thenReturn(BigDecimal.ZERO);

            service.complete(pick.getId(), PackingCompletion.whole(), actor);

            ArgumentCaptor<ShipmentJpaEntity> saved = ArgumentCaptor.forClass(ShipmentJpaEntity.class);
            verify(shipments).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getDeliveryMode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
        }

        @Test
        @DisplayName("a request id seen before answers with the shipment it made; for another task it is CONFLICT")
        void requestIdReplay() {
            UUID requestId = UUID.randomUUID();
            Shipment made = Shipment.ready(UUID.randomUUID(), "SO-0001-S1", orderId, pack.getId(), WAREHOUSE, requestId,
                    DeliveryMode.PARCEL, 1, BigDecimal.ZERO, null, packer, NOW);
            ShipmentJpaEntity row = FulfillmentFixtures.row(made);
            when(shipments.findByRequestId(requestId)).thenReturn(Optional.of(row));

            PackingService.Completed again = service.complete(pick.getId(),
                    new PackingCompletion(requestId, false, null), actor);

            assertThat(again.shipmentId()).isEqualTo(row.getId());
            verify(orders, never()).markPacked(any(), any());

            Shipment other = Shipment.ready(UUID.randomUUID(), "SO-0002-S1", UUID.randomUUID(), UUID.randomUUID(),
                    WAREHOUSE, requestId, DeliveryMode.PARCEL, 1, BigDecimal.ZERO, null, packer, NOW);
            when(shipments.findByRequestId(requestId)).thenReturn(Optional.of(FulfillmentFixtures.row(other)));
            assertThat(refusal(() -> service.complete(pick.getId(), new PackingCompletion(requestId, false, null), actor))
                    .errorCode()).isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("packing already PACKED answers with the last shipment it made")
        void completedBefore() {
            pack.apply(PackStatus.PACKED, NOW, packer, NOW, packer);
            Shipment first = Shipment.ready(UUID.randomUUID(), "SO-0001-S1", orderId, pack.getId(), WAREHOUSE, null,
                    DeliveryMode.PARCEL, 1, BigDecimal.ZERO, null, packer, NOW);
            Shipment last = Shipment.ready(UUID.randomUUID(), "SO-0001-S2", orderId, pack.getId(), WAREHOUSE, null,
                    DeliveryMode.PARCEL, 1, BigDecimal.ZERO, null, packer, NOW);
            when(shipments.findByPackIdOrderByReadyAtAscIdAsc(pack.getId())).thenReturn(List.of(
                    FulfillmentFixtures.row(first), FulfillmentFixtures.row(last)));

            assertThat(service.complete(pick.getId(), PackingCompletion.whole(), actor).shipmentId()).isEqualTo(last.id());
            verifyNoInteractions(store);
        }

        @Test
        @DisplayName("missing units without the partial flag are PACKING_INCOMPLETE")
        void incomplete() {
            sealedParcel(6);

            assertThat(refusal(() -> service.complete(pick.getId(), PackingCompletion.whole(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_INCOMPLETE);
            verify(orders, never()).markPacked(any(), any());
        }

        @Test
        @DisplayName("a partial shipment is refused unless Sales allowed it on the order (PACK-PARTIAL)")
        void partialNeedsTheOrdersConsent() {
            sealedParcel(6);

            assertThat(refusal(() -> service.complete(pick.getId(), new PackingCompletion(null, true, "only 6"), actor))
                    .errorCode()).isEqualTo(ErrorCode.PACK_PARTIAL_NOT_ALLOWED);
        }

        @Test
        @DisplayName("a partial shipment of an order that allows it records the order as packed, not marked, with the reason as its note")
        void partialShipment() {
            sealedParcel(6);
            orderIs(OrderStatus.PICKING, true, null);
            when(orders.codFor(eq(orderId), any(), any())).thenReturn(BigDecimal.ZERO);
            when(shipments.countByOrderId(orderId)).thenReturn(0L);

            PackingService.Completed done = service.complete(pick.getId(),
                    new PackingCompletion(null, true, "  only 6 on the shelf "), actor);

            verify(orders).recordPacked(orderId);
            verify(orders, never()).markPacked(any(), any());
            ArgumentCaptor<ShipmentJpaEntity> saved = ArgumentCaptor.forClass(ShipmentJpaEntity.class);
            verify(shipments).saveAndFlush(saved.capture());
            assertThat(saved.getValue().isPartial()).isTrue();
            assertThat(saved.getValue().getPartialNote()).isEqualTo("only 6 on the shelf");
            assertThat(session.status()).as("the rest is still to pack").isEqualTo(PackStatus.PACKING);
            assertThat(done.shipmentId()).isEqualTo(saved.getValue().getId());
        }

        @Test
        @DisplayName("the packer's own session is required: another packer is PACKING_TAKEN")
        void onlyTheStarterCompletes() {
            packing(UUID.randomUUID());
            sealedParcelByOther();

            assertThat(refusal(() -> service.complete(pick.getId(), PackingCompletion.whole(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_TAKEN);
        }

        private void sealedParcelByOther() {
            // nothing: PACKING_TAKEN is raised before parcels are looked at
        }

        @Test
        @DisplayName("an order that can no longer be packed refuses completion")
        void orderMustStillBePackable() {
            sealedParcel(10);
            orderIs(OrderStatus.ON_HOLD, false, HoldReason.PAYMENT);

            assertThat(refusal(() -> service.complete(pick.getId(), PackingCompletion.whole(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        }
    }

    @Nested
    @DisplayName("the design check of printed goods (BR-DSG-003, 08 BR-02)")
    class DesignCheck {

        private final UUID snapshot = UUID.randomUUID();
        private final UUID printLine = UUID.randomUUID();

        private OrderSummary printedOrder() {
            var stock = new OrderSummary.LineSummary(lineId, "SKU-A", 10, Money.vnd(10_000), Money.vnd(100_000), List.of());
            var printed = new OrderSummary.LineSummary(printLine, "CUP-PRINT", 4, Money.vnd(10_000), Money.vnd(40_000),
                    List.of(), snapshot, "sha-confirmed");
            var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                    BigDecimal.ZERO, new BigDecimal("140000"), new BigDecimal("140000"), BigDecimal.ZERO, List.of(),
                    List.of(), BigDecimal.ZERO, BigDecimal.ZERO, false, true);
            return new OrderSummary(orderId, "SO-0001", UUID.randomUUID(), OrderStatus.PICKING, Money.vnd(140_000),
                    List.of(stock, printed), NOW, null, null, null, null, null, null, null, null, null, WAREHOUSE, NOW,
                    null, null, lifecycle);
        }

        @BeforeEach
        void aPrintedOrderBeingPacked() {
            when(orders.findById(orderId)).thenReturn(Optional.of(printedOrder()));
            session = new PackingSession(pack.getId(), "SO-0001", PackStatus.PACKING, NOW, packer, null, null,
                    List.of(new PackingSession.Line(lineId, "SKU-A", null, 10, 10, false, null, Map.of(), Set.of(), true,
                                    false, 1),
                            new PackingSession.Line(printLine, "CUP-PRINT", null, 4, 4, true, snapshot, Map.of(), Set.of(),
                                    true, false, 1)), List.of(), List.of());
            pack.apply(PackStatus.PACKING, NOW, packer, null, null);
            PackingSession.Parcel parcel = session.openPackage(UUID.randomUUID(), PackageType.CARTON, NOW);
            session.scan(UUID.randomUUID(), parcel.id(), "SKU-A", null, null, 10, null, packer, NOW);
            session.scan(UUID.randomUUID(), parcel.id(), "CUP-PRINT", null, null, 4, null, packer, NOW);
            session.seal(parcel.id(), new PackingSession.Measures(new BigDecimal("8"), new BigDecimal("40"),
                    new BigDecimal("30"), new BigDecimal("25")), null, null, null, 6000, packer, NOW);
            when(orders.markPacked(eq(orderId), any())).thenReturn(printedOrder());
            when(orders.codFor(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a printed line that matches its confirmed design ships; the check is kept as MATCH evidence")
        void matchingDesignShips() {
            PackingService.Completed done = service.complete(pick.getId(), PackingCompletion.whole(), actor);

            assertThat(done.shipmentId()).isNotNull();
            ArgumentCaptor<DesignVerificationJpaEntity> evidence = ArgumentCaptor.forClass(DesignVerificationJpaEntity.class);
            verify(verifications).save(evidence.capture());
            assertThat(evidence.getValue().getResult()).isEqualTo(DesignVerificationResult.MATCH);
            verify(designs).verifyForFulfillment(snapshot, "sha-confirmed");
        }

        @Test
        @DisplayName("a mismatch holds packing and the order for QC, keeps MISMATCH evidence and makes no shipment")
        void mismatchHoldsPacking() {
            when(designs.verifyForFulfillment(any(), any())).thenReturn(
                    new DesignVerification(snapshot, "sha-confirmed", "sha-other", false));
            UUID requestId = UUID.randomUUID();

            PackingService.Completed done = service.complete(pick.getId(),
                    new PackingCompletion(requestId, false, null), actor);

            assertThat(done.shipmentId()).isNull();
            assertThat(session.status()).isEqualTo(PackStatus.ON_HOLD);
            assertThat(pack.getHeldRequestId()).isEqualTo(requestId);
            verify(orders).putOnDesignHold(orderId, "DESIGN_MISMATCH");
            verify(shipments, never()).saveAndFlush(any());
            ArgumentCaptor<DesignVerificationJpaEntity> evidence = ArgumentCaptor.forClass(DesignVerificationJpaEntity.class);
            verify(verifications).save(evidence.capture());
            assertThat(evidence.getValue().getResult()).isEqualTo(DesignVerificationResult.MISMATCH);
        }

        @Test
        @DisplayName("the same completion again after the hold answers the same: no shipment, nothing checked twice")
        void completionAfterTheHoldIsIdempotent() {
            UUID requestId = UUID.randomUUID();
            pack.apply(PackStatus.ON_HOLD, NOW, packer, null, null);
            pack.holdFor(requestId);

            PackingService.Completed done = service.complete(pick.getId(), new PackingCompletion(requestId, false, null),
                    actor);

            assertThat(done.shipmentId()).isNull();
            verifyNoInteractions(designs, verifications);
        }

        @Test
        @DisplayName("re-verifying a pack that has no hold is PICK_TASK_STATE_CONFLICT")
        void reverifyNeedsAHold() {
            assertThat(refusal(() -> service.reverify(pick.getId(), "ok", manager)).errorCode())
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("re-verified and matching: the order's design hold is resolved and packing resumes")
        void reverifyResumes() {
            pack.apply(PackStatus.ON_HOLD, NOW, packer, null, null);
            session.hold();

            boolean resumed = service.reverify(pick.getId(), "reprinted", manager);

            assertThat(resumed).isTrue();
            verify(orders).resolveDesignHold(orderId, manager.userId(), "reprinted");
            assertThat(session.status()).isEqualTo(PackStatus.PACKING);
            verify(store).save(session, pack);
        }

        @Test
        @DisplayName("re-verified and still not matching: the hold stays")
        void reverifyStillMismatching() {
            pack.apply(PackStatus.ON_HOLD, NOW, packer, null, null);
            session.hold();
            when(designs.verifyForFulfillment(any(), any())).thenReturn(
                    new DesignVerification(snapshot, "sha-confirmed", "sha-other", false));

            assertThat(service.reverify(pick.getId(), "again", manager)).isFalse();

            verify(orders, never()).resolveDesignHold(any(), any(), any());
            assertThat(session.status()).isEqualTo(PackStatus.ON_HOLD);
        }
    }

    @Nested
    @DisplayName("the goods that reached packing (facts of the session)")
    class Facts {

        @BeforeEach
        void started() {
            packing(packer);
        }

        private PickLineJpaEntity pickedLine(UUID orderLine, int qty, String lot, UUID reservation) {
            PickLineJpaEntity row = new PickLineJpaEntity(UUID.randomUUID(), pick.getId(), orderLine, reservation, "SKU-A",
                    "HCM-A-01", lot, 1, qty);
            row.apply(qty, PickLineStatus.PICKED, packer, NOW, NOW, "HCM-A-01", "SKU-A", 0, null, null, null);
            return row;
        }

        @Test
        @DisplayName("a stock line has available what was picked, by lot; units closed out of the order are not wanted")
        void stockLineFacts() {
            var line = new OrderSummary.LineSummary(lineId, "SKU-A", 10, Money.vnd(10_000), Money.vnd(100_000), List.of());
            var progress = new OrderSummary.LineProgress(lineId, 0, 0, 3);
            var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(progress), List.of());
            when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-0001", UUID.randomUUID(),
                    OrderStatus.PARTIALLY_FULFILLED, Money.vnd(100_000), List.of(line), NOW, null, null, null, null, null,
                    null, null, null, null, WAREHOUSE, NOW, null, null, lifecycle)));
            PickLineJpaEntity open = new PickLineJpaEntity(UUID.randomUUID(), pick.getId(), lineId, null, "SKU-A",
                    "HCM-A-02", "L9", 2, 5);
            when(pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId())).thenReturn(List.of(
                    pickedLine(lineId, 4, "L1", null), pickedLine(lineId, 3, "L2", null), open));

            service.session(pick.getId(), actor);

            assertThat(lastFacts).singleElement().satisfies(l -> {
                assertThat(l.wanted()).as("10 ordered, 3 closed out").isEqualTo(7);
                assertThat(l.available()).as("4 + 3 picked; the open line is not at packing").isEqualTo(7);
                assertThat(l.lots()).containsEntry("L1", 4).containsEntry("L2", 3).hasSize(2);
                assertThat(l.printLine()).isFalse();
                assertThat(l.barcode()).isEqualTo("8930000000017");
            });
        }

        @Test
        @DisplayName("goods without a lot make a line that is not tracked by lot")
        void noLotMeansNotLotTracked() {
            when(pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId())).thenReturn(List.of(
                    pickedLine(lineId, 4, null, null), pickedLine(lineId, 2, " ", null)));

            service.session(pick.getId(), actor);

            assertThat(lastFacts.getFirst().lots()).isEmpty();
            assertThat(lastFacts.getFirst().available()).isEqualTo(6);
        }

        @Test
        @DisplayName("a serial recorded on a picked line's hold becomes a serial the packer may scan")
        void serialsFromTheHolds() {
            UUID reservation = UUID.randomUUID();
            when(pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId())).thenReturn(List.of(
                    pickedLine(lineId, 1, "L1", reservation)));
            when(inventory.holds(any())).thenReturn(List.of(new HoldView(reservation, "SKU-A", "HCM-A-01", "L1", 1,
                    HoldView.Status.CONSUMED, null, null, "SN-1")));

            service.session(pick.getId(), actor);

            assertThat(lastFacts.getFirst().serials()).containsExactly("SN-1");
        }

        @Test
        @DisplayName("a printed line is at packing once production completed, and not while it is still printed (PACK-EARLY)")
        void printLinesAvailability() {
            var printed = new OrderSummary.LineSummary(lineId, "CUP-PRINT", 4, Money.vnd(10_000), Money.vnd(40_000),
                    List.of(), UUID.randomUUID(), "sha");
            for (boolean ready : new boolean[] {true, false}) {
                var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(),
                        BigDecimal.ZERO, BigDecimal.ZERO, true, ready);
                when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-0001",
                        UUID.randomUUID(), OrderStatus.PICKING, Money.vnd(40_000), List.of(printed), NOW, null, null, null,
                        null, null, null, null, null, null, WAREHOUSE, NOW, null, null, lifecycle)));

                service.session(pick.getId(), actor);

                assertThat(lastFacts.getFirst().printLine()).isTrue();
                assertThat(lastFacts.getFirst().available()).isEqualTo(ready ? 4 : 0);
            }
        }

        @Test
        @DisplayName("a FRAGILE item makes a fragile line; the item's rotatable flag and box count carry over")
        void itemFlags() {
            when(itemControls.logistics(any())).thenReturn(new InventoryItemLogistics("SKU-A", 0, new ItemLogistics("PCS",
                    null, null, null, null, null, null, null, null, null, 3, 1, StorageClass.FRAGILE, false, null, false,
                    false)));

            service.session(pick.getId(), actor);

            assertThat(lastFacts.getFirst().fragile()).isTrue();
            assertThat(lastFacts.getFirst().rotatable()).isFalse();
            assertThat(lastFacts.getFirst().packageCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("the queue (08 §5)")
    class Queue {

        @Test
        @DisplayName("an unknown status filter is UNSUPPORTED_PARAMETER; another warehouse is OUT_OF_DATA_SCOPE")
        void filters() {
            assertThat(refusal(() -> service.queue(null, "FINISHED", 0, 20, actor)).errorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);
            WithCurrentUser.run(FulfillmentFixtures.userOf(WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.queue(OTHER_WAREHOUSE, null, 0, 20, actor)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        private PickJpaEntity picked(UUID order) {
            PickJpaEntity p = new PickJpaEntity(UUID.randomUUID(), null, order, "SO-" + order.toString().substring(0, 4),
                    WAREHOUSE, PickStatus.PENDING);
            p.assign(packer, NOW);
            p.start(packer, NOW);
            p.complete(packer, NOW);
            return p;
        }

        @Test
        @DisplayName("orders on hold for anything but their design check, or no longer packable, are left out; counts exclude voided parcels and cancelled shipments")
        void queueRows() {
            UUID ok = UUID.randomUUID();
            UUID heldForDesign = UUID.randomUUID();
            UUID heldForPayment = UUID.randomUUID();
            UUID cancelled = UUID.randomUUID();
            UUID printedEarly = UUID.randomUUID();
            UUID printedNoConsent = UUID.randomUUID();
            List<PickJpaEntity> candidates = new ArrayList<>(List.of(picked(ok), picked(heldForDesign),
                    picked(heldForPayment), picked(cancelled), picked(printedEarly), picked(printedNoConsent)));
            when(picks.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                    any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(candidates));
            when(orders.holdStates(any())).thenReturn(List.of(
                    new OrderHoldState(ok, OrderStatus.PICKING, null, false),
                    new OrderHoldState(heldForDesign, OrderStatus.ON_HOLD, HoldReason.DESIGN_MISMATCH, false),
                    new OrderHoldState(heldForPayment, OrderStatus.ON_HOLD, HoldReason.PAYMENT, false),
                    new OrderHoldState(cancelled, OrderStatus.CANCELLED, null, false),
                    new OrderHoldState(printedEarly, OrderStatus.IN_PRODUCTION, null, true),
                    new OrderHoldState(printedNoConsent, OrderStatus.IN_PRODUCTION, null, false)));
            PackJpaEntity okPack = new PackJpaEntity(UUID.randomUUID(), candidates.get(0).getId(), PackStatus.PACKING,
                    null);
            okPack.apply(PackStatus.PACKING, NOW, packer, null, null);
            PackJpaEntity designPack = new PackJpaEntity(UUID.randomUUID(), candidates.get(1).getId(), PackStatus.ON_HOLD,
                    null);
            PackJpaEntity earlyPack = new PackJpaEntity(UUID.randomUUID(), candidates.get(4).getId(), PackStatus.PENDING,
                    null);
            when(packs.findByPickIdIn(any())).thenReturn(List.of(okPack, designPack, earlyPack));
            PackageJpaEntity sealed = FulfillmentFixtures.parcel(UUID.randomUUID(), 1, "40", "30", "25", "5");
            PackageJpaEntity open = new PackageJpaEntity(UUID.randomUUID(), okPack.getId(), 2, "PKG-2");
            PackageJpaEntity voided = new PackageJpaEntity(UUID.randomUUID(), okPack.getId(), 3, "PKG-3");
            voided.apply(null, PackageType.CARTON, PackageStatus.VOIDED, null, null, null, null, null, true, false, false,
                    false, null, null, NOW, null);
            when(packages.findByPackIdIn(any())).thenReturn(List.of(
                    withPack(sealed, okPack.getId()), withPack(open, okPack.getId()), withPack(voided, okPack.getId())));
            ShipmentJpaEntity live = FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH);
            ShipmentJpaEntity dead = FulfillmentFixtures.rowIn(ShipmentStatus.CANCELLED);
            org.springframework.test.util.ReflectionTestUtils.setField(live, "packId", okPack.getId());
            org.springframework.test.util.ReflectionTestUtils.setField(dead, "packId", okPack.getId());
            when(shipments.findByPackIdIn(any())).thenReturn(List.of(live, dead));
            when(identities.displayNames(any())).thenReturn(new java.util.HashMap<>(Map.of(packer, "Packer An")));

            var page = service.queue(null, null, 0, 20, actor);

            assertThat(page.items()).extracting(Packing.QueueRow::orderId).containsExactly(ok, heldForDesign, printedEarly);
            Packing.QueueRow row = page.items().getFirst();
            assertThat(row.packStatus()).isEqualTo("PACKING");
            assertThat(row.packageCount()).as("the voided parcel is not counted").isEqualTo(2);
            assertThat(row.sealedPackageCount()).isEqualTo(1);
            assertThat(row.shipmentCount()).as("the cancelled shipment is not counted").isEqualTo(1);
            assertThat(row.packerName()).isEqualTo("Packer An");
            assertThat(page.totalElements()).isEqualTo(3);
        }

        private PackageJpaEntity withPack(PackageJpaEntity row, UUID packId) {
            org.springframework.test.util.ReflectionTestUtils.setField(row, "packId", packId);
            return row;
        }
    }

    @Nested
    @DisplayName("the order was cancelled (08 BR-05)")
    class Cancelling {

        private PackageJpaEntity parcelOf(UUID shipmentId, PackageStatus status) {
            PackageJpaEntity parcel = new PackageJpaEntity(UUID.randomUUID(), pack.getId(), 1, "PKG-1");
            parcel.apply(shipmentId, PackageType.CARTON, status, new BigDecimal("5"), new BigDecimal("40"),
                    new BigDecimal("30"), new BigDecimal("25"), null, true, false, false, false, NOW, packer, null, null);
            return parcel;
        }

        @Test
        @DisplayName("a shipment waiting for dispatch is taken out of its plan and loads, cancelled, and its parcels voided with their labels")
        void waitingShipmentIsCancelled() {
            ShipmentJpaEntity row = FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH);
            when(shipments.findByPackIdOrderByReadyAtAscIdAsc(pack.getId())).thenReturn(List.of(row));
            when(planVehicles.partlyGone(row.getId())).thenReturn(false);
            PackageJpaEntity parcel = parcelOf(row.getId(), PackageStatus.SEALED);
            when(packages.findByShipmentIdOrderByPackageNoAsc(row.getId())).thenReturn(List.of(parcel));
            when(packages.findByPackIdOrderByPackageNoAsc(pack.getId())).thenReturn(List.of(parcel));

            service.cancelForOrder(pick, pack);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
            assertThat(row.getCancelledAt()).isEqualTo(NOW);
            assertThat(parcel.getStatus()).isEqualTo(PackageStatus.VOIDED);
            verify(loadPlans).releaseShipment(row.getId(), NOW);
            verify(planVehicles).withdraw(row.getId(), NOW);
            verify(timeline).record(any(Shipment.class), eq(ShipmentStatus.READY_TO_DISPATCH), eq(null), eq("SO-0001"),
                    eq(null), eq(null));
        }

        @Test
        @DisplayName("a shipment that already left in part on a plan's vehicle is not voided: left for a person to resolve")
        void partlyGoneShipmentIsLeftAlone() {
            ShipmentJpaEntity row = FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH);
            when(shipments.findByPackIdOrderByReadyAtAscIdAsc(pack.getId())).thenReturn(List.of(row));
            when(planVehicles.partlyGone(row.getId())).thenReturn(true);
            when(packages.findByPackIdOrderByPackageNoAsc(pack.getId())).thenReturn(List.of());

            service.cancelForOrder(pick, pack);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.READY_TO_DISPATCH);
            verifyNoInteractions(loadPlans, timeline);
            verify(planVehicles, never()).withdraw(any(), any());
        }

        @Test
        @DisplayName("a shipment already handed over is not touched")
        void handedOverShipmentIsLeftAlone() {
            ShipmentJpaEntity row = FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER);
            PackageJpaEntity shipped = parcelOf(row.getId(), PackageStatus.SEALED);
            when(shipments.findByPackIdOrderByReadyAtAscIdAsc(pack.getId())).thenReturn(List.of(row));
            when(packages.findByPackIdOrderByPackageNoAsc(pack.getId())).thenReturn(List.of(shipped));

            service.cancelForOrder(pick, pack);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.HANDED_OVER);
            assertThat(shipped.getStatus()).isEqualTo(PackageStatus.SEALED);
            verifyNoInteractions(timeline);
        }

        @Test
        @DisplayName("parcels packed but in no shipment yet are voided, with their labels")
        void unshippedParcelsAreVoided() {
            PackageJpaEntity open = parcelOf(null, PackageStatus.OPEN);
            PackageJpaEntity sealed = parcelOf(null, PackageStatus.SEALED);
            PackageJpaEntity alreadyVoid = parcelOf(null, PackageStatus.VOIDED);
            when(packages.findByPackIdOrderByPackageNoAsc(pack.getId())).thenReturn(List.of(open, sealed, alreadyVoid));

            service.cancelForOrder(pick, pack);

            assertThat(open.getStatus()).isEqualTo(PackageStatus.VOIDED);
            assertThat(sealed.getStatus()).isEqualTo(PackageStatus.VOIDED);
            assertThat(sealed.getVoidedAt()).isEqualTo(NOW);
            verify(packages).save(open);
            verify(packages).save(sealed);
            verify(packages, never()).save(alreadyVoid);
        }
    }
}
