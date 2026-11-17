-- =============================================================================
-- Adversarial checks + happy paths: a customer's legal profile keeps the invoice e-mail and the bank account, and their
-- changes are kept (V20261120000100, WF-05 E1; kltn-docs 18 §3, BR-12, §6; 21 BR-06). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
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

INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qa90:customer')::uuid, 'Cong ty QA 90', 'qa90@example.com', 'ACTIVE', 0, NOW());
INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name,
                                             representative_title, version, created_at)
VALUES (md5('qa90:profile')::uuid, md5('qa90:customer')::uuid, '1 Le Loi, Q1, TP HCM', 'Nguyen Van A', 'Giam doc', 0,
        NOW());

-- The invoice e-mail ----------------------------------------------------------------------------------------------
SELECT pg_temp.expect_ok('an invoice e-mail is recorded',
    $$UPDATE customer.customer_legal_profile SET invoice_email = 'ke.toan@qa90.example.com'
       WHERE id = md5('qa90:profile')::uuid$$);
SELECT pg_temp.expect_fail('an invoice e-mail without a domain is refused',
    $$UPDATE customer.customer_legal_profile SET invoice_email = 'ke.toan' WHERE id = md5('qa90:profile')::uuid$$);
SELECT pg_temp.expect_fail('an invoice e-mail with a space is refused',
    $$UPDATE customer.customer_legal_profile SET invoice_email = 'ke toan@qa90.example.com'
       WHERE id = md5('qa90:profile')::uuid$$);
SELECT pg_temp.expect_fail('two addresses in one value are refused',
    $$UPDATE customer.customer_legal_profile SET invoice_email = 'a@b.vn@c.vn' WHERE id = md5('qa90:profile')::uuid$$);
SELECT pg_temp.expect_ok('no invoice e-mail is allowed on the profile (checked when an invoice is issued)',
    $$UPDATE customer.customer_legal_profile SET invoice_email = NULL WHERE id = md5('qa90:profile')::uuid$$);

-- The bank account: all three or none ------------------------------------------------------------------------------
SELECT pg_temp.expect_ok('a bank account with its number, bank and holder',
    $$UPDATE customer.customer_legal_profile
         SET bank_account_number = '0123456789', bank_name = 'Vietcombank', bank_account_holder = 'CONG TY QA 90'
       WHERE id = md5('qa90:profile')::uuid$$);
SELECT pg_temp.expect_fail('a bank account without its holder is refused',
    $$UPDATE customer.customer_legal_profile SET bank_account_holder = NULL WHERE id = md5('qa90:profile')::uuid$$);
SELECT pg_temp.expect_fail('a bank account without its bank is refused',
    $$UPDATE customer.customer_legal_profile SET bank_name = '  ' WHERE id = md5('qa90:profile')::uuid$$);
SELECT pg_temp.expect_fail('a bank name without an account number is refused',
    $$UPDATE customer.customer_legal_profile SET bank_account_number = NULL WHERE id = md5('qa90:profile')::uuid$$);
SELECT pg_temp.expect_fail('an account number with punctuation is refused',
    $$UPDATE customer.customer_legal_profile SET bank_account_number = '0123-456' WHERE id = md5('qa90:profile')::uuid$$);
SELECT pg_temp.expect_ok('the bank account is taken off whole',
    $$UPDATE customer.customer_legal_profile
         SET bank_account_number = NULL, bank_name = NULL, bank_account_holder = NULL
       WHERE id = md5('qa90:profile')::uuid$$);

-- The change history -----------------------------------------------------------------------------------------------
SELECT pg_temp.expect_ok('a change of the invoice e-mail is kept',
    $$INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, new_value, changed_at)
      VALUES (gen_random_uuid(), md5('qa90:customer')::uuid, 'INVOICE_EMAIL', NULL, 'a@qa90.example.com', NOW())$$);
SELECT pg_temp.expect_ok('a change of the bank account is kept',
    $$INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, new_value, changed_at)
      VALUES (gen_random_uuid(), md5('qa90:customer')::uuid, 'BANK_ACCOUNT', NULL, '0123456789 | VCB | QA 90', NOW())$$);
SELECT pg_temp.expect_fail('a field nobody keeps history of is refused',
    $$INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, new_value, changed_at)
      VALUES (gen_random_uuid(), md5('qa90:customer')::uuid, 'PHONE', NULL, '0900000000', NOW())$$);

-- Grants -----------------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_true('Sales, the Sales Manager, the accountant and the administrator read and change legal profiles',
    (SELECT count(*) = 8 FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.resource = 'customer-legal-profiles'
        AND r.code IN ('SALES_STAFF', 'SALES_MANAGER', 'ACCOUNTANT', 'SYSTEM_ADMIN')));
SELECT pg_temp.expect_true('nobody else holds a legal-profile permission - a portal customer least of all',
    (SELECT count(*) = 0 FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.resource = 'customer-legal-profiles'
        AND r.code NOT IN ('SALES_STAFF', 'SALES_MANAGER', 'ACCOUNTANT', 'SYSTEM_ADMIN')));
SELECT pg_temp.expect_true('every profile of the demo seed took its contact e-mail as invoice e-mail',
    (SELECT count(*) = 0 FROM customer.customer_legal_profile p JOIN customer.customer c ON c.id = p.customer_id
      WHERE p.id <> md5('qa90:profile')::uuid AND p.invoice_email IS DISTINCT FROM lower(btrim(c.email))));

ROLLBACK;
