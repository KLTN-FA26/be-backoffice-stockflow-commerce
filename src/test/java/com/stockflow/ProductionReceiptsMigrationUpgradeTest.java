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
 * V20261108002800 on the real upgrade path (team plan 4A-5, kltn-docs 03 BR-03): goods receipts and lots written before
 * it stay valid and keep their rules. A receipt is a PURCHASE receipt, the blind one still a blind one; a goods receipt
 * lot is still one per receipt and (SKU, lot number, expiry) - the unique index now names goods receipt lots only - and
 * an untraced lot still names no document. Only production order lots are one per production order and SKU.
 */
class ProductionReceiptsMigrationUpgradeTest {

    private static final String BEFORE = "20261108002700";
    private static final String MIGRATION = "20261108002800";
    private static final String USER = "00000000-0000-4000-8000-0000000000a1";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000000a2";
    private static final String SUPPLIER = "00000000-0000-4000-8000-0000000000a3";
    private static final String PRODUCT = "00000000-0000-4000-8000-0000000000a4";
    private static final String RECEIPT = "00000000-0000-4000-8000-0000000000a5";
    private static final String RECEIPT_LOT = "00000000-0000-4000-8000-0000000000a6";
    private static final String UNTRACED_LOT = "00000000-0000-4000-8000-0000000000a7";
    private static final String SKU = "UPG-PR";

    @Test
    void receiptsAndLotsWrittenBeforeStayValid() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.text(db, "SELECT receipt_type || ':' || (production_order_id IS NULL) || ':' "
                        + "|| supplier_id || ':' || blind_reason FROM procurement.goods_receipts WHERE id = ?::uuid",
                        RECEIPT)).as("a receipt written before is a purchase receipt, blind as it was")
                        .isEqualTo("PURCHASE:true:" + SUPPLIER + ":arrived without a PO");
                assertThat(RawSql.texts(db, "SELECT origin_type || ':' || COALESCE(goods_receipt_number, '-') || ':' "
                        + "|| COALESCE(supplier_name, '-') || ':' || (production_order_id IS NULL) || ':' "
                        + "|| (production_order_code IS NULL) FROM inventory.stock_lot WHERE sku = ? ORDER BY origin_type",
                        SKU)).as("lots keep what they named, and name no production order")
                        .containsExactly("GOODS_RECEIPT:GR-UPG-1:Upgrade supplier:true:true", "UNTRACED:-:-:true:true");

                // One goods receipt lot per receipt and (SKU, lot number, expiry), as before.
                RawSql.refused(db, "uk_stock_lot_receipt", "INSERT INTO inventory.stock_lot (id, sku, lot_number, "
                        + "received_at, origin_type, goods_receipt_id, goods_receipt_number) VALUES (gen_random_uuid(), "
                        + "?, 'L1', NOW(), 'GOODS_RECEIPT', ?::uuid, 'GR-UPG-1')", SKU, RECEIPT);
                assertThat(RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, "
                        + "origin_type, goods_receipt_id, goods_receipt_number) VALUES (gen_random_uuid(), ?, 'L2', NOW(), "
                        + "'GOODS_RECEIPT', ?::uuid, 'GR-UPG-1')", SKU, RECEIPT)).as("another lot number of it")
                        .isEqualTo(1);
                RawSql.refused(db, "ck_stock_lot_receipt", "INSERT INTO inventory.stock_lot (id, sku, lot_number, "
                        + "origin_type) VALUES (gen_random_uuid(), ?, 'L3', 'GOODS_RECEIPT')", SKU);
                RawSql.refused(db, "ck_stock_lot_untraced", "INSERT INTO inventory.stock_lot (id, sku, lot_number, "
                        + "origin_type, supplier_id) VALUES (gen_random_uuid(), ?, 'L4', 'UNTRACED', ?::uuid)", SKU,
                        SUPPLIER);
                RawSql.refused(db, "ck_stock_lot_production_order", "INSERT INTO inventory.stock_lot (id, sku, "
                        + "lot_number, received_at, origin_type) VALUES (gen_random_uuid(), ?, 'PRD-20261109-0001', "
                        + "NOW(), 'PRODUCTION_ORDER')", SKU);

                // A purchase receipt is still against a PO or blind; a production one is against a production order.
                RawSql.refused(db, "ck_goods_receipts_blind", "INSERT INTO procurement.goods_receipts (id, "
                        + "receipt_number, warehouse_id, status, received_at, received_by) VALUES (gen_random_uuid(), "
                        + "'GR-UPG-2', ?::uuid, 'DRAFT', NOW(), ?::uuid)", WAREHOUSE, USER);
                RawSql.refused(db, "ck_goods_receipts_production_order", "INSERT INTO procurement.goods_receipts (id, "
                        + "receipt_number, receipt_type, warehouse_id, status, received_at, received_by) VALUES "
                        + "(gen_random_uuid(), 'GR-UPG-3', 'PRODUCTION', ?::uuid, 'DRAFT', NOW(), ?::uuid)", WAREHOUSE,
                        USER);
                assertThat(RawSql.update(db, "UPDATE procurement.goods_receipts SET status = 'CANCELLED' "
                        + "WHERE id = ?::uuid", RECEIPT)).as("the blind receipt moves on as it did").isEqualTo(1);

                assertThat(RawSql.texts(db, "SELECT indexname FROM pg_indexes WHERE schemaname = 'inventory' "
                        + "AND tablename = 'stock_lot' AND indexname LIKE 'uk_stock_lot%' ORDER BY 1"))
                        .containsExactly("uk_stock_lot_production_order", "uk_stock_lot_receipt");
                assertThat(RawSql.text(db, "SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_stock_lot_receipt'"))
                        .contains("WHERE ((origin_type)::text = 'GOODS_RECEIPT'::text)");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                + "created_at) VALUES (?::uuid, 'pr-clerk', 'pr-clerk@example.com', '!x', 'ACTIVE', 0, NOW())", USER);
        RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, "
                + "map_height, version, created_at) VALUES (?::uuid, 'Upgrade', 'ACTIVE', 'UPR', 'Somewhere', 'M', 50, "
                + "50, 0, NOW())", WAREHOUSE);
        RawSql.update(db, "INSERT INTO procurement.suppliers (id, code, name) VALUES (?::uuid, 'UPG-SUP', "
                + "'Upgrade supplier')", SUPPLIER);
        RawSql.update(db, "INSERT INTO product.products (id, code, name, slug) VALUES (?::uuid, 'UPGPR', 'Upgrade "
                + "fixture', 'upgpr')", PRODUCT);
        RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, attribute_signature) VALUES "
                + "(gen_random_uuid(), ?::uuid, ?, 'Upgrade fixture', '')", PRODUCT, SKU);
        RawSql.update(db, "INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, supplier_id, "
                + "blind_reason, status, received_at, received_by) VALUES (?::uuid, 'GR-UPG-1', ?::uuid, ?::uuid, "
                + "'arrived without a PO', 'DRAFT', NOW(), ?::uuid)", RECEIPT, WAREHOUSE, SUPPLIER, USER);
        RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, "
                + "goods_receipt_id, goods_receipt_number, supplier_id, supplier_name, warehouse_id) VALUES (?::uuid, ?, "
                + "'L1', NOW(), 'GOODS_RECEIPT', ?::uuid, 'GR-UPG-1', ?::uuid, 'Upgrade supplier', ?::uuid)",
                RECEIPT_LOT, SKU, RECEIPT, SUPPLIER, WAREHOUSE);
        RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, origin_type) VALUES (?::uuid, ?, "
                + "'OLD', 'UNTRACED')", UNTRACED_LOT, SKU);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
