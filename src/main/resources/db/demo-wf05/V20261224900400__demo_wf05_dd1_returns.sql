-- =============================================================================
-- Demo WF-05, second round (seed gap G-9, DĐ-1): WF5-DD1A and WF5-DD1B of V20261224900200 - two deliveries 42 and 38
-- days ago, paid in full (V20261224900300) - each had one misprinted carton of its first delivery returned, the refund
-- paid 31 days ago: PARTIALLY_REFUNDED, on the branch that closes 30 days after the refund once every delivered
-- shipment's window is over and every delivery is invoiced (OrderSettlementService.close, WF-05 DĐ-1). Demo only
-- (db/demo-wf05).
--
--   WF5-DD1A  its second delivery is not invoiced (a draft, refused and reopened - V20261224900300): the readiness says
--             SHIPMENT_UNINVOICED and the nightly run leaves it open; issued, the next run closes it and completes the
--             contract.
--   WF5-DD1B  both deliveries invoiced; only the return's decrease adjustment is still a draft: the run closes it, the
--             readiness having shown "còn điều chỉnh trả hàng chưa phát hành" as information (Q-16).
--
-- Each step as the application writes it, in its order (ReturnRequestService, ReturnReceiptService,
-- CartonReturnService, payment OrderSettlementService.settleReturn, ReturnResolvedListener →
-- EInvoiceCorrections.drawUpForReturn, RefundService.record → ReturnedGoodsListener):
--   day 37 10:00 the customer opens it on the portal (PRINT_DEFECT, a photo), on shipment 1; 14:00 Sales approve it;
--   day 36 09:00 the warehouse opens the return receipt into DNI-QC01 and scans the carton, 10:00 the manager confirms:
--          the carton back in stock in quarantine, still the customer's until the return is settled;
--   day 35 09:00 Sales propose the refund of the carton (its share of the line with VAT, 1,296,000); 14:00 the
--          accountant approves it: the order was paid in full, so all of it is refundable; the carton becomes the
--          company's; the decrease adjustment of shipment 1's invoice is drawn up;
--   day 31 10:00 the accountant records the refund: PARTIALLY_REFUNDED.
-- =============================================================================

