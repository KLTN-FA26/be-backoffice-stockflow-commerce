-- =============================================================================
-- Adversarial checks: WF-05 order lifecycle rules stated in the database (V20261217000100..000600; fix group G1).
--   * a shipment's units are closed once, only when it came back or was lost (Q-7, Q-12; L-I8);
--   * a contract is COMPLETED only when goods of its order were delivered, LIQUIDATED only when none were (Q-6; L-I4);
--   * a completion hold keeps the order from CLOSED too (Q-17; L-I3);
--   * the origin of a claim exists only on the return path (BE-40);
--   * the new edges of the transition table (BE-40, X-8, BE-46).
-- Needs the demo seed (delivered shipments, signed contracts, completed orders). Rolled back at the end.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- ---------------------------------------------------------------- orders from before RTM-RETURNED (Q-13 = a)
-- Every environment is rebuilt on deploy (QD-1), so none should exist. Reported, never refused: such an order keeps its
-- exit (an OPEN delivery failure case at its receipt, then Sales cancel what was not delivered, BE-16).
DO $$
DECLARE
    legacy BIGINT;
BEGIN
    SELECT count(*) INTO legacy FROM ordering.customer_order
     WHERE status = 'ON_HOLD' AND hold_reason = 'DELIVERY_FAILED';
    RAISE NOTICE 'ok    orders held for a failed delivery before RTM-RETURNED: % (expected 0 after a rebuild)', legacy;
END $$;

-- ---------------------------------------------------------------- units closed (Q-7, Q-12)
SELECT s.id AS delivered_shipment FROM ordering.order_shipment s WHERE s.status = 'DELIVERED'
 ORDER BY s.id LIMIT 1 \gset
SELECT pg_temp.expect_fail('a delivered shipment never has its units closed',
    format('UPDATE ordering.order_shipment SET units_closed_at = now() WHERE id = %L', :'delivered_shipment'));

-- The same shipment as if it had failed: only a LOST one is closed while DELIVERY_FAILED.
UPDATE ordering.order_shipment SET status = 'DELIVERY_FAILED', failure_reason = 'REFUSED'
 WHERE id = :'delivered_shipment';
SELECT pg_temp.expect_fail('a shipment failed as REFUSED is not closed as lost',
    format('UPDATE ordering.order_shipment SET units_closed_at = now() WHERE id = %L', :'delivered_shipment'));
UPDATE ordering.order_shipment SET failure_reason = 'LOST' WHERE id = :'delivered_shipment';
SELECT pg_temp.expect_ok('a shipment failed as LOST has its units closed',
    format('UPDATE ordering.order_shipment SET units_closed_at = TIMESTAMPTZ ''2026-11-20 10:00+07'' WHERE id = %L',
           :'delivered_shipment'));
SELECT pg_temp.expect_fail('units closed stay closed (no second closure)',
    format('UPDATE ordering.order_shipment SET units_closed_at = now() WHERE id = %L', :'delivered_shipment'));
SELECT pg_temp.expect_fail('units closed stay closed (never reopened)',
    format('UPDATE ordering.order_shipment SET units_closed_at = NULL WHERE id = %L', :'delivered_shipment'));
SELECT pg_temp.expect_fail('a lost-closed shipment is not delivered again',
    format('UPDATE ordering.order_shipment SET status = ''DELIVERED'', failure_reason = NULL WHERE id = %L',
           :'delivered_shipment'));
SELECT pg_temp.expect_fail('nor does its reason change',
    format('UPDATE ordering.order_shipment SET failure_reason = ''DAMAGED'' WHERE id = %L', :'delivered_shipment'));
SELECT pg_temp.expect_ok('found and brought back after all: RETURNED, still closed',
    format('UPDATE ordering.order_shipment SET status = ''RETURNED'' WHERE id = %L', :'delivered_shipment'));

-- ---------------------------------------------------------------- contracts (Q-6)
SELECT c.id AS delivered_contract, c.order_id AS delivered_order FROM ordering.sales_contract c
 WHERE c.status = 'SIGNED'
   AND EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = c.order_id AND s.status = 'DELIVERED')
 ORDER BY c.id LIMIT 1 \gset
SELECT pg_temp.expect_fail('a contract whose order delivered goods is not liquidated',
    format('UPDATE ordering.sales_contract SET status = ''LIQUIDATED'', liquidated_at = now() WHERE id = %L',
           :'delivered_contract'));
SELECT pg_temp.expect_fail('LIQUIDATED carries its moment',
    format('UPDATE ordering.sales_contract SET status = ''LIQUIDATED'' WHERE id = %L', :'delivered_contract'));

