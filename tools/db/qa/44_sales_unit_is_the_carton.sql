-- =============================================================================
-- Adversarial checks: a variant's sales unit is its item's active carton, read and never listed (V20261226000100;
-- kltn-docs 01 §3 "Đơn vị bán", BR-11). Needs the demo seed (the blue demo cup). Rolled back at the end.
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

\echo '--- the list of sales units is gone, with what guarded it'
SELECT pg_temp.expect_true('SU1 product.variant_sales_units is dropped',
    to_regclass('product.variant_sales_units') IS NULL);
SELECT pg_temp.expect_true('SU2 its trigger function went with it',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'product' AND p.proname = 'check_sales_unit_of_item'));
SELECT pg_temp.expect_true('SU3 no function body still names the table',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND p.prosrc ~ 'variant_sales_units'));
SELECT pg_temp.expect_true('SU4 no foreign key still points at it or from it',
    NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname LIKE '%variant_sales_units%'));
SELECT pg_temp.expect_fail('SU5 a sales unit written by an old client', $q$
    INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id)
    SELECT gen_random_uuid(), v.id, u.id
      FROM product.variants v
      JOIN inventory.inventory_items i ON i.sku = v.sku
      JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id AND u.active
     WHERE v.sku = 'LY-GIAY-DEMO-12OZ-XANH' LIMIT 1 $q$);

\echo '--- the carton it stood for is retired and replaced with nothing in the way (01 BR-10)'
SELECT pg_temp.expect_ok('SU6 the active carton of the blue cup retired', $q$
    UPDATE inventory.uom_conversions SET active = FALSE, default_shipping = FALSE
     WHERE active AND unit_of_measure <> 'EACH'
       AND inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH') $q$);
SELECT pg_temp.expect_ok('SU7 and a new carton specification declared in its place', $q$
    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, weight_kg, length_cm,
                                           width_cm, height_cm, active)
    SELECT gen_random_uuid(), id, 'QA44THUNG', 500, 5, 50, 40, 30, TRUE
      FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH' $q$);
ROLLBACK;
\echo 'PASS 44_sales_unit_is_the_carton'
