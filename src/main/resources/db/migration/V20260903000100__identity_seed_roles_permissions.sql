-- =============================================================================
-- Identity: seed the 10 platform roles, the permission catalog, and the default
-- role -> permission grants.  (WBS 3.19.1.1 / SCRUM-20)
--
-- Appended AFTER V20260902001200 (which created the empty tables); that migration
-- is already applied and is never edited -- Flyway validates it by checksum.
--
-- ---------------------------------------------------------------------------
-- READ THIS BEFORE CHANGING THE GRANTS
--
-- 1. Roles (app_role.code) use the exact authority strings from
--    common/security/Role.java, so a future identity-service can map a role row
--    straight to a JWT authority.  Do not rename them here without renaming the
--    enum -- keep the two in sync.
--
-- 2. Permission rows are the TARGET catalog derived from docs/api-audit.csv.
--    Only 3 resources have a live @PermissionResource in code today
--    (inventory-stock-items, inventory-reservations, sales-orders); the other
--    ~31 are planned resources for modules still being built.  Per ADR-0004 the
--    runtime catalog is generated from @PermissionResource annotations, NOT from
--    this table, and there is no validator that catches DB-vs-catalog drift.  So
--    when a planned module is actually built, confirm its resource code + action
--    set here match the annotation the team lands, and reconcile if they differ.
--
-- 3. The "skip-sensitive" rule (RoleMatrixAssembler.selectAllSafely): a bulk
--    grant of a resource gives only the non-sensitive actions
--    (VIEW_PAGE/READ/CREATE/UPDATE).  DELETE, APPROVE and EXPORT are sensitive
--    and are granted only by an explicit, separate statement per role below.
--
-- 4. DataScope (OWN/TEAM/WAREHOUSE/ALL) is NOT seeded: neither app_role nor
--    role_permission has a scope column yet, and ADR-0004 records the scope
--    filter as unimplemented.  Grants here are (resource, action) only.
--
-- 5. Two security defaults that need a human sign-off:
--      - identity-users / identity-roles / identity-rbac (platform admin) are
--        granted to ECOMMERCE_ADMIN, because none of the 10 roles is a dedicated
--        SUPER_ADMIN.  Revisit if a real admin role is added.
--      - CUSTOMER grants are global (no OWN scope yet); a customer can only be
--        held to their own rows once the DataScope filter exists.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. The 10 roles.  Deterministic ids so later seeds/tests can reference a role
--    by a known id; permission and grant ids are generated.
-- -----------------------------------------------------------------------------
INSERT INTO identity.app_role (id, code, name, description, version, created_at, created_by)
VALUES
    ('a0000001-0000-4000-8000-000000000001', 'WAREHOUSE_STAFF',   'Warehouse Staff',
     'Receive, put away, pick, pack and hand over to carriers',            0, NOW(), 'flyway'),
    ('a0000001-0000-4000-8000-000000000002', 'WAREHOUSE_MANAGER', 'Warehouse Manager',
     'Approve adjustments and transfers, override slotting',               0, NOW(), 'flyway'),
    ('a0000001-0000-4000-8000-000000000003', 'INVENTORY_PLANNER', 'Inventory Planner',
     'Monitor stock levels and raise transfer orders',                     0, NOW(), 'flyway'),
    ('a0000001-0000-4000-8000-000000000004', 'QC_STAFF',          'QC Staff',
     'Inspect inbound goods and printed output',                           0, NOW(), 'flyway'),
    ('a0000001-0000-4000-8000-000000000005', 'CUSTOMER',          'Customer',
     'Browse, design, order and track',                                    0, NOW(), 'flyway'),
    ('a0000001-0000-4000-8000-000000000006', 'SALES_STAFF',       'Sales Staff',
     'Advise customers, edit designs, create assisted orders',             0, NOW(), 'flyway'),
    ('a0000001-0000-4000-8000-000000000007', 'ORDER_COORDINATOR', 'Order Coordinator',
     'Release orders to the warehouse and handle holds',                   0, NOW(), 'flyway'),
    ('a0000001-0000-4000-8000-000000000008', 'ECOMMERCE_ADMIN',   'E-commerce Admin',
     'Catalog, pricing, promotions, design templates',                     0, NOW(), 'flyway'),
    ('a0000001-0000-4000-8000-000000000009', 'PROCUREMENT_STAFF', 'Procurement Staff',
     'Purchase orders and supplier coordination',                          0, NOW(), 'flyway'),
    ('a0000001-0000-4000-8000-00000000000a', 'ACCOUNTANT',        'Accountant',
     'Supplier invoices, three-way matching, reconciliation',              0, NOW(), 'flyway');


