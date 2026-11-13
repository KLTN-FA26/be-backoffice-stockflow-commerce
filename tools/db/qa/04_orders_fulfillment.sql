-- =============================================================================
-- Adversarial checks + happy paths: checkout vs the cross-schema keys, picking, packing,
-- shipment tracking, carts, returns, COD remittance (docs 07-09, 14, 15, 17).
-- Needs the demo seed. Rolled back at the end.
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

\echo '--- checkout: reservation written BEFORE the order row (OrderServiceImpl.placeOrder order)'
SELECT pg_temp.expect_ok('O1 reserve first, save the order after, same transaction (deferred keys)', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at, expires_at, status)
    VALUES (md5('qa:res:1')::uuid, '11111111-1111-4111-8111-111111111111', md5('qa:order:1')::uuid, gen_random_uuid(), gen_random_uuid(),
            2, NOW(), NOW() + INTERVAL '15 minutes', 'HELD');
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at)
    VALUES (md5('qa:order:1')::uuid, 'SO-QA-1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT',
            20000000, 'VND', NOW(), NOW());
    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
    VALUES (md5('qa:ol:1')::uuid, md5('qa:order:1')::uuid, 'SOFA-3S-GREY', 2, 10000000, 'VND');
    INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES (md5('qa:ol:1')::uuid, md5('qa:res:1')::uuid) $q$);
SELECT pg_temp.expect_fail('O2 a reservation whose order is never saved (fails at commit)', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at, expires_at, status)
    VALUES (gen_random_uuid(), '11111111-1111-4111-8111-111111111111', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
            1, NOW(), NOW() + INTERVAL '15 minutes', 'HELD') $q$);
SELECT pg_temp.expect_fail('O3 an order for a customer that does not exist', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at)
    VALUES (gen_random_uuid(), 'SO-QA-X', gen_random_uuid(), gen_random_uuid(), 'PENDING_PAYMENT', 0, 'VND', NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('O5 order line linked to a reservation that does not exist', $q$
    INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES (md5('qa:ol:1')::uuid, gen_random_uuid()) $q$);

\echo '--- picking (docs 07)'
INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
VALUES (md5('qa:pick:1')::uuid, md5('qa:order:1')::uuid, 'SO-QA-1', md5('demo:wh:HCM')::uuid, 'PICKING', NOW());
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at)
VALUES (md5('qa:order:2')::uuid, 'SO-QA-2', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT', 1, 'VND', NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa:ol:2')::uuid, md5('qa:order:2')::uuid, 'TABLE-OAK-160', 1, 1, 'VND');
SELECT pg_temp.expect_fail('O6 pick line for a line of another order', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, allocated_qty)
    VALUES (gen_random_uuid(), md5('qa:pick:1')::uuid, md5('qa:ol:2')::uuid, 'TABLE-OAK-160', 'HCM-B01-1-A', 1) $q$);
SELECT pg_temp.expect_fail('O7 pick line with another sku than its order line', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, allocated_qty)
    VALUES (gen_random_uuid(), md5('qa:pick:1')::uuid, md5('qa:ol:1')::uuid, 'TABLE-OAK-160', 'HCM-B01-1-A', 1) $q$);
-- Since V20261113000400 a pick line is one carton, named by its LPN (07 BR-04, BR-10): it is allocated from a carton's
-- row, and the checkout reservation of O1 (on an emptied loose row of the demo) is not a line's hold any more.
SELECT pg_temp.expect_ok('O8 pick line for a carton allocated from a bin, against its own hold', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at, expires_at, status)
    SELECT md5('qa:res:3')::uuid, s.id, md5('qa:order:1')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD'
      FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
     WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK' ORDER BY p.lpn LIMIT 1;
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, allocated_qty, lpn, whole_carton)
    SELECT md5('qa:pl:1')::uuid, md5('qa:pick:1')::uuid, md5('qa:ol:1')::uuid, md5('qa:res:3')::uuid, 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, p.lpn, TRUE
      FROM inventory.stock_reservation r JOIN inventory.stock_item s ON s.id = r.stock_item_id
      JOIN inventory.stock_package p ON p.id = s.stock_package_id WHERE r.id = md5('qa:res:3')::uuid $q$);
SELECT pg_temp.expect_fail('O9 picking more than allocated (BR-04)', $q$
    UPDATE fulfillment.pick_line SET picked_qty = 2 WHERE id = md5('qa:pl:1')::uuid $q$);
