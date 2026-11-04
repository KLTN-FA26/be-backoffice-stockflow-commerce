-- =============================================================================
-- inventory-cycle-counts:VIEW_PAGE, READ for WAREHOUSE_STAFF - the counter sees the counts to make.
-- Module: identity (grants) for inventory
--
-- docs 04c F-CNT: the warehouse manager plans a count and the warehouse staff count it (step 03, on mobile);
-- X-SHORT step 03 opens such a count by itself after a short pick. V20260903000100 granted the resource to
-- WAREHOUSE_MANAGER and INVENTORY_PLANNER only, so GET /api/v1/inventory/cycle-counts answered 403 to the
-- people the blind count is for. They get the screen and the list, blind (CycleCountService hides the book
-- quantity and the note); entering counted quantities is UPDATE and comes with the cycle-count story.
-- =============================================================================

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
  FROM identity.app_role role
  JOIN identity.permission permission
    ON permission.code IN ('inventory-cycle-counts:VIEW_PAGE', 'inventory-cycle-counts:READ')
 WHERE role.code = 'WAREHOUSE_STAFF'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Grants are cached per role version (ADR-0008): advance the role whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE code = 'WAREHOUSE_STAFF';
