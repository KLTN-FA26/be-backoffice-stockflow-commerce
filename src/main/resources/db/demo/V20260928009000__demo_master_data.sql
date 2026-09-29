-- =============================================================================
-- Demo data for the new model, applied only under the "demo" Flyway location (local and test
-- profiles, see application-local.yml / application-test.yml). Never part of production.
--
-- It makes the demo stock of V20260901000500 consistent with the new foreign keys and CHECKs, so
-- the contract steps (db/pending/C1..C4) can run on every developer's database:
--   * the two demo SKUs get a product, a variant and an inventory item;
--   * warehouse HCM gets a map, and the four demo stock rows move to location codes in the new
--     format (the old HCM-A-01-02-B / HCM-QC-01 fail ck_storage_location_code);
--   * a demo customer exists for the integration tests that place orders
--     (support/DemoData.CUSTOMER_ID), which ordering.customer_order.customer_id now requires.
--
-- Ids are md5-derived from a readable name, so they are stable across machines and a test or a
-- curl script can compute them.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Authors of the demo catalogue. DISABLED with an unusable password hash: they exist only so
--    the approval columns point at real, distinct users (BR-PRD-003). Nobody can sign in as them.
-- -----------------------------------------------------------------------------
INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
VALUES
    (md5('demo:user:editor')::uuid,   'demo.editor',   'demo.editor@stockflow.local',   '!demo-author-no-login',
     'Demo catalogue editor', 'DISABLED', 0, NOW(), 'flyway'),
    (md5('demo:user:approver')::uuid, 'demo.approver', 'demo.approver@stockflow.local', '!demo-author-no-login',
     'Demo catalogue approver', 'DISABLED', 0, NOW(), 'flyway');

INSERT INTO customer.customer (id, full_name, email, phone, status, version, created_at, created_by)
VALUES ('c0000000-0000-4000-8000-000000000001', 'Khách hàng demo', 'demo.customer@stockflow.local',
        '0900000001', 'ACTIVE', 0, NOW(), 'flyway');


-- -----------------------------------------------------------------------------
-- 2. Catalogue: brand, categories, attributes, two products, one variant each.
-- -----------------------------------------------------------------------------
INSERT INTO product.brands (id, code, name, slug, created_by)
VALUES (md5('demo:brand:NHAXINH')::uuid, 'NHAXINH', 'Nhà Xinh Demo', 'nha-xinh-demo', 'flyway');

INSERT INTO product.categories (id, parent_id, code, name, slug, path, depth, created_by)
VALUES
    (md5('demo:cat:LIVING')::uuid, NULL, 'LIVING', 'Phòng khách', 'phong-khach', '/LIVING', 0, 'flyway'),
    (md5('demo:cat:DINING')::uuid, NULL, 'DINING', 'Phòng ăn', 'phong-an', '/DINING', 0, 'flyway');
INSERT INTO product.categories (id, parent_id, code, name, slug, path, depth, created_by)
VALUES
    (md5('demo:cat:SOFA')::uuid,  md5('demo:cat:LIVING')::uuid, 'SOFA',  'Sofa', 'sofa', '/LIVING/SOFA', 1, 'flyway'),
    (md5('demo:cat:TABLE')::uuid, md5('demo:cat:DINING')::uuid, 'TABLE', 'Bàn ăn', 'ban-an', '/DINING/TABLE', 1, 'flyway');

INSERT INTO product.attributes (id, code, name, data_type, unit, is_filterable, created_by)
VALUES
    (md5('demo:attr:COLOR')::uuid,    'COLOR',    'Màu sắc',   'COLOR',  NULL, TRUE,  'flyway'),
    (md5('demo:attr:LENGTH')::uuid,   'LENGTH',   'Chiều dài', 'SELECT', 'cm', TRUE,  'flyway'),
    (md5('demo:attr:MATERIAL')::uuid, 'MATERIAL', 'Chất liệu', 'SELECT', NULL, TRUE,  'flyway');

