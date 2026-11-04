package com.stockflow.production;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lane A workshop migrations (V20261017000400 holds and issue, 000420 adjustment layer and QC grants,
 * 000430 agreed cancellation fee) on the real upgrade path: a database at V20261017000300 with an administrator's
 * own grant, upgraded. Proves the new resources reach exactly the roles named, QC loses
 * production-orders:UPDATE and nothing else it holds, every changed role's version moves (ADR-0008) and no other
 * does, and the new constraints are in force and valid.
 */
class WorkshopMigrationUpgradeTest {

    private static final String BEFORE = "20261017000300";
    private static final String LAST = "20261017000430";

    @Test
    void upgradeGrantsTheWorkshopStepsAndKeepsEverythingElse() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                assertThat(grants(db, "QC_STAFF")).contains("production-orders:UPDATE");
                // An administrator's own extra grant on QC_STAFF, which these migrations have no business removing.
                exec(db, "INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by) "
                        + "SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'admin' FROM identity.app_role r "
                        + "JOIN identity.permission p ON p.code = 'production-orders:EXPORT' WHERE r.code = 'QC_STAFF'");
                Map<String, Long> versionsBefore = versions(db);
                List<String> qcBefore = grants(db, "QC_STAFF");
                List<String> customerBefore = grants(db, "CUSTOMER");

                // Up to the last lane A migration only: anything later moves versions of its own.
                var result = flyway(postgres, LAST).migrate();
                assertThat(result.migrations).extracting(m -> m.version)
                        .contains("20261017000400", "20261017000420", LAST);

                assertThat(grants(db, "WAREHOUSE_STAFF")).filteredOn(g -> g.startsWith("production-material-issues:"))
                        .containsExactlyInAnyOrder("production-material-issues:READ", "production-material-issues:CREATE");
                assertThat(grants(db, "PRODUCTION_STAFF")).filteredOn(g -> g.startsWith("production-material-issues:")
                        || g.startsWith("production-qc-results:"))
                        .containsExactlyInAnyOrder("production-material-issues:READ", "production-qc-results:READ");
                assertThat(grants(db, "QC_STAFF")).filteredOn(g -> g.startsWith("production-qc-results:"))
                        .containsExactlyInAnyOrder("production-qc-results:READ", "production-qc-results:CREATE");
                List<String> qcExpected = new ArrayList<>(qcBefore);
                qcExpected.remove("production-orders:UPDATE");
                qcExpected.addAll(List.of("production-qc-results:READ", "production-qc-results:CREATE"));
                assertThat(grants(db, "QC_STAFF")).as("only UPDATE goes; the administrator's EXPORT stays")
                        .containsExactlyInAnyOrderElementsOf(qcExpected);
                assertThat(grants(db, "CUSTOMER")).containsExactlyInAnyOrderElementsOf(customerBefore);
                assertThat(scalar(db, "SELECT count(*) FROM identity.permission p WHERE p.resource IN "
                        + "('production-material-issues', 'production-qc-results') AND NOT EXISTS ("
                        + "SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id "
                        + "WHERE rp.permission_id = p.id AND r.code = 'SYSTEM_ADMIN')"))
                        .as("SYSTEM_ADMIN holds every new permission").isZero();

                Map<String, Long> versionsAfter = versions(db);
                Map<String, Integer> moves = Map.of("WAREHOUSE_STAFF", 1, "WAREHOUSE_MANAGER", 2, "PRODUCTION_STAFF", 2,
                        "SYSTEM_ADMIN", 2, "QC_STAFF", 1, "SALES_STAFF", 1);
                for (String role : versionsBefore.keySet()) {
                    assertThat(versionsAfter.get(role)).as("version of " + role)
                            .isEqualTo(versionsBefore.get(role) + moves.getOrDefault(role, 0));
                }

                assertThat(scalar(db, "SELECT count(*) FROM pg_constraint WHERE conname IN ('ck_stock_reservation_owner', "
                        + "'ck_stock_reservation_production_pinned', 'fk_stock_reservation_production_order', "
                        + "'ck_order_cancellation_fee', 'fk_production_material_issue_reservation') AND convalidated"))
                        .isEqualTo(5);
                assertThatThrownBy(() -> exec(db, "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, "
                        + "reserved, status, version, created_at) VALUES ('" + UUID.randomUUID() + "', 'X', 'NOWHERE', "
                        + "1, 0, 'ON_THE_MOON', 0, NOW())")).as("the status list is still closed");
                assertThat(scalar(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'inventory' "
                        + "AND table_name = 'stock_adjustment' AND column_name = 'received_at'")).isEqualTo(1);
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static List<String> grants(Connection db, String role) throws Exception {
        return RawSql.texts(db, "SELECT p.code FROM identity.role_permission rp "
                + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id "
                + "WHERE r.code = ?", role);
    }

    private static Map<String, Long> versions(Connection db) throws Exception {
        return RawSql.numbersByText(db, "SELECT code, version FROM identity.app_role");
    }

    private static long scalar(Connection db, String sql) throws Exception {
        return RawSql.number(db, sql);
    }

    private static void exec(Connection db, String sql) throws Exception {
        RawSql.update(db, sql);
    }
}
