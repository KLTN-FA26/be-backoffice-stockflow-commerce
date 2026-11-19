-- =============================================================================
-- Adversarial checks: a task has a cancellation time exactly while it is cancelled (V20261207012100,
-- WF04 G8, kltn-docs 07 section 4.5 step 13). Needs the demo seed (HCM, the demo customer). Rolled back at the end.
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
-- Fixtures: one order and one task of it.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at)
VALUES (md5('qa66:order')::uuid, 'SO-QA-66', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT', 1, 'VND', NOW(), NOW());
INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
VALUES (md5('qa66:pick')::uuid, md5('qa66:order')::uuid, 'SO-QA-66', md5('demo:wh:HCM')::uuid, 'PICKING', NOW());

SELECT pg_temp.expect_true('CA1 the demo seed holds no cancelled task without its cancellation time, nor the reverse',
    NOT EXISTS (SELECT 1 FROM fulfillment.pick WHERE (status = 'CANCELLED') <> (cancelled_at IS NOT NULL)));
SELECT pg_temp.expect_true('CA2 the rule is validated: nothing written before breaks it',
    (SELECT convalidated FROM pg_constraint WHERE conname = 'ck_pick_cancelled_at'));

SELECT pg_temp.expect_fail_by('CA3 a task cancelled with no cancellation time', 'ck_pick_cancelled_at', $q$
    UPDATE fulfillment.pick SET status = 'CANCELLED', needs_put_back = TRUE WHERE id = md5('qa66:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('CA4 a cancellation time on a task that is not cancelled', 'ck_pick_cancelled_at', $q$
    UPDATE fulfillment.pick SET cancelled_at = NOW() WHERE id = md5('qa66:pick')::uuid $q$);
SELECT pg_temp.expect_ok('CA5 cancelled with its cancellation time', $q$
    UPDATE fulfillment.pick SET status = 'CANCELLED', needs_put_back = TRUE, cancelled_at = NOW()
     WHERE id = md5('qa66:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('CA6 a cancelled task loses its cancellation time', 'ck_pick_cancelled_at', $q$
    UPDATE fulfillment.pick SET cancelled_at = NULL WHERE id = md5('qa66:pick')::uuid $q$);

ROLLBACK;
