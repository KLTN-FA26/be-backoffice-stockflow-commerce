-- =============================================================================
-- Adversarial checks + happy paths: when an e-invoice was last sent to the provider, and the lease on talking to the
-- provider about it (V20261120001000, V20261120001010; review WF05-einv, findings H1 and H2). Rolled back at the end.
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
VALUES (md5('qa96:product')::uuid, 'QAES', 'QA ly hoa don', 'qa-es', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa96:blank')::uuid, md5('qa96:product')::uuid, 'QA-ES-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa96:draft')::uuid, c.id, md5('qa96:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa96:snap')::uuid, md5('qa96:draft')::uuid, repeat('d', 64), 'https://example.com/e.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa96:own')::uuid, md5('qa96:product')::uuid, 'QA-ES-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-ES-OWN', 1, 0, NOW(), c.id, md5('qa96:snap')::uuid, md5('qa96:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa96:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-ES-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa96:order')::uuid, 'SO-QA-ES', c.id, gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa96:line')::uuid, md5('qa96:order')::uuid, 'QA-ES-OWN', 1000, 2000, 'VND', md5('qa96:carton')::uuid, 20,
        50, 10, 200000);
INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, package_count, ready_at, created_at)
VALUES (md5('qa96:s1')::uuid, md5('qa96:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-ES-S1', md5('demo:wh:HCM')::uuid, 1,
        NOW(), NOW()),
       (md5('qa96:s2')::uuid, md5('qa96:order')::uuid, 'READY_TO_DISPATCH', 'SO-QA-ES-S2', md5('demo:wh:HCM')::uuid, 1,
        NOW(), NOW());
INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
VALUES (md5('qa96:s1')::uuid, md5('qa96:order')::uuid, 'HANDED_OVER', 1100000, NOW()),
       (md5('qa96:s2')::uuid, md5('qa96:order')::uuid, 'HANDED_OVER', 1100000, NOW());

-- A draft of a shipment: cartons x 100,000 at 10%.
CREATE FUNCTION pg_temp.draft(tag TEXT, shipment TEXT, net NUMERIC, tax NUMERIC) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
                                    issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                    buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                    tax_total, grand_total, amount_in_words)
    SELECT md5('qa96:' || tag)::uuid, 'EINV-QA96-' || tag, 'ORIGINAL', 'DRAFT', o.id, o.order_number,
           md5('qa96:' || shipment)::uuid, o.customer_id, DATE '2026-11-12', TIMESTAMPTZ '2026-11-13 17:00:00+00',
           'CONG TY BAN', '0312345678', '1 Le Loi', 'CONG TY MUA', '0398765432', '12 Nguyen Hue', 'kt@mua.example.com',
           'VND', 'BANK_TRANSFER', net, tax, net + tax, 'so tien bang chu'
      FROM ordering.customer_order o WHERE o.id = md5('qa96:order')::uuid;
END $$;
CREATE FUNCTION pg_temp.goods(tag TEXT, line_no INT, cartons INT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, order_line_id, sku, item_name, unit, quantity,
                                         units_per_carton, unit_price, tax_rate, amount, tax_amount)
    VALUES (md5('qa96:' || tag || ':' || line_no)::uuid, md5('qa96:' || tag)::uuid, line_no, 'GOODS',
            md5('qa96:line')::uuid, 'QA-ES-OWN', 'QA ly in logo', 'Thung', cartons, 50, 100000, 10, cartons * 100000,
            cartons * 10000);
