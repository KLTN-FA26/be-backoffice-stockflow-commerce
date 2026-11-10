-- =============================================================================
-- Adversarial checks + happy paths: the shipping warehouse of an order of a contract and its stock held there by SKU
-- (V20261108002500, team plan 4B-7; kltn-docs 17 §4.2 steps 2-3, BR-22; 20 §5.6, BR-01, BR-17). Needs the demo seed
-- (SOFA-3S-GREY: 25 + 8 units AVAILABLE at HCM). Rolled back at the end.
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

-- Fixtures: a checkout order (not of a contract) and an order of a signed contract, CONFIRMED.
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, created_at)
SELECT md5('qa19:checkout')::uuid, 'SO-QA19-C', c.id, gen_random_uuid(), 'PENDING_PAYMENT', 1000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, created_at, contract_signed_at)
SELECT md5('qa19:contract')::uuid, 'SO-QA19-K', c.id, gen_random_uuid(), 'PENDING_CONTRACT', 1000, 'VND', NOW(),
       NOW(), NULL
  FROM customer.customer c ORDER BY c.id LIMIT 1;
-- Straight to CONFIRMED with its signature (the contract triggers are not what this script checks).
SET LOCAL session_replication_role = replica;
UPDATE ordering.customer_order SET status = 'CONFIRMED', contract_signed_at = NOW()
 WHERE id = md5('qa19:contract')::uuid;
SET LOCAL session_replication_role = origin;

\echo '--- the shipping warehouse of an order (17 §4.2 step 2, BR-22)'
SELECT pg_temp.expect_fail('SW1 a shipping warehouse for an order not of a contract', $q$
    UPDATE ordering.customer_order SET shipping_warehouse_id = w.id, shipping_warehouse_chosen_at = NOW(),
           shipping_warehouse_chosen_by = md5('demo:user:editor')::uuid
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' AND customer_order.id = md5('qa19:checkout')::uuid $q$);
SELECT pg_temp.expect_fail('SW2 a shipping warehouse nobody chose', $q$
    UPDATE ordering.customer_order SET shipping_warehouse_id = w.id
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' AND customer_order.id = md5('qa19:contract')::uuid $q$);
SELECT pg_temp.expect_ok('SW3 chosen by Sales for the confirmed order of a contract', $q$
    UPDATE ordering.customer_order SET shipping_warehouse_id = w.id, shipping_warehouse_chosen_at = NOW(),
           shipping_warehouse_chosen_by = md5('demo:user:editor')::uuid
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' AND customer_order.id = md5('qa19:contract')::uuid $q$);

\echo '--- its stock held at that warehouse, by SKU (20 §5.6, BR-01, BR-17)'
SELECT pg_temp.expect_fail('SW4 held at a warehouse that does not exist', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at, status,
                                             sku, warehouse_code)
    VALUES (gen_random_uuid(), md5('qa19:contract')::uuid, gen_random_uuid(), gen_random_uuid(), 10, NOW(), 'HELD',
            'SOFA-3S-GREY', 'NOWHERE') $q$);
SELECT pg_temp.expect_fail('SW5 more held at HCM than it has unheld (33 units)', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at, status,
                                             sku, warehouse_code)
    VALUES (gen_random_uuid(), md5('qa19:contract')::uuid, gen_random_uuid(), gen_random_uuid(), 34, NOW(), 'HELD',
            'SOFA-3S-GREY', 'HCM') $q$);
SELECT pg_temp.expect_ok('SW6 30 units held at HCM, with no deadline', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at, status,
                                             sku, warehouse_code)
    VALUES (md5('qa19:hold')::uuid, md5('qa19:contract')::uuid, md5('qa19:req')::uuid, md5('qa19:req')::uuid, 30,
            NOW(), 'HELD', 'SOFA-3S-GREY', 'HCM') $q$);
SELECT pg_temp.expect_fail('SW7 another 4 held at HCM: 34 > 33', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at, status,
                                             sku, warehouse_code)
    VALUES (gen_random_uuid(), md5('qa19:contract')::uuid, gen_random_uuid(), gen_random_uuid(), 4, NOW(), 'HELD',
            'SOFA-3S-GREY', 'HCM') $q$);
