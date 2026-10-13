-- =============================================================================
-- Outbound fulfillment: the order number and warehouse a task carries (SCRUM-466,
-- V20261014000100; kltn-docs 07 §4, BR-SEC-002). Needs the demo seed. Rolled back at the end.
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
                                     created_at, warehouse_id)
VALUES (md5('qa:order:f1')::uuid, 'SO-QA-F1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
        'IN_FULFILMENT', 12000000, 'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid);

\echo '--- the task carries its order number and warehouse (fulfillment.pick)'
SELECT pg_temp.expect_fail('F1 a pick with no warehouse', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, status, created_at)
    VALUES (md5('qa:pick:f1')::uuid, md5('qa:order:f1')::uuid, 'SO-QA-F1', 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_fail('F2 a pick for a warehouse that does not exist', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
    VALUES (md5('qa:pick:f1')::uuid, md5('qa:order:f1')::uuid, 'SO-QA-F1', gen_random_uuid(), 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_fail('F3 a pick with no order number', $q$
    INSERT INTO fulfillment.pick (id, order_id, warehouse_id, status, created_at)
    VALUES (md5('qa:pick:f1')::uuid, md5('qa:order:f1')::uuid, md5('demo:wh:HCM')::uuid, 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_ok('F4 a pick with both', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
    VALUES (md5('qa:pick:f1')::uuid, md5('qa:order:f1')::uuid, 'SO-QA-F1', md5('demo:wh:HCM')::uuid, 'PENDING', NOW()) $q$);

ROLLBACK;
\echo 'PASS 09_outbound'
