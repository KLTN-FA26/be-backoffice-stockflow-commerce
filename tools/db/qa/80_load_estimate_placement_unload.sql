-- =============================================================================
-- Adversarial checks + happy paths for the load tables no other script touches: the load estimate (a vehicle count
-- worked out for an order before a plan exists), the placement of a carton inside a planned vehicle, and the unload of
-- a parcel from a manifest. Each rule is attacked by the statement that breaks only it (expect_fail_by names the rule).
-- Needs the demo seed, whose sofas are cartons of one on HCM-A01-2-B. Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
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

-- Fixtures: one order, two LABELED cartons of it (60 x 40 x 40 cm), a CONFIRMED plan with one vehicle holding both, a
-- manifest on that vehicle carrying both.
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at,
                                     shipping_recipient_name, shipping_phone, shipping_line1, shipping_ward_code,
                                     shipping_province_code, shipping_country_code)
VALUES (md5('qa80:order')::uuid, 'SO-QA-80', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
        'PENDING_PAYMENT', 1, 'VND', NOW(), NOW(), 'Ms Lan', '0900000001', '1 Le Loi', 'W1', 'P1', 'VN'),
       (md5('qa80:order2')::uuid, 'SO-QA-80B', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
        'PENDING_PAYMENT', 1, 'VND', NOW(), NOW(), 'Ms Lan', '0900000001', '1 Le Loi', 'W1', 'P1', 'VN');
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa80:ol')::uuid, md5('qa80:order')::uuid, 'SOFA-3S-GREY', 2, 1, 'VND');
INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, picked_at, created_at)
VALUES (md5('qa80:pick')::uuid, md5('qa80:order')::uuid, 'SO-QA-80', md5('demo:wh:HCM')::uuid, 'PICKED', NOW(), NOW());
INSERT INTO fulfillment.pack (id, pick_id, status, created_at)
VALUES (md5('qa80:pack')::uuid, md5('qa80:pick')::uuid, 'PENDING', NOW());

CREATE TEMP TABLE qa80_carton AS
SELECT row_number() OVER (ORDER BY p.lpn) AS n, s.id AS row_id, p.id AS package_id, p.lpn
  FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
 WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK' AND s.on_hand = 1
 ORDER BY p.lpn LIMIT 2;
SELECT pg_temp.expect_true('F0 the demo seed has two sofa cartons to build on', (SELECT count(*) FROM qa80_carton) = 2);
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at, expires_at, status)
SELECT md5('qa80:res:' || n)::uuid, row_id, md5('qa80:order')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD'
  FROM qa80_carton;
INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no, allocated_qty,
                                   picked_qty, status, lpn, whole_carton, picked_by, picked_at, consumed_at)
SELECT md5('qa80:pl:' || n)::uuid, md5('qa80:pick')::uuid, md5('qa80:ol')::uuid, md5('qa80:res:' || n)::uuid, 'SOFA-3S-GREY',
       'HCM-A01-2-B', n, 1, 1, 'PICKED', lpn, TRUE, md5('demo:user:editor')::uuid, NOW(), NOW()
  FROM qa80_carton;
INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
SELECT md5('qa80:pkg:' || n)::uuid, md5('qa80:pack')::uuid, n, 'SO-QA-80-C' || n, 'TO_CHECK', md5('qa80:pl:' || n)::uuid, lpn
  FROM qa80_carton;
UPDATE fulfillment.package SET status = 'CHECKED', checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid
 WHERE id IN (md5('qa80:pkg:1')::uuid, md5('qa80:pkg:2')::uuid);
UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = md5('qa80:order')::uuid;
UPDATE fulfillment.package SET status = 'LABELED',
       labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid, label_version = 1,
       weight_kg = 12, length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16
 WHERE id IN (md5('qa80:pkg:1')::uuid, md5('qa80:pkg:2')::uuid);
INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, ready_by, created_at)
VALUES (md5('qa80:ship')::uuid, md5('qa80:pack')::uuid, md5('qa80:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-80-S1',
        md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid, NOW());
UPDATE fulfillment.package SET shipment_id = md5('qa80:ship')::uuid WHERE id IN (md5('qa80:pkg:1')::uuid, md5('qa80:pkg:2')::uuid);
INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, status, calculated_at, confirmed_at, confirmed_by)
VALUES (md5('qa80:plan')::uuid, 'LP-QA-80', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'CONFIRMED', NOW(), NOW(),
        md5('demo:user:editor')::uuid);
INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, vehicle_type_id, vehicle_code, vehicle_kind,
                                           inner_length_mm, inner_width_mm, inner_height_mm, max_payload_kg)
