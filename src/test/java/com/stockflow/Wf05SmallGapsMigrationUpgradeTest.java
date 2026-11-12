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
 * V20261121000100 and V20261121000200 on the real upgrade path (WF-05 fix plan, section 8).
 *
 * <ul>
 *   <li><b>Overdue is a flag.</b> Installments written before - one OVERDUE with nothing paid, one OVERDUE paid in
 *       part, and the other statuses - end as what their money says (DUE, PARTIALLY_PAID), the rest untouched; the
 *       rewritten CHECKs hold on them, OVERDUE is refused from then on, and {@code payment.installment_is_overdue}
 *       answers for the due day.</li>
 *   <li><b>The counter of a count's variance.</b> An adjustment of a count line takes the line's counter; one whose
 *       decider counted the line (written before the application refused it) keeps none, so the migration is not
 *       blocked; the posted adjustment's ledger line takes the requester and the counter; and from then on a decision
 *       by the counter is refused by the table.</li>
 * </ul>
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the
 * migration on rows the old rules accepted, not how the application makes them.</p>
 */
class Wf05SmallGapsMigrationUpgradeTest {

    private static final String BEFORE = "20261117000300";
    private static final String OVERDUE_MIGRATION = "20261121000100";
    private static final String COUNTER_MIGRATION = "20261121000200";

    private static final String CONTRACT = "00000000-0000-4000-8000-00000000c0b1";
    private static final String ORDER = "00000000-0000-4000-8000-00000000c0b2";
    private static final String CUSTOMER = "00000000-0000-4000-8000-00000000c0b3";
    private static final String REQUESTER = "00000000-0000-4000-8000-00000000c0c1";
    private static final String COUNTER = "00000000-0000-4000-8000-00000000c0c2";
    private static final String APPROVER = "00000000-0000-4000-8000-00000000c0c3";
    private static final String COUNT = "00000000-0000-4000-8000-00000000c0d0";
    private static final String LINE_POSTED = "00000000-0000-4000-8000-00000000c0d1";
    private static final String LINE_SELF = "00000000-0000-4000-8000-00000000c0d2";
    private static final String LINE_PENDING = "00000000-0000-4000-8000-00000000c0d3";
    private static final String ADJ_POSTED = "00000000-0000-4000-8000-00000000c0e1";
    private static final String ADJ_SELF = "00000000-0000-4000-8000-00000000c0e2";
    private static final String ADJ_PENDING = "00000000-0000-4000-8000-00000000c0e3";
    private static final String ADJ_BY_HAND = "00000000-0000-4000-8000-00000000c0e4";

    @Test
    void overdueBecomesAFlagAndTheCounterIsKept() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seedInstallments(db);
                seedAdjustments(db);

                var result = flyway(postgres, COUNTER_MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version)
                        .containsExactly(OVERDUE_MIGRATION, COUNTER_MIGRATION);

