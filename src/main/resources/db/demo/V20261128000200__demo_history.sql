-- =============================================================================
-- Demo: four months of trading (2026-11-14 plan, BE4-A), so that the warehouse capacity board, the receivables report
-- and the stock summary report show real figures. Demo only (db/demo). Ids are md5('demo:history:...')::uuid; every
-- day is counted back from the Vietnam business day the database is built on, so the ages of the debts are the same
-- whenever it is built.
--
-- What it holds (days are "days before the build day"):
--   * warehouse DNI "Kho Đồng Nai": one shelf of four bins (33.6 m³), a receiving, a quarantine and a dispatch area;
--     its warehouse manager demo.quanly.dn and warehouse staff demo.kho.dn, the Sales Manager demo.truongkd (approves
--     production orders) and the Production Coordinator demo.dieuphoi. Sales and accounting are WF02's demo.sales and
--     demo.ketoan. Sign-in password: the published demo one (Stockflow@2026), as V20261124000600.
--   * four customers, each with a portal account, a legal profile, addresses and one printed cup of their own
--     (LY-GIAY-DEMO-12OZ-<code>, drawn on WF02's blank, 1,000 cups a carton of 60 x 40 x 50 cm): Cà Phê Sáng (credit
--     600M, 30 days), Trà Sữa Mây (300M, 15 days), Bánh Mì Việt (250M, 30 days), Nước Ép Xanh (no credit: pays
--     before delivery).
--   * twelve orders, each through the real chain - quote sent and accepted, converted, contract sent and signed,
--     deposit, shipping warehouse, production order drawn up, approved by another person and sent, goods received in
--     LPN cartons and put away, held, released, picked by LPN, checked and labelled, one load plan, one truck handed
--     over and delivered:
--       nine delivered between day 108 and day 10; what they pay after delivery is unpaid on four and half paid on
--       one, so the receivables report has a debt in every bucket (over 90, 61-90, 31-60 half paid, 1-30, not yet
--       due), paid on three, and the customer without credit paid before delivery; no customer signed a contract
--       while it had something overdue, so every credit check at signing was WITHIN_LIMIT (15 BR-10);
--       two waiting for a production order sent to DNI (one partly received and held), whose cartons take DNI's
--       projected free room below zero - the approval that crossed zero told the warehouse manager (WARN);
--       one signed 20 days ago whose deposit, due 17 days ago, was never paid (overdue on signing).
--   * a return: three cartons of a delivered order came back damaged, into DNI's quarantine area; a count of that area
--     found one of them missing and the adjustment was approved (COUNT_VARIANCE).
-- What it leaves out: e-invoices (the delivered orders stay DELIVERED, as an order with a delivery not invoiced does),
-- the transient delay alerts and the files (no file is stored by db/demo, as V20261124000600 says). The sales rollups
-- are not written here: the application fills them from the orders on its first start (SalesRollupBackfill).
--
-- Loaded with every trigger on, each step in the order the application writes it, so the deferred checks see what
-- the application's own transactions leave. created_by is the acting user's id, as the application's auditing writes
-- it ("system" for what a listener or a job does).
-- =============================================================================

-- ---------------------------------------------------------------------------------------------
-- 0. Helpers (session-local, dropped at the end)
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION pg_temp.hid(key TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT md5('demo:history:' || key)::uuid $$;

-- The Vietnam business day the database is built on.
CREATE FUNCTION pg_temp.today() RETURNS DATE LANGUAGE sql STABLE AS
$$ SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date $$;

-- days_ago days before the build day, at hh:mi Vietnam time.
CREATE FUNCTION pg_temp.at(days_ago INTEGER, hhmi TEXT) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT ((pg_temp.today() - days_ago) + hhmi::time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

CREATE FUNCTION pg_temp.day(days_ago INTEGER) RETURNS DATE LANGUAGE sql STABLE AS
$$ SELECT pg_temp.today() - days_ago $$;

CREATE FUNCTION pg_temp.ymd(days_ago INTEGER) RETURNS TEXT LANGUAGE sql STABLE AS
$$ SELECT to_char(pg_temp.today() - days_ago, 'YYYYMMDD') $$;

-- ---------------------------------------------------------------------------------------------
-- 1. Staff accounts
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_staff (username TEXT PRIMARY KEY, full_name TEXT, role TEXT, dni BOOLEAN) ON COMMIT DROP;
INSERT INTO h_staff VALUES
    ('demo.truongkd', 'Phạm Minh Trưởng (demo)', 'SALES_MANAGER', FALSE),
    ('demo.dieuphoi', 'Võ Thị Điều Phối (demo)', 'PRODUCTION_COORDINATOR', FALSE),
    ('demo.quanly.dn', 'Đỗ Văn Quản Lý (demo)', 'WAREHOUSE_MANAGER', TRUE),
    ('demo.kho.dn', 'Bùi Văn Kho (demo)', 'WAREHOUSE_STAFF', TRUE);

INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
SELECT pg_temp.hid('user:' || username), username, username || '@stockflow.local',
       '{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y', full_name, 'ACTIVE', 0,
       pg_temp.at(140, '08:00'), 'flyway'
  FROM h_staff;

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT pg_temp.hid('user-role:' || s.username), pg_temp.hid('user:' || s.username), r.id, 0, pg_temp.at(140, '08:00'),
       'flyway'
  FROM h_staff s JOIN identity.app_role r ON r.code = s.role;

-- Who does what below.
CREATE FUNCTION pg_temp.staff(username TEXT) RETURNS UUID LANGUAGE sql STABLE AS
$$ SELECT id FROM identity.app_user WHERE username = $1 $$;

-- ---------------------------------------------------------------------------------------------
-- 2. Warehouse DNI: one shelf of four bins, receiving, quarantine and dispatch areas (06 §4.1)
-- ---------------------------------------------------------------------------------------------
INSERT INTO warehouse.warehouse (id, name, status, prefix, address, return_address, map_unit, map_width, map_height,
                                 version, created_at, created_by)
VALUES (pg_temp.hid('wh:DNI'), 'Kho Đồng Nai', 'ACTIVE', 'DNI',
        'Lô C5, KCN Nhơn Trạch 2, Xã Nhơn Trạch, Tỉnh Đồng Nai', 'Lô C5, KCN Nhơn Trạch 2, Xã Nhơn Trạch, Tỉnh Đồng Nai',
        'M', 40, 30, 0, pg_temp.at(140, '08:00'), 'flyway');

INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at, created_by)
SELECT pg_temp.hid('user-warehouse:' || username), pg_temp.hid('user:' || username), pg_temp.hid('wh:DNI'), 0,
       pg_temp.at(140, '08:05'), 'flyway'
  FROM h_staff WHERE dni;

INSERT INTO warehouse.zone (id, warehouse_id, name, color, version, created_at, created_by)
VALUES (pg_temp.hid('zone:DNI:A'), pg_temp.hid('wh:DNI'), 'Khu A - Ly giấy', '#4F81BD', 0, pg_temp.at(140, '08:10'),
        'flyway');

INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation, is_obstacle,
                             pick_north, pick_east, pick_south, pick_west, status, version, created_at, created_by)
VALUES (pg_temp.hid('shelf:DNI-A01'), pg_temp.hid('wh:DNI'), pg_temp.hid('zone:DNI:A'), 'A01', 'Kệ A01', 5, 5, 10, 1.2,
        0, TRUE, FALSE, FALSE, TRUE, FALSE, 'ACTIVE', 0, pg_temp.at(140, '08:10'), 'flyway');

INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight, version, created_at,
                                   created_by)
SELECT pg_temp.hid('level:DNI-A01-' || n), pg_temp.hid('shelf:DNI-A01'), n, (n - 1) * 1.5, 1.4, 800, 0,
       pg_temp.at(140, '08:10'), 'flyway'
  FROM generate_series(1, 2) n;

-- A location and the bin or area that owns it go in together: tg_storage_location_owned checks it at commit.
INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight, is_pickable,
                                        is_putaway_target, status, version, created_at, created_by)
SELECT pg_temp.hid('location:' || code), pg_temp.hid('wh:DNI'), kind, code, NULL, pickable, putaway, 'ACTIVE', 0,
       pg_temp.at(140, '08:10'), 'flyway'
  FROM (VALUES ('DNI-A01-1-A', 'BIN', TRUE, TRUE), ('DNI-A01-1-B', 'BIN', TRUE, TRUE),
               ('DNI-A01-2-A', 'BIN', TRUE, TRUE), ('DNI-A01-2-B', 'BIN', TRUE, TRUE),
               ('DNI-RCV01', 'AREA', FALSE, FALSE), ('DNI-QC01', 'AREA', FALSE, FALSE),
               ('DNI-DSP01', 'AREA', FALSE, FALSE)) AS l (code, kind, pickable, putaway);

INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type, version, created_at,
                           created_by)
SELECT pg_temp.hid('bin:' || code), pg_temp.hid('level:DNI-A01-' || lvl), pg_temp.hid('location:' || code), bin,
       CASE bin WHEN 'A' THEN 0 ELSE 5 END, 0, 5, 1.2, 0, 'PALLET', 0, pg_temp.at(140, '08:10'), 'flyway'
  FROM (VALUES ('DNI-A01-1-A', 1, 'A'), ('DNI-A01-1-B', 1, 'B'), ('DNI-A01-2-A', 2, 'A'),
               ('DNI-A01-2-B', 2, 'B')) AS b (code, lvl, bin);

INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle,
                            status, version, created_at, created_by)
SELECT pg_temp.hid('area:DNI-' || code), pg_temp.hid('wh:DNI'), pg_temp.hid('location:DNI-' || code), code, type, name,
       x, y, w, l, 0, FALSE, 'ACTIVE', 0, pg_temp.at(140, '08:10'), 'flyway'
  FROM (VALUES ('RCV01', 'RECEIVING', 'Khu nhận hàng', 25, 2, 12, 6),
               ('QC01', 'QUARANTINE', 'Khu cách ly', 25, 10, 8, 5),
               ('DSP01', 'DISPATCH', 'Khu chờ giao', 25, 18, 12, 6)) AS a (code, type, name, x, y, w, l);

-- ---------------------------------------------------------------------------------------------
-- 3. Customers: portal account, legal profile, addresses, credit (18; 15 BR-12: paying after delivery needs credit)
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_customer (code TEXT PRIMARY KEY, name TEXT, tax_code TEXT, username TEXT, contact TEXT,
                              phone TEXT, line1 TEXT, ward TEXT, ward_code TEXT, province TEXT, province_code TEXT,
                              credit_limit NUMERIC, term_days INTEGER, price INTEGER, since INTEGER) ON COMMIT DROP;
INSERT INTO h_customer VALUES
    ('CPS', 'Công ty TNHH Cà Phê Sáng', '0399990011', 'demo.kh.caphesang', 'Lê Văn Sáng', '0901110011',
     '45 Lê Lợi', 'Phường Sài Gòn', '26740', 'TP. Hồ Chí Minh', '79', 600000000, 30, 1100, 70),
    ('TSM', 'Công ty CP Trà Sữa Mây', '0399990012', 'demo.kh.trasuamay', 'Trần Thị Mây', '0901110012',
     '18 Võ Văn Tần', 'Phường Xuân Hòa', '27142', 'TP. Hồ Chí Minh', '79', 300000000, 15, 1200, 136),
    ('BMV', 'Công ty TNHH Bánh Mì Việt', '0399990013', 'demo.kh.banhmiviet', 'Nguyễn Văn Việt', '0901110013',
     '102 Nguyễn Trãi', 'Phường Bến Thành', '26743', 'TP. Hồ Chí Minh', '79', 250000000, 30, 1150, 131),
    ('NEX', 'Công ty TNHH Nước Ép Xanh', '0399990014', 'demo.kh.nuocepxanh', 'Phạm Thị Xanh', '0901110014',
     '7 Phan Xích Long', 'Phường Cầu Kiệu', '27043', 'TP. Hồ Chí Minh', '79', NULL, NULL, 1250, 81);

INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
SELECT pg_temp.hid('user:' || username), username, username || '@stockflow.local',
       '{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y', contact || ' (demo)', 'ACTIVE', 0,
       pg_temp.at(since, '09:00'), 'flyway'
  FROM h_customer;

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT pg_temp.hid('user-role:' || c.username), pg_temp.hid('user:' || c.username), r.id, 0,
       pg_temp.at(c.since, '09:00'), 'flyway'
  FROM h_customer c JOIN identity.app_role r ON r.code = 'CUSTOMER';

INSERT INTO customer.customer (id, full_name, email, phone, status, version, created_at, created_by, user_id, tax_code)
SELECT pg_temp.hid('customer:' || code), name, 'muahang.' || lower(code) || '@stockflow.local', phone, 'ACTIVE', 0,
       pg_temp.at(since, '09:00'), pg_temp.staff('demo.sales')::text, pg_temp.hid('user:' || username), tax_code
  FROM h_customer;

-- The invoice e-mail is the customer's contact e-mail, as V20261120000100 gave every profile it found.
INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name,
                                             representative_title, invoice_email, version, created_at, created_by)
SELECT pg_temp.hid('legal:' || code), pg_temp.hid('customer:' || code), line1 || ', ' || ward || ', ' || province,
       contact, 'Giám đốc', 'muahang.' || lower(code) || '@stockflow.local', 0, pg_temp.at(since, '09:30'),
       pg_temp.staff('demo.sales')::text
  FROM h_customer;

INSERT INTO customer.address (id, customer_id, type, recipient_name, phone, line1, ward, province, country, is_default,
                              ward_code, province_code, version, created_at, created_by)
