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
 * V20261214000100 on the real upgrade path (receiving redundancy removal, A2). It deletes the purchase receipts a
 * database may still hold - with their lines and photos - and moves a receipt stored CONFIRMED on to IN_PUTAWAY. A lot
 * such a receipt opened (origin GOODS_RECEIPT) is named by stock rows and by {@code fk_stock_lot_goods_receipt ON DELETE
 * RESTRICT}, so the delete would stop on it: the lot must survive, UNTRACED and naming no receipt. The rows are written
 * with triggers off because what is tested is the migration on rows the old rules accepted; the new CHECKs are
 * {@code tools/db/qa/96_receiving_redundancy_removal.sql}'s.
 */
class PurchaseReceiptsRemovedMigrationUpgradeTest {

    private static final String BEFORE = "20261213000200";
    private static final String MIGRATION = "20261214000100";
    private static final String PURCHASE = "00000000-0000-4000-8000-0000000e0b01";
    private static final String PURCHASE_LINE = "00000000-0000-4000-8000-0000000e0b02";
    private static final String PHOTO = "00000000-0000-4000-8000-0000000e0b03";
    private static final String LOT = "00000000-0000-4000-8000-0000000e0b04";
    private static final String PRODUCTION = "00000000-0000-4000-8000-0000000e0b05";
    private static final String SKU = "UPG-PR-SKU";

    @Test
    void purchaseReceiptsGoTheirLotsStayUntracedAndConfirmedMovesOn() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.number(db, "SELECT count(*) FROM procurement.goods_receipts WHERE id = ?::uuid",
                        PURCHASE)).as("the purchase receipt").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM procurement.goods_receipt_lines WHERE id = ?::uuid",
                        PURCHASE_LINE)).as("its line").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM procurement.goods_receipt_photos WHERE id = ?::uuid",
                        PHOTO)).as("its photo").isZero();
                assertThat(RawSql.texts(db, "SELECT origin_type || ':' || coalesce(goods_receipt_id::text, '-') "
                        + "FROM inventory.stock_lot WHERE id = ?::uuid", LOT))
                        .as("the lot it opened stays, untraced and naming no receipt").containsExactly("UNTRACED:-");
                assertThat(RawSql.texts(db, "SELECT status FROM procurement.goods_receipts WHERE id = ?::uuid",
                        PRODUCTION)).as("the receipt stored CONFIRMED moved on").containsExactly("IN_PUTAWAY");
                RawSql.refusedBy(db, "ck_goods_receipts_status", "CONFIRMED is no longer stored",
                        "UPDATE procurement.goods_receipts SET status = 'CONFIRMED' WHERE id = ?::uuid", PRODUCTION);
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, warehouse_id, "
                + "status, received_at, received_by, confirmed_at, confirmed_by, created_at) VALUES (?::uuid, "
                + "'GR-UPG-P1', 'PURCHASE', gen_random_uuid(), 'IN_PUTAWAY', NOW(), gen_random_uuid(), NOW(), "
                + "gen_random_uuid(), NOW())", PURCHASE);
        RawSql.update(db, "INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, "
                + "location_id, received_qty, lot_number, uom_conversion_id, created_at) VALUES (?::uuid, ?::uuid, "
                + "gen_random_uuid(), gen_random_uuid(), 10, 'UPG-PR-LOT', gen_random_uuid(), NOW())",
                PURCHASE_LINE, PURCHASE);
        RawSql.update(db, "INSERT INTO procurement.goods_receipt_photos (id, receipt_id, stage, storage_key, "
                + "original_name, content_type, size_bytes, checksum, taken_by, created_at) VALUES (?::uuid, ?::uuid, "
                + "'RECEIPT', 'qc-evidence/upg.jpg', 'upg.jpg', 'image/jpeg', 1, repeat('a', 64), gen_random_uuid(), "
                + "NOW())", PHOTO, PURCHASE);
        RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, "
                + "goods_receipt_id, goods_receipt_number, created_at) VALUES (?::uuid, ?, 'UPG-PR-LOT', NOW(), "
                + "'GOODS_RECEIPT', ?::uuid, 'GR-UPG-P1', NOW())", LOT, SKU, PURCHASE);
        RawSql.update(db, "INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, "
                + "production_order_id, warehouse_id, status, received_at, received_by, confirmed_at, confirmed_by, "
                + "created_at) VALUES (?::uuid, 'GR-UPG-P2', 'PRODUCTION', gen_random_uuid(), gen_random_uuid(), "
                + "'CONFIRMED', NOW(), gen_random_uuid(), NOW(), gen_random_uuid(), NOW())", PRODUCTION);
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
