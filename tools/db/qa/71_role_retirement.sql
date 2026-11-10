-- =============================================================================
-- Checks of what is left after three roles went: ORDER_COORDINATOR and INVENTORY_PLANNER (V20261115000200, team plan
-- 5-7) and PROCUREMENT_STAFF (V20261115000300, 5-8); kltn-docs 00 §2.1, §2.5. What happened to their holders is
-- RoleRetirementMigrationUpgradeTest's (it needs holders, which a freshly built demo database has none of); these are
-- the rules the migrated database itself must hold: the roles are gone, who took their work holds it, no permission is
-- left for nobody, the System Admin holds every row, and no staff account is left active without a role.
-- Needs the demo seed. Rolled back at the end.
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

CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;

\echo '--- the roles'
SELECT pg_temp.expect_true('RR1 the three roles are gone, with every grant and holding',
    NOT EXISTS (SELECT 1 FROM identity.app_role WHERE code IN ('ORDER_COORDINATOR', 'INVENTORY_PLANNER', 'PROCUREMENT_STAFF')));
SELECT pg_temp.expect_true('RR2 what is left: the seven staff roles of 00 §2.1 and the customer''s (PRODUCTION_COORDINATOR is 4A''s)',
    (SELECT array_agg(code::text ORDER BY code) FROM identity.app_role) = ARRAY['ACCOUNTANT', 'CUSTOMER',
        'PRODUCTION_COORDINATOR', 'SALES_MANAGER', 'SALES_STAFF', 'SYSTEM_ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF']);
SELECT pg_temp.expect_true('RR3 the roles that took the work say so in their description (00 §2.1)',
    (SELECT description FROM identity.app_role WHERE code = 'WAREHOUSE_MANAGER') LIKE '%order release%'
    AND (SELECT description FROM identity.app_role WHERE code = 'SALES_STAFF') LIKE '%holds%');

\echo '--- who took the work'
SELECT pg_temp.expect_true('RR4 release and hold are permissions of their own, and sales-orders:APPROVE is gone (QD-12)',
    EXISTS (SELECT 1 FROM identity.permission WHERE code = 'sales-order-releases:UPDATE')
    AND EXISTS (SELECT 1 FROM identity.permission WHERE code = 'sales-order-holds:UPDATE')
    AND NOT EXISTS (SELECT 1 FROM identity.permission WHERE code = 'sales-orders:APPROVE'));
SELECT pg_temp.expect_true('RR5 the warehouse manager releases, assigns pick tasks and records delivery outcomes; not Sales',
    pg_temp.grants('WAREHOUSE_MANAGER', 'sales-order-releases:UPDATE')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'fulfillment-task-assignment:CREATE')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'fulfillment-task-assignment:UPDATE')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'fulfillment-delivery-outcomes:APPROVE')
    AND NOT pg_temp.grants('SALES_STAFF', 'sales-order-releases:UPDATE')
    AND NOT pg_temp.grants('SALES_STAFF', 'fulfillment-task-assignment:CREATE')
    AND NOT pg_temp.grants('SALES_STAFF', 'fulfillment-delivery-outcomes:APPROVE'));
SELECT pg_temp.expect_true('RR6 Sales holds an order and asks the load estimate of a delivery; not the warehouse manager',
    pg_temp.grants('SALES_STAFF', 'sales-order-holds:UPDATE')
    AND pg_temp.grants('SALES_STAFF', 'fulfillment-load-estimates:READ')
    AND pg_temp.grants('SALES_STAFF', 'fulfillment-load-estimates:CREATE')
    AND NOT pg_temp.grants('WAREHOUSE_MANAGER', 'sales-order-holds:UPDATE'));
