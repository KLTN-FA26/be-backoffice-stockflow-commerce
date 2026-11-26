package com.stockflow.order.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.order.internal.domain.QuotePriceReviews;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@link QuotePriceReviews} in SQL: a review is written once when opened and once when closed, never deleted. */
@Repository
class QuotePriceReviewsAdapter implements QuotePriceReviews {

    private static final String COLUMNS = "id, quote_id, revision, outcome, threshold_percent, CAST(lines AS text), "
            + "submitted_at, submitted_by, decided_at, decided_by, reason";

    private final JdbcTemplate jdbc;
    private final AuditorAware<String> auditor;

    QuotePriceReviewsAdapter(JdbcTemplate jdbc, AuditorAware<String> auditor) {
        this.jdbc = jdbc;
        this.auditor = auditor;
    }

    @Override
    public Review open(UUID quoteId, int revision, BigDecimal thresholdPercent, String lines, UUID by, Instant at) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO ordering.quote_price_review (id, quote_id, revision, outcome, threshold_percent, lines,
                    submitted_at, submitted_by, version, created_at, created_by)
                VALUES (?, ?, ?, 'PENDING', ?, CAST(? AS jsonb), ?, ?, 0, ?, ?)""",
                id, quoteId, revision, thresholdPercent, lines, Timestamp.from(at), by, Timestamp.from(at),
                auditor.getCurrentAuditor().orElse(null));
        return new Review(id, quoteId, revision, Outcome.PENDING, thresholdPercent, lines, at, by, null, null, null);
    }

    @Override
    public Optional<Review> pending(UUID quoteId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ordering.quote_price_review WHERE quote_id = ? "
                + "AND outcome = 'PENDING'", (r, n) -> review(r), quoteId).stream().findFirst();
    }

    @Override
    public Optional<Review> latest(UUID quoteId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ordering.quote_price_review WHERE quote_id = ? "
                + "ORDER BY submitted_at DESC, id DESC LIMIT 1", (r, n) -> review(r), quoteId).stream().findFirst();
    }

    @Override
    public void close(UUID reviewId, Outcome outcome, UUID by, Instant at, String reason) {
        if (outcome == Outcome.PENDING) {
            throw new IllegalArgumentException("A review is closed approved, rejected or withdrawn");
        }
        int closed = jdbc.update("""
                UPDATE ordering.quote_price_review
                   SET outcome = ?, decided_by = ?, decided_at = ?, reason = ?, version = version + 1,
                       last_modified_at = ?, last_modified_by = ?
                 WHERE id = ? AND outcome = 'PENDING'""",
                outcome.name(), by, Timestamp.from(at), reason, Timestamp.from(at),
                auditor.getCurrentAuditor().orElse(null), reviewId);
        if (closed != 1) {
            throw new IllegalStateException("Price review " + reviewId + " was not waiting");
        }
    }

    private static Review review(ResultSet r) throws SQLException {
        Timestamp decided = r.getTimestamp(9);
        return new Review(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getInt(3),
                Outcome.valueOf(r.getString(4)), r.getBigDecimal(5), r.getString(6), r.getTimestamp(7).toInstant(),
                r.getObject(8, UUID.class), decided == null ? null : decided.toInstant(), r.getObject(10, UUID.class),
                r.getString(11));
    }
}
