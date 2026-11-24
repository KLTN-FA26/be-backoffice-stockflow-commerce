-- =============================================================================
-- Adversarial checks: WF-05 audit round 3, lifecycle and money (V20261224000100, V20261224000110).
--   * LM-1: a completion hold keeps a RETURNED order from entering the refund branch at all - PARTIALLY_REFUNDED and
--     REFUNDED alike - except a claim opened from the refund branch going back where it was (BE-40);
--   * LM-3: what a closure of the rest carries onto its fee item is a closure's only, within what it took off, left
--     out of its surplus, and owed on its fee item.
-- Needs the demo seed (an order with shipments). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail_with(label TEXT, stmt TEXT, needle TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    constraint_name TEXT;
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS constraint_name = CONSTRAINT_NAME;
        IF position(needle IN SQLERRM) = 0 AND position(needle IN COALESCE(constraint_name, '')) = 0 THEN
            RAISE EXCEPTION 'FAIL  %: refused for another reason than %: %', label, needle, SQLERRM;
        END IF;
        RAISE NOTICE 'ok    %  [%]', label, left(SQLERRM, 110);
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

CREATE FUNCTION pg_temp.expect_not_refused_by(label TEXT, stmt TEXT, needle TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    constraint_name TEXT;
BEGIN
    BEGIN
        EXECUTE stmt;
        RAISE EXCEPTION USING ERRCODE = 'P0099', MESSAGE = 'undo';
    EXCEPTION
        WHEN SQLSTATE 'P0099' THEN
            RAISE NOTICE 'ok    %', label;
            RETURN;
        WHEN OTHERS THEN
            GET STACKED DIAGNOSTICS constraint_name = CONSTRAINT_NAME;
            IF position(needle IN SQLERRM) > 0 OR position(needle IN COALESCE(constraint_name, '')) > 0 THEN
                RAISE EXCEPTION 'FAIL  %: refused by %: %', label, needle, SQLERRM;
            END IF;
            RAISE NOTICE 'ok    %  [not by %; %]', label, needle, left(SQLERRM, 80);
            RETURN;
    END;
END $$;

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    RAISE NOTICE 'ok    %', label;
END $$;

SELECT o.id AS ord, o.order_number AS ord_number, o.customer_id AS ord_customer, o.currency AS ord_currency
  FROM ordering.customer_order o
 WHERE o.status = 'DELIVERED'
   AND EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = o.id)
   AND NOT EXISTS (SELECT 1 FROM ordering.order_completion_hold h WHERE h.order_id = o.id AND h.released_at IS NULL)
 ORDER BY o.id LIMIT 1 \gset
SELECT u.id AS some_user FROM identity.app_user u ORDER BY u.id LIMIT 1 \gset

-- ---------------------------------------------------------------- LM-1: the hold and the refund branch
-- A whole carrier return: every shipment brought back, its units closed (set without their triggers: what is checked
-- is the edge into the refund branch, not how the order got there).
SET LOCAL session_replication_role = replica;
UPDATE ordering.order_shipment SET status = 'RETURNED', failure_reason = 'REFUSED',
       units_closed_at = coalesce(outcome_at, now())
 WHERE order_id = :'ord';
UPDATE ordering.customer_order SET status = 'RETURNED', status_before_return = NULL WHERE id = :'ord';
SET LOCAL session_replication_role = origin;
INSERT INTO ordering.order_completion_hold (id, order_id, reason, note, held_at, held_by, created_at)
VALUES ('00000000-0000-4000-8000-0000000095a1', :'ord', 'DELIVERY_DISPUTED', NULL, now(), :'some_user', now());

SELECT pg_temp.expect_fail_with('LM-1: a held RETURNED order does not become PARTIALLY_REFUNDED',
    format('UPDATE ordering.customer_order SET status = ''PARTIALLY_REFUNDED'', refunded_at = now() WHERE id = %L',
           :'ord'), 'tg_order_completion_hold_respected');
SELECT pg_temp.expect_fail_with('LM-1: nor REFUNDED - an entry into the refund branch all the same',
    format('UPDATE ordering.customer_order SET status = ''REFUNDED'', refunded_at = now() WHERE id = %L', :'ord'),
    'tg_order_completion_hold_respected');

-- A claim opened from the refund branch goes back where it was: no new entry, the hold does not refuse it.
SET LOCAL session_replication_role = replica;
UPDATE ordering.customer_order SET status_before_return = 'REFUNDED', refunded_at = now() WHERE id = :'ord';
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_not_refused_by('LM-1: a claim from the refund branch going back to REFUNDED is not the hold''s',
    format('UPDATE ordering.customer_order SET status = ''REFUNDED'', status_before_return = NULL WHERE id = %L',
           :'ord'), 'tg_order_completion_hold_respected');

UPDATE ordering.order_completion_hold SET released_at = now(), released_by = :'some_user', release_note = 'checked'
 WHERE id = '00000000-0000-4000-8000-0000000095a1';
SET LOCAL session_replication_role = replica;
UPDATE ordering.customer_order SET status_before_return = NULL WHERE id = :'ord';
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_not_refused_by('LM-1: released, the order may enter the refund branch',
    format('UPDATE ordering.customer_order SET status = ''PARTIALLY_REFUNDED'', refunded_at = now() WHERE id = %L',
           :'ord'), 'tg_order_completion_hold_respected');

-- ---------------------------------------------------------------- LM-3: what a closure carries onto its fee item
-- The rows' own CHECKs: written without triggers (and so without foreign keys), the arithmetic is what is refused.
SET LOCAL session_replication_role = replica;
SELECT pg_temp.expect_fail_with('LM-3: only a closure of the rest carries onto a fee item',
    format($q$INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number,
                  contract_number, kind, source_id, currency, collected_amount, closed_amount, reduced_amount,
                  surplus_amount, fee_amount, fee_covered_amount, refundable_amount, fee_owed_amount,
                  fee_installment_id, settled_at, carried_to_fee_amount)
              VALUES ('00000000-0000-4000-8000-0000000095b1', %L, '00000000-0000-4000-8000-0000000095b9', %L, %L,
                      'HD-QA-95', 'ORDER_CANCELLED', %L, %L, 100, 0, 0, 100, 0, 0, 100, 0,
                      '00000000-0000-4000-8000-0000000095c1', now(), 10)$q$,
           :'ord', :'ord_customer', :'ord_number', :'ord', :'ord_currency'), 'ck_order_settlement_carried');
