-- =============================================================================
-- Contract content, terms review, revisions, the signing deadline and CUSTOMER_SIGNED (V20270129000100; the contract
-- e-signing plan of 2026-11-28, HOP-DONG-API.md "Contract content (BE-A)" and "Lifecycle"; the two new permissions of
-- V20270129000110). Needs the migrations only (its own customer, order and contract) and two accounts of
-- identity.app_user. Rolled back at the end.
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

-- Fixtures of the script's own, as 99_contract_credit_review.sql builds them: a customer, their SKU in cartons of 50, a
-- DRAFT order of 20 cartons at 2,000 a piece with 10% VAT (2,200,000 with tax) and its draft contract.
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qacl:customer')::uuid, 'QA contract content customer', 'qacl@example.com', 'ACTIVE', 0, NOW());
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qacl:product')::uuid, 'QACL', 'QA ly noi dung hop dong', 'qa-cl', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qacl:blank')::uuid, md5('qacl:product')::uuid, 'QA-CL-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
VALUES (md5('qacl:draft')::uuid, md5('qacl:customer')::uuid, md5('qacl:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb);
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qacl:snap')::uuid, md5('qacl:draft')::uuid, repeat('d', 64), 'https://example.com/d.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.customer_skus (id, product_id, sku, name, status, version, created_at, customer_id,
                                  design_snapshot_id, variant_id)
VALUES (md5('qacl:own')::uuid, md5('qacl:product')::uuid, 'QA-CL-OWN', 'QA ly in logo', 'ACTIVE', 0, NOW(),
        md5('qacl:customer')::uuid, md5('qacl:snap')::uuid, md5('qacl:blank')::uuid);
INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, active, version, created_at)
VALUES (md5('qacl:carton')::uuid, md5('qacl:blank')::uuid, 'CARTON', 50, TRUE, 0, NOW());
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
VALUES (md5('qacl:order')::uuid, 'SO-QA-CL', md5('qacl:customer')::uuid, gen_random_uuid(), 'DRAFT', 2200000, 200000,
        'VND', NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qacl:line')::uuid, md5('qacl:order')::uuid, 'QA-CL-OWN', 1000, 2000, 'VND', md5('qacl:carton')::uuid, 20,
        50, 10, 200000);

CREATE TEMP TABLE qacl_people AS
SELECT (SELECT id FROM identity.app_user ORDER BY id LIMIT 1) AS sales,
       (SELECT id FROM identity.app_user ORDER BY id DESC LIMIT 1) AS manager;
DO $d$ BEGIN
    IF (SELECT sales = manager FROM qacl_people) THEN
        RAISE EXCEPTION 'FAIL  the checks below need two accounts';
    END IF;
END $d$;

INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
    seller_address, seller_representative, seller_representative_title, buyer_name, buyer_address,
    buyer_representative, buyer_representative_title, free_sample_rounds, drawn_up_by)
