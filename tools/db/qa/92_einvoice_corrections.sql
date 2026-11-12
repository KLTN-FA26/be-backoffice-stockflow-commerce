-- =============================================================================
-- Adversarial checks + happy paths: corrections to issued e-invoices (V20261120000500; WF-05 E4; plan §1.4;
-- TT 91/2026/TT-BTC Điều 10 as read in plans/E0-legal.md, one block per row of its table; kltn-docs 21 §4.4, §5.1,
-- BR-03, BR-08). Needs the demo seed for customers and a user. Rolled back at the end.
--
-- Every refusal is checked against the constraint or trigger that is meant to give it: a statement refused for
-- another reason (a fixture gone stale, a column renamed) fails the script instead of passing for the wrong reason.
--
-- To see every rule that does not hold instead of stopping at the first: psql -v every=1 (each FAIL is then a line,
-- and what the failing statement wrote is undone, so the checks after it still start from the state they expect).
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
\if :{?every}
SELECT set_config('qa92.every', 'on', FALSE) \g /dev/null
\endif
CREATE FUNCTION pg_temp.fail(message TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('qa92.every', TRUE) = 'on' THEN
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
        RAISE EXCEPTION 'accepted' USING ERRCODE = 'QA920';
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE = 'QA920' THEN
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
VALUES (md5('qa92:other-buyer')::uuid, 'QA cong ty khac', 'qa92-other@example.com', '0355555555', 'ACTIVE', 0, NOW());
CREATE TEMP TABLE qa92_customer ON COMMIT DROP AS
SELECT id, 1 AS n FROM (SELECT id FROM customer.customer WHERE id <> md5('qa92:other-buyer')::uuid ORDER BY id LIMIT 1) c
UNION ALL SELECT md5('qa92:other-buyer')::uuid, 2;
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qa92:product')::uuid, 'QAEC', 'QA ly dieu chinh', 'qa-ec', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa92:blank')::uuid, md5('qa92:product')::uuid, 'QA-EC-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa92:draft')::uuid, c.id, md5('qa92:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM qa92_customer c WHERE c.n = 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa92:snap')::uuid, md5('qa92:draft')::uuid, repeat('c', 64), 'https://example.com/c.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa92:own')::uuid, md5('qa92:product')::uuid, 'QA-EC-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-EC-OWN', 1, 0, NOW(), c.id, md5('qa92:snap')::uuid, md5('qa92:blank')::uuid
  FROM qa92_customer c WHERE c.n = 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa92:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-EC-OWN';
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, created_at)
SELECT md5('qa92:order' || c.n)::uuid, 'SO-QA-EC' || c.n, c.id, gen_random_uuid(), 'DRAFT', 4400000, 400000, 'VND',
       NOW(), NOW()
  FROM qa92_customer c;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton, tax_rate, tax_amount)
VALUES (md5('qa92:line')::uuid, md5('qa92:order1')::uuid, 'QA-EC-OWN', 2000, 2000, 'VND', md5('qa92:carton')::uuid, 40,
        50, 10, 400000);
INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, package_count, ready_at, created_at)
SELECT md5('qa92:' || s.tag)::uuid, md5('qa92:order' || s.o)::uuid, 'READY_TO_DISPATCH', 'SO-QA-EC-' || upper(s.tag),
       md5('demo:wh:HCM')::uuid, 1, NOW(), NOW()
  FROM (VALUES ('s1', 1), ('s2', 1), ('s3', 1), ('s4', 1), ('x1', 2)) AS s(tag, o);
INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
SELECT md5('qa92:' || s.tag)::uuid, md5('qa92:order' || s.o)::uuid, 'HANDED_OVER', 1100000, NOW()
  FROM (VALUES ('s1', 1), ('s2', 1), ('s3', 1), ('s4', 1), ('x1', 2)) AS s(tag, o);
INSERT INTO ordering.return_request (id, rma_number, order_id, customer_id, status, reason_code, requested_at,
                                     order_number, request_id, channel, requested_by)
SELECT md5('qa92:' || r.tag)::uuid, 'RMA-QA92-' || r.tag, o.id, o.customer_id, 'REQUESTED', 'DAMAGED_IN_TRANSIT', NOW(),
       o.order_number, gen_random_uuid(), 'BACK_OFFICE', (SELECT id FROM identity.app_user ORDER BY id LIMIT 1)
  FROM ordering.customer_order o, (VALUES ('r1'), ('r2')) AS r(tag)
 WHERE o.id = md5('qa92:order1')::uuid;

-- An original of a shipment: `cartons` cartons x 100,000 at 10%.
CREATE FUNCTION pg_temp.original(tag TEXT, order_no INT, shipment TEXT, cartons INT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
                                    issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                    buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                    tax_total, grand_total, amount_in_words)
    SELECT md5('qa92:' || tag)::uuid, 'EINV-QA92-' || tag, 'ORIGINAL', 'DRAFT', o.id, o.order_number,
           md5('qa92:' || shipment)::uuid, o.customer_id, DATE '2026-11-12', TIMESTAMPTZ '2026-11-13 17:00:00+00',
           'CONG TY BAN', '0312345678', '1 Le Loi', 'CONG TY MUA', '0398765432', '12 Nguyen Hue', 'kt@mua.example.com',
           'VND', 'BANK_TRANSFER', cartons * 100000, cartons * 10000, cartons * 110000, 'so tien bang chu'
      FROM ordering.customer_order o WHERE o.id = md5('qa92:order' || order_no)::uuid;
    IF order_no = 1 THEN
        PERFORM pg_temp.goods(tag, 1, cartons);
    ELSE
        INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, item_name, unit, quantity,
                                             unit_price, tax_rate, amount, tax_amount)
        VALUES (gen_random_uuid(), md5('qa92:' || tag)::uuid, 1, 'FEE', 'SHIPPING_FEE', 'Phi van chuyen', 'Lan', 1,
                cartons * 100000, 10, cartons * 100000, cartons * 10000);
    END IF;
END $$;
-- A goods line of `cartons` cartons (below zero on an adjustment that reduces).
CREATE FUNCTION pg_temp.goods(tag TEXT, line_no INT, cartons INT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, order_line_id, sku, item_name, unit, quantity,
                                         units_per_carton, unit_price, tax_rate, amount, tax_amount)
    VALUES (md5('qa92:' || tag || ':' || line_no)::uuid, md5('qa92:' || tag)::uuid, line_no, 'GOODS',
            md5('qa92:line')::uuid, 'QA-EC-OWN', 'QA ly in logo', 'Thung', cartons, 50, 100000, 10, cartons * 100000,
            cartons * 10000);
$$;
-- A note line: an adjustment that changes no money still says what it corrects.
CREATE FUNCTION pg_temp.note(tag TEXT, line_no INT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, item_name, unit, quantity, unit_price, tax_rate,
                                         amount, tax_amount)
    VALUES (md5('qa92:' || tag || ':' || line_no)::uuid, md5('qa92:' || tag)::uuid, line_no, 'NOTE',
            'Dieu chinh ma so thue nguoi mua', 'Lan', 1, 0, 0, 0, 0);
