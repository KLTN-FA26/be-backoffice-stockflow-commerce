-- =============================================================================
-- Adversarial checks: WF-05 HĐ-3 beyond the issued original (V20261203000300; the WF-05 review, defect 5) - an
-- invoice at the provider when its delivery is corrected to a failure, and a replacement standing for the shipment.
-- Needs the demo seed for customers and a user. Rolled back at the end.
--
-- Every refusal is checked against the constraint or trigger that is meant to give it.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
\if :{?every}
SELECT set_config('qa93.every', 'on', FALSE) \g /dev/null
\endif
CREATE FUNCTION pg_temp.fail(message TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('qa93.every', TRUE) = 'on' THEN
        RAISE NOTICE 'FAIL  %', message;
    ELSE
        RAISE EXCEPTION 'FAIL  %', message;
    END IF;
END $$;

CREATE FUNCTION pg_temp.expect_refused(label TEXT, by TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    refused_by TEXT;
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints and constraint triggers fire here, still inside the sub-transaction.
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
        -- Accepted: leave the sub-transaction by an error of our own, so that what the statement wrote is undone.
        RAISE EXCEPTION 'accepted' USING ERRCODE = 'QA930';
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE = 'QA930' THEN
            PERFORM pg_temp.fail(label || ': the database accepted it (expected ' || by || ')');
            RETURN;
        END IF;
        GET STACKED DIAGNOSTICS refused_by = CONSTRAINT_NAME;
        IF refused_by IS DISTINCT FROM by THEN
            PERFORM pg_temp.fail(format('%s: refused by %s and not by %s  [%s]', label,
                COALESCE(NULLIF(refused_by, ''), '?'), by, left(SQLERRM, 160)));
            RETURN;
        END IF;
        RAISE NOTICE 'ok    %  [%]', label, by;
    END;
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
        PERFORM pg_temp.fail(label);
        RETURN;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- Fixtures: the first customer's own SKU in cartons of 50; an order of 40 cartons at 2,000 a piece handed over on four
-- shipments; an order of another customer on one shipment; two returns of the first order.
INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
VALUES (md5('qa93:other-buyer')::uuid, 'QA cong ty khac', 'qa93-other@example.com', '0355555593', 'ACTIVE', 0, NOW());
CREATE TEMP TABLE qa93_customer ON COMMIT DROP AS
SELECT id, 1 AS n FROM (SELECT id FROM customer.customer WHERE id <> md5('qa93:other-buyer')::uuid ORDER BY id LIMIT 1) c
UNION ALL SELECT md5('qa93:other-buyer')::uuid, 2;
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qa93:product')::uuid, 'QADF', 'QA ly dieu chinh', 'qa-df', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa93:blank')::uuid, md5('qa93:product')::uuid, 'QA-DF-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa93:draft')::uuid, c.id, md5('qa93:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM qa93_customer c WHERE c.n = 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa93:snap')::uuid, md5('qa93:draft')::uuid, repeat('c', 64), 'https://example.com/c.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa93:own')::uuid, md5('qa93:product')::uuid, 'QA-DF-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-DF-OWN', 1, 0, NOW(), c.id, md5('qa93:snap')::uuid, md5('qa93:blank')::uuid
  FROM qa93_customer c WHERE c.n = 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, active,
                                       version, created_at, default_shipping)
SELECT md5('qa93:carton')::uuid, i.id, 'CARTON', 50, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-DF-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa93:order' || c.n)::uuid, 'SO-QA-DF' || c.n, c.id, gen_random_uuid(), 'DRAFT', 4400000, 400000, 'VND',
       NOW(), NOW()
  FROM qa93_customer c;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa93:line')::uuid, md5('qa93:order1')::uuid, 'QA-DF-OWN', 2000, 2000, 'VND', md5('qa93:carton')::uuid, 40,
        50, 10, 400000);
INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, package_count, ready_at, created_at)
SELECT md5('qa93:' || s.tag)::uuid, md5('qa93:order' || s.o)::uuid, 'READY_TO_DISPATCH', 'SO-QA-DF-' || upper(s.tag),
       md5('demo:wh:HCM')::uuid, 1, NOW(), NOW()
  FROM (VALUES ('s1', 1), ('s2', 1), ('s3', 1), ('s4', 1), ('x1', 2)) AS s(tag, o);
-- S2 was delivered: the returns R1 and R2 are against it (WF-05 TH-1, V20261201000300), as its invoice B is.
INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, outcome_at)
SELECT md5('qa93:' || s.tag)::uuid, md5('qa93:order' || s.o)::uuid,
       CASE s.tag WHEN 's2' THEN 'DELIVERED' ELSE 'HANDED_OVER' END, 1100000, NOW(),
       CASE s.tag WHEN 's2' THEN NOW() END
  FROM (VALUES ('s1', 1), ('s2', 1), ('s3', 1), ('s4', 1), ('x1', 2)) AS s(tag, o);
INSERT INTO ordering.return_request (id, rma_number, order_id, customer_id, status, reason_code, requested_at,
                                     order_number, request_id, channel, requested_by, shipment_id)
SELECT md5('qa93:' || r.tag)::uuid, 'RMA-QA93-' || r.tag, o.id, o.customer_id, 'REQUESTED', 'DAMAGED_IN_TRANSIT', NOW(),
       o.order_number, gen_random_uuid(), 'BACK_OFFICE', (SELECT id FROM identity.app_user ORDER BY id LIMIT 1),
       md5('qa93:s2')::uuid
  FROM ordering.customer_order o, (VALUES ('r1'), ('r2')) AS r(tag)
 WHERE o.id = md5('qa93:order1')::uuid;

-- An original of a shipment: `cartons` cartons x 100,000 at 10%.
CREATE FUNCTION pg_temp.original(tag TEXT, order_no INT, shipment TEXT, cartons INT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
                                    issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                    buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                    tax_total, grand_total, amount_in_words)
    SELECT md5('qa93:' || tag)::uuid, 'EINV-QA93-' || tag, 'ORIGINAL', 'DRAFT', o.id, o.order_number,
           md5('qa93:' || shipment)::uuid, o.customer_id, DATE '2026-11-12', TIMESTAMPTZ '2026-11-13 17:00:00+00',
           'CONG TY BAN', '0312345678', '1 Le Loi', 'CONG TY MUA', '0398765432', '12 Nguyen Hue', 'kt@mua.example.com',
           'VND', 'BANK_TRANSFER', cartons * 100000, cartons * 10000, cartons * 110000, 'so tien bang chu'
      FROM ordering.customer_order o WHERE o.id = md5('qa93:order' || order_no)::uuid;
    IF order_no = 1 THEN
        PERFORM pg_temp.goods(tag, 1, cartons);
    ELSE
        INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, item_name, unit, quantity,
                                             unit_price, tax_rate, amount, tax_amount)
        VALUES (gen_random_uuid(), md5('qa93:' || tag)::uuid, 1, 'FEE', 'SHIPPING_FEE', 'Phi van chuyen', 'Lan', 1,
                cartons * 100000, 10, cartons * 100000, cartons * 10000);
    END IF;
END $$;
-- A goods line of `cartons` cartons (below zero on an adjustment that reduces).
CREATE FUNCTION pg_temp.goods(tag TEXT, line_no INT, cartons INT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, order_line_id, sku, item_name, unit, quantity,
                                         units_per_carton, unit_price, tax_rate, amount, tax_amount)
    VALUES (md5('qa93:' || tag || ':' || line_no)::uuid, md5('qa93:' || tag)::uuid, line_no, 'GOODS',
            md5('qa93:line')::uuid, 'QA-DF-OWN', 'QA ly in logo', 'Thung', cartons, 50, 100000, 10, cartons * 100000,
            cartons * 10000);
