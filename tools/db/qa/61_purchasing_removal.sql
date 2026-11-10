-- =============================================================================
-- Adversarial checks + happy paths: purchasing is gone (V20261113000200, team plan 4A-9; kltn-docs 00 "bo PO", 02
-- receiving §2, 03 BR-03/BR-12). Needs the demo seed (the demo migration V20261113000190 retires its PO and suppliers
-- before the main one runs; the HCM warehouse and its bins). Rolled back at the end.
--
-- Each "gone" claim is also tried the other way round: no function body still names a dropped table or column (a SQL
-- function naming a missing table only fails when it is called), what was rewritten still refuses what it refused, and
-- what the migration relaxed (the area, the bin) still works.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints and constraint triggers fire here, still inside the sub-transaction.
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'ok    %  [%]', label, left(SQLERRM, 110);
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

-- Refused, and for the reason named: the message says {fragment}. A refusal for any other reason (a typo, a missing
-- column) is not the rule under test.
CREATE FUNCTION pg_temp.expect_fail_like(label TEXT, fragment TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF position(fragment IN SQLERRM) = 0 THEN
            RAISE EXCEPTION 'FAIL  %: refused, but for another reason: %', label, SQLERRM;
        END IF;
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, cond TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    result BOOLEAN;
BEGIN
    EXECUTE 'SELECT (' || cond || ')' INTO result;
    IF result IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %: % is not true', label, cond;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- the tables, columns and functions are gone, the receiving ones stay'
SELECT pg_temp.expect_true('T1 none of the 21 procurement tables of purchasing remains', $c$
    NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'procurement' AND table_name IN (
        'suppliers', 'supplier_contacts', 'supplier_addresses', 'supplier_items', 'supplier_item_prices',
        'approval_limits', 'replenishment_proposals', 'replenishment_proposal_lines', 'purchase_orders',
        'purchase_order_revisions', 'purchase_order_lines', 'purchase_order_line_revisions', 'purchase_order_approvals',
        'purchase_order_events', 'purchase_order_attachments', 'po_delivery_decision', 'po_number_sequence',
        'subcontract_settlement', 'supplier_invoices', 'supplier_invoice_lines', 'supplier_invoice_variances')) $c$);
SELECT pg_temp.expect_true('T2 nor the notification and warehouse ones', $c$
    to_regclass('notification.po_delivery_control') IS NULL AND to_regclass('notification.po_retry_cursor') IS NULL
    AND to_regclass('warehouse.subcontractor_site') IS NULL $c$);
SELECT pg_temp.expect_true('T3 procurement keeps exactly its three receiving tables', $c$
    (SELECT string_agg(table_name, ',' ORDER BY table_name) FROM information_schema.tables
      WHERE table_schema = 'procurement' AND table_type = 'BASE TABLE')
    = 'goods_receipt_lines,goods_receipt_photos,goods_receipts' $c$);
SELECT pg_temp.expect_true('T4 goods_receipts lost its PO, supplier, blind and approval columns', $c$
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'procurement'
                 AND table_name = 'goods_receipts' AND column_name IN ('po_id', 'po_revision_id', 'supplier_id',
                     'blind_reason', 'referenced_po_id', 'approval_requested_at', 'approval_requested_by',
                     'approval_decision', 'approval_decided_at', 'approval_decided_by', 'approval_reason')) $c$);
SELECT pg_temp.expect_true('T5 goods_receipt_lines lost po_line_id', $c$
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'procurement'
                 AND table_name = 'goods_receipt_lines' AND column_name = 'po_line_id') $c$);
SELECT pg_temp.expect_true('T6 inventory_items lost its purchasing columns', $c$
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                 AND table_name = 'inventory_items' AND column_name IN ('default_supplier_id',
                     'reference_purchase_price', 'reference_purchase_currency', 'lead_time_days', 'moq')) $c$);
