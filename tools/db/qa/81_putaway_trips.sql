-- =============================================================================
-- Adversarial checks + happy paths for putaway trips (V20261124000100; kltn-docs 05 §4.5, BR-15, BR-16; Jira
-- SCRUM-573): the status rules of a trip and of a carton on its cart, one open trip per clerk, one open cart per
-- carton, and the grants of the new resource. Each rule is attacked by the statement that breaks only it
-- (expect_fail_by names the rule). Needs the demo seed: two of its quarantined cartons in HCM-QC01 are given quarantine
-- release tasks, and two demo users are the clerks. Rolled back at the end.
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

-- Fixtures: two pending tasks, one per carton; trip T1 of demo.approver on its way with carton A on the cart, holding
-- its bin; trip T2 of demo.editor still loading, empty.
INSERT INTO warehouse.putaway_task (id, sku, quantity, status, version, created_at, stock_package_id, source)
SELECT md5('qa81:task:' || p.lpn)::uuid, i.sku, p.nominal_qty, 'PENDING', 0, NOW(), p.id, 'QUARANTINE_RELEASE'
  FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
 WHERE p.lpn IN ('LPN-20261111-000046', 'LPN-20261111-000047');
INSERT INTO warehouse.putaway_trip (id, warehouse_id, operator_id, status, start_location_id, started_at,
                                    last_activity_at, version, created_at)
SELECT md5('qa81:t1')::uuid, w.id, (SELECT id FROM identity.app_user WHERE username = 'demo.approver'), 'IN_PROGRESS',
       (SELECT id FROM warehouse.storage_location WHERE location_code = 'HCM-QC01'), NOW(), NOW(), 0, NOW()
  FROM warehouse.warehouse w WHERE w.prefix = 'HCM';
INSERT INTO warehouse.putaway_trip (id, warehouse_id, operator_id, status, last_activity_at, version, created_at)
SELECT md5('qa81:t2')::uuid, w.id, (SELECT id FROM identity.app_user WHERE username = 'demo.editor'), 'LOADING',
       NOW(), 0, NOW()
  FROM warehouse.warehouse w WHERE w.prefix = 'HCM';
INSERT INTO warehouse.putaway_trip_item (id, trip_id, putaway_task_id, status, assigned_location_id, stop_sequence,
                                         loaded_at, version, created_at)
VALUES (md5('qa81:t1:a')::uuid, md5('qa81:t1')::uuid, md5('qa81:task:LPN-20261111-000046')::uuid, 'ON_CART',
        (SELECT id FROM warehouse.storage_location WHERE location_code = 'HCM-A01-2-B'), 1, NOW(), 0, NOW());

\echo '--- a trip: its status, when it left, when it ended (ck_putaway_trip_*)'
SELECT pg_temp.expect_fail_by('PT1 a status the trip does not have', 'ck_putaway_trip_status', $q$
    UPDATE warehouse.putaway_trip SET status = 'PARKED' WHERE id = md5('qa81:t1')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT2 a cart still being loaded has not left', 'ck_putaway_trip_started', $q$
    UPDATE warehouse.putaway_trip SET started_at = NOW() WHERE id = md5('qa81:t2')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT3 a cart on its way has left', 'ck_putaway_trip_started', $q$
    UPDATE warehouse.putaway_trip SET started_at = NULL WHERE id = md5('qa81:t1')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT4 a trip that completed says when', 'ck_putaway_trip_closed', $q$
    UPDATE warehouse.putaway_trip SET status = 'COMPLETED' WHERE id = md5('qa81:t1')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT5 nobody closes a trip that completed: it completed with its last carton',
    'ck_putaway_trip_closed', $q$
    UPDATE warehouse.putaway_trip SET status = 'COMPLETED', closed_at = NOW(),
           closed_by = (SELECT id FROM identity.app_user WHERE username = 'demo.editor')
     WHERE id = md5('qa81:t1')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT6 an open trip has not ended', 'ck_putaway_trip_closed', $q$
    UPDATE warehouse.putaway_trip SET closed_at = NOW() WHERE id = md5('qa81:t2')::uuid $q$);

