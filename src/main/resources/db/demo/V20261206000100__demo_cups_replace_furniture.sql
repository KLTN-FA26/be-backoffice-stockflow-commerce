-- =============================================================================
-- Demo: printed cups and paper boxes take the place of the furniture (2026-11-19, one carton spec per SKU, part 1).
-- Demo only (db/demo). Ids are md5('demo:cups:...')::uuid (DemoData.demoId computes the same).
--
-- The demo seed (V20261111000001) still carried the two SKUs of before the rescope, SOFA-3S-GREY and TABLE-OAK-160,
-- sold one piece a carton (packing unit CARTON, factor 1), with their products SOFA-3S and TABLE-OAK. A carton of
-- one unit is not a carton of printed goods, and the carton spec is about to require more than one unit a carton
-- (owner, 2026-11-19), so they go. What the tests and QA scripts leant on is kept, in the same place and shape:
--
--   was                                                       now
--   SOFA-3S-GREY  25 cartons HCM-A01-2-B, 8 HCM-A02-1-A,      LY-GIAY-DEMO-12OZ-XANH, the demo customer's cup of WF02:
--                 lot UNTRACED, AVAILABLE, LPN ...000001-033   1,000 cups a THUNG of 60 x 40 x 50 cm, 8.6 kg
--   TABLE-OAK-160 12 cartons HCM-B01-1-A lot LOT-2026-03       LY-GIAY-DEMO-12OZ-DO, a second cup of the demo customer
--                 AVAILABLE (...034-045), 30 HCM-QC01 lot      on its own approved design: 500 cups a THUNG of
--                 LOT-2026-04 QUARANTINE (...046-075),        70 x 45 x 55 cm, 9.5 kg, not rotatable
--                 not rotatable
--   product SOFA-3S, published, axes COLOR / MATERIAL         product HOP-GIAY-DEMO "Hộp giấy in logo", published,
--                                                             axes COLOR (GREY, BLUE) and MATERIAL (PAPER), two
--                                                             template variants with no stock and no carton unit
--
-- Same LPN numbers, lots, statuses and bins, so a test changes its SKU string and multiplies its units, not its
-- cartons. Each carton has its opening ledger line, as V20261128000100 gave the furniture's (open-questions C45), so
-- the ledger and the shelves still agree (tools/db/qa/99_stock_ledger_balance.sql). The four empty stock rows the
-- furniture kept where its loose stock was boxed (11111111-..., 22222222-..., 33333333-..., 44444444-...) are made
-- again for the cups, at the same places, with ids of their own.
-- The two zones named after the furniture ('Khu A - Sofa', 'Khu B - Bàn') take the cups' names (section 6).
--
-- Order: the catalogue is added first; the furniture's stock leaves before the cups' stock comes in, because the cups
-- take the very LPNs (uk_stock_package_lpn) and a carton's LPN cannot be renamed (tg_stock_package_immutable); the
-- furniture's catalogue leaves last, and the barcode it frees is given to the red cup. The ledger is append-only and a
-- published product is never deleted by the application (01 BR-02): those two triggers stand aside for the delete
-- and are on again in this file, as V20261121000200 does for the ledger. Loaded with every other trigger on.
-- =============================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. The paper box: a second printable product, with variant axes
-- ---------------------------------------------------------------------------------------------
INSERT INTO product.attribute_values (id, attribute_id, code, label, hex_color, sort_order, is_active, version,
                                      created_at, created_by)
SELECT md5('demo:cups:attribute-value:' || a.code || ':' || v.code)::uuid, a.id, v.code, v.label, v.hex, v.sort, TRUE,
       0, NOW() - INTERVAL '28 days', 'flyway'
  FROM (VALUES ('COLOR', 'BLUE', 'Xanh dương', '#2F6FD0', 1),
               ('MATERIAL', 'PAPER', 'Giấy', NULL, 0)) AS v (attribute, code, label, hex, sort)
  JOIN product.attributes a ON a.code = v.attribute;

