package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain SQL over {@code inventory.stock_package_balance} (V20261022000100 §3), the view written for this lookup, as
 * {@code CycleCountRepositoryAdapter} reads it: a view has no entity, and nothing here writes, so there is no pending
 * change in the persistence context to flush first.
 */
@Repository
class StockPackageSearchAdapter implements StockPackageSearch {

    private static final String CARTON_COLUMNS = """
            b.stock_package_id, b.lpn, b.sku, b.unit_of_measure, b.nominal_qty, b.received_qty, b.location_code,
            b.status, b.unopened, b.lot_number, b.expiry_date, b.on_hand, b.reserved, b.goods_receipt_line_id,
            b.closed_at""";

    /** The sortable properties and their columns; the whitelist in the service names the same three. */
    private static final Map<String, String> SORT_COLUMNS =
            Map.of("lpn", "b.lpn", "locationCode", "b.location_code", "sku", "b.sku");

    /**
     * What the list's total is counted over: the columns {@link #where} filters on, named as the view names them, with
     * none of its sums. Counting over the view would join and group every matching carton's stock rows only to drop
     * them; the view's unit join is left out too, its key being NOT NULL with a foreign key.
     */
    private static final String COUNTED = """
            (SELECT p.status, p.location_code, i.sku
               FROM inventory.stock_package p
               JOIN inventory.inventory_items i ON i.id = p.inventory_item_id) b""";

    private final NamedParameterJdbcTemplate jdbc;

    StockPackageSearchAdapter(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Optional<CartonRow> byLpn(String lpn) {
        return jdbc.query("SELECT " + CARTON_COLUMNS + " FROM inventory.stock_package_balance b WHERE b.lpn = :lpn",
                new MapSqlParameterSource("lpn", lpn), StockPackageSearchAdapter::cartonRow).stream().findFirst();
    }

    @Override
    public List<ContentRow> contentsOf(UUID stockPackageId) {
        return jdbc.query("""
                        SELECT lot_number, expiry_date, received_at, status, on_hand, reserved
                          FROM inventory.stock_item
                         WHERE stock_package_id = :id AND on_hand > 0
                         ORDER BY expiry_date NULLS LAST, received_at NULLS LAST, id""",
                new MapSqlParameterSource("id", stockPackageId),
                (rs, i) -> new ContentRow(rs.getString("lot_number"), rs.getObject("expiry_date", LocalDate.class),
                        instant(rs, "received_at"), StockStatus.valueOf(rs.getString("status")), rs.getInt("on_hand"),
                        rs.getInt("reserved")));
    }

    /**
     * Rows of a carton are whole or opened by their carton's {@code unopened}; rows with no carton are loose. A carton
     * holds one lot (rule 2 of V20261022000100), so it falls in one group; were its rows in two stock statuses, it
     * would be counted in each, holding in each what its rows there hold.
     */
    @Override
    public List<LocationLine> atLocation(String locationCode) {
        return jdbc.query("""
                        SELECT s.sku, s.lot_number, s.expiry_date, s.status,
                               count(DISTINCT p.id) FILTER (WHERE p.unopened)                 AS whole_cartons,
                               COALESCE(sum(s.on_hand) FILTER (WHERE p.unopened), 0)          AS whole_units,
                               count(DISTINCT p.id) FILTER (WHERE NOT p.unopened)             AS opened_cartons,
                               COALESCE(sum(s.on_hand) FILTER (WHERE NOT p.unopened), 0)      AS opened_units,
                               COALESCE(sum(s.on_hand) FILTER (WHERE p.id IS NULL), 0)        AS loose_units
                          FROM inventory.stock_item s
                          LEFT JOIN inventory.stock_package p ON p.id = s.stock_package_id
                         WHERE s.location_code = :code AND s.on_hand > 0
                         GROUP BY s.sku, s.lot_number, s.expiry_date, s.status
                         ORDER BY s.sku, s.expiry_date NULLS LAST, s.lot_number NULLS FIRST, s.status""",
                new MapSqlParameterSource("code", locationCode),
                (rs, i) -> new LocationLine(rs.getString("sku"), rs.getString("lot_number"),
                        rs.getObject("expiry_date", LocalDate.class), StockStatus.valueOf(rs.getString("status")),
                        rs.getInt("whole_cartons"), rs.getInt("whole_units"), rs.getInt("opened_cartons"),
                        rs.getInt("opened_units"), rs.getInt("loose_units")));
    }

    @Override
    public Page<CartonRow> cartons(CartonCriteria criteria, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(criteria, params);
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + COUNTED + " WHERE " + where, params, Long.class);
        if (total == null || total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total == null ? 0 : total);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<CartonRow> content = jdbc.query("SELECT " + CARTON_COLUMNS + " FROM inventory.stock_package_balance b"
                        + " WHERE " + where + " ORDER BY " + orderBy(pageable.getSort())
                        + " LIMIT :limit OFFSET :offset",
                params, StockPackageSearchAdapter::cartonRow);
        return new PageImpl<>(content, pageable, total);
    }

    private static String where(CartonCriteria criteria, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        conditions.add("b.status IN (:statuses)");
        params.addValue("statuses", criteria.statuses().stream().map(StockPackageStatus::name).toList());
        if (criteria.locationCode() != null) {
            conditions.add("b.location_code = :location");
            params.addValue("location", criteria.locationCode());
        }
        if (criteria.sku() != null) {
            conditions.add("b.sku = :sku");
            params.addValue("sku", criteria.sku());
        }
        if (criteria.warehousePrefixes() != null) {
            List<String> any = new ArrayList<>();
            int index = 0;
            for (String prefix : criteria.warehousePrefixes()) {
                String name = "warehouse" + index++;
                any.add("b.location_code LIKE :" + name);
                params.addValue(name, prefix + "-%");
            }
            // A limited caller assigned to no warehouse sees nothing (WarehouseScope).
            conditions.add(any.isEmpty() ? "FALSE" : "(" + String.join(" OR ", any) + ")");
        }
        return String.join(" AND ", conditions);
    }

    private static String orderBy(Sort sort) {
        List<String> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column == null) {
                throw new IllegalArgumentException("Not a sortable carton property: " + order.getProperty());
            }
            orders.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        orders.add("b.stock_package_id");
        return String.join(", ", orders);
    }

    private static CartonRow cartonRow(ResultSet rs, int rowNum) throws SQLException {
        return new CartonRow(rs.getObject("stock_package_id", UUID.class), rs.getString("lpn"), rs.getString("sku"),
                rs.getString("unit_of_measure"), rs.getInt("nominal_qty"), rs.getObject("received_qty", Integer.class),
                rs.getString("location_code"), StockPackageStatus.valueOf(rs.getString("status")),
                rs.getBoolean("unopened"), rs.getString("lot_number"), rs.getObject("expiry_date", LocalDate.class),
                rs.getInt("on_hand"), rs.getInt("reserved"), rs.getObject("goods_receipt_line_id", UUID.class),
                instant(rs, "closed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
