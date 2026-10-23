package com.stockflow.reporting.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.reporting.internal.domain.BusinessDay;
import com.stockflow.reporting.internal.domain.OrderContribution;
import com.stockflow.reporting.internal.domain.OrderEventType;
import com.stockflow.reporting.internal.domain.SalesRollupLedger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SalesRollupLedger} over plain SQL: every write is one statement that is correct under concurrency, because
 * the rollups are bumped by {@code ON CONFLICT DO UPDATE SET x = x + :delta}, never read-modify-write, and "already
 * applied" is the primary key of {@code processed_order_events} refusing a second insert.
 *
 * <p>Callers hold the transaction (and the rollup lock, see {@link SalesRollupLock}); this class does not open one.</p>
 */
@Repository
public class SalesRollupLedgerAdapter implements SalesRollupLedger {

    private static final TypeReference<List<OrderContribution.Line>> LINES = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    SalesRollupLedgerAdapter(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean markApplied(UUID orderId, OrderEventType type, OrderContribution contribution) {
        return jdbc.update("""
                INSERT INTO reporting.processed_order_events
                    (order_id, event_type, placed_day, currency, total, lines, processed_at)
                VALUES (:orderId, :type, :day, :currency, :total, CAST(:lines AS jsonb), NOW())
                ON CONFLICT (order_id, event_type) DO NOTHING""",
                new MapSqlParameterSource("orderId", orderId)
                        .addValue("type", type.name())
                        .addValue("day", Date.valueOf(contribution.day()))
                        .addValue("currency", contribution.currency())
                        .addValue("total", contribution.total())
                        .addValue("lines", write(contribution.lines()))) == 1;
    }

    @Override
    public Optional<OrderContribution> placedContribution(UUID orderId) {
        return jdbc.query("""
                SELECT placed_day, currency, total, lines FROM reporting.processed_order_events
                WHERE order_id = :orderId AND event_type = 'PLACED'""",
                new MapSqlParameterSource("orderId", orderId),
                (rs, row) -> new OrderContribution(orderId, rs.getDate("placed_day").toLocalDate(),
                        rs.getString("currency"), rs.getBigDecimal("total"), read(rs.getString("lines"))))
                .stream().findFirst();
    }

    /**
     * Adds with an upsert. Removes with a plain UPDATE: Postgres checks CHECK constraints on the row an INSERT proposes
     * before it looks for the conflict, so an upsert of a negative delta would be refused even where the row exists
     * (and the sum would be fine). A removal with no row to remove from means the ledger and the rollups disagree -
     * that is raised, not papered over with a negative row.
     */
    @Override
    public void apply(OrderContribution contribution, int sign) {
        MapSqlParameterSource day = new MapSqlParameterSource("id", Identifiers.newId())
                .addValue("day", Date.valueOf(contribution.day()))
                .addValue("orders", sign)
                .addValue("revenue", contribution.total().multiply(BigDecimal.valueOf(sign)))
                .addValue("currency", contribution.currency());
        if (sign > 0) {
            jdbc.update("""
                    INSERT INTO reporting.sales_daily_summary
                        (id, day, orders_count, revenue, currency, version, created_at)
                    VALUES (:id, :day, :orders, :revenue, :currency, 0, NOW())
                    ON CONFLICT (day, currency) DO UPDATE SET
                        orders_count = reporting.sales_daily_summary.orders_count + EXCLUDED.orders_count,
                        revenue = reporting.sales_daily_summary.revenue + EXCLUDED.revenue,
                        version = reporting.sales_daily_summary.version + 1,
                        last_modified_at = NOW()""", day);
        } else if (jdbc.update("""
                UPDATE reporting.sales_daily_summary SET
                    orders_count = orders_count + :orders, revenue = revenue + :revenue,
                    version = version + 1, last_modified_at = NOW()
                WHERE day = :day AND currency = :currency""", day) != 1) {
            throw new IllegalStateException("No sales rollup for %s %s to take order %s out of"
                    .formatted(contribution.day(), contribution.currency(), contribution.orderId()));
        }
        if (contribution.lines().isEmpty()) {
            return;
        }
        SqlParameterSource[] rows = contribution.lines().stream()
                .<SqlParameterSource>map(line -> new MapSqlParameterSource("id", Identifiers.newId())
                        .addValue("sku", line.sku())
                        .addValue("period", BusinessDay.period(contribution.day()))
                        .addValue("quantity", line.quantity() * sign)
                        .addValue("revenue", line.revenue().multiply(BigDecimal.valueOf(sign))))
                .toArray(SqlParameterSource[]::new);
        if (sign > 0) {
            jdbc.batchUpdate("""
                    INSERT INTO reporting.product_sales_summary
                        (id, sku, period, quantity_sold, revenue, version, created_at)
                    VALUES (:id, :sku, :period, :quantity, :revenue, 0, NOW())
                    ON CONFLICT (sku, period) DO UPDATE SET
                        quantity_sold = reporting.product_sales_summary.quantity_sold + EXCLUDED.quantity_sold,
                        revenue = reporting.product_sales_summary.revenue + EXCLUDED.revenue,
                        version = reporting.product_sales_summary.version + 1,
                        last_modified_at = NOW()""", rows);
            return;
        }
        int[] updated = jdbc.batchUpdate("""
                UPDATE reporting.product_sales_summary SET
                    quantity_sold = quantity_sold + :quantity, revenue = revenue + :revenue,
                    version = version + 1, last_modified_at = NOW()
                WHERE sku = :sku AND period = :period""", rows);
        for (int count : updated) {
            if (count == 0) {
                throw new IllegalStateException("No product rollup for a line of order %s on %s to take it out of"
                        .formatted(contribution.orderId(), contribution.day()));
            }
        }
    }

    @Override
    public void clearDay(LocalDate day) {
        SqlParameterSource byDay = new MapSqlParameterSource("day", Date.valueOf(day))
                .addValue("period", BusinessDay.period(day));
        jdbc.update("DELETE FROM reporting.sales_daily_summary WHERE day = :day", byDay);
        jdbc.update("DELETE FROM reporting.product_sales_summary WHERE period = :period", byDay);
        jdbc.update("DELETE FROM reporting.processed_order_events WHERE placed_day = :day", byDay);
    }

    private String write(List<OrderContribution.Line> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise order lines for the rollup ledger", e);
        }
    }

    private List<OrderContribution.Line> read(String lines) {
        try {
            return json.readValue(lines, LINES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable order lines in the rollup ledger", e);
        }
    }
}
