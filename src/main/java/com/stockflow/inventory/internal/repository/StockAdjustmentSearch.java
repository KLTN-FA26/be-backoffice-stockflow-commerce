package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.AdjustmentStatus;
import com.stockflow.inventory.internal.domain.StockAdjustment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read side of stock adjustments: the approver's queue and the history. See {@link StockLedgerSearch}. */
public interface StockAdjustmentSearch {

    /**
     * @param hideCountedSlots leave out the adjustments of slots a cycle count holds, the pending variance of the
     *                         count included: its delta against the books would give the book quantity away (SCRUM-146)
     */
    record Criteria(AdjustmentStatus status, String sku, String location, boolean hideCountedSlots) {
        public Criteria(AdjustmentStatus status, String sku, String location) {
            this(status, sku, location, false);
        }
    }

    Page<StockAdjustment> search(Criteria criteria, Pageable pageable);
}