INSERT INTO product.categories (id, parent_id, code, name, slug, path, depth, sort_order, is_active, version,
                                created_at, created_by)
VALUES (md5('demo:cups:category:HOP_GIAY')::uuid, NULL, 'HOP_GIAY', 'Hộp giấy in logo', 'hop-giay-in-logo',
        '/HOP_GIAY', 0, 1, TRUE, 0, NOW() - INTERVAL '28 days', 'flyway');

INSERT INTO product.category_attributes (id, category_id, attribute_id, default_role, is_required, sort_order, version,
                                         created_at, created_by)
SELECT md5('demo:cups:category-attribute:HOP_GIAY:' || a.code)::uuid, md5('demo:cups:category:HOP_GIAY')::uuid, a.id,
       'VARIANT_AXIS', TRUE, x.sort, 0, NOW() - INTERVAL '28 days', 'flyway'
  FROM (VALUES ('COLOR', 0), ('MATERIAL', 1)) AS x (attribute, sort)
  JOIN product.attributes a ON a.code = x.attribute;

INSERT INTO product.products (id, code, name, slug, short_description, tax_class, kind, status, submitted_by,
                              submitted_at, approved_by, approved_at, published_at, ever_published, version, created_at,
                              created_by)
VALUES (md5('demo:cups:product:HOP-GIAY-DEMO')::uuid, 'HOP-GIAY-DEMO', 'Hộp giấy in logo', 'hop-giay-demo',
        'Hộp giấy đựng đồ mang đi, in theo thiết kế của khách hàng.', 'STANDARD', 'CUSTOMIZABLE', 'PUBLISHED',
        md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '28 days', '78608dc8-b899-0fc5-96b0-d181d2488ae8',
        NOW() - INTERVAL '27 days', NOW() - INTERVAL '27 days', TRUE, 0, NOW() - INTERVAL '28 days', 'flyway');

INSERT INTO product.product_categories (id, product_id, category_id, is_primary, version, created_at, created_by)
VALUES (md5('demo:cups:product-category:HOP-GIAY-DEMO')::uuid, md5('demo:cups:product:HOP-GIAY-DEMO')::uuid,
        md5('demo:cups:category:HOP_GIAY')::uuid, TRUE, 0, NOW() - INTERVAL '28 days', 'flyway');

INSERT INTO product.product_attributes (id, product_id, attribute_id, role, sort_order, version, created_at, created_by)
SELECT md5('demo:cups:product-attribute:HOP-GIAY-DEMO:' || a.code)::uuid, md5('demo:cups:product:HOP-GIAY-DEMO')::uuid,
       a.id, 'VARIANT_AXIS', x.sort, 0, NOW() - INTERVAL '28 days', 'flyway'
  FROM (VALUES ('COLOR', 0), ('MATERIAL', 1)) AS x (attribute, sort)
  JOIN product.attributes a ON a.code = x.attribute;

INSERT INTO product.product_attribute_options (id, product_attribute_id, attribute_value_id, version, created_at,
                                               created_by)
SELECT md5('demo:cups:option:HOP-GIAY-DEMO:' || a.code || ':' || v.code)::uuid,
       md5('demo:cups:product-attribute:HOP-GIAY-DEMO:' || a.code)::uuid, v.id, 0, NOW() - INTERVAL '28 days', 'flyway'
  FROM (VALUES ('COLOR', 'GREY'), ('COLOR', 'BLUE'), ('MATERIAL', 'PAPER')) AS x (attribute, value)
  JOIN product.attributes a ON a.code = x.attribute
  JOIN product.attribute_values v ON v.attribute_id = a.id AND v.code = x.value;

