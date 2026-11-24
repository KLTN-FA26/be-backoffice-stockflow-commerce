-- =============================================================================
-- Demo WF-05, second round (seed gap G-8, and the money of G-9): what came after each delivery of V20261224900200 -
-- its e-invoice, in the state a scene opens it in, and the payments after delivery. Demo only (db/demo-wf05).
--
-- Every invoice is written the way the application writes it: drawn up as a DRAFT when its shipment was delivered
-- (EInvoiceDrafts.drawUp: code EINV-<delivery day>-nnnn from platform.document_sequence, dated the delivery day, due by
-- its end, the goods in cartons at the delivery day's rate of the product's tax class, the shipping fee shared by
-- value, the buyer from the customer's legal profile), then moved DRAFT → SUBMITTING → ISSUED / REJECTED → DRAFT with
-- the provider's books and the call log beside it. Issued ones are issued by the built-in SANDBOX provider (its books:
-- invoicing.sandbox_issuance, sandbox_number), so a correction of them is accepted by the provider that issued them and
-- the reconciliation never asks SePay about a transaction SePay never saw (seed-gaps G-8). Their XML / PDF keys name
-- no stored object: the scenes open these invoices on screen, they do not download them.
--
--   WF5-C    DRAFT, TAX_RATE_DIFFERS_FROM_CONTRACT - held back from the automatic issuing; step 8.
--   WF5-D    sent yesterday, refused by the (simulated) tax authority, reopened: a DRAFT of yesterday the automatic
--            issuing no longer takes (attempt 1). Issued today through SePay it is dated today and "Lập trễ 1 ngày"
--            (step 9); next to WF5-C it shows an older draft does not block a newer one (HĐ-2).
--   WF5-HD1B SUBMITTING to SANDBOX, which keeps answering PENDING (its processing ends in 2099): "Kiểm tra lại", no
--            "Rút" (HĐ-1 step 2).
--   WF5-HD1C SUBMITTING to SANDBOX at 01:05, the send timed out and SANDBOX has no record: "Rút" with a reason from
--            04:05 (withdraw-after), then watched 30 days (HĐ-1 step 3). Both are dated today, so neither keeps an
--            invoice of today from being sent (EINVOICE_OLDER_DRAFT_PENDING looks for earlier dates only).
--   WF5-HD3R ISSUED, then REPLACED two days ago by a replacement (error in the buyer's address, agreement attached):
--            "Điều chỉnh" on the replacement is refused (HĐ-3 step 5).
--   WF5-HD5  ISSUED by the old provider, Viettel SInvoice (no longer in use, no account): a delivery corrected to a
--            failure now draws up a reduction no provider can send (HĐ-5).
--   WF5-HD7  DRAFT at 10%, TAX_RATE_DIFFERS_FROM_CONTRACT and TAX_CLASS_UNKNOWN (HĐ-7).
--   WF5-E, WF5-E2, WF5-TT2, WF5-TT5 (batch 1), WF5-TH1 (batch 1), WF5-DD1A (batch 1), WF5-DD1B (both): ISSUED.
--   WF5-DD1A batch 2: refused and reopened like WF5-D - a delivery not invoiced (DĐ-1).
-- And the money: WF5-E, WF5-E2, WF5-DD1A/B, and WF5-TH1 for its first delivery, pay what they owe after delivery; WF5-TT2's falls due yesterday unpaid and
-- not announced (TT-2: the overdue scan announces it); WF5-E2 completed 31 days ago with its contract (step 11: the
-- nightly run closes it; WF5-E it completes).
-- =============================================================================

CREATE FUNCTION pg_temp.wid(key TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT md5('demo:wf05:' || key)::uuid $$;

CREATE FUNCTION pg_temp.today() RETURNS DATE LANGUAGE sql STABLE AS
$$ SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date $$;

CREATE FUNCTION pg_temp.at(days_ago INTEGER, hhmi TEXT) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT ((pg_temp.today() - days_ago) + hhmi::time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

CREATE FUNCTION pg_temp.ymd(days_ago INTEGER) RETURNS TEXT LANGUAGE sql STABLE AS
$$ SELECT to_char(pg_temp.today() - days_ago, 'YYYYMMDD') $$;

CREATE FUNCTION pg_temp.staff(username TEXT) RETURNS UUID LANGUAGE sql STABLE AS
$$ SELECT id FROM identity.app_user WHERE username = $1 $$;

CREATE FUNCTION pg_temp.vn(t TIMESTAMPTZ) RETURNS DATE LANGUAGE sql IMMUTABLE AS
$$ SELECT (t AT TIME ZONE 'Asia/Ho_Chi_Minh')::date $$;

CREATE FUNCTION pg_temp.hex(t TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT encode(sha256(convert_to(t, 'UTF8')), 'hex') $$;

-- VietnameseAmountInWords.of, read the same way: groups of three, "không trăm" and "lẻ" inside a number that goes on,
-- "mốt" / "lăm", a capital first letter, "đồng" after it, "Âm" before a negative amount.
CREATE FUNCTION pg_temp.three(v INTEGER, full_read BOOLEAN) RETURNS TEXT LANGUAGE plpgsql IMMUTABLE AS $fn$
DECLARE
    digits TEXT[] := ARRAY['không', 'một', 'hai', 'ba', 'bốn', 'năm', 'sáu', 'bảy', 'tám', 'chín'];
    h INTEGER := v / 100;
    t INTEGER := v / 10 % 10;
    o INTEGER := v % 10;
    w TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF h > 0 OR full_read THEN
        w := w || digits[h + 1] || 'trăm'::TEXT;
    END IF;
    IF t = 0 THEN
        IF o > 0 THEN
            IF h > 0 OR full_read THEN
                w := w || 'lẻ'::TEXT;
            END IF;
            w := w || digits[o + 1];
        END IF;
        RETURN array_to_string(w, ' ');
    END IF;
    w := w || CASE WHEN t = 1 THEN 'mười' ELSE digits[t + 1] || ' mươi' END;
    IF o = 1 AND t > 1 THEN
        w := w || 'mốt'::TEXT;
    ELSIF o = 5 THEN
        w := w || 'lăm'::TEXT;
    ELSIF o > 0 THEN
        w := w || digits[o + 1];
    END IF;
    RETURN array_to_string(w, ' ');
END
$fn$;

CREATE FUNCTION pg_temp.words(amount NUMERIC) RETURNS TEXT LANGUAGE plpgsql IMMUTABLE AS $fn$
DECLARE
    n BIGINT := abs(amount)::BIGINT;
    groups TEXT[] := ARRAY['', 'nghìn', 'triệu'];
    w TEXT[] := ARRAY[]::TEXT[];
    started BOOLEAN := FALSE;
    billions BIGINT := n / 1000000000;
    rest BIGINT := n % 1000000000;
    g INTEGER;
    part INTEGER;
    result TEXT;
BEGIN
    IF n = 0 THEN
        result := 'không';
    ELSE
        IF billions > 0 THEN
            w := w || pg_temp.three(billions::INTEGER, FALSE) || 'tỷ'::TEXT;
            started := TRUE;
        END IF;
        FOR g IN REVERSE 2..0 LOOP
            part := (rest / (1000::BIGINT ^ g)::BIGINT % 1000)::INTEGER;
            CONTINUE WHEN part = 0;
            w := w || pg_temp.three(part, started);
            IF groups[g + 1] <> '' THEN
                w := w || groups[g + 1];
            END IF;
            started := TRUE;
        END LOOP;
        result := array_to_string(w, ' ');
    END IF;
    IF amount < 0 THEN
        result := 'âm ' || result;
    END IF;
    RETURN upper(left(result, 1)) || substr(result, 2) || ' đồng';
END
$fn$;

-- The rate of a tax class on a day as the invoice takes it (DeliveredShipmentsService.rateOf): the period's, else the
-- class's; no class - the highest, 10% (WF-05 Q-21).
CREATE FUNCTION pg_temp.rate_on(tax_class TEXT, day DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN tax_class IS NULL THEN 10.00
                ELSE coalesce((SELECT p.rate FROM ordering.tax_rate_period p
                                WHERE p.tax_class = rate_on.tax_class AND day BETWEEN p.valid_from AND p.valid_to
                                ORDER BY p.valid_from DESC LIMIT 1),
                              (SELECT r.rate FROM ordering.tax_rate r WHERE r.tax_class = rate_on.tax_class)) END
$$;

-- The next code of a day (EInvoiceRepositoryAdapter.nextCode): EINV-yyyyMMdd-nnnn from platform.document_sequence.
CREATE FUNCTION pg_temp.next_code(day DATE) RETURNS TEXT LANGUAGE sql AS $$
    INSERT INTO platform.document_sequence (document_type, sequence_date, last_value) VALUES ('EINV', day, 1)
    ON CONFLICT (document_type, sequence_date) DO UPDATE SET last_value = platform.document_sequence.last_value + 1
    RETURNING 'EINV-' || to_char(day, 'YYYYMMDD') || '-' || lpad(last_value::text, 4, '0')
$$;

-- ---------------------------------------------------------------------------------------------
-- The original of a delivered shipment, drawn up as a DRAFT the minute after its delivery (EInvoiceDrafts.drawUp)
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION pg_temp.draw_up(okey TEXT, bseq INTEGER) RETURNS UUID LANGUAGE plpgsql AS $fn$
DECLARE
    inv UUID := pg_temp.wid('einvoice:' || okey || ':' || bseq);
    sh RECORD;
    o RECORD;
    l RECORD;
    day DATE;
    goods_rate NUMERIC;
    fee_rate NUMERIC;
    cartons INTEGER;
    cum INTEGER;
    total_cartons INTEGER;
    ship NUMERIC;
    ship_share NUMERIC;
    contract_rate NUMERIC;
    ship_contract_rate NUMERIC;
    plate NUMERIC;
    plate_contract_rate NUMERIC;
    n INTEGER := 1;
    product_class TEXT;
    product_name TEXT;
    w TEXT[] := ARRAY[]::TEXT[];
    differs BOOLEAN;
BEGIN
    SELECT s.* INTO sh FROM ordering.order_shipment s WHERE s.id = pg_temp.wid('shipment:' || okey || '-' || bseq);
    SELECT co.*, k.contract_number, c.full_name AS cname, c.tax_code AS ctax, lp.registered_address, lp.invoice_email
      INTO o
      FROM ordering.customer_order co
      JOIN customer.customer c ON c.id = co.customer_id
      LEFT JOIN customer.customer_legal_profile lp ON lp.customer_id = c.id
      LEFT JOIN ordering.sales_contract k ON k.order_id = co.id AND k.status <> 'CANCELLED'
     WHERE co.id = sh.order_id;
    SELECT ol.* INTO l FROM ordering.order_line ol WHERE ol.order_id = o.id;
    SELECT p.tax_class, p.name INTO product_class, product_name
      FROM product.variants v JOIN product.products p ON p.id = v.product_id WHERE upper(v.sku) = upper(l.sku);
    day := pg_temp.vn(sh.outcome_at);
    goods_rate := pg_temp.rate_on(product_class, day);
    fee_rate := pg_temp.rate_on('STANDARD', day);
    SELECT bl.cartons INTO cartons FROM ordering.delivery_batch_line bl WHERE bl.delivery_batch_id = sh.delivery_batch_id;
    SELECT sum(bl.cartons) FILTER (WHERE b.seq <= bseq), sum(bl.cartons) INTO cum, total_cartons
      FROM ordering.delivery_batch b JOIN ordering.delivery_batch_line bl ON bl.delivery_batch_id = b.id
     WHERE b.contract_id = (SELECT k.id FROM ordering.sales_contract k WHERE k.order_id = o.id AND k.status <> 'CANCELLED');
    SELECT f.amount, f.tax_rate INTO ship, ship_contract_rate
      FROM ordering.order_fee_line f WHERE f.order_id = o.id AND f.kind = 'SHIPPING_FEE';
    -- The plate fee, whole, on the order's first invoice (FeeAllocation): the first delivery's here.
    SELECT f.amount, f.tax_rate INTO plate, plate_contract_rate
      FROM ordering.order_fee_line f WHERE f.order_id = o.id AND f.kind = 'PLATE_FEE' AND bseq = 1;
    ship_share := round(coalesce(ship, 0) * cum / total_cartons, 0) - round(coalesce(ship, 0) * (cum - cartons) / total_cartons, 0);
    contract_rate := l.tax_rate;
    differs := contract_rate IS DISTINCT FROM goods_rate OR (ship_share > 0 AND ship_contract_rate IS DISTINCT FROM fee_rate)
               OR (plate > 0 AND plate_contract_rate IS DISTINCT FROM fee_rate);
    IF o.invoice_email IS NULL THEN
        w := w || 'BUYER_EMAIL_MISSING'::TEXT;
    END IF;
    IF differs THEN
        w := w || 'TAX_RATE_DIFFERS_FROM_CONTRACT'::TEXT;
    END IF;
    IF product_class IS NULL THEN
        w := w || 'TAX_CLASS_UNKNOWN'::TEXT;
    END IF;

    INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, contract_number, shipment_id,
                                    shipment_code, delivery_batch_id, customer_id, issue_date, issue_due_at,
                                    seller_name, seller_tax_code, seller_address, buyer_name, buyer_tax_code,
                                    buyer_address, buyer_email, currency, payment_method, subtotal, tax_total,
                                    grand_total, amount_in_words, warnings, delivered_on, version, created_at,
                                    created_by)
    VALUES (inv, pg_temp.next_code(day), 'ORIGINAL', 'DRAFT', o.id, o.order_number, o.contract_number, sh.id,
            sh.shipment_code, sh.delivery_batch_id, o.customer_id, day,
            ((day + 1)::timestamp) AT TIME ZONE 'Asia/Ho_Chi_Minh',
            'CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT', '0312345678',
            'Số 268 Đường số 7, Phường Tân Tạo, Thành phố Hồ Chí Minh', o.cname, o.ctax, o.registered_address,
            o.invoice_email, 'VND', 'BANK_TRANSFER', 0, 0, 0, 'Không đồng',
            CASE WHEN cardinality(w) > 0 THEN array_to_string(w, ',') END, day, 0, sh.outcome_at + INTERVAL '2 minutes',
            'system');

    INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name, unit,
                                         quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
    VALUES (pg_temp.wid('einvoice-line:' || okey || ':' || bseq || ':1'), inv, 1, 'GOODS', NULL, l.id, l.sku,
            product_name, 'Thùng', cartons, l.units_per_carton, l.unit_price * l.units_per_carton, goods_rate,
            cartons * l.unit_price * l.units_per_carton,
            round(cartons * l.unit_price * l.units_per_carton * goods_rate / 100, 0));
    IF plate > 0 THEN
        n := n + 1;
        INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name,
                                             unit, quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
        VALUES (pg_temp.wid('einvoice-line:' || okey || ':' || bseq || ':' || n), inv, n, 'FEE', 'PLATE_FEE', NULL,
                l.sku, 'Phí khuôn / bản in (' || l.sku || ')', 'Lần', 1, NULL, plate, fee_rate, plate,
                round(plate * fee_rate / 100, 0));
    END IF;
    IF ship_share > 0 THEN
        n := n + 1;
        INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name,
                                             unit, quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
        VALUES (pg_temp.wid('einvoice-line:' || okey || ':' || bseq || ':' || n), inv, n, 'FEE', 'SHIPPING_FEE', NULL,
                NULL, 'Phí vận chuyển (lần giao ' || sh.shipment_code || ')', 'Lần', 1, NULL, ship_share, fee_rate,
                ship_share, round(ship_share * fee_rate / 100, 0));
    END IF;

    UPDATE invoicing.einvoice e
       SET subtotal = t.amount, tax_total = t.tax, grand_total = t.amount + t.tax,
           amount_in_words = pg_temp.words(t.amount + t.tax)
      FROM (SELECT sum(amount) AS amount, sum(tax_amount) AS tax FROM invoicing.einvoice_line
             WHERE einvoice_id = inv) t
     WHERE e.id = inv;
    RETURN inv;
END
$fn$;

-- One call to the provider, in the append-only log (ProviderCallRecorder).
CREATE FUNCTION pg_temp.call(inv UUID, provider TEXT, op TEXT, outcome TEXT, at TIMESTAMPTZ, n INTEGER,
                             error_code TEXT DEFAULT NULL) RETURNS VOID LANGUAGE sql AS $$
    INSERT INTO invoicing.einvoice_provider_call (id, einvoice_id, provider, transaction_id, operation, outcome,
                                                  error_code, request_hash, response_hash, called_at, duration_ms)
    SELECT pg_temp.wid('einvoice-call:' || inv || ':' || n), inv, provider, e.transaction_id, op, outcome, error_code,
           CASE WHEN op = 'SUBMIT' THEN pg_temp.hex('request:' || inv) END,
           CASE WHEN outcome NOT IN ('TIMEOUT') THEN pg_temp.hex('response:' || inv || ':' || n) END, at,
           CASE op WHEN 'DOWNLOAD' THEN 420 ELSE 180 END
      FROM invoicing.einvoice e WHERE e.id = inv
$$;

-- DRAFT → SUBMITTING (EInvoice.submit): the next attempt, a transaction of its own, the series of the provider.
CREATE FUNCTION pg_temp.submit(inv UUID, provider TEXT, symbol TEXT, at TIMESTAMPTZ, by_user UUID) RETURNS VOID
LANGUAGE sql AS $$
    UPDATE invoicing.einvoice
       SET status = 'SUBMITTING', provider = submit.provider, attempt = attempt + 1,
           transaction_id = id::text || '-' || (attempt + 1), template_code = '1', symbol = submit.symbol,
           submitted_at = at, submitted_by = by_user, rejection_code = NULL, rejection_message = NULL,
           version = version + 1, last_modified_at = at, last_modified_by = coalesce(by_user::text, 'system')
     WHERE id = inv
$$;

-- An original issued by SANDBOX (SandboxProvider.takeIn, then the reconciliation: SUBMITTING → ISSUED): sent by the
-- accountant at `at`, numbered in C26TSB, its documents in the provider's books.
CREATE FUNCTION pg_temp.issue_by_sandbox(inv UUID, at TIMESTAMPTZ, kind TEXT DEFAULT 'ORIGINAL') RETURNS INTEGER
LANGUAGE plpgsql AS $fn$
DECLARE
    tx TEXT;
    num INTEGER;
    digest TEXT;
    orig RECORD;
BEGIN
    PERFORM pg_temp.submit(inv, 'SANDBOX', 'C26TSB', at, pg_temp.staff('demo.ketoan'));
    SELECT transaction_id INTO tx FROM invoicing.einvoice WHERE id = inv;
    INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at, holder, held_until)
    VALUES (inv, tx, at, NULL, at + INTERVAL '40 seconds');
    INSERT INTO invoicing.sandbox_number (template_code, symbol, last_number) VALUES ('1', 'C26TSB', 1)
    ON CONFLICT (template_code, symbol) DO UPDATE SET last_number = invoicing.sandbox_number.last_number + 1
    RETURNING last_number INTO num;
    digest := upper(pg_temp.hex(tx));
    SELECT o.template_code, o.symbol, o.invoice_number INTO orig
      FROM invoicing.einvoice e JOIN invoicing.einvoice o ON o.id = e.original_einvoice_id WHERE e.id = inv;
    INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, invoice_number,
                                            tax_authority_code, lookup_code, xml_document, pdf_document, request_hash,
                                            received_at, ready_at, kind, original_template_code, original_symbol,
                                            original_invoice_number)
    VALUES (tx, 'ISSUED', '1', 'C26TSB', num, 'SBX' || substr(digest, 1, 31), 'SBX' || substr(digest, 32, 10),
            '<!-- SANDBOX E-INVOICE - TEST DOCUMENT - NO LEGAL VALUE (demo seed) -->',
            convert_to('SANDBOX E-INVOICE - TEST DOCUMENT - NO LEGAL VALUE (demo seed)', 'UTF8'),
            pg_temp.hex('request:' || inv), at, at + INTERVAL '5 seconds', kind, orig.template_code, orig.symbol,
            orig.invoice_number);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'QUERY', 'NOT_FOUND', at, 1);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'SUBMIT', 'ACCEPTED', at + INTERVAL '1 second', 2);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'QUERY', 'PENDING', at + INTERVAL '2 seconds', 3);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'QUERY', 'ISSUED', at + INTERVAL '30 seconds', 4);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'DOWNLOAD', 'DOWNLOADED', at + INTERVAL '31 seconds', 5);
    UPDATE invoicing.einvoice
       SET status = 'ISSUED', invoice_number = num, tax_authority_code = 'SBX' || substr(digest, 1, 31),
           lookup_code = 'SBX' || substr(digest, 32, 10), signed_at = at + INTERVAL '5 seconds',
           issued_at = at + INTERVAL '32 seconds', issued_via = 'PROVIDER',
           warnings = CASE WHEN coalesce(warnings, '') LIKE '%ISSUED_LATE%' THEN 'ISSUED_LATE' END,
           xml_file_key = 'einvoices/' || to_char(at AT TIME ZONE 'UTC', 'YYYY/MM/DD') || '/' || pg_temp.wid('xml:' || inv) || '.xml',
           pdf_file_key = 'einvoices/' || to_char(at AT TIME ZONE 'UTC', 'YYYY/MM/DD') || '/' || pg_temp.wid('pdf:' || inv) || '.pdf',
           version = version + 1, last_modified_at = at + INTERVAL '32 seconds', last_modified_by = 'system'
     WHERE id = inv;
    UPDATE invoicing.einvoice_sending SET held_until = at + INTERVAL '32 seconds' WHERE einvoice_id = inv;
    RETURN num;
