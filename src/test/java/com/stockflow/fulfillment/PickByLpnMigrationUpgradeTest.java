package com.stockflow.fulfillment;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * V20261113000400 on the real upgrade path (team plan 4C-4; kltn-docs 07 BR-04, BR-05, BR-09, BR-10): it stops, naming
 * the rows, before changing anything when a pick line still has work to do and cannot do it by carton; it archives what
 * it changes in {@code platform.legacy_archive}; a SHORT task goes back to PICKING; lines without an LPN are given
 * their hold's carton, or cancelled when nothing is left to pick, or kept as the loose history they are; and the four
 * rules are CHECKs - NOT VALID where the history breaks them, validated where nothing does.
 *
 * <p>The old state is written with {@code session_replication_role = replica}: foreign keys and row triggers are off
 * (the orders, users and places these rows name are not what this is about), the CHECKs are not, so every old row is
 * one the old schema accepted.</p>
 */
class PickByLpnMigrationUpgradeTest {

    private static final String BEFORE = "20261112000300";   // the version before 4C-4 on this line: 4D-1 and 4D-2 sit between
    private static final String MIGRATION = "20261113000400";

    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000c4000";
    private static final String BIN = "UPG4-A01-1-A";
    private static final String OTHER_BIN = "UPG4-A02-1-A";
    private static final String SKU = "UPG4-CUP";
    private static final String PICKER = "00000000-0000-4000-8000-0000000c4009";

    /** Ids are told apart by their last digits: {@code id(12)} is the twelfth row of this test. */
    private static String id(int n) {
        return "00000000-0000-4000-8000-0000000c4%03d".formatted(n);
    }

    /** A carton's LPN, as the stock_package check wants it, told apart by the row it belongs to. */
    private static String lpn(int n) {
        return "LPN-UPG4-" + n;
    }

    /** An order number of at most 32 characters, told apart by the task it belongs to. */
    private static String orderNo(String pickId) {
        return "SO-U4-" + pickId.substring(31);
    }

    // ---- the rows of the history test: what each is, and what the migration must do to it -----------------------

    private static final String OPEN_LOOSE_PICK = id(101);
    private static final String OPEN_LOOSE_LINE = id(102);

    private static final String SHORT_NO_CARTON_PICK = id(111);
    private static final String SHORT_NO_CARTON_LINE = id(112);

    /** Cancelled tasks waiting for a put-back that cannot go back as one whole carton: one line per reason. */
    private static final String NO_CARTON_LINE = id(122);
    private static final String NOT_CONSUMED_LINE = id(132);
    private static final String DAMAGED_AT_PACKING_LINE = id(142);
    private static final String NOT_THE_WHOLE_CARTON_LINE = id(152);
    /** The control: a picked line of a cancelled task that does go back as one whole carton. */
    private static final String PUT_BACK_OK_LINE = id(162);

    private static final String SHORT_TASK = id(201);
    private static final String SHORT_TASK_SHORT_LINE = id(202);
    private static final String PICKING_TASK = id(211);
    private static final String BACKFILL_LINE = id(212);
    private static final String NO_HOLD_LINE = id(213);
    private static final String RELEASED_LOOSE_LINE = id(214);
    private static final String IMPAIRED_LINE = id(215);
    private static final String MODERN_LINE = id(216);
    private static final String PUT_BACK_TASK = id(221);
    private static final String LOOSE_PARTIAL_PUT_BACK_LINE = id(222);
    private static final String LOOSE_PUT_BACK_MOVEMENT = id(223);

    private static final String[] NEW_RULES = {"ck_pick_line_lpn", "ck_pick_line_short_reported",
            "ck_pick_line_put_back_whole", "ck_pick_line_packing_damaged_whole", "ck_stock_movement_put_back_carton"};

    @Test
    @DisplayName("each of the three stops names its rows and changes nothing; once settled, the history is archived, kept "
            + "or given its carton, and the CHECKs wait NOT VALID")
    void stopsAndHistory() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seedHistory(db);

