-- =============================================================================
-- The Sales Manager reads customers' credit (customer-credit-profiles:READ), as Sales Staff and the accountant do.
-- Module: identity (grants). Repository owner's decision of 2026-11-27.
--
-- The manager approves quotes held for a price review and looks over the customer list, whose new credit columns
-- (GET /orders/credit-positions) and the price review's credit block both read this grant; without it the manager,
-- the one who signs off on deals, was the one Sales role blind to what a customer owes. READ only: setting a limit
-- stays with the accountant (credit-request approval).
--
-- Grants are cached per role version (ADR-0008, CLAUDE.md §5): the role is advanced in this transaction only when it
-- gained the row.
-- =============================================================================

WITH wanted (role_code, permission_code) AS (
    VALUES ('SALES_MANAGER', 'customer-credit-profiles:READ')
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
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
