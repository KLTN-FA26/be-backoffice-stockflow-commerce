-- =============================================================================
-- Customer groups (customer.segment) managed from the back office (user decision P7, 2026-10-12).
--
-- E-commerce Admin and System Admin create and rename groups and put customers in them; Sales and the
-- Order Coordinator read them. A resource of its own, not customer-customers: Sales may UPDATE a
-- customer's profile, and must not thereby move the customer to another group's price.
-- Additive only. The two CHECKs mirror CustomerSegment and are NOT VALID, so a row written before this
-- migration is never rewritten or refused retroactively; every new or changed row is checked.
-- =============================================================================

ALTER TABLE customer.segment ADD CONSTRAINT ck_segment_code
    CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$') NOT VALID;
ALTER TABLE customer.segment ADD CONSTRAINT ck_segment_name
    CHECK (btrim(name) <> '') NOT VALID;

-- ---- permissions ------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'customer-segments:' || a.action, 'customer-segments', a.action, 0, NOW(), 'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE', 'UPDATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('ECOMMERCE_ADMIN',   'VIEW_PAGE,READ,CREATE,UPDATE'),
          ('SALES_STAFF',       'VIEW_PAGE,READ'),
          ('ORDER_COORDINATOR', 'READ')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'customer-segments'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
  AND permission.resource = 'customer-segments'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('ECOMMERCE_ADMIN', 'SALES_STAFF', 'ORDER_COORDINATOR', 'SYSTEM_ADMIN');
