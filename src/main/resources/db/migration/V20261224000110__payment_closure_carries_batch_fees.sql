-- =============================================================================
-- WF-05 audit round 3, LM-3: a closure of the rest carries what the installments before delivery of the batches it
-- closed still owe onto its fee item (15 §4.6 steps 18-19, §5.1; D30, D32).
--
-- Why. Installments tied to delivery batches share the whole total by batch value, the plate and shipping fee lines
-- included, so a batch's installment before delivery is above its goods. A closure of the rest takes the goods of the
-- batches it closed off their installments first (TT-5) and leaves their share of the fee lines, which stay owed - on
-- an installment due when its batch is released and gating its handover, neither of which ever comes. It stayed
-- NOT_DUE for good with no due day (ck_installment_due_date), never overdue, outside the credit exposure and the aged
-- receivables (BR-10 counts after delivery and fee items), and the order, owing, never completed.
--
-- What. The closure takes all that such an installment still owes off it (an ordinary installment_reduction: PAID if it
-- was paid something, else CANCELLED) and its fee item owes it instead, beside the agreed fee it did not cover - due
-- from the closure's day, counted by the credit check, the receivables and the overdue scan as every fee item is. The
-- customer owes the same money; only where and from when changes (OrderSettlement.closeRemainder). Not carried: a batch
-- whose labelled cartons the closure keeps (its installment still gates their handover), installments tied to no batch
-- or to a batch that left, and installments after delivery (they fall due with the whole order).
--
-- Stated again here:
--   * carried_to_fee_amount: a closure of the rest's only, whole, within what it took off the installments
--     (ck_order_settlement_carried);
--   * the closure's arithmetic leaves it out: surplus = closed - (reduced - carried) (ck_order_settlement_surplus);
--   * the fee item owes fee_owed + carried, and exists exactly when that is above zero (ck_order_settlement_fee_split,
--     payment.assert_order_settlement);
--   * what is carried came off installments before delivery, tied to a batch none of whose shipments was handed over
--     or delivered, that the closure left owing nothing (payment.assert_order_settlement);
--   * a receipt recorded before such a closure on such an installment, which the closure left owing nothing, is not
--     voided: the installment would owe again with no milestone, no due day and no gate
--     (payment.check_payment_receipt_void; PaymentReceiptService.closedBatchSettled). One the closure left owing - a
--     batch whose labelled cartons it kept - still gates their handover, and voids there stay Q-20's.
--
-- Additive: settlements already written carry 0 and satisfy every rule as before. No backfill: an installment stranded
-- before this deploy keeps what it owes (the VPS database is rebuilt before the defence, decision Q-13).
-- =============================================================================

ALTER TABLE payment.order_settlement ADD COLUMN carried_to_fee_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

ALTER TABLE payment.order_settlement ADD CONSTRAINT ck_order_settlement_carried CHECK (
    carried_to_fee_amount >= 0
    AND carried_to_fee_amount = trunc(carried_to_fee_amount)
    AND carried_to_fee_amount <= reduced_amount
    AND (kind = 'REMAINDER_CLOSED' OR carried_to_fee_amount = 0));

ALTER TABLE payment.order_settlement DROP CONSTRAINT ck_order_settlement_surplus;
ALTER TABLE payment.order_settlement ADD CONSTRAINT ck_order_settlement_surplus CHECK (
    (kind = 'ORDER_CANCELLED' AND source_id = order_id AND closed_amount = 0 AND reduced_amount = 0
        AND surplus_amount = collected_amount)
    OR (kind = 'REMAINDER_CLOSED' AND closed_amount > 0 AND reduced_amount - carried_to_fee_amount <= closed_amount
        AND surplus_amount = closed_amount - (reduced_amount - carried_to_fee_amount)
        AND surplus_amount <= collected_amount AND fee_percent IS NULL)
    OR (kind IN ('GOODS_RETURNED', 'SHIPMENT_RETURNED') AND closed_amount > 0 AND reduced_amount <= closed_amount
        AND surplus_amount = closed_amount - reduced_amount AND surplus_amount <= collected_amount
        AND fee_amount = 0 AND fee_percent IS NULL AND fee_reason IS NULL));

ALTER TABLE payment.order_settlement DROP CONSTRAINT ck_order_settlement_fee_split;
ALTER TABLE payment.order_settlement ADD CONSTRAINT ck_order_settlement_fee_split CHECK (
    fee_covered_amount = LEAST(fee_amount, surplus_amount)
    AND refundable_amount = surplus_amount - fee_covered_amount
    AND fee_owed_amount = fee_amount - fee_covered_amount
    AND (fee_owed_amount + carried_to_fee_amount > 0) = (fee_installment_id IS NOT NULL));

COMMENT ON COLUMN payment.order_settlement.carried_to_fee_amount IS
    'REMAINDER_CLOSED: what the installments before delivery of the batches the closure closed whole still owed above '
    'the goods closed - their share of the fee lines - taken off them and owed on the closure''s fee item instead '
    '(audit r3 LM-3). Part of reduced_amount, never of what the goods closed took off; 0 for every other kind.';

