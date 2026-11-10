package com.stockflow.fulfillment;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * V20261113000600 on the real upgrade path (team plan 4C-5; kltn-docs 07 BR-08, 06 BR-18, BR-22, 20 BR-20, 11 BR-09): an
 * old PACKING area becomes a DISPATCH area keeping its id, code and location, with the stock standing in it and the
 * ledger lines that name it; every pick already written is a DISPATCH task and its lines are untouched; the area CHECK
 * takes STAGING and refuses PACKING; and the new triggers and indexes hold on the migrated data. The owner's decision
 * QD-1 (2026-11-10) keeps nothing in {@code platform.legacy_archive} and stops nothing, so there is nothing of that to
 * check - only that the migration runs on rows an earlier schema accepted.
 *
 * <p>The old state is written with {@code session_replication_role = replica}: foreign keys and row triggers are off
 * (the orders and users these rows name are not what this is about), the CHECKs are not, so every old row is one the
 * old schema accepted.</p>
 */
class StagingMigrationUpgradeTest {

    private static final String BEFORE = "20261113000400";
    private static final String MIGRATION = "20261113000600";

    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000c5000";
    private static final String OTHER_WAREHOUSE = "00000000-0000-4000-8000-0000000c5001";
    private static final String BIN = "UPG5-A01-1-A";
    private static final String PACKING_AREA = "UPG5-PACK01";
    private static final String PACKING_AREA_ID = "00000000-0000-4000-8000-0000000c5010";
    private static final String PACKING_LOCATION_ID = "00000000-0000-4000-8000-0000000c5011";
    private static final String PICKING_TASK = "00000000-0000-4000-8000-0000000c5020";
    private static final String PICKED_TASK = "00000000-0000-4000-8000-0000000c5021";
    private static final String OPEN_LINE = "00000000-0000-4000-8000-0000000c5030";
    private static final String PICKED_LINE = "00000000-0000-4000-8000-0000000c5031";
    private static final String OLD_MOVE = "00000000-0000-4000-8000-0000000c5040";
    private static final String SKU = "UPG5-CUP";
    private static final String PICKER = "00000000-0000-4000-8000-0000000c5009";

