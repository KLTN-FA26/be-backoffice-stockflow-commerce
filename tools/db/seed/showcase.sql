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
--   * sections 10-90 (2026-11-24), on top of that chain: the product dictionary, the catalog projection and the
--     customers' design studio (10); holds, cancellation requests, address and window changes, print sample rounds,
--     delay alerts, segments, customer and account history, portal invitations (20); cycle counts, count variances
--     and adjustments, putaway trips (30); Ahamove bookings, carrier callbacks, waybill imports, load estimates, an
--     unloaded truck (40); delivery-outcome imports, corrections, proof-of-delivery files, a returned shipment (50);
--     return requests in every status with their return receipts, settlements, refunds and an installment
--     reduction (60); a VAT e-invoice per delivery through the sandbox provider, with a replacement, an error notice,
--     a discount and the return adjustments (70); chat, in-app notifications, the mail log, the audit log (80); and
--     the number counters (90).
-- Every size and weight is the carton's (inventory.uom_conversions): an item carries none since V20261215000100, and
-- stock is whole LPN cartons only.
-- What it leaves out: files (every storage key it names is a placeholder; no object exists for it), events
-- (event_publication stays empty, so nothing is mailed when it loads), the sales rollups (the application rebuilds
-- them on its next start: SalesRollupBackfill), and the tables nothing writes any more or that only the running
-- application fills: identity.user_session, platform.idempotency_record, platform.shedlock, platform.legacy_archive
-- (rows of dropped tables), fulfillment.wave, warehouse.move_task, warehouse.slotting_rule (no code writes them) and
-- ordering.delivery_failure_case_hold (only a case waiting for redelivery has holds, and since 2026-11-19 no code
-- path leaves one).
--
-- Every trigger is on and the steps run in the order the application writes them, so every CHECK and trigger judges
-- these rows as it judges the application's. Ids are md5('showcase:...')::uuid and every "random" choice is a hash of
-- its key, so loading it twice on the same day gives the same rows. Staff and customer passwords are the published
-- demo one (Stockflow@2026); README.md says how to keep the old ones on a server being reset.
-- =============================================================================
\set ON_ERROR_STOP on
-- The deployment's database caps a statement at 10 s and a lock wait at 5 s, for the application's requests; a seed's
-- bulk statements run longer. Lifted for this session only.
SET statement_timeout = 0;
SET lock_timeout = 0;
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
   SET rotatable = k.rotatable
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

-- >>> sections 10-90 (spliced) >>>
-- ---------------------------------------------------------------------------------------------
-- dpc. The product dictionary (brands, attributes, the five products' axes and descriptions, their history), the
--      storefront listing as the catalogue projection writes it, and the customers' design projects: libraries,
--      review rounds, quote requests, and the drafts in progress in the studio (12 §4.9-§4.11, 01 §4, 13).
--      Changes no order and no stock. Every id key starts with 'dpc:'.
-- ---------------------------------------------------------------------------------------------

-- A layout as CustomerDesignService stores it: canonical JSON (RFC 8785) - keys in code-point order, no white space.
-- Only integers and short decimals are put in, which jsonb prints as ECMAScript does.
CREATE FUNCTION pg_temp.dpc_canon(j JSONB) RETURNS TEXT LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF jsonb_typeof(j) = 'object' THEN
        RETURN '{' || coalesce((SELECT string_agg(to_json(e.k)::text || ':' || pg_temp.dpc_canon(e.v), ','
                                                  ORDER BY e.k COLLATE "C")
                                  FROM jsonb_each(j) AS e (k, v)), '') || '}';
    ELSIF jsonb_typeof(j) = 'array' THEN
        RETURN '[' || coalesce((SELECT string_agg(pg_temp.dpc_canon(a.v), ',' ORDER BY a.n)
                                  FROM jsonb_array_elements(j) WITH ORDINALITY AS a (v, n)), '') || ']';
    END IF;
    RETURN j::text;
END $$;

CREATE FUNCTION pg_temp.dpc_hash(key TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT encode(sha256(convert_to('showcase:dpc:' || key, 'UTF8')), 'hex') $$;

-- ---------------------------------------------------------------------------------------------
-- dpc.1 Brands and the attribute dictionary, kept by the Sales Manager the day before the products were drawn up
-- ---------------------------------------------------------------------------------------------
INSERT INTO product.brands (id, code, name, slug, logo_url, is_active, version, created_at, created_by,
                            last_modified_at, last_modified_by)
VALUES (pg_temp.sid('dpc:brand:SF-PACK'), 'SF-PACK', 'StockFlow Pack', 'stockflow-pack', NULL, TRUE, 0,
        pg_temp.at(229, '14:00'), pg_temp.staff('truongkd')::text, NULL, NULL),
       (pg_temp.sid('dpc:brand:KRAFT-XANH'), 'KRAFT-XANH', 'Kraft Xanh', 'kraft-xanh', NULL, TRUE, 0,
        pg_temp.at(229, '14:02'), pg_temp.staff('truongkd')::text, NULL, NULL),
       -- A supplier brand from before the business printed to order; switched off, kept for the history.
       (pg_temp.sid('dpc:brand:ECOCUP'), 'ECOCUP', 'EcoCup', 'ecocup', NULL, FALSE, 1, pg_temp.at(229, '14:04'),
        pg_temp.staff('truongkd')::text, pg_temp.at(229, '14:30'), pg_temp.staff('truongkd')::text);

CREATE TEMP TABLE x_dpc_attr (code TEXT PRIMARY KEY, name TEXT, data_type TEXT, unit TEXT, filterable BOOLEAN,
                              searchable BOOLEAN, sort INTEGER) ON COMMIT DROP;
INSERT INTO x_dpc_attr VALUES
    ('DUNG_TICH', 'Dung tích', 'SELECT', NULL, TRUE, TRUE, 10),
    ('KICH_THUOC', 'Kích thước', 'SELECT', NULL, TRUE, TRUE, 20),
    ('CHAT_LIEU', 'Chất liệu', 'SELECT', NULL, TRUE, TRUE, 30),
    ('KIEU_THANH', 'Kiểu thành', 'SELECT', NULL, TRUE, FALSE, 40),
    ('MAU_NEN', 'Màu nền', 'COLOR', NULL, TRUE, FALSE, 50),
    ('DINH_LUONG_GIAY', 'Định lượng giấy', 'NUMBER', 'gsm', FALSE, FALSE, 60),
    ('DUONG_KINH_MIENG', 'Đường kính miệng', 'NUMBER', 'mm', FALSE, FALSE, 70),
    ('SO_MAU_IN_TOI_DA', 'Số màu in tối đa', 'NUMBER', 'màu', TRUE, FALSE, 80),
    ('DUNG_DO_NONG', 'Dùng được đồ nóng', 'BOOLEAN', NULL, TRUE, FALSE, 90),
    ('CHUNG_NHAN', 'Chứng nhận an toàn thực phẩm', 'MULTISELECT', NULL, TRUE, FALSE, 100),
    ('QUY_CACH_DONG_GOI', 'Quy cách đóng gói', 'TEXT', NULL, FALSE, TRUE, 110);

CREATE TEMP TABLE x_dpc_val (attr TEXT, code TEXT, label TEXT, num NUMERIC, hex TEXT, sort INTEGER) ON COMMIT DROP;
INSERT INTO x_dpc_val VALUES
    ('DUNG_TICH', '8OZ', '8 oz', 240, NULL, 1), ('DUNG_TICH', '12OZ', '12 oz', 360, NULL, 2),
    ('DUNG_TICH', '16OZ', '16 oz', 480, NULL, 3), ('DUNG_TICH', '22OZ', '22 oz', 650, NULL, 4),
    ('DUNG_TICH', '500ML', '500 ml', 500, NULL, 5), ('DUNG_TICH', '750ML', '750 ml', 750, NULL, 6),
    ('DUNG_TICH', '1000ML', '1000 ml', 1000, NULL, 7),
    ('KICH_THUOC', 'S', 'cỡ S', NULL, NULL, 1), ('KICH_THUOC', 'M', 'cỡ M', NULL, NULL, 2),
    ('KICH_THUOC', 'L', 'cỡ L', NULL, NULL, 3),
    ('CHAT_LIEU', 'GIAY_IVORY_PE', 'Giấy ivory tráng PE', NULL, NULL, 1),
    ('CHAT_LIEU', 'NHUA_PET', 'Nhựa PET', NULL, NULL, 2),
    ('CHAT_LIEU', 'GIAY_KRAFT', 'Giấy kraft nâu', NULL, NULL, 3),
    ('CHAT_LIEU', 'GIAY_KRAFT_TRANG', 'Giấy kraft trắng', NULL, NULL, 4),
    ('CHAT_LIEU', 'GIAY_XI_MANG', 'Giấy xi măng', NULL, NULL, 5),
    ('KIEU_THANH', '1_LOP', '1 lớp', NULL, NULL, 1), ('KIEU_THANH', '2_LOP', '2 lớp', NULL, NULL, 2),
    ('KIEU_THANH', 'GON_SONG', 'Gợn sóng cách nhiệt', NULL, NULL, 3),
    ('MAU_NEN', 'TRANG', 'Trắng', NULL, '#FFFFFF', 1), ('MAU_NEN', 'TRONG_SUOT', 'Trong suốt', NULL, '#EEF4F7', 2),
    ('MAU_NEN', 'NAU_KRAFT', 'Nâu kraft', NULL, '#B5865A', 3),
    ('CHUNG_NHAN', 'FDA', 'FDA (Hoa Kỳ)', NULL, NULL, 1), ('CHUNG_NHAN', 'ISO22000', 'ISO 22000', NULL, NULL, 2),
    ('CHUNG_NHAN', 'HACCP', 'HACCP', NULL, NULL, 3), ('CHUNG_NHAN', 'QCVN12', 'QCVN 12-1:2011/BYT', NULL, NULL, 4);

INSERT INTO product.attributes (id, code, name, data_type, unit, is_filterable, is_searchable, sort_order, is_active,
                                version, created_at, created_by)
SELECT pg_temp.sid('dpc:attribute:' || code), code, name, data_type, unit, filterable, searchable, sort, TRUE, 0,
       pg_temp.at(229, '14:10') + sort * interval '1 second', pg_temp.staff('truongkd')::text
  FROM x_dpc_attr;

INSERT INTO product.attribute_values (id, attribute_id, code, label, value_number, hex_color, sort_order, is_active,
                                      version, created_at, created_by)
SELECT pg_temp.sid('dpc:attribute-value:' || v.attr || ':' || v.code), pg_temp.sid('dpc:attribute:' || v.attr), v.code,
       v.label, v.num, v.hex, v.sort, TRUE, 0, pg_temp.at(229, '14:10') + a.sort * interval '1 second',
       pg_temp.staff('truongkd')::text
  FROM x_dpc_val v JOIN x_dpc_attr a ON a.code = v.attr;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- What each category's products are expected to carry: it pre-fills the product form, nothing more (spec D9).
CREATE TEMP TABLE x_dpc_cat_attr (category TEXT, attr TEXT, role TEXT, required BOOLEAN, sort INTEGER) ON COMMIT DROP;
INSERT INTO x_dpc_cat_attr VALUES
    ('LY_GIAY', 'DUNG_TICH', 'VARIANT_AXIS', TRUE, 0), ('LY_GIAY', 'CHAT_LIEU', 'DESCRIPTIVE', TRUE, 1),
    ('LY_GIAY', 'KIEU_THANH', 'DESCRIPTIVE', TRUE, 2), ('LY_GIAY', 'MAU_NEN', 'DESCRIPTIVE', FALSE, 3),
    ('LY_GIAY', 'DINH_LUONG_GIAY', 'DESCRIPTIVE', FALSE, 4), ('LY_GIAY', 'DUONG_KINH_MIENG', 'DESCRIPTIVE', FALSE, 5),
    ('LY_GIAY', 'SO_MAU_IN_TOI_DA', 'DESCRIPTIVE', FALSE, 6), ('LY_GIAY', 'DUNG_DO_NONG', 'DESCRIPTIVE', FALSE, 7),
    ('LY_GIAY', 'CHUNG_NHAN', 'DESCRIPTIVE', FALSE, 8), ('LY_GIAY', 'QUY_CACH_DONG_GOI', 'DESCRIPTIVE', FALSE, 9),
    ('LY_NHUA', 'DUNG_TICH', 'VARIANT_AXIS', TRUE, 0), ('LY_NHUA', 'CHAT_LIEU', 'DESCRIPTIVE', TRUE, 1),
    ('LY_NHUA', 'KIEU_THANH', 'DESCRIPTIVE', FALSE, 2), ('LY_NHUA', 'MAU_NEN', 'DESCRIPTIVE', FALSE, 3),
    ('LY_NHUA', 'DUONG_KINH_MIENG', 'DESCRIPTIVE', FALSE, 4), ('LY_NHUA', 'SO_MAU_IN_TOI_DA', 'DESCRIPTIVE', FALSE, 5),
    ('LY_NHUA', 'DUNG_DO_NONG', 'DESCRIPTIVE', FALSE, 6), ('LY_NHUA', 'CHUNG_NHAN', 'DESCRIPTIVE', FALSE, 7),
    ('LY_NHUA', 'QUY_CACH_DONG_GOI', 'DESCRIPTIVE', FALSE, 8),
    ('TO_HOP', 'DUNG_TICH', 'VARIANT_AXIS', FALSE, 0), ('TO_HOP', 'KICH_THUOC', 'VARIANT_AXIS', FALSE, 1),
    ('TO_HOP', 'CHAT_LIEU', 'DESCRIPTIVE', TRUE, 2), ('TO_HOP', 'MAU_NEN', 'DESCRIPTIVE', FALSE, 3),
    ('TO_HOP', 'DINH_LUONG_GIAY', 'DESCRIPTIVE', FALSE, 4), ('TO_HOP', 'SO_MAU_IN_TOI_DA', 'DESCRIPTIVE', FALSE, 5),
    ('TO_HOP', 'DUNG_DO_NONG', 'DESCRIPTIVE', FALSE, 6), ('TO_HOP', 'CHUNG_NHAN', 'DESCRIPTIVE', FALSE, 7),
    ('TO_HOP', 'QUY_CACH_DONG_GOI', 'DESCRIPTIVE', FALSE, 8),
    ('TUI_GIAY', 'KICH_THUOC', 'VARIANT_AXIS', TRUE, 0), ('TUI_GIAY', 'CHAT_LIEU', 'DESCRIPTIVE', TRUE, 1),
    ('TUI_GIAY', 'MAU_NEN', 'DESCRIPTIVE', FALSE, 2), ('TUI_GIAY', 'DINH_LUONG_GIAY', 'DESCRIPTIVE', FALSE, 3),
    ('TUI_GIAY', 'SO_MAU_IN_TOI_DA', 'DESCRIPTIVE', FALSE, 4), ('TUI_GIAY', 'QUY_CACH_DONG_GOI', 'DESCRIPTIVE', FALSE, 5);

INSERT INTO product.category_attributes (id, category_id, attribute_id, default_role, is_required, sort_order, version,
                                         created_at, created_by)
SELECT pg_temp.sid('dpc:category-attribute:' || category || ':' || attr), pg_temp.sid('category:' || category),
       pg_temp.sid('dpc:attribute:' || attr), role, required, sort, 0, pg_temp.at(229, '15:00'),
       pg_temp.staff('truongkd')::text
  FROM x_dpc_cat_attr;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- dpc.2 The five products' brand, description and attributes (01 §4). The brand and the description were part of the
--       product as it was drawn up (a product cannot be submitted without a brand: ck_products_brand_on_submit), so
--       they are set here without a new version or an UPDATED entry. Each product was born with one axis - the
--       base's signature DUNG_TICH=12OZ / KICH_THUOC=M - and its one option, which its blank variant carries.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_dpc_product (code TEXT PRIMARY KEY, brand TEXT, axis TEXT, axis_value TEXT, description TEXT)
    ON COMMIT DROP;
INSERT INTO x_dpc_product VALUES
    ('LY-GIAY', 'SF-PACK', 'DUNG_TICH', '12OZ',
     'Ly giấy hai lớp giấy ivory tráng PE, giữ nhiệt tốt, không cần ống lót. In logo và hoa văn theo file thiết kế '
     || 'của khách, tối đa 4 màu Pantone quanh thân ly. Dùng cho cà phê, trà nóng và đồ uống mang đi.'),
    ('LY-NHUA', 'SF-PACK', 'DUNG_TICH', '16OZ',
     'Ly nhựa PET trong suốt, thành dày, miệng 95 mm vừa nắp cầu và nắp dẹt. In logo theo thiết kế của khách, '
     || 'tối đa 4 màu. Dùng cho trà sữa, nước ép và đồ uống lạnh.'),
    ('TO-GIAY', 'KRAFT-XANH', 'DUNG_TICH', '750ML',
     'Tô giấy kraft nâu 750 ml tráng PE chống thấm, dùng được cho món nóng và món có nước. In logo 1 đến 2 màu '
     || 'quanh thân tô theo thiết kế của khách.'),
    ('HOP-BANH', 'KRAFT-XANH', 'KICH_THUOC', 'M',
     'Hộp giấy kraft trắng cỡ M đựng bánh ngọt, bánh mì và đồ ăn nhẹ, giao dạng phẳng, gấp không cần keo. '
     || 'In logo mặt trước theo thiết kế của khách, tối đa 4 màu.'),
    ('TUI-GIAY', 'KRAFT-XANH', 'KICH_THUOC', 'L',
     'Túi giấy xi măng cỡ L quai giấy xoắn, đáy vuông đứng được, chịu tải khoảng 5 kg. In logo mặt trước '
     || '1 đến 2 màu theo thiết kế của khách.');

UPDATE product.products p
   SET brand_id = pg_temp.sid('dpc:brand:' || x.brand), description = x.description
  FROM x_dpc_product x
 WHERE p.id = pg_temp.sid('product:' || x.code);

-- Which descriptive attribute each product carries, in its order, and its value.
CREATE TEMP TABLE x_dpc_desc (product TEXT, attr TEXT, sort INTEGER, value_code TEXT, text_value TEXT,
                              num_value NUMERIC, bool_value BOOLEAN) ON COMMIT DROP;
INSERT INTO x_dpc_desc VALUES
    ('LY-GIAY', 'CHAT_LIEU', 1, 'GIAY_IVORY_PE', NULL, NULL, NULL),
    ('LY-GIAY', 'KIEU_THANH', 2, '2_LOP', NULL, NULL, NULL),
    ('LY-GIAY', 'MAU_NEN', 3, 'TRANG', NULL, NULL, NULL),
    ('LY-GIAY', 'DINH_LUONG_GIAY', 4, NULL, NULL, 280, NULL),
    ('LY-GIAY', 'DUONG_KINH_MIENG', 5, NULL, NULL, 90, NULL),
    ('LY-GIAY', 'SO_MAU_IN_TOI_DA', 6, NULL, NULL, 4, NULL),
    ('LY-GIAY', 'DUNG_DO_NONG', 7, NULL, NULL, NULL, TRUE),
    ('LY-GIAY', 'CHUNG_NHAN', 8, 'FDA', NULL, NULL, NULL),
    ('LY-GIAY', 'CHUNG_NHAN', 8, 'ISO22000', NULL, NULL, NULL),
    ('LY-GIAY', 'QUY_CACH_DONG_GOI', 9, NULL, '1.000 ly/thùng (20 ống x 50 ly)', NULL, NULL),
    ('LY-NHUA', 'CHAT_LIEU', 1, 'NHUA_PET', NULL, NULL, NULL),
    ('LY-NHUA', 'KIEU_THANH', 2, '1_LOP', NULL, NULL, NULL),
    ('LY-NHUA', 'MAU_NEN', 3, 'TRONG_SUOT', NULL, NULL, NULL),
    ('LY-NHUA', 'DUONG_KINH_MIENG', 4, NULL, NULL, 95, NULL),
    ('LY-NHUA', 'SO_MAU_IN_TOI_DA', 5, NULL, NULL, 4, NULL),
    ('LY-NHUA', 'DUNG_DO_NONG', 6, NULL, NULL, NULL, FALSE),
    ('LY-NHUA', 'CHUNG_NHAN', 7, 'FDA', NULL, NULL, NULL),
    ('LY-NHUA', 'CHUNG_NHAN', 7, 'QCVN12', NULL, NULL, NULL),
    ('LY-NHUA', 'QUY_CACH_DONG_GOI', 8, NULL, '1.000 ly/thùng (20 ống x 50 ly)', NULL, NULL),
    ('TO-GIAY', 'CHAT_LIEU', 1, 'GIAY_KRAFT', NULL, NULL, NULL),
    ('TO-GIAY', 'MAU_NEN', 2, 'NAU_KRAFT', NULL, NULL, NULL),
    ('TO-GIAY', 'DINH_LUONG_GIAY', 3, NULL, NULL, 300, NULL),
    ('TO-GIAY', 'DUONG_KINH_MIENG', 4, NULL, NULL, 150, NULL),
    ('TO-GIAY', 'SO_MAU_IN_TOI_DA', 5, NULL, NULL, 2, NULL),
    ('TO-GIAY', 'DUNG_DO_NONG', 6, NULL, NULL, NULL, TRUE),
    ('TO-GIAY', 'CHUNG_NHAN', 7, 'FDA', NULL, NULL, NULL),
    ('TO-GIAY', 'CHUNG_NHAN', 7, 'HACCP', NULL, NULL, NULL),
    ('TO-GIAY', 'QUY_CACH_DONG_GOI', 8, NULL, '500 tô/thùng (10 ống x 50 tô), kèm nắp giấy', NULL, NULL),
    ('HOP-BANH', 'CHAT_LIEU', 1, 'GIAY_KRAFT_TRANG', NULL, NULL, NULL),
    ('HOP-BANH', 'MAU_NEN', 2, 'TRANG', NULL, NULL, NULL),
    ('HOP-BANH', 'DINH_LUONG_GIAY', 3, NULL, NULL, 350, NULL),
    ('HOP-BANH', 'SO_MAU_IN_TOI_DA', 4, NULL, NULL, 4, NULL),
    ('HOP-BANH', 'CHUNG_NHAN', 5, 'ISO22000', NULL, NULL, NULL),
    ('HOP-BANH', 'QUY_CACH_DONG_GOI', 6, NULL, '300 hộp/thùng, giao dạng phẳng', NULL, NULL),
    ('TUI-GIAY', 'CHAT_LIEU', 1, 'GIAY_XI_MANG', NULL, NULL, NULL),
    ('TUI-GIAY', 'MAU_NEN', 2, 'NAU_KRAFT', NULL, NULL, NULL),
    ('TUI-GIAY', 'DINH_LUONG_GIAY', 3, NULL, NULL, 120, NULL),
    ('TUI-GIAY', 'SO_MAU_IN_TOI_DA', 4, NULL, NULL, 2, NULL),
    ('TUI-GIAY', 'QUY_CACH_DONG_GOI', 5, NULL, '500 túi/thùng, quai giấy xoắn', NULL, NULL);

-- The axis, with the product (ProductServiceImpl.create -> defineAxes -> createMatrix).
INSERT INTO product.product_attributes (id, product_id, attribute_id, role, sort_order, version, created_at, created_by)
SELECT pg_temp.sid('dpc:product-attribute:' || code || ':' || axis), pg_temp.sid('product:' || code),
       pg_temp.sid('dpc:attribute:' || axis), 'VARIANT_AXIS', 0, 0, pg_temp.at(228, '09:00'),
       pg_temp.staff('sales1')::text
  FROM x_dpc_product;

INSERT INTO product.product_attribute_options (id, product_attribute_id, attribute_value_id, version, created_at,
                                               created_by)
SELECT pg_temp.sid('dpc:product-option:' || code || ':' || axis || ':' || axis_value),
       pg_temp.sid('dpc:product-attribute:' || code || ':' || axis),
       pg_temp.sid('dpc:attribute-value:' || axis || ':' || axis_value), 0, pg_temp.at(228, '09:00'),
       pg_temp.staff('sales1')::text
  FROM x_dpc_product;

INSERT INTO product.variant_attribute_values (id, variant_id, product_attribute_option_id, version, created_at,
                                              created_by)
SELECT pg_temp.sid('dpc:variant-option:' || p.variant_sku), pg_temp.sid('variant:' || p.variant_sku),
       pg_temp.sid('dpc:product-option:' || x.code || ':' || x.axis || ':' || x.axis_value), 0,
       pg_temp.at(228, '09:00'), pg_temp.staff('sales1')::text
  FROM x_dpc_product x JOIN s_product p ON p.code = x.code;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The descriptive set, five minutes later (ProductAttributeService.replaceDescriptives).
INSERT INTO product.product_attributes (id, product_id, attribute_id, role, sort_order, version, created_at, created_by)
SELECT DISTINCT pg_temp.sid('dpc:product-attribute:' || product || ':' || attr), pg_temp.sid('product:' || product),
       pg_temp.sid('dpc:attribute:' || attr), 'DESCRIPTIVE', sort, 0::bigint, pg_temp.at(228, '09:05'),
       pg_temp.staff('sales1')::text
  FROM x_dpc_desc;

INSERT INTO product.product_descriptive_values (id, product_attribute_id, attribute_value_id, value_text, value_number,
                                                value_boolean, version, created_at, created_by)
SELECT pg_temp.sid('dpc:descriptive-value:' || product || ':' || attr || ':' || coalesce(value_code, '-')),
       pg_temp.sid('dpc:product-attribute:' || product || ':' || attr),
       CASE WHEN value_code IS NOT NULL THEN pg_temp.sid('dpc:attribute-value:' || attr || ':' || value_code) END,
       text_value, num_value, bool_value, 0, pg_temp.at(228, '09:05'), pg_temp.staff('sales1')::text
  FROM x_dpc_desc;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- dpc.3 Each product's history (product_change_log, SCRUM-461) as the services write it, a few seconds apart so the
--       newest-first list reads in the order things happened. Publication writes no entry (ProductCommerceRepository).
-- ---------------------------------------------------------------------------------------------
INSERT INTO product.product_change_log (id, product_id, change_type, subject_type, subject_id, from_status, to_status,
                                        reason, changed_fields, actor_id, occurred_at)
SELECT pg_temp.sid('dpc:product-history:' || p.code || ':' || h.n), pg_temp.sid('product:' || p.code), h.change_type,
       h.subject_type,
       CASE h.subject WHEN 'variant' THEN pg_temp.sid('variant:' || p.variant_sku)
                      WHEN 'media' THEN pg_temp.sid('media:' || p.variant_sku) END,
       h.from_status, h.to_status, NULL, h.fields, pg_temp.staff(h.actor),
       pg_temp.at(h.days, h.hhmi)
  FROM s_product p
 CROSS JOIN (VALUES
       (1, 'CREATED', 'PRODUCT', NULL, NULL, 'DRAFT', '{}'::text[], 'sales1', 228, '09:00:00'),
       (2, 'VARIANT_ADDED', 'VARIANT', 'variant', NULL, 'DRAFT', '{}', 'sales1', 228, '09:00:01'),
       (3, 'UPDATED', 'PRODUCT', NULL, NULL, NULL, '{descriptiveAttributes}', 'sales1', 228, '09:05:00'),
       (4, 'MEDIA', 'MEDIA', 'media', NULL, NULL, '{uploaded}', 'sales1', 228, '09:20:00'),
       (5, 'SUBMITTED', 'PRODUCT', NULL, 'DRAFT', 'PENDING_APPROVAL', '{}', 'sales1', 228, '10:00:00'),
       (6, 'VARIANT_STATUS', 'VARIANT', 'variant', 'DRAFT', 'ACTIVE', '{}', 'truongkd', 227, '10:00:00'),
       (7, 'APPROVED', 'PRODUCT', NULL, 'PENDING_APPROVAL', 'APPROVED', '{}', 'truongkd', 227, '10:00:01'),
       (8, 'MEDIA', 'MEDIA', 'media', NULL, NULL, '{published}', 'truongkd', 227, '10:00:02'))
       AS h (n, change_type, subject_type, subject, from_status, to_status, fields, actor, days, hhmi);

-- Each customer SKU: issued by the design's approval in the reviewer's transaction (CustomerSkuService.issue), then
-- its carton unit and sales unit added by the salesperson (SkuUnitService).
INSERT INTO product.product_change_log (id, product_id, change_type, subject_type, subject_id, from_status, to_status,
                                        reason, changed_fields, actor_id, occurred_at)
SELECT pg_temp.sid('dpc:product-history:' || k.sku || ':' || h.n), pg_temp.sid('product:' || k.product), h.change_type,
       -- UOM_CONVERSION and SALES_UNIT are subject types of their own (ProductChangeType.subject()).
       CASE WHEN h.n = 1 THEN 'VARIANT' ELSE h.change_type END, pg_temp.sid('variant:' || k.sku), NULL, h.to_status,
       CASE WHEN h.n = 1 THEN 'customer SKU of design snapshot ' || pg_temp.sid('snapshot:' || k.sku) END,
       h.fields, pg_temp.staff(k.sales), pg_temp.at(k.since - 2, h.hhmi)
  FROM s_sku k
 CROSS JOIN (VALUES (1, 'VARIANT_ADDED', 'ACTIVE', '{}'::text[], '14:05:00'),
                    (2, 'UOM_CONVERSION', NULL, '{unit:THUNG}', '14:10:00'),
                    (3, 'SALES_UNIT', NULL, '{unit:THUNG}', '14:10:01')) AS h (n, change_type, to_status, fields, hhmi);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- dpc.4 The storefront listing, exactly as CatalogProjectionService.project leaves a published product (it re-runs on
--       every start and leaves these rows alone only when they equal its own output): the listing at the product's
--       version, enabled, its title and description published; one entry per ACTIVE blank variant (customer SKUs are
--       never listed), slug 'sku-<variant id>', no price, no stock figure.
-- ---------------------------------------------------------------------------------------------
INSERT INTO catalog.product_listing (product_id, slug, seo_title, seo_description, revision, projected_revision,
                                     enabled, ever_published, published_title, published_description,
                                     published_seo_title, published_seo_description, last_attempt_at)
SELECT p.id, p.slug, p.seo_title, p.seo_description, p.version, p.version, TRUE, TRUE, p.name, p.description,
       p.seo_title, p.seo_description, NULL
  FROM product.products p
 WHERE p.status = 'PUBLISHED';

INSERT INTO catalog.catalog_entry (id, sku, title, slug, description, atp, published, seo_title, seo_description,
                                   version, created_at, created_by, last_modified_at, last_modified_by, product_id,
                                   source_revision)
SELECT pg_temp.sid('dpc:catalog-entry:' || v.sku), v.sku, p.name, 'sku-' || v.id, p.description, NULL, TRUE,
       l.seo_title, l.seo_description, 0, p.published_at + interval '5 seconds', NULL, NULL, NULL, p.id, l.revision
  FROM product.variants v
  JOIN product.products p ON p.id = v.product_id
  JOIN catalog.product_listing l ON l.product_id = p.id
 WHERE p.status = 'PUBLISHED' AND v.status = 'ACTIVE' AND v.owner_customer_id IS NULL;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- dpc.5 Design projects (12 §4.9, BR-17): one customer in three keeps its whole packaging line in a project, started
--       the morning it first designed; its first designs were started in it and submitted together as one review
--       round (BR-19, BR-20). Ranks decide what else each project shows:
--         1-2   a second round waiting for the coordinator now (two new designs, PENDING_REVIEW)
--         3-6   a new design being drawn in the studio (DRAFT)
--         7-10  a quote request over the project's approved designs, still open for Sales (BR-21)
--         11-12 closed by the customer (BR-22)
--         13-14 a library file / text added and taken out again
--       Every project also asked its first quote as a project request, which Sales closed when they drew the quote.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_dpc_proj ON COMMIT DROP AS
SELECT c.code, c.idx, c.id AS customer_id, pg_temp.sid('user:' || c.username) AS owner, c.sales, c.since,
       regexp_replace(c.name, '^Công ty (TNHH|CP) ', '') AS brand,
       row_number() OVER (ORDER BY c.idx)::int AS rk, pg_temp.sid('dpc:project:' || c.code) AS id
  FROM s_customer c
 WHERE c.idx % 3 = 1;

ALTER TABLE x_dpc_proj ADD COLUMN name TEXT, ADD COLUMN description TEXT;
UPDATE x_dpc_proj
   SET name = 'Bộ bao bì ' || brand,
       description = 'Ly, tô, hộp in logo ' || brand || ' cho các cửa hàng; giữ đúng màu Pantone của bộ nhận diện, '
                     || 'logo đặt giữa thân, chừa lề an toàn 3 mm.';

INSERT INTO design.design_project (id, customer_id, owner_user_id, name, description, status, archived_at, version,
                                   created_at, created_by)
SELECT id, customer_id, owner, name, description, 'ACTIVE', NULL, 0, pg_temp.at(since - 1, '08:30'), owner::text
  FROM x_dpc_proj;

INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':created'), id, owner, 'PROJECT_CREATED', name, 0,
       pg_temp.at(since - 1, '08:30'), owner::text
  FROM x_dpc_proj;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The library (BR-18): the logo and a pattern, uploaded right after; ranks 1-6 add a seasonal banner the day their
-- new designs start; rank 13 uploads an old logo by mistake and takes it out.
CREATE TEMP TABLE x_dpc_lib ON COMMIT DROP AS
SELECT p.code, p.id AS project_id, p.owner, p.rk, f.n, f.file || lower(p.code) || '.png' AS original_name,
       f.w, f.h, 180000 + pg_temp.pick('dpc:lib-size:' || p.code || f.n, 0, 900) * 1000 AS size_bytes,
       pg_temp.at(p.since - 1, f.hhmi) AS at, FALSE AS removed
  FROM x_dpc_proj p
 CROSS JOIN (VALUES (1, 'logo-', 2000, 2000, '08:40'), (2, 'hoa-van-', 3000, 1200, '08:41')) AS f (n, file, w, h, hhmi);
INSERT INTO x_dpc_lib
SELECT p.code, p.id, p.owner, p.rk, 3, 'banner-mua-le-' || lower(p.code) || '.png', 2400, 800, 640000,
       pg_temp.at(CASE WHEN p.rk <= 2 THEN 4 ELSE 2 + pg_temp.pick('dpc:draft-day:' || p.code, 0, 7) END, '08:45'),
       FALSE
  FROM x_dpc_proj p WHERE p.rk <= 6;
INSERT INTO x_dpc_lib
SELECT p.code, p.id, p.owner, p.rk, 9, 'logo-cu-' || lower(p.code) || '.png', 1200, 1200, 210000,
       pg_temp.at(p.since - 1, '08:42'), TRUE
  FROM x_dpc_proj p WHERE p.rk = 13;
ALTER TABLE x_dpc_lib ADD COLUMN id UUID;
UPDATE x_dpc_lib SET id = pg_temp.sid('dpc:library-file:' || code || ':' || n);

INSERT INTO design.design_project_asset (id, project_id, storage_key, original_name, content_type, size_bytes,
                                         checksum, width_px, height_px, version, created_at, created_by, upload_key)
SELECT id, project_id, 'design-assets/showcase/' || lower(code) || '/' || original_name, original_name, 'image/png',
       size_bytes, pg_temp.dpc_hash('library-file:' || code || ':' || n), w, h, 0, at, owner::text, NULL
  FROM x_dpc_lib;

INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':asset-added:' || n), project_id, owner, 'ASSET_ADDED',
       id || ' ' || original_name, 0, at, owner::text
  FROM x_dpc_lib;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The old logo leaves the library; no design was drawn with it.
DELETE FROM design.design_project_asset WHERE id IN (SELECT id FROM x_dpc_lib WHERE removed);
INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':asset-removed:' || n), project_id, owner, 'ASSET_REMOVED',
       id || ' ' || original_name, 0, at + interval '25 minutes', owner::text
  FROM x_dpc_lib WHERE removed;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The library's texts with their type style (FE request 2026-11-13 E-1): the brand name, a slogan, a hotline.
CREATE TEMP TABLE x_dpc_text ON COMMIT DROP AS
SELECT p.code, p.id AS project_id, p.owner, t.n,
       CASE t.n WHEN 1 THEN p.brand
                WHEN 2 THEN (ARRAY['Ngon trọn từng ngụm', 'Tươi mỗi ngày', 'Vị quê trong từng ly', 'Mang đi đâu cũng ngon',
                                   'Chuẩn vị từ năm 2015', 'Xanh từ bao bì'])[1 + p.idx % 6]
                WHEN 3 THEN 'Đặt hàng: 09' || lpad((12000000 + p.idx * 7919)::text, 8, '0')
                ELSE 'Khuyến mãi khai trương - giảm 20%' END AS text,
       CASE t.n
           WHEN 1 THEN jsonb_build_object('fontCode', (ARRAY['montserrat', 'oswald', 'playfair-display'])[1 + p.idx % 3],
                                          'sizePt', 28, 'color', '#1F3B2D', 'bold', TRUE, 'italic', FALSE,
                                          'align', 'center', 'letterSpacingEm', 0.05, 'lineHeight', 1.1,
                                          'outline', NULL)
           WHEN 2 THEN jsonb_build_object('fontCode', (ARRAY['dancing-script', 'pacifico', 'lobster'])[1 + p.idx % 3],
                                          'sizePt', 18, 'color', '#8A4B12', 'bold', FALSE, 'italic', FALSE,
                                          'align', 'center', 'letterSpacingEm', 0, 'lineHeight', 1.2,
                                          'outline', jsonb_build_object('color', '#FFFFFF', 'widthMm', 0.3))
           WHEN 3 THEN jsonb_build_object('fontCode', 'be-vietnam-pro', 'sizePt', 9, 'color', '#333333', 'bold', FALSE,
                                          'italic', FALSE, 'align', 'left', 'letterSpacingEm', 0, 'lineHeight', 1.3,
                                          'outline', NULL)
           ELSE jsonb_build_object('fontCode', 'roboto-slab', 'sizePt', 14, 'color', '#C0392B', 'bold', TRUE,
                                   'italic', TRUE, 'align', 'right', 'letterSpacingEm', 0, 'lineHeight', 1.2,
                                   'outline', NULL) END AS style,
       pg_temp.at(p.since - 1, t.hhmi) AS at, t.n = 9 AS removed
  FROM x_dpc_proj p
 CROSS JOIN (VALUES (1, '08:50'), (2, '08:51'), (3, '08:52'), (9, '08:53')) AS t (n, hhmi)
 WHERE t.n <= 2 OR (t.n = 3 AND p.idx % 2 = 0) OR (t.n = 9 AND p.rk = 14);
ALTER TABLE x_dpc_text ADD COLUMN id UUID;
UPDATE x_dpc_text SET id = pg_temp.sid('dpc:library-text:' || code || ':' || n);

INSERT INTO design.design_project_text (id, project_id, text, style, version, created_at, created_by)
SELECT id, project_id, text, style, 0, at, owner::text FROM x_dpc_text;

INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':text-added:' || n), project_id, owner, 'TEXT_ADDED',
       id || ' ' || text, 0, at, owner::text
  FROM x_dpc_text;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

DELETE FROM design.design_project_text WHERE id IN (SELECT id FROM x_dpc_text WHERE removed);
INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':text-removed:' || n), project_id, owner, 'TEXT_REMOVED',
       id || ' ' || text, 0, at + interval '40 minutes', owner::text
  FROM x_dpc_text WHERE removed;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- dpc.6 The customer's first designs (the base's, approved since) were started in the project
--       (CustomerDesignService.start -> DesignProjectService.joinOnStart: ADDED_TO_PROJECT in the design's history),
--       submitted together as one round and closed when the last was approved (DesignReviewRounds.decided).
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_dpc_member ON COMMIT DROP AS
SELECT p.code, p.id AS project_id, p.owner, p.sales, p.since, d.id AS draft_id, d.sku AS blank_sku, k.sku,
       d.current_artifact_id, d.created_at, d.review_requested_at, d.reviewed_at, d.reviewed_by,
       row_number() OVER (PARTITION BY p.code ORDER BY d.id)::int AS n
  FROM x_dpc_proj p
  JOIN s_sku k ON k.cust = p.code
  JOIN design.design_draft d ON d.id = pg_temp.sid('design:' || k.sku);

UPDATE design.design_draft d SET project_id = m.project_id FROM x_dpc_member m WHERE d.id = m.draft_id;

INSERT INTO design.design_decision (id, draft_id, artifact_id, actor_id, decision, notes, draft_version, version,
                                    created_at, created_by)
SELECT pg_temp.sid('dpc:design-decision:' || sku || ':added'), draft_id, NULL, owner, 'ADDED_TO_PROJECT',
       'Project ' || project_id, 1, 0, created_at + interval '1 second', owner::text
  FROM x_dpc_member;

CREATE TEMP TABLE x_dpc_round ON COMMIT DROP AS
SELECT code, project_id, owner, sales, pg_temp.sid('dpc:review-round:' || code || ':1') AS id,
       min(review_requested_at) AS submitted_at, max(reviewed_at) AS closed_at,
       string_agg(draft_id::text, ', ' ORDER BY draft_id) AS designs,
       string_agg(draft_id || ' APPROVED', '; ' ORDER BY draft_id) AS outcomes
  FROM x_dpc_member GROUP BY code, project_id, owner, sales;

INSERT INTO design.design_review_round (id, project_id, submitted_by, submitted_at, closed_at, version, created_at,
                                        created_by, last_modified_at, last_modified_by)
SELECT id, project_id, owner, submitted_at, closed_at, 1, submitted_at, owner::text, closed_at,
       pg_temp.staff(sales)::text
  FROM x_dpc_round;

INSERT INTO design.design_review_round_design (round_id, draft_id, outcome, decided_at)
SELECT r.id, m.draft_id, 'APPROVED', m.reviewed_at
  FROM x_dpc_member m JOIN x_dpc_round r ON r.code = m.code;

INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':batch-submitted:1'), project_id, owner, 'BATCH_SUBMITTED',
       'Round ' || id || ': ' || designs, 0, submitted_at, owner::text
  FROM x_dpc_round
UNION ALL
SELECT pg_temp.sid('dpc:project-event:' || code || ':batch-closed:1'), project_id, pg_temp.staff(sales), 'BATCH_CLOSED',
       'Round ' || id || ': ' || outcomes, 0, closed_at + interval '1 second', pg_temp.staff(sales)::text
  FROM x_dpc_round;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- dpc.7 Quote requests (12 §4.8, §4.11; 16 §4.2). The evening before Sales drew each customer's first quote, the
--       customer asked for it from the studio: a project customer as one project request over the designs it names, the
--       others one request per design. Sales closed each as answered when they drew the quote that morning
--       (DesignQuoteRequestService.close / DesignProjectQuoteRequestService.close). No quote is referenced.
--       A few requests are open now, for the Sales inbox.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_dpc_firstq ON COMMIT DROP AS
SELECT DISTINCT ON (q.customer_id) q.id, q.customer_id, q.created_at, q.created_by::uuid AS sales_id,
       q.requested_delivery_date
  FROM ordering.quote q
 ORDER BY q.customer_id, q.created_at, q.id;

CREATE TEMP TABLE x_dpc_asked ON COMMIT DROP AS
SELECT c.code, c.idx, fq.customer_id, pg_temp.sid('user:' || c.username) AS owner, fq.sales_id, l.line_no,
       d.id AS draft_id, l.sku, l.cartons, d.current_artifact_id,
       fq.created_at - interval '17 hours' + l.line_no * interval '1 minute' AS asked_at, fq.created_at AS closed_at,
       fq.requested_delivery_date
  FROM x_dpc_firstq fq
  JOIN s_customer c ON c.id = fq.customer_id
  JOIN ordering.quote_line l ON l.quote_id = fq.id
  JOIN design.design_draft d ON d.id = pg_temp.sid('design:' || l.sku) AND d.status = 'APPROVED';

-- Project requests, closed.
CREATE TEMP TABLE x_dpc_preq ON COMMIT DROP AS
SELECT p.code, p.id AS project_id, a.owner, pg_temp.sid('dpc:project-quote-request:' || p.code || ':1') AS id,
       min(a.asked_at) AS asked_at, min(a.closed_at) AS closed_at, (array_agg(a.sales_id))[1] AS closed_by,
       min(a.requested_delivery_date) AS wanted, 'CLOSED'::text AS status,
       string_agg(a.draft_id || ' x' || a.cartons, ', ' ORDER BY a.line_no) AS asked
  FROM x_dpc_proj p JOIN x_dpc_asked a ON a.code = p.code
 GROUP BY p.code, p.id, a.owner;
UPDATE x_dpc_preq SET wanted = NULL WHERE wanted < (asked_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;

INSERT INTO design.design_project_quote_request (id, project_id, requested_by, desired_delivery_date, version,
                                                 created_at, created_by, status, closed_at, closed_by)
SELECT id, project_id, owner, wanted, 0, asked_at, owner::text, 'OPEN', NULL, NULL FROM x_dpc_preq;

INSERT INTO design.design_project_quote_request_line (request_id, draft_id, cartons)
SELECT r.id, a.draft_id, a.cartons FROM x_dpc_preq r JOIN x_dpc_asked a ON a.code = r.code;

INSERT INTO design.design_decision (id, draft_id, artifact_id, actor_id, decision, notes, draft_version, version,
                                    created_at, created_by)
SELECT pg_temp.sid('dpc:design-decision:' || a.sku || ':project-quote:1'), a.draft_id, a.current_artifact_id, a.owner,
       'QUOTE_REQUESTED', a.cartons || ' carton(s)' || coalesce(', delivery by ' || r.wanted, '')
       || ', project request ' || r.id, 2, 0, r.asked_at, a.owner::text
  FROM x_dpc_preq r JOIN x_dpc_asked a ON a.code = r.code;

INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':quote-requested:1'), project_id, owner, 'PROJECT_QUOTE_REQUESTED',
       'Request ' || id || ': ' || asked || coalesce('; delivery by ' || wanted, ''), 0, asked_at, owner::text
  FROM x_dpc_preq;

-- Design requests, closed (customers without a project).
INSERT INTO design.design_quote_request (id, draft_id, requested_by, cartons, desired_delivery_date, status, closed_at,
                                         closed_by, version, created_at, created_by)
SELECT pg_temp.sid('dpc:design-quote-request:' || a.sku || ':1'), a.draft_id, a.owner, a.cartons,
       CASE WHEN a.requested_delivery_date >= (a.asked_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
            THEN a.requested_delivery_date END,
       'OPEN', NULL, NULL, 0, a.asked_at, a.owner::text
  FROM x_dpc_asked a
 WHERE a.idx % 3 <> 1;

INSERT INTO design.design_decision (id, draft_id, artifact_id, actor_id, decision, notes, draft_version, version,
                                    created_at, created_by)
SELECT pg_temp.sid('dpc:design-decision:' || a.sku || ':quote:1'), a.draft_id, a.current_artifact_id, a.owner,
       'QUOTE_REQUESTED', r.cartons || ' carton(s)' || coalesce(', delivery by ' || r.desired_delivery_date, ''), 2, 0,
       a.asked_at, a.owner::text
  FROM x_dpc_asked a JOIN design.design_quote_request r ON r.id = pg_temp.sid('dpc:design-quote-request:' || a.sku || ':1');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Sales close them as they draw the quote.
UPDATE design.design_project_quote_request r
   SET status = 'CLOSED', closed_at = x.closed_at, closed_by = x.closed_by, version = 1,
       last_modified_at = x.closed_at, last_modified_by = x.closed_by::text
  FROM x_dpc_preq x WHERE r.id = x.id;

UPDATE design.design_quote_request r
   SET status = 'CLOSED', closed_at = a.closed_at, closed_by = a.sales_id, version = 1,
       last_modified_at = a.closed_at, last_modified_by = a.sales_id::text
  FROM x_dpc_asked a WHERE r.id = pg_temp.sid('dpc:design-quote-request:' || a.sku || ':1');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Open now: projects ranked 7-10 over all their approved designs, and one design each of six other customers.
CREATE TEMP TABLE x_dpc_open_line ON COMMIT DROP AS
SELECT p.code, p.id AS project_id, p.owner, m.draft_id, m.sku, m.current_artifact_id, m.n,
       15 + pg_temp.pick('dpc:open-cartons:' || m.sku, 0, 13) AS cartons,
       pg_temp.at(pg_temp.pick('dpc:open-day:' || p.code, 1, 3), '10:15') AS asked_at,
       pg_temp.day(-pg_temp.pick('dpc:open-wanted:' || p.code, 28, 45)) AS wanted,
       pg_temp.sid('dpc:project-quote-request:' || p.code || ':2') AS request_id
  FROM x_dpc_proj p JOIN x_dpc_member m ON m.code = p.code
 WHERE p.rk BETWEEN 7 AND 10;

INSERT INTO design.design_project_quote_request (id, project_id, requested_by, desired_delivery_date, version,
                                                 created_at, created_by, status, closed_at, closed_by)
SELECT DISTINCT request_id, project_id, owner, wanted, 0::bigint, asked_at, owner::text, 'OPEN', NULL::timestamptz,
       NULL::uuid
  FROM x_dpc_open_line;

INSERT INTO design.design_project_quote_request_line (request_id, draft_id, cartons)
SELECT request_id, draft_id, cartons FROM x_dpc_open_line;

INSERT INTO design.design_decision (id, draft_id, artifact_id, actor_id, decision, notes, draft_version, version,
                                    created_at, created_by)
SELECT pg_temp.sid('dpc:design-decision:' || sku || ':project-quote:2'), draft_id, current_artifact_id, owner,
       'QUOTE_REQUESTED', cartons || ' carton(s), delivery by ' || wanted || ', project request ' || request_id, 2, 0,
       asked_at, owner::text
  FROM x_dpc_open_line;

INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':quote-requested:2'), project_id, owner, 'PROJECT_QUOTE_REQUESTED',
       'Request ' || request_id || ': ' || string_agg(draft_id || ' x' || cartons, ', ' ORDER BY n)
       || '; delivery by ' || min(wanted), 0, min(asked_at), owner::text
  FROM x_dpc_open_line GROUP BY code, project_id, owner, request_id;

CREATE TEMP TABLE x_dpc_open_design ON COMMIT DROP AS
SELECT DISTINCT ON (c.code) c.code, pg_temp.sid('user:' || c.username) AS owner, d.id AS draft_id, k.sku,
       d.current_artifact_id, 12 + pg_temp.pick('dpc:open-cartons:' || k.sku, 0, 18) AS cartons,
       pg_temp.at(pg_temp.pick('dpc:open-day:' || c.code, 1, 4), '10:30') AS asked_at,
       CASE WHEN c.idx % 2 = 0 THEN pg_temp.day(-pg_temp.pick('dpc:open-wanted:' || c.code, 25, 40)) END AS wanted
  FROM s_customer c
  JOIN s_sku k ON k.cust = c.code
  JOIN design.design_draft d ON d.id = pg_temp.sid('design:' || k.sku) AND d.status = 'APPROVED'
 WHERE c.idx % 3 = 2 AND c.idx <= 17
 ORDER BY c.code, k.sku DESC;

INSERT INTO design.design_quote_request (id, draft_id, requested_by, cartons, desired_delivery_date, status, closed_at,
                                         closed_by, version, created_at, created_by)
SELECT pg_temp.sid('dpc:design-quote-request:' || sku || ':2'), draft_id, owner, cartons, wanted, 'OPEN', NULL, NULL, 0,
       asked_at, owner::text
  FROM x_dpc_open_design;

INSERT INTO design.design_decision (id, draft_id, artifact_id, actor_id, decision, notes, draft_version, version,
                                    created_at, created_by)
SELECT pg_temp.sid('dpc:design-decision:' || sku || ':quote:2'), draft_id, current_artifact_id, owner,
       'QUOTE_REQUESTED', cartons || ' carton(s)' || coalesce(', delivery by ' || wanted, ''), 2, 0, asked_at,
       owner::text
  FROM x_dpc_open_design;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- dpc.8 Projects ranked 11-12 are closed by their customer (BR-22); none of their designs waits for review.
-- ---------------------------------------------------------------------------------------------
UPDATE design.design_project p
   SET status = 'ARCHIVED', archived_at = pg_temp.at(12, '17:00'), version = 1,
       last_modified_at = pg_temp.at(12, '17:00'), last_modified_by = x.owner::text
  FROM x_dpc_proj x WHERE p.id = x.id AND x.rk IN (11, 12);

INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':archived'), id, owner, 'PROJECT_ARCHIVED', name, 0,
       pg_temp.at(12, '17:00'), owner::text
  FROM x_dpc_proj WHERE rk IN (11, 12);

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- dpc.9 New designs in the studio (12 §4.1-§4.3): started in the project on a blank variant's template, the
--       project's logo placed from the library (DesignAssetService.placeFromLibrary copies it into the design), the
--       layout saved (CustomerDesignService.saveDocument: canonical JSON and its SHA-256). Ranks 3-6 are still being
--       drawn; ranks 1-2 rendered their three files from the layout and submitted two designs as one round, which waits
--       for the Production Coordinator.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_dpc_draft ON COMMIT DROP AS
SELECT p.code, p.id AS project_id, p.customer_id, p.owner, p.rk, p.brand, k.product, k.sku AS customer_sku,
       k.variant_sku, k.name AS product_name, k.n,
       CASE WHEN p.rk <= 2 THEN 'PENDING_REVIEW' ELSE 'DRAFT' END AS status,
       CASE WHEN p.rk <= 2 THEN 4 ELSE 2 + pg_temp.pick('dpc:draft-day:' || p.code, 0, 7) END AS day,
       pg_temp.sid('dpc:design:' || p.code || ':' || k.product) AS id,
       pg_temp.sid('dpc:design-asset:' || p.code || ':' || k.product) AS asset_id
  FROM x_dpc_proj p
  JOIN LATERAL (SELECT s.*, row_number() OVER (ORDER BY s.idx)::int AS n FROM s_sku s WHERE s.cust = p.code) k
    ON k.n <= CASE WHEN p.rk <= 2 THEN 2 ELSE 1 END
 WHERE p.rk <= 6;

ALTER TABLE x_dpc_draft ADD COLUMN at TIMESTAMPTZ, ADD COLUMN name TEXT, ADD COLUMN area TEXT, ADD COLUMN lib_id UUID,
    ADD COLUMN lib_name TEXT, ADD COLUMN lib_size BIGINT, ADD COLUMN lib_checksum TEXT, ADD COLUMN text_content TEXT,
    ADD COLUMN text_style JSONB, ADD COLUMN document TEXT;
UPDATE x_dpc_draft x
   SET at = pg_temp.at(x.day, '09:00') + (x.n - 1) * interval '40 minutes',
       name = x.product_name || CASE WHEN x.rk <= 2 THEN ' - mùa Giáng sinh' ELSE ' - mẫu Tết 2027' END,
       area = (SELECT a.code FROM product.print_areas a WHERE a.template_id = pg_temp.sid('template:' || x.variant_sku)),
       lib_id = l.id, lib_name = l.original_name, lib_size = l.size_bytes,
       lib_checksum = pg_temp.dpc_hash('library-file:' || x.code || ':1')
  FROM x_dpc_lib l WHERE l.code = x.code AND l.n = 1;
UPDATE x_dpc_draft x SET text_content = t.text, text_style = t.style
  FROM x_dpc_text t WHERE t.code = x.code AND t.n = 1;
UPDATE x_dpc_draft x
   SET document = pg_temp.dpc_canon(jsonb_build_object(
       'schemaVersion', 1, 'templateCode', 'STD', 'sku', x.variant_sku,
       'surfaces', jsonb_build_object(x.area, jsonb_build_object(
           'background', jsonb_build_object('kind', 'none'),
           'elements', jsonb_build_array(
               jsonb_build_object('id', 'logo', 'type', 'image', 'assetId', x.asset_id::text,
                                  'naturalWidthPx', 2000, 'naturalHeightPx', 2000, 'widthMm', 34, 'heightMm', 34,
                                  'placement', jsonb_build_object('u', 100, 'v', 34, 'rotationDeg', 0, 'scale', 1)),
               jsonb_build_object('id', 'ten-thuong-hieu', 'type', 'text', 'content', x.text_content)
                   || (x.text_style - 'outline')
                   || jsonb_build_object('placement', jsonb_build_object('u', 100, 'v', 62, 'rotationDeg', 0,
                                                                         'scale', 1)))))));

INSERT INTO design.design_draft (id, customer_id, product_id, name, spec, status, version, created_at, created_by,
                                 owner_user_id, current_artifact_id, reviewed_by, review_notes, parent_snapshot_id,
                                 current_artifacts, variant_id, sku, template_id, template_code, review_requested_at,
                                 reviewed_at, archived_at, project_id)
SELECT id, customer_id, pg_temp.sid('product:' || product), name, NULL, 'DRAFT', 1, at, owner::text, owner, NULL, NULL,
       NULL, NULL, '{}'::jsonb, pg_temp.sid('variant:' || variant_sku), variant_sku,
       pg_temp.sid('template:' || variant_sku), 'STD', NULL, NULL, NULL, project_id
  FROM x_dpc_draft;

-- The empty layout the studio starts from (DesignDocumentRules.empty), replaced by the saved one below.
INSERT INTO design.design_document (draft_id, document, checksum, updated_at)
SELECT id, d.doc, encode(sha256(convert_to(d.doc, 'UTF8')), 'hex'), at
  FROM x_dpc_draft
 CROSS JOIN LATERAL (SELECT pg_temp.dpc_canon(jsonb_build_object(
           'schemaVersion', 1, 'templateCode', 'STD', 'sku', variant_sku,
           'surfaces', jsonb_build_object(area, jsonb_build_object('background', jsonb_build_object('kind', 'none'),
                                                                  'elements', '[]'::jsonb)))) AS doc) d;

INSERT INTO design.design_decision (id, draft_id, artifact_id, actor_id, decision, notes, draft_version, version,
                                    created_at, created_by)
SELECT pg_temp.sid('dpc:design-decision:' || code || ':' || product || ':created'), id, NULL::uuid, owner, 'CREATED',
       'SKU ' || variant_sku || ', template STD', 0, 0, at, owner::text
  FROM x_dpc_draft
UNION ALL
SELECT pg_temp.sid('dpc:design-decision:' || code || ':' || product || ':added'), id, NULL, owner, 'ADDED_TO_PROJECT',
       'Project ' || project_id, 1, 0, at + interval '1 second', owner::text
  FROM x_dpc_draft;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO design.design_asset (draft_id, id, storage_key, original_name, content_type, size_bytes, checksum, width_px,
                                 height_px, upload_key, created_at, created_by)
SELECT id, asset_id, 'design-assets/showcase/' || lower(code) || '/' || lower(product) || '-' || lib_name, lib_name,
       'image/png', lib_size, lib_checksum, 2000, 2000, owner || ':asset:library:' || lib_id,
       at + interval '5 minutes', owner::text
  FROM x_dpc_draft;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE design.design_document d
   SET document = x.document, checksum = encode(sha256(convert_to(x.document, 'UTF8')), 'hex'),
       updated_at = x.at + interval '25 minutes'
  FROM x_dpc_draft x WHERE d.draft_id = x.id;
UPDATE design.design_draft d SET version = 2 FROM x_dpc_draft x WHERE d.id = x.id;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Ranks 1-2: the three files rendered from the saved layout (DesignArtifactService, documentChecksum and the
-- template's print version), the preview being the design's current artifact.
INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, size_bytes, stored_at,
                                    checksum, version, created_at, created_by, upload_key, artifact_role,
                                    document_checksum, template_version)
SELECT pg_temp.sid('dpc:design-artifact:' || x.code || ':' || x.product || ':' || f.role), x.id,
       'design-renders/showcase/' || lower(x.code) || '-' || lower(x.product) || '-' || f.file, f.file, f.type,
       f.size, x.at + f.mins * interval '1 minute',
       pg_temp.dpc_hash('design-artifact:' || x.code || ':' || x.product || ':' || f.role), 0,
       x.at + f.mins * interval '1 minute', x.owner::text, NULL, f.role,
       encode(sha256(convert_to(x.document, 'UTF8')), 'hex'), t.print_version
  FROM x_dpc_draft x
  JOIN product.customization_templates t ON t.id = pg_temp.sid('template:' || x.variant_sku)
 CROSS JOIN (VALUES ('CUSTOMER_PREVIEW', 'preview.png', 'image/png', 184320, 30),
                    ('PRINT_READY', 'print_ready.pdf', 'application/pdf', 829440, 31),
                    ('TECHNICAL_SPEC', 'technical_spec.pdf', 'application/pdf', 97280, 32)) AS f (role, file, type, size, mins)
 WHERE x.status = 'PENDING_REVIEW';

UPDATE design.design_draft d
   SET current_artifact_id = pg_temp.sid('dpc:design-artifact:' || x.code || ':' || x.product || ':CUSTOMER_PREVIEW'),
       current_artifacts = jsonb_build_object(
           'CUSTOMER_PREVIEW', pg_temp.sid('dpc:design-artifact:' || x.code || ':' || x.product || ':CUSTOMER_PREVIEW'),
           'PRINT_READY', pg_temp.sid('dpc:design-artifact:' || x.code || ':' || x.product || ':PRINT_READY'),
           'TECHNICAL_SPEC', pg_temp.sid('dpc:design-artifact:' || x.code || ':' || x.product || ':TECHNICAL_SPEC')),
       version = 5
  FROM x_dpc_draft x WHERE d.id = x.id AND x.status = 'PENDING_REVIEW';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The batch (DesignBatchSubmissionService.submitLocked): yesterday afternoon, one round, both designs in it.
CREATE TEMP TABLE x_dpc_round2 ON COMMIT DROP AS
SELECT code, project_id, owner, pg_temp.sid('dpc:review-round:' || code || ':2') AS id, pg_temp.at(1, '15:00') AS at,
       string_agg(id::text, ', ' ORDER BY id) AS designs
  FROM x_dpc_draft WHERE status = 'PENDING_REVIEW'
 GROUP BY code, project_id, owner;

INSERT INTO design.design_review_round (id, project_id, submitted_by, submitted_at, closed_at, version, created_at,
                                        created_by)
SELECT id, project_id, owner, at, NULL, 0, at, owner::text FROM x_dpc_round2;

UPDATE design.design_draft d
   SET status = 'PENDING_REVIEW', review_requested_at = r.at, version = 6, last_modified_at = r.at,
       last_modified_by = r.owner::text
  FROM x_dpc_draft x JOIN x_dpc_round2 r ON r.code = x.code
 WHERE d.id = x.id AND x.status = 'PENDING_REVIEW';

INSERT INTO design.design_review_round_design (round_id, draft_id, outcome, decided_at)
SELECT r.id, x.id, NULL, NULL FROM x_dpc_draft x JOIN x_dpc_round2 r ON r.code = x.code
 WHERE x.status = 'PENDING_REVIEW';

INSERT INTO design.design_decision (id, draft_id, artifact_id, actor_id, decision, notes, draft_version, version,
                                    created_at, created_by)
SELECT pg_temp.sid('dpc:design-decision:' || x.code || ':' || x.product || ':submitted'), x.id,
       pg_temp.sid('dpc:design-artifact:' || x.code || ':' || x.product || ':CUSTOMER_PREVIEW'), x.owner,
       'SUBMITTED_FOR_REVIEW',
       'Layout ' || encode(sha256(convert_to(x.document, 'UTF8')), 'hex') || ', review round ' || r.id, 6, 0, r.at,
       x.owner::text
  FROM x_dpc_draft x JOIN x_dpc_round2 r ON r.code = x.code
 WHERE x.status = 'PENDING_REVIEW';

INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT pg_temp.sid('dpc:project-event:' || code || ':batch-submitted:2'), project_id, owner, 'BATCH_SUBMITTED',
       'Round ' || id || ': ' || designs, 0, at, owner::text
  FROM x_dpc_round2;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

DROP FUNCTION pg_temp.dpc_canon(JSONB);
DROP FUNCTION pg_temp.dpc_hash(TEXT);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- =============================================================================================
-- Group ox: order lifecycle extras, customers, identity.
--   holds (credit at signing, address, customer, other), cancellation requests, delivery address changes, delivery
--   window moves, partial-shipment decisions, completion holds, delay alerts, print sample rounds; customer segments,
--   default payment schedules, onboarding trail (status history, portal invitations), profile edits; staff account
--   lock/disable history.
-- Left empty, on purpose: ordering.order_remainder_closure and payment.installment_reduction. Order.closeRemainder
-- closes the rest only of a PARTIALLY_FULFILLED order with a shipment handed over or labelled (Order.java, the checks
-- at "only a partly fulfilled order" and "nothing of it was handed over"), and payment.check_installment_reduction takes
-- only a REMAINDER_CLOSED / GOODS_RETURNED settlement: no order of this group's partition has a shipment.
-- =============================================================================================

-- Which order plays which part (stage, i of h_order).
CREATE TEMP TABLE x_ox_o ON COMMIT DROP AS
SELECT r.role, o.*, CASE o.sales WHEN 'sf.sales' THEN 'sales1' ELSE o.sales END AS sl
  FROM (VALUES ('credit_open', 'PENDING_CONTRACT', 1), ('credit_ok', 'PENDING_CONTRACT', 2),
               ('cr_approved', 'PENDING_CONTRACT', 7), ('cr_rejected', 'PENDING_PAYMENT', 7),
               ('cr_pending', 'IN_PRODUCTION', 8),
               ('sample_wait', 'CONFIRMED', 1), ('sample_reprint', 'CONFIRMED', 2), ('sample_ok', 'CONFIRMED', 5),
               ('hold_address', 'CONFIRMED', 3), ('addr_confirmed', 'CONFIRMED', 4), ('window_draft', 'CONFIRMED', 6),
               ('addr_pp', 'PENDING_PAYMENT', 6), ('addr_accepted', 'PO_ACCEPTED', 3),
               ('window_sent', 'PO_SENT', 1), ('atrisk_window', 'IN_PRODUCTION', 7), ('atrisk_1', 'IN_PRODUCTION', 1),
               ('atrisk_2', 'PO_ACCEPTED', 2), ('atrisk_closed', 'IN_PRODUCTION', 5),
               ('hold_open', 'HELD', 1), ('hold_resolved', 'IN_PRODUCTION', 3),
               ('partial_1', 'PARTIAL', 1), ('partial_2', 'PARTIAL', 3), ('partial_3', 'IN_PRODUCTION', 6),
               ('done_disputed', 'DELIVERED', 19), ('done_released', 'DELIVERED', 139), ('done_other', 'DELIVERED', 7),
               ('done_alert_1', 'DELIVERED', 127), ('done_alert_2', 'DELIVERED', 103)) AS r (role, stage, i)
  JOIN h_order o ON o.stage = r.stage AND o.i = r.i;

-- ---------------------------------------------------------------------------------------------
-- 1. Contracts signed with the customer overdue (15 BR-10, BR-11; SalesContractService.placeSigned): held for credit.
--    credit_open waits for accounting; credit_ok was approved the next morning and waits for its deposit.
-- ---------------------------------------------------------------------------------------------
UPDATE ordering.sales_contract k
   SET status = 'SIGNED', signed_on = pg_temp.day(o.s), signed_at = pg_temp.at(o.s, '10:00'),
       signed_by = pg_temp.staff(o.sl), signed_file_key = 'documents/showcase/' || o.contract_number || '-ky.pdf',
       signed_file_name = o.contract_number || '-da-ky.pdf', version = k.version + 1,
       last_modified_at = pg_temp.at(o.s, '10:00'), last_modified_by = pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE k.id = o.contract_id AND o.role IN ('credit_open', 'credit_ok');

UPDATE ordering.customer_order co
   SET status = 'ON_HOLD', hold_reason = 'CREDIT', status_before_hold = NULL,
       contract_signed_at = pg_temp.at(o.s, '10:00'), placed_at = pg_temp.at(o.s, '10:00'), version = co.version + 1,
       last_modified_at = pg_temp.at(o.s, '10:00'), last_modified_by = pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE co.id = o.id AND o.role IN ('credit_open', 'credit_ok');

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('ox:order-history:' || o.key || ':signed'), o.id, 'PENDING_CONTRACT', 'ON_HOLD', 'CREDIT',
       pg_temp.staff(o.sl), pg_temp.at(o.s, '10:00'), pg_temp.at(o.s, '10:00'), pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE o.role IN ('credit_open', 'credit_ok');

-- The check at signing: OVERDUE (an installment of the customer past its due day), the exposure counted as section 11.
INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome, note,
                                         version, created_at, created_by)
SELECT pg_temp.sid('ox:credit-check:' || o.key || ':1'), o.id, pg_temp.at(o.s, '10:00'), o.credit_limit,
       coalesce((SELECT sum(p.rest - CASE WHEN p.paid_after > o.s THEN round(p.rest * p.paid_part, 0) ELSE 0 END)
                   FROM h_order p
                  WHERE p.cust = o.cust AND p.st >= 4 AND p.s > o.s AND p.credit), 0),
       o.rest, 'OVERDUE', 'Sales contract ' || o.contract_number, 0, pg_temp.at(o.s, '10:00'),
       pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE o.role IN ('credit_open', 'credit_ok');

INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, note, raised_by, version, created_at, created_by)
SELECT pg_temp.sid('ox:hold:' || o.key || ':credit'), o.id, 'CREDIT', pg_temp.at(o.s, '10:00'), NULL, NULL, 0,
       pg_temp.at(o.s, '10:00'), pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE o.role IN ('credit_open', 'credit_ok');

-- Held or not, a signed contract is owed what it says: the installments open with the signature (section 11's shape).
INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
                                 order_number, seq, name, milestone, delivery_batch_id, delivery_batch_seq, amount,
                                 paid_amount, currency, due_days, reached_at, due_date, status, version, created_at,
                                 created_by)
SELECT pg_temp.sid('ox:installment:' || o.key || ':' || p.seq), o.contract_id, p.id, o.id, o.customer_id,
       o.contract_number, o.order_number, p.seq, p.name, p.milestone, p.delivery_batch_id,
       CASE WHEN p.delivery_batch_id IS NOT NULL THEN 1 END, p.amount, 0, 'VND', p.due_days,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN pg_temp.at(o.s, '10:00') END,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN pg_temp.day(o.s) + p.due_days END,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN 'DUE' ELSE 'NOT_DUE' END, 0, pg_temp.at(o.s, '10:00'),
       pg_temp.staff(o.sl)::text
  FROM x_ox_o o JOIN ordering.contract_payment_installment p ON p.contract_id = o.contract_id
 WHERE o.role IN ('credit_open', 'credit_ok');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Accounting approves credit_ok (CreditHoldService.approve): its deposit is still unpaid, so PENDING_PAYMENT.
UPDATE ordering.customer_order co
   SET status = 'PENDING_PAYMENT', hold_reason = NULL, status_before_hold = NULL, hold_note = NULL,
       version = co.version + 1, last_modified_at = pg_temp.at(o.s - 1, '09:00'),
       last_modified_by = pg_temp.staff('ketoan')::text
  FROM x_ox_o o WHERE co.id = o.id AND o.role = 'credit_ok';

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('ox:order-history:' || o.key || ':credit-approved'), o.id, 'ON_HOLD', 'PENDING_PAYMENT',
       'CREDIT_APPROVED', pg_temp.staff('ketoan'), pg_temp.at(o.s - 1, '09:00'), pg_temp.at(o.s - 1, '09:00'),
       pg_temp.staff('ketoan')::text
  FROM x_ox_o o WHERE o.role = 'credit_ok';

INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome,
                                         decided_by, decided_at, note, version, created_at, created_by)
SELECT pg_temp.sid('ox:credit-check:' || o.key || ':2'), o.id, pg_temp.at(o.s - 1, '09:00'), c.credit_limit, c.exposure,
       c.order_amount, 'APPROVED', pg_temp.staff('ketoan'), pg_temp.at(o.s - 1, '09:00'),
       'Khách đã chuyển khoản phần công nợ quá hạn sáng nay; duyệt giữ nguyên hạn mức', 0, pg_temp.at(o.s - 1, '09:00'),
       pg_temp.staff('ketoan')::text
  FROM x_ox_o o JOIN ordering.order_credit_check c ON c.id = pg_temp.sid('ox:credit-check:' || o.key || ':1')
 WHERE o.role = 'credit_ok';

UPDATE ordering.order_hold h
   SET resolved_at = pg_temp.at(o.s - 1, '09:00'), resolved_by = pg_temp.staff('ketoan'),
       resolution_note = 'APPROVED: Khách đã chuyển khoản phần công nợ quá hạn sáng nay; duyệt giữ nguyên hạn mức',
       version = h.version + 1, last_modified_at = pg_temp.at(o.s - 1, '09:00'),
       last_modified_by = pg_temp.staff('ketoan')::text
  FROM x_ox_o o WHERE h.id = pg_temp.sid('ox:hold:' || o.key || ':credit') AND o.role = 'credit_ok';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 2. Cancellation requests (17 §4.4; OrderServiceImpl.cancelOwn, CancellationRequestService). The customer asks from
--    the portal; Sales decide. A DRAFT order is not the customer's to ask about, so none is on the base's orders
--    cancelled in draft.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.order_cancellation_request (id, order_id, requested_by, reason_code, note, requested_at, status,
                                                 version, created_at, created_by)
SELECT pg_temp.sid('ox:cancellation-request:' || o.key), o.id, pg_temp.sid('user:' || o.cust_user), x.code, x.note,
       pg_temp.at(x.days, x.hhmi), 'PENDING', 0, pg_temp.at(x.days, x.hhmi), pg_temp.sid('user:' || o.cust_user)::text
  FROM x_ox_o o
  JOIN (VALUES ('cr_approved', 'CUSTOMER_REQUEST', 'Bên mình dời kế hoạch mở cửa hàng sang quý sau, xin huỷ đơn này.', 2, '14:00'),
               ('cr_rejected', 'CUSTOMER_REQUEST', 'Bên mình chưa thu xếp kịp tiền đặt cọc, xin huỷ đơn.', 6, '10:00'),
               ('cr_pending', 'OTHER', 'Chi nhánh Quận 3 tạm đóng cửa để sửa chữa, xin huỷ phần hàng chưa giao.', 1, '16:00'))
       AS x (role, code, note, days, hhmi) ON x.role = o.role;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE ordering.order_cancellation_request r
   SET status = 'REJECTED', decided_by = pg_temp.staff(o.sl), decided_at = pg_temp.at(5, '15:00'),
       decision_note = 'Hợp đồng đã ký; đã thống nhất qua điện thoại gia hạn đặt cọc thêm 7 ngày, khách giữ đơn.',
       version = r.version + 1, last_modified_at = pg_temp.at(5, '15:00'), last_modified_by = pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE r.id = pg_temp.sid('ox:cancellation-request:' || o.key) AND o.role = 'cr_rejected';

-- Approved: the order is cancelled in the same transaction; its contract, sent and not signed, goes with it; nothing
-- was paid on it (no installment opens before the signature), so nothing is settled.
UPDATE ordering.order_cancellation_request r
   SET status = 'APPROVED', decided_by = pg_temp.staff(o.sl), decided_at = pg_temp.at(1, '10:00'),
       decision_note = 'Đồng ý huỷ theo đề nghị của khách; hợp đồng chưa ký nên không phát sinh phí.', retained_percent = NULL,
       version = r.version + 1, last_modified_at = pg_temp.at(1, '10:00'), last_modified_by = pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE r.id = pg_temp.sid('ox:cancellation-request:' || o.key) AND o.role = 'cr_approved';

UPDATE ordering.customer_order co
   SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST',
       cancellation_reason = 'CUSTOMER_REQUEST: Bên mình dời kế hoạch mở cửa hàng sang quý sau, xin huỷ đơn này.',
       cancellation_retained_amount = 0, version = co.version + 1, last_modified_at = pg_temp.at(1, '10:00'),
       last_modified_by = pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE co.id = o.id AND o.role = 'cr_approved';

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('ox:order-history:' || o.key || ':cancelled'), o.id, 'PENDING_CONTRACT', 'CANCELLED',
       'CUSTOMER_REQUEST: Bên mình dời kế hoạch mở cửa hàng sang quý sau, xin huỷ đơn này.', pg_temp.staff(o.sl),
       pg_temp.at(1, '10:00'), pg_temp.at(1, '10:00'), pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE o.role = 'cr_approved';

UPDATE ordering.sales_contract k
   SET status = 'CANCELLED', cancellation_reason = 'Huỷ cùng đơn hàng: Khách yêu cầu', cancelled_at = pg_temp.at(1, '10:00'),
       cancelled_by = pg_temp.staff(o.sl), version = k.version + 1, last_modified_at = pg_temp.at(1, '10:00'),
       last_modified_by = pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE k.id = o.contract_id AND o.role = 'cr_approved';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 3. Print sample rounds (4A-4; 17 §4.3 steps 9-10, BR-24; ProductionSampleService). Only a production order drawn up
--    with a sample to approve has rounds, and that is decided while it is a draft: three of the confirmed orders' draft
--    production orders are set to want one, then sent, accepted and sampled. sample_wait: round 1 waits for the
--    customer; sample_reprint: round 1 sent back, round 2 waits; sample_ok: round 1 approved, mass printing started.
-- ---------------------------------------------------------------------------------------------
UPDATE ordering.production_order p
   SET sample_approval_required = TRUE, sample_waiver_reason = NULL,
       note = 'Khách yêu cầu duyệt ảnh mẫu in thật trước khi in hàng loạt', version = p.version + 1,
       last_modified_at = pg_temp.at(o.s - 1, '15:20'), last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM x_ox_o o WHERE p.id = o.po_id AND o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Sent by the coordinator (as section 13): the slip, the order waiting for stock.
UPDATE ordering.production_order p
   SET status = 'SENT', sent_at = pg_temp.at(o.s - 1, '16:00'), sent_by = pg_temp.staff('dieuphoi'),
       version = p.version + 1, last_modified_at = pg_temp.at(o.s - 1, '16:00'),
       last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM x_ox_o o WHERE p.id = o.po_id AND o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

INSERT INTO ordering.production_order_slip (production_order_id, storage_key, sha256, size_bytes, created_at)
SELECT o.po_id, 'documents/showcase/' || o.po_code || '.pdf',
       encode(sha256(convert_to('showcase:ox:slip:' || o.key, 'UTF8')), 'hex'), 27136, pg_temp.at(o.s - 1, '16:00')
  FROM x_ox_o o WHERE o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status, to_status, reason,
                                                      changed_by, changed_at)
SELECT pg_temp.sid('ox:po-history:' || o.key || ':sent'), o.po_id, 'DRAFT', 'SENT', NULL, pg_temp.staff('dieuphoi'),
       pg_temp.at(o.s - 1, '16:00')
  FROM x_ox_o o WHERE o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

UPDATE ordering.customer_order co
   SET status = 'AWAITING_STOCK', version = co.version + 1, last_modified_at = pg_temp.at(o.s - 1, '16:00'),
       last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM x_ox_o o WHERE co.id = o.id AND o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('ox:order-history:' || o.key || ':po-sent'), o.id, 'CONFIRMED', 'AWAITING_STOCK',
       'PRODUCTION_ORDER_SENT', pg_temp.staff('dieuphoi'), pg_temp.at(o.s - 1, '16:00'), pg_temp.at(o.s - 1, '16:00'),
       pg_temp.staff('dieuphoi')::text
  FROM x_ox_o o WHERE o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE ordering.production_order p
   SET status = 'ACCEPTED', expected_completion_date = o.window_start - 5,
       acceptance_note = 'Nhận đơn; sẽ gửi ảnh mẫu in để khách duyệt trước khi chạy hàng loạt', version = p.version + 1,
       last_modified_at = pg_temp.at(o.s - 1, '17:00'), last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM x_ox_o o WHERE p.id = o.po_id AND o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status, to_status, reason,
                                                      changed_by, changed_at)
SELECT pg_temp.sid('ox:po-history:' || o.key || ':accepted'), o.po_id, 'SENT', 'ACCEPTED', NULL,
       pg_temp.staff('dieuphoi'), pg_temp.at(o.s - 1, '17:00')
  FROM x_ox_o o WHERE o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Round 1, two photos each, uploaded by the coordinator the next morning; the production order waits for its sample.
INSERT INTO ordering.production_order_sample_round (id, production_order_id, round_no, reprint_reason,
                                                    physical_sample_sent_on, opened_by, opened_at, created_at)
SELECT pg_temp.sid('ox:sample-round:' || o.key || ':1'), o.po_id, 1, NULL, NULL, pg_temp.staff('dieuphoi'),
       pg_temp.at(o.s - 2, '09:00'), pg_temp.at(o.s - 2, '09:00')
  FROM x_ox_o o WHERE o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

INSERT INTO ordering.production_order_sample_media (id, sample_round_id, storage_key, file_name, content_type,
                                                    size_bytes, uploaded_by, uploaded_at)
SELECT pg_temp.sid('ox:sample-media:' || o.key || ':1:' || m.n), pg_temp.sid('ox:sample-round:' || o.key || ':1'),
       'print-samples/showcase/' || o.po_code || '/vong-1-' || m.n || '.jpg', m.name, 'image/jpeg', m.size,
       pg_temp.staff('dieuphoi'), pg_temp.at(o.s - 2, '09:00')
  FROM x_ox_o o
 CROSS JOIN (VALUES (1, 'mau-in-mat-truoc.jpg', 1843200), (2, 'mau-in-can-canh-logo.jpg', 2211840)) AS m (n, name, size)
 WHERE o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

UPDATE ordering.production_order p
   SET status = 'SAMPLE_PENDING', version = p.version + 1, last_modified_at = pg_temp.at(o.s - 2, '09:00'),
       last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM x_ox_o o WHERE p.id = o.po_id AND o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status, to_status, reason,
                                                      changed_by, changed_at)
SELECT pg_temp.sid('ox:po-history:' || o.key || ':sample'), o.po_id, 'ACCEPTED', 'SAMPLE_PENDING',
       'Print sample round 1 uploaded', pg_temp.staff('dieuphoi'), pg_temp.at(o.s - 2, '09:00')
  FROM x_ox_o o WHERE o.role IN ('sample_wait', 'sample_reprint', 'sample_ok');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- A physical sample went out by courier the same day (17 §4.3 step 9).
UPDATE ordering.production_order_sample_round r
   SET physical_sample_sent_on = pg_temp.day(o.s - 2)
  FROM x_ox_o o WHERE r.id = pg_temp.sid('ox:sample-round:' || o.key || ':1') AND o.role = 'sample_wait';

-- The customer's answers, from their own portal login (13 BR-13).
UPDATE ordering.production_order_sample_round r
   SET result = 'REPRINT_REQUESTED', customer_note = 'Màu đỏ của logo nhạt hơn file đã duyệt, nhờ in lại đậm hơn.',
       answered_by = pg_temp.sid('user:' || o.cust_user), answered_at = pg_temp.at(o.s - 2, '11:00')
  FROM x_ox_o o WHERE r.id = pg_temp.sid('ox:sample-round:' || o.key || ':1') AND o.role = 'sample_reprint';

UPDATE ordering.production_order_sample_round r
   SET result = 'APPROVED', answered_by = pg_temp.sid('user:' || o.cust_user), answered_at = pg_temp.at(o.s - 2, '14:00')
  FROM x_ox_o o WHERE r.id = pg_temp.sid('ox:sample-round:' || o.key || ':1') AND o.role = 'sample_ok';

UPDATE ordering.production_order p
   SET status = 'IN_PRODUCTION', production_started_at = pg_temp.at(o.s - 2, '14:00'), version = p.version + 1,
       last_modified_at = pg_temp.at(o.s - 2, '14:00'), last_modified_by = pg_temp.sid('user:' || o.cust_user)::text
  FROM x_ox_o o WHERE p.id = o.po_id AND o.role = 'sample_ok';

INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status, to_status, reason,
                                                      changed_by, changed_at)
SELECT pg_temp.sid('ox:po-history:' || o.key || ':approved'), o.po_id, 'SAMPLE_PENDING', 'IN_PRODUCTION',
       'Print sample round 1 approved by the customer', pg_temp.sid('user:' || o.cust_user), pg_temp.at(o.s - 2, '14:00')
  FROM x_ox_o o WHERE o.role = 'sample_ok';

-- BR-11: the order records when printing started, once.
UPDATE ordering.customer_order co
   SET printing_started_at = pg_temp.at(o.s - 2, '14:00'), version = co.version + 1,
       last_modified_at = pg_temp.at(o.s - 2, '14:00'), last_modified_by = pg_temp.sid('user:' || o.cust_user)::text
  FROM x_ox_o o WHERE co.id = o.id AND o.role = 'sample_ok';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Round 2 of the one sent back: printed again for a print defect, waiting for the customer.
INSERT INTO ordering.production_order_sample_round (id, production_order_id, round_no, reprint_reason,
                                                    physical_sample_sent_on, opened_by, opened_at, created_at)
SELECT pg_temp.sid('ox:sample-round:' || o.key || ':2'), o.po_id, 2, 'PRINT_DEFECT', NULL, pg_temp.staff('dieuphoi'),
       pg_temp.at(o.s - 2, '16:30'), pg_temp.at(o.s - 2, '16:30')
  FROM x_ox_o o WHERE o.role = 'sample_reprint';

INSERT INTO ordering.production_order_sample_media (id, sample_round_id, storage_key, file_name, content_type,
                                                    size_bytes, uploaded_by, uploaded_at)
SELECT pg_temp.sid('ox:sample-media:' || o.key || ':2:' || m.n), pg_temp.sid('ox:sample-round:' || o.key || ':2'),
       'print-samples/showcase/' || o.po_code || '/vong-2-' || m.n || m.ext, m.name, m.type, m.size,
       pg_temp.staff('dieuphoi'), pg_temp.at(o.s - 2, '16:30')
  FROM x_ox_o o
 CROSS JOIN (VALUES (1, '.jpg', 'mau-in-lai-mat-truoc.jpg', 'image/jpeg', 1966080),
                    (2, '.mp4', 'mau-in-lai-xoay-360.mp4', 'video/mp4', 9437184)) AS m (n, ext, name, type, size)
 WHERE o.role = 'sample_reprint';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 4. Holds Sales put on and resolved (17 §5; OrderServiceImpl.putOnHold / resolveHold), and delivery address changes
--    (17 BR-16, ShippingAddressChangeService: a SHIPPING address of the customer's own book, until the first label).
-- ---------------------------------------------------------------------------------------------
-- A second delivery address in the book of four customers whose order changes address.
INSERT INTO customer.address (id, customer_id, type, recipient_name, phone, line1, ward, province, country, is_default,
                              ward_code, province_code, version, created_at, created_by)
SELECT pg_temp.sid('ox:address:' || o.key), o.customer_id, 'SHIPPING', a.recipient, a.phone, a.line1, a.ward,
       c.province, 'VN', FALSE, a.ward_code, c.province_code, 0, pg_temp.at(a.days, '15:45'),
       pg_temp.staff(o.sl)::text
  FROM x_ox_o o JOIN s_customer c ON c.code = o.cust
  JOIN (VALUES ('hold_address', 'Đoàn Văn Kho', '0909336112', '15 Trần Quang Khải', 'Phường Tân Định', '26734', 1),
               ('addr_confirmed', 'Phạm Thị Hồng', '0905448213', '25 Hùng Vương', 'Phường Hải Châu', '20194', 1),
               ('addr_pp', 'Khúc Thị Thảo', '0912775430', '18 Trần Thái Tông', 'Phường Cầu Giấy', '00166', 8),
               ('addr_accepted', 'Ninh Văn Bảo', '0983551207', '40 Phan Chu Trinh', 'Phường Hai Bà Trưng', '00256', 1))
       AS a (role, recipient, phone, line1, ward, ward_code, days) ON a.role = o.role;

-- hold_address: held for its address the afternoon it was confirmed, the address changed, the hold resolved.
UPDATE ordering.customer_order co
   SET status = 'ON_HOLD', hold_reason = 'ADDRESS', status_before_hold = 'CONFIRMED',
       hold_note = 'Khách báo đổi điểm nhận hàng sang kho chi nhánh mới, chờ khách gửi địa chỉ',
       version = co.version + 1, last_modified_at = pg_temp.at(1, '15:30'), last_modified_by = pg_temp.staff(o.sl)::text
  FROM x_ox_o o WHERE co.id = o.id AND o.role = 'hold_address';

-- hold_open: all its goods are in and held; the customer asks to wait. hold_resolved: held a while, then resumed.
UPDATE ordering.customer_order co
   SET status = 'ON_HOLD', hold_reason = x.reason, status_before_hold = co.status, hold_note = x.note,
       version = co.version + 1, last_modified_at = pg_temp.at(x.days, x.hhmi),
       last_modified_by = pg_temp.staff(o.sl)::text
  FROM x_ox_o o
  JOIN (VALUES ('hold_open', 'CUSTOMER', 'Khách đề nghị tạm hoãn giao vì cửa hàng mới chưa hoàn thiện; giữ hàng tại kho', 2, '10:00'),
               ('hold_resolved', 'OTHER', 'Chờ khách xác nhận lại số thùng của đợt giao trước khi nhập hàng', 8, '09:30'))
       AS x (role, reason, note, days, hhmi) ON x.role = o.role
 WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('ox:order-history:' || o.key || ':hold'), o.id, co.status_before_hold, 'ON_HOLD',
       co.hold_reason || ': ' || co.hold_note, pg_temp.staff(o.sl), co.last_modified_at, co.last_modified_at,
       pg_temp.staff(o.sl)::text
  FROM x_ox_o o JOIN ordering.customer_order co ON co.id = o.id
 WHERE o.role IN ('hold_address', 'hold_open', 'hold_resolved');

INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, note, raised_by, version, created_at, created_by)
SELECT pg_temp.sid('ox:hold:' || o.key || ':1'), o.id, co.hold_reason, co.last_modified_at, co.hold_note,
       pg_temp.staff(o.sl), 0, co.last_modified_at, pg_temp.staff(o.sl)::text
  FROM x_ox_o o JOIN ordering.customer_order co ON co.id = o.id
 WHERE o.role IN ('hold_address', 'hold_open', 'hold_resolved');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The address changes: the order's snapshot replaced, the change kept with the status the order was in.
CREATE TEMP TABLE x_ox_addr ON COMMIT DROP AS
SELECT o.key, o.id, o.sl, a.id AS address_id, pg_temp.at(x.days, x.hhmi) AS at
  FROM x_ox_o o JOIN customer.address a ON a.id = pg_temp.sid('ox:address:' || o.key)
  JOIN (VALUES ('hold_address', 1, '16:10'), ('addr_confirmed', 1, '16:20'), ('addr_pp', 8, '16:00'),
               ('addr_accepted', 1, '16:30')) AS x (role, days, hhmi) ON x.role = o.role;

INSERT INTO ordering.order_address_change (id, order_id, order_status, previous_recipient_name, previous_phone,
                                           previous_line1, previous_line2, previous_ward_code, previous_ward_name,
                                           previous_province_code, previous_province_name, previous_country_code,
                                           previous_postal_code, new_recipient_name, new_phone, new_line1, new_line2,
                                           new_ward_code, new_ward_name, new_province_code, new_province_name,
                                           new_country_code, new_postal_code, changed_by, changed_at, version,
                                           created_at, created_by)
SELECT pg_temp.sid('ox:address-change:' || x.key), co.id, co.status, co.shipping_recipient_name, co.shipping_phone,
       co.shipping_line1, co.shipping_line2, co.shipping_ward_code, co.shipping_ward_name, co.shipping_province_code,
       co.shipping_province_name, co.shipping_country_code, co.shipping_postal_code, a.recipient_name, a.phone, a.line1,
       a.line2, a.ward_code, a.ward, a.province_code, a.province, a.country, a.postal_code, pg_temp.staff(x.sl), x.at, 0,
       x.at, pg_temp.staff(x.sl)::text
  FROM x_ox_addr x JOIN ordering.customer_order co ON co.id = x.id JOIN customer.address a ON a.id = x.address_id;

UPDATE ordering.customer_order co
   SET shipping_recipient_name = a.recipient_name, shipping_phone = a.phone, shipping_line1 = a.line1,
       shipping_line2 = a.line2, shipping_ward_code = a.ward_code, shipping_ward_name = a.ward,
       shipping_province_code = a.province_code, shipping_province_name = a.province, shipping_country_code = a.country,
       shipping_postal_code = a.postal_code, version = co.version + 1, last_modified_at = x.at,
       last_modified_by = pg_temp.staff(x.sl)::text
  FROM x_ox_addr x JOIN customer.address a ON a.id = x.address_id
 WHERE co.id = x.id;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Resolved: back to where each was held from.
CREATE TEMP TABLE x_ox_resolve ON COMMIT DROP AS
SELECT o.key, o.id, o.sl, x.note, pg_temp.at(x.days, x.hhmi) AS at
  FROM x_ox_o o
  JOIN (VALUES ('hold_address', 'Đã cập nhật địa chỉ giao hàng mới theo xác nhận của khách', 1, '16:40'),
               ('hold_resolved', 'Khách xác nhận giữ nguyên số thùng theo hợp đồng', 6, '16:00'))
       AS x (role, note, days, hhmi) ON x.role = o.role;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('ox:order-history:' || x.key || ':resolved'), x.id, 'ON_HOLD', co.status_before_hold,
       'HOLD_RESOLVED_' || co.hold_reason || ': ' || x.note, pg_temp.staff(x.sl), x.at, x.at, pg_temp.staff(x.sl)::text
  FROM x_ox_resolve x JOIN ordering.customer_order co ON co.id = x.id;

UPDATE ordering.customer_order co
   SET status = co.status_before_hold, hold_reason = NULL, status_before_hold = NULL, hold_note = NULL,
       version = co.version + 1, last_modified_at = x.at, last_modified_by = pg_temp.staff(x.sl)::text
  FROM x_ox_resolve x WHERE co.id = x.id;

UPDATE ordering.order_hold h
   SET resolved_at = x.at, resolved_by = pg_temp.staff(x.sl), resolution_note = x.note, version = h.version + 1,
       last_modified_at = x.at, last_modified_by = pg_temp.staff(x.sl)::text
  FROM x_ox_resolve x WHERE h.id = pg_temp.sid('ox:hold:' || x.key || ':1');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 5. Partial shipments (PACK-PARTIAL; PartialShipmentService): Sales allow or stop shipping in parts. A row only when
--    the flag flips; the order's status at that moment.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ox_partial ON COMMIT DROP AS
SELECT o.key, o.id, o.sl, x.n, x.allowed, x.note, pg_temp.at(x.days, x.hhmi) AS at
  FROM x_ox_o o
  JOIN (VALUES ('partial_1', 1, TRUE, 'Khách đồng ý nhận trước phần hàng đã về kho, phần còn lại giao đợt sau', 1, '10:00'),
               ('partial_2', 1, TRUE, 'Khách cần gấp một phần hàng cho đợt khai trương', 4, '14:00'),
               ('partial_2', 2, FALSE, 'Khách đổi ý, muốn nhận một lần đủ hàng để tiết kiệm chuyến xe', 2, '09:00'),
               ('partial_3', 1, TRUE, 'Cho phép giao từng phần nếu nhà in trả hàng thành nhiều đợt', 3, '11:00'))
       AS x (role, n, allowed, note, days, hhmi) ON x.role = o.role;

INSERT INTO ordering.order_partial_shipment_decision (id, order_id, allowed, order_status, note, decided_by, decided_at,
                                                      version, created_at, created_by)
SELECT pg_temp.sid('ox:partial:' || x.key || ':' || x.n), x.id, x.allowed, co.status, x.note, pg_temp.staff(x.sl), x.at,
       0, x.at, pg_temp.staff(x.sl)::text
  FROM x_ox_partial x JOIN ordering.customer_order co ON co.id = x.id;

UPDATE ordering.customer_order co
   SET allow_partial_shipment = l.allowed, version = co.version + l.flips, last_modified_at = l.at,
       last_modified_by = pg_temp.staff(l.sl)::text
  FROM (SELECT DISTINCT ON (id) id, sl, allowed, at, count(*) OVER (PARTITION BY id) AS flips
          FROM x_ox_partial ORDER BY id, n DESC) l
 WHERE co.id = l.id;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 6. Delivery windows moved (17 BR-28; DeliveryBatchService.changeWindow): a later window, the customer's written
--    consent attached; a draft production order of the batch follows the new start (ProductionOrder.followWindow).
--    atrisk_window: the producer reported printing late first (section 7), so Sales moved the window (SELLER_DELAY).
-- ---------------------------------------------------------------------------------------------
-- The producer moves its expected completion past the need-by day (ProductionOrder.expectCompletion): AT_RISK.
CREATE TEMP TABLE x_ox_expect ON COMMIT DROP AS
SELECT o.key, o.id, o.po_id, x.n, p.need_by_date + x.late AS expected, pg_temp.at(x.days, x.hhmi) AS at
  FROM x_ox_o o JOIN ordering.production_order p ON p.id = o.po_id
  JOIN (VALUES ('atrisk_window', 1, 4, 2, '09:00'), ('atrisk_1', 1, 3, 1, '11:00'), ('atrisk_2', 1, 2, 1, '17:00'),
               ('atrisk_closed', 1, 3, 6, '10:00'), ('atrisk_closed', 2, -1, 3, '15:00'))
       AS x (role, n, late, days, hhmi) ON x.role = o.role;

UPDATE ordering.production_order p
   SET expected_completion_date = x.expected, version = p.version + 1, last_modified_at = x.at,
       last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM x_ox_expect x WHERE p.id = x.po_id AND x.n = 1;

-- Opened by the listener once the change committed.
INSERT INTO ordering.delay_alert (id, type, production_order_id, delivery_batch_id, sales_order_id, opened_at, closed_at)
SELECT pg_temp.sid('ox:delay-alert:' || x.key), 'AT_RISK', x.po_id, NULL, x.id, x.at + interval '2 seconds', NULL
  FROM x_ox_expect x WHERE x.n = 1;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- atrisk_closed: the producer caught up three days later; the alert closed itself.
UPDATE ordering.production_order p
   SET expected_completion_date = x.expected, version = p.version + 1, last_modified_at = x.at,
       last_modified_by = pg_temp.staff('dieuphoi')::text
  FROM x_ox_expect x WHERE p.id = x.po_id AND x.n = 2;

UPDATE ordering.delay_alert a
   SET closed_at = x.at + interval '2 seconds'
  FROM x_ox_expect x WHERE a.id = pg_temp.sid('ox:delay-alert:' || x.key) AND x.n = 2;

-- Two delivered orders' producers once reported a late day and caught up before printing ended: closed long ago.
INSERT INTO ordering.delay_alert (id, type, production_order_id, delivery_batch_id, sales_order_id, opened_at, closed_at)
SELECT pg_temp.sid('ox:delay-alert:' || o.key), 'AT_RISK', o.po_id, NULL, o.id, pg_temp.at(o.s - 6, '10:00'),
       pg_temp.at(o.s - 10, '15:00')
  FROM x_ox_o o WHERE o.role IN ('done_alert_1', 'done_alert_2');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

CREATE TEMP TABLE x_ox_window ON COMMIT DROP AS
SELECT o.key, o.id, o.sl, o.batch_id, o.po_id, o.order_number, b.window_start AS old_start, b.window_end AS old_end,
       b.window_start + x.shift AS new_start, b.window_end + x.shift AS new_end, x.reason, x.note,
       pg_temp.at(x.days, x.hhmi) AS at
  FROM x_ox_o o JOIN ordering.delivery_batch b ON b.id = o.batch_id
  JOIN (VALUES ('window_draft', 7, 'CUSTOMER_REQUEST', 'Khách xin lùi lịch nhận hàng một tuần vì kho bên khách đang sửa chữa', 1, '15:00'),
               ('window_sent', 7, 'CUSTOMER_REQUEST', 'Khách đề nghị giao sau ngày khai trương chi nhánh mới', 2, '11:00'),
               ('atrisk_window', 4, 'SELLER_DELAY', 'Nhà in báo chậm 4 ngày do bảo trì máy in; khách đồng ý lùi lịch giao', 1, '10:00'))
       AS x (role, shift, reason, note, days, hhmi) ON x.role = o.role;

INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
                                                   new_window_start, new_window_end, reason_code, note,
                                                   evidence_file_key, evidence_file_name, evidence_message_id,
                                                   changed_by, changed_at)
SELECT pg_temp.sid('ox:window-change:' || w.key), w.batch_id, w.id, w.old_start, w.old_end, w.new_start, w.new_end,
       w.reason, w.note, 'documents/showcase/window-changes/' || w.order_number || '-dot-1.pdf',
       'xac-nhan-doi-lich-giao-' || lower(w.order_number) || '.pdf', NULL, pg_temp.staff(w.sl), w.at
  FROM x_ox_window w;

UPDATE ordering.production_order p
   SET need_by_date = w.new_start, version = p.version + 1, last_modified_at = w.at,
       last_modified_by = pg_temp.staff(w.sl)::text
  FROM x_ox_window w WHERE p.id = w.po_id AND p.status = 'DRAFT';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 7. Completion holds (WF-05 GH-2; OrderCompletionHolds): a delivered order's automatic completion paused while a
--    delivery is checked with the carrier - one still open, two released.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ox_done ON COMMIT DROP AS
SELECT o.key, o.id, o.sl, x.reason, x.note, pg_temp.at(x.held, x.held_at) AS held_at, x.release_note,
       CASE WHEN x.released IS NOT NULL THEN pg_temp.at(x.released, '15:00') END AS released_at
  FROM x_ox_o o
  JOIN (VALUES ('done_disputed', 'DELIVERY_DISPUTED', 'Khách báo thiếu 2 thùng so với biên bản giao hàng; chờ kho đối chiếu với nhà xe', 10, '09:00', NULL, NULL),
               ('done_released', 'DELIVERY_DISPUTED', 'Khách báo một số thùng móp méo khi nhận', 21, '10:00',
                'Nhà xe gửi biên bản giao nhận có chữ ký của khách, hàng đủ và nguyên vẹn', 18),
               ('done_other', 'OTHER', 'Khách đề nghị chờ đối chiếu công nợ cuối tháng trước khi hoàn tất đơn', 39, '11:00',
                'Đã đối chiếu công nợ xong với kế toán bên khách', 30))
       AS x (role, reason, note, held, held_at, release_note, released) ON x.role = o.role;

INSERT INTO ordering.order_completion_hold (id, order_id, reason, note, held_at, held_by, version, created_at,
                                            created_by)
SELECT pg_temp.sid('ox:completion-hold:' || d.key), d.id, d.reason, d.note, d.held_at, pg_temp.staff(d.sl), 0, d.held_at,
       pg_temp.staff(d.sl)::text
  FROM x_ox_done d;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE ordering.order_completion_hold h
   SET released_at = d.released_at, released_by = pg_temp.staff(d.sl), release_note = d.release_note,
       version = h.version + 1, last_modified_at = d.released_at, last_modified_by = pg_temp.staff(d.sl)::text
  FROM x_ox_done d WHERE h.id = pg_temp.sid('ox:completion-hold:' || d.key) AND d.released_at IS NOT NULL;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 8. Customers' onboarding (18 §4; CustomerOnboardingService): Sales created each one PENDING_ACTIVATION with an
--    invitation to its contact's portal account (72 hours), mailed at once; the contact chose a password the same day
--    and the customer became ACTIVE. Two newer customers have not activated yet: one invitation open, one expired and
--    sent again.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ox_cust ON COMMIT DROP AS
SELECT c.code, c.idx, c.id, c.username, c.sales, c.since, c.name, c.contact, c.email, c.tax_code, c.credit_limit,
       c.term_days, pg_temp.at(c.since, '09:00') AS invited_at,
       pg_temp.at(c.since, '09:00') + make_interval(hours => 1 + pg_temp.pick('ox:activate-h:' || c.code, 0, 6),
                                                    mins => pg_temp.pick('ox:activate-m:' || c.code, 0, 59)) AS activated_at
  FROM s_customer c;

INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at, consumed_at, revoked_at,
                                        issued_by, delivery_status, delivery_attempted_at, version, created_at,
                                        created_by)
SELECT pg_temp.sid('ox:invitation:' || c.code), pg_temp.sid('user:' || c.username),
       encode(sha256(convert_to('showcase:ox:invitation:' || c.code, 'UTF8')), 'hex'), c.invited_at,
       c.invited_at + interval '72 hours', c.activated_at, NULL, pg_temp.staff(c.sales), 'SENT',
       c.invited_at + interval '4 seconds', 2, c.invited_at, pg_temp.staff(c.sales)::text
  FROM x_ox_cust c;

INSERT INTO customer.customer_status_history (id, customer_id, from_status, to_status, reason, changed_by, changed_at,
                                              version, created_at, created_by)
SELECT pg_temp.sid('ox:customer-status:' || c.code || ':0'), c.id, NULL, 'PENDING_ACTIVATION', NULL,
       pg_temp.staff(c.sales), c.invited_at, 0, c.invited_at, pg_temp.staff(c.sales)::text
  FROM x_ox_cust c
UNION ALL
SELECT pg_temp.sid('ox:customer-status:' || c.code || ':1'), c.id, 'PENDING_ACTIVATION', 'ACTIVE', NULL,
       pg_temp.sid('user:' || c.username), c.activated_at, 0, c.activated_at, pg_temp.sid('user:' || c.username)::text
  FROM x_ox_cust c;

UPDATE customer.customer cu
   SET version = cu.version + 1, last_modified_at = greatest(cu.last_modified_at, c.activated_at),
       last_modified_by = CASE WHEN cu.last_modified_at > c.activated_at THEN cu.last_modified_by
                               ELSE pg_temp.sid('user:' || c.username)::text END
  FROM x_ox_cust c WHERE cu.id = c.id;

UPDATE identity.app_user u
   SET version = u.version + 1, last_modified_at = greatest(u.last_modified_at, c.activated_at),
       last_modified_by = CASE WHEN u.last_modified_at > c.activated_at THEN u.last_modified_by
                               ELSE pg_temp.sid('user:' || c.username)::text END
  FROM x_ox_cust c WHERE u.id = pg_temp.sid('user:' || c.username);

-- Two customers still to activate: their contact's account has no password yet (User.inviteCustomer).
CREATE TEMP TABLE x_ox_new ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('TDP', 'Công ty TNHH Trà Đào Phố Núi', 'Hà Thị Đào', 'tradaophonui@khachhang.example', '0912664081',
     '0319011203', 'sales1', 1),
    ('BTT', 'Hộ kinh doanh Bánh Tráng Trộn Cô Ba', 'Lý Thị Ba', 'banhtrangcoba@khachhang.example', '0938227415',
     NULL, 'sales2', 6)) AS t (code, name, contact, email, phone, tax_code, sales, created);

INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
SELECT pg_temp.sid('ox:user:' || n.code), n.email, n.email, NULL, n.contact, 'ACTIVE', 0, pg_temp.at(n.created, '10:00'),
       pg_temp.staff(n.sales)::text
  FROM x_ox_new n;

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT pg_temp.sid('ox:user-role:' || n.code), pg_temp.sid('ox:user:' || n.code), r.id, 0, pg_temp.at(n.created, '10:00'),
       pg_temp.staff(n.sales)::text
  FROM x_ox_new n JOIN identity.app_role r ON r.code = 'CUSTOMER';

INSERT INTO customer.customer (id, full_name, email, phone, status, version, created_at, created_by, user_id, tax_code)
SELECT pg_temp.sid('ox:customer:' || n.code), n.name, n.email, n.phone, 'PENDING_ACTIVATION', 0,
       pg_temp.at(n.created, '10:00'), pg_temp.staff(n.sales)::text, pg_temp.sid('ox:user:' || n.code), n.tax_code
  FROM x_ox_new n;

INSERT INTO customer.customer_status_history (id, customer_id, from_status, to_status, reason, changed_by, changed_at,
                                              version, created_at, created_by)
SELECT pg_temp.sid('ox:customer-status:' || n.code || ':0'), pg_temp.sid('ox:customer:' || n.code), NULL,
       'PENDING_ACTIVATION', NULL, pg_temp.staff(n.sales), pg_temp.at(n.created, '10:00'), 0,
       pg_temp.at(n.created, '10:00'), pg_temp.staff(n.sales)::text
  FROM x_ox_new n;

-- TDP's invitation is open; BTT's first one expired unused and was revoked when Sales sent it again.
INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at, consumed_at, revoked_at,
                                        issued_by, delivery_status, delivery_attempted_at, version, created_at,
                                        created_by)
SELECT pg_temp.sid('ox:invitation:' || n.code || ':1'), pg_temp.sid('ox:user:' || n.code),
       encode(sha256(convert_to('showcase:ox:invitation:' || n.code || ':1', 'UTF8')), 'hex'),
       pg_temp.at(n.created, '10:00'), pg_temp.at(n.created, '10:00') + interval '72 hours', NULL,
       CASE WHEN n.code = 'BTT' THEN pg_temp.at(2, '09:30') END, pg_temp.staff(n.sales), 'SENT',
       pg_temp.at(n.created, '10:00') + interval '3 seconds', CASE WHEN n.code = 'BTT' THEN 2 ELSE 1 END,
       pg_temp.at(n.created, '10:00'), pg_temp.staff(n.sales)::text
  FROM x_ox_new n;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at, consumed_at, revoked_at,
                                        issued_by, delivery_status, delivery_attempted_at, version, created_at,
                                        created_by)
SELECT pg_temp.sid('ox:invitation:' || n.code || ':2'), pg_temp.sid('ox:user:' || n.code),
       encode(sha256(convert_to('showcase:ox:invitation:' || n.code || ':2', 'UTF8')), 'hex'),
       pg_temp.at(2, '09:30'), pg_temp.at(2, '09:30') + interval '72 hours', NULL, NULL, pg_temp.staff(n.sales), 'SENT',
       pg_temp.at(2, '09:30') + interval '3 seconds', 1, pg_temp.at(2, '09:30'), pg_temp.staff(n.sales)::text
  FROM x_ox_new n WHERE n.code = 'BTT';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 9. Profile edits before the first quote (CustomerServiceImpl.update, CustomerLegalProfileService): the value after is
--    what the profile says today.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ox_change ON COMMIT DROP AS
SELECT c.code, c.id, x.n, x.field, x.old_value,
       CASE x.field WHEN 'FULL_NAME' THEN cu.full_name WHEN 'TAX_CODE' THEN cu.tax_code
                    WHEN 'REGISTERED_ADDRESS' THEN lp.registered_address
                    WHEN 'REPRESENTATIVE' THEN lp.representative_name
                    WHEN 'REPRESENTATIVE_TITLE' THEN lp.representative_title
                    WHEN 'INVOICE_EMAIL' THEN lp.invoice_email END AS new_value,
       CASE WHEN x.by_contact THEN pg_temp.sid('user:' || c.username) ELSE pg_temp.staff(c.sales) END AS by,
       pg_temp.at(c.since - x.after, x.hhmi) AS at
  FROM x_ox_cust c JOIN customer.customer cu ON cu.id = c.id
  JOIN customer.customer_legal_profile lp ON lp.customer_id = c.id
  JOIN (VALUES ('CPM', 1, 'FULL_NAME', 'Công ty TNHH Cà Phê Mộc Sài Gòn', TRUE, 2, '10:15'),
               ('CPM', 2, 'REGISTERED_ADDRESS', '45 Lê Lợi, Phường Bến Nghé, Quận 1, TP. Hồ Chí Minh', FALSE, 2, '10:20'),
               ('TSM', 1, 'REPRESENTATIVE', 'Trần Văn Hải', FALSE, 3, '14:00'),
               ('TSM', 2, 'REPRESENTATIVE_TITLE', 'Chủ tịch Hội đồng quản trị', FALSE, 3, '14:00'),
               ('BMV', 1, 'INVOICE_EMAIL', 'ketoan.banhmiviet@gmail.com', TRUE, 1, '16:30'),
               ('BMV', 2, 'REGISTERED_ADDRESS', '102 Nguyễn Trãi, Phường Phạm Ngũ Lão, Quận 1, TP. Hồ Chí Minh', FALSE, 4, '09:45'),
               ('NEX', 1, 'TAX_CODE', '0318800841', FALSE, 1, '11:00'),
               ('LTH', 1, 'FULL_NAME', 'Công ty TNHH Lẩu Thái Siam', TRUE, 2, '15:10'),
               ('TCP', 1, 'REGISTERED_ADDRESS', '5 Nhà Chung, Phường Hàng Trống, Quận Hoàn Kiếm, TP. Hà Nội', FALSE, 3, '10:00'),
               ('TCP', 2, 'INVOICE_EMAIL', 'hoadon.trachanhpho@gmail.com', TRUE, 2, '08:40'))
       AS x (code, n, field, old_value, by_contact, after, hhmi) ON x.code = c.code;

INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, new_value, changed_by, changed_at,
                                              created_at)
SELECT pg_temp.sid('ox:customer-change:' || x.code || ':' || x.n), x.id, x.field, x.old_value, x.new_value, x.by, x.at,
       x.at
  FROM x_ox_change x;

UPDATE customer.customer cu
   SET version = cu.version + y.edits, last_modified_at = greatest(cu.last_modified_at, y.at),
       last_modified_by = CASE WHEN y.at > cu.last_modified_at THEN y.by::text ELSE cu.last_modified_by END
  FROM (SELECT DISTINCT ON (id) id, at, by, count(*) OVER (PARTITION BY id) AS edits
          FROM x_ox_change WHERE field IN ('FULL_NAME', 'TAX_CODE') ORDER BY id, at DESC) y
 WHERE cu.id = y.id;

UPDATE customer.customer_legal_profile lp
   SET version = lp.version + y.edits, last_modified_at = y.at, last_modified_by = y.by::text
  FROM (SELECT DISTINCT ON (id) id, at, by, dense_rank() OVER (PARTITION BY id ORDER BY at) AS edits
          FROM x_ox_change WHERE field NOT IN ('FULL_NAME', 'TAX_CODE') ORDER BY id, at DESC) y
 WHERE lp.customer_id = y.id;

-- ---------------------------------------------------------------------------------------------
-- 10. Customer groups (CustomerSegmentService): created by the sales manager, every customer put in one.
-- ---------------------------------------------------------------------------------------------
INSERT INTO customer.segment (id, code, name, description, version, created_at, created_by)
SELECT pg_temp.sid('ox:segment:' || s.code), s.code, s.name, s.description, 0, pg_temp.at(100, '09:00'),
       pg_temp.staff('truongkd')::text
  FROM (VALUES ('CHUOI-CA-PHE-TRA', 'Chuỗi cà phê & trà', 'Chuỗi cà phê, trà sữa, trà chanh: ly giấy, ly nhựa PET in logo, đặt đều hằng tháng'),
               ('NHA-HANG-QUAN-AN', 'Nhà hàng & quán ăn', 'Nhà hàng, quán ăn, căn tin: tô giấy kraft, túi giấy mang về'),
               ('BANH-KEM', 'Tiệm bánh & kem', 'Tiệm bánh, kem, chè: hộp bánh, ly kem in theo mùa'),
               ('DO-UONG-DONG-CHAI', 'Nước ép & đồ uống', 'Nước ép, sinh tố, sữa hạt, kombucha: ly PET và túi giấy'),
               ('KHACH-CHIEN-LUOC', 'Khách chiến lược', 'Đơn đều, sản lượng lớn, được ưu tiên lịch in và hạn mức tín dụng'))
       AS s (code, name, description);

UPDATE customer.customer cu
   SET segment_id = pg_temp.sid('ox:segment:' || g.segment), version = cu.version + 1,
       last_modified_at = greatest(cu.last_modified_at, pg_temp.at(least(c.since, 99), '16:00')),
       last_modified_by = CASE WHEN pg_temp.at(least(c.since, 99), '16:00') > cu.last_modified_at
                               THEN pg_temp.staff('truongkd')::text ELSE cu.last_modified_by END
  FROM x_ox_cust c
  JOIN (SELECT code, CASE
                   WHEN idx <= 3 THEN 'KHACH-CHIEN-LUOC'
                   WHEN name ~ '(Cà Phê|Trà)' THEN 'CHUOI-CA-PHE-TRA'
                   WHEN name ~ '(Bánh|Bakery|Kem|Chè|Tào Phớ|Sữa Chua)' THEN 'BANH-KEM'
                   WHEN name ~ '(Nước|Sinh Tố|Kombucha|Sữa Hạt|Trái Cây)' THEN 'DO-UONG-DONG-CHAI'
                   ELSE 'NHA-HANG-QUAN-AN' END AS segment
          FROM x_ox_cust) g ON g.code = c.code
 WHERE cu.id = c.id;

-- ---------------------------------------------------------------------------------------------
-- 11. Default payment schedules (18 §4 step 5; PaymentScheduleService): what a new contract's plan starts from. On
--     credit: 30% on signing, 70% after delivery within the credit term, approved by accounting (15 BR-12); without:
--     the rest before delivery. CHE's approved 30/70 was replaced three days ago by 20/80, which waits for accounting.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ox_schedule ON COMMIT DROP AS
SELECT c.code, c.id, c.sales, c.credit_limit IS NOT NULL AS credit, c.term_days,
       pg_temp.at(least(c.since - 1, 80), '11:00') AS created_at,
       CASE WHEN c.code = 'CHE' THEN pg_temp.at(3, '11:00') ELSE pg_temp.at(least(c.since - 1, 80), '11:00') END AS set_at,
       CASE WHEN c.credit_limit IS NOT NULL AND c.code <> 'CHE' THEN pg_temp.at(least(c.since - 1, 80) - 1, '09:30') END
           AS approved_at,
       CASE WHEN c.code = 'CHE' THEN 20 ELSE 30 END AS first_percent
  FROM x_ox_cust c WHERE c.idx <= 24;

INSERT INTO customer.default_payment_schedule (id, customer_id, set_by, set_at, approved_by, approved_at, note, version,
                                               created_at, created_by, last_modified_at, last_modified_by)
SELECT pg_temp.sid('ox:payment-schedule:' || s.code), s.id, pg_temp.staff(s.sales), s.set_at,
       CASE WHEN s.approved_at IS NOT NULL THEN pg_temp.staff('ketoan') END, s.approved_at,
       CASE WHEN s.code = 'CHE' THEN 'Khách đặt đều mỗi tháng, đề nghị giảm cọc từ 30% còn 20%; chờ kế toán duyệt lại'
            WHEN s.credit THEN 'Theo hạn mức tín dụng đã duyệt'
            ELSE 'Khách chưa có hạn mức tín dụng: thanh toán đủ trước khi giao' END,
       CASE WHEN s.code = 'CHE' THEN 2 WHEN s.approved_at IS NOT NULL THEN 1 ELSE 0 END, s.created_at,
       pg_temp.staff(s.sales)::text,
       coalesce(s.approved_at, s.set_at),
       CASE WHEN s.approved_at IS NOT NULL THEN pg_temp.staff('ketoan')::text ELSE pg_temp.staff(s.sales)::text END
  FROM x_ox_schedule s;

INSERT INTO customer.default_payment_installment (schedule_id, seq, name, percent, milestone, due_days)
SELECT pg_temp.sid('ox:payment-schedule:' || s.code), 1, 'Đặt cọc khi ký', s.first_percent, 'ON_SIGNING', 3
  FROM x_ox_schedule s
UNION ALL
SELECT pg_temp.sid('ox:payment-schedule:' || s.code), 2,
       CASE WHEN s.credit THEN 'Thanh toán sau giao hàng' ELSE 'Thanh toán trước giao hàng' END, 100 - s.first_percent,
       CASE WHEN s.credit THEN 'AFTER_DELIVERY' ELSE 'BEFORE_DELIVERY' END, CASE WHEN s.credit THEN s.term_days ELSE 0 END
  FROM x_ox_schedule s;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 12. A customer blocked and unblocked, one closed and reopened (CustomerServiceImpl.changeStatus): the contact's
--     portal account is suspended with it and reinstated (PortalAccountServiceImpl.suspend / reinstate).
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ox_status ON COMMIT DROP AS
SELECT c.code, c.id, c.username, x.n, x.from_status, x.to_status, x.reason, pg_temp.at(x.days, x.hhmi) AS at
  FROM x_ox_cust c
  JOIN (VALUES ('SUH', 2, 'ACTIVE', 'BLOCKED', 'Khách đổi địa chỉ trụ sở nhưng chưa gửi giấy phép kinh doanh cập nhật; tạm khoá đặt hàng chờ hồ sơ', 48, '10:00'),
               ('SUH', 3, 'BLOCKED', 'ACTIVE', 'Khách đã gửi giấy phép kinh doanh cập nhật địa chỉ trụ sở mới', 38, '14:30'),
               ('CTS', 2, 'ACTIVE', 'INACTIVE', 'Khách tạm ngừng kinh doanh để sửa chữa mặt bằng', 45, '09:00'),
               ('CTS', 3, 'INACTIVE', 'ACTIVE', 'Khách mở cửa lại sau sửa chữa, đề nghị hợp tác tiếp', 15, '10:30'))
       AS x (code, n, from_status, to_status, reason, days, hhmi) ON x.code = c.code;

INSERT INTO customer.customer_status_history (id, customer_id, from_status, to_status, reason, changed_by, changed_at,
                                              version, created_at, created_by)
SELECT pg_temp.sid('ox:customer-status:' || s.code || ':' || s.n), s.id, s.from_status, s.to_status, s.reason,
       pg_temp.staff('truongkd'), s.at, 0, s.at, pg_temp.staff('truongkd')::text
  FROM x_ox_status s;

UPDATE customer.customer cu
   SET version = cu.version + 2, last_modified_at = greatest(cu.last_modified_at, l.at),
       last_modified_by = CASE WHEN l.at > cu.last_modified_at THEN pg_temp.staff('truongkd')::text
                               ELSE cu.last_modified_by END
  FROM (SELECT id, max(at) AS at FROM x_ox_status GROUP BY id) l
 WHERE cu.id = l.id;

UPDATE identity.app_user u
   SET version = u.version + 2, last_modified_at = l.at, last_modified_by = pg_temp.staff('truongkd')::text
  FROM (SELECT username, max(at) AS at FROM x_ox_status GROUP BY username) l
 WHERE u.id = pg_temp.sid('user:' || l.username);

-- ---------------------------------------------------------------------------------------------
-- 13. Staff accounts locked and disabled by the administrator (IdentityServiceImpl.changeUserStatus): nv.bikhoa stays
--     LOCKED and nv.nghiviec DISABLED, as the base made them; kho.bd2 was locked for an hour and unlocked.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ox_user ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('kho.bd2', 1, 'LOCK', 'ACTIVE', 'LOCKED', 'Có lượt đăng nhập lạ ngoài giờ làm việc; khoá tạm chờ xác minh với nhân viên', 40, '06:10'),
    ('kho.bd2', 2, 'UNLOCK', 'LOCKED', 'ACTIVE', 'Đã xác minh: nhân viên đăng nhập từ điện thoại mới của chính mình', 40, '06:45'),
    ('nv.nghiviec', 1, 'DISABLE', 'ACTIVE', 'DISABLED', 'Nhân viên đã nghỉ việc theo quyết định của phòng nhân sự', 20, '17:00'),
    ('nv.bikhoa', 1, 'LOCK', 'ACTIVE', 'LOCKED', 'Đăng nhập sai mật khẩu nhiều lần từ thiết bị lạ; khoá chờ xác minh', 6, '08:45'))
    AS t (username, n, action, from_status, to_status, reason, days, hhmi);

INSERT INTO identity.user_status_change (id, user_id, action, from_status, to_status, reason, actor_id, actor_username,
                                         changed_at, version, created_at, created_by)
SELECT pg_temp.sid('ox:user-status:' || x.username || ':' || x.n), pg_temp.staff(x.username), x.action, x.from_status,
       x.to_status, x.reason, pg_temp.staff('admin'), 'admin', pg_temp.at(x.days, x.hhmi), 0, pg_temp.at(x.days, x.hhmi),
       pg_temp.staff('admin')::text
  FROM x_ox_user x;

UPDATE identity.app_user u
   SET version = u.version + l.changes, last_modified_at = l.at, last_modified_by = pg_temp.staff('admin')::text
  FROM (SELECT username, count(*) AS changes, max(pg_temp.at(days, hhmi)) AS at FROM x_ox_user GROUP BY username) l
 WHERE u.username = l.username;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- =============================================================================================
-- 30. Inventory and warehouse operations (group iop): putaway trips, carton moves by hand, cycle counts by carton
--     and the adjustments they raise, adjustments asked by hand (kltn-docs 05 §4.5, 11, 20 §4.2-§4.3)
-- =============================================================================================
-- Orders touched (h_order): the HELD orders with i >= 4 (seq 93, 94, 95: all received, held by SKU, not released).
--   * 94: one carton not found by a count, written off when the manager approved (carton LOST), and the order's SKU
--     hold cut by one carton afterwards, as CountWriteOffListener -> ShippingWarehouseService.giveUpUncovered ->
--     InventoryService.shrinkHold does: the order is one carton short again. A second carton not found waits for approval.
--   * 93: cartons moved by hand; a carton not found whose variance the manager rejected and sent for recount; a carton
--     reported lost by hand and withdrawn by its requester.
--   * 95: cartons moved by hand (consolidated); a damage write-off rejected and another still pending; a count of its
--     bins in progress.
-- READY orders (96, 99, 100) are only counted, by counts already closed: nothing about their cartons changes.
-- Putaway trips group DONE putaway tasks of the base; no task changes.
-- warehouse.move_task and warehouse.slotting_rule stay empty: nothing in the application writes or reads them (a move
-- by hand is ledger lines with reference MOVE_TASK and the request id, StockOperationsServiceImpl.relocatePackages;
-- SlottingRuleJpaRepository is a starter stub nothing injects).

-- A name-based UUID as java.util.UUID.nameUUIDFromBytes makes it (version 3): the request id of a shrunk hold.
CREATE FUNCTION pg_temp.iop_uuid3(t TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT (substr(h, 1, 12) || '3' || substr(h, 14, 3)
           || to_hex((('x' || substr(h, 17, 1))::bit(4)::int & 3) | 8) || substr(h, 18))::uuid
     FROM (SELECT md5(t) AS h) x $$;

-- The plan this section rests on: which orders are HELD with i >= 4 and which READY, as the base built them.
DO $$
BEGIN
    IF (SELECT string_agg(seq::text, ',' ORDER BY seq) FROM h_order WHERE stage = 'HELD' AND i >= 4) <> '93,94,95'
       OR (SELECT string_agg(seq::text, ',' ORDER BY seq) FROM h_order WHERE stage = 'READY') <> '96,97,98,99,100' THEN
        RAISE EXCEPTION 'iop: the base''s HELD / READY orders are not the ones this section was written for';
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 30.1 Putaway trips (05 §4.5): the clerk scans cartons of a receipt onto a cart, sets off (each carton assigned the
--      bin it went into), puts them away one by one; the trip completes with the last one. Now and then a trip is
--      closed with a carton still on the cart (it did not fit the cart's route), which goes on the next trip.
--      The receipts of the last two weeks, one per clerk and day (every receipt's putaway starts at 10:00).
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_iop_receipt ON COMMIT DROP AS
SELECT DISTINCT ON (op, d) g, op, d, wh
  FROM (SELECT t.goods_receipt_id AS g, t.last_modified_by AS op,
               (t.last_modified_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d, gr.warehouse_id AS wh, count(*) AS n
          FROM warehouse.putaway_task t JOIN procurement.goods_receipts gr ON gr.id = t.goods_receipt_id
         WHERE t.status = 'DONE' AND t.source = 'RECEIPT'
           AND t.last_modified_at >= pg_temp.at(14, '00:00')
         GROUP BY 1, 2, 3, 4) x
 ORDER BY op, d, n DESC, g;

CREATE TEMP TABLE x_iop_task ON COMMIT DROP AS
SELECT r.g, r.op::uuid AS op, r.wh, t.id AS task_id, t.target_location_id AS bin_id, t.last_modified_at AS placed,
       row_number() OVER (PARTITION BY r.g ORDER BY t.last_modified_at, t.id) AS rk,
       8 + pg_temp.pick('iop:trip-size:' || r.g, 0, 4) AS size
  FROM x_iop_receipt r JOIN warehouse.putaway_task t ON t.goods_receipt_id = r.g AND t.status = 'DONE';
ALTER TABLE x_iop_task ADD COLUMN trip INTEGER;
UPDATE x_iop_task SET trip = (rk - 1) / size;

-- One row per trip; a trip of a receipt chosen to show a closed cart is the first one, when another follows it.
CREATE TEMP TABLE x_iop_trip ON COMMIT DROP AS
SELECT g, trip, op, wh, min(placed) AS f, max(placed) AS l, count(*) AS n,
       trip = 0 AND pg_temp.rnd('iop:trip-closed:' || g) < 0.25
           AND EXISTS (SELECT 1 FROM x_iop_task x2 WHERE x2.g = x.g AND x2.trip = 1) AS closed,
       pg_temp.sid('iop:trip:' || g || ':' || trip) AS id
  FROM x_iop_task x GROUP BY g, trip, op, wh;

-- The items: the trip's own cartons, plus on a closed trip the first carton of the next one, taken back off.
CREATE TEMP TABLE x_iop_item ON COMMIT DROP AS
SELECT tr.id AS trip_id, t.task_id, t.bin_id, t.rk, t.placed, FALSE AS returned,
       row_number() OVER (PARTITION BY tr.id ORDER BY t.rk) - 1 AS j
  FROM x_iop_trip tr JOIN x_iop_task t ON t.g = tr.g AND t.trip = tr.trip
UNION ALL
SELECT tr.id, t.task_id, t.bin_id, t.rk, NULL, TRUE, tr.n
  FROM x_iop_trip tr JOIN x_iop_task t ON t.g = tr.g AND t.trip = 1 AND t.rk = (SELECT min(rk) FROM x_iop_task t3 WHERE t3.g = tr.g AND t3.trip = 1)
 WHERE tr.closed;

-- A clerk has one cart open at a time (uk_putaway_trip_open_operator) and a carton is on one open cart (uk_putaway_
-- trip_item_on_cart), so the trips go in rounds: the k-th trip of every clerk, each opened, loaded, sent off, put
-- away and completed (or closed) before that clerk's next one. Opened 45 s before the first carton goes into its
-- bin, a carton scanned every 3 s, off 6 s before the first stop.
ALTER TABLE x_iop_trip ADD COLUMN rn INTEGER;
UPDATE x_iop_trip t SET rn = o.rn
  FROM (SELECT id, row_number() OVER (PARTITION BY op ORDER BY f) AS rn FROM x_iop_trip) o WHERE o.id = t.id;

DO $$
DECLARE
    k INTEGER;
BEGIN
    FOR k IN 1 .. (SELECT max(rn) FROM x_iop_trip) LOOP
        INSERT INTO warehouse.putaway_trip (id, warehouse_id, operator_id, status, start_location_id, started_at,
                                            last_activity_at, closed_at, closed_by, version, created_at, created_by)
        SELECT tr.id, tr.wh, tr.op, 'LOADING', NULL, NULL, tr.f - interval '45 seconds', NULL, NULL, 0,
               tr.f - interval '45 seconds', tr.op::text
          FROM x_iop_trip tr WHERE tr.rn = k;

        INSERT INTO warehouse.putaway_trip_item (id, trip_id, putaway_task_id, status, assigned_location_id,
                                                 stop_sequence, hold_released_at, loaded_at, placed_at, version,
                                                 created_at, created_by)
        SELECT pg_temp.sid('iop:trip-item:' || i.trip_id || ':' || i.task_id), i.trip_id, i.task_id, 'ON_CART', NULL,
               NULL, NULL, tr.f - interval '45 seconds' + (i.j * 3 || ' seconds')::interval, NULL, 0,
               tr.f - interval '45 seconds' + (i.j * 3 || ' seconds')::interval, tr.op::text
          FROM x_iop_item i JOIN x_iop_trip tr ON tr.id = i.trip_id WHERE tr.rn = k;

        -- Set off: every carton on the cart assigned its bin, the route ordered from the receiving dock.
        UPDATE warehouse.putaway_trip tr
           SET status = 'IN_PROGRESS', started_at = x.f - interval '6 seconds',
               start_location_id = (SELECT s.id FROM warehouse.storage_location s
                                      JOIN warehouse.warehouse w ON w.id = s.warehouse_id
                                     WHERE s.warehouse_id = x.wh AND s.location_code = w.prefix || '-RCV01'),
               last_activity_at = x.f - interval '6 seconds', version = x.n + 1 + x.closed::int,
               last_modified_at = x.f - interval '6 seconds', last_modified_by = x.op::text
          FROM x_iop_trip x WHERE tr.id = x.id AND x.rn = k;

        UPDATE warehouse.putaway_trip_item it
           SET assigned_location_id = i.bin_id, stop_sequence = i.stop, version = 1,
               last_modified_at = x.f - interval '6 seconds', last_modified_by = x.op::text
          FROM (SELECT i2.*, dense_rank() OVER (PARTITION BY i2.trip_id ORDER BY i2.rk) AS stop FROM x_iop_item i2) i
          JOIN x_iop_trip x ON x.id = i.trip_id
         WHERE x.rn = k AND it.trip_id = i.trip_id AND it.putaway_task_id = i.task_id;

        -- Each carton put away at the time its task was done: the stops still ahead are renumbered from that bin,
        -- so the carton put away was always the first stop (PutawayTripsImpl.taskPutAway -> renumber).
        UPDATE warehouse.putaway_trip_item it
           SET status = 'PLACED', placed_at = i.placed, hold_released_at = i.placed, stop_sequence = 1, version = 2,
               last_modified_at = i.placed, last_modified_by = x.op::text
          FROM x_iop_item i JOIN x_iop_trip x ON x.id = i.trip_id
         WHERE x.rn = k AND it.trip_id = i.trip_id AND it.putaway_task_id = i.task_id AND NOT i.returned;

        UPDATE warehouse.putaway_trip tr
           SET status = 'COMPLETED', closed_at = x.l, last_activity_at = x.l, version = 2 * x.n + 1,
               last_modified_at = x.l, last_modified_by = x.op::text
          FROM x_iop_trip x WHERE tr.id = x.id AND x.rn = k AND NOT x.closed;

        -- Closed by the clerk 10 s after the last carton that went in: the one left on the cart goes back to the
        -- dock, its space given back, and its task waits for the next cart.
        UPDATE warehouse.putaway_trip_item it
           SET status = 'RETURNED', hold_released_at = x.l + interval '10 seconds', stop_sequence = 1, version = 2,
               last_modified_at = x.l + interval '10 seconds', last_modified_by = x.op::text
          FROM x_iop_item i JOIN x_iop_trip x ON x.id = i.trip_id
         WHERE x.rn = k AND it.trip_id = i.trip_id AND it.putaway_task_id = i.task_id AND i.returned;

        UPDATE warehouse.putaway_trip tr
           SET status = 'CLOSED', closed_at = x.l + interval '10 seconds', closed_by = x.op,
               last_activity_at = x.l + interval '10 seconds', version = 2 * x.n + 2,
               last_modified_at = x.l + interval '10 seconds', last_modified_by = x.op::text
          FROM x_iop_trip x WHERE tr.id = x.id AND x.rn = k AND x.closed;
    END LOOP;
END $$;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 30.2 The cartons this section names (h_carton: order seq and carton number n), read with their rows as they stand
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_iop_carton ON COMMIT DROP AS
SELECT c.seq, c.n, c.key, c.ln, c.line_id, c.package_id, c.stock_item_id, c.lpn, c.sku, c.upc, s.lot_number, s.lot_id
  FROM h_carton c JOIN inventory.stock_item s ON s.id = c.stock_item_id
 WHERE c.seq IN (93, 94, 95, 96, 99, 100);

-- Where a carton is now (it follows the moves below).
CREATE FUNCTION pg_temp.iop_bin(p_seq INTEGER, p_n INTEGER) RETURNS TEXT LANGUAGE sql STABLE AS
$$ SELECT p.location_code FROM inventory.stock_package p JOIN h_carton c ON c.package_id = p.id
    WHERE c.seq = p_seq AND c.n = p_n $$;

DO $$
BEGIN
    -- Every carton named below is in stock in a bin, its row AVAILABLE; those of the HELD orders are held by SKU only.
    IF EXISTS (SELECT 1 FROM x_iop_carton x JOIN inventory.stock_package p ON p.id = x.package_id
                 JOIN inventory.stock_item s ON s.id = x.stock_item_id
                WHERE p.status <> 'IN_STOCK' OR s.status <> 'AVAILABLE' OR p.location_code NOT LIKE 'BDG-_%-%-%'
                   OR (x.seq IN (93, 94, 95) AND s.reserved <> 0)) THEN
        RAISE EXCEPTION 'iop: a carton of orders 93-100 is not in stock in a BDG bin as expected';
    END IF;
    -- TUIL-NUD is order 94's alone, held by one SKU hold: the write-off below cuts that hold only.
    IF (SELECT count(*) FROM inventory.stock_reservation WHERE sku = 'TUIL-NUD' AND status = 'HELD') <> 1
       OR (SELECT count(*) FROM x_iop_carton WHERE seq = 94 AND sku = 'TUIL-NUD') <> 14 THEN
        RAISE EXCEPTION 'iop: TUIL-NUD is not held by order 94 alone';
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 30.3 Counts closed earlier in the week: two completed with every carton present, one cancelled before counting
--      (20 §4.2: planned by SKU, which means the SKU's cartons in bins; blind unless said otherwise)
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_iop_count (code TEXT PRIMARY KEY, number TEXT, days INTEGER, planned TEXT, trigger_type TEXT,
                               blind BOOLEAN, assignee TEXT, planner TEXT, note TEXT) ON COMMIT DROP;
INSERT INTO x_iop_count VALUES
    ('A2', 'CC-' || pg_temp.ymd(4) || '-7001', 4, '13:30', 'MANUAL', FALSE, 'kho.bd1', 'quanly.bd',
     'Kiểm kê hàng đơn của khách SDT trước ngày giải phóng xuất kho'),
    ('A', 'CC-' || pg_temp.ymd(3) || '-7001', 3, '08:00', 'MANUAL', TRUE, 'kho.bd2', 'quanly.bd',
     'Kiểm kê định kỳ khu B - mã TUIL-HNH'),
    ('F', 'CC-' || pg_temp.ymd(3) || '-7002', 3, '10:00', 'MANUAL', TRUE, 'kho.bd1', 'quanly.bd',
     'Kiểm kê định kỳ khu B - mã TUIL-KTU'),
    ('B', 'CC-' || pg_temp.ymd(1) || '-7001', 1, '08:00', 'MANUAL', TRUE, 'kho.bd1', 'quanly.bd',
     'Kiểm kê mã TUIL-NUD sau khi nhận hàng của lệnh sản xuất'),
    ('C', 'CC-' || pg_temp.ymd(1) || '-7002', 1, '09:00', 'MANUAL', TRUE, 'kho.bd2', 'quanly.bd',
     'Kiểm kê mã TOG750-MIQ sau khi dồn thùng sang ô B04-3-A'),
    ('E', 'CC-' || pg_temp.ymd(1) || '-7004', 1, '16:45', 'MANUAL', TRUE, 'kho.bd2', 'quanly.bd',
     'Kiểm kê các ô B04-2-B đến B04-3-D sau khi dồn thùng');

-- The lines of each count: its cartons, the minute each is counted (NULL: not yet) and whether it was found.
CREATE TEMP TABLE x_iop_line (code TEXT, seq INTEGER, n INTEGER, counted_min INTEGER, found BOOLEAN) ON COMMIT DROP;
INSERT INTO x_iop_line
SELECT 'A2', seq, n, 30 + row_number() OVER (ORDER BY n), TRUE FROM x_iop_carton
 WHERE seq = 99 AND sku IN ('LYG12-SDT', 'TOG750-SDT')
UNION ALL
SELECT 'A', seq, n, 30 + row_number() OVER (ORDER BY n), TRUE FROM x_iop_carton WHERE seq = 96 AND sku = 'TUIL-HNH'
UNION ALL
SELECT 'F', seq, n, NULL, NULL FROM x_iop_carton WHERE seq = 100 AND sku = 'TUIL-KTU';

CREATE FUNCTION pg_temp.iop_count_id(p_code TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT pg_temp.sid('iop:cycle-count:' || p_code) $$;
CREATE FUNCTION pg_temp.iop_line_id(p_code TEXT, p_seq INTEGER, p_n INTEGER) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT pg_temp.sid('iop:cycle-count-line:' || p_code || ':' || p_seq || ':' || p_n) $$;

-- Plans a count (CycleCountService.plan): the count PLANNED and one line per carton, the books' units of each.
CREATE FUNCTION pg_temp.iop_plan(p_code TEXT) RETURNS VOID LANGUAGE sql AS
$$
INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, is_blind, assigned_user_id,
                                   planned_date, note, version, created_at, created_by)
SELECT pg_temp.iop_count_id(c.code), c.number, pg_temp.sid('wh:BDG'), c.trigger_type, 'PLANNED', c.blind,
       pg_temp.staff(c.assignee), pg_temp.day(c.days), c.note, 0, pg_temp.at(c.days, c.planned),
       pg_temp.staff(c.planner)::text
  FROM x_iop_count c WHERE c.code = p_code;
INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, lot_number, expected_qty,
                                        recount_required, version, created_at, created_by, stock_package_id)
SELECT pg_temp.iop_line_id(l.code, l.seq, l.n), pg_temp.iop_count_id(l.code), pg_temp.iop_bin(l.seq, l.n), x.sku,
       x.lot_number, s.on_hand, FALSE, 0, pg_temp.at(c.days, c.planned), pg_temp.staff(c.planner)::text, x.package_id
  FROM x_iop_line l JOIN x_iop_count c ON c.code = l.code
  JOIN x_iop_carton x ON x.seq = l.seq AND x.n = l.n JOIN inventory.stock_item s ON s.id = x.stock_item_id
 WHERE l.code = p_code;
$$;

-- Counts the lines that have a minute (CycleCount.count): found or not, against the books at that moment; the first
-- line counted starts the count.
CREATE FUNCTION pg_temp.iop_count(p_code TEXT) RETURNS VOID LANGUAGE sql AS
$$
UPDATE inventory.cycle_count_line cl
   SET expected_qty = x.upc, counted_qty = CASE WHEN l.found THEN x.upc ELSE 0 END,
       counted_by = pg_temp.staff(c.assignee),
       counted_at = pg_temp.at(c.days, c.planned) + (l.counted_min || ' minutes')::interval, version = 1,
       last_modified_at = pg_temp.at(c.days, c.planned) + (l.counted_min || ' minutes')::interval,
       last_modified_by = pg_temp.staff(c.assignee)::text
  FROM x_iop_line l JOIN x_iop_count c ON c.code = l.code JOIN x_iop_carton x ON x.seq = l.seq AND x.n = l.n
 WHERE l.code = p_code AND l.counted_min IS NOT NULL AND cl.id = pg_temp.iop_line_id(l.code, l.seq, l.n);
UPDATE inventory.cycle_count cc
   SET status = 'IN_PROGRESS', started_at = pg_temp.at(c.days, c.planned) + (m.first_min || ' minutes')::interval,
       version = m.counted, last_modified_at = pg_temp.at(c.days, c.planned) + (m.last_min || ' minutes')::interval,
       last_modified_by = pg_temp.staff(c.assignee)::text
  FROM x_iop_count c,
       (SELECT min(counted_min) AS first_min, max(counted_min) AS last_min, count(counted_min) AS counted
          FROM x_iop_line WHERE code = p_code) m
 WHERE c.code = p_code AND cc.id = pg_temp.iop_count_id(c.code) AND m.counted > 0;
$$;

-- Submits a count whose every line is counted (CycleCountCountingService.submit), five minutes after the last line.
CREATE FUNCTION pg_temp.iop_submit(p_code TEXT) RETURNS VOID LANGUAGE sql AS
$$
UPDATE inventory.cycle_count cc
   SET status = 'COMPLETED', completed_at = pg_temp.at(c.days, c.planned) + ((m.last_min + 5) || ' minutes')::interval,
       version = cc.version + 1,
       last_modified_at = pg_temp.at(c.days, c.planned) + ((m.last_min + 5) || ' minutes')::interval,
       last_modified_by = pg_temp.staff(c.assignee)::text
  FROM x_iop_count c, (SELECT max(counted_min) AS last_min FROM x_iop_line WHERE code = p_code) m
 WHERE c.code = p_code AND cc.id = pg_temp.iop_count_id(c.code);
$$;

DO $$ BEGIN PERFORM pg_temp.iop_plan('A2'); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
DO $$ BEGIN PERFORM pg_temp.iop_count('A2'); END $$;
DO $$ BEGIN PERFORM pg_temp.iop_submit('A2'); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

DO $$ BEGIN PERFORM pg_temp.iop_plan('A'); END $$;
DO $$ BEGIN PERFORM pg_temp.iop_plan('F'); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
DO $$ BEGIN PERFORM pg_temp.iop_count('A'); END $$;
DO $$ BEGIN PERFORM pg_temp.iop_submit('A'); END $$;

-- Cancelled the next morning, nothing counted (CycleCount.cancel: the reason after the note).
UPDATE inventory.cycle_count
   SET status = 'CANCELLED',
       note = note || ' | Cancelled: Đơn hàng của mã này đã được giải phóng xuất kho; kiểm kê lại sau đợt giao',
       version = 1, last_modified_at = pg_temp.at(2, '08:15'), last_modified_by = pg_temp.staff('quanly.bd')::text
 WHERE id = pg_temp.iop_count_id('F');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 30.4 Adjustments asked by hand (20 §4.3; StockOperationsServiceImpl.requestPackageAdjustment): a carton by its LPN,
--      written off whole, decided by another person. A rejected one first.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_iop_adj (code TEXT PRIMARY KEY, number TEXT, seq INTEGER, n INTEGER, reason TEXT, note TEXT,
                             requester TEXT, days INTEGER, at TEXT, line_code TEXT) ON COMMIT DROP;
INSERT INTO x_iop_adj VALUES
    ('R', 'ADJ-' || pg_temp.ymd(3) || '-7001', 95, 12, 'DAMAGED',
     'Màng co rách, thùng móp một góc sau khi xe nâng chạm vào kệ B05', 'kho.bd1', 3, '15:30', NULL),
    ('W', 'ADJ-' || pg_temp.ymd(2) || '-7001', 93, 10, 'LOST',
     'Không thấy thùng tại ô khi đi kiểm tra vị trí', 'kho.bd2', 2, '16:00', NULL),
    ('V1', 'ADJ-' || pg_temp.ymd(1) || '-7001', 94, 9, 'COUNT_VARIANCE', NULL, 'kho.bd1', 1, '08:49', 'B'),
    ('V2', 'ADJ-' || pg_temp.ymd(1) || '-7002', 94, 12, 'COUNT_VARIANCE', NULL, 'kho.bd1', 1, '08:49', 'B'),
    ('V3', 'ADJ-' || pg_temp.ymd(1) || '-7003', 93, 20, 'COUNT_VARIANCE', NULL, 'kho.bd2', 1, '09:45', 'C'),
    ('P', 'ADJ-' || pg_temp.ymd(1) || '-7004', 95, 30, 'DAMAGED',
     'Thùng bị ướt do mưa tạt từ cửa dock, vỏ thùng mềm và bung đáy; đề nghị ghi giảm', 'kho.bd2', 1, '15:00', NULL);

CREATE FUNCTION pg_temp.iop_adj_id(p_code TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT pg_temp.sid('iop:adjustment:' || p_code) $$;

-- Asks for the adjustments of the given codes: the whole carton written off, at its bin, in its lot.
CREATE FUNCTION pg_temp.iop_request(p_codes TEXT[]) RETURNS VOID LANGUAGE sql AS
$$
INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, lot_number, quantity_delta,
                                        reason_code, note, status, cycle_count_line_id, requested_by, version,
                                        created_at, created_by, stock_package_id, counted_by)
SELECT pg_temp.iop_adj_id(a.code), a.number, pg_temp.iop_bin(a.seq, a.n), x.sku, x.lot_number, -x.upc, a.reason,
       CASE WHEN a.line_code IS NULL THEN a.note ELSE 'Count ' || c.number END, 'PENDING_APPROVAL',
       CASE WHEN a.line_code IS NOT NULL THEN pg_temp.iop_line_id(a.line_code, a.seq, a.n) END,
       pg_temp.staff(a.requester), 0, pg_temp.at(a.days, a.at), pg_temp.staff(a.requester)::text, x.package_id,
       CASE WHEN a.line_code IS NOT NULL THEN pg_temp.staff(c.assignee) END
  FROM x_iop_adj a JOIN x_iop_carton x ON x.seq = a.seq AND x.n = a.n
  LEFT JOIN x_iop_count c ON c.code = a.line_code
 WHERE a.code = ANY (p_codes)
 ORDER BY a.number;
$$;

DO $$ BEGIN PERFORM pg_temp.iop_request(ARRAY['R']); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE inventory.stock_adjustment
   SET status = 'REJECTED', decided_by = pg_temp.staff('quanly.bd'), decided_at = pg_temp.at(3, '17:00'),
       rejection_reason = 'Đã kiểm tra tại ô: chỉ rách màng co bên ngoài, ly bên trong nguyên vẹn. Quấn lại màng co, không ghi giảm',
       version = 1, last_modified_at = pg_temp.at(3, '17:00'), last_modified_by = pg_temp.staff('quanly.bd')::text
 WHERE id = pg_temp.iop_adj_id('R');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 30.5 Cartons moved by hand (11; StockOperationsController.move): scanned by LPN to one bin, all of them or none,
--      never a carton held for an order (these are held by SKU only, reserved 0). One MOVE line a carton, reference
--      MOVE_TASK and the request id; the carton and its row follow, status unchanged.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_iop_move ON COMMIT DROP AS
SELECT m.req, m.seq, m.n, m.to_bin, pg_temp.at(m.days, m.at) + (m.k * 40 || ' seconds')::interval AS at,
       pg_temp.iop_bin(m.seq, m.n) AS from_bin, pg_temp.sid('iop:move-request:' || m.req) AS request_id
  FROM (VALUES (1, 95, 1, 'BDG-B04-2-B', 3, '14:00', 0), (1, 95, 2, 'BDG-B04-2-B', 3, '14:00', 0),
               (2, 95, 3, 'BDG-B04-2-C', 3, '14:10', 0), (2, 95, 4, 'BDG-B04-2-C', 3, '14:10', 0),
               (3, 93, 25, 'BDG-B04-3-A', 2, '15:00', 0), (3, 93, 26, 'BDG-B04-3-A', 2, '15:00', 0))
       AS m (req, seq, n, to_bin, days, at, k);

UPDATE inventory.stock_package p
   SET location_code = m.to_bin, version = p.version + 1, last_modified_at = m.at,
       last_modified_by = pg_temp.staff('kho.bd2')::text
  FROM x_iop_move m JOIN x_iop_carton x ON x.seq = m.seq AND x.n = m.n WHERE p.id = x.package_id;

UPDATE inventory.stock_item s
   SET location_code = m.to_bin, version = s.version + 1, last_modified_at = m.at,
       last_modified_by = pg_temp.staff('kho.bd2')::text
  FROM x_iop_move m JOIN x_iop_carton x ON x.seq = m.seq AND x.n = m.n WHERE s.id = x.stock_item_id;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, reason, actor_id,
                                      from_stock_package_id, to_stock_package_id, occurred_at, version, created_at,
                                      created_by)
SELECT pg_temp.sid('iop:ledger:move:' || m.req || ':' || m.seq || ':' || m.n), 'MOVE', x.sku, x.lot_number, x.lot_id,
       m.from_bin, m.to_bin, x.upc, 'AVAILABLE', 'AVAILABLE', 'MOVE_TASK', m.request_id, NULL,
       pg_temp.staff('kho.bd2'), x.package_id, x.package_id, m.at, 0, m.at, pg_temp.staff('kho.bd2')::text
  FROM x_iop_move m JOIN x_iop_carton x ON x.seq = m.seq AND x.n = m.n;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- A carton reported lost by hand, found in the next bin within the hour: its requester withdraws the request.
DO $$ BEGIN PERFORM pg_temp.iop_request(ARRAY['W']); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
UPDATE inventory.stock_adjustment
   SET status = 'WITHDRAWN', withdrawn_at = pg_temp.at(2, '16:40'), version = 1,
       last_modified_at = pg_temp.at(2, '16:40'), last_modified_by = pg_temp.staff('kho.bd2')::text
 WHERE id = pg_temp.iop_adj_id('W');
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 30.6 Yesterday's counts. B (TUIL-NUD of order 94): two cartons not found; C (TOG750-MIQ of order 93): one. Each
--      submission asks one COUNT_VARIANCE adjustment of the whole carton per carton not found, in the submitter's
--      name, naming who counted the line (StockOperationsServiceImpl.requestCountVariance).
-- ---------------------------------------------------------------------------------------------
INSERT INTO x_iop_line
SELECT 'B', seq, n, 30 + row_number() OVER (ORDER BY n), n NOT IN (9, 12) FROM x_iop_carton
 WHERE seq = 94 AND sku = 'TUIL-NUD'
UNION ALL
SELECT 'C', seq, n, 30 + row_number() OVER (ORDER BY n), n <> 20 FROM x_iop_carton
 WHERE seq = 93 AND sku = 'TOG750-MIQ';

DO $$ BEGIN PERFORM pg_temp.iop_plan('B'); END $$;
DO $$ BEGIN PERFORM pg_temp.iop_plan('C'); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
DO $$ BEGIN PERFORM pg_temp.iop_count('B'); END $$;
DO $$ BEGIN PERFORM pg_temp.iop_submit('B'); END $$;
DO $$ BEGIN PERFORM pg_temp.iop_request(ARRAY['V1', 'V2']); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
DO $$ BEGIN PERFORM pg_temp.iop_count('C'); END $$;
DO $$ BEGIN PERFORM pg_temp.iop_submit('C'); END $$;
DO $$ BEGIN PERFORM pg_temp.iop_request(ARRAY['V3']); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- A damaged carton of order 95 reported in the afternoon; still waiting for the manager.
DO $$ BEGIN PERFORM pg_temp.iop_request(ARRAY['P']); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 30.7 The manager decides at the end of the day.
--      V1 approved (approveCarton): the carton the count did not find written off whole - its row to zero, the carton
--      LOST so a later count can find it again (20 §4.6 step 7) - and one ADJUSTMENT line naming who approved, who
--      asked and who counted (V20261121000200).
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_iop_post ON COMMIT DROP AS
SELECT a.id AS adj_id, a.adjustment_number, a.requested_by, a.counted_by, a.note, a.location_code, x.*
  FROM inventory.stock_adjustment a JOIN x_iop_carton x ON x.package_id = a.stock_package_id
 WHERE a.id = pg_temp.iop_adj_id('V1');

UPDATE inventory.stock_adjustment
   SET status = 'POSTED', decided_by = pg_temp.staff('quanly.bd'), decided_at = pg_temp.at(1, '16:00'),
       posted_at = pg_temp.at(1, '16:00'), version = 1, last_modified_at = pg_temp.at(1, '16:00'),
       last_modified_by = pg_temp.staff('quanly.bd')::text
 WHERE id = pg_temp.iop_adj_id('V1');

UPDATE inventory.stock_package p
   SET status = 'LOST', closed_at = pg_temp.at(1, '16:00'), version = p.version + 1,
       last_modified_at = pg_temp.at(1, '16:00'), last_modified_by = pg_temp.staff('quanly.bd')::text
  FROM x_iop_post x WHERE p.id = x.package_id;

UPDATE inventory.stock_item s
   SET on_hand = 0, version = s.version + 1, last_modified_at = pg_temp.at(1, '16:00'),
       last_modified_by = pg_temp.staff('quanly.bd')::text
  FROM x_iop_post x WHERE s.id = x.stock_item_id;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, reason, actor_id,
                                      from_stock_package_id, to_stock_package_id, occurred_at, version, created_at,
                                      created_by, requested_by, counted_by)
SELECT pg_temp.sid('iop:ledger:adjustment:V1'), 'ADJUSTMENT', x.sku, x.lot_number, x.lot_id, x.location_code, NULL,
       x.upc, 'AVAILABLE', 'AVAILABLE', 'STOCK_ADJUSTMENT', x.adj_id, 'COUNT_VARIANCE: ' || x.note,
       pg_temp.staff('quanly.bd'), x.package_id, NULL, pg_temp.at(1, '16:00'), 0, pg_temp.at(1, '16:00'),
       pg_temp.staff('quanly.bd')::text, x.requested_by, x.counted_by
  FROM x_iop_post x;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- After that commit (CountWriteOffListener, StockWrittenOffByCount): order 94's SKU hold no longer covered by the
-- cartons in the warehouse is cut by a whole carton (giveUpUncovered -> shrinkHold): the hold released SHORT_PICKED,
-- a smaller one in its place, added to the line and to the batch it was held for. The order stays where it is, one
-- carton short of its batch again.
CREATE TEMP TABLE x_iop_shrink ON COMMIT DROP AS
SELECT r.id AS old_id, r.order_id, r.sku, r.warehouse_code, r.quantity - p.upc AS keep, h.delivery_batch_id,
       h.order_line_id, pg_temp.sid('iop:hold-kept:' || r.id) AS kept_id,
       pg_temp.iop_uuid3('shrink:' || r.id || ':' || (r.quantity - p.upc)) AS request_id,
       pg_temp.at(1, '16:00') + interval '3 seconds' AS at
  FROM inventory.stock_reservation r JOIN ordering.delivery_batch_hold h ON h.reservation_id = r.id
  JOIN x_iop_post p ON p.sku = r.sku
 WHERE r.status = 'HELD' AND r.stock_item_id IS NULL AND r.warehouse_code = 'BDG';

UPDATE inventory.stock_reservation r
   SET status = 'RELEASED', release_reason = 'SHORT_PICKED', closed_at = x.at
  FROM x_iop_shrink x WHERE r.id = x.old_id;

INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at, status, sku,
                                         warehouse_code)
SELECT kept_id, order_id, request_id, request_id, keep, at, 'HELD', sku, warehouse_code FROM x_iop_shrink;

INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id)
SELECT order_line_id, kept_id FROM x_iop_shrink;

INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id, created_at)
SELECT kept_id, delivery_batch_id, order_id, order_line_id, at FROM x_iop_shrink;

UPDATE ordering.customer_order co
   SET version = co.version + 1, last_modified_at = x.at, last_modified_by = 'system'
  FROM x_iop_shrink x WHERE co.id = x.order_id;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- V3 rejected: the carton may stand in the bin beside; sent for recount at once (CycleCountService.recount): the line
-- flagged, a blind recount of its carton opened unassigned, then handed to a counter who did not count it first.
UPDATE inventory.stock_adjustment
   SET status = 'REJECTED', decided_by = pg_temp.staff('quanly.bd'), decided_at = pg_temp.at(1, '16:10'),
       rejection_reason = 'Có thể thùng bị xếp nhầm sang ô bên cạnh; đếm lại trước khi ghi giảm',
       version = 1, last_modified_at = pg_temp.at(1, '16:10'), last_modified_by = pg_temp.staff('quanly.bd')::text
 WHERE id = pg_temp.iop_adj_id('V3');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE inventory.cycle_count_line
   SET recount_required = TRUE, version = version + 1, last_modified_at = pg_temp.at(1, '16:15'),
       last_modified_by = pg_temp.staff('quanly.bd')::text
 WHERE id = pg_temp.iop_line_id('C', 93, 20);
UPDATE inventory.cycle_count
   SET version = version + 1, last_modified_at = pg_temp.at(1, '16:15'),
       last_modified_by = pg_temp.staff('quanly.bd')::text
 WHERE id = pg_temp.iop_count_id('C');

INSERT INTO x_iop_count VALUES
    ('D', 'CC-' || pg_temp.ymd(1) || '-7003', 1, '16:15', 'MANUAL', TRUE, NULL, 'quanly.bd',
     'Recount of ' || 'CC-' || pg_temp.ymd(1) || '-7002' || ': Kiểm tra cả các ô lân cận B03-3-B và B03-3-D');
INSERT INTO x_iop_line VALUES ('D', 93, 20, NULL, NULL);
DO $$ BEGIN PERFORM pg_temp.iop_plan('D'); END $$;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE inventory.cycle_count
   SET assigned_user_id = pg_temp.staff('kho.bd1'), version = 1, last_modified_at = pg_temp.at(1, '16:30'),
       last_modified_by = pg_temp.staff('quanly.bd')::text
 WHERE id = pg_temp.iop_count_id('D');
UPDATE x_iop_count SET assignee = 'kho.bd1' WHERE code = 'D';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 30.8 Late yesterday: a count of the bins order 95's cartons were moved into, half counted when the shift ended (by bin: every
--      carton in stock there, none of them on another count or waiting for an adjustment)
-- ---------------------------------------------------------------------------------------------
INSERT INTO x_iop_line
SELECT 'E', x.seq, x.n, NULL, NULL
  FROM x_iop_carton x JOIN inventory.stock_package p ON p.id = x.package_id
 WHERE p.status = 'IN_STOCK'
   AND p.location_code IN ('BDG-B04-2-B', 'BDG-B04-2-C', 'BDG-B04-2-D', 'BDG-B04-3-A', 'BDG-B04-3-B', 'BDG-B04-3-C',
                           'BDG-B04-3-D');

DO $$
BEGIN
    -- The bins hold cartons of the HELD orders 93-95 only, the whole of each bin is on the plan, and none is busy.
    IF EXISTS (SELECT 1 FROM inventory.stock_package p
                WHERE p.status = 'IN_STOCK'
                  AND p.location_code IN ('BDG-B04-2-B', 'BDG-B04-2-C', 'BDG-B04-2-D', 'BDG-B04-3-A', 'BDG-B04-3-B',
                                          'BDG-B04-3-C', 'BDG-B04-3-D')
                  AND p.id NOT IN (SELECT x.package_id FROM x_iop_line l JOIN x_iop_carton x ON x.seq = l.seq AND x.n = l.n
                                    WHERE l.code = 'E' AND x.seq IN (93, 94, 95)))
       OR EXISTS (SELECT 1 FROM x_iop_line l JOIN x_iop_carton x ON x.seq = l.seq AND x.n = l.n
                    JOIN inventory.cycle_count_line cl ON cl.stock_package_id = x.package_id
                    JOIN inventory.cycle_count c ON c.id = cl.cycle_count_id
                   WHERE l.code = 'E' AND (c.status IN ('PLANNED', 'IN_PROGRESS')
                                           OR EXISTS (SELECT 1 FROM inventory.stock_adjustment a
                                                       WHERE a.stock_package_id = x.package_id
                                                         AND a.status = 'PENDING_APPROVAL'))) THEN
        RAISE EXCEPTION 'iop: the bins of count E hold other cartons, or a carton already busy';
    END IF;
END $$;

-- Counted in bin order, a minute a carton from 17:00; the first eight scanned, all present.
UPDATE x_iop_line l
   SET counted_min = 14 + k, found = TRUE
  FROM (SELECT l2.seq, l2.n, row_number() OVER (ORDER BY pg_temp.iop_bin(l2.seq, l2.n), x.lpn) AS k
          FROM x_iop_line l2 JOIN x_iop_carton x ON x.seq = l2.seq AND x.n = l2.n WHERE l2.code = 'E') o
 WHERE l.code = 'E' AND l.seq = o.seq AND l.n = o.n AND o.k <= 8;

DO $$ BEGIN PERFORM pg_temp.iop_plan('E'); END $$;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
DO $$ BEGIN PERFORM pg_temp.iop_count('E'); END $$;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

DROP FUNCTION pg_temp.iop_count(TEXT);
DROP FUNCTION pg_temp.iop_submit(TEXT);
DROP FUNCTION pg_temp.iop_plan(TEXT);
DROP FUNCTION pg_temp.iop_request(TEXT[]);
DROP FUNCTION pg_temp.iop_adj_id(TEXT);
DROP FUNCTION pg_temp.iop_line_id(TEXT, INTEGER, INTEGER);
DROP FUNCTION pg_temp.iop_count_id(TEXT);
DROP FUNCTION pg_temp.iop_bin(INTEGER, INTEGER);
DROP FUNCTION pg_temp.iop_uuid3(TEXT);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 40. Carriers booked through an API (Ahamove), bookings and webhooks, waybill imports and files, a vehicle called
--     off while loading, load estimates (group ful; 09 §4.2-§4.5, LP2). Orders touched: READY / PICKING / PACKED
--     (all of them: estimates; O105, O107, O108: load plans and loads) and DELIVERED with i % 12 = 9 (O123, O135,
--     O159, O219, O231, O291, O147, O207, O267: bookings, waybill files and imports).
--
--     Ahamove is added as MANUAL with no key (ck_carrier_connection_api_booking refuses API_BOOKING without a sealed
--     key; the key is sealed at load time by seal_ahamove_key.py and never written here): the state seed_test_data.sql
--     leaves it in without a key. Its bookings, calls and callbacks are the history of the weeks it was connected.
--     fulfillment.wave stays empty: nothing writes it (every pick is created with wave_id null,
--     FulfillmentServiceImpl.createTask).
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION pg_temp.ful_epoch(t TIMESTAMPTZ) RETURNS BIGINT LANGUAGE sql IMMUTABLE AS
$$ SELECT extract(epoch FROM t)::bigint $$;

-- Instant.toString() of a whole second, as the dedupe key and the result notes print it.
CREATE FUNCTION pg_temp.ful_iso(t TIMESTAMPTZ) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT to_char(t AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') $$;

-- CarrierEventApplier.dedupeKey: carrier | kind | reference | stop | shipment code | reported time (or the body's digest).
CREATE FUNCTION pg_temp.ful_key(carrier UUID, kind TEXT, ref TEXT, stop INTEGER, code TEXT, reported TIMESTAMPTZ,
                                body TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT encode(sha256(convert_to(concat_ws('|', carrier::text, kind, ref, coalesce(stop::text, 'null'),
                                             coalesce(code, 'null'),
                                             CASE WHEN reported IS NOT NULL THEN pg_temp.ful_iso(reported)
                                                  ELSE 'body:' || encode(sha256(convert_to(body, 'UTF8')), 'hex') END),
                                   'UTF8')), 'hex') $$;

DO $$
BEGIN
    IF (SELECT count(*) FROM h_order
         WHERE key IN ('O123', 'O135', 'O159', 'O219', 'O231', 'O291', 'O147', 'O207', 'O267')
           AND stage = 'DELIVERED' AND i % 12 = 9 AND wh = 'BDG') <> 9
        OR (SELECT count(*) FROM h_order WHERE key = 'O107' AND stage = 'PACKED' AND d = 0 AND wh = 'BDG') <> 1
        OR (SELECT count(*) FROM h_order WHERE key IN ('O105', 'O108') AND stage = 'PACKED' AND d = -1 AND wh = 'BDG') <> 2
        OR (SELECT count(*) FROM h_order o JOIN s_customer c ON c.code = o.cust
             WHERE o.key IN ('O123', 'O135', 'O159', 'O219', 'O231', 'O105', 'O108') AND c.province_code = '79') <> 7 THEN
        RAISE EXCEPTION 'showcase ful: the orders this section names are no longer the ones it was written for';
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 40.1 Ahamove, its pickup points and service codes (the services listed through its API when they were set up)
-- ---------------------------------------------------------------------------------------------
INSERT INTO fulfillment.carrier (id, code, name, contact_name, contact_phone, status, version, created_at, created_by,
                                 connection_type, webhook_enabled, failure_reason_map)
VALUES (pg_temp.sid('ful:carrier:AHAMOVE'), 'AHAMOVE', 'Ahamove', 'Ahamove Partner Support', '1900545411', 'ACTIVE', 0,
        pg_temp.at(80, '09:00'), pg_temp.staff('quanly.bd')::text, 'MANUAL', FALSE,
        '{"CUSTOMER_NOT_AVAILABLE": "CUSTOMER_ABSENT", "CUSTOMER_REFUSED": "REFUSED", "WRONG_ADDRESS": "WRONG_ADDRESS"}');

INSERT INTO fulfillment.carrier_pickup_point (id, carrier_id, warehouse_id, province_code, address, contact_name,
                                              contact_phone, version, created_at, created_by)
SELECT pg_temp.sid('ful:pickup:' || w.prefix), pg_temp.sid('ful:carrier:AHAMOVE'), pg_temp.sid('wh:' || w.prefix),
       p.prov, w.address, s.full_name, p.phone, 0, pg_temp.at(79, '10:00'), pg_temp.staff(w.manager)::text
  FROM s_wh w
  JOIN (VALUES ('BDG', '79', '02837290088'), ('HNI', '01', '02435250099')) AS p (prefix, prov, phone) ON p.prefix = w.prefix
  JOIN s_staff s ON s.username = w.manager;

INSERT INTO fulfillment.carrier_service_code (id, carrier_id, vehicle_type_id, province_code, carrier_city_code,
                                              service_code, version, created_at, created_by)
SELECT pg_temp.sid('ful:service:' || c.prov || ':' || t.code), pg_temp.sid('ful:carrier:AHAMOVE'), t.id, c.prov, c.city,
       c.city || '-TRUCK-' || CASE t.code WHEN 'TRUCK-1T25' THEN '1000' ELSE '2000' END, 0,
       pg_temp.at(79, c.hhmi), pg_temp.staff(c.mgr)::text
  FROM (VALUES ('79', 'SGN', '10:10', 'quanly.bd'), ('01', 'HAN', '10:25', 'quanly.hn')) AS c (prov, city, hhmi, mgr)
 CROSS JOIN fulfillment.vehicle_type t
 WHERE t.code IN ('TRUCK-1T25', 'TRUCK-2T5');

INSERT INTO fulfillment.carrier_api_call (id, carrier_id, booking_id, operation, outcome, http_status, request_body,
                                          response_body, error, duration_ms, called_at, version, created_at, created_by)
SELECT pg_temp.sid('ful:api:services:' || c.city || ':' || x.op), pg_temp.sid('ful:carrier:AHAMOVE'), NULL, x.op, 'OK',
       200, NULL,
       CASE WHEN x.op = 'SERVICES' THEN json_build_array(
           json_build_object('_id', c.city || '-TRUCK-500', 'name_vi_vn', 'Xe tải 500 kg', 'city_id', c.city),
           json_build_object('_id', c.city || '-TRUCK-1000', 'name_vi_vn', 'Xe tải 1 tấn', 'city_id', c.city),
           json_build_object('_id', c.city || '-TRUCK-2000', 'name_vi_vn', 'Xe tải 2 tấn', 'city_id', c.city))::text END,
       NULL, x.ms, pg_temp.at(79, c.hhmi) + x.lag, 0, pg_temp.at(79, c.hhmi) + x.lag, pg_temp.staff(c.mgr)::text
  FROM (VALUES ('SGN', '10:05', 'quanly.bd'), ('HAN', '10:20', 'quanly.hn')) AS c (city, hhmi, mgr)
 CROSS JOIN (VALUES ('TOKEN', 240, interval '0 s'), ('SERVICES', 610, interval '1 s')) AS x (op, ms, lag);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 40.2 Delivered loads booked through Ahamove's API (09 BR-29): the truck hired by hand let the warehouse down the
--      afternoon before, its booking was cancelled (09 §4.2 step 6, kept in the booking history) and Ahamove booked in
--      its place; one booking Ahamove refused (no balance), one cancelled again by the dispatcher before a driver took
--      it. Every call to Ahamove is in the call log; every callback is kept as it came and decided once (09 BR-27).
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ful_dl ON COMMIT DROP AS
SELECT o.key, o.seq, o.id AS order_id, o.d, o.wh, o.mgr, o.kho, o.carrier AS xt, r.role, r.drv, r.drv_phone,
       pg_temp.sid('load:' || o.key) AS load_id, pg_temp.sid('shipment:' || o.key) AS shipment_id,
       l.code AS load_code, o.order_number || '-S1' AS shipment_code, c.contact, c.phone,
       c.line1 || ', ' || c.ward || ', ' || c.province AS addr, upper(substr(md5('ful:aha:' || o.key), 1, 10)) AS aha,
       pg_temp.sid('ful:booking:' || o.key) AS booking_id, l.vehicle_plate AS plate, l.driver_name AS xt_driver,
       l.driver_phone AS xt_phone, l.waybill_no AS xt_waybill, k.name AS xt_name, p.address AS pickup_addr,
       p.contact_name AS pickup_name, p.contact_phone AS pickup_phone, sc.service_code
  FROM h_order o
  JOIN (VALUES ('O123', 'AHA', 'Lê Hoàng Nam', '0918778899'), ('O159', 'AHA', 'Trần Minh Phúc', '0907445566'),
               ('O231', 'AHA', 'Nguyễn Văn Hậu', '0938112233'), ('O135', 'REFUSED', NULL, NULL),
               ('O219', 'CANCEL', NULL, NULL)) AS r (key, role, drv, drv_phone) ON r.key = o.key
  JOIN s_customer c ON c.code = o.cust
  JOIN fulfillment.dispatch_load l ON l.id = pg_temp.sid('load:' || o.key)
  JOIN fulfillment.carrier k ON k.code = o.carrier
  JOIN fulfillment.carrier_pickup_point p
    ON p.carrier_id = pg_temp.sid('ful:carrier:AHAMOVE') AND p.warehouse_id = pg_temp.sid('wh:' || o.wh)
  JOIN fulfillment.carrier_service_code sc
    ON sc.carrier_id = p.carrier_id AND sc.province_code = p.province_code
   AND sc.vehicle_type_id = (SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-2T5');

-- When each booking was claimed (the afternoon before for the re-booked ones, the morning for the other two).
ALTER TABLE x_ful_dl ADD COLUMN t_claim TIMESTAMPTZ, ADD COLUMN pickup_at TIMESTAMPTZ, ADD COLUMN xt_reason TEXT,
    ADD COLUMN body TEXT;
UPDATE x_ful_dl
   SET t_claim = pg_temp.at(d + 1, CASE role WHEN 'AHA' THEN '16:10' WHEN 'REFUSED' THEN '11:00' ELSE '11:30' END),
       pickup_at = pg_temp.at(d, '07:00'),
       xt_reason = CASE key
                       WHEN 'O123' THEN 'Nhà xe ' || xt_name || ' báo xe tải đi tỉnh chưa về kịp, không còn xe cho sáng mai'
                       WHEN 'O159' THEN 'Xe của ' || xt_name || ' hỏng hộp số chiều nay; nhà xe không có xe thay thế'
                       ELSE 'Nhà xe ' || xt_name || ' báo tài xế nghỉ đột xuất, không điều được xe khác' END;
UPDATE x_ful_dl
   SET body = json_build_object(
           'order_time', pg_temp.ful_epoch(pickup_at),
           'path', json_build_array(json_build_object('address', pickup_addr, 'name', pickup_name, 'mobile', pickup_phone),
                                    json_build_object('address', addr, 'name', contact, 'mobile', phone,
                                                      'tracking_number', shipment_code)),
           'service_id', service_code, 'requests', json_build_array(), 'payment_method', 'BALANCE',
           'remarks', load_code)::text;

-- The truck hired by hand is let go first (DispatchService.cancelBooking): its booking into the history, the
-- waybill number off the load.
INSERT INTO fulfillment.load_booking_cancellation (id, load_id, carrier_id, waybill_no, vehicle_plate, driver_name,
                                                   driver_phone, booked_at, booked_by, cancelled_at, cancelled_by,
                                                   reason, version, created_at, created_by)
SELECT pg_temp.sid('ful:booking-cancel:xt:' || x.key), x.load_id, pg_temp.sid('carrier:' || x.xt), x.xt_waybill, x.plate,
       x.xt_driver, x.xt_phone, pg_temp.at(x.d + 1, '15:00'), pg_temp.staff(x.mgr), pg_temp.at(x.d + 1, '16:00'),
       pg_temp.staff(x.mgr), x.xt_reason, 0, pg_temp.at(x.d + 1, '16:00'), pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x WHERE x.role = 'AHA';

INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, source, changed_by, changed_at,
                                        reason, version, created_at, created_by)
SELECT pg_temp.sid('ful:waybill:xt-off:' || x.key), x.load_id, x.xt_waybill, NULL, 'MANUAL', pg_temp.staff(x.mgr),
       pg_temp.at(x.d + 1, '16:00'), 'Booking cancelled: ' || x.xt_reason, 0, pg_temp.at(x.d + 1, '16:00'),
       pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x WHERE x.role = 'AHA';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Step 1: the claim (CarrierBookingService.prepare), REQUESTED.
INSERT INTO fulfillment.carrier_booking (id, load_id, carrier_id, service_code, stops, order_time, status,
                                         requested_by, requested_at, cancel_attempts, version, created_at, created_by)
SELECT x.booking_id, x.load_id, pg_temp.sid('ful:carrier:AHAMOVE'), x.service_code,
       jsonb_build_array(jsonb_build_object('seq', 1, 'shipmentId', x.shipment_id::text, 'shipmentCode', x.shipment_code,
                                            'address', x.addr)),
       x.pickup_at, 'REQUESTED', pg_temp.staff(x.mgr), x.t_claim, 0, 0, x.t_claim, pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Step 2: Ahamove called with nothing open - a token first (the application had restarted since), then the order.
INSERT INTO fulfillment.carrier_api_call (id, carrier_id, booking_id, operation, outcome, http_status, request_body,
                                          response_body, error, duration_ms, called_at, version, created_at, created_by)
SELECT pg_temp.sid('ful:api:token:' || x.key), pg_temp.sid('ful:carrier:AHAMOVE'), x.booking_id, 'TOKEN', 'OK', 200,
       NULL, NULL, NULL, 230 + x.seq % 90, x.t_claim, 0, x.t_claim, pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x;

INSERT INTO fulfillment.carrier_api_call (id, carrier_id, booking_id, operation, outcome, http_status, request_body,
                                          response_body, error, duration_ms, called_at, version, created_at, created_by)
SELECT pg_temp.sid('ful:api:book:' || x.key), pg_temp.sid('ful:carrier:AHAMOVE'), x.booking_id, 'BOOK',
       CASE WHEN x.role = 'REFUSED' THEN 'REFUSED' ELSE 'OK' END, CASE WHEN x.role = 'REFUSED' THEN 400 ELSE 200 END,
       x.body,
       CASE WHEN x.role = 'REFUSED'
            THEN '{"code":"NOT_ENOUGH_BALANCE","title":"Số dư tài khoản không đủ để tạo đơn hàng"}'
            ELSE json_build_object('order_id', x.aha, 'status', 'ASSIGNING',
                                   'shared_link', 'https://cloud.ahamove.com/share-order/' || x.aha)::text END,
       CASE WHEN x.role = 'REFUSED' THEN '400 NOT_ENOUGH_BALANCE: Số dư tài khoản không đủ để tạo đơn hàng' END,
       1180 + x.seq * 7 % 600, x.t_claim + interval '2 s', 0, x.t_claim + interval '2 s', pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Step 3: refused - the claim FAILED, the load untouched; booked - the load takes the order (applyCarrierBooking),
-- its waybill number the carrier's order id.
UPDATE fulfillment.carrier_booking b
   SET status = 'FAILED', last_error = '400 NOT_ENOUGH_BALANCE: Số dư tài khoản không đủ để tạo đơn hàng', version = 1,
       last_modified_at = x.t_claim + interval '2 s', last_modified_by = pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x WHERE b.id = x.booking_id AND x.role = 'REFUSED';

UPDATE fulfillment.carrier_booking b
   SET status = 'BOOKED', carrier_order_id = x.aha, tracking_link = 'https://cloud.ahamove.com/share-order/' || x.aha,
       booked_at = x.t_claim + interval '3 s', version = 1, last_modified_at = x.t_claim + interval '3 s',
       last_modified_by = pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x WHERE b.id = x.booking_id AND x.role IN ('AHA', 'CANCEL');

INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, source, changed_by, changed_at,
                                        reason, version, created_at, created_by)
SELECT pg_temp.sid('ful:waybill:api-on:' || x.key), x.load_id, NULL, x.aha, 'API', pg_temp.staff(x.mgr),
       x.t_claim + interval '3 s', NULL, 0, x.t_claim + interval '3 s', pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x WHERE x.role IN ('AHA', 'CANCEL');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- O219: no driver took the truck order in two hours; the dispatcher cancelled it at Ahamove and here
-- (cancelCarrierBooking), and hired the usual truck at 15:00. Ahamove echoes the cancel back, which changes nothing.
INSERT INTO fulfillment.carrier_api_call (id, carrier_id, booking_id, operation, outcome, http_status, request_body,
                                          response_body, error, duration_ms, called_at, version, created_at, created_by)
SELECT pg_temp.sid('ful:api:cancel:' || x.key), pg_temp.sid('ful:carrier:AHAMOVE'), x.booking_id, 'CANCEL', 'OK', 200,
       '{"comment":"Khách hàng muốn hủy đơn"}', json_build_object('order_id', x.aha, 'status', 'CANCELLED')::text, NULL,
       540, pg_temp.at(x.d + 1, '13:30') + interval '1 s', 0, pg_temp.at(x.d + 1, '13:30') + interval '1 s',
       pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x WHERE x.role = 'CANCEL';

UPDATE fulfillment.carrier_booking b
   SET status = 'CANCELLED', cancelled_at = pg_temp.at(x.d + 1, '13:30') + interval '2 s',
       cancel_reason = 'Sau 2 giờ chưa có tài xế xe tải nhận đơn; chuyển sang xe của ' || x.xt_name, version = 2,
       last_modified_at = pg_temp.at(x.d + 1, '13:30') + interval '2 s', last_modified_by = pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x WHERE b.id = x.booking_id AND x.role = 'CANCEL';

INSERT INTO fulfillment.load_booking_cancellation (id, load_id, carrier_id, waybill_no, vehicle_plate, driver_name,
                                                   driver_phone, booked_at, booked_by, cancelled_at, cancelled_by,
                                                   reason, version, created_at, created_by)
SELECT pg_temp.sid('ful:booking-cancel:api:' || x.key), x.load_id, pg_temp.sid('ful:carrier:AHAMOVE'), x.aha, NULL, NULL,
       NULL, x.t_claim + interval '3 s', pg_temp.staff(x.mgr), pg_temp.at(x.d + 1, '13:30') + interval '2 s',
       pg_temp.staff(x.mgr), 'Sau 2 giờ chưa có tài xế xe tải nhận đơn; chuyển sang xe của ' || x.xt_name, 0,
       pg_temp.at(x.d + 1, '13:30') + interval '2 s', pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x WHERE x.role = 'CANCEL';

INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, source, changed_by, changed_at,
                                        reason, version, created_at, created_by)
SELECT pg_temp.sid('ful:waybill:api-off:' || x.key), x.load_id, x.aha, NULL, 'API', pg_temp.staff(x.mgr),
       pg_temp.at(x.d + 1, '13:30') + interval '2 s',
       'Booking cancelled at the carrier: Sau 2 giờ chưa có tài xế xe tải nhận đơn; chuyển sang xe của ' || x.xt_name, 0,
       pg_temp.at(x.d + 1, '13:30') + interval '2 s', pg_temp.staff(x.mgr)::text
  FROM x_ful_dl x WHERE x.role = 'CANCEL';

-- The booking cancelled stays on the load as its last cancellation (DispatchLoad.book does not clear it).
UPDATE fulfillment.dispatch_load l
   SET booking_cancelled_at = pg_temp.at(x.d + 1, '13:30') + interval '2 s', booking_cancelled_by = pg_temp.staff(x.mgr),
       booking_cancel_reason = 'Sau 2 giờ chưa có tài xế xe tải nhận đơn; chuyển sang xe của ' || x.xt_name, version = 5
  FROM x_ful_dl x WHERE l.id = x.load_id AND x.role = 'CANCEL';

-- The callbacks. A kept call is matched in one transaction and decided in the next (CarrierEventService.handle).
CREATE TEMP TABLE x_ful_ev (id UUID, key TEXT, kind TEXT, ref TEXT, stop INTEGER, code TEXT, reported TIMESTAMPTZ,
                            received TIMESTAMPTZ, payload TEXT, result TEXT, note TEXT, shipment BOOLEAN,
                            milestone TEXT, verify BOOLEAN) ON COMMIT DROP;

-- O219's cancel echoed back: decided while matched (IGNORED, never applied).
INSERT INTO x_ful_ev
SELECT pg_temp.sid('ful:event:' || x.key || ':cancel-echo'), x.key, 'CANCELLED_ON_REQUEST', x.aha, NULL, NULL,
       pg_temp.at(x.d + 1, '13:30') + interval '1 s', pg_temp.at(x.d + 1, '13:30') + interval '4 s',
       json_build_object('_id', x.aha, 'status', 'CANCELLED', 'cancel_by_user', TRUE,
                         'cancel_comment', 'Khách hàng muốn hủy đơn',
                         'cancel_time', pg_temp.ful_epoch(pg_temp.at(x.d + 1, '13:30') + interval '1 s'),
                         'service_id', x.service_code)::text,
       'IGNORED', 'Order ' || x.aha || ' cancelled at the system''s request', FALSE, NULL, FALSE
  FROM x_ful_dl x WHERE x.role = 'CANCEL';

-- A driver takes the order (ACCEPTED: DRIVER_ASSIGNED). On O159 a first driver took it and gave it back; his
-- release reached the system after the second driver's acceptance, so it is outdated (STALE). O231's acceptance came
-- twice (DUPLICATE).
INSERT INTO x_ful_ev
SELECT pg_temp.sid('ful:event:' || x.key || ':accepted'), x.key, 'DRIVER_ASSIGNED', x.aha, NULL, NULL, a.accepted,
       a.accepted + interval '2 s',
       json_build_object('_id', x.aha, 'status', 'ACCEPTED', 'service_id', x.service_code, 'supplier_id', x.drv_phone,
                         'supplier_name', x.drv, 'accept_time', pg_temp.ful_epoch(a.accepted),
                         'order_time', pg_temp.ful_epoch(x.pickup_at))::text,
       'APPLIED', 'The driver on the load', FALSE, NULL, FALSE
  FROM x_ful_dl x
  JOIN (VALUES ('O123', '16:35'), ('O159', '16:31'), ('O231', '16:28')) AS t (key, hhmi) ON t.key = x.key
 CROSS JOIN LATERAL (SELECT pg_temp.at(x.d + 1, t.hhmi) AS accepted) a;

INSERT INTO x_ful_ev
SELECT pg_temp.sid('ful:event:O159:accepted-first'), x.key, 'DRIVER_ASSIGNED', x.aha, NULL, NULL,
       pg_temp.at(x.d + 1, '16:20'), pg_temp.at(x.d + 1, '16:20') + interval '2 s',
       json_build_object('_id', x.aha, 'status', 'ACCEPTED', 'service_id', x.service_code, 'supplier_id', '0933221100',
                         'supplier_name', 'Phạm Quốc Toàn', 'accept_time', pg_temp.ful_epoch(pg_temp.at(x.d + 1, '16:20')),
                         'order_time', pg_temp.ful_epoch(x.pickup_at))::text,
       'APPLIED', 'The driver on the load', FALSE, NULL, FALSE
  FROM x_ful_dl x WHERE x.key = 'O159';

INSERT INTO x_ful_ev
SELECT pg_temp.sid('ful:event:O159:released'), x.key, 'DRIVER_RELEASED', x.aha, NULL, NULL,
       pg_temp.at(x.d + 1, '16:20'), pg_temp.at(x.d + 1, '16:33') + interval '5 s',
       json_build_object('_id', x.aha, 'status', 'ASSIGNING', 'service_id', x.service_code,
                         'accept_time', pg_temp.ful_epoch(pg_temp.at(x.d + 1, '16:20')),
                         'order_time', pg_temp.ful_epoch(x.pickup_at))::text,
       'STALE', 'Older than the last report applied (' || pg_temp.ful_iso(pg_temp.at(x.d + 1, '16:31')) || ')', FALSE,
       NULL, FALSE
  FROM x_ful_dl x WHERE x.key = 'O159';

INSERT INTO x_ful_ev
SELECT pg_temp.sid('ful:event:O231:accepted-again'), e.key, e.kind, e.ref, NULL, NULL, e.reported,
       e.received + interval '61 s', e.payload, 'DUPLICATE', 'The same report was handled already', FALSE, NULL, FALSE
  FROM x_ful_ev e WHERE e.id = pg_temp.sid('ful:event:O231:accepted');

-- Picked up after the handover at 08:00 (a milestone of a vehicle that left); O123's "on its way" too.
INSERT INTO x_ful_ev
SELECT pg_temp.sid('ful:event:' || x.key || ':picked-up'), x.key, 'PICKED_UP', x.aha, NULL, NULL,
       pg_temp.at(x.d, '08:06'), pg_temp.at(x.d, '08:06') + interval '3 s',
       json_build_object('_id', x.aha, 'status', 'IN PROCESS', 'service_id', x.service_code, 'supplier_id', x.drv_phone,
                         'supplier_name', x.drv, 'pickup_time', pg_temp.ful_epoch(pg_temp.at(x.d, '08:06')))::text,
       'APPLIED', 'Milestone PICKED_UP', FALSE, 'PICKED_UP', FALSE
  FROM x_ful_dl x WHERE x.role = 'AHA';

INSERT INTO x_ful_ev
SELECT pg_temp.sid('ful:event:' || x.key || ':in-transit'), x.key, 'IN_TRANSIT', x.aha, NULL, NULL, NULL,
       pg_temp.at(x.d, '09:20') + interval '5 s',
       json_build_object('_id', x.aha, 'status', 'IN PROCESS', 'sub_status', 'COMPLETING', 'service_id', x.service_code,
                         'supplier_id', x.drv_phone, 'supplier_name', x.drv,
                         'pickup_time', pg_temp.ful_epoch(pg_temp.at(x.d, '08:06')))::text,
       'APPLIED', 'Milestone IN_TRANSIT', FALSE, 'IN_TRANSIT', FALSE
  FROM x_ful_dl x WHERE x.key = 'O123';

-- The drop-off completed at 14:52, but its callback came after the manager had recorded the delivery by hand at
-- 15:00: asked back of Ahamove's API (borne out), then changed nothing; so did the order's completion.
INSERT INTO x_ful_ev
SELECT pg_temp.sid('ful:event:' || x.key || ':dropoff'), x.key, 'DELIVERED', x.aha, 1, x.shipment_code,
       pg_temp.at(x.d, '14:52'), pg_temp.at(x.d, '15:12') + interval '4 s',
       json_build_object('_id', x.aha || '-1', 'status', 'IN PROCESS', 'service_id', x.service_code,
                         'path', json_build_array(
                             json_build_object('address', x.pickup_addr, 'status', 'COMPLETED'),
                             json_build_object('address', x.addr, 'tracking_number', x.shipment_code,
                                               'status', 'COMPLETED',
                                               'complete_time', pg_temp.ful_epoch(pg_temp.at(x.d, '14:52')))))::text,
       'IGNORED', 'The shipments were DELIVERED already', TRUE, NULL, TRUE
  FROM x_ful_dl x WHERE x.role = 'AHA';

INSERT INTO x_ful_ev
SELECT pg_temp.sid('ful:event:' || x.key || ':completed'), x.key, 'ORDER_COMPLETED', x.aha, NULL, NULL,
       pg_temp.at(x.d, '14:55'), pg_temp.at(x.d, '15:13') + interval '10 s',
       json_build_object('_id', x.aha, 'status', 'COMPLETED', 'service_id', x.service_code,
                         'complete_time', pg_temp.ful_epoch(pg_temp.at(x.d, '14:55')),
                         'path', json_build_array(
                             json_build_object('address', x.pickup_addr, 'status', 'COMPLETED'),
                             json_build_object('address', x.addr, 'tracking_number', x.shipment_code,
                                               'status', 'COMPLETED',
                                               'complete_time', pg_temp.ful_epoch(pg_temp.at(x.d, '14:52')))))::text,
       'IGNORED', 'The drop-offs'' outcomes were applied already', FALSE, NULL, TRUE
  FROM x_ful_dl x WHERE x.role = 'AHA';

INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, event_kind,
                                       carrier_reference, carrier_reported_at, dedupe_key, warehouse_id, load_id,
                                       shipment_id, result, result_note, decided_at, version, created_at, created_by,
                                       last_modified_at, last_modified_by, source)
SELECT e.id, pg_temp.sid('ful:carrier:AHAMOVE'), 'AHAMOVE', e.received, e.payload, TRUE, e.kind,
       e.ref || coalesce('-' || e.stop, ''), e.reported,
       CASE WHEN e.kind <> 'CANCELLED_ON_REQUEST'
            THEN pg_temp.ful_key(pg_temp.sid('ful:carrier:AHAMOVE'), e.kind, e.ref, e.stop, e.code, e.reported, e.payload) END,
       pg_temp.sid('wh:' || x.wh), x.load_id, CASE WHEN e.shipment THEN x.shipment_id END, 'RECEIVED', NULL, NULL, 0,
       e.received, 'system', NULL, NULL, 'WEBHOOK'
  FROM x_ful_ev e JOIN x_ful_dl x ON x.key = e.key;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- A final outcome of an API carrier is asked back of its API before it is applied (AhamoveOutcomeVerifier).
INSERT INTO fulfillment.carrier_api_call (id, carrier_id, booking_id, operation, outcome, http_status, request_body,
                                          response_body, error, duration_ms, called_at, version, created_at, created_by)
SELECT pg_temp.sid('ful:api:status:' || e.id), pg_temp.sid('ful:carrier:AHAMOVE'), x.booking_id, 'STATUS', 'OK', 200,
       NULL,
       json_build_object('_id', x.aha, 'status', CASE WHEN e.kind = 'DELIVERED' THEN 'COMPLETED' ELSE 'COMPLETED' END,
                         'service_id', x.service_code,
                         'path', json_build_array(
                             json_build_object('address', x.pickup_addr, 'status', 'COMPLETED'),
                             json_build_object('address', x.addr, 'tracking_number', x.shipment_code,
                                               'status', 'COMPLETED',
                                               'complete_time', pg_temp.ful_epoch(pg_temp.at(x.d, '14:52')))))::text,
       NULL, 420, e.received + interval '1 s', 0, e.received + interval '1 s', 'system'
  FROM x_ful_ev e JOIN x_ful_dl x ON x.key = e.key WHERE e.verify;

-- Decided: the echo while matched (version 1), the others under the load's lock in the next transaction (version 2).
UPDATE fulfillment.carrier_event c
   SET result = e.result, result_note = e.note,
       decided_at = e.received + CASE WHEN e.verify THEN interval '2 s' ELSE interval '1 s' END,
       version = CASE WHEN e.kind = 'CANCELLED_ON_REQUEST' THEN 1 ELSE 2 END,
       last_modified_at = e.received + CASE WHEN e.verify THEN interval '2 s' ELSE interval '1 s' END,
       last_modified_by = 'system'
  FROM x_ful_ev e WHERE c.id = e.id;

INSERT INTO fulfillment.tracking_milestone (id, load_id, milestone, carrier_reported_at, source, carrier_event_id,
                                            recorded_at, version, created_at)
SELECT pg_temp.sid('ful:milestone:' || e.id), x.load_id, e.milestone, coalesce(e.reported, e.received),
       'CARRIER_WEBHOOK', e.id, e.received + interval '1 s', 0, e.received + interval '1 s'
  FROM x_ful_ev e JOIN x_ful_dl x ON x.key = e.key WHERE e.milestone IS NOT NULL;

-- What the re-booked loads now say: Ahamove, its order id as the waybill number, the driver who took the order
-- (who also signed the manifest at the handover); the plate is the one written on the signed manifest.
UPDATE fulfillment.dispatch_load l
   SET carrier_id = pg_temp.sid('ful:carrier:AHAMOVE'), waybill_no = x.aha, driver_name = x.drv,
       driver_phone = x.drv_phone, carrier_representative = x.drv, booked_at = x.t_claim + interval '3 s',
       booked_by = pg_temp.staff(x.mgr), booking_cancelled_at = pg_temp.at(x.d + 1, '16:00'),
       booking_cancelled_by = pg_temp.staff(x.mgr), booking_cancel_reason = x.xt_reason,
       version = CASE WHEN x.key = 'O159' THEN 7 ELSE 6 END
  FROM x_ful_dl x WHERE l.id = x.load_id AND x.role = 'AHA';

UPDATE fulfillment.shipment s
   SET carrier_id = pg_temp.sid('ful:carrier:AHAMOVE')
  FROM x_ful_dl x WHERE s.id = x.shipment_id AND x.role = 'AHA';

UPDATE ordering.order_shipment s
   SET carrier_name = 'Ahamove'
  FROM x_ful_dl x WHERE s.id = x.shipment_id AND x.role = 'AHA';

-- Callbacks the system could not use: one whose token did not hold (kept as a summary), one for an order no load
-- holds (Ahamove's own test order, resolved by the manager), one naming a drop-off its order does not have.
INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, event_kind,
                                       carrier_reference, carrier_reported_at, dedupe_key, warehouse_id, load_id,
                                       shipment_id, result, result_note, decided_at, version, created_at, created_by,
                                       source)
SELECT pg_temp.sid('ful:event:invalid-token'), pg_temp.sid('ful:carrier:AHAMOVE'), 'AHAMOVE',
       pg_temp.at(18, '21:40'), '[refused: ' || octet_length(b.body) || ' bytes, sha256 '
           || encode(sha256(convert_to(b.body, 'UTF8')), 'hex') || '] ' || b.body,
       FALSE, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'INVALID_SIGNATURE',
       'The X-Ahamove-Token header does not carry the credential configured', pg_temp.at(18, '21:40'), 0,
       pg_temp.at(18, '21:40'), 'system', 'WEBHOOK'
  FROM (SELECT '{"_id":"7Q2K9X0ZLM","status":"COMPLETED","complete_time":'
                   || pg_temp.ful_epoch(pg_temp.at(18, '21:39')) || '}' AS body) b;

INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, event_kind,
                                       carrier_reference, carrier_reported_at, dedupe_key, warehouse_id, load_id,
                                       shipment_id, result, result_note, decided_at, version, created_at, created_by,
                                       source)
SELECT pg_temp.sid('ful:event:' || v.tag), pg_temp.sid('ful:carrier:AHAMOVE'), 'AHAMOVE', v.received, v.payload, TRUE,
       NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'RECEIVED', NULL, NULL, 0, v.received, 'system', 'WEBHOOK'
  FROM (VALUES ('unmatched', pg_temp.at(44, '10:15'),
                '{"_id":"TEST0001AH","status":"ACCEPTED","service_id":"SGN-TRUCK-1000","supplier_id":"0900000000","supplier_name":"Tai xe thu nghiem","accept_time":'
                    || pg_temp.ful_epoch(pg_temp.at(44, '10:14')) || '}'),
               ('no-dropoff', pg_temp.at(9, '14:41'),
                '{"_id":"' || (SELECT aha FROM x_ful_dl WHERE key = 'O231') || '-2","status":"IN PROCESS","path":[{"address":"'
                    || (SELECT pickup_addr FROM x_ful_dl WHERE key = 'O231') || '","status":"COMPLETED"},{"address":"'
                    || (SELECT addr FROM x_ful_dl WHERE key = 'O231') || '","status":"COMPLETED"}]}'))
       AS v (tag, received, payload);

UPDATE fulfillment.carrier_event
   SET event_kind = 'DRIVER_ASSIGNED', carrier_reference = 'TEST0001AH', carrier_reported_at = pg_temp.at(44, '10:14'),
       result = 'UNMATCHED', result_note = 'No load holds order TEST0001AH of carrier AHAMOVE',
       decided_at = pg_temp.at(44, '10:15') + interval '1 s', version = 1,
       last_modified_at = pg_temp.at(44, '10:15') + interval '1 s', last_modified_by = 'system'
 WHERE id = pg_temp.sid('ful:event:unmatched');

UPDATE fulfillment.carrier_event
   SET result = 'NEEDS_REVIEW',
       result_note = 'Ahamove callback for drop-off 2 of order ' || (SELECT aha FROM x_ful_dl WHERE key = 'O231')
           || ' has no such drop-off in its path',
       decided_at = pg_temp.at(9, '14:41') + interval '1 s', version = 1,
       last_modified_at = pg_temp.at(9, '14:41') + interval '1 s', last_modified_by = 'system'
 WHERE id = pg_temp.sid('ful:event:no-dropoff');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE fulfillment.carrier_event
   SET resolved_by = pg_temp.staff('quanly.bd'), resolved_at = pg_temp.at(44, '14:00'),
       resolution_note = 'Đơn thử Ahamove tạo khi kiểm tra kết nối webhook, không thuộc chuyến nào của kho', version = 2,
       last_modified_at = pg_temp.at(44, '14:00'), last_modified_by = pg_temp.staff('quanly.bd')::text
 WHERE id = pg_temp.sid('ful:event:unmatched');

UPDATE fulfillment.carrier_event
   SET resolved_by = pg_temp.staff('quanly.bd'), resolved_at = pg_temp.at(9, '16:30'),
       resolution_note = 'Đơn chỉ có một điểm giao; đã gọi Ahamove xác nhận gửi nhầm, chuyến đã giao đủ', version = 2,
       last_modified_at = pg_temp.at(9, '16:30'), last_modified_by = pg_temp.staff('quanly.bd')::text
 WHERE id = pg_temp.sid('ful:event:no-dropoff');

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 40.3 Three packed orders put on a truck plan yesterday (09 §4.1-§4.2), as section 19 plans the shipped ones:
--      O108: the hired truck broke down while loading - cancelled under control (XE_HONG), its cartons scanned off
--            (one confirmed off without a scan, its label torn), a new load opened for the vehicle, and Ahamove's
--            booking of it never answered: UNCONFIRMED, waiting for the dispatcher to check Ahamove's portal;
--      O107: a truck hired by hand without its number, the number imported from the carrier's file;
--      O105: booked through Ahamove, a driver has taken the order.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ful_pk ON COMMIT DROP AS
SELECT o.key, o.seq, o.id AS order_id, o.wh, o.mgr, o.kho, o.cartons, o.order_number, o.carrier AS xt, r.role,
       pg_temp.at(1, r.hhmi) AS t0, pg_temp.sid('ful:load-plan:' || o.key) AS plan_id,
       pg_temp.sid('ful:load-plan-vehicle:' || o.key) AS vehicle_id, pg_temp.sid('ful:load:' || o.key) AS load_id,
       'LD-' || pg_temp.ymd(1) || '-' || (7000 + o.seq) AS load_code, pg_temp.sid('shipment:' || o.key) AS shipment_id,
       o.order_number || '-S1' AS shipment_code, c.contact, c.phone, c.line1 || ', ' || c.ward || ', ' || c.province AS addr
  FROM h_order o
  JOIN (VALUES ('O108', 'UNLOAD', '15:20'), ('O107', 'IMPORT', '16:00'), ('O105', 'LIVE', '16:05')) AS r (key, role, hhmi)
    ON r.key = o.key
  JOIN s_customer c ON c.code = o.cust;

INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, source_type, selection_mode, status,
                                   calculated_at, calculated_by, solver_meta, confirmed_at, confirmed_by, revised_at,
                                   version, created_at, created_by)
SELECT o.plan_id, 'LP-' || pg_temp.ymd(1) || '-' || (7000 + o.seq), pg_temp.sid('ful:load-plan-request:' || o.key),
       pg_temp.sid('wh:' || o.wh), 'ORDER', 'SINGLE_TYPE', 'CONFIRMED', o.t0 + interval '5 min', pg_temp.staff(o.mgr),
       '{"algorithm": "GRASP_KIT", "setsTotal": 0, "kitWarnings": [], "minVehicles": 1, "budgetExhausted": false}'::jsonb,
       o.t0 + interval '10 min', pg_temp.staff(o.mgr), o.t0 + interval '10 min', 2, o.t0, pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o;

INSERT INTO fulfillment.load_plan_vehicle_type (id, load_plan_id, vehicle_type_id, sort_order, version, created_at,
                                                created_by)
SELECT pg_temp.sid('ful:load-plan-type:' || o.key), o.plan_id, t.id, 0, 0, o.t0, pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5';

INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, shipment_id, active, added_at, added_by,
                                           version, created_at, created_by)
SELECT pg_temp.sid('ful:load-plan-package:' || o.key || ':' || c.n), o.plan_id, c.shipping_package_id, o.shipment_id, TRUE,
       o.t0, pg_temp.staff(o.mgr), 0, o.t0, pg_temp.staff(o.mgr)::text
  FROM h_carton c JOIN x_ful_pk o ON o.key = c.key;

INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, active, vehicle_type_id, vehicle_code, vehicle_kind,
                                           inner_length_mm, inner_width_mm, inner_height_mm, max_payload_kg, gusset_mm,
                                           used_volume_mm3, used_weight_kg, volume_fill_pct, weight_fill_pct,
                                           needs_recalculation, version, created_at, created_by)
SELECT o.vehicle_id, o.plan_id, 1, TRUE, t.id, t.code, t.kind, t.inner_length_mm, t.inner_width_mm, t.inner_height_mm,
       t.max_payload_kg, t.gusset_mm, x.volume, x.weight,
       round(x.volume * 100.0 / (t.inner_length_mm::numeric * t.inner_width_mm * t.inner_height_mm), 2),
       round(x.weight * 100 / t.max_payload_kg, 2), FALSE, 0, o.t0 + interval '5 min', pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o
  JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5'
  JOIN (SELECT key, sum((length_cm * width_cm * height_cm * 1000)::bigint) AS volume, sum(carton_kg) AS weight
          FROM h_carton GROUP BY key) x ON x.key = o.key;

INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm,
                                        h_mm, d_mm, orientation, version, created_at, created_by)
SELECT pg_temp.sid('ful:load-placement:' || o.key || ':' || c.n), o.plan_id, o.vehicle_id, c.shipping_package_id,
       ((c.n - 1) % 4) * 450, (((c.n - 1) / 4) % 3) * 600, ((c.n - 1) / 12) * 600, (c.width_cm * 10)::int,
       (c.height_cm * 10)::int, (c.length_cm * 10)::int, 0, 0, o.t0 + interval '5 min', pg_temp.staff(o.mgr)::text
  FROM h_carton c JOIN x_ful_pk o ON o.key = c.key;

INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, load_plan_vehicle_id, status,
                                       shipment_count, package_count, version, created_at, created_by)
SELECT o.load_id, o.load_code, pg_temp.sid('ful:load-request:' || o.key), pg_temp.sid('wh:' || o.wh), o.vehicle_id,
       'PLANNED', 0, 0, 0, o.t0 + interval '10 min', pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o;

INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, active, added_at, added_by, version,
                                                created_at, created_by)
SELECT pg_temp.sid('ful:load-shipment:' || o.key), o.load_id, o.shipment_id, TRUE, o.t0 + interval '10 min',
       pg_temp.staff(o.mgr), 0, o.t0 + interval '10 min', pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Trucks hired by hand: O108's with its waybill number, plate and driver; O107's with nothing yet but the carrier.
UPDATE fulfillment.dispatch_load l
   SET status = 'VEHICLE_BOOKED', carrier_id = pg_temp.sid('carrier:' || o.xt),
       vehicle_plate = CASE WHEN o.role = 'UNLOAD' THEN '61C-' || (10000 + o.seq * 97 % 89999) END,
       driver_name = CASE WHEN o.role = 'UNLOAD' THEN 'Phạm Văn Đường' END,
       driver_phone = CASE WHEN o.role = 'UNLOAD' THEN '0909' || lpad((100000 + o.seq * 313)::text, 6, '0') END,
       waybill_no = CASE WHEN o.role = 'UNLOAD' THEN left(o.xt, 5) || '-' || pg_temp.ymd(1) || '-' || o.seq END,
       booked_at = o.t0 + CASE WHEN o.role = 'UNLOAD' THEN interval '90 min' ELSE interval '40 min' END,
       booked_by = pg_temp.staff(o.mgr), version = 1,
       last_modified_at = o.t0 + CASE WHEN o.role = 'UNLOAD' THEN interval '90 min' ELSE interval '40 min' END,
       last_modified_by = pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o WHERE l.id = o.load_id AND o.role IN ('UNLOAD', 'IMPORT');

INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, source, changed_by, changed_at,
                                        version, created_at, created_by)
SELECT pg_temp.sid('ful:waybill:' || o.key), o.load_id, NULL, left(o.xt, 5) || '-' || pg_temp.ymd(1) || '-' || o.seq,
       'MANUAL', pg_temp.staff(o.mgr), o.t0 + interval '90 min', 0, o.t0 + interval '90 min', pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o WHERE o.role = 'UNLOAD';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- O108: loading starts at 17:30; nine cartons are scanned on before the truck's brakes fail.
UPDATE fulfillment.dispatch_load l
   SET status = 'LOADING', loading_started_at = pg_temp.at(1, '17:30'), loading_started_by = pg_temp.staff(o.kho),
       version = 2, last_modified_at = pg_temp.at(1, '17:30'), last_modified_by = pg_temp.staff(o.kho)::text
  FROM x_ful_pk o WHERE l.id = o.load_id AND o.role = 'UNLOAD';

INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by,
                                               active, version, created_at, created_by)
SELECT pg_temp.sid('ful:load-package:' || o.key || ':' || c.n), o.load_id, c.shipping_package_id, o.shipment_id,
       pg_temp.sid('ful:scan:' || o.key || ':' || c.n), pg_temp.at(1, '17:30') + (c.n || ' seconds')::interval * 30,
       pg_temp.staff(o.kho), TRUE, 0, pg_temp.at(1, '17:30') + (c.n || ' seconds')::interval * 30,
       pg_temp.staff(o.kho)::text
  FROM h_carton c JOIN x_ful_pk o ON o.key = c.key WHERE o.role = 'UNLOAD' AND c.n <= 9;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The controlled cancel at 17:50 (DispatchLoad.cancelControlled): the shipment off the manifest, the cartons off the
-- count but locked until scanned off, the load CANCELLED with its code and note.
UPDATE fulfillment.dispatch_load_package p
   SET active = FALSE, removed_at = pg_temp.at(1, '17:50'), removed_by = pg_temp.staff(o.mgr), version = 1,
       last_modified_at = pg_temp.at(1, '17:50'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o WHERE p.load_id = o.load_id AND o.role = 'UNLOAD';

UPDATE fulfillment.dispatch_load_shipment s
   SET active = FALSE, removed_at = pg_temp.at(1, '17:50'), removed_by = pg_temp.staff(o.mgr), version = 1,
       last_modified_at = pg_temp.at(1, '17:50'), last_modified_by = pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o WHERE s.load_id = o.load_id AND o.role = 'UNLOAD';

INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, requested_at, reason_code,
                                              overridden, version, created_at, created_by)
SELECT pg_temp.sid('ful:unload:' || o.key || ':' || c.n), o.load_id, c.shipping_package_id, o.shipment_id,
       'Xe ' || '61C-' || (10000 + o.seq * 97 % 89999) || ' báo hỏng phanh khi đang xếp hàng; dỡ kiện xuống chờ xe khác',
       pg_temp.at(1, '17:50'), 'XE_HONG', FALSE, 0, pg_temp.at(1, '17:50'), pg_temp.staff(o.mgr)::text
  FROM h_carton c JOIN x_ful_pk o ON o.key = c.key WHERE o.role = 'UNLOAD' AND c.n <= 9;

UPDATE fulfillment.dispatch_load l
   SET status = 'CANCELLED', cancelled_at = pg_temp.at(1, '17:50'), cancelled_by = pg_temp.staff(o.mgr),
       cancel_reason = 'Xe ' || '61C-' || (10000 + o.seq * 97 % 89999)
           || ' báo hỏng phanh khi đang xếp hàng; dỡ kiện xuống chờ xe khác',
       cancel_reason_code = 'XE_HONG', version = 3, last_modified_at = pg_temp.at(1, '17:50'),
       last_modified_by = pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o WHERE l.id = o.load_id AND o.role = 'UNLOAD';

-- A new PLANNED load takes the vehicle's place with the same manifest (DispatchService.openReplacement).
INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, load_plan_vehicle_id, status,
                                       shipment_count, package_count, version, created_at, created_by)
SELECT pg_temp.sid('ful:load-replacement:' || o.key), 'LD-' || pg_temp.ymd(1) || '-' || (7500 + o.seq),
       pg_temp.sid('ful:load-replacement-request:' || o.key), pg_temp.sid('wh:' || o.wh), o.vehicle_id, 'PLANNED', 0, 0,
       0, pg_temp.at(1, '17:50'), pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o WHERE o.role = 'UNLOAD';

INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, active, added_at, added_by, version,
                                                created_at, created_by)
SELECT pg_temp.sid('ful:load-replacement-shipment:' || o.key), pg_temp.sid('ful:load-replacement:' || o.key),
       o.shipment_id, TRUE, pg_temp.at(1, '17:50'), pg_temp.staff(o.mgr), 0, pg_temp.at(1, '17:50'),
       pg_temp.staff(o.mgr)::text
  FROM x_ful_pk o WHERE o.role = 'UNLOAD';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Scanned off back to the dispatch area from 18:00; the last carton's label was torn, so the manager confirmed it off.
UPDATE fulfillment.dispatch_load_unload u
   SET unloaded_at = CASE WHEN c.n < 9 THEN pg_temp.at(1, '18:00') + (c.n || ' minutes')::interval
                          ELSE pg_temp.at(1, '18:20') END,
       unloaded_by = CASE WHEN c.n < 9 THEN pg_temp.staff(o.kho) ELSE pg_temp.staff(o.mgr) END,
       overridden = c.n = 9,
       override_note = CASE WHEN c.n = 9 THEN 'Nhãn kiện bị rách khi dỡ, đã kiểm đếm bằng mắt và xác nhận đã xuống xe' END,
       version = 1,
       last_modified_at = CASE WHEN c.n < 9 THEN pg_temp.at(1, '18:00') + (c.n || ' minutes')::interval
                               ELSE pg_temp.at(1, '18:20') END,
       last_modified_by = CASE WHEN c.n < 9 THEN pg_temp.staff(o.kho) ELSE pg_temp.staff(o.mgr) END::text
  FROM h_carton c JOIN x_ful_pk o ON o.key = c.key
 WHERE o.role = 'UNLOAD' AND u.id = pg_temp.sid('ful:unload:' || o.key || ':' || c.n);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Ahamove bookings of O108's new load (19:00, never answered) and of O105's load (16:30, booked). O107's customer is in
-- Huế, outside the province Ahamove picks up in (E24, CARRIER_STOP_OUT_OF_AREA), so its truck is hired by hand.
CREATE TEMP TABLE x_ful_bk ON COMMIT DROP AS
SELECT o.key, o.seq, o.role, o.mgr, o.shipment_id, o.shipment_code, o.addr, o.contact, o.phone,
       CASE WHEN o.role = 'UNLOAD' THEN pg_temp.sid('ful:load-replacement:' || o.key) ELSE o.load_id END AS load_id,
       CASE WHEN o.role = 'UNLOAD' THEN 'LD-' || pg_temp.ymd(1) || '-' || (7500 + o.seq) ELSE o.load_code END AS load_code,
       CASE WHEN o.role = 'UNLOAD' THEN pg_temp.at(1, '19:00') ELSE pg_temp.at(1, '16:30') END AS t_claim,
       CASE WHEN o.role = 'UNLOAD' THEN pg_temp.at(0, '07:00') ELSE pg_temp.at(0, '07:30') END AS pickup_at,
       pg_temp.sid('ful:booking:' || o.key) AS booking_id, upper(substr(md5('ful:aha:' || o.key), 1, 10)) AS aha,
       p.address AS pickup_addr, p.contact_name AS pickup_name, p.contact_phone AS pickup_phone, sc.service_code,
       NULL::text AS body
  FROM x_ful_pk o
  JOIN fulfillment.carrier_pickup_point p
    ON p.carrier_id = pg_temp.sid('ful:carrier:AHAMOVE') AND p.warehouse_id = pg_temp.sid('wh:' || o.wh)
  JOIN fulfillment.carrier_service_code sc
    ON sc.carrier_id = p.carrier_id AND sc.province_code = p.province_code
   AND sc.vehicle_type_id = (SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-2T5')
 WHERE o.role IN ('UNLOAD', 'LIVE');

UPDATE x_ful_bk
   SET body = json_build_object(
           'order_time', pg_temp.ful_epoch(pickup_at),
           'path', json_build_array(json_build_object('address', pickup_addr, 'name', pickup_name, 'mobile', pickup_phone),
                                    json_build_object('address', addr, 'name', contact, 'mobile', phone,
                                                      'tracking_number', shipment_code)),
           'service_id', service_code, 'requests', json_build_array(), 'payment_method', 'BALANCE',
           'remarks', load_code)::text;

INSERT INTO fulfillment.carrier_booking (id, load_id, carrier_id, service_code, stops, order_time, status,
                                         requested_by, requested_at, cancel_attempts, version, created_at, created_by)
SELECT b.booking_id, b.load_id, pg_temp.sid('ful:carrier:AHAMOVE'), b.service_code,
       jsonb_build_array(jsonb_build_object('seq', 1, 'shipmentId', b.shipment_id::text, 'shipmentCode', b.shipment_code,
                                            'address', b.addr)),
       b.pickup_at, 'REQUESTED', pg_temp.staff(b.mgr), b.t_claim, 0, 0, b.t_claim, pg_temp.staff(b.mgr)::text
  FROM x_ful_bk b;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO fulfillment.carrier_api_call (id, carrier_id, booking_id, operation, outcome, http_status, request_body,
                                          response_body, error, duration_ms, called_at, version, created_at, created_by)
SELECT pg_temp.sid('ful:api:token:' || b.key), pg_temp.sid('ful:carrier:AHAMOVE'), b.booking_id, 'TOKEN', 'OK', 200,
       NULL, NULL, NULL, 260, b.t_claim, 0, b.t_claim, pg_temp.staff(b.mgr)::text
  FROM x_ful_bk b;

INSERT INTO fulfillment.carrier_api_call (id, carrier_id, booking_id, operation, outcome, http_status, request_body,
                                          response_body, error, duration_ms, called_at, version, created_at, created_by)
SELECT pg_temp.sid('ful:api:book:' || b.key), pg_temp.sid('ful:carrier:AHAMOVE'), b.booking_id, 'BOOK',
       CASE WHEN b.role = 'UNLOAD' THEN 'NO_ANSWER' ELSE 'OK' END, CASE WHEN b.role = 'UNLOAD' THEN NULL ELSE 200 END,
       b.body,
       CASE WHEN b.role = 'LIVE' THEN json_build_object('order_id', b.aha, 'status', 'ASSIGNING',
                                                       'shared_link', 'https://cloud.ahamove.com/share-order/' || b.aha)::text END,
       CASE WHEN b.role = 'UNLOAD' THEN 'ResourceAccessException' END,
       CASE WHEN b.role = 'UNLOAD' THEN 30004 ELSE 1310 END,
       b.t_claim + CASE WHEN b.role = 'UNLOAD' THEN interval '30 s' ELSE interval '2 s' END, 0,
       b.t_claim + CASE WHEN b.role = 'UNLOAD' THEN interval '30 s' ELSE interval '2 s' END, pg_temp.staff(b.mgr)::text
  FROM x_ful_bk b;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- No answer is not a refusal: the claim keeps the load (CarrierBookingService.unconfirmed).
UPDATE fulfillment.carrier_booking k
   SET status = 'UNCONFIRMED',
       last_error = 'The carrier did not answer (Ahamove did not answer) and may have created the order: look on its '
           || 'portal for an order with remark ' || b.load_code || ', cancel it there if it exists, then cancel this '
           || 'booking to book again',
       version = 1, last_modified_at = b.t_claim + interval '30 s', last_modified_by = pg_temp.staff(b.mgr)::text
  FROM x_ful_bk b WHERE k.id = b.booking_id AND b.role = 'UNLOAD';

UPDATE fulfillment.dispatch_load l
   SET status = 'VEHICLE_BOOKED', carrier_id = pg_temp.sid('ful:carrier:AHAMOVE'), waybill_no = b.aha,
       booked_at = b.t_claim + interval '3 s', booked_by = pg_temp.staff(b.mgr), version = 1,
       last_modified_at = b.t_claim + interval '3 s', last_modified_by = pg_temp.staff(b.mgr)::text
  FROM x_ful_bk b WHERE l.id = b.load_id AND b.role = 'LIVE';

INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, source, changed_by, changed_at,
                                        version, created_at, created_by)
SELECT pg_temp.sid('ful:waybill:api-on:' || b.key), b.load_id, NULL, b.aha, 'API', pg_temp.staff(b.mgr),
       b.t_claim + interval '3 s', 0, b.t_claim + interval '3 s', pg_temp.staff(b.mgr)::text
  FROM x_ful_bk b WHERE b.role = 'LIVE';

UPDATE fulfillment.carrier_booking k
   SET status = 'BOOKED', carrier_order_id = b.aha, tracking_link = 'https://cloud.ahamove.com/share-order/' || b.aha,
       booked_at = b.t_claim + interval '3 s', version = 1, last_modified_at = b.t_claim + interval '3 s',
       last_modified_by = pg_temp.staff(b.mgr)::text
  FROM x_ful_bk b WHERE k.id = b.booking_id AND b.role = 'LIVE';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- O105: a driver took the order at 16:48; the load now names him.
INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, event_kind,
                                       carrier_reference, carrier_reported_at, dedupe_key, warehouse_id, load_id,
                                       shipment_id, result, result_note, decided_at, version, created_at, created_by,
                                       source)
SELECT pg_temp.sid('ful:event:' || b.key || ':accepted'), pg_temp.sid('ful:carrier:AHAMOVE'), 'AHAMOVE',
       pg_temp.at(1, '16:48') + interval '2 s',
       json_build_object('_id', b.aha, 'status', 'ACCEPTED', 'service_id', b.service_code, 'supplier_id', '0976554433',
                         'supplier_name', 'Đoàn Văn Lực', 'accept_time', pg_temp.ful_epoch(pg_temp.at(1, '16:48')),
                         'order_time', pg_temp.ful_epoch(b.pickup_at))::text,
       TRUE, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'RECEIVED', NULL, NULL, 0, pg_temp.at(1, '16:48') + interval '2 s',
       'system', 'WEBHOOK'
  FROM x_ful_bk b WHERE b.role = 'LIVE';

UPDATE fulfillment.carrier_event e
   SET event_kind = 'DRIVER_ASSIGNED', carrier_reference = b.aha, carrier_reported_at = pg_temp.at(1, '16:48'),
       warehouse_id = pg_temp.sid('wh:BDG'), load_id = b.load_id,
       dedupe_key = pg_temp.ful_key(pg_temp.sid('ful:carrier:AHAMOVE'), 'DRIVER_ASSIGNED', b.aha, NULL, NULL,
                                    pg_temp.at(1, '16:48'), NULL),
       version = 1, last_modified_at = pg_temp.at(1, '16:48') + interval '2 s', last_modified_by = 'system'
  FROM x_ful_bk b WHERE e.id = pg_temp.sid('ful:event:' || b.key || ':accepted') AND b.role = 'LIVE';

UPDATE fulfillment.dispatch_load l
   SET driver_name = 'Đoàn Văn Lực', driver_phone = '0976554433', version = 2,
       last_modified_at = pg_temp.at(1, '16:48') + interval '3 s', last_modified_by = 'system'
  FROM x_ful_bk b WHERE l.id = b.load_id AND b.role = 'LIVE';

UPDATE fulfillment.carrier_event e
   SET result = 'APPLIED', result_note = 'The driver on the load', decided_at = pg_temp.at(1, '16:48') + interval '3 s',
       version = 2, last_modified_at = pg_temp.at(1, '16:48') + interval '3 s', last_modified_by = 'system'
  FROM x_ful_bk b WHERE e.id = pg_temp.sid('ful:event:' || b.key || ':accepted') AND b.role = 'LIVE';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 40.4 Waybill numbers imported from a carrier's file (09 §4.4, WaybillImportService): a line that matches what the
--      load holds changes nothing (SAME), a line naming a load without its number fills it in (NEW, the file kept
--      with the load), the rest are refused line by line.
-- ---------------------------------------------------------------------------------------------
-- O107: Tân Phát's file of yesterday evening.
CREATE TEMP TABLE x_ful_imp ON COMMIT DROP AS
SELECT o.load_id, o.load_code, o.seq, o.mgr, o.xt,
       left(o.xt, 5) || '-' || pg_temp.ymd(1) || '-' || o.seq AS waybill,
       '61C-' || (10000 + o.seq * 131 % 89999) AS plate, 'Trần Minh Lái' AS driver,
       '0909' || lpad((200000 + o.seq * 271)::text, 6, '0') AS phone,
       pg_temp.sid('ful:waybill-import:packed') AS import_id,
       'van-don-tan-phat-' || pg_temp.ymd(1) || '.xlsx' AS file_name,
       'waybill-imports/showcase/' || pg_temp.ymd(1) || '/' || pg_temp.sid('ful:waybill-import-file:packed') || '.xlsx'
           AS storage_key
  FROM x_ful_pk o WHERE o.role = 'IMPORT';

UPDATE fulfillment.dispatch_load l
   SET waybill_no = i.waybill, vehicle_plate = i.plate, driver_name = i.driver, driver_phone = i.phone, version = 2,
       last_modified_at = pg_temp.at(1, '18:30'), last_modified_by = pg_temp.staff(i.mgr)::text
  FROM x_ful_imp i WHERE l.id = i.load_id;

INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, source, changed_by, changed_at,
                                        reason, version, created_at, created_by)
SELECT pg_temp.sid('ful:waybill:file:' || i.seq), i.load_id, NULL, i.waybill, 'FILE', pg_temp.staff(i.mgr),
       pg_temp.at(1, '18:30'), 'File ' || i.file_name || ', line 2', 0, pg_temp.at(1, '18:30'), pg_temp.staff(i.mgr)::text
  FROM x_ful_imp i;

INSERT INTO fulfillment.waybill_import (id, file_name, storage_key, uploaded_by, uploaded_at, rows_total, rows_applied,
                                        rows_rejected, version, created_at, created_by, warehouse_id)
SELECT i.import_id, i.file_name, i.storage_key, pg_temp.staff(i.mgr), pg_temp.at(1, '18:30'), 3, 1, 2, 0,
       pg_temp.at(1, '18:30'), pg_temp.staff(i.mgr)::text, pg_temp.sid('wh:BDG')
  FROM x_ful_imp i;

INSERT INTO fulfillment.waybill_import_row (id, import_id, line_no, load_code, waybill_no, vehicle_plate, driver_name,
                                            driver_phone, load_id, outcome, problem, version, created_at, created_by)
SELECT pg_temp.sid('ful:waybill-import-row:packed:' || r.line_no), i.import_id, r.line_no, r.load_code, r.waybill,
       r.plate, r.driver, r.phone, r.load_id, r.outcome, r.problem, 0, pg_temp.at(1, '18:30'), pg_temp.staff(i.mgr)::text
  FROM x_ful_imp i
 CROSS JOIN LATERAL (VALUES
       (2, i.load_code, i.waybill, i.plate, i.driver, i.phone, i.load_id, 'NEW', NULL),
       (3, 'LD-' || pg_temp.ymd(1) || '-' || (7500 + 108), left(i.xt, 5) || '-' || pg_temp.ymd(1) || '-108b',
        '61C-' || (10000 + 108 * 131 % 89999), 'Lê Văn Bình', '0909318440',
        pg_temp.sid('ful:load-replacement:O108'), 'ERROR', 'LOAD_NOT_BOOKED'),
       (4, (SELECT code FROM fulfillment.dispatch_load WHERE id = pg_temp.sid('load:O291')),
        left(i.xt, 5) || '-' || pg_temp.ymd(1) || '-291', '61C-48822', 'Lý Văn Tài', '0909191083',
        pg_temp.sid('load:O291'), 'ERROR', 'LOAD_LEFT')) AS r (line_no, load_code, waybill, plate, driver, phone, load_id,
                                                              outcome, problem);

INSERT INTO fulfillment.load_waybill_file (id, load_id, storage_key, file_name, content_type, size_bytes, uploaded_by,
                                           uploaded_at, version, created_at, created_by, waybill_import_id)
SELECT pg_temp.sid('ful:waybill-file:import:' || i.seq), i.load_id, i.storage_key, i.file_name,
       'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 9216, pg_temp.staff(i.mgr),
       pg_temp.at(1, '18:30'), 0, pg_temp.at(1, '18:30'), pg_temp.staff(i.mgr)::text, i.import_id
  FROM x_ful_imp i;

-- Hoàng Long's file of the evening O291's truck was booked: its own line matches (SAME, nothing written); a load
-- that left days before, a code no load has, a number with spaces and a line without a number are refused.
INSERT INTO fulfillment.waybill_import (id, file_name, storage_key, uploaded_by, uploaded_at, rows_total, rows_applied,
                                        rows_rejected, version, created_at, created_by, warehouse_id)
VALUES (pg_temp.sid('ful:waybill-import:hoanglong'), 'van-don-hoang-long-' || pg_temp.ymd(35) || '.csv',
        'waybill-imports/showcase/' || pg_temp.ymd(35) || '/' || pg_temp.sid('ful:waybill-import-file:hoanglong') || '.csv',
        pg_temp.staff('quanly.bd'), pg_temp.at(35, '17:00'), 5, 1, 4, 0, pg_temp.at(35, '17:00'),
        pg_temp.staff('quanly.bd')::text, pg_temp.sid('wh:BDG'));

INSERT INTO fulfillment.waybill_import_row (id, import_id, line_no, load_code, waybill_no, vehicle_plate, driver_name,
                                            driver_phone, load_id, outcome, problem, version, created_at, created_by)
SELECT pg_temp.sid('ful:waybill-import-row:hoanglong:' || r.line_no), pg_temp.sid('ful:waybill-import:hoanglong'),
       r.line_no, r.load_code, r.waybill, r.plate, r.driver, r.phone, r.load_id, r.outcome, r.problem, 0,
       pg_temp.at(35, '17:00'), pg_temp.staff('quanly.bd')::text
  FROM fulfillment.dispatch_load a, fulfillment.dispatch_load b
 CROSS JOIN LATERAL (VALUES
       (2, a.code, a.waybill_no, a.vehicle_plate, a.driver_name, a.driver_phone, a.id, 'SAME', NULL),
       (3, b.code, b.waybill_no, b.vehicle_plate, b.driver_name, b.driver_phone, b.id, 'ERROR', 'LOAD_LEFT'),
       (4, 'LD-' || pg_temp.ymd(35) || '-7990', 'XT-HO-' || pg_temp.ymd(35) || '-990', '61C-55102', 'Hồ Văn Sang',
        '0909550102', NULL::uuid, 'ERROR', 'LOAD_NOT_FOUND'),
       (5, a.code, 'HL 0926 ' || right(a.waybill_no, 3), a.vehicle_plate, a.driver_name, a.driver_phone, NULL::uuid,
        'ERROR', 'INVALID_WAYBILL'),
       (6, a.code, NULL, '61C-77310', NULL, NULL, NULL::uuid, 'ERROR', 'MISSING_FIELDS'))
       AS r (line_no, load_code, waybill, plate, driver, phone, load_id, outcome, problem)
 WHERE a.id = pg_temp.sid('load:O291') AND b.id = pg_temp.sid('load:O219');

-- The carriers' waybills kept with loads that left (09 BR-28): scans and photos the manager attached once booked.
INSERT INTO fulfillment.load_waybill_file (id, load_id, storage_key, file_name, content_type, size_bytes, uploaded_by,
                                           uploaded_at, version, created_at, created_by)
SELECT pg_temp.sid('ful:waybill-file:' || o.key), l.id,
       'documents/showcase/van-don/' || l.code || CASE WHEN o.seq % 2 = 0 THEN '.pdf' ELSE '.jpg' END,
       CASE WHEN o.seq % 2 = 0 THEN 'van-don-' || l.waybill_no || '.pdf' ELSE 'anh-van-don-' || l.waybill_no || '.jpg' END,
       CASE WHEN o.seq % 2 = 0 THEN 'application/pdf' ELSE 'image/jpeg' END, 180000 + o.seq * 1931,
       pg_temp.staff(o.mgr), pg_temp.at(o.d + 1, '15:20'), 0, pg_temp.at(o.d + 1, '15:20'), pg_temp.staff(o.mgr)::text
  FROM h_order o JOIN fulfillment.dispatch_load l ON l.id = pg_temp.sid('load:' || o.key)
 WHERE o.key IN ('O135', 'O147', 'O207', 'O267', 'O291');

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 40.5 Load estimates (LP2, 09 §4.0): the manager estimates the truck in the release dialog, with the warehouse
--      chosen, the morning of the release; three orders were estimated the afternoon before as well (fewest vehicles
--      of two types), and that estimate was superseded. Every carton is the SKU's carton (labels are the SKU: the
--      order's holds name no carton yet).
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_ful_est ON COMMIT DROP AS
SELECT o.key, o.id AS order_id, o.wh, o.mgr, o.d,
       jsonb_agg(jsonb_build_object(
           'orderLineId', l.line_id, 'sku', l.sku, 'inventoryItemId', i.id, 'quantity', l.cartons * u.factor,
           'rule', 'DEFAULT_SHIPPING_UNIT', 'uom', u.unit_of_measure, 'factor', u.factor, 'cartons', l.cartons,
           'partial', NULL,
           'dimsMm', jsonb_build_object('l', (u.length_cm * 10)::int, 'w', (u.width_cm * 10)::int,
                                        'h', (u.height_cm * 10)::int),
           'weightKg', u.weight_kg, 'rotatable', i.rotatable, 'kitParts', 1) ORDER BY l.ln) AS lines
  FROM h_order o
  JOIN h_line l ON l.key = o.key
  JOIN inventory.inventory_items i ON i.sku = l.sku
  JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id AND u.default_shipping AND u.active
 WHERE o.stage IN ('READY', 'PICKING', 'PACKED')
 GROUP BY o.key, o.id, o.wh, o.mgr, o.d;

ALTER TABLE x_ful_est ADD COLUMN boxes JSONB, ADD COLUMN volume BIGINT, ADD COLUMN weight NUMERIC, ADD COLUMN placed INTEGER;
UPDATE x_ful_est e
   SET boxes = b.boxes, volume = b.volume, weight = b.weight, placed = b.placed
  FROM (SELECT c.key,
               jsonb_agg(jsonb_build_object(
                   'xMm', ((c.n - 1) % 4) * 450, 'yMm', (((c.n - 1) / 4) % 3) * 600, 'zMm', ((c.n - 1) / 12) * 600,
                   'wMm', (u.width_cm * 10)::int, 'hMm', (u.height_cm * 10)::int, 'dMm', (u.length_cm * 10)::int,
                   'ref', c.line_id || '#' || (c.n - l.first_n + 1), 'sourceRef', c.line_id, 'label', c.sku,
                   'partName', c.sku, 'kitGroup', NULL,
                   'origMm', jsonb_build_object('l', (u.length_cm * 10)::int, 'w', (u.width_cm * 10)::int,
                                                'h', (u.height_cm * 10)::int),
                   'orientation', 'LWH', 'rotatable', i.rotatable, 'weightKg', u.weight_kg, 'loadSequence', c.n)
                   ORDER BY c.n) AS boxes,
               sum((u.length_cm * u.width_cm * u.height_cm * 1000)::bigint) AS volume, sum(u.weight_kg) AS weight,
               count(*)::int AS placed
          FROM h_carton c
          JOIN h_line l ON l.key = c.key AND l.ln = c.ln
          JOIN inventory.inventory_items i ON i.sku = c.sku
          JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id AND u.default_shipping AND u.active
         GROUP BY c.key) b
 WHERE b.key = e.key;

CREATE TEMP TABLE x_ful_est_row ON COMMIT DROP AS
SELECT e.*, v.tag, v.at, v.mode, v.types
  FROM x_ful_est e
 CROSS JOIN LATERAL (
       SELECT 'release' AS tag, pg_temp.at(e.d + 3, '08:50') AS at, 'SINGLE_TYPE' AS mode,
              jsonb_build_array((SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-2T5')) AS types
       UNION ALL
       SELECT 'eve', pg_temp.at(e.d + 4, '16:00'), 'FEWEST_VEHICLES',
              jsonb_build_array((SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25'),
                                (SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-2T5'))
        WHERE e.key IN ('O096', 'O101', 'O105')) v;

-- The earlier ones first, CURRENT until the release-morning estimate supersedes them.
INSERT INTO fulfillment.load_estimate (id, source_type, source_id, warehouse_id, vehicle_type_ids, mode, status, lines,
                                       layout, warnings, missing, incomplete, version, created_at, created_by)
SELECT pg_temp.sid('ful:estimate:' || r.key || ':' || r.tag), 'ORDER', r.order_id, pg_temp.sid('wh:' || r.wh), r.types,
       r.mode, 'CURRENT', r.lines,
       jsonb_build_object(
           'vehicles', jsonb_build_array(jsonb_build_object(
               'seq', 1, 'code', t.code, 'vehicleTypeId', t.id,
               'innerMm', jsonb_build_object('l', t.inner_length_mm, 'w', t.inner_width_mm, 'h', t.inner_height_mm),
               'maxPayloadKg', t.max_payload_kg, 'gussets', '[]'::jsonb, 'boxes', r.boxes, 'usedVolumeMm3', r.volume,
               'usedWeightKg', r.weight,
               'volumeFillPct', round(r.volume * 100.0 / (t.inner_length_mm::numeric * t.inner_width_mm * t.inner_height_mm), 2),
               'weightFillPct', round(r.weight * 100 / t.max_payload_kg, 2))),
           'unplaced', '[]'::jsonb,
           'meta', jsonb_build_object('seed', 0, 'budgetMillis', 5000, 'algorithm', 'GRASP_KIT', 'setsTotal', 0,
                                      'minVehicles', 1, 'budgetExhausted', FALSE,
                                      'elapsedMillis', 180 + r.placed * 9 + CASE WHEN r.tag = 'eve' THEN 640 ELSE 0 END,
                                      'bestFoundMillis', 40 + r.placed * 3, 'placedBoxes', r.placed, 'unplacedBoxes', 0,
                                      'kitBalance', '[]'::jsonb, 'kitWarnings', '[]'::jsonb)),
       '[]'::jsonb, '[]'::jsonb, FALSE, 0, r.at, pg_temp.staff(r.mgr)::text
  FROM x_ful_est_row r JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5'
 WHERE r.tag = 'eve';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE fulfillment.load_estimate e
   SET status = 'SUPERSEDED', version = 1, last_modified_at = pg_temp.at(r.d + 3, '08:50'),
       last_modified_by = pg_temp.staff(r.mgr)::text
  FROM x_ful_est_row r WHERE r.tag = 'eve' AND e.id = pg_temp.sid('ful:estimate:' || r.key || ':eve');

INSERT INTO fulfillment.load_estimate (id, source_type, source_id, warehouse_id, vehicle_type_ids, mode, status, lines,
                                       layout, warnings, missing, incomplete, version, created_at, created_by)
SELECT pg_temp.sid('ful:estimate:' || r.key || ':' || r.tag), 'ORDER', r.order_id, pg_temp.sid('wh:' || r.wh), r.types,
       r.mode, 'CURRENT', r.lines,
       jsonb_build_object(
           'vehicles', jsonb_build_array(jsonb_build_object(
               'seq', 1, 'code', t.code, 'vehicleTypeId', t.id,
               'innerMm', jsonb_build_object('l', t.inner_length_mm, 'w', t.inner_width_mm, 'h', t.inner_height_mm),
               'maxPayloadKg', t.max_payload_kg, 'gussets', '[]'::jsonb, 'boxes', r.boxes, 'usedVolumeMm3', r.volume,
               'usedWeightKg', r.weight,
               'volumeFillPct', round(r.volume * 100.0 / (t.inner_length_mm::numeric * t.inner_width_mm * t.inner_height_mm), 2),
               'weightFillPct', round(r.weight * 100 / t.max_payload_kg, 2))),
           'unplaced', '[]'::jsonb,
           'meta', jsonb_build_object('seed', 0, 'budgetMillis', 5000, 'algorithm', 'GRASP_KIT', 'setsTotal', 0,
                                      'minVehicles', 1, 'budgetExhausted', FALSE,
                                      'elapsedMillis', 180 + r.placed * 9, 'bestFoundMillis', 40 + r.placed * 3,
                                      'placedBoxes', r.placed, 'unplacedBoxes', 0, 'kitBalance', '[]'::jsonb,
                                      'kitWarnings', '[]'::jsonb)),
       '[]'::jsonb, '[]'::jsonb, FALSE, 0, r.at, pg_temp.staff(r.mgr)::text
  FROM x_ful_est_row r JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5'
 WHERE r.tag = 'release';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

DROP FUNCTION pg_temp.ful_key(UUID, TEXT, TEXT, INTEGER, TEXT, TIMESTAMPTZ, TEXT);
DROP FUNCTION pg_temp.ful_iso(TIMESTAMPTZ);
DROP FUNCTION pg_temp.ful_epoch(TIMESTAMPTZ);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 50. Delivery outcomes and delivery failures (group dlf; 09 §4.3, §4.5; 03 §4.2; 17 §5.1 RTM-RETURNED)
--
--   What the application does today, which is not what CLAUDE.md §4 still describes (commits 4b646b55, 0d423ae0,
--   19/11): a shipment the carrier brings back closes its units and the order goes RETURNED (nothing delivered), its
--   money settled as a closure of the rest; the return receipt that takes the goods back opens the delivery failure
--   case and cancels it at once (DeliveryFailureService.goodsReceived) - there is no redelivery decision any more, so
--   no case stays OPEN or goes REDELIVERY and ordering.delivery_failure_case_hold stays empty.
--
--   The six SHIPPED orders (handed over yesterday at 08:00), by their place in the stage (h_order.i):
--     1 - the carrier's outcome file says DELIVERED: applied, the order DELIVERED, a POD photo uploaded;
--     2 - the file says not delivered in the carrier's own words (OTHER); the warehouse manager corrects the reason;
--     3 - the file says DAMAGED; the manager corrects the outcome to DELIVERED (the carrier was wrong), POD uploaded;
--     4 - the file says REFUSED; the reason's note corrected, the truck brings the goods back (RETURNED), the order
--         RETURNED and settled, a return receipt takes the cartons into quarantine, the case opened and closed;
--     5, 6 - still on the road (SHIPPED).
--   Delivered orders with i % 12 = 5 are read only: the carrier's daily file of each repeats a delivery recorded by
--   hand already (IGNORED), some files with a refused line; each gets proof-of-delivery files.
--   Tracking milestones are not written: only a carrier webhook records one (TrackingMilestoneJpaRepository
--   .recordFromCarrier), and the three truck carriers are MANUAL with webhooks off.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_dlf_o ON COMMIT DROP AS
SELECT o.key, o.seq, o.i, o.id AS order_id, o.order_number, o.wh, o.mgr, o.kho, o.credit, o.term_days, o.customer_id,
       o.contract_id, o.contract_number, o.batch_id, o.cartons, o.po_code,
       s.id AS shipment_id, s.code AS shipment_code, l.id AS load_id, l.code AS load_code, l.waybill_no,
       l.carrier_id, k.code AS carrier_code, l.warehouse_id, c.contact AS recipient,
       CASE o.i WHEN 1 THEN 'DELIVERED' WHEN 2 THEN 'REASON' WHEN 3 THEN 'CORRECTED' ELSE 'RETURNED' END AS role
  FROM h_order o
  JOIN fulfillment.shipment s ON s.id = pg_temp.sid('shipment:' || o.key)
  JOIN fulfillment.dispatch_load l ON l.id = s.load_id
  JOIN fulfillment.carrier k ON k.id = l.carrier_id
  JOIN s_customer c ON c.code = o.cust
 WHERE o.stage = 'SHIPPED' AND o.i <= 4;

-- ---------------------------------------------------------------------------------------------
-- 50.1 The carriers' outcome files (WF-05 fix plan item 5): the manager downloads the carrier's daily list and imports
--      it; each line is checked against the vehicle its waybill names and, accepted, becomes a carrier event (source
--      FILE) applied as the carrier's own report.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_dlf_imp (imp TEXT PRIMARY KEY, file_name TEXT, uploaded_by TEXT, uploaded_at TIMESTAMPTZ)
    ON COMMIT DROP;
CREATE TEMP TABLE x_dlf_line (imp TEXT, line_no INTEGER, waybill_no TEXT, outcome TEXT, failure_cell TEXT,
                              reported_text TEXT, recipient TEXT, note TEXT, shipment_code TEXT, carrier_code TEXT,
                              load_id UUID, problem TEXT, okey TEXT, reported_at TIMESTAMPTZ, reason TEXT,
                              result TEXT, result_note TEXT) ON COMMIT DROP;

-- The returned order's carrier sent its morning list early: one line for the refused truck, one waybill mistyped.
INSERT INTO x_dlf_imp
SELECT 'r4:' || key, initcap(replace(lower(substring(carrier_code FROM 4)), '-', '')) || '_ket-qua-giao_'
       || to_char(pg_temp.day(1), 'DD-MM') || '_dot1.csv', mgr, pg_temp.at(1, '12:30')
  FROM x_dlf_o WHERE role = 'RETURNED';

INSERT INTO x_dlf_line (imp, line_no, waybill_no, outcome, failure_cell, reported_text, recipient, note, shipment_code,
                        carrier_code, load_id, problem, okey, reported_at, reason, result, result_note)
SELECT 'r4:' || key, 2, waybill_no, 'DELIVERY_FAILED', 'REFUSED', to_char(pg_temp.day(1), 'YYYY-MM-DD') || ' 11:15',
       NULL, 'Khách không nhận hàng', NULL, NULL, load_id, NULL, key, pg_temp.at(1, '11:15'), 'REFUSED', 'APPLIED',
       'DELIVERY_FAILED: 1 shipment(s) (REFUSED)'
  FROM x_dlf_o WHERE role = 'RETURNED'
UNION ALL
SELECT 'r4:' || key, 3, waybill_no || '0', 'DELIVERY_FAILED', 'CUSTOMER_ABSENT',
       to_char(pg_temp.day(1), 'YYYY-MM-DD') || ' 10:40', NULL, 'Gọi khách không nghe máy', NULL, NULL, NULL,
       'WAYBILL_NOT_FOUND', NULL, NULL, NULL, NULL, NULL
  FROM x_dlf_o WHERE role = 'RETURNED';

-- Each carrier's evening list of the other three: one file per carrier, in the order the trucks left.
INSERT INTO x_dlf_imp
SELECT 'ev:' || carrier_code,
       initcap(replace(lower(substring(carrier_code FROM 4)), '-', '')) || '_ket-qua-giao_'
       || to_char(pg_temp.day(1), 'DD-MM') || '.csv', min(mgr),
       pg_temp.at(1, '18:00') + ((dense_rank() OVER (ORDER BY carrier_code) - 1) * 10 || ' minutes')::interval
  FROM x_dlf_o WHERE role <> 'RETURNED' GROUP BY carrier_code;

INSERT INTO x_dlf_line (imp, line_no, waybill_no, outcome, failure_cell, reported_text, recipient, note, shipment_code,
                        carrier_code, load_id, problem, okey, reported_at, reason, result, result_note)
SELECT 'ev:' || carrier_code, 1 + row_number() OVER (PARTITION BY carrier_code ORDER BY seq)::int, waybill_no,
       CASE role WHEN 'DELIVERED' THEN 'DELIVERED' ELSE 'DELIVERY_FAILED' END,
       CASE role WHEN 'REASON' THEN 'Khách hẹn giao lại ngày khác' WHEN 'CORRECTED' THEN 'DAMAGED' END,
       to_char(pg_temp.day(1), 'YYYY-MM-DD') || CASE role WHEN 'DELIVERED' THEN ' 14:20' WHEN 'REASON' THEN ' 16:30'
                                                          ELSE ' 15:10' END,
       CASE role WHEN 'DELIVERED' THEN recipient END,
       CASE role WHEN 'DELIVERED' THEN 'Giao đủ ' || cartons || ' thùng, khách ký biên bản'
                 WHEN 'CORRECTED' THEN 'Thùng bị ướt mưa, khách không nhận' END,
       CASE role WHEN 'DELIVERED' THEN shipment_code END, NULL, load_id, NULL, key,
       pg_temp.at(1, CASE role WHEN 'DELIVERED' THEN '14:20' WHEN 'REASON' THEN '16:30' ELSE '15:10' END),
       CASE role WHEN 'REASON' THEN 'OTHER' WHEN 'CORRECTED' THEN 'DAMAGED' END, 'APPLIED',
       CASE role WHEN 'DELIVERED' THEN 'DELIVERED: 1 shipment(s)' WHEN 'REASON' THEN 'DELIVERY_FAILED: 1 shipment(s) (OTHER)'
                 ELSE 'DELIVERY_FAILED: 1 shipment(s) (DAMAGED)' END
  FROM x_dlf_o WHERE role <> 'RETURNED';

-- The daily lists of past deliveries (delivered orders with i % 12 = 5): the manager recorded the delivery by hand at
-- 15:00 and imports the carrier's list that evening; its line changes nothing (IGNORED). Some files hold a line the
-- check refuses.
CREATE TEMP TABLE x_dlf_hist ON COMMIT DROP AS
SELECT o.key, o.seq, o.i, o.d, o.mgr, o.cartons, l.id AS load_id, l.waybill_no, k.code AS carrier_code, c.contact
  FROM h_delivered o
  JOIN fulfillment.dispatch_load l ON l.id = pg_temp.sid('load:' || o.key)
  JOIN fulfillment.carrier k ON k.id = l.carrier_id
  JOIN s_customer c ON c.code = o.cust
 WHERE o.i % 12 = 5;

INSERT INTO x_dlf_imp
SELECT 'h:' || key, initcap(replace(lower(substring(carrier_code FROM 4)), '-', '')) || '_ket-qua-giao_'
       || to_char(pg_temp.day(d), 'DD-MM') || '.csv', mgr, pg_temp.at(d, '17:30')
  FROM x_dlf_hist;

INSERT INTO x_dlf_line (imp, line_no, waybill_no, outcome, failure_cell, reported_text, recipient, note, shipment_code,
                        carrier_code, load_id, problem, okey, reported_at, reason, result, result_note)
SELECT 'h:' || key, 2, waybill_no, 'DELIVERED', NULL,
       to_char((pg_temp.at(d, '14:00') + (pg_temp.pick('dlf:pod-time:' || key, 0, 55) || ' minutes')::interval)
               AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI'),
       contact, 'Giao đủ ' || cartons || ' thùng', NULL, NULL, load_id, NULL, key,
       date_trunc('minute', pg_temp.at(d, '14:00') + (pg_temp.pick('dlf:pod-time:' || key, 0, 55) || ' minutes')::interval),
       NULL, 'IGNORED', 'The shipments were DELIVERED already'
  FROM x_dlf_hist
UNION ALL
-- A waybill typed with a digit too many: no vehicle has it.
SELECT 'h:' || key, 3, waybill_no || '7', 'DELIVERED', NULL, to_char(pg_temp.day(d), 'YYYY-MM-DD') || ' 16:05',
       'Thủ kho khách', NULL, NULL, NULL, NULL, 'WAYBILL_NOT_FOUND', NULL, NULL, NULL, NULL, NULL
  FROM x_dlf_hist WHERE i % 72 = 5
UNION ALL
-- A failure without its reason.
SELECT 'h:' || key, 3, waybill_no, 'DELIVERY_FAILED', NULL, to_char(pg_temp.day(d), 'YYYY-MM-DD') || ' 11:30', NULL,
       NULL, NULL, NULL, NULL, 'REASON_REQUIRED', NULL, NULL, NULL, NULL, NULL
  FROM x_dlf_hist WHERE i % 72 = 29
UNION ALL
-- The pickup time typed as the delivery time: before the vehicle left.
SELECT 'h:' || key, 3, waybill_no, 'DELIVERED', NULL, to_char(pg_temp.day(d), 'YYYY-MM-DD') || ' 07:15', contact,
       NULL, NULL, NULL, load_id, 'TIME_BEFORE_DISPATCH', NULL, NULL, NULL, NULL, NULL
  FROM x_dlf_hist WHERE i % 72 = 53
UNION ALL
-- No time at all.
SELECT 'h:' || key, 3, waybill_no, 'DELIVERED', NULL, NULL, contact, 'Đã giao', NULL, NULL, NULL, 'MISSING_FIELDS',
       NULL, NULL, NULL, NULL, NULL
  FROM x_dlf_hist WHERE i % 72 = 17;

INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, uploaded_by, uploaded_at, rows_total,
                                                 rows_accepted, rows_rejected, version, created_at, created_by)
SELECT pg_temp.sid('dlf:import:' || m.imp), m.file_name,
       encode(sha256(convert_to('dlf:file:' || m.imp, 'UTF8')), 'hex'), pg_temp.staff(m.uploaded_by), m.uploaded_at,
       x.total, x.accepted, x.total - x.accepted, 0, m.uploaded_at, pg_temp.staff(m.uploaded_by)::text
  FROM x_dlf_imp m
  JOIN (SELECT imp, count(*)::int AS total, count(*) FILTER (WHERE problem IS NULL)::int AS accepted
          FROM x_dlf_line GROUP BY imp) x ON x.imp = m.imp;

INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, outcome, failure_reason,
                                                     reported_at, recipient, note, shipment_code, carrier_code, load_id,
                                                     verdict, problem, version, created_at, created_by)
SELECT pg_temp.sid('dlf:import-row:' || l.imp || ':' || l.line_no), pg_temp.sid('dlf:import:' || l.imp), l.line_no,
       l.waybill_no, l.outcome, l.failure_cell, l.reported_text, l.recipient, l.note, l.shipment_code, l.carrier_code,
       l.load_id, CASE WHEN l.problem IS NULL THEN 'ACCEPTED' ELSE 'ERROR' END, l.problem, 0, m.uploaded_at,
       pg_temp.staff(m.uploaded_by)::text
  FROM x_dlf_line l JOIN x_dlf_imp m ON m.imp = l.imp;

-- Each accepted line kept as a carrier event, matched to its vehicle (and its shipment, when the line names one),
-- before it is applied (CarrierEventApplier.matchFile).
CREATE TEMP TABLE x_dlf_event ON COMMIT DROP AS
SELECT l.*, m.uploaded_by, m.uploaded_at + (l.line_no || ' seconds')::interval AS received_at,
       pg_temp.sid('dlf:event:' || l.imp || ':' || l.line_no) AS event_id, d.code AS load_code, d.carrier_id,
       k.code AS carrier, d.warehouse_id,
       CASE WHEN l.shipment_code IS NOT NULL THEN (SELECT s.id FROM fulfillment.shipment s
                                                    WHERE s.load_id = d.id AND s.code = l.shipment_code) END AS shipment_id
  FROM x_dlf_line l
  JOIN x_dlf_imp m ON m.imp = l.imp
  JOIN fulfillment.dispatch_load d ON d.id = l.load_id
  JOIN fulfillment.carrier k ON k.id = d.carrier_id
 WHERE l.problem IS NULL;

INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, event_kind,
                                       carrier_reference, carrier_reported_at, dedupe_key, warehouse_id, load_id,
                                       shipment_id, result, version, created_at, created_by, source, outcome_import_id,
                                       outcome_import_line)
SELECT e.event_id, e.carrier_id, e.carrier, e.received_at,
       '{"source":"FILE","importId":' || to_json(pg_temp.sid('dlf:import:' || e.imp)::text) || ',"line":' || e.line_no
       || ',"waybillNo":' || to_json(e.waybill_no) || ',"outcome":' || to_json(e.outcome) || ',"reportedAt":'
       || to_json(to_char(e.reported_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))
       || coalesce(',"shipmentCode":' || to_json(e.shipment_code), '')
       || coalesce(',"failureReason":' || to_json(e.failure_cell), '')
       || coalesce(',"recipient":' || to_json(e.recipient), '') || coalesce(',"note":' || to_json(e.note), '') || '}',
       TRUE, e.outcome, e.waybill_no, e.reported_at,
       encode(sha256(convert_to(concat_ws('|', e.carrier_id::text, e.outcome, e.load_code, 'null',
                                          coalesce(e.shipment_code, 'null'),
                                          to_char(e.reported_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')),
                                'UTF8')), 'hex'),
       e.warehouse_id, e.load_id, e.shipment_id, 'RECEIVED', 0, e.received_at, pg_temp.staff(e.uploaded_by)::text,
       'FILE', pg_temp.sid('dlf:import:' || e.imp), e.line_no
  FROM x_dlf_event e;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Applied, line after line (CarrierEventApplier.apply). The past lists change nothing: those shipments were delivered.
UPDATE fulfillment.carrier_event c
   SET result = e.result, result_note = e.result_note, decided_at = e.received_at + interval '1 second', version = 1,
       last_modified_at = e.received_at + interval '1 second', last_modified_by = pg_temp.staff(e.uploaded_by)::text
  FROM x_dlf_event e WHERE c.id = e.event_id;

-- What the applied lines did to the four shipments (ShipmentService.carrierOutcome): recorded by nobody, at the
-- carrier's time, the step kept with its event; the order follows its shipment (OrderServiceImpl.recordShipment*).
CREATE TEMP TABLE x_dlf_applied ON COMMIT DROP AS
SELECT o.*, e.event_id, e.reported_at, e.reason AS file_reason, e.uploaded_by, e.received_at AS applied_at,
       CASE o.role WHEN 'REASON' THEN e.failure_cell ELSE e.note END AS file_note
  FROM x_dlf_o o JOIN x_dlf_event e ON e.okey = o.key;

UPDATE fulfillment.shipment s
   SET status = CASE a.role WHEN 'DELIVERED' THEN 'DELIVERED' ELSE 'DELIVERY_FAILED' END, outcome_at = a.reported_at,
       outcome_by = NULL, failure_reason = a.file_reason,
       failure_note = CASE WHEN a.role <> 'DELIVERED' THEN a.file_note END, version = 2,
       last_modified_at = a.applied_at, last_modified_by = pg_temp.staff(a.uploaded_by)::text
  FROM x_dlf_applied a WHERE s.id = a.shipment_id;

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, carrier_event_id, note, version,
                                        created_at, created_by, recorded_by)
SELECT pg_temp.sid('dlf:shipment-event:' || a.key || ':file'), a.shipment_id,
       CASE a.role WHEN 'DELIVERED' THEN 'DELIVERED' ELSE 'DELIVERY_FAILED' END, a.reported_at, 'CARRIER_WEBHOOK',
       a.event_id || CASE a.role WHEN 'DELIVERED' THEN ':DELIVERED' ELSE ':DELIVERY_FAILED' END,
       CASE WHEN a.role <> 'DELIVERED' THEN a.file_note END, 0, a.applied_at, pg_temp.staff(a.uploaded_by)::text, NULL
  FROM x_dlf_applied a;

UPDATE ordering.order_shipment s
   SET status = CASE a.role WHEN 'DELIVERED' THEN 'DELIVERED' ELSE 'DELIVERY_FAILED' END, outcome_at = a.reported_at,
       outcome_by = NULL, failure_reason = a.file_reason,
       failure_note = CASE WHEN a.role <> 'DELIVERED' THEN a.file_note END, version = 1,
       last_modified_at = a.applied_at, last_modified_by = pg_temp.staff(a.uploaded_by)::text
  FROM x_dlf_applied a WHERE s.id = a.shipment_id;

UPDATE ordering.customer_order co
   SET status = CASE a.role WHEN 'DELIVERED' THEN 'DELIVERED' ELSE 'DELIVERY_FAILED' END,
       delivered_at = CASE a.role WHEN 'DELIVERED' THEN a.reported_at END,
       delivery_failure_reason = CASE WHEN a.role <> 'DELIVERED' THEN a.file_reason END, version = 12,
       last_modified_at = a.applied_at, last_modified_by = pg_temp.staff(a.uploaded_by)::text
  FROM x_dlf_applied a WHERE co.id = a.order_id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('dlf:order-history:' || a.key || ':file'), a.order_id, 'SHIPPED',
       CASE a.role WHEN 'DELIVERED' THEN 'DELIVERED' ELSE 'DELIVERY_FAILED' END,
       CASE WHEN a.role <> 'DELIVERED' THEN a.file_reason END, NULL, a.reported_at, a.applied_at,
       pg_temp.staff(a.uploaded_by)::text
  FROM x_dlf_applied a;

-- Delivered: what the order pays after delivery is due from that day (as section 19).
UPDATE payment.installment i
   SET reached_at = a.reported_at, due_date = pg_temp.day(1) + a.term_days, status = 'DUE', version = 1,
       last_modified_at = a.applied_at, last_modified_by = 'system'
  FROM x_dlf_applied a
 WHERE a.role = 'DELIVERED' AND i.order_id = a.order_id AND i.milestone = 'AFTER_DELIVERY';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 50.2 The warehouse manager's corrections (09 §4.3 step 5, E17; 09 edge "hoàn hàng khi còn Handed Over")
-- ---------------------------------------------------------------------------------------------
-- (a) The refused truck: the reason stays, its note says what the customer objected to (before the goods come back).
-- (b) The carrier's own words for the second one: the manager learnt the real reason from its dispatcher.
CREATE TEMP TABLE x_dlf_reason ON COMMIT DROP AS
SELECT a.*, CASE a.role WHEN 'RETURNED' THEN 'REFUSED' ELSE 'CUSTOMER_ABSENT' END AS to_reason,
       CASE a.role WHEN 'RETURNED' THEN 'Khách từ chối nhận: cho rằng logo in sai mã màu so với bản duyệt; Sales đang xác minh'
                   ELSE 'Kho của khách đóng cửa, không có người nhận; khách hẹn giao lại sau' END AS to_note,
       CASE a.role WHEN 'RETURNED' THEN 'Tài xế Bắc Việt gửi biên bản khách từ chối, ghi rõ lý do'
                   ELSE 'Đã gọi điều phối nhà xe xác nhận lý do thật' END AS why,
       pg_temp.at(1, CASE a.role WHEN 'RETURNED' THEN '15:30' ELSE '19:10' END) AS at
  FROM x_dlf_applied a WHERE a.role IN ('RETURNED', 'REASON');

INSERT INTO fulfillment.failure_reason_correction (id, shipment_id, from_reason, from_note, to_reason, to_note, reason,
                                                   corrected_by, corrected_at, version, created_at, created_by)
SELECT pg_temp.sid('dlf:reason-correction:' || r.key), r.shipment_id, r.file_reason, r.file_note, r.to_reason,
       r.to_note, r.why, pg_temp.staff(r.mgr), r.at, 0, r.at, pg_temp.staff(r.mgr)::text
  FROM x_dlf_reason r;

UPDATE fulfillment.shipment s
   SET failure_reason = r.to_reason, failure_note = r.to_note, version = 3, last_modified_at = r.at,
       last_modified_by = pg_temp.staff(r.mgr)::text
  FROM x_dlf_reason r WHERE s.id = r.shipment_id;

UPDATE ordering.order_shipment s
   SET failure_reason = r.to_reason, failure_note = r.to_note, version = 2, last_modified_at = r.at,
       last_modified_by = pg_temp.staff(r.mgr)::text
  FROM x_dlf_reason r WHERE s.id = r.shipment_id;

UPDATE ordering.customer_order co
   SET delivery_failure_reason = r.to_reason, version = 13, last_modified_at = r.at,
       last_modified_by = pg_temp.staff(r.mgr)::text
  FROM x_dlf_reason r WHERE co.id = r.order_id;

-- (c) The third one was delivered after all: the driver came back at 15:40 and the customer signed for every carton.
INSERT INTO fulfillment.delivery_outcome_correction (id, shipment_id, from_status, to_status, failure_reason,
                                                     failure_note, reason, corrected_by, corrected_at, version,
                                                     created_at, created_by)
SELECT pg_temp.sid('dlf:outcome-correction:' || a.key), a.shipment_id, 'DELIVERY_FAILED', 'DELIVERED', NULL, NULL,
       'Hãng báo nhầm: tài xế quay lại giao lúc 15:40, khách đã ký biên bản nhận đủ ' || a.cartons || ' thùng',
       pg_temp.staff(a.mgr), pg_temp.at(1, '19:00'), 0, pg_temp.at(1, '19:00'), pg_temp.staff(a.mgr)::text
  FROM x_dlf_applied a WHERE a.role = 'CORRECTED';

UPDATE fulfillment.shipment s
   SET status = 'DELIVERED', outcome_at = pg_temp.at(1, '15:40'), outcome_by = pg_temp.staff(a.mgr), version = 3,
       last_modified_at = pg_temp.at(1, '19:00'), last_modified_by = pg_temp.staff(a.mgr)::text
  FROM x_dlf_applied a WHERE a.role = 'CORRECTED' AND s.id = a.shipment_id;

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at, created_by,
                                        recorded_by)
SELECT pg_temp.sid('dlf:shipment-event:' || a.key || ':corrected'), a.shipment_id, 'DELIVERED', pg_temp.at(1, '15:40'),
       'MANUAL', 0, pg_temp.at(1, '19:00'), pg_temp.staff(a.mgr)::text, pg_temp.staff(a.mgr)
  FROM x_dlf_applied a WHERE a.role = 'CORRECTED';

UPDATE ordering.order_shipment s
   SET status = 'DELIVERED', outcome_at = pg_temp.at(1, '15:40'), outcome_by = pg_temp.staff(a.mgr), version = 2,
       last_modified_at = pg_temp.at(1, '19:00'), last_modified_by = pg_temp.staff(a.mgr)::text
  FROM x_dlf_applied a WHERE a.role = 'CORRECTED' AND s.id = a.shipment_id;

UPDATE ordering.customer_order co
   SET status = 'DELIVERED', delivered_at = pg_temp.at(1, '15:40'), delivery_failure_reason = NULL, version = 13,
       last_modified_at = pg_temp.at(1, '19:00'), last_modified_by = pg_temp.staff(a.mgr)::text
  FROM x_dlf_applied a WHERE a.role = 'CORRECTED' AND co.id = a.order_id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('dlf:order-history:' || a.key || ':corrected'), a.order_id, 'DELIVERY_FAILED', 'DELIVERED', NULL,
       pg_temp.staff(a.mgr), pg_temp.at(1, '15:40'), pg_temp.at(1, '19:00'), pg_temp.staff(a.mgr)::text
  FROM x_dlf_applied a WHERE a.role = 'CORRECTED';

UPDATE payment.installment i
   SET reached_at = pg_temp.at(1, '15:40'), due_date = pg_temp.day(1) + a.term_days, status = 'DUE', version = 1,
       last_modified_at = pg_temp.at(1, '19:00'), last_modified_by = 'system'
  FROM x_dlf_applied a
 WHERE a.role = 'CORRECTED' AND i.order_id = a.order_id AND i.milestone = 'AFTER_DELIVERY';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 50.3 The refused truck back at the warehouse at 16:00 (09 §4.3 step 3-4, RTM-RETURNED): recorded by the manager;
--      the shipment's units are closed, the order RETURNED (nothing of it delivered) and its money settled as a closure
--      of the rest - here everything paid becomes refundable, for the accountant to pay back.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_dlf_ret ON COMMIT DROP AS
SELECT a.key, a.seq, a.order_id, a.order_number, a.shipment_id, a.shipment_code, a.wh, a.mgr, a.kho, a.batch_id,
       a.customer_id, a.contract_id, a.contract_number, a.po_code, pg_temp.at(1, '16:00') AS back_at,
       (SELECT sum(round(l.quantity * l.unit_price, 0) + coalesce(l.tax_amount, 0))
          FROM ordering.order_line l WHERE l.order_id = a.order_id) AS closed,
       (SELECT sum(i.paid_amount) FROM payment.installment i
         WHERE i.order_id = a.order_id AND i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE')) AS collected,
       pg_temp.sid('dlf:return-receipt:' || a.key) AS receipt_id, 'GR-' || pg_temp.ymd(1) || '-' || (7900 + a.seq) AS receipt_number,
       (SELECT s.id FROM warehouse.storage_location s JOIN warehouse.area r ON r.location_id = s.id
         WHERE s.warehouse_id = a.warehouse_id AND r.type = 'QUARANTINE' ORDER BY s.location_code LIMIT 1) AS area_id,
       (SELECT s.location_code FROM warehouse.storage_location s JOIN warehouse.area r ON r.location_id = s.id
         WHERE s.warehouse_id = a.warehouse_id AND r.type = 'QUARANTINE' ORDER BY s.location_code LIMIT 1) AS area,
       a.warehouse_id
  FROM x_dlf_applied a WHERE a.role = 'RETURNED';

UPDATE fulfillment.shipment s
   SET status = 'RETURNED', outcome_at = r.back_at, outcome_by = pg_temp.staff(r.mgr), version = 4,
       last_modified_at = r.back_at, last_modified_by = pg_temp.staff(r.mgr)::text
  FROM x_dlf_ret r WHERE s.id = r.shipment_id;

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at, created_by,
                                        recorded_by)
SELECT pg_temp.sid('dlf:shipment-event:' || r.key || ':returned'), r.shipment_id, 'RETURNED', r.back_at, 'MANUAL', 0,
       r.back_at, pg_temp.staff(r.mgr)::text, pg_temp.staff(r.mgr)
  FROM x_dlf_ret r;

UPDATE ordering.order_shipment s
   SET status = 'RETURNED', outcome_at = r.back_at, outcome_by = pg_temp.staff(r.mgr), version = 3,
       last_modified_at = r.back_at, last_modified_by = pg_temp.staff(r.mgr)::text
  FROM x_dlf_ret r WHERE s.id = r.shipment_id;

-- Order.closeUnitsOf: every unit the shipment carried is closed on its line.
UPDATE ordering.order_line l
   SET cancelled_quantity = l.cancelled_quantity + sl.quantity
  FROM ordering.order_shipment_line sl JOIN x_dlf_ret r ON r.shipment_id = sl.shipment_id
 WHERE l.id = sl.order_line_id;

UPDATE ordering.customer_order co
   SET status = 'RETURNED', delivery_failure_reason = NULL, closed_amount = r.closed, version = 14,
       last_modified_at = r.back_at, last_modified_by = pg_temp.staff(r.mgr)::text
  FROM x_dlf_ret r WHERE co.id = r.order_id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('dlf:order-history:' || r.key || ':returned'), r.order_id, 'DELIVERY_FAILED', 'RETURNED',
       'RETURNED_REFUSED', pg_temp.staff(r.mgr), r.back_at, r.back_at, pg_temp.staff(r.mgr)::text
  FROM x_dlf_ret r;

INSERT INTO ordering.order_remainder_closure (id, order_id, closure_id, reason_code, note, closed_by, closed_at,
                                              version, created_at, created_by)
SELECT pg_temp.sid('dlf:closure-row:' || r.key), r.order_id, pg_temp.sid('dlf:closure:' || r.key), 'DELIVERY_FAILED',
       'Lô giao bị hoàn về kho: phần hàng của lô không giao lại trên đơn này', pg_temp.staff(r.mgr), r.back_at, 0,
       r.back_at, pg_temp.staff(r.mgr)::text
  FROM x_dlf_ret r;

-- OrderSettlement.closeRemainder: what the goods closed are worth comes off what the installments still owe - the
-- batch's (handed over, so not "still to come"), latest batch first, after delivery before before delivery before on
-- signing - and what cannot come off was paid already: refundable.
CREATE TEMP TABLE x_dlf_cut ON COMMIT DROP AS
WITH owing AS (
    SELECT r.key, r.closed, i.id, i.amount - i.paid_amount - i.reduced_amount AS outstanding,
           sum(i.amount - i.paid_amount - i.reduced_amount) OVER (
               PARTITION BY r.key
               ORDER BY coalesce(i.delivery_batch_seq, -32768) DESC,
                        CASE i.milestone WHEN 'AFTER_DELIVERY' THEN 0 WHEN 'BEFORE_DELIVERY' THEN 1 ELSE 2 END,
                        i.seq DESC
               ROWS UNBOUNDED PRECEDING) AS running
      FROM x_dlf_ret r JOIN payment.installment i ON i.order_id = r.order_id
     WHERE i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE') AND i.status NOT IN ('PAID', 'CANCELLED')
       AND i.amount - i.paid_amount - i.reduced_amount > 0
)
SELECT key, id AS installment_id, least(outstanding, greatest(0, closed - (running - outstanding))) AS taken
  FROM owing
 WHERE closed - (running - outstanding) > 0;

INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
                                      source_id, currency, collected_amount, closed_amount, reduced_amount,
                                      surplus_amount, fee_amount, fee_covered_amount, refundable_amount,
                                      fee_owed_amount, settled_by, settled_at, version, created_at, created_by)
SELECT pg_temp.sid('dlf:settlement:' || r.key), r.order_id, r.contract_id, r.customer_id, r.order_number,
       r.contract_number, 'REMAINDER_CLOSED', pg_temp.sid('dlf:closure:' || r.key), 'VND', r.collected, r.closed,
       coalesce(c.taken, 0), r.closed - coalesce(c.taken, 0), 0, 0, r.closed - coalesce(c.taken, 0), 0,
       pg_temp.staff(r.mgr), r.back_at, 0, r.back_at, pg_temp.staff(r.mgr)::text
  FROM x_dlf_ret r LEFT JOIN (SELECT key, sum(taken) AS taken FROM x_dlf_cut GROUP BY key) c ON c.key = r.key;

UPDATE payment.installment i
   SET reduced_amount = i.reduced_amount + c.taken,
       status = CASE WHEN i.paid_amount + i.reduced_amount + c.taken < i.amount THEN i.status
                     WHEN i.paid_amount > 0 THEN 'PAID' ELSE 'CANCELLED' END,
       paid_at = CASE WHEN i.paid_amount + i.reduced_amount + c.taken = i.amount AND i.paid_amount > 0 THEN r.back_at
                      ELSE i.paid_at END,
       cancelled_at = CASE WHEN i.paid_amount + i.reduced_amount + c.taken = i.amount AND i.paid_amount = 0
                           THEN r.back_at END,
       version = i.version + 1, last_modified_at = r.back_at, last_modified_by = pg_temp.staff(r.mgr)::text
  FROM x_dlf_cut c JOIN x_dlf_ret r ON r.key = c.key
 WHERE i.id = c.installment_id;

INSERT INTO payment.installment_reduction (id, settlement_id, installment_id, amount, created_at)
SELECT pg_temp.sid('dlf:reduction:' || c.key || ':' || c.installment_id), pg_temp.sid('dlf:settlement:' || c.key),
       c.installment_id, c.taken, r.back_at
  FROM x_dlf_cut c JOIN x_dlf_ret r ON r.key = c.key;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 50.4 The return receipt (03 §4.2, 4D-2): the clerk opens it for the returned shipment into the quarantine room, scans
--      every carton by LPN (a few dented), and confirms: each carton back IN_STOCK sealed, its own row QUARANTINE in
--      its own lot, one RECEIPT ledger line each (CartonReturnService.receive); the receipt CLOSED, no putaway.
-- ---------------------------------------------------------------------------------------------
INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by, note,
                                        version, created_at, created_by, receipt_type, customer_order_id,
                                        return_shipment_id, quarantine_location_id)
SELECT r.receipt_id, r.receipt_number, r.warehouse_id, 'DRAFT', pg_temp.at(1, '16:20'), pg_temp.staff(r.kho),
       'Hàng hoàn chuyến ' || r.shipment_code || ': khách từ chối nhận', 0, pg_temp.at(1, '16:20'),
       pg_temp.staff(r.kho)::text, 'RETURN', r.order_id, r.shipment_id, r.area_id
  FROM x_dlf_ret r;

CREATE TEMP TABLE x_dlf_carton ON COMMIT DROP AS
SELECT c.key, c.n, c.lpn, c.sku, c.upc, c.package_id, c.stock_item_id, r.po_code AS lot_number, r.receipt_id, r.kho,
       r.order_id, r.area, pg_temp.at(1, '16:25') + (c.n * 30 || ' seconds')::interval AS scanned_at,
       CASE WHEN pg_temp.rnd('dlf:dent:' || c.key || ':' || c.n) < 0.12
            THEN 'Thùng móp góc khi bốc dỡ, hàng bên trong nguyên vẹn' END AS condition
  FROM h_carton c JOIN x_dlf_ret r ON r.key = c.key;

INSERT INTO procurement.goods_receipt_return_cartons (id, receipt_id, stock_package_id, lpn, sku, lot_number, quantity,
                                                      condition_note, scanned_by, version, created_at, created_by,
                                                      counted_qty)
SELECT pg_temp.sid('dlf:return-carton:' || c.key || ':' || c.n), c.receipt_id, c.package_id, c.lpn, c.sku,
       c.lot_number, c.upc, c.condition, pg_temp.staff(c.kho), 0, c.scanned_at, pg_temp.staff(c.kho)::text, c.upc
  FROM x_dlf_carton c;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE inventory.stock_package p
   SET status = 'IN_STOCK', location_code = c.area, closed_at = NULL, version = p.version + 1,
       last_modified_at = pg_temp.at(1, '17:10'), last_modified_by = pg_temp.staff(c.kho)::text
  FROM x_dlf_carton c WHERE p.id = c.package_id;

-- The row fills again with its own carton's units (StockItemRepositoryAdapter.saveMerged): the same receipt layer, so
-- the application names the row to the FIFO policy trigger for that one statement.
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN SELECT * FROM x_dlf_carton ORDER BY stock_item_id LOOP
        PERFORM set_config('stockflow.same_receipt_merge', c.stock_item_id::text, true);
        UPDATE inventory.stock_item s
           SET location_code = c.area, status = 'QUARANTINE', on_hand = c.upc, version = s.version + 1,
               last_modified_at = pg_temp.at(1, '17:10'), last_modified_by = pg_temp.staff(c.kho)::text
         WHERE s.id = c.stock_item_id;
        PERFORM set_config('stockflow.same_receipt_merge', '', true);
    END LOOP;
END $$;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      order_id, to_stock_package_id, occurred_at, version, created_at, created_by)
SELECT pg_temp.sid('dlf:ledger:return:' || c.key || ':' || c.n), 'RECEIPT', c.sku, s.lot_number, s.lot_id, NULL, c.area,
       c.upc, 'QUARANTINE', 'QUARANTINE', 'GOODS_RECEIPT', c.receipt_id, pg_temp.staff(c.kho), c.order_id,
       c.package_id, pg_temp.at(1, '17:10'), 0, pg_temp.at(1, '17:10'), pg_temp.staff(c.kho)::text
  FROM x_dlf_carton c JOIN inventory.stock_item s ON s.id = c.stock_item_id;

UPDATE procurement.goods_receipts g
   SET status = 'CLOSED', confirmed_at = pg_temp.at(1, '17:10'), confirmed_by = pg_temp.staff(r.kho),
       closed_at = pg_temp.at(1, '17:10'), version = 1, last_modified_at = pg_temp.at(1, '17:10'),
       last_modified_by = pg_temp.staff(r.kho)::text
  FROM x_dlf_ret r WHERE g.id = r.receipt_id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- After the receipt committed (ReturnedGoodsReceived → ReturnRequestService.goodsReceived → DeliveryFailureService
-- .goodsReceived): the case is opened with the shipment's reason and the cartons that came back, and - the units
-- having been closed when the shipment came back - cancelled at once by the clerk, as a record of the return. The
-- decision note is the application's own constant (DeliveryFailureService.CLOSED_BEFORE_RECEIVED).
INSERT INTO ordering.delivery_failure_case (id, order_id, shipment_id, delivery_batch_id, receipt_id, receipt_number,
                                            warehouse_id, failure_reason, failure_note, status, opened_at,
                                            carrier_claim, version, created_at)
SELECT pg_temp.sid('dlf:case:' || r.key), r.order_id, r.shipment_id, s.delivery_batch_id, r.receipt_id,
       r.receipt_number, r.warehouse_id, coalesce(s.failure_reason, 'OTHER'), s.failure_note, 'OPEN',
       pg_temp.at(1, '17:10'), FALSE, 0, pg_temp.at(1, '17:10') + interval '2 seconds'
  FROM x_dlf_ret r JOIN ordering.order_shipment s ON s.id = r.shipment_id;

INSERT INTO ordering.delivery_failure_case_carton (case_id, lpn, sku, lot_number, quantity, condition)
SELECT pg_temp.sid('dlf:case:' || c.key), c.lpn, c.sku, c.lot_number, c.upc, c.condition
  FROM x_dlf_carton c;

UPDATE ordering.delivery_failure_case f
   SET status = 'CANCELLED', decided_by = pg_temp.staff(r.kho), decided_at = f.opened_at,
       decision_note = 'Closed when the shipment came back; the goods are a record of the return',
       closed_at = f.opened_at, closed_by = pg_temp.staff(r.kho), version = 1
  FROM x_dlf_ret r WHERE f.id = pg_temp.sid('dlf:case:' || r.key);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 50.5 Proofs of delivery (09 §4.3 step 2): the signed delivery note photographed by the manager the evening of the
--      delivery, sometimes the carrier's signed slip as a PDF too. Placeholder keys: no object exists.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_dlf_pod ON COMMIT DROP AS
SELECT h.key, h.i, s.id AS shipment_id, s.code, h.mgr, pg_temp.at(h.d, '17:45') AS at
  FROM x_dlf_hist h JOIN fulfillment.shipment s ON s.id = pg_temp.sid('shipment:' || h.key)
UNION ALL
SELECT a.key, a.i, a.shipment_id, a.shipment_code, a.mgr,
       CASE a.role WHEN 'DELIVERED' THEN pg_temp.at(1, '18:25') ELSE pg_temp.at(1, '19:05') END
  FROM x_dlf_applied a WHERE a.role IN ('DELIVERED', 'CORRECTED');

INSERT INTO fulfillment.shipment_pod_file (id, shipment_id, storage_key, file_name, content_type, size_bytes,
                                           uploaded_by, uploaded_at, version, created_at, created_by)
SELECT pg_temp.sid('dlf:pod:' || p.key || ':1'), p.shipment_id, 'documents/showcase/pod/' || p.code || '-1.jpg',
       'bien-ban-giao-nhan-' || lower(p.code) || '.jpg', 'image/jpeg',
       280000 + pg_temp.pick('dlf:pod-size:' || p.key, 0, 650000), pg_temp.staff(p.mgr), p.at, 0, p.at,
       pg_temp.staff(p.mgr)::text
  FROM x_dlf_pod p
UNION ALL
SELECT pg_temp.sid('dlf:pod:' || p.key || ':2'), p.shipment_id, 'documents/showcase/pod/' || p.code || '-2.pdf',
       'phieu-giao-hang-ky-nhan.pdf', 'application/pdf', 95000 + pg_temp.pick('dlf:pod-pdf:' || p.key, 0, 120000),
       pg_temp.staff(p.mgr), p.at + interval '2 minutes', 0, p.at + interval '2 minutes', pg_temp.staff(p.mgr)::text
  FROM x_dlf_pod p WHERE p.i % 24 = 5 OR p.i <= 3;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 60. Returns (17 §4.10, 03 §4.2, 15 §4.5; team plan 4D-2, 4D-3): a return request opened on a delivered shipment and
--     approved on its photos; the goods always come back on a return receipt, scanned by LPN into the warehouse's
--     QUARANTINE area (each carton CONSUMED -> IN_STOCK, its row QUARANTINE in its own lot, one RECEIPT ledger line);
--     Sales propose the refund per line, a second person approves it and payment settles it (order_settlement
--     GOODS_RETURNED); the accountant records the transfer (refund_record) and the order moves on to
--     PARTIALLY_REFUNDED / REFUNDED. Every status of a return is shown, on delivered orders with i % 12 = 1 only.
--     Group prefix 'ret'. The adjustment e-invoices of the resolved returns are fragment 70's (invoicing).
-- ---------------------------------------------------------------------------------------------

-- The plan, one row per return. Days are days ago (as in h_order); NULL: the step is not reached.
--   req: opened · decide: approved (or rejected when reject_reason) · cancel: withdrawn by the customer ·
--   expire: closed by the nightly sweep · complete: Sales marked the goods complete · prop: refund proposed ·
--   settle: refund approved and settled · refund: the accountant recorded the transfer · release: a returned carton
--   released from quarantine by the warehouse manager.
CREATE TEMP TABLE x_ret_plan (key TEXT PRIMARY KEY, channel TEXT, reason TEXT, descr TEXT, req INT, ev_n INT,
                              decide INT, decide_note TEXT, reject_reason TEXT, cancel INT, cancel_reason TEXT,
                              expire INT, complete INT, complete_note TEXT, prop INT, prop_note TEXT, settle INT,
                              refund INT, release INT) ON COMMIT DROP;
INSERT INTO x_ret_plan VALUES
    ('O271', 'PORTAL', 'PRINT_DEFECT',
     'Hai thùng túi giấy in logo bị nhòe mực, chữ không đọc được, không thể dùng cho cửa hàng.', 3, 2,
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    ('O139', 'PORTAL', 'PRINT_DEFECT',
     'Ba thùng ly giấy in lệch màu so với bản thiết kế đã duyệt (màu xanh ngả tím).', 8, 2,
     6, 'Duyệt theo ảnh khách gửi; khách gửi trả 3 thùng về Kho Bình Dương trong 14 ngày.', NULL, NULL, NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    ('O151', 'PORTAL', 'DAMAGED_IN_TRANSIT',
     'Hai thùng túi giấy bị ướt và rách khi dỡ hàng, nhiều túi bị nhàu và bẩn.', 15, 3,
     14, 'Duyệt 2 thùng theo ảnh và biên bản giao nhận có ghi chú hư hỏng.', NULL, NULL, NULL, NULL,
     8, 'Khách xác nhận 180 túi còn lại đã hư hẳn và tự tiêu hủy, không gửi thêm.',
     6, 'Hoàn 820 túi nhận lại; khách còn nợ đợt sau giao hàng nên trừ thẳng vào công nợ.', 5, NULL, NULL),
    ('O259', 'BACK_OFFICE', 'DESIGN_MISMATCH',
     'Khách gọi điện báo hộp bánh in sai vị trí logo so với bản duyệt; gửi ảnh qua Zalo, kinh doanh tải lên thay khách.',
     18, 2, 17, 'Đã đối chiếu bản duyệt thiết kế: logo lệch 1,5 cm. Duyệt nhận lại 2 thùng và 150 hộp lẻ.', NULL, NULL,
     NULL, NULL, NULL, NULL, 10, 'Hoàn tiền 750 hộp nhận lại theo đơn giá hợp đồng, gồm VAT.', NULL, NULL, NULL),
    ('O223', 'PORTAL', 'DAMAGED_IN_TRANSIT',
     'Ba thùng bị móp nặng do xe tải xếp hàng đè lên, khách không nhận để bán.', 24, 2,
     23, 'Duyệt theo ảnh; nhà xe đã xác nhận sự cố khi vận chuyển.', NULL, NULL, NULL, NULL, NULL, NULL,
     16, 'Hoàn tiền 3 thùng nhận lại; chi phí do nhà xe bồi thường theo hợp đồng vận chuyển.', 15, NULL, 2),
    ('O247', 'PORTAL', 'PRINT_DEFECT',
     'Hai thùng ly giấy in bị sọc mực dọc thân ly, không giao cho cửa hàng được.', 24, 2,
     23, 'Duyệt 2 thùng theo ảnh; khách gửi trả về Kho Hà Nội.', NULL, NULL, NULL, NULL, NULL, NULL,
     NULL, NULL, NULL, NULL, NULL),
    ('O283', 'PORTAL', 'PRINT_DEFECT',
     'Màu in ly giấy nhạt hơn lô trước, khách muốn trả 1 thùng.', 160, 2,
     159, NULL, 'Ảnh cho thấy độ lệch màu nằm trong dung sai đã thỏa thuận trong hợp đồng (ΔE < 3); không nhận trả hàng.',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    ('O199', 'PORTAL', 'PRINT_DEFECT',
     'Ba thùng hộp pizza in bị lem mực ở nắp hộp, không dùng được.', 48, 2,
     47, 'Duyệt 3 thùng theo ảnh; lỗi in của nhà in.', NULL, NULL, NULL, NULL, NULL, NULL,
     38, 'Hoàn tiền 3 thùng lỗi in theo đơn giá hợp đồng, gồm VAT.', 37, 35, NULL),
    ('O187', 'PORTAL', 'WRONG_ITEM',
     'Hai thùng giao nhầm mẫu ly của đợt in trước (thiết kế Tết cũ).', 49, 1,
     48, 'Duyệt theo ảnh; kho đã kiểm tra LPN, đúng là thùng của mẫu cũ được dán nhầm lô.', NULL, NULL, NULL, NULL,
     NULL, NULL, 41, 'Hoàn tiền 2 thùng giao nhầm mẫu.', 40, 38, NULL),
    ('O175', 'PORTAL', 'PRINT_DEFECT',
     'Một số ly bị chấm mực ở thân ly.', 50, 1,
     NULL, NULL, NULL, 49, 'Đã thống nhất với kinh doanh: lỗi nhỏ, khách vẫn dùng được, không trả hàng nữa.',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    ('O211', 'BACK_OFFICE', 'PRINT_DEFECT',
     'Khách báo cả lô tô giấy in bong mực khi đựng đồ nóng; kinh doanh đến tận nơi kiểm tra và chụp ảnh.', 62, 3,
     61, 'Đã kiểm tra mẫu tại kho khách: mực in không đạt chuẩn chịu nhiệt. Nhận lại toàn bộ 10 thùng.', NULL, NULL,
     NULL, NULL, NULL, NULL, 53, 'Hoàn tiền toàn bộ lô 10 thùng theo giá trị hợp đồng.', 52, 50, NULL),
    ('O115', 'PORTAL', 'PRINT_DEFECT',
     'Hai thùng ly giấy bị in thiếu một màu (mất lớp màu đỏ).', 78, 2,
     77, 'Duyệt 2 thùng theo ảnh.', NULL, NULL, NULL, NULL, NULL, NULL,
     68, 'Hoàn tiền 2 thùng ly in thiếu màu.', 67, 65, NULL),
    ('O163', 'PORTAL', 'PRINT_DEFECT',
     'Một thùng tô giấy in mờ logo.', 94, 1,
     93, 'Duyệt theo ảnh; khách gửi trả 1 thùng về Kho Hà Nội trong 14 ngày.', NULL, NULL, NULL, 78, NULL, NULL,
     NULL, NULL, NULL, NULL, NULL),
    ('O295', 'PORTAL', 'DAMAGED_IN_TRANSIT',
     'Hai thùng túi giấy bị nước mưa thấm khi giao, túi bị nhăn và ố.', 103, 2,
     102, 'Duyệt theo ảnh và biên bản giao hàng.', NULL, NULL, NULL, NULL, NULL, NULL,
     86, 'Hoàn tiền 2 thùng túi giấy hư do vận chuyển.', 85, 83, NULL),
    ('O127', 'PORTAL', 'PRINT_DEFECT',
     'Hộp bánh và túi giấy in sai màu thương hiệu (cam thành đỏ); khách gửi trả làm hai đợt.', 119, 3,
     118, 'Duyệt 2 thùng hộp và 3 thùng túi theo ảnh.', NULL, NULL, NULL, NULL, NULL, NULL,
     108, 'Hoàn tiền 5 thùng in sai màu, gồm VAT.', 107, 105, NULL);

-- What each return claims, per order line: whole cartons (the line's first ones) and units with no carton.
CREATE TEMP TABLE x_ret_claim (key TEXT, ln INT, cartons INT, loose INT, PRIMARY KEY (key, ln)) ON COMMIT DROP;
INSERT INTO x_ret_claim VALUES
    ('O271', 2, 2, 0), ('O139', 1, 3, 0), ('O151', 1, 2, 0), ('O259', 1, 2, 150), ('O223', 1, 2, 0),
    ('O223', 2, 1, 0), ('O247', 2, 2, 0), ('O283', 1, 1, 0), ('O199', 1, 3, 0), ('O187', 1, 2, 0), ('O175', 1, 1, 0),
    ('O211', 1, 10, 0), ('O115', 2, 2, 0), ('O163', 1, 1, 0), ('O295', 2, 2, 0), ('O127', 1, 2, 0), ('O127', 3, 3, 0);

-- The parcels that came back, each on a return receipt of its own (WF-05 TH-3), and how many photos it took.
CREATE TEMP TABLE x_ret_parcel (key TEXT, parcel INT, day INT, note TEXT, photos INT, PRIMARY KEY (key, parcel))
    ON COMMIT DROP;
INSERT INTO x_ret_parcel VALUES
    ('O247', 1, 17, 'Khách gửi trả 2 thùng qua nhà xe Bắc Việt.', 1),
    ('O151', 1, 9, 'Khách gửi trả qua nhà xe Phương Trang: 1 thùng nguyên, 1 thùng rách đã mở.', 2),
    ('O259', 1, 12, 'Kinh doanh nhận lại tại cửa hàng khách: 2 thùng nguyên và 150 hộp lẻ không nhãn thùng.', 0),
    ('O223', 1, 18, 'Nhà xe Bắc Việt chở trả 3 thùng móp vỏ.', 2),
    ('O199', 1, 41, NULL, 0),
    ('O187', 1, 43, NULL, 0),
    ('O211', 1, 55, 'Xe của công ty đến lấy lại toàn bộ 10 thùng tại kho khách.', 1),
    ('O115', 1, 70, NULL, 0),
    ('O295', 1, 95, NULL, 0),
    ('O127', 1, 113, 'Đợt 1: 2 thùng hộp bánh và 1 thùng túi giấy.', 0),
    ('O127', 2, 110, 'Đợt 2: 2 thùng túi giấy còn lại.', 1);

-- The cartons each parcel brought (k: the k-th claimed carton of the line); counted below the label: opened.
CREATE TEMP TABLE x_ret_back (key TEXT, ln INT, k INT, parcel INT, counted INT, cond TEXT, PRIMARY KEY (key, ln, k))
    ON COMMIT DROP;
INSERT INTO x_ret_back VALUES
    ('O247', 2, 1, 1, NULL, NULL), ('O247', 2, 2, 1, NULL, NULL),
    ('O151', 1, 1, 1, NULL, 'Vỏ thùng ướt một góc, niêm phong còn nguyên'),
    ('O151', 1, 2, 1, 320, 'Thùng rách, băng keo đã bị cắt, thiếu hàng bên trong'),
    ('O259', 1, 1, 1, NULL, NULL), ('O259', 1, 2, 1, NULL, NULL),
    ('O223', 1, 1, 1, NULL, 'Vỏ thùng móp'), ('O223', 1, 2, 1, NULL, 'Vỏ thùng móp, rách nhẹ một cạnh'),
    ('O223', 2, 1, 1, NULL, 'Vỏ thùng móp'),
    ('O199', 1, 1, 1, NULL, NULL), ('O199', 1, 2, 1, NULL, NULL), ('O199', 1, 3, 1, NULL, NULL),
    ('O187', 1, 1, 1, NULL, NULL), ('O187', 1, 2, 1, NULL, NULL),
    ('O115', 2, 1, 1, NULL, NULL), ('O115', 2, 2, 1, NULL, NULL),
    ('O295', 2, 1, 1, NULL, 'Vỏ thùng ố nước'), ('O295', 2, 2, 1, NULL, 'Vỏ thùng ố nước'),
    ('O127', 1, 1, 1, NULL, NULL), ('O127', 1, 2, 1, NULL, NULL), ('O127', 3, 1, 1, NULL, NULL),
    ('O127', 3, 2, 2, NULL, NULL), ('O127', 3, 3, 2, NULL, NULL);
INSERT INTO x_ret_back SELECT 'O211', 1, k, 1, NULL, NULL FROM generate_series(1, 10) k;

-- Units that came back with no carton label (WF-05 TH-4): received for the return, never put into stock.
CREATE TEMP TABLE x_ret_loose (key TEXT, ln INT, parcel INT, qty INT, PRIMARY KEY (key, ln, parcel)) ON COMMIT DROP;
INSERT INTO x_ret_loose VALUES ('O259', 1, 1, 150);

-- Each return with its order, its people and its ids.
CREATE TEMP TABLE x_ret ON COMMIT DROP AS
SELECT p.*, o.seq, o.id AS order_id, o.customer_id, o.contract_id, o.contract_number, o.order_number, o.wh, o.kho,
       o.mgr, o.sales, o.po_code, o.d,
       pg_temp.sid('ret:return:' || p.key) AS rid,
       'RMA-' || pg_temp.ymd(p.req) || '-' || (7000 + o.seq) AS rma,
       CASE p.channel WHEN 'PORTAL' THEN pg_temp.sid('user:' || o.cust_user) ELSE pg_temp.staff(o.sales) END AS requester,
       CASE p.channel WHEN 'PORTAL' THEN pg_temp.staff(o.sales) ELSE pg_temp.staff('truongkd') END AS decider,
       pg_temp.sid('user:' || o.cust_user) AS customer_user,
       pg_temp.sid('wh:' || o.wh) AS wh_id,
       o.wh || '-QC01' AS qc_code,
       (SELECT s.id FROM warehouse.storage_location s WHERE s.location_code = o.wh || '-QC01') AS qc_id
  FROM x_ret_plan p JOIN h_order o ON o.key = p.key
 WHERE o.stage = 'DELIVERED' AND o.i % 12 = 1;

DO $$
BEGIN
    IF (SELECT count(*) FROM x_ret) <> (SELECT count(*) FROM x_ret_plan) THEN
        RAISE EXCEPTION 'returns: a planned order is not a delivered order of the partition (i %% 12 = 1)';
    END IF;
END $$;

-- The return lines, with the order line they claim.
CREATE TEMP TABLE x_ret_line ON COMMIT DROP AS
SELECT c.key, c.ln, c.cartons, c.loose, l.line_id, l.sku, l.upc, l.first_n, ol.unit_price, ol.quantity AS line_qty,
       ol.quantity * ol.unit_price + COALESCE(ol.tax_amount, 0) AS line_gross,
       c.cartons * l.upc + c.loose AS qty,
       pg_temp.sid('ret:line:' || c.key || ':' || c.ln) AS rlid
  FROM x_ret_claim c
  JOIN h_line l ON l.key = c.key AND l.ln = c.ln
  JOIN ordering.order_line ol ON ol.id = l.line_id;

-- Every carton that came back, with its parcel's receipt.
CREATE TEMP TABLE x_ret_carton ON COMMIT DROP AS
SELECT b.key, b.ln, b.k, b.parcel, b.cond, h.n, h.package_id, h.stock_item_id, h.lpn, h.sku, h.upc,
       COALESCE(b.counted, h.upc) AS counted, b.counted IS NOT NULL AND b.counted < h.upc AS opened,
       pg_temp.sid('lot:' || h.key || ':' || h.ln) AS lot_id, p.day,
       pg_temp.sid('ret:receipt:' || b.key || ':' || b.parcel) AS receipt_id
  FROM x_ret_back b
  JOIN x_ret_line l ON l.key = b.key AND l.ln = b.ln
  JOIN h_carton h ON h.key = b.key AND h.ln = b.ln AND h.n = l.first_n + b.k - 1
  JOIN x_ret_parcel p ON p.key = b.key AND p.parcel = b.parcel;

-- An order's status change, as Order.transitionTo writes it (version, audit columns, one history row).
CREATE TEMP TABLE x_ret_order_step (key TEXT, step INT, from_status TEXT, to_status TEXT, reason TEXT, actor UUID,
                                    at TIMESTAMPTZ) ON COMMIT DROP;

-- ---- 60.1 Opened (17 §4.10 step 37): the return, its lines, its photos of the problem; the order RETURN_REQUESTED
INSERT INTO ordering.return_request (id, rma_number, order_id, customer_id, status, reason_code, note, requested_at,
                                     version, created_at, created_by, order_number, request_id, channel, requested_by,
                                     requested_resolution, currency, shipment_id)
SELECT r.rid, r.rma, r.order_id, r.customer_id, 'REQUESTED', r.reason, r.descr, pg_temp.at(r.req, '09:15'), 0,
       pg_temp.at(r.req, '09:15'), r.requester::text, r.order_number, pg_temp.sid('ret:request:' || r.key), r.channel,
       r.requester, 'REFUND', 'VND', pg_temp.sid('shipment:' || r.key)
  FROM x_ret r;

INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, quantity, version, created_at,
                                          created_by, sku, custom_printed, unit_price)
SELECT l.rlid, r.rid, l.line_id, l.qty, 0, pg_temp.at(r.req, '09:15'), r.requester::text, l.sku, TRUE, l.unit_price
  FROM x_ret_line l JOIN x_ret r ON r.key = l.key;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.sid('ret:history:' || r.key || ':1'), r.rid, NULL, 'REQUESTED', r.reason, r.requester,
       pg_temp.at(r.req, '09:15'), pg_temp.at(r.req, '09:15'), r.requester::text
  FROM x_ret r;

INSERT INTO ordering.return_request_evidence (id, return_request_id, stage, file_key, original_name, content_type,
                                              size_bytes, uploaded_by, uploaded_at, created_at, created_by)
SELECT pg_temp.sid('ret:evidence:' || r.key || ':' || e), r.rid, 'CLAIM',
       'return-evidence/showcase/' || lower(r.rma) || '-' || e || '.jpg',
       CASE r.channel WHEN 'PORTAL' THEN 'IMG_' || pg_temp.ymd(r.req) || '_' || (91500 + e * 37) || '.jpg'
                      ELSE 'zalo-' || lower(r.order_number) || '-' || e || '.jpg' END,
       'image/jpeg', 640000 + pg_temp.pick('ret:evidence-size:' || r.key || ':' || e, 0, 2400000), r.requester,
       pg_temp.at(r.req, '09:20') + (e || ' minutes')::interval, pg_temp.at(r.req, '09:20') + (e || ' minutes')::interval,
       r.requester::text
  FROM x_ret r CROSS JOIN LATERAL generate_series(1, r.ev_n) e;

INSERT INTO x_ret_order_step
SELECT r.key, 1, 'DELIVERED', 'RETURN_REQUESTED', 'RETURN_REQUESTED ' || r.rma, r.requester, pg_temp.at(r.req, '09:15')
  FROM x_ret r;

UPDATE ordering.customer_order co
   SET status = s.to_status, version = co.version + 1, last_modified_at = s.at, last_modified_by = s.actor::text
  FROM x_ret_order_step s JOIN x_ret r ON r.key = s.key
 WHERE co.id = r.order_id AND s.step = 1;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---- 60.2 Reviewed (step 38): approved in full - the goods come back within 14 days - or rejected, saying why
UPDATE ordering.return_request_line l
   SET approved_quantity = l.quantity, version = 1, last_modified_at = pg_temp.at(r.decide, '10:30'),
       last_modified_by = r.decider::text
  FROM x_ret r
 WHERE l.return_request_id = r.rid AND r.decide IS NOT NULL AND r.reject_reason IS NULL;

UPDATE ordering.return_request q
   SET status = 'AWAITING_RETURN', decided_by = r.decider, decided_at = pg_temp.at(r.decide, '10:30'),
       decision_note = r.decide_note, return_required = TRUE,
       -- ReturnPolicy.returnDeadlineFrom: the end of the 14th day after the approval, as a start of day.
       return_deadline = pg_temp.at(r.decide - 15, '00:00'), version = 1,
       last_modified_at = pg_temp.at(r.decide, '10:30'), last_modified_by = r.decider::text
  FROM x_ret r
 WHERE q.id = r.rid AND r.decide IS NOT NULL AND r.reject_reason IS NULL;

UPDATE ordering.return_request q
   SET status = 'REJECTED', decided_by = r.decider, decided_at = pg_temp.at(r.decide, '10:30'),
       rejection_reason = r.reject_reason, closed_at = pg_temp.at(r.decide, '10:30'), version = 1,
       last_modified_at = pg_temp.at(r.decide, '10:30'), last_modified_by = r.decider::text
  FROM x_ret r
 WHERE q.id = r.rid AND r.reject_reason IS NOT NULL;

-- Withdrawn by the customer before anything came back (REQUESTED -> CANCELLED).
UPDATE ordering.return_request q
   SET status = 'CANCELLED', cancelled_by = r.customer_user, cancellation_reason = r.cancel_reason,
       closed_at = pg_temp.at(r.cancel, '16:00'), version = 1, last_modified_at = pg_temp.at(r.cancel, '16:00'),
       last_modified_by = r.customer_user::text
  FROM x_ret r
 WHERE q.id = r.rid AND r.cancel IS NOT NULL;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.sid('ret:history:' || r.key || ':2'), r.rid, 'REQUESTED',
       CASE WHEN r.cancel IS NOT NULL THEN 'CANCELLED' WHEN r.reject_reason IS NOT NULL THEN 'REJECTED'
            ELSE 'AWAITING_RETURN' END,
       CASE WHEN r.cancel IS NOT NULL THEN r.cancel_reason WHEN r.reject_reason IS NOT NULL THEN r.reject_reason
            ELSE 'APPROVED' END,
       CASE WHEN r.cancel IS NOT NULL THEN r.customer_user ELSE r.decider END,
       CASE WHEN r.cancel IS NOT NULL THEN pg_temp.at(r.cancel, '16:00') ELSE pg_temp.at(r.decide, '10:30') END,
       CASE WHEN r.cancel IS NOT NULL THEN pg_temp.at(r.cancel, '16:00') ELSE pg_temp.at(r.decide, '10:30') END,
       CASE WHEN r.cancel IS NOT NULL THEN r.customer_user ELSE r.decider END::text
  FROM x_ret r
 WHERE r.decide IS NOT NULL OR r.cancel IS NOT NULL;

-- Every return of the order ended before its goods came: back to DELIVERED (17 §5.1).
INSERT INTO x_ret_order_step
SELECT r.key, 2, 'RETURN_REQUESTED', 'DELIVERED', 'RETURN_ENDED',
       CASE WHEN r.cancel IS NOT NULL THEN r.customer_user ELSE r.decider END,
       CASE WHEN r.cancel IS NOT NULL THEN pg_temp.at(r.cancel, '16:00') ELSE pg_temp.at(r.decide, '10:30') END
  FROM x_ret r
 WHERE r.cancel IS NOT NULL OR r.reject_reason IS NOT NULL;

UPDATE ordering.customer_order co
   SET status = s.to_status, version = co.version + 1, last_modified_at = s.at, last_modified_by = s.actor::text
  FROM x_ret_order_step s JOIN x_ret r ON r.key = s.key
 WHERE co.id = r.order_id AND s.step = 2;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---- 60.3 The deadline passed with nothing back: the nightly sweep expires the return (actor none)
UPDATE ordering.return_request q
   SET status = 'EXPIRED', closed_at = pg_temp.at(r.expire, '01:00'), version = 2,
       last_modified_at = pg_temp.at(r.expire, '01:00'), last_modified_by = 'system'
  FROM x_ret r
 WHERE q.id = r.rid AND r.expire IS NOT NULL;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.sid('ret:history:' || r.key || ':3'), r.rid, 'AWAITING_RETURN', 'EXPIRED', 'RETURN_DEADLINE_PASSED',
       NULL, pg_temp.at(r.expire, '01:00'), pg_temp.at(r.expire, '01:00'), 'system'
  FROM x_ret r WHERE r.expire IS NOT NULL;

INSERT INTO x_ret_order_step
SELECT r.key, 3, 'RETURN_REQUESTED', 'DELIVERED', 'RETURN_ENDED', NULL, pg_temp.at(r.expire, '01:00')
  FROM x_ret r WHERE r.expire IS NOT NULL;

UPDATE ordering.customer_order co
   SET status = s.to_status, version = co.version + 1, last_modified_at = s.at, last_modified_by = 'system'
  FROM x_ret_order_step s JOIN x_ret r ON r.key = s.key
 WHERE co.id = r.order_id AND s.step = 3;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---- 60.4-60.6 The parcels, one return receipt each (03 §4.2, 4D-2; WF-05 TH-3). A return has one draft receipt at a
--      time (uk_goods_receipts_return_request_draft), so a second parcel's receipt is opened only after the first was
--      confirmed and taken: pg_temp.ret_parcel(n) runs the three committed steps of the n-th parcel of every return.
--   60.4 Opened by the warehouse against the approved return, into its QUARANTINE area; each carton scanned by LPN as
--        it was shipped (LPN, SKU, count, lot), the units inside counted; loose units without a label recorded; photos
--        of what arrived while the receipt is a draft.
--   60.5 Confirmed (CartonReturnService.receive): each carton back whole into quarantine - the carton IN_STOCK at the
--        area, its one row QUARANTINE at the label's count in its own lot, a RECEIPT ledger line; a carton counted
--        short is marked opened (never released, only written off); the receipt CLOSED.
--   60.6 Taken by the return after the receipt committed (ReturnRequestService.goodsReceived): each line adds the units
--        counted inside its cartons and the loose ones; RECEIVED once every line has what was approved, AWAITING_RETURN
--        until then; the order RETURNED once goods of an open return are back.
CREATE FUNCTION pg_temp.ret_parcel(rnd INTEGER) RETURNS VOID LANGUAGE plpgsql AS $fn$
DECLARE
    carton RECORD;
BEGIN
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by, note,
                                            version, created_at, created_by, receipt_type, customer_order_id,
                                            return_request_id, quarantine_location_id)
    SELECT pg_temp.sid('ret:receipt:' || p.key || ':' || p.parcel),
           'GR-' || pg_temp.ymd(p.day) || '-' || (76000 + r.seq * 10 + p.parcel), r.wh_id, 'DRAFT',
           pg_temp.at(p.day, '08:30'), pg_temp.staff(r.kho), p.note, 0, pg_temp.at(p.day, '08:30'),
           pg_temp.staff(r.kho)::text, 'RETURN', r.order_id, r.rid, r.qc_id
      FROM x_ret_parcel p JOIN x_ret r ON r.key = p.key
     WHERE p.parcel = rnd;

    INSERT INTO procurement.goods_receipt_return_cartons (id, receipt_id, stock_package_id, lpn, sku, lot_number,
                                                          quantity, condition_note, scanned_by, version, created_at,
                                                          created_by, counted_qty)
    SELECT pg_temp.sid('ret:receipt-carton:' || c.key || ':' || c.n), c.receipt_id, c.package_id, c.lpn, c.sku,
           r.po_code, c.upc, c.cond, pg_temp.staff(r.kho), 0,
           pg_temp.at(c.day, '08:35')
               + (row_number() OVER (PARTITION BY c.receipt_id ORDER BY c.n) || ' minutes')::interval,
           pg_temp.staff(r.kho)::text, c.counted
      FROM x_ret_carton c JOIN x_ret r ON r.key = c.key
     WHERE c.parcel = rnd;

    INSERT INTO procurement.goods_receipt_return_loose_lines (id, receipt_id, sku, lot_number, quantity, recorded_by,
                                                              version, created_at, created_by)
    SELECT pg_temp.sid('ret:receipt-loose:' || x.key || ':' || x.ln || ':' || x.parcel),
           pg_temp.sid('ret:receipt:' || x.key || ':' || x.parcel), l.sku, r.po_code, x.qty, pg_temp.staff(r.kho), 0,
           pg_temp.at(p.day, '09:00'), pg_temp.staff(r.kho)::text
      FROM x_ret_loose x
      JOIN x_ret_line l ON l.key = x.key AND l.ln = x.ln
      JOIN x_ret r ON r.key = x.key
      JOIN x_ret_parcel p ON p.key = x.key AND p.parcel = x.parcel
     WHERE x.parcel = rnd;

    INSERT INTO procurement.goods_receipt_photos (id, receipt_id, receipt_line_id, stage, storage_key, original_name,
                                                  content_type, size_bytes, checksum, taken_by, version, created_at,
                                                  created_by)
    SELECT pg_temp.sid('ret:receipt-photo:' || p.key || ':' || p.parcel || ':' || n),
           pg_temp.sid('ret:receipt:' || p.key || ':' || p.parcel), NULL, 'RECEIPT',
           'qc-evidence/showcase/return-' || lower(r.order_number) || '-' || p.parcel || '-' || n || '.jpg',
           'tra-hang-' || lower(r.order_number) || '-' || p.parcel || '-' || n || '.jpg', 'image/jpeg',
           820000 + pg_temp.pick('ret:receipt-photo-size:' || p.key || ':' || p.parcel || ':' || n, 0, 1800000),
           encode(sha256(convert_to('ret:receipt-photo:' || p.key || ':' || p.parcel || ':' || n, 'UTF8')), 'hex'),
           pg_temp.staff(r.kho), 0, pg_temp.at(p.day, '09:05') + (n || ' minutes')::interval,
           pg_temp.staff(r.kho)::text
      FROM x_ret_parcel p JOIN x_ret r ON r.key = p.key CROSS JOIN LATERAL generate_series(1, p.photos) n
     WHERE p.parcel = rnd;

    -- (the application commits here: the deferred checks run on what this step leaves)
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;

    -- 60.5: the carton stands at the area before the ledger line names it there (tg_stock_movement_package).
    UPDATE inventory.stock_package p
       SET status = 'IN_STOCK', location_code = r.qc_code, closed_at = NULL,
           return_opened_at = CASE WHEN c.opened THEN pg_temp.at(c.day, '09:30') END, version = p.version + 1,
           last_modified_at = pg_temp.at(c.day, '09:30'), last_modified_by = pg_temp.staff(r.kho)::text
      FROM x_ret_carton c JOIN x_ret r ON r.key = c.key
     WHERE p.id = c.package_id AND c.parcel = rnd;

    -- The carton's own row is filled again in place (StockItemRepositoryAdapter.saveMerged names the row it merges
    -- into for that one statement, so enforce_sku_policy does not take it for a new FIFO layer).
    FOR carton IN SELECT c.stock_item_id, c.upc, c.day, r.qc_code, r.kho
                    FROM x_ret_carton c JOIN x_ret r ON r.key = c.key
                   WHERE c.parcel = rnd ORDER BY c.stock_item_id LOOP
        PERFORM set_config('stockflow.same_receipt_merge', carton.stock_item_id::text, true);
        UPDATE inventory.stock_item s
           SET location_code = carton.qc_code, status = 'QUARANTINE', on_hand = carton.upc, reserved = 0,
               version = s.version + 1, last_modified_at = pg_temp.at(carton.day, '09:30'),
               last_modified_by = pg_temp.staff(carton.kho)::text
         WHERE s.id = carton.stock_item_id;
    END LOOP;
    PERFORM set_config('stockflow.same_receipt_merge', '', true);

    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code,
                                          to_location_code, quantity, from_status, to_status, reference_type,
                                          reference_id, actor_id, to_stock_package_id, order_id, occurred_at, version,
                                          created_at, created_by)
    SELECT pg_temp.sid('ret:ledger:return:' || c.key || ':' || c.n), 'RECEIPT', c.sku, r.po_code, c.lot_id, NULL,
           r.qc_code, c.upc, NULL, 'QUARANTINE', 'GOODS_RECEIPT', c.receipt_id, pg_temp.staff(r.kho), c.package_id,
           r.order_id, pg_temp.at(c.day, '09:30'), 0, pg_temp.at(c.day, '09:30'), pg_temp.staff(r.kho)::text
      FROM x_ret_carton c JOIN x_ret r ON r.key = c.key
     WHERE c.parcel = rnd;

    UPDATE procurement.goods_receipts g
       SET status = 'CLOSED', confirmed_at = pg_temp.at(p.day, '09:30'), confirmed_by = pg_temp.staff(r.kho),
           closed_at = pg_temp.at(p.day, '09:30'), version = 1, last_modified_at = pg_temp.at(p.day, '09:30'),
           last_modified_by = pg_temp.staff(r.kho)::text
      FROM x_ret_parcel p JOIN x_ret r ON r.key = p.key
     WHERE g.id = pg_temp.sid('ret:receipt:' || p.key || ':' || p.parcel) AND p.parcel = rnd;

    -- (the application commits here: the deferred checks run on what this step leaves)
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;

    -- 60.6
    INSERT INTO ordering.return_request_receipt (goods_receipt_id, return_request_id, parcel, receipt_number,
                                                 receiving_location_code, received_by, received_at, created_at,
                                                 created_by)
    SELECT g.id, r.rid, p.parcel, g.receipt_number, r.qc_code, pg_temp.staff(r.kho),
           pg_temp.at(p.day, '09:30') + interval '3 seconds', pg_temp.at(p.day, '09:30') + interval '3 seconds',
           pg_temp.staff(r.kho)::text
      FROM x_ret_parcel p JOIN x_ret r ON r.key = p.key
      JOIN procurement.goods_receipts g ON g.id = pg_temp.sid('ret:receipt:' || p.key || ':' || p.parcel)
     WHERE p.parcel = rnd;

    -- What each line has back after this parcel: the units counted in its cartons and its loose units, so far.
    CREATE TEMP TABLE x_ret_round ON COMMIT DROP AS
    SELECT l.key, l.ln, l.rlid, l.qty, p.day, r.rid, r.kho, r.qc_code,
           COALESCE((SELECT sum(c.counted) FROM x_ret_carton c
                      WHERE c.key = l.key AND c.ln = l.ln AND c.parcel <= rnd), 0)
               + COALESCE((SELECT sum(x.qty) FROM x_ret_loose x
                            WHERE x.key = l.key AND x.ln = l.ln AND x.parcel <= rnd), 0) AS received
      FROM x_ret_line l JOIN x_ret r ON r.key = l.key JOIN x_ret_parcel p ON p.key = l.key AND p.parcel = rnd;

    UPDATE ordering.return_request_line l
       SET received_quantity = v.received, version = l.version + 1,
           last_modified_at = pg_temp.at(v.day, '09:30') + interval '3 seconds',
           last_modified_by = pg_temp.staff(v.kho)::text
      FROM x_ret_round v
     WHERE l.id = v.rlid;

    UPDATE ordering.return_request q
       SET goods_receipt_id = COALESCE(q.goods_receipt_id, pg_temp.sid('ret:receipt:' || x.key || ':' || rnd)),
           received_at = pg_temp.at(x.day, '09:30') + interval '3 seconds', received_by = pg_temp.staff(x.kho),
           receiving_location_code = x.qc_code,
           status = CASE WHEN x.short THEN 'AWAITING_RETURN' ELSE 'RECEIVED' END, version = q.version + 1,
           last_modified_at = pg_temp.at(x.day, '09:30') + interval '3 seconds',
           last_modified_by = pg_temp.staff(x.kho)::text
      FROM (SELECT key, rid, day, kho, qc_code, bool_or(received < qty) AS short
              FROM x_ret_round GROUP BY key, rid, day, kho, qc_code) x
     WHERE q.id = x.rid;

    INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason,
                                                        actor_id, occurred_at, created_at, created_by)
    SELECT pg_temp.sid('ret:history:' || r.key || ':4'), r.rid, 'AWAITING_RETURN', 'RECEIVED',
           'RECEIVED ' || g.receipt_number, pg_temp.staff(r.kho), q.received_at, q.received_at,
           pg_temp.staff(r.kho)::text
      FROM x_ret r
      JOIN x_ret_parcel p ON p.key = r.key AND p.parcel = rnd
      JOIN ordering.return_request q ON q.id = r.rid AND q.status = 'RECEIVED'
      JOIN procurement.goods_receipts g ON g.id = pg_temp.sid('ret:receipt:' || r.key || ':' || rnd);

    INSERT INTO x_ret_order_step
    SELECT r.key, 4, 'RETURN_REQUESTED', 'RETURNED', 'RETURN_GOODS_BACK', pg_temp.staff(r.kho), q.received_at
      FROM x_ret r
      JOIN x_ret_parcel p ON p.key = r.key AND p.parcel = rnd
      JOIN ordering.return_request q ON q.id = r.rid AND q.status = 'RECEIVED';

    UPDATE ordering.customer_order co
       SET status = s.to_status, version = co.version + 1, last_modified_at = s.at, last_modified_by = s.actor::text
      FROM x_ret_order_step s JOIN x_ret r ON r.key = s.key
      JOIN x_ret_parcel p ON p.key = r.key AND p.parcel = rnd
     WHERE co.id = r.order_id AND s.step = 4;

    DROP TABLE x_ret_round;

    -- (the application commits here: the deferred checks run on what this step leaves)
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
END
$fn$;

DO $$
BEGIN
    PERFORM pg_temp.ret_parcel(1);
    PERFORM pg_temp.ret_parcel(2);
END $$;
DROP FUNCTION pg_temp.ret_parcel(INTEGER);

-- Photos the warehouse took of two printed deliveries of this partition while counting them (production receipts,
-- still drafts at the time): the delivery as a whole, and one line.
INSERT INTO procurement.goods_receipt_photos (id, receipt_id, receipt_line_id, stage, storage_key, original_name,
                                              content_type, size_bytes, checksum, taken_by, version, created_at,
                                              created_by)
SELECT pg_temp.sid('ret:production-photo:' || o.key || ':' || n), pg_temp.sid('receipt-goods:' || o.key),
       CASE WHEN n = 2 THEN pg_temp.sid('receipt-goods-line:' || o.key || ':1') END, 'RECEIPT',
       'qc-evidence/showcase/' || lower(o.receipt_number) || '-' || n || '.jpg',
       CASE WHEN n = 1 THEN 'xe-hang-' ELSE 'thung-mau-' END || lower(o.receipt_number) || '.jpg', 'image/jpeg',
       900000 + pg_temp.pick('ret:production-photo-size:' || o.key || ':' || n, 0, 1500000),
       encode(sha256(convert_to('ret:production-photo:' || o.key || ':' || n, 'UTF8')), 'hex'),
       pg_temp.staff(o.kho), 0, pg_temp.at(o.r, '08:40') + (n || ' minutes')::interval, pg_temp.staff(o.kho)::text
  FROM h_order o CROSS JOIN generate_series(1, 2) n
 WHERE o.stage = 'DELIVERED' AND o.i % 12 = 1 AND o.key IN ('O235', 'O283');  -- O283 has no goods back: its return was rejected

-- What each line has back, every parcel counted: the base of the steps below.
CREATE TEMP TABLE x_ret_received ON COMMIT DROP AS
SELECT l.key, l.ln, l.rlid,
       COALESCE((SELECT sum(c.counted) FROM x_ret_carton c WHERE c.key = l.key AND c.ln = l.ln), 0)
           + COALESCE((SELECT sum(x.qty) FROM x_ret_loose x WHERE x.key = l.key AND x.ln = l.ln), 0) AS received
  FROM x_ret_line l
 WHERE EXISTS (SELECT 1 FROM x_ret_parcel p WHERE p.key = l.key);

-- ---- 60.7 Fewer came back than approved and no more is coming: Sales mark the goods complete (WF-05 TH-3) - the
--      units short recorded as a discrepancy, RECEIVED, the order RETURNED.
INSERT INTO ordering.return_request_discrepancy (id, return_request_id, return_request_line_id, kind, sku, quantity,
                                                 note, recorded_by, recorded_at, created_at, created_by)
SELECT pg_temp.sid('ret:discrepancy:' || l.key || ':' || l.ln), r.rid, l.rlid, 'SHORT', l.sku, l.qty - v.received,
       'Goods marked complete', pg_temp.staff(r.sales), pg_temp.at(r.complete, '14:00'),
       pg_temp.at(r.complete, '14:00'), pg_temp.staff(r.sales)::text
  FROM x_ret r JOIN x_ret_line l ON l.key = r.key JOIN x_ret_received v ON v.rlid = l.rlid
 WHERE r.complete IS NOT NULL AND v.received < l.qty;

UPDATE ordering.return_request q
   SET status = 'RECEIVED', version = q.version + 1, last_modified_at = pg_temp.at(r.complete, '14:00'),
       last_modified_by = pg_temp.staff(r.sales)::text
  FROM x_ret r
 WHERE q.id = r.rid AND r.complete IS NOT NULL;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.sid('ret:history:' || r.key || ':5'), r.rid, 'AWAITING_RETURN', 'RECEIVED',
       'GOODS_COMPLETE: ' || r.complete_note, pg_temp.staff(r.sales), pg_temp.at(r.complete, '14:00'),
       pg_temp.at(r.complete, '14:00'), pg_temp.staff(r.sales)::text
  FROM x_ret r WHERE r.complete IS NOT NULL;

INSERT INTO x_ret_order_step
SELECT r.key, 5, 'RETURN_REQUESTED', 'RETURNED', 'RETURN_GOODS_BACK', pg_temp.staff(r.sales),
       pg_temp.at(r.complete, '14:00')
  FROM x_ret r WHERE r.complete IS NOT NULL;

UPDATE ordering.customer_order co
   SET status = s.to_status, version = co.version + 1, last_modified_at = s.at, last_modified_by = s.actor::text
  FROM x_ret_order_step s JOIN x_ret r ON r.key = s.key
 WHERE co.id = r.order_id AND s.step = 5;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---- 60.8 Refund proposed by Sales (step 40; RECEIVED -> SETTLEMENT_PENDING): per line the units approved and back,
--      worth what the order line charged for them with its VAT (OrderLine.grossOf, rounded half up to the dong).
CREATE TEMP TABLE x_ret_refund ON COMMIT DROP AS
SELECT l.key, l.ln, l.rlid, least(l.qty, v.received) AS units,
       CASE WHEN least(l.qty, v.received) = l.line_qty THEN l.line_gross
            ELSE round(l.line_gross * least(l.qty, v.received) / l.line_qty, 0) END AS amount
  FROM x_ret_line l JOIN x_ret_received v ON v.rlid = l.rlid JOIN x_ret r ON r.key = l.key
 WHERE r.prop IS NOT NULL;

UPDATE ordering.return_request_line l
   SET refund_quantity = f.units, refund_amount = f.amount, version = l.version + 1,
       last_modified_at = pg_temp.at(r.prop, '10:00'), last_modified_by = pg_temp.staff(r.sales)::text
  FROM x_ret_refund f JOIN x_ret r ON r.key = f.key
 WHERE l.id = f.rlid;

UPDATE ordering.return_request q
   SET status = 'SETTLEMENT_PENDING', settlement_amount = x.amount, money_mode = 'REFUND',
       proposed_by = pg_temp.staff(r.sales), proposed_at = pg_temp.at(r.prop, '10:00'), resolution_note = r.prop_note,
       version = q.version + 1, last_modified_at = pg_temp.at(r.prop, '10:00'),
       last_modified_by = pg_temp.staff(r.sales)::text
  FROM x_ret r JOIN (SELECT key, sum(amount) AS amount FROM x_ret_refund GROUP BY key) x ON x.key = r.key
 WHERE q.id = r.rid;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.sid('ret:history:' || r.key || ':6'), r.rid, 'RECEIVED', 'SETTLEMENT_PENDING',
       'REFUND_PROPOSED ' || x.amount::bigint || ' VND', pg_temp.staff(r.sales), pg_temp.at(r.prop, '10:00'),
       pg_temp.at(r.prop, '10:00'), pg_temp.staff(r.sales)::text
  FROM x_ret r JOIN (SELECT key, sum(amount) AS amount FROM x_ret_refund GROUP BY key) x ON x.key = r.key;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---- 60.9 Refund approved by the sales manager (four eyes) and settled in the same transaction (PaymentService
--      .settleReturn, OrderSettlement.returned): the goods' value first comes off what the order's installments still
--      owe - the return's own delivery batch first, then the rest, after delivery before before delivery before on
--      signing, the latest first - and only what is left is refundable (never more than was collected). The return
--      RESOLVED. O151 still owed its payment after delivery: the whole value comes off it (an installment_reduction,
--      the installment's reduced_amount up, its status unchanged while something is still owed), nothing is to refund,
--      and the order is refunded at once. The others had paid everything: the whole value is refundable and the order
--      stays RETURNED until the refund is recorded.
CREATE TEMP TABLE x_ret_reduce ON COMMIT DROP AS
WITH owing AS (
    SELECT r.key, i.id AS installment_id, i.amount - i.paid_amount - i.reduced_amount AS outstanding,
           q.settlement_amount,
           row_number() OVER (PARTITION BY r.key
                              ORDER BY (i.delivery_batch_id IS NOT DISTINCT FROM b.delivery_batch_id) DESC,
                                       CASE i.milestone WHEN 'AFTER_DELIVERY' THEN 1 WHEN 'BEFORE_DELIVERY' THEN 2
                                                        ELSE 3 END, i.seq DESC) AS rank
      FROM x_ret r
      JOIN ordering.return_request q ON q.id = r.rid
      JOIN ordering.order_shipment b ON b.id = q.shipment_id
      JOIN payment.installment i ON i.order_id = r.order_id
     WHERE r.settle IS NOT NULL AND i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE')
       AND i.status IN ('NOT_DUE', 'DUE', 'PARTIALLY_PAID') AND i.amount - i.paid_amount - i.reduced_amount > 0
)
SELECT key, installment_id, outstanding,
       least(outstanding, settlement_amount - COALESCE(sum(outstanding) OVER (PARTITION BY key ORDER BY rank
                                                     ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0)) AS taken
  FROM owing;
DELETE FROM x_ret_reduce WHERE taken <= 0;

CREATE TEMP TABLE x_ret_settle ON COMMIT DROP AS
SELECT r.key, r.rid, q.settlement_amount AS closed,
       COALESCE((SELECT sum(x.taken) FROM x_ret_reduce x WHERE x.key = r.key), 0) AS reduced,
       (SELECT sum(i.paid_amount) FROM payment.installment i
         WHERE i.order_id = r.order_id AND i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE')) AS collected
  FROM x_ret r JOIN ordering.return_request q ON q.id = r.rid
 WHERE r.settle IS NOT NULL;

DO $$
BEGIN
    -- What is left after the installments is refundable only up to what was collected; past that the order would
    -- take it off batches still to come, which none of these orders has.
    IF EXISTS (SELECT 1 FROM x_ret_settle WHERE closed - reduced > collected) THEN
        RAISE EXCEPTION 'returns: a settlement would refund more than its order collected';
    END IF;
END $$;

UPDATE payment.installment i
   SET reduced_amount = i.reduced_amount + x.taken,
       status = CASE WHEN i.paid_amount + i.reduced_amount + x.taken < i.amount THEN i.status
                     WHEN i.paid_amount > 0 THEN 'PAID' ELSE 'CANCELLED' END,
       paid_at = CASE WHEN i.paid_amount + i.reduced_amount + x.taken = i.amount AND i.paid_amount > 0
                      THEN pg_temp.at(r.settle, '15:00') ELSE i.paid_at END,
       cancelled_at = CASE WHEN i.paid_amount + i.reduced_amount + x.taken = i.amount AND i.paid_amount = 0
                           THEN pg_temp.at(r.settle, '15:00') ELSE i.cancelled_at END,
       version = i.version + 1, last_modified_at = pg_temp.at(r.settle, '15:00'),
       last_modified_by = pg_temp.staff('truongkd')::text
  FROM x_ret_reduce x JOIN x_ret r ON r.key = x.key
 WHERE i.id = x.installment_id;

INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
                                      source_id, currency, collected_amount, closed_amount, reduced_amount,
                                      surplus_amount, fee_amount, fee_covered_amount, refundable_amount,
                                      fee_owed_amount, settled_by, settled_at, version, created_at, created_by)
SELECT pg_temp.sid('ret:settlement:' || r.key), r.order_id, r.contract_id, r.customer_id, r.order_number,
       r.contract_number, 'GOODS_RETURNED', r.rid, 'VND', t.collected, t.closed, t.reduced, t.closed - t.reduced, 0, 0,
       t.closed - t.reduced, 0, pg_temp.staff('truongkd'), pg_temp.at(r.settle, '15:00'), 0,
       pg_temp.at(r.settle, '15:00'), pg_temp.staff('truongkd')::text
  FROM x_ret r JOIN x_ret_settle t ON t.key = r.key;

INSERT INTO payment.installment_reduction (id, settlement_id, installment_id, amount, created_at)
SELECT pg_temp.sid('ret:reduction:' || x.key || ':' || x.installment_id), pg_temp.sid('ret:settlement:' || x.key),
       x.installment_id, x.taken, pg_temp.at(r.settle, '15:00')
  FROM x_ret_reduce x JOIN x_ret r ON r.key = x.key;

UPDATE ordering.return_request q
   SET status = 'RESOLVED', settled_by = pg_temp.staff('truongkd'), settled_at = pg_temp.at(r.settle, '15:00'),
       refunded_amount = t.closed - t.reduced, offset_amount = t.reduced, vat_adjustment_required = TRUE,
       closed_at = pg_temp.at(r.settle, '15:00'), version = q.version + 1,
       last_modified_at = pg_temp.at(r.settle, '15:00'), last_modified_by = pg_temp.staff('truongkd')::text
  FROM x_ret r JOIN x_ret_settle t ON t.key = r.key
 WHERE q.id = r.rid;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.sid('ret:history:' || r.key || ':7'), r.rid, 'SETTLEMENT_PENDING', 'RESOLVED',
       'RESOLVED_REFUND ' || q.settlement_amount::bigint || ' VND', pg_temp.staff('truongkd'),
       pg_temp.at(r.settle, '15:00'), pg_temp.at(r.settle, '15:00'), pg_temp.staff('truongkd')::text
  FROM x_ret r JOIN ordering.return_request q ON q.id = r.rid
 WHERE r.settle IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---- 60.10 The accountant records the transfer the bank made (15 §4.5 step 16). On O199 the first record was typed
--      with a digit missing: voided with a reason the next morning and recorded again with the right amount.
INSERT INTO payment.refund_record (id, order_id, customer_id, order_number, amount, currency, refunded_on, reference,
                                   note, status, recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.sid('ret:refund:' || r.key || ':1'), r.order_id, r.customer_id, r.order_number,
       CASE WHEN r.key = 'O199' THEN round(q.settlement_amount / 10, 0) ELSE q.settlement_amount END, 'VND',
       pg_temp.day(r.refund), 'UNC-' || pg_temp.ymd(r.refund) || '-' || (7000 + r.seq),
       'Hoàn tiền hàng trả lại ' || r.rma || ' qua chuyển khoản Vietcombank', 'RECORDED', pg_temp.staff('ketoan'),
       pg_temp.at(r.refund, '10:30'), 0, pg_temp.at(r.refund, '10:30'), pg_temp.staff('ketoan')::text
  FROM x_ret r JOIN ordering.return_request q ON q.id = r.rid
 WHERE r.refund IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE payment.refund_record f
   SET status = 'VOIDED', voided_by = pg_temp.staff('ketoan'), voided_at = pg_temp.at(r.refund - 1, '08:45'),
       void_reason = 'Nhập thiếu một chữ số so với sao kê ngân hàng; ghi nhận lại đúng số tiền.', version = 1,
       last_modified_at = pg_temp.at(r.refund - 1, '08:45'), last_modified_by = pg_temp.staff('ketoan')::text
  FROM x_ret r
 WHERE f.id = pg_temp.sid('ret:refund:' || r.key || ':1') AND r.key = 'O199';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO payment.refund_record (id, order_id, customer_id, order_number, amount, currency, refunded_on, reference,
                                   note, status, recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.sid('ret:refund:' || r.key || ':2'), r.order_id, r.customer_id, r.order_number, q.settlement_amount,
       'VND', pg_temp.day(r.refund), 'UNC-' || pg_temp.ymd(r.refund) || '-' || (7000 + r.seq),
       'Hoàn tiền hàng trả lại ' || r.rma || ' qua chuyển khoản Vietcombank (ghi nhận lại)', 'RECORDED',
       pg_temp.staff('ketoan'), pg_temp.at(r.refund - 1, '08:50'), 0, pg_temp.at(r.refund - 1, '08:50'),
       pg_temp.staff('ketoan')::text
  FROM x_ret r JOIN ordering.return_request q ON q.id = r.rid
 WHERE r.key = 'O199' AND r.refund IS NOT NULL;

-- Everything made refundable is refunded and no return is open (ReturnRequestService.followRefunds, or followReturns
-- right at the settlement when the value only came off installments still owed): REFUNDED when what came back covers
-- the goods the order is worth, PARTIALLY_REFUNDED otherwise.
INSERT INTO x_ret_order_step
SELECT r.key, 6, 'RETURNED',
       CASE WHEN q.settlement_amount >= (SELECT sum(CASE WHEN ol.cancelled_quantity = 0
                                                         THEN ol.quantity * ol.unit_price + COALESCE(ol.tax_amount, 0)
                                                         ELSE round((ol.quantity * ol.unit_price
                                                                     + COALESCE(ol.tax_amount, 0))
                                                                    * (ol.quantity - ol.cancelled_quantity)
                                                                    / ol.quantity, 0) END)
                                           FROM ordering.order_line ol WHERE ol.order_id = r.order_id)
            THEN 'REFUNDED' ELSE 'PARTIALLY_REFUNDED' END,
       'REFUNDED', CASE WHEN r.refund IS NULL THEN pg_temp.staff('truongkd') ELSE pg_temp.staff('ketoan') END,
       CASE WHEN r.refund IS NULL THEN pg_temp.at(r.settle, '15:00')
            WHEN r.key = 'O199' THEN pg_temp.at(r.refund - 1, '08:50') + interval '2 seconds'
            ELSE pg_temp.at(r.refund, '10:30') + interval '2 seconds' END
  FROM x_ret r JOIN ordering.return_request q ON q.id = r.rid
 WHERE r.refund IS NOT NULL OR (q.status = 'RESOLVED' AND q.refunded_amount = 0);

UPDATE ordering.customer_order co
   SET status = s.to_status, refunded_at = s.at, version = co.version + 1, last_modified_at = s.at,
       last_modified_by = s.actor::text
  FROM x_ret_order_step s JOIN x_ret r ON r.key = s.key
 WHERE co.id = r.order_id AND s.step = 6;

-- The order's history, every step above.
INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.sid('ret:order-history:' || s.key || ':' || s.step), r.order_id, s.from_status, s.to_status, s.reason,
       s.actor, s.at, s.at, COALESCE(s.actor::text, 'system')
  FROM x_ret_order_step s JOIN x_ret r ON r.key = s.key;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---- 60.11 The warehouse manager looked at a returned carton whose box was only dented and released it from
--      quarantine (4D-1, CartonQuarantineService.release): a QUARANTINE_RELEASE putaway task, still to be done.
INSERT INTO warehouse.putaway_task (id, goods_receipt_id, sku, quantity, status, version, created_at, created_by,
                                    goods_receipt_line_id, stock_package_id, source)
SELECT pg_temp.sid('ret:release:' || c.key || ':' || c.n), NULL, c.sku, c.upc, 'PENDING', 0,
       pg_temp.at(r.release, '14:00'), pg_temp.staff(r.mgr)::text, NULL, c.package_id, 'QUARANTINE_RELEASE'
  FROM x_ret r JOIN x_ret_carton c ON c.key = r.key AND c.ln = 1 AND c.k = 1
 WHERE r.release IS NOT NULL AND NOT c.opened;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 70. E-invoices (kltn-docs 21, WF-05): one VAT invoice per delivered shipment, drawn up as a draft by the system when
--     the delivery is recorded (OrderDeliveredListener) and issued by the accountant through the sandbox provider -
--     the one active in invoicing.einvoice_provider_config, with automatic issuing off. Every DELIVERED order of the
--     base gets its original (those with i % 12 = 1 and 5 were delivered before their returns / carrier files), and so
--     does a shipment section 50 records delivered yesterday; nothing outside invoicing is written. What the provider is asked, and its own books (sandbox_issuance / sandbox_number), are written as the
--     application leaves them: EInvoiceIssuance.issue -> submit, then QUERY / SUBMIT / QUERY at once and QUERY /
--     DOWNLOAD when the sandbox has signed (5 s later), every call logged.
--   * every delivered shipment issued on the day of delivery (afternoon) or the next working morning, except the three
--     newest deliveries: still drafts, past their deadline (the accountants were warned and are reminded daily);
--   * one invoice whose files could not be stored for 40 minutes (storage down): issued at the provider, kept
--     SUBMITTING and asked again every reconciliation, announced stuck to the accountants, then issued;
--   * one invoice that named the buyer's tax code wrongly, replaced (agreement with the buyer attached);
--   * one invoice that named the buyer's old registered address: a 04/SS-HĐĐT notice, accepted (no new invoice);
--   * one customer's trade discount (2%) on three invoices: an adjustment of the newest, listing the other two;
--   * the returns section 60 resolves with a VAT adjustment: the RETURN adjustment the listener draws up
--     (EInvoiceCorrections.drawUpForReturn), issued the next working morning - the newest still a draft;
--   * the shipments section 50 records delivered yesterday: drafts, due at the end of the next working day.
--   The stories are told on orders with i % 12 not 1 or 5 only.
-- This fragment owns sandbox_number ('1', C26TSB / C27TSB) and the invoice numbers 1..N of each symbol; the codes are
-- EINV-<day>-7xxx. A later fragment that issues invoices continues from max(invoice_number) and updates that row.
-- ---------------------------------------------------------------------------------------------

-- Vietnamese amount in words, as common.text.VietnameseAmountInWords writes it.
CREATE FUNCTION pg_temp.inv_three(v INTEGER, full_ BOOLEAN) RETURNS TEXT LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    digits TEXT[] := ARRAY['không', 'một', 'hai', 'ba', 'bốn', 'năm', 'sáu', 'bảy', 'tám', 'chín'];
    h INTEGER := v / 100;
    t INTEGER := v / 10 % 10;
    o INTEGER := v % 10;
    w TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF h > 0 OR full_ THEN
        w := array_append(w, digits[h + 1]);
        w := array_append(w, 'trăm');
    END IF;
    IF t = 0 THEN
        IF o > 0 THEN
            IF h > 0 OR full_ THEN
                w := array_append(w, 'lẻ');
            END IF;
            w := array_append(w, digits[o + 1]);
        END IF;
        RETURN array_to_string(w, ' ');
    END IF;
    w := array_append(w, CASE WHEN t = 1 THEN 'mười' ELSE digits[t + 1] || ' mươi' END);
    IF o = 1 AND t > 1 THEN
        w := array_append(w, 'mốt');
    ELSIF o = 5 THEN
        w := array_append(w, 'lăm');
    ELSIF o > 0 THEN
        w := array_append(w, digits[o + 1]);
    END IF;
    RETURN array_to_string(w, ' ');
END $$;

CREATE FUNCTION pg_temp.inv_below_billion(v BIGINT, continued BOOLEAN) RETURNS TEXT LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    groups TEXT[] := ARRAY['', 'nghìn', 'triệu'];
    started BOOLEAN := continued;
    w TEXT[] := ARRAY[]::TEXT[];
    three INTEGER;
BEGIN
    FOR g IN REVERSE 2..0 LOOP
        three := ((v / (1000::BIGINT ^ g)::BIGINT) % 1000)::INTEGER;
        CONTINUE WHEN three = 0;
        w := array_append(w, pg_temp.inv_three(three, started));
        IF groups[g + 1] <> '' THEN
            w := array_append(w, groups[g + 1]);
        END IF;
        started := TRUE;
    END LOOP;
    RETURN array_to_string(w, ' ');
END $$;

CREATE FUNCTION pg_temp.inv_number(v NUMERIC) RETURNS TEXT LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    billions NUMERIC := trunc(v / 1000000000);
    below TEXT;
    w TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF billions > 0 THEN
        w := array_append(w, pg_temp.inv_number(billions));
        w := array_append(w, 'tỷ');
    END IF;
    below := pg_temp.inv_below_billion((v - billions * 1000000000)::BIGINT, billions > 0);
    IF below <> '' THEN
        w := array_append(w, below);
    END IF;
    RETURN array_to_string(w, ' ');
END $$;

CREATE FUNCTION pg_temp.inv_words(amount NUMERIC) RETURNS TEXT LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    words TEXT := CASE WHEN amount = 0 THEN 'không' ELSE pg_temp.inv_number(abs(amount)) END;
BEGIN
    IF amount < 0 THEN
        RETURN 'Âm ' || words || ' đồng';
    END IF;
    -- every number word starts with an ASCII letter: upper() needs no locale
    RETURN upper(left(words, 1)) || substr(words, 2) || ' đồng';
END $$;

-- The working day after a day (IssueDeadline: weekends skipped, no holidays).
CREATE FUNCTION pg_temp.inv_next_wd(d DATE) RETURNS DATE LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE extract(isodow FROM d)::int WHEN 5 THEN d + 3 WHEN 6 THEN d + 2 ELSE d + 1 END $$;

-- A day itself when it is a working day, else the Monday after.
CREATE FUNCTION pg_temp.inv_wd(d DATE) RETURNS DATE LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE extract(isodow FROM d)::int WHEN 6 THEN d + 2 WHEN 7 THEN d + 1 ELSE d END $$;

CREATE FUNCTION pg_temp.inv_ts(d DATE, hhmi TEXT) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT (d + hhmi::time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

-- IssueDeadline.of: the end of the working day after the invoice's date (NĐ 254 Phụ lục điểm 7).
CREATE FUNCTION pg_temp.inv_due(d DATE) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT pg_temp.inv_ts(pg_temp.inv_next_wd(d) + 1, '00:00') $$;

CREATE FUNCTION pg_temp.inv_h(t TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT encode(sha256(convert_to(t, 'UTF8')), 'hex') $$;

CREATE FUNCTION pg_temp.inv_ms(ms INTEGER) RETURNS INTERVAL LANGUAGE sql IMMUTABLE AS
$$ SELECT make_interval(secs => ms / 1000.0) $$;

-- ---------------------------------------------------------------------------------------------
-- 70.1 The plan: which delivered order gets which story, when each invoice is drawn up, sent and issued
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_inv_o ON COMMIT DROP AS
SELECT o.key, o.seq, o.i, o.id AS order_id, o.order_number, o.contract_number, o.customer_id, o.cust, o.batch_id, o.d,
       pg_temp.day(o.d) AS dday, pg_temp.sid('shipment:' || o.key) AS shipment_id, pg_temp.at(o.d, '15:00') AS recorded_at,
       row_number() OVER (ORDER BY o.i % 12 IN (1, 5), o.d, o.seq)::int AS recent, 'issued'::text AS role
  FROM h_delivered o
 WHERE o.stage = 'DELIVERED';

-- Shipments section 50 records delivered yesterday (by the carrier's file, or the manager's correction): drawn up when
-- the delivery was recorded and dated the day of the delivery; nothing is issued yet. None without section 50.
INSERT INTO x_inv_o (key, seq, i, order_id, order_number, contract_number, customer_id, cust, batch_id, d, dday,
                     shipment_id, recorded_at, recent, role)
SELECT o.key, o.seq, o.i, o.id, o.order_number, o.contract_number, o.customer_id, o.cust, o.batch_id,
       pg_temp.today() - (os.outcome_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
       (os.outcome_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, os.id,
       coalesce((SELECT max(h.occurred_at) FROM ordering.order_status_history h
                  WHERE h.order_id = o.id AND h.to_status = 'DELIVERED'), os.outcome_at),
       100000 + o.seq, 'late'
  FROM h_shipped o
  JOIN ordering.order_shipment os ON os.id = pg_temp.sid('shipment:' || o.key) AND os.status = 'DELIVERED'
 WHERE o.stage <> 'DELIVERED';

-- The three newest deliveries (of the orders the stories are told on) are still drafts.
UPDATE x_inv_o SET role = 'open' WHERE recent <= 3;

-- The stuck one: delivered on a weekday nobody else's goods were delivered, so nothing else was sent that afternoon.
UPDATE x_inv_o SET role = 'stuck'
 WHERE key = (SELECT o.key FROM x_inv_o o
               WHERE o.role = 'issued' AND o.i % 12 NOT IN (1, 5) AND o.d BETWEEN 20 AND 60
                 AND extract(isodow FROM o.dday) BETWEEN 1 AND 5
                 AND NOT EXISTS (SELECT 1 FROM x_inv_o p WHERE p.dday = o.dday AND p.key <> o.key)
               ORDER BY o.seq LIMIT 1);

-- The stories of corrections, on invoices issued well before the open drafts.
CREATE TEMP TABLE x_inv_pick (story TEXT PRIMARY KEY, key TEXT, seq INTEGER) ON COMMIT DROP;
INSERT INTO x_inv_pick
SELECT 'replaced', key, seq FROM x_inv_o WHERE role = 'issued' AND i % 12 NOT IN (1, 5) AND d BETWEEN 75 AND 110
 ORDER BY seq LIMIT 1;
INSERT INTO x_inv_pick
SELECT 'notice', o.key, o.seq FROM x_inv_o o
 WHERE o.role = 'issued' AND o.i % 12 NOT IN (1, 5) AND o.d BETWEEN 45 AND 70
   AND o.cust <> (SELECT x.cust FROM x_inv_o x JOIN x_inv_pick p ON p.key = x.key WHERE p.story = 'replaced')
 ORDER BY o.seq LIMIT 1;
-- The customer with the most invoices issued 36 to 120 days ago, other than those two; its three newest.
INSERT INTO x_inv_pick
SELECT CASE n WHEN 1 THEN 'discounted' ELSE 'listed' || (n - 1) END, key, seq
  FROM (SELECT o.key, o.seq, row_number() OVER (ORDER BY o.d, o.seq) AS n
          FROM x_inv_o o
         WHERE o.role = 'issued' AND o.i % 12 NOT IN (1, 5) AND o.d BETWEEN 36 AND 120
           AND o.cust = (SELECT c.cust FROM x_inv_o c
                          WHERE c.role = 'issued' AND c.i % 12 NOT IN (1, 5) AND c.d BETWEEN 36 AND 120
                            AND c.cust NOT IN (SELECT x.cust FROM x_inv_o x JOIN x_inv_pick p ON p.key = x.key)
                          GROUP BY c.cust ORDER BY count(*) DESC, c.cust LIMIT 1)) x
 WHERE n <= 3;

DO $$
BEGIN
    IF (SELECT count(*) FROM x_inv_pick) <> 5 OR NOT EXISTS (SELECT 1 FROM x_inv_o WHERE role = 'stuck') THEN
        RAISE EXCEPTION 'invoicing fragment: the stories found no order (picked %)', (SELECT count(*) FROM x_inv_pick);
    END IF;
END $$;

-- One row per invoice.
CREATE TEMP TABLE x_inv (
    k TEXT PRIMARY KEY, id UUID, type TEXT, basis TEXT, okey TEXT, orig_k TEXT, role TEXT,
    order_id UUID, order_number TEXT, contract_number TEXT, shipment_id UUID, shipment_code TEXT, batch_id UUID,
    customer_id UUID, issue_date DATE, created_at TIMESTAMPTZ, created_by TEXT,
    warned_at TIMESTAMPTZ, submit_at TIMESTAMPTZ, job_at TIMESTAMPTZ, symbol TEXT, num INTEGER, code TEXT,
    buyer_name TEXT, buyer_tax TEXT, buyer_address TEXT, buyer_email TEXT,
    seller_name TEXT, seller_tax TEXT, seller_address TEXT, reason TEXT, return_request_id UUID, rma TEXT,
    same_day BOOLEAN, send_day DATE
) ON COMMIT DROP;

-- Originals: drafted two seconds after the delivery was recorded (15:00), by the listener (no user).
INSERT INTO x_inv (k, id, type, okey, role, order_id, order_number, contract_number, shipment_id, shipment_code,
                   batch_id, customer_id, issue_date, created_at, created_by, buyer_name, buyer_tax, buyer_address,
                   buyer_email, seller_name, seller_tax, seller_address)
SELECT o.key, pg_temp.sid('inv:einvoice:' || o.key), 'ORIGINAL', o.key, o.role, o.order_id, o.order_number,
       o.contract_number, o.shipment_id, os.shipment_code, o.batch_id, o.customer_id, o.dday,
       o.recorded_at + pg_temp.inv_ms(1800 + pg_temp.pick('inv:draw:' || o.key, 0, 900)), 'system',
       c.full_name, c.tax_code, lp.registered_address, lp.invoice_email, sc.seller_name, sc.seller_tax_code,
       sc.seller_address
  FROM x_inv_o o
  JOIN ordering.order_shipment os ON os.id = o.shipment_id
  JOIN customer.customer c ON c.id = o.customer_id
  JOIN customer.customer_legal_profile lp ON lp.customer_id = o.customer_id
  JOIN ordering.sales_contract sc ON sc.order_id = o.order_id AND sc.contract_number = o.contract_number;

-- The buyer's tax code typed wrongly on the replaced one (its last two digits swapped): the customer's record was
-- corrected afterwards, which the replacement copies.
UPDATE x_inv i
   SET buyer_tax = CASE WHEN substr(i.buyer_tax, 9, 1) <> substr(i.buyer_tax, 10, 1)
                        THEN left(i.buyer_tax, 8) || substr(i.buyer_tax, 10, 1) || substr(i.buyer_tax, 9, 1)
                        ELSE left(i.buyer_tax, 9) || ((substr(i.buyer_tax, 10, 1)::int + 1) % 10)::text END
  FROM x_inv_pick p WHERE p.story = 'replaced' AND p.key = i.k;

-- The old registered address on the one the notice is about: the customer moved before the invoice's date.
UPDATE x_inv i
   SET buyer_address = (ARRAY['Số 9 Nguyễn Cửu Vân', 'Số 27 Phan Văn Trị', 'Số 140 Lê Văn Sỹ'])[1 + p.seq % 3]
                       || substr(i.buyer_address, strpos(i.buyer_address, ','))
  FROM x_inv_pick p WHERE p.story = 'notice' AND p.key = i.k;

-- When the accountant sends each original: the afternoon of a weekday delivery, or the next working morning (always,
-- for a weekend delivery); in each batch one after another, oldest invoice first, so numbers follow dates.
ALTER TABLE x_inv_o ADD COLUMN same_day BOOLEAN, ADD COLUMN send_day DATE;
UPDATE x_inv_o
   SET same_day = role = 'stuck'
                  OR (extract(isodow FROM dday) BETWEEN 1 AND 5
                      AND (pg_temp.rnd('inv:same-day:' || key) < 0.55
                           OR pg_temp.today() - pg_temp.inv_next_wd(dday) < 1));
UPDATE x_inv_o SET send_day = CASE WHEN same_day THEN dday ELSE pg_temp.inv_next_wd(dday) END;

UPDATE x_inv i SET same_day = o.same_day, send_day = o.send_day
  FROM x_inv_o o WHERE i.k = o.key AND o.role IN ('issued', 'stuck');

-- The adjustments of the returns section 60 resolved with a VAT adjustment (ReturnResolvedListener): drawn up by the
-- listener just after the refund was settled, dated that day, on the shipment's invoice in force; sent the next
-- working morning with that morning's originals - the newest still a draft. None without section 60.
INSERT INTO x_inv (k, id, type, basis, okey, orig_k, role, order_id, order_number, contract_number, shipment_id,
                   shipment_code, batch_id, customer_id, issue_date, created_at, created_by, buyer_name, buyer_tax,
                   buyer_address, buyer_email, seller_name, seller_tax, seller_address, reason, return_request_id,
                   rma, same_day, send_day)
SELECT 'T:' || o.k, pg_temp.sid('inv:einvoice:return:' || q.id), 'ADJUSTMENT', 'RETURN', o.okey, o.k,
       CASE WHEN row_number() OVER (ORDER BY q.settled_at DESC, q.id) = 1 THEN 'open' ELSE 'issued' END,
       o.order_id, o.order_number, o.contract_number, o.shipment_id, o.shipment_code, o.batch_id, o.customer_id,
       (q.settled_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, q.settled_at + pg_temp.inv_ms(2400), 'system',
       o.buyer_name, o.buyer_tax, o.buyer_address, o.buyer_email, o.seller_name, o.seller_tax, o.seller_address,
       'Hàng bán bị trả lại (phiếu trả hàng ' || q.rma_number || ')', q.id, q.rma_number, FALSE,
       pg_temp.inv_next_wd((q.settled_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
  FROM ordering.return_request q
  JOIN x_inv o ON o.order_id = q.order_id AND o.type = 'ORIGINAL' AND o.shipment_id = q.shipment_id
 WHERE q.status = 'RESOLVED' AND q.vat_adjustment_required
   AND EXISTS (SELECT 1 FROM ordering.return_request_line l WHERE l.return_request_id = q.id AND l.refund_quantity > 0);

-- When the accountant sends each: in each batch one after another, oldest invoice first, so numbers follow dates.
UPDATE x_inv i
   SET submit_at = CASE WHEN i.role = 'stuck' THEN pg_temp.inv_ts(i.send_day, '16:10')
                        ELSE pg_temp.inv_ts(i.send_day, CASE WHEN i.same_day THEN '16:00' ELSE '08:30' END)
                             + make_interval(mins => (7 * (b.n - 1))::int, secs => pg_temp.pick('inv:send:' || i.k, 0, 50))
                   END
  FROM (SELECT k, row_number() OVER (PARTITION BY send_day, same_day ORDER BY issue_date, k) AS n
          FROM x_inv WHERE role IN ('issued', 'stuck') AND send_day IS NOT NULL) b
 WHERE i.k = b.k;

-- The deadline sweep warns a day before the deadline: at midnight before the next working day - every invoice not
-- sent by then, drafts left open included (once that midnight has come).
UPDATE x_inv
   SET warned_at = pg_temp.inv_ts(pg_temp.inv_next_wd(issue_date), '00:07')
                   + make_interval(secs => pg_temp.pick('inv:warn:' || k, 0, 40))
 WHERE (role IN ('issued', 'stuck') AND send_day IS NOT NULL AND NOT same_day)
    OR (role IN ('open', 'late') AND pg_temp.inv_next_wd(issue_date) <= pg_temp.today());

-- The replacement: drawn up nine days after the delivery, at 09:40; the agreement attached at 10:15, sent at 10:40.
INSERT INTO x_inv (k, id, type, basis, okey, orig_k, role, order_id, order_number, contract_number, shipment_id,
                   shipment_code, batch_id, customer_id, issue_date, created_at, created_by, submit_at, buyer_name,
                   buyer_tax, buyer_address, buyer_email, seller_name, seller_tax, seller_address)
SELECT 'R:' || i.k, pg_temp.sid('inv:einvoice:replacement:' || i.k), 'REPLACEMENT', 'ERROR', i.okey, i.k, 'issued',
       i.order_id, i.order_number, i.contract_number, i.shipment_id, i.shipment_code, i.batch_id, i.customer_id,
       x.day, pg_temp.inv_ts(x.day, '09:40'), pg_temp.staff('ketoan')::text,
       pg_temp.inv_ts(x.day, '10:40') + make_interval(secs => 12), c.full_name, c.tax_code, i.buyer_address,
       i.buyer_email, i.seller_name, i.seller_tax, i.seller_address
  FROM x_inv i
  JOIN x_inv_pick p ON p.key = i.k AND p.story = 'replaced'
  JOIN customer.customer c ON c.id = i.customer_id
 CROSS JOIN LATERAL (SELECT pg_temp.inv_wd(i.issue_date + 9) AS day) x;

-- The trade discount: drawn up on the working day 30 days ago at 10:05, sent at 10:30.
INSERT INTO x_inv (k, id, type, basis, okey, orig_k, role, order_id, order_number, contract_number, shipment_id,
                   shipment_code, batch_id, customer_id, issue_date, created_at, created_by, submit_at, buyer_name,
                   buyer_tax, buyer_address, buyer_email, seller_name, seller_tax, seller_address)
SELECT 'A:' || i.k, pg_temp.sid('inv:einvoice:discount:' || i.k), 'ADJUSTMENT', 'TRADE_DISCOUNT', i.okey, i.k,
       'issued', i.order_id, i.order_number, i.contract_number, i.shipment_id, i.shipment_code, i.batch_id,
       i.customer_id, x.day, pg_temp.inv_ts(x.day, '10:05'), pg_temp.staff('ketoan')::text,
       pg_temp.inv_ts(x.day, '10:30') + make_interval(secs => 21), i.buyer_name, i.buyer_tax, i.buyer_address,
       i.buyer_email, i.seller_name, i.seller_tax, i.seller_address
  FROM x_inv i
  JOIN x_inv_pick p ON p.key = i.k AND p.story = 'discounted'
 CROSS JOIN LATERAL (SELECT pg_temp.inv_wd(pg_temp.day(30)) AS day) x;

-- Series, numbers in the order the sandbox took the invoices in, codes in the order they were drawn up each day.
UPDATE x_inv SET symbol = 'C' || to_char(issue_date, 'YY') || 'TSB' WHERE submit_at IS NOT NULL;
UPDATE x_inv i SET num = n.num
  FROM (SELECT k, row_number() OVER (PARTITION BY symbol ORDER BY submit_at, k)::int AS num
          FROM x_inv WHERE submit_at IS NOT NULL) n
 WHERE n.k = i.k;
UPDATE x_inv i SET code = 'EINV-' || to_char(i.issue_date, 'YYYYMMDD') || '-' || (7000 + n.n)
  FROM (SELECT k, row_number() OVER (PARTITION BY issue_date ORDER BY created_at, k) AS n FROM x_inv) n
 WHERE n.k = i.k;

-- When the sandbox's answer is collected: 6 to 29 s after sending (it signs 5 s after taking an invoice in); the stuck
-- one only on the 60th reconciliation, 41 s apart (each waits ~10 s for the storage that is down).
UPDATE x_inv SET job_at = submit_at + make_interval(secs => pg_temp.pick('inv:job:' || k, 6, 29))
 WHERE submit_at IS NOT NULL AND role <> 'stuck';
UPDATE x_inv SET job_at = submit_at + make_interval(secs => 30 + 59 * 41) WHERE role = 'stuck';

-- The reasons the corrections give.
UPDATE x_inv r
   SET reason = 'Hóa đơn ghi sai mã số thuế người mua (ghi ' || o.buyer_tax || ', đúng là ' || r.buyer_tax
                || '); lập hóa đơn thay thế theo biên bản thỏa thuận với người mua'
  FROM x_inv o WHERE r.type = 'REPLACEMENT' AND o.k = r.orig_k;
UPDATE x_inv a
   SET reason = 'Chiết khấu thương mại 2% trên tổng giá trị hàng của các hóa đơn ký hiệu ' || o.symbol || ' số '
                || (SELECT string_agg(lpad(x.num::text, 8, '0'), ', ' ORDER BY x.num)
                      FROM x_inv x JOIN x_inv_pick p ON p.key = x.k
                     WHERE p.story IN ('discounted', 'listed1', 'listed2'))
                || ' (đạt sản lượng theo phụ lục hợp đồng)'
  FROM x_inv o WHERE a.basis = 'TRADE_DISCOUNT' AND o.k = a.orig_k;

-- The sandbox's own documents of what it issued: the tax authority's XML (TaxAuthorityInvoiceXml) and a one-page PDF.
CREATE FUNCTION pg_temp.inv_xml(p_id UUID, p_number INTEGER, p_tac TEXT, p_lookup TEXT, p_signed TIMESTAMPTZ)
    RETURNS TEXT LANGUAGE sql STABLE AS $$
SELECT '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' || chr(10)
       || '<!-- SANDBOX E-INVOICE - TEST DOCUMENT - NO LEGAL VALUE - not signed, not sent to the tax authority -->'
       || chr(10)
       || xmlelement(name "HDon",
            xmlelement(name "DLHDon", xmlattributes('data' AS "Id"),
              xmlelement(name "TTChung",
                xmlforest('2.1.1' AS "PBan", 'HÓA ĐƠN GIÁ TRỊ GIA TĂNG' AS "THDon", e.template_code AS "KHMSHDon",
                          e.symbol AS "KHHDon", p_number::text AS "SHDon", to_char(e.issue_date, 'YYYY-MM-DD') AS "NLap",
                          e.currency AS "DVTTe", '1' AS "TGia", e.payment_method AS "HTTToan"),
                CASE WHEN o.id IS NOT NULL THEN
                  xmlelement(name "TTHDLQuan",
                    xmlforest(CASE e.type WHEN 'REPLACEMENT' THEN '1' ELSE '2' END AS "TCHDon",
                              o.template_code AS "KHMSHDCLQuan", o.symbol AS "KHHDCLQuan",
                              o.invoice_number::text AS "SHDCLQuan", to_char(o.issue_date, 'YYYY-MM-DD') AS "NLHDCLQuan",
                              e.adjustment_reason AS "GChu")) END),
              xmlelement(name "NDHDon",
                xmlelement(name "NBan", xmlforest(e.seller_name AS "Ten", e.seller_tax_code AS "MST",
                                                  e.seller_address AS "DChi")),
                xmlelement(name "NMua", xmlforest(e.buyer_name AS "Ten", e.buyer_tax_code AS "MST",
                                                  e.buyer_address AS "DChi", e.buyer_email AS "DCTDTu")),
                xmlelement(name "DSHHDVu",
                  (SELECT xmlagg(xmlelement(name "HHDVu",
                            xmlforest(CASE l.kind WHEN 'NOTE' THEN '4' ELSE '1' END AS "TChat", l.line_no::text AS "STT",
                                      l.sku AS "MHHDVu", l.item_name AS "THHDVu", l.unit AS "DVTinh",
                                      l.quantity::text AS "SLuong", trunc(l.unit_price)::text AS "DGia",
                                      trunc(l.amount)::text AS "ThTien", trim_scale(l.tax_rate)::text || '%' AS "TSuat",
                                      trunc(l.tax_amount)::text AS "TThue")) ORDER BY l.line_no)
                     FROM invoicing.einvoice_line l WHERE l.einvoice_id = e.id)),
                xmlelement(name "TToan",
                  xmlelement(name "THTTLTSuat",
                    (SELECT xmlagg(xmlelement(name "LTSuat",
                              xmlforest(trim_scale(r.tax_rate)::text || '%' AS "TSuat", trunc(r.amount)::text AS "ThTien",
                                        trunc(r.tax)::text AS "TThue")) ORDER BY r.tax_rate)
                       FROM (SELECT tax_rate, sum(amount) AS amount, sum(tax_amount) AS tax
                               FROM invoicing.einvoice_line WHERE einvoice_id = e.id AND kind <> 'NOTE'
                              GROUP BY tax_rate) r)),
                  xmlforest(trunc(e.subtotal)::text AS "TgTCThue", trunc(e.tax_total)::text AS "TgTThue",
                            trunc(e.grand_total)::text AS "TgTTTBSo", e.amount_in_words AS "TgTTTBChu"))),
              xmlelement(name "TTKhac",
                xmlelement(name "TTin", xmlforest('OrderNumber' AS "TTruong", e.order_number AS "DLieu")),
                xmlelement(name "TTin", xmlforest('ContractNumber' AS "TTruong", e.contract_number AS "DLieu")),
                xmlelement(name "TTin", xmlforest('ReferenceCode' AS "TTruong", e.code AS "DLieu")),
                xmlelement(name "TTin", xmlforest('LookupCode' AS "TTruong", p_lookup AS "DLieu")))),
            xmlelement(name "MCCQT", p_tac),
            xmlelement(name "DSCKS", xmlcomment(' No signature: a sandbox document. A provider signs here (XAdES; QD '
                                                || '1233/QD-CT). Signing time recorded: '
                                                || to_char(p_signed AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
                                                || ' ')))::text
  FROM invoicing.einvoice e
  LEFT JOIN invoicing.einvoice o ON o.id = e.original_einvoice_id
 WHERE e.id = p_id
$$;

CREATE FUNCTION pg_temp.inv_pdf(p_title TEXT) RETURNS BYTEA LANGUAGE sql IMMUTABLE AS $$
SELECT convert_to(
    '%PDF-1.4' || chr(10)
    || '1 0 obj <</Type /Catalog /Pages 2 0 R>> endobj' || chr(10)
    || '2 0 obj <</Type /Pages /Kids [3 0 R] /Count 1>> endobj' || chr(10)
    || '3 0 obj <</Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources <</Font <</F1 4 0 R>>>> '
    || '/Contents 5 0 R>> endobj' || chr(10)
    || '4 0 obj <</Type /Font /Subtype /Type1 /BaseFont /Helvetica>> endobj' || chr(10)
    || '5 0 obj <</Length ' || length(s.stream) || '>> stream' || chr(10) || s.stream || chr(10) || 'endstream endobj'
    || chr(10) || 'trailer <</Root 1 0 R>>' || chr(10) || '%%EOF' || chr(10), 'UTF8')
  FROM (SELECT 'BT /F1 14 Tf 50 790 Td (HOA DON THU NGHIEM - KHONG CO GIA TRI PHAP LY) Tj 0 -24 Td (' || p_title
               || ') Tj ET' AS stream) s
$$;

-- ---------------------------------------------------------------------------------------------
-- 70.2 The originals drawn up as drafts when each delivery is recorded (EInvoiceDrafts.drawUp)
-- ---------------------------------------------------------------------------------------------
INSERT INTO invoicing.einvoice (id, code, type, status, adjusted, order_id, order_number, contract_number, shipment_id,
                                shipment_code, delivery_batch_id, customer_id, attempt, issue_date, issue_due_at,
                                seller_name, seller_tax_code, seller_address, buyer_name, buyer_tax_code, buyer_address,
                                buyer_email, currency, payment_method, subtotal, tax_total, grand_total,
                                amount_in_words, version, created_at, created_by)
SELECT i.id, i.code, 'ORIGINAL', 'DRAFT', FALSE, i.order_id, i.order_number, i.contract_number, i.shipment_id,
       i.shipment_code, i.batch_id, i.customer_id, 0, i.issue_date, pg_temp.inv_due(i.issue_date), i.seller_name,
       i.seller_tax, i.seller_address, i.buyer_name, i.buyer_tax, i.buyer_address, i.buyer_email, 'VND',
       'BANK_TRANSFER', t.net, t.tax, t.net + t.tax, pg_temp.inv_words(t.net + t.tax), 0, i.created_at, i.created_by
  FROM x_inv i
  JOIN (SELECT o.key, sum(x.amount) AS net, sum(x.tax) AS tax
          FROM x_inv_o o
          JOIN (SELECT ol.order_id, ol.cartons * ol.units_per_carton * ol.unit_price AS amount,
                       round(ol.cartons * ol.units_per_carton * ol.unit_price * 8 / 100, 0) AS tax
                  FROM ordering.order_line ol
                UNION ALL
                SELECT f.order_id, f.amount, round(f.amount * 8 / 100, 0)
                  FROM ordering.order_fee_line f WHERE f.kind = 'PLATE_FEE' AND f.amount > 0) x
            ON x.order_id = o.order_id
         GROUP BY o.key) t ON t.key = i.k
 WHERE i.type = 'ORIGINAL';

-- Goods: each order line the shipment delivered, by the carton at the carton's price (the piece's times the pieces in a
-- carton), at the VAT rate of the day (8%, ordering.tax_rate_period); then each plate fee, on the order's first invoice.
INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name, unit,
                                     quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
SELECT pg_temp.sid('inv:line:' || l.key || ':' || l.ln), i.id, l.ln, 'GOODS', NULL, l.line_id, l.sku,
       -- ProductService.nameForSku: the product's name when exactly one product has the SKU, else the SKU itself
       coalesce((SELECT min(p.name) FROM product.products p JOIN product.variants v ON v.product_id = p.id
                  WHERE upper(v.sku) = upper(l.sku) HAVING count(*) = 1), l.sku),
       'Thùng', l.cartons, l.upc, l.unit_price * l.upc, 8, l.cartons * l.upc * l.unit_price,
       round(l.cartons * l.upc * l.unit_price * 8 / 100, 0)
  FROM h_line l
  JOIN x_inv i ON i.k = l.key AND i.type = 'ORIGINAL';

INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name, unit,
                                     quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
SELECT pg_temp.sid('inv:fee:' || l.key || ':' || l.ln), i.id,
       (SELECT count(*) FROM h_line g WHERE g.key = l.key)
         + row_number() OVER (PARTITION BY l.key ORDER BY l.ln),
       'FEE', 'PLATE_FEE', NULL, l.sku, 'Phí khuôn / bản in (' || l.sku || ')', 'Lần', 1, NULL, l.fee, 8, l.fee,
       round(l.fee * 8 / 100, 0)
  FROM h_line l
  JOIN x_inv i ON i.k = l.key AND i.type = 'ORIGINAL'
 WHERE l.plate AND l.fee > 0;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The deadline sweep: the drafts still waiting at midnight before the next working day are announced (EInvoiceIssueDue).
UPDATE invoicing.einvoice e
   SET due_warned_at = i.warned_at, version = e.version + 1, last_modified_at = i.warned_at, last_modified_by = 'system'
  FROM x_inv i
 WHERE e.id = i.id AND i.type = 'ORIGINAL' AND i.warned_at IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The open drafts are past their deadline: reminded once a day since (EInvoiceWorkQueues.remindOverdue); the last
-- reminder was one of this night's sweeps (each a day or more after the one before: due_warned_at at 00:07, reminded
-- at 00:22, sweeps every 15 minutes). A statement of its own, the version untouched, as the repository does.
UPDATE invoicing.einvoice e
   SET overdue_warned_at = pg_temp.at(0, '00:22') + make_interval(secs => pg_temp.pick('inv:remind:' || i.k, 0, 40))
  FROM x_inv i
 WHERE e.id = i.id AND i.type = 'ORIGINAL' AND i.role IN ('open', 'late')
   AND e.issue_due_at <= pg_temp.at(0, '00:22')
   AND i.warned_at + interval '1 day' <= pg_temp.at(0, '00:22');

-- ---------------------------------------------------------------------------------------------
-- 70.3 Issuing the originals (EInvoiceIssuance.issue): sent by the accountant, the sandbox asked at once, then asked
--      again by the reconciliation once it has signed
-- ---------------------------------------------------------------------------------------------
UPDATE invoicing.einvoice e
   SET status = 'SUBMITTING', provider = 'SANDBOX', template_code = '1', symbol = i.symbol, attempt = 1,
       transaction_id = i.id || '-1', submitted_at = i.submit_at, submitted_by = pg_temp.staff('ketoan'),
       version = e.version + 1, last_modified_at = i.submit_at, last_modified_by = pg_temp.staff('ketoan')::text
  FROM x_inv i
 WHERE e.id = i.id AND i.type = 'ORIGINAL' AND i.submit_at IS NOT NULL;

-- What the sandbox took in: numbered at once, signed 5 s later (SandboxProvider.takeIn).
INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, invoice_number,
                                        tax_authority_code, lookup_code, rejection_code, rejection_message,
                                        xml_document, pdf_document, request_hash, received_at, ready_at, kind)
SELECT e.transaction_id, 'ISSUED', '1', i.symbol, i.num,
       'SBX' || substr(upper(pg_temp.inv_h(e.transaction_id)), 1, 31),
       'SBX' || substr(upper(pg_temp.inv_h(e.transaction_id)), 32, 10), NULL, NULL,
       pg_temp.inv_xml(e.id, i.num, 'SBX' || substr(upper(pg_temp.inv_h(e.transaction_id)), 1, 31),
                       'SBX' || substr(upper(pg_temp.inv_h(e.transaction_id)), 32, 10),
                       i.submit_at + pg_temp.inv_ms(5070)),
       pg_temp.inv_pdf('1' || i.symbol || ' No ' || lpad(i.num::text, 8, '0') || ' - ' || i.code),
       pg_temp.inv_h('inv:document:' || e.transaction_id), i.submit_at + pg_temp.inv_ms(70),
       i.submit_at + pg_temp.inv_ms(5070), 'ORIGINAL'
  FROM x_inv i JOIN invoicing.einvoice e ON e.id = i.id
 WHERE i.type = 'ORIGINAL' AND i.submit_at IS NOT NULL;

-- Asked first (nothing there), sent, asked again (the sandbox is still signing): one row per call, hashes only.
INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
                                              http_status, error_code, request_hash, response_hash, called_at,
                                              duration_ms)
SELECT pg_temp.sid('inv:call:' || i.k || ':' || c.n), i.id, 'SANDBOX', i.id || '-1', c.op, c.outcome, NULL, NULL,
       CASE WHEN c.op = 'SUBMIT' THEN pg_temp.inv_h('inv:document:' || i.id || '-1') END,
       pg_temp.inv_h('inv:answer:' || i.k || ':' || c.n), i.submit_at + pg_temp.inv_ms(c.at_ms), c.dur
  FROM x_inv i
 CROSS JOIN LATERAL (VALUES (1, 'QUERY', 'NOT_FOUND', 50, 3),
                            (2, 'SUBMIT', 'ACCEPTED', 62, 140 + pg_temp.pick('inv:submit-ms:' || i.k, 0, 90)),
                            (3, 'QUERY', 'PENDING', 330, 2)) AS c (n, op, outcome, at_ms, dur)
 WHERE i.type = 'ORIGINAL' AND i.submit_at IS NOT NULL;

-- Who is talking to the provider about it, and when it was last sent: claimed, sent, given up.
INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at, holder, held_until)
SELECT i.id, i.id || '-1', i.submit_at + pg_temp.inv_ms(60), NULL, i.submit_at + pg_temp.inv_ms(345)
  FROM x_inv i WHERE i.type = 'ORIGINAL' AND i.submit_at IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The stuck one: issued at the sandbox, but each reconciliation failed to store its XML and PDF (the object storage was
-- down) and left it SUBMITTING (EInvoiceIssuance: StorageException) - asked and downloaded again every 41 s. The
-- 15-minute sweep told the accountants once it had waited 30 minutes (EInvoiceWorkQueues.announceStuck).
INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
                                              http_status, error_code, request_hash, response_hash, called_at,
                                              duration_ms)
SELECT pg_temp.sid('inv:call:' || i.k || ':' || (2 + 2 * r.n + c.n)), i.id, 'SANDBOX', i.id || '-1', c.op, c.outcome,
       NULL, NULL, NULL,
       pg_temp.inv_h(CASE c.op WHEN 'QUERY' THEN 'inv:result:' ELSE 'inv:files:' END || i.k),
       i.submit_at + make_interval(secs => 30 + (r.n - 1) * 41) + pg_temp.inv_ms(c.at_ms), c.dur
  FROM x_inv i
 CROSS JOIN generate_series(1, 59) AS r (n)
 CROSS JOIN LATERAL (VALUES (1, 'QUERY', 'ISSUED', 0, 3), (2, 'DOWNLOAD', 'DOWNLOADED', 6, 5)) AS c (n, op, outcome, at_ms, dur)
 WHERE i.role = 'stuck';

UPDATE invoicing.einvoice_sending s
   SET held_until = i.submit_at + make_interval(secs => 30 + 58 * 41) + pg_temp.inv_ms(10240)
  FROM x_inv i WHERE s.einvoice_id = i.id AND i.role = 'stuck';

INSERT INTO invoicing.einvoice_stuck_notice (einvoice_id, transaction_id, notified_at)
SELECT i.id, i.id || '-1', i.submit_at + interval '31 minutes 12 seconds'
  FROM x_inv i WHERE i.role = 'stuck';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The reconciliation finds them signed: asked (ISSUED), the XML and PDF downloaded and stored, the invoice issued.
INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
                                              http_status, error_code, request_hash, response_hash, called_at,
                                              duration_ms)
SELECT pg_temp.sid('inv:call:' || i.k || ':final:' || c.n), i.id, 'SANDBOX', i.id || '-1', c.op, c.outcome, NULL,
       NULL, NULL, pg_temp.inv_h(CASE c.op WHEN 'QUERY' THEN 'inv:result:' ELSE 'inv:files:' END || i.k),
       i.job_at + pg_temp.inv_ms(c.at_ms), c.dur
  FROM x_inv i
 CROSS JOIN LATERAL (VALUES (1, 'QUERY', 'ISSUED', 0, 3), (2, 'DOWNLOAD', 'DOWNLOADED', 6, 5)) AS c (n, op, outcome, at_ms, dur)
 WHERE i.type = 'ORIGINAL' AND i.submit_at IS NOT NULL;

UPDATE invoicing.einvoice e
   SET status = 'ISSUED', invoice_number = i.num, tax_authority_code = s.tax_authority_code,
       lookup_code = s.lookup_code, signed_at = s.ready_at,
       xml_file_key = 'einvoices/showcase/1' || i.symbol || '-' || lpad(i.num::text, 8, '0') || '.xml',
       pdf_file_key = 'einvoices/showcase/1' || i.symbol || '-' || lpad(i.num::text, 8, '0') || '.pdf',
       issued_at = i.job_at + pg_temp.inv_ms(120), version = e.version + 1,
       last_modified_at = i.job_at + pg_temp.inv_ms(120), last_modified_by = 'system'
  FROM x_inv i JOIN invoicing.sandbox_issuance s ON s.transaction_id = i.id || '-1'
 WHERE e.id = i.id AND i.type = 'ORIGINAL' AND i.submit_at IS NOT NULL;

UPDATE invoicing.einvoice_sending s
   SET held_until = i.job_at + pg_temp.inv_ms(130)
  FROM x_inv i WHERE s.einvoice_id = i.id AND i.type = 'ORIGINAL' AND i.submit_at IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 70.4 Corrections (EInvoiceCorrections, TT 91 Điều 10) and the notice of an error in the buyer's address
-- ---------------------------------------------------------------------------------------------
-- The replacement: the invoice as it should have been - the same lines, the buyer's right tax code.
INSERT INTO invoicing.einvoice (id, code, type, status, adjusted, order_id, order_number, contract_number, shipment_id,
                                shipment_code, delivery_batch_id, customer_id, original_einvoice_id, attempt,
                                issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                tax_total, grand_total, amount_in_words, adjustment_reason, correction_basis, version,
                                created_at, created_by)
SELECT r.id, r.code, 'REPLACEMENT', 'DRAFT', FALSE, r.order_id, r.order_number, r.contract_number, r.shipment_id,
       r.shipment_code, r.batch_id, r.customer_id, o.id, 0, r.issue_date, pg_temp.inv_due(r.issue_date), r.seller_name,
       r.seller_tax, r.seller_address, r.buyer_name, r.buyer_tax, r.buyer_address, r.buyer_email, 'VND',
       'BANK_TRANSFER', e.subtotal, e.tax_total, e.grand_total, pg_temp.inv_words(e.grand_total), r.reason, 'ERROR', 0,
       r.created_at, r.created_by
  FROM x_inv r JOIN x_inv o ON o.k = r.orig_k JOIN invoicing.einvoice e ON e.id = o.id
 WHERE r.type = 'REPLACEMENT';

INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name, unit,
                                     quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
SELECT pg_temp.sid('inv:line:replacement:' || l.id), r.id, l.line_no, l.kind, l.fee_kind, l.order_line_id, l.sku,
       l.item_name, l.unit, l.quantity, l.units_per_carton, l.unit_price, l.tax_rate, l.amount, l.tax_amount
  FROM x_inv r JOIN x_inv o ON o.k = r.orig_k JOIN invoicing.einvoice_line l ON l.einvoice_id = o.id
 WHERE r.type = 'REPLACEMENT';

-- The trade discount: on each goods line of the newest invoice, so much off a carton that the whole reduces the three
-- invoices' goods by 2% (whole dong a carton). Planned first: the draft's totals are its lines'.
CREATE TEMP TABLE x_inv_disc ON COMMIT DROP AS
SELECT pg_temp.sid('inv:line:discount:' || l.id) AS id, a.id AS einvoice_id, l.line_no, l.kind, l.order_line_id, l.sku,
       'Chiết khấu thương mại 2% - ' || l.item_name AS item_name, l.unit, -l.quantity AS quantity, l.units_per_carton,
       x.price AS unit_price, l.tax_rate, -l.quantity * x.price AS amount,
       round(-l.quantity * x.price * l.tax_rate / 100, 0) AS tax_amount
  FROM x_inv a
  JOIN x_inv o ON o.k = a.orig_k
  JOIN invoicing.einvoice_line l ON l.einvoice_id = o.id AND l.kind = 'GOODS'
 CROSS JOIN LATERAL (SELECT round(l.unit_price * 0.02
                                  * (SELECT sum(g.amount) FROM invoicing.einvoice_line g
                                       JOIN x_inv gi ON gi.id = g.einvoice_id
                                       JOIN x_inv_pick p ON p.key = gi.k
                                      WHERE g.kind = 'GOODS' AND p.story IN ('discounted', 'listed1', 'listed2'))
                                  / (SELECT sum(g.amount) FROM invoicing.einvoice_line g
                                      WHERE g.einvoice_id = o.id AND g.kind = 'GOODS'), 0) AS price) x
 WHERE a.basis = 'TRADE_DISCOUNT';

INSERT INTO invoicing.einvoice (id, code, type, status, adjusted, order_id, order_number, contract_number, shipment_id,
                                shipment_code, delivery_batch_id, customer_id, original_einvoice_id, attempt,
                                issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                tax_total, grand_total, amount_in_words, adjustment_reason, correction_basis, version,
                                created_at, created_by)
SELECT a.id, a.code, 'ADJUSTMENT', 'DRAFT', FALSE, a.order_id, a.order_number, a.contract_number, a.shipment_id,
       a.shipment_code, a.batch_id, a.customer_id, o.id, 0, a.issue_date, pg_temp.inv_due(a.issue_date), a.seller_name,
       a.seller_tax, a.seller_address, a.buyer_name, a.buyer_tax, a.buyer_address, a.buyer_email, 'VND',
       'BANK_TRANSFER', t.net, t.tax, t.net + t.tax, pg_temp.inv_words(t.net + t.tax), a.reason, 'TRADE_DISCOUNT', 0,
       a.created_at, a.created_by
  FROM x_inv a
  JOIN x_inv o ON o.k = a.orig_k
  JOIN (SELECT einvoice_id, sum(amount) AS net, sum(tax_amount) AS tax FROM x_inv_disc GROUP BY einvoice_id) t
    ON t.einvoice_id = a.id
 WHERE a.basis = 'TRADE_DISCOUNT';

INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name, unit,
                                     quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
SELECT id, einvoice_id, line_no, kind, NULL, order_line_id, sku, item_name, unit, quantity, units_per_carton,
       unit_price, tax_rate, amount, tax_amount
  FROM x_inv_disc;

-- The other invoices of the buyer it covers, written with the draft.
INSERT INTO invoicing.einvoice_adjustment_listing (einvoice_id, listed_einvoice_id)
SELECT a.id, l.id
  FROM x_inv a
  CROSS JOIN x_inv l
  JOIN x_inv_pick p ON p.key = l.k AND p.story IN ('listed1', 'listed2')
 WHERE a.basis = 'TRADE_DISCOUNT';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The written agreement with the buyer, signed and attached to the replacement before it is sent (TT 91 Điều 10
-- khoản 1 điểm b).
UPDATE invoicing.einvoice e
   SET agreement_file_key = 'einvoices/showcase/bien-ban-thoa-thuan-' || o.code || '.pdf',
       agreement_number = 'BBTT-' || to_char(r.issue_date, 'YYYYMMDD') || '/' || x.cust,
       agreement_date = r.issue_date, version = e.version + 1,
       last_modified_at = pg_temp.inv_ts(r.issue_date, '10:15'), last_modified_by = pg_temp.staff('ketoan')::text
  FROM x_inv r JOIN x_inv o ON o.k = r.orig_k JOIN x_inv_o x ON x.key = o.k
 WHERE e.id = r.id AND r.type = 'REPLACEMENT';

-- The notice that an issued invoice names the buyer's address wrongly (04/SS-HĐĐT): recorded, then sent; the sandbox
-- answers as the tax authority's 01/TB-SSĐT would, at once.
INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, form_code, subject, reason, correct_buyer_name,
                                             correct_buyer_address, status, recorded_by, created_at, created_by)
SELECT pg_temp.sid('inv:notice:' || i.k), i.id, '04/SS-HDDT', 'BUYER_NAME_ADDRESS',
       'Ghi sai địa chỉ người mua: hóa đơn ghi địa chỉ trụ sở cũ (' || i.buyer_address
       || '); người mua đã đăng ký thay đổi trụ sở trước ngày lập hóa đơn',
       NULL, lp.registered_address, 'DRAFT', pg_temp.staff('ketoan'),
       pg_temp.inv_ts(pg_temp.inv_wd(i.issue_date + 6), '14:20'), pg_temp.staff('ketoan')::text
  FROM x_inv i
  JOIN x_inv_pick p ON p.key = i.k AND p.story = 'notice'
  JOIN customer.customer_legal_profile lp ON lp.customer_id = i.customer_id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
                                              http_status, error_code, request_hash, response_hash, called_at,
                                              duration_ms)
SELECT pg_temp.sid('inv:call:notice:' || n.id), n.einvoice_id, 'SANDBOX', n.id::text, 'NOTICE', 'ACCEPTED', NULL, NULL,
       pg_temp.inv_h('inv:notice-document:' || n.id), pg_temp.inv_h('inv:notice-receipt:' || n.id),
       n.created_at + pg_temp.inv_ms(180), 4
  FROM invoicing.einvoice_error_notice n
  JOIN x_inv i ON i.id = n.einvoice_id;

UPDATE invoicing.einvoice_error_notice n
   SET status = 'ACCEPTED', sent_at = n.created_at + pg_temp.inv_ms(190),
       response_code = 'SBX-TBSS-' || upper(left(pg_temp.inv_h(n.id::text), 16)),
       response_message = 'Sandbox: notice received (simulated 01/TB-SSDT; no legal value)',
       responded_at = n.created_at + pg_temp.inv_ms(190), version = n.version + 1,
       last_modified_at = n.created_at + pg_temp.inv_ms(190)
  FROM x_inv i
 WHERE i.id = n.einvoice_id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The returns' adjustments (EInvoiceCorrections.drawUpForReturn): each goods line of the shipment's invoice the return
-- refunded, reduced by the pieces refunded - in whole cartons at the carton's price, or in pieces at the piece's.
CREATE TEMP TABLE x_inv_ret ON COMMIT DROP AS
SELECT pg_temp.sid('inv:line:return:' || a.k || ':' || l.id) AS id, a.id AS einvoice_id,
       row_number() OVER (PARTITION BY a.id ORDER BY l.line_no)::int AS line_no, l.order_line_id, l.sku, l.item_name,
       CASE WHEN x.take % l.units_per_carton = 0 THEN l.unit ELSE 'Cái' END AS unit,
       CASE WHEN x.take % l.units_per_carton = 0 THEN -(x.take / l.units_per_carton) ELSE -x.take END AS quantity,
       CASE WHEN x.take % l.units_per_carton = 0 THEN l.units_per_carton END AS units_per_carton,
       CASE WHEN x.take % l.units_per_carton = 0 THEN l.unit_price ELSE l.unit_price / l.units_per_carton END
           AS unit_price, l.tax_rate
  FROM x_inv a
  JOIN x_inv o ON o.k = a.orig_k
  JOIN invoicing.einvoice_line l ON l.einvoice_id = o.id AND l.kind = 'GOODS'
 CROSS JOIN LATERAL (SELECT least(coalesce(sum(rl.refund_quantity), 0), l.quantity * l.units_per_carton)::int AS take
                       FROM ordering.return_request_line rl
                      WHERE rl.return_request_id = a.return_request_id AND rl.order_line_id = l.order_line_id) x
 WHERE a.basis = 'RETURN' AND x.take > 0;

INSERT INTO invoicing.einvoice (id, code, type, status, adjusted, order_id, order_number, contract_number, shipment_id,
                                shipment_code, delivery_batch_id, customer_id, original_einvoice_id, attempt,
                                issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                tax_total, grand_total, amount_in_words, adjustment_reason, correction_basis,
                                return_request_id, version, created_at, created_by)
SELECT a.id, a.code, 'ADJUSTMENT', 'DRAFT', FALSE, a.order_id, a.order_number, a.contract_number, a.shipment_id,
       a.shipment_code, a.batch_id, a.customer_id, o.id, 0, a.issue_date, pg_temp.inv_due(a.issue_date), a.seller_name,
       a.seller_tax, a.seller_address, a.buyer_name, a.buyer_tax, a.buyer_address, a.buyer_email, 'VND',
       'BANK_TRANSFER', t.net, t.tax, t.net + t.tax, pg_temp.inv_words(t.net + t.tax), a.reason, 'RETURN',
       a.return_request_id, 0, a.created_at, a.created_by
  FROM x_inv a
  JOIN x_inv o ON o.k = a.orig_k
  JOIN (SELECT einvoice_id, sum(quantity * unit_price) AS net,
               sum(round(quantity * unit_price * tax_rate / 100, 0)) AS tax
          FROM x_inv_ret GROUP BY einvoice_id) t ON t.einvoice_id = a.id
 WHERE a.basis = 'RETURN';

INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name, unit,
                                     quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
SELECT id, einvoice_id, line_no, 'GOODS', NULL, order_line_id, sku, item_name, unit, quantity, units_per_carton,
       unit_price, tax_rate, quantity * unit_price, round(quantity * unit_price * tax_rate / 100, 0)
  FROM x_inv_ret;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Announced the midnight before the next working day: those still waiting then (sent that morning, or left open).
UPDATE invoicing.einvoice e
   SET due_warned_at = i.warned_at, version = e.version + 1, last_modified_at = i.warned_at, last_modified_by = 'system'
  FROM x_inv i
 WHERE e.id = i.id AND i.basis = 'RETURN' AND i.warned_at IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The draft left open is past its deadline: reminded daily, the last time this night.
UPDATE invoicing.einvoice e
   SET overdue_warned_at = pg_temp.at(0, '00:22') + make_interval(secs => pg_temp.pick('inv:remind:' || i.k, 0, 40))
  FROM x_inv i
 WHERE e.id = i.id AND i.basis = 'RETURN' AND i.role = 'open' AND e.issue_due_at <= pg_temp.at(0, '00:22')
   AND i.warned_at + interval '1 day' <= pg_temp.at(0, '00:22');

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The corrections sent, through the provider their original was issued by; the sandbox takes each in as a
-- correction of the invoice it names.
UPDATE invoicing.einvoice e
   SET status = 'SUBMITTING', provider = 'SANDBOX', template_code = '1', symbol = i.symbol, attempt = 1,
       transaction_id = i.id || '-1', submitted_at = i.submit_at, submitted_by = pg_temp.staff('ketoan'),
       version = e.version + 1, last_modified_at = i.submit_at, last_modified_by = pg_temp.staff('ketoan')::text
  FROM x_inv i
 WHERE e.id = i.id AND i.type <> 'ORIGINAL' AND i.submit_at IS NOT NULL;

INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, invoice_number,
                                        tax_authority_code, lookup_code, rejection_code, rejection_message,
                                        xml_document, pdf_document, request_hash, received_at, ready_at, kind,
                                        original_template_code, original_symbol, original_invoice_number)
SELECT e.transaction_id, 'ISSUED', '1', i.symbol, i.num,
       'SBX' || substr(upper(pg_temp.inv_h(e.transaction_id)), 1, 31),
       'SBX' || substr(upper(pg_temp.inv_h(e.transaction_id)), 32, 10), NULL, NULL,
       pg_temp.inv_xml(e.id, i.num, 'SBX' || substr(upper(pg_temp.inv_h(e.transaction_id)), 1, 31),
                       'SBX' || substr(upper(pg_temp.inv_h(e.transaction_id)), 32, 10),
                       i.submit_at + pg_temp.inv_ms(5070)),
       pg_temp.inv_pdf('1' || i.symbol || ' No ' || lpad(i.num::text, 8, '0') || ' - ' || i.code),
       pg_temp.inv_h('inv:document:' || e.transaction_id), i.submit_at + pg_temp.inv_ms(70),
       i.submit_at + pg_temp.inv_ms(5070), i.type, o.template_code, o.symbol, o.invoice_number
  FROM x_inv i
  JOIN invoicing.einvoice e ON e.id = i.id
  JOIN invoicing.einvoice o ON o.id = e.original_einvoice_id
 WHERE i.type <> 'ORIGINAL' AND i.submit_at IS NOT NULL;

INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
                                              http_status, error_code, request_hash, response_hash, called_at,
                                              duration_ms)
SELECT pg_temp.sid('inv:call:' || i.k || ':' || c.n), i.id, 'SANDBOX', i.id || '-1', c.op, c.outcome, NULL, NULL,
       CASE WHEN c.op = 'SUBMIT' THEN pg_temp.inv_h('inv:document:' || i.id || '-1') END,
       pg_temp.inv_h('inv:answer:' || i.k || ':' || c.n), i.submit_at + pg_temp.inv_ms(c.at_ms), c.dur
  FROM x_inv i
 CROSS JOIN LATERAL (VALUES (1, 'QUERY', 'NOT_FOUND', 50, 3),
                            (2, 'SUBMIT', 'ACCEPTED', 62, 140 + pg_temp.pick('inv:submit-ms:' || i.k, 0, 90)),
                            (3, 'QUERY', 'PENDING', 330, 2)) AS c (n, op, outcome, at_ms, dur)
 WHERE i.type <> 'ORIGINAL' AND i.submit_at IS NOT NULL;

INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at, holder, held_until)
SELECT i.id, i.id || '-1', i.submit_at + pg_temp.inv_ms(60), NULL, i.submit_at + pg_temp.inv_ms(345)
  FROM x_inv i WHERE i.type <> 'ORIGINAL' AND i.submit_at IS NOT NULL;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Issued (EInvoiceIssuanceSteps.issued): the invoices corrected are marked first - REPLACED, or "has an adjustment" on
-- the adjusted one and on each it lists - then the correction itself.
UPDATE invoicing.einvoice e
   SET status = 'REPLACED', version = e.version + 1, last_modified_at = r.job_at + pg_temp.inv_ms(100),
       last_modified_by = 'system'
  FROM x_inv r JOIN x_inv o ON o.k = r.orig_k
 WHERE e.id = o.id AND r.type = 'REPLACEMENT';

UPDATE invoicing.einvoice e
   SET adjusted = TRUE, version = e.version + 1, last_modified_at = a.job_at + pg_temp.inv_ms(100),
       last_modified_by = 'system'
  FROM x_inv a
 WHERE a.type = 'ADJUSTMENT' AND a.submit_at IS NOT NULL
   AND (e.id = (SELECT o.id FROM x_inv o WHERE o.k = a.orig_k)
        OR e.id IN (SELECT l.listed_einvoice_id FROM invoicing.einvoice_adjustment_listing l WHERE l.einvoice_id = a.id));

INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
                                              http_status, error_code, request_hash, response_hash, called_at,
                                              duration_ms)
SELECT pg_temp.sid('inv:call:' || i.k || ':final:' || c.n), i.id, 'SANDBOX', i.id || '-1', c.op, c.outcome, NULL,
       NULL, NULL, pg_temp.inv_h(CASE c.op WHEN 'QUERY' THEN 'inv:result:' ELSE 'inv:files:' END || i.k),
       i.job_at + pg_temp.inv_ms(c.at_ms), c.dur
  FROM x_inv i
 CROSS JOIN LATERAL (VALUES (1, 'QUERY', 'ISSUED', 0, 3), (2, 'DOWNLOAD', 'DOWNLOADED', 6, 5)) AS c (n, op, outcome, at_ms, dur)
 WHERE i.type <> 'ORIGINAL' AND i.submit_at IS NOT NULL;

UPDATE invoicing.einvoice e
   SET status = 'ISSUED', invoice_number = i.num, tax_authority_code = s.tax_authority_code,
       lookup_code = s.lookup_code, signed_at = s.ready_at,
       xml_file_key = 'einvoices/showcase/1' || i.symbol || '-' || lpad(i.num::text, 8, '0') || '.xml',
       pdf_file_key = 'einvoices/showcase/1' || i.symbol || '-' || lpad(i.num::text, 8, '0') || '.pdf',
       issued_at = i.job_at + pg_temp.inv_ms(120), version = e.version + 1,
       last_modified_at = i.job_at + pg_temp.inv_ms(120), last_modified_by = 'system'
  FROM x_inv i JOIN invoicing.sandbox_issuance s ON s.transaction_id = i.id || '-1'
 WHERE e.id = i.id AND i.type <> 'ORIGINAL' AND i.submit_at IS NOT NULL;

UPDATE invoicing.einvoice_sending s
   SET held_until = i.job_at + pg_temp.inv_ms(130)
  FROM x_inv i WHERE s.einvoice_id = i.id AND i.type <> 'ORIGINAL' AND i.submit_at IS NOT NULL;

-- The sandbox's last number under each symbol (SandboxLedger.nextNumber).
INSERT INTO invoicing.sandbox_number (template_code, symbol, last_number)
SELECT template_code, symbol, max(invoice_number) FROM invoicing.sandbox_issuance GROUP BY template_code, symbol;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

DROP FUNCTION pg_temp.inv_pdf(TEXT);
DROP FUNCTION pg_temp.inv_xml(UUID, INTEGER, TEXT, TEXT, TIMESTAMPTZ);
DROP FUNCTION pg_temp.inv_ms(INTEGER);
DROP FUNCTION pg_temp.inv_h(TEXT);
DROP FUNCTION pg_temp.inv_due(DATE);
DROP FUNCTION pg_temp.inv_ts(DATE, TEXT);
DROP FUNCTION pg_temp.inv_wd(DATE);
DROP FUNCTION pg_temp.inv_next_wd(DATE);
DROP FUNCTION pg_temp.inv_words(NUMERIC);
DROP FUNCTION pg_temp.inv_number(NUMERIC);
DROP FUNCTION pg_temp.inv_below_billion(BIGINT, BOOLEAN);
DROP FUNCTION pg_temp.inv_three(INTEGER, BOOLEAN);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- =============================================================================================
-- 80. Chat, notifications and the audit log (group cna)
--
-- What it adds, read-only on every other table:
--   * chat: Sales' saved replies; about twenty conversations between customers and their Sales, opened from a quote's
--     or an order's page or about nothing in particular, played step by step as the chat module plays them
--     (Conversation.open/route, accept*Message, changeStatus, claim, rate) with the jobs of ChatLifecycleJobs applied
--     up to the start of today (SLA alerts, auto-close, close after the reopen window); internal notes with mentions,
--     attachments (placeholder keys, no object), read markers as ReadMarkersAdapter writes them. No CARD or SYSTEM
--     message: the live application would also have posted document cards (quote, contract...) from its events.
--   * notification.user_notification: what the inbox listeners post for the base's own events, with their titles,
--     links and dedupe keys (QUOTE, PRODUCTION_ORDER, ORDER_SHIPPING, PAYMENT_OVERDUE, SALES_FOLLOW_UP) and what the
--     chat posts (CHAT, CHAT_SLA); the last 30 days, the older ones mostly read.
--   * notification.delivery_log: the two paths that log a mail - the producer's production order (RecordedDelivery)
--     and the customer mails (CustomerEmailLog: order.placed, shipment.handed-over, shipment.delivered, chat.reply).
--     Written as the SMTP transport leaves them (provider SMTP, no provider message id): every seeded address is under
--     the reserved .example domain, which the Resend transport refuses (RECIPIENT_RESERVED_DOMAIN). Staff notices
--     write no log row (NotificationSender.sendToStaff).
--   * notification.preference: a few rows; no code reads or writes the table (its repository is a starter stub).
--   * notification.template: one row per template code the delivery log names (tools/db/orphan_check.sql counts
--     delivery_log.template_code against it), its subject and lines copied verbatim from messages_vi.properties. No code
--     reads the table (NotificationTemplateJpaRepository is a starter stub): mail is laid out from
--     templates/email/customer.html and the i18n bundles (EmailTemplates), so these rows override nothing.
--   * platform.audit_log: rows as AuditAspect/AuditWriter write them for @Auditable methods only, for a sample of the
--     orders (one in ten) inside the 180-day retention, plus sign-ins, warehouse grants and two account changes.
-- created_by follows the application: the acting user's username, 'system' for listeners, jobs and async work.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 80.0 Helpers of this section (dropped at its end)
-- ---------------------------------------------------------------------------------------------
-- BusinessHours.nextOpening: Monday to Saturday, 08:00 to 17:30, Asia/Ho_Chi_Minh (ChatProperties defaults).
CREATE FUNCTION pg_temp.cna_bopen(t TIMESTAMPTZ) RETURNS TIMESTAMPTZ LANGUAGE plpgsql STABLE AS $$
DECLARE
    local TIMESTAMP := t AT TIME ZONE 'Asia/Ho_Chi_Minh';
    d DATE;
BEGIN
    FOR i IN 0..7 LOOP
        d := local::date + i;
        CONTINUE WHEN extract(isodow FROM d) = 7;
        IF local < d + TIME '08:00' THEN
            RETURN (d + TIME '08:00') AT TIME ZONE 'Asia/Ho_Chi_Minh';
        END IF;
        IF local < d + TIME '17:30' THEN
            RETURN t;
        END IF;
    END LOOP;
    RAISE EXCEPTION 'no working day within a week';
END $$;

-- BusinessHours.plus: minutes of business time after t.
CREATE FUNCTION pg_temp.cna_bplus(t TIMESTAMPTZ, mins INTEGER) RETURNS TIMESTAMPTZ LANGUAGE plpgsql STABLE AS $$
DECLARE
    cursor_at TIMESTAMPTZ := pg_temp.cna_bopen(t);
    left_over INTERVAL := mins * INTERVAL '1 minute';
    close_at TIMESTAMPTZ;
BEGIN
    LOOP
        close_at := ((cursor_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + TIME '17:30') AT TIME ZONE 'Asia/Ho_Chi_Minh';
        IF left_over <= close_at - cursor_at THEN
            RETURN cursor_at + left_over;
        END IF;
        left_over := left_over - (close_at - cursor_at);
        cursor_at := pg_temp.cna_bopen(close_at);
    END LOOP;
END $$;

-- IdentityServiceImpl.notificationRecipientsHolding (UserJpaRepository.findStaffWithRolesFor): ACTIVE staff holding a
-- role that grants the permission, not customers, whose role scope is ALL or who are assigned to the warehouse.
CREATE FUNCTION pg_temp.cna_holders(perm TEXT, wh UUID) RETURNS SETOF UUID LANGUAGE sql STABLE AS $$
SELECT u.id FROM identity.app_user u
 WHERE u.status = 'ACTIVE'
   AND EXISTS (SELECT 1 FROM identity.user_role ur
                 JOIN identity.role_permission rp ON rp.role_id = ur.role_id
                 JOIN identity.permission p ON p.id = rp.permission_id
                WHERE ur.user_id = u.id AND p.resource || ':' || p.action = perm)
   AND NOT EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                    WHERE ur.user_id = u.id AND r.code = 'CUSTOMER')
   AND (EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                 WHERE ur.user_id = u.id AND r.data_scope = 'ALL')
        OR (wh IS NOT NULL AND EXISTS (SELECT 1 FROM identity.user_warehouse w
                                        WHERE w.user_id = u.id AND w.warehouse_id = wh)))
$$;

-- Instant.toString of a whole-second instant.
CREATE FUNCTION pg_temp.cna_iso(t TIMESTAMPTZ) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT to_char(t AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') $$;

-- NumberFormat.getIntegerInstance(vi): dots between thousands.
CREATE FUNCTION pg_temp.cna_vnd(n NUMERIC) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT replace(to_char(round(n), 'FM999,999,999,999,990'), ',', '.') $$;

CREATE FUNCTION pg_temp.cna_uname(id UUID) RETURNS TEXT LANGUAGE sql STABLE AS
$$ SELECT username FROM identity.app_user WHERE id = $1 $$;

-- ---------------------------------------------------------------------------------------------
-- 80.1 Saved replies (16 §4.6): shared ones set by the chat supervisor, a few of an agent's own, one archived
-- ---------------------------------------------------------------------------------------------
INSERT INTO chat.canned_response (id, owner_id, title, body, shortcut, category, archived_at, version, created_at,
                                  created_by, last_modified_at, last_modified_by)
SELECT pg_temp.sid('cna:canned:' || r.shortcut), CASE WHEN r.owner IS NOT NULL THEN pg_temp.staff(r.owner) END, r.title,
       r.body, r.shortcut, r.category,
       CASE WHEN r.archived IS NOT NULL THEN pg_temp.at(r.archived, '17:10') END,
       CASE WHEN r.archived IS NOT NULL OR r.edited IS NOT NULL THEN 1 ELSE 0 END,
       pg_temp.at(r.created, '09:30'), coalesce(r.owner, 'truongkd'),
       CASE WHEN r.archived IS NOT NULL THEN pg_temp.at(r.archived, '17:10')
            WHEN r.edited IS NOT NULL THEN pg_temp.at(r.edited, '11:00') END,
       CASE WHEN r.archived IS NOT NULL OR r.edited IS NOT NULL THEN coalesce(r.owner, 'truongkd') END
  FROM (VALUES
    (NULL, 'Chào hỏi', 'Chào {customerName}, em là {agentName} bên Bao bì StockFlow. Em có thể hỗ trợ gì cho anh/chị ạ?',
     'chao', 'Chung', 170, NULL, NULL),
    (NULL, 'Báo giá đã gửi',
     'Dạ em đã gửi báo giá {quoteCode} trên cổng khách hàng. Anh/chị xem và bấm chấp nhận, hoặc gửi yêu cầu chỉnh sửa giúp em nhé.',
     'baogia', 'Báo giá', 170, 96, NULL),
    (NULL, 'Hợp đồng chờ ký',
     'Hợp đồng {contractCode} đã được gửi. Anh/chị ký, đóng dấu và gửi lại bản scan giúp em để bên em gửi đơn sản xuất cho xưởng in ạ.',
     'hopdong', 'Hợp đồng', 170, NULL, NULL),
    (NULL, 'Nhắc đặt cọc',
     'Đơn {orderCode} đang chờ khoản đặt cọc khi ký hợp đồng. Anh/chị chuyển khoản theo thông tin trên hợp đồng, nội dung ghi số hợp đồng {contractCode} giúp em ạ.',
     'datcoc', 'Thanh toán', 168, NULL, NULL),
    (NULL, 'Tiến độ sản xuất',
     'Đơn sản xuất {productionOrderCode} của đơn {orderCode} đang được xưởng in thực hiện. Khi hàng nhập kho em báo lịch giao ngay ạ.',
     'tiendo', 'Sản xuất', 168, NULL, NULL),
    (NULL, 'Lịch giao hàng',
     'Đơn {orderCode} được giao nguyên chuyến xe tải; tài xế gọi trước khi tới khoảng 30 phút. Người nhận ký biên bản giao hàng giúp em nhé.',
     'giaohang', 'Giao hàng', 168, NULL, NULL),
    (NULL, 'Kết thúc hội thoại',
     'Cảm ơn anh/chị đã liên hệ. Nếu cần thêm thông tin anh/chị cứ nhắn lại trong hội thoại này ạ.', 'camon', 'Chung',
     165, NULL, NULL),
    ('sales1', 'Xin file logo',
     'Anh/chị gửi giúp em file logo dạng vector (AI, PDF hoặc SVG) để bên em lên mẫu in đúng màu ạ.', 'filelogo',
     'Thiết kế', 150, NULL, NULL),
    ('sales2', 'Hẹn gọi lại', 'Dạ em xin phép gọi lại cho anh/chị trong 15 phút nữa để trao đổi chi tiết ạ.', 'goilai',
     NULL, 140, NULL, NULL),
    ('sales2', 'Ưu đãi tháng 9',
     'Tháng 9 bên em giảm 5% phí làm bản in cho đơn từ 30 thùng trở lên ạ.', 'km9', 'Khuyến mãi', 120, NULL, 52),
    ('sf.sales', 'Đơn gấp', 'Đơn cần gấp trước 10 ngày em báo xưởng xếp lịch ưu tiên, anh/chị xác nhận số thùng giúp em ạ.',
     'dongap', 'Sản xuất', 90, NULL, NULL))
       AS r (owner, title, body, shortcut, category, created, edited, archived);

-- ---------------------------------------------------------------------------------------------
-- 80.2 Conversations: who, about what, when (16 §4.1). Each is anchored on one of the base's orders or quotes and
--      opened on a day of its timeline: Q quote sent (s+7), A accepted (s+6), C contract sent (s+3), D delivered or
--      handed over (d), X days before today.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_cna_tpl (key TEXT PRIMARY KEY, stage TEXT, dmin INTEGER, dmax INTEGER, smin INTEGER, smax INTEGER,
                             k INTEGER, ctx TEXT, opener TEXT, anchor TEXT, day_off INTEGER, hhmi TEXT, subject TEXT,
                             fresh BOOLEAN DEFAULT FALSE) ON COMMIT DROP;
INSERT INTO x_cna_tpl (key, stage, dmin, dmax, smin, smax, k, ctx, opener, anchor, day_off, hhmi, subject) VALUES
    ('C01', 'DELIVERED', 60, 80, NULL, NULL, 1, 'ORDER', 'C', 'D', 1, '09:20', 'Lịch giao hàng đơn {SO}'),
    ('C02', 'DELIVERED', 40, 60, NULL, NULL, 1, 'QUOTE', 'C', 'A', 0, '09:05', 'Hỏi về báo giá {QT}'),
    ('C03', 'DELIVERED', 25, 40, NULL, NULL, 2, NULL, 'A', 'D', 20, '14:00', 'Giới thiệu mẫu tô giấy kraft'),
    ('C04', 'DELIVERED', 25, 50, NULL, NULL, 3, 'ORDER', 'C', 'D', -1, '08:40', 'Thùng hàng bị móp khi nhận'),
    ('C05', 'DELIVERED', 15, 30, NULL, NULL, 1, 'ORDER', 'C', 'D', -3, '10:15', 'Thanh toán đợt sau giao hàng'),
    ('C06', 'DELIVERED', 55, 80, NULL, NULL, 2, 'ORDER', 'A', 'D', 4, '15:30', 'Xác nhận địa chỉ nhận hàng'),
    ('C07', 'DELIVERED', 35, 55, NULL, NULL, 2, 'ORDER', 'C', 'D', -1, '16:10', 'Mã thùng trên tem'),
    ('C09', 'DELIVERED', 20, 35, NULL, NULL, 3, 'QUOTE', 'C', 'A', 0, '10:30', 'Giao một hay hai đợt'),
    ('C10', 'DELIVERED', 10, 22, NULL, NULL, 1, 'ORDER', 'C', 'D', 2, '11:00', 'Giờ nhận hàng'),
    ('C11', 'DELIVERED', 40, 70, NULL, NULL, 3, 'ORDER', 'C', 'D', -5, '13:30', 'Đặt thêm hàng cùng mẫu'),
    ('C12', 'O_CANCELLED', NULL, NULL, 20, 75, 1, 'QUOTE', 'C', 'A', -2, '08:30', 'Dừng đơn từ báo giá {QT}'),
    ('R1', 'SHIPPED', NULL, NULL, NULL, NULL, 1, 'ORDER', 'C', 'D', 0, '10:30', 'Xe giao đơn {SO}'),
    ('R2', 'PENDING_CONTRACT', NULL, NULL, -2, 2, 1, 'ORDER', 'C', 'C', 0, '14:00', 'Điều khoản hợp đồng {HD}'),
    ('R3', 'IN_PRODUCTION', NULL, NULL, 11, 13, 1, 'ORDER', 'C', 'X', 6, '09:15', 'Tiến độ in đơn {SO}'),
    ('R4', 'DELIVERED', 30, 60, NULL, NULL, 4, NULL, 'A', 'X', 10, '15:00', 'Ly in hình Tết'),
    ('O1', 'Q_SENT', NULL, NULL, -6, -6, 1, 'QUOTE', 'C', 'Q', 0, '14:20', 'Thời hạn báo giá {QT}'),
    ('O2', 'PARTIAL', NULL, NULL, NULL, NULL, 1, 'ORDER', 'C', 'X', 1, '09:40', 'Giao trước phần hàng đã về'),
    ('O3', 'PENDING_PAYMENT', NULL, NULL, 4, 12, 1, 'ORDER', 'A', 'X', 2, '10:00', 'Đặt cọc hợp đồng {HD}'),
    ('O4', 'IN_PRODUCTION', NULL, NULL, 9, 13, 2, 'ORDER', 'C', 'X', 5, '16:00', 'Trễ tiến độ in đơn {SO}'),
    ('O6', 'READY', NULL, NULL, NULL, NULL, 1, 'ORDER', 'C', 'X', 1, '15:10', 'Người nhận hàng thay');
-- Two customers who never chatted before: nobody answers them yet, so the conversation waits in the queue (16 §4.1
-- step 2: no responsible Sales on the page, no recent agent).
INSERT INTO x_cna_tpl (key, stage, dmin, dmax, smin, smax, k, ctx, opener, anchor, day_off, hhmi, subject, fresh) VALUES
    ('C08', 'DELIVERED', 30, 60, NULL, NULL, 1, NULL, 'C', 'D', 6, '19:45', 'Hỏi ly giấy 2 lớp', TRUE),
    ('O5', 'DELIVERED', 10, 120, NULL, NULL, 1, NULL, 'C', 'X', 4, '10:00', 'Hỏi giá hộp bánh in logo', TRUE);

-- Each template takes the k-th order of its stage and window that no template before it took.
CREATE TEMP TABLE x_cna_conv (key TEXT, okey TEXT, ctx TEXT, opener TEXT, anchor TEXT, day_off INTEGER, hhmi TEXT,
                              subject TEXT, fresh BOOLEAN) ON COMMIT DROP;
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT * FROM x_cna_tpl WHERE NOT fresh ORDER BY key LOOP
        INSERT INTO x_cna_conv
        SELECT t.key, p.okey, t.ctx, t.opener, t.anchor, t.day_off, t.hhmi, t.subject, t.fresh
          FROM (SELECT o.key AS okey, row_number() OVER (ORDER BY o.seq) AS rn
                  FROM h_order o
                 WHERE o.stage = t.stage AND (t.dmin IS NULL OR o.d BETWEEN t.dmin AND t.dmax)
                   AND (t.smin IS NULL OR o.s BETWEEN t.smin AND t.smax)
                   AND o.key NOT IN (SELECT okey FROM x_cna_conv)) p
         WHERE p.rn = t.k;
    END LOOP;
END $$;

INSERT INTO x_cna_conv
SELECT t.key, p.okey, t.ctx, t.opener, t.anchor, t.day_off, t.hhmi, t.subject, t.fresh
  FROM x_cna_tpl t
  JOIN (SELECT o.key AS okey, row_number() OVER (ORDER BY o.seq) AS rn
          FROM h_order o
         WHERE o.stage = 'DELIVERED' AND o.d BETWEEN 30 AND 60
           AND o.cust NOT IN (SELECT h.cust FROM x_cna_conv x JOIN h_order h ON h.key = x.okey)) p ON p.rn = 1
 WHERE t.key = 'C08';

INSERT INTO x_cna_conv
SELECT t.key, p.okey, t.ctx, t.opener, t.anchor, t.day_off, t.hhmi, t.subject, t.fresh
  FROM x_cna_tpl t
  JOIN (SELECT o.key AS okey, row_number() OVER (ORDER BY o.seq DESC) AS rn
          FROM h_order o
         WHERE o.stage = 'DELIVERED' AND o.d BETWEEN 10 AND 120
           AND o.cust NOT IN (SELECT h.cust FROM x_cna_conv x JOIN h_order h ON h.key = x.okey)) p ON p.rn = 1
 WHERE t.key = 'O5';

ALTER TABLE x_cna_conv ADD COLUMN id UUID, ADD COLUMN customer_id UUID, ADD COLUMN cust_uid UUID,
    ADD COLUMN cust_user TEXT, ADD COLUMN sales TEXT, ADD COLUMN sales_uid UUID, ADD COLUMN order_id UUID,
    ADD COLUMN quote_id UUID, ADD COLUMN open_at TIMESTAMPTZ, ADD COLUMN mgr TEXT, ADD COLUMN vars JSONB;

UPDATE x_cna_conv x
   SET id = pg_temp.sid('cna:conversation:' || x.key), customer_id = o.customer_id,
       cust_uid = pg_temp.sid('user:' || o.cust_user), cust_user = o.cust_user, sales = o.sales,
       sales_uid = pg_temp.staff(o.sales), order_id = o.id, quote_id = o.quote_id, mgr = o.mgr,
       open_at = pg_temp.at(CASE x.anchor WHEN 'Q' THEN o.s + 7 WHEN 'A' THEN o.s + 6 WHEN 'C' THEN o.s + 3
                                          WHEN 'D' THEN o.d ELSE 0 END + x.day_off, x.hhmi),
       vars = jsonb_build_object(
           '{SO}', o.order_number, '{QT}', o.quote_number, '{HD}', o.contract_number,
           '{WIN}', to_char(o.window_start, 'DD/MM'),
           '{SIGN}', to_char(pg_temp.day(o.s), 'DD/MM/YYYY'), '{DEP}', pg_temp.cna_vnd(o.deposit),
           '{VALID}', (SELECT to_char(q.valid_until AT TIME ZONE 'Asia/Ho_Chi_Minh', 'DD/MM/YYYY')
                         FROM ordering.quote q WHERE q.id = o.quote_id),
           '{MGRNAME}', (SELECT full_name FROM identity.app_user WHERE username = o.mgr),
           '{PLATE}', (SELECT l.vehicle_plate FROM fulfillment.dispatch_load l WHERE l.id = pg_temp.sid('load:' || o.key)),
           '{DRV}', (SELECT l.driver_name FROM fulfillment.dispatch_load l WHERE l.id = pg_temp.sid('load:' || o.key)))
  FROM h_order o WHERE o.key = x.okey;

DO $$
BEGIN
    IF (SELECT count(*) FROM x_cna_conv) <> (SELECT count(*) FROM x_cna_tpl) THEN
        RAISE EXCEPTION 'cna: % conversation templates found no order: %', (SELECT count(*) FROM x_cna_tpl) -
            (SELECT count(*) FROM x_cna_conv),
            (SELECT string_agg(key, ',') FROM x_cna_tpl t WHERE NOT EXISTS (SELECT 1 FROM x_cna_conv c WHERE c.key = t.key));
    END IF;
    IF EXISTS (SELECT okey FROM x_cna_conv GROUP BY okey HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'cna: two conversations picked the same order';
    END IF;
    IF EXISTS (SELECT 1 FROM x_cna_conv WHERE open_at >= pg_temp.at(0, '00:00')) THEN
        RAISE EXCEPTION 'cna: a conversation would open today or later';
    END IF;
END $$;

-- The steps of each conversation, in order: delta minutes after the step before (biz: business minutes, as a
-- conversation opened after hours is taken up the next working morning). who: C the customer, A the Sales in charge.
CREATE TEMP TABLE x_cna_tstep (tkey TEXT, n INTEGER, delta INTEGER, biz BOOLEAN, kind TEXT, who TEXT, body TEXT,
                               mention TEXT, reason TEXT, file TEXT, ctype TEXT, size INTEGER, score INTEGER)
    ON COMMIT DROP;
INSERT INTO x_cna_tstep (tkey, n, delta, biz, kind, who, body, mention, reason, file, ctype, size, score) VALUES
    ('C01', 1, 0, FALSE, 'MSG', 'C', 'Chào chị, cho em hỏi đơn {SO} bao giờ xe tới kho bên em ạ? Bên em cần sắp người nhận hàng.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C01', 2, 6, FALSE, 'MSG', 'A', 'Chào anh, đơn {SO} đã xếp xe tải 2,5 tấn, sáng mai 8h xe rời kho, dự kiến khoảng 15h chiều mai tới nơi ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C01', 3, 9, FALSE, 'MSG', 'C', 'Dạ ok chị, mai bên em có người nhận từ 13h. Cảm ơn chị.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C01', 4, 3, FALSE, 'MSG', 'A', 'Dạ vâng, tài xế sẽ gọi trước khoảng 30 phút. Có gì anh nhắn em nhé.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C01', 5, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C01', 6, 40, FALSE, 'READ', 'C', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    ('C01', 7, 5, FALSE, 'CSAT', 'C', NULL, NULL, 'Phản hồi nhanh, thông tin rõ ràng.', NULL, NULL, NULL, 5),

    ('C02', 1, 0, FALSE, 'MSG', 'C', 'Chào em, báo giá {QT} chị xem rồi. Nếu in thêm logo ở mặt sau thì giá có đổi không em?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C02', 2, 4, FALSE, 'FILE', 'C', 'Đây là logo mặt sau chị muốn in thêm.', NULL, NULL, 'logo-mat-sau.png', 'image/png', 284512, NULL),
    ('C02', 3, 7, FALSE, 'MSG', 'A', 'Dạ chào chị, in thêm mặt sau là in 2 mặt, đơn giá tăng khoảng 120 đồng mỗi cái và thêm phí làm bản in. Em tính lại rồi báo chị trong sáng nay ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C02', 4, 29, FALSE, 'MSG', 'A', 'Chị ơi, nếu chị giữ in 1 mặt như báo giá thì đơn giá và phí bản in giữ nguyên ạ. Chị cân nhắc giúp em nhé.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C02', 5, 15, FALSE, 'MSG', 'C', 'Ok em, đợt này chị giữ 1 mặt như báo giá, chiều chị bấm chấp nhận luôn.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C02', 6, 3, FALSE, 'MSG', 'A', 'Dạ em cảm ơn chị. Chị chấp nhận xong em lên hợp đồng gửi chị ký ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C02', 7, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C02', 8, 240, FALSE, 'READ', 'C', NULL, NULL, NULL, NULL, NULL, NULL, NULL),

    ('C03', 1, 0, FALSE, 'MSG', 'A', 'Chào anh, bên em vừa có thêm mẫu tô giấy kraft 750ml nắp PP, in được logo 1 màu. Anh có cần em gửi báo giá thử không ạ?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C03', 2, 95, FALSE, 'MSG', 'C', 'Gửi anh xem thử 30 thùng nhé, giao cùng đợt với đơn ly đang làm thì tốt.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C03', 3, 15, FALSE, 'MSG', 'A', 'Dạ vâng, em lên báo giá trong chiều nay. Mẫu kraft cần duyệt file in riêng, em gửi kèm bản phối để anh xem trước ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C03', 4, 2, FALSE, 'STATUS', 'A', 'WAITING_ON_CUSTOMER', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C03', 5, 1330, FALSE, 'MSG', 'C', 'Anh nhận được rồi. Để anh bàn với bên quán rồi báo lại em.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C03', 6, 10, FALSE, 'MSG', 'A', 'Dạ vâng anh. Khi nào anh cần cứ nhắn em trong hội thoại này ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C03', 7, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C03', 8, 50, FALSE, 'CSAT', 'C', NULL, NULL, 'Tư vấn nhiệt tình, mong có bảng giá sẵn để xem nhanh hơn.', NULL, NULL, NULL, 4),

    ('C04', 1, 0, FALSE, 'MSG', 'C', 'Chào em, hôm qua nhận hàng đơn {SO} có 2 thùng bị móp góc, ly bên trong vẫn dùng được nhưng vài cây bị bẹp miệng.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C04', 2, 2, FALSE, 'FILE', 'C', 'Ảnh 2 thùng bị móp lúc nhận hàng.', NULL, NULL, 'thung-mop.jpg', 'image/jpeg', 1843200, NULL),
    ('C04', 3, 7, FALSE, 'MSG', 'A', 'Dạ em chào anh, em rất tiếc về sự cố này. Em đã chuyển ảnh cho bên kho kiểm tra, trong sáng nay em báo anh hướng xử lý ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C04', 4, 3, FALSE, 'NOTE', 'A', '{MGRNAME} kiểm tra giúp em đơn {SO}: khách báo 2 thùng móp góc khi nhận, có ảnh ở trên. Lúc xếp xe có ghi nhận gì không ạ?', 'MGR', NULL, NULL, NULL, NULL, NULL),
    ('C04', 5, 1, FALSE, 'STATUS', 'A', 'WAITING_ON_INTERNAL', NULL, 'Chờ kho xác nhận tình trạng thùng khi xếp xe', NULL, NULL, NULL, NULL),
    ('C04', 6, 57, FALSE, 'NOTE', 'A', 'Kho xác nhận lúc xếp xe các thùng còn nguyên, có ảnh tải hàng; khả năng va đập dọc đường. Đề xuất gửi bù 2 thùng ở đợt giao sau.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C04', 7, 10, FALSE, 'STATUS', 'A', 'ACTIVE', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C04', 8, 2, FALSE, 'MSG', 'A', 'Anh ơi, bên em xin gửi bù 2 thùng cùng mã ở đợt giao tới, không tính phí. Anh giữ lại 2 thùng móp giúp em để tài xế thu hồi nhé.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C04', 9, 38, FALSE, 'MSG', 'C', 'Ok em, vậy được. Cảm ơn em xử lý nhanh.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C04', 10, 3, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C04', 11, 2, FALSE, 'CSAT', 'C', NULL, NULL, NULL, NULL, NULL, NULL, 5),

    ('C05', 1, 0, FALSE, 'MSG', 'C', 'Chào em, đợt thanh toán sau giao hàng của đơn {SO} bên chị chuyển khoản vào tài khoản nào? Nội dung ghi thế nào để bên em đối soát?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C05', 2, 7, FALSE, 'MSG', 'A', 'Dạ chào chị, em nhờ kế toán xác nhận số còn phải thu rồi báo chị ngay ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C05', 3, 1, FALSE, 'NOTE', 'A', 'Chị Kế ơi, xác nhận giúp em số còn phải thu của hợp đồng {HD} để em báo khách chuyển khoản ạ.', 'ketoan', NULL, NULL, NULL, NULL, NULL),
    ('C05', 4, 1, FALSE, 'STATUS', 'A', 'WAITING_ON_INTERNAL', NULL, 'Chờ kế toán xác nhận công nợ', NULL, NULL, NULL, NULL),
    ('C05', 5, 41, FALSE, 'NOTE', 'A', 'Kế toán xác nhận: còn đợt thanh toán sau giao hàng theo hợp đồng, nội dung chuyển khoản ghi số hợp đồng.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C05', 6, 2, FALSE, 'STATUS', 'A', 'ACTIVE', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C05', 7, 1, FALSE, 'MSG', 'A', 'Chị chuyển giúp em vào tài khoản công ty ghi trên hợp đồng, nội dung "{HD} thanh toan dot 2". Kế toán bên em đối soát theo số hợp đồng ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C05', 8, 22, FALSE, 'MSG', 'C', 'Ok em, chiều nay chị chuyển.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C05', 9, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C05', 10, 200, FALSE, 'READ', 'C', NULL, NULL, NULL, NULL, NULL, NULL, NULL),

    ('C06', 1, 0, FALSE, 'MSG', 'A', 'Chào anh, đơn {SO} dự kiến giao trong tuần tới. Anh xác nhận giúp em địa chỉ nhận vẫn là địa chỉ cửa hàng như trên hợp đồng chứ ạ?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C06', 2, 1, FALSE, 'STATUS', 'A', 'WAITING_ON_CUSTOMER', NULL, NULL, NULL, NULL, NULL, NULL),

    ('C07', 1, 0, FALSE, 'MSG', 'C', 'Chào em, thùng hàng đơn {SO} có in mã thùng và mã lô, bên chị cần lưu mã nào để sau này báo lỗi nếu có?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C07', 2, 8, FALSE, 'MSG', 'A', 'Dạ chị lưu mã thùng in trên tem (bắt đầu bằng LPN-) là đủ ạ. Mỗi thùng một mã, bên em tra được cả lô sản xuất từ mã đó.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C07', 3, 4, FALSE, 'MSG', 'C', 'Ok cảm ơn em.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C07', 4, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C07', 5, 2875, FALSE, 'MSG', 'C', 'Em ơi cho chị hỏi thêm, nếu tem thùng bị rách thì tra bằng gì được?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C07', 6, 10, FALSE, 'MSG', 'A', 'Dạ chị gửi em số đơn và mã hàng in trên hông thùng, em tra được danh sách thùng của đơn ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C07', 7, 10, FALSE, 'MSG', 'C', 'Ok em.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C07', 8, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),

    ('C08', 1, 0, FALSE, 'MSG', 'C', 'Chào shop, bên mình đang dùng ly giấy 12oz, muốn hỏi có loại ly 2 lớp cách nhiệt in logo không? Khoảng 20 thùng mỗi tháng.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C08', 2, 5, TRUE, 'CLAIM', 'A', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    ('C08', 3, 4, TRUE, 'MSG', 'A', 'Chào anh, bên em có ly giấy 2 lớp 12oz in logo, giữ nhiệt tốt cho đồ uống nóng. Với 20 thùng mỗi tháng em đề xuất ký hợp đồng giao theo đợt cho ổn định giá ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C08', 4, 1, FALSE, 'MSG', 'A', 'Anh cho em xin file logo, em lên mẫu và báo giá chi tiết trong hôm nay.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C08', 5, 35, FALSE, 'FILE', 'C', 'Logo quán bên mình.', NULL, NULL, 'logo-quan.pdf', 'application/pdf', 512000, NULL),
    ('C08', 6, 6, FALSE, 'MSG', 'A', 'Dạ em nhận được rồi ạ. Chiều nay em gửi báo giá trên cổng khách hàng, anh xem và phản hồi giúp em.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C08', 7, 1, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C08', 8, 300, FALSE, 'READ', 'C', NULL, NULL, NULL, NULL, NULL, NULL, NULL),

    ('C09', 1, 0, FALSE, 'MSG', 'C', 'Chào em, báo giá {QT} bên anh muốn chia làm 2 đợt giao cách nhau 2 tuần được không?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C09', 2, 9, FALSE, 'MSG', 'A', 'Dạ được anh. Nhưng đơn này vừa đúng một chuyến xe tải nên em đề xuất giao một đợt để tiết kiệm phí vận chuyển; nếu anh vẫn cần chia đợt em ghi vào hợp đồng ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C09', 3, 11, FALSE, 'MSG', 'C', 'Vậy giao một đợt cho gọn, chiều anh chấp nhận báo giá.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C09', 4, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),

    ('C10', 1, 0, FALSE, 'MSG', 'C', 'Em ơi, ngày giao đơn {SO} bên chị chỉ nhận được buổi chiều, sau 14h nhé.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C10', 2, 5, FALSE, 'MSG', 'A', 'Dạ em đã ghi chú cho tài xế giao sau 14h ạ. Xe rời kho lúc 8h nên khoảng 15h là tới chị.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C10', 3, 1, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C10', 4, 120, FALSE, 'READ', 'C', NULL, NULL, NULL, NULL, NULL, NULL, NULL),

    ('C11', 1, 0, FALSE, 'MSG', 'C', 'Chào em, ly đợt trước dùng tốt, bên anh muốn đặt thêm 15 thùng cùng mẫu. Em lên báo giá giúp anh nhé.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C11', 2, 12, FALSE, 'MSG', 'A', 'Dạ em cảm ơn anh. Em lên báo giá cùng mẫu, cùng đơn giá đợt trước và gửi anh trong ngày mai ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C11', 3, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C11', 4, 1500, FALSE, 'CSAT', 'C', NULL, NULL, 'Chờ báo giá hơi lâu.', NULL, NULL, NULL, 3),

    ('C12', 1, 0, FALSE, 'MSG', 'C', 'Chào em, bên chị muốn dừng đơn từ báo giá {QT}, kế hoạch bên chị thay đổi. Em huỷ giúp chị nhé.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C12', 2, 10, FALSE, 'MSG', 'A', 'Dạ đơn chưa lên hợp đồng nên em huỷ được ngay, không phát sinh phí ạ. Khi nào chị cần đặt lại cứ nhắn em.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C12', 3, 8, FALSE, 'MSG', 'C', 'Cảm ơn em.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('C12', 4, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),

    ('R1', 1, 0, FALSE, 'MSG', 'C', 'Em ơi xe giao đơn {SO} chạy chưa? Bên chị chưa thấy tài xế gọi.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R1', 2, 6, FALSE, 'MSG', 'A', 'Dạ xe rời kho lúc 8h sáng nay, tài xế {DRV}, biển số {PLATE}. Dự kiến chiều nay tới, tài xế sẽ gọi chị trước khi đến ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R1', 3, 9, FALSE, 'MSG', 'C', 'Ok em, cảm ơn em.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R1', 4, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R1', 5, 3, FALSE, 'CSAT', 'C', NULL, NULL, NULL, NULL, NULL, NULL, 5),

    ('R2', 1, 0, FALSE, 'MSG', 'C', 'Hợp đồng {HD} anh đọc rồi. Điều khoản đặt cọc 30% trong 3 ngày sau khi ký đúng không em? Ký xong gửi bản scan ở đâu?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R2', 2, 8, FALSE, 'MSG', 'A', 'Dạ đúng anh, đặt cọc 30% trong 3 ngày sau khi ký, phần còn lại theo lịch trong hợp đồng. Anh ký, đóng dấu rồi gửi bản scan cho em, em cập nhật vào hệ thống ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R2', 3, 12, FALSE, 'MSG', 'C', 'Ok, mai anh gửi.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R2', 4, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),

    ('R3', 1, 0, FALSE, 'MSG', 'C', 'Chào em, đơn {SO} đang in tới đâu rồi? Bên chị định khai trương chi nhánh mới cuối tháng.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R3', 2, 10, FALSE, 'MSG', 'A', 'Dạ chào chị, xưởng đã bắt đầu in, hàng nhập kho xong là kịp lịch giao từ ngày {WIN} như hợp đồng ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R3', 3, 15, FALSE, 'MSG', 'C', 'Ok em, có gì cập nhật giúp chị.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R3', 4, 2, FALSE, 'STATUS', 'A', 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL),

    ('R4', 1, 0, FALSE, 'MSG', 'A', 'Chào anh, bên em đang nhận đặt ly in hình Tết, cần chốt file trước ngày 15/12 để kịp giao. Anh có muốn làm mẫu Tết năm nay không ạ?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('R4', 2, 1, FALSE, 'STATUS', 'A', 'WAITING_ON_CUSTOMER', NULL, NULL, NULL, NULL, NULL, NULL),

    ('O1', 1, 0, FALSE, 'MSG', 'C', 'Em ơi, báo giá {QT} có hiệu lực tới ngày nào? Bên chị cần họp nội bộ rồi mới chốt.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O1', 2, 6, FALSE, 'MSG', 'A', 'Dạ báo giá có hiệu lực tới {VALID} chị ạ. Chị họp xong bấm chấp nhận trên cổng khách hàng trước ngày đó là được.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O1', 3, 14, FALSE, 'MSG', 'C', 'Ok. Nếu lấy thêm 10 thùng mỗi loại thì đơn giá có giảm không em?', NULL, NULL, NULL, NULL, NULL, NULL),

    ('O2', 1, 0, FALSE, 'MSG', 'C', 'Chào em, đơn {SO} có thể giao trước phần hàng đã về kho không? Bên anh đang thiếu ly.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O2', 2, 8, FALSE, 'MSG', 'A', 'Dạ để em kiểm tra số thùng đã nhập kho và hỏi lịch xe, em báo anh trong sáng nay ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O2', 3, 1, FALSE, 'NOTE', 'A', '{MGRNAME} kiểm tra giúp em số thùng đơn {SO} đã nhập kho, có xếp xe giao trước một phần được không ạ?', 'MGR', NULL, NULL, NULL, NULL, NULL),
    ('O2', 4, 1, FALSE, 'STATUS', 'A', 'WAITING_ON_INTERNAL', NULL, 'Chờ kho xác nhận số thùng đã nhập', NULL, NULL, NULL, NULL),

    ('O3', 1, 0, FALSE, 'MSG', 'A', 'Chào chị, hợp đồng {HD} đã ký ngày {SIGN}, đợt đặt cọc 30% là {DEP} đồng. Chị chuyển khoản giúp em để bên em gửi đơn sản xuất cho xưởng in ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O3', 2, 1, FALSE, 'STATUS', 'A', 'WAITING_ON_CUSTOMER', NULL, NULL, NULL, NULL, NULL, NULL),

    ('O4', 1, 0, FALSE, 'MSG', 'C', 'Đơn {SO} chưa thấy cập nhật tiến độ in. Bên em sắp khai trương, nếu trễ lịch giao bên em đề nghị giảm giá.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O4', 2, 7, FALSE, 'MSG', 'A', 'Dạ em xin lỗi anh. Em đang làm việc với xưởng in để có ngày hoàn thành chính xác và báo anh sớm nhất ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O4', 3, 2, FALSE, 'STATUS', 'A', 'ESCALATED', NULL, 'Khách đề nghị giảm giá nếu trễ tiến độ in; cần trưởng phòng kinh doanh quyết định.', NULL, NULL, NULL, NULL),
    ('O4', 4, 21, FALSE, 'NOTE', 'A', 'Anh xem giúp em đề nghị giảm giá của khách, đơn đang chờ xưởng in.', 'truongkd', NULL, NULL, NULL, NULL, NULL),

    ('O5', 1, 0, FALSE, 'MSG', 'C', 'Chào shop, mình cần hộp bánh kem in logo 1 màu, khoảng 2.000 hộp mỗi tháng. Shop báo giá giúp mình.', NULL, NULL, NULL, NULL, NULL, NULL),

    ('O6', 1, 0, FALSE, 'MSG', 'C', 'Em ơi, ngày giao đơn {SO} nếu chị không có ở cửa hàng thì bạn quản lý ca nhận thay được không?', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O6', 2, 4, FALSE, 'MSG', 'A', 'Dạ được chị, người nhận ký biên bản giao hàng là được ạ. Chị cho em xin tên bạn quản lý để em ghi chú cho tài xế.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O6', 3, 11, FALSE, 'MSG', 'C', 'Bạn tên Hưng, quản lý ca chiều.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O6', 4, 3, FALSE, 'MSG', 'A', 'Dạ em ghi chú cho tài xế rồi ạ.', NULL, NULL, NULL, NULL, NULL, NULL),
    ('O6', 5, 12, FALSE, 'READ', 'C', NULL, NULL, NULL, NULL, NULL, NULL, NULL);

-- What a customer did in a conversation after a chat notice: the notice reads as seen (80.4).
CREATE TEMP TABLE x_cna_seen (conversation_id UUID, at TIMESTAMPTZ) ON COMMIT DROP;

-- One line of the conversation log (ConversationEventJpaEntity; append-only).
CREATE FUNCTION pg_temp.cna_event(conv UUID, n INTEGER, typ TEXT, actor_type TEXT, actor UUID, at TIMESTAMPTZ,
                                  from_s TEXT, to_s TEXT, ctx UUID, msg UUID, target UUID, note TEXT, detail TEXT,
                                  by_name TEXT) RETURNS VOID LANGUAGE sql AS $$
INSERT INTO chat.conversation_event (id, conversation_id, type, actor_type, actor_id, occurred_at, from_status,
                                     to_status, context_id, message_id, target_user_id, note, detail, version,
                                     created_at, created_by)
VALUES (pg_temp.sid('cna:chat-event:' || conv || ':' || n), conv, typ, actor_type, actor, at, from_s, to_s, ctx, msg,
        target, note, detail::jsonb, 0, at, by_name)
$$;

-- ReadMarkersAdapter.advance, as the application runs it (forward only, version + 1 per move).
CREATE FUNCTION pg_temp.cna_mark(conv UUID, reader UUID, reader_type TEXT, upto BIGINT, at TIMESTAMPTZ)
    RETURNS VOID LANGUAGE sql AS $$
INSERT INTO chat.participant_read AS r (id, conversation_id, user_id, reader_type, last_read_seq, last_read_message_id,
                                        last_read_at, version, created_at)
SELECT pg_temp.sid('cna:read:' || conv || ':' || reader), conv, reader, reader_type, m.seq, m.id, at, 0, at
  FROM chat.message m WHERE m.conversation_id = conv AND m.seq = upto
    ON CONFLICT (conversation_id, user_id) DO UPDATE
   SET last_read_seq = excluded.last_read_seq, last_read_message_id = excluded.last_read_message_id,
       last_read_at = excluded.last_read_at, version = r.version + 1, last_modified_at = excluded.last_read_at
 WHERE excluded.last_read_seq > r.last_read_seq
$$;

-- ChatNotifier.firstResponseOverdue / resolutionOverdue: the chat supervisors' inbox.
CREATE FUNCTION pg_temp.cna_sla_notice(conv UUID, first_response BOOLEAN, due TIMESTAMPTZ, at TIMESTAMPTZ)
    RETURNS VOID LANGUAGE sql AS $$
INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, body, link, source_type,
                                            source_id, dedupe_key, created_at)
SELECT pg_temp.sid('cna:notice:chat-sla:' || conv || ':' || first_response || ':' || u), u, 'CHAT_SLA', 'WARN',
       CASE WHEN first_response THEN 'Có hội thoại chat quá hạn phản hồi đầu tiên'
            ELSE 'Có hội thoại chat quá hạn giải quyết' END,
       CASE WHEN first_response
            THEN 'Phản hồi đầu tiên đến hạn lúc ' || to_char(due AT TIME ZONE 'Asia/Ho_Chi_Minh', 'HH24:MI DD/MM/YYYY')
                 || ' nhưng chưa được gửi.'
            ELSE 'Hội thoại cần được giải quyết trước '
                 || to_char(due AT TIME ZONE 'Asia/Ho_Chi_Minh', 'HH24:MI DD/MM/YYYY') || ' nhưng vẫn chưa xong.' END,
       '/admin/chat/' || conv, 'CHAT_CONVERSATION', conv::text,
       CASE WHEN first_response THEN 'chat-first-response-overdue:' || conv
            ELSE 'chat-resolution-overdue:' || conv || ':' || extract(epoch FROM due)::bigint END, at
  FROM pg_temp.cna_holders('chat-supervision:UPDATE', NULL) u
    ON CONFLICT (recipient_id, dedupe_key) DO NOTHING
$$;

-- ---------------------------------------------------------------------------------------------
-- 80.3 Playing the conversations (Conversation, ConversationWriter, StaffChatService, CustomerChatService,
--      ChatAttachmentService, ChatLifecycle), one save per step: the lifecycle trigger judges every move.
-- ---------------------------------------------------------------------------------------------
DO $chat$
DECLARE
    c RECORD;
    s RECORD;
    ended CONSTANT TEXT[] := ARRAY['RESOLVED', 'AUTO_CLOSED', 'CLOSED'];
    cutoff CONSTANT TIMESTAMPTZ := pg_temp.at(0, '00:00');
    v_status TEXT; v_from TEXT; v_agent UUID; v_assigned_at TIMESTAMPTZ; v_status_at TIMESTAMPTZ;
    v_fr_due TIMESTAMPTZ; v_fr_at TIMESTAMPTZ; v_fr_alert TIMESTAMPTZ; v_res_due TIMESTAMPTZ; v_res_alert TIMESTAMPTZ;
    v_esc TEXT; v_resolved_at TIMESTAMPTZ; v_resolved_by UUID; v_auto_at TIMESTAMPTZ; v_closed_at TIMESTAMPTZ;
    v_reopen TIMESTAMPTZ; v_last_cust TIMESTAMPTZ; v_last_msg TIMESTAMPTZ; v_seq BIGINT; v_csat INTEGER;
    v_csat_comment TEXT; v_csat_at TIMESTAMPTZ;
    v_ev INTEGER; v_at TIMESTAMPTZ; v_limit TIMESTAMPTZ; v_ctx UUID; v_msg UUID;
    v_opener_type TEXT; v_opener UUID; v_opener_name TEXT; v_by UUID; v_by_name TEXT; v_by_type TEXT;
    v_body TEXT; v_mention UUID; v_detail TEXT; v_changed BOOLEAN; v_next TEXT; v_t TIMESTAMPTZ;
    v_t_fr TIMESTAMPTZ; v_t_res TIMESTAMPTZ; v_t_auto TIMESTAMPTZ; v_t_close TIMESTAMPTZ; v_period BIGINT;
    v_told INTEGER; v_ref TEXT; v_email TEXT; k TEXT;
BEGIN
    FOR c IN SELECT * FROM x_cna_conv ORDER BY open_at LOOP
        v_ev := 0; v_seq := 0; v_fr_at := NULL; v_fr_alert := NULL; v_res_alert := NULL; v_esc := NULL;
        v_resolved_at := NULL; v_resolved_by := NULL; v_auto_at := NULL; v_closed_at := NULL; v_reopen := NULL;
        v_last_cust := NULL; v_last_msg := NULL; v_csat := NULL; v_csat_comment := NULL; v_csat_at := NULL;
        v_ctx := NULL;
        IF c.opener = 'C' THEN
            v_opener_type := 'CUSTOMER'; v_opener := c.cust_uid; v_opener_name := c.cust_user;
        ELSE
            v_opener_type := 'AGENT'; v_opener := c.sales_uid; v_opener_name := c.sales;
        END IF;
        -- SalesRouter: the opener when Sales open it; the Sales answering for the document; else the customer's
        -- most recent agent; else the queue.
        v_agent := CASE WHEN c.opener = 'A' THEN c.sales_uid
                        WHEN c.ctx IS NOT NULL THEN c.sales_uid
                        ELSE (SELECT k2.assigned_agent_id FROM chat.conversation k2
                               WHERE k2.customer_id = c.customer_id AND k2.assigned_agent_id IS NOT NULL
                               ORDER BY coalesce(k2.last_message_at, k2.created_at) DESC LIMIT 1) END;
        v_status := CASE WHEN v_agent IS NULL THEN 'QUEUED' ELSE 'ASSIGNED' END;
        v_assigned_at := CASE WHEN v_agent IS NOT NULL THEN c.open_at END;
        v_status_at := c.open_at;
        v_fr_due := pg_temp.cna_bplus(c.open_at, 15);
        v_res_due := pg_temp.cna_bplus(c.open_at, 2 * 570);

        INSERT INTO chat.conversation (id, customer_id, order_id, subject, status, assigned_agent_id, version,
                                       created_at, created_by, opened_by_type, opened_by, assigned_at,
                                       status_changed_at, first_response_due_at, resolution_due_at, last_message_seq)
        SELECT c.id, c.customer_id, CASE WHEN c.ctx = 'ORDER' THEN c.order_id END,
               (SELECT string_agg(coalesce(c.vars ->> m[1], m[1]), '') FROM regexp_matches(c.subject, '(\{[A-Z]+\}|[^{]+)', 'g') m),
               v_status, v_agent, 0, c.open_at, v_opener_name, v_opener_type, v_opener, v_assigned_at, c.open_at,
               v_fr_due, v_res_due, 0;
        v_ev := v_ev + 1;
        PERFORM pg_temp.cna_event(c.id, v_ev, 'OPENED', v_opener_type, v_opener, c.open_at, NULL, NULL, NULL, NULL,
                                  NULL, NULL, NULL, v_opener_name);
        v_ev := v_ev + 1;
        PERFORM pg_temp.cna_event(c.id, v_ev, 'STATUS_CHANGED', 'SYSTEM', NULL, c.open_at, 'NEW', v_status, NULL, NULL,
                                  NULL, NULL, NULL, v_opener_name);
        v_ev := v_ev + 1;
        PERFORM pg_temp.cna_event(c.id, v_ev, CASE WHEN v_agent IS NULL THEN 'QUEUED' ELSE 'ASSIGNED' END, 'SYSTEM',
                                  NULL, c.open_at, NULL, NULL, NULL, NULL, v_agent, NULL, NULL, v_opener_name);
        IF c.ctx IS NOT NULL THEN
            v_ctx := pg_temp.sid('cna:chat-context:' || c.key);
            INSERT INTO chat.conversation_context (id, conversation_id, kind, quote_id, order_id, is_primary,
                                                   attached_by, attached_at, version, created_at, created_by)
            VALUES (v_ctx, c.id, c.ctx, CASE WHEN c.ctx = 'QUOTE' THEN c.quote_id END,
                    CASE WHEN c.ctx = 'ORDER' THEN c.order_id END, TRUE, v_opener, c.open_at, 0, c.open_at,
                    v_opener_name);
            v_ev := v_ev + 1;
            PERFORM pg_temp.cna_event(c.id, v_ev, 'CONTEXT_ATTACHED', v_opener_type, v_opener, c.open_at, NULL, NULL,
                                      v_ctx, NULL, NULL, NULL, NULL, v_opener_name);
        END IF;

        v_at := c.open_at;
        FOR s IN SELECT * FROM x_cna_tstep WHERE tkey = c.key ORDER BY n LOOP
            v_at := CASE WHEN s.biz THEN pg_temp.cna_bplus(v_at, s.delta) ELSE v_at + s.delta * INTERVAL '1 minute' END;
            -- The jobs that ran before this step (and, after the last step, up to the start of today).
            FOR pass IN 1..2 LOOP
                v_limit := CASE WHEN pass = 1 THEN v_at ELSE NULL END;
                EXIT WHEN pass = 2;
                LOOP
                    v_t_fr := CASE WHEN v_fr_at IS NULL AND v_fr_alert IS NULL AND v_fr_due IS NOT NULL
                                        AND NOT v_status = ANY (ended) THEN v_fr_due + INTERVAL '1 minute' END;
                    v_t_res := CASE WHEN v_res_due IS NOT NULL AND v_res_alert IS NULL AND NOT v_status = ANY (ended)
                                    THEN v_res_due + INTERVAL '1 minute' END;
                    v_t_auto := CASE WHEN v_status = 'WAITING_ON_CUSTOMER'
                                     THEN v_status_at + INTERVAL '7 days 1 minute' END;
                    v_t_close := CASE WHEN v_status IN ('RESOLVED', 'AUTO_CLOSED') THEN v_reopen + INTERVAL '1 minute' END;
                    v_t := least(v_t_fr, v_t_res, v_t_auto, v_t_close);
                    EXIT WHEN v_t IS NULL OR v_t >= v_limit;
                    RAISE EXCEPTION 'cna: conversation % (%): a job (% % % %) falls before step % at %', c.key, c.okey,
                        v_t_fr, v_t_res, v_t_auto, v_t_close, s.n, v_at;
                END LOOP;
            END LOOP;
            IF v_at >= cutoff THEN
                RAISE EXCEPTION 'cna: conversation % step % would be today or later', c.key, s.n;
            END IF;
            v_body := s.body;
            IF v_body IS NOT NULL THEN
                FOR k IN SELECT jsonb_object_keys(c.vars) LOOP
                    v_body := replace(v_body, k, coalesce(c.vars ->> k, ''));
                END LOOP;
            END IF;
            IF s.who = 'C' THEN
                v_by := c.cust_uid; v_by_name := c.cust_user; v_by_type := 'CUSTOMER';
            ELSE
                v_by := coalesce(v_agent, c.sales_uid); v_by_name := pg_temp.cna_uname(v_by); v_by_type := 'AGENT';
            END IF;
            v_from := v_status;
            v_changed := FALSE;

            IF s.kind IN ('MSG', 'FILE') THEN
                IF s.who = 'C' THEN
                    IF v_status IN ('RESOLVED', 'AUTO_CLOSED') THEN
                        IF v_at >= v_reopen THEN
                            RAISE EXCEPTION 'cna: % step %: past the reopen window', c.key, s.n;
                        END IF;
                        v_status := 'REOPENED'; v_detail := 'The customer wrote again';
                        v_res_due := pg_temp.cna_bplus(v_at, 2 * 570); v_res_alert := NULL;
                    ELSIF v_status = 'WAITING_ON_CUSTOMER' THEN
                        v_status := 'ACTIVE'; v_detail := 'The customer answered';
                    END IF;
                    v_last_cust := v_at;
                    INSERT INTO x_cna_seen VALUES (c.id, v_at);
                ELSE
                    IF v_agent IS NULL OR v_status = ANY (ended) THEN
                        RAISE EXCEPTION 'cna: % step %: no agent in charge, or the conversation ended', c.key, s.n;
                    END IF;
                    IF v_status IN ('ASSIGNED', 'REOPENED') THEN
                        v_status := 'ACTIVE'; v_detail := NULL;
                    END IF;
                END IF;
                v_changed := v_status <> v_from;
                IF v_changed THEN v_status_at := v_at; END IF;
                v_seq := v_seq + 1; v_last_msg := v_at;
                IF s.who = 'A' AND v_fr_at IS NULL THEN v_fr_at := v_at; END IF;
                v_msg := pg_temp.sid('cna:chat-message:' || c.key || ':' || v_seq);
                UPDATE chat.conversation
                   SET status = v_status, status_changed_at = v_status_at, first_responded_at = v_fr_at,
                       resolution_due_at = v_res_due, resolution_alerted_at = v_res_alert,
                       last_customer_message_at = v_last_cust, last_message_at = v_last_msg,
                       last_message_seq = v_seq, version = version + 1, last_modified_at = v_at,
                       last_modified_by = v_by_name
                 WHERE id = c.id;
                INSERT INTO chat.message (id, conversation_id, sender_type, sender_id, body, sent_at, version,
                                          created_at, created_by, seq, kind, visibility)
                VALUES (v_msg, c.id, v_by_type, v_by, v_body, v_at, 0, v_at, v_by_name, v_seq,
                        CASE s.kind WHEN 'FILE' THEN 'ATTACHMENT' ELSE 'TEXT' END, 'CUSTOMER');
                IF s.kind = 'FILE' THEN
                    INSERT INTO chat.message_attachment (id, message_id, message_kind, file_key, original_name,
                                                         content_type, size_bytes, sha256, uploaded_by, version,
                                                         created_at, created_by)
                    VALUES (pg_temp.sid('cna:chat-file:' || c.key || ':' || v_seq), v_msg, 'ATTACHMENT',
                            'chat-attachments/showcase/' || pg_temp.sid('cna:chat-file:' || c.key || ':' || v_seq)
                                || CASE s.ctype WHEN 'application/pdf' THEN '.pdf' WHEN 'image/png' THEN '.png'
                                                ELSE '.jpg' END,
                            s.file, s.ctype, s.size,
                            encode(sha256(convert_to('showcase:chat-file:' || c.key || ':' || v_seq, 'UTF8')), 'hex'),
                            v_by, 0, v_at, v_by_name);
                END IF;
                IF v_changed THEN
                    v_ev := v_ev + 1;
                    PERFORM pg_temp.cna_event(c.id, v_ev, 'STATUS_CHANGED', v_by_type, v_by, v_at, v_from, v_status,
                                              NULL, NULL, NULL, v_detail, NULL, v_by_name);
                END IF;
                IF s.who = 'A' AND v_fr_at = v_at THEN
                    v_ev := v_ev + 1;
                    PERFORM pg_temp.cna_event(c.id, v_ev, 'FIRST_RESPONSE', 'AGENT', v_by, v_at, NULL, NULL, NULL,
                                              NULL, NULL, NULL, NULL, v_by_name);
                END IF;
                PERFORM pg_temp.cna_mark(c.id, v_by, CASE WHEN s.who = 'C' THEN 'CUSTOMER' ELSE 'STAFF' END, v_seq,
                                         v_at);
                -- ChatNotifier.salesReplied: the customer is told unless a chat of theirs is open now (they wrote
                -- in the last ten minutes), once per hour; a new notice also mails them (chat.reply).
                IF s.who = 'A' AND (v_last_cust IS NULL OR v_last_cust < v_at - INTERVAL '10 minutes') THEN
                    v_period := (floor(extract(epoch FROM v_at) / 3600) * 3600)::bigint;
                    INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, body,
                                                                link, source_type, source_id, dedupe_key, created_at)
                    VALUES (pg_temp.sid('cna:notice:chat-reply:' || c.key || ':' || v_period), c.cust_uid, 'CHAT',
                            'INFO', 'Sale đã trả lời anh/chị trong hội thoại', 'Mở hội thoại để xem câu trả lời.',
                            '/chat/' || c.id, 'CHAT_CONVERSATION', c.id::text,
                            'chat-reply:' || c.id || ':' || v_period, v_at)
                        ON CONFLICT (recipient_id, dedupe_key) DO NOTHING;
                    GET DIAGNOSTICS v_told = ROW_COUNT;
                    IF v_told > 0 THEN
                        v_ref := 'customer-mail:chat.reply:' || c.id || '@' || v_period;
                        SELECT email INTO v_email FROM customer.customer WHERE id = c.customer_id;
                        INSERT INTO notification.delivery_log (id, template_code, channel, recipient, status, sent_at,
                                                               version, created_at, created_by, external_reference,
                                                               operation_reference, terminal, delivery_generation,
                                                               attempt_number, provider)
                        VALUES (pg_temp.sid('cna:mail:' || v_ref), 'chat.reply', 'EMAIL', v_email, 'SENT',
                                v_at + INTERVAL '3 seconds', 0, v_at + INTERVAL '3 seconds', 'system', v_ref, v_ref,
                                FALSE, 0, 1, 'SMTP');
                    END IF;
                END IF;

            ELSIF s.kind = 'NOTE' THEN
                v_seq := v_seq + 1; v_last_msg := v_at;
                v_msg := pg_temp.sid('cna:chat-message:' || c.key || ':' || v_seq);
                v_mention := CASE s.mention WHEN 'MGR' THEN pg_temp.staff(c.mgr)
                                            WHEN NULL THEN NULL ELSE pg_temp.staff(s.mention) END;
                UPDATE chat.conversation
                   SET last_message_at = v_last_msg, last_message_seq = v_seq, version = version + 1,
                       last_modified_at = v_at, last_modified_by = v_by_name
                 WHERE id = c.id;
                INSERT INTO chat.message (id, conversation_id, sender_type, sender_id, body, sent_at, version,
                                          created_at, created_by, seq, kind, visibility)
                VALUES (v_msg, c.id, 'AGENT', v_by, v_body, v_at, 0, v_at, v_by_name, v_seq, 'INTERNAL_NOTE', 'STAFF');
                IF v_mention IS NOT NULL THEN
                    INSERT INTO chat.message_mention (id, message_id, message_kind, user_id, created_at)
                    VALUES (pg_temp.sid('cna:chat-mention:' || c.key || ':' || v_seq), v_msg, 'INTERNAL_NOTE',
                            v_mention, v_at);
                END IF;
                PERFORM pg_temp.cna_mark(c.id, v_by, 'STAFF', v_seq, v_at);
                v_ev := v_ev + 1;
                PERFORM pg_temp.cna_event(c.id, v_ev, 'INTERNAL_NOTE', 'AGENT', v_by, v_at, NULL, NULL, NULL, v_msg,
                                          NULL, NULL,
                                          CASE WHEN v_mention IS NOT NULL THEN '{"mentions":["' || v_mention || '"]}' END,
                                          v_by_name);

            ELSIF s.kind = 'STATUS' THEN
                v_status := s.body;
                IF v_status = 'ESCALATED' THEN v_esc := s.reason; END IF;
                IF v_status = 'RESOLVED' THEN
                    v_resolved_at := v_at; v_resolved_by := v_by; v_reopen := v_at + INTERVAL '14 days';
                END IF;
                v_status_at := v_at;
                UPDATE chat.conversation
                   SET status = v_status, status_changed_at = v_status_at, escalation_reason = v_esc,
                       resolved_at = v_resolved_at, resolved_by = v_resolved_by, reopen_until = v_reopen,
                       version = version + 1, last_modified_at = v_at, last_modified_by = v_by_name
                 WHERE id = c.id;
                v_ev := v_ev + 1;
                PERFORM pg_temp.cna_event(c.id, v_ev, 'STATUS_CHANGED', 'AGENT', v_by, v_at, v_from, v_status, NULL,
                                          NULL, NULL, s.reason, NULL, v_by_name);

            ELSIF s.kind = 'CLAIM' THEN
                v_agent := c.sales_uid; v_by := v_agent; v_by_name := c.sales;
                v_assigned_at := v_at; v_status := 'ASSIGNED'; v_status_at := v_at;
                UPDATE chat.conversation
                   SET status = v_status, assigned_agent_id = v_agent, assigned_at = v_assigned_at,
                       status_changed_at = v_status_at, version = version + 1, last_modified_at = v_at,
                       last_modified_by = v_by_name
                 WHERE id = c.id;
                v_ev := v_ev + 1;
                PERFORM pg_temp.cna_event(c.id, v_ev, 'STATUS_CHANGED', 'AGENT', v_by, v_at, v_from, v_status, NULL,
                                          NULL, NULL, NULL, NULL, v_by_name);
                v_ev := v_ev + 1;
                PERFORM pg_temp.cna_event(c.id, v_ev, 'CLAIMED', 'AGENT', v_by, v_at, NULL, NULL, NULL, NULL, v_agent,
                                          NULL, NULL, v_by_name);

            ELSIF s.kind = 'READ' THEN
                PERFORM pg_temp.cna_mark(c.id, v_by, CASE WHEN s.who = 'C' THEN 'CUSTOMER' ELSE 'STAFF' END, v_seq,
                                         v_at);
                IF s.who = 'C' THEN INSERT INTO x_cna_seen VALUES (c.id, v_at); END IF;

            ELSIF s.kind = 'CSAT' THEN
                IF v_status <> 'RESOLVED' THEN
                    RAISE EXCEPTION 'cna: % step %: a rating only while resolved', c.key, s.n;
                END IF;
                v_csat := s.score; v_csat_comment := s.reason; v_csat_at := v_at;
                UPDATE chat.conversation
                   SET csat_score = v_csat, csat_comment = v_csat_comment, csat_submitted_at = v_csat_at,
                       version = version + 1, last_modified_at = v_at, last_modified_by = v_by_name
                 WHERE id = c.id;
                v_ev := v_ev + 1;
                PERFORM pg_temp.cna_event(c.id, v_ev, 'CSAT_SUBMITTED', 'CUSTOMER', v_by, v_at, NULL, NULL, NULL, NULL,
                                          NULL, NULL, '{"score":' || v_csat || '}', v_by_name);
                INSERT INTO x_cna_seen VALUES (c.id, v_at);
            END IF;
        END LOOP;

        -- ChatLifecycleJobs after the last step, in the order their moments came, up to the start of today: the SLA
        -- alerts (once per clock), auto-close of a silent customer after 7 days, close after the 14-day reopen window.
        LOOP
            v_t_fr := CASE WHEN v_fr_at IS NULL AND v_fr_alert IS NULL AND v_fr_due IS NOT NULL
                                AND NOT v_status = ANY (ended) THEN v_fr_due + INTERVAL '1 minute' END;
            v_t_res := CASE WHEN v_res_due IS NOT NULL AND v_res_alert IS NULL AND NOT v_status = ANY (ended)
                            THEN v_res_due + INTERVAL '1 minute' END;
            v_t_auto := CASE WHEN v_status = 'WAITING_ON_CUSTOMER' THEN v_status_at + INTERVAL '7 days 1 minute' END;
            v_t_close := CASE WHEN v_status IN ('RESOLVED', 'AUTO_CLOSED') THEN v_reopen + INTERVAL '1 minute' END;
            v_t := least(v_t_fr, v_t_res, v_t_auto, v_t_close);
            EXIT WHEN v_t IS NULL OR v_t >= cutoff;
            v_from := v_status;
            IF v_t = v_t_fr THEN
                v_fr_alert := v_t;
                UPDATE chat.conversation SET first_response_alerted_at = v_fr_alert, version = version + 1,
                       last_modified_at = v_t, last_modified_by = 'system' WHERE id = c.id;
                v_ev := v_ev + 1;
                PERFORM pg_temp.cna_event(c.id, v_ev, 'FIRST_RESPONSE_OVERDUE', 'SYSTEM', NULL, v_t, NULL, NULL, NULL,
                                          NULL, v_agent, NULL, '{"dueAt":"' || pg_temp.cna_iso(v_fr_due) || '"}',
                                          'system');
                PERFORM pg_temp.cna_sla_notice(c.id, TRUE, v_fr_due, v_t);
            ELSIF v_t = v_t_res THEN
                v_res_alert := v_t;
                UPDATE chat.conversation SET resolution_alerted_at = v_res_alert, version = version + 1,
                       last_modified_at = v_t, last_modified_by = 'system' WHERE id = c.id;
                v_ev := v_ev + 1;
                PERFORM pg_temp.cna_event(c.id, v_ev, 'RESOLUTION_OVERDUE', 'SYSTEM', NULL, v_t, NULL, NULL, NULL,
                                          NULL, v_agent, NULL, '{"dueAt":"' || pg_temp.cna_iso(v_res_due) || '"}',
                                          'system');
                PERFORM pg_temp.cna_sla_notice(c.id, FALSE, v_res_due, v_t);
            ELSIF v_t = v_t_auto THEN
                v_status := 'AUTO_CLOSED'; v_auto_at := v_t; v_reopen := v_t + INTERVAL '14 days'; v_status_at := v_t;
                UPDATE chat.conversation SET status = v_status, auto_closed_at = v_auto_at, reopen_until = v_reopen,
                       status_changed_at = v_status_at, version = version + 1, last_modified_at = v_t,
                       last_modified_by = 'system' WHERE id = c.id;
                v_ev := v_ev + 1;
                PERFORM pg_temp.cna_event(c.id, v_ev, 'STATUS_CHANGED', 'SYSTEM', NULL, v_t, v_from, v_status, NULL,
                                          NULL, NULL, 'The customer did not answer', NULL, 'system');
            ELSE
                v_status := 'CLOSED'; v_closed_at := v_t; v_status_at := v_t;
                UPDATE chat.conversation SET status = v_status, closed_at = v_closed_at, status_changed_at = v_status_at,
                       version = version + 1, last_modified_at = v_t, last_modified_by = 'system' WHERE id = c.id;
                v_ev := v_ev + 1;
                PERFORM pg_temp.cna_event(c.id, v_ev, 'STATUS_CHANGED', 'SYSTEM', NULL, v_t, v_from, v_status, NULL,
                                          NULL, NULL, NULL, NULL, 'system');
            END IF;
        END LOOP;
    END LOOP;
END $chat$;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM chat.conversation k
                WHERE k.last_message_seq <> coalesce((SELECT max(m.seq) FROM chat.message m WHERE m.conversation_id = k.id), 0)
                   OR k.last_message_seq <> (SELECT count(*) FROM chat.message m WHERE m.conversation_id = k.id))
       OR EXISTS (SELECT 1 FROM chat.participant_read r JOIN chat.conversation k ON k.id = r.conversation_id
                   WHERE r.last_read_seq > k.last_message_seq) THEN
        RAISE EXCEPTION 'cna: message numbering or read markers out of step';
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 80.4 The inbox (notification.user_notification) for the base's own events, as the listeners post them, over the
--      last 30 days (InboxPurgeJob deletes what was read more than 90 days ago)
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_cna_notice (id UUID, recipient_id UUID, category TEXT, severity TEXT, title TEXT, body TEXT,
                                link TEXT, source_type TEXT, source_id TEXT, dedupe_key TEXT, created_at TIMESTAMPTZ)
    ON COMMIT DROP;

-- QuoteNotificationListener (CustomerActedOnQuote): the Sales who sent the quote hears the customer accepted it or
-- asked for changes.
INSERT INTO x_cna_notice
SELECT pg_temp.sid('cna:notice:quote:' || o.key), q.sent_by, 'QUOTE', 'INFO',
       CASE WHEN q.status = 'CHANGES_REQUESTED' THEN 'Khách yêu cầu sửa báo giá ' || q.quote_number
            ELSE 'Báo giá ' || q.quote_number || ' đã được khách chấp nhận: chuyển thành đơn hàng' END,
       NULL, '/admin/sales/quotes/' || q.id, 'QUOTE', q.id::text,
       'quote:' || q.id || ':' || CASE WHEN q.status = 'CHANGES_REQUESTED' THEN 'CHANGES_REQUESTED' ELSE 'ACCEPTED' END
           || ':' || pg_temp.cna_iso(q.responded_at),
       q.responded_at + INTERVAL '2 seconds'
  FROM h_order o JOIN ordering.quote q ON q.id = o.quote_id
 WHERE (o.st >= 2 OR o.stage = 'Q_CHANGES') AND q.responded_at IS NOT NULL;

-- ProductionOrderArrivalNotificationListener and ProductionOrderSentOversightListener (ProductionOrderSent): the
-- receiving warehouse's people, and those who oversee the sending (Sales Manager, administrators).
INSERT INTO x_cna_notice
SELECT pg_temp.sid('cna:notice:po-arrival:' || o.key || ':' || u), u, 'PRODUCTION_ORDER', 'INFO',
       'Đơn sản xuất ' || p.code || ' sẽ về kho ' || w.name,
       v.cartons || ' thùng, thể tích ' || to_char(round(v.volume, 2), 'FM999999990.00') || ' m³, cần hàng ngày '
           || to_char(p.need_by_date, 'DD/MM/YYYY') || '.',
       '/production/orders/' || p.id, 'PRODUCTION_ORDER', p.id::text, 'production-order-sent:' || p.id,
       p.sent_at + INTERVAL '3 seconds'
  FROM h_sent o
  JOIN ordering.production_order p ON p.id = o.po_id
  JOIN warehouse.warehouse w ON w.id = p.receiving_warehouse_id
  JOIN (SELECT l.key, sum(l.cartons) AS cartons,
               round(sum(l.cartons * l.length_cm * l.width_cm * l.height_cm / 1000000.0), 3) AS volume
          FROM h_line l GROUP BY l.key) v ON v.key = o.key
 CROSS JOIN LATERAL pg_temp.cna_holders('procurement-goods-receipts:CREATE', p.receiving_warehouse_id) u;

INSERT INTO x_cna_notice
SELECT pg_temp.sid('cna:notice:po-oversight:' || o.key || ':' || u), u, 'PRODUCTION_ORDER', 'INFO',
       'Đơn sản xuất ' || p.code || ' đã được gửi',
       v.cartons || ' thùng, cần hàng ' || to_char(p.need_by_date, 'DD/MM/YYYY') || '.',
       '/production/orders/' || p.id, 'PRODUCTION_ORDER', p.id::text, 'production-order-sent-oversight:' || p.id,
       p.sent_at + INTERVAL '3 seconds'
  FROM h_sent o
  JOIN ordering.production_order p ON p.id = o.po_id
  JOIN (SELECT l.key, sum(l.cartons) AS cartons FROM h_line l GROUP BY l.key) v ON v.key = o.key
 CROSS JOIN LATERAL pg_temp.cna_holders('production-order-sends:READ', NULL) u;

-- VehicleHandoverNotificationListener (VehicleHandedOver): the customer's portal account, when the truck leaves.
INSERT INTO x_cna_notice
SELECT pg_temp.sid('cna:notice:handover:' || o.key), cu.user_id, 'ORDER_SHIPPING', 'INFO',
       'Đơn ' || o.order_number || ' đã bàn giao vận chuyển',
       'Hàng của đơn đã lên xe và rời kho. Hãng vận chuyển: ' || coalesce(nullif(k.name, ''), '-') || '. Mã vận đơn: '
           || coalesce(nullif(l.waybill_no, ''), '-') || '. Biển số xe: ' || coalesce(nullif(l.vehicle_plate, ''), '-')
           || '.',
       '/orders/' || o.id, 'ORDER', o.id::text, 'vehicle-handed-over:' || l.id || ':' || o.id,
       l.dispatched_at + INTERVAL '4 seconds'
  FROM h_shipped o
  JOIN fulfillment.dispatch_load l ON l.id = pg_temp.sid('load:' || o.key)
  JOIN fulfillment.carrier k ON k.id = l.carrier_id
  JOIN customer.customer cu ON cu.id = o.customer_id
 WHERE cu.user_id IS NOT NULL;

-- InstallmentOverdueNotificationListener (InstallmentOverdue, the 00:20 scan the day after the due day): an
-- installment still owing past its due day, to those who may hold the customer's orders.
INSERT INTO x_cna_notice
SELECT pg_temp.sid('cna:notice:overdue:' || i.id || ':' || u), u, 'PAYMENT_OVERDUE', 'WARN',
       'Khách có đợt thanh toán quá hạn: hợp đồng ' || i.contract_number || ', đợt ' || i.seq,
       'Đợt ' || i.seq || ' của hợp đồng ' || i.contract_number || ' (đơn ' || i.order_number || ') có hạn trả ngày '
           || to_char(i.due_date, 'DD/MM/YYYY') || ', đã qua mà chưa trả đủ; còn phải thu '
           || pg_temp.cna_vnd(i.amount - x.paid_before) || ' ' || i.currency
           || '. Anh/chị có thể treo tay các đơn đang chạy của khách.',
       '/admin/orders/' || i.order_id, 'INSTALLMENT', i.id::text, 'installment-overdue:' || i.id, x.announced
  FROM payment.installment i
 CROSS JOIN LATERAL (SELECT ((i.due_date + 1) + TIME '00:20') AT TIME ZONE 'Asia/Ho_Chi_Minh' AS announced) a
 CROSS JOIN LATERAL (SELECT a.announced,
                            coalesce((SELECT sum(rl.amount) FROM payment.payment_receipt_line rl
                                        JOIN payment.payment_receipt r ON r.id = rl.receipt_id
                                       WHERE rl.installment_id = i.id AND r.status = 'RECORDED'
                                         AND r.recorded_at < a.announced), 0) AS paid_before) x
 CROSS JOIN LATERAL pg_temp.cna_holders('sales-order-holds:UPDATE', NULL) u
 WHERE i.due_date IS NOT NULL AND x.paid_before < i.amount AND x.announced < pg_temp.at(0, '00:00');

-- SalesFollowUpService (hourly): a contract sent three days ago and not signed; an order signed three days ago whose
-- installment on signing is not paid. To the Sales who sent the contract.
INSERT INTO x_cna_notice
SELECT pg_temp.sid('cna:notice:unsigned:' || o.key), k.sent_by, 'SALES_FOLLOW_UP', 'WARN',
       'Hợp đồng ' || k.contract_number || ' chưa được ký sau 3 ngày',
       'Hợp đồng ' || k.contract_number || ' của đơn ' || o.order_number || ' đã gửi ngày '
           || to_char(k.sent_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'DD/MM/YYYY')
           || ' nhưng khách chưa ký. Liên hệ khách, hoặc huỷ hợp đồng kèm lý do.',
       '/admin/orders/' || o.id, 'SALES_CONTRACT', k.id::text, 'contract-unsigned:' || k.id,
       k.sent_at + INTERVAL '3 days 37 minutes'
  FROM h_order o JOIN ordering.sales_contract k ON k.id = o.contract_id
 WHERE o.stage = 'PENDING_CONTRACT' AND k.status = 'SENT'
   AND k.sent_at + INTERVAL '3 days 37 minutes' < pg_temp.at(0, '00:00');

INSERT INTO x_cna_notice
SELECT pg_temp.sid('cna:notice:unpaid:' || o.key), coalesce(k.sent_by, k.signed_by), 'SALES_FOLLOW_UP', 'WARN',
       'Đơn ' || o.order_number || ' chưa thanh toán sau 3 ngày ký hợp đồng',
       'Đơn ' || o.order_number || ' (hợp đồng ' || k.contract_number || ') ký ngày '
           || to_char(k.signed_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'DD/MM/YYYY')
           || ' nhưng đợt thanh toán khi ký chưa được trả. Nhắc khách; quá lâu thì huỷ đơn (PAYMENT_NOT_RECEIVED).',
       '/admin/orders/' || o.id, 'ORDER', o.id::text, 'order-unpaid:' || k.id,
       k.signed_at + INTERVAL '3 days 41 minutes'
  FROM h_order o JOIN ordering.sales_contract k ON k.id = o.contract_id
 WHERE o.stage = 'PENDING_PAYMENT' AND k.status = 'SIGNED'
   AND k.signed_at + INTERVAL '3 days 41 minutes' < pg_temp.at(0, '00:00');

INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, body, link, source_type,
                                            source_id, dedupe_key, created_at)
SELECT id, recipient_id, category, severity, title, body, link, source_type, source_id, dedupe_key, created_at
  FROM x_cna_notice
 WHERE recipient_id IS NOT NULL AND created_at >= pg_temp.at(30, '00:00') AND created_at < pg_temp.at(0, '00:00')
    ON CONFLICT (recipient_id, dedupe_key) DO NOTHING;

-- Read: a chat notice when the customer next acted in the conversation; any other one older than three days mostly
-- read within a day, the last three days left unread. Read rows stay younger than the 90-day purge.
UPDATE notification.user_notification n
   SET read_at = (SELECT min(s.at) FROM x_cna_seen s WHERE s.conversation_id::text = n.source_id AND s.at > n.created_at)
 WHERE n.category = 'CHAT';

UPDATE notification.user_notification n
   SET read_at = n.created_at + (30 + pg_temp.pick('cna:read-after:' || n.id, 0, 1300)) * INTERVAL '1 minute'
 WHERE n.category <> 'CHAT' AND n.created_at < pg_temp.at(3, '00:00')
   AND pg_temp.rnd('cna:read:' || n.id) < CASE WHEN n.severity = 'WARN' THEN 0.7 ELSE 0.85 END;

UPDATE notification.user_notification
   SET read_at = NULL
 WHERE read_at IS NOT NULL AND (read_at >= pg_temp.at(0, '00:00') OR read_at < pg_temp.at(88, '00:00'));

-- ---------------------------------------------------------------------------------------------
-- 80.5 The delivery log (notification.delivery_log): the producer's production order (RecordedDelivery, reference
--      production-order:{id}:sent) and the customer mails (CustomerEmailLog, reference customer-mail:{code}:{source});
--      a few first attempts failed and went out on the retry a minute later.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_cna_mail (ref TEXT, template TEXT, recipient TEXT, at TIMESTAMPTZ, retried BOOLEAN,
                              failure TEXT) ON COMMIT DROP;

INSERT INTO x_cna_mail
SELECT 'production-order:' || p.id || ':sent', 'production_order_sent', pr.email, p.sent_at + INTERVAL '4 seconds',
       pg_temp.rnd('cna:mail-retry:po:' || o.key) < 0.08, 'MAIL_SEND_FAILED'
  FROM h_sent o JOIN ordering.production_order p ON p.id = o.po_id JOIN ordering.producer pr ON pr.id = p.producer_id;

-- OrderPlaced is published when the contract is signed (Order.contractSigned).
INSERT INTO x_cna_mail
SELECT 'customer-mail:order.placed:' || o.id, 'order.placed', cu.email, co.contract_signed_at + INTERVAL '3 seconds',
       pg_temp.rnd('cna:mail-retry:placed:' || o.key) < 0.05, 'SMTP_SEND_FAILED'
  FROM h_signed o JOIN ordering.customer_order co ON co.id = o.id JOIN customer.customer cu ON cu.id = o.customer_id
 WHERE co.contract_signed_at IS NOT NULL;

-- ShipmentStatusChanged, HANDED_OVER and DELIVERED: one message per change, named by the event's own id.
INSERT INTO x_cna_mail
SELECT 'customer-mail:shipment.handed-over:' || pg_temp.sid('cna:shipment-event:' || o.key || ':HANDED_OVER'),
       'shipment.handed-over', cu.email, s.handed_over_at + INTERVAL '5 seconds',
       pg_temp.rnd('cna:mail-retry:handed:' || o.key) < 0.05, 'SMTP_SEND_FAILED'
  FROM h_shipped o JOIN fulfillment.shipment s ON s.id = pg_temp.sid('shipment:' || o.key)
  JOIN customer.customer cu ON cu.id = o.customer_id;

INSERT INTO x_cna_mail
SELECT 'customer-mail:shipment.delivered:' || pg_temp.sid('cna:shipment-event:' || o.key || ':DELIVERED'),
       'shipment.delivered', cu.email, s.outcome_at + INTERVAL '5 seconds',
       pg_temp.rnd('cna:mail-retry:delivered:' || o.key) < 0.05, 'SMTP_SEND_FAILED'
  FROM h_delivered o JOIN fulfillment.shipment s ON s.id = pg_temp.sid('shipment:' || o.key)
  JOIN customer.customer cu ON cu.id = o.customer_id;

-- A failed first attempt: not final, retried not before a minute later (no external reference: only a SENT row
-- carries it).
INSERT INTO notification.delivery_log (id, template_code, channel, recipient, status, error, sent_at, version,
                                       created_at, created_by, external_reference, operation_reference, terminal,
                                       delivery_generation, attempt_number, provider, next_attempt_at)
SELECT pg_temp.sid('cna:mail-failed:' || ref), template, 'EMAIL', recipient, 'FAILED', failure, NULL, 0, at, 'system',
       NULL, ref, FALSE, 0, 1, NULL, at + INTERVAL '1 minute'
  FROM x_cna_mail WHERE retried AND at < pg_temp.at(0, '00:00');

INSERT INTO notification.delivery_log (id, template_code, channel, recipient, status, sent_at, version, created_at,
                                       created_by, external_reference, operation_reference, terminal,
                                       delivery_generation, attempt_number, provider)
SELECT pg_temp.sid('cna:mail:' || ref), template, 'EMAIL', recipient, 'SENT',
       at + CASE WHEN retried THEN INTERVAL '65 seconds' ELSE INTERVAL '0' END, 0,
       at + CASE WHEN retried THEN INTERVAL '65 seconds' ELSE INTERVAL '0' END, 'system', ref, ref, FALSE, 0,
       CASE WHEN retried THEN 2 ELSE 1 END, 'SMTP'
  FROM x_cna_mail WHERE at < pg_temp.at(0, '00:00');

-- notification.template: the codes the delivery log names, worded as the bundle words them (read by nothing).
INSERT INTO notification.template (id, code, channel, subject, body, locale, active, version, created_at, created_by)
SELECT pg_temp.sid('cna:template:' || t.code), t.code, 'EMAIL', t.subject, t.body, 'vi', TRUE, 0, pg_temp.at(229, '09:00'),
       'admin'
  FROM (VALUES
    ('order.placed', 'Đơn hàng %s đã được tiếp nhận',
     E'Chúng tôi đã tiếp nhận đơn hàng của Quý khách\nCảm ơn Quý khách đã đặt hàng. Đơn hàng %s đã được ghi nhận với các thông tin dưới đây.'),
    ('shipment.handed-over', 'Đơn hàng %s đã được bàn giao cho đơn vị vận chuyển',
     E'Hàng đã được bàn giao cho đơn vị vận chuyển\nHàng của đơn hàng %s đã rời kho và được bàn giao cho đơn vị vận chuyển.'),
    ('shipment.delivered', 'Đơn hàng %s đã được giao thành công',
     E'Hàng đã được giao thành công\nHàng của đơn hàng %s đã được giao đến Quý khách.'),
    ('chat.reply', 'Sale đã trả lời anh/chị',
     E'Anh/chị có câu trả lời mới từ Sale\nBộ phận Sale đã trả lời tin nhắn của anh/chị trong hội thoại.%s'),
    ('production_order_sent', '[Đơn sản xuất] %s - cần hàng ngày %s',
     E'Kính gửi %s,\nChúng tôi gửi quý đơn vị Đơn sản xuất %s. Phiếu đặt hàng sản xuất (PDF) được đính kèm thư này; phiếu và file in cũng tải được theo các đường dẫn bên dưới.\nVui lòng trả lời thư này để xác nhận nhận đơn và ngày hoàn thành dự kiến.'))
       AS t (code, subject, body);

-- notification.preference: per-user channel choices. Nothing in the application reads or writes this table yet
-- (NotificationPreferenceJpaRepository is a starter stub); a few rows so the table is not empty.
INSERT INTO notification.preference (id, user_id, channel, enabled, version, created_at, created_by, last_modified_at,
                                     last_modified_by)
SELECT pg_temp.sid('cna:preference:' || p.username || ':' || p.channel), u.id, p.channel, p.enabled,
       CASE WHEN p.changed IS NOT NULL THEN 1 ELSE 0 END, pg_temp.at(p.created, '08:45'), p.username,
       CASE WHEN p.changed IS NOT NULL THEN pg_temp.at(p.changed, '16:20') END,
       CASE WHEN p.changed IS NOT NULL THEN p.username END
  FROM (VALUES ('ketoan', 'EMAIL', FALSE, 200, 61), ('ketoan', 'PUSH', TRUE, 200, NULL),
               ('sales1', 'EMAIL', TRUE, 190, NULL), ('sales1', 'SMS', FALSE, 190, NULL),
               ('quanly.bd', 'PUSH', TRUE, 180, NULL), ('dieuphoi', 'EMAIL', TRUE, 175, 33),
               ('kh.caphemoc', 'EMAIL', TRUE, 120, NULL), ('kh.trasuamay', 'SMS', FALSE, 110, NULL))
       AS p (username, channel, enabled, created, changed)
  JOIN identity.app_user u ON u.username = p.username;

-- ---------------------------------------------------------------------------------------------
-- 80.6 The audit log (platform.audit_log), as AuditAspect writes it: the @Auditable methods only, the acting user's id
--      and username (none and 'system' for a sign-in, made before anyone is authenticated), the request's client
--      address and correlation id; security_relevant for LOGIN, LOGOUT, GRANT, REVOKE, EXPORT and DELETE. A sample of
--      the orders (one in ten) through their whole chain, inside AuditPurger's 180 days (730 for security rows).
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_cna_audit (key TEXT, actor TEXT, action TEXT, rtype TEXT, rid TEXT, outcome TEXT, details TEXT,
                               at TIMESTAMPTZ) ON COMMIT DROP;
CREATE TEMP TABLE x_cna_aud_order ON COMMIT DROP AS SELECT * FROM h_order WHERE seq % 10 = 0;

INSERT INTO x_cna_audit
SELECT 'quote-create:' || o.key, o.sales, 'CREATE', 'quote', o.quote_id::text, 'SUCCESS', NULL, pg_temp.at(o.s + 7, '09:00')
  FROM x_cna_aud_order o
UNION ALL
SELECT 'quote-send:' || o.key, o.sales, 'TRANSITION', 'quote', o.quote_id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s + 7, '10:00')
  FROM x_cna_aud_order o WHERE o.st >= 1
UNION ALL
SELECT 'quote-accept:' || o.key, o.cust_user, 'APPROVE', 'quote', o.quote_id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s + 6, '15:00')
  FROM x_cna_aud_order o WHERE o.st >= 2
UNION ALL
SELECT 'quote-changes:' || o.key, o.cust_user, 'REJECT', 'quote', o.quote_id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s + 6, '15:00')
  FROM x_cna_aud_order o WHERE o.stage = 'Q_CHANGES'
UNION ALL
SELECT 'quote-cancel:' || o.key, o.sales, 'TRANSITION', 'quote', o.quote_id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s + 6, '11:00')
  FROM x_cna_aud_order o WHERE o.stage = 'Q_CANCELLED'
UNION ALL
SELECT 'quote-convert:' || o.key, o.sales, 'CREATE', 'quote', o.quote_id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s + 5, '09:30')
  FROM x_cna_aud_order o WHERE o.st >= 2
UNION ALL
SELECT 'order-cancel:' || o.key, o.sales, 'TRANSITION', 'order', o.id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s + 4, '10:00')
  FROM x_cna_aud_order o WHERE o.stage = 'O_CANCELLED'
UNION ALL
SELECT 'contract-create:' || o.key, o.sales, 'CREATE', 'sales-contract', o.contract_id::text, 'SUCCESS',
       '{"orderId":"' || o.id || '"}', pg_temp.at(o.s + 4, '10:00')
  FROM x_cna_aud_order o WHERE o.st >= 3
UNION ALL
SELECT 'contract-send:' || o.key, o.sales, 'TRANSITION', 'sales-contract', o.contract_id::text, 'SUCCESS',
       '{"orderId":"' || o.id || '","to":"SENT"}', pg_temp.at(o.s + 3, '09:00')
  FROM x_cna_aud_order o WHERE o.st >= 3
UNION ALL
SELECT 'contract-sign:' || o.key, o.sales, 'APPROVE', 'sales-contract', o.contract_id::text, 'SUCCESS',
       '{"orderId":"' || o.id || '","to":"SIGNED"}', pg_temp.at(o.s, '10:00')
  FROM x_cna_aud_order o WHERE o.st >= 4
UNION ALL
SELECT 'receipt:' || r.id, pg_temp.cna_uname(r.recorded_by), 'CREATE', 'payment-receipt', r.id::text, 'SUCCESS', NULL,
       r.recorded_at
  FROM x_cna_aud_order o JOIN payment.payment_receipt r
    ON r.id IN (pg_temp.sid('receipt:' || o.key || ':1'), pg_temp.sid('receipt:' || o.key || ':2'))
UNION ALL
SELECT 'warehouse:' || o.key, o.sales, 'UPDATE', 'order-shipping-warehouse', o.id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s - 1, '14:00')
  FROM x_cna_aud_order o WHERE o.st >= 5
UNION ALL
SELECT 'po-create:' || o.key, 'dieuphoi', 'CREATE', 'production-order', o.po_id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s - 1, '15:00')
  FROM x_cna_aud_order o WHERE o.st >= 5
UNION ALL
SELECT 'po-send:' || o.key, 'dieuphoi', 'TRANSITION', 'production-order', o.po_id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s - 2, '10:00')
  FROM x_cna_aud_order o WHERE o.st >= 6
UNION ALL
SELECT 'po-accept:' || o.key, 'dieuphoi', 'TRANSITION', 'production-order', o.po_id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s - 3, '09:00')
  FROM x_cna_aud_order o WHERE o.st >= 7
UNION ALL
SELECT 'po-start:' || o.key, 'dieuphoi', 'TRANSITION', 'production-order', o.po_id::text, 'SUCCESS', NULL,
       pg_temp.at(o.s - 4, '08:00')
  FROM x_cna_aud_order o WHERE o.st >= 8
UNION ALL
SELECT 'gr-create:' || o.key, o.kho, 'CREATE', 'goods-receipt', pg_temp.sid('receipt-goods:' || o.key)::text,
       'SUCCESS', NULL, pg_temp.at(o.r, '08:00')
  FROM x_cna_aud_order o WHERE o.st >= 9
UNION ALL
SELECT 'gr-lines:' || o.key, o.kho, 'UPDATE', 'goods-receipt', pg_temp.sid('receipt-goods:' || o.key)::text,
       'SUCCESS', NULL, pg_temp.at(o.r, '08:30')
  FROM x_cna_aud_order o WHERE o.st >= 9
UNION ALL
SELECT 'gr-confirm:' || o.key, o.kho, 'TRANSITION', 'goods-receipt', pg_temp.sid('receipt-goods:' || o.key)::text,
       'SUCCESS', NULL, pg_temp.at(o.r, '09:00')
  FROM x_cna_aud_order o WHERE o.st >= 9
UNION ALL
SELECT 'release:' || o.key, o.mgr, 'TRANSITION', 'order', o.id::text, 'SUCCESS', NULL, pg_temp.at(o.d + 3, '09:00')
  FROM x_cna_aud_order o WHERE o.st >= 11
UNION ALL
SELECT 'lp-create:' || o.key, o.mgr, 'CREATE', 'load-plan', pg_temp.sid('load-plan:' || o.key)::text, 'SUCCESS', NULL,
       pg_temp.at(o.d + 1, '10:00')
  FROM x_cna_aud_order o WHERE o.st >= 14
UNION ALL
SELECT 'lp-calculate:' || o.key, o.mgr, 'TRANSITION', 'load-plan', pg_temp.sid('load-plan:' || o.key)::text,
       'SUCCESS', NULL, pg_temp.at(o.d + 1, '10:05')
  FROM x_cna_aud_order o WHERE o.st >= 14
UNION ALL
SELECT 'lp-confirm:' || o.key, o.mgr, 'TRANSITION', 'load-plan', pg_temp.sid('load-plan:' || o.key)::text,
       'SUCCESS', NULL, pg_temp.at(o.d + 1, '10:10')
  FROM x_cna_aud_order o WHERE o.st >= 14;

-- A contract sent twice in one minute: the second attempt refused on the version the first had moved
-- (OPTIMISTIC_LOCK), written in a transaction of its own (AuditWriter.writeFailure).
INSERT INTO x_cna_audit
SELECT 'contract-send-stale:' || o.key, o.sales, 'TRANSITION', 'sales-contract', NULL, 'FAILURE',
       '{"error":"BusinessException","message":"The contract was changed by someone else; reload it","context":{"orderId":"'
           || o.id || '","to":"SENT"}}',
       pg_temp.at(o.s + 3, '09:00') + INTERVAL '40 seconds'
  FROM x_cna_aud_order o WHERE o.st >= 3 AND o.seq % 30 = 0;

-- Customers taken on by their Sales (CustomerOnboardingService.create).
INSERT INTO x_cna_audit
SELECT 'customer:' || c.code, c.sales, 'CREATE', 'customer', c.id::text, 'SUCCESS', NULL, pg_temp.at(c.since, '09:00')
  FROM s_customer c;

-- Warehouse staff given their warehouse when the accounts were made (IdentityServiceImpl.assignWarehouses).
INSERT INTO x_cna_audit
SELECT 'user-warehouses:' || s.username, 'admin', 'GRANT', 'user-warehouses', pg_temp.staff(s.username)::text,
       'SUCCESS', NULL, pg_temp.at(230, '08:05')
  FROM s_staff s WHERE s.wh IS NOT NULL;

-- The two accounts that are no longer active (IdentityServiceImpl.changeUserStatus).
INSERT INTO x_cna_audit VALUES
    ('user-lock', 'admin', 'TRANSITION', 'user', pg_temp.staff('nv.bikhoa')::text, 'SUCCESS',
     '{"change":"LOCK","reason":"Tạm khoá theo đề nghị của quản lý kho trong thời gian xác minh"}',
     pg_temp.at(40, '16:20')),
    ('user-disable', 'admin', 'TRANSITION', 'user', pg_temp.staff('nv.nghiviec')::text, 'SUCCESS',
     '{"change":"DISABLE","reason":"Nhân viên đã nghỉ việc, giữ tài khoản để tra lịch sử"}',
     pg_temp.at(25, '17:05'));

-- The accountant's monthly export of receipts (PaymentReceiptExportService.export).
INSERT INTO x_cna_audit
SELECT 'export:' || m, 'ketoan', 'EXPORT', 'payment-receipt', NULL, 'SUCCESS',
       '{"customerId":null,"status":null,"reference":null,"receivedFrom":"'
           || to_char(date_trunc('month', pg_temp.day(m * 30)), 'YYYY-MM-DD') || '","receivedTo":"'
           || to_char(date_trunc('month', pg_temp.day(m * 30)) + INTERVAL '1 month - 1 day', 'YYYY-MM-DD') || '"}',
       pg_temp.at(m * 30 - 31 + extract(day FROM pg_temp.day(m * 30))::int, '16:45')
  FROM generate_series(1, 5) m;

-- Sign-ins of the last ten days (IdentityServiceImpl.login): most mornings for each active staff member, a mistyped
-- password now and then, and the locked account refused.
INSERT INTO x_cna_audit
SELECT 'login:' || s.username || ':' || g.day, NULL, 'LOGIN', 'user', s.username, 'SUCCESS', NULL,
       pg_temp.at(g.day, '07:' || lpad((40 + pg_temp.pick('cna:login:' || s.username || g.day, 0, 19))::text, 2, '0'))
  FROM s_staff s CROSS JOIN generate_series(1, 10) AS g (day)
 WHERE s.status = 'ACTIVE' AND extract(isodow FROM pg_temp.day(g.day)) <> 7
   AND pg_temp.rnd('cna:login-day:' || s.username || g.day) < 0.75;

INSERT INTO x_cna_audit
SELECT 'login-failed:' || s.username || ':' || g.day, NULL, 'LOGIN', 'user', s.username, 'FAILURE',
       '{"error":"BusinessException","message":"Invalid username or password"}',
       pg_temp.at(g.day, '07:39')
  FROM s_staff s CROSS JOIN generate_series(1, 10) AS g (day)
 WHERE s.status = 'ACTIVE' AND extract(isodow FROM pg_temp.day(g.day)) <> 7
   AND pg_temp.rnd('cna:login-day:' || s.username || g.day) < 0.75
   AND pg_temp.rnd('cna:login-typo:' || s.username || g.day) < 0.08;

INSERT INTO x_cna_audit VALUES
    ('login-locked:1', NULL, 'LOGIN', 'user', 'nv.bikhoa', 'FAILURE',
     '{"error":"BusinessException","message":"Invalid username or password"}', pg_temp.at(39, '07:52')),
    ('login-locked:2', NULL, 'LOGIN', 'user', 'nv.bikhoa', 'FAILURE',
     '{"error":"BusinessException","message":"Invalid username or password"}', pg_temp.at(39, '07:53'));

-- The customers who accepted a sampled quote signed in to the portal a few minutes before.
INSERT INTO x_cna_audit
SELECT 'login-customer:' || o.key, NULL, 'LOGIN', 'user', o.cust_user, 'SUCCESS', NULL, pg_temp.at(o.s + 6, '14:52')
  FROM x_cna_aud_order o WHERE o.st >= 2 OR o.stage = 'Q_CHANGES';

INSERT INTO platform.audit_log (id, actor_id, actor_name, action, resource_type, resource_id, outcome, correlation_id,
                                client_address, details, occurred_at, security_relevant)
SELECT pg_temp.sid('cna:audit:' || a.key), CASE WHEN a.actor IS NOT NULL THEN u.id END, coalesce(a.actor, 'system'),
       a.action, a.rtype, a.rid, a.outcome,
       pg_temp.sid('cna:correlation:' || a.key)::text,
       CASE WHEN cu.code IS NOT NULL THEN '198.51.100.' || (10 + cu.idx)
            ELSE '203.0.113.' || (10 + coalesce((SELECT count(*) FROM s_staff s2 WHERE s2.username < who.username), 0)) END,
       a.details, a.at, a.action IN ('LOGIN', 'LOGOUT', 'GRANT', 'REVOKE', 'EXPORT', 'DELETE')
  FROM x_cna_audit a
 CROSS JOIN LATERAL (SELECT coalesce(a.actor, a.rid) AS username) who
  LEFT JOIN identity.app_user u ON u.username = a.actor
  LEFT JOIN s_customer cu ON cu.username = who.username
 WHERE a.at < pg_temp.at(0, '00:00')
   AND (a.at >= pg_temp.at(179, '00:00')
        OR (a.action IN ('LOGIN', 'LOGOUT', 'GRANT', 'REVOKE', 'EXPORT', 'DELETE') AND a.at >= pg_temp.at(729, '00:00')));

-- ---------------------------------------------------------------------------------------------
-- This section's helpers go with it.
-- ---------------------------------------------------------------------------------------------
DROP FUNCTION pg_temp.cna_sla_notice(UUID, BOOLEAN, TIMESTAMPTZ, TIMESTAMPTZ);
DROP FUNCTION pg_temp.cna_mark(UUID, UUID, TEXT, BIGINT, TIMESTAMPTZ);
DROP FUNCTION pg_temp.cna_event(UUID, INTEGER, TEXT, TEXT, UUID, TIMESTAMPTZ, TEXT, TEXT, UUID, UUID, UUID, TEXT, TEXT,
                                TEXT);
DROP FUNCTION pg_temp.cna_uname(UUID);
DROP FUNCTION pg_temp.cna_vnd(NUMERIC);
DROP FUNCTION pg_temp.cna_iso(TIMESTAMPTZ);
DROP FUNCTION pg_temp.cna_holders(TEXT, UUID);
DROP FUNCTION pg_temp.cna_bplus(TIMESTAMPTZ, INTEGER);
DROP FUNCTION pg_temp.cna_bopen(TIMESTAMPTZ);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 90. The number counters, last: each day's counter stands at the highest number this seed gave out that day
--     (SO- orders, RMA- returns, and the platform.document_sequence types: QT, PRD, GR, LPN, LD, LP, CC, ADJ, EINV), as
--     the application leaves them. The seed numbers its documents from 7000 up and the application counts from 1, so
--     the two could not collide on a past day anyway; the counters make the tables say what the documents say.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE x_seq_used (prefix TEXT, day DATE, n BIGINT) ON COMMIT DROP;

DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT table_schema, table_name, column_name
          FROM information_schema.columns
         WHERE table_schema IN ('ordering', 'procurement', 'inventory', 'fulfillment', 'invoicing', 'payment')
           AND data_type IN ('character varying', 'text')
           AND (column_name IN ('code', 'lpn', 'quote_number', 'order_number', 'rma_number', 'receipt_number',
                                'load_number', 'plan_number', 'adjustment_number', 'count_number', 'invoice_number')
                OR column_name LIKE '%\_number' OR column_name LIKE '%\_code')
           AND (table_schema, table_name) IN (SELECT table_schema, table_name FROM information_schema.tables
                                               WHERE table_type = 'BASE TABLE')
    LOOP
        EXECUTE format(
            'INSERT INTO x_seq_used
             SELECT m[1], to_date(m[2], ''YYYYMMDD''), m[3]::bigint
               FROM (SELECT regexp_match(%I::text, ''^(SO|RMA|QT|PRD|GR|LPN|LD|LP|CC|ADJ|EINV)-([0-9]{8})-([0-9]{4,7})$'') m
                       FROM %I.%I) x
              WHERE m IS NOT NULL', c.column_name, c.table_schema, c.table_name);
    END LOOP;
END $$;

INSERT INTO ordering.order_number_sequence (sequence_date, last_value)
SELECT day, max(n) FROM x_seq_used WHERE prefix = 'SO' GROUP BY day;

INSERT INTO ordering.return_number_sequence (sequence_date, last_value)
SELECT day, max(n) FROM x_seq_used WHERE prefix = 'RMA' GROUP BY day;

INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
SELECT prefix, day, max(n) FROM x_seq_used WHERE prefix NOT IN ('SO', 'RMA') GROUP BY prefix, day;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- <<< sections 10-90 <<<

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
