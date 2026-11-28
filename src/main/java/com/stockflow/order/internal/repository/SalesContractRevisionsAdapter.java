package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.SalesContractRevisions;
import com.stockflow.order.internal.domain.SentRevision;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SalesContractRevisions} in SQL on {@code ordering.sales_contract_revision}: a row is inserted when a revision is
 * sent and completed once when the customer asks for changes; {@code tg_sales_contract_revision_kept} refuses any other
 * change to it.
 */
@Repository
class SalesContractRevisionsAdapter implements SalesContractRevisions {

    private static final String COLUMNS = "contract_id, revision, sent_document_key, sent_at, sent_by, "
            + "change_request_reason, change_requested_at, change_requested_by";

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditorAware<String> auditor;

    SalesContractRevisionsAdapter(NamedParameterJdbcTemplate jdbc, AuditorAware<String> auditor) {
        this.jdbc = jdbc;
        this.auditor = auditor;
    }

    @Override
    public void recordSent(UUID contractId, SentRevision sent) {
        jdbc.update("""
                INSERT INTO ordering.sales_contract_revision (contract_id, revision, sent_document_key, sent_at, sent_by,
                    created_at, created_by)
                VALUES (:contract, :revision, :key, :at, :by, :at, :auditor)
                ON CONFLICT (contract_id, revision) DO NOTHING""", new MapSqlParameterSource()
                .addValue("contract", contractId).addValue("revision", sent.revision())
                .addValue("key", sent.documentKey()).addValue("at", Timestamp.from(sent.sentAt()))
                .addValue("by", sent.sentBy()).addValue("auditor", auditor.getCurrentAuditor().orElse(null)));
    }

    @Override
    public void recordChangeRequest(UUID contractId, int revision, String reason, Instant at, UUID by) {
        int changed = jdbc.update("""
                UPDATE ordering.sales_contract_revision
                   SET change_request_reason = :reason, change_requested_at = :at, change_requested_by = :by,
                       last_modified_at = :at, last_modified_by = :auditor
                 WHERE contract_id = :contract AND revision = :revision AND change_request_reason IS NULL""",
                new MapSqlParameterSource().addValue("contract", contractId).addValue("revision", revision)
                        .addValue("reason", reason).addValue("at", Timestamp.from(at)).addValue("by", by)
                        .addValue("auditor", auditor.getCurrentAuditor().orElse(null)));
        if (changed != 1) {
            throw new IllegalStateException("Contract " + contractId + " revision " + revision
                    + " has no sent revision to record a change request on");
        }
    }

    @Override
    public List<Revision> of(UUID contractId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ordering.sales_contract_revision WHERE contract_id = :contract "
                + "ORDER BY revision", new MapSqlParameterSource("contract", contractId),
                (rs, n) -> new Revision(rs.getObject("contract_id", UUID.class), rs.getInt("revision"),
                        rs.getString("sent_document_key"), rs.getTimestamp("sent_at").toInstant(),
                        rs.getObject("sent_by", UUID.class), rs.getString("change_request_reason"),
                        instant(rs.getTimestamp("change_requested_at")),
                        rs.getObject("change_requested_by", UUID.class)));
    }

    @Override
    public Optional<Revision> find(UUID contractId, int revision) {
        return of(contractId).stream().filter(r -> r.revision() == revision).findFirst();
    }

    @Override
    public List<Overdue> claimOverdue(LocalDate today, Instant at, int limit) {
        return jdbc.query("""
                UPDATE ordering.sales_contract_revision r
                   SET sign_deadline_notified_at = :at, last_modified_at = :at
                  FROM ordering.sales_contract c, ordering.customer_order o
                 WHERE c.id = r.contract_id AND o.id = c.order_id AND c.revision = r.revision
                   AND c.status = 'SENT' AND c.sign_by < :today AND r.sign_deadline_notified_at IS NULL
                   AND (r.contract_id, r.revision) IN (
                        SELECT r2.contract_id, r2.revision
                          FROM ordering.sales_contract_revision r2
                          JOIN ordering.sales_contract c2 ON c2.id = r2.contract_id AND c2.revision = r2.revision
                         WHERE c2.status = 'SENT' AND c2.sign_by < :today AND r2.sign_deadline_notified_at IS NULL
                         ORDER BY c2.sign_by, r2.contract_id
                         LIMIT :limit
                         FOR UPDATE OF r2 SKIP LOCKED)
                RETURNING c.id AS contract_id, c.contract_number, c.order_id, o.order_number, o.customer_id,
                          c.revision, c.sign_by""", new MapSqlParameterSource().addValue("at", Timestamp.from(at))
                .addValue("today", Date.valueOf(today)).addValue("limit", limit),
                (rs, n) -> new Overdue(rs.getObject("contract_id", UUID.class), rs.getString("contract_number"),
                        rs.getObject("order_id", UUID.class), rs.getString("order_number"),
                        rs.getObject("customer_id", UUID.class), rs.getInt("revision"),
                        rs.getDate("sign_by").toLocalDate()));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
