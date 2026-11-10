-- =============================================================================
-- Outbound fulfillment: the order number and warehouse a task carries (SCRUM-466,
-- V20261014000100; kltn-docs 07 §4, BR-SEC-002); pick confirmation, deduction and put-back
-- (SCRUM-267/274/275, V20261014001000; docs 07 step 6, BR-04, BR-05); the packing station's parcels, scans and
-- shipments (G4-5, V20261021000300; docs 08 BR-01/03/04, 09 §5); a load plan's vehicle, its loading and the handover
-- (SCRUM-392; docs 09 §4.2, BR-01/03/08 - every order ships as a full truckload, parcel loads and COD were removed by
-- V20261108000400); damage found at packing among picked units (R8,
-- V20261101030000/030100); a carrier's connection settings and service codes (3-1, V20261108001300; docs 09 §4.1
-- step 2, BR-21). Needs the demo seed.
-- Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
-- V20261111000110 (team plan 4C-1) put the demo stock into cartons. The checks of loose stock below - loose stock goes
-- with 4C-3 - use the demo sofa rows as they were before it. The sofas' cartons stay beside them, so the books hold
-- the demo sofas twice for the length of this script; no check here reads a SKU's total.
UPDATE inventory.stock_item SET on_hand = 25 WHERE id = '11111111-1111-4111-8111-111111111111';
UPDATE inventory.stock_item SET on_hand = 8 WHERE id = '22222222-2222-4222-8222-222222222222';

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

-- Since V20261113000400 (team plan 4C-4) a pick line is one carton, named by its LPN, and a put-back returns it whole:
-- the lines below are on demo cartons of one sofa (n = 1 to 4) and on a carton of three of their own, QA10-LPN-3.
CREATE TEMP TABLE qa10_carton AS
SELECT row_number() OVER (ORDER BY p.lpn) AS n, s.id AS row_id, p.id AS package_id, p.lpn
  FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
 WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK' AND s.on_hand = 1
 ORDER BY p.lpn LIMIT 4;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa10:unit')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'QA10_CTN', 3);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status)
VALUES (md5('qa10:pk3')::uuid, 'QA10-LPN-3', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa10:unit')::uuid, 3, 3,
        'HCM-A01-2-B', 'IN_STOCK');
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                  received_at, stock_package_id)
VALUES (md5('qa10:pk3:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-2-B', 'UNTRACED', 3, 0, 'AVAILABLE', NOW(),
        NOW() - interval '1 day', md5('qa10:pk3')::uuid);

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
SELECT md5('qa:res:f5')::uuid, c.row_id, md5('qa:order:f1')::uuid, gen_random_uuid(),
        gen_random_uuid(), 1, NOW(), NULL, 'CONSUMED', NOW() FROM qa10_carton c WHERE c.n = 1;
INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no, allocated_qty,
                                   lpn, whole_carton)
SELECT md5('qa:pl:f5')::uuid, md5('qa:pick:f1')::uuid, md5('qa:ol:f5')::uuid, md5('qa:res:f5')::uuid, 'SOFA-3S-GREY',
        'HCM-A01-2-B', 1, 1, c.lpn, TRUE FROM qa10_carton c WHERE c.n = 1;

SELECT pg_temp.expect_fail('F5 a line PICKED against a hold that was never deducted (consumed_at NULL)', $q$
    UPDATE fulfillment.pick_line SET status = 'PICKED', picked_qty = 1, picked_by = md5('demo:user:editor')::uuid,
           picked_at = NOW() WHERE id = md5('qa:pl:f5')::uuid $q$);
SELECT pg_temp.expect_fail_by('F6 a second live line for the same hold', 'uk_pick_line_reservation_live', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, allocated_qty, lpn)
    SELECT gen_random_uuid(), md5('qa:pick:f1')::uuid, md5('qa:ol:f5')::uuid, md5('qa:res:f5')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 1, c.lpn FROM qa10_carton c WHERE c.n = 1 $q$);
SELECT pg_temp.expect_ok('F7 a line picked in full and deducted', $q$
    UPDATE fulfillment.pick_line SET status = 'PICKED', picked_qty = 1, picked_by = md5('demo:user:editor')::uuid,
           picked_at = NOW(), consumed_at = NOW(), scanned_location_code = 'HCM-A01-2-B',
           scanned_code = (SELECT lpn FROM qa10_carton WHERE n = 1)
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
                                          reference_id, occurred_at, to_stock_package_id)
    SELECT gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 'AVAILABLE', 'PICK_LINE',
           md5('qa:pl:f5')::uuid, NOW(), c.package_id FROM qa10_carton c WHERE c.n = 1 $q$);