$$;
-- The head of an adjustment or replacement of invoice `of`, for `cartons` cartons (below zero reduces; 0: no money).
-- Its shipment, order and buyer are those of the invoice it corrects.
CREATE FUNCTION pg_temp.correction(tag TEXT, kind TEXT, basis TEXT, of TEXT, cartons INT, return_tag TEXT DEFAULT NULL,
                                   reason TEXT DEFAULT 'sai so luong', shipment TEXT DEFAULT NULL) RETURNS void LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
                                    original_einvoice_id, correction_basis, return_request_id, issue_date, issue_due_at,
                                    seller_name, seller_tax_code, seller_address, buyer_name, buyer_tax_code,
                                    buyer_address, buyer_email, currency, payment_method, subtotal, tax_total,
                                    grand_total, amount_in_words, adjustment_reason)
    SELECT md5('qa92:' || tag)::uuid, 'EINV-QA92-' || tag, kind, 'DRAFT', o.order_id, o.order_number,
           COALESCE(md5('qa92:' || shipment)::uuid, o.shipment_id), o.customer_id, o.id, basis, md5('qa92:' || return_tag)::uuid, DATE '2026-11-12',
           TIMESTAMPTZ '2026-11-13 17:00:00+00', o.seller_name, o.seller_tax_code, o.seller_address, o.buyer_name,
           o.buyer_tax_code, o.buyer_address, o.buyer_email, 'VND', 'BANK_TRANSFER', cartons * 100000, cartons * 10000,
           cartons * 110000, 'so tien bang chu', reason
      FROM invoicing.einvoice o WHERE o.id = md5('qa92:' || of)::uuid;
$$;
-- The written agreement of both parties (TT 91 Điều 10 khoản 1 điểm b): its file, number and date.
CREATE FUNCTION pg_temp.agreement(tag TEXT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET agreement_file_key = 'einvoices/2026/11/12/' || tag || '-agreement.pdf', agreement_number = 'BB-' || tag,
           agreement_date = DATE '2026-11-12'
     WHERE id = md5('qa92:' || tag)::uuid;
$$;
CREATE FUNCTION pg_temp.submit(tag TEXT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'SUBMITTING', provider = 'SANDBOX', attempt = attempt + 1,
           transaction_id = id::text || '-' || (attempt + 1), template_code = '1', symbol = 'C26TSB',
           submitted_at = NOW()
     WHERE id = md5('qa92:' || tag)::uuid;
$$;
CREATE FUNCTION pg_temp.issue(tag TEXT, number INT) RETURNS void LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'ISSUED', invoice_number = number, tax_authority_code = 'SBX' || number, lookup_code = 'L' || number,
           signed_at = NOW(), issued_at = NOW(), xml_file_key = 'einvoices/2026/11/12/' || tag || '.xml',
           pdf_file_key = 'einvoices/2026/11/12/' || tag || '.pdf'
     WHERE id = md5('qa92:' || tag)::uuid;
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
     WHERE id = md5('qa92:' || tag)::uuid;
$$;

SELECT pg_temp.expect_ok('fixtures: four issued originals of one order, one of another buyer, one draft', $q$
    SELECT pg_temp.issued_original('a', 1, 's1', 10, 920001);
    SELECT pg_temp.issued_original('b', 1, 's2', 10, 920002);
    SELECT pg_temp.issued_original('c', 1, 's3', 10, 920003);
    SELECT pg_temp.issued_original('x', 2, 'x1', 10, 920004);
    SELECT pg_temp.original('d', 1, 's4', 10) $q$);

\echo '--- A. no way to cancel an issued invoice (TT 91 Điều 10 has no such procedure; 21 BR-03; E0 row 6)'
SELECT pg_temp.expect_refused('A1 ISSUED -> VOIDED_DRAFT', 'tg_einvoice_issued_immutable', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'huy hoa don'
     WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A2 ISSUED -> DRAFT', 'tg_einvoice_issued_immutable', $q$
    UPDATE invoicing.einvoice SET status = 'DRAFT' WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A3 ISSUED -> SUBMITTING', 'tg_einvoice_issued_immutable', $q$
    UPDATE invoicing.einvoice SET status = 'SUBMITTING' WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A4 ISSUED -> REJECTED', 'tg_einvoice_issued_immutable', $q$
    UPDATE invoicing.einvoice SET status = 'REJECTED', rejection_code = 'CANCELLED' WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A5 a status "CANCELLED" does not exist', 'tg_einvoice_issued_immutable', $q$
    UPDATE invoicing.einvoice SET status = 'CANCELLED' WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A6 a draft does not become "CANCELLED" either: the vocabulary has no such word',
    'tg_einvoice_transition', $q$ UPDATE invoicing.einvoice SET status = 'CANCELLED' WHERE id = md5('qa92:d')::uuid $q$);
SELECT pg_temp.expect_refused('A7 an issued invoice is deleted', 'tg_einvoice_no_delete', $q$
    DELETE FROM invoicing.einvoice WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A8 its lines are deleted', 'tg_einvoice_line_draft_only', $q$
    DELETE FROM invoicing.einvoice_line WHERE einvoice_id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A9 ISSUED -> REPLACED by hand, with no replacement being issued: not a cancel in disguise',
    'tg_einvoice_replaced_by', $q$ UPDATE invoicing.einvoice SET status = 'REPLACED' WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A10 ... nor with a replacement that is still only a draft', 'tg_einvoice_replaced_by', $q$
    SELECT pg_temp.correction('a-rep0', 'REPLACEMENT', 'ERROR', 'a', 10);
    SELECT pg_temp.goods('a-rep0', 1, 10);
    UPDATE invoicing.einvoice SET status = 'REPLACED' WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A11 flagged "has an adjustment" by hand, with no adjustment being issued',
    'tg_einvoice_adjusted_by', $q$ UPDATE invoicing.einvoice SET adjusted = TRUE WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('A12 its totals are zeroed in place', 'tg_einvoice_issued_immutable', $q$
    UPDATE invoicing.einvoice SET subtotal = 0, tax_total = 0, grand_total = 0 WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_true('A13 after all that the invoice is as it was issued',
    pg_temp.status_of('a') = 'ISSUED'
    AND (SELECT count(*) = 1 FROM invoicing.einvoice WHERE shipment_id = md5('qa92:s1')::uuid));

\echo '--- B. E0 row 1: a wrong name or address, the tax code right -> a 04/SS notice and NO new invoice (khoản 1 điểm a)'
SELECT pg_temp.expect_refused('B1 a notice that says neither the right name nor the right address',
    'ck_einvoice_error_notice_correct', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason)
    VALUES (gen_random_uuid(), md5('qa92:a')::uuid, 'sai dia chi') $q$);
SELECT pg_temp.expect_refused('B2 a notice of a draft', 'tg_einvoice_error_notice_issued', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, correct_buyer_address)
    VALUES (gen_random_uuid(), md5('qa92:d')::uuid, 'sai dia chi', '14 Nguyen Hue') $q$);
SELECT pg_temp.expect_refused('B3 a notice recorded as already sent', 'tg_einvoice_error_notice_starts', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, correct_buyer_address, status, sent_at)
    VALUES (gen_random_uuid(), md5('qa92:a')::uuid, 'sai dia chi', '14 Nguyen Hue', 'SENT', NOW()) $q$);
SELECT pg_temp.expect_refused('B4 a notice on another form than 04/SS-HDDT', 'ck_einvoice_error_notice_form', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, correct_buyer_address, form_code)
    VALUES (gen_random_uuid(), md5('qa92:a')::uuid, 'sai dia chi', '14 Nguyen Hue', '01/TB-SSDT') $q$);
