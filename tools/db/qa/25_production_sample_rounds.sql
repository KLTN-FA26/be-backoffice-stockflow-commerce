-- =============================================================================
-- Adversarial checks + happy paths: print sample rounds and the producer's acceptance (V20261108003100, team plan
-- 4A-4; kltn-docs 17 §4.3 steps 8-10, §5.3, BR-24; 13 BR-13; 14 BR-18). Needs the demo seed (demo.editor is the
-- coordinator, demo.approver approves). Fixtures as 21_production_orders.sql. Rolled back at the end.
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
VALUES (md5('qa25:product')::uuid, 'QAPRD', 'QA ly dot', 'qa-prd', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa25:blank')::uuid, md5('qa25:product')::uuid, 'QA-PRD-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa25:draft')::uuid, c.id, md5('qa25:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa25:snap')::uuid, md5('qa25:draft')::uuid, repeat('c', 64), 'https://example.com/c.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa25:own')::uuid, md5('qa25:product')::uuid, 'QA-PRD-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-PRD-OWN', 1, 0, NOW(), c.id, md5('qa25:snap')::uuid, md5('qa25:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa25:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-PRD-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa25:order')::uuid, 'SO-QA-PRD', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa25:line')::uuid, md5('qa25:order')::uuid, 'QA-PRD-OWN', 1000, 2000, 'VND', md5('qa25:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds)
VALUES (md5('qa25:contract')::uuid, 'HD-QA-PRD-1', md5('qa25:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa25:b1')::uuid, md5('qa25:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32),
       (md5('qa25:b2')::uuid, md5('qa25:contract')::uuid, 2, CURRENT_DATE + 44, CURRENT_DATE + 44);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (gen_random_uuid(), md5('qa25:b1')::uuid, md5('qa25:line')::uuid, 10),
       (gen_random_uuid(), md5('qa25:b2')::uuid, md5('qa25:line')::uuid, 10);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
VALUES (md5('qa25:i1')::uuid, md5('qa25:contract')::uuid, 1, 'Before delivery', 'BEFORE_DELIVERY', 100, 2200000,
        NULL, NULL);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
-- Sent and signed straight (the contract's own triggers are 17's script), confirmed, shipping from HCM.
SET LOCAL session_replication_role = replica;
UPDATE ordering.sales_contract SET status = 'SIGNED', sent_at = NOW() - INTERVAL '2 days',
       sent_by = md5('demo:user:editor')::uuid, sent_document_key = 'documents/hd-qa-prd-1.pdf', signed_at = NOW(),
       signed_by = md5('demo:user:editor')::uuid, signed_on = CURRENT_DATE, signed_file_key = 'documents/s.pdf',
       signed_file_name = 's.pdf'
 WHERE id = md5('qa25:contract')::uuid;
UPDATE ordering.customer_order o SET status = 'CONFIRMED', contract_signed_at = NOW(), shipping_warehouse_id = w.id,
       shipping_warehouse_chosen_at = NOW(), shipping_warehouse_chosen_by = md5('demo:user:editor')::uuid
  FROM warehouse.warehouse w WHERE w.prefix = 'HCM' AND o.id = md5('qa25:order')::uuid;
SET LOCAL session_replication_role = origin;
INSERT INTO ordering.producer (id, code, name, channel, email)
VALUES (md5('qa25:producer')::uuid, 'QA-XUONG', 'QA xuong in', 'EMAIL', 'don@qa-xuong.vn');
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

CREATE FUNCTION pg_temp.draft(po_id UUID, warehouse_prefix TEXT, sample_default BOOLEAN, sample BOOLEAN, waiver TEXT,
                              po_code TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                           receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                           sample_required_by_default, sample_waiver_reason, drawn_up_by)
    SELECT po_id, po_code, o.id, md5('qa25:b2')::uuid, o.customer_id, md5('qa25:producer')::uuid, w.id,
           CURRENT_DATE + 44, 'DRAFT', sample, sample_default, waiver, md5('demo:user:editor')::uuid
      FROM ordering.customer_order o, warehouse.warehouse w
     WHERE o.id = md5('qa25:order')::uuid AND w.prefix = warehouse_prefix
$$;
CREATE FUNCTION pg_temp.line(po_id UUID, cartons INTEGER, line_sku TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                                design_snapshot_id, print_file_checksum)
    VALUES (gen_random_uuid(), po_id, md5('qa25:line')::uuid, line_sku, cartons, 50, md5('qa25:snap')::uuid,
            repeat('c', 64))
$$;

-- The customer's portal account answers their samples (13 BR-13); the demo customer may have none yet.
INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at)
VALUES (md5('qa25:portal')::uuid, 'qa25-portal', 'qa25-portal@example.com', '!qa', 'ACTIVE', 0, NOW());
UPDATE customer.customer c SET user_id = COALESCE(c.user_id, md5('qa25:portal')::uuid)
  FROM ordering.customer_order o WHERE o.id = md5('qa25:order')::uuid AND c.id = o.customer_id;
CREATE FUNCTION pg_temp.portal() RETURNS UUID LANGUAGE sql AS $$
    SELECT c.user_id FROM customer.customer c JOIN ordering.customer_order o ON o.customer_id = c.id
     WHERE o.id = md5('qa25:order')::uuid
$$;
CREATE FUNCTION pg_temp.round(round_id UUID, po_id UUID, round_no INTEGER, reason TEXT) RETURNS void
    LANGUAGE sql AS $$
    INSERT INTO ordering.production_order_sample_round (id, production_order_id, round_no, reprint_reason, opened_by,
                                                        opened_at)
    VALUES (round_id, po_id, round_no, reason, md5('demo:user:editor')::uuid, NOW())
$$;
CREATE FUNCTION pg_temp.media(round_id UUID, media_key TEXT, media_type TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order_sample_media (id, sample_round_id, storage_key, file_name, content_type,
                                                        size_bytes, uploaded_by, uploaded_at)
    VALUES (gen_random_uuid(), round_id, media_key, 'sample', media_type, 1000, md5('demo:user:editor')::uuid, NOW())
$$;

-- Two production orders of batch 2, sent: PO1 wants a print sample, PO2's was waived.
SELECT pg_temp.draft(md5('qa25:po1')::uuid, 'HCM', TRUE, TRUE, NULL, 'PRD-20261109-2501');
SELECT pg_temp.line(md5('qa25:po1')::uuid, 10, 'QA-PRD-OWN');
SELECT pg_temp.draft(md5('qa25:po2')::uuid, 'HCM', TRUE, FALSE, 'a re-run of a tested design', 'PRD-20261109-2502');
SELECT pg_temp.line(md5('qa25:po2')::uuid, 10, 'QA-PRD-OWN');
UPDATE ordering.production_order SET status = 'PENDING_APPROVAL', submitted_at = NOW()
 WHERE id IN (md5('qa25:po1')::uuid, md5('qa25:po2')::uuid);
UPDATE ordering.production_order SET status = 'SENT', approved_by = md5('demo:user:approver')::uuid,
       approved_at = NOW(), sent_at = NOW()
 WHERE id IN (md5('qa25:po1')::uuid, md5('qa25:po2')::uuid);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- accepted with the producer''s day (17 §4.3 step 8)'
SELECT pg_temp.expect_fail('SR1 a sample round before the producer accepted', $q$
    SELECT pg_temp.round(md5('qa25:r1')::uuid, md5('qa25:po1')::uuid, 1, NULL);
    SELECT pg_temp.media(md5('qa25:r1')::uuid, 'print-samples/2026/11/09/r1.jpg', 'image/jpeg') $q$);
SELECT pg_temp.expect_fail('SR2 accepted without the producer''s expected completion day', $q$
    UPDATE ordering.production_order SET status = 'ACCEPTED' WHERE id = md5('qa25:po1')::uuid $q$);
SELECT pg_temp.expect_fail('SR3 an expected completion day while it is only sent (4A-7)', $q$
    UPDATE ordering.production_order SET expected_completion_date = CURRENT_DATE + 20
     WHERE id = md5('qa25:po1')::uuid $q$);
SELECT pg_temp.expect_ok('SR4 accepted, done in 20 days', $q$
    UPDATE ordering.production_order SET status = 'ACCEPTED', expected_completion_date = CURRENT_DATE + 20
     WHERE id IN (md5('qa25:po1')::uuid, md5('qa25:po2')::uuid) $q$);
SELECT pg_temp.expect_fail('SR4a its day cleared once accepted', $q$
    UPDATE ordering.production_order SET expected_completion_date = NULL WHERE id = md5('qa25:po1')::uuid $q$);

\echo '--- a print sample to approve: no mass printing before the customer approves (BR-24)'
SELECT pg_temp.expect_fail('SR5 sample pending before round 1 is uploaded', $q$
    UPDATE ordering.production_order SET status = 'SAMPLE_PENDING' WHERE id = md5('qa25:po1')::uuid $q$);
SELECT pg_temp.expect_fail('SR6 in production by hand with a sample to approve', $q$
    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW()
     WHERE id = md5('qa25:po1')::uuid $q$);
SELECT pg_temp.expect_fail('SR7 round 1 with no photo or video (at commit)', $q$
    SELECT pg_temp.round(md5('qa25:r1')::uuid, md5('qa25:po1')::uuid, 1, NULL) $q$);
SELECT pg_temp.expect_fail('SR8 round 1 with a reprint reason', $q$
    SELECT pg_temp.round(md5('qa25:r1')::uuid, md5('qa25:po1')::uuid, 1, 'PRINT_DEFECT');
    SELECT pg_temp.media(md5('qa25:r1')::uuid, 'print-samples/2026/11/09/r1.jpg', 'image/jpeg') $q$);
SELECT pg_temp.expect_fail('SR9 a PDF as a sample photo', $q$
    SELECT pg_temp.round(md5('qa25:r1')::uuid, md5('qa25:po1')::uuid, 1, NULL);
    SELECT pg_temp.media(md5('qa25:r1')::uuid, 'print-samples/2026/11/09/r1.pdf', 'application/pdf') $q$);
SELECT pg_temp.expect_fail('SR9a a photo stored outside the print-samples category', $q$
    SELECT pg_temp.round(md5('qa25:r1')::uuid, md5('qa25:po1')::uuid, 1, NULL);
    SELECT pg_temp.media(md5('qa25:r1')::uuid, 'return-evidence/2026/11/09/r1.jpg', 'image/jpeg') $q$);
SELECT pg_temp.expect_fail('SR9b a round on a production order without a sample to approve', $q$
    SELECT pg_temp.round(md5('qa25:r9')::uuid, md5('qa25:po2')::uuid, 1, NULL);
    SELECT pg_temp.media(md5('qa25:r9')::uuid, 'print-samples/2026/11/09/r9.jpg', 'image/jpeg') $q$);
SELECT pg_temp.expect_ok('SR10 round 1 uploaded with two photos: the sample is pending', $q$
    SELECT pg_temp.round(md5('qa25:r1')::uuid, md5('qa25:po1')::uuid, 1, NULL);
    SELECT pg_temp.media(md5('qa25:r1')::uuid, 'print-samples/2026/11/09/r1a.jpg', 'image/jpeg');
    SELECT pg_temp.media(md5('qa25:r1')::uuid, 'print-samples/2026/11/09/r1b.png', 'image/png');
    UPDATE ordering.production_order SET status = 'SAMPLE_PENDING' WHERE id = md5('qa25:po1')::uuid $q$);
SELECT pg_temp.expect_fail('SR11 round 2 while round 1 waits for the customer', $q$
    SELECT pg_temp.round(md5('qa25:r2')::uuid, md5('qa25:po1')::uuid, 2, 'PRINT_DEFECT');
    SELECT pg_temp.media(md5('qa25:r2')::uuid, 'print-samples/2026/11/09/r2.jpg', 'image/jpeg') $q$);
SELECT pg_temp.expect_ok('SR11a a physical sample recorded while it waits', $q$
    UPDATE ordering.production_order_sample_round SET physical_sample_sent_on = CURRENT_DATE
     WHERE id = md5('qa25:r1')::uuid $q$);

\echo '--- answered once, by the customer who owns it (13 BR-13)'
SELECT pg_temp.expect_fail('SR12 answered by staff, not the customer''s account', $q$
    UPDATE ordering.production_order_sample_round SET result = 'APPROVED', answered_by = md5('demo:user:editor')::uuid,
           answered_at = NOW() WHERE id = md5('qa25:r1')::uuid $q$);
SELECT pg_temp.expect_fail('SR13 a reprint requested without saying what to change', $q$
    UPDATE ordering.production_order_sample_round SET result = 'REPRINT_REQUESTED', answered_by = pg_temp.portal(),
           answered_at = NOW() WHERE id = md5('qa25:r1')::uuid $q$);
SELECT pg_temp.expect_fail('SR14 an approval with a note', $q$
    UPDATE ordering.production_order_sample_round SET result = 'APPROVED', answered_by = pg_temp.portal(),
           answered_at = NOW(), customer_note = 'ok' WHERE id = md5('qa25:r1')::uuid $q$);
SELECT pg_temp.expect_fail('SR14a answered with no time', $q$
    UPDATE ordering.production_order_sample_round SET result = 'APPROVED', answered_by = pg_temp.portal()
     WHERE id = md5('qa25:r1')::uuid $q$);
SELECT pg_temp.expect_fail('SR15 in production while no round is approved', $q$
    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW()
     WHERE id = md5('qa25:po1')::uuid $q$);
SELECT pg_temp.expect_ok('SR16 round 1 sent back by the customer with a note', $q$
    UPDATE ordering.production_order_sample_round SET result = 'REPRINT_REQUESTED', answered_by = pg_temp.portal(),
           answered_at = NOW(), customer_note = 'the logo is smudged' WHERE id = md5('qa25:r1')::uuid $q$);
SELECT pg_temp.expect_fail('SR17 its answer changed', $q$
    UPDATE ordering.production_order_sample_round SET result = 'APPROVED', customer_note = NULL
     WHERE id = md5('qa25:r1')::uuid $q$);
SELECT pg_temp.expect_fail('SR17a its physical sample changed once answered', $q$
    UPDATE ordering.production_order_sample_round SET physical_sample_sent_on = CURRENT_DATE - 1
     WHERE id = md5('qa25:r1')::uuid $q$);
SELECT pg_temp.expect_fail('SR18 a photo added to the answered round', $q$
    SELECT pg_temp.media(md5('qa25:r1')::uuid, 'print-samples/2026/11/09/r1c.jpg', 'image/jpeg') $q$);
SELECT pg_temp.expect_fail('SR18a a photo of the answered round changed', $q$
    UPDATE ordering.production_order_sample_media SET file_name = 'other' WHERE sample_round_id = md5('qa25:r1')::uuid $q$);

\echo '--- a new round after a reprint request, classified (17 §4.3 step 9)'
SELECT pg_temp.expect_fail('SR19 round 2 without its reprint reason', $q$
    SELECT pg_temp.round(md5('qa25:r2')::uuid, md5('qa25:po1')::uuid, 2, NULL);
    SELECT pg_temp.media(md5('qa25:r2')::uuid, 'print-samples/2026/11/09/r2.jpg', 'image/jpeg') $q$);
SELECT pg_temp.expect_fail('SR19a round 2 with a reason that is not one', $q$
    SELECT pg_temp.round(md5('qa25:r2')::uuid, md5('qa25:po1')::uuid, 2, 'WHIM');
    SELECT pg_temp.media(md5('qa25:r2')::uuid, 'print-samples/2026/11/09/r2.jpg', 'image/jpeg') $q$);
SELECT pg_temp.expect_fail('SR20 round 3 with no round 2', $q$
    SELECT pg_temp.round(md5('qa25:r2')::uuid, md5('qa25:po1')::uuid, 3, 'PRINT_DEFECT');
    SELECT pg_temp.media(md5('qa25:r2')::uuid, 'print-samples/2026/11/09/r2.jpg', 'image/jpeg') $q$);
SELECT pg_temp.expect_ok('SR21 round 2, a print defect, with a video', $q$
    SELECT pg_temp.round(md5('qa25:r2')::uuid, md5('qa25:po1')::uuid, 2, 'PRINT_DEFECT');
    SELECT pg_temp.media(md5('qa25:r2')::uuid, 'print-samples/2026/11/09/r2.mp4', 'video/mp4') $q$);
SELECT pg_temp.expect_fail('SR22 its reprint reason reclassified', $q$
    UPDATE ordering.production_order_sample_round SET reprint_reason = 'CUSTOMER_CHANGE'
     WHERE id = md5('qa25:r2')::uuid $q$);
SELECT pg_temp.expect_fail('SR22a renumbered', $q$
    UPDATE ordering.production_order_sample_round SET round_no = 5 WHERE id = md5('qa25:r2')::uuid $q$);
SELECT pg_temp.expect_fail('SR23 a round deleted', $q$
    DELETE FROM ordering.production_order_sample_round WHERE id = md5('qa25:r2')::uuid $q$);
SELECT pg_temp.expect_fail('SR23a a photo deleted', $q$
    DELETE FROM ordering.production_order_sample_media WHERE sample_round_id = md5('qa25:r1')::uuid $q$);
SELECT pg_temp.expect_ok('SR24 round 2 approved by the customer: mass printing starts', $q$
    UPDATE ordering.production_order_sample_round SET result = 'APPROVED', answered_by = pg_temp.portal(),
           answered_at = NOW() WHERE id = md5('qa25:r2')::uuid;
    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW()
     WHERE id = md5('qa25:po1')::uuid $q$);
SELECT pg_temp.expect_true('SR25 rounds counted against the free ones: round 1 only, the defect is not (14 BR-18)',
    (SELECT count(*) FROM ordering.production_order_sample_round
      WHERE production_order_id = md5('qa25:po1')::uuid
        AND (round_no = 1 OR reprint_reason = 'CUSTOMER_CHANGE')) = 1
    AND (SELECT free_sample_rounds FROM ordering.sales_contract WHERE id = md5('qa25:contract')::uuid) = 2);
SELECT pg_temp.expect_fail('SR25a a round opened once mass printing started', $q$
    SELECT pg_temp.round(md5('qa25:r3')::uuid, md5('qa25:po1')::uuid, 3, 'CUSTOMER_CHANGE');
    SELECT pg_temp.media(md5('qa25:r3')::uuid, 'print-samples/2026/11/09/r3.jpg', 'image/jpeg') $q$);

\echo '--- no sample to approve: the coordinator starts it (17 §4.3 step 10)'
SELECT pg_temp.expect_fail('SR26 a waived production order waiting for a sample', $q$
    UPDATE ordering.production_order SET status = 'SAMPLE_PENDING' WHERE id = md5('qa25:po2')::uuid $q$);
SELECT pg_temp.expect_ok('SR27 in production from ACCEPTED, by hand', $q$
    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW(),
           expected_completion_date = CURRENT_DATE + 25 WHERE id = md5('qa25:po2')::uuid $q$);

\echo '--- who answers (13 BR-13)'
CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;
SELECT pg_temp.expect_true('SR28 the customer reads and answers their samples; staff do not answer for them',
    pg_temp.grants('CUSTOMER', 'production-samples:VIEW_PAGE')
    AND pg_temp.grants('CUSTOMER', 'production-samples:READ')
    AND pg_temp.grants('CUSTOMER', 'production-samples:APPROVE')
    AND NOT pg_temp.grants('PRODUCTION_COORDINATOR', 'production-samples:APPROVE')
    AND NOT pg_temp.grants('SALES_STAFF', 'production-samples:APPROVE')
    AND pg_temp.grants('PRODUCTION_COORDINATOR', 'production-orders:UPDATE'));

ROLLBACK;
