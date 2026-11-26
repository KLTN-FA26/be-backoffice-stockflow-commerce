-- =============================================================================
-- Adversarial checks + happy paths: the shipping warehouse of an order of a contract and its stock held there by SKU
-- (V20261108002500, team plan 4B-7; kltn-docs 17 §4.2 steps 2-3, BR-22; 20 §5.6, BR-01, BR-17). Needs the demo seed
-- (LY-GIAY-DEMO-12OZ-XANH: 25 + 8 cartons of 1,000 cups AVAILABLE at HCM). Rolled back at the end.
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
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, cartons, units_per_carton,
                                             reserved_at, status, sku, warehouse_code)
    VALUES (gen_random_uuid(), md5('qa19:contract')::uuid, gen_random_uuid(), gen_random_uuid(), 10, 1000, NOW(),
            'HELD', 'LY-GIAY-DEMO-12OZ-XANH', 'NOWHERE') $q$);
-- A hold counts cartons of one count (ADR-0011, V20261231000500): HCM has 33 free cartons of 1,000 of the cup.
SELECT pg_temp.expect_true('SW5a the demo cup has 33 free cartons of 1,000 at HCM and none of another count',
    (SELECT count(*) FILTER (WHERE p.nominal_qty = 1000) = 33 AND count(*) FILTER (WHERE p.nominal_qty <> 1000) = 0
       FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
      WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.stock_status = 'AVAILABLE'
        AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation a WHERE a.stock_package_id = p.id AND a.status = 'HELD')
        AND split_part(p.location_code, '-', 1) = 'HCM'));
SELECT pg_temp.expect_fail_by('SW5 more held at HCM than its free cartons of that count (34 > 33)',
    'exceed its free available cartons of that count (33)', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, cartons, units_per_carton,
                                             reserved_at, status, sku, warehouse_code)
    VALUES (gen_random_uuid(), md5('qa19:contract')::uuid, gen_random_uuid(), gen_random_uuid(), 34, 1000, NOW(),
            'HELD', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM') $q$);
SELECT pg_temp.expect_fail_by('SW5b a carton of 500 held at HCM: 33,000 units stand free, none in a carton of 500',
    'carton(s) of 500) exceed its free available cartons of that count', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, cartons, units_per_carton,
                                             reserved_at, status, sku, warehouse_code)
    VALUES (gen_random_uuid(), md5('qa19:contract')::uuid, gen_random_uuid(), gen_random_uuid(), 1, 500, NOW(),
            'HELD', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM') $q$);
SELECT pg_temp.expect_fail_by('SW5c a carton of 1,000 held at DNI, where the cup has none: covered system-wide is not covered there',
    'exceed its free available cartons of that count there', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, cartons, units_per_carton,
                                             reserved_at, status, sku, warehouse_code)
    VALUES (gen_random_uuid(), md5('qa19:contract')::uuid, gen_random_uuid(), gen_random_uuid(), 1, 1000, NOW(),
            'HELD', 'LY-GIAY-DEMO-12OZ-XANH', 'DNI') $q$);
SELECT pg_temp.expect_ok('SW6 30 cartons of 1,000 held at HCM, with no deadline', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, cartons, units_per_carton,
                                             reserved_at, status, sku, warehouse_code)
    VALUES (md5('qa19:hold')::uuid, md5('qa19:contract')::uuid, md5('qa19:req')::uuid, md5('qa19:req')::uuid, 30, 1000,
            NOW(), 'HELD', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM') $q$);
SELECT pg_temp.expect_fail_by('SW7 another 4 cartons held at HCM: 34 > 33',
    'exceed its free available cartons of that count (33)', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, cartons, units_per_carton,
                                             reserved_at, status, sku, warehouse_code)
    VALUES (gen_random_uuid(), md5('qa19:contract')::uuid, gen_random_uuid(), gen_random_uuid(), 4, 1000, NOW(),
            'HELD', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM') $q$);
SELECT pg_temp.expect_fail_by('SW8 a hold at no warehouse beside it: 34 > 33 system-wide',
    'exceed its free available cartons of that count (33)', $q$
    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, cartons, units_per_carton,
                                             reserved_at, status, sku)
    VALUES (gen_random_uuid(), md5('qa19:checkout')::uuid, gen_random_uuid(), gen_random_uuid(), 4, 1000, NOW(),
            'HELD', 'LY-GIAY-DEMO-12OZ-XANH') $q$);
SELECT pg_temp.expect_fail_by('SW8b the hold grown past the free cartons of its count (update, not insert)',
    'exceed its free available cartons of that count', $q$
    UPDATE inventory.stock_reservation SET cartons = 34 WHERE id = md5('qa19:hold')::uuid $q$);
SELECT pg_temp.expect_fail_by('SW8c the hold turned into cartons of another count (update)',
    'carton(s) of 500) exceed its free available cartons of that count', $q$
    UPDATE inventory.stock_reservation SET units_per_carton = 500 WHERE id = md5('qa19:hold')::uuid $q$);
SELECT pg_temp.expect_fail('SW9 where it is held moved', $q$
    UPDATE inventory.stock_reservation SET warehouse_code = NULL WHERE id = md5('qa19:hold')::uuid $q$);
SELECT pg_temp.expect_fail_by('SW10 a hold on a carton naming a warehouse', 'ck_stock_reservation_warehouse', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, order_id, root_request_id, request_id, cartons,
                                             units_per_carton, reserved_at, status, sku, warehouse_code)
    SELECT gen_random_uuid(), p.id, md5('qa19:contract')::uuid, gen_random_uuid(),
           gen_random_uuid(), 1, p.nominal_qty, NOW(), 'HELD', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM'
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A02-1-A' AND p.stock_status = 'AVAILABLE'
     ORDER BY p.lpn LIMIT 1 $q$);
-- Stock is whole LPN cartons: an allocation names one carton (the demo's 25 cartons of 1,000 cups on HCM-A01-2-B),
-- and that live row is what says the carton is taken (V20261231000600).
SELECT pg_temp.expect_ok('SW11 allocated on a carton of HCM, where it is held (released to HCM)', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, order_id, root_request_id, request_id, cartons,
                                             units_per_carton, reserved_at, status, sku, allocated_from_id)
    SELECT md5('qa19:alloc')::uuid, c.id, md5('qa19:contract')::uuid, gen_random_uuid(),
           gen_random_uuid(), 1, c.nominal_qty, NOW(), 'HELD', 'LY-GIAY-DEMO-12OZ-XANH', md5('qa19:hold')::uuid
      FROM (SELECT p.id, p.nominal_qty FROM inventory.stock_package p
              JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
             WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' AND p.stock_status = 'AVAILABLE'
               AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation a WHERE a.stock_package_id = p.id AND a.status = 'HELD') ORDER BY p.lpn LIMIT 1) c $q$);

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
SELECT pg_temp.expect_true('SW15 Sales chooses; the Sales Manager and the warehouse manager read; the order coordinator is retired (5-7), nor does the customer',
    pg_temp.grants('SALES_STAFF', 'sales-order-shipping-warehouses:UPDATE')
    AND pg_temp.grants('SALES_MANAGER', 'sales-order-shipping-warehouses:READ')
    AND NOT pg_temp.grants('SALES_MANAGER', 'sales-order-shipping-warehouses:UPDATE')
    AND NOT EXISTS (SELECT 1 FROM identity.app_role WHERE code = 'ORDER_COORDINATOR')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'sales-order-shipping-warehouses:READ')
    AND NOT pg_temp.grants('CUSTOMER', 'sales-order-shipping-warehouses:READ'));

ROLLBACK;