SELECT pg_temp.expect_fail('SW8 a checkout''s hold beside it: 34 > 33 system-wide', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at,
                                             expires_at, status, sku)
    VALUES (gen_random_uuid(), md5('qa19:checkout')::uuid, gen_random_uuid(), gen_random_uuid(), 4, NOW(),
            NOW() + INTERVAL '30 minutes', 'HELD', 'SOFA-3S-GREY') $q$);
SELECT pg_temp.expect_fail('SW9 where it is held moved', $q$
    UPDATE inventory.stock_reservation SET warehouse_code = NULL WHERE id = md5('qa19:hold')::uuid $q$);
SELECT pg_temp.expect_fail('SW10 a hold on a stock item naming a warehouse', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity,
                                             reserved_at, status, sku, warehouse_code)
    VALUES (gen_random_uuid(), '22222222-2222-4222-8222-222222222222', md5('qa19:contract')::uuid, gen_random_uuid(),
            gen_random_uuid(), 1, NOW(), 'HELD', 'SOFA-3S-GREY', 'HCM') $q$);
-- Stock is whole LPN cartons since 4C-1/4C-2: an allocation is one carton's row (the demo's 25 loose units became 25
-- cartons of one on HCM-A01-2-B), taken whole.
SELECT pg_temp.expect_ok('SW11 allocated on a carton of HCM, where it is held (released to HCM)', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity,
                                             reserved_at, status, sku, allocated_from_id)
    SELECT md5('qa19:alloc')::uuid, c.id, md5('qa19:contract')::uuid, gen_random_uuid(),
           gen_random_uuid(), 1, NOW(), 'HELD', 'SOFA-3S-GREY', md5('qa19:hold')::uuid
      FROM (SELECT s.id FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
             WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK'
               AND s.on_hand = 1 AND s.reserved = 0 ORDER BY p.lpn LIMIT 1) c;
    UPDATE inventory.stock_item SET reserved = reserved + 1
     WHERE id = (SELECT stock_item_id FROM inventory.stock_reservation WHERE id = md5('qa19:alloc')::uuid) $q$);

\echo '--- released from where it ships (BR-22)'
SELECT pg_temp.expect_fail('SW12 an order of a contract released with no shipping warehouse', $q$
    UPDATE ordering.customer_order SET shipping_warehouse_id = NULL, shipping_warehouse_chosen_at = NULL,
           shipping_warehouse_chosen_by = NULL, status = 'READY_TO_FULFILL', released_at = NOW(),
           released_by = md5('demo:user:editor')::uuid, warehouse_id = w.id
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' AND customer_order.id = md5('qa19:contract')::uuid $q$);
SELECT pg_temp.expect_fail('SW13 released from a warehouse it does not ship from', $q$
    UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL', released_at = NOW(),
           released_by = md5('demo:user:editor')::uuid, warehouse_id = gen_random_uuid()
     WHERE id = md5('qa19:contract')::uuid $q$);
SELECT pg_temp.expect_ok('SW14 released from its shipping warehouse', $q$
    UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL', released_at = NOW(),
           released_by = md5('demo:user:editor')::uuid, warehouse_id = shipping_warehouse_id
     WHERE id = md5('qa19:contract')::uuid $q$);

\echo '--- who chooses it'
CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;
SELECT pg_temp.expect_true('SW15 Sales chooses; the manager, coordinator and warehouse manager read; not the customer',
    pg_temp.grants('SALES_STAFF', 'sales-order-shipping-warehouses:UPDATE')
    AND pg_temp.grants('SALES_MANAGER', 'sales-order-shipping-warehouses:READ')
    AND NOT pg_temp.grants('SALES_MANAGER', 'sales-order-shipping-warehouses:UPDATE')
    AND pg_temp.grants('ORDER_COORDINATOR', 'sales-order-shipping-warehouses:READ')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'sales-order-shipping-warehouses:READ')
    AND NOT pg_temp.grants('CUSTOMER', 'sales-order-shipping-warehouses:READ'));

ROLLBACK;