                // ---- stop 1: an OPEN line whose live hold is loose
                Throwable first = catchThrowable(() -> flyway(postgres, MIGRATION).migrate());
                assertThat(first).hasMessageContaining("V20261113000400")
                        .hasMessageContaining("these open pick lines hold loose stock")
                        .hasMessageContaining(OPEN_LOOSE_LINE).hasMessageContaining(orderNo(OPEN_LOOSE_PICK))
                        .hasMessageContaining(id(103)).hasMessageContaining(SKU)
                        .as("only what is wrong is named").hasMessageNotContaining(SHORT_NO_CARTON_LINE)
                        .hasMessageNotContaining(BACKFILL_LINE).hasMessageNotContaining(RELEASED_LOOSE_LINE);
                assertNothingChanged(db);

                // ---- stop 2: a SHORT line with no carton, no damage report to explain it, and a hold on none
                fix(db, "UPDATE fulfillment.pick_line SET status = 'CANCELLED' WHERE id = ?::uuid", OPEN_LOOSE_LINE);
                Throwable second = catchThrowable(() -> flyway(postgres, MIGRATION).migrate());
                assertThat(second).hasMessageContaining("these short pick lines name no carton")
                        .hasMessageContaining(SHORT_NO_CARTON_LINE).hasMessageContaining(orderNo(SHORT_NO_CARTON_PICK))
                        .as("a SHORT line a damage report made is not this").hasMessageNotContaining(IMPAIRED_LINE)
                        .as("nor one whose hold is in a carton").hasMessageNotContaining(SHORT_TASK_SHORT_LINE);
                assertNothingChanged(db);

                // ---- stop 3: a picked line of a cancelled task that cannot go back as one whole carton
                fix(db, "UPDATE fulfillment.pick_line SET lpn = 'LPN-UPG4-FIXED' WHERE id = ?::uuid", SHORT_NO_CARTON_LINE);
                Throwable third = catchThrowable(() -> flyway(postgres, MIGRATION).migrate());
                assertThat(third).hasMessageContaining("these picked lines of cancelled orders wait for a put-back that "
                                + "cannot go back as one whole carton")
                        .as("no carton at all").hasMessageContaining(NO_CARTON_LINE)
                        .as("a carton not CONSUMED").hasMessageContaining(NOT_CONSUMED_LINE)
                        .as("units found damaged at packing").hasMessageContaining(DAMAGED_AT_PACKING_LINE)
                        .as("a hold that was not the whole carton").hasMessageContaining(NOT_THE_WHOLE_CARTON_LINE)
                        .as("one that can go back whole is not named").hasMessageNotContaining(PUT_BACK_OK_LINE);
                assertNothingChanged(db);

                // The reasons are the old put-backs' to settle: they are put back (or the task stops waiting) before.
                fix(db, "UPDATE fulfillment.pick SET needs_put_back = FALSE WHERE id IN (?::uuid, ?::uuid, ?::uuid, ?::uuid)",
                        id(121), id(131), id(141), id(151));

