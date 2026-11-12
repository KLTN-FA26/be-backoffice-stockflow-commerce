-- =============================================================================
-- Adversarial checks + happy paths: e-invoices (V20261120000200, V20261120000300; WF-05 E2; plan §1.4-§1.5; kltn-docs
-- 21 §5.1, BR-01, BR-03, BR-04, BR-06; NĐ 254/2026 Phụ lục điểm 2, 7; TT 91/2026 Điều 4, Điều 10). Needs the demo seed
-- for a customer. Rolled back at the end.
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
VALUES (md5('qa91:product')::uuid, 'QAEI', 'QA ly hoa don', 'qa-ei', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa91:blank')::uuid, md5('qa91:product')::uuid, 'QA-EI-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa91:draft')::uuid, c.id, md5('qa91:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa91:snap')::uuid, md5('qa91:draft')::uuid, repeat('e', 64), 'https://example.com/e.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa91:own')::uuid, md5('qa91:product')::uuid, 'QA-EI-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-EI-OWN', 1, 0, NOW(), c.id, md5('qa91:snap')::uuid, md5('qa91:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa91:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-EI-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa91:order')::uuid, 'SO-QA-EI', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa91:line')::uuid, md5('qa91:order')::uuid, 'QA-EI-OWN', 1000, 2000, 'VND', md5('qa91:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, package_count, ready_at, created_at)
VALUES (md5('qa91:s1')::uuid, md5('qa91:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-EI-S1', md5('demo:wh:HCM')::uuid, 1,
        NOW(), NOW()),
       (md5('qa91:s2')::uuid, md5('qa91:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-EI-S2', md5('demo:wh:HCM')::uuid, 1,
        NOW(), NOW());
INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
VALUES (md5('qa91:s1')::uuid, md5('qa91:order')::uuid, 'HANDED_OVER', 1100000, NOW()),
       (md5('qa91:s2')::uuid, md5('qa91:order')::uuid, 'HANDED_OVER', 1100000, NOW());

-- A draft of shipment 1: 10 cartons x 100,000 at 10%, and a plate fee of 500,000 at 8%.
CREATE FUNCTION pg_temp.draft(tag TEXT, shipment TEXT, net NUMERIC, tax NUMERIC) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
                                    issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                    buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                    tax_total, grand_total, amount_in_words)
    SELECT md5('qa91:' || tag)::uuid, 'EINV-QA91-' || tag, 'ORIGINAL', 'DRAFT', o.id, o.order_number,
           md5('qa91:' || shipment)::uuid, o.customer_id, DATE '2026-11-12', TIMESTAMPTZ '2026-11-13 17:00:00+00',
           'CONG TY BAN', '0312345678', '1 Le Loi', 'CONG TY MUA', '0398765432', '12 Nguyen Hue', 'kt@mua.example.com',
           'VND', 'BANK_TRANSFER', net, tax, net + tax, 'so tien bang chu'
      FROM ordering.customer_order o WHERE o.id = md5('qa91:order')::uuid;
END $$;
CREATE FUNCTION pg_temp.goods(tag TEXT, line_no INT, cartons INT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, order_line_id, sku, item_name, unit, quantity,
                                         units_per_carton, unit_price, tax_rate, amount, tax_amount)
    VALUES (md5('qa91:' || tag || ':' || line_no)::uuid, md5('qa91:' || tag)::uuid, line_no, 'GOODS',
            md5('qa91:line')::uuid, 'QA-EI-OWN', 'QA ly in logo', 'Thung', cartons, 50, 100000, 10, cartons * 100000,
            cartons * 10000);
$$;
CREATE FUNCTION pg_temp.submit(tag TEXT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'SUBMITTING', provider = 'SANDBOX', attempt = attempt + 1,
           transaction_id = id::text || '-' || (attempt + 1), template_code = '1', symbol = 'C26TSB',
           submitted_at = NOW()
     WHERE id = md5('qa91:' || tag)::uuid;
$$;
CREATE FUNCTION pg_temp.issue(tag TEXT, number INT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'ISSUED', invoice_number = number, tax_authority_code = 'SBX' || number, lookup_code = 'L' || number,
           signed_at = NOW(), issued_at = NOW(), xml_file_key = 'einvoices/2026/11/12/' || tag || '.xml',
           pdf_file_key = 'einvoices/2026/11/12/' || tag || '.pdf'
     WHERE id = md5('qa91:' || tag)::uuid;
$$;

\echo '--- drawing up: a draft with lines whose totals are the lines added up'
SELECT pg_temp.expect_ok('A1 a draft with its goods line and a fee line', $q$
    SELECT pg_temp.draft('a', 's1', 1500000, 140000);
    SELECT pg_temp.goods('a', 1, 10);
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, sku, item_name, unit, quantity,
                                         unit_price, tax_rate, amount, tax_amount)
    VALUES (md5('qa91:a:2')::uuid, md5('qa91:a')::uuid, 2, 'FEE', 'PLATE_FEE', 'QA-EI-OWN', 'Phi khuon', 'Lan', 1,
            500000, 8, 500000, 40000) $q$);
SELECT pg_temp.expect_fail('A2 an invoice without lines', $q$ SELECT pg_temp.draft('none', 's2', 0, 0) $q$);
SELECT pg_temp.expect_fail('A3 totals that are not the lines added up', $q$
    SELECT pg_temp.draft('off', 's2', 1000001, 100000);
    SELECT pg_temp.goods('off', 1, 10) $q$);
SELECT pg_temp.expect_fail('A4 a total with tax that is not the two others added', $q$
    UPDATE invoicing.einvoice SET grand_total = grand_total + 1 WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('A5 a second original in force for the same shipment (21 BR-01)', $q$
    SELECT pg_temp.draft('dup', 's1', 1000000, 100000);
    SELECT pg_temp.goods('dup', 1, 10) $q$);
SELECT pg_temp.expect_fail('A6 an original that invoices no shipment', $q$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, customer_id, issue_date,
        issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, currency, payment_method, subtotal,
        tax_total, grand_total, amount_in_words)
    SELECT gen_random_uuid(), 'EINV-QA91-NOSHIP', 'ORIGINAL', 'DRAFT', o.id, o.order_number, o.customer_id,
           CURRENT_DATE, NOW(), 'S', '0312345678', 'A', 'B', 'VND', 'BANK_TRANSFER', 0, 0, 0, 'x'
      FROM ordering.customer_order o WHERE o.id = md5('qa91:order')::uuid $q$);
SELECT pg_temp.expect_fail('A7 an invoice not drawn up as a draft', $q$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
        issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, buyer_tax_code,
        buyer_address, buyer_email, currency, payment_method, subtotal, tax_total, grand_total, amount_in_words,
        provider, attempt, transaction_id, template_code, symbol, submitted_at)
    SELECT gen_random_uuid(), 'EINV-QA91-SUB', 'ORIGINAL', 'SUBMITTING', o.id, o.order_number, md5('qa91:s2')::uuid,
           o.customer_id, DATE '2026-11-12', NOW(), 'S', '0312345678', 'A', 'B', '0398765432', 'C', 'a@b.vn', 'VND',
           'BANK_TRANSFER', 0, 0, 0, 'x', 'SANDBOX', 1, 'tx-qa91', '1', 'C26TSB', NOW()
      FROM ordering.customer_order o WHERE o.id = md5('qa91:order')::uuid $q$);
SELECT pg_temp.expect_fail('A8 a currency other than dong', $q$
    UPDATE invoicing.einvoice SET currency = 'USD' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('A9 a buyer tax code that is not 10, 10-3 or 12 digits', $q$
    UPDATE invoicing.einvoice SET buyer_tax_code = '12345' WHERE id = md5('qa91:a')::uuid $q$);

\echo '--- lines: amount = quantity x price, VAT rounded to the dong, goods name their order line'
SELECT pg_temp.expect_fail('B1 an amount that is not quantity x price', $q$
    UPDATE invoicing.einvoice_line SET amount = amount + 1 WHERE id = md5('qa91:a:1')::uuid $q$);
SELECT pg_temp.expect_fail('B2 a VAT that is not the amount at the rate', $q$
    UPDATE invoicing.einvoice_line SET tax_amount = tax_amount + 1 WHERE id = md5('qa91:a:1')::uuid $q$);
SELECT pg_temp.expect_fail('B3 a goods line that names no order line', $q$
    UPDATE invoicing.einvoice_line SET order_line_id = NULL WHERE id = md5('qa91:a:1')::uuid $q$);
SELECT pg_temp.expect_fail('B4 a fee line of a fee nobody knows', $q$
    UPDATE invoicing.einvoice_line SET fee_kind = 'CANCELLATION_FEE' WHERE id = md5('qa91:a:2')::uuid $q$);
SELECT pg_temp.expect_fail('B5 a line below zero on an original', $q$
    UPDATE invoicing.einvoice_line SET quantity = -10, amount = -1000000, tax_amount = -100000
     WHERE id = md5('qa91:a:1')::uuid $q$);
SELECT pg_temp.expect_fail('B6 two lines with one number', $q$
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, order_line_id, sku, item_name, unit, quantity,
                                         units_per_carton, unit_price, tax_rate, amount, tax_amount)
    VALUES (gen_random_uuid(), md5('qa91:a')::uuid, 2, 'GOODS', md5('qa91:line')::uuid, 'QA-EI-OWN', 'QA ly', 'Thung',
            1, 50, 100000, 10, 100000, 10000) $q$);
SELECT pg_temp.expect_fail('B7 a price that is not whole dong', $q$
    UPDATE invoicing.einvoice_line SET unit_price = 100000.5, amount = 1000005, tax_amount = 100001
     WHERE id = md5('qa91:a:1')::uuid $q$);
SELECT pg_temp.expect_ok('B8 a draft''s line is renamed', $q$
    UPDATE invoicing.einvoice_line SET item_name = 'QA ly in logo 12oz' WHERE id = md5('qa91:a:1')::uuid $q$);
SELECT pg_temp.expect_true('B9 half a dong of VAT rounds up, as the application rounds',
    round(25 * 10 / 100.0, 0) = 3 AND round(-25 * 10 / 100.0, 0) = -3);

\echo '--- the lifecycle: DRAFT -> SUBMITTING -> ISSUED | REJECTED; REJECTED -> DRAFT; DRAFT -> VOIDED_DRAFT'
SELECT pg_temp.expect_fail('C1 DRAFT -> ISSUED without going to the provider', $q$
    UPDATE invoicing.einvoice SET status = 'ISSUED', provider = 'SANDBOX', attempt = 1, transaction_id = 'x-qa91',
           template_code = '1', symbol = 'C26TSB', submitted_at = NOW(), invoice_number = 1,
           tax_authority_code = 'c', lookup_code = 'l', signed_at = NOW(), issued_at = NOW(), xml_file_key = 'a.xml',
           pdf_file_key = 'a.pdf'
     WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C2 DRAFT -> REJECTED', $q$
    UPDATE invoicing.einvoice SET status = 'REJECTED', provider = 'SANDBOX', attempt = 1, transaction_id = 'x-qa91',
           template_code = '1', symbol = 'C26TSB', submitted_at = NOW(), rejection_code = 'X'
     WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C3 DRAFT -> REPLACED', $q$
    UPDATE invoicing.einvoice SET status = 'REPLACED' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C4 sent without the buyer''s invoice e-mail (21 BR-06)', $q$
    UPDATE invoicing.einvoice SET buyer_email = NULL WHERE id = md5('qa91:a')::uuid;
    SELECT pg_temp.submit('a') $q$);
SELECT pg_temp.expect_fail('C5 sent without the buyer''s tax code', $q$
    UPDATE invoicing.einvoice SET buyer_tax_code = NULL WHERE id = md5('qa91:a')::uuid;
    SELECT pg_temp.submit('a') $q$);
SELECT pg_temp.expect_fail('C6 sent without a transaction id', $q$
    UPDATE invoicing.einvoice SET status = 'SUBMITTING', provider = 'SANDBOX', attempt = 1, template_code = '1',
           symbol = 'C26TSB', submitted_at = NOW() WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C7 sent under a symbol of another year than its date', $q$
    UPDATE invoicing.einvoice SET status = 'SUBMITTING', provider = 'SANDBOX', attempt = 1, transaction_id = 'x-qa91',
           template_code = '1', symbol = 'C27TSB', submitted_at = NOW() WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_ok('C8 DRAFT -> SUBMITTING', $q$ SELECT pg_temp.submit('a') $q$);
SELECT pg_temp.expect_fail('C9 SUBMITTING -> DRAFT: only the provider''s answer moves it on', $q$
    UPDATE invoicing.einvoice SET status = 'DRAFT' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C10 SUBMITTING -> VOIDED_DRAFT: it may be at the provider', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'x'
     WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C11 the buyer changes while it is at the provider', $q$
    UPDATE invoicing.einvoice SET buyer_name = 'KHAC' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C12 a line changes while it is at the provider', $q$
    UPDATE invoicing.einvoice_line SET item_name = 'khac' WHERE id = md5('qa91:a:1')::uuid $q$);
SELECT pg_temp.expect_fail('C13 its transaction id changes while it is at the provider (21 BR-04)', $q$
    UPDATE invoicing.einvoice SET transaction_id = 'other-qa91' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C14 REJECTED without the provider''s reason', $q$
    UPDATE invoicing.einvoice SET status = 'REJECTED' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_ok('C15 SUBMITTING -> REJECTED', $q$
    UPDATE invoicing.einvoice SET status = 'REJECTED', rejection_code = 'INVALID_BUYER_TAX_CODE'
     WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C16 REJECTED -> SUBMITTING without going back to a draft', $q$
    UPDATE invoicing.einvoice SET status = 'SUBMITTING' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C17 REJECTED -> ISSUED', $q$ SELECT pg_temp.issue('a', 1) $q$);
SELECT pg_temp.expect_fail('C18 REJECTED -> VOIDED_DRAFT', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'x'
     WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_ok('C19 REJECTED -> DRAFT, and the buyer is corrected on the draft', $q$
    UPDATE invoicing.einvoice SET status = 'DRAFT' WHERE id = md5('qa91:a')::uuid;
    UPDATE invoicing.einvoice SET buyer_tax_code = '0398765432-001' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C20 an attempt counted down', $q$
    UPDATE invoicing.einvoice SET attempt = 0 WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_ok('C21 DRAFT -> SUBMITTING again, a new transaction', $q$ SELECT pg_temp.submit('a') $q$);
SELECT pg_temp.expect_true('C22 the second attempt is its own transaction',
    (SELECT attempt = 2 AND transaction_id = id::text || '-2' FROM invoicing.einvoice WHERE id = md5('qa91:a')::uuid));
SELECT pg_temp.expect_fail('C23 issued without its files', $q$
    UPDATE invoicing.einvoice SET status = 'ISSUED', invoice_number = 1, tax_authority_code = 'c', lookup_code = 'l',
           signed_at = NOW(), issued_at = NOW() WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('C24 issued with a number of nine digits (NĐ 254 Phụ lục điểm 2)', $q$
    SELECT pg_temp.issue('a', 100000000) $q$);
SELECT pg_temp.expect_fail('C25 issued with number 0', $q$ SELECT pg_temp.issue('a', 0) $q$);
SELECT pg_temp.expect_fail('C26 a number without being issued', $q$
    UPDATE invoicing.einvoice SET invoice_number = 5 WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_ok('C27 SUBMITTING -> ISSUED', $q$ SELECT pg_temp.issue('a', 1) $q$);

\echo '--- an issued invoice never changes and is never cancelled (21 BR-03; TT 91 Điều 10)'
SELECT pg_temp.expect_fail('D1 ISSUED -> VOIDED_DRAFT', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'cancel it'
     WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D2 ISSUED -> DRAFT', $q$
    UPDATE invoicing.einvoice SET status = 'DRAFT' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D3 ISSUED -> SUBMITTING', $q$
    UPDATE invoicing.einvoice SET status = 'SUBMITTING' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D4 ISSUED -> REJECTED', $q$
    UPDATE invoicing.einvoice SET status = 'REJECTED', rejection_code = 'X' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D5 the buyer of an issued invoice changes', $q$
    UPDATE invoicing.einvoice SET buyer_name = 'KHAC' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D6 its number changes', $q$
    UPDATE invoicing.einvoice SET invoice_number = 2 WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D7 its totals change', $q$
    UPDATE invoicing.einvoice SET subtotal = subtotal + 1, grand_total = grand_total + 1
     WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D8 its files change', $q$
    UPDATE invoicing.einvoice SET pdf_file_key = 'einvoices/other.pdf' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D9 its tax authority code changes', $q$
    UPDATE invoicing.einvoice SET tax_authority_code = 'OTHER' WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D10 a line of an issued invoice changes', $q$
    UPDATE invoicing.einvoice_line SET item_name = 'khac' WHERE id = md5('qa91:a:1')::uuid $q$);
SELECT pg_temp.expect_fail('D11 a line is added to an issued invoice', $q$ SELECT pg_temp.goods('a', 3, 1) $q$);
SELECT pg_temp.expect_fail('D12 a line of an issued invoice is deleted', $q$
    DELETE FROM invoicing.einvoice_line WHERE id = md5('qa91:a:2')::uuid $q$);
SELECT pg_temp.expect_fail('D13 an issued invoice is deleted', $q$
    DELETE FROM invoicing.einvoice WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D14 what it is about changes: its shipment', $q$
    UPDATE invoicing.einvoice SET shipment_id = md5('qa91:s2')::uuid WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_ok('D15 the bookkeeping columns alone may move', $q$
    UPDATE invoicing.einvoice SET version = version + 1, last_modified_at = NOW() WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_ok('D16 it is flagged as having an adjustment (E4), and stays issued', $q$
    UPDATE invoicing.einvoice SET adjusted = TRUE WHERE id = md5('qa91:a')::uuid $q$);
SELECT pg_temp.expect_fail('D17 the flag is not taken back', $q$
    UPDATE invoicing.einvoice SET adjusted = FALSE WHERE id = md5('qa91:a')::uuid $q$);

\echo '--- numbers: once within a symbol (NĐ 254 Phụ lục điểm 2)'
SELECT pg_temp.expect_ok('E1 the second shipment''s invoice, issued as number 2', $q$
    SELECT pg_temp.draft('b', 's2', 1000000, 100000);
    SELECT pg_temp.goods('b', 1, 10);
    SELECT pg_temp.submit('b');
    SELECT pg_temp.issue('b', 2) $q$);
SELECT pg_temp.expect_fail('E2 a draft never deleted either', $q$
    DELETE FROM invoicing.einvoice WHERE id = md5('qa91:b')::uuid $q$);
SELECT pg_temp.expect_true('E3 two invoices of one order, one per shipment',
    (SELECT count(*) = 2 FROM invoicing.einvoice WHERE order_id = md5('qa91:order')::uuid AND status = 'ISSUED'));

\echo '--- adjustments and replacements (E4): only their place'
SELECT pg_temp.expect_fail('F1 an adjustment that names no invoice', $q$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, customer_id, issue_date,
        issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, currency, payment_method, subtotal,
        tax_total, grand_total, amount_in_words)
    SELECT gen_random_uuid(), 'EINV-QA91-ADJ0', 'ADJUSTMENT', 'DRAFT', o.id, o.order_number, o.customer_id,
           CURRENT_DATE, NOW(), 'S', '0312345678', 'A', 'B', 'VND', 'BANK_TRANSFER', -100000, -10000, -110000, 'x'
      FROM ordering.customer_order o WHERE o.id = md5('qa91:order')::uuid $q$);
SELECT pg_temp.expect_ok('F2 an adjustment of an issued invoice, with a line below zero (TT 91 Điều 10 khoản 6 điểm c)', $q$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, customer_id, original_einvoice_id,
        issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, currency, payment_method,
        subtotal, tax_total, grand_total, amount_in_words, adjustment_reason)
    SELECT md5('qa91:adj')::uuid, 'EINV-QA91-ADJ', 'ADJUSTMENT', 'DRAFT', o.id, o.order_number, o.customer_id,
           md5('qa91:a')::uuid, CURRENT_DATE, NOW(), 'S', '0312345678', 'A', 'B', 'VND', 'BANK_TRANSFER', -100000,
           -10000, -110000, 'x', '1 carton returned'
      FROM ordering.customer_order o WHERE o.id = md5('qa91:order')::uuid;
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, order_line_id, sku, item_name, unit, quantity,
                                         units_per_carton, unit_price, tax_rate, amount, tax_amount)
    VALUES (gen_random_uuid(), md5('qa91:adj')::uuid, 1, 'GOODS', md5('qa91:line')::uuid, 'QA-EI-OWN', 'QA ly', 'Thung',
            -1, 50, 100000, 10, -100000, -10000) $q$);
SELECT pg_temp.expect_fail('F3 an invoice that adjusts itself', $q$
    UPDATE invoicing.einvoice SET buyer_name = buyer_name WHERE FALSE;
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, customer_id, original_einvoice_id,
        issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, currency, payment_method,
        subtotal, tax_total, grand_total, amount_in_words)
    SELECT md5('qa91:self')::uuid, 'EINV-QA91-SELF', 'ADJUSTMENT', 'DRAFT', o.id, o.order_number, o.customer_id,
           md5('qa91:self')::uuid, CURRENT_DATE, NOW(), 'S', '0312345678', 'A', 'B', 'VND', 'BANK_TRANSFER', 0, 0, 0, 'x'
      FROM ordering.customer_order o WHERE o.id = md5('qa91:order')::uuid $q$);
SELECT pg_temp.expect_fail('F4 a replacement of an invoice that is not issued', $q$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, customer_id, original_einvoice_id,
        issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, currency, payment_method,
        subtotal, tax_total, grand_total, amount_in_words)
    SELECT gen_random_uuid(), 'EINV-QA91-REP', 'REPLACEMENT', 'DRAFT', o.id, o.order_number, o.customer_id,
           md5('qa91:adj')::uuid, CURRENT_DATE, NOW(), 'S', '0312345678', 'A', 'B', 'VND', 'BANK_TRANSFER', 0, 0, 0, 'x'
      FROM ordering.customer_order o WHERE o.id = md5('qa91:order')::uuid $q$);
SELECT pg_temp.expect_ok('F5 ISSUED -> REPLACED, the one way out of ISSUED', $q$
    UPDATE invoicing.einvoice SET status = 'REPLACED' WHERE id = md5('qa91:b')::uuid $q$);
SELECT pg_temp.expect_fail('F6 a replaced invoice is final', $q$
    UPDATE invoicing.einvoice SET status = 'ISSUED' WHERE id = md5('qa91:b')::uuid $q$);
SELECT pg_temp.expect_ok('F7 a replaced original no longer stands in the way of its shipment''s next invoice', $q$
    SELECT pg_temp.draft('c', 's2', 1000000, 100000);
    SELECT pg_temp.goods('c', 1, 10) $q$);

\echo '--- voiding a draft'
SELECT pg_temp.expect_fail('G1 voided without a reason', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW() WHERE id = md5('qa91:c')::uuid $q$);
SELECT pg_temp.expect_ok('G2 DRAFT -> VOIDED_DRAFT with a reason', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'drawn up by mistake'
     WHERE id = md5('qa91:c')::uuid $q$);
SELECT pg_temp.expect_fail('G3 a voided draft is final', $q$
    UPDATE invoicing.einvoice SET status = 'DRAFT', voided_at = NULL, void_reason = NULL
     WHERE id = md5('qa91:c')::uuid $q$);
SELECT pg_temp.expect_ok('G4 its shipment is drawn up again', $q$
    SELECT pg_temp.draft('d', 's2', 1000000, 100000);
    SELECT pg_temp.goods('d', 1, 10) $q$);

\echo '--- the provider, its series, its calls'
SELECT pg_temp.expect_fail('H1 a second provider in use', $q$
    INSERT INTO invoicing.einvoice_provider_config (id, provider, environment, active)
    VALUES (gen_random_uuid(), 'VIETTEL_SINVOICE', 'SANDBOX', TRUE) $q$);
SELECT pg_temp.expect_fail('H2 the sandbox as a production provider', $q$
    UPDATE invoicing.einvoice_provider_config SET environment = 'PRODUCTION' WHERE provider = 'SANDBOX' $q$);
SELECT pg_temp.expect_fail('H3 a secret kept in clear', $q$
    UPDATE invoicing.einvoice_provider_config SET secret_sealed = 'hunter2' WHERE provider = 'SANDBOX' $q$);
SELECT pg_temp.expect_fail('H4 a symbol that is not one (TT 91 Phụ lục I)', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active)
    VALUES (gen_random_uuid(), 'SANDBOX', '1', 'X26TAA', 2026, FALSE) $q$);
SELECT pg_temp.expect_fail('H5 a symbol of another year than its series', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active)
    VALUES (gen_random_uuid(), 'SANDBOX', '1', 'C26TAA', 2027, FALSE) $q$);
SELECT pg_temp.expect_fail('H6 two series in use for one provider and year', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active)
    VALUES (gen_random_uuid(), 'SANDBOX', '1', 'C26TAA', 2026, TRUE) $q$);
SELECT pg_temp.expect_ok('H7 another series of the year, not in use', $q$
    INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active)
    VALUES (gen_random_uuid(), 'SANDBOX', '1', 'C26TAA', 2026, FALSE) $q$);
SELECT pg_temp.expect_ok('H8 a call is logged', $q$
    INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
        request_hash, called_at, duration_ms)
    VALUES (md5('qa91:call')::uuid, md5('qa91:a')::uuid, 'SANDBOX', 'tx', 'SUBMIT', 'ACCEPTED', repeat('a', 64), NOW(), 12) $q$);
SELECT pg_temp.expect_fail('H9 a logged call is changed', $q$
    UPDATE invoicing.einvoice_provider_call SET outcome = 'REJECTED' WHERE id = md5('qa91:call')::uuid $q$);
SELECT pg_temp.expect_fail('H10 a logged call is deleted', $q$
    DELETE FROM invoicing.einvoice_provider_call WHERE id = md5('qa91:call')::uuid $q$);
SELECT pg_temp.expect_fail('H11 a payload where a hash belongs', $q$
    INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
        request_hash, called_at, duration_ms)
    VALUES (gen_random_uuid(), md5('qa91:a')::uuid, 'SANDBOX', 'tx', 'SUBMIT', 'ACCEPTED', '{"buyer":"CONG TY MUA"}',
            NOW(), 12) $q$);
