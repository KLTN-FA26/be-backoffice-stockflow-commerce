-- =============================================================================
-- Adversarial checks + happy paths: receiving against a production order (V20261108002800, team plan 4A-5;
-- kltn-docs 03 §4.1, BR-01..BR-15 and edge cases; 17 §4.3 step 11, §5.3). Needs the demo seed (demo.editor draws up
-- and receives, demo.approver approves; the HCM warehouse and its RECEIVING area HCM-RCV01). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints and constraint triggers fire here, still inside the sub-transaction.
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'ok    %  [%]', label, left(SQLERRM, 110);
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    RAISE NOTICE 'ok    %', label;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- Fixtures: the first customer's own SKU in cartons of 50 (and a box of 25), received complete with its logistics; an
-- order of 20 cartons shipping from HCM whose contract delivers them in one batch, signed; a producer; another
-- warehouse.
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qa22:product')::uuid, 'QAPRR', 'QA ly nhap', 'qa-prr', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa22:blank')::uuid, md5('qa22:product')::uuid, 'QA-PRR-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa22:draft')::uuid, c.id, md5('qa22:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa22:snap')::uuid, md5('qa22:draft')::uuid, repeat('d', 64), 'https://example.com/d.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa22:own')::uuid, md5('qa22:product')::uuid, 'QA-PRR-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-PRR-OWN', 1, 0, NOW(), c.id, md5('qa22:snap')::uuid, md5('qa22:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
UPDATE inventory.inventory_items SET weight_kg = 0.02, length_cm = 9, width_cm = 9, height_cm = 12
 WHERE sku IN ('QA-PRR-OWN', 'QA-PRR-BLANK');
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa22:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-PRR-OWN';
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa22:box')::uuid, i.id, 'BOX', 25, FALSE, FALSE, TRUE, 0, NOW(), FALSE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-PRR-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa22:order')::uuid, 'SO-QA-PRR', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa22:line')::uuid, md5('qa22:order')::uuid, 'QA-PRR-OWN', 1000, 2000, 'VND', md5('qa22:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds)
VALUES (md5('qa22:contract')::uuid, 'HD-QA-PRR-1', md5('qa22:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa22:b1')::uuid, md5('qa22:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (gen_random_uuid(), md5('qa22:b1')::uuid, md5('qa22:line')::uuid, 20);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
VALUES (md5('qa22:i1')::uuid, md5('qa22:contract')::uuid, 1, 'Before delivery', 'BEFORE_DELIVERY', 100, 2200000,
        NULL, NULL);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
-- Sent and signed straight (the contract's own triggers are another script's), confirmed, shipping from HCM.
SET LOCAL session_replication_role = replica;
UPDATE ordering.sales_contract SET status = 'SIGNED', sent_at = NOW() - INTERVAL '2 days',
       sent_by = md5('demo:user:editor')::uuid, sent_document_key = 'documents/hd-qa-prr-1.pdf', signed_at = NOW(),
       signed_by = md5('demo:user:editor')::uuid, signed_on = CURRENT_DATE, signed_file_key = 'documents/s.pdf',
       signed_file_name = 's.pdf'
 WHERE id = md5('qa22:contract')::uuid;
UPDATE ordering.customer_order o SET status = 'CONFIRMED', contract_signed_at = NOW(), shipping_warehouse_id = w.id,
       shipping_warehouse_chosen_at = NOW(), shipping_warehouse_chosen_by = md5('demo:user:editor')::uuid
  FROM warehouse.warehouse w WHERE w.prefix = 'HCM' AND o.id = md5('qa22:order')::uuid;
SET LOCAL session_replication_role = origin;
INSERT INTO ordering.producer (id, code, name, channel, email)
VALUES (md5('qa22:producer')::uuid, 'QA-XUONG-2', 'QA xuong in 2', 'EMAIL', 'don@qa-xuong-2.vn');
INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, version, created_at)
VALUES (md5('qa22:wh')::uuid, 'QA other warehouse', 'ACTIVE', 'QAW', 'Somewhere', 'M', 50, 50, 0, NOW());
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- A production order of the batch at HCM, drawn up, submitted and approved (so SENT), with a line of {cartons}.
CREATE FUNCTION pg_temp.sent(po_id UUID, po_code TEXT, sample BOOLEAN, cartons INTEGER) RETURNS void
    LANGUAGE sql AS $$
    INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                           receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                           sample_required_by_default, sample_waiver_reason, drawn_up_by)
    SELECT po_id, po_code, o.id, md5('qa22:b1')::uuid, o.customer_id, md5('qa22:producer')::uuid,
           o.shipping_warehouse_id, CURRENT_DATE + 30, 'DRAFT', sample, sample,  NULL, md5('demo:user:editor')::uuid
      FROM ordering.customer_order o WHERE o.id = md5('qa22:order')::uuid;
    INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                                design_snapshot_id, print_file_checksum)
    VALUES (md5(po_code || ':line')::uuid, po_id, md5('qa22:line')::uuid, 'QA-PRR-OWN', cartons, 50,
            md5('qa22:snap')::uuid, repeat('d', 64));
    UPDATE ordering.production_order SET status = 'PENDING_APPROVAL', submitted_at = NOW() WHERE id = po_id;
    UPDATE ordering.production_order SET status = 'SENT', approved_by = md5('demo:user:approver')::uuid,
           approved_at = NOW(), sent_at = NOW() WHERE id = po_id;