-- The same order as if every shipment had come back: nothing delivered.
UPDATE ordering.order_shipment SET status = 'RETURNED', failure_reason = 'REFUSED',
       units_closed_at = coalesce(units_closed_at, outcome_at, now())
 WHERE order_id = :'delivered_order' AND status = 'DELIVERED';
SELECT pg_temp.expect_fail('a contract whose order delivered nothing is not completed',
    format('UPDATE ordering.sales_contract SET status = ''COMPLETED'', completed_at = now() WHERE id = %L',
           :'delivered_contract'));
SELECT pg_temp.expect_ok('it is liquidated',
    format('UPDATE ordering.sales_contract SET status = ''LIQUIDATED'', liquidated_at = now() WHERE id = %L',
           :'delivered_contract'));
SELECT pg_temp.expect_fail('a liquidated contract does not change',
    format('UPDATE ordering.sales_contract SET liquidated_at = now() - interval ''1 day'' WHERE id = %L',
           :'delivered_contract'));
SELECT pg_temp.expect_fail('nor does it move on',
    format('UPDATE ordering.sales_contract SET status = ''COMPLETED'', completed_at = now(), liquidated_at = NULL '
           'WHERE id = %L', :'delivered_contract'));

-- ---------------------------------------------------------------- completion hold (Q-17)
-- A COMPLETED order: the seed may have none, so a delivered one is set completed without its triggers (the transition
-- is not what is checked here).
SELECT o.id AS completed_order FROM ordering.customer_order o WHERE o.status = 'DELIVERED'
   AND NOT EXISTS (SELECT 1 FROM ordering.order_completion_hold h WHERE h.order_id = o.id AND h.released_at IS NULL)
 ORDER BY o.id DESC LIMIT 1 \gset
SET LOCAL session_replication_role = replica;
UPDATE ordering.customer_order SET status = 'COMPLETED', completed_at = now() WHERE id = :'completed_order';
SET LOCAL session_replication_role = origin;
SELECT u.id AS some_user FROM identity.app_user u ORDER BY u.id LIMIT 1 \gset
INSERT INTO ordering.order_completion_hold (id, order_id, reason, note, held_at, held_by, created_at)
VALUES ('00000000-0000-4000-8000-0000000091a1', :'completed_order', 'DELIVERY_DISPUTED', NULL, now(), :'some_user',
        now());
SELECT pg_temp.expect_fail('a held order is not closed',
    format('UPDATE ordering.customer_order SET status = ''CLOSED'', closed_at = now() WHERE id = %L',
           :'completed_order'));
UPDATE ordering.order_completion_hold SET released_at = now(), released_by = :'some_user', release_note = 'checked'
 WHERE id = '00000000-0000-4000-8000-0000000091a1';
SELECT pg_temp.expect_ok('released, it closes',
    format('UPDATE ordering.customer_order SET status = ''CLOSED'', closed_at = now() WHERE id = %L',
           :'completed_order'));

-- ---------------------------------------------------------------- origin of a claim (BE-40)
SELECT o.id AS delivered_order2 FROM ordering.customer_order o
 WHERE o.status = 'DELIVERED' AND o.completed_at IS NULL ORDER BY o.id LIMIT 1 \gset
SELECT pg_temp.expect_fail('no origin off the return path',
    format('UPDATE ordering.customer_order SET status_before_return = ''DELIVERED'' WHERE id = %L',
           :'delivered_order2'));
SELECT pg_temp.expect_ok('a claim opened from DELIVERED remembers it',
    format('UPDATE ordering.customer_order SET status = ''RETURN_REQUESTED'', status_before_return = ''DELIVERED'' '
           'WHERE id = %L', :'delivered_order2'));
SELECT pg_temp.expect_fail('COMPLETED is an origin only of a completed order',
    format('UPDATE ordering.customer_order SET status_before_return = ''COMPLETED'' WHERE id = %L',
           :'delivered_order2'));

-- ---------------------------------------------------------------- edges (BE-40, X-8, BE-46)
SELECT pg_temp.expect_true('the return path leads back to the delivery path',
    (SELECT count(*) FROM ordering.order_status_transition
      WHERE (from_status, to_status) IN (('RETURN_REQUESTED', 'COMPLETED'), ('RETURN_REQUESTED', 'REFUNDED'),
                                         ('RETURN_REQUESTED', 'PARTIALLY_REFUNDED'),
                                         ('RETURN_REQUESTED', 'DELIVERY_FAILED'),
                                         ('RETURN_REQUESTED', 'PARTIALLY_FULFILLED'), ('RETURNED', 'DELIVERED'),
                                         ('RETURNED', 'COMPLETED'), ('RETURNED', 'DELIVERY_FAILED'),
                                         ('RETURNED', 'PARTIALLY_FULFILLED'))) = 9);

ROLLBACK;