SELECT pg_temp.expect_true('RR7 the planner''s two exports went to the warehouse manager, whom nobody else shares them with',
    pg_temp.grants('WAREHOUSE_MANAGER', 'inventory-stock-items:EXPORT')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'inventory-stock-movements:EXPORT')
    AND NOT pg_temp.grants('SALES_STAFF', 'inventory-stock-items:EXPORT')
    AND NOT pg_temp.grants('WAREHOUSE_STAFF', 'inventory-stock-items:EXPORT'));
SELECT pg_temp.expect_true('RR8 the warehouse manager reads no sales order (all customers'' prices): released from an id, not a list',
    NOT pg_temp.grants('WAREHOUSE_MANAGER', 'sales-orders:READ'));
SELECT pg_temp.expect_true('RR9 what PROCUREMENT_STAFF did (receiving) is held by warehouse staff and the manager',
    pg_temp.grants('WAREHOUSE_STAFF', 'procurement-goods-receipts:CREATE')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'procurement-goods-receipts:CREATE')
    AND pg_temp.grants('WAREHOUSE_STAFF', 'procurement-goods-receipts:UPDATE'));

\echo '--- permissions nobody declared are gone (cleanup 8.3), the ones a sibling package keeps are not'
SELECT pg_temp.expect_true('RR10 no row of a resource no code declares',
    NOT EXISTS (SELECT 1 FROM identity.permission
                 WHERE resource IN ('warehouse-replenishment', 'inventory-allocations', 'fulfillment-return-shipments',
                                    'product-brands', 'product-categories', 'product-media', 'sales-carts',
                                    'payment-payments', 'payment-settlements', 'payment-cod-remittances')
                    OR code = 'product-products:EXPORT'));
SELECT pg_temp.expect_true('RR11 payment-refunds is kept for its own package (4B-9)',
    EXISTS (SELECT 1 FROM identity.permission WHERE resource = 'payment-refunds'));

\echo '--- the System Admin and the accounts'
SELECT pg_temp.expect_true('RR12 SYSTEM_ADMIN holds every permission row (00 §2.5.3)',
    NOT EXISTS (SELECT 1 FROM identity.permission p
                 WHERE NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                                     JOIN identity.app_role r ON r.id = rp.role_id
                                    WHERE rp.permission_id = p.id AND r.code = 'SYSTEM_ADMIN')));
SELECT pg_temp.expect_true('RR13 no active account is left without a role (00 §2.5.1)',
    NOT EXISTS (SELECT 1 FROM identity.app_user u WHERE u.status = 'ACTIVE'
                   AND NOT EXISTS (SELECT 1 FROM identity.user_role ur WHERE ur.user_id = u.id)));
SELECT pg_temp.expect_true('RR14 nobody is signed in with no role: a live session belongs to an account that holds one',
    NOT EXISTS (SELECT 1 FROM identity.user_session s
                 WHERE s.revoked_at IS NULL AND s.expires_at > NOW()
                   AND NOT EXISTS (SELECT 1 FROM identity.user_role ur WHERE ur.user_id = s.user_id)));
SELECT pg_temp.expect_true('RR15 a warehouse manager or warehouse staff is assigned a warehouse, or sees nothing (00 §2.5.3)',
    NOT EXISTS (SELECT 1 FROM identity.app_user u
                  JOIN identity.user_role ur ON ur.user_id = u.id
                  JOIN identity.app_role r ON r.id = ur.role_id
                 WHERE u.status = 'ACTIVE' AND r.code IN ('WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')
                   AND NOT EXISTS (SELECT 1 FROM identity.user_warehouse w WHERE w.user_id = u.id)));
SELECT pg_temp.expect_true('RR16 an account disabled because purchasing went has its reason on record, from flyway',
    NOT EXISTS (SELECT 1 FROM identity.user_status_change c
                 WHERE c.reason LIKE 'Purchasing from suppliers was removed%'
                   AND (c.action <> 'DISABLE' OR c.to_status <> 'DISABLED' OR c.actor_username <> 'flyway')));

ROLLBACK;
\echo 'PASS 71_role_retirement'
