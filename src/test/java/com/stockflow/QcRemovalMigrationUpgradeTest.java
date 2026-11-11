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
 * V20261108000200 on the real upgrade path: a database migrated to the migration before it, holding what receiving QC
 * left behind - a receipt in QC with a line QC never decided on (a carton and loose units) next to a line it accepted,
 * one in QC with a rejected part, one in putaway with a quarantined part, a move task to the QC area, a QC photo, an item
 * flagged for QC, people holding QC_STAFF (one of them also a warehouse manager) - and a SUBCONTRACT order's receipt
 * closed at confirmation, part of whose goods are still INBOUND since production's holds went - then upgraded.
 *
 * <p>Proves nothing is lost (every row dropped is archived), the undecided goods get the putaway tasks
 * {@code PutawayTaskCreator} would have opened and their receipt goes to putaway, a receipt holding units QC kept back
 * is closed, the subcontract goods still INBOUND get a putaway task, the CHECKs no longer know IN_QC or RECEIPT_QC, a
 * QC photo stays and no new one is taken, design approval
 * moves to the Sales Manager, and QC staff become warehouse staff with their sessions revoked.</p>
 */
class QcRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261108000100";
    private static final String MIGRATION = "20261108000200";

    @Test
    void upgradeRetiresReceivingQcKeepingEverythingInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                String sku = "QCX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                UUID product = UUID.randomUUID();
                UUID clerk = UUID.randomUUID();
                UUID qcOnly = UUID.randomUUID();
                UUID qcManager = UUID.randomUUID();
                UUID onlySession = UUID.randomUUID();
                UUID managerSession = UUID.randomUUID();
                UUID warehouse = UUID.randomUUID();
                UUID receiving = UUID.randomUUID();
                UUID qcArea = UUID.randomUUID();
                UUID quarantine = UUID.randomUUID();
                UUID undecidedReceipt = UUID.randomUUID();
                UUID undecidedLine = UUID.randomUUID();
                UUID acceptedLine = UUID.randomUUID();
                UUID carton = UUID.randomUUID();
                UUID acceptedTask = UUID.randomUUID();
                UUID rejectedReceipt = UUID.randomUUID();
                UUID rejectedLine = UUID.randomUUID();
                UUID secondUndecidedLine = UUID.randomUUID();
                UUID quarantinedReceipt = UUID.randomUUID();
                UUID quarantinedLine = UUID.randomUUID();
                UUID plainReceipt = UUID.randomUUID();
                UUID plainLine = UUID.randomUUID();
                UUID moveTask = UUID.randomUUID();
                UUID qcPhoto = UUID.randomUUID();
                UUID subcontractOrder = UUID.randomUUID();
                UUID subcontractReceipt = UUID.randomUUID();
                UUID subcontractLine = UUID.randomUUID();
                UUID subcontractLot = UUID.randomUUID();

                // The item exists for real: the putaway tasks the migration opens check it by foreign key and trigger.
                RawSql.update(db, "INSERT INTO product.products (id, code, name, slug) VALUES (?, ?, 'QC fixture', ?)",
                        product, sku, sku.toLowerCase());
                RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, attribute_signature) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'QC fixture', '')", product, sku);
                UUID item = UUID.fromString(RawSql.text(db,
                        "SELECT id::text FROM inventory.inventory_items WHERE sku = ?", sku));
                RawSql.update(db, "UPDATE inventory.inventory_items SET qc_required = TRUE WHERE id = ?", item);

                for (UUID user : new UUID[]{clerk, qcOnly, qcManager}) {
                    String name = "u" + user.toString().substring(0, 8);
                    RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, created_at) "
                            + "VALUES (?, ?, ?, 'ACTIVE', NOW())", user, name, name + "@example.com");
                }
                RawSql.update(db, "INSERT INTO identity.user_role (id, user_id, role_id, created_at) SELECT "
                        + "gen_random_uuid(), ?, id, NOW() FROM identity.app_role WHERE code = 'QC_STAFF'", qcOnly);
                RawSql.update(db, "INSERT INTO identity.user_role (id, user_id, role_id, created_at) SELECT "
                        + "gen_random_uuid(), ?, id, NOW() FROM identity.app_role WHERE code IN ('QC_STAFF', "
                        + "'WAREHOUSE_MANAGER')", qcManager);
                RawSql.update(db, "INSERT INTO identity.user_session (id, user_id, issued_at, expires_at, created_at) "
                        + "VALUES (?, ?, NOW(), NOW() + interval '1 hour', NOW())", onlySession, qcOnly);
                RawSql.update(db, "INSERT INTO identity.user_session (id, user_id, issued_at, expires_at, created_at) "
                        + "VALUES (?, ?, NOW(), NOW() + interval '1 hour', NOW())", managerSession, qcManager);
                long salesManagerVersion = version(db, "SALES_MANAGER");
                long procurementVersion = version(db, "PROCUREMENT_STAFF");
                long warehouseStaffVersion = version(db, "WAREHOUSE_STAFF");

                // Receipts, their lines and stock are not what this test is about beyond their QC state: their other
                // foreign keys and the row triggers are off while the old state is written.
                RawSql.update(db, "SET session_replication_role = replica");
                receipt(db, undecidedReceipt, "GR-QC-1", "IN_QC", warehouse, clerk);
                line(db, undecidedLine, undecidedReceipt, item, receiving, 10, null, null);
                line(db, acceptedLine, undecidedReceipt, item, receiving, 5, qcArea, clerk);
                inspection(db, acceptedLine, "ACCEPTED", 5, null, null, clerk);
                RawSql.update(db, "INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, "
                        + "quantity, status, version, created_at) VALUES (?, ?, ?, ?, 5, 'PENDING', 0, NOW())",
                        acceptedTask, undecidedReceipt, acceptedLine, sku);
                // The undecided line: a carton of 6, still unopened at the receiving area, and 4 loose.
                RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                        + "nominal_qty, received_qty, location_code, status, unopened, goods_receipt_line_id, created_at) "
                        + "VALUES (?, 'LPN-20261108-900001', ?, gen_random_uuid(), 6, 6, 'QCX-RCV01', 'IN_STOCK', TRUE, ?, "
                        + "NOW())", carton, item, undecidedLine);
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, "
                        + "stock_package_id, created_at) VALUES (gen_random_uuid(), ?, 'QCX-RCV01', 6, 0, 'INBOUND', ?, NOW())",
                        sku, carton);
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, "
                        + "created_at) VALUES (gen_random_uuid(), ?, 'QCX-RCV01', 4, 0, 'INBOUND', NOW())", sku);

                receipt(db, rejectedReceipt, "GR-QC-2", "IN_QC", warehouse, clerk);
                line(db, rejectedLine, rejectedReceipt, item, receiving, 4, qcArea, clerk);
                inspection(db, rejectedLine, "ACCEPTED", 3, null, null, clerk);
                inspection(db, rejectedLine, "REJECTED", 1, quarantine, "cracked", clerk);
                line(db, secondUndecidedLine, rejectedReceipt, item, receiving, 2, null, null);

                receipt(db, quarantinedReceipt, "GR-QC-3", "IN_PUTAWAY", warehouse, clerk);
                line(db, quarantinedLine, quarantinedReceipt, item, receiving, 3, qcArea, clerk);
                inspection(db, quarantinedLine, "ACCEPTED", 2, null, null, clerk);
                inspection(db, quarantinedLine, "QUARANTINE", 1, quarantine, "dented", clerk);

                receipt(db, plainReceipt, "GR-QC-4", "IN_PUTAWAY", warehouse, clerk);
                RawSql.update(db, "INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, "
                        + "inventory_item_id, location_id, received_qty, qc_required, created_at) "
                        + "VALUES (?, ?, gen_random_uuid(), ?, ?, 7, FALSE, NOW())",
                        plainLine, plainReceipt, item, receiving);

                RawSql.update(db, "INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, sku, "
                        + "from_location_id, to_location_id, requested_qty, receipt_line_id, created_at) "
                        + "VALUES (?, 'MT-QC-1', ?, 'RECEIPT_QC', ?, ?, ?, 5, ?, NOW())",
                        moveTask, warehouse, sku, receiving, qcArea, acceptedLine);
                RawSql.update(db, "INSERT INTO procurement.goods_receipt_photos (id, receipt_id, receipt_line_id, stage, "
                        + "storage_key, original_name, content_type, size_bytes, checksum, taken_by) VALUES (?, ?, ?, 'QC', "
                        + "'qc-evidence/2026/11/01/x.jpg', 'x.jpg', 'image/jpeg', 1, ?, ?)",
                        qcPhoto, undecidedReceipt, acceptedLine, "0".repeat(64), clerk);
                // A SUBCONTRACT order's receipt of 8, CLOSED as it was confirmed; 5 of its units are still INBOUND
                // in its lot (production moved the rest), the 3 others are no longer the receipt's to put away.
                RawSql.update(db, "INSERT INTO procurement.purchase_orders (id, po_number, supplier_id, warehouse_id, "
                        + "currency, order_date, po_type, production_order_id) VALUES (?, 'PO-SUB-1', gen_random_uuid(), ?, "
                        + "'VND', CURRENT_DATE, 'SUBCONTRACT', gen_random_uuid())", subcontractOrder, warehouse);
                RawSql.update(db, "INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, "
                        + "warehouse_id, received_at, received_by, status, confirmed_at, confirmed_by, closed_at, version, "
                        + "created_at) VALUES (?, 'GR-QC-5', ?, gen_random_uuid(), ?, NOW(), ?, 'CLOSED', NOW(), ?, NOW(), 0, "
                        + "NOW())", subcontractReceipt, subcontractOrder, warehouse, clerk, clerk);
                RawSql.update(db, "INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, "
                        + "inventory_item_id, location_id, received_qty, qc_required, created_at) "
                        + "VALUES (?, ?, gen_random_uuid(), ?, ?, 8, FALSE, NOW())",
                        subcontractLine, subcontractReceipt, item, receiving);
                RawSql.update(db, "UPDATE procurement.goods_receipt_lines SET lot_number = 'SUB-1' WHERE id = ?",
                        subcontractLine);
                RawSql.update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, "
                        + "to_location_code, quantity, to_status, reference_type, reference_id, lot_id, occurred_at) VALUES "
                        + "(gen_random_uuid(), 'RECEIPT', ?, 'SUB-1', 'QCX-RCV01', 8, 'INBOUND', 'GOODS_RECEIPT_LINE', ?, ?, "
                        + "NOW())",
                        sku, subcontractLine, subcontractLot);
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, "
                        + "status, lot_id, created_at) VALUES (gen_random_uuid(), ?, 'QCX-RCV01', 'SUB-1', 5, 0, 'INBOUND', ?, "
                        + "NOW())",
                        sku, subcontractLot);
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, "
                        + "status, lot_id, created_at) VALUES (gen_random_uuid(), ?, 'QCX-B01', 'SUB-1', 3, 0, 'AVAILABLE', ?, "
                        + "NOW())",
                        sku, subcontractLot);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // The undecided goods are accepted as they stand: a task for the carton, one for the loose units.
                assertThat(RawSql.texts(db, "SELECT quantity || ':' || COALESCE(stock_package_id::text, 'loose') || ':' "
                        + "|| status FROM warehouse.putaway_task WHERE goods_receipt_line_id = ? ORDER BY quantity",
                        undecidedLine)).containsExactly("4:loose:PENDING", "6:" + carton + ":PENDING");
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.putaway_task WHERE goods_receipt_line_id = ?",
                        acceptedLine)).as("a line with a task was processed already").isEqualTo(1);
                assertThat(RawSql.texts(db, "SELECT quantity || ':' || COALESCE(stock_package_id::text, 'loose') "
                        + "FROM warehouse.putaway_task WHERE goods_receipt_line_id = ?", secondUndecidedLine))
                        .containsExactly("2:loose");
                assertThat(RawSql.text(db, "SELECT status FROM procurement.goods_receipts WHERE id = ?",
                        undecidedReceipt)).isEqualTo("IN_PUTAWAY");
                // Units QC kept back are never put away from a receipt: those receipts are closed.
                for (UUID closed : new UUID[]{rejectedReceipt, quarantinedReceipt}) {
                    assertThat(RawSql.text(db, "SELECT status || ':' || (closed_at IS NOT NULL) FROM "
                            + "procurement.goods_receipts WHERE id = ?", closed)).isEqualTo("CLOSED:true");
                }
                assertThat(RawSql.text(db, "SELECT status FROM procurement.goods_receipts WHERE id = ?", plainReceipt))
                        .isEqualTo("IN_PUTAWAY");
                // The subcontract goods still INBOUND get a loose task of what is there, not of what was received.
                assertThat(RawSql.texts(db, "SELECT quantity || ':' || COALESCE(stock_package_id::text, 'loose') "
                        + "FROM warehouse.putaway_task WHERE goods_receipt_line_id = ?", subcontractLine))
                        .containsExactly("5:loose");
                assertThat(RawSql.text(db, "SELECT status FROM procurement.goods_receipts WHERE id = ?",
                        subcontractReceipt)).isEqualTo("CLOSED");

                // Archived before it went.
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'procurement.qc_inspections'")).isEqualTo(5);
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'procurement.goods_receipt_lines.qc' AND row_id IN (?, ?, ?, ?, ?)", undecidedLine, acceptedLine,
                        rejectedLine, secondUndecidedLine, quarantinedLine)).isEqualTo(5);
                assertThat(RawSql.text(db, "SELECT row_data ->> 'qc_location_id' FROM platform.legacy_archive WHERE "
                        + "source_table = 'procurement.goods_receipt_lines.qc' AND row_id = ?", acceptedLine))
                        .isEqualTo(qcArea.toString());
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'inventory.inventory_items.qc_required' AND row_id = ?", item)).isEqualTo(1);
                assertThat(RawSql.texts(db, "SELECT row_data ->> 'status' FROM platform.legacy_archive WHERE "
                        + "source_table = 'procurement.goods_receipts' AND row_id IN (?, ?, ?, ?) ORDER BY 1",
                        undecidedReceipt, rejectedReceipt, quarantinedReceipt, plainReceipt))
                        .containsExactly("IN_PUTAWAY", "IN_QC", "IN_QC");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'warehouse.move_task' AND row_id = ?", moveTask)).isEqualTo(1);
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.move_task WHERE id = ?", moveTask)).isZero();

                // What QC kept in the schema is gone; the CHECKs no longer know its values.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'procurement' AND table_name = 'qc_inspections'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE (table_schema, "
                        + "table_name, column_name) IN (('procurement', 'goods_receipt_lines', 'qc_required'), "
                        + "('procurement', 'goods_receipt_lines', 'qc_location_id'), ('inventory', 'inventory_items', "
                        + "'qc_required'), ('warehouse', 'move_task', 'receipt_line_id'))")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE proname IN ('check_qc_inspection', "
                        + "'check_qc_inspection_moved', 'check_goods_receipt_line_qc_move', 'check_move_task_receipt_qc')"))
                        .isZero();
                RawSql.refused(db, "ck_goods_receipts_status",
                        "UPDATE procurement.goods_receipts SET status = 'IN_QC' WHERE id = ?", plainReceipt);
                // A posted line still keeps what was counted (BR-05), through the rewritten function.
                RawSql.refused(db, "BR-05", "UPDATE procurement.goods_receipt_lines SET received_qty = 8 WHERE id = ?",
                        plainLine);

                // The QC photo stays as evidence; no new one is taken.
                assertThat(RawSql.text(db, "SELECT stage FROM procurement.goods_receipt_photos WHERE id = ?", qcPhoto))
                        .isEqualTo("QC");
                RawSql.refused(db, "no QC photo", "INSERT INTO procurement.goods_receipt_photos (id, receipt_id, "
                        + "receipt_line_id, stage, storage_key, original_name, content_type, size_bytes, checksum, "
                        + "taken_by) VALUES (gen_random_uuid(), ?, ?, 'QC', 'qc-evidence/2026/11/09/y.jpg', 'y.jpg', "
                        + "'image/jpeg', 1, ?, ?)", undecidedReceipt, acceptedLine, "0".repeat(64), clerk);

                // Design approval is the Sales Manager's; the QC task permissions are gone with their grants.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.role_permission rp JOIN identity.app_role r "
                        + "ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id WHERE r.code = "
                        + "'SALES_MANAGER' AND p.code = 'design-designs:APPROVE'")).isEqualTo(1);
                assertThat(version(db, "SALES_MANAGER")).isEqualTo(salesManagerVersion + 1);
                assertThat(version(db, "PROCUREMENT_STAFF")).as("lost the QC task grants")
                        .isEqualTo(procurementVersion + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).as("its grants did not change")
                        .isEqualTo(warehouseStaffVersion);
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code LIKE "
                        + "'procurement-qc-tasks:%'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'identity.permission' AND row_data ->> 'code' LIKE 'procurement-qc-tasks:%'")).isEqualTo(3);

                // QC staff are warehouse staff; a warehouse manager stays one; both sign in again.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.app_role WHERE code = 'QC_STAFF'")).isZero();
                assertThat(roles(db, qcOnly)).containsExactly("WAREHOUSE_STAFF");
                assertThat(roles(db, qcManager)).containsExactly("WAREHOUSE_MANAGER");
                assertThat(RawSql.texts(db, "SELECT revoked_reason FROM identity.user_session WHERE id IN (?, ?)",
                        onlySession, managerSession)).containsOnly("ROLE_CHANGED").hasSize(2);
                assertThat(RawSql.text(db, "SELECT status FROM identity.app_user WHERE id = ?", qcOnly))
                        .isEqualTo("ACTIVE");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'identity.user_role' AND row_data ->> 'user_id' IN (?, ?)", qcOnly.toString(),
                        qcManager.toString())).isEqualTo(2);
            }
        }
    }

    private static void receipt(Connection db, UUID id, String number, String status, UUID warehouse, UUID clerk)
            throws Exception {
        RawSql.update(db, "INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, "
                + "warehouse_id, received_at, received_by, status, confirmed_at, confirmed_by, version, created_at) "
                + "VALUES (?, ?, gen_random_uuid(), gen_random_uuid(), ?, NOW(), ?, ?, NOW(), ?, 0, NOW())",
                id, number, warehouse, clerk, status, clerk);
    }

    /** A QC line; {@code qcArea} non-null when its goods were moved to the QC area. */
    private static void line(Connection db, UUID id, UUID receipt, UUID item, UUID location, int quantity, UUID qcArea,
                             UUID movedBy) throws Exception {
        // Each line its own PO line (fake: foreign keys are off), as one line per PO line and lot requires.
        RawSql.update(db, "INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, "
                + "location_id, received_qty, qc_required, qc_location_id, moved_to_qc_at, moved_to_qc_by, created_at) "
                + "VALUES (?, ?, gen_random_uuid(), ?, ?, ?, TRUE, ?, CASE WHEN ?::uuid IS NULL THEN NULL ELSE NOW() END, "
                + "?, NOW())",
                id, receipt, item, location, quantity, qcArea, qcArea, movedBy);
    }

    private static void inspection(Connection db, UUID line, String outcome, int quantity, UUID target, String reason,
                                   UUID by) throws Exception {
        RawSql.update(db, "INSERT INTO procurement.qc_inspections (id, receipt_line_id, outcome, quantity, "
                + "target_location_id, reason, inspected_by, inspected_at, created_at) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, NOW(), NOW())", line, outcome, quantity, target, reason,
                by);
    }

    private static long version(Connection db, String role) throws Exception {
        return RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = ?", role);
    }

    private static java.util.List<String> roles(Connection db, UUID user) throws Exception {
        return RawSql.texts(db, "SELECT r.code FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id "
                + "WHERE ur.user_id = ? ORDER BY r.code", user);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
