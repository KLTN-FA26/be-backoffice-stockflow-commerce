-- =============================================================================
-- Adversarial checks + happy paths: return receipts into quarantine by LPN (V20261112000300, team plan 4D-2;
-- kltn-docs 03 §4.2 steps 9-13, §5, BR-03, BR-14; 17 BR-18; 09 BR-06). Needs the demo seed (HCM-QC01 is a QUARANTINE
-- area, HCM-RCV01 a RECEIVING one). Rolled back at the end.
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
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- A RETURN receipt for the given order / shipment / return request / area, as a statement.
CREATE FUNCTION pg_temp.receipt(id_key TEXT, order_key TEXT, shipment_key TEXT, request_key TEXT, area TEXT,
                                warehouse UUID DEFAULT md5('demo:wh:HCM')::uuid) RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                                receipt_type, customer_order_id, return_shipment_id, return_request_id,
                                                quarantine_location_id)
        VALUES (md5(%L)::uuid, %L, %L::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid, 'RETURN', %s, %s, %s,
                (SELECT id FROM warehouse.storage_location WHERE location_code = %L))$f$,
        id_key, upper(replace(id_key, ':', '-')), warehouse,
        CASE WHEN order_key IS NULL THEN 'NULL' ELSE format('md5(%L)::uuid', order_key) END,
        CASE WHEN shipment_key IS NULL THEN 'NULL' ELSE format('md5(%L)::uuid', shipment_key) END,
        CASE WHEN request_key IS NULL THEN 'NULL' ELSE format('md5(%L)::uuid', request_key) END,
        area)
$$;

-- A carton scanned on a receipt, as a statement.
CREATE FUNCTION pg_temp.scan(receipt_key TEXT, carton_key TEXT, lpn TEXT, lot TEXT, quantity INT) RETURNS TEXT
    LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO procurement.goods_receipt_return_cartons (id, receipt_id, stock_package_id, lpn, sku, lot_number,
                                                              quantity, scanned_by)
        VALUES (gen_random_uuid(), md5(%L)::uuid, md5(%L)::uuid, %L, 'SOFA-3S-GREY', %L, %s,
                md5('demo:user:editor')::uuid)$f$, receipt_key, carton_key, lpn, lot, quantity)
$$;

-- Fixtures, written with triggers and foreign keys off (what the cartons and orders went through before is not this
-- file's subject): two orders of HCM (O1, O2) with a line of sofas each; O1 picked cartons C1 (shipped) and C3 (put
-- back), O2 picked C2; O1's shipment S1 came back (RETURNED), S2 was DELIVERED; O2's shipment S3 came back; O1 has an
-- approved return request awaiting its goods (RMA1) and one only requested (RMA2). A warehouse of its own (QA52) with
-- a QUARANTINE area of its own.
SET session_replication_role = replica;
INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, version, created_at)
VALUES (md5('qa52:wh')::uuid, 'QA52', 'ACTIVE', 'QA52', 'QA', 'M', 50, 50, 0, NOW());
INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable,
                                        is_putaway_target, status)
VALUES (md5('qa52:wh:qc')::uuid, md5('qa52:wh')::uuid, 'AREA', 'QA52-QC01', 'NORMAL', FALSE, FALSE, 'ACTIVE');
INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle,
                            status)
VALUES (gen_random_uuid(), md5('qa52:wh')::uuid, md5('qa52:wh:qc')::uuid, 'QC01', 'QUARANTINE', 'QC01', 1, 1, 5, 5, 0,
        FALSE, 'ACTIVE');

INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at, warehouse_id)
VALUES (md5('qa52:o1')::uuid, 'SO-QA52-O1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING',
        1000, 'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid),
       (md5('qa52:o2')::uuid, 'SO-QA52-O2', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING',
        1000, 'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid);
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa52:o1:l')::uuid, md5('qa52:o1')::uuid, 'SOFA-3S-GREY', 8, 100, 'VND'),
       (md5('qa52:o2:l')::uuid, md5('qa52:o2')::uuid, 'SOFA-3S-GREY', 4, 100, 'VND');

INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa52:unit')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'QA52_CTN', 4);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status, unopened, closed_at)
VALUES (md5('qa52:c1')::uuid, 'QA52-LPN-1', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa52:unit')::uuid, 4, 4,
        'HCM-A01-1-A', 'CONSUMED', FALSE, NOW()),
       (md5('qa52:c2')::uuid, 'QA52-LPN-2', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa52:unit')::uuid, 4, 4,
        'HCM-A01-1-A', 'CONSUMED', FALSE, NOW()),
       (md5('qa52:c3')::uuid, 'QA52-LPN-3', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa52:unit')::uuid, 4, 4,
        'HCM-A01-1-A', 'CONSUMED', FALSE, NOW());
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                  received_at, stock_package_id)
VALUES (md5('qa52:c1:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'PO-QA52-LOT', 0, 0, 'AVAILABLE', NOW(),
        NOW() - interval '30 days', md5('qa52:c1')::uuid),
       (md5('qa52:c2:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'PO-QA52-LOT', 0, 0, 'AVAILABLE', NOW(),
        NOW() - interval '30 days', md5('qa52:c2')::uuid),
       (md5('qa52:c3:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'PO-QA52-LOT', 0, 0, 'AVAILABLE', NOW(),
        NOW() - interval '30 days', md5('qa52:c3')::uuid);

INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
VALUES (md5('qa52:p1')::uuid, md5('qa52:o1')::uuid, 'SO-QA52-O1', md5('demo:wh:HCM')::uuid, 'PICKED', NOW()),
       (md5('qa52:p2')::uuid, md5('qa52:o2')::uuid, 'SO-QA52-O2', md5('demo:wh:HCM')::uuid, 'PICKED', NOW());
INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, allocated_qty, picked_qty,
                                   put_back_qty, status, lpn, whole_carton, picked_by, picked_at, put_back_at,
                                   put_back_by, put_back_location_code)
VALUES (gen_random_uuid(), md5('qa52:p1')::uuid, md5('qa52:o1:l')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 4, 4, 0,
        'PICKED', 'QA52-LPN-1', TRUE, md5('demo:user:editor')::uuid, NOW(), NULL, NULL, NULL),
       (gen_random_uuid(), md5('qa52:p1')::uuid, md5('qa52:o1:l')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 4, 4, 4,
        'PUT_BACK', 'QA52-LPN-3', TRUE, md5('demo:user:editor')::uuid, NOW(), NOW(), md5('demo:user:editor')::uuid,
        'HCM-A01-1-A'),
       (gen_random_uuid(), md5('qa52:p2')::uuid, md5('qa52:o2:l')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 4, 4, 0,
        'PICKED', 'QA52-LPN-2', TRUE, md5('demo:user:editor')::uuid, NOW(), NULL, NULL, NULL);

INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, ready_at, created_at, handed_over_at,
                                  outcome_at, whole_order_handover)
VALUES (md5('qa52:s1')::uuid, md5('qa52:o1')::uuid, 'RETURNED', 'SO-QA52-O1-S1', md5('demo:wh:HCM')::uuid, NOW(), NOW(),
        NOW(), NOW(), TRUE),
       (md5('qa52:s2')::uuid, md5('qa52:o1')::uuid, 'DELIVERED', 'SO-QA52-O1-S2', md5('demo:wh:HCM')::uuid, NOW(), NOW(),
        NOW(), NOW(), TRUE),
       (md5('qa52:s3')::uuid, md5('qa52:o2')::uuid, 'RETURNED', 'SO-QA52-O2-S1', md5('demo:wh:HCM')::uuid, NOW(), NOW(),
        NOW(), NOW(), TRUE);

INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel,
                                     requested_by, requested_resolution, status, reason_code, requested_at,
                                     return_required, return_deadline, decided_by, decided_at)
VALUES (md5('qa52:rma1')::uuid, 'RMA-QA52-1', md5('qa52:o1')::uuid, 'SO-QA52-O1', 'c0000000-0000-4000-8000-000000000001',
        gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REFUND', 'AWAITING_RETURN',
        'DAMAGED_IN_TRANSIT', NOW(), TRUE, NOW() + interval '14 days', md5('demo:user:approver')::uuid, NOW()),
       (md5('qa52:rma2')::uuid, 'RMA-QA52-2', md5('qa52:o1')::uuid, 'SO-QA52-O1', 'c0000000-0000-4000-8000-000000000001',
        gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REFUND', 'REQUESTED', 'DAMAGED_IN_TRANSIT',
        NOW(), NULL, NULL, NULL, NULL);
SET session_replication_role = DEFAULT;

\echo '--- receipt_type: three kinds, and only a RETURN receipt names an order, a source or a quarantine area (03 §1)'
SELECT pg_temp.expect_fail('RR1 a receipt of an unknown type', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                            receipt_type, supplier_id, blind_reason)
    VALUES (gen_random_uuid(), 'GR-QA52-X1', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid,
            'TRANSFER', (SELECT id FROM procurement.suppliers LIMIT 1), 'QA') $q$);
