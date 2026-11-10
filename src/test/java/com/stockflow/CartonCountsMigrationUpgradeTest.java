package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * V20261113000300 on the real upgrade path (team plan 4C-3; kltn-docs 20 §4.2, §4.3, BR-04, BR-09, BR-14, BR-18, BR-22):
 * a database migrated to the migration before it, holding what the loose-count and loose-lot era could leave, then
 * migrated, with its five stops and everything it archives, relabels, detaches and sets back, and the rules it then
 * enforces on new rows.
 *
 * <p>Each stop is seeded alone and must fail the migration with its message (a failed migration leaves the database
 * as it was: PostgreSQL runs it in one transaction), then the condition is repaired so the next stop can be reached.
 * What the repairs leave behind is the data the successful run works on: an emptied row with no lot, a carton with an
 * emptied second row, counted lines of open counts that are not whole cartons, a draft carton counted off its
 * standard, a carton in history off its standard.</p>
 */
class CartonCountsMigrationUpgradeTest {

    private static final String BEFORE = "20261111000200";
    private static final String MIGRATION = "20261113000300";

    private static final String CUP = "UPG3-CUP";
    private static final String CUP2 = "UPG3-CUP2";
    private static final String BIN = "UPG3-A01-1-A";
    private static final String BIN2 = "UPG3-A01-1-B";
    private static final String DOCK = "UPG3-RCV01";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000c3000";
    private static final String LOT = "00000000-0000-4000-8000-0000000c3001";
    private static final String CUP_UNTRACED_LOT = "00000000-0000-4000-8000-0000000c3002";
    private static final String USER_1 = "00000000-0000-4000-8000-0000000c3010";
    private static final String USER_2 = "00000000-0000-4000-8000-0000000c3011";

    private static final String X = "00000000-0000-4000-8000-0000000c3101";
    private static final String Y = "00000000-0000-4000-8000-0000000c3102";
    private static final String Z = "00000000-0000-4000-8000-0000000c3103";
    private static final String W = "00000000-0000-4000-8000-0000000c3104";
    private static final String DRAFT = "00000000-0000-4000-8000-0000000c3105";
    private static final String CONSUMED = "00000000-0000-4000-8000-0000000c3106";
    private static final String LOST_OLD = "00000000-0000-4000-8000-0000000c3107";

    private static final String X_ROW = "00000000-0000-4000-8000-0000000c3201";
    private static final String Y_ROW = "00000000-0000-4000-8000-0000000c3202";
    private static final String Z_ROW_1 = "00000000-0000-4000-8000-0000000c3203";
    private static final String Z_ROW_2 = "00000000-0000-4000-8000-0000000c3204";
    private static final String W_ROW = "00000000-0000-4000-8000-0000000c3205";
    private static final String CONSUMED_ROW = "00000000-0000-4000-8000-0000000c3206";
    private static final String NO_LOT_ROW_1 = "00000000-0000-4000-8000-0000000c3207";
    private static final String NO_LOT_ROW_2 = "00000000-0000-4000-8000-0000000c3208";
    private static final String LOST_OLD_ROW = "00000000-0000-4000-8000-0000000c3209";

    private static final String COUNT_PLANNED = "00000000-0000-4000-8000-0000000c3301";
    private static final String COUNT_DONE = "00000000-0000-4000-8000-0000000c3302";
    private static final String COUNT_OPEN = "00000000-0000-4000-8000-0000000c3303";
    private static final String LOOSE_LINE_OPEN = "00000000-0000-4000-8000-0000000c3401";
    private static final String LOOSE_LINE_DONE = "00000000-0000-4000-8000-0000000c3402";
    private static final String PARTIAL_LINE = "00000000-0000-4000-8000-0000000c3403";
    private static final String WHOLE_LINE = "00000000-0000-4000-8000-0000000c3404";
    private static final String ADJUSTMENT_1 = "00000000-0000-4000-8000-0000000c3501";
    private static final String ADJUSTMENT_2 = "00000000-0000-4000-8000-0000000c3502";

