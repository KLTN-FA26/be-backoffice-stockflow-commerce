-- WF-05 BE-44 (audit R-11): a return's invoice is adjusted when units were refunded, not when money was. The e-invoice
-- is per delivery and independent of installments: goods taken back on an order with no installments (refunded worth
-- 0, WF-05 TH-2) were still invoiced, and vat_adjustment_required = "settlement_amount > 0" left that invoice carrying
-- them. A refusal (Q-4) refunds no unit and adjusts nothing, as before.
--
--  1. ck_return_request_vat read only the return's own row, so it could state the money rule; the units are on the
--     lines. It keeps what one row can say (the flag only on a RESOLVED return), and check_return_request_refund, which
--     reads the lines already, says the rest: vat_adjustment_required = some unit refunded.
--     Returns resolved before with units refunded worth nothing are set to true (last, below); no adjustment is
--     drafted for them retroactively (nothing publishes their ReturnResolved again) - the query in the PR lists them
--     for the accountant.
--  2. check_return_request_refund, also (WF-05 Q-4, BE-12): a proposal that withholds every unit approved and back
--     names a refusal reason.

ALTER TABLE ordering.return_request DROP CONSTRAINT ck_return_request_vat;

ALTER TABLE ordering.return_request
    ADD CONSTRAINT ck_return_request_vat CHECK (NOT vat_adjustment_required OR status = 'RESOLVED');

CREATE OR REPLACE FUNCTION ordering.check_return_request_refund() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    r              RECORD;
    lines          NUMERIC;
    refunded_units BIGINT;
    refundable     BIGINT;
    return_id      UUID;
BEGIN
    -- One function for both tables: each branch names only the columns its own table has.
    IF TG_TABLE_NAME = 'return_request_line' THEN
        return_id := NEW.return_request_id;
    ELSE
        return_id := NEW.id;
    END IF;
    SELECT id, rma_number, status, settlement_amount, vat_adjustment_required, refusal_reason INTO r
      FROM ordering.return_request WHERE id = return_id;
    IF NOT FOUND OR r.status NOT IN ('SETTLEMENT_PENDING', 'RESOLVED') THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(SUM(refund_amount), 0), COALESCE(SUM(refund_quantity), 0),
           COALESCE(SUM(LEAST(COALESCE(approved_quantity, 0), COALESCE(received_quantity, 0))), 0)
      INTO lines, refunded_units, refundable
      FROM ordering.return_request_line WHERE return_request_id = r.id;
    IF lines <> r.settlement_amount THEN
        RAISE EXCEPTION 'return %: its lines refund % but it settles %', r.rma_number, lines, r.settlement_amount
            USING ERRCODE = 'check_violation';
    END IF;
    -- BE-44: the invoice follows the goods refunded, whatever they were worth in money.
    IF r.status = 'RESOLVED' AND r.vat_adjustment_required <> (refunded_units > 0) THEN
        RAISE EXCEPTION 'return %: an invoice adjustment is required exactly when units were refunded (% refunded)',
            r.rma_number, refunded_units USING ERRCODE = 'check_violation';
    END IF;
    -- Q-4: withholding everything that came back is a decision with a reason. Checked on the proposal, which is what
    -- the second person approves; a return closed with nothing usable come back (refundable 0) needs none.
    IF r.status = 'SETTLEMENT_PENDING' AND refunded_units = 0 AND refundable > 0 AND r.refusal_reason IS NULL THEN
        RAISE EXCEPTION 'return %: % unit(s) approved and back are all withheld without a refusal reason',
            r.rma_number, refundable USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

DROP TRIGGER tg_return_request_refund ON ordering.return_request;
CREATE CONSTRAINT TRIGGER tg_return_request_refund
    AFTER INSERT OR UPDATE OF status, settlement_amount, vat_adjustment_required, refusal_reason
    ON ordering.return_request DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.status IN ('SETTLEMENT_PENDING', 'RESOLVED'))
    EXECUTE FUNCTION ordering.check_return_request_refund();

-- Last: the update queues tg_return_request_refund (deferred to commit), and no ALTER TABLE or DROP TRIGGER on the
-- table may follow a statement with pending trigger events. The new trigger checks these rows at commit.
UPDATE ordering.return_request r
   SET vat_adjustment_required = TRUE
 WHERE r.status = 'RESOLVED' AND NOT r.vat_adjustment_required
   AND EXISTS (SELECT 1 FROM ordering.return_request_line x
                WHERE x.return_request_id = r.id AND x.refund_quantity > 0);
