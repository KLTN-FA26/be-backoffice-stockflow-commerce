-- =============================================================================
-- Adversarial checks + happy paths: the staging area (STAGING) and the pick destination
-- (V20261113000600, team plan 4C-5; kltn-docs 07 sections 4.1-4.3 and 4.5, BR-07, BR-08, BR-11; 06 BR-18, BR-22;
-- 20 section 4.9, BR-20; 11 BR-09).
-- Needs the demo seed: HCM-STG01 (STAGING), HCM-PACK01 (merged into DISPATCH) and the sofa cartons on HCM-A01-2-B
-- (V20261111000110). Rolled back at the end.
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
-- Fixtures: an order of sofas, a STAGING task and a DISPATCH task, and the cartons they hold.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at)
VALUES (md5('qa64:order')::uuid, 'SO-QA-64', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT', 1, 'VND', NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa64:ol')::uuid, md5('qa64:order')::uuid, 'SOFA-3S-GREY', 8, 1, 'VND');
-- A task is one per order: the DISPATCH task of the script belongs to a second order.
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at)
VALUES (md5('qa64:order2')::uuid, 'SO-QA-64B', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PENDING_PAYMENT', 1, 'VND', NOW(), NOW());

CREATE TEMP TABLE qa64_carton AS
SELECT row_number() OVER (ORDER BY p.lpn) AS n, s.id AS row_id, p.id AS package_id, p.lpn
  FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
 WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK' AND s.on_hand = 1
 ORDER BY p.lpn LIMIT 10;

\echo '--- the demo seed: PACKING is merged into DISPATCH, a staging area exists (06 BR-22)'
SELECT pg_temp.expect_true('ST1 the old packing area is a DISPATCH area, keeping its location',
    (SELECT a.type = 'DISPATCH' AND l.location_code = 'HCM-PACK01'
       FROM warehouse.area a JOIN warehouse.storage_location l ON l.id = a.location_id WHERE a.code = 'PACK01'));
SELECT pg_temp.expect_true('ST2 HCM-STG01 is a STAGING area, neither pickable nor a putaway target',
    (SELECT a.type = 'STAGING' AND NOT l.is_pickable AND NOT l.is_putaway_target
       FROM warehouse.area a JOIN warehouse.storage_location l ON l.id = a.location_id WHERE a.code = 'STG01'));
SELECT pg_temp.expect_true('ST3 no area is PACKING any more',
    NOT EXISTS (SELECT 1 FROM warehouse.area WHERE type = 'PACKING'));

-- Holds on the first six cartons, as an allocation leaves them (a hold takes the whole carton: one unit each).
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at, expires_at, status)
SELECT md5('qa64:res:' || n)::uuid, row_id, md5('qa64:order')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD'
  FROM qa64_carton WHERE n <= 6;

\echo '--- an area is a STAGING area, or not: the types and the location flags (06 BR-12, BR-18)'
-- A storage location must be owned by an area when the statement ends, so a location and its area go in together.
CREATE FUNCTION pg_temp.area_sql(code TEXT, type TEXT, pickable BOOLEAN, putaway BOOLEAN) RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
        VALUES (md5('qa64:loc:' || %1$L)::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-' || %1$L, 'NORMAL', %3$L, %4$L, 'ACTIVE');
        INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
        VALUES (md5('qa64:area:' || %1$L)::uuid, md5('demo:wh:HCM')::uuid, md5('qa64:loc:' || %1$L)::uuid, %1$L, %2$L, 'x',
                1, 1, 2, 2, 0, FALSE, 'ACTIVE') $f$, code, type, pickable, putaway)
$$;
SELECT pg_temp.expect_fail_by('SA1 a STAGING area on a pickable location', 'neither pickable nor a putaway target',
    pg_temp.area_sql('QAP', 'STAGING', TRUE, FALSE));
SELECT pg_temp.expect_fail_by('SA2 a STAGING area on a putaway-target location', 'neither pickable nor a putaway target',
    pg_temp.area_sql('QAT', 'STAGING', FALSE, TRUE));
