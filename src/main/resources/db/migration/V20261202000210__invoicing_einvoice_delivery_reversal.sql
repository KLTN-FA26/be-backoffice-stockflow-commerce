-- =============================================================================
-- WF-05 HĐ-3: an issued invoice whose delivery is corrected to a failure is reduced in full by the system, and the
-- shipment delivered again gets a new original (kltn-docs 21 §4.4, §4.5 step 14, BR-01, the edge case "Kết quả giao
-- sửa từ thành công sang thất bại, hoá đơn đã Issued"; 09 §4.3 step 5).
--
-- Why. Until now the correction only told the accountants. The one correction they could draw up for it was ERROR,
-- which needs a written agreement signed by the buyer - who has not received the goods - so an invoice stayed in force
-- for goods nobody had. And when the goods were delivered again, the shipment still had "its" original (the old one,
-- adjusted or not), so nothing was drawn up for the goods really delivered: the delivery went uninvoiced and the order
-- never completed.
--
-- What this does:
--   1. correction_basis DELIVERY_FAILED: an adjustment that only reduces (ck_einvoice_decrease, tg_einvoice_line_sign),
--      drawn up by the system alone and never voided (tg_einvoice_guard). It needs no agreement; one may be attached as
--      evidence (ck_einvoice_agreement). Doc 21 asks for the full decrease and is silent on an agreement for it; the
--      agreement of TT 91 Điều 10 khoản 1 điểm b is for an invoice drawn up wrongly, which this one was not
--      (CorrectionBasis.DELIVERY_FAILED says more). Owner's question G12 stays open for the accountant.
--   2. einvoice.reversed_at: when an issued original stopped standing for its shipment because that reduction of it
--      was drawn up. Written once, outside the aggregate's version (EInvoiceRepositoryAdapter.markReversed), only on an
--      issued original of a shipment and only while a live DELIVERY_FAILED adjustment names it (ck_einvoice_reversed,
--      tg_einvoice_guard).
--   3. uk_einvoice_original_per_shipment and uk_einvoice_in_force_per_shipment leave a reversed original out, so the
--      shipment delivered again takes a second original (21 BR-01: one original in force per delivered shipment).
-- No row is touched: no invoice is reversed yet.
-- =============================================================================

ALTER TABLE invoicing.einvoice ADD COLUMN reversed_at TIMESTAMPTZ;

ALTER TABLE invoicing.einvoice
    -- Only an issued original of a shipment is reversed; it stays issued (an invoice being adjusted is never replaced,
    -- tg_einvoice_correction_form).
    ADD CONSTRAINT ck_einvoice_reversed CHECK (
        reversed_at IS NULL OR (type = 'ORIGINAL' AND shipment_id IS NOT NULL AND status = 'ISSUED'));

ALTER TABLE invoicing.einvoice DROP CONSTRAINT ck_einvoice_correction_basis;
ALTER TABLE invoicing.einvoice ADD CONSTRAINT ck_einvoice_correction_basis CHECK (
    (type = 'ORIGINAL' AND correction_basis IS NULL)
    OR (type = 'ADJUSTMENT' AND COALESCE(correction_basis, '') IN ('ERROR', 'RETURN', 'TRADE_DISCOUNT',
                                                                   'DELIVERY_FAILED'))
    OR (type = 'REPLACEMENT' AND COALESCE(correction_basis, '') = 'ERROR'));

-- The agreement is its file, its number and its date together. Required on the correction of an error
-- (ck_einvoice_agreement_before_sending, unchanged); accepted as evidence on the reduction of a failed delivery.
ALTER TABLE invoicing.einvoice DROP CONSTRAINT ck_einvoice_agreement;
ALTER TABLE invoicing.einvoice ADD CONSTRAINT ck_einvoice_agreement CHECK (
    (agreement_file_key IS NULL AND agreement_number IS NULL AND agreement_date IS NULL)
    OR (agreement_file_key IS NOT NULL AND COALESCE(btrim(agreement_number), '') <> ''
        AND agreement_date IS NOT NULL AND correction_basis IN ('ERROR', 'DELIVERY_FAILED')));

-- One original in force per shipment, and one issued invoice in force per shipment - a reversed original is neither.
DROP INDEX invoicing.uk_einvoice_original_per_shipment;
CREATE UNIQUE INDEX uk_einvoice_original_per_shipment ON invoicing.einvoice (shipment_id)
    WHERE type = 'ORIGINAL' AND status NOT IN ('VOIDED_DRAFT', 'REPLACED') AND reversed_at IS NULL;
DROP INDEX invoicing.uk_einvoice_in_force_per_shipment;
CREATE UNIQUE INDEX uk_einvoice_in_force_per_shipment ON invoicing.einvoice (shipment_id)
    WHERE type IN ('ORIGINAL', 'REPLACEMENT') AND status = 'ISSUED' AND reversed_at IS NULL;

COMMENT ON COLUMN invoicing.einvoice.reversed_at IS
    'WF-05 HĐ-3 (21 §4.4-§4.5): when this issued original stopped standing for its shipment - its delivery was corrected to a failure and its full reduction (correction_basis DELIVERY_FAILED) drawn up. The shipment delivered again gets a new original.';
COMMENT ON COLUMN invoicing.einvoice.correction_basis IS
    'WF-05 E4 (TT 91 Điều 10): why an adjustment or replacement exists - ERROR (khoản 1 điểm b, needs the agreement), RETURN (khoản 5 điểm c), TRADE_DISCOUNT (khoản 5 điểm b); HĐ-3: DELIVERY_FAILED, the system''s full reduction of an invoice whose delivery was corrected to a failure (agreement optional).';

-- As V20261120000700, and: the reduction of a failed delivery only reduces.
CREATE OR REPLACE FUNCTION invoicing.einvoice_line_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    invoice_id       UUID;
    invoice_status   TEXT;
    invoice_type     TEXT;
    invoice_basis    TEXT;
    invoice_shipment UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        invoice_id := OLD.einvoice_id;
    ELSE
        invoice_id := NEW.einvoice_id;
    END IF;
    SELECT status, type, correction_basis, shipment_id INTO invoice_status, invoice_type, invoice_basis, invoice_shipment
      FROM invoicing.einvoice WHERE id = invoice_id FOR SHARE;
    IF invoice_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'the lines of an e-invoice change only while it is a draft (it is %)', invoice_status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_draft_only';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.einvoice_id <> OLD.einvoice_id THEN
        RAISE EXCEPTION 'a line stays on its e-invoice'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_draft_only';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        -- An original, and a replacement, say what was delivered: nothing below zero. An adjustment may reduce (TT 91
        -- Điều 10 khoản 6 điểm c); one for a return, a trade discount or a failed delivery only reduces.
        IF invoice_type IN ('ORIGINAL', 'REPLACEMENT') AND NEW.quantity < 0 THEN
            RAISE EXCEPTION 'an original or replacement e-invoice has no line below zero'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_sign';
        END IF;
        IF invoice_basis IN ('RETURN', 'TRADE_DISCOUNT', 'DELIVERY_FAILED') AND (NEW.quantity > 0 OR NEW.kind = 'NOTE') THEN
            RAISE EXCEPTION 'an adjustment for a return, a trade discount or a failed delivery only reduces'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_sign';
        END IF;
        IF NEW.kind = 'NOTE' AND invoice_type <> 'ADJUSTMENT' THEN
            RAISE EXCEPTION 'only an adjustment has a note line'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_note';
        END IF;
        -- An original without a shipment invoices the rest of the order's shipping fee: no goods, no plate fee.
        IF invoice_type = 'ORIGINAL' AND invoice_shipment IS NULL
           AND (NEW.kind <> 'FEE' OR NEW.fee_kind IS DISTINCT FROM 'SHIPPING_FEE') THEN
            RAISE EXCEPTION 'an e-invoice of a shipping fee rest carries the shipping fee only'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_shipping_fee_rest_lines';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

