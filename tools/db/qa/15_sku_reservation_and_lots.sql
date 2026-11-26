-- =============================================================================
-- Adversarial checks: SKU reservations and allocations (SCRUM-152, V20261023000100), counted in cartons since
-- V20261231000500 (ADR-0011, step 3c), and lot identity, the ledger's lot and order snapshot (SCRUM-142/153,
-- V20261023000200). Needs the demo seed. Rolled back at the end.
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
    IF condition THEN
        RAISE NOTICE 'ok    %', label;
    ELSE
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
END $$;

-- Fixtures: an order of the first customer, and a sellable carton of the demo cup on a bin.
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, version, created_at)
SELECT md5('qa15:order')::uuid, 'SO-QA-15', c.id, gen_random_uuid(), 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, version, created_at)
SELECT md5('qa15:other')::uuid, 'SO-QA-15B', c.id, gen_random_uuid(), 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
-- One carton of 2, in a lot of its own (the allocation below takes it: an allocation is its carton, V20261231000600).
-- 01 BR-03: an item has ONE active carton. The demo seed's own is retired, so the fixture's unit below is the one.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id IN (SELECT id FROM inventory.inventory_items WHERE sku IN ('LY-GIAY-DEMO-12OZ-XANH'));
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa15:u')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA_QA15', 2);
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
VALUES (md5('qa15:lot')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'QA15-LOT', NOW() - interval '3 days', 'UNTRACED', NOW());
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status,
                                     stock_status, lot_id, received_at)
VALUES (md5('qa15:p')::uuid, 'QA-QA15-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa15:u')::uuid, 2, NOW(), 'HCM-B01-2-B', 'IN_STOCK',
        'AVAILABLE', md5('qa15:lot')::uuid, NOW() - interval '3 days');

\echo '--- SKU reservations (V20261023000100), in cartons of one count (V20261231000500, ADR-0011)'
-- The fixture's carton of 2 stands beside the demo's cartons of 1,000 of the same cup: two counts of one SKU.
SELECT pg_temp.expect_true('SR0 the cup has one free carton of 2 and many of 1,000',
    (SELECT count(*) FILTER (WHERE p.nominal_qty = 2) = 1 AND count(*) FILTER (WHERE p.nominal_qty = 1000) >= 20
       FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
      WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.stock_status = 'AVAILABLE'
        AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation a
                         WHERE a.stock_package_id = p.id AND a.status = 'HELD')));
SELECT pg_temp.expect_fail_by('SR0a a carton of 2 held at DNI: the one carton of 2 stands at HCM',
    'exceed its free available cartons of that count there', $q$
    INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons, units_per_carton,
        reserved_at, status, warehouse_code)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid, gen_random_uuid(), gen_random_uuid(), 1, 2,
            NOW(), 'HELD', 'DNI') $q$);
SELECT pg_temp.expect_ok('SR1 a reservation of a SKU, on no carton: one carton of 2', $q$
    INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons, units_per_carton,
        reserved_at, status)
    VALUES (md5('qa15:soft')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid, gen_random_uuid(), gen_random_uuid(), 1, 2,
            NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail_by('SR2 a reservation no stock covers', 'exceed its free available cartons of that count', $q$
    INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons, units_per_carton,
        reserved_at, status)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid, gen_random_uuid(), gen_random_uuid(), 100000,
            1000, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail_by('SR2a a second carton of 2: covered per count - thousands of units stand free, in cartons of 1,000',
    '(2 carton(s) of 2) exceed its free available cartons of that count (1)', $q$
    INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons, units_per_carton,
        reserved_at, status)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid, gen_random_uuid(), gen_random_uuid(), 1, 2,
            NOW(), 'HELD') $q$);
SELECT pg_temp.expect_ok('SR2b a carton of 1,000 beside it: another count is other stock, held on its own', $q$
    INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons, units_per_carton,
        reserved_at, status, warehouse_code)
    VALUES (md5('qa15:thousand')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid, gen_random_uuid(),
            gen_random_uuid(), 1, 1000, NOW(), 'HELD', 'HCM') $q$);
SELECT pg_temp.expect_fail_by('SR2c that hold turned into cartons of 2 (an update, not an insert)',
    'exceed its free available cartons of that count', $q$
    UPDATE inventory.stock_reservation SET units_per_carton = 2 WHERE id = md5('qa15:thousand')::uuid $q$);
SELECT pg_temp.expect_fail_by('SR2d that hold grown past the free cartons of 1,000', 'exceed its free available cartons of that count', $q$
    UPDATE inventory.stock_reservation SET cartons = 100000 WHERE id = md5('qa15:thousand')::uuid $q$);
