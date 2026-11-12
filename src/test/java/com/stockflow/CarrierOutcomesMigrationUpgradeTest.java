package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261122000100 on the real upgrade path (WF-05 fix plan items 5 and 6): the webhook calls kept before it stay as
 * they were and read as source WEBHOOK; a line of a file names its import and line, one event per line, about a load,
 * and where an event came from never changes; UNVERIFIED is a webhook's result about a load, says why, and is the error
 * list's to resolve; a second webhook credential is an API_BOOKING carrier's, sealed, beside a first; an import and its
 * lines are append-only, a refused line saying why; the warehouse manager and the administrator get the import, their
 * versions moving (ADR-0008).
 */
class CarrierOutcomesMigrationUpgradeTest {

    private static final String BEFORE = "20261117000300";
    private static final String MIGRATION = "20261122000100";
    private static final String USER = "00000000-0000-4000-8000-0000000000aa";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000000e1";
    private static final String CARRIER = "00000000-0000-4000-8000-0000000000c1";
    private static final String API_CARRIER = "00000000-0000-4000-8000-0000000000c2";
    private static final String LOAD = "00000000-0000-4000-8000-0000000000d1";
    private static final String RECEIVED = "00000000-0000-4000-8000-0000000000f1";
    private static final String REVIEWED = "00000000-0000-4000-8000-0000000000f2";
    private static final String IMPORT = "00000000-0000-4000-8000-0000000000b1";
    private static final String SEALED = "v1:AAAAAAAAAAAAAAAAAAAAAA==";

