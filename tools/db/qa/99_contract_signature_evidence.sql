-- =============================================================================
-- Contract e-signing evidence (V20270129000300; the contract e-signing plan of 2026-11-28, HOP-DONG-API.md "Signature
-- view" and "Public verification"):
--   SG  ordering.sales_contract_signature: a party signs by the methods it may (a customer by OTP, UPLOAD or
--       STAFF_RECORDED; the company by SYSTEM, UPLOAD or STAFF_RECORDED); a code signature names the mailbox and keeps no
--       file; a file signature keeps the file and what inspecting it found; the system's own signature has a
--       certificate and no inspection; once per party and revision; append-only, leaving only with its contract
--   VC  ordering.sales_contract.verification_code: the printed alphabet and length, unique, set once
--   OD  ordering.sales_contract.outside_document_*: the PDF to sign outside is kept whole or not at all, and never moved
-- Needs the migrations only (its own customer, order and contract) and two accounts of identity.app_user. Rolled back at
-- the end.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- Fixtures of the script's own: a customer, their SKU in cartons of 50, a DRAFT order of 20 cartons and its contract.
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qasg:customer')::uuid, 'QA signature evidence customer', 'qasg@example.com', 'ACTIVE', 0, NOW());
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qasg:product')::uuid, 'QASG', 'QA ly bang chung chu ky', 'qa-sg', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qasg:blank')::uuid, md5('qasg:product')::uuid, 'QA-SG-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
VALUES (md5('qasg:draft')::uuid, md5('qasg:customer')::uuid, md5('qasg:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb);
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qasg:snap')::uuid, md5('qasg:draft')::uuid, repeat('d', 64), 'https://example.com/d.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.customer_skus (id, product_id, sku, name, status, version, created_at, customer_id,
                                  design_snapshot_id, variant_id)
VALUES (md5('qasg:own')::uuid, md5('qasg:product')::uuid, 'QA-SG-OWN', 'QA ly in logo', 'ACTIVE', 0, NOW(),
        md5('qasg:customer')::uuid, md5('qasg:snap')::uuid, md5('qasg:blank')::uuid);
INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, active, version, created_at)
VALUES (md5('qasg:carton')::uuid, md5('qasg:blank')::uuid, 'CARTON', 50, TRUE, 0, NOW());
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
VALUES (md5('qasg:order')::uuid, 'SO-QA-SG', md5('qasg:customer')::uuid, gen_random_uuid(), 'DRAFT', 2200000, 200000,
        'VND', NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qasg:line')::uuid, md5('qasg:order')::uuid, 'QA-SG-OWN', 1000, 2000, 'VND', md5('qasg:carton')::uuid, 20,
        50, 10, 200000);

CREATE TEMP TABLE qasg_people AS
SELECT (SELECT id FROM identity.app_user ORDER BY id LIMIT 1) AS account;

INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
    seller_address, seller_representative, seller_representative_title, buyer_name, buyer_address,
    buyer_representative, buyer_representative_title, free_sample_rounds, drawn_up_by)
VALUES (md5('qasg:contract')::uuid, 'HD-QA-SG-1', md5('qasg:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2, (SELECT account FROM qasg_people));

INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qasg:b1')::uuid, md5('qasg:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (md5('qasg:b1l')::uuid, md5('qasg:b1')::uuid, md5('qasg:line')::uuid, 20);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days)
VALUES (md5('qasg:i1')::uuid, md5('qasg:contract')::uuid, 1, 'On signing', 'ON_SIGNING', 100, 2200000, 0);

\echo '--- SG: the evidence rows'
SELECT pg_temp.expect_ok('SG1 a customer signing with a code names the mailbox and keeps no file', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name, email,
        account_user_id, signed_at, document_sha256)
    VALUES (md5('qasg:s1')::uuid, md5('qasg:contract')::uuid, 1, 'CUSTOMER', 'OTP', 'Tran Van B', 'b@example.com',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64)) $q$);
SELECT pg_temp.expect_fail('SG2 a code signature without the mailbox it went to', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name,
        account_user_id, signed_at, document_sha256)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 2, 'CUSTOMER', 'OTP', 'Tran Van B',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64)) $q$);
SELECT pg_temp.expect_fail('SG3 a code signature that keeps a file', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name, email,
        account_user_id, signed_at, document_sha256, file_key)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 2, 'CUSTOMER', 'OTP', 'Tran Van B', 'b@example.com',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64), 'k') $q$);
SELECT pg_temp.expect_fail('SG4 the company signing with a one-time code', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name, email,
        account_user_id, signed_at, document_sha256)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 1, 'COMPANY', 'OTP', 'Ms A', 'a@example.com',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64)) $q$);
SELECT pg_temp.expect_fail('SG5 the customer signing with the system''s certificate', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name,
        account_user_id, signed_at, document_sha256, file_key, certificate_subject, certificate_issuer,
        certificate_serial, test_certificate)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 2, 'CUSTOMER', 'SYSTEM', 'Tran Van B',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64), 'k', 'CN=x', 'CN=y', '01', TRUE) $q$);
SELECT pg_temp.expect_fail('SG6 a second signature of the same party and revision', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name, email,
        account_user_id, signed_at, document_sha256)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 1, 'CUSTOMER', 'OTP', 'Tran Van B', 'b@example.com',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64)) $q$);
SELECT pg_temp.expect_fail('SG7 a digest that is not a SHA-256', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name, email,
        account_user_id, signed_at, document_sha256)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 2, 'CUSTOMER', 'OTP', 'Tran Van B', 'b@example.com',
            (SELECT account FROM qasg_people), NOW(), 'xyz') $q$);
