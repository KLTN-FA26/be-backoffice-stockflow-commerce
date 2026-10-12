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