SELECT pg_temp.expect_fail('O10 PICKED with nothing picked: a carton is taken whole', $q$
    UPDATE fulfillment.pick_line SET status = 'PICKED', picked_qty = 0, picked_by = md5('demo:user:editor')::uuid, picked_at = NOW()
     WHERE id = md5('qa:pl:1')::uuid $q$);
-- consumed_at since V20261014001000: a picked line with a hold has deducted it (09_outbound F5).
SELECT pg_temp.expect_ok('O11 picked in full', $q$
    UPDATE fulfillment.pick_line SET status = 'PICKED', picked_qty = 1, picked_by = md5('demo:user:editor')::uuid, picked_at = NOW(),
           consumed_at = NOW()
     WHERE id = md5('qa:pl:1')::uuid $q$);

\echo '--- packing and shipping (docs 08, 09)'
INSERT INTO fulfillment.pack (id, pick_id, status, started_at, created_at) VALUES (md5('qa:pack:1')::uuid, md5('qa:pick:1')::uuid, 'IN_PROGRESS', NOW(), NOW());
INSERT INTO fulfillment.shipment (id, pack_id, order_id, carrier, status, code, warehouse_id, ready_at, created_at)
VALUES (md5('qa:ship:1')::uuid, md5('qa:pack:1')::uuid, md5('qa:order:1')::uuid, 'GHN', 'READY_TO_DISPATCH', 'SO-QA-1-S1', md5('demo:wh:HCM')::uuid, NOW(), NOW());
SELECT pg_temp.expect_fail('O12 a carton of the shipment that was never weighed and measured (labelled without them)', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn, checked_at, checked_by, labeled_at, labeled_by, label_version)
    SELECT gen_random_uuid(), md5('qa:pack:1')::uuid, 1, 'PKG-QA-1-1', 'LABELED', md5('qa:pl:1')::uuid, l.lpn, NOW(), md5('demo:user:editor')::uuid, NOW(), md5('demo:user:editor')::uuid, 1
      FROM fulfillment.pick_line l WHERE l.id = md5('qa:pl:1')::uuid $q$);
SELECT pg_temp.expect_ok('O13 a picked carton becomes a shipping carton row, labelled, in the shipment (4C-7: no parcels, no tracking numbers)', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT md5('qa:pkg:1')::uuid, md5('qa:pack:1')::uuid, 1, 'PKG-QA-1-1', 'TO_CHECK', md5('qa:pl:1')::uuid, l.lpn
      FROM fulfillment.pick_line l WHERE l.id = md5('qa:pl:1')::uuid;
    UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = md5('qa:order:1')::uuid;
    UPDATE fulfillment.package SET status = 'CHECKED', checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa:pkg:1')::uuid;
    UPDATE fulfillment.package SET status = 'LABELED', labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid, label_version = 1,
           weight_kg = 30, length_cm = 215, width_cm = 95, height_cm = 90, dim_weight_kg = 30, shipment_id = md5('qa:ship:1')::uuid
     WHERE id = md5('qa:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail('O14 a second carton row for the same pick line', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT gen_random_uuid(), md5('qa:pack:1')::uuid, 2, 'PKG-QA-1-2', 'TO_CHECK', md5('qa:pl:1')::uuid, l.lpn
      FROM fulfillment.pick_line l WHERE l.id = md5('qa:pl:1')::uuid $q$);
SELECT pg_temp.expect_fail('O15 a parcel that is no carton (no pick line, no LPN) is refused (4C-7)', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code) VALUES (gen_random_uuid(), md5('qa:pack:1')::uuid, 3, 'PKG-QA-1-3') $q$);
SELECT pg_temp.expect_ok('O16 tracking timeline from the carrier', $q$
    INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, carrier_event_id)
    VALUES (gen_random_uuid(), md5('qa:ship:1')::uuid, 'HANDED_OVER', NOW(), 'CARRIER_WEBHOOK', 'evt-1'),
           (gen_random_uuid(), md5('qa:ship:1')::uuid, 'IN_TRANSIT', NOW(), 'CARRIER_WEBHOOK', 'evt-2') $q$);
SELECT pg_temp.expect_fail('O17 the same webhook stored twice', $q$
    INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, carrier_event_id)
    VALUES (gen_random_uuid(), md5('qa:ship:1')::uuid, 'IN_TRANSIT', NOW(), 'CARRIER_WEBHOOK', 'evt-2') $q$);
