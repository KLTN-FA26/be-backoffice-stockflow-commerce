package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261129000100 on the real upgrade path: the Sales Manager gives up every identity-users, identity-roles and
 * identity-rbac grant (kltn-docs 00 §2.5.3 item 7: staff accounts are the System Admin's), the Production Coordinator
 * gets its inbox (notification-notifications VIEW_PAGE, READ, UPDATE), and exactly those two roles move their
 * authorisation version (ADR-0008); the System Admin keeps managing permissions, so nobody is locked out.
 */
class AdminOnlyAccountsMigrationUpgradeTest {

    private static final String BEFORE = "20261127000100";
    private static final String MIGRATION = "20261129000100";

    @Test
    void theSalesManagerLosesAccountsAndTheCoordinatorGetsItsInbox() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                assertThat(identityGrants(db, "SALES_MANAGER")).as("held before").isNotEmpty();
                long managerBefore = version(db, "SALES_MANAGER");
                long coordinatorBefore = version(db, "PRODUCTION_COORDINATOR");
                long staffBefore = version(db, "SALES_STAFF");
                long adminBefore = version(db, "SYSTEM_ADMIN");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(identityGrants(db, "SALES_MANAGER")).isEmpty();
                assertThat(identityGrants(db, "SYSTEM_ADMIN")).contains("identity-rbac:APPROVE", "identity-users:CREATE",
                        "identity-roles:UPDATE");
                assertThat(RawSql.texts(db, "SELECT p.code FROM identity.role_permission rp JOIN identity.app_role r "
                        + "ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id WHERE r.code = ? "
                        + "AND p.resource = 'notification-notifications' ORDER BY 1", "PRODUCTION_COORDINATOR"))
                        .containsExactly("notification-notifications:READ", "notification-notifications:UPDATE",
                                "notification-notifications:VIEW_PAGE");

                assertThat(version(db, "SALES_MANAGER")).isEqualTo(managerBefore + 1);
                assertThat(version(db, "PRODUCTION_COORDINATOR")).isEqualTo(coordinatorBefore + 1);
                assertThat(version(db, "SALES_STAFF")).isEqualTo(staffBefore);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(adminBefore);
                assertThat(RawSql.text(db, "SELECT description FROM identity.app_role WHERE code = ?", "SALES_MANAGER"))
                        .doesNotContain("staff accounts");
            }
        }
    }

    private static List<String> identityGrants(Connection db, String role) throws SQLException {
        return RawSql.texts(db, "SELECT p.code FROM identity.role_permission rp JOIN identity.app_role r ON r.id = "
                + "rp.role_id JOIN identity.permission p ON p.id = rp.permission_id WHERE r.code = ? AND p.resource IN "
                + "('identity-users', 'identity-roles', 'identity-rbac') ORDER BY 1", role);
    }

    private static long version(Connection db, String role) throws SQLException {
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
