package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.SalesContacts;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link SalesContacts} in one statement over {@code ordering.quote} and {@code ordering.sales_contract}: every sent
 * document of the customers asked, newest first per customer, the first kept ({@code DISTINCT ON}). The quotes are found
 * through {@code ix_quote_customer}, the contracts through their order ({@code ix_customer_order_customer}, then
 * {@code ix_sales_contract_order}), so a page of the customer list costs one bounded read, not one per row.
 *
 * <p>A document counts whatever it became afterwards - cancelled, expired, converted: the question is who last dealt
 * with the customer, not whose deal is still open. A contract's salesperson is {@code COALESCE(sent_by, signed_by)}, as
 * everywhere else a contract's responsibility is read ({@code SalesRecipients}, {@code SalesFollowUpsAdapter}); its time
 * goes with whichever of the two named the person. Ties on the same instant fall to the later id (UUIDv7: created
 * later).</p>
 */
@Repository
class SalesContactsAdapter implements SalesContacts {

    private final NamedParameterJdbcTemplate jdbc;

    SalesContactsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Latest> latestOf(Collection<UUID> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Latest> found = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (d.customer_id) d.customer_id, d.source, d.number, d.sales_user_id, d.at
                  FROM (SELECT q.customer_id, 'QUOTE' AS source, q.quote_number AS number, q.sent_by AS sales_user_id,
                               q.sent_at AS at, q.id
                          FROM ordering.quote q
                         WHERE q.customer_id IN (:ids) AND q.sent_by IS NOT NULL AND q.sent_at IS NOT NULL
                        UNION ALL
                        SELECT o.customer_id, 'CONTRACT', k.contract_number, COALESCE(k.sent_by, k.signed_by),
                               CASE WHEN k.sent_by IS NOT NULL THEN k.sent_at ELSE k.signed_at END, k.id
                          FROM ordering.sales_contract k
                          JOIN ordering.customer_order o ON o.id = k.order_id
                         WHERE o.customer_id IN (:ids) AND COALESCE(k.sent_by, k.signed_by) IS NOT NULL) d
                 ORDER BY d.customer_id, d.at DESC, d.id DESC""",
                Map.of("ids", Set.copyOf(customerIds)),
                rs -> {
                    UUID customerId = rs.getObject("customer_id", UUID.class);
                    found.put(customerId, new Latest(customerId, Source.valueOf(rs.getString("source")),
                            rs.getString("number"), rs.getObject("sales_user_id", UUID.class),
                            rs.getTimestamp("at").toInstant()));
                });
        return found;
    }
}
