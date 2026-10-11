-- =============================================================================
-- Adversarial checks + the happy path: purchasing, receiving, QC, invoicing (docs 02-04).
-- Needs the demo seed. Rolled back at the end. Run: tools/db/qa/run.sh <database>
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;

-- Inserts a warehouse whether or not the pre-C2 column "code" still exists (C2 drops it).
CREATE FUNCTION pg_temp.qa_warehouse(wid UUID, wprefix TEXT, wunit TEXT, w NUMERIC, h NUMERIC) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'warehouse' AND table_name = 'warehouse' AND column_name = 'code') THEN
        EXECUTE 'INSERT INTO warehouse.warehouse (id, code, name, status, prefix, address, map_unit, map_width, map_height, created_at)
                 VALUES ($1, $2, $2, ''ACTIVE'', $2, ''QA'', $3, $4, $5, NOW())' USING wid, wprefix, wunit, w, h;
    ELSE
        EXECUTE 'INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, created_at)
                 VALUES ($1, $2, ''ACTIVE'', $2, ''QA'', $3, $4, $5, NOW())' USING wid, wprefix, wunit, w, h;
    END IF;
END $$;

CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'ok    %  [%]', label, left(SQLERRM, 110);
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    RAISE NOTICE 'ok    %', label;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

-- Shorthands for the demo ids.
CREATE FUNCTION pg_temp.id(name TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS $$ SELECT md5(name)::uuid $$;

INSERT INTO procurement.suppliers (id, code, name) VALUES (md5('qa:supplier:OTHER')::uuid, 'OTHER', 'NCC khác');
SELECT pg_temp.qa_warehouse(md5('qa:wh:HN')::uuid, 'HN', 'M', 30, 20);
INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
VALUES (md5('qa:loc:HN-RCV')::uuid, md5('qa:wh:HN')::uuid, 'AREA', 'HN-RCV01', 'NORMAL', FALSE, FALSE, 'ACTIVE');
INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
VALUES (gen_random_uuid(), md5('qa:wh:HN')::uuid, md5('qa:loc:HN-RCV')::uuid, 'RCV01', 'RECEIVING', 'x', 1, 1, 5, 5, 0, FALSE, 'ACTIVE');

\echo '--- purchase order: header rules'
SELECT pg_temp.expect_fail('R1 PO total that does not add up', $q$
    UPDATE procurement.purchase_orders SET total_amount = 1 WHERE po_number = 'PO-HCM-DEMO-0001' $q$);
SELECT pg_temp.expect_fail('R2 PO line with line_total <> net + tax', $q$
    UPDATE procurement.purchase_order_lines SET line_total = 1 WHERE po_id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_fail('R3 ordered quantity 0', $q$
    UPDATE procurement.purchase_order_lines SET ordered_qty = 0 WHERE po_id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_fail('R4 tax rate 150%', $q$
    UPDATE procurement.purchase_order_lines SET tax_rate = 150 WHERE po_id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_fail('R5 submitted PO without any revision', $q$
    UPDATE procurement.purchase_orders SET status = 'PENDING_APPROVAL', submitted_by = md5('demo:user:editor')::uuid,
        submitted_at = NOW() WHERE po_number = 'PO-HCM-DEMO-0001' $q$);
SELECT pg_temp.expect_fail('R6 PO number changed', $q$
    UPDATE procurement.purchase_orders SET po_number = 'PO-X' WHERE po_number = 'PO-HCM-DEMO-0001' $q$);
-- A second PO, still DRAFT, with its initial revision: the "other PO" of the checks below.
INSERT INTO procurement.purchase_orders (id, po_number, supplier_id, warehouse_id, currency, order_date)
VALUES (md5('qa:po:2')::uuid, 'PO-QA-2', md5('demo:supplier:GOVIET')::uuid, md5('demo:wh:HCM')::uuid, 'VND', CURRENT_DATE);
INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, snapshot_header, changed_by)
VALUES (md5('qa:po:2:rev0')::uuid, md5('qa:po:2')::uuid, 0, '{}', md5('demo:user:editor')::uuid);
INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price, line_subtotal, line_net, line_total)
VALUES (md5('qa:po:2:line:1')::uuid, md5('qa:po:2')::uuid, 1, md5('demo:item:SOFA-3S-GREY')::uuid, 1, 1, 1, 1, 1);

SELECT pg_temp.expect_fail('R7 revision of another PO set as active', $q$
    UPDATE procurement.purchase_orders SET active_revision_id = md5('qa:po:2:rev0')::uuid WHERE id = md5('demo:po:1')::uuid $q$);

\echo '--- purchase order: submit -> approve -> confirm'
SELECT pg_temp.expect_ok('R8 submit: initial revision + PENDING_APPROVAL', $q$
    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header, changed_by)
    VALUES (md5('qa:po:1:rev0')::uuid, md5('demo:po:1')::uuid, 0, 'INITIAL', '{"total": 55000000}', md5('demo:user:editor')::uuid);
    UPDATE procurement.purchase_order_lines SET po_revision_id = md5('qa:po:1:rev0')::uuid WHERE po_id = md5('demo:po:1')::uuid;
    INSERT INTO procurement.purchase_order_line_revisions (id, po_line_id, po_revision_id, inventory_item_id, uom, ordered_qty,
        unit_price, tax_rate, discount_rate, line_subtotal, line_net, line_tax, line_total)
    SELECT gen_random_uuid(), l.id, l.po_revision_id, l.inventory_item_id, l.uom, l.ordered_qty, l.unit_price, l.tax_rate,
           l.discount_rate, l.line_subtotal, l.line_net, l.line_tax, l.line_total
      FROM procurement.purchase_order_lines l WHERE l.po_id = md5('demo:po:1')::uuid;
    UPDATE procurement.purchase_orders SET status = 'PENDING_APPROVAL', pending_revision_id = md5('qa:po:1:rev0')::uuid,
        submitted_by = md5('demo:user:editor')::uuid, submitted_at = NOW()
     WHERE id = md5('demo:po:1')::uuid;
    INSERT INTO procurement.purchase_order_events (id, po_id, po_revision_id, action, actor_id, from_status, to_status)
    VALUES (gen_random_uuid(), md5('demo:po:1')::uuid, md5('qa:po:1:rev0')::uuid, 'SUBMITTED', md5('demo:user:editor')::uuid, 'DRAFT', 'PENDING_APPROVAL') $q$);
SELECT pg_temp.expect_fail('R9 approved by the person who submitted (four eyes)', $q$
    UPDATE procurement.purchase_orders SET status = 'APPROVED', approved_by = md5('demo:user:editor')::uuid, approved_at = NOW(),
        active_revision_id = pending_revision_id, pending_revision_id = NULL WHERE id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_fail('R10 APPROVED without approver', $q$
    UPDATE procurement.purchase_orders SET status = 'APPROVED' WHERE id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_ok('R11 approve + confirm by the right people', $q$
    INSERT INTO procurement.purchase_order_approvals (id, po_revision_id, approver_id, decision)
    VALUES (gen_random_uuid(), md5('qa:po:1:rev0')::uuid, md5('demo:user:approver')::uuid, 'APPROVED');
    UPDATE procurement.purchase_orders SET status = 'APPROVED', approved_by = md5('demo:user:approver')::uuid, approved_at = NOW(),
        active_revision_id = pending_revision_id, pending_revision_id = NULL WHERE id = md5('demo:po:1')::uuid;
    UPDATE procurement.purchase_orders SET status = 'CONFIRMED', confirmed_by = md5('demo:user:approver')::uuid, confirmed_at = NOW()
     WHERE id = md5('demo:po:1')::uuid $q$);
-- A second line, lot/expiry-tracked item, for the lot checks below.
INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price, line_subtotal, line_net, line_total, po_revision_id)
VALUES (md5('qa:po:1:line:2')::uuid, md5('demo:po:1')::uuid, 2, md5('demo:item:TABLE-OAK-160')::uuid, 5, 3500000, 17500000, 17500000, 17500000, md5('qa:po:1:rev0')::uuid);

SELECT pg_temp.expect_fail('R12 revision edited after the fact (append-only)', $q$
    UPDATE procurement.purchase_order_revisions SET snapshot_header = '{"total": 1}' WHERE id = md5('qa:po:1:rev0')::uuid $q$);
SELECT pg_temp.expect_fail('R13 event deleted (append-only)', $q$
    DELETE FROM procurement.purchase_order_events WHERE po_id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_fail('R14 approval deleted (append-only)', $q$
    DELETE FROM procurement.purchase_order_approvals WHERE po_revision_id = md5('qa:po:1:rev0')::uuid $q$);
SELECT pg_temp.expect_fail('R15 rejection without a reason', $q$
    INSERT INTO procurement.purchase_order_approvals (id, po_revision_id, step_no, approver_id, decision)
    VALUES (gen_random_uuid(), md5('qa:po:1:rev0')::uuid, 2, md5('demo:user:approver')::uuid, 'REJECTED') $q$);
SELECT pg_temp.expect_fail('R16 CLOSED without close kind', $q$
    UPDATE procurement.purchase_orders SET status = 'CLOSED', closed_by = md5('demo:user:approver')::uuid, closed_at = NOW()
     WHERE id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_fail('R17 short close without a reason', $q$
    UPDATE procurement.purchase_orders SET close_kind = 'SHORT_CLOSE' WHERE id = md5('demo:po:1')::uuid $q$);

\echo '--- receiving (docs 03) and QC'
SELECT pg_temp.expect_fail('R18 receipt on a PO that is still DRAFT', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, received_at, received_by)
    VALUES (gen_random_uuid(), 'GR-QA-X', md5('qa:po:2')::uuid, md5('qa:po:2:rev0')::uuid, md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('R18b receipt against a revision of another PO', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, received_at, received_by)
    VALUES (gen_random_uuid(), 'GR-QA-Z', md5('demo:po:1')::uuid, md5('qa:po:2:rev0')::uuid, md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('R19 receipt into another warehouse than the PO', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, received_at, received_by)
    VALUES (gen_random_uuid(), 'GR-QA-Y', md5('demo:po:1')::uuid, md5('qa:po:1:rev0')::uuid, md5('qa:wh:HN')::uuid, NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('R20 receipt header on the confirmed PO', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, received_at, received_by)
    VALUES (md5('qa:gr:1')::uuid, 'GR-QA-1', md5('demo:po:1')::uuid, md5('qa:po:1:rev0')::uuid, md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('R21 receiving a different item than ordered', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty, lot_number, expiry_date)
    VALUES (gen_random_uuid(), md5('qa:gr:1')::uuid, md5('demo:po:1:line:1')::uuid, md5('demo:item:TABLE-OAK-160')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 1, 'L1', '2030-01-01') $q$);
SELECT pg_temp.expect_fail('R22 receiving into a location of another warehouse', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty)
    VALUES (gen_random_uuid(), md5('qa:gr:1')::uuid, md5('demo:po:1:line:1')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('qa:loc:HN-RCV')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('R23 receiving 11 of 10 at 5% tolerance (BR-04)', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty)
    VALUES (gen_random_uuid(), md5('qa:gr:1')::uuid, md5('demo:po:1:line:1')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 11) $q$);
-- Received under the 3-step flow (qc_required snapshot), so QC below has goods to decide on.
SELECT pg_temp.expect_ok('R24 receiving 10.5 of 10 (exactly at tolerance)', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty,
                                                 qc_required)
    VALUES (md5('qa:grl:1')::uuid, md5('qa:gr:1')::uuid, md5('demo:po:1:line:1')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 10.5, TRUE) $q$);
SELECT pg_temp.expect_fail('R25 a second receipt pushing the total past tolerance', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, received_at, received_by)
    VALUES (md5('qa:gr:2')::uuid, 'GR-QA-2', md5('demo:po:1')::uuid, md5('qa:po:1:rev0')::uuid, md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid);
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty)
    VALUES (gen_random_uuid(), md5('qa:gr:2')::uuid, md5('demo:po:1:line:1')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 0.5) $q$);
SELECT pg_temp.expect_fail('R26 the same PO line and lot twice on one receipt', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty, lot_number, expiry_date)
    VALUES (gen_random_uuid(), md5('qa:gr:1')::uuid, md5('qa:po:1:line:2')::uuid, md5('demo:item:TABLE-OAK-160')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 1, 'LOT-QA', '2030-01-01');
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty, lot_number, expiry_date)
    VALUES (gen_random_uuid(), md5('qa:gr:1')::uuid, md5('qa:po:1:line:2')::uuid, md5('demo:item:TABLE-OAK-160')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 1, 'LOT-QA', '2030-01-01') $q$);
SELECT pg_temp.expect_fail('R27 CONFIRMED receipt without who/when', $q$
    UPDATE procurement.goods_receipts SET status = 'CONFIRMED' WHERE id = md5('qa:gr:1')::uuid $q$);
SELECT pg_temp.expect_ok('R27b receipt confirmed, the line moved to the QC area (BR-08 precondition)', $q$
    UPDATE procurement.goods_receipts SET status = 'IN_QC', confirmed_by = md5('demo:user:approver')::uuid,
        confirmed_at = NOW() WHERE id = md5('qa:gr:1')::uuid;
    UPDATE procurement.goods_receipt_lines SET qc_location_id = md5('demo:loc:HCM-QCA01')::uuid, moved_to_qc_at = NOW(),
        moved_to_qc_by = md5('demo:user:editor')::uuid WHERE id = md5('qa:grl:1')::uuid $q$);
SELECT pg_temp.expect_fail('R28 QC inspects more than received', $q$
    INSERT INTO procurement.qc_inspections (id, receipt_line_id, outcome, quantity, inspected_by)
    VALUES (gen_random_uuid(), md5('qa:grl:1')::uuid, 'ACCEPTED', 11, md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('R29 quarantine without a location', $q$
    INSERT INTO procurement.qc_inspections (id, receipt_line_id, outcome, quantity, reason, inspected_by)
    VALUES (gen_random_uuid(), md5('qa:grl:1')::uuid, 'QUARANTINE', 1, 'scratch', md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('R30 rejection without a reason', $q$
    INSERT INTO procurement.qc_inspections (id, receipt_line_id, outcome, quantity, inspected_by)
    VALUES (gen_random_uuid(), md5('qa:grl:1')::uuid, 'REJECTED', 1, md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('R31 QC: 8.5 accepted + 2 quarantined at QC01', $q$
    INSERT INTO procurement.qc_inspections (id, receipt_line_id, outcome, quantity, inspected_by)
    VALUES (gen_random_uuid(), md5('qa:grl:1')::uuid, 'ACCEPTED', 8.5, md5('demo:user:editor')::uuid);
    INSERT INTO procurement.qc_inspections (id, receipt_line_id, outcome, quantity, target_location_id, reason, inspected_by)
    VALUES (gen_random_uuid(), md5('qa:grl:1')::uuid, 'QUARANTINE', 2, md5('demo:loc:HCM-QC01')::uuid, 'Trầy xước', md5('demo:user:editor')::uuid);
    UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY' WHERE id = md5('qa:gr:1')::uuid $q$);
SELECT pg_temp.expect_ok('R32 putaway task sourced from the receipt line', $q$
    INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, target_location_id, status, created_at)
    VALUES (gen_random_uuid(), md5('qa:gr:1')::uuid, md5('qa:grl:1')::uuid, 'SOFA-3S-GREY', 8, NULL, 'PENDING', NOW()) $q$);

\echo '--- item data required before receiving (docs 01 BR-03)'
-- GR-QA-1 is past DRAFT now and its lines are frozen (BR-05); the checks below count into a new draft.
SELECT pg_temp.expect_ok('R32b a second, draft receipt with one 2-step line', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, received_at, received_by)
    VALUES (md5('qa:gr:3')::uuid, 'GR-QA-3', md5('demo:po:1')::uuid, md5('qa:po:1:rev0')::uuid, md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid);
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty, lot_number, expiry_date)
    VALUES (md5('qa:grl:3')::uuid, md5('qa:gr:3')::uuid, md5('qa:po:1:line:2')::uuid, md5('demo:item:TABLE-OAK-160')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 1, 'LOT-QA3', '2030-01-01') $q$);
SELECT pg_temp.expect_fail('R33 lot-tracked item received without a lot', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty)
    VALUES (gen_random_uuid(), md5('qa:gr:3')::uuid, md5('qa:po:1:line:2')::uuid, md5('demo:item:TABLE-OAK-160')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('R33b expiry-tracked item received without an expiry date', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty, lot_number)
    VALUES (gen_random_uuid(), md5('qa:gr:3')::uuid, md5('qa:po:1:line:2')::uuid, md5('demo:item:TABLE-OAK-160')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 1, 'LOT-QA') $q$);
SELECT pg_temp.expect_fail('R34 item without weight/dimensions received', $q$
    UPDATE inventory.inventory_items SET weight_kg = NULL WHERE sku = 'SOFA-3S-GREY';
    UPDATE procurement.goods_receipt_lines SET note = 'recheck' WHERE id = md5('qa:grl:1')::uuid $q$);

\echo '--- invoicing (docs 04)'
SELECT pg_temp.expect_fail('R35 invoice for the PO from another supplier', $q$
    INSERT INTO procurement.supplier_invoices (id, invoice_number, supplier_id, po_id, invoice_date)
    VALUES (gen_random_uuid(), 'INV-1', md5('qa:supplier:OTHER')::uuid, md5('demo:po:1')::uuid, CURRENT_DATE) $q$);
SELECT pg_temp.expect_ok('R36 invoice from the PO supplier, one line', $q$
    INSERT INTO procurement.supplier_invoices (id, invoice_number, supplier_id, po_id, invoice_date, subtotal, tax_total, total_amount)
    VALUES (md5('qa:inv:1')::uuid, 'INV-1', md5('demo:supplier:GOVIET')::uuid, md5('demo:po:1')::uuid, CURRENT_DATE, 50000000, 5000000, 55000000);
    INSERT INTO procurement.supplier_invoice_lines (id, invoice_id, po_line_id, receipt_line_id, invoiced_qty, unit_price, tax_rate, line_total)
    VALUES (gen_random_uuid(), md5('qa:inv:1')::uuid, md5('demo:po:1:line:1')::uuid, md5('qa:grl:1')::uuid, 10, 5000000, 10, 55000000) $q$);
SELECT pg_temp.expect_fail('R37 the same supplier invoice number twice', $q$
    INSERT INTO procurement.supplier_invoices (id, invoice_number, supplier_id, invoice_date)
    VALUES (gen_random_uuid(), 'INV-1', md5('demo:supplier:GOVIET')::uuid, CURRENT_DATE) $q$);
SELECT pg_temp.expect_fail('R38 invoice line billing a line of another PO', $q$
    INSERT INTO procurement.supplier_invoice_lines (id, invoice_id, po_line_id, invoiced_qty, unit_price)
    VALUES (gen_random_uuid(), md5('qa:inv:1')::uuid, md5('qa:po:2:line:1')::uuid, 1, 1) $q$);
SELECT pg_temp.expect_fail('R38b invoice line citing a receipt of another PO line', $q$
    INSERT INTO procurement.supplier_invoice_lines (id, invoice_id, po_line_id, receipt_line_id, invoiced_qty, unit_price)
    VALUES (gen_random_uuid(), md5('qa:inv:1')::uuid, md5('qa:po:1:line:2')::uuid, md5('qa:grl:1')::uuid, 1, 1) $q$);
SELECT pg_temp.expect_fail('R39 invoice total <> subtotal + tax', $q$
    UPDATE procurement.supplier_invoices SET total_amount = 1 WHERE id = md5('qa:inv:1')::uuid $q$);
SELECT pg_temp.expect_fail('R40 due date before invoice date', $q$
    UPDATE procurement.supplier_invoices SET due_date = invoice_date - 1 WHERE id = md5('qa:inv:1')::uuid $q$);

\echo '--- supplier master, price lists, proposals, approval limits'
SELECT pg_temp.expect_fail('R41 overlapping price periods for one supplier item', $q$
    INSERT INTO procurement.supplier_item_prices (id, supplier_item_id, unit_price, effective_from)
    VALUES (gen_random_uuid(), md5('demo:si:GOVIET:SOFA')::uuid, 5200000, '2026-10-01T00:00:00Z') $q$);
SELECT pg_temp.expect_ok('R42 closing the old period, then the new price', $q$
    UPDATE procurement.supplier_item_prices SET effective_to = '2026-10-01T00:00:00Z' WHERE id = md5('demo:sip:SOFA')::uuid;
    INSERT INTO procurement.supplier_item_prices (id, supplier_item_id, unit_price, effective_from)
    VALUES (gen_random_uuid(), md5('demo:si:GOVIET:SOFA')::uuid, 5200000, '2026-10-01T00:00:00Z') $q$);
SELECT pg_temp.expect_fail('R43 two preferred suppliers for one item', $q$
    INSERT INTO procurement.supplier_items (id, supplier_id, inventory_item_id, current_price, is_preferred)
    VALUES (gen_random_uuid(), md5('qa:supplier:OTHER')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 1, TRUE) $q$);
SELECT pg_temp.expect_fail('R44 two suppliers with one tax id', $q$
    INSERT INTO procurement.suppliers (id, code, name, tax_id) VALUES (gen_random_uuid(), 'DUP', 'x', '0300000001') $q$);
SELECT pg_temp.expect_fail('R45 proposal line flagged converted without a PO', $q$
    INSERT INTO procurement.replenishment_proposals (id, code, warehouse_id, run_date)
    VALUES (md5('qa:prop')::uuid, 'RP-QA', md5('demo:wh:HCM')::uuid, CURRENT_DATE);
    INSERT INTO procurement.replenishment_proposal_lines (id, proposal_id, inventory_item_id, reorder_point, suggested_qty, converted)
    VALUES (gen_random_uuid(), md5('qa:prop')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 10, 5, TRUE) $q$);
SELECT pg_temp.expect_fail('R46 overlapping approval limits for one role', $q$
    INSERT INTO procurement.approval_limits (id, role_id, max_po_amount, effective_from)
    SELECT gen_random_uuid(), r.id, 100000000, DATE '2026-01-01' FROM identity.app_role r WHERE r.code = 'PROCUREMENT_STAFF';
    INSERT INTO procurement.approval_limits (id, role_id, max_po_amount, effective_from)
    SELECT gen_random_uuid(), r.id, 200000000, DATE '2026-06-01' FROM identity.app_role r WHERE r.code = 'PROCUREMENT_STAFF' $q$);
SELECT pg_temp.expect_ok('R47 a draft PO with history is deleted with it (cascade through append-only)', $q$
    INSERT INTO procurement.purchase_order_line_revisions (id, po_line_id, po_revision_id, inventory_item_id, uom, ordered_qty, unit_price, tax_rate, discount_rate, line_subtotal, line_net, line_total)
    VALUES (gen_random_uuid(), md5('qa:po:2:line:1')::uuid, md5('qa:po:2:rev0')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'EACH', 1, 1, 0, 0, 1, 1, 1);
    INSERT INTO procurement.purchase_order_events (id, po_id, po_revision_id, action) VALUES (gen_random_uuid(), md5('qa:po:2')::uuid, md5('qa:po:2:rev0')::uuid, 'CREATED');
    DELETE FROM procurement.purchase_orders WHERE id = md5('qa:po:2')::uuid $q$);

\echo '--- 3-step receiving: QC area, receipt lifecycle, move to QC (V20260929000250, docs 03 §4-5)'
SELECT pg_temp.expect_ok('QC1 an Area of type QUALITY_CONTROL', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (md5('qa:loc:HCM-QC02')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-QC02', 'OVERSIZE', FALSE, FALSE, 'ACTIVE');
    INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
    VALUES (md5('qa:area:HCM-QC02')::uuid, md5('demo:wh:HCM')::uuid, md5('qa:loc:HCM-QC02')::uuid, 'QC02', 'QUALITY_CONTROL',
            'Khu kiểm tra chất lượng', 52, 2, 6, 6, 0, FALSE, 'ACTIVE') $q$);
SELECT pg_temp.expect_fail('QC2 an Area type that does not exist', $q$
    UPDATE warehouse.area SET type = 'QC' WHERE id = md5('qa:area:HCM-QC02')::uuid $q$);
SELECT pg_temp.expect_ok('QC3 an inventory item flagged QC required (docs 01)', $q$
    UPDATE inventory.inventory_items SET qc_required = TRUE WHERE sku = 'TABLE-OAK-160' $q$);
SELECT pg_temp.expect_ok('QC4 a receipt with a line received under the 3-step flow', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, received_at, received_by)
    VALUES (md5('qa:gr:qc')::uuid, 'GR-QA-QC', md5('demo:po:1')::uuid, md5('qa:po:1:rev0')::uuid, md5('demo:wh:HCM')::uuid,
            NOW(), md5('demo:user:editor')::uuid);
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty,
                                                 lot_number, expiry_date, qc_required)
    VALUES (md5('qa:grl:qc')::uuid, md5('qa:gr:qc')::uuid, md5('qa:po:1:line:2')::uuid, md5('demo:item:TABLE-OAK-160')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 2, 'LOT-QC', '2030-01-01', TRUE) $q$);
SELECT pg_temp.expect_ok('QC5 receipt CONFIRMED with who and when', $q$
    UPDATE procurement.goods_receipts SET status = 'CONFIRMED', confirmed_by = md5('demo:user:editor')::uuid,
        confirmed_at = NOW() WHERE id = md5('qa:gr:qc')::uuid $q$);
SELECT pg_temp.expect_fail('QC6 a confirmed receipt cannot be cancelled (BR-05)', $q$
    UPDATE procurement.goods_receipts SET status = 'CANCELLED' WHERE id = md5('qa:gr:qc')::uuid $q$);
SELECT pg_temp.expect_fail('QC7 CLOSED without closed_at', $q$
    UPDATE procurement.goods_receipts SET status = 'CLOSED' WHERE id = md5('qa:gr:qc')::uuid $q$);
SELECT pg_temp.expect_fail('QC8 closed_at on a receipt that is not CLOSED', $q$
    UPDATE procurement.goods_receipts SET closed_at = NOW() WHERE id = md5('qa:gr:qc')::uuid $q$);
SELECT pg_temp.expect_ok('QC9 receipt IN_QC', $q$
    UPDATE procurement.goods_receipts SET status = 'IN_QC' WHERE id = md5('qa:gr:qc')::uuid $q$);
SELECT pg_temp.expect_fail('QC10 a RECEIPT_QC move task with no receipt line', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, sku, lot_number, from_location_id, to_location_id, requested_qty)
    VALUES (gen_random_uuid(), 'MT-QA-QC0', md5('demo:wh:HCM')::uuid, 'RECEIPT_QC', 'TABLE-OAK-160', 'LOT-QC',
            md5('demo:loc:HCM-RCV01')::uuid, md5('qa:loc:HCM-QC02')::uuid, 2) $q$);
SELECT pg_temp.expect_fail('QC11 a MANUAL move task pointing at a receipt line', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, sku, lot_number, from_location_id, to_location_id,
                                     requested_qty, receipt_line_id)
    VALUES (gen_random_uuid(), 'MT-QA-QC1', md5('demo:wh:HCM')::uuid, 'MANUAL', 'TABLE-OAK-160', 'LOT-QC',
            md5('demo:loc:HCM-RCV01')::uuid, md5('qa:loc:HCM-QC02')::uuid, 2, md5('qa:grl:qc')::uuid) $q$);
SELECT pg_temp.expect_fail('QC12 move to QC for a line received under the 2-step flow (BR-07)', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, sku, from_location_id, to_location_id,
                                     requested_qty, receipt_line_id)
    VALUES (gen_random_uuid(), 'MT-QA-QC2', md5('demo:wh:HCM')::uuid, 'RECEIPT_QC', 'TABLE-OAK-160',
            md5('demo:loc:HCM-RCV01')::uuid, md5('qa:loc:HCM-QC02')::uuid, 1, md5('qa:grl:3')::uuid) $q$);
SELECT pg_temp.expect_fail('QC13 move to QC in the wrong direction (QC area to RECEIVING)', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, sku, lot_number, from_location_id, to_location_id,
                                     requested_qty, receipt_line_id)
    VALUES (gen_random_uuid(), 'MT-QA-QC3', md5('demo:wh:HCM')::uuid, 'RECEIPT_QC', 'TABLE-OAK-160', 'LOT-QC',
            md5('qa:loc:HCM-QC02')::uuid, md5('demo:loc:HCM-RCV01')::uuid, 2, md5('qa:grl:qc')::uuid) $q$);
SELECT pg_temp.expect_fail('QC14 move to QC ending in QUARANTINE instead of QUALITY_CONTROL', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, sku, lot_number, from_location_id, to_location_id,
                                     requested_qty, receipt_line_id)
    VALUES (gen_random_uuid(), 'MT-QA-QC4', md5('demo:wh:HCM')::uuid, 'RECEIPT_QC', 'TABLE-OAK-160', 'LOT-QC',
            md5('demo:loc:HCM-RCV01')::uuid, md5('demo:loc:HCM-QC01')::uuid, 2, md5('qa:grl:qc')::uuid) $q$);