SELECT pg_temp.expect_fail_by('F17 a second put-back of the same pick line', 'uk_stock_movement', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, to_status, reference_type,
                                          reference_id, occurred_at, to_stock_package_id)
    SELECT gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 'AVAILABLE', 'PICK_LINE',
           md5('qa:pl:f5')::uuid, NOW(), c.package_id FROM qa10_carton c WHERE c.n = 1 $q$);
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
    SELECT md5('qa:res:f19')::uuid, c.row_id, md5('qa:order:f1')::uuid,
           gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD' FROM qa10_carton c WHERE c.n = 2;
    UPDATE inventory.stock_item SET reserved = reserved + 1 WHERE id = (SELECT row_id FROM qa10_carton WHERE n = 2);
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, allocated_qty, status,
                                       lpn, whole_carton)
    SELECT gen_random_uuid(), md5('qa:pick:f1')::uuid, md5('qa:ol:f19')::uuid, md5('qa:res:f19')::uuid,
           'SOFA-3S-GREY', 'HCM-A01-2-B', 1, v.status, c.lpn, TRUE
      FROM qa10_carton c CROSS JOIN (VALUES ('CANCELLED'), ('OPEN')) v(status) WHERE c.n = 2 $q$);

\echo '--- the check station: shipping cartons, shipments (V20261021000300, 4C-7, 4C-6; docs 08 BR-01/03/04, 09 §5)'
INSERT INTO fulfillment.pack (id, pick_id, status, created_at)
VALUES (md5('qa:pack:f1')::uuid, md5('qa:pick:f1')::uuid, 'PENDING', NOW());
-- One more picked, deducted carton on the pick (carton 4): the shipping carton row below is its.
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at,
                                         expires_at, status, closed_at)
SELECT md5('qa:res:f20')::uuid, c.row_id, md5('qa:order:f1')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL,
       'CONSUMED', NOW() FROM qa10_carton c WHERE c.n = 4;
INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no, allocated_qty,
                                   picked_qty, status, picked_by, picked_at, consumed_at, lpn, whole_carton)
SELECT md5('qa:pl:f20')::uuid, md5('qa:pick:f1')::uuid, md5('qa:ol:f5')::uuid, md5('qa:res:f20')::uuid, 'SOFA-3S-GREY',
       'HCM-A01-2-B', 5, 1, 1, 'PICKED', md5('demo:user:editor')::uuid, NOW(), NOW(), c.lpn, TRUE
  FROM qa10_carton c WHERE c.n = 4;
SELECT pg_temp.expect_fail_by('F20 a new row that names no pick line: parcels are no longer packed (4C-7)',
    'parcels are no longer packed', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, created_at)
    VALUES (gen_random_uuid(), md5('qa:pack:f1')::uuid, 9, 'PKG-SO-QA-F1-9', 'OPEN', NOW()) $q$);
SELECT pg_temp.expect_ok('F21 the picked carton becomes a shipping carton row, TO_CHECK', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn, created_at)
    SELECT md5('qa:pkg:f20')::uuid, md5('qa:pack:f1')::uuid, 1, 'PKG-SO-QA-F1-1', 'TO_CHECK', md5('qa:pl:f20')::uuid, c.lpn, NOW()
      FROM qa10_carton c WHERE c.n = 4 $q$);
SELECT pg_temp.expect_fail('F22 two cartons with one label code', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn, created_at)
    SELECT gen_random_uuid(), md5('qa:pack:f1')::uuid, 3, 'PKG-SO-QA-F1-1', 'TO_CHECK', md5('qa:pl:f20')::uuid, c.lpn, NOW()
      FROM qa10_carton c WHERE c.n = 4 $q$);
SELECT pg_temp.expect_fail('F23 a carton labelled without its height (BR-03)', $q$
    UPDATE fulfillment.package SET status = 'CHECKED', checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa:pkg:f20')::uuid;
    UPDATE fulfillment.package SET status = 'LABELED',
           labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid, label_version = 1, weight_kg = 12, length_cm = 60,
           width_cm = 40, dim_weight_kg = 16 WHERE id = md5('qa:pkg:f20')::uuid $q$);
SELECT pg_temp.expect_fail('F24 a shipment in the old PENDING status', $q$
    INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, created_at)
    VALUES (gen_random_uuid(), md5('qa:pack:f1')::uuid, md5('qa:order:f1')::uuid, 'PENDING', 'SO-QA-F1-S9',
            md5('demo:wh:HCM')::uuid, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('F25 a partial shipment that does not say why', $q$
    INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, partial, created_at)
    VALUES (gen_random_uuid(), md5('qa:pack:f1')::uuid, md5('qa:order:f1')::uuid, 'READY_TO_DISPATCH', 'SO-QA-F1-S9',
            md5('demo:wh:HCM')::uuid, NOW(), TRUE, NOW()) $q$);
