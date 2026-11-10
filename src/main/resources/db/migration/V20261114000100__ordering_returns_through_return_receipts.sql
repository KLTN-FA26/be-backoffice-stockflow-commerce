-- =============================================================================
-- Returns through return receipts, refunded against the installments (team plan 4D-3; kltn-docs 17 §4.10 steps
-- 37-41, BR-18; 03 §4.2, BR-14; 15 §4.5 steps 15-17, BR-05, BR-14).
--
-- A return's goods come back on a return receipt (V20261112000300, 4D-2): its confirmation is what makes the return
-- RECEIVED, with the units of each line's SKU among the cartons it took. There is no QC step, no free replacement order
-- and no credit kept for the customer: Sales propose a refund of the units that came back, a second person approves
-- it, and payment settles it against the order's installments - what the goods were worth with VAT comes off what the
-- installments still owe, the rest was paid and is to be refunded. Each rule names the code that states it first.
--
--   1. ordering.return_request.goods_receipt_id: the return receipt that brought the goods, once per return
--      (uk_return_request_goods_receipt). A RECEIVED or SETTLEMENT_PENDING return has one, and a return with one says
--      who confirmed it, when and into which area (ck_return_request_goods_back). The receipt is a CLOSED RETURN
--      receipt against this return (check_return_request_goods_receipt). ReturnRequest.goodsReceived.
--   2. The rules of the QC era that the new flow cannot keep are dropped: ck_return_request_received (counted
--      parcels), ck_return_request_inspected (RESOLVED needed an inspection), ck_return_request_credit_transfer (a set
--      off needed a ledger credit entry; the installments carry it now). The goods always come back
--      (ck_return_request_goods_required). NOT VALID where rows of the QC era may break them: V20261114000200 cleans
--      those rows up and validates.
--   3. return_request_line.refund_quantity / refund_amount: the units refunded, at most approved and received, worth
--      refund_amount with VAT (ck_return_request_line_refund); a refund proposed or settled adds up to the return's
--      settlement_amount at commit (check_return_request_refund). ReturnRequest.proposeRefund.
--   4. check_return_request_line counts a resolved line's claim on its order line by the units refunded.
--   5. A return keeps photos of its claim only; the goods are photographed on their receipt
--      (ck_return_request_evidence_claim_only, NOT VALID: earlier photos stay). ReturnRequest.requireEvidenceAllowed.
--   6. payment.order_settlement kind GOODS_RETURNED: one per return (uk_order_settlement on kind, source), source the
--      return request of the order with the same value, no fee, surplus = what could not come off the installments
--      (ck_order_settlement_surplus, check_order_settlement); its reductions are installment_reduction rows
--      (check_installment_reduction). OrderSettlement.returned.
--
-- Nothing is archived (QD-1): no row changes here.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. The return receipt that brought the goods
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.return_request
    ADD COLUMN goods_receipt_id UUID,
    ADD CONSTRAINT fk_return_request_goods_receipt FOREIGN KEY (goods_receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uk_return_request_goods_receipt ON ordering.return_request (goods_receipt_id)
    WHERE goods_receipt_id IS NOT NULL;
COMMENT ON COLUMN ordering.return_request.goods_receipt_id IS
    'The RETURN goods receipt whose confirmation brought the goods back (team plan 4D-3, kltn-docs 03 §4.2); set once.';

ALTER TABLE ordering.return_request ADD CONSTRAINT ck_return_request_goods_back CHECK (
    (goods_receipt_id IS NULL OR (received_at IS NOT NULL AND received_by IS NOT NULL
                                  AND receiving_location_code IS NOT NULL))
    AND (status NOT IN ('RECEIVED', 'SETTLEMENT_PENDING') OR goods_receipt_id IS NOT NULL)) NOT VALID;

CREATE FUNCTION ordering.check_return_request_goods_receipt() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.goods_receipt_id IS NOT NULL
        AND NEW.goods_receipt_id IS DISTINCT FROM OLD.goods_receipt_id THEN
        RAISE EXCEPTION 'return %: the receipt that brought its goods does not change', OLD.rma_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.goods_receipt_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM procurement.goods_receipts g
             WHERE g.id = NEW.goods_receipt_id AND g.receipt_type = 'RETURN' AND g.status = 'CLOSED'
               AND g.return_request_id = NEW.id) THEN
        RAISE EXCEPTION 'return %: its goods come back on a confirmed return receipt against it (03 §4.2)',
            NEW.rma_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_return_request_goods_receipt
    BEFORE INSERT OR UPDATE OF goods_receipt_id ON ordering.return_request
    FOR EACH ROW EXECUTE FUNCTION ordering.check_return_request_goods_receipt();


