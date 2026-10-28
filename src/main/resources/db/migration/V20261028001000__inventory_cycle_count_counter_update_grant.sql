-- =============================================================================
-- inventory-cycle-counts:UPDATE for WAREHOUSE_STAFF - the counter enters counted quantities (SCRUM-146).
-- Module: identity (grants) for inventory
--
-- docs 04c F-CNT step 03: the warehouse staff count on mobile. V20261023000300 gave them VIEW_PAGE and READ
-- and said entering counts is UPDATE and comes with the cycle-count story; this is that grant. Without it an
-- assignee gets 403 on PUT /{id}/lines/{lineId}/count, POST /{id}/lines and POST /{id}/submission.
-- CREATE and APPROVE stay out: staff neither plan, cancel, nor send counts for recount.
-- =============================================================================

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission
        ON permission.code = 'inventory-cycle-counts:UPDATE'
     WHERE role.code = 'WAREHOUSE_STAFF'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the role whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