SELECT pg_temp.expect_true('T7 the twelve trigger functions of the dropped tables, and the tolerance one, are gone', $c$
    NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname IN ('check_po_revision_pointers', 'check_po_line_revision',
        'check_po_line_revision_pair', 'check_po_line_unit', 'check_subcontract_po', 'check_subcontract_po_single_line',
        'guard_po_warehouse_change', 'guard_po_line_content_change', 'check_supplier_invoice',
        'check_supplier_invoice_line', 'check_supplier_invoice_match', 'check_subcontractor_site_location',
        'check_goods_receipt_tolerance')) $c$);
SELECT pg_temp.expect_true('T8 no function body of the application schemas still names a dropped table or column', $c$
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname IN ('procurement', 'inventory', 'warehouse', 'ordering', 'notification', 'fulfillment',
                                     'platform', 'identity', 'catalog', 'product', 'customer', 'payment', 'design')
                   AND (p.prosrc ~ 'procurement\.(suppliers|purchase_order|supplier_|po_|subcontract|approval_limits|replenishment)'
                     OR p.prosrc ~ 'subcontractor_site|notification\.po_|po_line_id|referenced_po_id|blind_reason'
                     OR p.prosrc ~ 'approval_requested|over_receipt_tolerance|default_supplier_id|reference_purchase'
                     OR p.prosrc ~ 'supplier_items|catalog\.pricing_rule')) $c$);
SELECT pg_temp.expect_true('T9 the tolerance triggers are gone, the check trigger names no dropped column', $c$
    NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'procurement.goods_receipts'::regclass AND NOT tgisinternal
                 AND tgname IN ('tg_goods_receipts_tolerance', 'tg_goods_receipts_tolerance_insert'))
    AND (SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname = 'tg_goods_receipts_check') NOT LIKE '%po_id%'
    AND (SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname = 'tg_goods_receipts_check') LIKE '%production_order_id%' $c$);
SELECT pg_temp.expect_true('T10 no foreign key points at a dropped table, and the lot keeps its snapshot columns', $c$
    NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname IN ('fk_stock_lot_purchase_order', 'fk_stock_lot_supplier',
        'fk_goods_receipts_po', 'fk_goods_receipts_supplier', 'fk_inventory_items_default_supplier',
        'fk_goods_receipt_lines_po_line', 'fk_stock_damage_disposition_supplier'))
    AND (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'inventory' AND table_name = 'stock_lot'
          AND column_name IN ('purchase_order_id', 'purchase_order_number', 'supplier_id', 'supplier_name')) = 4 $c$);

\echo '--- what was dropped is in the archive'
SELECT pg_temp.expect_true('A1 the demo suppliers are archived whole', $c$
    (SELECT count(*) FROM platform.legacy_archive WHERE source_table = 'procurement.suppliers') >= 2
    AND EXISTS (SELECT 1 FROM platform.legacy_archive WHERE source_table = 'procurement.suppliers'
                 AND row_data ->> 'code' = 'GOVIET') $c$);
SELECT pg_temp.expect_true('A2 the demo purchase order the demo migration cancelled is archived as it was left', $c$
    EXISTS (SELECT 1 FROM platform.legacy_archive WHERE source_table = 'procurement.purchase_orders'
             AND row_data ->> 'po_number' = 'PO-HCM-DEMO-0002' AND row_data ->> 'status' = 'CANCELLED') $c$);
SELECT pg_temp.expect_true('A3 the 33 permissions that went (QC task ones went with V20261108000200) are archived, the four goods-receipt ones are not', $c$
    (SELECT count(*) FROM platform.legacy_archive WHERE source_table = 'identity.permission'
        AND row_data ->> 'code' LIKE 'procurement-%' AND row_data ->> 'code' NOT LIKE 'procurement-qc-tasks:%') = 33
    AND NOT EXISTS (SELECT 1 FROM platform.legacy_archive WHERE source_table = 'identity.permission'
        AND row_data ->> 'code' IN ('procurement-goods-receipts:VIEW_PAGE', 'procurement-goods-receipts:READ',
                                    'procurement-goods-receipts:CREATE', 'procurement-goods-receipts:UPDATE')) $c$);

