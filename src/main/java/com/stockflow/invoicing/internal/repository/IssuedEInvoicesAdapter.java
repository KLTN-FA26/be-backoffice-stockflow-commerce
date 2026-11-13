package com.stockflow.invoicing.internal.repository;

import com.stockflow.invoicing.api.IssuedEInvoices;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The invoicing module's answer to {@link IssuedEInvoices}: one statement, issued invoices only. */
@Component
class IssuedEInvoicesAdapter implements IssuedEInvoices {

    private final NamedParameterJdbcTemplate jdbc;

    IssuedEInvoicesAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IssuedEInvoice> find(UUID einvoiceId) {
        if (einvoiceId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT e.id, e.customer_id, e.code, e.status FROM invoicing.einvoice e
                 WHERE e.id = :id AND e.status IN ('ISSUED', 'REPLACED')""", Map.of("id", einvoiceId),
                (rs, n) -> new IssuedEInvoice(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                        rs.getString("code"), rs.getString("status"))).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Card> card(UUID einvoiceId) {
        if (einvoiceId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT e.id, e.customer_id, e.code, e.status, e.order_id, e.order_number, e.template_code, e.symbol,
                       e.invoice_number, e.issue_date, e.currency, e.subtotal, e.tax_total, e.grand_total,
                       e.xml_file_key IS NOT NULL AS has_xml, e.pdf_file_key IS NOT NULL AS has_pdf
                  FROM invoicing.einvoice e
                 WHERE e.id = :id AND e.status IN ('ISSUED', 'REPLACED')""", Map.of("id", einvoiceId),
                (rs, n) -> new Card(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                        rs.getString("code"), rs.getString("status"), rs.getObject("order_id", UUID.class),
                        rs.getString("order_number"), rs.getString("template_code"), rs.getString("symbol"),
                        (Integer) rs.getObject("invoice_number"), rs.getObject("issue_date", java.time.LocalDate.class),
                        rs.getString("currency"), rs.getBigDecimal("subtotal"), rs.getBigDecimal("tax_total"),
                        rs.getBigDecimal("grand_total"), rs.getBoolean("has_xml"), rs.getBoolean("has_pdf")))
                .stream().findFirst();
    }
}
