-- =============================================================================
-- SePay eInvoice as an e-invoice provider that can be configured.
--
-- The sandbox stays the provider in use. This only makes a third one configurable:
--   * SEPAY_EINVOICE joins the providers invoicing.einvoice_provider_config may name.
--   * Its account is the deployment's configuration (EINVOICE_SEPAY_CLIENT_ID / EINVOICE_SEPAY_CLIENT_SECRET), not a
--     row: SePay issues a client id and secret per environment, which belong with a deployment's other API keys. So
--     the row may be put in use with no username or sealed secret; the service refuses to put it in use while the
--     deployment has no key (EInvoiceProviderAccountService.activate).
--   * a row for SEPAY_EINVOICE, not in use. Its series are named with SePay's template code ("1") through
--     PUT /api/v1/einvoice-settings/providers/SEPAY_EINVOICE/series, as Viettel's are.
-- =============================================================================

ALTER TABLE invoicing.einvoice_provider_config
    DROP CONSTRAINT ck_einvoice_provider_config_provider,
    ADD CONSTRAINT ck_einvoice_provider_config_provider
        CHECK (provider IN ('SANDBOX', 'VIETTEL_SINVOICE', 'SEPAY_EINVOICE'));

ALTER TABLE invoicing.einvoice_provider_config
    DROP CONSTRAINT ck_einvoice_provider_config_account,
    ADD CONSTRAINT ck_einvoice_provider_config_account
        CHECK (provider IN ('SANDBOX', 'SEPAY_EINVOICE') OR NOT active
            OR (username IS NOT NULL AND secret_sealed IS NOT NULL));

INSERT INTO invoicing.einvoice_provider_config (id, provider, environment, active, auto_issue, created_by)
VALUES (gen_random_uuid(), 'SEPAY_EINVOICE', 'SANDBOX', FALSE, FALSE, 'flyway');