END
$fn$;

-- Sent and refused by the simulated tax authority (SandboxProvider "+sandbox-authority-rejects"), then reopened by the
-- accountant (REJECTED → DRAFT): a draft again, attempt 1 - the automatic issuing takes only drafts never sent.
CREATE FUNCTION pg_temp.refuse_and_reopen(inv UUID, at TIMESTAMPTZ) RETURNS VOID LANGUAGE plpgsql AS $fn$
DECLARE
    tx TEXT;
BEGIN
    PERFORM pg_temp.submit(inv, 'SANDBOX', 'C26TSB', at, pg_temp.staff('demo.ketoan'));
    SELECT transaction_id INTO tx FROM invoicing.einvoice WHERE id = inv;
    INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at, holder, held_until)
    VALUES (inv, tx, at, NULL, at + INTERVAL '40 seconds');
    INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, rejection_code,
                                            rejection_message, request_hash, received_at, ready_at, kind)
    VALUES (tx, 'REJECTED', '1', 'C26TSB', 'TAX_AUTHORITY_REJECTED',
            'The tax authority did not give the invoice a code (rehearsed)', pg_temp.hex('request:' || inv), at,
            at + INTERVAL '5 seconds', 'ORIGINAL');
    PERFORM pg_temp.call(inv, 'SANDBOX', 'QUERY', 'NOT_FOUND', at, 1);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'SUBMIT', 'ACCEPTED', at + INTERVAL '1 second', 2);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'QUERY', 'REJECTED', at + INTERVAL '30 seconds', 3, 'TAX_AUTHORITY_REJECTED');
    UPDATE invoicing.einvoice
       SET status = 'REJECTED', rejection_code = 'TAX_AUTHORITY_REJECTED',
           rejection_message = 'The tax authority did not give the invoice a code (rehearsed)', version = version + 1,
           last_modified_at = at + INTERVAL '30 seconds', last_modified_by = 'system'
     WHERE id = inv;
    UPDATE invoicing.einvoice
       SET status = 'DRAFT', version = version + 1, last_modified_at = at + INTERVAL '40 minutes',
           last_modified_by = pg_temp.staff('demo.ketoan')::text
     WHERE id = inv;
