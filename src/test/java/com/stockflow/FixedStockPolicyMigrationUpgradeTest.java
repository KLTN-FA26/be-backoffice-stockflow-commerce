package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261115000100 on the real upgrade path (team plan 5-5; kltn-docs 01 BR-17, 03 BR-03): a database migrated to the
 * migration before it, holding what the configurable stock policy left behind - an item configured lot-tracked and
 * serial-tracked under FIFO with a live unit that has a serial number, an item no one configured, and an emptied row
 * with no lot (what 4C-1 left of the demo's loose stock) - then upgraded.
 *
 * <p>Proves nothing of the stock is lost (every row is there, the serial unit with its lot and quantity), the policy
 * columns, their CHECK and the serial unique index are gone, the two layer keys are made again without the serial, and
 * the one policy now holds for every row of every SKU, configured or not: live stock carries a lot, a row's identity
 * never changes in place, a layer is raised only by the application naming the row it merges into, no receipt time is
 * in the future - while an emptied row without a lot and a row that only shrinks stay allowed.</p>
 */
class FixedStockPolicyMigrationUpgradeTest {

    private static final String BEFORE = "20261115000050";
    private static final String MIGRATION = "20261115000100";
    /** A SKU someone configured lot-tracked, serial-tracked and FIFO, and one nobody ever configured. */
    private static final String TABLE = "FSP-TABLE";
    private static final String SOFA = "FSP-SOFA";
    private static final String BIN_A = "FSP-RCV01";
    private static final String BIN_B = "FSP-RCV02";

    @Test
    void upgradeFixesThePolicyForEverySkuAndKeepsTheStock() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID serialUnit = UUID.randomUUID();
                UUID emptied = UUID.randomUUID();
                UUID receivedAt1 = UUID.randomUUID();

                // This database has the migrations' data only: two SKUs and the two areas the stock sits in are made here.
                for (String sku : new String[] {TABLE, SOFA}) {
                    UUID product = UUID.randomUUID();
                    RawSql.update(db, "INSERT INTO product.products (id, code, name, slug) VALUES (?, ?, 'Policy fixture', "
                            + "?)", product, sku, sku.toLowerCase());
                    RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, attribute_signature) "
                            + "VALUES (gen_random_uuid(), ?, ?, 'Policy fixture', '')", product, sku);
                }
                UUID warehouse = UUID.randomUUID();
                RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, "
                        + "map_width, map_height, version, created_at) VALUES (?, 'Policy fixture', 'ACTIVE', 'FSP', 'Test "
                        + "address', 'M', 50, 50, 0, NOW())", warehouse);
                int x = 1;
                for (String area : new String[] {"RCV01", "RCV02"}) {
                    UUID location = UUID.randomUUID();
                    RawSql.update(db, "INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, "
                            + "storage_class, is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, 'NORMAL', "
                            + "FALSE, FALSE, 'ACTIVE')", location, warehouse, "FSP-" + area);
                    RawSql.update(db, "INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, "
                            + "width, length, rotation, is_obstacle, status) VALUES (gen_random_uuid(), ?, ?, ?, "
                            + "'RECEIVING', 'x', ?, 1, 5, 5, 0, FALSE, 'ACTIVE')", warehouse, location, area, x);
                    x += 20;
                }

                // The old state is written with the row triggers off: what the old policy accepted is not what this
                // test is about.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "UPDATE inventory.inventory_items SET lot_tracked = TRUE, serial_tracked = TRUE, "
                        + "removal_strategy = 'FIFO', policy_configured = TRUE WHERE sku = ?", TABLE);
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, serial_number, "
                        + "on_hand, reserved, status, received_at, version, created_at) VALUES (?, ?, 'FSP-RCV02', "
                        + "'LOT-SER', 'SN-0001', 1, 0, 'AVAILABLE', NOW() - interval '2 days', 0, NOW())",
                        serialUnit, TABLE);
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, "
                        + "version, created_at) VALUES (?, ?, 'FSP-RCV02', 0, 0, 'AVAILABLE', 0, NOW())", emptied, SOFA);
                RawSql.update(db, "SET session_replication_role = origin");
                long rows = RawSql.number(db, "SELECT count(*) FROM inventory.stock_item");
                long onHand = RawSql.number(db, "SELECT sum(on_hand) FROM inventory.stock_item");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'inventory_items' AND column_name IN ('lot_tracked', "
                        + "'serial_tracked', 'removal_strategy', 'policy_configured')")).as("the old policy columns")
                        .isEqualTo(4);

                flyway(postgres, MIGRATION).migrate();

                // Nothing of the stock is lost.
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item")).isEqualTo(rows);
                assertThat(RawSql.number(db, "SELECT sum(on_hand) FROM inventory.stock_item")).isEqualTo(onHand);
                assertThat(RawSql.text(db, "SELECT lot_number || '|' || on_hand FROM inventory.stock_item WHERE id = ?",
                        serialUnit)).isEqualTo("LOT-SER|1");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item WHERE id = ? AND lot_number IS "
                        + "NULL AND on_hand = 0", emptied)).as("an emptied row with no lot stays").isOne();

                // The policy columns, their CHECK, the serial number and its index are gone.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE (table_schema, "
                        + "table_name, column_name) IN (('inventory', 'inventory_items', 'lot_tracked'), "
                        + "('inventory', 'inventory_items', 'serial_tracked'), ('inventory', 'inventory_items', "
                        + "'removal_strategy'), ('inventory', 'inventory_items', 'policy_configured'), ('inventory', "
                        + "'stock_item', 'serial_number'))")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname IN "
                        + "('ck_inventory_control_removal', 'ck_stock_serial_quantity')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_indexes WHERE indexname = "
                        + "'uk_stock_item_live_serial'")).isZero();
                // Kept for their own packages: the packing station's and the damage report's serials.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE (table_schema, "
                        + "table_name, column_name) IN (('fulfillment', 'package_scan', 'serial_number'), ('inventory', "
                        + "'stock_damage_report', 'serial_number'))")).isEqualTo(2);
                // The two layer keys are made again, without the serial.
                for (String key : new String[] {"uk_stock_item_sku_location_lot", "uk_stock_item_package_layer"}) {
                    assertThat(RawSql.text(db, "SELECT indexdef FROM pg_indexes WHERE schemaname = 'inventory' AND "
                            + "indexname = ?", key)).as(key).contains("lot_number").contains("received_at")
                            .doesNotContain("serial");
                }
                RawSql.refused(db, "two loose layers of one SKU, place, lot and receipt time", "INSERT INTO "
                        + "inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, "
                        + "received_at, version, created_at) SELECT gen_random_uuid(), sku, location_code, lot_number, "
                        + "1, 0, 'AVAILABLE', received_at, 0, NOW() FROM inventory.stock_item WHERE id = ?", serialUnit);

                // The one policy, for every SKU: the sofa was never configured, the table was.
                for (String sku : new String[] {TABLE, SOFA}) {
                    RawSql.refused(db, "live stock of " + sku + " with no lot", insert(sku, "NULL", "5"));
                    RawSql.refused(db, "live stock of " + sku + " with a blank lot", insert(sku, "'   '", "5"));
                    assertThat(RawSql.update(db, insert(sku, "'LOT-NEW-" + sku.charAt(0) + "'", "5"))).isOne();
                    assertThat(RawSql.update(db, insert(sku, "NULL", "0")))
                            .as("an emptied row with no lot is not live stock").isOne();
                }
                RawSql.refused(db, "a receipt time in the future", "INSERT INTO inventory.stock_item (id, sku, "
                        + "location_code, lot_number, on_hand, reserved, status, received_at, version, created_at) "
                        + "VALUES (gen_random_uuid(), '" + TABLE + "', 'FSP-RCV02', 'LOT-FUTURE', 1, 0, 'AVAILABLE', "
                        + "NOW() + interval '1 day', 0, NOW())");

                // A row's identity never changes in place; a layer is raised only by the application naming the row.
                RawSql.refused(db, "the lot of a row changed", "UPDATE inventory.stock_item SET lot_number = 'LOT-X' "
                        + "WHERE id = ?", serialUnit);
                RawSql.refused(db, "the receipt time of a row changed", "UPDATE inventory.stock_item SET received_at = "
                        + "NOW() - interval '3 days' WHERE id = ?", serialUnit);
                RawSql.refused(db, "the SKU of a row changed", "UPDATE inventory.stock_item SET sku = '" + SOFA
                        + "' WHERE id = ?", serialUnit);
                RawSql.refused(db, "a layer raised in place", "UPDATE inventory.stock_item SET on_hand = on_hand + 1 "
                        + "WHERE id = ?", serialUnit);
                assertThat(RawSql.update(db, "UPDATE inventory.stock_item SET on_hand = 0 WHERE id = ?", serialUnit))
                        .as("a layer that only shrinks is free").isOne();
                db.setAutoCommit(false);
                try {
                    RawSql.text(db, "SELECT set_config('stockflow.same_receipt_merge', ?, true)", UUID.randomUUID().toString());
                    RawSql.update(db, "SAVEPOINT other_row");
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> RawSql.update(db,
                            "UPDATE inventory.stock_item SET on_hand = 3 WHERE id = ?", serialUnit))
                            .as("the marker names another row").hasMessageContaining("new receipt layer");
                    RawSql.update(db, "ROLLBACK TO SAVEPOINT other_row");
                    RawSql.text(db, "SELECT set_config('stockflow.same_receipt_merge', ?, true)", serialUnit.toString());
                    assertThat(RawSql.update(db, "UPDATE inventory.stock_item SET on_hand = 3 WHERE id = ?", serialUnit))
                            .as("the marker names this row").isOne();
                    db.commit();
                } finally {
                    db.rollback();
                    db.setAutoCommit(true);
                }
                assertThat(RawSql.number(db, "SELECT on_hand FROM inventory.stock_item WHERE id = ?", serialUnit))
                        .isEqualTo(3);
                assertThat(receivedAt1).isNotNull();
            }
        }
    }

    private static String insert(String sku, String lot, String quantity) {
        String where = sku.startsWith("T") ? BIN_A : BIN_B;
        return "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, "
                + "received_at, version, created_at) VALUES (gen_random_uuid(), '" + sku + "', '" + where + "', " + lot
                + ", " + quantity + ", 0, 'AVAILABLE', NOW() - interval '1 hour', 0, NOW())";
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
