-- =============================================================================
-- Corrections to issued e-invoices (WF-05 E4; plan §1.4; TT 91/2026/TT-BTC Điều 10 as read in plans/E0-legal.md).
--
-- An issued invoice is never cancelled and never changes. What the law gives instead:
--   * khoản 1 điểm a - a wrong buyer name or address with the right tax code: the buyer is told, the tax authority is
--     notified on Mẫu 04/SS-HĐĐT, and NO new invoice is drawn up. invoicing.einvoice_error_notice (E2 kept its place)
--     gets what the notice says and the rules of its life.
--   * khoản 1 điểm b - a wrong tax code, quantity, price, rate or amount: the seller chooses an ADJUSTMENT (signed
--     amounts; the original stays in force, flagged) or a REPLACEMENT (the original becomes REPLACED). A business buyer
--     and the seller put the error in a written agreement first: its file, number and date are on the invoice before it
--     leaves for the provider.
--   * khoản 5 điểm c.1 - goods returned after the invoice: the seller draws up a decrease adjustment, with the return's
--     own record and no agreement. khoản 5 điểm b - a trade discount by volume or revenue: a decrease adjustment with
--     the list of invoices it covers (invoicing.einvoice_adjustment_listing).
--   * khoản 6 điểm a - the form chosen for an invoice is kept: an invoice that was adjusted is adjusted again, one that
--     replaced another is replaced again. khoản 6 điểm c - an adjustment that reduces is written below zero.
--
-- einvoice.correction_basis says which of these an adjustment or replacement is. Every rule is stated twice: in the
-- aggregate (EInvoice, ErrorNotice) and here.
-- =============================================================================

-- The invoice: what a correction is, the agreement, the return ----------------------------------------------------------
ALTER TABLE invoicing.einvoice
    ADD COLUMN correction_basis  VARCHAR(16),
    ADD COLUMN agreement_number  VARCHAR(64),
    ADD COLUMN agreement_date    DATE,
    ADD COLUMN return_request_id UUID,
    ADD CONSTRAINT fk_einvoice_return_request FOREIGN KEY (return_request_id) REFERENCES ordering.return_request (id),
    -- An original corrects nothing. A replacement corrects an error; an adjustment an error, a return or a trade discount.
    ADD CONSTRAINT ck_einvoice_correction_basis CHECK (
        (type = 'ORIGINAL' AND correction_basis IS NULL)
        OR (type = 'ADJUSTMENT' AND correction_basis IN ('ERROR', 'RETURN', 'TRADE_DISCOUNT'))
        OR (type = 'REPLACEMENT' AND correction_basis = 'ERROR')),
    -- TT 91 Điều 10 khoản 1 điểm b: the correction says what was wrong.
    ADD CONSTRAINT ck_einvoice_correction_reason CHECK (
        type = 'ORIGINAL' OR COALESCE(btrim(adjustment_reason), '') <> ''),
    -- The agreement is its file, its number and its date together, and only an error is agreed in writing.
    ADD CONSTRAINT ck_einvoice_agreement CHECK (
        (agreement_file_key IS NULL AND agreement_number IS NULL AND agreement_date IS NULL)
        OR (agreement_file_key IS NOT NULL AND COALESCE(btrim(agreement_number), '') <> ''
            AND agreement_date IS NOT NULL AND correction_basis = 'ERROR')),
    -- No correction of an error leaves for the provider without it.
    ADD CONSTRAINT ck_einvoice_agreement_before_sending CHECK (
        status IN ('DRAFT', 'VOIDED_DRAFT') OR correction_basis IS DISTINCT FROM 'ERROR'
        OR agreement_file_key IS NOT NULL),
    -- khoản 5 điểm c: an adjustment for returned goods names the return it is for, and nothing else does.
    ADD CONSTRAINT ck_einvoice_return CHECK (
        (COALESCE(correction_basis, '') = 'RETURN') = (return_request_id IS NOT NULL)),
    -- A replacement is the invoice as it should have been: nothing below zero, as on an original.
    ADD CONSTRAINT ck_einvoice_replacement_amounts CHECK (
        type <> 'REPLACEMENT' OR (subtotal >= 0 AND tax_total >= 0)),
    -- A return and a trade discount only ever reduce (khoản 6 điểm c: written below zero).
    ADD CONSTRAINT ck_einvoice_decrease CHECK (
        correction_basis IS NULL OR correction_basis = 'ERROR' OR (subtotal < 0 AND tax_total <= 0));

