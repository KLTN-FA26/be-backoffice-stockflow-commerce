package com.stockflow.production;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261014000200 on the real upgrade path: a database migrated to develop's last migration
 * (V20261013000600), carrying rows the application and an administrator wrote, then upgraded.
 * Proves the grant change lands, every changed role's version moves (ADR-0008) and nothing else is
 * touched: an administrator's own extra grant on QC_STAFF, a QC user's role, the other roles' versions.
 */
class ProductionGrantsMigrationUpgradeTest {

    private static final String BEFORE = "20261013000600";
    private static final String MIGRATION = "20261014000200";

    @Test
    void upgradeMovesTheAnswerToApproveAndKeepsEverythingElse() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                assertThat(grants(db, "QC_STAFF")).contains("production-sample-requests:UPDATE");
                assertThat(grants(db, "CUSTOMER")).noneMatch(g -> g.startsWith("production-sample-requests:"));

                // Rows written before the upgrade: a QC inspector, and an administrator's extra grant on
                // QC_STAFF (an editable role) that the migration has no business removing.
                UUID inspector = UUID.randomUUID();
                exec(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at) "
                        + "VALUES ('" + inspector + "', 'qc-1', 'qc-1@example.com', '!x', 'ACTIVE', 0, NOW())");
                exec(db, "INSERT INTO identity.user_role (id, user_id, role_id, version, created_at) "
                        + "SELECT gen_random_uuid(), '" + inspector + "', id, 0, NOW() FROM identity.app_role "
                        + "WHERE code = 'QC_STAFF'");
                exec(db, "INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by) "
                        + "SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'admin' FROM identity.app_role r "
                        + "JOIN identity.permission p ON p.code = 'production-sample-requests:CREATE' "
                        + "WHERE r.code = 'QC_STAFF'");
                Map<String, Long> versionsBefore = versions(db);
                List<String> salesBefore = grants(db, "SALES_STAFF");
                List<String> productionStaffBefore = grants(db, "PRODUCTION_STAFF");

                // Up to this migration only: later ones (V20261014000300 quotes) move some of the same role
                // versions again, and this test is about what V20261014000200 does.
                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).contains(MIGRATION);

                assertThat(grants(db, "CUSTOMER")).filteredOn(g -> g.startsWith("production-"))
                        .containsExactlyInAnyOrder("production-sample-requests:VIEW_PAGE",
                                "production-sample-requests:READ", "production-sample-requests:APPROVE");
                assertThat(grants(db, "SALES_STAFF")).containsAll(salesBefore)
                        .contains("production-sample-requests:APPROVE")
                        .hasSize(salesBefore.size() + 1);
                assertThat(grants(db, "QC_STAFF"))
                        .doesNotContain("production-sample-requests:UPDATE", "production-sample-requests:APPROVE")
                        .contains("production-sample-requests:VIEW_PAGE", "production-sample-requests:READ",
                                "production-sample-requests:CREATE", "production-orders:UPDATE");
                assertThat(grants(db, "PRODUCTION_STAFF")).containsExactlyInAnyOrderElementsOf(productionStaffBefore);
                assertThat(scalar(db, "SELECT count(*) FROM identity.user_role ur JOIN identity.app_role r "
                        + "ON r.id = ur.role_id WHERE r.code = 'QC_STAFF' AND ur.user_id = '" + inspector + "'"))
                        .isEqualTo(1);

                Map<String, Long> versionsAfter = versions(db);
                for (String role : versionsBefore.keySet()) {
                    long expected = versionsBefore.get(role)
                            + (List.of("CUSTOMER", "SALES_STAFF", "QC_STAFF").contains(role) ? 1 : 0);
                    assertThat(versionsAfter.get(role)).as("version of " + role).isEqualTo(expected);
                }
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
        List<String> codes = new ArrayList<>();
        try (ResultSet rows = db.createStatement().executeQuery("SELECT p.code FROM identity.role_permission rp "
                + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id "
                + "WHERE r.code = '" + role + "'")) {
            while (rows.next()) {
                codes.add(rows.getString(1));
            }
        }
        return codes;
    }

    private static Map<String, Long> versions(Connection db) throws Exception {
        Map<String, Long> versions = new HashMap<>();
        try (ResultSet rows = db.createStatement().executeQuery("SELECT code, version FROM identity.app_role")) {
            while (rows.next()) {
                versions.put(rows.getString(1), rows.getLong(2));
            }
        }
        return versions;
    }

    private static long scalar(Connection db, String sql) throws Exception {
        try (ResultSet rows = db.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void exec(Connection db, String sql) throws Exception {
        db.createStatement().execute(sql);
    }
}
