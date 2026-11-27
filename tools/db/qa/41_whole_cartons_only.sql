-- =============================================================================
-- Adversarial checks + happy paths: stock is kept, moved, received, put away and allocated in whole LPN cartons only
-- (V20261111000200, team plan 4C-2; kltn-docs 07 BR-03, BR-05; 05 BR-08; 11 BR-04, BR-05; 03 BR-12, BR-13; 20 §4.6,
-- §5.5, §5.6). Needs the demo seed, whose cups are whole cartons (V20261206000100). Since V20261231000600 (ADR-0011)
-- the carton is the stock: the checks of a carton against the units on its stock row (part of it taken, refilled with
-- another figure, left empty) cannot be written any more and are gone, each named where it stood. Rolled back at the end.
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

-- Refused by the named rule: a statement that breaks something else proves nothing.
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

-- Fixtures: a carton unit of 4 cups; one carton of it in stock on a bin, one being received; an order of the first
-- customer with a SKU reservation of 4 cups.
-- 01 BR-03: an item has ONE active carton. The demo seed's own is retired, so the fixture's unit below is the one.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND variant_id IN (SELECT variant_id FROM inventory.inventory_items WHERE sku IN ('LY-GIAY-DEMO-12OZ-XANH'));
INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor)
VALUES (md5('qa41:unit')::uuid, (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA41_CTN', 4);
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
VALUES (md5('qa41:lot')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', NOW() - interval '1 day', 'UNTRACED', NOW());
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                     location_code, status, stock_status, lot_id, received_at)
VALUES (md5('qa41:pk')::uuid, 'QA41-LPN-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa41:unit')::uuid, 4, NOW(),
        'HCM-A01-1-A', 'IN_STOCK', 'AVAILABLE', md5('qa41:lot')::uuid, NOW() - interval '1 day');
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
VALUES (md5('qa41:rcv')::uuid, 'QA41-LPN-2', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa41:unit')::uuid, 4,
        'HCM-RCV01');
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, version, created_at)
SELECT md5('qa41:order')::uuid, 'SO-QA-41', c.id, gen_random_uuid(), 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons, units_per_carton,
                                         reserved_at, status)
VALUES (md5('qa41:soft')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa41:order')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 1, 4,
        NOW(), 'HELD');

\echo '--- the carton lifecycle (20 §5.5): never opened in stock, counted at its standard count only'
SELECT pg_temp.expect_true('WC1 a carton in stock is never opened: the flag is gone (V20261113000300, team plan 4C-3)',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_package' AND column_name = 'unopened'));
-- WC2 (part of a carton in stock taken, 3 of 4 left) cannot be written: a carton holds no quantity.
SELECT pg_temp.expect_true('WC3 a carton cannot be counted at another figure than its standard count (03 BR-13): '
    'there is no figure to write, a count is the moment counted_at (V20261231000300)',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_package' AND column_name = 'received_qty'));
SELECT pg_temp.expect_ok('WC4 a carton counted in: it stands for its standard count', $q$
    UPDATE inventory.stock_package SET counted_at = NOW() WHERE id = md5('qa41:rcv')::uuid $q$);
-- WC5 (a counted draft in stock holding another figure), WC7 (back in stock with part of its count), WC10c (found with
-- part of its count) cannot be written. WC8 and WC10b (back in stock, or found, "with its row still empty") are now:
-- back in stock without a stock status.
SELECT pg_temp.expect_ok('WC6 the whole carton leaves stock: CONSUMED, with no stock status', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW()
     WHERE id = md5('qa41:pk')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC8 a consumed carton back in stock without saying as what', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa41:pk')::uuid $q$);
SELECT pg_temp.expect_ok('WC9 CONSUMED -> IN_STOCK: put back whole on another bin as AVAILABLE (20 §4.6 step 5)', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'AVAILABLE', closed_at = NULL,
           location_code = 'HCM-A01-1-B' WHERE id = md5('qa41:pk')::uuid $q$);
SELECT pg_temp.expect_ok('WC10 a carton lost and found again whole is in stock again, the same carton (4C-2 review: it was never allocatable)', $q$
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW()
     WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'AVAILABLE', closed_at = NULL
     WHERE id = md5('qa41:pk')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC10b a carton found again without saying as what', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa41:pk')::uuid $q$);
