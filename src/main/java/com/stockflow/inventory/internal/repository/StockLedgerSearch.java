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
     * @param occurredFrom   lines at or after this instant; null for no lower bound
     * @param occurredBefore lines before this instant; null for no upper bound - the stock summary drills down to the
     *                 lines of its period (2026-11-14 plan, BE2-C)
     * @param warehousePrefix lines with either side in this warehouse (its location-code prefix); null for any
     * @param stockPackageId the lines about this carton ({@code stock_package_id}, indexed); null for any
     */
    record Criteria(String sku, String location, StockMovement.MovementType type, boolean hideCountedSlots,
                    java.time.Instant occurredFrom, java.time.Instant occurredBefore, String warehousePrefix,
                    java.util.UUID stockPackageId) {
        public Criteria(String sku, String location, StockMovement.MovementType type, boolean hideCountedSlots,
                        java.time.Instant occurredFrom, java.time.Instant occurredBefore, String warehousePrefix) {
            this(sku, location, type, hideCountedSlots, occurredFrom, occurredBefore, warehousePrefix, null);
        }

        public Criteria(String sku, String location, StockMovement.MovementType type) {
            this(sku, location, type, false);
        }

        public Criteria(String sku, String location, StockMovement.MovementType type, boolean hideCountedSlots) {
            this(sku, location, type, hideCountedSlots, null, null, null, null);
        }
    }

    Page<StockMovement> search(Criteria criteria, Pageable pageable);
}
