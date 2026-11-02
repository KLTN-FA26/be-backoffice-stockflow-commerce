package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.domain.PickLineStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickLineJpaRepository;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.consumption.PutBackCommand;
import com.stockflow.inventory.consumption.ShortPickRecount;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderReservations;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.OTHER_WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Picking (kltn-docs 07): lines planned from the order's holds, confirmed by scan with the stock deducted in the same
 * transaction (BR-04: never above the allocation), a short pick stopping the order, and the goods of a cancelled order
 * going back (BR-05). The line rules themselves are {@code PickListTest}'s; this is the orchestration around them.
 */
class PickListServiceTest {

    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final PickLineJpaRepository pickLines = mock(PickLineJpaRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final InventoryControlService itemControls = mock(InventoryControlService.class);
    private final PickConsumption consumption = mock(PickConsumption.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final OrderReservations orderReservations = mock(OrderReservations.class);
    private final PickListService service = new PickListService(picks, pickLines, orders, inventory, itemControls,
            consumption, warehouses, Clock.fixed(NOW, ZoneOffset.UTC), orderReservations);

    private final UUID picker = UUID.randomUUID();
    private final CurrentUser actor = new CurrentUser(picker, "picker",
            java.util.Set.of(com.stockflow.common.security.Role.WAREHOUSE_STAFF), java.util.Set.of(), DataScope.ALL,
            java.util.Set.of(), java.util.Set.of());
    private PickJpaEntity task;
    private final List<PickLineJpaEntity> stored = new ArrayList<>();

    @BeforeEach
    void aTaskBeingPicked() {
        task = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-0001", WAREHOUSE, PickStatus.PENDING);
        task.assign(picker, NOW.minusSeconds(120));
        task.start(picker, NOW.minusSeconds(60));
        when(picks.lockById(task.getId())).thenReturn(Optional.of(task));
        when(picks.findById(task.getId())).thenReturn(Optional.of(task));
        when(pickLines.findByPickIdOrderBySequenceNoAscIdAsc(task.getId())).thenAnswer(call -> List.copyOf(stored));
        when(orders.findById(task.getOrderId())).thenReturn(Optional.of(FulfillmentFixtures.orderWith(
                task.getOrderId(), OrderStatus.PICKING, List.of(), null)));
        when(itemControls.logistics(any())).thenAnswer(call -> new InventoryItemLogistics("X", 0, new ItemLogistics(
                "PCS", "8930000000017", null, null, null, null, null, null, null, null, 1, 1, null, false, null,
                false)));
    }

    private PickLineJpaEntity line(String sku, String location, int allocated, UUID reservation) {
        PickLineJpaEntity row = new PickLineJpaEntity(UUID.randomUUID(), task.getId(), UUID.randomUUID(), reservation,
                sku, location, "LOT-1", stored.size() + 1, allocated);
        stored.add(row);
        return row;
    }

    private void taskIs(PickStatus status) {
        task = new PickJpaEntity(task.getId(), null, task.getOrderId(), "SO-0001", WAREHOUSE, PickStatus.PENDING);
        task.assign(picker, NOW);
        switch (status) {
            case PICKING -> task.start(picker, NOW);
            case CANCELLED -> task.cancel();
            default -> { }
        }
        when(picks.lockById(task.getId())).thenReturn(Optional.of(task));
    }

    @Nested
    @DisplayName("planning the lines from the order's holds (07 step 3)")
    class Planning {

        private final UUID resA = UUID.randomUUID();
        private final UUID resB = UUID.randomUUID();

        private HoldView held(UUID reservation, String sku, String location, int qty, HoldView.Status status) {
            return new HoldView(reservation, sku, location, "LOT-1", qty, status, null, null);
        }

        private void locationsIn(UUID warehouse, String... codes) {
            for (String code : codes) {
                when(warehouses.findLocation(code)).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                        StorageLocationView.Kind.BIN, warehouse, code, null, null, null, true)));
            }
        }

        @Test
        @DisplayName("one line per live hold, in location order, with the held quantity as the allocation")
        void plansOneLinePerHold() {
            OrderSummary order = FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING,
                    List.of(FulfillmentFixtures.stockLine("SKU-A", resA), FulfillmentFixtures.stockLine("SKU-B", resB)),
                    null);
            when(inventory.holds(any())).thenReturn(List.of(held(resA, "SKU-A", "HCM-A-02", 3, HoldView.Status.HELD),
                    held(resB, "SKU-B", "HCM-A-01", 2, HoldView.Status.HELD)));
            locationsIn(WAREHOUSE, "HCM-A-01", "HCM-A-02");