$$;
-- A DRAFT receipt against a production order, into {warehouse_prefix}'s warehouse; registered with the production order unless
-- {registered} is false.
CREATE FUNCTION pg_temp.receipt(receipt_id UUID, receipt_no TEXT, po_id UUID, warehouse_prefix TEXT,
                                registered BOOLEAN) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id, warehouse_id,
                                            status, received_at, received_by)
    SELECT receipt_id, receipt_no, 'PRODUCTION', po_id, w.id, 'DRAFT', NOW(), md5('demo:user:editor')::uuid
      FROM warehouse.warehouse w WHERE w.prefix = warehouse_prefix;
    IF registered THEN
        INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status,
                                                       opened_at)
        VALUES (po_id, receipt_id, receipt_no, 'DRAFT', NOW());
    END IF;
END $$;
-- A receipt line of {cartons} cartons of 50 of the production order line {po_code}, at HCM-RCV01, in the lot {lot}.
CREATE FUNCTION pg_temp.line(line_id UUID, receipt_id UUID, po_code TEXT, units NUMERIC, lot TEXT, unit UUID)
    RETURNS void LANGUAGE sql AS $$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id,
                                                 location_id, received_qty, lot_number, uom_conversion_id)
    SELECT line_id, receipt_id, md5(po_code || ':line')::uuid, i.id, s.id, units, lot, unit
      FROM inventory.inventory_items i, warehouse.storage_location s
     WHERE i.sku = 'QA-PRR-OWN' AND s.location_code = 'HCM-RCV01'
$$;

SELECT pg_temp.sent(md5('qa22:po1')::uuid, 'PRD-20261109-2201', FALSE, 10);
SELECT pg_temp.sent(md5('qa22:po2')::uuid, 'PRD-20261109-2202', TRUE, 4);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- one receipt, one production order that can receive goods, at its warehouse (03 BR-01, BR-15)'
SELECT pg_temp.expect_fail('PR1 a receipt against a production order still a draft', $q$
    INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                           receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                           sample_required_by_default, drawn_up_by)
    SELECT md5('qa22:po0')::uuid, 'PRD-20261109-2200', sales_order_id, delivery_batch_id, customer_id, producer_id,
           receiving_warehouse_id, need_by_date, 'DRAFT', FALSE, FALSE, drawn_up_by
      FROM ordering.production_order WHERE id = md5('qa22:po1')::uuid;
    INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                                design_snapshot_id, print_file_checksum)
    VALUES (gen_random_uuid(), md5('qa22:po0')::uuid, md5('qa22:line')::uuid, 'QA-PRR-OWN', 2, 50,
            md5('qa22:snap')::uuid, repeat('d', 64));
    SELECT pg_temp.receipt(md5('qa22:gr0')::uuid, 'GR-QA22-0', md5('qa22:po0')::uuid, 'HCM', TRUE) $q$);
SELECT pg_temp.expect_fail('PR2 a receipt against a SENT production order whose print sample is not approved (BR-15)', $q$
    SELECT pg_temp.receipt(md5('qa22:gr0')::uuid, 'GR-QA22-0', md5('qa22:po2')::uuid, 'HCM', TRUE) $q$);