\echo '--- BR-15: one open trip per clerk, one open cart per carton'
SELECT pg_temp.expect_fail_by('PT7 a second open trip for a clerk', 'uk_putaway_trip_open_operator', $q$
    INSERT INTO warehouse.putaway_trip (id, warehouse_id, operator_id, status, last_activity_at, version, created_at)
    SELECT gen_random_uuid(), warehouse_id, operator_id, 'LOADING', NOW(), 0, NOW()
      FROM warehouse.putaway_trip WHERE id = md5('qa81:t1')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT8 a carton on a cart scanned onto another cart', 'uk_putaway_trip_item_on_cart', $q$
    INSERT INTO warehouse.putaway_trip_item (id, trip_id, putaway_task_id, status, loaded_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa81:t2')::uuid, md5('qa81:task:LPN-20261111-000046')::uuid, 'ON_CART', NOW(),
            0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('PT9 a carton twice on one trip: it comes back on its own row',
    'uk_putaway_trip_item_task', $q$
    INSERT INTO warehouse.putaway_trip_item (id, trip_id, putaway_task_id, status, loaded_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa81:t1')::uuid, md5('qa81:task:LPN-20261111-000046')::uuid, 'RETURNED', NOW(),
            0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('PT10 a carton whose putaway task does not exist', 'fk_putaway_trip_item_task', $q$
    INSERT INTO warehouse.putaway_trip_item (id, trip_id, putaway_task_id, status, loaded_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa81:t2')::uuid, gen_random_uuid(), 'ON_CART', NOW(), 0, NOW()) $q$);

\echo '--- a carton on a cart: its status, when it was put away, its hold, its stop (ck_putaway_trip_item_*)'
SELECT pg_temp.expect_fail_by('PT11 a status the carton does not have', 'ck_putaway_trip_item_status', $q$
    UPDATE warehouse.putaway_trip_item SET status = 'LOST', hold_released_at = NOW()
     WHERE id = md5('qa81:t1:a')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT12 a carton put away says when', 'ck_putaway_trip_item_placed', $q$
    UPDATE warehouse.putaway_trip_item SET status = 'PLACED', hold_released_at = NOW()
     WHERE id = md5('qa81:t1:a')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT13 a carton still on the cart was not put away', 'ck_putaway_trip_item_placed', $q$
    UPDATE warehouse.putaway_trip_item SET placed_at = NOW() WHERE id = md5('qa81:t1:a')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT14 nothing is held for a carton off the cart (BR-16)', 'ck_putaway_trip_item_hold', $q$
    UPDATE warehouse.putaway_trip_item SET status = 'RETURNED' WHERE id = md5('qa81:t1:a')::uuid $q$);
SELECT pg_temp.expect_fail_by('PT15 the stops are numbered from 1', 'ck_putaway_trip_item_stop', $q$
    UPDATE warehouse.putaway_trip_item SET stop_sequence = 0 WHERE id = md5('qa81:t1:a')::uuid $q$);

\echo '--- what the rules let through'
SELECT pg_temp.expect_ok('PT16 a carton put away: PLACED, when, its hold given back', $q$
    UPDATE warehouse.putaway_trip_item SET status = 'PLACED', placed_at = NOW(), hold_released_at = NOW()
     WHERE id = md5('qa81:t1:a')::uuid $q$);
SELECT pg_temp.expect_ok('PT17 the last carton put away completes the trip, closed by nobody', $q$
    UPDATE warehouse.putaway_trip SET status = 'COMPLETED', closed_at = NOW() WHERE id = md5('qa81:t1')::uuid $q$);
SELECT pg_temp.expect_ok('PT18 a clerk whose trip completed opens another', $q$
    INSERT INTO warehouse.putaway_trip (id, warehouse_id, operator_id, status, last_activity_at, version, created_at)
    SELECT md5('qa81:t3')::uuid, warehouse_id, operator_id, 'LOADING', NOW(), 0, NOW()
      FROM warehouse.putaway_trip WHERE id = md5('qa81:t1')::uuid $q$);
SELECT pg_temp.expect_ok('PT19 a carton taken off one cart goes onto another (BR-15)', $q$
    INSERT INTO warehouse.putaway_trip_item (id, trip_id, putaway_task_id, status, loaded_at, version, created_at)
    VALUES (md5('qa81:t2:b')::uuid, md5('qa81:t2')::uuid, md5('qa81:task:LPN-20261111-000047')::uuid, 'RETURNED',
            NOW(), 0, NOW()),
           (md5('qa81:t3:b')::uuid, md5('qa81:t3')::uuid, md5('qa81:task:LPN-20261111-000047')::uuid, 'ON_CART',
            NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_ok('PT20 a manager closes a trip left behind: CLOSED, when and by whom', $q$
    UPDATE warehouse.putaway_trip SET status = 'CLOSED', closed_at = NOW(),
           closed_by = (SELECT id FROM identity.app_user WHERE username = 'demo.approver')
     WHERE id = md5('qa81:t2')::uuid $q$);

\echo '--- the resource warehouse-putaway-trips: closing somebody else''s trip is the manager''s'
SELECT pg_temp.expect_true('PT21 the resource has VIEW_PAGE, READ, UPDATE and APPROVE', (
    SELECT array_agg(action ORDER BY action) FROM identity.permission WHERE resource = 'warehouse-putaway-trips')
    = ARRAY['APPROVE', 'READ', 'UPDATE', 'VIEW_PAGE']::varchar[]);
SELECT pg_temp.expect_true('PT22 the warehouse manager and the system admin hold all four', (
    SELECT count(*) FROM identity.role_permission rp
      JOIN identity.app_role r ON r.id = rp.role_id
      JOIN identity.permission p ON p.id = rp.permission_id
     WHERE p.resource = 'warehouse-putaway-trips' AND r.code IN ('WAREHOUSE_MANAGER', 'SYSTEM_ADMIN')) = 8);
SELECT pg_temp.expect_true('PT23 warehouse staff work their own trips but do not close another''s', (
    SELECT array_agg(p.action ORDER BY p.action) FROM identity.role_permission rp
      JOIN identity.app_role r ON r.id = rp.role_id
      JOIN identity.permission p ON p.id = rp.permission_id
     WHERE p.resource = 'warehouse-putaway-trips' AND r.code = 'WAREHOUSE_STAFF')
    = ARRAY['READ', 'UPDATE', 'VIEW_PAGE']::varchar[]);

ROLLBACK;
