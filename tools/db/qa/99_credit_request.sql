-- =============================================================================
-- Adversarial checks + happy paths: credit requests (QĐ-A, V20270102000100). Needs the demo seed (a customer and at
-- least three accounts). Rolled back at the end.
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

-- Three accounts: the customer's portal account (u1), Sales (u2), the accountant (u3).
CREATE TEMP TABLE qa_cr_user ON COMMIT DROP AS
SELECT id, row_number() OVER (ORDER BY id) AS n FROM identity.app_user ORDER BY id LIMIT 3;
CREATE FUNCTION pg_temp.u(n INT) RETURNS uuid LANGUAGE sql AS $$ SELECT id FROM qa_cr_user WHERE qa_cr_user.n = $1 $$;

-- Fixture: a portal request of the first customer, waiting for Sales.
INSERT INTO payment.credit_request (id, customer_id, source, status, requested_limit, requested_term_days, reason,
    submitted_by, submitted_at)
SELECT md5('qa:cr:portal')::uuid, c.id, 'PORTAL', 'SUBMITTED', 500000000, 30, 'Mở rộng đơn hàng', pg_temp.u(1), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;

\echo '--- credit requests (V20270102000100)'
SELECT pg_temp.expect_fail('CR1 an unknown status', $q$
    UPDATE payment.credit_request SET status = 'OPEN' WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR2 an unknown source', $q$
    UPDATE payment.credit_request SET source = 'EMAIL' WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR3 a limit of zero asked', $q$
    UPDATE payment.credit_request SET requested_limit = 0 WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR4 more than 365 days to pay asked', $q$
    UPDATE payment.credit_request SET requested_term_days = 366 WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR5 a blank reason', $q$
    UPDATE payment.credit_request SET reason = '  ' WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR6 a second open request of the same customer', $q$
    INSERT INTO payment.credit_request (id, customer_id, source, status, requested_limit, requested_term_days,
        submitted_by, submitted_at, sales_note, recommended_by, recommended_at)
    SELECT gen_random_uuid(), customer_id, 'SALES', 'PENDING_APPROVAL', 100000000, 30, pg_temp.u(2), NOW(), 'ok',
           pg_temp.u(2), NOW() FROM payment.credit_request WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR7 a Sales request waiting for Sales', $q$
    UPDATE payment.credit_request SET source = 'SALES' WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR8 waiting for the accountant with no Sales opinion', $q$
    UPDATE payment.credit_request SET status = 'PENDING_APPROVAL' WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR9 an opinion without who gave it', $q$
    UPDATE payment.credit_request SET status = 'PENDING_APPROVAL', sales_note = 'tốt', recommended_at = NOW()
     WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR10 a suggested limit with no opinion', $q$
    UPDATE payment.credit_request SET sales_recommended_limit = 100 WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR11 waiting for Sales yet carrying their opinion', $q$
    UPDATE payment.credit_request SET sales_note = 'tốt', recommended_by = pg_temp.u(2), recommended_at = NOW()
     WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_ok('CR12 Sales give their opinion', $q$
    UPDATE payment.credit_request SET status = 'PENDING_APPROVAL', sales_note = 'Khách trả đúng hạn',
           sales_recommended_limit = 400000000, recommended_by = pg_temp.u(2), recommended_at = NOW()
     WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR13 waiting for the accountant but marked decided', $q$
    UPDATE payment.credit_request SET decided_by = pg_temp.u(3), decided_at = NOW()
     WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR14 approved without a limit', $q$
    UPDATE payment.credit_request SET status = 'APPROVED', decided_by = pg_temp.u(3), decided_at = NOW()
     WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR15 approved above the limit asked (plan §5 q3)', $q$
    UPDATE payment.credit_request SET status = 'APPROVED', decided_by = pg_temp.u(3), decided_at = NOW(),
           approved_limit = 500000001, approved_term_days = 30 WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR16 a limit granted without its days', $q$
    UPDATE payment.credit_request SET status = 'APPROVED', decided_by = pg_temp.u(3), decided_at = NOW(),
           approved_limit = 300000000 WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR17 decided with no one deciding it', $q$
    UPDATE payment.credit_request SET status = 'APPROVED', decided_at = NOW(), approved_limit = 300000000,
           approved_term_days = 30 WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR18 four eyes: approved by whoever filed it', $q$
    UPDATE payment.credit_request SET status = 'APPROVED', decided_by = pg_temp.u(1), decided_at = NOW(),
           approved_limit = 300000000, approved_term_days = 30 WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR19 four eyes: rejected by whoever gave Sales'' opinion', $q$
    UPDATE payment.credit_request SET status = 'REJECTED', decided_by = pg_temp.u(2), decided_at = NOW(),
           decision_note = 'Không' WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR20 rejected without a reason', $q$
    UPDATE payment.credit_request SET status = 'REJECTED', decided_by = pg_temp.u(3), decided_at = NOW()
     WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR21 rejected yet granting a limit', $q$
    UPDATE payment.credit_request SET status = 'REJECTED', decided_by = pg_temp.u(3), decided_at = NOW(),
           decision_note = 'Không', approved_limit = 1, approved_term_days = 0 WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_ok('CR22 approved by the accountant, at most what was asked', $q$
    UPDATE payment.credit_request SET status = 'APPROVED', decided_by = pg_temp.u(3), decided_at = NOW(),
           approved_limit = 500000000, approved_term_days = 45 WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_ok('CR23 decided, the customer may ask again', $q$
    INSERT INTO payment.credit_request (id, customer_id, source, status, requested_limit, requested_term_days,
        submitted_by, submitted_at, sales_note, recommended_by, recommended_at)
    SELECT md5('qa:cr:sales')::uuid, customer_id, 'SALES', 'PENDING_APPROVAL', 800000000, 30, pg_temp.u(2), NOW(),
           'Sale đề nghị', pg_temp.u(2), NOW() FROM payment.credit_request WHERE id = md5('qa:cr:portal')::uuid $q$);
SELECT pg_temp.expect_fail('CR24 a Sales request whose opinion is someone else''s', $q$
    UPDATE payment.credit_request SET recommended_by = pg_temp.u(3) WHERE id = md5('qa:cr:sales')::uuid $q$);
SELECT pg_temp.expect_ok('CR25 withdrawn by whoever filed it (no four eyes for taking it back)', $q$
    UPDATE payment.credit_request SET status = 'WITHDRAWN', decided_by = pg_temp.u(2), decided_at = NOW()
     WHERE id = md5('qa:cr:sales')::uuid $q$);
SELECT pg_temp.expect_fail('CR26 withdrawn with no one withdrawing it', $q$
    UPDATE payment.credit_request SET decided_by = NULL WHERE id = md5('qa:cr:sales')::uuid $q$);

\echo '--- credit request files'
SELECT pg_temp.expect_fail('CRF1 a file outside the documents category', $q$
    INSERT INTO payment.credit_request_file (id, credit_request_id, storage_key, file_name, content_type, size_bytes,
        uploaded_by, uploaded_at)
    VALUES (gen_random_uuid(), md5('qa:cr:portal')::uuid, 'design-assets/2026/x.pdf', 'dkkd.pdf', 'application/pdf',
        10, pg_temp.u(1), NOW()) $q$);
SELECT pg_temp.expect_fail('CRF2 an empty file', $q$
    INSERT INTO payment.credit_request_file (id, credit_request_id, storage_key, file_name, content_type, size_bytes,
        uploaded_by, uploaded_at)
    VALUES (gen_random_uuid(), md5('qa:cr:portal')::uuid, 'documents/2026/x.pdf', 'dkkd.pdf', 'application/pdf',
        0, pg_temp.u(1), NOW()) $q$);
SELECT pg_temp.expect_ok('CRF3 a document kept with the request', $q$
    INSERT INTO payment.credit_request_file (id, credit_request_id, storage_key, file_name, content_type, size_bytes,
        uploaded_by, uploaded_at)
    VALUES (gen_random_uuid(), md5('qa:cr:portal')::uuid, 'documents/2026/qa-cr.pdf', 'dkkd.pdf', 'application/pdf',
        10, pg_temp.u(1), NOW()) $q$);
SELECT pg_temp.expect_fail('CRF4 the same stored object kept twice', $q$
    INSERT INTO payment.credit_request_file (id, credit_request_id, storage_key, file_name, content_type, size_bytes,
        uploaded_by, uploaded_at)
    VALUES (gen_random_uuid(), md5('qa:cr:sales')::uuid, 'documents/2026/qa-cr.pdf', 'bctc.pdf', 'application/pdf',
        10, pg_temp.u(2), NOW()) $q$);

\echo '--- who holds what'
DO $d$ BEGIN
    IF (SELECT array_agg(r.code ORDER BY r.code) FROM identity.role_permission rp
          JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
         WHERE p.code = 'customer-credit-requests:APPROVE') <> ARRAY['ACCOUNTANT', 'SYSTEM_ADMIN']::varchar[] THEN
        RAISE EXCEPTION 'FAIL  credit requests are not decided by the accountant and the System Admin alone';
    END IF;
    IF (SELECT array_agg(r.code ORDER BY r.code) FROM identity.role_permission rp
          JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
         WHERE p.code = 'customer-credit-requests:CREATE')
       <> ARRAY['CUSTOMER', 'SALES_MANAGER', 'SALES_STAFF', 'SYSTEM_ADMIN']::varchar[] THEN
        RAISE EXCEPTION 'FAIL  credit requests are not filed by the customer and Sales alone';
    END IF;
    RAISE NOTICE 'ok    CR27 deciders and filers as seeded';
END $d$;

ROLLBACK;
\echo 'PASS 99_credit_request'