SELECT pg_temp.expect_ok('F26 a labelled carton in a shipment ready to dispatch', $q$
    UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = md5('qa:order:f1')::uuid;
    UPDATE fulfillment.package SET status = 'CHECKED', checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa:pkg:f20')::uuid;
    UPDATE fulfillment.package SET status = 'LABELED',
           labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid, label_version = 1, weight_kg = 12, length_cm = 60,
           width_cm = 40, height_cm = 40, dim_weight_kg = 16 WHERE id = md5('qa:pkg:f20')::uuid;
    INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, ready_by, created_at)
    VALUES (md5('qa:ship:f26')::uuid, md5('qa:pack:f1')::uuid, md5('qa:order:f1')::uuid, 'READY_TO_DISPATCH',
            'SO-QA-F1-S1', md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid, NOW());
    UPDATE fulfillment.package SET shipment_id = md5('qa:ship:f26')::uuid WHERE id = md5('qa:pkg:f20')::uuid $q$);
SELECT pg_temp.expect_fail_by('F27 a shipped carton back to TO_CHECK', 'tg_package_carton', $q$
    UPDATE fulfillment.package SET status = 'TO_CHECK' WHERE id = md5('qa:pkg:f20')::uuid $q$);
SELECT pg_temp.expect_fail('F29 handed over without the time it happened', $q$
    UPDATE fulfillment.shipment SET status = 'HANDED_OVER' WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F30 a failed delivery without its reason (09 BR-02)', $q$
    UPDATE fulfillment.shipment SET status = 'DELIVERY_FAILED', handed_over_at = NOW(), outcome_at = NOW()
     WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F31 cancelled without the time it was', $q$
    UPDATE fulfillment.shipment SET status = 'CANCELLED' WHERE id = md5('qa:ship:f26')::uuid $q$);

\echo '--- a load plan''s vehicle: booking, loading and handover (SCRUM-392; docs 09 §4.2, BR-01/03/08)'
SELECT pg_temp.expect_fail('F33 an open load that is no load plan''s vehicle (parcel loads were removed)', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status)
    VALUES (gen_random_uuid(), 'LD-QA-F33', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'PLANNED') $q$);
INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, status, calculated_at, confirmed_at,
                                   confirmed_by)
VALUES (md5('qa:plan:f34')::uuid, 'LP-QA-F34', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'CONFIRMED', NOW(), NOW(),
        md5('demo:user:editor')::uuid);
INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, vehicle_type_id, vehicle_code, vehicle_kind,
                                           inner_length_mm, inner_width_mm, inner_height_mm, max_payload_kg)
SELECT md5('qa:veh:f34')::uuid, md5('qa:plan:f34')::uuid, 1, t.id, t.code, t.kind, t.inner_length_mm, t.inner_width_mm,
       t.inner_height_mm, t.max_payload_kg
  FROM fulfillment.vehicle_type t WHERE t.code = 'TRUCK-2T5';
SELECT pg_temp.expect_fail('F34 a plan''s vehicle LOADING without the carrier it was booked with', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status, load_plan_vehicle_id,
                                           booked_at, loading_started_at)
    VALUES (gen_random_uuid(), 'LD-QA-F34', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'LOADING',
            md5('qa:veh:f34')::uuid, NOW(), NOW()) $q$);
SELECT pg_temp.expect_ok('F35 the plan''s vehicle, PLANNED, then booked with GHN', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status, load_plan_vehicle_id)
    VALUES (md5('qa:load:f35')::uuid, 'LD-QA-F35', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'PLANNED',
            md5('qa:veh:f34')::uuid);
    UPDATE fulfillment.dispatch_load SET status = 'VEHICLE_BOOKED', carrier_id = md5('carrier:GHN')::uuid,
           booked_at = NOW(), booked_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa:load:f35')::uuid $q$);
SELECT pg_temp.expect_fail('F36 a plan''s vehicle loading that was never booked', $q$
    UPDATE fulfillment.dispatch_load SET status = 'LOADING', booked_at = NULL, loading_started_at = NOW()
     WHERE id = md5('qa:load:f35')::uuid $q$);
