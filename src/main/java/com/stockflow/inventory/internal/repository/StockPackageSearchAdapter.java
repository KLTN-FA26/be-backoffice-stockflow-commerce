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
 * Plain SQL over {@code inventory.stock_package}, as {@code CycleCountRepositoryAdapter} reads it: nothing here
 * writes, and the read model ({@code StockPackageBalance}) has no entity. Until V20261230000600 it read the view
 * {@code inventory.stock_package_balance}, which joined each carton to its stock row; the carton is the stock now
 * (ADR-0011), so the unit figures the lookup still answers are derived here, in {@link #CARTONS}.
 */
@Repository
class StockPackageSearchAdapter implements StockPackageSearch {

    /** A carton a live allocation names: the only record that it is taken ({@code uk_stock_reservation_live_carton}). */
    private static final String HELD = """
            EXISTS (SELECT 1 FROM inventory.stock_reservation r
                     WHERE r.stock_package_id = p.id AND r.status = 'HELD')""";

    /**
     * One row per carton, under the names the dropped view gave them, so the HTTP contract keeps its fields. The unit
     * figures are the carton's count or nothing: {@code on_hand} while it is in stock, {@code reserved} while an order
     * holds it. A carton has a stock status exactly while it is in stock ({@code ck_stock_package_stock_status}), so
     * {@code stock_status} and {@code received_at} are shown only then, as they were read from a row holding
     * something; the lot is shown whatever the carton's status, as the emptied row kept it.
     */
    private static final String CARTONS = """
            (SELECT p.id AS stock_package_id, p.lpn, i.sku, u.unit_of_measure, p.nominal_qty, p.counted_at,
                    p.location_code, p.status, p.stock_status, l.lot_number,
                    CASE WHEN p.status = 'IN_STOCK' THEN p.nominal_qty ELSE 0 END AS on_hand,
                    CASE WHEN\s""" + HELD + """
                     THEN p.nominal_qty ELSE 0 END AS reserved,
                    CASE WHEN p.status = 'IN_STOCK' THEN p.received_at END AS received_at,
                    p.goods_receipt_line_id, p.closed_at, p.return_opened_at, p.return_ownership, p.return_request_id
               FROM inventory.stock_package p
               JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
               JOIN inventory.uom_conversions u ON u.id = p.uom_conversion_id
               LEFT JOIN inventory.stock_lot l ON l.id = p.lot_id) b""";

    /**
     * The carton's columns, then what the list shows of each carton without opening it (#454), each a subquery on the
     * carton: still one statement for a page, whatever its size. {@code stock_statuses} stays a list in the contract;
     * it holds the carton's one stock status, or nothing when the carton is not in stock.
     *
     * <p>{@code source} reads the ledger by the carton a line names ({@code stock_package_id}, V20261230000200). A
     * PRODUCTION carton was one a production order's line put goods <em>into</em> - the arriving side, when a line had
     * two - and not one it took blanks out of; that is now said by the line having a place it went to
     * ({@code to_location_code}). A return receipt's line is a RECEIPT, which only ever arrives.</p>
     */
    private static final String CARTON_COLUMNS = """
            b.stock_package_id, b.lpn, b.sku, b.unit_of_measure, b.nominal_qty, b.counted_at, b.location_code,
            b.status, b.lot_number, b.on_hand, b.reserved, b.goods_receipt_line_id,
            b.closed_at, b.return_opened_at, b.return_ownership, b.return_request_id,
            CASE WHEN b.stock_status IS NULL THEN ARRAY[]::varchar[] ELSE ARRAY[b.stock_status] END AS stock_statuses,
            b.received_at,
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

    /** The column of each of {@link #SORTABLE}; checked when the class loads, so a gap fails startup, not a request. */
    private static final Map<String, String> SORT_COLUMNS =
            Map.of("lpn", "b.lpn", "locationCode", "b.location_code", "sku", "b.sku", "receivedAt", "b.received_at");

    static {
        if (!SORT_COLUMNS.keySet().equals(Set.copyOf(SORTABLE))) {
            throw new IllegalStateException("Sort columns " + SORT_COLUMNS.keySet() + " do not match " + SORTABLE);
        }
    }

    /**
     * What the list's total is counted over: the columns {@link #where} filters on, under the names {@link #CARTONS}
     * gives them, with none of its derived figures and no unit join (its key is NOT NULL with a foreign key).
     */
    private static final String COUNTED = """
            (SELECT p.id AS stock_package_id, p.lpn, p.status, p.stock_status, p.location_code, i.sku, l.lot_number
               FROM inventory.stock_package p
               JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
               LEFT JOIN inventory.stock_lot l ON l.id = p.lot_id) b""";

    private final NamedParameterJdbcTemplate jdbc;

    StockPackageSearchAdapter(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Optional<StockPackageBalance> byLpn(String lpn) {
        return jdbc.query("SELECT " + CARTON_COLUMNS + " FROM " + CARTONS + " WHERE b.lpn = :lpn",
                new MapSqlParameterSource("lpn", lpn), StockPackageSearchAdapter::cartonRow).stream().findFirst();
    }

    /**
     * At most one line: a carton is one lot, one receipt and one stock status (ADR-0011). The contract keeps the list
     * it had when a carton's contents were stock rows.
     */
    @Override
    public List<StockPackageBalance.Content> contentsOf(UUID stockPackageId) {
        return jdbc.query("SELECT b.lot_number, b.received_at, b.stock_status, b.on_hand, b.reserved FROM " + CARTONS
                        + " WHERE b.stock_package_id = :id AND b.status = 'IN_STOCK'",
                new MapSqlParameterSource("id", stockPackageId),
                (rs, i) -> new StockPackageBalance.Content(rs.getString("lot_number"), instant(rs, "received_at"),
                        StockStatus.valueOf(rs.getString("stock_status")), rs.getInt("on_hand"),
                        rs.getInt("reserved")));
    }

    /**
     * The cartons in stock at the place, grouped; a carton holds one lot, so it falls in one group. There is no loose
     * stock (the stock rows went with V20261230000600), so {@code looseUnits} is always 0: the field stays in the
     * contract.
     */
    @Override
    public List<StockPackageBalance.LocationLine> atLocation(String locationCode) {
        return jdbc.query("""
                        SELECT i.sku, l.lot_number, p.stock_status,
                               count(*)           AS whole_cartons,
                               sum(p.nominal_qty) AS whole_units
                          FROM inventory.stock_package p
                          JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
                          LEFT JOIN inventory.stock_lot l ON l.id = p.lot_id
                         WHERE p.location_code = :code AND p.status = 'IN_STOCK'
                         GROUP BY i.sku, l.lot_number, p.stock_status
                         ORDER BY i.sku, l.lot_number NULLS FIRST, p.stock_status""",
                new MapSqlParameterSource("code", locationCode),
                (rs, i) -> new StockPackageBalance.LocationLine(rs.getString("sku"), rs.getString("lot_number"),
                        StockStatus.valueOf(rs.getString("stock_status")),
                        rs.getInt("whole_cartons"), rs.getInt("whole_units"), 0));
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
            conditions.add("b.lot_number = :lot");
            params.addValue("lot", criteria.lotNumber());
        }
        if (criteria.lpnPart() != null) {
            conditions.add("b.lpn LIKE :lpnPart ESCAPE '\\'");
            params.addValue("lpnPart", "%" + criteria.lpnPart().replace("\\", "\\\\").replace("%", "\\%")
                    .replace("_", "\\_") + "%");
        }
        if (criteria.stockStatuses() != null && !criteria.stockStatuses().isEmpty()) {
            conditions.add("b.stock_status IN (:stockStatuses)");
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
                        SELECT r.stock_package_id, r.order_id, r.order_number,
                               sum(r.cartons * r.units_per_carton) AS quantity
                          FROM inventory.stock_reservation r
                         WHERE r.stock_package_id IN (:ids) AND r.status = 'HELD'
                         GROUP BY r.stock_package_id, r.order_id, r.order_number
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
