-- HĐ-7, owner's decision: an e-invoice line at 0% is never issued.
--
-- No tax class here is zero-rated. 0.00 is the rate ordering.tax_rate keeps for EXEMPT - goods not subject to VAT
-- ("không chịu thuế", KCT) - and an invoice that says 0% declares zero-rated goods (exports and the like), which is a
-- different thing in law. No provider adapter here states KCT yet: SePay takes 0, 5, 8 and 10 only, and nothing is sent
-- as KCT. So a line at 0% - which only EXEMPT produces, through a quote, an order, a fee or a correction - keeps its
-- invoice from leaving DRAFT. The aggregate says so first (EInvoice.submit, EINVOICE_TAX_EXEMPT_NOT_SUPPORTED, and the
-- warning TAX_EXEMPT_NOT_SUPPORTED that keeps the draft from the automatic issuing); this is the same rule where a
-- manual UPDATE cannot talk its way past it. A note line (kind NOTE, always 0) is not a rate and is not counted.
--
-- Only the move to SUBMITTING is checked: an invoice already at the provider or issued is left as it is (none exists -
-- no product is EXEMPT - and refusing to record a provider's answer would not unsend it). The tax class EXEMPT and the
-- CHECKs naming it stay: tests and the QA checks use them, and drawing a draft up at 0% is allowed - it is what shows
-- the accountant the problem.

CREATE FUNCTION invoicing.einvoice_no_zero_rate_line() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM invoicing.einvoice_line l
                WHERE l.einvoice_id = NEW.id AND l.kind <> 'NOTE' AND l.tax_rate = 0) THEN
        RAISE EXCEPTION 'e-invoice % has a line at 0%%: EXEMPT (not subject to VAT) is not issued as 0%%', NEW.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_no_zero_rate_line';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_no_zero_rate_line
    BEFORE UPDATE OF status ON invoicing.einvoice
    FOR EACH ROW
    WHEN (NEW.status = 'SUBMITTING' AND OLD.status IS DISTINCT FROM 'SUBMITTING')
    EXECUTE FUNCTION invoicing.einvoice_no_zero_rate_line();
