-- =============================================================================
-- Adversarial checks + happy paths: cancellation fees, closures of the rest and refunds of a contract order
-- (V20261108003000, team plan 4B-9; kltn-docs 15 §4.5, §4.6, BR-14..BR-16; 17 §4.8 steps 30 and 33, BR-11). Needs the
-- demo seed (demo.editor is the member of staff). Rolled back at the end. Generated from the fixtures of
-- 18_installments.sql (the same signed contract, renamed) and qa24 body.
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
VALUES (md5('qa24:product')::uuid, 'QAHUY', 'QA ly huy', 'qa-huy', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa24:blank')::uuid, md5('qa24:product')::uuid, 'QA-HUY-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa24:draft')::uuid, c.id, md5('qa24:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa24:snap')::uuid, md5('qa24:draft')::uuid, repeat('b', 64), 'https://example.com/b.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa24:own')::uuid, md5('qa24:product')::uuid, 'QA-HUY-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-HUY-OWN', 1, 0, NOW(), c.id, md5('qa24:snap')::uuid, md5('qa24:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa24:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-HUY-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa24:order')::uuid, 'SO-QA-HUY', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa24:line')::uuid, md5('qa24:order')::uuid, 'QA-HUY-OWN', 1000, 2000, 'VND', md5('qa24:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds)
VALUES (md5('qa24:contract')::uuid, 'HD-QA-HUY-1', md5('qa24:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa24:b1')::uuid, md5('qa24:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32),
       (md5('qa24:b2')::uuid, md5('qa24:contract')::uuid, 2, CURRENT_DATE + 44, CURRENT_DATE + 44);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (gen_random_uuid(), md5('qa24:b1')::uuid, md5('qa24:line')::uuid, 10),
       (gen_random_uuid(), md5('qa24:b2')::uuid, md5('qa24:line')::uuid, 10);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
VALUES (md5('qa24:i1')::uuid, md5('qa24:contract')::uuid, 1, 'On signing', 'ON_SIGNING', 50, 1100000, 3, NULL),
       (md5('qa24:i2')::uuid, md5('qa24:contract')::uuid, 2, 'Batch 2', 'BEFORE_DELIVERY', 50, 1100000, NULL,
        md5('qa24:b2')::uuid);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- installments come with the signature (15 §4.1 step 1)'
SELECT pg_temp.expect_fail('CR00 an installment of a contract not yet signed', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
        order_number, seq, name, milestone, amount, currency, due_days, status)
    SELECT gen_random_uuid(), md5('qa24:contract')::uuid, md5('qa24:i1')::uuid, o.id, o.customer_id, 'HD-QA-HUY-1',
           'SO-QA-HUY', 1, 'On signing', 'ON_SIGNING', 1100000, 'VND', 3, 'NOT_DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);
UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW() - INTERVAL '6 days',
       sent_by = md5('demo:user:editor')::uuid, sent_document_key = 'documents/hd-qa-huy-1.pdf'
 WHERE id = md5('qa24:contract')::uuid;
UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qa24:order')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = md5('demo:user:editor')::uuid,
       signed_on = CURRENT_DATE - 5, signed_file_key = 'documents/s.pdf', signed_file_name = 's.pdf'
 WHERE id = md5('qa24:contract')::uuid;
UPDATE ordering.customer_order SET status = 'PENDING_PAYMENT', contract_signed_at = NOW()
 WHERE id = md5('qa24:order')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- The signed plan's installments, as the signature opens them; the installment on signing is paid 600,000 of 1,100,000.
INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
    order_number, seq, name, milestone, delivery_batch_id, delivery_batch_seq, amount, currency, due_days,
    reached_at, due_date, status)
SELECT md5('qa24:n1')::uuid, md5('qa24:contract')::uuid, md5('qa24:i1')::uuid, o.id, o.customer_id,
       'HD-QA-HUY-1', 'SO-QA-HUY', 1, 'On signing', 'ON_SIGNING', NULL, NULL, 1100000, 'VND', 3, NOW(),
       CURRENT_DATE + 1, 'DUE'
  FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid
UNION ALL
SELECT md5('qa24:n2')::uuid, md5('qa24:contract')::uuid, md5('qa24:i2')::uuid, o.id, o.customer_id,
       'HD-QA-HUY-1', 'SO-QA-HUY', 2, 'Batch 2', 'BEFORE_DELIVERY', md5('qa24:b2')::uuid, 2, 1100000, 'VND', NULL,
       NULL, NULL, 'NOT_DUE'
  FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid;
INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                     recorded_at)
SELECT md5('qa24:r1')::uuid, o.customer_id, 'FT-QA24-1', CURRENT_DATE, 600000, 'VND', 'RECORDED',
       md5('demo:user:editor')::uuid, NOW()
  FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid;
INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
VALUES (gen_random_uuid(), md5('qa24:r1')::uuid, md5('qa24:n1')::uuid, 600000);
UPDATE payment.installment SET paid_amount = 600000, status = 'PARTIALLY_PAID' WHERE id = md5('qa24:n1')::uuid;
-- Another customer, for what must stay the order's customer's.
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qa24:other')::uuid, 'QA khach khac', 'qa24-other@example.com', 'ACTIVE', 0, NOW());
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- the cancellation fee item (15 §4.6 step 18, §5.1)'
SELECT pg_temp.expect_fail('CR01 a fee item naming a plan row', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
        order_number, seq, name, milestone, amount, currency, due_days, reached_at, due_date, status)
    SELECT gen_random_uuid(), md5('qa24:contract')::uuid, md5('qa24:i1')::uuid, o.id, o.customer_id, 'HD-QA-HUY-1',
           'SO-QA-HUY', 21, 'Fee', 'CANCELLATION_FEE', 100000, 'VND', 0, NOW(), CURRENT_DATE, 'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);
SELECT pg_temp.expect_fail('CR02 a plan installment naming no plan row', $q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
        milestone, amount, currency, due_days, reached_at, due_date, status)
    SELECT gen_random_uuid(), md5('qa24:contract')::uuid, o.id, o.customer_id, 'HD-QA-HUY-1', 'SO-QA-HUY', 3, 'Extra',
           'AFTER_DELIVERY', 100000, 'VND', 0, NOW(), CURRENT_DATE, 'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);
SELECT pg_temp.expect_fail('CR03 a fee item numbered as a plan row', $q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
        milestone, amount, currency, due_days, reached_at, due_date, status)
    SELECT gen_random_uuid(), md5('qa24:contract')::uuid, o.id, o.customer_id, 'HD-QA-HUY-1', 'SO-QA-HUY', 3, 'Fee',
           'CANCELLATION_FEE', 100000, 'VND', 0, NOW(), CURRENT_DATE, 'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);
SELECT pg_temp.expect_fail('CR04 a fee item not due from the day it is opened', $q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
        milestone, amount, currency, due_days, status)
    SELECT gen_random_uuid(), md5('qa24:contract')::uuid, o.id, o.customer_id, 'HD-QA-HUY-1', 'SO-QA-HUY', 21, 'Fee',
           'CANCELLATION_FEE', 100000, 'VND', 0, 'NOT_DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);
SELECT pg_temp.expect_fail('CR05 a fee item of another customer than its order''s', $q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
        milestone, amount, currency, due_days, reached_at, due_date, status)
    VALUES (gen_random_uuid(), md5('qa24:contract')::uuid, md5('qa24:order')::uuid, md5('qa24:other')::uuid,
            'HD-QA-HUY-1', 'SO-QA-HUY', 21, 'Fee', 'CANCELLATION_FEE', 100000, 'VND', 0, NOW(), CURRENT_DATE, 'DUE') $q$);
