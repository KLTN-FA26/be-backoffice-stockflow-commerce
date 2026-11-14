-- =============================================================================
-- A customer's legal profile holds what an e-invoice needs of the buyer, and what a refund is paid back to (WF-05 E1;
-- kltn-docs 18 §3, BR-12; 21 BR-06).
--
--   * customer.customer_legal_profile.invoice_email - where the customer's e-invoices are sent. Its default is the
--     customer's contact e-mail address (18 §3), so profiles already recorded take that address here; staff may change
--     it. Required before an invoice is issued (21 BR-06) - checked when the invoice is issued, not here: a profile
--     recorded for a contract is not refused for an address nobody asked for yet.
--   * bank_account_number / bank_name / bank_account_holder - the customer's bank account, optional and used only to
--     pay a refund back (18 §3). All three or none.
--   * customer.customer_change_history - a change of either is kept like a change of the registered address: before,
--     after, who, when (18 §6). A bank account is one value: "number | bank | holder".
--   * permission customer-legal-profiles (READ, UPDATE) - the profile's own grant. It was read and written under
--     customer-customers, which the accountant does not hold for changes and a portal customer does: Sales and the
--     accountant record the profile, the Sales Manager and the system administrator with them.
--
-- The company name and the tax code stay on customer.customer (ck_customer_tax_code: 10 digits, 10-3 for a dependent
-- unit, or the 12 digits of a household business), with their own history.
-- =============================================================================

ALTER TABLE customer.customer_legal_profile
    ADD COLUMN invoice_email       VARCHAR(320),
    ADD COLUMN bank_account_number VARCHAR(34),
    ADD COLUMN bank_name           VARCHAR(200),
    ADD COLUMN bank_account_holder VARCHAR(200),
    ADD CONSTRAINT ck_customer_legal_profile_invoice_email CHECK (
        invoice_email IS NULL OR invoice_email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    ADD CONSTRAINT ck_customer_legal_profile_bank CHECK (
        (bank_account_number IS NULL AND bank_name IS NULL AND bank_account_holder IS NULL)
        OR (COALESCE(bank_account_number, '') ~ '^[0-9A-Za-z]{4,34}$' AND btrim(COALESCE(bank_name, '')) <> ''
            AND btrim(COALESCE(bank_account_holder, '')) <> ''));

COMMENT ON COLUMN customer.customer_legal_profile.invoice_email IS
    'WF-05 E1 (18 BR-12): where e-invoices are sent; defaults to the contact e-mail; required before an invoice is issued.';
COMMENT ON COLUMN customer.customer_legal_profile.bank_account_number IS
    'WF-05 E1 (18 §3): the customer''s bank account, for refunds only; with bank_name and bank_account_holder or not at all.';

-- 18 §3: the default is the contact address. One that would not pass the check stays empty for staff to enter.
UPDATE customer.customer_legal_profile p
   SET invoice_email = lower(btrim(c.email))
  FROM customer.customer c
 WHERE c.id = p.customer_id
   AND lower(btrim(c.email)) ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$';

ALTER TABLE customer.customer_change_history
    DROP CONSTRAINT ck_customer_change_history_field,
    ADD CONSTRAINT ck_customer_change_history_field CHECK (field IN ('FULL_NAME', 'TAX_CODE', 'REGISTERED_ADDRESS',
                                                                     'REPRESENTATIVE', 'REPRESENTATIVE_TITLE',
                                                                     'INVOICE_EMAIL', 'BANK_ACCOUNT'));

COMMENT ON TABLE customer.customer_change_history IS
    'Every change of a customer''s company name, tax code and legal profile, its invoice e-mail and bank account '
    'included: before, after, who, when (decision 2026-10-17; 18 §6; WF-05 E1).';

-- Permissions ------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'customer-legal-profiles:' || a.action, 'customer-legal-profiles', a.action, 0, NOW(), 'flyway'
  FROM unnest(ARRAY['READ', 'UPDATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, actions) AS (
    VALUES
        -- 18 §3: Sales records the profile before a contract; 21 §2: Sales adds a missing invoice e-mail.
        ('SALES_STAFF',   'READ,UPDATE'),
        ('SALES_MANAGER', 'READ,UPDATE'),
        -- 21 §4.2: the accountant, whose invoice is blocked by it, reads and corrects it.
        ('ACCOUNTANT',    'READ,UPDATE'),
        ('SYSTEM_ADMIN',  'READ,UPDATE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission
        ON permission.resource = 'customer-legal-profiles'
       AND permission.action = ANY (string_to_array(wanted.actions, ','))
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
