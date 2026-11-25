-- =============================================================================
-- WF-02 fix round 6 (AUTH-4, AUTH-6; owner decisions QĐ14, QĐ15 of 2026-11-24): two read-only grants.
--
-- AUTH-4 / QĐ14: the Sales Manager supervises the sales chat and approves prices (kltn-docs 16 §4, 14 §2), and already
-- reads an order's contract and installments, but not the quote or the order itself: every link from a chat card to a
-- quote or an order answered 403. Granted: sales-quotes and sales-orders VIEW_PAGE + READ. Not CREATE or UPDATE:
-- drawing up a quote and changing an order stay with Sales Staff.
--
-- AUTH-6 / QĐ15: the Accountant grants and changes a customer's credit limit (customer-credit-profiles:APPROVE,
-- 15 §4.3), which is on the customer's page, and could not open that page. Granted: customer-customers VIEW_PAGE + READ.
-- Not UPDATE: the customer's data stays Sales'.
--
-- Both roles have data scope ALL. Every role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

WITH wanted (role_code, permission_code) AS (
    VALUES ('SALES_MANAGER', 'sales-quotes:VIEW_PAGE'),
           ('SALES_MANAGER', 'sales-quotes:READ'),
           ('SALES_MANAGER', 'sales-orders:VIEW_PAGE'),
           ('SALES_MANAGER', 'sales-orders:READ'),
           ('ACCOUNTANT', 'customer-customers:VIEW_PAGE'),
           ('ACCOUNTANT', 'customer-customers:READ')
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
