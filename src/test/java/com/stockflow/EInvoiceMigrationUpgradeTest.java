package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261120000200 and V20261120000300 on the real upgrade path (WF-05 E2): the invoicing schema arrives with the
 * sandbox as the provider in use and a series for this year and the next, no VAT period is in force, nothing of
 * ordering.tax_rate moved, and the roles granted the new permissions - and only they - have their version advanced
 * (ADR-0008), the administrator still holding every permission.
 */
class EInvoiceMigrationUpgradeTest {

    private static final String BEFORE = "20261120000100";
    private static final String MIGRATION = "20261120000300";

    @Test
    void theInvoicingSchemaArrivesWithItsSeedAndGrants() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                long accountant = version(db, "ACCOUNTANT");
                long salesManager = version(db, "SALES_MANAGER");
                long admin = version(db, "SYSTEM_ADMIN");
                long salesStaff = version(db, "SALES_STAFF");
                long customer = version(db, "CUSTOMER");
                String rates = RawSql.text(db, "SELECT string_agg(tax_class || '=' || rate, ',' ORDER BY tax_class) "
                        + "FROM ordering.tax_rate");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly("20261120000200", MIGRATION);

                assertThat(RawSql.text(db, "SELECT string_agg(tax_class || '=' || rate, ',' ORDER BY tax_class) "
                        + "FROM ordering.tax_rate")).as("the rates quotes and orders read are untouched").isEqualTo(rates);
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.tax_rate_period")).isZero();

                assertThat(RawSql.text(db, "SELECT provider || '/' || environment || '/' || auto_issue "
                        + "FROM invoicing.einvoice_provider_config WHERE active")).isEqualTo("SANDBOX/SANDBOX/false");
                assertThat(RawSql.texts(db, "SELECT template_code || symbol FROM invoicing.einvoice_series "
                        + "WHERE active ORDER BY year")).containsExactly("1C26TSB", "1C27TSB");
                assertThat(RawSql.number(db, "SELECT count(*) FROM invoicing.einvoice")).isZero();
                assertThat(RawSql.texts(db, "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'invoicing' ORDER BY 1")).containsExactly("einvoice",
                        "einvoice_error_notice", "einvoice_line", "einvoice_provider_call", "einvoice_provider_config",
                        "einvoice_series", "sandbox_issuance", "sandbox_number");

                assertThat(RawSql.texts(db, "SELECT r.code || ':' || p.code FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p "
                        + "ON p.id = rp.permission_id WHERE p.resource IN ('einvoices', 'einvoice-settings') "
                        + "AND r.code <> 'SYSTEM_ADMIN' ORDER BY 1")).containsExactly(
                        "ACCOUNTANT:einvoice-settings:READ", "ACCOUNTANT:einvoices:APPROVE",
                        "ACCOUNTANT:einvoices:CREATE", "ACCOUNTANT:einvoices:EXPORT", "ACCOUNTANT:einvoices:READ",
                        "ACCOUNTANT:einvoices:UPDATE", "ACCOUNTANT:einvoices:VIEW_PAGE", "SALES_MANAGER:einvoices:READ",
                        "SALES_MANAGER:einvoices:VIEW_PAGE");
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission")).as("the administrator holds "
                        + "every permission").isEqualTo(RawSql.number(db, "SELECT count(*) FROM "
                        + "identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id "
                        + "WHERE r.code = 'SYSTEM_ADMIN'"));
                assertThat(version(db, "ACCOUNTANT")).as("granted: version advanced").isEqualTo(accountant + 1);
                assertThat(version(db, "SALES_MANAGER")).isEqualTo(salesManager + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(admin + 1);
                assertThat(version(db, "SALES_STAFF")).as("not granted: untouched").isEqualTo(salesStaff);
                assertThat(version(db, "CUSTOMER")).isEqualTo(customer);
            }
        }
    }

    private static long version(Connection db, String role) throws Exception {
        return RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = ?", role);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