                // ---- overdue is a flag
                assertThat(RawSql.texts(db, "SELECT seq || ':' || status FROM payment.installment ORDER BY seq"))
                        .as("OVERDUE became what the money says; the rest stayed")
                        .containsExactly("1:NOT_DUE", "2:DUE", "3:PARTIALLY_PAID", "4:PAID", "5:PARTIALLY_PAID",
                                "6:CANCELLED", "7:DUE");
                assertThat(RawSql.texts(db, "SELECT seq::text FROM payment.installment WHERE "
                        + "payment.installment_is_overdue(due_date, status) ORDER BY seq"))
                        .as("the two that were OVERDUE are overdue by their due day; a paid or cancelled one never is")
                        .containsExactly("5", "7");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conrelid = "
                        + "'payment.installment'::regclass AND convalidated = false")).as("every CHECK validated")
                        .isZero();
                // OVERDUE is no status any more: no edge leads to it, and the table does not know it.
                RawSql.refusedBy(db, "is not a transition", "DUE -> OVERDUE", "UPDATE payment.installment SET "
                        + "status = 'OVERDUE' WHERE seq = 2");
                RawSql.refusedBy(db, "is not a transition", "PARTIALLY_PAID -> OVERDUE", "UPDATE payment.installment "
                        + "SET status = 'OVERDUE' WHERE seq = 5");
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.refusedBy(db, "ck_installment_status", "OVERDUE past the trigger", "UPDATE payment.installment "
                        + "SET status = 'OVERDUE' WHERE seq = 2");
                RawSql.update(db, "SET session_replication_role = origin");

                // ---- the counter on the adjustment and on the ledger
                assertThat(RawSql.text(db, "SELECT counted_by::text FROM inventory.stock_adjustment WHERE id = ?::uuid",
                        ADJ_POSTED)).isEqualTo(COUNTER);
                assertThat(RawSql.text(db, "SELECT counted_by::text FROM inventory.stock_adjustment WHERE id = ?::uuid",
                        ADJ_PENDING)).isEqualTo(COUNTER);
                assertThat(RawSql.text(db, "SELECT counted_by::text FROM inventory.stock_adjustment WHERE id = ?::uuid",
                        ADJ_SELF)).as("its decider counted the line: no counter kept, the migration not blocked")
                        .isNull();
                assertThat(RawSql.text(db, "SELECT counted_by::text FROM inventory.stock_adjustment WHERE id = ?::uuid",
                        ADJ_BY_HAND)).isNull();
                assertThat(RawSql.texts(db, "SELECT coalesce(requested_by::text, '-') || '/' || "
                        + "coalesce(counted_by::text, '-') FROM inventory.stock_movement ORDER BY reason"))
                        .as("the posted variance's line names who asked and who counted; a by-hand one who asked; "
                                + "a pick names nobody")
                        .containsExactly(REQUESTER + "/" + COUNTER, REQUESTER + "/-", "-/-");

                // The counter does not decide, whoever asks the table.
                RawSql.update(db, "SET session_replication_role = replica");   // the carton triggers are not the point
                RawSql.refusedBy(db, "ck_stock_adjustment_counter_not_decider", "the counter decides", "UPDATE inventory.stock_adjustment SET "
                        + "status = 'REJECTED', decided_by = ?::uuid, decided_at = NOW(), rejection_reason = 'x' "
                        + "WHERE id = ?::uuid", COUNTER, ADJ_PENDING);
                RawSql.refusedBy(db, "ck_stock_adjustment_counted_by_count", "a counter without a count", "UPDATE inventory.stock_adjustment SET "
                        + "counted_by = ?::uuid WHERE id = ?::uuid", COUNTER, ADJ_BY_HAND);
                RawSql.refusedBy(db, "ck_stock_movement_adjustment_people", "people on a pick line", "UPDATE inventory.stock_movement SET "
                        + "requested_by = ?::uuid WHERE movement_type = 'PICK'", REQUESTER);
                RawSql.update(db, "SET session_replication_role = origin");
            }
        }
    }

    private static void seedInstallments(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        String insert = "INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, "
                + "customer_id, contract_number, order_number, seq, name, milestone, amount, paid_amount, currency, "
                + "due_days, reached_at, due_date, status, paid_at, cancelled_at) VALUES (gen_random_uuid(), "
                + "?::uuid, gen_random_uuid(), ?::uuid, ?::uuid, 'HD-UPG-8', 'SO-UPG-8', ?, 'Row', ?, 1000000, ?, "
                + "'VND', ?, %s, %s, ?, %s, %s)";
        RawSql.update(db, insert.formatted("NULL", "NULL", "NULL", "NULL"), CONTRACT, ORDER, CUSTOMER, 1,
                "AFTER_DELIVERY", 0, 30, "NOT_DUE");
        RawSql.update(db, insert.formatted("NOW()", "CURRENT_DATE + 3", "NULL", "NULL"), CONTRACT, ORDER, CUSTOMER,
                2, "ON_SIGNING", 0, 3, "DUE");
        RawSql.update(db, insert.formatted("NOW()", "CURRENT_DATE + 3", "NULL", "NULL"), CONTRACT, ORDER, CUSTOMER,
                3, "ON_SIGNING", 400000, 3, "PARTIALLY_PAID");
        RawSql.update(db, insert.formatted("NOW()", "CURRENT_DATE - 20", "NOW()", "NULL"), CONTRACT, ORDER, CUSTOMER, 4,
                "ON_SIGNING", 1000000, 0, "PAID");
        // OVERDUE, paid in part: the money the old status hid.
        RawSql.update(db, insert.formatted("NOW() - INTERVAL '40 days'", "CURRENT_DATE - 10", "NULL", "NULL"),
                CONTRACT, ORDER, CUSTOMER, 5, "AFTER_DELIVERY", 300000, 30, "OVERDUE");
        RawSql.update(db, insert.formatted("NOW() - INTERVAL '40 days'", "CURRENT_DATE - 10", "NULL", "NOW()"),
                CONTRACT, ORDER, CUSTOMER, 6, "AFTER_DELIVERY", 100000, 30, "CANCELLED");
        // OVERDUE, nothing paid.
        RawSql.update(db, insert.formatted("NOW() - INTERVAL '9 days'", "CURRENT_DATE - 6", "NULL", "NULL"),
                CONTRACT, ORDER, CUSTOMER, 7, "ON_SIGNING", 0, 3, "OVERDUE");
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static void seedAdjustments(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        for (String[] user : new String[][] {{REQUESTER, "upg8.requester"}, {COUNTER, "upg8.counter"},
                {APPROVER, "upg8.approver"}}) {
            RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, created_at) VALUES "
                    + "(?::uuid, ?, ?, 'ACTIVE', NOW())", user[0], user[1], user[1] + "@example.test");
        }
        RawSql.update(db, "INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status) "
                + "VALUES (?::uuid, 'CC-UPG-8', gen_random_uuid(), 'MANUAL', 'PLANNED')", COUNT);
        for (String line : new String[] {LINE_POSTED, LINE_SELF, LINE_PENDING}) {
            RawSql.update(db, "INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, "
                    + "lot_number, expected_qty, counted_qty, counted_by, counted_at) VALUES (?::uuid, ?::uuid, "
                    + "'HCM-A01-1-A', 'SKU-UPG-8', ?, 50, 0, ?::uuid, NOW())", line, COUNT, "LOT-" + line.substring(32),
                    COUNTER);
        }
        String adjustment = "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, "
                + "quantity_delta, reason_code, status, cycle_count_line_id, requested_by, decided_by, decided_at, "
                + "posted_at) VALUES (?::uuid, ?, 'HCM-A01-1-A', 'SKU-UPG-8', -50, ?, ?, %s, ?::uuid, %s, %s, %s)";
        // A count's variance approved by a third person and posted.
        RawSql.update(db, adjustment.formatted("?::uuid", "?::uuid", "NOW()", "NOW()"), ADJ_POSTED, "ADJ-UPG-8-1",
                "COUNT_VARIANCE", "POSTED", LINE_POSTED, REQUESTER, APPROVER);
        // One the counter decided, before the application refused it.
        RawSql.update(db, adjustment.formatted("?::uuid", "?::uuid", "NOW()", "NOW()"), ADJ_SELF, "ADJ-UPG-8-2",
                "COUNT_VARIANCE", "POSTED", LINE_SELF, REQUESTER, COUNTER);
        // One still waiting.
        RawSql.update(db, adjustment.formatted("?::uuid", "NULL", "NULL", "NULL"), ADJ_PENDING, "ADJ-UPG-8-3",
                "COUNT_VARIANCE", "PENDING_APPROVAL", LINE_PENDING, REQUESTER);
        // One asked by hand, posted.
        RawSql.update(db, adjustment.formatted("NULL", "?::uuid", "NOW()", "NOW()"), ADJ_BY_HAND, "ADJ-UPG-8-4",
                "DAMAGED", "POSTED", REQUESTER, APPROVER);
        String movement = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, quantity, "
                + "from_status, reference_type, reference_id, reason, actor_id, occurred_at) VALUES (gen_random_uuid(), "
                + "?, 'SKU-UPG-8', 'HCM-A01-1-A', 50, 'AVAILABLE', ?, ?::uuid, ?, ?::uuid, NOW())";
        RawSql.update(db, movement, "ADJUSTMENT", "STOCK_ADJUSTMENT", ADJ_POSTED, "1 count variance", APPROVER);
        RawSql.update(db, movement, "ADJUSTMENT", "STOCK_ADJUSTMENT", ADJ_BY_HAND, "2 by hand", APPROVER);
        RawSql.update(db, movement, "PICK", "PICK_LINE", ADJ_PENDING, "3 a pick", APPROVER);
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
