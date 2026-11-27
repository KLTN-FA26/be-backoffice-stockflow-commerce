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
 * V20261226000100 on the real upgrade path: the list of units a variant sells in goes (kltn-docs 01 §3 "Đơn vị bán",
 * BR-11: the sales unit is the SKU's carton, assigned by the system). A database that listed sales units loses the
 * rows with the table; the cartons they named stay as they were, the sales unit of each SKU is now read from them.
 */
class SalesUnitsMigrationUpgradeTest {

    private static final String BEFORE = "20261225000100";
    private static final String MIGRATION = "20261226000100";

    @Test
    void theListGoesAndTheCartonsStay() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.text(db, "SELECT COALESCE(to_regclass('product.variant_sales_units')::text, 'gone')"))
                        .isEqualTo("gone");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE proname = 'check_sales_unit_of_item'"))
                        .isZero();
                assertThat(RawSql.texts(db, "SELECT i.sku || ':' || c.unit_of_measure || ':' || c.factor || ':' || c.active "
                        + "FROM inventory.uom_conversions c JOIN inventory.inventory_items i ON i.id = c.inventory_item_id "
                        + "WHERE i.sku LIKE 'UPG-S%' AND c.unit_of_measure <> 'EACH' ORDER BY i.sku"))
                        .as("the cartons the sales units named are untouched")
                        .containsExactly("UPG-S1:THUNG:1000:true", "UPG-S2:THUNG:500:true");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        // Written with triggers off: what is tested is the migration on rows the old rules accepted.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, unit_of_measure, created_at) "
                + "SELECT gen_random_uuid(), s, 'EACH', NOW() FROM unnest(ARRAY['UPG-S1', 'UPG-S2']) s");
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base) "
                + "SELECT gen_random_uuid(), id, 'EACH', 1, TRUE FROM inventory.inventory_items WHERE sku LIKE 'UPG-S%'");
        String carton = "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active, "
                + "default_shipping, length_cm, width_cm, height_cm, weight_kg) SELECT gen_random_uuid(), id, 'THUNG', ?, "
                + "TRUE, TRUE, 60, 40, 50, 8 FROM inventory.inventory_items WHERE sku = ?";
        RawSql.update(db, carton, 1000, "UPG-S1");
        RawSql.update(db, carton, 500, "UPG-S2");
        // One SKU sells in its carton, the other in its base unit: both kinds of row a database may hold.
        RawSql.update(db, "INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id) "
                + "SELECT gen_random_uuid(), gen_random_uuid(), c.id FROM inventory.uom_conversions c "
                + "JOIN inventory.inventory_items i ON i.id = c.inventory_item_id "
                + "WHERE (i.sku = 'UPG-S1' AND NOT c.is_base) OR (i.sku = 'UPG-S2' AND c.is_base)");
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
