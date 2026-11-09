-- =============================================================================
-- Checks: customers no longer order for themselves (V20261113000100, team plan 5-2; kltn-docs 13 BR-08, 14 BR-01,
-- BR-02). The cart tables and the sales-carts permission are gone, a customer holds no right to create an order, and
-- staff still do. Needs the demo seed. Read-only apart from fixtures, rolled back at the end.
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


-- The tables are gone and nothing can write to them.
SELECT pg_temp.expect_true('ordering.cart is gone', to_regclass('ordering.cart') IS NULL);
SELECT pg_temp.expect_true('ordering.cart_line is gone', to_regclass('ordering.cart_line') IS NULL);
SELECT pg_temp.expect_fail('a cart cannot be written',
    $q$INSERT INTO ordering.cart (id, guest_token) VALUES (gen_random_uuid(), 'qa-60')$q$);
SELECT pg_temp.expect_fail('a cart line cannot be written',
    $q$INSERT INTO ordering.cart_line (id, cart_id, sku, quantity) VALUES (gen_random_uuid(), gen_random_uuid(), 'X', 1)$q$);
SELECT pg_temp.expect_true('no foreign key points at a cart',
    NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.contype = 'f' AND c.confrelid::regclass::text LIKE '%cart%'));
SELECT pg_temp.expect_true('no column, index or function still names a cart table',
    NOT EXISTS (SELECT 1 FROM pg_proc WHERE prosrc ILIKE '%ordering.cart%')
    AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'ordering' AND tablename LIKE 'cart%'));

-- The permission and every grant of it are gone; no grant is left dangling.
SELECT pg_temp.expect_true('sales-carts has no permission row',
    NOT EXISTS (SELECT 1 FROM identity.permission WHERE resource = 'sales-carts' OR code LIKE 'sales-carts:%'));
SELECT pg_temp.expect_true('no role_permission points at a missing permission or role',
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                LEFT JOIN identity.permission p ON p.id = rp.permission_id
                LEFT JOIN identity.app_role r ON r.id = rp.role_id
                WHERE p.id IS NULL OR r.id IS NULL));

-- A customer follows its orders but cannot create one; staff can place an order for a customer.
SELECT pg_temp.expect_true('CUSTOMER does not hold sales-orders:CREATE',
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
                WHERE r.code = 'CUSTOMER' AND p.code = 'sales-orders:CREATE'));
SELECT pg_temp.expect_true('CUSTOMER still holds sales-orders:READ',
    EXISTS (SELECT 1 FROM identity.role_permission rp
            JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
            WHERE r.code = 'CUSTOMER' AND p.code = 'sales-orders:READ'));
SELECT pg_temp.expect_true('SALES_STAFF still holds sales-orders:CREATE',
    EXISTS (SELECT 1 FROM identity.role_permission rp
            JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
            WHERE r.code = 'SALES_STAFF' AND p.code = 'sales-orders:CREATE'));
SELECT pg_temp.expect_true('sales-orders:CREATE permission itself is kept',
    (SELECT count(*) FROM identity.permission WHERE code = 'sales-orders:CREATE') = 1);

-- The archive table still exists and takes rows of these tables (the migration wrote to it before dropping).
SELECT pg_temp.expect_ok('the archive accepts a cart row',
    $q$INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
       VALUES ('ordering.cart', gen_random_uuid(), '{"qa":60}'::jsonb)$q$);

-- A checkout-side order of a customer is still a plain order: nothing in the orders table needed the cart.
SELECT pg_temp.expect_true('customer_order has no cart column',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ordering' AND column_name ILIKE '%cart%'));

ROLLBACK;
