-- =============================================================================
-- The inventory planner reads stock adjustments (SCRUM-439 review, decided by Tú on 14/10/2026).
--
-- INVENTORY_PLANNER holds inventory-stock-items:UPDATE, so it can request and withdraw an adjustment
-- (POST /inventory/adjustments, .../withdrawal), but it never held inventory-stock-adjustments:READ:
-- it could not open the adjustment it had just requested. The team kept the right to request; this
-- adds only the reading half (VIEW_PAGE, READ). Approving stays with the warehouse manager
-- (inventory-stock-adjustments:APPROVE is not granted here).
--
-- Grants are cached per role version (ADR-0008): the role is advanced only if it gained rows.
-- =============================================================================

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
    FROM identity.app_role role
    JOIN identity.permission permission
      ON permission.resource = 'inventory-stock-adjustments'
     AND permission.action IN ('VIEW_PAGE', 'READ')
    WHERE role.code = 'INVENTORY_PLANNER'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE id IN (SELECT role_id FROM granted);
