package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108001100 on the real upgrade path: a database migrated to the migration before it, then upgraded. Proves the
 * new permission reaches exactly the roles the business names - the customer asks, the sales staff and the Sales
 * Manager read, the administrator holds all - and that each of them, and only they, moved their version (ADR-0008).
 */
class DesignQuoteRequestsMigrationUpgradeTest {

    private static final String BEFORE = "20261108001000";
    private static final String MIGRATION = "20261108001100";

    @Test
    void upgradeGrantsTheRightToAskAndToReadQuoteRequests() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                long customer = version(db, "CUSTOMER");
                long sales = version(db, "SALES_STAFF");
                long manager = version(db, "SALES_MANAGER");
                long admin = version(db, "SYSTEM_ADMIN");
                long coordinator = version(db, "ORDER_COORDINATOR");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(grants(db, "CUSTOMER")).isEqualTo("CREATE");
                assertThat(grants(db, "SALES_STAFF")).isEqualTo("READ,VIEW_PAGE");
                assertThat(grants(db, "SALES_MANAGER")).isEqualTo("READ,VIEW_PAGE");
                assertThat(grants(db, "SYSTEM_ADMIN")).isEqualTo("CREATE,READ,VIEW_PAGE");
                assertThat(grants(db, "ORDER_COORDINATOR")).isEmpty();

                assertThat(version(db, "CUSTOMER")).isEqualTo(customer + 1);
                assertThat(version(db, "SALES_STAFF")).isEqualTo(sales + 1);
                assertThat(version(db, "SALES_MANAGER")).isEqualTo(manager + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(admin + 1);
                assertThat(version(db, "ORDER_COORDINATOR")).isEqualTo(coordinator);
            }
        }
    }

    private static String grants(Connection db, String role) throws Exception {
        String found = RawSql.text(db, "SELECT coalesce(string_agg(p.action, ',' ORDER BY p.action), '') FROM "
                + "identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p "
                + "ON p.id = rp.permission_id WHERE p.resource = 'design-quote-requests' AND r.code = ?", role);
        return found == null ? "" : found;
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
