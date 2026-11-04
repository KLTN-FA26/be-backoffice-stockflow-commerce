-- =============================================================================
-- Adversarial checks: SKU reservations and allocations (SCRUM-152, V20261023000100) and lot identity, the ledger's
-- lot and order snapshot (SCRUM-142/153, V20261023000200). Needs the demo seed. Rolled back at the end.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition THEN
        RAISE NOTICE 'ok    %', label;
    ELSE
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
END $$;

-- Fixtures: an order of the first customer, and a sellable layer of the demo sofa on a bin.
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, version, created_at)
SELECT md5('qa15:order')::uuid, 'SO-QA-15', c.id, gen_random_uuid(), 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, version, created_at)
SELECT md5('qa15:other')::uuid, 'SO-QA-15B', c.id, gen_random_uuid(), 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at, received_at)
VALUES (md5('qa15:layer')::uuid, 'SOFA-3S-GREY', 'HCM-B01-2-B', 6, 0, 'AVAILABLE', 0, NOW(), NOW() - interval '3 days');

\echo '--- SKU reservations (V20261023000100)'
SELECT pg_temp.expect_ok('SR1 a checkout reservation of a SKU, on no stock row', $q$
    INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, quantity, reserved_at,
        expires_at, status)
    VALUES (md5('qa15:soft')::uuid, 'SOFA-3S-GREY', md5('qa15:order')::uuid, gen_random_uuid(), gen_random_uuid(), 2,
            NOW(), NOW() + interval '30 minutes', 'HELD') $q$);
SELECT pg_temp.expect_fail('SR2 a reservation no stock covers', $q$
    INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, quantity, reserved_at,
        expires_at, status)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', md5('qa15:order')::uuid, gen_random_uuid(), gen_random_uuid(), 100000,
            NOW(), NOW() + interval '30 minutes', 'HELD') $q$);
SELECT pg_temp.expect_fail('SR3 a SKU reservation that is no customer order''s', $q$
    INSERT INTO inventory.stock_reservation (id, sku, root_request_id, request_id, quantity, reserved_at, expires_at,
        status)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', gen_random_uuid(), gen_random_uuid(), 1, NOW(),
            NOW() + interval '30 minutes', 'HELD') $q$);
SELECT pg_temp.expect_fail('SR4 a SKU reservation consumed directly', $q$
    UPDATE inventory.stock_reservation SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa15:soft')::uuid $q$);
SELECT pg_temp.expect_fail('SR5 ALLOCATED on a stock row', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, root_request_id, request_id, quantity,
        reserved_at, status, closed_at)
    VALUES (gen_random_uuid(), md5('qa15:layer')::uuid, 'SOFA-3S-GREY', md5('qa15:order')::uuid, gen_random_uuid(),
            gen_random_uuid(), 1, NOW(), 'ALLOCATED', NOW()) $q$);
SELECT pg_temp.expect_fail('SR6 a hold on a stock row naming another SKU', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, root_request_id, request_id, quantity,
        reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa15:layer')::uuid, 'TABLE-OAK-160', md5('qa15:order')::uuid, gen_random_uuid(),
            gen_random_uuid(), 1, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail('SR7 an allocation that expires', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, quantity, reserved_at, expires_at, status)
    VALUES (gen_random_uuid(), md5('qa15:layer')::uuid, 'SOFA-3S-GREY', md5('qa15:order')::uuid,
            md5('qa15:soft')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(), NOW() + interval '1 hour', 'HELD') $q$);
SELECT pg_temp.expect_fail('SR8 an allocation for another order than its reservation', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, quantity, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa15:layer')::uuid, 'SOFA-3S-GREY', md5('qa15:other')::uuid,
            md5('qa15:soft')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_ok('SR9 an allocation of the reservation, pinned, with the order number snapshot', $q$
    UPDATE inventory.stock_item SET reserved = 2 WHERE id = md5('qa15:layer')::uuid;
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, quantity, reserved_at, status)
    VALUES (md5('qa15:alloc')::uuid, md5('qa15:layer')::uuid, 'SOFA-3S-GREY', md5('qa15:order')::uuid,
            md5('qa15:soft')::uuid, md5('qa15:soft')::uuid, gen_random_uuid(), 2, NOW(), 'HELD');
    UPDATE inventory.stock_reservation SET status = 'ALLOCATED', expires_at = NULL, closed_at = NOW()
     WHERE id = md5('qa15:soft')::uuid $q$);
SELECT pg_temp.expect_true('SR10 the allocation names its order',
    (SELECT order_number = 'SO-QA-15' FROM inventory.stock_reservation WHERE id = md5('qa15:alloc')::uuid));

\echo '--- lots (V20261023000200)'
SELECT pg_temp.expect_true('LT1 a layer written without a lot got one',
    (SELECT lot_id IS NOT NULL FROM inventory.stock_item WHERE id = md5('qa15:layer')::uuid));
SELECT pg_temp.expect_true('LT2 every layer has a lot', NOT EXISTS (SELECT 1 FROM inventory.stock_item WHERE lot_id IS NULL));
SELECT pg_temp.expect_fail('LT3 a layer moved into a lot of another SKU', $q$
    UPDATE inventory.stock_item SET lot_id = (SELECT id FROM inventory.stock_lot WHERE sku <> 'SOFA-3S-GREY' LIMIT 1)
     WHERE id = md5('qa15:layer')::uuid $q$);
SELECT pg_temp.expect_fail('LT4 a ledger line naming a lot of another SKU', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, quantity, reference_type,
        reference_id, occurred_at, lot_id)
    VALUES (gen_random_uuid(), 'PICK', 'SOFA-3S-GREY', 'HCM-B01-2-B', 1, 'ORDER', md5('qa15:order')::uuid, NOW(),
            (SELECT id FROM inventory.stock_lot WHERE sku <> 'SOFA-3S-GREY' LIMIT 1)) $q$);
SELECT pg_temp.expect_ok('LT5 a pick from the layer takes its lot and its order number', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, quantity, reference_type,
        reference_id, occurred_at, order_id)
    VALUES (md5('qa15:pick')::uuid, 'PICK', 'SOFA-3S-GREY', 'HCM-B01-2-B', 1, 'ORDER', md5('qa15:order')::uuid, NOW(),
            md5('qa15:order')::uuid) $q$);
SELECT pg_temp.expect_true('LT6 ... with the layer''s lot and the order''s number',
    (SELECT m.lot_id = s.lot_id AND m.order_number = 'SO-QA-15'
       FROM inventory.stock_movement m, inventory.stock_item s
      WHERE m.id = md5('qa15:pick')::uuid AND s.id = md5('qa15:layer')::uuid));
SELECT pg_temp.expect_fail('LT7 a receipt lot without its receipt', $q$
    INSERT INTO inventory.stock_lot (id, sku, origin_type, received_at) VALUES (gen_random_uuid(), 'SOFA-3S-GREY',
        'GOODS_RECEIPT', NOW()) $q$);
SELECT pg_temp.expect_true('LT8 a lot counts as a use of its SKU (rename guard)',
    inventory.item_in_use((SELECT id FROM inventory.inventory_items WHERE sku = 'SOFA-3S-GREY'), 'SOFA-3S-GREY'));

ROLLBACK;
