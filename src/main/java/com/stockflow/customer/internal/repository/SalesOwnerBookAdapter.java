package com.stockflow.customer.internal.repository;

import com.stockflow.customer.api.SalesOwnerBook;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link SalesOwnerBook} straight from {@code customer.customer}, {@code customer.customer_sales_owner_history} and
 * {@code customer.sales_assignment_setting} (V20270128000100): one statement per question, on
 * {@code ix_customer_sales_owner}.
 */
@Component
class SalesOwnerBookAdapter implements SalesOwnerBook {

    private final NamedParameterJdbcTemplate jdbc;

    SalesOwnerBookAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnedCustomer> ownedBy(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        SELECT id, sales_owner_id, sales_owner_since
                          FROM customer.customer
                         WHERE sales_owner_id IN (:users) AND sales_owner_state = 'ASSIGNED'""",
                Map.of("users", Set.copyOf(userIds)),
                (rs, n) -> new OwnedCustomer(rs.getObject("id", UUID.class),
                        rs.getObject("sales_owner_id", UUID.class), instant(rs.getTimestamp("sales_owner_since"))));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> assignedSince(Collection<UUID> userIds, Instant since) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.query("""
                        SELECT to_user_id, count(DISTINCT customer_id) AS given
                          FROM customer.customer_sales_owner_history
                         WHERE to_user_id IN (:users) AND changed_at >= :since
                           AND reason NOT IN ('BACKFILL', 'TEMPORARY_LEAVE', 'RETURN_FROM_LEAVE')
                         GROUP BY to_user_id""",
                Map.of("users", Set.copyOf(userIds), "since", Timestamp.from(since)),
                rs -> {
                    counts.put(rs.getObject("to_user_id", UUID.class), rs.getInt("given"));
                });
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, AssignmentSetting> assignmentSettings(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AssignmentSetting> settings = new HashMap<>();
        jdbc.query("""
                        SELECT user_id, paused, capacity_per_month
                          FROM customer.sales_assignment_setting
                         WHERE user_id IN (:users)""",
                Map.of("users", Set.copyOf(userIds)),
                rs -> {
                    UUID user = rs.getObject("user_id", UUID.class);
                    settings.put(user, new AssignmentSetting(user, rs.getBoolean("paused"),
                            rs.getObject("capacity_per_month", Integer.class)));
                });
        return settings;
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }
}
