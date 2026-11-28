-- =============================================================================
-- Contract e-signing tables (V20270129000200): the signing key stored only sealed, one per purpose; one-time codes stored
-- only as a hash, five tries, one live code per contract; forgiven not before made (V20270129000400). Needs the demo
-- seed; rolled back.
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

-- CS1: a private key stored in clear (PKCS#8 base64 starts MII) is refused.
SELECT pg_temp.expect_fail('CS1 an unsealed private key', $q$
    INSERT INTO ordering.contract_signing_key (id, purpose, certificate_der, private_key_sealed)
    VALUES (md5('qa:key:1')::uuid, 'TEST_CERT', '\x00', 'MIIEvQIBADANBgkqhkiG9w0BAQEFAASC') $q$);
SELECT pg_temp.expect_fail('CS2 an unknown purpose', $q$
    INSERT INTO ordering.contract_signing_key (id, purpose, certificate_der, private_key_sealed)
    VALUES (md5('qa:key:2')::uuid, 'REAL_CERT', '\x00', 'v1:abc') $q$);
SELECT pg_temp.expect_ok('CS3 a sealed key', $q$
    INSERT INTO ordering.contract_signing_key (id, purpose, certificate_der, private_key_sealed)
    VALUES (md5('qa:key:3')::uuid, 'TEST_CERT', '\x00', 'v1:abc') $q$);
SELECT pg_temp.expect_fail('CS4 a second key for the purpose', $q$
    INSERT INTO ordering.contract_signing_key (id, purpose, certificate_der, private_key_sealed)
    VALUES (md5('qa:key:4')::uuid, 'TEST_CERT', '\x00', 'v1:def') $q$);
SELECT pg_temp.expect_ok('CS5 a second writer racing for the purpose does nothing', $q$
    INSERT INTO ordering.contract_signing_key (id, purpose, certificate_der, private_key_sealed)
    VALUES (md5('qa:key:5')::uuid, 'TEST_CERT', '\x00', 'v1:def') ON CONFLICT (purpose) DO NOTHING $q$);

CREATE TEMP TABLE qa_target AS
SELECT (SELECT id FROM ordering.sales_contract ORDER BY id LIMIT 1) AS contract_id,
       (SELECT id FROM identity.app_user ORDER BY id LIMIT 1) AS user_id;

SELECT pg_temp.expect_ok('OT1 a code asked for', format($q$
    INSERT INTO ordering.contract_sign_otp (id, contract_id, revision, customer_user_id, email, code_hash, expires_at)
    SELECT md5('qa:otp:1')::uuid, contract_id, 1, user_id, 'a@example.com', %L, now() + interval '10 minutes'
      FROM qa_target $q$, repeat('a', 64)));
SELECT pg_temp.expect_fail('OT2 a second live code for the contract', format($q$
    INSERT INTO ordering.contract_sign_otp (id, contract_id, revision, customer_user_id, email, code_hash, expires_at)
    SELECT md5('qa:otp:2')::uuid, contract_id, 1, user_id, 'a@example.com', %L, now() + interval '10 minutes'
      FROM qa_target $q$, repeat('b', 64)));
SELECT pg_temp.expect_ok('OT3 superseding the first frees the place for another', $q$
    UPDATE ordering.contract_sign_otp SET superseded_at = now() WHERE id = md5('qa:otp:1')::uuid $q$);
SELECT pg_temp.expect_ok('OT4 and then the second is accepted', format($q$
    INSERT INTO ordering.contract_sign_otp (id, contract_id, revision, customer_user_id, email, code_hash, expires_at)
    SELECT md5('qa:otp:2')::uuid, contract_id, 1, user_id, 'a@example.com', %L, now() + interval '10 minutes'
      FROM qa_target $q$, repeat('b', 64)));
SELECT pg_temp.expect_fail('OT5 six wrong tries', $q$
    UPDATE ordering.contract_sign_otp SET attempts = 6 WHERE id = md5('qa:otp:2')::uuid $q$);
SELECT pg_temp.expect_fail('OT6 the six digits themselves stored instead of a hash', $q$
    UPDATE ordering.contract_sign_otp SET code_hash = '123456' WHERE id = md5('qa:otp:2')::uuid $q$);
SELECT pg_temp.expect_fail('OT7 a code that expires before it was made', $q$
    UPDATE ordering.contract_sign_otp SET expires_at = created_at - interval '1 second'
     WHERE id = md5('qa:otp:2')::uuid $q$);
SELECT pg_temp.expect_fail('OT8 revision zero', $q$
    UPDATE ordering.contract_sign_otp SET revision = 0 WHERE id = md5('qa:otp:2')::uuid $q$);
SELECT pg_temp.expect_fail('OT9 a code for a contract that does not exist', format($q$
    INSERT INTO ordering.contract_sign_otp (id, contract_id, revision, customer_user_id, email, code_hash, expires_at)
    SELECT md5('qa:otp:9')::uuid, md5('qa:no-such-contract')::uuid, 1, user_id, 'a@example.com', %L,
           now() + interval '10 minutes' FROM qa_target $q$, repeat('c', 64)));

-- V20270129000400: a code forgiven before it was made would let a backdated stamp hide tries made after it.
SELECT pg_temp.expect_fail('OT10 forgiven before the code was made', $q$
    UPDATE ordering.contract_sign_otp SET forgiven_at = created_at - interval '1 second'
     WHERE id = md5('qa:otp:2')::uuid $q$);
SELECT pg_temp.expect_ok('OT11 forgiven after it was made', $q$
    UPDATE ordering.contract_sign_otp SET forgiven_at = created_at + interval '1 second'
     WHERE id = md5('qa:otp:2')::uuid $q$);

ROLLBACK;
\echo 'PASS 99_contract_signing'
