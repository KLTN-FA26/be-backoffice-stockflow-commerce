-- =============================================================================
-- Demo WF-05, second round (seed gaps G-8, G-9; demo-run 24/11/2026 §BLOCKED): the orders the scenes "open a seeded
-- order" stand on, each walked through the same chain as V20261222000500 (this file is that one's chain with other
-- orders): quote sent and accepted, converted, contract sent and signed, deposit, shipping warehouse DNI, one production
-- order per batch sent by the coordinator and received in LPN cartons, put away, held, released, picked by LPN,
-- checked and labelled, one load plan per truck, booked on DEMO-WEBHOOK, loaded, handed over at 08:00 and delivered at
-- 15:00 (today's deliveries: half an hour and ten minutes before the build). Demo only (db/demo-wf05, see
-- V20261224900100). What happens to each after its delivery - its e-invoice, its later payments, its return - is in
-- V20261224900300 and V20261224900400.
--
--   WF5-C    Mộc, step 8: 3 cartons delivered today (the night run). Its contract fixed VAT 10% (quote and order line at 10%);
--            the delivery day's rate is 8% (NĐ 174/2025), so its invoice is drawn up TAX_RATE_DIFFERS_FROM_CONTRACT.
--   WF5-D    Mộc, step 9 and HĐ-2: 3 cartons delivered yesterday.
--   WF5-HD1B Mộc, HĐ-1 "waiting at the provider": 2 cartons delivered today (the night run).
--   WF5-HD1C Mộc, HĐ-1 "the provider has no record": 2 cartons delivered today (the night run).
--   WF5-HD3R Mộc, HĐ-3 step 5 (a replacement invoice): 3 cartons delivered 4 days ago.
--   WF5-HD5  Mộc, HĐ-5 (an invoice of the old provider): 3 cartons delivered 2 days ago.
--   WF5-HD7  Mộc, HĐ-7: 2 cartons of LY-TRE-DEMO-MOC (no tax class, V20261224900100) delivered yesterday.
--   WF5-E    Mộc, step 11 "Hoàn tất": 3 cartons delivered 3 days ago, complaint period 1 day.
--   WF5-E2   Mộc, step 11 "Đã đóng": 3 cartons delivered 33 days ago, complaint period 1 day (completed 31 days ago).
--   WF5-TT2  Mộc, TT-2: 3 cartons delivered 31 days ago; 70% after delivery in 30 days - due yesterday.
--   WF5-TT5  Mộc, TT-5: two batches of 3, a plate fee of 1,000,000, one installment after each; batch 1 delivered 3 days ago, batch 2 produced,
--            received and held at DNI, never released - "Đóng phần còn lại" closes it.
--   WF5-TH1  Mộc, TH-1 (no longer on WF5-A, so step 11 is not spent on it): two batches of 5, one installment after
--            each; batch 1 delivered yesterday, batch 2 held at DNI, never released.
--   WF5-DD1A, WF5-DD1B  Mộc, DĐ-1: two batches of 3 each, delivered 42 and 38 days ago; the return of one carton of
--            batch 1 and its refund follow in V20261224900400.
-- Numbers: orders SO-...-9600nn, quotes QT-...-96nn, receipts and production orders ...-96nn, LPNs LPN-...-96nnnnn,
-- loads LD-...-96nn - apart from V20261222000500's 95xx.
-- =============================================================================

CREATE FUNCTION pg_temp.wid(key TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT md5('demo:wf05:' || key)::uuid $$;

CREATE FUNCTION pg_temp.today() RETURNS DATE LANGUAGE sql STABLE AS
$$ SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date $$;

CREATE FUNCTION pg_temp.at(days_ago INTEGER, hhmi TEXT) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT ((pg_temp.today() - days_ago) + hhmi::time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

CREATE FUNCTION pg_temp.day(days_ago INTEGER) RETURNS DATE LANGUAGE sql STABLE AS
$$ SELECT pg_temp.today() - days_ago $$;

CREATE FUNCTION pg_temp.ymd(days_ago INTEGER) RETURNS TEXT LANGUAGE sql STABLE AS
$$ SELECT to_char(pg_temp.today() - days_ago, 'YYYYMMDD') $$;

CREATE FUNCTION pg_temp.staff(username TEXT) RETURNS UUID LANGUAGE sql STABLE AS
$$ SELECT id FROM identity.app_user WHERE username = $1 $$;

-- When a batch delivered d days ago was handed over and delivered: 08:00 and 15:00; today's deliveries were a night
-- run (00:30 and 01:00, or half an hour and ten minutes before the build if that is earlier), so they are there however
-- early the database is built - and an invoice sent at 01:05 can be taken back from 04:05 (withdraw-after, HĐ-1).
CREATE FUNCTION pg_temp.ho(days_ago INTEGER) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT CASE WHEN days_ago = 0 THEN least(pg_temp.at(0, '00:30'), now() - INTERVAL '30 minutes')
               ELSE pg_temp.at(days_ago, '08:00') END $$;

CREATE FUNCTION pg_temp.dl(days_ago INTEGER) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT CASE WHEN days_ago = 0 THEN least(pg_temp.at(0, '01:00'), now() - INTERVAL '10 minutes')
               ELSE pg_temp.at(days_ago, '15:00') END $$;

-- ---------------------------------------------------------------------------------------------
-- 1. The orders and their batches, days before the build day
-- ---------------------------------------------------------------------------------------------
-- s: the contract signed. plate / ship: the fee lines. per_batch: one installment after each delivery (else one after
-- the last). window_days: the complaint period written in the contract (null: the configured default).
CREATE TEMP TABLE w_order (key TEXT PRIMARY KEY, seq INTEGER, cust TEXT, s INTEGER, plate NUMERIC, ship NUMERIC,
                           per_batch BOOLEAN, window_days INTEGER, vat NUMERIC, skuk TEXT, sku_code TEXT) ON COMMIT DROP;
INSERT INTO w_order VALUES
    ('WF5-C', 1, 'MOC', 11, 0, 300000, FALSE, NULL, 10, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-D', 2, 'MOC', 12, 0, 300000, FALSE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-HD1B', 3, 'MOC', 11, 0, 200000, FALSE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-HD1C', 4, 'MOC', 11, 0, 200000, FALSE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-HD3R', 5, 'MOC', 15, 0, 300000, FALSE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-HD5', 6, 'MOC', 13, 0, 300000, FALSE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-HD7', 7, 'MOC', 12, 0, 200000, FALSE, NULL, 8, 'TRE-MOC', 'LY-TRE-DEMO-MOC'),
    ('WF5-E', 8, 'MOC', 14, 0, 300000, FALSE, 1, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-E2', 9, 'MOC', 44, 0, 300000, FALSE, 1, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-TT2', 10, 'MOC', 42, 0, 300000, FALSE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-TT5', 11, 'MOC', 14, 1000000, 400000, TRUE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-TH1', 12, 'MOC', 12, 0, 400000, TRUE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-DD1A', 13, 'MOC', 53, 0, 400000, FALSE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC'),
    ('WF5-DD1B', 14, 'MOC', 53, 0, 400000, FALSE, NULL, 8, 'MOC', 'LY-GIAY-DEMO-12OZ-MOC');

-- r: received at DNI. d: delivered. load_seq: its load's code; null for a batch never released (17 BR-29: it waits,
-- held at DNI, for Sales to release or close it).
CREATE TEMP TABLE w_batch (key TEXT, bseq INTEGER, cartons INTEGER, r INTEGER, d INTEGER, load_seq INTEGER,
                           PRIMARY KEY (key, bseq)) ON COMMIT DROP;
INSERT INTO w_batch VALUES
    ('WF5-C', 1, 3, 5, 0, 9601), ('WF5-D', 1, 3, 6, 1, 9602), ('WF5-HD1B', 1, 2, 5, 0, 9603),
    ('WF5-HD1C', 1, 2, 5, 0, 9604), ('WF5-HD3R', 1, 3, 9, 4, 9605), ('WF5-HD5', 1, 3, 7, 2, 9606),
    ('WF5-HD7', 1, 2, 6, 1, 9607), ('WF5-E', 1, 3, 8, 3, 9608), ('WF5-E2', 1, 3, 38, 33, 9609),
    ('WF5-TT2', 1, 3, 36, 31, 9610),
    ('WF5-TT5', 1, 3, 8, 3, 9611), ('WF5-TT5', 2, 3, 8, NULL, NULL),
    ('WF5-TH1', 1, 5, 6, 1, 9612), ('WF5-TH1', 2, 5, 6, NULL, NULL),
    ('WF5-DD1A', 1, 3, 47, 42, 9613), ('WF5-DD1A', 2, 3, 43, 38, 9614),
    ('WF5-DD1B', 1, 3, 47, 42, 9615), ('WF5-DD1B', 2, 3, 43, 38, 9616);

ALTER TABLE w_order
    ADD COLUMN id UUID, ADD COLUMN customer_id UUID, ADD COLUMN username TEXT, ADD COLUMN cname TEXT,
    ADD COLUMN contact TEXT, ADD COLUMN phone TEXT, ADD COLUMN line1 TEXT, ADD COLUMN ward TEXT, ADD COLUMN ward_code TEXT,
    ADD COLUMN tax_code TEXT, ADD COLUMN sku TEXT, ADD COLUMN unit_id UUID, ADD COLUMN snapshot_id UUID,
    ADD COLUMN line_id UUID, ADD COLUMN quote_id UUID, ADD COLUMN contract_id UUID, ADD COLUMN cartons INTEGER,
    ADD COLUMN goods NUMERIC, ADD COLUMN goods_tax NUMERIC, ADD COLUMN plate_tax NUMERIC, ADD COLUMN ship_tax NUMERIC,
    ADD COLUMN total NUMERIC, ADD COLUMN deposit NUMERIC, ADD COLUMN order_number TEXT, ADD COLUMN quote_number TEXT,
    ADD COLUMN contract_number TEXT, ADD COLUMN batches INTEGER, ADD COLUMN window_start DATE;

UPDATE w_order o
   SET id = pg_temp.wid('order:' || o.key), customer_id = c.id, username = u.username, cname = c.full_name,
       contact = a.recipient_name, phone = a.phone, line1 = a.line1, ward = a.ward, ward_code = a.ward_code,
       tax_code = c.tax_code, sku = o.sku_code, unit_id = pg_temp.wid('unit:' || o.skuk),
       snapshot_id = pg_temp.wid('snapshot:' || o.skuk), line_id = pg_temp.wid('order-line:' || o.key),
       quote_id = pg_temp.wid('quote:' || o.key), contract_id = pg_temp.wid('contract:' || o.key),
       order_number = 'SO-' || pg_temp.ymd(o.s + 5) || '-' || (960000 + o.seq),
       quote_number = 'QT-' || pg_temp.ymd(o.s + 7) || '-' || (9600 + o.seq)
  FROM customer.customer c
  JOIN identity.app_user u ON u.id = c.user_id
  JOIN customer.address a ON a.customer_id = c.id AND a.type = 'SHIPPING'
 WHERE c.id = pg_temp.wid('customer:' || o.cust);

UPDATE w_order o
   SET cartons = b.cartons, batches = b.n
  FROM (SELECT key, sum(cartons) AS cartons, count(*) AS n FROM w_batch GROUP BY key) b
 WHERE b.key = o.key;

-- VAT 8% on the goods and on both fees until 31/12/2026 (NĐ 174/2025; ordering.tax_rate_period of STANDARD).
UPDATE w_order SET goods = cartons * 1000 * 1200;
UPDATE w_order SET goods_tax = round(goods * vat / 100, 0), plate_tax = round(plate * vat / 100, 0),
                   ship_tax = round(ship * vat / 100, 0);
UPDATE w_order SET total = goods + goods_tax + plate + plate_tax + ship + ship_tax,
                   contract_number = 'HD-' || substring(order_number FROM 4) || '-1';
UPDATE w_order SET deposit = round(total * 30 / 100, 0);

ALTER TABLE w_batch
    ADD COLUMN bkey TEXT, ADD COLUMN batch_id UUID, ADD COLUMN po_id UUID, ADD COLUMN po_line_id UUID,
    ADD COLUMN po_code TEXT, ADD COLUMN window_start DATE, ADD COLUMN window_end DATE, ADD COLUMN amount NUMERIC,
    ADD COLUMN released INTEGER, ADD COLUMN picked INTEGER, ADD COLUMN planned INTEGER;

-- A batch on its truck now: released three days ago, picked and labelled two days ago, its truck planned, booked and
-- loaded yesterday; its window opened yesterday, so it may be handed over today. A delivered one: as V20261128000200.
UPDATE w_batch b
   SET bkey = b.key || '-' || b.bseq, batch_id = pg_temp.wid('batch:' || b.key || ':' || b.bseq),
       po_id = pg_temp.wid('po:' || b.key || ':' || b.bseq), po_line_id = pg_temp.wid('po-line:' || b.key || ':' || b.bseq),
       po_code = 'PRD-' || pg_temp.ymd(o.s - 1) || '-' || (9600 + o.seq * 10 + b.bseq),
       window_start = CASE WHEN b.d IS NOT NULL THEN pg_temp.day(b.d + 1) ELSE pg_temp.day(-7) END,
       window_end = CASE WHEN b.d IS NOT NULL THEN pg_temp.day(b.d - 2) ELSE pg_temp.day(-10) END,
       released = CASE WHEN b.load_seq IS NOT NULL THEN coalesce(b.d + 3, 3) END, picked = coalesce(b.d + 2, 2),
       planned = coalesce(b.d + 1, 1)
  FROM w_order o WHERE o.key = b.key;

UPDATE w_order o SET window_start = (SELECT min(b.window_start) FROM w_batch b WHERE b.key = o.key);

-- What each batch is worth on the invoice side: its goods with VAT; the fees ride on the last batch.
UPDATE w_batch b
   SET amount = b.cartons * 1000 * 1200 + round(b.cartons * 1000 * 1200 * o.vat / 100, 0)
                + CASE WHEN b.bseq = o.batches THEN o.plate + o.plate_tax + o.ship + o.ship_tax ELSE 0 END
  FROM w_order o WHERE o.key = b.key;

-- One row per carton: its LPN, its rows, the bin it was put away in.
CREATE TEMP TABLE w_carton ON COMMIT DROP AS
SELECT b.key, b.bseq, b.bkey, n, pg_temp.wid('lpn:' || b.bkey || ':' || n) AS package_id,
       pg_temp.wid('stock:' || b.bkey || ':' || n) AS stock_item_id,
       pg_temp.wid('putaway:' || b.bkey || ':' || n) AS task_id,
       pg_temp.wid('allocation:' || b.bkey || ':' || n) AS allocation_id,
       pg_temp.wid('pick-line:' || b.bkey || ':' || n) AS pick_line_id,
       pg_temp.wid('package:' || b.bkey || ':' || n) AS shipping_package_id,
       'LPN-' || pg_temp.ymd(b.r) || '-96' || lpad(((o.seq * 10 + b.bseq) * 100 + n)::text, 5, '0') AS lpn,
       -- Spread over the four bins, so no bin goes past its 8.4 m³ beside the cartons already there.
       'DNI-A01-' || (ARRAY['1-A', '2-B', '1-A', '2-B', '1-B', '2-A'])[1 + ((o.seq * 10 + b.bseq + n) % 6)] AS bin
  FROM w_batch b JOIN w_order o ON o.key = b.key
 CROSS JOIN LATERAL generate_series(1, b.cartons) n;

-- ---------------------------------------------------------------------------------------------
-- 2. Quotes (14 §4.1): drawn up and sent by Sales, accepted by the customer's own login
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, revision, valid_until, terms, sent_at,
                            sent_by, responded_at, responded_by, requested_delivery_date, version, created_at,
                            created_by)
