-- =============================================================================
-- Demo: the standard price of the demo cups (kltn-docs 13 BR-04 and 14 BR-10 as changed on 2026-11-26).
--
-- The Sales Manager keeps a standard price per variant; a quote line starts from the customer's own price, else from it,
-- and a quote priced more than 10% below that reference - or with none - waits for the Sales Manager. The demo
-- customers' own prices of the 12 oz paper cup run 1 050 - 1 350 VND a piece; the standard price sits at the same level,
-- for a usual print, so a demo quote at the customers' prices is sent and one priced well below waits for approval.
-- The bamboo cup exists only where demo-wf05 ran; the paper box has no standard price on purpose (a design on it waits
-- for approval: there is no reference to measure against).
-- =============================================================================

INSERT INTO catalog.standard_price (id, sku, currency, note, version, created_at, created_by)
SELECT md5('demo:standard-price:' || v.sku)::uuid, v.sku, 'VND', 'Giá chuẩn cho mẫu in thông thường (1-2 màu)', 0,
       NOW() - INTERVAL '30 days', 'flyway'
  FROM product.variants v
 WHERE v.sku IN ('LY-GIAY-DEMO-12OZ', 'LY-TRE-DEMO-12OZ') AND v.owner_customer_id IS NULL
ON CONFLICT (sku) DO NOTHING;

INSERT INTO catalog.standard_price_tier (id, standard_price_id, min_quantity, unit_price, version, created_at, created_by)
SELECT md5('demo:standard-price-tier:' || p.sku || ':' || t.min_quantity)::uuid, p.id, t.min_quantity,
       CASE WHEN p.sku = 'LY-TRE-DEMO-12OZ' THEN t.unit_price + 600 ELSE t.unit_price END, 0,
       NOW() - INTERVAL '30 days', 'flyway'
  FROM catalog.standard_price p
 CROSS JOIN (VALUES (10, 1300), (20, 1250), (50, 1150), (100, 1050)) AS t (min_quantity, unit_price)
 WHERE p.sku IN ('LY-GIAY-DEMO-12OZ', 'LY-TRE-DEMO-12OZ')
ON CONFLICT (standard_price_id, min_quantity) DO NOTHING;
