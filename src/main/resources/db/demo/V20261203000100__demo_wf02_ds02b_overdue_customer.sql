-- =============================================================================
-- Demo: DS-02b of the WF-02 review as written - a SECOND customer, one with an instalment overdue, whose new contract
-- pays after delivery (kltn-docs 14 §4.3 steps 13-15, 15 §4.3 steps 9-10, BR-10, BR-11). Demo only (db/demo). Ids are
-- md5('demo:wf02b:...')::uuid; the history's rows are md5('demo:history:...')::uuid (V20261128000200).
--
-- Why a new file: V20261124000600 seeded DS-02b on the demo customer of DS-02a - credit 500,000,000 and nothing owed -
-- with its contract SENT already. Signed, that contract checks WITHIN_LIMIT and its order goes PENDING_PAYMENT: the
-- credit hold the script shows never happens, nor the warning before sending. That order stays as it is (a contract
-- sent and waiting for its signature; Sales may cancel and redraw it); the credit exception is this one.
--
-- The customer: Công ty CP Trà Sữa Mây ("TSM", portal login demo.kh.trasuamay, the published demo password of
-- tools/db/seed), credit 300,000,000 at 15 days. V20261128000200 left it an instalment overdue: order O01 was delivered
-- 108 days before the build day and what it pays after delivery was never paid (due 93 days before the build day). Its
-- cup LY-GIAY-DEMO-12OZ-TSM was produced before (O01, O02, O08), so a reorder has no plate fee (14 BR-17); its own price
-- by cartons is 1,300 from 20, 1,200 from 50, 1,100 from 100 (VND a cup).
--
-- What is left for the presenter, everything as DRAFT:
--   * quote QT-20261202-9002, sent, accepted by the customer at 60 cartons (the 50-carton tier: 1,200 a cup) and
--     converted into order SO-20261202-900002 (DRAFT, nothing held): 72,000,000 + VAT at the STANDARD rate of the build
--     day (8% through 2026, Nghị định 174/2025: 5,760,000; 10% after);
--   * its contract HD-20261202-900002-1, DRAFT: batch 1 of 30 cartons three weeks out, batch 2 of 30 five weeks out;
--     ON_SIGNING 30%, BEFORE_DELIVERY tied to batch 1, AFTER_DELIVERY tied to batch 2 (15 days) - the rest in halves;
--   * a conversation "Hợp đồng SO-20261202-900002" about the order, assigned to demo.sales.
-- Click path: demo.sales opens the contract - the DRAFT already reads creditWarning OVERDUE (14 §4.3 step 13: Sales
-- decide before sending) - and sends it (warned, never refused); uploads the signed scan: the order goes ON_HOLD for
-- CREDIT (outcome OVERDUE recorded). demo.ketoan opens the credit holds and approves with a note: PENDING_PAYMENT, the
-- deposit still unpaid - or refuses with a note: CANCELLED, CREDIT_REJECTED, and whatever was collected meanwhile (the
-- deposit, recorded while held) is to refund.
--
-- Loaded with every trigger on, in the order the application writes the rows, each step closed as the application
-- commits it, so the deferred checks (quote, order totals, contract schedule and plan) see what the application leaves.
-- =============================================================================

-- The build day in Vietnam, the STANDARD rate the application takes on it (TaxRates.onDay: a period, else the class),
-- and every figure below.
CREATE TEMP TABLE ds02b ON COMMIT DROP AS
WITH day AS (SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS today),
     rate AS (SELECT coalesce((SELECT p.rate FROM ordering.tax_rate_period p, day
                                WHERE p.tax_class = 'STANDARD' AND day.today BETWEEN p.valid_from AND p.valid_to),
                              (SELECT r.rate FROM ordering.tax_rate r WHERE r.tax_class = 'STANDARD')) AS vat),
     goods AS (SELECT day.today, rate.vat, 72000000::numeric AS net, round(72000000 * rate.vat / 100, 0) AS tax
                 FROM day, rate)
