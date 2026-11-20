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
 * V20261210000200 on the real upgrade path (spec 2026-11-20, coordinator owns production): the Production Coordinator
 * reviews the customers' designs instead of the Sales Manager (design-designs:APPROVE, with the READ and VIEW_PAGE the
 * review screens call); production orders are no longer approved (production-orders:APPROVE gone, grants and row); the
 * Sales Manager and the administrator are told of production orders sent (production-order-sends:READ). Exactly the
 * roles whose grants changed move their authorisation version (ADR-0008).
 */
class CoordinatorReviewsDesignsMigrationUpgradeTest {

    private static final String BEFORE = "20261210000100";
    private static final String MIGRATION = "20261210000200";

    @Test
    void theCoordinatorReviewsDesignsAndTheManagerIsToldOfSends() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                assertThat(holders(db, "design-designs:APPROVE")).containsExactly("SALES_MANAGER", "SYSTEM_ADMIN");
                assertThat(holders(db, "production-orders:APPROVE")).containsExactly("SALES_MANAGER", "SYSTEM_ADMIN");
                long coordinatorBefore = version(db, "PRODUCTION_COORDINATOR");
                long managerBefore = version(db, "SALES_MANAGER");
                long adminBefore = version(db, "SYSTEM_ADMIN");
                long customerBefore = version(db, "CUSTOMER");
                long warehouseManagerBefore = version(db, "WAREHOUSE_MANAGER");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(holders(db, "design-designs:APPROVE")).containsExactly("PRODUCTION_COORDINATOR",
                        "SYSTEM_ADMIN");
                assertThat(holders(db, "production-order-sends:READ")).containsExactly("SALES_MANAGER",
                        "SYSTEM_ADMIN");
                assertThat(RawSql.texts(db, "SELECT p.code FROM identity.role_permission rp JOIN identity.app_role r "
                        + "ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id WHERE r.code = ? "
                        + "AND p.resource = 'design-designs' ORDER BY 1", "PRODUCTION_COORDINATOR"))
                        .as("what the review screens call, nothing more")
                        .containsExactly("design-designs:APPROVE", "design-designs:READ", "design-designs:VIEW_PAGE");
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code = ?",
                        "production-orders:APPROVE")).isZero();

                assertThat(version(db, "PRODUCTION_COORDINATOR")).isEqualTo(coordinatorBefore + 1);
                assertThat(version(db, "SALES_MANAGER")).isEqualTo(managerBefore + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(adminBefore + 1);
                assertThat(version(db, "CUSTOMER")).isEqualTo(customerBefore);
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(warehouseManagerBefore);
            }
        }
    }

    private static List<String> holders(Connection db, String permission) throws SQLException {
        return RawSql.texts(db, "SELECT r.code FROM identity.role_permission rp JOIN identity.app_role r ON r.id = "
                + "rp.role_id JOIN identity.permission p ON p.id = rp.permission_id WHERE p.code = ? ORDER BY 1",
                permission);
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
