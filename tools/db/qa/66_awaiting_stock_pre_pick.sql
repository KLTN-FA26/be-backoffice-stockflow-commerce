-- =============================================================================
-- Adversarial checks + happy paths: picking an order awaiting stock before its release (V20261207013100; owner
-- decision RTM-AWAIT-1118; kltn-docs 07 §4.1 step 2, §4.3 step 7; 17 §4.4 steps 15-16). A pick names a released
-- delivery batch of its order - or, while the order is AWAITING_STOCK and not released, a batch of its own contract.
-- Needs the demo seed (HCM, the first customer, demo.editor). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail_by(label TEXT, rule TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM NOT LIKE '%' || rule || '%' THEN
            RAISE EXCEPTION 'FAIL  %: refused, but not by %: %', label, rule, SQLERRM;
        END IF;
        RAISE NOTICE 'ok    %  [%]', label, rule;
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

-- Fixtures, written with triggers off (how an order is signed and held is not this file's subject): order A, signed and
-- AWAITING_STOCK at HCM, not released, its contract (its status is not this file's subject) with batches A1 and A2;
-- order C, signed and CONFIRMED at HCM, its contract with batch C1. Nothing is released.
SET session_replication_role = replica;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, created_at, contract_signed_at, shipping_warehouse_id,
                                     shipping_warehouse_chosen_at, shipping_warehouse_chosen_by)
SELECT md5('qa66:' || k)::uuid, 'SO-QA66-' || upper(k), c.id, gen_random_uuid(), s, 1000, 'VND', NOW(), NOW(), NOW(),
       md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid
  FROM (VALUES ('a', 'AWAITING_STOCK'), ('c', 'CONFIRMED')) AS o(k, s),
       (SELECT id FROM customer.customer ORDER BY id LIMIT 1) c;
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds)
SELECT md5('qa66:contract:' || k)::uuid, 'HD-QA66-' || upper(k), md5('qa66:' || k)::uuid, 'DRAFT', 'Seller', '0101',
       'A St', 'Ms A', 'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 0
  FROM (VALUES ('a'), ('c')) AS o(k);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa66:a1')::uuid, md5('qa66:contract:a')::uuid, 1, CURRENT_DATE + 7, CURRENT_DATE + 9),
       (md5('qa66:a2')::uuid, md5('qa66:contract:a')::uuid, 2, CURRENT_DATE + 21, CURRENT_DATE + 23),
       (md5('qa66:c1')::uuid, md5('qa66:contract:c')::uuid, 1, CURRENT_DATE + 7, CURRENT_DATE + 9);
SET session_replication_role = origin;

\echo '--- a pick of a batch not released: only for an order awaiting stock, of its own contract'
SELECT pg_temp.expect_ok('PP1 an order awaiting stock picks its batch before the release', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, delivery_batch_id)
    VALUES (md5('qa66:pick:a')::uuid, md5('qa66:a')::uuid, 'SO-QA66-A', md5('demo:wh:HCM')::uuid, 'PENDING', NOW(),
            md5('qa66:a1')::uuid) $q$);
SELECT pg_temp.expect_fail_by('PP2 a confirmed order does not pick a batch it has not released', 'not a released batch', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, delivery_batch_id)
    VALUES (md5('qa66:pick:c')::uuid, md5('qa66:c')::uuid, 'SO-QA66-C', md5('demo:wh:HCM')::uuid, 'PENDING', NOW(),
            md5('qa66:c1')::uuid) $q$);
SELECT pg_temp.expect_fail_by('PP3 an order awaiting stock does not pick another order''s batch', 'not a released batch', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, delivery_batch_id)
    VALUES (md5('qa66:pick:x')::uuid, md5('qa66:a')::uuid, 'SO-QA66-A', md5('demo:wh:HCM')::uuid, 'PENDING', NOW(),
            md5('qa66:c1')::uuid) $q$);
SELECT pg_temp.expect_fail_by('PP4 nor is a pick moved onto another order''s batch', 'not a released batch', $q$
    UPDATE fulfillment.pick SET delivery_batch_id = md5('qa66:c1')::uuid WHERE id = md5('qa66:pick:a')::uuid $q$);
-- Order A released (written with triggers off, as the fixtures): from now on its picks name released batches.
SET session_replication_role = replica;
UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL', released_at = NOW(),
       released_by = md5('demo:user:editor')::uuid, warehouse_id = md5('demo:wh:HCM')::uuid
 WHERE id = md5('qa66:a')::uuid;
SET session_replication_role = origin;
SELECT pg_temp.expect_fail_by('PP5 once released, an order picks its released batches only', 'not a released batch', $q$
    UPDATE fulfillment.pick SET delivery_batch_id = md5('qa66:a2')::uuid WHERE id = md5('qa66:pick:a')::uuid $q$);

ROLLBACK;
