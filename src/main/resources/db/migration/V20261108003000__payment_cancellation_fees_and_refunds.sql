-- 4B-9 (kltn-docs 15 §4.5, §4.6, BR-14..BR-16; 17 §4.8 steps 30 and 33, BR-11..BR-14; open questions D30, D32): what a
-- cancelled order, or the rest of one closed, does with the money of its contract's installments.
--
--   * order_settlement: one row per cancellation and per closure of the rest. A cancellation's money collected on the
--     installments pays the cancellation fee first; the surplus is the amount to refund, a shortfall becomes the
--     cancellation fee item. A closure lowers what the installments still owe by the value of the goods closed (with
--     their VAT, never the mould / print or shipping fee lines - D30, D32), the tied and latest installments first; what
--     the customer paid above the order's new worth pays the closure's fee first, the rest is refunded (BR-16).
--   * The cancellation fee item is a row of payment.installment with milestone CANCELLATION_FEE (15 §5.1: "dùng cùng
--     vòng đời, bắt đầu từ Due"): a receipt pays it like any installment, the nightly sweep makes it OVERDUE, the credit
--     check counts it (BR-10). It names no plan row, so contract_installment_id becomes nullable for it alone.
--   * installment.reduced_amount and installment_reduction: what a closure took off an installment, row by row.
--   * refund_record: the accountant records a refund made outside the system (amount, day, statement reference); voided
--     with a reason when wrong. The records of an order never exceed what its settlements made refundable (BR-14).
--
-- The money ledger (customer_transfer, refund, receivable) is left as it is until 4B-10 / 4B-11. It holds no money of a
-- contract order (OrderAllocator refuses one), and the order no longer announces a contract order's fee or refund to
-- it, so nothing is counted twice.
--
-- Data: no settlement is back-filled. A contract order cancelled between 4B-6 and this migration keeps its CANCELLED
-- installments and the money paid on them; its refund, if any, is the accountant's to settle by hand (the agreed fee, if
-- any, is on the order, and a legacy receivable carries the part not covered). Every existing installment keeps
-- satisfying the widened checks: reduced_amount starts at 0, and each rewritten CHECK reduces to the old one then.

-- The installments: a cancellation fee item, and what a closure took off ----------------------------------------------
ALTER TABLE payment.installment ALTER COLUMN contract_installment_id DROP NOT NULL;
ALTER TABLE payment.installment ADD COLUMN reduced_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

ALTER TABLE payment.installment DROP CONSTRAINT ck_installment_seq;
ALTER TABLE payment.installment DROP CONSTRAINT ck_installment_milestone;
ALTER TABLE payment.installment DROP CONSTRAINT ck_installment_amounts;
ALTER TABLE payment.installment DROP CONSTRAINT ck_installment_status_facts;

