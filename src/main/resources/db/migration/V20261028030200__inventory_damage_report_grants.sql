-- =============================================================================
-- Permission resource inventory-damage-reports (SCRUM-475, BR-STK-003). Module: identity (grants) for inventory.
--
--   VIEW_PAGE, READ  the damage reports of my warehouses and their photos
--   CREATE           report damaged stock found on a shelf (with photos); picking reports through
--                    fulfillment-picking:UPDATE, the permission the picker already holds
--   APPROVE          decide what happens to damaged units: write off, return to the supplier, restore. Never
--                    the reporter's own report (four eyes, in the aggregate and the table)
--
-- Who (docs 00 roles, 03 §5 "report damage: Warehouse staff; write-off: Warehouse manager"):
--   WAREHOUSE_STAFF    find and report; see the reports
--   QC_STAFF           find and report (inspection); see the reports
--   WAREHOUSE_MANAGER  everything, including the decision
--   INVENTORY_PLANNER  see the reports (stock levels across warehouses)
--   SYSTEM_ADMIN       everything
--
-- Grants are cached per role version (ADR-0008): every role whose grants change is advanced in this migration.
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), r.resource || ':' || a.action, r.resource, a.action, 0, NOW(), 'flyway'
  FROM (VALUES ('inventory-damage-reports', 'VIEW_PAGE,READ,CREATE,APPROVE')) AS r(resource, actions)
 CROSS JOIN LATERAL unnest(string_to_array(r.actions, ',')) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
  FROM (VALUES
            ('WAREHOUSE_MANAGER', 'inventory-damage-reports', 'VIEW_PAGE,READ,CREATE,APPROVE'),
            ('WAREHOUSE_STAFF',   'inventory-damage-reports', 'VIEW_PAGE,READ,CREATE'),
            ('QC_STAFF',          'inventory-damage-reports', 'VIEW_PAGE,READ,CREATE'),
            ('INVENTORY_PLANNER', 'inventory-damage-reports', 'VIEW_PAGE,READ')
       ) AS g(role_code, resource, actions)
  JOIN identity.app_role role ON role.code = g.role_code
  JOIN identity.permission permission
    ON permission.resource = g.resource
   AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
  FROM identity.app_role role
  JOIN identity.permission permission ON permission.resource = 'inventory-damage-reports'
 WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE code IN ('WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF', 'QC_STAFF', 'INVENTORY_PLANNER', 'SYSTEM_ADMIN');
