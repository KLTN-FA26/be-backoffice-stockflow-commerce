-- =============================================================================
-- B2B customer onboarding (SCRUM-429 / SCRUM-450).
-- Docs: kltn-docs 18 §4.1 (Sales creates, the contact activates), §5 (lifecycle), BR-01 (the tax code
-- identifies a business), BR-01a (only Sales creates; only ACTIVE sees prices, quotes and orders).
--
-- customer.customer
--   tax_code            the business's tax code, normalised: 10 digits for an enterprise, 10-3 for a
--                       dependent unit (Law 38/2019/QH14 art. 30(2), Circular 86/2024/TT-BTC art. 5 as
--                       quoted by secondary sources). NULL for every customer created before this
--                       migration: no code is invented for them; Sales adds the real one.
--   PENDING_ACTIVATION  Sales created the customer, its contact has not activated the portal account.
--                       Lifecycle mapping of kltn-docs 18 §5 (a decision of this change, to be confirmed
--                       by the business owner): Pending Activation = PENDING_ACTIVATION, Active =
--                       ACTIVE, Suspended = BLOCKED, Closed = INACTIVE.
--   uk_customer_tax_code_live  BR-01 forbids two Active customers with one tax code, and §4.1 checks
--                       duplicates when Sales creates the (pending) customer. Both live states are
--                       covered so two pending customers cannot race to the same code; INACTIVE
--                       (closed) and BLOCKED rows are not, as the documents do not cover them.
--
-- identity.app_user.password_hash  now NULL for an account that has no password yet - the contact of a
--                       pending customer. Nothing can sign in to it; its only credential is the open
--                       invitation.
--
-- identity.portal_invitation  one row per invitation e-mail. Only the SHA-256 digest of the link's
--                       token is stored. At most one open (neither consumed nor revoked) invitation
--                       per account: a resend revokes the open one, an activation consumes it.
--                       delivery_status is the outcome of the after-commit e-mail (NULL = none recorded).
--
-- Carry-over: the SCRUM-429 branch kept an open invitation as '{portal-invitation}<issued>:<expires>:<digest>'
-- in password_hash. Such rows move here with their digest and dates, so a link already e-mailed keeps
-- working; their customers become PENDING_ACTIVATION. Every other INACTIVE customer stays INACTIVE.
--
-- Sign-in: the contact accounts (CUSTOMER role only) of BLOCKED and INACTIVE customers are disabled and
-- their live sessions ended, matching what the application now does on every status change.
--
-- customer-customers:CREATE  Sales and the e-commerce admin onboard customers (SYSTEM_ADMIN gets every
--                       permission). Roles whose grants change advance app_role.version.
-- =============================================================================

-- ---- customer.customer ----------------------------------------------------------
ALTER TABLE customer.customer
    ADD COLUMN tax_code VARCHAR(14),
    ADD CONSTRAINT ck_customer_tax_code CHECK (tax_code IS NULL OR tax_code ~ '^[0-9]{10}(-[0-9]{3})?$'),
    DROP CONSTRAINT ck_customer_status,
    ADD CONSTRAINT ck_customer_status CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'INACTIVE', 'BLOCKED'));

CREATE UNIQUE INDEX uk_customer_tax_code_live ON customer.customer (tax_code)
    WHERE tax_code IS NOT NULL AND status IN ('PENDING_ACTIVATION', 'ACTIVE');

COMMENT ON COLUMN customer.customer.tax_code IS
    'Normalised tax code (10 digits or 10-3). NULL for customers created before SCRUM-450.';

