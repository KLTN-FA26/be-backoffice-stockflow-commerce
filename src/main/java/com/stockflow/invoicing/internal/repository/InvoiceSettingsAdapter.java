package com.stockflow.invoicing.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.invoicing.internal.domain.InvoiceSeries;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link InvoiceSettings} over {@code invoicing.einvoice_provider_config} and {@code invoicing.einvoice_series}. Plain
 * SQL: one row of configuration and a handful of series. The sealed secret is never selected - only whether there is
 * one.
 */
@Repository
class InvoiceSettingsAdapter implements InvoiceSettings {

    private static final String PROVIDER = """
            SELECT provider, environment, endpoint, username, secret_sealed IS NOT NULL AS secret_set, auto_issue,
                   version
              FROM invoicing.einvoice_provider_config WHERE active""";

    private static final RowMapper<Provider> PROVIDER_ROW = (row, n) -> new Provider(row.getString("provider"),
            row.getString("environment"), row.getString("endpoint"), row.getString("username"),
            row.getBoolean("secret_set"), row.getBoolean("auto_issue"), row.getLong("version"));

    private static final RowMapper<InvoiceSeries> SERIES_ROW = (row, n) -> new InvoiceSeries(row.getString("provider"),
            row.getString("template_code"), row.getString("symbol"), row.getInt("year"));

    private final JdbcTemplate jdbc;

    InvoiceSettingsAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Provider active() {
        List<Provider> found = jdbc.query(PROVIDER, PROVIDER_ROW);
        if (found.isEmpty()) {
            // The sandbox is seeded as the provider in use; only a hand-edited table gets here.
            throw new BusinessException(ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE, "No e-invoice provider is in use");
        }
        return found.getFirst();
    }

    @Override
    public Provider changeAutoIssue(boolean autoIssue, long expectedVersion) {
        int changed = jdbc.update("""
                UPDATE invoicing.einvoice_provider_config
                   SET auto_issue = ?, version = version + 1, last_modified_at = NOW()
                 WHERE active AND version = ?""", autoIssue, expectedVersion);
        if (changed == 0) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "The e-invoice settings changed since they were read");
        }
        return active();
    }

    @Override
    public Optional<InvoiceSeries> seriesInUse(String provider, int year) {
        // uk_einvoice_series_in_use: at most one.
        return jdbc.query("""
                SELECT provider, template_code, symbol, year FROM invoicing.einvoice_series
                 WHERE provider = ? AND year = ? AND active""", SERIES_ROW, provider, year).stream().findFirst();
    }

    @Override
    public List<InvoiceSeries> seriesOfActiveProvider() {
        return jdbc.query("""
                SELECT s.provider, s.template_code, s.symbol, s.year
                  FROM invoicing.einvoice_series s
                  JOIN invoicing.einvoice_provider_config c ON c.provider = s.provider AND c.active
                 WHERE s.active ORDER BY s.year DESC, s.symbol""", SERIES_ROW);
    }
}