INSERT INTO product.attribute_values (id, attribute_id, code, label, value_number, hex_color, created_by)
VALUES
    (md5('demo:val:COLOR:GREY')::uuid,     md5('demo:attr:COLOR')::uuid,    'GREY',   'Xám',     NULL, '#8A8A8A', 'flyway'),
    (md5('demo:val:LENGTH:160')::uuid,     md5('demo:attr:LENGTH')::uuid,   '160',    '160 cm',  160,  NULL,      'flyway'),
    (md5('demo:val:MATERIAL:FABRIC')::uuid, md5('demo:attr:MATERIAL')::uuid, 'FABRIC', 'Vải nỉ',  NULL, NULL,      'flyway'),
    (md5('demo:val:MATERIAL:OAK')::uuid,   md5('demo:attr:MATERIAL')::uuid, 'OAK',    'Gỗ sồi',  NULL, NULL,      'flyway');

INSERT INTO product.category_attributes (id, category_id, attribute_id, default_role, is_required, created_by)
VALUES
    (md5('demo:ca:SOFA:COLOR')::uuid,     md5('demo:cat:SOFA')::uuid,  md5('demo:attr:COLOR')::uuid,    'VARIANT_AXIS', TRUE,  'flyway'),
    (md5('demo:ca:SOFA:MATERIAL')::uuid,  md5('demo:cat:SOFA')::uuid,  md5('demo:attr:MATERIAL')::uuid, 'DESCRIPTIVE',  FALSE, 'flyway'),
    (md5('demo:ca:TABLE:LENGTH')::uuid,   md5('demo:cat:TABLE')::uuid, md5('demo:attr:LENGTH')::uuid,   'VARIANT_AXIS', TRUE,  'flyway'),
    (md5('demo:ca:TABLE:MATERIAL')::uuid, md5('demo:cat:TABLE')::uuid, md5('demo:attr:MATERIAL')::uuid, 'DESCRIPTIVE',  FALSE, 'flyway');

INSERT INTO product.products (id, brand_id, code, name, slug, short_description, tax_class, kind, status,
                              submitted_by, submitted_at, approved_by, approved_at, published_at, created_by)
VALUES
    (md5('demo:product:SOFA-3S')::uuid, md5('demo:brand:NHAXINH')::uuid, 'SOFA-3S', 'Sofa 3 chỗ', 'sofa-3-cho',
     'Sofa 3 chỗ ngồi, khung gỗ, bọc vải nỉ.', 'STANDARD', 'STANDARD', 'PUBLISHED',
     md5('demo:user:editor')::uuid, NOW(), md5('demo:user:approver')::uuid, NOW(), NOW(), 'flyway'),
    (md5('demo:product:TABLE-OAK')::uuid, md5('demo:brand:NHAXINH')::uuid, 'TABLE-OAK', 'Bàn ăn gỗ sồi', 'ban-an-go-soi',
     'Bàn ăn gỗ sồi nguyên khối.', 'STANDARD', 'STANDARD', 'PUBLISHED',
     md5('demo:user:editor')::uuid, NOW(), md5('demo:user:approver')::uuid, NOW(), NOW(), 'flyway');

INSERT INTO product.product_categories (id, product_id, category_id, is_primary, created_by)
VALUES
    (md5('demo:pc:SOFA-3S')::uuid,   md5('demo:product:SOFA-3S')::uuid,   md5('demo:cat:SOFA')::uuid,  TRUE, 'flyway'),
    (md5('demo:pc:TABLE-OAK')::uuid, md5('demo:product:TABLE-OAK')::uuid, md5('demo:cat:TABLE')::uuid, TRUE, 'flyway');

INSERT INTO product.product_attributes (id, product_id, attribute_id, role, created_by)
VALUES
    (md5('demo:pa:SOFA-3S:COLOR')::uuid,      md5('demo:product:SOFA-3S')::uuid,   md5('demo:attr:COLOR')::uuid,    'VARIANT_AXIS', 'flyway'),
    (md5('demo:pa:SOFA-3S:MATERIAL')::uuid,   md5('demo:product:SOFA-3S')::uuid,   md5('demo:attr:MATERIAL')::uuid, 'DESCRIPTIVE',  'flyway'),
    (md5('demo:pa:TABLE-OAK:LENGTH')::uuid,   md5('demo:product:TABLE-OAK')::uuid, md5('demo:attr:LENGTH')::uuid,   'VARIANT_AXIS', 'flyway'),
    (md5('demo:pa:TABLE-OAK:MATERIAL')::uuid, md5('demo:product:TABLE-OAK')::uuid, md5('demo:attr:MATERIAL')::uuid, 'DESCRIPTIVE',  'flyway');

