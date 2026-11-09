-- =============================================================================
-- Adversarial checks + happy paths: production orders (V20261108002700, team plan 4A-2; kltn-docs 17 §4.3, §5.3,
-- BR-05, BR-06, BR-23, BR-24). Needs the demo seed (demo.editor draws up, demo.approver approves). Rolled back at
-- the end.
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
VALUES (md5('qa21:product')::uuid, 'QAPRD', 'QA ly dot', 'qa-prd', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa21:blank')::uuid, md5('qa21:product')::uuid, 'QA-PRD-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa21:draft')::uuid, c.id, md5('qa21:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa21:snap')::uuid, md5('qa21:draft')::uuid, repeat('c', 64), 'https://example.com/c.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa21:own')::uuid, md5('qa21:product')::uuid, 'QA-PRD-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-PRD-OWN', 1, 0, NOW(), c.id, md5('qa21:snap')::uuid, md5('qa21:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa21:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-PRD-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa21:order')::uuid, 'SO-QA-PRD', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa21:line')::uuid, md5('qa21:order')::uuid, 'QA-PRD-OWN', 1000, 2000, 'VND', md5('qa21:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, seller_address,
                                     seller_representative, seller_representative_title, buyer_name, buyer_address,
                                     buyer_representative, buyer_representative_title, free_sample_rounds)
VALUES (md5('qa21:contract')::uuid, 'HD-QA-PRD-1', md5('qa21:order')::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A',
        'Director', 'Buyer', '1 Le Loi', 'Tran Van B', 'Director', 2);
INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('qa21:b1')::uuid, md5('qa21:contract')::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 32),
       (md5('qa21:b2')::uuid, md5('qa21:contract')::uuid, 2, CURRENT_DATE + 44, CURRENT_DATE + 44);
INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (gen_random_uuid(), md5('qa21:b1')::uuid, md5('qa21:line')::uuid, 10),
       (gen_random_uuid(), md5('qa21:b2')::uuid, md5('qa21:line')::uuid, 10);
INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
VALUES (md5('qa21:i1')::uuid, md5('qa21:contract')::uuid, 1, 'Before delivery', 'BEFORE_DELIVERY', 100, 2200000,
        NULL, NULL);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
-- Sent and signed straight (the contract's own triggers are 17's script), confirmed, shipping from HCM.
SET LOCAL session_replication_role = replica;
UPDATE ordering.sales_contract SET status = 'SIGNED', sent_at = NOW() - INTERVAL '2 days',
       sent_by = md5('demo:user:editor')::uuid, sent_document_key = 'documents/hd-qa-prd-1.pdf', signed_at = NOW(),
       signed_by = md5('demo:user:editor')::uuid, signed_on = CURRENT_DATE, signed_file_key = 'documents/s.pdf',
       signed_file_name = 's.pdf'
 WHERE id = md5('qa21:contract')::uuid;
UPDATE ordering.customer_order o SET status = 'CONFIRMED', contract_signed_at = NOW(), shipping_warehouse_id = w.id,
       shipping_warehouse_chosen_at = NOW(), shipping_warehouse_chosen_by = md5('demo:user:editor')::uuid
  FROM warehouse.warehouse w WHERE w.prefix = 'HCM' AND o.id = md5('qa21:order')::uuid;
SET LOCAL session_replication_role = origin;
INSERT INTO ordering.producer (id, code, name, channel, email)
VALUES (md5('qa21:producer')::uuid, 'QA-XUONG', 'QA xuong in', 'EMAIL', 'don@qa-xuong.vn');
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

CREATE FUNCTION pg_temp.draft(po_id UUID, warehouse_prefix TEXT, sample_default BOOLEAN, sample BOOLEAN, waiver TEXT,
                              po_code TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                           receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                           sample_required_by_default, sample_waiver_reason, drawn_up_by)
    SELECT po_id, po_code, o.id, md5('qa21:b2')::uuid, o.customer_id, md5('qa21:producer')::uuid, w.id,
           CURRENT_DATE + 44, 'DRAFT', sample, sample_default, waiver, md5('demo:user:editor')::uuid
      FROM ordering.customer_order o, warehouse.warehouse w
     WHERE o.id = md5('qa21:order')::uuid AND w.prefix = warehouse_prefix
$$;
CREATE FUNCTION pg_temp.line(po_id UUID, cartons INTEGER, line_sku TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                                design_snapshot_id, print_file_checksum)
    VALUES (gen_random_uuid(), po_id, md5('qa21:line')::uuid, line_sku, cartons, 50, md5('qa21:snap')::uuid,
            repeat('c', 64))
$$;

\echo '--- drawn up for a batch of a signed order, received where it ships (17 BR-05, BR-22)'
SELECT pg_temp.expect_fail('PO1 a production order with no line', $q$
    SELECT pg_temp.draft(md5('qa21:po0')::uuid, 'HCM', TRUE, TRUE, NULL, 'PRD-20261109-0901') $q$);
SELECT pg_temp.expect_fail('PO2 a code that is not PRD-yyyyMMdd-nnnn', $q$
    SELECT pg_temp.draft(md5('qa21:po0')::uuid, 'HCM', TRUE, TRUE, NULL, 'LSX-1');
    SELECT pg_temp.line(md5('qa21:po0')::uuid, 10, 'QA-PRD-OWN') $q$);
SELECT pg_temp.expect_fail('PO3 a line with another SKU than its order line''s', $q$
    SELECT pg_temp.draft(md5('qa21:po0')::uuid, 'HCM', TRUE, TRUE, NULL, 'PRD-20261109-0901');
    SELECT pg_temp.line(md5('qa21:po0')::uuid, 10, 'QA-PRD-BLANK') $q$);
