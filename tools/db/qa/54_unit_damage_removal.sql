-- =============================================================================
-- Adversarial checks + happy paths: the per-unit damage reports go (V20261114000400, team plan 5-6; kltn-docs 07 §4.4
-- step 9, 07 BR-04, BR-05, BR-10, 08 §4.2 step 4, 20 BR-21): a damaged carton is a short pick with a reason or removed
-- whole at the check station, so no damage report, photo, disposition or supplier hand-over table is left, the pick
-- line has no damage column, and the rules that named those columns are stated again in the database: a line names its
-- carton, a SHORT line says why, picked and short stay within the allocation, a put-back returns what was picked.
-- Needs the demo seed. Rolled back at the end.
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
-- Fixtures: an order of HCM with a line of 8 sofas and its pick; two demo cartons of one sofa and one carton of three of
-- their own (QA54-LPN-3), each held for the order (consumed once picked) - the lines below are written against them.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE qa54_carton AS
SELECT row_number() OVER (ORDER BY p.lpn) AS n, s.id AS row_id, p.id AS package_id, p.lpn
  FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
 WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK' AND s.on_hand = 1
 ORDER BY p.lpn LIMIT 2;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa54:unit')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'QA54_CTN', 3);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status)
VALUES (md5('qa54:pk3')::uuid, 'QA54-LPN-3', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa54:unit')::uuid, 3, 3,
        'HCM-A01-2-B', 'IN_STOCK');
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at,
                                  stock_package_id)
VALUES (md5('qa54:pk3:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-2-B', 'UNTRACED', 3, 0, 'AVAILABLE', NOW(),
        NOW() - interval '1 day', md5('qa54:pk3')::uuid);

INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at, warehouse_id)
VALUES (md5('qa54:o')::uuid, 'SO-QA54', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING', 1000,
        'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid);
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa54:ol')::uuid, md5('qa54:o')::uuid, 'SOFA-3S-GREY', 8, 100, 'VND');
INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
VALUES (md5('qa54:pick')::uuid, md5('qa54:o')::uuid, 'SO-QA54', md5('demo:wh:HCM')::uuid, 'PICKING', NOW());
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at,
                                         expires_at, status)
SELECT md5('qa54:res:' || n)::uuid, row_id, md5('qa54:o')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD'
  FROM qa54_carton;
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at,
                                         expires_at, status)
VALUES (md5('qa54:res:3')::uuid, md5('qa54:pk3:row')::uuid, md5('qa54:o')::uuid, gen_random_uuid(), gen_random_uuid(), 3, NOW(),
        NULL, 'HELD');

\echo '--- a damaged carton is a SHORT line with its reason, or no line at all (07 §4.4 step 9, BR-04)'
SELECT pg_temp.expect_fail_by('DM1 a line that names no carton: the unit damage report that stood for one is gone',
    'ck_pick_line_lpn', $q$
    ALTER TABLE fulfillment.pick_line DISABLE TRIGGER tg_pick_line_carton;   -- the CHECK is the second line of defence
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                       allocated_qty, short_qty, status, short_reason)
    VALUES (gen_random_uuid(), md5('qa54:pick')::uuid, md5('qa54:ol')::uuid, md5('qa54:res:1')::uuid, 'SOFA-3S-GREY',
            'HCM-A01-2-B', 1, 1, 1, 'SHORT', 'DAMAGED') $q$);
SELECT pg_temp.expect_fail_by('DM2 a SHORT line that does not say why', 'ck_pick_line_short_reported', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                       allocated_qty, short_qty, status, lpn, whole_carton)
    SELECT gen_random_uuid(), md5('qa54:pick')::uuid, md5('qa54:ol')::uuid, md5('qa54:res:1')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 1, 1, 1, 'SHORT', c.lpn, TRUE FROM qa54_carton c WHERE c.n = 1 $q$);
SELECT pg_temp.expect_ok('DM3 a damaged carton found at the shelf: a SHORT line, reason DAMAGED, naming the carton', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                       allocated_qty, short_qty, status, short_reason, lpn, whole_carton)
    SELECT md5('qa54:pl:1')::uuid, md5('qa54:pick')::uuid, md5('qa54:ol')::uuid, md5('qa54:res:1')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 1, 1, 1, 'SHORT', 'DAMAGED', c.lpn, TRUE FROM qa54_carton c WHERE c.n = 1 $q$);
