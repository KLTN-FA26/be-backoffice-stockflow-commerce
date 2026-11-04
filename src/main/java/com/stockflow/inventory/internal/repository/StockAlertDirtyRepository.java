package com.stockflow.inventory.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * {@code inventory.stock_alert_dirty}: the SKUs whose stock changed, appended by the triggers on
 * {@code stock_item} (V20261014000900) and drained here.
 */
@Repository
public class StockAlertDirtyRepository {

    private final JdbcTemplate jdbc;

    public StockAlertDirtyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes up to {@code limit} queue rows and returns their distinct SKUs. The rows are deleted in the caller's
     * transaction: roll it back and they are queued again. {@code SKIP LOCKED} lets a second instance take other
     * rows instead of waiting, though the job's ShedLock normally keeps it to one.
     */
    public List<String> claim(int limit) {
        return jdbc.queryForList("""
                WITH claimed AS (
                    DELETE FROM inventory.stock_alert_dirty
                    WHERE id IN (SELECT id FROM inventory.stock_alert_dirty ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
                    RETURNING sku)
                SELECT DISTINCT sku FROM claimed ORDER BY sku""", String.class, limit);
    }

    /** Puts SKUs back after a failed evaluation, so the next drain retries them. */
    public void mark(Collection<String> skus) {
        for (String sku : skus) {
            jdbc.update("INSERT INTO inventory.stock_alert_dirty (sku) VALUES (?)", sku);
        }
    }

    public long size() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM inventory.stock_alert_dirty", Long.class);
        return count == null ? 0 : count;
    }
}
