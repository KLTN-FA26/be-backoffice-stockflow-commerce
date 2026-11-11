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
 * V20261111000200 on the real upgrade path (team plan 4C-2; kltn-docs 20 §4.6, §5.5; 03 BR-12, BR-13; 05 BR-08; 11
 * BR-04): it changes no data, so what matters is how the rows written before it fare under its rules. The ledger lines
 * and putaway tasks written loose before stay as they are - the CHECKs are NOT VALID - while a new loose one is
 * refused; a carton consumed before it, opened as every consumed carton was, comes back into stock sealed, which the
 * old lifecycle refused; a draft carton counted at another figure than its standard count goes into stock only once
 * recounted at it; a sealed carton in stock is no longer opened.
 */
class WholeCartonsOnlyMigrationUpgradeTest {

    private static final String BEFORE = "20261111000110";
    private static final String MIGRATION = "20261111000200";

    private static final String CUP = "UPG2-CUP";
    private static final String BIN = "UPG2-A01-1-A";
    private static final String DOCK = "UPG2-RCV01";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000c2000";
    private static final String LOT = "00000000-0000-4000-8000-0000000c2001";
    private static final String CONSUMED = "00000000-0000-4000-8000-0000000c2101";
    private static final String DRAFT = "00000000-0000-4000-8000-0000000c2102";
    private static final String SEALED = "00000000-0000-4000-8000-0000000c2103";
    private static final String CONSUMED_ROW = "00000000-0000-4000-8000-0000000c2201";
    private static final String SEALED_ROW = "00000000-0000-4000-8000-0000000c2203";
    private static final String LOOSE_MOVE = "00000000-0000-4000-8000-0000000c2301";
    private static final String LOOSE_RECEIPT = "00000000-0000-4000-8000-0000000c2302";
    private static final String LOOSE_PUTAWAY = "00000000-0000-4000-8000-0000000c2303";
    private static final String LOOSE_TASK = "00000000-0000-4000-8000-0000000c2401";

    private static final String RESTOCK_SEALED = "UPDATE inventory.stock_package SET status = 'IN_STOCK', unopened = TRUE, "
            + "closed_at = NULL WHERE id = '" + CONSUMED + "'::uuid";
    private static final String REFILL = "UPDATE inventory.stock_item SET on_hand = 10 WHERE id = '" + CONSUMED_ROW
            + "'::uuid";

