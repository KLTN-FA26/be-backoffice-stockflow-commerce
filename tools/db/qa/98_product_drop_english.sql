-- =============================================================================
-- The product is described in Vietnamese only (V20270106000200): its English name and description are gone.
-- Needs the demo seed (any product). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

SELECT pg_temp.expect_true('product.products has no English name any more', NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'product' AND table_name = 'products' AND column_name = 'name_en'));
SELECT pg_temp.expect_true('nor an English description', NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'product' AND table_name = 'products' AND column_name = 'description_en'));
SELECT pg_temp.expect_true('no SQL function still names them', NOT EXISTS (
    SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
      AND (p.prosrc ~ '\mname_en\M' OR p.prosrc ~ '\mdescription_en\M')));
-- A product is still written with its Vietnamese details alone.
UPDATE product.products SET name = 'Ly giấy QA', description = 'Mô tả QA'
WHERE id = (SELECT id FROM product.products ORDER BY id LIMIT 1);
SELECT pg_temp.expect_true('a product row is written without them',
    EXISTS (SELECT 1 FROM product.products WHERE name = 'Ly giấy QA' AND description = 'Mô tả QA'));

ROLLBACK;
