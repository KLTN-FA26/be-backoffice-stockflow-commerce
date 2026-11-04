-- Somebody must be able to approve a purchase order.
--
-- The seed granted procurement-purchase-orders:APPROVE to no role, so with security on every PO
-- stopped at DRAFT: PROCUREMENT_STAFF creates and sends, but approval needs a different holder
-- (docs 02: the approver is a role-registry right, not the buyer). WAREHOUSE_MANAGER is the
-- approver the backoffice frontend already assumes, and the role that receives the goods.
-- It also needs VIEW_PAGE and READ: approving an order you cannot open is not a workflow.
--
-- Administrators can change this at runtime from the permission screen (ADR-0008); this is only
-- the default a fresh database starts with.

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission
  ON permission.resource = 'procurement-purchase-orders'
 AND permission.action IN ('VIEW_PAGE', 'READ', 'APPROVE')
WHERE role.code = 'WAREHOUSE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Grants are cached by role version (ADR-0008): a grant change must advance the role's version in
-- the same transaction, or holders keep the old grants until the cached set expires.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code = 'WAREHOUSE_MANAGER';
