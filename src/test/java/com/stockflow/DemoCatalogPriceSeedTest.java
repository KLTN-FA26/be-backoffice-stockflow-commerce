package com.stockflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

/** Real PostgreSQL tests of demo-only scope, populated upgrades and non-destructive repeatability. */
class DemoCatalogPriceSeedTest {
    @Test
    void upgradedDemoKeepsExistingPricesAndRepeatedSeedsChangeNothing() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration", "classpath:db/demo")
                    .repeatableSqlMigrationPrefix("OLD_RELEASE_WITHOUT_REPEATABLE_SEEDS")
                    .load().migrate();
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate("""
                        insert into catalog.pricing_rule(id,name,sku,price,currency,active,version,created_at,created_by)
                        values (md5('test:existing-price')::uuid,'BASE:SOFA-3S-GREY','SOFA-3S-GREY',14200000,
                            'VND',true,7,'2026-10-01T00:00:00Z','operator');
                        """);
                String existing = rows(connection);
                var flyway = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                        .locations("classpath:db/migration", "classpath:db/demo").load();
                assertThat(flyway.migrate().migrations).extracting(m -> m.description)
                        .contains("demo catalog selling prices");
                flyway.validate();
                try (var row = sql.executeQuery("select row_to_json(r)::text from catalog.pricing_rule r where sku='SOFA-3S-GREY'")) {
                    assertThat(row.next()).isTrue();
                    assertThat("[" + row.getString(1) + "]").isEqualTo(existing);
                }
                try (var row = sql.executeQuery("select price,created_by from catalog.pricing_rule where sku='TABLE-OAK-160'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getBigDecimal(1)).isEqualByComparingTo("8000000");
                    assertThat(row.getString(2)).isEqualTo("demo-seed");
                }
                String seeded = rows(connection);
                seedAgain(connection);
                seedAgain(connection);
                assertThat(rows(connection)).isEqualTo(seeded);
                assertThat(flyway.migrate().migrationsExecuted).isZero();

                // An active general rule must not acquire competing sample base rules.
                sql.executeUpdate("delete from catalog.pricing_rule");
                sql.executeUpdate("""
                        insert into catalog.pricing_rule(id,name,price,currency,active,created_at)
                        values (md5('test:general-price')::uuid,'Existing general price',9000000,'VND',true,now())
                        """);
                String general = rows(connection);
                seedAgain(connection);
                assertThat(rows(connection)).isEqualTo(general);
            }
        }
    }

    @Test
    void normalMigrationsAndUnrelatedProductsNeverReceiveDemoPrices() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            // The default/production migration location does not discover db/demo.
            var flyway = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load();
            assertThat(flyway.info().pending()).extracting(m -> m.getDescription())
                    .doesNotContain("demo catalog selling prices");
            flyway.migrate();
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate("""
                        insert into product.products(id,code,name,slug,created_by)
                        values (md5('test:business-product')::uuid,'SOFA-3S','Real product','real-product','operator');
                        insert into product.variants(id,product_id,sku,name,attribute_signature,created_by)
                        values (md5('test:business-variant')::uuid,md5('test:business-product')::uuid,
                            'SOFA-3S-GREY','Real variant','','operator');
                        """);
                seedAgain(connection);
                try (var row = sql.executeQuery("select count(*) from catalog.pricing_rule")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong(1)).isZero();
                }
            }
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

    private static String rows(Connection connection) throws Exception {
        try (var sql = connection.createStatement();
                var row = sql.executeQuery("select coalesce(json_agg(r order by sku,name),'[]'::json)::text from catalog.pricing_rule r")) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }
}
