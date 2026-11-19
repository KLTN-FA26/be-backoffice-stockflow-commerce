-- =============================================================================
-- WF-05 HĐ-3, the two cases V20261202000210 left to the accountant: a delivery corrected to a failure while its invoice
-- is still at the provider, and while a replacement stands for the shipment (kltn-docs 21 §4.4, §4.5 step 14, BR-01).
--
-- Why. Both only told the accountants, and both fell back on the problem HĐ-3 exists to solve. The one correction they
-- could draw up by hand was ERROR, which needs an agreement signed by a buyer who never received the goods; and nothing
-- stopped the invoice standing for the shipment, so the goods delivered again were never invoiced and the order never
-- completed.
--
-- What this does:
--   1. einvoice.delivery_failed_at: the delivery the invoice was sent for was corrected to a failure while the invoice
--      was at the provider. Nothing can be decided then - the provider may still issue it, or refuse it - so the mark
--      says what to do once it answers (DeliveryCorrectionListener): issued, it is reduced in full like any issued
--      invoice of a failed delivery; refused, it is voided instead of being corrected and sent again. Written once,
--      while the invoice is SUBMITTING, on an original of a shipment or on the replacement of an issued original that
--      is at the provider; an invoice so marked is never sent again (tg_einvoice_delivery_failed).
--      EInvoiceIssuanceSteps.submit refuses it first, with a business error. Taken back only while the invoice is not
--      issued, when the shipment turns out delivered after all (the failure corrected back, or a carrier's retry).
--   2. ck_einvoice_reversed: a replacement in force may be reversed too. It stands for its shipment exactly as an
--      original does, and the reduction of it is an adjustment with the basis DELIVERY_FAILED, which TT 91 Điều 10
--      allows on a replacement (only an ERROR is replaced again rather than adjusted).
--   3. einvoice_starts_as_draft: a shipment whose replacement was reversed takes a new original. "Invoiced by a
--      replacement" now means a replacement no reversal has superseded: a reversed invoice of the shipment created at
--      or after it - the reversed replacement itself, or the head of its chain (a replacement replaced again). Created
--      in a later transaction than every invoice of its chain, and earlier than any invoice of the shipment's next
--      delivery, which can only be drawn up once the old chain stopped standing for the shipment.
-- No row is touched: no invoice is marked or reversed yet by this.
-- =============================================================================

ALTER TABLE invoicing.einvoice ADD COLUMN delivery_failed_at TIMESTAMPTZ;

ALTER TABLE invoicing.einvoice
    -- What can be at the provider for a shipment when its delivery is corrected: its original, or the replacement of
    -- its issued original. An adjustment is never marked: it changes an invoice, it does not stand for a delivery.
    ADD CONSTRAINT ck_einvoice_delivery_failed CHECK (
        delivery_failed_at IS NULL OR (type IN ('ORIGINAL', 'REPLACEMENT') AND shipment_id IS NOT NULL));

ALTER TABLE invoicing.einvoice DROP CONSTRAINT ck_einvoice_reversed;
ALTER TABLE invoicing.einvoice
    ADD CONSTRAINT ck_einvoice_reversed CHECK (
        reversed_at IS NULL OR (type IN ('ORIGINAL', 'REPLACEMENT') AND shipment_id IS NOT NULL AND status = 'ISSUED'));

CREATE FUNCTION invoicing.einvoice_delivery_failed_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.delivery_failed_at IS NOT NULL THEN
            RAISE EXCEPTION 'e-invoice % is drawn up for a delivery, not marked as a failed one', NEW.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_delivery_failed';
        END IF;
        RETURN NEW;
    END IF;
    -- Taken back (the shipment delivered after all) only while the invoice is not issued: an issued one never changes.
    IF NEW.delivery_failed_at IS NULL AND OLD.delivery_failed_at IS NOT NULL
       AND OLD.status NOT IN ('SUBMITTING', 'DRAFT', 'REJECTED') THEN
        RAISE EXCEPTION 'e-invoice % keeps its mark once it is %', OLD.code, OLD.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_delivery_failed';
    END IF;
    IF NEW.delivery_failed_at IS NOT NULL AND NEW.delivery_failed_at IS DISTINCT FROM OLD.delivery_failed_at
       AND (OLD.delivery_failed_at IS NOT NULL OR OLD.status <> 'SUBMITTING') THEN
        RAISE EXCEPTION 'e-invoice % is marked once, while it is at the provider', OLD.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_delivery_failed';
    END IF;
    IF NEW.delivery_failed_at IS NOT NULL AND NEW.status = 'SUBMITTING' AND OLD.status <> 'SUBMITTING' THEN
        RAISE EXCEPTION 'e-invoice % invoices a delivery that failed: it is not sent again', OLD.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_delivery_failed';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_delivery_failed
    BEFORE INSERT OR UPDATE ON invoicing.einvoice
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_delivery_failed_guard();

-- As V20261120000500, and: a replacement superseded by a reversal (its own, or of the replacement that replaced it)
-- no longer keeps a new original from its shipment.
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

COMMENT ON COLUMN invoicing.einvoice.delivery_failed_at IS
    'WF-05 HĐ-3: when the delivery this original (or this replacement of an issued original) was sent to the provider for was corrected to a failure, before the provider answered. Issued, it is then reduced in full (correction_basis DELIVERY_FAILED); refused, it is voided rather than sent again (a refused replacement has its original reduced). Taken back while not issued if the shipment is delivered after all.';
COMMENT ON COLUMN invoicing.einvoice.reversed_at IS
    'WF-05 HĐ-3 (21 §4.4-§4.5): when this issued original, or the replacement standing for its shipment, stopped standing for the shipment - its delivery was corrected to a failure and its full reduction (correction_basis DELIVERY_FAILED) drawn up. The shipment delivered again gets a new original.';
