package com.stockflow.customer.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.internal.domain.OpenLeads;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link OpenLeads} in SQL: there is no lead entity yet (the lead feature brings it). Three statements whatever the
 * number of leads: lock them, put them back, write their history rows. Joins the caller's transaction.
 */
@Repository
class OpenLeadsAdapter implements OpenLeads {

    private final NamedParameterJdbcTemplate jdbc;

    OpenLeadsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> releaseOpenLeadsOf(UUID salesUserId, String note, UUID byUserId, Instant at) {
        if (salesUserId == null) {
            return List.of();
        }
        record Open(UUID id, String status) {
        }
        List<Open> open = jdbc.query("""
                SELECT id, status FROM customer.lead
                 WHERE assigned_to = :user AND status IN ('ASSIGNED', 'CONTACTED')
                 ORDER BY id FOR UPDATE""", new MapSqlParameterSource("user", salesUserId),
                (rs, row) -> new Open(rs.getObject("id", UUID.class), rs.getString("status")));
        if (open.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = open.stream().map(Open::id).toList();
        Timestamp when = Timestamp.from(at);
        jdbc.update("""
                UPDATE customer.lead
                   SET status = 'NEW', assigned_to = NULL, assigned_by = NULL, assigned_at = NULL,
                       version = version + 1, last_modified_at = :at, last_modified_by = 'system'
                 WHERE id IN (:ids)""", new MapSqlParameterSource("ids", ids).addValue("at", when));
        jdbc.batchUpdate("""
                INSERT INTO customer.lead_history (id, lead_id, from_status, to_status, from_user_id, to_user_id, note,
                                                   changed_by, changed_at, version, created_at, created_by)
                VALUES (:id, :lead, :from, 'NEW', :user, NULL, :note, :by, :at, 0, :at, 'system')""",
                open.stream().map(lead -> new MapSqlParameterSource("id", Identifiers.newId())
                        .addValue("lead", lead.id()).addValue("from", lead.status()).addValue("user", salesUserId)
                        .addValue("note", note).addValue("by", byUserId).addValue("at", when))
                        .toArray(MapSqlParameterSource[]::new));
        return ids;
    }
}