INSERT INTO product.product_attribute_options (id, product_attribute_id, attribute_value_id, created_by)
VALUES
    (md5('demo:po:SOFA-3S:GREY')::uuid,  md5('demo:pa:SOFA-3S:COLOR')::uuid,    md5('demo:val:COLOR:GREY')::uuid,  'flyway'),
    (md5('demo:po:TABLE-OAK:160')::uuid, md5('demo:pa:TABLE-OAK:LENGTH')::uuid, md5('demo:val:LENGTH:160')::uuid, 'flyway');

INSERT INTO product.product_descriptive_values (id, product_attribute_id, attribute_value_id, created_by)
VALUES
    (md5('demo:pd:SOFA-3S:FABRIC')::uuid, md5('demo:pa:SOFA-3S:MATERIAL')::uuid,   md5('demo:val:MATERIAL:FABRIC')::uuid, 'flyway'),
    (md5('demo:pd:TABLE-OAK:OAK')::uuid,  md5('demo:pa:TABLE-OAK:MATERIAL')::uuid, md5('demo:val:MATERIAL:OAK')::uuid,    'flyway');

INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, created_by)
VALUES
    (md5('demo:variant:SOFA-3S-GREY')::uuid,  md5('demo:product:SOFA-3S')::uuid,   'SOFA-3S-GREY',  'Sofa 3 chỗ - Xám',       'ACTIVE', TRUE, 'COLOR=GREY', 'flyway'),
    (md5('demo:variant:TABLE-OAK-160')::uuid, md5('demo:product:TABLE-OAK')::uuid, 'TABLE-OAK-160', 'Bàn ăn gỗ sồi - 160 cm', 'ACTIVE', TRUE, 'LENGTH=160', 'flyway');

INSERT INTO product.variant_attribute_values (id, variant_id, product_attribute_option_id, created_by)
VALUES
    (md5('demo:vav:SOFA-3S-GREY')::uuid,  md5('demo:variant:SOFA-3S-GREY')::uuid,  md5('demo:po:SOFA-3S:GREY')::uuid,  'flyway'),
    (md5('demo:vav:TABLE-OAK-160')::uuid, md5('demo:variant:TABLE-OAK-160')::uuid, md5('demo:po:TABLE-OAK:160')::uuid, 'flyway');

INSERT INTO product.media (id, variant_id, kind, url, alt_text, is_primary, is_published, published_at, published_by, created_by)
VALUES
    (md5('demo:media:SOFA-3S-GREY')::uuid, md5('demo:variant:SOFA-3S-GREY')::uuid, 'IMAGE',
     'https://placehold.co/800x600?text=SOFA-3S-GREY', 'Sofa 3 chỗ màu xám', TRUE, TRUE, NOW(),
     md5('demo:user:approver')::uuid, 'flyway'),
    (md5('demo:media:TABLE-OAK-160')::uuid, md5('demo:variant:TABLE-OAK-160')::uuid, 'IMAGE',
     'https://placehold.co/800x600?text=TABLE-OAK-160', 'Bàn ăn gỗ sồi 160 cm', TRUE, TRUE, NOW(),
     md5('demo:user:approver')::uuid, 'flyway');


-- -----------------------------------------------------------------------------
-- 3. Supplier, and the inventory items of the two SKUs (logistics data complete, BR-03).
--    TABLE-OAK-160 is lot- and expiry-tracked because the original demo stock has lots with
--    expiry dates, which the FEFO allocation demo relies on.
-- -----------------------------------------------------------------------------
INSERT INTO procurement.suppliers (id, code, name, legal_name, tax_id, currency, payment_terms,
                                   over_receipt_tolerance, lead_time_days, created_by)
