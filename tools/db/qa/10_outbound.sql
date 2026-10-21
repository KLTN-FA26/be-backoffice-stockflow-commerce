-- =============================================================================
-- Outbound fulfillment: the order number and warehouse a task carries (SCRUM-466,
-- V20261014000100; kltn-docs 07 §4, BR-SEC-002); pick confirmation, deduction and put-back
-- (SCRUM-267/274/275, V20261014001000; docs 07 step 6, BR-04, BR-05). Needs the demo seed.
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

ROLLBACK;
\echo 'PASS 09_outbound'
