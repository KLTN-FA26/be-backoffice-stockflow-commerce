package com.stockflow.inventory.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The stock-row reads of the nightly expiry sweep ({@code ExpirySweeper}); the writes go through the aggregate. */
@Repository
public class StockExpiryRepository {

    /**
     * Stock of one lot in one warehouse, summed over its locations and layers.
     *
     * @param locationSample any location code of the warehouse, to resolve its id through {@code warehouse :: api}
     * @param impaired       some of the stock is still AVAILABLE and held by a customer's reservation
     */
    public record LotRow(String warehousePrefix, String locationSample, String sku, String lotNumber,
                         LocalDate expiryDate, long onHand, boolean impaired) {
    }

    private final JdbcTemplate jdbc;

    public StockExpiryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ids of stock rows that expired and are still AVAILABLE, after {@code afterId}, in id order (the order every
     * stock write locks rows in). Rows with nothing on hand have nothing to expire and are left out.
     */
    public List<UUID> expiredAvailableIds(LocalDate today, UUID afterId, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM inventory.stock_item
                WHERE status = 'AVAILABLE' AND expiry_date < ? AND on_hand > 0 AND id > ?
                ORDER BY id LIMIT ?""", UUID.class, today, afterId, limit);
    }

    /**
     * Every lot that needs an alert today: AVAILABLE and expiring by {@code horizon}, or past its expiry date
     * and AVAILABLE (about to be blocked, or held by a reservation) or already EXPIRED. Lots with nothing on hand
     * are left out, which is how an alert learns its stock is gone.
     */
    public List<LotRow> lots(LocalDate today, LocalDate horizon) {
        return jdbc.query("""
                SELECT split_part(location_code, '-', 1) AS prefix, min(location_code) AS sample, sku, lot_number,
                       expiry_date, sum(on_hand) AS on_hand, bool_or(status = 'AVAILABLE' AND reserved > 0) AS impaired
                FROM inventory.stock_item
                WHERE on_hand > 0 AND expiry_date IS NOT NULL
                  AND ((status = 'AVAILABLE' AND expiry_date <= ?) OR (status = 'EXPIRED' AND expiry_date < ?))
                GROUP BY split_part(location_code, '-', 1), sku, lot_number, expiry_date
                ORDER BY prefix, sku, lot_number NULLS FIRST, expiry_date""",
                (rs, n) -> new LotRow(rs.getString("prefix"), rs.getString("sample"), rs.getString("sku"),
                        rs.getString("lot_number"), rs.getObject("expiry_date", LocalDate.class),
                        rs.getLong("on_hand"), rs.getBoolean("impaired")), horizon, today);
    }
}
