package com.stockflow.inventory.internal.repository;

import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.domain.StockPackageBalance;
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

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Plain SQL over {@code inventory.stock_package_balance} (V20261022000100 §3), the view written for this lookup, as
 * {@code CycleCountRepositoryAdapter} reads it: a view has no entity, and nothing here writes, so there is no pending
 * change in the persistence context to flush first.
 */
@Repository
class StockPackageSearchAdapter implements StockPackageSearch {

    /**
     * The view's columns, then what the list shows of each carton without opening it (#454), each a subquery on the
     * carton's own rows: still one statement for a page, whatever its size. A carton is one stock row
     * (V20261113000300), so {@code stock_statuses} holds one status, but it is read as the rows say.
     *
     * <p>{@code source} reads the ledger by the carton a line names ({@code stock_package_id}, V20261230000200). A
     * PRODUCTION carton was one a production order's line put goods <em>into</em> - the arriving side, when a line had
     * two - and not one it took blanks out of; that is now said by the line having a place it went to
     * ({@code to_location_code}). A return receipt's line is a RECEIPT, which only ever arrives.</p>
     */
    private static final String CARTON_COLUMNS = """
            b.stock_package_id, b.lpn, b.sku, b.unit_of_measure, b.nominal_qty, b.counted_at, b.location_code,
            b.status, b.lot_number, b.on_hand, b.reserved, b.goods_receipt_line_id,
            b.closed_at,
            (SELECT p.return_opened_at FROM inventory.stock_package p WHERE p.id = b.stock_package_id)
                AS return_opened_at,
            (SELECT p.return_ownership FROM inventory.stock_package p WHERE p.id = b.stock_package_id)
                AS return_ownership,
            (SELECT p.return_request_id FROM inventory.stock_package p WHERE p.id = b.stock_package_id)
                AS return_request_id,
            ARRAY(SELECT DISTINCT s.status FROM inventory.stock_item s
                   WHERE s.stock_package_id = b.stock_package_id AND s.on_hand > 0 ORDER BY s.status)
                AS stock_statuses,
            (SELECT min(s.received_at) FROM inventory.stock_item s
              WHERE s.stock_package_id = b.stock_package_id AND s.on_hand > 0) AS received_at,
            CASE
                WHEN EXISTS (SELECT 1 FROM inventory.stock_movement m
                              WHERE m.stock_package_id = b.stock_package_id AND m.reference_type = 'GOODS_RECEIPT')
                    THEN 'RETURN'
                WHEN b.goods_receipt_line_id IS NOT NULL THEN 'GOODS_RECEIPT'
                WHEN EXISTS (SELECT 1 FROM inventory.stock_movement m
                              WHERE m.stock_package_id = b.stock_package_id
                                AND m.reference_type = 'PRODUCTION_ORDER' AND m.to_location_code IS NOT NULL)
                    THEN 'PRODUCTION'
                ELSE 'IN_WAREHOUSE'
            END AS source,
            (SELECT c.id FROM inventory.cycle_count_line l JOIN inventory.cycle_count c ON c.id = l.cycle_count_id
              WHERE l.stock_package_id = b.stock_package_id AND c.status IN ('PLANNED', 'IN_PROGRESS')
              ORDER BY c.created_at, c.id LIMIT 1) AS open_count_id,
            (SELECT c.count_number FROM inventory.cycle_count_line l JOIN inventory.cycle_count c
                 ON c.id = l.cycle_count_id
              WHERE l.stock_package_id = b.stock_package_id AND c.status IN ('PLANNED', 'IN_PROGRESS')
              ORDER BY c.created_at, c.id LIMIT 1) AS open_count_number""";

    /** The view, one row per carton (V20261113000300); nothing expires since team plan 5-1, so nothing else is read. */
    private static final String CARTONS = "inventory.stock_package_balance b";