SELECT pg_temp.expect_ok('SA3 a STAGING area on a location that is neither', pg_temp.area_sql('QAS', 'STAGING', FALSE, FALSE));
SELECT pg_temp.expect_fail_by('SA4 flipping is_pickable on the staging location later', 'is a staging area', $q$
    UPDATE warehouse.storage_location SET is_pickable = TRUE WHERE location_code = 'HCM-STG01' $q$);
SELECT pg_temp.expect_fail_by('SA5 flipping is_putaway_target on a staging location later', 'is a staging area', $q$
    UPDATE warehouse.storage_location SET is_putaway_target = TRUE WHERE location_code = 'HCM-QAS' $q$);
SELECT pg_temp.expect_ok('SA6 a DISPATCH area may be pickable (it is not STAGING)', pg_temp.area_sql('QAD', 'DISPATCH', TRUE, FALSE));
SELECT pg_temp.expect_fail_by('SA7 ... but it does not turn into a STAGING area while it is pickable', 'neither pickable nor a putaway target', $q$
    UPDATE warehouse.area SET type = 'STAGING' WHERE code = 'QAD' AND warehouse_id = md5('demo:wh:HCM')::uuid $q$);
UPDATE warehouse.storage_location SET is_pickable = FALSE WHERE location_code = 'HCM-QAD';
SELECT pg_temp.expect_fail_by('SA8 PACKING is no area type any more', 'ck_area_type', pg_temp.area_sql('QAK', 'PACKING', FALSE, FALSE));
SELECT pg_temp.expect_fail_by('SA9 an unknown type is refused as before', 'ck_area_type', $q$
    UPDATE warehouse.area SET type = 'WAITING' WHERE code = 'QAD' AND warehouse_id = md5('demo:wh:HCM')::uuid $q$);

\echo '--- an area holding stock does not turn into a STAGING area, nor out of one (20 BR-20)'
-- Carton 9 stands in the DISPATCH area, carton 10 in the staging area (moved as the rows and the carton move together).
SELECT pg_temp.expect_ok('SB1 stock in a DISPATCH area and in a STAGING area', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-QAD' WHERE id = (SELECT package_id FROM qa64_carton WHERE n = 9);
    UPDATE inventory.stock_item SET location_code = 'HCM-QAD' WHERE id = (SELECT row_id FROM qa64_carton WHERE n = 9);
    UPDATE inventory.stock_package SET location_code = 'HCM-STG01' WHERE id = (SELECT package_id FROM qa64_carton WHERE n = 10);
    UPDATE inventory.stock_item SET location_code = 'HCM-STG01' WHERE id = (SELECT row_id FROM qa64_carton WHERE n = 10) $q$);
SELECT pg_temp.expect_fail_by('SB2 the DISPATCH area holding stock turns into STAGING', 'holds stock', $q$
    UPDATE warehouse.area SET type = 'STAGING' WHERE code = 'QAD' AND warehouse_id = md5('demo:wh:HCM')::uuid $q$);
SELECT pg_temp.expect_fail_by('SB3 the STAGING area holding stock turns into DISPATCH', 'holds stock', $q$
    UPDATE warehouse.area SET type = 'DISPATCH' WHERE code = 'STG01' AND warehouse_id = md5('demo:wh:HCM')::uuid $q$);
SELECT pg_temp.expect_ok('SB4 an empty STAGING area becomes DISPATCH and STAGING again', $q$
    UPDATE warehouse.area SET type = 'DISPATCH' WHERE code = 'QAS' AND warehouse_id = md5('demo:wh:HCM')::uuid;
    UPDATE warehouse.area SET type = 'STAGING' WHERE code = 'QAS' AND warehouse_id = md5('demo:wh:HCM')::uuid $q$);
SELECT pg_temp.expect_ok('SB5 its other columns change freely while it holds stock', $q$
    UPDATE warehouse.area SET name = 'Khu chờ xuất (renamed)' WHERE code = 'STG01' AND warehouse_id = md5('demo:wh:HCM')::uuid $q$);
