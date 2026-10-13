-- =============================================================================
-- 1. Inventory: a stock adjustment may name the receipt layer it corrects (SCRUM-398, decision Q4 2026-10-12).
--
-- An adjustment found its stock by (SKU, location, lot) and refused when several layers matched
-- (INVENTORY_POLICY_STOCK_CONFLICT). The print shop holds layers of the same lot received at different times,
-- each issued from its own storage layer, and a write-off of spoilt units must hit the layer they are on -
-- or its approval could not find it. NULL keeps the old behaviour: the only layer of the lot.
-- =============================================================================

ALTER TABLE inventory.stock_adjustment ADD COLUMN received_at TIMESTAMPTZ;


-- =============================================================================
-- Identity: QC of printed goods is a resource of its own (SCRUM-399; 03 §16: QC → COMPLETED / READY, actor
-- QC staff). Until now QC_STAFF recorded nothing but held production-orders:UPDATE, which is also prepress,
-- start printing, record output and hold - steps whose actor is Production staff. With its own resource QC
-- keeps exactly its step and loses UPDATE on the production order (READ stays).
--   QC_STAFF           production-qc-results READ, CREATE; production-orders - UPDATE
--   WAREHOUSE_MANAGER  production-qc-results READ, CREATE
--   PRODUCTION_STAFF   production-qc-results READ
--   SALES_STAFF        production-qc-results READ        follows the orders it sold
--   SYSTEM_ADMIN       every permission (V20260929000200)
-- Grants are cached per role version (ADR-0008): every role whose grants change is advanced.
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'production-qc-results:' || a.action, 'production-qc-results', a.action, 0, NOW(), 'flyway'
FROM unnest(ARRAY['READ', 'CREATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('QC_STAFF',          'READ,CREATE'),
          ('WAREHOUSE_MANAGER', 'READ,CREATE'),
          ('PRODUCTION_STAFF',  'READ'),
          ('SALES_STAFF',       'READ'),
          ('SYSTEM_ADMIN',      'READ,CREATE')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'production-qc-results'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

DELETE FROM identity.role_permission rp
USING identity.app_role role, identity.permission permission
WHERE rp.role_id = role.id
  AND rp.permission_id = permission.id
  AND role.code = 'QC_STAFF'
  AND permission.resource = 'production-orders'
  AND permission.action = 'UPDATE';

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('QC_STAFF', 'WAREHOUSE_MANAGER', 'PRODUCTION_STAFF', 'SALES_STAFF', 'SYSTEM_ADMIN');
