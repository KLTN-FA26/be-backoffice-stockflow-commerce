package com.stockflow.order;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261021000100 (G3-2) on the real upgrade path: a database at develop's last migration holding orders in every
 * status the old lifecycle had - IN_FULFILMENT with and without a completed package, ON_HOLD for credit, for a design
 * mismatch and without any hold record, SHIPPED / DELIVERED / COMPLETED with their history, a delivered credit order
 * with a part-paid receivable - then upgraded. Proves every backfill, that the new CHECKs hold on old rows, and that the
 * transition table and trigger arrive with the edges of {@link OrderStatus}.
 */
class OrderLifecycleMigrationUpgradeTest {

    private static final String BEFORE = "20261020000800";
    private static final String MIGRATION = "20261021000100";

    @Test
    void upgradeMovesEveryOldStatusAndBackfillsTheLifecycle() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID customer = UUID.randomUUID();
                exec(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                        + "VALUES ('" + customer + "', 'Old customer', 'old@example.com', 'ACTIVE', 0, NOW())");
                UUID warehouse = UUID.randomUUID();
                exec(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, "
                        + "map_height, version, created_at) VALUES ('" + warehouse + "', 'Old', 'ACTIVE', 'OLD', 'x', 'M', "
                        + "10, 10, 0, NOW())");

                UUID picking = order(db, customer, "IN_FULFILMENT", "PREPAID");
                UUID packedByPack = order(db, customer, "IN_FULFILMENT", "PREPAID");
                UUID pick = UUID.randomUUID();
                exec(db, "INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at) "
                        + "SELECT '" + pick + "', id, order_number, '" + warehouse + "', 'PICKED', NOW() "
                        + "FROM ordering.customer_order WHERE id = '" + packedByPack + "'");
                exec(db, "INSERT INTO fulfillment.pack (id, pick_id, status, packed_at, created_at) "
                        + "VALUES (gen_random_uuid(), '" + pick + "', 'PACKED', NOW(), NOW())");
                UUID packedByFact = order(db, customer, "IN_FULFILMENT", "PREPAID");
                exec(db, "UPDATE ordering.customer_order SET packed_at = NOW() WHERE id = '" + packedByFact + "'");

                UUID creditHold = order(db, customer, "ON_HOLD", "CREDIT");
                hold(db, creditHold, "CREDIT");
                UUID designHold = order(db, customer, "ON_HOLD", "PREPAID");
                hold(db, designHold, "DESIGN_MISMATCH");
                UUID bareHold = order(db, customer, "ON_HOLD", "PREPAID");