-- As V20261120000500, and: reversed_at is written once on an issued original, while a live reduction of a failed
-- delivery names it, and never changes after; that reduction is never voided.
CREATE OR REPLACE FUNCTION invoicing.einvoice_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    identity_columns TEXT[] := ARRAY['id', 'code', 'type', 'order_id', 'order_number', 'shipment_id', 'customer_id',
                                     'original_einvoice_id', 'correction_basis', 'return_request_id', 'created_at',
                                     'created_by'];
    bookkeeping      TEXT[] := ARRAY['version', 'last_modified_at', 'last_modified_by'];
    content_columns  TEXT[] := ARRAY['contract_number', 'shipment_code', 'delivery_batch_id', 'issue_date',
                                     'seller_name', 'seller_tax_code', 'seller_address', 'buyer_name', 'buyer_tax_code',
                                     'buyer_address', 'buyer_email', 'currency', 'payment_method', 'subtotal',
                                     'tax_total', 'grand_total', 'amount_in_words', 'adjustment_reason',
                                     'agreement_file_key', 'agreement_number', 'agreement_date'];
    old_row          JSONB;
    new_row          JSONB;
    name             TEXT;
    corrected        invoicing.einvoice%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'e-invoice % is never deleted (status %)', OLD.code, OLD.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_no_delete';
    END IF;
    old_row := to_jsonb(OLD);
    new_row := to_jsonb(NEW);
    FOREACH name IN ARRAY identity_columns LOOP
        IF old_row -> name IS DISTINCT FROM new_row -> name THEN
            RAISE EXCEPTION 'e-invoice %: % never changes', OLD.code, name
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_identity';
        END IF;
    END LOOP;
    IF OLD.status IN ('VOIDED_DRAFT', 'REPLACED') THEN
        RAISE EXCEPTION 'e-invoice % is % and final', OLD.code, OLD.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_final';
    END IF;
    -- WF-05 HĐ-3: once reversed, for good; and reversed only by a live reduction of its failed delivery.
    IF NEW.reversed_at IS DISTINCT FROM OLD.reversed_at AND (OLD.reversed_at IS NOT NULL OR NOT EXISTS (
            SELECT 1 FROM invoicing.einvoice a
             WHERE a.original_einvoice_id = OLD.id AND a.type = 'ADJUSTMENT'
               AND a.correction_basis = 'DELIVERY_FAILED' AND a.status <> 'VOIDED_DRAFT')) THEN
        RAISE EXCEPTION 'e-invoice % is reversed once, as the reduction of its failed delivery is drawn up', OLD.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_reversed_by';
    END IF;
    IF OLD.status = 'ISSUED' THEN
        IF (old_row - bookkeeping - 'status' - 'adjusted' - 'reversed_at')
               IS DISTINCT FROM (new_row - bookkeeping - 'status' - 'adjusted' - 'reversed_at')
           OR NEW.status NOT IN ('ISSUED', 'REPLACED') OR (OLD.adjusted AND NOT NEW.adjusted) THEN
            RAISE EXCEPTION 'e-invoice % is issued and does not change', OLD.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_issued_immutable';
        END IF;
        -- REPLACED is written as the replacement is issued, in the same transaction: the replacement is at the provider.
        IF NEW.status = 'REPLACED' AND NOT EXISTS (
                SELECT 1 FROM invoicing.einvoice r
                 WHERE r.original_einvoice_id = OLD.id AND r.type = 'REPLACEMENT' AND r.status = 'SUBMITTING') THEN
            RAISE EXCEPTION 'e-invoice % becomes REPLACED only as its replacement is issued', OLD.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_replaced_by';
        END IF;
        -- "Has an adjustment" likewise: an adjustment naming it, or listing it, is being issued.
        IF NEW.adjusted AND NOT OLD.adjusted AND NOT EXISTS (
                SELECT 1 FROM invoicing.einvoice a
                 WHERE a.type = 'ADJUSTMENT' AND a.status = 'SUBMITTING'
                   AND (a.original_einvoice_id = OLD.id
                        OR EXISTS (SELECT 1 FROM invoicing.einvoice_adjustment_listing l
                                    WHERE l.einvoice_id = a.id AND l.listed_einvoice_id = OLD.id))) THEN
            RAISE EXCEPTION 'e-invoice % is flagged adjusted only as an adjustment of it is issued', OLD.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_adjusted_by';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status <> OLD.status AND NOT (
            (OLD.status = 'DRAFT' AND NEW.status IN ('SUBMITTING', 'VOIDED_DRAFT'))
            OR (OLD.status = 'SUBMITTING' AND NEW.status IN ('ISSUED', 'REJECTED'))
            OR (OLD.status = 'REJECTED' AND NEW.status = 'DRAFT')) THEN
        RAISE EXCEPTION 'e-invoice % cannot go from % to %', OLD.code, OLD.status, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_transition';
    END IF;
    -- WF-05 HĐ-3: the reduction of a failed delivery is issued, never voided - the invoice it reduces no longer stands
    -- for its shipment, which may already carry its new original.
    IF NEW.status = 'VOIDED_DRAFT' AND OLD.status <> 'VOIDED_DRAFT' AND OLD.correction_basis = 'DELIVERY_FAILED' THEN
        RAISE EXCEPTION 'e-invoice % reduces an invoice whose delivery failed: it is issued, not voided', OLD.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_reversal_kept';
    END IF;
    -- A correction is issued only once the invoice it corrects carries the mark: REPLACED for a replacement, "has an
    -- adjustment" for an adjustment. The two are written together, the corrected invoice first.
    IF OLD.status = 'SUBMITTING' AND NEW.status = 'ISSUED' AND NEW.original_einvoice_id IS NOT NULL THEN
        SELECT * INTO corrected FROM invoicing.einvoice WHERE id = NEW.original_einvoice_id;
        IF (NEW.type = 'REPLACEMENT' AND corrected.status <> 'REPLACED')
           OR (NEW.type = 'ADJUSTMENT' AND NOT (corrected.status = 'ISSUED' AND corrected.adjusted)) THEN
            RAISE EXCEPTION 'e-invoice % is issued only with the invoice it corrects marked', OLD.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_correction_marks_original';
        END IF;
    END IF;
    IF OLD.status <> 'DRAFT' THEN
        FOREACH name IN ARRAY content_columns LOOP
            IF old_row -> name IS DISTINCT FROM new_row -> name THEN
                RAISE EXCEPTION 'e-invoice % is %: % changes only on a draft', OLD.code, OLD.status, name
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_content';
            END IF;
        END LOOP;
    END IF;
    -- A transaction is one attempt: it changes only when a rejected invoice goes back to a draft for another.
    IF OLD.status = 'SUBMITTING' AND (NEW.transaction_id IS DISTINCT FROM OLD.transaction_id
            OR NEW.attempt <> OLD.attempt OR NEW.template_code IS DISTINCT FROM OLD.template_code
            OR NEW.symbol IS DISTINCT FROM OLD.symbol OR NEW.provider IS DISTINCT FROM OLD.provider) THEN
        RAISE EXCEPTION 'e-invoice % is at the provider: its transaction and series do not change', OLD.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_transaction';
    END IF;
    IF NEW.attempt < OLD.attempt THEN
        RAISE EXCEPTION 'e-invoice %: attempts only count up', OLD.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_transaction';
    END IF;
    RETURN NEW;
END
$$;