SELECT pg_temp.expect_fail('PR3 into another warehouse than the production order is received at (BR-01)', $q$
    SELECT pg_temp.receipt(md5('qa22:gr0')::uuid, 'GR-QA22-0', md5('qa22:po1')::uuid, 'QAW', TRUE) $q$);
SELECT pg_temp.expect_fail('PR4 a PURCHASE receipt started (purchasing was removed, V20261113000200)', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, warehouse_id, status, received_at,
                                            received_by)
    SELECT md5('qa22:gr0')::uuid, 'GR-QA22-0', 'PURCHASE', w.id, 'DRAFT', NOW(), md5('demo:user:editor')::uuid
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' $q$);
SELECT pg_temp.expect_fail('PR5 a PRODUCTION receipt against no production order', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, warehouse_id, status, received_at,
                                            received_by)
    SELECT md5('qa22:gr0')::uuid, 'GR-QA22-0', 'PRODUCTION', w.id, 'DRAFT', NOW(), md5('demo:user:editor')::uuid
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' $q$);
SELECT pg_temp.expect_fail('PR6 a receipt that says nothing of what it is against (receipt_type has no default)', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by)
    SELECT md5('qa22:gr0')::uuid, 'GR-QA22-0', w.id, 'DRAFT', NOW(), md5('demo:user:editor')::uuid
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' $q$);
SELECT pg_temp.expect_fail('PR7 a production receipt its production order does not know of', $q$
    SELECT pg_temp.receipt(md5('qa22:gr0')::uuid, 'GR-QA22-0', md5('qa22:po1')::uuid, 'HCM', FALSE) $q$);
SELECT pg_temp.expect_ok('PR8 a draft against the SENT production order with no sample to approve, registered', $q$
    SELECT pg_temp.receipt(md5('qa22:gr1')::uuid, 'GR-QA22-1', md5('qa22:po1')::uuid, 'HCM', TRUE) $q$);
SELECT pg_temp.expect_fail('PR9 turned into a purchase receipt', $q$
    UPDATE procurement.goods_receipts SET receipt_type = 'PURCHASE', production_order_id = NULL
     WHERE id = md5('qa22:gr1')::uuid $q$);
SELECT pg_temp.expect_fail('PR10 sent to the warehouse manager for approval (no tolerance, BR-02: PENDING_APPROVAL is gone)', $q$
    UPDATE procurement.goods_receipts SET status = 'PENDING_APPROVAL' WHERE id = md5('qa22:gr1')::uuid $q$);

\echo '--- its lines: the production order''s, in its lot, in whole cartons (BR-03, BR-06, BR-11..BR-13)'
SELECT pg_temp.expect_fail('PR11 a line of another production order', $q$
    SELECT pg_temp.line(md5('qa22:l0')::uuid, md5('qa22:gr1')::uuid, 'PRD-20261109-2202', 100, 'PRD-20261109-2202',
                        md5('qa22:carton')::uuid) $q$);
SELECT pg_temp.expect_fail('PR12 a line in a lot of its own (BR-03)', $q$
    SELECT pg_temp.line(md5('qa22:l0')::uuid, md5('qa22:gr1')::uuid, 'PRD-20261109-2201', 100, 'LOT-A',
                        md5('qa22:carton')::uuid) $q$);
SELECT pg_temp.expect_fail('PR13 a line with an expiry date (BR-06)', $q$
    SELECT pg_temp.line(md5('qa22:l0')::uuid, md5('qa22:gr1')::uuid, 'PRD-20261109-2201', 100, 'PRD-20261109-2201',
                        md5('qa22:carton')::uuid);
    UPDATE procurement.goods_receipt_lines SET expiry_date = CURRENT_DATE + 365 WHERE id = md5('qa22:l0')::uuid $q$);
SELECT pg_temp.expect_fail('PR14 120 units: not whole cartons of 50 (BR-12, BR-13)', $q$
    SELECT pg_temp.line(md5('qa22:l0')::uuid, md5('qa22:gr1')::uuid, 'PRD-20261109-2201', 120, 'PRD-20261109-2201',
                        md5('qa22:carton')::uuid) $q$);
