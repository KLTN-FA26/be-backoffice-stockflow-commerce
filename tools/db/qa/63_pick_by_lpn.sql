-- =============================================================================
-- Adversarial checks + happy paths: picking by LPN only, short picks with a reason, put-back as whole cartons
-- (V20261113000400, team plan 4C-4; kltn-docs 07 §4.2 step 5, §4.4 step 9, §4.5 step 14, BR-04, BR-05, BR-09, BR-10).
-- Needs the demo seed, whose sofas are cartons of one on HCM-A01-2-B (V20261111000110). Rolled back at the end.
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

-- The same, and it is the named rule that refuses it: a statement that breaks something else proves nothing.
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

-- ---------------------------------------------------------------------------------------------
-- Fixtures: an order of four sofas, its task, and one hold on each of six demo cartons (one unit each).
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at)
VALUES (md5('qa63:order')::uuid, 'SO-QA-63', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT', 1, 'VND', NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa63:ol')::uuid, md5('qa63:order')::uuid, 'SOFA-3S-GREY', 6, 1, 'VND');
INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
VALUES (md5('qa63:pick')::uuid, md5('qa63:order')::uuid, 'SO-QA-63', md5('demo:wh:HCM')::uuid, 'PICKING', NOW());

CREATE TEMP TABLE qa63_carton AS
SELECT row_number() OVER (ORDER BY p.lpn) AS n, s.id AS row_id, p.id AS package_id, p.lpn
  FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
 WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK' AND s.on_hand = 1
 ORDER BY p.lpn LIMIT 8;

INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at, expires_at, status)
SELECT md5('qa63:res:' || n)::uuid, row_id, md5('qa63:order')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD'
  FROM qa63_carton WHERE n <= 6;

-- A line of the task on carton n, with the columns that differ between the cases given by the caller.
CREATE FUNCTION pg_temp.line_sql(n INT, status TEXT, extra_cols TEXT DEFAULT '', extra_vals TEXT DEFAULT '')
RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                           allocated_qty, status, lpn, whole_carton %s)
        SELECT gen_random_uuid(), md5('qa63:pick')::uuid, md5('qa63:ol')::uuid, md5('qa63:res:' || c.n)::uuid,
               'SOFA-3S-GREY', 'HCM-A01-2-B', c.n, 1, %L, c.lpn, TRUE %s
          FROM qa63_carton c WHERE c.n = %s $f$, extra_cols, status, extra_vals, n)
$$;

\echo '--- a line is a carton, named by its LPN (07 BR-04, BR-10)'
SELECT pg_temp.expect_fail_by('PL1 a line with no LPN and no damage report to explain it', 'ck_pick_line_lpn', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, allocated_qty)
    VALUES (gen_random_uuid(), md5('qa63:pick')::uuid, md5('qa63:ol')::uuid, 'SOFA-3S-GREY', 'HCM-A01-2-B', 1) $q$);
SELECT pg_temp.expect_ok('PL2 a line naming the carton of its hold, one whole carton', pg_temp.line_sql(1, 'OPEN'));
SELECT pg_temp.expect_fail('PL3 a line naming another carton than its hold is in (tg_pick_line_carton)', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, allocated_qty, lpn)
    SELECT gen_random_uuid(), md5('qa63:pick')::uuid, md5('qa63:ol')::uuid, md5('qa63:res:2')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 1, c.lpn FROM qa63_carton c WHERE c.n = 3 $q$);
SELECT pg_temp.expect_fail('PL4 a line naming a carton but holding no stock (no hold: the hold is the carton)', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, allocated_qty, lpn)
    SELECT gen_random_uuid(), md5('qa63:pick')::uuid, md5('qa63:ol')::uuid, 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, c.lpn
      FROM qa63_carton c WHERE c.n = 3 $q$);

\echo '--- a short pick says why, and is short of the whole carton (07 §4.4 step 9, BR-09)'
SELECT pg_temp.expect_ok('PS1 a carton short because it is not at the bin: NOT_FOUND, short of all of it',
    pg_temp.line_sql(2, 'SHORT', ', short_qty, short_reason', ', 1, ''NOT_FOUND'''));
SELECT pg_temp.expect_ok('PS2 ... or because it is there but not fit to ship: DAMAGED',
    pg_temp.line_sql(3, 'SHORT', ', short_qty, short_reason', ', 1, ''DAMAGED'''));
