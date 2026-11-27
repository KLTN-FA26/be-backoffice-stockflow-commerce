-- =============================================================================
-- Transfers recorded from the bank feed (V20270103000100; credit plan 2026-11-26 part C, kltn-docs 15 BR-02):
-- payment.bank_transaction's checks and unique indexes, the bank feed's system user and who holds
-- payment-bank-transactions. Needs the demo seed (a customer, a recorded receipt). Rolled back at the end.
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

-- A transfer as the feed keeps it: RECEIVED, nothing decided. Columns not named are what each statement changes.
CREATE FUNCTION pg_temp.kept(key TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO payment.bank_transaction (id, provider, provider_transaction_id, source, dedupe_key, account_number,
        amount, content, bank_reference, transaction_at, received_at, raw, status, version, created_at)
    VALUES (md5('qa:bt:' || key)::uuid, 'SEPAY', 'qa-' || key, 'WEBHOOK', 'REF:QA' || upper(key), '0071001234567',
        660000, 'HD-20261108-000007-1 DOT1', 'QA' || upper(key), NOW(), NOW(), '{}'::jsonb, 'RECEIVED', 0, NOW())
$$;

\echo '--- keeping a transfer'
SELECT pg_temp.expect_ok('BT1 a transfer kept RECEIVED', $q$ SELECT pg_temp.kept('one') $q$);
SELECT pg_temp.expect_fail('BT2 the same SePay id kept twice', $q$
    INSERT INTO payment.bank_transaction (id, provider, provider_transaction_id, source, dedupe_key, amount,
        transaction_at, received_at, raw, status, version, created_at)
    VALUES (gen_random_uuid(), 'SEPAY', 'qa-one', 'CATCH_UP', 'REF:OTHER', 660000, NOW(), NOW(), '{}', 'RECEIVED', 0,
        NOW()) $q$);
SELECT pg_temp.expect_fail('BT3 the same bank reference kept twice (webhook and catch-up ids differ)', $q$
    INSERT INTO payment.bank_transaction (id, provider, provider_transaction_id, source, dedupe_key, amount,
        transaction_at, received_at, raw, status, version, created_at)
    VALUES (gen_random_uuid(), 'SEPAY', 'v2:qa-other', 'CATCH_UP', 'REF:QAONE', 660000, NOW(), NOW(), '{}',
        'RECEIVED', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('BT4 a provider other than SePay', $q$
    UPDATE payment.bank_transaction SET provider = 'CASSO' WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT5 a source other than the webhook and the catch-up', $q$
    UPDATE payment.bank_transaction SET source = 'MANUAL' WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT6 an amount of nothing (money going out is not kept)', $q$
    UPDATE payment.bank_transaction SET amount = 0 WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT7 a fraction of a dong', $q$
    UPDATE payment.bank_transaction SET amount = 660000.5 WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT8 a blank SePay id', $q$
    UPDATE payment.bank_transaction SET provider_transaction_id = ' ' WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT9 an unknown status', $q$
    UPDATE payment.bank_transaction SET status = 'MATCHED', decided_at = NOW() WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT10 RECEIVED with a decision time', $q$
    UPDATE payment.bank_transaction SET decided_at = NOW() WHERE id = md5('qa:bt:one')::uuid $q$);

\echo '--- the Accountant''s queue'
SELECT pg_temp.expect_fail('BT11 NEEDS_REVIEW without a reason', $q$
    UPDATE payment.bank_transaction SET status = 'NEEDS_REVIEW', decided_at = NOW(), review_note = 'why'
     WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT12 NEEDS_REVIEW without a note', $q$
    UPDATE payment.bank_transaction SET status = 'NEEDS_REVIEW', decided_at = NOW(), review_reason = 'OVERPAYMENT'
     WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT13 a review reason nobody translates', $q$
    UPDATE payment.bank_transaction SET status = 'NEEDS_REVIEW', decided_at = NOW(), review_reason = 'OTHER',
           review_note = 'why' WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT14 a review reason on a transfer that does not wait for review', $q$
    UPDATE payment.bank_transaction SET review_reason = 'OVERPAYMENT', review_note = 'why'
     WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_ok('BT15 to the queue with a reason and a note', $q$
    UPDATE payment.bank_transaction SET status = 'NEEDS_REVIEW', decided_at = NOW(), review_reason = 'OVERPAYMENT',
           review_note = 'brought 700000; owes 660000' WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT16 waiting for review yet reviewed by someone', $q$
    UPDATE payment.bank_transaction SET reviewed_by = (SELECT id FROM identity.app_user ORDER BY id LIMIT 1),
           reviewed_at = NOW() WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT17 IGNORED without a reason', $q$
    UPDATE payment.bank_transaction SET status = 'IGNORED', review_reason = NULL,
           reviewed_by = (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), reviewed_at = NOW()
     WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT18 IGNORED by nobody', $q$
    UPDATE payment.bank_transaction SET status = 'IGNORED', review_reason = NULL, ignore_reason = 'not a customer'
     WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT19 a blank ignore reason', $q$
    UPDATE payment.bank_transaction SET status = 'IGNORED', review_reason = NULL, ignore_reason = '  ',
           reviewed_by = (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), reviewed_at = NOW()
     WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_fail('BT20 reviewed by someone at no time', $q$
    UPDATE payment.bank_transaction SET status = 'IGNORED', review_reason = NULL, ignore_reason = 'not a customer',
           reviewed_by = (SELECT id FROM identity.app_user ORDER BY id LIMIT 1)
     WHERE id = md5('qa:bt:one')::uuid $q$);
SELECT pg_temp.expect_ok('BT21 ignored by the Accountant, with why', $q$
    UPDATE payment.bank_transaction SET status = 'IGNORED', review_reason = NULL, ignore_reason = 'not a customer',
           reviewed_by = (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), reviewed_at = NOW()
     WHERE id = md5('qa:bt:one')::uuid $q$);

\echo '--- applied: a receipt recorded from it'
SELECT pg_temp.expect_ok('BT22 a second transfer kept', $q$ SELECT pg_temp.kept('two') $q$);
SELECT pg_temp.expect_fail('BT23 APPLIED without a receipt', $q$
    UPDATE payment.bank_transaction SET status = 'APPLIED', decided_at = NOW() WHERE id = md5('qa:bt:two')::uuid $q$);
SELECT pg_temp.expect_fail('BT24 a receipt named while it waits', $q$
    UPDATE payment.bank_transaction SET receipt_id = (SELECT id FROM payment.payment_receipt ORDER BY id LIMIT 1)
     WHERE id = md5('qa:bt:two')::uuid $q$);
SELECT pg_temp.expect_fail('BT25 a receipt that does not exist', $q$
    UPDATE payment.bank_transaction SET status = 'APPLIED', decided_at = NOW(), receipt_id = gen_random_uuid()
     WHERE id = md5('qa:bt:two')::uuid $q$);
SELECT pg_temp.expect_ok('BT26 applied automatically: a receipt, nobody reviewed it', $q$
    UPDATE payment.bank_transaction SET status = 'APPLIED', decided_at = NOW(),
           receipt_id = (SELECT id FROM payment.payment_receipt ORDER BY id LIMIT 1)
     WHERE id = md5('qa:bt:two')::uuid $q$);
SELECT pg_temp.expect_ok('BT27 a third transfer kept', $q$ SELECT pg_temp.kept('three') $q$);
SELECT pg_temp.expect_fail('BT28 one receipt recorded from two transfers', $q$
    UPDATE payment.bank_transaction SET status = 'APPLIED', decided_at = NOW(),
           receipt_id = (SELECT id FROM payment.payment_receipt ORDER BY id LIMIT 1)
     WHERE id = md5('qa:bt:three')::uuid $q$);
SELECT pg_temp.expect_fail('BT29 a hint naming a customer that does not exist', $q$
    UPDATE payment.bank_transaction SET customer_id = gen_random_uuid() WHERE id = md5('qa:bt:three')::uuid $q$);
SELECT pg_temp.expect_fail('BT30 a hint naming an installment that does not exist', $q$
    UPDATE payment.bank_transaction SET installment_id = gen_random_uuid() WHERE id = md5('qa:bt:three')::uuid $q$);

\echo '--- the bank feed''s user and who works the queue'
DO $d$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM identity.app_user WHERE id = '0193c000-0000-7000-8000-00000000bf01'
                      AND status = 'DISABLED' AND password_hash IS NULL) THEN
        RAISE EXCEPTION 'FAIL  the bank feed''s system user is missing, or can sign in';
    END IF;
    IF EXISTS (SELECT 1 FROM identity.user_role WHERE user_id = '0193c000-0000-7000-8000-00000000bf01') THEN
        RAISE EXCEPTION 'FAIL  the bank feed''s system user holds a role';
    END IF;
    IF (SELECT array_agg(DISTINCT r.code ORDER BY r.code) FROM identity.role_permission rp
          JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
         WHERE p.resource = 'payment-bank-transactions') <> ARRAY['ACCOUNTANT', 'SYSTEM_ADMIN']::varchar[] THEN
        RAISE EXCEPTION 'FAIL  bank transfers are not the Accountant''s and the System Admin''s alone';
    END IF;
    RAISE NOTICE 'ok    BT31 the feed''s user signs in nowhere; the queue is the Accountant''s and the System Admin''s';
END $d$;

ROLLBACK;
\echo 'PASS 99_bank_transaction'
