package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
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
import org.junit.jupiter.api.Disabled;
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
 * kltn-docs 05 (putaway) BR-03/BR-04/BR-09: confirming a task puts goods away in as many parts as the clerk likes, into
 * a bin that passes the hard rules (bin, same warehouse, usable, storage class, capacity), whatever was suggested.
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

    private final PutawayTasks tasks = mock(PutawayTasks.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final StockItemRepository stockItems = mock(StockItemRepository.class);
    private final StockMovementLog ledger = mock(StockMovementLog.class);
    private final InventoryPolicyRepository items = mock(InventoryPolicyRepository.class);
    private final BinOccupancyQuery occupancy = mock(BinOccupancyQuery.class);
    private final StockOperations operations = mock(StockOperations.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PutawayService service = new PutawayService(tasks, warehouses, stockPackages, stockItems, ledger,
            items, occupancy, operations, events);

    private final UUID binId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    private static PutawayTaskView loose(String status, int quantity) {
        return new PutawayTaskView(UUID.randomUUID(), RECEIPT, LINE, SKU, quantity, null, null, status);
    }

    private static StorageLocationView location(UUID id, Kind kind, UUID warehouse, String code,
                                                StorageLocationView.StorageClass storageClass, boolean usable) {
        return new StorageLocationView(id, kind, warehouse, code, storageClass, StorageLocationView.Status.ACTIVE,
                StorageLocationView.Status.ACTIVE, usable);
    }

    private static ItemRow item(StorageClass storageClass) {
        ItemLogistics logistics = new ItemLogistics("PCS", null, new BigDecimal("0.010"), null, null, null, null, null,
                null, null, 1, 1, storageClass, false, null, false);
        return new ItemRow(UUID.randomUUID(), SKU, 1, logistics,
                new InventoryPolicy(null, null, null, RemovalStrategy.FEFO, TrackingMode.LOT, false, null));
    }

    private static CurrentUser clerkOf(String prefix) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(prefix), Set.of(UUID.randomUUID()));
    }

    @BeforeEach
    void aReceivedLotWaitsInTheReceivingArea() {
        StockMovement received = new StockMovement(UUID.randomUUID(), StockMovement.MovementType.RECEIPT, new Sku(SKU),
                "L1", null, new LocationId(RCV), 100, StockStatus.INBOUND,
                StockMovement.ReferenceType.GOODS_RECEIPT_LINE, LINE, null, null, NOW).ofLot(LOT);
        when(ledger.findLooseByReferences(any(), any(), anyCollection())).thenReturn(Map.of(LINE, received));
        when(ledger.findLooseByReference(any(), any(), eq(LINE))).thenReturn(Optional.of(received));
        when(stockItems.looseInboundLocationByLot(anyCollection())).thenReturn(Map.of(LOT, RCV));
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
        when(operations.putAwayLoose(any(), any(), anyString(), anyInt(), any(), any())).thenAnswer(c -> c.getArgument(3));
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

    @Nested
    @DisplayName("a loose task")
    class LooseTask {

        @Test
        @DisplayName("a part is moved into the bin and recorded with what was left before; no completion yet")
        void partialConfirm() {
            PutawayTaskView task = stubbed(loose("PENDING", 100));
            afterwards(task, "IN_PROGRESS");

            TaskView view = service.confirm(task.id(), BIN, null, 40, CLERK);

            verify(operations).putAwayLoose(new Sku(SKU), LOT, BIN, 40, task.id(), CLERK);
            verify(tasks).recordPutaway(task.id(), 40, 100, binId);
            verify(events, never()).publishEvent(any(Object.class));
            assertThat(view.targetLocationCode()).isEqualTo(BIN);
        }

        @Test
        @DisplayName("the last part completes the task and announces it once")
        void lastPartCompletes() {
            PutawayTaskView task = stubbed(loose("IN_PROGRESS", 100));
            when(ledger.sumQuantityByReference(anyCollection(), any(), eq(task.id()))).thenReturn(60);
            afterwards(task, "DONE");

            service.confirm(task.id(), BIN, null, 40, CLERK);

            verify(tasks).recordPutaway(task.id(), 40, 40, binId);
            ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
            verify(events).publishEvent(published.capture());
            assertThat(published.getValue()).isEqualTo(new PutawayCompleted(RECEIPT, LINE, task.id()));
        }

        @Test
        @DisplayName("what is left of a task in progress is its quantity less what the ledger says was put away")
        void leftFromLedger() {
            PutawayTaskView task = stubbed(loose("IN_PROGRESS", 100));
            when(ledger.sumQuantityByReference(anyCollection(), any(), eq(task.id()))).thenReturn(70);

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, 31, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);
            verify(operations, never()).putAwayLoose(any(), any(), anyString(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("a loose task needs a quantity of at least 1")
        void quantityRequired() {
            PutawayTaskView task = stubbed(loose("PENDING", 100));

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, null, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);
            assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, 0, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);
        }

        @Test
        @DisplayName("more than the task has left is PUTAWAY_QUANTITY_EXCEEDED")
        void tooMuch() {
            PutawayTaskView task = stubbed(loose("PENDING", 100));

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, 101, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);
        }

        @Test
        @DisplayName("a loose task is confirmed by a quantity, never by an LPN")
        void lpnForbidden() {
            PutawayTaskView task = stubbed(loose("PENDING", 100));

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, "LPN-0001", 10, CLERK)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a task that is already done cannot be confirmed again")
        void doneTask() {
            PutawayTaskView task = stubbed(loose("DONE", 100));

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, 1, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_TASK_DONE);
        }

        @Test
        @DisplayName("a cancelled task cannot be confirmed")
        void cancelledTask() {
            PutawayTaskView task = stubbed(loose("CANCELLED", 100));

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, 1, CLERK)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a task whose goods cannot be located is PUTAWAY_TASK_NOT_FOUND")
        void unlocatable() {
            PutawayTaskView task = stubbed(loose("PENDING", 100));
            when(ledger.findLooseByReferences(any(), any(), anyCollection())).thenReturn(Map.of());

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, 1, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_TASK_NOT_FOUND);
        }

        @Test
        @DisplayName("BR-SEC-002: a clerk of another warehouse is OUT_OF_DATA_SCOPE, said before the task's status")
        void otherWarehouseClerk() {
            PutawayTaskView task = stubbed(loose("DONE", 100));

            WithCurrentUser.run(clerkOf("HN"), () ->
                    assertThat(codeOf(() -> service.confirm(task.id(), BIN, null, 1, CLERK)))
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }
    }

    @Nested
    @DisplayName("the bin the clerk chose (BR-04, BR-09: hard rules cannot be overridden)")
    class ChosenBin {

        private PutawayTaskView task;

        @BeforeEach
        void aTask() {
            task = stubbed(loose("PENDING", 100));
            afterwards(task, "IN_PROGRESS");
        }

        private ErrorCode refusal() {
            return codeOf(() -> service.confirm(task.id(), BIN, null, 40, CLERK));
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
        @DisplayName("a bin of another warehouse is MOVE_ACROSS_WAREHOUSES: that is a transfer order")
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
                    new Occupancy(20, BigDecimal.ZERO, false, false)));

            assertThat(refusal()).isEqualTo(ErrorCode.LOCATION_CAPACITY_EXCEEDED);
            verify(operations, never()).putAwayLoose(any(), any(), anyString(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("BR-03: filling the bin exactly is allowed")
        void fillingExactly() {
            when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                    .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, 50, null)));
            when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of(BIN,
                    new Occupancy(10, BigDecimal.ZERO, false, false)));

            service.confirm(task.id(), BIN, null, 40, CLERK);

            verify(operations).putAwayLoose(any(), any(), eq(BIN), eq(40), any(), any());
        }

        @Test
        @DisplayName("BR-03: a declared weight limit counts the units' weight: 40 x 0.010 kg on top of 7.8 kg exceeds 8 kg")
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

            service.confirm(task.id(), BIN, null, 40, CLERK);

            verify(operations).putAwayLoose(any(), any(), eq(BIN), eq(40), any(), any());
        }

        @Test
        @Disabled("Possible bug: a bin other than the suggested ones is accepted with no deviation reason "
                + "(kltn-docs warehouse/05-putaway BR-07, BR-09); PutawayService.java:191 confirm() takes no reason; see PR body")
        @DisplayName("BR-07: putting goods in a bin that was not suggested must come with a reason")
        void deviationFromTheSuggestionNeedsAReason() {
            when(warehouses.putawayCandidates(WAREHOUSE, StorageLocationView.StorageClass.NORMAL)).thenReturn(List.of(
                    new PutawayCandidate(UUID.randomUUID(), "HCM-A09-1-A", 100, null)));

            // BIN is a valid bin, but not among the suggestions; the service has no way to be told why.
            assertThat(refusal()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("two putaways into one bin cannot both see it as it was: the bin is locked before its occupancy is read")
        void binLockedBeforeOccupancy() {
            service.confirm(task.id(), BIN, null, 40, CLERK);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(occupancy);
            order.verify(occupancy).lockBin(BIN);
            order.verify(occupancy).occupancyOf(anyCollection(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("a carton task")
    class CartonTask {

        private final StockPackageId carton = new StockPackageId(UUID.randomUUID());
        private PutawayTaskView task;

        @BeforeEach
        void aCartonTask() {
            task = stubbed(new PutawayTaskView(UUID.randomUUID(), RECEIPT, LINE, SKU, 24, carton.value(), null,
                    "PENDING"));
            afterwards(task, "DONE");
            when(stockPackages.findAllById(anyCollection())).thenReturn(List.of(new StockPackage(carton, "LPN-0001",
                    UUID.randomUUID(), UUID.randomUUID(), 24, 24, new LocationId(RCV), StockPackageStatus.IN_STOCK,
                    true, LINE, null, 0L)));
            when(stockPackages.onHandOf(anyCollection())).thenReturn(Map.of(carton, 24));
        }

        @Test
        @DisplayName("is confirmed by the carton's LPN, any case, and moves the carton whole as AVAILABLE")
        void confirmedByLpn() {
            service.confirm(task.id(), BIN, " lpn-0001 ", null, CLERK);

            ArgumentCaptor<ReclassifyStockPackagesCommand> command =
                    ArgumentCaptor.forClass(ReclassifyStockPackagesCommand.class);
            verify(operations).reclassifyStockPackages(command.capture());
            assertThat(command.getValue().lpns()).containsExactly("LPN-0001");
            assertThat(command.getValue().toLocationCode()).isEqualTo(BIN);
            assertThat(command.getValue().disposition()).isEqualTo(StockDisposition.AVAILABLE);
            assertThat(command.getValue().reference()).isEqualTo(MoveReference.PUTAWAY_TASK);
            assertThat(command.getValue().referenceId()).isEqualTo(task.id());
            verify(tasks).recordPutaway(task.id(), 24, 24, binId);
            verify(events).publishEvent(any(PutawayCompleted.class));
        }

        @Test
        @DisplayName("a quantity is refused: a carton goes in whole")
        void quantityRefused() {
            assertThat(codeOf(() -> service.confirm(task.id(), BIN, "LPN-0001", 24, CLERK)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("scanning another carton or nothing is PUTAWAY_LPN_MISMATCH")
        void wrongScan() {
            assertThat(codeOf(() -> service.confirm(task.id(), BIN, "LPN-0002", null, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_LPN_MISMATCH);
            assertThat(codeOf(() -> service.confirm(task.id(), BIN, " ", null, CLERK)))
                    .isEqualTo(ErrorCode.PUTAWAY_LPN_MISMATCH);
            verify(operations, never()).reclassifyStockPackages(any());
        }

        @Test
        @DisplayName("the bin must take what the carton holds now, not what the task was opened for")
        void judgedByWhatItHoldsNow() {
            when(stockPackages.onHandOf(anyCollection())).thenReturn(Map.of(carton, 60));
            when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                    .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, 50, null)));

            assertThat(codeOf(() -> service.confirm(task.id(), BIN, "LPN-0001", null, CLERK)))
                    .isEqualTo(ErrorCode.LOCATION_CAPACITY_EXCEEDED);
        }

        @Test
        @DisplayName("a carton that fills the bin exactly still fits when it already stands in that bin")
        void cartonAlreadyInTheBinIsNotCountedTwice() {
            when(stockPackages.findAllById(anyCollection())).thenReturn(List.of(new StockPackage(carton, "LPN-0001",
                    UUID.randomUUID(), UUID.randomUUID(), 24, 24, new LocationId(BIN), StockPackageStatus.IN_STOCK,
                    true, LINE, null, 0L)));
            when(warehouses.findLocation(BIN)).thenReturn(Optional.of(location(binId, Kind.BIN, WAREHOUSE, BIN,
                    StorageLocationView.StorageClass.NORMAL, true)));
            when(warehouses.putawayCandidate(WAREHOUSE, StorageLocationView.StorageClass.NORMAL, BIN))
                    .thenReturn(Optional.of(new PutawayCandidate(binId, BIN, 24, null)));
            when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of(BIN,
                    new Occupancy(24, BigDecimal.ZERO, true, true)));

            service.confirm(task.id(), BIN, "LPN-0001", null, CLERK);

            verify(operations).reclassifyStockPackages(any());
        }
    }

    @Nested
    @DisplayName("reading one task with its suggested bins")
    class Suggestions {

        private PutawayTaskView task;

        @BeforeEach
        void aPendingTask() {
            task = stubbed(loose("PENDING", 100));
            when(warehouses.findLocation(RCV)).thenReturn(Optional.of(location(UUID.randomUUID(), Kind.AREA, WAREHOUSE,
                    RCV, StorageLocationView.StorageClass.NORMAL, true)));
        }

        @Test
        @DisplayName("an unknown task is PUTAWAY_TASK_NOT_FOUND")
        void unknown() {
            when(tasks.find(any())).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.get(UUID.randomUUID()))).isEqualTo(ErrorCode.PUTAWAY_TASK_NOT_FOUND);
        }

        @Test
        @DisplayName("bins that cannot take a loose unit are left out; a bin already holding the lot ranks first (consolidate before spreading)")
        void rankedSuggestions() {
            when(warehouses.putawayCandidates(WAREHOUSE, StorageLocationView.StorageClass.NORMAL)).thenReturn(List.of(
                    new PutawayCandidate(UUID.randomUUID(), "HCM-A01-1-A", 50, null),
                    new PutawayCandidate(UUID.randomUUID(), "HCM-A01-1-B", 50, null),
                    new PutawayCandidate(UUID.randomUUID(), "HCM-A01-1-C", 10, null)));
            when(occupancy.occupancyOf(anyCollection(), anyString(), any())).thenReturn(Map.of(
                    "HCM-A01-1-A", new Occupancy(0, BigDecimal.ZERO, false, false),
                    "HCM-A01-1-B", new Occupancy(20, BigDecimal.ZERO, true, true),
                    "HCM-A01-1-C", new Occupancy(10, BigDecimal.ZERO, false, false)));

            TaskView view = service.get(task.id());

            assertThat(view.suggestions()).extracting(PutawayRanker.Suggestion::locationCode)
                    .containsExactly("HCM-A01-1-B", "HCM-A01-1-A");
            assertThat(view.suggestions().get(0).reason()).isEqualTo(PutawayRanker.Reason.SAME_LOT);
            assertThat(view.suggestions().get(0).freeUnits()).isEqualTo(30);
        }

        @Test
        @DisplayName("a finished task has nothing left to put away, so no suggestions are computed")
        void doneTaskHasNoSuggestions() {
            PutawayTaskView done = stubbed(loose("DONE", 100));

            TaskView view = service.get(done.id());

            assertThat(view.left()).isZero();
            assertThat(view.suggestions()).isEmpty();
            verify(warehouses, never()).putawayCandidates(any(), any());
        }

        @Test
        @DisplayName("a pending task has all of its quantity left, without reading the ledger for it")
        void pendingHasEverythingLeft() {
            TaskView view = service.get(task.id());

            assertThat(view.left()).isEqualTo(100);
            assertThat(view.sourceLocationCode()).isEqualTo(RCV);
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
