-- =============================================================================
-- Adversarial checks: WF-05 audit round 2, settlement (V20261223000100).
--   * an order the carrier's returns or losses ended with nothing made refundable closes from RETURNED /
--     PARTIALLY_FULFILLED (BE-45, audit r2 contract M-6) - never one a customer's return made RETURNED, never while a
--     shipment is on its way back or still to be decided, never once a settlement made money refundable;
--   * a completion hold keeps a RETURNED order from PARTIALLY_REFUNDED too (Q-17, audit r2 lifecycle L-1).
-- Needs the demo seed (an order with shipments). Rolled back at the end.
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

SELECT pg_temp.expect_true('the order closes without a refund from RETURNED and PARTIALLY_FULFILLED',
    (SELECT count(*) FROM ordering.order_status_transition
      WHERE (from_status, to_status) IN (('RETURNED', 'CLOSED'), ('PARTIALLY_FULFILLED', 'CLOSED'))) = 2);

-- A delivered order with shipments, nothing settled, not held. The states below are set without their triggers (what
-- is checked is the closing edge, not how the order got there).
SELECT o.id AS ord, o.order_number AS ord_number, o.customer_id AS ord_customer, o.currency AS ord_currency
  FROM ordering.customer_order o
 WHERE o.status = 'DELIVERED'
   AND EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = o.id)
   AND NOT EXISTS (SELECT 1 FROM payment.order_settlement ps WHERE ps.order_id = o.id)
   AND NOT EXISTS (SELECT 1 FROM ordering.order_completion_hold h WHERE h.order_id = o.id AND h.released_at IS NULL)
 ORDER BY o.id LIMIT 1 \gset
SELECT u.id AS some_user FROM identity.app_user u ORDER BY u.id LIMIT 1 \gset

-- ---------------------------------------------------------------- a customer's return made it RETURNED
SET LOCAL session_replication_role = replica;
UPDATE ordering.customer_order SET status = 'RETURNED' WHERE id = :'ord';
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_fail('an order a customer''s return made RETURNED (a shipment DELIVERED) is not closed from there',
    format('UPDATE ordering.customer_order SET status = ''CLOSED'', closed_at = now() WHERE id = %L', :'ord'));

-- ---------------------------------------------------------------- the completion hold (Q-17, L-1)
INSERT INTO ordering.order_completion_hold (id, order_id, reason, note, held_at, held_by, created_at)
VALUES ('00000000-0000-4000-8000-0000000094a1', :'ord', 'DELIVERY_DISPUTED', NULL, now(), :'some_user', now());
SELECT pg_temp.expect_fail('a held RETURNED order does not become PARTIALLY_REFUNDED',
    format('UPDATE ordering.customer_order SET status = ''PARTIALLY_REFUNDED'', refunded_at = now() WHERE id = %L',
           :'ord'));
UPDATE ordering.order_completion_hold SET released_at = now(), released_by = :'some_user', release_note = 'checked'
 WHERE id = '00000000-0000-4000-8000-0000000094a1';

-- ---------------------------------------------------------------- ended by the carrier
-- Every shipment brought back; one of them still on its way back (its receipt not confirmed).
SET LOCAL session_replication_role = replica;
UPDATE ordering.order_shipment SET status = 'RETURNED', failure_reason = 'REFUSED', units_closed_at = NULL
 WHERE order_id = :'ord';
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_fail('not closed while a shipment is on its way back',
    format('UPDATE ordering.customer_order SET status = ''CLOSED'', closed_at = now() WHERE id = %L', :'ord'));

SET LOCAL session_replication_role = replica;
UPDATE ordering.order_shipment SET units_closed_at = coalesce(outcome_at, now()) WHERE order_id = :'ord';
-- A settlement of the order made money refundable: the refund branch's.
INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
                                      source_id, currency, collected_amount, surplus_amount, refundable_amount,
                                      settled_at)
VALUES ('00000000-0000-4000-8000-0000000094b1', :'ord', '00000000-0000-4000-8000-0000000094b2', :'ord_customer',
        :'ord_number', 'HD-QA-94', 'ORDER_CANCELLED', :'ord', :'ord_currency', 1000, 1000, 1000, now());
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_fail('not closed without a refund once a settlement made money refundable',
    format('UPDATE ordering.customer_order SET status = ''CLOSED'', closed_at = now() WHERE id = %L', :'ord'));

SET LOCAL session_replication_role = replica;
DELETE FROM payment.order_settlement WHERE id = '00000000-0000-4000-8000-0000000094b1';
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_ok('ended by the carrier, nothing refundable, not held: it closes from RETURNED',
    format('UPDATE ordering.customer_order SET status = ''CLOSED'', closed_at = now() WHERE id = %L', :'ord'));

ROLLBACK;
