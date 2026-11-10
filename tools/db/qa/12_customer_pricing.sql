-- =============================================================================
-- Customer prices (SCRUM-428, V20261017000510; since 4B-2, V20261108002000, the customer's own SKUs only), customer groups
-- (V20261017000600) and the customer status history (SCRUM-429 follow-up, V20261017000500). Customer-group prices
-- (V20261017000530) went with V20261110000100 (5-3): 30_catalog_prices.sql checks they are gone. Needs the demo seed.
-- Rolled back at the end.
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

\echo '--- customer prices (catalog.customer_price, catalog.customer_price_tier)'
-- The demo customer's own SKU (2F-7), drawn on a customizable product of this script's own.
INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at)
VALUES (md5('qa:cp428:product')::uuid, 'QACP', 'QA ly giay', 'qa-cp', 'DRAFT', 'CUSTOMIZABLE', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa:cp428:blank')::uuid, md5('qa:cp428:product')::uuid, 'QA-CP-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
VALUES (md5('qa:cp428:draft')::uuid, 'c0000000-0000-4000-8000-000000000001', md5('qa:cp428:product')::uuid, 'DRAFT',
        NOW(), '{}'::jsonb);
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa:cp428:snapshot')::uuid, md5('qa:cp428:draft')::uuid, repeat('e', 64), 'https://example.com/p.pdf', NOW(),
        NOW(), '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
VALUES (md5('qa:cp428:own')::uuid, md5('qa:cp428:product')::uuid, 'QA-CP-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
        'OWNED-SKU:QA-CP-OWN', 1, 0, NOW(), 'c0000000-0000-4000-8000-000000000001', md5('qa:cp428:snapshot')::uuid,
        md5('qa:cp428:blank')::uuid);
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qa:cp428:other')::uuid, 'QA khach khac', 'qa-cp-other@example.com', 'ACTIVE', 0, NOW());
SELECT pg_temp.expect_ok('P1 a customer price for the customer''s own SKU', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (md5('qa:cp428:1')::uuid, 'c0000000-0000-4000-8000-000000000001', 'QA-CP-OWN', NOW()) $q$);
SELECT pg_temp.expect_fail('P1a a customer price for a stock variant (13 BR-05: own SKUs only, 4B-2)', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'SOFA-3S-GREY', NOW()) $q$);
SELECT pg_temp.expect_fail('P1b a customer price for another customer''s SKU', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (gen_random_uuid(), md5('qa:cp428:other')::uuid, 'QA-CP-OWN', NOW()) $q$);
SELECT pg_temp.expect_fail('P2 a second price for the same customer and SKU', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'QA-CP-OWN', NOW()) $q$);
SELECT pg_temp.expect_fail('P3 a SKU that does not exist (no customer owns it: tg_customer_price_own_sku, before the key)', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'QA-NO-SUCH-SKU', NOW()) $q$);
SELECT pg_temp.expect_fail('P4 a customer that does not exist (owns no SKU: tg_customer_price_own_sku, before the key)', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (gen_random_uuid(), gen_random_uuid(), 'TABLE-OAK-160', NOW()) $q$);
SELECT pg_temp.expect_fail('P5 a currency other than VND', $q$
    UPDATE catalog.customer_price SET currency = 'USD' WHERE id = md5('qa:cp428:1')::uuid $q$);
SELECT pg_temp.expect_ok('P6 two tiers, from 1 and 10 cartons', $q$
    INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:cp428:1')::uuid, 1, 11000000, NOW()),
           (gen_random_uuid(), md5('qa:cp428:1')::uuid, 10, 10000000, NOW()) $q$);
