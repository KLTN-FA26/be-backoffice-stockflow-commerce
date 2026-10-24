-- =============================================================================
-- Outbound fulfillment: the order number and warehouse a task carries (SCRUM-466,
-- V20261014000100; kltn-docs 07 §4, BR-SEC-002); pick confirmation, deduction and put-back
-- (SCRUM-267/274/275, V20261014001000; docs 07 step 6, BR-04, BR-05); the packing station's parcels, scans and
-- shipments (G4-5, V20261021000300; docs 08 BR-01/03/04, 09 §5); carriers, loads and the handover (G4-2a,
-- V20261021000400/500; docs 09 §4.1, BR-01/03/04); damage found at packing among picked units (R8,
-- V20261025030000/030100). Needs the demo seed.
-- Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;

CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
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

INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at, warehouse_id)
VALUES (md5('qa:order:f1')::uuid, 'SO-QA-F1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
        'PICKING', 12000000, 'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid);

\echo '--- the task carries its order number and warehouse (fulfillment.pick)'
SELECT pg_temp.expect_fail('F1 a pick with no warehouse', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, status, created_at)
    VALUES (md5('qa:pick:f1')::uuid, md5('qa:order:f1')::uuid, 'SO-QA-F1', 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_fail('F2 a pick for a warehouse that does not exist', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
    VALUES (md5('qa:pick:f1')::uuid, md5('qa:order:f1')::uuid, 'SO-QA-F1', gen_random_uuid(), 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_fail('F3 a pick with no order number', $q$
    INSERT INTO fulfillment.pick (id, order_id, warehouse_id, status, created_at)
    VALUES (md5('qa:pick:f1')::uuid, md5('qa:order:f1')::uuid, md5('demo:wh:HCM')::uuid, 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_ok('F4 a pick with both', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
    VALUES (md5('qa:pick:f1')::uuid, md5('qa:order:f1')::uuid, 'SO-QA-F1', md5('demo:wh:HCM')::uuid, 'PENDING', NOW()) $q$);

\echo '--- pick lines: picked means deducted, one live line per hold, put back in full (fulfillment.pick_line)'
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa:ol:f5')::uuid, md5('qa:order:f1')::uuid, 'SOFA-3S-GREY', 1, 12000000, 'VND');
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at,
                                         expires_at, status, closed_at)
VALUES (md5('qa:res:f5')::uuid, '11111111-1111-4111-8111-111111111111', md5('qa:order:f1')::uuid, gen_random_uuid(),
        gen_random_uuid(), 1, NOW(), NULL, 'CONSUMED', NOW());
INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no, allocated_qty)
VALUES (md5('qa:pl:f5')::uuid, md5('qa:pick:f1')::uuid, md5('qa:ol:f5')::uuid, md5('qa:res:f5')::uuid, 'SOFA-3S-GREY',
        'HCM-A01-2-B', 1, 1);

SELECT pg_temp.expect_fail('F5 a line PICKED against a hold that was never deducted (consumed_at NULL)', $q$
    UPDATE fulfillment.pick_line SET status = 'PICKED', picked_qty = 1, picked_by = md5('demo:user:editor')::uuid,
           picked_at = NOW() WHERE id = md5('qa:pl:f5')::uuid $q$);
SELECT pg_temp.expect_fail('F6 a second live line for the same hold', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, allocated_qty)
    VALUES (gen_random_uuid(), md5('qa:pick:f1')::uuid, md5('qa:ol:f5')::uuid, md5('qa:res:f5')::uuid, 'SOFA-3S-GREY',
            'HCM-A01-2-B', 1) $q$);
SELECT pg_temp.expect_ok('F7 a line picked in full and deducted', $q$
    UPDATE fulfillment.pick_line SET status = 'PICKED', picked_qty = 1, picked_by = md5('demo:user:editor')::uuid,
           picked_at = NOW(), consumed_at = NOW(), scanned_location_code = 'HCM-A01-2-B', scanned_code = '8930000000011'
     WHERE id = md5('qa:pl:f5')::uuid $q$);
SELECT pg_temp.expect_fail('F8 PUT_BACK without the bin it went back onto', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 1, put_back_by = md5('demo:user:editor')::uuid,
           put_back_at = NOW() WHERE id = md5('qa:pl:f5')::uuid $q$);
SELECT pg_temp.expect_fail('F9 PUT_BACK of less than was picked', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 0, put_back_by = md5('demo:user:editor')::uuid,
           put_back_location_code = 'HCM-A01-2-B', put_back_at = NOW() WHERE id = md5('qa:pl:f5')::uuid $q$);
SELECT pg_temp.expect_fail('F10 a put-back time on a line that was not put back', $q$
    UPDATE fulfillment.pick_line SET put_back_at = NOW() WHERE id = md5('qa:pl:f5')::uuid $q$);
SELECT pg_temp.expect_fail('F11 PUT_BACK onto a location that does not exist', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 1, put_back_by = md5('demo:user:editor')::uuid,
           put_back_location_code = 'HCM-ZZ9-9-Z', put_back_at = NOW() WHERE id = md5('qa:pl:f5')::uuid $q$);

\echo '--- the ledger: a pick line deducts once and is put back once (inventory.stock_movement)'
SELECT pg_temp.expect_ok('F12 the deduction of a pick line', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, quantity, from_status, reference_type,
                                          reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PICK', 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 'AVAILABLE', 'PICK_LINE', md5('qa:pl:f5')::uuid,
            NOW()) $q$);
SELECT pg_temp.expect_fail('F13 a second deduction of the same pick line', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, quantity, from_status, reference_type,
                                          reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PICK', 'SOFA-3S-GREY', 'HCM-A02-1-A', 1, 'AVAILABLE', 'PICK_LINE', md5('qa:pl:f5')::uuid,
            NOW()) $q$);
SELECT pg_temp.expect_fail('F14 a put-back from nowhere that no pick line caused', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, to_status, reference_type,
                                          reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 'AVAILABLE', 'MOVE_TASK', gen_random_uuid(),
            NOW()) $q$);
