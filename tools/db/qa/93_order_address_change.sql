-- =============================================================================
-- Adversarial checks + happy paths: an order's delivery address changed by Sales before the first label (WF04 G2,
-- V20261204006110; kltn-docs 17 §4.7 steps 23-24, BR-16; 08 BR-04). ordering.order_address_change keeps every change
-- (append-only, a VN address, an OrderStatus-shaped status, a real order and a real user), and tg_order_label_lock
-- (baseline) refuses an address change once a carton is labelled. Needs the demo seed (demo.editor). Rolled back at the
-- end.
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
END $$;

-- One change of order O, as a statement: the status, the new country and line 1 and the actor vary.
CREATE FUNCTION pg_temp.change(key TEXT, order_key TEXT, status TEXT, country TEXT, line1 TEXT,
                               actor UUID DEFAULT md5('demo:user:editor')::uuid) RETURNS TEXT
    LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO ordering.order_address_change (id, order_id, order_status, previous_recipient_name, previous_phone,
            previous_line1, previous_ward_code, previous_ward_name, previous_province_code, previous_province_name,
            previous_country_code, new_recipient_name, new_phone, new_line1, new_ward_code, new_ward_name,
            new_province_code, new_province_name, new_country_code, changed_by, changed_at)
        VALUES (md5(%L)::uuid, md5(%L)::uuid, %L, 'Kho A', '0901234567', '1 Le Loi', '26734', 'Ben Nghe', '79',
                'Ho Chi Minh', 'VN', 'Kho B', '0907654321', %L, '26737', 'Da Kao', '79', 'Ho Chi Minh', %L, %L, NOW())$f$,
        key, order_key, status, line1, country, actor)
$$;

-- Fixtures, written with triggers and foreign keys off: order O picking, not labelled; order L labelled.
SET session_replication_role = replica;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at, shipping_recipient_name, shipping_phone, shipping_line1,
                                     shipping_ward_code, shipping_ward_name, shipping_province_code,
                                     shipping_province_name, shipping_country_code, first_labeled_at)
VALUES (md5('qa93:o')::uuid, 'SO-QA93-O', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING', 1000,
        'VND', NOW(), NOW(), 'Kho A', '0901234567', '1 Le Loi', '26734', 'Ben Nghe', '79', 'Ho Chi Minh', 'VN', NULL),
       (md5('qa93:l')::uuid, 'SO-QA93-L', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING', 1000,
        'VND', NOW(), NOW(), 'Kho A', '0901234567', '1 Le Loi', '26734', 'Ben Nghe', '79', 'Ho Chi Minh', 'VN', NOW());
SET session_replication_role = DEFAULT;

\echo '--- a change is a VN address of a real order by a real user, with an OrderStatus-shaped status'
SELECT pg_temp.expect_fail('AC1 a new address outside Vietnam',
    pg_temp.change('qa93:x1', 'qa93:o', 'PICKING', 'TH', '9 Hai Ba Trung'));
SELECT pg_temp.expect_fail('AC2 a status that is no OrderStatus name',
    pg_temp.change('qa93:x2', 'qa93:o', 'picking; drop', 'VN', '9 Hai Ba Trung'));
SELECT pg_temp.expect_fail('AC3 a new address without its first line',
    pg_temp.change('qa93:x3', 'qa93:o', 'PICKING', 'VN', NULL));
SELECT pg_temp.expect_fail('AC4 a change of an order that does not exist',
    pg_temp.change('qa93:x4', 'qa93:nope', 'PICKING', 'VN', '9 Hai Ba Trung'));
SELECT pg_temp.expect_fail('AC5 a change by a user that does not exist',
    pg_temp.change('qa93:x5', 'qa93:o', 'PICKING', 'VN', '9 Hai Ba Trung', gen_random_uuid()));
SELECT pg_temp.expect_ok('AC6 the change of order O, before its first label',
    pg_temp.change('qa93:c1', 'qa93:o', 'PICKING', 'VN', '9 Hai Ba Trung'));

\echo '--- the history is kept as written'
SELECT pg_temp.expect_fail('AC7 a change rewritten', $q$
    UPDATE ordering.order_address_change SET new_line1 = 'elsewhere' WHERE id = md5('qa93:c1')::uuid $q$);
SELECT pg_temp.expect_fail('AC8 a change deleted while its order exists', $q$
    DELETE FROM ordering.order_address_change WHERE id = md5('qa93:c1')::uuid $q$);

\echo '--- the order''s address changes until its first label, never after (tg_order_label_lock, 08 BR-04)'
SELECT pg_temp.expect_ok('AC9 order O, not labelled, takes the new address', $q$
    UPDATE ordering.customer_order SET shipping_line1 = '9 Hai Ba Trung' WHERE id = md5('qa93:o')::uuid $q$);
SELECT pg_temp.expect_fail('AC10 order L, labelled, keeps its address', $q$
    UPDATE ordering.customer_order SET shipping_line1 = '9 Hai Ba Trung' WHERE id = md5('qa93:l')::uuid $q$);

ROLLBACK;
\echo 'PASS 93_order_address_change'
