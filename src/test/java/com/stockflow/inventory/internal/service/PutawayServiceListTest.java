package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.service.PutawayService.TaskView;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-92: the list of putaway tasks does not depend on every task being resolvable. One task whose carton cannot be
 * found must not turn the list into a 404 for everyone; a single task read stays strict. Historical loose tasks (team
 * plan 4C-2: putaway is by carton only) are still listed, located where they put their goods or, never put away,
 * where their receipt line set them down.
 */
class PutawayServiceListTest {

    private static final String RCV = "WH1-RCV01";
    private static final String BIN = "WH1-A01-1-A";

    private final PutawayTasks tasks = mock(PutawayTasks.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final StockMovementLog ledger = mock(StockMovementLog.class);
    private final PutawayService service = new PutawayService(tasks, warehouses, stockPackages, ledger,
            mock(BinOccupancyQuery.class), mock(StockOperations.class),
            mock(ApplicationEventPublisher.class), mock(StockItemRepository.class),
            mock(com.stockflow.warehouse.api.PutawayTrips.class));

    private final UUID warehouse = UUID.randomUUID();

    @Nested
    @DisplayName("carton tasks")
    class CartonTasks {

        private final StockPackageId healthyCarton = new StockPackageId(UUID.randomUUID());
        private final PutawayTaskView healthy = task(healthyCarton);
        private final PutawayTaskView broken = task(new StockPackageId(UUID.randomUUID()));

        private static PutawayTaskView task(StockPackageId carton) {
            return new PutawayTaskView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SKU-1", 10,
                    carton.value(), null, "PENDING");
        }

        private void twoOpenTasksOneWithoutItsCarton() {
            StockPackage carton = mock(StockPackage.class);
            when(carton.id()).thenReturn(healthyCarton);
            when(carton.lpn()).thenReturn("LPN-1");
            when(carton.location()).thenReturn(new LocationId(RCV));
            when(tasks.openTasks()).thenReturn(List.of(healthy, broken));
            when(stockPackages.findAllById(any())).thenReturn(List.of(carton));
            StorageLocationView location = mock(StorageLocationView.class);
            when(location.locationCode()).thenReturn(RCV);
            when(location.warehouseId()).thenReturn(warehouse);
            when(warehouses.findLocations(any())).thenReturn(List.of(location));
        }

        @Test
        @DisplayName("a task whose carton cannot be found is left out of the warehouse list, the others are listed")
        void brokenTaskIsSkipped() {
            twoOpenTasksOneWithoutItsCarton();

            PageResponse<TaskView> page = service.list(warehouse, null, null, 0, 20);

            assertThat(page.items()).extracting(TaskView::id).containsExactly(healthy.id());
            assertThat(page.items().get(0).lpn()).isEqualTo("LPN-1");
            assertThat(page.items().get(0).sourceLocationCode()).isEqualTo(RCV);
        }

        @Test
        @DisplayName("the list reads the tasks' own cartons by id, not every carton of the open lines")
        @SuppressWarnings("unchecked")
        void readsOnlyTheTasksCartons() {
            twoOpenTasksOneWithoutItsCarton();

            service.list(warehouse, null, null, 0, 20);

            verify(stockPackages, never()).findByReceiptLines(any());
            org.mockito.ArgumentCaptor<Collection<StockPackageId>> ids =
                    org.mockito.ArgumentCaptor.forClass(Collection.class);
            verify(stockPackages).findAllById(ids.capture());
            assertThat(ids.getValue()).containsExactlyInAnyOrder(healthyCarton,
                    new StockPackageId(broken.stockPackageId()));
        }

        @Test
        @DisplayName("a single task whose carton cannot be found is still PUTAWAY_TASK_NOT_FOUND")
        void getStaysStrict() {
            when(tasks.find(broken.id())).thenReturn(Optional.of(broken));
            when(stockPackages.findAllById(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.get(broken.id())).isInstanceOf(BusinessException.class);
        }
    }

    /**
     * A loose task has no carton to follow and no loose stock is left to look up, so where it stands comes from the
     * task and the ledger alone: its target bin once it put goods away, otherwise the {@code to} of its receipt
     * line's loose RECEIPT line.
     */
    @Nested
    @DisplayName("historical loose tasks of a receipt")
    class HistoricalLooseTasks {

        private final UUID receipt = UUID.randomUUID();
        private final UUID binId = UUID.randomUUID();
        private final PutawayTaskView done = loose(binId, "DONE");
        private final PutawayTaskView cancelled = loose(null, "CANCELLED");
        private final PutawayTaskView nowhere = loose(null, "CANCELLED");

        private PutawayTaskView loose(UUID target, String status) {
            return new PutawayTaskView(UUID.randomUUID(), receipt, UUID.randomUUID(), "SKU-1", 10, null, target,
                    status);
        }

        private StockMovement receivedInto(PutawayTaskView task, String locationCode) {
            return new StockMovement(UUID.randomUUID(), StockMovement.MovementType.RECEIPT, new Sku("SKU-1"), "L7",
                    null, new LocationId(locationCode), 10, StockStatus.INBOUND,
                    StockMovement.ReferenceType.GOODS_RECEIPT_LINE, task.goodsReceiptLineId(), null, null, NOW);
        }

        private PageResponse<TaskView> listed() {
            when(tasks.tasksOfReceipt(receipt)).thenReturn(List.of(done, cancelled, nowhere));
            when(ledger.findLooseByReferences(any(), any(), anyCollection())).thenReturn(Map.of(
                    done.goodsReceiptLineId(), receivedInto(done, RCV),
                    cancelled.goodsReceiptLineId(), receivedInto(cancelled, RCV)));
            when(warehouses.findLocation(binId)).thenReturn(Optional.of(new StorageLocationView(binId,
                    StorageLocationView.Kind.BIN, warehouse, BIN,
                    StorageLocationView.Status.ACTIVE, StorageLocationView.Status.ACTIVE, true)));
            return service.list(null, receipt, null, 0, 20);
        }

        @Test
        @DisplayName("a done loose task stands at its target bin, not where it was received")
        void doneAtItsTarget() {
            TaskView view = byId(listed(), done.id());

            assertThat(view.sourceLocationCode()).isEqualTo(BIN);
            assertThat(view.targetLocationCode()).isEqualTo(BIN);
            assertThat(view.lpn()).isNull();
            assertThat(view.left()).isZero();
        }

        @Test
        @DisplayName("a cancelled loose task without a target stands where its receipt line set the goods down")
        void cancelledAtItsReceiptLine() {
            TaskView view = byId(listed(), cancelled.id());

            assertThat(view.sourceLocationCode()).isEqualTo(RCV);
            assertThat(view.targetLocationCode()).isNull();
            assertThat(view.lotNumber()).isEqualTo("L7");
        }

        @Test
        @DisplayName("a loose task with neither a target nor a receipt line is left out; the others are listed")
        void unlocatableIsLeftOut() {
            PageResponse<TaskView> page = listed();

            assertThat(page.items()).extracting(TaskView::id).containsExactly(done.id(), cancelled.id());
        }

        @Test
        @DisplayName("loose tasks are located from the ledger alone: no carton is read for them")
        @SuppressWarnings("unchecked")
        void noCartonRead() {
            listed();

            org.mockito.ArgumentCaptor<Collection<StockPackageId>> ids =
                    org.mockito.ArgumentCaptor.forClass(Collection.class);
            verify(stockPackages).findAllById(ids.capture());
            assertThat(ids.getValue()).isEmpty();
        }

        private static TaskView byId(PageResponse<TaskView> page, UUID id) {
            return page.items().stream().filter(view -> view.id().equals(id)).findFirst().orElseThrow();
        }
    }
}
