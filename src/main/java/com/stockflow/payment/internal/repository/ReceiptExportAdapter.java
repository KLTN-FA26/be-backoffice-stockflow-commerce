package com.stockflow.payment.internal.repository;

import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.domain.ReceiptFilter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link ReceiptExportSearch} over {@code payment.payment_receipt}, its lines and {@code payment.installment}, all three
 * the payment module's own. The filter is the list's ({@code InstallmentBookAdapter.matching}) spelt in SQL: customer,
 * status, the normalised reference (BR-03, {@code reference_normalized}) and the received days, both inclusive.
 */
@Repository
class ReceiptExportAdapter implements ReceiptExportSearch {

    private final NamedParameterJdbcTemplate jdbc;

    ReceiptExportAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ExportedReceipt> receipts(ReceiptFilter filter, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        return jdbc.query("""
                SELECT r.id, r.customer_id, r.reference, r.received_on, r.amount, r.currency, r.note, r.status,
                       r.recorded_by, r.recorded_at, r.voided_by, r.voided_at, r.void_reason
                  FROM payment.payment_receipt r
                 WHERE %s
                 ORDER BY r.received_on, r.recorded_at, r.id
                 LIMIT :limit""".formatted(where(filter, params)), params, (rs, n) -> new ExportedReceipt(
                rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("reference"),
                rs.getObject("received_on", LocalDate.class), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("note"), PaymentReceiptStatus.valueOf(rs.getString("status")),
                rs.getObject("recorded_by", UUID.class), instant(rs.getObject("recorded_at", OffsetDateTime.class)),
                rs.getObject("voided_by", UUID.class), instant(rs.getObject("voided_at", OffsetDateTime.class)),
                rs.getString("void_reason")));
    }

    @Override
    public List<ExportedLine> lines(ReceiptFilter filter, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        return jdbc.query("""
                SELECT r.reference, r.received_on, r.status, i.contract_number, i.order_number, i.seq, i.name,
                       i.milestone, l.amount, r.currency
                  FROM payment.payment_receipt r
                  JOIN payment.payment_receipt_line l ON l.receipt_id = r.id
                  JOIN payment.installment i ON i.id = l.installment_id
                 WHERE %s
                 ORDER BY r.received_on, r.recorded_at, r.id, l.created_at, l.id
                 LIMIT :limit""".formatted(where(filter, params)), params, (rs, n) -> new ExportedLine(
                rs.getString("reference"), rs.getObject("received_on", LocalDate.class),
                PaymentReceiptStatus.valueOf(rs.getString("status")), rs.getString("contract_number"),
                rs.getString("order_number"), rs.getInt("seq"), rs.getString("name"), rs.getString("milestone"),
                rs.getBigDecimal("amount"), rs.getString("currency")));
    }

    /** The filter on {@code payment_receipt r}, its values bound on {@code params}; TRUE for no filter at all. */
    private static String where(ReceiptFilter filter, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        if (filter.customerId() != null) {
            clauses.add("r.customer_id = :customer");
            params.addValue("customer", filter.customerId());
        }
        if (filter.status() != null) {
            clauses.add("r.status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.normalizedReference() != null) {
            clauses.add("r.reference_normalized = :reference");
            params.addValue("reference", filter.normalizedReference());
        }
        if (filter.receivedFrom() != null) {
            clauses.add("r.received_on >= :receivedFrom");
            params.addValue("receivedFrom", filter.receivedFrom());
        }
        if (filter.receivedTo() != null) {
            clauses.add("r.received_on <= :receivedTo");
            params.addValue("receivedTo", filter.receivedTo());
        }
        return clauses.isEmpty() ? "TRUE" : String.join(" AND ", clauses);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
