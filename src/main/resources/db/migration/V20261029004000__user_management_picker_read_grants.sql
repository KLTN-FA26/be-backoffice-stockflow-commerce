-- =============================================================================
-- identity-roles:READ and warehouse-warehouses:READ for every role that holds identity-users:READ
-- (SCRUM-474 part 5b: the staff user-management screen, SCRUM-458).
-- Module: identity (grants) for warehouse
--
-- The screen that creates and edits a staff user picks the user's roles from GET /identity/roles
-- (identity-roles:READ) and the warehouses the user may work in from GET /warehouses
-- (warehouse-warehouses:READ). A role that can open the screen (identity-users:READ) but lacks either
-- permission gets an empty picker. In the seed this is ECOMMERCE_ADMIN (no warehouse read); SYSTEM_ADMIN
-- already holds both. READ only: VIEW_PAGE, CREATE and UPDATE on roles and warehouses are not granted.
-- =============================================================================

WITH needed AS (
    SELECT holder.role_id, wanted.id AS permission_id
      FROM identity.role_permission holder
      JOIN identity.permission users_read
        ON users_read.id = holder.permission_id AND users_read.code = 'identity-users:READ'
      JOIN identity.permission wanted
        ON wanted.code IN ('identity-roles:READ', 'warehouse-warehouses:READ')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), needed.role_id, needed.permission_id, 0, NOW(), 'flyway'
      FROM needed
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
