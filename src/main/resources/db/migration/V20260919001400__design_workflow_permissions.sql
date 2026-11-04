-- Align the code-declared workflow resources with the identity permission catalog.
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES
    (gen_random_uuid(), 'design-designs:APPROVE', 'design-designs', 'APPROVE', 0, NOW(), 'flyway'),
    (gen_random_uuid(), 'design-administration:CREATE', 'design-administration', 'CREATE', 0, NOW(), 'flyway'),
    (gen_random_uuid(), 'design-administration:UPDATE', 'design-administration', 'UPDATE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

-- QC may approve only when the service-level assignment/scope check also passes.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission
  ON permission.code IN ('design-designs:READ', 'design-designs:APPROVE')
WHERE role.code = 'QC_STAFF'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Assisted draft creation and staff assignment are privileged administration operations.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission
  ON permission.resource = 'design-administration'
WHERE role.code = 'ECOMMERCE_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