    /** The column of each of {@link #SORTABLE}; checked when the class loads, so a gap fails startup, not a request. */
    private static final Map<String, String> SORT_COLUMNS =
            Map.of("lpn", "b.lpn", "locationCode", "b.location_code", "sku", "b.sku", "receivedAt", "received_at");

    static {
        if (!SORT_COLUMNS.keySet().equals(Set.copyOf(SORTABLE))) {
            throw new IllegalStateException("Sort columns " + SORT_COLUMNS.keySet() + " do not match " + SORTABLE);
        }
    }

    /**
     * What the list's total is counted over: the columns {@link #where} filters on, named as the view names them, with
     * none of its sums. Counting over the view would join and group every matching carton's stock rows only to drop
     * them; the view's unit join is left out too, its key being NOT NULL with a foreign key.
     */
    private static final String COUNTED = """
            (SELECT p.id AS stock_package_id, p.lpn, p.status, p.location_code, i.sku
               FROM inventory.stock_package p
               JOIN inventory.inventory_items i ON i.id = p.inventory_item_id) b""";

    private final NamedParameterJdbcTemplate jdbc;

    StockPackageSearchAdapter(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Optional<StockPackageBalance> byLpn(String lpn) {
        return jdbc.query("SELECT " + CARTON_COLUMNS + " FROM " + CARTONS + " WHERE b.lpn = :lpn",
                new MapSqlParameterSource("lpn", lpn), StockPackageSearchAdapter::cartonRow).stream().findFirst();
    }

    @Override
    public List<StockPackageBalance.Content> contentsOf(UUID stockPackageId) {
        return jdbc.query("""
                        SELECT lot_number, received_at, status, on_hand, reserved
                          FROM inventory.stock_item
                         WHERE stock_package_id = :id AND on_hand > 0
                         ORDER BY received_at NULLS LAST, id""",
                new MapSqlParameterSource("id", stockPackageId),
                (rs, i) -> new StockPackageBalance.Content(rs.getString("lot_number"), instant(rs, "received_at"), StockStatus.valueOf(rs.getString("status")), rs.getInt("on_hand"),
                        rs.getInt("reserved")));
    }

    /**
     * A row of a carton is a whole carton (one row per carton, V20261113000300); rows with no carton are loose. A
     * carton holds one lot, so it falls in one group.
     */
    @Override
    public List<StockPackageBalance.LocationLine> atLocation(String locationCode) {
        return jdbc.query("""
                        SELECT s.sku, s.lot_number, s.status,
                               count(DISTINCT p.id)                                           AS whole_cartons,
                               COALESCE(sum(s.on_hand) FILTER (WHERE p.id IS NOT NULL), 0)    AS whole_units,
                               COALESCE(sum(s.on_hand) FILTER (WHERE p.id IS NULL), 0)        AS loose_units
                          FROM inventory.stock_item s
                          LEFT JOIN inventory.stock_package p ON p.id = s.stock_package_id
                         WHERE s.location_code = :code AND s.on_hand > 0
                         GROUP BY s.sku, s.lot_number, s.status
                         ORDER BY s.sku, s.lot_number NULLS FIRST, s.status""",
                new MapSqlParameterSource("code", locationCode),
                (rs, i) -> new StockPackageBalance.LocationLine(rs.getString("sku"), rs.getString("lot_number"),
                        StockStatus.valueOf(rs.getString("status")),
                        rs.getInt("whole_cartons"), rs.getInt("whole_units"), rs.getInt("loose_units")));
    }

    @Override
    public Page<StockPackageBalance> cartons(CartonCriteria criteria, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(criteria, params);
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + COUNTED + " WHERE " + where, params, Long.class);
        if (total == null || total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total == null ? 0 : total);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<StockPackageBalance> content = jdbc.query("SELECT " + CARTON_COLUMNS + " FROM " + CARTONS
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
            conditions.add(WarehouseScope.locationsInSql("b.location_code", criteria.warehousePrefixes(), "warehouse",
                    params));
        }
        if (criteria.warehousePrefix() != null) {
            conditions.add(WarehouseScope.locationsInSql("b.location_code", List.of(criteria.warehousePrefix()),
                    "oneWarehouse", params));
        }
        if (criteria.lotNumber() != null) {
            // The rows, not the view's lot column: the count runs over cartons alone (COUNTED).
            conditions.add("EXISTS (SELECT 1 FROM inventory.stock_item s WHERE s.stock_package_id = b.stock_package_id"
                    + " AND s.lot_number = :lot)");
            params.addValue("lot", criteria.lotNumber());
        }
        if (criteria.lpnPart() != null) {
            conditions.add("b.lpn LIKE :lpnPart ESCAPE '\\'");
            params.addValue("lpnPart", "%" + criteria.lpnPart().replace("\\", "\\\\").replace("%", "\\%")
                    .replace("_", "\\_") + "%");
        }
        if (criteria.stockStatuses() != null && !criteria.stockStatuses().isEmpty()) {
            conditions.add("EXISTS (SELECT 1 FROM inventory.stock_item s WHERE s.stock_package_id = b.stock_package_id"
                    + " AND s.on_hand > 0 AND s.status IN (:stockStatuses))");
            params.addValue("stockStatuses", criteria.stockStatuses().stream().map(StockStatus::name).toList());
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

    @Override
    public Map<UUID, List<StockPackageBalance.Hold>> holdsOf(Collection<UUID> stockPackageIds) {
        if (stockPackageIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<StockPackageBalance.Hold>> holds = new HashMap<>();
        jdbc.query("""
                        SELECT s.stock_package_id, r.order_id, r.order_number,
                               sum(r.cartons * r.units_per_carton) AS quantity
                          FROM inventory.stock_reservation r
                          JOIN inventory.stock_item s ON s.id = r.stock_item_id
                         WHERE s.stock_package_id IN (:ids) AND r.status = 'HELD'
                         GROUP BY s.stock_package_id, r.order_id, r.order_number
                         ORDER BY r.order_number NULLS LAST, r.order_id""",
                new MapSqlParameterSource("ids", List.copyOf(stockPackageIds)),
                rs -> {
                    holds.computeIfAbsent(rs.getObject("stock_package_id", UUID.class), k -> new ArrayList<>())
                            .add(new StockPackageBalance.Hold(rs.getObject("order_id", UUID.class),
                                    rs.getString("order_number"), rs.getInt("quantity")));
                });
        return holds;
    }

    private static StockPackageBalance cartonRow(ResultSet rs, int rowNum) throws SQLException {
        List<StockStatus> stockStatuses = new ArrayList<>();
        Array statuses = rs.getArray("stock_statuses");
        if (statuses != null) {
            for (Object status : (Object[]) statuses.getArray()) {
                stockStatuses.add(StockStatus.valueOf((String) status));
            }
        }
        UUID openCount = rs.getObject("open_count_id", UUID.class);
        return new StockPackageBalance(rs.getObject("stock_package_id", UUID.class), rs.getString("lpn"),
                rs.getString("sku"), rs.getString("unit_of_measure"), rs.getInt("nominal_qty"),
                instant(rs, "counted_at"), rs.getString("location_code"),
                StockPackageStatus.valueOf(rs.getString("status")), rs.getString("lot_number"), rs.getInt("on_hand"),
                rs.getInt("reserved"), rs.getObject("goods_receipt_line_id", UUID.class), instant(rs, "closed_at"),
                instant(rs, "return_opened_at"), rs.getString("return_ownership"),
                rs.getObject("return_request_id", UUID.class), stockStatuses, instant(rs, "received_at"),
                StockPackageBalance.Source.valueOf(rs.getString("source")),
                openCount == null ? null : new StockPackageBalance.OpenTask(StockPackageBalance.TaskType.CYCLE_COUNT,
                        openCount, rs.getString("open_count_number")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
