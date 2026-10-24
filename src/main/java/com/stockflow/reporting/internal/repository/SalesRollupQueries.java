package com.stockflow.reporting.internal.repository;

import com.stockflow.reporting.internal.domain.BusinessDay;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The reads behind the dashboard, each one aggregate query over the two rollup tables. Days are inclusive at both ends;
 * only {@code currency} rows are read (single currency, VND).
 */
@Repository
public class SalesRollupQueries {

    private final NamedParameterJdbcTemplate jdbc;

    SalesRollupQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One row of the daily series; days with no orders have no row. */
    public record DayRow(LocalDate day, int ordersCount, BigDecimal revenue) {
    }

    public record ProductRow(String sku, long quantitySold, BigDecimal revenue) {
    }

    public record Totals(long ordersCount, BigDecimal revenue) {
    }

    public List<DayRow> days(LocalDate from, LocalDate to, String currency) {
        return jdbc.query("""
                SELECT day, orders_count, revenue FROM reporting.sales_daily_summary
                WHERE day BETWEEN :from AND :to AND currency = :currency ORDER BY day""",
                range(from, to).addValue("currency", currency),
                (rs, row) -> new DayRow(rs.getDate("day").toLocalDate(), rs.getInt("orders_count"),
                        rs.getBigDecimal("revenue")));
    }

    public Totals totals(LocalDate from, LocalDate to, String currency) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(orders_count), 0), COALESCE(SUM(revenue), 0) FROM reporting.sales_daily_summary
                WHERE day BETWEEN :from AND :to AND currency = :currency""",
                range(from, to).addValue("currency", currency),
                (rs, row) -> new Totals(rs.getLong(1), rs.getBigDecimal(2)));
    }

    /** Best sellers of the range by revenue, then units, then SKU (so equal rows keep a stable order). */
    public List<ProductRow> topProducts(LocalDate from, LocalDate to, int limit) {
        return jdbc.query("""
                SELECT sku, SUM(quantity_sold) AS quantity_sold, SUM(revenue) AS revenue
                FROM reporting.product_sales_summary
                WHERE period BETWEEN :fromPeriod AND :toPeriod
                GROUP BY sku
                HAVING SUM(quantity_sold) > 0
                ORDER BY SUM(revenue) DESC, SUM(quantity_sold) DESC, sku
                LIMIT :limit""",
                new MapSqlParameterSource("fromPeriod", BusinessDay.period(from))
                        .addValue("toPeriod", BusinessDay.period(to))
                        .addValue("limit", limit),
                (rs, row) -> new ProductRow(rs.getString("sku"), rs.getLong("quantity_sold"),
                        rs.getBigDecimal("revenue")));
    }

    /** Units and revenue of the given SKUs over the range; a SKU with no row in the range is absent. */
    /** @param firstSoldOn the first day with units sold; null when every day of the range nets to zero */
    public record SkuRow(String sku, long quantitySold, BigDecimal revenue, LocalDate firstSoldOn) {
    }

    public List<SkuRow> salesOf(java.util.Collection<String> skus, LocalDate from, LocalDate to) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT sku, SUM(quantity_sold) AS quantity_sold, SUM(revenue) AS revenue,
                       MIN(period) FILTER (WHERE quantity_sold > 0) AS first_sold
                FROM reporting.product_sales_summary
                WHERE period BETWEEN :fromPeriod AND :toPeriod AND sku IN (:skus)
                GROUP BY sku
                ORDER BY sku""",
                new MapSqlParameterSource("fromPeriod", BusinessDay.period(from))
                        .addValue("toPeriod", BusinessDay.period(to))
                        .addValue("skus", skus),
                (rs, row) -> {
                    String first = rs.getString("first_sold");
                    return new SkuRow(rs.getString("sku"), rs.getLong("quantity_sold"), rs.getBigDecimal("revenue"),
                            first == null ? null : LocalDate.parse(first));
                });
    }

    /** The earliest day the ledger knows an order for; empty when nothing was ever rolled up. */
    public Optional<LocalDate> earliestRolledUpDay() {
        return Optional.ofNullable(jdbc.getJdbcTemplate().queryForObject(
                "SELECT MIN(placed_day) FROM reporting.processed_order_events", Date.class))
                .map(Date::toLocalDate);
    }

    private static MapSqlParameterSource range(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("from", Date.valueOf(from)).addValue("to", Date.valueOf(to));
    }
}
