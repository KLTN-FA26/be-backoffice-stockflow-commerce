package com.stockflow.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static com.stockflow.support.RawSql.number;
import static com.stockflow.support.RawSql.refused;
import static com.stockflow.support.RawSql.text;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ck_stock_movement_shape} is re-created twice: by develop's V20261014001000 (SCRUM-267: a put-back of a pick
 * line comes from nowhere, the goods were deducted when picked) and by Sprint 2's V20261017000470 (SCRUM-441: a
 * CONSUMPTION leaves a location for nowhere). The later one must keep the earlier one's rule, or a database that
 * already holds such a put-back cannot be upgraded - the ADD CONSTRAINT validates every existing row - and a fresh
 * one refuses the put-back the fulfillment module writes.
 */
class StockMovementShapeMigrationUpgradeTest {

    /** The newest migration on develop when Sprint 2 was last merged with it (e0d082e, #120): what is deployed. */
    private static final String DEVELOP = "20261016000100";
    private static final String BIN = "HCM-A01-1-A";
    private static final String OTHER_BIN = "HCM-A01-1-B";
    private static final String SKU = "SOFA-3S-GREY";
    private static final String INSERT = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code,"
            + " to_location_code, quantity, reference_type, reference_id, occurred_at)"
            + " VALUES (gen_random_uuid(), ?, ?, ?, ?, 1, ?, gen_random_uuid(), NOW())";

    @Test
    void upgradeKeepsPutBacksOfPickLinesAndAddsConsumption() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, DEVELOP).migrate();
            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                // What develop writes: a pick deducted from a bin, then put back onto one from nowhere.
                UUID pickLine = UUID.randomUUID();
                update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, quantity,"
                        + " reference_type, reference_id, occurred_at) VALUES (gen_random_uuid(), 'PICK', ?, ?, 1,"
                        + " 'PICK_LINE', ?, NOW())", SKU, BIN, pickLine);
                update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity,"
                        + " reference_type, reference_id, occurred_at) VALUES (gen_random_uuid(), 'PUT_BACK', ?, ?, 1,"
                        + " 'PICK_LINE', ?, NOW())", SKU, BIN, pickLine);

                var result = flyway(pg, null).migrate();
                assertThat(result.success).isTrue();
                assertThat(result.migrations).extracting(m -> m.version).contains("20261017000470");

                assertThat(number(db, "SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'PUT_BACK'"
                        + " AND from_location_code IS NULL AND reference_id = ?", pickLine)).isEqualTo(1);
                assertThat(text(db, "SELECT convalidated::text FROM pg_constraint WHERE conname = 'ck_stock_movement_shape'"))
                        .isEqualTo("true");
                assertShape(db);
            }
        }
    }

    /** Both rules, on the upgraded database: develop's put-back and Sprint 2's consumption. */
    private static void assertShape(Connection db) throws SQLException {
        update(db, INSERT, "PUT_BACK", SKU, null, BIN, "PICK_LINE");
        update(db, INSERT, "PUT_BACK", SKU, OTHER_BIN, BIN, "MOVE_TASK");
        refused(db, "a put-back from nowhere that is neither a pick line's nor an order's", INSERT, "PUT_BACK", SKU,
                null, BIN, "MOVE_TASK");
        // V20261021001300 (G4-Q2): a cancelled order's printed goods, consumed at completion, come back for their scrap.
        update(db, INSERT, "PUT_BACK", SKU, null, BIN, "ORDER");
        refused(db, "a put-back onto nowhere", INSERT, "PUT_BACK", SKU, BIN, null, "PICK_LINE");
        refused(db, "a put-back onto the bin it came from", INSERT, "PUT_BACK", SKU, BIN, BIN, "MOVE_TASK");

        update(db, INSERT, "CONSUMPTION", SKU, BIN, null, "PRODUCTION_ORDER");
        refused(db, "a consumption that lands somewhere", INSERT, "CONSUMPTION", SKU, BIN, OTHER_BIN, "PRODUCTION_ORDER");
        refused(db, "a consumption from nowhere", INSERT, "CONSUMPTION", SKU, null, null, "PRODUCTION_ORDER");

        update(db, INSERT, "PICK", SKU, BIN, null, "PICK_LINE");
        refused(db, "a pick that lands somewhere", INSERT, "PICK", SKU, BIN, OTHER_BIN, "PICK_LINE");
    }

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        var config = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }
}