SELECT pg_temp.expect_true('WC10d through all of that the carton kept its lot and its receipt date',
    (SELECT lot_id = md5('qa41:lot')::uuid AND received_at < NOW() - interval '23 hours'
       FROM inventory.stock_package WHERE id = md5('qa41:pk')::uuid));
SELECT pg_temp.expect_fail('WC11 VOID back to receiving', $q$
    UPDATE inventory.stock_package SET status = 'VOID', closed_at = NOW() WHERE id = md5('qa41:rcv')::uuid;
    UPDATE inventory.stock_package SET status = 'RECEIVING', closed_at = NULL WHERE id = md5('qa41:rcv')::uuid $q$);

\echo '--- every unit received is in a carton (03 BR-12)'
-- One rule says it for every kind of line: stock_movement.stock_package_id is NOT NULL (V20261231000400, ADR-0011).
SELECT pg_temp.expect_fail_by('WC12 a receipt line received loose', 'null value in column "stock_package_id"', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, to_status,
                                          reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'RECEIPT', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-RCV01', 'INBOUND',
            'GOODS_RECEIPT_LINE', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_ok('WC13 a receipt line received into its carton', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, to_status,
                                          reference_type, reference_id, stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'RECEIPT', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-RCV01', 'INBOUND',
            'GOODS_RECEIPT_LINE', gen_random_uuid(), md5('qa41:rcv')::uuid, NOW()) $q$);
-- It was allowed while a return line could be counted in loose; returns come back as cartons, through a return receipt
-- (4D-2, QA 52), and a line of loose stock is no longer written by anything.
SELECT pg_temp.expect_fail_by('WC14 a customer return counted in loose is refused like any loose line', 'null value in column "stock_package_id"', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, to_status,
                                          reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'RETURN_IN', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-QC01', 'QUARANTINE',
            'RETURN_REQUEST_LINE', gen_random_uuid(), NOW()) $q$);

\echo '--- a person moves whole cartons, never one held for an order (11 BR-04, BR-05)'
SELECT pg_temp.expect_fail_by('WC15 a move by hand of loose units', 'null value in column "stock_package_id"', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-2-B', 'HCM-A01-1-A', 'MOVE_TASK',
            gen_random_uuid(), NOW()) $q$);
-- There is no "one side only" any more (V20261231000200): the line names its carton once. What is still refused is a
-- carton that stands at neither of the line's places (it is at HCM-A01-1-B).
SELECT pg_temp.expect_fail_by('WC16 a move by hand naming a carton that stands at neither of its places', 'names a stock package at', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, reference_type, reference_id, stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-2-B', 'HCM-A02-1-A', 'MOVE_TASK',
            gen_random_uuid(), md5('qa41:pk')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('WC17 a carton moved by hand, its line naming it', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-A' WHERE id = md5('qa41:pk')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, reference_type, reference_id, stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-1-B', 'HCM-A01-1-A', 'MOVE_TASK',
            gen_random_uuid(), md5('qa41:pk')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('WC18 the carton allocated whole to the order (07 BR-03)', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (md5('qa41:alloc')::uuid, md5('qa41:pk')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa41:order')::uuid,
            md5('qa41:soft')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 1, 4, NOW(), 'HELD');
    UPDATE inventory.stock_reservation SET status = 'ALLOCATED', closed_at = NOW() WHERE id = md5('qa41:soft')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC19 the held carton moved by hand', 'it is not moved by hand', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa41:pk')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, reference_type, reference_id, stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-1-A', 'HCM-A01-1-B', 'MOVE_TASK',
            gen_random_uuid(), md5('qa41:pk')::uuid, NOW()) $q$);
