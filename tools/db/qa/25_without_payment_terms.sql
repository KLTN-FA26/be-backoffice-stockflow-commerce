-- =============================================================================
-- Adversarial checks + happy paths: orders, quotes and customers without their own payment terms
-- (V20261108003200, team plan 4B-10; kltn-docs 15 BR-01, BR-12, 06/11 "phieu thu theo dot thay so cai"). The code no
-- longer writes payment_term, deposit_*, balance_policy, credit_term_days of an order or a quote, nor allow_credit of a
-- customer's credit: every row it writes takes those columns' defaults, which must pass every rule, and a customer's
-- credit is a limit and its days to pay alone. Needs the demo seed. Rolled back at the end.
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

-- Fixtures: two customers of the test's own, the demo editor as the accountant.
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qa25:credit')::uuid, 'QA Cong No', 'qa25-credit@example.com', 'ACTIVE', 0, NOW()),
       (md5('qa25:none')::uuid, 'QA Khong No', 'qa25-none@example.com', 'ACTIVE', 0, NOW());

\echo '--- a customer''s credit: a limit and its days to pay, allow_credit left at its default'
SELECT pg_temp.expect_ok('PT1 credit written as the code writes it: a limit and days, no term columns',
    $q$INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by, approved_at)
       VALUES (md5('qa25:cp1')::uuid, md5('qa25:credit')::uuid, 50000000, 30, md5('demo:user:editor')::uuid, NOW())$q$);
SELECT pg_temp.expect_true('PT2 the term columns took their defaults',
    (SELECT NOT allow_credit AND allow_prepaid AND NOT allow_deposit AND default_payment_term = 'PREPAID'
            AND deposit_percent IS NULL
       FROM customer.credit_profile WHERE id = md5('qa25:cp1')::uuid));
SELECT pg_temp.expect_ok('PT3 no credit at all: neither a limit nor days',
    $q$INSERT INTO customer.credit_profile (id, customer_id, approved_by, approved_at)
       VALUES (md5('qa25:cp2')::uuid, md5('qa25:none')::uuid, md5('demo:user:editor')::uuid, NOW())$q$);
SELECT pg_temp.expect_fail('PT4 a limit without its days to pay',
    $q$UPDATE customer.credit_profile SET credit_term_days = NULL WHERE id = md5('qa25:cp1')::uuid$q$);
SELECT pg_temp.expect_fail('PT5 days to pay without a limit',
    $q$UPDATE customer.credit_profile SET credit_term_days = 30 WHERE id = md5('qa25:cp2')::uuid$q$);
SELECT pg_temp.expect_fail('PT6 a negative limit',
    $q$UPDATE customer.credit_profile SET credit_limit = -1 WHERE id = md5('qa25:cp1')::uuid$q$);
SELECT pg_temp.expect_fail('PT7 more than 365 days to pay',
    $q$UPDATE customer.credit_profile SET credit_term_days = 366 WHERE id = md5('qa25:cp1')::uuid$q$);
SELECT pg_temp.expect_ok('PT8 allow_credit is no longer the credit: true or false, the limit stands',
    $q$UPDATE customer.credit_profile SET allow_credit = true WHERE id = md5('qa25:cp1')::uuid$q$);

\echo '--- a default payment schedule paying after delivery needs a limit above zero, nothing else'
SELECT pg_temp.expect_ok('PT9 approved schedule paying after delivery for a customer with a limit (allow_credit false)',
    $q$UPDATE customer.credit_profile SET allow_credit = false WHERE id = md5('qa25:cp1')::uuid;
       INSERT INTO customer.default_payment_schedule (id, customer_id, set_by, set_at, approved_by, approved_at)
       VALUES (md5('qa25:s1')::uuid, md5('qa25:credit')::uuid, md5('demo:user:editor')::uuid, NOW(),
               md5('demo:user:approver')::uuid, NOW());
       INSERT INTO customer.default_payment_installment (schedule_id, seq, name, percent, milestone, due_days)
       VALUES (md5('qa25:s1')::uuid, 1, 'Coc', 30, 'ON_SIGNING', 0),
              (md5('qa25:s1')::uuid, 2, 'Sau giao', 70, 'AFTER_DELIVERY', 30)$q$);
SELECT pg_temp.expect_fail('PT10 the limit taken to zero under it',
    $q$UPDATE customer.credit_profile SET credit_limit = 0 WHERE id = md5('qa25:cp1')::uuid;
       UPDATE customer.default_payment_schedule SET set_at = NOW() WHERE id = md5('qa25:s1')::uuid$q$);