END
$fn$;

-- A payment after delivery recorded by the accountant (15 §4.2): the receipt, its line, the installment PAID.
CREATE FUNCTION pg_temp.pay(okey TEXT, n INTEGER, at TIMESTAMPTZ) RETURNS VOID LANGUAGE sql AS $$
    INSERT INTO payment.payment_receipt (id, customer_id, reference, received_on, amount, currency, note, status,
                                         recorded_by, recorded_at, version, created_at, created_by)
    SELECT pg_temp.wid('receipt:' || okey || ':' || n), i.customer_id,
           'FT' || to_char(pg_temp.vn(at), 'YYYYMMDD') || upper(substr(pg_temp.hex(okey || ':' || n), 1, 8)),
           pg_temp.vn(at), i.amount, 'VND', 'Thanh toán ' || i.name || ' hợp đồng ' || i.contract_number, 'RECORDED',
           pg_temp.staff('demo.ketoan'), at, 0, at, pg_temp.staff('demo.ketoan')::text
      FROM payment.installment i WHERE i.id = pg_temp.wid('installment:' || okey || ':' || n);
    INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount, created_at)
    SELECT pg_temp.wid('receipt-line:' || okey || ':' || n), pg_temp.wid('receipt:' || okey || ':' || n), i.id,
           i.amount, at
      FROM payment.installment i WHERE i.id = pg_temp.wid('installment:' || okey || ':' || n);
    UPDATE payment.installment
       SET paid_amount = amount, status = 'PAID', paid_at = at, version = version + 1, last_modified_at = at,
           last_modified_by = pg_temp.staff('demo.ketoan')::text
     WHERE id = pg_temp.wid('installment:' || okey || ':' || n);
