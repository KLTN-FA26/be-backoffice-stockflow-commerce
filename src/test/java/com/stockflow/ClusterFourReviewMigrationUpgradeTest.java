package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three migrations of the cluster 4 review on the real upgrade path: a database with the demo, migrated to the
 * migration before them, given what they clean up, then upgraded.
 *
 * <ul>
 *   <li>V20261117000100 (M-5): a live stock row in no carton, with a hold on it, is released and written off on the
 *       ledger; an emptied loose row stays; then {@code ck_stock_item_whole_cartons} refuses a live loose row. Review
 *       P-4: a warehouse SKU hold the write-off leaves uncovered is released, newest first, until the rest is covered;
 *       a hold the cartons still cover stays.</li>
 *   <li>V20261117000200 (Q-1, Q-2): a load estimate whose order or warehouse is missing is deleted, a good one stays,
 *       and both are keys now; the purchasing columns of {@code stock_lot} are gone, its CHECKs restated.</li>
 *   <li>V20261117000300 (D-1): a checkout reservation (HELD with a deadline) ends EXPIRED when the deadline passed and
 *       RELEASED when not, a warehouse hold (no deadline) stays HELD; {@code inventory-reservations:CREATE} is gone,
 *       and exactly the roles that held it have a new version.</li>
 * </ul>
 * One container for the three: they are one review's clean-up and run back to back on every upgrade.
 */
class ClusterFourReviewMigrationUpgradeTest {

    private static final String BEFORE = "20261116000300";
    private static final String AFTER = "20261117000300";
    private static final String SKU = "C4R-CUP";
    /** A SKU whose loose units covered part of its warehouse holds (review P-4). */
    private static final String LID = "C4R-LID";
    private static final UUID CUSTOMER = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final String HCM = "md5('demo:wh:HCM')::uuid";

    @Test
    void theReviewMigrationsCleanUpThenHoldTheirRules() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                UUID order = UUID.randomUUID();
                UUID loose = UUID.randomUUID();
                UUID emptied = UUID.randomUUID();
                UUID looseHold = UUID.randomUUID();
                UUID lapsed = UUID.randomUUID();
                UUID running = UUID.randomUUID();
                UUID warehouseHold = UUID.randomUUID();
                UUID goodEstimate = UUID.randomUUID();
                UUID noOrder = UUID.randomUUID();
                UUID noWarehouse = UUID.randomUUID();
                UUID olderLidHold = UUID.randomUUID();
                UUID newerLidHold = UUID.randomUUID();

