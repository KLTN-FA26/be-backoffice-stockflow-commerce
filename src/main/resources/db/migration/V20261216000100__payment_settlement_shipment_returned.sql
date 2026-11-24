-- WF-05 BE-03 (owner's decision Q-2, with Q-7, Q-12 and Q-19): the units of a shipment the carrier brought back - once
-- the warehouse confirmed its return receipt - or lost - once Sales closed it with the carrier's confirmation - are
-- settled as a customer's return is: the shipment's own delivery batch first, then the older debt, then refunded up to
-- what was collected, the batches still to come last; no fee. Before this they were settled as a closure of the rest
-- (REMAINDER_CLOSED), which takes the latest batch first: batch 1 brought back lowered batches 2 and 3, their
-- installment before delivery - the gate of their handover - included.
--
-- A new kind, SHIPMENT_RETURNED, naming its shipment. Stated again here what OrderSettlement states:
--   * the kind names a shipment of the settlement's order, and only that kind names one (ck_order_settlement_shipment,
--     fk_order_settlement_shipment, check_order_settlement);
--   * that shipment is RETURNED, or DELIVERY_FAILED for LOST (Q-12 keeps the lost shipment DELIVERY_FAILED with its
--     reason): the goods are not with the customer;
--   * a shipment's money settles once (uk_order_settlement_shipment);
--   * the arithmetic of a return (ck_order_settlement_surplus): no fee, surplus = closed - reduced, at most collected;
--   * the shipping fee waived with it (Q-19) is part of closed, never above it, zero for every other kind;
--   * its reductions are accepted like a return's (check_installment_reduction).
-- Settlements already written stay as they are (immutable money history): no backfill.

ALTER TABLE payment.order_settlement ADD COLUMN shipment_id UUID;
ALTER TABLE payment.order_settlement ADD COLUMN shipping_fee_waived_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

ALTER TABLE payment.order_settlement ADD CONSTRAINT fk_order_settlement_shipment
    FOREIGN KEY (shipment_id) REFERENCES ordering.order_shipment (id);

ALTER TABLE payment.order_settlement DROP CONSTRAINT ck_order_settlement_kind;
ALTER TABLE payment.order_settlement ADD CONSTRAINT ck_order_settlement_kind
    CHECK (kind IN ('ORDER_CANCELLED', 'REMAINDER_CLOSED', 'GOODS_RETURNED', 'SHIPMENT_RETURNED'));

ALTER TABLE payment.order_settlement ADD CONSTRAINT ck_order_settlement_shipment CHECK (
    (kind = 'SHIPMENT_RETURNED') = (shipment_id IS NOT NULL)
    AND shipping_fee_waived_amount >= 0
    AND shipping_fee_waived_amount = trunc(shipping_fee_waived_amount)
    AND shipping_fee_waived_amount <= closed_amount
    AND (kind = 'SHIPMENT_RETURNED' OR shipping_fee_waived_amount = 0));

ALTER TABLE payment.order_settlement DROP CONSTRAINT ck_order_settlement_surplus;
ALTER TABLE payment.order_settlement ADD CONSTRAINT ck_order_settlement_surplus CHECK (
    (kind = 'ORDER_CANCELLED' AND source_id = order_id AND closed_amount = 0 AND reduced_amount = 0
        AND surplus_amount = collected_amount)
    OR (kind = 'REMAINDER_CLOSED' AND closed_amount > 0 AND reduced_amount <= closed_amount
        AND surplus_amount = closed_amount - reduced_amount AND surplus_amount <= collected_amount
        AND fee_percent IS NULL)
    OR (kind IN ('GOODS_RETURNED', 'SHIPMENT_RETURNED') AND closed_amount > 0 AND reduced_amount <= closed_amount
        AND surplus_amount = closed_amount - reduced_amount AND surplus_amount <= collected_amount
        AND fee_amount = 0 AND fee_percent IS NULL AND fee_reason IS NULL));

CREATE UNIQUE INDEX uk_order_settlement_shipment ON payment.order_settlement (shipment_id)
    WHERE kind = 'SHIPMENT_RETURNED';

COMMENT ON COLUMN payment.order_settlement.shipment_id IS
    'SHIPMENT_RETURNED: the shipment whose units the carrier brought back or lost (WF-05 Q-2); null for every other kind.';
COMMENT ON COLUMN payment.order_settlement.shipping_fee_waived_amount IS
    'SHIPMENT_RETURNED: the part of closed_amount that is the order''s shipping fee, waived when the order ends with '
    'nothing delivered (WF-05 Q-19); 0 otherwise.';
COMMENT ON COLUMN payment.order_settlement.closed_amount IS
    'REMAINDER_CLOSED: what the goods closed were worth with VAT; GOODS_RETURNED: what the goods refunded on a return '
    'were worth with VAT (team plan 4D-3); SHIPMENT_RETURNED: what the shipment''s units brought back or lost were worth '
    'with VAT, plus the shipping fee waived with them (WF-05 Q-2, Q-19); 0 for a cancellation.';