VALUES (md5('demo:supplier:GOVIET')::uuid, 'GOVIET', 'Xưởng gỗ Việt', 'Công ty TNHH Xưởng Gỗ Việt (demo)',
        '0300000001', 'VND', 'NET30', 5, 14, 'flyway');

INSERT INTO procurement.supplier_contacts (id, supplier_id, full_name, phone, email, is_primary, created_by)
VALUES (md5('demo:contact:GOVIET')::uuid, md5('demo:supplier:GOVIET')::uuid, 'Nguyễn Văn Demo',
        '0900000002', 'sales@goviet.example', TRUE, 'flyway');

INSERT INTO inventory.inventory_items (id, sku, unit_of_measure, barcode, weight_kg, length_cm, width_cm, height_cm,
                                       package_weight_kg, package_length_cm, package_width_cm, package_height_cm,
                                       package_count, storage_class, lot_tracked, expiry_tracked,
                                       min_qty, max_qty, reorder_point, default_supplier_id, created_by)
VALUES
    (md5('demo:item:SOFA-3S-GREY')::uuid, 'SOFA-3S-GREY', 'EACH', '8930000000011', 48.000, 210, 90, 85,
     55.000, 215, 95, 90, 2, 'OVERSIZE', FALSE, FALSE, 5, 40, 10, md5('demo:supplier:GOVIET')::uuid, 'flyway'),
    (md5('demo:item:TABLE-OAK-160')::uuid, 'TABLE-OAK-160', 'EACH', '8930000000028', 35.000, 160, 80, 75,
     40.000, 165, 85, 20, 1, 'NORMAL', TRUE, TRUE, 5, 50, 10, md5('demo:supplier:GOVIET')::uuid, 'flyway');

INSERT INTO procurement.supplier_items (id, supplier_id, inventory_item_id, supplier_sku_code, current_price,
                                        moq, pack_size, lead_time_days, is_preferred, created_by)
VALUES
    (md5('demo:si:GOVIET:SOFA')::uuid,  md5('demo:supplier:GOVIET')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid,
     'GV-SF3-GR', 5000000, 2, 1, 14, TRUE, 'flyway'),
    (md5('demo:si:GOVIET:TABLE')::uuid, md5('demo:supplier:GOVIET')::uuid, md5('demo:item:TABLE-OAK-160')::uuid,
     'GV-TB160-OAK', 3500000, 2, 1, 14, TRUE, 'flyway');

INSERT INTO procurement.supplier_item_prices (id, supplier_item_id, unit_price, effective_from, created_by)
VALUES
    (md5('demo:sip:SOFA')::uuid,  md5('demo:si:GOVIET:SOFA')::uuid,  5000000, '2026-09-01T00:00:00Z', 'flyway'),
    (md5('demo:sip:TABLE')::uuid, md5('demo:si:GOVIET:TABLE')::uuid, 3500000, '2026-09-01T00:00:00Z', 'flyway');


-- -----------------------------------------------------------------------------
-- 4. Warehouse HCM with a small map: three shelves of two levels, storage areas, a wall.
--    The row is created with the old NOT NULL code as well, since the old columns stay until C2.
-- -----------------------------------------------------------------------------
INSERT INTO warehouse.warehouse (id, code, name, address_line, city, status, prefix, address, return_address,
                                 map_unit, map_width, map_height, version, created_at, created_by)
VALUES (md5('demo:wh:HCM')::uuid, 'HCM', 'Kho Hồ Chí Minh', '12 Đường Demo, Phường Tân Thuận', 'Hồ Chí Minh',
        'ACTIVE', 'HCM', '12 Đường Demo, Phường Tân Thuận, TP. Hồ Chí Minh', NULL, 'M', 60, 40, 0, NOW(), 'flyway');

INSERT INTO warehouse.zone (id, warehouse_id, name, color, created_by)
VALUES (md5('demo:zone:HCM:A')::uuid, md5('demo:wh:HCM')::uuid, 'Khu A - Sofa', '#4F81BD', 'flyway'),
       (md5('demo:zone:HCM:B')::uuid, md5('demo:wh:HCM')::uuid, 'Khu B - Bàn', '#9BBB59', 'flyway');

INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation, is_obstacle,
                             pick_north, pick_east, pick_south, pick_west, default_storage_class, status, created_by)
VALUES
    (md5('demo:shelf:HCM-A01')::uuid, md5('demo:wh:HCM')::uuid, md5('demo:zone:HCM:A')::uuid, 'A01', 'Kệ A01',
     5, 5, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'OVERSIZE', 'ACTIVE', 'flyway'),
    (md5('demo:shelf:HCM-A02')::uuid, md5('demo:wh:HCM')::uuid, md5('demo:zone:HCM:A')::uuid, 'A02', 'Kệ A02',
     5, 9, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'OVERSIZE', 'ACTIVE', 'flyway'),
    (md5('demo:shelf:HCM-B01')::uuid, md5('demo:wh:HCM')::uuid, md5('demo:zone:HCM:B')::uuid, 'B01', 'Kệ B01',
     5, 14, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'NORMAL', 'ACTIVE', 'flyway');

INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight, created_by)
SELECT md5('demo:level:HCM-' || s.code || '-' || l.idx)::uuid, md5('demo:shelf:HCM-' || s.code)::uuid,
       l.idx, (l.idx - 1) * 1.5, 1.4, 500, 'flyway'
FROM (VALUES ('A01'), ('A02'), ('B01')) AS s(code)
CROSS JOIN (VALUES (1), (2)) AS l(idx);

-- Every bin: shelf x level x {A, B}. The storage location is inserted first and owned by its bin
-- by the end of the migration (tg_storage_location_owned is deferred to commit).
INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, capacity_units,
                                        max_weight, is_pickable, is_putaway_target, status, created_by)
SELECT md5('demo:loc:HCM-' || s.code || '-' || l.idx || '-' || b.code)::uuid, md5('demo:wh:HCM')::uuid, 'BIN',
       'HCM-' || s.code || '-' || l.idx || '-' || b.code, s.storage_class, 40, 250, TRUE, TRUE, 'ACTIVE', 'flyway'
FROM (VALUES ('A01', 'OVERSIZE'), ('A02', 'OVERSIZE'), ('B01', 'NORMAL')) AS s(code, storage_class)
CROSS JOIN (VALUES (1), (2)) AS l(idx)
CROSS JOIN (VALUES ('A', 0), ('B', 5)) AS b(code, x);

INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type, created_by)
SELECT md5('demo:bin:HCM-' || s.code || '-' || l.idx || '-' || b.code)::uuid,
       md5('demo:level:HCM-' || s.code || '-' || l.idx)::uuid,
       md5('demo:loc:HCM-' || s.code || '-' || l.idx || '-' || b.code)::uuid,
       b.code, b.x, 0, 5, 1.2, 0, 'PALLET', 'flyway'
FROM (VALUES ('A01'), ('A02'), ('B01')) AS s(code)
CROSS JOIN (VALUES (1), (2)) AS l(idx)
CROSS JOIN (VALUES ('A', 0), ('B', 5)) AS b(code, x);

INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, capacity_units,
                                        max_weight, is_pickable, is_putaway_target, status, created_by)