SELECT md5('qa80:veh')::uuid, md5('qa80:plan')::uuid, 1, t.id, t.code, t.kind, t.inner_length_mm, t.inner_width_mm,
       t.inner_height_mm, t.max_payload_kg
  FROM fulfillment.vehicle_type t WHERE t.code = 'TRUCK-2T5';
INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, shipment_id, added_at, added_by)
SELECT gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:pkg:' || n)::uuid, md5('qa80:ship')::uuid, NOW(), md5('demo:user:editor')::uuid
  FROM qa80_carton;
INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status, load_plan_vehicle_id)
VALUES (md5('qa80:load')::uuid, 'LD-QA-80', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'PLANNED', md5('qa80:veh')::uuid);
INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, added_at, added_by)
VALUES (md5('qa80:ls')::uuid, md5('qa80:load')::uuid, md5('qa80:ship')::uuid, NOW(), md5('demo:user:editor')::uuid);
INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by)
SELECT md5('qa80:lp:' || n)::uuid, md5('qa80:load')::uuid, md5('qa80:pkg:' || n)::uuid, md5('qa80:ship')::uuid, gen_random_uuid(), NOW(),
       md5('demo:user:editor')::uuid FROM qa80_carton;

\echo '--- load estimate: a vehicle count for an order (ck_load_estimate_*, uk_load_estimate_current)'
SELECT pg_temp.expect_ok('E1 a current estimate for the order', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, warehouse_id, mode, status)
    VALUES (md5('qa80:est:1')::uuid, 'ORDER', md5('qa80:order')::uuid, md5('demo:wh:HCM')::uuid, 'FEWEST_VEHICLES', 'CURRENT') $q$);
SELECT pg_temp.expect_fail_by('E2 a second CURRENT estimate for the same order', 'uk_load_estimate_current', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status)
    VALUES (gen_random_uuid(), 'ORDER', md5('qa80:order')::uuid, 'SINGLE_TYPE', 'CURRENT') $q$);
SELECT pg_temp.expect_ok('E3 ... while the old one is SUPERSEDED, and another order has its own', $q$
    UPDATE fulfillment.load_estimate SET status = 'SUPERSEDED' WHERE id = md5('qa80:est:1')::uuid;
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status)
    VALUES (gen_random_uuid(), 'ORDER', md5('qa80:order')::uuid, 'SINGLE_TYPE', 'CURRENT'),
           (gen_random_uuid(), 'ORDER', md5('qa80:order2')::uuid, 'SINGLE_TYPE', 'CURRENT') $q$);
SELECT pg_temp.expect_fail_by('E4 a source that is not an order', 'ck_load_estimate_source_type', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status)
    VALUES (gen_random_uuid(), 'QUOTE', gen_random_uuid(), 'SINGLE_TYPE', 'SUPERSEDED') $q$);
SELECT pg_temp.expect_fail_by('E5 a mode the planner does not know', 'ck_load_estimate_mode', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status)
    VALUES (gen_random_uuid(), 'ORDER', gen_random_uuid(), 'CHEAPEST', 'SUPERSEDED') $q$);
SELECT pg_temp.expect_fail_by('E6 a status other than CURRENT or SUPERSEDED', 'ck_load_estimate_status', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status)
    VALUES (gen_random_uuid(), 'ORDER', gen_random_uuid(), 'SINGLE_TYPE', 'DRAFT') $q$);
SELECT pg_temp.expect_fail_by('E7 incomplete with nothing missing', 'ck_load_estimate_incomplete', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status, incomplete)
    VALUES (gen_random_uuid(), 'ORDER', gen_random_uuid(), 'SINGLE_TYPE', 'SUPERSEDED', TRUE) $q$);
SELECT pg_temp.expect_fail_by('E8 something missing but not flagged incomplete', 'ck_load_estimate_incomplete', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status, missing)
    VALUES (gen_random_uuid(), 'ORDER', gen_random_uuid(), 'SINGLE_TYPE', 'SUPERSEDED', '["SOFA-3S-GREY"]'::jsonb) $q$);
SELECT pg_temp.expect_ok('E9 ... flagged, it is accepted', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status, missing, incomplete)
    VALUES (gen_random_uuid(), 'ORDER', gen_random_uuid(), 'SINGLE_TYPE', 'SUPERSEDED', '["SOFA-3S-GREY"]'::jsonb, TRUE) $q$);