SELECT pg_temp.hid('address:' || c.code || ':' || t.type), pg_temp.hid('customer:' || c.code), t.type,
       CASE t.type WHEN 'SHIPPING' THEN c.contact ELSE c.name END, c.phone, c.line1, c.ward, c.province, 'VN', TRUE,
       c.ward_code, c.province_code, 0, pg_temp.at(c.since, '09:30'), pg_temp.staff('demo.sales')::text
  FROM h_customer c CROSS JOIN (VALUES ('SHIPPING'), ('BILLING')) AS t (type);

INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, currency, approved_by, approved_at,
                                     note, version, created_at, created_by)
SELECT pg_temp.hid('credit:' || code), pg_temp.hid('customer:' || code), credit_limit, term_days, 'VND',
       pg_temp.staff('demo.ketoan'), pg_temp.at(since - 1, '10:00'),
       'Hạn mức duyệt theo hồ sơ năng lực tài chính; thanh toán đợt sau giao hàng trong ' || term_days || ' ngày', 0,
       pg_temp.at(since - 1, '10:00'), pg_temp.staff('demo.ketoan')::text
  FROM h_customer WHERE credit_limit IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 4. Each customer's printed cup: approved design, the SKU issued on it (01 BR-03), its carton, its own price
-- ---------------------------------------------------------------------------------------------
INSERT INTO design.design_draft (id, customer_id, product_id, name, spec, status, owner_user_id, reviewed_by,
                                 review_notes, current_artifacts, variant_id, sku, template_id, template_code,
                                 review_requested_at, reviewed_at, version, created_at, created_by)
SELECT pg_temp.hid('design:' || code), pg_temp.hid('customer:' || code), md5('demo:wf02:product:LY-GIAY-DEMO')::uuid,
       'Ly giấy 12oz logo ' || name, 'Logo giữa thân ly, 2 màu Pantone', 'APPROVED', pg_temp.hid('user:' || username),
       pg_temp.staff('demo.sales'), 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', '{}'::jsonb,
       md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 'LY-GIAY-DEMO-12OZ',
       md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid, 'STD', pg_temp.at(since - 1, '10:00'),
       pg_temp.at(since - 2, '14:00'), 0, pg_temp.at(since - 1, '09:00'), pg_temp.hid('user:' || username)::text
  FROM h_customer;

INSERT INTO design.design_decision (id, draft_id, actor_id, decision, notes, draft_version, version, created_at,
                                    created_by)
SELECT pg_temp.hid('design-decision:' || c.code || ':' || d.n), pg_temp.hid('design:' || c.code),
       CASE WHEN d.n = 2 THEN pg_temp.staff('demo.sales') ELSE pg_temp.hid('user:' || c.username) END, d.decision,
       d.notes, d.n, 0, pg_temp.at(c.since - d.day, d.hhmi), 'flyway'
  FROM h_customer c
 CROSS JOIN (VALUES (0, 'CREATED', 'Tạo thiết kế', 1, '09:00'),
                    (1, 'SUBMITTED_FOR_REVIEW', 'Gửi duyệt thiết kế', 1, '10:00'),
                    (2, 'APPROVED', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', 2, '14:00'))
            AS d (n, decision, notes, day, hhmi);

INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, size_bytes, stored_at,
                                    checksum, artifact_role, version, created_at, created_by)
SELECT pg_temp.hid('artifact:' || code), pg_temp.hid('design:' || code),
       'design-renders/demo/ly-giay-12oz-' || lower(code) || '.pdf', 'print_ready.pdf', 'application/pdf', 51200,
       pg_temp.at(since - 1, '09:45'), encode(sha256(convert_to('demo:history:artifact:' || code, 'UTF8')), 'hex'),
       'PRINT_READY', 0, pg_temp.at(since - 1, '09:45'), pg_temp.hid('user:' || username)::text
  FROM h_customer;

UPDATE design.design_draft d
   SET current_artifact_id = pg_temp.hid('artifact:' || c.code),
       current_artifacts = jsonb_build_object('PRINT_READY', pg_temp.hid('artifact:' || c.code))
  FROM h_customer c
 WHERE d.id = pg_temp.hid('design:' || c.code);

-- The snapshot's checksum is the manifest's (DesignSnapshotJpaEntity.manifestChecksum), as V20261124000600 computes it.
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, artifact_id, confirmed_by,
                                    reviewed_by, spec, review_notes, artifact_manifest, version, created_at, created_by)
SELECT pg_temp.hid('snapshot:' || c.code), a.draft_id,
       encode(sha256(convert_to('PRINT_READY:' || a.id || ':' || a.checksum || E'\n', 'UTF8')), 'hex'), a.storage_key,
       pg_temp.at(c.since - 2, '14:00'), a.id, pg_temp.hid('user:' || c.username), pg_temp.staff('demo.sales'),
       'Logo giữa thân ly, 2 màu Pantone', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm',
       jsonb_build_array(jsonb_build_object('artifactId', a.id, 'role', a.artifact_role, 'storageKey', a.storage_key,
                                            'originalName', a.original_name, 'contentType', a.content_type,
                                            'sizeBytes', a.size_bytes, 'checksum', a.checksum)),
       0, pg_temp.at(c.since - 2, '14:00'), pg_temp.staff('demo.sales')::text
  FROM h_customer c JOIN design.design_artifact a ON a.id = pg_temp.hid('artifact:' || c.code);

-- tg_variant_inventory_item makes each SKU's inventory item, as the catalogue makes it.
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                              owner_customer_id, design_snapshot_id, source_variant_id, version, created_at, created_by)
SELECT pg_temp.hid('variant:' || code), md5('demo:wf02:product:LY-GIAY-DEMO')::uuid, 'LY-GIAY-DEMO-12OZ-' || code,
       'Ly giấy 2 lớp in logo - 12 oz - ' || name, 'ACTIVE', FALSE, 'OWNED-SKU:LY-GIAY-DEMO-12OZ-' || code,
       20 + row_number() OVER (ORDER BY code), pg_temp.hid('customer:' || code), pg_temp.hid('snapshot:' || code),
       md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 0, pg_temp.at(since - 2, '14:05'), 'system'
  FROM h_customer;

UPDATE inventory.inventory_items SET weight_kg = 0.008, length_cm = 9, width_cm = 9, height_cm = 11
 WHERE sku IN (SELECT 'LY-GIAY-DEMO-12OZ-' || code FROM h_customer);

-- Its carton: 1,000 cups, 60 x 40 x 50 cm, 8.6 kg (01 BR-03) - the unit it ships in and the only one it is sold in.
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, weight_kg, length_cm,
                                       width_cm, height_cm, default_purchase, active, default_shipping, version,
                                       created_at, created_by)
SELECT pg_temp.hid('unit:' || c.code), i.id, 'THUNG', 1000, FALSE, 8.6, 60, 40, 50, FALSE, TRUE, TRUE, 0,
       pg_temp.at(c.since - 2, '14:10'), pg_temp.staff('demo.sales')::text
  FROM h_customer c JOIN inventory.inventory_items i ON i.sku = 'LY-GIAY-DEMO-12OZ-' || c.code;

INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id, version, created_at, created_by)
SELECT pg_temp.hid('sales-unit:' || code), pg_temp.hid('variant:' || code), pg_temp.hid('unit:' || code), 0,
       pg_temp.at(since - 2, '14:10'), pg_temp.staff('demo.sales')::text
  FROM h_customer;

-- The customer's own price by cartons (13 BR-05): from 20 cartons the base price plus 100, from 50 the base, from 100
-- the base less 100 (VND a cup).
INSERT INTO catalog.customer_price (id, customer_id, sku, currency, note, version, created_at, created_by)
SELECT pg_temp.hid('price:' || code), pg_temp.hid('customer:' || code), 'LY-GIAY-DEMO-12OZ-' || code, 'VND',
       'Bảng giá nội bộ theo số thùng (giá một ly)', 0, pg_temp.at(since - 2, '15:00'),
       pg_temp.staff('demo.sales')::text
  FROM h_customer;

INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version, created_at,
                                         created_by)
SELECT pg_temp.hid('price-tier:' || c.code || ':' || t.min_quantity), pg_temp.hid('price:' || c.code), t.min_quantity,
       c.price + t.delta, 0, pg_temp.at(c.since - 2, '15:00'), pg_temp.staff('demo.sales')::text
  FROM h_customer c CROSS JOIN (VALUES (20, 100), (50, 0), (100, -100)) AS t (min_quantity, delta);

-- ---------------------------------------------------------------------------------------------
-- 5. The producers the cups are printed by (17 §4.3)
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.producer (id, code, name, contact_name, contact_phone, email, channel, standard_lead_days, status,
                               note, version, created_at, created_by)
VALUES (pg_temp.hid('producer:INDN'), 'DEMO-INDN', 'Công ty In Bao Bì Đồng Nai (demo)', 'Hồ Văn In', '0251390011',
        'sanxuat.indn@stockflow.local', 'EMAIL', 10, 'ACTIVE', NULL, 0, pg_temp.at(140, '09:00'),
        pg_temp.staff('demo.dieuphoi')::text),
       (pg_temp.hid('producer:INLX'), 'DEMO-INLX', 'Xưởng In Ly Giấy Long Xuyên (demo)', 'Đinh Thị Ly', '0296390022',
        'donhang.inlx@stockflow.local', 'EMAIL', 12, 'ACTIVE', NULL, 0, pg_temp.at(140, '09:00'),
        pg_temp.staff('demo.dieuphoi')::text);

-- ---------------------------------------------------------------------------------------------
-- 6. The twelve orders, their timeline in days before the build day
-- ---------------------------------------------------------------------------------------------
-- s: the contract signed. d: delivered (null: not yet). paid_after / paid_part: when, and how much of, the
-- installment after delivery was paid. received: cartons received so far for an order still waiting for its goods.
CREATE TEMP TABLE h_order (key TEXT PRIMARY KEY, seq INTEGER, cust TEXT, cartons INTEGER, s INTEGER, d INTEGER,
                           outcome TEXT, plate BOOLEAN, paid_after INTEGER, paid_part NUMERIC, received INTEGER,
                           producer TEXT, window_from INTEGER) ON COMMIT DROP;
INSERT INTO h_order VALUES
    ('O01', 1, 'TSM', 40, 125, 108, 'DELIVERED', TRUE, NULL, NULL, NULL, 'INDN', NULL),
    ('O02', 2, 'TSM', 30, 112, 95, 'DELIVERED', FALSE, 82, 1, NULL, 'INLX', NULL),
    ('O03', 3, 'BMV', 50, 120, 100, 'DELIVERED', TRUE, NULL, NULL, NULL, 'INDN', NULL),
    ('O04', 4, 'BMV', 35, 92, 75, 'DELIVERED', FALSE, 40, 0.5, NULL, 'INLX', NULL),
    ('O05', 5, 'NEX', 25, 70, 50, 'DELIVERED', TRUE, NULL, NULL, NULL, 'INDN', NULL),
    ('O06', 6, 'CPS', 45, 60, 42, 'DELIVERED', TRUE, NULL, NULL, NULL, 'INLX', NULL),
    ('O07', 7, 'CPS', 40, 28, 10, 'DELIVERED', FALSE, NULL, NULL, NULL, 'INDN', NULL),
    ('O08', 8, 'TSM', 35, 103, 86, 'DELIVERED', FALSE, 74, 1, NULL, 'INDN', NULL),
    ('O09', 9, 'CPS', 150, 22, NULL, 'AWAITING_STOCK', FALSE, NULL, NULL, 60, 'INLX', -12),
    ('O10', 10, 'CPS', 150, 15, NULL, 'AWAITING_STOCK', FALSE, NULL, NULL, 0, 'INDN', -18),
    ('O11', 11, 'NEX', 30, 20, NULL, 'PENDING_PAYMENT', FALSE, NULL, NULL, NULL, NULL, -10),
    ('O12', 12, 'CPS', 30, 48, 30, 'DELIVERED', FALSE, 8, 1, NULL, 'INLX', NULL);

ALTER TABLE h_order
    ADD COLUMN id UUID, ADD COLUMN customer_id UUID, ADD COLUMN sku TEXT, ADD COLUMN unit_id UUID,
    ADD COLUMN snapshot_id UUID, ADD COLUMN line_id UUID, ADD COLUMN quote_id UUID, ADD COLUMN contract_id UUID,
    ADD COLUMN batch_id UUID, ADD COLUMN po_id UUID, ADD COLUMN po_line_id UUID, ADD COLUMN credit BOOLEAN,
    ADD COLUMN term_days INTEGER, ADD COLUMN unit_price NUMERIC, ADD COLUMN goods NUMERIC, ADD COLUMN fee NUMERIC,
    ADD COLUMN goods_tax NUMERIC, ADD COLUMN fee_tax NUMERIC, ADD COLUMN total NUMERIC, ADD COLUMN deposit NUMERIC,
    ADD COLUMN rest NUMERIC, ADD COLUMN order_number TEXT, ADD COLUMN quote_number TEXT, ADD COLUMN contract_number TEXT,
    ADD COLUMN po_code TEXT, ADD COLUMN window_start DATE, ADD COLUMN window_end DATE, ADD COLUMN r INTEGER;