SELECT o.quote_id, o.quote_number, pg_temp.wid('quote-request:' || o.key), o.customer_id, 'DRAFT', 1,
       pg_temp.at(o.s - 7, '17:00'), 'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.', NULL, NULL, NULL, NULL,
       o.window_start, 0, pg_temp.at(o.s + 7, '09:00'), pg_temp.staff('demo.sales')::text
  FROM w_order o;

INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton, tax_class,
                                 tax_rate)
SELECT pg_temp.wid('quote-line:' || o.key), o.quote_id, 1, o.sku, o.unit_id, o.cartons, 1000, 'STANDARD', o.vat
  FROM w_order o;

INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
SELECT pg_temp.wid('quote-line:' || o.key), 20, 1200 FROM w_order o;

INSERT INTO ordering.quote_fee_line (id, quote_id, kind, sku, amount, tax_class, tax_rate)
SELECT pg_temp.wid('quote-fee:plate:' || o.key), o.quote_id, 'PLATE_FEE', o.sku, o.plate, 'STANDARD', o.vat
  FROM w_order o WHERE o.plate > 0
UNION ALL
SELECT pg_temp.wid('quote-fee:ship:' || o.key), o.quote_id, 'SHIPPING_FEE', NULL, o.ship, 'STANDARD', o.vat
  FROM w_order o WHERE o.ship > 0;

UPDATE ordering.quote q
   SET status = 'SENT', sent_at = pg_temp.at(o.s + 7, '10:00'), sent_by = pg_temp.staff('demo.sales'), version = 1,
       last_modified_at = pg_temp.at(o.s + 7, '10:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE q.id = o.quote_id;

UPDATE ordering.quote_line l
   SET accepted_cartons = o.cartons, accepted_unit_price = 1200
  FROM w_order o WHERE l.quote_id = o.quote_id;

UPDATE ordering.quote q
   SET status = 'ACCEPTED', responded_at = pg_temp.at(o.s + 6, '15:00'), responded_by = pg_temp.wid('user:' || o.username),
       version = 2, last_modified_at = pg_temp.at(o.s + 6, '15:00'),
       last_modified_by = pg_temp.wid('user:' || o.username)::text
  FROM w_order o WHERE q.id = o.quote_id;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, offer, changed_at,
                                           changed_by)
SELECT pg_temp.wid('quote-history:' || o.key || ':0'), o.quote_id, 1, NULL, 'DRAFT', NULL, NULL,
       pg_temp.at(o.s + 7, '09:00'), pg_temp.staff('demo.sales')
  FROM w_order o