-- Two template variants (quy cách); tg_variant_inventory_item makes their inventory items and base units. No carton
-- unit and no stock: nothing is printed on a template, only on a customer's SKU drawn from it.
CREATE TEMP TABLE c_box_variant (color TEXT, color_label TEXT, is_default BOOLEAN, position INTEGER) ON COMMIT DROP;
INSERT INTO c_box_variant VALUES ('GREY', 'Xám', TRUE, 0), ('BLUE', 'Xanh dương', FALSE, 1);

INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, created_by)
SELECT md5('demo:cups:variant:HOP-GIAY-DEMO-' || color || '-PAPER')::uuid, md5('demo:cups:product:HOP-GIAY-DEMO')::uuid,
       'HOP-GIAY-DEMO-' || color || '-PAPER', 'Hộp giấy in logo - ' || color_label || ' - Giấy', 'ACTIVE', is_default,
       'COLOR=' || color || ';MATERIAL=PAPER', position, 0, NOW() - INTERVAL '28 days', 'flyway'
  FROM c_box_variant;

INSERT INTO product.variant_attribute_values (id, variant_id, product_attribute_option_id, version, created_at,
                                              created_by)
SELECT md5('demo:cups:variant-value:HOP-GIAY-DEMO-' || b.color || '-PAPER:' || x.attribute)::uuid,
       md5('demo:cups:variant:HOP-GIAY-DEMO-' || b.color || '-PAPER')::uuid,
       md5('demo:cups:option:HOP-GIAY-DEMO:' || x.attribute || ':' || x.value)::uuid, 0, NOW() - INTERVAL '28 days',
       'flyway'
  FROM c_box_variant b
 CROSS JOIN LATERAL (VALUES ('COLOR', b.color), ('MATERIAL', 'PAPER')) AS x (attribute, value);

-- The catalog shows a published product only with a category and an image approved by someone other than its
-- uploader (as V20261124000600 says of the cup).
INSERT INTO product.media (id, variant_id, kind, url, renditions, alt_text, sort_order, is_primary, is_published,
                           published_at, published_by, version, created_at, created_by)
SELECT md5('demo:cups:media:HOP-GIAY-DEMO-' || color || '-PAPER')::uuid,
       md5('demo:cups:variant:HOP-GIAY-DEMO-' || color || '-PAPER')::uuid, 'IMAGE',
       'https://placehold.co/800x600?text=HOP-GIAY-DEMO-' || color || '-PAPER', '[]',
       'Hộp giấy in logo màu ' || lower(color_label), 0, TRUE, TRUE, NOW() - INTERVAL '27 days',
       '78608dc8-b899-0fc5-96b0-d181d2488ae8', 0, NOW() - INTERVAL '28 days', 'flyway'
  FROM c_box_variant;

-- ---------------------------------------------------------------------------------------------
-- 2. The red cup: the demo customer's second design on WF02's blank and the SKU issued on it (12 §4.5; 01 BR-03),
--    made as V20261124000600 makes the blue one
-- ---------------------------------------------------------------------------------------------
INSERT INTO design.design_draft (id, customer_id, product_id, name, spec, status, owner_user_id, reviewed_by,
                                 review_notes, current_artifacts, variant_id, sku, template_id, template_code,
                                 review_requested_at, reviewed_at, version, created_at, created_by)
VALUES (md5('demo:cups:design:DO')::uuid, 'c0000000-0000-4000-8000-000000000001',
        md5('demo:wf02:product:LY-GIAY-DEMO')::uuid, 'Ly giấy 12oz logo đỏ',
        'Logo đỏ giữa thân ly, 2 màu Pantone', 'APPROVED', md5('demo:wf02:user:demo.khachhang')::uuid,
        md5('demo:wf02:user:demo.sales')::uuid, 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', '{}'::jsonb,
        md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 'LY-GIAY-DEMO-12OZ',
        md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid, 'STD', NOW() - INTERVAL '25 days',
        NOW() - INTERVAL '24 days', 0, NOW() - INTERVAL '26 days', 'flyway');

