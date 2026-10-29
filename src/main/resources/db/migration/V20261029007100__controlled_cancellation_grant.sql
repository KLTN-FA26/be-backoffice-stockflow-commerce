-- =============================================================================
-- fulfillment-controlled-cancellations:UPDATE for WAREHOUSE_MANAGER and SYSTEM_ADMIN only (SCRUM-474 item 1, owner
-- decision UNLOAD-DESIGN 29/10/2026 points 5-6; kltn-docs 09 BR-14).
-- Module: identity (grants) for fulfillment
--
-- One permission covers what only a warehouse manager may do after loading started: call a load plan, or one of its
-- vehicles, off under control (POST .../controlled-cancellation) and confirm a parcel off the vehicle without a scan when
-- its label is damaged (POST .../unload-overrides). The code checks the permission, never a role. Scanning a parcel off
-- stays the dispatcher's loading work (fulfillment-dispatch:UPDATE).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'fulfillment-controlled-cancellations:UPDATE', 'fulfillment-controlled-cancellations',
        'UPDATE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission
        ON permission.code = 'fulfillment-controlled-cancellations:UPDATE'
     WHERE role.code IN ('WAREHOUSE_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