SELECT pg_temp.expect_refused('B5 a notice without its reason', 'ck_einvoice_error_notice_reason', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, correct_buyer_address)
    VALUES (gen_random_uuid(), md5('qa92:a')::uuid, '  ', '14 Nguyen Hue') $q$);
SELECT pg_temp.expect_ok('B6 the notice: the right address, on Mẫu 04/SS-HĐĐT', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, correct_buyer_address)
    VALUES (md5('qa92:n1')::uuid, md5('qa92:a')::uuid, 'sai dia chi nguoi mua', '14 Nguyen Hue, P. Ben Nghe, TP HCM') $q$);
SELECT pg_temp.expect_true('B7 no invoice was drawn up and the issued one is untouched: still the only one of its shipment',
    (SELECT count(*) = 1 FROM invoicing.einvoice WHERE shipment_id = md5('qa92:s1')::uuid)
    AND (SELECT count(*) = 0 FROM invoicing.einvoice WHERE original_einvoice_id = md5('qa92:a')::uuid
            AND code NOT LIKE '%rep0')
    AND (SELECT status = 'ISSUED' AND NOT adjusted AND buyer_address = '12 Nguyen Hue'
           FROM invoicing.einvoice WHERE id = md5('qa92:a')::uuid)
    AND (SELECT form_code = '04/SS-HDDT' AND subject = 'BUYER_NAME_ADDRESS' AND status = 'DRAFT'
           FROM invoicing.einvoice_error_notice WHERE id = md5('qa92:n1')::uuid));
SELECT pg_temp.expect_refused('B8 a second notice of the invoice while the first is on its way',
    'uk_einvoice_error_notice_open', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, correct_buyer_name)
    VALUES (gen_random_uuid(), md5('qa92:a')::uuid, 'sai ten', 'CONG TY MUA HANG') $q$);
