package com.stockflow.invoicing.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * The sandbox provider's own books ({@code invoicing.sandbox_issuance}, {@code invoicing.sandbox_number}): what a real
 * provider keeps on its side - every transaction it took in, and the last number it gave under each symbol. Kept in the
 * database, not in memory, so a transaction survives a restart exactly as it would at a real provider, which is the
 * property the reconciliation is built on.
 */
@Repository
public class SandboxLedger {

    /**
     * A transaction the sandbox took in.
     *
     * @param issued  whether it ends issued (numbered) or refused by the simulated tax authority
     * @param readyAt when its simulated processing is done; before it the transaction is still pending
     */
    public record Issuance(String transactionId, boolean issued, String templateCode, String symbol,
                           Integer invoiceNumber, String taxAuthorityCode, String lookupCode, String rejectionCode,
                           String rejectionMessage, String xml, byte[] pdf, String requestHash, Instant receivedAt,
                           Instant readyAt) {
    }

    private final JdbcTemplate jdbc;

    SandboxLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Issuance> find(String transactionId) {
        return jdbc.query("""
                SELECT transaction_id, state, template_code, symbol, invoice_number, tax_authority_code, lookup_code,
                       rejection_code, rejection_message, xml_document, pdf_document, request_hash, received_at, ready_at
                  FROM invoicing.sandbox_issuance WHERE transaction_id = ?""",
                (row, n) -> new Issuance(row.getString("transaction_id"), "ISSUED".equals(row.getString("state")),
                        row.getString("template_code"), row.getString("symbol"),
                        row.getObject("invoice_number", Integer.class), row.getString("tax_authority_code"),
                        row.getString("lookup_code"), row.getString("rejection_code"),
                        row.getString("rejection_message"), row.getString("xml_document"),
                        row.getBytes("pdf_document"), row.getString("request_hash"),
                        row.getTimestamp("received_at").toInstant(), row.getTimestamp("ready_at").toInstant()),
                transactionId).stream().findFirst();
    }

    /**
     * Takes a transaction in; false when one with that id is already there (the primary key decides between two
     * submissions arriving together).
     */
    public boolean insert(Issuance issuance) {
        return jdbc.update("""
                INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, invoice_number,
                    tax_authority_code, lookup_code, rejection_code, rejection_message, xml_document, pdf_document,
                    request_hash, received_at, ready_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transaction_id) DO NOTHING""", issuance.transactionId(),
                issuance.issued() ? "ISSUED" : "REJECTED", issuance.templateCode(), issuance.symbol(),
                issuance.invoiceNumber(), issuance.taxAuthorityCode(), issuance.lookupCode(),
                issuance.rejectionCode(), issuance.rejectionMessage(), issuance.xml(), issuance.pdf(),
                issuance.requestHash(), Timestamp.from(issuance.receivedAt()), Timestamp.from(issuance.readyAt())) == 1;
    }

    /**
     * The next number under a symbol: from 1, one after the other, the row locked until the caller's transaction ends
     * so two invoices never share one (NĐ 254 Phụ lục điểm 2). A symbol names its year, so a new year's symbol starts
     * at 1 again. Empty when the symbol's 8 digits are used up.
     */
    public Optional<Integer> nextNumber(String templateCode, String symbol) {
        return jdbc.query("""
                INSERT INTO invoicing.sandbox_number (template_code, symbol, last_number) VALUES (?, ?, 1)
                ON CONFLICT (template_code, symbol)
                DO UPDATE SET last_number = invoicing.sandbox_number.last_number + 1
                    WHERE invoicing.sandbox_number.last_number < 99999999
                RETURNING last_number""", (row, n) -> row.getInt(1), templateCode, symbol).stream().findFirst();
    }
}