-- Carton 10 goes back to its bin, so the staging area is empty for the rest of the script.
UPDATE inventory.stock_package SET location_code = 'HCM-A01-2-B' WHERE id = (SELECT package_id FROM qa64_carton WHERE n = 10);
UPDATE inventory.stock_item SET location_code = 'HCM-A01-2-B' WHERE id = (SELECT row_id FROM qa64_carton WHERE n = 10);

\echo '--- stock enters and leaves a staging area only by picking (11 BR-09, 20 BR-20)'
CREATE FUNCTION pg_temp.move_sql(movement TEXT, from_loc TEXT, to_loc TEXT, ref_type TEXT, ref UUID, carton INT DEFAULT NULL,
                                 from_status TEXT DEFAULT NULL, to_status TEXT DEFAULT NULL) RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, quantity,
                                              from_status, to_status, reference_type, reference_id, occurred_at,
                                              from_stock_package_id, to_stock_package_id)
        SELECT gen_random_uuid(), %L, 'SOFA-3S-GREY', 'UNTRACED', %L, %L, 1, %L, %L, %L, %L::uuid, NOW(),
               CASE WHEN %L IS NOT NULL AND %L IS NOT NULL THEN c.package_id END,
               CASE WHEN %L IS NOT NULL AND %L IS NOT NULL THEN c.package_id END
          FROM (SELECT 1) one LEFT JOIN qa64_carton c ON c.n = %s $f$,
        movement, from_loc, to_loc, from_status, to_status, ref_type, ref,
        from_loc, carton, to_loc, carton, coalesce(carton, 0))
$$;
SELECT pg_temp.expect_fail_by('LG1 a return line moves a loose unit into the staging area', 'cannot touch staging area',
    pg_temp.move_sql('MOVE', 'HCM-A01-2-B', 'HCM-STG01', 'RETURN_REQUEST_LINE', gen_random_uuid()));
SELECT pg_temp.expect_fail_by('LG2 a person moves an unheld carton into the staging area', 'cannot touch staging area',
    pg_temp.move_sql('MOVE', 'HCM-A01-2-B', 'HCM-STG01', 'MOVE_TASK', gen_random_uuid(), 7));
SELECT pg_temp.expect_fail_by('LG3 a person moves a carton out of the staging area', 'cannot touch staging area',
    pg_temp.move_sql('MOVE', 'HCM-STG01', 'HCM-A01-2-B', 'MOVE_TASK', gen_random_uuid(), 7));
SELECT pg_temp.expect_fail_by('LG4 a putaway into the staging area', 'cannot touch staging area',
    pg_temp.move_sql('PUTAWAY', 'HCM-RCV01', 'HCM-STG01', 'PUTAWAY_TASK', gen_random_uuid()));
SELECT pg_temp.expect_fail_by('LG5 a status change on stock standing in the staging area', 'cannot touch staging area',
    pg_temp.move_sql('STATUS_CHANGE', 'HCM-STG01', 'HCM-STG01', 'STOCK_PACKAGE', gen_random_uuid(), NULL, 'AVAILABLE', 'QUARANTINE'));
SELECT pg_temp.expect_fail_by('LG6 a receipt straight into the staging area', 'cannot touch staging area',
    pg_temp.move_sql('RECEIPT', NULL, 'HCM-STG01', 'GOODS_RECEIPT_LINE', gen_random_uuid()));
SELECT pg_temp.expect_ok('LG7 a MOVE between two ordinary bins is what it was',
    pg_temp.move_sql('MOVE', 'HCM-A01-2-B', 'HCM-A01-1-A', 'RETURN_REQUEST_LINE', gen_random_uuid()));
-- A pick line stages its carton out of its bin, and puts it back out of the staging area.
SELECT pg_temp.expect_ok('LG8 a pick line stages a carton: a MOVE naming the line, bin to staging',
    pg_temp.move_sql('MOVE', 'HCM-A01-2-B', 'HCM-STG01', 'PICK_LINE', md5('qa64:line:A')::uuid, 7));
SELECT pg_temp.expect_fail_by('LG9 the same line stages out of the same place again', 'uk_stock_movement_pick_line_move',
    pg_temp.move_sql('MOVE', 'HCM-A01-2-B', 'HCM-STG01', 'PICK_LINE', md5('qa64:line:A')::uuid, 7));
