package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ItemRow;
import com.stockflow.inventory.internal.service.PutawayService.TaskView;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.PutawayCandidate;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.StorageLocationView.Kind;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kltn-docs 05 (putaway) BR-03/BR-04/BR-08/BR-09, team plan 4C-2: a task is one carton, confirmed by scanning its LPN
 * and put away whole into a bin that passes the hard rules (bin, same warehouse, usable, storage class, capacity),
 * whatever was suggested. Loose tasks are history: one can still be read, never confirmed.
 */
class PutawayServiceConfirmTest {

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID LOT = UUID.randomUUID();
    private static final UUID RECEIPT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID CLERK = UUID.randomUUID();
    private static final String RCV = "HCM-RCV01";
    private static final String BIN = "HCM-A01-1-A";
    private static final String SKU = "CUP-12OZ";
    private static final String LPN = "LPN-0001";
    private static final int CARTON_UNITS = 24;

    private final PutawayTasks tasks = mock(PutawayTasks.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final StockMovementLog ledger = mock(StockMovementLog.class);
    private final InventoryPolicyRepository items = mock(InventoryPolicyRepository.class);
    private final BinOccupancyQuery occupancy = mock(BinOccupancyQuery.class);
    private final StockOperations operations = mock(StockOperations.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PutawayService service = new PutawayService(tasks, warehouses, stockPackages, ledger, items,
            occupancy, operations, events, mock(StockItemRepository.class));

    private final UUID binId = UUID.randomUUID();
    private final StockPackageId carton = new StockPackageId(UUID.randomUUID());

    private PutawayTaskView cartonTask(String status) {
        return new PutawayTaskView(UUID.randomUUID(), RECEIPT, LINE, SKU, CARTON_UNITS, carton.value(), null, status);
    }

    /** A task opened before receipts put every unit into a carton: no carton, and its target if it got that far. */
    private static PutawayTaskView looseTask(String status, UUID target) {
        return new PutawayTaskView(UUID.randomUUID(), RECEIPT, LINE, SKU, 100, null, target, status);
    }

    private StockPackage cartonAt(String locationCode) {
        return new StockPackage(carton, LPN, UUID.randomUUID(), UUID.randomUUID(), CARTON_UNITS, CARTON_UNITS,
                new LocationId(locationCode), StockPackageStatus.IN_STOCK, LINE, null, 0L);
    }

    private static StorageLocationView location(UUID id, Kind kind, UUID warehouse, String code,
                                                StorageLocationView.StorageClass storageClass, boolean usable) {
        return new StorageLocationView(id, kind, warehouse, code, storageClass, StorageLocationView.Status.ACTIVE,
                StorageLocationView.Status.ACTIVE, usable);
    }

    private static ItemRow item(StorageClass storageClass) {
        ItemLogistics logistics = new ItemLogistics("PCS", null, new BigDecimal("0.010"), null, null, null, null, null,
                null, null, 1, 1, storageClass, false, null);
        return new ItemRow(UUID.randomUUID(), SKU, 1, logistics);
    }

    private static CurrentUser clerkOf(String prefix) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(prefix), Set.of(UUID.randomUUID()));
    }