                UUID shipped = order(db, customer, "SHIPPED", "PREPAID");
                exec(db, "INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, occurred_at) "
                        + "VALUES (gen_random_uuid(), '" + shipped + "', 'IN_FULFILMENT', 'SHIPPED', "
                        + "TIMESTAMPTZ '2026-10-15 10:00:00+07')");
                UUID completed = order(db, customer, "COMPLETED", "PREPAID");
                UUID deliveredCredit = order(db, customer, "DELIVERED", "CREDIT");
                exec(db, "UPDATE ordering.customer_order SET paid_amount = 400000 WHERE id = '" + deliveredCredit + "'");
                exec(db, "INSERT INTO payment.receivable (id, order_id, customer_id, amount, paid_amount, currency, "
                        + "issued_at, due_date, status) VALUES (gen_random_uuid(), '" + deliveredCredit + "', '" + customer
                        + "', 1000000, 400000, 'VND', NOW(), CURRENT_DATE + 30, 'PARTIALLY_PAID')");
                UUID otherHold = UUID.randomUUID();

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).contains(MIGRATION);

                assertThat(status(db, picking)).isEqualTo("PICKING");
                assertThat(status(db, packedByPack)).isEqualTo("PACKED");
                assertThat(status(db, packedByFact)).isEqualTo("PACKED");
                assertThat(RawSql.texts(db, "SELECT hold_reason || '/' || COALESCE(status_before_hold, '-') "
                        + "FROM ordering.customer_order WHERE id IN ('" + creditHold + "', '" + designHold + "', '"
                        + bareHold + "') ORDER BY order_number"))
                        .containsExactlyInAnyOrder("CREDIT/-", "DESIGN_MISMATCH/PICKING", "OTHER/CONFIRMED");
                assertThat(RawSql.texts(db, "SELECT to_char(shipped_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24') "
                        + "FROM ordering.customer_order WHERE id = '" + shipped + "'")).containsExactly("2026-10-15 10");
                assertThat(scalar(db, "SELECT count(*) FROM ordering.customer_order WHERE id = '" + completed
                        + "' AND shipped_at IS NOT NULL AND delivered_at IS NOT NULL AND completed_at IS NOT NULL"))
                        .isEqualTo(1);
                assertThat(RawSql.texts(db, "SELECT billed_amount::bigint || '/' || receivable_paid_amount::bigint "
                        + "FROM ordering.customer_order WHERE id = '" + deliveredCredit + "'")).containsExactly("1000000/400000");
                assertThat(scalar(db, "SELECT count(*) FROM ordering.order_status_history WHERE to_status = 'IN_FULFILMENT' "
                        + "OR from_status = 'IN_FULFILMENT'")).as("history is append-only and keeps the old word").isEqualTo(1);

                // The transition table holds exactly the edges of the enum.
                assertThat(RawSql.texts(db, "SELECT from_status || '>' || to_status FROM ordering.order_status_transition"))
                        .containsExactlyInAnyOrderElementsOf(OrderStatus.edges().stream()
                                // V20261022000400 (PACK-EARLY) adds these two later.
                                .filter(edge -> !edge.equals("IN_PRODUCTION>PARTIALLY_FULFILLED")
                                        && !edge.equals("READY_TO_FULFILL>PARTIALLY_FULFILLED")).toList());

                // The trigger: an edge is accepted, a jump is not, a hold goes back only where it came from.
                exec(db, "UPDATE ordering.customer_order SET status = 'PACKED', packed_at = NOW() WHERE id = '" + picking + "'");
                assertThatThrownBy(() -> exec(db, "UPDATE ordering.customer_order SET status = 'DELIVERED', "
                        + "delivered_at = NOW(), shipped_at = NOW() WHERE id = '" + picking + "'"))
                        .hasMessageContaining("cannot move from PACKED to DELIVERED");
                assertThatThrownBy(() -> exec(db, "UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL', "
                        + "hold_reason = NULL, status_before_hold = NULL, released_at = NOW(), warehouse_id = '" + warehouse
                        + "' WHERE id = '" + designHold + "'"))
                        .hasMessageContaining("leaves its hold for READY_TO_FULFILL");
                exec(db, "UPDATE ordering.customer_order SET status = 'PICKING', hold_reason = NULL, "
                        + "status_before_hold = NULL, hold_note = NULL WHERE id = '" + designHold + "'");
                exec(db, "UPDATE ordering.customer_order SET status = 'CONFIRMED', hold_reason = NULL "
                        + "WHERE id = '" + creditHold + "'");
                assertThatThrownBy(() -> exec(db, "UPDATE ordering.customer_order SET status = 'IN_FULFILMENT' "
                        + "WHERE id = '" + otherHold + "' OR id = '" + shipped + "'"))
                        .hasMessageContaining("cannot move from SHIPPED to IN_FULFILMENT");
                // An unknown hold reason on an old record became OTHER with its word kept: none here, so a new one fails.
                assertThatThrownBy(() -> hold(db, picking, "FOO")).hasMessageContaining("ck_order_hold_reason");
            }
        }
    }

    private static UUID order(Connection db, UUID customer, String status, String term) throws Exception {
        UUID id = UUID.randomUUID();
        String number = "SO-OLD-" + id.toString().substring(0, 8);
        exec(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, "
                + "currency, placed_at, version, created_at, last_modified_at, payment_term, credit_term_days, paid_amount, "
                + "paid_in_full_at) VALUES ('" + id + "', '" + number + "', '" + customer + "', gen_random_uuid(), '"
                + status + "', 1000000, 'VND', NOW() - interval '10 days', 0, NOW(), NOW() - interval '1 day', '" + term
                + "', " + ("CREDIT".equals(term) ? "30" : "NULL") + ", " + ("CREDIT".equals(term) ? "0, NULL" : "1000000, NOW()")
                + ")");
        return id;
    }

    private static void hold(Connection db, UUID order, String reason) throws Exception {
        exec(db, "INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, version, created_at) "
                + "VALUES (gen_random_uuid(), '" + order + "', '" + reason + "', NOW(), 0, NOW())");
    }

    private static String status(Connection db, UUID order) throws Exception {
        return RawSql.texts(db, "SELECT status FROM ordering.customer_order WHERE id = '" + order + "'").getFirst();
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static long scalar(Connection db, String sql) throws Exception {
        return RawSql.number(db, sql);
    }

    private static void exec(Connection db, String sql) throws Exception {
        RawSql.update(db, sql);
    }
}
