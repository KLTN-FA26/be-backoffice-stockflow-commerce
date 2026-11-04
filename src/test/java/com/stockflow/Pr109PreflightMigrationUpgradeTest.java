package com.stockflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tools/db/preflight/pr109_preflight.sql} on a database as develop deployed it (V20261016000100, #120): it writes
 * nothing and says, before the upgrade, what would stop it - a failed or newer applied migration (Flyway does not run
 * this branch's files out of order) and, for #121's blank foreign keys, a blank with no inventory item. Ids and counts
 * only: no SKU, name or tax code reaches the deploy terminal.
 */
class Pr109PreflightMigrationUpgradeTest {

    private static final String DEVELOP = "20261016000100";

    @Test
    @DisplayName("on develop's database the preflight passes the history, counts a blank without an item, and names a newer applied version")
    void thePreflightTellsWhatWouldStopTheUpgrade() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, DEVELOP).migrate();
            pg.copyFileToContainer(MountableFile.forHostPath(Path.of("tools/db/preflight/pr109_preflight.sql")),
                    "/tmp/preflight.sql");

            String clean = preflight(pg);
            assertThat(clean).contains("OK: 0.", "OK: 1.", "OK: 2.", "OK: 3.", "OK: 4.");

            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                UUID draft = UUID.randomUUID();
                UUID snapshot = UUID.randomUUID();
                update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at,"
                        + " current_artifacts) VALUES (?, 'c0000000-0000-4000-8000-000000000001',"
                        + " md5('demo:product:SOFA-3S')::uuid, 'DRAFT', NOW(), '{}'::jsonb)", draft);
                update(db, "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at,"
                        + " created_at, artifact_manifest) VALUES (?, ?, ?, 'https://example.com/p.pdf', NOW(), NOW(),"
                        + " '[]'::jsonb)", snapshot, draft, "e".repeat(64));
                update(db, "INSERT INTO production.sample_request (id, request_number, customer_id, blank_sku,"
                        + " design_snapshot_id, quantity, status) VALUES (gen_random_uuid(), 'SMP-PREFLIGHT-1',"
                        + " 'c0000000-0000-4000-8000-000000000001', 'NO-SUCH-BLANK', ?, 1, 'DRAFT')", snapshot);
                update(db, "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script,"
                        + " checksum, installed_by, installed_on, execution_time, success) VALUES"
                        + " ((SELECT max(installed_rank) + 1 FROM flyway_schema_history), '20261018000100',"
                        + " 'someone else''s', 'SQL', 'V20261018000100__x.sql', 0, 'test', NOW(), 0, true)");

                String found = preflight(pg);
                assertThat(found).contains("4. 1 sample request(s) name a blank with no inventory item")
                        .contains("0. applied version 20261018000100 is above V20261017000100")
                        .doesNotContain("NO-SUCH-BLANK");
                assertThat(number(db, "SELECT count(*) FROM production.sample_request")).as("nothing written")
                        .isEqualTo(1);
            }
        }
    }

    private static String preflight(PostgreSQLContainer<?> pg) throws Exception {
        var run = pg.execInContainer("psql", "-U", pg.getUsername(), "-d", pg.getDatabaseName(), "-X", "-q", "-f",
                "/tmp/preflight.sql");
        assertThat(run.getExitCode()).withFailMessage(run.getStderr()).isZero();
        return run.getStderr();
    }

    private static long number(Connection db, String sql) throws Exception {
        return com.stockflow.support.RawSql.number(db, sql);
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
