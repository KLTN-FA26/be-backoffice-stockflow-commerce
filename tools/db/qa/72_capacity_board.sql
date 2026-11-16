-- =============================================================================
-- Adversarial checks + happy paths for the capacity board (V20261124000800; kltn-docs 06 §4.6, C43; Jira SCRUM-669):
-- a warehouse's utilisation factor stays in (0, 1], the demo warehouse took the default, and the new read permission
-- reached the roles that read the board. Needs the demo seed. Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail_by(label TEXT, rule TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
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

SELECT pg_temp.expect_true('the demo warehouse took the default factor 1',
    (SELECT utilization_factor = 1 FROM warehouse.warehouse WHERE prefix = 'HCM'));

SELECT pg_temp.expect_fail_by('a factor of 0 is refused', 'ck_warehouse_utilization_factor',
    $q$UPDATE warehouse.warehouse SET utilization_factor = 0 WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_fail_by('a negative factor is refused', 'ck_warehouse_utilization_factor',
    $q$UPDATE warehouse.warehouse SET utilization_factor = -0.5 WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_fail_by('a factor above 1 is refused', 'ck_warehouse_utilization_factor',
    $q$UPDATE warehouse.warehouse SET utilization_factor = 1.0001 WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_fail_by('a missing factor is refused', 'utilization_factor',
    $q$UPDATE warehouse.warehouse SET utilization_factor = NULL WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_ok('a factor of 0.85 is kept',
    $q$UPDATE warehouse.warehouse SET utilization_factor = 0.85 WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_ok('a factor of exactly 1 is kept',
    $q$UPDATE warehouse.warehouse SET utilization_factor = 1 WHERE prefix = 'HCM'$q$);

SELECT pg_temp.expect_true('the board''s readers hold warehouse-capacity:READ',
    (SELECT count(DISTINCT r.code) = 5
       FROM identity.app_role r
       JOIN identity.role_permission rp ON rp.role_id = r.id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.code = 'warehouse-capacity:READ'
        AND r.code IN ('WAREHOUSE_MANAGER', 'SALES_STAFF', 'SALES_MANAGER', 'PRODUCTION_COORDINATOR',
                       'SYSTEM_ADMIN')));
SELECT pg_temp.expect_true('nobody else holds it',
    (SELECT count(*) = 0
       FROM identity.app_role r
       JOIN identity.role_permission rp ON rp.role_id = r.id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.code = 'warehouse-capacity:READ'
        AND r.code NOT IN ('WAREHOUSE_MANAGER', 'SALES_STAFF', 'SALES_MANAGER', 'PRODUCTION_COORDINATOR',
                           'SYSTEM_ADMIN')));

ROLLBACK;