SELECT pg_temp.expect_fail_by('DM4 picked and short together above what the line allocated', 'ck_pick_line_within_allocation', $q$
    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                       allocated_qty, picked_qty, short_qty, status, short_reason, lpn, whole_carton)
    SELECT gen_random_uuid(), md5('qa54:pick')::uuid, md5('qa54:ol')::uuid, md5('qa54:res:2')::uuid, 'SOFA-3S-GREY',
           'HCM-A01-2-B', 2, 1, 1, 1, 'SHORT', 'DAMAGED', c.lpn, TRUE FROM qa54_carton c WHERE c.n = 2 $q$);

\echo '--- a put-back returns what the line picked, and something (07 BR-05)'
INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no,
                                   allocated_qty, picked_qty, status, lpn, whole_carton, picked_by, picked_at, consumed_at)
VALUES (md5('qa54:pl:3')::uuid, md5('qa54:pick')::uuid, md5('qa54:ol')::uuid, md5('qa54:res:3')::uuid, 'SOFA-3S-GREY',
        'HCM-A01-2-B', 3, 3, 3, 'PICKED', 'QA54-LPN-3', TRUE, md5('demo:user:editor')::uuid, NOW(), NOW());
SELECT pg_temp.expect_fail_by('DM5 a put-back of a part of what the line picked (the rest was "damaged at packing")',
    'ck_pick_line_put_back', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 2, put_back_by = md5('demo:user:editor')::uuid,
           put_back_location_code = 'HCM-A01-2-B', put_back_at = NOW() WHERE id = md5('qa54:pl:3')::uuid $q$);
SELECT pg_temp.expect_fail_by('DM6 a put-back of nothing', 'ck_pick_line_put_back', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 0, put_back_by = md5('demo:user:editor')::uuid,
           put_back_location_code = 'HCM-A01-2-B', put_back_at = NOW() WHERE id = md5('qa54:pl:3')::uuid $q$);
SELECT pg_temp.expect_ok('DM7 the whole carton put back', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 3, put_back_by = md5('demo:user:editor')::uuid,
           put_back_location_code = 'HCM-A01-2-B', put_back_at = NOW() WHERE id = md5('qa54:pl:3')::uuid $q$);

\echo '--- what the damage reports had is gone'
SELECT pg_temp.expect_true('DM8 no report, photo, disposition or supplier hand-over table, no function behind them', (
    SELECT count(*) FROM information_schema.tables WHERE table_schema = 'inventory' AND table_name IN
        ('stock_damage_report', 'stock_damage_photo', 'stock_damage_disposition', 'stock_damage_supplier_shipment')) = 0
    AND NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'inventory'
                       AND p.proname IN ('check_stock_damage_supplier_shipment', 'check_stock_damage_disposition',
                                         'check_stock_damage_report_photo', 'check_stock_damage_report_after_pick')));
SELECT pg_temp.expect_true('DM9 a pick line has no damaged quantity, damage report or packing damage', (
    SELECT count(*) FROM information_schema.columns WHERE table_schema = 'fulfillment' AND table_name = 'pick_line'
       AND column_name IN ('damaged_qty', 'damage_report_id', 'packing_damaged_qty')) = 0
    AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname IN ('ck_pick_line_damaged', 'ck_pick_line_packing_damaged',
                                                                   'ck_pick_line_packing_damaged_whole',
                                                                   'fk_pick_line_damage_report')));
SELECT pg_temp.expect_true('DM10 the permission of the damage reports is gone with its grants',
    (SELECT count(*) FROM identity.permission WHERE resource = 'inventory-damage-reports') = 0);
SELECT pg_temp.expect_true('DM11 the four rules that were restated are validated on the demo seed (no loose history)', (
    SELECT bool_and(convalidated) AND count(*) = 4 FROM pg_constraint
     WHERE conrelid = 'fulfillment.pick_line'::regclass AND conname IN
        ('ck_pick_line_lpn', 'ck_pick_line_short_reported', 'ck_pick_line_put_back', 'ck_pick_line_within_allocation')));
SELECT pg_temp.expect_true('DM12 the trigger that keeps a carton live at the check station from being put back fires on status only', (
    SELECT t.tgtype IS NOT NULL AND pg_get_triggerdef(t.oid) LIKE '%BEFORE UPDATE OF status ON fulfillment.pick_line%'
           AND pg_get_triggerdef(t.oid) NOT LIKE '%packing_damaged%'
      FROM pg_trigger t WHERE t.tgname = 'tg_pick_line_carton_removed' AND NOT t.tgisinternal));

ROLLBACK;
\echo 'PASS 54_unit_damage_removal'