INSERT INTO design.design_decision (id, draft_id, actor_id, decision, notes, draft_version, version, created_at,
                                    created_by)
VALUES (md5('demo:cups:design-decision:DO:0')::uuid, md5('demo:cups:design:DO')::uuid,
        md5('demo:wf02:user:demo.khachhang')::uuid, 'CREATED', 'Tạo thiết kế', 0, 0, NOW() - INTERVAL '26 days',
        'flyway'),
       (md5('demo:cups:design-decision:DO:1')::uuid, md5('demo:cups:design:DO')::uuid,
        md5('demo:wf02:user:demo.khachhang')::uuid, 'SUBMITTED_FOR_REVIEW', 'Gửi duyệt thiết kế', 1, 0,
        NOW() - INTERVAL '25 days', 'flyway'),
       (md5('demo:cups:design-decision:DO:2')::uuid, md5('demo:cups:design:DO')::uuid,
        md5('demo:wf02:user:demo.sales')::uuid, 'APPROVED', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', 2, 0,
        NOW() - INTERVAL '24 days', 'flyway');

-- Its print file, as the studio stored it: without a primary artifact the snapshot is not the design's approved one
-- (DesignSnapshotJpaRepository.snapshotsOf). db/demo carries no files, so downloading it answers not found.
INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, size_bytes, stored_at,
                                    checksum, artifact_role, version, created_at, created_by)
VALUES (md5('demo:cups:artifact:DO:PRINT_READY')::uuid, md5('demo:cups:design:DO')::uuid,
        'design-renders/demo/ly-giay-12oz-logo-do.pdf', 'print_ready.pdf', 'application/pdf', 47906,
        NOW() - INTERVAL '25 days', encode(sha256(convert_to('demo:cups:artifact:DO:PRINT_READY', 'UTF8')), 'hex'),
        'PRINT_READY', 0, NOW() - INTERVAL '25 days', 'flyway');

UPDATE design.design_draft
   SET current_artifact_id = md5('demo:cups:artifact:DO:PRINT_READY')::uuid,
       current_artifacts = jsonb_build_object('PRINT_READY', md5('demo:cups:artifact:DO:PRINT_READY')::uuid)
 WHERE id = md5('demo:cups:design:DO')::uuid;

-- The snapshot's checksum is the manifest's (DesignSnapshotJpaEntity.manifestChecksum), as V20261124000600 computes it.
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, artifact_id, confirmed_by,
                                    reviewed_by, spec, review_notes, artifact_manifest, version, created_at, created_by)
SELECT md5('demo:cups:snapshot:DO')::uuid, a.draft_id,
       encode(sha256(convert_to('PRINT_READY:' || a.id || ':' || a.checksum || E'\n', 'UTF8')), 'hex'), a.storage_key,
       NOW() - INTERVAL '24 days', a.id, md5('demo:wf02:user:demo.khachhang')::uuid,
       md5('demo:wf02:user:demo.sales')::uuid, 'Logo đỏ giữa thân ly, 2 màu Pantone',
       'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm',
       jsonb_build_array(jsonb_build_object('artifactId', a.id, 'role', a.artifact_role, 'storageKey', a.storage_key,
                                            'originalName', a.original_name, 'contentType', a.content_type,
                                            'sizeBytes', a.size_bytes, 'checksum', a.checksum)),
       0, NOW() - INTERVAL '24 days', 'flyway'
  FROM design.design_artifact a
 WHERE a.id = md5('demo:cups:artifact:DO:PRINT_READY')::uuid;