\echo '--- permissions'
SELECT pg_temp.expect_true('P1 only the four goods-receipt permissions are left under procurement-', $c$
    (SELECT string_agg(code, ',' ORDER BY code) FROM identity.permission WHERE code LIKE 'procurement-%')
    = 'procurement-goods-receipts:CREATE,procurement-goods-receipts:READ,procurement-goods-receipts:UPDATE,procurement-goods-receipts:VIEW_PAGE' $c$);
SELECT pg_temp.expect_true('P2 no role holds a grant of a permission that is gone', $c$
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                 WHERE NOT EXISTS (SELECT 1 FROM identity.permission p WHERE p.id = rp.permission_id)) $c$);

\echo '--- the rewritten functions run'
-- A SQL function naming a table that is not there fails only when it is called.
SELECT pg_temp.expect_true('F1 item_in_use runs and is true for an item with stock', $c$
    inventory.item_in_use(md5('demo:item:SOFA-3S-GREY')::uuid, 'SOFA-3S-GREY') $c$);
SELECT pg_temp.expect_true('F2 unit_in_use runs', $c$
    NOT inventory.unit_in_use(gen_random_uuid()) $c$);

-- Fixtures: a carton of 10 sofas (its own unit code: the demo sofa already has the CARTON of one that 4C-1 made); a production order of 5 cartons of them at HCM, SENT with no sample to approve first;
-- a DRAFT receipt against it, registered with it (as 22_production_receipts.sql does). The production order's own rows are
-- written with the row triggers off: its rules are 21_production_orders.sql's.
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, length_cm, width_cm, height_cm, weight_kg)
VALUES (md5('qa61:carton')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'CARTON10', 10, 60, 40, 50, 12),
       (md5('qa61:old')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'OLDCTN', 10, NULL, NULL, NULL, NULL);
UPDATE inventory.uom_conversions SET active = FALSE WHERE id = md5('qa61:old')::uuid;
SET LOCAL session_replication_role = replica;
INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                       receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                       sample_required_by_default, drawn_up_by, submitted_at, approved_by, approved_at, sent_at)
VALUES (md5('qa61:prod')::uuid, 'PRD-20261109-6101', md5('qa61:so')::uuid, md5('qa61:batch')::uuid,
        md5('qa61:cust')::uuid, md5('qa61:producer')::uuid, md5('demo:wh:HCM')::uuid, CURRENT_DATE + 30, 'SENT', FALSE,
        FALSE, md5('demo:user:editor')::uuid, NOW(), md5('demo:user:approver')::uuid, NOW(), NOW());
INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                            design_snapshot_id, print_file_checksum)
VALUES (md5('qa61:prod:line')::uuid, md5('qa61:prod')::uuid, md5('qa61:ol')::uuid, 'SOFA-3S-GREY', 5, 10,
        md5('qa61:snap')::uuid, repeat('d', 64));
-- An item nothing but a production order line names.
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qa61:product')::uuid, 'QA61', 'QA 61', 'qa-61', 'DRAFT', 'STANDARD', 'STANDARD', 0, NOW());
SET LOCAL session_replication_role = origin;
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version, created_at)
VALUES (md5('qa61:variant')::uuid, md5('qa61:product')::uuid, 'QA-61-ONLY', 'QA 61', 'DRAFT', TRUE, 'DEFAULT', 0, 0, NOW());
SELECT pg_temp.expect_true('F3 item_in_use is false for an item no document names', $c$
    NOT inventory.item_in_use((SELECT id FROM inventory.inventory_items WHERE sku = 'QA-61-ONLY'), 'QA-61-ONLY') $c$);
SELECT pg_temp.expect_ok('F3b an unused DRAFT variant may still be renamed', $q$
    UPDATE product.variants SET sku = 'QA-61-FREE' WHERE id = md5('qa61:variant')::uuid $q$);
SET LOCAL session_replication_role = replica;
INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                            design_snapshot_id, print_file_checksum)
VALUES (md5('qa61:prod:line2')::uuid, md5('qa61:prod')::uuid, md5('qa61:ol2')::uuid, 'QA-61-FREE', 1, 10,
        md5('qa61:snap')::uuid, repeat('d', 64));
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_true('F4 item_in_use counts a production order line (the SKU may no longer be renamed)', $c$
    inventory.item_in_use((SELECT id FROM inventory.inventory_items WHERE sku = 'QA-61-FREE'), 'QA-61-FREE') $c$);