SELECT pg_temp.expect_fail_by('PS3 a SHORT line with neither a reason nor a damage report', 'ck_pick_line_short_reported',
    pg_temp.line_sql(4, 'SHORT', ', short_qty', ', 1'));
SELECT pg_temp.expect_fail_by('PS4 a reason outside NOT_FOUND and DAMAGED', 'ck_pick_line_short_reason',
    pg_temp.line_sql(4, 'SHORT', ', short_qty, short_reason', ', 1, ''LOST_IN_TRANSIT'''));
SELECT pg_temp.expect_fail_by('PS5 a reason with a part of the carton short', 'ck_pick_line_short_reason', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                       allocated_qty, short_qty, short_reason, status, lpn, whole_carton)
    SELECT gen_random_uuid(), md5('qa63:pick')::uuid, md5('qa63:ol')::uuid, md5('qa63:res:4')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 4, 2, 1, 'NOT_FOUND', 'SHORT', c.lpn, TRUE FROM qa63_carton c WHERE c.n = 4 $q$);
SELECT pg_temp.expect_fail_by('PS6 a reason on a line that is not short at all', 'ck_pick_line_short_reason',
    pg_temp.line_sql(4, 'OPEN', ', short_qty, short_reason', ', 1, ''NOT_FOUND'''));
SELECT pg_temp.expect_fail_by('PS7 a reason on a line already picked', 'ck_pick_line_short_reason', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                       allocated_qty, picked_qty, short_qty, short_reason, status, lpn, whole_carton,
                                       picked_by, picked_at, consumed_at)
    SELECT gen_random_uuid(), md5('qa63:pick')::uuid, md5('qa63:ol')::uuid, md5('qa63:res:4')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 4, 1, 1, 1, 'NOT_FOUND', 'PICKED', c.lpn, TRUE, md5('demo:user:editor')::uuid, NOW(), NOW()
      FROM qa63_carton c WHERE c.n = 4 $q$);
SELECT pg_temp.expect_ok('PS8 a reallocated line (CANCELLED) keeps its shortfall and its reason, beside the line that replaced it',
    pg_temp.line_sql(2, 'CANCELLED', ', short_qty, short_reason', ', 1, ''NOT_FOUND'''));
SELECT pg_temp.expect_fail_by('PS9 a task is never SHORT: its lines are', 'ck_pick_status', $q$
    UPDATE fulfillment.pick SET status = 'SHORT' WHERE id = md5('qa63:pick')::uuid $q$);
SELECT pg_temp.expect_ok('PS10 a task goes PICKING, PICKED, CANCELLED as it did', $q$
    UPDATE fulfillment.pick SET status = 'PICKED', picked_at = NOW() WHERE id = md5('qa63:pick')::uuid;
    UPDATE fulfillment.pick SET status = 'CANCELLED', needs_put_back = TRUE WHERE id = md5('qa63:pick')::uuid;
    UPDATE fulfillment.pick SET status = 'PICKING', needs_put_back = FALSE WHERE id = md5('qa63:pick')::uuid $q$);

\echo '--- a put-back returns the whole carton the line took (07 BR-05, §4.5 step 14)'
SELECT pg_temp.expect_ok('PB1 a whole put-back: the line returns all it allocated', pg_temp.line_sql(5, 'PUT_BACK',
    ', picked_qty, picked_by, picked_at, consumed_at, put_back_qty, put_back_by, put_back_at, put_back_location_code',
    ', 1, md5(''demo:user:editor'')::uuid, NOW(), NOW(), 1, md5(''demo:user:editor'')::uuid, NOW(), ''HCM-A01-1-A'''));
SELECT pg_temp.expect_fail('PB2 a put-back of a part of what the line picked (the carton goes back whole; there is no damage at packing any more)', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                       allocated_qty, picked_qty, status, lpn, whole_carton,
                                       picked_by, picked_at, consumed_at, put_back_qty, put_back_by, put_back_at,
                                       put_back_location_code)
    SELECT gen_random_uuid(), md5('qa63:pick')::uuid, md5('qa63:ol')::uuid, md5('qa63:res:6')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 6, 3, 3, 'PUT_BACK', c.lpn, TRUE, md5('demo:user:editor')::uuid, NOW(), NOW(), 2,
           md5('demo:user:editor')::uuid, NOW(), 'HCM-A01-1-A'
      FROM qa63_carton c WHERE c.n = 6 $q$);