-- ---------------------------------------------------------------------------------------------
-- 2. Rules of the QC era
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.return_request
    DROP CONSTRAINT IF EXISTS ck_return_request_received,
    DROP CONSTRAINT IF EXISTS ck_return_request_inspected,
    DROP CONSTRAINT IF EXISTS ck_return_request_credit_transfer;

-- 17 BR-18: an approved return's goods always come back.
ALTER TABLE ordering.return_request ADD CONSTRAINT ck_return_request_goods_required CHECK (
    status NOT IN ('AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING', 'RESOLVED', 'EXPIRED')
    OR return_required IS TRUE) NOT VALID;


-- ---------------------------------------------------------------------------------------------
-- 3. The units refunded
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.return_request_line
    ADD COLUMN refund_quantity INTEGER       NOT NULL DEFAULT 0,
    ADD COLUMN refund_amount   NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_return_request_line_refund CHECK (
        refund_quantity >= 0 AND refund_amount >= 0
        AND refund_quantity <= COALESCE(approved_quantity, 0)
        AND refund_quantity <= COALESCE(received_quantity, 0)
        AND (refund_quantity > 0 OR refund_amount = 0));
COMMENT ON COLUMN ordering.return_request_line.refund_quantity IS
    'Units refunded (team plan 4D-3): at most approved and at most received on the return receipt.';
COMMENT ON COLUMN ordering.return_request_line.refund_amount IS
    'What the units refunded are worth with their VAT, as the order line charged them (kltn-docs 15 §4.5).';

CREATE FUNCTION ordering.check_return_request_refund() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    r         RECORD;
    lines     NUMERIC;
    return_id UUID;
BEGIN
    -- One function for both tables: each branch names only the columns its own table has.
    IF TG_TABLE_NAME = 'return_request_line' THEN
        return_id := NEW.return_request_id;
    ELSE
        return_id := NEW.id;
    END IF;
    SELECT id, rma_number, status, settlement_amount INTO r FROM ordering.return_request WHERE id = return_id;
    IF NOT FOUND OR r.status NOT IN ('SETTLEMENT_PENDING', 'RESOLVED') THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(SUM(refund_amount), 0) INTO lines FROM ordering.return_request_line WHERE return_request_id = r.id;
    IF lines <> r.settlement_amount THEN
        RAISE EXCEPTION 'return %: its lines refund % but it settles %', r.rma_number, lines, r.settlement_amount
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_return_request_refund
    AFTER INSERT OR UPDATE OF status, settlement_amount ON ordering.return_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.status IN ('SETTLEMENT_PENDING', 'RESOLVED'))
    EXECUTE FUNCTION ordering.check_return_request_refund();

CREATE CONSTRAINT TRIGGER tg_return_request_line_refund
    AFTER INSERT OR UPDATE OF refund_quantity, refund_amount ON ordering.return_request_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_return_request_refund();


-- ---------------------------------------------------------------------------------------------
-- 4. What a resolved line claims of its order line
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ordering.check_return_request_line() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    deliverable    INTEGER;
    claimed        INTEGER;
    open_elsewhere BOOLEAN;
