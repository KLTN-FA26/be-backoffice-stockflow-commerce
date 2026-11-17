package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261125000100 on the real upgrade path (BE1; kltn-docs 06 §4.6, C43): a CHECKed, NOT NULL column added to
 * {@code warehouse.warehouse}, which holds rows - every warehouse there takes 100% and keeps everything else, and from
 * then on the factor stays in (0, 1]. The board's permissions go to exactly the five roles 06 §2 names, whose
 * authorisation version moves (ADR-0008); a role granted nothing keeps its version.
 */
class WarehouseCapacityMigrationUpgradeTest {

    private static final String BEFORE = "20261124000800";
    private static final String MIGRATION = "20261125000100";

    @Test
    void everyWarehouseTakesOneHundredPercentAndTheFiveRolesReadTheBoard() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                // Two warehouses written before the column: one active, one inactive.
                RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, "
                        + "map_width, map_height, version, created_at) VALUES "
                        + "(gen_random_uuid(), 'Kho Ha Noi', 'ACTIVE', 'UPHN', '1 Hue', 'M', 40, 25, 3, NOW()), "
                        + "(gen_random_uuid(), 'Kho Da Nang', 'INACTIVE', 'UPDN', '2 Bach Dang', 'M', 30, 20, 0, NOW())");
                long managerBefore = version(db, "WAREHOUSE_MANAGER");
                long salesBefore = version(db, "SALES_STAFF");
                long staffBefore = version(db, "WAREHOUSE_STAFF");
                long customerBefore = version(db, "CUSTOMER");
                long accountantBefore = version(db, "ACCOUNTANT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT prefix || ':' || utilization_factor || ':' || status || ':' "
                        + "|| version || ':' || map_width FROM warehouse.warehouse WHERE prefix IN ('UPHN', 'UPDN') "
                        + "ORDER BY prefix"))
                        .as("100% for every warehouse there, nothing else touched")
                        .containsExactly("UPDN:1.0000:INACTIVE:0:30.000", "UPHN:1.0000:ACTIVE:3:40.000");
                RawSql.update(db, "UPDATE warehouse.warehouse SET utilization_factor = 0.85 WHERE prefix = 'UPHN'");
                assertThat(new BigDecimal(RawSql.text(db, "SELECT utilization_factor::text FROM warehouse.warehouse "
                        + "WHERE prefix = ?", "UPHN"))).isEqualByComparingTo("0.85");
                RawSql.refusedBy(db, "ck_warehouse_utilization_factor", "C43: above 0",
                        "UPDATE warehouse.warehouse SET utilization_factor = 0 WHERE prefix = 'UPHN'");
                RawSql.refusedBy(db, "ck_warehouse_utilization_factor", "C43: at most 100%",
                        "UPDATE warehouse.warehouse SET utilization_factor = 1.0001 WHERE prefix = 'UPDN'");

                for (String code : List.of("warehouse-capacity:VIEW_PAGE", "warehouse-capacity:READ")) {
                    assertThat(holders(db, code)).as(code).containsExactly("PRODUCTION_COORDINATOR", "SALES_MANAGER",
                            "SALES_STAFF", "SYSTEM_ADMIN", "WAREHOUSE_MANAGER");
                }
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(managerBefore + 1);
                assertThat(version(db, "SALES_STAFF")).isEqualTo(salesBefore + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staffBefore);
                assertThat(version(db, "CUSTOMER")).isEqualTo(customerBefore);
                assertThat(version(db, "ACCOUNTANT")).isEqualTo(accountantBefore);
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
