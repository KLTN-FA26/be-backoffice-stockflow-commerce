-- =============================================================================
-- WF-05 BE-10, BE-29, BE-36, Q-9 (decisions Q-8, Q-9): the reduction of a failed delivery may now be voided in two
-- named cases, the reversal of an invoice may be taken back once, and a replacement is never adjusted.
--
-- Why.
--   * BE-10: a reduction that no provider can send - its original was issued through a provider no longer usable or
--     one that issues no corrections, or the provider refuses it for good - could be neither issued nor voided
--     (tg_einvoice_reversal_kept), and the order waited on it for ever. Q-8: the accountant voids it with the code
--     UNSENDABLE_REVERSAL, makes the reduction on the provider's portal and records it (issued_via PORTAL_RECORD).
--     The original stays reversed: the shipment, if delivered again, still takes its own original.
--   * BE-29: a delivery corrected to a failure and then back to delivered before the reduction left gave three
--     invoices for one delivery (the original, its reduction, and a new original). The system now voids that
--     reduction (code DELIVERY_CORRECTED_BACK) and takes the original's reversed_at back, in one transaction; the
--     guard lets reversed_at go back to NULL only then, which the voided reduction's row version (xmin = this
--     transaction) proves. uk_einvoice_original_per_shipment refuses it if another original stands meanwhile.
--   * BE-36: an invoice reversed for a failed delivery was still open to adjustments and replacements.
--   * BE-30: a correction is recorded under the series the provider numbered it under, which SePay chooses itself.
--   * Q-9: a replacement is put right by another replacement (TT 91 Điều 10 khoản 6 điểm a) and SePay refuses any
--     adjustment of it (sandbox T-5: 422 INVALID_ORIGIN_INVOICE); only ERROR was refused before.
-- What this does: void_reason_code with its CHECK, and the guard and the draft-first trigger as below.
-- No row is touched.
-- =============================================================================

ALTER TABLE invoicing.einvoice ADD COLUMN void_reason_code VARCHAR(32);
ALTER TABLE invoicing.einvoice
    ADD CONSTRAINT ck_einvoice_void_reason_code CHECK (
        void_reason_code IS NULL
        OR (void_reason_code IN ('UNSENDABLE_REVERSAL', 'DELIVERY_CORRECTED_BACK') AND status = 'VOIDED_DRAFT'
            AND correction_basis = 'DELIVERY_FAILED'));

COMMENT ON COLUMN invoicing.einvoice.void_reason_code IS
    'WF-05 Q-8/BE-10, BE-29: why the reduction of a failed delivery was voided - UNSENDABLE_REVERSAL (no provider can send it: made on the portal and recorded instead) or DELIVERY_CORRECTED_BACK (the delivery was corrected back to delivered before it left; the original''s reversed_at taken back in the same transaction). Null on every other row.';

-- As V20261202000210, and: delivered_on never changes (V20261219000400 adds it; listed here, where the function is
-- rewritten, since a column the function names must exist only when the function runs); reversed_at may go back to
-- NULL as above; the reduction of a failed delivery may be voided under one of the two codes.
CREATE OR REPLACE FUNCTION invoicing.einvoice_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    identity_columns TEXT[] := ARRAY['id', 'code', 'type', 'order_id', 'order_number', 'shipment_id', 'customer_id',
                                     'original_einvoice_id', 'correction_basis', 'return_request_id', 'created_at',
                                     'created_by', 'delivered_on'];
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
    -- WF-05 HĐ-3: reversed only by a live reduction of its failed delivery, written once. BE-29: the mark is taken
    -- back only when the delivery was corrected back to delivered before that reduction left - its reduction voided
    -- as DELIVERY_CORRECTED_BACK in this very transaction, and no other live reduction of a failed delivery naming it.
    IF NEW.reversed_at IS DISTINCT FROM OLD.reversed_at THEN
        IF NEW.reversed_at IS NULL THEN
            IF NOT EXISTS (SELECT 1 FROM invoicing.einvoice a
                            WHERE a.original_einvoice_id = OLD.id AND a.type = 'ADJUSTMENT'
                              AND a.correction_basis = 'DELIVERY_FAILED' AND a.status = 'VOIDED_DRAFT'
                              AND a.void_reason_code = 'DELIVERY_CORRECTED_BACK'
                              AND a.xmin = pg_current_xact_id()::xid)
               OR EXISTS (SELECT 1 FROM invoicing.einvoice a
                           WHERE a.original_einvoice_id = OLD.id AND a.type = 'ADJUSTMENT'
                             AND a.correction_basis = 'DELIVERY_FAILED' AND a.status <> 'VOIDED_DRAFT') THEN
                RAISE EXCEPTION 'e-invoice % stays reversed: its reduction was not voided as corrected back now', OLD.code
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_reversed_by';
            END IF;
        ELSIF OLD.reversed_at IS NOT NULL OR NOT EXISTS (
                SELECT 1 FROM invoicing.einvoice a
                 WHERE a.original_einvoice_id = OLD.id AND a.type = 'ADJUSTMENT'
                   AND a.correction_basis = 'DELIVERY_FAILED' AND a.status <> 'VOIDED_DRAFT') THEN
            RAISE EXCEPTION 'e-invoice % is reversed once, as the reduction of its failed delivery is drawn up', OLD.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_reversed_by';
        END IF;
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
    -- for its shipment, which may already carry its new original. Two exceptions, each with its code (Q-8, BE-10,
    -- BE-29): the accountant voids one no provider can send, to make it on the provider's portal and record it; the
    -- system voids one whose delivery was corrected back to delivered before it left (and takes the mark back).
    IF NEW.status = 'VOIDED_DRAFT' AND OLD.status <> 'VOIDED_DRAFT' AND OLD.correction_basis = 'DELIVERY_FAILED'
       AND NEW.void_reason_code IS DISTINCT FROM 'UNSENDABLE_REVERSAL'
       AND NEW.void_reason_code IS DISTINCT FROM 'DELIVERY_CORRECTED_BACK' THEN
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
    -- A transaction is one attempt: it changes only when a rejected invoice goes back to a draft for another. BE-30: a
    -- correction takes the symbol the provider numbered it under as it is recorded issued (SePay chooses the series of
    -- an adjustment or replacement itself, sandbox T-4); an original never does.
    IF OLD.status = 'SUBMITTING' AND (NEW.transaction_id IS DISTINCT FROM OLD.transaction_id
            OR NEW.attempt <> OLD.attempt OR NEW.template_code IS DISTINCT FROM OLD.template_code
            OR (NEW.symbol IS DISTINCT FROM OLD.symbol
                AND NOT (NEW.status = 'ISSUED' AND OLD.type <> 'ORIGINAL' AND NEW.symbol IS NOT NULL))
            OR NEW.provider IS DISTINCT FROM OLD.provider) THEN
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

