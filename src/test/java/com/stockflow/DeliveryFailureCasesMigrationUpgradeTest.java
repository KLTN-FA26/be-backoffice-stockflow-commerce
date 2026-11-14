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
 * V20261121000300 on the real upgrade path (WF-05 fix plan, sections 2-3). It rewrites CHECKs of
 * {@code ordering.customer_order}, {@code ordering.order_hold} and {@code payment.installment}, widens the unique key of
 * {@code fulfillment.pick} and replaces three payment trigger functions. Rows written before it must pass the rewritten
 * rules as they are - an order on hold for another reason, an order RETURNED by its shipment (not backfilled: it stays),
 * a cancelled order, a cancellation fee item, an order's one pick - and the new rules must hold from then on.
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the
 * migration on rows the old rules accepted. What the application does with the new tables is
 * {@code FtlDispatchIntegrationTest}'s delivery failure scenarios; every CHECK and trigger of the case table is
 * {@code tools/db/qa/90_delivery_failure_cases.sql}'s.</p>
 */
class DeliveryFailureCasesMigrationUpgradeTest {

    private static final String BEFORE = "20261121000200";
    private static final String MIGRATION = "20261121000300";
    private static final String CUSTOMER = "00000000-0000-4000-8000-00000000d0a0";
    private static final String HELD = "00000000-0000-4000-8000-00000000d0a1";
    private static final String RETURNED = "00000000-0000-4000-8000-00000000d0a2";
    private static final String CANCELLED = "00000000-0000-4000-8000-00000000d0a3";
    private static final String CONTRACT = "00000000-0000-4000-8000-00000000d0b1";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-00000000d0c1";

