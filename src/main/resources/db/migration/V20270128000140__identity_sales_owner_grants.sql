-- =============================================================================
-- The permissions of the sales-owner plan (API contract, "Permissions"). Module: identity (permissions and grants).
--
--   customer-sales-owners        READ: who looks after a customer, its history (Sales, manager, accountant)
--                                UPDATE: assign, change, bulk transfer, return (manager only: BR-S01)
--   staff-covers                 READ / CREATE / UPDATE (cancel): covers during leave (Sales: their own; manager: anyone,
--                                checked in the service)
--   sales-leads                  READ / UPDATE: leads (Sales: their own; manager: all, assigns)
--   sales-workload               READ: the managers' assignment board
--   sales-owner-change-requests  CREATE: a portal customer asks; READ: the customer their own, the manager all;
--                                APPROVE: the manager decides
--
-- Sales keep scope ALL for reading (BR-S11); who may act for a customer is the service's rule (SalesOwnership).
-- SYSTEM_ADMIN holds every permission. Grants are cached per role version (ADR-0008): every role that gained a row is
-- advanced in this transaction.
-- =============================================================================

WITH wanted (code, resource, action) AS (
    VALUES ('customer-sales-owners:READ', 'customer-sales-owners', 'READ'),
           ('customer-sales-owners:UPDATE', 'customer-sales-owners', 'UPDATE'),
           ('staff-covers:READ', 'staff-covers', 'READ'),
           ('staff-covers:CREATE', 'staff-covers', 'CREATE'),
           ('staff-covers:UPDATE', 'staff-covers', 'UPDATE'),
           ('sales-leads:READ', 'sales-leads', 'READ'),
           ('sales-leads:UPDATE', 'sales-leads', 'UPDATE'),
           ('sales-workload:READ', 'sales-workload', 'READ'),
           ('sales-owner-change-requests:CREATE', 'sales-owner-change-requests', 'CREATE'),
           ('sales-owner-change-requests:READ', 'sales-owner-change-requests', 'READ'),
           ('sales-owner-change-requests:APPROVE', 'sales-owner-change-requests', 'APPROVE')
)
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), code, resource, action, 0, NOW(), 'flyway'
  FROM wanted
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, permission_code) AS (
    VALUES ('SALES_STAFF', 'customer-sales-owners:READ'),
           ('SALES_MANAGER', 'customer-sales-owners:READ'),
           ('ACCOUNTANT', 'customer-sales-owners:READ'),
           ('SYSTEM_ADMIN', 'customer-sales-owners:READ'),
           ('SALES_MANAGER', 'customer-sales-owners:UPDATE'),
           ('SYSTEM_ADMIN', 'customer-sales-owners:UPDATE'),
           ('SALES_STAFF', 'staff-covers:READ'),
           ('SALES_MANAGER', 'staff-covers:READ'),
           ('SYSTEM_ADMIN', 'staff-covers:READ'),
           ('SALES_STAFF', 'staff-covers:CREATE'),
           ('SALES_MANAGER', 'staff-covers:CREATE'),
           ('SYSTEM_ADMIN', 'staff-covers:CREATE'),
           ('SALES_STAFF', 'staff-covers:UPDATE'),
           ('SALES_MANAGER', 'staff-covers:UPDATE'),
           ('SYSTEM_ADMIN', 'staff-covers:UPDATE'),
           ('SALES_STAFF', 'sales-leads:READ'),
           ('SALES_MANAGER', 'sales-leads:READ'),
           ('SYSTEM_ADMIN', 'sales-leads:READ'),
           ('SALES_STAFF', 'sales-leads:UPDATE'),
           ('SALES_MANAGER', 'sales-leads:UPDATE'),
           ('SYSTEM_ADMIN', 'sales-leads:UPDATE'),
           ('SALES_MANAGER', 'sales-workload:READ'),
           ('SYSTEM_ADMIN', 'sales-workload:READ'),
           ('CUSTOMER', 'sales-owner-change-requests:CREATE'),
           ('SYSTEM_ADMIN', 'sales-owner-change-requests:CREATE'),
           ('CUSTOMER', 'sales-owner-change-requests:READ'),
           ('SALES_MANAGER', 'sales-owner-change-requests:READ'),
           ('SYSTEM_ADMIN', 'sales-owner-change-requests:READ'),
           ('SALES_MANAGER', 'sales-owner-change-requests:APPROVE'),
           ('SYSTEM_ADMIN', 'sales-owner-change-requests:APPROVE')
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