ALTER TABLE payment.installment
    ADD CONSTRAINT ck_installment_milestone CHECK (milestone IN ('ON_SIGNING', 'BEFORE_DELIVERY', 'AFTER_DELIVERY',
                                                                'CANCELLATION_FEE')),
    -- A plan row is 1-20 of its contract; a fee item comes after them, 21-40 (uk_installment_seq keeps one per number).
    ADD CONSTRAINT ck_installment_seq CHECK ((milestone = 'CANCELLATION_FEE' AND seq BETWEEN 21 AND 40)
        OR (milestone <> 'CANCELLATION_FEE' AND seq BETWEEN 1 AND 20)),
    -- Whole dong; never more paid and taken off than owed (15 BR-05, BR-16).
    ADD CONSTRAINT ck_installment_amounts CHECK (amount > 0 AND amount = TRUNC(amount)
        AND paid_amount >= 0 AND paid_amount = TRUNC(paid_amount)
        AND reduced_amount >= 0 AND reduced_amount = TRUNC(reduced_amount)
        AND paid_amount + reduced_amount <= amount),
    -- 15 §4.6 step 18: a fee item is no plan row; it is due from the day it is opened (§5.1 "bắt đầu từ Due"), tied to
    -- no delivery batch, never lowered by a closure, and never cancelled - its order is already cancelled or closed.
    ADD CONSTRAINT ck_installment_fee_item CHECK ((milestone = 'CANCELLATION_FEE') = (contract_installment_id IS NULL)
        AND (milestone <> 'CANCELLATION_FEE' OR (due_days = 0 AND reached_at IS NOT NULL AND delivery_batch_id IS NULL
                                                 AND reduced_amount = 0 AND status <> 'CANCELLED'))),
    -- The status says what the money and the milestone say (15 §5.1), what a closure took off counting as settled:
    -- paid in part and the rest taken off, it is PAID; taken off whole with nothing paid, it is no longer owed -
    -- CANCELLED.
    ADD CONSTRAINT ck_installment_status_facts CHECK (
        ((status = 'NOT_DUE' AND reached_at IS NULL AND paid_amount = 0 AND reduced_amount < amount)
      OR (status = 'DUE' AND reached_at IS NOT NULL AND paid_amount = 0 AND reduced_amount < amount)
      OR (status = 'PARTIALLY_PAID' AND paid_amount > 0 AND paid_amount + reduced_amount < amount)
      OR (status = 'OVERDUE' AND due_date IS NOT NULL AND paid_amount + reduced_amount < amount)
      OR (status = 'PAID' AND paid_amount > 0 AND paid_amount + reduced_amount = amount)
      OR (status = 'CANCELLED' AND paid_amount < amount))
        AND (status = 'PAID') = (paid_at IS NOT NULL)
        AND (status = 'CANCELLED') = (cancelled_at IS NOT NULL));

COMMENT ON COLUMN payment.installment.reduced_amount IS
    'What closures of the rest took off it (installment_reduction; 15 §4.6 step 19): it owes amount - reduced - paid.';
COMMENT ON COLUMN payment.installment.contract_installment_id IS
    'The signed plan row it was made from; null only for a CANCELLATION_FEE item (15 §4.6 step 18), which has none.';

-- An installment is the contract's plan row it names, of a signed contract, for that contract's order and customer. A
-- fee item names no plan row: it is of the signed contract of its order, in the order's currency.
CREATE OR REPLACE FUNCTION payment.check_installment_matches_plan() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    plan     RECORD;
    contract RECORD;
BEGIN
    IF NEW.contract_installment_id IS NULL THEN
        SELECT c.contract_number, c.status AS contract_status, c.order_id, o.customer_id, o.order_number, o.currency
          INTO contract
          FROM ordering.sales_contract c
          JOIN ordering.customer_order o ON o.id = c.order_id
         WHERE c.id = NEW.contract_id;
        IF NOT FOUND OR contract.contract_status NOT IN ('SIGNED', 'COMPLETED') THEN
            RAISE EXCEPTION 'cancellation fee item %: contract % is not a signed one (15 §4.6)', NEW.id, NEW.contract_id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.order_id IS DISTINCT FROM contract.order_id OR NEW.customer_id IS DISTINCT FROM contract.customer_id
            OR NEW.contract_number IS DISTINCT FROM contract.contract_number
            OR NEW.order_number IS DISTINCT FROM contract.order_number OR NEW.currency IS DISTINCT FROM contract.currency
        THEN
            RAISE EXCEPTION 'cancellation fee item of contract %: it is that contract''s order''s, in its currency',
                contract.contract_number USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    SELECT p.contract_id, p.seq, p.name, p.milestone, p.amount, p.due_days, p.delivery_batch_id, b.seq AS batch_seq,
           c.contract_number, c.status AS contract_status, c.order_id, o.customer_id, o.order_number, o.currency
      INTO plan
      FROM ordering.contract_payment_installment p
      JOIN ordering.sales_contract c ON c.id = p.contract_id
      JOIN ordering.customer_order o ON o.id = c.order_id
      LEFT JOIN ordering.delivery_batch b ON b.id = p.delivery_batch_id
     WHERE p.id = NEW.contract_installment_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'installment %: no plan row %', NEW.id, NEW.contract_installment_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF plan.contract_status NOT IN ('SIGNED', 'COMPLETED') THEN
        RAISE EXCEPTION 'installment of contract %: the contract is %, installments come with its signature (15 §4.1)',
            plan.contract_number, plan.contract_status USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.contract_id IS DISTINCT FROM plan.contract_id OR NEW.order_id IS DISTINCT FROM plan.order_id
        OR NEW.customer_id IS DISTINCT FROM plan.customer_id OR NEW.contract_number IS DISTINCT FROM plan.contract_number
        OR NEW.order_number IS DISTINCT FROM plan.order_number OR NEW.currency IS DISTINCT FROM plan.currency
        OR NEW.seq IS DISTINCT FROM plan.seq OR NEW.name IS DISTINCT FROM plan.name
        OR NEW.milestone IS DISTINCT FROM plan.milestone OR NEW.amount IS DISTINCT FROM plan.amount
        OR NEW.due_days IS DISTINCT FROM plan.due_days OR NEW.delivery_batch_id IS DISTINCT FROM plan.delivery_batch_id
        OR NEW.delivery_batch_seq IS DISTINCT FROM plan.batch_seq THEN
        RAISE EXCEPTION 'installment % of contract %: it is the signed plan''s row, as signed (14 BR-14)', NEW.seq,
            plan.contract_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- What it is never changes; its milestone comes once; CANCELLED is the end; OVERDUE stays until paid in full (15 §5.1);