-- tg_variant_inventory_item makes its inventory item, as the catalogue makes it.
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                              owner_customer_id, design_snapshot_id, source_variant_id, version, created_at, created_by)
VALUES (md5('demo:cups:variant:LY-GIAY-DEMO-12OZ-DO')::uuid, md5('demo:wf02:product:LY-GIAY-DEMO')::uuid,
        'LY-GIAY-DEMO-12OZ-DO', 'Ly giấy 2 lớp in logo - 12 oz - logo đỏ', 'ACTIVE', FALSE,
        'OWNED-SKU:LY-GIAY-DEMO-12OZ-DO', 11, 'c0000000-0000-4000-8000-000000000001',
        md5('demo:cups:snapshot:DO')::uuid, md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 0,
        NOW() - INTERVAL '24 days', 'flyway');

-- A cup is a cup; its carton does not turn on the shelf (the table it replaces did not either). The barcode is given
-- in step 5, once the sofa has let it go (uk_inventory_items_barcode).
UPDATE inventory.inventory_items SET weight_kg = 0.008, length_cm = 9, width_cm = 9, height_cm = 11, rotatable = FALSE
 WHERE sku = 'LY-GIAY-DEMO-12OZ-DO';

-- Its carton: 500 cups, 70 x 45 x 55 cm, 9.5 kg (01 BR-03) - the unit it ships in and the only one it is sold in.
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, weight_kg, length_cm,
                                       width_cm, height_cm, default_purchase, active, default_shipping, version,
                                       created_at, created_by)
SELECT md5('demo:cups:unit:LY-GIAY-DEMO-12OZ-DO')::uuid, i.id, 'THUNG', 500, FALSE, 9.5, 70, 45, 55, FALSE, TRUE, TRUE,
       0, NOW() - INTERVAL '24 days', 'flyway'
  FROM inventory.inventory_items i
 WHERE i.sku = 'LY-GIAY-DEMO-12OZ-DO';

INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id, version, created_at, created_by)
VALUES (md5('demo:cups:sales-unit:LY-GIAY-DEMO-12OZ-DO')::uuid, md5('demo:cups:variant:LY-GIAY-DEMO-12OZ-DO')::uuid,
        md5('demo:cups:unit:LY-GIAY-DEMO-12OZ-DO')::uuid, 0, NOW() - INTERVAL '24 days', 'flyway');

-- ---------------------------------------------------------------------------------------------
-- 3. The furniture's stock leaves: its ledger lines, stock rows, cartons and lots
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE c_furniture (sku TEXT PRIMARY KEY) ON COMMIT DROP;
INSERT INTO c_furniture VALUES ('SOFA-3S-GREY'), ('TABLE-OAK-160');

ALTER TABLE inventory.stock_movement DISABLE TRIGGER tg_stock_movement_append_only;
DELETE FROM inventory.stock_movement WHERE sku IN (SELECT sku FROM c_furniture);
ALTER TABLE inventory.stock_movement ENABLE TRIGGER tg_stock_movement_append_only;

DELETE FROM inventory.stock_item WHERE sku IN (SELECT sku FROM c_furniture);

DELETE FROM inventory.stock_package p
 USING inventory.inventory_items i
 WHERE i.id = p.inventory_item_id AND i.sku IN (SELECT sku FROM c_furniture);

DELETE FROM inventory.stock_lot WHERE sku IN (SELECT sku FROM c_furniture);

-- ---------------------------------------------------------------------------------------------
-- 4. The cups' stock comes in where the furniture stood: same LPNs, bins, lots and statuses
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE c_stock (first_lpn INTEGER, last_lpn INTEGER, sku TEXT, location_code TEXT, lot_number TEXT,
                           status TEXT) ON COMMIT DROP;