    @Test
    void eachStopNamesWhatToFinishFirstAndTheRestIsArchivedRelabelledAndEnforced() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                // ---- stop 1: an open count with a loose line
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, "
                        + "status) VALUES (?::uuid, 'CC-UPG3-1', ?::uuid, 'MANUAL', 'PLANNED')", COUNT_PLANNED,
                        WAREHOUSE);
                RawSql.update(db, "INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, "
                        + "lot_number, expected_qty) VALUES (?::uuid, ?::uuid, ?, ?, 'L-1', 25)", LOOSE_LINE_OPEN,
                        COUNT_PLANNED, BIN, CUP);
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                stops(postgres, "open cycle counts with loose lines: CC-UPG3-1", "submit or cancel them first");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_package' AND column_name = 'unopened'"))
                        .as("a stopped migration changes nothing").isOne();
                assertThat(RawSql.number(db, "SELECT count(*) FROM flyway_schema_history WHERE version = ?",
                        MIGRATION)).isZero();
                // Cancelled, the count and its loose line are history.
                RawSql.update(db, "UPDATE inventory.cycle_count SET status = 'CANCELLED' WHERE id = ?::uuid",
                        COUNT_PLANNED);

                // ---- stop 2: a carton with two adjustments waiting for approval
                RawSql.update(db, "SET session_replication_role = replica");
                adjustment(db, ADJUSTMENT_1, "ADJ-UPG3-1", -2);
                adjustment(db, ADJUSTMENT_2, "ADJ-UPG3-2", -3);
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                stops(postgres, "cartons with more than one adjustment awaiting approval: LPN-UPG3-X",
                        "decide them first");
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "UPDATE inventory.stock_adjustment SET status = 'REJECTED', decided_by = ?::uuid, "
                        + "decided_at = NOW(), rejection_reason = 'Partial' WHERE id = ?::uuid", USER_2, ADJUSTMENT_2);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                // ---- stop 3: a live stock row with no lot
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "UPDATE inventory.stock_item SET on_hand = 2 WHERE id = ?::uuid", NO_LOT_ROW_1);
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                stops(postgres, "stock rows holding units with no lot: " + NO_LOT_ROW_1, "relabel them first");
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "UPDATE inventory.stock_item SET on_hand = 0 WHERE id = ?::uuid", NO_LOT_ROW_1);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                // ---- stop 4: a carton in stock off its standard count
                RawSql.update(db, "SET session_replication_role = replica");
                carton(db, Y, "LPN-UPG3-Y", BIN2, "IN_STOCK", true, 3);
                row(db, Y_ROW, BIN2, 3, Y, 6);
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                stops(postgres, "cartons in stock off their standard count: LPN-UPG3-Y", null);
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "UPDATE inventory.stock_package SET received_qty = 4 WHERE id = ?::uuid", Y);
                RawSql.update(db, "UPDATE inventory.stock_item SET on_hand = 4 WHERE id = ?::uuid", Y_ROW);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                // ---- stop 5: a carton with two rows holding units
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "UPDATE inventory.stock_item SET on_hand = 2 WHERE id IN (?::uuid, ?::uuid)",
                        Z_ROW_1, Z_ROW_2);
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                stops(postgres, "cartons with more than one stock row holding units: LPN-UPG3-Z", null);
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "UPDATE inventory.stock_item SET on_hand = 4 WHERE id = ?::uuid", Z_ROW_1);
                RawSql.update(db, "UPDATE inventory.stock_item SET on_hand = 0 WHERE id = ?::uuid", Z_ROW_2);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                // ---- the migration, at last
                long cartonsBefore = RawSql.number(db, "SELECT count(*) FROM inventory.stock_package");
                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                archived(db, cartonsBefore);
                relabelled(db);
                detachedAndSetBack(db);
                shape(db);
                enforced(db);
            }
        }
    }

    /**
     * A draft receipt's carton counted at another figure than its standard before V20261111000200 is set back to not
     * counted by the migration (archived), so the draft counts it again at the standard.
     */
    @Test
    void aDraftCartonCountedOffItsStandardIsSetBackToNotCounted() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                RawSql.update(db, "SET session_replication_role = replica");
                carton(db, DRAFT, "LPN-UPG3-D", DOCK, "RECEIVING", true, 3);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT row_id::text FROM platform.legacy_archive WHERE source_table = "
                        + "'inventory.stock_package' ORDER BY 1")).as("only the draft carton counted off its standard")
                        .containsExactly(DRAFT);
                assertThat(RawSql.text(db, "SELECT row_data->>'received_qty' FROM platform.legacy_archive WHERE "
                        + "source_table = 'inventory.stock_package' AND row_id = ?::uuid", DRAFT)).isEqualTo("3");
                assertThat(RawSql.text(db, "SELECT COALESCE(received_qty::text, 'not counted') FROM "
                        + "inventory.stock_package WHERE id = ?::uuid", DRAFT)).isEqualTo("not counted");
                assertThat(RawSql.text(db, "SELECT COALESCE(stock_item_id::text, 'no row') || ':' || on_hand FROM "
                        + "inventory.stock_package_balance WHERE stock_package_id = ?::uuid", DRAFT))
                        .as("a carton being received has no row yet").isEqualTo("no row:0");
                RawSql.update(db, "UPDATE inventory.stock_package SET received_qty = 4 WHERE id = ?::uuid", DRAFT);
                assertThat(RawSql.number(db, "SELECT received_qty FROM inventory.stock_package WHERE id = ?::uuid",
                        DRAFT)).as("counted again, at its standard").isEqualTo(4);
            }
        }
    }

    // ------------------------------------------------------------------ what the run did

    private static void archived(Connection db, long cartonsBefore) throws SQLException {
        assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                + "'inventory.stock_package.unopened'")).as("every carton's flag, whole").isEqualTo(cartonsBefore);
        assertThat(RawSql.texts(db, "SELECT row_data->>'lpn' || ':' || (row_data->>'unopened') FROM "
                + "platform.legacy_archive WHERE source_table = 'inventory.stock_package.unopened' AND row_data->>'lpn'"
                + " IN ('LPN-UPG3-X', 'LPN-UPG3-C', 'LPN-UPG3-O') ORDER BY 1"))
                .containsExactly("LPN-UPG3-C:false", "LPN-UPG3-O:false", "LPN-UPG3-X:true");
        assertThat(RawSql.texts(db, "SELECT row_id::text FROM platform.legacy_archive WHERE source_table = "
                + "'inventory.stock_item' ORDER BY 1")).as("the rows relabelled and the row detached")
                .containsExactlyInAnyOrder(NO_LOT_ROW_1, NO_LOT_ROW_2, Z_ROW_2);
        assertThat(RawSql.texts(db, "SELECT row_id::text FROM platform.legacy_archive WHERE source_table = "
                + "'inventory.cycle_count_line' ORDER BY 1")).as("only the open count's partial line")
                .containsExactly(PARTIAL_LINE);
        assertThat(RawSql.text(db, "SELECT row_data->>'counted_qty' || ':' || (row_data->>'expected_qty') FROM "
                + "platform.legacy_archive WHERE row_id = ?::uuid AND source_table = 'inventory.cycle_count_line'",
                PARTIAL_LINE)).as("archived as it was").isEqualTo("2:3");
    }

    private static void relabelled(Connection db) throws SQLException {
        assertThat(RawSql.texts(db, "SELECT lot_number || ':' || (lot_id IS NOT NULL) || ':' || on_hand FROM "
                + "inventory.stock_item WHERE id IN (?::uuid, ?::uuid) ORDER BY id", NO_LOT_ROW_1, NO_LOT_ROW_2))
                .containsExactly("UNTRACED:true:0", "UNTRACED:true:0");
        assertThat(RawSql.text(db, "SELECT lot_id::text FROM inventory.stock_item WHERE id = ?::uuid", NO_LOT_ROW_1))
                .as("the SKU's UNTRACED lot that was there is reused").isEqualTo(CUP_UNTRACED_LOT);
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_lot WHERE sku = ? AND lot_number = "
                + "'UNTRACED'", CUP)).isOne();
        assertThat(RawSql.text(db, "SELECT l.origin_type || ':' || l.created_by FROM inventory.stock_item s JOIN "
                + "inventory.stock_lot l ON l.id = s.lot_id WHERE s.id = ?::uuid", NO_LOT_ROW_2))
                .as("a SKU with none gets one, made by the migration").isEqualTo("UNTRACED:V20261113000300");
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_lot WHERE sku = ? AND lot_number = "
                + "'UNTRACED'", CUP2)).isOne();
        assertThat(RawSql.text(db, "SELECT lot_number || ':' || lot_id FROM inventory.stock_item WHERE id = "
                + "?::uuid", X_ROW)).as("a row that had a lot keeps it").isEqualTo("L-1:" + LOT);
    }

    private static void detachedAndSetBack(Connection db) throws SQLException {
        assertThat(RawSql.text(db, "SELECT COALESCE(stock_package_id::text, 'none') || ':' || on_hand FROM "
                + "inventory.stock_item WHERE id = ?::uuid", Z_ROW_2)).as("the emptied second row is detached")
                .isEqualTo("none:0");
        assertThat(RawSql.text(db, "SELECT stock_package_id::text || ':' || on_hand FROM inventory.stock_item WHERE id "
                + "= ?::uuid", Z_ROW_1)).as("the row holding the units stays").isEqualTo(Z + ":4");
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item WHERE stock_package_id IS NOT NULL "
                + "GROUP BY stock_package_id ORDER BY count(*) DESC LIMIT 1")).as("one row per carton").isOne();
        assertThat(RawSql.text(db, "SELECT COALESCE(counted_qty::text, 'not counted') || ':' || expected_qty || ':' || "
                + "(counted_by IS NULL) FROM inventory.cycle_count_line WHERE id = ?::uuid", PARTIAL_LINE))
                .as("the partial line is scanned again, booked whole").isEqualTo("not counted:4:true");
        assertThat(RawSql.text(db, "SELECT counted_qty || ':' || expected_qty FROM inventory.cycle_count_line WHERE "
                + "id = ?::uuid", WHOLE_LINE)).as("a line that was whole is left").isEqualTo("0:4");
        assertThat(RawSql.text(db, "SELECT counted_qty || ':' || expected_qty FROM inventory.cycle_count_line WHERE "
                + "id = ?::uuid", LOOSE_LINE_DONE)).as("a loose line of a completed count is history, untouched")
                .isEqualTo("23:25");
        assertThat(RawSql.text(db, "SELECT expected_qty::text FROM inventory.cycle_count_line WHERE id = ?::uuid",
                LOOSE_LINE_OPEN)).as("so is the one of a cancelled count").isEqualTo("25");
        assertThat(RawSql.text(db, "SELECT status || ':' || received_qty FROM inventory.stock_package WHERE id = "
                + "?::uuid", LOST_OLD)).as("a closed carton off its standard keeps its figure").isEqualTo("LOST:3");
    }

    private static void shape(Connection db) throws SQLException {
        assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                + "'inventory' AND table_name IN ('stock_package', 'stock_package_balance') AND column_name = "
                + "'unopened'")).as("the flag is gone from the table and the view").isZero();
        assertThat(RawSql.texts(db, "SELECT table_name || '.' || column_name || ':' || is_nullable FROM "
                + "information_schema.columns WHERE table_schema = 'inventory' AND table_name = 'stock_item' AND "
                + "column_name IN ('lot_number', 'lot_id') ORDER BY column_name"))
                .containsExactly("stock_item.lot_id:NO", "stock_item.lot_number:NO");
        assertThat(RawSql.texts(db, "SELECT indexname FROM pg_indexes WHERE schemaname = 'inventory' AND indexname IN "
                + "('uk_stock_item_package', 'uk_stock_item_package_layer', 'uk_stock_adjustment_pending_package') "
                + "ORDER BY 1")).containsExactly("uk_stock_adjustment_pending_package", "uk_stock_item_package");
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_package_balance")).as("one row per carton")
                .isEqualTo(RawSql.number(db, "SELECT count(*) FROM inventory.stock_package"));
        assertThat(RawSql.text(db, "SELECT lpn || ':' || lot_number || ':' || stock_status || ':' || on_hand || ':' "
                + "|| status FROM inventory.stock_package_balance WHERE stock_package_id = ?::uuid", X))
                .isEqualTo("LPN-UPG3-X:L-1:AVAILABLE:4:IN_STOCK");
        assertThat(RawSql.text(db, "SELECT status FROM inventory.stock_adjustment WHERE id = ?::uuid", ADJUSTMENT_1))
                .as("a pending adjustment of the history, partial as asked, is decided as it was asked")
                .isEqualTo("PENDING_APPROVAL");
        assertThat(RawSql.number(db, "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success",
                MIGRATION)).isOne();
    }

    // ------------------------------------------------------------------ the rules on new rows

    private static void enforced(Connection db) throws SQLException {
        // A lot on every row.
        refusedBy(db, "a row with no lot", "23502", "lot_number", "INSERT INTO inventory.stock_item (id, sku, "
                + "location_code, on_hand, reserved, status, created_at, received_at) VALUES (gen_random_uuid(), ?, ?, "
                + "1, 0, 'AVAILABLE', NOW(), NOW())", CUP, BIN);
        refusedBy(db, "a row with a blank lot", "23514", "ck_stock_item_lot", "INSERT INTO inventory.stock_item (id, "
                + "sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at) VALUES "
                + "(gen_random_uuid(), ?, ?, '  ', 1, 0, 'AVAILABLE', NOW(), NOW())", CUP, BIN);
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, "
                + "status, created_at, received_at) VALUES (gen_random_uuid(), ?, ?, 'UNTRACED', 1, 0, 'AVAILABLE', "
                + "NOW(), NOW())", CUP, BIN);

        // One row per carton; loose rows are still written.
        refusedBy(db, "a second row of a carton", "23505", "uk_stock_item_package", "INSERT INTO inventory.stock_item "
                + "(id, sku, location_code, lot_number, lot_id, on_hand, reserved, status, created_at, received_at, "
                + "stock_package_id) VALUES (gen_random_uuid(), ?, ?, 'L-1', ?::uuid, 0, 0, 'AVAILABLE', NOW(), "
                + "NOW() - interval '30 days', ?::uuid)", CUP, BIN, LOT, X);

        // The standard count, on insert too.
        refusedBy(db, "a carton in stock made off its standard", "23514", "ck_stock_package_standard_count",
                "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, "
                        + "received_qty, location_code, status) SELECT gen_random_uuid(), 'LPN-UPG3-NEW', i.id, u.id, "
                        + "u.factor, 3, ?, 'IN_STOCK' FROM inventory.inventory_items i JOIN inventory.uom_conversions u "
                        + "ON u.inventory_item_id = i.id AND u.unit_of_measure = 'CTN' WHERE i.sku = ?", BIN, CUP);
        refusedBy(db, "a draft carton made counted off its standard", "23514", "ck_stock_package_standard_count",
                "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, "
                        + "received_qty, location_code) SELECT gen_random_uuid(), 'LPN-UPG3-NEW2', i.id, u.id, "
                        + "u.factor, 5, ? FROM inventory.inventory_items i JOIN inventory.uom_conversions u "
                        + "ON u.inventory_item_id = i.id AND u.unit_of_measure = 'CTN' WHERE i.sku = ?",
                DOCK, CUP);
        refusedBy(db, "a closed carton off its standard coming back into stock", "23514",
                "ck_stock_package_standard_count", "UPDATE inventory.stock_package SET status = 'IN_STOCK', "
                        + "closed_at = NULL WHERE id = ?::uuid", LOST_OLD);

        // A count line is a carton, whole.
        refusedBy(db, "a loose count line", "23514", "names no carton", "INSERT INTO inventory.cycle_count_line (id, "
                + "cycle_count_id, location_code, sku, expected_qty) VALUES (gen_random_uuid(), ?::uuid, ?, ?, 4)",
                COUNT_OPEN, BIN, CUP);
        refusedBy(db, "a carton line booked at 3 of 4", "23514", "a carton is counted whole", "INSERT INTO "
                + "inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id) "
                + "VALUES (gen_random_uuid(), ?::uuid, ?, ?, 3, ?::uuid)", COUNT_OPEN, BIN, CUP, Z);
        refusedBy(db, "a counted line recounted at part of a carton", "23514", "a carton is counted whole",
                "UPDATE inventory.cycle_count_line SET counted_qty = 2, counted_by = ?::uuid, counted_at = NOW() "
                        + "WHERE id = ?::uuid", USER_1, PARTIAL_LINE);
        RawSql.update(db, "UPDATE inventory.cycle_count_line SET counted_qty = 4, counted_by = ?::uuid, counted_at = "
                + "NOW() WHERE id = ?::uuid", USER_1, PARTIAL_LINE);
        RawSql.update(db, "INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, "
                + "expected_qty, stock_package_id) VALUES (gen_random_uuid(), ?::uuid, ?, ?, 4, ?::uuid)", COUNT_OPEN,
                BIN, CUP, Z);

        // An adjustment of a carton moves it whole, for a reason of BR-04, one at a time.
        refusedBy(db, "a write-off of part of a carton", "23514", "adjusted whole", adjustmentSql("ADJ-UPG3-N1", -3,
                "DAMAGED"), BIN, CUP, USER_1, W);
        refusedBy(db, "a restock of more than a carton", "23514", "adjusted whole", adjustmentSql("ADJ-UPG3-N2", 5,
                "FOUND"), BIN, CUP, USER_1, W);
        for (String reason : new String[]{"EXPIRED", "SCRAP", "SAMPLE", "SUBCONTRACT_LOSS"}) {
            refusedBy(db, "a carton adjusted for " + reason, "23514", "lists six", adjustmentSql("ADJ-UPG3-R" + reason
                    .substring(0, 3), -4, reason), BIN, CUP, USER_1, W);
        }
        refusedBy(db, "a count variance of no carton", "23514", "count variance of no carton", "INSERT INTO "
                + "inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, "
                + "reason_code, cycle_count_line_id, requested_by) VALUES (gen_random_uuid(), 'ADJ-UPG3-N3', ?, ?, -4, "
                + "'COUNT_VARIANCE', ?::uuid, ?::uuid)", BIN, CUP, WHOLE_LINE, USER_1);
        RawSql.update(db, adjustmentSql("ADJ-UPG3-OK", -4, "LOST"), BIN, CUP, USER_1, W);
        refusedBy(db, "a second adjustment of the carton while one waits", "23505",
                "uk_stock_adjustment_pending_package", adjustmentSql("ADJ-UPG3-N4", 4, "FOUND"), BIN, CUP, USER_1, W);
        refusedBy(db, "a new adjustment of the carton the history left one pending for", "23505",
                "uk_stock_adjustment_pending_package", adjustmentSql("ADJ-UPG3-N5", -4, "DAMAGED"), BIN, CUP, USER_1, X);
        RawSql.update(db, "UPDATE inventory.stock_adjustment SET status = 'REJECTED', decided_by = ?::uuid, "
                + "decided_at = NOW(), rejection_reason = 'Partial, asked before whole cartons' WHERE id = ?::uuid",
                USER_2, ADJUSTMENT_1);
        RawSql.update(db, adjustmentSql("ADJ-UPG3-N6", -4, "DAMAGED"), BIN, CUP, USER_1, X);
        // The loose path keeps every reason of the history: returns and damage reports still write loose stock.
        RawSql.update(db, "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, "
                + "lot_number, quantity_delta, reason_code, requested_by) VALUES (gen_random_uuid(), 'ADJ-UPG3-L1', ?, "
                + "?, 'UNTRACED', -1, 'SCRAP', ?::uuid)", BIN, CUP, USER_1);
    }

    // ------------------------------------------------------------------ the old state

    private static void seed(Connection db) throws SQLException {
        // Foreign keys and row triggers are off while the old state is written; the warehouse, places, units and
        // users are real rows, which what is written after the migration (with them on) names.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, "
                + "map_height, version, created_at) VALUES (?::uuid, 'Upgrade 3', 'ACTIVE', 'UPG3', 'Somewhere', 'M', "
                + "50, 50, 0, NOW())", WAREHOUSE);
        RawSql.update(db, "INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, "
                + "storage_class, is_pickable, is_putaway_target, status) VALUES (gen_random_uuid(), ?::uuid, 'BIN', "
                + "?, 'NORMAL', TRUE, TRUE, 'ACTIVE'), (gen_random_uuid(), ?::uuid, 'BIN', ?, 'NORMAL', TRUE, TRUE, "
                + "'ACTIVE'), (gen_random_uuid(), ?::uuid, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')", WAREHOUSE, BIN,
                WAREHOUSE, BIN2, WAREHOUSE, DOCK);
        for (String user : new String[]{USER_1, USER_2}) {
            RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, created_at) VALUES "
                    + "(?::uuid, ?, ?, 'ACTIVE', NOW())", user, "u" + user.substring(user.length() - 4),
                    "u" + user.substring(user.length() - 4) + "@example.com");
        }
        for (String sku : new String[]{CUP, CUP2}) {
            RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, policy_configured, lot_tracked, "
                    + "removal_strategy) VALUES (gen_random_uuid(), ?, TRUE, FALSE, 'FEFO')", sku);
            RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                    + "is_base, active, version, created_at) SELECT gen_random_uuid(), id, 'EACH', 1, TRUE, TRUE, 0, "
                    + "NOW() FROM inventory.inventory_items WHERE sku = ?", sku);
            RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                    + "is_base, active, default_shipping, version, created_at) SELECT gen_random_uuid(), id, 'CTN', 4, "
                    + "FALSE, TRUE, TRUE, 0, NOW() FROM inventory.inventory_items WHERE sku = ?", sku);
        }
        RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at) "
                + "VALUES (?::uuid, ?, 'L-1', NOW() - interval '9 days', 'UNTRACED', NOW()), (?::uuid, ?, 'UNTRACED', "
                + "NOW() - interval '9 days', 'UNTRACED', NOW())", LOT, CUP, CUP_UNTRACED_LOT, CUP);

        // Cartons, as the loose and lot-less era left them.
        carton(db, X, "LPN-UPG3-X", BIN, "IN_STOCK", true, 4);
        row(db, X_ROW, BIN, 4, X, 9);
        carton(db, Z, "LPN-UPG3-Z", BIN, "IN_STOCK", true, 4);
        row(db, Z_ROW_1, BIN, 4, Z, 9);
        row(db, Z_ROW_2, BIN, 0, Z, 8);
        carton(db, W, "LPN-UPG3-W", BIN, "IN_STOCK", true, 4);
        row(db, W_ROW, BIN, 4, W, 9);
        carton(db, CONSUMED, "LPN-UPG3-C", BIN, "CONSUMED", false, 4);
        row(db, CONSUMED_ROW, BIN, 0, CONSUMED, 9);
        carton(db, LOST_OLD, "LPN-UPG3-O", BIN2, "LOST", false, 3);
        row(db, LOST_OLD_ROW, BIN2, 0, LOST_OLD, 9);
        // Emptied rows of the history with no lot at all (V20261111000110 boxed every live unit with a lot).
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, "
                + "created_at, received_at) VALUES (?::uuid, ?, ?, 0, 0, 'AVAILABLE', NOW(), NOW() - interval '20 days'), "
                + "(?::uuid, ?, ?, 0, 0, 'AVAILABLE', NOW(), NOW() - interval '20 days')", NO_LOT_ROW_1, CUP, BIN,
                NO_LOT_ROW_2, CUP2, BIN);

        // Counts: a completed one with a partial loose line, an open one with a partial and a whole carton line.
        RawSql.update(db, "INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, "
                + "started_at, completed_at) VALUES (?::uuid, 'CC-UPG3-2', ?::uuid, 'MANUAL', 'COMPLETED', NOW(), NOW())",
                COUNT_DONE, WAREHOUSE);
        RawSql.update(db, "INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, lot_number, "
                + "expected_qty, counted_qty, counted_by, counted_at) VALUES (?::uuid, ?::uuid, ?, ?, 'L-1', 25, 23, "
                + "?::uuid, NOW())", LOOSE_LINE_DONE, COUNT_DONE, BIN, CUP, USER_1);
        RawSql.update(db, "INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, "
                + "started_at) VALUES (?::uuid, 'CC-UPG3-3', ?::uuid, 'MANUAL', 'IN_PROGRESS', NOW())", COUNT_OPEN,
                WAREHOUSE);
        RawSql.update(db, "INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, lot_number, "
                + "expected_qty, counted_qty, counted_by, counted_at, stock_package_id) VALUES (?::uuid, ?::uuid, ?, "
                + "?, 'L-1', 3, 2, ?::uuid, NOW(), ?::uuid), (?::uuid, ?::uuid, ?, ?, 'L-1', 4, 0, ?::uuid, NOW(), "
                + "?::uuid)", PARTIAL_LINE, COUNT_OPEN, BIN, CUP, USER_1, X, WHOLE_LINE, COUNT_OPEN, BIN, CUP, USER_1, W);
        RawSql.update(db, "SET session_replication_role = DEFAULT");
    }

    private static void adjustment(Connection db, String id, String number, int delta) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, "
                + "lot_number, quantity_delta, reason_code, requested_by, stock_package_id) VALUES (?::uuid, ?, ?, ?, "
                + "'L-1', ?, 'DAMAGED', ?::uuid, ?::uuid)", id, number, BIN, CUP, delta, USER_1, X);
    }

    /** One pending carton adjustment of lot L-1, to bind with (location, SKU, requested by, carton). */
    private static String adjustmentSql(String number, int delta, String reason) {
        return "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, lot_number, "
                + "quantity_delta, reason_code, requested_by, stock_package_id) VALUES (gen_random_uuid(), '" + number
                + "', ?, ?, 'L-1', " + delta + ", '" + reason + "', ?::uuid, ?::uuid)";
    }

    private static void carton(Connection db, String id, String lpn, String location, String status, boolean unopened,
                               int received) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, received_qty, location_code, status, unopened, closed_at, created_at) SELECT ?::uuid, ?, "
                + "i.id, u.id, u.factor, ?, ?, ?, ?, CASE ? WHEN 'CONSUMED' THEN NOW() WHEN 'LOST' THEN NOW() END, NOW() FROM "
                + "inventory.inventory_items i JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id AND "
                + "u.unit_of_measure = 'CTN' WHERE i.sku = ?", id, lpn, received, location, status, unopened, status,
                CUP);
    }

    private static void row(Connection db, String id, String location, int onHand, String carton, int daysAgo)
            throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, lot_id, on_hand, "
                + "reserved, status, created_at, received_at, stock_package_id) VALUES (?::uuid, ?, ?, 'L-1', ?::uuid, "
                + "?, 0, 'AVAILABLE', NOW(), NOW() - (? || ' days')::interval, ?::uuid)", id, CUP, location, LOT, onHand,
                String.valueOf(daysAgo), carton);
    }

    // ------------------------------------------------------------------ helpers

    /** The migration fails with {@code message} (and {@code hint}, when given) in its text. */
    private static void stops(PostgreSQLContainer<?> postgres, String message, String hint) {
        var thrown = assertThatThrownBy(() -> flyway(postgres, MIGRATION).migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining(message);
        if (hint != null) {
            thrown.hasMessageContaining(hint);
        }
    }

    /** The statement is refused by the database as {@code state} (a CHECK, key or NOT NULL), naming {@code reason}. */
    private static void refusedBy(Connection db, String rule, String state, String reason, String sql, Object... values) {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> RawSql.update(db, sql, values));
        assertThat((Throwable) refusal).as("%s is refused, but the database accepted: %s", rule, sql).isNotNull();
        assertThat(refusal.getSQLState()).as("%s: %s", rule, refusal.getMessage()).isEqualTo(state);
        assertThat(refusal.getMessage()).as(rule).contains(reason);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