SELECT pg_temp.expect_fail_by('E10 lines that are an object, not a list', 'ck_load_estimate_lines', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status, lines)
    VALUES (gen_random_uuid(), 'ORDER', gen_random_uuid(), 'SINGLE_TYPE', 'SUPERSEDED', '{}'::jsonb) $q$);
SELECT pg_temp.expect_fail_by('E11 a layout that is a list, not an object', 'ck_load_estimate_layout', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status, layout)
    VALUES (gen_random_uuid(), 'ORDER', gen_random_uuid(), 'SINGLE_TYPE', 'SUPERSEDED', '[]'::jsonb) $q$);
SELECT pg_temp.expect_fail_by('E12 vehicle type ids that are an object', 'ck_load_estimate_vehicle_type_ids', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status, vehicle_type_ids)
    VALUES (gen_random_uuid(), 'ORDER', gen_random_uuid(), 'SINGLE_TYPE', 'SUPERSEDED', '{}'::jsonb) $q$);
SELECT pg_temp.expect_fail_by('E13 warnings that are an object', 'ck_load_estimate_warnings', $q$
    INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status, warnings)
    VALUES (gen_random_uuid(), 'ORDER', gen_random_uuid(), 'SINGLE_TYPE', 'SUPERSEDED', '{}'::jsonb) $q$);
-- load_estimate.warehouse_id carries no foreign key in the schema (reported, not fixed here); orphan_check.sql counts it.

\echo '--- placement of a carton in a planned vehicle (ck_load_placement_*, tg_load_placement_check)'
SELECT pg_temp.expect_ok('L1 carton 1 stands in the vehicle at its own measures (600 x 400 x 400 mm)', $q$
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (md5('qa80:place:1')::uuid, md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, md5('qa80:pkg:1')::uuid, 0, 0, 0, 600, 400, 400, 0) $q$);
SELECT pg_temp.expect_fail_by('L2 the same carton placed twice', 'uk_load_placement_package', $q$
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, md5('qa80:pkg:1')::uuid, 600, 0, 0, 600, 400, 400, 0) $q$);
SELECT pg_temp.expect_fail_by('L3 a carton standing at a negative position', 'ck_load_placement_position', $q$
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, md5('qa80:pkg:2')::uuid, -1, 0, 0, 600, 400, 400, 0) $q$);
SELECT pg_temp.expect_fail_by('L4 a carton with a zero side', 'ck_load_placement_size', $q$
    ALTER TABLE fulfillment.load_placement DISABLE TRIGGER tg_load_placement_check; -- the trigger would refuse first
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, md5('qa80:pkg:2')::uuid, 0, 0, 0, 0, 400, 400, 0) $q$);
SELECT pg_temp.expect_fail_by('L5 a seventh orientation', 'ck_load_placement_orientation', $q$
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, md5('qa80:pkg:2')::uuid, 0, 0, 0, 600, 400, 400, 6) $q$);
SELECT pg_temp.expect_fail_by('L6 sizes that are not the carton''s measures', 'tg_load_placement_check', $q$
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, md5('qa80:pkg:2')::uuid, 0, 0, 0, 500, 400, 400, 0) $q$);
SELECT pg_temp.expect_fail_by('L7 a carton standing out of the vehicle (1800 mm wide)', 'tg_load_placement_check', $q$
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, md5('qa80:pkg:2')::uuid, 1300, 0, 0, 600, 400, 400, 0) $q$);
SELECT pg_temp.expect_fail_by('L8 a placement for a carton the plan does not hold', 'is not held by the plan', $q$
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, gen_random_uuid(), 0, 0, 0, 600, 400, 400, 0) $q$);
SELECT pg_temp.expect_fail_by('L9 a placement in a vehicle that is not the plan''s', 'fk_load_placement_vehicle', $q$
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, gen_random_uuid(), md5('qa80:pkg:2')::uuid, 0, 0, 0, 600, 400, 400, 0) $q$);
SELECT pg_temp.expect_fail_by('L10 a placement for a carton that left the plan (inactive membership)', 'tg_load_placement_check', $q$
    UPDATE fulfillment.load_plan_package SET active = FALSE, released_at = NOW(), release_reason = 'LEFT_BEHIND' WHERE load_plan_id = md5('qa80:plan')::uuid AND package_id = md5('qa80:pkg:2')::uuid;
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, md5('qa80:pkg:2')::uuid, 0, 0, 400, 600, 400, 400, 0) $q$);
SELECT pg_temp.expect_ok('L11 a rotated carton (sides swapped) fits where its measures allow', $q$
    INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation)
    VALUES (gen_random_uuid(), md5('qa80:plan')::uuid, md5('qa80:veh')::uuid, md5('qa80:pkg:2')::uuid, 0, 0, 400, 400, 600, 400, 1) $q$);

