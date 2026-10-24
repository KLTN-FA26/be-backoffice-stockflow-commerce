package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.DamageReason;
import com.stockflow.inventory.internal.domain.StockDamageReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

/**
 * Read side of damage reports: the warehouse's list and the manager's queue (open = units still waiting for a
 * decision). Rows are projections of the report row and a per-page sum of its decisions; the photos and the
 * decisions themselves are the detail screen's, never loaded per row. Limited to the caller's warehouses.
 */
public interface StockDamageReportSearch {

    /** Every filter optional: {@code open} true for the queue, false for the settled ones. */
    record Criteria(UUID warehouseId, Boolean open, String sku, StockDamageReport.Origin origin) {
    }

    /** One report in a list. */
    record Row(UUID id, String number, StockDamageReport.Origin origin, UUID warehouseId, String sku,
               String lotNumber, String sourceLocationCode, String damageLocationCode, int quantity,
               int decidedQuantity, DamageReason reason, UUID orderId, UUID reportedBy, Instant reportedAt) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);
}
