package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261112000100 on the real upgrade path (team plan 5-4; kltn-docs 06 BR-01, BR-19, storage class removed 2026-11-05): a map
 * and an item written with storage classes, a unit capacity and the carrier flags lose those columns, every value
 * archived first; a legacy level without a clear height stays as it is but cannot be written again without one; and a
 * carton that does not fit a bin can no longer be put into it, while one already standing in such a bin stays.
 */
class BinVolumeMigrationUpgradeTest {

    private static final String BEFORE = "20261111000200";
    private static final String MIGRATION = "20261112000100";

    private static final String SKU = "UPG3-CUP";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000c3000";
    private static final String SHELF = "00000000-0000-4000-8000-0000000c3001";
    private static final String LEVEL = "00000000-0000-4000-8000-0000000c3002";
    private static final String BIN_LOCATION = "00000000-0000-4000-8000-0000000c3003";
    private static final String BIN = "00000000-0000-4000-8000-0000000c3004";
    private static final String ITEM = "00000000-0000-4000-8000-0000000c3005";
    private static final String CARTON_UNIT = "00000000-0000-4000-8000-0000000c3006";
    private static final String BIG_CARTON = "00000000-0000-4000-8000-0000000c3007";
    private static final String BIN_CODE = "UPG3-A01-1-A";
    private static final String DOCK = "UPG3-RCV01";

    @Test
    void storageClassesAndUnitCapacityGoArchivedAndTheFitHoldsFromNowOn() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                // Merged with the other lines, migrations of theirs may sit between BEFORE and this one (final rescope merge).
                assertThat(result.migrations).extracting(m -> m.version).last().isEqualTo(MIGRATION);

                // The columns are gone.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE "
                        + "(table_schema, table_name, column_name) IN (('warehouse', 'storage_location', 'storage_class'), "
                        + "('warehouse', 'storage_location', 'capacity_units'), ('warehouse', 'shelf', "
                        + "'default_storage_class'), ('warehouse', 'bin', 'storage_class_override'), ('inventory', "
                        + "'inventory_items', 'storage_class'), ('inventory', 'inventory_items', "
                        + "'requires_adult_signature'), ('inventory', 'inventory_items', 'shipping_restriction_note'))"))
                        .isZero();

                // Every value is in the archive, by row.
                assertThat(RawSql.text(db, "SELECT row_data ->> 'storage_class' || ':' || (row_data ->> 'capacity_units')"
                        + " FROM platform.legacy_archive WHERE source_table = 'warehouse.storage_location' AND row_id = "
                        + "?::uuid", BIN_LOCATION)).isEqualTo("COLD:40");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'default_storage_class' FROM platform.legacy_archive "
                        + "WHERE source_table = 'warehouse.shelf' AND row_id = ?::uuid", SHELF)).isEqualTo("OVERSIZE");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'storage_class_override' FROM platform.legacy_archive "
                        + "WHERE source_table = 'warehouse.bin' AND row_id = ?::uuid", BIN)).isEqualTo("COLD");
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'storage_class') || ':' || "
                        + "(row_data ->> 'requires_adult_signature') || ':' || (row_data ->> 'shipping_restriction_note')"
                        + " FROM platform.legacy_archive WHERE source_table = 'inventory.inventory_items' AND row_id = "
                        + "?::uuid", ITEM)).isEqualTo("FRAGILE:true:Not for minors");

                // The legacy level keeps its missing clear height, but is not written again without one.
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.shelf_level WHERE id = ?::uuid "
                        + "AND usable_height IS NULL", LEVEL)).isOne();
                RawSql.refused(db, "an edit of the legacy level that leaves it without a clear height",
                        "UPDATE warehouse.shelf_level SET max_weight = 400 WHERE id = ?::uuid", LEVEL);
                RawSql.update(db, "UPDATE warehouse.shelf_level SET usable_height = 0.25 WHERE id = ?::uuid", LEVEL);

                // The carton put in before the rule stays where it is; moving it in again is refused (0.3 m tall,
                // the level is now 0.25 m clear), and so is a new one.
                assertThat(RawSql.text(db, "SELECT location_code FROM inventory.stock_package WHERE id = ?::uuid",
                        BIG_CARTON)).isEqualTo(BIN_CODE);
                RawSql.update(db, "UPDATE inventory.stock_package SET location_code = ? WHERE id = ?::uuid", DOCK,
                        BIG_CARTON);
                RawSql.refused(db, "the carton back into a bin it does not fit",
                        "UPDATE inventory.stock_package SET location_code = ? WHERE id = ?::uuid", BIN_CODE, BIG_CARTON);
                RawSql.update(db, "UPDATE warehouse.shelf_level SET usable_height = 0.3 WHERE id = ?::uuid", LEVEL);
                RawSql.update(db, "UPDATE inventory.stock_package SET location_code = ? WHERE id = ?::uuid", BIN_CODE,
                        BIG_CARTON);
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        // Foreign keys and row triggers are off while the old state is written: the documents around it are not what
        // this is about.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO warehouse.shelf (id, warehouse_id, code, name, x, y, width, length, rotation, "
                + "is_obstacle, pick_north, pick_east, pick_south, pick_west, default_storage_class, status) VALUES "
                + "(?::uuid, ?::uuid, 'A01', 'Shelf', 1, 1, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'OVERSIZE', "
                + "'ACTIVE')", SHELF, WAREHOUSE);
        RawSql.update(db, "INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, "
                + "max_weight) VALUES (?::uuid, ?::uuid, 1, 0, NULL, 500)", LEVEL, SHELF);
        RawSql.update(db, "INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, "
                + "capacity_units, max_weight, is_pickable, is_putaway_target, status) VALUES (?::uuid, ?::uuid, 'BIN', "
                + "?, 'COLD', 40, 250, TRUE, TRUE, 'ACTIVE'), (gen_random_uuid(), ?::uuid, 'AREA', ?, 'NORMAL', NULL, "
                + "NULL, FALSE, FALSE, 'ACTIVE')", BIN_LOCATION, WAREHOUSE, BIN_CODE, WAREHOUSE, DOCK);
        RawSql.update(db, "INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, "
                + "type, storage_class_override) VALUES (?::uuid, ?::uuid, ?::uuid, 'A', 0, 0, 1, 1.2, 0, 'PALLET', "
                + "'COLD')", BIN, LEVEL, BIN_LOCATION);
        RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, policy_configured, lot_tracked, "
                + "removal_strategy, storage_class, requires_adult_signature, shipping_restriction_note) VALUES "
                + "(?::uuid, ?, TRUE, FALSE, 'FEFO', 'FRAGILE', TRUE, 'Not for minors')", ITEM, SKU);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "is_base, active, length_cm, width_cm, height_cm, version, created_at) VALUES (?::uuid, ?::uuid, "
                + "'CTN', 10, FALSE, TRUE, 50, 40, 30, 0, NOW())", CARTON_UNIT, ITEM);
        // A carton standing in the bin before any fit was checked.
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, received_qty, location_code, status, created_at) VALUES (?::uuid, 'LPN-UPG3-B', "
                + "?::uuid, ?::uuid, 10, 10, ?, 'RECEIVING', NOW())", BIG_CARTON, ITEM, CARTON_UNIT, BIN_CODE);
        RawSql.update(db, "SET session_replication_role = DEFAULT");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
