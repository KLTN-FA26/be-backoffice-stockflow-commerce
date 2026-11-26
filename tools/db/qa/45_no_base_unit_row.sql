-- =============================================================================
-- Adversarial checks: an item's base unit is its own unit_of_measure and has no row in uom_conversions (V20261226000200;
-- kltn-docs 01 §3 "Đơn vị cơ sở", BR-09). The units of an item are its carton specifications. Needs the demo seed (the
-- blue demo cup). Rolled back at the end.
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

\echo '--- no base unit row, and nothing that made one'
SELECT pg_temp.expect_true('BU1 no row of uom_conversions is a base unit: the flag itself is gone (V20261229000100, QA 46)',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'inventory' AND table_name = 'uom_conversions' AND column_name = 'is_base'));
SELECT pg_temp.expect_true('BU2 the triggers that made and renamed the base row are gone, with their functions',
    NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname IN ('tg_inventory_items_base_uom',
                                                          'tg_inventory_items_follow_base_uom'))
    AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname IN ('create_base_uom', 'follow_base_uom')));
SELECT pg_temp.expect_true('BU3 every item still names the unit its pieces are counted in',
    NOT EXISTS (SELECT 1 FROM inventory.inventory_items WHERE unit_of_measure IS NULL));
INSERT INTO product.products (id, code, name, slug) VALUES (md5('qa45:product')::uuid, 'QA45', 'QA 45', 'qa45');
SELECT pg_temp.expect_ok('BU4 a new item is made with its variant (tg_variant_inventory_item)', $q$
    INSERT INTO product.variants (id, product_id, sku, name, attribute_signature)
    VALUES (md5('qa45:variant')::uuid, md5('qa45:product')::uuid, 'QA45-ITEM', 'QA 45', '') $q$);
SELECT pg_temp.expect_true('BU5 ... its unit EACH on the item, nothing in uom_conversions',
    (SELECT i.unit_of_measure = 'EACH'
            AND NOT EXISTS (SELECT 1 FROM inventory.uom_conversions u WHERE u.variant_id = i.variant_id)
       FROM inventory.inventory_items i WHERE i.sku = 'QA45-ITEM'));
SELECT pg_temp.expect_fail('BU6 a base unit row written by an old script (no is_base column since V20261229000100)', $q$
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, is_base)
    SELECT gen_random_uuid(), variant_id, 'QA45BASE', 1, TRUE FROM inventory.inventory_items WHERE sku = 'QA45-ITEM' $q$);
SELECT pg_temp.expect_fail('BU7 a carton turned into a base unit (no is_base column since V20261229000100)', $q$
    UPDATE inventory.uom_conversions SET is_base = TRUE
     WHERE active AND variant_id = (SELECT variant_id FROM inventory.inventory_items
                                            WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH') $q$);

\echo '--- a packing unit is never called what its item''s pieces are counted in'
SELECT pg_temp.expect_fail('BU8 a carton called EACH on an item counted in EACH (tg_uom_conversions_unit_code)', $q$
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, length_cm,
                                           width_cm, height_cm, active)
    SELECT gen_random_uuid(), variant_id, 'EACH', 100, 5, 50, 40, 30, TRUE
      FROM inventory.inventory_items WHERE sku = 'QA45-ITEM' $q$);
SELECT pg_temp.expect_ok('BU9 a carton under its own code', $q$
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, length_cm,
                                           width_cm, height_cm, active)
    SELECT md5('qa45:carton')::uuid, variant_id, 'THUNG', 100, 5, 50, 40, 30, TRUE
      FROM inventory.inventory_items WHERE sku = 'QA45-ITEM' $q$);
SELECT pg_temp.expect_fail('BU10 that carton renamed to the item''s unit', $q$
    UPDATE inventory.uom_conversions SET unit_of_measure = 'EACH' WHERE id = md5('qa45:carton')::uuid $q$);
SELECT pg_temp.expect_fail('BU11 the item''s unit renamed to its carton''s code (tg_inventory_items_unit_code)', $q$
    UPDATE inventory.inventory_items SET unit_of_measure = 'THUNG' WHERE sku = 'QA45-ITEM' $q$);
SELECT pg_temp.expect_ok('BU12 the item''s unit renamed to a free code: nothing follows it, there is no row to', $q$
    UPDATE inventory.inventory_items SET unit_of_measure = 'PCS' WHERE sku = 'QA45-ITEM' $q$);
SELECT pg_temp.expect_true('BU13 ... and the carton is the item''s only unit',
    (SELECT count(*) FROM inventory.uom_conversions u JOIN inventory.inventory_items i ON i.variant_id = u.variant_id
      WHERE i.sku = 'QA45-ITEM') = 1);
ROLLBACK;
\echo 'PASS 45_no_base_unit_row'
