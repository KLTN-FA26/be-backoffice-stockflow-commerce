-- =============================================================================
-- Adversarial checks + happy paths: one stock policy for every SKU (V20261115000100, team plan 5-5; kltn-docs 01
-- BR-17, 03 BR-03): lot-tracked, no serial numbers, first in first out by the day of receipt. Before it the checks of
-- inventory.enforce_sku_policy ran only for an item someone had "configured", so most items were checked by nothing.
-- Needs the demo seed (the demo sofa, never configured, and the oak table, which was). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail_like(label TEXT, fragment TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints and constraint triggers fire here, still inside the sub-transaction.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- live stock carries a lot: every SKU, configured or not'
SELECT pg_temp.expect_fail_like('FP1 live stock of the demo sofa (never configured) with no lot', 'needs a lot number', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A01-1-A', 5, 0, 'AVAILABLE', NOW(), NOW() - interval '1 hour') $q$);
SELECT pg_temp.expect_fail_like('FP2 live stock of the oak table (configured before) with no lot', 'needs a lot number', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-A01-1-A', 5, 0, 'AVAILABLE', NOW(), NOW() - interval '1 hour') $q$);
SELECT pg_temp.expect_fail_like('FP3 a blank lot is no lot', 'needs a lot number', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A01-1-A', '   ', 5, 0, 'AVAILABLE', NOW(), NOW() - interval '1 hour') $q$);
-- A row at zero needs one too: lot_number is NOT NULL for every row since V20261113000300 (4C-3, 20 BR-22), which the
-- trigger's "nothing is on hand" allowance of 5-5 no longer reaches.
SELECT pg_temp.expect_fail_like('FP4 a row at zero with no lot', 'lot_number', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at)
    VALUES (md5('qa70:zero')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-B', 0, 0, 'AVAILABLE', NOW(), NOW() - interval '2 hour') $q$);
-- One carton of 5 for each SKU: live stock sits in a carton (ck_stock_item_whole_cartons).
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
SELECT md5('qa70:u:' || i.sku)::uuid, i.id, 'QA_QA70', 5 FROM inventory.inventory_items i WHERE i.sku IN ('SOFA-3S-GREY', 'TABLE-OAK-160');
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status)
SELECT md5('qa70:p:' || i.sku)::uuid, 'QA-QA70-' || left(i.sku, 4), i.id, md5('qa70:u:' || i.sku)::uuid, 5, 5, 'HCM-A01-1-A', 'IN_STOCK'
  FROM inventory.inventory_items i WHERE i.sku IN ('SOFA-3S-GREY', 'TABLE-OAK-160');
SELECT pg_temp.expect_ok('FP6 live stock with its lot, of either SKU', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (md5('qa70:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'QA70-LOT', 5, 0, 'AVAILABLE', NOW(),
            NOW() - interval '1 hour', md5('qa70:p:SOFA-3S-GREY')::uuid),
           (md5('qa70:row2')::uuid, 'TABLE-OAK-160', 'HCM-A01-1-A', 'QA70-LOT', 5, 0, 'AVAILABLE', NOW(),
            NOW() - interval '1 hour', md5('qa70:p:TABLE-OAK-160')::uuid) $q$);
SELECT pg_temp.expect_fail_like('FP7 a receipt time in the future', 'Invalid receipt date', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A01-1-A', 'QA70-FUT', 1, 0, 'AVAILABLE', NOW(), NOW() + interval '1 day') $q$);

\echo '--- a row keeps its identity; a layer is raised only by the application naming it'
SELECT pg_temp.expect_fail_like('FP8 the lot of a row changed in place', 'identity is immutable', $q$
    UPDATE inventory.stock_item SET lot_number = 'QA70-OTHER' WHERE id = md5('qa70:row')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP9 the receipt time of a row changed in place', 'identity is immutable', $q$
    UPDATE inventory.stock_item SET received_at = received_at - interval '1 day' WHERE id = md5('qa70:row')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP10 the SKU of a row changed in place', 'identity is immutable', $q$
    UPDATE inventory.stock_item SET sku = 'TABLE-OAK-160' WHERE id = md5('qa70:row')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP11 a layer raised in place (first in, first out: new units are a new layer)',
    'new receipt layer', $q$
    UPDATE inventory.stock_item SET on_hand = on_hand + 1 WHERE id = md5('qa70:row')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP12 the application named another row for the statement', 'new receipt layer', $q$
    SET LOCAL stockflow.same_receipt_merge = '00000000-0000-4000-8000-000000000070';
    UPDATE inventory.stock_item SET on_hand = on_hand + 1 WHERE id = md5('qa70:row')::uuid $q$);
-- A carton row is whole (20 BR-18): since 4C-3 the merge of a receipt into a layer in place cannot grow it, whoever names it.
SELECT pg_temp.expect_fail_like('FP13 the application named this row, but a carton in stock is whole: no unit is merged into it', 'a carton in stock is whole', $q$
    SET LOCAL stockflow.same_receipt_merge = 'd79905cd-5752-0db5-f34c-e5abdc6cfbfa';
    UPDATE inventory.stock_item SET on_hand = on_hand + 1 WHERE id = md5('qa70:row')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP14 ... and no other row rides on that', 'new receipt layer', $q$
    UPDATE inventory.stock_item SET on_hand = on_hand + 1 WHERE id = md5('qa70:row2')::uuid $q$);
SELECT pg_temp.expect_ok('FP15 a layer that does not grow is free: a hold, then released', $q$
    UPDATE inventory.stock_item SET reserved = 2 WHERE id = md5('qa70:row')::uuid;
    UPDATE inventory.stock_item SET reserved = 0 WHERE id = md5('qa70:row')::uuid $q$);

\echo '--- no serial numbers, no per-item policy; the layer keys without the serial'
SELECT pg_temp.expect_true('FP16 the policy columns of the item and the serial of stock are gone', (
    SELECT count(*) FROM information_schema.columns
     WHERE (table_schema, table_name, column_name) IN (('inventory', 'inventory_items', 'lot_tracked'),
           ('inventory', 'inventory_items', 'serial_tracked'), ('inventory', 'inventory_items', 'removal_strategy'),
           ('inventory', 'inventory_items', 'policy_configured'), ('inventory', 'stock_item', 'serial_number'))) = 0);
SELECT pg_temp.expect_true('FP17 their CHECKs and the live-serial index are gone', (
    SELECT count(*) FROM pg_constraint WHERE conname IN ('ck_inventory_control_removal', 'ck_stock_serial_quantity')) = 0
    AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'uk_stock_item_live_serial'));
-- Kept by 5-5 for their own packages, both went with them earlier in version order: the packing station's scans
-- (V20261114000300, 4C-7) and the damage reports (V20261114000400, 5-6).
SELECT pg_temp.expect_true('FP18 no serial left anywhere: the packing station (4C-7) and damage reports (5-6) are gone', (
    SELECT count(*) FROM information_schema.columns
     WHERE (table_schema, table_name, column_name) IN (('fulfillment', 'package_scan', 'serial_number'),
           ('inventory', 'stock_damage_report', 'serial_number'))) = 0);
SELECT pg_temp.expect_true('FP19 the layer keys read the lot and the receipt time, not a serial', (
    SELECT bool_and(indexdef LIKE '%lot_number%' AND indexdef LIKE '%received_at%' AND indexdef NOT LIKE '%serial%')
      FROM pg_indexes WHERE schemaname = 'inventory'
       AND indexname IN ('uk_stock_item_sku_location_lot', 'uk_stock_item_package_layer')));
SELECT pg_temp.expect_ok('FP19b a loose row at zero, of the same SKU, place, lot and receipt time as the carton row', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at)
    SELECT md5('qa70:loose0')::uuid, sku, location_code, lot_number, 0, 0, 'AVAILABLE', NOW(), received_at
      FROM inventory.stock_item WHERE id = md5('qa70:row')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP20 two loose layers (emptied: the only loose ones left) of one SKU, place, lot and receipt time', 'uk_stock_item_sku_location_lot', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at)
    SELECT gen_random_uuid(), sku, location_code, lot_number, 0, 0, 'AVAILABLE', NOW(), received_at
      FROM inventory.stock_item WHERE id = md5('qa70:row')::uuid $q$);
SELECT pg_temp.expect_ok('FP21 ... but a later receipt of the same lot is a layer of its own', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at)
    SELECT gen_random_uuid(), sku, location_code, lot_number, 0, 0, 'AVAILABLE', NOW(), received_at + interval '1 minute'
      FROM inventory.stock_item WHERE id = md5('qa70:row')::uuid $q$);

\echo '--- every goods receipt line names its lot (03 BR-03)'
SELECT pg_temp.expect_true('FP22 the line check asks a lot of every line, not only a lot-tracked item''s', (
    SELECT position('btrim(NEW.lot_number)' IN prosrc) > 0 AND position('lot_tracked' IN prosrc) = 0   -- (LIKE would read _ as a wildcard)
      FROM pg_proc WHERE proname = 'check_receivable_item' AND pronamespace = 'inventory'::regnamespace));
SELECT pg_temp.expect_true('FP23 the stock policy function reads no column of the item''s old policy', (
    SELECT position('policy_configured' IN prosrc) = 0 AND position('removal_strategy' IN prosrc) = 0
           AND position('serial' IN prosrc) = 0
      FROM pg_proc WHERE proname = 'enforce_sku_policy' AND pronamespace = 'inventory'::regnamespace));

ROLLBACK;
\echo 'PASS 70_fixed_stock_policy'
