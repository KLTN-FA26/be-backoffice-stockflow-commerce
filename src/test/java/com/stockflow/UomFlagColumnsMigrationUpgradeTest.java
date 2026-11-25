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
 * V20261229000100 (renumbered from V20261226000300) on the real upgrade path: is_base and default_shipping leave inventory.uom_conversions (kltn-docs 01
 * BR-03: one active carton, nothing to choose to ship in). Units written before it - an active carton with the old
 * mark, one without it, a retired one - keep their ids, codes, factors and active flag, and one active carton per item
 * still holds: the index whose predicate named is_base is rebuilt, not lost.
 */
class UomFlagColumnsMigrationUpgradeTest {

    private static final String BEFORE = "20261228000400";
    private static final String MIGRATION = "20261229000100";

    @Test
    void theFlagsGoAndTheUnitsStay() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                var unitsBefore = RawSql.texts(db, units());

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, units())).as("every unit, active and retired, marked or not, unchanged")
                        .isEqualTo(unitsBefore)
                        .hasSize(3);
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'uom_conversions' AND column_name IN ('is_base', "
                        + "'default_shipping')")).isZero();

                // 01 BR-03 still holds: a second active carton beside the unmarked one is refused.
                assertThatThrownBy(() -> RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, "
                        + "inventory_item_id, unit_of_measure, factor, active) SELECT gen_random_uuid(), id, 'BOX', 20, "
                        + "TRUE FROM inventory.inventory_items WHERE sku = 'UPG-F2'"))
                        .hasMessageContaining("uk_uom_conversions_one_carton");
            }
        }
    }

    private static String units() {
        return "SELECT c.id || ':' || i.sku || ':' || c.unit_of_measure || ':' || c.factor || ':' || c.active "
                + "FROM inventory.uom_conversions c JOIN inventory.inventory_items i ON i.id = c.inventory_item_id "
                + "WHERE i.sku LIKE 'UPG-F%' ORDER BY i.sku, c.unit_of_measure";
    }

    private static void seed(Connection db) throws SQLException {
        // Written with triggers off: what is tested is the migration on rows the old rules accepted.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, unit_of_measure, created_at) "
                + "SELECT gen_random_uuid(), s.sku, 'EACH', NOW() FROM (VALUES ('UPG-F1'), ('UPG-F2')) AS s (sku)");
        String unit = "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active, "
                + "default_shipping, length_cm, width_cm, height_cm, weight_kg) SELECT gen_random_uuid(), id, ?, ?, ?, ?, "
                + "60, 40, 50, 8 FROM inventory.inventory_items WHERE sku = ?";
        RawSql.update(db, unit, "THUNG", 1000, true, true, "UPG-F1");
        RawSql.update(db, unit, "OLD", 800, false, false, "UPG-F1");
        // An active carton nobody marked for shipping: it is the SKU's carton all the same.
        RawSql.update(db, unit, "THUNG", 500, true, false, "UPG-F2");
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