SELECT pg_temp.expect_refused('B9 what the notice says is changed', 'tg_einvoice_error_notice_content', $q$
    UPDATE invoicing.einvoice_error_notice SET correct_buyer_address = 'khac' WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_refused('B10 the notice is moved to another invoice', 'tg_einvoice_error_notice_content', $q$
    UPDATE invoicing.einvoice_error_notice SET einvoice_id = md5('qa92:b')::uuid WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_refused('B11 the notice is deleted', 'tg_einvoice_error_notice_no_delete', $q$
    DELETE FROM invoicing.einvoice_error_notice WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_refused('B12 SENT without when', 'ck_einvoice_error_notice_sent', $q$
    UPDATE invoicing.einvoice_error_notice SET status = 'SENT' WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_ok('B13 DRAFT -> SENT', $q$
    UPDATE invoicing.einvoice_error_notice SET status = 'SENT', sent_at = NOW() WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_refused('B14 SENT -> DRAFT', 'tg_einvoice_error_notice_transition', $q$
    UPDATE invoicing.einvoice_error_notice SET status = 'DRAFT', sent_at = NULL WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_refused('B15 an answer without when it came', 'ck_einvoice_error_notice_response', $q$
    UPDATE invoicing.einvoice_error_notice SET status = 'ACCEPTED' WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_ok('B16 SENT -> ACCEPTED: the tax authority''s 01/TB-SSĐT (khoản 4)', $q$
    UPDATE invoicing.einvoice_error_notice SET status = 'ACCEPTED', responded_at = NOW(), response_code = '01/TB-SSDT'
     WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_refused('B17 an answered notice is final: ACCEPTED -> REJECTED',
    'tg_einvoice_error_notice_transition', $q$
    UPDATE invoicing.einvoice_error_notice SET status = 'REJECTED' WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_refused('B18 ... not even its answer is rewritten', 'tg_einvoice_error_notice_transition', $q$
    UPDATE invoicing.einvoice_error_notice SET response_message = 'khac' WHERE id = md5('qa92:n1')::uuid $q$);
SELECT pg_temp.expect_ok('B19 once answered, the invoice may be notified again (a wrong name this time)', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, correct_buyer_name)
    VALUES (md5('qa92:n2')::uuid, md5('qa92:a')::uuid, 'sai ten', 'CONG TY MUA HANG') $q$);
SELECT pg_temp.expect_ok('B20 DRAFT -> REJECTED: refused before it was ever marked sent', $q$
    UPDATE invoicing.einvoice_error_notice SET status = 'REJECTED', sent_at = NOW(), responded_at = NOW()
     WHERE id = md5('qa92:n2')::uuid $q$);
SELECT pg_temp.expect_refused('B21 a notice beside a correction carries no name or address of its own',
    'ck_einvoice_error_notice_correct', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, subject, correct_buyer_name)
    VALUES (gen_random_uuid(), md5('qa92:a')::uuid, 'kem hoa don dieu chinh', 'WITH_CORRECTION', 'X') $q$);
-- (Two CHECKs refuse it; PostgreSQL names the first by name: the one saying what each subject carries.)
SELECT pg_temp.expect_refused('B22 a subject nobody knows', 'ck_einvoice_error_notice_correct', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, subject)
    VALUES (gen_random_uuid(), md5('qa92:a')::uuid, 'huy hoa don', 'CANCELLATION') $q$);

\echo '--- C. E0 rows 2-3: a wrong tax code, quantity, price, rate or amount -> an ADJUSTMENT (khoản 1 điểm b)'
SELECT pg_temp.expect_refused('C1 an original that says what it corrects', 'ck_einvoice_correction_basis', $q$
    UPDATE invoicing.einvoice SET buyer_name = buyer_name WHERE FALSE;
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
        correction_basis, issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, currency,
        payment_method, subtotal, tax_total, grand_total, amount_in_words)
    SELECT gen_random_uuid(), 'EINV-QA92-OB', 'ORIGINAL', 'DRAFT', o.id, o.order_number, md5('qa92:s4')::uuid,
           o.customer_id, 'ERROR', CURRENT_DATE, NOW(), 'S', '0312345678', 'A', 'B', 'VND', 'BANK_TRANSFER', 0, 0, 0, 'x'
      FROM ordering.customer_order o WHERE o.id = md5('qa92:order1')::uuid $q$);
SELECT pg_temp.expect_refused('C2 an adjustment that does not say what it is for', 'ck_einvoice_correction_basis', $q$
    SELECT pg_temp.correction('c2', 'ADJUSTMENT', NULL, 'a', -1);
    SELECT pg_temp.goods('c2', 1, -1) $q$);
SELECT pg_temp.expect_refused('C2b ... nor a replacement that does not', 'ck_einvoice_correction_basis', $q$
    SELECT pg_temp.correction('c2b', 'REPLACEMENT', NULL, 'b', 9);
    SELECT pg_temp.goods('c2b', 1, 9) $q$);
SELECT pg_temp.expect_refused('C2c ... which would leave for the provider with no agreement at all',
    'ck_einvoice_correction_basis', $q$
    SELECT pg_temp.correction('c2c', 'ADJUSTMENT', NULL, 'b', -1);
    SELECT pg_temp.goods('c2c', 1, -1);
    SELECT pg_temp.submit('c2c') $q$);
SELECT pg_temp.expect_refused('C3 an adjustment for a reason the law does not know', 'ck_einvoice_correction_basis', $q$
    SELECT pg_temp.correction('c3', 'ADJUSTMENT', 'CANCELLATION', 'a', -10) $q$);
SELECT pg_temp.expect_refused('C4 a correction that does not say what was wrong', 'ck_einvoice_correction_reason', $q$
    SELECT pg_temp.correction('c4', 'ADJUSTMENT', 'ERROR', 'a', -1, NULL, '  ') $q$);
SELECT pg_temp.expect_refused('C5 an adjustment of a draft', 'tg_einvoice_original_in_force', $q$
    SELECT pg_temp.correction('c5', 'ADJUSTMENT', 'ERROR', 'd', -1) $q$);
SELECT pg_temp.expect_refused('C6 an adjustment for another shipment than the invoice it corrects',
    'tg_einvoice_original_in_force', $q$
    SELECT pg_temp.correction('c6', 'ADJUSTMENT', 'ERROR', 'a', -1, NULL, 'sai so luong', 's2') $q$);
SELECT pg_temp.expect_refused('C7 an adjustment of another buyer''s invoice, under this order', 'tg_einvoice_original_in_force', $q$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
        original_einvoice_id, correction_basis, issue_date, issue_due_at, seller_name, seller_tax_code, seller_address,
        buyer_name, currency, payment_method, subtotal, tax_total, grand_total, amount_in_words, adjustment_reason)
    SELECT gen_random_uuid(), 'EINV-QA92-C7', 'ADJUSTMENT', 'DRAFT', o.id, o.order_number, md5('qa92:x1')::uuid,
           o.customer_id, md5('qa92:x')::uuid, 'ERROR', CURRENT_DATE, NOW(), 'S', '0312345678', 'A', 'B', 'VND',
           'BANK_TRANSFER', -100000, -10000, -110000, 'x', 'sai'
      FROM ordering.customer_order o WHERE o.id = md5('qa92:order1')::uuid $q$);
SELECT pg_temp.expect_refused('C8 an adjustment that names no invoice', 'ck_einvoice_original', $q$
    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, shipment_id, customer_id,
        correction_basis, issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, currency,
        payment_method, subtotal, tax_total, grand_total, amount_in_words, adjustment_reason)
    SELECT gen_random_uuid(), 'EINV-QA92-C8', 'ADJUSTMENT', 'DRAFT', o.id, o.order_number, md5('qa92:s1')::uuid,
           o.customer_id, 'ERROR', CURRENT_DATE, NOW(), 'S', '0312345678', 'A', 'B', 'VND', 'BANK_TRANSFER', -100000,
           -10000, -110000, 'x', 'sai'
      FROM ordering.customer_order o WHERE o.id = md5('qa92:order1')::uuid $q$);
SELECT pg_temp.expect_refused('C9 an adjustment that takes more off the invoice than it holds',
    'tg_einvoice_adjustment_bound', $q$
    SELECT pg_temp.correction('c9', 'ADJUSTMENT', 'ERROR', 'a', -11);
    SELECT pg_temp.goods('c9', 1, -11) $q$);
SELECT pg_temp.expect_ok('C10 a draft adjustment of invoice a: 2 cartons too many were invoiced (below zero, khoản 6 điểm c)', $q$
    SELECT pg_temp.correction('adj1', 'ADJUSTMENT', 'ERROR', 'a', -2);
    SELECT pg_temp.goods('adj1', 1, -2) $q$);
SELECT pg_temp.expect_true('C11 drawing it up changed nothing about the invoice it corrects',
    pg_temp.status_of('a') = 'ISSUED');
SELECT pg_temp.expect_refused('C12 the agreement''s file without its number and date', 'ck_einvoice_agreement', $q$
    UPDATE invoicing.einvoice SET agreement_file_key = 'einvoices/2026/11/12/bb.pdf' WHERE id = md5('qa92:adj1')::uuid $q$);
SELECT pg_temp.expect_refused('C13 the agreement''s number and date without its file', 'ck_einvoice_agreement', $q$
    UPDATE invoicing.einvoice SET agreement_number = 'BB-01', agreement_date = DATE '2026-11-12'
     WHERE id = md5('qa92:adj1')::uuid $q$);
SELECT pg_temp.expect_refused('C14 a business buyer and no written agreement: the adjustment does not leave for the provider',
    'ck_einvoice_agreement_before_sending', $q$ SELECT pg_temp.submit('adj1') $q$);
SELECT pg_temp.expect_true('C15 ... and it is still a draft', pg_temp.status_of('adj1') = 'DRAFT');
SELECT pg_temp.expect_ok('C16 the agreement is attached to the draft: file, number, date', $q$
    SELECT pg_temp.agreement('adj1') $q$);
SELECT pg_temp.expect_ok('C17 DRAFT -> SUBMITTING with the agreement', $q$ SELECT pg_temp.submit('adj1') $q$);
SELECT pg_temp.expect_refused('C18 the agreement is taken off while the adjustment is at the provider', 'tg_einvoice_content', $q$
    UPDATE invoicing.einvoice SET agreement_file_key = NULL, agreement_number = NULL, agreement_date = NULL
     WHERE id = md5('qa92:adj1')::uuid $q$);
SELECT pg_temp.expect_refused('C19 what the correction is for changes', 'tg_einvoice_identity', $q$
    UPDATE invoicing.einvoice SET correction_basis = 'TRADE_DISCOUNT' WHERE id = md5('qa92:adj1')::uuid $q$);
SELECT pg_temp.expect_refused('C20 the invoice it corrects changes', 'tg_einvoice_identity', $q$
    UPDATE invoicing.einvoice SET original_einvoice_id = md5('qa92:b')::uuid WHERE id = md5('qa92:adj1')::uuid $q$);
SELECT pg_temp.expect_refused('C21 the adjustment is issued before the invoice it adjusts says so',
    'tg_einvoice_correction_marks_original', $q$ SELECT pg_temp.issue('adj1', 920011) $q$);
SELECT pg_temp.expect_ok('C22 the adjusted invoice is flagged and the adjustment issued, together', $q$
    UPDATE invoicing.einvoice SET adjusted = TRUE WHERE id = md5('qa92:a')::uuid;
    SELECT pg_temp.issue('adj1', 920011) $q$);
SELECT pg_temp.expect_true('C23 the adjusted invoice stays ISSUED and in force, flagged; nothing else about it moved',
    pg_temp.status_of('a') = 'ISSUED+adjusted'
    AND (SELECT invoice_number = 920001 AND grand_total = 1100000 FROM invoicing.einvoice WHERE id = md5('qa92:a')::uuid));
SELECT pg_temp.expect_refused('C24 the flag is not taken back', 'tg_einvoice_issued_immutable', $q$
    UPDATE invoicing.einvoice SET adjusted = FALSE WHERE id = md5('qa92:a')::uuid $q$);
SELECT pg_temp.expect_refused('C25 an issued adjustment is as final as any issued invoice: never voided',
    'tg_einvoice_issued_immutable', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'x'
     WHERE id = md5('qa92:adj1')::uuid $q$);
SELECT pg_temp.expect_refused('C26 an adjustment is not itself adjusted: the invoice it names is', 'tg_einvoice_original_in_force', $q$
    SELECT pg_temp.correction('c26', 'ADJUSTMENT', 'ERROR', 'adj1', 1) $q$);
SELECT pg_temp.expect_refused('C27 an adjustment is not replaced', 'tg_einvoice_original_in_force', $q$
    SELECT pg_temp.correction('c27', 'REPLACEMENT', 'ERROR', 'adj1', 8) $q$);

\echo '--- D. khoản 6 điểm a: the form chosen first is kept - an adjusted invoice is adjusted again, never replaced'
SELECT pg_temp.expect_refused('D1 a replacement of the invoice that was adjusted', 'tg_einvoice_correction_form', $q$
    SELECT pg_temp.correction('d1', 'REPLACEMENT', 'ERROR', 'a', 8) $q$);
SELECT pg_temp.expect_ok('D2 a second adjustment of it, an increase (above zero); "adjusted" stays "adjusted"', $q$
    SELECT pg_temp.correction('adj2', 'ADJUSTMENT', 'ERROR', 'a', 1);
    SELECT pg_temp.goods('adj2', 1, 1);
    SELECT pg_temp.agreement('adj2');
    SELECT pg_temp.submit('adj2');
    SELECT pg_temp.issue('adj2', 920012) $q$);
SELECT pg_temp.expect_true('D3 the invoice is still ISSUED, flagged, with two adjustments issued against it',
    pg_temp.status_of('a') = 'ISSUED+adjusted'
    AND (SELECT count(*) = 2 FROM invoicing.einvoice
          WHERE original_einvoice_id = md5('qa92:a')::uuid AND type = 'ADJUSTMENT' AND status = 'ISSUED'));
SELECT pg_temp.expect_refused('D4 the bound counts every adjustment standing: 10 - 2 + 1 = 9 cartons are left, not 10',
    'tg_einvoice_adjustment_bound', $q$
    SELECT pg_temp.correction('d4', 'ADJUSTMENT', 'ERROR', 'a', -10);
    SELECT pg_temp.goods('d4', 1, -10) $q$);
SELECT pg_temp.expect_ok('D5 a wrong tax code alone (E0 row 2): an adjustment that changes no money has a note line', $q$
    SELECT pg_temp.correction('adj3', 'ADJUSTMENT', 'ERROR', 'a', 0, NULL, 'sai ma so thue nguoi mua');
    SELECT pg_temp.note('adj3', 1);
    UPDATE invoicing.einvoice SET buyer_tax_code = '0398765499' WHERE id = md5('qa92:adj3')::uuid $q$);
SELECT pg_temp.expect_refused('D6 a note line on an original', 'tg_einvoice_line_note', $q$ SELECT pg_temp.note('d', 2) $q$);
SELECT pg_temp.expect_refused('D7 a note line that carries money', 'ck_einvoice_line_subject', $q$
    UPDATE invoicing.einvoice_line SET unit_price = 1000, amount = 1000 WHERE id = md5('qa92:adj3:1')::uuid $q$);
SELECT pg_temp.expect_ok('D8 a draft adjustment of invoice c is drawn up ...', $q$
    SELECT pg_temp.correction('c-adj', 'ADJUSTMENT', 'ERROR', 'c', -1);
    SELECT pg_temp.goods('c-adj', 1, -1) $q$);
SELECT pg_temp.expect_refused('D9 ... and while it stands, even as a draft, the invoice is not replaced',
    'tg_einvoice_correction_form', $q$ SELECT pg_temp.correction('d9', 'REPLACEMENT', 'ERROR', 'c', 10) $q$);
SELECT pg_temp.expect_ok('D10 the draft adjustment is given up (a draft is voided, with its reason) ...', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'chon thay the'
     WHERE id = md5('qa92:c-adj')::uuid $q$);
SELECT pg_temp.expect_true('D11 ... which chose nothing: the invoice was never flagged', pg_temp.status_of('c') = 'ISSUED');

\echo '--- E. E0 rows 2-3: ... or a REPLACEMENT; the original becomes REPLACED (khoản 1 điểm b.2; 21 §4.4 step 13)'
SELECT pg_temp.expect_refused('E1 a replacement for returned goods: a replacement only ever corrects an error',
    'ck_einvoice_correction_basis', $q$ SELECT pg_temp.correction('e1', 'REPLACEMENT', 'RETURN', 'c', 9, 'r1') $q$);
SELECT pg_temp.expect_refused('E2 a replacement written below zero: it is the invoice as it should have been',
    'ck_einvoice_replacement_amounts', $q$ SELECT pg_temp.correction('e2', 'REPLACEMENT', 'ERROR', 'c', -1) $q$);
SELECT pg_temp.expect_ok('E3 a draft replacement of invoice c, now that no adjustment of it stands: 9 cartons, not 10', $q$
    SELECT pg_temp.correction('rep', 'REPLACEMENT', 'ERROR', 'c', 9, NULL, 'sai so luong: 9 thung');
    SELECT pg_temp.goods('rep', 1, 9) $q$);
SELECT pg_temp.expect_refused('E4 a line below zero on a replacement', 'tg_einvoice_line_sign', $q$
    SELECT pg_temp.goods('rep', 2, -1) $q$);
SELECT pg_temp.expect_refused('E5 a note line on a replacement', 'tg_einvoice_line_note', $q$
    SELECT pg_temp.note('rep', 2) $q$);
SELECT pg_temp.expect_refused('E6 a second replacement of the same invoice (21 BR-08: replaced once)',
    'uk_einvoice_replacement', $q$ SELECT pg_temp.correction('e6', 'REPLACEMENT', 'ERROR', 'c', 8) $q$);
SELECT pg_temp.expect_refused('E7 the form is kept the other way too: an invoice being replaced is not adjusted',
    'tg_einvoice_correction_form', $q$ SELECT pg_temp.correction('e7', 'ADJUSTMENT', 'ERROR', 'c', -1) $q$);
SELECT pg_temp.expect_refused('E8 ... not for returned goods either: the replacement is what will stand for the shipment',
    'tg_einvoice_correction_form', $q$ SELECT pg_temp.correction('e8', 'ADJUSTMENT', 'RETURN', 'c', -1, 'r1') $q$);
SELECT pg_temp.expect_true('E9 a draft replacement replaces nothing yet: the original is ISSUED and in force',
    pg_temp.status_of('c') = 'ISSUED');
SELECT pg_temp.expect_refused('E10 a business buyer and no written agreement: the replacement does not leave for the provider',
    'ck_einvoice_agreement_before_sending', $q$ SELECT pg_temp.submit('rep') $q$);
SELECT pg_temp.expect_refused('E11 the original marked REPLACED while its replacement is only a draft',
    'tg_einvoice_replaced_by', $q$ UPDATE invoicing.einvoice SET status = 'REPLACED' WHERE id = md5('qa92:c')::uuid $q$);
SELECT pg_temp.expect_ok('E12 the agreement attached, DRAFT -> SUBMITTING', $q$
    SELECT pg_temp.agreement('rep');
    SELECT pg_temp.submit('rep') $q$);
SELECT pg_temp.expect_refused('E13 the replacement issued while the original still stands: two invoices in force for one shipment',
    'tg_einvoice_correction_marks_original', $q$ SELECT pg_temp.issue('rep', 920021) $q$);
SELECT pg_temp.expect_ok('E14 ISSUED -> REPLACED and the replacement issued, together', $q$
    UPDATE invoicing.einvoice SET status = 'REPLACED' WHERE id = md5('qa92:c')::uuid;
    SELECT pg_temp.issue('rep', 920021) $q$);
SELECT pg_temp.expect_true('E15 the original is REPLACED and keeps its number and files; the replacement is the one in force',
    pg_temp.status_of('c') = 'REPLACED' AND pg_temp.status_of('rep') = 'ISSUED'
    AND (SELECT invoice_number = 920003 AND xml_file_key IS NOT NULL AND pdf_file_key IS NOT NULL
           FROM invoicing.einvoice WHERE id = md5('qa92:c')::uuid)
    AND (SELECT count(*) = 1 FROM invoicing.einvoice
          WHERE shipment_id = md5('qa92:s3')::uuid AND type <> 'ADJUSTMENT' AND status = 'ISSUED'));
SELECT pg_temp.expect_refused('E16 REPLACED is final: not back to ISSUED', 'tg_einvoice_final', $q$
    UPDATE invoicing.einvoice SET status = 'ISSUED' WHERE id = md5('qa92:c')::uuid $q$);
SELECT pg_temp.expect_refused('E17 REPLACED is final: not voided', 'tg_einvoice_final', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'x'
     WHERE id = md5('qa92:c')::uuid $q$);
SELECT pg_temp.expect_refused('E18 a replaced invoice is never deleted', 'tg_einvoice_no_delete', $q$
    DELETE FROM invoicing.einvoice WHERE id = md5('qa92:c')::uuid $q$);
SELECT pg_temp.expect_refused('E19 a replaced invoice is not adjusted (21 BR-08)', 'tg_einvoice_original_in_force', $q$
    SELECT pg_temp.correction('e19', 'ADJUSTMENT', 'ERROR', 'c', -1) $q$);
SELECT pg_temp.expect_refused('E20 a replaced invoice is not replaced a second time', 'tg_einvoice_original_in_force', $q$
    SELECT pg_temp.correction('e20', 'REPLACEMENT', 'ERROR', 'c', 8) $q$);
SELECT pg_temp.expect_refused('E21 a second original for a shipment a replacement invoices',
    'tg_einvoice_original_per_shipment', $q$ SELECT pg_temp.original('e21', 1, 's3', 9) $q$);
SELECT pg_temp.expect_refused('E22 a wrong name on a REPLACED invoice is not notified: it is no longer in force',
    'tg_einvoice_error_notice_issued', $q$
    INSERT INTO invoicing.einvoice_error_notice (id, einvoice_id, reason, correct_buyer_name)
    VALUES (gen_random_uuid(), md5('qa92:c')::uuid, 'sai ten', 'CONG TY MUA HANG') $q$);
SELECT pg_temp.expect_refused('E23 khoản 6 điểm a: a later error of the invoice that replaced is replaced again, never adjusted',
    'tg_einvoice_correction_form', $q$ SELECT pg_temp.correction('e23', 'ADJUSTMENT', 'ERROR', 'rep', -1) $q$);
SELECT pg_temp.expect_ok('E24 ... replaced again: a draft replacement of the replacement, then given up', $q$
    SELECT pg_temp.correction('rep2', 'REPLACEMENT', 'ERROR', 'rep', 8);
    SELECT pg_temp.goods('rep2', 1, 8);
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'lap nham'
     WHERE id = md5('qa92:rep2')::uuid $q$);
SELECT pg_temp.expect_ok('E25 a voided draft replacement no longer stands in the way of the next', $q$
    SELECT pg_temp.correction('rep3', 'REPLACEMENT', 'ERROR', 'rep', 8);
    SELECT pg_temp.goods('rep3', 1, 8);
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'lap nham'
     WHERE id = md5('qa92:rep3')::uuid $q$);

\echo '--- F. E0 row 4: goods returned after the invoice -> the seller''s decrease adjustment, no agreement (khoản 5 điểm c.1)'
SELECT pg_temp.expect_refused('F1 an adjustment for a return that names no return', 'ck_einvoice_return', $q$
    SELECT pg_temp.correction('f1', 'ADJUSTMENT', 'RETURN', 'b', -3) $q$);
SELECT pg_temp.expect_refused('F2 a correction of an error that names a return', 'ck_einvoice_return', $q$
    SELECT pg_temp.correction('f2', 'ADJUSTMENT', 'ERROR', 'b', -3, 'r1') $q$);
SELECT pg_temp.expect_refused('F3 a return that does not exist', 'fk_einvoice_return_request', $q$
    SELECT pg_temp.correction('f3', 'ADJUSTMENT', 'RETURN', 'b', -3, 'nowhere') $q$);
SELECT pg_temp.expect_refused('F4 an adjustment for a return that increases', 'ck_einvoice_decrease', $q$
    SELECT pg_temp.correction('f4', 'ADJUSTMENT', 'RETURN', 'b', 3, 'r1') $q$);
SELECT pg_temp.expect_refused('F5 an adjustment for a return that changes no money', 'ck_einvoice_decrease', $q$
    SELECT pg_temp.correction('f5', 'ADJUSTMENT', 'RETURN', 'b', 0, 'r1') $q$);
SELECT pg_temp.expect_ok('F6 a draft decrease adjustment of invoice b for return r1: 3 cartons came back', $q$
    SELECT pg_temp.correction('ret', 'ADJUSTMENT', 'RETURN', 'b', -3, 'r1', 'khach tra 3 thung, RMA-QA92-r1');
    SELECT pg_temp.goods('ret', 1, -3) $q$);
SELECT pg_temp.expect_true('F7 it reduces: below zero in its lines and its totals (khoản 6 điểm c), and names the return',
    (SELECT subtotal = -300000 AND tax_total = -30000 AND grand_total = -330000
            AND return_request_id = md5('qa92:r1')::uuid AND agreement_file_key IS NULL
       FROM invoicing.einvoice WHERE id = md5('qa92:ret')::uuid)
    AND (SELECT quantity = -3 FROM invoicing.einvoice_line WHERE id = md5('qa92:ret:1')::uuid));
SELECT pg_temp.expect_refused('F8 a line above zero on an adjustment for a return', 'tg_einvoice_line_sign', $q$
    SELECT pg_temp.goods('ret', 2, 1) $q$);
SELECT pg_temp.expect_refused('F9 a note line on an adjustment for a return', 'tg_einvoice_line_sign', $q$
    SELECT pg_temp.note('ret', 2) $q$);
SELECT pg_temp.expect_refused('F10 an agreement on an adjustment for a return: only an error is agreed in writing',
    'ck_einvoice_agreement', $q$ SELECT pg_temp.agreement('ret') $q$);
SELECT pg_temp.expect_refused('F11 the same return adjusted twice on the same invoice', 'uk_einvoice_return_adjustment', $q$
    SELECT pg_temp.correction('f11', 'ADJUSTMENT', 'RETURN', 'b', -3, 'r1') $q$);
SELECT pg_temp.expect_refused('F12 the return it is for changes', 'tg_einvoice_identity', $q$
    UPDATE invoicing.einvoice SET return_request_id = md5('qa92:r2')::uuid WHERE id = md5('qa92:ret')::uuid $q$);
SELECT pg_temp.expect_ok('F13 it leaves for the provider WITHOUT an agreement, the invoice is flagged, it is issued', $q$
    SELECT pg_temp.submit('ret');
    UPDATE invoicing.einvoice SET adjusted = TRUE WHERE id = md5('qa92:b')::uuid;
    SELECT pg_temp.issue('ret', 920031) $q$);
SELECT pg_temp.expect_true('F14 the invoice of the returned goods stays ISSUED, flagged',
    pg_temp.status_of('b') = 'ISSUED+adjusted' AND pg_temp.status_of('ret') = 'ISSUED');
SELECT pg_temp.expect_ok('F15 another return of the order is adjusted on the same invoice: 7 cartons are left to take off', $q$
    SELECT pg_temp.correction('ret2', 'ADJUSTMENT', 'RETURN', 'b', -7, 'r2');
    SELECT pg_temp.goods('ret2', 1, -7) $q$);
SELECT pg_temp.expect_refused('F16 ... but no return takes off more than the invoice still holds',
    'tg_einvoice_adjustment_bound', $q$
    UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'x'
     WHERE id = md5('qa92:ret2')::uuid;
    SELECT pg_temp.correction('f16', 'ADJUSTMENT', 'RETURN', 'b', -8, 'r2');
    SELECT pg_temp.goods('f16', 1, -8) $q$);
SELECT pg_temp.expect_ok('F17 a return reaches a replacement too: the invoice in force for the shipment, by adjustment', $q$
    SELECT pg_temp.correction('ret3', 'ADJUSTMENT', 'RETURN', 'rep', -1, 'r1');
    SELECT pg_temp.goods('ret3', 1, -1) $q$);

\echo '--- G. E0 row 5: a trade discount by volume or revenue -> a decrease adjustment with its list of invoices (khoản 5 điểm b)'
SELECT pg_temp.expect_refused('G1 a trade discount that increases', 'ck_einvoice_decrease', $q$
    SELECT pg_temp.correction('g1', 'ADJUSTMENT', 'TRADE_DISCOUNT', 'x', 1) $q$);
SELECT pg_temp.expect_ok('G2 fixtures: a second issued invoice of the other buyer, and a draft trade-discount adjustment', $q$
    INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, package_count, ready_at, created_at)
    VALUES (md5('qa92:x2')::uuid, md5('qa92:order2')::uuid, 'READY_TO_DISPATCH', 'SO-QA-EC-X2', md5('demo:wh:HCM')::uuid,
            1, NOW(), NOW());
    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
    VALUES (md5('qa92:x2')::uuid, md5('qa92:order2')::uuid, 'HANDED_OVER', 1100000, NOW());
    SELECT pg_temp.issued_original('y', 2, 'x2', 10, 920005);
    SELECT pg_temp.correction('disc', 'ADJUSTMENT', 'TRADE_DISCOUNT', 'x', -15, NULL, 'chiet khau doanh so quy 4');
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, item_name, unit, quantity, unit_price,
                                         tax_rate, amount, tax_amount)
    VALUES (md5('qa92:disc:1')::uuid, md5('qa92:disc')::uuid, 1, 'FEE', 'SHIPPING_FEE', 'Chiet khau thuong mai', 'Lan',
            -1, 1500000, 10, -1500000, -150000);
    INSERT INTO invoicing.einvoice_adjustment_listing (einvoice_id, listed_einvoice_id)
    VALUES (md5('qa92:disc')::uuid, md5('qa92:y')::uuid) $q$);
SELECT pg_temp.expect_refused('G3 the same discount without its list: more than the one invoice it names holds',
    'tg_einvoice_adjustment_bound', $q$
    SELECT pg_temp.correction('g3', 'ADJUSTMENT', 'TRADE_DISCOUNT', 'y', -15);
    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, item_name, unit, quantity, unit_price,
                                         tax_rate, amount, tax_amount)
    VALUES (gen_random_uuid(), md5('qa92:g3')::uuid, 1, 'FEE', 'SHIPPING_FEE', 'Chiet khau thuong mai', 'Lan', -1,
            1500000, 10, -1500000, -150000) $q$);
