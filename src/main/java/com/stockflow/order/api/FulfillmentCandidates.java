package com.stockflow.order.api;

import com.stockflow.common.api.PageResponse;

import java.util.UUID;

/**
 * The orders waiting for a fulfillment task (kltn-docs 07 step 1): what fulfillment offers whoever opens tasks.
 * Separate from {@link OrderService#list}, which is the sales order screen's and answers to its permission.
 */
public interface FulfillmentCandidates {

    /**
     * READY_TO_FULFILL orders, oldest release first, each with its lines. Only orders of the caller's warehouses when
     * the caller is limited to some (the ambient warehouse scope); {@code warehouseId}, when given, narrows to one.
     * Joins no transaction of the caller's and locks nothing: by the time a task is opened, createTask checks the
     * order again under its row lock.
     */
    PageResponse<FulfillmentCandidate> readyToFulfill(UUID warehouseId, int page, int size);
}
