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
    void aFactorChangeWaitsForTheDocumentBeingWrittenInItsUnit() throws Exception {
        try (Connection writer = connect(); Connection editor = connect()) {
            String carton = unit(writer, "RACED");
            writer.setAutoCommit(false);
            update(writer, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id,"
                    + " nominal_qty, location_code) VALUES (gen_random_uuid(), 'IT-RACED', " + SOFA + ", " + carton
                    + ", 10, 'HCM-A01-1-A')");

            // Without FOR SHARE on the unit row the foreign key's KEY SHARE lets this through, it cannot see the
            // uncommitted stock package, and both commit: a carton of 10 in a unit that now says 12.
            update(editor, "SET lock_timeout = '1s'");
            SQLException blocked = catchThrowableOfType(SQLException.class, () ->
                    update(editor, "UPDATE inventory.uom_conversions SET factor = 12 WHERE id = " + carton));
            assertThat(blocked == null ? "accepted" : blocked.getSQLState())
                    .as("the factor change waits for the stock package's transaction (55P03: lock timeout)")
                    .isEqualTo("55P03");

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
}
