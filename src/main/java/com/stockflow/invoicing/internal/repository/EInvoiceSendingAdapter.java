package com.stockflow.invoicing.internal.repository;

import com.stockflow.invoicing.internal.domain.EInvoiceSending;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link EInvoiceSending} over {@code invoicing.einvoice_sending}, one row per invoice. The lease is timed by the
 * database's {@code now()}, one clock for every instance; a claim is one {@code INSERT ... ON CONFLICT DO UPDATE ...
 * WHERE}, which Postgres runs atomically against a concurrent claim of the same invoice.
 */
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

    @Override
    public boolean claim(UUID einvoiceId, UUID holder, Duration lease) {
        return jdbc.update("""
                INSERT INTO invoicing.einvoice_sending (einvoice_id, holder, held_until)
                VALUES (?, ?, now() + make_interval(secs => ?))
                ON CONFLICT (einvoice_id) DO UPDATE SET holder = EXCLUDED.holder, held_until = EXCLUDED.held_until
                 WHERE einvoice_sending.holder IS NULL OR einvoice_sending.held_until <= now()""",
                einvoiceId, holder, seconds(lease)) == 1;
    }

    @Override
    public boolean renew(UUID einvoiceId, UUID holder, Duration lease) {
        return jdbc.update("""
                UPDATE invoicing.einvoice_sending SET held_until = now() + make_interval(secs => ?)
                 WHERE einvoice_id = ? AND holder = ?""", seconds(lease), einvoiceId, holder) == 1;
    }

    @Override
    public void release(UUID einvoiceId, UUID holder) {
        jdbc.update("""
                UPDATE invoicing.einvoice_sending SET holder = NULL, held_until = now()
                 WHERE einvoice_id = ? AND holder = ?""", einvoiceId, holder);
    }

    private static double seconds(Duration lease) {
        return lease.toMillis() / 1000.0;
    }
}
