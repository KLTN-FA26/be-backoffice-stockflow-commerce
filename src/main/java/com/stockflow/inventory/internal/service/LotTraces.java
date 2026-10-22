package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.internal.domain.LotTrace;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The lot traceability report (SCRUM-153, WBS 3.6.2.3): for a lot, where it came from — supplier, purchase order,
 * goods receipt — every ledger line it moved through, the allocations and holds placed on it, the customer orders
 * it was picked for, and what is left of it now, where.
 *
 * <p>Read-only, and limited to the caller's warehouses (BR-SEC-002): a warehouse-bound user sees lots that were in
 * one of their warehouses, and only the balances, holds and ledger lines inside them.</p>
 *
 * <p>Inside {@code internal}, like {@link StockOperations}: only this module's web layer reads it.</p>
 */
public interface LotTraces {

    /**
     * @param sku       exact SKU, any case; null for every SKU
     * @param lotNumber exact lot number as printed; null for any — two lots may share one (two receipts)
     * @param warehouse only lots that were in this warehouse, with their balances there
     * @param from      first business day (Asia/Ho_Chi_Minh) a lot must have moved on, inclusive; null for no limit
     * @param to        last business day, inclusive
     */
    record Query(String sku, String lotNumber, UUID warehouse, LocalDate from, LocalDate to, int page, int size) { }

    /**
     * Lots matching the query, most recently received first.
     *
     * @throws com.stockflow.common.error.BusinessException {@code WAREHOUSE_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code VALIDATION_FAILED} when {@code to} is before {@code from}
     */
    PageResponse<LotTrace.Summary> lots(Query query);

    /**
     * One lot, traced. {@code warehouse} narrows the balances, holds and orders to it.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} for an unknown lot or one the caller's
     *         warehouses never held
     */
    LotTrace trace(UUID lotId, UUID warehouse);

    /** The lot's ledger lines, oldest first — the order it moved in — within the warehouse and days asked for. */
    PageResponse<LotTrace.Movement> movements(UUID lotId, UUID warehouse, LocalDate from, LocalDate to, int page,
                                              int size);
}
