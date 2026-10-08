-- Read-only psql checks, before upgrading to the combined #36/#38 release.
-- Run with: psql -X -v ON_ERROR_STOP=1 -f inventory-commerce-upgrade-preflight.sql
-- History inventory is informational; rows labelled problem require reconciliation.
SELECT version, script, checksum, success FROM public.flyway_schema_history
WHERE version IN ('20260930000500','20260930001000','20260930001100','20261008000100')
ORDER BY installed_rank;
SELECT 'OLD_PR38_MISSING_PO_REVIEW_00500' AS problem
WHERE EXISTS (SELECT 1 FROM public.flyway_schema_history
              WHERE success AND version='20261008000100'
                AND script='V20261008000100__canonical_inventory_and_ecommerce.sql')
  AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history
                  WHERE success AND version='20260930000500');
SELECT 'UNPUBLISHED_PO_VERSION_COLLIDES_WITH_CANONICAL_38' AS problem, version, script
FROM public.flyway_schema_history
WHERE success AND version='20261008000100'
  AND script<>'V20261008000100__canonical_inventory_and_ecommerce.sql';
SELECT version, description FROM public.flyway_schema_history
WHERE success AND version IN ('20260930001200','20260930001300','20260930001400','20260930001500','20260930001600');
-- These removed, out-of-scope extension versions need a schema-owner upgrade plan, not Flyway repair.

-- Canonical cutover drops the private policy table and the duplicate SKU fields. Skip those
-- checks when already cut over; never query removed columns on an old remote #38 database.
SELECT to_regclass('inventory.sku_policy') IS NOT NULL AS has_legacy_policy \gset
\if :has_legacy_policy
SELECT s.code, 'DIVERGENT_LEGACY_POLICY' AS problem
FROM product.sku s LEFT JOIN inventory.sku_policy p ON p.sku=s.code
WHERE (p.sku IS NULL AND (s.reorder_point IS NOT NULL OR s.safety_stock IS NOT NULL
       OR s.removal_strategy<>'FEFO' OR s.serial_tracked OR s.expiry_tracked
       OR s.max_shelf_life_days IS NOT NULL))
   OR (p.sku IS NOT NULL AND
       (s.reorder_point IS DISTINCT FROM p.reorder_point
        OR s.safety_stock IS DISTINCT FROM p.safety_stock
        OR s.removal_strategy IS DISTINCT FROM p.removal_strategy
        OR s.lot_tracked IS DISTINCT FROM (p.tracking_mode='LOT')
        OR s.serial_tracked IS DISTINCT FROM (p.tracking_mode='SERIAL')
        OR s.expiry_tracked IS DISTINCT FROM p.expiry_tracked
        OR s.max_shelf_life_days IS DISTINCT FROM p.max_shelf_life_days));
SELECT p.sku, 'MISSING_CANONICAL_ITEM' AS problem
FROM inventory.sku_policy p LEFT JOIN inventory.inventory_items i ON i.sku=p.sku WHERE i.id IS NULL;
SELECT p.sku, 'CONFLICTING_POLICY' AS problem
FROM inventory.sku_policy p JOIN inventory.inventory_items i ON i.sku=p.sku
WHERE (i.reorder_point IS NOT NULL AND i.reorder_point IS DISTINCT FROM p.reorder_point)
   OR i.lot_tracked IS DISTINCT FROM (p.tracking_mode='LOT')
   OR i.serial_tracked IS DISTINCT FROM (p.tracking_mode='SERIAL')
   OR i.expiry_tracked IS DISTINCT FROM p.expiry_tracked
   OR (p.expiry_tracked AND p.tracking_mode<>'LOT');
SELECT l.product_id, 'MISSING_CANONICAL_PRODUCT' AS problem
FROM catalog.product_listing l LEFT JOIN product.products p ON p.id=l.product_id WHERE p.id IS NULL;
SELECT l.product_id, p.slug AS canonical_slug, l.slug AS old_slug, 'CONFLICTING_SEO' AS problem
FROM catalog.product_listing l JOIN product.products p ON p.id=l.product_id
WHERE p.slug IS DISTINCT FROM l.slug
   OR (p.seo_title IS NOT NULL AND p.seo_title IS DISTINCT FROM l.seo_title)
   OR (p.seo_description IS NOT NULL AND p.seo_description IS DISTINCT FROM l.seo_description)
   OR length(l.seo_title)>255;
\else
\echo Private policy table absent; legacy-source checks skipped. Inspect the history inventory above.
\endif