    @BeforeEach
    void aReceivedCartonWaitsInTheReceivingArea() {
        StockMovement received = new StockMovement(UUID.randomUUID(), StockMovement.MovementType.RECEIPT, new Sku(SKU),
                "L1", null, new LocationId(RCV), CARTON_UNITS, StockStatus.INBOUND,
                StockMovement.ReferenceType.GOODS_RECEIPT_LINE, LINE, null, null, NOW).ofLot(LOT);
        when(ledger.findByReference(any(), any(), eq(LINE))).thenReturn(Optional.of(received));
        when(ledger.findLooseByReferences(any(), any(), anyCollection())).thenReturn(Map.of(LINE, received));
        when(ledger.findLooseByReference(any(), any(), eq(LINE))).thenReturn(Optional.of(received));
        when(stockPackages.findAllById(anyCollection())).thenReturn(List.of(cartonAt(RCV)));
        when(stockPackages.onHandOf(anyCollection())).thenReturn(Map.of(carton, CARTON_UNITS));
        when(items.findBySkus(anyCollection())).thenReturn(List.of(item(StorageClass.NORMAL)));
        when(warehouses.findLocation(RCV)).thenReturn(Optional.of(location(UUID.randomUUID(), Kind.AREA, WAREHOUSE,
                RCV, StorageLocationView.StorageClass.NORMAL, true)));
        when(warehouses.findLocation(BIN)).thenReturn(Optional.of(location(binId, Kind.BIN, WAREHOUSE, BIN,
                StorageLocationView.StorageClass.NORMAL, true)));
        when(warehouses.findLocation(binId)).thenReturn(Optional.of(location(binId, Kind.BIN, WAREHOUSE, BIN,
                StorageLocationView.StorageClass.NORMAL, true)));
        when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, 100, null)));
        when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of());
    }

    private PutawayTaskView stubbed(PutawayTaskView task) {
        when(tasks.lockForUpdate(task.id())).thenReturn(task);
        when(tasks.find(task.id())).thenReturn(Optional.of(task));
        return task;
    }

    private void afterwards(PutawayTaskView task, String status) {
        when(tasks.recordPutaway(eq(task.id()), anyInt(), anyInt(), any())).thenReturn(new PutawayTaskView(task.id(),
                task.goodsReceiptId(), task.goodsReceiptLineId(), task.sku(), task.quantity(), task.stockPackageId(),
                binId, status));
    }

    private void nothingMoved() {
        verify(operations, never()).reclassifyStockPackages(any());
        verify(tasks, never()).recordPutaway(any(), anyInt(), anyInt(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Nested
    @DisplayName("a carton task")
    class CartonTask {

        private PutawayTaskView task;

        @BeforeEach
        void aPendingCartonTask() {
            task = stubbed(cartonTask("PENDING"));
            afterwards(task, "DONE");
        }

        @Test
        @DisplayName("BR-08: is confirmed by the carton's LPN, any case, and moves the carton whole as AVAILABLE")
        void confirmedByLpn() {
            TaskView view = service.confirm(task.id(), BIN, " lpn-0001 ", CLERK);

            ArgumentCaptor<ReclassifyStockPackagesCommand> command =
                    ArgumentCaptor.forClass(ReclassifyStockPackagesCommand.class);
            verify(operations).reclassifyStockPackages(command.capture());
            assertThat(command.getValue().lpns()).containsExactly(LPN);
            assertThat(command.getValue().toLocationCode()).isEqualTo(BIN);
            assertThat(command.getValue().disposition()).isEqualTo(StockDisposition.AVAILABLE);
            assertThat(command.getValue().reference()).isEqualTo(MoveReference.PUTAWAY_TASK);
            assertThat(command.getValue().referenceId()).isEqualTo(task.id());
            assertThat(command.getValue().actorId()).isEqualTo(CLERK);
            verify(tasks).recordPutaway(task.id(), CARTON_UNITS, CARTON_UNITS, binId);
            assertThat(view.targetLocationCode()).isEqualTo(BIN);
        }

        @Test
        @DisplayName("the whole carton completes the task, which is announced exactly once")
        void completionPublishedOnce() {
            service.confirm(task.id(), BIN, LPN, CLERK);

            ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
            verify(events).publishEvent(published.capture());
            assertThat(published.getAllValues()).containsExactly(new PutawayCompleted(RECEIPT, LINE, task.id()));
        }

        /** The warehouse module owns the task's state: the event follows what it recorded, not what was sent. */
        @Test
        @DisplayName("no PutawayCompleted unless the task came back DONE")
        void noCompletionUnlessDone() {
            afterwards(task, "IN_PROGRESS");

            service.confirm(task.id(), BIN, LPN, CLERK);

            verify(operations).reclassifyStockPackages(any());
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("scanning another carton or nothing is PUTAWAY_LPN_MISMATCH, and nothing moves")
        void wrongScan() {
            assertThat(codeOf(() -> service.confirm(task.id(), BIN, "LPN-0002", CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_LPN_MISMATCH);
            assertThat(codeOf(() -> service.confirm(task.id(), BIN, " ", CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_LPN_MISMATCH);
            assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_LPN_MISMATCH);
            nothingMoved();
        }

        @Test
        @DisplayName("the bin must take what the carton holds now, not what the task was opened for")
        void judgedByWhatItHoldsNow() {
            when(stockPackages.onHandOf(anyCollection())).thenReturn(Map.of(carton, 60));
            when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                    .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, 50, null)));

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, LPN, CLERK)))
                    .isEqualTo(ErrorCode.LOCATION_CAPACITY_EXCEEDED);
            nothingMoved();
        }

        @Test
        @DisplayName("a carton that fills the bin exactly still fits when it already stands in that bin")
        void cartonAlreadyInTheBinIsNotCountedTwice() {
            when(stockPackages.findAllById(anyCollection())).thenReturn(List.of(cartonAt(BIN)));
            when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                    .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, CARTON_UNITS, null)));
            when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of(BIN,
                    new Occupancy(CARTON_UNITS, BigDecimal.ZERO, true, true)));

            service.confirm(task.id(), BIN, LPN, CLERK);

            verify(operations).reclassifyStockPackages(any());
        }

        @Test
        @DisplayName("a task that is already done cannot be confirmed again")
        void doneTask() {
            PutawayTaskView done = stubbed(cartonTask("DONE"));

            assertThat(codeOf(() -> service.confirm(done.id(), BIN, LPN, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_TASK_DONE);
            nothingMoved();
        }

        @Test
        @DisplayName("a cancelled task cannot be confirmed")
        void cancelledTask() {
            PutawayTaskView cancelled = stubbed(cartonTask("CANCELLED"));

            assertThat(codeOf(() -> service.confirm(cancelled.id(), BIN, LPN, CLERK)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            nothingMoved();
        }

        @Test
        @DisplayName("a task whose carton no longer exists is PUTAWAY_TASK_NOT_FOUND")
        void cartonGone() {
            when(stockPackages.findAllById(anyCollection())).thenReturn(List.of());

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, LPN, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_TASK_NOT_FOUND);
            nothingMoved();
        }

        @Test
        @DisplayName("BR-SEC-002: a clerk of another warehouse is OUT_OF_DATA_SCOPE, said before the task's status")
        void otherWarehouseClerk() {
            PutawayTaskView done = stubbed(cartonTask("DONE"));

            WithCurrentUser.run(clerkOf("HN"), () ->
                    assertThat(codeOf(() -> service.confirm(done.id(), BIN, LPN, CLERK)))
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }
    }

    /**
     * Loose tasks were opened before receipts put every unit into a carton and closed before the stock was boxed
     * (V20261111000110). One left PENDING would have no carton to scan and no loose stock to move, so it is refused
     * instead of being put away by a quantity.
     */
    @Nested
    @DisplayName("a historical loose task")
    class HistoricalLooseTask {

        @Test
        @DisplayName("is refused with VALIDATION_FAILED, and nothing moves")
        void refused() {
            PutawayTaskView task = stubbed(looseTask("PENDING", null));

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, LPN, CLERK)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            nothingMoved();
            verify(occupancy, never()).lockBin(anyString());
        }

        @Test
        @DisplayName("one already done is still PUTAWAY_TASK_DONE: the status is said before the task's shape")
        void doneIsSaidFirst() {
            PutawayTaskView task = stubbed(looseTask("DONE", binId));

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_TASK_DONE);
            nothingMoved();
        }
    }

    @Nested
    @DisplayName("the bin the clerk chose (BR-04, BR-09: hard rules cannot be overridden)")
    class ChosenBin {

        private PutawayTaskView task;

        @BeforeEach
        void aTask() {
            task = stubbed(cartonTask("PENDING"));
            afterwards(task, "DONE");
        }

        private ErrorCode refusal() {
            return codeOf(() -> service.confirm(task.id(), BIN, LPN, CLERK));
        }

        @Test
        @DisplayName("an unknown location is LOCATION_NOT_FOUND")
        void unknownLocation() {
            when(warehouses.findLocation(BIN)).thenReturn(Optional.empty());

            assertThat(refusal()).isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
        }

        @Test
        @DisplayName("goods are put away into bins, not areas")
        void notABin() {
            when(warehouses.findLocation(BIN)).thenReturn(Optional.of(location(binId, Kind.AREA, WAREHOUSE, BIN,
                    StorageLocationView.StorageClass.NORMAL, true)));

            assertThat(refusal()).isEqualTo(ErrorCode.PUTAWAY_TARGET_NOT_BIN);
        }

        @Test
        @DisplayName("a bin of another warehouse is MOVE_ACROSS_WAREHOUSES: stock never changes warehouse")
        void otherWarehouse() {
            when(warehouses.findLocation(BIN)).thenReturn(Optional.of(location(binId, Kind.BIN, UUID.randomUUID(), BIN,
                    StorageLocationView.StorageClass.NORMAL, true)));

            assertThat(refusal()).isEqualTo(ErrorCode.MOVE_ACROSS_WAREHOUSES);
        }

        @Test
        @DisplayName("a bin that is not usable now (blocked, in maintenance) is LOCATION_NOT_USABLE")
        void notUsable() {
            when(warehouses.findLocation(BIN)).thenReturn(Optional.of(location(binId, Kind.BIN, WAREHOUSE, BIN,
                    StorageLocationView.StorageClass.NORMAL, false)));

            assertThat(refusal()).isEqualTo(ErrorCode.LOCATION_NOT_USABLE);
        }

        @Test
        @DisplayName("a bin of another storage class than the item's is LOCATION_STORAGE_CLASS_MISMATCH")
        void storageClassMismatch() {
            when(warehouses.findLocation(BIN)).thenReturn(Optional.of(location(binId, Kind.BIN, WAREHOUSE, BIN,
                    StorageLocationView.StorageClass.COLD, true)));

            assertThat(refusal()).isEqualTo(ErrorCode.LOCATION_STORAGE_CLASS_MISMATCH);
        }

        @Test
        @DisplayName("a bin not flagged for putaway is LOCATION_NOT_USABLE, though it is a usable bin")
        void notAPutawayBin() {
            when(warehouses.putawayCandidate(any(), any(), anyString())).thenReturn(Optional.empty());

            assertThat(refusal()).isEqualTo(ErrorCode.LOCATION_NOT_USABLE);
        }

        @Test
        @DisplayName("BR-03: more than the bin's declared capacity is LOCATION_CAPACITY_EXCEEDED, counting what is there")
        void capacityExceeded() {
            when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                    .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, 50, null)));
            when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of(BIN,
                    new Occupancy(30, BigDecimal.ZERO, false, false)));

            assertThat(refusal()).isEqualTo(ErrorCode.LOCATION_CAPACITY_EXCEEDED);
            nothingMoved();
        }

        @Test
        @DisplayName("BR-03: filling the bin exactly is allowed")
        void fillingExactly() {
            when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                    .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, 50, null)));
            when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of(BIN,
                    new Occupancy(26, BigDecimal.ZERO, false, false)));

            service.confirm(task.id(), BIN, LPN, CLERK);

            verify(operations).reclassifyStockPackages(any());
        }

        @Test
        @DisplayName("BR-03: a declared weight limit counts the units' weight: 24 x 0.010 kg on top of 7.8 kg exceeds 8 kg")
        void weightExceeded() {
            when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                    .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, null, new BigDecimal("8.0"))));
            when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of(BIN,
                    new Occupancy(780, new BigDecimal("7.8"), false, false)));

            assertThat(refusal()).isEqualTo(ErrorCode.LOCATION_CAPACITY_EXCEEDED);
        }

        @Test
        @DisplayName("BR-03: a limit that is not declared is not checked")
        void undeclaredLimitsAreNotChecked() {
            when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                    .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, null, null)));
            when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of(BIN,
                    new Occupancy(1_000_000, new BigDecimal("9999"), false, false)));

            service.confirm(task.id(), BIN, LPN, CLERK);

            verify(operations).reclassifyStockPackages(any());
        }

        /**
         * A reason code for leaving the suggestions is later scope (kltn-docs open question C18): suggestions are
         * advice, and the scanned bin is judged by the hard rules alone. An earlier reading of BR-07 expected a
         * refusal here; the 05-putaway chapter of 2026-10-28 settled it the other way.
         */
        @Test
        @DisplayName("BR-07: a bin that was not suggested is accepted without a reason when it passes the hard rules")
        void deviationFromTheSuggestionNeedsNoReason() {
            when(warehouses.putawayCandidates(WAREHOUSE, StorageLocationView.StorageClass.NORMAL)).thenReturn(List.of(
                    new PutawayCandidate(UUID.randomUUID(), "HCM-A09-1-A", 100, null)));

            service.confirm(task.id(), BIN, LPN, CLERK);

            verify(operations).reclassifyStockPackages(new ReclassifyStockPackagesCommand(LPN, BIN,
                    StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, task.id(), CLERK, null));
        }

        @Test
        @DisplayName("two putaways into one bin cannot both see it as it was: the bin is locked before its occupancy is read")
        void binLockedBeforeOccupancy() {
            service.confirm(task.id(), BIN, LPN, CLERK);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(occupancy);
            order.verify(occupancy).lockBin(BIN);
            order.verify(occupancy).occupancyOf(anyCollection(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("reading one task with its suggested bins")
    class Suggestions {

        private PutawayTaskView task;

        @BeforeEach
        void aPendingTask() {
            task = stubbed(cartonTask("PENDING"));
        }

        @Test
        @DisplayName("an unknown task is PUTAWAY_TASK_NOT_FOUND")
        void unknown() {
            when(tasks.find(any())).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.get(UUID.randomUUID()))).isEqualTo(ErrorCode.PUTAWAY_TASK_NOT_FOUND);
        }

        @Test
        @DisplayName("bins that cannot take the whole carton are left out; a bin already holding the lot ranks first (consolidate before spreading)")
        void rankedSuggestions() {
            when(warehouses.putawayCandidates(WAREHOUSE, StorageLocationView.StorageClass.NORMAL)).thenReturn(List.of(
                    new PutawayCandidate(UUID.randomUUID(), "HCM-A01-1-A", 50, null),
                    new PutawayCandidate(UUID.randomUUID(), "HCM-A01-1-B", 50, null),
                    new PutawayCandidate(UUID.randomUUID(), "HCM-A01-1-C", 40, null)));
            when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of(
                    "HCM-A01-1-A", new Occupancy(0, BigDecimal.ZERO, false, false),
                    "HCM-A01-1-B", new Occupancy(20, BigDecimal.ZERO, true, true),
                    "HCM-A01-1-C", new Occupancy(20, BigDecimal.ZERO, false, false)));

            TaskView view = service.get(task.id());

            assertThat(view.suggestions()).extracting(PutawayRanker.Suggestion::locationCode)
                    .containsExactly("HCM-A01-1-B", "HCM-A01-1-A");
            assertThat(view.suggestions().get(0).reason()).isEqualTo(PutawayRanker.Reason.SAME_LOT);
            assertThat(view.suggestions().get(0).freeUnits()).isEqualTo(30);
        }

        @Test
        @DisplayName("a finished task has nothing left to put away, so no suggestions are computed")
        void doneTaskHasNoSuggestions() {
            PutawayTaskView done = stubbed(cartonTask("DONE"));

            TaskView view = service.get(done.id());

            assertThat(view.left()).isZero();
            assertThat(view.suggestions()).isEmpty();
            verify(warehouses, never()).putawayCandidates(any(), any());
        }

        @Test
        @DisplayName("a pending task has its whole carton left, without reading the ledger for it")
        void pendingHasEverythingLeft() {
            TaskView view = service.get(task.id());

            assertThat(view.left()).isEqualTo(CARTON_UNITS);
            assertThat(view.sourceLocationCode()).isEqualTo(RCV);
            assertThat(view.lpn()).isEqualTo(LPN);
            assertThat(view.lotNumber()).isEqualTo("L1");
            verify(ledger, never()).sumQuantityByReference(anyCollection(), any(), any());
        }

        @Test
        @DisplayName("BR-SEC-002: a clerk of another warehouse cannot read the task, its status included")
        void otherWarehouse() {
            WithCurrentUser.run(clerkOf("HN"), () ->
                    assertThat(codeOf(() -> service.get(task.id()))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }
    }
}
