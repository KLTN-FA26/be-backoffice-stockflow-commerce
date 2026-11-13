package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.DelayAlert;
import com.stockflow.order.internal.domain.DelayAlertRepository;
import com.stockflow.order.internal.domain.DelayAlertType;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.ProductionScheduleQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link DelayAlertRepository} and {@link ProductionScheduleQuery} in plain SQL over {@code ordering.delay_alert} and
 * the production orders (4A-7). An alert is opened with {@code ON CONFLICT DO NOTHING} on the unique partial index of
 * open alerts, and closed with a conditional update, so two writers that both decided the same thing write it once and
 * each learns whether it was the one - the one that opened it is the one that tells people.
 */
@Repository
class DelayAlertRepositoryAdapter implements DelayAlertRepository, ProductionScheduleQuery {

    private static final String OPEN_STATUSES = "('DRAFT', 'PENDING_APPROVAL', 'SENT', 'ACCEPTED', 'SAMPLE_PENDING', "
            + "'IN_PRODUCTION', 'PARTIALLY_RECEIVED')";

    /**
     * An order in one of {@code :statuses}, or on hold from one of them (DelayAlertService.followed: a credit hold placed
     * at signing has no status before it and is not followed).
     */
    private static final String FOLLOWED = "(o.status IN (:statuses) OR (o.status = 'ON_HOLD' "
            + "AND o.status_before_hold IN (:statuses)))";

    private static final String ALERT_COLUMNS = """
            a.id, a.type, a.production_order_id, a.delivery_batch_id, a.sales_order_id, a.opened_at, a.closed_at""";

    private final NamedParameterJdbcTemplate named;

    DelayAlertRepositoryAdapter(JdbcTemplate jdbc) {
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    // ---- DelayAlertRepository ---------------------------------------------------------------------------------------

    @Override
    public void lockSalesOrder(UUID salesOrderId) {
        // A transaction-level advisory lock of its own name space: it holds no row, so it orders against nothing else.
        // Wrapped in a sub-select because pg_advisory_xact_lock returns void.
        named.queryForObject("""
                        SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended('delay-alert:' || :order, 0)))
                            AS locked""", new MapSqlParameterSource("order", salesOrderId.toString()), Integer.class);
    }

    @Override
    public List<DelayAlert> openOf(UUID salesOrderId) {
        return named.query("SELECT " + ALERT_COLUMNS + """
                         FROM ordering.delay_alert a
                        WHERE a.sales_order_id = :order AND a.closed_at IS NULL
                        ORDER BY a.opened_at, a.id""",
                new MapSqlParameterSource("order", salesOrderId), DelayAlertRepositoryAdapter::alert);
    }

    @Override
    public boolean open(DelayAlert alert) {
        return named.update("""
                        INSERT INTO ordering.delay_alert (id, type, production_order_id, delivery_batch_id,
                                                          sales_order_id, opened_at)
                        VALUES (:id, :type, :production, :batch, :order, :opened)
                        ON CONFLICT (type, subject_id) WHERE closed_at IS NULL DO NOTHING""",
                new MapSqlParameterSource("id", alert.id())
                        .addValue("type", alert.type().name())
                        .addValue("production", alert.productionOrderId())
                        .addValue("batch", alert.deliveryBatchId())
                        .addValue("order", alert.salesOrderId())
                        .addValue("opened", Timestamp.from(alert.openedAt()))) == 1;
    }

    @Override
    public boolean close(UUID id, Instant at) {
        return named.update("""
                        UPDATE ordering.delay_alert SET closed_at = GREATEST(:at, opened_at)
                         WHERE id = :id AND closed_at IS NULL""",
                new MapSqlParameterSource("id", id).addValue("at", Timestamp.from(at))) == 1;
    }

    @Override
    public List<UUID> salesOrdersToSweep(Collection<String> statuses) {
        return named.queryForList("""
                        SELECT o.id FROM ordering.customer_order o
                         WHERE """ + FOLLOWED + """
                           AND o.contract_signed_at IS NOT NULL
                        UNION
                        SELECT p.sales_order_id FROM ordering.production_order p WHERE p.status IN """ + OPEN_STATUSES
                        + """

                        UNION
                        SELECT a.sales_order_id FROM ordering.delay_alert a WHERE a.closed_at IS NULL""",
                new MapSqlParameterSource("statuses", statuses), UUID.class);
    }

    @Override
    public List<DelayAlert> openOn(Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return List.of();
        }
        return named.query("SELECT " + ALERT_COLUMNS + """
                         FROM ordering.delay_alert a
                        WHERE a.subject_id IN (:subjects) AND a.closed_at IS NULL
                        ORDER BY a.opened_at, a.id""",
                new MapSqlParameterSource("subjects", subjectIds), DelayAlertRepositoryAdapter::alert);
    }