$$;
CREATE FUNCTION pg_temp.submit(tag TEXT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'SUBMITTING', provider = 'SANDBOX', attempt = attempt + 1,
           transaction_id = id::text || '-' || (attempt + 1), template_code = '1', symbol = 'C26TSB',
           submitted_at = NOW()
     WHERE id = md5('qa96:' || tag)::uuid;
$$;
CREATE FUNCTION pg_temp.issue(tag TEXT, number INT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'ISSUED', invoice_number = number, tax_authority_code = 'SBX' || number, lookup_code = 'L' || number,
           signed_at = NOW(), issued_at = NOW(), xml_file_key = 'einvoices/2026/11/12/' || tag || '.xml',
           pdf_file_key = 'einvoices/2026/11/12/' || tag || '.pdf'
     WHERE id = md5('qa96:' || tag)::uuid;
$$;

CREATE FUNCTION pg_temp.reject(tag TEXT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice SET status = 'REJECTED', rejection_code = 'INVALID_BUYER_TAX_CODE',
           rejection_message = 'refused'
     WHERE id = md5('qa96:' || tag)::uuid;
    UPDATE invoicing.einvoice SET status = 'DRAFT' WHERE id = md5('qa96:' || tag)::uuid;
$$;
-- The statements of EInvoiceSendingAdapter, word for word where it matters: the conflict clause and its WHERE.
CREATE FUNCTION pg_temp.claim(tag TEXT, who TEXT, seconds DOUBLE PRECISION) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE
    taken INTEGER;
BEGIN
    INSERT INTO invoicing.einvoice_sending (einvoice_id, holder, held_until)
    VALUES (md5('qa96:' || tag)::uuid, md5('qa96:holder:' || who)::uuid, now() + make_interval(secs => seconds))
    ON CONFLICT (einvoice_id) DO UPDATE SET holder = EXCLUDED.holder, held_until = EXCLUDED.held_until
     WHERE einvoice_sending.holder IS NULL OR einvoice_sending.held_until <= now();
    GET DIAGNOSTICS taken = ROW_COUNT;
    RETURN taken = 1;
END $$;
CREATE FUNCTION pg_temp.sent(tag TEXT, tx TEXT, at TIMESTAMPTZ) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at)
    VALUES (md5('qa96:' || tag)::uuid, tx, at)
    ON CONFLICT (einvoice_id) DO UPDATE
        SET transaction_id = EXCLUDED.transaction_id, last_sent_at = EXCLUDED.last_sent_at;
$$;
CREATE FUNCTION pg_temp.tx(tag TEXT) RETURNS TEXT LANGUAGE sql AS $$
    SELECT transaction_id FROM invoicing.einvoice WHERE id = md5('qa96:' || tag)::uuid;
$$;

-- a: at the provider. b: still a draft, submitted later under a lease.
SELECT pg_temp.draft('a', 's1', 1000000, 100000);
SELECT pg_temp.goods('a', 1, 10);
SELECT pg_temp.draft('b', 's2', 500000, 50000);
SELECT pg_temp.goods('b', 1, 5);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
SELECT pg_temp.submit('a');

\echo '--- a send: only of an invoice at the provider, under its own transaction, never before it went there'
SELECT pg_temp.expect_fail('A1 a send of an invoice that does not exist', $q$
    INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at)
    VALUES (gen_random_uuid(), 'tx-nobody', NOW()) $q$);
SELECT pg_temp.expect_fail('A2 a send of a draft: it never went to the provider', $q$
    SELECT pg_temp.sent('b', md5('qa96:b')::uuid::text || '-1', NOW()) $q$);
SELECT pg_temp.expect_fail('A3 a send under a transaction that is not the invoice''s', $q$
    SELECT pg_temp.sent('a', md5('qa96:a')::uuid::text || '-2', NOW()) $q$);
SELECT pg_temp.expect_fail('A4 a send dated before the invoice was submitted', $q$
    SELECT pg_temp.sent('a', pg_temp.tx('a'), NOW() - INTERVAL '1 second') $q$);
SELECT pg_temp.expect_fail('A5 a send under an empty transaction id', $q$
    SELECT pg_temp.sent('a', '', NOW()) $q$);
SELECT pg_temp.expect_fail('A6 a transaction id without the time it was sent', $q$
    INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at)
    VALUES (md5('qa96:a')::uuid, pg_temp.tx('a'), NULL) $q$);
SELECT pg_temp.expect_fail('A7 a time sent without the transaction id', $q$
    INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at)
    VALUES (md5('qa96:a')::uuid, NULL, NOW()) $q$);
SELECT pg_temp.expect_ok('A8 the first send is recorded', $q$
    SELECT pg_temp.sent('a', pg_temp.tx('a'), NOW()) $q$);
SELECT pg_temp.expect_fail('A9 a second row for the same invoice', $q$
    INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at)
    VALUES (md5('qa96:a')::uuid, pg_temp.tx('a'), NOW()) $q$);
SELECT pg_temp.expect_ok('A10 the next send overwrites the one before', $q$
    SELECT pg_temp.sent('a', pg_temp.tx('a'), NOW() + INTERVAL '7 minutes') $q$);
