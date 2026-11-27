package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.StockAdjustmentQueries;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link StockAdjustmentQueries} over {@link StockOperations#adjustments}, the screen's own list: the same filters,
 * sort and warehouse limit, so the two cannot disagree.
 *
 * <p><b>The blind count too.</b> The screen passes {@code BlindCount.visibilityFor(user)}, which leaves out the
 * adjustments of slots under a count for a counter: a pending {@code COUNT_VARIANCE} is a delta against the books
 * (kltn-docs 20 BR-07). The caller is classified here by the same rule ({@link BookVisibility#of}), never left at the
 * {@code FULL} default.</p>
 */
@Service
@Transactional(readOnly = true)
class StockAdjustmentQueriesImpl implements StockAdjustmentQueries {

    private final StockOperations operations;
    private final CurrentUserProvider currentUsers;

    StockAdjustmentQueriesImpl(StockOperations operations, CurrentUserProvider currentUsers) {
        this.operations = operations;
        this.currentUsers = currentUsers;
    }

    @Override
    public PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                           String warehousePrefix, int page, int size, String sort) {
        return operations.adjustments(status, sku, location, warehousePrefix, page, size, sort,
                BookVisibility.of(currentUsers.current()));
    }
}
