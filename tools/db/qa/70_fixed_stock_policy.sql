-- =============================================================================
-- Adversarial checks + happy paths: one stock policy for every SKU (V20261115000100, team plan 5-5; kltn-docs 01
-- BR-17, 03 BR-03): lot-tracked, no serial numbers, first in first out by the day of receipt. The policy was a trigger
-- on the stock rows (inventory.enforce_sku_policy); since V20261231000600 (ADR-0011) the carton is the stock and the
-- same policy is the carton's: a lot once it is in stock, a lot and a receipt date that never change.
-- Needs the demo seed (two cups, stocked in lots and cartons, none carrying a policy flag: FP16); rolled back at the end.
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
-- One carton of 5 for both SKUs. 01 BR-03: a variant has ONE active carton, and both cups are packed in their
-- variant's (V20270104000100); the demo seed's own is retired.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND variant_id = (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH');
INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor)
SELECT md5('qa70:u')::uuid, i.variant_id, 'QA_QA70', 5 FROM inventory.inventory_items i WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH';
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
SELECT md5('qa70:lot:' || i.sku)::uuid, i.sku, 'QA70-LOT', NOW() - interval '1 hour', 'UNTRACED', NOW()
  FROM inventory.inventory_items i WHERE i.sku IN ('LY-GIAY-DEMO-12OZ-XANH', 'LY-GIAY-DEMO-12OZ-DO');
CREATE FUNCTION pg_temp.carton_sql(sku TEXT, lpn TEXT, lot TEXT, received TEXT) RETURNS TEXT LANGUAGE sql AS $f$
    SELECT format($i$
        INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                             location_code, status, stock_status, lot_id, received_at)
        SELECT md5('qa70:p:' || %L)::uuid, %L, i.id, md5('qa70:u')::uuid, 5, NOW(), 'HCM-A01-1-A', 'IN_STOCK',
               'AVAILABLE', %s, %s
          FROM inventory.inventory_items i WHERE i.sku = %L $i$, lpn, lpn, lot, received, sku)
$f$;
SELECT pg_temp.expect_fail_like('FP1 a carton of the blue demo cup in stock with no lot', 'ck_stock_package_lot',
    pg_temp.carton_sql('LY-GIAY-DEMO-12OZ-XANH', 'QA-QA70-NOLOT', 'NULL', 'NOW() - interval ''1 hour'''));
SELECT pg_temp.expect_fail_like('FP2 a carton of the red demo cup in stock with no lot', 'ck_stock_package_lot',
    pg_temp.carton_sql('LY-GIAY-DEMO-12OZ-DO', 'QA-QA70-NOLOT', 'NULL', 'NOW() - interval ''1 hour'''));
-- FP3 ("a blank lot is no lot") and FP4 ("a row at zero with no lot") were ck_stock_item_lot and the policy trigger on
-- the stock row's own lot_number. The carton names its lot by id; the lot number is inventory.stock_lot's.
SELECT pg_temp.expect_ok('FP6 live stock with its lot, of either SKU', pg_temp.carton_sql('LY-GIAY-DEMO-12OZ-XANH',
    'QA-QA70-XANH', 'md5(''qa70:lot:LY-GIAY-DEMO-12OZ-XANH'')::uuid', 'NOW() - interval ''1 hour''') || ';'
    || pg_temp.carton_sql('LY-GIAY-DEMO-12OZ-DO', 'QA-QA70-DO', 'md5(''qa70:lot:LY-GIAY-DEMO-12OZ-DO'')::uuid',
                          'NOW() - interval ''1 hour'''));
SELECT pg_temp.expect_fail_like('FP7 a receipt time in the future', 'invalid receipt date',
    pg_temp.carton_sql('LY-GIAY-DEMO-12OZ-XANH', 'QA-QA70-FUT', 'md5(''qa70:lot:LY-GIAY-DEMO-12OZ-XANH'')::uuid',
                       'NOW() + interval ''1 day'''));

