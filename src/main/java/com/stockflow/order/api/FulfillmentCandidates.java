package com.stockflow.order.api;

import com.stockflow.common.api.PageResponse;

import java.util.UUID;

/**
 * The orders waiting for a fulfillment task (kltn-docs 07 step 1): what fulfillment offers whoever opens tasks.
 * Separate from {@link OrderService#list}, which is the sales order screen's and answers to its permission.
 */
public interface FulfillmentCandidates {

    /**
     * The orders a fulfillment task may be opened for, by the rule createTask applies (review of #150): READY_TO_FULFILL,
     * or IN_PRODUCTION with a stock line (its stock is picked while it is printed, PACK-EARLY) - and no task opened for
     * it yet ({@code fulfilment_started_at}, which the order records when createTask calls
     * {@link OrderService#startPicking}). Oldest release first, each with its lines. Only orders of the caller's
     * warehouses when the caller is limited to some (the ambient warehouse scope); {@code warehouseId}, when given,
     * narrows to one. Read-only: it joins the caller's transaction when there is one and locks nothing - by the time a
     * task is opened, createTask checks the order again under its row lock.
     */
    PageResponse<FulfillmentCandidate> awaitingTask(UUID warehouseId, int page, int size);
}