-- what a closure took off is never given back (15 §4.6 step 19).
CREATE OR REPLACE FUNCTION payment.check_installment_transition() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'installment % of contract %: installments are kept', OLD.seq, OLD.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.contract_id IS DISTINCT FROM OLD.contract_id
        OR NEW.contract_installment_id IS DISTINCT FROM OLD.contract_installment_id
        OR NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
        OR NEW.contract_number IS DISTINCT FROM OLD.contract_number
        OR NEW.order_number IS DISTINCT FROM OLD.order_number OR NEW.seq IS DISTINCT FROM OLD.seq
        OR NEW.name IS DISTINCT FROM OLD.name OR NEW.milestone IS DISTINCT FROM OLD.milestone
        OR NEW.delivery_batch_id IS DISTINCT FROM OLD.delivery_batch_id
        OR NEW.delivery_batch_seq IS DISTINCT FROM OLD.delivery_batch_seq OR NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.due_days IS DISTINCT FROM OLD.due_days THEN
        RAISE EXCEPTION 'installment % of contract %: what it is does not change (14 BR-14)', OLD.seq,
            OLD.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.reduced_amount < OLD.reduced_amount THEN
        RAISE EXCEPTION 'installment % of contract %: what a closure took off is not given back (15 §4.6 step 19)',
            OLD.seq, OLD.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.reached_at IS NOT NULL AND (NEW.reached_at IS DISTINCT FROM OLD.reached_at
                                       OR NEW.due_date IS DISTINCT FROM OLD.due_date) THEN
        RAISE EXCEPTION 'installment % of contract %: its milestone came on %; that does not change', OLD.seq,
            OLD.contract_number, OLD.reached_at USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'CANCELLED' THEN
        RAISE EXCEPTION 'installment % of contract %: a CANCELLED installment does not change', OLD.seq,
            OLD.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'NOT_DUE' AND NEW.status IN ('DUE', 'PARTIALLY_PAID', 'PAID', 'CANCELLED'))
        OR (OLD.status = 'DUE' AND NEW.status IN ('PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED'))
        -- Back to DUE / NOT_DUE only by a receipt voided (15 §4.4 step 12).
        OR (OLD.status = 'PARTIALLY_PAID' AND NEW.status IN ('PAID', 'OVERDUE', 'CANCELLED', 'DUE', 'NOT_DUE'))
        OR (OLD.status = 'OVERDUE' AND NEW.status IN ('PAID', 'CANCELLED'))
        OR (OLD.status = 'PAID' AND NEW.status IN ('PARTIALLY_PAID', 'DUE', 'NOT_DUE', 'OVERDUE'))) THEN
        RAISE EXCEPTION 'installment % of contract %: % -> % is not a transition (15 §5.1)', OLD.seq,
            OLD.contract_number, OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- The settlements --------------------------------------------------------------------------------------------------
