-- =============================================================================
-- Commercial terms and credit at the order (SCRUM-427, V20261013000400; kltn-docs 15 §4.3, 18 §3).
-- Needs the demo seed. Rolled back at the end.
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

\echo '--- commercial terms (customer.credit_profile)'
SELECT pg_temp.expect_fail('K1 terms that allow nothing', $q$
    INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, allow_credit, approved_by, approved_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', FALSE, FALSE, FALSE, md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('K2 a default term that is not allowed', $q$
    INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, default_payment_term, approved_by, approved_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', TRUE, 'CREDIT', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('K3 credit without a limit', $q$
    INSERT INTO customer.credit_profile (id, customer_id, allow_credit, credit_term_days, approved_by, approved_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', TRUE, 30, md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('K4 a deposit of 100 percent', $q$
    INSERT INTO customer.credit_profile (id, customer_id, allow_deposit, deposit_percent, approved_by, approved_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', TRUE, 100, md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('K5 terms nobody approved', $q$
    INSERT INTO customer.credit_profile (id, customer_id, approved_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', NOW()) $q$);
SELECT pg_temp.expect_ok('K6 prepaid, deposit 30% and credit 50M over 30 days', $q$
    INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, allow_credit, default_payment_term,
                                         deposit_percent, credit_limit, credit_term_days, approved_by, approved_at)
    VALUES (md5('qa:cp:1')::uuid, 'c0000000-0000-4000-8000-000000000001', TRUE, TRUE, TRUE, 'CREDIT', 30, 50000000, 30,
            md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('K7 a second set of terms for the same customer', $q$
    INSERT INTO customer.credit_profile (id, customer_id, approved_by, approved_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', md5('demo:user:approver')::uuid, NOW()) $q$);

\echo '--- credit orders and their checks (ordering.order_credit_check)'
SELECT pg_temp.expect_fail('K8 a credit order without days to pay', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                         created_at, payment_term)
    VALUES (gen_random_uuid(), 'SO-QA-K8', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'CONFIRMED',
            1000000, 'VND', NOW(), NOW(), 'CREDIT') $q$);
SELECT pg_temp.expect_fail('K9 days to pay on a prepaid order', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                         created_at, credit_term_days)
    VALUES (gen_random_uuid(), 'SO-QA-K9', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT',
            1000000, 'VND', NOW(), NOW(), 30) $q$);
SELECT pg_temp.expect_ok('K10 a credit order confirmed on credit', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                         created_at, payment_term, credit_term_days)
    VALUES (md5('qa:order:k10')::uuid, 'SO-QA-K10', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'CONFIRMED',
            1000000, 'VND', NOW(), NOW(), 'CREDIT', 30) $q$);
SELECT pg_temp.expect_fail('K11 WITHIN_LIMIT over the limit', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome)
    VALUES (gen_random_uuid(), md5('qa:order:k10')::uuid, NOW(), 1000000, 500000, 1000000, 'WITHIN_LIMIT') $q$);
SELECT pg_temp.expect_ok('K12 WITHIN_LIMIT within the limit', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome)
    VALUES (gen_random_uuid(), md5('qa:order:k10')::uuid, NOW(), 2000000, 500000, 1000000, 'WITHIN_LIMIT') $q$);
SELECT pg_temp.expect_fail('K13 an approval with nobody and no reason', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome)
    VALUES (gen_random_uuid(), md5('qa:order:k10')::uuid, NOW(), 0, 500000, 1000000, 'APPROVED') $q$);
SELECT pg_temp.expect_ok('K14 an approval naming who and why', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome,
                                             decided_by, decided_at, note)
    VALUES (gen_random_uuid(), md5('qa:order:k10')::uuid, NOW(), 0, 500000, 1000000, 'APPROVED',
            md5('demo:user:approver')::uuid, NOW(), 'trusted customer') $q$);
SELECT pg_temp.expect_fail('K15 an automatic check that names a decider', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome,
                                             decided_by)
    VALUES (gen_random_uuid(), md5('qa:order:k10')::uuid, NOW(), 0, 0, 1000000, 'OVER_LIMIT',
            md5('demo:user:approver')::uuid) $q$);

ROLLBACK;
\echo 'PASS 07_credit_terms'