-- An allocation is its carton (ADR-0011): one carton, of the carton's own count, and the carton's only live one.
SELECT pg_temp.expect_fail_by('WC20 the allocation cut to part of its carton', 'an allocation is its carton', $q$
    UPDATE inventory.stock_reservation SET units_per_carton = 3 WHERE id = md5('qa41:alloc')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC20b the allocation made two cartons', 'ck_stock_reservation_kind', $q$
    UPDATE inventory.stock_reservation SET cartons = 2 WHERE id = md5('qa41:alloc')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC20c the allocation said to be of a carton of another count (1,000, as the demo''s are)',
    'an allocation is its carton', $q$
    UPDATE inventory.stock_reservation SET units_per_carton = 1000 WHERE id = md5('qa41:alloc')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC21 an allocation of part of a carton', 'an allocation is its carton', $q$
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'MANUAL_OVERRIDE', closed_at = NOW()
     WHERE id = md5('qa41:alloc')::uuid;
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa41:pk')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa41:order')::uuid,
            md5('qa41:soft')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 1, 2, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail_by('WC21b an allocation of two cartons on one carton', 'ck_stock_reservation_kind', $q$
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'MANUAL_OVERRIDE', closed_at = NOW()
     WHERE id = md5('qa41:alloc')::uuid;
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa41:pk')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa41:order')::uuid,
            md5('qa41:soft')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 2, 4, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail_by('WC21c a second live allocation of the carton', 'uk_stock_reservation_live_carton', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa41:pk')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa41:order')::uuid,
            md5('qa41:soft')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 1, 4, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail_by('WC21d the held carton leaving stock with its allocation still live', 'is held for order', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW()
     WHERE id = md5('qa41:pk')::uuid $q$);
SELECT pg_temp.expect_ok('WC22 the held carton picked: the allocation consumed, the carton CONSUMED whole', $q$
    UPDATE inventory.stock_reservation SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa41:alloc')::uuid;
    UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW()
     WHERE id = md5('qa41:pk')::uuid $q$);

\echo '--- a putaway puts a carton away (05 BR-08)'
SELECT pg_temp.expect_fail_by('WC23 a putaway of loose units', 'null value in column "stock_package_id"', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-RCV01', 'HCM-A01-1-A', 'INBOUND',
            'AVAILABLE', 'PUTAWAY_TASK', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail_by('WC24 a putaway in place of loose units', 'null value in column "stock_package_id"', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-1-A', 'HCM-A01-1-A',
            'INBOUND', 'AVAILABLE', 'PUTAWAY_TASK', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_true('WC25 a putaway task of a receipt line names its carton (checked for every new task)',
    EXISTS (SELECT 1 FROM pg_constraint
             WHERE conname = 'ck_putaway_task_receipt_carton' AND conrelid = 'warehouse.putaway_task'::regclass
               AND pg_get_constraintdef(oid) LIKE '%goods_receipt_line_id IS NULL%stock_package_id IS NOT NULL%'));

\echo '--- the history written before stays as it was'
-- The ledger's three checks (a receipt, a move by hand, a putaway names a carton) became one in V20261231000200, and
-- that one the column's NOT NULL in V20261231000400, when the loose lines of history went: every line is judged now.
SELECT pg_temp.expect_true('WC26 every ledger line names a carton (NOT NULL); the putaway task''s check is validated',
    (SELECT attnotnull FROM pg_attribute
      WHERE attrelid = 'inventory.stock_movement'::regclass AND attname = 'stock_package_id')
    AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_movement_names_carton')
    AND (SELECT convalidated FROM pg_constraint WHERE conname = 'ck_putaway_task_receipt_carton')
    AND NOT EXISTS (SELECT 1 FROM pg_constraint
                     WHERE conname IN ('ck_stock_movement_receipt_in_carton', 'ck_stock_movement_move_task_carton',
                                       'ck_stock_movement_putaway_carton')));
SELECT pg_temp.expect_true('WC27 no line of the demo seed names no carton',
    NOT EXISTS (SELECT 1 FROM inventory.stock_movement WHERE stock_package_id IS NULL));

ROLLBACK;
\echo 'PASS 41_whole_cartons_only'
