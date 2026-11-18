-- =============================================================================
-- DS-02b of the WF-02 review can be walked on the demo seed (V20261203000100): the customer whose contract it is owes
-- an instalment past its due day - so the contract is warned about when drawn up and sent, and its order held for
-- credit when signed (15 BR-10, BR-11) - while the demo customer of DS-02a owes nothing overdue, or DS-02a would be held
-- too. The contract waits as a DRAFT whose schedule and plan add up, paying after delivery within the credit approved.
-- Ages are counted from the Vietnam business day. Needs the demo seed. Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

CREATE TEMP TABLE qa99b AS
SELECT o.id AS order_id, o.customer_id, o.status AS order_status, o.total_amount, c.id AS contract_id,
       c.status AS contract_status
  FROM ordering.customer_order o JOIN ordering.sales_contract c ON c.order_id = o.id
 WHERE o.order_number = 'SO-20261202-900002';

\echo '--- the customer of DS-02b owes something overdue; the customer of DS-02a does not'
SELECT pg_temp.expect_true('DS1 the DS-02b order and its contract are both DRAFT, of Trà Sữa Mây',
    (SELECT count(*) FROM qa99b q JOIN customer.customer c ON c.id = q.customer_id
      WHERE q.order_status = 'DRAFT' AND q.contract_status = 'DRAFT' AND c.tax_code = '0399990012') = 1);
SELECT pg_temp.expect_true('DS2 that customer has an instalment overdue today (payment.installment_is_overdue)',
    EXISTS (SELECT 1 FROM payment.installment i JOIN qa99b q ON q.customer_id = i.customer_id
             WHERE payment.installment_is_overdue(i.due_date, i.status)
               AND i.amount - i.paid_amount - i.reduced_amount > 0));
SELECT pg_temp.expect_true('DS3 the demo customer of DS-02a has nothing overdue',
    NOT EXISTS (SELECT 1 FROM payment.installment i
                 WHERE i.customer_id = 'c0000000-0000-4000-8000-000000000001'
                   AND payment.installment_is_overdue(i.due_date, i.status)));

\echo '--- the DRAFT contract is one the application would send'
SELECT pg_temp.expect_true('DS4 its instalments add up to the order total with tax',
    (SELECT sum(i.amount) FROM ordering.contract_payment_installment i JOIN qa99b q ON q.contract_id = i.contract_id)
    = (SELECT total_amount FROM qa99b));
SELECT pg_temp.expect_true('DS5 its batches carry every carton of the order line, in windows not in the past',
    (SELECT sum(l.cartons) FROM ordering.delivery_batch_line l JOIN ordering.delivery_batch b ON b.id = l.delivery_batch_id
       JOIN qa99b q ON q.contract_id = b.contract_id)
    = (SELECT sum(ol.cartons) FROM ordering.order_line ol JOIN qa99b q ON q.order_id = ol.order_id)
    AND NOT EXISTS (SELECT 1 FROM ordering.delivery_batch b JOIN qa99b q ON q.contract_id = b.contract_id
                     WHERE b.window_start < (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date));
SELECT pg_temp.expect_true('DS6 it pays after delivery, within the credit accounting approved for the customer',
    EXISTS (SELECT 1 FROM ordering.contract_payment_installment i JOIN qa99b q ON q.contract_id = i.contract_id
             WHERE i.milestone = 'AFTER_DELIVERY')
    AND (SELECT p.credit_limit FROM customer.credit_profile p JOIN qa99b q ON q.customer_id = p.customer_id) > 0);

ROLLBACK;