SELECT pg_temp.expect_fail('P7 two tiers from the same quantity', $q$
    INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:cp428:1')::uuid, 10, 9000000, NOW()) $q$);
SELECT pg_temp.expect_fail('P8 a tier from zero units', $q$
    INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:cp428:1')::uuid, 0, 9000000, NOW()) $q$);
SELECT pg_temp.expect_fail('P9 a free tier', $q$
    INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:cp428:1')::uuid, 20, 0, NOW()) $q$);
SELECT pg_temp.expect_fail('P10 a fraction of a dong', $q$
    INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:cp428:1')::uuid, 20, 100.50, NOW()) $q$);
SELECT pg_temp.expect_ok('P11 removing the price removes its tiers', $q$
    DELETE FROM catalog.customer_price WHERE id = md5('qa:cp428:1')::uuid;
    DO $d$ BEGIN
        IF EXISTS (SELECT 1 FROM catalog.customer_price_tier WHERE customer_price_id = md5('qa:cp428:1')::uuid)
        THEN RAISE EXCEPTION 'tiers left behind'; END IF;
    END $d$ $q$);

\echo '--- customer status history (customer.customer_status_history)'
SELECT pg_temp.expect_fail('H1 reopening a closed customer without a reason', $q$
    INSERT INTO customer.customer_status_history (id, customer_id, from_status, to_status, changed_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'INACTIVE', 'ACTIVE', NOW()) $q$);
SELECT pg_temp.expect_fail('H2 a change to the same status', $q$
    INSERT INTO customer.customer_status_history (id, customer_id, from_status, to_status, changed_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'ACTIVE', 'ACTIVE', NOW()) $q$);
SELECT pg_temp.expect_fail('H3 a status outside the lifecycle', $q$
    INSERT INTO customer.customer_status_history (id, customer_id, from_status, to_status, changed_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'ACTIVE', 'SUSPENDED', NOW()) $q$);
SELECT pg_temp.expect_ok('H4 a reopening with its reason', $q$
    INSERT INTO customer.customer_status_history (id, customer_id, from_status, to_status, reason, changed_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'INACTIVE', 'ACTIVE', 'came back', NOW()) $q$);

\echo '--- customer groups (customer.segment, V20261017000600)'
INSERT INTO customer.segment (id, code, name, version, created_at)
VALUES (md5('qa:seg530')::uuid, 'QA-SEG-530', 'QA group', 0, NOW());
SELECT pg_temp.expect_fail('G7 a group code in lower case', $q$
    INSERT INTO customer.segment (id, code, name, version, created_at)
    VALUES (gen_random_uuid(), 'qa-lower', 'QA', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('G8 a group without a name', $q$
    INSERT INTO customer.segment (id, code, name, version, created_at)
    VALUES (gen_random_uuid(), 'QA-BLANK', '  ', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('G9 a second group with the same code', $q$
    INSERT INTO customer.segment (id, code, name, version, created_at)
    VALUES (gen_random_uuid(), 'QA-SEG-530', 'Again', 0, NOW()) $q$);

\echo '--- grants'
SELECT pg_temp.expect_ok('P12 catalog-customer-prices: Sales Manager edits, Sales reads, the warehouse manager nothing beyond reading (the coordinator role is retired, 5-7), Customer nothing', $q$
    DO $d$ BEGIN
        IF EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                   JOIN identity.permission p ON p.id = rp.permission_id
                   WHERE p.resource = 'catalog-customer-prices'
                     AND (r.code IN ('CUSTOMER', 'WAREHOUSE_STAFF')
                          OR (r.code IN ('SALES_STAFF', 'WAREHOUSE_MANAGER') AND p.action NOT IN ('READ', 'VIEW_PAGE'))))
        THEN RAISE EXCEPTION 'unexpected grant'; END IF;
    END $d$ $q$);
SELECT pg_temp.expect_ok('G10 customer-segments: admins manage, Sales reads, the warehouse manager nothing beyond reading (the coordinator role is retired, 5-7), Customer and warehouse nothing', $q$
    DO $d$ BEGIN
        IF EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                   JOIN identity.permission p ON p.id = rp.permission_id
                   WHERE p.resource = 'customer-segments'
                     AND (r.code IN ('CUSTOMER', 'WAREHOUSE_STAFF')
                          OR (r.code IN ('SALES_STAFF', 'WAREHOUSE_MANAGER') AND p.action NOT IN ('READ', 'VIEW_PAGE'))))
        THEN RAISE EXCEPTION 'unexpected grant'; END IF;
        IF (SELECT count(*) FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
            JOIN identity.permission p ON p.id = rp.permission_id
            WHERE p.resource = 'customer-segments' AND p.action = 'UPDATE'
              AND r.code IN ('SALES_MANAGER', 'SYSTEM_ADMIN')) <> 2
        THEN RAISE EXCEPTION 'an administrator cannot assign groups'; END IF;
    END $d$ $q$);

ROLLBACK;
\echo 'PASS 10_customer_pricing'
