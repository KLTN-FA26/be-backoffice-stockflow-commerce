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
SELECT pg_temp.expect_fail('O4 a payment for an order that does not exist', $q$
    INSERT INTO payment.payment (id, order_id, amount, method, status, created_at)
    VALUES (gen_random_uuid(), gen_random_uuid(), 1, 'COD', 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_fail('O5 order line linked to a reservation that does not exist', $q$
    INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES (md5('qa:ol:1')::uuid, gen_random_uuid()) $q$);

\echo '--- picking (docs 07)'
INSERT INTO fulfillment.pick (id, order_id, status, created_at) VALUES (md5('qa:pick:1')::uuid, md5('qa:order:1')::uuid, 'PICKING', NOW());
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
SELECT pg_temp.expect_ok('O8 pick line allocated from a bin, against the reservation', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, allocated_qty)
    VALUES (md5('qa:pl:1')::uuid, md5('qa:pick:1')::uuid, md5('qa:ol:1')::uuid, md5('qa:res:1')::uuid, 'SOFA-3S-GREY', 'HCM-A01-2-B', 2) $q$);
SELECT pg_temp.expect_fail('O9 picking more than allocated (BR-04)', $q$
    UPDATE fulfillment.pick_line SET picked_qty = 3 WHERE id = md5('qa:pl:1')::uuid $q$);
SELECT pg_temp.expect_fail('O10 PICKED with a partial quantity', $q$
    UPDATE fulfillment.pick_line SET status = 'PICKED', picked_qty = 1, picked_by = md5('demo:user:editor')::uuid, picked_at = NOW()
     WHERE id = md5('qa:pl:1')::uuid $q$);
SELECT pg_temp.expect_ok('O11 picked in full', $q$
    UPDATE fulfillment.pick_line SET status = 'PICKED', picked_qty = 2, picked_by = md5('demo:user:editor')::uuid, picked_at = NOW()
     WHERE id = md5('qa:pl:1')::uuid $q$);

\echo '--- packing and shipping (docs 08, 09)'
INSERT INTO fulfillment.pack (id, pick_id, status, created_at) VALUES (md5('qa:pack:1')::uuid, md5('qa:pick:1')::uuid, 'PACKING', NOW());
INSERT INTO fulfillment.shipment (id, pack_id, order_id, carrier, status, created_at)
VALUES (md5('qa:ship:1')::uuid, md5('qa:pack:1')::uuid, md5('qa:order:1')::uuid, 'GHN', 'PENDING', NOW());
SELECT pg_temp.expect_fail('O12 sealed parcel that was never weighed', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, status, sealed_at) VALUES (gen_random_uuid(), md5('qa:pack:1')::uuid, 1, 'SEALED', NOW()) $q$);
SELECT pg_temp.expect_ok('O13 two parcels (a sofa ships in two boxes), one shipment', $q$
    INSERT INTO fulfillment.package (id, pack_id, shipment_id, package_no, weight_kg, length_cm, width_cm, height_cm, tracking_number, status, sealed_at)
    VALUES (md5('qa:pkg:1')::uuid, md5('qa:pack:1')::uuid, md5('qa:ship:1')::uuid, 1, 30, 215, 95, 90, 'GHN001', 'SEALED', NOW()),
           (md5('qa:pkg:2')::uuid, md5('qa:pack:1')::uuid, md5('qa:ship:1')::uuid, 2, 25, 215, 95, 45, 'GHN002', 'SEALED', NOW());
    INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity)
    VALUES (gen_random_uuid(), md5('qa:pkg:1')::uuid, md5('qa:ol:1')::uuid, 2) $q$);
SELECT pg_temp.expect_fail('O14 a parcel holding a line of another order (BR-04)', $q$
    INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity) VALUES (gen_random_uuid(), md5('qa:pkg:2')::uuid, md5('qa:ol:2')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('O15 one tracking number on two live parcels', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, tracking_number) VALUES (gen_random_uuid(), md5('qa:pack:1')::uuid, 3, 'GHN001') $q$);