UNION ALL
SELECT pg_temp.wid('quote-history:' || o.key || ':1'), o.quote_id, 1, 'DRAFT', 'SENT', NULL,
       jsonb_build_object(
           'revision', 1,
           'validUntil', to_char(pg_temp.at(o.s - 7, '17:00') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
           'terms', 'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.',
           'requestedDeliveryDate', to_char(o.window_start, 'YYYY-MM-DD'),
           'lines', jsonb_build_array(jsonb_build_object(
               'lineNo', 1, 'sku', o.sku, 'uomConversionId', o.unit_id, 'cartons', o.cartons, 'unitsPerCarton', 1000,
               'taxClass', 'STANDARD', 'taxRate', o.vat,
               'tiers', jsonb_build_array(jsonb_build_object('minQuantity', 20, 'unitPrice', 1200)))),
           'fees', (SELECT coalesce(jsonb_agg(f.fee), '[]'::jsonb) FROM (
                        SELECT jsonb_build_object('kind', 'PLATE_FEE', 'sku', o.sku, 'amount', o.plate,
                                                  'taxClass', 'STANDARD', 'taxRate', o.vat) AS fee WHERE o.plate > 0
                        UNION ALL
                        SELECT jsonb_build_object('kind', 'SHIPPING_FEE', 'sku', NULL, 'amount', o.ship,
                                                  'taxClass', 'STANDARD', 'taxRate', o.vat) WHERE o.ship > 0) f)),
       pg_temp.at(o.s + 7, '10:00'), pg_temp.staff('demo.sales')
  FROM w_order o
UNION ALL
SELECT pg_temp.wid('quote-history:' || o.key || ':2'), o.quote_id, 1, 'SENT', 'ACCEPTED', NULL, NULL,
       pg_temp.at(o.s + 6, '15:00'), pg_temp.wid('user:' || o.username)
  FROM w_order o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 3. Each quote converted into a DRAFT order holding nothing (14 §4.2)
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, contact_name, contact_email, contact_phone,
                                     shipping_recipient_name, shipping_phone, shipping_line1, shipping_ward_code,
                                     shipping_ward_name, shipping_province_code, shipping_province_name,
                                     shipping_country_code, billing_recipient_name, billing_phone, billing_line1,
                                     billing_ward_code, billing_ward_name, billing_province_code, billing_province_name,
                                     billing_country_code, version, created_at, created_by)
SELECT o.id, o.order_number, o.customer_id, pg_temp.wid('order-request:' || o.key), 'DRAFT', o.total,
       o.goods_tax + o.plate_tax + o.ship_tax, 'VND', pg_temp.at(o.s + 5, '09:30'), o.contact,
       'muahang.' || lower(o.cust) || '@stockflow.local', o.phone, o.contact, o.phone, o.line1, o.ward_code, o.ward,
       '79', 'TP. Hồ Chí Minh', 'VN', o.cname, o.phone, o.line1, o.ward_code, o.ward, '79', 'TP. Hồ Chí Minh', 'VN', 0,
       pg_temp.at(o.s + 5, '09:30'), pg_temp.staff('demo.sales')::text
  FROM w_order o;

INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, requested_delivery_date,
                                 uom_conversion_id, cartons, units_per_carton, tax_rate, tax_amount)
SELECT o.line_id, o.id, o.sku, o.cartons * 1000, 1200, 'VND', o.window_start, o.unit_id, o.cartons, 1000, o.vat,
       o.goods_tax
  FROM w_order o;

INSERT INTO ordering.order_fee_line (id, order_id, kind, sku, amount, tax_rate, tax_amount, currency)
SELECT pg_temp.wid('order-fee:plate:' || o.key), o.id, 'PLATE_FEE', o.sku, o.plate, o.vat, o.plate_tax, 'VND'
  FROM w_order o WHERE o.plate > 0
UNION ALL
SELECT pg_temp.wid('order-fee:ship:' || o.key), o.id, 'SHIPPING_FEE', NULL, o.ship, o.vat, o.ship_tax, 'VND'
  FROM w_order o WHERE o.ship > 0;

UPDATE ordering.quote q
   SET status = 'CONVERTED', converted_order_id = o.id, converted_at = pg_temp.at(o.s + 5, '09:30'), version = 3,
       last_modified_at = pg_temp.at(o.s + 5, '09:30'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE q.id = o.quote_id;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, changed_at,
                                           changed_by)
SELECT pg_temp.wid('quote-history:' || o.key || ':3'), o.quote_id, 1, 'ACCEPTED', 'CONVERTED', o.order_number,
       pg_temp.at(o.s + 5, '09:30'), pg_temp.staff('demo.sales')
  FROM w_order o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 4. The contract (14 §4.3): the seller An Phát (V20261221000110), the batches, the plan; sent, then signed
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
                                     seller_address, seller_representative, seller_representative_title, buyer_name,
                                     buyer_tax_code, buyer_address, buyer_representative, buyer_representative_title,
                                     free_sample_rounds, return_window_days, version, created_at, created_by)
SELECT o.contract_id, o.contract_number, o.id, 'DRAFT', 'CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT', '0312345678',
       'Số 268 Đường số 7, Phường Tân Tạo, Thành phố Hồ Chí Minh', 'Trần Quốc An', 'Giám đốc', o.cname, o.tax_code,
       o.line1 || ', ' || o.ward || ', TP. Hồ Chí Minh', o.contact, 'Giám đốc', 2, o.window_days, 0,
       pg_temp.at(o.s + 4, '10:00'), pg_temp.staff('demo.sales')::text
  FROM w_order o;

INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
SELECT b.batch_id, o.contract_id, b.bseq, b.window_start, b.window_end FROM w_batch b JOIN w_order o ON o.key = b.key;

INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
SELECT pg_temp.wid('batch-line:' || b.bkey), b.batch_id, o.line_id, b.cartons
  FROM w_batch b JOIN w_order o ON o.key = b.key;

-- 30% on signing (decision file §4.2); the rest after delivery on the customer's credit (15 BR-11): after the last
-- delivery, or after each one shared by its goods.
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
SELECT pg_temp.wid('plan:' || o.key || ':1'), o.contract_id, 1, 'Đặt cọc khi ký', 'ON_SIGNING', 30, o.deposit, 3, NULL
  FROM w_order o
UNION ALL
SELECT pg_temp.wid('plan:' || o.key || ':2'), o.contract_id, 2, 'Thanh toán sau giao hàng', 'AFTER_DELIVERY', 70,
       o.total - o.deposit, 30, b.batch_id
  FROM w_order o JOIN w_batch b ON b.key = o.key AND b.bseq = o.batches
 WHERE NOT o.per_batch
UNION ALL
SELECT pg_temp.wid('plan:' || o.key || ':' || (b.bseq + 1)), o.contract_id, b.bseq + 1,
       'Thanh toán sau giao hàng đợt ' || b.bseq, 'AFTER_DELIVERY', NULL,
       CASE WHEN b.bseq = o.batches THEN o.total - o.deposit - round((o.total - o.deposit) / o.batches, 0) * (o.batches - 1)
            ELSE round((o.total - o.deposit) / o.batches, 0) END, 30, b.batch_id
  FROM w_order o JOIN w_batch b ON b.key = o.key
 WHERE o.per_batch;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE ordering.sales_contract k
   SET status = 'SENT', sent_at = pg_temp.at(o.s + 3, '09:00'), sent_by = pg_temp.staff('demo.sales'),
       sent_document_key = 'documents/demo/' || o.contract_number || '.pdf', version = 1,
       last_modified_at = pg_temp.at(o.s + 3, '09:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE k.id = o.contract_id;

UPDATE ordering.customer_order co
   SET status = 'PENDING_CONTRACT', version = 1, last_modified_at = pg_temp.at(o.s + 3, '09:00'),
       last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE co.id = o.id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

UPDATE ordering.sales_contract k
   SET status = 'SIGNED', signed_on = pg_temp.day(o.s), signed_at = pg_temp.at(o.s, '10:00'),
       signed_by = pg_temp.staff('demo.sales'), signed_file_key = 'documents/demo/' || o.contract_number || '-ky.pdf',
       signed_file_name = o.contract_number || '-da-ky.pdf', version = 2, last_modified_at = pg_temp.at(o.s, '10:00'),
       last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE k.id = o.contract_id;

UPDATE ordering.customer_order co
   SET status = 'PENDING_PAYMENT', contract_signed_at = pg_temp.at(o.s, '10:00'), placed_at = pg_temp.at(o.s, '10:00'),
       version = 2, last_modified_at = pg_temp.at(o.s, '10:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE co.id = o.id;

-- 15 BR-10, BR-11: the part paid after delivery checked against the customer's credit - within the limit; what the
-- customer's earlier contracts here pay after delivery is the exposure.
INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome, note,
                                         version, created_at, created_by)
SELECT pg_temp.wid('credit-check:' || o.key), o.id, pg_temp.at(o.s, '10:00'), 500000000,
       coalesce((SELECT sum(p.total - p.deposit) FROM w_order p WHERE p.cust = o.cust AND p.s > o.s), 0),
       o.total - o.deposit, 'WITHIN_LIMIT', 'Sales contract ' || o.contract_number, 0, pg_temp.at(o.s, '10:00'),
       pg_temp.staff('demo.sales')::text
  FROM w_order o;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.wid('order-history:' || o.key || ':' || h.n), o.id, h.from_status, h.to_status, h.reason,
       CASE WHEN h.n > 0 THEN pg_temp.staff('demo.sales') END, pg_temp.at(o.s + h.day, h.hhmi),
       pg_temp.at(o.s + h.day, h.hhmi), pg_temp.staff('demo.sales')::text
  FROM w_order o
 CROSS JOIN (VALUES (0, NULL, 'DRAFT', NULL, 5, '09:30'),
                    (1, 'DRAFT', 'PENDING_CONTRACT', 'CONTRACT_SENT', 3, '09:00'),
                    (2, 'PENDING_CONTRACT', 'PENDING_PAYMENT', 'CONTRACT_SIGNED', 0, '10:00'))
            AS h (n, from_status, to_status, reason, day, hhmi);

-- 15 §4.1 step 1 (4B-6): the installments open with the signature; the one on signing is due at once.
INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
                                 order_number, seq, name, milestone, delivery_batch_id, delivery_batch_seq, amount,
                                 paid_amount, currency, due_days, reached_at, due_date, status, version, created_at,
                                 created_by)
