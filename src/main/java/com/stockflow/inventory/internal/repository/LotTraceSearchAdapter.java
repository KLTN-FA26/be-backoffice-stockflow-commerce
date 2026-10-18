package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LotTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link LotTraceSearch} in SQL. Conditions are appended only for the filters given: an absent filter is no
 * condition at all rather than a {@code :param IS NULL} test, which PostgreSQL cannot type for a null parameter.
 * Every value is a bound parameter; only fixed fragments are concatenated.
 */
@Repository
class LotTraceSearchAdapter implements LotTraceSearch {

    /** The warehouse prefix of a location code ({@code HCM} in {@code HCM-A01-2-B}). */
    private static final String PREFIX = "split_part(%s, '-', 1)";

    private final NamedParameterJdbcTemplate jdbc;

    LotTraceSearchAdapter(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public long countLots(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long count = jdbc.queryForObject("SELECT count(*) FROM inventory.stock_lot l WHERE " + where(filter, params),
                params, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<LotTrace.Summary> lots(Filter filter, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("offset", offset).addValue("limit", limit);
        String sql = summarySelect(filter.warehouses(), filter.today(), params) + " WHERE " + where(filter, params)
                + " ORDER BY l.received_at DESC NULLS LAST, l.id LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, i) -> summary(rs));
    }

    @Override
    public Optional<LotTrace.Summary> lot(UUID lotId, Set<String> warehouses, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("lotId", lotId);
        StringBuilder where = new StringBuilder("l.id = :lotId");
        if (!warehouses.isEmpty()) {
            where.append(" AND ").append(wasIn(warehouses, params));
        }
        return jdbc.query(summarySelect(warehouses, today, params) + " WHERE " + where, params, (rs, i) -> summary(rs))
                .stream().findFirst();
    }

    @Override
    public List<LotTrace.Balance> balances(UUID lotId, Set<String> warehouses) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("lotId", lotId);
        String sql = """
                SELECT si.location_code, si.status, sum(si.on_hand) AS on_hand, sum(si.reserved) AS held
                  FROM inventory.stock_item si
                 WHERE si.lot_id = :lotId AND si.on_hand > 0""" + inWarehouses("si.location_code", warehouses, params)
                + " GROUP BY si.location_code, si.status ORDER BY si.location_code, si.status";
        return jdbc.query(sql, params, (rs, i) -> new LotTrace.Balance(rs.getString("location_code"),
                rs.getString("status"), rs.getInt("on_hand"), rs.getInt("held")));
    }

    @Override
    public List<LotTrace.Hold> holds(UUID lotId, Set<String> warehouses) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("lotId", lotId);
        String sql = """
                SELECT r.id, r.order_id, r.order_number, r.production_order_id, r.allocated_from_id, si.location_code,
                       r.quantity,
                       r.status, r.reserved_at, r.closed_at
                  FROM inventory.stock_reservation r
                  JOIN inventory.stock_item si ON si.id = r.stock_item_id
                 WHERE si.lot_id = :lotId""" + inWarehouses("si.location_code", warehouses, params)
                + " ORDER BY r.reserved_at, r.id";
        return jdbc.query(sql, params, (rs, i) -> new LotTrace.Hold(uuid(rs, "id"), uuid(rs, "order_id"),
                rs.getString("order_number"), uuid(rs, "production_order_id"), uuid(rs, "allocated_from_id"), rs.getString("location_code"),
                rs.getInt("quantity"), rs.getString("status"), instant(rs, "reserved_at"), instant(rs, "closed_at")));
    }

    @Override
    public List<LotTrace.OrderLeg> orders(UUID lotId, Set<String> warehouses) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("lotId", lotId);
        String sql = """
                SELECT m.order_id, max(m.order_number) AS order_number,
                       sum(CASE WHEN m.movement_type = 'PICK' THEN m.quantity ELSE 0 END) AS picked,
                       sum(CASE WHEN m.movement_type = 'PUT_BACK' THEN m.quantity ELSE 0 END) AS put_back,
                       min(m.occurred_at) AS first_at, max(m.occurred_at) AS last_at
                  FROM inventory.stock_movement m
                 WHERE m.lot_id = :lotId AND m.order_id IS NOT NULL""" + movementIn(warehouses, params)
                + " GROUP BY m.order_id ORDER BY min(m.occurred_at), m.order_id";
        return jdbc.query(sql, params, (rs, i) -> new LotTrace.OrderLeg(uuid(rs, "order_id"),
                rs.getString("order_number"), rs.getLong("picked"),
                rs.getLong("put_back"), instant(rs, "first_at"), instant(rs, "last_at")));
    }

    @Override
    public long countMovements(UUID lotId, Set<String> warehouses, Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("lotId", lotId);
        Long count = jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement m WHERE m.lot_id = :lotId"
                + movementIn(warehouses, params) + window(from, to, params), params, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<LotTrace.Movement> movements(UUID lotId, Set<String> warehouses, Instant from, Instant to,
                                             long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("lotId", lotId)
                .addValue("offset", offset).addValue("limit", limit);
        String sql = """
                SELECT m.id, m.movement_type, m.from_location_code, m.to_location_code, m.quantity, m.from_status,
                       m.to_status, m.reference_type, m.reference_id, m.order_id, m.order_number, m.actor_id,
                       m.occurred_at, m.reason
                  FROM inventory.stock_movement m
                 WHERE m.lot_id = :lotId""" + movementIn(warehouses, params) + window(from, to, params)
                + " ORDER BY m.occurred_at, m.id LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, i) -> new LotTrace.Movement(uuid(rs, "id"), rs.getString("movement_type"),
                rs.getString("from_location_code"), rs.getString("to_location_code"), rs.getInt("quantity"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("reference_type"),
                uuid(rs, "reference_id"), uuid(rs, "order_id"), rs.getString("order_number"), uuid(rs, "actor_id"),
                instant(rs, "occurred_at"),
                rs.getString("reason")));
    }

