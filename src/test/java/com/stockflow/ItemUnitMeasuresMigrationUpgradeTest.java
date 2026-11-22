package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261215000100 on the real upgrade path: an inventory item loses its unit weight and size and its shipped-box
 * columns (kltn-docs 01 §3, BR-03, C21, chốt lại 2026-11-22). Items written before it, measured or not, keep their row,
 * their base unit and their carton with the carton's own measures; the receiving trigger no longer reads the item.
 */
class ItemUnitMeasuresMigrationUpgradeTest {

    private static final String BEFORE = "20261214000400";
    private static final String MIGRATION = "20261215000100";

    @Test
    void itemMeasuresGoAndTheCartonKeepsItsOwn() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT column_name FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'inventory_items' AND (column_name LIKE '%\\_kg' OR column_name "
                        + "LIKE '%\\_cm' OR column_name IN ('package_count', 'pack_size'))"))
                        .as("no measure is left on the item").isEmpty();
                assertThat(RawSql.texts(db, "SELECT sku || ':' || unit_of_measure || ':' || barcode || ':' || rotatable "
                        + "FROM inventory.inventory_items WHERE sku LIKE 'UPG-M%' ORDER BY sku"))
                        .as("the rows and what still describes them stay")
                        .containsExactly("UPG-M1:EACH:893000000001:false", "UPG-M2:EACH:893000000002:true");
                assertThat(RawSql.texts(db, "SELECT i.sku || ':' || c.unit_of_measure || ':' || c.factor || ':' "
                        + "|| c.length_cm || 'x' || c.width_cm || 'x' || c.height_cm || ':' || c.weight_kg "
                        + "FROM inventory.uom_conversions c JOIN inventory.inventory_items i ON i.id = c.inventory_item_id "
                        + "WHERE NOT c.is_base ORDER BY i.sku"))
                        .as("the carton keeps its own size and weight")
                        .containsExactly("UPG-M1:THUNG:1000:60.00x50.00x40.00:6.500",
                                "UPG-M2:THUNG:500:45.00x40.00x30.00:3.200");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.uom_conversions WHERE is_base"))
                        .as("each item keeps its base unit").isEqualTo(2);
                assertThat(RawSql.text(db, "SELECT prosrc FROM pg_proc WHERE proname = 'check_receivable_item'"))
                        .as("the receiving trigger reads the carton's measures, no item measure, and keeps the lot rule")
                        .contains("carton.weight_kg").doesNotContain("item.weight_kg").contains("lot_number");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        // Written with triggers off: what is tested is the migration on rows the old rules accepted.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, unit_of_measure, barcode, rotatable, "
                + "weight_kg, length_cm, width_cm, height_cm, package_weight_kg, package_length_cm, package_width_cm, "
                + "package_height_cm, package_count, pack_size, created_at) VALUES (gen_random_uuid(), 'UPG-M1', 'EACH', "
                + "'893000000001', FALSE, 0.006, 9, 9, 11, 0.5, 20, 20, 30, 2, 50, NOW())");
        RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, unit_of_measure, barcode, created_at) "
                + "VALUES (gen_random_uuid(), 'UPG-M2', 'EACH', '893000000002', NOW())");
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base) "
                + "SELECT gen_random_uuid(), id, 'EACH', 1, TRUE FROM inventory.inventory_items WHERE sku LIKE 'UPG-M%'");
        String carton = "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active, "
                + "default_shipping, length_cm, width_cm, height_cm, weight_kg) SELECT gen_random_uuid(), id, 'THUNG', ?, "
                + "TRUE, TRUE, ?, ?, ?, ? FROM inventory.inventory_items WHERE sku = ?";
        RawSql.update(db, carton, 1000, 60, 50, 40, 6.5, "UPG-M1");
        RawSql.update(db, carton, 500, 45, 40, 30, 3.2, "UPG-M2");
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
