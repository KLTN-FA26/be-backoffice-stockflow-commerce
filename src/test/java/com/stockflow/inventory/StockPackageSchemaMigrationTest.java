package com.stockflow.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static com.stockflow.support.RawSql.refused;
import static com.stockflow.support.RawSql.text;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The rules V20261020000100-0600 (and V20261230000600, the carton is the stock) put on stock packages and the units
 * documents count in, on a fresh database with the demo seed. tools/db/qa/15_stock_packages.sql holds the full set; these are the ones the build must keep, since no
 * workflow runs that script: a later migration replacing one of these functions would otherwise pass CI.
 *
 * <p>Each test makes its own units and stock packages, so they share one migrated database.</p>
 */
class StockPackageSchemaMigrationTest {

    /** The items of the demo cups {@code LY-GIAY-DEMO-12OZ-XANH} and {@code LY-GIAY-DEMO-12OZ-DO}. */
    private static final String BLUE_CUP =
            "(SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH')";
    private static final String RED_CUP =
            "(SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-DO')";

    private static PostgreSQLContainer<?> pg;

    @BeforeAll
    static void migrate() {
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    /** A packing unit of LY-GIAY-DEMO-12OZ-XANH, factor 10. */
    private static String unit(Connection db, String name) throws SQLException {
        // 01 BR-03: the item has one active carton, so the earlier tests' units are retired (their cartons stay).
        update(db, "UPDATE inventory.uom_conversions SET active = FALSE WHERE "
                + "inventory_item_id = " + BLUE_CUP + " AND active");
        return unit(db, name, true);
    }

    private static String unit(Connection db, String name, boolean active) throws SQLException {
        String id = "md5('it:unit:" + name + "')::uuid";
        update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active) "
                + "VALUES (" + id + ", " + BLUE_CUP + ", '" + name + "', 10, " + active + ")");
        return id;
    }

    /** The UNTRACED lot of the blue cup these cartons are of, opened the first time. */
    private static final String LOT = "md5('it:lot:UNTRACED')::uuid";

    private static void lot(Connection db) throws SQLException {
        update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, origin_type, created_at) VALUES (" + LOT
                + ", 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'UNTRACED', NOW()) ON CONFLICT (id) DO NOTHING");
    }

    /** A whole stock package of 10 in stock at {@code location}, AVAILABLE, of lot UNTRACED: the carton is the stock. */
    private static String inStock(Connection db, String name, String unit, String location) throws SQLException {
        String id = "md5('it:pk:" + name + "')::uuid";
        lot(db);
        update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,"
                + " counted_at, location_code, status, stock_status, lot_id, received_at) VALUES (" + id + ", 'IT-"
                + name + "', " + BLUE_CUP + ", " + unit + ", 10, NOW(), '" + location + "', 'IN_STOCK', 'AVAILABLE', "
                + LOT + ", NOW() - interval '1 day')");
        return id;
    }

    @Test
    void aUnitInUseKeepsItsFactorItemAndName() throws Exception {
        try (Connection db = connect()) {
            String carton = unit(db, "LOCKED");
            String unused = unit(db, "UNUSED", false);
            update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,"
                    + " location_code) VALUES (gen_random_uuid(), 'IT-LOCKED', " + BLUE_CUP + ", " + carton
                    + ", 10, 'HCM-A01-1-A')");

            refused(db, "the factor of a unit in use", "UPDATE inventory.uom_conversions SET factor = 12 WHERE id = "
                    + carton);
            refused(db, "a unit in use moved to another item",
                    "UPDATE inventory.uom_conversions SET inventory_item_id = " + RED_CUP + " WHERE id = " + carton);
            refused(db, "a unit in use renamed",
                    "UPDATE inventory.uom_conversions SET unit_of_measure = 'BOX' WHERE id = " + carton);
            assertThat(text(db, "SELECT unit_of_measure || ':' || factor FROM inventory.uom_conversions WHERE id = "
                    + carton)).isEqualTo("LOCKED:10");

            // A unit no document counts in is still edited freely, and retiring one in use is how it is replaced.
            update(db, "UPDATE inventory.uom_conversions SET unit_of_measure = 'PACK', factor = 6 WHERE id = " + unused);
            update(db, "UPDATE inventory.uom_conversions SET active = FALSE WHERE id = " + carton);
            assertThat(text(db, "SELECT unit_of_measure || ':' || factor FROM inventory.uom_conversions WHERE id = "
                    + unused)).isEqualTo("PACK:6");
        }
    }

    @Test
    void aUnitBeingWrittenOntoADocumentKeepsItsFactorAndOnlyThat() throws Exception {
        try (Connection writer = connect(); Connection editor = connect()) {
            String carton = unit(writer, "RACED");
            writer.setAutoCommit(false);
            update(writer, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id,"
                    + " nominal_qty, location_code) VALUES (gen_random_uuid(), 'IT-RACED', " + BLUE_CUP + ", " + carton
                    + ", 10, 'HCM-A01-1-A')");
            // Anything that waits is a failure here: the lock_timeout turns a wait into an error at once.
            update(editor, "SET lock_timeout = '1s'");

            // The foreign key's KEY SHARE does not stop a factor change, and unit_in_use cannot see the uncommitted
            // stock package: without the unit's advisory lock both would commit, a carton of 10 in a unit of 12.
            SQLException change = catchThrowableOfType(SQLException.class, () ->
                    update(editor, "UPDATE inventory.uom_conversions SET factor = 12 WHERE id = " + carton));
            assertThat(change == null ? "accepted" : change.getSQLState())
                    .as("the factor change is refused while the stock package is being written, without waiting")
                    .isEqualTo("23514");
            // Everything else about the unit is still edited meanwhile, and nothing waits for the writer.
            update(editor, "UPDATE inventory.uom_conversions SET weight_kg = 12.5, length_cm = 60 WHERE id = " + carton);

            writer.commit();
            writer.setAutoCommit(true);
            refused(editor, "the factor, once the stock package committed",
                    "UPDATE inventory.uom_conversions SET factor = 12 WHERE id = " + carton);
        }
    }

    @Test
    void aRetiredUnitIsNotUsedForANewStockPackage() throws Exception {
        try (Connection db = connect()) {
            String old = unit(db, "RETIRED");
            update(db, "UPDATE inventory.uom_conversions SET active = FALSE WHERE id = " + old);
            refused(db, "a stock package made in the warehouse in a retired unit",
                    "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,"
                            + " location_code) VALUES (gen_random_uuid(), 'IT-RETIRED', " + BLUE_CUP + ", " + old
                            + ", 10, 'HCM-A01-1-A')");
        }
    }

    @Test
    void theLedgerNamesAStockPackageOfItsSkuAtOneOfItsLocations() throws Exception {
        try (Connection db = connect()) {
            String pkg = inStock(db, "LEDGER", unit(db, "LEDGER"), "HCM-A01-1-A");
            String row = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code,"
                    + " to_location_code, reference_type, reference_id, occurred_at, stock_package_id)"
                    + " VALUES (gen_random_uuid(), 'MOVE', '%s', '%s', '%s', 'MOVE_TASK',"
                    + " gen_random_uuid(), NOW(), " + pkg + ")";   // a move by hand names its carton

            refused(db, "a stock package of another SKU",
                    row.formatted("LY-GIAY-DEMO-12OZ-DO", "HCM-A01-1-A", "HCM-A01-1-B"));
            refused(db, "a stock package at a bin the movement never touched",
                    row.formatted("LY-GIAY-DEMO-12OZ-XANH", "HCM-A01-1-B", "HCM-RCV01"));
            update(db, row.formatted("LY-GIAY-DEMO-12OZ-XANH", "HCM-A01-1-A", "HCM-A01-1-B"));
        }
    }

    @Test
    void aCartonIsTakenByOneLiveAllocationAndKeepsItsLotAndFifoDate() throws Exception {
        try (Connection db = connect()) {
            String pkg = inStock(db, "ONEHOLD", unit(db, "ONEHOLD"), "HCM-A01-1-A");
            String order = "(SELECT id FROM ordering.customer_order ORDER BY id LIMIT 1)";
            String hold = "md5('it:hold:ONEHOLD')::uuid";
            // The SKU-level hold the allocations are made from: marked ALLOCATED, as a release leaves it.
            update(db, "INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons,"
                    + " units_per_carton, reserved_at, status, closed_at) VALUES (" + hold
                    + ", 'LY-GIAY-DEMO-12OZ-XANH', " + order + ", gen_random_uuid(), gen_random_uuid(), 1, 10, NOW(),"
                    + " 'ALLOCATED', NOW())");
            String allocation = "INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id,"
                    + " root_request_id, request_id, cartons, units_per_carton, reserved_at, status, allocated_from_id)"
                    + " VALUES (gen_random_uuid(), " + pkg + ", 'LY-GIAY-DEMO-12OZ-XANH', " + order
                    + ", gen_random_uuid(), gen_random_uuid(), 1, 10, NOW(), 'HELD', " + hold + ")";

            // ADR-0011: "is this carton taken" is its one live allocation; there is no reserved counter to drift.
            update(db, allocation);
            SQLException twice = catchThrowableOfType(SQLException.class, () -> update(db, allocation));
            assertThat(twice == null ? "accepted" : twice.getSQLState()).as("a second live allocation of one carton")
                    .isEqualTo("23505");
            assertThat((Throwable) twice).hasMessageContaining("uk_stock_reservation_live_carton");

            // Moved whole: there is no row to follow it, and its allocation goes with it.
            update(db, "UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = " + pkg);
            assertThat(text(db, "SELECT location_code || ':' || stock_status FROM inventory.stock_package WHERE id = "
                    + pkg)).isEqualTo("HCM-A01-1-B:AVAILABLE");

            // FIFO: the lot and the receipt date are written when the carton goes into stock, never after.
            refused(db, "the receipt date of a carton in stock",
                    "UPDATE inventory.stock_package SET received_at = NOW() WHERE id = " + pkg);
            refused(db, "the lot of a carton in stock",
                    "UPDATE inventory.stock_package SET lot_id = NULL WHERE id = " + pkg);
        }
    }

    @Test
    void anAdjustmentOrACountLineNamesACartonOfItsSkuAtItsLocation() throws Exception {
        try (Connection db = connect()) {
            String pkg = inStock(db, "DOC", unit(db, "DOC"), "HCM-A01-1-A");
            String user = "(SELECT id FROM identity.app_user ORDER BY id LIMIT 1)";
            String adjustment = "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku,"
                    + " direction, reason_code, requested_by, stock_package_id) VALUES (gen_random_uuid(), '%s', '%s',"
                    + " '%s', 'WRITE_OFF', 'DAMAGED', " + user + ", " + pkg + ")";
            refused(db, "an adjustment naming a carton of another SKU",
                    adjustment.formatted("ADJ-IT-DOC-1", "HCM-A01-1-A", "LY-GIAY-DEMO-12OZ-DO"));
            refused(db, "an adjustment naming a carton at another bin",
                    adjustment.formatted("ADJ-IT-DOC-2", "HCM-A01-1-B", "LY-GIAY-DEMO-12OZ-XANH"));
            update(db, adjustment.formatted("ADJ-IT-DOC-3", "HCM-A01-1-A", "LY-GIAY-DEMO-12OZ-XANH"));

            // One count line per carton: two cartons of one SKU, bin and lot are two lines. A line names a carton.
            String count = "md5('it:count:DOC')::uuid";
            update(db, "INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type) SELECT " + count
                    + ", 'CC-IT-DOC', id, 'MANUAL' FROM warehouse.warehouse WHERE prefix = 'HCM'");
            String other = inStock(db, "DOC2", unit(db, "DOC2"), "HCM-A01-1-A");
            String line = "INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked,"
                    + " stock_package_id) VALUES (gen_random_uuid(), " + count
                    + ", '%s', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, %s)";
            update(db, line.formatted("HCM-A01-1-A", pkg));
            update(db, line.formatted("HCM-A01-1-A", other));
            refused(db, "the same carton twice in one count", line.formatted("HCM-A01-1-A", pkg));
            refused(db, "a count line naming a carton at another bin", line.formatted("HCM-A01-1-B", pkg));
            refused(db, "a loose line: a count line names its carton (NOT NULL since V20261230000100)", line.formatted("HCM-A01-1-A", "NULL"));
        }
    }

    @Test
    void aCartonInStockIsCountedAndHasItsStockStatusAndItsLot() throws Exception {
        try (Connection db = connect()) {
            String unit = unit(db, "FULL");
            String pkg = "md5('it:pk:FULL')::uuid";
            lot(db);
            String carton = "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id,"
                    + " nominal_qty, counted_at, location_code, status, stock_status, lot_id, received_at) VALUES ("
                    + pkg + ", 'IT-FULL', " + BLUE_CUP + ", " + unit + ", 10, %s, 'HCM-A01-1-A', '%s', %s, %s,"
                    + " NOW() - interval '1 day')";

            refused(db, "a carton in stock that was never counted in (ck_stock_package_counted)",
                    carton.formatted("NULL", "IN_STOCK", "'AVAILABLE'", LOT));
            // The carton is the stock (V20261230000600): it holds no quantity that could be 7 or 11 of its 10, and it
            // says what stock it is exactly while it is in stock.
            refused(db, "a carton in stock with no stock status (ck_stock_package_stock_status)",
                    carton.formatted("NOW()", "IN_STOCK", "NULL", LOT));
            refused(db, "a carton still being received with a stock status (ck_stock_package_stock_status)",
                    carton.formatted("NOW()", "RECEIVING", "'AVAILABLE'", "NULL"));
            refused(db, "a carton in stock with no lot (ck_stock_package_lot)",
                    carton.formatted("NOW()", "IN_STOCK", "'AVAILABLE'", "NULL"));

            update(db, carton.formatted("NOW()", "IN_STOCK", "'AVAILABLE'", LOT));
            assertThat(text(db, "SELECT p.lpn || ':' || i.sku || ':' || u.unit_of_measure || ':' || p.nominal_qty || ':'"
                    + " || p.status || ':' || p.stock_status || ':' || l.lot_number FROM inventory.stock_package p"
                    + " JOIN inventory.inventory_items i ON i.id = p.inventory_item_id"
                    + " JOIN inventory.uom_conversions u ON u.id = p.uom_conversion_id"
                    + " JOIN inventory.stock_lot l ON l.id = p.lot_id WHERE p.id = " + pkg))
                    .isEqualTo("IT-FULL:LY-GIAY-DEMO-12OZ-XANH:FULL:10:IN_STOCK:AVAILABLE:UNTRACED");
        }
    }
}
