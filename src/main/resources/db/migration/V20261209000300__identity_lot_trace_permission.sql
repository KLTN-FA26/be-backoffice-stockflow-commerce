-- =============================================================================
-- The lot trace report gets its own permission (permission review 2026-11-20, A2; kltn-docs 20 §3).
--
-- 20 §3 gives the lot trace report (origin -> cartons left by location -> holds -> customer orders) to the warehouse
-- manager, Sales (customer care) and the Production Coordinator. GET /api/v1/inventory/lots[/{id}[/movements]]
-- answered inventory-stock-movements:READ, the whole stock ledger, which only the warehouse roles hold - so Sales and
-- the coordinator were refused, and granting them the ledger would give them more than the report.
--
-- inventory-lot-traces (VIEW_PAGE, READ) now guards those three endpoints. It is granted to every role that held
-- inventory-stock-movements:READ, custom roles included, so nobody loses the report, and to SALES_STAFF and
-- PRODUCTION_COORDINATOR. Neither of the two gains anything on the stock ledger itself.
--
-- Every role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'inventory-lot-traces:VIEW_PAGE', 'inventory-lot-traces', 'VIEW_PAGE',
        'Open the lot trace screen (kltn-docs 20 §3)', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'inventory-lot-traces:READ', 'inventory-lot-traces', 'READ',
        'Trace a lot: origin, cartons left by location, holds, customer orders, its ledger lines (kltn-docs 20 §3)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH grantee AS (
    SELECT DISTINCT rp.role_id
      FROM identity.role_permission rp
      JOIN identity.permission p ON p.id = rp.permission_id
     WHERE p.code = 'inventory-stock-movements:READ'
    UNION
    SELECT r.id FROM identity.app_role r
     WHERE r.code IN ('SYSTEM_ADMIN', 'SALES_STAFF', 'PRODUCTION_COORDINATOR')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), g.role_id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
     CROSS JOIN identity.permission p
     WHERE p.code IN ('inventory-lot-traces:VIEW_PAGE', 'inventory-lot-traces:READ')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
