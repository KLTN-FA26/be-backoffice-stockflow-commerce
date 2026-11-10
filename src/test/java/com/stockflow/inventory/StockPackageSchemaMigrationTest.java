package com.stockflow.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static com.stockflow.support.RawSql.refused;
import static com.stockflow.support.RawSql.text;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The rules V20261020000100-0600 (and V20261113000300, whole-carton counts) put on stock packages and the units
 * documents count in, on a fresh database with the demo seed. tools/db/qa/15_stock_packages.sql holds the full set; these are the ones the build must keep, since no
 * workflow runs that script: a later migration replacing one of these functions would otherwise pass CI.
 *
 * <p>Each test makes its own units and stock packages, so they share one migrated database.</p>
 */
class StockPackageSchemaMigrationTest {

    private static final String SOFA = "md5('demo:item:SOFA-3S-GREY')::uuid";
    private static final String TABLE = "md5('demo:item:TABLE-OAK-160')::uuid";

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

    /** A packing unit of SOFA-3S-GREY, factor 10. */
    private static String unit(Connection db, String name) throws SQLException {
        String id = "md5('it:unit:" + name + "')::uuid";
        update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor) VALUES ("
                + id + ", " + SOFA + ", '" + name + "', 10)");
        return id;
    }

    /** A whole stock package of 10 in stock at {@code location}, holding its 10 sofas in one row (lot UNTRACED). */
    private static String inStock(Connection db, String name, String unit, String location) throws SQLException {
        String id = "md5('it:pk:" + name + "')::uuid";
        transaction(db,
                "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,"
                        + " received_qty, location_code, status) VALUES (" + id + ", 'IT-" + name + "', "
                        + SOFA + ", " + unit + ", 10, 10, '" + location + "', 'IN_STOCK')",
                "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status,"
                        + " created_at, received_at, stock_package_id) VALUES (md5('it:pk:" + name
                        + ":row')::uuid, 'SOFA-3S-GREY', '" + location + "', 'UNTRACED', 10, 0, 'AVAILABLE', NOW(),"
                        + " NOW() - interval '1 day', " + id + ")");
        return id;
    }

    /** The statements as one transaction, so the deferred checks run once, at its commit. */
    private static void transaction(Connection db, String... statements) throws SQLException {
        db.setAutoCommit(false);
        try (Statement st = db.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    @Test
    void aUnitInUseKeepsItsFactorItemAndName() throws Exception {
        try (Connection db = connect()) {
            String carton = unit(db, "LOCKED");
            String unused = unit(db, "UNUSED");
            update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,"
                    + " location_code) VALUES (gen_random_uuid(), 'IT-LOCKED', " + SOFA + ", " + carton
                    + ", 10, 'HCM-A01-1-A')");

            refused(db, "the factor of a unit in use", "UPDATE inventory.uom_conversions SET factor = 12 WHERE id = "
                    + carton);
            refused(db, "a unit in use moved to another item",
                    "UPDATE inventory.uom_conversions SET inventory_item_id = " + TABLE + " WHERE id = " + carton);
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
                    + " nominal_qty, location_code) VALUES (gen_random_uuid(), 'IT-RACED', " + SOFA + ", " + carton
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
                            + " location_code) VALUES (gen_random_uuid(), 'IT-RETIRED', " + SOFA + ", " + old
                            + ", 10, 'HCM-A01-1-A')");
        }
    }

    @Test
    void theLedgerNamesAStockPackageOfItsSkuAtOneOfItsLocations() throws Exception {
        try (Connection db = connect()) {
            String pkg = inStock(db, "LEDGER", unit(db, "LEDGER"), "HCM-A01-1-A");
            String row = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code,"
                    + " to_location_code, quantity, reference_type, reference_id, occurred_at, from_stock_package_id,"
                    + " to_stock_package_id) VALUES (gen_random_uuid(), 'MOVE', '%s', '%s', '%s', 1, 'MOVE_TASK',"
                    + " gen_random_uuid(), NOW(), " + pkg + ", " + pkg + ")";   // a move by hand names its carton both sides

            refused(db, "a stock package of another SKU", row.formatted("TABLE-OAK-160", "HCM-A01-1-A", "HCM-A01-1-B"));
            refused(db, "a stock package at a bin the movement never touched",
                    row.formatted("SOFA-3S-GREY", "HCM-A01-1-B", "HCM-RCV01"));
            update(db, row.formatted("SOFA-3S-GREY", "HCM-A01-1-A", "HCM-A01-1-B"));
        }
    }

    @Test
    void aCartonIsOneStockRowAndItsRowFollowsIt() throws Exception {
        try (Connection db = connect()) {
            String pkg = inStock(db, "ONEROW", unit(db, "ONEROW"), "HCM-A01-1-A");
            String second = "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved,"
                    + " status, created_at, received_at, stock_package_id) VALUES (gen_random_uuid(), 'SOFA-3S-GREY',"
                    + " 'HCM-A01-1-A', 'UNTRACED', 0, 0, 'AVAILABLE', NOW(), NOW() - interval '2 day', " + pkg + ")";

            // V20261113000300 (kltn-docs 20 BR-18): a carton is one row, put back or found again it fills the same row.
            SQLException twice = catchThrowableOfType(SQLException.class, () -> update(db, second));
            assertThat(twice == null ? "accepted" : twice.getSQLState()).as("a second row of one carton")
                    .isEqualTo("23505");
            assertThat((Throwable) twice).hasMessageContaining("uk_stock_item_package");

            // Moved whole, its row goes with it - in one transaction, for the commit-time check.
            transaction(db,
                    "UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = " + pkg,
                    "UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-B' WHERE stock_package_id = " + pkg);
            assertThat(text(db, "SELECT count(*) || ' row at ' || min(location_code) FROM inventory.stock_item"
                    + " WHERE stock_package_id = " + pkg)).isEqualTo("1 row at HCM-A01-1-B");
            SQLException apart = catchThrowableOfType(SQLException.class, () -> transaction(db,
                    "UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-A' WHERE id = " + pkg));
            assertThat((Throwable) apart).as("the carton moved without its row").hasMessageContaining("is at");
        }
    }

    @Test
    void anAdjustmentOrACountLineNamesACartonOfItsSkuAtItsLocation() throws Exception {
        try (Connection db = connect()) {
            String pkg = inStock(db, "DOC", unit(db, "DOC"), "HCM-A01-1-A");
            String user = "(SELECT id FROM identity.app_user ORDER BY id LIMIT 1)";
            String adjustment = "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku,"
                    + " quantity_delta, reason_code, requested_by, stock_package_id) VALUES (gen_random_uuid(), '%s', '%s',"
                    + " '%s', -10, 'DAMAGED', " + user + ", " + pkg + ")";
            refused(db, "an adjustment naming a carton of another SKU",
                    adjustment.formatted("ADJ-IT-DOC-1", "HCM-A01-1-A", "TABLE-OAK-160"));
            refused(db, "an adjustment naming a carton at another bin",
                    adjustment.formatted("ADJ-IT-DOC-2", "HCM-A01-1-B", "SOFA-3S-GREY"));
            update(db, adjustment.formatted("ADJ-IT-DOC-3", "HCM-A01-1-A", "SOFA-3S-GREY"));

            // One count line per carton: two cartons of one SKU, bin and lot are two lines. A line names a carton.
            String count = "md5('it:count:DOC')::uuid";
            update(db, "INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type) SELECT " + count
                    + ", 'CC-IT-DOC', id, 'MANUAL' FROM warehouse.warehouse WHERE prefix = 'HCM'");
            String other = inStock(db, "DOC2", unit(db, "DOC2"), "HCM-A01-1-A");
            String line = "INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty,"
                    + " stock_package_id) VALUES (gen_random_uuid(), " + count + ", '%s', 'SOFA-3S-GREY', 10, %s)";
            update(db, line.formatted("HCM-A01-1-A", pkg));
            update(db, line.formatted("HCM-A01-1-A", other));
            refused(db, "the same carton twice in one count", line.formatted("HCM-A01-1-A", pkg));
            refused(db, "a count line naming a carton at another bin", line.formatted("HCM-A01-1-B", pkg));
            refused(db, "a loose line: a count states cartons (V20261113000300)", line.formatted("HCM-A01-1-A", "NULL"));
        }
    }

    @Test
    void aCartonInStockIsCountedAtItsStandardAndHoldsExactlyThatAndReadsAsOneRow() throws Exception {
        try (Connection db = connect()) {
            String unit = unit(db, "FULL");
            String pkg = "md5('it:pk:FULL')::uuid";
            String carton = "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id,"
                    + " nominal_qty, received_qty, location_code, status) VALUES (" + pkg + ", 'IT-FULL', "
                    + SOFA + ", " + unit + ", 10, %d, 'HCM-A01-1-A', 'IN_STOCK')";
            String row = "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved,"
                    + " status, created_at, received_at, stock_package_id) VALUES (md5('it:pk:FULL:row')::uuid,"
                    + " 'SOFA-3S-GREY', 'HCM-A01-1-A', 'UNTRACED', %d, 0, 'AVAILABLE', NOW(),"
                    + " NOW() - interval '1 day', " + pkg + ")";

            refused(db, "a carton in stock counted in at 6 of a standard 10 (ck_stock_package_standard_count)",
                    carton.formatted(6));
            SQLException partial = catchThrowableOfType(SQLException.class,
                    () -> transaction(db, carton.formatted(10), row.formatted(7)));
            assertThat((Throwable) partial).as("a carton in stock holding 7 of the 10 it was counted at")
                    .hasMessageContaining("a carton in stock is whole");
            SQLException overfilled = catchThrowableOfType(SQLException.class,
                    () -> transaction(db, carton.formatted(10), row.formatted(11)));
            assertThat((Throwable) overfilled).as("a carton in stock holding 11").hasMessageContaining("holds 11");

            transaction(db, carton.formatted(10), row.formatted(10));
            assertThat(text(db, "SELECT lpn || ':' || sku || ':' || unit_of_measure || ':' || received_qty || ':'"
                    + " || on_hand || ':' || reserved || ':' || available || ':' || status || ':' || lot_number"
                    + " FROM inventory.stock_package_balance WHERE stock_package_id = " + pkg))
                    .isEqualTo("IT-FULL:SOFA-3S-GREY:FULL:10:10:0:10:IN_STOCK:UNTRACED");
        }
    }
}