SELECT pg_temp.expect_fail('O18 tracking history rewritten', $q$
    UPDATE fulfillment.shipment_event SET status = 'DELIVERED' WHERE carrier_event_id = 'evt-2' $q$);

-- (carts, O23-O29, went with customer self-ordering: V20261113000100, QA 60)

\echo '--- order timeline and returns (docs 17)'
SELECT pg_temp.expect_ok('O30 status history rows', $q$
    INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, actor_id, occurred_at)
    VALUES (md5('qa:osh:1')::uuid, md5('qa:order:1')::uuid, NULL, 'PENDING_PAYMENT', NULL, NOW()),
           (gen_random_uuid(), md5('qa:order:1')::uuid, 'PENDING_PAYMENT', 'PAID', md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('O31 history rewritten', $q$
    UPDATE ordering.order_status_history SET to_status = 'CANCELLED' WHERE id = md5('qa:osh:1')::uuid $q$);
SELECT pg_temp.expect_fail('O32 a "transition" to the same status', $q$
    INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, occurred_at)
    VALUES (gen_random_uuid(), md5('qa:order:1')::uuid, 'PAID', 'PAID', NOW()) $q$);
\echo '--- returns (SCRUM-243, V20261101020000)'
INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                                     requested_resolution, status, reason_code, requested_at)
VALUES (md5('qa:rma:1')::uuid, 'RMA-QA-1', md5('qa:order:1')::uuid, 'SO-QA-1', 'c0000000-0000-4000-8000-000000000001',
        gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REFUND', 'REQUESTED', 'DAMAGED_IN_TRANSIT', NOW());
SELECT pg_temp.expect_fail('O33 returning a line of another order', $q$
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity)
    VALUES (gen_random_uuid(), md5('qa:rma:1')::uuid, md5('qa:ol:2')::uuid, 'TABLE-OAK-160', FALSE, 1, 1) $q$);
SELECT pg_temp.expect_fail('O34 returning 3 of 2 ordered', $q$
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity)
    VALUES (gen_random_uuid(), md5('qa:rma:1')::uuid, md5('qa:ol:1')::uuid, 'SOFA-3S-GREY', FALSE, 10000000, 3) $q$);
SELECT pg_temp.expect_ok('O35 returning 1: approved by someone else, the goods are to come back (they always do, 17 BR-18)', $q$
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity)
    VALUES (md5('qa:rmal:1')::uuid, md5('qa:rma:1')::uuid, md5('qa:ol:1')::uuid, 'SOFA-3S-GREY', FALSE, 10000000, 1);
    UPDATE ordering.return_request_line SET approved_quantity = 1 WHERE id = md5('qa:rmal:1')::uuid;
    UPDATE ordering.return_request SET status = 'AWAITING_RETURN', decided_by = md5('demo:user:approver')::uuid, decided_at = NOW(),
           return_required = TRUE, return_deadline = NOW() + INTERVAL '14 days' WHERE id = md5('qa:rma:1')::uuid $q$);
SELECT pg_temp.expect_fail('O36 a second return of a line that already has an open one', $q$
    INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                                         requested_resolution, status, reason_code, requested_at)
    VALUES (md5('qa:rma:2')::uuid, 'RMA-QA-2', md5('qa:order:1')::uuid, 'SO-QA-1', 'c0000000-0000-4000-8000-000000000001',
            gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REFUND', 'REQUESTED', 'PRINT_DEFECT', NOW());
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity)
    VALUES (gen_random_uuid(), md5('qa:rma:2')::uuid, md5('qa:ol:1')::uuid, 'SOFA-3S-GREY', FALSE, 10000000, 1) $q$);
SELECT pg_temp.expect_fail('O37 RESOLVED straight from AWAITING_RETURN: the goods never came back, nothing was refunded', $q$
    UPDATE ordering.return_request SET status = 'RESOLVED' WHERE id = md5('qa:rma:1')::uuid $q$);
SELECT pg_temp.expect_fail('O38 RECEIVED with no return receipt behind it (4D-3: the goods come back on a receipt)', $q$
    UPDATE ordering.return_request_line SET received_quantity = 1 WHERE id = md5('qa:rmal:1')::uuid;
    UPDATE ordering.return_request SET status = 'RECEIVED', received_by = md5('demo:user:editor')::uuid, received_at = NOW(),
           receiving_location_code = 'HCM-QC01' WHERE id = md5('qa:rma:1')::uuid $q$);
