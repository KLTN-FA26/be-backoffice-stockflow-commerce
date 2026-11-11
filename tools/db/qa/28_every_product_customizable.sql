-- =============================================================================
-- Every product is CUSTOMIZABLE (V20261116000200, team plan 5-9). Needs the demo seed; rolled back.
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

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    RAISE NOTICE 'ok    %', label;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

SELECT pg_temp.expect_true('PK1 no product is STANDARD, the demo ones included',
    NOT EXISTS (SELECT 1 FROM product.products WHERE kind <> 'CUSTOMIZABLE'));
SELECT pg_temp.expect_true('PK2 the demo sofa was converted by the migration (its version moved)',
    (SELECT kind = 'CUSTOMIZABLE' AND version >= 1 FROM product.products WHERE code = 'SOFA-3S'));
SELECT pg_temp.expect_fail('PK3 a product changed to STANDARD', $q$
    UPDATE product.products SET kind = 'STANDARD' WHERE code = 'SOFA-3S' $q$);
SELECT pg_temp.expect_fail('PK4 a product inserted as STANDARD', $q$
    INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at)
    VALUES (gen_random_uuid(), 'QA28STD', 'QA standard', 'qa28-std', 'DRAFT', 'STANDARD', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('PK5 a product with no kind at all', $q$
    INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at)
    VALUES (gen_random_uuid(), 'QA28NUL', 'QA null', 'qa28-nul', 'DRAFT', NULL, 0, NOW()) $q$);
SELECT pg_temp.expect_ok('PK6 a product inserted without a kind is CUSTOMIZABLE', $q$
    INSERT INTO product.products (id, code, name, slug, status, version, created_at)
    VALUES (md5('qa28:product')::uuid, 'QA28DEF', 'QA default', 'qa28-def', 'DRAFT', 0, NOW()) $q$);
SELECT pg_temp.expect_true('PK7 ... and says so', (SELECT kind = 'CUSTOMIZABLE' FROM product.products
    WHERE id = md5('qa28:product')::uuid));
SELECT pg_temp.expect_ok('PK8 a print template hangs on any product''s variant: no kind to refuse it any more', $q$
    INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                                  created_at)
    VALUES (md5('qa28:variant')::uuid, md5('qa28:product')::uuid, 'QA28-BLANK', 'QA', 'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW());
    INSERT INTO product.customization_templates (id, variant_id, code, name, allowed_file_formats, max_file_size_mb)
    VALUES (md5('qa28:template')::uuid, md5('qa28:variant')::uuid, 'T28', 'x', ARRAY['image/png'], 10) $q$);

ROLLBACK;
\echo 'PASS 28_every_product_customizable'
