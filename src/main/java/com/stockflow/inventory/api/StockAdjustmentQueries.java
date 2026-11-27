package com.stockflow.inventory.api;

import com.stockflow.common.api.PageResponse;

/**
 * Stock adjustments for other modules (the assistant, ADR-0011): the list the adjustment screen shows, the approver's
 * queue of PENDING_APPROVAL among them (SCRUM-145).
 *
 * <p><b>Scope.</b> The caller's warehouse limit is applied exactly as on {@code GET /api/v1/inventory/adjustments}: a
 * warehouse-limited caller sees the adjustments of their own warehouses' bins. Call it inside the same guard as that
 * endpoint, {@code @RequiresPermission(resource = "inventory-stock-adjustments", action = READ)}.</p>
 *
 * <p><b>Blind count</b> (kltn-docs 20 BR-07). As on that screen, a caller without
 * {@code inventory-cycle-counts:APPROVE} does not get the adjustments of a slot a count holds (open, or its variance
 * still pending): a pending {@code COUNT_VARIANCE} is a delta against the books the counter is to find. The caller is
 * read from the security context; an absent one is treated as blind.</p>
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
    default PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                            int page, int size, String sort) {
        return adjustments(status, sku, location, null, page, size, sort);
    }

    /**
     * As above, kept to one warehouse: the adjustments of the locations whose code starts with its prefix (06 BR-13),
     * for the assistant's work queue narrowed to the warehouse a manager runs a shift at.
     *
     * @param warehousePrefix the warehouse's code prefix; null for every warehouse the caller may see
     */
    PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                    String warehousePrefix, int page, int size, String sort);
}