-- Cartons 7 and 8 stand in the staging area from here (a ledger line names the carton where it leaves from).
UPDATE inventory.stock_package SET location_code = 'HCM-STG01' WHERE id IN (SELECT package_id FROM qa64_carton WHERE n IN (7, 8));
UPDATE inventory.stock_item SET location_code = 'HCM-STG01' WHERE id IN (SELECT row_id FROM qa64_carton WHERE n IN (7, 8));
SELECT pg_temp.expect_ok('LG10 ... its order cancelled, it puts the carton back: a second MOVE, out of the staging area',
    pg_temp.move_sql('MOVE', 'HCM-STG01', 'HCM-A01-1-A', 'PICK_LINE', md5('qa64:line:A')::uuid, 7));
SELECT pg_temp.expect_fail_by('LG11 a third MOVE of the line out of the staging area', 'uk_stock_movement_pick_line_move',
    pg_temp.move_sql('MOVE', 'HCM-STG01', 'HCM-A01-2-B', 'PICK_LINE', md5('qa64:line:A')::uuid, 7));
SELECT pg_temp.expect_ok('LG12 a task moved to dispatch deducts from the staging area: a PICK out of it',
    pg_temp.move_sql('PICK', 'HCM-STG01', NULL, 'PICK_LINE', md5('qa64:line:B')::uuid, 8, 'AVAILABLE'));
SELECT pg_temp.expect_fail_by('LG13 a second PICK of the line', 'uk_stock_movement_pick_line',
    pg_temp.move_sql('PICK', 'HCM-STG01', NULL, 'PICK_LINE', md5('qa64:line:B')::uuid, 8, 'AVAILABLE'));
UPDATE inventory.stock_package SET location_code = 'HCM-A01-2-B' WHERE id IN (SELECT package_id FROM qa64_carton WHERE n IN (7, 8));
UPDATE inventory.stock_item SET location_code = 'HCM-A01-2-B' WHERE id IN (SELECT row_id FROM qa64_carton WHERE n IN (7, 8));
SELECT pg_temp.expect_ok('LG14 a count corrects a staging area (20 section 4.9 step 4): COUNT_CORRECTION out of it',
    pg_temp.move_sql('COUNT_CORRECTION', 'HCM-STG01', NULL, 'CYCLE_COUNT_LINE', gen_random_uuid(), NULL, 'AVAILABLE'));
SELECT pg_temp.expect_ok('LG15 ... and an approved adjustment writes it down: ADJUSTMENT out of it',
    pg_temp.move_sql('ADJUSTMENT', 'HCM-STG01', NULL, 'STOCK_ADJUSTMENT', gen_random_uuid(), NULL, 'AVAILABLE'));
-- Review L5: never up - found units there would be nobody's, out of ATP, unpickable and unmovable.
SELECT pg_temp.expect_fail_by('LG16 an ADJUSTMENT into a staging area (a surplus)', 'cannot add to staging area',
    pg_temp.move_sql('ADJUSTMENT', NULL, 'HCM-STG01', 'STOCK_ADJUSTMENT', gen_random_uuid(), NULL, NULL, 'AVAILABLE'));
SELECT pg_temp.expect_fail_by('LG17 a COUNT_CORRECTION into a staging area (a surplus)', 'cannot add to staging area',
    pg_temp.move_sql('COUNT_CORRECTION', NULL, 'HCM-STG01', 'CYCLE_COUNT_LINE', gen_random_uuid(), NULL, NULL, 'AVAILABLE'));

\echo '--- the task: a destination, a staging area for it, STAGED (07 BR-08)'
INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, created_at)
VALUES (md5('qa64:wh')::uuid, 'QA other warehouse', 'ACTIVE', 'QB', 'x', 'M', 20, 20, NOW());
INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
VALUES (md5('qa64:loc:qb')::uuid, md5('qa64:wh')::uuid, 'AREA', 'QB-STG01', 'NORMAL', FALSE, FALSE, 'ACTIVE');
INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
VALUES (md5('qa64:area:qb')::uuid, md5('qa64:wh')::uuid, md5('qa64:loc:qb')::uuid, 'STG01', 'STAGING', 'x', 1, 1, 2, 2, 0, FALSE, 'ACTIVE');

