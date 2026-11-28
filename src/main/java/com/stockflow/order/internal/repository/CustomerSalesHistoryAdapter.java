package com.stockflow.order.internal.repository;

import com.stockflow.order.api.CustomerSalesHistory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The order module's answer to {@link CustomerSalesHistory}: one statement over its own tables, as
 * {@link CustomerDocumentsAdapter} answers its questions, so a price review does not load an order aggregate per SKU.
 *
 * <p><b>Sold</b> means agreed in a signed contract: the order has a contract signed and not cancelled, and the order
 * itself is not cancelled. A draft, a contract sent and never signed, or an order cancelled are prices nobody paid. The
 * latest is by the day the contract was signed, then by when the order was placed, so two orders signed the same day
 * give the one placed later.</p>
 */
@Component
class CustomerSalesHistoryAdapter implements CustomerSalesHistory {

    /** More SKUs than any quote has lines; a caller asking for more gets the first ones. */
    private static final int MAX_SKUS = 200;

    private static final String LAST_SOLD = """
            SELECT DISTINCT ON (l.sku) l.sku, l.unit_price, l.currency, s.signed_on, o.order_number, o.id AS order_id,
                   l.cartons, l.units_per_carton
              FROM ordering.order_line l
              JOIN ordering.customer_order o ON o.id = l.order_id
              JOIN LATERAL (SELECT max(k.signed_on) AS signed_on
                              FROM ordering.sales_contract k
                             WHERE k.order_id = o.id AND k.signed_on IS NOT NULL
                               AND k.status <> 'CANCELLED') s ON s.signed_on IS NOT NULL
             WHERE o.customer_id = :customerId
               AND o.status <> 'CANCELLED'
               AND l.sku IN (:skus)
             ORDER BY l.sku, s.signed_on DESC, o.placed_at DESC, o.id DESC""";

    private final NamedParameterJdbcTemplate jdbc;

    CustomerSalesHistoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, LastSold> lastSold(UUID customerId, Collection<String> skus) {
        if (customerId == null || skus == null) {
            return Map.of();
        }
        List<String> asked = skus.stream().filter(Objects::nonNull).distinct().limit(MAX_SKUS).toList();
        if (asked.isEmpty()) {
            return Map.of();
        }
        Map<String, LastSold> found = new LinkedHashMap<>();
        jdbc.query(LAST_SOLD, new MapSqlParameterSource("customerId", customerId).addValue("skus", asked), rs -> {
            Date signed = rs.getDate("signed_on");
            String sku = rs.getString("sku");
            found.put(sku, new LastSold(sku, rs.getBigDecimal("unit_price"), rs.getString("currency"),
                    signed == null ? null : signed.toLocalDate(), rs.getString("order_number"),
                    rs.getObject("order_id", UUID.class), (Integer) rs.getObject("cartons"),
                    (Integer) rs.getObject("units_per_carton")));
        });
        return Map.copyOf(found);
    }
}
