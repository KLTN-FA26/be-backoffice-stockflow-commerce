-- =============================================================================
-- Adversarial checks + happy paths: production order slips (V20261110000200, team plan 4A-3; kltn-docs 17 §4.3
-- step 7, BR-23): one slip per production order, only once it is sent, kept as it was sent. The fixtures are QA 21's
-- (a confirmed order of a signed contract shipping from HCM, a producer). Needs the demo seed (demo.editor draws up,
-- demo.approver approves). Rolled back at the end.
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
VALUES (md5('qa30:product')::uuid, 'QASLP', 'QA ly dot', 'qa-slp', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa30:blank')::uuid, md5('qa30:product')::uuid, 'QA-SLP-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa30:draft')::uuid, c.id, md5('qa30:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa30:snap')::uuid, md5('qa30:draft')::uuid, repeat('c', 64), 'https://example.com/c.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa30:own')::uuid, md5('qa30:product')::uuid, 'QA-SLP-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-SLP-OWN', 1, 0, NOW(), c.id, md5('qa30:snap')::uuid, md5('qa30:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa30:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-SLP-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa30:order')::uuid, 'SO-QA-SLP', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa30:line')::uuid, md5('qa30:order')::uuid, 'QA-SLP-OWN', 1000, 2000, 'VND', md5('qa30:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds)
VALUES (md5('qa30:contract')::uuid, 'HD-QA-SLP-1', md5('qa30:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa30:b1')::uuid, md5('qa30:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32),
       (md5('qa30:b2')::uuid, md5('qa30:contract')::uuid, 2, CURRENT_DATE + 44, CURRENT_DATE + 44);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (gen_random_uuid(), md5('qa30:b1')::uuid, md5('qa30:line')::uuid, 10),
       (gen_random_uuid(), md5('qa30:b2')::uuid, md5('qa30:line')::uuid, 10);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
VALUES (md5('qa30:i1')::uuid, md5('qa30:contract')::uuid, 1, 'Before delivery', 'BEFORE_DELIVERY', 100, 2200000,
        NULL, NULL);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
-- Sent and signed straight (the contract's own triggers are 17's script), confirmed, shipping from HCM.
SET LOCAL session_replication_role = replica;
UPDATE ordering.sales_contract SET status = 'SIGNED', sent_at = NOW() - INTERVAL '2 days',
       sent_by = md5('demo:user:editor')::uuid, sent_document_key = 'documents/hd-qa-slp-1.pdf', signed_at = NOW(),
       signed_by = md5('demo:user:editor')::uuid, signed_on = CURRENT_DATE, signed_file_key = 'documents/s.pdf',
       signed_file_name = 's.pdf'
 WHERE id = md5('qa30:contract')::uuid;
UPDATE ordering.customer_order o SET status = 'CONFIRMED', contract_signed_at = NOW(), shipping_warehouse_id = w.id,
       shipping_warehouse_chosen_at = NOW(), shipping_warehouse_chosen_by = md5('demo:user:editor')::uuid
  FROM warehouse.warehouse w WHERE w.prefix = 'HCM' AND o.id = md5('qa30:order')::uuid;
SET LOCAL session_replication_role = origin;
INSERT INTO ordering.producer (id, code, name, channel, email)
VALUES (md5('qa30:producer')::uuid, 'QA-XUONG-SLP', 'QA xuong in', 'EMAIL', 'don@qa-xuong.vn');
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

CREATE FUNCTION pg_temp.draft(po_id UUID, warehouse_prefix TEXT, sample_default BOOLEAN, sample BOOLEAN, waiver TEXT,
                              po_code TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                           receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                           sample_required_by_default, sample_waiver_reason, drawn_up_by)
    SELECT po_id, po_code, o.id, md5('qa30:b2')::uuid, o.customer_id, md5('qa30:producer')::uuid, w.id,
           CURRENT_DATE + 44, 'DRAFT', sample, sample_default, waiver, md5('demo:user:editor')::uuid
      FROM ordering.customer_order o, warehouse.warehouse w
     WHERE o.id = md5('qa30:order')::uuid AND w.prefix = warehouse_prefix
$$;
CREATE FUNCTION pg_temp.line(po_id UUID, cartons INTEGER, line_sku TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                                design_snapshot_id, print_file_checksum)
    VALUES (gen_random_uuid(), po_id, md5('qa30:line')::uuid, line_sku, cartons, 50, md5('qa30:snap')::uuid,
            repeat('c', 64))
$$;

-- Two production orders of batch 2: po1 is sent below, po2 stays a draft until it is approved together with its slip.
SELECT pg_temp.draft(md5('qa30:po1')::uuid, 'HCM', TRUE, TRUE, NULL, 'PRD-20261110-0901');
SELECT pg_temp.line(md5('qa30:po1')::uuid, 5, 'QA-SLP-OWN');
SELECT pg_temp.draft(md5('qa30:po2')::uuid, 'HCM', TRUE, TRUE, NULL, 'PRD-20261110-0902');
SELECT pg_temp.line(md5('qa30:po2')::uuid, 5, 'QA-SLP-OWN');
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
CREATE FUNCTION pg_temp.slip(po_id UUID, slip_key TEXT, digest TEXT, bytes BIGINT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order_slip (production_order_id, storage_key, sha256, size_bytes)
    VALUES (po_id, slip_key, digest, bytes)
$$;

\echo '--- only a sent production order has a slip (17 §4.3 step 7)'
SELECT pg_temp.expect_fail('SL1 a slip for a draft', $q$
    SELECT pg_temp.slip(md5('qa30:po1')::uuid, 'documents/2026/11/10/a.pdf', repeat('a', 64), 2048) $q$);
SELECT pg_temp.expect_fail('SL2 a slip for a production order awaiting approval', $q$
    UPDATE ordering.production_order SET status = 'PENDING_APPROVAL', submitted_at = NOW()
     WHERE id = md5('qa30:po1')::uuid;
    SELECT pg_temp.slip(md5('qa30:po1')::uuid, 'documents/2026/11/10/a.pdf', repeat('a', 64), 2048) $q$);
SELECT pg_temp.expect_ok('SL3 submitted and approved by someone else, so sent', $q$
    UPDATE ordering.production_order SET status = 'PENDING_APPROVAL', submitted_at = NOW()
     WHERE id = md5('qa30:po1')::uuid;
    UPDATE ordering.production_order SET status = 'SENT', approved_by = md5('demo:user:approver')::uuid,
           approved_at = NOW(), sent_at = NOW() WHERE id = md5('qa30:po1')::uuid $q$);

\echo '--- what a slip is: a PDF kept in the documents category, with its SHA-256'
SELECT pg_temp.expect_fail('SL4 a key outside the documents category', $q$
    SELECT pg_temp.slip(md5('qa30:po1')::uuid, 'design-renders/2026/11/10/a.pdf', repeat('a', 64), 2048) $q$);
SELECT pg_temp.expect_fail('SL5 a digest that is not lower-case hex', $q$
    SELECT pg_temp.slip(md5('qa30:po1')::uuid, 'documents/2026/11/10/a.pdf', repeat('A', 64), 2048) $q$);
SELECT pg_temp.expect_fail('SL5a a digest with a letter that is not hex', $q$
    SELECT pg_temp.slip(md5('qa30:po1')::uuid, 'documents/2026/11/10/a.pdf', repeat('a', 63) || 'g', 2048) $q$);
SELECT pg_temp.expect_fail('SL6 an empty file', $q$
    SELECT pg_temp.slip(md5('qa30:po1')::uuid, 'documents/2026/11/10/a.pdf', repeat('a', 64), 0) $q$);
SELECT pg_temp.expect_fail('SL6a larger than a document may be (25 MiB)', $q$
    SELECT pg_temp.slip(md5('qa30:po1')::uuid, 'documents/2026/11/10/a.pdf', repeat('a', 64), 26214401) $q$);
SELECT pg_temp.expect_fail('SL7 a slip of no production order', $q$
    SELECT pg_temp.slip(gen_random_uuid(), 'documents/2026/11/10/z.pdf', repeat('a', 64), 2048) $q$);
SELECT pg_temp.expect_ok('SL8 the sent production order''s slip', $q$
    SELECT pg_temp.slip(md5('qa30:po1')::uuid, 'documents/2026/11/10/a.pdf', repeat('a', 64), 2048) $q$);

\echo '--- one per production order, kept as it was sent'
SELECT pg_temp.expect_fail('SL9 a second slip for the same production order', $q$
    SELECT pg_temp.slip(md5('qa30:po1')::uuid, 'documents/2026/11/10/b.pdf', repeat('b', 64), 2048) $q$);
SELECT pg_temp.expect_fail('SL10 its file replaced', $q$
    UPDATE ordering.production_order_slip SET storage_key = 'documents/2026/11/10/b.pdf', sha256 = repeat('b', 64)
     WHERE production_order_id = md5('qa30:po1')::uuid $q$);
SELECT pg_temp.expect_fail('SL11 removed', $q$
    DELETE FROM ordering.production_order_slip WHERE production_order_id = md5('qa30:po1')::uuid $q$);
SELECT pg_temp.expect_fail('SL12 another production order''s slip naming the same file', $q$
    UPDATE ordering.production_order SET status = 'PENDING_APPROVAL', submitted_at = NOW()
     WHERE id = md5('qa30:po2')::uuid;
    UPDATE ordering.production_order SET status = 'SENT', approved_by = md5('demo:user:approver')::uuid,
           approved_at = NOW(), sent_at = NOW() WHERE id = md5('qa30:po2')::uuid;
    SELECT pg_temp.slip(md5('qa30:po2')::uuid, 'documents/2026/11/10/a.pdf', repeat('a', 64), 2048) $q$);
SELECT pg_temp.expect_ok('SL13 the slip written before the approval, in the same transaction (checked at commit)', $q$
    UPDATE ordering.production_order SET status = 'PENDING_APPROVAL', submitted_at = NOW()
     WHERE id = md5('qa30:po2')::uuid;
    SELECT pg_temp.slip(md5('qa30:po2')::uuid, 'documents/2026/11/10/c.pdf', repeat('c', 64), 4096);
    UPDATE ordering.production_order SET status = 'SENT', approved_by = md5('demo:user:approver')::uuid,
           approved_at = NOW(), sent_at = NOW() WHERE id = md5('qa30:po2')::uuid $q$);
SELECT pg_temp.expect_true('SL14 each sent production order has its one slip',
    (SELECT count(*) FROM ordering.production_order_slip
      WHERE production_order_id IN (md5('qa30:po1')::uuid, md5('qa30:po2')::uuid)) = 2);
SELECT pg_temp.expect_ok('SL15 cancelled after it was sent', $q$
    UPDATE ordering.production_order SET status = 'CANCELLED', cancelled_by = md5('demo:user:editor')::uuid,
           cancelled_at = NOW(), cancellation_reason = 'Customer changed the file'
     WHERE id = md5('qa30:po2')::uuid $q$);
SELECT pg_temp.expect_true('SL15a the cancelled production order keeps its slip',
    EXISTS (SELECT 1 FROM ordering.production_order_slip WHERE production_order_id = md5('qa30:po2')::uuid));

ROLLBACK;
