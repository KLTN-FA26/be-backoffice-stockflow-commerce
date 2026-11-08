package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.order.api.FulfillmentCandidate;
import com.stockflow.order.api.FulfillmentCandidates;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The small collaborators of the fulfillment services: the task-creation list, shipment context, partial rules. */
class SmallServicesTest {

    @Test
    @DisplayName("the create-task list refuses a warehouse out of scope before saying whether it exists, then an unknown one")
    void candidatesCheckScopeBeforeExistence() {
        FulfillmentCandidates orders = mock(FulfillmentCandidates.class);
        WarehouseService warehouses = mock(WarehouseService.class);
        TaskCandidateService service = new TaskCandidateService(orders, warehouses);

        WithCurrentUser.run(FulfillmentFixtures.userOf(WAREHOUSE), DataScope.WAREHOUSE, () -> {
            assertThat(refusal(() -> service.candidates(OTHER_WAREHOUSE, 0, 20)).errorCode())
                    .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
            when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.empty());
            assertThat(refusal(() -> service.candidates(WAREHOUSE, 0, 20)).errorCode())
                    .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        });
        verifyNoInteractions(orders);
    }

    @Test
    @DisplayName("the create-task list asks the orders for those awaiting a task, narrowed to the warehouse when given")
    void candidatesAreTheOrdersAwaitingATask() {
        FulfillmentCandidates orders = mock(FulfillmentCandidates.class);
        WarehouseService warehouses = mock(WarehouseService.class);
        when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(
                new WarehouseView(WAREHOUSE, "HCM", "HCM warehouse", "1 Le Loi", true)));
        PageResponse<FulfillmentCandidate> page = PageResponse.of(List.of(), 0, 20, 0);
        when(orders.awaitingTask(WAREHOUSE, 0, 20)).thenReturn(page);
        when(orders.awaitingTask(null, 1, 5)).thenReturn(page);
        TaskCandidateService service = new TaskCandidateService(orders, warehouses);

        assertThat(service.candidates(WAREHOUSE, 0, 20)).isSameAs(page);
        assertThat(service.candidates(null, 1, 5)).isSameAs(page);
        verify(warehouses, never()).findWarehouse(null);
    }

    @Test
    @DisplayName("a shipment's task is the pick of its pack; a shipment made without a pack has none")
    void tasksOfShipments() {
        PackJpaRepository packs = mock(PackJpaRepository.class);
        ShipmentContext context = new ShipmentContext(packs);
        UUID pickId = UUID.randomUUID();
        PackJpaEntity pack = new PackJpaEntity(UUID.randomUUID(), pickId, PackStatus.PACKED, NOW);
        ShipmentJpaEntity withPack = FulfillmentFixtures.row(com.stockflow.fulfillment.internal.domain.Shipment.ready(
                UUID.randomUUID(), "S1", UUID.randomUUID(), pack.getId(), WAREHOUSE, null, 1, null, UUID.randomUUID(),
                NOW));
        ShipmentJpaEntity noPack = FulfillmentFixtures.row(com.stockflow.fulfillment.internal.domain.Shipment.ready(
                UUID.randomUUID(), "S2", UUID.randomUUID(), null, WAREHOUSE, null, 1, null, UUID.randomUUID(), NOW));
        when(packs.findAllById(Set.of(pack.getId()))).thenReturn(List.of(pack));

        Map<UUID, UUID> tasks = context.tasks(List.of(withPack, noPack));

        assertThat(tasks).containsOnly(Map.entry(withPack.getId(), pickId));
        assertThat(context.tasks(List.of(noPack))).isEmpty();
        verify(packs, org.mockito.Mockito.times(1)).findAllById(any());
    }

    @Test
    @DisplayName("an order may ship in parts when Sales allowed it, and also when it cannot be read or has no lifecycle (not checked)")
    void mayShipInParts() {
        UUID id = UUID.randomUUID();
        assertThat(PartialShipments.mayShipInParts(null)).isTrue();
        assertThat(PartialShipments.mayShipInParts(FulfillmentFixtures.order(id, null))).isTrue();
        assertThat(PartialShipments.mayShipInParts(withLifecycle(id, false, 10, 0, 0))).isFalse();
        assertThat(PartialShipments.mayShipInParts(withLifecycle(id, true, 10, 0, 0))).isTrue();
    }

    @Test
    @DisplayName("the units an order still wants to ship are ordered less closed less already shipped")
    void outstandingUnits() {
        UUID id = UUID.randomUUID();

        assertThat(PartialShipments.outstandingUnits(withLifecycle(id, false, 10, 0, 0))).isEqualTo(10);
        assertThat(PartialShipments.outstandingUnits(withLifecycle(id, false, 10, 3, 2))).isEqualTo(5);
        assertThat(PartialShipments.outstandingUnits(withLifecycle(id, false, 10, 10, 0))).isZero();
    }

    private static OrderSummary withLifecycle(UUID id, boolean allowPartial, int quantity, int shipped, int closed) {
        UUID line = UUID.randomUUID();
        var summary = new OrderSummary.LineSummary(line, "SKU-A", quantity, Money.vnd(1000), Money.vnd(1000L * quantity),
                List.of());
        var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(new OrderSummary.LineProgress(line, shipped, 0,
                closed)), List.of(), allowPartial);
        return new OrderSummary(id, "SO-1", UUID.randomUUID(), OrderStatus.PACKED, Money.vnd(1000L * quantity),
                List.of(summary), NOW, null, null, null, null, null, null, null, null, null, WAREHOUSE, NOW, null, null,
                lifecycle);
    }
}
