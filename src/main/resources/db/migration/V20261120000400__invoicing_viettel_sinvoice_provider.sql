-- =============================================================================
-- WF-05 E3: Viettel SInvoice as an e-invoice provider that can be configured.
--
-- The sandbox stays the provider in use. This only makes the second one configurable:
--   * a row for VIETTEL_SINVOICE in invoicing.einvoice_provider_config, not in use, with no account yet. The
--     administrator stores the account (the password sealed with STOCKFLOW_SECRETS_KEY) and then puts it in use.
--   * invoicing.einvoice_series.provider_template_code: what the PROVIDER calls the template a series is numbered
--     under. The template code of TT 91 is one digit ("1"); SInvoice names its own templates ("1/001", up to 20
--     characters) and every call to it carries that name. The sandbox has none.
--   * a provider other than the sandbox is put in use only with an account: stated in the service and here.
-- =============================================================================

ALTER TABLE invoicing.einvoice_series
    ADD COLUMN provider_template_code VARCHAR(20),
    ADD CONSTRAINT ck_einvoice_series_provider_template
        CHECK (provider_template_code IS NULL OR provider_template_code ~ '^[0-9A-Za-z/]{1,20}$');

COMMENT ON COLUMN invoicing.einvoice_series.provider_template_code IS
    'WF-05 E3: the template''s code at the provider (SInvoice templateCode, e.g. 1/001); null where the provider has none (sandbox).';

ALTER TABLE invoicing.einvoice_provider_config
    ADD CONSTRAINT ck_einvoice_provider_config_account
        CHECK (provider = 'SANDBOX' OR NOT active OR (username IS NOT NULL AND secret_sealed IS NOT NULL));

INSERT INTO invoicing.einvoice_provider_config (id, provider, environment, active, auto_issue, created_by)
VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', 'SANDBOX', FALSE, FALSE, 'flyway');
