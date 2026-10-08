package com.stockflow.procurement.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockflow.common.domain.CommercialTerms;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;

/** Unlike a fresh-context test, exercises the exact develop-to-feature upgrade path. */
class ProcurementMigrationUpgradeTest {
    @Test
    void publishedRemoteBaselineRequiresTargetedReconciliationBeforeNormalUpgrade(
            @TempDir Path baseline) throws Exception {
        var loader = getClass().getClassLoader();
        try (var manifest = loader.getResourceAsStream("db/upgrade/pr36-9fbb90f-manifest.txt")) {
            for (String line :
                    new String(manifest.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (line.startsWith("#") || line.isBlank()) continue;
                String[] entry = line.split(" ");
                try (var resource = loader.getResourceAsStream("db/migration/" + entry[0])) {
                    byte[] sql = resource.readAllBytes();
                    // Check normalized line endings: Git's checkout EOL must not alter the fixture.
                    sql =
                            new String(sql, StandardCharsets.UTF_8)
                                    .replace("\r\n", "\n")
                                    .getBytes(StandardCharsets.UTF_8);
                    assertThat(
                                    HexFormat.of()
                                            .formatHex(
                                                    MessageDigest.getInstance("SHA-256")
                                                            .digest(sql)))
                            .as("Published migration %s must keep its SQL", entry[0])
                            .isEqualTo(entry[1]);
                    Files.write(baseline.resolve(entry[0]), sql);
                }
            }
        }
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("filesystem:" + baseline)
                    .load()
                    .migrate();
            var normal =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .load();
            assertThatThrownBy(normal::migrate)
                    .isInstanceOf(FlywayValidateException.class)
                    .hasMessageContaining("20260929000100")
                    .hasMessageContaining("20260929000200");
            // The runbook's one-off, explicitly scoped recovery: only backfill prerequisites.
            var prerequisite =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .outOfOrder(true)
                            .target("20260930000400")
                            .load();
            assertThat(prerequisite.migrate().migrations)
                    .extracting(m -> m.version)
                    .containsExactly("20260929000100", "20260929000200");
            assertThat(normal.migrate().migrations)
                    .extracting(m -> m.version)
                    .contains("20260930000500");
            normal.validate();
        }
    }

