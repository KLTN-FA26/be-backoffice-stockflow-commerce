-- =============================================================================
-- Identity: staff user management and custom roles (SCRUM-453: 454, 455, 456).
--
-- Until now a staff account could only be created with deploy/scripts/create-user.sh (SQL), the
-- role set was the closed Role enum, and nothing stopped a holder of identity-users:UPDATE from
-- giving themselves SYSTEM_ADMIN. This adds what the API needs to manage both from the back office.
--
-- app_user
--   must_change_password  set by an administrator's password reset; cleared when the user changes it
--   failed_login_count    consecutive wrong passwords since the last good one
--   locked_until          a temporary lock after too many wrong passwords; it ends on its own.
--                         status LOCKED stays the administrator's lock, which only unlock ends.
--
-- app_role
--   is_system             the roles the code knows by name (common/security/Role.java). They cannot
--                         be renamed or deleted; administrators may add others (decision 2026-10-10,
--                         which supersedes "do not invent new roles" in SCRUM-19).
--   code format           upper-case like the system roles, so a custom code never looks like a
--                         permission authority (those contain ':').
--
-- user_session           three new reasons a session ends: the administrator locked or disabled
--                         the account, or reset its password.
--
-- identity-roles:DELETE  deleting a custom role. Sensitive action: granted to SYSTEM_ADMIN only.
-- =============================================================================

ALTER TABLE identity.app_user
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN failed_login_count   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until         TIMESTAMPTZ,
    ADD CONSTRAINT ck_app_user_failed_login_count CHECK (failed_login_count >= 0);

ALTER TABLE identity.app_role
    ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE identity.app_role
SET is_system = TRUE
WHERE code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'INVENTORY_PLANNER', 'QC_STAFF', 'CUSTOMER',
               'SALES_STAFF', 'ORDER_COORDINATOR', 'ECOMMERCE_ADMIN', 'PROCUREMENT_STAFF', 'ACCOUNTANT',
               'PRODUCTION_STAFF', 'SYSTEM_ADMIN');

ALTER TABLE identity.app_role
    ADD CONSTRAINT ck_app_role_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{1,63}$');

ALTER TABLE identity.user_session
    DROP CONSTRAINT ck_user_session_reason,
    ADD CONSTRAINT ck_user_session_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('LOGOUT', 'LOGOUT_OTHERS', 'PASSWORD_CHANGED', 'ROLE_CHANGED',
         'ACCOUNT_LOCKED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET'));

-- Lists of users per role, and the "is this the last active admin" check.
CREATE INDEX IF NOT EXISTS ix_user_role_role ON identity.user_role (role_id);

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'identity-roles:DELETE', 'identity-roles', 'DELETE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code = 'SYSTEM_ADMIN';
