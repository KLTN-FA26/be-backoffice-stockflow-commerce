package com.stockflow.procurement;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261101050000 on the real upgrade path: a database migrated to the migration before it, with invoices written
 * under the old status names (RECEIVED, MATCHED) and PROCUREMENT_STAFF holding the grants the seed gave it, then
 * upgraded. Proves the statuses are renamed as kltn-docs 04 names them, the column takes the longer name, and
 * procurement staff keep only VIEW_PAGE and READ on invoices - the accountant keeps everything - with the role
 * version moved (ADR-0008) and no other role touched.
 */
class SupplierInvoiceMigrationUpgradeTest {

    private static final String BEFORE = "20261101040100";
    private static final String MIGRATION = "20261101050000";
    private static final String INVOICES = "procurement-supplier-invoices";

    @Test
    void upgradeRenamesTheStatusesAndLeavesProcurementStaffToRead() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                assertThat(invoiceGrants(db, "PROCUREMENT_STAFF")).containsExactlyInAnyOrder(
                        INVOICES + ":VIEW_PAGE", INVOICES + ":READ", INVOICES + ":CREATE", INVOICES + ":UPDATE");
                List<String> accountantBefore = invoiceGrants(db, "ACCOUNTANT");
                assertThat(accountantBefore).contains(INVOICES + ":CREATE", INVOICES + ":UPDATE", INVOICES + ":APPROVE");

                UUID supplier = UUID.randomUUID();
                UUID entered = UUID.randomUUID();
                UUID matched = UUID.randomUUID();
                UUID voided = UUID.randomUUID();
                RawSql.update(db, "INSERT INTO procurement.suppliers (id, code, name, status, currency, version, created_at) "
                        + "VALUES (?, 'MIG-SUP', 'Migrated supplier', 'ACTIVE', 'VND', 0, NOW())", supplier);
                invoice(db, entered, supplier, "HD-1", "RECEIVED");
                invoice(db, matched, supplier, "HD-2", "MATCHED");
                invoice(db, voided, supplier, "HD-3", "VOID");
                Map<String, Long> versionsBefore = versions(db);
                List<String> warehouseBefore = RawSql.texts(db, "SELECT p.code FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id "
                        + "WHERE r.code = 'WAREHOUSE_MANAGER'");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).contains(MIGRATION);

                assertThat(RawSql.text(db, "SELECT status FROM procurement.supplier_invoices WHERE id = ?", entered))
                        .isEqualTo("DRAFT");
                assertThat(RawSql.text(db, "SELECT status FROM procurement.supplier_invoices WHERE id = ?", matched))
                        .isEqualTo("MATCHED");
                // A legacy VOID row becomes CANCELLED and says who (the disabled legacy stand-in) and why.
                assertThat(RawSql.text(db, "SELECT status FROM procurement.supplier_invoices WHERE id = ?", voided))
                        .isEqualTo("CANCELLED");
                assertThat(RawSql.text(db, "SELECT u.username FROM procurement.supplier_invoices i "
                        + "JOIN identity.app_user u ON u.id = i.voided_by WHERE i.id = ?", voided))
                        .isEqualTo("system.legacy-import");
                // A status the docs name and the old column could not hold.
                RawSql.update(db, "UPDATE procurement.supplier_invoices SET status = 'APPROVED_FOR_PAYMENT' WHERE id = ?",
                        matched);
                assertThat(RawSql.text(db, "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'procurement' AND table_name = 'supplier_invoices' "
                        + "AND column_name = 'status'")).contains("DRAFT");

                assertThat(invoiceGrants(db, "PROCUREMENT_STAFF"))
                        .containsExactlyInAnyOrder(INVOICES + ":VIEW_PAGE", INVOICES + ":READ");
                assertThat(invoiceGrants(db, "ACCOUNTANT")).containsExactlyInAnyOrderElementsOf(accountantBefore);
                assertThat(RawSql.texts(db, "SELECT p.code FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id "
                        + "WHERE r.code = 'WAREHOUSE_MANAGER'")).containsExactlyInAnyOrderElementsOf(warehouseBefore);

                Map<String, Long> versionsAfter = versions(db);
                for (String role : versionsBefore.keySet()) {
                    long expected = versionsBefore.get(role) + (role.equals("PROCUREMENT_STAFF") ? 1 : 0);
                    assertThat(versionsAfter.get(role)).as("version of " + role).isEqualTo(expected);
                }
            }
        }
    }

    private static void invoice(Connection db, UUID id, UUID supplier, String number, String status) throws Exception {
        RawSql.update(db, "INSERT INTO procurement.supplier_invoices (id, invoice_number, supplier_id, status, currency, "
                + "invoice_date, subtotal, tax_total, total_amount, version, created_at) "
                + "VALUES (?, ?, ?, ?, 'VND', CURRENT_DATE, 100, 0, 100, 0, NOW())", id, number, supplier, status);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    private static List<String> invoiceGrants(Connection db, String role) throws Exception {
        return RawSql.texts(db, "SELECT p.code FROM identity.role_permission rp "
                + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id "
                + "WHERE r.code = ? AND p.resource = '" + INVOICES + "'", role);
    }

    private static Map<String, Long> versions(Connection db) throws Exception {
        return RawSql.numbersByText(db, "SELECT code, version FROM identity.app_role");
    }
}
