-- =============================================================================
-- A contract whose credit check fails waits for accounting before it is sent (V20270101000100; kltn-docs 15 BR-11 as
-- changed on 2026-11-26): ordering.sales_contract.credit_review, ordering.contract_credit_review and the check at signing
-- that names a pre-send approval (order_credit_check.pre_send_review_id). Needs the migrations only (its own customer and
-- order) and two accounts of identity.app_user. Rolled back at the end.
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

-- Fixtures of the script's own, as 17_sales_contracts.sql builds them: a customer, their SKU in cartons of 50, a DRAFT
-- order of 20 cartons at 2,000 a piece with 10% VAT (2,200,000 with tax) and its draft contract, everything on signing.
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qacr:customer')::uuid, 'QA credit review customer', 'qacr@example.com', 'ACTIVE', 0, NOW());
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qacr:product')::uuid, 'QACR', 'QA ly duyet cong no', 'qa-cr', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qacr:blank')::uuid, md5('qacr:product')::uuid, 'QA-CR-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
VALUES (md5('qacr:draft')::uuid, md5('qacr:customer')::uuid, md5('qacr:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb);
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qacr:snap')::uuid, md5('qacr:draft')::uuid, repeat('c', 64), 'https://example.com/c.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
VALUES (md5('qacr:own')::uuid, md5('qacr:product')::uuid, 'QA-CR-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
        'OWNED-SKU:QA-CR-OWN', 1, 0, NOW(), md5('qacr:customer')::uuid, md5('qacr:snap')::uuid, md5('qacr:blank')::uuid);
-- The customer's SKU is packed in its blank variant's carton (V20270104000100): the carton is the variant's.
INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, active, version, created_at)
VALUES (md5('qacr:carton')::uuid, md5('qacr:blank')::uuid, 'CARTON', 50, TRUE, 0, NOW());
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
VALUES (md5('qacr:order')::uuid, 'SO-QA-CR', md5('qacr:customer')::uuid, gen_random_uuid(), 'DRAFT', 2200000, 200000,
        'VND', NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qacr:line')::uuid, md5('qacr:order')::uuid, 'QA-CR-OWN', 1000, 2000, 'VND', md5('qacr:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
    seller_address, seller_representative, seller_representative_title, buyer_name, buyer_address,
    buyer_representative, buyer_representative_title, free_sample_rounds)
VALUES (md5('qacr:contract')::uuid, 'HD-QA-CR-1', md5('qacr:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qacr:b1')::uuid, md5('qacr:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (md5('qacr:b1l')::uuid, md5('qacr:b1')::uuid, md5('qacr:line')::uuid, 20);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days)
VALUES (md5('qacr:i1')::uuid, md5('qacr:contract')::uuid, 1, 'On signing', 'ON_SIGNING', 100, 2200000, 0);

CREATE TEMP TABLE qacr_people AS
SELECT (SELECT id FROM identity.app_user ORDER BY id LIMIT 1) AS sales,
       (SELECT id FROM identity.app_user ORDER BY id DESC LIMIT 1) AS accountant;
DO $d$ BEGIN
    IF (SELECT sales = accountant FROM qacr_people) THEN
        RAISE EXCEPTION 'FAIL  the checks below need two accounts';
    END IF;
END $d$;

\echo '--- the contract waits (ordering.sales_contract.credit_review)'
SELECT pg_temp.expect_ok('CR1 a draft waits for accounting', $q$
    UPDATE ordering.sales_contract SET credit_review = 'PENDING' WHERE id = md5('qacr:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CR2 a sent contract still waiting (only a DRAFT waits)', $q$
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = (SELECT sales FROM qacr_people),
           sent_document_key = 'qa/cr.pdf' WHERE id = md5('qacr:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CR3 a cancelled contract still waiting', $q$
    UPDATE ordering.sales_contract SET status = 'CANCELLED', cancelled_at = NOW(),
           cancelled_by = (SELECT sales FROM qacr_people), cancellation_reason = 'QA'
     WHERE id = md5('qacr:contract')::uuid $q$);
SELECT pg_temp.expect_fail('CR4 a credit review state that does not exist', $q$
    UPDATE ordering.sales_contract SET credit_review = 'APPROVED' WHERE id = md5('qacr:contract')::uuid $q$);

\echo '--- its submission (ordering.contract_credit_review)'
SELECT pg_temp.expect_ok('CR5 its submission, waiting: past the limit with this contract', $q$
    INSERT INTO ordering.contract_credit_review (id, contract_id, contract_version, outcome, check_outcome,
        credit_limit, exposure, after_delivery, overdue_amount, submitted_at, submitted_by, created_at)
    VALUES (md5('qacr:review')::uuid, md5('qacr:contract')::uuid, 0, 'PENDING', 'OVER_LIMIT', 1000000, 500000,
        800000, 0, NOW(), (SELECT sales FROM qacr_people), NOW()) $q$);
SELECT pg_temp.expect_fail('CR6 a second submission waiting on the same contract', $q$
    INSERT INTO ordering.contract_credit_review (id, contract_id, contract_version, outcome, check_outcome,
        credit_limit, exposure, after_delivery, overdue_amount, submitted_at, submitted_by, created_at)
    VALUES (gen_random_uuid(), md5('qacr:contract')::uuid, 0, 'PENDING', 'OVER_LIMIT', 1000000, 500000,
        800000, 0, NOW(), (SELECT sales FROM qacr_people), NOW()) $q$);
SELECT pg_temp.expect_fail('CR7 OVER_LIMIT with figures within the limit', $q$
    UPDATE ordering.contract_credit_review SET after_delivery = 400000 WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR8 OVER_LIMIT with something overdue (that is OVERDUE)', $q$
    UPDATE ordering.contract_credit_review SET overdue_amount = 1 WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR9 OVERDUE with nothing overdue', $q$
    UPDATE ordering.contract_credit_review SET check_outcome = 'OVERDUE' WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR10 a check that passed (WITHIN_LIMIT needs no review)', $q$
    UPDATE ordering.contract_credit_review SET check_outcome = 'WITHIN_LIMIT' WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR11 nothing paid after delivery (no credit asked)', $q$
    UPDATE ordering.contract_credit_review SET check_outcome = 'OVERDUE', overdue_amount = 1, after_delivery = 0
     WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR12 a negative exposure', $q$
    UPDATE ordering.contract_credit_review SET exposure = -1 WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR13 an outcome that does not exist', $q$
    UPDATE ordering.contract_credit_review SET outcome = 'MAYBE' WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR14 approved by whoever submitted it (four eyes)', $q$
    UPDATE ordering.contract_credit_review SET outcome = 'APPROVED', decided_at = NOW(), decided_by = submitted_by,
           note = 'ok' WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR15 refused by whoever submitted it (four eyes)', $q$
    UPDATE ordering.contract_credit_review SET outcome = 'REJECTED', decided_at = NOW(), decided_by = submitted_by,
           note = 'no' WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR16 approved without a note', $q$
    UPDATE ordering.contract_credit_review SET outcome = 'APPROVED', decided_at = NOW(),
           decided_by = (SELECT accountant FROM qacr_people) WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR17 refused with a blank reason', $q$
    UPDATE ordering.contract_credit_review SET outcome = 'REJECTED', decided_at = NOW(),
           decided_by = (SELECT accountant FROM qacr_people), note = '   ' WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR18 decided with no one deciding it', $q$
    UPDATE ordering.contract_credit_review SET outcome = 'APPROVED', decided_at = NOW(), note = 'ok'
     WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR19 still waiting but marked decided', $q$
    UPDATE ordering.contract_credit_review SET decided_at = NOW(), decided_by = (SELECT accountant FROM qacr_people)
     WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_fail('CR20 a negative contract version', $q$
    UPDATE ordering.contract_credit_review SET contract_version = -1 WHERE id = md5('qacr:review')::uuid $q$);
SELECT pg_temp.expect_ok('CR21 refused by the accountant, with a reason; the draft is Sales'' again', $q$
    UPDATE ordering.contract_credit_review SET outcome = 'REJECTED', decided_at = NOW(),
           decided_by = (SELECT accountant FROM qacr_people), note = 'More on signing'
     WHERE id = md5('qacr:review')::uuid;
    UPDATE ordering.sales_contract SET credit_review = 'NONE' WHERE id = md5('qacr:contract')::uuid $q$);
SELECT pg_temp.expect_ok('CR22 withdrawn by whoever submitted it, with no note (no four eyes for taking it back)', $q$
    INSERT INTO ordering.contract_credit_review (id, contract_id, contract_version, outcome, check_outcome,
        credit_limit, exposure, after_delivery, overdue_amount, submitted_at, submitted_by, decided_at, decided_by,
        created_at)
    VALUES (gen_random_uuid(), md5('qacr:contract')::uuid, 3, 'WITHDRAWN', 'OVERDUE', 1000000, 0, 800000, 250000,
        NOW(), (SELECT sales FROM qacr_people), NOW(), (SELECT sales FROM qacr_people), NOW()) $q$);
SELECT pg_temp.expect_ok('CR23 submitted again and approved by the accountant, with a note', $q$
    INSERT INTO ordering.contract_credit_review (id, contract_id, contract_version, outcome, check_outcome,
        credit_limit, exposure, after_delivery, overdue_amount, submitted_at, submitted_by, decided_at, decided_by,
        note, created_at)
    VALUES (md5('qacr:approved')::uuid, md5('qacr:contract')::uuid, 5, 'APPROVED', 'OVERDUE', 1000000, 0, 800000,
        250000, NOW(), (SELECT sales FROM qacr_people), NOW(), (SELECT accountant FROM qacr_people),
        'Customer paid by phone; receipt follows', NOW()) $q$);

\echo '--- the check at signing that names the approval (order_credit_check.pre_send_review_id)'
SELECT pg_temp.expect_ok('CR24 a failed check at signing let through by the approval given before sending', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome,
        note, pre_send_review_id)
    VALUES (gen_random_uuid(), md5('qacr:order')::uuid, NOW(), 1000000, 0, 800000, 'OVERDUE',
        'Approved before sending', md5('qacr:approved')::uuid) $q$);
SELECT pg_temp.expect_fail('CR25 a check within the limit naming an approval (it needs none)', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome,
        pre_send_review_id)
    VALUES (gen_random_uuid(), md5('qacr:order')::uuid, NOW(), 1000000, 0, 800000, 'WITHIN_LIMIT',
        md5('qacr:approved')::uuid) $q$);
SELECT pg_temp.expect_fail('CR26 an accountant''s decision on a hold naming a pre-send approval', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome,
        decided_by, decided_at, note, pre_send_review_id)
    VALUES (gen_random_uuid(), md5('qacr:order')::uuid, NOW(), 1000000, 0, 800000, 'APPROVED',
        (SELECT accountant FROM qacr_people), NOW(), 'ok', md5('qacr:approved')::uuid) $q$);
SELECT pg_temp.expect_fail('CR27 naming a review that does not exist', $q$
    INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure, order_amount, outcome,
        pre_send_review_id)
    VALUES (gen_random_uuid(), md5('qacr:order')::uuid, NOW(), 1000000, 0, 800000, 'OVERDUE', gen_random_uuid()) $q$);

\echo '--- who decides'
DO $d$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE p.code = 'sales-order-credit-holds:APPROVE' AND r.code = 'ACCOUNTANT') THEN
        RAISE EXCEPTION 'FAIL  the accountant does not decide credit';
    END IF;
    RAISE NOTICE 'ok    CR28 the accountant decides credit (sales-order-credit-holds:APPROVE)';
END $d$;

ROLLBACK;
\echo 'PASS 99_contract_credit_review'