SELECT today, vat, net, tax, net + tax AS total, round((net + tax) * 30 / 100, 0) AS deposit,
       floor(((net + tax) - round((net + tax) * 30 / 100, 0)) / 2) AS before_batch1,
       (net + tax) - round((net + tax) * 30 / 100, 0) - floor(((net + tax) - round((net + tax) * 30 / 100, 0)) / 2)
           AS after_batch2
  FROM goods;

-- ---------------------------------------------------------------------------------------------
-- 1. The quote: drawn up and sent by Sales, accepted by the customer at 60 cartons
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, revision, valid_until, terms, sent_at,
                            sent_by, responded_at, responded_by, requested_delivery_date, version, created_at,
                            created_by)
SELECT md5('demo:wf02b:quote')::uuid, 'QT-20261202-9002', md5('demo:wf02b:quote-request')::uuid,
       md5('demo:history:customer:TSM')::uuid, 'DRAFT', 1, NOW() + INTERVAL '10 days',
       'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.', NULL, NULL, NULL, NULL, d.today + 21, 0,
       NOW() - INTERVAL '3 days', md5('demo:wf02:user:demo.sales')::uuid::text
  FROM ds02b d;

INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton, tax_class,
                                 tax_rate)
SELECT md5('demo:wf02b:quote-line')::uuid, md5('demo:wf02b:quote')::uuid, 1, 'LY-GIAY-DEMO-12OZ-TSM',
       md5('demo:history:unit:TSM')::uuid, 60, 1000, 'STANDARD', d.vat
  FROM ds02b d;

INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
VALUES (md5('demo:wf02b:quote-line')::uuid, 20, 1300),
       (md5('demo:wf02b:quote-line')::uuid, 50, 1200),
       (md5('demo:wf02b:quote-line')::uuid, 100, 1100);

UPDATE ordering.quote
   SET status = 'SENT', sent_at = NOW() - INTERVAL '3 days' + INTERVAL '1 hour',
       sent_by = md5('demo:wf02:user:demo.sales')::uuid, version = 1,
       last_modified_at = NOW() - INTERVAL '3 days' + INTERVAL '1 hour',
       last_modified_by = md5('demo:wf02:user:demo.sales')::uuid::text
 WHERE id = md5('demo:wf02b:quote')::uuid;

UPDATE ordering.quote_line SET accepted_cartons = 60, accepted_unit_price = 1200
 WHERE id = md5('demo:wf02b:quote-line')::uuid;

UPDATE ordering.quote
   SET status = 'ACCEPTED', responded_at = NOW() - INTERVAL '2 days',
       responded_by = md5('demo:history:user:demo.kh.trasuamay')::uuid, version = 2,
       last_modified_at = NOW() - INTERVAL '2 days',
       last_modified_by = md5('demo:history:user:demo.kh.trasuamay')::uuid::text
 WHERE id = md5('demo:wf02b:quote')::uuid;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, offer, changed_at,
                                           changed_by)
SELECT md5('demo:wf02b:quote-history:0')::uuid, md5('demo:wf02b:quote')::uuid, 1, NULL, 'DRAFT', NULL, NULL,
       NOW() - INTERVAL '3 days', md5('demo:wf02:user:demo.sales')::uuid
