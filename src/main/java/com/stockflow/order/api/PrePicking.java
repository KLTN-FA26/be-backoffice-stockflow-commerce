package com.stockflow.order.api;

import java.util.UUID;

/**
 * Picking an order AWAITING_STOCK before its release (kltn-docs 07 §4.1 steps 1-2, §4.3 step 7, §6 edge cases; 08 §4.1
 * step 1; 17 §4.4 steps 15-16, §4.5 step 18): the warehouse takes the cartons already held for its next delivery batch -
 * to the staging area, or to dispatch when Sales allowed a partial shipment - and tops the same task up as the rest
 * arrives. The order stays AWAITING_STOCK until that batch is held in full; then it is released as any order is.
 * Fulfillment's: it opens and tops up the task, under the pick's lock, in its own transaction.
 */
public interface PrePicking {

    /**
     * The cartons held for the order's next delivery batch become allocations on bins of its shipping warehouse (20
     * §5.6: whole cartons, k of the N the batch needs), and the order records that its task is open
     * ({@code fulfilment_started_at}). It stays AWAITING_STOCK and is not released. Locks the order, then inventory's
     * rows.
     *
     * @return the batch the task picks, and where
     * @throws com.stockflow.common.error.BusinessException {@code PICK_TASK_STATE_CONFLICT} for an order not
     *         AWAITING_STOCK, released already, without a delivery schedule, or whose next batch is held in full (it is
     *         released instead, 17 §4.4 step 16); {@code ORDER_STOCK_NOT_HELD} when nothing is held for that batch yet
     */
    PrePick prePick(UUID orderId, UUID by);

    /**
     * The top-up of a task opened before the order's release (07 §4.3 step 7, 17 §4.4 step 16): what was held for its
     * batch since becomes allocations, and - the batch now held in full and the order not on hold - the batch is
     * released (17 §4.2 step 5): AWAITING_STOCK → READY_TO_FULFILL, or PARTIALLY_FULFILLED kept when its part held left
     * first. Nothing for an order released already: its release allocated the batch. Locks the order.
     *
     * @return whether the batch was released now
     */
    boolean topUp(UUID orderId, UUID deliveryBatchId, UUID by);

    /**
     * The part held goes towards dispatch first (17 §4.5 step 18; the task opened to dispatch, or its staged cartons
     * moved there), Sales having allowed a partial shipment: the first delivery is ready, so the installment before
     * delivery falls due (owner decision SCOPE-PARTIAL-1105; 15 §4.1 step 2). The order stays AWAITING_STOCK. Locks
     * the order.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_INVALID_TRANSITION} for an order not awaiting
     *         stock, not picked ahead, or not allowed to ship in parts
     */
    void partGoesFirst(UUID orderId, UUID deliveryBatchId);

    /**
     * @param deliveryBatchId the batch the task picks
     * @param warehouseId     the order's shipping warehouse, where the cartons are
     */
    record PrePick(UUID deliveryBatchId, int seq, UUID warehouseId) {
    }
}