-- As the baseline, plus: the fee item owes fee_owed + carried, and what is carried came off installments before delivery
-- of batches that never left, left owing nothing.
CREATE OR REPLACE FUNCTION payment.assert_order_settlement(settlement uuid) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    s       RECORD;
    taken   NUMERIC;
    fee     RECORD;
    status  VARCHAR;
    settled NUMERIC;
BEGIN
    SELECT * INTO s FROM payment.order_settlement WHERE id = settlement;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT COALESCE(SUM(r.amount), 0) INTO taken FROM payment.installment_reduction r WHERE r.settlement_id = settlement;
    IF taken <> s.reduced_amount THEN
        RAISE EXCEPTION 'settlement of order %: % taken off on record, its reductions take %', s.order_number,
            s.reduced_amount, taken USING ERRCODE = 'check_violation';
    END IF;
    IF s.fee_installment_id IS NOT NULL THEN
        SELECT i.order_id, i.milestone, i.amount INTO fee FROM payment.installment i WHERE i.id = s.fee_installment_id;
        IF fee.order_id <> s.order_id OR fee.milestone <> 'CANCELLATION_FEE'
            OR fee.amount <> s.fee_owed_amount + s.carried_to_fee_amount THEN
            RAISE EXCEPTION 'settlement of order %: its fee item owes the % the money did not cover and the % carried '
                'onto it (15 §4.6 steps 18-19)', s.order_number, s.fee_owed_amount, s.carried_to_fee_amount
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF s.carried_to_fee_amount > 0 THEN
        SELECT COALESCE(SUM(r.amount), 0) INTO settled
          FROM payment.installment_reduction r
          JOIN payment.installment i ON i.id = r.installment_id
         WHERE r.settlement_id = settlement
           AND i.milestone = 'BEFORE_DELIVERY' AND i.delivery_batch_id IS NOT NULL
           AND i.status IN ('PAID', 'CANCELLED')
           AND NOT EXISTS (SELECT 1 FROM ordering.order_shipment sh
                            WHERE sh.order_id = i.order_id AND sh.delivery_batch_id = i.delivery_batch_id
                              AND sh.status IN ('HANDED_OVER', 'DELIVERED'));
        IF s.carried_to_fee_amount > settled THEN
            RAISE EXCEPTION 'settlement of order %: % carried onto the fee item, but the installments before delivery of '
                'the batches it closed that it settled were taken off only % (audit r3 LM-3)', s.order_number,
                s.carried_to_fee_amount, settled USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF s.kind = 'ORDER_CANCELLED' THEN
        SELECT o.status INTO status FROM ordering.customer_order o WHERE o.id = s.order_id;
        IF status <> 'CANCELLED' THEN
            RAISE EXCEPTION 'order % is %: only a cancelled order is settled as cancelled', s.order_number, status
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
END;
$$;

-- As V20261121000300, plus: a receipt recorded before a closure of the rest settled an installment before delivery of a
-- batch that never left - a batch the closure closed - is not voided (PaymentReceiptService.closedBatchSettled).
CREATE OR REPLACE FUNCTION payment.check_payment_receipt_void()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    settled VARCHAR;
BEGIN
    IF NEW.status = 'VOIDED' AND OLD.status = 'RECORDED' THEN
        SELECT s.order_number INTO settled
          FROM payment.payment_receipt_line l
          JOIN payment.installment i ON i.id = l.installment_id
          JOIN payment.order_settlement s ON s.order_id = i.order_id
         WHERE l.receipt_id = NEW.id AND i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE')
           AND OLD.recorded_at <= s.settled_at
           AND (s.kind = 'ORDER_CANCELLED' OR s.surplus_amount > 0)
         LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'payment receipt %: order % was settled on what it paid (15 §4.4 step 13)', OLD.reference,
                settled USING ERRCODE = 'check_violation';
        END IF;
        SELECT s.order_number INTO settled
          FROM payment.payment_receipt_line l
          JOIN payment.installment i ON i.id = l.installment_id
          JOIN payment.installment_reduction r ON r.installment_id = i.id
          JOIN payment.order_settlement s ON s.id = r.settlement_id
         WHERE l.receipt_id = NEW.id AND i.milestone = 'BEFORE_DELIVERY' AND i.delivery_batch_id IS NOT NULL
           AND i.status IN ('PAID', 'CANCELLED')
           AND s.kind = 'REMAINDER_CLOSED' AND OLD.recorded_at <= s.settled_at
           AND NOT EXISTS (SELECT 1 FROM ordering.order_shipment sh
                            WHERE sh.order_id = i.order_id AND sh.delivery_batch_id = i.delivery_batch_id
                              AND sh.status IN ('HANDED_OVER', 'DELIVERED'))
         LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'payment receipt %: it paid an installment before delivery of a batch the closure of the rest '
                'of order % closed; voided, it would owe again with no milestone and no due day (audit r3 LM-3)',
                OLD.reference, settled USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;