SELECT pg_temp.expect_refused('G4 the list names the invoice the adjustment already names',
    'tg_einvoice_adjustment_listing_in_force', $q$
    INSERT INTO invoicing.einvoice_adjustment_listing (einvoice_id, listed_einvoice_id)
    VALUES (md5('qa92:disc')::uuid, md5('qa92:x')::uuid) $q$);
SELECT pg_temp.expect_refused('G5 the list names another buyer''s invoice', 'tg_einvoice_adjustment_listing_in_force', $q$
    INSERT INTO invoicing.einvoice_adjustment_listing (einvoice_id, listed_einvoice_id)
    VALUES (md5('qa92:disc')::uuid, md5('qa92:a')::uuid) $q$);
-- (The trigger speaks before the CHECK does: an adjustment is not an invoice a list may name.)
SELECT pg_temp.expect_refused('G6 the list names itself', 'tg_einvoice_adjustment_listing_in_force', $q$
    INSERT INTO invoicing.einvoice_adjustment_listing (einvoice_id, listed_einvoice_id)
    VALUES (md5('qa92:disc')::uuid, md5('qa92:disc')::uuid) $q$);
SELECT pg_temp.expect_refused('G7 a list on an adjustment for a return', 'tg_einvoice_adjustment_listing_draft', $q$
    INSERT INTO invoicing.einvoice_adjustment_listing (einvoice_id, listed_einvoice_id)
    VALUES (md5('qa92:ret3')::uuid, md5('qa92:a')::uuid) $q$);