SELECT pg_temp.expect_fail('PO4 a print sample required by default waived without a reason (BR-24)', $q$
    SELECT pg_temp.draft(md5('qa21:po0')::uuid, 'HCM', TRUE, FALSE, NULL, 'PRD-20261109-0901');
    SELECT pg_temp.line(md5('qa21:po0')::uuid, 10, 'QA-PRD-OWN') $q$);
SELECT pg_temp.expect_ok('PO5 a draft for batch 2: 10 cartons of 50 at HCM, a sample wanted', $q$
    SELECT pg_temp.draft(md5('qa21:po1')::uuid, 'HCM', TRUE, TRUE, NULL, 'PRD-20261109-0901');
    SELECT pg_temp.line(md5('qa21:po1')::uuid, 10, 'QA-PRD-OWN') $q$);
SELECT pg_temp.expect_true('PO5a its line counts its units', (SELECT total_units FROM ordering.production_order_line
    WHERE production_order_id = md5('qa21:po1')::uuid) = 500);
SELECT pg_temp.expect_fail('PO6 sent without being submitted and approved (17 §5.3)', $q$
    UPDATE ordering.production_order SET status = 'SENT', approved_by = md5('demo:user:approver')::uuid,
           approved_at = NOW(), sent_at = NOW() WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PO7 moved to another sales order', $q$
    UPDATE ordering.production_order SET sales_order_id = gen_random_uuid() WHERE id = md5('qa21:po1')::uuid $q$);

\echo '--- submitted, approved by someone else (BR-23)'
SELECT pg_temp.expect_ok('PO8 submitted for approval', $q$
    UPDATE ordering.production_order SET status = 'PENDING_APPROVAL', submitted_at = NOW()
     WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PO9 its need-by date changed once submitted (B33)', $q$
    UPDATE ordering.production_order SET need_by_date = need_by_date + 1 WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PO10 its lines changed once submitted', $q$
    UPDATE ordering.production_order_line SET cartons = 9 WHERE production_order_id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PO11 approved by the one who drew it up', $q$
    UPDATE ordering.production_order SET status = 'SENT', approved_by = md5('demo:user:editor')::uuid,
           approved_at = NOW(), sent_at = NOW() WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_ok('PO12 approved by the approver, so sent', $q$
    UPDATE ordering.production_order SET status = 'SENT', approved_by = md5('demo:user:approver')::uuid,
           approved_at = NOW(), sent_at = NOW() WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PO13 sent, received at another warehouse', $q$
    UPDATE ordering.production_order SET receiving_warehouse_id = gen_random_uuid()
     WHERE id = md5('qa21:po1')::uuid $q$);

\echo '--- cancelled before mass printing, closed after (BR-06)'
SELECT pg_temp.expect_fail('PO14 cancelled without a reason', $q$
    UPDATE ordering.production_order SET status = 'CANCELLED', cancelled_at = NOW(),
           cancelled_by = md5('demo:user:editor')::uuid WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PO15 closed while not yet in production', $q$
    UPDATE ordering.production_order SET status = 'CLOSED', closed_at = NOW(), closed_by = md5('demo:user:editor')::uuid,
           closing_reason = 'stopped' WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PO16 in production with no start time', $q$
    UPDATE ordering.production_order SET status = 'IN_PRODUCTION' WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_ok('PO17 in production, then closed with a reason', $q$
    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW()
     WHERE id = md5('qa21:po1')::uuid;
    UPDATE ordering.production_order SET status = 'CLOSED', closed_at = NOW(), closed_by = md5('demo:user:editor')::uuid,
           closing_reason = 'the producer stopped before delivering anything' WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PO18 a closed production order reopened', $q$
    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', closed_at = NULL, closed_by = NULL,
           closing_reason = NULL WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_fail('PO19 its history rewritten', $q$
    INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status, to_status, changed_at)
    VALUES (md5('qa21:h1')::uuid, md5('qa21:po1')::uuid, NULL, 'DRAFT', NOW());
    UPDATE ordering.production_order_status_history SET to_status = 'SENT' WHERE id = md5('qa21:h1')::uuid $q$);
SELECT pg_temp.expect_fail('PO20 deleted', $q$
    DELETE FROM ordering.production_order WHERE id = md5('qa21:po1')::uuid $q$);
SELECT pg_temp.expect_true('PO21 the 18 edges of 17 §5.3',
    (SELECT count(*) FROM ordering.production_order_status_transition) = 18);

\echo '--- who does what (00 §2.1, 17 BR-23)'
CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;
SELECT pg_temp.expect_true('PO22 the coordinator draws up, the Sales Manager approves, Sales and the warehouse read',
    pg_temp.grants('PRODUCTION_COORDINATOR', 'production-orders:CREATE')
    AND pg_temp.grants('PRODUCTION_COORDINATOR', 'production-orders:UPDATE')
    AND NOT pg_temp.grants('PRODUCTION_COORDINATOR', 'production-orders:APPROVE')
    AND pg_temp.grants('SALES_MANAGER', 'production-orders:APPROVE')
    AND NOT pg_temp.grants('SALES_MANAGER', 'production-orders:CREATE')
    AND pg_temp.grants('SALES_STAFF', 'production-orders:READ')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'production-orders:READ')
    AND pg_temp.grants('PRODUCTION_COORDINATOR', 'sales-orders:READ')
    AND NOT pg_temp.grants('CUSTOMER', 'production-orders:READ'));

ROLLBACK;
