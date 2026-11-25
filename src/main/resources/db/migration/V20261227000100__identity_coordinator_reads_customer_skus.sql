-- =============================================================================
-- SCRUM-716 (kltn-docs 12 BR-03, 01 §4.8, §5.2): who looks a customer's own SKUs up, and who blocks or retires them.
--
-- 12 BR-03: an approved design and the customer's own SKUs are looked up by customer by Sales, the Sales Manager and
-- the Production Coordinator (to quote, to draw the order up, to draw the production order up). Every Sales Staff
-- member reads every customer's: there is no "Sales owning a customer" in the system (18 has no such field), and the
-- owner decided on 2026-11-25 to follow 12 BR-03 rather than read one into 01 §2. The coordinator lacked the read:
--
--   product-variants:READ    + PRODUCTION_COORDINATOR (GET /customer-skus and the variant lookups; read-only)
--
-- Unchanged, and pinned by PermissionMatrixTest: blocking a SKU is product-variants:UPDATE (Sales Staff, Sales Manager,
-- System Admin, 01 §5.2 "Active → Blocked"); unblocking (POST .../activation) and retiring (POST .../obsoletion) are
-- product-variants:APPROVE (Sales Manager, System Admin). OBSOLETE is terminal in VariantStatus.
--
-- The role that gained a grant advances app_role.version in the same transaction (ADR-0008, CLAUDE.md §5).
-- =============================================================================

WITH grantee (role_code, permission_code) AS (
    VALUES ('PRODUCTION_COORDINATOR', 'product-variants:READ')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
      JOIN identity.app_role r ON r.code = g.role_code
      JOIN identity.permission p ON p.code = g.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
