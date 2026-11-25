-- =============================================================================
-- Adversarial checks: inventory.uom_conversions has no is_base and no default_shipping (V20261229000100). An item's
-- units are its carton specifications and one of them is active (kltn-docs 01 BR-03): that one is received, sold and
-- shipped in, so there is no unit to mark for shipping and no base row to flag. What the columns took with them must
-- still hold: one active carton per item (the index named it), a unit a document uses keeps its code and factor (the
-- trigger's WHEN named it), a carton is made only in a live unit of its own count (the function named it). Needs the
-- demo seed (the blue demo cup, whose THUNG carton holds stock). Rolled back at the end.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- the columns are gone, and nothing names them'
SELECT pg_temp.expect_true('UF1 uom_conversions has neither is_base nor default_shipping',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'inventory' AND table_name = 'uom_conversions'
                   AND column_name IN ('is_base', 'default_shipping')));
SELECT pg_temp.expect_true('UF2 nor any constraint or index that held them',
    NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname IN ('ck_uom_conversions_no_base',
                                                              'ck_uom_conversions_default_shipping',
                                                              'ck_uom_conversions_base'))
    AND to_regclass('inventory.uk_uom_conversions_default_shipping') IS NULL
    AND to_regclass('inventory.uk_uom_conversions_base') IS NULL);
SELECT pg_temp.expect_true('UF3 no function body names them (a body naming a dropped column fails at its next call)',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                   AND (p.prosrc ~ '\mis_base\M' OR p.prosrc ~ '\mdefault_shipping\M')));
SELECT pg_temp.expect_fail('UF4 an old script marking the default shipping unit', $q$
    UPDATE inventory.uom_conversions SET default_shipping = TRUE
     WHERE active AND inventory_item_id = (SELECT id FROM inventory.inventory_items
                                            WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH') $q$);

\echo '--- one active carton per item still (01 BR-03): the index was rebuilt on active alone'
SELECT pg_temp.expect_true('UF5 uk_uom_conversions_one_carton is unique on the item, where active',
    (SELECT i.indisunique AND pg_get_expr(i.indpred, i.indrelid) = 'active'
       FROM pg_index i WHERE i.indexrelid = 'inventory.uk_uom_conversions_one_carton'::regclass));
SELECT pg_temp.expect_fail('UF6 a second active carton beside the blue cup''s THUNG (uk_uom_conversions_one_carton)', $q$
    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, weight_kg, length_cm,
                                           width_cm, height_cm, active)
    SELECT md5('qa46:second')::uuid, id, 'QA46', 500, 5, 50, 40, 30, TRUE
      FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH' $q$);
SELECT pg_temp.expect_ok('UF7 a retired carton beside it, as history (BR-10)', $q$
    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, weight_kg, length_cm,
                                           width_cm, height_cm, active)
    SELECT md5('qa46:retired')::uuid, id, 'QA46OLD', 500, 5, 50, 40, 30, FALSE
      FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH' $q$);
SELECT pg_temp.expect_fail('UF8 that retired carton made active again beside the active one', $q$
    UPDATE inventory.uom_conversions SET active = TRUE WHERE id = md5('qa46:retired')::uuid $q$);

\echo '--- a unit a document uses keeps its code and factor (BR-10): the trigger was rebuilt without is_base'
SELECT pg_temp.expect_true('UF9 the blue cup''s THUNG is used by its cartons',
    (SELECT inventory.unit_in_use(u.id) FROM inventory.uom_conversions u
       JOIN inventory.inventory_items i ON i.id = u.inventory_item_id
      WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND u.active));
SELECT pg_temp.expect_fail('UF10 its code renamed (tg_uom_conversions_locked_in_use)', $q$
    UPDATE inventory.uom_conversions SET unit_of_measure = 'THUNG2'
     WHERE active AND inventory_item_id = (SELECT id FROM inventory.inventory_items
                                            WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH') $q$);
SELECT pg_temp.expect_fail('UF11 its factor changed (tg_uom_conversions_locked_in_use)', $q$
    UPDATE inventory.uom_conversions SET factor = 999
     WHERE active AND inventory_item_id = (SELECT id FROM inventory.inventory_items
                                            WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH') $q$);
SELECT pg_temp.expect_ok('UF12 its size changed: that is not what a document counts in', $q$
    UPDATE inventory.uom_conversions SET height_cm = 51
     WHERE active AND inventory_item_id = (SELECT id FROM inventory.inventory_items
                                            WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH') $q$);
SELECT pg_temp.expect_ok('UF13 a retired unit no document uses renamed', $q$
    UPDATE inventory.uom_conversions SET unit_of_measure = 'QA46OLD2' WHERE id = md5('qa46:retired')::uuid $q$);

\echo '--- a carton is made only in a live unit of its own count: check_stock_package_created, rewritten'
SELECT pg_temp.expect_fail('UF14 a carton made in a retired unit with no receipt line in it', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    SELECT gen_random_uuid(), 'QA46-LPN-1', id, md5('qa46:retired')::uuid, 500, 'QA46-NOWHERE'
      FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH' $q$);
SELECT pg_temp.expect_fail('UF15 a carton holding other than its unit''s factor', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    SELECT gen_random_uuid(), 'QA46-LPN-2', i.id, u.id, 999, 'QA46-NOWHERE'
      FROM inventory.inventory_items i JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id
     WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND u.active $q$);
-- check_goods_receipt_line (the line's carton holds the production order line's count) is exercised by QA 22, PR15.
ROLLBACK;
\echo 'PASS 46_uom_flag_columns_dropped'