SELECT pg_temp.expect_refused('G8 a listed invoice is swapped for another', 'tg_einvoice_adjustment_listing_draft', $q$
    UPDATE invoicing.einvoice_adjustment_listing SET listed_einvoice_id = md5('qa92:a')::uuid
     WHERE einvoice_id = md5('qa92:disc')::uuid $q$);
SELECT pg_temp.expect_refused('G9 an agreement on a trade discount: only an error is agreed in writing',
    'ck_einvoice_agreement', $q$ SELECT pg_temp.agreement('disc') $q$);
SELECT pg_temp.expect_ok('G10 it leaves without an agreement; every invoice it covers is flagged; it is issued', $q$
    SELECT pg_temp.submit('disc');
    UPDATE invoicing.einvoice SET adjusted = TRUE WHERE id IN (md5('qa92:x')::uuid, md5('qa92:y')::uuid);
    SELECT pg_temp.issue('disc', 920041) $q$);
SELECT pg_temp.expect_refused('G11 the list of an issued adjustment is never taken apart',
    'tg_einvoice_adjustment_listing_draft', $q$
    DELETE FROM invoicing.einvoice_adjustment_listing WHERE einvoice_id = md5('qa92:disc')::uuid $q$);
SELECT pg_temp.expect_refused('G12 a listed invoice was adjusted too: it is not replaced afterwards',
    'tg_einvoice_correction_form', $q$ SELECT pg_temp.correction('g12', 'REPLACEMENT', 'ERROR', 'y', 10) $q$);

