package com.stockflow;

import com.stockflow.common.security.Role;
import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261113000110 on the real upgrade path: the CUSTOMER role's seeded description said the customer orders; after the
 * migration it equals {@link Role#CUSTOMER}'s text, so the database and the enum agree. A description an administrator
 * wrote is left alone, and no version moves (no grant changed; ADR-0008 keys the cache by grants).
 */
class CustomerRoleDescriptionMigrationUpgradeTest {

    private static final String BEFORE = "20261113000100";
    private static final String MIGRATION = "20261113000110";

    @Test
    void theSeededDescriptionIsReplacedByTheEnumsText() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                assertThat(description(db)).as("the seeded text").isEqualTo("Browse, design, order and track");
                long version = RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'CUSTOMER'");
                String staff = RawSql.text(db, "SELECT description FROM identity.app_role WHERE code = 'SALES_STAFF'");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(description(db)).isEqualTo(Role.CUSTOMER.description());
                assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'CUSTOMER'"))
                        .isEqualTo(version);
                assertThat(RawSql.text(db, "SELECT description FROM identity.app_role WHERE code = 'SALES_STAFF'"))
                        .as("another role is untouched").isEqualTo(staff);
            }
        }
    }

    @Test
    void aDescriptionAnAdministratorWroteIsLeftAlone() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                RawSql.update(db, "UPDATE identity.app_role SET description = 'Portal accounts of our wholesale shops' "
                        + "WHERE code = 'CUSTOMER'");

                flyway(postgres, MIGRATION).migrate();

                assertThat(description(db)).isEqualTo("Portal accounts of our wholesale shops");
            }
        }
    }

    private static String description(Connection db) throws Exception {
        return RawSql.text(db, "SELECT description FROM identity.app_role WHERE code = 'CUSTOMER'");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
