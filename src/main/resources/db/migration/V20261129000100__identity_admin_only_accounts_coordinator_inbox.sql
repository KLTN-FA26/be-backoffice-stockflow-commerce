-- =============================================================================
-- Two seeded grants that disagreed with the business documents.
--
-- 1. Staff accounts, roles and permissions are the System Admin's alone (kltn-docs 00 §2.5.3 item 7, decided
--    2026-11-07 to match the capstone registration). The Sales Manager - the former E-commerce Admin - still held
--    identity-users (VIEW_PAGE, READ, CREATE, UPDATE), identity-roles (VIEW_PAGE, READ, CREATE, UPDATE) and
--    identity-rbac (VIEW_PAGE, READ, APPROVE). All are revoked. These resources guard only the user, role and
--    permission screens (IdentityController, UserManagementController); no other feature reads them, and the
--    warehouse task-assignee picker goes through /fulfillment/assignees. SYSTEM_ADMIN keeps every permission, so
--    the RBAC lockout guard (IdentityServiceImpl, identity-rbac:APPROVE) is unaffected.
--
-- 2. The Production Coordinator (added by V20261108002600) never got notification-notifications, so its own inbox
--    (GET /api/v1/notifications/me, unread count, mark read) answered 403 although delay alerts and production order
--    events are addressed to it. It gets VIEW_PAGE, READ and UPDATE, as every other role holds.
--
-- Every role whose grants change advances app_role.version in the same statement (ADR-0008): moving the version is
-- what invalidates the cached authorisation of the role's holders.
-- =============================================================================

WITH revoked AS (
    DELETE FROM identity.role_permission rp
     USING identity.app_role role, identity.permission permission
     WHERE rp.role_id = role.id
       AND rp.permission_id = permission.id
       AND role.code = 'SALES_MANAGER'
       AND permission.resource IN ('identity-users', 'identity-roles', 'identity-rbac')
    RETURNING rp.role_id
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission
        ON permission.resource = 'notification-notifications'
       AND permission.action IN ('VIEW_PAGE', 'READ', 'UPDATE')
     WHERE role.code = 'PRODUCTION_COORDINATOR'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM revoked UNION SELECT role_id FROM granted);

-- The role's description no longer names staff accounts.
UPDATE identity.app_role
   SET description = 'Product data and approval, internal prices per customer, print setup, review of the customers'' '
                     || 'designs, production order approval',
       last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE code = 'SALES_MANAGER';
