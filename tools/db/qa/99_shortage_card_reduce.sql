-- =============================================================================
-- Adversarial checks + happy paths: the shortage card's code and "Giảm số thùng" (V20270129000530,
-- V20270129000540; UNH-CODE-1128; kltn-docs 17 §4.14 step 58, D45, D52) - the code's shape and uniqueness, and a card
-- carried out as REDUCE_CARTONS dropping, with every reduce card of the batch before it, at most the cartons the signed
-- batch has of each line. Needs the demo seed (demo.editor). Rolled back at the end.
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

-- Fixtures, written with triggers and foreign keys off: an order with a signed contract of one batch of 4 cartons of
-- one line, and two reduce cards of that batch.
SET session_replication_role = replica;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at)
VALUES (md5('qa99r:o')::uuid, 'SO-QA99R-O1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DRAFT',
        1000, 'VND', NOW(), NOW()),
       (md5('qa99r:o2')::uuid, 'SO-QA99R-O2', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DRAFT',
        1000, 'VND', NOW(), NOW());
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds, sent_at,
                                     sent_by, sent_document_key, signed_at, signed_by, signed_on, signed_file_key,
                                     signed_file_name)
VALUES (md5('qa99r:contract')::uuid, 'HD-QA99R-1', md5('qa99r:o')::uuid, 'SIGNED', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2, NOW() - INTERVAL '6 days',
        md5('demo:user:editor')::uuid, 'documents/hd-qa99r-1.pdf', NOW(), md5('demo:user:editor')::uuid,
        CURRENT_DATE - 5, 'documents/s.pdf', 's.pdf');
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa99r:b1')::uuid, md5('qa99r:contract')::uuid, 1, CURRENT_DATE + 3, CURRENT_DATE + 8);
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa99r:l1')::uuid, md5('qa99r:o')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 4000, 100, 'VND');
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (gen_random_uuid(), md5('qa99r:b1')::uuid, md5('qa99r:l1')::uuid, 4);
-- Two answered reduce cards of batch 1 (one live card per batch is the table's rule; the second is written ended and
-- re-opened by hand below), each 2 cartons of the line missing.
INSERT INTO ordering.shortage_card (id, code, order_id, delivery_batch_id, customer_id, status, options_offered,
                                    missing_cartons, amounts, sent_by, sent_at, chosen_option, chosen_by, chosen_at,
                                    ended_reason)
VALUES (md5('qa99r:c1')::uuid, 'TT-20990103-0001', md5('qa99r:o')::uuid, md5('qa99r:b1')::uuid,
        'c0000000-0000-4000-8000-000000000001', 'ANSWERED', ARRAY['REDUCE_CARTONS'], 2, '[]'::jsonb,
        md5('demo:user:editor')::uuid, NOW(), 'REDUCE_CARTONS', md5('demo:user:editor')::uuid, NOW(), NULL),
       (md5('qa99r:c2')::uuid, 'TT-20990103-0002', md5('qa99r:o')::uuid, md5('qa99r:b1')::uuid,
        'c0000000-0000-4000-8000-000000000001', 'EXPIRED', ARRAY['REDUCE_CARTONS'], 3, '[]'::jsonb,
        md5('demo:user:editor')::uuid, NOW(), 'REDUCE_CARTONS', md5('demo:user:editor')::uuid, NOW(),
        'DECIDED_WITHOUT_CARD');
INSERT INTO ordering.shortage_card_line (id, card_id, order_line_id, sku, batch_cartons, available_cartons,
                                         missing_cartons, units_per_carton)
VALUES (gen_random_uuid(), md5('qa99r:c1')::uuid, md5('qa99r:l1')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 4, 2, 2, 1000),
       (gen_random_uuid(), md5('qa99r:c2')::uuid, md5('qa99r:l1')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 4, 1, 3, 1000);
SET session_replication_role = origin;

\echo '--- the code: TT-yyyyMMdd-NNNN, once'
SELECT pg_temp.expect_fail('SR01 a code of another shape', $q$
    UPDATE ordering.shortage_card SET code = 'THE-01' WHERE id = md5('qa99r:c1')::uuid $q$);
SELECT pg_temp.expect_fail('SR02 a code without its number', $q$
    UPDATE ordering.shortage_card SET code = 'TT-20990103-' WHERE id = md5('qa99r:c1')::uuid $q$);
SELECT pg_temp.expect_fail('SR03 two cards with one code', $q$
    UPDATE ordering.shortage_card SET code = 'TT-20990103-0001' WHERE id = md5('qa99r:c2')::uuid $q$);
SELECT pg_temp.expect_fail('SR04 a card without a code', $q$
    UPDATE ordering.shortage_card SET code = NULL WHERE id = md5('qa99r:c1')::uuid $q$);

\echo '--- a reduce card carried out drops no more than the signed batch has (D45, D52)'
SELECT pg_temp.expect_ok('SR05 2 of the batch''s 4 cartons dropped', $q$
    UPDATE ordering.shortage_card SET status = 'EXECUTED', executed_by = md5('demo:user:editor')::uuid,
           executed_at = NOW() WHERE id = md5('qa99r:c1')::uuid $q$);
SELECT pg_temp.expect_fail('SR06 3 more of the 2 left', $q$
    UPDATE ordering.shortage_card SET status = 'ANSWERED', ended_reason = NULL WHERE id = md5('qa99r:c2')::uuid;
    UPDATE ordering.shortage_card SET status = 'EXECUTED', executed_by = md5('demo:user:editor')::uuid,
           executed_at = NOW() WHERE id = md5('qa99r:c2')::uuid $q$);
ROLLBACK;