-- ---- identity.portal_invitation ---------------------------------------------------
CREATE TABLE identity.portal_invitation
(
    id                    UUID        NOT NULL,
    user_id               UUID        NOT NULL,
    token_digest          CHAR(64)    NOT NULL,
    issued_at             TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    consumed_at           TIMESTAMPTZ,
    revoked_at            TIMESTAMPTZ,
    -- Null for an invitation carried over from before this table, or issued by the system.
    issued_by             UUID,
    delivery_status       VARCHAR(16),
    delivery_attempted_at TIMESTAMPTZ,

    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(100),
    last_modified_at      TIMESTAMPTZ,
    last_modified_by      VARCHAR(100),

    CONSTRAINT pk_portal_invitation PRIMARY KEY (id),
    CONSTRAINT uk_portal_invitation_digest UNIQUE (token_digest),
    -- Deferred: the account and its first invitation are written in one transaction, and with
    -- order_inserts which row Hibernate flushes first is not something to depend on (V20260928004000).
    CONSTRAINT fk_portal_invitation_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_portal_invitation_issued_by FOREIGN KEY (issued_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_portal_invitation_digest CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_portal_invitation_lifetime CHECK (expires_at > issued_at),
    -- An invitation ends one way: redeemed or replaced, never both.
    CONSTRAINT ck_portal_invitation_one_end CHECK (consumed_at IS NULL OR revoked_at IS NULL),
    -- Redeemed while it was valid; the expiry instant itself is already too late.
    CONSTRAINT ck_portal_invitation_consumed CHECK (consumed_at IS NULL
        OR (consumed_at >= issued_at AND consumed_at < expires_at)),
    CONSTRAINT ck_portal_invitation_revoked CHECK (revoked_at IS NULL OR revoked_at >= issued_at),
    CONSTRAINT ck_portal_invitation_delivery CHECK (
        (delivery_status IS NULL AND delivery_attempted_at IS NULL)
     OR (delivery_status IN ('SENT', 'FAILED') AND delivery_attempted_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_portal_invitation_open ON identity.portal_invitation (user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
CREATE INDEX ix_portal_invitation_user ON identity.portal_invitation (user_id, issued_at);

COMMENT ON TABLE identity.portal_invitation IS
    'Portal activation invitations of Sales-created customer accounts. Stores the token digest, never the token.';

-- ---- carry over the branch-era invitations ----------------------------------------
ALTER TABLE identity.app_user ALTER COLUMN password_hash DROP NOT NULL;

INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at, created_at, created_by)
SELECT gen_random_uuid(), u.id, parts[3],
       to_timestamp(parts[1]::BIGINT), to_timestamp(parts[2]::BIGINT), NOW(), 'flyway'
FROM identity.app_user u
CROSS JOIN LATERAL regexp_match(u.password_hash, '^\{portal-invitation\}(\d+):(\d+):([0-9a-f]{64})$') AS parts
WHERE parts IS NOT NULL;

UPDATE customer.customer c
SET status = 'PENDING_ACTIVATION', last_modified_at = NOW(), last_modified_by = 'flyway'
FROM identity.app_user u
WHERE c.user_id = u.id AND c.status = 'INACTIVE' AND u.password_hash LIKE '{portal-invitation}%';

UPDATE identity.app_user
SET password_hash = NULL, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE password_hash LIKE '{portal-invitation}%';

-- ---- sign-in follows the customer's status ------------------------------------------
-- kltn-docs 18 §5: a Suspended (BLOCKED) customer may not sign in; a Closed (INACTIVE) one closed its account.
-- From now on the application disables the contact's account when staff block or close a customer
-- and enables it again when they reactivate it; this brings the rows that predate that rule in line.
-- Only accounts holding CUSTOMER and nothing else: a staff login is never taken away through a
-- customer profile. Live sessions of those accounts end, as a block would end them.
CREATE TEMPORARY TABLE barred_customer_account ON COMMIT DROP AS
SELECT u.id
FROM identity.app_user u
JOIN customer.customer c ON c.user_id = u.id
WHERE c.status IN ('BLOCKED', 'INACTIVE')
  AND u.status = 'ACTIVE'
  AND EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
              WHERE ur.user_id = u.id AND r.code = 'CUSTOMER')
  AND NOT EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                  WHERE ur.user_id = u.id AND r.code <> 'CUSTOMER');

UPDATE identity.app_user
SET status = 'DISABLED', last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE id IN (SELECT id FROM barred_customer_account);

UPDATE identity.user_session
SET revoked_at = NOW(), revoked_reason = 'ACCOUNT_DISABLED', last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE revoked_at IS NULL AND user_id IN (SELECT id FROM barred_customer_account);

-- A pending customer always has the account its contact will activate.
ALTER TABLE customer.customer
    ADD CONSTRAINT ck_customer_pending_has_account CHECK (status <> 'PENDING_ACTIVATION' OR user_id IS NOT NULL);

-- ---- permissions ------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'customer-customers:CREATE', 'customer-customers', 'CREATE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.code = 'customer-customers:CREATE'
WHERE role.code IN ('SALES_STAFF', 'ECOMMERCE_ADMIN', 'SYSTEM_ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('SALES_STAFF', 'ECOMMERCE_ADMIN', 'SYSTEM_ADMIN');