UNION ALL
SELECT md5('demo:wf02b:quote-history:1')::uuid, md5('demo:wf02b:quote')::uuid, 1, 'DRAFT', 'SENT', NULL,
       jsonb_build_object(
           'revision', 1,
           'validUntil', to_char((NOW() + INTERVAL '10 days') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
           'terms', 'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.',
           'requestedDeliveryDate', to_char(d.today + 21, 'YYYY-MM-DD'),
           'lines', jsonb_build_array(jsonb_build_object(
               'lineNo', 1, 'sku', 'LY-GIAY-DEMO-12OZ-TSM', 'uomConversionId', md5('demo:history:unit:TSM')::uuid,
               'cartons', 60, 'unitsPerCarton', 1000, 'taxClass', 'STANDARD', 'taxRate', d.vat,
               'tiers', jsonb_build_array(jsonb_build_object('minQuantity', 20, 'unitPrice', 1300),
                                          jsonb_build_object('minQuantity', 50, 'unitPrice', 1200),
                                          jsonb_build_object('minQuantity', 100, 'unitPrice', 1100)))),
           'fees', '[]'::jsonb),
       NOW() - INTERVAL '3 days' + INTERVAL '1 hour', md5('demo:wf02:user:demo.sales')::uuid
  FROM ds02b d
UNION ALL
SELECT md5('demo:wf02b:quote-history:2')::uuid, md5('demo:wf02b:quote')::uuid, 1, 'SENT', 'ACCEPTED', NULL, NULL,
       NOW() - INTERVAL '2 days', md5('demo:history:user:demo.kh.trasuamay')::uuid;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 2. Converted into a DRAFT order holding nothing (14 §4.2): 60 cartons at 1,200 a cup, VAT of the day
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, contact_name, contact_email, contact_phone,
                                     shipping_recipient_name, shipping_phone, shipping_line1, shipping_ward_code,
                                     shipping_ward_name, shipping_province_code, shipping_province_name,
                                     shipping_country_code, billing_recipient_name, billing_phone, billing_line1,
                                     billing_ward_code, billing_ward_name, billing_province_code, billing_province_name,
                                     billing_country_code, version, created_at, created_by)
SELECT md5('demo:wf02b:order')::uuid, 'SO-20261202-900002', md5('demo:history:customer:TSM')::uuid,
       md5('demo:wf02b:order-request')::uuid, 'DRAFT', d.total, d.tax, 'VND', NOW() - INTERVAL '1 day',
       'Trần Thị Mây', 'muahang.tsm@stockflow.local', '0901110012', 'Trần Thị Mây', '0901110012', '18 Võ Văn Tần',
       '27142', 'Phường Xuân Hòa', '79', 'TP. Hồ Chí Minh', 'VN', 'Công ty CP Trà Sữa Mây', '0901110012',
       '18 Võ Văn Tần', '27142', 'Phường Xuân Hòa', '79', 'TP. Hồ Chí Minh', 'VN', 0, NOW() - INTERVAL '1 day',
       md5('demo:wf02:user:demo.sales')::uuid::text
  FROM ds02b d;

INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, requested_delivery_date,
                                 uom_conversion_id, cartons, units_per_carton, tax_rate, tax_amount)
SELECT md5('demo:wf02b:order-line')::uuid, md5('demo:wf02b:order')::uuid, 'LY-GIAY-DEMO-12OZ-TSM', 60000, 1200, 'VND',
       d.today + 21, md5('demo:history:unit:TSM')::uuid, 60, 1000, d.vat, d.tax
  FROM ds02b d;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
VALUES (md5('demo:wf02b:order-history:0')::uuid, md5('demo:wf02b:order')::uuid, NULL, 'DRAFT',
        'Chuyển từ báo giá QT-20261202-9002', md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '1 day',
        NOW() - INTERVAL '1 day', md5('demo:wf02:user:demo.sales')::uuid::text);

UPDATE ordering.quote
   SET status = 'CONVERTED', converted_order_id = md5('demo:wf02b:order')::uuid, converted_at = NOW() - INTERVAL '1 day',
       version = 3, last_modified_at = NOW() - INTERVAL '1 day',
       last_modified_by = md5('demo:wf02:user:demo.sales')::uuid::text
 WHERE id = md5('demo:wf02b:quote')::uuid;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, changed_at,
                                           changed_by)