SELECT pg_temp.expect_ok('QC15 move to QC: RECEIVING to QUALITY_CONTROL for a QC-required line', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, sku, lot_number, from_location_id, to_location_id,
                                     requested_qty, receipt_line_id)
    VALUES (gen_random_uuid(), 'MT-QA-QC5', md5('demo:wh:HCM')::uuid, 'RECEIPT_QC', 'TABLE-OAK-160', 'LOT-QC',
            md5('demo:loc:HCM-RCV01')::uuid, md5('qa:loc:HCM-QC02')::uuid, 2, md5('qa:grl:qc')::uuid) $q$);
SELECT pg_temp.expect_ok('QC16 INBOUND stock in the QC area', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, version, created_at)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-QC02', 'LOT-QC', '2030-01-01', 2, 0, 'INBOUND', 0, NOW()) $q$);
SELECT pg_temp.expect_ok('QC17 BLOCKED (rejected) stock in the quarantine area', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, version, created_at)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-QC01', 'LOT-RJ', '2030-01-01', 1, 0, 'BLOCKED', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('QC18 a stock status that does not exist', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, version, created_at)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-QC02', 'LOT-X', '2030-01-01', 1, 0, 'PENDING_QC', 0, NOW()) $q$);
SELECT pg_temp.expect_ok('QC19 ledger line INBOUND → AVAILABLE on putaway', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, quantity,
                                          from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PUTAWAY', 'TABLE-OAK-160', 'LOT-QC', 'HCM-QC02', 'HCM-B01-2-A', 2, 'INBOUND', 'AVAILABLE',
            'PUTAWAY_TASK', gen_random_uuid(), NOW()) $q$);