$$;
-- A note line: an adjustment that changes no money still says what it corrects.
CREATE FUNCTION pg_temp.note(tag TEXT, line_no INT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, item_name, unit, quantity, unit_price, tax_rate,
                                         amount, tax_amount)
    VALUES (md5('qa93:' || tag || ':' || line_no)::uuid, md5('qa93:' || tag)::uuid, line_no, 'NOTE',
            'Dieu chinh ma so thue nguoi mua', 'Lan', 1, 0, 0, 0, 0);
$$;
-- The head of an adjustment or replacement of invoice `of`, for `cartons` cartons (below zero reduces; 0: no money).
-- Its shipment, order and buyer are those of the invoice it corrects.
CREATE FUNCTION pg_temp.correction(tag TEXT, kind TEXT, basis TEXT, of TEXT, cartons INT, return_tag TEXT DEFAULT NULL,
                                   reason TEXT DEFAULT 'sai so luong', shipment TEXT DEFAULT NULL,
                                   via TEXT DEFAULT NULL) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
                                    original_einvoice_id, correction_basis, return_request_id, issue_date, issue_due_at,
                                    seller_name, seller_tax_code, seller_address, buyer_name, buyer_tax_code,
                                    buyer_address, buyer_email, currency, payment_method, subtotal, tax_total,
                                    grand_total, amount_in_words, adjustment_reason, issued_via)
    SELECT md5('qa93:' || tag)::uuid, 'EINV-QA93-' || tag, kind, 'DRAFT', o.order_id, o.order_number,
           COALESCE(md5('qa93:' || shipment)::uuid, o.shipment_id), o.customer_id, o.id, basis, md5('qa93:' || return_tag)::uuid, DATE '2026-11-12',
           TIMESTAMPTZ '2026-11-13 17:00:00+00', o.seller_name, o.seller_tax_code, o.seller_address, o.buyer_name,
           o.buyer_tax_code, o.buyer_address, o.buyer_email, 'VND', 'BANK_TRANSFER', cartons * 100000, cartons * 10000,
           cartons * 110000, 'so tien bang chu', reason, via
      FROM invoicing.einvoice o WHERE o.id = md5('qa93:' || of)::uuid;