VALUES (md5('demo:wf02b:quote-history:3')::uuid, md5('demo:wf02b:quote')::uuid, 1, 'ACCEPTED', 'CONVERTED',
        'SO-20261202-900002', NOW() - INTERVAL '1 day', md5('demo:wf02:user:demo.sales')::uuid);

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 3. Its contract, DRAFT: two batches of 30 cartons; 30% on signing, batch 1 before delivery, batch 2 after it
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
                                     seller_address, seller_representative, seller_representative_title, buyer_name,
                                     buyer_tax_code, buyer_address, buyer_representative, buyer_representative_title,
                                     free_sample_rounds, return_window_days, version, created_at, created_by)
VALUES (md5('demo:wf02b:contract')::uuid, 'HD-20261202-900002-1', md5('demo:wf02b:order')::uuid, 'DRAFT',
        'DEMO STOCKFLOW CO LTD', '0000000000', '1 Demo Street, Ho Chi Minh City', 'Demo Director', 'Director',
        'Công ty CP Trà Sữa Mây', '0399990012', '18 Võ Văn Tần, Phường Xuân Hòa, TP. Hồ Chí Minh', 'Trần Thị Mây',
        'Giám đốc', 2, 7, 0, NOW() - INTERVAL '20 hours', md5('demo:wf02:user:demo.sales')::uuid::text);

INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
SELECT md5('demo:wf02b:batch:1')::uuid, md5('demo:wf02b:contract')::uuid, 1, d.today + 21, d.today + 23 FROM ds02b d
UNION ALL
SELECT md5('demo:wf02b:batch:2')::uuid, md5('demo:wf02b:contract')::uuid, 2, d.today + 35, d.today + 37 FROM ds02b d;

INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (md5('demo:wf02b:batch-line:1')::uuid, md5('demo:wf02b:batch:1')::uuid, md5('demo:wf02b:order-line')::uuid, 30),
       (md5('demo:wf02b:batch-line:2')::uuid, md5('demo:wf02b:batch:2')::uuid, md5('demo:wf02b:order-line')::uuid, 30);

INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
SELECT md5('demo:wf02b:plan:1')::uuid, md5('demo:wf02b:contract')::uuid, 1, 'Đặt cọc khi ký', 'ON_SIGNING', 30,
       d.deposit, 3, NULL
  FROM ds02b d
UNION ALL
SELECT md5('demo:wf02b:plan:2')::uuid, md5('demo:wf02b:contract')::uuid, 2, 'Thanh toán trước giao đợt 1',
       'BEFORE_DELIVERY', NULL, d.before_batch1, NULL, md5('demo:wf02b:batch:1')::uuid
  FROM ds02b d
UNION ALL
SELECT md5('demo:wf02b:plan:3')::uuid, md5('demo:wf02b:contract')::uuid, 3, 'Thanh toán sau giao đợt 2',
       'AFTER_DELIVERY', NULL, d.after_batch2, 15, md5('demo:wf02b:batch:2')::uuid
  FROM ds02b d;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 4. The chat: a conversation about the order, assigned to demo.sales
-- ---------------------------------------------------------------------------------------------
INSERT INTO chat.conversation (id, customer_id, subject, status, assigned_agent_id, assigned_at, opened_by_type,
                               opened_by, status_changed_at, first_response_due_at, resolution_due_at, version,
                               created_at, created_by)
VALUES (md5('demo:wf02b:conversation')::uuid, md5('demo:history:customer:TSM')::uuid, 'Hợp đồng SO-20261202-900002',
        'ASSIGNED', md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '20 hours', 'AGENT',
        md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '20 hours',
        NOW() - INTERVAL '20 hours' + INTERVAL '15 minutes', NOW() + INTERVAL '1 day', 0, NOW() - INTERVAL '20 hours',
        md5('demo:wf02:user:demo.sales')::uuid::text);

INSERT INTO chat.conversation_context (id, conversation_id, kind, order_id, is_primary, attached_by, attached_at,
                                       version, created_at, created_by)
