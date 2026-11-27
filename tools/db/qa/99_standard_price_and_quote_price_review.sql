-- =============================================================================
-- Standard prices of variants (V20261230000100, kltn-docs 13 BR-04) and quotes waiting for their prices to be approved
-- (V20261230000200, 14 BR-10), both as changed on 2026-11-26. Needs the demo seed. Rolled back at the end.
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

\echo '--- standard prices (catalog.standard_price, catalog.standard_price_tier)'
-- A catalogue variant of this script's own, so the demo's standard prices stay as they are.
INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at)
VALUES (md5('qa:sp:product')::uuid, 'QASP', 'QA ly giay chuan', 'qa-sp', 'DRAFT', 'CUSTOMIZABLE', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa:sp:blank')::uuid, md5('qa:sp:product')::uuid, 'QA-SP-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
SELECT pg_temp.expect_ok('SP1 a standard price for a catalogue variant', $q$
    INSERT INTO catalog.standard_price (id, sku, created_at)
    VALUES (md5('qa:sp:1')::uuid, 'QA-SP-BLANK', NOW()) $q$);
SELECT pg_temp.expect_fail('SP2 a second standard price for the same variant', $q$
    INSERT INTO catalog.standard_price (id, sku, created_at) VALUES (gen_random_uuid(), 'QA-SP-BLANK', NOW()) $q$);
SELECT pg_temp.expect_fail('SP3 a standard price for a customer''s own SKU (13 BR-05: it has the customer''s price)', $q$
    INSERT INTO catalog.standard_price (id, sku, created_at) VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', NOW()) $q$);
SELECT pg_temp.expect_fail('SP4 a standard price for a SKU that does not exist', $q$
    INSERT INTO catalog.standard_price (id, sku, created_at) VALUES (gen_random_uuid(), 'QA-NO-SUCH-SKU', NOW()) $q$);
SELECT pg_temp.expect_fail('SP5 a standard price in another currency', $q$
    UPDATE catalog.standard_price SET currency = 'USD' WHERE id = md5('qa:sp:1')::uuid $q$);
SELECT pg_temp.expect_ok('SP6 a break: from 10 cartons, 1 200 VND a piece', $q$
    INSERT INTO catalog.standard_price_tier (id, standard_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:sp:1')::uuid, 10, 1200, NOW()) $q$);
SELECT pg_temp.expect_fail('SP7 two breaks from the same number of cartons', $q$
    INSERT INTO catalog.standard_price_tier (id, standard_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:sp:1')::uuid, 10, 1100, NOW()) $q$);
SELECT pg_temp.expect_fail('SP8 a break priced 0', $q$
    INSERT INTO catalog.standard_price_tier (id, standard_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:sp:1')::uuid, 20, 0, NOW()) $q$);
SELECT pg_temp.expect_fail('SP9 a break priced with a fraction of a dong', $q$
    INSERT INTO catalog.standard_price_tier (id, standard_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:sp:1')::uuid, 20, 1100.5, NOW()) $q$);
SELECT pg_temp.expect_fail('SP10 a break from 0 cartons', $q$
    INSERT INTO catalog.standard_price_tier (id, standard_price_id, min_quantity, unit_price, created_at)
    VALUES (gen_random_uuid(), md5('qa:sp:1')::uuid, 0, 1100, NOW()) $q$);
SELECT pg_temp.expect_ok('SP11 deleting the variant deletes its standard price with it', $q$
    DELETE FROM product.variants WHERE id = md5('qa:sp:blank')::uuid $q$);
DO $d$ BEGIN
    IF EXISTS (SELECT 1 FROM catalog.standard_price WHERE id = md5('qa:sp:1')::uuid) THEN
        RAISE EXCEPTION 'FAIL  SP11: the standard price outlived its variant';
    END IF;
END $d$;

\echo '--- quotes waiting for their prices to be approved (ordering.quote.price_review, ordering.quote_price_review)'
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until)
SELECT md5('qa:pr:quote')::uuid, 'QT-QA-PR', gen_random_uuid(), c.id, 'DRAFT', NOW() + interval '7 days'
  FROM customer.customer c ORDER BY c.id LIMIT 1;
SELECT pg_temp.expect_ok('PR1 a draft waits for its prices to be approved', $q$
    UPDATE ordering.quote SET price_review = 'PENDING' WHERE id = md5('qa:pr:quote')::uuid $q$);
SELECT pg_temp.expect_fail('PR2 a sent quote still waiting (only a DRAFT waits)', $q$
    UPDATE ordering.quote SET status = 'SENT', sent_at = NOW(), sent_by = gen_random_uuid()
     WHERE id = md5('qa:pr:quote')::uuid $q$);
SELECT pg_temp.expect_fail('PR3 a price review state that does not exist', $q$
    UPDATE ordering.quote SET price_review = 'MAYBE' WHERE id = md5('qa:pr:quote')::uuid $q$);
SELECT pg_temp.expect_ok('PR4 its submission, waiting', $q$
    INSERT INTO ordering.quote_price_review (id, quote_id, revision, outcome, threshold_percent, lines, submitted_at,
        submitted_by, created_at)
    VALUES (md5('qa:pr:review')::uuid, md5('qa:pr:quote')::uuid, 1, 'PENDING', 10, '[]', NOW(),
        (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), NOW()) $q$);
SELECT pg_temp.expect_fail('PR5 a second submission waiting on the same quote', $q$
    INSERT INTO ordering.quote_price_review (id, quote_id, revision, outcome, threshold_percent, lines, submitted_at,
        submitted_by, created_at)
    VALUES (gen_random_uuid(), md5('qa:pr:quote')::uuid, 1, 'PENDING', 10, '[]', NOW(), (SELECT id FROM identity.app_user ORDER BY id LIMIT 1),
        NOW()) $q$);
SELECT pg_temp.expect_fail('PR6 approved by whoever submitted it (four eyes)', $q$
    UPDATE ordering.quote_price_review SET outcome = 'APPROVED', decided_at = NOW(), decided_by = submitted_by
     WHERE id = md5('qa:pr:review')::uuid $q$);
SELECT pg_temp.expect_fail('PR7 rejected without a reason', $q$
    UPDATE ordering.quote_price_review SET outcome = 'REJECTED', decided_at = NOW(),
           decided_by = (SELECT id FROM identity.app_user ORDER BY id DESC LIMIT 1) WHERE id = md5('qa:pr:review')::uuid $q$);
SELECT pg_temp.expect_fail('PR8 decided with no one deciding it', $q$
    UPDATE ordering.quote_price_review SET outcome = 'APPROVED', decided_at = NOW()
     WHERE id = md5('qa:pr:review')::uuid $q$);
SELECT pg_temp.expect_fail('PR9 still waiting but marked decided', $q$
    UPDATE ordering.quote_price_review SET decided_at = NOW(), decided_by = (SELECT id FROM identity.app_user ORDER BY id DESC LIMIT 1)
     WHERE id = md5('qa:pr:review')::uuid $q$);
SELECT pg_temp.expect_fail('PR10 lines that are not a list', $q$
    UPDATE ordering.quote_price_review SET lines = '{}' WHERE id = md5('qa:pr:review')::uuid $q$);
SELECT pg_temp.expect_fail('PR11 a threshold of 100%', $q$
    UPDATE ordering.quote_price_review SET threshold_percent = 100 WHERE id = md5('qa:pr:review')::uuid $q$);
SELECT pg_temp.expect_ok('PR12 approved by someone else', $q$
    UPDATE ordering.quote_price_review SET outcome = 'APPROVED', decided_at = NOW(),
           decided_by = (SELECT id FROM identity.app_user ORDER BY id DESC LIMIT 1) WHERE id = md5('qa:pr:review')::uuid $q$);
SELECT pg_temp.expect_ok('PR13 withdrawn by whoever submitted it (no four eyes for taking it back)', $q$
    INSERT INTO ordering.quote_price_review (id, quote_id, revision, outcome, threshold_percent, lines, submitted_at,
        submitted_by, decided_at, decided_by, created_at)
    VALUES (gen_random_uuid(), md5('qa:pr:quote')::uuid, 1, 'WITHDRAWN', 10, '[]', NOW(), (SELECT id FROM identity.app_user ORDER BY id LIMIT 1),
        NOW(), (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), NOW()) $q$);

\echo '--- who approves'
DO $d$ BEGIN
    IF (SELECT array_agg(r.code ORDER BY r.code) FROM identity.role_permission rp
          JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
         WHERE p.code = 'sales-quote-price-approvals:APPROVE') <> ARRAY['SALES_MANAGER', 'SYSTEM_ADMIN']::varchar[] THEN
        RAISE EXCEPTION 'FAIL  quote price approvals are not the Sales Manager''s and the System Admin''s alone';
    END IF;
    IF (SELECT array_agg(r.code ORDER BY r.code) FROM identity.role_permission rp
          JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
         WHERE p.code = 'catalog-standard-prices:UPDATE') <> ARRAY['SALES_MANAGER', 'SYSTEM_ADMIN']::varchar[] THEN
        RAISE EXCEPTION 'FAIL  standard prices are not edited by the Sales Manager and the System Admin alone';
    END IF;
    RAISE NOTICE 'ok    PR14 approvers and editors as seeded';
END $d$;

ROLLBACK;
\echo 'PASS 99_standard_price_and_quote_price_review'