                for (String sku : new String[] {SKU, LID}) {
                    UUID product = UUID.randomUUID();
                    RawSql.update(db, "INSERT INTO product.products (id, code, name, slug) VALUES (?, ?, 'Review "
                            + "fixture', ?)", product, sku, sku.toLowerCase(java.util.Locale.ROOT));
                    RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, attribute_signature) "
                            + "VALUES (gen_random_uuid(), ?, ?, 'Review fixture', '')", product, sku);
                }
                // A carton of each beside the loose units: what stays in stock after the write-off.
                carton(db, SKU, "HCM-A01-2-A", 6);
                carton(db, LID, "HCM-A02-1-A", 4);
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                        + "total_amount, currency, placed_at, version, created_at) VALUES (?, 'SO-20991230-000001', ?, "
                        + "gen_random_uuid(), 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW())", order, CUSTOMER);
                // Stock as the time before 4C-1 left it: ten loose units on a bin, two of them held for the order; and a
                // loose row 4C-3 emptied and relabelled.
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, "
                        + "status, received_at, version, created_at) VALUES (?, ?, 'HCM-A01-1-A', 'LOT-L', 10, 0, "
                        + "'AVAILABLE', '2026-10-01T00:00:00Z', 0, NOW())", loose, SKU);
                RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, "
                        + "root_request_id, request_id, quantity, reserved_at, status) VALUES (?, ?, ?, ?, gen_random_uuid(), "
                        + "gen_random_uuid(), 2, NOW(), 'HELD')", looseHold, loose, SKU, order);
                RawSql.update(db, "UPDATE inventory.stock_item SET reserved = 2 WHERE id = ?", loose);
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, "
                        + "status, version, created_at) VALUES (?, ?, 'HCM-A01-1-B', 'UNTRACED', 0, 0, 'AVAILABLE', 0, NOW())",
                        emptied, SKU);
                // P-4: five loose lids and a carton of 4 cover two Sales holds at HCM, 4 (older) and 3 (newer); the
                // write-off leaves 4, which covers the older hold only.
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, "
                        + "status, received_at, version, created_at) VALUES (gen_random_uuid(), ?, 'HCM-A01-1-B', 'LOT-L', 5, "
                        + "0, 'AVAILABLE', '2026-10-01T00:00:00Z', 0, NOW())", LID);
                RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, "
                        + "quantity, reserved_at, status, warehouse_code) VALUES (?, ?, ?, gen_random_uuid(), "
                        + "gen_random_uuid(), 4, NOW() - interval '2 hours', 'HELD', 'HCM'), (?, ?, ?, gen_random_uuid(), "
                        + "gen_random_uuid(), 3, NOW() - interval '1 hour', 'HELD', 'HCM')",
                        olderLidHold, LID, order, newerLidHold, LID, order);
                // Checkout reservations (a deadline: one passed, one not) and a Sales hold at HCM (no deadline); the cup
                // carton of 6 still covers all three after the write-off, so P-4 leaves them to V20261117000300.
                RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, "
                        + "quantity, reserved_at, expires_at, status) VALUES (?, ?, ?, gen_random_uuid(), gen_random_uuid(), "
                        + "1, NOW() - interval '1 hour', NOW() - interval '30 minutes', 'HELD'), (?, ?, ?, gen_random_uuid(), "
                        + "gen_random_uuid(), 1, NOW(), NOW() + interval '30 minutes', 'HELD')",
                        lapsed, SKU, order, running, SKU, order);
                RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, "
                        + "quantity, reserved_at, status, warehouse_code) VALUES (?, ?, ?, gen_random_uuid(), "
                        + "gen_random_uuid(), 1, NOW(), 'HELD', 'HCM')", warehouseHold, SKU, order);
                // Load estimates: the order's, one of an order that no longer exists, one of a warehouse that does not.
                estimate(db, goodEstimate, order, HCM, "CURRENT");
                estimate(db, noOrder, UUID.randomUUID(), HCM, "CURRENT");
                estimate(db, noWarehouse, order, "gen_random_uuid()", "SUPERSEDED");

                long cartonUnits = RawSql.number(db, "SELECT COALESCE(sum(on_hand), 0) FROM inventory.stock_item WHERE "
                        + "stock_package_id IS NOT NULL");
                Map<String, Long> versions = versions(db);

                flyway(pg, AFTER).migrate();

                // V20261117000100: the hold released, the units written off on the ledger, the emptied row kept.
                assertThat(RawSql.text(db, "SELECT on_hand || '|' || reserved FROM inventory.stock_item WHERE id = ?",
                        loose)).isEqualTo("0|0");
                assertThat(RawSql.text(db, "SELECT status || '|' || release_reason FROM inventory.stock_reservation "
                        + "WHERE id = ?", looseHold)).isEqualTo("RELEASED|MANUAL_OVERRIDE");
                assertThat(RawSql.text(db, "SELECT movement_type || '|' || reference_type || '|' || quantity || '|' || "
                        + "from_location_code FROM inventory.stock_movement WHERE reference_id = ?", loose))
                        .isEqualTo("ADJUSTMENT|STOCK_ADJUSTMENT|10|HCM-A01-1-A");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_movement WHERE reason LIKE "
                        + "'V20261117000100%'")).as("the demo had no loose units: only the fixture's are written off")
                        .isEqualTo(2);
                // P-4: the newer lid hold released, the older one, still covered by the carton of 4, kept.
                assertThat(RawSql.text(db, "SELECT status || '|' || release_reason FROM inventory.stock_reservation "
                        + "WHERE id = ?", newerLidHold)).isEqualTo("RELEASED|MANUAL_OVERRIDE");
                assertThat(RawSql.text(db, "SELECT status FROM inventory.stock_reservation WHERE id = ?", olderLidHold))
                        .isEqualTo("HELD");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item WHERE id = ? AND on_hand = 0",
                        emptied)).isOne();
                assertThat(RawSql.number(db, "SELECT COALESCE(sum(on_hand), 0) FROM inventory.stock_item WHERE "
                        + "stock_package_id IS NOT NULL")).as("stock in cartons untouched").isEqualTo(cartonUnits);
                RawSql.refusedBy(db, "ck_stock_item_whole_cartons", "a live stock row in no carton", "INSERT INTO "
                        + "inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, received_at, "
                        + "version, created_at) VALUES (gen_random_uuid(), ?, 'HCM-A01-2-A', 'LOT-N', 1, 0, 'AVAILABLE', "
                        + "NOW() - interval '1 hour', 0, NOW())", SKU);
                assertThat(RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, "
                        + "on_hand, reserved, status, version, created_at) VALUES (gen_random_uuid(), ?, 'HCM-A01-2-A', "
                        + "'LOT-E', 0, 0, 'AVAILABLE', 0, NOW())", SKU)).as("an emptied loose row is still allowed").isOne();

                // V20261117000200: orphaned estimates gone, the good one kept, both references keys now.
                assertThat(RawSql.texts(db, "SELECT id::text FROM fulfillment.load_estimate WHERE id IN (?, ?, ?)",
                        goodEstimate, noOrder, noWarehouse)).containsExactly(goodEstimate.toString());
                RawSql.refusedBy(db, "fk_load_estimate_order", "an estimate of no order", estimateSql(UUID.randomUUID(),
                        HCM, "SUPERSEDED"));
                RawSql.refusedBy(db, "fk_load_estimate_warehouse", "an estimate at no warehouse", estimateSql(order,
                        "gen_random_uuid()", "SUPERSEDED"));
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_lot' AND column_name IN ('purchase_order_id', "
                        + "'purchase_order_number', 'supplier_id', 'supplier_name')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conrelid = "
                        + "'inventory.stock_lot'::regclass AND conname IN ('ck_stock_lot_production_order', "
                        + "'ck_stock_lot_untraced') AND convalidated")).as("the lot rules restated").isEqualTo(2);

                // V20261117000300: checkout reservations closed by their deadline, the warehouse hold untouched.
                assertThat(RawSql.text(db, "SELECT status || '|' || release_reason FROM inventory.stock_reservation "
                        + "WHERE id = ?", lapsed)).isEqualTo("EXPIRED|RESERVATION_EXPIRED");
                assertThat(RawSql.text(db, "SELECT status || '|' || release_reason FROM inventory.stock_reservation "
                        + "WHERE id = ?", running)).isEqualTo("RELEASED|MANUAL_OVERRIDE");
                assertThat(RawSql.text(db, "SELECT status FROM inventory.stock_reservation WHERE id = ?",
                        warehouseHold)).isEqualTo("HELD");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_reservation WHERE status = 'HELD' "
                        + "AND expires_at IS NOT NULL")).as("no reservation with a deadline left").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE resource = "
                        + "'inventory-reservations' AND action = 'CREATE'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE resource = "
                        + "'inventory-reservations'")).as("reading, consuming and releasing stay").isPositive();
                Map<String, Long> after = versions(db);
                for (Map.Entry<String, Long> role : versions.entrySet()) {
                    long bump = Map.of("SYSTEM_ADMIN", 1, "WAREHOUSE_MANAGER", 1, "WAREHOUSE_STAFF", 1)
                            .getOrDefault(role.getKey(), 0);
                    assertThat(after.get(role.getKey())).as("version of %s", role.getKey())
                            .isEqualTo(role.getValue() + bump);
                }
            }
        }
    }

    /** One IN_STOCK carton of {@code units}, whole in its one row; a carton's contents are checked at commit. */
    private static void carton(Connection db, String sku, String location, int units) throws Exception {
        UUID unit = UUID.randomUUID();
        UUID carton = UUID.randomUUID();
        db.setAutoCommit(false);
        try {
            RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                    + "is_base) SELECT ?, id, ?, ?, FALSE FROM inventory.inventory_items WHERE sku = ?", unit,
                    "CTN" + units, units, sku);
            RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                    + "nominal_qty, received_qty, location_code, status) SELECT ?, ?, id, ?, ?, ?, ?, 'IN_STOCK' FROM "
                    + "inventory.inventory_items WHERE sku = ?", carton, sku + "-1", unit, units, units, location, sku);
            RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, "
                    + "status, received_at, stock_package_id, version, created_at) VALUES (gen_random_uuid(), ?, ?, "
                    + "'LOT-C', ?, 0, 'AVAILABLE', '2026-10-02T00:00:00Z', ?, 0, NOW())", sku, location, units, carton);
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    private static Map<String, Long> versions(Connection db) throws Exception {
        return RawSql.numbersByText(db, "SELECT code, version FROM identity.app_role");
    }

    private static void estimate(Connection db, UUID id, UUID order, String warehouse, String status) throws Exception {
        RawSql.update(db, estimateSql(order, warehouse, status).replace("gen_random_uuid(), 'ORDER'", "?, 'ORDER'"), id);
    }

    private static String estimateSql(UUID order, String warehouse, String status) {
        return "INSERT INTO fulfillment.load_estimate (id, source_type, source_id, warehouse_id, vehicle_type_ids, mode, "
                + "status, lines, layout, warnings, missing, incomplete, version, created_at) VALUES (gen_random_uuid(), "
                + "'ORDER', '" + order + "', " + warehouse + ", '[]'::jsonb, 'FEWEST_VEHICLES', '" + status + "', "
                + "'[]'::jsonb, '{}'::jsonb, '[]'::jsonb, '[]'::jsonb, FALSE, 0, NOW())";
    }

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        return Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .target(target)
                .load();
    }
}
