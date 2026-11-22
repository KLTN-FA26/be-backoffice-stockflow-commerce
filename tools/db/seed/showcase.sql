-- =============================================================================
-- Showcase seed for the shared dev server (VPS): six months of a printed-cup business, loaded once into a freshly
-- migrated database. NOT a Flyway migration and not db/demo (which the tests own): run it by hand with psql after the
-- application has migrated an empty database - tools/db/seed/README.md says how.
--
-- What it holds (days are "days before the day it is loaded"):
--   * the team's staff accounts (admin, sales1, ketoan, dieuphoi, quanly.bd, kho.hn1, ...) and two warehouses:
--     BDG "Kho Bình Dương" (24 shelves, 288 bins) and HNI "Kho Hà Nội" (16 shelves, 192 bins), each walled, with
--     dock doors, an emergency exit, a walled quarantine room, an office, and receiving, quarantine, dispatch,
--     staging and overflow areas;
--   * five printable products (paper cup, PET cup, kraft bowl, cake box, paper bag) with their print templates;
--   * 40 B2B customers - portal account, legal profile, addresses, most with credit - and, on each customer's approved
--     designs, 2 or 3 SKUs of its own (about 100), each with its carton spec and the customer's price tiers;
--   * six producers and three truck carriers;
--   * 300 quotes, 271 of them orders, every one through the real chain as far as its stage: quote sent and accepted,
--     converted, contract sent and signed, deposit, shipping warehouse, production order sent by the coordinator,
--     goods received in LPN cartons and put away, held, released, picked by LPN, checked
--     and labelled, a load plan, a truck handed over and delivered; payments after delivery paid, half paid or owed.
--     Stages: quotes in draft / sent / changes requested / expired / cancelled, orders in draft / cancelled /
--     PENDING_CONTRACT / PENDING_PAYMENT / CONFIRMED / AWAITING_STOCK (production order sent, accepted, printing, part
--     received, all received and held) / READY_TO_FULFILL / PICKING / PACKED / SHIPPED, and 186 DELIVERED.
-- What it leaves out: e-invoices, chat, files (every storage key it names is a placeholder; no object exists for it),
-- events (event_publication stays empty, so nothing is mailed when it loads) and the sales rollups (the application
-- rebuilds them on its next start: SalesRollupBackfill).
--
-- Every trigger is on and the steps run in the order the application writes them, so every CHECK and trigger judges
-- these rows as it judges the application's. Ids are md5('showcase:...')::uuid and every "random" choice is a hash of
-- its key, so loading it twice on the same day gives the same rows. Staff and customer passwords are the published
-- demo one (Stockflow@2026); README.md says how to keep the old ones on a server being reset.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
SET CONSTRAINTS ALL DEFERRED;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM identity.app_user) OR EXISTS (SELECT 1 FROM warehouse.warehouse)
        OR EXISTS (SELECT 1 FROM customer.customer) THEN
        RAISE EXCEPTION 'showcase seed: the database already holds users, warehouses or customers; it loads only into a freshly migrated database';
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 0. Helpers (session-local, dropped at the end)
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION pg_temp.sid(key TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT md5('showcase:' || key)::uuid $$;

-- A number in [0, 1) that depends only on the key.
CREATE FUNCTION pg_temp.rnd(key TEXT) RETURNS DOUBLE PRECISION LANGUAGE sql IMMUTABLE AS
$$ SELECT ('x' || substr(md5('showcase:rnd:' || key), 1, 8))::bit(32)::bigint / 4294967296.0 $$;

-- An integer in [lo, hi] that depends only on the key.
CREATE FUNCTION pg_temp.pick(key TEXT, lo INTEGER, hi INTEGER) RETURNS INTEGER LANGUAGE sql IMMUTABLE AS
$$ SELECT lo + floor(pg_temp.rnd(key) * (hi - lo + 1))::int $$;

CREATE FUNCTION pg_temp.today() RETURNS DATE LANGUAGE sql STABLE AS
$$ SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date $$;

CREATE FUNCTION pg_temp.at(days_ago INTEGER, hhmi TEXT) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT ((pg_temp.today() - days_ago) + hhmi::time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

CREATE FUNCTION pg_temp.day(days_ago INTEGER) RETURNS DATE LANGUAGE sql STABLE AS
$$ SELECT pg_temp.today() - days_ago $$;

CREATE FUNCTION pg_temp.ymd(days_ago INTEGER) RETURNS TEXT LANGUAGE sql STABLE AS
$$ SELECT to_char(pg_temp.today() - days_ago, 'YYYYMMDD') $$;

-- The published demo password, Stockflow@2026 (the same hash db/demo uses).
CREATE FUNCTION pg_temp.pw() RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT '{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y'::text $$;

-- ---------------------------------------------------------------------------------------------
-- 1. Staff
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE s_staff (username TEXT PRIMARY KEY, email TEXT, full_name TEXT, role TEXT, wh TEXT, status TEXT)
    ON COMMIT DROP;
INSERT INTO s_staff VALUES
    ('admin', 'admin@stockflow.io.vn', 'Quản trị hệ thống', 'SYSTEM_ADMIN', NULL, 'ACTIVE'),
    ('sf.admin', 'sf.admin@stockflow.test', 'Quản trị StockFlow', 'SYSTEM_ADMIN', NULL, 'ACTIVE'),
    ('truongkd', 'truongkd@stockflow.example', 'Phạm Minh Trưởng', 'SALES_MANAGER', NULL, 'ACTIVE'),
    ('sf.sales', 'sf.sales@stockflow.test', 'Lê Thu Hà', 'SALES_MANAGER', NULL, 'ACTIVE'),
    ('sales1', 'sales1@stockflow.example', 'Nguyễn Thị Lan', 'SALES_STAFF', NULL, 'ACTIVE'),
    ('sales2', 'sales2@stockflow.example', 'Trần Quốc Bảo', 'SALES_STAFF', NULL, 'ACTIVE'),
    ('ketoan', 'ketoan@stockflow.example', 'Hoàng Thị Kế', 'ACCOUNTANT', NULL, 'ACTIVE'),
    ('dieuphoi', 'dieuphoi@stockflow.example', 'Võ Thị Điều Phối', 'PRODUCTION_COORDINATOR', NULL, 'ACTIVE'),
    ('quanly.bd', 'quanly.bd@stockflow.example', 'Đỗ Văn Quản', 'WAREHOUSE_MANAGER', 'BDG', 'ACTIVE'),
    ('quanly.hn', 'quanly.hn@stockflow.example', 'Ngô Thị Hà', 'WAREHOUSE_MANAGER', 'HNI', 'ACTIVE'),
    ('kho.bd1', 'kho.bd1@stockflow.example', 'Bùi Văn Kho', 'WAREHOUSE_STAFF', 'BDG', 'ACTIVE'),
    ('kho.bd2', 'kho.bd2@stockflow.example', 'Lý Văn Tâm', 'WAREHOUSE_STAFF', 'BDG', 'ACTIVE'),
    ('kho.hn1', 'kho.hn1@stockflow.example', 'Đặng Văn Hùng', 'WAREHOUSE_STAFF', 'HNI', 'ACTIVE'),
    ('kho.hn2', 'kho.hn2@stockflow.example', 'Phan Thị Mai', 'WAREHOUSE_STAFF', 'HNI', 'ACTIVE'),
    ('nv.bikhoa', 'nv.bikhoa@stockflow.example', 'Trương Văn Khóa', 'WAREHOUSE_STAFF', 'BDG', 'LOCKED'),
    ('nv.nghiviec', 'nv.nghiviec@stockflow.example', 'Mai Văn Nghỉ', 'WAREHOUSE_STAFF', 'HNI', 'DISABLED');

INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
SELECT pg_temp.sid('user:' || username), username, email, pg_temp.pw(), full_name, status, 0, pg_temp.at(230, '08:00'),
       'seed'
  FROM s_staff;

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT pg_temp.sid('user-role:' || s.username), pg_temp.sid('user:' || s.username), r.id, 0, pg_temp.at(230, '08:00'),
       'seed'
  FROM s_staff s JOIN identity.app_role r ON r.code = s.role;

CREATE FUNCTION pg_temp.staff(username TEXT) RETURNS UUID LANGUAGE sql STABLE AS
$$ SELECT id FROM identity.app_user WHERE username = $1 $$;

-- ---------------------------------------------------------------------------------------------
-- 2. Warehouses BDG and HNI: shelves of bins, and the five kinds of area (06 §4.1)
-- ---------------------------------------------------------------------------------------------
-- The map: shelf pairs standing back to back, 10 m long and one 1.2 m pallet deep, the top one of a pair picked from
-- the north and the bottom one from the south, with a 3.6 m aisle between pairs and 3 m cross aisles between columns.
-- BDG has three columns, HNI two; eight rows each, zone A the top four, zone B the bottom four. The service areas
-- stand east of the shelves, by the dock doors in the east wall: receiving at the top, dispatch at the bottom.
CREATE TEMP TABLE s_wh (prefix TEXT PRIMARY KEY, name TEXT, address TEXT, cols INTEGER, shelves INTEGER,
                        area_x NUMERIC, map_w NUMERIC, manager TEXT, staff1 TEXT, staff2 TEXT) ON COMMIT DROP;
INSERT INTO s_wh
SELECT prefix, name, address, cols, cols * 8, cols * 13 + 7, cols * 13 + 25, manager, staff1, staff2
  FROM (VALUES ('BDG', 'Kho Bình Dương', 'Số 8 Đường số 6, KCN Sóng Thần 1, Phường Dĩ An, TP. Hồ Chí Minh', 3,
                'quanly.bd', 'kho.bd1', 'kho.bd2'),
               ('HNI', 'Kho Hà Nội', 'Lô CN3, KCN Quang Minh, Xã Quang Minh, TP. Hà Nội', 2, 'quanly.hn', 'kho.hn1',
                'kho.hn2')) AS w (prefix, name, address, cols, manager, staff1, staff2);

INSERT INTO warehouse.warehouse (id, name, status, prefix, address, return_address, map_unit, map_width, map_height,
                                 version, created_at, created_by)
SELECT pg_temp.sid('wh:' || prefix), name, 'ACTIVE', prefix, address, address, 'M', map_w, 40, 0,
       pg_temp.at(230, '08:00'), 'seed'
  FROM s_wh;

INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at, created_by)
SELECT pg_temp.sid('user-warehouse:' || username), pg_temp.sid('user:' || username), pg_temp.sid('wh:' || wh), 0,
       pg_temp.at(230, '08:05'), 'seed'
  FROM s_staff WHERE wh IS NOT NULL;

INSERT INTO warehouse.zone (id, warehouse_id, name, color, version, created_at, created_by)
SELECT pg_temp.sid('zone:' || w.prefix || ':' || z.code), pg_temp.sid('wh:' || w.prefix), z.name, z.color, 0,
       pg_temp.at(230, '08:10'), 'seed'
  FROM s_wh w CROSS JOIN (VALUES ('A', 'Khu A - Ly và tô', '#4F81BD'), ('B', 'Khu B - Hộp và túi', '#9BBB59')) AS z (code, name, color);

-- Shelves A01, A02, ... in zone A, then B01, ... in zone B; each three levels of four 2.4 x 1.2 m bins.
CREATE TEMP TABLE s_shelf ON COMMIT DROP AS
SELECT w.prefix, k, CASE WHEN k <= w.shelves / 2 THEN 'A' ELSE 'B' END AS zone,
       CASE WHEN k <= w.shelves / 2 THEN 'A' || lpad(k::text, 2, '0')
            ELSE 'B' || lpad((k - w.shelves / 2)::text, 2, '0') END AS code,
       4 + ((k - 1) % w.cols) * 13 AS x,
       4 + ((k - 1) / w.cols / 2) * 6 + ((k - 1) / w.cols % 2) * 1.2 AS y,
       (k - 1) / w.cols % 2 = 0 AS north
  FROM s_wh w CROSS JOIN LATERAL generate_series(1, w.shelves) k;

INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation, is_obstacle,
                             pick_north, pick_east, pick_south, pick_west, status, version, created_at, created_by)
SELECT pg_temp.sid('shelf:' || prefix || '-' || code), pg_temp.sid('wh:' || prefix),
       pg_temp.sid('zone:' || prefix || ':' || zone), code, 'Kệ ' || code, x, y, 10, 1.2, 0, TRUE, north, FALSE,
       NOT north, FALSE, 'ACTIVE', 0, pg_temp.at(230, '08:10'), 'seed'
  FROM s_shelf;

INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight, version, created_at,
                                   created_by)
SELECT pg_temp.sid('level:' || s.prefix || '-' || s.code || '-' || n), pg_temp.sid('shelf:' || s.prefix || '-' || s.code),
       n, (n - 1) * 1.7, 1.5, 2000, 0, pg_temp.at(230, '08:10'), 'seed'
  FROM s_shelf s CROSS JOIN generate_series(1, 3) n;

CREATE TEMP TABLE s_bin ON COMMIT DROP AS
SELECT s.prefix, s.code AS shelf, n AS lvl, b, s.prefix || '-' || s.code || '-' || n || '-' || b AS code,
       row_number() OVER (PARTITION BY s.prefix ORDER BY s.k, n, b) AS idx
  FROM s_shelf s CROSS JOIN generate_series(1, 3) n CROSS JOIN unnest(ARRAY['A', 'B', 'C', 'D']) b;

-- A location and the bin or area that owns it go in together: tg_storage_location_owned checks it at commit.
INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight, is_pickable,
                                        is_putaway_target, status, version, created_at, created_by)
SELECT pg_temp.sid('location:' || code), pg_temp.sid('wh:' || prefix), 'BIN', code, NULL::numeric, TRUE, TRUE, 'ACTIVE', 0,
       pg_temp.at(230, '08:10'), 'seed'
  FROM s_bin
UNION ALL
SELECT pg_temp.sid('location:' || w.prefix || '-' || a.code), pg_temp.sid('wh:' || w.prefix), 'AREA',
       w.prefix || '-' || a.code, NULL, FALSE, FALSE, 'ACTIVE', 0, pg_temp.at(230, '08:10'), 'seed'
  FROM s_wh w CROSS JOIN (VALUES ('RCV01'), ('QC01'), ('DSP01'), ('STG01'), ('OVF01')) AS a (code);

INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type, version, created_at,
                           created_by)
SELECT pg_temp.sid('bin:' || code), pg_temp.sid('level:' || prefix || '-' || shelf || '-' || lvl),
       pg_temp.sid('location:' || code), b, (ascii(b) - 65) * 2.5, 0, 2.4, 1.2, 0, 'PALLET', 0,
       pg_temp.at(230, '08:10'), 'seed'
  FROM s_bin;

INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle,
                            status, version, created_at, created_by)
SELECT pg_temp.sid('area:' || w.prefix || '-' || a.code), pg_temp.sid('wh:' || w.prefix),
       pg_temp.sid('location:' || w.prefix || '-' || a.code), a.code, a.type, a.name,
       CASE WHEN a.by_dock THEN w.area_x ELSE 4 END, a.y, a.w, a.l, 0, FALSE, 'ACTIVE', 0,
       pg_temp.at(230, '08:10'), 'seed'
  FROM s_wh w
 CROSS JOIN (VALUES ('RCV01', 'RECEIVING', 'Khu nhận hàng', TRUE, 2, 14, 7),
                    ('QC01', 'QUARANTINE', 'Khu cách ly', TRUE, 12, 8, 6),
                    ('STG01', 'STAGING', 'Khu tập kết', TRUE, 20, 10, 5),
                    ('DSP01', 'DISPATCH', 'Khu chờ giao', TRUE, 26, 14, 7),
                    ('OVF01', 'OVERFLOW', 'Khu tràn', FALSE, 30, 12, 6)) AS a (code, type, name, by_dock, y, w, l);

-- Walls and doors (06 §4.1): the outer walls, with two receiving and two dispatch dock doors in the east wall (the
-- second dispatch dock shut) and an emergency exit in the west wall; the quarantine area walled in as a room with a
-- shut door; the warehouse office in the south-east corner. W is the map's width, Q the quarantine room's west edge.
INSERT INTO warehouse.boundary (id, warehouse_id, type, start_x, start_y, end_x, end_y, is_passable, operational_status,
                                version, created_at, created_by)