SELECT pg_temp.expect_ok('O38e the customer withdraws the return before sending the goods: CANCELLED with who and why', $q$
    UPDATE ordering.return_request SET status = 'CANCELLED', cancelled_by = md5('demo:user:editor')::uuid,
           cancellation_reason = 'customer changed their mind', closed_at = NOW() WHERE id = md5('qa:rma:1')::uuid $q$);
-- Refused by the line trigger itself, by its own message: another refusal of the same insert would not prove the rule.
SELECT pg_temp.expect_ok('O38g2 goods printed to the customer''s design claimed for OTHER, a reason naming no defect (BR-RMA-002)', $q$
    DO $d$
    BEGIN
        INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel,
                                             requested_by, requested_resolution, status, reason_code, note, requested_at)
        VALUES (md5('qa:rma:other')::uuid, 'RMA-QA-OTHER', md5('qa:order:1')::uuid, 'SO-QA-1',
                'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid,
                'REFUND', 'REQUESTED', 'OTHER', 'x', NOW());
        INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price,
                                                  quantity)
        VALUES (gen_random_uuid(), md5('qa:rma:other')::uuid, md5('qa:ol:1')::uuid, 'SOFA-3S-GREY', TRUE, 10000000, 1);
        RAISE EXCEPTION 'the database accepted it';
    EXCEPTION WHEN check_violation THEN
        IF SQLERRM NOT LIKE 'printed goods come back for a named defect only%' THEN
            RAISE;
        END IF;
    END $d$ $q$);

\echo '--- returns: four eyes on approval (business review 2026-10-25); the QC-era inspection, replacement and credit rules went with 4D-3/4D-4, the new ones are in 53'
INSERT INTO ordering.return_request (id, rma_number, order_id, order_number, customer_id, request_id, channel, requested_by,
                                     requested_resolution, status, reason_code, requested_at)
VALUES (md5('qa:rma:3')::uuid, 'RMA-QA-3', md5('qa:order:2')::uuid, 'SO-QA-2', 'c0000000-0000-4000-8000-000000000001',
        gen_random_uuid(), 'BACK_OFFICE', md5('demo:user:editor')::uuid, 'REFUND', 'REQUESTED', 'PRINT_DEFECT', NOW());
INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, custom_printed, unit_price, quantity)
VALUES (md5('qa:rmal:3')::uuid, md5('qa:rma:3')::uuid, md5('qa:ol:2')::uuid, 'TABLE-OAK-160', FALSE, 1, 1);
SELECT pg_temp.expect_fail('O38h a claim Sales opened, approved by the same person', $q$
    UPDATE ordering.return_request SET status = 'AWAITING_RETURN', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), return_required = TRUE, return_deadline = NOW() + INTERVAL '14 days'
     WHERE id = md5('qa:rma:3')::uuid $q$);
SELECT pg_temp.expect_fail('O38i a late claim recorded on the portal', $q$
    UPDATE ordering.return_request SET channel = 'PORTAL', window_override_reason = 'x' WHERE id = md5('qa:rma:3')::uuid $q$);
SELECT pg_temp.expect_ok('O38j approved by someone else', $q$
    UPDATE ordering.return_request_line SET approved_quantity = 1 WHERE id = md5('qa:rmal:3')::uuid;
    UPDATE ordering.return_request SET status = 'AWAITING_RETURN', decided_by = md5('demo:user:approver')::uuid,
           decided_at = NOW(), return_required = TRUE, return_deadline = NOW() + INTERVAL '14 days'
     WHERE id = md5('qa:rma:3')::uuid $q$);

\echo '--- CONFIRMED, money received, cancellations (SCRUM-460, V20261013000300)'
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at)
VALUES (md5('qa:order:c1')::uuid, 'SO-QA-C1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT',
        1000000, 'VND', NOW(), NOW());
SELECT pg_temp.expect_fail('O39 an order CONFIRMED with no contract signed (ck_order_confirmed_basis, 4B-11)', $q$
    UPDATE ordering.customer_order SET status = 'CONFIRMED' WHERE id = md5('qa:order:c1')::uuid $q$);
SELECT pg_temp.expect_ok('O41 its contract signed, then CONFIRMED', $q$
    UPDATE ordering.customer_order SET contract_signed_at = NOW(), status = 'CONFIRMED'
     WHERE id = md5('qa:order:c1')::uuid $q$);