SELECT pg_temp.expect_fail('PT11 a schedule paying after delivery for a customer with no limit',
    $q$INSERT INTO customer.default_payment_schedule (id, customer_id, set_by, set_at)
       VALUES (md5('qa25:s2')::uuid, md5('qa25:none')::uuid, md5('demo:user:editor')::uuid, NOW());
       INSERT INTO customer.default_payment_installment (schedule_id, seq, name, percent, milestone, due_days)
       VALUES (md5('qa25:s2')::uuid, 1, 'Sau giao', 100, 'AFTER_DELIVERY', 30)$q$);
SELECT pg_temp.expect_ok('PT12 a schedule paying before delivery needs no credit',
    $q$INSERT INTO customer.default_payment_schedule (id, customer_id, set_by, set_at)
       VALUES (md5('qa25:s3')::uuid, md5('qa25:none')::uuid, md5('demo:user:editor')::uuid, NOW());
       INSERT INTO customer.default_payment_installment (schedule_id, seq, name, percent, milestone, due_days)
       VALUES (md5('qa25:s3')::uuid, 1, 'Ky', 100, 'ON_SIGNING', 0)$q$);

\echo '--- an order and a quote written without terms'
SELECT pg_temp.expect_ok('PT13 an order written as the code writes it: no term, no deposit, no paid columns',
    $q$INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                            placed_at, created_at)
       VALUES (md5('qa25:o1')::uuid, 'SO-QA25-1', md5('qa25:credit')::uuid, gen_random_uuid(), 'DRAFT', 1000000, 'VND',
               NOW(), NOW())$q$);
SELECT pg_temp.expect_true('PT14 its term columns took their defaults',
    (SELECT payment_term = 'PREPAID' AND deposit_required IS NULL AND deposit_received_at IS NULL
            AND balance_policy IS NULL AND credit_term_days IS NULL AND paid_in_full_at IS NULL
       FROM ordering.customer_order WHERE id = md5('qa25:o1')::uuid));
SELECT pg_temp.expect_fail('PT15 CONFIRMED with neither a signed contract nor a return to replace',
    $q$UPDATE ordering.customer_order SET status = 'CONFIRMED' WHERE id = md5('qa25:o1')::uuid$q$);
SELECT pg_temp.expect_true('PT16 a free replacement of a return is confirmed as itself, no longer as a prepaid order '
    || 'paid in full (paid_in_full_at is not written any more)',
    (SELECT pg_get_constraintdef(oid) LIKE '%replaces_return_request_id IS NOT NULL%'
       FROM pg_constraint WHERE conname = 'ck_order_confirmed_basis'
        AND conrelid = 'ordering.customer_order'::regclass));
SELECT pg_temp.expect_ok('PT17 a free replacement order confirmed with its term columns at their defaults (its CHECKs '
    || 'alone: triggers and keys off)',
    $q$SET LOCAL session_replication_role = replica;
       INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                            placed_at, created_at, replaces_return_request_id)
       VALUES (md5('qa25:o2')::uuid, 'SO-QA25-2', md5('qa25:credit')::uuid, gen_random_uuid(), 'CONFIRMED', 0, 'VND',
               NOW(), NOW(), md5('qa25:return')::uuid);
       SET LOCAL session_replication_role = origin$q$);
SELECT pg_temp.expect_true('PT18 every order confirmed before keeps a basis the new rule accepts',
    NOT EXISTS (SELECT 1 FROM ordering.customer_order WHERE status = 'CONFIRMED' AND contract_signed_at IS NULL
                   AND replaces_return_request_id IS NULL
                   AND NOT (payment_term = 'PREPAID' AND paid_in_full_at IS NOT NULL)
                   AND NOT (payment_term = 'DEPOSIT' AND deposit_received_at IS NOT NULL)
                   AND payment_term <> 'CREDIT'));
SELECT pg_temp.expect_ok('PT19 a quote written without a term: the default',
    $q$INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until)
       VALUES (md5('qa25:q1')::uuid, 'QT-QA25-1', gen_random_uuid(), md5('qa25:credit')::uuid, 'DRAFT',
               NOW() + INTERVAL '7 days')$q$);
SELECT pg_temp.expect_true('PT20 its term is the neutral PREPAID, with no deposit share',
    (SELECT payment_term = 'PREPAID' AND deposit_percent IS NULL FROM ordering.quote WHERE id = md5('qa25:q1')::uuid));

\echo '--- the existing rows'
SELECT pg_temp.expect_true('PT21 every credit profile is a limit and its days, or neither',
    NOT EXISTS (SELECT 1 FROM customer.credit_profile WHERE (credit_limit IS NULL) <> (credit_term_days IS NULL)));

ROLLBACK;
