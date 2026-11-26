-- =============================================================================
-- Adversarial checks + happy paths: stock is kept, moved, received, put away and allocated in whole LPN cartons only
-- (V20261111000200, team plan 4C-2; kltn-docs 07 BR-03, BR-05; 05 BR-08; 11 BR-04, BR-05; 03 BR-12, BR-13; 20 §4.6,
-- §5.5, §5.6). Needs the demo seed, whose cups are whole cartons (V20261206000100). Rolled back at the end.
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
 WHERE active AND inventory_item_id IN (SELECT id FROM inventory.inventory_items WHERE sku IN ('LY-GIAY-DEMO-12OZ-XANH'));
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa41:unit')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA41_CTN', 4);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                     location_code, status)
VALUES (md5('qa41:pk')::uuid, 'QA41-LPN-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa41:unit')::uuid, 4, NOW(),
        'HCM-A01-1-A', 'IN_STOCK');
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                  received_at, stock_package_id)
VALUES (md5('qa41:pk:row')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'UNTRACED', 4, 0, 'AVAILABLE', NOW(),
        NOW() - interval '1 day', md5('qa41:pk')::uuid);
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
SELECT pg_temp.expect_fail('WC2 part of a carton in stock is taken (3 of 4 left)', $q$
    UPDATE inventory.stock_item SET on_hand = 3 WHERE id = md5('qa41:pk:row')::uuid $q$);
SELECT pg_temp.expect_true('WC3 a carton cannot be counted at another figure than its standard count (03 BR-13): '
    'there is no figure to write, a count is the moment counted_at (V20261230000300)',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_package' AND column_name = 'received_qty'));
SELECT pg_temp.expect_ok('WC4 a carton counted in: it stands for its standard count', $q$
    UPDATE inventory.stock_package SET counted_at = NOW() WHERE id = md5('qa41:rcv')::uuid $q$);
SELECT pg_temp.expect_fail('WC5 a counted draft goes into stock holding another figure than its standard count', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                         location_code)
    VALUES (md5('qa41:old')::uuid, 'QA41-LPN-3', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa41:unit')::uuid, 4, NOW(),
            'HCM-RCV01');
    UPDATE inventory.stock_package SET status = 'IN_STOCK' WHERE id = md5('qa41:old')::uuid;
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                      received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-RCV01', 'UNTRACED', 3, 0, 'INBOUND', NOW(), NOW(),
            md5('qa41:old')::uuid) $q$);
SELECT pg_temp.expect_ok('WC6 the whole carton leaves stock: emptied and CONSUMED in one transaction', $q$
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa41:pk:row')::uuid;
    UPDATE inventory.stock_package SET status = 'CONSUMED', closed_at = NOW()
     WHERE id = md5('qa41:pk')::uuid $q$);
SELECT pg_temp.expect_fail('WC7 a consumed carton back in stock with only part of its count', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL
     WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_item SET on_hand = 3 WHERE id = md5('qa41:pk:row')::uuid $q$);
