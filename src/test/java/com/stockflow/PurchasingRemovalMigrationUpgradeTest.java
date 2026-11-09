package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * V20261113000200 on the real upgrade path (team plan 4A-9): a database migrated to the migration before it, holding
 * what purchasing left behind - purchase orders of every settled status and a DRAFT one, a subcontract order with its
 * settlement, an invoice with a line and a variance, suppliers with their catalogue, a reorder proposal, approval limits,
 * a closed and a blind purchase receipt whose lines lack a lot or a unit, a QC and a RECEIPT photo, an item with purchasing
 * columns, a lot naming its purchase order, a subcontractor site and unfinished and finished event publications - then
 * upgraded.
 *
 * <p>Proves the migration refuses, naming the rows, while a submitted purchase order, a purchase receipt not yet counted
 * in, a settlement awaiting approval or a base-unit line of an item without a base unit remain, and that a DRAFT order
 * does not block it. When it runs: everything dropped is archived whole, the tables, columns and functions are gone, the
 * lines get their unit and lot (the item's base unit; the receipt number), a stock lot keeps its snapshot, no purchase
 * receipt can be started, the CHECKs no longer know PENDING_APPROVAL or the QC stage, {@code item_in_use} counts production
 * order lines, a new area commits and a subcontractor location does not, the publications nobody can deliver are gone and
 * exactly the roles that lost a grant had their version advanced.</p>
 */
class PurchasingRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261110000200";
    private static final String MIGRATION = "20261113000200";

    /** The four goods-receipt permissions that stay; every other procurement permission goes. */
    private static final List<String> KEPT_PERMISSIONS = List.of("procurement-goods-receipts:CREATE",
            "procurement-goods-receipts:READ", "procurement-goods-receipts:UPDATE",
            "procurement-goods-receipts:VIEW_PAGE");

    private static final List<String> LOSING_ROLES = List.of("ACCOUNTANT", "INVENTORY_PLANNER", "PROCUREMENT_STAFF",
            "SALES_MANAGER", "SYSTEM_ADMIN", "WAREHOUSE_MANAGER", "WAREHOUSE_STAFF");

    private static final List<String> DROPPED_PROCUREMENT_TABLES = List.of("suppliers", "supplier_contacts",
            "supplier_addresses", "supplier_items", "supplier_item_prices", "approval_limits", "replenishment_proposals",
            "replenishment_proposal_lines", "purchase_orders", "purchase_order_revisions", "purchase_order_lines",
            "purchase_order_line_revisions", "purchase_order_approvals", "purchase_order_events",
            "purchase_order_attachments", "po_delivery_decision", "po_number_sequence", "subcontract_settlement",
            "supplier_invoices", "supplier_invoice_lines", "supplier_invoice_variances");

    private static final List<String> DROPPED_FUNCTIONS = List.of("check_po_revision_pointers", "check_po_line_revision",
            "check_po_line_revision_pair", "check_po_line_unit", "check_subcontract_po", "check_subcontract_po_single_line",
            "guard_po_warehouse_change", "guard_po_line_content_change", "check_supplier_invoice",
            "check_supplier_invoice_line", "check_supplier_invoice_match", "check_subcontractor_site_location",
            "check_goods_receipt_tolerance");

    /** Rows seeded per archive source table. */
    private static final Map<String, Long> ARCHIVED = new LinkedHashMap<>();

    static {
        ARCHIVED.put("procurement.suppliers", 2L);
        ARCHIVED.put("procurement.supplier_contacts", 1L);
        ARCHIVED.put("procurement.supplier_addresses", 1L);
        ARCHIVED.put("procurement.supplier_items", 1L);
        ARCHIVED.put("procurement.supplier_item_prices", 1L);
        ARCHIVED.put("procurement.approval_limits", 1L);
        ARCHIVED.put("procurement.replenishment_proposals", 1L);
        ARCHIVED.put("procurement.replenishment_proposal_lines", 1L);
        ARCHIVED.put("procurement.purchase_orders", 5L);
        ARCHIVED.put("procurement.purchase_order_revisions", 4L);
        ARCHIVED.put("procurement.purchase_order_lines", 5L);
        ARCHIVED.put("procurement.purchase_order_approvals", 1L);
        ARCHIVED.put("procurement.purchase_order_events", 5L);
        ARCHIVED.put("procurement.purchase_order_attachments", 1L);
        ARCHIVED.put("procurement.po_delivery_decision", 1L);
        ARCHIVED.put("procurement.po_number_sequence", 1L);
        ARCHIVED.put("procurement.subcontract_settlement", 1L);
        ARCHIVED.put("procurement.supplier_invoices", 1L);
        ARCHIVED.put("procurement.supplier_invoice_lines", 1L);
        ARCHIVED.put("procurement.supplier_invoice_variances", 1L);
        ARCHIVED.put("notification.po_delivery_control", 1L);
        ARCHIVED.put("notification.po_retry_cursor", 1L);
        ARCHIVED.put("warehouse.subcontractor_site", 1L);
        ARCHIVED.put("procurement.goods_receipts.purchase", 2L);
        ARCHIVED.put("procurement.goods_receipt_lines.purchase", 3L);
        ARCHIVED.put("procurement.goods_receipt_photos", 1L);
        ARCHIVED.put("inventory.inventory_items.purchasing", 1L);
        ARCHIVED.put("public.event_publication", 1L);
    }

    /**
     * What the purchasing of the previous version left, all of it settled: written with the foreign keys and row
     * triggers off, since the old tables' rules are not what this test is about. The items exist for real. PMG-A carries
     * the purchasing columns, PMG-B is on the blind receipt and PMG-C is named by a production order line only.
     */
    private static final String SEED = """
            INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at)
            VALUES (md5('u')::uuid, 'pm-clerk', 'pm-clerk@example.com', '!x', 'ACTIVE', 0, NOW()),
                   (md5('u2')::uuid, 'pm-boss', 'pm-boss@example.com', '!x', 'ACTIVE', 0, NOW());
            INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, version, created_at)
            VALUES (md5('wh')::uuid, 'Purchasing', 'ACTIVE', 'PMG', 'Somewhere', 'M', 50, 50, 0, NOW());
            INSERT INTO product.products (id, code, name, slug) VALUES (md5('p1')::uuid, 'PMG1', 'p1', 'pmg1'), (md5('p2')::uuid, 'PMG2', 'p2', 'pmg2'), (md5('p3')::uuid, 'PMG3', 'p3', 'pmg3');
            INSERT INTO product.variants (id, product_id, sku, name, attribute_signature) VALUES
             (gen_random_uuid(), md5('p1')::uuid, 'PMG-A', 'A', ''), (gen_random_uuid(), md5('p2')::uuid, 'PMG-B', 'B', ''), (gen_random_uuid(), md5('p3')::uuid, 'PMG-C', 'C', '');
            SET session_replication_role = replica;
            INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
            VALUES (md5('locR')::uuid, md5('wh')::uuid, 'AREA', 'PMG-RCV', 'NORMAL', FALSE, FALSE, 'ACTIVE'),
                   (md5('locS')::uuid, md5('wh')::uuid, 'SUBCONTRACTOR', 'PMG-SUB-1', 'NORMAL', FALSE, FALSE, 'ACTIVE');
            INSERT INTO procurement.suppliers (id, code, name) VALUES (md5('s1')::uuid, 'PMG-SUP', 'Purchasing supplier'), (md5('s2')::uuid, 'PMG-SUB', 'Print subcontractor');
            INSERT INTO procurement.supplier_contacts (id, supplier_id, full_name, email) VALUES (md5('sc')::uuid, md5('s1')::uuid, 'Contact', 'c@example.com');
            INSERT INTO procurement.supplier_addresses (id, supplier_id, type, line1) VALUES (md5('sa')::uuid, md5('s1')::uuid, 'BILLING', '1 Street');
            INSERT INTO procurement.supplier_items (id, supplier_id, inventory_item_id, current_price) SELECT md5('si')::uuid, md5('s1')::uuid, id, 1500 FROM inventory.inventory_items WHERE sku = 'PMG-A';
            INSERT INTO procurement.supplier_item_prices (id, supplier_item_id, unit_price) VALUES (md5('sip')::uuid, md5('si')::uuid, 1500);
            INSERT INTO procurement.approval_limits (id, role_id, max_po_amount, effective_from) SELECT md5('al')::uuid, id, 5000000, CURRENT_DATE FROM identity.app_role WHERE code = 'SALES_MANAGER';
            INSERT INTO warehouse.subcontractor_site (id, warehouse_id, supplier_id, location_id) VALUES (md5('site')::uuid, md5('wh')::uuid, md5('s2')::uuid, md5('locS')::uuid);
            UPDATE inventory.inventory_items SET default_supplier_id = md5('s1')::uuid, reference_purchase_price = 1400, reference_purchase_currency = 'VND', lead_time_days = 7, moq = 10 WHERE sku = 'PMG-A';
            INSERT INTO procurement.replenishment_proposals (id, code, warehouse_id, run_date) VALUES (md5('rp')::uuid, 'RP-PMG-1', md5('wh')::uuid, CURRENT_DATE);
            INSERT INTO procurement.replenishment_proposal_lines (id, proposal_id, inventory_item_id, on_hand_qty, on_order_qty, reserved_qty, reorder_point, suggested_qty) SELECT md5('rpl')::uuid, md5('rp')::uuid, id, 0, 0, 0, 10, 100 FROM inventory.inventory_items WHERE sku = 'PMG-A';
            CREATE FUNCTION pg_temp.po(tag TEXT, st TEXT, typ TEXT DEFAULT 'STANDARD') RETURNS void LANGUAGE plpgsql AS $$
            DECLARE rev UUID := md5(tag || ':rev')::uuid; uid UUID := md5('u')::uuid; u2 UUID := md5('u2')::uuid;
            BEGIN
                INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency, order_date, subtotal, discount_total, tax_total, shipping_fee, total_amount,
                                                         active_revision_id, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by, closed_at, closed_by, close_kind, cancel_reason, po_type, production_order_id, supplier_confirmation_status, auto_approved)
                VALUES (md5(tag)::uuid, tag, st, md5(CASE typ WHEN 'SUBCONTRACT' THEN 's2' ELSE 's1' END)::uuid, md5('wh')::uuid, 'VND', CURRENT_DATE, 0, 0, 0, 0, 0,
                        CASE WHEN st = 'DRAFT' THEN NULL ELSE rev END,
                        CASE WHEN st IN ('DRAFT') THEN NULL ELSE NOW() END, CASE WHEN st IN ('DRAFT') THEN NULL ELSE uid END,
                        CASE WHEN st IN ('APPROVED','CONFIRMED','PARTIALLY_RECEIVED','RECEIVED','CLOSED') THEN NOW() END, CASE WHEN st IN ('APPROVED','CONFIRMED','PARTIALLY_RECEIVED','RECEIVED','CLOSED') THEN u2 END,
                        CASE WHEN st IN ('CONFIRMED','PARTIALLY_RECEIVED','RECEIVED') THEN NOW() END, CASE WHEN st IN ('CONFIRMED','PARTIALLY_RECEIVED','RECEIVED') THEN u2 END,
                        CASE WHEN st = 'CLOSED' THEN NOW() END, CASE WHEN st = 'CLOSED' THEN u2 END, CASE WHEN st = 'CLOSED' THEN 'NORMAL' END,
                        CASE WHEN st = 'CANCELLED' THEN 'changed our mind' END, typ, CASE WHEN typ = 'SUBCONTRACT' THEN md5(tag || ':prod')::uuid END, 'NOT_SENT', FALSE);
                IF st <> 'DRAFT' THEN
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header, changed_by) VALUES (rev, md5(tag)::uuid, 0, 'INITIAL', '{}'::jsonb, uid);
                END IF;
                INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id, uom, ordered_qty, unit_price, tax_rate, discount_rate, line_subtotal, line_discount_amount, line_net, line_tax, line_total, status)
                SELECT md5(tag || ':line')::uuid, md5(tag)::uuid, CASE WHEN st <> 'DRAFT' THEN rev END, 1, i.id, 'EACH', 100, 1500, 0, 0, 150000, 0, 150000, 0, 150000, 'OPEN'
                  FROM inventory.inventory_items i WHERE i.sku = 'PMG-A';
                INSERT INTO procurement.purchase_order_events (id, po_id, action) VALUES (gen_random_uuid(), md5(tag)::uuid, 'CREATED');
            END $$;
            SELECT pg_temp.po('PO-PMG-DRAFT', 'DRAFT');
            SELECT pg_temp.po('PO-PMG-CANCELLED', 'CANCELLED');
            SELECT pg_temp.po('PO-PMG-RECEIVED', 'RECEIVED');
            SELECT pg_temp.po('PO-PMG-CLOSED', 'CLOSED');
            SELECT pg_temp.po('PO-PMG-SUB', 'CLOSED', 'SUBCONTRACT');
            INSERT INTO procurement.purchase_order_attachments (id, po_id, file_name, file_url, uploaded_by) VALUES (md5('att')::uuid, md5('PO-PMG-CLOSED')::uuid, 'quote.pdf', 'documents/po-attachments/quote.pdf', md5('u')::uuid);
            INSERT INTO procurement.purchase_order_approvals (id, po_revision_id, approver_id, decision) VALUES (md5('apr')::uuid, md5('PO-PMG-CLOSED:rev')::uuid, md5('u2')::uuid, 'APPROVED');
            INSERT INTO procurement.subcontract_settlement (po_id, production_order_id, status, currency, unit_price, received_quantity, passed_quantity, failed_quantity, payable_amount, blanks_sent, blanks_used, blanks_returned, blanks_lost, tolerance_percent, tolerance_blanks, excess_loss, deduction_amount, cancellation_fee, net_payable, settled_at)
            VALUES (md5('PO-PMG-SUB')::uuid, md5('PO-PMG-SUB:prod')::uuid, 'FINAL', 'VND', 1000, 100, 100, 0, 100000, 100, 100, 0, 0, 1, 1, 0, 0, 0, 100000, NOW());
            INSERT INTO procurement.po_number_sequence (sequence_date, last_value) VALUES (CURRENT_DATE, 6);
            INSERT INTO procurement.po_delivery_decision (id, purchase_order_id, generation, reason, reconciled, acknowledge_past_due, channel, recipient, actor, requested_at) VALUES (md5('pdd')::uuid, md5('PO-PMG-CLOSED')::uuid, 1, 'late', TRUE, FALSE, 'EMAIL', 'a@b.c', 'u', NOW());
            INSERT INTO notification.po_delivery_control (purchase_order_id, generation) VALUES (md5('PO-PMG-CLOSED')::uuid, 1);
            INSERT INTO procurement.supplier_invoices (id, invoice_number, supplier_id, po_id, status, currency, invoice_date, subtotal, tax_total, total_amount) VALUES (md5('inv')::uuid, 'INV-PMG-1', md5('s1')::uuid, md5('PO-PMG-CLOSED')::uuid, 'DRAFT', 'VND', CURRENT_DATE, 100, 0, 100);
            INSERT INTO procurement.supplier_invoice_lines (id, invoice_id, po_line_id, invoiced_qty, unit_price, tax_rate, line_total) VALUES (md5('invl')::uuid, md5('inv')::uuid, md5('PO-PMG-CLOSED:line')::uuid, 1, 100, 0, 100);
            INSERT INTO procurement.supplier_invoice_variances (id, invoice_id, invoice_line_id, po_id, kind, expected_value, invoiced_value, variance_amount) VALUES (md5('var')::uuid, md5('inv')::uuid, md5('invl')::uuid, md5('PO-PMG-CLOSED')::uuid, 'PRICE', 90, 100, 10);
            INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, status, received_at, received_by, confirmed_at, confirmed_by, closed_at, receipt_type)
            VALUES (md5('r1')::uuid, 'GR-PMG-1', md5('PO-PMG-CLOSED')::uuid, md5('PO-PMG-CLOSED:rev')::uuid, md5('wh')::uuid, 'CLOSED', NOW(), md5('u')::uuid, NOW(), md5('u')::uuid, NOW(), 'PURCHASE');
            INSERT INTO procurement.goods_receipts (id, receipt_number, supplier_id, blind_reason, warehouse_id, status, received_at, received_by, confirmed_at, confirmed_by, approval_requested_at, approval_requested_by, approval_decision, approval_decided_at, approval_decided_by, approval_reason, receipt_type)
            VALUES (md5('r2')::uuid, 'GR-PMG-2', md5('s1')::uuid, 'arrived without a PO', md5('wh')::uuid, 'IN_PUTAWAY', NOW(), md5('u')::uuid, NOW(), md5('u')::uuid, NOW(), md5('u')::uuid, 'APPROVED', NOW(), md5('u2')::uuid, 'known supplier', 'PURCHASE');
            INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty, lot_number, uom_conversion_id)
            SELECT md5('l1')::uuid, md5('r1')::uuid, md5('PO-PMG-CLOSED:line')::uuid, i.id, md5('locR')::uuid, 60, NULL, NULL FROM inventory.inventory_items i WHERE i.sku = 'PMG-A';
            INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty, lot_number, uom_conversion_id)
            SELECT md5('l2')::uuid, md5('r1')::uuid, md5('PO-PMG-CLOSED:line')::uuid, i.id, md5('locR')::uuid, 40, 'LOT-KEEP', u.id FROM inventory.inventory_items i JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id AND u.is_base WHERE i.sku = 'PMG-A';
            INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty, lot_number, uom_conversion_id)
            SELECT md5('l3')::uuid, md5('r2')::uuid, i.id, md5('locR')::uuid, 5, NULL, NULL FROM inventory.inventory_items i WHERE i.sku = 'PMG-B';
            INSERT INTO procurement.goods_receipt_photos (id, receipt_id, receipt_line_id, stage, storage_key, original_name, content_type, size_bytes, checksum, taken_by) VALUES
             (md5('ph-qc')::uuid, md5('r1')::uuid, md5('l1')::uuid, 'QC', 'qc-evidence/2026/10/01/qc.jpg', 'qc.jpg', 'image/jpeg', 1, repeat('0', 64), md5('u')::uuid),
             (md5('ph-rc')::uuid, md5('r1')::uuid, md5('l1')::uuid, 'RECEIPT', 'qc-evidence/2026/10/01/rc.jpg', 'rc.jpg', 'image/jpeg', 1, repeat('0', 64), md5('u')::uuid);
            INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, goods_receipt_id, goods_receipt_number, purchase_order_id, purchase_order_number, supplier_id, supplier_name, warehouse_id)
            VALUES (md5('lot')::uuid, 'PMG-A', 'LOT-KEEP', NOW(), 'GOODS_RECEIPT', md5('r1')::uuid, 'GR-PMG-1', md5('PO-PMG-CLOSED')::uuid, 'PO-PMG-CLOSED', md5('s1')::uuid, 'Purchasing supplier', md5('wh')::uuid);
            INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id, receiving_warehouse_id, need_by_date, status, sample_approval_required, sample_required_by_default, drawn_up_by)
            VALUES (md5('prod')::uuid, 'PRD-20261109-0001', md5('so')::uuid, md5('batch')::uuid, md5('cust')::uuid, md5('producer')::uuid, md5('wh')::uuid, CURRENT_DATE + 30, 'DRAFT', FALSE, FALSE, md5('u')::uuid);
            INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton, design_snapshot_id, print_file_checksum)
            VALUES (md5('prodl')::uuid, md5('prod')::uuid, md5('ol')::uuid, 'PMG-C', 2, 50, md5('snap')::uuid, repeat('d', 64));
            INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date) VALUES
             (md5('e1')::uuid, 'com.stockflow.notification.internal.service.PurchaseOrderNotificationListener.on(com.stockflow.contracts.PurchaseOrderSent)', 'com.stockflow.contracts.PurchaseOrderSent', '{}', NOW(), NULL),
             (md5('e2')::uuid, 'com.stockflow.notification.internal.service.PurchaseOrderNotificationListener.on(com.stockflow.contracts.PurchaseOrderSent)', 'com.stockflow.contracts.PurchaseOrderSent', '{"done":1}', NOW(), NOW()),
             (md5('e3')::uuid, 'com.stockflow.notification.internal.service.OrderNotificationListener.on(com.stockflow.contracts.OrderPlaced)', 'com.stockflow.contracts.OrderPlaced', '{}', NOW(), NULL);
            SET session_replication_role = DEFAULT;
            """;

    /** What a purchase order, a receipt, a settlement or an item may still carry that stops the migration. */
    private static final String OFFENDERS = """
            INSERT INTO product.products (id, code, name, slug) VALUES (md5('p4')::uuid, 'PMG4', 'p4', 'pmg4');
            INSERT INTO product.variants (id, product_id, sku, name, attribute_signature)
            VALUES (gen_random_uuid(), md5('p4')::uuid, 'PMG-NB', 'NB', '');
            SET session_replication_role = replica;
            SELECT pg_temp.po('PO-OPEN-CONF', 'CONFIRMED');
            SELECT pg_temp.po('PO-OPEN-PART', 'PARTIALLY_RECEIVED');
            SELECT pg_temp.po('PO-OPEN-PEND', 'PENDING_APPROVAL');
            SELECT pg_temp.po('PO-OPEN-APPR', 'APPROVED');
            INSERT INTO procurement.goods_receipts (id, receipt_number, supplier_id, blind_reason, warehouse_id, status,
                                                    received_at, received_by, receipt_type)
            VALUES (md5('r3')::uuid, 'GR-OPEN-DRAFT', md5('s1')::uuid, 'no PO yet', md5('wh')::uuid, 'DRAFT', NOW(),
                    md5('u')::uuid, 'PURCHASE');
            SELECT pg_temp.po('PO-OPEN-SUB', 'CLOSED', 'SUBCONTRACT');
            INSERT INTO procurement.subcontract_settlement (po_id, production_order_id, status, currency, unit_price,
                    received_quantity, passed_quantity, failed_quantity, payable_amount, blanks_sent, blanks_used,
                    blanks_returned, blanks_lost, tolerance_percent, tolerance_blanks, excess_loss, deduction_amount,
                    cancellation_fee, net_payable, settled_at)
            VALUES (md5('PO-OPEN-SUB')::uuid, md5('PO-OPEN-SUB:prod')::uuid, 'AWAITING_APPROVAL', 'VND', 1000, 100, 100, 0,
                    100000, 110, 100, 0, 10, 1, 1, 9, 0, 0, 100000, NOW());
            DELETE FROM inventory.uom_conversions
             WHERE inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = 'PMG-NB');
            INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty,
                                                         lot_number)
            SELECT md5('l9')::uuid, md5('r1')::uuid, i.id, md5('locR')::uuid, 3, 'NB-LOT'
              FROM inventory.inventory_items i WHERE i.sku = 'PMG-NB';
            SET session_replication_role = DEFAULT;
            """;

    @Test
    void refusesWhileAnythingSubmittedOrUncountedRemains() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                script(db, SEED);
                script(db, OFFENDERS);

                String all = refusal(postgres);
                assertThat(all).contains("purchasing cannot be removed yet")
                        .contains("PO-OPEN-APPR APPROVED", "PO-OPEN-CONF CONFIRMED", "PO-OPEN-PART PARTIALLY_RECEIVED",
                                "PO-OPEN-PEND PENDING_APPROVAL")
                        .contains("GR-OPEN-DRAFT DRAFT").contains("PO-OPEN-SUB")
                        .contains("of an item without a base unit: 1 (PMG-NB)")
                        .as("a DRAFT order was never submitted, a CLOSED one is settled").doesNotContain("PO-PMG-DRAFT")
                        .doesNotContain("PO-PMG-CLOSED").doesNotContain("PO-PMG-RECEIVED");
                // Nothing was done: the refusal rolled the whole migration back.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'procurement' AND table_name = 'suppliers'")).isEqualTo(1);
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'procurement.suppliers'")).isZero();

                // Each condition on its own: settle one, and it reads "none" while the others still refuse.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "UPDATE procurement.purchase_orders SET status = 'CANCELLED', cancel_reason = 'settled' "
                        + "WHERE po_number LIKE 'PO-OPEN-%' AND po_number <> 'PO-OPEN-SUB'");
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                String afterOrders = refusal(postgres);
                assertThat(afterOrders).contains("not received, closed or cancelled: none")
                        .contains("GR-OPEN-DRAFT DRAFT").contains("PO-OPEN-SUB").contains("(PMG-NB)");

                RawSql.update(db, "UPDATE procurement.goods_receipts SET status = 'CANCELLED' "
                        + "WHERE receipt_number = 'GR-OPEN-DRAFT'");
                String afterReceipt = refusal(postgres);
                assertThat(afterReceipt).contains("of type PURCHASE not yet counted in: none").contains("PO-OPEN-SUB")
                        .contains("(PMG-NB)");

                RawSql.update(db, "DELETE FROM procurement.subcontract_settlement WHERE po_id = md5('PO-OPEN-SUB')::uuid");
                String afterSettlement = refusal(postgres);
                assertThat(afterSettlement).contains("awaiting approval: none")
                        .contains("of an item without a base unit: 1 (PMG-NB)");

                // The last one alone: the item has a base unit again and the migration runs.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                        + "is_base, default_purchase, active, version, created_at) SELECT gen_random_uuid(), id, 'EACH', 1, "
                        + "TRUE, FALSE, TRUE, 0, NOW() FROM inventory.inventory_items WHERE sku = 'PMG-NB'");
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);
                assertThat(RawSql.text(db, "SELECT lot_number || ':' || (uom_conversion_id IS NOT NULL) FROM "
                        + "procurement.goods_receipt_lines WHERE id = md5('l9')::uuid"))
                        .as("a line counted in base units of an item that has one now names it")
                        .isEqualTo("NB-LOT:true");
            }
        }
    }

    @Test
    void upgradeArchivesPurchasingAndDropsIt() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                script(db, SEED);

                Map<String, Long> versionsBefore = RawSql.numbersByText(db,
                        "SELECT code, version FROM identity.app_role");
                long keptGrantsBefore = RawSql.number(db, "SELECT count(*) FROM identity.role_permission rp JOIN "
                        + "identity.permission p ON p.id = rp.permission_id WHERE p.code IN " + inList(KEPT_PERMISSIONS));
                long goneGrants = RawSql.number(db, "SELECT count(*) FROM identity.role_permission rp JOIN "
                        + "identity.permission p ON p.id = rp.permission_id WHERE p.code LIKE 'procurement-%' AND p.code "
                        + "NOT IN " + inList(KEPT_PERMISSIONS));
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code LIKE 'procurement-%'"))
                        .as("the four goods-receipt permissions and the 33 that go").isEqualTo(37);
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code IN "
                        + inList(KEPT_PERMISSIONS))).isEqualTo(4);
                assertThat(RawSql.text(db, "SELECT inventory.item_in_use(id, sku)::text FROM inventory.inventory_items "
                        + "WHERE sku = 'PMG-C'")).as("only a production order line names it: not counted before")
                        .isEqualTo("false");
                String baseUnitA = RawSql.text(db, "SELECT u.id::text FROM inventory.uom_conversions u JOIN "
                        + "inventory.inventory_items i ON i.id = u.inventory_item_id WHERE i.sku = 'PMG-A' AND u.is_base");
                String baseUnitB = RawSql.text(db, "SELECT u.id::text FROM inventory.uom_conversions u JOIN "
                        + "inventory.inventory_items i ON i.id = u.inventory_item_id WHERE i.sku = 'PMG-B' AND u.is_base");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // Archived whole, per source table; the DRAFT order with the rest.
                Map<String, Long> archived = RawSql.numbersByText(db, "SELECT source_table, count(*) FROM "
                        + "platform.legacy_archive WHERE source_table IN " + inList(ARCHIVED.keySet())
                        + " GROUP BY source_table");
                assertThat(archived).containsExactlyInAnyOrderEntriesOf(ARCHIVED);
                assertThat(RawSql.texts(db, "SELECT row_data ->> 'po_number' FROM platform.legacy_archive WHERE "
                        + "source_table = 'procurement.purchase_orders' ORDER BY 1")).containsExactly("PO-PMG-CANCELLED",
                        "PO-PMG-CLOSED", "PO-PMG-DRAFT", "PO-PMG-RECEIVED", "PO-PMG-SUB");
                assertThat(RawSql.text(db, "SELECT row_id::text FROM platform.legacy_archive WHERE source_table = "
                        + "'procurement.subcontract_settlement'")).isEqualTo(RawSql.text(db,
                        "SELECT md5('PO-PMG-SUB')::uuid::text"));
                assertThat(RawSql.text(db, "SELECT row_id::text FROM platform.legacy_archive WHERE source_table = "
                        + "'notification.po_delivery_control'")).isEqualTo(RawSql.text(db,
                        "SELECT md5('PO-PMG-CLOSED')::uuid::text"));
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table IN "
                        + "('procurement.po_number_sequence', 'notification.po_retry_cursor') AND row_id IS NOT NULL"))
                        .as("rows keyed by no single uuid have no row_id").isZero();
                assertThat(RawSql.text(db, "SELECT row_data ->> 'file_url' FROM platform.legacy_archive WHERE source_table "
                        + "= 'procurement.purchase_order_attachments'")).isEqualTo("documents/po-attachments/quote.pdf");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'storage_key' FROM platform.legacy_archive WHERE "
                        + "source_table = 'procurement.goods_receipt_photos'")).isEqualTo("qc-evidence/2026/10/01/qc.jpg");
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'default_supplier_id') || ':' || (row_data ->> 'moq') || "
                        + "':' || (row_data ->> 'sku') FROM platform.legacy_archive WHERE source_table = "
                        + "'inventory.inventory_items.purchasing'"))
                        .isEqualTo(RawSql.text(db, "SELECT md5('s1')::uuid::text") + ":10:PMG-A");
                assertThat(RawSql.texts(db, "SELECT COALESCE(row_data ->> 'lot_number', '-') FROM platform.legacy_archive "
                        + "WHERE source_table = 'procurement.goods_receipt_lines.purchase' ORDER BY 1"))
                        .as("the lines as they were: two had no lot").containsExactly("-", "-", "LOT-KEEP");
                assertThat(RawSql.texts(db, "SELECT ((row_data ->> 'po_id') IS NULL)::text || ':' || "
                        + "((row_data ->> 'blind_reason') IS NOT NULL)::text FROM platform.legacy_archive WHERE source_table = "
                        + "'procurement.goods_receipts.purchase' ORDER BY row_data ->> 'receipt_number'"))
                        .containsExactly("false:false", "true:true");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'identity.permission' AND row_data ->> 'code' LIKE 'procurement-%'")).isEqualTo(33);
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'identity.permission' AND row_data ->> 'code' IN " + inList(KEPT_PERMISSIONS))).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive a JOIN "
                        + "platform.legacy_archive p ON p.source_table = 'identity.permission' AND p.row_id::text = "
                        + "a.row_data ->> 'permission_id' WHERE a.source_table = 'identity.role_permission' AND "
                        + "p.row_data ->> 'code' LIKE 'procurement-%'")).isEqualTo(goneGrants);
                assertThat(RawSql.text(db, "SELECT obj_description('platform.legacy_archive'::regclass, 'pg_class')"))
                        .contains("purchasing").contains("V20261113000200");

                // The tables, columns, functions and triggers are gone; receiving keeps its three tables.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'procurement' AND table_name IN " + inList(DROPPED_PROCUREMENT_TABLES))).isZero();
                assertThat(RawSql.texts(db, "SELECT table_name FROM information_schema.tables WHERE table_schema = "
                        + "'procurement' AND table_type = 'BASE TABLE' ORDER BY 1"))
                        .containsExactly("goods_receipt_lines", "goods_receipt_photos", "goods_receipts");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE (table_schema, "
                        + "table_name) IN (('notification', 'po_delivery_control'), ('notification', 'po_retry_cursor'), "
                        + "('warehouse', 'subcontractor_site'))")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE (table_schema, "
                        + "table_name, column_name) IN (('procurement', 'goods_receipts', 'po_id'), ('procurement', "
                        + "'goods_receipts', 'po_revision_id'), ('procurement', 'goods_receipts', 'supplier_id'), "
                        + "('procurement', 'goods_receipts', 'blind_reason'), ('procurement', 'goods_receipts', "
                        + "'referenced_po_id'), ('procurement', 'goods_receipts', 'approval_requested_at'), "
                        + "('procurement', 'goods_receipts', 'approval_requested_by'), ('procurement', 'goods_receipts', "
                        + "'approval_decision'), ('procurement', 'goods_receipts', 'approval_decided_at'), "
                        + "('procurement', 'goods_receipts', 'approval_decided_by'), ('procurement', 'goods_receipts', "
                        + "'approval_reason'), ('procurement', 'goods_receipt_lines', 'po_line_id'), ('inventory', "
                        + "'inventory_items', 'default_supplier_id'), ('inventory', 'inventory_items', "
                        + "'reference_purchase_price'), ('inventory', 'inventory_items', 'reference_purchase_currency'), "
                        + "('inventory', 'inventory_items', 'lead_time_days'), ('inventory', 'inventory_items', 'moq'))"))
                        .isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE proname IN "
                        + inList(DROPPED_FUNCTIONS))).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_trigger WHERE tgname IN "
                        + "('tg_goods_receipts_tolerance', 'tg_goods_receipts_tolerance_insert') AND NOT tgisinternal"))
                        .isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_trigger WHERE tgname = 'tg_goods_receipts_check' "
                        + "AND pg_get_triggerdef(oid) NOT LIKE '%po_id%' AND pg_get_triggerdef(oid) LIKE "
                        + "'%production_order_id%'")).as("its column list names no dropped column").isEqualTo(1);

                // A stock lot keeps its snapshot of where its goods came from; only the references went.
                assertThat(RawSql.text(db, "SELECT purchase_order_number || ':' || supplier_name FROM inventory.stock_lot "
                        + "WHERE id = md5('lot')::uuid")).isEqualTo("PO-PMG-CLOSED:Purchasing supplier");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname IN "
                        + "('fk_stock_lot_purchase_order', 'fk_stock_lot_supplier', 'fk_goods_receipts_po', "
                        + "'fk_inventory_items_default_supplier')")).isZero();

                // The lines: the item's base unit and the receipt's number where they had none; the rest untouched.
                assertThat(RawSql.texts(db, "SELECT lot_number || ':' || uom_conversion_id || ':' || version FROM "
                        + "procurement.goods_receipt_lines WHERE id IN (md5('l1')::uuid, md5('l3')::uuid) ORDER BY "
                        + "lot_number")).containsExactly("GR-PMG-1:" + baseUnitA + ":1", "GR-PMG-2:" + baseUnitB + ":1");
                assertThat(RawSql.text(db, "SELECT lot_number || ':' || uom_conversion_id || ':' || version FROM "
                        + "procurement.goods_receipt_lines WHERE id = md5('l2')::uuid"))
                        .as("a line that had both is not rewritten").isEqualTo("LOT-KEEP:" + baseUnitA + ":0");
                assertThat(RawSql.texts(db, "SELECT column_name || ':' || is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'procurement' AND table_name = 'goods_receipt_lines' AND column_name IN "
                        + "('lot_number', 'uom_conversion_id') ORDER BY 1")).containsExactly("lot_number:NO",
                        "uom_conversion_id:NO");
                assertThat(RawSql.text(db, "SELECT column_default FROM information_schema.columns WHERE table_schema = "
                        + "'procurement' AND table_name = 'goods_receipts' AND column_name = 'receipt_type'"))
                        .as("a receipt says what it is against: no default").isNull();

                // Statuses and stages; the QC photo is gone, the RECEIPT one stays.
                refusedWith(db, "ck_goods_receipts_status", "UPDATE procurement.goods_receipts SET status = "
                        + "'PENDING_APPROVAL' WHERE id = md5('r2')::uuid");
                refusedWith(db, "ck_goods_receipt_photos_stage", "INSERT INTO procurement.goods_receipt_photos (id, "
                        + "receipt_id, receipt_line_id, stage, storage_key, original_name, content_type, size_bytes, "
                        + "checksum, taken_by) VALUES (gen_random_uuid(), md5('r1')::uuid, md5('l1')::uuid, 'QC', "
                        + "'qc-evidence/2026/11/01/z.jpg', 'z.jpg', 'image/jpeg', 1, repeat('0', 64), md5('u')::uuid)");
                assertThat(RawSql.texts(db, "SELECT stage FROM procurement.goods_receipt_photos")).containsExactly("RECEIPT");
                assertThat(RawSql.text(db, "SELECT storage_key FROM procurement.goods_receipt_photos"))
                        .isEqualTo("qc-evidence/2026/10/01/rc.jpg");

                // No purchase receipt is started any more; the old ones keep moving to their end.
                refusedWith(db, "purchasing was removed", "INSERT INTO procurement.goods_receipts (id, receipt_number, "
                        + "receipt_type, warehouse_id, status, received_at, received_by) VALUES "
                        + "(gen_random_uuid(), 'GR-PMG-NEW', 'PURCHASE', md5('wh')::uuid, 'DRAFT', NOW(), "
                        + "md5('u')::uuid)");
                assertThat(RawSql.update(db, "UPDATE procurement.goods_receipts SET status = 'CLOSED', closed_at = NOW() "
                        + "WHERE id = md5('r2')::uuid")).as("an old receipt still closes").isEqualTo(1);

                // item_in_use counts production order lines (it did not before); unit_in_use runs without PO lines.
                assertThat(RawSql.text(db, "SELECT inventory.item_in_use(id, sku)::text FROM inventory.inventory_items "
                        + "WHERE sku = 'PMG-C'")).isEqualTo("true");
                RawSql.update(db, "INSERT INTO product.products (id, code, name, slug) VALUES (md5('p5')::uuid, 'PMG5', "
                        + "'p5', 'pmg5')");
                RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, attribute_signature) VALUES "
                        + "(gen_random_uuid(), md5('p5')::uuid, 'PMG-D', 'D', '')");
                assertThat(RawSql.text(db, "SELECT inventory.item_in_use(id, sku)::text FROM inventory.inventory_items "
                        + "WHERE sku = 'PMG-D'")).isEqualTo("false");
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, "
                        + "sku, cartons, units_per_carton, design_snapshot_id, print_file_checksum) VALUES "
                        + "(gen_random_uuid(), md5('prod')::uuid, md5('ol')::uuid, 'PMG-D', 1, 50, md5('snap')::uuid, "
                        + "repeat('d', 64))");
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                assertThat(RawSql.text(db, "SELECT inventory.item_in_use(id, sku)::text FROM inventory.inventory_items "
                        + "WHERE sku = 'PMG-D'")).as("a production order line makes the SKU immutable").isEqualTo("true");
                assertThat(RawSql.text(db, "SELECT inventory.unit_in_use(?::uuid)::text", baseUnitA))
                        .as("a receipt line names it").isEqualTo("true");
                assertThat(RawSql.text(db, "SELECT inventory.unit_in_use(u.id)::text FROM inventory.uom_conversions u "
                        + "JOIN inventory.inventory_items i ON i.id = u.inventory_item_id WHERE i.sku = 'PMG-D' AND "
                        + "u.is_base")).isEqualTo("false");

                // Creating a storage location runs warehouse.check_storage_location_owned at every commit: it reads
                // tables that exist, accepts an area, and refuses a location of the subcontractor kind that nothing owns.
                db.setAutoCommit(false);
                try {
                    RawSql.update(db, "INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, "
                            + "storage_class, is_pickable, is_putaway_target, status) VALUES (md5('locO')::uuid, "
                            + "md5('wh')::uuid, 'AREA', 'PMG-OVF', 'NORMAL', FALSE, FALSE, 'ACTIVE')");
                    RawSql.update(db, "INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, "
                            + "width, length, rotation, is_obstacle, status) VALUES (md5('areaO')::uuid, md5('wh')::uuid, "
                            + "md5('locO')::uuid, 'OVF', 'OVERFLOW', 'Overflow', 0, 0, 5, 5, 0, FALSE, 'ACTIVE')");
                    db.commit();
                } finally {
                    db.setAutoCommit(true);
                }
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.area WHERE id = md5('areaO')::uuid"))
                        .isEqualTo(1);
                refusedWith(db, "owned by no bin and no area", "INSERT INTO warehouse.storage_location (id, "
                        + "warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status) "
                        + "VALUES (gen_random_uuid(), md5('wh')::uuid, 'SUBCONTRACTOR', 'PMG-SUB-2', 'NORMAL', FALSE, "
                        + "FALSE, 'ACTIVE')");
                refusedWith(db, "owned by no bin and no area", "INSERT INTO warehouse.storage_location (id, "
                        + "warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status) "
                        + "VALUES (gen_random_uuid(), md5('wh')::uuid, 'AREA', 'PMG-LOST', 'NORMAL', FALSE, FALSE, "
                        + "'ACTIVE')");
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.storage_location WHERE location_code = "
                        + "'PMG-SUB-1'")).as("the subcontractor location there was stays").isEqualTo(1);

                // Event publications nobody can deliver any more are archived and gone; the others stay.
                assertThat(RawSql.texts(db, "SELECT event_type || ':' || (completion_date IS NOT NULL) FROM "
                        + "event_publication WHERE id IN (md5('e1')::uuid, md5('e2')::uuid, md5('e3')::uuid) ORDER BY 1"))
                        .containsExactly("com.stockflow.contracts.OrderPlaced:false",
                                "com.stockflow.contracts.PurchaseOrderSent:true");
                assertThat(RawSql.text(db, "SELECT row_id::text FROM platform.legacy_archive WHERE source_table = "
                        + "'public.event_publication'")).isEqualTo(RawSql.text(db, "SELECT md5('e1')::uuid::text"));

                // Permissions: only the four goods-receipt ones are left, their grants untouched, and exactly the roles
                // that lost a grant moved on to a new version.
                assertThat(RawSql.texts(db, "SELECT code FROM identity.permission WHERE code LIKE 'procurement-%' "
                        + "ORDER BY 1")).containsExactlyElementsOf(KEPT_PERMISSIONS);
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.role_permission rp JOIN identity.permission p "
                        + "ON p.id = rp.permission_id WHERE p.code IN " + inList(KEPT_PERMISSIONS)))
                        .isEqualTo(keptGrantsBefore);
                Map<String, Long> versionsAfter = RawSql.numbersByText(db, "SELECT code, version FROM identity.app_role");
                versionsBefore.forEach((role, version) -> assertThat(versionsAfter.get(role)).as("version of %s", role)
                        .isEqualTo(LOSING_ROLES.contains(role) ? version + 1 : version));
            }
        }
    }

    /** The database refuses the statement as an integrity violation whose message names {@code fragment}. */
    private static void refusedWith(Connection db, String fragment, String sql) {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> RawSql.update(db, sql));
        assertThat((Object) refusal).as("%s is refused, but the database accepted: %s", fragment, sql).isNotNull();
        assertThat(refusal.getSQLState()).as(refusal.getMessage()).startsWith("23");
        assertThat(refusal.getMessage()).contains(fragment);
    }

    /** The message with which the migration refuses to run. */
    private static String refusal(PostgreSQLContainer<?> postgres) {
        var thrown = catchThrowableOfType(Exception.class, () -> flyway(postgres, MIGRATION).migrate());
        assertThat(thrown).as("the migration refuses").isNotNull();
        // The cause chain carries the database's message; the exception itself repeats it.
        return thrown.getMessage();
    }

    private static String inList(Iterable<String> values) {
        StringBuilder list = new StringBuilder("(");
        for (String value : values) {
            list.append(list.length() > 1 ? ", " : "").append('\'').append(value).append('\'');
        }
        return list.append(')').toString();
    }

    private static void script(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres) {
        return flyway(postgres, BEFORE);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