CREATE FUNCTION pg_temp.wid(key TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT md5('demo:wf05:' || key)::uuid $$;

CREATE FUNCTION pg_temp.today() RETURNS DATE LANGUAGE sql STABLE AS
$$ SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date $$;

CREATE FUNCTION pg_temp.at(days_ago INTEGER, hhmi TEXT) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT ((pg_temp.today() - days_ago) + hhmi::time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

CREATE FUNCTION pg_temp.ymd(days_ago INTEGER) RETURNS TEXT LANGUAGE sql STABLE AS
$$ SELECT to_char(pg_temp.today() - days_ago, 'YYYYMMDD') $$;

CREATE FUNCTION pg_temp.staff(username TEXT) RETURNS UUID LANGUAGE sql STABLE AS
$$ SELECT id FROM identity.app_user WHERE username = $1 $$;

-- The two returns: the order, its first shipment, the carton that comes back (the first of that batch), the figures.
CREATE TEMP TABLE w_ret ON COMMIT DROP AS
SELECT x.okey, x.n, pg_temp.wid('return:' || x.okey) AS return_id,
       'RMA-' || pg_temp.ymd(37) || '-' || (9600 + x.n) AS rma, 'GR-' || pg_temp.ymd(36) || '-' || (9690 + x.n) AS gr,
       pg_temp.wid('return-receipt:' || x.okey) AS receipt_id, co.id AS order_id, co.order_number, co.customer_id,
       c.user_id AS customer_user, sh.id AS shipment_id, ol.id AS line_id, ol.sku, ol.unit_price,
       ol.quantity AS line_qty, round(ol.quantity * ol.unit_price, 0) + ol.tax_amount AS line_gross,
       p.id AS package_id, p.lpn, si.id AS stock_item_id, si.lot_number, si.lot_id,
       k.id AS contract_id, k.contract_number
  FROM (VALUES ('WF5-DD1A', 1), ('WF5-DD1B', 2)) AS x (okey, n)
  JOIN ordering.customer_order co ON co.id = pg_temp.wid('order:' || x.okey)
  JOIN customer.customer c ON c.id = co.customer_id
  JOIN ordering.order_shipment sh ON sh.id = pg_temp.wid('shipment:' || x.okey || '-1')
  JOIN ordering.order_line ol ON ol.order_id = co.id
  JOIN inventory.stock_package p ON p.id = pg_temp.wid('lpn:' || x.okey || '-1:1')
  JOIN inventory.stock_item si ON si.stock_package_id = p.id
  JOIN ordering.sales_contract k ON k.order_id = co.id AND k.status = 'SIGNED';

ALTER TABLE w_ret ADD COLUMN refund NUMERIC;
-- OrderLine.grossBetween(0, 1000): the line's total with VAT, pro rata, rounded half up.
UPDATE w_ret SET refund = round(line_gross * 1000 / line_qty, 0);

-- ---------------------------------------------------------------------------------------------
-- 1. Day 37 10:00: opened by the customer on the portal, on shipment 1, with a photo
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.wid('order-history:' || r.okey || ':return-requested'), r.order_id, 'DELIVERED', 'RETURN_REQUESTED',
       'RETURN_REQUESTED ' || r.rma, r.customer_user, pg_temp.at(37, '10:00'), pg_temp.at(37, '10:00'),
       r.customer_user::text
  FROM w_ret r;

UPDATE ordering.customer_order co
   SET status = 'RETURN_REQUESTED', status_before_return = 'DELIVERED', version = co.version + 1,
       last_modified_at = pg_temp.at(37, '10:00'), last_modified_by = r.customer_user::text
  FROM w_ret r WHERE co.id = r.order_id;

INSERT INTO ordering.return_request (id, rma_number, order_id, customer_id, shipment_id, status, reason_code, note,
                                     requested_at, order_number, request_id, channel, requested_by,
                                     requested_resolution, currency, version, created_at, created_by)
SELECT r.return_id, r.rma, r.order_id, r.customer_id, r.shipment_id, 'REQUESTED', 'PRINT_DEFECT',
       '1 thùng in lệch màu logo, giao chuyến 1', pg_temp.at(37, '10:00'), r.order_number,
       pg_temp.wid('return-request:' || r.okey), 'PORTAL', r.customer_user, 'REFUND', 'VND', 0, pg_temp.at(37, '10:00'),
       r.customer_user::text
  FROM w_ret r;

INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, quantity, sku, custom_printed,
                                          unit_price, created_at, created_by)
SELECT pg_temp.wid('return-line:' || r.okey), r.return_id, r.line_id, 1000, r.sku, TRUE, r.unit_price,
       pg_temp.at(37, '10:00'), r.customer_user::text
  FROM w_ret r;

-- The photo's key, like V20261203000500's: no object behind it (the scene opens the order, not the photo).
INSERT INTO ordering.return_request_evidence (id, return_request_id, stage, file_key, original_name, content_type,
                                              size_bytes, uploaded_by, uploaded_at, created_at, created_by)
SELECT pg_temp.wid('return-evidence:' || r.okey), r.return_id, 'CLAIM', 'return-evidence/demo/' || r.rma || '-1.jpg',
       'thung-in-lech-mau.jpg', 'image/jpeg', 184320, r.customer_user, pg_temp.at(37, '10:00'), pg_temp.at(37, '10:00'),
       r.customer_user::text
  FROM w_ret r;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.wid('return-history:' || r.okey || ':0'), r.return_id, NULL, 'REQUESTED', 'PRINT_DEFECT',
       r.customer_user, pg_temp.at(37, '10:00'), pg_temp.at(37, '10:00'), r.customer_user::text
  FROM w_ret r;

-- (the application commits here: the deferred checks run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 2. Day 37 14:00: approved by Sales, the carton to come back within 15 days
-- ---------------------------------------------------------------------------------------------
UPDATE ordering.return_request_line l SET approved_quantity = l.quantity FROM w_ret r WHERE l.return_request_id = r.return_id;

UPDATE ordering.return_request q
   SET status = 'AWAITING_RETURN', decided_by = pg_temp.staff('demo.sales'), decided_at = pg_temp.at(37, '14:00'),
       decision_note = 'Đồng ý nhận lại thùng lỗi in', return_required = TRUE,
       return_deadline = ((pg_temp.today() - 37 + 15)::timestamp) AT TIME ZONE 'Asia/Ho_Chi_Minh', version = 1,
       last_modified_at = pg_temp.at(37, '14:00'), last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_ret r WHERE q.id = r.return_id;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.wid('return-history:' || r.okey || ':1'), r.return_id, 'REQUESTED', 'AWAITING_RETURN', 'APPROVED',
       pg_temp.staff('demo.sales'), pg_temp.at(37, '14:00'), pg_temp.at(37, '14:00'), pg_temp.staff('demo.sales')::text
  FROM w_ret r;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 3. Day 36 09:00: the return receipt into DNI-QC01, the carton scanned by its LPN
-- ---------------------------------------------------------------------------------------------
INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                        receipt_type, customer_order_id, return_request_id, quarantine_location_id,
                                        note, version, created_at, created_by)
SELECT r.receipt_id, r.gr, md5('demo:history:wh:DNI')::uuid, 'DRAFT', pg_temp.at(36, '09:00'),
       pg_temp.staff('demo.kho.dn'), 'RETURN', r.order_id, r.return_id, md5('demo:history:location:DNI-QC01')::uuid,
       'Hàng trả theo ' || r.rma, 0, pg_temp.at(36, '09:00'), pg_temp.staff('demo.kho.dn')::text
  FROM w_ret r;

UPDATE ordering.return_request q
   SET open_draft_receipt_id = r.receipt_id, last_modified_at = pg_temp.at(36, '09:00'),
       last_modified_by = pg_temp.staff('demo.kho.dn')::text
  FROM w_ret r WHERE q.id = r.return_id;

INSERT INTO procurement.goods_receipt_return_cartons (id, receipt_id, stock_package_id, lpn, sku, lot_number, quantity,
                                                      condition_note, scanned_by, created_at, created_by)
SELECT pg_temp.wid('return-carton:' || r.okey), r.receipt_id, r.package_id, r.lpn, r.sku, r.lot_number, 1000,
       'Thùng còn nguyên đai, logo in lệch màu', pg_temp.staff('demo.kho.dn'), pg_temp.at(36, '09:10'),
       pg_temp.staff('demo.kho.dn')::text
  FROM w_ret r;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 4. Day 36 10:00: confirmed by the manager - the carton in quarantine, still the customer's; the goods are back
-- ---------------------------------------------------------------------------------------------
SELECT set_config('stockflow.same_receipt_merge', r.stock_item_id::text, true) FROM w_ret r WHERE r.n = 1;
UPDATE inventory.stock_package p
   SET status = 'IN_STOCK', location_code = 'DNI-QC01', closed_at = NULL, version = p.version + 1,
       last_modified_at = pg_temp.at(36, '10:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r WHERE p.id = r.package_id AND r.n = 1;
UPDATE inventory.stock_item s
   SET location_code = 'DNI-QC01', on_hand = 1000, status = 'QUARANTINE', version = s.version + 1,
       last_modified_at = pg_temp.at(36, '10:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r WHERE s.id = r.stock_item_id AND r.n = 1;
SELECT set_config('stockflow.same_receipt_merge', r.stock_item_id::text, true) FROM w_ret r WHERE r.n = 2;
UPDATE inventory.stock_package p
   SET status = 'IN_STOCK', location_code = 'DNI-QC01', closed_at = NULL, version = p.version + 1,
       last_modified_at = pg_temp.at(36, '10:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r WHERE p.id = r.package_id AND r.n = 2;
UPDATE inventory.stock_item s
   SET location_code = 'DNI-QC01', on_hand = 1000, status = 'QUARANTINE', version = s.version + 1,
       last_modified_at = pg_temp.at(36, '10:00'), last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r WHERE s.id = r.stock_item_id AND r.n = 2;
SELECT set_config('stockflow.same_receipt_merge', '', true);

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, lot_id, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, actor_id,
                                      to_stock_package_id, order_id, occurred_at, version, created_at, created_by)
SELECT pg_temp.wid('ledger:return:' || r.okey), 'RECEIPT', r.sku, r.lot_number, r.lot_id, NULL, 'DNI-QC01', 1000,
       'QUARANTINE', 'QUARANTINE', 'GOODS_RECEIPT', r.receipt_id, pg_temp.staff('demo.quanly.dn'), r.package_id,
       r.order_id, pg_temp.at(36, '10:00'), 0, pg_temp.at(36, '10:00'), pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r;

UPDATE inventory.stock_package p
   SET return_request_id = r.return_id, return_ownership = 'PENDING'
  FROM w_ret r WHERE p.id = r.package_id;

UPDATE procurement.goods_receipts g
   SET status = 'CLOSED', confirmed_at = pg_temp.at(36, '10:00'), confirmed_by = pg_temp.staff('demo.quanly.dn'),
       closed_at = pg_temp.at(36, '10:00'), version = 1, last_modified_at = pg_temp.at(36, '10:00'),
       last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r WHERE g.id = r.receipt_id;

UPDATE ordering.return_request_line l SET received_quantity = 1000 FROM w_ret r WHERE l.return_request_id = r.return_id;

UPDATE ordering.return_request q
   SET status = 'RECEIVED', open_draft_receipt_id = NULL, goods_receipt_id = r.receipt_id,
       received_by = pg_temp.staff('demo.quanly.dn'), received_at = pg_temp.at(36, '10:00'),
       receiving_location_code = 'DNI-QC01', version = 2, last_modified_at = pg_temp.at(36, '10:00'),
       last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r WHERE q.id = r.return_id;

INSERT INTO ordering.return_request_receipt (goods_receipt_id, return_request_id, parcel, receipt_number,
                                             receiving_location_code, received_by, received_at, created_at, created_by)
SELECT r.receipt_id, r.return_id, 1, r.gr, 'DNI-QC01', pg_temp.staff('demo.quanly.dn'), pg_temp.at(36, '10:00'),
       pg_temp.at(36, '10:00'), pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.wid('return-history:' || r.okey || ':2'), r.return_id, 'AWAITING_RETURN', 'RECEIVED', 'RECEIVED ' || r.gr,
       pg_temp.staff('demo.quanly.dn'), pg_temp.at(36, '10:00'), pg_temp.at(36, '10:00'),
       pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.wid('order-history:' || r.okey || ':returned'), r.order_id, 'RETURN_REQUESTED', 'RETURNED',
       'RETURN_GOODS_BACK', pg_temp.staff('demo.quanly.dn'), pg_temp.at(36, '10:00'), pg_temp.at(36, '10:00'),
       pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r;

UPDATE ordering.customer_order co
   SET status = 'RETURNED', version = co.version + 1, last_modified_at = pg_temp.at(36, '10:00'),
       last_modified_by = pg_temp.staff('demo.quanly.dn')::text
  FROM w_ret r WHERE co.id = r.order_id;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 5. Day 35 09:00: Sales propose the refund of the carton
-- ---------------------------------------------------------------------------------------------
UPDATE ordering.return_request_line l
   SET refund_quantity = 1000, refund_amount = r.refund
  FROM w_ret r WHERE l.return_request_id = r.return_id;

UPDATE ordering.return_request q
   SET status = 'SETTLEMENT_PENDING', settlement_amount = r.refund, money_mode = 'REFUND',
       proposed_by = pg_temp.staff('demo.sales'), proposed_at = pg_temp.at(35, '09:00'),
       resolution_note = 'Hoàn tiền 1 thùng lỗi in', version = 3, last_modified_at = pg_temp.at(35, '09:00'),
       last_modified_by = pg_temp.staff('demo.sales')::text
  FROM w_ret r WHERE q.id = r.return_id;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.wid('return-history:' || r.okey || ':3'), r.return_id, 'RECEIVED', 'SETTLEMENT_PENDING',
       'REFUND_PROPOSED ' || r.refund::bigint || ' VND', pg_temp.staff('demo.sales'), pg_temp.at(35, '09:00'),
       pg_temp.at(35, '09:00'), pg_temp.staff('demo.sales')::text
  FROM w_ret r;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 6. Day 35 14:00: approved by the accountant (four eyes) - paid in full, so all of it is refundable
-- ---------------------------------------------------------------------------------------------
INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
                                      source_id, currency, collected_amount, closed_amount, reduced_amount,
                                      surplus_amount, fee_amount, fee_covered_amount, refundable_amount,
                                      fee_owed_amount, settled_by, settled_at, version, created_at, created_by)
SELECT pg_temp.wid('settlement:' || r.okey), r.order_id, r.contract_id, r.customer_id, r.order_number,
       r.contract_number, 'GOODS_RETURNED', r.return_id, 'VND',
       (SELECT sum(i.paid_amount) FROM payment.installment i WHERE i.order_id = r.order_id), r.refund, 0, r.refund, 0,
       0, r.refund, 0, pg_temp.staff('demo.ketoan'), pg_temp.at(35, '14:00'), 0, pg_temp.at(35, '14:00'),
       pg_temp.staff('demo.ketoan')::text
  FROM w_ret r;

UPDATE ordering.return_request q
   SET status = 'RESOLVED', settled_by = pg_temp.staff('demo.ketoan'), settled_at = pg_temp.at(35, '14:00'),
       refunded_amount = r.refund, offset_amount = 0, vat_adjustment_required = TRUE,
       closed_at = pg_temp.at(35, '14:00'), version = 4, last_modified_at = pg_temp.at(35, '14:00'),
       last_modified_by = pg_temp.staff('demo.ketoan')::text
  FROM w_ret r WHERE q.id = r.return_id;

UPDATE inventory.stock_package p SET return_ownership = 'COMPANY' FROM w_ret r WHERE p.id = r.package_id;

INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT pg_temp.wid('return-history:' || r.okey || ':4'), r.return_id, 'SETTLEMENT_PENDING', 'RESOLVED',
       'RESOLVED_REFUND ' || r.refund::bigint || ' VND', pg_temp.staff('demo.ketoan'), pg_temp.at(35, '14:00'),
       pg_temp.at(35, '14:00'), pg_temp.staff('demo.ketoan')::text
  FROM w_ret r;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 7. Day 35 14:01: the decrease adjustment of shipment 1's invoice, drawn up as a draft (ReturnResolvedListener) -
--    one carton less, at the original's price and rate. Nobody issued it: the information line of DĐ-1.
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.document_sequence (document_type, sequence_date, last_value) VALUES ('EINV', pg_temp.today() - 35, 0)
ON CONFLICT (document_type, sequence_date) DO NOTHING;

CREATE TEMP TABLE w_adj ON COMMIT DROP AS
SELECT r.okey, r.return_id, r.rma, pg_temp.wid('einvoice:' || r.okey || ':return') AS adj_id, o.*
  FROM w_ret r JOIN invoicing.einvoice o ON o.shipment_id = r.shipment_id AND o.type = 'ORIGINAL' AND o.status = 'ISSUED';

UPDATE platform.document_sequence SET last_value = last_value + (SELECT count(*) FROM w_adj)
 WHERE document_type = 'EINV' AND sequence_date = pg_temp.today() - 35;

INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, contract_number, shipment_id,
                                shipment_code, delivery_batch_id, customer_id, original_einvoice_id, issue_date,
                                issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, buyer_tax_code,
                                buyer_address, buyer_email, currency, payment_method, subtotal, tax_total, grand_total,
                                amount_in_words, adjustment_reason, correction_basis, return_request_id, version,
                                created_at, created_by)
SELECT a.adj_id,
       'EINV-' || pg_temp.ymd(35) || '-' || lpad(((SELECT last_value FROM platform.document_sequence
                                                    WHERE document_type = 'EINV' AND sequence_date = pg_temp.today() - 35)
                                                  - (SELECT count(*) FROM w_adj) + row_number() OVER (ORDER BY a.okey))::text, 4, '0'),
       'ADJUSTMENT', 'DRAFT', a.order_id, a.order_number, a.contract_number, a.shipment_id, a.shipment_code,
       a.delivery_batch_id, a.customer_id, a.id, pg_temp.today() - 35,
       ((pg_temp.today() - 34)::timestamp) AT TIME ZONE 'Asia/Ho_Chi_Minh', a.seller_name, a.seller_tax_code,
       a.seller_address, a.buyer_name, a.buyer_tax_code, a.buyer_address, a.buyer_email, 'VND', 'BANK_TRANSFER',
       -l.unit_price, -round(l.unit_price * l.tax_rate / 100, 0), -l.unit_price - round(l.unit_price * l.tax_rate / 100, 0),
       -- VietnameseAmountInWords.of(-1 296 000): one carton, 1,200,000 + 8% VAT.
       'Âm một triệu hai trăm chín mươi sáu nghìn đồng',
       'Hàng bán bị trả lại (phiếu trả hàng ' || a.rma || ')', 'RETURN', a.return_id, 0,
       pg_temp.at(35, '14:01'), 'system'
  FROM w_adj a JOIN invoicing.einvoice_line l ON l.einvoice_id = a.id AND l.kind = 'GOODS';

INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name, unit,
                                     quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
SELECT pg_temp.wid('einvoice-line:' || a.okey || ':return:1'), a.adj_id, 1, 'GOODS', NULL, l.order_line_id, l.sku,
       l.item_name, l.unit, -1, l.units_per_carton, l.unit_price, l.tax_rate, -l.unit_price,
       -round(l.unit_price * l.tax_rate / 100, 0)
  FROM w_adj a JOIN invoicing.einvoice_line l ON l.einvoice_id = a.id AND l.kind = 'GOODS';

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 8. Day 31 10:00: the refund recorded by the accountant; the order follows, PARTIALLY_REFUNDED
-- ---------------------------------------------------------------------------------------------
INSERT INTO payment.refund_record (id, order_id, customer_id, order_number, amount, currency, refunded_on, reference,
                                   note, status, recorded_by, recorded_at, version, created_at, created_by)
SELECT pg_temp.wid('refund:' || r.okey), r.order_id, r.customer_id, r.order_number, r.refund, 'VND',
       pg_temp.today() - 31, 'FT' || pg_temp.ymd(31) || 'HT' || (9600 + r.n), 'Hoàn tiền ' || r.rma, 'RECORDED',
       pg_temp.staff('demo.ketoan'), pg_temp.at(31, '10:00'), 0, pg_temp.at(31, '10:00'),
       pg_temp.staff('demo.ketoan')::text
  FROM w_ret r;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
SELECT pg_temp.wid('order-history:' || r.okey || ':refunded'), r.order_id, 'RETURNED', 'PARTIALLY_REFUNDED', 'REFUNDED',
       pg_temp.staff('demo.ketoan'), pg_temp.at(31, '10:00') + INTERVAL '1 second',
       pg_temp.at(31, '10:00') + INTERVAL '1 second', 'system'
  FROM w_ret r;

UPDATE ordering.customer_order co
   SET status = 'PARTIALLY_REFUNDED', refunded_at = pg_temp.at(31, '10:00') + INTERVAL '1 second',
       status_before_return = NULL, version = co.version + 1,
       last_modified_at = pg_temp.at(31, '10:00') + INTERVAL '1 second', last_modified_by = 'system'
  FROM w_ret r WHERE co.id = r.order_id;

DROP FUNCTION pg_temp.wid(TEXT);
DROP FUNCTION pg_temp.today();
DROP FUNCTION pg_temp.at(INTEGER, TEXT);
DROP FUNCTION pg_temp.ymd(INTEGER);
DROP FUNCTION pg_temp.staff(TEXT);