CREATE TABLE payment.order_settlement
(
    id                 UUID           NOT NULL,
    order_id           UUID           NOT NULL,
    contract_id        UUID           NOT NULL,
    customer_id        UUID           NOT NULL,
    order_number       VARCHAR(32)    NOT NULL,
    contract_number    VARCHAR(40)    NOT NULL,
    -- ORDER_CANCELLED (17 §4.8 step 30) or REMAINDER_CLOSED (step 33).
    kind               VARCHAR(20)    NOT NULL,
    -- What it settles: the order for a cancellation, the closure for a rest closed. Once each (uk_order_settlement).
    source_id          UUID           NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    -- What the order's installments had been paid when it was settled (15 BR-14: nothing above it is refunded).
    collected_amount   NUMERIC(19, 4) NOT NULL,
    -- A closure: the goods closed with their VAT (D30, D32); what it took off the installments; the rest of it is money
    -- paid above the order's new worth.
    closed_amount      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    reduced_amount     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    -- The money that pays the fee first (BR-15): collected for a cancellation, closed - reduced for a closure.
    surplus_amount     NUMERIC(19, 4) NOT NULL,
    fee_amount         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    -- 17 §4.8 step 30: a share of the money collected, or an agreed amount with its reason and detail.
    fee_percent        NUMERIC(5, 2),
    fee_reason         VARCHAR(500),
    fee_detail         VARCHAR(2000),
    fee_covered_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    refundable_amount  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    fee_owed_amount    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    -- The cancellation fee item the shortfall became (15 §4.6 step 18).
    fee_installment_id UUID,
    -- Null when the system settled it (a stock hold run out cancels an unpaid order with no one to name).
    settled_by         UUID,
    settled_at         TIMESTAMPTZ    NOT NULL,

    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_order_settlement PRIMARY KEY (id),
    CONSTRAINT uk_order_settlement UNIQUE (kind, source_id),
    CONSTRAINT uk_order_settlement_fee_installment UNIQUE (fee_installment_id),
    CONSTRAINT fk_order_settlement_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id),
    CONSTRAINT fk_order_settlement_contract FOREIGN KEY (contract_id) REFERENCES ordering.sales_contract (id),
    CONSTRAINT fk_order_settlement_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id),
    CONSTRAINT fk_order_settlement_fee_installment FOREIGN KEY (fee_installment_id) REFERENCES payment.installment (id),
    CONSTRAINT fk_order_settlement_settled_by FOREIGN KEY (settled_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_order_settlement_kind CHECK (kind IN ('ORDER_CANCELLED', 'REMAINDER_CLOSED')),
    CONSTRAINT ck_order_settlement_amounts CHECK (
        collected_amount >= 0 AND collected_amount = TRUNC(collected_amount)
        AND closed_amount >= 0 AND closed_amount = TRUNC(closed_amount)
        AND reduced_amount >= 0 AND reduced_amount = TRUNC(reduced_amount)
        AND surplus_amount >= 0 AND fee_amount >= 0 AND fee_amount = TRUNC(fee_amount)),
    -- A cancellation: every paid dong is surplus. A closure: what it could not take off the installments was paid above
    -- the order's new worth - never more than was collected (15 BR-14), and never a share (17 §4.8 step 33).
    CONSTRAINT ck_order_settlement_surplus CHECK (
        (kind = 'ORDER_CANCELLED' AND source_id = order_id AND closed_amount = 0 AND reduced_amount = 0
            AND surplus_amount = collected_amount)
     OR (kind = 'REMAINDER_CLOSED' AND closed_amount > 0 AND reduced_amount <= closed_amount
            AND surplus_amount = closed_amount - reduced_amount AND surplus_amount <= collected_amount
            AND fee_percent IS NULL)),
    -- 15 BR-15: the surplus pays the fee first; the rest is refundable, a shortfall is owed on the fee item.
    CONSTRAINT ck_order_settlement_fee_split CHECK (
        fee_covered_amount = LEAST(fee_amount, surplus_amount)
        AND refundable_amount = surplus_amount - fee_covered_amount
        AND fee_owed_amount = fee_amount - fee_covered_amount
        AND (fee_owed_amount > 0) = (fee_installment_id IS NOT NULL)),
    -- 17 §4.8 step 30: a share of the money collected (rounded to the dong), or an agreed amount with its reason (500)
    -- and detail (2000) - not both; no fee, no terms.
    CONSTRAINT ck_order_settlement_fee_terms CHECK (
        (fee_reason IS NULL) = (fee_detail IS NULL)
        AND NOT (fee_percent IS NOT NULL AND fee_reason IS NOT NULL)
        AND (fee_reason IS NULL OR (btrim(fee_reason) <> '' AND btrim(fee_detail) <> '' AND fee_amount > 0))
        AND (fee_percent IS NULL OR (fee_percent > 0 AND fee_percent <= 100
                                     AND fee_amount = ROUND(collected_amount * fee_percent / 100)))
        AND (fee_amount = 0 OR fee_percent IS NOT NULL OR fee_reason IS NOT NULL))
);

