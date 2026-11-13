-- =============================================================================
-- Adversarial checks + happy paths: returns through return receipts and the QC-era return flow's removal
-- (V20261114000100 team plan 4D-3, V20261114000200 4D-4, V20261114000300 4C-7; kltn-docs 17 §4.10 steps 37-41, BR-18;
-- 03 §4.2; 15 §4.5): a return's goods come back on a CLOSED return receipt against it, only refund units that came back
-- are refunded and they add up to the settlement, a GOODS_RETURNED settlement needs the order's signed contract, no
-- QC / replacement / credit column is left, the QUALITY_CONTROL area type and the repack station's tables are gone.
-- Needs the demo seed. Rolled back at the end.
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

-- The same, and it is the named rule that refuses it: a statement that breaks something else proves nothing.
CREATE FUNCTION pg_temp.expect_fail_by(label TEXT, rule TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM NOT LIKE '%' || rule || '%' THEN
            RAISE EXCEPTION 'FAIL  %: refused, but not by %: %', label, rule, SQLERRM;
        END IF;
        RAISE NOTICE 'ok    %  [%]', label, rule;
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

-- ---------------------------------------------------------------------------------------------
-- Fixtures: an order of HCM with a line of 10 sofas; a return of 6 of them, approved for 4 and awaiting its goods, by
-- a Sales who opened it (the editor) and another who approved it (the approver). A CLOSED return receipt against it,
-- written with triggers off (what the receipt went through is QA 52's subject).
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at, warehouse_id)
VALUES (md5('qa53:o1')::uuid, 'SO-QA53-O1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING',
        1000, 'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid);
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa53:o1:l')::uuid, md5('qa53:o1')::uuid, 'SOFA-3S-GREY', 10, 100, 'VND');
INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                                     requested_resolution, status, reason_code, requested_at)
VALUES (md5('qa53:r1')::uuid, 'RMA-QA53-1', md5('qa53:o1')::uuid, 'SO-QA53-O1', 'c0000000-0000-4000-8000-000000000001',
        gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REFUND', 'REQUESTED', 'DAMAGED_IN_TRANSIT', NOW());
INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity)
VALUES (md5('qa53:r1:l')::uuid, md5('qa53:r1')::uuid, md5('qa53:o1:l')::uuid, 'SOFA-3S-GREY', FALSE, 100, 6);
UPDATE ordering.return_request_line SET approved_quantity = 4 WHERE id = md5('qa53:r1:l')::uuid;
UPDATE ordering.return_request SET status = 'AWAITING_RETURN', decided_by = md5('demo:user:approver')::uuid, decided_at = NOW(),
       return_required = TRUE, return_deadline = NOW() + INTERVAL '14 days' WHERE id = md5('qa53:r1')::uuid;

SET LOCAL session_replication_role = replica;
INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by, receipt_type,
                                        customer_order_id, return_request_id, quarantine_location_id)
VALUES (md5('qa53:g')::uuid, 'GR-QA53-C', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid, 'RETURN',
        md5('qa53:o1')::uuid, md5('qa53:r1')::uuid, (SELECT id FROM warehouse.storage_location WHERE location_code = 'HCM-QC01'));
SET LOCAL session_replication_role = origin;