SELECT pg_temp.expect_fail('O42 the old PAID status', $q$
    UPDATE ordering.customer_order SET status = 'PAID' WHERE id = md5('qa:order:c1')::uuid $q$);
SELECT pg_temp.expect_fail('O43 CANCELLED without a reason code', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'x' WHERE id = md5('qa:order:c1')::uuid $q$);
SELECT pg_temp.expect_fail('O44 an unknown reason code', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'x', cancellation_reason_code = 'BORED'
     WHERE id = md5('qa:order:c1')::uuid $q$);
SELECT pg_temp.expect_ok('O46 cancelled, keeping part of what was paid', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'x', cancellation_reason_code = 'CUSTOMER_REQUEST',
           cancellation_retained_amount = 0 WHERE id = md5('qa:order:c1')::uuid $q$);
SELECT pg_temp.expect_ok('O48 a cancellation request', $q$
    INSERT INTO ordering.order_cancellation_request (id, order_id, reason_code, requested_at)
    VALUES (md5('qa:ocr:1')::uuid, md5('qa:order:1')::uuid, 'CUSTOMER_REQUEST', NOW()) $q$);
SELECT pg_temp.expect_fail('O49 a second pending request on the same order', $q$
    INSERT INTO ordering.order_cancellation_request (id, order_id, reason_code, requested_at)
    VALUES (gen_random_uuid(), md5('qa:order:1')::uuid, 'CUSTOMER_REQUEST', NOW()) $q$);
SELECT pg_temp.expect_fail('O50 a request for OTHER without a note', $q$
    INSERT INTO ordering.order_cancellation_request (id, order_id, reason_code, requested_at)
    VALUES (gen_random_uuid(), md5('qa:order:c1')::uuid, 'OTHER', NOW()) $q$);
SELECT pg_temp.expect_fail('O51 a rejection without a reason', $q$
    UPDATE ordering.order_cancellation_request SET status = 'REJECTED', decided_by = md5('demo:user:approver')::uuid,
           decided_at = NOW() WHERE id = md5('qa:ocr:1')::uuid $q$);
SELECT pg_temp.expect_fail('O52 a pick to put back that was not cancelled', $q$
    UPDATE fulfillment.pick SET needs_put_back = TRUE WHERE id = md5('qa:pick:1')::uuid $q$);
SELECT pg_temp.expect_ok('O53 a cancelled pick whose goods go back', $q$
    UPDATE fulfillment.pick SET status = 'CANCELLED', needs_put_back = TRUE WHERE id = md5('qa:pick:1')::uuid $q$);