SELECT pg_temp.sid('boundary:' || w.prefix || ':' || b.key), pg_temp.sid('wh:' || w.prefix), b.type,
       b.sx, b.sy, b.ex, b.ey, b.passable, b.status, 0, pg_temp.at(230, '08:10'), 'seed'
  FROM s_wh w
 CROSS JOIN LATERAL (VALUES
        ('wall-north',       'WALL', 0, 0, w.map_w, 0, FALSE, NULL),
        ('wall-south',       'WALL', 0, 40, w.map_w, 40, FALSE, NULL),
        ('wall-west-1',      'WALL', 0, 0, 0, 18, FALSE, NULL),
        ('door-exit',        'DOOR', 0, 18, 0, 19.5, FALSE, 'CLOSED'),
        ('wall-west-2',      'WALL', 0, 19.5, 0, 40, FALSE, NULL),
        ('wall-east-1',      'WALL', w.map_w, 0, w.map_w, 3, FALSE, NULL),
        ('door-receiving-1', 'DOOR', w.map_w, 3, w.map_w, 6, TRUE, 'OPEN'),
        ('wall-east-2',      'WALL', w.map_w, 6, w.map_w, 7, FALSE, NULL),
        ('door-receiving-2', 'DOOR', w.map_w, 7, w.map_w, 10, TRUE, 'OPEN'),
        ('wall-east-3',      'WALL', w.map_w, 10, w.map_w, 27, FALSE, NULL),
        ('door-dispatch-1',  'DOOR', w.map_w, 27, w.map_w, 30, TRUE, 'OPEN'),
        ('wall-east-4',      'WALL', w.map_w, 30, w.map_w, 31, FALSE, NULL),
        ('door-dispatch-2',  'DOOR', w.map_w, 31, w.map_w, 34, TRUE, 'CLOSED'),
        ('wall-east-5',      'WALL', w.map_w, 34, w.map_w, 40, FALSE, NULL),
        ('wall-qc-north',    'WALL', w.area_x, 12, w.area_x + 8, 12, FALSE, NULL),
        ('wall-qc-east',     'WALL', w.area_x + 8, 12, w.area_x + 8, 18, FALSE, NULL),
        ('wall-qc-south',    'WALL', w.area_x, 18, w.area_x + 8, 18, FALSE, NULL),
        ('wall-qc-west-1',   'WALL', w.area_x, 12, w.area_x, 14, FALSE, NULL),
        ('door-qc',          'DOOR', w.area_x, 14, w.area_x, 16, TRUE, 'CLOSED'),
        ('wall-qc-west-2',   'WALL', w.area_x, 16, w.area_x, 18, FALSE, NULL),
        ('wall-office-north','WALL', w.map_w - 12, 35, w.map_w, 35, FALSE, NULL),
        ('wall-office-west-1','WALL', w.map_w - 12, 35, w.map_w - 12, 36.5, FALSE, NULL),
        ('door-office',      'DOOR', w.map_w - 12, 36.5, w.map_w - 12, 38, TRUE, 'OPEN'),
        ('wall-office-west-2','WALL', w.map_w - 12, 38, w.map_w - 12, 40, FALSE, NULL)
     ) AS b (key, type, sx, sy, ex, ey, passable, status);

-- ---------------------------------------------------------------------------------------------
-- 3. The five printable products: category, blank variant, approved image, print template (01 §4, 12 §4)
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE s_product (code TEXT PRIMARY KEY, idx INTEGER, short TEXT, name TEXT, slug TEXT, category TEXT,
                             category_name TEXT, variant_sku TEXT, variant_name TEXT, signature TEXT, unit_word TEXT,
                             units INTEGER, length_cm NUMERIC, width_cm NUMERIC, height_cm NUMERIC, carton_kg NUMERIC,
                             item_kg NUMERIC, item_l NUMERIC, item_w NUMERIC, item_h NUMERIC, base_price INTEGER,
                             rotatable BOOLEAN, model JSONB) ON COMMIT DROP;
INSERT INTO s_product VALUES
    ('LY-GIAY', 1, 'LYG12', 'Ly giấy 2 lớp in logo', 'ly-giay-2-lop-in-logo', 'LY_GIAY', 'Ly giấy in logo',
     'LY-GIAY-12OZ', 'Ly giấy 2 lớp in logo - 12 oz', 'DUNG_TICH=12OZ', 'ly', 1000, 60, 40, 50, 8.6, 0.008, 9, 9, 11,
     1150, TRUE,
     '{"kind": "parametric-cup", "topDiameterMm": 90, "bottomDiameterMm": 60, "heightMm": 110, "rimMm": 3, "seamMm": 8, "bandTopOffsetMm": 6, "bandBottomOffsetMm": 8, "material": "paper-white"}'),
    ('LY-NHUA', 2, 'LYP16', 'Ly nhựa PET in logo', 'ly-nhua-pet-in-logo', 'LY_NHUA', 'Ly nhựa in logo',
     'LY-NHUA-16OZ', 'Ly nhựa PET in logo - 16 oz', 'DUNG_TICH=16OZ', 'ly', 1000, 55, 45, 60, 11.0, 0.011, 9.5, 9.5, 13,
     950, TRUE,
     '{"kind": "parametric-cup", "topDiameterMm": 95, "bottomDiameterMm": 60, "heightMm": 130, "rimMm": 2, "seamMm": 0, "bandTopOffsetMm": 10, "bandBottomOffsetMm": 10, "material": "pet-clear"}'),
    ('TO-GIAY', 3, 'TOG750', 'Tô giấy kraft in logo', 'to-giay-kraft-in-logo', 'TO_HOP', 'Tô và hộp giấy in logo',
     'TO-GIAY-750ML', 'Tô giấy kraft in logo - 750 ml', 'DUNG_TICH=750ML', 'tô', 500, 50, 40, 45, 7.5, 0.015, 15, 15, 7,
     1950, TRUE,
     '{"kind": "parametric-cup", "topDiameterMm": 150, "bottomDiameterMm": 125, "heightMm": 70, "rimMm": 3, "seamMm": 8, "bandTopOffsetMm": 5, "bandBottomOffsetMm": 5, "material": "kraft"}'),
    ('HOP-BANH', 4, 'HOPM', 'Hộp giấy đựng bánh in logo', 'hop-giay-dung-banh-in-logo', 'TO_HOP',
     'Tô và hộp giấy in logo', 'HOP-BANH-M', 'Hộp giấy đựng bánh in logo - cỡ M', 'KICH_THUOC=M', 'hộp', 300, 60, 40,
     40, 9.0, 0.03, 20, 15, 8, 3400, FALSE,
     '{"kind": "parametric-bag", "widthMm": 200, "gussetMm": 150, "heightMm": 80, "topFoldMm": 0, "glueFlapMm": 20, "bottomFoldMm": 90, "handle": "none", "material": "kraft-white"}'),
    ('TUI-GIAY', 5, 'TUIL', 'Túi giấy xi măng in logo', 'tui-giay-xi-mang-in-logo', 'TUI_GIAY', 'Túi giấy in logo',
     'TUI-GIAY-L', 'Túi giấy xi măng in logo - cỡ L', 'KICH_THUOC=L', 'túi', 500, 50, 35, 30, 10.0, 0.02, 32, 22, 1,
     2800, TRUE,
     '{"kind": "parametric-bag", "widthMm": 320, "gussetMm": 110, "heightMm": 220, "topFoldMm": 40, "glueFlapMm": 20, "bottomFoldMm": 66, "handle": "twisted", "material": "kraft-brown"}');

INSERT INTO product.categories (id, parent_id, code, name, slug, path, depth, sort_order, is_active, version,
                                created_at, created_by)
SELECT DISTINCT ON (category) pg_temp.sid('category:' || category), NULL::uuid, category, category_name,
       lower(replace(category, '_', '-')), '/' || category, 0, idx, TRUE, 0, pg_temp.at(230, '09:00'), 'seed'
  FROM s_product ORDER BY category, idx;

INSERT INTO product.products (id, code, name, slug, short_description, tax_class, kind, status, submitted_by,
                              submitted_at, approved_by, approved_at, published_at, ever_published, version, created_at,
                              created_by)
SELECT pg_temp.sid('product:' || code), code, name, slug, name || ', in theo thiết kế của khách hàng.', 'STANDARD',
       'CUSTOMIZABLE', 'PUBLISHED', pg_temp.staff('sales1'), pg_temp.at(228, '10:00'), pg_temp.staff('truongkd'),
       pg_temp.at(227, '10:00'), pg_temp.at(227, '10:00'), TRUE, 0, pg_temp.at(228, '09:00'),
       pg_temp.staff('sales1')::text
  FROM s_product;

INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, created_by)
SELECT pg_temp.sid('variant:' || variant_sku), pg_temp.sid('product:' || code), variant_sku, variant_name, 'ACTIVE',
       TRUE, signature, 0, 0, pg_temp.at(228, '09:00'), pg_temp.staff('sales1')::text
  FROM s_product;

INSERT INTO product.product_categories (id, product_id, category_id, is_primary, version, created_at, created_by)
SELECT pg_temp.sid('product-category:' || code), pg_temp.sid('product:' || code), pg_temp.sid('category:' || category),
       TRUE, 0, pg_temp.at(228, '09:00'), pg_temp.staff('sales1')::text
  FROM s_product;

-- The storefront shows a product only with an image approved by someone other than its uploader; the image is a
-- placeholder URL, not a stored file.
INSERT INTO product.media (id, variant_id, kind, url, renditions, alt_text, sort_order, is_primary, is_published,
                           published_at, published_by, version, created_at, created_by)
SELECT pg_temp.sid('media:' || variant_sku), pg_temp.sid('variant:' || variant_sku), 'IMAGE',
       'https://placehold.co/800x600?text=' || variant_sku, '[]', variant_name, 0, TRUE, TRUE, pg_temp.at(227, '10:00'),
       pg_temp.staff('truongkd'), 0, pg_temp.at(228, '09:00'), pg_temp.staff('sales1')::text
  FROM s_product;

INSERT INTO product.customization_templates (id, variant_id, code, name, min_dpi, color_mode, allowed_file_formats,
                                             max_file_size_mb, max_colors, is_active, model_params_json, version,
                                             created_at, created_by)
SELECT pg_temp.sid('template:' || variant_sku), pg_temp.sid('variant:' || variant_sku), 'STD',
       'Mẫu in ' || lower(name), 300, 'CMYK', ARRAY['image/png', 'image/jpeg'], 20, 4, TRUE, model, 0,
       pg_temp.at(228, '09:00'), pg_temp.staff('sales1')::text
  FROM s_product;

INSERT INTO product.print_areas (id, template_id, code, name, x_mm, y_mm, width_mm, height_mm, safe_margin_mm,
                                 bleed_mm, shape_json, uv_mapping_json, version, created_at, created_by)
SELECT pg_temp.sid('print-area:' || variant_sku), pg_temp.sid('template:' || variant_sku),
       CASE WHEN model ->> 'kind' = 'parametric-bag' THEN 'FRONT' ELSE 'BODY' END,
       CASE WHEN model ->> 'kind' = 'parametric-bag' THEN 'Mặt trước' ELSE 'Thân' END, 10, 10, 200, 80, 3, 2,
       CASE WHEN model ->> 'kind' = 'parametric-bag' THEN '{"type": "rect"}'::jsonb
            ELSE '{"type": "annular-sector"}'::jsonb END,
       '{"meshPart": "body"}'::jsonb, 0, pg_temp.at(228, '09:00'), pg_temp.staff('sales1')::text
  FROM s_product;

-- ---------------------------------------------------------------------------------------------
-- 4. Producers (17 §4.3) and truck carriers (09)
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.producer (id, code, name, contact_name, contact_phone, email, channel, standard_lead_days, status,
                               note, version, created_at, created_by)
SELECT pg_temp.sid('producer:' || code), code, name, contact, phone, email, 'EMAIL', lead, 'ACTIVE', note, 0,
       pg_temp.at(229, '09:00'), pg_temp.staff('dieuphoi')::text
  FROM (VALUES ('INDN', 'Công ty TNHH In Bao Bì Đồng Nai', 'Hồ Văn In', '0251390011', 'sanxuat@inbaobidongnai.example', 10,
                'Ly giấy, tô giấy; in flexo 4 màu'),
               ('INLX', 'Xưởng In Ly Giấy Long Xuyên', 'Đinh Thị Ly', '0296390022', 'donhang@inlylongxuyen.example', 12,
                NULL),
               ('NHPT', 'Công ty CP Nhựa Phú Thọ', 'Kiều Văn Nhựa', '0210390033', 'kinhdoanh@nhuaphutho.example', 14,
                'Ly nhựa PET, in lụa'),
               ('HOPV', 'Công ty TNHH Hộp Giấy Việt', 'Lương Thị Hộp', '0274390044', 'sx@hopgiayviet.example', 9,
                'Hộp bánh, túi giấy; bế và dán tự động'),
               ('TUIX', 'Xưởng Túi Giấy Xanh Hưng Yên', 'Tạ Văn Túi', '0221390055', 'order@tuigiayxanh.example', 11, NULL),
               ('INHP', 'Công ty TNHH In Ấn Hải Phòng', 'Vũ Thị Hải', '0225390066', 'baogia@inanhaiphong.example', 13,
                'Phục vụ đơn miền Bắc'))
       AS p (code, name, contact, phone, email, lead, note);

INSERT INTO fulfillment.carrier (id, code, name, contact_name, contact_phone, status, version, created_at, created_by,
                                 connection_type, webhook_enabled, failure_reason_map)
SELECT pg_temp.sid('carrier:' || code), code, name, contact, phone, 'ACTIVE', 0, pg_temp.at(229, '09:00'),
       pg_temp.staff('quanly.bd')::text, 'MANUAL', FALSE, '{}'
  FROM (VALUES ('XT-TANPHAT', 'Xe tải Tân Phát', 'Điều phối Tân Phát', '0909000111'),
               ('XT-HOANGLONG', 'Vận tải Hoàng Long', 'Điều phối Hoàng Long', '0909000222'),
               ('XT-BACVIET', 'Xe tải Bắc Việt', 'Điều phối Bắc Việt', '0243900333')) AS c (code, name, contact, phone);

-- ---------------------------------------------------------------------------------------------
-- 5. Customers: portal account, legal profile, addresses, credit (18; 15 BR-12: paying after delivery needs credit)
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE s_customer (code TEXT PRIMARY KEY, idx SERIAL, name TEXT, contact TEXT, username TEXT,
                              user_email TEXT, line1 TEXT, ward TEXT, ward_code TEXT, province TEXT,
                              province_code TEXT, region TEXT) ON COMMIT DROP;
