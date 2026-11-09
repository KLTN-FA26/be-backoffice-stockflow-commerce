-- =============================================================================
-- Adversarial checks + happy paths: a signed contract's installments and the payment receipts that pay them
-- (V20261108002400, team plan 4B-6; kltn-docs 15 §4.1-§4.4, §5.1, §5.3, BR-03..BR-05). Needs the demo seed. Rolled
-- back at the end.
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
VALUES (md5('qa18:product')::uuid, 'QADOT', 'QA ly dot', 'qa-dot', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa18:blank')::uuid, md5('qa18:product')::uuid, 'QA-DOT-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa18:draft')::uuid, c.id, md5('qa18:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa18:snap')::uuid, md5('qa18:draft')::uuid, repeat('b', 64), 'https://example.com/b.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa18:own')::uuid, md5('qa18:product')::uuid, 'QA-DOT-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-DOT-OWN', 1, 0, NOW(), c.id, md5('qa18:snap')::uuid, md5('qa18:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa18:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-DOT-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa18:order')::uuid, 'SO-QA-DOT', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa18:line')::uuid, md5('qa18:order')::uuid, 'QA-DOT-OWN', 1000, 2000, 'VND', md5('qa18:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds)
VALUES (md5('qa18:contract')::uuid, 'HD-QA-DOT-1', md5('qa18:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa18:b1')::uuid, md5('qa18:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32),
       (md5('qa18:b2')::uuid, md5('qa18:contract')::uuid, 2, CURRENT_DATE + 44, CURRENT_DATE + 44);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (gen_random_uuid(), md5('qa18:b1')::uuid, md5('qa18:line')::uuid, 10),
       (gen_random_uuid(), md5('qa18:b2')::uuid, md5('qa18:line')::uuid, 10);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
VALUES (md5('qa18:i1')::uuid, md5('qa18:contract')::uuid, 1, 'On signing', 'ON_SIGNING', 50, 1100000, 3, NULL),
       (md5('qa18:i2')::uuid, md5('qa18:contract')::uuid, 2, 'Batch 2', 'BEFORE_DELIVERY', 50, 1100000, NULL,
        md5('qa18:b2')::uuid);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- installments come with the signature (15 §4.1 step 1)'
SELECT pg_temp.expect_fail('IN1 an installment of a contract not yet signed', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
        order_number, seq, name, milestone, amount, currency, due_days, status)
    SELECT gen_random_uuid(), md5('qa18:contract')::uuid, md5('qa18:i1')::uuid, o.id, o.customer_id, 'HD-QA-DOT-1',
           'SO-QA-DOT', 1, 'On signing', 'ON_SIGNING', 1100000, 'VND', 3, 'NOT_DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa18:order')::uuid $q$);
UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW() - INTERVAL '6 days',
       sent_by = md5('demo:user:editor')::uuid, sent_document_key = 'documents/hd-qa-dot-1.pdf'
 WHERE id = md5('qa18:contract')::uuid;
UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = md5('qa18:order')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = md5('demo:user:editor')::uuid,
       signed_on = CURRENT_DATE - 5, signed_file_key = 'documents/s.pdf', signed_file_name = 's.pdf'
 WHERE id = md5('qa18:contract')::uuid;
UPDATE ordering.customer_order SET status = 'PENDING_PAYMENT', contract_signed_at = NOW()
 WHERE id = md5('qa18:order')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

SELECT pg_temp.expect_fail('IN2 an installment that is not its plan row as signed (14 BR-14)', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
        order_number, seq, name, milestone, amount, currency, due_days, reached_at, due_date, status)
    SELECT gen_random_uuid(), md5('qa18:contract')::uuid, md5('qa18:i1')::uuid, o.id, o.customer_id, 'HD-QA-DOT-1',
           'SO-QA-DOT', 1, 'On signing', 'ON_SIGNING', 1000000, 'VND', 3, NOW(), CURRENT_DATE - 2, 'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa18:order')::uuid $q$);
SELECT pg_temp.expect_fail('IN3 due without its due day', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
        order_number, seq, name, milestone, amount, currency, due_days, reached_at, status)
    SELECT gen_random_uuid(), md5('qa18:contract')::uuid, md5('qa18:i1')::uuid, o.id, o.customer_id, 'HD-QA-DOT-1',
           'SO-QA-DOT', 1, 'On signing', 'ON_SIGNING', 1100000, 'VND', 3, NOW(), 'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa18:order')::uuid $q$);
