-- =============================================================================
-- G2-3 (SCRUM-218, V20261021000200): one ledger of incoming money, deposit balance policy, carrier-collected balances.
-- kltn-docs 15 §4.2 step 3, BR-05..BR-08; 09 §4.3 step 4, BR-04. Needs the demo seed. Rolled back at the end.
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

\echo '--- the customer''s terms (customer.credit_profile)'
SELECT pg_temp.expect_ok('L1 a deposit customer with a balance policy', $q$
    INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, default_payment_term,
                                         deposit_percent, approved_by, approved_at, deposit_balance_policy)
    VALUES (md5('qa:cp:l1')::uuid, 'c0000000-0000-4000-8000-000000000001', TRUE, TRUE, 'DEPOSIT', 30,
            md5('demo:user:approver')::uuid, NOW(), 'ON_DELIVERY')
    ON CONFLICT (customer_id) DO UPDATE SET allow_deposit = TRUE, deposit_percent = 30,
                                            deposit_balance_policy = 'ON_DELIVERY' $q$);
SELECT pg_temp.expect_fail('L2 deposits allowed without a balance policy', $q$
    UPDATE customer.credit_profile SET deposit_balance_policy = NULL
     WHERE customer_id = 'c0000000-0000-4000-8000-000000000001' $q$);
SELECT pg_temp.expect_fail('L3 a balance policy without deposits', $q$
    UPDATE customer.credit_profile SET allow_deposit = FALSE, deposit_percent = NULL, default_payment_term = 'PREPAID'
     WHERE customer_id = 'c0000000-0000-4000-8000-000000000001' $q$);
SELECT pg_temp.expect_fail('L4 a balance policy the application does not know (CREDIT waits for G3 Q5)', $q$
    UPDATE customer.credit_profile SET deposit_balance_policy = 'CREDIT'
     WHERE customer_id = 'c0000000-0000-4000-8000-000000000001' $q$);

\echo '--- the order keeps its policy (ordering.customer_order)'
SELECT pg_temp.expect_ok('L5 a deposit order collected on delivery', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                         placed_at, created_at, payment_term, deposit_required, balance_policy)
    VALUES (md5('qa:order:l5')::uuid, 'SO-QA-L5', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
            'PENDING_PAYMENT', 10000000, 'VND', NOW(), NOW(), 'DEPOSIT', 3000000, 'ON_DELIVERY') $q$);
SELECT pg_temp.expect_fail('L6 a deposit order without a balance policy', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                         placed_at, created_at, payment_term, deposit_required)
    VALUES (gen_random_uuid(), 'SO-QA-L6', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
            'PENDING_PAYMENT', 10000000, 'VND', NOW(), NOW(), 'DEPOSIT', 3000000) $q$);
SELECT pg_temp.expect_fail('L7 a prepaid order with a balance policy', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                         placed_at, created_at, payment_term, balance_policy)
    VALUES (gen_random_uuid(), 'SO-QA-L7', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
            'PENDING_PAYMENT', 10000000, 'VND', NOW(), NOW(), 'PREPAID', 'BEFORE_DELIVERY') $q$);
SELECT pg_temp.expect_fail('L8 a COD amount above the order total', $q$
    UPDATE ordering.customer_order SET cod_amount = 10000001 WHERE id = md5('qa:order:l5')::uuid $q$);
SELECT pg_temp.expect_ok('L9 a COD amount of the balance, ON_DELIVERY', $q$
    UPDATE ordering.customer_order SET cod_amount = 7000000 WHERE id = md5('qa:order:l5')::uuid $q$);
SELECT pg_temp.expect_fail('L10 a COD amount on a balance paid before delivery', $q$
    UPDATE ordering.customer_order SET balance_policy = 'BEFORE_DELIVERY' WHERE id = md5('qa:order:l5')::uuid $q$);

