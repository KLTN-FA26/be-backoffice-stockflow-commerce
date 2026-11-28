-- =============================================================================
-- Adversarial checks + happy paths: carrying out a customer's choice on a shortage card (V20270128000510,
-- V20270128000520; kltn-docs 17 §4.14 step 58, BR-28, BR-32, D48) - the window moved with the card as the evidence
-- (exactly one of file, message and card; the card is the order's batch's, the customer chose to wait, the window
-- starts on the card's date, the seller's delay) and the "làm hộ" action of the press. Needs the demo seed
-- (demo.editor). Rolled back at the end.
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

-- A window change of batch 1 as a statement: the evidence columns and values, the new start and the reason vary.
CREATE FUNCTION pg_temp.move(evidence_cols TEXT, evidence_vals TEXT, new_start TEXT DEFAULT 'CURRENT_DATE + 20',
                             reason TEXT DEFAULT 'SELLER_DELAY') RETURNS TEXT LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start,
            old_window_end, new_window_start, new_window_end, reason_code, note, changed_by, changed_at, %s)
        VALUES (gen_random_uuid(), md5('qa99x:b1')::uuid, md5('qa99x:o')::uuid, CURRENT_DATE + 3, CURRENT_DATE + 8,
                %s, CURRENT_DATE + 25, %L, 'Theo lựa chọn của khách trên thẻ thiếu thùng',
                md5('demo:user:editor')::uuid, NOW(), %s)$f$, evidence_cols, new_start, reason, evidence_vals)
$$;

-- Fixtures, written with triggers and foreign keys off: two orders of the same customer, the first with a signed
-- contract of one batch; on it a card the customer chose to wait on (expected +20 days), a card ended on which they
-- chose to reduce, and a card of the second order.
SET session_replication_role = replica;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at)
VALUES (md5('qa99x:o')::uuid, 'SO-QA99X-O1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DRAFT',
        1000, 'VND', NOW(), NOW()),
       (md5('qa99x:o2')::uuid, 'SO-QA99X-O2', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DRAFT',
        1000, 'VND', NOW(), NOW());
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds, sent_at,
                                     sent_by, sent_document_key, signed_at, signed_by, signed_on, signed_file_key,
                                     signed_file_name)
VALUES (md5('qa99x:contract')::uuid, 'HD-QA99X-1', md5('qa99x:o')::uuid, 'SIGNED', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2, NOW() - INTERVAL '6 days',
        md5('demo:user:editor')::uuid, 'documents/hd-qa99x-1.pdf', NOW(), md5('demo:user:editor')::uuid,
        CURRENT_DATE - 5, 'documents/s.pdf', 's.pdf');
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa99x:b1')::uuid, md5('qa99x:contract')::uuid, 1, CURRENT_DATE + 3, CURRENT_DATE + 8);
INSERT INTO ordering.shortage_card (id, code, order_id, delivery_batch_id, customer_id, status, options_offered,
                                    missing_cartons, amounts, expected_delivery_date, expected_date_source, sent_by,
                                    sent_at, chosen_option, chosen_by, chosen_at, ended_reason)
VALUES (md5('qa99x:wait')::uuid, 'TT-20990102-0001', md5('qa99x:o')::uuid, md5('qa99x:b1')::uuid, 'c0000000-0000-4000-8000-000000000001',
        'ANSWERED', ARRAY['WAIT_FOR_PRODUCTION', 'REDUCE_CARTONS'], 1, '[]'::jsonb, CURRENT_DATE + 20,
        'STANDARD_LEAD_TIME', md5('demo:user:editor')::uuid, NOW(), 'WAIT_FOR_PRODUCTION',
        md5('demo:user:editor')::uuid, NOW(), NULL),
       (md5('qa99x:reduce')::uuid, 'TT-20990102-0002', md5('qa99x:o')::uuid, md5('qa99x:b1')::uuid, 'c0000000-0000-4000-8000-000000000001',
        'EXPIRED', ARRAY['WAIT_FOR_PRODUCTION', 'REDUCE_CARTONS'], 1, '[]'::jsonb, CURRENT_DATE + 20,
        'STANDARD_LEAD_TIME', md5('demo:user:editor')::uuid, NOW(), 'REDUCE_CARTONS', md5('demo:user:editor')::uuid,
        NOW(), 'DECIDED_WITHOUT_CARD'),
       (md5('qa99x:other')::uuid, 'TT-20990102-0003', md5('qa99x:o2')::uuid, md5('qa99x:b1')::uuid,
        'c0000000-0000-4000-8000-000000000001', 'ANSWERED', ARRAY['WAIT_FOR_PRODUCTION'], 1, '[]'::jsonb,
        CURRENT_DATE + 20, 'STANDARD_LEAD_TIME', md5('demo:user:editor')::uuid, NOW(), 'WAIT_FOR_PRODUCTION',
        md5('demo:user:editor')::uuid, NOW(), NULL);
