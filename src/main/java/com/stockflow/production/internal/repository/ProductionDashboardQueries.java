package com.stockflow.production.internal.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The counts behind the workshop dashboard (SCRUM-415), each one aggregate query over
 * {@code production.production_order} or {@code production.sample_request} — no entity is loaded.
 * {@code warehouses} null reads every warehouse; otherwise only those (a warehouse-bound caller).
 */
@Repository
public class ProductionDashboardQueries {

    private static final String OPEN = "status NOT IN ('COMPLETED', 'CANCELLED')";

    private final NamedParameterJdbcTemplate jdbc;

    ProductionDashboardQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Open production orders per status. */
    public Map<String, Long> openByStatus(Collection<UUID> warehouses) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT status, count(*) FROM production.production_order WHERE " + OPEN + scope(warehouses)
                        + " GROUP BY status ORDER BY status",
                params(warehouses), rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        return counts;
    }

    /** Open production orders due before {@code today}. */
    public long overdue(Collection<UUID> warehouses, LocalDate today) {
        return jdbc.queryForObject("SELECT count(*) FROM production.production_order WHERE " + OPEN
                + " AND due_date < :today" + scope(warehouses), params(warehouses).addValue("today", today), Long.class);
    }

    /** Units printed and scrapped by production orders created in [{@code from}, {@code to}); null bounds are open. */
    public long[] printedAndScrap(Collection<UUID> warehouses, Instant from, Instant to) {
        MapSqlParameterSource params = params(warehouses);
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        if (from != null) {
            where.append(" AND created_at >= :from");
            params.addValue("from", java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND created_at < :to");
            params.addValue("to", java.sql.Timestamp.from(to));
        }
        return jdbc.queryForObject("SELECT COALESCE(SUM(printed_quantity), 0), COALESCE(SUM(scrap_quantity), 0) "
                        + "FROM production.production_order" + where + scope(warehouses), params,
                (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)});
    }

    /**
     * QA3, the default: units printed and scrapped in [{@code from}, {@code to}), whenever their production order was
     * created. Printed = the runs recorded in the period plus a subcontractor's goods received in it; scrap = the
     * production_scrap rows recorded in it — each spoilt unit is one row once, whether spoilt on the press, failed in
     * QC or written off when its job was stopped or cancelled, so nothing is counted twice. Null bounds are open.
     */
    public long[] printedAndScrapInPeriod(Collection<UUID> warehouses, Instant from, Instant to) {
        MapSqlParameterSource params = params(warehouses);
        String period = period(params, from, to);
        String scope = warehouses == null ? "" : " AND o.warehouse_id IN (:warehouses)";
        long printed = jdbc.queryForObject("""
                SELECT COALESCE((SELECT SUM(r.printed_quantity) FROM production.production_print_run r
                                   JOIN production.production_order o ON o.id = r.production_order_id
                                  WHERE TRUE""" + period.replace("{at}", "r.recorded_at") + scope + "), 0)" + """
                     + COALESCE((SELECT SUM(g.quantity) FROM production.production_subcontract_receipt g
                                   JOIN production.production_order o ON o.id = g.production_order_id
                                  WHERE TRUE""" + period.replace("{at}", "g.received_at") + scope + "), 0)",
                params, Long.class);
        long scrap = jdbc.queryForObject("""
                SELECT COALESCE(SUM(s.quantity), 0) FROM production.production_scrap s
                  JOIN production.production_order o ON o.id = s.production_order_id
                 WHERE TRUE""" + period.replace("{at}", "s.recorded_at") + scope, params, Long.class);
        return new long[] {printed, scrap};
    }

    private static String period(MapSqlParameterSource params, Instant from, Instant to) {
        StringBuilder where = new StringBuilder();
        if (from != null) {
            where.append(" AND {at} >= :from");
            params.addValue("from", java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND {at} < :to");
            params.addValue("to", java.sql.Timestamp.from(to));
        }
        return where.toString();
    }

    /** Sample rounds sent to the customer and not answered yet, made in the warehouses given. */
    public long samplesAwaitingApproval(Collection<UUID> warehouses) {
        String scope = warehouses == null ? "" : " AND EXISTS (SELECT 1 FROM production.production_order p "
                + "WHERE p.sample_request_id = s.id AND p.warehouse_id IN (:warehouses))";
        return jdbc.queryForObject("SELECT count(*) FROM production.sample_request s WHERE s.status = 'SENT_TO_CUSTOMER'"
                + scope, params(warehouses), Long.class);
    }

    private static String scope(Collection<UUID> warehouses) {
        return warehouses == null ? "" : " AND warehouse_id IN (:warehouses)";
    }

    private static MapSqlParameterSource params(Collection<UUID> warehouses) {
        return new MapSqlParameterSource("warehouses", warehouses);
    }
}
