package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.order.api.FulfillmentCandidate;
import com.stockflow.order.api.FulfillmentCandidates;
import com.stockflow.order.api.ReleasedBatchOfOrder;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The create-task dialog's order list. It used the sales order list, whose permission ({@code sales-orders:READ}) no
 * warehouse role holds, so a warehouse manager could open tasks but not choose an order for one. This list answers
 * to the task-opening permission instead and shows only what opening a task needs.
 */
@Service
@Transactional(readOnly = true)
class TaskCandidateService implements TaskCandidates {

    private final FulfillmentCandidates orders;
    private final WarehouseService warehouses;
    private final PickJpaRepository picks;

    TaskCandidateService(FulfillmentCandidates orders, WarehouseService warehouses, PickJpaRepository picks) {
        this.orders = orders;
        this.warehouses = warehouses;
        this.picks = picks;
    }

    @Override
    public PageResponse<FulfillmentCandidate> candidates(UUID warehouseId, int page, int size) {
        if (warehouseId != null) {
            // Scope first: whether another warehouse exists is not an out-of-scope caller's to learn.
            WarehouseScope.requireWarehouse(warehouseId);
            if (warehouses.findWarehouse(warehouseId).isEmpty()) {
                throw new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND);
            }
        }
        return orders.awaitingTask(warehouseId, ordersWithBatchToPick(warehouseId), page, size);
    }

    /**
     * 07 BR-07: an order released by delivery batch has a task per batch, so a PARTIALLY_FULFILLED order whose latest
     * released batch has no task yet is waiting for one (what {@code createTask} opens for it: {@code batchToPick}).
     * The orders do not know their tasks; the picks do, read once for the few orders that have a batch released.
     */
    private Set<UUID> ordersWithBatchToPick(UUID warehouseId) {
        List<ReleasedBatchOfOrder> released = orders.lastReleasedBatches(warehouseId);
        if (released.isEmpty()) {
            return Set.of();
        }
        Set<UUID> picked = picks.findByOrderIdIn(released.stream().map(ReleasedBatchOfOrder::orderId).toList())
                .stream().map(PickJpaEntity::getDeliveryBatchId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return released.stream().filter(batch -> !picked.contains(batch.batchId()))
                .map(ReleasedBatchOfOrder::orderId).collect(Collectors.toSet());
    }
}
