package com.stockflow.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static com.stockflow.support.RawSql.refused;
import static com.stockflow.support.RawSql.refusedBy;
import static com.stockflow.support.RawSql.text;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ck_stock_movement_shape} carries rules added by different features at different times: a put-back of a pick
 * line comes from nowhere, the goods having been deducted when picked (SCRUM-267); a CONSUMPTION leaves a location for
 * nowhere (SCRUM-441); a cancelled order's printed goods come back from nowhere (G4-Q2). Each re-creation of the CHECK
 * had to keep the rules before it, so they are pinned together here, on a freshly migrated database.
 */
class StockMovementShapeSchemaTest {

    private static final String BIN = "HCM-A01-1-A";
    private static final String OTHER_BIN = "HCM-A01-1-B";
    private static final String SKU = "LY-GIAY-DEMO-12OZ-XANH";
    private static final String INSERT = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code,"
            + " to_location_code, quantity, reference_type, reference_id, occurred_at, stock_package_id)"
            + " VALUES (gen_random_uuid(), ?, ?, ?, ?, 1, ?, gen_random_uuid(), NOW(), ?)";
    /**
     * The carton every line here names (V20261230000200: a line names its carton), standing at {@link #BIN}, which
     * each allowed line has on one side: the refusals below are then the shape's, not the missing carton's.
     */
    private static final java.util.UUID CARTON = java.util.UUID.fromString("0198f000-0000-7000-8000-00000000c001");

    @Test
    void theShapeKeepsEveryFeaturesRule() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                assertThat(text(db, "SELECT convalidated::text FROM pg_constraint WHERE conname = ?",
                        "ck_stock_movement_shape")).isEqualTo("true");
                carton(db);
                assertShape(db);
            }
        }
    }

    /** One whole carton of {@link #SKU} in stock at {@link #BIN}, in the SKU's carton unit. */
    private static void carton(Connection db) throws SQLException {
        db.setAutoCommit(false);   // the carton and its row are checked together, at commit
        update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,"
                + " counted_at, location_code, status) SELECT ?, 'IT-SHAPE-1', u.inventory_item_id, u.id, u.factor,"
                + " NOW(), ?, 'IN_STOCK' FROM inventory.uom_conversions u JOIN inventory.inventory_items i"
                + " ON i.id = u.inventory_item_id WHERE i.sku = ? AND u.active", CARTON, BIN, SKU);
        update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status,"
                + " created_at, received_at, stock_package_id) SELECT gen_random_uuid(), ?, ?, 'UNTRACED', p.nominal_qty,"
                + " 0, 'AVAILABLE', NOW(), NOW(), p.id FROM inventory.stock_package p WHERE p.id = ?", SKU, BIN, CARTON);
        db.commit();
        db.setAutoCommit(true);
    }

    /** Every rule of the shape, each allowed case next to the case it refuses. */
    private static void assertShape(Connection db) throws SQLException {
        // Since 4C-4 a pick line's put-back names the carton it goes back into; since V20261230000200 every line names
        // its carton (ck_stock_movement_names_carton), so the shape allows the put-back and the line without one is
        // refused.
        update(db, INSERT, "PUT_BACK", SKU, null, BIN, "PICK_LINE", CARTON);
        refusedBy(db, "ck_stock_movement_names_carton", "a pick line's put-back that names no carton", INSERT,
                "PUT_BACK", SKU, null, BIN, "PICK_LINE", null);
        update(db, INSERT, "PUT_BACK", SKU, OTHER_BIN, BIN, "MOVE_TASK", CARTON);
        refused(db, "a put-back from nowhere that is neither a pick line's nor an order's", INSERT, "PUT_BACK", SKU,
                null, BIN, "MOVE_TASK", CARTON);
        // G4-Q2: a cancelled order's printed goods, consumed at completion, come back for their scrap.
        update(db, INSERT, "PUT_BACK", SKU, null, BIN, "ORDER", CARTON);
        refused(db, "a put-back onto nowhere", INSERT, "PUT_BACK", SKU, BIN, null, "PICK_LINE", CARTON);
        refused(db, "a put-back onto the bin it came from", INSERT, "PUT_BACK", SKU, BIN, BIN, "MOVE_TASK", CARTON);

        update(db, INSERT, "CONSUMPTION", SKU, BIN, null, "PRODUCTION_ORDER", CARTON);
        refused(db, "a consumption that lands somewhere", INSERT, "CONSUMPTION", SKU, BIN, OTHER_BIN,
                "PRODUCTION_ORDER", CARTON);
        refused(db, "a consumption from nowhere", INSERT, "CONSUMPTION", SKU, null, null, "PRODUCTION_ORDER", CARTON);

        update(db, INSERT, "PICK", SKU, BIN, null, "PICK_LINE", CARTON);
        refused(db, "a pick that lands somewhere", INSERT, "PICK", SKU, BIN, OTHER_BIN, "PICK_LINE", CARTON);
    }
}
