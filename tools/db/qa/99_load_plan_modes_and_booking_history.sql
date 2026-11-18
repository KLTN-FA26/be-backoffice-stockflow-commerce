-- =============================================================================
-- Adversarial checks + happy paths: the selection modes a load plan stores (WF4-LP-MODES, kltn-docs 09 §4.2;
-- V20261204004100) and the history of cancelled bookings (09 §4.2 step 6; V20261204004200).
-- Needs the demo seed (its history holds a load plan and its dispatch load). Rolled back at the end.
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

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    RAISE NOTICE 'ok    %', label;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

\echo '--- load_plan.selection_mode: the modes a plan stores'
SELECT pg_temp.expect_ok('LM1 KEEP_LINES ("Giữ nguyên dòng")', $q$
    UPDATE fulfillment.load_plan SET selection_mode = 'KEEP_LINES'
     WHERE id = (SELECT id FROM fulfillment.load_plan ORDER BY id LIMIT 1) $q$);
SELECT pg_temp.expect_ok('LM2 OVER_CAPACITY ("Vượt sức chứa")', $q$
    UPDATE fulfillment.load_plan SET selection_mode = 'OVER_CAPACITY'
     WHERE id = (SELECT id FROM fulfillment.load_plan ORDER BY id LIMIT 1) $q$);
SELECT pg_temp.expect_fail('LM3 PRIORITY_FILL: its main / filler split is not defined for a plan yet', $q$
    UPDATE fulfillment.load_plan SET selection_mode = 'PRIORITY_FILL'
     WHERE id = (SELECT id FROM fulfillment.load_plan ORDER BY id LIMIT 1) $q$);
SELECT pg_temp.expect_fail('LM4 an unknown mode', $q$
    UPDATE fulfillment.load_plan SET selection_mode = 'MANUAL'
     WHERE id = (SELECT id FROM fulfillment.load_plan ORDER BY id LIMIT 1) $q$);

\echo '--- load_booking_cancellation: a cancelled booking kept with its load'
SELECT pg_temp.expect_ok('LB1 a booking cancelled by hand, as it stood', $q$
    INSERT INTO fulfillment.load_booking_cancellation (id, load_id, carrier_id, waybill_no, vehicle_plate, driver_name,
        driver_phone, booked_at, cancelled_at, reason)
    SELECT gen_random_uuid(), (SELECT id FROM fulfillment.dispatch_load ORDER BY id LIMIT 1),
           (SELECT id FROM fulfillment.carrier ORDER BY id LIMIT 1), 'WB-QA-1', '51C-123.45', 'Lê Văn Lài',
           '0901234567', NOW() - INTERVAL '1 hour', NOW(), 'Xe không tới' $q$);
SELECT pg_temp.expect_fail('LB2 no reason', $q$
    INSERT INTO fulfillment.load_booking_cancellation (id, load_id, carrier_id, cancelled_at, reason)
    SELECT gen_random_uuid(), (SELECT id FROM fulfillment.dispatch_load ORDER BY id LIMIT 1),
           (SELECT id FROM fulfillment.carrier ORDER BY id LIMIT 1), NOW(), '  ' $q$);
SELECT pg_temp.expect_fail('LB3 a load that does not exist', $q$
    INSERT INTO fulfillment.load_booking_cancellation (id, load_id, carrier_id, cancelled_at, reason)
    SELECT gen_random_uuid(), gen_random_uuid(), (SELECT id FROM fulfillment.carrier ORDER BY id LIMIT 1), NOW(), 'x' $q$);
SELECT pg_temp.expect_fail('LB4 no carrier: a booking always had one', $q$
    INSERT INTO fulfillment.load_booking_cancellation (id, load_id, carrier_id, cancelled_at, reason)
    SELECT gen_random_uuid(), (SELECT id FROM fulfillment.dispatch_load ORDER BY id LIMIT 1), NULL, NOW(), 'x' $q$);

ROLLBACK;
