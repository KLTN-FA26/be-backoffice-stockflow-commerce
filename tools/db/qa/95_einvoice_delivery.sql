-- =============================================================================
-- Adversarial checks + happy paths: a stuck e-invoice is announced once per submission, and the customer reads their
-- own invoices (V20261120000600, WF-05 E5; plan 1.4). Rolled back at the end.
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
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- Fixtures: the first customer's own SKU in cartons of 50; an order of 20 cartons at 2,000 a piece; two shipments of it
-- handed over (their delivery is the order module's business, not this script's).
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qa95:product')::uuid, 'QAED', 'QA ly hoa don', 'qa-ed', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa95:blank')::uuid, md5('qa95:product')::uuid, 'QA-ED-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa95:draft')::uuid, c.id, md5('qa95:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa95:snap')::uuid, md5('qa95:draft')::uuid, repeat('d', 64), 'https://example.com/e.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa95:own')::uuid, md5('qa95:product')::uuid, 'QA-ED-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-ED-OWN', 1, 0, NOW(), c.id, md5('qa95:snap')::uuid, md5('qa95:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa95:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-ED-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa95:order')::uuid, 'SO-QA-ED', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa95:line')::uuid, md5('qa95:order')::uuid, 'QA-ED-OWN', 1000, 2000, 'VND', md5('qa95:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, package_count, ready_at, created_at)
VALUES (md5('qa95:s1')::uuid, md5('qa95:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-ED-S1', md5('demo:wh:HCM')::uuid, 1,
        NOW(), NOW()),
       (md5('qa95:s2')::uuid, md5('qa95:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-ED-S2', md5('demo:wh:HCM')::uuid, 1,
        NOW(), NOW());
INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
VALUES (md5('qa95:s1')::uuid, md5('qa95:order')::uuid, 'HANDED_OVER', 1100000, NOW()),
       (md5('qa95:s2')::uuid, md5('qa95:order')::uuid, 'HANDED_OVER', 1100000, NOW());

-- A draft of shipment 1: 10 cartons x 100,000 at 10%, and a plate fee of 500,000 at 8%.
CREATE FUNCTION pg_temp.draft(tag TEXT, shipment TEXT, net NUMERIC, tax NUMERIC) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
                                    issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                    buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                    tax_total, grand_total, amount_in_words)
    SELECT md5('qa95:' || tag)::uuid, 'EINV-QA95-' || tag, 'ORIGINAL', 'DRAFT', o.id, o.order_number,
           md5('qa95:' || shipment)::uuid, o.customer_id, DATE '2026-11-12', TIMESTAMPTZ '2026-11-13 17:00:00+00',
           'CONG TY BAN', '0312345678', '1 Le Loi', 'CONG TY MUA', '0398765432', '12 Nguyen Hue', 'kt@mua.example.com',
           'VND', 'BANK_TRANSFER', net, tax, net + tax, 'so tien bang chu'
      FROM ordering.customer_order o WHERE o.id = md5('qa95:order')::uuid;
END $$;
CREATE FUNCTION pg_temp.goods(tag TEXT, line_no INT, cartons INT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, order_line_id, sku, item_name, unit, quantity,
                                         units_per_carton, unit_price, tax_rate, amount, tax_amount)
    VALUES (md5('qa95:' || tag || ':' || line_no)::uuid, md5('qa95:' || tag)::uuid, line_no, 'GOODS',
            md5('qa95:line')::uuid, 'QA-ED-OWN', 'QA ly in logo', 'Thung', cartons, 50, 100000, 10, cartons * 100000,
            cartons * 10000);
$$;
CREATE FUNCTION pg_temp.submit(tag TEXT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'SUBMITTING', provider = 'SANDBOX', attempt = attempt + 1,
           transaction_id = id::text || '-' || (attempt + 1), template_code = '1', symbol = 'C26TSB',
           submitted_at = NOW()
     WHERE id = md5('qa95:' || tag)::uuid;
$$;
CREATE FUNCTION pg_temp.issue(tag TEXT, number INT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'ISSUED', invoice_number = number, tax_authority_code = 'SBX' || number, lookup_code = 'L' || number,
           signed_at = NOW(), issued_at = NOW(), xml_file_key = 'einvoices/2026/11/12/' || tag || '.xml',
           pdf_file_key = 'einvoices/2026/11/12/' || tag || '.pdf'
     WHERE id = md5('qa95:' || tag)::uuid;
$$;

SELECT pg_temp.draft('a', 's1', 1000000, 100000);
SELECT pg_temp.goods('a', 1, 10);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
SELECT pg_temp.submit('a');

\echo '--- the stuck notice: once per invoice and transaction id, only of an invoice that exists'
SELECT pg_temp.expect_ok('A1 a submission is recorded as announced', $q$
    INSERT INTO invoicing.einvoice_stuck_notice (einvoice_id, transaction_id, notified_at)
    SELECT id, transaction_id, NOW() FROM invoicing.einvoice WHERE id = md5('qa95:a')::uuid $q$);
SELECT pg_temp.expect_fail('A2 the same submission announced a second time', $q$
    INSERT INTO invoicing.einvoice_stuck_notice (einvoice_id, transaction_id, notified_at)
    SELECT id, transaction_id, NOW() FROM invoicing.einvoice WHERE id = md5('qa95:a')::uuid $q$);
SELECT pg_temp.expect_ok('A3 the next attempt of the same invoice - a new transaction id - may be announced again', $q$
    INSERT INTO invoicing.einvoice_stuck_notice (einvoice_id, transaction_id, notified_at)
    VALUES (md5('qa95:a')::uuid, md5('qa95:a')::uuid::text || '-2', NOW()) $q$);
SELECT pg_temp.expect_fail('A4 a notice of an invoice that does not exist', $q$
    INSERT INTO invoicing.einvoice_stuck_notice (einvoice_id, transaction_id, notified_at)
    VALUES (gen_random_uuid(), 'tx-nobody', NOW()) $q$);
SELECT pg_temp.expect_fail('A5 a notice without a transaction id', $q$
    INSERT INTO invoicing.einvoice_stuck_notice (einvoice_id, transaction_id, notified_at)
    VALUES (md5('qa95:a')::uuid, NULL, NOW()) $q$);
SELECT pg_temp.expect_fail('A6 a notice without the time it was sent', $q$
    INSERT INTO invoicing.einvoice_stuck_notice (einvoice_id, transaction_id, notified_at)
    VALUES (md5('qa95:a')::uuid, 'tx-no-time', NULL) $q$);
SELECT pg_temp.expect_ok('A7 the invoice is issued with its notices standing: bookkeeping, not part of the invoice', $q$
    SELECT pg_temp.issue('a', 950001) $q$);
SELECT pg_temp.expect_true('A8 both notices are kept',
    (SELECT count(*) = 2 FROM invoicing.einvoice_stuck_notice WHERE einvoice_id = md5('qa95:a')::uuid));

\echo '--- permissions: the customer reads invoices, and nothing more of them'
SELECT pg_temp.expect_true('B1 CUSTOMER holds einvoices:READ',
    EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
              JOIN identity.permission p ON p.id = rp.permission_id
             WHERE r.code = 'CUSTOMER' AND p.resource = 'einvoices' AND p.action = 'READ'));
SELECT pg_temp.expect_true('B2 CUSTOMER holds no other action on einvoices and nothing on einvoice-settings',
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE r.code = 'CUSTOMER' AND ((p.resource = 'einvoices' AND p.action <> 'READ')
                       OR p.resource = 'einvoice-settings')));
SELECT pg_temp.expect_true('B3 no role but ACCOUNTANT, SALES_MANAGER, SYSTEM_ADMIN and CUSTOMER reads e-invoices',
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.resource = 'einvoices'
                   AND r.code NOT IN ('ACCOUNTANT', 'SALES_MANAGER', 'SYSTEM_ADMIN', 'CUSTOMER')));
SELECT pg_temp.expect_true('B4 the grant advanced the CUSTOMER role''s version (ADR-0008)',
    (SELECT version > 0 FROM identity.app_role WHERE code = 'CUSTOMER'));
SELECT pg_temp.expect_true('B5 the administrator still holds every permission',
    (SELECT count(*) FROM identity.permission)
        = (SELECT count(*) FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
            WHERE r.code = 'SYSTEM_ADMIN'));

ROLLBACK;