SELECT pg_temp.expect_ok('F36b loading starts on the booked vehicle', $q$
    UPDATE fulfillment.dispatch_load SET status = 'LOADING', loading_started_at = NOW(),
           loading_started_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa:load:f35')::uuid $q$);
SELECT pg_temp.expect_fail('F37 a parcel loaded without its shipment on the manifest', $q$
    INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by)
    VALUES (gen_random_uuid(), md5('qa:load:f35')::uuid, md5('qa:pkg:f20')::uuid, md5('qa:ship:f26')::uuid,
            gen_random_uuid(), NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('F38 the shipment on the manifest, its sealed parcel scanned onto the vehicle', $q$
    INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, added_at, added_by)
    VALUES (md5('qa:ls:f38')::uuid, md5('qa:load:f35')::uuid, md5('qa:ship:f26')::uuid, NOW(),
            md5('demo:user:editor')::uuid);
    INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by)
    VALUES (md5('qa:lp:f38')::uuid, md5('qa:load:f35')::uuid, md5('qa:pkg:f20')::uuid, md5('qa:ship:f26')::uuid,
            gen_random_uuid(), NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('F39 one shipment on the vehicles of two plans at once', $q$
    INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, status, calculated_at, confirmed_at,
                                       confirmed_by)
    VALUES (md5('qa:plan:f39')::uuid, 'LP-QA-F39', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'CONFIRMED', NOW(),
            NOW(), md5('demo:user:editor')::uuid);
    INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, vehicle_type_id, vehicle_code, vehicle_kind,
                                               inner_length_mm, inner_width_mm, inner_height_mm, max_payload_kg)
    SELECT md5('qa:veh:f39')::uuid, md5('qa:plan:f39')::uuid, 1, t.id, t.code, t.kind, t.inner_length_mm,
           t.inner_width_mm, t.inner_height_mm, t.max_payload_kg
      FROM fulfillment.vehicle_type t WHERE t.code = 'TRUCK-2T5';
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status, load_plan_vehicle_id)
    VALUES (md5('qa:load:f39')::uuid, 'LD-QA-F39', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'PLANNED',
            md5('qa:veh:f39')::uuid);
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
SELECT pg_temp.expect_fail('F42 nothing is collected on delivery: shipments carry no COD any more', $q$
    UPDATE fulfillment.shipment SET cod_amount = 1 WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F43 the order told of a shipment fulfillment never made', $q$
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
    VALUES (gen_random_uuid(), md5('qa:order:f1')::uuid, 'HANDED_OVER', 1, NOW()) $q$);
SELECT pg_temp.expect_ok('F44 signed: the load DISPATCHED, the shipment HANDED_OVER on it with its carrier', $q$
    UPDATE fulfillment.dispatch_load SET status = 'DISPATCHED', dispatched_at = NOW(),
           dispatched_by = md5('demo:user:editor')::uuid, carrier_representative = 'Driver', vehicle_plate = '51C-1',
           shipment_count = 1, package_count = 1, waybill_no = 'WB-QA-F44'
     WHERE id = md5('qa:load:f35')::uuid;
    UPDATE fulfillment.shipment SET status = 'HANDED_OVER', handed_over_at = NOW(),
           handed_over_by = md5('demo:user:editor')::uuid, load_id = md5('qa:load:f35')::uuid,
           carrier_id = md5('carrier:GHN')::uuid
     WHERE id = md5('qa:ship:f26')::uuid $q$);
SELECT pg_temp.expect_fail('F45 a parcel loaded once its load left', $q$
    UPDATE fulfillment.dispatch_load_package SET active = FALSE, removed_at = NOW() WHERE id = md5('qa:lp:f38')::uuid;
    UPDATE fulfillment.dispatch_load_package SET active = TRUE, removed_at = NULL WHERE id = md5('qa:lp:f38')::uuid $q$);

\echo '--- the per-unit damage reports are gone (V20261114000400, 5-6); the ledger keeps what they wrote'
SELECT pg_temp.expect_fail('F63 a ledger line of goods that went back to the supplier into a location (they left to nowhere; the ledger keeps its history rule)', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          from_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'SUPPLIER_RETURN', 'SOFA-3S-GREY', 'HCM-QC01', 'HCM-A01-2-B', 1, 'DAMAGED',
            'DAMAGE_REPORT', gen_random_uuid(), NOW()) $q$);

-- 3-1: what each kind of carrier connection needs (V20261108001300).
INSERT INTO fulfillment.carrier (id, code, name, status) VALUES (md5('qa:carrier:f64')::uuid, 'QA-AHA', 'QA Ahamove',
                                                                  'ACTIVE');
SELECT pg_temp.expect_fail('F64 a manual carrier with webhooks enabled', $q$
    UPDATE fulfillment.carrier SET webhook_enabled = TRUE WHERE id = md5('qa:carrier:f64')::uuid $q$);
SELECT pg_temp.expect_fail('F65 a signing carrier without its secret', $q$
    UPDATE fulfillment.carrier SET connection_type = 'WEBHOOK_LOAD' WHERE id = md5('qa:carrier:f64')::uuid $q$);
