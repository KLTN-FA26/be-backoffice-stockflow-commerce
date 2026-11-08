package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108001600 on the real upgrade path (kltn-docs 09 §4.2 step 6, BR-29; team plan 3-4): loads booked before the
 * carrier API existed keep everything they had and hold no order at a carrier; from then on one order is in flight or
 * held per load - an unconfirmed one too, until released - one pickup point per carrier and warehouse, a booked order
 * carries the carrier's id, a token call is logged without either body and the log cannot be rewritten; a waybill change
 * of source API may name nobody, a typed one may not.
 */
class CarrierBookingMigrationUpgradeTest {

    private static final String BEFORE = "20261108001500";
    private static final String MIGRATION = "20261108001600";
    private static final String USER = "00000000-0000-4000-8000-0000000000aa";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000000e1";
    private static final String CARRIER = "00000000-0000-4000-8000-0000000000c1";
    private static final String LOAD = "00000000-0000-4000-8000-0000000000d1";

    @Test
    void upgradeAddsCarrierOrdersWithoutTouchingLoads() throws Exception {
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
                RawSql.update(db, "INSERT INTO fulfillment.carrier (id, code, name, connection_type, api_environment, "
                        + "api_account) VALUES (?::uuid, 'AHA', 'Ahamove', 'MANUAL', NULL, NULL)", CARRIER);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, "
                        + "carrier_id, status, waybill_no, cancelled_at, cancel_reason, created_at) VALUES (?::uuid, "
                        + "'LD-1', gen_random_uuid(), ?::uuid, ?::uuid, 'CANCELLED', NULL, NOW(), 'x', NOW())",
                        LOAD, WAREHOUSE, CARRIER);
                long permissions = RawSql.number(db, "SELECT count(*) FROM identity.role_permission");
                long roles = RawSql.number(db, "SELECT sum(version) FROM identity.app_role");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // Nothing about the loads or the grants changes; no load holds an order.
                assertThat(RawSql.text(db, "SELECT status || ':' || code FROM fulfillment.dispatch_load WHERE id = "
                        + "?::uuid", LOAD)).isEqualTo("CANCELLED:LD-1");
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.carrier_booking")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.role_permission")).isEqualTo(permissions);
                assertThat(RawSql.number(db, "SELECT sum(version) FROM identity.app_role")).isEqualTo(roles);

                // One pickup point per carrier and warehouse.
                String pickup = "INSERT INTO fulfillment.carrier_pickup_point (id, carrier_id, warehouse_id, "
                        + "province_code, address) VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?, ?)";
                RawSql.update(db, pickup, CARRIER, WAREHOUSE, "79", "1 Lê Lợi, Bến Nghé, Quận 1, Hồ Chí Minh");
                RawSql.refused(db, "uk_carrier_pickup_point", pickup, CARRIER, WAREHOUSE, "79", "2 Lê Lợi");
                RawSql.refused(db, "ck_carrier_pickup_point_address", "UPDATE fulfillment.carrier_pickup_point "
                        + "SET address = '  '");
                RawSql.refused(db, "ck_carrier_pickup_point_province", "UPDATE fulfillment.carrier_pickup_point "
                        + "SET province_code = 'Hồ Chí Minh'");

                // One order in flight or held per load; a failed one does not hold it.
                String booking = "INSERT INTO fulfillment.carrier_booking (id, load_id, carrier_id, service_code, "
                        + "stops, status, carrier_order_id, booked_at, last_error, requested_by, requested_at) VALUES "
                        + "(gen_random_uuid(), ?::uuid, ?::uuid, 'SGN-TRUCK-1000', ?::jsonb, ?, ?, ?::timestamptz, ?, "
                        + "?::uuid, NOW())";
                String stops = "[{\"seq\":1,\"shipmentCode\":\"SO-1-S1\"}]";
                RawSql.update(db, booking, LOAD, CARRIER, stops, "FAILED", null, null, "406 refused", USER);
                RawSql.update(db, booking, LOAD, CARRIER, stops, "REQUESTED", null, null, null, USER);
                RawSql.refused(db, "uk_carrier_booking_live", booking, LOAD, CARRIER, stops, "BOOKED", "AHA-1",
                        "2026-11-09T01:00:00Z", null, USER);
                // A booked order has the carrier's id; a failure says why; the stops are a list.
                RawSql.refused(db, "ck_carrier_booking_booked", "UPDATE fulfillment.carrier_booking SET status = "
                        + "'BOOKED' WHERE status = 'REQUESTED'");
                RawSql.refused(db, "ck_carrier_booking_failed", "UPDATE fulfillment.carrier_booking SET status = "
                        + "'FAILED' WHERE status = 'REQUESTED'");
                RawSql.refused(db, "ck_carrier_booking_stops", "UPDATE fulfillment.carrier_booking SET stops = '[]'");
                RawSql.refused(db, "ck_carrier_booking_cancelled", "UPDATE fulfillment.carrier_booking SET "
                        + "cancelled_at = NOW() WHERE status = 'FAILED'");
                RawSql.update(db, "UPDATE fulfillment.carrier_booking SET status = 'BOOKED', carrier_order_id = "
                        + "'AHA-1', booked_at = NOW() WHERE status = 'REQUESTED'");
                RawSql.refused(db, "uk_carrier_booking_order", booking, LOAD, CARRIER, stops, "CANCELLED", "AHA-1",
                        "2026-11-09T01:00:00Z", null, USER);
                // A call that did not come back holds the load until released, and says why.
                RawSql.update(db, "UPDATE fulfillment.carrier_booking SET status = 'UNCONFIRMED', carrier_order_id = "
                        + "NULL, booked_at = NULL, last_error = 'no answer' WHERE status = 'BOOKED'");
                RawSql.refused(db, "uk_carrier_booking_live", booking, LOAD, CARRIER, stops, "REQUESTED", null, null,
                        null, USER);
                RawSql.refused(db, "ck_carrier_booking_failed", "UPDATE fulfillment.carrier_booking SET last_error = "
                        + "NULL WHERE status = 'UNCONFIRMED'");
                RawSql.update(db, "UPDATE fulfillment.carrier_booking SET status = 'FAILED' WHERE status = "
                        + "'UNCONFIRMED'");
                RawSql.update(db, booking, LOAD, CARRIER, stops, "REQUESTED", null, null, null, USER);

                // The system dropping a carrier order names nobody; a typed change does.
                String change = "INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, "
                        + "source, changed_by, changed_at) VALUES (gen_random_uuid(), ?::uuid, 'AHA-1', NULL, ?, "
                        + "?::uuid, NOW())";
                RawSql.update(db, change, LOAD, "API", null);
                RawSql.refused(db, "ck_waybill_change_who", change, LOAD, "MANUAL", null);

                // The call log: a token call keeps neither body, and nothing is rewritten.
                String call = "INSERT INTO fulfillment.carrier_api_call (id, carrier_id, operation, outcome, "
                        + "http_status, request_body, response_body, duration_ms, called_at) VALUES "
                        + "(gen_random_uuid(), ?::uuid, ?, ?, ?, ?, ?, 12, NOW())";
                RawSql.update(db, call, CARRIER, "TOKEN", "OK", 200, null, null);
                RawSql.update(db, call, CARRIER, "BOOK", "NO_ANSWER", null, "{}", null);
                RawSql.refused(db, "ck_carrier_api_call_token_body", call, CARRIER, "TOKEN", "OK", 200,
                        "{\"api_key\":\"k\"}", null);
                RawSql.refused(db, "ck_carrier_api_call_token_body", call, CARRIER, "TOKEN", "OK", 200, null,
                        "{\"token\":\"t\"}");
                RawSql.refused(db, "ck_carrier_api_call_answered", call, CARRIER, "BOOK", "OK", null, "{}", null);
                RawSql.refused(db, "trg_carrier_api_call_append_only", "DELETE FROM fulfillment.carrier_api_call");
                RawSql.refused(db, "trg_carrier_api_call_append_only", "UPDATE fulfillment.carrier_api_call SET "
                        + "duration_ms = 0");
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
