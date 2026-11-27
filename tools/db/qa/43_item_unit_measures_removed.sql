-- =============================================================================
-- Adversarial checks + happy paths: an inventory item carries no physical measure of its own (V20261215000100;
-- kltn-docs 01 §3, BR-03, C21). The carton unit still does. Needs the demo seed (the blue demo cup). Rolled back at
-- the end. Receiving asks the carton, not the item: QA 22 receives an item that has no measure (PR18) and refuses a
-- carton without its weight (PR17b).
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

\echo '--- the item keeps only what identifies and handles it'
SELECT pg_temp.expect_true('IM1 none of the ten measure columns is left on inventory_items',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'inventory' AND table_name = 'inventory_items'
                   AND column_name IN ('weight_kg', 'length_cm', 'width_cm', 'height_cm', 'package_weight_kg',
                                       'package_length_cm', 'package_width_cm', 'package_height_cm', 'package_count',
                                       'pack_size')));
SELECT pg_temp.expect_true('IM2 unit_of_measure and barcode stay; rotatable moved to the variant (V20270104000200)',
    (SELECT count(*) FROM information_schema.columns
      WHERE table_schema = 'inventory' AND table_name = 'inventory_items'
        AND column_name IN ('unit_of_measure', 'barcode', 'rotatable')) = 2
    AND EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'product' AND table_name = 'variants' AND column_name = 'rotatable'));
SELECT pg_temp.expect_true('IM3 their CHECKs went with them',
    NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'inventory.inventory_items'::regclass
                   AND conname IN ('ck_inventory_items_weight', 'ck_inventory_items_size',
                                   'ck_inventory_items_package_weight', 'ck_inventory_items_package_size',
                                   'ck_inventory_items_package_count', 'ck_inventory_items_pack_size')));
SELECT pg_temp.expect_true('IM4 no function body still names a dropped item column',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                   AND p.prosrc ~ 'inventory_items'
                   AND p.prosrc ~ '\m(item|i)\.(weight_kg|length_cm|width_cm|height_cm|package_[a-z_]+|pack_size)\M'));
SELECT pg_temp.expect_fail('IM5 an item weight written by an old client', $q$
    UPDATE inventory.inventory_items SET weight_kg = 0.02 WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH' $q$);

\echo '--- the carton keeps its measures, and their rules'
SELECT pg_temp.expect_ok('IM6 the carton of the blue cup measured', $q$
    UPDATE inventory.uom_conversions SET length_cm = 60, width_cm = 50, height_cm = 40, weight_kg = 6
     WHERE active
       AND variant_id = (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH') $q$);
SELECT pg_temp.expect_fail('IM7 a carton of no weight (ck_uom_conversions_weight)', $q$
    UPDATE inventory.uom_conversions SET weight_kg = 0
     WHERE active
       AND variant_id = (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH') $q$);
SELECT pg_temp.expect_true('IM8 no base unit row is left to give a weight to: the base unit is the item''s own (V20261226000200, V20261229000100)',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'inventory' AND table_name = 'uom_conversions' AND column_name = 'is_base'));
ROLLBACK;
\echo 'PASS 43_item_unit_measures_removed'
