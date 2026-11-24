package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261224000200 on the real upgrade path (WF-05 audit r3 EINV-1, confirmation CF-1). The migration backfills
 * {@code undone_delivered_at} on corrections of a delivery to a failure made before it - rows of an append-only table,
 * whose {@code trg_delivery_outcome_correction_append_only} refuses every UPDATE. A database holding no such correction
 * (every test, every QA script, a fresh demo seed) never reaches the backfill's rows, so only a database that already
 * holds one shows whether Flyway gets past it: this test is that database.
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}) only so that the shipment and
 * the user they name need not exist; the migration itself runs on Flyway's own connection, with every trigger on.</p>
 */
class CorrectionUndoneDeliveryMigrationUpgradeTest {

    private static final String BEFORE = "20261224000110";
    private static final String MIGRATION = "20261224000200";
    private static final String SHIPMENT = "00000000-0000-4000-8000-00000000e1a1";
    private static final String UNDATED_SHIPMENT = "00000000-0000-4000-8000-00000000e1a2";
    private static final String TO_FAILURE = "00000000-0000-4000-8000-00000000e1b1";
    private static final String BACK = "00000000-0000-4000-8000-00000000e1b2";
    private static final String UNDATED = "00000000-0000-4000-8000-00000000e1b3";
    private static final String USER = "00000000-0000-4000-8000-00000000e1c1";

    @Test
    void aCorrectionOfADeliveryMadeBeforeTheMigrationIsBackfilledAndTheTableStaysAppendOnly() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT id || ':' || coalesce(to_char(undone_delivered_at AT TIME ZONE "
                        + "'UTC', 'YYYY-MM-DD HH24:MI'), '-') FROM fulfillment.delivery_outcome_correction ORDER BY id"))
                        .as("the correction to a failure names the delivery it undid (the latest DELIVERED step at or "
                                + "before it, not the later one); the correction back names none; a timeline with no "
                                + "delivery leaves it NULL")
                        .containsExactly(TO_FAILURE + ":2026-12-01 10:00", BACK + ":-", UNDATED + ":-");

                // The guard the backfill stood aside from is on again, for every statement after the migration.
                assertThat(RawSql.text(db, "SELECT tgenabled FROM pg_trigger WHERE tgname = "
                        + "'trg_delivery_outcome_correction_append_only'")).isEqualTo("O");
                RawSql.refusedBy(db, "is append-only", "an update of a correction after the migration",
                        "UPDATE fulfillment.delivery_outcome_correction SET reason = 'rewritten' WHERE id = ?::uuid",
                        TO_FAILURE);
                // The rules the migration added hold for new rows.
                String correction = "INSERT INTO fulfillment.delivery_outcome_correction (id, shipment_id, from_status, "
                        + "to_status, failure_reason, reason, corrected_by, corrected_at, undone_delivered_at) VALUES "
                        + "(gen_random_uuid(), ?::uuid, 'DELIVERED', 'DELIVERY_FAILED', 'REFUSED', 'Wrong outcome', "
                        + "?::uuid, TIMESTAMPTZ '2026-12-10 09:00Z', %s)";
                // A CHECK refuses before the foreign keys (after-row triggers) look at the shipment and the user.
                RawSql.refusedBy(db, "ck_delivery_outcome_correction_undone_kept",
                        "a new correction of a delivery naming no delivery", correction.formatted("NULL"), SHIPMENT, USER);
                RawSql.refusedBy(db, "ck_delivery_outcome_correction_undone",
                        "a delivery undone after the correction", correction.formatted("TIMESTAMPTZ '2026-12-11 09:00Z'"),
                        SHIPMENT, USER);
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        String event = "INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source) VALUES "
                + "(gen_random_uuid(), ?::uuid, ?, ?::timestamptz, 'MANUAL')";
        RawSql.update(db, event, SHIPMENT, "OUT_FOR_DELIVERY", "2026-12-01 08:00Z");
        RawSql.update(db, event, SHIPMENT, "DELIVERED", "2026-12-01 10:00Z");
        RawSql.update(db, event, SHIPMENT, "DELIVERY_FAILED", "2026-12-01 10:00Z");
        // Found delivered after all, later: not the delivery the first correction undid.
        RawSql.update(db, event, SHIPMENT, "DELIVERED", "2026-12-03 15:00Z");
        String correction = "INSERT INTO fulfillment.delivery_outcome_correction (id, shipment_id, from_status, "
                + "to_status, failure_reason, reason, corrected_by, corrected_at) VALUES (?::uuid, ?::uuid, ?, ?, ?, "
                + "'Recorded wrongly', ?::uuid, ?::timestamptz)";
        RawSql.update(db, correction, TO_FAILURE, SHIPMENT, "DELIVERED", "DELIVERY_FAILED", "REFUSED", USER,
                "2026-12-02 09:00Z");
        RawSql.update(db, correction, BACK, SHIPMENT, "DELIVERY_FAILED", "DELIVERED", null, USER, "2026-12-03 15:30Z");
        // A delivery corrected to a failure whose timeline shows no delivery step.
        RawSql.update(db, correction, UNDATED, UNDATED_SHIPMENT, "DELIVERED", "DELIVERY_FAILED", "LOST", USER,
                "2026-12-02 09:00Z");
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