SELECT pg_temp.expect_fail('PR15 counted in boxes of 25, not cartons of the line''s 50 (BR-13)', $q$
    SELECT pg_temp.line(md5('qa22:l0')::uuid, md5('qa22:gr1')::uuid, 'PRD-20261109-2201', 100, 'PRD-20261109-2201',
                        md5('qa22:box')::uuid) $q$);
SELECT pg_temp.expect_fail('PR16 a line naming no production order line', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty,
                                                 lot_number, uom_conversion_id)
    SELECT md5('qa22:l0')::uuid, md5('qa22:gr1')::uuid, i.id, s.id, 100, 'PRD-20261109-2201', md5('qa22:carton')::uuid
      FROM inventory.inventory_items i, warehouse.storage_location s
     WHERE i.sku = 'QA-PRR-OWN' AND s.location_code = 'HCM-RCV01' $q$);
SELECT pg_temp.expect_fail('PR17 another SKU than the production order line''s (BR-11)', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id,
                                                 location_id, received_qty, lot_number)
    SELECT md5('qa22:l0')::uuid, md5('qa22:gr1')::uuid, md5('PRD-20261109-2201:line')::uuid, i.id, s.id, 100,
           'PRD-20261109-2201'
      FROM inventory.inventory_items i, warehouse.storage_location s
     WHERE i.sku = 'QA-PRR-BLANK' AND s.location_code = 'HCM-RCV01' $q$);
SELECT pg_temp.expect_ok('PR18 4 cartons of 50 on the draft', $q$
    SELECT pg_temp.line(md5('qa22:l1')::uuid, md5('qa22:gr1')::uuid, 'PRD-20261109-2201', 200, 'PRD-20261109-2201',
                        md5('qa22:carton')::uuid) $q$);
SELECT pg_temp.expect_fail('PR19 the same production order line twice on one receipt', $q$
    SELECT pg_temp.line(md5('qa22:l0')::uuid, md5('qa22:gr1')::uuid, 'PRD-20261109-2201', 50, 'PRD-20261109-2201',
                        md5('qa22:carton')::uuid) $q$);

\echo '--- never more cartons than ordered, on every receipt not cancelled, drafts included (BR-02)'
SELECT pg_temp.expect_ok('PR20 a second draft', $q$
    SELECT pg_temp.receipt(md5('qa22:gr2')::uuid, 'GR-QA22-2', md5('qa22:po1')::uuid, 'HCM', TRUE) $q$);
SELECT pg_temp.expect_fail('PR21 7 more cartons on it while the first draft holds 4 of the 10', $q$
    SELECT pg_temp.line(md5('qa22:l2')::uuid, md5('qa22:gr2')::uuid, 'PRD-20261109-2201', 350, 'PRD-20261109-2201',
                        md5('qa22:carton')::uuid) $q$);
SELECT pg_temp.expect_ok('PR22 6 more cartons: exactly the 10', $q$
    SELECT pg_temp.line(md5('qa22:l2')::uuid, md5('qa22:gr2')::uuid, 'PRD-20261109-2201', 300, 'PRD-20261109-2201',
                        md5('qa22:carton')::uuid) $q$);
SELECT pg_temp.expect_fail('PR23 recounted one carton up', $q$
    UPDATE procurement.goods_receipt_lines SET received_qty = 350 WHERE id = md5('qa22:l2')::uuid $q$);

