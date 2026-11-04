-- A real platform administrator: SYSTEM_ADMIN holds every permission in the catalog.
--
-- The seed (V20260903000100, note 5) had no admin role, so user / role / permission management
-- was parked on ECOMMERCE_ADMIN "until a real admin role is added". This is that role. ECOMMERCE_ADMIN
-- keeps those grants for now so nobody who administers today loses access on deploy; taking them
-- away is a separate change, made once someone actually holds SYSTEM_ADMIN.
--
-- "Every permission" includes the sensitive actions (DELETE, APPROVE, EXPORT) that the seed only
-- ever grants one statement at a time. That is the point of this role, and it is why its grants
-- are not editable from the permission screen (IdentityServiceImpl.isEditable): one wrong untick
-- would quietly turn the all-powerful role into an ordinary one.
--
-- Any later migration that adds rows to identity.permission must grant them to SYSTEM_ADMIN in the
-- same file. SystemAdminRoleIntegrationTest fails the build when it does not.

INSERT INTO identity.app_role (id, code, name, description, version, created_at, created_by)
VALUES ('a0000001-0000-4000-8000-00000000000b', 'SYSTEM_ADMIN', 'System Admin',
        'Every permission: users, roles, permissions and all modules', 0, NOW(), 'flyway');

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- No version bump: the role is new, so no cached grant set exists under any version yet (ADR-0008).
