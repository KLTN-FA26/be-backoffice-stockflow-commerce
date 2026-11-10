package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.domain.PickLineStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.domain.ShortPickReason;
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
            consumption, warehouses, Clock.fixed(NOW, ZoneOffset.UTC), orderReservations,
            mock(com.stockflow.inventory.consumption.PickDamageReporting.class),
            org.springframework.transaction.support.TransactionOperations.withoutTransaction(),
            mock(com.stockflow.fulfillment.internal.repository.PackJpaRepository.class));

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
                "PCS", "8930000000017", null, null, null, null, null, null, null, null, 1, 1, null, false, null)));
    }

    private PickLineJpaEntity line(String sku, String location, int allocated, UUID reservation) {
        PickLineJpaEntity row = new PickLineJpaEntity(UUID.randomUUID(), task.getId(), UUID.randomUUID(), reservation,
                sku, location, "LOT-1", stored.size() + 1, allocated, lpnOf(sku), true);
        stored.add(row);
        return row;
    }

    /** The carton of a SKU's line in these tests: every line is one carton, named by its LPN (4C-4). */
    private static String lpnOf(String sku) {
        return "LPN-" + sku;
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
            return new HoldView(reservation, sku, location, "LOT-1", qty, status, null, null, null, lpnOf(sku), true);
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
        @DisplayName("a live hold on no carton is PICK_HOLD_NOT_IN_CARTON: a line is picked by its carton's LPN, so none is planned")
        void looseHoldIsRefused() {
            OrderSummary order = FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING,
                    List.of(FulfillmentFixtures.stockLine("SKU-A", resA)), null);
            when(inventory.holds(any())).thenReturn(List.of(new HoldView(resA, "SKU-A", "HCM-A-01", "LOT-1", 3,
                    HoldView.Status.HELD, null, null)));
            locationsIn(WAREHOUSE, "HCM-A-01");

            assertThat(refusal(() -> service.planLines(task, order)).errorCode())
                    .isEqualTo(ErrorCode.PICK_HOLD_NOT_IN_CARTON);
            verify(pickLines, never()).saveAll(any());
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
        @DisplayName("a print line is planned like any line: one pick line per live hold (07 BR-06)")
        void printLinesArePicked() {
            var print = new OrderSummary.LineSummary(UUID.randomUUID(), "CUP-PRINT", 100,
                    com.stockflow.common.domain.Money.vnd(1000), com.stockflow.common.domain.Money.vnd(100_000),
                    List.of(resA), UUID.randomUUID(), "abc");
            OrderSummary order = FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING, List.of(print), null);
            when(inventory.holds(any())).thenReturn(List.of(held(resA, "CUP-PRINT", "HCM-A-01", 100,
                    HoldView.Status.HELD)));
            locationsIn(task.getWarehouseId(), "HCM-A-01");

            service.planLines(task, order);

            verify(inventory).holds(any());
            verify(pickLines).saveAll(any());
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
    @DisplayName("confirming a line by scan (07 section 4.2 step 5, BR-04, BR-10)")
    class Confirming {

        private final UUID reservation = UUID.randomUUID();
        private PickLineJpaEntity row;

        @BeforeEach
        void aLine() {
            row = line("SKU-A", "HCM-A-01", 3, reservation);
        }

        @Test
        @DisplayName("the bin and the carton's LPN pick the whole carton and deduct its hold in the same call")
        void picksAndDeducts() {
            var view = service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-A", actor);

            assertThat(view.status()).isEqualTo("PICKED");
            assertThat(view.pickedQty()).as("the whole carton, no quantity typed").isEqualTo(3);
            assertThat(view.lpn()).isEqualTo("LPN-SKU-A");
            assertThat(row.getStatus()).isEqualTo(PickLineStatus.PICKED);
            assertThat(row.getScannedCode()).isEqualTo("LPN-SKU-A");
            assertThat(row.getConsumedAt()).isEqualTo(NOW);
            verify(consumption).consumeForPick(reservation, row.getId(), picker);
            verify(pickLines).save(row);
        }

        @Test
        @DisplayName("the LPN in any case, with spaces around it, is the same carton")
        void lpnInAnyCase() {
            service.confirm(task.getId(), row.getId(), " hcm-a-01 ", " lpn-sku-a ", actor);

            assertThat(row.getStatus()).isEqualTo(PickLineStatus.PICKED);
            verify(consumption).consumeForPick(reservation, row.getId(), picker);
        }

        @Test
        @DisplayName("the SKU, its barcode and another carton's LPN are PICK_ITEM_MISMATCH: nothing is deducted")
        void onlyTheLinesLpnIsAccepted() {
            for (String scan : new String[] {"SKU-A", "8930000000017", "LPN-SKU-Z", "", null}) {
                assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", scan, actor))
                        .errorCode()).as("scan %s", scan).isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
            }
            assertThat(row.getStatus()).isEqualTo(PickLineStatus.OPEN);
            verifyNoInteractions(consumption);
            verify(pickLines, never()).save(any());
        }

        @Test
        @DisplayName("the same scan again answers with the line and deducts nothing twice")
        void repeatedScanIsIdempotent() {
            service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-A", actor);

            var again = service.confirm(task.getId(), row.getId(), "HCM-A-01", "lpn-sku-a", actor);

            assertThat(again.status()).isEqualTo("PICKED");
            verify(consumption, org.mockito.Mockito.times(1)).consumeForPick(any(), any(), any());
        }

        @Test
        @DisplayName("another location is PICK_LOCATION_MISMATCH, checked before the carton")
        void locationMustMatch() {
            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-09", "LPN-SKU-A", actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-09", "LPN-SKU-Z", actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("someone who is not the assignee finds no such task: NOT_FOUND")
        void onlyTheAssigneePicks() {
            CurrentUser other = FulfillmentFixtures.user();

            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-A", other))
                    .errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("a task that does not exist is NOT_FOUND")
        void unknownTask() {
            UUID id = UUID.randomUUID();
            when(picks.lockById(id)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.confirm(id, row.getId(), "HCM-A-01", "LPN-SKU-A", actor)).errorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a task of a warehouse the caller is not assigned to is OUT_OF_DATA_SCOPE")
        void outOfScope() {
            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-A", actor))
                            .errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a cancelled or closed order's task is no longer workable: PICK_TASK_STATE_CONFLICT")
        void closedOrderStopsPicking() {
            when(orders.findById(task.getOrderId())).thenReturn(Optional.of(FulfillmentFixtures.orderWith(
                    task.getOrderId(), OrderStatus.CANCELLED, List.of(), null)));

            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-A", actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("a line is confirmed only while the task is PICKING")
        void taskMustBePicking() {
            taskIs(PickStatus.PENDING);

            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-A", actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a line with a carton but no hold fails loudly: IllegalStateException, nothing deducted or saved")
        void lineWithoutHold() {
            PickLineJpaEntity free = line("SKU-F", "HCM-A-03", 1, null);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    service.confirm(task.getId(), free.getId(), "HCM-A-03", "LPN-SKU-F", actor))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("has no hold");

            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("a line of before cartons, with no LPN, can be confirmed by no scan: PICK_LINE_NOT_OPEN")
        void lineWithoutCarton() {
            PickLineJpaEntity history = new PickLineJpaEntity(UUID.randomUUID(), task.getId(), UUID.randomUUID(),
                    UUID.randomUUID(), "SKU-H", "HCM-A-04", "LOT-1", 9, 2);
            stored.add(history);

            assertThat(refusal(() -> service.confirm(task.getId(), history.getId(), "HCM-A-04", "SKU-H", actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
            verifyNoInteractions(consumption);
        }
    }

    @Nested
    @DisplayName("a short pick stops the order without cancelling it (X-SHORT, 07 section 4.4 step 9, BR-09)")
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
        @DisplayName("the carton is short whole with its reason, nothing is deducted, the order goes ON_HOLD for INVENTORY and a blind count of found = 0 is asked")
        void shortStopsTheOrder() {
            var view = service.reportShort(task.getId(), row.getId(), "HCM-A-01", ShortPickReason.NOT_FOUND, actor);

            assertThat(view.status()).isEqualTo("SHORT");
            assertThat(view.shortQty()).isEqualTo(5);
            assertThat(view.shortReason()).isEqualTo(ShortPickReason.NOT_FOUND);
            assertThat(view.pickedQty()).isZero();
            assertThat(row.getShortReason()).isEqualTo(ShortPickReason.NOT_FOUND);
            verify(consumption, never()).consumeForPick(any(), any(), any());
            ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
            verify(orders).putOnHold(eq(task.getOrderId()), eq(HoldReason.INVENTORY), note.capture(), eq(picker));
            assertThat(note.getValue()).contains("LPN-SKU-A").contains("NOT_FOUND");
            verify(consumption).requestRecount(reservation, row.getId(), 0);
        }

        @Test
        @DisplayName("DAMAGED is a reason like NOT_FOUND: same stop, same blind count, the reason on the line and the hold note")
        void damagedReason() {
            var view = service.reportShort(task.getId(), row.getId(), "HCM-A-01", ShortPickReason.DAMAGED, actor);

            assertThat(view.shortReason()).isEqualTo(ShortPickReason.DAMAGED);
            ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
            verify(orders).putOnHold(eq(task.getOrderId()), eq(HoldReason.INVENTORY), note.capture(), eq(picker));
            assertThat(note.getValue()).contains("DAMAGED");
            verify(consumption).requestRecount(reservation, row.getId(), 0);
        }

        @Test
        @DisplayName("an order already ON_HOLD stays as it is: a hold is not stacked on a hold")
        void noHoldOnAHold() {
            when(orders.findById(task.getOrderId())).thenReturn(Optional.of(FulfillmentFixtures.orderWith(
                    task.getOrderId(), OrderStatus.ON_HOLD, List.of(), HoldReason.INVENTORY)));

            service.reportShort(task.getId(), row.getId(), "HCM-A-01", ShortPickReason.NOT_FOUND, actor);

            verify(orders, never()).putOnHold(any(), any(), any(), any());
        }

        @Test
        @DisplayName("the wrong bin is PICK_LOCATION_MISMATCH and nothing is stopped or counted")
        void wrongBin() {
            assertThat(refusal(() -> service.reportShort(task.getId(), row.getId(), "HCM-A-09",
                    ShortPickReason.NOT_FOUND, actor)).errorCode()).isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
            verifyNoInteractions(consumption);
            verify(orders, never()).putOnHold(any(), any(), any(), any());
            assertThat(row.getStatus()).isEqualTo(PickLineStatus.OPEN);
        }

        @Test
        @DisplayName("the same report again answers with the line and holds the order and counts once")
        void repeatedReport() {
            service.reportShort(task.getId(), row.getId(), "HCM-A-01", ShortPickReason.NOT_FOUND, actor);

            var again = service.reportShort(task.getId(), row.getId(), "HCM-A-01", ShortPickReason.NOT_FOUND, actor);

            assertThat(again.status()).isEqualTo("SHORT");
            verify(orders, org.mockito.Mockito.times(1)).putOnHold(any(), any(), any(), any());
            verify(consumption, org.mockito.Mockito.times(1)).requestRecount(any(), any(), anyInt());
        }

        @Test
        @DisplayName("the same report with another reason is PICK_LINE_NOT_OPEN: the first reason stands")
        void anotherReason() {
            service.reportShort(task.getId(), row.getId(), "HCM-A-01", ShortPickReason.NOT_FOUND, actor);

            assertThat(refusal(() -> service.reportShort(task.getId(), row.getId(), "HCM-A-01",
                    ShortPickReason.DAMAGED, actor)).errorCode()).isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
            assertThat(row.getShortReason()).isEqualTo(ShortPickReason.NOT_FOUND);
            verify(orders, org.mockito.Mockito.times(1)).putOnHold(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a line with a carton but no hold fails loudly: no recount asked, the order is not held")
        void lineWithoutHold() {
            PickLineJpaEntity free = line("SKU-F", "HCM-A-03", 2, null);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reportShort(task.getId(), free.getId(),
                    "HCM-A-03", ShortPickReason.NOT_FOUND, actor))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("has no hold");

            verifyNoInteractions(consumption);
            // putOnHold is asked before the guard, inside the transaction: it rolls back with the exception.
        }

        @Test
        @DisplayName("only the assignee reports a short pick")
        void onlyTheAssignee() {
            assertThat(refusal(() -> service.reportShort(task.getId(), row.getId(), "HCM-A-01",
                    ShortPickReason.NOT_FOUND, FulfillmentFixtures.user())).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            verifyNoInteractions(consumption);
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
            row.recordShort(5, ShortPickReason.NOT_FOUND);
        }

        private void inventoryAllocates(String location) {
            UUID hold = UUID.randomUUID();
            when(inventory.reallocate(any())).thenReturn(List.of(new StockReservation(hold,
                    UUID.randomUUID(), location, "LOT-2", 5)));
            when(inventory.holds(any())).thenReturn(List.of(new HoldView(hold, "SKU-A", location, "LOT-2", 5,
                    HoldView.Status.HELD, null, null, null, "LPN-NEW", true)));
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
                assertThat(v.lpn()).as("the new line is the new hold's carton").isEqualTo("LPN-NEW");
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
            second.recordShort(4, ShortPickReason.DAMAGED);

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
    @DisplayName("putting the cartons of a cancelled order back (07 BR-05, section 4.5 step 14)")
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
            row.apply(3, PickLineStatus.PICKED, picker, NOW, NOW, "HCM-A-01", "LPN-SKU-A", 0, null, null, null);
        }

        private void binOf(UUID warehouse, boolean usable, StorageLocationView.Kind kind) {
            when(warehouses.findLocation("HCM-A-05")).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                    kind, warehouse, "HCM-A-05", null, null, null, usable)));
        }

        @Test
        @DisplayName("the whole carton, scanned by its LPN, goes back onto a bin of the task's warehouse, in inventory too")
        void putsBack() {
            binOf(WAREHOUSE, true, StorageLocationView.Kind.BIN);

            var view = service.putBack(task.getId(), row.getId(), " HCM-A-05 ", " lpn-sku-a ", actor);

            assertThat(view.status()).isEqualTo("PUT_BACK");
            assertThat(view.putBackQty()).isEqualTo(3);
            assertThat(view.putBackLocationCode()).isEqualTo("HCM-A-05");
            ArgumentCaptor<PutBackCommand> command = ArgumentCaptor.forClass(PutBackCommand.class);
            verify(consumption).putBack(command.capture());
            assertThat(command.getValue().reservationId()).isEqualTo(reservation);
            assertThat(command.getValue().pickLineId()).isEqualTo(row.getId());
            assertThat(command.getValue().lpn()).as("the carton, as the line names it").isEqualTo("LPN-SKU-A");
            assertThat(command.getValue().locationCode()).isEqualTo("HCM-A-05");
            assertThat(command.getValue().actorId()).isEqualTo(picker);
        }

        @Test
        @DisplayName("another carton's LPN, the SKU or nothing is PICK_ITEM_MISMATCH: inventory is not asked")
        void wrongScan() {
            binOf(WAREHOUSE, true, StorageLocationView.Kind.BIN);

            for (String scan : new String[] {"LPN-SKU-Z", "SKU-A", "8930000000017", "", null}) {
                assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", scan, actor))
                        .errorCode()).as("scan %s", scan).isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
            }
            assertThat(row.getStatus()).isEqualTo(PickLineStatus.PICKED);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("a carton part of which went to quarantine at packing is STOCK_PACKAGE_TAKEN_WHOLE: inventory is not asked")
        void partlyDamagedCarton() {
            row.recordDamageAfterPick(1);
            binOf(WAREHOUSE, true, StorageLocationView.Kind.BIN);

            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", "LPN-SKU-A", actor))
                    .errorCode()).isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(row.getStatus()).isEqualTo(PickLineStatus.PICKED);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("onto an area, an unusable bin, a bin of another warehouse or an unknown code: PUT_BACK_LOCATION_INVALID")
        void targetMustBeAUsableBinOfTheWarehouse() {
            binOf(WAREHOUSE, true, StorageLocationView.Kind.AREA);
            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", "LPN-SKU-A", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
            binOf(WAREHOUSE, false, StorageLocationView.Kind.BIN);
            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", "LPN-SKU-A", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
            binOf(OTHER_WAREHOUSE, true, StorageLocationView.Kind.BIN);
            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", "LPN-SKU-A", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
            when(warehouses.findLocation("HCM-A-05")).thenReturn(Optional.empty());
            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", "LPN-SKU-A", actor)).errorCode())
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

            assertThat(refusal(() -> service.putBack(task.getId(), row.getId(), "HCM-A-05", "LPN-SKU-A", actor)).errorCode())
                    .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
        }

        @Test
        @DisplayName("a picked line that holds no reservation cannot be put back: a RuntimeException, inventory not asked")
        void lineWithoutHold() {
            PickLineJpaEntity free = line("SKU-F", "HCM-A-02", 1, null);
            free.apply(1, PickLineStatus.PICKED, picker, NOW, null, "HCM-A-02", "SKU-F", 0, null, null, null);
            binOf(WAREHOUSE, true, StorageLocationView.Kind.BIN);

            // PutBackCommand refuses a null hold (NullPointerException) - loud, though not as worded as requireHold.
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    service.putBack(task.getId(), free.getId(), "HCM-A-05", "LPN-SKU-F", actor))
                    .isInstanceOf(RuntimeException.class);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("putting the same line back again answers with the line and moves no stock twice")
        void putBackIsIdempotent() {
            binOf(WAREHOUSE, true, StorageLocationView.Kind.BIN);
            service.putBack(task.getId(), row.getId(), "HCM-A-05", "LPN-SKU-A", actor);

            var again = service.putBack(task.getId(), row.getId(), "HCM-A-05", "LPN-SKU-A", actor);

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

    @Nested
    @DisplayName("a STAGING task: staging, moving to dispatch, topping up, putting back (team plan 4C-5)")
    class Staging {

        private static final String AREA = "HCM-STG01";
        private final UUID reservation = UUID.randomUUID();

        /** The task of the enclosing test, redrawn with a STAGING destination and walked to {@code status}. */
        private void stagingTaskIs(PickStatus status) {
            task = new PickJpaEntity(task.getId(), null, task.getOrderId(), "SO-0001", WAREHOUSE, PickStatus.PENDING,
                    com.stockflow.fulfillment.api.PickDestination.STAGING, AREA);
            task.assign(picker, NOW);
            if (status != PickStatus.PENDING) {
                task.start(picker, NOW);
            }
            if (status == PickStatus.STAGED) {
                task.complete(picker, NOW);
            }
            if (status == PickStatus.CANCELLED) {
                task.cancel();
            }
            when(picks.lockById(task.getId())).thenReturn(Optional.of(task));
            when(picks.findById(task.getId())).thenReturn(Optional.of(task));
        }

        /** A line staged by the task: STAGED, at the staging area, not deducted. */
        private PickLineJpaEntity stagedLine(String sku, String bin, int qty, UUID hold) {
            PickLineJpaEntity row = line(sku, bin, qty, hold);
            row.apply(qty, PickLineStatus.STAGED, picker, NOW, null, bin, lpnOf(sku), 0, null, null, null);
            row.recordStaged(NOW, AREA);
            return row;
        }

        @Test
        @DisplayName("a confirmation stages the carton: the line turns STAGED at the area, inventory is asked to move it, nothing is deducted")
        void confirmStages() {
            stagingTaskIs(PickStatus.PICKING);
            PickLineJpaEntity row = line("SKU-A", "HCM-A-01", 3, reservation);

            var view = service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-A", actor);

            assertThat(view.status()).isEqualTo("STAGED");
            assertThat(view.stagedLocationCode()).isEqualTo(AREA);
            assertThat(view.stagedAt()).isEqualTo(NOW);
            assertThat(row.getStatus()).isEqualTo(PickLineStatus.STAGED);
            assertThat(row.getConsumedAt()).as("not deducted until the task moves to dispatch").isNull();
            verify(consumption).stage(new com.stockflow.inventory.consumption.StageCommand(row.getId(), reservation,
                    "LPN-SKU-A", AREA, picker));
            verify(consumption, never()).consumeForPick(any(), any(), any());
            verify(pickLines).save(row);
        }

        @Test
        @DisplayName("the same scan again stages nothing twice")
        void confirmRepeat() {
            stagingTaskIs(PickStatus.PICKING);
            PickLineJpaEntity row = line("SKU-A", "HCM-A-01", 3, reservation);
            service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-A", actor);

            var again = service.confirm(task.getId(), row.getId(), "HCM-A-01", "lpn-sku-a", actor);

            assertThat(again.status()).isEqualTo("STAGED");
            verify(consumption, org.mockito.Mockito.times(1)).stage(any());
        }

        @Test
        @DisplayName("a wrong carton is PICK_ITEM_MISMATCH and inventory is not asked to move anything")
        void wrongScanStagesNothing() {
            stagingTaskIs(PickStatus.PICKING);
            PickLineJpaEntity row = line("SKU-A", "HCM-A-01", 3, reservation);

            assertThat(refusal(() -> service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-Z", actor))
                    .errorCode()).isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
            verifyNoInteractions(consumption);
        }

        @Test
        @DisplayName("a task moved to dispatch picks a top-up line the ordinary way: PICKED and deducted at once")
        void movedTaskPicksDirectly() {
            stagingTaskIs(PickStatus.STAGED);
            task.moveToDispatch(picker, NOW);
            task.reopen();
            PickLineJpaEntity row = line("SKU-A", "HCM-A-01", 3, reservation);

            var view = service.confirm(task.getId(), row.getId(), "HCM-A-01", "LPN-SKU-A", actor);

            assertThat(view.status()).isEqualTo("PICKED");
            verify(consumption).consumeForPick(reservation, row.getId(), picker);
            verify(consumption, never()).stage(any());
        }

        @Test
        @DisplayName("moving to dispatch deducts every STAGED line where it stands, in one call (review M1), and records it")
        void moveStagedLinesDeductsEach() {
            stagingTaskIs(PickStatus.STAGED);
            UUID second = UUID.randomUUID();
            PickLineJpaEntity a = stagedLine("SKU-A", "HCM-A-01", 3, reservation);
            PickLineJpaEntity b = stagedLine("SKU-B", "HCM-A-02", 2, second);

            service.moveStagedLinesToDispatch(task, picker);

            assertThat(a.getStatus()).isEqualTo(PickLineStatus.PICKED);
            assertThat(b.getStatus()).isEqualTo(PickLineStatus.PICKED);
            assertThat(a.getConsumedAt()).isEqualTo(NOW);
            assertThat(b.getConsumedAt()).isEqualTo(NOW);
            verify(consumption).consumeStaged(List.of(
                    new com.stockflow.inventory.consumption.StagedLine(reservation, a.getId()),
                    new com.stockflow.inventory.consumption.StagedLine(second, b.getId())), picker);
            verify(consumption, never()).consumeStaged(any(UUID.class), any(UUID.class), any(UUID.class));
            verify(pickLines).save(a);
            verify(pickLines).save(b);
        }

        @Test
        @DisplayName("a task that is not STAGED deducts nothing: PICK_TASK_STATE_CONFLICT")
        void moveNeedsAStagedTask() {
            stagingTaskIs(PickStatus.PICKING);
            stagedLine("SKU-A", "HCM-A-01", 3, reservation);

            assertThat(refusal(() -> service.moveStagedLinesToDispatch(task, picker)).errorCode())
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            verifyNoInteractions(consumption);
        }

        private OrderSummary orderHolding(UUID... reservations) {
            return FulfillmentFixtures.orderWith(task.getOrderId(), OrderStatus.PICKING,
                    List.of(FulfillmentFixtures.stockLine("SKU-A", reservations)), null);
        }

        private void holdsInHcm(UUID hold, String location, String lpn) {
            when(inventory.holds(any())).thenAnswer(call -> List.of(new HoldView(hold, "SKU-A", location, "LOT-1", 4,
                    HoldView.Status.HELD, null, null, null, lpn, true)));
            when(warehouses.findLocation(location)).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                    StorageLocationView.Kind.BIN, WAREHOUSE, location, null, null, null, true)));
        }

        @Test
        @DisplayName("a top-up plans a line for a hold the task has none for, after its last stop, and reopens a STAGED task")
        void topUpReopensAStagedTask() {
            stagingTaskIs(PickStatus.STAGED);
            stagedLine("SKU-A", "HCM-A-01", 3, reservation);
            UUID later = UUID.randomUUID();
            holdsInHcm(later, "HCM-A-05", "LPN-NEW");
            OrderSummary order = orderHolding(reservation, later);
            when(orders.findById(task.getOrderId())).thenReturn(Optional.of(order));

            var added = service.topUp(task.getId(), actor);

            assertThat(added).singleElement().satisfies(view -> {
                assertThat(view.sequenceNo()).isEqualTo(2);
                assertThat(view.lpn()).isEqualTo("LPN-NEW");
                assertThat(view.status()).isEqualTo("OPEN");
            });
            assertThat(task.getStatus()).isEqualTo(PickStatus.PICKING);
            verify(picks).save(task);
            verify(pickLines).saveAll(any());
        }

        @Test
        @DisplayName("a top-up with every hold already covered changes nothing and the task stays STAGED")
        void topUpWithNothingNew() {
            stagingTaskIs(PickStatus.STAGED);
            stagedLine("SKU-A", "HCM-A-01", 3, reservation);
            holdsInHcm(reservation, "HCM-A-01", "LPN-SKU-A");
            when(orders.findById(task.getOrderId())).thenReturn(Optional.of(orderHolding(reservation)));

            assertThat(service.topUp(task.getId(), actor)).isEmpty();

            assertThat(task.getStatus()).isEqualTo(PickStatus.STAGED);
            verify(pickLines, never()).saveAll(any());
            verify(picks, never()).save(any());
        }

        @Test
        @DisplayName("a top-up of a cancelled task is PICK_TASK_STATE_CONFLICT; of a closed order's task likewise")
        void topUpRefusals() {
            stagingTaskIs(PickStatus.CANCELLED);
            assertThat(refusal(() -> service.topUp(task.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);

            stagingTaskIs(PickStatus.STAGED);
            when(orders.findById(task.getOrderId())).thenReturn(Optional.of(FulfillmentFixtures.orderWith(
                    task.getOrderId(), OrderStatus.CANCELLED, List.of(), null)));
            assertThat(refusal(() -> service.topUp(task.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            verify(pickLines, never()).saveAll(any());
        }

        @Test
        @DisplayName("planning again for a task that has lines plans only the holds it never had a line for")
        void planningAfterLinesExistTakesOnlyNewHolds() {
            stagingTaskIs(PickStatus.PICKING);
            line("SKU-A", "HCM-A-01", 3, reservation);
            UUID later = UUID.randomUUID();
            holdsInHcm(later, "HCM-A-05", "LPN-NEW");
            when(inventory.holds(any())).thenAnswer(call -> List.of(
                    new HoldView(reservation, "SKU-A", "HCM-A-01", "LOT-1", 3, HoldView.Status.HELD, null, null, null,
                            "LPN-SKU-A", true),
                    new HoldView(later, "SKU-A", "HCM-A-05", "LOT-1", 4, HoldView.Status.HELD, null, null, null,
                            "LPN-NEW", true)));

            var added = service.planLines(task, orderHolding(reservation, later));

            assertThat(added).singleElement().satisfies(line -> assertThat(line.reservationId()).isEqualTo(later));
            assertThat(task.getStatus()).as("a PICKING task needs no reopening").isEqualTo(PickStatus.PICKING);
        }

        @Test
        @DisplayName("a STAGED line of a cancelled task is put back through unstage, a PICKED one through putBack")
        void putBackFromStagingUnstages() {
            stagingTaskIs(PickStatus.CANCELLED);
            when(picks.lockById(task.getId())).thenReturn(Optional.of(task));
            PickLineJpaEntity staged = stagedLine("SKU-A", "HCM-A-01", 3, reservation);
            UUID otherHold = UUID.randomUUID();
            PickLineJpaEntity picked = line("SKU-B", "HCM-A-02", 2, otherHold);
            picked.apply(2, PickLineStatus.PICKED, picker, NOW, NOW, "HCM-A-02", lpnOf("SKU-B"), 0, null, null, null);
            when(warehouses.findLocation("HCM-A-01")).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                    StorageLocationView.Kind.BIN, WAREHOUSE, "HCM-A-01", null, null, null, true)));
            when(warehouses.findLocation("HCM-A-02")).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                    StorageLocationView.Kind.BIN, WAREHOUSE, "HCM-A-02", null, null, null, true)));

            var fromStaging = service.putBack(task.getId(), staged.getId(), "HCM-A-01", "LPN-SKU-A", actor);
            var fromDispatch = service.putBack(task.getId(), picked.getId(), "HCM-A-02", "LPN-SKU-B", actor);

            assertThat(fromStaging.status()).isEqualTo("PUT_BACK");
            assertThat(fromDispatch.status()).isEqualTo("PUT_BACK");
            verify(consumption).unstage(new PutBackCommand(staged.getId(), reservation, "LPN-SKU-A", "HCM-A-01", picker));
            verify(consumption).putBack(new PutBackCommand(picked.getId(), otherHold, "LPN-SKU-B", "HCM-A-02", picker));
            verify(consumption, never()).putBack(new PutBackCommand(staged.getId(), reservation, "LPN-SKU-A", "HCM-A-01",
                    picker));
        }

        @Test
        @DisplayName("the put-back queue shows a staged carton at its staging area and a picked one at its bin")
        void putBackQueueNamesWhereTheCartonStands() {
            stagingTaskIs(PickStatus.CANCELLED);
            PickLineJpaEntity staged = stagedLine("SKU-A", "HCM-A-01", 3, reservation);
            PickLineJpaEntity picked = line("SKU-B", "HCM-A-02", 2, UUID.randomUUID());
            picked.apply(2, PickLineStatus.PICKED, picker, NOW.plusSeconds(5), NOW, "HCM-A-02", lpnOf("SKU-B"), 0, null,
                    null, null);
            when(pickLines.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                    any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(staged, picked)));
            when(picks.findAllById(any())).thenReturn(List.of(task));

            var rows = service.putBacks(WAREHOUSE, 0, 20, actor).items();

            assertThat(rows).extracting(row -> row.lpn() + "@" + row.fromLocationCode())
                    .containsExactly("LPN-SKU-A@" + AREA, "LPN-SKU-B@HCM-A-02");
        }
    }
}