SELECT pg_temp.expect_fail('H12 a 04/SS notice for an invoice that was never issued', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason)
    VALUES (gen_random_uuid(), md5('qa91:d')::uuid, 'wrong address') $q$);
SELECT pg_temp.expect_ok('H13 a 04/SS notice for an issued invoice: no new invoice (TT 91 Điều 10 khoản 1 điểm a)', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason)
    VALUES (gen_random_uuid(), md5('qa91:a')::uuid, 'wrong address') $q$);
SELECT pg_temp.expect_fail('H14 the sandbox gives a number once per symbol', $q$
    INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, invoice_number,
        tax_authority_code, lookup_code, xml_document, pdf_document, request_hash, received_at, ready_at)
    VALUES ('qa91-1', 'ISSUED', '1', 'C26TSB', 900001, 'c', 'l', '<x/>', '\x25504446', repeat('a', 64), NOW(), NOW()),
           ('qa91-2', 'ISSUED', '1', 'C26TSB', 900001, 'c', 'l', '<x/>', '\x25504446', repeat('b', 64), NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('H15 the sandbox''s numbers stop at 8 digits', $q$
    INSERT INTO invoicing.sandbox_number (template_code, symbol, last_number) VALUES ('1', 'C26TZZ', 100000000) $q$);

\echo '--- VAT rates of a period'
SELECT pg_temp.expect_ok('I1 a reduction of the standard rate for a period', $q$
    INSERT INTO ordering.tax_rate_period (id, tax_class, rate, valid_from, valid_to, note)
    VALUES (md5('qa91:period')::uuid, 'STANDARD', 8, DATE '2026-07-01', DATE '2026-12-31', 'QA reduction') $q$);
SELECT pg_temp.expect_fail('I2 a second rate for a day of that period', $q$
    INSERT INTO ordering.tax_rate_period (id, tax_class, rate, valid_from, valid_to)
    VALUES (gen_random_uuid(), 'STANDARD', 9, DATE '2026-12-31', DATE '2027-06-30') $q$);
SELECT pg_temp.expect_ok('I3 the next period starts the day after', $q$
    INSERT INTO ordering.tax_rate_period (id, tax_class, rate, valid_from, valid_to)
    VALUES (gen_random_uuid(), 'STANDARD', 9, DATE '2027-01-01', DATE '2027-06-30') $q$);
SELECT pg_temp.expect_ok('I4 another class has its own periods', $q$
    INSERT INTO ordering.tax_rate_period (id, tax_class, rate, valid_from, valid_to)
    VALUES (gen_random_uuid(), 'REDUCED', 4, DATE '2026-07-01', DATE '2026-12-31') $q$);
SELECT pg_temp.expect_fail('I5 a period that ends before it starts', $q$
    INSERT INTO ordering.tax_rate_period (id, tax_class, rate, valid_from, valid_to)
    VALUES (gen_random_uuid(), 'EXEMPT', 0, DATE '2028-02-01', DATE '2028-01-01') $q$);
SELECT pg_temp.expect_fail('I6 a class nobody knows', $q$
    INSERT INTO ordering.tax_rate_period (id, tax_class, rate, valid_from, valid_to)
    VALUES (gen_random_uuid(), 'LUXURY', 20, DATE '2028-01-01', DATE '2028-02-01') $q$);
SELECT pg_temp.expect_true('I7 the rate of a day: the period''s inside it, the class''s own outside',
    (SELECT COALESCE(p.rate, r.rate) = 8 FROM ordering.tax_rate r LEFT JOIN ordering.tax_rate_period p
        ON p.tax_class = r.tax_class AND DATE '2026-11-12' BETWEEN p.valid_from AND p.valid_to
      WHERE r.tax_class = 'STANDARD')
    AND (SELECT COALESCE(p.rate, r.rate) = 10 FROM ordering.tax_rate r LEFT JOIN ordering.tax_rate_period p
        ON p.tax_class = r.tax_class AND DATE '2026-06-30' BETWEEN p.valid_from AND p.valid_to
      WHERE r.tax_class = 'STANDARD'));

\echo '--- grants'
SELECT pg_temp.expect_true('J1 the accountant holds every e-invoice action and reads the settings',
    (SELECT count(*) = 7 FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
      WHERE r.code = 'ACCOUNTANT' AND (p.resource = 'einvoices' OR p.code = 'einvoice-settings:READ')));
SELECT pg_temp.expect_true('J2 the Sales Manager only reads',
    (SELECT string_agg(p.code, ',' ORDER BY p.code) = 'einvoices:READ,einvoices:VIEW_PAGE'
       FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
      WHERE r.code = 'SALES_MANAGER' AND p.resource IN ('einvoices', 'einvoice-settings')));
SELECT pg_temp.expect_true('J3 only the administrator changes the settings; the accountant does not',
    (SELECT string_agg(r.code, ',') = 'SYSTEM_ADMIN' FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.code = 'einvoice-settings:UPDATE'));
-- E5 (V20261120000600): the customer reads their own issued invoices on the portal - READ, and nothing else.
SELECT pg_temp.expect_true('J4 nobody else holds an e-invoice permission - Sales staff, the warehouse; the customer only reads',
    (SELECT count(*) = 0 FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.resource IN ('einvoices', 'einvoice-settings')
        AND r.code NOT IN ('ACCOUNTANT', 'SALES_MANAGER', 'SYSTEM_ADMIN')
        AND NOT (r.code = 'CUSTOMER' AND p.resource = 'einvoices' AND p.action = 'READ')));
SELECT pg_temp.expect_true('J5 the administrator still holds every permission',
    (SELECT count(*) FROM identity.permission)
        = (SELECT count(*) FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
            WHERE r.code = 'SYSTEM_ADMIN'));
SELECT pg_temp.expect_true('J6 the sandbox is the provider in use, with a series for this year and the next',
    (SELECT provider = 'SANDBOX' AND environment = 'SANDBOX' AND NOT auto_issue
       FROM invoicing.einvoice_provider_config WHERE active)
    AND (SELECT count(*) = 2 FROM invoicing.einvoice_series WHERE provider = 'SANDBOX' AND active
          AND year IN (2026, 2027)));

ROLLBACK;