$$;

CREATE FUNCTION pg_temp.delivered_at(okey TEXT, bseq INTEGER) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT outcome_at FROM ordering.order_shipment WHERE id = pg_temp.wid('shipment:' || okey || '-' || bseq) $$;

-- ---------------------------------------------------------------------------------------------
-- 1. The originals, drawn up as each shipment was delivered - in the order of the deliveries, as the codes come
-- ---------------------------------------------------------------------------------------------
SELECT pg_temp.draw_up(x.okey, x.bseq)
  FROM (VALUES ('WF5-DD1A', 1), ('WF5-DD1B', 1), ('WF5-DD1A', 2), ('WF5-DD1B', 2), ('WF5-E2', 1), ('WF5-TT2', 1),
               ('WF5-HD3R', 1), ('WF5-E', 1), ('WF5-TT5', 1), ('WF5-HD5', 1), ('WF5-D', 1), ('WF5-HD7', 1),
               ('WF5-TH1', 1), ('WF5-C', 1), ('WF5-HD1B', 1), ('WF5-HD1C', 1)) AS x (okey, bseq)
 ORDER BY pg_temp.delivered_at(x.okey, x.bseq), x.okey;

-- (the application commits here: the deferred checks - totals against lines - run on what this step leaves)
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 2. Issued by SANDBOX: the accountant issued each the evening of its delivery (the sandbox provider issues nothing
--    on its own, auto_issue FALSE), in the order of the days - numbers of a series run with the dates
-- ---------------------------------------------------------------------------------------------
SELECT pg_temp.issue_by_sandbox(pg_temp.wid('einvoice:' || x.okey || ':' || x.bseq),
                                pg_temp.delivered_at(x.okey, x.bseq) + INTERVAL '2 hours')
  FROM (VALUES ('WF5-DD1A', 1), ('WF5-DD1B', 1), ('WF5-DD1B', 2), ('WF5-E2', 1), ('WF5-TT2', 1), ('WF5-HD3R', 1),
               ('WF5-E', 1), ('WF5-TT5', 1)) AS x (okey, bseq)
 ORDER BY pg_temp.delivered_at(x.okey, x.bseq), x.okey;

