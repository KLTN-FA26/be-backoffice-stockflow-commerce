package com.stockflow.invoicing.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link ProviderCallLog} over {@code invoicing.einvoice_provider_call}: insert and read, nothing else. */
@Repository
class ProviderCallLogAdapter implements ProviderCallLog {

    private final JdbcTemplate jdbc;

    ProviderCallLogAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Call call) {
        jdbc.update("""
                INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation,
                    outcome, http_status, error_code, request_hash, response_hash, called_at, duration_ms,
                    provider_reference)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Identifiers.newId(), call.einvoiceId(),
                call.provider(), call.transactionId(), call.operation().name(), call.outcome().name(),
                call.httpStatus(), call.errorCode(), call.requestHash(), call.responseHash(),
                Timestamp.from(call.calledAt()), call.durationMs(), call.providerReference());
    }

    @Override
    public Optional<String> lastProviderReference(String provider, String transactionId) {
        return jdbc.query("""
                SELECT provider_reference FROM invoicing.einvoice_provider_call
                 WHERE provider = ? AND transaction_id = ? AND operation = 'SUBMIT' AND provider_reference IS NOT NULL
                 ORDER BY called_at DESC, id DESC LIMIT 1""", (row, n) -> row.getString(1), provider, transactionId)
                .stream().findFirst();
    }

    @Override
    public boolean providerHeld(UUID einvoiceId, String transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice_provider_call
                                WHERE einvoice_id = ? AND transaction_id = ?
                                  AND ((operation = 'SUBMIT' AND outcome = 'DUPLICATE')
                                       OR (operation = 'QUERY' AND outcome = 'ISSUED')))""", Boolean.class,
                einvoiceId, transactionId));
    }

    @Override
    public List<Call> of(UUID einvoiceId) {
        return jdbc.query("""
                SELECT einvoice_id, provider, transaction_id, operation, outcome, http_status, error_code,
                       request_hash, response_hash, called_at, duration_ms, provider_reference
                  FROM invoicing.einvoice_provider_call WHERE einvoice_id = ? ORDER BY called_at, id""",
                ProviderCallLogAdapter::call, einvoiceId);
    }

    @Override
    public Optional<Call> lastQuery(UUID einvoiceId, String transactionId) {
        // An invoice stuck for a day has thousands of look-ups on record: the newest one only.
        return jdbc.query("""
                SELECT einvoice_id, provider, transaction_id, operation, outcome, http_status, error_code,
                       request_hash, response_hash, called_at, duration_ms, provider_reference
                  FROM invoicing.einvoice_provider_call
                 WHERE einvoice_id = ? AND transaction_id = ? AND operation = 'QUERY'
                 ORDER BY called_at DESC, id DESC LIMIT 1""", ProviderCallLogAdapter::call, einvoiceId,
                transactionId).stream().findFirst();
    }

    private static Call call(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new Call(row.getObject("einvoice_id", UUID.class), row.getString("provider"),
                row.getString("transaction_id"), Operation.valueOf(row.getString("operation")),
                Outcome.valueOf(row.getString("outcome")), row.getObject("http_status", Integer.class),
                row.getString("error_code"), row.getString("request_hash"), row.getString("response_hash"),
                row.getTimestamp("called_at").toInstant(), row.getLong("duration_ms"),
                row.getString("provider_reference"));
    }
}
