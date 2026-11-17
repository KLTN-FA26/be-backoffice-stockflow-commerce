-- =============================================================================
-- E-invoices: the buyer's e-mail is no longer needed to send an invoice (legal review WF05-E0, finding L7).
--
-- It is not invoice content - NĐ 254 Phụ lục điểm 4.b names the buyer's name, address and tax code - but where the
-- invoice is mailed, and a buyer without one is told on the portal. Requiring it held back invoices the law requires
-- to be issued by the next working day.
--   * ck_einvoice_buyer_complete: name, tax code and address, as before; no e-mail.
-- =============================================================================

ALTER TABLE invoicing.einvoice DROP CONSTRAINT ck_einvoice_buyer_complete;
-- NĐ 254 Phụ lục điểm 4.b, kltn-docs 21 BR-06: no invoice leaves for the provider without the buyer's name, tax code
-- and address. The e-mail it is mailed to is not among them.
ALTER TABLE invoicing.einvoice ADD CONSTRAINT ck_einvoice_buyer_complete CHECK (status IN ('DRAFT', 'VOIDED_DRAFT')
    OR (btrim(buyer_name) <> '' AND COALESCE(btrim(buyer_tax_code), '') <> ''
        AND COALESCE(btrim(buyer_address), '') <> ''));