-- As V20261203000300, and: a reversed invoice is not corrected again (BE-36); no adjustment of a replacement (Q-9).
CREATE OR REPLACE FUNCTION invoicing.einvoice_starts_as_draft() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    original invoicing.einvoice%ROWTYPE;
BEGIN
    IF NEW.status <> 'DRAFT' OR NEW.adjusted THEN
        RAISE EXCEPTION 'e-invoice % is drawn up as a draft, not %', NEW.code, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_starts_as_draft';
    END IF;
    IF NEW.original_einvoice_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM invoicing.einvoice r
                    WHERE r.shipment_id = NEW.shipment_id AND r.type = 'REPLACEMENT' AND r.status <> 'VOIDED_DRAFT'
                      AND NOT EXISTS (SELECT 1 FROM invoicing.einvoice x
                                       WHERE x.shipment_id = r.shipment_id AND x.reversed_at IS NOT NULL
                                         AND x.created_at >= r.created_at)) THEN
            RAISE EXCEPTION 'the shipment of e-invoice % is invoiced by a replacement', NEW.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_original_per_shipment';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO original FROM invoicing.einvoice WHERE id = NEW.original_einvoice_id FOR NO KEY UPDATE;
    -- TT 91 Điều 10: only an invoice in force is adjusted or replaced, for the same order, buyer and shipment.
    -- BE-36: an invoice reduced in full for a failed delivery is not corrected again; only the reduction of that
    -- failure names it (the system's, or the one recorded from the provider's portal after it, Q-8).
    IF original.status <> 'ISSUED' OR original.type = 'ADJUSTMENT' OR original.order_id <> NEW.order_id
       OR original.customer_id <> NEW.customer_id OR original.shipment_id IS DISTINCT FROM NEW.shipment_id
       OR (original.reversed_at IS NOT NULL AND NEW.correction_basis IS DISTINCT FROM 'DELIVERY_FAILED') THEN
        RAISE EXCEPTION 'e-invoice % changes an invoice that is not an issued invoice of the same order', NEW.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_original_in_force';
    END IF;
    IF NEW.type = 'REPLACEMENT' AND (original.adjusted OR EXISTS (
            SELECT 1 FROM invoicing.einvoice a
             WHERE a.type = 'ADJUSTMENT' AND a.status <> 'VOIDED_DRAFT'
               AND (a.original_einvoice_id = original.id
                    OR EXISTS (SELECT 1 FROM invoicing.einvoice_adjustment_listing l
                                WHERE l.einvoice_id = a.id AND l.listed_einvoice_id = original.id)))) THEN
        RAISE EXCEPTION 'e-invoice % replaces an invoice that is being adjusted', NEW.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_correction_form';
    END IF;
    -- Q-9 (TT 91 Điều 10 khoản 6 điểm a; SePay 422 INVALID_ORIGIN_INVOICE, sandbox T-5): a replacement is never
    -- adjusted, whatever the basis - only a reduction made on the provider's portal is recorded against it.
    IF NEW.type = 'ADJUSTMENT' AND (
            (original.type = 'REPLACEMENT' AND NEW.issued_via IS DISTINCT FROM 'PORTAL_RECORD')
            OR EXISTS (SELECT 1 FROM invoicing.einvoice r
                        WHERE r.original_einvoice_id = original.id AND r.type = 'REPLACEMENT'
                          AND r.status <> 'VOIDED_DRAFT')) THEN
        RAISE EXCEPTION 'e-invoice % adjusts an invoice whose errors are corrected by replacement', NEW.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_correction_form';
    END IF;
    RETURN NEW;
END
$$;