INSERT INTO s_customer (code, name, contact, username, user_email, line1, ward, ward_code, province, province_code,
                        region) VALUES
    ('CPM', 'Công ty TNHH Cà Phê Mộc', 'Lê Văn Mộc', 'kh.caphemoc', 'caphemoc@khachhang.example', '45 Lê Lợi',
     'Phường Sài Gòn', '26740', 'TP. Hồ Chí Minh', '79', 'S'),
    ('TSM', 'Công ty CP Trà Sữa Mây', 'Trần Thị Mây', 'kh.trasuamay', 'trasuamay@khachhang.example', '18 Võ Văn Tần',
     'Phường Xuân Hòa', '27142', 'TP. Hồ Chí Minh', '79', 'S'),
    ('BMV', 'Công ty TNHH Bánh Mì Việt', 'Nguyễn Văn Việt', 'kh.banhmiviet', 'banhmiviet@khachhang.example',
     '102 Nguyễn Trãi', 'Phường Bến Thành', '26743', 'TP. Hồ Chí Minh', '79', 'S'),
    ('NEX', 'Công ty TNHH Nước Ép Xanh', 'Phạm Thị Xanh', 'kh.nuocepxanh', 'nuocepxanh@khachhang.example',
     '7 Phan Xích Long', 'Phường Cầu Kiệu', '27043', 'TP. Hồ Chí Minh', '79', 'S'),
    ('KTU', 'Công ty TNHH Kem Tươi', 'Đoàn Thị Kem', 'kh.kemtuoi', 'kemtuoi@khachhang.example', '210 Hai Bà Trưng',
     'Phường Tân Định', '26734', 'TP. Hồ Chí Minh', '79', 'S'),
    ('CTS', 'Công ty TNHH Cơm Tấm Sài Gòn', 'Huỳnh Văn Tấm', 'kh.comtamsg', 'comtamsg@khachhang.example',
     '88 Nguyễn Đình Chiểu', 'Phường Bàn Cờ', '27154', 'TP. Hồ Chí Minh', '79', 'S'),
    ('LTH', 'Công ty CP Lẩu Thái Siam', 'Vương Thị Lan', 'kh.lauthai', 'lauthai@khachhang.example', '36 Trần Duy Hưng',
     'Phường Yên Hòa', '00187', 'TP. Hà Nội', '01', 'N'),
    ('BBH', 'Công ty TNHH Bún Bò Huế O Hà', 'Tôn Nữ Hà', 'kh.bunbohue', 'bunbohue@khachhang.example', '12 Lê Lợi',
     'Phường Thuận Hóa', '19774', 'TP. Huế', '46', 'C'),
    ('TCP', 'Công ty TNHH Trà Chanh Phố', 'Khúc Văn Phố', 'khach1@stockflow.test', 'khach1@stockflow.test',
     '5 Nhà Chung', 'Phường Hoàn Kiếm', '00070', 'TP. Hà Nội', '01', 'N'),
    ('BNM', 'Công ty TNHH Bánh Ngọt Mộc Lan', 'Châu Mộc Lan', 'khach2@stockflow.test', 'khach2@stockflow.test',
     '61 Phạm Ngọc Thạch', 'Phường Xuân Hòa', '27142', 'TP. Hồ Chí Minh', '79', 'S'),
    ('CAD', 'Công ty TNHH Cà Phê Đất Đỏ', 'Y Đất Niê', NULL, NULL, '19 Phan Đăng Lưu', 'Phường Gia Định', '26944',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('TRX', 'Công ty CP Trà Xanh Lá', 'Lâm Thị Lá', NULL, NULL, '240 Cách Mạng Tháng Tám', 'Phường Nhiêu Lộc', '27139',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('SDT', 'Công ty TNHH Sinh Tố Đồng Tháp', 'Lê Văn Sen', NULL, NULL, '77 Lý Thường Kiệt', 'Phường Phú Thọ', '27286',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('GAR', 'Công ty TNHH Gà Rán Phố Xưa', 'Quách Văn Gà', NULL, NULL, '15 Quang Trung', 'Phường Gò Vấp', '26890',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('PIZ', 'Công ty CP Pizza Nắng', 'Phan Minh Nắng', NULL, NULL, '9 Thảo Điền', 'Phường An Khánh', '27097',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('MIQ', 'Công ty TNHH Mì Quảng Hội An Xưa', 'Trương Thị Quảng', NULL, NULL, '120 Bạch Đằng', 'Phường Hải Châu',
     '20194', 'TP. Đà Nẵng', '48', 'C'),
    ('SUA', 'Công ty TNHH Sữa Chua Đà Lạt Xanh', 'Hà Văn Lạt', NULL, NULL, '33 Nguyễn Thị Minh Khai',
     'Phường Bến Thành', '26743', 'TP. Hồ Chí Minh', '79', 'S'),
    ('CHE', 'Công ty TNHH Chè Ba Miền', 'Diệp Thị Chè', NULL, NULL, '4 Tôn Thất Thiệp', 'Phường Sài Gòn', '26740',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('TAO', 'Công ty TNHH Tào Phớ Mát', 'Lạc Văn Phớ', NULL, NULL, '58 Lê Văn Sỹ', 'Phường Nhiêu Lộc', '27139',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('HUT', 'Công ty TNHH Hủ Tiếu Nam Vang 99', 'Tăng Văn Vang', NULL, NULL, '99 Nguyễn Trãi', 'Phường Chợ Quán',
     '27301', 'TP. Hồ Chí Minh', '79', 'S'),
    ('BKE', 'Công ty TNHH Bakery Hoa Sữa', 'Mạc Thị Hoa', NULL, NULL, '27 Phan Văn Trị', 'Phường An Nhơn', '26878',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('NUD', 'Công ty CP Nước Dừa Bến Tre Xanh', 'Võ Văn Dừa', NULL, NULL, '140 Đồng Khởi', 'Phường An Hội', '28756',
     'Tỉnh Vĩnh Long', '86', 'S'),
    ('KOM', 'Công ty TNHH Kombucha Lên Men', 'Kha Thị Men', NULL, NULL, '6 Nguyễn Văn Hưởng', 'Phường An Khánh',
     '27097', 'TP. Hồ Chí Minh', '79', 'S'),
    ('CPV', 'Công ty CP Chuỗi Cà Phê Vỉa Hè', 'Ông Văn Hè', NULL, NULL, '301 Điện Biên Phủ', 'Phường Bàn Cờ', '27154',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('XOI', 'Công ty TNHH Xôi Gà Sáng Sớm', 'Âu Thị Xôi', NULL, NULL, '44 Bà Hom', 'Phường Bình Tiên', '27316',
     'TP. Hồ Chí Minh', '79', 'S'),
    ('SNK', 'Công ty TNHH Snack Giòn Tan', 'Lục Văn Giòn', NULL, NULL, 'Lô B2 KCN Tân Bình', 'Phường Tây Thạnh',
     '27007', 'TP. Hồ Chí Minh', '79', 'S'),
    ('CAN', 'Công ty TNHH Căn Tin Văn Phòng Xanh', 'Thạch Thị Tin', NULL, NULL, '2 Hải Triều', 'Phường Sài Gòn',
     '26740', 'TP. Hồ Chí Minh', '79', 'S'),
    ('DNC', 'Công ty TNHH Cà Phê Biển Đà Nẵng', 'Nguyễn Hải Biển', NULL, NULL, '68 Võ Nguyên Giáp', 'Phường Sơn Trà',
     '20227', 'TP. Đà Nẵng', '48', 'C'),
    ('HNH', 'Công ty TNHH Bánh Bèo Huế Thương', 'Hồ Thị Thương', NULL, NULL, '21 Chi Lăng', 'Phường Phú Xuân', '19750',
     'TP. Huế', '46', 'C'),
    ('QNM', 'Công ty TNHH Trà Sữa Quảng Nam', 'Phạm Văn Quảng', NULL, NULL, '150 Phan Châu Trinh', 'Phường Tam Kỳ',
     '20341', 'TP. Đà Nẵng', '48', 'C'),
    ('CTO', 'Công ty TNHH Trái Cây Tô Châu', 'Lê Thị Châu', NULL, NULL, '30 Hai Bà Trưng', 'Phường Ninh Kiều', '31117',
     'TP. Cần Thơ', '92', 'S'),
    ('HNC', 'Công ty CP Cà Phê Phố Cổ Hà Nội', 'Đào Văn Cổ', NULL, NULL, '11 Hàng Gai', 'Phường Hoàn Kiếm', '00070',
     'TP. Hà Nội', '01', 'N'),
    ('BCH', 'Công ty TNHH Bún Chả Hồ Gươm', 'Lò Văn Chả', NULL, NULL, '74 Hàng Quạt', 'Phường Hoàn Kiếm', '00070',
     'TP. Hà Nội', '01', 'N'),
    ('PHO', 'Công ty TNHH Phở Bò Ngõ Nhỏ', 'Kim Văn Phở', NULL, NULL, '13 Lò Đúc', 'Phường Hai Bà Trưng', '00256',
     'TP. Hà Nội', '01', 'N'),
    ('TRC', 'Công ty TNHH Trà Chanh Nhà Thờ', 'Ninh Thị Chanh', NULL, NULL, '2 Nhà Thờ', 'Phường Hoàn Kiếm', '00070',
     'TP. Hà Nội', '01', 'N'),
    ('KEM', 'Công ty CP Kem Hồ Tây', 'Hoàng Văn Tây', NULL, NULL, '9 Thanh Niên', 'Phường Tây Hồ', '00103',
     'TP. Hà Nội', '01', 'N'),
    ('BMH', 'Công ty TNHH Bánh Mì Que Hải Phòng', 'Bạch Văn Que', NULL, NULL, '57 Lê Lợi', 'Phường Ngô Quyền', '11311',
     'TP. Hải Phòng', '31', 'N'),
    ('SUH', 'Công ty TNHH Sữa Hạt Mộc Châu Xanh', 'Sầm Thị Hạt', NULL, NULL, '25 Nguyễn Chí Thanh', 'Phường Láng',
     '00229', 'TP. Hà Nội', '01', 'N'),
    ('CTR', 'Công ty CP Cà Phê Trứng Hàng Bạc', 'Đinh Văn Trứng', NULL, NULL, '39 Hàng Bạc', 'Phường Hoàn Kiếm',
     '00070', 'TP. Hà Nội', '01', 'N'),
    ('NBH', 'Công ty TNHH Nước Ép Bắc Hà', 'Giàng Thị Mỷ', NULL, NULL, '8 Cầu Giấy', 'Phường Cầu Giấy', '00166',
     'TP. Hà Nội', '01', 'N');

ALTER TABLE s_customer
    ADD COLUMN id UUID, ADD COLUMN tax_code TEXT, ADD COLUMN phone TEXT, ADD COLUMN email TEXT, ADD COLUMN sales TEXT,
    ADD COLUMN credit_limit NUMERIC, ADD COLUMN term_days INTEGER, ADD COLUMN wh TEXT, ADD COLUMN since INTEGER;

UPDATE s_customer
   SET id = pg_temp.sid('customer:' || code), tax_code = '03' || lpad((18800000 + idx * 37)::text, 8, '0'),
       phone = '09' || lpad((12000000 + idx * 7919)::text, 8, '0'),
       email = 'muahang.' || lower(code) || '@khachhang.example',
       username = coalesce(username, 'kh.' || lower(code)), user_email = coalesce(user_email, lower(code) || '@khachhang.example'),
       sales = (ARRAY['sales1', 'sales2', 'sf.sales'])[1 + idx % 3],
       -- Seven customers in ten buy on credit (15 BR-11); the rest pay before delivery.
       credit_limit = CASE WHEN pg_temp.rnd('credit:' || code) < 0.7
                           THEN (8 + pg_temp.pick('limit:' || code, 0, 22)) * 100000000::numeric END,
       wh = CASE WHEN region = 'N' THEN 'HNI' ELSE 'BDG' END;
UPDATE s_customer
   SET term_days = CASE WHEN credit_limit IS NOT NULL THEN (ARRAY[15, 30, 30, 45])[pg_temp.pick('term:' || code, 1, 4)] END;

-- ---------------------------------------------------------------------------------------------
-- 6. The orders' plan: stage, customer and timeline of each of the 300 (filled in before the customers go in, because
--    a customer's first day is its first quote's)
-- ---------------------------------------------------------------------------------------------
-- Stage rank: what each step below needs. 0 quote only, 1 quote sent, 2 converted, 3 contract sent, 4 signed,
-- 5 deposit paid / warehouse / production order drawn up, 6 sent, 7 accepted, 8 printing, 9 part received,
-- 10 all received and held, 11 released, 12 picking, 13 packed, 14 shipped, 15 delivered.
CREATE TEMP TABLE h_order ON COMMIT DROP AS
WITH plan (stage, st, n) AS (
    VALUES ('Q_DRAFT', 0, 5), ('Q_SENT', 1, 8), ('Q_CHANGES', 1, 4), ('Q_EXPIRED', 1, 8), ('Q_CANCELLED', 1, 4),
           ('O_DRAFT', 2, 5), ('O_CANCELLED', 2, 8), ('PENDING_CONTRACT', 3, 7), ('PENDING_PAYMENT', 4, 9),
           ('CONFIRMED', 5, 6), ('PO_SENT', 6, 5), ('PO_ACCEPTED', 7, 5), ('IN_PRODUCTION', 8, 10),
           ('PARTIAL', 9, 5), ('HELD', 10, 6), ('READY', 11, 5), ('PICKING', 12, 4), ('PACKED', 13, 4),
           ('SHIPPED', 14, 6), ('DELIVERED', 15, 186)),
expanded AS (
    SELECT p.stage, p.st, i FROM plan p CROSS JOIN LATERAL generate_series(1, p.n) i
)
SELECT row_number() OVER (ORDER BY st, stage, i)::int AS seq, stage, st, i
  FROM expanded;

ALTER TABLE h_order ADD COLUMN key TEXT, ADD COLUMN cust TEXT, ADD COLUMN s INTEGER, ADD COLUMN d INTEGER,
    ADD COLUMN r INTEGER;

-- Customers are not equally busy: the first ones on the list buy more often.
UPDATE h_order o
   SET key = 'O' || lpad(seq::text, 3, '0'),
       cust = (SELECT c.code FROM s_customer c
                WHERE c.idx = 1 + floor(power(pg_temp.rnd('cust:' || o.seq), 1.35) * 40)::int);

-- s: the day the contract is (or would be) signed; every other step is counted from it, as in demo_history:
-- quote drawn up s+7, sent s+7, accepted s+6, converted s+5, contract drawn up s+4 and sent s+3, signed s, deposit
-- s-1, production order drawn up s-1, approved and sent s-2, accepted s-3, printing s-4, goods received r, released
-- d+3, picked d+2, packed d+2, load plan d+1, handed over d at 08:00, delivered d at 15:00. A negative day is ahead.
UPDATE h_order o
   SET d = CASE stage
               -- Denser towards today (a business that grows), and a quarter delivered in the last five weeks: what was
               -- signed a month or two ago is delivered by now, so the dashboard's month-on-month change stays modest
               -- beside the orders still in hand, which were all signed lately.
               WHEN 'DELIVERED' THEN CASE WHEN pg_temp.rnd('d-recent:' || seq) < 0.25
                                          THEN 6 + pg_temp.pick('d:' || seq, 0, 36)
                                          ELSE 3 + floor(power(pg_temp.rnd('d:' || seq), 1.2) * 172)::int END
               WHEN 'SHIPPED' THEN 1
               WHEN 'PACKED' THEN pg_temp.pick('d:' || seq, -1, 0)
               WHEN 'PICKING' THEN -1
               WHEN 'READY' THEN pg_temp.pick('d:' || seq, -2, -1)
           END;
UPDATE h_order o
   SET r = CASE WHEN d IS NOT NULL THEN d + 6
                WHEN stage = 'HELD' THEN pg_temp.pick('r:' || seq, 1, 7)
                WHEN stage = 'PARTIAL' THEN pg_temp.pick('r:' || seq, 1, 5) END;
UPDATE h_order o
   SET s = CASE stage
               WHEN 'Q_DRAFT' THEN pg_temp.pick('s:' || seq, -6, -5)
               WHEN 'Q_SENT' THEN pg_temp.pick('s:' || seq, -6, -1)
               WHEN 'Q_CHANGES' THEN pg_temp.pick('s:' || seq, -5, -2)
               WHEN 'Q_EXPIRED' THEN pg_temp.pick('s:' || seq, 10, 90)
               WHEN 'Q_CANCELLED' THEN pg_temp.pick('s:' || seq, 5, 90)
               WHEN 'O_DRAFT' THEN pg_temp.pick('s:' || seq, -4, -1)
               WHEN 'O_CANCELLED' THEN pg_temp.pick('s:' || seq, 5, 120)
               WHEN 'PENDING_CONTRACT' THEN pg_temp.pick('s:' || seq, -2, 2)
               WHEN 'PENDING_PAYMENT' THEN pg_temp.pick('s:' || seq, 1, 12)
               WHEN 'CONFIRMED' THEN pg_temp.pick('s:' || seq, 2, 3)
               WHEN 'PO_SENT' THEN pg_temp.pick('s:' || seq, 3, 4)
               WHEN 'PO_ACCEPTED' THEN pg_temp.pick('s:' || seq, 4, 5)
               WHEN 'IN_PRODUCTION' THEN pg_temp.pick('s:' || seq, 5, 13)
               ELSE r + 12 + pg_temp.pick('s:' || seq, 0, 10)
           END;

-- A customer's first day: ten days before its first quote (at least 30 days ago).
UPDATE s_customer c
   SET since = greatest(30, coalesce((SELECT max(o.s) + 17 FROM h_order o WHERE o.cust = c.code), 30));

INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
SELECT pg_temp.sid('user:' || username), username, user_email, pg_temp.pw(), contact, 'ACTIVE', 0,
       pg_temp.at(since, '09:00'), pg_temp.staff(sales)::text
  FROM s_customer;

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT pg_temp.sid('user-role:' || c.username), pg_temp.sid('user:' || c.username), r.id, 0, pg_temp.at(c.since, '09:00'),
       pg_temp.staff(c.sales)::text
  FROM s_customer c JOIN identity.app_role r ON r.code = 'CUSTOMER';

INSERT INTO customer.customer (id, full_name, email, phone, status, version, created_at, created_by, user_id, tax_code)
SELECT id, name, email, phone, 'ACTIVE', 0, pg_temp.at(since, '09:00'), pg_temp.staff(sales)::text,
       pg_temp.sid('user:' || username), tax_code
  FROM s_customer;

INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name,
                                             representative_title, invoice_email, version, created_at, created_by)
SELECT pg_temp.sid('legal:' || code), id, line1 || ', ' || ward || ', ' || province, contact, 'Giám đốc', email, 0,
       pg_temp.at(since, '09:30'), pg_temp.staff(sales)::text
  FROM s_customer;

INSERT INTO customer.address (id, customer_id, type, recipient_name, phone, line1, ward, province, country, is_default,
                              ward_code, province_code, version, created_at, created_by)
SELECT pg_temp.sid('address:' || c.code || ':' || t.type), c.id, t.type,
       CASE t.type WHEN 'SHIPPING' THEN c.contact ELSE c.name END, c.phone, c.line1, c.ward, c.province, 'VN', TRUE,
       c.ward_code, c.province_code, 0, pg_temp.at(c.since, '09:30'), pg_temp.staff(c.sales)::text
  FROM s_customer c CROSS JOIN (VALUES ('SHIPPING'), ('BILLING')) AS t (type);

INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, currency, approved_by, approved_at,
                                     note, version, created_at, created_by)
SELECT pg_temp.sid('credit:' || code), id, credit_limit, term_days, 'VND', pg_temp.staff('ketoan'),
       pg_temp.at(since - 1, '10:00'),
       'Hạn mức duyệt theo hồ sơ năng lực tài chính; thanh toán đợt sau giao hàng trong ' || term_days || ' ngày', 0,
       pg_temp.at(since - 1, '10:00'), pg_temp.staff('ketoan')::text
  FROM s_customer WHERE credit_limit IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 7. Each customer's own SKUs: 2 or 3 of the five products, each on an approved design (01 BR-03), with its carton
--    (the unit it ships in and the only one it is sold in) and the customer's price by cartons (13 BR-05)
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE s_sku ON COMMIT DROP AS
SELECT c.code AS cust, p.code AS product, p.short || '-' || c.code AS sku, p.*, c.id AS customer_id, c.username,
       c.since, c.sales,
       -- Its price a unit: the product's, give or take, in tens of dong.
       p.base_price + 10 * pg_temp.pick('price:' || c.code || ':' || p.code, -10, 25) AS price
  FROM s_customer c
  JOIN s_product p
    ON ((p.idx - pg_temp.pick('first-product:' || c.code, 1, 5) + 5) % 5)
       < 2 + (pg_temp.rnd('third-product:' || c.code) < 0.5)::int;

ALTER TABLE s_sku DROP COLUMN code;

INSERT INTO design.design_draft (id, customer_id, product_id, name, spec, status, owner_user_id, reviewed_by,
                                 review_notes, current_artifacts, variant_id, sku, template_id, template_code,
                                 review_requested_at, reviewed_at, version, created_at, created_by)
SELECT pg_temp.sid('design:' || sku), customer_id, pg_temp.sid('product:' || product),
       name || ' - logo ' || cust, 'Logo giữa thân, 2 màu Pantone', 'APPROVED', pg_temp.sid('user:' || username),
       pg_temp.staff(sales), 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', '{}'::jsonb,
       pg_temp.sid('variant:' || variant_sku), variant_sku, pg_temp.sid('template:' || variant_sku), 'STD',
       pg_temp.at(since - 1, '10:00'), pg_temp.at(since - 2, '14:00'), 0, pg_temp.at(since - 1, '09:00'),
       pg_temp.sid('user:' || username)::text
  FROM s_sku;

INSERT INTO design.design_decision (id, draft_id, actor_id, decision, notes, draft_version, version, created_at,
                                    created_by)
SELECT pg_temp.sid('design-decision:' || k.sku || ':' || d.n), pg_temp.sid('design:' || k.sku),
       CASE WHEN d.n = 2 THEN pg_temp.staff(k.sales) ELSE pg_temp.sid('user:' || k.username) END, d.decision,
       d.notes, d.n, 0, pg_temp.at(k.since - d.day, d.hhmi), 'seed'
  FROM s_sku k
 CROSS JOIN (VALUES (0, 'CREATED', 'Tạo thiết kế', 1, '09:00'),
                    (1, 'SUBMITTED_FOR_REVIEW', 'Gửi duyệt thiết kế', 1, '10:00'),
                    (2, 'APPROVED', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', 2, '14:00'))
            AS d (n, decision, notes, day, hhmi);

INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, size_bytes, stored_at,
                                    checksum, artifact_role, version, created_at, created_by)
SELECT pg_temp.sid('artifact:' || sku), pg_temp.sid('design:' || sku),
       'design-renders/showcase/' || lower(sku) || '.pdf', 'print_ready.pdf', 'application/pdf', 51200,
       pg_temp.at(since - 1, '09:45'), encode(sha256(convert_to('showcase:artifact:' || sku, 'UTF8')), 'hex'),
       'PRINT_READY', 0, pg_temp.at(since - 1, '09:45'), pg_temp.sid('user:' || username)::text
  FROM s_sku;

UPDATE design.design_draft d
   SET current_artifact_id = pg_temp.sid('artifact:' || k.sku),
       current_artifacts = jsonb_build_object('PRINT_READY', pg_temp.sid('artifact:' || k.sku))
  FROM s_sku k
 WHERE d.id = pg_temp.sid('design:' || k.sku);

-- The snapshot's checksum is the manifest's (DesignSnapshotJpaEntity.manifestChecksum).
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, artifact_id, confirmed_by,
                                    reviewed_by, spec, review_notes, artifact_manifest, version, created_at, created_by)
SELECT pg_temp.sid('snapshot:' || k.sku), a.draft_id,
       encode(sha256(convert_to('PRINT_READY:' || a.id || ':' || a.checksum || E'\n', 'UTF8')), 'hex'), a.storage_key,
       pg_temp.at(k.since - 2, '14:00'), a.id, pg_temp.sid('user:' || k.username), pg_temp.staff(k.sales),
       'Logo giữa thân, 2 màu Pantone', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm',
       jsonb_build_array(jsonb_build_object('artifactId', a.id, 'role', a.artifact_role, 'storageKey', a.storage_key,
                                            'originalName', a.original_name, 'contentType', a.content_type,
                                            'sizeBytes', a.size_bytes, 'checksum', a.checksum)),
       0, pg_temp.at(k.since - 2, '14:00'), pg_temp.staff(k.sales)::text
  FROM s_sku k JOIN design.design_artifact a ON a.id = pg_temp.sid('artifact:' || k.sku);

-- tg_variant_inventory_item makes each SKU's inventory item, as the catalogue makes it.
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                              owner_customer_id, design_snapshot_id, source_variant_id, version, created_at, created_by)
SELECT pg_temp.sid('variant:' || sku), pg_temp.sid('product:' || product), sku,
       variant_name || ' - ' || (SELECT c.name FROM s_customer c WHERE c.code = cust), 'ACTIVE', FALSE,
       'OWNED-SKU:' || sku, 10 + row_number() OVER (PARTITION BY product ORDER BY cust), customer_id,
       pg_temp.sid('snapshot:' || sku), pg_temp.sid('variant:' || variant_sku), 0, pg_temp.at(since - 2, '14:05'),
       'system'
  FROM s_sku;

UPDATE inventory.inventory_items i
   SET weight_kg = k.item_kg, length_cm = k.item_l, width_cm = k.item_w, height_cm = k.item_h, rotatable = k.rotatable
  FROM s_sku k WHERE i.sku = k.sku;

INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, weight_kg, length_cm,
                                       width_cm, height_cm, active, default_shipping, version, created_at, created_by)
SELECT pg_temp.sid('unit:' || k.sku), i.id, 'THUNG', k.units, FALSE, k.carton_kg, k.length_cm, k.width_cm, k.height_cm,
       TRUE, TRUE, 0, pg_temp.at(k.since - 2, '14:10'), pg_temp.staff(k.sales)::text
  FROM s_sku k JOIN inventory.inventory_items i ON i.sku = k.sku;

INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id, version, created_at, created_by)
SELECT pg_temp.sid('sales-unit:' || sku), pg_temp.sid('variant:' || sku), pg_temp.sid('unit:' || sku), 0,
       pg_temp.at(since - 2, '14:10'), pg_temp.staff(sales)::text
  FROM s_sku;

-- From 10 cartons the price plus 100, from 30 the price, from 60 the price less 100 (VND a unit).
INSERT INTO catalog.customer_price (id, customer_id, sku, currency, note, version, created_at, created_by)
SELECT pg_temp.sid('price:' || sku), customer_id, sku, 'VND', 'Bảng giá theo số thùng (giá một ' || unit_word || ')', 0,
       pg_temp.at(since - 2, '15:00'), pg_temp.staff(sales)::text
  FROM s_sku;

INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version, created_at,
                                         created_by)
SELECT pg_temp.sid('price-tier:' || k.sku || ':' || t.min_quantity), pg_temp.sid('price:' || k.sku), t.min_quantity,
       k.price + t.delta, 0, pg_temp.at(k.since - 2, '15:00'), pg_temp.staff(k.sales)::text
  FROM s_sku k CROSS JOIN (VALUES (10, 100), (30, 0), (60, -100)) AS t (min_quantity, delta);

-- ---------------------------------------------------------------------------------------------
-- 8. The orders in full: lines (1 to 3 of the customer's SKUs, 10 to 28 cartons each - one truck), prices, fees,
--    installments
-- ---------------------------------------------------------------------------------------------
ALTER TABLE h_order
    ADD COLUMN id UUID, ADD COLUMN customer_id UUID, ADD COLUMN quote_id UUID, ADD COLUMN contract_id UUID,
    ADD COLUMN batch_id UUID, ADD COLUMN po_id UUID, ADD COLUMN credit BOOLEAN, ADD COLUMN term_days INTEGER,
    ADD COLUMN credit_limit NUMERIC, ADD COLUMN sales TEXT, ADD COLUMN wh TEXT, ADD COLUMN mgr TEXT, ADD COLUMN kho TEXT,
    ADD COLUMN producer TEXT, ADD COLUMN carrier TEXT, ADD COLUMN cust_user TEXT,
    ADD COLUMN goods NUMERIC, ADD COLUMN fee NUMERIC, ADD COLUMN goods_tax NUMERIC, ADD COLUMN fee_tax NUMERIC,
    ADD COLUMN total NUMERIC, ADD COLUMN deposit NUMERIC, ADD COLUMN rest NUMERIC, ADD COLUMN cartons INTEGER,
    ADD COLUMN order_number TEXT, ADD COLUMN quote_number TEXT, ADD COLUMN contract_number TEXT, ADD COLUMN po_code TEXT,
    ADD COLUMN receipt_number TEXT, ADD COLUMN window_start DATE, ADD COLUMN window_end DATE,
    ADD COLUMN paid_after INTEGER, ADD COLUMN paid_part NUMERIC;

UPDATE h_order o
   SET id = pg_temp.sid('order:' || o.key), customer_id = c.id, quote_id = pg_temp.sid('quote:' || o.key),
       contract_id = pg_temp.sid('contract:' || o.key), batch_id = pg_temp.sid('batch:' || o.key),
       po_id = CASE WHEN o.st >= 5 THEN pg_temp.sid('po:' || o.key) END, credit = c.credit_limit IS NOT NULL,
       term_days = c.term_days, credit_limit = c.credit_limit, sales = c.sales, wh = c.wh, cust_user = c.username,
       mgr = w.manager, kho = CASE WHEN o.seq % 2 = 0 THEN w.staff1 ELSE w.staff2 END,
       producer = CASE WHEN c.wh = 'HNI' THEN (ARRAY['INHP', 'TUIX', 'NHPT'])[pg_temp.pick('producer:' || o.seq, 1, 3)]
                       ELSE (ARRAY['INDN', 'INLX', 'HOPV', 'NHPT'])[pg_temp.pick('producer:' || o.seq, 1, 4)] END,
       carrier = CASE WHEN c.wh = 'HNI' THEN 'XT-BACVIET'
                      ELSE (ARRAY['XT-TANPHAT', 'XT-HOANGLONG'])[pg_temp.pick('carrier:' || o.seq, 1, 2)] END,
       order_number = 'SO-' || pg_temp.ymd(o.s + 5) || '-' || (700000 + o.seq),
       quote_number = 'QT-' || pg_temp.ymd(o.s + 7) || '-' || (7000 + o.seq),
       po_code = CASE WHEN o.st >= 5 THEN 'PRD-' || pg_temp.ymd(o.s - 1) || '-' || (7000 + o.seq) END,
       receipt_number = CASE WHEN o.r IS NOT NULL THEN 'GR-' || pg_temp.ymd(o.r) || '-' || (7000 + o.seq) END,
       -- Delivered and on-the-way orders: the window opens the day before the truck leaves; the others' is ahead.
       window_start = CASE WHEN o.d IS NOT NULL THEN pg_temp.day(o.d + 1)
                           ELSE pg_temp.day(least(o.s, 0) - 14 - pg_temp.pick('window:' || o.seq, 0, 10)) END
  FROM s_customer c, s_wh w
 WHERE c.code = o.cust AND w.prefix = c.wh;
UPDATE h_order SET window_end = window_start + 3;

CREATE TEMP TABLE h_line ON COMMIT DROP AS
SELECT o.key, o.seq, row_number() OVER (PARTITION BY o.key ORDER BY k.sku)::int AS ln, k.sku, k.units AS upc,
       k.price, k.unit_word, k.length_cm, k.width_cm, k.height_cm, k.carton_kg, k.rotatable,
       10 + pg_temp.pick('cartons:' || o.key || ':' || k.sku, 0, 18) AS cartons
  FROM h_order o
  JOIN s_sku k ON k.cust = o.cust
 WHERE pg_temp.rnd('line:' || o.key || ':' || k.sku) < 0.6;

-- An order with no line drawn takes the customer's first SKU.
INSERT INTO h_line
SELECT o.key, o.seq, 1, k.sku, k.units, k.price, k.unit_word, k.length_cm, k.width_cm, k.height_cm, k.carton_kg,
       k.rotatable, 10 + pg_temp.pick('cartons:' || o.key || ':' || k.sku, 0, 18)
  FROM h_order o
  JOIN LATERAL (SELECT * FROM s_sku k WHERE k.cust = o.cust ORDER BY k.sku LIMIT 1) k ON TRUE
 WHERE NOT EXISTS (SELECT 1 FROM h_line l WHERE l.key = o.key);

ALTER TABLE h_line
    ADD COLUMN line_id UUID, ADD COLUMN unit_id UUID, ADD COLUMN snapshot_id UUID, ADD COLUMN unit_price NUMERIC,
    ADD COLUMN goods NUMERIC, ADD COLUMN tax NUMERIC, ADD COLUMN plate BOOLEAN, ADD COLUMN fee NUMERIC,
    ADD COLUMN fee_tax NUMERIC, ADD COLUMN po_line_id UUID, ADD COLUMN received INTEGER, ADD COLUMN first_n INTEGER;

UPDATE h_line l
   SET line_id = pg_temp.sid('order-line:' || l.key || ':' || l.ln), unit_id = pg_temp.sid('unit:' || l.sku),
       snapshot_id = pg_temp.sid('snapshot:' || l.sku), po_line_id = pg_temp.sid('po-line:' || l.key || ':' || l.ln),
       unit_price = l.price + CASE WHEN l.cartons >= 60 THEN -100 WHEN l.cartons >= 30 THEN 0 ELSE 100 END;

-- A SKU's first quote carries its plate fee (14 BR-17); Sales types the amount.
UPDATE h_line l
   SET plate = f.first, fee = CASE WHEN f.first THEN 1500000 + 250000 * pg_temp.pick('fee:' || l.sku, 0, 6) ELSE 0 END
  FROM (SELECT l2.key, l2.ln, row_number() OVER (PARTITION BY l2.sku ORDER BY o.s DESC, o.seq) = 1 AS first
          FROM h_line l2 JOIN h_order o ON o.key = l2.key) f
 WHERE f.key = l.key AND f.ln = l.ln;

-- VAT: the standard rate of the period, 8% until 31/12/2026 (Nghị định 174/2025/NĐ-CP, ordering.tax_rate_period).
UPDATE h_line SET goods = cartons * upc * unit_price;
UPDATE h_line SET tax = round(goods * 8 / 100, 0), fee_tax = round(fee * 8 / 100, 0);
-- Each line's cartons are numbered on from the line before, within the order: LPNs, bins and truck slots use it.
UPDATE h_line l
   SET first_n = 1 + coalesce((SELECT sum(p.cartons) FROM h_line p WHERE p.key = l.key AND p.ln < l.ln), 0),
       received = CASE WHEN o.st = 9 THEN greatest(1, l.cartons / 2) WHEN o.st >= 10 THEN l.cartons ELSE 0 END
  FROM h_order o WHERE o.key = l.key;

UPDATE h_order o
   SET goods = x.goods, fee = x.fee, goods_tax = x.tax, fee_tax = x.fee_tax, cartons = x.cartons
  FROM (SELECT key, sum(goods) goods, sum(fee) fee, sum(tax) tax, sum(fee_tax) fee_tax, sum(cartons) cartons
          FROM h_line GROUP BY key) x
 WHERE x.key = o.key;
UPDATE h_order
   SET total = goods + fee + goods_tax + fee_tax, contract_number = 'HD-' || substring(order_number FROM 4) || '-1';
UPDATE h_order SET deposit = round(total * 30 / 100, 0);
UPDATE h_order SET rest = total - deposit;

-- What a delivered order on credit paid after delivery: mostly on time, some half, some still owed - so the
-- receivables report has a debt in every bucket. One that is not yet due is paid early now and then.
-- u: the order's draw; paid when u < share (85% once due, 35% before), in full unless u falls in the last fifth.
UPDATE h_order o
   SET paid_after = CASE WHEN x.u < x.share
                         THEN greatest(1, o.d - pg_temp.pick('paid-day:' || o.seq, 1,
                                                             greatest(1, least(o.term_days, o.d - 1)))) END,
       paid_part = CASE WHEN x.u < x.share * 0.82 THEN 1 WHEN x.u < x.share THEN 0.5 END
  FROM (SELECT seq, pg_temp.rnd('paid:' || seq) AS u, CASE WHEN d > term_days THEN 0.85 ELSE 0.35 END AS share
          FROM h_order) x
 WHERE x.seq = o.seq AND o.stage = 'DELIVERED' AND o.credit AND o.d > 1;

-- One row per carton of each order that has goods in a warehouse: its LPN, its rows, where it was put away.
CREATE TEMP TABLE h_carton ON COMMIT DROP AS
SELECT o.key, o.seq, l.ln, l.sku, l.line_id, l.upc, l.length_cm, l.width_cm, l.height_cm, l.carton_kg, l.rotatable,
       l.first_n + m - 1 AS n,
       pg_temp.sid('lpn:' || o.key || ':' || (l.first_n + m - 1)) AS package_id,
       pg_temp.sid('stock:' || o.key || ':' || (l.first_n + m - 1)) AS stock_item_id,
       pg_temp.sid('putaway:' || o.key || ':' || (l.first_n + m - 1)) AS task_id,
       pg_temp.sid('allocation:' || o.key || ':' || (l.first_n + m - 1)) AS allocation_id,
       pg_temp.sid('pick-line:' || o.key || ':' || (l.first_n + m - 1)) AS pick_line_id,
       pg_temp.sid('package:' || o.key || ':' || (l.first_n + m - 1)) AS shipping_package_id,
       'LPN-' || pg_temp.ymd(o.r) || '-' || (7000000 + o.seq * 100 + l.first_n + m - 1) AS lpn,
       -- Bins in turn: each order starts somewhere else along the warehouse.
       (SELECT b.code FROM s_bin b
         WHERE b.prefix = o.wh
           AND b.idx = 1 + ((o.seq * 11 + l.first_n + m - 2) % (SELECT count(*) FROM s_bin b2 WHERE b2.prefix = o.wh)))
           AS bin,
       -- Picked: every carton of an order packed or later; the first half of one still being picked.
       o.st >= 13 OR (o.st = 12 AND l.first_n + m - 1 <= o.cartons / 2) AS picked
  FROM h_order o
  JOIN h_line l ON l.key = o.key
 CROSS JOIN LATERAL generate_series(1, l.received) m
 WHERE o.r IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 9. Quotes (14 §4.1): drawn up and sent by Sales, accepted by the customer's own login, converted - or left in draft,
--    sent, sent back for changes, expired or cancelled
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, revision, valid_until, terms, sent_at,
                            sent_by, responded_at, responded_by, requested_delivery_date, version, created_at,
                            created_by)
SELECT o.quote_id, o.quote_number, pg_temp.sid('quote-request:' || o.key), o.customer_id, 'DRAFT', 1,
       pg_temp.at(o.s - 7, '17:00'), 'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.', NULL, NULL, NULL, NULL,
       o.window_start, 0, pg_temp.at(o.s + 7, '09:00'), pg_temp.staff(o.sales)::text
  FROM h_order o;

INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton, tax_class,
                                 tax_rate)
SELECT pg_temp.sid('quote-line:' || l.key || ':' || l.ln), o.quote_id, l.ln, l.sku, l.unit_id, l.cartons, l.upc,
       'STANDARD', 8
  FROM h_line l JOIN h_order o ON o.key = l.key;

INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
SELECT pg_temp.sid('quote-line:' || l.key || ':' || l.ln), t.min_quantity, l.price + t.delta
  FROM h_line l CROSS JOIN (VALUES (10, 100), (30, 0), (60, -100)) AS t (min_quantity, delta);

INSERT INTO ordering.quote_fee_line (id, quote_id, kind, sku, amount, tax_class, tax_rate)
SELECT pg_temp.sid('quote-fee:' || l.key || ':' || l.ln), o.quote_id, 'PLATE_FEE', l.sku, l.fee, 'STANDARD', 8
  FROM h_line l JOIN h_order o ON o.key = l.key WHERE l.plate;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, offer, changed_at,
                                           changed_by)
