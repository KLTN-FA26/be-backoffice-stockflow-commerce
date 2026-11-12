package com.stockflow.invoicing.internal.repository;

import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link EInvoiceQueries} over {@code invoicing.einvoice}: the accountant's list and the period register, one statement
 * each. Every value a caller gives is bound, never concatenated; the only text built here is the fixed column
 * predicates.
 */
@Repository
class EInvoiceQueriesAdapter implements EInvoiceQueries {

    private static final String LIST_COLUMNS = """
            id, code, type, status, adjusted, order_id, order_number, shipment_id, shipment_code, customer_id,
            buyer_name, buyer_tax_code, issue_date, issue_due_at, template_code, symbol, invoice_number, subtotal,
            tax_total, grand_total, warnings, rejection_code, issued_at""";

    private static final RowMapper<Listing> LISTING = (row, n) -> new Listing(row.getObject("id", UUID.class),
            row.getString("code"), EInvoiceType.valueOf(row.getString("type")),
            EInvoiceStatus.valueOf(row.getString("status")), row.getBoolean("adjusted"),
            row.getObject("order_id", UUID.class), row.getString("order_number"),
            row.getObject("shipment_id", UUID.class), row.getString("shipment_code"),
            row.getObject("customer_id", UUID.class), row.getString("buyer_name"), row.getString("buyer_tax_code"),
            row.getObject("issue_date", LocalDate.class), row.getTimestamp("issue_due_at").toInstant(),
            row.getString("template_code"), row.getString("symbol"), row.getObject("invoice_number", Integer.class),
            row.getBigDecimal("subtotal"), row.getBigDecimal("tax_total"), row.getBigDecimal("grand_total"),
            row.getString("warnings"), row.getString("rejection_code"),
            row.getTimestamp("issued_at") == null ? null : row.getTimestamp("issued_at").toInstant());

    private final JdbcTemplate jdbc;

    EInvoiceQueriesAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Listing> list(Filter filter, int offset, int limit) {
        List<Object> arguments = new ArrayList<>();
        String where = where(filter, arguments);
        arguments.add(limit);
        arguments.add(offset);
        // What still has to be issued comes first, the nearest deadline first: that is the accountant's work.
        // The text block's common indentation is stripped, so the space before ORDER BY is spelled out.
        return jdbc.query("SELECT " + LIST_COLUMNS + " FROM invoicing.einvoice" + where + " " + """
                 ORDER BY CASE WHEN status IN ('DRAFT', 'REJECTED', 'SUBMITTING') THEN 0 ELSE 1 END,
                          CASE WHEN status IN ('DRAFT', 'REJECTED', 'SUBMITTING') THEN issue_due_at END,
                          created_at DESC, id
                 LIMIT ? OFFSET ?""", LISTING, arguments.toArray());
    }

    @Override
    public long count(Filter filter) {
        List<Object> arguments = new ArrayList<>();
        Long count = jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice" + where(filter, arguments),
                Long.class, arguments.toArray());
        return count == null ? 0 : count;
    }

    private static String where(Filter filter, List<Object> arguments) {
        List<String> predicates = new ArrayList<>();
        if (filter.status() != null) {
            predicates.add("status = ?");
            arguments.add(filter.status().name());
        }
        if (filter.type() != null) {
            predicates.add("type = ?");
            arguments.add(filter.type().name());
        }
        if (filter.orderId() != null) {
            predicates.add("order_id = ?");
            arguments.add(filter.orderId());
        }
        if (filter.customerId() != null) {
            predicates.add("customer_id = ?");
            arguments.add(filter.customerId());
        }
        if (filter.issuedFrom() != null) {
            predicates.add("issue_date >= ?");
            arguments.add(java.sql.Date.valueOf(filter.issuedFrom()));
        }
        if (filter.issuedTo() != null) {
            predicates.add("issue_date <= ?");
            arguments.add(java.sql.Date.valueOf(filter.issuedTo()));
        }
        if (filter.dueBefore() != null) {
            predicates.add("status IN ('DRAFT', 'REJECTED', 'SUBMITTING') AND issue_due_at < ?");
            arguments.add(Timestamp.from(filter.dueBefore()));
        }
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }

    @Override
    public List<RegisterEntry> register(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT i.template_code, i.symbol, i.invoice_number, i.issue_date, i.type, i.status, i.adjusted,
                       i.buyer_name, i.buyer_tax_code, i.buyer_address, i.order_number, i.subtotal, i.tax_total,
                       i.grand_total, i.tax_authority_code, i.lookup_code,
                       o.template_code || o.symbol || ' ' || o.invoice_number AS original_reference
                  FROM invoicing.einvoice i
                  LEFT JOIN invoicing.einvoice o ON o.id = i.original_einvoice_id
                 WHERE i.status IN ('ISSUED', 'REPLACED') AND i.issue_date BETWEEN ? AND ?
                 ORDER BY i.template_code, i.symbol, i.invoice_number""",
                (row, n) -> new RegisterEntry(row.getString("template_code"), row.getString("symbol"),
                        row.getInt("invoice_number"), row.getObject("issue_date", LocalDate.class),
                        EInvoiceType.valueOf(row.getString("type")), EInvoiceStatus.valueOf(row.getString("status")),
                        row.getBoolean("adjusted"), row.getString("buyer_name"), row.getString("buyer_tax_code"),
                        row.getString("buyer_address"), row.getString("order_number"), row.getBigDecimal("subtotal"),
                        row.getBigDecimal("tax_total"), row.getBigDecimal("grand_total"),
                        row.getString("tax_authority_code"), row.getString("lookup_code"),
                        row.getString("original_reference")),
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
    }
}