-- -----------------------------------------------------------------------------
-- 2. The permission catalog.  One row per (resource, action); code is
--    "resource:ACTION" to match common/security/PermissionCode.toString().
--    Actions are expanded from the array with unnest, so each resource is one
--    readable line here.  VIEW_PAGE is listed explicitly per resource.
-- -----------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
SELECT gen_random_uuid(), res || ':' || act, res, act, NULL, 0, NOW(), 'flyway'
FROM (
    VALUES
        -- Inventory
        ('inventory-stock-items',         ARRAY['VIEW_PAGE','READ','UPDATE','APPROVE','EXPORT']),
        ('inventory-reservations',        ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','DELETE']),
        ('inventory-allocations',         ARRAY['VIEW_PAGE','READ','CREATE']),
        ('inventory-stock-movements',     ARRAY['VIEW_PAGE','READ','EXPORT']),
        ('inventory-transfer-orders',     ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','APPROVE']),
        ('inventory-cycle-counts',        ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','APPROVE']),
        ('inventory-stock-adjustments',   ARRAY['VIEW_PAGE','READ','APPROVE']),
        -- Sales (order module)
        ('sales-carts',                   ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','DELETE']),
        ('sales-orders',                  ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','APPROVE','EXPORT']),
        ('sales-rmas',                    ARRAY['VIEW_PAGE','READ','CREATE','APPROVE']),
        -- Products
        ('product-products',              ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','APPROVE','EXPORT']),
        -- Procurement
        ('procurement-purchase-orders',   ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','APPROVE','EXPORT']),
        ('procurement-goods-receipts',    ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','APPROVE']),
        ('procurement-qc-tasks',          ARRAY['VIEW_PAGE','READ','APPROVE']),
        ('procurement-supplier-invoices', ARRAY['VIEW_PAGE','READ','CREATE','UPDATE','APPROVE']),
        -- Warehouse
        ('warehouse-locations',           ARRAY['VIEW_PAGE','READ','CREATE','UPDATE']),
        ('warehouse-putaway-tasks',       ARRAY['VIEW_PAGE','READ','UPDATE']),
        ('warehouse-replenishment',       ARRAY['VIEW_PAGE','READ','UPDATE']),
        -- Design
        ('design-designs',                ARRAY['VIEW_PAGE','READ','CREATE','UPDATE']),
        ('design-snapshots',              ARRAY['VIEW_PAGE','READ']),
        -- Payment
        ('payment-payments',              ARRAY['VIEW_PAGE','READ','CREATE','APPROVE']),
        ('payment-refunds',               ARRAY['VIEW_PAGE','READ','CREATE','APPROVE']),
        ('payment-settlements',           ARRAY['VIEW_PAGE','READ','CREATE']),
        ('payment-cod-remittances',       ARRAY['VIEW_PAGE','READ','CREATE']),
        -- Fulfilment
        ('fulfillment-pick-lists',        ARRAY['VIEW_PAGE','READ','UPDATE']),
        ('fulfillment-packages',          ARRAY['VIEW_PAGE','READ','CREATE']),
        ('fulfillment-shipments',         ARRAY['VIEW_PAGE','READ','CREATE','UPDATE']),
        ('fulfillment-return-shipments',  ARRAY['VIEW_PAGE','READ','CREATE']),
        -- Identity (platform admin)
        ('identity-users',                ARRAY['VIEW_PAGE','READ','CREATE','UPDATE']),
        ('identity-roles',                ARRAY['VIEW_PAGE','READ','CREATE','UPDATE']),
        ('identity-rbac',                 ARRAY['VIEW_PAGE','READ','APPROVE']),
        -- Reporting
        ('reporting-reports',             ARRAY['VIEW_PAGE','READ','EXPORT']),
        -- Chat
        ('chat-conversations',            ARRAY['VIEW_PAGE','READ','CREATE','UPDATE']),
        -- Notification
        ('notification-notifications',    ARRAY['VIEW_PAGE','READ','UPDATE'])
) AS r(res, actions)
CROSS JOIN LATERAL unnest(r.actions) AS act;


-- -----------------------------------------------------------------------------
-- 3. Default grants, per role.
--
--    Pattern per role:
--      (a) one BULK grant of the role's resources, restricted to the
--          non-sensitive actions -- this IS the skip-sensitive rule; the JOIN
--          only matches actions that exist for the resource, so listing an
--          action a resource does not have is harmless.
--      (b) zero or more EXPLICIT grants of the sensitive actions
--          (DELETE / APPROVE / EXPORT) the role's job genuinely needs.
--
--    Bulk grants never overlap explicit grants (non-sensitive vs sensitive), so
--    uk_role_permission (role_id, permission_id) is never violated.
-- -----------------------------------------------------------------------------

-- helper note: NON_SENSITIVE = ('VIEW_PAGE','READ','CREATE','UPDATE')

-- ---- WAREHOUSE_STAFF: floor operations, no approvals -------------------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('inventory-stock-items','inventory-reservations','warehouse-putaway-tasks',
                     'warehouse-replenishment','fulfillment-pick-lists','fulfillment-packages',
                     'fulfillment-shipments','fulfillment-return-shipments','procurement-goods-receipts')
 AND pe.action  IN ('VIEW_PAGE','READ','CREATE','UPDATE')
WHERE ro.code = 'WAREHOUSE_STAFF';

-- ---- WAREHOUSE_MANAGER: everything staff can do, plus approvals -------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('inventory-stock-items','inventory-reservations','inventory-transfer-orders',
                     'inventory-cycle-counts','inventory-stock-adjustments','warehouse-locations',
                     'warehouse-putaway-tasks','warehouse-replenishment','fulfillment-pick-lists',
                     'fulfillment-packages','fulfillment-shipments','fulfillment-return-shipments',
                     'procurement-goods-receipts')
 AND pe.action  IN ('VIEW_PAGE','READ','CREATE','UPDATE')
WHERE ro.code = 'WAREHOUSE_MANAGER';
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.code IN ('inventory-stock-items:APPROVE','inventory-transfer-orders:APPROVE',
                 'inventory-cycle-counts:APPROVE','inventory-stock-adjustments:APPROVE')
WHERE ro.code = 'WAREHOUSE_MANAGER';

-- ---- INVENTORY_PLANNER: monitor + plan transfers/counts --------------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('inventory-stock-items','inventory-stock-movements','inventory-allocations',
                     'inventory-transfer-orders','inventory-cycle-counts')
 AND pe.action  IN ('VIEW_PAGE','READ','CREATE','UPDATE')
WHERE ro.code = 'INVENTORY_PLANNER';
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.code IN ('inventory-stock-items:EXPORT','inventory-stock-movements:EXPORT')
WHERE ro.code = 'INVENTORY_PLANNER';

-- ---- QC_STAFF: inspect inbound + printed output ----------------------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('procurement-qc-tasks','procurement-goods-receipts','inventory-stock-items')
 AND pe.action  IN ('VIEW_PAGE','READ')
WHERE ro.code = 'QC_STAFF';
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.code IN ('procurement-qc-tasks:APPROVE','inventory-stock-items:APPROVE')
WHERE ro.code = 'QC_STAFF';

-- ---- CUSTOMER: storefront self-service (scope OWN, once the filter exists) --
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('sales-carts','sales-orders','sales-rmas','design-designs','design-snapshots',
                     'payment-payments','chat-conversations','notification-notifications')
 AND pe.action  IN ('VIEW_PAGE','READ','CREATE','UPDATE')
WHERE ro.code = 'CUSTOMER';

-- ---- SALES_STAFF: advise, assisted orders, edit designs --------------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('sales-carts','sales-orders','sales-rmas','design-designs','chat-conversations',
                     'product-products','payment-payments')
 AND pe.action  IN ('VIEW_PAGE','READ','CREATE','UPDATE')
WHERE ro.code = 'SALES_STAFF';

-- ---- ORDER_COORDINATOR: release orders, resolve holds ----------------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('sales-orders','sales-rmas','fulfillment-pick-lists','fulfillment-shipments')
 AND pe.action  IN ('VIEW_PAGE','READ','CREATE','UPDATE')
WHERE ro.code = 'ORDER_COORDINATOR';
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.code IN ('sales-orders:APPROVE','sales-orders:EXPORT','sales-rmas:APPROVE')
WHERE ro.code = 'ORDER_COORDINATOR';

-- ---- ECOMMERCE_ADMIN: catalog + platform administration --------------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('product-products','design-designs','reporting-reports',
                     'identity-users','identity-roles','identity-rbac')
 AND pe.action  IN ('VIEW_PAGE','READ','CREATE','UPDATE')
WHERE ro.code = 'ECOMMERCE_ADMIN';
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.code IN ('product-products:APPROVE','product-products:EXPORT',
                 'reporting-reports:EXPORT','identity-rbac:APPROVE')
WHERE ro.code = 'ECOMMERCE_ADMIN';

-- ---- PROCUREMENT_STAFF: purchase orders + supplier coordination ------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('procurement-purchase-orders','procurement-goods-receipts',
                     'procurement-qc-tasks','procurement-supplier-invoices')
 AND pe.action  IN ('VIEW_PAGE','READ','CREATE','UPDATE')
WHERE ro.code = 'PROCUREMENT_STAFF';
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.code IN ('procurement-purchase-orders:EXPORT')
WHERE ro.code = 'PROCUREMENT_STAFF';

-- ---- ACCOUNTANT: invoices, matching, reconciliation, payments --------------
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.resource IN ('procurement-supplier-invoices','payment-payments','payment-refunds',
                     'payment-settlements','payment-cod-remittances','reporting-reports')
 AND pe.action  IN ('VIEW_PAGE','READ','CREATE','UPDATE')
WHERE ro.code = 'ACCOUNTANT';
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), ro.id, pe.id, 0, NOW(), 'flyway'
FROM identity.app_role ro
JOIN identity.permission pe
  ON pe.code IN ('procurement-supplier-invoices:APPROVE','payment-payments:APPROVE',
                 'payment-refunds:APPROVE','reporting-reports:EXPORT')
WHERE ro.code = 'ACCOUNTANT';