    @Test
    void upgradeKeepsWebhookEventsAndAddsFileEventsAndUnverified() throws Exception {
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
                RawSql.update(db, "INSERT INTO fulfillment.carrier (id, code, name, connection_type, webhook_enabled, "
                        + "webhook_auth_header, webhook_auth_value_sealed, api_environment, api_account, api_key_sealed) "
                        + "VALUES (?::uuid, 'AHA', 'Ahamove', 'API_BOOKING', TRUE, 'apikey', ?, 'STAGING', "
                        + "'84908000000', ?)", API_CARRIER, SEALED, SEALED);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, "
                        + "carrier_id, status, cancelled_at, cancel_reason, created_at) VALUES (?::uuid, 'LD-1', "
                        + "gen_random_uuid(), ?::uuid, ?::uuid, 'CANCELLED', NOW(), 'x', NOW())", LOAD, WAREHOUSE,
                        CARRIER);
                // Webhook calls kept before the migration: one still being handled, one for review and resolved.
                RawSql.update(db, "INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, "
                        + "payload, signature_valid, result) VALUES (?::uuid, ?::uuid, 'WL', NOW(), '{}', TRUE, "
                        + "'RECEIVED')", RECEIVED, CARRIER);
                RawSql.update(db, "INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, "
                        + "payload, signature_valid, result, result_note, decided_at, load_id, warehouse_id, "
                        + "resolved_at, resolved_by, resolution_note) VALUES (?::uuid, ?::uuid, 'WL', NOW(), '{}', TRUE, "
                        + "'NEEDS_REVIEW', 'x', NOW(), ?::uuid, ?::uuid, NOW(), ?::uuid, 'by hand')", REVIEWED, CARRIER,
                        LOAD, WAREHOUSE, USER);
                long manager = version(db, "WAREHOUSE_MANAGER");
                long admin = version(db, "SYSTEM_ADMIN");
                long staff = version(db, "WAREHOUSE_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                // Whatever lies between the baseline and this migration runs first; it is the last one applied.
                assertThat(result.migrations).extracting(m -> m.version).endsWith(MIGRATION);

                // What was there is kept, and reads as a webhook's.
                assertThat(RawSql.texts(db, "SELECT result || ':' || source || ':' || (outcome_import_id IS NULL) "
                        + "FROM fulfillment.carrier_event ORDER BY id")).containsExactly("RECEIVED:WEBHOOK:true",
                        "NEEDS_REVIEW:WEBHOOK:true");
                assertThat(RawSql.text(db, "SELECT resolution_note FROM fulfillment.carrier_event WHERE id = ?::uuid",
                        REVIEWED)).isEqualTo("by hand");
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.carrier WHERE "
                        + "webhook_auth_value_next_sealed IS NOT NULL")).isZero();

                // The import and its lines: counted, a refused line says why, an accepted one names its load.
                String header = "INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, "
                        + "uploaded_by, uploaded_at, rows_total, rows_accepted, rows_rejected) VALUES (?::uuid, ?, ?, "
                        + "?::uuid, NOW(), ?, ?, ?)";
                String digest = "a".repeat(64);
                refusedBy(db, "ck_delivery_outcome_import_rows", header, IMPORT, "x.csv", digest, USER, 2, 1, 0);
                refusedBy(db, "ck_delivery_outcome_import_rows", header, IMPORT, "x.csv", digest, USER, 0, 0, 0);
                refusedBy(db, "ck_delivery_outcome_import_digest", header, IMPORT, "x.csv", "A".repeat(64), USER,
                        2, 1, 1);
                refusedBy(db, "ck_delivery_outcome_import_name", header, IMPORT, " ", digest, USER, 2, 1, 1);
                RawSql.update(db, header, IMPORT, "x.csv", digest, USER, 2, 1, 1);
                String line = "INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, "
                        + "outcome, reported_at, load_id, verdict, problem) VALUES (gen_random_uuid(), ?::uuid, ?, ?, "
                        + "'DELIVERED', '2026-11-09 10:15', ?::uuid, ?, ?)";
                refusedBy(db, "ck_delivery_outcome_import_row_problem", line, IMPORT, 2, "WB-1", LOAD, "ERROR",
                        null);
                refusedBy(db, "ck_delivery_outcome_import_row_problem", line, IMPORT, 2, "WB-1", LOAD, "ACCEPTED",
                        "WAYBILL_NOT_FOUND");
                refusedBy(db, "ck_delivery_outcome_import_row_problem", line, IMPORT, 2, "WB-1", null, "ERROR",
                        "NO_SUCH_PROBLEM");
                refusedBy(db, "ck_delivery_outcome_import_row_verdict", line, IMPORT, 2, "WB-1", LOAD, "APPLIED",
                        null);
                refusedBy(db, "ck_delivery_outcome_import_row_load", line, IMPORT, 2, "WB-1", null, "ACCEPTED",
                        null);
                refusedBy(db, "ck_delivery_outcome_import_row_line", line, IMPORT, 1, "WB-1", LOAD, "ACCEPTED",
                        null);
                RawSql.update(db, line, IMPORT, 2, "WB-1", LOAD, "ACCEPTED", null);
                RawSql.update(db, line, IMPORT, 3, "WB-9", null, "ERROR", "WAYBILL_NOT_FOUND");
                refusedBy(db, "uk_delivery_outcome_import_row_line", line, IMPORT, 3, "WB-1", LOAD, "ACCEPTED",
                        null);
                RawSql.refusedBy(db, "append-only", "trg_delivery_outcome_import_append_only",
                        "UPDATE fulfillment.delivery_outcome_import SET rows_accepted = 2, rows_rejected = 0");
                RawSql.refusedBy(db, "append-only", "trg_delivery_outcome_import_row_append_only",
                        "DELETE FROM fulfillment.delivery_outcome_import_row");

                // A line of a file as an event: names its import and line, is about a load, one event per line.
                String event = "INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, "
                        + "payload, signature_valid, result, result_note, decided_at, load_id, warehouse_id, source, "
                        + "outcome_import_id, outcome_import_line) VALUES (?::uuid, ?::uuid, 'WL', NOW(), '{}', ?, ?, ?, "
                        + "?::timestamptz, ?::uuid, ?::uuid, ?, ?::uuid, ?)";
                String fileEvent = "00000000-0000-4000-8000-0000000000f3";
                String other = "00000000-0000-4000-8000-0000000000f9";
                refusedBy(db, "ck_carrier_event_source", event, other, CARRIER, true, "RECEIVED", null, null, LOAD,
                        WAREHOUSE, "EMAIL", null, null);
                refusedBy(db, "ck_carrier_event_import", event, other, CARRIER, true, "RECEIVED", null, null, LOAD,
                        WAREHOUSE, "FILE", null, null);
                refusedBy(db, "ck_carrier_event_import", event, other, CARRIER, true, "RECEIVED", null, null, LOAD,
                        WAREHOUSE, "WEBHOOK", IMPORT, 2);
                refusedBy(db, "ck_carrier_event_import", event, other, CARRIER, true, "RECEIVED", null, null, LOAD,
                        WAREHOUSE, "FILE", IMPORT, null);
                refusedBy(db, "ck_carrier_event_import", event, other, CARRIER, true, "RECEIVED", null, null, LOAD,
                        WAREHOUSE, "FILE", IMPORT, 1);
                refusedBy(db, "ck_carrier_event_file", event, other, CARRIER, true, "RECEIVED", null, null, null,
                        null, "FILE", IMPORT, 2);
                refusedBy(db, "fk_carrier_event_outcome_import", event, other, CARRIER, true, "RECEIVED", null,
                        null, LOAD, WAREHOUSE, "FILE", "00000000-0000-4000-8000-0000000000b9", 2);
                RawSql.update(db, event, fileEvent, CARRIER, true, "RECEIVED", null, null, LOAD, WAREHOUSE, "FILE",
                        IMPORT, 2);
                refusedBy(db, "uk_carrier_event_import_line", event, other, CARRIER, true, "RECEIVED", null, null,
                        LOAD, WAREHOUSE, "FILE", IMPORT, 2);
                // Where it came from is part of what was received.
                RawSql.refusedBy(db, "kept as it came", "carrier_event_guard", "UPDATE fulfillment.carrier_event SET "
                        + "source = 'WEBHOOK', outcome_import_id = NULL, outcome_import_line = NULL WHERE id = ?::uuid", fileEvent);
                RawSql.refusedBy(db, "kept as it came", "carrier_event_guard", "UPDATE fulfillment.carrier_event SET "
                        + "outcome_import_line = 3 WHERE id = ?::uuid", fileEvent);
                // Decided as a webhook call is, once.
                RawSql.update(db, "UPDATE fulfillment.carrier_event SET result = 'DUPLICATE', result_note = 'again', "
                        + "decided_at = NOW() WHERE id = ?::uuid", fileEvent);
                RawSql.refusedBy(db, "decided once", "carrier_event_guard", "UPDATE fulfillment.carrier_event SET result = "
                        + "'APPLIED' WHERE id = ?::uuid", fileEvent);

                // UNVERIFIED: a webhook's report about a load, with why; resolved by hand like the error list.
                refusedBy(db, "ck_carrier_event_unverified", event, other, CARRIER, true, "UNVERIFIED", "x",
                        "2026-11-09T00:00:00Z", null, null, "WEBHOOK", null, null);
                refusedBy(db, "ck_carrier_event_unverified", event, other, CARRIER, true, "UNVERIFIED", null,
                        "2026-11-09T00:00:00Z", LOAD, WAREHOUSE, "WEBHOOK", null, null);
                refusedBy(db, "ck_carrier_event_unverified", event, other, CARRIER, true, "UNVERIFIED", "x",
                        "2026-11-09T00:00:00Z", LOAD, WAREHOUSE, "FILE", IMPORT, 3);
                refusedBy(db, "ck_carrier_event_result", event, other, CARRIER, true, "VERIFIED", "x",
                        "2026-11-09T00:00:00Z", LOAD, WAREHOUSE, "WEBHOOK", null, null);
                // The call still being handled before the migration is decided UNVERIFIED after it.
                RawSql.update(db, "UPDATE fulfillment.carrier_event SET result = 'UNVERIFIED', result_note = 'the API "
                        + "says otherwise', decided_at = NOW(), load_id = ?::uuid, warehouse_id = ?::uuid "
                        + "WHERE id = ?::uuid", LOAD, WAREHOUSE, RECEIVED);
                RawSql.update(db, "UPDATE fulfillment.carrier_event SET resolved_at = NOW(), resolved_by = ?::uuid, "
                        + "resolution_note = 'asked the carrier' WHERE id = ?::uuid", USER, RECEIVED);
                refusedBy(db, "ck_carrier_event_resolvable", "UPDATE fulfillment.carrier_event SET resolved_at = "
                        + "NOW(), resolved_by = ?::uuid, resolution_note = 'x' WHERE id = ?::uuid", USER, fileEvent);

                // A second webhook credential: sealed, an API_BOOKING carrier's, beside a first.
                String next = "UPDATE fulfillment.carrier SET webhook_auth_value_next_sealed = ? WHERE id = ?::uuid";
                refusedBy(db, "ck_carrier_webhook_auth_next", next, SEALED, CARRIER);
                refusedBy(db, "ck_carrier_webhook_auth_next", next, "in-clear-0123456789", API_CARRIER);
                RawSql.update(db, next, SEALED, API_CARRIER);
                refusedBy(db, "ck_carrier_", "UPDATE fulfillment.carrier SET connection_type = 'MANUAL', "
                        + "webhook_enabled = FALSE WHERE id = ?::uuid", API_CARRIER);

                assertThat(RawSql.texts(db, "SELECT r.code || ':' || p.action FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p "
                        + "ON p.id = rp.permission_id WHERE p.resource = 'fulfillment-delivery-outcome-imports' "
                        + "ORDER BY 1")).containsExactly("SYSTEM_ADMIN:CREATE", "SYSTEM_ADMIN:READ",
                        "WAREHOUSE_MANAGER:CREATE", "WAREHOUSE_MANAGER:READ");
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(manager + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(admin + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staff);
            }
        }
    }

    /** Refused, and by the constraint or index named - not by another rule the statement happens to break too. */
    private static void refusedBy(Connection db, String rule, String sql, Object... values) {
        RawSql.refusedBy(db, rule, rule, sql, values);
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
