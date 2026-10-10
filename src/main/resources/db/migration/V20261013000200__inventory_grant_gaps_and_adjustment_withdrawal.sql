-- =============================================================================
-- Inventory and order permissions that did not match the docs, and adjustment withdrawal (SCRUM-459,
-- SCRUM-256). Docs: kltn-docs 02 §2, 10 §5, 17 §2 and §4.5, 19 (scrap and samples).
--
-- 1. Stock adjustments
--    * SCRAP and SAMPLE (allowed by the table since V20261010000200) only write stock down:
--      blanks spoiled in printing or used for a sample never come back.
--    * WITHDRAWN: the requester takes back a request nobody has decided. Nobody decided it, so it
--      has no decider; withdrawn_at records when.
-- 2. New resource sales-order-cancellations (UPDATE) guards POST /orders/{id}/admin-cancellation.
--    It used to be sales-orders:APPROVE, which also releases orders to production, so Sales, who
--    handle cancellations (17 §2), could not be given it without also being able to release.
-- 3. Grant gaps found in the 2026-10-10 audit:
--    * WAREHOUSE_STAFF reads the stock ledger, adjustments and purchase orders (02 §2: staff look up
--      a Confirmed PO to prepare receiving; the FE receipt screen asks for it). Purchase orders are
--      filtered to the staff member's warehouses in code (BR-SEC-002).
--    * ORDER_COORDINATOR may read and release stock holds and cancel orders; WAREHOUSE_MANAGER may
--      release holds in its warehouses. Only SYSTEM_ADMIN could before.
-- 4. inventory-stock-items:APPROVE guards no endpoint: removed with its grants.
--
-- Transfer orders: submit and cancel now need CREATE (planner / manager) instead of UPDATE, which
-- warehouse staff hold for picking and dispatch. That is a code change; no grant moves here.
--
-- Grants are cached per role version (ADR-0008): every role whose grants change is advanced below.
-- =============================================================================

-- ---- 1. stock adjustments ---------------------------------------------------
ALTER TABLE inventory.stock_adjustment ADD COLUMN withdrawn_at TIMESTAMPTZ;

ALTER TABLE inventory.stock_adjustment DROP CONSTRAINT ck_stock_adjustment_status;
ALTER TABLE inventory.stock_adjustment ADD CONSTRAINT ck_stock_adjustment_status
    CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'POSTED', 'WITHDRAWN'));

ALTER TABLE inventory.stock_adjustment DROP CONSTRAINT ck_stock_adjustment_decided;
ALTER TABLE inventory.stock_adjustment ADD CONSTRAINT ck_stock_adjustment_decided
    CHECK ((status IN ('PENDING_APPROVAL', 'WITHDRAWN')) = (decided_by IS NULL AND decided_at IS NULL));

ALTER TABLE inventory.stock_adjustment ADD CONSTRAINT ck_stock_adjustment_withdrawn
    CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL));

ALTER TABLE inventory.stock_adjustment ADD CONSTRAINT ck_stock_adjustment_writes_down
    CHECK (reason_code NOT IN ('SCRAP', 'SAMPLE') OR quantity_delta < 0);

-- ---- 2. new permission ------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-order-cancellations:UPDATE', 'sales-order-cancellations', 'UPDATE',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

-- ---- 3. grants --------------------------------------------------------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('WAREHOUSE_STAFF',   'inventory-stock-movements',   'VIEW_PAGE,READ'),
          ('WAREHOUSE_STAFF',   'inventory-stock-adjustments', 'VIEW_PAGE,READ'),
          ('WAREHOUSE_STAFF',   'procurement-purchase-orders', 'VIEW_PAGE,READ'),
          ('WAREHOUSE_MANAGER', 'inventory-reservations',      'DELETE'),
          ('ORDER_COORDINATOR', 'inventory-reservations',      'VIEW_PAGE,READ,DELETE'),
          ('ORDER_COORDINATOR', 'sales-order-cancellations',   'UPDATE'),
          ('SALES_STAFF',       'sales-order-cancellations',   'UPDATE')
     ) AS g(role_code, resource, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = g.resource
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ---- 4. orphan permission ---------------------------------------------------
-- Every role that loses it is advanced, custom roles included.
WITH removed AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE rp.permission_id = p.id AND p.code = 'inventory-stock-items:APPROVE'
    RETURNING rp.role_id
)
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE id IN (SELECT role_id FROM removed);

DELETE FROM identity.permission WHERE code = 'inventory-stock-items:APPROVE';

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ORDER_COORDINATOR', 'SALES_STAFF', 'SYSTEM_ADMIN');
