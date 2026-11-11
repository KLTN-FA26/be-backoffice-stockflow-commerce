-- =============================================================================
-- Adversarial checks + happy paths: an order waiting for stock (V20261108002900, team plan 4A-6; kltn-docs 17 §4.2
-- step 5, §4.9, §5.1, BR-04, BR-11). Needs the demo seed (demo.editor is the member of staff). Rolled back at the end.
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

-- Fixtures: three CONFIRMED orders of the first customer - one of a signed contract shipping from HCM, one of a signed
-- contract with no shipping warehouse chosen yet, one placed at checkout and paid in full (no contract) - and a second
-- warehouse.
INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, version,
                                 created_at)
VALUES (md5('qa23:other')::uuid, 'QA kho khac', 'ACTIVE', 'QAW', 'Somewhere', 'M', 10, 10, 0, NOW());
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, created_at, contract_signed_at, shipping_warehouse_id,
                                     shipping_warehouse_chosen_at, shipping_warehouse_chosen_by)
SELECT md5('qa23:order')::uuid, 'SO-QA-AWAIT-1', c.id, gen_random_uuid(), 'CONFIRMED', 2200000, 'VND', NOW(), NOW(),
       NOW(), w.id, NOW(), md5('demo:user:editor')::uuid
  FROM customer.customer c, warehouse.warehouse w WHERE w.prefix = 'HCM' ORDER BY c.id LIMIT 1;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, created_at, contract_signed_at)
SELECT md5('qa23:unchosen')::uuid, 'SO-QA-AWAIT-2', c.id, gen_random_uuid(), 'CONFIRMED', 2200000, 'VND', NOW(), NOW(),
       NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, created_at, contract_signed_at)
SELECT md5('qa23:checkout')::uuid, 'SO-QA-AWAIT-3', c.id, gen_random_uuid(), 'CONFIRMED', 1000000, 'VND', NOW(), NOW(),
       NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- the status and its edges (17 §5.1)'
SELECT pg_temp.expect_true('AS01 the six edges of 17 §5.1 are rows, and no other edge names AWAITING_STOCK',
    (SELECT array_agg(from_status || '>' || to_status ORDER BY from_status || '>' || to_status)
       FROM ordering.order_status_transition
      WHERE 'AWAITING_STOCK' IN (from_status, to_status))
    = ARRAY['AWAITING_STOCK>CANCELLED', 'AWAITING_STOCK>ON_HOLD', 'AWAITING_STOCK>PARTIALLY_FULFILLED',
            'AWAITING_STOCK>READY_TO_FULFILL', 'CONFIRMED>AWAITING_STOCK', 'ON_HOLD>AWAITING_STOCK']::text[]);
SELECT pg_temp.expect_fail('AS02 a status not in the lifecycle (ck_order_status)', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                         placed_at, created_at, contract_signed_at)
    SELECT gen_random_uuid(), 'SO-QA-AWAIT-4', c.id, gen_random_uuid(), 'AWAITING', 1000, 'VND', NOW(), NOW(), NOW()
      FROM customer.customer c ORDER BY c.id LIMIT 1 $q$);
SELECT pg_temp.expect_fail('AS03 an order placed at checkout waits for stock (no contract, BR-22)', $q$
    UPDATE ordering.customer_order SET status = 'AWAITING_STOCK' WHERE id = md5('qa23:checkout')::uuid $q$);
SELECT pg_temp.expect_fail('AS04 an order of a contract with no shipping warehouse waits for stock', $q$
    UPDATE ordering.customer_order SET status = 'AWAITING_STOCK' WHERE id = md5('qa23:unchosen')::uuid $q$);
