package com.stockflow.customer.internal.repository;

import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerCredits;
import com.stockflow.customer.api.CustomerSearch;
import com.stockflow.customer.api.CustomerSummaries;
import com.stockflow.customer.api.CustomerSummary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link CustomerSummaries} and {@link CustomerCredits}: one statement each over {@code customer.customer} and
 * {@code customer.credit_profile}, however many customers a report lists. The rows map to the api records exactly as
 * {@code CustomerServiceImpl} maps one customer (its {@code toSummary} and {@code toTerms}), so a report shows the same
 * code and the same credit as the customer's own screen. {@link CustomerSearch} too: one bounded statement for the ids
 * a search box means.
 */
@Component
class CustomerBatchReadsAdapter implements CustomerSummaries, CustomerCredits, CustomerSearch {

    private final NamedParameterJdbcTemplate jdbc;

    CustomerBatchReadsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CustomerSummary> summariesOf(Collection<UUID> customerIds) {
        Map<UUID, CustomerSummary> found = new HashMap<>();
        List<UUID> ids = ids(customerIds);
        if (ids.isEmpty()) {
            return found;
        }
        jdbc.query("""
                SELECT id, user_id, full_name, email, phone, tax_code, segment_id, status, version, created_at
                  FROM customer.customer
                 WHERE id IN (:ids)""", Map.of("ids", ids), rs -> {
            UUID id = rs.getObject("id", UUID.class);
            found.put(id, new CustomerSummary(id, rs.getObject("user_id", UUID.class), rs.getString("full_name"),
                    rs.getString("email"), rs.getString("phone"), rs.getString("tax_code"),
                    rs.getObject("segment_id", UUID.class), rs.getString("status"), rs.getLong("version"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()));
        });
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CreditTerms> creditTermsOf(Collection<UUID> customerIds) {
        Map<UUID, CreditTerms> found = new HashMap<>();
        List<UUID> ids = ids(customerIds);
        if (ids.isEmpty()) {
            return found;
        }
        jdbc.query("""
                SELECT c.id, p.id AS profile_id, p.credit_limit, p.credit_term_days, p.currency, p.approved_by,
                       p.approved_at, p.note, p.version
                  FROM customer.customer c
                  LEFT JOIN customer.credit_profile p ON p.customer_id = c.id
                 WHERE c.id IN (:ids)""", Map.of("ids", ids), rs -> {
            UUID id = rs.getObject("id", UUID.class);
            if (rs.getObject("profile_id") == null) {
                found.put(id, CreditTerms.none(id));
                return;
            }
            OffsetDateTime approvedAt = rs.getObject("approved_at", OffsetDateTime.class);
            found.put(id, new CreditTerms(id, true, rs.getBigDecimal("credit_limit"),
                    rs.getObject("credit_term_days", Integer.class), rs.getString("currency"),
                    rs.getObject("approved_by", UUID.class), approvedAt == null ? null : approvedAt.toInstant(),
                    rs.getString("note"), rs.getLong("version")));
        });
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> idsMatching(String search, int limit) {
        if (search == null || search.isBlank() || limit <= 0) {
            return List.of();
        }
        // A bound parameter, its LIKE wildcards escaped: "50%" is not "anything".
        String like = "%" + search.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.queryForList("""
                SELECT id FROM customer.customer
                 WHERE lower(public.unaccent(full_name)) LIKE lower(public.unaccent(:like)) ESCAPE '\\'
                    OR lower(tax_code) LIKE lower(:like) ESCAPE '\\'
                 ORDER BY id
                 LIMIT :limit""", Map.of("like", like, "limit", limit), UUID.class);
    }

    private static List<UUID> ids(Collection<UUID> customerIds) {
        return customerIds == null ? List.of() : customerIds.stream().filter(Objects::nonNull).distinct().toList();
    }
}
