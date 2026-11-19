-- =============================================================================
-- WF-05 GH-3: a delivery corrected to a failure takes back the milestone of the installments paid after it
-- (kltn-docs 09 §4.3 step 5, 15 §4.1; open question E17: "mốc giao hàng của đợt AFTER_DELIVERY tính lại theo kết quả
-- mới").
--
-- Why. The milestone of an installment came once and never changed (payment.check_installment_transition refused any
-- change of reached_at / due_date). So an installment made due by a delivery the carrier reported wrongly stayed due
-- from that day after the warehouse manager corrected it to a failure: thirty days later it was overdue, the customer
-- was refused releases for other orders and a new contract was held for credit - for goods nobody had received - and
-- when the goods were delivered again a week later, nothing moved the due day either.
--
-- What this does: payment.check_installment_transition (the body of V20261121000100 otherwise) lets an installment
-- AFTER_DELIVERY that is not paid in full
--   * lose its milestone - reached_at and due_date both NULL again, DUE back to NOT_DUE, PARTIALLY_PAID kept (what was
--     paid stays paid; ck_installment_status_facts and ck_installment_due_date hold as they are);
--   * have its milestone moved to the day the goods were delivered again, when that delivery was recorded before the
--     correction was heard (InstallmentService.deliveryCorrected).
-- Nothing else about a milestone changes: ON_SIGNING and BEFORE_DELIVERY keep theirs, and so does an installment paid
-- in full, which owes nothing for its due day to decide. No row is touched here.
-- =============================================================================

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
    -- WF-05 GH-3 (09 §4.3 step 5, E17): the milestone of an installment paid after delivery, not paid in full, follows
    -- a delivery corrected to a failure - taken back, or moved to the day of the delivery made again.
    IF OLD.reached_at IS NOT NULL AND (NEW.reached_at IS DISTINCT FROM OLD.reached_at
                                       OR NEW.due_date IS DISTINCT FROM OLD.due_date)
       AND NOT (OLD.milestone = 'AFTER_DELIVERY' AND OLD.status NOT IN ('PAID', 'CANCELLED')) THEN
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
        -- Back to NOT_DUE when its delivery was corrected to a failure (WF-05 GH-3): the milestone is taken back.
        OR (OLD.status = 'DUE' AND NEW.status = 'NOT_DUE' AND OLD.milestone = 'AFTER_DELIVERY'
            AND NEW.reached_at IS NULL)
        -- Back to DUE / NOT_DUE only by a receipt voided (15 §4.4 step 12).
        OR (OLD.status = 'PARTIALLY_PAID' AND NEW.status IN ('PAID', 'CANCELLED', 'DUE', 'NOT_DUE'))
        OR (OLD.status = 'PAID' AND NEW.status IN ('PARTIALLY_PAID', 'DUE', 'NOT_DUE'))) THEN
        RAISE EXCEPTION 'installment % of contract %: % -> % is not a transition (15 §5.1)', OLD.seq,
            OLD.contract_number, OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