\echo '--- unloading a parcel from a manifest (ck_dispatch_load_unload_*, tg_dispatch_load_unload_pending)'
SELECT pg_temp.expect_ok('U1 a parcel asked off the load', $q$
    INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, requested_at)
    VALUES (md5('qa80:un:1')::uuid, md5('qa80:load')::uuid, md5('qa80:pkg:1')::uuid, md5('qa80:ship')::uuid, 'wrong vehicle', NOW()) $q$);
SELECT pg_temp.expect_fail_by('U2 the same parcel asked off twice', 'uk_dispatch_load_unload', $q$
    INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, requested_at)
    VALUES (gen_random_uuid(), md5('qa80:load')::uuid, md5('qa80:pkg:1')::uuid, md5('qa80:ship')::uuid, 'again', NOW()) $q$);
SELECT pg_temp.expect_fail_by('U3 an unload with a blank reason', 'ck_dispatch_load_unload_reason', $q$
    INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, requested_at)
    VALUES (gen_random_uuid(), md5('qa80:load')::uuid, md5('qa80:pkg:2')::uuid, md5('qa80:ship')::uuid, '   ', NOW()) $q$);
SELECT pg_temp.expect_fail_by('U4 a reason code outside the list', 'ck_dispatch_load_unload_reason_code', $q$
    INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, reason_code, requested_at)
    VALUES (gen_random_uuid(), md5('qa80:load')::uuid, md5('qa80:pkg:2')::uuid, md5('qa80:ship')::uuid, 'x', 'BORED', NOW()) $q$);
SELECT pg_temp.expect_fail_by('U5 unloaded at a time by nobody', 'ck_dispatch_load_unload_done', $q$
    INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, requested_at, unloaded_at)
    VALUES (gen_random_uuid(), md5('qa80:load')::uuid, md5('qa80:pkg:2')::uuid, md5('qa80:ship')::uuid, 'x', NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail_by('U6 an override of an unload that has not happened', 'ck_dispatch_load_unload_override', $q$
    INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, requested_at, overridden)
    VALUES (gen_random_uuid(), md5('qa80:load')::uuid, md5('qa80:pkg:2')::uuid, md5('qa80:ship')::uuid, 'x', NOW(), TRUE) $q$);
SELECT pg_temp.expect_fail_by('U7 an override note on an unload nobody overrode', 'ck_dispatch_load_unload_override_note', $q$
    INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, requested_at, override_note)
    VALUES (gen_random_uuid(), md5('qa80:load')::uuid, md5('qa80:pkg:2')::uuid, md5('qa80:ship')::uuid, 'x', NOW(), 'because') $q$);
SELECT pg_temp.expect_fail_by('U8 an unload of a parcel that does not exist', 'fk_dispatch_load_unload_package', $q$
    INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, requested_at)
    VALUES (gen_random_uuid(), md5('qa80:load')::uuid, gen_random_uuid(), md5('qa80:ship')::uuid, 'x', NOW()) $q$);

-- DISPATCHED needs its own facts (ck_dispatch_load_dispatched and its neighbours), so the one thing that differs is the unload.
CREATE FUNCTION pg_temp.dispatch_sql() RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$ UPDATE fulfillment.dispatch_load SET status = 'DISPATCHED', carrier_id = %L, booked_at = NOW(), loading_started_at = NOW(),
        dispatched_at = NOW(), dispatched_by = md5('demo:user:editor')::uuid, carrier_representative = 'Driver', vehicle_plate = '51C-00000',
        waybill_no = 'WB-QA-80', shipment_count = 1, package_count = 2 WHERE id = md5('qa80:load')::uuid $f$,
        (SELECT id FROM fulfillment.carrier ORDER BY code LIMIT 1))
$$;
SELECT pg_temp.expect_true('U9 the demo seed has a carrier to dispatch with', (SELECT count(*) FROM fulfillment.carrier) > 0);
SELECT pg_temp.expect_fail_by('U10 a load is not DISPATCHED while a parcel is still to be unloaded', 'tg_dispatch_load_unload_pending',
    pg_temp.dispatch_sql());
SELECT pg_temp.expect_ok('U11 the parcel is unloaded', $q$
    UPDATE fulfillment.dispatch_load_unload SET unloaded_at = NOW(), unloaded_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa80:un:1')::uuid $q$);
SELECT pg_temp.expect_ok('U12 ... and then the load is DISPATCHED', pg_temp.dispatch_sql());

ROLLBACK;
