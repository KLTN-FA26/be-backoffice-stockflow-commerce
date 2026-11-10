-- =============================================================================
-- Adversarial checks + happy paths: stock is kept, moved, received, put away and allocated in whole LPN cartons only
-- (V20261111000200, team plan 4C-2; kltn-docs 07 BR-03, BR-05; 05 BR-08; 11 BR-04, BR-05; 03 BR-12, BR-13; 20 §4.6,
-- §5.5, §5.6). Needs the demo seed, whose sofas are cartons of one since V20261111000110. Rolled back at the end.
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

-- Fixtures: a carton unit of 4 sofas; one carton of it in stock on a bin, one being received; an order of the first
-- customer with a SKU reservation of 4 sofas.
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa41:unit')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'QA41_CTN', 4);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status)
VALUES (md5('qa41:pk')::uuid, 'QA41-LPN-1', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa41:unit')::uuid, 4, 4,
        'HCM-A01-1-A', 'IN_STOCK');
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                  received_at, stock_package_id)
VALUES (md5('qa41:pk:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'UNTRACED', 4, 0, 'AVAILABLE', NOW(),
        NOW() - interval '1 day', md5('qa41:pk')::uuid);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
VALUES (md5('qa41:rcv')::uuid, 'QA41-LPN-2', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa41:unit')::uuid, 4,
        'HCM-RCV01');
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, version, created_at)
SELECT md5('qa41:order')::uuid, 'SO-QA-41', c.id, gen_random_uuid(), 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, quantity, reserved_at, status)
VALUES (md5('qa41:soft')::uuid, 'SOFA-3S-GREY', md5('qa41:order')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 4,
        NOW(), 'HELD');