\echo '--- H. the provider''s log and the sandbox''s books'
SELECT pg_temp.expect_ok('H1 sending a notice is logged as its own operation; a provider that has none answers NOT_SUPPORTED', $q$
    INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
        request_hash, called_at, duration_ms)
    VALUES (gen_random_uuid(), md5('qa92:a')::uuid, 'SANDBOX', 'tx-n1', 'NOTICE', 'ACCEPTED', repeat('a', 64), NOW(), 5),
           (gen_random_uuid(), md5('qa92:a')::uuid, 'SANDBOX', 'tx-n2', 'NOTICE', 'NOT_SUPPORTED', repeat('b', 64), NOW(), 5) $q$);
SELECT pg_temp.expect_refused('H2 no operation "CANCEL" is ever logged: there is none to make',
    'ck_einvoice_provider_call_operation', $q$
    INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
        request_hash, called_at, duration_ms)
    VALUES (gen_random_uuid(), md5('qa92:a')::uuid, 'SANDBOX', 'tx-c', 'CANCEL', 'ACCEPTED', repeat('a', 64), NOW(), 5) $q$);
SELECT pg_temp.expect_refused('H3 the sandbox issues an adjustment only against the number of an invoice',
    'ck_sandbox_issuance_original', $q$
    INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, invoice_number,
        tax_authority_code, lookup_code, xml_document, pdf_document, request_hash, received_at, ready_at, kind)
    VALUES ('qa92-adj', 'ISSUED', '1', 'C26TSB', 920901, 'c', 'l', '<x/>', '\x25504446', repeat('a', 64), NOW(), NOW(),
            'ADJUSTMENT') $q$);