SELECT pg_temp.wid('installment:' || o.key || ':' || p.seq), o.contract_id, p.id, o.id, o.customer_id,
       o.contract_number, o.order_number, p.seq, p.name, p.milestone, p.delivery_batch_id, b.seq, p.amount, 0, 'VND',
       p.due_days, CASE WHEN p.milestone = 'ON_SIGNING' THEN pg_temp.at(o.s, '10:00') END,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN pg_temp.day(o.s) + p.due_days END,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN 'DUE' ELSE 'NOT_DUE' END, 0, pg_temp.at(o.s, '10:00'),
       pg_temp.staff('demo.sales')::text
  FROM w_order o
  JOIN ordering.contract_payment_installment p ON p.contract_id = o.contract_id
  LEFT JOIN ordering.delivery_batch b ON b.id = p.delivery_batch_id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 5. Deposits (15 §4.2): the accountant records the transfer the next morning and the order is confirmed
-- ---------------------------------------------------------------------------------------------
INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, note, status,
                                     recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.wid('receipt:' || o.key || ':1'), o.customer_id, 'FT' || pg_temp.ymd(o.s - 1) || (96000 + o.seq * 10 + 1),
       pg_temp.day(o.s - 1), o.deposit, 'VND', 'Đặt cọc hợp đồng ' || o.contract_number, 'RECORDED',
       pg_temp.staff('demo.ketoan'), pg_temp.at(o.s - 1, '10:30'), 0, pg_temp.at(o.s - 1, '10:30'),
       pg_temp.staff('demo.ketoan')::text
  FROM w_order o;

INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount, created_at)
SELECT pg_temp.wid('receipt-line:' || o.key || ':1'), pg_temp.wid('receipt:' || o.key || ':1'),
       pg_temp.wid('installment:' || o.key || ':1'), o.deposit, pg_temp.at(o.s - 1, '10:30')
  FROM w_order o;

UPDATE payment.installment i
   SET paid_amount = o.deposit, status = 'PAID', paid_at = pg_temp.at(o.s - 1, '10:30'), version = 1,
       last_modified_at = pg_temp.at(o.s - 1, '10:30'), last_modified_by = pg_temp.staff('demo.ketoan')::text
  FROM w_order o WHERE i.id = pg_temp.wid('installment:' || o.key || ':1');

UPDATE ordering.customer_order co
   SET status = 'CONFIRMED', version = 3, last_modified_at = pg_temp.at(o.s - 1, '10:30'), last_modified_by = 'system'
  FROM w_order o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.wid('order-history:' || o.key || ':3'), o.id, 'PENDING_PAYMENT', 'CONFIRMED', 'INSTALLMENT_PAID', NULL,
       pg_temp.at(o.s - 1, '10:30'), pg_temp.at(o.s - 1, '10:30'), 'system'
  FROM w_order o;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Sales chooses DNI the afternoon the order is confirmed (17 §4.2); nothing is there, every carton is to be produced.
UPDATE ordering.customer_order co
   SET shipping_warehouse_id = md5('demo:history:wh:DNI')::uuid,
       shipping_warehouse_chosen_at = pg_temp.at(o.s - 1, '14:00'),
       shipping_warehouse_chosen_by = pg_temp.staff('demo.sales'), version = 4,
       last_modified_at = pg_temp.at(o.s - 1, '14:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE co.id = o.id;

-- ---------------------------------------------------------------------------------------------
-- 6. Production orders (17 §4.3): one per batch, drawn up and sent by the coordinator; the producer accepts and
--    prints. The order waits AWAITING_STOCK from the sending.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                       receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                       sample_required_by_default, sample_waiver_reason, drawn_up_by, version,
                                       created_at, created_by)
SELECT b.po_id, b.po_code, o.id, b.batch_id, o.customer_id, md5('demo:history:producer:INDN')::uuid,
       md5('demo:history:wh:DNI')::uuid, b.window_start - 3, 'DRAFT', FALSE, o.plate > 0 AND b.bseq = 1,
       CASE WHEN o.plate > 0 AND b.bseq = 1 THEN 'Khách đã duyệt bản in thử trên file in; miễn mẫu in' END,
       pg_temp.staff('demo.dieuphoi'), 0, pg_temp.at(o.s - 1, '15:00'), pg_temp.staff('demo.dieuphoi')::text
  FROM w_batch b JOIN w_order o ON o.key = b.key;

INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                            design_snapshot_id, print_file_checksum)
SELECT b.po_line_id, b.po_id, o.line_id, o.sku, b.cartons, 1000, o.snapshot_id, a.checksum
  FROM w_batch b JOIN w_order o ON o.key = b.key
  JOIN design.design_artifact a ON a.id = pg_temp.wid('artifact:' || o.skuk);

UPDATE ordering.production_order p
   SET status = 'SENT', sent_at = pg_temp.at(o.s - 2, '09:00'), sent_by = pg_temp.staff('demo.dieuphoi'), version = 1,
       last_modified_at = pg_temp.at(o.s - 2, '09:00'), last_modified_by = pg_temp.staff('demo.dieuphoi')::text
  FROM w_batch b JOIN w_order o ON o.key = b.key WHERE p.id = b.po_id;

-- The slip the producer was sent (17 §4.3 step 8); db/demo stores no file, so it is named, not kept.
INSERT INTO ordering.production_order_slip (production_order_id, storage_key, sha256, size_bytes, created_at)
SELECT b.po_id, 'documents/demo/' || b.po_code || '.pdf',
       encode(sha256(convert_to('demo:wf05:slip:' || b.bkey, 'UTF8')), 'hex'), 26214, pg_temp.at(o.s - 2, '09:00')
  FROM w_batch b JOIN w_order o ON o.key = b.key;

UPDATE ordering.customer_order co
   SET status = 'AWAITING_STOCK', version = 5, last_modified_at = pg_temp.at(o.s - 2, '09:00'),
       last_modified_by = pg_temp.staff('demo.dieuphoi')::text
  FROM w_order o WHERE co.id = o.id;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.wid('order-history:' || o.key || ':4'), o.id, 'CONFIRMED', 'AWAITING_STOCK', 'PRODUCTION_ORDER_SENT',
       pg_temp.staff('demo.dieuphoi'), pg_temp.at(o.s - 2, '09:00'), pg_temp.at(o.s - 2, '09:00'),
       pg_temp.staff('demo.dieuphoi')::text
  FROM w_order o;

UPDATE ordering.production_order p
   SET status = 'ACCEPTED', expected_completion_date = pg_temp.day(b.r + 1), version = 2,
       last_modified_at = pg_temp.at(o.s - 3, '09:00'), last_modified_by = pg_temp.staff('demo.dieuphoi')::text
  FROM w_batch b JOIN w_order o ON o.key = b.key WHERE p.id = b.po_id;

UPDATE ordering.production_order p
   SET status = 'IN_PRODUCTION', production_started_at = pg_temp.at(o.s - 4, '08:00'), version = 3,
       last_modified_at = pg_temp.at(o.s - 4, '08:00'), last_modified_by = pg_temp.staff('demo.dieuphoi')::text
  FROM w_batch b JOIN w_order o ON o.key = b.key WHERE p.id = b.po_id;

-- BR-11: the order records when printing started, once - what the cancellation fee rule reads.
UPDATE ordering.customer_order co
   SET printing_started_at = pg_temp.at(o.s - 4, '08:00'), version = 6,
       last_modified_at = pg_temp.at(o.s - 4, '08:00'), last_modified_by = pg_temp.staff('demo.dieuphoi')::text
  FROM w_order o WHERE co.id = o.id;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 7. The printed goods received at DNI (03 §4.1) in LPN cartons, in the production order's lot (BR-03); confirmed
-- ---------------------------------------------------------------------------------------------
INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                        delivery_note, version, created_at, created_by, receipt_type,
                                        production_order_id)
SELECT pg_temp.wid('receipt-goods:' || b.bkey), 'GR-' || pg_temp.ymd(b.r) || '-' || (9600 + o.seq * 10 + b.bseq),
       md5('demo:history:wh:DNI')::uuid, 'DRAFT', pg_temp.at(b.r, '08:00'), pg_temp.staff('demo.kho.dn'),
       'PXK-' || b.po_code, 0, pg_temp.at(b.r, '08:00'), pg_temp.staff('demo.kho.dn')::text, 'PRODUCTION', b.po_id
  FROM w_batch b JOIN w_order o ON o.key = b.key;

INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status, opened_at)
SELECT b.po_id, pg_temp.wid('receipt-goods:' || b.bkey), 'GR-' || pg_temp.ymd(b.r) || '-' || (9600 + o.seq * 10 + b.bseq),
       'DRAFT', pg_temp.at(b.r, '08:00')
  FROM w_batch b JOIN w_order o ON o.key = b.key;

INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty, lot_number,
                                             version, created_at, created_by, uom_conversion_id,
                                             production_order_line_id)
SELECT pg_temp.wid('receipt-goods-line:' || b.bkey), pg_temp.wid('receipt-goods:' || b.bkey), i.id,
       md5('demo:history:location:DNI-RCV01')::uuid, b.cartons * 1000, b.po_code, 0, pg_temp.at(b.r, '08:20'),
       pg_temp.staff('demo.kho.dn')::text, o.unit_id, b.po_line_id
  FROM w_batch b JOIN w_order o ON o.key = b.key JOIN inventory.inventory_items i ON i.sku = o.sku;

INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status, goods_receipt_line_id, version, created_at, created_by)
SELECT c.package_id, c.lpn, i.id, o.unit_id, 1000, 1000, 'DNI-RCV01', 'RECEIVING',
       pg_temp.wid('receipt-goods-line:' || c.bkey), 0, pg_temp.at(b.r, '08:30'), pg_temp.staff('demo.kho.dn')::text
  FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_order o ON o.key = c.key
  JOIN inventory.inventory_items i ON i.sku = o.sku;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, warehouse_id, version, created_at,
                                 created_by, production_order_id, production_order_code)
