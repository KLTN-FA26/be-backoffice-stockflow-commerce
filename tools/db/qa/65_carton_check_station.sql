-- =============================================================================
-- Adversarial checks + happy paths: the carton check and labelling station (V20261113000700, team plan 4C-6;
-- kltn-docs 08 §4-§5, BR-01..BR-11, 17 BR-12, BR-16): the session's statuses, the shipping carton's rows and states,
-- the put-back rule, the first label locking the order (its delivery address included), and what a labelled carton may
-- do afterwards (join a load plan and a manifest). Needs the demo seed, whose sofas are cartons of one on HCM-A01-2-B
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
-- Fixtures: order A (task A, six cartons) is the one the labels lock; order B (task B, two cartons) stays unlocked until
-- L15; order C has no task and is the one that gets cancelled; order D (no task either) is put on hold (V20261113000710).
-- Every line is a PICKED carton of one sofa, held for its order and named by its LPN.
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at,
                                     shipping_recipient_name, shipping_phone, shipping_line1, shipping_ward_code,
                                     shipping_province_code, shipping_country_code)
SELECT md5('qa65:order:' || k)::uuid, 'SO-QA-65' || k, 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
       'PENDING_PAYMENT', 1, 'VND', NOW(), NOW(), 'Ms Lan', '0900000001', '1 Le Loi', 'W1', 'P1', 'VN'
  FROM (VALUES ('A'), ('B'), ('C'), ('D')) v(k);
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
SELECT md5('qa65:ol:' || k)::uuid, md5('qa65:order:' || k)::uuid, 'SOFA-3S-GREY', 8, 1, 'VND'
  FROM (VALUES ('A'), ('B')) v(k);
INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, picked_at, created_at)
SELECT md5('qa65:pick:' || k)::uuid, md5('qa65:order:' || k)::uuid, 'SO-QA-65' || k, md5('demo:wh:HCM')::uuid, 'PICKED', NOW(), NOW()
  FROM (VALUES ('A'), ('B')) v(k);
INSERT INTO fulfillment.pack (id, pick_id, status, created_at)
SELECT md5('qa65:pack:' || k)::uuid, md5('qa65:pick:' || k)::uuid, 'PENDING', NOW() FROM (VALUES ('A'), ('B')) v(k);

CREATE TEMP TABLE qa65_carton AS
SELECT row_number() OVER (ORDER BY p.lpn) AS n, s.id AS row_id, p.id AS package_id, p.lpn
  FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
 WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK' AND s.on_hand = 1
 ORDER BY p.lpn LIMIT 9;

-- Cartons 1-6 are order A's, 7-8 order B's; carton 9 is nobody's.
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at, expires_at, status)
SELECT md5('qa65:res:' || n)::uuid, row_id,
       CASE WHEN n <= 6 THEN md5('qa65:order:A')::uuid ELSE md5('qa65:order:B')::uuid END,
       gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD'
  FROM qa65_carton WHERE n <= 8;
INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no, allocated_qty,
                                   picked_qty, status, lpn, whole_carton, picked_by, picked_at, consumed_at)
SELECT md5('qa65:pl:' || n)::uuid,
       CASE WHEN n <= 6 THEN md5('qa65:pick:A')::uuid ELSE md5('qa65:pick:B')::uuid END,
       CASE WHEN n <= 6 THEN md5('qa65:ol:A')::uuid ELSE md5('qa65:ol:B')::uuid END,
       md5('qa65:res:' || n)::uuid, 'SOFA-3S-GREY', 'HCM-A01-2-B', n, 1, 1, 'PICKED', lpn, TRUE,
       md5('demo:user:editor')::uuid, NOW(), NOW()
  FROM qa65_carton WHERE n <= 8;

-- A shipping carton row of the session of task k on carton n, with the columns that differ given by the caller.
CREATE FUNCTION pg_temp.carton_sql(n INT, k TEXT, status TEXT, extra_cols TEXT DEFAULT '', extra_vals TEXT DEFAULT '')
RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn %s)
        SELECT md5('qa65:pkg:' || c.n)::uuid, md5('qa65:pack:' || %L)::uuid, c.n, 'SO-QA-65' || %L || '-C' || c.n,
               %L, md5('qa65:pl:' || c.n)::uuid, c.lpn %s
          FROM qa65_carton c WHERE c.n = %s $f$, extra_cols, k, k, status, extra_vals, n)