SELECT pg_temp.expect_fail_by('PB3 a put-back of nothing', 'ck_pick_line_put_back', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                       allocated_qty, picked_qty, status, lpn, whole_carton, picked_by, picked_at,
                                       consumed_at, put_back_by, put_back_at, put_back_location_code)
    SELECT gen_random_uuid(), md5('qa63:pick')::uuid, md5('qa63:ol')::uuid, md5('qa63:res:6')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 6, 1, 1, 'PUT_BACK', c.lpn, TRUE, md5('demo:user:editor')::uuid, NOW(), NOW(),
           md5('demo:user:editor')::uuid, NOW(), 'HCM-A01-1-A'
      FROM qa63_carton c WHERE c.n = 6 $q$);

\echo '--- no damage at packing any more (5-6, V20261114000400): a damaged carton is a short pick or removed whole'
SELECT pg_temp.expect_true('PD1 no packing-damage or damage-report column is left on a pick line', (
    SELECT count(*) FROM information_schema.columns WHERE table_schema = 'fulfillment' AND table_name = 'pick_line'
       AND column_name IN ('damaged_qty', 'damage_report_id', 'packing_damaged_qty')) = 0);

\echo '--- the ledger line of a put-back names the carton it goes back into'
SELECT pg_temp.expect_fail_by('LG1 a pick line put-back in the ledger with no carton', 'ck_stock_movement_put_back_carton', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, to_status, reference_type,
                                          reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-A01-1-A', 1, 'AVAILABLE', 'PICK_LINE',
            gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_ok('LG2 ... naming its carton, which is at the bin it goes back onto', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, to_status, reference_type,
                                          reference_id, occurred_at, to_stock_package_id)
    SELECT gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 'AVAILABLE', 'PICK_LINE', gen_random_uuid(),
           NOW(), c.package_id FROM qa63_carton c WHERE c.n = 7 $q$);
SELECT pg_temp.expect_ok('LG3 a ledger line of what a damage report once put back into quarantine from nowhere is still valid history', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, to_status, reference_type,
                                          reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-QC01', 1, 'DAMAGED', 'DAMAGE_REPORT',
            gen_random_uuid(), NOW()) $q$);

\echo '--- the carton comes back whole: CONSUMED to IN_STOCK, sealed, its row refilled (20 §4.6; 4C-2)'
-- Carton 8: picked (its hold consumed, the row emptied, the carton CONSUMED), then put back onto another bin.
SELECT pg_temp.expect_ok('CB1 a carton picked whole: hold consumed, row emptied, carton CONSUMED', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at, expires_at, status)
    SELECT md5('qa63:res:8')::uuid, c.row_id, md5('qa63:order')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD'
      FROM qa63_carton c WHERE c.n = 8;
    UPDATE inventory.stock_reservation SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa63:res:8')::uuid;
    UPDATE inventory.stock_item SET on_hand = 0, reserved = 0 WHERE id = (SELECT row_id FROM qa63_carton WHERE n = 8);
    UPDATE inventory.stock_package SET status = 'CONSUMED', unopened = FALSE, closed_at = NOW()
     WHERE id = (SELECT package_id FROM qa63_carton WHERE n = 8) $q$);
SELECT pg_temp.expect_fail('CB2 put back opened: a carton in stock is sealed', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL, location_code = 'HCM-A01-1-A'
     WHERE id = (SELECT package_id FROM qa63_carton WHERE n = 8) $q$);
SELECT pg_temp.expect_fail('CB3 put back sealed but its row left empty: the carton holds nothing', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', unopened = TRUE, closed_at = NULL, location_code = 'HCM-A01-1-A'
     WHERE id = (SELECT package_id FROM qa63_carton WHERE n = 8) $q$);
SELECT pg_temp.expect_fail('CB4 put back sealed, its row refilled with other than the carton''s count', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', unopened = TRUE, closed_at = NULL, location_code = 'HCM-A01-1-A'
     WHERE id = (SELECT package_id FROM qa63_carton WHERE n = 8);
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-A', on_hand = 3
     WHERE id = (SELECT row_id FROM qa63_carton WHERE n = 8) $q$);
SELECT pg_temp.expect_fail('CB5 put back with its row left at the old bin: carton and row are in one place', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', unopened = TRUE, closed_at = NULL, location_code = 'HCM-A01-1-A'
     WHERE id = (SELECT package_id FROM qa63_carton WHERE n = 8);
    UPDATE inventory.stock_item SET on_hand = 1 WHERE id = (SELECT row_id FROM qa63_carton WHERE n = 8) $q$);
