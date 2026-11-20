-- =============================================================================
-- The Sales Manager no longer reaches the actuator (permission review 2026-11-20, A1; kltn-docs 00 §2.1).
--
-- V20261022000200 seeded platform-actuator:READ / UPDATE for the two roles that reached /actuator/** before the
-- permission existed: SALES_MANAGER and SYSTEM_ADMIN. That was a carry-over, not a decision: 00 §2.1 gives the Sales
-- Manager the business (products, prices, designs, customers, approving production orders) and the running of the
-- system to the System Admin. READ shows health details, metrics and the module map; UPDATE is every other method on
-- any exposed endpoint. Both stay with SYSTEM_ADMIN only. A role created at run time keeps whatever it was granted.
--
-- The role that lost a grant advances app_role.version in the same file (ADR-0008), so its holders lose it on their
-- next request.
-- =============================================================================

WITH revoked AS (
    DELETE FROM identity.role_permission rp
     USING identity.app_role role, identity.permission permission
     WHERE rp.role_id = role.id
       AND rp.permission_id = permission.id
       AND role.code = 'SALES_MANAGER'
       AND permission.code IN ('platform-actuator:READ', 'platform-actuator:UPDATE')
    RETURNING rp.role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM revoked);