$$;

-- What a carton of this size and weight is when labelled: the carton spec's measures and the volumetric weight.
\set LABELED 'status = ''LABELED'', checked_at = NOW(), checked_by = md5(''demo:user:editor'')::uuid, labeled_at = NOW(), labeled_by = md5(''demo:user:editor'')::uuid, label_version = 1, weight_kg = 12, length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16'

\echo '--- the session: the documented statuses only (08 §5.1)'
SELECT pg_temp.expect_fail_by('S1 the repack station''s PACKING is not a session status any more', 'ck_pack_status', $q$
    UPDATE fulfillment.pack SET status = 'PACKING', started_at = NOW() WHERE id = md5('qa65:pack:A')::uuid $q$);
SELECT pg_temp.expect_fail_by('S2 ... nor PACKED', 'ck_pack_status', $q$
    UPDATE fulfillment.pack SET status = 'PACKED', started_at = NOW(), packed_at = NOW() WHERE id = md5('qa65:pack:A')::uuid $q$);
SELECT pg_temp.expect_fail_by('S3 IN_PROGRESS without who started it when', 'ck_pack_started', $q$
    UPDATE fulfillment.pack SET status = 'IN_PROGRESS' WHERE id = md5('qa65:pack:A')::uuid $q$);
SELECT pg_temp.expect_fail_by('S4 COMPLETED without when it was completed', 'ck_pack_packed', $q$
    UPDATE fulfillment.pack SET status = 'COMPLETED', started_at = NOW() WHERE id = md5('qa65:pack:A')::uuid $q$);
SELECT pg_temp.expect_ok('S5 PENDING, IN_PROGRESS, COMPLETED, each with its facts', $q$
    UPDATE fulfillment.pack SET status = 'IN_PROGRESS', started_at = NOW(), started_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa65:pack:A')::uuid;
    UPDATE fulfillment.pack SET status = 'COMPLETED', packed_at = NOW(), packed_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa65:pack:A')::uuid;
    UPDATE fulfillment.pack SET status = 'IN_PROGRESS', packed_at = NULL, packed_by = NULL
     WHERE id = md5('qa65:pack:A')::uuid $q$);
SELECT pg_temp.expect_ok('S6 a session is CANCELLED with its order', $q$
    UPDATE fulfillment.pack SET status = 'CANCELLED' WHERE id = md5('qa65:pack:B')::uuid;
    UPDATE fulfillment.pack SET status = 'PENDING' WHERE id = md5('qa65:pack:B')::uuid $q$);

\echo '--- a shipping carton row is one picked carton, never a repack parcel (ck_package_kind, tg_package_carton, tg_package_is_carton; 08 BR-05)'
SELECT pg_temp.expect_fail_by('K1 a carton row with no LPN', 'ck_package_kind', $q$
    ALTER TABLE fulfillment.package DISABLE TRIGGER tg_package_carton;
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id)
    VALUES (gen_random_uuid(), md5('qa65:pack:A')::uuid, 91, 'QA65-K1', 'TO_CHECK', md5('qa65:pl:1')::uuid) $q$);
SELECT pg_temp.expect_fail_by('K2 a repack parcel with an LPN', 'ck_package_kind', $q$
    ALTER TABLE fulfillment.package DISABLE TRIGGER tg_package_is_carton;
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, lpn)
    SELECT gen_random_uuid(), md5('qa65:pack:A')::uuid, 92, 'QA65-K2', 'OPEN', c.lpn
      FROM qa65_carton c WHERE c.n = 9 $q$);
SELECT pg_temp.expect_fail_by('K3 a repack parcel with a carton''s status', 'ck_package_kind', $q$
    ALTER TABLE fulfillment.package DISABLE TRIGGER tg_package_is_carton;
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status)
    VALUES (gen_random_uuid(), md5('qa65:pack:A')::uuid, 93, 'QA65-K3', 'TO_CHECK') $q$);
SELECT pg_temp.expect_fail_by('K4 a carton that is SEALED (it is never packed again)', 'ck_package_kind',
    'ALTER TABLE fulfillment.package DISABLE TRIGGER tg_package_carton; ALTER TABLE fulfillment.package DISABLE TRIGGER tg_package_is_carton; '
    || pg_temp.carton_sql(1, 'A', 'SEALED', ', sealed_at', ', NOW()'));