SELECT pg_temp.expect_fail_with('LM-3: never more than the closure took off',
    format($q$INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number,
                  contract_number, kind, source_id, currency, collected_amount, closed_amount, reduced_amount,
                  surplus_amount, fee_amount, fee_covered_amount, refundable_amount, fee_owed_amount,
                  fee_installment_id, settled_at, carried_to_fee_amount)
              VALUES ('00000000-0000-4000-8000-0000000095b2', %L, '00000000-0000-4000-8000-0000000095b9', %L, %L,
                      'HD-QA-95', 'REMAINDER_CLOSED', '00000000-0000-4000-8000-0000000095d2', %L, 200, 100, 5, 105, 0,
                      0, 105, 0, '00000000-0000-4000-8000-0000000095c1', now(), 10)$q$,
           :'ord', :'ord_customer', :'ord_number', :'ord_currency'), 'ck_order_settlement_carried');
SELECT pg_temp.expect_fail_with('LM-3: what is carried is owed on a fee item',
    format($q$INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number,
                  contract_number, kind, source_id, currency, collected_amount, closed_amount, reduced_amount,
                  surplus_amount, fee_amount, fee_covered_amount, refundable_amount, fee_owed_amount,
                  fee_installment_id, settled_at, carried_to_fee_amount)
              VALUES ('00000000-0000-4000-8000-0000000095b3', %L, '00000000-0000-4000-8000-0000000095b9', %L, %L,
                      'HD-QA-95', 'REMAINDER_CLOSED', '00000000-0000-4000-8000-0000000095d3', %L, 0, 100, 110, 0, 0,
                      0, 0, 0, NULL, now(), 10)$q$,
           :'ord', :'ord_customer', :'ord_number', :'ord_currency'), 'ck_order_settlement_fee_split');
SELECT pg_temp.expect_fail_with('LM-3: what is carried is no part of the surplus',
    format($q$INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number,
                  contract_number, kind, source_id, currency, collected_amount, closed_amount, reduced_amount,
                  surplus_amount, fee_amount, fee_covered_amount, refundable_amount, fee_owed_amount,
                  fee_installment_id, settled_at, carried_to_fee_amount)
              VALUES ('00000000-0000-4000-8000-0000000095b4', %L, '00000000-0000-4000-8000-0000000095b9', %L, %L,
                      'HD-QA-95', 'REMAINDER_CLOSED', '00000000-0000-4000-8000-0000000095d4', %L, 100, 100, 110, 10,
                      0, 0, 10, 0, '00000000-0000-4000-8000-0000000095c1', now(), 10)$q$,
           :'ord', :'ord_customer', :'ord_number', :'ord_currency'), 'ck_order_settlement_surplus');
SELECT pg_temp.expect_ok('LM-3: 100 of goods closed, 10 carried: 110 taken off, nothing surplus, a fee item',
    format($q$INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number,
                  contract_number, kind, source_id, currency, collected_amount, closed_amount, reduced_amount,
                  surplus_amount, fee_amount, fee_covered_amount, refundable_amount, fee_owed_amount,
                  fee_installment_id, settled_at, carried_to_fee_amount)
              VALUES ('00000000-0000-4000-8000-0000000095b5', %L, '00000000-0000-4000-8000-0000000095b9', %L, %L,
                      'HD-QA-95', 'REMAINDER_CLOSED', '00000000-0000-4000-8000-0000000095d5', %L, 0, 100, 110, 0, 0,
                      0, 0, 0, '00000000-0000-4000-8000-0000000095c1', now(), 10)$q$,
           :'ord', :'ord_customer', :'ord_number', :'ord_currency'));
SET LOCAL session_replication_role = origin;

ROLLBACK;