            service.planLines(task, order);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PickLineJpaEntity>> saved = ArgumentCaptor.forClass(List.class);
            verify(pickLines).saveAll(saved.capture());
            assertThat(saved.getValue()).extracting(PickLineJpaEntity::getLocationCode)
                    .containsExactly("HCM-A-01", "HCM-A-02");
            assertThat(saved.getValue()).extracting(PickLineJpaEntity::getAllocatedQty).containsExactly(2, 3);
            assertThat(saved.getValue()).extracting(PickLineJpaEntity::getSequenceNo).containsExactly(1, 2);
        }

        @Test
        @DisplayName("a hold in a carton plans a line naming that carton (SCRUM-473)")
        void holdsOfPassesTheCarton() {
            OrderSummary order = FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING,
                    List.of(FulfillmentFixtures.stockLine("SKU-A", resA)), null);
            when(inventory.holds(any())).thenReturn(List.of(new HoldView(resA, "SKU-A", "HCM-A-01", "LOT-1", 10,
                    HoldView.Status.HELD, null, null, null, "LPN-20261028-000001", true)));
            locationsIn(WAREHOUSE, "HCM-A-01");

            service.planLines(task, order);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PickLineJpaEntity>> saved = ArgumentCaptor.forClass(List.class);
            verify(pickLines).saveAll(saved.capture());
            assertThat(saved.getValue()).singleElement().satisfies(row -> {
                assertThat(row.getLpn()).isEqualTo("LPN-20261028-000001");
                assertThat(row.isWholeCarton()).isTrue();
            });
        }

        @Test
        @DisplayName("a task that already has lines is left as it is")
        void planningIsIdempotent() {
            line("SKU-A", "HCM-A-01", 3, resA);

            service.planLines(task, FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING, List.of(), null));

            verify(pickLines, never()).saveAll(any());
            verifyNoInteractions(inventory);
        }

        @Test
        @DisplayName("a consumed or released hold has nothing left to pick")
        void deadHoldsAreSkipped() {
            OrderSummary order = FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING,
                    List.of(FulfillmentFixtures.stockLine("SKU-A", resA)), null);
            when(inventory.holds(any())).thenReturn(List.of(held(resA, "SKU-A", "HCM-A-01", 3,
                    HoldView.Status.CONSUMED)));
            locationsIn(WAREHOUSE, "HCM-A-01");

            service.planLines(task, order);

            verify(pickLines, never()).saveAll(any());
        }

        @Test
        @DisplayName("a hold in another warehouse is FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE")
        void holdOutsideTheWarehouse() {
            OrderSummary order = FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING,
                    List.of(FulfillmentFixtures.stockLine("SKU-A", resA)), null);
            when(inventory.holds(any())).thenReturn(List.of(held(resA, "SKU-A", "HN-A-01", 3, HoldView.Status.HELD)));
            locationsIn(OTHER_WAREHOUSE, "HN-A-01");

            assertThat(refusal(() -> service.planLines(task, order)).errorCode())
                    .isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
        }

        @Test
        @DisplayName("an order of print lines only has nothing to pick: holds are not even read (07 BR-06)")
        void printLinesAreNotPicked() {
            var print = new OrderSummary.LineSummary(UUID.randomUUID(), "CUP-PRINT", 100,
                    com.stockflow.common.domain.Money.vnd(1000), com.stockflow.common.domain.Money.vnd(100_000),
                    List.of(resA), UUID.randomUUID(), "abc");
            OrderSummary order = FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING, List.of(print), null);

            service.planLines(task, order);

            verify(inventory, never()).holds(any());
            verify(pickLines, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("closing the task needs every line picked (07 step 9)")
    class Closing {

        @Test
        @DisplayName("a task with open lines is PICK_LINES_INCOMPLETE")
        void openLinesBlockClosing() {
            line("SKU-A", "HCM-A-01", 3, UUID.randomUUID());

            assertThat(refusal(() -> service.requireAllPicked(task, FulfillmentFixtures.order(task.getOrderId(), null)))
                    .errorCode()).isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
        }

        @Test
        @DisplayName("a task with no lines yet, while the order still has a live hold, must be started to load them")
        void noLinesButSomethingToPick() {
            UUID res = UUID.randomUUID();
            OrderSummary order = FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING,
                    List.of(FulfillmentFixtures.stockLine("SKU-A", res)), null);
            when(inventory.holds(any())).thenReturn(List.of(new HoldView(res, "SKU-A", "HCM-A-01", "L", 2,
                    HoldView.Status.HELD, null, null)));
            when(warehouses.findLocation("HCM-A-01")).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.requireAllPicked(task, order)).errorCode())
                    .isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
        }

        @Test
        @DisplayName("a task with no lines and nothing live to pick may close")
        void noLinesNothingToPick() {
            service.requireAllPicked(task, FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING,
                    List.of(), null));
        }

        @Test
        @DisplayName("a task whose lines are all picked may close")
        void allPicked() {
            PickLineJpaEntity row = line("SKU-A", "HCM-A-01", 3, UUID.randomUUID());
            row.apply(3, PickLineStatus.PICKED, picker, NOW, null, "HCM-A-01", "SKU-A", 0, null, null, null);

            service.requireAllPicked(task, FulfillmentFixtures.order(task.getOrderId(), null));
        }
    }

    @Test
    @DisplayName("cancelling the order cancels the lines nobody picked and leaves the picked ones for the put-back")
    void cancelOpenLinesKeepsPickedOnes() {
        PickLineJpaEntity open = line("SKU-A", "HCM-A-01", 3, UUID.randomUUID());
        PickLineJpaEntity picked = line("SKU-B", "HCM-A-02", 2, UUID.randomUUID());
        picked.apply(2, PickLineStatus.PICKED, picker, NOW, NOW, "HCM-A-02", "SKU-B", 0, null, null, null);

        service.cancelOpenLines(task);

        assertThat(open.getStatus()).isEqualTo(PickLineStatus.CANCELLED);
        assertThat(picked.getStatus()).isEqualTo(PickLineStatus.PICKED);
        verify(pickLines).save(open);
        verify(pickLines, never()).save(picked);
    }

    @Nested
    @DisplayName("confirming a line by scan (07 step 6, BR-04)")
    class Confirming {

        private final UUID reservation = UUID.randomUUID();
        private PickLineJpaEntity row;

        @BeforeEach
        void aLine() {
            row = line("SKU-A", "HCM-A-01", 3, reservation);
        }

        @Test
        @DisplayName("the exact allocation scanned at its bin picks the line and deducts its hold in the same call")
        void picksAndDeducts() {
            var view = service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-A", 3, actor);

            assertThat(view.status()).isEqualTo("PICKED");
            assertThat(view.pickedQty()).isEqualTo(3);
            assertThat(row.getStatus()).isEqualTo(PickLineStatus.PICKED);
            assertThat(row.getConsumedAt()).isEqualTo(NOW);
            verify(consumption).consumeForPick(reservation, row.getId(), picker);
            verify(pickLines).save(row);
        }

        @Test
        @DisplayName("the SKU's barcode may be scanned instead of the SKU, and the view carries it")
        void barcodeIsAccepted() {
            var view = service.confirm(task.getId(), row.getId(), "HCM-A-01", "8930000000017", 3, actor);

            assertThat(view.barcode()).isEqualTo("8930000000017");
            assertThat(row.getStatus()).isEqualTo(PickLineStatus.PICKED);
        }

        @Test
        @DisplayName("the same scan again answers with the line and deducts nothing twice")
        void repeatedScanIsIdempotent() {
            service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-A", 3, actor);

            var again = service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-A", 3, actor);

            assertThat(again.status()).isEqualTo("PICKED");
            verify(consumption, org.mockito.Mockito.times(1)).consumeForPick(any(), any(), any());
        }

        @Test
        @DisplayName("more than the allocation is PICK_QUANTITY_EXCEEDS_ALLOCATION; fewer is PICK_QUANTITY_SHORT")
        void quantityMustMatchTheAllocation() {
            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-A", 4, actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_QUANTITY_EXCEEDS_ALLOCATION);
            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-A", 2, actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_QUANTITY_SHORT);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("another location is PICK_LOCATION_MISMATCH; another item is PICK_ITEM_MISMATCH")
        void scansMustMatch() {
            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-09", "SKU-A", 3, actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-Z", 3, actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
        }

        @Test
        @DisplayName("someone who is not the assignee finds no such task: NOT_FOUND")
        void onlyTheAssigneePicks() {
            CurrentUser other = FulfillmentFixtures.user();

            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-A", 3, other))
                    .errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("a task that does not exist is NOT_FOUND")
        void unknownTask() {
            UUID id = UUID.randomUUID();
            when(picks.lockById(id)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.confirm(id, row.getId(), "HCM-A-01", "SKU-A", 3, actor)).errorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a task of a warehouse the caller is not assigned to is OUT_OF_DATA_SCOPE")
        void outOfScope() {
            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-A", 3, actor))
                            .errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a cancelled or closed order's task is no longer workable: PICK_TASK_STATE_CONFLICT")
        void closedOrderStopsPicking() {
            when(orders.findById(task.getOrderId())).thenReturn(Optional.of(FulfillmentFixtures.orderWith(
                    task.getOrderId(), OrderStatus.CANCELLED, List.of(), null)));

            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-A", 3, actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("a line is confirmed only while the task is PICKING")
        void taskMustBePicking() {
            taskIs(PickStatus.PENDING);

            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "SKU-A", 3, actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a line without a hold is picked without touching inventory")
        void lineWithoutHold() {
            PickLineJpaEntity free = line("SKU-F", "HCM-A-03", 1, null);

            service.confirm(task.getId(), free.getId(), "HCM-A-03", "SKU-F", 1, actor);

            assertThat(free.getStatus()).isEqualTo(PickLineStatus.PICKED);
            verifyNoInteractions(consumption);
        }
    }

    @Nested
    @DisplayName("a short pick stops the order without cancelling it (X-SHORT)")
    class ReportingShort {

        private final UUID reservation = UUID.randomUUID();
        private PickLineJpaEntity row;

        @BeforeEach
        void aLine() {
            row = line("SKU-A", "HCM-A-01", 5, reservation);
            when(consumption.requestRecount(eq(reservation), eq(row.getId()), anyInt()))
                    .thenReturn(new ShortPickRecount(UUID.randomUUID(), "CC-1", true));
        }

        @Test
        @DisplayName("the line records the shortfall, nothing is deducted, the order goes ON_HOLD for INVENTORY and the bin is recounted")
        void shortStopsTheOrder() {
            var view = service.reportShort(task.getId(), row.getId(), "HCM-A-01", 3, actor);

            assertThat(view.status()).isEqualTo("SHORT");
            assertThat(view.shortQty()).isEqualTo(2);
            assertThat(view.pickedQty()).isZero();
            verify(consumption, never()).consumeForPick(any(), any(), any());
            verify(orders).putOnHold(eq(task.getOrderId()), eq(HoldReason.INVENTORY), any(), eq(picker));
            verify(consumption).requestRecount(reservation, row.getId(), 3);
        }

        @Test
        @DisplayName("an order already ON_HOLD stays as it is: a hold is not stacked on a hold")
        void noHoldOnAHold() {
            when(orders.findById(task.getOrderId())).thenReturn(Optional.of(FulfillmentFixtures.orderWith(
                    task.getOrderId(), OrderStatus.ON_HOLD, List.of(), HoldReason.INVENTORY)));

            service.reportShort(task.getId(), row.getId(), "HCM-A-01", 0, actor);

            verify(orders, never()).putOnHold(any(), any(), any(), any());
        }

        @Test
        @DisplayName("finding as many as allocated is not short: VALIDATION_FAILED")
        void notShort() {
            assertThat(refusal(() -> service.reportShort(task.getId(), row.getId(), "HCM-A-01", 5, actor)).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("the same report again answers with the line and holds the order once")
        void repeatedReport() {
            service.reportShort(task.getId(), row.getId(), "HCM-A-01", 3, actor);

            var again = service.reportShort(task.getId(), row.getId(), "HCM-A-01", 3, actor);

            assertThat(again.status()).isEqualTo("SHORT");
            verify(orders, org.mockito.Mockito.times(1)).putOnHold(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("reallocating a short line (X-SHORT, step 03)")
    class Reallocating {

        private final UUID reservation = UUID.randomUUID();
        private PickLineJpaEntity row;

        @BeforeEach
        void aShortLine() {
            row = line("SKU-A", "HCM-A-01", 5, reservation);
            row.apply(0, PickLineStatus.SHORT, null, null, null, "HCM-A-01", null, 0, null, null, null);
            row.recordShort(2);
        }

        private void inventoryAllocates(String location) {
            when(inventory.reallocate(any())).thenReturn(List.of(new StockReservation(UUID.randomUUID(),
                    UUID.randomUUID(), location, "LOT-2", 5)));
            when(warehouses.findLocation(location)).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                    StorageLocationView.Kind.BIN, WAREHOUSE, location, null, null, null, true)));
        }

        @Test
        @DisplayName("a line that is not SHORT is PICK_LINE_NOT_OPEN")
        void onlyShortLinesAreReallocated() {
            PickLineJpaEntity open = line("SKU-B", "HCM-A-02", 1, UUID.randomUUID());

            assertThat(refusal(() -> service.reallocateShort(task.getId(), open.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
            verifyNoInteractions(inventory);
        }

        @Test
        @DisplayName("a line the task does not have is NOT_FOUND")
        void unknownLine() {
            assertThat(refusal(() -> service.reallocateShort(task.getId(), UUID.randomUUID(), actor)).errorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("the short line is superseded by one new line per allocation, the order's hold follows, the order resumes")
        void reallocatesAndResumes() {
            inventoryAllocates("HCM-B-01");
            when(orders.findById(task.getOrderId())).thenReturn(Optional.of(FulfillmentFixtures.orderWith(
                    task.getOrderId(), OrderStatus.ON_HOLD, List.of(), HoldReason.INVENTORY)));

            var added = service.reallocateShort(task.getId(), row.getId(), actor);

            assertThat(added).singleElement().satisfies(v -> {
                assertThat(v.locationCode()).isEqualTo("HCM-B-01");
                assertThat(v.allocatedQty()).isEqualTo(5);
                assertThat(v.sequenceNo()).isEqualTo(2);
            });
            assertThat(row.getStatus()).isEqualTo(PickLineStatus.CANCELLED);
            verify(orderReservations).replaceHold(eq(task.getOrderId()), eq(reservation), any());
            verify(orders).resolveHold(eq(task.getOrderId()), any(), eq(picker));
        }

        @Test
        @DisplayName("an order held for another reason than INVENTORY is not resumed")
        void otherHoldsAreLeftAlone() {
            inventoryAllocates("HCM-B-01");
            when(orders.findById(task.getOrderId())).thenReturn(Optional.of(FulfillmentFixtures.orderWith(
                    task.getOrderId(), OrderStatus.ON_HOLD, List.of(), HoldReason.PAYMENT)));

            service.reallocateShort(task.getId(), row.getId(), actor);

            verify(orders, never()).resolveHold(any(), any(), any());
        }

        @Test
        @DisplayName("while another line is still SHORT the order stays held")
        void waitsForTheOtherShortLines() {
            inventoryAllocates("HCM-B-01");
            PickLineJpaEntity second = line("SKU-B", "HCM-A-02", 4, UUID.randomUUID());
            second.apply(0, PickLineStatus.SHORT, null, null, null, "HCM-A-02", null, 0, null, null, null);
            second.recordShort(4);

            service.reallocateShort(task.getId(), row.getId(), actor);

            verify(orders, never()).resolveHold(any(), any(), any());
        }

        @Test
        @DisplayName("an allocation in another warehouse is FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE")
        void allocationMustStayInTheWarehouse() {
            when(inventory.reallocate(any())).thenReturn(List.of(new StockReservation(UUID.randomUUID(),
                    UUID.randomUUID(), "HN-B-01", "LOT-2", 5)));
            when(warehouses.findLocation("HN-B-01")).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                    StorageLocationView.Kind.BIN, OTHER_WAREHOUSE, "HN-B-01", null, null, null, true)));

            assertThat(refusal(() -> service.reallocateShort(task.getId(), row.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
        }
    }

    @Nested
    @DisplayName("putting the goods of a cancelled order back (07 BR-05)")
    class PuttingBack {

        private final UUID reservation = UUID.randomUUID();
        private PickLineJpaEntity row;

        @BeforeEach
        void aCancelledTaskWithAPickedLine() {
            task = new PickJpaEntity(task.getId(), null, task.getOrderId(), "SO-0001", WAREHOUSE, PickStatus.PENDING);
            task.assign(picker, NOW);
            task.start(picker, NOW);
            task.cancel();
            when(picks.lockById(task.getId())).thenReturn(Optional.of(task));
            row = line("SKU-A", "HCM-A-01", 3, reservation);
            row.apply(3, PickLineStatus.PICKED, picker, NOW, NOW, "HCM-A-01", "SKU-A", 0, null, null, null);
        }

        private void binOf(UUID warehouse, boolean usable, StorageLocationView.Kind kind) {
            when(warehouses.findLocation("HCM-A-05")).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                    kind, warehouse, "HCM-A-05", null, null, null, usable)));
        }

        @Test
        @DisplayName("everything the line picked goes back onto a bin of the task's warehouse, in inventory too")
        void putsBack() {
            binOf(WAREHOUSE, true, StorageLocationView.Kind.BIN);

            var view = service.putBack(task.getId(), row.getId(), " HCM-A-05 ", actor);

            assertThat(view.status()).isEqualTo("PUT_BACK");
            assertThat(view.putBackQty()).isEqualTo(3);
            assertThat(view.putBackLocationCode()).isEqualTo("HCM-A-05");
            ArgumentCaptor<PutBackCommand> command = ArgumentCaptor.forClass(PutBackCommand.class);
            verify(consumption).putBack(command.capture());
            assertThat(command.getValue().reservationId()).isEqualTo(reservation);
            assertThat(command.getValue().sku()).isEqualTo("SKU-A");
        }

        @Test
        @DisplayName("onto an area, an unusable bin, a bin of another warehouse or an unknown code: PUT_BACK_LOCATION_INVALID")
        void targetMustBeAUsableBinOfTheWarehouse() {
            binOf(WAREHOUSE, true, StorageLocationView.Kind.AREA);
            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
            binOf(WAREHOUSE, false, StorageLocationView.Kind.BIN);
            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
            binOf(OTHER_WAREHOUSE, true, StorageLocationView.Kind.BIN);
            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
            when(warehouses.findLocation("HCM-A-05")).thenReturn(Optional.empty());
            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("a task that was not started before it was cancelled has nothing to put back")
        void nothingToPutBack() {
            task = new PickJpaEntity(task.getId(), null, task.getOrderId(), "SO-0001", WAREHOUSE, PickStatus.PENDING);
            task.cancel();
            when(picks.lockById(task.getId())).thenReturn(Optional.of(task));
            binOf(WAREHOUSE, true, StorageLocationView.Kind.BIN);

            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
        }

        @Test
        @DisplayName("a picked line that holds no reservation cannot be put back: PUT_BACK_NOT_REQUIRED")
        void lineWithoutHold() {
            PickLineJpaEntity free = line("SKU-F", "HCM-A-02", 1, null);
            free.apply(1, PickLineStatus.PICKED, picker, NOW, null, "HCM-A-02", "SKU-F", 0, null, null, null);
            binOf(WAREHOUSE, true, StorageLocationView.Kind.BIN);

            assertThat(refusal(() -> service.putBack(task.getId(), free.getId(), "HCM-A-05", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
        }

        @Test
        @DisplayName("putting the same line back again answers with the line and moves no stock twice")
        void putBackIsIdempotent() {
            binOf(WAREHOUSE, true, StorageLocationView.Kind.BIN);
            service.putBack(task.getId(), row.getId(), "HCM-A-05", actor);

            var again = service.putBack(task.getId(), row.getId(), "HCM-A-05", actor);

            assertThat(again.status()).isEqualTo("PUT_BACK");
            verify(consumption, org.mockito.Mockito.times(1)).putBack(any());
        }

        @Test
        @DisplayName("the put-back queue refuses a warehouse the caller is not assigned to")
        void queueIsScoped() {
            WithCurrentUser.run(FulfillmentFixtures.userOf(WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.putBacks(OTHER_WAREHOUSE, 0, 20, actor)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }
    }

    @Test
    @DisplayName("a task's lines are visible to its assignee and refused to anyone else without oversight")
    void linesAreVisibleToTheAssignee() {
        PickLineJpaEntity row = line("SKU-A", "HCM-A-01", 3, UUID.randomUUID());
        when(pickLines.findByPickId(eq(task.getId()), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(row), org.springframework.data.domain.PageRequest.of(0, 20), 1));

        var page = service.lines(task.getId(), 0, 20, actor);

        assertThat(page.items()).singleElement().satisfies(v -> {
            assertThat(v.sku()).isEqualTo("SKU-A");
            assertThat(v.barcode()).isEqualTo("8930000000017");
        });
        assertThat(refusal(() -> service.lines(task.getId(), 0, 20, FulfillmentFixtures.user())).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