SELECT pg_temp.wid('lot:' || b.bkey), o.sku, b.po_code, pg_temp.at(b.r, '09:00'), 'PRODUCTION_ORDER',
       md5('demo:history:wh:DNI')::uuid, 0, pg_temp.at(b.r, '09:00'), pg_temp.staff('demo.kho.dn')::text, b.po_id,
       b.po_code
  FROM w_batch b JOIN w_order o ON o.key = b.key;

UPDATE procurement.goods_receipts g
   SET status = 'IN_PUTAWAY', confirmed_at = pg_temp.at(b.r, '09:00'), confirmed_by = pg_temp.staff('demo.kho.dn'),
       version = 1, last_modified_at = pg_temp.at(b.r, '09:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM w_batch b WHERE g.id = pg_temp.wid('receipt-goods:' || b.bkey);

UPDATE ordering.production_order_receipt r
   SET status = 'CONFIRMED', settled_at = pg_temp.at(b.r, '09:00')
  FROM w_batch b WHERE r.goods_receipt_id = pg_temp.wid('receipt-goods:' || b.bkey);

UPDATE ordering.production_order_line l SET received_cartons = b.cartons FROM w_batch b WHERE l.id = b.po_line_id;

UPDATE ordering.production_order p
   SET status = 'RECEIVED', version = 4, last_modified_at = pg_temp.at(b.r, '09:00'),
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM w_batch b WHERE p.id = b.po_id;

UPDATE inventory.stock_package p
   SET status = 'IN_STOCK', version = 1, last_modified_at = pg_temp.at(b.r, '09:00'),
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey WHERE p.id = c.package_id;

INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, version, created_at,
                                  created_by, received_at, stock_package_id, lot_id)
SELECT c.stock_item_id, o.sku, 'DNI-RCV01', b.po_code, 1000, 0, 'INBOUND', 0, pg_temp.at(b.r, '09:00'),
       pg_temp.staff('demo.kho.dn')::text, pg_temp.at(b.r, '09:00'), c.package_id, pg_temp.wid('lot:' || b.bkey)
  FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_order o ON o.key = c.key;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      to_stock_package_id, occurred_at, version, created_at, created_by)
SELECT pg_temp.wid('ledger:receipt:' || c.bkey || ':' || c.n), 'RECEIPT', o.sku, b.po_code, pg_temp.wid('lot:' || b.bkey),
       NULL, 'DNI-RCV01', 1000, NULL, 'INBOUND', 'GOODS_RECEIPT_LINE', pg_temp.wid('receipt-goods-line:' || b.bkey),
       pg_temp.staff('demo.kho.dn'), c.package_id, pg_temp.at(b.r, '09:00'), 0, pg_temp.at(b.r, '09:00'),
       pg_temp.staff('demo.kho.dn')::text
  FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_order o ON o.key = c.key;

INSERT INTO warehouse.putaway_task (id, goods_receipt_id, sku, quantity, status, version, created_at, created_by,
                                    goods_receipt_line_id, stock_package_id, source)
SELECT c.task_id, pg_temp.wid('receipt-goods:' || c.bkey), o.sku, 1000, 'PENDING', 0, pg_temp.at(b.r, '09:00'), 'system',
       pg_temp.wid('receipt-goods-line:' || c.bkey), c.package_id, 'RECEIPT'
  FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_order o ON o.key = c.key;

INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status, to_status, reason,
                                                      changed_by, changed_at)
SELECT pg_temp.wid('po-history:' || b.bkey || ':' || h.n), b.po_id, h.from_status, h.to_status, h.reason,
       pg_temp.staff(h.who), CASE h.n WHEN 0 THEN pg_temp.at(o.s - 1, '15:00') WHEN 1 THEN pg_temp.at(o.s - 2, '09:00')
                                      WHEN 2 THEN pg_temp.at(o.s - 3, '09:00') WHEN 3 THEN pg_temp.at(o.s - 4, '08:00')
                                      ELSE pg_temp.at(b.r, '09:00') END
  FROM w_batch b JOIN w_order o ON o.key = b.key
 CROSS JOIN LATERAL (VALUES (0, NULL, 'DRAFT', NULL, 'demo.dieuphoi'),
                            (1, 'DRAFT', 'SENT', NULL, 'demo.dieuphoi'),
                            (2, 'SENT', 'ACCEPTED', NULL, 'demo.dieuphoi'),
                            (3, 'ACCEPTED', 'IN_PRODUCTION', NULL, 'demo.dieuphoi'),
                            (4, 'IN_PRODUCTION', 'RECEIVED', 'Received in full on receipt GR-' || pg_temp.ymd(b.r) || '-'
                             || (9600 + o.seq * 10 + b.bseq), 'demo.kho.dn'))
             AS h (n, from_status, to_status, reason, who);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 8. Putaway (05): each carton whole into a bin, a minute a carton from 10:00; then held for its batch
-- ---------------------------------------------------------------------------------------------
UPDATE inventory.stock_package p
   SET location_code = c.bin, version = 2, last_modified_at = pg_temp.at(b.r, '10:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey WHERE p.id = c.package_id;

UPDATE inventory.stock_item s
   SET location_code = c.bin, status = 'AVAILABLE', version = 1,
       last_modified_at = pg_temp.at(b.r, '10:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey WHERE s.id = c.stock_item_id;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      from_stock_package_id, to_stock_package_id, occurred_at, version, created_at,
                                      created_by)
SELECT pg_temp.wid('ledger:putaway:' || c.bkey || ':' || c.n), 'MOVE', o.sku, b.po_code, pg_temp.wid('lot:' || b.bkey),
       'DNI-RCV01', c.bin, 1000, 'INBOUND', 'AVAILABLE', 'PUTAWAY_TASK', c.task_id, pg_temp.staff('demo.kho.dn'),
       c.package_id, c.package_id, pg_temp.at(b.r, '10:00') + (c.n || ' minutes')::interval, 0,
       pg_temp.at(b.r, '10:00') + (c.n || ' minutes')::interval, pg_temp.staff('demo.kho.dn')::text
  FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_order o ON o.key = c.key;

UPDATE warehouse.putaway_task t
   SET status = 'DONE', target_location_id = md5('demo:history:location:' || c.bin)::uuid, version = 1,
       last_modified_at = pg_temp.at(b.r, '10:00') + (c.n || ' minutes')::interval,
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey WHERE t.id = c.task_id;

UPDATE procurement.goods_receipts g
   SET status = 'CLOSED', closed_at = pg_temp.at(b.r, '11:00'), version = 2, last_modified_at = pg_temp.at(b.r, '11:00'),
       last_modified_by = 'system'
  FROM w_batch b WHERE g.id = pg_temp.wid('receipt-goods:' || b.bkey);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Put away, the goods top up the order waiting for them (17 §4.2 step 5): a hold of the SKU at DNI for the batch.
INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at, status, sku,
                                         warehouse_code)
SELECT pg_temp.wid('hold:' || b.bkey), o.id, pg_temp.wid('hold-request:' || b.bkey), pg_temp.wid('hold-request:' || b.bkey),
       b.cartons * 1000, pg_temp.at(b.r, '11:00'), 'HELD', o.sku, 'DNI'
  FROM w_batch b JOIN w_order o ON o.key = b.key;

INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id, created_at)
SELECT pg_temp.wid('hold:' || b.bkey), b.batch_id, o.id, o.line_id, pg_temp.at(b.r, '11:00')
  FROM w_batch b JOIN w_order o ON o.key = b.key;

INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id)
SELECT o.line_id, pg_temp.wid('hold:' || b.bkey) FROM w_batch b JOIN w_order o ON o.key = b.key;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 9. Each batch released, picked, checked and labelled, its truck planned, booked and loaded - batch by batch in
--    time order (a step per call), and WF5-B's first batch handed over and delivered before its second is released.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE w_step (bkey TEXT PRIMARY KEY) ON COMMIT DROP;