-- One replacement per invoice: the invoice it replaces is then REPLACED and final.
CREATE UNIQUE INDEX uk_einvoice_replacement ON invoicing.einvoice (original_einvoice_id)
    WHERE type = 'REPLACEMENT' AND status <> 'VOIDED_DRAFT';
-- A return is adjusted once on each invoice it reaches.
CREATE UNIQUE INDEX uk_einvoice_return_adjustment ON invoicing.einvoice (return_request_id, original_einvoice_id)
    WHERE return_request_id IS NOT NULL AND status <> 'VOIDED_DRAFT';
-- One invoice in force per delivered shipment, whether it is the original or what replaced it. (An original that
-- was replaced leaves uk_einvoice_original_per_shipment; this is what still stands for its shipment.)
CREATE UNIQUE INDEX uk_einvoice_in_force_per_shipment ON invoicing.einvoice (shipment_id)
    WHERE type IN ('ORIGINAL', 'REPLACEMENT') AND status = 'ISSUED';
CREATE INDEX ix_einvoice_return_request ON invoicing.einvoice (return_request_id) WHERE return_request_id IS NOT NULL;

COMMENT ON COLUMN invoicing.einvoice.correction_basis IS
    'WF-05 E4 (TT 91 Điều 10): why an adjustment or replacement exists - ERROR (khoản 1 điểm b, needs the agreement), RETURN (khoản 5 điểm c), TRADE_DISCOUNT (khoản 5 điểm b).';

