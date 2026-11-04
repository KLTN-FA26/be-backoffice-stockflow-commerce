-- =============================================================================
-- reporting-reports:VIEW_PAGE, READ for the roles that open the back-office dashboard (SCRUM-344).
-- Module: identity (grants) for reporting
--
-- The dashboard now shows sales figures (GET /api/v1/reports/*, all reporting-reports:READ). V20260903000100
-- granted the resource to ECOMMERCE_ADMIN and ACCOUNTANT, and SYSTEM_ADMIN holds every permission
-- (V20260929000200). The two managers that also open the dashboard (FE permissions.ts "dashboard.view") but
-- hold no reporting grant get it here: WAREHOUSE_MANAGER and ORDER_COORDINATOR. No EXPORT: rebuilding the
-- rollups (POST /reports/rebuild) stays with ECOMMERCE_ADMIN, ACCOUNTANT and SYSTEM_ADMIN.
--
-- Not granted: INVENTORY_PLANNER and PROCUREMENT_STAFF also see the dashboard but have no business with
-- revenue figures; they keep the stock and purchasing tiles and get a 403 on the sales ones.
--
-- Grants are cached per role version (ADR-0008): a role is advanced only if it gained rows.
-- =============================================================================

DO $$
BEGIN
    IF (SELECT count(*) FROM identity.permission
         WHERE code IN ('reporting-reports:VIEW_PAGE', 'reporting-reports:READ')) <> 2 THEN
        RAISE EXCEPTION 'V20261024002100: a permission it grants is missing';
    END IF;
END $$;

WITH wanted (role_code, permission_code) AS (
    VALUES ('WAREHOUSE_MANAGER', 'reporting-reports:VIEW_PAGE'),
           ('WAREHOUSE_MANAGER', 'reporting-reports:READ'),
           ('ORDER_COORDINATOR', 'reporting-reports:VIEW_PAGE'),
           ('ORDER_COORDINATOR', 'reporting-reports:READ')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
    FROM wanted
    JOIN identity.app_role role ON role.code = wanted.role_code
    JOIN identity.permission permission ON permission.code = wanted.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE id IN (SELECT role_id FROM granted);
