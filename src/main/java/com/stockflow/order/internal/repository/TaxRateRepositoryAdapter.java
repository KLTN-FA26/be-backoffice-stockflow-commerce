package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.SkuProductionHistory;
import com.stockflow.order.internal.domain.TaxRates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code ordering.tax_rate} and the order lines a SKU's production history is read from (4B-3). Plain SQL: two small
 * reads that join the caller's transaction.
 */
@Repository
class TaxRateRepositoryAdapter implements TaxRates, SkuProductionHistory {

    private final JdbcTemplate jdbc;

    TaxRateRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, BigDecimal> all() {
        Map<String, BigDecimal> rates = new HashMap<>();
        jdbc.query("SELECT tax_class, rate FROM ordering.tax_rate",
                row -> { rates.put(row.getString(1), row.getBigDecimal(2)); });
        return rates;
    }

    /**
     * A SKU on a line of an order confirmed and not cancelled: DRAFT, PENDING_CONTRACT and PENDING_PAYMENT orders were
     * never produced, and
     * neither was an order held for credit, which waits for accounting before it is confirmed at all.
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
                   AND NOT (o.status = 'ON_HOLD' AND o.hold_reason IS NOT DISTINCT FROM 'CREDIT')""",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("varchar", skus.toArray())),
                row -> { produced.add(row.getString(1)); });
        return produced;
    }
}
