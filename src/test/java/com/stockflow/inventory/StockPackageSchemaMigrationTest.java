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
 * The rules V20261020000100-0600 put on stock packages and the units documents count in, on a fresh database with the
 * demo seed. tools/db/qa/15_stock_packages.sql holds the full set; these are the ones the build must keep, since no
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

    /** An opened stock package in stock at {@code location}, holding {@code held} sofas in one row. */
    private static String inStock(Connection db, String name, String unit, String location, int held)
            throws SQLException {
        String id = "md5('it:pk:" + name + "')::uuid";
        transaction(db,
                "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,"
                        + " received_qty, location_code, status, unopened) VALUES (" + id + ", 'IT-" + name + "', "
                        + SOFA + ", " + unit + ", 10, " + held + ", '" + location + "', 'IN_STOCK', FALSE)",
                "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at,"
                        + " received_at, stock_package_id) VALUES (md5('it:pk:" + name + ":row')::uuid, 'SOFA-3S-GREY', '"
                        + location + "', " + held + ", 0, 'AVAILABLE', NOW(), NOW() - interval '1 day', " + id + ")");
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
            String pkg = inStock(db, "LEDGER", unit(db, "LEDGER"), "HCM-A01-1-A", 4);
            String row = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code,"
                    + " to_location_code, quantity, reference_type, reference_id, occurred_at, from_stock_package_id)"
                    + " VALUES (gen_random_uuid(), 'MOVE', '%s', '%s', '%s', 1, 'MOVE_TASK', gen_random_uuid(), NOW(), "
                    + pkg + ")";

            refused(db, "a stock package of another SKU", row.formatted("TABLE-OAK-160", "HCM-A01-1-A", "HCM-A01-1-B"));
            refused(db, "a stock package at a bin the movement never touched",
                    row.formatted("SOFA-3S-GREY", "HCM-A01-1-B", "HCM-RCV01"));
            update(db, row.formatted("SOFA-3S-GREY", "HCM-A01-1-A", "HCM-A01-1-B"));
        }
    }

    @Test
    void anEmptiedRowStaysInItsStockPackageWithoutPinningIt() throws Exception {
        try (Connection db = connect()) {
            String pkg = inStock(db, "EMPTIED", unit(db, "EMPTIED"), "HCM-A01-1-A", 4);
            update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at,"
                    + " received_at, stock_package_id) VALUES (md5('it:pk:EMPTIED:empty')::uuid, 'SOFA-3S-GREY',"
                    + " 'HCM-A01-1-A', 0, 0, 'AVAILABLE', NOW(), NOW() - interval '2 day', " + pkg + ")");

            // A serial picked out of the carton leaves its row at 0; the carton moves on without it.
            transaction(db,
                    "UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = " + pkg,
                    "UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-B' WHERE id = md5('it:pk:EMPTIED:row')::uuid");
            refused(db, "the emptied row filled again away from its stock package",
                    "UPDATE inventory.stock_item SET on_hand = 1 WHERE id = md5('it:pk:EMPTIED:empty')::uuid");
            // A carton's layer is one row wherever it is: a put-back fills the emptied row, it does not open a
            // second row of the same layer on the carton's new bin.
            refused(db, "a second row of the emptied layer in the same stock package, on another bin",
                    "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at,"
                            + " received_at, stock_package_id) SELECT gen_random_uuid(), sku, 'HCM-A01-1-B', 1, 0,"
                            + " 'AVAILABLE', NOW(), received_at, stock_package_id FROM inventory.stock_item"
                            + " WHERE id = md5('it:pk:EMPTIED:empty')::uuid");
            // And the carton goes back to the bin its emptied row never left.
            transaction(db,
                    "UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-A' WHERE id = " + pkg,
                    "UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-A' WHERE id = md5('it:pk:EMPTIED:row')::uuid");
            assertThat(text(db, "SELECT count(*) || ' rows at ' || min(location_code) FROM inventory.stock_item"
                    + " WHERE stock_package_id = " + pkg)).isEqualTo("2 rows at HCM-A01-1-A");
        }
    }
}