CREATE FUNCTION pg_temp.release_and_load(first_released INTEGER) RETURNS VOID LANGUAGE plpgsql AS $fn$
BEGIN
    DELETE FROM w_step;
    INSERT INTO w_step SELECT bkey FROM w_batch WHERE released = first_released;

    -- Release (17 §4.4, 4B-8): the hold allocated carton by carton; the order READY_TO_FULFILL, or still
    -- PARTIALLY_FULFILLED for a later batch once a part has left (17 §4.5).
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    SELECT b.batch_id, o.id, b.bseq, md5('demo:history:wh:DNI')::uuid, pg_temp.at(b.released, '09:00') + (b.bseq || ' minutes')::interval,
           pg_temp.staff('demo.quanly.dn')
      FROM w_batch b JOIN w_order o ON o.key = b.key JOIN w_step s ON s.bkey = b.bkey;

    UPDATE ordering.customer_order co
       SET status = CASE WHEN co.status = 'PARTIALLY_FULFILLED' THEN co.status ELSE 'READY_TO_FULFILL' END,
           warehouse_id = md5('demo:history:wh:DNI')::uuid,
           released_at = coalesce(co.released_at, pg_temp.at(first_released, '09:00')),
           released_by = coalesce(co.released_by, pg_temp.staff('demo.quanly.dn')), version = co.version + 1,
           last_modified_at = pg_temp.at(first_released, '09:00'),
           last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_order o
     WHERE co.id = o.id AND EXISTS (SELECT 1 FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE b.key = o.key);

    INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                               created_at, created_by)
    SELECT pg_temp.wid('order-history:' || o.key || ':release:' || first_released), o.id, 'AWAITING_STOCK',
           'READY_TO_FULFILL', NULL, NULL, pg_temp.at(first_released, '09:00'), pg_temp.at(first_released, '09:00'),
           pg_temp.staff('demo.quanly.dn')::text
      FROM w_order o JOIN ordering.customer_order co ON co.id = o.id
     WHERE co.status = 'READY_TO_FULFILL'
       AND EXISTS (SELECT 1 FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE b.key = o.key);

    UPDATE inventory.stock_reservation r
       SET status = 'ALLOCATED', closed_at = pg_temp.at(b.released, '09:00') + (b.bseq || ' minutes')::interval
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE r.id = pg_temp.wid('hold:' || b.bkey);

    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity,
                                             reserved_at, status, sku, allocated_from_id)
    SELECT c.allocation_id, c.stock_item_id, o.id, pg_temp.wid('hold:' || b.bkey),
           pg_temp.wid('allocation-request:' || c.bkey || ':' || c.n), 1000,
           pg_temp.at(b.released, '09:00') + (b.bseq || ' minutes')::interval, 'HELD', o.sku, pg_temp.wid('hold:' || b.bkey)
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = c.key;

    UPDATE inventory.stock_item si
       SET reserved = 1000, version = 2, last_modified_at = pg_temp.at(b.released, '09:00'),
           last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey WHERE si.id = c.stock_item_id;

    INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id, created_at)
    SELECT c.allocation_id, b.batch_id, o.id, o.line_id, pg_temp.at(b.released, '09:00')
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = c.key;

    -- The line swaps the batch's hold for its allocations (CLAUDE.md §4: all of them, not the first).
    DELETE FROM ordering.order_line_reservation l
     USING w_batch b JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = b.key
     WHERE l.order_line_id = o.line_id AND l.reservation_id = pg_temp.wid('hold:' || b.bkey);
    INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id)
    SELECT o.line_id, c.allocation_id
      FROM w_carton c JOIN w_step s ON s.bkey = c.bkey JOIN w_order o ON o.key = c.key;

    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;

    -- Picking by LPN (07): a task per batch for the warehouse staff; each carton scanned at its bin.
    INSERT INTO fulfillment.pick (id, order_id, status, version, created_at, created_by, assigned_user_id, assigned_at,
                                  order_number, warehouse_id, destination, delivery_batch_id)
    SELECT pg_temp.wid('pick:' || b.bkey), o.id, 'PENDING', 0, pg_temp.at(b.picked, '07:30'),
           pg_temp.staff('demo.quanly.dn')::text, pg_temp.staff('demo.kho.dn'), pg_temp.at(b.picked, '07:30'),
           o.order_number, md5('demo:history:wh:DNI')::uuid, 'DISPATCH', b.batch_id
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = b.key;

    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, lot_number,
                                       sequence_no, allocated_qty, picked_qty, short_qty, status, version, created_at,
                                       created_by, lpn, whole_carton)
    SELECT c.pick_line_id, pg_temp.wid('pick:' || c.bkey), o.line_id, c.allocation_id, o.sku, c.bin, b.po_code, c.n,
           1000, 0, 0, 'OPEN', 0, pg_temp.at(b.picked, '07:30'), pg_temp.staff('demo.quanly.dn')::text, c.lpn, TRUE
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = c.key;

    INSERT INTO fulfillment.pack (id, pick_id, status, version, created_at, created_by)
    SELECT pg_temp.wid('pack:' || b.bkey), pg_temp.wid('pick:' || b.bkey), 'PENDING', 0, pg_temp.at(b.picked, '07:30'),
           pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey;

    INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                               created_at, created_by)
    SELECT pg_temp.wid('order-history:' || o.key || ':picking:' || first_released), o.id, 'READY_TO_FULFILL', 'PICKING',
           NULL, pg_temp.staff('demo.quanly.dn'), pg_temp.at(first_released - 1, '07:30'),
           pg_temp.at(first_released - 1, '07:30'), pg_temp.staff('demo.quanly.dn')::text
      FROM w_order o JOIN ordering.customer_order co ON co.id = o.id
     WHERE co.status = 'READY_TO_FULFILL'
       AND EXISTS (SELECT 1 FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE b.key = o.key);

    UPDATE ordering.customer_order co
       SET status = CASE WHEN co.status = 'READY_TO_FULFILL' THEN 'PICKING' ELSE co.status END,
           fulfilment_started_at = coalesce(co.fulfilment_started_at, pg_temp.at(first_released - 1, '07:30')),
           version = co.version + 1, last_modified_at = pg_temp.at(first_released - 1, '07:30'),
           last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_order o
     WHERE co.id = o.id AND EXISTS (SELECT 1 FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE b.key = o.key);

    UPDATE fulfillment.pick p
       SET status = 'PICKING', started_at = pg_temp.at(b.picked, '08:00'), version = 1,
           last_modified_at = pg_temp.at(b.picked, '08:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE p.id = pg_temp.wid('pick:' || b.bkey);

    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;

    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code,
                                          to_location_code, quantity, from_status, to_status, reference_type,
                                          reference_id, actor_id, from_stock_package_id, order_id, occurred_at, version,
                                          created_at, created_by)
    SELECT pg_temp.wid('ledger:pick:' || c.bkey || ':' || c.n), 'PICK', o.sku, b.po_code, pg_temp.wid('lot:' || b.bkey),
           c.bin, NULL, 1000, 'AVAILABLE', NULL, 'PICK_LINE', c.pick_line_id, pg_temp.staff('demo.kho.dn'),
           c.package_id, o.id, pg_temp.at(b.picked, '08:00') + (c.n || ' minutes')::interval, 0,
           pg_temp.at(b.picked, '08:00') + (c.n || ' minutes')::interval, pg_temp.staff('demo.kho.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = c.key;

    UPDATE inventory.stock_item si
       SET on_hand = 0, reserved = 0, version = 3,
           last_modified_at = pg_temp.at(b.picked, '08:00') + (c.n || ' minutes')::interval,
           last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey WHERE si.id = c.stock_item_id;

    UPDATE inventory.stock_package p
       SET status = 'CONSUMED', closed_at = pg_temp.at(b.picked, '08:00') + (c.n || ' minutes')::interval, version = 3,
           last_modified_at = pg_temp.at(b.picked, '08:00') + (c.n || ' minutes')::interval,
           last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey WHERE p.id = c.package_id;

    UPDATE inventory.stock_reservation r
       SET status = 'CONSUMED', closed_at = pg_temp.at(b.picked, '08:00') + (c.n || ' minutes')::interval
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey WHERE r.id = c.allocation_id;

    UPDATE fulfillment.pick_line l
       SET status = 'PICKED', picked_qty = 1000, picked_by = pg_temp.staff('demo.kho.dn'),
           picked_at = pg_temp.at(b.picked, '08:00') + (c.n || ' minutes')::interval,
           consumed_at = pg_temp.at(b.picked, '08:00') + (c.n || ' minutes')::interval, scanned_location_code = c.bin,
           scanned_code = c.lpn, version = 1,
           last_modified_at = pg_temp.at(b.picked, '08:00') + (c.n || ' minutes')::interval,
           last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey WHERE l.id = c.pick_line_id;

    UPDATE fulfillment.pick p
       SET status = 'PICKED', picked_at = pg_temp.at(b.picked, '09:00'), version = 2,
           last_modified_at = pg_temp.at(b.picked, '09:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE p.id = pg_temp.wid('pick:' || b.bkey);

    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;

    -- Carton check and labelling (08, 4C-6): each LPN carton is the shipping carton; completion makes one shipment.
    UPDATE fulfillment.pack k
       SET status = 'IN_PROGRESS', started_at = pg_temp.at(b.picked, '13:00'), started_by = pg_temp.staff('demo.kho.dn'),
           version = 1, last_modified_at = pg_temp.at(b.picked, '13:00'),
           last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE k.id = pg_temp.wid('pack:' || b.bkey);

    INSERT INTO fulfillment.package (id, pack_id, package_no, status, version, created_at, created_by, label_code,
                                     rotatable, rotatable_overridden, pick_line_id, lpn)
    SELECT c.shipping_package_id, pg_temp.wid('pack:' || c.bkey), c.n, 'TO_CHECK', 0, pg_temp.at(b.picked, '13:00'),
           pg_temp.staff('demo.kho.dn')::text, o.order_number || '-B' || b.bseq || '-C' || c.n, TRUE, FALSE,
           c.pick_line_id, c.lpn
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = c.key;

    UPDATE fulfillment.package p
       SET status = 'CHECKED', checked_at = pg_temp.at(b.picked, '13:00') + (c.n || ' minutes')::interval,
           checked_by = pg_temp.staff('demo.kho.dn'), version = 1,
           last_modified_at = pg_temp.at(b.picked, '13:00') + (c.n || ' minutes')::interval,
           last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey
     WHERE p.id = c.shipping_package_id;

    -- The first label locks the order's cancellation and address (OrderService.recordFirstLabel).
    UPDATE ordering.customer_order co
       SET first_labeled_at = coalesce(co.first_labeled_at, pg_temp.at(first_released - 1, '14:00')),
           version = co.version + 1, last_modified_at = pg_temp.at(first_released - 1, '14:00'),
           last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_order o
     WHERE co.id = o.id AND EXISTS (SELECT 1 FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE b.key = o.key);

    -- Labelled from the SKU's carton spec (08 BR-07, BR-09): 60 x 40 x 50 cm, 8.6 kg, volumetric 20 kg.
    UPDATE fulfillment.package p
       SET status = 'LABELED', weight_kg = 8.6, length_cm = 60, width_cm = 40, height_cm = 50, dim_weight_kg = 20,
           labeled_at = pg_temp.at(b.picked, '14:00'), labeled_by = pg_temp.staff('demo.kho.dn'), label_version = 1,
           version = 2, last_modified_at = pg_temp.at(b.picked, '14:00'),
           last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey
     WHERE p.id = c.shipping_package_id;

    INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, version, created_at, created_by, code, warehouse_id,
                                      package_count, partial, ready_at, ready_by, whole_order_handover)
    SELECT pg_temp.wid('shipment:' || b.bkey), pg_temp.wid('pack:' || b.bkey), o.id, 'READY_TO_DISPATCH', 0,
           pg_temp.at(b.picked, '14:30') + (b.bseq || ' minutes')::interval, pg_temp.staff('demo.kho.dn')::text,
           o.order_number || '-S' || b.bseq, md5('demo:history:wh:DNI')::uuid, b.cartons, FALSE,
           pg_temp.at(b.picked, '14:30') + (b.bseq || ' minutes')::interval, pg_temp.staff('demo.kho.dn'), FALSE
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = b.key;

    UPDATE fulfillment.package p
       SET shipment_id = pg_temp.wid('shipment:' || c.bkey), version = 3
      FROM w_carton c JOIN w_step s ON s.bkey = c.bkey WHERE p.id = c.shipping_package_id;

    UPDATE fulfillment.pack k
       SET status = 'COMPLETED', packed_at = pg_temp.at(b.picked, '14:30') + (b.bseq || ' minutes')::interval,
           packed_by = pg_temp.staff('demo.kho.dn'), version = 2,
           last_modified_at = pg_temp.at(b.picked, '14:30') + (b.bseq || ' minutes')::interval,
           last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE k.id = pg_temp.wid('pack:' || b.bkey);

    INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at,
                                            created_by, recorded_by)
    SELECT pg_temp.wid('shipment-event:' || b.bkey || ':0'), pg_temp.wid('shipment:' || b.bkey), 'READY_TO_DISPATCH',
           pg_temp.at(b.picked, '14:30') + (b.bseq || ' minutes')::interval, 'MANUAL', 0,
           pg_temp.at(b.picked, '14:30') + (b.bseq || ' minutes')::interval, pg_temp.staff('demo.kho.dn')::text,
           pg_temp.staff('demo.kho.dn')
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey;

    -- Everything released is packed: PACKED while nothing has left; once a part has left, PARTIALLY_FULFILLED until
    -- its last handover (17 §4.5, WF4-PF-NEXT).
    INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                               created_at, created_by)
    SELECT pg_temp.wid('order-history:' || o.key || ':packed:' || first_released), o.id, 'PICKING', 'PACKED', NULL,
           pg_temp.staff('demo.kho.dn'), pg_temp.at(first_released - 1, '14:45'),
           pg_temp.at(first_released - 1, '14:45'), pg_temp.staff('demo.kho.dn')::text
      FROM w_order o JOIN ordering.customer_order co ON co.id = o.id
     WHERE co.status = 'PICKING'
       AND EXISTS (SELECT 1 FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE b.key = o.key);

    UPDATE ordering.customer_order co
       SET status = CASE WHEN co.status = 'PICKING' THEN 'PACKED' ELSE co.status END,
           packed_at = coalesce(co.packed_at, pg_temp.at(first_released - 1, '14:45')), version = co.version + 1,
           last_modified_at = pg_temp.at(first_released - 1, '14:45'),
           last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_order o
     WHERE co.id = o.id AND EXISTS (SELECT 1 FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE b.key = o.key);

    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;

    -- One truck a batch (09, LP): the load plan calculated and confirmed the day before it leaves; the truck of the
    -- carrier DEMO-WEBHOOK booked with its driver and waybill; loaded by scanning every carton.
    INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, source_type, selection_mode, status,
                                       calculated_at, calculated_by, solver_meta, confirmed_at, confirmed_by, revised_at,
                                       version, created_at, created_by)
    SELECT pg_temp.wid('load-plan:' || b.bkey), 'LP-' || pg_temp.ymd(b.planned) || '-' || b.load_seq,
           pg_temp.wid('load-plan-request:' || b.bkey), md5('demo:history:wh:DNI')::uuid, 'ORDER', 'SINGLE_TYPE',
           'CONFIRMED', pg_temp.at(b.planned, '10:05'), pg_temp.staff('demo.quanly.dn'),
           '{"algorithm": "GRASP_KIT", "setsTotal": 0, "kitWarnings": [], "minVehicles": 1, "budgetExhausted": false}'::jsonb,
           pg_temp.at(b.planned, '10:10'), pg_temp.staff('demo.quanly.dn'), pg_temp.at(b.planned, '10:10'), 2,
           pg_temp.at(b.planned, '10:00'), pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey;

    INSERT INTO fulfillment.load_plan_vehicle_type (id, load_plan_id, vehicle_type_id, sort_order, version, created_at,
                                                    created_by)
    SELECT pg_temp.wid('load-plan-type:' || b.bkey), pg_temp.wid('load-plan:' || b.bkey), t.id, 0, 0,
           pg_temp.at(b.planned, '10:00'), pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5';

    INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, shipment_id, active, added_at, added_by,
                                               version, created_at, created_by)
    SELECT pg_temp.wid('load-plan-package:' || c.bkey || ':' || c.n), pg_temp.wid('load-plan:' || c.bkey),
           c.shipping_package_id, pg_temp.wid('shipment:' || c.bkey), TRUE, pg_temp.at(b.planned, '10:00'),
           pg_temp.staff('demo.quanly.dn'), 0, pg_temp.at(b.planned, '10:00'), pg_temp.staff('demo.quanly.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey;

    -- One truck: 4,300 x 1,800 x 1,800 mm inside; the cartons four across, three high, row after row.
    INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, active, vehicle_type_id, vehicle_code,
                                               vehicle_kind, inner_length_mm, inner_width_mm, inner_height_mm,
                                               max_payload_kg, gusset_mm, used_volume_mm3, used_weight_kg,
                                               volume_fill_pct, weight_fill_pct, needs_recalculation, version,
                                               created_at, created_by)
    SELECT pg_temp.wid('load-plan-vehicle:' || b.bkey), pg_temp.wid('load-plan:' || b.bkey), 1, TRUE, t.id, t.code,
           t.kind, t.inner_length_mm, t.inner_width_mm, t.inner_height_mm, t.max_payload_kg, t.gusset_mm,
           b.cartons * 120000000::bigint, b.cartons * 8.6,
           round(b.cartons * 120000000.0 * 100 / (t.inner_length_mm::numeric * t.inner_width_mm * t.inner_height_mm), 2),
           round(b.cartons * 8.6 * 100 / t.max_payload_kg, 2), FALSE, 0, pg_temp.at(b.planned, '10:05'),
           pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey JOIN fulfillment.vehicle_type t ON t.code = 'TRUCK-2T5';

    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm,
                                            h_mm, d_mm, orientation, version, created_at, created_by)
    SELECT pg_temp.wid('load-placement:' || c.bkey || ':' || c.n), pg_temp.wid('load-plan:' || c.bkey),
           pg_temp.wid('load-plan-vehicle:' || c.bkey), c.shipping_package_id, ((c.n - 1) % 4) * 400,
           (((c.n - 1) / 4) % 3) * 500, ((c.n - 1) / 12) * 600, 400, 500, 600, 0, 0, pg_temp.at(b.planned, '10:05'),
           pg_temp.staff('demo.quanly.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey;

    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, load_plan_vehicle_id, status,
                                           shipment_count, package_count, version, created_at, created_by)
    SELECT pg_temp.wid('load:' || b.bkey), 'LD-' || pg_temp.ymd(b.planned) || '-' || b.load_seq,
           pg_temp.wid('load-request:' || b.bkey), md5('demo:history:wh:DNI')::uuid,
           pg_temp.wid('load-plan-vehicle:' || b.bkey), 'PLANNED', 0, 0, 0, pg_temp.at(b.planned, '10:10'),
           pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey;

    INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, active, added_at, added_by, version,
                                                    created_at, created_by)
    SELECT pg_temp.wid('load-shipment:' || b.bkey), pg_temp.wid('load:' || b.bkey), pg_temp.wid('shipment:' || b.bkey),
           TRUE, pg_temp.at(b.planned, '10:10'), pg_temp.staff('demo.quanly.dn'), 0, pg_temp.at(b.planned, '10:10'),
           pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey;

    UPDATE fulfillment.dispatch_load l
       SET status = 'VEHICLE_BOOKED', carrier_id = md5('demo:wf05:carrier:DEMO-WEBHOOK')::uuid,
           vehicle_plate = '51C-' || (95100 + b.load_seq % 100 * 7), driver_name = 'Phan Văn Lái',
           driver_phone = '0909000666', waybill_no = 'VD-' || pg_temp.ymd(b.planned) || '-' || b.load_seq,
           booked_at = pg_temp.at(b.planned, '15:00'), booked_by = pg_temp.staff('demo.quanly.dn'), version = 1,
           last_modified_at = pg_temp.at(b.planned, '15:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE l.id = pg_temp.wid('load:' || b.bkey);

    INSERT INTO fulfillment.waybill_change (id, load_id, new_waybill_no, source, changed_by, changed_at, version,
                                            created_at, created_by)
    SELECT pg_temp.wid('waybill:' || b.bkey), pg_temp.wid('load:' || b.bkey),
           'VD-' || pg_temp.ymd(b.planned) || '-' || b.load_seq, 'MANUAL', pg_temp.staff('demo.quanly.dn'),
           pg_temp.at(b.planned, '15:00'), 0, pg_temp.at(b.planned, '15:00'), pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey;

    UPDATE fulfillment.dispatch_load l
       SET status = 'LOADING', loading_started_at = pg_temp.at(b.planned, '16:00'),
           loading_started_by = pg_temp.staff('demo.kho.dn'), version = 2,
           last_modified_at = pg_temp.at(b.planned, '16:00'), last_modified_by = pg_temp.staff('demo.kho.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE l.id = pg_temp.wid('load:' || b.bkey);

    INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by,
                                                   active, version, created_at, created_by)
    SELECT pg_temp.wid('load-package:' || c.bkey || ':' || c.n), pg_temp.wid('load:' || c.bkey), c.shipping_package_id,
           pg_temp.wid('shipment:' || c.bkey), pg_temp.wid('scan:' || c.bkey || ':' || c.n),
           pg_temp.at(b.planned, '16:00') + (c.n || ' seconds')::interval * 40, pg_temp.staff('demo.kho.dn'), TRUE, 0,
           pg_temp.at(b.planned, '16:00') + (c.n || ' seconds')::interval * 40, pg_temp.staff('demo.kho.dn')::text
      FROM w_carton c JOIN w_batch b ON b.bkey = c.bkey JOIN w_step s ON s.bkey = b.bkey;

    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
END
$fn$;

-- Handed over at 08:00 and delivered at 15:00 (09 §4.2 step 8, §4.3): the load leaves, the shipment arrives, and
-- what the order pays after this delivery is due from today.
CREATE FUNCTION pg_temp.hand_over_and_deliver(delivered INTEGER) RETURNS VOID LANGUAGE plpgsql AS $fn$
BEGIN
    DELETE FROM w_step;
    INSERT INTO w_step SELECT bkey FROM w_batch WHERE d = delivered;

    UPDATE fulfillment.dispatch_load l
       SET status = 'DISPATCHED', dispatched_at = pg_temp.ho(b.d), dispatched_by = pg_temp.staff('demo.quanly.dn'),
           carrier_representative = 'Phan Văn Lái', shipment_count = 1, package_count = b.cartons, version = 3,
           last_modified_at = pg_temp.ho(b.d), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE l.id = pg_temp.wid('load:' || b.bkey);

    UPDATE fulfillment.load_plan p
       SET status = 'DISPATCHED', dispatched_at = pg_temp.ho(b.d), revised_at = pg_temp.ho(b.d),
           version = 3, last_modified_at = pg_temp.ho(b.d),
           last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE p.id = pg_temp.wid('load-plan:' || b.bkey);

    UPDATE fulfillment.shipment sh
       SET status = 'HANDED_OVER', handed_over_at = pg_temp.ho(b.d),
           handed_over_by = pg_temp.staff('demo.quanly.dn'), carrier_id = md5('demo:wf05:carrier:DEMO-WEBHOOK')::uuid,
           load_id = pg_temp.wid('load:' || b.bkey), version = 1, last_modified_at = pg_temp.ho(b.d),
           last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE sh.id = pg_temp.wid('shipment:' || b.bkey);

    INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at,
                                            created_by, recorded_by)
    SELECT pg_temp.wid('shipment-event:' || b.bkey || ':1'), pg_temp.wid('shipment:' || b.bkey), 'HANDED_OVER',
           pg_temp.ho(b.d), 'MANUAL', 0, pg_temp.ho(b.d), pg_temp.staff('demo.quanly.dn')::text,
           pg_temp.staff('demo.quanly.dn')
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey;

    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, handed_over_by, version,
                                         created_at, created_by, shipment_code, package_count, carrier_name,
                                         delivery_batch_id)
    SELECT pg_temp.wid('shipment:' || b.bkey), o.id, 'HANDED_OVER', b.amount, pg_temp.ho(b.d),
           pg_temp.staff('demo.quanly.dn'), 0, pg_temp.ho(b.d), pg_temp.staff('demo.quanly.dn')::text,
           o.order_number || '-S' || b.bseq, b.cartons, 'Vận tải Demo (webhook)', b.batch_id
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = b.key;

    INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity)
    SELECT pg_temp.wid('shipment:' || b.bkey), o.line_id, b.cartons * 1000
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey JOIN w_order o ON o.key = b.key;

    -- The last batch handed over: SHIPPED; a part with the rest still to go: PARTIALLY_FULFILLED (17 §4.5).
    INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                               created_at, created_by)
    SELECT pg_temp.wid('order-history:' || o.key || ':handover:' || delivered), o.id,
           (SELECT co.status FROM ordering.customer_order co WHERE co.id = o.id),
           CASE WHEN b.bseq = o.batches THEN 'SHIPPED' ELSE 'PARTIALLY_FULFILLED' END, NULL,
           pg_temp.staff('demo.quanly.dn'), pg_temp.ho(delivered), pg_temp.ho(delivered),
           pg_temp.staff('demo.quanly.dn')::text
      FROM w_order o JOIN w_batch b ON b.key = o.key JOIN w_step s ON s.bkey = b.bkey;

    UPDATE ordering.customer_order co
       SET status = CASE WHEN b.bseq = o.batches THEN 'SHIPPED' ELSE 'PARTIALLY_FULFILLED' END,
           shipped_at = CASE WHEN b.bseq = o.batches THEN pg_temp.ho(delivered) END,
           goods_left_at = coalesce(co.goods_left_at, pg_temp.ho(delivered)),
           version = co.version + 1, last_modified_at = pg_temp.ho(delivered),
           last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_order o JOIN w_batch b ON b.key = o.key JOIN w_step s ON s.bkey = b.bkey
     WHERE co.id = o.id;

    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;

    UPDATE fulfillment.shipment sh
       SET status = 'DELIVERED', outcome_at = pg_temp.dl(b.d), outcome_by = pg_temp.staff('demo.quanly.dn'),
           version = 2, last_modified_at = pg_temp.dl(b.d),
           last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE sh.id = pg_temp.wid('shipment:' || b.bkey);

    INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, version, created_at,
                                            created_by, recorded_by)
    SELECT pg_temp.wid('shipment-event:' || b.bkey || ':2'), pg_temp.wid('shipment:' || b.bkey), 'DELIVERED',
           pg_temp.dl(b.d), 'MANUAL', 0, pg_temp.dl(b.d), pg_temp.staff('demo.quanly.dn')::text,
           pg_temp.staff('demo.quanly.dn')
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey;

    UPDATE ordering.order_shipment os
       SET status = 'DELIVERED', outcome_at = pg_temp.dl(b.d), outcome_by = pg_temp.staff('demo.quanly.dn'),
           version = 1, last_modified_at = pg_temp.dl(b.d),
           last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey WHERE os.id = pg_temp.wid('shipment:' || b.bkey);

    -- Everything shipped arrived: DELIVERED.
    INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                               created_at, created_by)
    SELECT pg_temp.wid('order-history:' || o.key || ':delivered:' || delivered), o.id, 'SHIPPED', 'DELIVERED', NULL,
           pg_temp.staff('demo.quanly.dn'), pg_temp.dl(delivered), pg_temp.dl(delivered),
           pg_temp.staff('demo.quanly.dn')::text
      FROM w_order o JOIN w_batch b ON b.key = o.key JOIN w_step s ON s.bkey = b.bkey
     WHERE b.bseq = o.batches;

    UPDATE ordering.customer_order co
       SET status = 'DELIVERED', delivered_at = pg_temp.dl(delivered), version = co.version + 1,
           last_modified_at = pg_temp.dl(delivered), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
      FROM w_order o JOIN w_batch b ON b.key = o.key JOIN w_step s ON s.bkey = b.bkey
     WHERE co.id = o.id AND b.bseq = o.batches;

    UPDATE payment.installment i
       SET reached_at = pg_temp.dl(b.d), due_date = pg_temp.day(b.d) + i.due_days, status = 'DUE',
           version = 1, last_modified_at = pg_temp.dl(b.d), last_modified_by = 'system'
      FROM w_batch b JOIN w_step s ON s.bkey = b.bkey
     WHERE i.delivery_batch_id = b.batch_id AND i.milestone = 'AFTER_DELIVERY';

    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
