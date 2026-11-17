-- =============================================================================
-- Release and handover by delivery batch (V20261116000300, team plan 4B-8; kltn-docs 17 §4.2 step 3, §4.5, BR-03, BR-28, BR-29; 09 BR-04):
-- batches are released in schedule order at the shipping warehouse, a shipment waits for its window, a window moves only with
-- a reason and evidence, before anything of the batch left, and never out of schedule order; one pick per released batch.
-- Needs the demo seed. Rolled back at the end.
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

-- Fixtures: the first customer's own SKU in cartons of 50; an order of 20 cartons at 2,000 a piece with 10% VAT -
-- 2,200,000 with tax - whose contract, in two batches of 10 cartons, pays half on signing (due 3 days later) and half
-- before the second batch is handed over. Signed five days ago.
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qa29:product')::uuid, 'QADB', 'QA ly huy', 'qa-db', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa29:blank')::uuid, md5('qa29:product')::uuid, 'QA-DB-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa29:draft')::uuid, c.id, md5('qa29:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa29:snap')::uuid, md5('qa29:draft')::uuid, repeat('b', 64), 'https://example.com/b.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa29:own')::uuid, md5('qa29:product')::uuid, 'QA-DB-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-DB-OWN', 1, 0, NOW(), c.id, md5('qa29:snap')::uuid, md5('qa29:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa29:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-DB-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa29:order')::uuid, 'SO-QA-DB', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa29:line')::uuid, md5('qa29:order')::uuid, 'QA-DB-OWN', 1000, 2000, 'VND', md5('qa29:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds)
VALUES (md5('qa29:contract')::uuid, 'HD-QA-DB-1', md5('qa29:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa29:b1')::uuid, md5('qa29:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32),
       (md5('qa29:b2')::uuid, md5('qa29:contract')::uuid, 2, CURRENT_DATE + 44, CURRENT_DATE + 44);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (gen_random_uuid(), md5('qa29:b1')::uuid, md5('qa29:line')::uuid, 10),
       (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:line')::uuid, 10);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
VALUES (md5('qa29:i1')::uuid, md5('qa29:contract')::uuid, 1, 'On signing', 'ON_SIGNING', 50, 1100000, 3, NULL),
       (md5('qa29:i2')::uuid, md5('qa29:contract')::uuid, 2, 'Batch 2', 'BEFORE_DELIVERY', 50, 1100000, NULL,
        md5('qa29:b2')::uuid);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- installments come with the signature (15 §4.1 step 1)'
SELECT pg_temp.expect_fail('CR00 an installment of a contract not yet signed', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
        order_number, seq, name, milestone, amount, currency, due_days, status)
    SELECT gen_random_uuid(), md5('qa29:contract')::uuid, md5('qa29:i1')::uuid, o.id, o.customer_id, 'HD-QA-DB-1',
           'SO-QA-DB', 1, 'On signing', 'ON_SIGNING', 1100000, 'VND', 3, 'NOT_DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa29:order')::uuid $q$);
UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW() - INTERVAL '6 days',
       sent_by = md5('demo:user:editor')::uuid, sent_document_key = 'documents/hd-qa-db-1.pdf'
 WHERE id = md5('qa29:contract')::uuid;
UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qa29:order')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = md5('demo:user:editor')::uuid,
       signed_on = CURRENT_DATE - 5, signed_file_key = 'documents/s.pdf', signed_file_name = 's.pdf'
 WHERE id = md5('qa29:contract')::uuid;
UPDATE ordering.customer_order SET status = 'PENDING_PAYMENT', contract_signed_at = NOW()
 WHERE id = md5('qa29:order')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The order is signed and CONFIRMED by its contract (paid, as far as these checks care): HCM is its shipping warehouse.
UPDATE ordering.customer_order SET status = 'CONFIRMED', shipping_warehouse_id = md5('demo:wh:HCM')::uuid,
       shipping_warehouse_chosen_at = NOW(), shipping_warehouse_chosen_by = md5('demo:user:editor')::uuid
 WHERE id = md5('qa29:order')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- release in schedule order, at the shipping warehouse (17 BR-03, BR-22, BR-29)'
SELECT pg_temp.expect_fail('BR1 batch 2 released before batch 1', $q$
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    VALUES (md5('qa29:b2')::uuid, md5('qa29:order')::uuid, 2, md5('demo:wh:HCM')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('BR2 batch 1 released at a warehouse that is not the order''s shipping warehouse', $q$
    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, version,
                                     created_at)
    VALUES (md5('qa29:wh2')::uuid, 'QA elsewhere', 'ACTIVE', 'QAW29', 'Somewhere', 'M', 50, 50, 0, NOW());
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    VALUES (md5('qa29:b1')::uuid, md5('qa29:order')::uuid, 1, md5('qa29:wh2')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('BR3 a release whose seq is not the batch''s', $q$
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    VALUES (md5('qa29:b1')::uuid, md5('qa29:order')::uuid, 2, md5('demo:wh:HCM')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('BR4 batch 1 released at the shipping warehouse', $q$
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    VALUES (md5('qa29:b1')::uuid, md5('qa29:order')::uuid, 1, md5('demo:wh:HCM')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('BR5 a batch released twice', $q$
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    VALUES (md5('qa29:b1')::uuid, md5('qa29:order')::uuid, 1, md5('demo:wh:HCM')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('BR6 batch 2 released while batch 1 has handed nothing over', $q$
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    VALUES (md5('qa29:b2')::uuid, md5('qa29:order')::uuid, 2, md5('demo:wh:HCM')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('BR7 a release is written once', $q$
    UPDATE ordering.delivery_batch_release SET released_at = NOW() - interval '1 day'
     WHERE delivery_batch_id = md5('qa29:b1')::uuid;
    DELETE FROM ordering.delivery_batch_release WHERE delivery_batch_id = md5('qa29:b1')::uuid;
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    VALUES (md5('qa29:b1')::uuid, md5('qa29:order')::uuid, 1, md5('demo:wh:HCM')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);

\echo '--- a shipment of a batch: released, and not before its window (09 BR-04)'
INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, ready_at, created_at)
VALUES (md5('qa29:shipment1')::uuid, md5('qa29:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-DB-S1',
        md5('demo:wh:HCM')::uuid, NOW(), NOW()),
       (md5('qa29:shipment1b')::uuid, md5('qa29:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-DB-S1B',
        md5('demo:wh:HCM')::uuid, NOW(), NOW()),
       (md5('qa29:shipment2')::uuid, md5('qa29:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-DB-S2',
        md5('demo:wh:HCM')::uuid, NOW(), NOW());
SELECT pg_temp.expect_fail('BR8 a shipment naming a batch that is not released', $q$
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, delivery_batch_id)
    VALUES (md5('qa29:shipment2')::uuid, md5('qa29:order')::uuid, 'HANDED_OVER', 1100000, NOW() + interval '60 days',
            md5('qa29:b2')::uuid) $q$);
SELECT pg_temp.expect_fail('BR9 batch 1 handed over before its window opens', $q$
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, delivery_batch_id)
    VALUES (md5('qa29:shipment1')::uuid, md5('qa29:order')::uuid, 'HANDED_OVER', 1100000, NOW(),
            md5('qa29:b1')::uuid) $q$);

\echo '--- the window moves with a reason and evidence, before anything of the batch left, never out of order (17 BR-28)'
SELECT pg_temp.expect_fail('BR11 a window in the past', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_file_key, evidence_file_name, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 44, CURRENT_DATE + 44,
            CURRENT_DATE - 1, CURRENT_DATE + 50, 'CUSTOMER_REQUEST', 'later', 'files/e.pdf', 'e.pdf',
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('BR12 no evidence', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 44, CURRENT_DATE + 44,
            CURRENT_DATE + 48, CURRENT_DATE + 50, 'CUSTOMER_REQUEST', 'later', md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('BR13 an unknown reason', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_file_key, evidence_file_name, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 44, CURRENT_DATE + 44,
            CURRENT_DATE + 48, CURRENT_DATE + 50, 'BORED', 'later', 'files/e.pdf', 'e.pdf',
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('BR14 the same window again', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_file_key, evidence_file_name, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 44, CURRENT_DATE + 44,
            CURRENT_DATE + 44, CURRENT_DATE + 44, 'SELLER_DELAY', 'same', 'files/e.pdf', 'e.pdf',
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('BR15 batch 2 moved to start before batch 1', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_file_key, evidence_file_name, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 44, CURRENT_DATE + 44,
            CURRENT_DATE + 10, CURRENT_DATE + 12, 'SELLER_DELAY', 'earlier', 'files/e.pdf', 'e.pdf',
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('BR16 batch 2 moved later, with a file as evidence', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_file_key, evidence_file_name, changed_by, changed_at)
    VALUES (md5('qa29:wc1')::uuid, md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 44, CURRENT_DATE + 44,
            CURRENT_DATE + 48, CURRENT_DATE + 50, 'CUSTOMER_REQUEST', 'later', 'files/e.pdf', 'e.pdf',
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('BR17 evidence by file and by chat message at once', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_file_key, evidence_file_name,
        evidence_message_id, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 48, CURRENT_DATE + 50,
            CURRENT_DATE + 52, CURRENT_DATE + 53, 'SELLER_DELAY', 'later', 'files/e.pdf', 'e.pdf', gen_random_uuid(),
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('BR18 a window change is written once', $q$
    UPDATE ordering.delivery_batch_window_change SET note = 'rewritten' WHERE id = md5('qa29:wc1')::uuid $q$);
SELECT pg_temp.expect_fail('BR19 a window change that names a chat message that does not exist', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_message_id, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 48, CURRENT_DATE + 50,
            CURRENT_DATE + 52, CURRENT_DATE + 53, 'SELLER_DELAY', 'later', gen_random_uuid(),
            md5('demo:user:editor')::uuid, NOW()) $q$);
-- Review fix R4 (17 BR-28): consent by chat is a message the customer sent, in their own conversation (about this order
-- or about none). An agent's message, and a message of another customer's conversation, are no consent.
INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
VALUES (md5('qa29:other-customer')::uuid, 'QA other customer', 'qa29-other@example.com', '0399999929', 'ACTIVE', 0,
        NOW());
-- Every message names the account that sent it (V20261124000200); the demo editor stands in for both sides here.
INSERT INTO chat.conversation (id, customer_id, order_id, subject, status, opened_by_type, status_changed_at, version,
                               created_at)
SELECT md5('qa29:conv')::uuid, o.customer_id, o.id, 'Lich giao', 'QUEUED', 'CUSTOMER', NOW(), 0, NOW()
  FROM ordering.customer_order o WHERE o.id = md5('qa29:order')::uuid;
INSERT INTO chat.conversation (id, customer_id, subject, status, opened_by_type, status_changed_at, version, created_at)
VALUES (md5('qa29:conv-other')::uuid, md5('qa29:other-customer')::uuid, 'Hoi gia', 'QUEUED', 'CUSTOMER', NOW(), 0,
        NOW());
INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, sent_at, version, created_at)
VALUES (md5('qa29:msg-agent')::uuid, md5('qa29:conv')::uuid, 1, 'AGENT', md5('demo:user:editor')::uuid,
        'Chung toi doi dot 2 nhe', NOW(), 0, NOW()),
       (md5('qa29:msg-other')::uuid, md5('qa29:conv-other')::uuid, 1, 'CUSTOMER', md5('demo:user:editor')::uuid,
        'Dong y', NOW(), 0, NOW()),
       (md5('qa29:msg-own')::uuid, md5('qa29:conv')::uuid, 2, 'CUSTOMER', md5('demo:user:editor')::uuid,
        'Dong y doi dot 2', NOW(), 0, NOW());
SELECT pg_temp.expect_fail('BR19a consent by a message the agent sent', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_message_id, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 48, CURRENT_DATE + 50,
            CURRENT_DATE + 49, CURRENT_DATE + 51, 'SELLER_DELAY', 'later', md5('qa29:msg-agent')::uuid,
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('BR19b consent by a message of another customer''s conversation', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_message_id, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 48, CURRENT_DATE + 50,
            CURRENT_DATE + 49, CURRENT_DATE + 51, 'SELLER_DELAY', 'later', md5('qa29:msg-other')::uuid,
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('BR19c consent by the customer''s own message: batch 2 moves to day 49', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_message_id, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b2')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 48, CURRENT_DATE + 50,
            CURRENT_DATE + 49, CURRENT_DATE + 51, 'SELLER_DELAY', 'later', md5('qa29:msg-own')::uuid,
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('BR20 half of batch 1 handed over on the first day of its window', $q$
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, delivery_batch_id)
    VALUES (md5('qa29:shipment1')::uuid, md5('qa29:order')::uuid, 'HANDED_OVER', 550000,
            NOW() + interval '31 days', md5('qa29:b1')::uuid);
    INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity)
    VALUES (md5('qa29:shipment1')::uuid, md5('qa29:line')::uuid, 250) $q$);
SELECT pg_temp.expect_fail('BR20a (R5) batch 2 released while batch 1 is handed over by half (250 of 500 units)', $q$
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    VALUES (md5('qa29:b2')::uuid, md5('qa29:order')::uuid, 2, md5('demo:wh:HCM')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('BR20b the other half of batch 1 handed over', $q$
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, delivery_batch_id)
    VALUES (md5('qa29:shipment1b')::uuid, md5('qa29:order')::uuid, 'HANDED_OVER', 550000,
            NOW() + interval '32 days', md5('qa29:b1')::uuid);
    INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity)
    VALUES (md5('qa29:shipment1b')::uuid, md5('qa29:line')::uuid, 250) $q$);
SELECT pg_temp.expect_fail('BR21 batch 1''s window moves no more once a carton of it left', $q$
    INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start, old_window_end,
        new_window_start, new_window_end, reason_code, note, evidence_file_key, evidence_file_name, changed_by, changed_at)
    VALUES (gen_random_uuid(), md5('qa29:b1')::uuid, md5('qa29:order')::uuid, CURRENT_DATE + 30, CURRENT_DATE + 32,
            CURRENT_DATE + 31, CURRENT_DATE + 33, 'SELLER_DELAY', 'late', 'files/e.pdf', 'e.pdf',
            md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('BR22 now batch 2 is released (batch 1 handed over in full, 500 units, 17 BR-29; its window in force from day 49 is not asked yet)', $q$
    INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id, released_at, released_by)
    VALUES (md5('qa29:b2')::uuid, md5('qa29:order')::uuid, 2, md5('demo:wh:HCM')::uuid, NOW(),
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('BR23 batch 2 handed over before the window in force (day 49), though the signed contract said 44', $q$
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, delivery_batch_id)
    VALUES (md5('qa29:shipment2')::uuid, md5('qa29:order')::uuid, 'HANDED_OVER', 1100000,
            NOW() + interval '45 days', md5('qa29:b2')::uuid) $q$);

\echo '--- one pick per order and batch (uk_fulfillment_pick_order_batch)'
INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, delivery_batch_id)
VALUES (md5('qa29:pick1')::uuid, md5('qa29:order')::uuid, 'SO-QA-DB', md5('demo:wh:HCM')::uuid, 'PICKING', NOW(),
        md5('qa29:b1')::uuid);
SELECT pg_temp.expect_fail('BR24 a second pick of the same batch', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, delivery_batch_id)
    VALUES (gen_random_uuid(), md5('qa29:order')::uuid, 'SO-QA-DB', md5('demo:wh:HCM')::uuid, 'PICKING', NOW(),
            md5('qa29:b1')::uuid) $q$);
SELECT pg_temp.expect_ok('BR25 a pick of batch 2 beside batch 1''s', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, delivery_batch_id)
    VALUES (md5('qa29:pick2')::uuid, md5('qa29:order')::uuid, 'SO-QA-DB', md5('demo:wh:HCM')::uuid, 'PICKING', NOW(),
            md5('qa29:b2')::uuid) $q$);
SELECT pg_temp.expect_fail('BR26 a pick without a batch beside them is a second of a batch-less kind only once', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
    VALUES (gen_random_uuid(), md5('qa29:order')::uuid, 'SO-QA-DB', md5('demo:wh:HCM')::uuid, 'PICKING', NOW()),
           (gen_random_uuid(), md5('qa29:order')::uuid, 'SO-QA-DB', md5('demo:wh:HCM')::uuid, 'PICKING', NOW()) $q$);

\echo '--- a hold stands for a line its batch carries and a reservation of its own order (review fix R7)'
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa29:order2')::uuid, 'SO-QA-DB-2', o.customer_id, gen_random_uuid(), 'DRAFT', 110000, 10000, 'VND', NOW(),
       NOW()
  FROM ordering.customer_order o WHERE o.id = md5('qa29:order')::uuid;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa29:line2')::uuid, md5('qa29:order2')::uuid, 'QA-DB-OWN', 50, 2000, 'VND', md5('qa29:carton')::uuid, 1,
        50, 10, 10000);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
-- Inventory's holds of both orders, written past inventory's own triggers: what they promise is not checked here.
SET session_replication_role = replica;
INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity, reserved_at, status, sku,
                                         warehouse_code)
VALUES (md5('qa29:res-own')::uuid, md5('qa29:order')::uuid, gen_random_uuid(), gen_random_uuid(), 50, NOW(), 'HELD',
        'QA-DB-OWN', 'HCM'),
       (md5('qa29:res-other')::uuid, md5('qa29:order2')::uuid, gen_random_uuid(), gen_random_uuid(), 50, NOW(), 'HELD',
        'QA-DB-OWN', 'HCM');
SET session_replication_role = DEFAULT;
SELECT pg_temp.expect_fail('BR27 a hold for a line its batch does not carry (another order''s line)', $q$
    INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id)
    VALUES (md5('qa29:res-own')::uuid, md5('qa29:b2')::uuid, md5('qa29:order')::uuid, md5('qa29:line2')::uuid) $q$);
SELECT pg_temp.expect_fail('BR28 a hold naming another order''s reservation, refused at commit', $q$
    INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id)
    VALUES (md5('qa29:res-other')::uuid, md5('qa29:b2')::uuid, md5('qa29:order')::uuid, md5('qa29:line')::uuid) $q$);
SELECT pg_temp.expect_ok('BR29 the order''s own reservation for a line batch 2 carries', $q$
    INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id, order_line_id)
    VALUES (md5('qa29:res-own')::uuid, md5('qa29:b2')::uuid, md5('qa29:order')::uuid, md5('qa29:line')::uuid) $q$);

ROLLBACK;
\echo 'PASS 29_release_by_delivery_batch'