SET session_replication_role = origin;

\echo '--- exactly one evidence: a file, a message or the card'
SELECT pg_temp.expect_fail('SX01 a card and a file together', pg_temp.move(
    'evidence_file_key, evidence_file_name, evidence_shortage_card_id', $v$'files/e.pdf', 'e.pdf', md5('qa99x:wait')::uuid$v$));
SELECT pg_temp.expect_fail('SX02 a file key without its name, with a card', pg_temp.move(
    'evidence_file_key, evidence_shortage_card_id', $v$'files/e.pdf', md5('qa99x:wait')::uuid$v$));
SELECT pg_temp.expect_fail('SX03 a card that does not exist', pg_temp.move(
    'evidence_shortage_card_id', $v$md5('qa99x:nothing')::uuid$v$));

\echo '--- the card is consent only to what the customer chose on it (D48)'
SELECT pg_temp.expect_fail('SX04 a card of another order', pg_temp.move(
    'evidence_shortage_card_id', $v$md5('qa99x:other')::uuid$v$));
SELECT pg_temp.expect_fail('SX05 a card on which the customer chose to reduce the cartons', pg_temp.move(
    'evidence_shortage_card_id', $v$md5('qa99x:reduce')::uuid$v$));
SELECT pg_temp.expect_fail('SX06 a start other than the card''s expected date', pg_temp.move(
    'evidence_shortage_card_id', $v$md5('qa99x:wait')::uuid$v$, 'CURRENT_DATE + 21'));
SELECT pg_temp.expect_fail('SX07 the customer''s request as the reason', pg_temp.move(
    'evidence_shortage_card_id', $v$md5('qa99x:wait')::uuid$v$, 'CURRENT_DATE + 20', 'CUSTOMER_REQUEST'));
SELECT pg_temp.expect_ok('SX08 the window moved to the card''s date, the seller''s delay, the card as evidence',
    pg_temp.move('evidence_shortage_card_id', $v$md5('qa99x:wait')::uuid$v$));
SELECT pg_temp.expect_fail('SX09 a card kept as evidence is not deleted', $q$
    DELETE FROM ordering.shortage_card WHERE id = md5('qa99x:wait')::uuid $q$);
SELECT pg_temp.expect_ok('SX10 a file alone is evidence as before', pg_temp.move(
    'evidence_file_key, evidence_file_name', $v$'files/e.pdf', 'e.pdf'$v$, 'CURRENT_DATE + 22'));

\echo '--- the press of "Thực hiện" is a "làm hộ" action (17 BR-32)'
SELECT pg_temp.expect_ok('SX11 EXECUTE_SHORTAGE_CHOICE is an action of the order''s history', $q$
    INSERT INTO ordering.order_on_behalf_action (id, order_id, action, actor_id, acting_for_user_id, acted_at)
    VALUES (gen_random_uuid(), md5('qa99x:o')::uuid, 'EXECUTE_SHORTAGE_CHOICE', md5('demo:user:editor')::uuid, NULL,
            NOW()) $q$);
SELECT pg_temp.expect_fail('SX12 an action not in the list', $q$
    INSERT INTO ordering.order_on_behalf_action (id, order_id, action, actor_id, acting_for_user_id, acted_at)
    VALUES (gen_random_uuid(), md5('qa99x:o')::uuid, 'GUESS_FOR_CUSTOMER', md5('demo:user:editor')::uuid, NULL,
            NOW()) $q$);
ROLLBACK;
