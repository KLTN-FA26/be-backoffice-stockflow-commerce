package com.stockflow.order.internal.repository;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleSales;
import com.stockflow.order.api.CustomerDocument;
import com.stockflow.order.api.CustomerDocuments;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The order module's answer to {@link CustomerDocuments}: one statement per question, straight from its own tables, so
 * a module asking "whose is this quote" does not load the quote aggregate and its lines to learn one column.
 *
 * <p>The Sales account that answers for any of them is whoever looks after the document's <b>customer</b> today -
 * {@code customer :: api}'s {@link ResponsibleSales}, the owner or the owner's cover (sales-owner plan 2026-11-28; 00
 * §6, 18 BR-20) - not who sent the document: one customer has one contact, whoever sent its quote or contract. A
 * customer with no active owner has none ({@code responsibleUserId} null): its managers are no document's salesperson.
 * Asked once per answer, whatever the number of documents in it.</p>
 */
@Component
class CustomerDocumentsAdapter implements CustomerDocuments {

    private static final int MAX_LIMIT = 50;

    private static final String QUOTES = """
            SELECT q.id, q.customer_id, q.quote_number AS number, q.status,
                   NULL::numeric AS amount, NULL AS currency, q.created_at
              FROM ordering.quote q""";
    /**
     * {@link #QUOTES} as the customer's portal names the status: a revision being written after the customer asked for
     * changes (DRAFT, sent before) is CHANGES_REQUESTED to them ({@code Quote#revisingSentQuote}).
     */
    private static final String QUOTES_AS_CUSTOMER_SEES = """
            SELECT q.id, q.customer_id, q.quote_number AS number,
                   CASE WHEN q.status = 'DRAFT' AND q.sent_at IS NOT NULL THEN 'CHANGES_REQUESTED' ELSE q.status END
                       AS status,
                   NULL::numeric AS amount, NULL AS currency, q.created_at
              FROM ordering.quote q""";
    private static final String ORDERS = """
            SELECT o.id, o.customer_id, o.order_number AS number, o.status,
                   o.total_amount AS amount, o.currency, o.created_at
              FROM ordering.customer_order o""";
    private static final String CONTRACTS = """
            SELECT k.id, o.customer_id, k.contract_number AS number, k.status,
                   NULL::numeric AS amount, NULL AS currency, k.created_at
              FROM ordering.sales_contract k JOIN ordering.customer_order o ON o.id = k.order_id""";
    private static final String PRODUCTION_ORDERS = """
            SELECT p.id, p.customer_id, p.code AS number, p.status,
                   NULL::numeric AS amount, NULL AS currency, p.created_at
              FROM ordering.production_order p""";

    private static final RowMapper<CustomerDocument> ROW = (rs, n) -> {
        Timestamp created = rs.getTimestamp("created_at");
        return new CustomerDocument(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("number"), rs.getString("status"), null,
                rs.getBigDecimal("amount"), rs.getString("currency"), created == null ? null : created.toInstant());
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ResponsibleSales responsible;
    private final Clock clock;

    CustomerDocumentsAdapter(NamedParameterJdbcTemplate jdbc, ResponsibleSales responsible, Clock clock) {
        this.jdbc = jdbc;
        this.responsible = responsible;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerDocument> quote(UUID quoteId) {
        return one(QUOTES + " WHERE q.id = :id", quoteId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerDocument> quoteAsCustomerSees(UUID quoteId) {
        return one(QUOTES_AS_CUSTOMER_SEES + " WHERE q.id = :id", quoteId);
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

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UUID> contractSalespeople(java.util.Collection<UUID> contractIds) {
        if (contractIds == null || contractIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> customerOf = new java.util.HashMap<>();
        jdbc.query("""
                SELECT k.id, o.customer_id
                  FROM ordering.sales_contract k JOIN ordering.customer_order o ON o.id = k.order_id
                 WHERE k.id IN (:ids)""",
                Map.of("ids", java.util.Set.copyOf(contractIds)),
                rs -> {
                    customerOf.put(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class));
                });
        Map<UUID, UUID> acting = actingUsers(customerOf.values());
        Map<UUID, UUID> found = new java.util.HashMap<>();
        customerOf.forEach((contract, customer) -> {
            UUID sales = acting.get(customer);
            if (sales != null) {
                found.put(contract, sales);
            }
        });
        return found;
    }

    /** Who acts for each customer today - the owner or the cover; customers with no active owner are left out. */
    private Map<UUID, UUID> actingUsers(java.util.Collection<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> acting = new java.util.HashMap<>();
        for (Responsible who : responsible.responsibleFor(java.util.Set.copyOf(customerIds),
                BusinessCalendar.date(clock.instant())).values()) {
            if (who.actingUserId() != null) {
                acting.put(who.customerId(), who.actingUserId());
            }
        }
        return acting;
    }

    /** The documents with the Sales account that answers for their customer today. */
    private List<CustomerDocument> withResponsible(List<CustomerDocument> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        Map<UUID, UUID> acting = actingUsers(documents.stream().map(CustomerDocument::customerId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        return documents.stream().map(d -> new CustomerDocument(d.id(), d.customerId(), d.number(), d.status(),
                acting.get(d.customerId()), d.amount(), d.currency(), d.createdAt())).toList();
    }

    private Optional<CustomerDocument> one(String sql, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return withResponsible(jdbc.query(sql, Map.of("id", id), ROW)).stream().findFirst();
    }

    private List<CustomerDocument> recent(String sql, UUID customerId, int limit) {
        if (customerId == null) {
            return List.of();
        }
        return withResponsible(jdbc.query(sql + " LIMIT :limit", Map.of("customer", customerId,
                "limit", Math.max(1, Math.min(limit, MAX_LIMIT))), ROW));
    }
}
