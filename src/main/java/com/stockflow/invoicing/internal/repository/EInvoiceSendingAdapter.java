package com.stockflow.invoicing.internal.repository;

import com.stockflow.invoicing.internal.domain.EInvoiceSending;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@link EInvoiceSending} over {@code invoicing.einvoice_sending}, one row per invoice. */
@Repository
class EInvoiceSendingAdapter implements EInvoiceSending {

    private final JdbcTemplate jdbc;

    EInvoiceSendingAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> lastSent(UUID einvoiceId, String transactionId) {
        return jdbc.query("""
                SELECT last_sent_at FROM invoicing.einvoice_sending
                 WHERE einvoice_id = ? AND transaction_id = ? AND last_sent_at IS NOT NULL""",
                (row, n) -> row.getTimestamp("last_sent_at").toInstant(), einvoiceId, transactionId).stream().findFirst();
    }

    @Override
    public void sent(UUID einvoiceId, String transactionId, Instant at) {
        jdbc.update("""
                INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at) VALUES (?, ?, ?)
                ON CONFLICT (einvoice_id) DO UPDATE
                    SET transaction_id = EXCLUDED.transaction_id, last_sent_at = EXCLUDED.last_sent_at""",
                einvoiceId, transactionId, Timestamp.from(at));
    }
}
