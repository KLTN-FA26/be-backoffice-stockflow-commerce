-- The E-commerce Admin is the Sales Manager now (kltn-docs 00 §2, decided 2026-11-05).
--
-- Same role under a new name: its id, grants and data scope stay, only the code, name and description change. The
-- code is what the application knows a system role by (common/security/Role.java) and what every token issued before
-- this migration still carries, which decides the order below:
--
--   1. Stop if a custom role already holds the code SALES_MANAGER: codes are unique, and renaming over a role an
--      administrator made would be refused anyway - better with a sentence that says which one.
--   2. Rename, and advance app_role.version. Permissions are resolved per request from a cache keyed by role and
--      version (ADR-0008); a new version means nothing cached under the old one is ever served for this role again.
--   3. End the live sessions of everyone holding the role (reason ROLE_CHANGED, as the service does when it changes a
--      user's roles). Their tokens name a code that no longer exists, and a token whose role resolves to nothing would
--      leave them signed in without a single permission. Signing in again issues a token with the new code.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM identity.app_role WHERE code = 'SALES_MANAGER') THEN
        RAISE EXCEPTION 'identity.app_role already has a role coded SALES_MANAGER: rename or remove it, then migrate';
    END IF;
END $$;

UPDATE identity.app_role
SET code             = 'SALES_MANAGER',
    name             = 'Sales Manager',
    description      = 'Product data and approval, internal prices per customer, print setup, review of the '
                           || 'customers'' designs, production order approval, staff accounts',
    version          = version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE code = 'ECOMMERCE_ADMIN';

UPDATE identity.user_session
SET revoked_at       = NOW(),
    revoked_reason   = 'ROLE_CHANGED',
    version          = version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE revoked_at IS NULL
  AND expires_at > NOW()
  AND user_id IN (SELECT ur.user_id
                  FROM identity.user_role ur
                           JOIN identity.app_role r ON r.id = ur.role_id
                  WHERE r.code = 'SALES_MANAGER');
