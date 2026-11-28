-- =============================================================================
-- Adversarial checks + happy paths: who bears the fee of a cancellation or a closure of the rest (V20270128000550;
-- kltn-docs 17 §4.8 steps 30 and 33, 15 §4.6 steps 18-19; D46, D50) - the bearer on the order, on the closure row and
-- on the settlement, and the settlement's fee terms read as "the fee entered". Fixtures are written with triggers and
-- foreign keys off (session_replication_role = replica): only the CHECKs and indexes of these columns are under test.
-- Needs the demo seed (c0000000-0000-4000-8000-000000000001 is its first customer). Rolled back at the end.
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
END $$;

SET session_replication_role = replica;

-- ---- ordering.customer_order.cancellation_fee_bearer ------------------------------------------------------------

-- A cancelled order that kept an agreed fee after printing started, with and without its bearer.
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at, printing_started_at)
VALUES (md5('qa99f:o1')::uuid, 'SO-QA99F-O1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DRAFT',
        2000000, 'VND', NOW(), NOW(), NOW()),
       (md5('qa99f:o2')::uuid, 'SO-QA99F-O2', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DRAFT',
        2000000, 'VND', NOW(), NOW(), NULL);

\echo '--- the order: an agreed fee has its bearer; a bearer needs a cancelled order whose printing started'
SELECT pg_temp.expect_fail('FB01 an agreed fee with no bearer', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'OUT_OF_STOCK',
           cancellation_reason = 'OUT_OF_STOCK', cancellation_fee_amount = 500000, cancellation_fee_reason = 'Cups printed',
           cancellation_fee_detail = 'Printed 1,000'
     WHERE id = md5('qa99f:o1')::uuid $q$);
SELECT pg_temp.expect_fail('FB02 a bearer outside CUSTOMER / COMPANY', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'OUT_OF_STOCK',
           cancellation_reason = 'OUT_OF_STOCK', cancellation_fee_amount = 500000, cancellation_fee_reason = 'Cups printed',
           cancellation_fee_detail = 'Printed 1,000', cancellation_fee_bearer = 'BOTH'
     WHERE id = md5('qa99f:o1')::uuid $q$);
SELECT pg_temp.expect_fail('FB03 a bearer on an order that is not cancelled', $q$
    UPDATE ordering.customer_order SET cancellation_fee_bearer = 'COMPANY' WHERE id = md5('qa99f:o1')::uuid $q$);
SELECT pg_temp.expect_fail('FB04 a bearer on an order whose printing never started (17 BR-11)', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'OUT_OF_STOCK',
           cancellation_reason = 'OUT_OF_STOCK', cancellation_fee_bearer = 'COMPANY'
     WHERE id = md5('qa99f:o2')::uuid $q$);
SELECT pg_temp.expect_ok('FB05 the company bears an agreed fee', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'OUT_OF_STOCK',
           cancellation_reason = 'OUT_OF_STOCK', cancellation_fee_amount = 500000, cancellation_fee_reason = 'Cups printed',
           cancellation_fee_detail = 'Printed 1,000', cancellation_fee_bearer = 'COMPANY'
     WHERE id = md5('qa99f:o1')::uuid $q$);

-- ---- ordering.order_remainder_closure.fee_amount / fee_bearer ----------------------------------------------------

\echo '--- a closure''s fee and its bearer come together'
SELECT pg_temp.expect_fail('FB10 a closure fee with no bearer', $q$
    INSERT INTO ordering.order_remainder_closure (id, order_id, closure_id, reason_code, closed_at, fee_amount)
    VALUES (gen_random_uuid(), md5('qa99f:o1')::uuid, gen_random_uuid(), 'OUT_OF_STOCK', NOW(), 100000) $q$);
SELECT pg_temp.expect_fail('FB11 a closure bearer with no fee', $q$
    INSERT INTO ordering.order_remainder_closure (id, order_id, closure_id, reason_code, closed_at, fee_bearer)
    VALUES (gen_random_uuid(), md5('qa99f:o1')::uuid, gen_random_uuid(), 'OUT_OF_STOCK', NOW(), 'COMPANY') $q$);
SELECT pg_temp.expect_fail('FB12 a closure fee of zero', $q$
    INSERT INTO ordering.order_remainder_closure (id, order_id, closure_id, reason_code, closed_at, fee_amount, fee_bearer)
    VALUES (gen_random_uuid(), md5('qa99f:o1')::uuid, gen_random_uuid(), 'OUT_OF_STOCK', NOW(), 0, 'CUSTOMER') $q$);
SELECT pg_temp.expect_fail('FB13 a closure bearer outside CUSTOMER / COMPANY', $q$
    INSERT INTO ordering.order_remainder_closure (id, order_id, closure_id, reason_code, closed_at, fee_amount, fee_bearer)
    VALUES (gen_random_uuid(), md5('qa99f:o1')::uuid, gen_random_uuid(), 'OUT_OF_STOCK', NOW(), 100000, 'ANYONE') $q$);
