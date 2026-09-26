package com.stockflow.procurement.internal.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

/** Unlike a fresh-context test, exercises the exact develop-to-feature upgrade path. */
class ProcurementMigrationUpgradeTest {
    @Test void upgradeDevelopWithHistoricalSentOrdersWithoutInventingSendTime() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target("20260919001700").load().migrate();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var sql = connection.createStatement()) {
                sql.executeUpdate("""
                        insert into procurement.supplier(id,code,name,email,status,created_at)
                        values ('00000000-0000-0000-0000-000000000001','LEGACY','Legacy','old@example.com','ACTIVE',now())
                        """);
                sql.executeUpdate("""
                        insert into procurement.purchase_order(id,po_number,supplier_id,status,created_at)
                        values ('00000000-0000-0000-0000-000000000002','PO-LEGACY',
                        '00000000-0000-0000-0000-000000000001','SENT',now())
                        """);
                var upgrade = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load();
                assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(4);
                upgrade.validate();
                try (var controls = sql.executeQuery("select count(*) from notification.po_delivery_control where purchase_order_id='00000000-0000-0000-0000-000000000002'")) {
                    controls.next(); assertThat(controls.getInt(1)).isZero();
                }
                try (var defaults = sql.executeQuery("select column_name,column_default from information_schema.columns where table_schema='procurement' and table_name='supplier' and column_name in ('payment_term_days','lead_time_days')")) {
                    while (defaults.next()) assertThat(defaults.getString(2)).isEqualTo(Integer.toString(
                            defaults.getString(1).equals("payment_term_days") ? com.stockflow.common.domain.CommercialTerms.PAYMENT_DAYS
                                    : com.stockflow.common.domain.CommercialTerms.LEAD_DAYS));
                }
                try (var checks = sql.executeQuery("select count(*) from pg_constraint where connamespace='procurement'::regnamespace and not convalidated")) {
                    checks.next(); assertThat(checks.getInt(1)).isZero();
                }
                try (var row = sql.executeQuery("select supplier_confirmation_status,sent_at,legacy_sent_without_timestamp from procurement.purchase_order where po_number='PO-LEGACY'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("PENDING");
                    assertThat(row.getTimestamp(2)).isNull();
                    assertThat(row.getBoolean(3)).isTrue();
                }
                assertThat(sql.executeUpdate("update procurement.purchase_order set supplier_confirmation_status='CONFIRMED',supplier_responded_at=now() where po_number='PO-LEGACY'")).isEqualTo(1);
                try (var row = sql.executeQuery("""
                        select count(*) from identity.role_permission rp
                        join identity.app_role r on r.id=rp.role_id join identity.permission p on p.id=rp.permission_id
                        where r.code='PROCUREMENT_STAFF' and p.code='procurement-suppliers:DELETE'
                        """)) {
                    row.next(); assertThat(row.getInt(1)).isZero();
                }
            }
        }
    }
}
