-- =============================================================================
-- Adversarial checks + happy paths: B2B quotes (SCRUM-448, V20261014000300), the sample -> quote key, and
-- who may answer a sample or a quote (V20261014000200 / 000300). Needs the demo seed. Rolled back at the end.
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
    IF condition THEN
        RAISE NOTICE 'ok    %', label;
    ELSE
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
END $$;

-- Fixtures: a confirmed design of the first customer, a draft quote with one line and one tier.
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa9:draft')::uuid, id, (SELECT p.id FROM product.products p ORDER BY p.code LIMIT 1), 'DRAFT', NOW(),
       '{}'::jsonb FROM customer.customer ORDER BY id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa9:snap')::uuid, md5('qa9:draft')::uuid, repeat('b', 64), 'https://example.com/b.pdf', NOW(), NOW(), '[]'::jsonb);
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, payment_term, valid_until)
SELECT md5('qa9:quote')::uuid, 'QT-QA-1', gen_random_uuid(), id, 'DRAFT', 'PREPAID', NOW() + interval '7 days'
  FROM customer.customer ORDER BY id LIMIT 1;
INSERT INTO ordering.quote_line (id, quote_id, line_no, blank_sku, design_snapshot_id, quantity)
VALUES (md5('qa9:line')::uuid, md5('qa9:quote')::uuid, 1, 'SOFA-3S-GREY', md5('qa9:snap')::uuid, 500);
INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
VALUES (md5('qa9:line')::uuid, 500, 2000);

\echo '--- quotes (V20261014000300)'
SELECT pg_temp.expect_fail('QT1 an unknown status', $q$
    UPDATE ordering.quote SET status = 'APPROVED' WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT2 SENT without who sent it and when', $q$
    UPDATE ordering.quote SET status = 'SENT' WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT3 a deposit share on a prepaid quote', $q$
    UPDATE ordering.quote SET deposit_percent = 30 WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT4 a deposit of 100% (Order.applyTerms needs below 100)', $q$
    UPDATE ordering.quote SET payment_term = 'DEPOSIT', deposit_percent = 100 WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT5 valid until before it was created', $q$
    UPDATE ordering.quote SET valid_until = created_at - interval '1 day' WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT6 CHANGES_REQUESTED without the customer''s feedback', $q$
    UPDATE ordering.quote SET status = 'CHANGES_REQUESTED', sent_at = NOW(), sent_by = md5('demo:user:editor')::uuid,
           responded_at = NOW(), responded_by = md5('demo:user:editor')::uuid WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT7 CONVERTED without its order', $q$
    UPDATE ordering.quote SET status = 'CONVERTED', sent_at = NOW(), sent_by = md5('demo:user:editor')::uuid,
           responded_at = NOW(), responded_by = md5('demo:user:editor')::uuid WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT8 CANCELLED without a reason', $q$
    UPDATE ordering.quote SET status = 'CANCELLED' WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT9 two quotes for one request id', $q$
    INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, payment_term, valid_until)
    SELECT gen_random_uuid(), 'QT-QA-2', request_id, customer_id, 'DRAFT', 'PREPAID', valid_until
      FROM ordering.quote WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT10 a tier price with a fraction of a dong', $q$
    INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
    VALUES (md5('qa9:line')::uuid, 1000, 1999.50) $q$);
SELECT pg_temp.expect_fail('QT11 two tiers starting at the same quantity', $q$
    INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
    VALUES (md5('qa9:line')::uuid, 500, 1900) $q$);
SELECT pg_temp.expect_fail('QT12 a line on a blank that is not a product variant', $q$
    INSERT INTO ordering.quote_line (id, quote_id, line_no, blank_sku, design_snapshot_id, quantity)
    VALUES (gen_random_uuid(), md5('qa9:quote')::uuid, 2, 'NO-SUCH-SKU', md5('qa9:snap')::uuid, 500) $q$);
SELECT pg_temp.expect_fail('QT13 an accepted quantity without its price', $q$
    UPDATE ordering.quote_line SET accepted_quantity = 500 WHERE id = md5('qa9:line')::uuid $q$);
SELECT pg_temp.expect_fail('QT14 a SENT history row without the offer it sent', $q$
    INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, changed_at)
    VALUES (gen_random_uuid(), md5('qa9:quote')::uuid, 1, 'DRAFT', 'SENT', NOW()) $q$);
SELECT pg_temp.expect_ok('QT15 sent, then accepted at 500 for 2,000', $q$
    UPDATE ordering.quote SET status = 'ACCEPTED', sent_at = NOW(), sent_by = md5('demo:user:editor')::uuid,
           responded_at = NOW(), responded_by = md5('demo:user:editor')::uuid WHERE id = md5('qa9:quote')::uuid;
    UPDATE ordering.quote_line SET accepted_quantity = 500, accepted_unit_price = 2000 WHERE id = md5('qa9:line')::uuid $q$);

\echo '--- the sample -> quote key'
SELECT pg_temp.expect_true('SQ1 fk_sample_request_quote is validated on this database',
    EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_sample_request_quote' AND convalidated));
SELECT pg_temp.expect_fail('SQ2 a sample naming a quote that does not exist', $q$
    INSERT INTO production.sample_request (id, request_number, customer_id, quote_id, blank_sku, design_snapshot_id,
                                           quantity, status)
    SELECT gen_random_uuid(), 'SR-QA-9', customer_id, gen_random_uuid(), 'SOFA-3S-GREY', md5('qa9:snap')::uuid, 2, 'DRAFT'
      FROM ordering.quote WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_ok('SQ3 a sample naming its quote', $q$
    INSERT INTO production.sample_request (id, request_number, customer_id, quote_id, blank_sku, design_snapshot_id,
                                           quantity, status)
    SELECT gen_random_uuid(), 'SR-QA-10', customer_id, id, 'SOFA-3S-GREY', md5('qa9:snap')::uuid, 2, 'DRAFT'
      FROM ordering.quote WHERE id = md5('qa9:quote')::uuid $q$);

\echo '--- who answers (V20261014000200 / 000300)'
CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;
SELECT pg_temp.expect_true('GR1 the customer reads and answers samples',
    pg_temp.grants('CUSTOMER', 'production-sample-requests:READ')
    AND pg_temp.grants('CUSTOMER', 'production-sample-requests:APPROVE')
    AND NOT pg_temp.grants('CUSTOMER', 'production-sample-requests:UPDATE'));
SELECT pg_temp.expect_true('GR2 Sales records a sample answer; QC does not handle rounds',
    pg_temp.grants('SALES_STAFF', 'production-sample-requests:APPROVE')
    AND NOT pg_temp.grants('QC_STAFF', 'production-sample-requests:UPDATE')
    AND pg_temp.grants('QC_STAFF', 'production-sample-requests:READ')
    AND pg_temp.grants('QC_STAFF', 'production-orders:UPDATE'));
SELECT pg_temp.expect_true('GR3 only the customer answers a quote; Sales drafts and converts',
    pg_temp.grants('CUSTOMER', 'sales-quotes:APPROVE')
    AND NOT pg_temp.grants('SALES_STAFF', 'sales-quotes:APPROVE')
    AND NOT pg_temp.grants('ORDER_COORDINATOR', 'sales-quotes:APPROVE')
    AND pg_temp.grants('SALES_STAFF', 'sales-quotes:CREATE')
    AND pg_temp.grants('SALES_STAFF', 'sales-quotes:UPDATE')
    AND NOT pg_temp.grants('CUSTOMER', 'sales-quotes:CREATE'));

ROLLBACK;