INSERT INTO c_stock VALUES
    (1, 25, 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'UNTRACED', 'AVAILABLE'),
    (26, 33, 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A02-1-A', 'UNTRACED', 'AVAILABLE'),
    (34, 45, 'LY-GIAY-DEMO-12OZ-DO', 'HCM-B01-1-A', 'LOT-2026-03', 'AVAILABLE'),
    (46, 75, 'LY-GIAY-DEMO-12OZ-DO', 'HCM-QC01', 'LOT-2026-04', 'QUARANTINE');

-- One carton a row, with the SKU's carton unit (THUNG: 1,000 blue cups, 500 red).
CREATE TEMP TABLE c_carton ON COMMIT DROP AS
SELECT 'LPN-20261111-' || lpad(n::text, 6, '0') AS lpn, s.sku, s.location_code, s.lot_number, s.status, i.id AS item_id,
       u.id AS unit_id, u.factor
  FROM c_stock s
 CROSS JOIN LATERAL generate_series(s.first_lpn, s.last_lpn) AS n
  JOIN inventory.inventory_items i ON i.sku = s.sku
  JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id AND u.unit_of_measure = 'THUNG';

-- Opening stock, as the furniture's was: an UNTRACED lot with no receipt date (kltn-docs 20 §4.7 step 3, C45).
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, version, created_at, created_by)
SELECT DISTINCT md5('demo:cups:lot:' || sku || ':' || lot_number)::uuid, sku, lot_number, NULL::timestamptz,
       'UNTRACED', 0, TIMESTAMPTZ '2026-11-11 13:19:30+00', 'flyway'
  FROM c_stock;

INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status, version, created_at, created_by)
SELECT md5('demo:cups:carton:' || lpn)::uuid, lpn, item_id, unit_id, factor, factor, location_code, 'IN_STOCK', 0,
       TIMESTAMPTZ '2026-11-11 13:19:35+00', 'V20261206000100'
  FROM c_carton;

INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, version, created_at,
                                  created_by, stock_package_id, lot_id)
SELECT md5('demo:cups:stock:' || lpn)::uuid, sku, location_code, lot_number, factor, 0, status, 0,
       TIMESTAMPTZ '2026-11-11 13:19:35+00', 'V20261206000100', md5('demo:cups:carton:' || lpn)::uuid,
       md5('demo:cups:lot:' || sku || ':' || lot_number)::uuid
  FROM c_carton;

-- The empty rows the boxing of loose stock left behind, one per place (the furniture's 11111111-... to 44444444-...).
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, version, created_at,
                                  created_by, lot_id)
SELECT md5('demo:cups:empty-stock:' || sku || ':' || location_code)::uuid, sku, location_code, lot_number, 0, 0, status,
       0, TIMESTAMPTZ '2026-11-11 13:19:20+00', 'flyway', md5('demo:cups:lot:' || sku || ':' || lot_number)::uuid
  FROM c_stock;

-- Each carton's opening line, as V20261128000100 wrote the furniture's: a RECEIPT of its units into it, where it
-- stands, in its lot and status, referenced by the carton itself.
INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id,
                                      to_stock_package_id, reason, occurred_at, version, created_at, created_by)
SELECT md5('demo:cups:opening-ledger:' || lpn)::uuid, 'RECEIPT', sku, lot_number,
       md5('demo:cups:lot:' || sku || ':' || lot_number)::uuid, NULL, location_code, factor, NULL, status,
       'STOCK_PACKAGE', md5('demo:cups:carton:' || lpn)::uuid, md5('demo:cups:carton:' || lpn)::uuid,
       'Tồn đầu kỳ khi đưa hệ thống vào dùng (open-questions C45)', TIMESTAMPTZ '2026-11-11 13:19:35+00', 0,
       TIMESTAMPTZ '2026-11-11 13:19:35+00', 'flyway'
  FROM c_carton;

-- ---------------------------------------------------------------------------------------------
-- 5. The furniture's catalogue leaves: units, items, media, variants, axes, products, brand, categories, values
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE c_furniture_product (code TEXT PRIMARY KEY) ON COMMIT DROP;
INSERT INTO c_furniture_product VALUES ('SOFA-3S'), ('TABLE-OAK');

DELETE FROM inventory.uom_conversions u
 USING inventory.inventory_items i
 WHERE i.id = u.inventory_item_id AND i.sku IN (SELECT sku FROM c_furniture);

