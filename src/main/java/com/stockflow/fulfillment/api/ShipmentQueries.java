package com.stockflow.fulfillment.api;

import com.stockflow.common.api.PageResponse;

/**
 * Shipments for other modules (the assistant, ADR-0010): the list the shipment screen shows, newest first, or the
 * oldest first to work a queue from its head.
 *
 * <p><b>Scope.</b> The caller's warehouse limit is applied exactly as on {@code GET /api/v1/fulfillment/shipments}: a
 * warehouse-limited caller sees the shipments of their own warehouses. Call it inside the same guard as that endpoint,
 * {@code @RequiresPermission(resource = "fulfillment-shipments", action = READ)}.</p>
 */
public interface ShipmentQueries {

    /**
     * @param status      one of {@link ShipmentSummary#status()}'s values; null for every status
     * @param search      text inside the shipment code, which starts with the order number; null for none
     * @param oldestFirst the longest ready first instead of the most recently ready
     * @throws com.stockflow.common.error.BusinessException {@code UNSUPPORTED_PARAMETER} for a status that is not one
     */
    PageResponse<ShipmentSummary> shipments(String status, String search, boolean oldestFirst, int page, int size);
}
