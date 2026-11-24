-- =============================================================================
-- Demo WF-05 with the SePay sandbox (seed gaps G-2, G-3). Its own location, db/demo-sepay, added to
-- spring.flyway.locations ONLY on a machine that runs the demo against SePay with its keys set
-- (EINVOICE_SEPAY_CLIENT_ID / EINVOICE_SEPAY_CLIENT_SECRET), for example
--     --spring.flyway.locations=classpath:db/migration,classpath:db/demo,classpath:db/demo-fake,classpath:db/demo-sepay
-- With SePay active and no keys nothing could be issued at all, which is why the application refuses to activate it
-- without them (EInvoiceProviderAccountService.activate, V20261124000800) and why this is not in db/demo. No key or
-- secret is written here.
--
-- G-2: SePay becomes the provider in use, issuing on its own (V20261219000400 set auto_issue for it); the built-in
--      SANDBOX provider steps aside - one provider is active at a time (uk_einvoice_provider_config_active).
-- G-3: SePay's 2026 series: template 1, symbol C26TSE, the sandbox series T-1..T-7 issued on
--      (rescope-handoff debate/sandbox/state.json). A demo held in 2027 needs a C27 series checked on the sandbox first
--      (decision file §4.2).
-- =============================================================================

UPDATE invoicing.einvoice_provider_config
   SET active = FALSE, version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE provider = 'SANDBOX';

UPDATE invoicing.einvoice_provider_config
   SET active = TRUE, auto_issue = TRUE, environment = 'SANDBOX', version = version + 1, last_modified_at = NOW(),
       last_modified_by = 'flyway'
 WHERE provider = 'SEPAY_EINVOICE';

INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active, provider_template_code,
                                       version, created_at, created_by)
VALUES (md5('demo:wf05:series:SEPAY:C26TSE')::uuid, 'SEPAY_EINVOICE', '1', 'C26TSE', 2026, TRUE, '1', 0, NOW(),
        'flyway');