SELECT pg_temp.expect_fail('IN4 paid before delivery with a due day (BR-08: it blocks the handover instead)', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
        order_number, seq, name, milestone, delivery_batch_id, delivery_batch_seq, amount, currency, reached_at,
        due_date, status)
    SELECT gen_random_uuid(), md5('qa18:contract')::uuid, md5('qa18:i2')::uuid, o.id, o.customer_id, 'HD-QA-DOT-1',
           'SO-QA-DOT', 2, 'Batch 2', 'BEFORE_DELIVERY', md5('qa18:b2')::uuid, 2, 1100000, 'VND', NOW(), CURRENT_DATE,
           'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa18:order')::uuid $q$);
SELECT pg_temp.expect_ok('IN5 the signed plan''s installments: the one on signing due, the other waiting', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
        order_number, seq, name, milestone, delivery_batch_id, delivery_batch_seq, amount, currency, due_days,
        reached_at, due_date, status)
    SELECT md5('qa18:n1')::uuid, md5('qa18:contract')::uuid, md5('qa18:i1')::uuid, o.id, o.customer_id,
           'HD-QA-DOT-1', 'SO-QA-DOT', 1, 'On signing', 'ON_SIGNING', NULL, NULL, 1100000, 'VND', 3, NOW(),
           CURRENT_DATE - 2, 'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa18:order')::uuid
    UNION ALL
    SELECT md5('qa18:n2')::uuid, md5('qa18:contract')::uuid, md5('qa18:i2')::uuid, o.id, o.customer_id,
           'HD-QA-DOT-1', 'SO-QA-DOT', 2, 'Batch 2', 'BEFORE_DELIVERY', md5('qa18:b2')::uuid, 2, 1100000, 'VND', NULL,
           NULL, NULL, 'NOT_DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa18:order')::uuid $q$);
SELECT pg_temp.expect_fail('IN6 a plan row opened twice', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
        order_number, seq, name, milestone, amount, currency, due_days, reached_at, due_date, status)
    SELECT gen_random_uuid(), md5('qa18:contract')::uuid, md5('qa18:i1')::uuid, o.id, o.customer_id, 'HD-QA-DOT-1',
           'SO-QA-DOT', 1, 'On signing', 'ON_SIGNING', 1100000, 'VND', 3, NOW(), CURRENT_DATE - 2, 'DUE'
      FROM ordering.customer_order o WHERE o.id = md5('qa18:order')::uuid $q$);

