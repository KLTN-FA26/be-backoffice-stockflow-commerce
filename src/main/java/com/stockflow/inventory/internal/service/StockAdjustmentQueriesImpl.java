package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.api.StockAdjustmentQueries;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link StockAdjustmentQueries} over {@link StockOperations#adjustments}, the screen's own list: the same filters,
 * sort and warehouse limit, so the two cannot disagree.
 */
@Service
@Transactional(readOnly = true)
class StockAdjustmentQueriesImpl implements StockAdjustmentQueries {

    private final StockOperations operations;

    StockAdjustmentQueriesImpl(StockOperations operations) {
        this.operations = operations;
    }

    @Override
    public PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                           int page, int size, String sort) {
        return operations.adjustments(status, sku, location, page, size, sort);
    }
}