SELECT pg_temp.expect_fail('F66 a secret stored in clear', $q$
    UPDATE fulfillment.carrier SET connection_type = 'WEBHOOK_LOAD', webhook_secret_sealed = 'my-signing-secret'
    WHERE id = md5('qa:carrier:f64')::uuid $q$);
SELECT pg_temp.expect_fail('F67 a carrier booked through its API without the webhook header credential', $q$
    UPDATE fulfillment.carrier SET connection_type = 'API_BOOKING', api_environment = 'STAGING',
           api_account = '84908000000', api_key_sealed = 'v1:AAAAAAAAAAAAAAAAAAAAAAAA'
    WHERE id = md5('qa:carrier:f64')::uuid $q$);
SELECT pg_temp.expect_ok('F68 a carrier booked through its API with everything it needs', $q$
    UPDATE fulfillment.carrier SET connection_type = 'API_BOOKING', webhook_enabled = TRUE, api_environment = 'STAGING',
           api_account = '84908000000', api_key_sealed = 'v1:AAAAAAAAAAAAAAAAAAAAAAAA', webhook_auth_header = 'apikey',
           webhook_auth_value_sealed = 'v1:BBBBBBBBBBBBBBBBBBBBBBBB',
           failure_reason_map = '{"Khách không nghe máy": "CUSTOMER_ABSENT"}'
    WHERE id = md5('qa:carrier:f64')::uuid $q$);
SELECT pg_temp.expect_fail('F69 a failure reason mapped to no internal reason', $q$
    UPDATE fulfillment.carrier SET failure_reason_map = '{"Xe hỏng": "BROKEN_TRUCK"}'
    WHERE id = md5('qa:carrier:f64')::uuid $q$);
SELECT pg_temp.expect_ok('F70 a service code for a truck size in a city', $q$
    INSERT INTO fulfillment.carrier_service_code (id, carrier_id, vehicle_type_id, province_code, carrier_city_code,
                                                  service_code)
    VALUES (md5('qa:svc:f70')::uuid, md5('qa:carrier:f64')::uuid,
            (SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25'), '79', 'SGN', 'SGN-TRUCK-1000') $q$);
SELECT pg_temp.expect_fail('F71 a second service code for the same truck size in the same city', $q$
    INSERT INTO fulfillment.carrier_service_code (id, carrier_id, vehicle_type_id, province_code, carrier_city_code,
                                                  service_code)
    VALUES (gen_random_uuid(), md5('qa:carrier:f64')::uuid,
            (SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25'), '79', 'SGN', 'SGN-TRUCK-OTHER') $q$);

-- 3-2: the waybill number on a load, its history (V20261108001400).
INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, carrier_id, vehicle_plate, status,
                                       loading_started_at, dispatched_at, dispatched_by, carrier_representative,
                                       shipment_count, package_count, waybill_no)
VALUES (md5('qa:load:f72')::uuid, 'LD-QA-F72', gen_random_uuid(), md5('demo:wh:HCM')::uuid,
        md5('qa:carrier:f64')::uuid, '51C-1', 'DISPATCHED', NOW(), NOW(), md5('demo:user:approver')::uuid, 'Tai', 1,
        1, 'WB-QA-F72');
SELECT pg_temp.expect_fail('F72 a load leaving without a waybill number', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, carrier_id, vehicle_plate, status,
                                           loading_started_at, dispatched_at, dispatched_by, carrier_representative,
                                           shipment_count, package_count)
    VALUES (gen_random_uuid(), 'LD-QA-F72B', gen_random_uuid(), md5('demo:wh:HCM')::uuid,
            md5('qa:carrier:f64')::uuid, '51C-2', 'DISPATCHED', NOW(), NOW(), md5('demo:user:approver')::uuid,
            'Tai', 1, 1) $q$);
SELECT pg_temp.expect_fail('F73 the same waybill number on two live loads of one carrier', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, carrier_id, vehicle_plate, status,
                                           loading_started_at, dispatched_at, dispatched_by, carrier_representative,
                                           shipment_count, package_count, waybill_no)
    VALUES (gen_random_uuid(), 'LD-QA-F73', gen_random_uuid(), md5('demo:wh:HCM')::uuid,
            md5('qa:carrier:f64')::uuid, '51C-3', 'DISPATCHED', NOW(), NOW(), md5('demo:user:approver')::uuid,
            'Tai', 1, 1, 'WB-QA-F72') $q$);
SELECT pg_temp.expect_ok('F74 a cancelled load of the carrier may hold the same number', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, carrier_id, status, cancelled_at,
                                           cancel_reason, waybill_no)
    VALUES (gen_random_uuid(), 'LD-QA-F74', gen_random_uuid(), md5('demo:wh:HCM')::uuid,
            md5('qa:carrier:f64')::uuid, 'CANCELLED', NOW(), 'xe không tới', 'WB-QA-F72') $q$);
SELECT pg_temp.expect_ok('F75 a waybill change recorded', $q$
    INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, source, changed_by,
                                            changed_at, reason)
    VALUES (md5('qa:wbc:f75')::uuid, md5('qa:load:f72')::uuid, NULL, 'WB-QA-F72', 'MANUAL',
            md5('demo:user:approver')::uuid, NOW(), NULL) $q$);
SELECT pg_temp.expect_fail('F76 a waybill change rewritten (append-only)', $q$
    UPDATE fulfillment.waybill_change SET reason = 'later' WHERE id = md5('qa:wbc:f75')::uuid $q$);
SELECT pg_temp.expect_fail('F77 a webhook change signed by someone', $q$
    INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, source, changed_by,
                                            changed_at)
    VALUES (gen_random_uuid(), md5('qa:load:f72')::uuid, 'WB-QA-F72', 'WB-QA-F77', 'WEBHOOK',
            md5('demo:user:approver')::uuid, NOW()) $q$);