-- As V20261121000300, plus: a shipment's settlement names a shipment of the order whose goods are not with the customer.
CREATE OR REPLACE FUNCTION payment.check_order_settlement()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    o         RECORD;
    collected NUMERIC;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'settlement % of order %: a settlement is kept as it was made', OLD.kind, OLD.order_number
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT co.order_number, co.customer_id, co.currency, co.total_amount, co.closed_amount, co.printing_started_at,
           c.contract_number, c.status AS contract_status, c.order_id AS contract_order_id
      INTO o
      FROM ordering.customer_order co
      JOIN ordering.sales_contract c ON c.id = NEW.contract_id
     WHERE co.id = NEW.order_id;
    IF NOT FOUND OR o.contract_order_id <> NEW.order_id OR o.contract_status NOT IN ('SIGNED', 'COMPLETED') THEN
        RAISE EXCEPTION 'settlement of order %: contract % is not its signed one', NEW.order_number, NEW.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.customer_id IS DISTINCT FROM o.customer_id OR NEW.order_number IS DISTINCT FROM o.order_number
        OR NEW.contract_number IS DISTINCT FROM o.contract_number OR NEW.currency IS DISTINCT FROM o.currency THEN
        RAISE EXCEPTION 'settlement of order %: it is that order''s, of its customer, in its currency', o.order_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.fee_amount > 0 AND o.printing_started_at IS NULL AND NOT (NEW.kind = 'REMAINDER_CLOSED' AND EXISTS (
            SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = NEW.order_id AND s.status = 'RETURNED')) THEN
        RAISE EXCEPTION 'settlement of order %: no fee before mass printing started (17 BR-11)', o.order_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.kind = 'ORDER_CANCELLED' AND NEW.fee_amount > o.total_amount - o.closed_amount THEN
        RAISE EXCEPTION 'settlement of order %: a fee of % is above what the order is worth (17 §4.8 step 30)',
            o.order_number, NEW.fee_amount USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.kind = 'REMAINDER_CLOSED' AND NEW.fee_amount > NEW.closed_amount THEN
        RAISE EXCEPTION 'settlement of order %: a fee of % is above the goods closed (17 §4.8 step 33)',
            o.order_number, NEW.fee_amount USING ERRCODE = 'check_violation';
    END IF;
    -- 4D-3: a return's refund settles the return of this order whose refund was proposed at this value.
    IF NEW.kind = 'GOODS_RETURNED' AND NOT EXISTS (
            SELECT 1 FROM ordering.return_request r
             WHERE r.id = NEW.source_id AND r.order_id = NEW.order_id
               AND r.status IN ('SETTLEMENT_PENDING', 'RESOLVED') AND r.settlement_amount = NEW.closed_amount) THEN
        RAISE EXCEPTION 'settlement of order %: goods returned are settled for a return of the order whose refund is '
            'proposed at %', o.order_number, NEW.closed_amount USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 Q-2, Q-12: a shipment's units brought back by the carrier (RETURNED), or lost (DELIVERY_FAILED for LOST) -
    -- never a shipment the customer has, nor one still on its way.
    IF NEW.kind = 'SHIPMENT_RETURNED' AND NOT EXISTS (
            SELECT 1 FROM ordering.order_shipment s
             WHERE s.id = NEW.shipment_id AND s.order_id = NEW.order_id
               AND (s.status = 'RETURNED' OR (s.status = 'DELIVERY_FAILED' AND s.failure_reason = 'LOST'))) THEN
        RAISE EXCEPTION 'settlement of order %: a shipment is settled as brought back for a shipment of the order the '
            'carrier brought back or lost', o.order_number USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(SUM(i.paid_amount), 0) INTO collected
      FROM payment.installment i
     WHERE i.order_id = NEW.order_id AND i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE');
    IF collected <> NEW.collected_amount THEN
        RAISE EXCEPTION 'settlement of order %: % collected on record, its installments were paid % (15 BR-14)',
            o.order_number, NEW.collected_amount, collected USING ERRCODE = 'check_violation';
    END IF;
    -- 15 BR-14: what the order's settlements make refundable, this one with them, never exceeds what was collected.
    IF NEW.refundable_amount + (SELECT COALESCE(SUM(s.refundable_amount), 0) FROM payment.order_settlement s
                                 WHERE s.order_id = NEW.order_id) > collected THEN
        RAISE EXCEPTION 'settlement of order %: more to refund than the % collected (15 BR-14)', o.order_number,
            collected USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$function$;

-- As V20261121000300, plus a shipment brought back or lost (WF-05 Q-2): it takes off its own order's installments,
-- never a fee item.
CREATE OR REPLACE FUNCTION payment.check_installment_reduction() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    settlement  RECORD;
    installment RECORD;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'installment reduction %: kept as written', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    SELECT s.kind, s.order_id, s.order_number INTO settlement FROM payment.order_settlement s
     WHERE s.id = NEW.settlement_id;
    SELECT i.order_id, i.milestone, i.seq INTO installment FROM payment.installment i WHERE i.id = NEW.installment_id;
    -- A closure of the rest, goods returned and refunded (4D-3), or a shipment brought back or lost (WF-05 Q-2) takes
    -- off its own order's installments.
    IF settlement.kind NOT IN ('REMAINDER_CLOSED', 'GOODS_RETURNED', 'SHIPMENT_RETURNED')
        OR installment.order_id <> settlement.order_id
        OR installment.milestone IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE') THEN
        RAISE EXCEPTION 'order %: a closure takes off its own order''s installments, never a fee item (15 §4.6 step 19)',
            settlement.order_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
