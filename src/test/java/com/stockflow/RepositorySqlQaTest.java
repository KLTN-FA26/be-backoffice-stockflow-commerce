package com.stockflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs every adversarial script under {@code tools/db/qa/} and {@code tools/db/orphan_check.sql} against a freshly
 * migrated database with the demo seed: every CHECK and trigger refuses what it should, and no row points at nothing.
 * The scripts state the database's rules; this test is what keeps them true in CI rather than only when someone runs
 * {@code tools/db/qa/run.sh} by hand.
 */
class RepositorySqlQaTest {

    @Test
    void freshDatabasePassesRepositorySqlQaAndHasNoOrphans() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
            long checks = 0;
            try (var scripts = Files.list(Path.of("tools/db/qa"))) {
                for (var script : scripts.filter(p -> p.getFileName().toString().matches("[0-9]{2}_.*\\.sql"))
                        .sorted().toList()) {
                    String target = "/tmp/" + script.getFileName();
                    pg.copyFileToContainer(MountableFile.forHostPath(script), target);
                    var result = pg.execInContainer("psql", "-U", pg.getUsername(), "-d", pg.getDatabaseName(),
                            "-X", "-q", "-o", "/dev/null", "-f", target);
                    assertThat(result.getExitCode()).withFailMessage(script + ": " + result.getStderr()).isZero();
                    // A check that fails raises 'FAIL  <label>' (two spaces); a bare "FAIL" also matches the label
                    // of a passing check that names DELIVERY_FAILED or FAILED_TRIP_FEE.
                    assertThat(result.getStderr()).as(script.toString()).doesNotContain("FAIL  ", "ERROR:");
                    checks += result.getStderr().lines().filter(line -> line.contains("ok    ")).count();
                }
            }
            assertThat(checks).isGreaterThan(0);
            System.out.println("Repository SQL QA checks passed: " + checks);
            pg.copyFileToContainer(MountableFile.forHostPath(Path.of("tools/db/orphan_check.sql")), "/tmp/orphan.sql");
            var orphans = pg.execInContainer("psql", "-U", pg.getUsername(), "-d", pg.getDatabaseName(),
                    "-X", "-A", "-t", "-f", "/tmp/orphan.sql");
            assertThat(orphans.getExitCode()).withFailMessage(orphans.getStderr()).isZero();
            SeedTestDataLoadTest.assertOrphanReportClean(orphans.getStdout());
        }
    }
}