-- 3-3: an import of waybill numbers and its lines (V20261108001500).
SELECT pg_temp.expect_ok('F78 an import of four lines, one applied', $q$
    INSERT INTO fulfillment.waybill_import (id, file_name, storage_key, uploaded_by, uploaded_at, rows_total,
                                            rows_applied, rows_rejected)
    VALUES (md5('qa:imp:f78')::uuid, 'van-don.csv', 'waybill-imports/2026/11/09/qa-f78.csv', md5('demo:user:approver')::uuid,
            NOW(), 4, 1, 3) $q$);
SELECT pg_temp.expect_fail('F79 an import whose counts do not add up', $q$
    INSERT INTO fulfillment.waybill_import (id, file_name, storage_key, uploaded_by, uploaded_at, rows_total,
                                            rows_applied, rows_rejected)
    VALUES (gen_random_uuid(), 'x.csv', 'waybill-imports/2026/11/09/qa-f79.csv', md5('demo:user:approver')::uuid, NOW(), 4, 1,
            1) $q$);
SELECT pg_temp.expect_fail('F80 an applied line naming no load', $q$
    INSERT INTO fulfillment.waybill_import_row (id, import_id, line_no, load_code, waybill_no, outcome)
    VALUES (gen_random_uuid(), md5('qa:imp:f78')::uuid, 2, 'LD-X', 'WB-X', 'NEW') $q$);
SELECT pg_temp.expect_fail('F81 a refused line with no reason', $q$
    INSERT INTO fulfillment.waybill_import_row (id, import_id, line_no, load_code, waybill_no, outcome)
    VALUES (gen_random_uuid(), md5('qa:imp:f78')::uuid, 3, 'LD-X', 'WB-X', 'ERROR') $q$);
SELECT pg_temp.expect_ok('F82 a refused line with its reason', $q$
    INSERT INTO fulfillment.waybill_import_row (id, import_id, line_no, load_code, waybill_no, outcome, problem)
    VALUES (gen_random_uuid(), md5('qa:imp:f78')::uuid, 4, 'LD-X', 'WB-X', 'ERROR', 'LOAD_NOT_FOUND') $q$);
SELECT pg_temp.expect_fail('F83 an import rewritten (append-only)', $q$
    UPDATE fulfillment.waybill_import SET rows_applied = 2, rows_rejected = 2 WHERE id = md5('qa:imp:f78')::uuid $q$);
SELECT pg_temp.expect_ok('F84 the imported file kept with two loads', $q$
    INSERT INTO fulfillment.load_waybill_file (id, load_id, storage_key, file_name, content_type, size_bytes,
                                               uploaded_by, uploaded_at, waybill_import_id)
    VALUES (gen_random_uuid(), md5('qa:load:f72')::uuid, 'waybill-imports/2026/11/09/qa-f78.csv', 'van-don.csv', 'text/csv',
            10, md5('demo:user:approver')::uuid, NOW(), md5('qa:imp:f78')::uuid),
           (gen_random_uuid(), md5('qa:load:f35')::uuid, 'waybill-imports/2026/11/09/qa-f78.csv', 'van-don.csv', 'text/csv',
            10, md5('demo:user:approver')::uuid, NOW(), md5('qa:imp:f78')::uuid) $q$);