UPDATE h_order o
   SET id = pg_temp.hid('order:' || o.key), customer_id = pg_temp.hid('customer:' || o.cust),
       sku = 'LY-GIAY-DEMO-12OZ-' || o.cust, unit_id = pg_temp.hid('unit:' || o.cust),
       snapshot_id = pg_temp.hid('snapshot:' || o.cust), line_id = pg_temp.hid('order-line:' || o.key),
       quote_id = pg_temp.hid('quote:' || o.key), contract_id = pg_temp.hid('contract:' || o.key),
       batch_id = pg_temp.hid('batch:' || o.key), po_id = CASE WHEN o.producer IS NOT NULL
                                                             THEN pg_temp.hid('po:' || o.key) END,
       po_line_id = CASE WHEN o.producer IS NOT NULL THEN pg_temp.hid('po-line:' || o.key) END,
       credit = c.credit_limit IS NOT NULL, term_days = c.term_days,
       unit_price = c.price + CASE WHEN o.cartons >= 100 THEN -100 WHEN o.cartons >= 50 THEN 0 ELSE 100 END,
       order_number = 'SO-' || pg_temp.ymd(o.s + 5) || '-' || (910000 + o.seq),
       quote_number = 'QT-' || pg_temp.ymd(o.s + 7) || '-' || (9100 + o.seq),
       po_code = CASE WHEN o.producer IS NOT NULL THEN 'PRD-' || pg_temp.ymd(o.s - 1) || '-' || (9100 + o.seq) END,
       -- Delivered orders: the window opens the day before the truck leaves; the others' is still ahead.
       window_start = CASE WHEN o.d IS NOT NULL THEN pg_temp.day(o.d + 1) ELSE pg_temp.day(o.window_from) END,
       window_end = CASE WHEN o.d IS NOT NULL THEN pg_temp.day(o.d - 2) ELSE pg_temp.day(o.window_from - 2) END,
       -- The goods of a delivered order are received six days before it leaves; O09's part arrived 6 days ago.
       r = CASE WHEN o.d IS NOT NULL THEN o.d + 6 WHEN o.received > 0 THEN 6 END
  FROM h_customer c
 WHERE c.code = o.cust;

UPDATE h_order
   SET goods = cartons * 1000 * unit_price, fee = CASE WHEN plate THEN 1500000 ELSE 0 END;
UPDATE h_order
   SET goods_tax = round(goods * 10 / 100, 0), fee_tax = round(fee * 10 / 100, 0);
UPDATE h_order
   SET total = goods + fee + goods_tax + fee_tax, contract_number = 'HD-' || substring(order_number FROM 4) || '-1';
UPDATE h_order SET deposit = round(total * 30 / 100, 0);
UPDATE h_order SET rest = total - deposit;

-- One row per carton of each order that has goods in the warehouse: its LPN, its rows, where it was put away.
CREATE TEMP TABLE h_carton ON COMMIT DROP AS
SELECT o.key, n, pg_temp.hid('lpn:' || o.key || ':' || n) AS package_id,
       pg_temp.hid('stock:' || o.key || ':' || n) AS stock_item_id,
       pg_temp.hid('putaway:' || o.key || ':' || n) AS task_id,
       pg_temp.hid('allocation:' || o.key || ':' || n) AS allocation_id,
       pg_temp.hid('pick-line:' || o.key || ':' || n) AS pick_line_id,
       pg_temp.hid('package:' || o.key || ':' || n) AS shipping_package_id,
       'LPN-' || pg_temp.ymd(o.r) || '-9' || lpad((o.seq * 1000 + n)::text, 5, '0') AS lpn,
       -- Bins in turn by order, never more than 40 cartons in one: a bin of 8.4 m³ and 800 kg a level holds them.
       'DNI-A01-' || (ARRAY['1-A', '1-B', '2-A', '2-B'])[1 + ((o.seq + (n - 1) / 40) % 4)] AS bin
  FROM h_order o
 CROSS JOIN LATERAL generate_series(1, CASE WHEN o.d IS NOT NULL THEN o.cartons ELSE coalesce(o.received, 0) END) n
 WHERE o.r IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 7. Quotes (14 §4.1): drawn up and sent by Sales, accepted by the customer's own login, converted
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, revision, valid_until, terms, sent_at,
                            sent_by, responded_at, responded_by, requested_delivery_date, version, created_at,
                            created_by)
SELECT o.quote_id, o.quote_number, pg_temp.hid('quote-request:' || o.key), o.customer_id, 'DRAFT', 1,
       pg_temp.at(o.s - 7, '17:00'), 'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.', NULL, NULL, NULL, NULL,
       o.window_start, 0, pg_temp.at(o.s + 7, '09:00'), pg_temp.staff('demo.sales')::text
  FROM h_order o;

INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton, tax_class,
                                 tax_rate)
SELECT pg_temp.hid('quote-line:' || o.key), o.quote_id, 1, o.sku, o.unit_id, o.cartons, 1000, 'STANDARD', 10
  FROM h_order o;

INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
SELECT pg_temp.hid('quote-line:' || o.key), t.min_quantity, c.price + t.delta
  FROM h_order o JOIN h_customer c ON c.code = o.cust
 CROSS JOIN (VALUES (20, 100), (50, 0), (100, -100)) AS t (min_quantity, delta);

-- A SKU never produced carries the plate fee on its first quote (14 BR-17); Sales types the amount.
INSERT INTO ordering.quote_fee_line (id, quote_id, kind, sku, amount, tax_class, tax_rate)
SELECT pg_temp.hid('quote-fee:' || o.key), o.quote_id, 'PLATE_FEE', o.sku, o.fee, 'STANDARD', 10
  FROM h_order o WHERE o.plate;

UPDATE ordering.quote q
   SET status = 'SENT', sent_at = pg_temp.at(o.s + 7, '10:00'), sent_by = pg_temp.staff('demo.sales'), version = 1,
       last_modified_at = pg_temp.at(o.s + 7, '10:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM h_order o WHERE q.id = o.quote_id;

UPDATE ordering.quote_line l
   SET accepted_cartons = o.cartons, accepted_unit_price = o.unit_price
  FROM h_order o WHERE l.quote_id = o.quote_id;

UPDATE ordering.quote q
   SET status = 'ACCEPTED', responded_at = pg_temp.at(o.s + 6, '15:00'),
       responded_by = pg_temp.hid('user:' || c.username), version = 2,
       last_modified_at = pg_temp.at(o.s + 6, '15:00'), last_modified_by = pg_temp.hid('user:' || c.username)::text
  FROM h_order o JOIN h_customer c ON c.code = o.cust WHERE q.id = o.quote_id;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, offer, changed_at,
                                           changed_by)
SELECT pg_temp.hid('quote-history:' || o.key || ':0'), o.quote_id, 1, NULL, 'DRAFT', NULL, NULL,
       pg_temp.at(o.s + 7, '09:00'), pg_temp.staff('demo.sales')
  FROM h_order o
UNION ALL
SELECT pg_temp.hid('quote-history:' || o.key || ':1'), o.quote_id, 1, 'DRAFT', 'SENT', NULL,
       jsonb_build_object(
           'revision', 1,
           'validUntil', to_char(pg_temp.at(o.s - 7, '17:00') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
           'terms', 'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.',
           'requestedDeliveryDate', to_char(o.window_start, 'YYYY-MM-DD'),
           'lines', jsonb_build_array(jsonb_build_object(
               'lineNo', 1, 'sku', o.sku, 'uomConversionId', o.unit_id, 'cartons', o.cartons, 'unitsPerCarton', 1000,
               'taxClass', 'STANDARD', 'taxRate', 10,
               'tiers', jsonb_build_array(jsonb_build_object('minQuantity', 20, 'unitPrice', c.price + 100),
                                          jsonb_build_object('minQuantity', 50, 'unitPrice', c.price),
                                          jsonb_build_object('minQuantity', 100, 'unitPrice', c.price - 100)))),
           'fees', CASE WHEN o.plate
                        THEN jsonb_build_array(jsonb_build_object('kind', 'PLATE_FEE', 'sku', o.sku, 'amount', o.fee,
                                                                  'taxClass', 'STANDARD', 'taxRate', 10))
                        ELSE '[]'::jsonb END),
       pg_temp.at(o.s + 7, '10:00'), pg_temp.staff('demo.sales')
  FROM h_order o JOIN h_customer c ON c.code = o.cust
UNION ALL
SELECT pg_temp.hid('quote-history:' || o.key || ':2'), o.quote_id, 1, 'SENT', 'ACCEPTED', NULL, NULL,
       pg_temp.at(o.s + 6, '15:00'), pg_temp.hid('user:' || c.username)
  FROM h_order o JOIN h_customer c ON c.code = o.cust;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 8. Each quote converted into a DRAFT order holding nothing (14 §4.2), the next morning but one
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, contact_name, contact_email, contact_phone,
                                     shipping_recipient_name, shipping_phone, shipping_line1, shipping_ward_code,
                                     shipping_ward_name, shipping_province_code, shipping_province_name,
                                     shipping_country_code, billing_recipient_name, billing_phone, billing_line1,
                                     billing_ward_code, billing_ward_name, billing_province_code, billing_province_name,
                                     billing_country_code, version, created_at, created_by)
SELECT o.id, o.order_number, o.customer_id, pg_temp.hid('order-request:' || o.key), 'DRAFT', o.total,
       o.goods_tax + o.fee_tax, 'VND', pg_temp.at(o.s + 5, '09:30'), c.contact,
       'muahang.' || lower(c.code) || '@stockflow.local', c.phone, c.contact, c.phone, c.line1, c.ward_code, c.ward,
       c.province_code, c.province, 'VN', c.name, c.phone, c.line1, c.ward_code, c.ward, c.province_code, c.province,
       'VN', 0, pg_temp.at(o.s + 5, '09:30'), pg_temp.staff('demo.sales')::text
  FROM h_order o JOIN h_customer c ON c.code = o.cust;

INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, requested_delivery_date,
                                 uom_conversion_id, cartons, units_per_carton, tax_rate, tax_amount)
SELECT o.line_id, o.id, o.sku, o.cartons * 1000, o.unit_price, 'VND', o.window_start, o.unit_id, o.cartons, 1000, 10,
       o.goods_tax
  FROM h_order o;

INSERT INTO ordering.order_fee_line (id, order_id, kind, sku, amount, tax_rate, tax_amount, currency)
SELECT pg_temp.hid('order-fee:' || o.key), o.id, 'PLATE_FEE', o.sku, o.fee, 10, o.fee_tax, 'VND'
  FROM h_order o WHERE o.plate;

UPDATE ordering.quote q
   SET status = 'CONVERTED', converted_order_id = o.id, converted_at = pg_temp.at(o.s + 5, '09:30'), version = 3,
       last_modified_at = pg_temp.at(o.s + 5, '09:30'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM h_order o WHERE q.id = o.quote_id;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, changed_at,
                                           changed_by)
SELECT pg_temp.hid('quote-history:' || o.key || ':3'), o.quote_id, 1, 'ACCEPTED', 'CONVERTED', o.order_number,
       pg_temp.at(o.s + 5, '09:30'), pg_temp.staff('demo.sales')
  FROM h_order o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 9. The contract (14 §4.3): one delivery batch; 30% on signing, the rest after delivery on the customer's credit
--    (15 BR-11) or, without credit, before delivery. Drawn up, sent, signed with the customer's scan.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
                                     seller_address, seller_representative, seller_representative_title, buyer_name,
                                     buyer_tax_code, buyer_address, buyer_representative, buyer_representative_title,
                                     free_sample_rounds, version, created_at, created_by)
SELECT o.contract_id, o.contract_number, o.id, 'DRAFT', 'DEMO STOCKFLOW CO LTD', '0000000000',
       '1 Demo Street, Ho Chi Minh City', 'Demo Director', 'Director', c.name, c.tax_code,
       c.line1 || ', ' || c.ward || ', ' || c.province, c.contact, 'Giám đốc', 2, 0, pg_temp.at(o.s + 4, '10:00'),
       pg_temp.staff('demo.sales')::text
  FROM h_order o JOIN h_customer c ON c.code = o.cust;

INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
SELECT o.batch_id, o.contract_id, 1, o.window_start, o.window_end FROM h_order o;

INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
SELECT pg_temp.hid('batch-line:' || o.key), o.batch_id, o.line_id, o.cartons FROM h_order o;

INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
SELECT pg_temp.hid('plan:' || o.key || ':1'), o.contract_id, 1, 'Đặt cọc khi ký', 'ON_SIGNING', 30, o.deposit, 3, NULL
  FROM h_order o
