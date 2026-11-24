package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261226000200 on the real upgrade path: the base unit row of every item goes (kltn-docs 01 §3, BR-09: the base unit
 * is the item's own unit_of_measure). Items written before it keep their unit and their cartons, active and retired,
 * with the cartons' ids - documents name them; a database where a document names a base unit is not migrated silently.
 */
class BaseUnitRowMigrationUpgradeTest {

    private static final String BEFORE = "20261226000100";
    private static final String MIGRATION = "20261226000200";

    @Test
    void theBaseRowsGoAndTheCartonsStay() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                var cartonsBefore = RawSql.texts(db, cartons());

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.uom_conversions WHERE is_base"))
                        .as("no base unit row is left").isZero();
                assertThat(RawSql.texts(db, cartons())).as("the cartons, active and retired, are untouched")
                        .isEqualTo(cartonsBefore)
                        .hasSize(3);
                assertThat(RawSql.texts(db, "SELECT sku || ':' || unit_of_measure FROM inventory.inventory_items "
                        + "WHERE sku LIKE 'UPG-B%' ORDER BY sku"))
                        .as("each item still names the unit its pieces are counted in")
                        .containsExactly("UPG-B1:EACH", "UPG-B2:PCS", "UPG-B3:EACH");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE proname IN ('create_base_uom', "
                        + "'follow_base_uom')")).isZero();

                // From here no row can be a base unit (QA 45 has the rest: a new item is made with no unit row).
                assertThatThrownBy(() -> RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, "
                        + "inventory_item_id, unit_of_measure, factor, is_base) SELECT gen_random_uuid(), id, 'BASE', 1, "
                        + "TRUE FROM inventory.inventory_items WHERE sku = 'UPG-B3'"))
                        .hasMessageContaining("ck_uom_conversions_no_base");
            }
        }
    }

    @Test
    void aDocumentNamingABaseUnitStopsTheMigration() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                        + "nominal_qty, location_code, status, closed_at, created_at) SELECT gen_random_uuid(), "
                        + "'LPN-20261101-000001', c.inventory_item_id, c.id, 1, 'UPG-NOWHERE', 'VOID', NOW(), NOW() "
                        + "FROM inventory.uom_conversions c "
                        + "JOIN inventory.inventory_items i ON i.id = c.inventory_item_id "
                        + "WHERE i.sku = 'UPG-B1' AND c.is_base");
                RawSql.update(db, "SET session_replication_role = origin");

                assertThatThrownBy(() -> flyway(postgres, MIGRATION).migrate())
                        .hasMessageContaining("base units are still named by: inventory.stock_package (1)");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.uom_conversions WHERE is_base"))
                        .as("nothing was deleted").isEqualTo(3);
            }
        }
    }

    private static String cartons() {
        return "SELECT c.id || ':' || i.sku || ':' || c.unit_of_measure || ':' || c.factor || ':' || c.active "
                + "FROM inventory.uom_conversions c JOIN inventory.inventory_items i ON i.id = c.inventory_item_id "
                + "WHERE i.sku LIKE 'UPG-B%' AND NOT c.is_base ORDER BY i.sku, c.unit_of_measure";
    }

    private static void seed(Connection db) throws SQLException {
        // Written with triggers off: what is tested is the migration on rows the old rules accepted.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, unit_of_measure, created_at) "
                + "SELECT gen_random_uuid(), s.sku, s.unit, NOW() FROM (VALUES ('UPG-B1', 'EACH'), ('UPG-B2', 'PCS'), "
                + "('UPG-B3', 'EACH')) AS s (sku, unit)");
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base) "
                + "SELECT gen_random_uuid(), id, unit_of_measure, 1, TRUE FROM inventory.inventory_items "
                + "WHERE sku LIKE 'UPG-B%'");
        String carton = "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active, "
                + "default_shipping, length_cm, width_cm, height_cm, weight_kg) SELECT gen_random_uuid(), id, ?, ?, ?, ?, "
                + "60, 40, 50, 8 FROM inventory.inventory_items WHERE sku = ?";
        RawSql.update(db, carton, "THUNG", 1000, true, true, "UPG-B1");
        RawSql.update(db, carton, "OLD", 800, false, false, "UPG-B1");
        RawSql.update(db, carton, "THUNG", 500, true, true, "UPG-B2");
        // UPG-B3 has a base unit only: an item whose carton is not declared yet.
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
