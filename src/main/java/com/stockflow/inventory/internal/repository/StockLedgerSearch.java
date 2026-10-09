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
     */
    record Criteria(String sku, String location, StockMovement.MovementType type) {
    }

    Page<StockMovement> search(Criteria criteria, Pageable pageable);
}
