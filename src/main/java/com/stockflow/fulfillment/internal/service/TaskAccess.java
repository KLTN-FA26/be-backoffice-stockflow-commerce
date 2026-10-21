package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
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

    private static final java.util.Set<OrderStatus> WORKABLE = java.util.EnumSet.of(OrderStatus.PICKING,
            OrderStatus.PACKED, OrderStatus.PARTIALLY_FULFILLED, OrderStatus.ON_HOLD);

    /**
     * The assignee sees the task; so does whoever oversees tasks or re-verifies designs, for the warehouses they are
     * assigned to (BR-SEC-002: a caller whose scope is ALL is not limited). Once picking is done the task is at the
     * packing table, where any packer of its warehouse may take it (G4-5, docs 08): they see it too. Seeded, that is
     * managers, QC and coordinators, and warehouse staff at packing (V20261021000600).
     */
    static void requireVisible(PickJpaEntity pick, CurrentUser actor) {
        if (Objects.equals(pick.getAssignedUserId(), actor.userId())) {
            return;
        }
        if (!actor.can(FulfillmentPermissions.TASK_OVERSIGHT, Action.READ)
                && !actor.can(FulfillmentPermissions.DESIGN_REVERIFICATION, Action.READ)
                && !(actor.can(FulfillmentPermissions.PACKING, Action.UPDATE) && atPacking(pick))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
    }

    /**
     * The confirmed design files of a task's print lines (docs 08 step 2: printed goods are checked against the
     * design at packing): the assignee; whoever re-verifies designs (managers and QC) in the task's warehouse; and,
     * once the task is at the packing table, a packer of its warehouse. Anyone else: not found.
     */
    static void requireArtifactAccess(PickJpaEntity pick, CurrentUser actor) {
        if (Objects.equals(pick.getAssignedUserId(), actor.userId())) {
            return;
        }
        if (!actor.can(FulfillmentPermissions.DESIGN_REVERIFICATION, Action.READ)
                && !(actor.can(FulfillmentPermissions.PACKING, Action.UPDATE) && atPacking(pick))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
    }

    /** Picking is complete: the goods wait at the packing area and the task is the packer's to work. */
    static boolean atPacking(PickJpaEntity pick) {
        return pick.getStatus() == com.stockflow.fulfillment.internal.domain.PickStatus.PICKED;
    }

    /**
     * A task is only workable while its order is still in fulfilment (or held by it). Cancelling an
     * order releases its stock but leaves the pick and pack rows behind, so without this check a
     * warehouse user could go on picking and packing goods that were already returned to stock.
     */
    static OrderSummary orderInFulfilment(OrderService orders, PickJpaEntity pick) {
        var order = orders.findById(pick.getOrderId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // G3-2: the warehouse's statuses - picking, packed, the rest of a split order - or held from one of them.
        if (!WORKABLE.contains(order.status())) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "The order is " + order.status() + "; this fulfillment task is closed");
        }
        return order;
    }
}
