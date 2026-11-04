package com.stockflow.inventory.api;

import com.stockflow.common.api.PageResponse;

/**
 * Stock adjustments for other modules (the assistant, ADR-0010): the list the adjustment screen shows, the approver's
 * queue of PENDING_APPROVAL among them (SCRUM-145).
 *
 * <p><b>Scope.</b> The caller's warehouse limit is applied exactly as on {@code GET /api/v1/inventory/adjustments}: a
 * warehouse-limited caller sees the adjustments of their own warehouses' bins. Call it inside the same guard as that
 * endpoint, {@code @RequiresPermission(resource = "inventory-stock-adjustments", action = READ)}.</p>
 */
public interface StockAdjustmentQueries {

    /**
     * Adjustments as the screen lists them; every filter optional.
     *
     * @param status   only adjustments in this status; null for all
     * @param sku      only this SKU
     * @param location only this bin, by its full code
     * @param sort     the screen's: {@code createdAt}, {@code status} or {@code sku}, with {@code ,asc} or {@code ,desc};
     *                 null for the newest first
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a sort the screen does not
     *         allow
     */
    PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                    int page, int size, String sort);
}