\echo '--- a draft holds the production order open (03 edge cases)'
SELECT pg_temp.expect_fail('PR24 the production order cancelled while it has draft receipts', $q$
    UPDATE ordering.production_order SET status = 'CANCELLED', cancelled_at = NOW(),
           cancelled_by = md5('demo:user:editor')::uuid, cancellation_reason = 'customer cancelled'
     WHERE id = md5('qa22:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PR25 closed while it has draft receipts', $q$
    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW()
     WHERE id = md5('qa22:po1')::uuid;
    UPDATE ordering.production_order SET status = 'CLOSED', closed_at = NOW(), closed_by = md5('demo:user:editor')::uuid,
           closing_reason = 'stopped' WHERE id = md5('qa22:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PR26 a receipt registered straight as confirmed', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id, warehouse_id,
                                            status, received_at, received_by)
    SELECT md5('qa22:gr0')::uuid, 'GR-QA22-0', 'PRODUCTION', md5('qa22:po1')::uuid, w.id, 'DRAFT', NOW(),
           md5('demo:user:editor')::uuid FROM warehouse.warehouse w WHERE w.prefix = 'HCM';
    INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status,
                                                   opened_at, settled_at)
    VALUES (md5('qa22:po1')::uuid, md5('qa22:gr0')::uuid, 'GR-QA22-0', 'CONFIRMED', NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('PR27 registered as confirmed while the receipt is still a draft', $q$
    UPDATE ordering.production_order_receipt SET status = 'CONFIRMED', settled_at = NOW()
     WHERE goods_receipt_id = md5('qa22:gr2')::uuid $q$);
SELECT pg_temp.expect_fail('PR28 the receipt cancelled, its registration left a draft', $q$
    UPDATE procurement.goods_receipts SET status = 'CANCELLED' WHERE id = md5('qa22:gr2')::uuid $q$);
SELECT pg_temp.expect_fail('PR29 a registration removed', $q$
    DELETE FROM ordering.production_order_receipt WHERE goods_receipt_id = md5('qa22:gr2')::uuid $q$);
SELECT pg_temp.expect_ok('PR30 the second draft cancelled, and its registration with it', $q$
    UPDATE procurement.goods_receipts SET status = 'CANCELLED' WHERE id = md5('qa22:gr2')::uuid;
    UPDATE ordering.production_order_receipt SET status = 'CANCELLED', settled_at = NOW()
     WHERE goods_receipt_id = md5('qa22:gr2')::uuid $q$);
SELECT pg_temp.expect_fail('PR31 a cancelled registration settled again', $q$
    UPDATE ordering.production_order_receipt SET status = 'CONFIRMED' WHERE goods_receipt_id = md5('qa22:gr2')::uuid $q$);
SELECT pg_temp.expect_true('PR32 a cancelled receipt counts no carton: 6 are free again',
    (SELECT SUM(g.received_qty) FROM procurement.goods_receipt_lines g JOIN procurement.goods_receipts r
        ON r.id = g.receipt_id WHERE g.production_order_line_id = md5('PRD-20261109-2201:line')::uuid
       AND r.status <> 'CANCELLED') = 200);

\echo '--- posted: the lines are frozen, the lot is the production order''s (BR-03, BR-05)'
SELECT pg_temp.expect_ok('PR33 the first draft posted, registered confirmed, the production order in production', $q$
    UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_at = NOW(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa22:gr1')::uuid;
    UPDATE ordering.production_order_receipt SET status = 'CONFIRMED', settled_at = NOW()
     WHERE goods_receipt_id = md5('qa22:gr1')::uuid;
    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW()
     WHERE id = md5('qa22:po1')::uuid;
    UPDATE ordering.production_order SET status = 'PARTIALLY_RECEIVED' WHERE id = md5('qa22:po1')::uuid;
    UPDATE ordering.production_order_line SET received_cartons = 4
     WHERE id = md5('PRD-20261109-2201:line')::uuid $q$);
SELECT pg_temp.expect_fail('PR34 a posted line recounted (BR-05)', $q$
    UPDATE procurement.goods_receipt_lines SET received_qty = 150 WHERE id = md5('qa22:l1')::uuid $q$);
SELECT pg_temp.expect_fail('PR35 a production order lot naming a goods receipt', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, production_order_id,
                                     production_order_code, goods_receipt_id, goods_receipt_number)
    VALUES (md5('qa22:lot0')::uuid, 'QA-PRR-OWN', 'PRD-20261109-2201', NOW(), 'PRODUCTION_ORDER',
            md5('qa22:po1')::uuid, 'PRD-20261109-2201', md5('qa22:gr1')::uuid, 'GR-QA22-1') $q$);
SELECT pg_temp.expect_fail('PR36 a production order lot whose number is not the code (BR-03)', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, production_order_id,
                                     production_order_code)
    VALUES (md5('qa22:lot0')::uuid, 'QA-PRR-OWN', 'LOT-A', NOW(), 'PRODUCTION_ORDER', md5('qa22:po1')::uuid,
            'PRD-20261109-2201') $q$);
SELECT pg_temp.expect_fail('PR37 a production order lot under another code than the production order''s', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, production_order_id,
                                     production_order_code)
    VALUES (md5('qa22:lot0')::uuid, 'QA-PRR-OWN', 'PRD-20261109-2202', NOW(), 'PRODUCTION_ORDER',
            md5('qa22:po1')::uuid, 'PRD-20261109-2202') $q$);