SELECT pg_temp.expect_ok('AS05 an order of a contract shipping from HCM: CONFIRMED -> AWAITING_STOCK', $q$
    UPDATE ordering.customer_order SET status = 'AWAITING_STOCK' WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_fail('AS06 back to CONFIRMED (no edge)', $q$
    UPDATE ordering.customer_order SET status = 'CONFIRMED' WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_fail('AS07 straight to picking (no edge: released first)', $q$
    UPDATE ordering.customer_order SET status = 'PICKING' WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_fail('AS08 to IN_PRODUCTION, the legacy status (no edge)', $q$
    UPDATE ordering.customer_order SET status = 'IN_PRODUCTION', released_at = NOW(),
           released_by = md5('demo:user:editor')::uuid, warehouse_id = shipping_warehouse_id
     WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_fail('AS09 READY_TO_FULFILL without a release (ck_order_release_status)', $q$
    UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL' WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_fail('AS10 released from another warehouse than its shipping warehouse', $q$
    UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL', released_at = NOW(),
           released_by = md5('demo:user:editor')::uuid, warehouse_id = md5('qa23:other')::uuid
     WHERE id = md5('qa23:order')::uuid $q$);

\echo '--- held and resolved back where it was (17 §4.9 steps 35-36)'
SELECT pg_temp.expect_ok('AS11 held by Sales from AWAITING_STOCK', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'CUSTOMER',
           status_before_hold = 'AWAITING_STOCK' WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_fail('AS12 the hold resolved to CONFIRMED, not where it was held from', $q$
    UPDATE ordering.customer_order SET status = 'CONFIRMED', hold_reason = NULL, status_before_hold = NULL
     WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_ok('AS13 the hold resolved back to AWAITING_STOCK', $q$
    UPDATE ordering.customer_order SET status = 'AWAITING_STOCK', hold_reason = NULL, status_before_hold = NULL
     WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_fail('AS14 AWAITING_STOCK kept as the hold''s return while not on hold', $q$
    UPDATE ordering.customer_order SET status_before_hold = 'AWAITING_STOCK' WHERE id = md5('qa23:order')::uuid $q$);

\echo '--- printing started, once (BR-11)'
SELECT pg_temp.expect_fail('AS15 a fee kept before printing started (ck_order_fee_after_printing)', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST',
           cancellation_reason = 'CUSTOMER_REQUEST', cancellation_retained_amount = 0, cancellation_fee_amount = 500000,
           cancellation_fee_reason = 'printed', cancellation_fee_detail = 'cups printed'
     WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_ok('AS16 the first production order in production: printing started', $q$
    UPDATE ordering.customer_order SET printing_started_at = NOW() - INTERVAL '1 hour'
     WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_fail('AS17 printing started rewritten (tg_order_printing_started)', $q$
    UPDATE ordering.customer_order SET printing_started_at = NOW() WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_fail('AS18 printing started cleared', $q$
    UPDATE ordering.customer_order SET printing_started_at = NULL WHERE id = md5('qa23:order')::uuid $q$);
SELECT pg_temp.expect_ok('AS19 an update of the order that leaves printing started as it is', $q$
    UPDATE ordering.customer_order SET printing_started_at = printing_started_at, last_modified_at = NOW()
     WHERE id = md5('qa23:order')::uuid $q$);

\echo '--- released, or cancelled (17 §5.1)'
SAVEPOINT before_release;
SELECT pg_temp.expect_ok('AS20 released with every carton held: AWAITING_STOCK -> READY_TO_FULFILL', $q$
    UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL', released_at = NOW(),
           released_by = md5('demo:user:editor')::uuid, warehouse_id = shipping_warehouse_id
     WHERE id = md5('qa23:order')::uuid $q$);
ROLLBACK TO SAVEPOINT before_release;
SELECT pg_temp.expect_ok('AS21 cancelled while waiting, a fee kept once printing started', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST',
           cancellation_reason = 'CUSTOMER_REQUEST', cancellation_retained_amount = 0, cancellation_fee_amount = 500000,
           cancellation_fee_reason = 'printed', cancellation_fee_detail = 'cups printed'
     WHERE id = md5('qa23:order')::uuid $q$);

ROLLBACK;
