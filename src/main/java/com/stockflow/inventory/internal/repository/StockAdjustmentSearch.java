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
     * @param warehousePrefix  only the adjustments of locations in this warehouse (its code prefix, 06 BR-13); null for
     *                         any
     */
    record Criteria(AdjustmentStatus status, String sku, String location, boolean hideCountedSlots,
                    String warehousePrefix) {
        public Criteria(AdjustmentStatus status, String sku, String location) {
            this(status, sku, location, false);
        }

        public Criteria(AdjustmentStatus status, String sku, String location, boolean hideCountedSlots) {
            this(status, sku, location, hideCountedSlots, null);
        }
    }

    Page<StockAdjustment> search(Criteria criteria, Pageable pageable);
}
