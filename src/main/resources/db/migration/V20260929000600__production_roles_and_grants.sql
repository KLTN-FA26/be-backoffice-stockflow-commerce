-- =============================================================================
-- Identity: who may do what in production (SCRUM-422), and two gaps in earlier grants.
-- Docs: kltn-docs 00-system-overview (roles), 19-production §2 (actors).
--
-- Resources, declared later by the production module's controllers (@PermissionResource code):
--   production-orders            the LSX: prepress, printing, scrap, QC, hold, split to subcontract
--   production-sample-requests   sample rounds: request, send to the customer, record the answer
-- Module-prefixed like every other code (inventory-..., procurement-...).
--
-- New role PRODUCTION_STAFF: runs the in-house print stations (19 §2).
--
-- Gaps found while testing SCRUM-424/326/435 with security on:
--   * WAREHOUSE_MANAGER could not read the stock ledger (inventory-stock-movements), the history of
--     the moves and adjustments it approves;
--   * WAREHOUSE_STAFF could not open, pick or dispatch a transfer order, the floor work of docs 10.
--
-- Grants are cached per role version (ADR-0008): every role whose grants change is advanced below.
-- SYSTEM_ADMIN gets every new permission (V20260929000200).
-- =============================================================================

INSERT INTO identity.app_role (id, code, name, description, version, created_at, created_by)
VALUES ('a0000001-0000-4000-8000-00000000000c', 'PRODUCTION_STAFF', 'Production Staff',
        'Run the print stations: check files, print, record good and scrapped units', 0, NOW(), 'flyway');

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), r.resource || ':' || a.action, r.resource, a.action, 0, NOW(), 'flyway'
FROM (VALUES
          ('production-orders',          'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE,EXPORT'),
          ('production-sample-requests', 'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE')
     ) AS r(resource, actions)
CROSS JOIN LATERAL unnest(string_to_array(r.actions, ',')) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          -- The print shop: records what it printed and scrapped; reads the samples it prints.
          ('PRODUCTION_STAFF',  'production-orders',          'VIEW_PAGE,READ,UPDATE'),
          ('PRODUCTION_STAFF',  'production-sample-requests', 'VIEW_PAGE,READ'),
          ('PRODUCTION_STAFF',  'inventory-stock-items',      'VIEW_PAGE,READ'),
          -- Coordinates the shop: holds, cancels a printed order, splits off a subcontracted part,
          -- approves the blanks reconciliation (19 §2).
          ('WAREHOUSE_MANAGER', 'production-orders',          'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE,EXPORT'),
          ('WAREHOUSE_MANAGER', 'production-sample-requests', 'VIEW_PAGE,READ'),
          ('WAREHOUSE_MANAGER', 'inventory-stock-movements',  'VIEW_PAGE,READ'),
          -- Issues blanks to the PRODUCTION area and moves finished goods to PACKING.
          ('WAREHOUSE_STAFF',   'production-orders',          'VIEW_PAGE,READ'),
          ('WAREHOUSE_STAFF',   'inventory-transfer-orders',  'VIEW_PAGE,READ,UPDATE'),
          -- Passes or fails printed output and samples.
          ('QC_STAFF',          'production-orders',          'VIEW_PAGE,READ,UPDATE'),
          ('QC_STAFF',          'production-sample-requests', 'VIEW_PAGE,READ,UPDATE'),
          -- Asks for samples, sends them, records the customer's answer; follows orders in production.
          ('SALES_STAFF',       'production-sample-requests', 'VIEW_PAGE,READ,CREATE,UPDATE'),
          ('SALES_STAFF',       'production-orders',          'VIEW_PAGE,READ'),
          -- Releases orders (the point an ORDER production order is born) and watches them.
          ('ORDER_COORDINATOR', 'production-orders',          'VIEW_PAGE,READ'),
          ('ORDER_COORDINATOR', 'production-sample-requests', 'VIEW_PAGE,READ'),
          -- Completes and sends the SUBCONTRACT purchase order a split raises.
          ('PROCUREMENT_STAFF', 'production-orders',          'VIEW_PAGE,READ')
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

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF', 'QC_STAFF', 'SALES_STAFF', 'ORDER_COORDINATOR',
               'PROCUREMENT_STAFF', 'SYSTEM_ADMIN');