SELECT pg_temp.expect_true('A11 one row, carrying the last send',
    (SELECT count(*) = 1 AND max(last_sent_at) = NOW() + INTERVAL '7 minutes'
       FROM invoicing.einvoice_sending WHERE einvoice_id = md5('qa96:a')::uuid));
SELECT pg_temp.expect_fail('A12 the send moved back before the submission', $q$
    UPDATE invoicing.einvoice_sending SET last_sent_at = NOW() - INTERVAL '1 hour'
     WHERE einvoice_id = md5('qa96:a')::uuid $q$);
SELECT pg_temp.expect_fail('A13 the send moved under another transaction', $q$
    UPDATE invoicing.einvoice_sending SET transaction_id = 'tx-other' WHERE einvoice_id = md5('qa96:a')::uuid $q$);
SELECT pg_temp.expect_fail('A14 the send wiped half-way: the time left without its transaction', $q$
    UPDATE invoicing.einvoice_sending SET transaction_id = NULL WHERE einvoice_id = md5('qa96:a')::uuid $q$);

\echo '--- the next attempt: a refused invoice sent again under a new transaction'
SELECT pg_temp.expect_ok('B1 the invoice is refused and goes back to a draft with its send standing', $q$
    SELECT pg_temp.reject('a') $q$);
SELECT pg_temp.expect_fail('B2 a send while the invoice is a draft again', $q$
    SELECT pg_temp.sent('a', pg_temp.tx('a'), NOW() + INTERVAL '8 minutes') $q$);
SELECT pg_temp.expect_ok('B3 it is submitted again: attempt 2', $q$ SELECT pg_temp.submit('a') $q$);
SELECT pg_temp.expect_true('B4 the send on record is of the transaction before: none under the current one',
    (SELECT s.transaction_id <> i.transaction_id AND i.attempt = 2
       FROM invoicing.einvoice_sending s JOIN invoicing.einvoice i ON i.id = s.einvoice_id
      WHERE i.id = md5('qa96:a')::uuid));
SELECT pg_temp.expect_fail('B5 a send under the transaction of the attempt before', $q$
    SELECT pg_temp.sent('a', md5('qa96:a')::uuid::text || '-1', NOW()) $q$);
SELECT pg_temp.expect_ok('B6 a send under the new transaction replaces it', $q$
    SELECT pg_temp.sent('a', pg_temp.tx('a'), NOW()) $q$);

\echo '--- the lease: one holder at a time, on the database''s clock'
SELECT pg_temp.expect_fail('C1 a holder without the time its lease ends', $q$
    INSERT INTO invoicing.einvoice_sending (einvoice_id, holder, held_until)
    VALUES (md5('qa96:b')::uuid, gen_random_uuid(), NULL) $q$);
SELECT pg_temp.expect_fail('C2 a lease on an invoice that does not exist', $q$
    INSERT INTO invoicing.einvoice_sending (einvoice_id, holder, held_until)
    VALUES (gen_random_uuid(), gen_random_uuid(), NOW() + INTERVAL '15 minutes') $q$);
-- The claim and what it left are separate statements: a sub-select beside the claim would be read before it ran.
SELECT pg_temp.expect_true('C3 a lease is taken on an invoice never sent', pg_temp.claim('b', 'one', 900));
SELECT pg_temp.expect_true('C3b the row it made records no send',
    (SELECT transaction_id IS NULL AND last_sent_at IS NULL AND held_until > NOW()
       FROM invoicing.einvoice_sending WHERE einvoice_id = md5('qa96:b')::uuid));
SELECT pg_temp.expect_true('C4 a second holder is refused while the lease runs', NOT pg_temp.claim('b', 'two', 900));
SELECT pg_temp.expect_true('C5 the holder itself does not take it twice: it renews instead',
    NOT pg_temp.claim('b', 'one', 900));
SELECT pg_temp.expect_true('C5b the first holder still holds it',
    (SELECT holder = md5('qa96:holder:one')::uuid FROM invoicing.einvoice_sending
      WHERE einvoice_id = md5('qa96:b')::uuid));
SELECT pg_temp.expect_fail('C6 the holder kept, the end of its lease wiped', $q$
    UPDATE invoicing.einvoice_sending SET held_until = NULL WHERE einvoice_id = md5('qa96:b')::uuid $q$);
SELECT pg_temp.expect_ok('C7 the lease runs out', $q$
    UPDATE invoicing.einvoice_sending SET held_until = NOW() - INTERVAL '1 second'
     WHERE einvoice_id = md5('qa96:b')::uuid $q$);
