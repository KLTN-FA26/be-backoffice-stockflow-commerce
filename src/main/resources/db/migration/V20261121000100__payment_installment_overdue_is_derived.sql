-- =============================================================================
-- WF-05 fix plan, section 8: "overdue" is a flag worked out from the due day, no longer a status of an installment
-- (kltn-docs 15 §4.1 step 4, §4.3 step 9, §5.1).
--
-- Why. OVERDUE was a status the nightly sweep set and only a payment in full lifted, so it hid the money: an overdue
-- installment paid in part stayed OVERDUE and never read PARTIALLY_PAID (WF-05 audit, F-05). It was also only as true
-- as the last run of the job - an installment past its due day was not overdue until 00:15, and the credit check
-- (15 BR-11) read the stale status.
--
-- What this does:
--   1. moves every OVERDUE installment to what its money says: PARTIALLY_PAID when something was paid, else DUE (an
--      overdue installment has a due day, so its milestone came). The status then says the money and the milestone
--      only: NOT_DUE, DUE, PARTIALLY_PAID, PAID, CANCELLED;
--   2. states it in the table: ck_installment_status and ck_installment_status_facts no longer know OVERDUE, and
--      payment.check_installment_transition has no edge into or out of it;
--   3. adds payment.installment_is_overdue(due_date, status, on_day): past its due day and still owing - the one
--      definition the application repeats (Installment.overdue, InstallmentBook.overdue). The aged-receivables reading
--      filters by it; nothing stores it.
--
-- The nightly job (ReceivableOverdueJob) is gone with the status it kept. Nothing is archived (QD-1).
-- =============================================================================

-- 1. The rows -----------------------------------------------------------------------------------------------------------
-- OVERDUE -> DUE / PARTIALLY_PAID was never an edge (OVERDUE left only by PAID or CANCELLED), so the transition trigger
-- stands aside for this one statement; the facts CHECK still holds for every row it writes.
ALTER TABLE payment.installment DISABLE TRIGGER tg_installment_transition;

UPDATE payment.installment
   SET status = CASE WHEN paid_amount > 0 THEN 'PARTIALLY_PAID' ELSE 'DUE' END,
       version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE status = 'OVERDUE';

ALTER TABLE payment.installment ENABLE TRIGGER tg_installment_transition;

-- 2. The rules ----------------------------------------------------------------------------------------------------------
ALTER TABLE payment.installment DROP CONSTRAINT ck_installment_status;
ALTER TABLE payment.installment DROP CONSTRAINT ck_installment_status_facts;

ALTER TABLE payment.installment
    ADD CONSTRAINT ck_installment_status CHECK (status IN ('NOT_DUE', 'DUE', 'PARTIALLY_PAID', 'PAID', 'CANCELLED')),
    -- The status says what the money and the milestone say (15 §5.1), what a closure took off counting as settled:
    -- paid in part and the rest taken off, it is PAID; taken off whole with nothing paid, it is no longer owed -
    -- CANCELLED. Whether it is overdue is the due day's to say (payment.installment_is_overdue), not the status's.
    ADD CONSTRAINT ck_installment_status_facts CHECK (
        ((status = 'NOT_DUE' AND reached_at IS NULL AND paid_amount = 0 AND reduced_amount < amount)
      OR (status = 'DUE' AND reached_at IS NOT NULL AND paid_amount = 0 AND reduced_amount < amount)
      OR (status = 'PARTIALLY_PAID' AND paid_amount > 0 AND paid_amount + reduced_amount < amount)
      OR (status = 'PAID' AND paid_amount > 0 AND paid_amount + reduced_amount = amount)
      OR (status = 'CANCELLED' AND paid_amount < amount))
        AND (status = 'PAID') = (paid_at IS NOT NULL)
        AND (status = 'CANCELLED') = (cancelled_at IS NOT NULL));

COMMENT ON COLUMN payment.installment.status IS
    'NOT_DUE, DUE, PARTIALLY_PAID, PAID or CANCELLED: what the money and the milestone say (15 §5.1). Overdue is not a '
    'status - see payment.installment_is_overdue.';
COMMENT ON COLUMN payment.installment.due_date IS
    'The milestone''s day plus due_days; null before the milestone and for an installment paid before delivery. Past it '
    'and still owing, the installment is overdue (payment.installment_is_overdue) - a flag, worked out when read.';

-- What it is never changes; its milestone comes once; CANCELLED is the end; what a closure took off is never given back
-- (15 §4.6 step 19). The 14 edges of 15 §5.1 without OVERDUE - the same body as V20261108003000 otherwise.
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
        OR (OLD.status = 'DUE' AND NEW.status IN ('PARTIALLY_PAID', 'PAID', 'CANCELLED'))
        -- Back to DUE / NOT_DUE only by a receipt voided (15 §4.4 step 12).
        OR (OLD.status = 'PARTIALLY_PAID' AND NEW.status IN ('PAID', 'CANCELLED', 'DUE', 'NOT_DUE'))
        OR (OLD.status = 'PAID' AND NEW.status IN ('PARTIALLY_PAID', 'DUE', 'NOT_DUE'))) THEN
        RAISE EXCEPTION 'installment % of contract %: % -> % is not a transition (15 §5.1)', OLD.seq,
            OLD.contract_number, OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- 3. The flag -----------------------------------------------------------------------------------------------------------
-- Past its due day and still owing. An installment paid before delivery has no due day and is never overdue; one paid
-- in full or cancelled owes nothing. on_day is the business day asked about (Asia/Ho_Chi_Minh), today by default.
CREATE FUNCTION payment.installment_is_overdue(due_date DATE, status TEXT,
                                               on_day DATE DEFAULT (NOW() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
    RETURNS BOOLEAN
    LANGUAGE sql STABLE AS
$$
    SELECT due_date IS NOT NULL AND due_date < on_day AND status NOT IN ('PAID', 'CANCELLED');
$$;
COMMENT ON FUNCTION payment.installment_is_overdue(DATE, TEXT, DATE) IS
    'WF-05 fix plan, section 8 (15 §4.1 step 4): whether an installment is overdue on on_day - past its due day and '
    'still owing. Worked out, never stored; Installment.overdue and InstallmentBook.overdue say the same.';