SELECT pg_temp.expect_ok('CB6 put back whole onto another bin: IN_STOCK sealed, its row there with the carton''s count, one ledger line', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', unopened = TRUE, closed_at = NULL, location_code = 'HCM-A01-1-A'
     WHERE id = (SELECT package_id FROM qa63_carton WHERE n = 8);
    UPDATE inventory.stock_item SET location_code = 'HCM-A01-1-A', on_hand = 1
     WHERE id = (SELECT row_id FROM qa63_carton WHERE n = 8);
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, to_status, reference_type,
                                          reference_id, occurred_at, to_stock_package_id)
    SELECT gen_random_uuid(), 'PUT_BACK', 'SOFA-3S-GREY', 'HCM-A01-1-A', 1, 'AVAILABLE', 'PICK_LINE', md5('qa63:pb:8')::uuid,
           NOW(), c.package_id FROM qa63_carton c WHERE c.n = 8 $q$);
SELECT pg_temp.expect_true('CB7 the carton is IN_STOCK, sealed, at the bin, its row AVAILABLE holding its one unit',
    (SELECT p.status = 'IN_STOCK' AND p.unopened AND p.closed_at IS NULL AND p.location_code = 'HCM-A01-1-A'
            AND s.location_code = 'HCM-A01-1-A' AND s.on_hand = 1 AND s.status = 'AVAILABLE'
       FROM qa63_carton c JOIN inventory.stock_package p ON p.id = c.package_id
       JOIN inventory.stock_item s ON s.id = c.row_id WHERE c.n = 8));
SELECT pg_temp.expect_true('CB8 and the ledger names it once',
    (SELECT count(*) FROM inventory.stock_movement m JOIN qa63_carton c ON c.package_id = m.to_stock_package_id
      WHERE c.n = 8 AND m.movement_type = 'PUT_BACK' AND m.reference_type = 'PICK_LINE'
        AND m.reference_id = md5('qa63:pb:8')::uuid) = 1);
SELECT pg_temp.expect_fail('CB9 a second live line on a hold that already has one (uk_pick_line_reservation_live)', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                       allocated_qty, lpn, whole_carton)
    SELECT gen_random_uuid(), md5('qa63:pick')::uuid, md5('qa63:ol')::uuid, md5('qa63:res:5')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 5, 1, c.lpn, TRUE FROM qa63_carton c WHERE c.n = 5 $q$);

\echo '--- the rules are in the database, validated where nothing written before breaks them'
SELECT pg_temp.expect_true('CK1 the four rules exist',
    (SELECT count(*) FROM pg_constraint WHERE conname IN ('ck_pick_line_lpn', 'ck_pick_line_short_reported',
        'ck_pick_line_put_back_whole', 'ck_pick_line_short_reason', 'ck_stock_movement_put_back_carton')) = 5);
SELECT pg_temp.expect_true('CK2 a task cannot be SHORT, the other statuses are still allowed',
    (SELECT pg_get_constraintdef(oid) NOT LIKE '%SHORT%' AND pg_get_constraintdef(oid) LIKE '%PENDING%'
            AND pg_get_constraintdef(oid) LIKE '%PICKING%' AND pg_get_constraintdef(oid) LIKE '%PICKED%'
            AND pg_get_constraintdef(oid) LIKE '%CANCELLED%'
       FROM pg_constraint WHERE conname = 'ck_pick_status'));
SELECT pg_temp.expect_true('CK3 short_reason is a VARCHAR(16)',
    (SELECT data_type = 'character varying' AND character_maximum_length = 16 FROM information_schema.columns
      WHERE table_schema = 'fulfillment' AND table_name = 'pick_line' AND column_name = 'short_reason'));
-- NOT VALID is for history only: a database whose orders were all picked by carton - the demo seed - has every rule
-- validated, so every stored row is checked, not only the new ones.
SELECT pg_temp.expect_true('CK4 on the demo seed (no loose history) all four NOT VALID rules are validated',
    (SELECT bool_and(convalidated) AND count(*) = 4 FROM pg_constraint
      WHERE conname IN ('ck_pick_line_lpn', 'ck_pick_line_short_reported', 'ck_pick_line_put_back_whole',
                        'ck_stock_movement_put_back_carton')));

ROLLBACK;
\echo 'PASS 63_pick_by_lpn'
