-- =============================================================================
-- Checks of the permission review of 2026-11-20 (A1, A3):
--   V20261209000100 - the actuator is the System Admin's only (kltn-docs 00 §2.1);
--   V20261209000200 - the Accountant reads stock adjustments but does not decide them (kltn-docs 20 §3, BR-05).
-- Both moved grants of a system role, so each also checks the System Admin still holds every permission row. Rolled
-- back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

CREATE FUNCTION pg_temp.holders(code TEXT) RETURNS TEXT[] LANGUAGE sql AS $$
    SELECT array_agg(r.code::text ORDER BY r.code)
      FROM identity.role_permission rp
      JOIN identity.app_role r ON r.id = rp.role_id
      JOIN identity.permission p ON p.id = rp.permission_id
     WHERE p.code = $1 AND r.is_system
$$;

\echo '--- A1: the actuator'
SELECT pg_temp.expect_true('PR1 platform-actuator:READ is the System Admin''s only',
    pg_temp.holders('platform-actuator:READ') = ARRAY['SYSTEM_ADMIN']);
SELECT pg_temp.expect_true('PR2 platform-actuator:UPDATE is the System Admin''s only',
    pg_temp.holders('platform-actuator:UPDATE') = ARRAY['SYSTEM_ADMIN']);

\echo '--- A3: the Accountant and stock adjustments'
SELECT pg_temp.expect_true('PR3 the Accountant reads stock adjustments (VIEW_PAGE, READ)',
    'ACCOUNTANT' = ANY (pg_temp.holders('inventory-stock-adjustments:READ'))
    AND 'ACCOUNTANT' = ANY (pg_temp.holders('inventory-stock-adjustments:VIEW_PAGE')));
SELECT pg_temp.expect_true('PR4 deciding an adjustment stays with the warehouse manager (and the admin)',
    pg_temp.holders('inventory-stock-adjustments:APPROVE') = ARRAY['SYSTEM_ADMIN', 'WAREHOUSE_MANAGER']);
SELECT pg_temp.expect_true('PR5 the Accountant does not operate stock: no inventory write of any kind',
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE r.code = 'ACCOUNTANT' AND p.resource LIKE 'inventory-%'
                   AND p.action NOT IN ('READ', 'VIEW_PAGE')));

\echo '--- both'
SELECT pg_temp.expect_true('PR6 the System Admin still holds every permission row (00 §2.5.3)',
    NOT EXISTS (SELECT 1 FROM identity.permission p
                 WHERE NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                                     JOIN identity.app_role r ON r.id = rp.role_id
                                    WHERE r.code = 'SYSTEM_ADMIN' AND rp.permission_id = p.id)));

ROLLBACK;