$$;
-- The written agreement of both parties (TT 91 Điều 10 khoản 1 điểm b): its file, number and date.
CREATE FUNCTION pg_temp.agreement(tag TEXT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET agreement_file_key = 'einvoices/2026/11/12/' || tag || '-agreement.pdf', agreement_number = 'BB-' || tag,
           agreement_date = DATE '2026-11-12'
     WHERE id = md5('qa93:' || tag)::uuid;
$$;
CREATE FUNCTION pg_temp.submit(tag TEXT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'SUBMITTING', provider = 'SANDBOX', attempt = attempt + 1,
           transaction_id = id::text || '-' || (attempt + 1), template_code = '1', symbol = 'C26TSB',
           submitted_at = NOW()
     WHERE id = md5('qa93:' || tag)::uuid;
$$;
CREATE FUNCTION pg_temp.issue(tag TEXT, number INT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'ISSUED', invoice_number = number, tax_authority_code = 'SBX' || number, lookup_code = 'L' || number,
           signed_at = NOW(), issued_at = NOW(), xml_file_key = 'einvoices/2026/11/12/' || tag || '.xml',
           pdf_file_key = 'einvoices/2026/11/12/' || tag || '.pdf'
     WHERE id = md5('qa93:' || tag)::uuid;
$$;
-- An original issued under `number`.
CREATE FUNCTION pg_temp.issued_original(tag TEXT, order_no INT, shipment TEXT, cartons INT, number INT) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_temp.original(tag, order_no, shipment, cartons);
    PERFORM pg_temp.submit(tag);
    PERFORM pg_temp.issue(tag, number);
END $$;
CREATE FUNCTION pg_temp.status_of(tag TEXT) RETURNS TEXT LANGUAGE sql AS $$
    SELECT status || CASE WHEN adjusted THEN '+adjusted' ELSE '' END FROM invoicing.einvoice
     WHERE id = md5('qa93:' || tag)::uuid;
$$;

SELECT pg_temp.expect_ok('fixtures: an original at the provider, an issued one, an issued one to replace', $q$
    SELECT pg_temp.original('a', 1, 's1', 10);
    SELECT pg_temp.submit('a');
    SELECT pg_temp.issued_original('b', 1, 's2', 10, 930002);
    SELECT pg_temp.issued_original('c', 1, 's3', 10, 930003);
    SELECT pg_temp.original('d', 1, 's4', 10) $q$);

\echo '--- D. an invoice at the provider whose delivery failed: marked once, never sent again'
SELECT pg_temp.expect_refused('D1 drawn up already marked', 'tg_einvoice_delivery_failed', $q$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
                                    issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                    buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                    tax_total, grand_total, amount_in_words, delivery_failed_at)
    SELECT md5('qa93:d1')::uuid, 'EINV-QA93-d1', 'ORIGINAL', 'DRAFT', o.id, o.order_number, md5('qa93:x1')::uuid,
           o.customer_id, DATE '2026-11-12', TIMESTAMPTZ '2026-11-13 17:00:00+00', 'CONG TY BAN', '0312345678',
           '1 Le Loi', 'CONG TY MUA', '0398765432', '12 Nguyen Hue', 'kt@mua.example.com', 'VND', 'BANK_TRANSFER', 0, 0,
           0, 'khong dong', NOW()
      FROM ordering.customer_order o WHERE o.id = md5('qa93:order2')::uuid $q$);
SELECT pg_temp.expect_refused('D2 a draft marked: it is voided, not marked', 'tg_einvoice_delivery_failed', $q$
    UPDATE invoicing.einvoice SET delivery_failed_at = NOW() WHERE id = md5('qa93:d')::uuid $q$);
SELECT pg_temp.expect_refused('D3 an issued invoice marked: it is reduced, not marked', 'tg_einvoice_delivery_failed', $q$
    UPDATE invoicing.einvoice SET delivery_failed_at = NOW() WHERE id = md5('qa93:b')::uuid $q$);
SELECT pg_temp.expect_ok('D4 the invoice at the provider marked', $q$
    UPDATE invoicing.einvoice SET delivery_failed_at = NOW() WHERE id = md5('qa93:a')::uuid $q$);
SELECT pg_temp.expect_refused('D5 the mark moved to another time', 'tg_einvoice_delivery_failed', $q$
    UPDATE invoicing.einvoice SET delivery_failed_at = NOW() + INTERVAL '1 day' WHERE id = md5('qa93:a')::uuid $q$);
SELECT pg_temp.expect_ok('D5b delivered after all: the mark taken back while at the provider, and set again', $q$
    UPDATE invoicing.einvoice SET delivery_failed_at = NULL WHERE id = md5('qa93:a')::uuid;
    UPDATE invoicing.einvoice SET delivery_failed_at = NOW() WHERE id = md5('qa93:a')::uuid $q$);
SELECT pg_temp.expect_ok('D6 the provider refuses it, and it goes back to a draft', $q$
    UPDATE invoicing.einvoice SET status = 'REJECTED', rejection_code = 'INVALID_BUYER_TAX_CODE'
     WHERE id = md5('qa93:a')::uuid;
    UPDATE invoicing.einvoice SET status = 'DRAFT' WHERE id = md5('qa93:a')::uuid $q$);
SELECT pg_temp.expect_refused('D7 ... and is not sent again for goods nobody received', 'tg_einvoice_delivery_failed', $q$
    SELECT pg_temp.submit('a') $q$);
SELECT pg_temp.expect_ok('D8 it is voided', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'giao that bai'
     WHERE id = md5('qa93:a')::uuid $q$);
SELECT pg_temp.expect_refused('D9 an adjustment at the provider is not marked: only what stands for a shipment',
    'ck_einvoice_delivery_failed', $q$
    SELECT pg_temp.correction('badj', 'ADJUSTMENT', 'TRADE_DISCOUNT', 'b', -1, NULL, 'chiet khau');
    SELECT pg_temp.goods('badj', 1, -1);
    SELECT pg_temp.submit('badj');
    UPDATE invoicing.einvoice SET delivery_failed_at = NOW() WHERE id = md5('qa93:badj')::uuid $q$);

\echo '--- R. a replacement standing for the shipment, reduced in full for a failed delivery'
SELECT pg_temp.expect_ok('R1 the replacement of c issued, c REPLACED', $q$
    SELECT pg_temp.correction('crep', 'REPLACEMENT', 'ERROR', 'c', 10, NULL, 'sai ten hang');
    SELECT pg_temp.goods('crep', 1, 10);
    SELECT pg_temp.agreement('crep');
    SELECT pg_temp.submit('crep');
    UPDATE invoicing.einvoice SET status = 'REPLACED' WHERE id = md5('qa93:c')::uuid;
    SELECT pg_temp.issue('crep', 930021) $q$);
SELECT pg_temp.expect_refused('R2 a second original of the shipment while the replacement stands',
    'tg_einvoice_original_per_shipment', $q$ SELECT pg_temp.original('cnew0', 1, 's3', 10) $q$);
SELECT pg_temp.expect_refused('R3 the replacement reversed with no reduction of it', 'tg_einvoice_reversed_by', $q$
    UPDATE invoicing.einvoice SET reversed_at = NOW() WHERE id = md5('qa93:crep')::uuid $q$);
-- WF-05 Q-9: no provider adjusts a replacement (SePay 422, sandbox T-5), so the system never draws up its reduction;
-- the accountant makes it on the provider's portal and records it (issued_via PORTAL_RECORD, Q-8).
SELECT pg_temp.expect_refused('R4 the system drafts a reduction of the replacement', 'tg_einvoice_correction_form', $q$
    SELECT pg_temp.correction('crev0', 'ADJUSTMENT', 'DELIVERY_FAILED', 'crep', -10, NULL, 'giao that bai') $q$);
SELECT pg_temp.expect_ok('R4b the reduction made on the portal recorded, and the replacement reversed', $q$
    SELECT pg_temp.correction('crev', 'ADJUSTMENT', 'DELIVERY_FAILED', 'crep', -10, NULL, 'giao that bai', NULL,
                              'PORTAL_RECORD');
    SELECT pg_temp.goods('crev', 1, -10);
    UPDATE invoicing.einvoice SET reversed_at = NOW() WHERE id = md5('qa93:crep')::uuid $q$);
SELECT pg_temp.expect_refused('R5 the replaced original is still final: not reversed itself', 'tg_einvoice_final', $q$
    UPDATE invoicing.einvoice SET reversed_at = NOW() WHERE id = md5('qa93:c')::uuid $q$);
SELECT pg_temp.expect_ok('R6 the shipment delivered again takes a new original', $q$
    SELECT pg_temp.original('cnew', 1, 's3', 10) $q$);
SELECT pg_temp.expect_refused('R7 ... and only one', 'uk_einvoice_original_per_shipment', $q$
    SELECT pg_temp.original('cnew2', 1, 's3', 10) $q$);
SELECT pg_temp.expect_ok('R8 the new original issued beside the reversed replacement', $q$
    SELECT pg_temp.submit('cnew');
    SELECT pg_temp.issue('cnew', 930022) $q$);
SELECT pg_temp.expect_true('R9 one invoice in force for the shipment: the new original',
    (SELECT count(*) = 1 FROM invoicing.einvoice
      WHERE shipment_id = md5('qa93:s3')::uuid AND type IN ('ORIGINAL', 'REPLACEMENT') AND status = 'ISSUED'
        AND reversed_at IS NULL)
    AND (SELECT reversed_at IS NOT NULL FROM invoicing.einvoice WHERE id = md5('qa93:crep')::uuid));

ROLLBACK;