\echo '--- a carton keeps its identity: its lot, its receipt date and its item never change once it is in stock (FIFO)'
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
VALUES (md5('qa70:lot:other')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'QA70-OTHER', NOW() - interval '1 hour', 'UNTRACED', NOW());
SELECT pg_temp.expect_fail_like('FP8 the lot of a carton changed in place (to another lot of its own SKU)', 'never changed (FIFO)', $q$
    UPDATE inventory.stock_package SET lot_id = md5('qa70:lot:other')::uuid
     WHERE id = md5('qa70:p:QA-QA70-XANH')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP8a ... and to a lot of another SKU', 'but lot', $q$
    UPDATE inventory.stock_package SET lot_id = md5('qa70:lot:LY-GIAY-DEMO-12OZ-DO')::uuid
     WHERE id = md5('qa70:p:QA-QA70-XANH')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP9 the receipt time of a carton changed in place', 'never changed (FIFO)', $q$
    UPDATE inventory.stock_package SET received_at = received_at - interval '1 day'
     WHERE id = md5('qa70:p:QA-QA70-XANH')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP9a ... or taken away', 'never changed (FIFO)', $q$
    UPDATE inventory.stock_package SET received_at = NULL WHERE id = md5('qa70:p:QA-QA70-XANH')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP10 the item of a carton changed in place', 'immutable', $q$
    UPDATE inventory.stock_package
       SET inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-DO')
     WHERE id = md5('qa70:p:QA-QA70-XANH')::uuid $q$);
-- FP11 to FP14 (a layer raised in place; the same-receipt merge marker stockflow.same_receipt_merge) are gone with the
-- quantity: a carton cannot grow, so "replenishment is a new layer" has nothing to refuse and no marker to excuse.
SELECT pg_temp.expect_ok('FP15 a carton out of stock and back keeps its lot and its receipt date', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW()
     WHERE id = md5('qa70:p:QA-QA70-XANH')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'AVAILABLE', closed_at = NULL
     WHERE id = md5('qa70:p:QA-QA70-XANH')::uuid $q$);
SELECT pg_temp.expect_fail_like('FP15a ... and is not dated anew as it comes back', 'never changed (FIFO)', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW()
     WHERE id = md5('qa70:p:QA-QA70-XANH')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'AVAILABLE', closed_at = NULL,
           received_at = NOW() WHERE id = md5('qa70:p:QA-QA70-XANH')::uuid $q$);

\echo '--- no serial numbers, no per-item policy; the layer keys without the serial'
SELECT pg_temp.expect_true('FP16 the policy columns of the item and the serial of stock are gone', (
    SELECT count(*) FROM information_schema.columns
     WHERE (table_schema, table_name, column_name) IN (('inventory', 'inventory_items', 'lot_tracked'),
           ('inventory', 'inventory_items', 'serial_tracked'), ('inventory', 'inventory_items', 'removal_strategy'),
           ('inventory', 'inventory_items', 'policy_configured'), ('inventory', 'stock_package', 'serial_number'))) = 0);
SELECT pg_temp.expect_true('FP17 their CHECKs and the live-serial index are gone', (
    SELECT count(*) FROM pg_constraint WHERE conname IN ('ck_inventory_control_removal', 'ck_stock_serial_quantity')) = 0
    AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'uk_stock_item_live_serial'));
-- Kept by 5-5 for their own packages, both went with them earlier in version order: the packing station's scans
-- (V20261114000300, 4C-7) and the damage reports (V20261114000400, 5-6).
SELECT pg_temp.expect_true('FP18 no serial left anywhere: the packing station (4C-7) and damage reports (5-6) are gone', (
    SELECT count(*) FROM information_schema.columns
     WHERE (table_schema, table_name, column_name) IN (('fulfillment', 'package_scan', 'serial_number'),
           ('inventory', 'stock_damage_report', 'serial_number'))) = 0);
-- FP19 to FP21 (the keys of a loose layer: SKU, place, lot, receipt time) went with the stock rows and their indexes.
SELECT pg_temp.expect_true('FP19 there is no layer key left: a carton is one row, keyed by its LPN', (
    NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory'
                 AND indexname IN ('uk_stock_item_sku_location_lot', 'uk_stock_item_package_layer', 'uk_stock_item_package'))
    AND EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'uk_stock_package_lpn')));

\echo '--- every goods receipt line names its lot (03 BR-03)'
SELECT pg_temp.expect_true('FP22 the line check asks a lot of every line, not only a lot-tracked item''s', (
    SELECT position('btrim(NEW.lot_number)' IN prosrc) > 0 AND position('lot_tracked' IN prosrc) = 0   -- (LIKE would read _ as a wildcard)
      FROM pg_proc WHERE proname = 'check_receivable_item' AND pronamespace = 'inventory'::regnamespace));
SELECT pg_temp.expect_true('FP23 the stock policy function went with the stock rows, and its marker with it', (
    NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'enforce_sku_policy' AND pronamespace = 'inventory'::regnamespace)
    AND NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                     WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                       AND p.prosrc LIKE '%same_receipt_merge%')));

ROLLBACK;
\echo 'PASS 70_fixed_stock_policy'