-- 17 §4.8: an order is cancelled once.
CREATE UNIQUE INDEX uk_order_settlement_cancelled ON payment.order_settlement (order_id)
    WHERE kind = 'ORDER_CANCELLED';
CREATE INDEX ix_order_settlement_order ON payment.order_settlement (order_id, settled_at);
CREATE INDEX ix_order_settlement_customer ON payment.order_settlement (customer_id, settled_at DESC);

COMMENT ON TABLE payment.order_settlement IS
    'What a cancellation, or a closure of the rest, did with a contract order''s money (kltn-docs 15 §4.5, §4.6): the '
    'fee paid from the surplus first, the rest refundable, a shortfall owed on a CANCELLATION_FEE installment. Never '
    'changed.';

CREATE TABLE payment.installment_reduction
(
    id             UUID           NOT NULL,
    settlement_id  UUID           NOT NULL,
    installment_id UUID           NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_installment_reduction PRIMARY KEY (id),
    CONSTRAINT uk_installment_reduction UNIQUE (settlement_id, installment_id),
    CONSTRAINT fk_installment_reduction_settlement FOREIGN KEY (settlement_id)
        REFERENCES payment.order_settlement (id),
    CONSTRAINT fk_installment_reduction_installment FOREIGN KEY (installment_id) REFERENCES payment.installment (id),
    CONSTRAINT ck_installment_reduction_amount CHECK (amount > 0 AND amount = TRUNC(amount))
);

CREATE INDEX ix_installment_reduction_installment ON payment.installment_reduction (installment_id);

COMMENT ON TABLE payment.installment_reduction IS
    'What one closure of the rest took off one installment (15 §4.6 step 19): the tied and latest installments first.';

-- A settlement is of the order's signed contract, by its customer; a fee is kept only once mass printing started (17
-- BR-11), at most the order's worth for a cancellation (step 30) or the goods closed for a closure (step 33); and what
-- was collected is what the order's installments had been paid.
CREATE FUNCTION payment.check_order_settlement() RETURNS TRIGGER
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

CREATE TRIGGER tg_order_settlement
    BEFORE INSERT OR UPDATE OR DELETE ON payment.order_settlement
    FOR EACH ROW EXECUTE FUNCTION payment.check_order_settlement();

-- A reduction is written with its closure and stays: on an installment of that order, not a fee item.
CREATE FUNCTION payment.check_installment_reduction() RETURNS TRIGGER
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
    IF settlement.kind <> 'REMAINDER_CLOSED' OR installment.order_id <> settlement.order_id
        OR installment.milestone = 'CANCELLATION_FEE' THEN
        RAISE EXCEPTION 'order %: a closure takes off its own order''s installments, never a fee item (15 §4.6 step 19)',
            settlement.order_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_installment_reduction
    BEFORE INSERT OR UPDATE OR DELETE ON payment.installment_reduction
    FOR EACH ROW EXECUTE FUNCTION payment.check_installment_reduction();

