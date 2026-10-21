-- =============================================================================
-- Every access check is a permission: the role lists of @PreAuthorize and of CurrentUser.hasRole become
-- resource:ACTION codes, granted to exactly the roles those lists let through.
-- Module: identity (grants) for fulfillment, customer, catalog and the platform
--
-- The lead's rule (2026-10-22): no role name decides access in code; every check calls the permission system, so an
-- administrator who ticks a permission for another role in the matrix screen (ADR-0008) widens it without a release.
--
-- Until now an endpoint passed when the caller held its permission AND one of the roles its @PreAuthorize (or the
-- service's role check) named; SYSTEM_ADMIN passed every role list (RoleHierarchyConfig, CurrentUser.hasRole). Where
-- the holders of the endpoint's permission were exactly that list, the endpoint keeps its permission and only loses the
-- role list. Where they were not, the endpoint gets a new code, mirroring product-lifecycle:APPROVE
-- (V20261019000700):
--
--   new code                                    endpoints / checks                                    replaces
--   fulfillment-task-assignment:CREATE          POST /fulfillment/tasks                               pick-lists:UPDATE + OC, WM
--   fulfillment-task-assignment:UPDATE          GET /fulfillment/assignees, PUT /tasks/{id}/assignment pick-lists:UPDATE + OC, WM
--   fulfillment-task-oversight:READ             task list: every task; task visible to non-assignee  role WM, OC
--   fulfillment-picking:UPDATE                  picking-start/-completion, pick-line confirmation,     pick-lists:UPDATE + WS, WM
--                                               pick-line put-back
--   fulfillment-picking:READ                    GET /fulfillment/put-backs                            pick-lists:READ + WS, WM
--   fulfillment-packing:UPDATE                  packing-start, packages, scans, line removal, seal,    packages:CREATE + WS, WM
--                                               reopening, void, packing-completion; packer sees a
--                                               picked task
--   fulfillment-packing:READ                    GET .../packages/{id}/label                           packages:READ + WS, WM
--   fulfillment-packing:APPROVE                 work / take over another packer's session              role WM
--   fulfillment-design-reverification:UPDATE   POST .../design-integrity-reverification             packages:CREATE + QC, WM
--   fulfillment-design-reverification:READ     task list: tasks on a design hold; task and design     role QC, WM
--                                               files visible to non-assignee
--   fulfillment-carriers:UPDATE                 POST, PUT /fulfillment/carriers                       shipments:UPDATE + WM
--   fulfillment-dispatch:CREATE                 POST /fulfillment/loads                               shipments:CREATE + WS, WM
--   fulfillment-dispatch:UPDATE                 load shipments add/remove, scans, unscan, handover,    shipments:UPDATE + WS, WM
--                                               PUT /shipments/{id}/carrier
--   fulfillment-load-cancellations:UPDATE       POST /fulfillment/loads/{id}/cancellation             shipments:UPDATE + WM
--   fulfillment-delivery-outcomes:UPDATE        POST /shipments/{id}/delivery-outcome (RETURNED)      shipments:UPDATE + OC, WM
--   fulfillment-delivery-outcomes:APPROVE       ... outcome DELIVERED / DELIVERY_FAILED (09 BR-02)    shipments:UPDATE + OC
--   customer-status:APPROVE                     POST /customers/{id}/status                           customer-customers:APPROVE + ECOMMERCE_ADMIN
--   catalog-storefront:READ                     GET /catalog/products/availability, /prices           role CUSTOMER
--   platform-actuator:READ                      GET/HEAD /actuator/**, health details (probes and      role ECOMMERCE_ADMIN
--                                               info stay anonymous)
--   platform-actuator:UPDATE                    every other method on /actuator/**                    role ECOMMERCE_ADMIN
--   sales-order-cancellations:READ              GET /orders/cancellation-requests                     sales-orders:READ + back office
--
-- (OC order coordinator, WM warehouse manager, WS warehouse staff, QC QC staff.) Each grant below goes to a listed
-- role only while it still holds the permission the endpoint needed before, so a matrix edited at run time keeps
-- exactly the access it had. The two READ codes checked inside the task services (oversight, design re-verification)
-- serve endpoints of both fulfillment-pick-lists:READ and fulfillment-packages:READ, so either one qualifies. Every new permission goes to SYSTEM_ADMIN (V20260929000200; SystemAdminRoleIntegrationTest).
--
-- Unchanged: which rows (warehouse scope, a portal customer's own customer, assignee and session owner checks).
--
-- One deliberate change, the lead's decision 2026-10-21 ("ACCREAD"): the Accountant reads orders - the back-office
-- order list and an order's detail and history - with sales-orders:VIEW_PAGE and :READ.
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), p.resource || ':' || p.action, p.resource, p.action, 0, NOW(), 'flyway'
FROM (VALUES
          ('fulfillment-task-assignment', 'CREATE'),
          ('fulfillment-task-assignment', 'UPDATE'),
          ('fulfillment-task-oversight', 'READ'),
          ('fulfillment-picking', 'READ'),
          ('fulfillment-picking', 'UPDATE'),
          ('fulfillment-packing', 'READ'),
          ('fulfillment-packing', 'UPDATE'),
          ('fulfillment-packing', 'APPROVE'),
          ('fulfillment-design-reverification', 'READ'),
          ('fulfillment-design-reverification', 'UPDATE'),
          ('fulfillment-carriers', 'UPDATE'),
          ('fulfillment-dispatch', 'CREATE'),
          ('fulfillment-dispatch', 'UPDATE'),
          ('fulfillment-load-cancellations', 'UPDATE'),
          ('fulfillment-delivery-outcomes', 'UPDATE'),
          ('fulfillment-delivery-outcomes', 'APPROVE'),
          ('customer-status', 'APPROVE'),
          ('catalog-storefront', 'READ'),
          ('platform-actuator', 'READ'),
          ('platform-actuator', 'UPDATE'),
          ('sales-order-cancellations', 'READ')
     ) AS p(resource, action)
ON CONFLICT (code) DO NOTHING;

-- role, new permission, and the permission the role had to hold to pass before (NULL: the old check was a role only).
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('ORDER_COORDINATOR', 'fulfillment-task-assignment:CREATE',      ARRAY['fulfillment-pick-lists:UPDATE']),
          ('WAREHOUSE_MANAGER', 'fulfillment-task-assignment:CREATE',      ARRAY['fulfillment-pick-lists:UPDATE']),
          ('ORDER_COORDINATOR', 'fulfillment-task-assignment:UPDATE',      ARRAY['fulfillment-pick-lists:UPDATE']),
          ('WAREHOUSE_MANAGER', 'fulfillment-task-assignment:UPDATE',      ARRAY['fulfillment-pick-lists:UPDATE']),
          ('ORDER_COORDINATOR', 'fulfillment-task-oversight:READ',         ARRAY['fulfillment-pick-lists:READ', 'fulfillment-packages:READ']),
          ('WAREHOUSE_MANAGER', 'fulfillment-task-oversight:READ',         ARRAY['fulfillment-pick-lists:READ', 'fulfillment-packages:READ']),
          ('WAREHOUSE_STAFF',   'fulfillment-picking:UPDATE',              ARRAY['fulfillment-pick-lists:UPDATE']),
          ('WAREHOUSE_MANAGER', 'fulfillment-picking:UPDATE',              ARRAY['fulfillment-pick-lists:UPDATE']),
          ('WAREHOUSE_STAFF',   'fulfillment-picking:READ',                ARRAY['fulfillment-pick-lists:READ']),
          ('WAREHOUSE_MANAGER', 'fulfillment-picking:READ',                ARRAY['fulfillment-pick-lists:READ']),
          ('WAREHOUSE_STAFF',   'fulfillment-packing:UPDATE',              ARRAY['fulfillment-packages:CREATE']),
          ('WAREHOUSE_MANAGER', 'fulfillment-packing:UPDATE',              ARRAY['fulfillment-packages:CREATE']),
          ('WAREHOUSE_STAFF',   'fulfillment-packing:READ',                ARRAY['fulfillment-packages:READ']),
          ('WAREHOUSE_MANAGER', 'fulfillment-packing:READ',                ARRAY['fulfillment-packages:READ']),
          ('WAREHOUSE_MANAGER', 'fulfillment-packing:APPROVE',             ARRAY['fulfillment-packages:CREATE']),
          ('QC_STAFF',          'fulfillment-design-reverification:UPDATE', ARRAY['fulfillment-packages:CREATE']),
          ('WAREHOUSE_MANAGER', 'fulfillment-design-reverification:UPDATE', ARRAY['fulfillment-packages:CREATE']),
          ('QC_STAFF',          'fulfillment-design-reverification:READ', ARRAY['fulfillment-pick-lists:READ', 'fulfillment-packages:READ']),
          ('WAREHOUSE_MANAGER', 'fulfillment-design-reverification:READ', ARRAY['fulfillment-pick-lists:READ', 'fulfillment-packages:READ']),
          ('WAREHOUSE_MANAGER', 'fulfillment-carriers:UPDATE',             ARRAY['fulfillment-shipments:UPDATE']),
          ('WAREHOUSE_STAFF',   'fulfillment-dispatch:CREATE',             ARRAY['fulfillment-shipments:CREATE']),
          ('WAREHOUSE_MANAGER', 'fulfillment-dispatch:CREATE',             ARRAY['fulfillment-shipments:CREATE']),
          ('WAREHOUSE_STAFF',   'fulfillment-dispatch:UPDATE',             ARRAY['fulfillment-shipments:UPDATE']),
          ('WAREHOUSE_MANAGER', 'fulfillment-dispatch:UPDATE',             ARRAY['fulfillment-shipments:UPDATE']),
          ('WAREHOUSE_MANAGER', 'fulfillment-load-cancellations:UPDATE',   ARRAY['fulfillment-shipments:UPDATE']),
          ('ORDER_COORDINATOR', 'fulfillment-delivery-outcomes:UPDATE',    ARRAY['fulfillment-shipments:UPDATE']),
          ('WAREHOUSE_MANAGER', 'fulfillment-delivery-outcomes:UPDATE',    ARRAY['fulfillment-shipments:UPDATE']),
          ('ORDER_COORDINATOR', 'fulfillment-delivery-outcomes:APPROVE',   ARRAY['fulfillment-shipments:UPDATE']),
          ('ECOMMERCE_ADMIN',   'customer-status:APPROVE',                 ARRAY['customer-customers:APPROVE']),
          ('CUSTOMER',          'catalog-storefront:READ',                 NULL),
          ('ECOMMERCE_ADMIN',   'platform-actuator:READ',                  NULL),
          ('ECOMMERCE_ADMIN',   'platform-actuator:UPDATE',                NULL)
     ) AS g(role_code, permission_code, required_codes)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission ON permission.code = g.permission_code
WHERE g.required_codes IS NULL
   OR EXISTS (SELECT 1
                FROM identity.role_permission held
                JOIN identity.permission old ON old.id = held.permission_id
               WHERE held.role_id = role.id
                 AND old.code = ANY (g.required_codes))
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- The cancellation-request queue (GET /orders/cancellation-requests) needed sales-orders:READ and a back-office
-- account. It gets its own code so that the Accountant's new sales-orders:READ (ACCREAD, below) does not open it: every
-- role that holds sales-orders:READ now - which is before the Accountant's grant - except CUSTOMER, which was always
-- refused there (ScopeGuards.requireBackOffice). Roles created at run time included.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, queue.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission queue ON queue.code = 'sales-order-cancellations:READ'
WHERE role.code NOT IN ('CUSTOMER', 'ACCOUNTANT')
  AND EXISTS (SELECT 1
                FROM identity.role_permission held
                JOIN identity.permission old ON old.id = held.permission_id
               WHERE held.role_id = role.id
                 AND old.code = 'sales-orders:READ')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ACCREAD (2026-10-21): the Accountant reads orders - the back-office list, an order's detail and history.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.code IN ('sales-orders:VIEW_PAGE', 'sales-orders:READ')
WHERE role.code = 'ACCOUNTANT'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ADR-0008: role-keyed permission caches must change generation in this same transaction.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('ORDER_COORDINATOR', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF', 'QC_STAFF', 'ECOMMERCE_ADMIN', 'CUSTOMER',
               'ACCOUNTANT', 'SALES_STAFF', 'SYSTEM_ADMIN')
   OR id IN (SELECT rp.role_id FROM identity.role_permission rp JOIN identity.permission p ON p.id = rp.permission_id
              WHERE p.code = 'sales-order-cancellations:READ');