    // ---- ProductionScheduleQuery ------------------------------------------------------------------------------------

    @Override
    public List<ScheduledProduction> openProductionOrders(UUID producerId, UUID warehouseId, LocalDate from,
                                                          LocalDate to, Collection<UUID> warehouses,
                                                          boolean alertsOnly, int limit) {
        if (warehouses != null && warehouses.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("producer", producerId).addValue("warehouse", warehouseId)
                .addValue("from", from == null ? null : Date.valueOf(from))
                .addValue("to", to == null ? null : Date.valueOf(to))
                .addValue("warehouses", warehouses).addValue("limit", limit);
        String sql = """
                SELECT p.id, p.code, p.status, p.producer_id, pr.code AS producer_code, pr.name AS producer_name,
                       p.sales_order_id, o.order_number, p.customer_id, p.delivery_batch_id, b.seq, b.window_start,
                       b.window_end, p.receiving_warehouse_id, p.need_by_date, p.expected_completion_date,
                       COALESCE(l.cartons, 0) AS cartons, COALESCE(l.received, 0) AS received
                  FROM ordering.production_order p
                  JOIN ordering.producer pr ON pr.id = p.producer_id
                  JOIN ordering.customer_order o ON o.id = p.sales_order_id
                  JOIN ordering.delivery_batch b ON b.id = p.delivery_batch_id
                  LEFT JOIN (SELECT production_order_id, SUM(cartons) AS cartons, SUM(received_cartons) AS received
                               FROM ordering.production_order_line GROUP BY production_order_id) l
                    ON l.production_order_id = p.id
                 WHERE p.status IN """ + OPEN_STATUSES + """

                   AND (CAST(:producer AS uuid) IS NULL OR p.producer_id = :producer)
                   AND (CAST(:warehouse AS uuid) IS NULL OR p.receiving_warehouse_id = :warehouse)
                   AND (CAST(:from AS date) IS NULL OR p.need_by_date >= :from)
                   AND (CAST(:to AS date) IS NULL OR p.need_by_date <= :to)
                """ + (warehouses == null ? "" : "   AND p.receiving_warehouse_id IN (:warehouses)\n")
                + (!alertsOnly ? "" : """
                   AND EXISTS (SELECT 1 FROM ordering.delay_alert a
                                WHERE a.closed_at IS NULL AND (a.production_order_id = p.id
                                   OR (a.delivery_batch_id = p.delivery_batch_id AND a.type = 'DELIVERY_LATE')))
                """) + """
                 ORDER BY p.need_by_date, p.code
                 LIMIT :limit""";
        return named.query(sql, params, (rs, row) -> new ScheduledProduction(rs.getObject("id", UUID.class),
                rs.getString("code"), ProductionOrderStatus.valueOf(rs.getString("status")),
                rs.getObject("producer_id", UUID.class), rs.getString("producer_code"), rs.getString("producer_name"),
                rs.getObject("sales_order_id", UUID.class), rs.getString("order_number"),
                rs.getObject("customer_id", UUID.class), rs.getObject("delivery_batch_id", UUID.class),
                rs.getInt("seq"), date(rs, "window_start"), date(rs, "window_end"),
                rs.getObject("receiving_warehouse_id", UUID.class), date(rs, "need_by_date"),
                date(rs, "expected_completion_date"), rs.getInt("cartons"), rs.getInt("received")));
    }

    @Override
    public Map<UUID, LocalDate> expectedCompletionDates(UUID salesOrderId) {
        Map<UUID, LocalDate> dates = new HashMap<>();
        named.query("""
                        SELECT p.id, p.expected_completion_date FROM ordering.production_order p
                         WHERE p.sales_order_id = :order AND p.expected_completion_date IS NOT NULL""",
                new MapSqlParameterSource("order", salesOrderId),
                rs -> {
                    dates.put(rs.getObject("id", UUID.class), date(rs, "expected_completion_date"));
                });
        return dates;
    }

    @Override
    public List<FollowedOrder> ordersIn(Collection<String> statuses, UUID warehouseId, Collection<UUID> warehouses,
                                        LocalDate from, LocalDate to, boolean alertsOnly, int limit) {
        if (statuses.isEmpty() || warehouses != null && warehouses.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("statuses", statuses)
                .addValue("warehouse", warehouseId).addValue("warehouses", warehouses)
                .addValue("from", from == null ? null : Date.valueOf(from))
                .addValue("to", to == null ? null : Date.valueOf(to))
                .addValue("ranged", from != null || to != null).addValue("limit", limit);
        // The batches that may show: in the range (one with no window only when no range is asked), and with an open
        // alert when only those are asked for. Orders go by the earliest of them, so a limit keeps the most urgent.
        String sql = """
                SELECT o.id, o.order_number, o.customer_id, o.shipping_warehouse_id, o.status
                  FROM ordering.customer_order o
                  JOIN ordering.sales_contract c ON c.order_id = o.id
                  JOIN ordering.delivery_batch b ON b.contract_id = c.id
                 WHERE """ + FOLLOWED + """
                           AND o.contract_signed_at IS NOT NULL
                   AND (CAST(:warehouse AS uuid) IS NULL OR o.shipping_warehouse_id = :warehouse)
                   AND (b.window_start IS NOT NULL OR NOT :ranged)
                   AND (CAST(:from AS date) IS NULL OR b.window_start >= :from)
                   AND (CAST(:to AS date) IS NULL OR b.window_start <= :to)
                """ + (warehouses == null ? "" : "   AND o.shipping_warehouse_id IN (:warehouses)\n")
                + (!alertsOnly ? "" : """
                   AND EXISTS (SELECT 1 FROM ordering.delay_alert a
                                WHERE a.delivery_batch_id = b.id AND a.closed_at IS NULL)
                """) + """
                 GROUP BY o.id, o.order_number, o.customer_id, o.shipping_warehouse_id, o.status, o.contract_signed_at
                 ORDER BY MIN(b.window_start) NULLS LAST, o.contract_signed_at, o.id
                 LIMIT :limit""";
        return named.query(sql, params, (rs, row) -> new FollowedOrder(rs.getObject("id", UUID.class),
                rs.getString("order_number"), rs.getObject("customer_id", UUID.class),
                rs.getObject("shipping_warehouse_id", UUID.class), rs.getString("status")));
    }

    @Override
    public AlertPage alerts(UUID salesOrderId, DelayAlertType type, boolean openOnly, Collection<UUID> warehouses,
                            int limit, long offset) {
        if (warehouses != null && warehouses.isEmpty()) {
            return new AlertPage(List.of(), 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource("order", salesOrderId)
                .addValue("type", type == null ? null : type.name()).addValue("open", openOnly)
                .addValue("warehouses", warehouses)
                .addValue("limit", limit).addValue("offset", offset);
        // The warehouse an alert concerns, where it lives now: the production order's receiving warehouse, the order's
        // shipping warehouse for a batch.
        String from = """
                  FROM ordering.delay_alert a
                  JOIN ordering.customer_order o ON o.id = a.sales_order_id
                  LEFT JOIN ordering.production_order p ON p.id = a.production_order_id
                  LEFT JOIN ordering.delivery_batch b ON b.id = a.delivery_batch_id
                 WHERE (CAST(:order AS uuid) IS NULL OR a.sales_order_id = :order)
                   AND (CAST(:type AS varchar) IS NULL OR a.type = :type)
                   AND (NOT :open OR a.closed_at IS NULL)
                """ + (warehouses == null ? ""
                : "   AND COALESCE(p.receiving_warehouse_id, o.shipping_warehouse_id) IN (:warehouses)\n");
        Long total = named.queryForObject("SELECT count(*) " + from, params, Long.class);
        List<AlertView> items = named.query("SELECT " + ALERT_COLUMNS + """
                        , o.order_number, p.code AS production_code, b.seq,
                               COALESCE(p.receiving_warehouse_id, o.shipping_warehouse_id) AS warehouse_id
                        """ + from + " ORDER BY a.opened_at DESC, a.id LIMIT :limit OFFSET :offset", params,
                (rs, row) -> {
                    DelayAlert alert = alert(rs, row);
                    Object seq = rs.getObject("seq");
                    return new AlertView(alert.id(), alert.type(), alert.salesOrderId(), rs.getString("order_number"),
                            alert.productionOrderId(), rs.getString("production_code"), alert.deliveryBatchId(),
                            seq == null ? null : ((Number) seq).intValue(), rs.getObject("warehouse_id", UUID.class),
                            alert.openedAt(), alert.closedAt());
                });
        return new AlertPage(items, total == null ? 0 : total);
    }

    // ---- mapping ----------------------------------------------------------------------------------------------------

    private static DelayAlert alert(ResultSet rs, int row) throws SQLException {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new DelayAlert(rs.getObject("id", UUID.class), DelayAlertType.valueOf(rs.getString("type")),
                rs.getObject("production_order_id", UUID.class), rs.getObject("delivery_batch_id", UUID.class),
                rs.getObject("sales_order_id", UUID.class), rs.getTimestamp("opened_at").toInstant(),
                closed == null ? null : closed.toInstant());
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }
}