CREATE FUNCTION pg_temp.pick_sql(id TEXT, status TEXT, destination TEXT, staging TEXT, extra_cols TEXT DEFAULT '', extra_vals TEXT DEFAULT '')
RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, destination, staging_location_code %s)
        VALUES (md5(%L)::uuid, md5('qa64:order')::uuid, 'SO-QA-64', md5('demo:wh:HCM')::uuid, %L, NOW(), %L, %L %s) $f$,
        extra_cols, id, status, destination, staging, extra_vals)
$$;
SELECT pg_temp.expect_fail_by('PK1 a destination outside DISPATCH and STAGING', 'ck_pick_destination',
    pg_temp.pick_sql('qa64:p1', 'PENDING', 'WAITING', NULL));
SELECT pg_temp.expect_fail_by('PK2 a STAGING task that names no staging area', 'ck_pick_staging_location',
    pg_temp.pick_sql('qa64:p2', 'PENDING', 'STAGING', NULL));
SELECT pg_temp.expect_fail_by('PK3 a DISPATCH task that names a staging area', 'ck_pick_staging_location',
    pg_temp.pick_sql('qa64:p3', 'PENDING', 'DISPATCH', 'HCM-STG01'));
SELECT pg_temp.expect_fail_by('PK4 a staging area that is a bin', 'not a staging area of warehouse',
    pg_temp.pick_sql('qa64:p4', 'PENDING', 'STAGING', 'HCM-A01-2-B'));
SELECT pg_temp.expect_fail_by('PK5 a staging area that is another area type (dispatch)', 'not a staging area of warehouse',
    pg_temp.pick_sql('qa64:p5', 'PENDING', 'STAGING', 'HCM-PACK01'));
SELECT pg_temp.expect_fail_by('PK6 a staging area of another warehouse', 'not a staging area of warehouse',
    pg_temp.pick_sql('qa64:p6', 'PENDING', 'STAGING', 'QB-STG01'));
SELECT pg_temp.expect_fail('PK7 a staging area that does not exist (fk_pick_staging_location)',
    pg_temp.pick_sql('qa64:p7', 'PENDING', 'STAGING', 'HCM-NOWHERE'));
SELECT pg_temp.expect_ok('PK8 a STAGING task naming a staging area of its warehouse, picking',
    pg_temp.pick_sql('qa64:pick', 'PICKING', 'STAGING', 'HCM-STG01', ', assigned_user_id', ', md5(''demo:user:editor'')::uuid'));