    @Test
    void upgradeExistingFeatureBackfillsAttemptNumbersAndAdminCacheVersion() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target("20260930000400")
                    .load()
                    .migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var sql = connection.createStatement()) {
                long adminVersion;
                try (var version =
                        sql.executeQuery(
                                "select version from identity.app_role where"
                                    + " code='SYSTEM_ADMIN'")) {
                    version.next();
                    adminVersion = version.getLong(1);
                }
                sql.executeUpdate(
                        """
insert into notification.delivery_log
    (id,channel,recipient,status,created_at,operation_reference,delivery_generation)
values
    ('00000000-0000-0000-0000-000000000001','EMAIL','s@example.com','FAILED',now(),'purchase-order:legacy',0),
    ('00000000-0000-0000-0000-000000000002','EMAIL','s@example.com','SENT',now(),'purchase-order:legacy',0),
    ('00000000-0000-0000-0000-000000000003','EMAIL','s@example.com','FAILED',now(),'purchase-order:legacy',1),
    ('00000000-0000-0000-0000-000000000004','EMAIL','s@example.com','SENT',now(),'purchase-order:legacy:cancellation',0)
""");
                var upgrade =
                        Flyway.configure()
                                .dataSource(
                                        postgres.getJdbcUrl(),
                                        postgres.getUsername(),
                                        postgres.getPassword())
                                .load();
                assertThat(upgrade.migrate().migrations.stream().map(m -> m.version).toList())
                        .contains("20260930000500");
                upgrade.validate();
                try (var rows =
                        sql.executeQuery(
                                "select attempt_number from notification.delivery_log order by"
                                    + " id")) {
                    for (int expected : new int[] {1, 2, 1, 1}) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(expected);
                    }
                    assertThat(rows.next()).isFalse();
                }
                try (var version =
                        sql.executeQuery(
                                "select version from identity.app_role where"
                                    + " code='SYSTEM_ADMIN'")) {
                    version.next();
                    assertThat(version.getLong(1)).isEqualTo(adminVersion + 1);
                }
                try (var missing =
                        sql.executeQuery(
                                """
select count(*) from identity.permission p where not exists (
    select 1 from identity.role_permission rp join identity.app_role r on r.id=rp.role_id
    where r.code='SYSTEM_ADMIN' and rp.permission_id=p.id)
""")) {
                    missing.next();
                    assertThat(missing.getLong(1)).isZero();
                }
                try (var staff =
                        sql.executeQuery(
                                """
select p.action from identity.role_permission rp
join identity.app_role r on r.id=rp.role_id
join identity.permission p on p.id=rp.permission_id
where r.code='PROCUREMENT_STAFF' and p.resource='procurement-suppliers'
order by p.action
""")) {
                    var actions = new ArrayList<String>();
                    while (staff.next()) actions.add(staff.getString(1));
                    assertThat(actions)
                            .containsExactly("CREATE", "EXPORT", "READ", "UPDATE", "VIEW_PAGE");
                }
            }
        }
    }

    @Test
    void upgradeDevelopWithHistoricalSentOrdersWithoutInventingSendTime() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target("20260929000200")
                    .load()
                    .migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate(
                        """
insert into procurement.supplier(id,code,name,email,status,tax_code,created_at)
values ('00000000-0000-0000-0000-000000000001','LEGACY','Legacy','old@example.com','ACTIVE','TAX-001',now())
""");
                sql.executeUpdate(
                        """
insert into procurement.purchase_order(id,po_number,supplier_id,status,created_at)
values ('00000000-0000-0000-0000-000000000002','PO-LEGACY',
'00000000-0000-0000-0000-000000000001','SENT',now())
""");
                long adminVersion;
                long staffVersion;
                try (var versions =
                        sql.executeQuery(
                                "select version from identity.app_role where"
                                    + " code='ECOMMERCE_ADMIN'")) {
                    versions.next();
                    adminVersion = versions.getLong(1);
                }
                try (var versions =
                        sql.executeQuery(
                                "select version from identity.app_role where"
                                    + " code='PROCUREMENT_STAFF'")) {
                    versions.next();
                    staffVersion = versions.getLong(1);
                }
                var upgrade =
                        Flyway.configure()
                                .dataSource(
                                        postgres.getJdbcUrl(),
                                        postgres.getUsername(),
                                        postgres.getPassword())
                                .load();
                assertThat(upgrade.migrate().migrations.stream().map(m -> m.version).toList())
                        .contains(
                                "20260930000100",
                                "20260930000200",
                                "20260930000300",
                                "20260930000400",
                                "20260930000500");
                upgrade.validate();
                try (var tax =
                        sql.executeQuery(
                                "select tax_code,legacy_tax_code from procurement.supplier where"
                                    + " code='LEGACY'")) {
                    tax.next();
                    assertThat(tax.getString(1)).isNull();
                    assertThat(tax.getString(2)).isEqualTo("TAX-001");
                }
                assertThat(
                                sql.executeUpdate(
                                        "update procurement.supplier set name='Editable legacy"
                                            + " supplier' where code='LEGACY'"))
                        .isEqualTo(1);
                try (var versions =
                        sql.executeQuery(
                                "select code,version from identity.app_role where code in"
                                    + " ('ECOMMERCE_ADMIN','PROCUREMENT_STAFF')")) {
                    while (versions.next())
                        assertThat(versions.getLong(2))
                                .isEqualTo(
                                        versions.getString(1).equals("ECOMMERCE_ADMIN")
                                                ? adminVersion + 1
                                                : staffVersion + 2);
                }
                try (var permission =
                        sql.executeQuery(
                                """
select count(*) from identity.role_permission rp
join identity.app_role r on r.id=rp.role_id join identity.permission p on p.id=rp.permission_id
where r.code='ECOMMERCE_ADMIN' and p.code='procurement-suppliers:DELETE'
""")) {
                    permission.next();
                    assertThat(permission.getInt(1)).isEqualTo(1);
                }
                try (var controls =
                        sql.executeQuery(
                                "select count(*) from notification.po_delivery_control where"
                                    + " purchase_order_id='00000000-0000-0000-0000-000000000002'")) {
                    controls.next();
                    assertThat(controls.getInt(1)).isZero();
                }
                try (var defaults =
                        sql.executeQuery(
                                "select column_name,column_default from information_schema.columns"
                                    + " where table_schema='procurement' and table_name='supplier'"
                                    + " and column_name in"
                                    + " ('payment_term_days','lead_time_days')")) {
                    while (defaults.next())
                        assertThat(defaults.getString(2))
                                .isEqualTo(
                                        Integer.toString(
                                                defaults.getString(1).equals("payment_term_days")
                                                        ? CommercialTerms.PAYMENT_DAYS
                                                        : CommercialTerms.LEAD_DAYS));
                }
                try (var checks =
                        sql.executeQuery(
                                "select count(*) from pg_constraint where"
                                    + " connamespace='procurement'::regnamespace and not"
                                    + " convalidated")) {
                    checks.next();
                    assertThat(checks.getInt(1)).isZero();
                }
                try (var row =
                        sql.executeQuery(
                                "select"
                                    + " supplier_confirmation_status,sent_at,legacy_sent_without_timestamp"
                                    + " from procurement.purchase_order where"
                                    + " po_number='PO-LEGACY'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("PENDING");
                    assertThat(row.getTimestamp(2)).isNull();
                    assertThat(row.getBoolean(3)).isTrue();
                }
                assertThat(
                                sql.executeUpdate(
                                        "update procurement.purchase_order set"
                                            + " supplier_confirmation_status='CONFIRMED',supplier_responded_at=now()"
                                            + " where po_number='PO-LEGACY'"))
                        .isEqualTo(1);
                try (var row =
                        sql.executeQuery(
                                """
select count(*) from identity.role_permission rp
join identity.app_role r on r.id=rp.role_id join identity.permission p on p.id=rp.permission_id
where r.code='PROCUREMENT_STAFF' and p.code='procurement-suppliers:DELETE'
""")) {
                    row.next();
                    assertThat(row.getInt(1)).isZero();
                }
            }
        }
    }
}
