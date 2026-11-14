package com.stockflow.invoicing.internal.repository;

import com.stockflow.invoicing.internal.domain.ErrorNotice;
import com.stockflow.invoicing.internal.domain.ErrorNoticeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ErrorNoticeRepository} over {@code invoicing.einvoice_error_notice}: a few plain rows per invoice, written
 * and read with SQL like the invoice's lines. The row lock, not a version, decides between two sendings.
 */
@Repository
class ErrorNoticeRepositoryAdapter implements ErrorNoticeRepository {

    private static final String COLUMNS = """
            id, einvoice_id, form_code, subject, reason, correct_buyer_name, correct_buyer_address, status, sent_at,
            response_code, response_message, responded_at, recorded_by, created_at""";

    private static final RowMapper<ErrorNotice.Snapshot> NOTICE = (row, n) -> new ErrorNotice.Snapshot(
            row.getObject("id", UUID.class), row.getObject("einvoice_id", UUID.class), row.getString("form_code"),
            ErrorNotice.Subject.valueOf(row.getString("subject")), row.getString("reason"),
            row.getString("correct_buyer_name"), row.getString("correct_buyer_address"),
            ErrorNotice.Status.valueOf(row.getString("status")), instant(row.getTimestamp("sent_at")),
            row.getString("response_code"), row.getString("response_message"),
            instant(row.getTimestamp("responded_at")), row.getObject("recorded_by", UUID.class),
            instant(row.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    ErrorNoticeRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ErrorNotice notice) {
        ErrorNotice.Snapshot s = notice.snapshot();
        jdbc.update("""
                INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, form_code, subject, reason,
                    correct_buyer_name, correct_buyer_address, status, recorded_by, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", s.id(), s.einvoiceId(), s.formCode(), s.subject().name(),
                s.reason(), s.correctBuyerName(), s.correctBuyerAddress(), s.status().name(), s.recordedBy(),
                Timestamp.from(s.createdAt()), s.recordedBy() == null ? "system" : s.recordedBy().toString());
    }

    @Override
    public Optional<ErrorNotice> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM invoicing.einvoice_error_notice WHERE id = ?", NOTICE, id)
                .stream().findFirst().map(ErrorNotice::restore);
    }

    @Override
    public Optional<ErrorNotice> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM invoicing.einvoice_error_notice WHERE id = ? FOR UPDATE",
                NOTICE, id).stream().findFirst().map(ErrorNotice::restore);
    }

    @Override
    public void update(ErrorNotice notice) {
        ErrorNotice.Snapshot s = notice.snapshot();
        jdbc.update("""
                UPDATE invoicing.einvoice_error_notice
                   SET status = ?, sent_at = ?, response_code = ?, response_message = ?, responded_at = ?,
                       version = version + 1, last_modified_at = NOW()
                 WHERE id = ?""", s.status().name(), timestamp(s.sentAt()), s.responseCode(), s.responseMessage(),
                timestamp(s.respondedAt()), s.id());
    }

    @Override
    public List<ErrorNotice.Snapshot> findByInvoice(UUID einvoiceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM invoicing.einvoice_error_notice WHERE einvoice_id = ? "
                + "ORDER BY created_at, id", NOTICE, einvoiceId);
    }

    @Override
    public boolean hasOpen(UUID einvoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice_error_notice
                                WHERE einvoice_id = ? AND status IN ('DRAFT', 'SENT'))""", Boolean.class, einvoiceId));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
