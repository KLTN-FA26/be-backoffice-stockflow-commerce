-- =============================================================================
-- Customer-group (segment) prices, managed (user decision P7, 2026-10-12).
--
-- E-commerce Admin and System Admin manage the price of a SKU for a customer group (kltn-docs 13 §2,
-- 18 §4.3 step 10); Sales Staff and the Order Coordinator read them, and Sales negotiates per quote
-- instead of editing a group's price. A group price is a catalog.pricing_rule row with a segment and a
-- SKU - the shape PriceResolver already reads between the customer's own tiers and the base price - and
-- applies from one unit. Nothing is added to the table: one price per group and SKU, positive whole VND,
-- like every selling price, stated here as well as in the aggregate.
-- =============================================================================

CREATE UNIQUE INDEX uk_pricing_rule_segment_sku ON catalog.pricing_rule (segment_id, sku)
    WHERE segment_id IS NOT NULL AND sku IS NOT NULL;

ALTER TABLE catalog.pricing_rule ADD CONSTRAINT ck_pricing_rule_segment_vnd CHECK
    (segment_id IS NULL OR (currency = 'VND' AND price > 0 AND price = trunc(price)));

-- ---- permissions ------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'catalog-segment-prices:' || a.action, 'catalog-segment-prices', a.action, 0, NOW(), 'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE', 'UPDATE', 'DELETE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('ECOMMERCE_ADMIN',   'VIEW_PAGE,READ,CREATE,UPDATE,DELETE'),
          ('SALES_STAFF',       'VIEW_PAGE,READ'),
          ('ORDER_COORDINATOR', 'READ')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'catalog-segment-prices'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
  AND permission.resource = 'catalog-segment-prices'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('ECOMMERCE_ADMIN', 'SALES_STAFF', 'ORDER_COORDINATOR', 'SYSTEM_ADMIN');
