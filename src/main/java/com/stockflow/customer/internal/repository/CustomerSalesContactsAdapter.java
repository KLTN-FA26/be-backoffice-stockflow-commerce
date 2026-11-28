package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.SalesContacts;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** {@link SalesContacts} in SQL, over {@code customer.customer_sales_contact} and the owner history. */
@Repository
class CustomerSalesContactsAdapter implements SalesContacts {

    private final NamedParameterJdbcTemplate jdbc;

    CustomerSalesContactsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID customerId, UUID userId, Source source, Instant at) {
        jdbc.update("""
                INSERT INTO customer.customer_sales_contact (customer_id, user_id, last_contact_at, last_source)
                VALUES (:customer, :user, :at, :source)
                ON CONFLICT (customer_id, user_id) DO UPDATE
                   SET last_contact_at = EXCLUDED.last_contact_at, last_source = EXCLUDED.last_source
                 WHERE customer.customer_sales_contact.last_contact_at < EXCLUDED.last_contact_at""",
                new MapSqlParameterSource("customer", customerId).addValue("user", userId)
                        .addValue("at", Timestamp.from(at)).addValue("source", source.name()));
    }

    @Override
    public Set<Pair> inTouchSince(Collection<Pair> pairs, Instant since) {
        List<Pair> asked = pairs == null ? List.of() : pairs.stream()
                .filter(pair -> pair.customerId() != null && pair.userId() != null).distinct().toList();
        if (asked.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query("""
                SELECT p.customer_id, p.user_id
                  FROM unnest(CAST(:customers AS uuid[]), CAST(:users AS uuid[])) AS p(customer_id, user_id)
                 WHERE EXISTS (SELECT 1 FROM customer.customer_sales_contact s
                                WHERE s.customer_id = p.customer_id AND s.user_id = p.user_id
                                  AND s.last_contact_at >= :since)
                    OR EXISTS (SELECT 1 FROM customer.customer_sales_owner_history h
                                WHERE h.customer_id = p.customer_id AND h.changed_at >= :since
                                  AND (h.to_user_id = p.user_id OR h.from_user_id = p.user_id))""",
                new MapSqlParameterSource("customers", array(asked.stream().map(Pair::customerId).toList()))
                        .addValue("users", array(asked.stream().map(Pair::userId).toList()))
                        .addValue("since", Timestamp.from(since)),
                (rs, row) -> new Pair(rs.getObject("customer_id", UUID.class), rs.getObject("user_id", UUID.class))));
    }

    private static String array(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }
}
