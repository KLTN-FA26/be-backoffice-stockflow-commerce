-- =============================================================================
-- Adversarial checks + happy paths: the sales contract of an order, its delivery schedule and payment plan, and the
-- buyer's legal profile (V20261108002200, team plan 4B-4; kltn-docs 14 §4.3, §5.2, BR-12, BR-13, BR-14, BR-23; 15 BR-12;
-- 18 BR-05). Needs the demo seed. Rolled back at the end.
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

-- Fixtures: the first customer's own SKU in cartons of 50, and a draft order of 20 cartons at 2,000 a piece with 10%
-- VAT - 2,200,000 with tax.
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qa17:product')::uuid, 'QAHD', 'QA ly hop dong', 'qa-hd', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa17:blank')::uuid, md5('qa17:product')::uuid, 'QA-HD-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa17:draft')::uuid, c.id, md5('qa17:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa17:snap')::uuid, md5('qa17:draft')::uuid, repeat('a', 64), 'https://example.com/a.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa17:own')::uuid, md5('qa17:product')::uuid, 'QA-HD-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-HD-OWN', 1, 0, NOW(), c.id, md5('qa17:snap')::uuid, md5('qa17:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa17:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-HD-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa17:order')::uuid, 'SO-QA-HD', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa17:line')::uuid, md5('qa17:order')::uuid, 'QA-HD-OWN', 1000, 2000, 'VND', md5('qa17:carton')::uuid, 20,
        50, 10, 200000);

\echo '--- the buyer''s legal profile (18 §3, BR-05)'
SELECT pg_temp.expect_fail('SC1 a legal profile without its representative', $q$
    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name,
                                                 representative_title)
    SELECT gen_random_uuid(), c.id, '1 Le Loi', ' ', 'Director' FROM customer.customer c ORDER BY c.id LIMIT 1 $q$);
SELECT pg_temp.expect_ok('SC2 the registered address, the legal representative and their title', $q$
    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name,
                                                 representative_title)
    SELECT md5('qa17:legal')::uuid, c.id, '1 Le Loi, Ho Chi Minh City', 'Tran Van B', 'Director'
      FROM customer.customer c ORDER BY c.id LIMIT 1 $q$);
SELECT pg_temp.expect_ok('SC3 a change of the representative kept in the customer''s history (18 §6)', $q$
    INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, new_value, changed_at)
    SELECT gen_random_uuid(), c.id, 'REPRESENTATIVE', 'Tran Van A', 'Tran Van B', NOW()
      FROM customer.customer c ORDER BY c.id LIMIT 1 $q$);

