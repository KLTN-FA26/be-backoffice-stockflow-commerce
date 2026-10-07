-- Read-only checks for databases that ran PR #38 before the canonical cutover.
-- Run before V20261008000100; empty result sets mean no listed blocker. No data is changed.
SELECT version, description FROM public.flyway_schema_history
WHERE success AND version IN ('20260930001200','20260930001300','20260930001400','20260930001500','20260930001600');
-- These removed, out-of-scope extension versions need a schema-owner upgrade plan, not Flyway repair.

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