\echo '--- the carton lifecycle (20 §5.5): never opened in stock, counted at its standard count only'
SELECT pg_temp.expect_true('WC1 a carton in stock is never opened: the flag is gone (V20261113000300, team plan 4C-3)',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_package' AND column_name = 'unopened'));
SELECT pg_temp.expect_fail('WC2 part of a carton in stock is taken (3 of 4 left)', $q$
    UPDATE inventory.stock_item SET on_hand = 3 WHERE id = md5('qa41:pk:row')::uuid $q$);
SELECT pg_temp.expect_fail('WC3 a carton counted at another figure than its standard count (03 BR-13)', $q$
    UPDATE inventory.stock_package SET received_qty = 3 WHERE id = md5('qa41:rcv')::uuid $q$);
SELECT pg_temp.expect_ok('WC4 a carton counted at its standard count', $q$
    UPDATE inventory.stock_package SET received_qty = 4 WHERE id = md5('qa41:rcv')::uuid $q$);
SELECT pg_temp.expect_fail('WC5 a draft counted at another figure before 4C-2 goes into stock unchanged', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                         location_code)
    VALUES (md5('qa41:old')::uuid, 'QA41-LPN-3', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa41:unit')::uuid, 4, 3,
            'HCM-RCV01');
    UPDATE inventory.stock_package SET status = 'IN_STOCK' WHERE id = md5('qa41:old')::uuid;
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                      received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-RCV01', 'UNTRACED', 3, 0, 'INBOUND', NOW(), NOW(),
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
SELECT pg_temp.expect_fail('WC12 a receipt line received loose', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, quantity, to_status,
                                          reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-RCV01', 4, 'INBOUND',
            'GOODS_RECEIPT_LINE', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_ok('WC13 a receipt line received into its carton', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, quantity, to_status,
                                          reference_type, reference_id, to_stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-RCV01', 4, 'INBOUND',
            'GOODS_RECEIPT_LINE', gen_random_uuid(), md5('qa41:rcv')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('WC14 a customer return is still counted in loose (team plan 4D-2)', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, quantity, to_status,
                                          reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'RETURN_IN', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-QC01', 1, 'QUARANTINE',
            'RETURN_REQUEST_LINE', gen_random_uuid(), NOW()) $q$);

\echo '--- a person moves whole cartons, never one held for an order (11 BR-04, BR-05)'
SELECT pg_temp.expect_fail('WC15 a move by hand of loose units', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-A01-2-B', 'HCM-A01-1-A', 1, 'MOVE_TASK',
            gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('WC16 a move by hand naming the carton on one side only', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, reference_type, reference_id, from_stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-A01-1-B', 'HCM-A01-1-A', 4, 'MOVE_TASK',
            gen_random_uuid(), md5('qa41:pk')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('WC17 a carton moved by hand, named on both sides', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-A' WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-A' WHERE id = md5('qa41:pk:row')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, reference_type, reference_id, from_stock_package_id,
                                          to_stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-A01-1-B', 'HCM-A01-1-A', 4, 'MOVE_TASK',
            gen_random_uuid(), md5('qa41:pk')::uuid, md5('qa41:pk')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('WC18 the carton allocated whole to the order (07 BR-03)', $q$
    UPDATE inventory.stock_item SET reserved = 4 WHERE id = md5('qa41:pk:row')::uuid;
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, quantity, reserved_at, status)
    VALUES (md5('qa41:alloc')::uuid, md5('qa41:pk:row')::uuid, 'SOFA-3S-GREY', md5('qa41:order')::uuid,
            md5('qa41:soft')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 4, NOW(), 'HELD');
    UPDATE inventory.stock_reservation SET status = 'ALLOCATED', closed_at = NOW() WHERE id = md5('qa41:soft')::uuid $q$);
SELECT pg_temp.expect_fail('WC19 the held carton moved by hand', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa41:pk')::uuid;
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa41:pk:row')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, reference_type, reference_id, from_stock_package_id,
                                          to_stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-A01-1-A', 'HCM-A01-1-B', 4, 'MOVE_TASK',
            gen_random_uuid(), md5('qa41:pk')::uuid, md5('qa41:pk')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('WC20 the allocation cut to part of its carton', $q$
    UPDATE inventory.stock_item SET reserved = 3 WHERE id = md5('qa41:pk:row')::uuid;
    UPDATE inventory.stock_reservation SET quantity = 3 WHERE id = md5('qa41:alloc')::uuid $q$);
SELECT pg_temp.expect_fail('WC21 an allocation of part of a carton', $q$
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'MANUAL_OVERRIDE', closed_at = NOW()
     WHERE id = md5('qa41:alloc')::uuid;
    UPDATE inventory.stock_item SET reserved = 2 WHERE id = md5('qa41:pk:row')::uuid;
    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, allocated_from_id, root_request_id,
        request_id, quantity, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa41:pk:row')::uuid, 'SOFA-3S-GREY', md5('qa41:order')::uuid,
            md5('qa41:soft')::uuid, md5('qa41:soft')::uuid, gen_random_uuid(), 2, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_ok('WC22 the held carton picked: the allocation consumed, the carton CONSUMED whole', $q$
    UPDATE inventory.stock_reservation SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa41:alloc')::uuid;
    UPDATE inventory.stock_item SET on_hand = 0, reserved = 0 WHERE id = md5('qa41:pk:row')::uuid;
    UPDATE inventory.stock_package SET status = 'CONSUMED', closed_at = NOW()
     WHERE id = md5('qa41:pk')::uuid $q$);

\echo '--- a putaway puts a carton away (05 BR-08)'
SELECT pg_temp.expect_fail('WC23 a putaway of loose units', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-RCV01', 'HCM-A01-1-A', 1, 'INBOUND',
            'AVAILABLE', 'PUTAWAY_TASK', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('WC24 a putaway in place of loose units', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-A01-1-A', 'HCM-A01-1-A', 1,
            'INBOUND', 'AVAILABLE', 'PUTAWAY_TASK', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_true('WC25 a putaway task of a receipt line names its carton (checked for every new task)',
    EXISTS (SELECT 1 FROM pg_constraint
             WHERE conname = 'ck_putaway_task_receipt_carton' AND conrelid = 'warehouse.putaway_task'::regclass
               AND pg_get_constraintdef(oid) LIKE '%goods_receipt_line_id IS NULL%stock_package_id IS NOT NULL%'));

\echo '--- the history written before stays as it was'
SELECT pg_temp.expect_true('WC26 the new checks are NOT VALID: old loose lines and tasks are kept',
    (SELECT bool_and(NOT convalidated) FROM pg_constraint
      WHERE conname IN ('ck_stock_movement_receipt_in_carton', 'ck_stock_movement_move_task_carton',
                        'ck_stock_movement_putaway_carton', 'ck_putaway_task_receipt_carton')));

ROLLBACK;
\echo 'PASS 41_whole_cartons_only'