SELECT pg_temp.expect_fail('F85 the same file kept twice with one load', $q$
    INSERT INTO fulfillment.load_waybill_file (id, load_id, storage_key, file_name, content_type, size_bytes,
                                               uploaded_by, uploaded_at)
    VALUES (gen_random_uuid(), md5('qa:load:f72')::uuid, 'waybill-imports/2026/11/09/qa-f78.csv', 'van-don.csv', 'text/csv',
            10, md5('demo:user:approver')::uuid, NOW()) $q$);

-- 3-4: booking through a carrier's API - pickup points, orders at the carrier, the call log (V20261108001600).
SELECT pg_temp.expect_ok('F86 a pickup point of the carrier at a warehouse', $q$
    INSERT INTO fulfillment.carrier_pickup_point (id, carrier_id, warehouse_id, province_code, address)
    VALUES (gen_random_uuid(), md5('qa:carrier:f64')::uuid, md5('demo:wh:HCM')::uuid, '79',
            '1 Lê Lợi, Bến Nghé, Quận 1, Hồ Chí Minh') $q$);
SELECT pg_temp.expect_fail('F87 a second pickup point of the carrier at that warehouse', $q$
    INSERT INTO fulfillment.carrier_pickup_point (id, carrier_id, warehouse_id, province_code, address)
    VALUES (gen_random_uuid(), md5('qa:carrier:f64')::uuid, md5('demo:wh:HCM')::uuid, '79', '2 Lê Lợi') $q$);