SELECT pg_temp.expect_fail('RR2 a purchase receipt naming a customer order and a returned shipment', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                            receipt_type, supplier_id, blind_reason, customer_order_id,
                                            return_shipment_id)
    VALUES (gen_random_uuid(), 'GR-QA52-X2', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid,
            'PURCHASE', (SELECT id FROM procurement.suppliers LIMIT 1), 'QA', md5('qa52:o1')::uuid,
            md5('qa52:s1')::uuid) $q$);
SELECT pg_temp.expect_ok('RR3 a blind purchase receipt still needs only its supplier and reason', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                            supplier_id, blind_reason)
    VALUES (md5('qa52:blind')::uuid, 'GR-QA52-B1', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
            md5('demo:user:editor')::uuid, (SELECT id FROM procurement.suppliers LIMIT 1), 'QA') $q$);
SELECT pg_temp.expect_true('RR4 a receipt written without a type is a PURCHASE receipt',
    (SELECT receipt_type FROM procurement.goods_receipts WHERE id = md5('qa52:blind')::uuid) = 'PURCHASE');

\echo '--- a RETURN receipt names its order, its quarantine area and exactly one source (BR-14)'
SELECT pg_temp.expect_fail('RR5 a return receipt against nothing',
    pg_temp.receipt('qa52:r:x5', 'qa52:o1', NULL, NULL, 'HCM-QC01'));
SELECT pg_temp.expect_fail('RR6 a return receipt against a shipment AND a return request',
    pg_temp.receipt('qa52:r:x6', 'qa52:o1', 'qa52:s1', 'qa52:rma1', 'HCM-QC01'));
SELECT pg_temp.expect_fail('RR7 a return receipt naming no order',
    pg_temp.receipt('qa52:r:x7', NULL, 'qa52:s1', NULL, 'HCM-QC01'));
SELECT pg_temp.expect_fail('RR8 a return receipt naming no quarantine area',
    pg_temp.receipt('qa52:r:x8', 'qa52:o1', 'qa52:s1', NULL, 'NO-SUCH-PLACE'));
SELECT pg_temp.expect_fail('RR9 a return receipt naming a supplier and a reason, as a blind receipt does', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                            receipt_type, customer_order_id, return_shipment_id,
                                            quarantine_location_id, supplier_id, blind_reason)
    VALUES (gen_random_uuid(), 'GR-QA52-X9', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid,
            'RETURN', md5('qa52:o1')::uuid, md5('qa52:s1')::uuid,
            (SELECT id FROM warehouse.storage_location WHERE location_code = 'HCM-QC01'),
            (SELECT id FROM procurement.suppliers LIMIT 1), 'QA') $q$);

\echo '--- opened only for goods coming back, of its order, at the warehouse they left, into its quarantine (09 BR-06)'
SELECT pg_temp.expect_fail('RR10 a return receipt for a shipment that was DELIVERED, not returned',
    pg_temp.receipt('qa52:r:x10', 'qa52:o1', 'qa52:s2', NULL, 'HCM-QC01'));
SELECT pg_temp.expect_fail('RR11 a return receipt naming another order than its shipment''s',
    pg_temp.receipt('qa52:r:x11', 'qa52:o2', 'qa52:s1', NULL, 'HCM-QC01'));
SELECT pg_temp.expect_fail('RR12 a return receipt for a return request not yet approved',
    pg_temp.receipt('qa52:r:x12', 'qa52:o1', NULL, 'qa52:rma2', 'HCM-QC01'));
SELECT pg_temp.expect_fail('RR13 a return receipt at another warehouse than the one the goods left',
    pg_temp.receipt('qa52:r:x13', 'qa52:o1', 'qa52:s1', NULL, 'QA52-QC01', md5('qa52:wh')::uuid));
SELECT pg_temp.expect_fail('RR14 a return receipt into a quarantine area of another warehouse',
    pg_temp.receipt('qa52:r:x14', 'qa52:o1', 'qa52:s1', NULL, 'QA52-QC01'));
SELECT pg_temp.expect_fail('RR15 a return receipt into the RECEIVING area (not through RECEIVING, BR-14)',
    pg_temp.receipt('qa52:r:x15', 'qa52:o1', 'qa52:s1', NULL, 'HCM-RCV01'));
