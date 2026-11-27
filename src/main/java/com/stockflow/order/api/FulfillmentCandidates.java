package com.stockflow.order.api;

import com.stockflow.common.api.PageResponse;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The orders waiting for a fulfillment task (kltn-docs 07 step 1): what fulfillment offers whoever opens tasks.
 * Separate from {@link OrderService#list}, which is the sales order screen's and answers to its permission.
 */
public interface FulfillmentCandidates {

    /**
     * The batch each PARTIALLY_FULFILLED order of {@code warehouseId} (any when null) was last released for. Whether a
     * task exists for it is fulfillment's to say; it answers with {@code awaitingBatchOrders} of
     * {@link #awaitingTask}. Read-only, no warehouse scope: the ids are used to ask, never shown.
     */
    List<ReleasedBatchOfOrder> lastReleasedBatches(UUID warehouseId);

    /**
     * The orders a fulfillment task may be opened for, by the rule createTask applies (review of #150): READY_TO_FULFILL
     * and no task opened for it yet ({@code fulfilment_started_at}, which the order records when createTask calls
     * {@link OrderService#startPicking}) - and, kltn-docs 07 BR-07, the PARTIALLY_FULFILLED orders among
     * {@code awaitingBatchOrders}: those whose latest released delivery batch has no task yet - and, 07 §4.1 step 1,
     * the AWAITING_STOCK orders with no task whose next delivery batch has cartons held, not all, at their shipping
     * warehouse ({@link PrePicking}). Oldest release first, the orders awaiting stock last, each with its lines. Only
     * orders of the caller's
     * warehouses when the caller is limited to some (the ambient warehouse scope); {@code warehouseId}, when given,
     * narrows to one. Read-only: it joins the caller's transaction when there is one and locks nothing - by the time a
     * task is opened, createTask checks the order again under its row lock.
     */
    PageResponse<FulfillmentCandidate> awaitingTask(UUID warehouseId, Collection<UUID> awaitingBatchOrders, int page,
                                                    int size);
}