VALUES (md5('qacl:contract')::uuid, 'HD-QA-CL-1', md5('qacl:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2, (SELECT sales FROM qacl_people));
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qacl:b1')::uuid, md5('qacl:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (md5('qacl:b1l')::uuid, md5('qacl:b1')::uuid, md5('qacl:line')::uuid, 20);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days)
VALUES (md5('qacl:i1')::uuid, md5('qacl:contract')::uuid, 1, 'On signing', 'ON_SIGNING', 100, 2200000, 0);

SELECT pg_temp.expect_true('CL0 a new contract is revision 1, standard, with no review, deadline or customer signature',
    (SELECT revision = 1 AND NOT non_standard_terms AND terms_review = 'NONE' AND sign_by IS NULL
            AND customer_signed_at IS NULL AND special_terms IS NULL AND penalty_percent IS NULL
       FROM ordering.sales_contract WHERE id = md5('qacl:contract')::uuid));

\echo '--- the content: the terms, their caps, the signers'
SELECT pg_temp.expect_fail('CL1 a penalty above the 8% of Luat Thuong mai Dieu 301', $q$
    UPDATE ordering.sales_contract SET penalty_percent = 9, non_standard_terms = TRUE
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL2 a negative penalty', $q$
    UPDATE ordering.sales_contract SET penalty_percent = -1, non_standard_terms = TRUE
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL3 a quantity tolerance above 10%', $q$
    UPDATE ordering.sales_contract SET quantity_tolerance_percent = 11, non_standard_terms = TRUE
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL4 a warranty of more than 36 months', $q$
    UPDATE ordering.sales_contract SET warranty_months = 37, non_standard_terms = TRUE
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL5 special terms of blanks only', $q$
    UPDATE ordering.sales_contract SET special_terms = '   ', non_standard_terms = TRUE
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL6 special terms of 4001 characters', $q$
    UPDATE ordering.sales_contract SET special_terms = repeat('x', 4001), non_standard_terms = TRUE
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL7 special terms on a contract not marked non-standard', $q$
    UPDATE ordering.sales_contract SET special_terms = 'Free replacement within 30 days'
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL8 a signer title with no signer name', $q$
    UPDATE ordering.sales_contract SET seller_signer_title = 'Deputy director'
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL9 an authorization reference with no signer name', $q$
    UPDATE ordering.sales_contract SET seller_signer_authorization_ref = '12/UQ-2026'
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL10 a buyer signer title with no name', $q$
    UPDATE ordering.sales_contract SET buyer_signer_title = 'Owner' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL11 a blank signer name', $q$
    UPDATE ordering.sales_contract SET buyer_signer_name = ' ' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL12 an authorization reference of 101 characters', $q$
    UPDATE ordering.sales_contract SET seller_signer_name = 'Ms C', seller_signer_authorization_ref = repeat('a', 101)
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_ok('CL13 special terms, a penalty of 5% and a signer under an authorization', $q$
    UPDATE ordering.sales_contract
       SET special_terms = 'Free replacement of damaged cartons within 30 days', penalty_percent = 5,
           non_standard_terms = TRUE, seller_signer_name = 'Ms C', seller_signer_title = 'Deputy director',
           seller_signer_authorization_ref = '12/UQ-2026', buyer_signer_name = 'Mr D', buyer_signer_title = 'Owner'
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL14 a revision that is not at least 1', $q$
    UPDATE ordering.sales_contract SET revision = 0 WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL15 a draft''s revision moved on its own', $q$
    UPDATE ordering.sales_contract SET revision = 2 WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL16 who drew it up changed', $q$
    UPDATE ordering.sales_contract SET drawn_up_by = (SELECT manager FROM qacl_people)
     WHERE id = md5('qacl:contract')::uuid $q$);

\echo '--- the terms review (QĐ-4)'
SELECT pg_temp.expect_fail('CL20 an unknown review state', $q$
    UPDATE ordering.sales_contract SET terms_review = 'MAYBE' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL21 waiting without who asked', $q$
    UPDATE ordering.sales_contract SET terms_review = 'PENDING' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL22 approved without who decided', $q$
    UPDATE ordering.sales_contract SET terms_review = 'APPROVED' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL23 rejected without a reason', $q$
    UPDATE ordering.sales_contract SET terms_review = 'REJECTED', terms_reviewed_by = (SELECT manager FROM qacl_people),
           terms_reviewed_at = NOW() WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL24 a blank rejection reason', $q$
    UPDATE ordering.sales_contract SET terms_review = 'REJECTED', terms_review_note = '  ',
           terms_reviewed_by = (SELECT manager FROM qacl_people), terms_reviewed_at = NOW()
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL25 a note on a review nobody decided', $q$
    UPDATE ordering.sales_contract SET terms_review_note = 'x' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_ok('CL26 the draft waits for a Sales Manager', $q$
    UPDATE ordering.sales_contract SET terms_review = 'PENDING', terms_review_requested_by = (SELECT sales FROM qacl_people),
           terms_review_requested_at = NOW() WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL27 sent while the manager has it', $q$
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = (SELECT sales FROM qacl_people),
           sent_document_key = 'qa/cl-1.pdf', sign_by = CURRENT_DATE + 7 WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_ok('CL28 approved by the manager', $q$
    UPDATE ordering.sales_contract SET terms_review = 'APPROVED', terms_reviewed_by = (SELECT manager FROM qacl_people),
           terms_reviewed_at = NOW() WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_ok('CL29 an approved draft, taken back by an edit, is NONE again', $q$
    UPDATE ordering.sales_contract SET terms_review = 'NONE', terms_reviewed_by = NULL, terms_reviewed_at = NULL,
           terms_review_requested_by = NULL, terms_review_requested_at = NULL WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL30 a non-standard contract sent without the manager''s approval', $q$
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = (SELECT sales FROM qacl_people),
           sent_document_key = 'qa/cl-1.pdf', sign_by = CURRENT_DATE + 7 WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_fail('CL31 a review on a contract that is not non-standard', $q$
    UPDATE ordering.sales_contract SET special_terms = NULL, penalty_percent = NULL, non_standard_terms = FALSE,
           terms_review = 'REJECTED', terms_review_note = 'no', terms_reviewed_by = (SELECT manager FROM qacl_people),
           terms_reviewed_at = NOW() WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_ok('CL32 rejected with a reason', $q$
    UPDATE ordering.sales_contract SET terms_review = 'REJECTED', terms_review_note = 'The clause is not ours to give',
           terms_reviewed_by = (SELECT manager FROM qacl_people), terms_reviewed_at = NOW(),
           terms_review_requested_by = (SELECT sales FROM qacl_people), terms_review_requested_at = NOW()
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_ok('CL33 submitted again, then approved', $q$
    UPDATE ordering.sales_contract SET terms_review = 'PENDING', terms_review_note = NULL, terms_reviewed_by = NULL,
           terms_reviewed_at = NULL WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.sales_contract SET terms_review = 'APPROVED', terms_reviewed_by = (SELECT manager FROM qacl_people),
           terms_reviewed_at = NOW() WHERE id = md5('qacl:contract')::uuid $q$);

\echo '--- sent: the deadline, the revision, locked content'
SELECT pg_temp.expect_fail('CL40 a deadline on a draft', $q$
    UPDATE ordering.sales_contract SET sign_by = CURRENT_DATE + 7 WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL41 sent with a deadline before the day it was sent', $q$
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = (SELECT sales FROM qacl_people),
           sent_document_key = 'qa/cl-1.pdf', sign_by = CURRENT_DATE - 1 WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_ok('CL42 sent with the manager''s approval, its PDF and a deadline of seven days', $q$
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = (SELECT sales FROM qacl_people),
           sent_document_key = 'qa/cl-1.pdf', sign_by = CURRENT_DATE + 7 WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qacl:order')::uuid;
    INSERT INTO ordering.sales_contract_revision (contract_id, revision, sent_document_key, sent_at, sent_by, created_at)
    VALUES (md5('qacl:contract')::uuid, 1, 'qa/cl-1.pdf', NOW(), (SELECT sales FROM qacl_people), NOW()) $q$);
SELECT pg_temp.expect_fail('CL43 a sent contract''s special terms changed', $q$
    UPDATE ordering.sales_contract SET special_terms = 'Something else entirely' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL44 a sent contract''s penalty changed', $q$
    UPDATE ordering.sales_contract SET penalty_percent = 2 WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL45 a sent contract''s signer changed', $q$
    UPDATE ordering.sales_contract SET buyer_signer_name = 'Someone else' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL46 a sent contract marked standard to skip what was approved', $q$
    UPDATE ordering.sales_contract SET non_standard_terms = FALSE WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL47 a sent contract''s approval taken back', $q$
    UPDATE ordering.sales_contract SET terms_review = 'NONE', terms_reviewed_by = NULL, terms_reviewed_at = NULL,
           terms_review_requested_by = NULL, terms_review_requested_at = NULL WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL48 a sent contract''s revision moved without being reopened', $q$
    UPDATE ordering.sales_contract SET revision = 2 WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL49 a sent contract''s deadline put before it was sent', $q$
    UPDATE ordering.sales_contract SET sign_by = CURRENT_DATE - 3 WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_ok('CL50 Sales extend the deadline while it is sent', $q$
    UPDATE ordering.sales_contract SET sign_by = CURRENT_DATE + 30 WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL51 a signature time on a contract still SENT', $q$
    UPDATE ordering.sales_contract SET customer_signed_at = NOW() WHERE id = md5('qacl:contract')::uuid $q$);

\echo '--- the customer asks for changes: SENT -> DRAFT, revision + 1'
SELECT pg_temp.expect_fail('CL60 reopened with the sent PDF still on it', $q$
    UPDATE ordering.sales_contract SET status = 'DRAFT', revision = 2, sign_by = NULL
     WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'DRAFT' WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_fail('CL61 reopened without the next revision number', $q$
    UPDATE ordering.sales_contract SET status = 'DRAFT', sent_at = NULL, sent_by = NULL, sent_document_key = NULL,
           sign_by = NULL WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'DRAFT' WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_fail('CL62 reopened skipping a revision number', $q$
    UPDATE ordering.sales_contract SET status = 'DRAFT', revision = 3, sent_at = NULL, sent_by = NULL,
           sent_document_key = NULL, sign_by = NULL WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'DRAFT' WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_fail('CL63 reopened with the deadline kept (a draft has none)', $q$
    UPDATE ordering.sales_contract SET status = 'DRAFT', revision = 2, sent_at = NULL, sent_by = NULL,
           sent_document_key = NULL WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'DRAFT' WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_fail('CL64 reopened while its order still waits for a contract that is sent', $q$
    UPDATE ordering.sales_contract SET status = 'DRAFT', revision = 2, sent_at = NULL, sent_by = NULL,
           sent_document_key = NULL, sign_by = NULL WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL65 reopened with its content changed in the same step', $q$
    UPDATE ordering.sales_contract SET status = 'DRAFT', revision = 2, sent_at = NULL, sent_by = NULL,
           sent_document_key = NULL, sign_by = NULL, special_terms = 'Changed on the way back'
     WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'DRAFT' WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_ok('CL66 reopened as revision 2, the order back to a draft; the request kept on revision 1', $q$
    UPDATE ordering.sales_contract SET status = 'DRAFT', revision = 2, sent_at = NULL, sent_by = NULL,
           sent_document_key = NULL, sign_by = NULL WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'DRAFT' WHERE id = md5('qacl:order')::uuid;
    UPDATE ordering.sales_contract_revision SET change_request_reason = 'Please split the goods in two deliveries',
           change_requested_at = NOW(), change_requested_by = (SELECT sales FROM qacl_people)
     WHERE contract_id = md5('qacl:contract')::uuid AND revision = 1 $q$);
SELECT pg_temp.expect_true('CL67 the approval stays through the reopening',
    (SELECT terms_review = 'APPROVED' AND revision = 2 AND status = 'DRAFT' AND sent_at IS NULL
       FROM ordering.sales_contract WHERE id = md5('qacl:contract')::uuid));

\echo '--- the revision rows'
SELECT pg_temp.expect_fail('CL70 a second row for the same revision', $q$
    INSERT INTO ordering.sales_contract_revision (contract_id, revision, sent_document_key, sent_at, sent_by, created_at)
    VALUES (md5('qacl:contract')::uuid, 1, 'qa/other.pdf', NOW(), (SELECT sales FROM qacl_people), NOW()) $q$);
SELECT pg_temp.expect_fail('CL71 a revision number of 0', $q$
    INSERT INTO ordering.sales_contract_revision (contract_id, revision, sent_document_key, sent_at, sent_by, created_at)
    VALUES (md5('qacl:contract')::uuid, 0, 'qa/other.pdf', NOW(), (SELECT sales FROM qacl_people), NOW()) $q$);
SELECT pg_temp.expect_fail('CL72 the PDF a revision was sent as, swapped', $q$
    UPDATE ordering.sales_contract_revision SET sent_document_key = 'qa/swapped.pdf'
     WHERE contract_id = md5('qacl:contract')::uuid AND revision = 1 $q$);
SELECT pg_temp.expect_fail('CL73 a change request rewritten', $q$
    UPDATE ordering.sales_contract_revision SET change_request_reason = 'Something else entirely, said later'
     WHERE contract_id = md5('qacl:contract')::uuid AND revision = 1 $q$);
SELECT pg_temp.expect_fail('CL76 a revision of a contract that does not exist', $q$
    INSERT INTO ordering.sales_contract_revision (contract_id, revision, sent_document_key, sent_at, sent_by, created_at)
    VALUES (gen_random_uuid(), 1, 'qa/other.pdf', NOW(), (SELECT sales FROM qacl_people), NOW()) $q$);
SELECT pg_temp.expect_ok('CL77 the deadline notice is marked once', $q$
    UPDATE ordering.sales_contract_revision SET sign_deadline_notified_at = NOW()
     WHERE contract_id = md5('qacl:contract')::uuid AND revision = 1 $q$);
SELECT pg_temp.expect_fail('CL78 and not marked again', $q$
    UPDATE ordering.sales_contract_revision SET sign_deadline_notified_at = NOW() + interval '1 day'
     WHERE contract_id = md5('qacl:contract')::uuid AND revision = 1 $q$);

\echo '--- revision 2: sent again, signed by the customer, then by the company'
SELECT pg_temp.expect_ok('CL80 revision 2 sent (the manager''s approval still stands)', $q$
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = (SELECT sales FROM qacl_people),
           sent_document_key = 'qa/cl-2.pdf', sign_by = CURRENT_DATE + 7 WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qacl:order')::uuid;
    INSERT INTO ordering.sales_contract_revision (contract_id, revision, sent_document_key, sent_at, sent_by, created_at)
    VALUES (md5('qacl:contract')::uuid, 2, 'qa/cl-2.pdf', NOW(), (SELECT sales FROM qacl_people), NOW()) $q$);
SELECT pg_temp.expect_fail('CL74 a change request of nine characters', $q$
    UPDATE ordering.sales_contract_revision SET change_request_reason = 'too short', change_requested_at = NOW(),
           change_requested_by = (SELECT sales FROM qacl_people)
     WHERE contract_id = md5('qacl:contract')::uuid AND revision = 2 $q$);
SELECT pg_temp.expect_fail('CL75 a change request with no one who made it', $q$
    UPDATE ordering.sales_contract_revision SET change_request_reason = 'Please change the delivery windows',
           change_requested_at = NOW() WHERE contract_id = md5('qacl:contract')::uuid AND revision = 2 $q$);
SELECT pg_temp.expect_fail('CL81 CUSTOMER_SIGNED without the time it was signed', $q$
    UPDATE ordering.sales_contract SET status = 'CUSTOMER_SIGNED' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL82 CUSTOMER_SIGNED that the company already signed (signed_at is the company''s)', $q$
    UPDATE ordering.sales_contract SET status = 'CUSTOMER_SIGNED', customer_signed_at = NOW(), signed_at = NOW(),
           signed_by = (SELECT manager FROM qacl_people), signed_on = CURRENT_DATE, signed_file_key = 'qa/s.pdf',
           signed_file_name = 's.pdf' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_ok('CL83 the customer signs: SENT -> CUSTOMER_SIGNED, the order keeps waiting', $q$
    UPDATE ordering.sales_contract SET status = 'CUSTOMER_SIGNED', customer_signed_at = NOW()
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_true('CL84 the order is still PENDING_CONTRACT',
    (SELECT status = 'PENDING_CONTRACT' FROM ordering.customer_order WHERE id = md5('qacl:order')::uuid));
SELECT pg_temp.expect_fail('CL85 CUSTOMER_SIGNED back to SENT', $q$
    UPDATE ordering.sales_contract SET status = 'SENT' WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL86 CUSTOMER_SIGNED back to a draft (the customer cannot ask for changes after signing)', $q$
    UPDATE ordering.sales_contract SET status = 'DRAFT', revision = 3, sent_at = NULL, sent_by = NULL,
           sent_document_key = NULL, sign_by = NULL, customer_signed_at = NULL WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'DRAFT' WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_fail('CL87 the customer''s signature time rewritten', $q$
    UPDATE ordering.sales_contract SET customer_signed_at = NOW() + interval '1 hour'
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL88 content of a customer-signed contract changed', $q$
    UPDATE ordering.sales_contract SET special_terms = 'Changed after the customer signed'
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL89 the deadline of a customer-signed contract moved', $q$
    UPDATE ordering.sales_contract SET sign_by = CURRENT_DATE + 9 WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL90 signed by the company while the order still waits for the signature', $q$
    UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = (SELECT manager FROM qacl_people),
           signed_on = CURRENT_DATE, signed_file_key = 'qa/s.pdf', signed_file_name = 's.pdf'
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL91 the order confirmed while its contract still waits for the company', $q$
    UPDATE ordering.customer_order SET status = 'CONFIRMED', contract_signed_at = NOW()
     WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_ok('CL92 the company signs: CUSTOMER_SIGNED -> SIGNED; the order confirmed', $q$
    UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = (SELECT manager FROM qacl_people),
           signed_on = CURRENT_DATE, signed_file_key = 'qa/s.pdf', signed_file_name = 's.pdf'
     WHERE id = md5('qacl:contract')::uuid;
    UPDATE ordering.customer_order SET status = 'CONFIRMED', contract_signed_at = NOW()
     WHERE id = md5('qacl:order')::uuid $q$);
SELECT pg_temp.expect_fail('CL93 a signed contract cancelled', $q$
    UPDATE ordering.sales_contract SET status = 'CANCELLED', cancelled_at = NOW(),
           cancelled_by = (SELECT sales FROM qacl_people), cancellation_reason = 'x'
     WHERE id = md5('qacl:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CL94 a signed contract''s customer signature time rewritten', $q$
    UPDATE ordering.sales_contract SET customer_signed_at = NOW() + interval '1 day'
     WHERE id = md5('qacl:contract')::uuid $q$);

\echo '--- cancelling a contract the customer signed, and the SENT -> SIGNED shortcut (a paper signed by both)'
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
VALUES (md5('qacl:order2')::uuid, 'SO-QA-CL2', md5('qacl:customer')::uuid, gen_random_uuid(), 'DRAFT', 2200000, 200000,
        'VND', NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qacl:line2')::uuid, md5('qacl:order2')::uuid, 'QA-CL-OWN', 1000, 2000, 'VND', md5('qacl:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
    seller_address, seller_representative, seller_representative_title, buyer_name, buyer_address,
    buyer_representative, buyer_representative_title, free_sample_rounds)
VALUES (md5('qacl:contract2')::uuid, 'HD-QA-CL-2', md5('qacl:order2')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qacl:c2b1')::uuid, md5('qacl:contract2')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (md5('qacl:c2b1l')::uuid, md5('qacl:c2b1')::uuid, md5('qacl:line2')::uuid, 20);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days)
VALUES (md5('qacl:c2i1')::uuid, md5('qacl:contract2')::uuid, 1, 'On signing', 'ON_SIGNING', 100, 2200000, 0);
SELECT pg_temp.expect_ok('CL100 the second contract is sent, then signed by the customer', $q$
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = (SELECT sales FROM qacl_people),
           sent_document_key = 'qa/cl2-1.pdf', sign_by = CURRENT_DATE + 7 WHERE id = md5('qacl:contract2')::uuid;
    UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qacl:order2')::uuid;
    UPDATE ordering.sales_contract SET status = 'CUSTOMER_SIGNED', customer_signed_at = NOW()
     WHERE id = md5('qacl:contract2')::uuid $q$);
SELECT pg_temp.expect_ok('CL101 cancelled after the customer signed; its order back to a draft', $q$
    UPDATE ordering.sales_contract SET status = 'CANCELLED', cancelled_at = NOW(),
           cancelled_by = (SELECT sales FROM qacl_people), cancellation_reason = 'The customer withdrew'
     WHERE id = md5('qacl:contract2')::uuid;
    UPDATE ordering.customer_order SET status = 'DRAFT' WHERE id = md5('qacl:order2')::uuid $q$);
SELECT pg_temp.expect_ok('CL102 a new contract for the order, sent, then a paper signed by both recorded from SENT', $q$
    INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
        seller_address, seller_representative, seller_representative_title, buyer_name, buyer_address,
        buyer_representative, buyer_representative_title, free_sample_rounds)
    VALUES (md5('qacl:contract3')::uuid, 'HD-QA-CL-3', md5('qacl:order2')::uuid, 'DRAFT', 'Seller', '0101', 'A St',
            'Ms A', 'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
    INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
    VALUES (md5('qacl:c3b1')::uuid, md5('qacl:contract3')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32);
    INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
    VALUES (md5('qacl:c3b1l')::uuid, md5('qacl:c3b1')::uuid, md5('qacl:line2')::uuid, 20);
    INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days)
    VALUES (md5('qacl:c3i1')::uuid, md5('qacl:contract3')::uuid, 1, 'On signing', 'ON_SIGNING', 100, 2200000, 0);
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = (SELECT sales FROM qacl_people),
           sent_document_key = 'qa/cl3-1.pdf', sign_by = CURRENT_DATE + 7 WHERE id = md5('qacl:contract3')::uuid;
    UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qacl:order2')::uuid;
    UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = (SELECT manager FROM qacl_people),
           signed_on = CURRENT_DATE, signed_file_key = 'qa/s3.pdf', signed_file_name = 's3.pdf'
     WHERE id = md5('qacl:contract3')::uuid;
    UPDATE ordering.customer_order SET status = 'CONFIRMED', contract_signed_at = NOW()
     WHERE id = md5('qacl:order2')::uuid $q$);

\echo '--- the new permissions are the Sales Manager''s and the System Admin''s, and moved their roles'' versions'
SELECT pg_temp.expect_true('CL110 sales-contract-terms:APPROVE exists',
    EXISTS (SELECT 1 FROM identity.permission WHERE code = 'sales-contract-terms:APPROVE'
               AND resource = 'sales-contract-terms' AND action = 'APPROVE'));
SELECT pg_temp.expect_true('CL111 sales-contract-signatures:APPROVE exists',
    EXISTS (SELECT 1 FROM identity.permission WHERE code = 'sales-contract-signatures:APPROVE'
               AND resource = 'sales-contract-signatures' AND action = 'APPROVE'));
SELECT pg_temp.expect_true('CL112 exactly the Sales Manager and the System Admin hold them',
    (SELECT array_agg(DISTINCT r.code ORDER BY r.code) = ARRAY['SALES_MANAGER', 'SYSTEM_ADMIN']::varchar[]
       FROM identity.role_permission rp
       JOIN identity.permission p ON p.id = rp.permission_id
       JOIN identity.app_role r ON r.id = rp.role_id
      WHERE p.code IN ('sales-contract-terms:APPROVE', 'sales-contract-signatures:APPROVE')));
SELECT pg_temp.expect_true('CL113 each of the two holds both',
    (SELECT count(*) = 4 FROM identity.role_permission rp
       JOIN identity.permission p ON p.id = rp.permission_id
       JOIN identity.app_role r ON r.id = rp.role_id
      WHERE p.code IN ('sales-contract-terms:APPROVE', 'sales-contract-signatures:APPROVE')
        AND r.code IN ('SALES_MANAGER', 'SYSTEM_ADMIN')));
SELECT pg_temp.expect_true('CL114 the roles that gained a grant advanced their version (ADR-0008)',
    (SELECT bool_and(r.version >= 1) FROM identity.app_role r WHERE r.code IN ('SALES_MANAGER', 'SYSTEM_ADMIN')));

ROLLBACK;