SELECT pg_temp.expect_fail('WC8 a consumed carton back in stock with its row still empty', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa41:pk')::uuid $q$);
SELECT pg_temp.expect_ok('WC9 CONSUMED -> IN_STOCK: put back whole on another bin (20 §4.6 step 5)', $q$
    SET LOCAL stockflow.same_receipt_merge = '855874c7-250e-165a-634e-378f66c747e3';
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL,
           location_code = 'HCM-A01-1-B' WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_item SET on_hand = 4, location_code = 'HCM-A01-1-B' WHERE id = md5('qa41:pk:row')::uuid $q$);
SELECT pg_temp.expect_ok('WC10 a carton lost and found again whole is in stock again, on its own row (4C-2 review: it was never allocatable)', $q$
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa41:pk:row')::uuid;
    UPDATE inventory.stock_package SET status = 'LOST', closed_at = NOW()
     WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL
     WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_item SET on_hand = 4 WHERE id = md5('qa41:pk:row')::uuid $q$);
SELECT pg_temp.expect_fail('WC10b a carton found again without its units back on its row', $q$
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa41:pk:row')::uuid;
    UPDATE inventory.stock_package SET status = 'LOST', closed_at = NOW() WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa41:pk')::uuid $q$);
SELECT pg_temp.expect_fail('WC10c a lost carton found with part of its count on its row', $q$
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa41:pk:row')::uuid;
    UPDATE inventory.stock_package SET status = 'LOST', closed_at = NOW() WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_item SET on_hand = 3 WHERE id = md5('qa41:pk:row')::uuid $q$);
SELECT pg_temp.expect_fail('WC11 VOID back to receiving', $q$
    UPDATE inventory.stock_package SET status = 'VOID', closed_at = NOW() WHERE id = md5('qa41:rcv')::uuid;
    UPDATE inventory.stock_package SET status = 'RECEIVING', closed_at = NULL WHERE id = md5('qa41:rcv')::uuid $q$);

\echo '--- every unit received is in a carton (03 BR-12)'
-- One rule says it for every kind of line: stock_movement.stock_package_id is NOT NULL (V20261230000400, ADR-0011).
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
-- There is no "one side only" any more (V20261230000200): the line names its carton once. What is still refused is a
-- carton that stands at neither of the line's places (it is at HCM-A01-1-B).
SELECT pg_temp.expect_fail_by('WC16 a move by hand naming a carton that stands at neither of its places', 'names a stock package at', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, reference_type, reference_id, stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-2-B', 'HCM-A02-1-A', 'MOVE_TASK',
            gen_random_uuid(), md5('qa41:pk')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('WC17 a carton moved by hand, its line naming it', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-A' WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-A' WHERE id = md5('qa41:pk:row')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, reference_type, reference_id, stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-1-B', 'HCM-A01-1-A', 'MOVE_TASK',
            gen_random_uuid(), md5('qa41:pk')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('WC18 the carton allocated whole to the order (07 BR-03)', $q$
    UPDATE inventory.stock_item SET reserved = 4 WHERE id = md5('qa41:pk:row')::uuid;
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (md5('qa41:alloc')::uuid, md5('qa41:pk:row')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa41:order')::uuid,
            md5('qa41:soft')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 1, 4, NOW(), 'HELD');
    UPDATE inventory.stock_reservation SET status = 'ALLOCATED', closed_at = NOW() WHERE id = md5('qa41:soft')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC19 the held carton moved by hand', 'it is not moved by hand', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa41:pk:row')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, reference_type, reference_id, stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-1-A', 'HCM-A01-1-B', 'MOVE_TASK',
            gen_random_uuid(), md5('qa41:pk')::uuid, NOW()) $q$);
-- An allocation is its carton (ADR-0011, V20261230000500): one carton, of the carton's own count.
SELECT pg_temp.expect_fail_by('WC20 the allocation cut to part of its carton', 'an allocation is its carton', $q$
    UPDATE inventory.stock_item SET reserved = 3 WHERE id = md5('qa41:pk:row')::uuid;
    UPDATE inventory.stock_reservation SET units_per_carton = 3 WHERE id = md5('qa41:alloc')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC20b the allocation made two cartons', 'ck_stock_reservation_one_carton', $q$
    UPDATE inventory.stock_reservation SET cartons = 2 WHERE id = md5('qa41:alloc')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC20c the allocation said to be of a carton of another count (1,000, as the demo''s are)',
    'an allocation is its carton', $q$
    UPDATE inventory.stock_reservation SET units_per_carton = 1000 WHERE id = md5('qa41:alloc')::uuid $q$);
SELECT pg_temp.expect_fail_by('WC21 an allocation of part of a carton', 'an allocation is its carton', $q$
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'MANUAL_OVERRIDE', closed_at = NOW()
     WHERE id = md5('qa41:alloc')::uuid;
    UPDATE inventory.stock_item SET reserved = 2 WHERE id = md5('qa41:pk:row')::uuid;
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa41:pk:row')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa41:order')::uuid,
            md5('qa41:soft')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 1, 2, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_fail_by('WC21b an allocation of two cartons on one carton''s row', 'ck_stock_reservation_one_carton', $q$
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'MANUAL_OVERRIDE', closed_at = NOW()
     WHERE id = md5('qa41:alloc')::uuid;
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, cartons, units_per_carton, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa41:pk:row')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa41:order')::uuid,
            md5('qa41:soft')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 2, 4, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_ok('WC22 the held carton picked: the allocation consumed, the carton CONSUMED whole', $q$
    UPDATE inventory.stock_reservation SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa41:alloc')::uuid;
    UPDATE inventory.stock_item SET on_hand = 0, reserved = 0 WHERE id = md5('qa41:pk:row')::uuid;
    UPDATE inventory.stock_package SET status = 'CONSUMED', closed_at = NOW()
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
-- The ledger's three checks (a receipt, a move by hand, a putaway names a carton) became one in V20261230000200, and
-- that one the column's NOT NULL in V20261230000400, when the loose lines of history went: every line is judged now.
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