-- At commit: an installment's reduced amount is what its reductions took off.
CREATE FUNCTION payment.assert_installment_reduced(installment UUID) RETURNS VOID
    LANGUAGE plpgsql AS
$$
DECLARE
    taken  NUMERIC;
    held   NUMERIC;
    seq    SMALLINT;
    number VARCHAR;
BEGIN
    SELECT i.reduced_amount, i.seq, i.contract_number INTO held, seq, number FROM payment.installment i
     WHERE i.id = installment;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT COALESCE(SUM(r.amount), 0) INTO taken FROM payment.installment_reduction r
     WHERE r.installment_id = installment;
    IF taken <> held THEN
        RAISE EXCEPTION 'installment % of contract %: % taken off on record, its reductions take % (15 §4.6 step 19)',
            seq, number, held, taken USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

-- At commit: a settlement is its reductions, its fee item owes its shortfall, and a cancellation's order is cancelled.
CREATE FUNCTION payment.assert_order_settlement(settlement UUID) RETURNS VOID
    LANGUAGE plpgsql AS
$$
DECLARE
    s      RECORD;
    taken  NUMERIC;
    fee    RECORD;
    status VARCHAR;
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
        IF fee.order_id <> s.order_id OR fee.milestone <> 'CANCELLATION_FEE' OR fee.amount <> s.fee_owed_amount THEN
            RAISE EXCEPTION 'settlement of order %: its fee item owes the % the money did not cover (15 §4.6 step 18)',
                s.order_number, s.fee_owed_amount USING ERRCODE = 'check_violation';
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

-- At commit: a fee item is the shortfall of exactly one settlement.
CREATE FUNCTION payment.assert_fee_item(installment UUID) RETURNS VOID
    LANGUAGE plpgsql AS
$$
DECLARE
    i RECORD;
