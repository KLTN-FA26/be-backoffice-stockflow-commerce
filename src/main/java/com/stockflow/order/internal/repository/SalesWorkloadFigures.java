package com.stockflow.order.internal.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The order module's figures of the Sales Managers' workload board (sales-owner plan §10.4), grouped in SQL: one
 * statement per figure for every customer or salesperson asked, never one per row.
 */
@Repository
public class SalesWorkloadFigures {

    /** The figures of one customer's documents. */
    public record CustomerFigures(int openQuotes, int contractsAwaitingSignature, boolean openOrders) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SalesWorkloadFigures(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * For each customer that has any: its quotes still open - a draft, sent and not past its validity, changes asked,
     * accepted and not converted yet - its contracts sent and not signed, and whether an order of it is under way (not
     * cancelled, closed or completed, and past its draft). Customers with none of it are absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, CustomerFigures> byCustomer(Collection<UUID> customerIds, Instant now) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CustomerFigures> figures = new HashMap<>();
        jdbc.query("""
                        SELECT d.customer_id, sum(d.quotes) AS quotes, sum(d.contracts) AS contracts,
                               sum(d.orders) AS orders
                          FROM (SELECT q.customer_id, count(*) AS quotes, 0 AS contracts, 0 AS orders
                                  FROM ordering.quote q
                                 WHERE q.customer_id IN (:customers)
                                   AND (q.status IN ('DRAFT', 'CHANGES_REQUESTED', 'ACCEPTED')
                                        OR (q.status = 'SENT' AND q.valid_until > :now))
                                 GROUP BY q.customer_id
                                UNION ALL
                                SELECT o.customer_id, 0, count(*), 0
                                  FROM ordering.sales_contract k
                                  JOIN ordering.customer_order o ON o.id = k.order_id
                                 WHERE o.customer_id IN (:customers) AND k.status = 'SENT'
                                 GROUP BY o.customer_id
                                UNION ALL
                                SELECT o.customer_id, 0, 0, count(*)
                                  FROM ordering.customer_order o
                                 WHERE o.customer_id IN (:customers)
                                   AND o.status NOT IN ('DRAFT', 'CANCELLED', 'CLOSED', 'COMPLETED')
                                 GROUP BY o.customer_id) d
                         GROUP BY d.customer_id""",
                Map.of("customers", Set.copyOf(customerIds), "now", Timestamp.from(now)),
                rs -> {
                    figures.put(rs.getObject("customer_id", UUID.class), new CustomerFigures(rs.getInt("quotes"),
                            rs.getInt("contracts"), rs.getLong("orders") > 0));
                });
        return figures;
    }

    /**
     * Sales signed since {@code since}, by the salesperson the contract recorded as the customer's owner when it was
     * drawn up (14 BR-26 - never the owner of today): the orders' totals less what was closed of them, cancelled orders
     * left out. People with none are absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> revenueSince(Collection<UUID> userIds, Instant since) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> revenue = new HashMap<>();
        jdbc.query("""
                        SELECT k.sales_owner_id, sum(o.total_amount - o.closed_amount) AS revenue
                          FROM ordering.sales_contract k
                          JOIN ordering.customer_order o ON o.id = k.order_id
                         WHERE k.sales_owner_id IN (:users) AND k.signed_at >= :since
                           AND k.status IN ('SIGNED', 'COMPLETED') AND o.status <> 'CANCELLED'
                         GROUP BY k.sales_owner_id""",
                Map.of("users", Set.copyOf(userIds), "since", Timestamp.from(since)),
                rs -> {
                    revenue.put(rs.getObject("sales_owner_id", UUID.class), rs.getBigDecimal("revenue"));
                });
        return revenue;
    }
}