SELECT pg_temp.expect_ok('FB14 a closure without a fee', $q$
    INSERT INTO ordering.order_remainder_closure (id, order_id, closure_id, reason_code, closed_at)
    VALUES (gen_random_uuid(), md5('qa99f:o1')::uuid, gen_random_uuid(), 'OUT_OF_STOCK', NOW()) $q$);
SELECT pg_temp.expect_ok('FB15 a closure whose fee the company bears', $q$
    INSERT INTO ordering.order_remainder_closure (id, order_id, closure_id, reason_code, closed_at, fee_amount, fee_bearer)
    VALUES (gen_random_uuid(), md5('qa99f:o1')::uuid, gen_random_uuid(), 'OUT_OF_STOCK', NOW(), 100000, 'COMPANY') $q$);

-- ---- payment.order_settlement.fee_bearer / company_fee_amount ----------------------------------------------------

-- One settlement per case: 1,000,000 collected, all of it surplus (a cancellation).
CREATE FUNCTION pg_temp.settlement(label TEXT, fee NUMERIC, percent NUMERIC, reason TEXT, bearer TEXT, company NUMERIC)
RETURNS TEXT LANGUAGE plpgsql AS $$
BEGIN
    RETURN format($f$DELETE FROM payment.order_settlement WHERE order_id = md5('qa99f:o1')::uuid;
        INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number,
            contract_number, kind, source_id, currency, collected_amount, surplus_amount, fee_amount, fee_percent,
            fee_reason, fee_detail, fee_covered_amount, refundable_amount, fee_owed_amount, fee_bearer,
            company_fee_amount, settled_at)
        VALUES (gen_random_uuid(), md5('qa99f:o1')::uuid, md5('qa99f:c')::uuid,
                'c0000000-0000-4000-8000-000000000001', 'SO-QA99F-O1', 'HD-QA99F-1', 'ORDER_CANCELLED',
                md5('qa99f:o1')::uuid, 'VND', 1000000, 1000000, %s, %s, %L, %L, LEAST(%s, 1000000),
                1000000 - LEAST(%s, 1000000), %s - LEAST(%s, 1000000), %L, %s, NOW())$f$,
        fee, coalesce(percent::text, 'NULL'), reason, CASE WHEN reason IS NULL THEN NULL ELSE 'detail' END, fee, fee,
        fee, fee, bearer, company);
END $$;

\echo '--- the settlement: the fee entered is charged to the customer or kept as the company''s cost'
SELECT pg_temp.expect_ok('FB20 no fee, no bearer', pg_temp.settlement('x', 0, NULL, NULL, NULL, 0));
SELECT pg_temp.expect_ok('FB21 the customer bears an agreed fee: charged, nothing for the company',
    pg_temp.settlement('x', 400000, NULL, 'Cups printed', 'CUSTOMER', 0));
SELECT pg_temp.expect_ok('FB22 the company bears an agreed fee: the customer is charged nothing',
    pg_temp.settlement('x', 0, NULL, 'Cups printed', 'COMPANY', 400000));
SELECT pg_temp.expect_ok('FB23 the company bears a share of 10 percent of what was collected',
    pg_temp.settlement('x', 0, 10, NULL, 'COMPANY', 100000));
SELECT pg_temp.expect_ok('FB24 the company bears a share that rounds to nothing',
    pg_temp.settlement('x', 0, 0.01, NULL, 'COMPANY', 100));
SELECT pg_temp.expect_fail('FB25 a fee with no bearer',
    pg_temp.settlement('x', 400000, NULL, 'Cups printed', NULL, 0));
SELECT pg_temp.expect_fail('FB26 a bearer with no fee', pg_temp.settlement('x', 0, NULL, NULL, 'COMPANY', 0));
SELECT pg_temp.expect_fail('FB27 a bearer outside CUSTOMER / COMPANY',
    pg_temp.settlement('x', 400000, NULL, 'Cups printed', 'BOTH', 0));
SELECT pg_temp.expect_fail('FB28 the company bears it and the customer is charged it too',
    pg_temp.settlement('x', 400000, NULL, 'Cups printed', 'COMPANY', 400000));
SELECT pg_temp.expect_fail('FB29 the customer bears it and the company records a cost as well',
    pg_temp.settlement('x', 400000, NULL, 'Cups printed', 'CUSTOMER', 400000));
SELECT pg_temp.expect_fail('FB30 the company bears an agreed fee but no cost is recorded',
    pg_temp.settlement('x', 0, NULL, 'Cups printed', 'COMPANY', 0));
SELECT pg_temp.expect_fail('FB31 a share the company bears with a cost that is not the share of what was collected',
    pg_temp.settlement('x', 0, 10, NULL, 'COMPANY', 99999));

ROLLBACK;
