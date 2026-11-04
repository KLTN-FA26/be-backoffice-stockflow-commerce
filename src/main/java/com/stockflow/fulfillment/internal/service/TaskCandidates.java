package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.order.api.FulfillmentCandidate;

import java.util.UUID;

/**
 * The orders a fulfillment task may be opened for now, for whoever opens tasks (kltn-docs 07 step 1). Inside
 * {@code internal}, like {@link PickLists}: only this module's web layer reads it.
 */
public interface TaskCandidates {

    /**
     * READY_TO_FULFILL orders - the only ones {@code createTask} accepts - of the caller's warehouses, oldest release
     * first, with their lines; {@code warehouseId} narrows to one warehouse.
     *
     * @throws com.stockflow.common.error.BusinessException {@code WAREHOUSE_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} for a
     *         warehouse the caller is not assigned to
     */
    PageResponse<FulfillmentCandidate> candidates(UUID warehouseId, int page, int size);
}
