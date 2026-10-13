package com.stockflow.fulfillment.api;

import java.util.UUID;

/**
 * Filters of the task list; every one is optional and they combine with AND. The caller's role and
 * warehouse assignments narrow the result further.
 *
 * @param status         a pick status name ({@code PENDING}, {@code PICKING}, ...); case-insensitive
 * @param warehouseId    only this warehouse's tasks; refused with OUT_OF_DATA_SCOPE for one the caller
 *                       is not assigned to
 * @param assignedUserId only this person's tasks
 */
public record FulfillmentTaskQuery(String status, UUID warehouseId, UUID assignedUserId, int page, int size) {
}
