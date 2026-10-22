package com.stockflow.order;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.stockflow.support.RawSql.number;
import static com.stockflow.support.RawSql.texts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261022001100 (ORDER_COORDINATOR may list warehouses for release) upgraded from the newest migration before it,
 * and only through it: exactly one grant row is added, every other role keeps its grants and its version, and
 * running the file again on a database that already has the row changes nothing.
 */
class CoordinatorWarehouseReadMigrationUpgradeTest {

    private static final String BEFORE = "20261022001000";
    private static final String UNDER_TEST = "20261022001100";
    private static final String SCRIPT = "db/migration/V20261022001100__order_coordinator_warehouse_read.sql";
    private static final List<String> ROLES = List.of("ORDER_COORDINATOR", "SALES_STAFF", "ACCOUNTANT", "SYSTEM_ADMIN",
            "WAREHOUSE_STAFF", "WAREHOUSE_MANAGER", "INVENTORY_PLANNER", "QC_STAFF", "ECOMMERCE_ADMIN",
            "PROCUREMENT_STAFF", "PRODUCTION_STAFF", "CUSTOMER");

    @Test
    void grantsTheCoordinatorWarehouseReadAndNothingElse() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                assertThat(grants(db, "ORDER_COORDINATOR", "warehouse-warehouses")).as("the gap being fixed").isEmpty();
                assertThat(grants(db, "ORDER_COORDINATOR", "sales-orders")).contains("APPROVE");
                List<String> before = allGrants(db);
                List<Long> versionsBefore = versions(db);

                var result = flyway(pg, UNDER_TEST).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(UNDER_TEST);

                List<String> after = allGrants(db);
                List<String> added = new ArrayList<>(after);
                added.removeAll(before);
                assertThat(added).as("exactly one new row").containsExactly("ORDER_COORDINATOR warehouse-warehouses:READ");
                assertThat(after).as("nothing revoked").containsAll(before);

                assertThat(grants(db, "ORDER_COORDINATOR", "warehouse-warehouses")).containsExactly("READ");
                assertThat(grants(db, "ORDER_COORDINATOR", "warehouse-locations")).as("no warehouse map").isEmpty();
                assertThat(grants(db, "ORDER_COORDINATOR", "sales-orders")).as("still releases").contains("APPROVE");
                assertThat(grants(db, "ACCOUNTANT", "warehouse-warehouses")).isEmpty();
                assertThat(grants(db, "SALES_STAFF", "warehouse-warehouses")).containsExactly("READ");
                assertThat(grants(db, "SYSTEM_ADMIN", "warehouse-warehouses"))
                        .containsExactly("CREATE", "READ", "UPDATE", "VIEW_PAGE");

                List<Long> versionsAfter = versions(db);
                for (int i = 0; i < ROLES.size(); i++) {
                    long expected = versionsBefore.get(i) + ("ORDER_COORDINATOR".equals(ROLES.get(i)) ? 1 : 0);
                    assertThat(versionsAfter.get(i)).as(ROLES.get(i) + " version").isEqualTo(expected);
                }

                // Re-applied by hand on a database that already has the row: no duplicate, no cache invalidation.
                try (var statement = db.createStatement()) {
                    statement.execute(script());
                }
                assertThat(allGrants(db)).isEqualTo(after);
                assertThat(versions(db)).isEqualTo(versionsAfter);
            }
        }
    }

    private static List<String> grants(Connection db, String role, String resource) throws Exception {
        return texts(db, "SELECT p.action FROM identity.role_permission rp"
                + " JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id"
                + " WHERE r.code = ? AND p.resource = ? ORDER BY p.action", role, resource);
    }

    /** Every (role, permission) pair, duplicates included, so a doubled row would show. */
    private static List<String> allGrants(Connection db) throws Exception {
        return texts(db, "SELECT r.code || ' ' || p.code FROM identity.role_permission rp"
                + " JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id"
                + " ORDER BY r.code, p.code");
    }

    private static List<Long> versions(Connection db) throws Exception {
        List<Long> versions = new ArrayList<>();
        for (String role : ROLES) {
            versions.add(number(db, "SELECT version FROM identity.app_role WHERE code = ?", role));
        }
        return versions;
    }

    private static String script() throws Exception {
        try (var in = CoordinatorWarehouseReadMigrationUpgradeTest.class.getClassLoader().getResourceAsStream(SCRIPT)) {
            return new String(Objects.requireNonNull(in, SCRIPT).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        return Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo").target(target).load();
    }
}
