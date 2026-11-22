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

    /**
     * The order statuses a task may be worked in: from release until everything left, and while it is held - and
     * AWAITING_STOCK, whose part held is picked before its release (kltn-docs 07 §4.1 step 2, 17 §4.4 step 15).
     */
    private static final java.util.Set<OrderStatus> WORKABLE = java.util.EnumSet.of(OrderStatus.READY_TO_FULFILL,
            OrderStatus.PICKING, OrderStatus.PACKED, OrderStatus.PARTIALLY_FULFILLED, OrderStatus.ON_HOLD,
            OrderStatus.AWAITING_STOCK);

    /**
     * The assignee sees the task; so does whoever oversees tasks, for the warehouses they are assigned to (BR-SEC-002:
     * a caller whose scope is ALL is not limited). Once picking is done the task is at the
     * packing table, where any packer of its warehouse may take it (G4-5, docs 08): they see it too. Seeded, that is
     * managers, and warehouse staff at packing (V20261022000200). The assignee too must still be assigned to the task's
     * warehouse: a clerk taken off it no longer reads its tasks, as they could no longer work them.
     */
    static void requireVisible(PickJpaEntity pick, CurrentUser actor) {
        boolean assignee = Objects.equals(pick.getAssignedUserId(), actor.userId());
        if (!assignee && !actor.can(FulfillmentPermissions.TASK_OVERSIGHT, Action.READ)
                && !(actor.can(FulfillmentPermissions.PACKING, Action.UPDATE) && atPacking(pick))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
    }

    /**
     * Who may work a pick task (07 BR-08, and the actor table of 07 §2: the warehouse manager "works on behalf when
     * needed"; WF4-QLK-TAKEOVER). The assignee does; so does whoever holds task assignment ({@code
     * fulfillment-task-assignment:UPDATE}) for a warehouse of their scope - the caller checked the scope - even once
     * picking has started. Permission codes only. Anyone else is refused as before ({@code NOT_FOUND}).
     *
     * @return the user the task is worked as: the caller for the assignee, the assignee for the one working on behalf
     */
    static java.util.UUID requireWorker(PickJpaEntity pick, CurrentUser actor) {
        // A task nobody is assigned to is nobody's to work on behalf of: refused as before, never given to the manager.
        if (pick.getAssignedUserId() != null && actor.can(FulfillmentPermissions.TASK_ASSIGNMENT, Action.UPDATE)) {
            return pick.getAssignedUserId();
        }
        pick.requireAssigned(actor.userId());
        return actor.userId();
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
    /**
     * The order's status read again under its row lock, after work that took no lock (photos stored): an order
     * cancelled meanwhile is refused here, before anything is allocated for it - a replacement allocation for a
     * cancelled order would be held for nobody, for good.
     */
    static void requireStillInFulfilment(OrderService orders, PickJpaEntity pick) {
        com.stockflow.order.api.OrderStatus status = orders.lockedStatus(pick.getOrderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!WORKABLE.contains(status)) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "The order is " + status + "; this fulfillment task is closed");
        }
    }

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
