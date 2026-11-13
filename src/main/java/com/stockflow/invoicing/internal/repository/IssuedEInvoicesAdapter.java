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
}
