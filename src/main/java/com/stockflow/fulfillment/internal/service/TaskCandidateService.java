package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.order.api.FulfillmentCandidate;
import com.stockflow.order.api.FulfillmentCandidates;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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

    TaskCandidateService(FulfillmentCandidates orders, WarehouseService warehouses) {
        this.orders = orders;
        this.warehouses = warehouses;
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
        return orders.awaitingTask(warehouseId, page, size);
    }
}