-- WF5-DD1A's second delivery and WF5-D: sent the evening of their delivery and refused by the tax authority's
-- simulated check, then reopened - drafts the automatic issuing no longer takes.
SELECT pg_temp.refuse_and_reopen(pg_temp.wid('einvoice:WF5-DD1A:2'), pg_temp.delivered_at('WF5-DD1A', 2)
                                 + INTERVAL '2 hours');
SELECT pg_temp.refuse_and_reopen(pg_temp.wid('einvoice:WF5-D:1'), pg_temp.delivered_at('WF5-D', 1)
                                 + INTERVAL '2 hours');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 3. HĐ-1: two invoices of the night run still at SANDBOX
-- ---------------------------------------------------------------------------------------------
-- WF5-HD1B: sent at the delivery + 5 minutes; SANDBOX took it in and is "still processing" until 2099 - every question
-- answers PENDING (SandboxProvider.query), so it is never taken back (WithdrawalRule: the provider holds it).
SELECT pg_temp.submit(pg_temp.wid('einvoice:WF5-HD1B:1'), 'SANDBOX', 'C26TSB',
                      pg_temp.delivered_at('WF5-HD1B', 1) + INTERVAL '5 minutes', pg_temp.staff('demo.ketoan'));
INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at, holder, held_until)
SELECT id, transaction_id, submitted_at, NULL, submitted_at + INTERVAL '40 seconds'
  FROM invoicing.einvoice WHERE id = pg_temp.wid('einvoice:WF5-HD1B:1');
INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, rejection_code, rejection_message,
                                        request_hash, received_at, ready_at, kind)
SELECT transaction_id, 'REJECTED', '1', 'C26TSB', 'TAX_AUTHORITY_REJECTED',
       'Demo: still at the tax authority (processing ends 2099)', pg_temp.hex('request:' || id), submitted_at,
       TIMESTAMPTZ '2099-12-31 00:00:00+07', 'ORIGINAL'
  FROM invoicing.einvoice WHERE id = pg_temp.wid('einvoice:WF5-HD1B:1');
SELECT pg_temp.call(pg_temp.wid('einvoice:WF5-HD1B:1'), 'SANDBOX', 'QUERY', 'NOT_FOUND',
                    pg_temp.delivered_at('WF5-HD1B', 1) + INTERVAL '5 minutes', 1);
SELECT pg_temp.call(pg_temp.wid('einvoice:WF5-HD1B:1'), 'SANDBOX', 'SUBMIT', 'ACCEPTED',
                    pg_temp.delivered_at('WF5-HD1B', 1) + INTERVAL '5 minutes 1 second', 2);
SELECT pg_temp.call(pg_temp.wid('einvoice:WF5-HD1B:1'), 'SANDBOX', 'QUERY', 'PENDING',
                    pg_temp.delivered_at('WF5-HD1B', 1) + INTERVAL '6 minutes', 3);

-- WF5-HD1C: sent at the delivery + 5 minutes; the send timed out and never reached SANDBOX (its "+sandbox-unreachable"
-- rehearsal): no record there. The reconciliation asks and, past auto-resend-for, sends nothing again; the accountant
-- may take it back withdraw-after (3 h) after the last send, with a reason (WithdrawalRule: NOT_FOUND).
SELECT pg_temp.submit(pg_temp.wid('einvoice:WF5-HD1C:1'), 'SANDBOX', 'C26TSB',
                      pg_temp.delivered_at('WF5-HD1C', 1) + INTERVAL '5 minutes', pg_temp.staff('demo.ketoan'));
INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at, holder, held_until)
SELECT id, transaction_id, submitted_at, NULL, submitted_at + INTERVAL '2 minutes'
  FROM invoicing.einvoice WHERE id = pg_temp.wid('einvoice:WF5-HD1C:1');
SELECT pg_temp.call(pg_temp.wid('einvoice:WF5-HD1C:1'), 'SANDBOX', 'QUERY', 'NOT_FOUND',
                    pg_temp.delivered_at('WF5-HD1C', 1) + INTERVAL '5 minutes', 1);
SELECT pg_temp.call(pg_temp.wid('einvoice:WF5-HD1C:1'), 'SANDBOX', 'SUBMIT', 'TIMEOUT',
                    pg_temp.delivered_at('WF5-HD1C', 1) + INTERVAL '5 minutes 1 second', 2);
SELECT pg_temp.call(pg_temp.wid('einvoice:WF5-HD1C:1'), 'SANDBOX', 'QUERY', 'NOT_FOUND',
                    pg_temp.delivered_at('WF5-HD1C', 1) + INTERVAL '7 minutes', 3);

-- The accountants were told of both as the jobs tell them (EInvoiceWorkQueues.announceStuck, announceResultOverdue):
-- noted, so the first sweep after the build does not tell them again.
INSERT INTO invoicing.einvoice_stuck_notice (einvoice_id, transaction_id, notified_at)
SELECT id, transaction_id, submitted_at + INTERVAL '31 minutes'
  FROM invoicing.einvoice WHERE id IN (pg_temp.wid('einvoice:WF5-HD1B:1'), pg_temp.wid('einvoice:WF5-HD1C:1'));

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 4. HĐ-3 step 5: WF5-HD3R's original replaced two days ago (EInvoice.drawUpReplacement): the buyer's address was
--    printed wrongly; the agreement with the buyer is attached; issued by SANDBOX, the original REPLACED
-- ---------------------------------------------------------------------------------------------
INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, contract_number, shipment_id,
                                shipment_code, delivery_batch_id, customer_id, original_einvoice_id, issue_date,
                                issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name, buyer_tax_code,
                                buyer_address, buyer_email, currency, payment_method, subtotal, tax_total, grand_total,
                                amount_in_words, warnings, adjustment_reason, correction_basis, agreement_file_key,
                                agreement_number, agreement_date, version, created_at, created_by)