\echo '--- order lifecycle: fulfilment states, hold, delivery outcome, split shipments (G3-2, V20261021000100)'
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at, contract_signed_at)
VALUES (md5('qa:order:l1')::uuid, 'SO-QA-L1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'CONFIRMED',
        1000000, 'VND', NOW(), NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa:order:l1:line')::uuid, md5('qa:order:l1')::uuid, 'TABLE-OAK-160', 10, 100000, 'VND');
SELECT pg_temp.expect_fail('O57 the retired IN_FULFILMENT', $q$
    UPDATE ordering.customer_order SET status = 'IN_FULFILMENT' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O58 CONFIRMED straight to PICKING (no release)', $q$
    UPDATE ordering.customer_order SET status = 'PICKING' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_ok('O59 released, READY_TO_FULFILL → PACKED (printed goods only, 17 §4.2.6)', $q$
    UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL', warehouse_id = md5('demo:wh:HCM')::uuid,
           shipping_warehouse_id = md5('demo:wh:HCM')::uuid, shipping_warehouse_chosen_at = NOW(),
           shipping_warehouse_chosen_by = md5('demo:user:editor')::uuid,
           released_at = NOW(), released_by = md5('demo:user:editor')::uuid WHERE id = md5('qa:order:l1')::uuid;
    UPDATE ordering.customer_order SET status = 'PACKED', packed_at = NOW() WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O60 PACKED → CANCELLED (a packed order goes through a return, 2026-10-17)', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST',
           cancellation_reason = 'x' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O61 ON_HOLD without a hold reason', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O62 an ADDRESS hold that does not say where it goes back', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'ADDRESS' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_ok('O62b an ADDRESS hold from PACKED', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'ADDRESS', status_before_hold = 'PACKED',
           hold_note = 'sai phường' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O63 leaving the hold somewhere else than where it was held from', $q$
    UPDATE ordering.customer_order SET status = 'PICKING', hold_reason = NULL, status_before_hold = NULL, hold_note = NULL
     WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_ok('O63b resolved back to PACKED, then shipped', $q$
    UPDATE ordering.customer_order SET status = 'PACKED', hold_reason = NULL, status_before_hold = NULL, hold_note = NULL
     WHERE id = md5('qa:order:l1')::uuid;
    UPDATE ordering.customer_order SET status = 'SHIPPED', shipped_at = NOW() WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O64 a failed delivery without a reason', $q$
    UPDATE ordering.customer_order SET status = 'DELIVERY_FAILED' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O64b SHIPPED → CANCELLED (BR-031: after shipping, a return)', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST',
           cancellation_reason = 'x' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O65 DELIVERED without delivered_at', $q$
    UPDATE ordering.customer_order SET status = 'DELIVERED' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_ok('O65b failed, then delivered on a retry', $q$
    UPDATE ordering.customer_order SET status = 'DELIVERY_FAILED', delivery_failure_reason = 'CUSTOMER_ABSENT'
     WHERE id = md5('qa:order:l1')::uuid;
    UPDATE ordering.customer_order SET status = 'DELIVERED', delivery_failure_reason = NULL, delivered_at = NOW()
     WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O66 COMPLETED without completed_at', $q$
    UPDATE ordering.customer_order SET status = 'COMPLETED' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O67 an unknown hold reason on order_hold', $q$
    INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, created_at)
    VALUES (gen_random_uuid(), md5('qa:order:l1')::uuid, 'FOO', NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('O67b an OTHER hold without a note', $q$
    INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, created_at)
    VALUES (gen_random_uuid(), md5('qa:order:l1')::uuid, 'OTHER', NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('O68 CLOSED without closed_at', $q$
    UPDATE ordering.customer_order SET status = 'COMPLETED', completed_at = NOW() WHERE id = md5('qa:order:l1')::uuid;
    UPDATE ordering.customer_order SET status = 'CLOSED' WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_ok('O69 the happy path to the end: COMPLETED → CLOSED', $q$
    UPDATE ordering.customer_order SET status = 'COMPLETED', completed_at = NOW() WHERE id = md5('qa:order:l1')::uuid;
    UPDATE ordering.customer_order SET status = 'CLOSED', closed_at = NOW() WHERE id = md5('qa:order:l1')::uuid $q$);
SELECT pg_temp.expect_fail('O70 CLOSED is terminal', $q$
    UPDATE ordering.customer_order SET status = 'COMPLETED', closed_at = NULL WHERE id = md5('qa:order:l1')::uuid $q$);
-- G4-2a: an order's shipment is fulfillment's shipment (fk_order_shipment_shipment, V20261021000500): one to refer to.
INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, ready_at, created_at)
VALUES (md5('qa:shipment:l1')::uuid, md5('qa:order:l1')::uuid, 'READY_TO_DISPATCH', 'SO-QA-L1-S1',
        md5('demo:wh:HCM')::uuid, NOW(), NOW());
SELECT pg_temp.expect_fail('O73 a failed shipment without a reason', $q$
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, outcome_at)
    VALUES (md5('qa:shipment:l1')::uuid, md5('qa:order:l1')::uuid, 'DELIVERY_FAILED', 100000, NOW(), NOW()) $q$);
SELECT pg_temp.expect_ok('O74 a shipment of 4 units handed over', $q$
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
    VALUES (md5('qa:shipment:l1')::uuid, md5('qa:order:l1')::uuid, 'HANDED_OVER', 400000, NOW());
    INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity)
    VALUES (md5('qa:shipment:l1')::uuid, md5('qa:order:l1:line')::uuid, 4) $q$);
SELECT pg_temp.expect_fail('O75 a shipment line of no units', $q$
    INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity)
    VALUES (md5('qa:shipment:l1')::uuid, md5('qa:order:l1:line')::uuid, 0) $q$);
SELECT pg_temp.expect_fail('O76 closing more units than the line has', $q$
    UPDATE ordering.order_line SET cancelled_quantity = 11 WHERE id = md5('qa:order:l1:line')::uuid $q$);
SELECT pg_temp.expect_fail('O79 the transition table and the trigger agree: an edge removed is refused', $q$
    UPDATE ordering.customer_order SET status = 'PENDING_PAYMENT' WHERE id = md5('qa:order:c1')::uuid $q$);


ROLLBACK;
\echo 'PASS 04_orders_fulfillment'
