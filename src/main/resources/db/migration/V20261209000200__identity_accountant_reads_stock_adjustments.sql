-- =============================================================================
-- The Accountant reads stock adjustments (permission review 2026-11-20, A3; kltn-docs 20 §3).
--
-- 20 §3 lists the stock adjustment (Pending Approval -> Posted / Rejected / Withdrawn) as read by the warehouse manager
-- and the Accountant: an adjustment writes stock up or down, and the Accountant reconciles it. Until now only the
-- warehouse roles held inventory-stock-adjustments:READ, so GET /api/v1/inventory/adjustments answered the Accountant
-- 403. Granted: VIEW_PAGE (the screen) and READ (list and detail). Not APPROVE: deciding an adjustment stays with the
-- warehouse manager (20 BR-05). The Accountant's scope is ALL, so it reads the adjustments of every warehouse.
--
-- The role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
     CROSS JOIN identity.permission permission
     WHERE role.code = 'ACCOUNTANT'
       AND permission.code IN ('inventory-stock-adjustments:VIEW_PAGE', 'inventory-stock-adjustments:READ')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