SELECT pg_temp.expect_fail_by('SR2e a hold of no carton', 'ck_stock_reservation_cartons', $q$
    UPDATE inventory.stock_reservation SET cartons = 0 WHERE id = md5('qa15:thousand')::uuid $q$);
SELECT pg_temp.expect_fail_by('SR2f a hold of cartons of nothing', 'ck_stock_reservation_units_per_carton', $q$
    UPDATE inventory.stock_reservation SET units_per_carton = 0 WHERE id = md5('qa15:thousand')::uuid $q$);
SELECT pg_temp.expect_ok('SR2g that hold released again', $q$
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'MANUAL_OVERRIDE', closed_at = NOW()
     WHERE id = md5('qa15:thousand')::uuid $q$);
SELECT pg_temp.expect_fail('SR3 a SKU reservation that is no customer order''s', $q$
    INSERT INTO inventory.stock_reservation (id, sku, root_request_id, request_id, cartons, units_per_carton, reserved_at,
        status)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', gen_random_uuid(), gen_random_uuid(), 1, 2, NOW(),
            'HELD') $q$);
SELECT pg_temp.expect_fail('SR4 a SKU reservation consumed directly', $q$
    UPDATE inventory.stock_reservation SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa15:soft')::uuid $q$);
SELECT pg_temp.expect_fail('SR5 ALLOCATED on a carton', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, root_request_id, request_id, cartons,
        units_per_carton, reserved_at, status, closed_at)
    VALUES (gen_random_uuid(), md5('qa15:p')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid, gen_random_uuid(),
            gen_random_uuid(), 1, 2, NOW(), 'ALLOCATED', NOW()) $q$);
SELECT pg_temp.expect_fail('SR6 a hold on a carton naming another SKU', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, root_request_id, request_id, cartons,
        units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa15:p')::uuid, 'LY-GIAY-DEMO-12OZ-DO', md5('qa15:order')::uuid, gen_random_uuid(),
            gen_random_uuid(), 1, 2, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail_by('SR7 an allocation of two cartons: an allocation is one', 'ck_stock_reservation_kind', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa15:p')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid,
            md5('qa15:soft')::uuid, gen_random_uuid(), gen_random_uuid(), 2, 2, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail_by('SR7a an allocation whose count is not its carton''s (1,000 on a carton of 2)',
    'an allocation is its carton', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa15:p')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid,
            md5('qa15:soft')::uuid, gen_random_uuid(), gen_random_uuid(), 1, 1000, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail_by('SR7b an allocation of part of its carton (1 of 2)', 'an allocation is its carton', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa15:p')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid,
            md5('qa15:soft')::uuid, gen_random_uuid(), gen_random_uuid(), 1, 1, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail('SR8 an allocation for another order than its reservation', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa15:p')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:other')::uuid,
            md5('qa15:soft')::uuid, gen_random_uuid(), gen_random_uuid(), 1, 2, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_ok('SR9 an allocation of the reservation: its carton, with the order number snapshot', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (md5('qa15:alloc')::uuid, md5('qa15:p')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa15:order')::uuid,
            md5('qa15:soft')::uuid, md5('qa15:soft')::uuid, gen_random_uuid(), 1, 2, NOW(), 'HELD');
    UPDATE inventory.stock_reservation SET status = 'ALLOCATED', closed_at = NOW()
     WHERE id = md5('qa15:soft')::uuid $q$);
SELECT pg_temp.expect_true('SR10 the allocation names its order',
    (SELECT order_number = 'SO-QA-15' FROM inventory.stock_reservation WHERE id = md5('qa15:alloc')::uuid));
SELECT pg_temp.expect_fail_by('SR10a the allocation''s count changed under it', 'an allocation is its carton', $q$
    UPDATE inventory.stock_reservation SET units_per_carton = 1000 WHERE id = md5('qa15:alloc')::uuid $q$);

\echo '--- the unit columns are gone, and nothing live reads them (V20261231000500)'
SELECT pg_temp.expect_true('SR11 stock_reservation keeps no quantity and no deadline',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_reservation' AND column_name IN ('quantity', 'expires_at')));
SELECT pg_temp.expect_true('SR12 it keeps cartons and their count, both required',
    (SELECT count(*) = 2 FROM information_schema.columns WHERE table_schema = 'inventory'
        AND table_name = 'stock_reservation' AND column_name IN ('cartons', 'units_per_carton') AND is_nullable = 'NO'));
SELECT pg_temp.expect_true('SR13 the CHECKs and the index of the dropped columns went with them',
    NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname IN ('ck_stock_reservation_quantity',
                    'ck_stock_reservation_allocation', 'ck_stock_reservation_expired_had_deadline'))
    AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'ix_stock_reservation_expiry'));
-- Postgres does not track what a function body mentions: a function still reading a dropped column fails only when
-- it runs. Every function that names the table, and the two trigger functions that read NEW of it.
SELECT pg_temp.expect_true('SR14 no live function reads a reservation''s quantity or deadline',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                   AND (p.prosrc ~* 'stock_reservation'
                        OR p.proname IN ('check_sku_reservation_covered', 'check_allocation_whole_carton'))
                   AND (p.prosrc ~* 'expires_at' OR p.prosrc ~* '\m(r|a|x|h|o|parent|new|old)\.quantity\M'
                        OR p.prosrc ~* 'sum\(quantity\)')));
SELECT pg_temp.expect_true('SR15 no trigger of the table is conditioned on a dropped column',
    NOT EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = 'inventory.stock_reservation'::regclass
                   AND NOT t.tgisinternal AND pg_get_triggerdef(t.oid) ~* '(quantity|expires_at)'));
SELECT pg_temp.expect_true('SR16 no view reads the table',
    NOT EXISTS (SELECT 1 FROM pg_views WHERE definition ~* 'stock_reservation')
    AND NOT EXISTS (SELECT 1 FROM pg_matviews WHERE definition ~* 'stock_reservation'));

\echo '--- lots (V20261023000200)'
-- LT1 ("a layer written with a lot number got a lot row") went with tg_stock_item_lot: a carton has no lot number to
-- resolve a lot from, the application names the lot (V20261231000600).
SELECT pg_temp.expect_true('LT2 every carton that has been in stock has a lot',
    NOT EXISTS (SELECT 1 FROM inventory.stock_package WHERE lot_id IS NULL AND status NOT IN ('RECEIVING', 'VOID')));
SELECT pg_temp.expect_fail('LT3 a carton moved into a lot of another SKU', $q$
    UPDATE inventory.stock_package SET lot_id = (SELECT id FROM inventory.stock_lot WHERE sku <> 'LY-GIAY-DEMO-12OZ-XANH' LIMIT 1)
     WHERE id = md5('qa15:p')::uuid $q$);
SELECT pg_temp.expect_fail_by('LT3a a new carton in a lot of another SKU', 'but lot', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                         location_code, status, stock_status, lot_id)
    VALUES (gen_random_uuid(), 'QA-QA15-X', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('qa15:u')::uuid, 2, NOW(), 'HCM-B01-2-B', 'IN_STOCK', 'AVAILABLE',
            (SELECT id FROM inventory.stock_lot WHERE sku <> 'LY-GIAY-DEMO-12OZ-XANH' LIMIT 1)) $q$);