    @Test
    void rowsWrittenBeforeLiveUnderTheWholeCartonRules() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                // Before: a consumed carton never came back into stock, sealed or not.
                db.setAutoCommit(false);
                RawSql.refused(db, "CONSUMED -> IN_STOCK before V20261111000200", RESTOCK_SEALED);
                db.rollback();
                db.setAutoCommit(true);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // History stays: the loose lines and the loose task are there, and the CHECKs are NOT VALID.
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_movement WHERE id IN (?::uuid, "
                        + "?::uuid, ?::uuid)", LOOSE_MOVE, LOOSE_RECEIPT, LOOSE_PUTAWAY)).isEqualTo(3);
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.putaway_task WHERE id = ?::uuid",
                        LOOSE_TASK)).isOne();
                assertThat(RawSql.texts(db, "SELECT conname || ':' || convalidated FROM pg_constraint WHERE conname IN "
                        + "('ck_stock_movement_receipt_in_carton', 'ck_stock_movement_move_task_carton', "
                        + "'ck_stock_movement_putaway_carton', 'ck_putaway_task_receipt_carton') ORDER BY conname"))
                        .containsExactly("ck_putaway_task_receipt_carton:false",
                                "ck_stock_movement_move_task_carton:false", "ck_stock_movement_putaway_carton:false",
                                "ck_stock_movement_receipt_in_carton:false");

                // New loose lines are refused.
                RawSql.refused(db, "a new loose move by hand", "INSERT INTO inventory.stock_movement (id, "
                        + "movement_type, sku, lot_number, from_location_code, to_location_code, quantity, "
                        + "reference_type, reference_id, occurred_at) VALUES (gen_random_uuid(), 'MOVE', ?, 'L-1', ?, "
                        + "?, 1, 'MOVE_TASK', gen_random_uuid(), NOW())", CUP, BIN, DOCK);
                RawSql.refused(db, "a new loose putaway task of a receipt line", "INSERT INTO warehouse.putaway_task "
                        + "(id, goods_receipt_id, goods_receipt_line_id, sku, quantity, status, created_at) VALUES "
                        + "(gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), ?, 4, 'PENDING', NOW())", CUP);

                // The carton consumed before comes back whole and sealed - not open.
                db.setAutoCommit(false);
                RawSql.refused(db, "a consumed carton back in stock still open", "UPDATE inventory.stock_package SET "
                        + "status = 'IN_STOCK', closed_at = NULL WHERE id = ?::uuid", CONSUMED);
                db.rollback();
                RawSql.update(db, RESTOCK_SEALED);
                RawSql.update(db, REFILL);
                db.commit();
                db.setAutoCommit(true);
                assertThat(RawSql.text(db, "SELECT status || ':' || unopened FROM inventory.stock_package WHERE id = "
                        + "?::uuid", CONSUMED)).isEqualTo("IN_STOCK:true");

                // The draft counted at 8 of 10 goes into stock only recounted at 10.
                RawSql.refused(db, "a carton counted off its standard count into stock", "UPDATE "
                        + "inventory.stock_package SET status = 'IN_STOCK' WHERE id = ?::uuid", DRAFT);
                RawSql.refused(db, "a recount at another figure", "UPDATE inventory.stock_package SET received_qty = 9 "
                        + "WHERE id = ?::uuid", DRAFT);
                db.setAutoCommit(false);
                RawSql.update(db, "UPDATE inventory.stock_package SET received_qty = 10 WHERE id = ?::uuid", DRAFT);
                RawSql.update(db, "UPDATE inventory.stock_package SET status = 'IN_STOCK' WHERE id = ?::uuid", DRAFT);
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, lot_id, "
                        + "on_hand, reserved, status, created_at, received_at, stock_package_id) VALUES "
                        + "(gen_random_uuid(), ?, ?, 'L-1', ?::uuid, 10, 0, 'INBOUND', NOW(), NOW(), ?::uuid)", CUP,
                        DOCK, LOT, DRAFT);
                db.commit();
                db.setAutoCommit(true);

                // A sealed carton in stock is not opened.
                RawSql.refused(db, "a carton in stock opened", "UPDATE inventory.stock_package SET unopened = FALSE "
                        + "WHERE id = ?::uuid", SEALED);
                assertThat(RawSql.number(db, "SELECT on_hand FROM inventory.stock_item WHERE id = ?::uuid",
                        SEALED_ROW)).isEqualTo(10);
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        // The warehouse and the receipt documents are not what this is about: foreign keys and row triggers are off
        // while the old state is written. The SKU, its units and the places are real rows, which the cartons name.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, "
                + "is_pickable, is_putaway_target, status) VALUES (gen_random_uuid(), ?::uuid, 'BIN', ?, 'NORMAL', TRUE, "
                + "TRUE, 'ACTIVE'), (gen_random_uuid(), ?::uuid, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')",
                WAREHOUSE, BIN, WAREHOUSE, DOCK);
        RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, policy_configured, lot_tracked, "
                + "removal_strategy) VALUES (gen_random_uuid(), ?, TRUE, FALSE, 'FEFO')", CUP);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "is_base, active, version, created_at) SELECT gen_random_uuid(), id, 'EACH', 1, TRUE, TRUE, 0, NOW() "
                + "FROM inventory.inventory_items WHERE sku = ?", CUP);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "is_base, active, default_shipping, version, created_at) SELECT gen_random_uuid(), id, 'CTN', 10, "
                + "FALSE, TRUE, TRUE, 0, NOW() FROM inventory.inventory_items WHERE sku = ?", CUP);
        RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at) "
                + "VALUES (?::uuid, ?, 'L-1', NOW() - interval '9 days', 'UNTRACED', NOW())", LOT, CUP);

        // A carton picked whole before: CONSUMED, opened as every consumed carton is, its row emptied.
        carton(db, CONSUMED, "LPN-UPG2-C", BIN, "CONSUMED", false, 10);
        row(db, CONSUMED_ROW, BIN, 0, CONSUMED);
        // A draft receipt's carton counted at 8 of its 10 before the standard count was the only count.
        carton(db, DRAFT, "LPN-UPG2-D", DOCK, "RECEIVING", true, 8);
        // A sealed carton in stock.
        carton(db, SEALED, "LPN-UPG2-S", BIN, "IN_STOCK", true, 10);
        row(db, SEALED_ROW, BIN, 10, SEALED);

        // The ledger and a putaway task written loose before.
        RawSql.update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, "
                + "to_location_code, quantity, reference_type, reference_id, occurred_at) VALUES (?::uuid, 'MOVE', ?, "
                + "'L-1', ?, ?, 3, 'MOVE_TASK', gen_random_uuid(), NOW() - interval '8 days')", LOOSE_MOVE, CUP, BIN,
                DOCK);
        RawSql.update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, "
                + "quantity, to_status, reference_type, reference_id, occurred_at) VALUES (?::uuid, 'RECEIPT', ?, "
                + "'L-1', ?, 4, 'INBOUND', 'GOODS_RECEIPT_LINE', gen_random_uuid(), NOW() - interval '9 days')",
                LOOSE_RECEIPT, CUP, DOCK);
        RawSql.update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, "
                + "to_location_code, quantity, reference_type, reference_id, occurred_at) VALUES (?::uuid, 'MOVE', ?, "
                + "'L-1', ?, ?, 4, 'PUTAWAY_TASK', ?::uuid, NOW() - interval '9 days')", LOOSE_PUTAWAY, CUP, DOCK, BIN,
                LOOSE_TASK);
        RawSql.update(db, "INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, "
                + "quantity, status, created_at) VALUES (?::uuid, gen_random_uuid(), gen_random_uuid(), ?, 4, 'DONE', "
                + "NOW() - interval '9 days')", LOOSE_TASK, CUP);
        RawSql.update(db, "SET session_replication_role = DEFAULT");
    }

    private static void carton(Connection db, String id, String lpn, String location, String status, boolean unopened,
                               int received) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, received_qty, location_code, status, unopened, closed_at, created_at) SELECT ?::uuid, ?, "
                + "i.id, u.id, u.factor, ?, ?, ?, ?, CASE ? WHEN 'CONSUMED' THEN NOW() END, NOW() FROM "
                + "inventory.inventory_items i JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id AND "
                + "u.unit_of_measure = 'CTN' WHERE i.sku = ?", id, lpn, received, location, status, unopened, status,
                CUP);
    }

    private static void row(Connection db, String id, String location, int onHand, String carton) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, lot_id, on_hand, "
                + "reserved, status, created_at, received_at, stock_package_id) VALUES (?::uuid, ?, ?, 'L-1', ?::uuid, "
                + "?, 0, 'AVAILABLE', NOW(), NOW() - interval '9 days', ?::uuid)", id, CUP, location, LOT, onHand,
                carton);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
