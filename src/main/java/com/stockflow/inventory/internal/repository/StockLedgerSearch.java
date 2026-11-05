package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The ledger's read side, for the stock history screen (SCRUM-145, FE 439). Outside the domain
 * port on purpose: paging and filtering are Spring Data's vocabulary, and
 * {@code ArchitectureTest} keeps that vocabulary out of {@code internal.domain}.
 */
public interface StockLedgerSearch {

    /**
     * @param location matches either side of a line — a move out of A and a move into A are both
     *                 "history of A"
     * @param hideCountedSlots leave out the lines of slots a cycle count holds: summed, a bin's history is its book
     *                 quantity, which a blind counter must not read (SCRUM-146)
     */
    record Criteria(String sku, String location, StockMovement.MovementType type, boolean hideCountedSlots) {
        public Criteria(String sku, String location, StockMovement.MovementType type) {
            this(sku, location, type, false);
        }
    }

    Page<StockMovement> search(Criteria criteria, Pageable pageable);
}
