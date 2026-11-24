-- =============================================================================
-- Demo WF-05, second round (seed gap G-9): the orders that end before any goods are made - each through the chain of
-- V20261224900200 up to the signature (quote sent and accepted, converted, contract sent and signed, installments
-- opened). Demo only (db/demo-wf05).
--
--   WF5-TT1A, WF5-TT1B  TT-1: two new orders of Công ty CP Trà Sữa Mây (TSM), the customer V20261203000100 left with an
--            installment overdue for three months. Signed, each is held for credit (Order.contractSigned: the credit
--            check says OVERDUE - ON_HOLD, hold CREDIT, nothing reserved): Sales see them "Treo - tín dụng", the
--            accountant finds them among the credit holds. TT1A: no deposit yet - approved, it waits for its deposit
--            (PENDING_PAYMENT). TT1B: its deposit was paid while it was held - refused, it is cancelled and the
--            deposit becomes refundable (TT-1, the second case).
--   WF5-F    Mộc, step 11's "Thanh lý": signed, deposit paid, CONFIRMED; the customer withdrew before anything was
--            printed - cancelled (CUSTOMER_REQUEST, no fee), the deposit made refundable and refunded. Its contract
--            stays SIGNED until the nightly run liquidates it (OrderSettlementService.liquidateCancelled, WF-05 Q-6):
--            the first run after the build shows "Thanh lý".
-- 50 cartons for TSM (its own price is 1,200 a cup from 50 cartons); 5 for Mộc.
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

-- ---------------------------------------------------------------------------------------------
-- 1. The orders: signed s days ago; one delivery batch, two weeks ahead
-- ---------------------------------------------------------------------------------------------
-- held: the signature holds it for credit. due_after: the days after delivery the customer's credit term gives.
CREATE TEMP TABLE w_order (key TEXT PRIMARY KEY, seq INTEGER, cust TEXT, cust_id UUID, s INTEGER, plate NUMERIC,
                           ship NUMERIC, per_batch BOOLEAN, window_days INTEGER, vat NUMERIC, skuk TEXT, sku_code TEXT,
                           unit UUID, snapshot UUID, held BOOLEAN, credit_limit NUMERIC, due_after INTEGER)
    ON COMMIT DROP;
INSERT INTO w_order VALUES
    ('WF5-TT1A', 21, 'TSM', md5('demo:history:customer:TSM')::uuid, 1, 0, 1500000, FALSE, NULL, 8, NULL,
     'LY-GIAY-DEMO-12OZ-TSM', md5('demo:history:unit:TSM')::uuid, NULL, TRUE, 300000000, 15),
    ('WF5-TT1B', 22, 'TSM', md5('demo:history:customer:TSM')::uuid, 3, 0, 1500000, FALSE, NULL, 8, NULL,
     'LY-GIAY-DEMO-12OZ-TSM', md5('demo:history:unit:TSM')::uuid, NULL, TRUE, 300000000, 15),
    ('WF5-F', 23, 'MOC', md5('demo:wf05:customer:MOC')::uuid, 6, 0, 300000, FALSE, NULL, 8, 'MOC',
     'LY-GIAY-DEMO-12OZ-MOC', md5('demo:wf05:unit:MOC')::uuid, md5('demo:wf05:snapshot:MOC')::uuid, FALSE, 500000000,
     30);

CREATE TEMP TABLE w_batch (key TEXT, bseq INTEGER, cartons INTEGER, r INTEGER, d INTEGER, load_seq INTEGER,
                           PRIMARY KEY (key, bseq)) ON COMMIT DROP;
INSERT INTO w_batch VALUES ('WF5-TT1A', 1, 50, NULL, NULL, NULL), ('WF5-TT1B', 1, 50, NULL, NULL, NULL),
                           ('WF5-F', 1, 5, NULL, NULL, NULL);

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
       tax_code = c.tax_code, sku = o.sku_code, unit_id = o.unit,
       snapshot_id = o.snapshot, line_id = pg_temp.wid('order-line:' || o.key),
       quote_id = pg_temp.wid('quote:' || o.key), contract_id = pg_temp.wid('contract:' || o.key),
       order_number = 'SO-' || pg_temp.ymd(o.s + 5) || '-' || (960000 + o.seq),
       quote_number = 'QT-' || pg_temp.ymd(o.s + 7) || '-' || (9600 + o.seq)
  FROM customer.customer c
  JOIN identity.app_user u ON u.id = c.user_id
  JOIN customer.address a ON a.customer_id = c.id AND a.type = 'SHIPPING'
 WHERE c.id = o.cust_id;

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
       window_start = pg_temp.day(-14), window_end = pg_temp.day(-17),
       released = CASE WHEN b.load_seq IS NOT NULL THEN coalesce(b.d + 3, 3) END, picked = coalesce(b.d + 2, 2),
       planned = coalesce(b.d + 1, 1)
  FROM w_order o WHERE o.key = b.key;