SELECT pg_temp.expect_ok('F88 an order claimed at the carrier for a load', $q$
    INSERT INTO fulfillment.carrier_booking (id, load_id, carrier_id, service_code, stops, status, requested_by,
                                             requested_at)
    VALUES (md5('qa:cb:f88')::uuid, md5('qa:load:f72')::uuid, md5('qa:carrier:f64')::uuid, 'SGN-TRUCK-1000',
            '[{"seq":1,"shipmentCode":"SO-QA-1"}]', 'REQUESTED', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F89 a second order in flight for the same load', $q$
    INSERT INTO fulfillment.carrier_booking (id, load_id, carrier_id, service_code, stops, status, requested_by,
                                             requested_at)
    VALUES (gen_random_uuid(), md5('qa:load:f72')::uuid, md5('qa:carrier:f64')::uuid, 'SGN-TRUCK-1000',
            '[{"seq":1,"shipmentCode":"SO-QA-1"}]', 'REQUESTED', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F90 an order booked without the carrier''s id', $q$
    UPDATE fulfillment.carrier_booking SET status = 'BOOKED', booked_at = NOW() WHERE id = md5('qa:cb:f88')::uuid $q$);
SELECT pg_temp.expect_fail('F91 a token call logged with its key', $q$
    INSERT INTO fulfillment.carrier_api_call (id, carrier_id, operation, outcome, http_status, request_body,
                                              duration_ms, called_at)
    VALUES (gen_random_uuid(), md5('qa:carrier:f64')::uuid, 'TOKEN', 'OK', 200, '{"api_key":"k"}', 10, NOW()) $q$);
SELECT pg_temp.expect_ok('F92 a booking call that got no answer', $q$
    INSERT INTO fulfillment.carrier_api_call (id, carrier_id, booking_id, operation, outcome, request_body, error,
                                              duration_ms, called_at)
    VALUES (md5('qa:cac:f92')::uuid, md5('qa:carrier:f64')::uuid, md5('qa:cb:f88')::uuid, 'BOOK', 'NO_ANSWER', '{}',
            'SocketTimeoutException', 10000, NOW()) $q$);
SELECT pg_temp.expect_fail('F93 the call log rewritten (append-only)', $q$
    UPDATE fulfillment.carrier_api_call SET error = NULL WHERE id = md5('qa:cac:f92')::uuid $q$);
SELECT pg_temp.expect_ok('F94 a claim the carrier never confirmed, with why', $q$
    UPDATE fulfillment.carrier_booking SET status = 'UNCONFIRMED', last_error = 'no answer'
     WHERE id = md5('qa:cb:f88')::uuid $q$);
SELECT pg_temp.expect_fail('F95 a new claim while an unconfirmed one holds the load', $q$
    INSERT INTO fulfillment.carrier_booking (id, load_id, carrier_id, service_code, stops, status, requested_by,
                                             requested_at)
    VALUES (gen_random_uuid(), md5('qa:load:f72')::uuid, md5('qa:carrier:f64')::uuid, 'SGN-TRUCK-1000',
            '[{"seq":1,"shipmentCode":"SO-QA-1"}]', 'REQUESTED', md5('demo:user:approver')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('F96 the system dropping a carrier order names nobody', $q$
    INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, new_waybill_no, source, changed_by,
                                            changed_at, reason)
    VALUES (gen_random_uuid(), md5('qa:load:f72')::uuid, 'WB-QA-F72', NULL, 'API', NULL, NOW(),
            'A shipment was taken off') $q$);

-- 3-5: what carriers report through their webhooks, and the journey (V20261108001700).
SELECT pg_temp.expect_ok('F97 a webhook call kept as received', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result)
    VALUES (md5('qa:ce:f97')::uuid, md5('qa:carrier:f64')::uuid, 'QA-F64', NOW(), '{"loadCode":"LD-QA-F72"}', true,
            'RECEIVED') $q$);
SELECT pg_temp.expect_ok('F98 the call decided for review, matched to its load', $q$
    UPDATE fulfillment.carrier_event SET result = 'NEEDS_REVIEW', result_note = 'before the vehicle left',
           decided_at = NOW(), load_id = md5('qa:load:f72')::uuid, warehouse_id = md5('demo:wh:HCM')::uuid
     WHERE id = md5('qa:ce:f97')::uuid $q$);
SELECT pg_temp.expect_fail('F99 a decided call applied again', $q$
    UPDATE fulfillment.carrier_event SET result = 'APPLIED' WHERE id = md5('qa:ce:f97')::uuid $q$);
SELECT pg_temp.expect_fail('F100 a call with a bad credential matched to a load', $q$
    INSERT INTO fulfillment.carrier_event (id, carrier_id, carrier_code, received_at, payload, signature_valid, result,
                                           result_note, decided_at, load_id, warehouse_id)
    VALUES (gen_random_uuid(), md5('qa:carrier:f64')::uuid, 'QA-F64', NOW(), '{}', false, 'INVALID_SIGNATURE', 'x',
            NOW(), md5('qa:load:f72')::uuid, md5('demo:wh:HCM')::uuid) $q$);
SELECT pg_temp.expect_ok('F101 a vehicle picked up, as the carrier reported', $q$
    INSERT INTO fulfillment.tracking_milestone (id, load_id, milestone, carrier_reported_at, source, carrier_event_id,
                                                recorded_at)
    VALUES (gen_random_uuid(), md5('qa:load:f72')::uuid, 'PICKED_UP', NOW(), 'CARRIER_WEBHOOK',
            md5('qa:ce:f97')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('F102 the same milestone twice for one load', $q$
    INSERT INTO fulfillment.tracking_milestone (id, load_id, milestone, carrier_reported_at, source, carrier_event_id,
                                                recorded_at)
    VALUES (gen_random_uuid(), md5('qa:load:f72')::uuid, 'PICKED_UP', NOW(), 'CARRIER_WEBHOOK',
            md5('qa:ce:f97')::uuid, NOW()) $q$);

-- 3-6: a delivery corrected by hand, a proof of delivery (V20261108001800).
SELECT pg_temp.expect_ok('F103 a delivery corrected to a failure, with its reason and why', $q$
    INSERT INTO fulfillment.delivery_outcome_correction (id, shipment_id, from_status, to_status, failure_reason,
                                                         reason, corrected_by, corrected_at)
    SELECT md5('qa:doc:f103')::uuid, id, 'DELIVERED', 'DELIVERY_FAILED', 'CUSTOMER_ABSENT',
           'the customer says nothing arrived', md5('demo:user:approver')::uuid, NOW()
      FROM fulfillment.shipment LIMIT 1 $q$);
SELECT pg_temp.expect_fail('F104 a correction to a failure without its reason', $q$
    INSERT INTO fulfillment.delivery_outcome_correction (id, shipment_id, from_status, to_status, reason, corrected_by,
                                                         corrected_at)
    SELECT gen_random_uuid(), id, 'DELIVERED', 'DELIVERY_FAILED', 'x', md5('demo:user:approver')::uuid, NOW()
      FROM fulfillment.shipment LIMIT 1 $q$);
SELECT pg_temp.expect_fail('F105 a correction rewritten (append-only)', $q$
    UPDATE fulfillment.delivery_outcome_correction SET reason = 'later' WHERE id = md5('qa:doc:f103')::uuid $q$);
SELECT pg_temp.expect_fail('F106 a proof of delivery outside the documents', $q$
    INSERT INTO fulfillment.shipment_pod_file (id, shipment_id, storage_key, file_name, content_type, size_bytes,
                                               uploaded_by, uploaded_at)
    SELECT gen_random_uuid(), id, 'images/2026/11/09/qa-pod.jpg', 'pod.jpg', 'image/jpeg', 10,
           md5('demo:user:approver')::uuid, NOW()
      FROM fulfillment.shipment LIMIT 1 $q$);
ROLLBACK;
\echo 'PASS 09_outbound'