UNION ALL
SELECT pg_temp.hid('plan:' || o.key || ':2'), o.contract_id, 2,
       CASE WHEN o.credit THEN 'Thanh toán sau giao hàng' ELSE 'Thanh toán trước giao hàng' END,
       CASE WHEN o.credit THEN 'AFTER_DELIVERY' ELSE 'BEFORE_DELIVERY' END, NULL, o.rest,
       CASE WHEN o.credit THEN o.term_days END, o.batch_id
  FROM h_order o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE ordering.sales_contract k
   SET status = 'SENT', sent_at = pg_temp.at(o.s + 3, '09:00'), sent_by = pg_temp.staff('demo.sales'),
       sent_document_key = 'documents/demo/' || o.contract_number || '.pdf', version = 1,
       last_modified_at = pg_temp.at(o.s + 3, '09:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM h_order o WHERE k.id = o.contract_id;

UPDATE ordering.customer_order co
   SET status = 'PENDING_CONTRACT', version = 1, last_modified_at = pg_temp.at(o.s + 3, '09:00'),
       last_modified_by = pg_temp.staff('demo.sales')::text
  FROM h_order o WHERE co.id = o.id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Signed: the order is placed (17 §4.11: the day the dashboard counts it from) and waits for its deposit.
UPDATE ordering.sales_contract k
   SET status = 'SIGNED', signed_on = pg_temp.day(o.s), signed_at = pg_temp.at(o.s, '10:00'),
       signed_by = pg_temp.staff('demo.sales'), signed_file_key = 'documents/demo/' || o.contract_number || '-ky.pdf',
       signed_file_name = o.contract_number || '-da-ky.pdf', version = 2, last_modified_at = pg_temp.at(o.s, '10:00'),
       last_modified_by = pg_temp.staff('demo.sales')::text
  FROM h_order o WHERE k.id = o.contract_id;

UPDATE ordering.customer_order co
   SET status = 'PENDING_PAYMENT', contract_signed_at = pg_temp.at(o.s, '10:00'), placed_at = pg_temp.at(o.s, '10:00'),
       version = 2, last_modified_at = pg_temp.at(o.s, '10:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM h_order o WHERE co.id = o.id;

-- 15 BR-10, BR-11: a plan paying after delivery is checked against the customer's credit, and the check recorded. What
-- the customer owed after delivery on the contracts signed before - less what was paid by then - is the exposure.
INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome, note,
                                         version, created_at, created_by)
SELECT pg_temp.hid('credit-check:' || o.key), o.id, pg_temp.at(o.s, '10:00'), c.credit_limit,
       coalesce((SELECT sum(p.rest - CASE WHEN p.paid_after > o.s THEN round(p.rest * p.paid_part, 0) ELSE 0 END)
                   FROM h_order p
                  WHERE p.cust = o.cust AND p.s > o.s), 0),
       o.rest, 'WITHIN_LIMIT', 'Sales contract ' || o.contract_number, 0, pg_temp.at(o.s, '10:00'),
       pg_temp.staff('demo.sales')::text
  FROM h_order o JOIN h_customer c ON c.code = o.cust
 WHERE o.credit;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.hid('order-history:' || o.key || ':' || h.n), o.id, h.from_status, h.to_status, h.reason,
       CASE WHEN h.n > 0 THEN pg_temp.staff('demo.sales') END, pg_temp.at(o.s + h.day, h.hhmi),
       pg_temp.at(o.s + h.day, h.hhmi), pg_temp.staff('demo.sales')::text
  FROM h_order o
 CROSS JOIN (VALUES (0, NULL, 'DRAFT', NULL, 5, '09:30'),
                    (1, 'DRAFT', 'PENDING_CONTRACT', 'CONTRACT_SENT', 3, '09:00'),
                    (2, 'PENDING_CONTRACT', 'PENDING_PAYMENT', 'CONTRACT_SIGNED', 0, '10:00'))
            AS h (n, from_status, to_status, reason, day, hhmi);

-- 15 §4.1 step 1 (4B-6): the installments open with the signature; the one on signing is due at once, three days to
-- pay.
INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
                                 order_number, seq, name, milestone, delivery_batch_id, delivery_batch_seq, amount,
                                 paid_amount, currency, due_days, reached_at, due_date, status, version, created_at,
                                 created_by)
SELECT pg_temp.hid('installment:' || o.key || ':' || p.seq), o.contract_id, p.id, o.id, o.customer_id,
       o.contract_number, o.order_number, p.seq, p.name, p.milestone, p.delivery_batch_id,
       CASE WHEN p.delivery_batch_id IS NOT NULL THEN 1 END, p.amount, 0, 'VND', p.due_days,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN pg_temp.at(o.s, '10:00') END,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN pg_temp.day(o.s) + p.due_days END,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN 'DUE' ELSE 'NOT_DUE' END, 0, pg_temp.at(o.s, '10:00'),
       pg_temp.staff('demo.sales')::text
  FROM h_order o JOIN ordering.contract_payment_installment p ON p.contract_id = o.contract_id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 10. Deposits (15 §4.2): the accountant records the transfer the next morning and the order is confirmed - except
--     O11's, never paid, overdue since its due day.
-- ---------------------------------------------------------------------------------------------
INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, note, status,
                                     recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.hid('receipt:' || o.key || ':1'), o.customer_id, 'FT' || pg_temp.ymd(o.s - 1) || (91000 + o.seq * 10 + 1),
       pg_temp.day(o.s - 1), o.deposit, 'VND', 'Đặt cọc hợp đồng ' || o.contract_number, 'RECORDED',
       pg_temp.staff('demo.ketoan'), pg_temp.at(o.s - 1, '10:30'), 0, pg_temp.at(o.s - 1, '10:30'),
       pg_temp.staff('demo.ketoan')::text
  FROM h_order o WHERE o.outcome <> 'PENDING_PAYMENT';

INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount, created_at)
SELECT pg_temp.hid('receipt-line:' || o.key || ':1'), pg_temp.hid('receipt:' || o.key || ':1'),
       pg_temp.hid('installment:' || o.key || ':1'), o.deposit, pg_temp.at(o.s - 1, '10:30')
  FROM h_order o WHERE o.outcome <> 'PENDING_PAYMENT';

UPDATE payment.installment i
   SET paid_amount = o.deposit, status = 'PAID', paid_at = pg_temp.at(o.s - 1, '10:30'), version = 1,
       last_modified_at = pg_temp.at(o.s - 1, '10:30'), last_modified_by = pg_temp.staff('demo.ketoan')::text
  FROM h_order o
 WHERE i.id = pg_temp.hid('installment:' || o.key || ':1') AND o.outcome <> 'PENDING_PAYMENT';

UPDATE ordering.customer_order co
   SET status = 'CONFIRMED', version = 3, last_modified_at = pg_temp.at(o.s - 1, '10:30'), last_modified_by = 'system'
  FROM h_order o WHERE co.id = o.id AND o.outcome <> 'PENDING_PAYMENT';

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.hid('order-history:' || o.key || ':3'), o.id, 'PENDING_PAYMENT', 'CONFIRMED', 'INSTALLMENT_PAID', NULL,
       pg_temp.at(o.s - 1, '10:30'), pg_temp.at(o.s - 1, '10:30'), 'system'
  FROM h_order o WHERE o.outcome <> 'PENDING_PAYMENT';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 11. Shipping warehouse (17 §4.2): Sales chooses DNI the afternoon the order is confirmed. Nothing is there, so
--     nothing is held: every carton is to be produced.
-- ---------------------------------------------------------------------------------------------
UPDATE ordering.customer_order co
   SET shipping_warehouse_id = pg_temp.hid('wh:DNI'), shipping_warehouse_chosen_at = pg_temp.at(o.s - 1, '14:00'),
       shipping_warehouse_chosen_by = pg_temp.staff('demo.sales'), version = 4,
       last_modified_at = pg_temp.at(o.s - 1, '14:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM h_order o WHERE co.id = o.id AND o.outcome <> 'PENDING_PAYMENT';

-- ---------------------------------------------------------------------------------------------
-- 12. Production orders (17 §4.3): drawn up by the coordinator for the batch, approved by the Sales Manager - another
--     person (BR-23) - and sent; the producer accepts and prints. The first print of a SKU would need a print sample;
--     it was waived on the approved print file (BR-24). The order waits AWAITING_STOCK from the sending.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                       receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                       sample_required_by_default, sample_waiver_reason, unit_price, currency,
                                       drawn_up_by, version, created_at, created_by)
SELECT o.po_id, o.po_code, o.id, o.batch_id, o.customer_id, pg_temp.hid('producer:' || o.producer),
       pg_temp.hid('wh:DNI'), o.window_start - 3, 'DRAFT', FALSE, o.plate,
       CASE WHEN o.plate THEN 'Khách đã duyệt bản in thử trên file in; miễn mẫu in' END, 520, 'VND',
       pg_temp.staff('demo.dieuphoi'), 0, pg_temp.at(o.s - 1, '15:00'), pg_temp.staff('demo.dieuphoi')::text
  FROM h_order o WHERE o.po_id IS NOT NULL;

INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                            design_snapshot_id, print_file_checksum)
SELECT o.po_line_id, o.po_id, o.line_id, o.sku, o.cartons, 1000, o.snapshot_id, a.checksum
  FROM h_order o JOIN design.design_artifact a ON a.id = pg_temp.hid('artifact:' || o.cust)
 WHERE o.po_id IS NOT NULL;

UPDATE ordering.production_order p
   SET status = 'PENDING_APPROVAL', submitted_at = pg_temp.at(o.s - 1, '16:00'), version = 1,
       last_modified_at = pg_temp.at(o.s - 1, '16:00'), last_modified_by = pg_temp.staff('demo.dieuphoi')::text
  FROM h_order o WHERE p.id = o.po_id;

UPDATE ordering.production_order p
   SET status = 'SENT', approved_by = pg_temp.staff('demo.truongkd'), approved_at = pg_temp.at(o.s - 2, '09:00'),
       sent_at = pg_temp.at(o.s - 2, '09:00'), version = 2, last_modified_at = pg_temp.at(o.s - 2, '09:00'),
       last_modified_by = pg_temp.staff('demo.truongkd')::text
  FROM h_order o WHERE p.id = o.po_id;

-- The slip the producer was sent (17 §4.3 step 8); db/demo stores no file, so it is named, not kept.
INSERT INTO ordering.production_order_slip (production_order_id, storage_key, sha256, size_bytes, created_at)
SELECT o.po_id, 'documents/demo/' || o.po_code || '.pdf',
       encode(sha256(convert_to('demo:history:slip:' || o.key, 'UTF8')), 'hex'), 26214, pg_temp.at(o.s - 2, '09:00')
  FROM h_order o WHERE o.po_id IS NOT NULL;

UPDATE ordering.customer_order co
   SET status = 'AWAITING_STOCK', version = 5, last_modified_at = pg_temp.at(o.s - 2, '09:00'),
       last_modified_by = pg_temp.staff('demo.truongkd')::text
  FROM h_order o WHERE co.id = o.id AND o.po_id IS NOT NULL;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.hid('order-history:' || o.key || ':4'), o.id, 'CONFIRMED', 'AWAITING_STOCK', 'PRODUCTION_ORDER_SENT',
       pg_temp.staff('demo.truongkd'), pg_temp.at(o.s - 2, '09:00'), pg_temp.at(o.s - 2, '09:00'),
       pg_temp.staff('demo.truongkd')::text
  FROM h_order o WHERE o.po_id IS NOT NULL;

UPDATE ordering.production_order p
   SET status = 'ACCEPTED', expected_completion_date = CASE WHEN o.d IS NOT NULL THEN pg_temp.day(o.r + 1)
                                                            ELSE o.window_start - 5 END,
       version = 3, last_modified_at = pg_temp.at(o.s - 3, '09:00'),
       last_modified_by = pg_temp.staff('demo.dieuphoi')::text
  FROM h_order o WHERE p.id = o.po_id;

UPDATE ordering.production_order p
   SET status = 'IN_PRODUCTION', production_started_at = pg_temp.at(o.s - 4, '08:00'), version = 4,
       last_modified_at = pg_temp.at(o.s - 4, '08:00'), last_modified_by = pg_temp.staff('demo.dieuphoi')::text
  FROM h_order o WHERE p.id = o.po_id;

-- BR-11: the order records when printing started, once - what the cancellation fee rule reads.
UPDATE ordering.customer_order co
   SET printing_started_at = pg_temp.at(o.s - 4, '08:00'), version = 6,
       last_modified_at = pg_temp.at(o.s - 4, '08:00'), last_modified_by = pg_temp.staff('demo.dieuphoi')::text
  FROM h_order o WHERE co.id = o.id AND o.po_id IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 13. The printed goods received at DNI (03 §4.1), in cartons of the standard count labelled while counted, in the
--     production order's lot (BR-03), and confirmed into INBOUND cartons on the receiving dock
-- ---------------------------------------------------------------------------------------------
INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                        delivery_note, version, created_at, created_by, receipt_type,
                                        production_order_id)
SELECT pg_temp.hid('receipt-goods:' || o.key), 'GR-' || pg_temp.ymd(o.r) || '-' || (9100 + o.seq),
       pg_temp.hid('wh:DNI'), 'DRAFT', pg_temp.at(o.r, '08:00'), pg_temp.staff('demo.kho.dn'),
       'PXK-' || o.po_code, 0, pg_temp.at(o.r, '08:00'), pg_temp.staff('demo.kho.dn')::text, 'PRODUCTION', o.po_id
  FROM h_order o WHERE o.r IS NOT NULL;

INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status, opened_at)
SELECT o.po_id, pg_temp.hid('receipt-goods:' || o.key), 'GR-' || pg_temp.ymd(o.r) || '-' || (9100 + o.seq), 'DRAFT',
       pg_temp.at(o.r, '08:00')
  FROM h_order o WHERE o.r IS NOT NULL;

INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty, lot_number,
                                             version, created_at, created_by, uom_conversion_id,
                                             production_order_line_id)
SELECT pg_temp.hid('receipt-goods-line:' || o.key), pg_temp.hid('receipt-goods:' || o.key), i.id,
       pg_temp.hid('location:DNI-RCV01'), count(c.n) * 1000, o.po_code, 0, pg_temp.at(o.r, '08:20'),
       pg_temp.staff('demo.kho.dn')::text, o.unit_id, o.po_line_id
  FROM h_order o
  JOIN inventory.inventory_items i ON i.sku = o.sku
  JOIN h_carton c ON c.key = o.key
 GROUP BY o.key, o.r, o.po_code, o.unit_id, o.po_line_id, i.id;

INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status, goods_receipt_line_id, version, created_at, created_by)
SELECT c.package_id, c.lpn, i.id, o.unit_id, 1000, 1000, 'DNI-RCV01', 'RECEIVING',
       pg_temp.hid('receipt-goods-line:' || o.key), 0, pg_temp.at(o.r, '08:30'), pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_order o ON o.key = c.key JOIN inventory.inventory_items i ON i.sku = o.sku;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Confirmed (03 §4.1 step 6): the lot, the cartons in stock on the dock, one RECEIPT line each, a putaway task each.
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, warehouse_id, version, created_at,
                                 created_by, production_order_id, production_order_code)
SELECT pg_temp.hid('lot:' || o.key), o.sku, o.po_code, pg_temp.at(o.r, '09:00'), 'PRODUCTION_ORDER',
       pg_temp.hid('wh:DNI'), 0, pg_temp.at(o.r, '09:00'), pg_temp.staff('demo.kho.dn')::text, o.po_id, o.po_code
  FROM h_order o WHERE o.r IS NOT NULL;

UPDATE procurement.goods_receipts g
   SET status = 'IN_PUTAWAY', confirmed_at = pg_temp.at(o.r, '09:00'), confirmed_by = pg_temp.staff('demo.kho.dn'),
       version = 1, last_modified_at = pg_temp.at(o.r, '09:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_order o WHERE g.id = pg_temp.hid('receipt-goods:' || o.key);

UPDATE ordering.production_order_receipt r
   SET status = 'CONFIRMED', settled_at = pg_temp.at(o.r, '09:00')
  FROM h_order o WHERE r.goods_receipt_id = pg_temp.hid('receipt-goods:' || o.key);

UPDATE ordering.production_order_line l
   SET received_cartons = (SELECT count(*) FROM h_carton c WHERE c.key = o.key)
  FROM h_order o WHERE l.id = o.po_line_id AND o.r IS NOT NULL;

UPDATE ordering.production_order p
   SET status = CASE WHEN o.d IS NOT NULL THEN 'RECEIVED' ELSE 'PARTIALLY_RECEIVED' END, version = 5,
       last_modified_at = pg_temp.at(o.r, '09:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_order o WHERE p.id = o.po_id AND o.r IS NOT NULL;

UPDATE inventory.stock_package p
   SET status = 'IN_STOCK', version = 1, last_modified_at = pg_temp.at(o.r, '09:00'),
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_order o ON o.key = c.key WHERE p.id = c.package_id;

INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, version, created_at,
                                  created_by, received_at, stock_package_id, lot_id)
SELECT c.stock_item_id, o.sku, 'DNI-RCV01', o.po_code, 1000, 0, 'INBOUND', 0, pg_temp.at(o.r, '09:00'),
       pg_temp.staff('demo.kho.dn')::text, pg_temp.at(o.r, '09:00'), c.package_id, pg_temp.hid('lot:' || o.key)
  FROM h_carton c JOIN h_order o ON o.key = c.key;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      to_stock_package_id, occurred_at, version, created_at, created_by)
SELECT pg_temp.hid('ledger:receipt:' || o.key || ':' || c.n), 'RECEIPT', o.sku, o.po_code, pg_temp.hid('lot:' || o.key),
       NULL, 'DNI-RCV01', 1000, NULL, 'INBOUND', 'GOODS_RECEIPT_LINE', pg_temp.hid('receipt-goods-line:' || o.key),
       pg_temp.staff('demo.kho.dn'), c.package_id, pg_temp.at(o.r, '09:00'), 0, pg_temp.at(o.r, '09:00'),
       pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_order o ON o.key = c.key;

INSERT INTO warehouse.putaway_task (id, goods_receipt_id, sku, quantity, status, version, created_at, created_by,
                                    goods_receipt_line_id, stock_package_id, source)
SELECT c.task_id, pg_temp.hid('receipt-goods:' || o.key), o.sku, 1000, 'PENDING', 0, pg_temp.at(o.r, '09:00'), 'system',
       pg_temp.hid('receipt-goods-line:' || o.key), c.package_id, 'RECEIPT'
  FROM h_carton c JOIN h_order o ON o.key = c.key;

INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status, to_status, reason,
                                                      changed_by, changed_at)
SELECT pg_temp.hid('po-history:' || o.key || ':' || h.n), o.po_id, h.from_status, h.to_status, h.reason,
       pg_temp.staff(h.who), CASE h.n WHEN 0 THEN pg_temp.at(o.s - 1, '15:00') WHEN 1 THEN pg_temp.at(o.s - 1, '16:00')
                                      WHEN 2 THEN pg_temp.at(o.s - 2, '09:00') WHEN 3 THEN pg_temp.at(o.s - 3, '09:00')
                                      WHEN 4 THEN pg_temp.at(o.s - 4, '08:00') ELSE pg_temp.at(o.r, '09:00') END
  FROM h_order o
 CROSS JOIN LATERAL (VALUES (0, NULL, 'DRAFT', NULL, 'demo.dieuphoi'),
                            (1, 'DRAFT', 'PENDING_APPROVAL', NULL, 'demo.dieuphoi'),
                            (2, 'PENDING_APPROVAL', 'SENT', NULL, 'demo.truongkd'),
                            (3, 'SENT', 'ACCEPTED', NULL, 'demo.dieuphoi'),
                            (4, 'ACCEPTED', 'IN_PRODUCTION', NULL, 'demo.dieuphoi'),
                            (5, 'IN_PRODUCTION', CASE WHEN o.d IS NOT NULL THEN 'RECEIVED' ELSE 'PARTIALLY_RECEIVED' END,
                             CASE WHEN o.d IS NOT NULL
                                  THEN 'Received in full on receipt GR-' || pg_temp.ymd(o.r) || '-' || (9100 + o.seq)
                                  ELSE 'Received in part on receipt GR-' || pg_temp.ymd(o.r) || '-' || (9100 + o.seq)
                                       || '; the rest stays open (03 BR-10)' END, 'demo.kho.dn'))
             AS h (n, from_status, to_status, reason, who)
 WHERE o.po_id IS NOT NULL AND (h.n < 5 OR o.r IS NOT NULL);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 14. Putaway (05): one task per carton, each carton whole into a bin that fits it - a minute a carton from 10:00
-- ---------------------------------------------------------------------------------------------
UPDATE inventory.stock_package p
   SET location_code = c.bin, version = 2,
       last_modified_at = pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_order o ON o.key = c.key WHERE p.id = c.package_id;

UPDATE inventory.stock_item s
   SET location_code = c.bin, status = 'AVAILABLE', version = 1,
       last_modified_at = pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_order o ON o.key = c.key WHERE s.id = c.stock_item_id;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      from_stock_package_id, to_stock_package_id, occurred_at, version, created_at,
                                      created_by)
SELECT pg_temp.hid('ledger:putaway:' || o.key || ':' || c.n), 'MOVE', o.sku, o.po_code, pg_temp.hid('lot:' || o.key),
       'DNI-RCV01', c.bin, 1000, 'INBOUND', 'AVAILABLE', 'PUTAWAY_TASK', c.task_id, pg_temp.staff('demo.kho.dn'),
       c.package_id, c.package_id, pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval, 0,
       pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval, pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_order o ON o.key = c.key;

UPDATE warehouse.putaway_task t
   SET status = 'DONE', target_location_id = pg_temp.hid('location:' || c.bin), version = 1,
       last_modified_at = pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_order o ON o.key = c.key WHERE t.id = c.task_id;

UPDATE procurement.goods_receipts g
   SET status = 'CLOSED', closed_at = pg_temp.at(o.r, '11:00'), version = 2, last_modified_at = pg_temp.at(o.r, '11:00'),
       last_modified_by = 'system'
  FROM h_order o WHERE g.id = pg_temp.hid('receipt-goods:' || o.key);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Put away, the goods top up the order waiting for them (17 §4.2 step 5): a hold of the SKU at DNI, its batch's.
INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at, status, sku,
                                         warehouse_code)
SELECT pg_temp.hid('hold:' || o.key), o.id, pg_temp.hid('hold-request:' || o.key), pg_temp.hid('hold-request:' || o.key),
       count(c.n) * 1000, pg_temp.at(o.r, '11:00'), 'HELD', o.sku, 'DNI'
  FROM h_order o JOIN h_carton c ON c.key = o.key
 GROUP BY o.key, o.id, o.r, o.sku;

INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id, created_at)
SELECT pg_temp.hid('hold:' || o.key), o.batch_id, o.id, o.line_id, pg_temp.at(o.r, '11:00')
  FROM h_order o WHERE o.r IS NOT NULL;

INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id)
SELECT o.line_id, pg_temp.hid('hold:' || o.key) FROM h_order o WHERE o.r IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 15. Release (17 §4.4, 4B-8): the warehouse manager releases the batch three days before it leaves; its hold is
--     allocated carton by carton (whole sealed cartons, FIFO); a payment before delivery falls due.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_delivered ON COMMIT DROP AS SELECT * FROM h_order WHERE d IS NOT NULL;

INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
SELECT o.batch_id, o.id, 1, pg_temp.hid('wh:DNI'), pg_temp.at(o.d + 3, '09:00'), pg_temp.staff('demo.quanly.dn')
  FROM h_delivered o;

UPDATE ordering.customer_order co
   SET status = 'READY_TO_FULFILL', warehouse_id = pg_temp.hid('wh:DNI'), released_at = pg_temp.at(o.d + 3, '09:00'),
       released_by = pg_temp.staff('demo.quanly.dn'), version = 7, last_modified_at = pg_temp.at(o.d + 3, '09:00'),
       last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o WHERE co.id = o.id;

UPDATE inventory.stock_reservation r
   SET status = 'ALLOCATED', closed_at = pg_temp.at(o.d + 3, '09:00')
  FROM h_delivered o WHERE r.id = pg_temp.hid('hold:' || o.key);

INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity,
                                         reserved_at, status, sku, allocated_from_id)
SELECT c.allocation_id, c.stock_item_id, o.id, pg_temp.hid('hold:' || o.key),
       pg_temp.hid('allocation-request:' || o.key || ':' || c.n), 1000, pg_temp.at(o.d + 3, '09:00'), 'HELD', o.sku,
       pg_temp.hid('hold:' || o.key)
  FROM h_carton c JOIN h_delivered o ON o.key = c.key;

UPDATE inventory.stock_item s
   SET reserved = 1000, version = 2, last_modified_at = pg_temp.at(o.d + 3, '09:00'),
       last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key WHERE s.id = c.stock_item_id;

INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id, created_at)
SELECT c.allocation_id, o.batch_id, o.id, o.line_id, pg_temp.at(o.d + 3, '09:00')
  FROM h_carton c JOIN h_delivered o ON o.key = c.key;

-- The line swaps its hold for the allocations (CLAUDE.md §4: all of them, not the first).
DELETE FROM ordering.order_line_reservation l
 USING h_delivered o WHERE l.order_line_id = o.line_id AND l.reservation_id = pg_temp.hid('hold:' || o.key);
INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id)
SELECT o.line_id, c.allocation_id FROM h_carton c JOIN h_delivered o ON o.key = c.key;

UPDATE payment.installment i
   SET reached_at = pg_temp.at(o.d + 3, '09:00'), status = 'DUE', version = 1,
       last_modified_at = pg_temp.at(o.d + 3, '09:00'), last_modified_by = 'system'
  FROM h_delivered o
 WHERE i.order_id = o.id AND i.milestone = 'BEFORE_DELIVERY';

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.hid('order-history:' || o.key || ':5'), o.id, 'AWAITING_STOCK', 'READY_TO_FULFILL', NULL, NULL,
       pg_temp.at(o.d + 3, '09:00'), pg_temp.at(o.d + 3, '09:00'), pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o;

-- The customer without credit pays what is due before delivery the day after the release (15 BR-08).
INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, note, status,
                                     recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.hid('receipt:' || o.key || ':2'), o.customer_id, 'FT' || pg_temp.ymd(o.d + 2) || (91000 + o.seq * 10 + 2),
       pg_temp.day(o.d + 2), o.rest, 'VND', 'Thanh toán trước giao hàng hợp đồng ' || o.contract_number, 'RECORDED',
       pg_temp.staff('demo.ketoan'), pg_temp.at(o.d + 2, '10:30'), 0, pg_temp.at(o.d + 2, '10:30'),
       pg_temp.staff('demo.ketoan')::text
  FROM h_delivered o WHERE NOT o.credit;

INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount, created_at)
SELECT pg_temp.hid('receipt-line:' || o.key || ':2'), pg_temp.hid('receipt:' || o.key || ':2'),
       pg_temp.hid('installment:' || o.key || ':2'), o.rest, pg_temp.at(o.d + 2, '10:30')
  FROM h_delivered o WHERE NOT o.credit;

UPDATE payment.installment i
   SET paid_amount = o.rest, status = 'PAID', paid_at = pg_temp.at(o.d + 2, '10:30'), version = 2,
       last_modified_at = pg_temp.at(o.d + 2, '10:30'), last_modified_by = pg_temp.staff('demo.ketoan')::text
  FROM h_delivered o WHERE i.id = pg_temp.hid('installment:' || o.key || ':2') AND NOT o.credit;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 16. Picking by LPN (07): the manager opens the task to dispatch for the warehouse staff the next morning; each
--     carton is scanned at its bin and deducted at the scan (07 BR-10, BR-04)
-- ---------------------------------------------------------------------------------------------
INSERT INTO fulfillment.pick (id, order_id, status, version, created_at, created_by, assigned_user_id, assigned_at,
                              order_number, warehouse_id, destination, delivery_batch_id)