SELECT pg_temp.expect_fail('RR16 a return receipt into a bin',
    pg_temp.receipt('qa52:r:x16', 'qa52:o1', 'qa52:s1', NULL, 'HCM-A01-1-A'));
SELECT pg_temp.expect_ok('RR17 a return receipt for the returned shipment, into HCM-QC01',
    pg_temp.receipt('qa52:r:s1', 'qa52:o1', 'qa52:s1', NULL, 'HCM-QC01'));
SELECT pg_temp.expect_ok('RR18 a return receipt for the approved return request',
    pg_temp.receipt('qa52:r:rma1', 'qa52:o1', NULL, 'qa52:rma1', 'HCM-QC01'));
SELECT pg_temp.expect_fail('RR19 a second open return receipt for the same shipment',
    pg_temp.receipt('qa52:r:s1b', 'qa52:o1', 'qa52:s1', NULL, 'HCM-RTV01'));
SELECT pg_temp.expect_fail('RR20 a second open return receipt for the same return request',
    pg_temp.receipt('qa52:r:rma1b', 'qa52:o1', NULL, 'qa52:rma1', 'HCM-QC01'));
SELECT pg_temp.expect_ok('RR21 a cancelled return receipt does not block a new one for its shipment', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                            receipt_type, customer_order_id, return_shipment_id, quarantine_location_id)
    VALUES (md5('qa52:r:s3')::uuid, 'GR-QA52-S3', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
            md5('demo:user:editor')::uuid, 'RETURN', md5('qa52:o2')::uuid, md5('qa52:s3')::uuid,
            (SELECT id FROM warehouse.storage_location WHERE location_code = 'HCM-QC01'));
    UPDATE procurement.goods_receipts SET status = 'CANCELLED' WHERE id = md5('qa52:r:s3')::uuid;
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                            receipt_type, customer_order_id, return_shipment_id, quarantine_location_id)
    VALUES (md5('qa52:r:s3b')::uuid, 'GR-QA52-S3B', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
            md5('demo:user:editor')::uuid, 'RETURN', md5('qa52:o2')::uuid, md5('qa52:s3')::uuid,
            (SELECT id FROM warehouse.storage_location WHERE location_code = 'HCM-QC01')) $q$);
