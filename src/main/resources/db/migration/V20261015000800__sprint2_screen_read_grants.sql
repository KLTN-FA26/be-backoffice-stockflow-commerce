-- =============================================================================
-- Read grants the Sprint 2 screens need (FE live integration, 14/10/2026; SCRUM-403, 441, 448).
--
-- Each row is the reading half of a step the role already performs; no role gains a write.
--
--   SALES_STAFF        warehouse-warehouses:READ   submits a sample round to a warehouse
--                                                  (POST /production/sample-requests/{id}/submission
--                                                  takes a warehouseId) but could not list warehouses.
--   SALES_STAFF        design-snapshots:READ       picks the customer's confirmed design for a quote or a
--                                                  sample (GET /design-snapshots?customerId=). Back office
--                                                  only: CUSTOMER holds the same permission since
--                                                  V20260903000100 and the endpoint refuses it.
--   WAREHOUSE_MANAGER  procurement-suppliers:READ  splits work to a print subcontractor (APPROVE on
--                                                  production orders) but could not list suppliers.
--
-- Not granted: VIEW_PAGE (none of these roles gets the warehouse, supplier or design admin page), and
-- warehouse-locations:READ for QC_STAFF - the whole warehouse map is more than choosing where finished
-- goods go; QC reads the usable PACKING areas of its production order from
-- GET /production/orders/{id}/packing-locations instead.
--
-- Grants are cached per role version (ADR-0008): a role is advanced only if it gained rows.
-- =============================================================================

-- Fail loudly rather than grant less than this file says: every permission named must exist.
DO $$
BEGIN
    IF (SELECT count(*) FROM identity.permission
         WHERE code IN ('warehouse-warehouses:READ', 'design-snapshots:READ', 'procurement-suppliers:READ')) <> 3 THEN
        RAISE EXCEPTION 'V20261015000800: a permission it grants is missing';
    END IF;
END $$;

WITH wanted (role_code, permission_code) AS (
    VALUES ('SALES_STAFF',       'warehouse-warehouses:READ'),
           ('SALES_STAFF',       'design-snapshots:READ'),
           ('WAREHOUSE_MANAGER', 'procurement-suppliers:READ')
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