SELECT pg_temp.sid('quote-history:' || o.key || ':0'), o.quote_id, 1, NULL, 'DRAFT', NULL, NULL,
       pg_temp.at(o.s + 7, '09:00'), pg_temp.staff(o.sales)
  FROM h_order o;

UPDATE ordering.quote q
   SET status = 'SENT', sent_at = pg_temp.at(o.s + 7, '10:00'), sent_by = pg_temp.staff(o.sales), version = 1,
       last_modified_at = pg_temp.at(o.s + 7, '10:00'), last_modified_by = pg_temp.staff(o.sales)::text
  FROM h_order o WHERE q.id = o.quote_id AND o.st >= 1;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, offer, changed_at,
                                           changed_by)
SELECT pg_temp.sid('quote-history:' || o.key || ':1'), o.quote_id, 1, 'DRAFT', 'SENT', NULL,
       jsonb_build_object(
           'revision', 1,
           'validUntil', to_char(pg_temp.at(o.s - 7, '17:00') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
           'terms', 'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.',
           'requestedDeliveryDate', to_char(o.window_start, 'YYYY-MM-DD'),
           'lines', (SELECT jsonb_agg(jsonb_build_object(
                                'lineNo', l.ln, 'sku', l.sku, 'uomConversionId', l.unit_id, 'cartons', l.cartons,
                                'unitsPerCarton', l.upc, 'taxClass', 'STANDARD', 'taxRate', 8,
                                'tiers', jsonb_build_array(jsonb_build_object('minQuantity', 10, 'unitPrice', l.price + 100),
                                                           jsonb_build_object('minQuantity', 30, 'unitPrice', l.price),
                                                           jsonb_build_object('minQuantity', 60, 'unitPrice', l.price - 100)))
                                ORDER BY l.ln)
                       FROM h_line l WHERE l.key = o.key),
           'fees', coalesce((SELECT jsonb_agg(jsonb_build_object('kind', 'PLATE_FEE', 'sku', l.sku, 'amount', l.fee,
                                                                  'taxClass', 'STANDARD', 'taxRate', 8) ORDER BY l.ln)
                               FROM h_line l WHERE l.key = o.key AND l.plate), '[]'::jsonb)),
       pg_temp.at(o.s + 7, '10:00'), pg_temp.staff(o.sales)
  FROM h_order o WHERE o.st >= 1;

-- Sent back for changes by the customer the next day (14 §4.1 step 4).
UPDATE ordering.quote q
   SET status = 'CHANGES_REQUESTED', responded_at = pg_temp.at(o.s + 6, '15:00'),
       responded_by = pg_temp.sid('user:' || o.cust_user),
       customer_feedback = (ARRAY['Nhờ báo lại giá nếu tăng lên 40 thùng mỗi loại.',
                                  'Bên mình muốn giao làm hai đợt, mỗi đợt cách nhau hai tuần.',
                                  'Phí làm bản in hơi cao, anh chị xem giảm được không?'])[1 + o.seq % 3],
       version = 2, last_modified_at = pg_temp.at(o.s + 6, '15:00'),
       last_modified_by = pg_temp.sid('user:' || o.cust_user)::text
  FROM h_order o WHERE q.id = o.quote_id AND o.stage = 'Q_CHANGES';

-- Past its validity, the sweep writes it EXPIRED (QuoteExpirySweeper, actor "system").
UPDATE ordering.quote q
   SET status = 'EXPIRED', version = 2, last_modified_at = pg_temp.at(o.s - 7, '17:05'), last_modified_by = 'system'
  FROM h_order o WHERE q.id = o.quote_id AND o.stage = 'Q_EXPIRED';

UPDATE ordering.quote q
   SET status = 'CANCELLED',
       cancellation_reason = (ARRAY['Khách chuyển sang mẫu ly khác, sẽ gửi báo giá mới.',
                                    'Khách hoãn mở chi nhánh mới, chưa cần hàng.'])[1 + o.seq % 2],
       version = 2, last_modified_at = pg_temp.at(o.s + 6, '11:00'), last_modified_by = pg_temp.staff(o.sales)::text
  FROM h_order o WHERE q.id = o.quote_id AND o.stage = 'Q_CANCELLED';

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, changed_at,
                                           changed_by)
