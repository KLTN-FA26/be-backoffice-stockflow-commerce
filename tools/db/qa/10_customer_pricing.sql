-- =============================================================================
-- Customer prices (SCRUM-428, V20261014000510), customer-group prices (P7, V20261014000530) and the customer status history (SCRUM-429 follow-up,
-- V20261014000500). Needs the demo seed. Rolled back at the end.
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
SELECT pg_temp.expect_ok('P1 a customer price for a real SKU', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (md5('qa:cp428:1')::uuid, 'c0000000-0000-4000-8000-000000000001', 'SOFA-3S-GREY', NOW()) $q$);
SELECT pg_temp.expect_fail('P2 a second price for the same customer and SKU', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'SOFA-3S-GREY', NOW()) $q$);
SELECT pg_temp.expect_fail('P3 a SKU that does not exist', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'QA-NO-SUCH-SKU', NOW()) $q$);
SELECT pg_temp.expect_fail('P4 a customer that does not exist', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
    VALUES (gen_random_uuid(), gen_random_uuid(), 'TABLE-OAK-160', NOW()) $q$);
SELECT pg_temp.expect_fail('P5 a currency other than VND', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, created_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'TABLE-OAK-160', 'USD', NOW()) $q$);
SELECT pg_temp.expect_ok('P6 two tiers', $q$
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

\echo '--- customer-group prices (catalog.pricing_rule with a segment, V20261014000530)'
INSERT INTO customer.segment (id, code, name, version, created_at)
VALUES (md5('qa:seg530')::uuid, 'QA-SEG-530', 'QA group', 0, NOW());
SELECT pg_temp.expect_ok('G1 a group price for a SKU', $q$
    INSERT INTO catalog.pricing_rule (id, name, sku, segment_id, price, currency, created_at)
    VALUES (gen_random_uuid(), 'SEGMENT:qa:1', 'SOFA-3S-GREY', md5('qa:seg530')::uuid, 11000000, 'VND', NOW()) $q$);
SELECT pg_temp.expect_fail('G2 a second price for the same group and SKU', $q$
    INSERT INTO catalog.pricing_rule (id, name, sku, segment_id, price, currency, created_at)
    VALUES (gen_random_uuid(), 'SEGMENT:qa:2', 'SOFA-3S-GREY', md5('qa:seg530')::uuid, 10000000, 'VND', NOW()) $q$);
SELECT pg_temp.expect_fail('G3 a free group price', $q$
    INSERT INTO catalog.pricing_rule (id, name, sku, segment_id, price, currency, created_at)
    SELECT gen_random_uuid(), 'SEGMENT:qa:3', sku, md5('qa:seg530')::uuid, 0, 'VND', NOW()
    FROM product.variants WHERE sku <> 'SOFA-3S-GREY' LIMIT 1 $q$);
SELECT pg_temp.expect_fail('G4 a fraction of a dong', $q$
    INSERT INTO catalog.pricing_rule (id, name, sku, segment_id, price, currency, created_at)
    SELECT gen_random_uuid(), 'SEGMENT:qa:4', sku, md5('qa:seg530')::uuid, 1000.5, 'VND', NOW()
    FROM product.variants WHERE sku <> 'SOFA-3S-GREY' LIMIT 1 $q$);
SELECT pg_temp.expect_fail('G5 a group that does not exist', $q$
    INSERT INTO catalog.pricing_rule (id, name, sku, segment_id, price, currency, created_at)
    VALUES (gen_random_uuid(), 'SEGMENT:qa:5', 'SOFA-3S-GREY', gen_random_uuid(), 11000000, 'VND', NOW()) $q$);

\echo '--- grants'
SELECT pg_temp.expect_ok('P12 catalog-customer-prices: E-commerce Admin edits, Sales and Coordinator read, Customer nothing', $q$
    DO $d$ BEGIN
        IF EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                   JOIN identity.permission p ON p.id = rp.permission_id
                   WHERE p.resource = 'catalog-customer-prices'
                     AND (r.code IN ('CUSTOMER', 'WAREHOUSE_STAFF')
                          OR (r.code IN ('SALES_STAFF', 'ORDER_COORDINATOR') AND p.action NOT IN ('READ', 'VIEW_PAGE'))))
        THEN RAISE EXCEPTION 'unexpected grant'; END IF;
    END $d$ $q$);
SELECT pg_temp.expect_ok('G6 catalog-segment-prices: E-commerce and System Admin edit, Sales and Coordinator read, Customer nothing', $q$
    DO $d$ BEGIN
        IF EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                   JOIN identity.permission p ON p.id = rp.permission_id
                   WHERE p.resource = 'catalog-segment-prices'
                     AND (r.code IN ('CUSTOMER', 'WAREHOUSE_STAFF')
                          OR (r.code IN ('SALES_STAFF', 'ORDER_COORDINATOR') AND p.action NOT IN ('READ', 'VIEW_PAGE'))))
        THEN RAISE EXCEPTION 'unexpected grant'; END IF;
        IF (SELECT count(*) FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
            JOIN identity.permission p ON p.id = rp.permission_id
            WHERE p.resource = 'catalog-segment-prices' AND p.action = 'UPDATE'
              AND r.code IN ('ECOMMERCE_ADMIN', 'SYSTEM_ADMIN')) <> 2
        THEN RAISE EXCEPTION 'an administrator cannot edit group prices'; END IF;
    END $d$ $q$);

ROLLBACK;
\echo 'PASS 10_customer_pricing'
