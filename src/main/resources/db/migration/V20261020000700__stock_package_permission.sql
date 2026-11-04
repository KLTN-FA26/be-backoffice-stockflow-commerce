-- =============================================================================
-- Permission resource for stock packages: looking one up by its label, printing and reprinting
-- labels, and the manager's corrections.
-- Module: identity   Issue: GitHub #125, Jira SCRUM-473
--
-- Seeded ahead of the controller, as V20260928006000 does: the code below is the contract, declare
-- exactly it in @PermissionResource(code = ...). Receiving, putaway, picking and counting cartons stay
-- under their own resources (procurement-goods-receipts, warehouse-putaway-tasks,
-- fulfillment-pick-lists, inventory-cycle-counts); this one is the stock package itself.
--
-- UPDATE is the manager's. Marking a stock package LOST empties it, which writes stock off, and
-- warehouse staff do not write stock off without approval (they only read inventory-stock-adjustments,
-- V20261013000200): a lost carton is booked through an approved count or adjustment. Staff look up,
-- print and reprint labels (CREATE).
--
-- Every new permission is granted to SYSTEM_ADMIN in the same file (V20260929000200;
-- SystemAdminRoleIntegrationTest fails the build otherwise).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'inventory-stock-packages:' || a.action, 'inventory-stock-packages', a.action, 0, NOW(),
       'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE', 'UPDATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

-- Grants. "all" = every action of the resource; otherwise the listed actions only.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('WAREHOUSE_MANAGER', 'all'),
          ('WAREHOUSE_STAFF',   'VIEW_PAGE,READ,CREATE'),
          ('INVENTORY_PLANNER', 'VIEW_PAGE,READ'),
          ('QC_STAFF',          'VIEW_PAGE,READ')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'inventory-stock-packages'
 AND (g.actions = 'all' OR permission.action = ANY (string_to_array(g.actions, ',')))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ADR-0008: role-keyed permission caches must change generation in this same transaction.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF', 'INVENTORY_PLANNER', 'QC_STAFF', 'SYSTEM_ADMIN');
