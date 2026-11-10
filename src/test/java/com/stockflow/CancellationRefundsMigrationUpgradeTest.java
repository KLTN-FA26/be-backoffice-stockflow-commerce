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
 * V20261108003000 on the real upgrade path (team plan 4B-9): it rewrites four CHECKs of {@code payment.installment} and
 * makes {@code contract_installment_id} nullable for the cancellation fee item. Installments written before it - one in
 * every status of 15 §5.1 - must pass the rewritten CHECKs as they are (the migration validates them when it adds them),
 * keep nothing taken off, and still move as before; a plan installment still names its plan row.
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the CHECKs
 * on rows the old rules accepted, not how a signature makes them, which {@code InstallmentReceiptIntegrationTest}
 * covers.</p>
 */
class CancellationRefundsMigrationUpgradeTest {

    private static final String BEFORE = "20261108002900";
    private static final String MIGRATION = "20261108003000";
    private static final String CONTRACT = "00000000-0000-4000-8000-0000000000b1";
    private static final String ORDER = "00000000-0000-4000-8000-0000000000b2";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000b3";
    private static final String CANCELLED_ORDER = "00000000-0000-4000-8000-0000000000b4";

    @Test
    void installmentsWrittenBeforeStayValid() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT seq || ':' || status || ':' || reduced_amount::int "
                        + "FROM payment.installment ORDER BY seq")).as("every status kept, nothing taken off")
                        .containsExactly("1:NOT_DUE:0", "2:DUE:0", "3:PARTIALLY_PAID:0", "4:PAID:0", "5:OVERDUE:0",
                                "6:CANCELLED:0", "7:CANCELLED:0");
                assertThat(RawSql.text(db, "SELECT last_modified_by FROM payment.installment WHERE seq = 7"))
                        .as("an order cancelled before the upgrade, its listener still pending: cancelled by the "
                                + "migration, as the listener would have").isEqualTo("flyway");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conrelid = "
                        + "'payment.installment'::regclass AND convalidated = false")).as("every CHECK validated")
                        .isZero();

                // They move as before: the nightly sweep, a milestone reached.
                assertThat(RawSql.update(db, "UPDATE payment.installment SET status = 'OVERDUE' WHERE status = 'DUE' "
                        + "AND seq = 2")).isEqualTo(1);
                assertThat(RawSql.update(db, "UPDATE payment.installment SET status = 'DUE', reached_at = NOW(), "
                        + "due_date = CURRENT_DATE + 30 WHERE seq = 1")).isEqualTo(1);

                // A plan installment still names its plan row, and PAID still means paid.
                RawSql.refused(db, "ck_installment_fee_item", "UPDATE payment.installment SET "
                        + "contract_installment_id = NULL WHERE seq = 3");
                RawSql.refused(db, "ck_installment_status_facts", "UPDATE payment.installment SET status = 'PAID', "
                        + "paid_at = NOW() WHERE seq = 3");
                RawSql.refused(db, "ck_installment_amounts", "UPDATE payment.installment SET reduced_amount = 1000001 "
                        + "WHERE seq = 3");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        String insert = "INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, "
                + "customer_id, contract_number, order_number, seq, name, milestone, amount, paid_amount, currency, "
                + "due_days, reached_at, due_date, status, paid_at, cancelled_at) VALUES (gen_random_uuid(), "
                + "?::uuid, gen_random_uuid(), ?::uuid, ?::uuid, 'HD-UPG-1', 'SO-UPG-1', ?, 'Row', ?, 1000000, ?, "
                + "'VND', ?, %s, %s, ?, %s, %s)";
        // NOT_DUE: waiting for delivery.
        RawSql.update(db, insert.formatted("NULL", "NULL", "NULL", "NULL"), CONTRACT, ORDER, CUSTOMER, 1,
                "AFTER_DELIVERY", 0, 30, "NOT_DUE");
        // DUE: on signing, due in three days.
        RawSql.update(db, insert.formatted("NOW()", "CURRENT_DATE + 3", "NULL", "NULL"), CONTRACT, ORDER, CUSTOMER,
                2, "ON_SIGNING", 0, 3, "DUE");
        // PARTIALLY_PAID before delivery (no due day).
        RawSql.update(db, "INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, "
                + "customer_id, contract_number, order_number, seq, name, milestone, amount, paid_amount, currency, "
                + "reached_at, status) VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), ?::uuid, ?::uuid, "
                + "'HD-UPG-1', 'SO-UPG-1', 3, 'Row', 'BEFORE_DELIVERY', 1000000, 400000, 'VND', NOW(), "
                + "'PARTIALLY_PAID')", CONTRACT, ORDER, CUSTOMER);
        // PAID.
        RawSql.update(db, insert.formatted("NOW()", "CURRENT_DATE", "NOW()", "NULL"), CONTRACT, ORDER, CUSTOMER, 4,
                "ON_SIGNING", 1000000, 0, "PAID");
        // OVERDUE, paid in part.
        RawSql.update(db, insert.formatted("NOW() - INTERVAL '40 days'", "CURRENT_DATE - 10", "NULL", "NULL"),
                CONTRACT, ORDER, CUSTOMER, 5, "AFTER_DELIVERY", 300000, 30, "OVERDUE");
        // CANCELLED with its order, paid in part.
        RawSql.update(db, insert.formatted("NULL", "NULL", "NULL", "NOW()"), CONTRACT, ORDER, CUSTOMER, 6,
                "AFTER_DELIVERY", 100000, 30, "CANCELLED");
        // A contract order cancelled just before the upgrade, its installment still DUE (its listener not yet run).
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at, cancellation_reason_code, cancellation_reason) "
                + "VALUES (?::uuid, 'SO-UPG-2', ?::uuid, gen_random_uuid(), 'CANCELLED', 1000000, 'VND', NOW(), NOW(), "
                + "'CUSTOMER_REQUEST', 'CUSTOMER_REQUEST')", CANCELLED_ORDER, CUSTOMER);
        RawSql.update(db, insert.formatted("NOW()", "CURRENT_DATE + 3", "NULL", "NULL"), CONTRACT, CANCELLED_ORDER,
                CUSTOMER, 7, "ON_SIGNING", 0, 3, "DUE");
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