-- SCRUM-435 (V20261010000100): where a QC line's goods are, BR-05 on lines, BR-08 in the database.
SELECT pg_temp.expect_fail('QC21 inspection before the goods are moved to the QC area (BR-08)', $q$
    INSERT INTO procurement.qc_inspections (id, receipt_line_id, outcome, quantity, inspected_by)
    VALUES (gen_random_uuid(), md5('qa:grl:qc')::uuid, 'ACCEPTED', 2, md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('QC22 QC location set without who and when', $q$
    UPDATE procurement.goods_receipt_lines SET qc_location_id = md5('qa:loc:HCM-QC02')::uuid
     WHERE id = md5('qa:grl:qc')::uuid $q$);
SELECT pg_temp.expect_fail('QC23 QC location that is a QUARANTINE area, not QUALITY_CONTROL', $q$
    UPDATE procurement.goods_receipt_lines SET qc_location_id = md5('demo:loc:HCM-QC01')::uuid,
           moved_to_qc_at = NOW(), moved_to_qc_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa:grl:qc')::uuid $q$);
SELECT pg_temp.expect_ok('QC24 goods of a QC line moved to the QC area', $q$
    UPDATE procurement.goods_receipt_lines SET qc_location_id = md5('qa:loc:HCM-QC02')::uuid,
           moved_to_qc_at = NOW(), moved_to_qc_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa:grl:qc')::uuid $q$);
SELECT pg_temp.expect_ok('QC25 inspection once the goods are in the QC area', $q$
    INSERT INTO procurement.qc_inspections (id, receipt_line_id, outcome, quantity, inspected_by)
    VALUES (gen_random_uuid(), md5('qa:grl:qc')::uuid, 'ACCEPTED', 2, md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('QC26 recounting a line of a confirmed receipt (BR-05)', $q$
    UPDATE procurement.goods_receipt_lines SET received_qty = 1 WHERE id = md5('qa:grl:qc')::uuid $q$);
SELECT pg_temp.expect_fail('QC27 adding a line to a confirmed receipt (BR-05)', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty,
                                                 lot_number, expiry_date, qc_required)
    SELECT gen_random_uuid(), receipt_id, po_line_id, inventory_item_id, location_id, 1, 'LOT-QC2', expiry_date, TRUE
      FROM procurement.goods_receipt_lines WHERE id = md5('qa:grl:qc')::uuid $q$);
SELECT pg_temp.expect_fail('QC28 removing a line of a confirmed receipt (BR-05)', $q$
    DELETE FROM procurement.goods_receipt_lines WHERE id = md5('qa:grl:qc')::uuid $q$);
SELECT pg_temp.expect_ok('QC20 receipt CLOSED with closed_at', $q$
    UPDATE procurement.goods_receipts SET status = 'CLOSED', closed_at = NOW() WHERE id = md5('qa:gr:qc')::uuid $q$);

\echo '--- supplier communication (C4 P0, V20261011000100)'
SELECT pg_temp.expect_fail('R48 API supplier without an endpoint', $q$
    UPDATE procurement.suppliers SET communication_channel = 'API', api_endpoint = NULL
     WHERE id = md5('demo:supplier:GOVIET')::uuid $q$);
SELECT pg_temp.expect_fail('R49 API supplier on plain http', $q$
    UPDATE procurement.suppliers SET communication_channel = 'API', api_endpoint = 'http://po.goviet.vn/orders'
     WHERE id = md5('demo:supplier:GOVIET')::uuid $q$);
SELECT pg_temp.expect_ok('R50 API supplier on https', $q$
    UPDATE procurement.suppliers SET communication_channel = 'API', api_endpoint = 'https://po.goviet.vn/orders'
     WHERE id = md5('demo:supplier:GOVIET')::uuid $q$);
SELECT pg_temp.expect_fail('R51 unknown delivery channel', $q$
    UPDATE procurement.suppliers SET communication_channel = 'FAX' WHERE id = md5('demo:supplier:GOVIET')::uuid $q$);
SELECT pg_temp.expect_fail('R52 payment term of 400 days', $q$
    UPDATE procurement.suppliers SET payment_term_days = 400 WHERE id = md5('demo:supplier:GOVIET')::uuid $q$);
SELECT pg_temp.expect_fail('R53 supplier answer without a time', $q$
    UPDATE procurement.purchase_orders SET supplier_confirmation_status = 'CONFIRMED'
     WHERE id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_fail('R54 supplier refusal without a reason', $q$
    UPDATE procurement.purchase_orders SET supplier_confirmation_status = 'REJECTED',
           supplier_responded_at = NOW(), supplier_response_note = '   '
     WHERE id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_ok('R55 supplier confirmation with time and reference', $q$
    UPDATE procurement.purchase_orders SET supplier_confirmation_status = 'CONFIRMED',
           supplier_responded_at = NOW(), supplier_reference = 'GV-SO-1'
     WHERE id = md5('demo:po:1')::uuid $q$);
SELECT pg_temp.expect_fail('R56 negative lead time snapshot', $q$
    UPDATE procurement.purchase_orders SET lead_time_days = -1 WHERE id = md5('demo:po:1')::uuid $q$);

ROLLBACK;
\echo 'PASS 02_procurement'
