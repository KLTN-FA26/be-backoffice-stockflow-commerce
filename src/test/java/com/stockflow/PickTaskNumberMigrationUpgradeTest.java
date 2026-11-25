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
 * V20261228000200 on the real upgrade path. It adds {@code fulfillment.pick.task_number} ({@code PK-yyyyMMdd-nnnn}) and
 * numbers the tasks already there: per business day (Asia/Ho_Chi_Minh) of their creation, in creation order, after any
 * PK number that day already drew; then moves {@code platform.document_sequence} past them, so the next task of such a
 * day takes the next number, and makes the column NOT NULL, unique and immutable.
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the migration
 * on rows the old schema accepted. How the application assigns the number is {@code FulfillmentServiceImplTest}'s.</p>
 */
class PickTaskNumberMigrationUpgradeTest {

    private static final String BEFORE = "20261228000100";
    private static final String MIGRATION = "20261228000200";
    private static final String CUSTOMER = "00000000-0000-4000-8000-00000000e0a0";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-00000000e0c1";
    private static final String ORDER = "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, "
            + "status, total_amount, currency, placed_at, created_at, contract_signed_at) VALUES (gen_random_uuid(), ?, "
            + "?::uuid, gen_random_uuid(), 'CONFIRMED', 1000, 'VND', NOW(), NOW(), NOW())";

    @Test
    void existingTasksAreNumberedPerBusinessDayAndTheSequenceMovesPastThem() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT order_number || ':' || task_number FROM fulfillment.pick "
                        + "WHERE order_number LIKE 'SO-UPG-T%' ORDER BY order_number"))
                        .as("numbered by the Saigon day of creation, in creation order (id breaks a tie), after the "
                                + "PK numbers 2026-11-10 had drawn already")
                        .containsExactly(
                                "SO-UPG-T1:PK-20261109-0001",
                                "SO-UPG-T2:PK-20261109-0002",
                                // 17:30 UTC on the 9th is 00:30 on the 10th in Saigon; the 10th had drawn 3.
                                "SO-UPG-T3:PK-20261110-0004",
                                "SO-UPG-T4:PK-20261110-0005");
                assertThat(RawSql.texts(db, "SELECT to_char(sequence_date, 'YYYYMMDD') || ':' || last_value "
                        + "FROM platform.document_sequence WHERE document_type = 'PK' ORDER BY sequence_date"))
                        .as("the sequence moved past every number given")
                        .containsExactly("20261109:2", "20261110:5");

                // The rows below name a customer and a warehouse this bare database does not hold, so the foreign keys
                // stay off. The trigger under test fires anyway - the migration enables it ALWAYS, for fixtures written
                // this way - and CHECK and UNIQUE always hold.
                RawSql.update(db, "SET session_replication_role = replica");
                // A task written without a number afterwards (a SQL fixture, a seed) draws the next one of its day.
                RawSql.update(db, ORDER, "SO-UPG-T5", CUSTOMER);
                RawSql.update(db, "INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, "
                        + "created_at) VALUES (gen_random_uuid(), (SELECT id FROM ordering.customer_order WHERE "
                        + "order_number = 'SO-UPG-T5'), 'SO-UPG-T5', ?::uuid, 'PENDING', "
                        + "'2026-11-10 03:00:00+00'::timestamptz)", WAREHOUSE);
                assertThat(RawSql.text(db, "SELECT task_number FROM fulfillment.pick WHERE order_number = 'SO-UPG-T5'"))
                        .isEqualTo("PK-20261110-0006");

                assertThat(RawSql.texts(db, "SELECT attnotnull::text FROM pg_attribute WHERE attrelid = "
                        + "'fulfillment.pick'::regclass AND attname = 'task_number'")).containsExactly("true");
                RawSql.refusedBy(db, "tg_pick_task_number", "a task number never changes",
                        "UPDATE fulfillment.pick SET task_number = 'PK-20261109-0099' WHERE order_number = 'SO-UPG-T1'");
                for (String other : java.util.List.of("SO-UPG-T6", "SO-UPG-T7")) {
                    RawSql.update(db, ORDER, other, CUSTOMER);
                }
                String numbered = "INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, "
                        + "created_at, task_number) VALUES (gen_random_uuid(), (SELECT id FROM ordering.customer_order "
                        + "WHERE order_number = ?), ?, ?::uuid, 'PENDING', NOW(), ?)";
                RawSql.refusedBy(db, "uk_pick_task_number", "two tasks never share a number", numbered,
                        "SO-UPG-T6", "SO-UPG-T6", WAREHOUSE, "PK-20261109-0001");
                RawSql.refusedBy(db, "ck_pick_task_number", "a number not of the form PK-yyyyMMdd-nnnn", numbered,
                        "SO-UPG-T7", "SO-UPG-T7", WAREHOUSE, "TASK-1");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        String pick = "INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at) "
                + "VALUES (?::uuid, (SELECT id FROM ordering.customer_order WHERE order_number = ?), ?, ?::uuid, "
                + "'PENDING', ?::timestamptz)";
        // T1 and T2 at the same moment: the id decides.
        seedTask(db, pick, "SO-UPG-T1", "00000000-0000-4000-8000-0000000000a1", "2026-11-09 02:00:00+00");
        seedTask(db, pick, "SO-UPG-T2", "00000000-0000-4000-8000-0000000000a2", "2026-11-09 02:00:00+00");
        seedTask(db, pick, "SO-UPG-T3", "00000000-0000-4000-8000-0000000000a3", "2026-11-09 17:30:00+00");
        seedTask(db, pick, "SO-UPG-T4", "00000000-0000-4000-8000-0000000000a4", "2026-11-10 09:00:00+00");
        // A PK number already drawn on the 10th (nothing drew one before this migration; a database that did keeps it).
        RawSql.update(db, "INSERT INTO platform.document_sequence (document_type, sequence_date, last_value) "
                + "VALUES ('PK', DATE '2026-11-10', 3)");
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static void seedTask(Connection db, String pick, String number, String id, String at)
            throws SQLException {
        RawSql.update(db, ORDER, number, CUSTOMER);
        RawSql.update(db, pick, id, number, number, WAREHOUSE, at);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