DELETE FROM inventory.inventory_items WHERE sku IN (SELECT sku FROM c_furniture);

UPDATE inventory.inventory_items SET barcode = '8930000000011' WHERE sku = 'LY-GIAY-DEMO-12OZ-DO';

-- The catalog's projection of the two published products: CatalogProjectionListener writes it at every start, so a
-- database the application has run on holds it, and its foreign keys would refuse the variants' and products' delete.
DELETE FROM catalog.catalog_entry WHERE sku IN (SELECT sku FROM c_furniture);

DELETE FROM catalog.product_listing l
 USING product.products p
 WHERE p.id = l.product_id AND p.code IN (SELECT code FROM c_furniture_product);

DELETE FROM product.media m
 USING product.variants v JOIN product.products p ON p.id = v.product_id
 WHERE m.variant_id = v.id AND p.code IN (SELECT code FROM c_furniture_product);

DELETE FROM product.variant_attribute_values x
 USING product.variants v JOIN product.products p ON p.id = v.product_id
 WHERE x.variant_id = v.id AND p.code IN (SELECT code FROM c_furniture_product);

DELETE FROM product.variants v
 USING product.products p
 WHERE p.id = v.product_id AND p.code IN (SELECT code FROM c_furniture_product);

DELETE FROM product.product_descriptive_values d
 USING product.product_attributes a JOIN product.products p ON p.id = a.product_id
 WHERE d.product_attribute_id = a.id AND p.code IN (SELECT code FROM c_furniture_product);

DELETE FROM product.product_attribute_options o
 USING product.product_attributes a JOIN product.products p ON p.id = a.product_id
 WHERE o.product_attribute_id = a.id AND p.code IN (SELECT code FROM c_furniture_product);

DELETE FROM product.product_attributes a
 USING product.products p
 WHERE p.id = a.product_id AND p.code IN (SELECT code FROM c_furniture_product);

DELETE FROM product.product_categories c
 USING product.products p
 WHERE p.id = c.product_id AND p.code IN (SELECT code FROM c_furniture_product);

-- The change log is append-only: its rows leave with their product (fk_product_change_log_product cascades, and
-- platform.append_only lets a row go once its parent is gone).
ALTER TABLE product.products DISABLE TRIGGER tg_products_delete_draft_only;
DELETE FROM product.products WHERE code IN (SELECT code FROM c_furniture_product);
ALTER TABLE product.products ENABLE TRIGGER tg_products_delete_draft_only;

-- The furniture's brand: nothing else carries it (the cups and the box have none), and it named a real store.
DELETE FROM product.brands b
 WHERE b.code = 'NHAXINH'
   AND NOT EXISTS (SELECT 1 FROM product.products p WHERE p.brand_id = b.id);

DELETE FROM product.category_attributes a
 USING product.categories c
 WHERE c.id = a.category_id AND c.code IN ('LIVING', 'DINING', 'SOFA', 'TABLE');

DELETE FROM product.categories WHERE code IN ('SOFA', 'TABLE');
DELETE FROM product.categories WHERE code IN ('LIVING', 'DINING');

-- Dictionary values only the furniture used; GREY stays, the box is grey too.
DELETE FROM product.attribute_values v
 USING product.attributes a
 WHERE a.id = v.attribute_id
   AND (a.code, v.code) IN (('MATERIAL', 'OAK'), ('MATERIAL', 'FABRIC'), ('LENGTH', '160'));

-- ---------------------------------------------------------------------------------------------
-- 6. The zones of HCM were named after the furniture they held: they are named after the cups standing there now
-- ---------------------------------------------------------------------------------------------
UPDATE warehouse.zone SET name = 'Khu A - Ly giấy xanh', version = version + 1, last_modified_at = NOW(),
                          last_modified_by = 'flyway'
 WHERE id = md5('demo:zone:HCM:A')::uuid;