    // ------------------------------------------------------------------ SQL fragments

    /**
     * The lot with its receipt quantity and current balance, narrowed to {@code warehouses}. {@code available} is
     * what {@code StockItem.available()} means, summed: unheld units of AVAILABLE rows, and none of a row past its
     * expiry on {@code today} - expired stock is on hand, never available.
     */
    private static String summarySelect(Set<String> warehouses, LocalDate today, MapSqlParameterSource params) {
        params.addValue("today", today);
        String items = inWarehouses("si.location_code", warehouses, params);
        String receipts = inWarehouses("m.to_location_code", warehouses, params);
        return """
                SELECT l.id, l.sku, l.lot_number, l.expiry_date, l.received_at, l.origin_type, l.goods_receipt_id,
                       l.goods_receipt_number, l.purchase_order_id, l.purchase_order_number, l.supplier_id,
                       l.supplier_name, l.warehouse_id,
                       COALESCE((SELECT sum(m.quantity) FROM inventory.stock_movement m
                                  WHERE m.lot_id = l.id AND m.movement_type = 'RECEIPT'""" + receipts + "), 0) AS received,"
                + """
                       COALESCE((SELECT sum(si.on_hand) FROM inventory.stock_item si WHERE si.lot_id = l.id""" + items
                + "), 0) AS on_hand,"
                + """
                       COALESCE((SELECT sum(si.on_hand - si.reserved) FROM inventory.stock_item si
                                  WHERE si.lot_id = l.id AND si.status = 'AVAILABLE'
                                    AND (si.expiry_date IS NULL OR si.expiry_date >= :today)""" + items
                + "), 0) AS available,"
                + """
                       COALESCE((SELECT sum(si.reserved) FROM inventory.stock_item si WHERE si.lot_id = l.id""" + items
                + "), 0) AS held FROM inventory.stock_lot l";
    }

    private static String where(Filter filter, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>(List.of("TRUE"));
        if (filter.sku() != null) {
            conditions.add("l.sku = :sku");
            params.addValue("sku", filter.sku());
        }
        if (filter.lotNumber() != null) {
            conditions.add("l.lot_number = :lotNumber");
            params.addValue("lotNumber", filter.lotNumber());
        }
        if (filter.from() != null || filter.to() != null) {
            // Moved inside the window - and, when warehouses are given, inside them.
            conditions.add("EXISTS (SELECT 1 FROM inventory.stock_movement m WHERE m.lot_id = l.id"
                    + movementIn(filter.warehouses(), params) + window(filter.from(), filter.to(), params) + ")");
        } else if (!filter.warehouses().isEmpty()) {
            conditions.add(wasIn(filter.warehouses(), params));
        }
        return String.join(" AND ", conditions);
    }

    /** The lot has a stock row, or a ledger line naming a location, in one of {@code warehouses}. */
    private static String wasIn(Set<String> warehouses, MapSqlParameterSource params) {
        params.addValue("warehouses", warehouses);
        return "(EXISTS (SELECT 1 FROM inventory.stock_item si WHERE si.lot_id = l.id AND "
                + PREFIX.formatted("si.location_code") + " IN (:warehouses))"
                + " OR EXISTS (SELECT 1 FROM inventory.stock_movement m WHERE m.lot_id = l.id"
                + movementIn(warehouses, params) + "))";
    }

    private static String inWarehouses(String column, Set<String> warehouses, MapSqlParameterSource params) {
        if (warehouses.isEmpty()) {
            return "";
        }
        params.addValue("warehouses", warehouses);
        return " AND " + PREFIX.formatted(column) + " IN (:warehouses)";
    }

    /** A ledger line is in a warehouse when either side of it is. */
    private static String movementIn(Set<String> warehouses, MapSqlParameterSource params) {
        if (warehouses.isEmpty()) {
            return "";
        }
        params.addValue("warehouses", warehouses);
        return " AND (" + PREFIX.formatted("m.from_location_code") + " IN (:warehouses) OR "
                + PREFIX.formatted("m.to_location_code") + " IN (:warehouses))";
    }

    private static String window(Instant from, Instant to, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder();
        if (from != null) {
            sql.append(" AND m.occurred_at >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND m.occurred_at < :to");
            params.addValue("to", Timestamp.from(to));
        }
        return sql.toString();
    }

    private static LotTrace.Summary summary(ResultSet rs) throws SQLException {
        LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
        return new LotTrace.Summary(uuid(rs, "id"), rs.getString("sku"), rs.getString("lot_number"), expiry,
                instant(rs, "received_at"), rs.getString("origin_type"), uuid(rs, "goods_receipt_id"),
                rs.getString("goods_receipt_number"), uuid(rs, "purchase_order_id"),
                rs.getString("purchase_order_number"), uuid(rs, "supplier_id"), rs.getString("supplier_name"),
                uuid(rs, "warehouse_id"), rs.getLong("received"), rs.getLong("on_hand"), rs.getLong("available"),
                rs.getLong("held"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
