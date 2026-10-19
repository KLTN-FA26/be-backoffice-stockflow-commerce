package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.DesignVerification;
import com.stockflow.fulfillment.api.FulfillmentTaskQuery;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.DesignVerificationJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.repository.DesignVerificationJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.identity.api.AssignableUser;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.OrderHold;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentServiceImplTest {

    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final PackJpaRepository packs = mock(PackJpaRepository.class);
    private final DesignVerificationJpaRepository evidence = mock(DesignVerificationJpaRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final DesignService designs = mock(DesignService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final PickListService pickLists = mock(PickListService.class);
    private final Instant now = Instant.parse("2026-09-18T08:00:00Z");
    private final FulfillmentServiceImpl service = new FulfillmentServiceImpl(
            picks, packs, evidence, orders, designs, identities, inventory, warehouses, pickLists,
            mock(com.stockflow.inventory.consumption.PickConsumption.class),
            Clock.fixed(now, ZoneOffset.UTC));
    private final List<DesignVerificationJpaEntity> recorded = new ArrayList<>();

    @BeforeEach
    void retainVerificationEvidence() {
        when(evidence.save(any())).thenAnswer(call -> {
            DesignVerificationJpaEntity value = call.getArgument(0);
            recorded.add(value);
            return value;
        });
        when(evidence.findByPackIdOrderByVerifiedAtDesc(any())).thenAnswer(call -> List.copyOf(recorded));
    }

    @Test
    void checksumMismatchPutsPackageAndOrderOnHoldWithoutLosingEvidence() {
        UUID assigned = UUID.randomUUID();
        var pick = pickedTask(assigned);
        var pack = new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.PENDING, null);
        String expected = "a".repeat(64);
        String actual = "b".repeat(64);
        var order = order(pick.getOrderId(), expected);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(pack));
        when(orders.findById(pick.getOrderId())).thenReturn(Optional.of(order));
        when(designs.verifyForFulfillment(order.lines().getFirst().designSnapshotId(), expected))
                .thenReturn(new DesignVerification(order.lines().getFirst().designSnapshotId(), expected, actual, false));

        var result = service.completePacking(pick.getId(), user(assigned, Role.WAREHOUSE_STAFF));

        assertThat(result.packStatus()).isEqualTo("ON_HOLD");
        assertThat(result.integrityEvidence()).singleElement().satisfies(item -> {
            assertThat(item.matching()).isFalse();
            assertThat(item.actualChecksum()).isEqualTo(actual);
        });
        verify(orders).putOnDesignHold(pick.getOrderId(), "DESIGN_MISMATCH");
        verify(orders, never()).resolveDesignHold(any(), any(), any());
    }

    @Test
    void onlySuccessfulQcReverificationCompletesHeldPackageAndResolvesOrder() {
        UUID assigned = UUID.randomUUID();
        UUID qc = UUID.randomUUID();
        var pick = pickedTask(assigned);
        var pack = new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.ON_HOLD, null);
        String expected = "c".repeat(64);
        var order = order(pick.getOrderId(), expected);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(pack));
        when(orders.findById(pick.getOrderId())).thenReturn(Optional.of(order));
        when(designs.verifyForFulfillment(order.lines().getFirst().designSnapshotId(), expected))
                .thenReturn(new DesignVerification(order.lines().getFirst().designSnapshotId(), expected, expected, true));

        var result = service.reverifyDesignIntegrity(
                pick.getId(), "Artifact restored from the confirmed immutable version", user(qc, Role.QC_STAFF));

        assertThat(result.packStatus()).isEqualTo("PACKED");
        verify(orders).resolveDesignHold(pick.getOrderId(), qc,
                "Artifact restored from the confirmed immutable version");
    }

    @Test
    void unrelatedWarehouseUserCannotSeeTaskOrDownloadArtifact() {
        var pick = pickedTask(UUID.randomUUID());
        when(picks.findById(pick.getId())).thenReturn(Optional.of(pick));

        var unrelated = user(UUID.randomUUID(), Role.WAREHOUSE_STAFF);
        assertThatThrownBy(() -> service.findTask(pick.getId(), unrelated))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.artifactDownload(
                pick.getId(), UUID.randomUUID(), "PRINT_READY", unrelated))
                .isInstanceOf(BusinessException.class);
        verify(designs, never()).fulfillmentDownload(any(), any(), any());
    }

    @Test
    void aCancelledOrderCannotBePickedPackedReverifiedOrDownloaded() {
        UUID assigned = UUID.randomUUID();
        var pick = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-TEST", UUID.randomUUID(), PickStatus.PENDING);
        pick.assign(assigned, now.minusSeconds(120));
        var live = order(pick.getOrderId(), "d".repeat(64));
        var cancelled = new OrderSummary(live.orderId(), live.orderNumber(), live.customerId(), OrderStatus.CANCELLED,
                live.total(), live.lines(), live.placedAt(), null, null, null, null, null, null, null, null);
        var pack = new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.ON_HOLD, null);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(picks.findById(pick.getId())).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(pack));
        when(orders.findById(pick.getOrderId())).thenReturn(Optional.of(cancelled));
        var worker = user(assigned, Role.WAREHOUSE_STAFF);
        var qc = user(UUID.randomUUID(), Role.QC_STAFF);

        assertThatThrownBy(() -> service.startPicking(pick.getId(), worker)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.completePicking(pick.getId(), worker)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.completePacking(pick.getId(), worker)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reverifyDesignIntegrity(pick.getId(), "note", qc))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.artifactDownload(pick.getId(), live.lines().getFirst().lineId(), "PRINT_READY", qc))
                .isInstanceOf(BusinessException.class);

        assertThat(pick.getStatus()).isEqualTo(PickStatus.PENDING);
        verify(designs, never()).fulfillmentDownload(any(), any(), any());
        verify(orders, never()).putOnDesignHold(any(), any());
        verify(orders, never()).resolveDesignHold(any(), any(), any());
    }

    // ---- SCRUM-466: a task carries its order number and warehouse --------------------------------

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();

    private OrderSummary orderIn(OrderStatus status, UUID warehouse) {
        var line = new OrderSummary.LineSummary(UUID.randomUUID(), "SOFA-3S-GREY", 2,
                Money.vnd(100_000), Money.vnd(200_000), List.of());
        return new OrderSummary(UUID.randomUUID(), "SO-20261014-0001", UUID.randomUUID(), status,
                Money.vnd(200_000), List.of(line), now.minusSeconds(600), null, null, null, null, null, null, null,
                null, null, warehouse, warehouse == null ? null : now.minusSeconds(60));
    }

    private void holdsAt(OrderSummary order, String... locationsAndSkus) {
        var holds = new ArrayList<OrderHold>();
        for (String entry : locationsAndSkus) {
            String[] parts = entry.split("=");
            holds.add(new OrderHold(parts[1], parts[0], 2));
            UUID where = parts[0].startsWith("HN") ? HN : HCM;
            when(warehouses.findLocation(parts[0])).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                    StorageLocationView.Kind.BIN, where, parts[0], StorageLocationView.StorageClass.NORMAL,
                    StorageLocationView.Status.ACTIVE, StorageLocationView.Status.ACTIVE, true)));
        }
        when(inventory.holdsOf(order.orderId())).thenReturn(holds);
    }

    private void assignable(UUID warehouse, UUID... users) {
        when(identities.assignableUsers(any(), eq(warehouse))).thenReturn(java.util.Arrays.stream(users)
                .map(id -> new AssignableUser(id, "Staff " + id.toString().substring(0, 4), List.of("WAREHOUSE_STAFF")))
                .toList());
    }

    private CurrentUser coordinator() {
        return new CurrentUser(UUID.randomUUID(), "oc", Set.of(Role.ORDER_COORDINATOR), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
    }

    private CurrentUser managerOf(UUID... warehouseIds) {
        return new CurrentUser(UUID.randomUUID(), "manager", Set.of(Role.WAREHOUSE_MANAGER), Set.of(),
                DataScope.WAREHOUSE, Set.of(), Set.of(warehouseIds));
    }

    private <T> T asScoped(CurrentUser user, java.util.function.Supplier<T> action) {
        return WithCurrentUser.call(user, DataScope.ALL, action);
    }

    private static BusinessException refusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return (BusinessException) org.assertj.core.api.Assertions.catchThrowable(call);
    }

    @Test
    void aConfirmedOrderNotYetReleasedTakesTheWarehouseWhereItsHoldsAre() {
        var order = orderIn(OrderStatus.CONFIRMED, null);
        UUID picker = UUID.randomUUID();
        holdsAt(order, "HN-A01-1-A=SOFA-3S-GREY", "HN-A01-2-B=SOFA-3S-GREY");
        assignable(HN, picker);
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(picks.findByOrderId(order.orderId())).thenReturn(Optional.empty());
        when(packs.findByPickId(any())).thenReturn(Optional.of(new PackJpaEntity(UUID.randomUUID(), UUID.randomUUID(),
                PackStatus.PENDING, null)));
        when(identities.displayNames(any())).thenReturn(java.util.Map.of(picker, "Kho Nam"));

        var task = service.createTask(order.orderId(), picker, coordinator());

        assertThat(task.orderNumber()).isEqualTo("SO-20261014-0001");
        assertThat(task.warehouseId()).isEqualTo(HN);
        assertThat(task.assignedUserName()).isEqualTo("Kho Nam");
        assertThat(task.lines()).isNull();
        verify(orders).releaseToFulfillment(order.orderId());
        org.mockito.ArgumentCaptor<PickJpaEntity> saved = org.mockito.ArgumentCaptor.forClass(PickJpaEntity.class);
        verify(picks).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOrderNumber()).isEqualTo("SO-20261014-0001");
        assertThat(saved.getValue().getWarehouseId()).isEqualTo(HN);
        // SCRUM-267: the lines are planned with the task, from the order's holds.
        verify(pickLists).planLines(saved.getValue(), order);
    }

    @Test
    void aReleasedOrderIsPickedInItsOwnWarehouse() {
        var order = orderIn(OrderStatus.READY_TO_FULFILL, HCM);
        UUID picker = UUID.randomUUID();
        holdsAt(order, "HCM-A01-1-A=SOFA-3S-GREY");
        assignable(HCM, picker);
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(picks.findByOrderId(order.orderId())).thenReturn(Optional.empty());
        when(packs.findByPickId(any())).thenReturn(Optional.of(new PackJpaEntity(UUID.randomUUID(), UUID.randomUUID(),
                PackStatus.PENDING, null)));

        assertThat(service.createTask(order.orderId(), picker, coordinator()).warehouseId()).isEqualTo(HCM);
    }

    @Test
    void aReleasedOrderWithNothingHeldIsStillPickedInItsOwnWarehouse() {
        var order = orderIn(OrderStatus.READY_TO_FULFILL, HCM);
        UUID picker = UUID.randomUUID();
        when(inventory.holdsOf(order.orderId())).thenReturn(List.of());
        assignable(HCM, picker);
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(picks.findByOrderId(order.orderId())).thenReturn(Optional.empty());
        when(packs.findByPickId(any())).thenReturn(Optional.of(new PackJpaEntity(UUID.randomUUID(), UUID.randomUUID(),
                PackStatus.PENDING, null)));

        assertThat(service.createTask(order.orderId(), picker, coordinator()).warehouseId()).isEqualTo(HCM);
    }

    @Test
    void holdsInTwoWarehousesAreRefusedWithTheSkusInTheMessage() {
        var order = orderIn(OrderStatus.CONFIRMED, null);
        holdsAt(order, "HN-A01-1-A=SOFA-3S-GREY", "HCM-B01-1-A=TABLE-OAK-160");
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(picks.findByOrderId(order.orderId())).thenReturn(Optional.empty());

        var thrown = refusal(() -> service.createTask(order.orderId(), UUID.randomUUID(), coordinator()));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
        assertThat(thrown.getMessage()).contains("SOFA-3S-GREY", "TABLE-OAK-160");
        verify(orders, never()).releaseToFulfillment(any());
        verify(picks, never()).save(any());
        verify(picks, never()).saveAndFlush(any());
    }

    @Test
    void holdsOutsideTheWarehouseTheOrderWasReleasedFromAreRefused() {
        var order = orderIn(OrderStatus.READY_TO_FULFILL, HCM);
        holdsAt(order, "HCM-A01-1-A=SOFA-3S-GREY", "HN-B01-1-A=TABLE-OAK-160");
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(picks.findByOrderId(order.orderId())).thenReturn(Optional.empty());

        var thrown = refusal(() -> service.createTask(order.orderId(), UUID.randomUUID(), coordinator()));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
        assertThat(thrown.getMessage()).contains("TABLE-OAK-160").doesNotContain("SOFA-3S-GREY");
        verify(picks, never()).save(any());
        verify(picks, never()).saveAndFlush(any());
    }

    @Test
    void anUnreleasedOrderWithNoHoldsHasNoWarehouseToPickIn() {
        var order = orderIn(OrderStatus.CONFIRMED, null);
        when(inventory.holdsOf(order.orderId())).thenReturn(List.of());
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(picks.findByOrderId(order.orderId())).thenReturn(Optional.empty());

        assertThat(refusal(() -> service.createTask(order.orderId(), UUID.randomUUID(), coordinator())).errorCode())
                .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
    }

    @Test
    void anAssigneeOfAnotherWarehouseIsRefusedOnTheAssignedUserIdField() {
        var order = orderIn(OrderStatus.READY_TO_FULFILL, HCM);
        UUID inHn = UUID.randomUUID();
        holdsAt(order, "HCM-A01-1-A=SOFA-3S-GREY");
        assignable(HCM, UUID.randomUUID());
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(picks.findByOrderId(order.orderId())).thenReturn(Optional.empty());

        var thrown = refusal(() -> service.createTask(order.orderId(), inHn, coordinator()));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.fieldErrors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("assignedUserId");
            assertThat(error.code()).isEqualTo("NotAssignable");
        });
        verify(orders, never()).releaseToFulfillment(any());
        verify(picks, never()).save(any());
        verify(picks, never()).saveAndFlush(any());
    }

    @Test
    void aManagerCannotOpenATaskInAWarehouseTheyAreNotAssignedTo() {
        var order = orderIn(OrderStatus.READY_TO_FULFILL, HN);
        holdsAt(order, "HN-A01-1-A=SOFA-3S-GREY");
        assignable(HN, UUID.randomUUID());
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(picks.findByOrderId(order.orderId())).thenReturn(Optional.empty());

        var thrown = refusal(() -> asScoped(managerOf(HCM),
                () -> service.createTask(order.orderId(), UUID.randomUUID(), managerOf(HCM))));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        verify(picks, never()).save(any());
        verify(picks, never()).saveAndFlush(any());
    }

    @Test
    void anOrderThatAlreadyHasATaskReturnsThatTask() {
        UUID picker = UUID.randomUUID();
        var existing = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-OLD", HCM, PickStatus.PENDING);
        existing.assign(picker, now);
        assignable(HCM, picker);
        when(picks.findByOrderId(existing.getOrderId())).thenReturn(Optional.of(existing));
        when(packs.findByPickId(existing.getId())).thenReturn(Optional.of(new PackJpaEntity(UUID.randomUUID(),
                existing.getId(), PackStatus.PENDING, null)));

        var task = service.createTask(existing.getOrderId(), picker, coordinator());

        assertThat(task.taskId()).isEqualTo(existing.getId());
        assertThat(task.orderNumber()).isEqualTo("SO-OLD");
        verify(orders, never()).findById(any());
        verify(picks, never()).save(any());
        verify(picks, never()).saveAndFlush(any());
    }

    @Test
    void theListRefusesAWarehouseOutsideTheCallersScopeBeforeQuerying() {
        var manager = managerOf(HCM);

        var thrown = refusal(() -> asScoped(manager,
                () -> service.listTasks(manager, new FulfillmentTaskQuery(null, HN, null, 0, 20))));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        verify(picks, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void theListRefusesAnUnknownStatus() {
        var thrown = refusal(() -> service.listTasks(coordinator(), new FulfillmentTaskQuery("DONE", null, null, 0, 20)));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);
        assertThat(thrown.fieldErrors()).singleElement().satisfies(error -> assertThat(error.field()).isEqualTo("status"));
    }

    @Test
    void aCustomerStyleCallerWithNoWarehouseRoleCannotListTasks() {
        var nobody = new CurrentUser(UUID.randomUUID(), "x", Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL, Set.of());

        assertThat(refusal(() -> service.listTasks(nobody, new FulfillmentTaskQuery(null, null, null, 0, 20))).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void theDetailCarriesTheLinesToPickAndTheAssigneeName() {
        UUID picker = UUID.randomUUID();
        var pick = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-LINES", HCM, PickStatus.PENDING);
        pick.assign(picker, now);
        var order = order(pick.getOrderId(), "e".repeat(64));
        when(picks.findById(pick.getId())).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(new PackJpaEntity(UUID.randomUUID(), pick.getId(),
                PackStatus.PENDING, null)));
        when(orders.findById(pick.getOrderId())).thenReturn(Optional.of(order));
        when(identities.displayNames(any())).thenReturn(java.util.Map.of(picker, "Kho Nam"));

        var task = service.findTask(pick.getId(), user(picker, Role.WAREHOUSE_STAFF));

        assertThat(task.assignedUserName()).isEqualTo("Kho Nam");
        assertThat(task.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku()).isEqualTo("CUSTOM-SKU");
            assertThat(line.quantity()).isEqualTo(1);
            assertThat(line.designSnapshotId()).isEqualTo(order.lines().getFirst().designSnapshotId());
            assertThat(line.printLine()).isTrue();
        });
    }

    @Test
    void aManagerOfAnotherWarehouseCannotReadOrReassignATask() {
        var pick = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-HN", HN, PickStatus.PENDING);
        when(picks.findById(pick.getId())).thenReturn(Optional.of(pick));
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        var manager = managerOf(HCM);

        assertThat(refusal(() -> asScoped(manager, () -> service.findTask(pick.getId(), manager))).errorCode())
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(refusal(() -> asScoped(manager,
                () -> service.assign(pick.getId(), UUID.randomUUID(), manager))).errorCode())
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(pick.getAssignedUserId()).isNull();
    }

    @Test
    void reassigningChecksTheNewAssigneeAgainstTheTasksWarehouse() {
        UUID stranger = UUID.randomUUID();
        UUID local = UUID.randomUUID();
        var pick = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-HCM", HCM, PickStatus.PENDING);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(picks.save(any())).thenAnswer(call -> call.getArgument(0));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(new PackJpaEntity(UUID.randomUUID(), pick.getId(),
                PackStatus.PENDING, null)));
        assignable(HCM, local);

        var thrown = refusal(() -> service.assign(pick.getId(), stranger, coordinator()));
        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.fieldErrors()).singleElement()
                .satisfies(error -> assertThat(error.field()).isEqualTo("assignedUserId"));
        assertThat(pick.getAssignedUserId()).isNull();

        assertThat(service.assign(pick.getId(), local, coordinator()).assignedUserId()).isEqualTo(local);
    }

    @Test
    void theAssigneesOfAWarehouseAreNamedAndLimitedToTheCallersWarehouses() {
        UUID picker = UUID.randomUUID();
        assignable(HCM, picker);
        when(warehouses.findWarehouse(HCM)).thenReturn(Optional.of(new WarehouseView(HCM, "HCM", "Kho HCM", "x", true)));

        var assignees = service.assignees(HCM, coordinator());

        assertThat(assignees).singleElement().satisfies(one -> {
            assertThat(one.userId()).isEqualTo(picker);
            assertThat(one.roles()).containsExactly("WAREHOUSE_STAFF");
        });
        assertThat(refusal(() -> asScoped(managerOf(HN), () -> service.assignees(HCM, managerOf(HN)))).errorCode())
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(refusal(() -> service.assignees(HCM, user(UUID.randomUUID(), Role.WAREHOUSE_STAFF))).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(refusal(() -> service.assignees(UUID.randomUUID(), coordinator())).errorCode())
                .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
    }

    // ---- SCRUM-267: lines are planned at start and must all be picked to complete ----------------

    @Test
    void startingPlansTheLinesAndCompletingNeedsEveryLinePicked() {
        UUID assigned = UUID.randomUUID();
        var pick = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-TEST", HCM, PickStatus.PENDING);
        pick.assign(assigned, now.minusSeconds(120));
        var order = order(pick.getOrderId(), "e".repeat(64));
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(picks.save(any())).thenAnswer(call -> call.getArgument(0));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(
                new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.PENDING, null)));
        when(orders.findById(pick.getOrderId())).thenReturn(Optional.of(order));
        var worker = user(assigned, Role.WAREHOUSE_STAFF);

        assertThat(service.startPicking(pick.getId(), worker).pickStatus()).isEqualTo("PICKING");
        verify(pickLists).planLines(pick, order);
        // Starting again is answered: the task stays PICKING and its lines are planned if missing.
        assertThat(service.startPicking(pick.getId(), worker).pickStatus()).isEqualTo("PICKING");

        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.PICK_LINES_INCOMPLETE))
                .when(pickLists).requireAllPicked(pick, order);
        assertThat(refusal(() -> service.completePicking(pick.getId(), worker)).errorCode())
                .isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
        assertThat(pick.getStatus()).isEqualTo(PickStatus.PICKING);

        org.mockito.Mockito.doNothing().when(pickLists).requireAllPicked(pick, order);
        assertThat(service.completePicking(pick.getId(), worker).pickStatus()).isEqualTo("PICKED");
        // Completing twice is a state conflict, no longer the generic CONFLICT.
        assertThat(refusal(() -> service.completePicking(pick.getId(), worker)).errorCode())
                .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
    }

    @Test
    void aCancelledOrdersTaskIsAStateConflict() {
        UUID assigned = UUID.randomUUID();
        var pick = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-TEST", HCM, PickStatus.PENDING);
        pick.assign(assigned, now.minusSeconds(120));
        var live = order(pick.getOrderId(), "f".repeat(64));
        var cancelled = new OrderSummary(live.orderId(), live.orderNumber(), live.customerId(), OrderStatus.CANCELLED,
                live.total(), live.lines(), live.placedAt(), null, null, null, null, null, null, null, null);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(orders.findById(pick.getOrderId())).thenReturn(Optional.of(cancelled));

        assertThat(refusal(() -> service.startPicking(pick.getId(), user(assigned, Role.WAREHOUSE_STAFF))).errorCode())
                .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        verify(pickLists, never()).planLines(any(), any());
    }

    private PickJpaEntity pickedTask(UUID assigned) {
        var pick = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-TEST", UUID.randomUUID(), PickStatus.PENDING);
        pick.assign(assigned, now.minusSeconds(120));
        pick.start(assigned, now.minusSeconds(60));
        pick.complete(assigned, now.minusSeconds(30));
        return pick;
    }

    private OrderSummary order(UUID orderId, String checksum) {
        UUID snapshot = UUID.randomUUID();
        var line = new OrderSummary.LineSummary(UUID.randomUUID(), "CUSTOM-SKU", 1,
                Money.vnd(100_000), Money.vnd(100_000), List.of(), snapshot, checksum);
        return new OrderSummary(orderId, "SO-TEST", UUID.randomUUID(), OrderStatus.IN_FULFILMENT,
                Money.vnd(100_000), List.of(line), now.minusSeconds(600), null, null, null, null, null, null, null, null);
    }

    private CurrentUser user(UUID id, Role role) {
        return new CurrentUser(id, role.name().toLowerCase(), Set.of(role), Set.of(), DataScope.ALL, Set.of());
    }
}
