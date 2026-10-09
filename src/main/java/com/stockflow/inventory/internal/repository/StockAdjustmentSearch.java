package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.AdjustmentStatus;
import com.stockflow.inventory.internal.domain.StockAdjustment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read side of stock adjustments: the approver's queue and the history. See {@link StockLedgerSearch}. */
public interface StockAdjustmentSearch {

    record Criteria(AdjustmentStatus status, String sku, String location) {
    }

    Page<StockAdjustment> search(Criteria criteria, Pageable pageable);
}