-- The invoices a trade-discount adjustment covers besides the one it names (khoản 5 điểm b: "bảng kê các số hóa đơn").
CREATE TABLE invoicing.einvoice_adjustment_listing
(
    einvoice_id        UUID NOT NULL,
    listed_einvoice_id UUID NOT NULL,

    CONSTRAINT pk_einvoice_adjustment_listing PRIMARY KEY (einvoice_id, listed_einvoice_id),
    CONSTRAINT fk_einvoice_adjustment_listing_einvoice FOREIGN KEY (einvoice_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT fk_einvoice_adjustment_listing_listed FOREIGN KEY (listed_einvoice_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT ck_einvoice_adjustment_listing_other CHECK (einvoice_id <> listed_einvoice_id)
);

CREATE INDEX ix_einvoice_adjustment_listing_listed ON invoicing.einvoice_adjustment_listing (listed_einvoice_id);

COMMENT ON TABLE invoicing.einvoice_adjustment_listing IS
    'WF-05 E4 (TT 91 Điều 10 khoản 5 điểm b): the other issued invoices of the buyer a trade-discount adjustment covers.';

CREATE FUNCTION invoicing.einvoice_adjustment_listing_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    adjustment invoicing.einvoice%ROWTYPE;
    listed     invoicing.einvoice%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO adjustment FROM invoicing.einvoice WHERE id = NEW.einvoice_id FOR SHARE;
    ELSE
        SELECT * INTO adjustment FROM invoicing.einvoice WHERE id = OLD.einvoice_id FOR SHARE;
    END IF;
    IF adjustment.status <> 'DRAFT' OR COALESCE(adjustment.correction_basis, '') <> 'TRADE_DISCOUNT' OR TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'the list of an adjustment is written with a draft trade-discount adjustment and never changed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_adjustment_listing_draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    SELECT * INTO listed FROM invoicing.einvoice WHERE id = NEW.listed_einvoice_id FOR SHARE;
    IF listed.status <> 'ISSUED' OR listed.type = 'ADJUSTMENT' OR listed.customer_id <> adjustment.customer_id
       OR listed.id = adjustment.original_einvoice_id
       OR EXISTS (SELECT 1 FROM invoicing.einvoice r
                   WHERE r.original_einvoice_id = listed.id AND r.type = 'REPLACEMENT' AND r.status <> 'VOIDED_DRAFT') THEN
        RAISE EXCEPTION 'an adjustment lists other invoices in force of the same buyer'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_adjustment_listing_in_force';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_adjustment_listing_guard
    BEFORE INSERT OR UPDATE OR DELETE ON invoicing.einvoice_adjustment_listing
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_adjustment_listing_guard();

-- Lines: a note line, for an adjustment that changes no money ------------------------------------------------------------
-- A wrong tax code is adjusted without a dong changing: the adjustment still has a line, saying what it corrects.
ALTER TABLE invoicing.einvoice_line
    DROP CONSTRAINT ck_einvoice_line_kind,
    DROP CONSTRAINT ck_einvoice_line_subject,
    ADD CONSTRAINT ck_einvoice_line_kind CHECK (kind IN ('GOODS', 'FEE', 'NOTE')),
    ADD CONSTRAINT ck_einvoice_line_subject CHECK (
        (kind = 'GOODS' AND fee_kind IS NULL AND order_line_id IS NOT NULL AND sku IS NOT NULL)
        OR (kind = 'FEE' AND fee_kind IN ('PLATE_FEE', 'SHIPPING_FEE') AND order_line_id IS NULL
            AND units_per_carton IS NULL)
        OR (kind = 'NOTE' AND fee_kind IS NULL AND order_line_id IS NULL AND sku IS NULL
            AND units_per_carton IS NULL AND quantity = 1 AND unit_price = 0 AND tax_rate = 0));

CREATE OR REPLACE FUNCTION invoicing.einvoice_line_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    invoice_id     UUID;
    invoice_status TEXT;
    invoice_type   TEXT;
    invoice_basis  TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        invoice_id := OLD.einvoice_id;
    ELSE
        invoice_id := NEW.einvoice_id;
    END IF;
    SELECT status, type, correction_basis INTO invoice_status, invoice_type, invoice_basis
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
        -- Điều 10 khoản 6 điểm c); one for a return or a trade discount only reduces.
        IF invoice_type IN ('ORIGINAL', 'REPLACEMENT') AND NEW.quantity < 0 THEN
            RAISE EXCEPTION 'an original or replacement e-invoice has no line below zero'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_sign';
        END IF;
        IF invoice_basis IN ('RETURN', 'TRADE_DISCOUNT') AND (NEW.quantity > 0 OR NEW.kind = 'NOTE') THEN
            RAISE EXCEPTION 'an adjustment for a return or a trade discount only reduces'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_sign';
        END IF;
        IF NEW.kind = 'NOTE' AND invoice_type <> 'ADJUSTMENT' THEN
            RAISE EXCEPTION 'only an adjustment has a note line'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_note';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

-- Guards of the invoice ------------------------------------------------------------------------------------------------
-- As V20261120000300, and: what a correction is and the return it is for never change; the agreement changes only on
-- a draft; an invoice becomes REPLACED only as its replacement is issued, and "has an adjustment" only as one is; an
-- adjustment or replacement is issued only once the invoice it corrects says so.
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
    IF OLD.status = 'ISSUED' THEN
        IF (old_row - bookkeeping - 'status' - 'adjusted') IS DISTINCT FROM (new_row - bookkeeping - 'status' - 'adjusted')
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

-- As V20261120000300, and the rules of what may correct what (TT 91 Điều 10):
--   * only an original or a replacement in force is corrected - an adjustment is not adjusted, the invoice it names is;
--   * khoản 6 điểm a, the form is kept: an invoice with a live adjustment is not replaced, one with a live replacement
--     is not adjusted, and an invoice that itself replaced another has its errors replaced again, never adjusted;
--   * a correction is for the shipment of the invoice it corrects;
--   * a shipment whose original was replaced gets no second original.
-- The corrected invoice's row is locked for the rest of the transaction, so two corrections drawn up together are
-- decided one after the other.
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
                    WHERE r.shipment_id = NEW.shipment_id AND r.type = 'REPLACEMENT' AND r.status <> 'VOIDED_DRAFT') THEN
            RAISE EXCEPTION 'the shipment of e-invoice % is invoiced by a replacement', NEW.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_original_per_shipment';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO original FROM invoicing.einvoice WHERE id = NEW.original_einvoice_id FOR NO KEY UPDATE;
    -- TT 91 Điều 10: only an invoice in force is adjusted or replaced, for the same order, buyer and shipment.
    IF original.status <> 'ISSUED' OR original.type = 'ADJUSTMENT' OR original.order_id <> NEW.order_id
       OR original.customer_id <> NEW.customer_id OR original.shipment_id IS DISTINCT FROM NEW.shipment_id THEN
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
    IF NEW.type = 'ADJUSTMENT' AND (
            (NEW.correction_basis = 'ERROR' AND original.type = 'REPLACEMENT')
            OR EXISTS (SELECT 1 FROM invoicing.einvoice r
                        WHERE r.original_einvoice_id = original.id AND r.type = 'REPLACEMENT'
                          AND r.status <> 'VOIDED_DRAFT')) THEN
        RAISE EXCEPTION 'e-invoice % adjusts an invoice whose errors are corrected by replacement', NEW.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_correction_form';
    END IF;
    RETURN NEW;