SELECT pg_temp.expect_true('C8 a lease that ran out is taken over', pg_temp.claim('b', 'two', 900));
SELECT pg_temp.expect_true('C8b by the one who asked, for a full lease',
    (SELECT holder = md5('qa96:holder:two')::uuid AND held_until > NOW()
       FROM invoicing.einvoice_sending WHERE einvoice_id = md5('qa96:b')::uuid));
SELECT pg_temp.expect_ok('C9 given up: no holder, and when it was given up', $q$
    UPDATE invoicing.einvoice_sending SET holder = NULL, held_until = NOW()
     WHERE einvoice_id = md5('qa96:b')::uuid AND holder = md5('qa96:holder:two')::uuid $q$);
SELECT pg_temp.expect_true('C10 a lease given up is taken at once', pg_temp.claim('b', 'one', 900));

\echo '--- the lease and the send share the row and leave each other alone'
SELECT pg_temp.expect_true('D1 a lease is taken on an invoice already sent', pg_temp.claim('a', 'one', 900));
SELECT pg_temp.expect_true('D1b and its send stays',
    (SELECT transaction_id = pg_temp.tx('a') AND last_sent_at IS NOT NULL
       FROM invoicing.einvoice_sending WHERE einvoice_id = md5('qa96:a')::uuid));
SELECT pg_temp.expect_ok('D2 a send recorded under the lease', $q$
    SELECT pg_temp.sent('a', pg_temp.tx('a'), NOW() + INTERVAL '1 minute') $q$);
SELECT pg_temp.expect_true('D3 the holder still holds it',
    (SELECT holder = md5('qa96:holder:one')::uuid FROM invoicing.einvoice_sending
      WHERE einvoice_id = md5('qa96:a')::uuid));
SELECT pg_temp.expect_ok('D4 the draft under a lease is submitted: the lease is no part of the invoice', $q$
    SELECT pg_temp.submit('b') $q$);
SELECT pg_temp.expect_ok('D5 and its first send is recorded on the row the lease made', $q$
    SELECT pg_temp.sent('b', pg_temp.tx('b'), NOW()) $q$);
SELECT pg_temp.expect_ok('D6 the invoice is issued with its send and its lease standing', $q$
    SELECT pg_temp.issue('a', 960001) $q$);
SELECT pg_temp.expect_ok('D7 the lease of an invoice issued meanwhile is renewed', $q$
    UPDATE invoicing.einvoice_sending SET held_until = NOW() + INTERVAL '15 minutes'
     WHERE einvoice_id = md5('qa96:a')::uuid AND holder = md5('qa96:holder:one')::uuid $q$);
SELECT pg_temp.expect_ok('D8 and given up: the holder is never left stuck by the answer it fetched', $q$
    UPDATE invoicing.einvoice_sending SET holder = NULL, held_until = NOW()
     WHERE einvoice_id = md5('qa96:a')::uuid AND holder = md5('qa96:holder:one')::uuid $q$);
SELECT pg_temp.expect_true('D9 the row of the issued invoice: nobody holds it, its last send kept',
    (SELECT holder IS NULL AND held_until IS NOT NULL AND last_sent_at IS NOT NULL
       FROM invoicing.einvoice_sending WHERE einvoice_id = md5('qa96:a')::uuid));
SELECT pg_temp.expect_fail('D10 a send of an invoice that was issued', $q$
    SELECT pg_temp.sent('a', pg_temp.tx('a'), NOW() + INTERVAL '2 minutes') $q$);

\echo '--- the shape of the checks'
SELECT pg_temp.expect_true('E1 no send on record is dated before its invoice was submitted',
    NOT EXISTS (SELECT 1 FROM invoicing.einvoice_sending s JOIN invoicing.einvoice i ON i.id = s.einvoice_id
                 WHERE s.last_sent_at < i.submitted_at));
SELECT pg_temp.expect_true('E2 the check fires on an insert and on a change of the send, not on a change of the lease',
    (SELECT count(*) = 1 FROM pg_trigger t
      WHERE t.tgrelid = 'invoicing.einvoice_sending'::regclass AND NOT t.tgisinternal
        AND t.tgname = 'tg_einvoice_sending_check' AND array_length(t.tgattr::int2[], 1) = 2));

ROLLBACK;