                // ---- settled: the migration runs
                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // A SHORT task is PICKING, archived as it was; nothing else is touched in the table.
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.pick WHERE id = ?::uuid", SHORT_TASK))
                        .isEqualTo("PICKING");
                assertThat(RawSql.text(db, "SELECT last_modified_by FROM fulfillment.pick WHERE id = ?::uuid", SHORT_TASK))
                        .isEqualTo("V20261113000400");
                assertThat(RawSql.texts(db, "SELECT row_id::text || ':' || (row_data->>'status') FROM platform.legacy_archive "
                        + "WHERE source_table = 'fulfillment.pick'")).containsExactly(SHORT_TASK + ":SHORT");
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.pick WHERE id = ?::uuid", PICKING_TASK))
                        .as("another task is left as it was").isEqualTo("PICKING");
                assertThat(RawSql.text(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_pick_status'"))
                        .doesNotContain("SHORT");

                // Every line that had no LPN is archived as it was, and only those.
                assertThat(RawSql.texts(db, "SELECT row_id::text FROM platform.legacy_archive WHERE source_table = "
                        + "'fulfillment.pick_line' ORDER BY row_id::text")).containsExactlyInAnyOrder(
                        OPEN_LOOSE_LINE, NO_CARTON_LINE, NOT_CONSUMED_LINE, DAMAGED_AT_PACKING_LINE,
                        NOT_THE_WHOLE_CARTON_LINE, PUT_BACK_OK_LINE, SHORT_TASK_SHORT_LINE, BACKFILL_LINE,
                        NO_HOLD_LINE, RELEASED_LOOSE_LINE, IMPAIRED_LINE, LOOSE_PARTIAL_PUT_BACK_LINE);
                assertThat(RawSql.text(db, "SELECT (row_data->>'status') || ':' || coalesce(row_data->>'lpn', 'null') "
                        + "FROM platform.legacy_archive WHERE row_id = ?::uuid", NO_HOLD_LINE))
                        .as("archived as it was, before it was cancelled").isEqualTo("OPEN:null");
                assertThat(RawSql.text(db, "SELECT coalesce(row_data->>'lpn', 'null') FROM platform.legacy_archive "
                        + "WHERE row_id = ?::uuid", BACKFILL_LINE)).as("and before it was given its carton").isEqualTo("null");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE row_id = ?::uuid", MODERN_LINE))
                        .as("a line that had its LPN is not archived").isZero();

                // A line on a carton's hold is given that carton's LPN, wherever it stands.
                assertThat(lpnOf(db, BACKFILL_LINE)).isEqualTo(lpn(310));
                assertThat(lpnOf(db, SHORT_TASK_SHORT_LINE)).isEqualTo(lpn(311));
                assertThat(lpnOf(db, NOT_CONSUMED_LINE)).isEqualTo(lpn(332));
                assertThat(lpnOf(db, DAMAGED_AT_PACKING_LINE)).isEqualTo(lpn(342));
                assertThat(lpnOf(db, NOT_THE_WHOLE_CARTON_LINE)).isEqualTo(lpn(352));
                assertThat(lpnOf(db, PUT_BACK_OK_LINE)).isEqualTo(lpn(362));
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.pick_line WHERE id = ?::uuid", BACKFILL_LINE))
                        .isEqualTo("OPEN");

                // An OPEN line with nothing to pick - no hold, or a released one on loose stock - is CANCELLED.
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.pick_line WHERE id = ?::uuid", NO_HOLD_LINE))
                        .isEqualTo("CANCELLED");
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.pick_line WHERE id = ?::uuid", RELEASED_LOOSE_LINE))
                        .isEqualTo("CANCELLED");
                assertThat(lpnOf(db, NO_HOLD_LINE)).isNull();

                // Loose history is kept as it was: picked, partly put back, the line a damage report made.
                assertThat(RawSql.text(db, "SELECT status || ':' || coalesce(lpn, 'null') FROM fulfillment.pick_line WHERE id = ?::uuid",
                        NO_CARTON_LINE)).isEqualTo("PICKED:null");
                assertThat(RawSql.text(db, "SELECT status || ':' || put_back_qty || ':' || coalesce(lpn, 'null') FROM "
                        + "fulfillment.pick_line WHERE id = ?::uuid", LOOSE_PARTIAL_PUT_BACK_LINE))
                        .isEqualTo("PUT_BACK:3:null");
                assertThat(RawSql.text(db, "SELECT status || ':' || coalesce(lpn, 'null') FROM fulfillment.pick_line WHERE id = ?::uuid",
                        IMPAIRED_LINE)).isEqualTo("SHORT:null");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_movement WHERE id = ?::uuid",
                        LOOSE_PUT_BACK_MOVEMENT)).as("the ledger is never rewritten").isOne();

