-- =============================================================================
-- Two roles go: ORDER_COORDINATOR and INVENTORY_PLANNER (team plan 5-7; kltn-docs 00 §2 decision 2026-11-05, §2.3;
-- QD-12, QD-13; cleanup §8.1-§8.3).
--
-- The customer's and the production side of their work went to Sales (holds, cancellation requests, partial delivery,
-- returns, quotes into orders), the warehouse side to the warehouse manager of the shipping warehouse (release, pick
-- tasks, staging, delivery outcomes, counts). Order of the steps, as cleanup §8.3 requires:
--
--   1. new permissions, split out of sales-orders:APPROVE so that neither side receives the other's work (QD-12):
--        * sales-order-releases:UPDATE - releasing an order to a warehouse (warehouse manager, of the warehouse);
--        * sales-order-holds:UPDATE    - putting an order on hold and resolving the hold (Sales);
--      the order-level delivered / not delivered records (an order without shipments) answer to
--      fulfillment-delivery-outcomes:APPROVE, as the shipment's do;
--   2. grants, each advancing app_role.version of exactly the roles that gained one (ADR-0008):
--        * WAREHOUSE_MANAGER: sales-order-releases:UPDATE, fulfillment-delivery-outcomes:APPROVE, and
--          sales-orders:READ / VIEW_PAGE - to find the orders it releases and whose delivery it records (17 §2, BR-03,
--          BR-27). Its scope is WAREHOUSE, and the order list and page answer it only for orders shipping from or
--          released to its warehouses (OrderWarehouseVisibility). The planner's two exports
--          (inventory-stock-items:EXPORT, inventory-stock-movements:EXPORT) are not handed on: no endpoint checks
--          either, and they go with the planner's grants;
--        * SALES_STAFF: sales-order-holds:UPDATE, fulfillment-load-estimates:READ and :CREATE (00 §2.1: Sales answers
--          the customer about a delivery). Sales already holds cancellations, partial delivery, returns, quote
--          conversion and the shipping warehouse;
--        * SYSTEM_ADMIN: every permission row it does not hold yet - the two new ones (00 §2.5.3: all of them);
--   3. the people holding a retired role are given the role that took its work (QD-13): an order coordinator becomes
--      Sales Staff; an inventory planner becomes Warehouse Manager and, being of scope WAREHOUSE from now on, is
--      assigned every active warehouse when assigned none (00 §2.5.3: assigned no warehouse, they would see nothing).
--      A staff account always keeps a role (00 §2.5.1);
--   4. their live sessions end (revoked_reason ROLE_CHANGED, 00 §2.5.1 rule 5): a signed-in holder would otherwise go
--      on with a token naming a role that no longer exists;
--   5. every grant of the two roles goes - generically, every row, not a list: grants added to them by migrations
--      merged before this one (fulfillment-task-assignment, sales-order-shipping-warehouses, ...) go too, and the task
--      assignment stays with the warehouse manager, who holds it, as 07 §2 gives it to them - then their user_role
--      rows, then the roles themselves;
--   6. sales-orders:APPROVE, which nothing checks any more: its grants first (advancing the version of the roles
--      that lose one), then the row;
--   7. permission rows no code declares (cleanup §8.3 table; only rows no sibling package uses again - payment-refunds
--      is 4B-9's, procurement-* is V20261113000200's): grants first, versions, then the rows. Kept on purpose:
--      chat-conversations, product-customization-templates (cleanup §8.3);
--   8. the descriptions of the roles that took the work (and Role.java says the same).
--
-- Grants are deleted before permission rows on purpose: fk_role_permission_permission cascades, and a cascade moves no
-- version, so a signed-in holder would keep the grant in the authorisation cache for up to an hour.
--
-- Nothing is archived (owner's decision QD-1, 2026-11-10: the database is rebuilt on deploy). Written to survive what
-- the production line runs before it: nothing here names procurement.approval_limits (V20261113000200 drops it); its
-- rows of a deleted role went with fk_approval_limits_role's cascade while the table existed.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. New permissions
-- ---------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-order-releases:UPDATE', 'sales-order-releases', 'UPDATE',
        'Release an order to a warehouse: its stock is allocated on bins there (kltn-docs 17 BR-03)', 0, NOW(),
        'flyway'),
       (gen_random_uuid(), 'sales-order-holds:UPDATE', 'sales-order-holds', 'UPDATE',
        'Put an order on hold and resolve the hold (kltn-docs 17 §5)', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;


-- ---------------------------------------------------------------------------------------------
-- 2. Grants to the roles that took the work
-- ---------------------------------------------------------------------------------------------
WITH wanted (role_code, permission_code) AS (
    VALUES ('WAREHOUSE_MANAGER', 'sales-order-releases:UPDATE'),
           ('WAREHOUSE_MANAGER', 'fulfillment-delivery-outcomes:APPROVE'),
           ('WAREHOUSE_MANAGER', 'sales-orders:READ'),
           ('WAREHOUSE_MANAGER', 'sales-orders:VIEW_PAGE'),
           ('SALES_STAFF', 'sales-order-holds:UPDATE'),
           ('SALES_STAFF', 'fulfillment-load-estimates:READ'),
           ('SALES_STAFF', 'fulfillment-load-estimates:CREATE')
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


-- ---------------------------------------------------------------------------------------------
-- 3. The holders of the retired roles take the role that took their work (QD-13)
-- ---------------------------------------------------------------------------------------------
CREATE TEMPORARY TABLE retired_role_holder ON COMMIT DROP AS
SELECT DISTINCT ur.user_id, r.code AS retired_code
  FROM identity.user_role ur
  JOIN identity.app_role r ON r.id = ur.role_id
 WHERE r.code IN ('ORDER_COORDINATOR', 'INVENTORY_PLANNER');

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT gen_random_uuid(), h.user_id, successor.id, 0, NOW(), 'flyway'
  FROM retired_role_holder h
  JOIN identity.app_role successor
    ON successor.code = CASE h.retired_code WHEN 'ORDER_COORDINATOR' THEN 'SALES_STAFF' ELSE 'WAREHOUSE_MANAGER' END
ON CONFLICT (user_id, role_id) DO NOTHING;

-- A former planner had scope ALL; as a warehouse manager, assigned to no warehouse, they would see nothing.
INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at, created_by)
SELECT gen_random_uuid(), h.user_id, w.id, 0, NOW(), 'flyway'
  FROM retired_role_holder h
 CROSS JOIN warehouse.warehouse w
 WHERE h.retired_code = 'INVENTORY_PLANNER'
   AND w.status = 'ACTIVE'
   AND NOT EXISTS (SELECT 1 FROM identity.user_warehouse uw WHERE uw.user_id = h.user_id)
ON CONFLICT (user_id, warehouse_id) DO NOTHING;


-- ---------------------------------------------------------------------------------------------
-- 4. Their live sessions end (00 §2.5.1 rule 5)
-- ---------------------------------------------------------------------------------------------
UPDATE identity.user_session s
   SET revoked_at = NOW(), revoked_reason = 'ROLE_CHANGED', version = s.version + 1,
       last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE s.revoked_at IS NULL
   AND s.expires_at > NOW()
   AND s.user_id IN (SELECT user_id FROM retired_role_holder);


-- ---------------------------------------------------------------------------------------------
-- 5. The roles: every grant, then who holds them, then the rows
-- ---------------------------------------------------------------------------------------------
WITH lost AS (
    DELETE FROM identity.role_permission rp
     USING identity.app_role r
     WHERE r.id = rp.role_id
       AND r.code IN ('ORDER_COORDINATOR', 'INVENTORY_PLANNER')
    RETURNING rp.role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.user_role ur
 USING identity.app_role r
 WHERE r.id = ur.role_id
   AND r.code IN ('ORDER_COORDINATOR', 'INVENTORY_PLANNER');

DELETE FROM identity.app_role WHERE code IN ('ORDER_COORDINATOR', 'INVENTORY_PLANNER');


-- ---------------------------------------------------------------------------------------------
-- 6. sales-orders:APPROVE: release went to sales-order-releases, holds to sales-order-holds, the order's delivery
--    outcome to fulfillment-delivery-outcomes:APPROVE
-- ---------------------------------------------------------------------------------------------
WITH lost AS (
    DELETE FROM identity.role_permission rp
     USING identity.permission p
     WHERE p.id = rp.permission_id
       AND p.code = 'sales-orders:APPROVE'
    RETURNING rp.role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.permission WHERE code = 'sales-orders:APPROVE';


-- ---------------------------------------------------------------------------------------------
-- 7. Permission rows no code declares (cleanup §8.3)
-- ---------------------------------------------------------------------------------------------
CREATE TEMPORARY TABLE undeclared_permission ON COMMIT DROP AS
SELECT id
  FROM identity.permission
 WHERE resource IN ('warehouse-replenishment', 'inventory-allocations', 'fulfillment-return-shipments',
                    'product-brands', 'product-categories', 'product-media',
                    'sales-carts', 'payment-payments', 'payment-settlements', 'payment-cod-remittances')
    OR code = 'product-products:EXPORT';

WITH lost AS (
    DELETE FROM identity.role_permission rp
     WHERE rp.permission_id IN (SELECT id FROM undeclared_permission)
    RETURNING rp.role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.permission WHERE id IN (SELECT id FROM undeclared_permission);


-- ---------------------------------------------------------------------------------------------
-- 8. What the roles that took the work do now (kltn-docs 00 §2.1)
-- ---------------------------------------------------------------------------------------------
UPDATE identity.app_role AS r
   SET description = d.description, last_modified_at = NOW(), last_modified_by = 'flyway'
  FROM (VALUES
        ('WAREHOUSE_MANAGER',
         'Assigned warehouses: set-up, receiving decisions, order release, pick tasks and staging, delivery outcomes, '
             || 'counts and adjustment approval'),
        ('SALES_STAFF',
         'Customers, quotes, orders and contracts; shipping warehouse, holds, cancellations, partial delivery and '
             || 'returns of a confirmed order; chat'),
        ('ACCOUNTANT',
         'Receipts against contract instalments, receivables and refunds, credit approval, e-invoices')
       ) AS d (code, description)
 WHERE r.code = d.code;