SELECT pg_temp.expect_fail('SG8 a signed file kept without what inspecting it found', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name,
        account_user_id, signed_at, document_sha256, file_key)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 2, 'CUSTOMER', 'UPLOAD', 'Tran Van B',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64), 'k') $q$);
SELECT pg_temp.expect_fail('SG9 the system''s signature without its certificate', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name,
        account_user_id, signed_at, document_sha256, file_key)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 1, 'COMPANY', 'SYSTEM', 'Ms A',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64), 'k') $q$);
SELECT pg_temp.expect_fail('SG10 a file signature claiming the system''s test certificate', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name,
        account_user_id, signed_at, document_sha256, file_key, inspection, test_certificate)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 1, 'COMPANY', 'UPLOAD', 'Ms A',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64), 'k', '{"kind":"SCAN"}'::jsonb, TRUE) $q$);
SELECT pg_temp.expect_fail('SG11 an inspection that says nothing of what the file is', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name,
        account_user_id, signed_at, document_sha256, file_key, inspection)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 1, 'COMPANY', 'UPLOAD', 'Ms A',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64), 'k', '{"valid":true}'::jsonb) $q$);
SELECT pg_temp.expect_fail('SG12 a signer with no name', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name, email,
        account_user_id, signed_at, document_sha256)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 2, 'CUSTOMER', 'OTP', '   ', 'b@example.com',
            (SELECT account FROM qasg_people), NOW(), repeat('a', 64)) $q$);
SELECT pg_temp.expect_ok('SG13 the company signing with a file and an inspection', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name,
        account_user_id, signed_at, document_sha256, file_key, inspection)
    VALUES (md5('qasg:s2')::uuid, md5('qasg:contract')::uuid, 1, 'COMPANY', 'UPLOAD', 'Ms A',
            (SELECT account FROM qasg_people), NOW(), repeat('b', 64), 'k', '{"kind":"SCAN"}'::jsonb) $q$);
SELECT pg_temp.expect_fail('SG14 a signature that names no account', $q$
    INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name, email,
        account_user_id, signed_at, document_sha256)
    VALUES (gen_random_uuid(), md5('qasg:contract')::uuid, 3, 'CUSTOMER', 'OTP', 'Tran Van B', 'b@example.com',
            gen_random_uuid(), NOW(), repeat('a', 64)) $q$);

\echo '--- SG: append-only'
SELECT pg_temp.expect_fail('SG15 evidence cannot be edited', $q$
    UPDATE ordering.sales_contract_signature SET signer_name = 'Someone else' WHERE id = md5('qasg:s1')::uuid $q$);
SELECT pg_temp.expect_fail('SG16 evidence cannot be deleted by itself', $q$
    DELETE FROM ordering.sales_contract_signature WHERE id = md5('qasg:s1')::uuid $q$);

\echo '--- VC: the verification code'
SELECT pg_temp.expect_fail('VC1 a code with a character people misread (O)', $q$
    UPDATE ordering.sales_contract SET verification_code = 'ABCDEFGHJKMO' WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_fail('VC2 a code in lower case', $q$
    UPDATE ordering.sales_contract SET verification_code = 'abcdefghjkmn' WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_fail('VC3 a code of the wrong length', $q$
    UPDATE ordering.sales_contract SET verification_code = 'ABCDEFGHJK' WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_ok('VC4 a code of the printed alphabet', $q$
    UPDATE ordering.sales_contract SET verification_code = 'ABCDEFGHJKMN' WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_fail('VC5 a code, once set, never changes', $q$
    UPDATE ordering.sales_contract SET verification_code = 'ABCDEFGHJKMP' WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_true('VC6 a code is unique across contracts',
    EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_sales_contract_verification_code' AND contype = 'u'));

\echo '--- OD: the PDF to sign outside'
SELECT pg_temp.expect_fail('OD1 a key without its digest, length and page', $q$
    UPDATE ordering.sales_contract SET outside_document_key = 'k' WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_fail('OD2 a digest that is not a SHA-256', $q$
    UPDATE ordering.sales_contract SET outside_document_key = 'k', outside_document_sha256 = 'zz',
        outside_document_length = 10, outside_page_key = 'p' WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_fail('OD3 an empty document', $q$
    UPDATE ordering.sales_contract SET outside_document_key = 'k', outside_document_sha256 = repeat('c', 64),
        outside_document_length = 0, outside_page_key = 'p' WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_ok('OD4 the document kept whole', $q$
    UPDATE ordering.sales_contract SET outside_document_key = 'k', outside_document_sha256 = repeat('c', 64),
        outside_document_length = 1234, outside_page_key = 'p' WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_fail('OD5 the document, once kept, is never replaced', $q$
    UPDATE ordering.sales_contract SET outside_document_key = 'k2', outside_document_sha256 = repeat('d', 64)
     WHERE id = md5('qasg:contract')::uuid $q$);

\echo '--- SG: the evidence leaves only with its contract'
SELECT pg_temp.expect_ok('SG17 deleting the contract takes its evidence with it', $q$
    DELETE FROM ordering.sales_contract WHERE id = md5('qasg:contract')::uuid $q$);
SELECT pg_temp.expect_true('SG18 and nothing of it is left',
    NOT EXISTS (SELECT 1 FROM ordering.sales_contract_signature WHERE contract_id = md5('qasg:contract')::uuid));

ROLLBACK;
