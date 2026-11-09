-- =============================================================================
-- Adversarial checks + happy paths: the producer catalogue and the Production Coordinator role (V20261108002600, team
-- plan 4A-1; kltn-docs 17 §4.3, 00 §2.1 decision 2026-11-07). Rolled back at the end.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- the producers (17 §4.3)'
SELECT pg_temp.expect_fail('PR1 a code in lower case', $q$
    INSERT INTO ordering.producer (id, code, name, channel) VALUES (gen_random_uuid(), 'in-a', 'Xuong A', 'PDF') $q$);
SELECT pg_temp.expect_fail('PR2 orders by e-mail with no address', $q$
    INSERT INTO ordering.producer (id, code, name, channel) VALUES (gen_random_uuid(), 'IN-A', 'Xuong A', 'EMAIL') $q$);
SELECT pg_temp.expect_fail('PR3 an address that is not one', $q$
    INSERT INTO ordering.producer (id, code, name, channel, email)
    VALUES (gen_random_uuid(), 'IN-A', 'Xuong A', 'EMAIL', 'nobody') $q$);
SELECT pg_temp.expect_fail('PR4 a standard lead time of 0 days', $q$
    INSERT INTO ordering.producer (id, code, name, channel, standard_lead_days)
    VALUES (gen_random_uuid(), 'IN-A', 'Xuong A', 'PDF', 0) $q$);
SELECT pg_temp.expect_fail('PR5 an API channel (later scope)', $q$
    INSERT INTO ordering.producer (id, code, name, channel) VALUES (gen_random_uuid(), 'IN-A', 'Xuong A', 'API') $q$);
SELECT pg_temp.expect_ok('PR6 a producer by e-mail, 14 days by default', $q$
    INSERT INTO ordering.producer (id, code, name, channel, email)
    VALUES (md5('qa20:a')::uuid, 'IN-A', 'Xuong A', 'EMAIL', 'don@xuong-a.vn') $q$);
SELECT pg_temp.expect_true('PR6a its default lead time and status',
    (SELECT standard_lead_days = 14 AND status = 'ACTIVE' FROM ordering.producer WHERE id = md5('qa20:a')::uuid));
SELECT pg_temp.expect_fail('PR7 the same code twice', $q$
    INSERT INTO ordering.producer (id, code, name, channel) VALUES (gen_random_uuid(), 'IN-A', 'Xuong B', 'PDF') $q$);

\echo '--- who keeps them (00 §2.1, 2026-11-07)'
CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;
SELECT pg_temp.expect_true('PR8 the Production Coordinator role, every warehouse, a system role',
    (SELECT data_scope = 'ALL' AND is_system FROM identity.app_role WHERE code = 'PRODUCTION_COORDINATOR'));
SELECT pg_temp.expect_true('PR9 the coordinator keeps the catalogue; the Sales Manager, Sales and the warehouse read it',
    pg_temp.grants('PRODUCTION_COORDINATOR', 'sales-producers:CREATE')
    AND pg_temp.grants('PRODUCTION_COORDINATOR', 'sales-producers:UPDATE')
    AND pg_temp.grants('SALES_MANAGER', 'sales-producers:READ')
    AND NOT pg_temp.grants('SALES_MANAGER', 'sales-producers:UPDATE')
    AND pg_temp.grants('SALES_STAFF', 'sales-producers:READ')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'sales-producers:READ')
    AND NOT pg_temp.grants('CUSTOMER', 'sales-producers:READ'));

ROLLBACK;
