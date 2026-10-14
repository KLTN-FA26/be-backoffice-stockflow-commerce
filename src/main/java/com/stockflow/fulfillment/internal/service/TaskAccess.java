package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;

import java.util.Objects;

/** Who may see a task, and whether its order still lets it be worked; shared by the task and pick-line services. */
final class TaskAccess {

    private TaskAccess() {
    }

    /**
     * The assignee sees the task; managers, QC and coordinators see the tasks of the warehouses they are
     * assigned to (BR-SEC-002: a coordinator is not limited, as their scope is ALL).
     */
    static void requireVisible(PickJpaEntity pick, CurrentUser actor) {
        if (Objects.equals(pick.getAssignedUserId(), actor.userId())) {
            return;
        }
        if (!actor.hasAnyRole(Role.WAREHOUSE_MANAGER, Role.QC_STAFF, Role.ORDER_COORDINATOR)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
    }

    /**
     * A task is only workable while its order is still in fulfilment (or held by it). Cancelling an
     * order releases its stock but leaves the pick and pack rows behind, so without this check a
     * warehouse user could go on picking and packing goods that were already returned to stock.
     */
    static OrderSummary orderInFulfilment(OrderService orders, PickJpaEntity pick) {
        var order = orders.findById(pick.getOrderId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (order.status() != OrderStatus.IN_FULFILMENT && order.status() != OrderStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "The order is " + order.status() + "; this fulfillment task is closed");
        }
        return order;
    }
}
