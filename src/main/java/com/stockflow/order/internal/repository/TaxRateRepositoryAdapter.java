package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.SkuProductionHistory;
import com.stockflow.order.internal.domain.TaxRates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code ordering.tax_rate}, its periods ({@code ordering.tax_rate_period}, WF-05 E2) and the order lines a SKU's production history is read from (4B-3). Plain SQL: two small
 * reads that join the caller's transaction.
 */
@Repository
class TaxRateRepositoryAdapter implements TaxRates, SkuProductionHistory {

    private final JdbcTemplate jdbc;

    TaxRateRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, BigDecimal> onDay(LocalDate day) {
        Map<String, BigDecimal> rates = new HashMap<>();
        // At most one period covers a day (ex_tax_rate_period_overlap), so the join never doubles a class.
        jdbc.query("""
                SELECT r.tax_class, COALESCE(p.rate, r.rate)
                  FROM ordering.tax_rate r
                  LEFT JOIN ordering.tax_rate_period p
                    ON p.tax_class = r.tax_class AND ? BETWEEN p.valid_from AND p.valid_to""",
                row -> { rates.put(row.getString(1), row.getBigDecimal(2)); }, java.sql.Date.valueOf(day));
        return rates;
    }

    /**
     * A SKU on a line of an order confirmed and not cancelled - DRAFT, PENDING_CONTRACT and PENDING_PAYMENT orders were
     * never produced, and neither was an order held for credit, which waits for accounting before it is confirmed at
     * all - or on a production order that reached In Production ({@code production_started_at} set), whatever became
     * of the order since: the plate was made then (QD4).
     */
    @Override
    public Set<String> producedBefore(Collection<String> skus) {
        Set<String> produced = new HashSet<>();
        if (skus == null || skus.isEmpty()) {
            return produced;
        }
        jdbc.query("""
                SELECT DISTINCT l.sku FROM ordering.order_line l
                  JOIN ordering.customer_order o ON o.id = l.order_id
                 WHERE l.sku = ANY (?) AND o.status NOT IN ('DRAFT', 'PENDING_CONTRACT', 'PENDING_PAYMENT', 'CANCELLED')
                   AND NOT (o.status = 'ON_HOLD' AND o.hold_reason IS NOT DISTINCT FROM 'CREDIT')
                UNION
                SELECT pl.sku FROM ordering.production_order_line pl
                  JOIN ordering.production_order p ON p.id = pl.production_order_id
                 WHERE pl.sku = ANY (?) AND p.production_started_at IS NOT NULL""",
                ps -> {
                    java.sql.Array codes = ps.getConnection().createArrayOf("varchar", skus.toArray());
                    ps.setArray(1, codes);
                    ps.setArray(2, codes);
                },
                row -> { produced.add(row.getString(1)); });
        return produced;
    }
}