END
$fn$;

-- In time order, day by day back to front: on a day, the deliveries (08:00-15:00) before the releases (09:00 onwards
-- the next batches' picking) - WF5-DD1A/B's second batch is released only after the first was handed over (BR-29).
DO $$
DECLARE
    x INTEGER;
BEGIN
    FOR x IN SELECT DISTINCT v FROM (SELECT d AS v FROM w_batch WHERE d IS NOT NULL
                                     UNION SELECT released FROM w_batch WHERE released IS NOT NULL) days
              ORDER BY v DESC LOOP
        IF EXISTS (SELECT 1 FROM w_batch WHERE d = x) THEN
            PERFORM pg_temp.hand_over_and_deliver(x);
        END IF;
        IF EXISTS (SELECT 1 FROM w_batch WHERE released = x) THEN
            PERFORM pg_temp.release_and_load(x);
        END IF;
    END LOOP;
END
$$;

DROP FUNCTION pg_temp.release_and_load(INTEGER);
DROP FUNCTION pg_temp.hand_over_and_deliver(INTEGER);
DROP FUNCTION pg_temp.wid(TEXT);
DROP FUNCTION pg_temp.today();
DROP FUNCTION pg_temp.at(INTEGER, TEXT);
DROP FUNCTION pg_temp.day(INTEGER);
DROP FUNCTION pg_temp.ymd(INTEGER);
DROP FUNCTION pg_temp.staff(TEXT);
DROP FUNCTION pg_temp.ho(INTEGER);
DROP FUNCTION pg_temp.dl(INTEGER);
