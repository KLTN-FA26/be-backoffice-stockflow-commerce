-- =============================================================================
-- 2026-11-14 plan, BE2: the management reports and the Excel export of payment receipts.
--
-- Two report resources of their own rather than more actions of reporting-reports (the sales dashboard, 17 §4.11):
-- the dashboard's readers are not the reports' readers, and a permission is what opens a screen (ADR-0008).
--
--   reporting-receivables (VIEW_PAGE, READ, EXPORT) - receivables per customer and their age, today (kltn-docs 15
--     §3 "công nợ phải thu và vị thế tín dụng", §4.3 step 8). Read by the accountant, Sales ("xem công nợ của khách",
--     15 §2) and the Sales Manager; exported by the accountant and the Sales Manager - the export takes every
--     customer's debt out of the building, which a salesperson following their own customers does not need.
--   reporting-stock-summary (VIEW_PAGE, READ, EXPORT) - stock in, out and on hand per SKU and warehouse over a period,
--     in pieces and cartons, from the stock ledger (20 §4.7, glossary "sổ biến động tồn"). Read by the warehouse
--     manager (their warehouses only: the endpoint narrows a WAREHOUSE-scoped caller), the Sales Manager and the
--     accountant; exported by the warehouse manager and the accountant.
--   payment-receipts:EXPORT - the receipts of a period as XLSX, what the accountant takes into the accounting software
--     (15 BR-06). The accountant only, as recording and voiding receipts are.
--
-- System Admin holds every permission row (00 §2.5.3; SystemAdminRoleIntegrationTest), by the generic block at the
-- end. The customer gets none of them. Nothing but the two permission tables is written.
--
-- Every role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'reporting-receivables:VIEW_PAGE', 'reporting-receivables', 'VIEW_PAGE',
        'Open the receivables report: what each customer owes after delivery and how overdue (kltn-docs 15 §4.3)',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'reporting-receivables:READ', 'reporting-receivables', 'READ',
        'Read receivables per customer, aged by due date, as of today (kltn-docs 15 §3, §4.3 step 8)',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'reporting-receivables:EXPORT', 'reporting-receivables', 'EXPORT',
        'Export the receivables report as an Excel file (kltn-docs 15 §4.3)',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'reporting-stock-summary:VIEW_PAGE', 'reporting-stock-summary', 'VIEW_PAGE',
        'Open the stock in/out/on-hand report per SKU and warehouse (kltn-docs 20 §4.7)',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'reporting-stock-summary:READ', 'reporting-stock-summary', 'READ',
        'Read opening, received, returned, picked, adjusted and closing stock per SKU and warehouse over a period',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'reporting-stock-summary:EXPORT', 'reporting-stock-summary', 'EXPORT',
        'Export the stock in/out/on-hand report as an Excel file',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'payment-receipts:EXPORT', 'payment-receipts', 'EXPORT',
        'Export the payment receipts of a filter as an Excel file, for the accounting software (kltn-docs 15 BR-06)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, permission_code) AS (
    VALUES ('ACCOUNTANT', 'reporting-receivables:VIEW_PAGE'),
           ('ACCOUNTANT', 'reporting-receivables:READ'),
           ('ACCOUNTANT', 'reporting-receivables:EXPORT'),
           ('SALES_MANAGER', 'reporting-receivables:VIEW_PAGE'),
           ('SALES_MANAGER', 'reporting-receivables:READ'),
           ('SALES_MANAGER', 'reporting-receivables:EXPORT'),
           ('SALES_STAFF', 'reporting-receivables:VIEW_PAGE'),
           ('SALES_STAFF', 'reporting-receivables:READ'),
           ('WAREHOUSE_MANAGER', 'reporting-stock-summary:VIEW_PAGE'),
           ('WAREHOUSE_MANAGER', 'reporting-stock-summary:READ'),
           ('WAREHOUSE_MANAGER', 'reporting-stock-summary:EXPORT'),
           ('SALES_MANAGER', 'reporting-stock-summary:VIEW_PAGE'),
           ('SALES_MANAGER', 'reporting-stock-summary:READ'),
           ('ACCOUNTANT', 'reporting-stock-summary:VIEW_PAGE'),
           ('ACCOUNTANT', 'reporting-stock-summary:READ'),
           ('ACCOUNTANT', 'reporting-stock-summary:EXPORT'),
           ('ACCOUNTANT', 'payment-receipts:EXPORT')
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

-- System Admin holds every permission row (00 §2.5.3; SystemAdminRoleIntegrationTest).
WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
     CROSS JOIN identity.permission permission
     WHERE role.code = 'SYSTEM_ADMIN'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