\echo '--- goods back: RECEIVED needs a CLOSED return receipt against this return and who, when and where (BR-18, 03 §4.2)'
SELECT pg_temp.expect_fail_by('RT1 RECEIVED with no return receipt behind it: the QC-era parcel count is gone',
    'ck_return_request_goods_back', $q$
    UPDATE ordering.return_request_line SET received_quantity = 4 WHERE id = md5('qa53:r1:l')::uuid;
    UPDATE ordering.return_request SET status = 'RECEIVED', received_by = md5('demo:user:editor')::uuid, received_at = NOW(),
           receiving_location_code = 'HCM-QC01' WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_fail_by('RT2 a receipt that is still a draft', 'its goods come back on a confirmed return receipt', $q$
    UPDATE ordering.return_request SET goods_receipt_id = md5('qa53:g')::uuid, received_by = md5('demo:user:editor')::uuid,
           received_at = NOW(), receiving_location_code = 'HCM-QC01' WHERE id = md5('qa53:r1')::uuid $q$);
SET LOCAL session_replication_role = replica;
UPDATE procurement.goods_receipts SET status = 'CLOSED', confirmed_at = NOW(), confirmed_by = md5('demo:user:editor')::uuid,
       closed_at = NOW() WHERE id = md5('qa53:g')::uuid;
SET LOCAL session_replication_role = origin;
-- A legacy purchase receipt: the supplier and blind columns went with purchasing (V20261113000200, 4A-9), and no new
-- one is started by the application, so it is written as old data is, triggers off.
SET LOCAL session_replication_role = replica;
INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by, receipt_type)
VALUES (md5('qa53:g:purchase')::uuid, 'GR-QA53-P', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid,
        'PURCHASE');
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_fail_by('RT3 a receipt that is not a return receipt (a purchase one)', 'its goods come back on a confirmed return receipt', $q$
    UPDATE ordering.return_request SET goods_receipt_id = md5('qa53:g:purchase')::uuid,
           received_by = md5('demo:user:editor')::uuid, received_at = NOW(), receiving_location_code = 'HCM-QC01'
     WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_fail_by('RT4 a receipt named without who took the goods, when and where', 'ck_return_request_goods_back', $q$
    UPDATE ordering.return_request SET goods_receipt_id = md5('qa53:g')::uuid WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_ok('RT5 RECEIVED on the closed receipt, 4 units counted', $q$
    UPDATE ordering.return_request_line SET received_quantity = 4 WHERE id = md5('qa53:r1:l')::uuid;
    UPDATE ordering.return_request SET status = 'RECEIVED', goods_receipt_id = md5('qa53:g')::uuid,
           received_by = md5('demo:user:editor')::uuid, received_at = NOW(), receiving_location_code = 'HCM-QC01'
     WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_fail_by('RT6 the receipt that brought the goods is changed', 'the receipt that brought its goods does not change', $q$
    UPDATE ordering.return_request SET goods_receipt_id = gen_random_uuid() WHERE id = md5('qa53:r1')::uuid $q$);

\echo '--- the refund: units that came back, adding up to the settlement (17 §4.10 step 40)'
SELECT pg_temp.expect_fail_by('RF1 more units refunded than came back', 'ck_return_request_line_refund', $q$
    UPDATE ordering.return_request_line SET refund_quantity = 5, refund_amount = 500 WHERE id = md5('qa53:r1:l')::uuid $q$);
SELECT pg_temp.expect_fail_by('RF2 more units refunded than approved (3 came back of 4... refund 5 of the 4 approved)',
    'ck_return_request_line_refund', $q$
    UPDATE ordering.return_request_line SET received_quantity = 6 WHERE id = md5('qa53:r1:l')::uuid;
    UPDATE ordering.return_request_line SET refund_quantity = 5, refund_amount = 500 WHERE id = md5('qa53:r1:l')::uuid $q$);
SELECT pg_temp.expect_fail_by('RF3 an amount refunded for no unit', 'ck_return_request_line_refund', $q$
    UPDATE ordering.return_request_line SET refund_amount = 100 WHERE id = md5('qa53:r1:l')::uuid $q$);
SELECT pg_temp.expect_fail_by('RF4 a proposal whose lines do not add up to its amount (at commit)', 'its lines refund', $q$
    UPDATE ordering.return_request_line SET refund_quantity = 3, refund_amount = 330 WHERE id = md5('qa53:r1:l')::uuid;
    UPDATE ordering.return_request SET status = 'SETTLEMENT_PENDING', money_mode = 'REFUND', settlement_amount = 400,
           proposed_by = md5('demo:user:editor')::uuid, proposed_at = NOW() WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_ok('RF5 3 of the 4 units refunded at their price with VAT: proposed', $q$
    UPDATE ordering.return_request_line SET refund_quantity = 3, refund_amount = 330 WHERE id = md5('qa53:r1:l')::uuid;
    UPDATE ordering.return_request SET status = 'SETTLEMENT_PENDING', money_mode = 'REFUND', settlement_amount = 330,
           proposed_by = md5('demo:user:editor')::uuid, proposed_at = NOW() WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_fail('RF6 the proposer approving their own refund (four eyes)', $q$
    UPDATE ordering.return_request SET status = 'RESOLVED', settled_by = md5('demo:user:editor')::uuid, settled_at = NOW(),
           refunded_amount = 330, vat_adjustment_required = TRUE, closed_at = NOW() WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_fail('RF7 a refund settled without flagging the adjustment invoice', $q$
    UPDATE ordering.return_request SET status = 'RESOLVED', settled_by = md5('demo:user:approver')::uuid, settled_at = NOW(),
           refunded_amount = 330, closed_at = NOW() WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_fail('RF8 set off and refunded not adding up to what was settled', $q$
    UPDATE ordering.return_request SET status = 'RESOLVED', settled_by = md5('demo:user:approver')::uuid, settled_at = NOW(),
           refunded_amount = 100, offset_amount = 100, vat_adjustment_required = TRUE, closed_at = NOW()
     WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_ok('RF9 settled by another person: part set off, part to refund, the adjustment invoice flagged', $q$
    UPDATE ordering.return_request SET status = 'RESOLVED', settled_by = md5('demo:user:approver')::uuid, settled_at = NOW(),
           refunded_amount = 130, offset_amount = 200, vat_adjustment_required = TRUE, closed_at = NOW()
     WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_fail('RF10 a resolved return refunding another amount afterwards', $q$
    UPDATE ordering.return_request_line SET refund_quantity = 2, refund_amount = 220 WHERE id = md5('qa53:r1:l')::uuid $q$);
SELECT pg_temp.expect_fail_by('RF11 a refund of a return that is no settlement of this order (no signed contract behind it)',
    'is not its signed one', $q$
    INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
                                          source_id, currency, collected_amount, closed_amount, reduced_amount,
                                          surplus_amount, settled_by, settled_at)
    VALUES (gen_random_uuid(), md5('qa53:o1')::uuid, gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'SO-QA53-O1',
            'CT-QA53', 'GOODS_RETURNED', md5('qa53:r1')::uuid, 'VND', 0, 330, 330, 0, md5('demo:user:approver')::uuid, NOW()) $q$);

\echo '--- what the QC era had is gone (4D-4)'
SELECT pg_temp.expect_true('QE1 INSPECTED is no status a return may have',
    (SELECT pg_get_constraintdef(oid) NOT LIKE '%INSPECTED%' FROM pg_constraint WHERE conname = 'ck_return_request_status'
        AND conrelid = 'ordering.return_request'::regclass));
SELECT pg_temp.expect_fail_by('QE2 a return asking for a replacement', 'ck_return_request_resolution', $q$
    INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                                         requested_resolution, status, reason_code, requested_at)
    VALUES (gen_random_uuid(), 'RMA-QA53-R', md5('qa53:o1')::uuid, 'SO-QA53-O1', 'c0000000-0000-4000-8000-000000000001',
            gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REPLACEMENT', 'REQUESTED', 'PRINT_DEFECT', NOW()) $q$);
SELECT pg_temp.expect_fail_by('QE3 a credit note as the way the money goes', 'ck_return_request_money_mode', $q$
    UPDATE ordering.return_request SET money_mode = 'CREDIT_NOTE' WHERE id = md5('qa53:r1')::uuid $q$);
SELECT pg_temp.expect_fail('QE4 a photo of the goods as they came back kept on the return (they are the receipt''s)', $q$
    INSERT INTO ordering.return_request_evidence (id, return_request_id, stage, storage_key, original_name, content_type,
                                                  size_bytes, uploaded_by, uploaded_at)
    VALUES (gen_random_uuid(), md5('qa53:r1')::uuid, 'RECEIPT', 'return-evidence/qa53.jpg', 'a.jpg', 'image/jpeg', 10,
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail_by('QE4b a return line that received fewer than nothing (the rule that went with received_sku, restated)',
    'ck_return_request_line_received', $q$
    INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                                         requested_resolution, status, reason_code, requested_at)
    VALUES (md5('qa53:r2')::uuid, 'RMA-QA53-2', md5('qa53:o1')::uuid, 'SO-QA53-O1', 'c0000000-0000-4000-8000-000000000001',
            gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REFUND', 'REQUESTED', 'PRINT_DEFECT', NOW());
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity,
                                              received_quantity)
    VALUES (md5('qa53:r2:l')::uuid, md5('qa53:r2')::uuid, md5('qa53:o1:l')::uuid, 'SOFA-3S-GREY', FALSE, 100, 2, -1) $q$);
SELECT pg_temp.expect_ok('QE4c ... and nothing received is fine', $q$
    INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                                         requested_resolution, status, reason_code, requested_at)
    VALUES (md5('qa53:r3')::uuid, 'RMA-QA53-3', md5('qa53:o1')::uuid, 'SO-QA53-O1', 'c0000000-0000-4000-8000-000000000001',
            gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REFUND', 'REQUESTED', 'PRINT_DEFECT', NOW());
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity,
                                              received_quantity)
    VALUES (md5('qa53:r3:l')::uuid, md5('qa53:r3')::uuid, md5('qa53:o1:l')::uuid, 'SOFA-3S-GREY', FALSE, 100, 2, 0) $q$);
SELECT pg_temp.expect_true('QE5 no QC, parcel, credit, replacement or withdrawal column is left on a return',
    (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ordering' AND table_name = 'return_request'
        AND column_name IN ('inspected_by', 'inspected_at', 'inspection_note', 'receipt_note', 'parcel_count',
                            'credited_amount', 'credit_transfer_id', 'replacement_order_id', 'withdrawal_status',
                            'withdrawal_reason', 'withdrawal_amount', 'withdrawal_offset_amount',
                            'withdrawal_credited_amount')) = 0);
SELECT pg_temp.expect_true('QE6 nor on a return line',
    (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ordering' AND table_name = 'return_request_line'
        AND column_name IN ('received_sku', 'lot_number', 'accepted_quantity', 'rejected_quantity', 'item_condition',
                            'inspection_note', 'restock_quantity', 'scrap_quantity', 'customer_return_quantity',
                            'restock_location_code', 'received_plain', 'disposal_consent_note', 'scrap_adjustment_id',
                            'customer_return_adjustment_id', 'replacement_quantity', 'credit_quantity', 'credit_amount',
                            'replacement_order_line_id')) = 0);
SELECT pg_temp.expect_true('QE7 an order no longer replaces a return',
    (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ordering' AND table_name = 'customer_order'
        AND column_name = 'replaces_return_request_id') = 0);
SELECT pg_temp.expect_true('QE8 ck_order_confirmed_basis survived the column it once named, without it',
    (SELECT pg_get_constraintdef(oid) NOT LIKE '%replaces_return%' FROM pg_constraint WHERE conname = 'ck_order_confirmed_basis'
        AND conrelid = 'ordering.customer_order'::regclass));
SELECT pg_temp.expect_fail_by('QE9 a CONFIRMED order with no basis at all still refused', 'ck_order_confirmed_basis', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                         placed_at, created_at)
    VALUES (gen_random_uuid(), 'SO-QA53-C', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'CONFIRMED', 1,
            'VND', NOW(), NOW()) $q$);
SELECT pg_temp.expect_true('QE10 the QC-era permissions are gone from the catalogue and every role',
    (SELECT count(*) FROM identity.permission WHERE resource IN ('sales-rma-inspections', 'sales-rma-receipts')) = 0);

\echo '--- the QUALITY_CONTROL area type is gone, QUARANTINE stays (4D-4)'
SELECT pg_temp.expect_fail_by('AR1 a QUALITY_CONTROL area', 'ck_area_type', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, is_pickable,
                                            is_putaway_target, status)
    VALUES (md5('qa53:loc:qc')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-QA53A', FALSE, FALSE, 'ACTIVE');
    INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle,
                                status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, md5('qa53:loc:qc')::uuid, 'QA53A', 'QUALITY_CONTROL', 'QC', 60, 2, 3, 3,
            0, FALSE, 'ACTIVE') $q$);
SELECT pg_temp.expect_ok('AR2 a QUARANTINE area', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, is_pickable,
                                            is_putaway_target, status)
    VALUES (md5('qa53:loc:q')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-QA53B', FALSE, FALSE, 'ACTIVE');
    INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle,
                                status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, md5('qa53:loc:q')::uuid, 'QA53B', 'QUARANTINE', 'Q', 60, 8, 3, 3,
            0, FALSE, 'ACTIVE') $q$);
SELECT pg_temp.expect_true('AR3 the demo''s old QC area became an inactive quarantine area with its code kept',
    (SELECT a.type = 'QUARANTINE' AND a.status = 'INACTIVE' FROM warehouse.area a
       JOIN warehouse.storage_location s ON s.id = a.location_id WHERE s.location_code = 'HCM-QCA01'));

\echo '--- the repack station is gone (4C-7)'
SELECT pg_temp.expect_true('RP1 no parcel contents, scans or kit rules are left', NOT EXISTS (
    SELECT 1 FROM information_schema.tables WHERE table_schema = 'fulfillment' AND table_name IN ('package_line', 'package_scan'))
    AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname IN ('tg_package_kit_check', 'tg_package_kit_ships_whole',
                                                               'tg_package_kit_fixed'))
    AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname IN ('check_package_line_quantity', 'check_kit_part_line',
                                                             'check_package_kit', 'check_kit_ships_whole')));
SELECT pg_temp.expect_true('RP2 a package has no type, kit, tracking number or outdated label any more', (
    SELECT count(*) FROM information_schema.columns WHERE table_schema = 'fulfillment' AND table_name = 'package'
       AND column_name IN ('package_type', 'fragile', 'fragile_overridden', 'kit_group_id', 'kit_part_no', 'kit_part_count',
                           'kit_order_line_id', 'kit_override_reason', 'label_outdated_at', 'tracking_number')) = 0);
SELECT pg_temp.expect_true('RP3 an order has no packing-started time', (
    SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ordering' AND table_name = 'customer_order'
       AND column_name = 'packing_started_at') = 0);
SELECT pg_temp.expect_fail_by('RP4 a new parcel that is no shipping carton', 'parcels are no longer packed', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
    VALUES (md5('qa53:pick')::uuid, md5('qa53:o1')::uuid, 'SO-QA53-O1', md5('demo:wh:HCM')::uuid, 'PICKED', NOW());
    INSERT INTO fulfillment.pack (id, pick_id, status, created_at) VALUES (md5('qa53:pack')::uuid, md5('qa53:pick')::uuid, 'PENDING', NOW());
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status)
    VALUES (gen_random_uuid(), md5('qa53:pack')::uuid, 1, 'QA53-P1', 'OPEN') $q$);

ROLLBACK;
