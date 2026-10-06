-- SCRUM-46/47: link profiles to identity accounts and adopt Vietnam's two-level address model.
ALTER TABLE identity.app_user
    ALTER COLUMN username TYPE VARCHAR(320);

CREATE UNIQUE INDEX uk_app_user_email_normalized ON identity.app_user (lower(email));
CREATE UNIQUE INDEX uk_app_user_username_normalized ON identity.app_user (lower(username));

ALTER TABLE customer.customer
    ADD COLUMN user_id UUID;

CREATE UNIQUE INDEX uk_customer_user
    ON customer.customer (user_id)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX uk_customer_email_normalized ON customer.customer (lower(email));

COMMENT ON COLUMN customer.customer.user_id IS
    'Logical reference to identity.app_user. No cross-schema FK by ADR-0005.';

-- Preserve legacy rows before removing the obsolete district/city shape. New writes use official
-- ward/province codes; deterministic LEGACY codes keep old rows readable until users re-save them.
ALTER TABLE customer.address
    ADD COLUMN ward_code VARCHAR(20),
    ADD COLUMN province_code VARCHAR(20);

UPDATE customer.address
SET province = COALESCE(NULLIF(province, ''), city),
    ward_code = CASE WHEN ward IS NULL OR btrim(ward) = '' THEN NULL
                     ELSE 'LEGACY-' || substring(md5(ward) from 1 for 12) END,
    province_code = CASE WHEN COALESCE(NULLIF(province, ''), city) IS NULL THEN NULL
                         ELSE 'LEGACY-' || substring(md5(COALESCE(NULLIF(province, ''), city)) from 1 for 12) END;

ALTER TABLE customer.address
    DROP COLUMN district,
    DROP COLUMN city;

-- NOT VALID preserves incomplete legacy rows, while PostgreSQL still enforces these checks for
-- every insert/update made by the new API. Re-saving a legacy row upgrades it to official codes.
ALTER TABLE customer.address
    ADD CONSTRAINT ck_address_recipient_required CHECK (recipient_name IS NOT NULL AND btrim(recipient_name) <> '') NOT VALID,
    ADD CONSTRAINT ck_address_phone_required CHECK (phone IS NOT NULL AND btrim(phone) <> '') NOT VALID,
    ADD CONSTRAINT ck_address_ward_required CHECK (ward IS NOT NULL AND btrim(ward) <> '') NOT VALID,
    ADD CONSTRAINT ck_address_ward_code_required CHECK (ward_code IS NOT NULL AND btrim(ward_code) <> '') NOT VALID,
    ADD CONSTRAINT ck_address_province_required CHECK (province IS NOT NULL AND btrim(province) <> '') NOT VALID,
    ADD CONSTRAINT ck_address_province_code_required CHECK (province_code IS NOT NULL AND btrim(province_code) <> '') NOT VALID,
    ADD CONSTRAINT ck_address_country_vn CHECK (country = 'VN');

CREATE UNIQUE INDEX uk_address_one_default_per_type
    ON customer.address (customer_id, type)
    WHERE is_default = TRUE;

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), resource || ':' || action, resource, action, 0, NOW(), 'flyway'
FROM (VALUES
    ('customer-customers', 'VIEW_PAGE'), ('customer-customers', 'READ'),
    ('customer-customers', 'UPDATE'), ('customer-customers', 'APPROVE'),
    ('customer-addresses', 'VIEW_PAGE'), ('customer-addresses', 'READ'),
    ('customer-addresses', 'CREATE'), ('customer-addresses', 'UPDATE'),
    ('customer-addresses', 'DELETE')
) AS declared(resource, action)
ON CONFLICT (code) DO NOTHING;

-- Customers can manage only the profile linked to their JWT subject; service-level ownership
-- checks remain mandatory because the current JWT scope claim is intentionally coarse.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission
  ON permission.code IN (
      'customer-customers:VIEW_PAGE', 'customer-customers:READ', 'customer-customers:UPDATE',
      'customer-addresses:VIEW_PAGE', 'customer-addresses:READ', 'customer-addresses:CREATE',
      'customer-addresses:UPDATE', 'customer-addresses:DELETE')
WHERE role.code = 'CUSTOMER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.resource IN ('customer-customers', 'customer-addresses')
WHERE role.code IN ('ECOMMERCE_ADMIN', 'SALES_STAFF')
ON CONFLICT (role_id, permission_id) DO NOTHING;
