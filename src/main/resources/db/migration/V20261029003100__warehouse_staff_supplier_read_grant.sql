-- =============================================================================
-- procurement-suppliers:READ for WAREHOUSE_STAFF - the receiver picks the supplier of a receipt without a PO
-- (SCRUM-478, owner decision NOPO-WHO 29/10).
-- Module: identity (grants) for procurement
--
-- A receipt without a PO names its supplier (required). The warehouse clerk who receives the goods could count
-- and send a receipt for approval, but could not list suppliers to create one. READ only: VIEW_PAGE, CREATE and
-- UPDATE on suppliers stay with procurement. The decision itself stays with procurement-goods-receipts:APPROVE.
-- =============================================================================

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission
        ON permission.code = 'procurement-suppliers:READ'
     WHERE role.code = 'WAREHOUSE_STAFF'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the role whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