END
$$;

-- An invoice is not reduced below nothing: what it says, with every adjustment of it still standing, is not below
-- zero. A trade discount is measured against every invoice it lists. Checked when the transaction commits, once the
-- list is written.
CREATE FUNCTION invoicing.einvoice_adjustment_bound_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    adjustment invoicing.einvoice%ROWTYPE;
    covered    NUMERIC;
    adjusted   NUMERIC;
BEGIN
    SELECT * INTO adjustment FROM invoicing.einvoice WHERE id = NEW.id;
    IF NOT FOUND OR adjustment.type <> 'ADJUSTMENT' OR adjustment.status = 'VOIDED_DRAFT' THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(sum(grand_total), 0) INTO covered
      FROM invoicing.einvoice
     WHERE id = adjustment.original_einvoice_id
        OR id IN (SELECT listed_einvoice_id FROM invoicing.einvoice_adjustment_listing WHERE einvoice_id = adjustment.id);
    SELECT COALESCE(sum(grand_total), 0) INTO adjusted
      FROM invoicing.einvoice
     WHERE original_einvoice_id = adjustment.original_einvoice_id AND type = 'ADJUSTMENT' AND status <> 'VOIDED_DRAFT';
    IF covered + adjusted < 0 THEN
        RAISE EXCEPTION 'e-invoice % reduces the invoice it adjusts by more than it holds (% against %)',
            adjustment.code, adjusted, covered
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_adjustment_bound';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_einvoice_adjustment_bound
    AFTER INSERT ON invoicing.einvoice
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_adjustment_bound_check();

-- "Invoice drawn up wrongly" notices (Mẫu 04/SS-HĐĐT) --------------------------------------------------------------------
-- BUYER_NAME_ADDRESS: khoản 1 điểm a - the buyer's name or address is wrong, the tax code is right; the notice carries
-- the right ones and no invoice is drawn up. WITH_CORRECTION: a notice sent beside an adjustment or replacement - điểm
-- b does not ask for one and whether a provider sends it by itself is not verified (plans/E0-legal.md row 2), so the
-- application records it only where stockflow.invoicing.corrections.error-notice-with-correction is on.
ALTER TABLE invoicing.einvoice_error_notice
    ADD COLUMN subject               VARCHAR(24) NOT NULL DEFAULT 'BUYER_NAME_ADDRESS',
    ADD COLUMN correct_buyer_name    VARCHAR(200),
    ADD COLUMN correct_buyer_address VARCHAR(500),
    ADD COLUMN recorded_by           UUID,
    ADD CONSTRAINT fk_einvoice_error_notice_recorded_by FOREIGN KEY (recorded_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_einvoice_error_notice_form CHECK (form_code = '04/SS-HDDT'),
    ADD CONSTRAINT ck_einvoice_error_notice_subject CHECK (subject IN ('BUYER_NAME_ADDRESS', 'WITH_CORRECTION')),
    ADD CONSTRAINT ck_einvoice_error_notice_correct CHECK (
        (subject = 'BUYER_NAME_ADDRESS'
            AND (COALESCE(btrim(correct_buyer_name), '') <> '' OR COALESCE(btrim(correct_buyer_address), '') <> ''))
        OR (subject = 'WITH_CORRECTION' AND correct_buyer_name IS NULL AND correct_buyer_address IS NULL));

-- One notice of an invoice is on its way at a time.
CREATE UNIQUE INDEX uk_einvoice_error_notice_open ON invoicing.einvoice_error_notice (einvoice_id)
    WHERE status IN ('DRAFT', 'SENT');

-- A wrong name or address is notified on an invoice in force; a notice beside a correction, on one that was issued.
CREATE OR REPLACE FUNCTION invoicing.einvoice_error_notice_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'a notice is recorded before it is sent'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_error_notice_starts';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM invoicing.einvoice
                    WHERE id = NEW.einvoice_id
                      AND (status = 'ISSUED' OR (status = 'REPLACED' AND NEW.subject = 'WITH_CORRECTION'))) THEN
        RAISE EXCEPTION 'only an issued e-invoice is notified as drawn up wrongly'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_error_notice_issued';
    END IF;
    RETURN NEW;
