-- =============================================================================
-- Adversarial checks + happy paths: Viettel SInvoice as a provider that can be configured (V20261120000400; WF-05 E3;
-- plan §1.5; kltn-docs 21 BR-10). Needs nothing but the migrations. Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints and constraint triggers fire here, still inside the sub-transaction.
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'ok    %  [%]', label, left(SQLERRM, 110);
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    RAISE NOTICE 'ok    %', label;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- what the migration leaves'
SELECT pg_temp.expect_true('A1 the sandbox is still the provider in use, alone',
    (SELECT count(*) = 1 AND bool_and(provider = 'SANDBOX') FROM invoicing.einvoice_provider_config WHERE active));
SELECT pg_temp.expect_true('A2 Viettel SInvoice is there, not in use, with no account',
    (SELECT count(*) = 1 AND bool_and(NOT active AND NOT auto_issue AND environment = 'SANDBOX'
                                      AND username IS NULL AND secret_sealed IS NULL)
       FROM invoicing.einvoice_provider_config WHERE provider = 'VIETTEL_SINVOICE'));
SELECT pg_temp.expect_true('A3 no series of the sandbox names a provider template',
    (SELECT count(*) = 0 FROM invoicing.einvoice_series WHERE provider_template_code IS NOT NULL));

\echo '--- putting Viettel SInvoice in use'
SELECT pg_temp.expect_fail('B1 in use beside the sandbox', $q$
    UPDATE invoicing.einvoice_provider_config SET active = TRUE, username = '0312345678',
           secret_sealed = 'v1:c2VhbGVk' WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_fail('B2 a second row for the provider', $q$
    INSERT INTO invoicing.einvoice_provider_config (id, provider, environment, active)
    VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', 'SANDBOX', FALSE) $q$);
SELECT pg_temp.expect_fail('B3 a provider nobody wrote an adapter for', $q$
    INSERT INTO invoicing.einvoice_provider_config (id, provider, environment, active)
    VALUES (gen_random_uuid(), 'MISA', 'SANDBOX', FALSE) $q$);

UPDATE invoicing.einvoice_provider_config SET active = FALSE WHERE provider = 'SANDBOX';
SELECT pg_temp.expect_fail('B4 in use with no account at all', $q$
    UPDATE invoicing.einvoice_provider_config SET active = TRUE WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_fail('B5 in use with a user name and no password', $q$
    UPDATE invoicing.einvoice_provider_config SET active = TRUE, username = '0312345678'
     WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_fail('B6 in use with a password and no user name', $q$
    UPDATE invoicing.einvoice_provider_config SET active = TRUE, secret_sealed = 'v1:c2VhbGVk'
     WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_fail('B7 a password kept in clear', $q$
    UPDATE invoicing.einvoice_provider_config SET username = '0312345678', secret_sealed = 'hunter2'
     WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_ok('B8 the account stored while not in use', $q$
    UPDATE invoicing.einvoice_provider_config SET username = '0312345678', secret_sealed = 'v1:c2VhbGVk',
           endpoint = 'https://api-vinvoice.viettel.vn/services/einvoiceapplication/api'
     WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_ok('B9 in use once it has an account', $q$
    UPDATE invoicing.einvoice_provider_config SET active = TRUE WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_fail('B10 its password taken away while in use', $q$
    UPDATE invoicing.einvoice_provider_config SET secret_sealed = NULL WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_fail('B11 its user name taken away while in use', $q$
    UPDATE invoicing.einvoice_provider_config SET username = NULL WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_fail('B12 the sandbox back in use beside it', $q$
    UPDATE invoicing.einvoice_provider_config SET active = TRUE WHERE provider = 'SANDBOX' $q$);
SELECT pg_temp.expect_ok('B13 production is an environment of a real provider', $q$
    UPDATE invoicing.einvoice_provider_config SET environment = 'PRODUCTION' WHERE provider = 'VIETTEL_SINVOICE' $q$);
SELECT pg_temp.expect_ok('B14 back to the sandbox: one out, the other in', $q$
    UPDATE invoicing.einvoice_provider_config SET active = FALSE WHERE provider = 'VIETTEL_SINVOICE';
    UPDATE invoicing.einvoice_provider_config SET active = TRUE WHERE provider = 'SANDBOX' $q$);
SELECT pg_temp.expect_true('B15 the sandbox needs no account to be in use',
    (SELECT username IS NULL AND secret_sealed IS NULL FROM invoicing.einvoice_provider_config WHERE active));

\echo '--- a series and the template the provider knows it by'
SELECT pg_temp.expect_ok('C1 a series of Viettel SInvoice with its template', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active, provider_template_code)
    VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', '1', 'C26TAA', 2026, TRUE, '1/001') $q$);
SELECT pg_temp.expect_fail('C2 a second series in use for the provider and year', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active, provider_template_code)
    VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', '1', 'C26TAB', 2026, TRUE, '1/002') $q$);
SELECT pg_temp.expect_fail('C3 a template code with a space', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active, provider_template_code)
    VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', '1', 'C26TAB', 2026, FALSE, '1 001') $q$);
SELECT pg_temp.expect_fail('C4 an empty template code', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active, provider_template_code)
    VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', '1', 'C26TAB', 2026, FALSE, '') $q$);
SELECT pg_temp.expect_fail('C5 a template code of 21 characters', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active, provider_template_code)
    VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', '1', 'C26TAB', 2026, FALSE, '123456789012345678901') $q$);
SELECT pg_temp.expect_fail('C6 a template code carrying markup', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active, provider_template_code)
    VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', '1', 'C26TAB', 2026, FALSE, '1/001<x>') $q$);
SELECT pg_temp.expect_ok('C7 a template code of 20 characters', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active, provider_template_code)
    VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', '1', 'C26TAB', 2026, FALSE, '12345678901234567/AB') $q$);
SELECT pg_temp.expect_true('C8 the sandbox series of the year is still the one in use there',
    (SELECT count(*) = 1 FROM invoicing.einvoice_series WHERE provider = 'SANDBOX' AND year = 2026 AND active));

ROLLBACK;