SELECT pg_temp.hid('pick:' || o.key), o.id, 'PENDING', 0, pg_temp.at(o.d + 2, '07:30'),
       pg_temp.staff('demo.quanly.dn')::text, pg_temp.staff('demo.kho.dn'), pg_temp.at(o.d + 2, '07:30'),
       o.order_number, pg_temp.hid('wh:DNI'), 'DISPATCH', o.batch_id
  FROM h_delivered o;

INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, lot_number,
                                   sequence_no, allocated_qty, picked_qty, short_qty, status, version, created_at,
                                   created_by, lpn, whole_carton)
SELECT c.pick_line_id, pg_temp.hid('pick:' || o.key), o.line_id, c.allocation_id, o.sku, c.bin, o.po_code, c.n, 1000, 0,
       0, 'OPEN', 0, pg_temp.at(o.d + 2, '07:30'), pg_temp.staff('demo.quanly.dn')::text, c.lpn, TRUE
  FROM h_carton c JOIN h_delivered o ON o.key = c.key;

-- The check station's session is opened with the task (fulfillment.pack, 4C-6).
INSERT INTO fulfillment.pack (id, pick_id, status, version, created_at, created_by)
SELECT pg_temp.hid('pack:' || o.key), pg_temp.hid('pick:' || o.key), 'PENDING', 0, pg_temp.at(o.d + 2, '07:30'),
       pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o;

