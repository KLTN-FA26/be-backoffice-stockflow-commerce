package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108001700 on the real upgrade path (kltn-docs 09 §4.5, BR-27; team plan 3-5): nothing there before changes;
 * every webhook call is kept as received and decided once - a bad credential is the error list's and matches nothing,
 * what was applied names its load, a repeat applies once - the error list is resolved once, with a note, and nothing is
 * deleted; one milestone of each kind per load, append-only; the warehouse manager and the administrator get the error
 * list, their versions moving (ADR-0008).
 */
class CarrierEventsMigrationUpgradeTest {

    private static final String BEFORE = "20261108001600";
    private static final String MIGRATION = "20261108001700";
    private static final String USER = "00000000-0000-4000-8000-0000000000aa";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000000e1";
    private static final String CARRIER = "00000000-0000-4000-8000-0000000000c1";
    private static final String LOAD = "00000000-0000-4000-8000-0000000000d1";
    private static final String EVENT = "00000000-0000-4000-8000-0000000000f1";

    @Test
    void upgradeKeepsCarrierEventsAsReceived() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                        + "created_at) VALUES (?::uuid, 'qa', 'qa@example.com', '!x', 'ACTIVE', 0, NOW())", USER);
                RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, "
                        + "map_width, map_height, version, created_at) VALUES (?::uuid, 'QA', 'ACTIVE', 'QAW', '1 QA', "
                        + "'M', 10, 10, 0, NOW())", WAREHOUSE);
                RawSql.update(db, "INSERT INTO fulfillment.carrier (id, code, name) VALUES (?::uuid, 'WL', 'Xe')",
                        CARRIER);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, "
                        + "carrier_id, status, cancelled_at, cancel_reason, created_at) VALUES (?::uuid, 'LD-1', "
                        + "gen_random_uuid(), ?::uuid, ?::uuid, 'CANCELLED', NOW(), 'x', NOW())", LOAD, WAREHOUSE,
                        CARRIER);
                long manager = version(db, "WAREHOUSE_MANAGER");
                long admin = version(db, "SYSTEM_ADMIN");
                long staff = version(db, "WAREHOUSE_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.dispatch_load WHERE id = ?::uuid", LOAD))
                        .isEqualTo("CANCELLED");

                String call = "INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, "
                        + "payload, signature_valid, result, result_note, decided_at, load_id, warehouse_id) VALUES "
                        + "(?::uuid, ?::uuid, 'WL', NOW(), '{}', ?, ?, ?, ?::timestamptz, ?::uuid, ?::uuid)";
                // A bad credential is the error list's and matches nothing; an unknown carrier's call is one too.
                RawSql.refused(db, "ck_carrier_event_signature", call, "00000000-0000-4000-8000-0000000000f9", CARRIER,
                        false, "UNMATCHED", "x", "2026-11-09T00:00:00Z", null, null);
                RawSql.refused(db, "ck_carrier_event_signature", call, "00000000-0000-4000-8000-0000000000f9", CARRIER,
                        false, "INVALID_SIGNATURE", "x", "2026-11-09T00:00:00Z", LOAD, WAREHOUSE);
                RawSql.refused(db, "ck_carrier_event_unknown_carrier", call, "00000000-0000-4000-8000-0000000000f9",
                        null, true, "RECEIVED", null, null, null, null);
                // Applied, repeated or outdated names its load; decided means when.
                RawSql.refused(db, "ck_carrier_event_matched", call, "00000000-0000-4000-8000-0000000000f9", CARRIER,
                        true, "APPLIED", null, "2026-11-09T00:00:00Z", null, null);
                RawSql.refused(db, "ck_carrier_event_decided", call, "00000000-0000-4000-8000-0000000000f9", CARRIER,
                        true, "IGNORED", null, null, null, null);
                RawSql.update(db, call, EVENT, CARRIER, true, "RECEIVED", null, null, null, null);

                // Decided once: what was received never changes, nor does the decision.
                RawSql.update(db, "UPDATE fulfillment.carrier_event SET result = 'NEEDS_REVIEW', result_note = 'x', "
                        + "decided_at = NOW(), load_id = ?::uuid, warehouse_id = ?::uuid WHERE id = ?::uuid", LOAD,
                        WAREHOUSE, EVENT);
                RawSql.refused(db, "carrier_event_guard", "UPDATE fulfillment.carrier_event SET result = 'APPLIED' "
                        + "WHERE id = ?::uuid", EVENT);
                RawSql.refused(db, "carrier_event_guard", "UPDATE fulfillment.carrier_event SET payload = '{\"x\":1}' "
                        + "WHERE id = ?::uuid", EVENT);
                // Resolved once, by someone, with a note; never deleted.
                RawSql.refused(db, "ck_carrier_event_resolution", "UPDATE fulfillment.carrier_event SET resolved_at = "
                        + "NOW(), resolution_note = 'x' WHERE id = ?::uuid", EVENT);
                RawSql.update(db, "UPDATE fulfillment.carrier_event SET resolved_at = NOW(), resolved_by = ?::uuid, "
                        + "resolution_note = 'recorded by hand' WHERE id = ?::uuid", USER, EVENT);
                RawSql.refused(db, "carrier_event_guard", "UPDATE fulfillment.carrier_event SET resolution_note = "
                        + "'later' WHERE id = ?::uuid", EVENT);
                RawSql.refused(db, "carrier_event_guard", "DELETE FROM fulfillment.carrier_event");

                // Two copies of a report: one applies.
                String applied = "INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, "
                        + "payload, signature_valid, result, decided_at, load_id, warehouse_id, dedupe_key) VALUES "
                        + "(gen_random_uuid(), ?::uuid, 'WL', NOW(), '{}', true, ?, NOW(), ?::uuid, ?::uuid, 'k1')";
                RawSql.update(db, applied, CARRIER, "APPLIED", LOAD, WAREHOUSE);
                RawSql.refused(db, "uk_carrier_event_applied", applied, CARRIER, "APPLIED", LOAD, WAREHOUSE);
                RawSql.update(db, applied, CARRIER, "DUPLICATE", LOAD, WAREHOUSE);
                // Only the error list is resolved.
                RawSql.refused(db, "ck_carrier_event_resolvable", "UPDATE fulfillment.carrier_event SET resolved_at = "
                        + "NOW(), resolved_by = ?::uuid, resolution_note = 'x' WHERE result = 'APPLIED'", USER);

                // One milestone of each kind per load, from an event; append-only.
                String milestone = "INSERT INTO fulfillment.tracking_milestone (id, load_id, milestone, "
                        + "carrier_reported_at, source, carrier_event_id, recorded_at) VALUES (gen_random_uuid(), "
                        + "?::uuid, ?, NOW(), ?, ?::uuid, NOW())";
                RawSql.update(db, milestone, LOAD, "PICKED_UP", "CARRIER_WEBHOOK", EVENT);
                RawSql.refused(db, "uk_tracking_milestone", milestone, LOAD, "PICKED_UP", "CARRIER_WEBHOOK", EVENT);
                RawSql.refused(db, "ck_tracking_milestone_event", milestone, LOAD, "IN_TRANSIT", "CARRIER_WEBHOOK",
                        null);
                RawSql.refused(db, "ck_tracking_milestone_kind", milestone, LOAD, "DELIVERED", "CARRIER_WEBHOOK", EVENT);
                RawSql.refused(db, "trg_tracking_milestone_append_only", "DELETE FROM fulfillment.tracking_milestone");

                assertThat(RawSql.texts(db, "SELECT r.code || ':' || p.action FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p "
                        + "ON p.id = rp.permission_id WHERE p.resource = 'fulfillment-carrier-events' ORDER BY 1"))
                        .containsExactly("SYSTEM_ADMIN:READ", "SYSTEM_ADMIN:UPDATE", "WAREHOUSE_MANAGER:READ",
                                "WAREHOUSE_MANAGER:UPDATE");
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(manager + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(admin + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staff);
            }
        }
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