VALUES (md5('demo:wf02b:context:order')::uuid, md5('demo:wf02b:conversation')::uuid, 'ORDER',
        md5('demo:wf02b:order')::uuid, TRUE, md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '20 hours', 0,
        NOW() - INTERVAL '20 hours', md5('demo:wf02:user:demo.sales')::uuid::text);

INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, body, card_kind,
                          card_payload, card_state, context_id, sent_at, version, created_at, created_by)
VALUES
    (md5('demo:wf02b:message:1')::uuid, md5('demo:wf02b:conversation')::uuid, 1, 'TEXT', 'CUSTOMER', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid,
     'Em đã tạo đơn SO-20261202-900002 (60 thùng ly logo Trà Sữa Mây) và đang soạn hợp đồng: giao 2 đợt, mỗi đợt 30 thùng; đặt cọc 30% khi ký, đợt 1 thanh toán trước khi giao, đợt 2 thanh toán sau khi giao 15 ngày.',
     NULL, NULL, NULL, md5('demo:wf02b:context:order')::uuid, NOW() - INTERVAL '20 hours', 0,
     NOW() - INTERVAL '20 hours', md5('demo:wf02:user:demo.sales')::uuid::text),
    (md5('demo:wf02b:message:2')::uuid, md5('demo:wf02b:conversation')::uuid, 2, 'TEXT', 'CUSTOMER', 'CUSTOMER',
     md5('demo:history:user:demo.kh.trasuamay')::uuid, 'Ok em, gửi hợp đồng cho chị ký nhé.', NULL, NULL, NULL, NULL,
     NOW() - INTERVAL '18 hours', 0, NOW() - INTERVAL '18 hours',
     md5('demo:history:user:demo.kh.trasuamay')::uuid::text);

UPDATE chat.conversation
   SET status = 'ACTIVE', status_changed_at = NOW() - INTERVAL '20 hours', first_responded_at = NOW() - INTERVAL '20 hours',
       last_message_seq = 2, last_message_at = NOW() - INTERVAL '18 hours',
       last_customer_message_at = NOW() - INTERVAL '18 hours'
 WHERE id = md5('demo:wf02b:conversation')::uuid;

INSERT INTO chat.participant_read (id, conversation_id, user_id, reader_type, last_read_seq, last_read_message_id,
                                   last_read_at, version, created_at, created_by)
VALUES (md5('demo:wf02b:read:customer')::uuid, md5('demo:wf02b:conversation')::uuid,
        md5('demo:history:user:demo.kh.trasuamay')::uuid, 'CUSTOMER', 2, md5('demo:wf02b:message:2')::uuid,
        NOW() - INTERVAL '18 hours', 0, NOW() - INTERVAL '18 hours',
        md5('demo:history:user:demo.kh.trasuamay')::uuid::text);

INSERT INTO chat.conversation_event (id, conversation_id, type, actor_type, actor_id, occurred_at, from_status,
                                     to_status, context_id, message_id, target_user_id, note, detail, version,
                                     created_at, created_by)
VALUES
    (md5('demo:wf02b:event:1')::uuid, md5('demo:wf02b:conversation')::uuid, 'OPENED', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '20 hours', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0,
     NOW() - INTERVAL '20 hours', md5('demo:wf02:user:demo.sales')::uuid::text),
    (md5('demo:wf02b:event:2')::uuid, md5('demo:wf02b:conversation')::uuid, 'CONTEXT_ATTACHED', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '20 hours', NULL, NULL,
     md5('demo:wf02b:context:order')::uuid, NULL, NULL, NULL, NULL, 0, NOW() - INTERVAL '20 hours',
     md5('demo:wf02:user:demo.sales')::uuid::text),
    (md5('demo:wf02b:event:3')::uuid, md5('demo:wf02b:conversation')::uuid, 'FIRST_RESPONSE', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '20 hours', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0,
     NOW() - INTERVAL '20 hours', md5('demo:wf02:user:demo.sales')::uuid::text);