SELECT pg_temp.sid('quote-history:' || o.key || ':2'), o.quote_id, 1, 'SENT', q.status,
       coalesce(q.customer_feedback, q.cancellation_reason), q.last_modified_at,
       CASE o.stage WHEN 'Q_CHANGES' THEN pg_temp.sid('user:' || o.cust_user)
                    WHEN 'Q_CANCELLED' THEN pg_temp.staff(o.sales) END
  FROM h_order o JOIN ordering.quote q ON q.id = o.quote_id
 WHERE o.stage IN ('Q_CHANGES', 'Q_EXPIRED', 'Q_CANCELLED');

UPDATE ordering.quote_line l
   SET accepted_cartons = h.cartons, accepted_unit_price = h.unit_price
  FROM h_line h JOIN h_order o ON o.key = h.key
 WHERE l.id = pg_temp.sid('quote-line:' || h.key || ':' || h.ln) AND o.st >= 2;

UPDATE ordering.quote q
   SET status = 'ACCEPTED', responded_at = pg_temp.at(o.s + 6, '15:00'),
       responded_by = pg_temp.sid('user:' || o.cust_user), version = 2,
       last_modified_at = pg_temp.at(o.s + 6, '15:00'), last_modified_by = pg_temp.sid('user:' || o.cust_user)::text
  FROM h_order o WHERE q.id = o.quote_id AND o.st >= 2;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, offer, changed_at,
                                           changed_by)
SELECT pg_temp.sid('quote-history:' || o.key || ':2'), o.quote_id, 1, 'SENT', 'ACCEPTED', NULL, NULL,
       pg_temp.at(o.s + 6, '15:00'), pg_temp.sid('user:' || o.cust_user)
  FROM h_order o WHERE o.st >= 2;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 10. Each accepted quote converted into a DRAFT order holding nothing (14 §4.2), the next morning but one
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_placed ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 2;

INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, contact_name, contact_email, contact_phone,
                                     shipping_recipient_name, shipping_phone, shipping_line1, shipping_ward_code,
                                     shipping_ward_name, shipping_province_code, shipping_province_name,
                                     shipping_country_code, billing_recipient_name, billing_phone, billing_line1,
                                     billing_ward_code, billing_ward_name, billing_province_code, billing_province_name,
                                     billing_country_code, version, created_at, created_by)
SELECT o.id, o.order_number, o.customer_id, pg_temp.sid('order-request:' || o.key), 'DRAFT', o.total,
       o.goods_tax + o.fee_tax, 'VND', pg_temp.at(o.s + 5, '09:30'), c.contact, c.email, c.phone, c.contact, c.phone,
       c.line1, c.ward_code, c.ward, c.province_code, c.province, 'VN', c.name, c.phone, c.line1, c.ward_code, c.ward,
       c.province_code, c.province, 'VN', 0, pg_temp.at(o.s + 5, '09:30'), pg_temp.staff(o.sales)::text
  FROM h_placed o JOIN s_customer c ON c.code = o.cust;

INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, requested_delivery_date,
                                 uom_conversion_id, cartons, units_per_carton, tax_rate, tax_amount)
SELECT l.line_id, o.id, l.sku, l.cartons * l.upc, l.unit_price, 'VND', o.window_start, l.unit_id, l.cartons, l.upc, 8,
       l.tax
  FROM h_line l JOIN h_placed o ON o.key = l.key;

INSERT INTO ordering.order_fee_line (id, order_id, kind, sku, amount, tax_rate, tax_amount, currency)
SELECT pg_temp.sid('order-fee:' || l.key || ':' || l.ln), o.id, 'PLATE_FEE', l.sku, l.fee, 8, l.fee_tax, 'VND'
  FROM h_line l JOIN h_placed o ON o.key = l.key WHERE l.plate;

UPDATE ordering.quote q
   SET status = 'CONVERTED', converted_order_id = o.id, converted_at = pg_temp.at(o.s + 5, '09:30'), version = 3,
       last_modified_at = pg_temp.at(o.s + 5, '09:30'), last_modified_by = pg_temp.staff(o.sales)::text
  FROM h_placed o WHERE q.id = o.quote_id;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, changed_at,
                                           changed_by)
SELECT pg_temp.sid('quote-history:' || o.key || ':3'), o.quote_id, 1, 'ACCEPTED', 'CONVERTED', o.order_number,
       pg_temp.at(o.s + 5, '09:30'), pg_temp.staff(o.sales)
  FROM h_placed o;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':0'), o.id, NULL, 'DRAFT', NULL, NULL, pg_temp.at(o.s + 5, '09:30'),
       pg_temp.at(o.s + 5, '09:30'), pg_temp.staff(o.sales)::text
  FROM h_placed o;

-- Some drafts are cancelled at the customer's request before a contract is drawn up.
UPDATE ordering.customer_order co
   SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST',
       cancellation_reason = (ARRAY['Khách đổi số lượng, sẽ đặt lại đơn mới.',
                                    'Khách tạm dừng kế hoạch khai trương cửa hàng.',
                                    'Khách đặt trùng đơn với báo giá trước.'])[1 + o.seq % 3],
       version = 1, last_modified_at = pg_temp.at(o.s + 4, '10:00'), last_modified_by = pg_temp.staff(o.sales)::text
  FROM h_placed o WHERE co.id = o.id AND o.stage = 'O_CANCELLED';

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':c'), o.id, 'DRAFT', 'CANCELLED', 'CUSTOMER_REQUEST',
       pg_temp.staff(o.sales), pg_temp.at(o.s + 4, '10:00'), pg_temp.at(o.s + 4, '10:00'), pg_temp.staff(o.sales)::text
  FROM h_placed o WHERE o.stage = 'O_CANCELLED';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 11. The contract (14 §4.3): one delivery batch; 30% on signing, the rest after delivery on the customer's credit
--     (15 BR-11) or, without credit, before delivery. Drawn up, sent, signed with the customer's scan.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_contract ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 3;

INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
                                     seller_address, seller_representative, seller_representative_title, buyer_name,
                                     buyer_tax_code, buyer_address, buyer_representative, buyer_representative_title,
                                     free_sample_rounds, version, created_at, created_by)
SELECT o.contract_id, o.contract_number, o.id, 'DRAFT', 'CÔNG TY TNHH BAO BÌ STOCKFLOW', '0318888888',
       'Số 8 Đường số 6, KCN Sóng Thần 1, Phường Dĩ An, TP. Hồ Chí Minh', 'Phạm Minh Trưởng', 'Giám đốc kinh doanh',
       c.name, c.tax_code, c.line1 || ', ' || c.ward || ', ' || c.province, c.contact, 'Giám đốc', 2, 0,
       pg_temp.at(o.s + 4, '10:00'), pg_temp.staff(o.sales)::text
  FROM h_contract o JOIN s_customer c ON c.code = o.cust;

INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
SELECT o.batch_id, o.contract_id, 1, o.window_start, o.window_end FROM h_contract o;

INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
SELECT pg_temp.sid('batch-line:' || l.key || ':' || l.ln), o.batch_id, l.line_id, l.cartons
  FROM h_line l JOIN h_contract o ON o.key = l.key;

INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
SELECT pg_temp.sid('plan:' || o.key || ':1'), o.contract_id, 1, 'Đặt cọc khi ký', 'ON_SIGNING', 30, o.deposit, 3, NULL
  FROM h_contract o
UNION ALL
SELECT pg_temp.sid('plan:' || o.key || ':2'), o.contract_id, 2,
       CASE WHEN o.credit THEN 'Thanh toán sau giao hàng' ELSE 'Thanh toán trước giao hàng' END,
       CASE WHEN o.credit THEN 'AFTER_DELIVERY' ELSE 'BEFORE_DELIVERY' END, NULL, o.rest,
       CASE WHEN o.credit THEN o.term_days END, o.batch_id
  FROM h_contract o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE ordering.sales_contract k
   SET status = 'SENT', sent_at = pg_temp.at(o.s + 3, '09:00'), sent_by = pg_temp.staff(o.sales),
       sent_document_key = 'documents/showcase/' || o.contract_number || '.pdf', version = 1,
       last_modified_at = pg_temp.at(o.s + 3, '09:00'), last_modified_by = pg_temp.staff(o.sales)::text
  FROM h_contract o WHERE k.id = o.contract_id;

