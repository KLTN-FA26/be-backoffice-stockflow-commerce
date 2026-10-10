-- =============================================================================
-- Receivables, customer transfers and allocations (SCRUM-431, V20261013000500; kltn-docs 15 §4.3, §5.3).
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

INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at, payment_term, credit_term_days)
VALUES (md5('qa:order:r1')::uuid, 'SO-QA-R1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DELIVERED',
        12000000, 'VND', NOW(), NOW(), 'CREDIT', 30);

\echo '--- receivables (payment.receivable)'
SELECT pg_temp.expect_ok('R1 a receivable opened at delivery', $q$
    INSERT INTO payment.receivable (id, order_id, customer_id, amount, currency, issued_at, due_date)
    VALUES (md5('qa:rcv:1')::uuid, md5('qa:order:r1')::uuid, 'c0000000-0000-4000-8000-000000000001', 12000000, 'VND',
            NOW(), CURRENT_DATE + 30) $q$);
SELECT pg_temp.expect_fail('R2 a second receivable for the same order', $q$
    INSERT INTO payment.receivable (id, order_id, customer_id, amount, currency, issued_at, due_date)
    VALUES (gen_random_uuid(), md5('qa:order:r1')::uuid, 'c0000000-0000-4000-8000-000000000001', 1, 'VND', NOW(),
            CURRENT_DATE) $q$);
SELECT pg_temp.expect_fail('R3 paid more than owed', $q$
    UPDATE payment.receivable SET paid_amount = 12000001, status = 'PAID', settled_at = NOW()
     WHERE id = md5('qa:rcv:1')::uuid $q$);
SELECT pg_temp.expect_fail('R4 PAID with money still owed', $q$
    UPDATE payment.receivable SET paid_amount = 5000000, status = 'PAID', settled_at = NOW()
     WHERE id = md5('qa:rcv:1')::uuid $q$);
SELECT pg_temp.expect_fail('R5 OPEN with money received', $q$
    UPDATE payment.receivable SET paid_amount = 5000000 WHERE id = md5('qa:rcv:1')::uuid $q$);
SELECT pg_temp.expect_ok('R6 partially paid', $q$
    UPDATE payment.receivable SET paid_amount = 5000000, status = 'PARTIALLY_PAID' WHERE id = md5('qa:rcv:1')::uuid $q$);
SELECT pg_temp.expect_ok('R7 overdue with money still owed', $q$
    UPDATE payment.receivable SET status = 'OVERDUE' WHERE id = md5('qa:rcv:1')::uuid $q$);

\echo '--- customer transfers and allocations'
SELECT pg_temp.expect_ok('R8 a transfer recorded from the statement', $q$
    INSERT INTO payment.customer_transfer (id, customer_id, reference, amount, unallocated_amount, currency, received_on,
                                           recorded_by, recorded_at)
    VALUES (md5('qa:tr:1')::uuid, 'c0000000-0000-4000-8000-000000000001', 'FT-QA-1', 10000000, 3000000, 'VND',
            CURRENT_DATE, md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('R9 the same statement line twice (BR-07)', $q$
    INSERT INTO payment.customer_transfer (id, customer_id, reference, amount, unallocated_amount, currency, received_on,
                                           recorded_by, recorded_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'FT-QA-1', 1, 1, 'VND', CURRENT_DATE,
            md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('R10 more left unallocated than was sent', $q$
    UPDATE payment.customer_transfer SET unallocated_amount = 10000001 WHERE id = md5('qa:tr:1')::uuid $q$);
SELECT pg_temp.expect_ok('R11 an allocation', $q$
    INSERT INTO payment.transfer_allocation (id, transfer_id, receivable_id, amount, allocated_at, allocated_by)
    VALUES (md5('qa:al:1')::uuid, md5('qa:tr:1')::uuid, md5('qa:rcv:1')::uuid, 7000000, NOW(),
            md5('demo:user:approver')::uuid) $q$);
SELECT pg_temp.expect_fail('R12 an allocation of nothing', $q$
    INSERT INTO payment.transfer_allocation (id, transfer_id, receivable_id, amount, allocated_at)
    VALUES (gen_random_uuid(), md5('qa:tr:1')::uuid, md5('qa:rcv:1')::uuid, 0, NOW()) $q$);
SELECT pg_temp.expect_fail('R13 an allocation edited afterwards (BR-06)', $q$
    UPDATE payment.transfer_allocation SET amount = 1 WHERE id = md5('qa:al:1')::uuid $q$);
SELECT pg_temp.expect_fail('R14 an allocation deleted (BR-06)', $q$
    DELETE FROM payment.transfer_allocation WHERE id = md5('qa:al:1')::uuid $q$);
SELECT pg_temp.expect_ok('R15 a credit check blocked by an overdue receivable', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome)
    VALUES (gen_random_uuid(), md5('qa:order:r1')::uuid, NOW(), 50000000, 12000000, 1000000, 'OVERDUE') $q$);

ROLLBACK;
\echo 'PASS 08_receivables'