SELECT pg_temp.expect_ok('O16 tracking timeline from the carrier', $q$
    INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, carrier_event_id)
    VALUES (gen_random_uuid(), md5('qa:ship:1')::uuid, 'HANDED_OVER', NOW(), 'CARRIER_WEBHOOK', 'evt-1'),
           (gen_random_uuid(), md5('qa:ship:1')::uuid, 'IN_TRANSIT', NOW(), 'CARRIER_WEBHOOK', 'evt-2') $q$);
SELECT pg_temp.expect_fail('O17 the same webhook stored twice', $q$
    INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, carrier_event_id)
    VALUES (gen_random_uuid(), md5('qa:ship:1')::uuid, 'IN_TRANSIT', NOW(), 'CARRIER_WEBHOOK', 'evt-2') $q$);
SELECT pg_temp.expect_fail('O18 tracking history rewritten', $q$
    UPDATE fulfillment.shipment_event SET status = 'DELIVERED' WHERE carrier_event_id = 'evt-2' $q$);

\echo '--- COD remittance (docs 09 step 10, 15)'
INSERT INTO payment.payment (id, order_id, amount, method, status, created_at)
VALUES (md5('qa:pay:1')::uuid, md5('qa:order:1')::uuid, 20000000, 'COD', 'PENDING', NOW());
INSERT INTO payment.cod_remittance (id, remittance_number, carrier, period_from, period_to, expected_amount)
VALUES (md5('qa:cod:1')::uuid, 'COD-QA-1', 'GHN', CURRENT_DATE, CURRENT_DATE, 20000000);
SELECT pg_temp.expect_fail('O19 MATCHED line whose amounts do not match', $q$
    INSERT INTO payment.cod_remittance_line (id, remittance_id, shipment_id, payment_id, cod_amount, remitted_amount, fee_amount, match_status)
    VALUES (gen_random_uuid(), md5('qa:cod:1')::uuid, md5('qa:ship:1')::uuid, md5('qa:pay:1')::uuid, 20000000, 19000000, 0, 'MATCHED') $q$);
SELECT pg_temp.expect_ok('O20 remitted minus the carrier fee = MATCHED', $q$
    INSERT INTO payment.cod_remittance_line (id, remittance_id, shipment_id, payment_id, cod_amount, remitted_amount, fee_amount, match_status)
    VALUES (gen_random_uuid(), md5('qa:cod:1')::uuid, md5('qa:ship:1')::uuid, md5('qa:pay:1')::uuid, 20000000, 19800000, 200000, 'MATCHED') $q$);
SELECT pg_temp.expect_fail('O21 the same shipment in a second remittance', $q$
    INSERT INTO payment.cod_remittance (id, remittance_number, carrier, period_from, period_to) VALUES (md5('qa:cod:2')::uuid, 'COD-QA-2', 'GHN', CURRENT_DATE, CURRENT_DATE);
    INSERT INTO payment.cod_remittance_line (id, remittance_id, shipment_id, cod_amount, remitted_amount, match_status)
    VALUES (gen_random_uuid(), md5('qa:cod:2')::uuid, md5('qa:ship:1')::uuid, 20000000, 0, 'MISSING') $q$);
SELECT pg_temp.expect_fail('O22 RECONCILED without who/when', $q$
    UPDATE payment.cod_remittance SET status = 'RECONCILED', received_amount = 19800000, received_at = NOW() WHERE id = md5('qa:cod:1')::uuid $q$);

\echo '--- carts (docs 14)'
SELECT pg_temp.expect_ok('O23 customer cart with a line', $q$
    INSERT INTO ordering.cart (id, customer_id) VALUES (md5('qa:cart:1')::uuid, 'c0000000-0000-4000-8000-000000000001');
    INSERT INTO ordering.cart_line (id, cart_id, sku, quantity, unit_price_snapshot) VALUES (gen_random_uuid(), md5('qa:cart:1')::uuid, 'SOFA-3S-GREY', 1, 10000000) $q$);
