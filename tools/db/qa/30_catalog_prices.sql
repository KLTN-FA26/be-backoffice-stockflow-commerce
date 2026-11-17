-- =============================================================================
-- Adversarial checks: no base price, no group price, no promotion, no price on a storefront entry (V20261110000100,
-- team plan 5-3; kltn-docs 13 BR-04, BR-06, BR-10, D14). A customer's own price is the one price left, set only for
-- the customer's own SKU. Needs the demo seed. Rolled back at the end.
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

-- Fixtures: a customizable product with a variant (quy cách), the first customer's own SKU drawn on it, and another
-- customer.
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qa30:product')::uuid, 'QAPRC', 'QA ly gia', 'qa-prc', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa30:blank')::uuid, md5('qa30:product')::uuid, 'QA-PRC-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa30:draft')::uuid, c.id, md5('qa30:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa30:snapshot')::uuid, md5('qa30:draft')::uuid, repeat('c', 64), 'https://example.com/c.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa30:own')::uuid, md5('qa30:product')::uuid, 'QA-PRC-OWN', 'QA ly rieng', 'ACTIVE', FALSE, 'OWNED-SKU:QA-PRC-OWN',
       1, 0, NOW(), c.id, md5('qa30:snapshot')::uuid, md5('qa30:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qa30:other')::uuid, 'QA other customer', 'qa30-other@example.test', 'ACTIVE', 0, NOW());

\echo '--- base, group and promotion prices are gone'
SELECT pg_temp.expect_true('PR1 catalog.pricing_rule (base and group prices) is gone',
    to_regclass('catalog.pricing_rule') IS NULL);
SELECT pg_temp.expect_true('PR2 catalog.promotion is gone', to_regclass('catalog.promotion') IS NULL);
SELECT pg_temp.expect_fail('PR3 a base price cannot be written anywhere', $q$
    INSERT INTO catalog.pricing_rule (id, name, sku, price, currency, active, created_at)
    VALUES (gen_random_uuid(), 'BASE:QA-PRC-BLANK', 'QA-PRC-BLANK', 12500000, 'VND', TRUE, NOW()) $q$);

\echo '--- a storefront entry carries no price'
SELECT pg_temp.expect_true('PR4 catalog.catalog_entry has neither price nor currency',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'catalog' AND table_name = 'catalog_entry'
                   AND column_name IN ('price', 'currency')));
SELECT pg_temp.expect_fail('PR5 an entry cannot be written with a price', $q$
    INSERT INTO catalog.catalog_entry (id, sku, title, slug, description, price, published, version, created_at)
    VALUES (gen_random_uuid(), 'QA-PRC-BLANK', 'QA', 'qa30-priced', NULL, 100, TRUE, 0, NOW()) $q$);
SELECT pg_temp.expect_ok('PR6 an entry is listed without a price', $q$
    INSERT INTO catalog.catalog_entry (id, sku, title, slug, description, published, version, created_at, product_id,
                                       source_revision)
    VALUES (gen_random_uuid(), 'QA-PRC-BLANK', 'QA', 'qa30-entry', NULL, TRUE, 0, NOW(), md5('qa30:product')::uuid, 0) $q$);

\echo '--- a sales unit names a unit, never a price'
SELECT pg_temp.expect_true('PR16 product.variant_sales_units has no price column',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'product' AND table_name = 'variant_sales_units' AND column_name = 'price'));

\echo '--- the customer''s own price is the one price, for its own SKU only'
SELECT pg_temp.expect_fail('PR7 a new customer price on a variant (quy cach) - there it would be a base price by another name', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, version, created_at)
    SELECT gen_random_uuid(), v.owner_customer_id, 'QA-PRC-BLANK', 'VND', 0, NOW()
      FROM product.variants v WHERE v.sku = 'QA-PRC-OWN' $q$);
SELECT pg_temp.expect_fail('PR8 a customer price on another customer''s SKU', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, version, created_at)
    VALUES (gen_random_uuid(), md5('qa30:other')::uuid, 'QA-PRC-OWN', 'VND', 0, NOW()) $q$);
SELECT pg_temp.expect_ok('PR9 the customer''s own price on its own SKU', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, version, created_at)
    SELECT md5('qa30:price')::uuid, v.owner_customer_id, 'QA-PRC-OWN', 'VND', 0, NOW()
      FROM product.variants v WHERE v.sku = 'QA-PRC-OWN' $q$);
SELECT pg_temp.expect_fail('PR10 a price in another currency', $q$
    UPDATE catalog.customer_price SET currency = 'USD' WHERE id = md5('qa30:price')::uuid $q$);
SELECT pg_temp.expect_fail('PR11 a tier of a fraction of a dong', $q$
    INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version, created_at)
    VALUES (gen_random_uuid(), md5('qa30:price')::uuid, 1, 1000.5, 0, NOW()) $q$);

\echo '--- "is this item used?" no longer reads a dropped table'
SELECT pg_temp.expect_true('PR12 inventory.item_in_use runs and counts the customer''s own price',
    inventory.item_in_use(NULL, 'QA-PRC-OWN'));
SELECT pg_temp.expect_true('PR13 inventory.item_in_use does not name the price rules any more',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE p.prosrc ~* '(pricing_rule|catalog\.promotion)'));

\echo '--- the group-price permission is gone'
SELECT pg_temp.expect_true('PR14 no catalog-segment-prices permission, and no role holds one',
    NOT EXISTS (SELECT 1 FROM identity.permission WHERE resource = 'catalog-segment-prices' OR code LIKE 'catalog-segment-prices:%')
    AND NOT EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.permission p ON p.id = rp.permission_id
                     WHERE p.code LIKE 'catalog-segment-prices:%'));
SELECT pg_temp.expect_true('PR15 the customer still holds no customer-price grant (13 BR-10)',
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE r.code = 'CUSTOMER' AND p.resource = 'catalog-customer-prices'));

ROLLBACK;
