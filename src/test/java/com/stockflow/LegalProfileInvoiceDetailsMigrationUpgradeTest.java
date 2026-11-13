package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261120000100 on the real upgrade path (WF-05 E1): a legal profile recorded before takes its customer's contact
 * e-mail as invoice e-mail, the change history accepts the two new fields and keeps the rows it had, and the roles
 * given the profile's own permission have their version advanced (ADR-0008) while the others do not.
 */
class LegalProfileInvoiceDetailsMigrationUpgradeTest {

    /** The baseline: every migration before 2026-11-11 is one file since the squash. */
    private static final String BEFORE = "20261111000000";
    private static final String MIGRATION = "20261120000100";

    @Test
    void profilesTakeTheContactEmailAndTheGrantsMoveTheirRoles() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID plain = UUID.randomUUID();
                UUID odd = UUID.randomUUID();
                RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                        + "VALUES (?, 'Cong ty A', ' Lien.He@Example.com', 'ACTIVE', 0, NOW())", plain);
                // An address the new check would refuse: left empty for staff rather than failing the migration.
                RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                        + "VALUES (?, 'Cong ty B', 'khong co a cong', 'ACTIVE', 0, NOW())", odd);
                for (UUID customer : new UUID[] {plain, odd}) {
                    RawSql.update(db, "INSERT INTO customer.customer_legal_profile (id, customer_id, "
                            + "registered_address, representative_name, representative_title, version, created_at) "
                            + "VALUES (?, ?, '1 Le Loi', 'Nguyen Van A', 'Giam doc', 0, NOW())", UUID.randomUUID(),
                            customer);
                }
                RawSql.update(db, "INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, "
                        + "new_value, changed_at) VALUES (?, ?, 'REPRESENTATIVE', NULL, 'Nguyen Van A', NOW())",
                        UUID.randomUUID(), plain);
                long accountant = version(db, "ACCOUNTANT");
                long sales = version(db, "SALES_STAFF");
                long customerRole = version(db, "CUSTOMER");
                long warehouse = version(db, "WAREHOUSE_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.text(db, "SELECT invoice_email FROM customer.customer_legal_profile "
                        + "WHERE customer_id = ?", plain)).isEqualTo("lien.he@example.com");
                assertThat(RawSql.text(db, "SELECT invoice_email FROM customer.customer_legal_profile "
                        + "WHERE customer_id = ?", odd)).isNull();
                assertThat(RawSql.number(db, "SELECT count(*) FROM customer.customer_change_history "
                        + "WHERE customer_id = ?", plain)).as("history kept").isEqualTo(1);
                RawSql.update(db, "INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, "
                        + "new_value, changed_at) VALUES (?, ?, 'INVOICE_EMAIL', NULL, 'a@b.vn', NOW())",
                        UUID.randomUUID(), plain);
                RawSql.update(db, "INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, "
                        + "new_value, changed_at) VALUES (?, ?, 'BANK_ACCOUNT', NULL, '0123 | VCB | A', NOW())",
                        UUID.randomUUID(), plain);
                RawSql.refused(db, "ck_customer_legal_profile_bank", "UPDATE customer.customer_legal_profile "
                        + "SET bank_account_number = '0123456789' WHERE customer_id = ?", plain);

                assertThat(RawSql.texts(db, "SELECT r.code || ':' || p.action FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p "
                        + "ON p.id = rp.permission_id WHERE p.resource = 'customer-legal-profiles' ORDER BY 1"))
                        .containsExactly("ACCOUNTANT:READ", "ACCOUNTANT:UPDATE", "SALES_MANAGER:READ",
                                "SALES_MANAGER:UPDATE", "SALES_STAFF:READ", "SALES_STAFF:UPDATE", "SYSTEM_ADMIN:READ",
                                "SYSTEM_ADMIN:UPDATE");
                assertThat(version(db, "ACCOUNTANT")).as("granted: version advanced").isEqualTo(accountant + 1);
                assertThat(version(db, "SALES_STAFF")).isEqualTo(sales + 1);
                assertThat(version(db, "CUSTOMER")).as("not granted: untouched").isEqualTo(customerRole);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(warehouse);
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