SELECT pg_temp.expect_fail_by('K5 a new row naming no pick line: parcels are no longer packed (4C-7)', 'parcels are no longer packed', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status)
    VALUES (gen_random_uuid(), md5('qa65:pack:A')::uuid, 94, 'QA65-K5', 'OPEN') $q$);
SELECT pg_temp.expect_fail_by('K6 a carton of another task''s pick line (task B''s line in task A''s session)', 'tg_package_carton',
    pg_temp.carton_sql(7, 'A', 'TO_CHECK'));
SELECT pg_temp.expect_fail_by('K7 a carton row inserted CHECKED: every carton starts TO_CHECK', 'tg_package_carton',
    pg_temp.carton_sql(1, 'A', 'CHECKED', ', checked_at, checked_by', ', NOW(), md5(''demo:user:editor'')::uuid'));
SELECT pg_temp.expect_fail_by('K8 a carton row whose LPN is not the pick line''s', 'tg_package_carton', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT gen_random_uuid(), md5('qa65:pack:A')::uuid, 95, 'QA65-K8', 'TO_CHECK', md5('qa65:pl:1')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 2 $q$);
SELECT pg_temp.expect_fail_by('K9 a carton of a pick line not PICKED (OPEN)', 'tg_package_carton', $q$
    UPDATE fulfillment.pick_line SET status = 'OPEN', picked_qty = 0, picked_by = NULL, picked_at = NULL, consumed_at = NULL
     WHERE id = md5('qa65:pl:3')::uuid;
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT gen_random_uuid(), md5('qa65:pack:A')::uuid, 96, 'QA65-K9', 'TO_CHECK', md5('qa65:pl:3')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 3 $q$);
SELECT pg_temp.expect_ok('K10 a TO_CHECK carton of the task''s own picked line', pg_temp.carton_sql(1, 'A', 'TO_CHECK'));
SELECT pg_temp.expect_fail_by('K11 a second row for the same pick line', 'uk_package_pick_line', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT gen_random_uuid(), md5('qa65:pack:A')::uuid, 97, 'QA65-K11', 'TO_CHECK', md5('qa65:pl:1')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 1 $q$);
SELECT pg_temp.expect_fail_by('K12 one LPN TO_CHECK or CHECKED in two rows at once', 'uk_package_lpn_checking', $q$
    ALTER TABLE fulfillment.package DISABLE TRIGGER tg_package_carton;
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT gen_random_uuid(), md5('qa65:pack:A')::uuid, 98, 'QA65-K12', 'TO_CHECK', md5('qa65:pl:2')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 1 $q$);
SELECT pg_temp.expect_fail_by('K13 the carton''s LPN is rewritten', 'tg_package_carton', $q$
    UPDATE fulfillment.package SET lpn = (SELECT lpn FROM qa65_carton WHERE n = 2) WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('K14 the carton is moved to another pick line', 'tg_package_carton', $q$
    UPDATE fulfillment.package SET pick_line_id = md5('qa65:pl:2')::uuid WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('K15 the carton is moved to another session', 'tg_package_carton', $q$
    UPDATE fulfillment.package SET pack_id = md5('qa65:pack:B')::uuid WHERE id = md5('qa65:pkg:1')::uuid $q$);

\echo '--- the carton''s states: TO_CHECK -> CHECKED -> LABELED, or REMOVED (08 §5.2)'
SELECT pg_temp.expect_fail_by('T1 TO_CHECK straight to LABELED (not checked)', 'tg_package_carton', $q$
    UPDATE fulfillment.package SET status = 'LABELED', checked_at = COALESCE(checked_at, NOW()), checked_by = md5('demo:user:editor')::uuid, labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid, label_version = 1, weight_kg = 12, length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16 WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('T2 CHECKED without who checked it when', 'ck_package_checked', $q$
    UPDATE fulfillment.package SET status = 'CHECKED' WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('T3 checked_at without checked_by', 'ck_package_checked', $q$
    UPDATE fulfillment.package SET checked_at = NOW() WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_ok('T4 TO_CHECK -> CHECKED', $q$
    UPDATE fulfillment.package SET status = 'CHECKED', checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('T5 CHECKED back to TO_CHECK', 'tg_package_carton', $q$
    UPDATE fulfillment.package SET status = 'TO_CHECK' WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('T6 LABELED without its size (BR-07)', 'ck_package_labeled', $q$
    UPDATE fulfillment.package SET status = 'LABELED', labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid,
           label_version = 1, weight_kg = 12, dim_weight_kg = 16 WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('T7 LABELED without its weight (BR-07)', 'ck_package_labeled', $q$
    UPDATE fulfillment.package SET status = 'LABELED', labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid,
           label_version = 1, length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16
     WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('T8 LABELED without its volumetric weight (BR-09)', 'ck_package_labeled', $q$
    UPDATE fulfillment.package SET status = 'LABELED', labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid,
           label_version = 1, weight_kg = 12, length_cm = 60, width_cm = 40, height_cm = 40
     WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('T9 LABELED with label version 0', 'ck_package_labeled', $q$
    UPDATE fulfillment.package SET status = 'LABELED', labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid,
           label_version = 0, weight_kg = 12, length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16
     WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('T10 a label version with nobody having labelled', 'ck_package_labeled', $q$
    UPDATE fulfillment.package SET label_version = 1 WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('T11 labelled by nobody', 'ck_package_labeled', $q$
    UPDATE fulfillment.package SET status = 'LABELED', labeled_at = NOW(), label_version = 1, weight_kg = 12,
           length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16 WHERE id = md5('qa65:pkg:1')::uuid $q$);

\echo '--- the first label locks the order (08 BR-04, 17 BR-12, BR-16)'
SELECT pg_temp.expect_fail_by('L1 a LABELED carton of an order not locked fails at commit', 'tg_package_label_locks_order', $q$
    UPDATE fulfillment.package SET status = 'LABELED', checked_at = COALESCE(checked_at, NOW()), checked_by = md5('demo:user:editor')::uuid, labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid, label_version = 1, weight_kg = 12, length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16 WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_ok('L2 the same, once the order''s first label time is set in the same transaction', $q$
    UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = md5('qa65:order:A')::uuid;
    UPDATE fulfillment.package SET status = 'LABELED', checked_at = COALESCE(checked_at, NOW()), checked_by = md5('demo:user:editor')::uuid, labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid, label_version = 1, weight_kg = 12, length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16 WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('L3 a carton labelled for an order that is not locked, inserted at once', 'tg_package_label_locks_order', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT md5('qa65:pkg:7')::uuid, md5('qa65:pack:B')::uuid, 7, 'SO-QA-65B-C7', 'TO_CHECK', md5('qa65:pl:7')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 7;
    UPDATE fulfillment.package SET status = 'CHECKED', checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa65:pkg:7')::uuid;
    UPDATE fulfillment.package SET status = 'LABELED', checked_at = COALESCE(checked_at, NOW()), checked_by = md5('demo:user:editor')::uuid, labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid, label_version = 1, weight_kg = 12, length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16 WHERE id = md5('qa65:pkg:7')::uuid $q$);
SELECT pg_temp.expect_fail_by('L4 an order with a labelled carton is not cancelled', 'ck_order_labeled_not_cancelled', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'qa', cancellation_reason_code = 'OTHER' WHERE id = md5('qa65:order:A')::uuid $q$);
SELECT pg_temp.expect_ok('L5 an order never labelled (C) is cancelled as before', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'qa', cancellation_reason_code = 'OTHER' WHERE id = md5('qa65:order:C')::uuid $q$);
SELECT pg_temp.expect_fail_by('L5b ... and a cancelled order is never labelled afterwards', 'ck_order_labeled_not_cancelled', $q$
    UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = md5('qa65:order:C')::uuid $q$);
SELECT pg_temp.expect_fail_by('L6 the delivery address of a locked order changes (line 1)', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET shipping_line1 = '2 Tran Hung Dao' WHERE id = md5('qa65:order:A')::uuid $q$);
SELECT pg_temp.expect_fail_by('L7 ... the recipient', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET shipping_recipient_name = 'Someone else' WHERE id = md5('qa65:order:A')::uuid $q$);
SELECT pg_temp.expect_fail_by('L8 ... the phone', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET shipping_phone = '0900000002' WHERE id = md5('qa65:order:A')::uuid $q$);
SELECT pg_temp.expect_fail_by('L9 ... the province', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET shipping_province_code = 'P2' WHERE id = md5('qa65:order:A')::uuid $q$);
SELECT pg_temp.expect_fail_by('L10 ... even to a null', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET shipping_line2 = 'Floor 3' WHERE id = md5('qa65:order:A')::uuid $q$);
SELECT pg_temp.expect_fail_by('L11 the first label time is moved', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET first_labeled_at = NOW() + interval '1 hour' WHERE id = md5('qa65:order:A')::uuid $q$);
SELECT pg_temp.expect_fail_by('L12 the first label time is cleared', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET first_labeled_at = NULL WHERE id = md5('qa65:order:A')::uuid $q$);
SELECT pg_temp.expect_ok('L13 anything else on a locked order still changes (a contact name)', $q$
    UPDATE ordering.customer_order SET contact_name = 'Ms Lan' WHERE id = md5('qa65:order:A')::uuid $q$);
SELECT pg_temp.expect_ok('L14 the delivery address of an order not labelled yet changes', $q$
    UPDATE ordering.customer_order SET shipping_line1 = '2 Tran Hung Dao', shipping_phone = '0900000002'
     WHERE id = md5('qa65:order:B')::uuid $q$);
SELECT pg_temp.expect_ok('L15 and its first label time is set once', $q$
    UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = md5('qa65:order:B')::uuid $q$);
SELECT pg_temp.expect_fail_by('L16 ... after which its address is locked too', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET shipping_line1 = '3 Hai Ba Trung' WHERE id = md5('qa65:order:B')::uuid $q$);

\echo '--- no first label on an order on hold (V20261113000710; review L3, 08 edge "a wrong address is caught before the first label")'
UPDATE ordering.customer_order SET status = 'CONFIRMED', contract_signed_at = NOW() WHERE id = md5('qa65:order:D')::uuid;

SELECT pg_temp.expect_fail_by('L17 put on hold and labelled in one statement', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'ADDRESS', status_before_hold = 'CONFIRMED',
           first_labeled_at = NOW() WHERE id = md5('qa65:order:D')::uuid $q$);
SELECT pg_temp.expect_ok('L18 an ADDRESS hold on an order not labelled', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'ADDRESS', status_before_hold = 'CONFIRMED'
     WHERE id = md5('qa65:order:D')::uuid $q$);
SELECT pg_temp.expect_fail_by('L19 a held order gets its first label', 'tg_order_label_lock', $q$
    UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = md5('qa65:order:D')::uuid $q$);
SELECT pg_temp.expect_ok('L20 while held, its address is corrected (that is what the hold is for)', $q$
    UPDATE ordering.customer_order SET shipping_line1 = '9 Nguyen Hue' WHERE id = md5('qa65:order:D')::uuid $q$);
SELECT pg_temp.expect_ok('L21 the hold resolved, the first label is recorded', $q$
    UPDATE ordering.customer_order SET status = 'CONFIRMED', hold_reason = NULL, status_before_hold = NULL
     WHERE id = md5('qa65:order:D')::uuid;
    UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = md5('qa65:order:D')::uuid $q$);
SELECT pg_temp.expect_ok('L22 a hold put on an order already labelled is not this rule''s concern (its label stands)', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'CUSTOMER', status_before_hold = 'CONFIRMED'
     WHERE id = md5('qa65:order:D')::uuid $q$);

\echo '--- a reprint raises the label version, never lowers it (08 BR-08)'
SELECT pg_temp.expect_ok('R1 a reprint: LABELED stays LABELED, the version goes 1 -> 2', $q$
    UPDATE fulfillment.package SET label_version = 2 WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('R2 a lower label version on a reprint', 'tg_package_carton', $q$
    UPDATE fulfillment.package SET label_version = 1 WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('R3 label version back to 0 once labelled', 'ck_package_labeled', $q$
    ALTER TABLE fulfillment.package DISABLE TRIGGER tg_package_carton;
    UPDATE fulfillment.package SET label_version = 0 WHERE id = md5('qa65:pkg:1')::uuid $q$);

\echo '--- a removed carton says why; a labelled one leaves only while in no shipment (08 §5.2, BR-04)'
SELECT pg_temp.expect_ok('D1 a TO_CHECK carton of order A is added (carton 2), then removed as damaged by a worker', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT md5('qa65:pkg:2')::uuid, md5('qa65:pack:A')::uuid, 2, 'SO-QA-65A-C2', 'TO_CHECK', md5('qa65:pl:2')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 2 $q$);
SELECT pg_temp.expect_fail_by('D2 REMOVED without a reason', 'ck_package_removed', $q$
    UPDATE fulfillment.package SET status = 'REMOVED', removed_at = NOW() WHERE id = md5('qa65:pkg:2')::uuid $q$);
SELECT pg_temp.expect_fail_by('D3 REMOVED without removed_at', 'ck_package_removed', $q$
    UPDATE fulfillment.package SET status = 'REMOVED', removal_reason = 'ORDER_CANCELLED' WHERE id = md5('qa65:pkg:2')::uuid $q$);
SELECT pg_temp.expect_fail_by('D4 DAMAGED without who removed it', 'ck_package_removed', $q$
    UPDATE fulfillment.package SET status = 'REMOVED', removed_at = NOW(), removal_reason = 'DAMAGED' WHERE id = md5('qa65:pkg:2')::uuid $q$);
SELECT pg_temp.expect_fail_by('D5 a reason outside DAMAGED, ORDER_CANCELLED and REMAINDER_CLOSED', 'ck_package_removed', $q$
    UPDATE fulfillment.package SET status = 'REMOVED', removed_at = NOW(), removed_by = md5('demo:user:editor')::uuid,
           removal_reason = 'LOST' WHERE id = md5('qa65:pkg:2')::uuid $q$);
SELECT pg_temp.expect_fail_by('D6 a blank removal note', 'ck_package_removed', $q$
    UPDATE fulfillment.package SET status = 'REMOVED', removed_at = NOW(), removed_by = md5('demo:user:editor')::uuid,
           removal_reason = 'DAMAGED', removal_note = '   ' WHERE id = md5('qa65:pkg:2')::uuid $q$);
SELECT pg_temp.expect_fail_by('D7 a removal reason on a carton that is not removed', 'ck_package_removed', $q$
    UPDATE fulfillment.package SET removal_reason = 'DAMAGED', removed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa65:pkg:2')::uuid $q$);
SELECT pg_temp.expect_ok('D8 DAMAGED by a worker, with a note', $q$
    UPDATE fulfillment.package SET status = 'REMOVED', removed_at = NOW(), removed_by = md5('demo:user:editor')::uuid,
           removal_reason = 'DAMAGED', removal_note = 'corner crushed' WHERE id = md5('qa65:pkg:2')::uuid $q$);
SELECT pg_temp.expect_fail_by('D9 a REMOVED carton comes back', 'tg_package_carton', $q$
    UPDATE fulfillment.package SET status = 'CHECKED', removed_at = NULL, removed_by = NULL, removal_reason = NULL,
           removal_note = NULL, checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid WHERE id = md5('qa65:pkg:2')::uuid $q$);
SELECT pg_temp.expect_ok('D10 a CHECKED carton (3) is removed by the system for a cancelled order: no worker', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT md5('qa65:pkg:3')::uuid, md5('qa65:pack:A')::uuid, 3, 'SO-QA-65A-C3', 'TO_CHECK', md5('qa65:pl:3')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 3;
    UPDATE fulfillment.package SET status = 'REMOVED', removed_at = NOW(), removal_reason = 'ORDER_CANCELLED'
     WHERE id = md5('qa65:pkg:3')::uuid $q$);
SELECT pg_temp.expect_ok('D11 a LABELED carton (4) removed while in no shipment', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT md5('qa65:pkg:4')::uuid, md5('qa65:pack:A')::uuid, 4, 'SO-QA-65A-C4', 'TO_CHECK', md5('qa65:pl:4')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 4;
    UPDATE fulfillment.package SET status = 'CHECKED', checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid WHERE id = md5('qa65:pkg:4')::uuid;
    UPDATE fulfillment.package SET status = 'LABELED', labeled_at = NOW(), labeled_by = md5('demo:user:editor')::uuid, label_version = 1,
           weight_kg = 12, length_cm = 60, width_cm = 40, height_cm = 40, dim_weight_kg = 16 WHERE id = md5('qa65:pkg:4')::uuid;
    UPDATE fulfillment.package SET status = 'REMOVED', removed_at = NOW(), removed_by = md5('demo:user:editor')::uuid,
           removal_reason = 'DAMAGED' WHERE id = md5('qa65:pkg:4')::uuid $q$);

\echo '--- a shipment takes sealed parcels and labelled cartons only (ck_package_shipped)'
INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, ready_by, created_at)
VALUES (md5('qa65:ship:A')::uuid, md5('qa65:pack:A')::uuid, md5('qa65:order:A')::uuid, 'READY_TO_DISPATCH', 'SO-QA-65A-S1',
        md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid, NOW());
SELECT pg_temp.expect_fail_by('H1 a REMOVED carton in a shipment', 'ck_package_shipped', $q$
    UPDATE fulfillment.package SET shipment_id = md5('qa65:ship:A')::uuid WHERE id = md5('qa65:pkg:2')::uuid $q$);
SELECT pg_temp.expect_ok('H2 a LABELED carton (1) in the shipment', $q$
    UPDATE fulfillment.package SET shipment_id = md5('qa65:ship:A')::uuid WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('H3 a LABELED carton that is in a shipment is not removed', 'ck_package_shipped', $q$
    UPDATE fulfillment.package SET status = 'REMOVED', removed_at = NOW(), removed_by = md5('demo:user:editor')::uuid,
           removal_reason = 'DAMAGED' WHERE id = md5('qa65:pkg:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('H4 a CHECKED carton in a shipment', 'ck_package_shipped', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT md5('qa65:pkg:5')::uuid, md5('qa65:pack:A')::uuid, 5, 'SO-QA-65A-C5', 'TO_CHECK', md5('qa65:pl:5')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 5;
    UPDATE fulfillment.package SET status = 'CHECKED', checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid,
           shipment_id = md5('qa65:ship:A')::uuid WHERE id = md5('qa65:pkg:5')::uuid $q$);

\echo '--- a carton live at the station is not put back; a removed one is (tg_pick_line_carton_removed; 08 §4.2 step 4)'
SELECT pg_temp.expect_ok('P1 carton 5 comes to the station: TO_CHECK', $q$
    INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
    SELECT md5('qa65:pkg:5')::uuid, md5('qa65:pack:A')::uuid, 5, 'SO-QA-65A-C5', 'TO_CHECK', md5('qa65:pl:5')::uuid, c.lpn
      FROM qa65_carton c WHERE c.n = 5 $q$);
SELECT pg_temp.expect_fail_by('P2 its line put back while TO_CHECK', 'tg_pick_line_carton_removed', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 1, put_back_by = md5('demo:user:editor')::uuid,
           put_back_at = NOW(), put_back_location_code = 'HCM-A01-1-A' WHERE id = md5('qa65:pl:5')::uuid $q$);
SELECT pg_temp.expect_ok('P3 checked', $q$
    UPDATE fulfillment.package SET status = 'CHECKED', checked_at = NOW(), checked_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa65:pkg:5')::uuid $q$);
SELECT pg_temp.expect_fail_by('P4 its line put back while CHECKED', 'tg_pick_line_carton_removed', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 1, put_back_by = md5('demo:user:editor')::uuid,
           put_back_at = NOW(), put_back_location_code = 'HCM-A01-1-A' WHERE id = md5('qa65:pl:5')::uuid $q$);
SELECT pg_temp.expect_true('P4b no damage-after-pick columns are left on a pick line (5-6): damaged cartons are a short pick or a removal', (
    SELECT count(*) FROM information_schema.columns WHERE table_schema = 'fulfillment' AND table_name = 'pick_line'
       AND column_name IN ('damaged_qty', 'damage_report_id', 'packing_damaged_qty')) = 0);
SELECT pg_temp.expect_ok('P4c ... so a pick line of a carton at the station still changes in a column that is not its status', $q$
    UPDATE fulfillment.pick_line SET sequence_no = sequence_no WHERE id = md5('qa65:pl:5')::uuid $q$);
SELECT pg_temp.expect_fail_by('P5 the line of the LABELED carton (1) put back', 'tg_pick_line_carton_removed', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 1, put_back_by = md5('demo:user:editor')::uuid,
           put_back_at = NOW(), put_back_location_code = 'HCM-A01-1-A' WHERE id = md5('qa65:pl:1')::uuid $q$);
SELECT pg_temp.expect_ok('P6 the line of the REMOVED carton (2) is put back into quarantine', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 1, put_back_by = md5('demo:user:editor')::uuid,
           put_back_at = NOW(), put_back_location_code = 'HCM-QC01' WHERE id = md5('qa65:pl:2')::uuid $q$);
SELECT pg_temp.expect_ok('P7 a line with no carton row at all (6) is put back as a cancelled order''s', $q$
    UPDATE fulfillment.pick_line SET status = 'PUT_BACK', put_back_qty = 1, put_back_by = md5('demo:user:editor')::uuid,
           put_back_at = NOW(), put_back_location_code = 'HCM-A01-1-A' WHERE id = md5('qa65:pl:6')::uuid $q$);

\echo '--- what a labelled carton may do afterwards: join a load plan and a manifest (09)'
INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, status, calculated_at, confirmed_at, confirmed_by)
VALUES (md5('qa65:plan')::uuid, 'LP-QA-65', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'CONFIRMED', NOW(), NOW(),
        md5('demo:user:editor')::uuid);
INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, vehicle_type_id, vehicle_code, vehicle_kind,
                                           inner_length_mm, inner_width_mm, inner_height_mm, max_payload_kg)
SELECT md5('qa65:veh')::uuid, md5('qa65:plan')::uuid, 1, t.id, t.code, t.kind, t.inner_length_mm, t.inner_width_mm,
       t.inner_height_mm, t.max_payload_kg
  FROM fulfillment.vehicle_type t WHERE t.code = 'TRUCK-2T5';
SELECT pg_temp.expect_ok('M1 a LABELED carton of a READY shipment is a member of the load plan', $q$
    INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, shipment_id, added_at, added_by)
    VALUES (gen_random_uuid(), md5('qa65:plan')::uuid, md5('qa65:pkg:1')::uuid, md5('qa65:ship:A')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail_by('M2 a carton not LABELED is not (a CHECKED one)', 'tg_load_plan_package_check', $q$
    INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, shipment_id, added_at, added_by)
    VALUES (gen_random_uuid(), md5('qa65:plan')::uuid, md5('qa65:pkg:5')::uuid, md5('qa65:ship:A')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('M3 the plan''s vehicle, its manifest with the shipment, the labelled carton scanned onto it', $q$
    INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status, load_plan_vehicle_id)
    VALUES (md5('qa65:load')::uuid, 'LD-QA-65', gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'PLANNED', md5('qa65:veh')::uuid);
    INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, added_at, added_by)
    VALUES (md5('qa65:ls')::uuid, md5('qa65:load')::uuid, md5('qa65:ship:A')::uuid, NOW(), md5('demo:user:editor')::uuid);
    INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by)
    VALUES (md5('qa65:lp')::uuid, md5('qa65:load')::uuid, md5('qa65:pkg:1')::uuid, md5('qa65:ship:A')::uuid,
            gen_random_uuid(), NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail_by('M4 a removed carton is not loaded', 'is not a sealed parcel or labelled carton', $q$
    INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, shipment_id, scan_id, loaded_at, loaded_by)
    VALUES (gen_random_uuid(), md5('qa65:load')::uuid, md5('qa65:pkg:2')::uuid, md5('qa65:ship:A')::uuid,
            gen_random_uuid(), NOW(), md5('demo:user:editor')::uuid) $q$);

\echo '--- the shape the migration leaves'
SELECT pg_temp.expect_true('X1 customer_order.first_labeled_at exists', EXISTS (SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'ordering' AND table_name = 'customer_order' AND column_name = 'first_labeled_at'));
SELECT pg_temp.expect_true('X2 no pack is left in a status the session does not know',
    NOT EXISTS (SELECT 1 FROM fulfillment.pack WHERE status NOT IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')));
SELECT pg_temp.expect_true('X3 a carton row always has both its line and its LPN',
    NOT EXISTS (SELECT 1 FROM fulfillment.package WHERE (pick_line_id IS NULL) <> (lpn IS NULL)));
SELECT pg_temp.expect_true('X4 cartons are found by LPN through ix_package_lpn, every LPN not null (V20261113000710)',
    EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'fulfillment' AND tablename = 'package'
               AND indexname = 'ix_package_lpn' AND indexdef LIKE '%(lpn)%' AND indexdef LIKE '%lpn IS NOT NULL%'));

ROLLBACK;