\echo '--- the ledger (payment.customer_transfer, payment.transfer_allocation)'
SELECT pg_temp.expect_ok('L11 a transfer from the statement', $q$
    INSERT INTO payment.customer_transfer (id, customer_id, reference, amount, unallocated_amount, currency, received_on,
                                           recorded_by, recorded_at)
    VALUES (md5('qa:tr:l11')::uuid, 'c0000000-0000-4000-8000-000000000001', 'FT-QA-L11', 5000000, 2000000, 'VND',
            CURRENT_DATE, md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('L12 a carrier remittance without its carrier', $q$
    INSERT INTO payment.customer_transfer (id, customer_id, reference, amount, unallocated_amount, currency, received_on,
                                           recorded_by, recorded_at, channel)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'GHN-QA:SO-QA-L5', 1, 1, 'VND', CURRENT_DATE,
            md5('demo:user:approver')::uuid, NOW(), 'CARRIER_REMITTANCE') $q$);
SELECT pg_temp.expect_fail('L13 more refunded and left than the transfer brought (BR-05)', $q$
    UPDATE payment.customer_transfer SET refunded_amount = 3000001 WHERE id = md5('qa:tr:l11')::uuid $q$);
SELECT pg_temp.expect_ok('L14 money paid to an order itself (no receivable)', $q$
    INSERT INTO payment.transfer_allocation (id, transfer_id, order_id, receivable_id, amount, allocated_at)
    VALUES (md5('qa:al:l14')::uuid, md5('qa:tr:l11')::uuid, md5('qa:order:l5')::uuid, NULL, 3000000, NOW()) $q$);
SELECT pg_temp.expect_fail('L15 an allocation naming no order', $q$
    INSERT INTO payment.transfer_allocation (id, transfer_id, amount, allocated_at)
    VALUES (gen_random_uuid(), md5('qa:tr:l11')::uuid, 1, NOW()) $q$);
SELECT pg_temp.expect_fail('L16 a reversal without the accountant''s reason (BR-06)', $q$
    INSERT INTO payment.transfer_allocation (id, transfer_id, order_id, amount, allocated_at, reverses_allocation_id)
    VALUES (gen_random_uuid(), md5('qa:tr:l11')::uuid, md5('qa:order:l5')::uuid, -3000000, NOW(),
            md5('qa:al:l14')::uuid) $q$);
SELECT pg_temp.expect_fail('L17 a negative amount that reverses nothing', $q$
    INSERT INTO payment.transfer_allocation (id, transfer_id, order_id, amount, allocated_at)
    VALUES (gen_random_uuid(), md5('qa:tr:l11')::uuid, md5('qa:order:l5')::uuid, -1, NOW()) $q$);
SELECT pg_temp.expect_ok('L18 a reversal with its reason', $q$
    INSERT INTO payment.transfer_allocation (id, transfer_id, order_id, amount, allocated_at, reverses_allocation_id,
                                             note)
    VALUES (md5('qa:al:l18')::uuid, md5('qa:tr:l11')::uuid, md5('qa:order:l5')::uuid, -3000000, NOW(),
            md5('qa:al:l14')::uuid, 'wrong order') $q$);
SELECT pg_temp.expect_fail('L19 the same allocation reversed twice', $q$
    INSERT INTO payment.transfer_allocation (id, transfer_id, order_id, amount, allocated_at, reverses_allocation_id,
                                             note)
    VALUES (gen_random_uuid(), md5('qa:tr:l11')::uuid, md5('qa:order:l5')::uuid, -3000000, NOW(),
            md5('qa:al:l14')::uuid, 'again') $q$);
SELECT pg_temp.expect_fail('L20 a reversal edited afterwards (BR-06)', $q$
    UPDATE payment.transfer_allocation SET note = 'changed' WHERE id = md5('qa:al:l18')::uuid $q$);

\echo '--- carrier-collected balances (payment.receivable)'
SELECT pg_temp.expect_fail('L21 an unknown receivable kind', $q$
    INSERT INTO payment.receivable (id, order_id, customer_id, kind, amount, currency, issued_at, due_date)
    VALUES (gen_random_uuid(), md5('qa:order:l5')::uuid, 'c0000000-0000-4000-8000-000000000001', 'FOO', 1, 'VND',
            NOW(), CURRENT_DATE) $q$);
SELECT pg_temp.expect_fail('L22 a carrier collecting a credit order''s receivable', $q$
    INSERT INTO payment.receivable (id, order_id, customer_id, kind, collector, amount, currency, issued_at, due_date)
    VALUES (gen_random_uuid(), md5('qa:order:l5')::uuid, 'c0000000-0000-4000-8000-000000000001', 'CREDIT_ORDER',
            'CARRIER', 1, 'VND', NOW(), CURRENT_DATE) $q$);
SELECT pg_temp.expect_ok('L23 a deposit balance the carrier collected', $q$
    INSERT INTO payment.receivable (id, order_id, customer_id, kind, collector, amount, currency, issued_at, due_date)
    VALUES (gen_random_uuid(), md5('qa:order:l5')::uuid, 'c0000000-0000-4000-8000-000000000001', 'DEPOSIT_BALANCE',
            'CARRIER', 7000000, 'VND', NOW(), CURRENT_DATE) $q$);

\echo '--- refunds from the ledger (payment.refund)'
SELECT pg_temp.expect_ok('L24 a refund of a customer''s credit (1M of the 2M left)', $q$
    UPDATE payment.customer_transfer SET unallocated_amount = 1000000, refunded_amount = 1000000
     WHERE id = md5('qa:tr:l11')::uuid;
    INSERT INTO payment.refund (id, transfer_id, amount, currency, status, created_at, source)
    VALUES (md5('qa:rf:l24')::uuid, md5('qa:tr:l11')::uuid, 1000000, 'VND', 'PENDING', NOW(), 'CUSTOMER_CREDIT') $q$);
SELECT pg_temp.expect_fail('L25 a refund from two places at once', $q$
    INSERT INTO payment.refund (id, transfer_id, allocation_id, amount, currency, status, created_at, source)
    VALUES (gen_random_uuid(), md5('qa:tr:l11')::uuid, md5('qa:al:l14')::uuid, 1, 'VND', 'PENDING', NOW(),
            'CUSTOMER_CREDIT') $q$);
SELECT pg_temp.expect_fail('L26 a refund from nowhere', $q$
    INSERT INTO payment.refund (id, amount, currency, status, created_at, source)
    VALUES (gen_random_uuid(), 1, 'VND', 'PENDING', NOW(), 'MANUAL') $q$);
SELECT pg_temp.expect_fail('L27 a customer-credit refund placed on an allocation', $q$
    INSERT INTO payment.refund (id, allocation_id, amount, currency, status, created_at, source)
    VALUES (gen_random_uuid(), md5('qa:al:l14')::uuid, 1, 'VND', 'PENDING', NOW(), 'CUSTOMER_CREDIT') $q$);

SELECT pg_temp.expect_ok('L30 the credit refund did not go through: the 1M is credit again', $q$
    UPDATE payment.refund SET status = 'FAILED' WHERE id = md5('qa:rf:l24')::uuid $q$);
DO $$
BEGIN
    IF (SELECT unallocated_amount <> 2000000 OR refunded_amount <> 0 FROM payment.customer_transfer
         WHERE id = md5('qa:tr:l11')::uuid) THEN
        RAISE EXCEPTION 'FAIL  L31 a failed credit refund did not give the money back';
    END IF;
    RAISE NOTICE 'ok    L31 credit 2M, nothing refunded';
END $$;
SELECT pg_temp.expect_ok('L32 retried: the 1M leaves the credit again', $q$
    UPDATE payment.refund SET status = 'PENDING' WHERE id = md5('qa:rf:l24')::uuid $q$);

\echo '--- the order counts a reversal once (ordering.order_payment)'
SELECT pg_temp.expect_ok('L28 a reversal counted as a negative row', $q$
    INSERT INTO ordering.order_payment (payment_id, order_id, amount, currency, captured_at)
    VALUES (md5('qa:al:l18')::uuid, md5('qa:order:l5')::uuid, -3000000, 'VND', NOW()) $q$);
SELECT pg_temp.expect_fail('L29 a payment of nothing', $q$
    INSERT INTO ordering.order_payment (payment_id, order_id, amount, currency, captured_at)
    VALUES (gen_random_uuid(), md5('qa:order:l5')::uuid, 0, 'VND', NOW()) $q$);

ROLLBACK;
\echo 'PASS 16_money_ledger'
