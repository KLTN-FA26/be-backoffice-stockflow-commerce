-- =============================================================================
-- B2B customer onboarding (SCRUM-429/450, V20261014000100; kltn-docs 18 §4.1, §5, BR-01).
-- Needs the demo seed. Rolled back at the end.
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
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at)
VALUES (md5('qa:b2b:contact1')::uuid, 'qa.contact1@example.test', 'qa.contact1@example.test', NULL, 'ACTIVE', 0, NOW()),
       (md5('qa:b2b:contact2')::uuid, 'qa.contact2@example.test', 'qa.contact2@example.test', NULL, 'ACTIVE', 0, NOW());

\echo '--- customers: tax code, pending activation (customer.customer)'
SELECT pg_temp.expect_ok('B1 a pending customer with a normalised tax code and its account', $q$
    INSERT INTO customer.customer (id, user_id, full_name, email, status, tax_code, version, created_at)
    VALUES (md5('qa:b2b:c1')::uuid, md5('qa:b2b:contact1')::uuid, 'QA Co', 'qa.contact1@example.test',
            'PENDING_ACTIVATION', '0312345678', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('B2 a second live customer with the same tax code', $q$
    INSERT INTO customer.customer (id, user_id, full_name, email, status, tax_code, version, created_at)
    VALUES (gen_random_uuid(), md5('qa:b2b:contact2')::uuid, 'QA Copy', 'qa.contact2@example.test',
            'ACTIVE', '0312345678', 0, NOW()) $q$);
SELECT pg_temp.expect_ok('B3 a closed customer may share it (BR-01 covers live customers)', $q$
    INSERT INTO customer.customer (id, full_name, email, status, tax_code, version, created_at)
    VALUES (gen_random_uuid(), 'QA Closed', 'qa.closed@example.test', 'INACTIVE', '0312345678', 0, NOW()) $q$);
SELECT pg_temp.expect_ok('B4 a dependent unit 10-3 is a different code', $q$
    INSERT INTO customer.customer (id, full_name, email, status, tax_code, version, created_at)
    VALUES (gen_random_uuid(), 'QA Branch', 'qa.branch@example.test', 'ACTIVE', '0312345678-001', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('B5 a tax code in another spelling', $q$
    INSERT INTO customer.customer (id, full_name, email, status, tax_code, version, created_at)
    VALUES (gen_random_uuid(), 'QA Spelling', 'qa.spelling@example.test', 'ACTIVE', '0312345678001', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('B6 a pending customer without an account to activate', $q$
    INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
    VALUES (gen_random_uuid(), 'QA None', 'qa.none@example.test', 'PENDING_ACTIVATION', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('B7 a status outside the lifecycle', $q$
    UPDATE customer.customer SET status = 'SUSPENDED' WHERE id = md5('qa:b2b:c1')::uuid $q$);
SELECT pg_temp.expect_ok('B8 an existing customer without a tax code stays valid', $q$
    UPDATE customer.customer SET full_name = full_name WHERE tax_code IS NULL $q$);

\echo '--- invitations (identity.portal_invitation)'
SELECT pg_temp.expect_ok('B9 an open invitation stores a digest', $q$
    INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at)
    VALUES (md5('qa:b2b:inv1')::uuid, md5('qa:b2b:contact1')::uuid, md5('a') || md5('b'), NOW(), NOW() + INTERVAL '3 days') $q$);
SELECT pg_temp.expect_fail('B10 a second open invitation for one account', $q$
    INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at)
    VALUES (gen_random_uuid(), md5('qa:b2b:contact1')::uuid, md5('c') || md5('d'), NOW(), NOW() + INTERVAL '3 days') $q$);
SELECT pg_temp.expect_fail('B11 a raw token instead of a digest', $q$
    INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at)
    VALUES (gen_random_uuid(), md5('qa:b2b:contact2')::uuid, repeat('Z', 64), NOW(), NOW() + INTERVAL '3 days') $q$);
SELECT pg_temp.expect_fail('B12 an invitation that expires when issued', $q$
    INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at)
    VALUES (gen_random_uuid(), md5('qa:b2b:contact2')::uuid, md5('e') || md5('f'), NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('B13 consumed and revoked', $q$
    UPDATE identity.portal_invitation SET consumed_at = issued_at, revoked_at = issued_at
    WHERE id = md5('qa:b2b:inv1')::uuid $q$);
SELECT pg_temp.expect_fail('B14 consumed at the expiry instant', $q$
    UPDATE identity.portal_invitation SET consumed_at = expires_at WHERE id = md5('qa:b2b:inv1')::uuid $q$);
SELECT pg_temp.expect_fail('B15 a delivery outcome without its time', $q$
    UPDATE identity.portal_invitation SET delivery_status = 'SENT' WHERE id = md5('qa:b2b:inv1')::uuid $q$);
SELECT pg_temp.expect_fail('B16 an invitation for an account that does not exist', $q$
    INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at)
    VALUES (gen_random_uuid(), gen_random_uuid(), md5('g') || md5('h'), NOW(), NOW() + INTERVAL '3 days') $q$);
SELECT pg_temp.expect_ok('B17 revoke, then a new open invitation', $q$
    UPDATE identity.portal_invitation SET revoked_at = NOW(), delivery_status = 'FAILED', delivery_attempted_at = NOW()
    WHERE id = md5('qa:b2b:inv1')::uuid;
    INSERT INTO identity.portal_invitation (id, user_id, token_digest, issued_at, expires_at)
    VALUES (gen_random_uuid(), md5('qa:b2b:contact1')::uuid, md5('i') || md5('j'), NOW(), NOW() + INTERVAL '3 days') $q$);

\echo '--- grants'
SELECT pg_temp.expect_ok('B18 customer-customers:CREATE for Sales, e-commerce admin and system admin only', $q$
    DO $d$ BEGIN
        IF (SELECT array_agg(r.code ORDER BY r.code) FROM identity.role_permission rp
            JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
            WHERE p.code = 'customer-customers:CREATE') <> ARRAY['ECOMMERCE_ADMIN', 'SALES_STAFF', 'SYSTEM_ADMIN']::varchar[]
        THEN RAISE EXCEPTION 'unexpected holders'; END IF;
    END $d$ $q$);

ROLLBACK;
\echo 'PASS 09_customer_onboarding'
