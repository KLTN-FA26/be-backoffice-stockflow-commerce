-- =============================================================================
-- Adversarial checks + happy paths for the warehouse capacity board (V20261125000100; BE1; kltn-docs 06 §4.6, BR-20,
-- C43): the utilization factor is in (0, 1] - 0 and anything above 1 refused by ck_warehouse_utilization_factor, a
-- missing one refused by NOT NULL, 0.85 and 1 accepted (more than four decimals is the aggregate's to refuse: the column
-- would round) - every warehouse of the demo seed starts at 100%, and the board's two permissions are held by exactly
-- the five roles 06 §2 names.
-- Each rule is attacked by the statement that breaks only it (expect_fail_by names the rule). Needs the demo seed (its
-- warehouse HCM). Rolled back at the end.
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

-- ---- the utilization factor (C43) ------------------------------------------------------------------------------------
SELECT pg_temp.expect_true('every demo warehouse starts at 100%',
    (SELECT bool_and(utilization_factor = 1) AND count(*) > 0 FROM warehouse.warehouse));

SELECT pg_temp.expect_fail_by('a factor of 0: a warehouse that fills none of its bins', 'ck_warehouse_utilization_factor',
    $q$UPDATE warehouse.warehouse SET utilization_factor = 0 WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_fail_by('a factor above 100%', 'ck_warehouse_utilization_factor',
    $q$UPDATE warehouse.warehouse SET utilization_factor = 1.0001 WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_fail_by('a negative factor', 'ck_warehouse_utilization_factor',
    $q$UPDATE warehouse.warehouse SET utilization_factor = -0.5 WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_fail_by('no factor at all', 'utilization_factor',
    $q$UPDATE warehouse.warehouse SET utilization_factor = NULL WHERE prefix = 'HCM'$q$);

SELECT pg_temp.expect_ok('85%',
    $q$UPDATE warehouse.warehouse SET utilization_factor = 0.85 WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_true('85% is stored as 0.8500',
    (SELECT utilization_factor = 0.8500 FROM warehouse.warehouse WHERE prefix = 'HCM'));
SELECT pg_temp.expect_ok('the smallest factor the column holds, 0.0001',
    $q$UPDATE warehouse.warehouse SET utilization_factor = 0.0001 WHERE prefix = 'HCM'$q$);
SELECT pg_temp.expect_ok('back to 100%',
    $q$UPDATE warehouse.warehouse SET utilization_factor = 1 WHERE prefix = 'HCM'$q$);

SELECT pg_temp.expect_ok('a warehouse registered without a factor',
    $q$INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, version,
                                       created_at)
       VALUES (md5('qa98:warehouse')::uuid, 'QA98 kho', 'ACTIVE', 'QA98', '1 Kho', 'M', 10, 10, 0, NOW())$q$);
SELECT pg_temp.expect_true('takes 100%, the default',
    (SELECT utilization_factor = 1 FROM warehouse.warehouse WHERE prefix = 'QA98'));
SELECT pg_temp.expect_fail_by('a warehouse registered at 0%', 'ck_warehouse_utilization_factor',
    $q$INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, version,
                                       created_at, utilization_factor)
       VALUES (md5('qa98:warehouse-zero')::uuid, 'QA98 kho 0', 'ACTIVE', 'QA98Z', '1 Kho', 'M', 10, 10, 0, NOW(), 0)$q$);

-- ---- the board's permissions (06 §2) ---------------------------------------------------------------------------------
SELECT pg_temp.expect_true('warehouse-capacity:VIEW_PAGE and :READ exist',
    (SELECT count(*) = 2 FROM identity.permission
      WHERE code IN ('warehouse-capacity:VIEW_PAGE', 'warehouse-capacity:READ') AND resource = 'warehouse-capacity'));
SELECT pg_temp.expect_true('READ: the warehouse manager, Sales, the Sales Manager, the coordinator, the administrator',
    (SELECT array_agg(r.code::text ORDER BY r.code)
       FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.code = 'warehouse-capacity:READ')
    = ARRAY['PRODUCTION_COORDINATOR', 'SALES_MANAGER', 'SALES_STAFF', 'SYSTEM_ADMIN', 'WAREHOUSE_MANAGER']);
SELECT pg_temp.expect_true('VIEW_PAGE: the same five',
    (SELECT array_agg(r.code::text ORDER BY r.code)
       FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.code = 'warehouse-capacity:VIEW_PAGE')
    = ARRAY['PRODUCTION_COORDINATOR', 'SALES_MANAGER', 'SALES_STAFF', 'SYSTEM_ADMIN', 'WAREHOUSE_MANAGER']);
SELECT pg_temp.expect_true('warehouse staff and the customer read no capacity',
    NOT EXISTS (SELECT 1
                  FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.resource = 'warehouse-capacity' AND r.code IN ('WAREHOUSE_STAFF', 'CUSTOMER', 'ACCOUNTANT')));

ROLLBACK;