UPDATE ordering.customer_order co
   SET status = 'PENDING_CONTRACT', version = 1, last_modified_at = pg_temp.at(o.s + 3, '09:00'),
       last_modified_by = pg_temp.staff(o.sales)::text
  FROM h_contract o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':1'), o.id, 'DRAFT', 'PENDING_CONTRACT', 'CONTRACT_SENT',
       pg_temp.staff(o.sales), pg_temp.at(o.s + 3, '09:00'), pg_temp.at(o.s + 3, '09:00'), pg_temp.staff(o.sales)::text
  FROM h_contract o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Signed: the order is placed (17 §4.11: the day the dashboard counts it from) and waits for its deposit.
CREATE TEMP TABLE h_signed ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 4;

UPDATE ordering.sales_contract k
   SET status = 'SIGNED', signed_on = pg_temp.day(o.s), signed_at = pg_temp.at(o.s, '10:00'),
       signed_by = pg_temp.staff(o.sales), signed_file_key = 'documents/showcase/' || o.contract_number || '-ky.pdf',
       signed_file_name = o.contract_number || '-da-ky.pdf', version = 2, last_modified_at = pg_temp.at(o.s, '10:00'),
       last_modified_by = pg_temp.staff(o.sales)::text
  FROM h_signed o WHERE k.id = o.contract_id;

UPDATE ordering.customer_order co
   SET status = 'PENDING_PAYMENT', contract_signed_at = pg_temp.at(o.s, '10:00'), placed_at = pg_temp.at(o.s, '10:00'),
       version = 2, last_modified_at = pg_temp.at(o.s, '10:00'), last_modified_by = pg_temp.staff(o.sales)::text
  FROM h_signed o WHERE co.id = o.id;

-- 15 BR-10, BR-11: a plan paying after delivery is checked against the customer's credit, and the check recorded. What
-- the customer owed after delivery on the contracts signed before - less what was paid by then - is the exposure.
INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome, note,
                                         version, created_at, created_by)
SELECT pg_temp.sid('credit-check:' || o.key), o.id, pg_temp.at(o.s, '10:00'), o.credit_limit,
       coalesce((SELECT sum(p.rest - CASE WHEN p.paid_after > o.s THEN round(p.rest * p.paid_part, 0) ELSE 0 END)
                   FROM h_signed p
                  WHERE p.cust = o.cust AND p.s > o.s AND p.credit), 0),
       o.rest, 'WITHIN_LIMIT', 'Sales contract ' || o.contract_number, 0, pg_temp.at(o.s, '10:00'),
       pg_temp.staff(o.sales)::text
  FROM h_signed o
 WHERE o.credit;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':2'), o.id, 'PENDING_CONTRACT', 'PENDING_PAYMENT', 'CONTRACT_SIGNED',
       pg_temp.staff(o.sales), pg_temp.at(o.s, '10:00'), pg_temp.at(o.s, '10:00'), pg_temp.staff(o.sales)::text
  FROM h_signed o;

-- 15 §4.1 step 1 (4B-6): the installments open with the signature; the one on signing is due at once, three days to pay.
INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
                                 order_number, seq, name, milestone, delivery_batch_id, delivery_batch_seq, amount,
                                 paid_amount, currency, due_days, reached_at, due_date, status, version, created_at,
                                 created_by)
SELECT pg_temp.sid('installment:' || o.key || ':' || p.seq), o.contract_id, p.id, o.id, o.customer_id,
       o.contract_number, o.order_number, p.seq, p.name, p.milestone, p.delivery_batch_id,
       CASE WHEN p.delivery_batch_id IS NOT NULL THEN 1 END, p.amount, 0, 'VND', p.due_days,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN pg_temp.at(o.s, '10:00') END,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN pg_temp.day(o.s) + p.due_days END,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN 'DUE' ELSE 'NOT_DUE' END, 0, pg_temp.at(o.s, '10:00'),
       pg_temp.staff(o.sales)::text
  FROM h_signed o JOIN ordering.contract_payment_installment p ON p.contract_id = o.contract_id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 12. Deposits (15 §4.2): the accountant records the transfer the next morning and the order is confirmed; Sales
--     chooses the shipping warehouse that afternoon - nothing is there, so every carton is to be produced.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_confirmed ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 5;

INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, note, status,
                                     recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.sid('receipt:' || o.key || ':1'), o.customer_id, 'FT' || pg_temp.ymd(o.s - 1) || (70000 + o.seq * 10 + 1),
       pg_temp.day(o.s - 1), o.deposit, 'VND', 'Đặt cọc hợp đồng ' || o.contract_number, 'RECORDED',
       pg_temp.staff('ketoan'), pg_temp.at(o.s - 1, '10:30'), 0, pg_temp.at(o.s - 1, '10:30'),
       pg_temp.staff('ketoan')::text
  FROM h_confirmed o;

INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount, created_at)
SELECT pg_temp.sid('receipt-line:' || o.key || ':1'), pg_temp.sid('receipt:' || o.key || ':1'),
       pg_temp.sid('installment:' || o.key || ':1'), o.deposit, pg_temp.at(o.s - 1, '10:30')
  FROM h_confirmed o;

UPDATE payment.installment i
   SET paid_amount = o.deposit, status = 'PAID', paid_at = pg_temp.at(o.s - 1, '10:30'), version = 1,
       last_modified_at = pg_temp.at(o.s - 1, '10:30'), last_modified_by = pg_temp.staff('ketoan')::text
  FROM h_confirmed o WHERE i.id = pg_temp.sid('installment:' || o.key || ':1');

UPDATE ordering.customer_order co
   SET status = 'CONFIRMED', version = 3, last_modified_at = pg_temp.at(o.s - 1, '10:30'), last_modified_by = 'system'
  FROM h_confirmed o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':3'), o.id, 'PENDING_PAYMENT', 'CONFIRMED', 'INSTALLMENT_PAID', NULL,
       pg_temp.at(o.s - 1, '10:30'), pg_temp.at(o.s - 1, '10:30'), 'system'
  FROM h_confirmed o;

UPDATE ordering.customer_order co
   SET shipping_warehouse_id = pg_temp.sid('wh:' || o.wh), shipping_warehouse_chosen_at = pg_temp.at(o.s - 1, '14:00'),
       shipping_warehouse_chosen_by = pg_temp.staff(o.sales), version = 4,
       last_modified_at = pg_temp.at(o.s - 1, '14:00'), last_modified_by = pg_temp.staff(o.sales)::text
  FROM h_confirmed o WHERE co.id = o.id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 13. Production orders (17 §4.3): drawn up by the coordinator for the batch and sent by the coordinator, with no
--     approval since 2026-11-20 (BR-23, V20261213000100); the producer accepts and prints. A SKU's first print would need a print sample: it
--     was waived on the approved print file (BR-24). The order waits AWAITING_STOCK from the sending.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                       receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                       sample_required_by_default, sample_waiver_reason, drawn_up_by, version,
                                       created_at, created_by)
SELECT o.po_id, o.po_code, o.id, o.batch_id, o.customer_id, pg_temp.sid('producer:' || o.producer),
       pg_temp.sid('wh:' || o.wh), o.window_start - 3, 'DRAFT', FALSE, o.fee > 0,
       CASE WHEN o.fee > 0 THEN 'Khách đã duyệt bản in thử trên file in; miễn mẫu in' END,
       pg_temp.staff('dieuphoi'), 0, pg_temp.at(o.s - 1, '15:00'), pg_temp.staff('dieuphoi')::text
  FROM h_confirmed o;

INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                            design_snapshot_id, print_file_checksum)
SELECT l.po_line_id, o.po_id, l.line_id, l.sku, l.cartons, l.upc, l.snapshot_id, a.checksum
  FROM h_line l JOIN h_confirmed o ON o.key = l.key
  JOIN design.design_artifact a ON a.id = pg_temp.sid('artifact:' || l.sku);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

CREATE TEMP TABLE h_sent ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 6;

UPDATE ordering.production_order p
   SET status = 'SENT', sent_at = pg_temp.at(o.s - 2, '10:00'), sent_by = pg_temp.staff('dieuphoi'), version = 1,
       last_modified_at = pg_temp.at(o.s - 2, '10:00'), last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM h_sent o WHERE p.id = o.po_id;

-- The slip the producer was sent (17 §4.3 step 8): named, not stored.
INSERT INTO ordering.production_order_slip (production_order_id, storage_key, sha256, size_bytes, created_at)
SELECT o.po_id, 'documents/showcase/' || o.po_code || '.pdf',
       encode(sha256(convert_to('showcase:slip:' || o.key, 'UTF8')), 'hex'), 26214, pg_temp.at(o.s - 2, '10:00')
  FROM h_sent o;

UPDATE ordering.customer_order co
   SET status = 'AWAITING_STOCK', version = 5, last_modified_at = pg_temp.at(o.s - 2, '10:00'),
       last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM h_sent o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':4'), o.id, 'CONFIRMED', 'AWAITING_STOCK', 'PRODUCTION_ORDER_SENT',
       pg_temp.staff('dieuphoi'), pg_temp.at(o.s - 2, '10:00'), pg_temp.at(o.s - 2, '10:00'),
       pg_temp.staff('dieuphoi')::text
  FROM h_sent o;

UPDATE ordering.production_order p
   SET status = 'ACCEPTED', expected_completion_date = CASE WHEN o.r IS NOT NULL THEN pg_temp.day(o.r + 1)
                                                            ELSE o.window_start - 5 END,
       acceptance_note = 'Nhận đơn, đã xếp lịch máy in', version = 3, last_modified_at = pg_temp.at(o.s - 3, '09:00'),
       last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM h_order o WHERE p.id = o.po_id AND o.st >= 7;

UPDATE ordering.production_order p
   SET status = 'IN_PRODUCTION', production_started_at = pg_temp.at(o.s - 4, '08:00'), version = 4,
       last_modified_at = pg_temp.at(o.s - 4, '08:00'), last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM h_order o WHERE p.id = o.po_id AND o.st >= 8;

-- BR-11: the order records when printing started, once - what the cancellation fee rule reads.
UPDATE ordering.customer_order co
   SET printing_started_at = pg_temp.at(o.s - 4, '08:00'), version = 6,
       last_modified_at = pg_temp.at(o.s - 4, '08:00'), last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM h_order o WHERE co.id = o.id AND o.st >= 8;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 14. The printed goods received (03 §4.1), in cartons of the standard count labelled while counted, in the
--     production order's lot (BR-03), and confirmed into INBOUND cartons on the receiving dock
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_received ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 9;

INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                        delivery_note, version, created_at, created_by, receipt_type,
                                        production_order_id)
SELECT pg_temp.sid('receipt-goods:' || o.key), o.receipt_number, pg_temp.sid('wh:' || o.wh), 'DRAFT',
       pg_temp.at(o.r, '08:00'), pg_temp.staff(o.kho), 'PXK-' || o.po_code, 0, pg_temp.at(o.r, '08:00'),
       pg_temp.staff(o.kho)::text, 'PRODUCTION', o.po_id
  FROM h_received o;

INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status, opened_at)
SELECT o.po_id, pg_temp.sid('receipt-goods:' || o.key), o.receipt_number, 'DRAFT', pg_temp.at(o.r, '08:00')
  FROM h_received o;

INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty, lot_number,
                                             version, created_at, created_by, uom_conversion_id,
                                             production_order_line_id)
SELECT pg_temp.sid('receipt-goods-line:' || l.key || ':' || l.ln), pg_temp.sid('receipt-goods:' || o.key), i.id,
       pg_temp.sid('location:' || o.wh || '-RCV01'), l.received * l.upc, o.po_code, 0, pg_temp.at(o.r, '08:20'),
       pg_temp.staff(o.kho)::text, l.unit_id, l.po_line_id
  FROM h_line l JOIN h_received o ON o.key = l.key JOIN inventory.inventory_items i ON i.sku = l.sku;

INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status, goods_receipt_line_id, version, created_at, created_by)
SELECT c.package_id, c.lpn, i.id, pg_temp.sid('unit:' || c.sku), c.upc, c.upc, o.wh || '-RCV01', 'RECEIVING',
       pg_temp.sid('receipt-goods-line:' || c.key || ':' || c.ln), 0, pg_temp.at(o.r, '08:30'),
       pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_received o ON o.key = c.key JOIN inventory.inventory_items i ON i.sku = c.sku;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Confirmed (03 §4.1 step 6): the lot, the cartons in stock on the dock, one RECEIPT line each, a putaway task each.
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, warehouse_id, version, created_at,
                                 created_by, production_order_id, production_order_code)
SELECT pg_temp.sid('lot:' || l.key || ':' || l.ln), l.sku, o.po_code, pg_temp.at(o.r, '09:00'), 'PRODUCTION_ORDER',
       pg_temp.sid('wh:' || o.wh), 0, pg_temp.at(o.r, '09:00'), pg_temp.staff(o.kho)::text, o.po_id, o.po_code
  FROM h_line l JOIN h_received o ON o.key = l.key;

UPDATE procurement.goods_receipts g
   SET status = 'IN_PUTAWAY', confirmed_at = pg_temp.at(o.r, '09:00'), confirmed_by = pg_temp.staff(o.kho),
       version = 1, last_modified_at = pg_temp.at(o.r, '09:00'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_received o WHERE g.id = pg_temp.sid('receipt-goods:' || o.key);

UPDATE ordering.production_order_receipt r
   SET status = 'CONFIRMED', settled_at = pg_temp.at(o.r, '09:00')
  FROM h_received o WHERE r.goods_receipt_id = pg_temp.sid('receipt-goods:' || o.key);

UPDATE ordering.production_order_line pl
   SET received_cartons = l.received
  FROM h_line l JOIN h_received o ON o.key = l.key WHERE pl.id = l.po_line_id;

UPDATE ordering.production_order p
   SET status = CASE WHEN o.st >= 10 THEN 'RECEIVED' ELSE 'PARTIALLY_RECEIVED' END, version = 5,
       last_modified_at = pg_temp.at(o.r, '09:00'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_received o WHERE p.id = o.po_id;

UPDATE inventory.stock_package p
   SET status = 'IN_STOCK', version = 1, last_modified_at = pg_temp.at(o.r, '09:00'),
       last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_received o ON o.key = c.key WHERE p.id = c.package_id;

INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, version, created_at,
                                  created_by, received_at, stock_package_id, lot_id)
SELECT c.stock_item_id, c.sku, o.wh || '-RCV01', o.po_code, c.upc, 0, 'INBOUND', 0, pg_temp.at(o.r, '09:00'),
       pg_temp.staff(o.kho)::text, pg_temp.at(o.r, '09:00'), c.package_id, pg_temp.sid('lot:' || c.key || ':' || c.ln)
  FROM h_carton c JOIN h_received o ON o.key = c.key;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      to_stock_package_id, occurred_at, version, created_at, created_by)
SELECT pg_temp.sid('ledger:receipt:' || c.key || ':' || c.n), 'RECEIPT', c.sku, o.po_code,
       pg_temp.sid('lot:' || c.key || ':' || c.ln), NULL, o.wh || '-RCV01', c.upc, NULL, 'INBOUND', 'GOODS_RECEIPT_LINE',
       pg_temp.sid('receipt-goods-line:' || c.key || ':' || c.ln), pg_temp.staff(o.kho), c.package_id,
       pg_temp.at(o.r, '09:00'), 0, pg_temp.at(o.r, '09:00'), pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_received o ON o.key = c.key;

INSERT INTO warehouse.putaway_task (id, goods_receipt_id, sku, quantity, status, version, created_at, created_by,
                                    goods_receipt_line_id, stock_package_id, source)
SELECT c.task_id, pg_temp.sid('receipt-goods:' || o.key), c.sku, c.upc, 'PENDING', 0, pg_temp.at(o.r, '09:00'), 'system',
       pg_temp.sid('receipt-goods-line:' || c.key || ':' || c.ln), c.package_id, 'RECEIPT'
  FROM h_carton c JOIN h_received o ON o.key = c.key;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The production order's history, as far as each one went.
INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status, to_status, reason,
                                                      changed_by, changed_at)
SELECT pg_temp.sid('po-history:' || o.key || ':' || h.n), o.po_id, h.from_status, h.to_status, h.reason,
       pg_temp.staff(h.who), h.at
  FROM h_confirmed o
 CROSS JOIN LATERAL (VALUES (0, 5, NULL, 'DRAFT', NULL, 'dieuphoi', pg_temp.at(o.s - 1, '15:00')),
                            (2, 6, 'DRAFT', 'SENT', NULL, 'dieuphoi', pg_temp.at(o.s - 2, '10:00')),
                            (3, 7, 'SENT', 'ACCEPTED', NULL, 'dieuphoi', pg_temp.at(o.s - 3, '09:00')),
                            (4, 8, 'ACCEPTED', 'IN_PRODUCTION', NULL, 'dieuphoi', pg_temp.at(o.s - 4, '08:00')),
                            (5, 9, 'IN_PRODUCTION', CASE WHEN o.st >= 10 THEN 'RECEIVED' ELSE 'PARTIALLY_RECEIVED' END,
                             CASE WHEN o.st >= 10 THEN 'Received in full on receipt ' || o.receipt_number
                                  ELSE 'Received in part on receipt ' || o.receipt_number
                                       || '; the rest stays open (03 BR-10)' END, o.kho, pg_temp.at(o.r, '09:00')))
             AS h (n, needs, from_status, to_status, reason, who, at)
 WHERE o.st >= h.needs;