SELECT pg_temp.wid('einvoice:WF5-HD3R:replacement'), pg_temp.next_code(pg_temp.today() - 2), 'REPLACEMENT', 'DRAFT',
       o.order_id, o.order_number, o.contract_number, o.shipment_id, o.shipment_code, o.delivery_batch_id,
       o.customer_id, o.id, pg_temp.today() - 2, ((pg_temp.today() - 1)::timestamp) AT TIME ZONE 'Asia/Ho_Chi_Minh',
       o.seller_name, o.seller_tax_code, o.seller_address, o.buyer_name, o.buyer_tax_code, o.buyer_address,
       o.buyer_email, 'VND', 'BANK_TRANSFER', o.subtotal, o.tax_total, o.grand_total, o.amount_in_words, NULL,
       'Thay thế hóa đơn ' || o.code || ': ghi sai địa chỉ người mua (thiếu số nhà); hai bên đã lập biên bản thỏa thuận',
       'ERROR', 'einvoices/demo/' || o.code || '-bien-ban-thoa-thuan.pdf', 'BBTT-' || o.code, pg_temp.today() - 2, 0,
       pg_temp.at(2, '09:00'), pg_temp.staff('demo.ketoan')::text
  FROM invoicing.einvoice o WHERE o.id = pg_temp.wid('einvoice:WF5-HD3R:1');

INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku, item_name, unit,
                                     quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
SELECT pg_temp.wid('einvoice-line:WF5-HD3R:replacement:' || l.line_no), pg_temp.wid('einvoice:WF5-HD3R:replacement'),
       l.line_no, l.kind, l.fee_kind, l.order_line_id, l.sku, l.item_name, l.unit, l.quantity, l.units_per_carton,
       l.unit_price, l.tax_rate, l.amount, l.tax_amount
  FROM invoicing.einvoice_line l WHERE l.einvoice_id = pg_temp.wid('einvoice:WF5-HD3R:1');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- Sent like any correction: SUBMITTING, the provider takes it in (its books name the original), the original becomes
-- REPLACED while the replacement is at the provider, then the replacement is ISSUED.
DO $$
DECLARE
    inv UUID := pg_temp.wid('einvoice:WF5-HD3R:replacement');
    at TIMESTAMPTZ := pg_temp.at(2, '10:00');
    tx TEXT;
    num INTEGER;
    digest TEXT;
BEGIN
    PERFORM pg_temp.submit(inv, 'SANDBOX', 'C26TSB', at, pg_temp.staff('demo.ketoan'));
    SELECT transaction_id INTO tx FROM invoicing.einvoice WHERE id = inv;
    INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at, holder, held_until)
    VALUES (inv, tx, at, NULL, at + INTERVAL '32 seconds');
    INSERT INTO invoicing.sandbox_number (template_code, symbol, last_number) VALUES ('1', 'C26TSB', 1)
    ON CONFLICT (template_code, symbol) DO UPDATE SET last_number = invoicing.sandbox_number.last_number + 1
    RETURNING last_number INTO num;
    digest := upper(pg_temp.hex(tx));
    INSERT INTO invoicing.sandbox_issuance (transaction_id, state, template_code, symbol, invoice_number,
                                            tax_authority_code, lookup_code, xml_document, pdf_document, request_hash,
                                            received_at, ready_at, kind, original_template_code, original_symbol,
                                            original_invoice_number)
    SELECT tx, 'ISSUED', '1', 'C26TSB', num, 'SBX' || substr(digest, 1, 31), 'SBX' || substr(digest, 32, 10),
           '<!-- SANDBOX E-INVOICE - TEST DOCUMENT - NO LEGAL VALUE (demo seed) -->',
           convert_to('SANDBOX E-INVOICE - TEST DOCUMENT - NO LEGAL VALUE (demo seed)', 'UTF8'),
           pg_temp.hex('request:' || inv), at, at + INTERVAL '5 seconds', 'REPLACEMENT', o.template_code, o.symbol,
           o.invoice_number
      FROM invoicing.einvoice o WHERE o.id = pg_temp.wid('einvoice:WF5-HD3R:1');
    PERFORM pg_temp.call(inv, 'SANDBOX', 'QUERY', 'NOT_FOUND', at, 1);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'SUBMIT', 'ACCEPTED', at + INTERVAL '1 second', 2);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'QUERY', 'ISSUED', at + INTERVAL '30 seconds', 3);
    PERFORM pg_temp.call(inv, 'SANDBOX', 'DOWNLOAD', 'DOWNLOADED', at + INTERVAL '31 seconds', 4);
    UPDATE invoicing.einvoice SET status = 'REPLACED', version = version + 1, last_modified_at = at + INTERVAL '32 seconds',
                                  last_modified_by = 'system'
     WHERE id = pg_temp.wid('einvoice:WF5-HD3R:1');
    UPDATE invoicing.einvoice
       SET status = 'ISSUED', invoice_number = num, tax_authority_code = 'SBX' || substr(digest, 1, 31),
           lookup_code = 'SBX' || substr(digest, 32, 10), signed_at = at + INTERVAL '5 seconds',
           issued_at = at + INTERVAL '32 seconds', issued_via = 'PROVIDER',
           xml_file_key = 'einvoices/' || to_char(at AT TIME ZONE 'UTC', 'YYYY/MM/DD') || '/' || pg_temp.wid('xml:' || inv) || '.xml',
           pdf_file_key = 'einvoices/' || to_char(at AT TIME ZONE 'UTC', 'YYYY/MM/DD') || '/' || pg_temp.wid('pdf:' || inv) || '.pdf',
           version = version + 1, last_modified_at = at + INTERVAL '32 seconds', last_modified_by = 'system'
     WHERE id = inv;
END
$$;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- WF5-TH1's first delivery, yesterday: issued after the replacement of the day before, so the series' numbers keep
-- following the dates.
SELECT pg_temp.issue_by_sandbox(pg_temp.wid('einvoice:WF5-TH1:1'), pg_temp.delivered_at('WF5-TH1', 1) + INTERVAL '2 hours');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 5. HĐ-5: WF5-HD5's original issued by Viettel SInvoice, the provider used before SePay (no longer in use, no
--    account stored): its symbol C26TVT, its number in Viettel's sequence. Nothing of it is in SANDBOX's books.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    inv UUID := pg_temp.wid('einvoice:WF5-HD5:1');
    at TIMESTAMPTZ := pg_temp.delivered_at('WF5-HD5', 1) + INTERVAL '2 hours';
