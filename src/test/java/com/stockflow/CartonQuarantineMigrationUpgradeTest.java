package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * V20261112000200 on the real upgrade path (team plan 4D-1; kltn-docs 05 BR-01, BR-14; 20 §4.8, BR-21). A putaway task
 * written before gets the source it had, RECEIPT; a legacy return-line task is archived whole and deleted with its
 * column; {@code ck_putaway_task_source} then admits a receipt's task and a quarantine release, each only in its own
 * shape, and a release only of a carton whose stock is all QUARANTINE. Both quarantine checks are validated on rows
 * written before (the old return flow's exit from quarantine is allowed); a legacy QUARANTINE row still holding units
 * for an order stops the migration, naming it, since the check would refuse every later update of that row.
 */
class CartonQuarantineMigrationUpgradeTest {

    private static final String BEFORE = "20261111000200";
    private static final String MIGRATION = "20261112000200";

    private static final String CUP = "UPG4D-CUP";
    private static final String BIN = "UPG4D-A01-1-A";
    private static final String QUARANTINE_AREA = "UPG4D-QC01";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000d1000";
    private static final String LOT = "00000000-0000-4000-8000-0000000d1001";
    private static final String GOOD = "00000000-0000-4000-8000-0000000d1101";
    private static final String QUARANTINED = "00000000-0000-4000-8000-0000000d1102";
    private static final String HELD_LEGACY = "00000000-0000-4000-8000-0000000d1103";
    private static final String GOOD_ROW = "00000000-0000-4000-8000-0000000d1201";
    private static final String QUARANTINED_ROW = "00000000-0000-4000-8000-0000000d1202";
    private static final String HELD_LEGACY_ROW = "00000000-0000-4000-8000-0000000d1203";
    private static final String RECEIPT_TASK = "00000000-0000-4000-8000-0000000d1301";
    private static final String RETURN_TASK = "00000000-0000-4000-8000-0000000d1302";
    private static final String RETURN_LINE = "00000000-0000-4000-8000-0000000d1401";
    private static final String OLD_RETURN_EXIT = "00000000-0000-4000-8000-0000000d1501";

    private static final String NEW_TASK = "INSERT INTO warehouse.putaway_task (id, source, goods_receipt_id, "
            + "goods_receipt_line_id, sku, quantity, status, created_at, stock_package_id) VALUES (gen_random_uuid(), "
            + "?, ?::uuid, ?::uuid, ?, 10, 'PENDING', NOW(), ?::uuid)";

    @Test
    void putawayTasksGetASourceAndQuarantineRulesHold() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db, false);

                var result = flyway(postgres, MIGRATION).migrate();
                // Merged with the other lines, migrations of theirs may sit between BEFORE and this one (final rescope merge).
                assertThat(result.migrations).extracting(m -> m.version).last().isEqualTo(MIGRATION);

                // The receipt's task is a RECEIPT task; the return-line task is archived whole and gone, with its column.
                assertThat(RawSql.text(db, "SELECT source FROM warehouse.putaway_task WHERE id = ?::uuid",
                        RECEIPT_TASK)).isEqualTo("RECEIPT");
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.putaway_task WHERE id = ?::uuid",
                        RETURN_TASK)).isZero();
                assertThat(RawSql.text(db, "SELECT row_data ->> 'return_request_line_id' FROM platform.legacy_archive "
                        + "WHERE source_table = 'warehouse.putaway_task' AND row_id = ?::uuid", RETURN_TASK))
                        .isEqualTo(RETURN_LINE);
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'warehouse' AND table_name = 'putaway_task' AND column_name = 'return_request_line_id'"))
                        .isZero();

                // ck_putaway_task_source: each source in its own shape.
                RawSql.refused(db, "a receipt task naming no line", NEW_TASK, "RECEIPT", null, null, CUP, GOOD);
                RawSql.refused(db, "a release naming a receipt line", NEW_TASK, "QUARANTINE_RELEASE",
                        "00000000-0000-4000-8000-0000000d9001", "00000000-0000-4000-8000-0000000d9002", CUP,
                        QUARANTINED);
                RawSql.refused(db, "a release of no carton", NEW_TASK, "QUARANTINE_RELEASE", null, null, CUP, null);
                RawSql.refused(db, "a source of the old flows", NEW_TASK, "RETURN", null, null, CUP, QUARANTINED);
                // check_putaway_task_package: a release only of a carton whose stock is all QUARANTINE.
                RawSql.refused(db, "a release of a sellable carton", NEW_TASK, "QUARANTINE_RELEASE", null, null, CUP,
                        GOOD);
                RawSql.update(db, NEW_TASK, "QUARANTINE_RELEASE", null, null, CUP, QUARANTINED);
                RawSql.refused(db, "a task changing its source", "UPDATE warehouse.putaway_task SET source = "
                        + "'QUARANTINE_RELEASE' WHERE id = ?::uuid", RECEIPT_TASK);

                // Both checks are validated: no row written before breaks them (the old return exit is allowed).
                assertThat(RawSql.texts(db, "SELECT conname || ':' || convalidated FROM pg_constraint WHERE conname IN "
                        + "('ck_stock_item_quarantine_unheld', 'ck_stock_movement_quarantine_exit') ORDER BY conname"))
                        .containsExactly("ck_stock_item_quarantine_unheld:true",
                                "ck_stock_movement_quarantine_exit:true");
                // No quarantined row takes a hold, and a held row is not quarantined.
                RawSql.refused(db, "a hold on a quarantined row", "UPDATE inventory.stock_item SET reserved = 2 "
                        + "WHERE id = ?::uuid", QUARANTINED_ROW);
                RawSql.update(db, "UPDATE inventory.stock_item SET reserved = 2 WHERE id = ?::uuid", GOOD_ROW);
                RawSql.refused(db, "a held row quarantined", "UPDATE inventory.stock_item SET status = 'QUARANTINE' "
                        + "WHERE id = ?::uuid", GOOD_ROW);

                // The ledger: a quarantine's STATUS_CHANGE names two places; quarantined stock leaves only rightly.
                RawSql.update(db, line("STATUS_CHANGE", BIN, QUARANTINE_AREA, "AVAILABLE", "QUARANTINE",
                        "STOCK_PACKAGE"));
                RawSql.refused(db, "a STATUS_CHANGE across places of another document",
                        line("STATUS_CHANGE", BIN, QUARANTINE_AREA, "AVAILABLE", "QUARANTINE", "MOVE_TASK"));
                RawSql.refused(db, "a release by status change across places",
                        line("STATUS_CHANGE", QUARANTINE_AREA, BIN, "QUARANTINE", "AVAILABLE", "STOCK_PACKAGE"));
                RawSql.refused(db, "quarantined stock made sellable by a hand move",
                        line("MOVE", QUARANTINE_AREA, BIN, "QUARANTINE", "AVAILABLE", "MOVE_TASK"));
                RawSql.refused(db, "quarantined stock picked",
                        line("PICK", QUARANTINE_AREA, null, "QUARANTINE", "QUARANTINE", "PICK_LINE"));
                RawSql.update(db, line("MOVE", QUARANTINE_AREA, BIN, "QUARANTINE", "AVAILABLE", "PUTAWAY_TASK"));
                RawSql.update(db, line("ADJUSTMENT", QUARANTINE_AREA, null, "QUARANTINE", "QUARANTINE",
                        "STOCK_ADJUSTMENT"));
                RawSql.update(db, line("MOVE", QUARANTINE_AREA, BIN, "QUARANTINE", "QUARANTINE", "MOVE_TASK"));
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_movement WHERE id = ?::uuid",
                        OLD_RETURN_EXIT)).isOne();
            }
        }
    }

    /**
     * A QUARANTINE row still holding units for an order would be refused by the check on every later update - a pick
     * decrementing the hold included - so the migration stops and names it rather than leave it NOT VALID.
     */
    @Test
    void aHeldQuarantinedRowStopsTheMigration() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db, true);

                Throwable stopped = catchThrowable(() -> flyway(postgres, MIGRATION).migrate());

                assertThat(stopped).isNotNull();
                assertThat(stopped).rootCause().hasMessageContaining(HELD_LEGACY_ROW);
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'warehouse' AND table_name = 'putaway_task' AND column_name = 'source'")).isZero();
            }
        }
    }

    private static String line(String type, String from, String to, String status, String toStatus, String reference) {
        // Every side names the quarantined carton, as the carton rules of V20261111000200 want of a MOVE.
        return "INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, "
                + "to_location_code, quantity, from_status, to_status, reference_type, reference_id, occurred_at, "
                + "from_stock_package_id, to_stock_package_id) "
                + "VALUES (gen_random_uuid(), '" + type + "', '" + CUP + "', 'L-1', '" + LOT + "'::uuid, "
                + (from == null ? "NULL" : "'" + from + "'") + ", " + (to == null ? "NULL" : "'" + to + "'")
                + ", 10, '" + status + "', '" + toStatus + "', '" + reference + "', gen_random_uuid(), NOW(), "
                + (from == null ? "NULL" : "'" + QUARANTINED + "'::uuid") + ", "
                + (to == null ? "NULL" : "'" + QUARANTINED + "'::uuid") + ")";
    }

    private static void seed(Connection db, boolean heldQuarantinedRow) throws SQLException {
        // Foreign keys and row triggers are off while the old state is written; the SKU, its units, its lot and the
        // places are real rows, which the cartons, rows and ledger lines name.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, "
                + "is_pickable, is_putaway_target, status) VALUES (gen_random_uuid(), ?::uuid, 'BIN', ?, 'NORMAL', TRUE, "
                + "TRUE, 'ACTIVE'), (gen_random_uuid(), ?::uuid, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')",
                WAREHOUSE, BIN, WAREHOUSE, QUARANTINE_AREA);
        RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, policy_configured, lot_tracked, "
                + "removal_strategy) VALUES (gen_random_uuid(), ?, TRUE, FALSE, 'FIFO')", CUP);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "is_base, active, version, created_at) SELECT gen_random_uuid(), id, 'EACH', 1, TRUE, TRUE, 0, NOW() "
                + "FROM inventory.inventory_items WHERE sku = ?", CUP);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "is_base, active, default_shipping, version, created_at) SELECT gen_random_uuid(), id, 'CTN', 10, "
                + "FALSE, TRUE, TRUE, 0, NOW() FROM inventory.inventory_items WHERE sku = ?", CUP);
        RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at) "
                + "VALUES (?::uuid, ?, 'L-1', NOW() - interval '9 days', 'UNTRACED', NOW())", LOT, CUP);

        carton(db, GOOD, "LPN-UPG4D-G", BIN);
        row(db, GOOD_ROW, BIN, "AVAILABLE", 0, GOOD);
        carton(db, QUARANTINED, "LPN-UPG4D-Q", QUARANTINE_AREA);
        row(db, QUARANTINED_ROW, QUARANTINE_AREA, "QUARANTINE", 0, QUARANTINED);
        if (heldQuarantinedRow) {
            // Legacy: a quarantined row that still holds units for an order (what the new CHECK forbids).
            carton(db, HELD_LEGACY, "LPN-UPG4D-H", QUARANTINE_AREA);
            row(db, HELD_LEGACY_ROW, QUARANTINE_AREA, "QUARANTINE", 4, HELD_LEGACY);
        }

        RawSql.update(db, "INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, "
                + "quantity, status, created_at, stock_package_id) VALUES (?::uuid, gen_random_uuid(), "
                + "gen_random_uuid(), ?, 10, 'DONE', NOW() - interval '9 days', ?::uuid)", RECEIPT_TASK, CUP, GOOD);
        RawSql.update(db, "INSERT INTO warehouse.putaway_task (id, return_request_line_id, sku, quantity, status, "
                + "created_at) VALUES (?::uuid, ?::uuid, ?, 3, 'PENDING', NOW() - interval '5 days')", RETURN_TASK,
                RETURN_LINE, CUP);
        // The old customer-return flow released quarantined goods by reclassifying them: allowed by the new CHECK.
        RawSql.update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, "
                + "from_location_code, to_location_code, quantity, from_status, to_status, reference_type, reference_id, "
                + "occurred_at) VALUES (?::uuid, 'MOVE', ?, 'L-1', ?::uuid, ?, ?, 2, 'QUARANTINE', 'AVAILABLE', "
                + "'RETURN_REQUEST_LINE', ?::uuid, NOW() - interval '5 days')", OLD_RETURN_EXIT, CUP, LOT,
                QUARANTINE_AREA, BIN, RETURN_LINE);
        RawSql.update(db, "SET session_replication_role = DEFAULT");
    }

    private static void carton(Connection db, String id, String lpn, String location) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, received_qty, location_code, status, unopened, created_at) SELECT ?::uuid, ?, i.id, "
                + "u.id, u.factor, u.factor, ?, 'IN_STOCK', TRUE, NOW() FROM inventory.inventory_items i JOIN "
                + "inventory.uom_conversions u ON u.inventory_item_id = i.id AND u.unit_of_measure = 'CTN' WHERE "
                + "i.sku = ?", id, lpn, location, CUP);
    }

    private static void row(Connection db, String id, String location, String status, int reserved, String carton)
            throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, lot_id, on_hand, "
                + "reserved, status, created_at, received_at, stock_package_id) VALUES (?::uuid, ?, ?, 'L-1', ?::uuid, "
                + "10, ?, ?, NOW(), NOW() - interval '9 days', ?::uuid)", id, CUP, location, LOT, reserved, status,
                carton);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