\echo '--- the contract, its schedule and plan (V20261108002200)'
SELECT pg_temp.expect_ok('SC4 a draft contract: one batch of the 20 cartons, everything on signing', $q$
    INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
        seller_address, seller_representative, seller_representative_title, buyer_name, buyer_address,
        buyer_representative, buyer_representative_title, free_sample_rounds)
    VALUES (md5('qa17:contract')::uuid, 'HD-QA-1', md5('qa17:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
            'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
    INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
    VALUES (md5('qa17:b1')::uuid, md5('qa17:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32);
    INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
    VALUES (md5('qa17:b1l')::uuid, md5('qa17:b1')::uuid, md5('qa17:line')::uuid, 20);
    INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days)
    VALUES (md5('qa17:i1')::uuid, md5('qa17:contract')::uuid, 1, 'On signing', 'ON_SIGNING', 100, 2200000, 0) $q$);
SELECT pg_temp.expect_fail('SC5 a second live contract for the order (14 BR-12)', $q$
    INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
        seller_address, seller_representative, seller_representative_title, buyer_name, buyer_address,
        buyer_representative, buyer_representative_title, free_sample_rounds)
    VALUES (gen_random_uuid(), 'HD-QA-2', md5('qa17:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
            'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2) $q$);
SELECT pg_temp.expect_fail('SC6 19 of the line''s 20 cartons scheduled (14 BR-23)', $q$
    UPDATE ordering.delivery_batch_line SET cartons = 19 WHERE id = md5('qa17:b1l')::uuid $q$);
SELECT pg_temp.expect_fail('SC7 a plan short of the total with tax by a dong (14 BR-13)', $q$
    UPDATE ordering.contract_payment_installment SET amount = 2199999 WHERE id = md5('qa17:i1')::uuid $q$);
SELECT pg_temp.expect_fail('SC8 a second batch starting before the first (14 BR-23)', $q$
    UPDATE ordering.delivery_batch_line SET cartons = 10 WHERE id = md5('qa17:b1l')::uuid;
    INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
    VALUES (md5('qa17:b2')::uuid, md5('qa17:contract')::uuid, 2, CURRENT_DATE + 20, CURRENT_DATE + 20);
    INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
    VALUES (gen_random_uuid(), md5('qa17:b2')::uuid, md5('qa17:line')::uuid, 10) $q$);
SELECT pg_temp.expect_ok('SC9 two batches, the second two weeks later; its goods paid before it is handed over', $q$
    UPDATE ordering.delivery_batch_line SET cartons = 10 WHERE id = md5('qa17:b1l')::uuid;
    INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
    VALUES (md5('qa17:b2')::uuid, md5('qa17:contract')::uuid, 2, CURRENT_DATE + 44, CURRENT_DATE + 44);
    INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
    VALUES (gen_random_uuid(), md5('qa17:b2')::uuid, md5('qa17:line')::uuid, 10);
    UPDATE ordering.contract_payment_installment SET amount = 1100000, percent = 50 WHERE id = md5('qa17:i1')::uuid;
    INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, amount,
                                                       delivery_batch_id)
    VALUES (md5('qa17:i2')::uuid, md5('qa17:contract')::uuid, 2, 'Batch 2', 'BEFORE_DELIVERY', 1100000,
            md5('qa17:b2')::uuid) $q$);
SELECT pg_temp.expect_fail('SC10 an installment on signing tied to a batch', $q$
    UPDATE ordering.contract_payment_installment SET delivery_batch_id = md5('qa17:b1')::uuid
     WHERE id = md5('qa17:i1')::uuid $q$);
SELECT pg_temp.expect_fail('SC11 an installment before delivery with a due day', $q$
    UPDATE ordering.contract_payment_installment SET due_days = 5 WHERE id = md5('qa17:i2')::uuid $q$);
SELECT pg_temp.expect_true('SC12a the demo customer has no credit approved',
    NOT EXISTS (SELECT 1 FROM customer.credit_profile p WHERE p.customer_id = (SELECT id FROM customer.customer
                ORDER BY id LIMIT 1) AND p.allow_credit AND p.credit_limit > 0));
SELECT pg_temp.expect_fail('SC12 an installment after delivery without credit accounting approved (15 BR-12)', $q$
    UPDATE ordering.contract_payment_installment SET milestone = 'AFTER_DELIVERY', due_days = 30
     WHERE id = md5('qa17:i2')::uuid $q$);

\echo '--- the contract''s life (14 §5.2, BR-14)'
SELECT pg_temp.expect_fail('SC13 a draft signed without being sent', $q$
    UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = md5('demo:user:editor')::uuid,
           signed_on = CURRENT_DATE, signed_file_key = 'documents/s.pdf', signed_file_name = 's.pdf'
     WHERE id = md5('qa17:contract')::uuid $q$);
SELECT pg_temp.expect_fail('SC14 sent without the PDF the customer received', $q$
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa17:contract')::uuid $q$);
SELECT pg_temp.expect_ok('SC15 sent, with its PDF', $q$
    UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = md5('demo:user:editor')::uuid,
           sent_document_key = 'documents/hd-qa-1.pdf'
     WHERE id = md5('qa17:contract')::uuid $q$);
SELECT pg_temp.expect_fail('SC16 a sent contract''s free sample rounds changed', $q$
    UPDATE ordering.sales_contract SET free_sample_rounds = 5 WHERE id = md5('qa17:contract')::uuid $q$);
SELECT pg_temp.expect_fail('SC17 a sent contract''s delivery window moved', $q$
    UPDATE ordering.delivery_batch SET window_end = window_end + 1 WHERE id = md5('qa17:b2')::uuid $q$);
SELECT pg_temp.expect_fail('SC18 signed on a day after it was recorded', $q$
    UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = md5('demo:user:editor')::uuid,
           signed_on = CURRENT_DATE + 3, signed_file_key = 'documents/s.pdf', signed_file_name = 's.pdf'
     WHERE id = md5('qa17:contract')::uuid $q$);
SELECT pg_temp.expect_ok('SC19 signed, with the signed file and the day', $q$
    UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = md5('demo:user:editor')::uuid,
           signed_on = CURRENT_DATE, signed_file_key = 'documents/s.pdf', signed_file_name = 's.pdf'
     WHERE id = md5('qa17:contract')::uuid $q$);
SELECT pg_temp.expect_fail('SC20 a signed contract cancelled', $q$
    UPDATE ordering.sales_contract SET status = 'CANCELLED', cancelled_at = NOW(),
           cancelled_by = md5('demo:user:editor')::uuid, cancellation_reason = 'x', signed_at = NULL,
           signed_by = NULL, signed_on = NULL, signed_file_key = NULL, signed_file_name = NULL
     WHERE id = md5('qa17:contract')::uuid $q$);

\echo '--- who works on contracts'
CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;
SELECT pg_temp.expect_true('SC21 Sales draws up, sends and records signed; accounting and the manager read',
    pg_temp.grants('SALES_STAFF', 'sales-contracts:CREATE')
    AND pg_temp.grants('SALES_STAFF', 'sales-contracts:UPDATE')
    AND pg_temp.grants('SALES_STAFF', 'sales-contracts:APPROVE')
    AND pg_temp.grants('ACCOUNTANT', 'sales-contracts:READ')
    AND NOT pg_temp.grants('ACCOUNTANT', 'sales-contracts:UPDATE')
    AND pg_temp.grants('SALES_MANAGER', 'sales-contracts:READ')
    AND NOT pg_temp.grants('SALES_MANAGER', 'sales-contracts:APPROVE')
    AND NOT pg_temp.grants('CUSTOMER', 'sales-contracts:READ'));

ROLLBACK;