UPDATE warehouse.zone SET name = 'Khu B - Ly giấy đỏ', version = version + 1, last_modified_at = NOW(),
                          last_modified_by = 'flyway'
 WHERE id = md5('demo:zone:HCM:B')::uuid;

-- ---------------------------------------------------------------------------------------------
-- 7. Nothing of the furniture is left, and the cups stand where it stood
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    leftover TEXT;
    cartons  TEXT;
BEGIN
    SELECT string_agg(what, ', ') INTO leftover FROM (
        SELECT 'inventory item ' || sku AS what FROM inventory.inventory_items
         WHERE sku LIKE 'SOFA-3S%' OR sku LIKE 'TABLE-OAK%'
        UNION ALL SELECT 'stock row ' || id FROM inventory.stock_item
         WHERE sku LIKE 'SOFA-3S%' OR sku LIKE 'TABLE-OAK%'
        UNION ALL SELECT 'lot ' || id FROM inventory.stock_lot WHERE sku LIKE 'SOFA-3S%' OR sku LIKE 'TABLE-OAK%'
        UNION ALL SELECT 'ledger line ' || id FROM inventory.stock_movement
         WHERE sku LIKE 'SOFA-3S%' OR sku LIKE 'TABLE-OAK%'
        UNION ALL SELECT 'variant ' || sku FROM product.variants WHERE sku LIKE 'SOFA-3S%' OR sku LIKE 'TABLE-OAK%'
        UNION ALL SELECT 'product ' || code FROM product.products WHERE code LIKE 'SOFA-3S%' OR code LIKE 'TABLE-OAK%'
        UNION ALL SELECT 'brand ' || code FROM product.brands WHERE code = 'NHAXINH'
        UNION ALL SELECT 'category ' || code FROM product.categories WHERE code IN ('LIVING', 'DINING', 'SOFA', 'TABLE')
        UNION ALL SELECT 'attribute value ' || a.code || '=' || v.code FROM product.attribute_values v
          JOIN product.attributes a ON a.id = v.attribute_id
         WHERE (a.code, v.code) IN (('MATERIAL', 'OAK'), ('MATERIAL', 'FABRIC'), ('LENGTH', '160'))
        UNION ALL SELECT 'catalog entry ' || sku FROM catalog.catalog_entry
         WHERE sku LIKE 'SOFA-3S%' OR sku LIKE 'TABLE-OAK%'
        UNION ALL SELECT 'media ' || id FROM product.media WHERE url LIKE '%SOFA-3S%' OR url LIKE '%TABLE-OAK%'
        UNION ALL SELECT 'zone ' || name FROM warehouse.zone WHERE name IN ('Khu A - Sofa', 'Khu B - Bàn')
    ) furniture;
    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'furniture demo rows left: %', leftover;
    END IF;

    SELECT string_agg(place, ', ' ORDER BY place) INTO cartons FROM (
        SELECT i.sku || ' ' || p.location_code || ' ' || s.status || ' ' || count(*) AS place
          FROM inventory.stock_package p
          JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
          JOIN inventory.stock_item s ON s.stock_package_id = p.id
         WHERE p.created_by = 'V20261206000100' AND p.status = 'IN_STOCK' AND s.on_hand = p.nominal_qty
         GROUP BY i.sku, p.location_code, s.status
    ) placed;
    IF cartons IS DISTINCT FROM 'LY-GIAY-DEMO-12OZ-DO HCM-B01-1-A AVAILABLE 12, LY-GIAY-DEMO-12OZ-DO HCM-QC01 QUARANTINE 30, '
                                || 'LY-GIAY-DEMO-12OZ-XANH HCM-A01-2-B AVAILABLE 25, '
                                || 'LY-GIAY-DEMO-12OZ-XANH HCM-A02-1-A AVAILABLE 8' THEN
        RAISE EXCEPTION 'the cups do not stand where the furniture stood: %', cartons;
    END IF;
END
$$;