BEGIN
    SELECT l.quantity - l.cancelled_quantity INTO deliverable
      FROM ordering.order_line l
      JOIN ordering.return_request r ON r.order_id = l.order_id
     WHERE l.id = NEW.order_line_id AND r.id = NEW.return_request_id
       FOR UPDATE OF l;
    IF deliverable IS NULL THEN
        RAISE EXCEPTION 'return line must be a line of the returned order' USING ERRCODE = 'check_violation';
    END IF;
    -- ReturnLine.claimedUnits: asked for, approved while open, refunded once resolved (accepted for the QC era's).
    SELECT COALESCE(SUM(CASE
                            WHEN r.status = 'REQUESTED' THEN x.quantity
                            WHEN r.status IN ('AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING')
                                THEN x.approved_quantity
                            WHEN r.status = 'INSPECTED' THEN x.accepted_quantity
                            WHEN r.status = 'RESOLVED' THEN COALESCE(x.accepted_quantity, x.refund_quantity)
                            ELSE 0 END), 0),
           COALESCE(bool_or(r.status IN ('REQUESTED', 'AWAITING_RETURN', 'RECEIVED', 'INSPECTED',
                                         'SETTLEMENT_PENDING')), FALSE)
      INTO claimed, open_elsewhere
      FROM ordering.return_request_line x
      JOIN ordering.return_request r ON r.id = x.return_request_id
     WHERE x.order_line_id = NEW.order_line_id AND x.return_request_id <> NEW.return_request_id;
    IF TG_OP = 'INSERT' AND open_elsewhere THEN
        RAISE EXCEPTION 'order line % already has an open return', NEW.order_line_id
            USING ERRCODE = 'check_violation';
    END IF;
    -- BR-RMA-002: goods printed to the customer's design come back for a named defect only (ReturnReason.isNamedFault).
    IF TG_OP = 'INSERT' AND NEW.custom_printed AND EXISTS (SELECT 1 FROM ordering.return_request r
                                                           WHERE r.id = NEW.return_request_id
                                                             AND r.reason_code IN ('CHANGED_MIND', 'OTHER')) THEN
        RAISE EXCEPTION 'printed goods come back for a named defect only (BR-RMA-002)' USING ERRCODE = 'check_violation';
    END IF;
    IF claimed + COALESCE(NEW.accepted_quantity, NEW.approved_quantity, NEW.quantity) > deliverable THEN
        RAISE EXCEPTION 'would claim % of the % unit(s) delivered',
            claimed + COALESCE(NEW.accepted_quantity, NEW.approved_quantity, NEW.quantity), deliverable
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 5. Photos of the claim only
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.return_request_evidence
    ADD CONSTRAINT ck_return_request_evidence_claim_only CHECK (stage = 'CLAIM') NOT VALID;


-- ---------------------------------------------------------------------------------------------
-- 6. payment.order_settlement: GOODS_RETURNED
-- ---------------------------------------------------------------------------------------------
ALTER TABLE payment.order_settlement
    DROP CONSTRAINT ck_order_settlement_kind,
    ADD CONSTRAINT ck_order_settlement_kind CHECK (kind IN ('ORDER_CANCELLED', 'REMAINDER_CLOSED', 'GOODS_RETURNED')),
    DROP CONSTRAINT ck_order_settlement_surplus,
    ADD CONSTRAINT ck_order_settlement_surplus CHECK (
        (kind = 'ORDER_CANCELLED' AND source_id = order_id AND closed_amount = 0 AND reduced_amount = 0
            AND surplus_amount = collected_amount)
        OR (kind = 'REMAINDER_CLOSED' AND closed_amount > 0 AND reduced_amount <= closed_amount
            AND surplus_amount = closed_amount - reduced_amount AND surplus_amount <= collected_amount
            AND fee_percent IS NULL)
        OR (kind = 'GOODS_RETURNED' AND closed_amount > 0 AND reduced_amount <= closed_amount
            AND surplus_amount = closed_amount - reduced_amount AND surplus_amount <= collected_amount
            AND fee_amount = 0 AND fee_percent IS NULL AND fee_reason IS NULL));
COMMENT ON COLUMN payment.order_settlement.closed_amount IS
    'REMAINDER_CLOSED: what the goods closed were worth with VAT; GOODS_RETURNED: what the goods refunded on a return '
    'were worth with VAT (team plan 4D-3); 0 for a cancellation.';

CREATE OR REPLACE FUNCTION payment.check_order_settlement() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
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
    IF NEW.fee_amount > 0 AND o.printing_started_at IS NULL THEN
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
    SELECT COALESCE(SUM(i.paid_amount), 0) INTO collected
      FROM payment.installment i
     WHERE i.order_id = NEW.order_id AND i.milestone <> 'CANCELLATION_FEE';
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
$$;

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
    -- A closure of the rest, or goods returned and refunded (4D-3), takes off its own order's installments.
    IF settlement.kind NOT IN ('REMAINDER_CLOSED', 'GOODS_RETURNED') OR installment.order_id <> settlement.order_id
        OR installment.milestone = 'CANCELLATION_FEE' THEN
        RAISE EXCEPTION 'order %: a closure takes off its own order''s installments, never a fee item (15 §4.6 step 19)',
            settlement.order_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