SELECT pg_temp.expect_refused('H4 ... and an original against none', 'ck_sandbox_issuance_original', $q$
    INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, invoice_number,
        tax_authority_code, lookup_code, xml_document, pdf_document, request_hash, received_at, ready_at, kind,
        original_template_code, original_symbol, original_invoice_number)
    VALUES ('qa92-org', 'ISSUED', '1', 'C26TSB', 920902, 'c', 'l', '<x/>', '\x25504446', repeat('a', 64), NOW(), NOW(),
            'ORIGINAL', '1', 'C26TSB', 920001) $q$);
SELECT pg_temp.expect_refused('H5 the sandbox knows no "CANCELLATION"', 'ck_sandbox_issuance_kind', $q$
    INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, invoice_number,
        tax_authority_code, lookup_code, xml_document, pdf_document, request_hash, received_at, ready_at, kind,
        original_template_code, original_symbol, original_invoice_number)
    VALUES ('qa92-can', 'ISSUED', '1', 'C26TSB', 920903, 'c', 'l', '<x/>', '\x25504446', repeat('a', 64), NOW(), NOW(),
            'CANCELLATION', '1', 'C26TSB', 920001) $q$);

\echo '--- I. where it all stands: nothing was cancelled, deleted or rewritten on the way'
SELECT pg_temp.expect_true('I1 every invoice that was issued still is, or is REPLACED by one that is',
    (SELECT count(*) = 0 FROM invoicing.einvoice e
      WHERE e.code LIKE 'EINV-QA92-%' AND e.invoice_number IS NOT NULL AND e.status NOT IN ('ISSUED', 'REPLACED'))
    AND (SELECT count(*) = 1 FROM invoicing.einvoice e WHERE e.code LIKE 'EINV-QA92-%' AND e.status = 'REPLACED')
    AND (SELECT count(*) = 1 FROM invoicing.einvoice r
          WHERE r.type = 'REPLACEMENT' AND r.status = 'ISSUED' AND r.original_einvoice_id = md5('qa92:c')::uuid));
SELECT pg_temp.expect_true('I2 one invoice in force per shipment: the original, or what replaced it',
    (SELECT bool_and(n = 1) FROM (
        SELECT count(*) AS n FROM invoicing.einvoice e
         WHERE e.code LIKE 'EINV-QA92-%' AND e.type <> 'ADJUSTMENT' AND e.status = 'ISSUED' GROUP BY e.shipment_id) s));
SELECT pg_temp.expect_true('I3 an invoice is flagged adjusted exactly when an issued adjustment names or lists it',
    (SELECT bool_and(e.adjusted = (EXISTS (
                SELECT 1 FROM invoicing.einvoice a
                 WHERE a.type = 'ADJUSTMENT' AND a.status = 'ISSUED'
                   AND (a.original_einvoice_id = e.id OR EXISTS (
                        SELECT 1 FROM invoicing.einvoice_adjustment_listing l
                         WHERE l.einvoice_id = a.id AND l.listed_einvoice_id = e.id)))))
       FROM invoicing.einvoice e WHERE e.code LIKE 'EINV-QA92-%' AND e.type <> 'ADJUSTMENT' AND e.status = 'ISSUED'));

ROLLBACK;