SELECT pg_temp.expect_fail('F15 a put-back with no bin to go to', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, quantity, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 1, 'PICK_LINE', md5('qa:pl:f5')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('F16 the order is cancelled: the line goes back onto its bin, one PUT_BACK row from nowhere', $q$
    UPDATE fulfillment.pick SET status = 'CANCELLED', needs_put_back = TRUE WHERE id = md5('qa:pick:f1')::uuid;
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 1, put_back_by = md5('demo:user:editor')::uuid,
           put_back_location_code = 'HCM-A01-2-B', put_back_at = NOW() WHERE id = md5('qa:pl:f5')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, to_status, reference_type,
                                          reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 'AVAILABLE', 'PICK_LINE',
            md5('qa:pl:f5')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F17 a second put-back of the same pick line', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, to_status, reference_type,
                                          reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-A02-1-A', 1, 'AVAILABLE', 'PICK_LINE',
            md5('qa:pl:f5')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('F18 a put-back moved back from a staging location still has both ends (unchanged shape)', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-PACK01', 'HCM-A01-2-B', 1, 'AVAILABLE', 'AVAILABLE',
            'MOVE_TASK', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_ok('F19 a cancelled line frees its hold for a new live line', $q$
    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
    VALUES (md5('qa:ol:f19')::uuid, md5('qa:order:f1')::uuid, 'SOFA-3S-GREY', 1, 12000000, 'VND');
    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity,
                                             reserved_at, expires_at, status)
    VALUES (md5('qa:res:f19')::uuid, '11111111-1111-4111-8111-111111111111', md5('qa:order:f1')::uuid,
            gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD');
    UPDATE inventory.stock_item SET reserved = reserved + 1 WHERE id = '11111111-1111-4111-8111-111111111111';
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, allocated_qty, status)
    VALUES (gen_random_uuid(), md5('qa:pick:f1')::uuid, md5('qa:ol:f19')::uuid, md5('qa:res:f19')::uuid,
            'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 'CANCELLED'),
           (gen_random_uuid(), md5('qa:pick:f1')::uuid, md5('qa:ol:f19')::uuid, md5('qa:res:f19')::uuid,
            'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 'OPEN') $q$);

\echo '--- the packing station: parcels, scans, shipments (G4-5, V20261021000300; docs 08 BR-01/03/04, 09 §5)'
INSERT INTO fulfillment.pack (id, pick_id, status, created_at)
VALUES (md5('qa:pack:f1')::uuid, md5('qa:pick:f1')::uuid, 'PENDING', NOW());
INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, created_at)
VALUES (md5('qa:pkg:f20')::uuid, md5('qa:pack:f1')::uuid, 1, 'PKG-SO-QA-F1-1', 'OPEN', NOW());
SELECT pg_temp.expect_ok('F20 a parcel holds the one unit the order line wants', $q$
    INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity)
    VALUES (gen_random_uuid(), md5('qa:pkg:f20')::uuid, md5('qa:ol:f5')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('F21 a second parcel with the same unit: packed more than the order wants (BR-01)', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, created_at)
    VALUES (md5('qa:pkg:f21')::uuid, md5('qa:pack:f1')::uuid, 2, 'PKG-SO-QA-F1-2', 'OPEN', NOW());
    INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity)
    VALUES (gen_random_uuid(), md5('qa:pkg:f21')::uuid, md5('qa:ol:f5')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('F22 two parcels with one label code', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, created_at)
    VALUES (gen_random_uuid(), md5('qa:pack:f1')::uuid, 3, 'PKG-SO-QA-F1-1', 'OPEN', NOW()) $q$);
SELECT pg_temp.expect_fail('F23 a parcel sealed without its height (BR-03)', $q$
    UPDATE fulfillment.package SET status = 'SEALED', sealed_at = NOW(), weight_kg = 1, length_cm = 1, width_cm = 1
     WHERE id = md5('qa:pkg:f20')::uuid $q$);
SELECT pg_temp.expect_fail('F24 a shipment in the old PENDING status', $q$
    INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, created_at)
    VALUES (gen_random_uuid(), md5('qa:pack:f1')::uuid, md5('qa:order:f1')::uuid, 'PENDING', 'SO-QA-F1-S9',
            md5('demo:wh:HCM')::uuid, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('F25 a partial shipment that does not say why', $q$
    INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, partial, created_at)
    VALUES (gen_random_uuid(), md5('qa:pack:f1')::uuid, md5('qa:order:f1')::uuid, 'READY_TO_DISPATCH', 'SO-QA-F1-S9',
            md5('demo:wh:HCM')::uuid, NOW(), TRUE, NOW()) $q$);
SELECT pg_temp.expect_ok('F26 a sealed parcel in a shipment ready to dispatch, its scan kept', $q$
    UPDATE fulfillment.package SET status = 'SEALED', sealed_at = NOW(), weight_kg = 1, length_cm = 1, width_cm = 1,
           height_cm = 1, sealed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa:pkg:f20')::uuid;
    INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, ready_by, created_at)
    VALUES (md5('qa:ship:f26')::uuid, md5('qa:pack:f1')::uuid, md5('qa:order:f1')::uuid, 'READY_TO_DISPATCH',
            'SO-QA-F1-S1', md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid, NOW());
    UPDATE fulfillment.package SET shipment_id = md5('qa:ship:f26')::uuid WHERE id = md5('qa:pkg:f20')::uuid;
    INSERT INTO fulfillment.package_scan (id, package_id, order_line_id, scanned_code, quantity, scanned_by, scanned_at)
    VALUES (md5('qa:scan:f26')::uuid, md5('qa:pkg:f20')::uuid, md5('qa:ol:f5')::uuid, 'SOFA-3S-GREY', 1,
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F27 a shipped parcel opened again', $q$
    UPDATE fulfillment.package SET status = 'OPEN' WHERE id = md5('qa:pkg:f20')::uuid $q$);
SELECT pg_temp.expect_fail('F28 a scan rewritten (append-only)', $q$
    UPDATE fulfillment.package_scan SET quantity = 2 WHERE id = md5('qa:scan:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F29 handed over without the time it happened', $q$
    UPDATE fulfillment.shipment SET status = 'HANDED_OVER' WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F30 a failed delivery without its reason (09 BR-02)', $q$
    UPDATE fulfillment.shipment SET status = 'DELIVERY_FAILED', handed_over_at = NOW(), outcome_at = NOW()
     WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F31 cancelled without the time it was', $q$
    UPDATE fulfillment.shipment SET status = 'CANCELLED' WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F32 one parcel, one order (BR-04): a scan of another order''s line', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                         placed_at, created_at, warehouse_id)
    VALUES (md5('qa:order:f32')::uuid, 'SO-QA-F32', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
            'PICKING', 1, 'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid);
    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
    VALUES (md5('qa:ol:f32')::uuid, md5('qa:order:f32')::uuid, 'SOFA-3S-GREY', 1, 1, 'VND');
    INSERT INTO fulfillment.package_scan (id, package_id, order_line_id, scanned_code, quantity, scanned_by, scanned_at)
    VALUES (gen_random_uuid(), md5('qa:pkg:f20')::uuid, md5('qa:ol:f32')::uuid, 'SOFA-3S-GREY', 1,
            md5('demo:user:editor')::uuid, NOW()) $q$);


\echo '--- loading and handover, parcel branch (G4-2a, V20261021000400; docs 09 §4.1, BR-01/03/04)'
SELECT pg_temp.expect_fail('F33 a parcel load LOADING without its carrier', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, mode, status, loading_started_at)
    VALUES (gen_random_uuid(), 'LD-QA-F33', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'PARCEL', 'LOADING',
            NOW()) $q$);
SELECT pg_temp.expect_fail('F34 a parcel load PLANNED (only a load plan plans a vehicle, BR-08)', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, mode, carrier_id, status)
    VALUES (gen_random_uuid(), 'LD-QA-F34', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'PARCEL',
            md5('carrier:GHN')::uuid, 'PLANNED') $q$);
SELECT pg_temp.expect_ok('F35 a parcel load opened for GHN at HCM', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, mode, carrier_id, status,
                                           loading_started_at, loading_started_by)
    VALUES (md5('qa:load:f35')::uuid, 'LD-QA-F35', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'PARCEL',
            md5('carrier:GHN')::uuid, 'LOADING', NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('F36 a shipment chosen for another carrier put on the manifest', $q$
    UPDATE fulfillment.shipment SET carrier_id = md5('carrier:GHTK')::uuid WHERE id = md5('qa:ship:f26')::uuid;
    INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, added_at, added_by)
    VALUES (gen_random_uuid(), md5('qa:load:f35')::uuid, md5('qa:ship:f26')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('F37 a parcel loaded without its shipment on the manifest', $q$
    INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by)
    VALUES (gen_random_uuid(), md5('qa:load:f35')::uuid, md5('qa:pkg:f20')::uuid, md5('qa:ship:f26')::uuid,
            gen_random_uuid(), NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('F38 the shipment on the manifest, its sealed parcel scanned onto the vehicle', $q$
    INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, added_at, added_by, label_cod_amount)
    VALUES (md5('qa:ls:f38')::uuid, md5('qa:load:f35')::uuid, md5('qa:ship:f26')::uuid, NOW(),
            md5('demo:user:editor')::uuid, 0);
    INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by)
    VALUES (md5('qa:lp:f38')::uuid, md5('qa:load:f35')::uuid, md5('qa:pkg:f20')::uuid, md5('qa:ship:f26')::uuid,
            gen_random_uuid(), NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('F39 one shipment on two open manifests at once', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, mode, carrier_id, status,
                                           loading_started_at)
    VALUES (md5('qa:load:f39')::uuid, 'LD-QA-F39', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'PARCEL',
            md5('carrier:GHN')::uuid, 'LOADING', NOW());
    INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, added_at, added_by)
    VALUES (gen_random_uuid(), md5('qa:load:f39')::uuid, md5('qa:ship:f26')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('F40 dispatched without who signed for the carrier and the plate', $q$
    UPDATE fulfillment.dispatch_load SET status = 'DISPATCHED', dispatched_at = NOW(),
           dispatched_by = md5('demo:user:editor')::uuid, shipment_count = 1, package_count = 1
     WHERE id = md5('qa:load:f35')::uuid $q$);
SELECT pg_temp.expect_fail('F41 a shipment handed over on no manifest (not a legacy one)', $q$
    UPDATE fulfillment.shipment SET status = 'HANDED_OVER', handed_over_at = NOW(),
           handed_over_by = md5('demo:user:editor')::uuid WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F42 a full truckload carrying COD (lead decision 2026-10-21)', $q$
    UPDATE fulfillment.shipment SET delivery_mode = 'FULL_TRUCKLOAD', cod_amount = 1
     WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F43 the order told of a shipment fulfillment never made', $q$
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
    VALUES (gen_random_uuid(), md5('qa:order:f1')::uuid, 'HANDED_OVER', 1, NOW()) $q$);
SELECT pg_temp.expect_ok('F44 signed: the load DISPATCHED, the shipment HANDED_OVER on it with its carrier', $q$
    UPDATE fulfillment.dispatch_load SET status = 'DISPATCHED', dispatched_at = NOW(),
           dispatched_by = md5('demo:user:editor')::uuid, carrier_representative = 'Driver', vehicle_plate = '51C-1',
           shipment_count = 1, package_count = 1
     WHERE id = md5('qa:load:f35')::uuid;
    UPDATE fulfillment.shipment SET status = 'HANDED_OVER', handed_over_at = NOW(),
           handed_over_by = md5('demo:user:editor')::uuid, load_id = md5('qa:load:f35')::uuid,
           carrier_id = md5('carrier:GHN')::uuid
     WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F45 a parcel loaded once its load left', $q$
    UPDATE fulfillment.dispatch_load_package SET active = FALSE, removed_at = NOW() WHERE id = md5('qa:lp:f38')::uuid;
    UPDATE fulfillment.dispatch_load_package SET active = TRUE, removed_at = NULL WHERE id = md5('qa:lp:f38')::uuid $q$);

\echo '--- damage found at packing (R8): units a pick took, back into quarantine, never more than it took'
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at,
                                         expires_at, status, closed_at)
VALUES (md5('qa:res:f46')::uuid, '11111111-1111-4111-8111-111111111111', md5('qa:order:f1')::uuid, gen_random_uuid(),
        gen_random_uuid(), 2, NOW(), NULL, 'CONSUMED', NOW());
INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                   allocated_qty, picked_qty, status, picked_by, picked_at, consumed_at)
VALUES (md5('qa:pl:f46')::uuid, md5('qa:pick:f1')::uuid, md5('qa:ol:f5')::uuid, md5('qa:res:f46')::uuid,
        'SOFA-3S-GREY', 'HCM-A01-2-B', 2, 2, 2, 'PICKED', md5('demo:user:editor')::uuid, NOW(), NOW());
INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, received_at, version, created_at)
SELECT md5('qa:si:f46')::uuid, sku, 'HCM-QC01', 1, 0, 'DAMAGED', received_at, 0, NOW()
  FROM inventory.stock_item WHERE id = '11111111-1111-4111-8111-111111111111';

SELECT pg_temp.expect_fail('F46 more units found damaged at packing than the line picked', $q$
    UPDATE fulfillment.pick_line SET packing_damaged_qty = 3 WHERE id = md5('qa:pl:f46')::uuid $q$);
SELECT pg_temp.expect_fail('F47 units found damaged at packing on a line that picked nothing', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, allocated_qty, status,
                                       packing_damaged_qty)
    VALUES (gen_random_uuid(), md5('qa:pick:f1')::uuid, md5('qa:ol:f5')::uuid, 'SOFA-3S-GREY', 'HCM-A01-2-B', 1,
            'OPEN', 1) $q$);
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at,
                                         expires_at, status, closed_at)
VALUES (md5('qa:res:f48')::uuid, '11111111-1111-4111-8111-111111111111', md5('qa:order:f1')::uuid, gen_random_uuid(),
        gen_random_uuid(), 1, NOW(), NULL, 'RELEASED', NOW());
SELECT pg_temp.expect_fail('F48 a packing damage report on an allocation that was never picked', $q$
    INSERT INTO inventory.stock_damage_report (id, report_number, request_id, origin, warehouse_id, sku,
           source_stock_item_id, source_location_code, damaged_stock_item_id, damage_location_code, quantity,
           reason_code, order_id, pick_line_id, reservation_id, reported_by, reported_at)
    VALUES (md5('qa:dmg:f48')::uuid, 'DMG-QA-F48', gen_random_uuid(), 'PACKING', md5('demo:wh:HCM')::uuid,
            'SOFA-3S-GREY', '11111111-1111-4111-8111-111111111111', 'HCM-A01-2-B', md5('qa:si:f46')::uuid,
            'HCM-QC01', 1, 'BROKEN', md5('qa:order:f1')::uuid, md5('qa:pl:f46')::uuid, md5('qa:res:f48')::uuid,
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F49 a packing damage report that names no pick line', $q$
    INSERT INTO inventory.stock_damage_report (id, report_number, request_id, origin, warehouse_id, sku,
           source_stock_item_id, source_location_code, damaged_stock_item_id, damage_location_code, quantity,
           reason_code, reported_by, reported_at)
    VALUES (md5('qa:dmg:f49')::uuid, 'DMG-QA-F49', gen_random_uuid(), 'PACKING', md5('demo:wh:HCM')::uuid,
            'SOFA-3S-GREY', '11111111-1111-4111-8111-111111111111', 'HCM-A01-2-B', md5('qa:si:f46')::uuid,
            'HCM-QC01', 1, 'BROKEN', md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('F50 one unit of the picked line damaged at packing: the report, its photo, the line, the ledger', $q$
    INSERT INTO inventory.stock_damage_report (id, report_number, request_id, origin, warehouse_id, sku,
           source_stock_item_id, source_location_code, damaged_stock_item_id, damage_location_code, quantity,
           reason_code, order_id, pick_line_id, reservation_id, reported_by, reported_at)
    VALUES (md5('qa:dmg:f50')::uuid, 'DMG-QA-F50', gen_random_uuid(), 'PACKING', md5('demo:wh:HCM')::uuid,
            'SOFA-3S-GREY', '11111111-1111-4111-8111-111111111111', 'HCM-A01-2-B', md5('qa:si:f46')::uuid,
            'HCM-QC01', 1, 'BROKEN', md5('qa:order:f1')::uuid, md5('qa:pl:f46')::uuid, md5('qa:res:f46')::uuid,
            md5('demo:user:editor')::uuid, NOW());
    INSERT INTO inventory.stock_damage_photo (id, damage_report_id, storage_key, original_name, content_type,
                                              size_bytes, checksum, sort_order)
    VALUES (gen_random_uuid(), md5('qa:dmg:f50')::uuid, 'qc-evidence/qa-f50.jpg', 'f50.jpg', 'image/jpeg', 20,
            repeat('a', 64), 1);
    UPDATE fulfillment.pick_line SET packing_damaged_qty = 1 WHERE id = md5('qa:pl:f46')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, from_status, to_status,
                                          reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-QC01', 1, 'DAMAGED', 'DAMAGED', 'DAMAGE_REPORT',
            md5('qa:dmg:f50')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F51 a second packing report taking the line past what it picked', $q$
    INSERT INTO inventory.stock_damage_report (id, report_number, request_id, origin, warehouse_id, sku,
           source_stock_item_id, source_location_code, damaged_stock_item_id, damage_location_code, quantity,
           reason_code, order_id, pick_line_id, reservation_id, reported_by, reported_at)
    VALUES (md5('qa:dmg:f51')::uuid, 'DMG-QA-F51', gen_random_uuid(), 'PACKING', md5('demo:wh:HCM')::uuid,
            'SOFA-3S-GREY', '11111111-1111-4111-8111-111111111111', 'HCM-A01-2-B', md5('qa:si:f46')::uuid,
            'HCM-QC01', 2, 'BROKEN', md5('qa:order:f1')::uuid, md5('qa:pl:f46')::uuid, md5('qa:res:f46')::uuid,
            md5('demo:user:editor')::uuid, NOW());
    INSERT INTO inventory.stock_damage_photo (id, damage_report_id, storage_key, original_name, content_type,
                                              size_bytes, checksum, sort_order)
    VALUES (gen_random_uuid(), md5('qa:dmg:f51')::uuid, 'qc-evidence/qa-f51.jpg', 'f51.jpg', 'image/jpeg', 20,
            repeat('a', 64), 1) $q$);
SELECT pg_temp.expect_fail('F52 a put-back of everything the line picked, the damaged unit included', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 2, put_back_by = md5('demo:user:editor')::uuid,
           put_back_location_code = 'HCM-A01-2-B', put_back_at = NOW() WHERE id = md5('qa:pl:f46')::uuid $q$);
SELECT pg_temp.expect_ok('F53 a put-back of what the line still has', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 1, put_back_by = md5('demo:user:editor')::uuid,
           put_back_location_code = 'HCM-A01-2-B', put_back_at = NOW() WHERE id = md5('qa:pl:f46')::uuid $q$);

\echo '--- damaged goods back to the supplier: in parts or refused, never more than decided, shipped units off the books'
INSERT INTO inventory.stock_damage_disposition (id, damage_report_id, request_id, disposition_type, quantity, note,
                                                decided_by, decided_at)
VALUES (md5('qa:disp:f54')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'RETURN_TO_SUPPLIER', 1,
        'supplier credits it', md5('demo:user:approver')::uuid, NOW());
SELECT pg_temp.expect_fail('F54 more units handed over than the return decision named', $q$
    INSERT INTO inventory.stock_damage_supplier_shipment (id, disposition_id, damage_report_id, request_id, outcome,
                                                          quantity, delivery_note, shipped_by, shipped_at)
    VALUES (gen_random_uuid(), md5('qa:disp:f54')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'SHIPPED', 2,
            'PXT-QA-1', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F55 a hand-over with no delivery note', $q$
    INSERT INTO inventory.stock_damage_supplier_shipment (id, disposition_id, damage_report_id, request_id, outcome,
                                                          quantity, delivery_note, shipped_by, shipped_at)
    VALUES (gen_random_uuid(), md5('qa:disp:f54')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'SHIPPED', 1,
            '  ', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F56 a refusal that does not say what the supplier answered', $q$
    INSERT INTO inventory.stock_damage_supplier_shipment (id, disposition_id, damage_report_id, request_id, outcome,
                                                          quantity, shipped_by, shipped_at)
    VALUES (gen_random_uuid(), md5('qa:disp:f54')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'REFUSED', 1,
            md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F57 a hand-over of a decision that is not a return to the supplier', $q$
    INSERT INTO inventory.stock_damage_disposition (id, damage_report_id, request_id, disposition_type, quantity,
                                                    record_number, stock_adjustment_id, decided_by, decided_at)
    SELECT md5('qa:disp:f57')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'WRITE_OFF', 1, 'WO-QA-F57', a.id,
           md5('demo:user:approver')::uuid, NOW() FROM inventory.stock_adjustment a LIMIT 1;
    INSERT INTO inventory.stock_damage_supplier_shipment (id, disposition_id, damage_report_id, request_id, outcome,
                                                          quantity, delivery_note, shipped_by, shipped_at)
    VALUES (gen_random_uuid(), md5('qa:disp:f57')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'SHIPPED', 1,
            'PXT-QA-9', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('F58 the supplier refused the unit: undecided again, decided once more', $q$
    INSERT INTO inventory.stock_damage_supplier_shipment (id, disposition_id, damage_report_id, request_id, outcome,
                                                          quantity, note, shipped_by, shipped_at)
    VALUES (md5('qa:ship:f58')::uuid, md5('qa:disp:f54')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'REFUSED',
            1, 'not ours to take back', md5('demo:user:approver')::uuid, NOW());
    INSERT INTO inventory.stock_damage_disposition (id, damage_report_id, request_id, disposition_type, quantity, note,
                                                    decided_by, decided_at)
    VALUES (md5('qa:disp:f58')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'RETURN_TO_SUPPLIER', 1,
            'another supplier takes it', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F59 the refused decision handed over all the same', $q$
    INSERT INTO inventory.stock_damage_supplier_shipment (id, disposition_id, damage_report_id, request_id, outcome,
                                                          quantity, delivery_note, shipped_by, shipped_at)
    VALUES (gen_random_uuid(), md5('qa:disp:f54')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'SHIPPED', 1,
            'PXT-QA-2', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('F60 the second decision handed over, and its SUPPLIER_RETURN line out of quarantine', $q$
    INSERT INTO inventory.stock_damage_supplier_shipment (id, disposition_id, damage_report_id, request_id, outcome,
                                                          quantity, delivery_note, shipped_by, shipped_at)
    VALUES (md5('qa:ship:f60')::uuid, md5('qa:disp:f58')::uuid, md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'SHIPPED',
            1, 'PXT-QA-1', md5('demo:user:approver')::uuid, NOW());
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, quantity, from_status, to_status,
                                          reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'SUPPLIER_RETURN', 'SOFA-3S-GREY', 'HCM-QC01', 1, 'DAMAGED', 'DAMAGED',
            'DAMAGE_REPORT', md5('qa:dmg:f50')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F61 a decision beyond the report once the refused unit was decided again', $q$
    INSERT INTO inventory.stock_damage_disposition (id, damage_report_id, request_id, disposition_type, quantity, note,
                                                    decided_by, decided_at)
    VALUES (gen_random_uuid(), md5('qa:dmg:f50')::uuid, gen_random_uuid(), 'RESTORE', 1, 'fine after all',
            md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F62 a hand-over rewritten (append-only)', $q$
    UPDATE inventory.stock_damage_supplier_shipment SET delivery_note = 'PXT-QA-9' WHERE id = md5('qa:ship:f60')::uuid $q$);
SELECT pg_temp.expect_fail('F63 goods going back to the supplier into a location (they leave to nowhere)', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          from_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'SUPPLIER_RETURN', 'SOFA-3S-GREY', 'HCM-QC01', 'HCM-A01-2-B', 1, 'DAMAGED',
            'DAMAGE_REPORT', md5('qa:dmg:f50')::uuid, NOW()) $q$);
ROLLBACK;
\echo 'PASS 09_outbound'
