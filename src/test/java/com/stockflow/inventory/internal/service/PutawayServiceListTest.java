package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.service.PutawayService.TaskView;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-92: the list of open putaway tasks of a warehouse does not depend on every task being resolvable. One task
 * whose carton cannot be found must not turn the list into a 404 for everyone; a single task read stays strict.
 */
class PutawayServiceListTest {

    private static final String BIN = "WH1-RCV01";

    private final PutawayTasks tasks = mock(PutawayTasks.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final StockItemRepository stockItems = mock(StockItemRepository.class);
    private final StockMovementLog ledger = mock(StockMovementLog.class);
    private final PutawayService service = new PutawayService(tasks, warehouses, stockPackages, stockItems, ledger,
            mock(InventoryPolicyRepository.class), mock(BinOccupancyQuery.class), mock(StockOperations.class),
            mock(ApplicationEventPublisher.class));

    private final UUID warehouse = UUID.randomUUID();
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
        when(carton.location()).thenReturn(new LocationId(BIN));
        when(tasks.openTasks()).thenReturn(List.of(healthy, broken));
        when(stockPackages.findAllById(any())).thenReturn(List.of(carton));
        StorageLocationView location = mock(StorageLocationView.class);
        when(location.locationCode()).thenReturn(BIN);
        when(location.warehouseId()).thenReturn(warehouse);
        when(warehouses.findLocations(any())).thenReturn(List.of(location));
    }

    @Test
    @DisplayName("a task whose carton cannot be found is left out of the warehouse list, the others are listed")
    void brokenTaskIsSkipped() {
        twoOpenTasksOneWithoutItsCarton();

        PageResponse<TaskView> page = service.list(warehouse, null, null, 0, 20);

        assertThat(page.items()).extracting(TaskView::id).containsExactly(healthy.id());
    }

    @Test
    @DisplayName("the list reads the tasks' own cartons by id, not every carton of the open lines")
    @SuppressWarnings("unchecked")
    void readsOnlyTheTasksCartons() {
        twoOpenTasksOneWithoutItsCarton();

        service.list(warehouse, null, null, 0, 20);

        verify(stockPackages, never()).findByReceiptLines(any());
        org.mockito.ArgumentCaptor<Collection<StockPackageId>> ids = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(stockPackages).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(healthyCarton, new StockPackageId(broken.stockPackageId()));
    }

    @Test
    @DisplayName("a single task whose carton cannot be found is still PUTAWAY_TASK_NOT_FOUND")
    void getStaysStrict() {
        when(tasks.find(broken.id())).thenReturn(Optional.of(broken));
        when(stockPackages.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.get(broken.id())).isInstanceOf(BusinessException.class);
    }
}
