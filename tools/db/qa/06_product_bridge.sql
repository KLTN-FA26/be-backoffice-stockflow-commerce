-- =============================================================================
-- Adversarial checks + happy paths: the legacy-to-PIM product bridge (issue #68):
-- admin writes reach products, categories, product_categories. Needs the demo seed. Rolled back at the end.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, query TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE ok BOOLEAN;
BEGIN
    EXECUTE query INTO ok;
    IF ok IS NOT TRUE THEN RAISE EXCEPTION 'FAIL  %', label; END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- categories'
SELECT pg_temp.expect_ok('B1 legacy root and child category', $q$
    INSERT INTO product.category (id, code, name, created_at) VALUES (md5('qa:cat:cups')::uuid, 'cups', 'Ly', NOW());
    INSERT INTO product.category (id, code, name, parent_id, created_at)
    VALUES (md5('qa:cat:paper')::uuid, 'PAPER-CUPS', 'Ly giấy', md5('qa:cat:cups')::uuid, NOW()) $q$);
SELECT pg_temp.expect_true('B2 copied to product.categories with path, depth and slug', $q$
    SELECT string_agg(code || ' ' || path || ' ' || depth || ' ' || slug, ' | ' ORDER BY depth) =
           'CUPS /CUPS 0 cups | PAPER-CUPS /CUPS/PAPER-CUPS 1 paper-cups'
      FROM product.categories WHERE id IN (md5('qa:cat:cups')::uuid, md5('qa:cat:paper')::uuid) $q$);
SELECT pg_temp.expect_true('B3 renaming the legacy category renames the PIM one', $q$
    WITH u AS (UPDATE product.category SET name = 'Ly giấy in' WHERE id = md5('qa:cat:paper')::uuid RETURNING 1)
    SELECT (SELECT count(*) FROM u) = 1 $q$);
SELECT pg_temp.expect_true('B3b ... seen on the PIM side', $q$
    SELECT name = 'Ly giấy in' FROM product.categories WHERE id = md5('qa:cat:paper')::uuid $q$);

\echo '--- products'
SELECT pg_temp.expect_ok('B4 legacy product with a category (lower-case code)', $q$
    INSERT INTO product.product (id, code, name, name_en, category_id, status, customizable, brand, tax_class, created_at)
    VALUES (md5('qa:prd:1')::uuid, 'cup-12oz', 'Ly giấy 12oz', 'Paper cup 12oz', md5('qa:cat:paper')::uuid, 'DRAFT',
            TRUE, 'StockFlow', 'STANDARD', NOW()) $q$);
SELECT pg_temp.expect_true('B5 copied: same id, upper-case code, slug, CUSTOMIZABLE, DRAFT', $q$
    SELECT code = 'CUP-12OZ' AND slug = 'cup-12oz' AND kind = 'CUSTOMIZABLE' AND status = 'DRAFT'
           AND name_en = 'Paper cup 12oz' AND tax_class = 'STANDARD'
      FROM product.products WHERE id = md5('qa:prd:1')::uuid $q$);
SELECT pg_temp.expect_true('B6 its category is the primary one', $q$
    SELECT count(*) = 1 FROM product.product_categories
     WHERE product_id = md5('qa:prd:1')::uuid AND category_id = md5('qa:cat:paper')::uuid AND is_primary $q$);
SELECT pg_temp.expect_ok('B7 submitted, then approved by someone else', $q$
    UPDATE product.product SET status = 'PENDING_APPROVAL', submitted_by = md5('demo:user:editor')::uuid,
           submitted_at = NOW() WHERE id = md5('qa:prd:1')::uuid;
    UPDATE product.product SET status = 'APPROVED', approved_by = md5('demo:user:approver')::uuid,
           approved_at = NOW() WHERE id = md5('qa:prd:1')::uuid $q$);
SELECT pg_temp.expect_true('B8 the PIM row is APPROVED with who and when', $q$
    SELECT status = 'APPROVED' AND approved_by = md5('demo:user:approver')::uuid AND submitted_at IS NOT NULL
      FROM product.products WHERE id = md5('qa:prd:1')::uuid $q$);
SELECT pg_temp.expect_ok('B9 a variant attaches to the copied product (SCRUM-45 unblocked)', $q$
    INSERT INTO product.variants (id, product_id, sku, name, attribute_signature)
    VALUES (md5('qa:var:1')::uuid, md5('qa:prd:1')::uuid, 'CUP-12OZ-WHITE', 'Trắng', '') $q$);
SELECT pg_temp.expect_ok('B10 PIM publishes it; a later legacy edit keeps it PUBLISHED', $q$
    UPDATE product.products SET status = 'PUBLISHED', published_at = NOW() WHERE id = md5('qa:prd:1')::uuid;
    UPDATE product.product SET name = 'Ly giấy 12oz (mới)' WHERE id = md5('qa:prd:1')::uuid $q$);
SELECT pg_temp.expect_true('B11 ... still PUBLISHED, name updated', $q$
    SELECT status = 'PUBLISHED' AND name = 'Ly giấy 12oz (mới)' FROM product.products WHERE id = md5('qa:prd:1')::uuid $q$);
SELECT pg_temp.expect_ok('B12 discontinued in the admin', $q$
    UPDATE product.product SET status = 'DISCONTINUED' WHERE id = md5('qa:prd:1')::uuid $q$);
SELECT pg_temp.expect_true('B13 ... DISCONTINUED with discontinued_at', $q$
    SELECT status = 'DISCONTINUED' AND discontinued_at IS NOT NULL FROM product.products WHERE id = md5('qa:prd:1')::uuid $q$);
SELECT pg_temp.expect_ok('B14 a code the PIM refuses stays legacy-only, the admin write succeeds', $q$
    INSERT INTO product.product (id, code, name, status, created_at)
    VALUES (md5('qa:prd:long')::uuid, repeat('A', 60), 'Too long', 'DRAFT', NOW()) $q$);
SELECT pg_temp.expect_true('B15 ... and is not in product.products', $q$
    SELECT NOT EXISTS (SELECT 1 FROM product.products WHERE id = md5('qa:prd:long')::uuid) $q$);
SELECT pg_temp.expect_ok('B16 same code as a seeded PIM product, different id: legacy only', $q$
    INSERT INTO product.product (id, code, name, status, created_at)
    VALUES (md5('qa:prd:dup')::uuid, 'SOFA-3S', 'Dup', 'DRAFT', NOW()) $q$);
SELECT pg_temp.expect_true('B17 ... the seeded product is untouched', $q$
    SELECT name <> 'Dup' FROM product.products WHERE code = 'SOFA-3S' $q$);

ROLLBACK;
\echo 'PASS 06_product_bridge'