                // The history breaks all four rules, so all four wait NOT VALID - and a new row is still checked.
                assertThat(RawSql.texts(db, "SELECT conname || ':' || convalidated FROM pg_constraint WHERE conname IN "
                        + "('ck_pick_line_lpn', 'ck_pick_line_short_reported', 'ck_pick_line_put_back_whole', "
                        + "'ck_pick_line_packing_damaged_whole', 'ck_stock_movement_put_back_carton') ORDER BY conname"))
                        .containsExactly("ck_pick_line_lpn:false", "ck_pick_line_packing_damaged_whole:false",
                                "ck_pick_line_put_back_whole:false", "ck_pick_line_short_reported:false",
                                "ck_stock_movement_put_back_carton:false");
                assertThat(RawSql.text(db, "SELECT data_type || ':' || character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'fulfillment' AND table_name = 'pick_line' AND column_name = 'short_reason'"))
                        .isEqualTo("character varying:16");

                assertNewRowsAreChecked(db);
            }
        }
    }

    @Test
    @DisplayName("on a database with no loose history all four CHECKs are validated, so every row is checked")
    void cleanDatabaseValidatesEverything() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                // What a database whose orders were all picked by carton holds: lines with their LPN, a whole put-back
                // naming its carton in the ledger, a SHORT line with its reason, a line a damage report made.
                RawSql.update(db, "SET session_replication_role = replica");
                carton(db, id(410), lpn(410), BIN, "IN_STOCK", true, 5, null);
                row(db, id(411), BIN, 5, id(410));
                reservation(db, id(412), id(411), 5, "HELD", orderNo(id(400)));
                pick(db, id(400), "PICKING", false);
                line(db, id(401), id(400), id(412), "OPEN", 5, 0, 0, lpn(410), null);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT conname || ':' || convalidated FROM pg_constraint WHERE conname IN "
                        + "('ck_pick_line_lpn', 'ck_pick_line_short_reported', 'ck_pick_line_put_back_whole', "
                        + "'ck_pick_line_packing_damaged_whole', 'ck_stock_movement_put_back_carton') ORDER BY conname"))
                        .containsExactly("ck_pick_line_lpn:true", "ck_pick_line_packing_damaged_whole:true",
                                "ck_pick_line_put_back_whole:true", "ck_pick_line_short_reported:true",
                                "ck_stock_movement_put_back_carton:true");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table IN "
                        + "('fulfillment.pick', 'fulfillment.pick_line')")).as("nothing to archive").isZero();
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.pick_line WHERE id = ?::uuid", id(401)))
                        .isEqualTo("OPEN");
                assertNewRowsAreChecked(db);
            }
        }
    }

    // ---- what a new or changed row must now satisfy ---------------------------------------------------------------

    private static void assertNewRowsAreChecked(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        try {
            String carton = "LPN-UPG4-NEW";
            // 3 and 4: a line names its carton, unless a damage report made it.
            refusedBy(db, "ck_pick_line_lpn", "a new line with no carton and no damage report",
                    lineInsert(id(501), "OPEN", 3, 0, 0, null, null, null));
            // 4: a SHORT line says why.
            refusedBy(db, "ck_pick_line_short_reported", "a SHORT line with neither a reason nor a damage report",
                    lineInsert(id(502), "SHORT", 3, 0, 3, carton, null, null));
            // 2: the reason is for a carton short whole.
            refusedBy(db, "ck_pick_line_short_reason", "a short reason on a part of the carton",
                    lineInsert(id(503), "SHORT", 3, 0, 1, carton, "NOT_FOUND", null));
            refusedBy(db, "ck_pick_line_short_reason", "a reason outside NOT_FOUND and DAMAGED",
                    lineInsert(id(504), "SHORT", 3, 0, 3, carton, "LOST", null));
            refusedBy(db, "ck_pick_line_short_reason", "a reason on a line that is not short",
                    lineInsert(id(505), "OPEN", 3, 0, 3, carton, "NOT_FOUND", null));
            // 5: a put-back returns the whole carton.
            refusedBy(db, "ck_pick_line_packing_damaged_whole", "a partial put-back (the rest damaged at packing)",
                    putBackInsert(id(506), 5, 2, 3, carton));
            // 7: damage at packing takes the whole carton a line picked, or nothing.
            refusedBy(db, "ck_pick_line_packing_damaged_whole", "part of a picked carton damaged at packing",
                    packedInsert(id(508), 5, 2));
            // 6: the ledger line of a put-back names the carton.
            refusedBy(db, "ck_stock_movement_put_back_carton", "a pick line's put-back in the ledger with no carton",
                    putBackMovement(id(507), null));
            // 1: a task is never SHORT.
            refusedBy(db, "ck_pick_status", "a SHORT task",
                    "UPDATE fulfillment.pick SET status = 'SHORT' WHERE id = '" + id(400) + "'::uuid OR TRUE");

            // What is allowed: a carton short whole with either reason; the line a damage report made, with no carton;
            // a whole put-back; the ledger line naming its carton.
            RawSql.update(db, lineInsert(id(511), "SHORT", 3, 0, 3, carton, "NOT_FOUND", null));
            RawSql.update(db, lineInsert(id(512), "SHORT", 3, 0, 3, carton, "DAMAGED", null));
            RawSql.update(db, lineInsert(id(513), "CANCELLED", 3, 0, 3, carton, "DAMAGED", null));
            RawSql.update(db, lineInsert(id(514), "SHORT", 3, 0, 3, null, null, id(900)));
            RawSql.update(db, lineInsert(id(515), "OPEN", 3, 0, 0, carton, null, null));
            RawSql.update(db, putBackInsert(id(516), 5, 0, 5, carton));
            RawSql.update(db, putBackMovement(id(517), id(518)));
            RawSql.update(db, packedInsert(id(519), 5, 0));
            RawSql.update(db, packedInsert(id(520), 5, 5));
        } finally {
            RawSql.update(db, "SET session_replication_role = DEFAULT");
        }
    }

    /** The database refuses {@code sql}, and it is {@code constraint} that refuses it. */
    private static void refusedBy(Connection db, String constraint, String what, String sql) {
        Throwable refusal = catchThrowable(() -> RawSql.update(db, sql));
        assertThat(refusal).as("%s is refused by %s", what, constraint).isInstanceOf(SQLException.class)
                .hasMessageContaining(constraint);
    }

    private static String lineInsert(String id, String status, int allocated, int picked, int shortQty, String lpn,
                                     String reason, String damageReport) {
        return "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, allocated_qty, "
                + "picked_qty, short_qty, status, lpn, short_reason, damage_report_id) VALUES ('" + id + "'::uuid, '"
                + id(900) + "'::uuid, '" + id(901) + "'::uuid, '" + SKU + "', '" + BIN + "', " + allocated + ", " + picked
                + ", " + shortQty + ", '" + status + "', " + (lpn == null ? "NULL" : "'" + lpn + "'") + ", "
                + (reason == null ? "NULL" : "'" + reason + "'") + ", "
                + (damageReport == null ? "NULL" : "'" + damageReport + "'::uuid") + ")";
    }

    private static String putBackInsert(String id, int allocated, int packingDamaged, int putBack, String lpn) {
        return "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, allocated_qty, "
                + "picked_qty, packing_damaged_qty, status, picked_by, picked_at, consumed_at, lpn, put_back_qty, "
                + "put_back_by, put_back_at, put_back_location_code) VALUES ('" + id + "'::uuid, '" + id(900)
                + "'::uuid, '" + id(901) + "'::uuid, '" + SKU + "', '" + BIN + "', " + allocated + ", " + allocated + ", "
                + packingDamaged + ", 'PUT_BACK', '" + PICKER + "'::uuid, NOW(), NOW(), '" + lpn + "', " + putBack + ", '"
                + PICKER + "'::uuid, NOW(), '" + OTHER_BIN + "')";
    }

    /** A PICKED line of {@code picked} units, {@code damaged} of them found damaged at packing. */
    private static String packedInsert(String id, int picked, int damaged) {
        return "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, allocated_qty, "
                + "picked_qty, packing_damaged_qty, status, picked_by, picked_at, lpn) VALUES ('" + id + "'::uuid, '"
                + id(900) + "'::uuid, '" + id(901) + "'::uuid, '" + SKU + "', '" + BIN + "', " + picked + ", " + picked
                + ", " + damaged + ", 'PICKED', '" + PICKER + "'::uuid, NOW(), 'LPN-UPG4-NEW')";
    }

    private static String putBackMovement(String id, String toCarton) {
        return "INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, to_status, "
                + "reference_type, reference_id, occurred_at, to_stock_package_id) VALUES ('" + id
                + "'::uuid, 'PUT_BACK', '" + SKU + "', '" + OTHER_BIN + "', 3, 'AVAILABLE', 'PICK_LINE', gen_random_uuid(), "
                + "NOW(), " + (toCarton == null ? "NULL" : "'" + toCarton + "'::uuid") + ")";
    }

    // ---- the old state --------------------------------------------------------------------------------------------

    /**
     * Every kind of pick line the migration meets. Stops first (rows 1xx), then the rows it must change or keep (2xx),
     * their cartons and holds (3xx).
     */
    private static void seedHistory(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        // Cartons and their rows, one per hold that is in a carton: LPN-UPG4-<n> holds the 3xx row it was named after.
        carton(db, id(310), lpn(310), BIN, "IN_STOCK", true, 4, null);
        carton(db, id(311), lpn(311), BIN, "IN_STOCK", true, 2, null);
        carton(db, id(332), lpn(332), BIN, "IN_STOCK", true, 3, null);
        carton(db, id(342), lpn(342), BIN, "CONSUMED", false, 4, "NOW()");
        carton(db, id(352), lpn(352), BIN, "CONSUMED", false, 5, "NOW()");
        carton(db, id(362), lpn(362), BIN, "CONSUMED", false, 3, "NOW()");
        carton(db, id(370), lpn(370), BIN, "IN_STOCK", true, 2, null);
        row(db, id(320), BIN, 4, id(310));
        row(db, id(321), BIN, 2, id(311));
        row(db, id(333), BIN, 3, id(332));
        row(db, id(343), BIN, 0, id(342));
        row(db, id(353), BIN, 0, id(352));
        row(db, id(363), BIN, 0, id(362));
        row(db, id(371), BIN, 2, id(370));
        row(db, id(380), BIN, 3, null);                                  // loose stock, as it was before 4C-1

        // ---- stop 1: an OPEN line whose live hold is loose
        reservation(db, id(103), id(380), 3, "HELD", orderNo(OPEN_LOOSE_PICK));
        pick(db, OPEN_LOOSE_PICK, "PICKING", false);
        line(db, OPEN_LOOSE_LINE, OPEN_LOOSE_PICK, id(103), "OPEN", 3, 0, 0, null, null);

        // ---- stop 2: a SHORT line with no LPN whose hold is on no carton and that no damage report made
        reservation(db, id(113), id(380), 2, "HELD", orderNo(SHORT_NO_CARTON_PICK));
        pick(db, SHORT_NO_CARTON_PICK, "PICKING", false);
        line(db, SHORT_NO_CARTON_LINE, SHORT_NO_CARTON_PICK, id(113), "SHORT", 2, 0, 2, null, null);

        // ---- stop 3: a picked line of a cancelled task still waiting for its put-back, one for each way to fail
        pickedLine(db, id(121), NO_CARTON_LINE, id(123), id(380), 3, 0, null);                // loose
        pickedLine(db, id(131), NOT_CONSUMED_LINE, id(133), id(333), 3, 0, null);             // the carton is IN_STOCK
        pickedLine(db, id(141), DAMAGED_AT_PACKING_LINE, id(143), id(343), 4, 1, null);       // 1 of 4 damaged at packing
        pickedLine(db, id(151), NOT_THE_WHOLE_CARTON_LINE, id(153), id(353), 4, 0, null);     // 4 of a carton of 5
        pickedLine(db, id(161), PUT_BACK_OK_LINE, id(163), id(363), 3, 0, null);              // the whole of 3: fine

        // ---- what the migration changes or keeps once those are settled
        // A SHORT task, which no code ever wrote, with a SHORT line on a carton's hold: no reason, no damage report.
        reservation(db, id(203), id(321), 2, "HELD", orderNo(SHORT_TASK));
        pick(db, SHORT_TASK, "SHORT", false);
        line(db, SHORT_TASK_SHORT_LINE, SHORT_TASK, id(203), "SHORT", 2, 0, 2, null, null);
        // A PICKING task with: an OPEN line on a carton's hold and no LPN; an OPEN line on no hold; an OPEN line whose
        // hold was released on loose stock; a SHORT line a damage report made, on a loose DAMAGED row; one that is
        // modern, with its LPN and its carton's hold.
        pick(db, PICKING_TASK, "PICKING", false);
        reservation(db, id(217), id(320), 4, "HELD", orderNo(PICKING_TASK));
        line(db, BACKFILL_LINE, PICKING_TASK, id(217), "OPEN", 4, 0, 0, null, null);
        line(db, NO_HOLD_LINE, PICKING_TASK, null, "OPEN", 1, 0, 0, null, null);
        reservation(db, id(218), id(380), 1, "RELEASED", orderNo(PICKING_TASK));
        line(db, RELEASED_LOOSE_LINE, PICKING_TASK, id(218), "OPEN", 1, 0, 0, null, null);
        reservation(db, id(219), id(380), 2, "HELD", orderNo(PICKING_TASK));
        line(db, IMPAIRED_LINE, PICKING_TASK, id(219), "SHORT", 2, 0, 2, null, id(990));
        reservation(db, id(220), id(371), 2, "HELD", orderNo(PICKING_TASK));
        line(db, MODERN_LINE, PICKING_TASK, id(220), "OPEN", 2, 0, 0, lpn(370), null);
        // A partial put-back of loose goods, and the loose PUT_BACK the ledger wrote for it.
        pick(db, PUT_BACK_TASK, "CANCELLED", false);
        reservation(db, id(224), id(380), 5, "CONSUMED", orderNo(PUT_BACK_TASK));
        RawSql.update(db, "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, "
                + "location_code, sequence_no, allocated_qty, picked_qty, packing_damaged_qty, status, picked_by, picked_at, "
                + "consumed_at, put_back_qty, put_back_by, put_back_at, put_back_location_code) VALUES (?::uuid, ?::uuid, "
                + "gen_random_uuid(), ?::uuid, ?, ?, 1, 5, 5, 2, 'PUT_BACK', ?::uuid, NOW(), NOW(), 3, ?::uuid, NOW(), ?)",
                LOOSE_PARTIAL_PUT_BACK_LINE, PUT_BACK_TASK, id(224), SKU, BIN, PICKER, PICKER, OTHER_BIN);
        RawSql.update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, "
                + "to_status, reference_type, reference_id, occurred_at) VALUES (?::uuid, 'PUT_BACK', ?, ?, 3, "
                + "'AVAILABLE', 'PICK_LINE', ?::uuid, NOW())", LOOSE_PUT_BACK_MOVEMENT, SKU, OTHER_BIN,
                LOOSE_PARTIAL_PUT_BACK_LINE);
        RawSql.update(db, "SET session_replication_role = DEFAULT");
    }

    /** A cancelled task, waiting for a put-back, with one picked line on the hold of {@code row}. */
    private static void pickedLine(Connection db, String pick, String line, String reservation, String row, int picked,
                                   int packingDamaged, String lpn) throws SQLException {
        reservation(db, reservation, row, picked, "CONSUMED", orderNo(pick));
        pick(db, pick, "CANCELLED", true);
        RawSql.update(db, "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, "
                + "location_code, sequence_no, allocated_qty, picked_qty, packing_damaged_qty, status, picked_by, picked_at, "
                + "consumed_at, lpn) VALUES (?::uuid, ?::uuid, gen_random_uuid(), ?::uuid, ?, ?, 1, ?, ?, ?, 'PICKED', "
                + "?::uuid, NOW(), NOW(), ?)", line, pick, reservation, SKU, BIN, picked, picked, packingDamaged, PICKER,
                lpn);
    }

    private static void carton(Connection db, String id, String lpn, String location, String status, boolean unopened,
                               int count, String closedAt) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, received_qty, location_code, status, unopened, closed_at, created_at) VALUES (?::uuid, ?, "
                + "gen_random_uuid(), gen_random_uuid(), ?, ?, ?, ?, ?, " + (closedAt == null ? "NULL" : closedAt)
                + ", NOW())", id, lpn, count, count, location, status, unopened);
    }

    /** A stock row at {@code BIN}; {@code carton} null for loose stock. */
    private static void row(Connection db, String id, String location, int onHand, String carton) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, "
                + "created_at, received_at, stock_package_id) VALUES (?::uuid, ?, ?, 'L-1', ?, 0, 'AVAILABLE', NOW(), "
                + "NOW() - interval '9 days', ?::uuid)", id, SKU, location, onHand, carton);
    }

    private static void reservation(Connection db, String id, String row, int quantity, String status, String orderNumber)
            throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, "
                + "request_id, quantity, reserved_at, status, closed_at, sku, order_number) VALUES (?::uuid, ?::uuid, "
                + "gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), ?, NOW(), ?, "
                + (status.equals("HELD") ? "NULL" : "NOW()") + ", ?, ?)", id, row, quantity, status, SKU, orderNumber);
    }

    private static void pick(Connection db, String id, String status, boolean needsPutBack) throws SQLException {
        RawSql.update(db, "INSERT INTO fulfillment.pick (id, order_id, status, created_at, order_number, warehouse_id, "
                + "needs_put_back) VALUES (?::uuid, gen_random_uuid(), ?, NOW(), ?, ?::uuid, ?)", id, status,
                orderNo(id), WAREHOUSE, needsPutBack);
    }

    private static void line(Connection db, String id, String pick, String reservation, String status, int allocated,
                             int picked, int shortQty, String lpn, String damageReport) throws SQLException {
        RawSql.update(db, "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, "
                + "sequence_no, allocated_qty, picked_qty, short_qty, status, lpn, damage_report_id) VALUES (?::uuid, "
                + "?::uuid, gen_random_uuid(), ?::uuid, ?, ?, 1, ?, ?, ?, ?, ?, ?::uuid)", id, pick, reservation, SKU, BIN,
                allocated, picked, shortQty, status, lpn, damageReport);
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    private static String lpnOf(Connection db, String line) throws SQLException {
        return RawSql.text(db, "SELECT lpn FROM fulfillment.pick_line WHERE id = ?::uuid", line);
    }

    /** A refused migration rolled back whole: no archive, no new column, no new rule, the old status rule. */
    private static void assertNothingChanged(Connection db) throws SQLException {
        assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table IN "
                + "('fulfillment.pick', 'fulfillment.pick_line')")).as("nothing archived").isZero();
        assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'fulfillment' "
                + "AND table_name = 'pick_line' AND column_name = 'short_reason'")).as("no new column").isZero();
        assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname = ANY (?::text[])",
                "{" + String.join(",", List.of(NEW_RULES)) + "}")).as("no new rule").isZero();
        assertThat(RawSql.text(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_pick_status'"))
                .as("a task may still be SHORT").contains("SHORT");
        assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.pick_line WHERE lpn IS NULL AND status = 'OPEN' "
                + "AND id = ?::uuid", BACKFILL_LINE)).as("no LPN filled in, no line cancelled").isOne();
    }

    /** A change to the old rows, made as the old state was: no foreign keys, no triggers. */
    private static void fix(Connection db, String sql, Object... values) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        try {
            RawSql.update(db, sql, values);
        } finally {
            RawSql.update(db, "SET session_replication_role = DEFAULT");
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