UPDATE ordering.customer_order co
   SET status = 'PICKING', fulfilment_started_at = pg_temp.at(o.d + 2, '07:30'), version = 8,
       last_modified_at = pg_temp.at(o.d + 2, '07:30'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o WHERE co.id = o.id;

UPDATE fulfillment.pick p
   SET status = 'PICKING', started_at = pg_temp.at(o.d + 2, '08:00'), version = 1,
       last_modified_at = pg_temp.at(o.d + 2, '08:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_delivered o WHERE p.id = pg_temp.hid('pick:' || o.key);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      from_stock_package_id, order_id, occurred_at, version, created_at, created_by)
SELECT pg_temp.hid('ledger:pick:' || o.key || ':' || c.n), 'PICK', o.sku, o.po_code, pg_temp.hid('lot:' || o.key), c.bin,
       NULL, 1000, 'AVAILABLE', NULL, 'PICK_LINE', c.pick_line_id, pg_temp.staff('demo.kho.dn'), c.package_id, o.id,
       pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval, 0,
       pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval, pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key;

UPDATE inventory.stock_item s
   SET on_hand = 0, reserved = 0, version = 3,
       last_modified_at = pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key WHERE s.id = c.stock_item_id;

UPDATE inventory.stock_package p
   SET status = 'CONSUMED', closed_at = pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval, version = 3,
       last_modified_at = pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key WHERE p.id = c.package_id;

UPDATE inventory.stock_reservation r
   SET status = 'CONSUMED', closed_at = pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval
  FROM h_carton c JOIN h_delivered o ON o.key = c.key WHERE r.id = c.allocation_id;

UPDATE fulfillment.pick_line l
   SET status = 'PICKED', picked_qty = 1000, picked_by = pg_temp.staff('demo.kho.dn'),
       picked_at = pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval,
       consumed_at = pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval, scanned_location_code = c.bin,
       scanned_code = c.lpn, version = 1, last_modified_at = pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key WHERE l.id = c.pick_line_id;

UPDATE fulfillment.pick p
   SET status = 'PICKED', picked_at = pg_temp.at(o.d + 2, '09:00'), version = 2,
       last_modified_at = pg_temp.at(o.d + 2, '09:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_delivered o WHERE p.id = pg_temp.hid('pick:' || o.key);

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.hid('order-history:' || o.key || ':6'), o.id, 'READY_TO_FULFILL', 'PICKING', NULL,
       pg_temp.staff('demo.quanly.dn'), pg_temp.at(o.d + 2, '07:30'), pg_temp.at(o.d + 2, '07:30'),
       pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 17. Carton check and labelling (08, 4C-6): each LPN carton is the shipping carton, checked and labelled with its
--     carton spec; completion makes the labelled cartons one shipment and the order PACKED
-- ---------------------------------------------------------------------------------------------
UPDATE fulfillment.pack k
   SET status = 'IN_PROGRESS', started_at = pg_temp.at(o.d + 2, '13:00'), started_by = pg_temp.staff('demo.kho.dn'),
       version = 1, last_modified_at = pg_temp.at(o.d + 2, '13:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_delivered o WHERE k.id = pg_temp.hid('pack:' || o.key);

INSERT INTO fulfillment.package (id, pack_id, package_no, status, version, created_at, created_by, label_code,
                                 rotatable, rotatable_overridden, pick_line_id, lpn)
SELECT c.shipping_package_id, pg_temp.hid('pack:' || o.key), c.n, 'TO_CHECK', 0, pg_temp.at(o.d + 2, '13:00'),
       pg_temp.staff('demo.kho.dn')::text, o.order_number || '-C' || c.n, TRUE, FALSE, c.pick_line_id, c.lpn
  FROM h_carton c JOIN h_delivered o ON o.key = c.key;

UPDATE fulfillment.package p
   SET status = 'CHECKED', checked_at = pg_temp.at(o.d + 2, '13:00') + (c.n || ' minutes')::interval,
       checked_by = pg_temp.staff('demo.kho.dn'), version = 1,
       last_modified_at = pg_temp.at(o.d + 2, '13:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key WHERE p.id = c.shipping_package_id;

-- The first label locks the order's cancellation and address (OrderService.recordFirstLabel).
UPDATE ordering.customer_order co
   SET first_labeled_at = pg_temp.at(o.d + 2, '14:00'), version = 9, last_modified_at = pg_temp.at(o.d + 2, '14:00'),
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_delivered o WHERE co.id = o.id;

-- Labelled from the SKU's carton spec (08 BR-07, BR-09): 60 x 40 x 50 cm, 8.6 kg, volumetric 60*40*50/6000 = 20 kg.
UPDATE fulfillment.package p
   SET status = 'LABELED', weight_kg = 8.6, length_cm = 60, width_cm = 40, height_cm = 50, dim_weight_kg = 20,
       labeled_at = pg_temp.at(o.d + 2, '14:00'), labeled_by = pg_temp.staff('demo.kho.dn'), label_version = 1,
       version = 2, last_modified_at = pg_temp.at(o.d + 2, '14:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key WHERE p.id = c.shipping_package_id;

INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, version, created_at, created_by, code, warehouse_id,
                                  package_count, partial, ready_at, ready_by, whole_order_handover)
SELECT pg_temp.hid('shipment:' || o.key), pg_temp.hid('pack:' || o.key), o.id, 'READY_TO_DISPATCH', 0,
       pg_temp.at(o.d + 2, '14:30'), pg_temp.staff('demo.kho.dn')::text, o.order_number || '-S1', pg_temp.hid('wh:DNI'),
       o.cartons, FALSE, pg_temp.at(o.d + 2, '14:30'), pg_temp.staff('demo.kho.dn'), FALSE
  FROM h_delivered o;

UPDATE fulfillment.package p
   SET shipment_id = pg_temp.hid('shipment:' || o.key), version = 3
  FROM h_carton c JOIN h_delivered o ON o.key = c.key WHERE p.id = c.shipping_package_id;

UPDATE fulfillment.pack k
   SET status = 'COMPLETED', packed_at = pg_temp.at(o.d + 2, '14:30'), packed_by = pg_temp.staff('demo.kho.dn'),
       version = 2, last_modified_at = pg_temp.at(o.d + 2, '14:30'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_delivered o WHERE k.id = pg_temp.hid('pack:' || o.key);

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at, created_by,
                                        recorded_by)
SELECT pg_temp.hid('shipment-event:' || o.key || ':0'), pg_temp.hid('shipment:' || o.key), 'READY_TO_DISPATCH',
       pg_temp.at(o.d + 2, '14:30'), 'MANUAL', 0, pg_temp.at(o.d + 2, '14:30'), pg_temp.staff('demo.kho.dn')::text,
       pg_temp.staff('demo.kho.dn')
  FROM h_delivered o;

UPDATE ordering.customer_order co
   SET status = 'PACKED', packed_at = pg_temp.at(o.d + 2, '14:30'), version = 10,
       last_modified_at = pg_temp.at(o.d + 2, '14:30'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_delivered o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.hid('order-history:' || o.key || ':7'), o.id, 'PICKING', 'PACKED', NULL, pg_temp.staff('demo.kho.dn'),
       pg_temp.at(o.d + 2, '14:30'), pg_temp.at(o.d + 2, '14:30'), pg_temp.staff('demo.kho.dn')::text
  FROM h_delivered o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 18. A full truckload (09, LP): the load plan of the shipment on a 2.5 t truck, calculated and confirmed the day
--     before; the truck of Xe tải Minh Phát booked with its waybill; loaded by scanning every carton; handed over at
--     08:00 and delivered the same afternoon
-- ---------------------------------------------------------------------------------------------
INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, source_type, selection_mode, status,
                                   calculated_at, calculated_by, solver_meta, confirmed_at, confirmed_by, revised_at,
                                   version, created_at, created_by)
SELECT pg_temp.hid('load-plan:' || o.key), 'LP-' || pg_temp.ymd(o.d + 1) || '-' || (9100 + o.seq),
       pg_temp.hid('load-plan-request:' || o.key), pg_temp.hid('wh:DNI'), 'ORDER', 'SINGLE_TYPE', 'CONFIRMED',
       pg_temp.at(o.d + 1, '10:05'), pg_temp.staff('demo.quanly.dn'),
       '{"algorithm": "GRASP_KIT", "setsTotal": 0, "kitWarnings": [], "minVehicles": 1, "budgetExhausted": false}'::jsonb,
       pg_temp.at(o.d + 1, '10:10'), pg_temp.staff('demo.quanly.dn'), pg_temp.at(o.d + 1, '10:10'), 2,
       pg_temp.at(o.d + 1, '10:00'), pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o;

INSERT INTO fulfillment.load_plan_vehicle_type (id, load_plan_id, vehicle_type_id, sort_order, version, created_at,
                                                created_by)
SELECT pg_temp.hid('load-plan-type:' || o.key), pg_temp.hid('load-plan:' || o.key), t.id, 0, 0,
       pg_temp.at(o.d + 1, '10:00'), pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5';

INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, shipment_id, active, added_at, added_by,
                                           version, created_at, created_by)
SELECT pg_temp.hid('load-plan-package:' || o.key || ':' || c.n), pg_temp.hid('load-plan:' || o.key),
       c.shipping_package_id, pg_temp.hid('shipment:' || o.key), TRUE, pg_temp.at(o.d + 1, '10:00'),
       pg_temp.staff('demo.quanly.dn'), 0, pg_temp.at(o.d + 1, '10:00'), pg_temp.staff('demo.quanly.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key;

-- One truck: 4,300 x 1,800 x 1,800 mm inside; the cartons four across, three high, row after row.
INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, active, vehicle_type_id, vehicle_code, vehicle_kind,
                                           inner_length_mm, inner_width_mm, inner_height_mm, max_payload_kg, gusset_mm,
                                           used_volume_mm3, used_weight_kg, volume_fill_pct, weight_fill_pct,
                                           needs_recalculation, version, created_at, created_by)
SELECT pg_temp.hid('load-plan-vehicle:' || o.key), pg_temp.hid('load-plan:' || o.key), 1, TRUE, t.id, t.code, t.kind,
       t.inner_length_mm, t.inner_width_mm, t.inner_height_mm, t.max_payload_kg, t.gusset_mm,
       o.cartons * 120000000::bigint, o.cartons * 8.6,
       round(o.cartons * 120000000.0 * 100 / (t.inner_length_mm::numeric * t.inner_width_mm * t.inner_height_mm), 2),
       round(o.cartons * 8.6 * 100 / t.max_payload_kg, 2), FALSE, 0, pg_temp.at(o.d + 1, '10:05'),
       pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5';

INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm,
                                        h_mm, d_mm, orientation, version, created_at, created_by)
SELECT pg_temp.hid('load-placement:' || o.key || ':' || c.n), pg_temp.hid('load-plan:' || o.key),
       pg_temp.hid('load-plan-vehicle:' || o.key), c.shipping_package_id, ((c.n - 1) % 4) * 400,
       (((c.n - 1) / 4) % 3) * 500, ((c.n - 1) / 12) * 600, 400, 500, 600, 0, 0, pg_temp.at(o.d + 1, '10:05'),
       pg_temp.staff('demo.quanly.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key;

-- The plan confirmed makes the truck's load (PLANNED), with the shipment on it.
INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, load_plan_vehicle_id, status,
                                       shipment_count, package_count, version, created_at, created_by)
SELECT pg_temp.hid('load:' || o.key), 'LD-' || pg_temp.ymd(o.d + 1) || '-' || (9100 + o.seq),
       pg_temp.hid('load-request:' || o.key), pg_temp.hid('wh:DNI'), pg_temp.hid('load-plan-vehicle:' || o.key),
       'PLANNED', 0, 0, 0, pg_temp.at(o.d + 1, '10:10'), pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o;

INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, active, added_at, added_by, version,
                                                created_at, created_by)
SELECT pg_temp.hid('load-shipment:' || o.key), pg_temp.hid('load:' || o.key), pg_temp.hid('shipment:' || o.key), TRUE,
       pg_temp.at(o.d + 1, '10:10'), pg_temp.staff('demo.quanly.dn'), 0, pg_temp.at(o.d + 1, '10:10'),
       pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o;

UPDATE fulfillment.dispatch_load l
   SET status = 'VEHICLE_BOOKED', carrier_id = c.id, vehicle_plate = '60C-' || (51200 + o.seq * 37),
       driver_name = 'Lý Văn Tài', driver_phone = '0909000222', waybill_no = 'MP-' || pg_temp.ymd(o.d + 1) || '-' || o.seq,
       booked_at = pg_temp.at(o.d + 1, '15:00'), booked_by = pg_temp.staff('demo.quanly.dn'), version = 1,
       last_modified_at = pg_temp.at(o.d + 1, '15:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o, fulfillment.carrier c
 WHERE l.id = pg_temp.hid('load:' || o.key) AND c.code = 'XT-MINHPHAT';

INSERT INTO fulfillment.waybill_change (id, load_id, new_waybill_no, source, changed_by, changed_at, version,
                                        created_at, created_by)
SELECT pg_temp.hid('waybill:' || o.key), pg_temp.hid('load:' || o.key), 'MP-' || pg_temp.ymd(o.d + 1) || '-' || o.seq,
       'MANUAL', pg_temp.staff('demo.quanly.dn'), pg_temp.at(o.d + 1, '15:00'), 0, pg_temp.at(o.d + 1, '15:00'),
       pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o;

UPDATE fulfillment.dispatch_load l
   SET status = 'LOADING', loading_started_at = pg_temp.at(o.d, '07:00'), loading_started_by = pg_temp.staff('demo.kho.dn'),
       version = 2, last_modified_at = pg_temp.at(o.d, '07:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_delivered o WHERE l.id = pg_temp.hid('load:' || o.key);

INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by,
                                               active, version, created_at, created_by)
SELECT pg_temp.hid('load-package:' || o.key || ':' || c.n), pg_temp.hid('load:' || o.key), c.shipping_package_id,
       pg_temp.hid('shipment:' || o.key), pg_temp.hid('scan:' || o.key || ':' || c.n),
       pg_temp.at(o.d, '07:00') + (c.n || ' seconds')::interval * 40, pg_temp.staff('demo.kho.dn'), TRUE, 0,
       pg_temp.at(o.d, '07:00') + (c.n || ' seconds')::interval * 40, pg_temp.staff('demo.kho.dn')::text
  FROM h_carton c JOIN h_delivered o ON o.key = c.key;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Handed over at 08:00 (09 §4.2 step 8): the load leaves, the shipment and the order are shipped.
UPDATE fulfillment.dispatch_load l
   SET status = 'DISPATCHED', dispatched_at = pg_temp.at(o.d, '08:00'), dispatched_by = pg_temp.staff('demo.quanly.dn'),
       carrier_representative = 'Lý Văn Tài', shipment_count = 1, package_count = o.cartons, version = 3,
       last_modified_at = pg_temp.at(o.d, '08:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o WHERE l.id = pg_temp.hid('load:' || o.key);

UPDATE fulfillment.load_plan p
   SET status = 'DISPATCHED', dispatched_at = pg_temp.at(o.d, '08:00'), revised_at = pg_temp.at(o.d, '08:00'),
       version = 3, last_modified_at = pg_temp.at(o.d, '08:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o WHERE p.id = pg_temp.hid('load-plan:' || o.key);

UPDATE fulfillment.shipment s
   SET status = 'HANDED_OVER', handed_over_at = pg_temp.at(o.d, '08:00'), handed_over_by = pg_temp.staff('demo.quanly.dn'),
       carrier_id = c.id, load_id = pg_temp.hid('load:' || o.key), version = 1,
       last_modified_at = pg_temp.at(o.d, '08:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o, fulfillment.carrier c
 WHERE s.id = pg_temp.hid('shipment:' || o.key) AND c.code = 'XT-MINHPHAT';

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at, created_by,
                                        recorded_by)
SELECT pg_temp.hid('shipment-event:' || o.key || ':1'), pg_temp.hid('shipment:' || o.key), 'HANDED_OVER',
       pg_temp.at(o.d, '08:00'), 'MANUAL', 0, pg_temp.at(o.d, '08:00'), pg_temp.staff('demo.quanly.dn')::text,
       pg_temp.staff('demo.quanly.dn')
  FROM h_delivered o;

INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, handed_over_by, version, created_at,
                                     created_by, shipment_code, package_count, carrier_name, delivery_batch_id)
SELECT pg_temp.hid('shipment:' || o.key), o.id, 'HANDED_OVER', o.total, pg_temp.at(o.d, '08:00'),
       pg_temp.staff('demo.quanly.dn'), 0, pg_temp.at(o.d, '08:00'), pg_temp.staff('demo.quanly.dn')::text,
       o.order_number || '-S1', o.cartons, 'Xe tải Minh Phát', o.batch_id
  FROM h_delivered o;

INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity)
SELECT pg_temp.hid('shipment:' || o.key), o.line_id, o.cartons * 1000 FROM h_delivered o;

UPDATE ordering.customer_order co
   SET status = 'SHIPPED', shipped_at = pg_temp.at(o.d, '08:00'), goods_left_at = pg_temp.at(o.d, '08:00'),
       version = 11, last_modified_at = pg_temp.at(o.d, '08:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.hid('order-history:' || o.key || ':8'), o.id, 'PACKED', 'SHIPPED', NULL, pg_temp.staff('demo.quanly.dn'),
       pg_temp.at(o.d, '08:00'), pg_temp.at(o.d, '08:00'), pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Delivered at 15:00 (09 §4.3): what the order pays after delivery is due from today, its days to pay counted.
UPDATE fulfillment.shipment s
   SET status = 'DELIVERED', outcome_at = pg_temp.at(o.d, '15:00'), outcome_by = pg_temp.staff('demo.quanly.dn'),
       version = 2, last_modified_at = pg_temp.at(o.d, '15:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o WHERE s.id = pg_temp.hid('shipment:' || o.key);

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at, created_by,
                                        recorded_by)
SELECT pg_temp.hid('shipment-event:' || o.key || ':2'), pg_temp.hid('shipment:' || o.key), 'DELIVERED',
       pg_temp.at(o.d, '15:00'), 'MANUAL', 0, pg_temp.at(o.d, '15:00'), pg_temp.staff('demo.quanly.dn')::text,
       pg_temp.staff('demo.quanly.dn')
  FROM h_delivered o;

UPDATE ordering.order_shipment s
   SET status = 'DELIVERED', outcome_at = pg_temp.at(o.d, '15:00'), outcome_by = pg_temp.staff('demo.quanly.dn'),
       version = 1, last_modified_at = pg_temp.at(o.d, '15:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o WHERE s.id = pg_temp.hid('shipment:' || o.key);

UPDATE ordering.customer_order co
   SET status = 'DELIVERED', delivered_at = pg_temp.at(o.d, '15:00'), version = 12,
       last_modified_at = pg_temp.at(o.d, '15:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.hid('order-history:' || o.key || ':9'), o.id, 'SHIPPED', 'DELIVERED', NULL,
       pg_temp.staff('demo.quanly.dn'), pg_temp.at(o.d, '15:00'), pg_temp.at(o.d, '15:00'),
       pg_temp.staff('demo.quanly.dn')::text
  FROM h_delivered o;

UPDATE payment.installment i
   SET reached_at = pg_temp.at(o.d, '15:00'), due_date = pg_temp.day(o.d) + o.term_days, status = 'DUE', version = 1,
       last_modified_at = pg_temp.at(o.d, '15:00'), last_modified_by = 'system'
  FROM h_delivered o WHERE i.order_id = o.id AND i.milestone = 'AFTER_DELIVERY';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 19. Payments after delivery (15 §4.2): three paid in full, one half - on the days the transfers came in. The rest
--     stays owed: the receivables report ages it.
-- ---------------------------------------------------------------------------------------------
INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, note, status,
                                     recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.hid('receipt:' || o.key || ':2'), o.customer_id,
       'FT' || pg_temp.ymd(o.paid_after) || (91000 + o.seq * 10 + 2), pg_temp.day(o.paid_after),
       round(o.rest * o.paid_part, 0), 'VND',
       'Thanh toán sau giao hàng hợp đồng ' || o.contract_number || CASE WHEN o.paid_part < 1 THEN ' (một phần)' ELSE '' END,
       'RECORDED', pg_temp.staff('demo.ketoan'), pg_temp.at(o.paid_after, '10:30'), 0, pg_temp.at(o.paid_after, '10:30'),
       pg_temp.staff('demo.ketoan')::text
  FROM h_delivered o WHERE o.paid_after IS NOT NULL;

INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount, created_at)
SELECT pg_temp.hid('receipt-line:' || o.key || ':2'), pg_temp.hid('receipt:' || o.key || ':2'),
       pg_temp.hid('installment:' || o.key || ':2'), round(o.rest * o.paid_part, 0), pg_temp.at(o.paid_after, '10:30')
  FROM h_delivered o WHERE o.paid_after IS NOT NULL;

UPDATE payment.installment i
   SET paid_amount = round(o.rest * o.paid_part, 0), status = CASE WHEN o.paid_part = 1 THEN 'PAID' ELSE 'PARTIALLY_PAID' END,
       paid_at = CASE WHEN o.paid_part = 1 THEN pg_temp.at(o.paid_after, '10:30') END, version = 2,
       last_modified_at = pg_temp.at(o.paid_after, '10:30'), last_modified_by = pg_temp.staff('demo.ketoan')::text
  FROM h_delivered o WHERE i.id = pg_temp.hid('installment:' || o.key || ':2') AND o.paid_after IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 20. A return (17 §4.10, 4D-2, 4D-3): the day after O07 was delivered Cà Phê Sáng reports three cartons crushed in
--     transit, with photos, on the portal; Sales approves it and the goods must come back. Five days before the build
--     day they come back on a return receipt, scanned by LPN, into DNI's quarantine area (03 §4.2, BR-14). The refund
--     is still to be proposed (SETTLEMENT_PENDING comes next): the order is RETURN_REQUESTED.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_return ON COMMIT DROP AS
SELECT o.*, pg_temp.hid('return:' || o.key) AS return_id, 'RMA-' || pg_temp.ymd(o.d - 1) || '-9101' AS rma_number,
       pg_temp.hid('return-receipt:' || o.key) AS receipt_id, 'GR-' || pg_temp.ymd(o.d - 5) || '-9150' AS receipt_number
  FROM h_order o WHERE o.key = 'O07';

CREATE TEMP TABLE h_returned ON COMMIT DROP AS
SELECT c.* FROM h_carton c WHERE c.key = 'O07' AND c.n IN (7, 8, 9);

INSERT INTO ordering.return_request (id, rma_number, order_id, customer_id, status, reason_code, note, requested_at,
                                     version, created_at, created_by, order_number, request_id, channel, requested_by,
                                     requested_resolution, currency, settlement_amount, refunded_amount, offset_amount,
                                     vat_adjustment_required)
SELECT r.return_id, r.rma_number, r.id, r.customer_id, 'REQUESTED', 'DAMAGED_IN_TRANSIT',
       'Ba thùng bị móp, ly bên trong bị bẹp; ảnh chụp lúc nhận hàng đính kèm.', pg_temp.at(r.d - 1, '09:00'), 0,
       pg_temp.at(r.d - 1, '09:00'), pg_temp.hid('user:demo.kh.caphesang')::text, r.order_number,
       pg_temp.hid('return-request:' || r.key), 'PORTAL', pg_temp.hid('user:demo.kh.caphesang'), 'REFUND', 'VND', 0, 0, 0,
       FALSE
  FROM h_return r;

INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, quantity, version, created_at,
                                         created_by, sku, custom_printed, unit_price)
SELECT pg_temp.hid('return-line:' || r.key), r.return_id, r.line_id, 3000, 0, pg_temp.at(r.d - 1, '09:00'),
       pg_temp.hid('user:demo.kh.caphesang')::text, r.sku, TRUE, r.unit_price
  FROM h_return r;

INSERT INTO ordering.return_request_evidence (id, return_request_id, stage, file_key, original_name, content_type,
                                             size_bytes, uploaded_by, uploaded_at, created_at, created_by)
SELECT pg_temp.hid('return-photo:' || r.key || ':' || n), r.return_id, 'CLAIM',
       'returns/demo/' || r.rma_number || '-' || n || '.jpg', 'thung-mop-' || n || '.jpg', 'image/jpeg', 182400 + n,
       pg_temp.hid('user:demo.kh.caphesang'), pg_temp.at(r.d - 1, '09:00'), pg_temp.at(r.d - 1, '09:00'),
       pg_temp.hid('user:demo.kh.caphesang')::text
  FROM h_return r CROSS JOIN generate_series(1, 2) n;

UPDATE ordering.customer_order co
   SET status = 'RETURN_REQUESTED', version = 13, last_modified_at = pg_temp.at(r.d - 1, '09:00'),
       last_modified_by = pg_temp.hid('user:demo.kh.caphesang')::text
  FROM h_return r WHERE co.id = r.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.hid('order-history:' || r.key || ':10'), r.id, 'DELIVERED', 'RETURN_REQUESTED', r.rma_number,
       pg_temp.hid('user:demo.kh.caphesang'), pg_temp.at(r.d - 1, '09:00'), pg_temp.at(r.d - 1, '09:00'),
       pg_temp.hid('user:demo.kh.caphesang')::text
  FROM h_return r;

UPDATE ordering.return_request_line l
   SET approved_quantity = 3000, version = 1, last_modified_at = pg_temp.at(r.d - 2, '10:00'),
       last_modified_by = pg_temp.staff('demo.sales')::text
  FROM h_return r WHERE l.return_request_id = r.return_id;

UPDATE ordering.return_request q
   SET status = 'AWAITING_RETURN', decided_by = pg_temp.staff('demo.sales'), decided_at = pg_temp.at(r.d - 2, '10:00'),
       decision_note = 'Ảnh cho thấy thùng móp do vận chuyển; nhận lại 3 thùng để kiểm.', return_required = TRUE,
       return_deadline = pg_temp.at(r.d - 2, '10:00') + INTERVAL '14 days', version = 1,
       last_modified_at = pg_temp.at(r.d - 2, '10:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM h_return r WHERE q.id = r.return_id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                        delivery_note, version, created_at, created_by, receipt_type, customer_order_id,
                                        return_request_id, quarantine_location_id)
SELECT r.receipt_id, r.receipt_number, pg_temp.hid('wh:DNI'), 'DRAFT', pg_temp.at(r.d - 5, '09:00'),
       pg_temp.staff('demo.kho.dn'), r.rma_number, 0, pg_temp.at(r.d - 5, '09:00'), pg_temp.staff('demo.kho.dn')::text,
       'RETURN', r.id, r.return_id, pg_temp.hid('location:DNI-QC01')
  FROM h_return r;

INSERT INTO procurement.goods_receipt_return_cartons (id, receipt_id, stock_package_id, lpn, sku, lot_number, quantity,
                                                      condition_note, scanned_by, version, created_at, created_by)
SELECT pg_temp.hid('return-carton:' || c.key || ':' || c.n), r.receipt_id, c.package_id, c.lpn, r.sku, r.po_code, 1000,
       'Thùng móp một góc, ly bên trong bẹp', pg_temp.staff('demo.kho.dn'), 0,
       pg_temp.at(r.d - 5, '09:10') + (c.n || ' minutes')::interval, pg_temp.staff('demo.kho.dn')::text
  FROM h_returned c CROSS JOIN h_return r;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Confirmed (4D-2): each carton back whole and sealed into quarantine, in its own lot, one RECEIPT line each.
UPDATE inventory.stock_package p
   SET status = 'IN_STOCK', location_code = 'DNI-QC01', closed_at = NULL, version = 4,
       last_modified_at = pg_temp.at(r.d - 5, '09:30'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_returned c CROSS JOIN h_return r WHERE p.id = c.package_id;

-- The carton's own row comes back to its count: the same receipt layer, so the row is named to the FIFO policy
-- trigger for the one statement, as StockItemRepositoryAdapter does (stockflow.same_receipt_merge).
DO $$
DECLARE
    returned RECORD;
BEGIN
    FOR returned IN SELECT c.stock_item_id, r.d FROM h_returned c CROSS JOIN h_return r ORDER BY c.stock_item_id LOOP
        PERFORM set_config('stockflow.same_receipt_merge', returned.stock_item_id::text, true);
        UPDATE inventory.stock_item s
           SET location_code = 'DNI-QC01', status = 'QUARANTINE', on_hand = 1000, version = 4,
               last_modified_at = pg_temp.at(returned.d - 5, '09:30'),
               last_modified_by = pg_temp.staff('demo.kho.dn')::text
         WHERE s.id = returned.stock_item_id;
    END LOOP;
    PERFORM set_config('stockflow.same_receipt_merge', '', true);
END $$;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      to_stock_package_id, order_id, occurred_at, version, created_at, created_by)
SELECT pg_temp.hid('ledger:return:' || c.key || ':' || c.n), 'RECEIPT', r.sku, r.po_code, pg_temp.hid('lot:' || r.key),
       NULL, 'DNI-QC01', 1000, 'QUARANTINE', 'QUARANTINE', 'GOODS_RECEIPT', r.receipt_id, pg_temp.staff('demo.kho.dn'),
       c.package_id, r.id, pg_temp.at(r.d - 5, '09:30'), 0, pg_temp.at(r.d - 5, '09:30'),
       pg_temp.staff('demo.kho.dn')::text
  FROM h_returned c CROSS JOIN h_return r;

UPDATE procurement.goods_receipts g
   SET status = 'CLOSED', confirmed_at = pg_temp.at(r.d - 5, '09:30'), confirmed_by = pg_temp.staff('demo.kho.dn'),
       closed_at = pg_temp.at(r.d - 5, '09:30'), version = 1, last_modified_at = pg_temp.at(r.d - 5, '09:30'),
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_return r WHERE g.id = r.receipt_id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Received (ReturnedGoodsListener, after the receipt's commit): the return names what came back and where.
UPDATE ordering.return_request_line l
   SET received_quantity = 3000, version = 2, last_modified_at = pg_temp.at(r.d - 5, '09:30'), last_modified_by = 'system'
  FROM h_return r WHERE l.return_request_id = r.return_id;

UPDATE ordering.return_request q
   SET status = 'RECEIVED', goods_receipt_id = r.receipt_id, received_at = pg_temp.at(r.d - 5, '09:30'),
       received_by = pg_temp.staff('demo.kho.dn'), receiving_location_code = 'DNI-QC01', version = 2,
       last_modified_at = pg_temp.at(r.d - 5, '09:30'), last_modified_by = 'system'
  FROM h_return r WHERE q.id = r.return_id;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.hid('return-history:' || r.key || ':' || h.n), r.return_id, h.from_status, h.to_status, h.reason, h.actor,
       h.at, h.at, h.actor::text
  FROM h_return r
 CROSS JOIN LATERAL (VALUES (0, NULL, 'REQUESTED', NULL, pg_temp.hid('user:demo.kh.caphesang'), pg_temp.at(r.d - 1, '09:00')),
                            (1, 'REQUESTED', 'AWAITING_RETURN', NULL, pg_temp.staff('demo.sales'),
                             pg_temp.at(r.d - 2, '10:00')),
                            (2, 'AWAITING_RETURN', 'RECEIVED', r.receipt_number, NULL, pg_temp.at(r.d - 5, '09:30')))
             AS h (n, from_status, to_status, reason, actor, at);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 21. A count of the quarantine area (20 §4.3, 4C-3): the warehouse staff scans the three returned cartons three days
--     before the build day and finds two; the count's variance is one carton (COUNT_VARIANCE), which the warehouse
--     manager - not the counter (BR-05) - approves the next day: the carton is written off as lost.
-- ---------------------------------------------------------------------------------------------
INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, is_blind, assigned_user_id,
                                   planned_date, note, version, created_at, created_by)
VALUES (pg_temp.hid('count:DNI-QC01'), 'CC-' || pg_temp.ymd(3) || '-9101', pg_temp.hid('wh:DNI'), 'MANUAL', 'PLANNED',
        TRUE, pg_temp.staff('demo.kho.dn'), pg_temp.day(3), 'Kiểm khu cách ly sau khi nhận hàng hoàn', 0,
        pg_temp.at(4, '16:00'), pg_temp.staff('demo.quanly.dn')::text);

INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, lot_number, expected_qty, version,
                                        created_at, created_by, stock_package_id)
SELECT pg_temp.hid('count-line:' || c.key || ':' || c.n), pg_temp.hid('count:DNI-QC01'), 'DNI-QC01', r.sku, r.po_code,
       1000, 0, pg_temp.at(4, '16:00'), pg_temp.staff('demo.quanly.dn')::text, c.package_id
  FROM h_returned c CROSS JOIN h_return r;

UPDATE inventory.cycle_count
   SET status = 'IN_PROGRESS', started_at = pg_temp.at(3, '08:00'), version = 1, last_modified_at = pg_temp.at(3, '08:00'),
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
 WHERE id = pg_temp.hid('count:DNI-QC01');

UPDATE inventory.cycle_count_line l
   SET counted_qty = CASE WHEN c.n = 9 THEN 0 ELSE 1000 END, counted_by = pg_temp.staff('demo.kho.dn'),
       counted_at = pg_temp.at(3, '08:10') + (c.n || ' minutes')::interval, version = 1,
       last_modified_at = pg_temp.at(3, '08:10') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM h_returned c WHERE l.id = pg_temp.hid('count-line:' || c.key || ':' || c.n);

UPDATE inventory.cycle_count
   SET status = 'COMPLETED', completed_at = pg_temp.at(3, '08:30'), version = 2, last_modified_at = pg_temp.at(3, '08:30'),
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
 WHERE id = pg_temp.hid('count:DNI-QC01');

INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, lot_number, quantity_delta,
                                        reason_code, note, status, cycle_count_line_id, requested_by, version,
                                        created_at, created_by, stock_package_id, counted_by)
SELECT pg_temp.hid('adjustment:' || c.key || ':' || c.n), 'ADJ-' || pg_temp.ymd(3) || '-9101', 'DNI-QC01', r.sku,
       r.po_code, -1000, 'COUNT_VARIANCE', 'Kiểm kê khu cách ly: không thấy thùng ' || c.lpn, 'PENDING_APPROVAL',
       pg_temp.hid('count-line:' || c.key || ':' || c.n), pg_temp.staff('demo.kho.dn'), 0, pg_temp.at(3, '08:30'),
       pg_temp.staff('demo.kho.dn')::text, c.package_id, pg_temp.staff('demo.kho.dn')
  FROM h_returned c CROSS JOIN h_return r WHERE c.n = 9;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE inventory.stock_adjustment a
   SET status = 'POSTED', decided_by = pg_temp.staff('demo.quanly.dn'), decided_at = pg_temp.at(2, '09:00'),
       posted_at = pg_temp.at(2, '09:00'), version = 1, last_modified_at = pg_temp.at(2, '09:00'),
       last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_returned c WHERE a.id = pg_temp.hid('adjustment:' || c.key || ':' || c.n) AND c.n = 9;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, reason, actor_id,
                                      from_stock_package_id, requested_by, counted_by, occurred_at, version,
                                      created_at, created_by)
SELECT pg_temp.hid('ledger:adjustment:' || c.key || ':' || c.n), 'ADJUSTMENT', r.sku, r.po_code,
       pg_temp.hid('lot:' || r.key), 'DNI-QC01', NULL, 1000, 'QUARANTINE', NULL, 'STOCK_ADJUSTMENT',
       pg_temp.hid('adjustment:' || c.key || ':' || c.n), 'COUNT_VARIANCE', pg_temp.staff('demo.quanly.dn'),
       c.package_id, pg_temp.staff('demo.kho.dn'), pg_temp.staff('demo.kho.dn'), pg_temp.at(2, '09:00'), 0,
       pg_temp.at(2, '09:00'), pg_temp.staff('demo.quanly.dn')::text
  FROM h_returned c CROSS JOIN h_return r WHERE c.n = 9;

UPDATE inventory.stock_item s
   SET on_hand = 0, version = 5, last_modified_at = pg_temp.at(2, '09:00'),
       last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_returned c WHERE s.id = c.stock_item_id AND c.n = 9;

UPDATE inventory.stock_package p
   SET status = 'LOST', closed_at = pg_temp.at(2, '09:00'), version = 5, last_modified_at = pg_temp.at(2, '09:00'),
       last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM h_returned c WHERE p.id = c.package_id AND c.n = 9;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 22. The capacity warning (06 §4.6 step 8, C42): O10's production order, approved 13 days before the build day,
--     took DNI's projected free room below zero - 33.6 m³ of bins, less the cartons then in them (O07's, 0.12 m³ each),
--     less O09's production order still to come - and the warehouse manager was told once, WARN, in the inbox.
-- ---------------------------------------------------------------------------------------------
INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, body, link, source_type,
                                            source_id, dedupe_key, created_at)
