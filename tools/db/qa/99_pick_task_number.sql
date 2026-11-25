-- =============================================================================
-- Adversarial checks: a pick task's human number PK-yyyyMMdd-nnnn (V20261228000200): every task has one, no two share
-- one, it never changes, and a task written without one draws the next of its business day from
-- platform.document_sequence. Needs the demo seed (HCM, the demo customer). Rolled back at the end.
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

-- The same, and it is the named rule that refuses it: a statement that breaks something else proves nothing.
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
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- ---------------------------------------------------------------------------------------------
-- Fixtures: three orders; the first gets a task written without a number, as a fixture or a seed writes one.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at)
VALUES (md5('qa99tn:order1')::uuid, 'SO-QA-99TN-1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT', 1, 'VND', NOW(), NOW()),
       (md5('qa99tn:order2')::uuid, 'SO-QA-99TN-2', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT', 1, 'VND', NOW(), NOW()),
       (md5('qa99tn:order3')::uuid, 'SO-QA-99TN-3', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT', 1, 'VND', NOW(), NOW());
INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
VALUES (md5('qa99tn:pick1')::uuid, md5('qa99tn:order1')::uuid, 'SO-QA-99TN-1', md5('demo:wh:HCM')::uuid, 'PENDING',
        TIMESTAMPTZ '2031-03-04 18:00:00+00');

SELECT pg_temp.expect_true('TN1 every task of the demo seed has a number of the form PK-yyyyMMdd-nnnn',
    NOT EXISTS (SELECT 1 FROM fulfillment.pick WHERE task_number IS NULL OR task_number !~ '^PK-[0-9]{8}-[0-9]{4,}$'));
SELECT pg_temp.expect_true('TN2 the sequence is past every number given: no later task can draw one already held',
    NOT EXISTS (SELECT 1 FROM fulfillment.pick p
                 WHERE substring(p.task_number FROM 13)::bigint > COALESCE(
                       (SELECT q.last_value FROM platform.document_sequence q
                         WHERE q.document_type = 'PK'
                           AND q.sequence_date = to_date(substring(p.task_number FROM 4 FOR 8), 'YYYYMMDD')), 0)));
SELECT pg_temp.expect_true('TN3 a task written without a number draws the first of its Saigon day (18:00 UTC is the next day there)',
    (SELECT task_number FROM fulfillment.pick WHERE id = md5('qa99tn:pick1')::uuid) = 'PK-20310305-0001');

SELECT pg_temp.expect_fail_by('TN4 a task number changed', 'tg_pick_task_number', $q$
    UPDATE fulfillment.pick SET task_number = 'PK-20310305-0099' WHERE id = md5('qa99tn:pick1')::uuid $q$);
SELECT pg_temp.expect_fail_by('TN5 a task number dropped', 'tg_pick_task_number', $q$
    UPDATE fulfillment.pick SET task_number = NULL WHERE id = md5('qa99tn:pick1')::uuid $q$);
SELECT pg_temp.expect_fail_by('TN6 two tasks with one number', 'uk_pick_task_number', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, task_number)
    VALUES (md5('qa99tn:pick2')::uuid, md5('qa99tn:order2')::uuid, 'SO-QA-99TN-2', md5('demo:wh:HCM')::uuid, 'PENDING',
            NOW(), 'PK-20310305-0001') $q$);
SELECT pg_temp.expect_fail_by('TN7 a number not of the form PK-yyyyMMdd-nnnn', 'ck_pick_task_number', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, task_number)
    VALUES (md5('qa99tn:pick3')::uuid, md5('qa99tn:order3')::uuid, 'SO-QA-99TN-3', md5('demo:wh:HCM')::uuid, 'PENDING',
            NOW(), 'TASK-1') $q$);
SELECT pg_temp.expect_ok('TN8 the application''s own number is kept as given', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, task_number)
    VALUES (md5('qa99tn:pick2')::uuid, md5('qa99tn:order2')::uuid, 'SO-QA-99TN-2', md5('demo:wh:HCM')::uuid, 'PENDING',
            NOW(), 'PK-20310305-0002') $q$);
SELECT pg_temp.expect_true('TN9 the application''s number was kept',
    (SELECT task_number FROM fulfillment.pick WHERE id = md5('qa99tn:pick2')::uuid) = 'PK-20310305-0002');

ROLLBACK;
