package com.stockflow.customer;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static com.stockflow.support.RawSql.number;
import static com.stockflow.support.RawSql.refused;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261015000830 (one customer per tax code in every status, decided 16/10/2026) on a database that already holds
 * customers: a duplicate stops the migration with the ids for a person to reconcile - nothing is merged or changed -
 * and the read-only preflight names the same duplicate first. Once reconciled, the code is unique for BLOCKED and
 * INACTIVE customers too, and NULL stays free.
 */
class CustomerTaxCodeMigrationUpgradeTest {

    private static final String BEFORE = "20261015000820";

    @Test
    void aDuplicateStopsTheUpgradeUntilSomeoneReconcilesIt() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                UUID active = customer(db, "ACTIVE", "0319999901");
                UUID closed = customer(db, "INACTIVE", "0319999901");
                customer(db, "ACTIVE", null);
                customer(db, "INACTIVE", null);

                pg.copyFileToContainer(MountableFile.forHostPath(Path.of("tools/db/preflight/pr109_preflight.sql")),
                        "/tmp/preflight.sql");
                var preflight = pg.execInContainer("psql", "-U", pg.getUsername(), "-d", pg.getDatabaseName(), "-X",
                        "-q", "-f", "/tmp/preflight.sql");
                assertThat(preflight.getExitCode()).withFailMessage(preflight.getStderr()).isZero();
                assertThat(preflight.getStderr()).contains("3. tax code 0319999901 is held by more than one customer",
                        active.toString(), closed.toString());

                assertThatThrownBy(() -> flyway(pg, null).migrate()).isInstanceOf(FlywayException.class)
                        .hasMessageContaining("0319999901").hasMessageContaining(closed.toString());
                assertThat(number(db, "SELECT count(*) FROM customer.customer WHERE tax_code = '0319999901'"))
                        .as("nothing merged, deleted or renumbered").isEqualTo(2);

                // Reconciled by hand (here: the closed duplicate is found to be a typo and corrected).
                update(db, "UPDATE customer.customer SET tax_code = '0319999902' WHERE id = ?", closed);
                flyway(pg, null).repair();
                flyway(pg, null).migrate();

                assertThat(number(db, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uk_customer_tax_code'"))
                        .isEqualTo(1);
                assertThat(number(db, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uk_customer_tax_code_live'"))
                        .isZero();
                refused(db, "a BLOCKED customer under a code already held",
                        "INSERT INTO customer.customer (id, full_name, email, status, tax_code, version, created_at)"
                                + " VALUES (gen_random_uuid(), 'Twin', ?, 'BLOCKED', '0319999901', 0, NOW())",
                        "twin-" + UUID.randomUUID() + "@example.test");
                customer(db, "ACTIVE", null);   // no tax code: a household business, never constrained
            }
        }
    }

    private static UUID customer(Connection db, String status, String taxCode) throws Exception {
        UUID id = UUID.randomUUID();
        update(db, "INSERT INTO customer.customer (id, full_name, email, status, tax_code, version, created_at)"
                + " VALUES (?, 'Co', ?, ?, ?, 0, NOW())", id, id + "@example.test", status, taxCode);
        return id;
    }

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        var config = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }
}
