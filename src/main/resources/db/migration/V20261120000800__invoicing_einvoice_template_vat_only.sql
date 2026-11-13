-- =============================================================================
-- E-invoices: the template code is 1, a VAT invoice (legal review WF05-E0, finding L1).
--
-- The seller declares VAT by the credit method and issues no other kind of invoice (NĐ 254 Phụ lục; TT 91 Phụ lục I),
-- so a series or an invoice under another digit is a typing error that would reach the provider as a sales invoice.
--   * ck_einvoice_series_template: a series is entered under template 1 only.
--   * ck_einvoice_template: an invoice is sent under template 1 only.
-- =============================================================================

ALTER TABLE invoicing.einvoice_series DROP CONSTRAINT ck_einvoice_series_template;
ALTER TABLE invoicing.einvoice_series ADD CONSTRAINT ck_einvoice_series_template CHECK (template_code = '1');

-- Null until the invoice is sent; ck_einvoice_submitted requires it from then on.
ALTER TABLE invoicing.einvoice ADD CONSTRAINT ck_einvoice_template CHECK (template_code IS NULL OR template_code = '1');