VALUES
    (md5('demo:loc:HCM-RCV01')::uuid,  md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-RCV01',  'OVERSIZE', NULL, NULL, FALSE, FALSE, 'ACTIVE', 'flyway'),
    (md5('demo:loc:HCM-QC01')::uuid,   md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-QC01',   'OVERSIZE', NULL, NULL, FALSE, FALSE, 'ACTIVE', 'flyway'),
    (md5('demo:loc:HCM-PACK01')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-PACK01', 'OVERSIZE', NULL, NULL, FALSE, FALSE, 'ACTIVE', 'flyway'),
    (md5('demo:loc:HCM-DSP01')::uuid,  md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-DSP01',  'OVERSIZE', NULL, NULL, FALSE, FALSE, 'ACTIVE', 'flyway');

INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation,
                            is_obstacle, status, created_by)
VALUES
    (md5('demo:area:HCM-RCV01')::uuid,  md5('demo:wh:HCM')::uuid, md5('demo:loc:HCM-RCV01')::uuid,  'RCV01',  'RECEIVING',   'Khu nhận hàng',   40, 2,  15, 8, 0, FALSE, 'ACTIVE', 'flyway'),
    (md5('demo:area:HCM-QC01')::uuid,   md5('demo:wh:HCM')::uuid, md5('demo:loc:HCM-QC01')::uuid,   'QC01',   'QUARANTINE',  'Khu cách ly QC',  40, 12, 8,  6, 0, FALSE, 'ACTIVE', 'flyway'),
    (md5('demo:area:HCM-PACK01')::uuid, md5('demo:wh:HCM')::uuid, md5('demo:loc:HCM-PACK01')::uuid, 'PACK01', 'PACKING',     'Khu đóng gói',    40, 20, 10, 6, 0, FALSE, 'ACTIVE', 'flyway'),
    (md5('demo:area:HCM-DSP01')::uuid,  md5('demo:wh:HCM')::uuid, md5('demo:loc:HCM-DSP01')::uuid,  'DSP01',  'DISPATCH',    'Khu chờ giao',    40, 28, 10, 6, 0, FALSE, 'ACTIVE', 'flyway'),
    (md5('demo:area:HCM-OFFICE')::uuid, md5('demo:wh:HCM')::uuid, NULL,                             'OFFICE', 'NON_STORAGE', 'Văn phòng kho',   2,  30, 10, 8, 0, TRUE,  'ACTIVE', 'flyway');

INSERT INTO warehouse.boundary (id, warehouse_id, type, start_x, start_y, end_x, end_y, is_passable,
                                operational_status, created_by)
VALUES
    (md5('demo:wall:HCM-N')::uuid, md5('demo:wh:HCM')::uuid, 'WALL', 0, 0, 60, 0, FALSE, NULL, 'flyway'),
    (md5('demo:door:HCM-E')::uuid, md5('demo:wh:HCM')::uuid, 'DOOR', 60, 2, 60, 8, TRUE, 'OPEN', 'flyway');


-- -----------------------------------------------------------------------------
-- 5. The original demo stock moves to real locations of the map.
-- -----------------------------------------------------------------------------
UPDATE inventory.stock_item SET location_code = 'HCM-A01-2-B' WHERE id = '11111111-1111-4111-8111-111111111111';
UPDATE inventory.stock_item SET location_code = 'HCM-A02-1-A' WHERE id = '22222222-2222-4222-8222-222222222222';
UPDATE inventory.stock_item SET location_code = 'HCM-B01-1-A' WHERE id = '33333333-3333-4333-8333-333333333333';
UPDATE inventory.stock_item SET location_code = 'HCM-QC01'    WHERE id = '44444444-4444-4444-8444-444444444444';


-- -----------------------------------------------------------------------------
-- 6. One draft purchase order, to open the PO screens on something. DRAFT needs no revision yet.
--    10 sofas at 5,000,000 VND + 10% VAT = 55,000,000 VND.
-- -----------------------------------------------------------------------------
INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency, order_date,
                                         expected_date, payment_terms, subtotal, tax_total, total_amount, note,
                                         created_by)
VALUES (md5('demo:po:1')::uuid, 'PO-HCM-DEMO-0001', 'DRAFT', md5('demo:supplier:GOVIET')::uuid,
        md5('demo:wh:HCM')::uuid, 'VND', DATE '2026-09-28', DATE '2026-10-12', 'NET30',
        50000000, 5000000, 55000000, 'Đơn nháp demo', 'flyway');

INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price,
                                              tax_rate, line_subtotal, line_net, line_tax, line_total, created_by)
VALUES (md5('demo:po:1:line:1')::uuid, md5('demo:po:1')::uuid, 1, md5('demo:item:SOFA-3S-GREY')::uuid,
        10, 5000000, 10, 50000000, 50000000, 5000000, 55000000, 'flyway');