SELECT pg_temp.expect_fail('RR22 a return receipt moved to another shipment', $q$
    UPDATE procurement.goods_receipts SET return_shipment_id = md5('qa52:s3')::uuid, customer_order_id = md5('qa52:o2')::uuid
     WHERE id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_fail('RR23 a return receipt''s quarantine area changed', $q$
    UPDATE procurement.goods_receipts
       SET quarantine_location_id = (SELECT id FROM warehouse.storage_location WHERE location_code = 'HCM-RTV01')
     WHERE id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_fail('RR24 a return receipt turned into a purchase receipt', $q$
    UPDATE procurement.goods_receipts SET receipt_type = 'PURCHASE' WHERE id = md5('qa52:r:s1')::uuid $q$);

\echo '--- only a carton picked for the receipt''s order and not put back, named as shipped (BR-03, BR-14)'
SELECT pg_temp.expect_fail('RC1 a carton picked for another order',
    pg_temp.scan('qa52:r:s1', 'qa52:c2', 'QA52-LPN-2', 'PO-QA52-LOT', 4));
SELECT pg_temp.expect_fail('RC2 a carton picked for the order but put back',
    pg_temp.scan('qa52:r:s1', 'qa52:c3', 'QA52-LPN-3', 'PO-QA52-LOT', 4));
SELECT pg_temp.expect_fail('RC3 the carton under a lot it was not shipped with (a new lot, BR-03)',
    pg_temp.scan('qa52:r:s1', 'qa52:c1', 'QA52-LPN-1', 'RETURN-LOT', 4));
SELECT pg_temp.expect_fail('RC4 the carton with another count than it was counted in at',
    pg_temp.scan('qa52:r:s1', 'qa52:c1', 'QA52-LPN-1', 'PO-QA52-LOT', 3));
SELECT pg_temp.expect_fail('RC5 the carton under another LPN (a new label)',
    pg_temp.scan('qa52:r:s1', 'qa52:c1', 'QA52-LPN-9', 'PO-QA52-LOT', 4));
SELECT pg_temp.expect_fail('RC6 a carton scanned on a purchase receipt',
    pg_temp.scan('qa52:blind', 'qa52:c1', 'QA52-LPN-1', 'PO-QA52-LOT', 4));
SELECT pg_temp.expect_fail('RC7 a carton with a blank condition note', $q$
    INSERT INTO procurement.goods_receipt_return_cartons (id, receipt_id, stock_package_id, lpn, sku, lot_number,
                                                          quantity, condition_note, scanned_by)
    VALUES (gen_random_uuid(), md5('qa52:r:s1')::uuid, md5('qa52:c1')::uuid, 'QA52-LPN-1', 'SOFA-3S-GREY',
            'PO-QA52-LOT', 4, '  ', md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('RC8 the shipped carton, with its LPN, count and original lot',
    pg_temp.scan('qa52:r:s1', 'qa52:c1', 'QA52-LPN-1', 'PO-QA52-LOT', 4));
SELECT pg_temp.expect_fail('RC9 the same carton scanned twice on the receipt',
    pg_temp.scan('qa52:r:s1', 'qa52:c1', 'QA52-LPN-1', 'PO-QA52-LOT', 4));
SELECT pg_temp.expect_ok('RC10 the condition of a scanned carton noted while the receipt is a draft', $q$
    UPDATE procurement.goods_receipt_return_cartons SET condition_note = 'Dented corner'
     WHERE receipt_id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_fail('RC11 the count of a scanned carton changed', $q$
    UPDATE procurement.goods_receipt_return_cartons SET quantity = 3 WHERE receipt_id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_ok('RC12 a carton scanned by mistake removed while the receipt is a draft', $q$
    INSERT INTO procurement.goods_receipt_return_cartons (id, receipt_id, stock_package_id, lpn, sku, lot_number,
                                                          quantity, scanned_by)
    VALUES (md5('qa52:rc12')::uuid, md5('qa52:r:rma1')::uuid, md5('qa52:c1')::uuid, 'QA52-LPN-1', 'SOFA-3S-GREY',
            'PO-QA52-LOT', 4, md5('demo:user:editor')::uuid);
    DELETE FROM procurement.goods_receipt_return_cartons WHERE id = md5('qa52:rc12')::uuid $q$);

\echo '--- the ledger: a GOODS_RECEIPT reference is a RECEIPT of a carton into QUARANTINE, once (BR-08, BR-14)'
SELECT pg_temp.expect_fail('RL1 a GOODS_RECEIPT line that is not a RECEIPT', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, from_status, to_status, reference_type, reference_id, occurred_at,
                                          from_stock_package_id, to_stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'PO-QA52-LOT', 'HCM-A01-1-A', 'HCM-QC01', 4, 'AVAILABLE',
            'QUARANTINE', 'GOODS_RECEIPT', md5('qa52:r:s1')::uuid, NOW(), md5('qa52:c1')::uuid,
            md5('qa52:c1')::uuid) $q$);
SELECT pg_temp.expect_fail('RL2 a return received as AVAILABLE stock (it goes into QUARANTINE, out of ATP)', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', unopened = TRUE, closed_at = NULL,
           location_code = 'HCM-QC01' WHERE id = md5('qa52:c1')::uuid;
    UPDATE inventory.stock_item SET on_hand = 4, location_code = 'HCM-QC01' WHERE id = md5('qa52:c1:row')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, quantity, to_status,
                                          reference_type, reference_id, occurred_at, to_stock_package_id)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'PO-QA52-LOT', 'HCM-QC01', 4, 'AVAILABLE', 'GOODS_RECEIPT',
            md5('qa52:r:s1')::uuid, NOW(), md5('qa52:c1')::uuid) $q$);
SELECT pg_temp.expect_fail('RL3 a return received loose, naming no carton', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, quantity, to_status,
                                          reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'PO-QA52-LOT', 'HCM-QC01', 4, 'QUARANTINE', 'GOODS_RECEIPT',
            md5('qa52:r:s1')::uuid, NOW()) $q$);

\echo '--- DRAFT -> CLOSED with every carton received into its area; never through putaway (03 §5)'
SELECT pg_temp.expect_fail('RS1 a return receipt sent to putaway', $q$
    UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_at = NOW(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_fail('RS2 a return receipt closed with its carton not received', $q$
    UPDATE procurement.goods_receipts SET status = 'CLOSED', confirmed_at = now(), closed_at = now(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_fail('RS3 a return receipt closed with no carton at all', $q$
    UPDATE procurement.goods_receipts SET status = 'CLOSED', confirmed_at = now(), closed_at = now(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa52:r:rma1')::uuid $q$);
SELECT pg_temp.expect_fail('RS4 a return receipt closed at another moment than it was confirmed', $q$
    UPDATE procurement.goods_receipts SET status = 'CLOSED', confirmed_at = now() - interval '1 hour',
           closed_at = now(), confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_fail('RS5 the carton received into another place than the receipt''s area', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', unopened = TRUE, closed_at = NULL,
           location_code = 'HCM-RTV01' WHERE id = md5('qa52:c1')::uuid;
    UPDATE inventory.stock_item SET on_hand = 4, status = 'QUARANTINE', location_code = 'HCM-RTV01'
     WHERE id = md5('qa52:c1:row')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, quantity, to_status,
                                          reference_type, reference_id, occurred_at, to_stock_package_id)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'PO-QA52-LOT', 'HCM-RTV01', 4, 'QUARANTINE', 'GOODS_RECEIPT',
            md5('qa52:r:s1')::uuid, NOW(), md5('qa52:c1')::uuid);
    UPDATE procurement.goods_receipts SET status = 'CLOSED', confirmed_at = now(), closed_at = now(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_ok('RS6 confirmed: the carton back whole and sealed in HCM-QC01 as QUARANTINE, one RECEIPT line, CLOSED', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', unopened = TRUE, closed_at = NULL,
           location_code = 'HCM-QC01' WHERE id = md5('qa52:c1')::uuid;
    UPDATE inventory.stock_item SET on_hand = 4, status = 'QUARANTINE', location_code = 'HCM-QC01'
     WHERE id = md5('qa52:c1:row')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, quantity, to_status,
                                          reference_type, reference_id, occurred_at, to_stock_package_id)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'PO-QA52-LOT', 'HCM-QC01', 4, 'QUARANTINE', 'GOODS_RECEIPT',
            md5('qa52:r:s1')::uuid, NOW(), md5('qa52:c1')::uuid);
    UPDATE procurement.goods_receipts SET status = 'CLOSED', confirmed_at = now(), closed_at = now(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_true('RS7 the carton keeps its LPN and its original lot, and is not in ATP',
    (SELECT p.lpn = 'QA52-LPN-1' AND i.lot_number = 'PO-QA52-LOT' AND i.status = 'QUARANTINE'
            AND i.location_code = 'HCM-QC01' AND p.status = 'IN_STOCK'
       FROM inventory.stock_package p JOIN inventory.stock_item i ON i.stock_package_id = p.id
      WHERE p.id = md5('qa52:c1')::uuid));
SELECT pg_temp.expect_true('RS8 no putaway task was opened for the return receipt',
    NOT EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE goods_receipt_id = md5('qa52:r:s1')::uuid));
SELECT pg_temp.expect_fail('RS9 a second ledger line for the same receipt and carton', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, quantity, to_status,
                                          reference_type, reference_id, occurred_at, to_stock_package_id)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'PO-QA52-LOT', 'HCM-QC01', 4, 'QUARANTINE', 'GOODS_RECEIPT',
            md5('qa52:r:s1')::uuid, NOW(), md5('qa52:c1')::uuid) $q$);
SELECT pg_temp.expect_fail('RS10 a carton removed from a closed return receipt', $q$
    DELETE FROM procurement.goods_receipt_return_cartons WHERE receipt_id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_fail('RS11 the condition of a carton changed after the receipt closed', $q$
    UPDATE procurement.goods_receipt_return_cartons SET condition_note = 'Fine after all'
     WHERE receipt_id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_fail('RS12 a closed return receipt cancelled', $q$
    UPDATE procurement.goods_receipts SET status = 'CANCELLED' WHERE id = md5('qa52:r:s1')::uuid $q$);
SELECT pg_temp.expect_fail('RS13 another return receipt for the shipment once its goods came back', $q$
    INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by,
                                            receipt_type, customer_order_id, return_shipment_id, quarantine_location_id)
    VALUES (gen_random_uuid(), 'GR-QA52-S1C', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
            md5('demo:user:editor')::uuid, 'RETURN', md5('qa52:o1')::uuid, md5('qa52:s1')::uuid,
            (SELECT id FROM warehouse.storage_location WHERE location_code = 'HCM-QC01')) $q$);
SELECT pg_temp.expect_ok('RS14 a draft return receipt cancelled', $q$
    UPDATE procurement.goods_receipts SET status = 'CANCELLED' WHERE id = md5('qa52:r:rma1')::uuid $q$);

ROLLBACK;
