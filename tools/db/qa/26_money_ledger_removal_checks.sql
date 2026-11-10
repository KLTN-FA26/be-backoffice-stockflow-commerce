-- =============================================================================
-- The CHECKs the money ledger removal restates (V20261116000100, team plan 4B-11; kltn-docs 14 BR-15, 15 BR-07): an order is CONFIRMED by its signed contract (legacy rows stay), a cancellation fee only on a cancelled order, a shipment carries something, a return's withdrawal wants no credit transfer. Needs the demo seed; rolled back.
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

\echo '--- ck_order_confirmed_basis: a signed contract, or a free replacement (NOT VALID: old rows stay)'
SELECT pg_temp.expect_true('MR1 the check does not validate the rows that were there: legacy CONFIRMED orders stay',
    (SELECT NOT convalidated FROM pg_constraint WHERE conname = 'ck_order_confirmed_basis'));
SELECT pg_temp.expect_fail('MR2 a new CONFIRMED order with no contract signed', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                         created_at)
    VALUES (gen_random_uuid(), 'SO-QA-MR2', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'CONFIRMED',
            1000, 'VND', NOW(), NOW()) $q$);
SELECT pg_temp.expect_ok('MR3 a CONFIRMED order of a signed contract', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                         created_at, contract_signed_at)
    VALUES (md5('qa26:order')::uuid, 'SO-QA-MR3', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'CONFIRMED',
            1000, 'VND', NOW(), NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('MR4 a PENDING_PAYMENT order moved to CONFIRMED without its contract', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                         created_at)
    VALUES (md5('qa26:order:2')::uuid, 'SO-QA-MR4', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
            'PENDING_PAYMENT', 1000, 'VND', NOW(), NOW());
    UPDATE ordering.customer_order SET status = 'CONFIRMED' WHERE id = md5('qa26:order:2')::uuid $q$);

\echo '--- cancellation fee and what was retained: no longer bounded by a paid amount the order does not keep'
SELECT pg_temp.expect_fail('MR5 a cancellation fee on an order that is not cancelled', $q$
    UPDATE ordering.customer_order SET cancellation_fee_reason = 'x', cancellation_fee_detail = 'y',
           cancellation_fee_amount = 100, printing_started_at = NOW() WHERE id = md5('qa26:order')::uuid $q$);
SELECT pg_temp.expect_fail('MR6 a retained amount on an order that is not cancelled', $q$
    UPDATE ordering.customer_order SET cancellation_retained_amount = 0 WHERE id = md5('qa26:order')::uuid $q$);
SELECT pg_temp.expect_fail('MR7 a cancellation fee of nothing', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'x',
           cancellation_reason_code = 'CUSTOMER_REQUEST', cancellation_fee_reason = 'x', cancellation_fee_detail = 'y',
           cancellation_fee_amount = 0, printing_started_at = NOW() WHERE id = md5('qa26:order')::uuid $q$);
SELECT pg_temp.expect_fail('MR8 a fee reason without its detail and amount', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'x',
           cancellation_reason_code = 'CUSTOMER_REQUEST', cancellation_fee_reason = 'x', printing_started_at = NOW()
     WHERE id = md5('qa26:order')::uuid $q$);
SELECT pg_temp.expect_fail('MR9 a negative retained amount', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'x',
           cancellation_reason_code = 'CUSTOMER_REQUEST', cancellation_retained_amount = -1
     WHERE id = md5('qa26:order')::uuid $q$);
SELECT pg_temp.expect_ok('MR10 cancelled with a fee and what was retained, whatever was paid', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'x',
           cancellation_reason_code = 'CUSTOMER_REQUEST', cancellation_fee_reason = 'Printing done',
           cancellation_fee_detail = '4 cups', cancellation_fee_amount = 200, cancellation_retained_amount = 200,
           printing_started_at = NOW() WHERE id = md5('qa26:order')::uuid $q$);

\echo '--- ck_order_shipment_amounts: a shipment carries something'
SELECT pg_temp.expect_fail('MR11 a shipment of nothing', $q$
    INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, ready_at, created_at)
    VALUES (md5('qa26:shipment')::uuid, md5('qa26:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-MR-S1',
            md5('demo:wh:HCM')::uuid, NOW(), NOW());
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
    VALUES (md5('qa26:shipment')::uuid, md5('qa26:order')::uuid, 'HANDED_OVER', 0, NOW()) $q$);

\echo '--- a credit note books no transfer: the return check that wanted one is gone'
SELECT pg_temp.expect_true('MR12 ck_return_request_credit_transfer is gone',
    NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_return_request_credit_transfer'));
SELECT pg_temp.expect_true('MR13 the withdrawal check no longer names a credit transfer',
    (SELECT pg_get_constraintdef(oid) NOT LIKE '%transfer%' FROM pg_constraint WHERE conname = 'ck_return_request_withdrawal'));
SELECT pg_temp.expect_true('MR14 the return''s transfer foreign keys are gone',
    NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname IN ('fk_return_request_credit_transfer',
                                                              'fk_return_request_withdrawal_transfer')));

ROLLBACK;
\echo 'PASS 26_money_ledger_removal_checks'
