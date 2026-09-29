-- =============================================================================
-- Permission catalogue: the resources of the new tables, granted to the roles docs 00 names.
-- Module: identity   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase S
--
-- Seeded ahead of the code, as V20260903000100 already did for inventory-cycle-counts and others:
-- a permission row with no @PermissionResource yet is inert (PermissionCatalogValidator compares
-- the code with itself, not with this table), and it saves every member a migration when their
-- controller lands. The resource codes below are the contract: declare exactly these in
-- @PermissionResource(code = ...).
--
-- Resources the new tables need that ALREADY exist, and are therefore not repeated here:
-- inventory-stock-movements / -stock-adjustments / -cycle-counts / -transfer-orders,
-- warehouse-locations (the map: zones, shelves, bins, areas, boundaries, storage locations),
-- warehouse-putaway-tasks, warehouse-replenishment (move tasks), fulfillment-pick-lists /
-- -packages / -shipments, sales-carts, sales-rmas, payment-cod-remittances,
-- procurement-purchase-orders / -goods-receipts / -qc-tasks / -supplier-invoices.
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), r.resource || ':' || a.action, r.resource, a.action, 0, NOW(), 'flyway'
FROM (VALUES
          ('product-brands',                   'VIEW_PAGE,READ,CREATE,UPDATE,DELETE'),
          ('product-categories',               'VIEW_PAGE,READ,CREATE,UPDATE,DELETE'),
          ('product-attributes',               'VIEW_PAGE,READ,CREATE,UPDATE,DELETE'),
          ('product-variants',                 'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE,EXPORT'),
          ('product-media',                    'VIEW_PAGE,READ,CREATE,UPDATE,DELETE,APPROVE'),
          ('product-customization-templates',  'VIEW_PAGE,READ,CREATE,UPDATE,DELETE'),
          ('inventory-inventory-items',        'VIEW_PAGE,READ,CREATE,UPDATE,EXPORT'),
          ('warehouse-warehouses',             'VIEW_PAGE,READ,CREATE,UPDATE'),
          ('warehouse-locations',              'DELETE'),
          ('procurement-suppliers',            'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE,EXPORT'),
          ('procurement-supplier-items',       'VIEW_PAGE,READ,CREATE,UPDATE,DELETE'),
          ('procurement-replenishment-proposals', 'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE'),
          ('procurement-approval-limits',      'VIEW_PAGE,READ,CREATE,UPDATE')
     ) AS r(resource, actions)
CROSS JOIN LATERAL unnest(string_to_array(r.actions, ',')) AS a(action)
ON CONFLICT (code) DO NOTHING;


-- Grants. "all" = every action of the resource; otherwise the listed actions only.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          -- Catalogue master data is e-commerce administration; sales staff read it to sell.
          ('ECOMMERCE_ADMIN',   'product-brands',                  'all'),
          ('ECOMMERCE_ADMIN',   'product-categories',              'all'),
          ('ECOMMERCE_ADMIN',   'product-attributes',              'all'),
          ('ECOMMERCE_ADMIN',   'product-variants',                'all'),
          ('ECOMMERCE_ADMIN',   'product-media',                   'all'),
          ('ECOMMERCE_ADMIN',   'product-customization-templates', 'all'),
          ('SALES_STAFF',       'product-brands',                  'VIEW_PAGE,READ'),
          ('SALES_STAFF',       'product-categories',              'VIEW_PAGE,READ'),
          ('SALES_STAFF',       'product-attributes',              'VIEW_PAGE,READ'),
          ('SALES_STAFF',       'product-variants',                'VIEW_PAGE,READ,CREATE,UPDATE'),
          -- Staff edit the gallery; publishing it (APPROVE) stays with the admin.
          ('SALES_STAFF',       'product-media',                   'VIEW_PAGE,READ,CREATE,UPDATE'),
          ('QC_STAFF',          'product-customization-templates', 'VIEW_PAGE,READ'),
          -- Logistics data of a SKU belongs to the warehouse side (docs 01 BR-08).
          ('WAREHOUSE_MANAGER', 'inventory-inventory-items',       'all'),
          ('INVENTORY_PLANNER', 'inventory-inventory-items',       'all'),
          ('WAREHOUSE_STAFF',   'inventory-inventory-items',       'VIEW_PAGE,READ'),
          ('PROCUREMENT_STAFF', 'inventory-inventory-items',       'VIEW_PAGE,READ'),
          ('WAREHOUSE_MANAGER', 'warehouse-warehouses',            'all'),
          ('WAREHOUSE_STAFF',   'warehouse-warehouses',            'VIEW_PAGE,READ'),
          ('INVENTORY_PLANNER', 'warehouse-warehouses',            'VIEW_PAGE,READ'),
          ('PROCUREMENT_STAFF', 'warehouse-warehouses',            'VIEW_PAGE,READ'),
          ('WAREHOUSE_MANAGER', 'warehouse-locations',             'DELETE'),
          ('WAREHOUSE_STAFF',   'warehouse-locations',             'VIEW_PAGE,READ'),
          -- Purchasing.
          ('PROCUREMENT_STAFF', 'procurement-suppliers',           'VIEW_PAGE,READ,CREATE,UPDATE,EXPORT'),
          ('PROCUREMENT_STAFF', 'procurement-supplier-items',      'all'),
          ('PROCUREMENT_STAFF', 'procurement-replenishment-proposals', 'VIEW_PAGE,READ,CREATE,UPDATE'),
          ('INVENTORY_PLANNER', 'procurement-replenishment-proposals', 'all'),
          ('ACCOUNTANT',        'procurement-suppliers',           'VIEW_PAGE,READ'),
          -- Blacklisting a supplier and setting approval limits are management decisions.
          ('ECOMMERCE_ADMIN',   'procurement-suppliers',           'all'),
          ('ECOMMERCE_ADMIN',   'procurement-approval-limits',     'all'),
          ('PROCUREMENT_STAFF', 'procurement-approval-limits',     'VIEW_PAGE,READ')
     ) AS g(role_code, resource, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = g.resource
 AND (g.actions = 'all' OR permission.action = ANY (string_to_array(g.actions, ',')))
ON CONFLICT (role_id, permission_id) DO NOTHING;
