-- =============================================================================
-- ORDER_COORDINATOR may list warehouses (FE live integration, 22/10/2026; SCRUM-423 order release).
--
-- The reading half of a step the role already performs; it gains no write.
--
--   ORDER_COORDINATOR  warehouse-warehouses:READ   releases an order to a warehouse
--                                                  (POST /orders/{id}/release takes a warehouseId,
--                                                  sales-orders:APPROVE since V20260903000100) but could
--                                                  not list warehouses: GET /warehouses answered 403, so
--                                                  the release dialog had nothing to choose from.
--
-- Not granted: VIEW_PAGE (the coordinator gets no warehouse admin page), CREATE / UPDATE, and
-- warehouse-locations:* - choosing the warehouse an order ships from is not editing it or reading its map.
-- GET /warehouses is not narrowed by data scope, and ORDER_COORDINATOR's scope is ALL (V20261012000200):
-- the coordinator sees the same directory SALES_STAFF reads since V20261017000800.
--
-- Grants are cached per role version (ADR-0008): the role is advanced only if it gained the row, so
-- re-running this on a database that already has it changes nothing.
-- =============================================================================

-- Fail loudly rather than grant less than this file says: the permission and the role must exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM identity.permission WHERE code = 'warehouse-warehouses:READ') THEN
        RAISE EXCEPTION 'V20261022001100: permission warehouse-warehouses:READ is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM identity.app_role WHERE code = 'ORDER_COORDINATOR') THEN
        RAISE EXCEPTION 'V20261022001100: role ORDER_COORDINATOR is missing';
    END IF;
END $$;

WITH wanted (role_code, permission_code) AS (
    VALUES ('ORDER_COORDINATOR', 'warehouse-warehouses:READ')
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