SELECT pg_temp.expect_fail('O24 a second ACTIVE cart for the customer', $q$
    INSERT INTO ordering.cart (id, customer_id) VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001') $q$);
SELECT pg_temp.expect_fail('O25 a cart that belongs to nobody', $q$
    INSERT INTO ordering.cart (id) VALUES (gen_random_uuid()) $q$);
SELECT pg_temp.expect_fail('O26 the same sku twice as separate lines', $q$
    INSERT INTO ordering.cart_line (id, cart_id, sku, quantity) VALUES (gen_random_uuid(), md5('qa:cart:1')::uuid, 'SOFA-3S-GREY', 1) $q$);
SELECT pg_temp.expect_fail('O27 a sku that is not sold', $q$
    INSERT INTO ordering.cart_line (id, cart_id, sku, quantity) VALUES (gen_random_uuid(), md5('qa:cart:1')::uuid, 'NOPE', 1) $q$);
SELECT pg_temp.expect_fail('O28 CONVERTED without its order', $q$
    UPDATE ordering.cart SET status = 'CONVERTED' WHERE id = md5('qa:cart:1')::uuid $q$);
SELECT pg_temp.expect_ok('O29 guest cart merged into the account cart at login', $q$
    INSERT INTO ordering.cart (id, guest_token) VALUES (md5('qa:cart:guest')::uuid, 'tok-qa-1');
    UPDATE ordering.cart SET status = 'MERGED', merged_into_cart_id = md5('qa:cart:1')::uuid WHERE id = md5('qa:cart:guest')::uuid $q$);

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
INSERT INTO ordering.return_request (id, rma_number, order_id, customer_id, reason_code, requested_at)
VALUES (md5('qa:rma:1')::uuid, 'RMA-QA-1', md5('qa:order:1')::uuid, 'c0000000-0000-4000-8000-000000000001', 'DAMAGED_IN_TRANSIT', NOW());
SELECT pg_temp.expect_fail('O33 returning a line of another order', $q$
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, quantity) VALUES (gen_random_uuid(), md5('qa:rma:1')::uuid, md5('qa:ol:2')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('O34 returning 3 of 2 ordered', $q$
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, quantity) VALUES (gen_random_uuid(), md5('qa:rma:1')::uuid, md5('qa:ol:1')::uuid, 3) $q$);
SELECT pg_temp.expect_ok('O35 returning 1, approved, received, put away to quarantine', $q$
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, quantity, item_condition, disposition)
    VALUES (md5('qa:rmal:1')::uuid, md5('qa:rma:1')::uuid, md5('qa:ol:1')::uuid, 1, 'DAMAGED', 'QUARANTINE');
    UPDATE ordering.return_request SET status = 'RECEIVED', decided_by = md5('demo:user:approver')::uuid, decided_at = NOW(), received_at = NOW()
     WHERE id = md5('qa:rma:1')::uuid;
    INSERT INTO warehouse.putaway_task (id, return_request_line_id, sku, quantity, status, created_at)
    VALUES (gen_random_uuid(), md5('qa:rmal:1')::uuid, 'SOFA-3S-GREY', 1, 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_fail('O36 a second return pushing the line past what was ordered', $q$
    INSERT INTO ordering.return_request (id, rma_number, order_id, reason_code, requested_at)
    VALUES (md5('qa:rma:2')::uuid, 'RMA-QA-2', md5('qa:order:1')::uuid, 'CHANGED_MIND', NOW());
    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, quantity) VALUES (gen_random_uuid(), md5('qa:rma:2')::uuid, md5('qa:ol:1')::uuid, 2) $q$);
SELECT pg_temp.expect_fail('O37 REFUNDED without the refund', $q$
    UPDATE ordering.return_request SET status = 'REFUNDED' WHERE id = md5('qa:rma:1')::uuid $q$);
SELECT pg_temp.expect_fail('O38 disposition decided before inspection', $q$
    UPDATE ordering.return_request_line SET item_condition = NULL WHERE id = md5('qa:rmal:1')::uuid $q$);

ROLLBACK;
\echo 'PASS 04_orders_fulfillment'
