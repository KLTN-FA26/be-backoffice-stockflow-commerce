-- =============================================================================
-- Commercial terms and credit at the order (SCRUM-427, V20261013000400; kltn-docs 15 §4.3, 18 §3), and the customer's
-- default payment schedule (4B-1, V20261108001900; kltn-docs 18 BR-03).
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
SELECT pg_temp.expect_ok('K6 prepaid, deposit 30% (balance before delivery) and credit 50M over 30 days', $q$
    INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, allow_credit, default_payment_term,
                                         deposit_percent, credit_limit, credit_term_days, approved_by, approved_at,
                                         deposit_balance_policy)
    VALUES (md5('qa:cp:1')::uuid, 'c0000000-0000-4000-8000-000000000001', TRUE, TRUE, TRUE, 'CREDIT', 30, 50000000, 30,
            md5('demo:user:approver')::uuid, NOW(), 'BEFORE_DELIVERY') $q$);
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

\echo '--- the default payment schedule (customer.default_payment_schedule, checked at commit)'
SELECT pg_temp.expect_fail('K16 a schedule whose shares add up to 90', $q$
    WITH s AS (
        INSERT INTO customer.default_payment_schedule (id, customer_id, set_by, set_at)
        VALUES (md5('qa:dps:1')::uuid, 'c0000000-0000-4000-8000-000000000001', md5('demo:user:approver')::uuid, NOW())
        RETURNING id)
    INSERT INTO customer.default_payment_installment (schedule_id, seq, name, percent, milestone)
    SELECT s.id, v.seq, v.name, v.percent, v.milestone FROM s,
           (VALUES (1, 'Đặt cọc', 60.00, 'ON_SIGNING'), (2, 'Trước giao', 30.00, 'BEFORE_DELIVERY'))
               AS v (seq, name, percent, milestone) $q$);
SELECT pg_temp.expect_fail('K17 a schedule paying after delivery that no accountant approved', $q$
    WITH s AS (
        INSERT INTO customer.default_payment_schedule (id, customer_id, set_by, set_at)
        VALUES (md5('qa:dps:1')::uuid, 'c0000000-0000-4000-8000-000000000001', md5('demo:user:approver')::uuid, NOW())
        RETURNING id)
    INSERT INTO customer.default_payment_installment (schedule_id, seq, name, percent, milestone, due_days)
    SELECT s.id, v.seq, v.name, v.percent, v.milestone, v.due_days FROM s,
           (VALUES (1, 'Đặt cọc', 40.00, 'ON_SIGNING', 0), (2, 'Công nợ', 60.00, 'AFTER_DELIVERY', 30))
               AS v (seq, name, percent, milestone, due_days) $q$);
SELECT pg_temp.expect_ok('K18 40% on signing, 60% thirty days after delivery, approved, on the approved credit', $q$
    WITH s AS (
        INSERT INTO customer.default_payment_schedule (id, customer_id, set_by, set_at, approved_by, approved_at)
        VALUES (md5('qa:dps:1')::uuid, 'c0000000-0000-4000-8000-000000000001', md5('demo:user:approver')::uuid, NOW(),
                md5('demo:user:approver')::uuid, NOW())
        RETURNING id)
    INSERT INTO customer.default_payment_installment (schedule_id, seq, name, percent, milestone, due_days)
    SELECT s.id, v.seq, v.name, v.percent, v.milestone, v.due_days FROM s,
           (VALUES (1, 'Đặt cọc', 40.00, 'ON_SIGNING', 0), (2, 'Công nợ', 60.00, 'AFTER_DELIVERY', 30))
               AS v (seq, name, percent, milestone, due_days) $q$);
SELECT pg_temp.expect_fail('K19 the credit taken away while the schedule pays after delivery', $q$
    UPDATE customer.credit_profile SET allow_credit = FALSE, credit_limit = NULL, credit_term_days = NULL,
                                       default_payment_term = 'PREPAID'
     WHERE customer_id = 'c0000000-0000-4000-8000-000000000001' $q$);
SELECT pg_temp.expect_fail('K20 an instalment removed, the rest adding up to 40', $q$
    DELETE FROM customer.default_payment_installment WHERE schedule_id = md5('qa:dps:1')::uuid AND seq = 2 $q$);
SELECT pg_temp.expect_fail('K21 a share of 0', $q$
    UPDATE customer.default_payment_installment SET percent = 0 WHERE schedule_id = md5('qa:dps:1')::uuid AND seq = 1 $q$);
SELECT pg_temp.expect_fail('K22 a milestone the contract does not know', $q$
    UPDATE customer.default_payment_installment SET milestone = 'ON_DELIVERY'
     WHERE schedule_id = md5('qa:dps:1')::uuid AND seq = 1 $q$);
SELECT pg_temp.expect_fail('K23 a second schedule for the same customer', $q$
    INSERT INTO customer.default_payment_schedule (id, customer_id, set_by, set_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('K24 the schedule moved to paying before delivery, the credit then taken away', $q$
    WITH moved AS (
        UPDATE customer.default_payment_installment SET milestone = 'BEFORE_DELIVERY', due_days = 0
         WHERE schedule_id = md5('qa:dps:1')::uuid AND seq = 2 RETURNING schedule_id)
    UPDATE customer.credit_profile SET allow_credit = FALSE, credit_limit = NULL, credit_term_days = NULL,
                                       default_payment_term = 'PREPAID'
     WHERE customer_id = 'c0000000-0000-4000-8000-000000000001' AND EXISTS (SELECT 1 FROM moved) $q$);

ROLLBACK;
\echo 'PASS 07_credit_terms'
