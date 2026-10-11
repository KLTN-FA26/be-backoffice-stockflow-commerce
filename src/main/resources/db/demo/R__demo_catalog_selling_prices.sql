-- Sample selling prices for the two canonical demo products, NOT business quotations.
-- Loaded only with classpath:db/demo (local/test or an explicitly selected demo database).
-- Repeatable so already-migrated demo databases receive it without changing any old checksum.
-- Exact seed identities prevent applying sample prices to unrelated products with similar SKUs.
-- Serialize with catalog price edits, which also lock the canonical product first.
SELECT id FROM product.products
WHERE id IN (md5('demo:product:SOFA-3S')::uuid, md5('demo:product:TABLE-OAK')::uuid)
ORDER BY id FOR UPDATE;

INSERT INTO catalog.pricing_rule
    (id, name, sku, price, currency, priority, active, version, created_at, created_by)
SELECT md5('demo:catalog:base-price:' || sample.sku)::uuid,
       'BASE:' || sample.sku, sample.sku, sample.price, 'VND', 0, true, 0, now(), 'demo-seed'
FROM (VALUES
    ('SOFA-3S', 'SOFA-3S-GREY', 12500000),
    ('TABLE-OAK', 'TABLE-OAK-160', 8000000)
) AS sample(product_code, sku, price)
JOIN product.products p ON p.id = md5('demo:product:' || sample.product_code)::uuid
    AND p.code = sample.product_code AND p.created_by = 'flyway'
JOIN product.variants v ON v.id = md5('demo:variant:' || sample.sku)::uuid
    AND v.product_id = p.id AND v.sku = sample.sku AND v.created_by = 'flyway'
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.pricing_rule r
    WHERE r.name = 'BASE:' || sample.sku
       OR (r.segment_id IS NULL AND (r.sku = sample.sku OR (r.sku IS NULL AND r.active)))
)
AND NOT EXISTS (
    SELECT 1 FROM catalog.catalog_entry e WHERE e.sku = sample.sku AND e.price IS NOT NULL
)
ON CONFLICT DO NOTHING;