SELECT pg_temp.expect_ok('PK9 a DISPATCH task, the default of every task before this', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
    VALUES (md5('qa64:dispatch')::uuid, md5('qa64:order2')::uuid, 'SO-QA-64B', md5('demo:wh:HCM')::uuid, 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_true('PK10 ... and it says DISPATCH, with no staging area',
    (SELECT destination = 'DISPATCH' AND staging_location_code IS NULL FROM fulfillment.pick WHERE id = md5('qa64:dispatch')::uuid));

\echo '--- the assignee and the destination change only while the task is PENDING (07 BR-08)'
SELECT pg_temp.expect_ok('PE1 a PENDING task is given an assignee, then another, a destination, and the other again', $q$
    UPDATE fulfillment.pick SET assigned_user_id = md5('demo:user:editor')::uuid WHERE id = md5('qa64:dispatch')::uuid;
    UPDATE fulfillment.pick SET assigned_user_id = md5('demo:user:approver')::uuid WHERE id = md5('qa64:dispatch')::uuid;
    UPDATE fulfillment.pick SET destination = 'STAGING', staging_location_code = 'HCM-STG01' WHERE id = md5('qa64:dispatch')::uuid;
    UPDATE fulfillment.pick SET destination = 'DISPATCH', staging_location_code = NULL WHERE id = md5('qa64:dispatch')::uuid $q$);
SELECT pg_temp.expect_fail_by('PE2 a PICKING task is handed to someone else', 'only while it is PENDING', $q$
    UPDATE fulfillment.pick SET assigned_user_id = md5('demo:user:approver')::uuid WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('PE3 a PICKING task loses its assignee', 'only while it is PENDING', $q$
    UPDATE fulfillment.pick SET assigned_user_id = NULL WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('PE4 a PICKING task changes its destination', 'only while it is PENDING', $q$
    UPDATE fulfillment.pick SET destination = 'DISPATCH', staging_location_code = NULL WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('PE5 a PICKING task changes its staging area', 'only while it is PENDING', $q$
    UPDATE fulfillment.pick SET staging_location_code = 'HCM-QAS' WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_ok('PE6 a PICKING task changes its status as it did (the trigger is for the three columns only)', $q$
    UPDATE fulfillment.pick SET started_at = NOW() WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('PE7 a CANCELLED task is no exception', 'only while it is PENDING', $q$
    UPDATE fulfillment.pick SET status = 'CANCELLED', needs_put_back = TRUE WHERE id = md5('qa64:pick')::uuid;
    UPDATE fulfillment.pick SET assigned_user_id = md5('demo:user:approver')::uuid WHERE id = md5('qa64:pick')::uuid $q$);

\echo '--- the lines: STAGED is picked in full, by someone, and not deducted (07 section 4.2 step 5)'
CREATE FUNCTION pg_temp.line_sql(n INT, status TEXT, extra_cols TEXT DEFAULT '', extra_vals TEXT DEFAULT '')
RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                           allocated_qty, status, lpn, whole_carton %s)
        SELECT gen_random_uuid(), md5('qa64:pick')::uuid, md5('qa64:ol')::uuid, md5('qa64:res:' || c.n)::uuid,
               'SOFA-3S-GREY', 'HCM-A01-2-B', c.n, 1, %L, c.lpn, TRUE %s
          FROM qa64_carton c WHERE c.n = %s $f$, extra_cols, status, extra_vals, n)
$$;
SELECT pg_temp.expect_ok('LN1 a STAGED line: taken whole by a picker, when and where it was staged, nothing deducted',
    pg_temp.line_sql(1, 'STAGED', ', picked_qty, picked_by, picked_at, staged_at, staged_location_code',
        ', 1, md5(''demo:user:editor'')::uuid, NOW(), NOW(), ''HCM-STG01'''));
SELECT pg_temp.expect_fail_by('LN2 a STAGED line already deducted', 'ck_pick_line_staged',
    pg_temp.line_sql(2, 'STAGED', ', picked_qty, picked_by, picked_at, consumed_at, staged_at, staged_location_code',
        ', 1, md5(''demo:user:editor'')::uuid, NOW(), NOW(), NOW(), ''HCM-STG01'''));
SELECT pg_temp.expect_fail_by('LN3 a STAGED line that says nothing of when and where (also a picked line never deducted)', 'ck_pick_line_picked',
    pg_temp.line_sql(2, 'STAGED', ', picked_qty, picked_by, picked_at', ', 1, md5(''demo:user:editor'')::uuid, NOW()'));
SELECT pg_temp.expect_fail_by('LN4 a time of staging with no place', 'ck_pick_line_staged',
    pg_temp.line_sql(2, 'OPEN', ', staged_at', ', NOW()'));
SELECT pg_temp.expect_fail_by('LN5 a place of staging with no time', 'ck_pick_line_staged',
    pg_temp.line_sql(2, 'OPEN', ', staged_location_code', ', ''HCM-STG01'''));
SELECT pg_temp.expect_fail('LN6 a staging place that is no location (fk_pick_line_staged_location)',
    pg_temp.line_sql(2, 'OPEN', ', staged_at, staged_location_code', ', NOW(), ''HCM-NOWHERE'''));
SELECT pg_temp.expect_fail_by('LN7 a STAGED line short of its whole carton', 'ck_pick_line_picked',
    pg_temp.line_sql(2, 'STAGED', ', picked_qty, picked_by, picked_at, staged_at, staged_location_code',
        ', 0, md5(''demo:user:editor'')::uuid, NOW(), NOW(), ''HCM-STG01'''));
SELECT pg_temp.expect_fail_by('LN8 a STAGED line nobody picked', 'ck_pick_line_picked',
    pg_temp.line_sql(2, 'STAGED', ', picked_qty, picked_at, staged_at, staged_location_code', ', 1, NOW(), NOW(), ''HCM-STG01'''));
SELECT pg_temp.expect_fail_by('LN9 a PICKED line never deducted is still refused', 'ck_pick_line_picked',
    pg_temp.line_sql(2, 'PICKED', ', picked_qty, picked_by, picked_at', ', 1, md5(''demo:user:editor'')::uuid, NOW()'));
SELECT pg_temp.expect_fail_by('LN10 a PICKED line never deducted is refused even if it was staged', 'ck_pick_line_picked',
    pg_temp.line_sql(2, 'PICKED', ', picked_qty, picked_by, picked_at, staged_at, staged_location_code',
        ', 1, md5(''demo:user:editor'')::uuid, NOW(), NOW(), ''HCM-STG01'''));
SELECT pg_temp.expect_ok('LN11 a line staged, then deducted when the task moved to dispatch: PICKED, consumed, staging kept',
    pg_temp.line_sql(3, 'PICKED', ', picked_qty, picked_by, picked_at, consumed_at, staged_at, staged_location_code',
        ', 1, md5(''demo:user:editor'')::uuid, NOW(), NOW(), NOW(), ''HCM-STG01'''));
SELECT pg_temp.expect_ok('LN12 a line put back from staging, never deducted: PUT_BACK with staged_at and no consumed_at',
    pg_temp.line_sql(4, 'PUT_BACK', ', picked_qty, picked_by, picked_at, staged_at, staged_location_code, put_back_qty, put_back_by, put_back_at, put_back_location_code',
        ', 1, md5(''demo:user:editor'')::uuid, NOW(), NOW(), ''HCM-STG01'', 1, md5(''demo:user:editor'')::uuid, NOW(), ''HCM-A01-1-A'''));
SELECT pg_temp.expect_fail_by('LN13 a line put back that was neither staged nor deducted', 'ck_pick_line_picked',
    pg_temp.line_sql(5, 'PUT_BACK', ', picked_qty, picked_by, picked_at, put_back_qty, put_back_by, put_back_at, put_back_location_code',
        ', 1, md5(''demo:user:editor'')::uuid, NOW(), 1, md5(''demo:user:editor'')::uuid, NOW(), ''HCM-A01-1-A'''));
SELECT pg_temp.expect_ok('LN14 a line put back after a pick was deducted, as before (PUT_BACK, consumed)',
    pg_temp.line_sql(6, 'PUT_BACK', ', picked_qty, picked_by, picked_at, consumed_at, put_back_qty, put_back_by, put_back_at, put_back_location_code',
        ', 1, md5(''demo:user:editor'')::uuid, NOW(), NOW(), 1, md5(''demo:user:editor'')::uuid, NOW(), ''HCM-A01-1-A'''));
SELECT pg_temp.expect_ok('LN15 an OPEN line is untouched by all this', pg_temp.line_sql(5, 'OPEN'));

\echo '--- the task: STAGED needs a STAGING task with its time; moving to dispatch is once, by someone (07 BR-11)'
UPDATE fulfillment.pick SET status = 'PICKING', needs_put_back = FALSE WHERE id = md5('qa64:pick')::uuid;
SELECT pg_temp.expect_fail_by('PS1 a STAGED DISPATCH task', 'ck_pick_staged',
    pg_temp.pick_sql('qa64:s1', 'STAGED', 'DISPATCH', NULL, ', staged_at', ', NOW()'));
SELECT pg_temp.expect_fail_by('PS2 a STAGED task with no time of staging', 'ck_pick_staged',
    pg_temp.pick_sql('qa64:s2', 'STAGED', 'STAGING', 'HCM-STG01'));
SELECT pg_temp.expect_ok('PS3 a STAGING task completes STAGED, with its time', $q$
    UPDATE fulfillment.pick SET status = 'STAGED', staged_at = NOW() WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('PS4 a STAGED task already moved to dispatch', 'ck_pick_staged', $q$
    UPDATE fulfillment.pick SET moved_to_dispatch_at = NOW(), moved_to_dispatch_by = md5('demo:user:approver')::uuid
     WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('PS5 moved to dispatch, nobody having moved it', 'ck_pick_moved_to_dispatch', $q$
    UPDATE fulfillment.pick SET status = 'PICKED', picked_at = NOW(), moved_to_dispatch_at = NOW()
     WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('PS6 moved to dispatch by someone, at no time', 'ck_pick_moved_to_dispatch', $q$
    UPDATE fulfillment.pick SET status = 'PICKED', picked_at = NOW(), moved_to_dispatch_by = md5('demo:user:approver')::uuid
     WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('PS7 a DISPATCH task that was never staged is not moved to dispatch', 'ck_pick_moved_to_dispatch', $q$
    UPDATE fulfillment.pick SET status = 'PICKED', picked_at = NOW(), moved_to_dispatch_at = NOW(),
           moved_to_dispatch_by = md5('demo:user:approver')::uuid WHERE id = md5('qa64:dispatch')::uuid $q$);
SELECT pg_temp.expect_ok('PS8 the warehouse manager moves the staged task to dispatch: PICKED, when and by whom', $q$
    UPDATE fulfillment.pick SET status = 'PICKED', picked_at = NOW(), moved_to_dispatch_at = NOW(),
           moved_to_dispatch_by = md5('demo:user:approver')::uuid WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_ok('PS9 a top-up reopens it to PICKING, then it is PICKED again (07 section 4.3 step 7)', $q$
    UPDATE fulfillment.pick SET status = 'PICKING', picked_at = NULL WHERE id = md5('qa64:pick')::uuid;
    UPDATE fulfillment.pick SET status = 'PICKED', picked_at = NOW() WHERE id = md5('qa64:pick')::uuid $q$);
SELECT pg_temp.expect_fail_by('PS10 a status that is none of the five', 'ck_pick_status', $q$
    UPDATE fulfillment.pick SET status = 'WAITING' WHERE id = md5('qa64:pick')::uuid $q$);

\echo '--- the rules exist, and the history of earlier migrations passes them'
SELECT pg_temp.expect_true('CK1 the destination, staging, moved and staged rules exist on the task',
    (SELECT count(*) FROM pg_constraint WHERE conrelid = 'fulfillment.pick'::regclass AND conname IN
        ('ck_pick_destination', 'ck_pick_staging_location', 'ck_pick_moved_to_dispatch', 'ck_pick_staged')) = 4);
SELECT pg_temp.expect_true('CK2 the staged rule exists on the line',
    (SELECT count(*) FROM pg_constraint WHERE conrelid = 'fulfillment.pick_line'::regclass AND conname = 'ck_pick_line_staged') = 1);
SELECT pg_temp.expect_true('CK3 the area type list holds STAGING and no PACKING',
    (SELECT pg_get_constraintdef(oid) LIKE '%STAGING%' AND pg_get_constraintdef(oid) NOT LIKE '%PACKING%'
       FROM pg_constraint WHERE conname = 'ck_area_type'));
SELECT pg_temp.expect_true('CK4 the task and line status lists hold STAGED',
    (SELECT bool_and(pg_get_constraintdef(oid) LIKE '%STAGED%') FROM pg_constraint
      WHERE conname IN ('ck_pick_status', 'ck_pick_line_status')));
SELECT pg_temp.expect_true('CK5 the four triggers exist',
    (SELECT count(*) FROM pg_trigger WHERE tgname IN ('tg_area_staging', 'tg_storage_location_staging',
        'tg_stock_movement_staging', 'tg_pick_pending_edits') AND NOT tgisinternal) = 4);
SELECT pg_temp.expect_true('CK6 every rule on the pick tables is validated (nothing written before breaks them)',
    (SELECT bool_and(convalidated) FROM pg_constraint WHERE conrelid IN ('fulfillment.pick'::regclass,
        'fulfillment.pick_line'::regclass) AND conname IN ('ck_pick_destination', 'ck_pick_staging_location',
        'ck_pick_moved_to_dispatch', 'ck_pick_staged', 'ck_pick_line_staged', 'ck_pick_line_picked')));

ROLLBACK;