    @Test
    @DisplayName("a PACKING area with stock and ledger lines becomes DISPATCH keeping its id, code and location; old picks are "
            + "DISPATCH tasks; the new CHECKs, triggers and indexes hold")
    void packingAreasBecomeDispatchAndTheNewRulesHold() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                assertThat(RawSql.text(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_area_type'"))
                        .as("before: PACKING is an area type").contains("PACKING").doesNotContain("STAGING");
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);
                letNewRowsNameNoOrder(db);

                // ---- the old packing area
                assertThat(RawSql.text(db, "SELECT type || ':' || code || ':' || location_id || ':' || version FROM warehouse.area "
                        + "WHERE id = ?::uuid", PACKING_AREA_ID))
                        .as("DISPATCH, same code and location, a new version").isEqualTo("DISPATCH:PACK01:" + PACKING_LOCATION_ID + ":1");
                assertThat(RawSql.text(db, "SELECT last_modified_by FROM warehouse.area WHERE id = ?::uuid", PACKING_AREA_ID))
                        .isEqualTo("V20261113000600");
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.area WHERE type = 'PACKING'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.area WHERE type = 'DISPATCH' AND id = ?::uuid",
                        PACKING_AREA_ID)).isOne();
                assertThat(RawSql.number(db, "SELECT on_hand FROM inventory.stock_item WHERE location_code = ?", PACKING_AREA))
                        .as("the stock standing there stays").isEqualTo(3);
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_movement WHERE id = ?::uuid AND "
                        + "to_location_code = ?", OLD_MOVE, PACKING_AREA)).as("and the ledger line naming it").isOne();
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = 'warehouse.area'"))
                        .as("QD-1: nothing is archived").isZero();

                // ---- the area type list
                assertThat(RawSql.text(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_area_type'"))
                        .contains("STAGING").contains("DISPATCH").doesNotContain("PACKING");
                RawSql.refused(db, "ck_area_type refuses a PACKING area",
                        areaOnly("UPG5-PK2", "PK2", "PACKING"));
                RawSql.refused(db, "ck_area_type refuses an unknown type", areaOnly("UPG5-PK3", "PK3", "WAITING"));
                RawSql.update(db, areaOnly("UPG5-STG01", "STG01", "STAGING"));
                assertThat(RawSql.text(db, "SELECT type FROM warehouse.area WHERE code = 'STG01' AND warehouse_id = ?::uuid",
                        WAREHOUSE)).isEqualTo("STAGING");

                // ---- the staging area is neither pickable nor a putaway target, at creation and later
                refusedWith(db, "neither pickable nor a putaway target", areaOnly("UPG5-STG02", "STG02", "STAGING", true, false));
                refusedWith(db, "neither pickable nor a putaway target", areaOnly("UPG5-STG03", "STG03", "STAGING", false, true));
                refusedWith(db, "is a staging area", "UPDATE warehouse.storage_location SET is_pickable = TRUE "
                        + "WHERE location_code = 'UPG5-STG01'");
                refusedWith(db, "is a staging area", "UPDATE warehouse.storage_location SET is_putaway_target = TRUE "
                        + "WHERE location_code = 'UPG5-STG01'");
                // an area holding stock does not turn into a staging area - the old packing area holds 3 units
                refusedWith(db, "holds stock", "UPDATE warehouse.area SET type = 'STAGING' WHERE id = '" + PACKING_AREA_ID + "'::uuid");
                assertThat(RawSql.text(db, "SELECT type FROM warehouse.area WHERE id = ?::uuid", PACKING_AREA_ID))
                        .isEqualTo("DISPATCH");

                // ---- the ledger: a staging area is entered and left only by picking
                refusedWith(db, "cannot touch staging area", move("MOVE", BIN, "UPG5-STG01", "RETURN_REQUEST_LINE", "'a'"));
                refusedWith(db, "cannot touch staging area", move("PUTAWAY", BIN, "UPG5-STG01", "PUTAWAY_TASK", "'b'"));
                RawSql.update(db, move("MOVE", BIN, "UPG5-STG01", "PICK_LINE", id(100)));
                RawSql.update(db, move("PICK", "UPG5-STG01", null, "PICK_LINE", id(101)));
                RawSql.update(db, move("COUNT_CORRECTION", "UPG5-STG01", null, "CYCLE_COUNT_LINE", id(102)));
                RawSql.update(db, move("MOVE", BIN, PACKING_AREA, "RETURN_REQUEST_LINE", id(103)));
                // a pick line stages out of one place once, and is put back out of the staging area once
                RawSql.refused(db, "uk_stock_movement_pick_line_move: staged out of the same place twice",
                        move("MOVE", BIN, "UPG5-STG01", "PICK_LINE", id(100)));
                RawSql.update(db, move("MOVE", "UPG5-STG01", BIN, "PICK_LINE", id(100)));
                RawSql.refused(db, "uk_stock_movement_pick_line_move: a third MOVE out of the staging area",
                        move("MOVE", "UPG5-STG01", BIN, "PICK_LINE", id(100)));
                // the other movements stay one per pick line and type
                RawSql.refused(db, "uk_stock_movement_pick_line: a second PICK",
                        move("PICK", "UPG5-STG01", null, "PICK_LINE", id(101)));

                // ---- the picks
                assertThat(RawSql.texts(db, "SELECT id::text || ':' || destination || ':' || coalesce(staging_location_code, '-') "
                        + "|| ':' || status FROM fulfillment.pick ORDER BY id"))
                        .containsExactly(PICKING_TASK + ":DISPATCH:-:PICKING", PICKED_TASK + ":DISPATCH:-:PICKED");
                assertThat(RawSql.texts(db, "SELECT id::text || ':' || status || ':' || coalesce(staged_at::text, '-') "
                        + "FROM fulfillment.pick_line ORDER BY id"))
                        .as("their lines are untouched").containsExactly(OPEN_LINE + ":OPEN:-", PICKED_LINE + ":PICKED:-");
                RawSql.refused(db, "ck_pick_destination: a destination outside DISPATCH and STAGING",
                        pickInsert(id(113), "UPG5-STG01").replace("'STAGING'", "'X'"));
                RawSql.refused(db, "ck_pick_staging_location: STAGING without an area",
                        pickInsert(id(114), "UPG5-STG01").replace(", 'UPG5-STG01')", ", NULL)"));
                RawSql.refused(db, "ck_pick_staging_location: DISPATCH with an area",
                        pickInsert(id(115), "UPG5-STG01").replace("'STAGING'", "'DISPATCH'"));
                refusedWith(db, "only while it is PENDING", "UPDATE fulfillment.pick SET destination = 'STAGING', "
                        + "staging_location_code = 'UPG5-STG01' WHERE id = '" + PICKING_TASK + "'::uuid");
                refusedWith(db, "only while it is PENDING", "UPDATE fulfillment.pick SET assigned_user_id = NULL "
                        + "WHERE id = '" + PICKING_TASK + "'::uuid");
                RawSql.refused(db, "ck_pick_staged: STAGED with destination DISPATCH",
                        "UPDATE fulfillment.pick SET status = 'STAGED', staged_at = NOW() WHERE id = '" + PICKING_TASK + "'::uuid");
                RawSql.refused(db, "ck_pick_status: PACKING is not a pick status",
                        "UPDATE fulfillment.pick SET status = 'PACKING' WHERE id = '" + PICKING_TASK + "'::uuid");
                // a staging area of another warehouse, or a bin, is not a staging area of the task's warehouse
                RawSql.update(db, areaOnly(OTHER_WAREHOUSE, "UPG6-STG01", "STG01", "STAGING", false, false));
                refusedWith(db, "not a staging area of warehouse", pickInsert(id(110), "UPG6-STG01"));
                refusedWith(db, "not a staging area of warehouse", pickInsert(id(111), BIN));
                RawSql.update(db, pickInsert(id(112), "UPG5-STG01"));
                assertThat(RawSql.text(db, "SELECT destination FROM fulfillment.pick WHERE id = ?::uuid", id(112)))
                        .isEqualTo("STAGING");

                // ---- the lines
                RawSql.refused(db, "ck_pick_line_staged: STAGED already deducted", lineInsert(id(120), "STAGED", "NOW()", "'UPG5-STG01'", "NOW()"));
                RawSql.refused(db, "ck_pick_line_staged: a time with no place", lineInsert(id(121), "OPEN", "NOW()", "NULL", "NULL"));
                RawSql.update(db, lineInsert(id(122), "STAGED", "NOW()", "'UPG5-STG01'", "NULL"));
                RawSql.update(db, putBackLineInsert(id(123), "NOW()", "'UPG5-STG01'"));
                // (a line with a hold that was put back neither staged nor deducted is QA 64's LN13: it needs a hold)
            }
        }
    }

    /**
     * The rows written after the migration are about its own rules, not about the orders, order lines and holds they would
     * have to name: those foreign keys and the carton-of-the-hold trigger come off, what this migration added stays.
     */
    private static void letNewRowsNameNoOrder(Connection db) throws SQLException {
        for (String fk : RawSql.texts(db, "SELECT conname FROM pg_constraint WHERE contype = 'f' AND conrelid = "
                + "'fulfillment.pick'::regclass AND conname NOT IN ('fk_pick_staging_location', 'fk_pick_moved_to_dispatch_by')")) {
            RawSql.update(db, "ALTER TABLE fulfillment.pick DROP CONSTRAINT " + fk);
        }
        for (String fk : RawSql.texts(db, "SELECT conname FROM pg_constraint WHERE contype = 'f' AND conrelid = "
                + "'fulfillment.pick_line'::regclass AND confrelid <> 'fulfillment.pick'::regclass "
                + "AND conname <> 'fk_pick_line_staged_location'")) {
            RawSql.update(db, "ALTER TABLE fulfillment.pick_line DROP CONSTRAINT " + fk);
        }
        // a line must be of the task's own order and on the carton of its hold (QA 63's rule); these have neither. The
        // line CHECKs of this migration are not triggers and stay.
        RawSql.update(db, "ALTER TABLE fulfillment.pick_line DISABLE TRIGGER USER");
    }

    // ---- the old state --------------------------------------------------------------------------------------------

    private static String id(int n) {
        return "00000000-0000-4000-8000-0000000c5%03d".formatted(n);
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        try {
            for (String warehouse : new String[] {WAREHOUSE, OTHER_WAREHOUSE}) {
                RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, "
                        + "map_height, created_at) VALUES (?::uuid, 'Upgrade warehouse', 'ACTIVE', ?, 'x', 'M', 50, 50, NOW())",
                        warehouse, warehouse.equals(WAREHOUSE) ? "UPG5" : "UPG6");
            }
            RawSql.update(db, "INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, "
                    + "is_pickable, is_putaway_target, status) VALUES (?::uuid, ?::uuid, 'BIN', ?, 'NORMAL', TRUE, TRUE, 'ACTIVE')",
                    id(1), WAREHOUSE, BIN);
            RawSql.update(db, "INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, "
                    + "is_pickable, is_putaway_target, status) VALUES (?::uuid, ?::uuid, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')",
                    PACKING_LOCATION_ID, WAREHOUSE, PACKING_AREA);
            RawSql.update(db, "INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, "
                    + "length, rotation, is_obstacle, status) VALUES (?::uuid, ?::uuid, ?::uuid, 'PACK01', 'PACKING', 'Packing', "
                    + "1, 1, 2, 2, 0, FALSE, 'ACTIVE')", PACKING_AREA_ID, WAREHOUSE, PACKING_LOCATION_ID);
            RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, policy_configured, lot_tracked, "
                    + "removal_strategy) VALUES (gen_random_uuid(), ?, TRUE, FALSE, 'FEFO')", SKU);
            // Stock standing in the packing area, and the ledger line that put it there.
            RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, "
                    + "created_at, received_at) VALUES (?::uuid, ?, ?, 'L-1', 3, 0, 'AVAILABLE', NOW(), NOW())",
                    id(2), SKU, PACKING_AREA);
            RawSql.update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, "
                    + "quantity, reference_type, reference_id, occurred_at) VALUES (?::uuid, 'MOVE', ?, ?, ?, 3, "
                    + "'RETURN_REQUEST_LINE', gen_random_uuid(), NOW())", OLD_MOVE, SKU, BIN, PACKING_AREA);
            // Two tasks as the old schema wrote them, and a line each.
            RawSql.update(db, "INSERT INTO fulfillment.pick (id, order_id, status, created_at, order_number, warehouse_id, "
                    + "needs_put_back, assigned_user_id) VALUES (?::uuid, gen_random_uuid(), 'PICKING', NOW(), 'SO-U5-1', ?::uuid, "
                    + "FALSE, ?::uuid)", PICKING_TASK, WAREHOUSE, PICKER);
            RawSql.update(db, "INSERT INTO fulfillment.pick (id, order_id, status, created_at, order_number, warehouse_id, "
                    + "needs_put_back, assigned_user_id) VALUES (?::uuid, gen_random_uuid(), 'PICKED', NOW(), 'SO-U5-2', ?::uuid, "
                    + "FALSE, ?::uuid)", PICKED_TASK, WAREHOUSE, PICKER);
            RawSql.update(db, "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, sequence_no, "
                    + "allocated_qty, status, lpn) VALUES (?::uuid, ?::uuid, gen_random_uuid(), ?, ?, 1, 4, 'OPEN', 'LPN-UPG5-1')",
                    OPEN_LINE, PICKING_TASK, SKU, BIN);
            RawSql.update(db, "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, sequence_no, "
                    + "allocated_qty, picked_qty, status, picked_by, picked_at, consumed_at, lpn) VALUES (?::uuid, ?::uuid, "
                    + "gen_random_uuid(), ?, ?, 1, 4, 4, 'PICKED', ?::uuid, NOW(), NOW(), 'LPN-UPG5-2')",
                    PICKED_LINE, PICKED_TASK, SKU, BIN, PICKER);
        } finally {
            RawSql.update(db, "SET session_replication_role = DEFAULT");
        }
    }

    // ---- statements -----------------------------------------------------------------------------------------------

    private static String areaOnly(String locationCode, String code, String type) {
        return areaOnly(WAREHOUSE, locationCode, code, type, false, false);
    }

    private static String areaOnly(String locationCode, String code, String type, boolean pickable, boolean putaway) {
        return areaOnly(WAREHOUSE, locationCode, code, type, pickable, putaway);
    }

    /** A location and its area in one statement: a storage location must be owned by an area when it commits. */
    private static String areaOnly(String warehouse, String locationCode, String code, String type, boolean pickable,
                                   boolean putaway) {
        return """
                WITH l AS (INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                                   is_pickable, is_putaway_target, status)
                           VALUES (gen_random_uuid(), '%1$s'::uuid, 'AREA', '%2$s', 'NORMAL', %5$s, %6$s, 'ACTIVE')
                           RETURNING id)
                INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation,
                                            is_obstacle, status)
                SELECT gen_random_uuid(), '%1$s'::uuid, l.id, '%3$s', '%4$s', 'x', 1, 1, 2, 2, 0, FALSE, 'ACTIVE' FROM l"""
                .formatted(warehouse, locationCode, code, type, pickable, putaway);
    }

    private static String move(String type, String from, String to, String referenceType, String referenceId) {
        String reference = referenceId.startsWith("'") ? "md5(" + referenceId + ")::uuid" : "'" + referenceId + "'::uuid";
        return "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity, "
                + "reference_type, reference_id, occurred_at) VALUES (gen_random_uuid(), '" + type + "', '" + SKU + "', "
                + (from == null ? "NULL" : "'" + from + "'") + ", " + (to == null ? "NULL" : "'" + to + "'") + ", 1, '"
                + referenceType + "', " + reference + ", NOW())";
    }

    private static String pickInsert(String id, String stagingLocation) {
        return "INSERT INTO fulfillment.pick (id, order_id, status, created_at, order_number, warehouse_id, destination, "
                + "staging_location_code) VALUES ('" + id + "'::uuid, gen_random_uuid(), 'PENDING', NOW(), 'SO-U5-" + id.substring(33)
                + "', '" + WAREHOUSE + "'::uuid, 'STAGING', '" + stagingLocation + "')";
    }

    private static String lineInsert(String id, String status, String stagedAt, String stagedLocation, String consumedAt) {
        return "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, sequence_no, allocated_qty, "
                + "picked_qty, status, picked_by, picked_at, consumed_at, lpn, staged_at, staged_location_code) VALUES ('" + id
                + "'::uuid, '" + PICKING_TASK + "'::uuid, gen_random_uuid(), '" + SKU + "', '" + BIN + "', " + sequence(id) + ", 4, "
                + (status.equals("OPEN") ? "0" : "4") + ", '" + status + "', " + (status.equals("OPEN") ? "NULL" : "'" + PICKER + "'::uuid")
                + ", " + (status.equals("OPEN") ? "NULL" : "NOW()") + ", " + consumedAt + ", 'LPN-UPG5-" + sequence(id) + "', " + stagedAt + ", "
                + stagedLocation + ")";
    }

    /** A sequence number and an LPN suffix told apart by the row, so no two lines of the task share either. */
    private static int sequence(String id) {
        return Integer.parseInt(id.substring(33));
    }

    private static String putBackLineInsert(String id, String stagedAt, String stagedLocation) {
        return "INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, sequence_no, allocated_qty, "
                + "picked_qty, status, picked_by, picked_at, lpn, staged_at, staged_location_code, put_back_qty, put_back_by, "
                + "put_back_at, put_back_location_code) VALUES ('" + id + "'::uuid, '" + PICKING_TASK + "'::uuid, "
                + "gen_random_uuid(), '" + SKU + "', '" + BIN + "', " + sequence(id) + ", 4, 4, 'PUT_BACK', '" + PICKER + "'::uuid, NOW(), "
                + "'LPN-UPG5-" + sequence(id) + "', " + stagedAt + ", " + stagedLocation + ", 4, '" + PICKER + "'::uuid, NOW(), '" + BIN + "')";
    }

    /** The statement is refused by a rule of the database, and the rule says {@code message}. */
    private static void refusedWith(Connection db, String message, String sql) {
        Throwable refusal = catchThrowable(() -> RawSql.update(db, sql));
        assertThat(refusal).as("refused with '%s': %s", message, sql).isInstanceOf(SQLException.class)
                .hasMessageContaining(message);
        assertThat(((SQLException) refusal).getSQLState()).startsWith("23");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