-- ---------------------------------------------------------------------------------------------
-- 15. Putaway (05): one task per carton, each carton whole into a bin that fits it - a minute a carton from 10:00
-- ---------------------------------------------------------------------------------------------
UPDATE inventory.stock_package p
   SET location_code = c.bin, version = 2,
       last_modified_at = pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_received o ON o.key = c.key WHERE p.id = c.package_id;

UPDATE inventory.stock_item s
   SET location_code = c.bin, status = 'AVAILABLE', version = 1,
       last_modified_at = pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_received o ON o.key = c.key WHERE s.id = c.stock_item_id;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      from_stock_package_id, to_stock_package_id, occurred_at, version, created_at,
                                      created_by)
SELECT pg_temp.sid('ledger:putaway:' || c.key || ':' || c.n), 'MOVE', c.sku, o.po_code,
       pg_temp.sid('lot:' || c.key || ':' || c.ln), o.wh || '-RCV01', c.bin, c.upc, 'INBOUND', 'AVAILABLE',
       'PUTAWAY_TASK', c.task_id, pg_temp.staff(o.kho), c.package_id, c.package_id,
       pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval, 0,
       pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval, pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_received o ON o.key = c.key;

UPDATE warehouse.putaway_task t
   SET status = 'DONE', target_location_id = pg_temp.sid('location:' || c.bin), version = 1,
       last_modified_at = pg_temp.at(o.r, '10:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_received o ON o.key = c.key WHERE t.id = c.task_id;

UPDATE procurement.goods_receipts g
   SET status = 'CLOSED', closed_at = pg_temp.at(o.r, '12:00'), version = 2, last_modified_at = pg_temp.at(o.r, '12:00'),
       last_modified_by = 'system'
  FROM h_received o WHERE g.id = pg_temp.sid('receipt-goods:' || o.key);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Put away, the goods top up the order waiting for them (17 §4.2 step 5): a hold of each line's SKU, its batch's.
INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at, status, sku,
                                         warehouse_code)
SELECT pg_temp.sid('hold:' || l.key || ':' || l.ln), o.id, pg_temp.sid('hold-request:' || l.key || ':' || l.ln),
       pg_temp.sid('hold-request:' || l.key || ':' || l.ln), l.received * l.upc, pg_temp.at(o.r, '12:00'), 'HELD', l.sku,
       o.wh
  FROM h_line l JOIN h_received o ON o.key = l.key;

INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id, created_at)
SELECT pg_temp.sid('hold:' || l.key || ':' || l.ln), o.batch_id, o.id, l.line_id, pg_temp.at(o.r, '12:00')
  FROM h_line l JOIN h_received o ON o.key = l.key;

INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id)
SELECT l.line_id, pg_temp.sid('hold:' || l.key || ':' || l.ln) FROM h_line l JOIN h_received o ON o.key = l.key;

-- ---------------------------------------------------------------------------------------------
-- 16. Release (17 §4.4, 4B-8): the warehouse manager releases the batch three days before it leaves; each hold is
--     allocated carton by carton (whole sealed cartons, FIFO); a payment before delivery falls due.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_released ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 11;

INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
SELECT o.batch_id, o.id, 1, pg_temp.sid('wh:' || o.wh), pg_temp.at(o.d + 3, '09:00'), pg_temp.staff(o.mgr)
  FROM h_released o;

UPDATE ordering.customer_order co
   SET status = 'READY_TO_FULFILL', warehouse_id = pg_temp.sid('wh:' || o.wh), released_at = pg_temp.at(o.d + 3, '09:00'),
       released_by = pg_temp.staff(o.mgr), version = 7, last_modified_at = pg_temp.at(o.d + 3, '09:00'),
       last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_released o WHERE co.id = o.id;

UPDATE inventory.stock_reservation r
   SET status = 'ALLOCATED', closed_at = pg_temp.at(o.d + 3, '09:00')
  FROM h_line l JOIN h_released o ON o.key = l.key WHERE r.id = pg_temp.sid('hold:' || l.key || ':' || l.ln);

INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity,
                                         reserved_at, status, sku, allocated_from_id)
SELECT c.allocation_id, c.stock_item_id, o.id, pg_temp.sid('hold:' || c.key || ':' || c.ln),
       pg_temp.sid('allocation-request:' || c.key || ':' || c.n), c.upc, pg_temp.at(o.d + 3, '09:00'), 'HELD', c.sku,
       pg_temp.sid('hold:' || c.key || ':' || c.ln)
  FROM h_carton c JOIN h_released o ON o.key = c.key;

UPDATE inventory.stock_item s
   SET reserved = c.upc, version = 2, last_modified_at = pg_temp.at(o.d + 3, '09:00'),
       last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_carton c JOIN h_released o ON o.key = c.key WHERE s.id = c.stock_item_id;

INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id, created_at)
SELECT c.allocation_id, o.batch_id, o.id, c.line_id, pg_temp.at(o.d + 3, '09:00')
  FROM h_carton c JOIN h_released o ON o.key = c.key;

-- The line swaps its hold for the allocations (CLAUDE.md §4: all of them, not the first).
DELETE FROM ordering.order_line_reservation lr
 USING h_line l, h_released o
 WHERE o.key = l.key AND lr.order_line_id = l.line_id AND lr.reservation_id = pg_temp.sid('hold:' || l.key || ':' || l.ln);
INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id)
SELECT c.line_id, c.allocation_id FROM h_carton c JOIN h_released o ON o.key = c.key;

UPDATE payment.installment i
   SET reached_at = pg_temp.at(o.d + 3, '09:00'), status = 'DUE', version = 1,
       last_modified_at = pg_temp.at(o.d + 3, '09:00'), last_modified_by = 'system'
  FROM h_released o
 WHERE i.order_id = o.id AND i.milestone = 'BEFORE_DELIVERY';

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':5'), o.id, 'AWAITING_STOCK', 'READY_TO_FULFILL', NULL,
       pg_temp.staff(o.mgr), pg_temp.at(o.d + 3, '09:00'), pg_temp.at(o.d + 3, '09:00'), pg_temp.staff(o.mgr)::text
  FROM h_released o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- A customer without credit pays what is due before delivery the day after the release (15 BR-08).
CREATE TEMP TABLE h_picking ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 12;

INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, note, status,
                                     recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.sid('receipt:' || o.key || ':2'), o.customer_id, 'FT' || pg_temp.ymd(o.d + 2) || (70000 + o.seq * 10 + 2),
       pg_temp.day(o.d + 2), o.rest, 'VND', 'Thanh toán trước giao hàng hợp đồng ' || o.contract_number, 'RECORDED',
       pg_temp.staff('ketoan'), pg_temp.at(o.d + 2, '07:00'), 0, pg_temp.at(o.d + 2, '07:00'),
       pg_temp.staff('ketoan')::text
  FROM h_picking o WHERE NOT o.credit;

INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount, created_at)
SELECT pg_temp.sid('receipt-line:' || o.key || ':2'), pg_temp.sid('receipt:' || o.key || ':2'),
       pg_temp.sid('installment:' || o.key || ':2'), o.rest, pg_temp.at(o.d + 2, '07:00')
  FROM h_picking o WHERE NOT o.credit;

UPDATE payment.installment i
   SET paid_amount = o.rest, status = 'PAID', paid_at = pg_temp.at(o.d + 2, '07:00'), version = 2,
       last_modified_at = pg_temp.at(o.d + 2, '07:00'), last_modified_by = pg_temp.staff('ketoan')::text
  FROM h_picking o WHERE i.id = pg_temp.sid('installment:' || o.key || ':2') AND NOT o.credit;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 17. Picking by LPN (07): the manager opens the task to dispatch for the warehouse staff the next morning; each
--     carton is scanned at its bin and deducted at the scan (07 BR-10, BR-04)
-- ---------------------------------------------------------------------------------------------
INSERT INTO fulfillment.pick (id, order_id, status, version, created_at, created_by, assigned_user_id, assigned_at,
                              order_number, warehouse_id, destination, delivery_batch_id)
SELECT pg_temp.sid('pick:' || o.key), o.id, 'PENDING', 0, pg_temp.at(o.d + 2, '07:30'), pg_temp.staff(o.mgr)::text,
       pg_temp.staff(o.kho), pg_temp.at(o.d + 2, '07:30'), o.order_number, pg_temp.sid('wh:' || o.wh), 'DISPATCH',
       o.batch_id
  FROM h_picking o;

INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, lot_number,
                                   sequence_no, allocated_qty, picked_qty, short_qty, status, version, created_at,
                                   created_by, lpn, whole_carton)
SELECT c.pick_line_id, pg_temp.sid('pick:' || o.key), c.line_id, c.allocation_id, c.sku, c.bin, o.po_code, c.n, c.upc,
       0, 0, 'OPEN', 0, pg_temp.at(o.d + 2, '07:30'), pg_temp.staff(o.mgr)::text, c.lpn, TRUE
  FROM h_carton c JOIN h_picking o ON o.key = c.key;

-- The check station's session is opened with the task (fulfillment.pack, 4C-6).
INSERT INTO fulfillment.pack (id, pick_id, status, version, created_at, created_by)
SELECT pg_temp.sid('pack:' || o.key), pg_temp.sid('pick:' || o.key), 'PENDING', 0, pg_temp.at(o.d + 2, '07:30'),
       pg_temp.staff(o.mgr)::text
  FROM h_picking o;

UPDATE ordering.customer_order co
   SET status = 'PICKING', fulfilment_started_at = pg_temp.at(o.d + 2, '07:30'), version = 8,
       last_modified_at = pg_temp.at(o.d + 2, '07:30'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_picking o WHERE co.id = o.id;

UPDATE fulfillment.pick p
   SET status = 'PICKING', started_at = pg_temp.at(o.d + 2, '08:00'), version = 1,
       last_modified_at = pg_temp.at(o.d + 2, '08:00'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_picking o WHERE p.id = pg_temp.sid('pick:' || o.key);

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':6'), o.id, 'READY_TO_FULFILL', 'PICKING', NULL,
       pg_temp.staff(o.mgr), pg_temp.at(o.d + 2, '07:30'), pg_temp.at(o.d + 2, '07:30'), pg_temp.staff(o.mgr)::text
  FROM h_picking o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

CREATE TEMP TABLE h_picked ON COMMIT DROP AS
SELECT c.*, o.d, o.kho, o.po_code, o.id AS order_id, pg_temp.at(o.d + 2, '08:00') + (c.n || ' minutes')::interval AS at
  FROM h_carton c JOIN h_picking o ON o.key = c.key WHERE c.picked;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      from_stock_package_id, order_id, occurred_at, version, created_at, created_by)
SELECT pg_temp.sid('ledger:pick:' || c.key || ':' || c.n), 'PICK', c.sku, c.po_code,
       pg_temp.sid('lot:' || c.key || ':' || c.ln), c.bin, NULL, c.upc, 'AVAILABLE', NULL, 'PICK_LINE', c.pick_line_id,
       pg_temp.staff(c.kho), c.package_id, c.order_id, c.at, 0, c.at, pg_temp.staff(c.kho)::text
  FROM h_picked c;

UPDATE inventory.stock_item s
   SET on_hand = 0, reserved = 0, version = 3, last_modified_at = c.at, last_modified_by = pg_temp.staff(c.kho)::text
  FROM h_picked c WHERE s.id = c.stock_item_id;

UPDATE inventory.stock_package p
   SET status = 'CONSUMED', closed_at = c.at, version = 3, last_modified_at = c.at,
       last_modified_by = pg_temp.staff(c.kho)::text
  FROM h_picked c WHERE p.id = c.package_id;

UPDATE inventory.stock_reservation r
   SET status = 'CONSUMED', closed_at = c.at
  FROM h_picked c WHERE r.id = c.allocation_id;

UPDATE fulfillment.pick_line l
   SET status = 'PICKED', picked_qty = c.upc, picked_by = pg_temp.staff(c.kho), picked_at = c.at, consumed_at = c.at,
       scanned_location_code = c.bin, scanned_code = c.lpn, version = 1, last_modified_at = c.at,
       last_modified_by = pg_temp.staff(c.kho)::text
  FROM h_picked c WHERE l.id = c.pick_line_id;

CREATE TEMP TABLE h_packed ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 13;

UPDATE fulfillment.pick p
   SET status = 'PICKED', picked_at = pg_temp.at(o.d + 2, '10:00'), version = 2,
       last_modified_at = pg_temp.at(o.d + 2, '10:00'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_packed o WHERE p.id = pg_temp.sid('pick:' || o.key);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 18. Carton check and labelling (08, 4C-6): each LPN carton is the shipping carton, checked and labelled with its
--     carton spec; completion makes the labelled cartons one shipment and the order PACKED
-- ---------------------------------------------------------------------------------------------
UPDATE fulfillment.pack k
   SET status = 'IN_PROGRESS', started_at = pg_temp.at(o.d + 2, '13:00'), started_by = pg_temp.staff(o.kho),
       version = 1, last_modified_at = pg_temp.at(o.d + 2, '13:00'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_packed o WHERE k.id = pg_temp.sid('pack:' || o.key);

INSERT INTO fulfillment.package (id, pack_id, package_no, status, version, created_at, created_by, label_code,
                                 rotatable, rotatable_overridden, pick_line_id, lpn)
SELECT c.shipping_package_id, pg_temp.sid('pack:' || o.key), c.n, 'TO_CHECK', 0, pg_temp.at(o.d + 2, '13:00'),
       pg_temp.staff(o.kho)::text, o.order_number || '-C' || c.n, c.rotatable, FALSE, c.pick_line_id, c.lpn
  FROM h_carton c JOIN h_packed o ON o.key = c.key;

UPDATE fulfillment.package p
   SET status = 'CHECKED', checked_at = pg_temp.at(o.d + 2, '13:00') + (c.n || ' minutes')::interval,
       checked_by = pg_temp.staff(o.kho), version = 1,
       last_modified_at = pg_temp.at(o.d + 2, '13:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_packed o ON o.key = c.key WHERE p.id = c.shipping_package_id;

-- The first label locks the order's cancellation and address (OrderService.recordFirstLabel).
UPDATE ordering.customer_order co
   SET first_labeled_at = pg_temp.at(o.d + 2, '14:30'), version = 9, last_modified_at = pg_temp.at(o.d + 2, '14:30'),
       last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_packed o WHERE co.id = o.id;

-- Labelled from the SKU's carton spec (08 BR-07, BR-09); volumetric weight L*W*H/6000.
UPDATE fulfillment.package p
   SET status = 'LABELED', weight_kg = c.carton_kg, length_cm = c.length_cm, width_cm = c.width_cm,
       height_cm = c.height_cm, dim_weight_kg = round(c.length_cm * c.width_cm * c.height_cm / 6000, 2),
       labeled_at = pg_temp.at(o.d + 2, '14:30'), labeled_by = pg_temp.staff(o.kho), label_version = 1, version = 2,
       last_modified_at = pg_temp.at(o.d + 2, '14:30'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_packed o ON o.key = c.key WHERE p.id = c.shipping_package_id;

INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, version, created_at, created_by, code, warehouse_id,
                                  package_count, partial, ready_at, ready_by, whole_order_handover)
SELECT pg_temp.sid('shipment:' || o.key), pg_temp.sid('pack:' || o.key), o.id, 'READY_TO_DISPATCH', 0,
       pg_temp.at(o.d + 2, '15:00'), pg_temp.staff(o.kho)::text, o.order_number || '-S1', pg_temp.sid('wh:' || o.wh),
       o.cartons, FALSE, pg_temp.at(o.d + 2, '15:00'), pg_temp.staff(o.kho), FALSE
  FROM h_packed o;

UPDATE fulfillment.package p
   SET shipment_id = pg_temp.sid('shipment:' || o.key), version = 3
  FROM h_carton c JOIN h_packed o ON o.key = c.key WHERE p.id = c.shipping_package_id;

UPDATE fulfillment.pack k
   SET status = 'COMPLETED', packed_at = pg_temp.at(o.d + 2, '15:00'), packed_by = pg_temp.staff(o.kho),
       version = 2, last_modified_at = pg_temp.at(o.d + 2, '15:00'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_packed o WHERE k.id = pg_temp.sid('pack:' || o.key);

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at, created_by,
                                        recorded_by)
SELECT pg_temp.sid('shipment-event:' || o.key || ':0'), pg_temp.sid('shipment:' || o.key), 'READY_TO_DISPATCH',
       pg_temp.at(o.d + 2, '15:00'), 'MANUAL', 0, pg_temp.at(o.d + 2, '15:00'), pg_temp.staff(o.kho)::text,
       pg_temp.staff(o.kho)
  FROM h_packed o;

UPDATE ordering.customer_order co
   SET status = 'PACKED', packed_at = pg_temp.at(o.d + 2, '15:00'), version = 10,
       last_modified_at = pg_temp.at(o.d + 2, '15:00'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_packed o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':7'), o.id, 'PICKING', 'PACKED', NULL, pg_temp.staff(o.kho),
       pg_temp.at(o.d + 2, '15:00'), pg_temp.at(o.d + 2, '15:00'), pg_temp.staff(o.kho)::text
  FROM h_packed o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 19. A full truckload (09, LP): the load plan of the shipment on a 2.5 t truck, calculated and confirmed the day
--     before; the truck booked with its waybill; loaded by scanning every carton; handed over at 08:00 and delivered
--     the same afternoon. The cartons stand in slots four across, three high, row after row.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE h_shipped ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 14;

INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, source_type, selection_mode, status,
                                   calculated_at, calculated_by, solver_meta, confirmed_at, confirmed_by, revised_at,
                                   version, created_at, created_by)
SELECT pg_temp.sid('load-plan:' || o.key), 'LP-' || pg_temp.ymd(o.d + 1) || '-' || (7000 + o.seq),
       pg_temp.sid('load-plan-request:' || o.key), pg_temp.sid('wh:' || o.wh), 'ORDER', 'SINGLE_TYPE', 'CONFIRMED',
       pg_temp.at(o.d + 1, '10:05'), pg_temp.staff(o.mgr),
       '{"algorithm": "GRASP_KIT", "setsTotal": 0, "kitWarnings": [], "minVehicles": 1, "budgetExhausted": false}'::jsonb,
       pg_temp.at(o.d + 1, '10:10'), pg_temp.staff(o.mgr), pg_temp.at(o.d + 1, '10:10'), 2,
       pg_temp.at(o.d + 1, '10:00'), pg_temp.staff(o.mgr)::text
  FROM h_shipped o;

INSERT INTO fulfillment.load_plan_vehicle_type (id, load_plan_id, vehicle_type_id, sort_order, version, created_at,
                                                created_by)
SELECT pg_temp.sid('load-plan-type:' || o.key), pg_temp.sid('load-plan:' || o.key), t.id, 0, 0,
       pg_temp.at(o.d + 1, '10:00'), pg_temp.staff(o.mgr)::text
  FROM h_shipped o JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5';

INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, shipment_id, active, added_at, added_by,
                                           version, created_at, created_by)
SELECT pg_temp.sid('load-plan-package:' || o.key || ':' || c.n), pg_temp.sid('load-plan:' || o.key),
       c.shipping_package_id, pg_temp.sid('shipment:' || o.key), TRUE, pg_temp.at(o.d + 1, '10:00'),
       pg_temp.staff(o.mgr), 0, pg_temp.at(o.d + 1, '10:00'), pg_temp.staff(o.mgr)::text
  FROM h_carton c JOIN h_shipped o ON o.key = c.key;

INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, active, vehicle_type_id, vehicle_code, vehicle_kind,
                                           inner_length_mm, inner_width_mm, inner_height_mm, max_payload_kg, gusset_mm,
                                           used_volume_mm3, used_weight_kg, volume_fill_pct, weight_fill_pct,
                                           needs_recalculation, version, created_at, created_by)
