package com.stockflow.order.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.order.internal.domain.ContractCreditFigures;
import com.stockflow.order.internal.domain.ContractCreditReviews;
import com.stockflow.order.internal.domain.CreditChecks;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ContractCreditReviews} in SQL: a review is written once when opened and once when closed, never deleted - as
 * {@code QuotePriceReviewsAdapter} keeps the quotes' price reviews.
 */
@Repository
class ContractCreditReviewsAdapter implements ContractCreditReviews {

    private static final String COLUMNS = "r.id, r.contract_id, r.contract_version, r.outcome, r.check_outcome, "
            + "r.credit_limit, r.exposure, r.after_delivery, r.overdue_amount, r.submitted_at, r.submitted_by, "
            + "r.decided_at, r.decided_by, r.note";
    private static final String WAITING = """
            SELECT r.id, r.contract_id, c.contract_number, c.version, o.id, o.order_number, o.customer_id,
                   r.check_outcome, r.credit_limit, r.exposure, r.after_delivery, r.overdue_amount, r.submitted_by,
                   r.submitted_at
              FROM ordering.contract_credit_review r
              JOIN ordering.sales_contract c ON c.id = r.contract_id
              JOIN ordering.customer_order o ON o.id = c.order_id
             WHERE r.outcome = 'PENDING'""";

    private final JdbcTemplate jdbc;
    private final AuditorAware<String> auditor;

    ContractCreditReviewsAdapter(JdbcTemplate jdbc, AuditorAware<String> auditor) {
        this.jdbc = jdbc;
        this.auditor = auditor;
    }

    @Override
    public Review open(UUID contractId, long contractVersion, ContractCreditFigures figures, UUID by, Instant at) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO ordering.contract_credit_review (id, contract_id, contract_version, outcome, check_outcome,
                    credit_limit, exposure, after_delivery, overdue_amount, submitted_at, submitted_by, version,
                    created_at, created_by)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)""",
                id, contractId, contractVersion, figures.outcome().name(), figures.creditLimit(), figures.exposure(),
                figures.afterDelivery(), figures.overdueAmount(), Timestamp.from(at), by, Timestamp.from(at),
                auditor.getCurrentAuditor().orElse(null));
        return new Review(id, contractId, contractVersion, Outcome.PENDING, figures, at, by, null, null, null);
    }

    @Override
    public Optional<Review> pending(UUID contractId) {
        return one("WHERE r.contract_id = ? AND r.outcome = 'PENDING'", contractId);
    }

    @Override
    public Optional<Review> latest(UUID contractId) {
        return one("WHERE r.contract_id = ? ORDER BY r.submitted_at DESC, r.id DESC LIMIT 1", contractId);
    }

    @Override
    public Optional<Review> approved(UUID contractId) {
        return one("WHERE r.contract_id = ? AND r.outcome = 'APPROVED' ORDER BY r.decided_at DESC LIMIT 1",
                contractId);
    }

    @Override
    public void close(UUID reviewId, Outcome outcome, UUID by, Instant at, String note) {
        if (outcome == Outcome.PENDING) {
            throw new IllegalArgumentException("A review is closed approved, rejected or withdrawn");
        }
        int closed = jdbc.update("""
                UPDATE ordering.contract_credit_review
                   SET outcome = ?, decided_by = ?, decided_at = ?, note = ?, version = version + 1,
                       last_modified_at = ?, last_modified_by = ?
                 WHERE id = ? AND outcome = 'PENDING'""",
                outcome.name(), by, Timestamp.from(at), note, Timestamp.from(at),
                auditor.getCurrentAuditor().orElse(null), reviewId);
        if (closed != 1) {
            throw new IllegalStateException("Contract credit review " + reviewId + " was not waiting");
        }
    }

    @Override
    public List<Waiting> waiting(long offset, int limit) {
        return jdbc.query(WAITING + " ORDER BY r.submitted_at, r.id OFFSET ? LIMIT ?", (r, n) -> waiting(r),
                offset, limit);
    }

    @Override
    public long countWaiting() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ordering.contract_credit_review WHERE outcome = 'PENDING'",
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<Waiting> waitingSince(Instant submittedBy, int limit) {
        return jdbc.query(WAITING + " AND r.submitted_at <= ? ORDER BY r.submitted_at DESC, r.id DESC LIMIT ?",
                (r, n) -> waiting(r), Timestamp.from(submittedBy), limit);
    }

    private Optional<Review> one(String where, UUID contractId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ordering.contract_credit_review r " + where,
                (r, n) -> review(r), contractId).stream().findFirst();
    }

    private static Review review(ResultSet r) throws SQLException {
        Timestamp decided = r.getTimestamp(12);
        return new Review(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3),
                Outcome.valueOf(r.getString(4)), new ContractCreditFigures(CreditChecks.Outcome.valueOf(r.getString(5)),
                r.getBigDecimal(6), r.getBigDecimal(7), r.getBigDecimal(8), r.getBigDecimal(9)),
                r.getTimestamp(10).toInstant(), r.getObject(11, UUID.class),
                decided == null ? null : decided.toInstant(), r.getObject(13, UUID.class), r.getString(14));
    }

    private static Waiting waiting(ResultSet r) throws SQLException {
        return new Waiting(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getLong(4),
                r.getObject(5, UUID.class), r.getString(6), r.getObject(7, UUID.class),
                new ContractCreditFigures(CreditChecks.Outcome.valueOf(r.getString(8)), r.getBigDecimal(9),
                        r.getBigDecimal(10), r.getBigDecimal(11), r.getBigDecimal(12)),
                r.getObject(13, UUID.class), r.getTimestamp(14).toInstant());
    }
}
