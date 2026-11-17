package com.stockflow.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static com.stockflow.support.RawSql.refused;
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
    private static final String SKU = "SOFA-3S-GREY";
    private static final String INSERT = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code,"
            + " to_location_code, quantity, reference_type, reference_id, occurred_at)"
            + " VALUES (gen_random_uuid(), ?, ?, ?, ?, 1, ?, gen_random_uuid(), NOW())";

    @Test
    void theShapeKeepsEveryFeaturesRule() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                for (String check : new String[] {"ck_stock_movement_shape", "ck_stock_movement_put_back_carton"}) {
                    assertThat(text(db, "SELECT convalidated::text FROM pg_constraint WHERE conname = ?", check))
                            .as(check).isEqualTo("true");
                }
                assertShape(db);
            }
        }
    }

    /** Every rule of the shape, each allowed case next to the case it refuses. */
    private static void assertShape(Connection db) throws SQLException {
        // Since 4C-4 a pick line's put-back names the carton it goes back into: the shape allows it,
        // ck_stock_movement_put_back_carton refuses it without one.
        refused(db, "a pick line's put-back that names no carton", INSERT, "PUT_BACK", SKU, null, BIN, "PICK_LINE");
        update(db, INSERT, "PUT_BACK", SKU, OTHER_BIN, BIN, "MOVE_TASK");
        refused(db, "a put-back from nowhere that is neither a pick line's nor an order's", INSERT, "PUT_BACK", SKU,
                null, BIN, "MOVE_TASK");
        // G4-Q2: a cancelled order's printed goods, consumed at completion, come back for their scrap.
        update(db, INSERT, "PUT_BACK", SKU, null, BIN, "ORDER");
        refused(db, "a put-back onto nowhere", INSERT, "PUT_BACK", SKU, BIN, null, "PICK_LINE");
        refused(db, "a put-back onto the bin it came from", INSERT, "PUT_BACK", SKU, BIN, BIN, "MOVE_TASK");

        update(db, INSERT, "CONSUMPTION", SKU, BIN, null, "PRODUCTION_ORDER");
        refused(db, "a consumption that lands somewhere", INSERT, "CONSUMPTION", SKU, BIN, OTHER_BIN, "PRODUCTION_ORDER");
        refused(db, "a consumption from nowhere", INSERT, "CONSUMPTION", SKU, null, null, "PRODUCTION_ORDER");

        update(db, INSERT, "PICK", SKU, BIN, null, "PICK_LINE");
        refused(db, "a pick that lands somewhere", INSERT, "PICK", SKU, BIN, OTHER_BIN, "PICK_LINE");
    }
}