SELECT pg_temp.expect_fail('CR06 a fee item no settlement owes', $q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
        milestone, amount, currency, due_days, reached_at, due_date, status)
    SELECT gen_random_uuid(), md5('qa24:contract')::uuid, o.id, o.customer_id, 'HD-QA-HUY-1', 'SO-QA-HUY', 21, 'Fee',
           'CANCELLATION_FEE', 100000, 'VND', 0, NOW(), CURRENT_DATE, 'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);

\echo '--- a closure of the rest (15 §4.6 step 19, BR-16; 17 §4.8 step 33)'
-- The happy closure written by each statement below, in one: goods worth 1,500,000 closed; batch 2 before delivery
-- (1,100,000, tied to a batch never reached) taken off whole, then 400,000 of the installment on signing; nothing paid
-- above the new worth; an agreed fee of 200,000 owed whole on fee item 21.
CREATE TEMP TABLE qa24_closure (stmt TEXT);
INSERT INTO qa24_closure VALUES ($q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
        milestone, amount, currency, due_days, reached_at, due_date, status)
    SELECT md5('qa24:fee1')::uuid, md5('qa24:contract')::uuid, o.id, o.customer_id, 'HD-QA-HUY-1', 'SO-QA-HUY', 21,
           'Fee for the rest closed', 'CANCELLATION_FEE', 200000, 'VND', 0, NOW(), CURRENT_DATE, 'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid;
    UPDATE payment.installment SET reduced_amount = 1100000, status = 'CANCELLED', cancelled_at = NOW()
     WHERE id = md5('qa24:n2')::uuid;
    UPDATE payment.installment SET reduced_amount = 400000 WHERE id = md5('qa24:n1')::uuid;
    INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
        source_id, currency, collected_amount, closed_amount, reduced_amount, surplus_amount,
        fee_amount, fee_reason, fee_detail,
        fee_covered_amount, refundable_amount, fee_owed_amount, fee_installment_id, settled_by, settled_at)
    SELECT md5('qa24:s1')::uuid, o.id, md5('qa24:contract')::uuid, o.customer_id, 'SO-QA-HUY', 'HD-QA-HUY-1',
           'REMAINDER_CLOSED', md5('qa24:closure1')::uuid, 'VND', 600000, 1500000, 1500000, 0,
           200000, 'Printed cartons', 'Kept as the customer''s stock',
           0, 0, 200000, md5('qa24:fee1')::uuid, md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid;
    INSERT INTO payment.installment_reduction (id, settlement_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa24:s1')::uuid, md5('qa24:n2')::uuid, 1100000),
           (gen_random_uuid(), md5('qa24:s1')::uuid, md5('qa24:n1')::uuid, 400000) $q$);

