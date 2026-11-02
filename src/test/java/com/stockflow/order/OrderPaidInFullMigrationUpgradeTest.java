package com.stockflow.order;

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
 * V20261102030100 (follow-up of #196, SHORT-LIMITS) on the real upgrade path: a database at develop's last migration
 * before it, holding orders whose units were closed - paid what they are now worth, paid short of it, paid the worth but
 * not the fee a closure agreed - and one nothing was closed out of, then upgraded. Proves {@code closed_amount} is
 * backfilled from the lines, the orders that paid their worth read paid in full, nothing else moves, and the new
 * {@code ck_order_paid_in_full} compares with the worth.
 */
class OrderPaidInFullMigrationUpgradeTest {

    private static final String BEFORE = "20261102020200";
    private static final String MIGRATION = "20261102030100";

    @Test
    void upgradeBackfillsTheClosedAmountAndPaidInFullOnTheWorth() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID customer = UUID.randomUUID();
                exec(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                        + "VALUES ('" + customer + "', 'Old customer', 'old@example.com', 'ACTIVE', 0, NOW())");

                // The lines name a SKU no product row stands behind here: the seed skips foreign keys.
                exec(db, "SET session_replication_role = replica");
                // 10 units at 100 000 = 1 000 000; 4 closed: worth 600 000.
                UUID paidOnTheWorth = order(db, customer, 600_000, 0, 4);
                UUID paidShort = order(db, customer, 599_999, 0, 4);
                UUID notClosed = order(db, customer, 600_000, 0, 0);
                // A fee of 50 000 agreed on the closure: paid in full is 650 000.
                UUID feeNotCovered = order(db, customer, 600_000, 50_000, 4);
                UUID feeCovered = order(db, customer, 650_000, 50_000, 4);
                UUID nothingPaid = order(db, customer, 0, 0, 4);

                exec(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).contains(MIGRATION);

                assertThat(closed(db, paidOnTheWorth)).isEqualTo("400000");
                assertThat(closed(db, notClosed)).isEqualTo("0");
                assertThat(closed(db, feeCovered)).isEqualTo("400000");
                assertThat(paidInFull(db, paidOnTheWorth)).as("paid what it is now worth: PAID").isTrue();
                assertThat(paidInFull(db, feeCovered)).as("the worth and the fee").isTrue();
                assertThat(RawSql.texts(db, "SELECT (paid_in_full_at = last_modified_at)::text FROM ordering.customer_order "
                        + "WHERE id = '" + paidOnTheWorth + "'")).as("stamped with the last audit time").containsExactly("true");
                assertThat(paidInFull(db, paidShort)).as("a dong short: stays PARTIALLY_PAID").isFalse();
                assertThat(paidInFull(db, feeNotCovered)).as("the worth but not the fee: stays").isFalse();
                assertThat(paidInFull(db, notClosed)).as("never closed: unchanged").isFalse();
                assertThat(paidInFull(db, nothingPaid)).as("nothing received is not paid").isFalse();

                // The new check compares the money with the worth, not the total.
                assertThatThrownBy(() -> exec(db, "UPDATE ordering.customer_order SET paid_in_full_at = NOW() WHERE id = '"
                        + paidShort + "'")).hasMessageContaining("ck_order_paid_in_full");
                exec(db, "UPDATE ordering.customer_order SET paid_amount = 600000, paid_in_full_at = NOW() WHERE id = '"
                        + paidShort + "'");
                assertThatThrownBy(() -> exec(db, "UPDATE ordering.customer_order SET paid_in_full_at = NOW() WHERE id = '"
                        + notClosed + "'")).as("an order nothing was closed out of still needs its total")
                        .hasMessageContaining("ck_order_paid_in_full");
            }
        }
    }

    /** An order of one line of 10 units at 100 000, {@code closedUnits} of them closed; part paid, as the old rule left it. */
    private static UUID order(Connection db, UUID customer, long paid, long fee, int closedUnits) throws Exception {
        UUID id = UUID.randomUUID();
        exec(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, "
                + "currency, placed_at, version, created_at, last_modified_at, payment_term, deposit_required, "
                + "deposit_received_at, balance_policy, paid_amount, paid_in_full_at, remainder_fee_amount) VALUES ('" + id + "', 'SO-OLD-"
                + id.toString().substring(0, 8) + "', '" + customer + "', gen_random_uuid(), 'CONFIRMED', 1000000, 'VND', "
                + "NOW() - interval '10 days', 0, NOW(), NOW() - interval '1 day', 'DEPOSIT', 100000, NOW(), 'BEFORE_DELIVERY', " + paid
                + ", NULL, " + fee + ")");
        exec(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, cancelled_quantity) "
                + "VALUES (gen_random_uuid(), '" + id + "', 'CUP-OLD', 10, 100000, 'VND', " + closedUnits + ")");
        return id;
    }

    private static String closed(Connection db, UUID order) throws Exception {
        return RawSql.texts(db, "SELECT closed_amount::bigint FROM ordering.customer_order WHERE id = '" + order + "'")
                .getFirst();
    }

    private static boolean paidInFull(Connection db, UUID order) throws Exception {
        return RawSql.number(db, "SELECT count(*) FROM ordering.customer_order WHERE id = '" + order
                + "' AND paid_in_full_at IS NOT NULL") == 1;
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void exec(Connection db, String sql) throws Exception {
        RawSql.update(db, sql);
    }
}