SELECT pg_temp.sid('load-plan-vehicle:' || o.key), pg_temp.sid('load-plan:' || o.key), 1, TRUE, t.id, t.code, t.kind,
       t.inner_length_mm, t.inner_width_mm, t.inner_height_mm, t.max_payload_kg, t.gusset_mm, x.volume, x.weight,
       round(x.volume * 100.0 / (t.inner_length_mm::numeric * t.inner_width_mm * t.inner_height_mm), 2),
       round(x.weight * 100 / t.max_payload_kg, 2), FALSE, 0, pg_temp.at(o.d + 1, '10:05'), pg_temp.staff(o.mgr)::text
  FROM h_shipped o
  JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5'
  JOIN (SELECT key, sum((length_cm * width_cm * height_cm * 1000)::bigint) AS volume, sum(carton_kg) AS weight
          FROM h_carton GROUP BY key) x ON x.key = o.key;

INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm,
                                        h_mm, d_mm, orientation, version, created_at, created_by)
SELECT pg_temp.sid('load-placement:' || o.key || ':' || c.n), pg_temp.sid('load-plan:' || o.key),
       pg_temp.sid('load-plan-vehicle:' || o.key), c.shipping_package_id, ((c.n - 1) % 4) * 450,
       (((c.n - 1) / 4) % 3) * 600, ((c.n - 1) / 12) * 600, (c.width_cm * 10)::int, (c.height_cm * 10)::int,
       (c.length_cm * 10)::int, 0, 0, pg_temp.at(o.d + 1, '10:05'), pg_temp.staff(o.mgr)::text
  FROM h_carton c JOIN h_shipped o ON o.key = c.key;

-- The plan confirmed makes the truck's load (PLANNED), with the shipment on it.
INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, load_plan_vehicle_id, status,
                                       shipment_count, package_count, version, created_at, created_by)
SELECT pg_temp.sid('load:' || o.key), 'LD-' || pg_temp.ymd(o.d + 1) || '-' || (7000 + o.seq),
       pg_temp.sid('load-request:' || o.key), pg_temp.sid('wh:' || o.wh), pg_temp.sid('load-plan-vehicle:' || o.key),
       'PLANNED', 0, 0, 0, pg_temp.at(o.d + 1, '10:10'), pg_temp.staff(o.mgr)::text
  FROM h_shipped o;

INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, active, added_at, added_by, version,
                                                created_at, created_by)
SELECT pg_temp.sid('load-shipment:' || o.key), pg_temp.sid('load:' || o.key), pg_temp.sid('shipment:' || o.key), TRUE,
       pg_temp.at(o.d + 1, '10:10'), pg_temp.staff(o.mgr), 0, pg_temp.at(o.d + 1, '10:10'), pg_temp.staff(o.mgr)::text
  FROM h_shipped o;

UPDATE fulfillment.dispatch_load l
   SET status = 'VEHICLE_BOOKED', carrier_id = pg_temp.sid('carrier:' || o.carrier),
       vehicle_plate = CASE WHEN o.wh = 'HNI' THEN '29C-' ELSE '61C-' END || (10000 + o.seq * 97 % 89999),
       driver_name = (ARRAY['Lý Văn Tài', 'Nguyễn Văn Xế', 'Trần Minh Lái', 'Phạm Văn Đường'])[1 + o.seq % 4],
       driver_phone = '0909' || lpad((100000 + o.seq * 313)::text, 6, '0'),
       waybill_no = left(o.carrier, 5) || '-' || pg_temp.ymd(o.d + 1) || '-' || o.seq,
       booked_at = pg_temp.at(o.d + 1, '15:00'), booked_by = pg_temp.staff(o.mgr), version = 1,
       last_modified_at = pg_temp.at(o.d + 1, '15:00'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_shipped o WHERE l.id = pg_temp.sid('load:' || o.key);

INSERT INTO fulfillment.waybill_change (id, load_id, new_waybill_no, source, changed_by, changed_at, version,
                                        created_at, created_by)
SELECT pg_temp.sid('waybill:' || o.key), pg_temp.sid('load:' || o.key),
       left(o.carrier, 5) || '-' || pg_temp.ymd(o.d + 1) || '-' || o.seq, 'MANUAL', pg_temp.staff(o.mgr),
       pg_temp.at(o.d + 1, '15:00'), 0, pg_temp.at(o.d + 1, '15:00'), pg_temp.staff(o.mgr)::text
  FROM h_shipped o;

UPDATE fulfillment.dispatch_load l
   SET status = 'LOADING', loading_started_at = pg_temp.at(o.d, '07:00'), loading_started_by = pg_temp.staff(o.kho),
       version = 2, last_modified_at = pg_temp.at(o.d, '07:00'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM h_shipped o WHERE l.id = pg_temp.sid('load:' || o.key);

INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by,
                                               active, version, created_at, created_by)
SELECT pg_temp.sid('load-package:' || o.key || ':' || c.n), pg_temp.sid('load:' || o.key), c.shipping_package_id,
       pg_temp.sid('shipment:' || o.key), pg_temp.sid('scan:' || o.key || ':' || c.n),
       pg_temp.at(o.d, '07:00') + (c.n || ' seconds')::interval * 30, pg_temp.staff(o.kho), TRUE, 0,
       pg_temp.at(o.d, '07:00') + (c.n || ' seconds')::interval * 30, pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN h_shipped o ON o.key = c.key;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Handed over at 08:00 (09 §4.2 step 8): the load leaves, the shipment and the order are shipped.
UPDATE fulfillment.dispatch_load l
   SET status = 'DISPATCHED', dispatched_at = pg_temp.at(o.d, '08:00'), dispatched_by = pg_temp.staff(o.mgr),
       carrier_representative = l.driver_name, shipment_count = 1, package_count = o.cartons, version = 3,
       last_modified_at = pg_temp.at(o.d, '08:00'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_shipped o WHERE l.id = pg_temp.sid('load:' || o.key);

UPDATE fulfillment.load_plan p
   SET status = 'DISPATCHED', dispatched_at = pg_temp.at(o.d, '08:00'), revised_at = pg_temp.at(o.d, '08:00'),
       version = 3, last_modified_at = pg_temp.at(o.d, '08:00'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_shipped o WHERE p.id = pg_temp.sid('load-plan:' || o.key);

UPDATE fulfillment.shipment s
   SET status = 'HANDED_OVER', handed_over_at = pg_temp.at(o.d, '08:00'), handed_over_by = pg_temp.staff(o.mgr),
       carrier_id = pg_temp.sid('carrier:' || o.carrier), load_id = pg_temp.sid('load:' || o.key), version = 1,
       last_modified_at = pg_temp.at(o.d, '08:00'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_shipped o WHERE s.id = pg_temp.sid('shipment:' || o.key);

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at, created_by,
                                        recorded_by)
SELECT pg_temp.sid('shipment-event:' || o.key || ':1'), pg_temp.sid('shipment:' || o.key), 'HANDED_OVER',
       pg_temp.at(o.d, '08:00'), 'MANUAL', 0, pg_temp.at(o.d, '08:00'), pg_temp.staff(o.mgr)::text, pg_temp.staff(o.mgr)
  FROM h_shipped o;

INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, handed_over_by, version, created_at,
                                     created_by, shipment_code, package_count, carrier_name, delivery_batch_id)
SELECT pg_temp.sid('shipment:' || o.key), o.id, 'HANDED_OVER', o.total, pg_temp.at(o.d, '08:00'),
       pg_temp.staff(o.mgr), 0, pg_temp.at(o.d, '08:00'), pg_temp.staff(o.mgr)::text, o.order_number || '-S1',
       o.cartons, k.name, o.batch_id
  FROM h_shipped o JOIN fulfillment.carrier k ON k.code = o.carrier;

INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity)
SELECT pg_temp.sid('shipment:' || o.key), l.line_id, l.cartons * l.upc
  FROM h_line l JOIN h_shipped o ON o.key = l.key;

UPDATE ordering.customer_order co
   SET status = 'SHIPPED', shipped_at = pg_temp.at(o.d, '08:00'), goods_left_at = pg_temp.at(o.d, '08:00'),
       version = 11, last_modified_at = pg_temp.at(o.d, '08:00'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_shipped o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':8'), o.id, 'PACKED', 'SHIPPED', NULL, pg_temp.staff(o.mgr),
       pg_temp.at(o.d, '08:00'), pg_temp.at(o.d, '08:00'), pg_temp.staff(o.mgr)::text
  FROM h_shipped o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Delivered at 15:00 (09 §4.3): what the order pays after delivery is due from that day, its days to pay counted.
CREATE TEMP TABLE h_delivered ON COMMIT DROP AS SELECT * FROM h_order WHERE st >= 15;

UPDATE fulfillment.shipment s
   SET status = 'DELIVERED', outcome_at = pg_temp.at(o.d, '15:00'), outcome_by = pg_temp.staff(o.mgr),
       version = 2, last_modified_at = pg_temp.at(o.d, '15:00'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_delivered o WHERE s.id = pg_temp.sid('shipment:' || o.key);

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at, created_by,
                                        recorded_by)
SELECT pg_temp.sid('shipment-event:' || o.key || ':2'), pg_temp.sid('shipment:' || o.key), 'DELIVERED',
       pg_temp.at(o.d, '15:00'), 'MANUAL', 0, pg_temp.at(o.d, '15:00'), pg_temp.staff(o.mgr)::text, pg_temp.staff(o.mgr)
  FROM h_delivered o;

UPDATE ordering.order_shipment s
   SET status = 'DELIVERED', outcome_at = pg_temp.at(o.d, '15:00'), outcome_by = pg_temp.staff(o.mgr),
       version = 1, last_modified_at = pg_temp.at(o.d, '15:00'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_delivered o WHERE s.id = pg_temp.sid('shipment:' || o.key);

UPDATE ordering.customer_order co
   SET status = 'DELIVERED', delivered_at = pg_temp.at(o.d, '15:00'), version = 12,
       last_modified_at = pg_temp.at(o.d, '15:00'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM h_delivered o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('order-history:' || o.key || ':9'), o.id, 'SHIPPED', 'DELIVERED', NULL, pg_temp.staff(o.mgr),
       pg_temp.at(o.d, '15:00'), pg_temp.at(o.d, '15:00'), pg_temp.staff(o.mgr)::text
  FROM h_delivered o;

UPDATE payment.installment i
   SET reached_at = pg_temp.at(o.d, '15:00'), due_date = pg_temp.day(o.d) + o.term_days, status = 'DUE', version = 1,
       last_modified_at = pg_temp.at(o.d, '15:00'), last_modified_by = 'system'
  FROM h_delivered o WHERE i.order_id = o.id AND i.milestone = 'AFTER_DELIVERY';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 20. Payments after delivery (15 §4.2): on the days the transfers came in; the rest stays owed and the receivables
--     report ages it.
-- ---------------------------------------------------------------------------------------------
INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, note, status,
                                     recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.sid('receipt:' || o.key || ':2'), o.customer_id,
       'FT' || pg_temp.ymd(o.paid_after) || (70000 + o.seq * 10 + 2), pg_temp.day(o.paid_after),
       round(o.rest * o.paid_part, 0), 'VND',
       'Thanh toán sau giao hàng hợp đồng ' || o.contract_number || CASE WHEN o.paid_part < 1 THEN ' (một phần)' ELSE '' END,
       'RECORDED', pg_temp.staff('ketoan'), pg_temp.at(o.paid_after, '10:30'), 0, pg_temp.at(o.paid_after, '10:30'),
       pg_temp.staff('ketoan')::text
  FROM h_delivered o WHERE o.paid_after IS NOT NULL;

INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount, created_at)
SELECT pg_temp.sid('receipt-line:' || o.key || ':2'), pg_temp.sid('receipt:' || o.key || ':2'),
       pg_temp.sid('installment:' || o.key || ':2'), round(o.rest * o.paid_part, 0), pg_temp.at(o.paid_after, '10:30')
  FROM h_delivered o WHERE o.paid_after IS NOT NULL;

UPDATE payment.installment i
   SET paid_amount = round(o.rest * o.paid_part, 0),
       status = CASE WHEN o.paid_part = 1 THEN 'PAID' ELSE 'PARTIALLY_PAID' END,
       paid_at = CASE WHEN o.paid_part = 1 THEN pg_temp.at(o.paid_after, '10:30') END, version = 2,
       last_modified_at = pg_temp.at(o.paid_after, '10:30'), last_modified_by = pg_temp.staff('ketoan')::text
  FROM h_delivered o WHERE i.id = pg_temp.sid('installment:' || o.key || ':2') AND o.paid_after IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- The session's helpers go with it.
-- ---------------------------------------------------------------------------------------------
DROP FUNCTION pg_temp.staff(TEXT);
DROP FUNCTION pg_temp.pw();
DROP FUNCTION pg_temp.ymd(INTEGER);
DROP FUNCTION pg_temp.day(INTEGER);
DROP FUNCTION pg_temp.at(INTEGER, TEXT);
DROP FUNCTION pg_temp.today();
DROP FUNCTION pg_temp.pick(TEXT, INTEGER, INTEGER);
DROP FUNCTION pg_temp.rnd(TEXT);
DROP FUNCTION pg_temp.sid(TEXT);

COMMIT;
