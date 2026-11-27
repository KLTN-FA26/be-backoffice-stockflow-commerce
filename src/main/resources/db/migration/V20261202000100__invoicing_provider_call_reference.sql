-- =============================================================================
-- HĐ-1: the provider's own handle on a submission it took in, kept with the call.
--
-- SePay eInvoice takes an invoice in at once and works on it afterwards; whether that work failed is told only by the
-- tracking code its answer gave (GET /v1/invoices/{create|adjust|replace}/check/{tracking_code}: Pending, Success,
-- Failed - developer.sepay.vn/vi/einvoice-api/v1/theo-doi-trang-thai-xuat-hoa-don, kltn-docs 21 §4.2 step 6). The
-- look-up by our own reference only ever showed "draft" and "issued", so an invoice whose processing failed sat in
-- SUBMITTING for good. The code is an id SePay made up, not anything the invoice says, and it is kept with the SUBMIT
-- call that received it: the log is already where every call's facts are, and the invoice row is immutable once
-- issued.
--
--   * provider_reference: on SUBMIT rows of a provider that gives one; null everywhere else.
-- =============================================================================

ALTER TABLE invoicing.einvoice_provider_call ADD COLUMN provider_reference VARCHAR(100);

ALTER TABLE invoicing.einvoice_provider_call ADD CONSTRAINT ck_einvoice_provider_call_reference
    CHECK (provider_reference IS NULL OR (operation = 'SUBMIT' AND btrim(provider_reference) <> ''));

-- The latest handle of a transaction is asked for at every look-up of it.
CREATE INDEX ix_einvoice_provider_call_reference ON invoicing.einvoice_provider_call (transaction_id, called_at)
    WHERE provider_reference IS NOT NULL;

COMMENT ON COLUMN invoicing.einvoice_provider_call.provider_reference IS
    'HĐ-1: the provider''s handle on a submission it took in (SePay: <create|adjust|replace>/<tracking_code>); how a failure after it was taken in is learnt.';
