-- =============================================================================
-- Adversarial checks + happy paths: stock packages (cartons with an LPN), the units documents count
-- in, and the factor lock (GitHub #125, Jira SCRUM-473; V20261020000100-0700). Needs the demo seed.
-- Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;

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

-- Packing units: a carton of 10 sofas, a carton of 5 tables, and a case of 4 sofas nobody uses.
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, length_cm, width_cm, height_cm, weight_kg)
VALUES (md5('qa:unit:SOFA-CARTON')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'CARTON', 10, 60, 40, 50, 12),
       (md5('qa:unit:TABLE-CARTON')::uuid, md5('demo:item:TABLE-OAK-160')::uuid, 'CARTON', 5, 80, 60, 40, 30),
       (md5('qa:unit:SOFA-CASE')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'CASE', 4, NULL, NULL, NULL, NULL);
-- The supplier's old carton of 10 sofas, retired during the run (docs 01 edge case "NCC đổi quy cách thùng").
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa:unit:SOFA-OLD')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'OLDCTN', 10);


\echo '--- a stock package and its stock rows (V20261020000100, 0200)'
SELECT pg_temp.expect_ok('K1 received into stock: RECEIVING, counted, then IN_STOCK with its row', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code)
    VALUES (md5('qa:pk:1')::uuid, 'QA-LPN-1', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 10, 10, 'HCM-A01-1-A');
    UPDATE inventory.stock_package SET status = 'IN_STOCK' WHERE id = md5('qa:pk:1')::uuid;
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (md5('qa:pk:1:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 10, 0, 'AVAILABLE', NOW(), NOW() - interval '1 hour', md5('qa:pk:1')::uuid) $q$);
SELECT pg_temp.expect_fail('K2 the stock package moved without its rows', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail('K3 a row moved out from under its stock package', $q$
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa:pk:1:row')::uuid $q$);
SELECT pg_temp.expect_ok('K4 the stock package moved with its rows, one transaction', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa:pk:1')::uuid;
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-B' WHERE stock_package_id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail('K5 an unopened stock package loses stock', $q$
    UPDATE inventory.stock_item SET on_hand = 9 WHERE id = md5('qa:pk:1:row')::uuid $q$);
SELECT pg_temp.expect_fail('K6 part of an unopened stock package leaves for QC', $q$
    UPDATE inventory.stock_item SET on_hand = 6 WHERE id = md5('qa:pk:1:row')::uuid;
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-QC01', 4, 0, 'QUARANTINE', NOW(), NOW() - interval '1 hour') $q$);
SELECT pg_temp.expect_fail('K7 a row of another SKU put into it', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-A01-1-B', 'L1', CURRENT_DATE + 30, 1, 0, 'AVAILABLE', NOW(), NOW() - interval '1 hour', md5('qa:pk:1')::uuid) $q$);
SELECT pg_temp.expect_fail('K8 counted again once in stock', $q$
    UPDATE inventory.stock_package SET received_qty = 9 WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail('K9 its LPN changed', $q$
    UPDATE inventory.stock_package SET lpn = 'QA-LPN-X' WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail('K10 a second stock package with the same LPN', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA-LPN-1', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 10, 'HCM-A01-1-A') $q$);
SELECT pg_temp.expect_fail('K11 emptied but left IN_STOCK', $q$
    UPDATE inventory.stock_package SET unopened = FALSE WHERE id = md5('qa:pk:1')::uuid;
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa:pk:1:row')::uuid $q$);
SELECT pg_temp.expect_fail('K12 CONSUMED while still holding stock', $q$
    UPDATE inventory.stock_package SET unopened = FALSE, status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_ok('K13 opened, and part of it leaves for QC as loose stock', $q$
    UPDATE inventory.stock_package SET unopened = FALSE WHERE id = md5('qa:pk:1')::uuid;
    UPDATE inventory.stock_item SET on_hand = 6 WHERE id = md5('qa:pk:1:row')::uuid;
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-QC01', 4, 0, 'QUARANTINE', NOW(), NOW() - interval '1 hour') $q$);
SELECT pg_temp.expect_fail('K14 an opened stock package marked unopened again', $q$
    UPDATE inventory.stock_package SET unopened = TRUE WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_ok('K15 emptied and CONSUMED in one transaction', $q$
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa:pk:1:row')::uuid;
    UPDATE inventory.stock_package SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail('K16 CONSUMED back to IN_STOCK', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa:pk:1')::uuid;
    UPDATE inventory.stock_item SET on_hand = 6 WHERE id = md5('qa:pk:1:row')::uuid $q$);

\echo '--- creation and lifecycle'
SELECT pg_temp.expect_ok('K17 made in the warehouse (repacked): created IN_STOCK with its row', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status)
    VALUES (md5('qa:pk:2')::uuid, 'QA-LPN-2', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 10, 4, 'HCM-A01-1-A', 'IN_STOCK');
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (md5('qa:pk:2:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 4, 0, 'AVAILABLE', NOW(), NOW() - interval '2 hour', md5('qa:pk:2')::uuid) $q$);
SELECT pg_temp.expect_ok('K18 not found by a count (LOST, emptied), then found again', $q$
    UPDATE inventory.stock_package SET unopened = FALSE WHERE id = md5('qa:pk:2')::uuid;
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa:pk:2:row')::uuid;
    UPDATE inventory.stock_package SET status = 'LOST', closed_at = NOW() WHERE id = md5('qa:pk:2')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa:pk:2')::uuid;
    UPDATE inventory.stock_item SET on_hand = 4 WHERE id = md5('qa:pk:2:row')::uuid $q$);
SELECT pg_temp.expect_fail('K19 created already CONSUMED (skipping the lifecycle)', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status, unopened, closed_at)
    VALUES (gen_random_uuid(), 'QA-LPN-19', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 10, 10, 'HCM-A01-1-A', 'CONSUMED', FALSE, NOW()) $q$);
SELECT pg_temp.expect_fail('K20 made in the base unit', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    SELECT gen_random_uuid(), 'QA-LPN-20', inventory_item_id, id, 1, 'HCM-A01-1-A' FROM inventory.uom_conversions
     WHERE inventory_item_id = md5('demo:item:SOFA-3S-GREY')::uuid AND is_base $q$);
SELECT pg_temp.expect_fail('K21 nominal_qty other than the unit''s factor', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA-LPN-21', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 12, 'HCM-A01-1-A') $q$);
SELECT pg_temp.expect_fail('K22 a unit of another item', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA-LPN-22', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:TABLE-CARTON')::uuid, 5, 'HCM-A01-1-A') $q$);
SELECT pg_temp.expect_fail('K23 in stock without having been counted', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, status)
    VALUES (gen_random_uuid(), 'QA-LPN-23', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 10, 'HCM-A01-1-A', 'IN_STOCK') $q$);
SELECT pg_temp.expect_fail('K24 still RECEIVING but holding stock', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (md5('qa:pk:24')::uuid, 'QA-LPN-24', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 10, 'HCM-A01-1-A');
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A01-1-A', 3, 0, 'AVAILABLE', NOW(), NOW() - interval '3 hour', md5('qa:pk:24')::uuid) $q$);
SELECT pg_temp.expect_fail('K25 two lots in one stock package', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status, unopened)
    VALUES (md5('qa:pk:25')::uuid, 'QA-LPN-25', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa:unit:TABLE-CARTON')::uuid, 5, 5, 'HCM-A01-1-A', 'IN_STOCK', FALSE);
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-A01-1-A', 'L1', CURRENT_DATE + 30, 2, 0, 'AVAILABLE', NOW(), NOW() - interval '1 hour', md5('qa:pk:25')::uuid),
           (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-A01-1-A', 'L2', CURRENT_DATE + 30, 3, 0, 'AVAILABLE', NOW(), NOW() - interval '1 hour', md5('qa:pk:25')::uuid) $q$);
SELECT pg_temp.expect_ok('K26 one lot received at two times in one stock package', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status, unopened)
    VALUES (md5('qa:pk:26')::uuid, 'QA-LPN-26', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa:unit:TABLE-CARTON')::uuid, 5, 5, 'HCM-A01-1-A', 'IN_STOCK', FALSE);
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-A01-1-A', 'L1', CURRENT_DATE + 30, 2, 0, 'AVAILABLE', NOW(), NOW() - interval '1 hour', md5('qa:pk:26')::uuid),
           (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-A01-1-A', 'L1', CURRENT_DATE + 30, 3, 0, 'AVAILABLE', NOW(), NOW() - interval '3 hour', md5('qa:pk:26')::uuid) $q$);

\echo '--- ledger (V20261020000300)'
SELECT pg_temp.expect_fail('K27 a source stock package with no source location', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, reference_type, reference_id, occurred_at, from_stock_package_id)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'HCM-A01-1-A', 1, 'GOODS_RECEIPT_LINE', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid) $q$);
SELECT pg_temp.expect_ok('K28 a whole stock package moved, named on both sides', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity, reference_type, reference_id, occurred_at, from_stock_package_id, to_stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-1-A', 'HCM-A01-1-B', 4, 'MOVE_TASK', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid, md5('qa:pk:2')::uuid) $q$);
SELECT pg_temp.expect_fail('K28a a movement naming a stock package of another SKU', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity, reference_type, reference_id, occurred_at, from_stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'TABLE-OAK-160', 'HCM-A01-1-A', 'HCM-A01-1-B', 1, 'MOVE_TASK', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid) $q$);
SELECT pg_temp.expect_fail('K28b a movement naming a stock package at a bin it never touched', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity, reference_type, reference_id, occurred_at, from_stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-1-B', 'HCM-RCV01', 1, 'MOVE_TASK', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid) $q$);

\echo '--- emptied rows stay in their stock package (V20261020000200)'
SELECT pg_temp.expect_ok('K28c an emptied row left behind when the stock package moves', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (md5('qa:pk:2:empty')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 0, 0, 'AVAILABLE', NOW(), NOW() - interval '5 hour', md5('qa:pk:2')::uuid);
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa:pk:2')::uuid;
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa:pk:2:row')::uuid $q$);
SELECT pg_temp.expect_fail('K28d the emptied row filled again away from its stock package', $q$
    UPDATE inventory.stock_item SET on_hand = 1 WHERE id = md5('qa:pk:2:empty')::uuid $q$);
SELECT pg_temp.expect_ok('K28e one lot picked out, another put back into the stock package', $q$
    UPDATE inventory.stock_item SET on_hand = 0 WHERE stock_package_id = md5('qa:pk:26')::uuid;
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-A01-1-A', 'L2', CURRENT_DATE + 30, 1, 0, 'AVAILABLE', NOW(), NOW() - interval '1 hour', md5('qa:pk:26')::uuid) $q$);
SELECT pg_temp.expect_fail('K28f a second row of the emptied layer in the stock package, on its new bin', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at, stock_package_id)
    SELECT gen_random_uuid(), sku, 'HCM-A01-1-B', 1, 0, 'AVAILABLE', NOW(), received_at, stock_package_id
      FROM inventory.stock_item WHERE id = md5('qa:pk:2:empty')::uuid $q$);
SELECT pg_temp.expect_ok('K28g the stock package back on the bin its emptied row never left', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-A' WHERE id = md5('qa:pk:2')::uuid;
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-A' WHERE id = md5('qa:pk:2:row')::uuid $q$);

\echo '--- purchase order lines record their unit (V20261020000400)'
INSERT INTO procurement.purchase_orders (id, po_number, supplier_id, warehouse_id, currency, order_date)
VALUES (md5('qa:pk:po:draft')::uuid, 'PO-QA-PK', md5('demo:supplier:GOVIET')::uuid, md5('demo:wh:HCM')::uuid, 'VND', CURRENT_DATE);
SELECT pg_temp.expect_ok('U1 5 CARTON of 10 = 50 sofas', $q$
    INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price, line_subtotal, line_net, line_total, uom_conversion_id, unit_qty)
    VALUES (md5('qa:pk:po:line:1')::uuid, md5('qa:pk:po:draft')::uuid, 1, md5('demo:item:SOFA-3S-GREY')::uuid, 50, 1, 50, 50, 50, md5('qa:unit:SOFA-CARTON')::uuid, 5) $q$);
SELECT pg_temp.expect_fail('U2 5 CARTON of 10 booked as 40', $q$
    UPDATE procurement.purchase_order_lines SET ordered_qty = 40, line_subtotal = 40, line_net = 40, line_total = 40
     WHERE id = md5('qa:pk:po:line:1')::uuid $q$);
SELECT pg_temp.expect_ok('U3 a draft line changed to 3 CARTON = 30', $q$
    UPDATE procurement.purchase_order_lines SET unit_qty = 3, ordered_qty = 30, line_subtotal = 30, line_net = 30, line_total = 30
     WHERE id = md5('qa:pk:po:line:1')::uuid $q$);
SELECT pg_temp.expect_fail('U4 a unit of another item', $q$
    INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price, line_subtotal, line_net, line_total, uom_conversion_id, unit_qty)
    VALUES (gen_random_uuid(), md5('qa:pk:po:draft')::uuid, 2, md5('demo:item:SOFA-3S-GREY')::uuid, 10, 1, 10, 10, 10, md5('qa:unit:TABLE-CARTON')::uuid, 2) $q$);
SELECT pg_temp.expect_fail('U5 a unit without a unit quantity', $q$
    INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price, line_subtotal, line_net, line_total, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa:pk:po:draft')::uuid, 2, md5('demo:item:SOFA-3S-GREY')::uuid, 10, 1, 10, 10, 10, md5('qa:unit:SOFA-CARTON')::uuid) $q$);
SELECT pg_temp.expect_ok('U6 a line without a unit is still a base-unit line', $q$
    INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price, line_subtotal, line_net, line_total)
    VALUES (gen_random_uuid(), md5('qa:pk:po:draft')::uuid, 2, md5('demo:item:TABLE-OAK-160')::uuid, 7, 1, 7, 7, 7) $q$);
SELECT pg_temp.expect_ok('U7 the revision freezes the unit with the line', $q$
    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, snapshot_header, changed_by)
    VALUES (md5('qa:pk:po:rev0')::uuid, md5('qa:pk:po:draft')::uuid, 0, '{}', md5('demo:user:editor')::uuid);
    UPDATE procurement.purchase_order_lines SET po_revision_id = md5('qa:pk:po:rev0')::uuid WHERE po_id = md5('qa:pk:po:draft')::uuid;
    INSERT INTO procurement.purchase_order_line_revisions (id, po_line_id, po_revision_id, inventory_item_id, uom, ordered_qty,
        unit_price, tax_rate, discount_rate, line_subtotal, line_net, line_tax, line_total, uom_conversion_id, unit_qty)
    SELECT gen_random_uuid(), l.id, l.po_revision_id, l.inventory_item_id, l.uom, l.ordered_qty, l.unit_price, l.tax_rate,
           l.discount_rate, l.line_subtotal, l.line_net, l.line_tax, l.line_total, l.uom_conversion_id, l.unit_qty
      FROM procurement.purchase_order_lines l WHERE l.po_id = md5('qa:pk:po:draft')::uuid $q$);
SELECT pg_temp.expect_fail('U8 a revision whose unit quantity does not convert', $q$
    INSERT INTO procurement.purchase_order_line_revisions (id, po_line_id, po_revision_id, inventory_item_id, uom, ordered_qty,
        unit_price, tax_rate, discount_rate, line_subtotal, line_net, line_tax, line_total, uom_conversion_id, unit_qty)
    VALUES (gen_random_uuid(), md5('qa:pk:po:line:1')::uuid, md5('qa:pk:po:rev0')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'EACH', 30,
        1, 0, 0, 30, 30, 0, 30, md5('qa:unit:SOFA-CARTON')::uuid, 4) $q$);
SELECT pg_temp.expect_ok('U8a a draft line in the old carton, before it is retired', $q$
    INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price, line_subtotal, line_net, line_total, uom_conversion_id, unit_qty)
    VALUES (md5('qa:pk:po:line:old')::uuid, md5('qa:pk:po:draft')::uuid, 3, md5('demo:item:SOFA-3S-GREY')::uuid, 20, 1, 20, 20, 20, md5('qa:unit:SOFA-OLD')::uuid, 2);
    UPDATE inventory.uom_conversions SET active = FALSE WHERE id = md5('qa:unit:SOFA-OLD')::uuid $q$);
SELECT pg_temp.expect_fail('U8b a new line in a retired unit', $q$
    INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price, line_subtotal, line_net, line_total, uom_conversion_id, unit_qty)
    VALUES (gen_random_uuid(), md5('qa:pk:po:draft')::uuid, 4, md5('demo:item:SOFA-3S-GREY')::uuid, 10, 1, 10, 10, 10, md5('qa:unit:SOFA-OLD')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('U8c a line moved onto a retired unit', $q$
    UPDATE procurement.purchase_order_lines SET uom_conversion_id = md5('qa:unit:SOFA-OLD')::uuid, unit_qty = 3
     WHERE id = md5('qa:pk:po:line:1')::uuid $q$);
SELECT pg_temp.expect_ok('U8d a line already in the retired unit keeps it and is recounted', $q$
    UPDATE procurement.purchase_order_lines SET unit_qty = 3, ordered_qty = 30, line_subtotal = 30, line_net = 30, line_total = 30
     WHERE id = md5('qa:pk:po:line:old')::uuid $q$);

\echo '--- receiving by the carton, putaway by the stock package (V20261020000400, 0500)'
-- demo:po:1 (10 sofas) submitted, approved and confirmed, as 02_procurement.sql does.
INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header, changed_by)
VALUES (md5('qa:pk:po1:rev0')::uuid, md5('demo:po:1')::uuid, 0, 'INITIAL', '{}', md5('demo:user:editor')::uuid);
UPDATE procurement.purchase_order_lines SET po_revision_id = md5('qa:pk:po1:rev0')::uuid WHERE po_id = md5('demo:po:1')::uuid;
INSERT INTO procurement.purchase_order_line_revisions (id, po_line_id, po_revision_id, inventory_item_id, uom, ordered_qty,
    unit_price, tax_rate, discount_rate, line_subtotal, line_net, line_tax, line_total)
SELECT gen_random_uuid(), l.id, l.po_revision_id, l.inventory_item_id, l.uom, l.ordered_qty, l.unit_price, l.tax_rate,
       l.discount_rate, l.line_subtotal, l.line_net, l.line_tax, l.line_total
  FROM procurement.purchase_order_lines l WHERE l.po_id = md5('demo:po:1')::uuid;
UPDATE procurement.purchase_orders SET status = 'PENDING_APPROVAL', pending_revision_id = md5('qa:pk:po1:rev0')::uuid,
    submitted_by = md5('demo:user:editor')::uuid, submitted_at = NOW() WHERE id = md5('demo:po:1')::uuid;
INSERT INTO procurement.purchase_order_approvals (id, po_revision_id, approver_id, decision)
VALUES (gen_random_uuid(), md5('qa:pk:po1:rev0')::uuid, md5('demo:user:approver')::uuid, 'APPROVED');
UPDATE procurement.purchase_orders SET status = 'APPROVED', approved_by = md5('demo:user:approver')::uuid, approved_at = NOW(),
    active_revision_id = pending_revision_id, pending_revision_id = NULL WHERE id = md5('demo:po:1')::uuid;
UPDATE procurement.purchase_orders SET status = 'CONFIRMED', confirmed_by = md5('demo:user:approver')::uuid, confirmed_at = NOW()
 WHERE id = md5('demo:po:1')::uuid;
INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, received_at, received_by)
VALUES (md5('qa:pk:gr')::uuid, 'GR-QA-PK', md5('demo:po:1')::uuid, md5('qa:pk:po1:rev0')::uuid, md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid);

SELECT pg_temp.expect_fail('U9 received in a unit of another item', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('demo:po:1:line:1')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 10, md5('qa:unit:TABLE-CARTON')::uuid) $q$);
SELECT pg_temp.expect_ok('U10 received in CARTON', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id, received_qty, uom_conversion_id)
    VALUES (md5('qa:pk:grl')::uuid, md5('qa:pk:gr')::uuid, md5('demo:po:1:line:1')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:loc:HCM-RCV01')::uuid, 10, md5('qa:unit:SOFA-CARTON')::uuid) $q$);
SELECT pg_temp.expect_fail('K29 a stock package naming a receipt line of another item', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, goods_receipt_line_id)
    VALUES (gen_random_uuid(), 'QA-LPN-29', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa:unit:TABLE-CARTON')::uuid, 5, 'HCM-RCV01', md5('qa:pk:grl')::uuid) $q$);
SELECT pg_temp.expect_ok('K30 a stock package of the receipt line, waiting in the receiving area', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, goods_receipt_line_id)
    VALUES (md5('qa:pk:30')::uuid, 'QA-LPN-30', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 10, 10, 'HCM-RCV01', md5('qa:pk:grl')::uuid) $q$);
SELECT pg_temp.expect_fail('K31 a putaway task naming a stock package of another SKU', $q$
    INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:grl')::uuid, 'TABLE-OAK-160', 10, 'PENDING', NOW(), md5('qa:pk:30')::uuid) $q$);
SELECT pg_temp.expect_ok('K32 one open putaway task for the stock package', $q$
    INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:grl')::uuid, 'SOFA-3S-GREY', 10, 'PENDING', NOW(), md5('qa:pk:30')::uuid) $q$);
SELECT pg_temp.expect_fail('K33 a second open putaway task for it', $q$
    INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:grl')::uuid, 'SOFA-3S-GREY', 10, 'PENDING', NOW(), md5('qa:pk:30')::uuid) $q$);
SELECT pg_temp.expect_fail('K33a a putaway task naming a VOID stock package', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, goods_receipt_line_id)
    VALUES (md5('qa:pk:33a')::uuid, 'QA-LPN-33A', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 10, 'HCM-RCV01', md5('qa:pk:grl')::uuid);
    UPDATE inventory.stock_package SET status = 'VOID', closed_at = NOW() WHERE id = md5('qa:pk:33a')::uuid;
    INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:grl')::uuid, 'SOFA-3S-GREY', 10, 'PENDING', NOW(), md5('qa:pk:33a')::uuid) $q$);

\echo '--- a retired unit stays for the documents already in it (V20261020000100, 0400)'
SELECT pg_temp.expect_fail('U11 a receipt line newly put in a retired unit its PO line was not ordered in', $q$
    UPDATE procurement.goods_receipt_lines SET uom_conversion_id = md5('qa:unit:SOFA-OLD')::uuid WHERE id = md5('qa:pk:grl')::uuid $q$);
SELECT pg_temp.expect_fail('K34 a stock package in a retired unit, made in the warehouse', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA-LPN-34', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-OLD')::uuid, 10, 'HCM-RCV01') $q$);
SELECT pg_temp.expect_fail('K35 a stock package in a retired unit its receipt line was not received in', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, goods_receipt_line_id)
    VALUES (gen_random_uuid(), 'QA-LPN-35', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-OLD')::uuid, 10, 'HCM-RCV01', md5('qa:pk:grl')::uuid) $q$);
SELECT pg_temp.expect_ok('K36 cartons received in the old unit before it was retired are still packed in it', $q$
    UPDATE inventory.uom_conversions SET active = TRUE WHERE id = md5('qa:unit:SOFA-OLD')::uuid;
    UPDATE procurement.goods_receipt_lines SET uom_conversion_id = md5('qa:unit:SOFA-OLD')::uuid WHERE id = md5('qa:pk:grl')::uuid;
    UPDATE inventory.uom_conversions SET active = FALSE WHERE id = md5('qa:unit:SOFA-OLD')::uuid;
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, goods_receipt_line_id)
    VALUES (gen_random_uuid(), 'QA-LPN-36', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-OLD')::uuid, 10, 'HCM-RCV01', md5('qa:pk:grl')::uuid) $q$);

\echo '--- a unit a document counts in keeps its factor (V20261020000600)'
SELECT pg_temp.expect_true('F1 the carton unit is in use', $c$ inventory.unit_in_use(md5('qa:unit:SOFA-CARTON')::uuid) $c$);
SELECT pg_temp.expect_true('F2 the case unit is not', $c$ NOT inventory.unit_in_use(md5('qa:unit:SOFA-CASE')::uuid) $c$);
SELECT pg_temp.expect_fail('F3 the factor of a unit in use changed (BR-10)', $q$
    UPDATE inventory.uom_conversions SET factor = 12 WHERE id = md5('qa:unit:SOFA-CARTON')::uuid $q$);
SELECT pg_temp.expect_ok('F4 the factor of an unused unit changed', $q$
    UPDATE inventory.uom_conversions SET factor = 6 WHERE id = md5('qa:unit:SOFA-CASE')::uuid $q$);
SELECT pg_temp.expect_fail('F5 a unit in use deleted', $q$
    DELETE FROM inventory.uom_conversions WHERE id = md5('qa:unit:SOFA-CARTON')::uuid $q$);
SELECT pg_temp.expect_fail('F5a a unit in use moved to another item', $q$
    UPDATE inventory.uom_conversions SET inventory_item_id = md5('demo:item:TABLE-OAK-160')::uuid
     WHERE id = md5('qa:unit:SOFA-CARTON')::uuid $q$);
SELECT pg_temp.expect_fail('F5b a unit in use renamed', $q$
    UPDATE inventory.uom_conversions SET unit_of_measure = 'BOX' WHERE id = md5('qa:unit:SOFA-CARTON')::uuid $q$);
SELECT pg_temp.expect_ok('F5c an unused unit renamed', $q$
    UPDATE inventory.uom_conversions SET unit_of_measure = 'PACK' WHERE id = md5('qa:unit:SOFA-CASE')::uuid $q$);
SELECT pg_temp.expect_true('F6 an item with stock packages is in use', $c$
    inventory.item_in_use(md5('demo:item:SOFA-3S-GREY')::uuid, 'SOFA-3S-GREY') $c$);

\echo '--- documents name their carton; a carton holds no more than was counted; one row per carton (V20261022000100)'
SELECT pg_temp.expect_ok('D1 an opened carton counted in at 6, holding 6, on HCM-A01-1-A', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status, unopened)
    VALUES (md5('qa:pk:doc')::uuid, 'QA-LPN-D1', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa:unit:SOFA-CARTON')::uuid, 10, 6, 'HCM-A01-1-A', 'IN_STOCK', FALSE);
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (md5('qa:pk:doc:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 6, 0, 'AVAILABLE', NOW(), NOW() - interval '3 day', md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_fail('D2 an adjustment naming a carton of another SKU', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'QA-ADJ-D2', 'HCM-A01-1-A', 'TABLE-OAK-160', -1, 'DAMAGED',
            (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_fail('D3 an adjustment naming a carton at another bin', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'QA-ADJ-D3', 'HCM-A01-1-B', 'SOFA-3S-GREY', -1, 'DAMAGED',
            (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_ok('D4 an adjustment of that carton', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'QA-ADJ-D4', 'HCM-A01-1-A', 'SOFA-3S-GREY', -1, 'DAMAGED',
            (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_ok('D5 a count with a line for the carton and one for the loose slot of the same bin', $q$
    INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type)
    SELECT md5('qa:cc:doc')::uuid, 'QA-CC-D5', id, 'MANUAL' FROM warehouse.warehouse WHERE prefix = 'HCM';
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:cc:doc')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 6, md5('qa:pk:doc')::uuid),
           (gen_random_uuid(), md5('qa:cc:doc')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 0, NULL) $q$);
SELECT pg_temp.expect_fail('D6 the same carton counted twice in one count', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:cc:doc')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 6, md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_fail('D7 an opened carton filled past what was counted into it', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A01-1-A', 1, 0, 'AVAILABLE', NOW(), NOW() - interval '2 day', md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_true('D8 the balance view reads the carton as one row', $c$
    (SELECT lpn || ':' || on_hand || ':' || reserved || ':' || available || ':' || unit_of_measure
       FROM inventory.stock_package_balance WHERE stock_package_id = md5('qa:pk:doc')::uuid) = 'QA-LPN-D1:6:0:6:CARTON' $c$);

\echo '--- permissions (V20261020000700)'
SELECT pg_temp.expect_true('P1 SYSTEM_ADMIN holds every inventory-stock-packages permission', $c$
    (SELECT count(*) FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE r.code = 'SYSTEM_ADMIN' AND p.resource = 'inventory-stock-packages') = 4 $c$);
SELECT pg_temp.expect_true('P2 warehouse staff cannot UPDATE (mark one LOST)', $c$
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE r.code = 'WAREHOUSE_STAFF' AND p.code = 'inventory-stock-packages:UPDATE') $c$);

ROLLBACK;
