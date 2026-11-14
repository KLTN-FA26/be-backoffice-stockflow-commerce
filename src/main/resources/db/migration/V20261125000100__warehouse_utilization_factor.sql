-- =============================================================================
-- BE1 (kltn-docs 06 §4.6, BR-20, BR-21; 17 BR-25; open questions C40-C43): the warehouse capacity board.
--
-- 1. warehouse.warehouse.utilization_factor - the share of the bins' computed volume that can really be filled, once
--    working space and the gaps between cartons are taken out (06 §4.6 step 2). One factor for the whole warehouse,
--    100% by default, in (0%, 100%] (C43, decided 2026-11-05), editable by the warehouse manager; used by the capacity
--    board only - putaway still checks a bin against its full computed volume (05 BR-03). Stored as a fraction:
--    1 = 100%, 0.85 = 85%. Every warehouse that exists takes the default, so nothing it holds or shows changes until
--    its manager sets it. The aggregate states the same bound (Warehouse.changeUtilizationFactor).
--
-- 2. The board's own permission, warehouse-capacity:VIEW_PAGE and :READ (06 §2): the warehouse manager follows it for
--    their warehouses, Sales read it when choosing a shipping warehouse, the Production Coordinator when drawing up a
--    production order and the Sales Manager when approving one (17 §4.2 step 2, §4.3 steps 6-7). Its own code rather
--    than an existing one: none of those roles shares a permission that means "reads every warehouse's free space",
--    and warehouse-warehouses:READ is held by warehouse staff too, who have no use for it. The endpoints narrow a
--    caller limited to warehouses (scope WAREHOUSE) to theirs.
--
-- Nothing is written to any table but warehouse.warehouse and the two permission ones. Every role that gained a grant
-- advances app_role.version in the same file (ADR-0008).
-- =============================================================================

ALTER TABLE warehouse.warehouse
    ADD COLUMN utilization_factor numeric(5,4) NOT NULL DEFAULT 1;

ALTER TABLE warehouse.warehouse
    ADD CONSTRAINT ck_warehouse_utilization_factor CHECK (utilization_factor > 0 AND utilization_factor <= 1);

COMMENT ON COLUMN warehouse.warehouse.utilization_factor IS
    'Share of the bins'' computed volume that can be filled, (0, 1]; capacity board only (kltn-docs 06 §4.6, C43)';

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'warehouse-capacity:VIEW_PAGE', 'warehouse-capacity', 'VIEW_PAGE',
        'Open the warehouse capacity board (kltn-docs 06 §4.6)', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'warehouse-capacity:READ', 'warehouse-capacity', 'READ',
        'Read the warehouse capacity board: capacity, occupied, incoming, projected free space (kltn-docs 06 §4.6, BR-20)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, board.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission board ON board.code IN ('warehouse-capacity:VIEW_PAGE', 'warehouse-capacity:READ')
     WHERE role.code IN ('WAREHOUSE_MANAGER', 'SALES_STAFF', 'SALES_MANAGER', 'PRODUCTION_COORDINATOR')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);

-- System Admin holds every permission row (00 §2.5.3; SystemAdminRoleIntegrationTest).
WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
     CROSS JOIN identity.permission permission
     WHERE role.code = 'SYSTEM_ADMIN'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
