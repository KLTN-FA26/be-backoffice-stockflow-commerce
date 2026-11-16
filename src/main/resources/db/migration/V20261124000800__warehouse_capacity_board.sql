-- =============================================================================
-- SCRUM-669 (story SCRUM-626, team plan G4-05): the warehouse capacity board (kltn-docs 06 §4.6, BR-19, BR-20).
--
-- 1. A warehouse's utilisation factor: the share of its bins' computed volume that cartons really fill (aisles inside
--    a bin, gaps between cartons). One per warehouse, default 100%, in (0, 100%] (06 §4.6 step 2, C43). It scales the
--    board only; putaway still checks a bin's full computed volume. Existing warehouses take the default.
-- 2. The board's read permission, warehouse-capacity:READ: the warehouse manager prepares room by it; Sales, the Sales
--    Manager and the production coordinator are warned from it when they choose a shipping warehouse or draw up and
--    approve a production order (06 §2, BR-21; SCRUM-673). System Admin holds every permission.
--
-- Every role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

ALTER TABLE warehouse.warehouse
    ADD COLUMN utilization_factor numeric(5,4) NOT NULL DEFAULT 1;

ALTER TABLE warehouse.warehouse
    ADD CONSTRAINT ck_warehouse_utilization_factor CHECK (utilization_factor > 0 AND utilization_factor <= 1);

COMMENT ON COLUMN warehouse.warehouse.utilization_factor IS
    'kltn-docs 06 §4.6 step 2: the share of the bins'' computed volume cartons really fill, in (0, 1]; scales the capacity board only, never the putaway check.';

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'warehouse-capacity:READ', 'warehouse-capacity', 'READ',
        'Read a warehouse''s capacity board: bin capacity, occupied, incoming, expected free (kltn-docs 06 §4.6)',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'warehouse-capacity:VIEW_PAGE', 'warehouse-capacity', 'VIEW_PAGE',
        'Open the capacity board page (kltn-docs 06 §4.6)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission
        ON permission.code IN ('warehouse-capacity:READ', 'warehouse-capacity:VIEW_PAGE')
     WHERE role.code IN ('WAREHOUSE_MANAGER', 'SALES_STAFF', 'SALES_MANAGER', 'PRODUCTION_COORDINATOR', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
