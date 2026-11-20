-- =============================================================================
-- Checks of the permission review of 2026-11-20 (A2, A4):
--   V20261209000300 - the lot trace report has its own permission, read by the warehouse, Sales and the Production
--                     Coordinator, while the stock ledger stays the warehouse's (kltn-docs 20 §3);
--   V20261209000400 - the coordinator reads the delay alerts, and late deliveries are for those who follow delivery
--                     only (kltn-docs 17 §3, BR-30).
-- Rolled back at the end.
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

\echo '--- A2: the lot trace report'
SELECT pg_temp.expect_true('PR7 inventory-lot-traces has exactly VIEW_PAGE and READ',
    (SELECT array_agg(action::text ORDER BY action) FROM identity.permission WHERE resource = 'inventory-lot-traces')
        = ARRAY['READ', 'VIEW_PAGE']);
SELECT pg_temp.expect_true('PR8 the warehouse, Sales and the coordinator trace a lot',
    pg_temp.holders('inventory-lot-traces:READ') = ARRAY['PRODUCTION_COORDINATOR', 'SALES_STAFF', 'SYSTEM_ADMIN',
        'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF']
    AND pg_temp.holders('inventory-lot-traces:VIEW_PAGE') = pg_temp.holders('inventory-lot-traces:READ'));
SELECT pg_temp.expect_true('PR9 the stock ledger itself stays the warehouse''s',
    pg_temp.holders('inventory-stock-movements:READ') = ARRAY['SYSTEM_ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF']);
SELECT pg_temp.expect_true('PR10 every role, custom ones too, that reads the ledger also traces a lot',
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.code = 'inventory-stock-movements:READ'
                   AND NOT EXISTS (SELECT 1 FROM identity.role_permission t
                                     JOIN identity.permission q ON q.id = t.permission_id
                                    WHERE t.role_id = rp.role_id AND q.code = 'inventory-lot-traces:READ')));

\echo '--- A4: the delay alerts'
SELECT pg_temp.expect_true('PR11 the coordinator reads the delay alerts',
    pg_temp.holders('delay-alerts:READ') = ARRAY['PRODUCTION_COORDINATOR', 'SALES_MANAGER', 'SALES_STAFF',
        'SYSTEM_ADMIN', 'WAREHOUSE_MANAGER']);
SELECT pg_temp.expect_true('PR12 late deliveries are not the coordinator''s (BR-30)',
    pg_temp.holders('delivery-delay-alerts:READ') = ARRAY['SALES_MANAGER', 'SALES_STAFF', 'SYSTEM_ADMIN',
        'WAREHOUSE_MANAGER']);

\echo '--- both'
SELECT pg_temp.expect_true('PR13 the System Admin still holds every permission row (00 §2.5.3)',
    NOT EXISTS (SELECT 1 FROM identity.permission p
                 WHERE NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                                     JOIN identity.app_role r ON r.id = rp.role_id
                                    WHERE r.code = 'SYSTEM_ADMIN' AND rp.permission_id = p.id)));

ROLLBACK;