SELECT pg_temp.hid('capacity-warn:' || o.key), pg_temp.staff('demo.quanly.dn'), 'WAREHOUSE_CAPACITY', 'WARN',
       'Kho Kho Đồng Nai sẽ thiếu chỗ',
       format('Đơn sản xuất %s vừa gửi làm còn trống dự kiến của kho %s xuống âm %s m³. Mở thêm bin, dọn chỗ hoặc báo '
                  || 'Điều phối sản xuất trước khi hàng về.', o.po_code, 'Kho Đồng Nai',
              to_char(-(33.6
                        - (SELECT count(*) * 0.12 FROM h_carton c JOIN h_order p ON p.key = c.key
                            WHERE p.r > o.s - 2 AND (p.d IS NULL OR p.d + 2 < o.s - 2))
                        - (SELECT coalesce(sum(p.cartons), 0) * 0.12 FROM h_order p
                            WHERE p.po_id IS NOT NULL AND p.key <> o.key AND p.s - 2 > o.s - 2
                              AND (p.r IS NULL OR p.r < o.s - 2))
                        - o.cartons * 0.12), 'FM990.00')),
       '/warehouse/capacity/' || pg_temp.hid('wh:DNI'), 'WAREHOUSE', pg_temp.hid('wh:DNI')::text,
       'capacity-negative:' || pg_temp.hid('wh:DNI') || ':' || o.po_id, pg_temp.at(o.s - 2, '09:00')
  FROM h_order o WHERE o.key = 'O10';

-- ---------------------------------------------------------------------------------------------
-- The session's helpers go with it.
-- ---------------------------------------------------------------------------------------------
DROP FUNCTION pg_temp.staff(TEXT);
DROP FUNCTION pg_temp.ymd(INTEGER);
DROP FUNCTION pg_temp.day(INTEGER);
DROP FUNCTION pg_temp.at(INTEGER, TEXT);
DROP FUNCTION pg_temp.today();
DROP FUNCTION pg_temp.hid(TEXT);