UPDATE w_order o SET window_start = (SELECT min(b.window_start) FROM w_batch b WHERE b.key = o.key);

-- What each batch is worth on the invoice side: its goods with VAT; the fees ride on the last batch.
UPDATE w_batch b
   SET amount = b.cartons * 1000 * 1200 + round(b.cartons * 1000 * 1200 * o.vat / 100, 0)
                + CASE WHEN b.bseq = o.batches THEN o.plate + o.plate_tax + o.ship + o.ship_tax ELSE 0 END
  FROM w_order o WHERE o.key = b.key;

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
   SET status = 'ACCEPTED', responded_at = pg_temp.at(o.s + 6, '15:00'), responded_by = pg_temp.staff(o.username),
       version = 2, last_modified_at = pg_temp.at(o.s + 6, '15:00'),
       last_modified_by = pg_temp.staff(o.username)::text
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
       pg_temp.at(o.s + 6, '15:00'), pg_temp.staff(o.username)
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
       (SELECT c.email FROM customer.customer c WHERE c.id = o.customer_id), o.phone, o.contact, o.phone, o.line1, o.ward_code, o.ward,
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
       o.total - o.deposit, o.due_after, b.batch_id
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

-- Order.contractSigned: a customer with an installment overdue is held for credit (hold CREDIT, nothing reserved);
-- the others wait for their deposit.
UPDATE ordering.customer_order co
   SET status = CASE WHEN o.held THEN 'ON_HOLD' ELSE 'PENDING_PAYMENT' END,
       hold_reason = CASE WHEN o.held THEN 'CREDIT' END, contract_signed_at = pg_temp.at(o.s, '10:00'),
       placed_at = pg_temp.at(o.s, '10:00'), version = 2, last_modified_at = pg_temp.at(o.s, '10:00'),
       last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE co.id = o.id;

-- 15 BR-10, BR-11: the part paid after delivery checked against the customer's credit - within the limit; what the
-- customer's earlier contracts here pay after delivery is the exposure.
INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome, note,
                                         version, created_at, created_by)
SELECT pg_temp.wid('credit-check:' || o.key), o.id, pg_temp.at(o.s, '10:00'), o.credit_limit,
       -- the receivables still open when it was signed (InstallmentBookAdapter: after delivery and fees, unpaid)
       coalesce((SELECT sum(i.amount - i.reduced_amount - i.paid_amount) FROM payment.installment i
                  WHERE i.customer_id = o.customer_id AND i.status NOT IN ('PAID', 'CANCELLED')
                    AND i.milestone IN ('AFTER_DELIVERY', 'CANCELLATION_FEE', 'FAILED_TRIP_FEE')
                    AND i.created_at < pg_temp.at(o.s, '10:00')), 0),
       o.total - o.deposit, CASE WHEN o.held THEN 'OVERDUE' ELSE 'WITHIN_LIMIT' END,
       'Sales contract ' || o.contract_number, 0, pg_temp.at(o.s, '10:00'), pg_temp.staff('demo.sales')::text
  FROM w_order o;

-- The hold itself (OrderHoldJpaEntity): raised by the signature, nobody's, open.
INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, version, created_at, created_by)
SELECT pg_temp.wid('order-hold:' || o.key), o.id, 'CREDIT', pg_temp.at(o.s, '10:00'), 0, pg_temp.at(o.s, '10:00'),
       pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE o.held;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.wid('order-history:' || o.key || ':' || h.n), o.id, h.from_status,
       coalesce(h.to_status, CASE WHEN o.held THEN 'ON_HOLD' ELSE 'PENDING_PAYMENT' END),
       CASE WHEN h.n = 2 THEN CASE WHEN o.held THEN 'CREDIT' ELSE 'CONTRACT_SIGNED' END ELSE h.reason END,
       CASE WHEN h.n > 0 THEN pg_temp.staff('demo.sales') END, pg_temp.at(o.s + h.day, h.hhmi),
       pg_temp.at(o.s + h.day, h.hhmi), pg_temp.staff('demo.sales')::text
  FROM w_order o
 CROSS JOIN (VALUES (0, NULL, 'DRAFT', NULL, 5, '09:30'),
                    (1, 'DRAFT', 'PENDING_CONTRACT', 'CONTRACT_SENT', 3, '09:00'),
                    (2, 'PENDING_CONTRACT', NULL, NULL, 0, '10:00'))
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
-- 5. Deposits (15 §4.2), the morning after the signature: WF5-F's confirms its order; WF5-TT1B's is recorded while the
--    order is held (the receipt path refuses only a cancelled order) and the order stays ON_HOLD
-- ---------------------------------------------------------------------------------------------
INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, note, status,
                                     recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.wid('receipt:' || o.key || ':1'), o.customer_id, 'FT' || pg_temp.ymd(o.s - 1) || (96000 + o.seq * 10 + 1),
       pg_temp.day(o.s - 1), o.deposit, 'VND', 'Đặt cọc hợp đồng ' || o.contract_number, 'RECORDED',
       pg_temp.staff('demo.ketoan'), pg_temp.at(o.s - 1, '10:30'), 0, pg_temp.at(o.s - 1, '10:30'),
       pg_temp.staff('demo.ketoan')::text
  FROM w_order o WHERE o.key IN ('WF5-TT1B', 'WF5-F');

INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount, created_at)
SELECT pg_temp.wid('receipt-line:' || o.key || ':1'), pg_temp.wid('receipt:' || o.key || ':1'),
       pg_temp.wid('installment:' || o.key || ':1'), o.deposit, pg_temp.at(o.s - 1, '10:30')
  FROM w_order o WHERE o.key IN ('WF5-TT1B', 'WF5-F');

UPDATE payment.installment i
   SET paid_amount = o.deposit, status = 'PAID', paid_at = pg_temp.at(o.s - 1, '10:30'), version = 1,
       last_modified_at = pg_temp.at(o.s - 1, '10:30'), last_modified_by = pg_temp.staff('demo.ketoan')::text
  FROM w_order o WHERE i.id = pg_temp.wid('installment:' || o.key || ':1') AND o.key IN ('WF5-TT1B', 'WF5-F');

UPDATE ordering.customer_order co
   SET status = 'CONFIRMED', version = 3, last_modified_at = pg_temp.at(o.s - 1, '10:30'), last_modified_by = 'system'
  FROM w_order o WHERE co.id = o.id AND o.key = 'WF5-F';

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.wid('order-history:' || o.key || ':3'), o.id, 'PENDING_PAYMENT', 'CONFIRMED', 'INSTALLMENT_PAID', NULL,
       pg_temp.at(o.s - 1, '10:30'), pg_temp.at(o.s - 1, '10:30'), 'system'
  FROM w_order o WHERE o.key = 'WF5-F';

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 6. WF5-F, three days ago: the customer withdrew before printing (Order.cancel, CUSTOMER_REQUEST, nothing kept); what
--    was not paid is cancelled, what was paid is refundable (payment OrderSettlement.cancel); two days ago the
--    accountant refunded it. The contract stays SIGNED: the nightly run liquidates it.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.wid('order-history:' || o.key || ':cancelled'), o.id, 'CONFIRMED', 'CANCELLED',
       'CUSTOMER_REQUEST: Khách đổi mẫu thiết kế, rút đơn trước khi in', pg_temp.staff('demo.sales'),
       pg_temp.at(3, '11:00'), pg_temp.at(3, '11:00'), pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE o.key = 'WF5-F';

UPDATE ordering.customer_order co
   SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST',
       cancellation_reason = 'CUSTOMER_REQUEST: Khách đổi mẫu thiết kế, rút đơn trước khi in',
       cancellation_retained_amount = 0, version = co.version + 1, last_modified_at = pg_temp.at(3, '11:00'),
       last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE co.id = o.id AND o.key = 'WF5-F';

UPDATE payment.installment i
   SET status = 'CANCELLED', cancelled_at = pg_temp.at(3, '11:00'), version = i.version + 1,
       last_modified_at = pg_temp.at(3, '11:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE i.order_id = o.id AND o.key = 'WF5-F' AND i.status <> 'PAID';

INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
                                      source_id, currency, collected_amount, closed_amount, reduced_amount,
                                      surplus_amount, fee_amount, fee_covered_amount, refundable_amount,
                                      fee_owed_amount, settled_by, settled_at, version, created_at, created_by)
SELECT pg_temp.wid('settlement:' || o.key), o.id, o.contract_id, o.customer_id, o.order_number, o.contract_number,
       'ORDER_CANCELLED', o.id, 'VND', o.deposit, 0, 0, o.deposit, 0, 0, o.deposit, 0, pg_temp.staff('demo.sales'),
       pg_temp.at(3, '11:00'), 0, pg_temp.at(3, '11:00'), pg_temp.staff('demo.sales')::text
  FROM w_order o WHERE o.key = 'WF5-F';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO payment.refund_record (id, order_id, customer_id, order_number, amount, currency, refunded_on, reference,
                                   note, status, recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.wid('refund:' || o.key), o.id, o.customer_id, o.order_number, o.deposit, 'VND', pg_temp.day(2),
       'FT' || pg_temp.ymd(2) || 'HT' || (9600 + o.seq), 'Hoàn tiền đặt cọc ' || o.contract_number, 'RECORDED',
       pg_temp.staff('demo.ketoan'), pg_temp.at(2, '10:00'), 0, pg_temp.at(2, '10:00'),
       pg_temp.staff('demo.ketoan')::text
  FROM w_order o WHERE o.key = 'WF5-F';

DROP FUNCTION pg_temp.wid(TEXT);
DROP FUNCTION pg_temp.today();
DROP FUNCTION pg_temp.at(INTEGER, TEXT);
DROP FUNCTION pg_temp.day(INTEGER);
DROP FUNCTION pg_temp.ymd(INTEGER);
DROP FUNCTION pg_temp.staff(TEXT);
