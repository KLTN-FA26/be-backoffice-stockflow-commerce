package com.stockflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * The repeatable demo price seed on real PostgreSQL, since 5-3 emptied it: V20261110000100 dropped the base prices it
 * wrote (kltn-docs 13 BR-04). It is kept, not deleted, because Flyway validates the repeatable migrations a database
 * has applied - so it must still apply, validate and change nothing, on a new demo database and on one that ran the old
 * body.
 */
class DemoCatalogPriceSeedTest {

    private static final String SEED = "demo catalog selling prices";

    @Test
    void theEmptiedSeedAppliesOnANewAndAnOldDemoDatabaseAndChangesNothing() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            var flyway = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration", "classpath:db/demo").load();
            assertThat(flyway.migrate().migrations).extracting(m -> m.description).contains(SEED);
            flyway.validate();
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    var sql = connection.createStatement()) {
                assertThat(priceTableExists(connection)).isFalse();

                // A demo database that applied the old body has its checksum: the new body runs once, then validates.
                sql.executeUpdate("update flyway_schema_history set checksum = 42 where description = '" + SEED + "'");
                var rerun = flyway.migrate();
                assertThat(rerun.migrationsExecuted).isEqualTo(1);
                assertThat(rerun.migrations).extracting(m -> m.description).containsExactly(SEED);
                flyway.validate();
                assertThat(flyway.migrate().migrationsExecuted).isZero();

                seedAgain(connection);
                seedAgain(connection);
                assertThat(priceTableExists(connection)).isFalse();
            }
        }
    }

    @Test
    void normalMigrationsNeverDiscoverTheDemoSeed() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            // The default/production migration location does not discover db/demo.
            var flyway = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load();
            assertThat(flyway.info().pending()).extracting(m -> m.getDescription()).doesNotContain(SEED);
            flyway.migrate();
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                assertThat(priceTableExists(connection)).isFalse();
            }
        }
    }

    private static boolean priceTableExists(Connection connection) throws Exception {
        try (var sql = connection.createStatement();
                var row = sql.executeQuery("select to_regclass('catalog.pricing_rule') is not null")) {
            assertThat(row.next()).isTrue();
            return row.getBoolean(1);
        }
    }

    private static void seedAgain(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/demo/R__demo_catalog_selling_prices.sql"));
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