END
$$;

-- What a notice says never changes; it goes DRAFT -> SENT -> ACCEPTED | REJECTED (or straight to its answer), and an
-- answered notice is final. Nothing is deleted: it is the record that the tax authority was told.
CREATE FUNCTION invoicing.einvoice_error_notice_life() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a notice of an e-invoice drawn up wrongly is never deleted'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_error_notice_no_delete';
    END IF;
    IF NEW.einvoice_id <> OLD.einvoice_id OR NEW.form_code <> OLD.form_code OR NEW.reason <> OLD.reason
       OR NEW.subject <> OLD.subject OR NEW.correct_buyer_name IS DISTINCT FROM OLD.correct_buyer_name
       OR NEW.correct_buyer_address IS DISTINCT FROM OLD.correct_buyer_address
       OR NEW.recorded_by IS DISTINCT FROM OLD.recorded_by THEN
        RAISE EXCEPTION 'what a notice says does not change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_error_notice_content';
    END IF;
    IF OLD.status IN ('ACCEPTED', 'REJECTED')
       OR (NEW.status <> OLD.status AND NOT (
            (OLD.status = 'DRAFT' AND NEW.status IN ('SENT', 'ACCEPTED', 'REJECTED'))
            OR (OLD.status = 'SENT' AND NEW.status IN ('ACCEPTED', 'REJECTED')))) THEN
        RAISE EXCEPTION 'a notice cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_error_notice_transition';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_error_notice_life
    BEFORE UPDATE OR DELETE ON invoicing.einvoice_error_notice
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_error_notice_life();

-- Calls to the provider: sending a notice is one more operation -----------------------------------------------------------
ALTER TABLE invoicing.einvoice_provider_call
    DROP CONSTRAINT ck_einvoice_provider_call_operation,
    ADD CONSTRAINT ck_einvoice_provider_call_operation CHECK (operation IN ('SUBMIT', 'QUERY', 'DOWNLOAD', 'NOTICE')),
    DROP CONSTRAINT ck_einvoice_provider_call_outcome,
    ADD CONSTRAINT ck_einvoice_provider_call_outcome CHECK (outcome IN ('ACCEPTED', 'DUPLICATE', 'PENDING', 'ISSUED',
                                                                    'REJECTED', 'NOT_FOUND', 'DOWNLOADED', 'TIMEOUT',
                                                                    'ERROR', 'NOT_SUPPORTED'));

-- The sandbox provider's books: which invoice a transaction adjusts or replaces -------------------------------------------
ALTER TABLE invoicing.sandbox_issuance
    ADD COLUMN kind                    VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL',
    ADD COLUMN original_template_code  VARCHAR(1),
    ADD COLUMN original_symbol         VARCHAR(6),
    ADD COLUMN original_invoice_number INTEGER,
    ADD CONSTRAINT ck_sandbox_issuance_kind CHECK (kind IN ('ORIGINAL', 'ADJUSTMENT', 'REPLACEMENT')),
    ADD CONSTRAINT ck_sandbox_issuance_original CHECK (
        (kind = 'ORIGINAL' AND original_template_code IS NULL AND original_symbol IS NULL
            AND original_invoice_number IS NULL)
        OR (kind <> 'ORIGINAL' AND original_template_code IS NOT NULL AND original_symbol IS NOT NULL
            AND original_invoice_number IS NOT NULL));

CREATE INDEX ix_sandbox_issuance_original ON invoicing.sandbox_issuance
    (original_template_code, original_symbol, original_invoice_number) WHERE kind <> 'ORIGINAL';