SELECT pg_temp.expect_fail('LT4 a ledger line naming a lot of another SKU', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, reference_type,
        reference_id, occurred_at, lot_id, stock_package_id)
    VALUES (gen_random_uuid(), 'PICK', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-B01-2-B', 'ORDER', md5('qa15:order')::uuid, NOW(),
            (SELECT id FROM inventory.stock_lot WHERE sku <> 'LY-GIAY-DEMO-12OZ-XANH' LIMIT 1), md5('qa15:p')::uuid) $q$);
SELECT pg_temp.expect_ok('LT5 a pick of the carton takes its lot and its order number', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code,
        reference_type, reference_id, occurred_at, order_id, stock_package_id)
    VALUES (md5('qa15:pick')::uuid, 'PICK', 'LY-GIAY-DEMO-12OZ-XANH', 'QA15-LOT', 'HCM-B01-2-B', 'ORDER',
            md5('qa15:order')::uuid, NOW(), md5('qa15:order')::uuid, md5('qa15:p')::uuid) $q$);
SELECT pg_temp.expect_true('LT6 ... with the carton''s lot and the order''s number',
    (SELECT m.lot_id = p.lot_id AND m.order_number = 'SO-QA-15'
       FROM inventory.stock_movement m, inventory.stock_package p
      WHERE m.id = md5('qa15:pick')::uuid AND p.id = md5('qa15:p')::uuid));
SELECT pg_temp.expect_fail('LT7 a lot of the removed origin GOODS_RECEIPT (ck_stock_lot_origin; QA 96 names the rule)', $q$
    INSERT INTO inventory.stock_lot (id, sku, origin_type, received_at) VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH',
        'GOODS_RECEIPT', NOW()) $q$);
SELECT pg_temp.expect_true('LT8 a lot counts as a use of its SKU (rename guard)',
    inventory.item_in_use((SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'LY-GIAY-DEMO-12OZ-XANH'));

ROLLBACK;
