-- =============================================================================
-- Checks of V20261213000200 (spec 2026-11-20, the Production Coordinator owns production):
--   - the coordinator reviews the customers' designs (kltn-docs 12 §4.4, §4.10), the Sales Manager no longer does;
--   - production orders are sent without approval: production-orders:APPROVE is gone, grants and row (17 §4.3);
--   - the Sales Manager is told of a production order sent (17 §2): production-order-sends:READ.
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

\echo '--- design review'
SELECT pg_temp.expect_true('CP1 the coordinator reviews the customers'' designs, the Sales Manager no longer does',
    pg_temp.holders('design-designs:APPROVE') = ARRAY['PRODUCTION_COORDINATOR', 'SYSTEM_ADMIN']);
SELECT pg_temp.expect_true('CP2 the coordinator opens and reads what the review screens show',
    'PRODUCTION_COORDINATOR' = ANY (pg_temp.holders('design-designs:READ'))
    AND 'PRODUCTION_COORDINATOR' = ANY (pg_temp.holders('design-designs:VIEW_PAGE')));
SELECT pg_temp.expect_true('CP3 the coordinator still neither creates nor edits a customer''s design',
    NOT 'PRODUCTION_COORDINATOR' = ANY (pg_temp.holders('design-designs:CREATE'))
    AND NOT 'PRODUCTION_COORDINATOR' = ANY (pg_temp.holders('design-designs:UPDATE')));

\echo '--- production orders'
SELECT pg_temp.expect_true('CP4 nobody approves a production order: no row production-orders:APPROVE',
    NOT EXISTS (SELECT 1 FROM identity.permission WHERE code = 'production-orders:APPROVE'));
SELECT pg_temp.expect_true('CP5 the Sales Manager and the administrator are told of production orders sent',
    pg_temp.holders('production-order-sends:READ') = ARRAY['SALES_MANAGER', 'SYSTEM_ADMIN']);
SELECT pg_temp.expect_true('CP6 production-order-sends has READ only',
    (SELECT array_agg(action::text ORDER BY action) FROM identity.permission WHERE resource = 'production-order-sends')
        = ARRAY['READ']);

\echo '--- both'
SELECT pg_temp.expect_true('CP7 the System Admin still holds every permission row (00 §2.5.3)',
    NOT EXISTS (SELECT 1 FROM identity.permission p
                 WHERE NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                                     JOIN identity.app_role r ON r.id = rp.role_id
                                    WHERE r.code = 'SYSTEM_ADMIN' AND rp.permission_id = p.id)));

ROLLBACK;