SELECT pg_temp.expect_fail_like('F5 the SKU of that item renamed', 'unused DRAFT variant', $q$
    UPDATE product.variants SET sku = 'QA-61-RENAMED' WHERE id = md5('qa61:variant')::uuid $q$);

INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, version, created_at)
VALUES (md5('qa61:wh')::uuid, 'QA other warehouse', 'ACTIVE', 'QAX', 'Somewhere', 'M', 50, 50, 0, NOW());
INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id, warehouse_id, status,
                                        received_at, received_by)
VALUES (md5('qa61:gr')::uuid, 'GR-QA61', 'PRODUCTION', md5('qa61:prod')::uuid, md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
        md5('demo:user:editor')::uuid);
INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status, opened_at)
VALUES (md5('qa61:prod')::uuid, md5('qa61:gr')::uuid, 'GR-QA61', 'DRAFT', NOW());
-- A second draft of the same production order, to try a photo against the wrong receipt.
INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id, warehouse_id, status,
                                        received_at, received_by)
VALUES (md5('qa61:gr2')::uuid, 'GR-QA61-2', 'PRODUCTION', md5('qa61:prod')::uuid, md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
        md5('demo:user:editor')::uuid);
INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status, opened_at)
VALUES (md5('qa61:prod')::uuid, md5('qa61:gr2')::uuid, 'GR-QA61-2', 'DRAFT', NOW());
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- no purchase receipt is started; a receipt says what it is against'
SELECT pg_temp.expect_fail_like('R1 a PURCHASE receipt started', 'purchasing was removed', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, warehouse_id, status, received_at, received_by)
    VALUES (gen_random_uuid(), 'GR-QA61-P', 'PURCHASE', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail_like('R2 a receipt with no type (the PURCHASE default is gone)', 'receipt_type', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by)
    VALUES (gen_random_uuid(), 'GR-QA61-N', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_true('R3 receipt_type has no default', $c$
    (SELECT column_default FROM information_schema.columns WHERE table_schema = 'procurement'
        AND table_name = 'goods_receipts' AND column_name = 'receipt_type') IS NULL $c$);
SELECT pg_temp.expect_fail_like('R4 a draft receipt turned into a purchase receipt', 'does not change', $q$
    UPDATE procurement.goods_receipts SET receipt_type = 'PURCHASE', production_order_id = NULL
     WHERE id = md5('qa61:gr')::uuid $q$);
SELECT pg_temp.expect_fail_like('R5 PENDING_APPROVAL is no status any more', 'ck_goods_receipts_status', $q$
    UPDATE procurement.goods_receipts SET status = 'PENDING_APPROVAL', confirmed_at = NOW(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa61:gr')::uuid $q$);
SELECT pg_temp.expect_fail_like('R6 a receipt into another warehouse than its production order is received at', 'BR-01', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id, warehouse_id, status,
                                            received_at, received_by)
    VALUES (gen_random_uuid(), 'GR-QA61-W', 'PRODUCTION', md5('qa61:prod')::uuid, md5('qa61:wh')::uuid, 'DRAFT', NOW(),
            md5('demo:user:editor')::uuid) $q$);

\echo '--- a line has a unit and a lot (NOT NULL), and the rewritten line checks still refuse'
SELECT pg_temp.expect_true('L1 goods_receipt_lines.lot_number and uom_conversion_id are NOT NULL', $c$
    (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'procurement'
        AND table_name = 'goods_receipt_lines' AND column_name IN ('lot_number', 'uom_conversion_id')
        AND is_nullable = 'NO') = 2 $c$);
SELECT pg_temp.expect_fail_like('L2 a line without a lot, written with the row triggers off', 'lot_number', $q$
    SET LOCAL session_replication_role = replica;
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa61:gr')::uuid, md5('qa61:prod:line')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 10, NULL, md5('qa61:carton')::uuid) $q$);
SELECT pg_temp.expect_fail_like('L3 a line without a unit, written with the row triggers off', 'uom_conversion_id', $q$
    SET LOCAL session_replication_role = replica;
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa61:gr')::uuid, md5('qa61:prod:line')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-6101', NULL) $q$);
SELECT pg_temp.expect_fail_like('L4 a line in a lot of its own on a production receipt (BR-03)', 'the lot is production order', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa61:gr')::uuid, md5('qa61:prod:line')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'LOT-A', md5('qa61:carton')::uuid) $q$);
-- The expiry column goes with 5-1 (V20261111000300); the check reads it through to_jsonb either way.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'procurement'
                AND table_name = 'goods_receipt_lines' AND column_name = 'expiry_date') THEN
        PERFORM pg_temp.expect_fail_like('L5 a production line with an expiry date (BR-06)', 'no expiry date', $q$
            INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id,
                                                         location_id, received_qty, lot_number, uom_conversion_id, expiry_date)
            VALUES (gen_random_uuid(), md5('qa61:gr')::uuid, md5('qa61:prod:line')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
                    md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-6101', md5('qa61:carton')::uuid, CURRENT_DATE + 365) $q$);
    ELSE
        RAISE NOTICE 'ok    L5 (expiry_date is gone: nothing to try)';
    END IF;
END $$;
SELECT pg_temp.expect_fail_like('L6 a line that names no production order line (the receipt is against one)', 'names a production order line',
$q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty, lot_number,
                                                 uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa61:gr')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, md5('demo:loc:HCM-RCV01')::uuid,
            10, 'PRD-20261109-6101', md5('qa61:carton')::uuid) $q$);
SELECT pg_temp.expect_fail_like('L7 a line in a retired unit takes nothing new (no purchase order line to excuse it)', 'is retired', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa61:gr')::uuid, md5('qa61:prod:line')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-6101', md5('qa61:old')::uuid) $q$);
SELECT pg_temp.expect_fail_like('L8 a line in a unit of another item', 'cartons holding', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    SELECT gen_random_uuid(), md5('qa61:gr')::uuid, md5('qa61:prod:line')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
           md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-6101', u.id
      FROM inventory.uom_conversions u WHERE u.inventory_item_id = md5('demo:item:TABLE-OAK-160')::uuid AND u.is_base $q$);
SELECT pg_temp.expect_ok('L9 a whole carton of the production order line, in its lot', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (md5('qa61:l1')::uuid, md5('qa61:gr')::uuid, md5('qa61:prod:line')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 20, 'PRD-20261109-6101', md5('qa61:carton')::uuid) $q$);
SELECT pg_temp.expect_true('L10 unit_in_use sees the receipt line''s unit', $c$ inventory.unit_in_use(md5('qa61:carton')::uuid) $c$);

\echo '--- a posted receipt keeps what was counted (BR-05), only a draft is edited'
SELECT pg_temp.expect_ok('L11 the draft posted and its registration confirmed', $q$
    UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_at = NOW(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa61:gr')::uuid;
    UPDATE ordering.production_order_receipt SET status = 'CONFIRMED', settled_at = NOW()
     WHERE goods_receipt_id = md5('qa61:gr')::uuid $q$);
SELECT pg_temp.expect_fail_like('L12 a posted line recounted', 'BR-05', $q$
    UPDATE procurement.goods_receipt_lines SET received_qty = 30 WHERE id = md5('qa61:l1')::uuid $q$);
SELECT pg_temp.expect_fail_like('L13 a posted line moved to another unit', 'BR-05', $q$
    UPDATE procurement.goods_receipt_lines SET uom_conversion_id = md5('qa61:old')::uuid WHERE id = md5('qa61:l1')::uuid $q$);
SELECT pg_temp.expect_fail_like('L14 a line added to a posted receipt', 'BR-05', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa61:gr')::uuid, md5('qa61:prod:line')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-6101', md5('qa61:carton')::uuid) $q$);
SELECT pg_temp.expect_ok('L15 a posted line''s note is still editable', $q$
    UPDATE procurement.goods_receipt_lines SET note = 'checked twice' WHERE id = md5('qa61:l1')::uuid $q$);

\echo '--- photos: only the RECEIPT stage is left'
SELECT pg_temp.expect_fail_like('H1 a QC photo', 'ck_goods_receipt_photos_stage', $q$
    INSERT INTO procurement.goods_receipt_photos (id, receipt_id, receipt_line_id, stage, storage_key, original_name,
                                                  content_type, size_bytes, checksum, taken_by)
    VALUES (gen_random_uuid(), md5('qa61:gr')::uuid, md5('qa61:l1')::uuid, 'QC', 'qc-evidence/2026/11/09/q.jpg', 'q.jpg',
            'image/jpeg', 1, repeat('0', 64), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('H2 a RECEIPT photo of a line', $q$
    INSERT INTO procurement.goods_receipt_photos (id, receipt_id, receipt_line_id, stage, storage_key, original_name,
                                                  content_type, size_bytes, checksum, taken_by)
    VALUES (md5('qa61:photo')::uuid, md5('qa61:gr')::uuid, md5('qa61:l1')::uuid, 'RECEIPT',
            'qc-evidence/2026/11/09/r.jpg', 'r.jpg', 'image/jpeg', 1, repeat('0', 64), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail_like('H3 a photo of a line of another receipt', 'is not a line of receipt', $q$
    INSERT INTO procurement.goods_receipt_photos (id, receipt_id, receipt_line_id, stage, storage_key, original_name,
                                                  content_type, size_bytes, checksum, taken_by)
    VALUES (gen_random_uuid(), md5('qa61:gr2')::uuid, md5('qa61:l1')::uuid, 'RECEIPT', 'qc-evidence/2026/11/09/o.jpg', 'o.jpg',
            'image/jpeg', 1, repeat('0', 64), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('H4 a photo changed (append-only)', $q$
    UPDATE procurement.goods_receipt_photos SET original_name = 'z.jpg' WHERE id = md5('qa61:photo')::uuid $q$);

\echo '--- storage locations: a bin or an area owns each; the SUBCONTRACTOR kind takes no new one'
-- check_storage_location_owned runs at the commit of every bin and area created: it must read only tables that exist.
SELECT pg_temp.expect_ok('S1 a new area, location first (owned by the commit)', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (md5('qa61:locA')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-QA61', 'NORMAL', FALSE, FALSE, 'ACTIVE');
    INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
    VALUES (md5('qa61:areaA')::uuid, md5('demo:wh:HCM')::uuid, md5('qa61:locA')::uuid, 'QA61', 'OVERFLOW', 'QA 61', 0, 0, 2, 2, 0,
            FALSE, 'ACTIVE') $q$);
SELECT pg_temp.expect_ok('S2 a new bin, location first (owned by the commit)', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (md5('qa61:locB')::uuid, md5('demo:wh:HCM')::uuid, 'BIN', 'HCM-A01-1-Q', 'NORMAL', TRUE, TRUE, 'ACTIVE');
    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
    VALUES (md5('qa61:binB')::uuid, md5('demo:level:HCM-A01-1')::uuid, md5('qa61:locB')::uuid, 'Q', 0, 0, 1, 1, 0, 'STANDARD') $q$);
SELECT pg_temp.expect_fail_like('S3 a SUBCONTRACTOR location (nothing owns it any more)', 'owned by no bin and no area', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'SUBCONTRACTOR', 'HCM-SUB-QA61', 'NORMAL', FALSE, FALSE, 'ACTIVE') $q$);
SELECT pg_temp.expect_fail_like('S4 a bin location whose bin was never written', 'owned by no bin and no area', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'BIN', 'HCM-A01-1-R', 'NORMAL', TRUE, TRUE, 'ACTIVE') $q$);
SELECT pg_temp.expect_true('S5 the kind itself stays in the CHECKs: ledger lines of the locations there were name them', $c$
    (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_storage_location_kind') LIKE '%SUBCONTRACTOR%' $c$);

ROLLBACK;
