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
 * V20261213000400 on the real upgrade path (plan 2026-11-20-receiving-redundancy-removal, task 5): a SKU has ONE active
 * carton (kltn-docs 01 BR-03). Items that, before it, had several active cartons keep one - the default shipping one,
 * else the most recently created - and the others are retired (BR-10: a unit a document used is never deleted), after
 * which {@code uk_uom_conversions_one_carton} holds. Retired units, the base unit and items with one carton are left alone.
 */
class CartonUnitPerItemMigrationUpgradeTest {

    private static final String BEFORE = "20261213000300";
    private static final String MIGRATION = "20261213000400";

    @Test
    void extraActiveCartonsAreRetiredAndTheIndexHolds() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, units("UPG-A")))
                        .as("the default shipping carton stays active, the other is retired")
                        .containsExactly("EACH:true:false", "BOX:true:true", "BIG:false:false");
                assertThat(RawSql.texts(db, units("UPG-B")))
                        .as("no default shipping: the most recently created stays")
                        .containsExactly("EACH:true:false", "NEW:true:false", "OLD:false:false");
                assertThat(RawSql.texts(db, units("UPG-C")))
                        .as("one carton and a retired one: nothing changes")
                        .containsExactly("EACH:true:false", "C1:true:true", "C0:false:false");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.uom_conversions WHERE unit_of_measure = "
                        + "'BIG'")).as("retired, never deleted").isEqualTo(1);
                assertThat(RawSql.texts(db, "SELECT indexname FROM pg_indexes WHERE indexname = "
                        + "'uk_uom_conversions_one_carton'")).hasSize(1);

                RawSql.refusedBy(db, "uk_uom_conversions_one_carton", "a second active carton",
                        "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active) "
                                + "SELECT gen_random_uuid(), id, 'EXTRA', 5, TRUE FROM inventory.inventory_items "
                                + "WHERE sku = 'UPG-A'");
                assertThat(RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, "
                        + "unit_of_measure, factor, active) SELECT gen_random_uuid(), id, 'EXTRA', 5, FALSE FROM "
                        + "inventory.inventory_items WHERE sku = 'UPG-A'")).isEqualTo(1);
            }
        }
    }

    /** Code:active:default_shipping of each unit of the item: base first, then active before retired. */
    private static String units(String sku) {
        return "SELECT c.unit_of_measure || ':' || c.active || ':' || c.default_shipping FROM inventory.uom_conversions c "
                + "JOIN inventory.inventory_items i ON i.id = c.inventory_item_id WHERE i.sku = '" + sku
                + "' ORDER BY c.is_base DESC, c.active DESC, c.default_shipping DESC, c.unit_of_measure";
    }

    private static void seed(Connection db) throws SQLException {
        // Written with triggers off: what is tested is the migration on rows the old rules accepted.
        RawSql.update(db, "SET session_replication_role = replica");
        for (String sku : new String[] {"UPG-A", "UPG-B", "UPG-C"}) {
            RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, unit_of_measure, created_at) "
                    + "VALUES (gen_random_uuid(), ?, 'EACH', NOW())", sku);
            RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                    + "is_base) SELECT gen_random_uuid(), id, 'EACH', 1, TRUE FROM inventory.inventory_items "
                    + "WHERE sku = ?", sku);
        }
        String unit = "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active, "
                + "default_shipping, created_at) SELECT gen_random_uuid(), id, ?, 10, ?, ?, NOW() - ?::interval "
                + "FROM inventory.inventory_items WHERE sku = ?";
        RawSql.update(db, unit, "BIG", true, false, "1 day", "UPG-A");
        RawSql.update(db, unit, "BOX", true, true, "3 days", "UPG-A");
        RawSql.update(db, unit, "OLD", true, false, "5 days", "UPG-B");
        RawSql.update(db, unit, "NEW", true, false, "1 day", "UPG-B");
        RawSql.update(db, unit, "C0", false, false, "5 days", "UPG-C");
        RawSql.update(db, unit, "C1", true, true, "1 day", "UPG-C");
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