BEGIN
    SELECT f.milestone, f.seq, f.contract_number INTO i FROM payment.installment f WHERE f.id = installment;
    IF NOT FOUND OR i.milestone <> 'CANCELLATION_FEE' THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM payment.order_settlement s WHERE s.fee_installment_id = installment) THEN
        RAISE EXCEPTION 'cancellation fee item % of contract %: no settlement owes it (15 §4.6 step 18)', i.seq,
            i.contract_number USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION payment.check_installment_settled() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM payment.assert_installment_reduced(NEW.id);
    IF TG_OP = 'INSERT' THEN
        PERFORM payment.assert_fee_item(NEW.id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION payment.check_order_settlement_totals() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM payment.assert_order_settlement(NEW.id);
    RETURN NULL;
END;
$$;

CREATE FUNCTION payment.check_installment_reduction_totals() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM payment.assert_installment_reduced(NEW.installment_id);
    PERFORM payment.assert_order_settlement(NEW.settlement_id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tg_installment_settled
    AFTER INSERT OR UPDATE OF reduced_amount ON payment.installment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payment.check_installment_settled();

CREATE CONSTRAINT TRIGGER tg_order_settlement_totals
    AFTER INSERT ON payment.order_settlement
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payment.check_order_settlement_totals();

CREATE CONSTRAINT TRIGGER tg_installment_reduction_totals
    AFTER INSERT ON payment.installment_reduction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payment.check_installment_reduction_totals();

-- A receipt of a settled order --------------------------------------------------------------------------------------
-- 15 §4.4 step 13: once a cancellation, or a closure that left money to refund, settled an order on what its
-- installments had been paid, a receipt that paid them by then is not voided - the refund was worked out from it. A
-- receipt recorded after the settlement, or paying a fee item, stays voidable: no settlement counted its money.
CREATE FUNCTION payment.check_payment_receipt_void() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    settled VARCHAR;
BEGIN
    IF NEW.status = 'VOIDED' AND OLD.status = 'RECORDED' THEN
        SELECT s.order_number INTO settled
          FROM payment.payment_receipt_line l
          JOIN payment.installment i ON i.id = l.installment_id
          JOIN payment.order_settlement s ON s.order_id = i.order_id
         WHERE l.receipt_id = NEW.id AND i.milestone <> 'CANCELLATION_FEE' AND OLD.recorded_at <= s.settled_at
           AND (s.kind = 'ORDER_CANCELLED' OR s.surplus_amount > 0)
         LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'payment receipt %: order % was settled on what it paid (15 §4.4 step 13)', OLD.reference,
                settled USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_payment_receipt_void
    BEFORE UPDATE OF status ON payment.payment_receipt
    FOR EACH ROW EXECUTE FUNCTION payment.check_payment_receipt_void();

-- The refunds recorded -----------------------------------------------------------------------------------------------
CREATE TABLE payment.refund_record
(
    id               UUID           NOT NULL,
    order_id         UUID           NOT NULL,
    customer_id      UUID           NOT NULL,
    order_number     VARCHAR(32)    NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    -- The day the money left, never after the day it was recorded (15 §3 "ngày chuyển").
    refunded_on      DATE           NOT NULL,
    -- The outgoing bank statement line (15 §4.5 step 16).
    reference        VARCHAR(100)   NOT NULL,
    note             VARCHAR(500),
    status           VARCHAR(16)    NOT NULL,
    recorded_by      UUID           NOT NULL,
    recorded_at      TIMESTAMPTZ    NOT NULL,
    voided_by        UUID,
    voided_at        TIMESTAMPTZ,
    void_reason      VARCHAR(500),

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_refund_record PRIMARY KEY (id),
    CONSTRAINT fk_refund_record_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id),
    CONSTRAINT fk_refund_record_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id),
    CONSTRAINT fk_refund_record_recorded_by FOREIGN KEY (recorded_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_refund_record_voided_by FOREIGN KEY (voided_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_refund_record_amount CHECK (amount > 0 AND amount = TRUNC(amount)),
    CONSTRAINT ck_refund_record_reference CHECK (reference = btrim(reference) AND reference <> ''),
    CONSTRAINT ck_refund_record_note CHECK (note IS NULL OR btrim(note) <> ''),
    CONSTRAINT ck_refund_record_refunded_on CHECK (
        refunded_on <= (recorded_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE),
    CONSTRAINT ck_refund_record_status CHECK (status IN ('RECORDED', 'VOIDED')),
    -- 15 §4.5 step 16: a record made wrong is voided with who, when and why, and kept.
    CONSTRAINT ck_refund_record_voided CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL)
        AND (voided_at IS NULL) = (voided_by IS NULL) AND (voided_at IS NULL) = (void_reason IS NULL)
        AND (void_reason IS NULL OR btrim(void_reason) <> '') AND (voided_at IS NULL OR voided_at >= recorded_at))
);

-- One outgoing statement line, one live record: the same money is not recorded refunded twice.
CREATE UNIQUE INDEX uk_refund_record_reference_recorded ON payment.refund_record (reference) WHERE status = 'RECORDED';
CREATE INDEX ix_refund_record_order ON payment.refund_record (order_id, recorded_at);
CREATE INDEX ix_refund_record_customer ON payment.refund_record (customer_id, refunded_on DESC);

COMMENT ON TABLE payment.refund_record IS
    'A refund the accountant made outside the system and recorded (kltn-docs 15 §4.5 step 16, BR-14): never edited; '
    'one recorded wrong is VOIDED with a reason and kept. The live records of an order never exceed what its '
    'settlements made refundable.';

-- A record is written for its order's customer, RECORDED; afterwards only its void moves, once.
CREATE FUNCTION payment.check_refund_record() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'refund record %: refund records are kept (15 §4.5)', OLD.reference
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT co.customer_id, co.order_number, co.currency INTO o FROM ordering.customer_order co
         WHERE co.id = NEW.order_id;
        IF NEW.status <> 'RECORDED' OR NEW.customer_id IS DISTINCT FROM o.customer_id
            OR NEW.order_number IS DISTINCT FROM o.order_number OR NEW.currency IS DISTINCT FROM o.currency THEN
            RAISE EXCEPTION 'refund record %: recorded for order %, its customer, in its currency', NEW.reference,
                o.order_number USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
        OR NEW.order_number IS DISTINCT FROM OLD.order_number OR NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.refunded_on IS DISTINCT FROM OLD.refunded_on
        OR NEW.reference IS DISTINCT FROM OLD.reference OR NEW.note IS DISTINCT FROM OLD.note
        OR NEW.recorded_by IS DISTINCT FROM OLD.recorded_by OR NEW.recorded_at IS DISTINCT FROM OLD.recorded_at THEN
        RAISE EXCEPTION 'refund record %: a record is not edited; void it and record the right one (15 §4.5)',
            OLD.reference USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'VOIDED' AND (NEW.status IS DISTINCT FROM OLD.status OR NEW.voided_by IS DISTINCT FROM OLD.voided_by
                                  OR NEW.voided_at IS DISTINCT FROM OLD.voided_at
                                  OR NEW.void_reason IS DISTINCT FROM OLD.void_reason) THEN
        RAISE EXCEPTION 'refund record %: a voided record stays as it was voided', OLD.reference
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_refund_record
    BEFORE INSERT OR UPDATE OR DELETE ON payment.refund_record
    FOR EACH ROW EXECUTE FUNCTION payment.check_refund_record();

-- At commit: what is recorded refunded for an order never exceeds what its settlements made refundable (15 BR-14).
CREATE FUNCTION payment.check_refund_record_total() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    refundable NUMERIC;
    refunded   NUMERIC;
BEGIN
    SELECT COALESCE(SUM(s.refundable_amount), 0) INTO refundable FROM payment.order_settlement s
     WHERE s.order_id = NEW.order_id;
    SELECT COALESCE(SUM(r.amount), 0) INTO refunded FROM payment.refund_record r
     WHERE r.order_id = NEW.order_id AND r.status = 'RECORDED';
    IF refunded > refundable THEN
        RAISE EXCEPTION 'order %: % recorded refunded, only % is to refund (15 BR-14)', NEW.order_number, refunded,
            refundable USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tg_refund_record_total
    AFTER INSERT OR UPDATE OF status ON payment.refund_record
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payment.check_refund_record_total();

-- Contract orders cancelled before this migration --------------------------------------------------------------------
-- Until now an OrderCancelled listener cancelled a contract order's unpaid installments after commit; from here the
-- cancellation settles them in its own transaction and the listener no longer does. An order cancelled just before the
-- upgrade whose event is still waiting in the publication registry would keep installments it no longer owes: they are
-- cancelled here, as the listener would have. Its refund, if any, is not worked out (no settlement is back-filled).
UPDATE payment.installment i
   SET status = 'CANCELLED', cancelled_at = NOW(), version = i.version + 1, last_modified_at = NOW(),
       last_modified_by = 'flyway'
  FROM ordering.customer_order o
 WHERE o.id = i.order_id AND o.status = 'CANCELLED' AND i.status NOT IN ('PAID', 'CANCELLED');

-- Permissions ------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'payment-refunds:' || a.action, 'payment-refunds', a.action, 0, NOW(), 'flyway'
  FROM unnest(ARRAY ['VIEW_PAGE', 'READ', 'CREATE', 'UPDATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, actions) AS (
    VALUES
        -- 15 §2, §4.5 step 16: the accountant refunds outside the system, records it, and voids a record made wrong.
        ('ACCOUNTANT',    'VIEW_PAGE,READ,CREATE,UPDATE'),
        -- Sales follows what a cancellation left to refund (17 §2) and answers the customer.
        ('SALES_STAFF',   'VIEW_PAGE,READ'),
        ('SALES_MANAGER', 'VIEW_PAGE,READ'),
        -- 17 §5.2: the customer sees their order's refund on the portal (scope OWN on the endpoint).
        ('CUSTOMER',      'READ'),
        ('SYSTEM_ADMIN',  'VIEW_PAGE,READ,CREATE,UPDATE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission
        ON permission.resource = 'payment-refunds'
       AND permission.action = ANY (string_to_array(wanted.actions, ','))
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
