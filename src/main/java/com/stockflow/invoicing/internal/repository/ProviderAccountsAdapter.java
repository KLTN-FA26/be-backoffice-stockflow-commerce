package com.stockflow.invoicing.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.invoicing.internal.domain.InvoiceSeries;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProviderAccounts} over {@code invoicing.einvoice_provider_config} and {@code invoicing.einvoice_series}. Plain
 * SQL, as {@link InvoiceSettingsAdapter}: two rows of configuration. The sealed secret is read here and only here; it
 * is opened by whoever calls the provider and is never part of an answer to a person.
 */
@Repository
class ProviderAccountsAdapter implements ProviderAccounts {

    private static final String SELECT = """
            SELECT id, provider, environment, username, secret_sealed, active, auto_issue, version
              FROM invoicing.einvoice_provider_config""";

    private static final RowMapper<Account> ROW = (row, n) -> new Account(row.getObject("id", UUID.class),
            row.getString("provider"), row.getString("environment"), row.getString("username"),
            row.getString("secret_sealed"), row.getBoolean("active"), row.getBoolean("auto_issue"),
            row.getLong("version"));

    private final JdbcTemplate jdbc;

    ProviderAccountsAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Account> all() {
        return jdbc.query(SELECT + " ORDER BY active DESC, provider", ROW);
    }

    @Override
    public Optional<Account> find(String provider) {
        return jdbc.query(SELECT + " WHERE provider = ?", ROW, provider).stream().findFirst();
    }

    @Override
    public void store(String provider, String environment, String username, String secretSealed,
                      long expectedVersion) {
        int changed = jdbc.update("""
                UPDATE invoicing.einvoice_provider_config
                   SET environment = ?, username = ?, secret_sealed = COALESCE(?, secret_sealed),
                       version = version + 1, last_modified_at = NOW()
                 WHERE provider = ? AND version = ?""", environment, username, secretSealed, provider, expectedVersion);
        requireChanged(changed);
    }

    @Override
    public void activate(String provider, long expectedVersion) {
        // The one in use first: uk_einvoice_provider_config_active allows a single row in use at any statement's end.
        jdbc.update("""
                UPDATE invoicing.einvoice_provider_config
                   SET active = FALSE, version = version + 1, last_modified_at = NOW()
                 WHERE active AND provider <> ?""", provider);
        int changed = jdbc.update("""
                UPDATE invoicing.einvoice_provider_config
                   SET active = TRUE, version = version + 1, last_modified_at = NOW()
                 WHERE provider = ? AND version = ?""", provider, expectedVersion);
        requireChanged(changed);
    }

    @Override
    public void useSeries(InvoiceSeries series, String providerTemplateCode) {
        // uk_einvoice_series_in_use: one series in use per provider and year, so the year's other series step back first.
        jdbc.update("""
                UPDATE invoicing.einvoice_series
                   SET active = FALSE, version = version + 1, last_modified_at = NOW()
                 WHERE provider = ? AND year = ? AND active AND (template_code <> ? OR symbol <> ?)""",
                series.provider(), series.year(), series.templateCode(), series.symbol());
        jdbc.update("""
                INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active,
                                                       provider_template_code)
                VALUES (?, ?, ?, ?, ?, TRUE, ?)
                ON CONFLICT ON CONSTRAINT uk_einvoice_series
                DO UPDATE SET active = TRUE, provider_template_code = EXCLUDED.provider_template_code,
                              version = invoicing.einvoice_series.version + 1, last_modified_at = NOW()""",
                Identifiers.newId(), series.provider(), series.templateCode(), series.symbol(), series.year(),
                providerTemplateCode);
    }

    @Override
    public Optional<String> providerTemplateCode(String provider, String templateCode, String symbol) {
        return jdbc.query("""
                SELECT provider_template_code FROM invoicing.einvoice_series
                 WHERE provider = ? AND symbol = ? AND provider_template_code IS NOT NULL
                   AND (CAST(? AS VARCHAR) IS NULL OR template_code = ?)
                 ORDER BY active DESC, template_code LIMIT 1""",
                (row, n) -> row.getString(1), provider, symbol, templateCode, templateCode).stream().findFirst();
    }

    private static void requireChanged(int changed) {
        if (changed == 0) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "The e-invoice settings changed since they were read");
        }
    }
}
