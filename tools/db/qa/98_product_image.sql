-- =============================================================================
-- Adversarial checks + happy paths: the product's own image, one per product (V20270106000100, ADR-0012).
-- All of the image or none of it: ck_products_image.
-- Needs the demo seed (any product). Rolled back at the end.
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

-- The same, and it is the named rule that refuses it: a statement that breaks something else proves nothing.
CREATE FUNCTION pg_temp.expect_fail_by(label TEXT, rule TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM NOT LIKE '%' || rule || '%' THEN
            RAISE EXCEPTION 'FAIL  %: refused, but not by %: %', label, rule, SQLERRM;
        END IF;
        RAISE NOTICE 'ok    %  [%]', label, rule;
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


CREATE TEMP TABLE qa_product AS SELECT id FROM product.products ORDER BY id LIMIT 1;

-- The UPDATE writing a whole, valid image; `col` (optional) is given `val` instead of its valid value.
CREATE FUNCTION pg_temp.with_image(col TEXT DEFAULT NULL, val TEXT DEFAULT NULL) RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
    valid JSONB := jsonb_build_object(
        'image_key', quote_literal('product-originals/2026/11/27/a.png'),
        'image_original_name', quote_literal('a.png'),
        'image_content_type', quote_literal('image/png'),
        'image_size_bytes', '10',
        'image_stored_at', 'now()',
        'image_checksum', 'repeat(''a'', 64)',
        'image_renditions', quote_literal('[{"edge":256,"width":256,"height":192,"file":{"key":"product-renditions/2026/11/27/b.jpg"}}]'),
        'image_alt_text', 'NULL');
    assignments TEXT;
BEGIN
    IF col IS NOT NULL THEN
        valid := valid || jsonb_build_object(col, val);
    END IF;
    SELECT string_agg(format('%I = %s', key, value), ', ') INTO assignments FROM jsonb_each_text(valid);
    RETURN format('UPDATE product.products SET %s WHERE id = %L', assignments, (SELECT id FROM qa_product));
END $$;

-- --- happy paths ---------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_true('a product starts without an image',
    (SELECT image_key IS NULL AND image_renditions = '[]'::jsonb FROM product.products WHERE id = (SELECT id FROM qa_product)));
SELECT pg_temp.expect_ok('a whole image is accepted', pg_temp.with_image());
SELECT pg_temp.expect_ok('with its alt text', pg_temp.with_image('image_alt_text', quote_literal('Mặt trước')));
SELECT pg_temp.expect_ok('and taken off whole', format($f$UPDATE product.products SET image_key = NULL,
        image_original_name = NULL, image_content_type = NULL, image_size_bytes = NULL, image_stored_at = NULL,
        image_checksum = NULL, image_renditions = '[]', image_alt_text = NULL WHERE id = %L$f$, (SELECT id FROM qa_product)));

-- --- refused --------------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail_by('an image with no rendition', 'ck_products_image',
    pg_temp.with_image('image_renditions', '''[]'''));
SELECT pg_temp.expect_fail_by('an original outside product-originals/', 'ck_products_image',
    pg_temp.with_image('image_key', '''product-images/2026/a.png'''));
SELECT pg_temp.expect_fail_by('an image without its checksum', 'ck_products_image',
    pg_temp.with_image('image_checksum', 'NULL'));
SELECT pg_temp.expect_fail_by('a checksum that is not SHA-256 hex', 'ck_products_image',
    pg_temp.with_image('image_checksum', 'repeat(''G'', 64)'));
SELECT pg_temp.expect_fail_by('an empty file', 'ck_products_image',
    pg_temp.with_image('image_size_bytes', '0'));
SELECT pg_temp.expect_fail_by('an image without its name', 'ck_products_image',
    pg_temp.with_image('image_original_name', 'NULL'));
SELECT pg_temp.expect_fail_by('renditions that are not an array', 'ck_products_image',
    pg_temp.with_image('image_renditions', '''{}'''));
SELECT pg_temp.expect_fail_by('alt text with no image', 'ck_products_image', format(
    $f$UPDATE product.products SET image_alt_text = 'x' WHERE id = %L$f$, (SELECT id FROM qa_product)));
SELECT pg_temp.expect_fail_by('renditions with no image', 'ck_products_image', format(
    $f$UPDATE product.products SET image_renditions = '[{"edge":1}]' WHERE id = %L$f$, (SELECT id FROM qa_product)));
SELECT pg_temp.expect_fail_by('half an image: a key alone', 'ck_products_image', format(
    $f$UPDATE product.products SET image_key = 'product-originals/a.png' WHERE id = %L$f$, (SELECT id FROM qa_product)));

ROLLBACK;