    @Test
    void rowsWrittenBeforeStayValidAndTheNewRulesHold() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT order_number || ':' || status || ':' || coalesce(hold_reason, '-') "
                        + "FROM ordering.customer_order WHERE customer_id = ?::uuid ORDER BY order_number", CUSTOMER))
                        .as("no order was moved: an order RETURNED by its shipment before this stays RETURNED")
                        .containsExactly("SO-UPG-D1:ON_HOLD:ADDRESS", "SO-UPG-D2:RETURNED:-", "SO-UPG-D3:CANCELLED:-");
                // The rules this migration rewrote, each validated on the rows that were there (ck_order_hold_reason is
                // on the order and on its holds: eight in all).
                assertThat(RawSql.texts(db, "SELECT conname || ':' || convalidated FROM pg_constraint WHERE conname IN "
                        + "('ck_order_hold_reason', 'ck_order_delivery_failed_hold', 'ck_order_cancellation_code_values', "
                        + "'ck_installment_milestone', 'ck_installment_seq', 'ck_installment_fee_item', "
                        + "'uk_fulfillment_pick_order_batch') ORDER BY conname"))
                        .hasSize(8).allMatch(rule -> rule.endsWith(":true"));
                assertThat(RawSql.texts(db, "SELECT seq || ':' || milestone || ':' || status FROM payment.installment "
                        + "ORDER BY seq")).containsExactly("1:ON_SIGNING:DUE", "21:CANCELLATION_FEE:DUE");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'ordering' AND table_name LIKE 'delivery_failure_case%'")).isEqualTo(3);
                assertThat(RawSql.texts(db, "SELECT from_status FROM ordering.order_status_transition WHERE to_status = "
                        + "'ON_HOLD' AND from_status IN ('SHIPPED', 'DELIVERY_FAILED') ORDER BY 1"))
                        .containsExactly("DELIVERY_FAILED", "SHIPPED");

                // The hold for a failed delivery: known from now on, and always held from PARTIALLY_FULFILLED.
                RawSql.refusedBy(db, "ck_order_delivery_failed_hold", "held for a failed delivery from PICKING",
                        "UPDATE ordering.customer_order SET hold_reason = 'DELIVERY_FAILED' WHERE id = ?::uuid", HELD);
                assertThat(RawSql.update(db, "UPDATE ordering.customer_order SET hold_reason = 'DELIVERY_FAILED', "
                        + "status_before_hold = 'PARTIALLY_FULFILLED' WHERE id = ?::uuid", HELD)).isEqualTo(1);
                // One hold of an order is open at a time (uk_order_active_hold): the earlier one gives way.
                assertThat(RawSql.update(db, "UPDATE ordering.order_hold SET resolved_at = NOW(), resolution_note = "
                        + "'Superseded: a shipment came back', resolved_by = (SELECT id FROM identity.app_user LIMIT 1) "
                        + "WHERE order_id = ?::uuid", HELD)).isEqualTo(1);
                assertThat(RawSql.update(db, "INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, "
                        + "created_at) VALUES (gen_random_uuid(), ?::uuid, 'DELIVERY_FAILED', NOW(), NOW())", HELD))
                        .isEqualTo(1);
                RawSql.refusedBy(db, "ck_order_hold_reason", "an unknown hold reason", "INSERT INTO ordering.order_hold "
                        + "(id, order_id, reason, raised_at, created_at) VALUES (gen_random_uuid(), ?::uuid, 'CARRIER', "
                        + "NOW(), NOW())", RETURNED);
                RawSql.refusedBy(db, "ck_order_cancellation_code_values", "an unknown cancellation reason",
                        "UPDATE ordering.customer_order SET cancellation_reason_code = 'LOST_IN_TRANSIT' "
                                + "WHERE id = ?::uuid", CANCELLED);

                // One first pick per order still; the widened key lets a redelivery have its own.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.refusedBy(db, "uk_fulfillment_pick_order_batch", "a second first pick of the order",
                        "INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at) "
                                + "VALUES (gen_random_uuid(), ?::uuid, 'SO-UPG-D1', ?::uuid, 'PENDING', NOW())", HELD,
                        WAREHOUSE);
                assertThat(RawSql.update(db, "INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, "
                        + "status, created_at, redelivery_case_id) VALUES (gen_random_uuid(), ?::uuid, 'SO-UPG-D1', "
                        + "?::uuid, 'PENDING', NOW(), gen_random_uuid())", HELD, WAREHOUSE)).isEqualTo(1);

                // The fee item of a failed trip is a fee item, as the cancellation fee item is.
                String feeItem = "INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, "
                        + "customer_id, contract_number, order_number, seq, name, milestone, amount, paid_amount, "
                        + "currency, due_days, reached_at, due_date, status) VALUES (gen_random_uuid(), ?::uuid, %s, "
                        + "?::uuid, ?::uuid, 'HD-UPG-D', 'SO-UPG-D1', %d, 'Failed delivery', 'FAILED_TRIP_FEE', 850000, "
                        + "0, 'VND', 0, NOW(), CURRENT_DATE, 'DUE')";
                RawSql.refusedBy(db, "ck_installment_seq", "a fee item numbered inside the plan",
                        feeItem.formatted("NULL", 5), CONTRACT, HELD, CUSTOMER);
                RawSql.refusedBy(db, "ck_installment_fee_item", "a fee item naming a plan row",
                        feeItem.formatted("gen_random_uuid()", 22), CONTRACT, HELD, CUSTOMER);
                assertThat(RawSql.update(db, feeItem.formatted("NULL", 22), CONTRACT, HELD, CUSTOMER)).isEqualTo(1);
                RawSql.update(db, "SET session_replication_role = origin");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        String order = "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at, hold_reason, status_before_hold, "
                + "cancellation_reason_code, cancellation_reason) VALUES (?::uuid, ?, ?::uuid, gen_random_uuid(), ?, "
                + "1000000, 'VND', NOW(), NOW(), ?, ?, ?, ?)";
        // On hold for another reason, held from PICKING.
        RawSql.update(db, order, HELD, "SO-UPG-D1", CUSTOMER, "ON_HOLD", "ADDRESS", "PICKING", null, null);
        // RETURNED by its shipment, as the old lifecycle left it.
        RawSql.update(db, order, RETURNED, "SO-UPG-D2", CUSTOMER, "RETURNED", null, null, null, null);
        RawSql.update(db, order, CANCELLED, "SO-UPG-D3", CUSTOMER, "CANCELLED", null, null, "CUSTOMER_REQUEST",
                "CUSTOMER_REQUEST");
        RawSql.update(db, "INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, created_at) VALUES "
                + "(gen_random_uuid(), ?::uuid, 'ADDRESS', NOW(), NOW())", HELD);
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, created_at) VALUES "
                + "(gen_random_uuid(), 'upg.dfail', 'upg.dfail@example.test', 'ACTIVE', NOW())");
        RawSql.update(db, "INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at) "
                + "VALUES (gen_random_uuid(), ?::uuid, 'SO-UPG-D1', ?::uuid, 'PICKING', NOW())", HELD, WAREHOUSE);
        String installment = "INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, "
                + "customer_id, contract_number, order_number, seq, name, milestone, amount, paid_amount, currency, "
                + "due_days, reached_at, due_date, status) VALUES (gen_random_uuid(), ?::uuid, %s, ?::uuid, ?::uuid, "
                + "'HD-UPG-D', 'SO-UPG-D1', ?, 'Row', ?, 1000000, 0, 'VND', 0, NOW(), CURRENT_DATE, 'DUE')";
        RawSql.update(db, installment.formatted("gen_random_uuid()"), CONTRACT, HELD, CUSTOMER, 1, "ON_SIGNING");
        // A cancellation fee item: no plan row, numbered after the plan.
        RawSql.update(db, installment.formatted("NULL"), CONTRACT, HELD, CUSTOMER, 21, "CANCELLATION_FEE");
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
