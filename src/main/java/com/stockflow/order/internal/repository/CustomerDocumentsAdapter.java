package com.stockflow.order.internal.repository;

import com.stockflow.order.api.CustomerDocument;
import com.stockflow.order.api.CustomerDocuments;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The order module's answer to {@link CustomerDocuments}: one statement per question, straight from its own tables, so
 * a module asking "whose is this quote" does not load the quote aggregate and its lines to learn one column.
 *
 * <p>The Sales account that answers for an order or a production order is the one who sent the quote the order came
 * from: an order has no salesperson column of its own, and the quote is where Sales put their name.</p>
 */
@Component
class CustomerDocumentsAdapter implements CustomerDocuments {

    private static final int MAX_LIMIT = 50;

    private static final String QUOTES = """
            SELECT q.id, q.customer_id, q.quote_number AS number, q.status, q.sent_by AS responsible,
                   NULL::numeric AS amount, NULL AS currency, q.created_at
              FROM ordering.quote q""";
    private static final String ORDERS = """
            SELECT o.id, o.customer_id, o.order_number AS number, o.status,
                   (SELECT q.sent_by FROM ordering.quote q WHERE q.converted_order_id = o.id
                     ORDER BY q.converted_at DESC NULLS LAST LIMIT 1) AS responsible,
                   o.total_amount AS amount, o.currency, o.created_at
              FROM ordering.customer_order o""";
    private static final String CONTRACTS = """
            SELECT k.id, o.customer_id, k.contract_number AS number, k.status, k.sent_by AS responsible,
                   NULL::numeric AS amount, NULL AS currency, k.created_at
              FROM ordering.sales_contract k JOIN ordering.customer_order o ON o.id = k.order_id""";
    private static final String PRODUCTION_ORDERS = """
            SELECT p.id, p.customer_id, p.code AS number, p.status,
                   (SELECT q.sent_by FROM ordering.quote q WHERE q.converted_order_id = p.sales_order_id
                     ORDER BY q.converted_at DESC NULLS LAST LIMIT 1) AS responsible,
                   NULL::numeric AS amount, NULL AS currency, p.created_at
              FROM ordering.production_order p""";

    private static final RowMapper<CustomerDocument> ROW = (rs, n) -> {
        Timestamp created = rs.getTimestamp("created_at");
        return new CustomerDocument(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("number"), rs.getString("status"), rs.getObject("responsible", UUID.class),
                rs.getBigDecimal("amount"), rs.getString("currency"), created == null ? null : created.toInstant());
    };

    private final NamedParameterJdbcTemplate jdbc;

    CustomerDocumentsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerDocument> quote(UUID quoteId) {
        return one(QUOTES + " WHERE q.id = :id", quoteId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerDocument> order(UUID orderId) {
        return one(ORDERS + " WHERE o.id = :id", orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerDocument> contract(UUID contractId) {
        return one(CONTRACTS + " WHERE k.id = :id", contractId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerDocument> productionOrder(UUID productionOrderId) {
        return one(PRODUCTION_ORDERS + " WHERE p.id = :id", productionOrderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerDocument> recentQuotes(UUID customerId, int limit) {
        return recent(QUOTES + " WHERE q.customer_id = :customer ORDER BY q.created_at DESC, q.id DESC", customerId,
                limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerDocument> recentOrders(UUID customerId, int limit) {
        return recent(ORDERS + " WHERE o.customer_id = :customer ORDER BY o.created_at DESC, o.id DESC", customerId,
                limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerDocument> recentContracts(UUID customerId, int limit) {
        return recent(CONTRACTS + " WHERE o.customer_id = :customer ORDER BY k.created_at DESC, k.id DESC", customerId,
                limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerDocument> recentProductionOrders(UUID customerId, int limit) {
        return recent(PRODUCTION_ORDERS + " WHERE p.customer_id = :customer ORDER BY p.created_at DESC, p.id DESC",
                customerId, limit);
    }

    private Optional<CustomerDocument> one(String sql, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.query(sql, Map.of("id", id), ROW).stream().findFirst();
    }

    private List<CustomerDocument> recent(String sql, UUID customerId, int limit) {
        if (customerId == null) {
            return List.of();
        }
        return jdbc.query(sql + " LIMIT :limit", Map.of("customer", customerId,
                "limit", Math.max(1, Math.min(limit, MAX_LIMIT))), ROW);
    }
}