\echo '--- what an installment is never changes (15 §5.1)'
SELECT pg_temp.expect_fail('IN7 its amount changed', $q$
    UPDATE payment.installment SET amount = 1000000 WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_fail('IN8 its milestone moved once it came', $q$
    UPDATE payment.installment SET reached_at = NOW() - INTERVAL '1 day', due_date = CURRENT_DATE + 10
     WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_fail('IN9 PAID with nothing paid', $q$
    UPDATE payment.installment SET status = 'PAID', paid_at = NOW() WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_fail('IN10 paid without a receipt (check_installment_paid)', $q$
    UPDATE payment.installment SET paid_amount = 100000, status = 'PARTIALLY_PAID' WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_fail('IN11 deleted', $q$
    DELETE FROM payment.installment WHERE id = md5('qa18:n2')::uuid $q$);

\echo '--- the nightly sweep (15 §4.1 step 4)'
SELECT pg_temp.expect_ok('IN12 past its due day and unpaid: OVERDUE', $q$
    UPDATE payment.installment SET status = 'OVERDUE'
     WHERE status IN ('DUE', 'PARTIALLY_PAID') AND due_date < CURRENT_DATE AND id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_true('IN12a the installment on signing is overdue; the one before delivery has no due day',
    (SELECT status FROM payment.installment WHERE id = md5('qa18:n1')::uuid) = 'OVERDUE'
    AND (SELECT due_date FROM payment.installment WHERE id = md5('qa18:n2')::uuid) IS NULL);
SELECT pg_temp.expect_fail('IN13 OVERDUE back to DUE without being paid', $q$
    UPDATE payment.installment SET status = 'DUE' WHERE id = md5('qa18:n1')::uuid $q$);

\echo '--- receipts (15 §4.2, BR-03..BR-05)'
SELECT pg_temp.expect_fail('IN14 a receipt with no line', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                         recorded_at)
    SELECT gen_random_uuid(), c.customer_id, 'FT-QA18-0', CURRENT_DATE, 100000, 'VND', 'RECORDED',
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order c WHERE c.id = md5('qa18:order')::uuid $q$);
SELECT pg_temp.expect_fail('IN15 a receipt its lines do not add up to', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                         recorded_at)
    SELECT md5('qa18:r0')::uuid, c.customer_id, 'FT-QA18-0', CURRENT_DATE, 500000, 'VND', 'RECORDED',
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order c WHERE c.id = md5('qa18:order')::uuid;
    INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa18:r0')::uuid, md5('qa18:n1')::uuid, 400000);
    UPDATE payment.installment SET paid_amount = 400000 WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_fail('IN16 money received on a day after it was recorded', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                         recorded_at)
    SELECT gen_random_uuid(), c.customer_id, 'FT-QA18-0', CURRENT_DATE + 2, 400000, 'VND', 'RECORDED',
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order c WHERE c.id = md5('qa18:order')::uuid $q$);
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qa18:other')::uuid, 'QA cong ty khac', 'qa18-other@example.com', 'ACTIVE', 0, NOW());
SELECT pg_temp.expect_fail('IN17 another customer''s installment (BR-05)', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                         recorded_at)
    VALUES (md5('qa18:rx')::uuid, md5('qa18:other')::uuid, 'FT-QA18-X', CURRENT_DATE, 400000, 'VND', 'RECORDED',
            md5('demo:user:editor')::uuid, NOW());
    INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa18:rx')::uuid, md5('qa18:n1')::uuid, 400000);
    UPDATE payment.installment SET paid_amount = 400000 WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_ok('IN18 part of the overdue installment paid: it stays OVERDUE (15 §5.1)', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                         recorded_at)
    SELECT md5('qa18:r1')::uuid, c.customer_id, 'FT-QA18-1', CURRENT_DATE, 400000, 'VND', 'RECORDED',
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order c WHERE c.id = md5('qa18:order')::uuid;
    INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa18:r1')::uuid, md5('qa18:n1')::uuid, 400000);
    UPDATE payment.installment SET paid_amount = 400000 WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_fail('IN19 the same statement line recorded on a second live receipt (BR-03)', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                         recorded_at)
    SELECT md5('qa18:r2')::uuid, c.customer_id, 'FT-QA18-1', CURRENT_DATE, 100000, 'VND', 'RECORDED',
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order c WHERE c.id = md5('qa18:order')::uuid;
    INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa18:r2')::uuid, md5('qa18:n2')::uuid, 100000);
    UPDATE payment.installment SET paid_amount = 100000, status = 'PARTIALLY_PAID'
     WHERE id = md5('qa18:n2')::uuid $q$);
SELECT pg_temp.expect_fail('IN20 more paid than owed, nothing left over (BR-05)', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                         recorded_at)
    SELECT md5('qa18:r3')::uuid, c.customer_id, 'FT-QA18-3', CURRENT_DATE, 800000, 'VND', 'RECORDED',
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order c WHERE c.id = md5('qa18:order')::uuid;
    INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa18:r3')::uuid, md5('qa18:n1')::uuid, 800000);
    UPDATE payment.installment SET paid_amount = 1200000 WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_fail('IN21 a receipt edited (BR-04)', $q$
    UPDATE payment.payment_receipt SET reference = 'FT-QA18-9' WHERE id = md5('qa18:r1')::uuid $q$);
SELECT pg_temp.expect_fail('IN22 a receipt deleted (BR-04)', $q$
    DELETE FROM payment.payment_receipt WHERE id = md5('qa18:r1')::uuid $q$);
SELECT pg_temp.expect_fail('IN23 a receipt''s line taken off (BR-04)', $q$
    DELETE FROM payment.payment_receipt_line WHERE receipt_id = md5('qa18:r1')::uuid $q$);

\echo '--- a receipt voided (15 §4.4, BR-04)'
SELECT pg_temp.expect_fail('IN24 voided without saying why', $q$
    UPDATE payment.payment_receipt SET status = 'VOIDED', voided_at = NOW(), voided_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa18:r1')::uuid $q$);
SELECT pg_temp.expect_fail('IN25 voided while its installment still counts its money', $q$
    UPDATE payment.payment_receipt SET status = 'VOIDED', voided_at = NOW(), voided_by = md5('demo:user:editor')::uuid,
           void_reason = 'wrong customer'
     WHERE id = md5('qa18:r1')::uuid $q$);
SELECT pg_temp.expect_ok('IN26 voided, its installment put back: still OVERDUE, its due day past', $q$
    UPDATE payment.payment_receipt SET status = 'VOIDED', voided_at = NOW(), voided_by = md5('demo:user:editor')::uuid,
           void_reason = 'wrong customer'
     WHERE id = md5('qa18:r1')::uuid;
    UPDATE payment.installment SET paid_amount = 0 WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_fail('IN27 a voided receipt brought back', $q$
    UPDATE payment.payment_receipt SET status = 'RECORDED', voided_at = NULL, voided_by = NULL, void_reason = NULL
     WHERE id = md5('qa18:r1')::uuid $q$);
SELECT pg_temp.expect_ok('IN28 the statement line recorded again on the right receipt: the installment paid', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                         recorded_at)
    SELECT md5('qa18:r4')::uuid, c.customer_id, 'FT-QA18-1', CURRENT_DATE, 1500000, 'VND', 'RECORDED',
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order c WHERE c.id = md5('qa18:order')::uuid;
    INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa18:r4')::uuid, md5('qa18:n1')::uuid, 1100000),
           (gen_random_uuid(), md5('qa18:r4')::uuid, md5('qa18:n2')::uuid, 400000);
    UPDATE payment.installment SET paid_amount = 1100000, status = 'PAID', paid_at = NOW()
     WHERE id = md5('qa18:n1')::uuid;
    UPDATE payment.installment SET paid_amount = 400000, status = 'PARTIALLY_PAID'
     WHERE id = md5('qa18:n2')::uuid $q$);
SELECT pg_temp.expect_true('IN28a the paid and the receipts agree',
    (SELECT paid_amount FROM payment.installment WHERE id = md5('qa18:n1')::uuid) = 1100000
    AND (SELECT status FROM payment.installment WHERE id = md5('qa18:n2')::uuid) = 'PARTIALLY_PAID');

\echo '--- the order cancelled (15 §4.6 step 18)'
SELECT pg_temp.expect_ok('IN29 the unpaid installment CANCELLED, keeping what it was paid', $q$
    UPDATE payment.installment SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = md5('qa18:n2')::uuid $q$);
SELECT pg_temp.expect_fail('IN30 a PAID installment cancelled with what it was paid (status facts)', $q$
    UPDATE payment.installment SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = md5('qa18:n1')::uuid $q$);
SELECT pg_temp.expect_fail('IN31 a CANCELLED installment changed', $q$
    UPDATE payment.installment SET status = 'DUE', cancelled_at = NULL, reached_at = NOW()
     WHERE id = md5('qa18:n2')::uuid $q$);
SELECT pg_temp.expect_fail('IN32 a receipt for a CANCELLED installment (edge "tiền về sau khi huỷ đơn")', $q$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, status, recorded_by,
                                         recorded_at)
    SELECT md5('qa18:r5')::uuid, c.customer_id, 'FT-QA18-5', CURRENT_DATE, 100000, 'VND', 'RECORDED',
           md5('demo:user:editor')::uuid, NOW()
      FROM ordering.customer_order c WHERE c.id = md5('qa18:order')::uuid;
    INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
    VALUES (gen_random_uuid(), md5('qa18:r5')::uuid, md5('qa18:n2')::uuid, 100000) $q$);

\echo '--- who works on installments and receipts'
CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;
SELECT pg_temp.expect_true('IN33 the accountant records and voids; Sales and the customer read installments only',
    pg_temp.grants('ACCOUNTANT', 'payment-receipts:CREATE')
    AND pg_temp.grants('ACCOUNTANT', 'payment-receipts:UPDATE')
    AND pg_temp.grants('ACCOUNTANT', 'payment-installments:READ')
    AND pg_temp.grants('SALES_STAFF', 'payment-installments:READ')
    AND NOT pg_temp.grants('SALES_STAFF', 'payment-receipts:CREATE')
    AND pg_temp.grants('CUSTOMER', 'payment-installments:READ')
    AND NOT pg_temp.grants('CUSTOMER', 'payment-receipts:READ')
    AND NOT pg_temp.grants('WAREHOUSE_STAFF', 'payment-installments:READ'));

ROLLBACK;