SELECT pg_temp.expect_fail('CR10 no fee before mass printing started (17 BR-11)',
    (SELECT stmt FROM qa24_closure));
UPDATE ordering.customer_order SET printing_started_at = NOW() WHERE id = md5('qa24:order')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
SELECT pg_temp.expect_fail('CR11 taken off an installment with no reduction to say so', $q$
    UPDATE payment.installment SET reduced_amount = 100000 WHERE id = md5('qa24:n2')::uuid $q$);
SELECT pg_temp.expect_fail('CR12 a settlement whose reductions do not add up', replace(
    (SELECT stmt FROM qa24_closure), '(gen_random_uuid(), md5(''qa24:s1'')::uuid, md5(''qa24:n1'')::uuid, 400000)',
    '(gen_random_uuid(), md5(''qa24:s1'')::uuid, md5(''qa24:n1'')::uuid, 300000)'));
SELECT pg_temp.expect_fail('CR13 the fee split wrong: a refund while the fee is owed', replace(
    (SELECT stmt FROM qa24_closure), '0, 0, 200000, md5', '0, 1, 200000, md5'));
SELECT pg_temp.expect_fail('CR14 a closure keeping a share (17 §4.8 step 33)', replace(replace(replace(
    (SELECT stmt FROM qa24_closure), '200000', '60000'), 'fee_amount, fee_reason, fee_detail,', 'fee_amount, fee_percent,'),
    '60000, ''Printed cartons'', ''Kept as the customer''''s stock'',', '60000, 10,'));
SELECT pg_temp.expect_fail('CR15 a closure fee above the goods closed (17 §4.8 step 33)', replace(
    (SELECT stmt FROM qa24_closure), '200000', '1600000'));
SELECT pg_temp.expect_fail('CR16 its fee item owing another amount than the shortfall', replace(
    (SELECT stmt FROM qa24_closure), '200000, ''VND'', 0, NOW()', '150000, ''VND'', 0, NOW()'));
SELECT pg_temp.expect_fail('CR17 collected said wrong (15 BR-14)', replace(
    (SELECT stmt FROM qa24_closure), '''VND'', 600000, 1500000', '''VND'', 500000, 1500000'));
SELECT pg_temp.expect_fail('CR18 a closure taking off a fee item', (SELECT stmt FROM qa24_closure) || $q$;
    INSERT INTO payment.installment_reduction (id, settlement_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa24:s1')::uuid, md5('qa24:fee1')::uuid, 1000) $q$);
SELECT pg_temp.expect_ok('CR19 the closure: batch 2 taken off whole, 400,000 off the deposit, the fee owed whole',
    (SELECT stmt FROM qa24_closure));
SELECT pg_temp.expect_true('CR19a batch 2 no longer owed; the deposit owes 100,000; fee item 21 due today',
    (SELECT status FROM payment.installment WHERE id = md5('qa24:n2')::uuid) = 'CANCELLED'
    AND (SELECT amount - reduced_amount - paid_amount FROM payment.installment WHERE id = md5('qa24:n1')::uuid) = 100000
    AND (SELECT status || ':' || seq || ':' || due_date FROM payment.installment WHERE id = md5('qa24:fee1')::uuid)
        = 'DUE:21:' || CURRENT_DATE);
SELECT pg_temp.expect_fail('CR20 what a closure took off given back', $q$
    UPDATE payment.installment SET reduced_amount = 300000 WHERE id = md5('qa24:n1')::uuid $q$);
SELECT pg_temp.expect_fail('CR21 the same closure settled twice', $q$
    INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
        source_id, currency, collected_amount, closed_amount, reduced_amount, surplus_amount, fee_covered_amount,
        refundable_amount, fee_owed_amount, settled_at)
    SELECT gen_random_uuid(), o.id, md5('qa24:contract')::uuid, o.customer_id, 'SO-QA-HUY', 'HD-QA-HUY-1',
           'REMAINDER_CLOSED', md5('qa24:closure1')::uuid, 'VND', 600000, 100, 0, 100, 0, 100, 0, NOW()
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);
SELECT pg_temp.expect_fail('CR22 a settlement changed', $q$
    UPDATE payment.order_settlement SET refundable_amount = 0 WHERE id = md5('qa24:s1')::uuid $q$);
SELECT pg_temp.expect_fail('CR23 a settlement deleted', $q$
    DELETE FROM payment.order_settlement WHERE id = md5('qa24:s1')::uuid $q$);
SELECT pg_temp.expect_fail('CR24 a reduction deleted', $q$
    DELETE FROM payment.installment_reduction WHERE settlement_id = md5('qa24:s1')::uuid $q$);
SELECT pg_temp.expect_ok('CR25 the fee item paid by a receipt like any installment', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status,
                                         recorded_by, recorded_at)
    SELECT md5('qa24:r2')::uuid, o.customer_id, 'FT-QA24-2', CURRENT_DATE, 200000, 'VND', 'RECORDED',
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid;
    INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa24:r2')::uuid, md5('qa24:fee1')::uuid, 200000);
    UPDATE payment.installment SET paid_amount = 200000, status = 'PAID', paid_at = NOW()
     WHERE id = md5('qa24:fee1')::uuid $q$);
SELECT pg_temp.expect_fail('CR26 a fee item cancelled', $q$
    UPDATE payment.installment SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = md5('qa24:fee1')::uuid $q$);

\echo '--- the cancellation (15 §4.6 step 18, BR-15; 17 §4.8 step 30)'
CREATE TEMP TABLE qa24_cancel (stmt TEXT);
-- 10% of the 600,000 collected (the fee item's 200,000 is the fee's, never collected for the order): 60,000 kept,
-- 540,000 to refund. The deposit, paid in part, is cancelled.
INSERT INTO qa24_cancel VALUES ($q$
    UPDATE payment.installment SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = md5('qa24:n1')::uuid;
    INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
        source_id, currency, collected_amount, surplus_amount, fee_amount, fee_percent, fee_covered_amount,
        refundable_amount, fee_owed_amount, settled_by, settled_at)
    SELECT md5('qa24:s2')::uuid, o.id, md5('qa24:contract')::uuid, o.customer_id, 'SO-QA-HUY', 'HD-QA-HUY-1',
           'ORDER_CANCELLED', o.id, 'VND', 600000, 600000, 60000, 10, 60000, 540000, 0,
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);
SELECT pg_temp.expect_fail('CR30 a cancellation settled while the order is not cancelled',
    (SELECT stmt FROM qa24_cancel));
UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST',
       cancellation_reason = 'CUSTOMER_REQUEST', cancellation_retained_amount = 0
 WHERE id = md5('qa24:order')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
SELECT pg_temp.expect_fail('CR31 a share not rounded as ROUND() says', replace((SELECT stmt FROM qa24_cancel),
    '600000, 600000, 60000, 10, 60000, 540000', '600000, 600000, 60001, 10, 60001, 539999'));
SELECT pg_temp.expect_fail('CR32 a share and an agreed amount at once', replace(replace((SELECT stmt FROM qa24_cancel),
    'fee_percent, fee_covered_amount', 'fee_percent, fee_reason, fee_detail, fee_covered_amount'),
    '60000, 10, 60000', '60000, 10, ''r'', ''d'', 60000'));
SELECT pg_temp.expect_fail('CR33 an agreed fee above what the order is worth (17 §4.8 step 30)', replace(replace(
    (SELECT stmt FROM qa24_cancel), 'fee_percent, fee_covered_amount', 'fee_reason, fee_detail, fee_covered_amount'),
    '600000, 600000, 60000, 10, 60000, 540000, 0', '600000, 600000, 2200001, ''r'', ''d'', 600000, 0, 1600001'));
SELECT pg_temp.expect_fail('CR34 a cancellation keyed on something else than its order', replace(
    (SELECT stmt FROM qa24_cancel), '''ORDER_CANCELLED'', o.id', '''ORDER_CANCELLED'', gen_random_uuid()'));
SELECT pg_temp.expect_fail('CR35 more to refund than was collected (15 BR-14)', replace(
    (SELECT stmt FROM qa24_cancel), '600000, 600000, 60000, 10, 60000, 540000',
    '600000, 600000, 0, NULL, 0, 600000') || $q$;
    INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, contract_number, kind,
        source_id, currency, collected_amount, closed_amount, reduced_amount, surplus_amount, fee_covered_amount,
        refundable_amount, fee_owed_amount, settled_at)
    SELECT gen_random_uuid(), o.id, md5('qa24:contract')::uuid, o.customer_id, 'SO-QA-HUY', 'HD-QA-HUY-1',
           'REMAINDER_CLOSED', gen_random_uuid(), 'VND', 600000, 100, 0, 100, 0, 100, 0, NOW()
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);
SELECT pg_temp.expect_ok('CR36 the cancellation: 60,000 kept of 600,000, 540,000 to refund',
    (SELECT stmt FROM qa24_cancel));
SELECT pg_temp.expect_fail('CR37 the order cancelled twice', replace(replace(replace((SELECT stmt FROM qa24_cancel),
    'qa24:s2', 'qa24:s8'), 'UPDATE payment.installment SET status = ''CANCELLED'', cancelled_at = NOW() WHERE id = '
    || 'md5(''qa24:n1'')::uuid;', ''), '600000, 600000, 60000, 10, 60000, 540000', '600000, 600000, 600000, 100, '
    || '600000, 0'));

\echo '--- receipts of a settled order (15 §4.4 step 13)'
SELECT pg_temp.expect_fail('CR40 a receipt the settlement counted, voided', $q$
    UPDATE payment.payment_receipt SET status = 'VOIDED', voided_by = md5('demo:user:editor')::uuid,
           voided_at = NOW(), void_reason = 'wrong' WHERE id = md5('qa24:r1')::uuid $q$);
SELECT pg_temp.expect_ok('CR41 a receipt that paid the fee item, voided', $q$
    UPDATE payment.payment_receipt SET status = 'VOIDED', voided_by = md5('demo:user:editor')::uuid,
           voided_at = NOW(), void_reason = 'wrong statement line' WHERE id = md5('qa24:r2')::uuid;
    UPDATE payment.installment SET paid_amount = 0, status = 'DUE', paid_at = NULL
     WHERE id = md5('qa24:fee1')::uuid $q$);

\echo '--- refunds recorded (15 §4.5 step 16, BR-14)'
CREATE TEMP TABLE qa24_refund (stmt TEXT);
INSERT INTO qa24_refund VALUES ($q$
    INSERT INTO payment.refund_record (id, order_id, customer_id, order_number, amount, currency, refunded_on,
        reference, status, recorded_by, recorded_at)
    SELECT md5('qa24:f1')::uuid, o.id, o.customer_id, 'SO-QA-HUY', 300000, 'VND', CURRENT_DATE, 'FT-QA24-OUT-1',
           'RECORDED', md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order o WHERE o.id = md5('qa24:order')::uuid $q$);
SELECT pg_temp.expect_fail('RF01 a refund above what is refundable', replace((SELECT stmt FROM qa24_refund),
    '300000', '540001'));
SELECT pg_temp.expect_fail('RF02 a refund for another customer than the order''s', replace(
    (SELECT stmt FROM qa24_refund), 'o.id, o.customer_id', 'o.id, md5(''qa24:other'')::uuid'));
SELECT pg_temp.expect_fail('RF03 a refund made after the day it was recorded', replace((SELECT stmt FROM qa24_refund),
    '''VND'', CURRENT_DATE', '''VND'', CURRENT_DATE + 2'));
SELECT pg_temp.expect_fail('RF04 a refund recorded VOIDED', replace((SELECT stmt FROM qa24_refund),
    '''RECORDED''', '''VOIDED'''));
SELECT pg_temp.expect_fail('RF05 a refund of nothing', replace((SELECT stmt FROM qa24_refund), '300000', '0'));
SELECT pg_temp.expect_ok('RF06 300,000 refunded', (SELECT stmt FROM qa24_refund));
SELECT pg_temp.expect_fail('RF07 the same statement line recorded twice', replace(replace(
    (SELECT stmt FROM qa24_refund), 'qa24:f1', 'qa24:f2'), '300000', '1000'));
SELECT pg_temp.expect_fail('RF08 240,001 more: above what is left', replace(replace(replace(
    (SELECT stmt FROM qa24_refund), 'qa24:f1', 'qa24:f2'), '300000', '240001'), 'OUT-1', 'OUT-2'));
SELECT pg_temp.expect_ok('RF09 the remaining 240,000 refunded', replace(replace(replace(
    (SELECT stmt FROM qa24_refund), 'qa24:f1', 'qa24:f2'), '300000', '240000'), 'OUT-1', 'OUT-2'));
SELECT pg_temp.expect_fail('RF10 a refund record edited', $q$
    UPDATE payment.refund_record SET amount = 200000 WHERE id = md5('qa24:f1')::uuid $q$);
SELECT pg_temp.expect_fail('RF11 a refund record deleted', $q$
    DELETE FROM payment.refund_record WHERE id = md5('qa24:f1')::uuid $q$);
SELECT pg_temp.expect_fail('RF12 a void with no reason', $q$
    UPDATE payment.refund_record SET status = 'VOIDED', voided_by = md5('demo:user:editor')::uuid, voided_at = NOW()
     WHERE id = md5('qa24:f1')::uuid $q$);
SELECT pg_temp.expect_ok('RF13 a refund record voided with its reason; its statement line free again', $q$
    UPDATE payment.refund_record SET status = 'VOIDED', voided_by = md5('demo:user:editor')::uuid, voided_at = NOW(),
           void_reason = 'sent to the wrong account' WHERE id = md5('qa24:f1')::uuid $q$);
SELECT pg_temp.expect_fail('RF14 a voided record brought back', $q$
    UPDATE payment.refund_record SET status = 'RECORDED', voided_by = NULL, voided_at = NULL, void_reason = NULL
     WHERE id = md5('qa24:f1')::uuid $q$);
SELECT pg_temp.expect_ok('RF15 the voided statement line recorded again, for the right account', replace(
    (SELECT stmt FROM qa24_refund), 'qa24:f1', 'qa24:f3'));
SELECT pg_temp.expect_true('RF15a refunded in full: 540,000 of 540,000',
    (SELECT SUM(amount) FROM payment.refund_record WHERE order_id = md5('qa24:order')::uuid AND status = 'RECORDED')
    = (SELECT SUM(refundable_amount) FROM payment.order_settlement WHERE order_id = md5('qa24:order')::uuid));

\echo '--- the existing rows and the grants'
SELECT pg_temp.expect_true('CR50 every installment says what its money says, closures counted',
    NOT EXISTS (SELECT 1 FROM payment.installment WHERE paid_amount + reduced_amount > amount
                   OR (status = 'PAID') <> (paid_amount > 0 AND paid_amount + reduced_amount = amount)));
CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;
SELECT pg_temp.expect_true('CR51 the accountant records and voids refunds; Sales and the customer read them',
    pg_temp.grants('ACCOUNTANT', 'payment-refunds:CREATE')
    AND pg_temp.grants('ACCOUNTANT', 'payment-refunds:UPDATE')
    AND pg_temp.grants('SALES_STAFF', 'payment-refunds:READ')
    AND NOT pg_temp.grants('SALES_STAFF', 'payment-refunds:CREATE')
    AND pg_temp.grants('CUSTOMER', 'payment-refunds:READ')
    AND NOT pg_temp.grants('CUSTOMER', 'payment-refunds:CREATE')
    AND NOT pg_temp.grants('WAREHOUSE_STAFF', 'payment-refunds:READ'));

ROLLBACK;