BEGIN
    PERFORM pg_temp.submit(inv, 'VIETTEL_SINVOICE', 'C26TVT', at, pg_temp.staff('demo.ketoan'));
    INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at, holder, held_until)
    SELECT id, transaction_id, at, NULL, at + INTERVAL '2 minutes' FROM invoicing.einvoice WHERE id = inv;
    PERFORM pg_temp.call(inv, 'VIETTEL_SINVOICE', 'QUERY', 'NOT_FOUND', at, 1);
    PERFORM pg_temp.call(inv, 'VIETTEL_SINVOICE', 'SUBMIT', 'ACCEPTED', at + INTERVAL '2 seconds', 2);
    PERFORM pg_temp.call(inv, 'VIETTEL_SINVOICE', 'QUERY', 'ISSUED', at + INTERVAL '95 seconds', 3);
    PERFORM pg_temp.call(inv, 'VIETTEL_SINVOICE', 'DOWNLOAD', 'DOWNLOADED', at + INTERVAL '100 seconds', 4);
    UPDATE invoicing.einvoice
       SET status = 'ISSUED', invoice_number = 1532, tax_authority_code = upper(substr(pg_temp.hex('viettel:' || inv), 1, 34)),
           lookup_code = upper(substr(pg_temp.hex('viettel-lookup:' || inv), 1, 12)), signed_at = at + INTERVAL '60 seconds',
           issued_at = at + INTERVAL '100 seconds', issued_via = 'PROVIDER', warnings = NULL,
           xml_file_key = 'einvoices/' || to_char(at AT TIME ZONE 'UTC', 'YYYY/MM/DD') || '/' || pg_temp.wid('xml:' || inv) || '.xml',
           pdf_file_key = 'einvoices/' || to_char(at AT TIME ZONE 'UTC', 'YYYY/MM/DD') || '/' || pg_temp.wid('pdf:' || inv) || '.pdf',
           version = version + 1, last_modified_at = at + INTERVAL '100 seconds', last_modified_by = 'system'
     WHERE id = inv;
END
$$;

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- 6. The money after delivery: WF5-E, WF5-E2, WF5-DD1A/B paid in full the day after their (last) delivery
-- ---------------------------------------------------------------------------------------------
SELECT pg_temp.pay(x.okey, 2, pg_temp.delivered_at(x.okey, x.last_batch) + INTERVAL '1 day 2 hours')
  FROM (VALUES ('WF5-E', 1), ('WF5-E2', 1), ('WF5-DD1A', 2), ('WF5-DD1B', 2)) AS x (okey, last_batch);

-- WF5-TH1 paid what its first delivery owes the same evening: a return from that delivery (TH-1) then has money to
-- give back, and "Ghi nhận đã hoàn" is shown on it.
SELECT pg_temp.pay('WF5-TH1', 2, pg_temp.delivered_at('WF5-TH1', 1) + INTERVAL '3 hours');

SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- WF5-E2 (step 11, "Đã đóng"): completed by the nightly run 31 days ago - its window of one day past, paid, invoiced
-- (OrderSettlementService.complete) - and its contract with it. Thirty days on, the next run closes it.
INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
VALUES (pg_temp.wid('order-history:WF5-E2:completed'), pg_temp.wid('order:WF5-E2'), 'DELIVERED', 'COMPLETED',
        'RETURN_WINDOW_CLOSED', NULL, pg_temp.at(31, '00:30'), pg_temp.at(31, '00:30'), 'system');

UPDATE ordering.customer_order
   SET status = 'COMPLETED', completed_at = pg_temp.at(31, '00:30'), version = version + 1,
       last_modified_at = pg_temp.at(31, '00:30'), last_modified_by = 'system'
 WHERE id = pg_temp.wid('order:WF5-E2');

UPDATE ordering.sales_contract
   SET status = 'COMPLETED', completed_at = pg_temp.at(31, '00:30'), version = version + 1,
       last_modified_at = pg_temp.at(31, '00:30'), last_modified_by = 'system'
 WHERE id = pg_temp.wid('contract:WF5-E2');

-- Every code and number above is the sequences' own: the next invoice drawn up or issued live continues them.
DROP FUNCTION pg_temp.draw_up(TEXT, INTEGER);
DROP FUNCTION pg_temp.issue_by_sandbox(UUID, TIMESTAMPTZ, TEXT);
DROP FUNCTION pg_temp.refuse_and_reopen(UUID, TIMESTAMPTZ);
DROP FUNCTION pg_temp.submit(UUID, TEXT, TEXT, TIMESTAMPTZ, UUID);
DROP FUNCTION pg_temp.call(UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, INTEGER, TEXT);
DROP FUNCTION pg_temp.pay(TEXT, INTEGER, TIMESTAMPTZ);
DROP FUNCTION pg_temp.delivered_at(TEXT, INTEGER);
DROP FUNCTION pg_temp.next_code(DATE);
DROP FUNCTION pg_temp.rate_on(TEXT, DATE);
DROP FUNCTION pg_temp.words(NUMERIC);
DROP FUNCTION pg_temp.three(INTEGER, BOOLEAN);
DROP FUNCTION pg_temp.hex(TEXT);
DROP FUNCTION pg_temp.vn(TIMESTAMPTZ);
DROP FUNCTION pg_temp.wid(TEXT);
DROP FUNCTION pg_temp.today();
DROP FUNCTION pg_temp.at(INTEGER, TEXT);
DROP FUNCTION pg_temp.ymd(INTEGER);
DROP FUNCTION pg_temp.staff(TEXT);