SELECT pg_temp.expect_fail('PR38 a production order lot of a SKU it does not make', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, production_order_id,
                                     production_order_code)
    VALUES (md5('qa22:lot0')::uuid, 'QA-PRR-BLANK', 'PRD-20261109-2201', NOW(), 'PRODUCTION_ORDER',
            md5('qa22:po1')::uuid, 'PRD-20261109-2201') $q$);
SELECT pg_temp.expect_fail('PR39 a production order lot with an expiry date (BR-06)', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, expiry_date, received_at, origin_type, production_order_id,
                                     production_order_code)
    VALUES (md5('qa22:lot0')::uuid, 'QA-PRR-OWN', 'PRD-20261109-2201', CURRENT_DATE + 30, NOW(), 'PRODUCTION_ORDER',
            md5('qa22:po1')::uuid, 'PRD-20261109-2201') $q$);
SELECT pg_temp.expect_ok('PR40 the lot of the production order''s SKU', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, production_order_id,
                                     production_order_code)
    VALUES (md5('qa22:lot1')::uuid, 'QA-PRR-OWN', 'PRD-20261109-2201', NOW(), 'PRODUCTION_ORDER',
            md5('qa22:po1')::uuid, 'PRD-20261109-2201') $q$);
SELECT pg_temp.expect_fail('PR41 a second lot of the same production order and SKU (one lot across receipts)', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, production_order_id,
                                     production_order_code)
    VALUES (md5('qa22:lot0')::uuid, 'QA-PRR-OWN', 'PRD-20261109-2201', NOW() + INTERVAL '1 day', 'PRODUCTION_ORDER',
            md5('qa22:po1')::uuid, 'PRD-20261109-2201') $q$);
SELECT pg_temp.expect_fail('PR42 a goods receipt lot naming a production order', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, goods_receipt_id,
                                     goods_receipt_number, production_order_id, production_order_code)
    VALUES (md5('qa22:lot0')::uuid, 'QA-PRR-OWN', 'PRD-20261109-2201', NOW(), 'GOODS_RECEIPT', md5('qa22:gr1')::uuid,
            'GR-QA22-1', md5('qa22:po1')::uuid, 'PRD-20261109-2201') $q$);
SELECT pg_temp.expect_fail('PR43 an untraced lot naming a production order', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, origin_type, production_order_id, production_order_code)
    VALUES (md5('qa22:lot0')::uuid, 'QA-PRR-OWN', 'PRD-20261109-2201', 'UNTRACED', md5('qa22:po1')::uuid,
            'PRD-20261109-2201') $q$);
SELECT pg_temp.expect_ok('PR44 goods receipt lots keep their rule: one per receipt, SKU, lot and expiry', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, goods_receipt_id,
                                     goods_receipt_number)
    VALUES (md5('qa22:lot2')::uuid, 'QA-PRR-BLANK', 'L1', NOW(), 'GOODS_RECEIPT', md5('qa22:gr1')::uuid, 'GR-QA22-1') $q$);
SELECT pg_temp.expect_fail('PR45 the same goods receipt lot twice', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, goods_receipt_id,
                                     goods_receipt_number)
    VALUES (md5('qa22:lot3')::uuid, 'QA-PRR-BLANK', 'L1', NOW(), 'GOODS_RECEIPT', md5('qa22:gr1')::uuid, 'GR-QA22-1') $q$);

\echo '--- settled receipts leave the production order free (BR-10: short is closed by hand)'
SELECT pg_temp.expect_ok('PR46 closed short once no receipt is a draft', $q$
    UPDATE ordering.production_order SET status = 'CLOSED', closed_at = NOW(), closed_by = md5('demo:user:editor')::uuid,
           closing_reason = 'the producer will not deliver the rest' WHERE id = md5('qa22:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PR47 a new receipt against the closed production order (BR-01)', $q$
    SELECT pg_temp.receipt(md5('qa22:gr3')::uuid, 'GR-QA22-3', md5('qa22:po1')::uuid, 'HCM', TRUE) $q$);

ROLLBACK;
