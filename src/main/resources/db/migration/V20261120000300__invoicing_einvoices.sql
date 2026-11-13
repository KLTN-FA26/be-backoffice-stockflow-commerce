-- =============================================================================
-- E-invoices: the invoicing module's schema (WF-05 E2; plan §1.3-§1.5; kltn-docs 21; NĐ 254/2026/NĐ-CP, TT 91/2026/TT-BTC
-- as read in plans/E0-legal.md).
--
--   * invoicing.einvoice - one VAT invoice. ORIGINAL: one per delivered shipment (NĐ 254 Điều 9 khoản 3: every
--     delivery is invoiced), drawn up as a DRAFT when the delivery is recorded. ADJUSTMENT and REPLACEMENT name the
--     invoice they change (original_einvoice_id); they are package E4's, and only their place is here.
--       DRAFT -> SUBMITTING -> ISSUED | REJECTED;  REJECTED -> DRAFT;  DRAFT -> VOIDED_DRAFT;  ISSUED -> REPLACED (E4).
--     An issued invoice is never cancelled and never changes (TT 91 Điều 10 has no cancellation): the only writes it
--     takes are the flag "has an adjustment" and the status REPLACED. It copies the seller and the buyer when drawn
--     up; the buyer is refreshed while it is a draft, never after.
--   * invoicing.einvoice_line - its goods lines (a delivered order line: cartons x the carton's price) and fee lines
--     (the plate fees on the order's first invoice, the shipment's share of the shipping fee). Money is in whole dong;
--     a line's amount is its quantity x its unit price and its VAT is its amount x its rate, rounded to the dong; the
--     invoice's totals are the sums of its lines. Lines change only while the invoice is a draft.
--   * invoicing.einvoice_series - the template code and symbol an invoice is numbered under at the provider, one in
--     use per provider and year (TT 91 Điều 4, Phụ lục I: template 1 = VAT invoice; symbol C26Txx = with the tax
--     authority's code, year 2026, registered by the business, two letters of the seller's own). Numbers are the
--     provider's: at most 8 digits, from 1 each year, never reused within a symbol (NĐ 254 Phụ lục điểm 2).
--   * invoicing.einvoice_provider_config - which provider issues (SANDBOX by default at capstone scale; Viettel
--     SInvoice is package E3), its environment, endpoint and account, the secret sealed with STOCKFLOW_SECRETS_KEY, and
--     whether a draft whose buyer is complete is issued without waiting for the accountant.
--   * invoicing.einvoice_provider_call - one row per call to the provider: the transaction id, the operation, the
--     outcome, hashes of what was sent and received. Never the payload, a secret or a signed link. Append-only.
--   * invoicing.einvoice_error_notice - the notice "invoice drawn up wrongly" (Mẫu 04/SS-HĐĐT, TT 91 Điều 10 khoản 1
--     điểm a: a wrong buyer name or address is notified, no new invoice). Package E4 writes it; its place is here.
--   * invoicing.sandbox_issuance / sandbox_number - the sandbox provider's own books, standing in for the provider's
--     system: what it was sent under which transaction id, and the last number it gave per symbol.
--
-- Every rule is stated twice: in the aggregate (EInvoice) and here.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS invoicing;

-- Series and provider ------------------------------------------------------------------------------------------------
CREATE TABLE invoicing.einvoice_provider_config
(
    id               UUID         NOT NULL,
    provider         VARCHAR(32)  NOT NULL,
    environment      VARCHAR(16)  NOT NULL,
    endpoint         VARCHAR(500),
    username         VARCHAR(200),
    -- SecretCipher's sealed form (v1:...), bound to this row; never the clear secret.
    secret_sealed    VARCHAR(2000),
    active           BOOLEAN      NOT NULL DEFAULT FALSE,
    auto_issue       BOOLEAN      NOT NULL DEFAULT FALSE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_einvoice_provider_config PRIMARY KEY (id),
    CONSTRAINT uk_einvoice_provider_config_provider UNIQUE (provider),
    CONSTRAINT ck_einvoice_provider_config_provider CHECK (provider IN ('SANDBOX', 'VIETTEL_SINVOICE')),
    CONSTRAINT ck_einvoice_provider_config_environment CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    -- The sandbox issues nothing of legal value: it is never a production provider.
    CONSTRAINT ck_einvoice_provider_config_sandbox CHECK (provider <> 'SANDBOX' OR environment = 'SANDBOX'),
    CONSTRAINT ck_einvoice_provider_config_secret CHECK (secret_sealed IS NULL OR secret_sealed LIKE 'v1:%')
);

-- One provider issues at a time.
CREATE UNIQUE INDEX uk_einvoice_provider_config_active ON invoicing.einvoice_provider_config (active) WHERE active;

COMMENT ON TABLE invoicing.einvoice_provider_config IS
    'WF-05 E2: the e-invoice provider in use, its account (secret sealed) and whether complete drafts are issued automatically.';

INSERT INTO invoicing.einvoice_provider_config (id, provider, environment, active, auto_issue, created_by)
VALUES (gen_random_uuid(), 'SANDBOX', 'SANDBOX', TRUE, FALSE, 'flyway');

CREATE TABLE invoicing.einvoice_series
(
    id               UUID         NOT NULL,
    provider         VARCHAR(32)  NOT NULL,
    template_code    VARCHAR(1)   NOT NULL,
    symbol           VARCHAR(6)   NOT NULL,
    year             SMALLINT     NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_einvoice_series PRIMARY KEY (id),
    CONSTRAINT uk_einvoice_series UNIQUE (provider, template_code, symbol),
    CONSTRAINT fk_einvoice_series_provider FOREIGN KEY (provider)
        REFERENCES invoicing.einvoice_provider_config (provider),
    -- TT 91 Phụ lục I: one digit 1-9; C/K, the year's last two digits, the kind of invoice, two letters.
    CONSTRAINT ck_einvoice_series_template CHECK (template_code ~ '^[1-9]$'),
    CONSTRAINT ck_einvoice_series_symbol CHECK (symbol ~ '^[CK][0-9]{2}[TDLMNBGHXF][A-Z]{2}$'),
    CONSTRAINT ck_einvoice_series_year CHECK (year BETWEEN 2026 AND 2099
        AND substr(symbol, 2, 2) = lpad((year % 100)::text, 2, '0'))
);

-- The series an invoice of a year is numbered under: one per provider and year.
CREATE UNIQUE INDEX uk_einvoice_series_in_use ON invoicing.einvoice_series (provider, year) WHERE active;

COMMENT ON TABLE invoicing.einvoice_series IS
    'WF-05 E2 (TT 91 Điều 4): template code and symbol invoices are numbered under at the provider; one in use per provider and year.';

-- The sandbox numbers under an obviously made-up pair of letters; a real provider's series are entered by the administrator.
INSERT INTO invoicing.einvoice_series (id, provider, template_code, symbol, year, active, created_by)
VALUES (gen_random_uuid(), 'SANDBOX', '1', 'C26TSB', 2026, TRUE, 'flyway'),
       (gen_random_uuid(), 'SANDBOX', '1', 'C27TSB', 2027, TRUE, 'flyway');

-- The invoice --------------------------------------------------------------------------------------------------------
CREATE TABLE invoicing.einvoice
(
    id                   UUID           NOT NULL,
    code                 VARCHAR(32)    NOT NULL,
    type                 VARCHAR(16)    NOT NULL,
    status               VARCHAR(16)    NOT NULL,
    -- Set once an adjustment of it is issued (E4); the invoice stays in force.
    adjusted             BOOLEAN        NOT NULL DEFAULT FALSE,

    order_id             UUID           NOT NULL,
    order_number         VARCHAR(32)    NOT NULL,
    contract_number      VARCHAR(40),
    shipment_id          UUID,
    shipment_code        VARCHAR(48),
    delivery_batch_id    UUID,
    customer_id          UUID           NOT NULL,
    original_einvoice_id UUID,

    -- The provider and what it gave. transaction_id is this invoice's id and its attempt: the provider knows one
    -- invoice per transaction id, which is what keeps a retry from issuing twice.
    provider             VARCHAR(32),
    attempt              INTEGER        NOT NULL DEFAULT 0,
    transaction_id       VARCHAR(64),
    template_code        VARCHAR(1),
    symbol               VARCHAR(6),
    invoice_number       INTEGER,
    tax_authority_code   VARCHAR(64),
    lookup_code          VARCHAR(64),

    -- The day the goods were delivered (NĐ 254 Điều 9 khoản 1), whenever the invoice is issued.
    issue_date           DATE           NOT NULL,
    -- Signed and sent for the tax authority's code no later than the working day after issue_date (NĐ 254 Phụ lục điểm 7).
    issue_due_at         TIMESTAMPTZ    NOT NULL,
    due_warned_at        TIMESTAMPTZ,
    signed_at            TIMESTAMPTZ,

    seller_name          VARCHAR(200)   NOT NULL,
    seller_tax_code      VARCHAR(14)    NOT NULL,
    seller_address       VARCHAR(500)   NOT NULL,
    buyer_name           VARCHAR(200)   NOT NULL,
    buyer_tax_code       VARCHAR(14),
    buyer_address        VARCHAR(500),
    buyer_email          VARCHAR(320),

    currency             VARCHAR(3)     NOT NULL,
    payment_method       VARCHAR(16)    NOT NULL,
    subtotal             NUMERIC(19, 4) NOT NULL,
    tax_total            NUMERIC(19, 4) NOT NULL,
    grand_total          NUMERIC(19, 4) NOT NULL,
    amount_in_words      VARCHAR(500)   NOT NULL,
    -- Codes of what the accountant should look at before issuing, comma-separated; null for none.
    warnings             VARCHAR(200),

    -- E4: why an adjustment or replacement was drawn up, and the agreement of both parties where TT 91 asks for one.
    adjustment_reason    VARCHAR(500),
    agreement_file_key   VARCHAR(500),

    xml_file_key         VARCHAR(500),
    pdf_file_key         VARCHAR(500),
    rejection_code       VARCHAR(64),
    rejection_message    VARCHAR(500),

    submitted_at         TIMESTAMPTZ,
    submitted_by         UUID,
    issued_at            TIMESTAMPTZ,
    voided_at            TIMESTAMPTZ,
    voided_by            UUID,
    void_reason          VARCHAR(500),

    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_einvoice PRIMARY KEY (id),
    CONSTRAINT uk_einvoice_code UNIQUE (code),
    CONSTRAINT uk_einvoice_transaction UNIQUE (transaction_id),
    CONSTRAINT fk_einvoice_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id),
    CONSTRAINT fk_einvoice_shipment FOREIGN KEY (shipment_id) REFERENCES ordering.order_shipment (id),
    CONSTRAINT fk_einvoice_delivery_batch FOREIGN KEY (delivery_batch_id) REFERENCES ordering.delivery_batch (id),
    CONSTRAINT fk_einvoice_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id),
    CONSTRAINT fk_einvoice_original FOREIGN KEY (original_einvoice_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT fk_einvoice_submitted_by FOREIGN KEY (submitted_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_einvoice_voided_by FOREIGN KEY (voided_by) REFERENCES identity.app_user (id),

    CONSTRAINT ck_einvoice_type CHECK (type IN ('ORIGINAL', 'ADJUSTMENT', 'REPLACEMENT')),
    CONSTRAINT ck_einvoice_status CHECK (status IN ('DRAFT', 'SUBMITTING', 'ISSUED', 'REJECTED', 'VOIDED_DRAFT',
                                                    'REPLACED')),
    -- An original invoices a shipment and changes no invoice; an adjustment or a replacement names the one it changes.
    CONSTRAINT ck_einvoice_original CHECK (
        (type = 'ORIGINAL' AND original_einvoice_id IS NULL AND shipment_id IS NOT NULL)
        OR (type <> 'ORIGINAL' AND original_einvoice_id IS NOT NULL AND original_einvoice_id <> id)),
    CONSTRAINT ck_einvoice_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_einvoice_payment_method CHECK (payment_method IN ('BANK_TRANSFER')),
    -- Whole dong, and the total with tax is the two others added.
    CONSTRAINT ck_einvoice_totals CHECK (grand_total = subtotal + tax_total
        AND subtotal = trunc(subtotal) AND tax_total = trunc(tax_total)),
    CONSTRAINT ck_einvoice_original_amounts CHECK (type <> 'ORIGINAL' OR (subtotal >= 0 AND tax_total >= 0)),
    CONSTRAINT ck_einvoice_seller CHECK (btrim(seller_name) <> '' AND btrim(seller_tax_code) <> ''
        AND btrim(seller_address) <> ''),
    CONSTRAINT ck_einvoice_words CHECK (btrim(amount_in_words) <> ''),
    -- NĐ 254 Phụ lục điểm 4.b, kltn-docs 21 BR-06: no invoice leaves for the provider without the buyer's name, tax
    -- code and address, and the e-mail address it is sent to.
    CONSTRAINT ck_einvoice_buyer_complete CHECK (status IN ('DRAFT', 'VOIDED_DRAFT')
        OR (btrim(buyer_name) <> '' AND COALESCE(btrim(buyer_tax_code), '') <> ''
            AND COALESCE(btrim(buyer_address), '') <> '' AND COALESCE(btrim(buyer_email), '') <> '')),
    CONSTRAINT ck_einvoice_buyer_tax_code CHECK (buyer_tax_code IS NULL
        OR buyer_tax_code ~ '^([0-9]{10}(-[0-9]{3})?|[0-9]{12})$'),
    -- Once sent it carries the provider, its transaction and the series it is to be numbered under.
    CONSTRAINT ck_einvoice_submitted CHECK (status IN ('DRAFT', 'VOIDED_DRAFT')
        OR (provider IS NOT NULL AND transaction_id IS NOT NULL AND template_code IS NOT NULL AND symbol IS NOT NULL
            AND submitted_at IS NOT NULL AND attempt >= 1)),
    CONSTRAINT ck_einvoice_attempt CHECK (attempt >= 0),
    CONSTRAINT ck_einvoice_symbol_year CHECK (symbol IS NULL OR substr(symbol, 2, 2) = to_char(issue_date, 'YY')),
    -- NĐ 254 Phụ lục điểm 2: at most 8 digits, from 1.
    CONSTRAINT ck_einvoice_number CHECK (invoice_number IS NULL OR invoice_number BETWEEN 1 AND 99999999),
    -- Issued: numbered, coded by the tax authority, signed, and both files kept. Not issued: no number.
    CONSTRAINT ck_einvoice_issued CHECK (
        (status IN ('ISSUED', 'REPLACED') AND invoice_number IS NOT NULL AND tax_authority_code IS NOT NULL
            AND lookup_code IS NOT NULL AND signed_at IS NOT NULL AND issued_at IS NOT NULL
            AND xml_file_key IS NOT NULL AND pdf_file_key IS NOT NULL)
        OR (status NOT IN ('ISSUED', 'REPLACED') AND invoice_number IS NULL AND tax_authority_code IS NULL
            AND xml_file_key IS NULL AND pdf_file_key IS NULL AND issued_at IS NULL)),
    CONSTRAINT ck_einvoice_adjusted CHECK (NOT adjusted OR status = 'ISSUED'),
    CONSTRAINT ck_einvoice_rejected CHECK (status <> 'REJECTED' OR COALESCE(btrim(rejection_code), '') <> ''),
    CONSTRAINT ck_einvoice_voided CHECK ((status = 'VOIDED_DRAFT') = (voided_at IS NOT NULL)
        AND (status <> 'VOIDED_DRAFT' OR COALESCE(btrim(void_reason), '') <> ''))
);

-- kltn-docs 21 BR-01: one original invoice in force per delivered shipment.
-- A REPLACED original no longer counts here: what takes its place is a REPLACEMENT (package E4), not a second
-- original. Until E4 settles what such a shipment may still be invoiced for, the application draws no original up for a
-- shipment that has a replaced one (EInvoiceDrafts.drawUp); E4 is to state that rule here as well.
CREATE UNIQUE INDEX uk_einvoice_original_per_shipment ON invoicing.einvoice (shipment_id)
    WHERE type = 'ORIGINAL' AND status NOT IN ('VOIDED_DRAFT', 'REPLACED');
-- NĐ 254 Phụ lục điểm 2: a number is used once within a symbol.
CREATE UNIQUE INDEX uk_einvoice_number ON invoicing.einvoice (template_code, symbol, invoice_number)
    WHERE invoice_number IS NOT NULL;
CREATE INDEX ix_einvoice_order ON invoicing.einvoice (order_id);
CREATE INDEX ix_einvoice_customer ON invoicing.einvoice (customer_id);
CREATE INDEX ix_einvoice_status_due ON invoicing.einvoice (status, issue_due_at);
CREATE INDEX ix_einvoice_issue_date ON invoicing.einvoice (issue_date) WHERE status IN ('ISSUED', 'REPLACED');
CREATE INDEX ix_einvoice_original ON invoicing.einvoice (original_einvoice_id) WHERE original_einvoice_id IS NOT NULL;

COMMENT ON TABLE invoicing.einvoice IS
    'WF-05 E2 (kltn-docs 21; NĐ 254, TT 91): a VAT e-invoice - one original per delivered shipment; immutable once issued.';

CREATE TABLE invoicing.einvoice_line
(
    id            UUID           NOT NULL,
    einvoice_id   UUID           NOT NULL,
    line_no       INTEGER        NOT NULL,
    kind          VARCHAR(8)     NOT NULL,
    fee_kind      VARCHAR(16),
    order_line_id UUID,
    sku           VARCHAR(64),
    item_name     VARCHAR(500)   NOT NULL,
    unit          VARCHAR(16)    NOT NULL,
    quantity      INTEGER        NOT NULL,
    -- On a goods line of cartons: the pieces in one, printed with the name.
    units_per_carton INTEGER,
    unit_price    NUMERIC(19, 4) NOT NULL,
    tax_rate      NUMERIC(5, 2)  NOT NULL,
    amount        NUMERIC(19, 4) NOT NULL,
    tax_amount    NUMERIC(19, 4) NOT NULL,

    CONSTRAINT pk_einvoice_line PRIMARY KEY (id),
    CONSTRAINT uk_einvoice_line_no UNIQUE (einvoice_id, line_no),
    CONSTRAINT fk_einvoice_line_einvoice FOREIGN KEY (einvoice_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT fk_einvoice_line_order_line FOREIGN KEY (order_line_id) REFERENCES ordering.order_line (id),
    CONSTRAINT ck_einvoice_line_no CHECK (line_no >= 1),
    CONSTRAINT ck_einvoice_line_kind CHECK (kind IN ('GOODS', 'FEE')),
    -- A goods line is a delivered order line; a fee line says which fee and names no order line.
    CONSTRAINT ck_einvoice_line_subject CHECK (
        (kind = 'GOODS' AND fee_kind IS NULL AND order_line_id IS NOT NULL AND sku IS NOT NULL)
        OR (kind = 'FEE' AND fee_kind IN ('PLATE_FEE', 'SHIPPING_FEE') AND order_line_id IS NULL
            AND units_per_carton IS NULL)),
    CONSTRAINT ck_einvoice_line_name CHECK (btrim(item_name) <> '' AND btrim(unit) <> ''),
    CONSTRAINT ck_einvoice_line_quantity CHECK (quantity <> 0 AND (units_per_carton IS NULL OR units_per_carton >= 1)),
    CONSTRAINT ck_einvoice_line_price CHECK (unit_price >= 0 AND unit_price = trunc(unit_price)),
    CONSTRAINT ck_einvoice_line_rate CHECK (tax_rate >= 0 AND tax_rate < 100),
    -- Rounded line by line, half up, to the dong (kltn-docs 14 BR-21): an invoice's tax is the sum of its lines' taxes.
    CONSTRAINT ck_einvoice_line_amount CHECK (amount = quantity * unit_price
        AND tax_amount = round(amount * tax_rate / 100, 0))
);

COMMENT ON TABLE invoicing.einvoice_line IS
    'WF-05 E2: a goods line (delivered cartons x the carton price) or a fee line of an e-invoice, with its VAT.';

-- Guards -------------------------------------------------------------------------------------------------------------
-- What an invoice is about never changes; what it says changes only while it is a draft; once issued the only writes
-- are "has an adjustment" and REPLACED; a voided draft and a replaced invoice are final; nothing is deleted.
CREATE FUNCTION invoicing.einvoice_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    identity_columns TEXT[] := ARRAY['id', 'code', 'type', 'order_id', 'order_number', 'shipment_id', 'customer_id',
                                     'original_einvoice_id', 'created_at', 'created_by'];
    bookkeeping      TEXT[] := ARRAY['version', 'last_modified_at', 'last_modified_by'];
    content_columns  TEXT[] := ARRAY['contract_number', 'shipment_code', 'delivery_batch_id', 'issue_date',
                                     'seller_name', 'seller_tax_code', 'seller_address', 'buyer_name', 'buyer_tax_code',
                                     'buyer_address', 'buyer_email', 'currency', 'payment_method', 'subtotal',
                                     'tax_total', 'grand_total', 'amount_in_words', 'adjustment_reason',
                                     'agreement_file_key'];
    old_row          JSONB;
    new_row          JSONB;
    name             TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'e-invoice % is never deleted (status %)', OLD.code, OLD.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_no_delete';
    END IF;
    old_row := to_jsonb(OLD);
    new_row := to_jsonb(NEW);
    FOREACH name IN ARRAY identity_columns LOOP
        IF old_row -> name IS DISTINCT FROM new_row -> name THEN
            RAISE EXCEPTION 'e-invoice %: % never changes', OLD.code, name
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_identity';
        END IF;
    END LOOP;
    IF OLD.status IN ('VOIDED_DRAFT', 'REPLACED') THEN
        RAISE EXCEPTION 'e-invoice % is % and final', OLD.code, OLD.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_final';
    END IF;
    IF OLD.status = 'ISSUED' THEN
        IF (old_row - bookkeeping - 'status' - 'adjusted') IS DISTINCT FROM (new_row - bookkeeping - 'status' - 'adjusted')
           OR NEW.status NOT IN ('ISSUED', 'REPLACED') OR (OLD.adjusted AND NOT NEW.adjusted) THEN
            RAISE EXCEPTION 'e-invoice % is issued and does not change', OLD.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_issued_immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status <> OLD.status AND NOT (
            (OLD.status = 'DRAFT' AND NEW.status IN ('SUBMITTING', 'VOIDED_DRAFT'))
            OR (OLD.status = 'SUBMITTING' AND NEW.status IN ('ISSUED', 'REJECTED'))
            OR (OLD.status = 'REJECTED' AND NEW.status = 'DRAFT')) THEN
        RAISE EXCEPTION 'e-invoice % cannot go from % to %', OLD.code, OLD.status, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_transition';
    END IF;
    IF OLD.status <> 'DRAFT' THEN
        FOREACH name IN ARRAY content_columns LOOP
            IF old_row -> name IS DISTINCT FROM new_row -> name THEN
                RAISE EXCEPTION 'e-invoice % is %: % changes only on a draft', OLD.code, OLD.status, name
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_content';
            END IF;
        END LOOP;
    END IF;
    -- A transaction is one attempt: it changes only when a rejected invoice goes back to a draft for another.
    IF OLD.status = 'SUBMITTING' AND (NEW.transaction_id IS DISTINCT FROM OLD.transaction_id
            OR NEW.attempt <> OLD.attempt OR NEW.template_code IS DISTINCT FROM OLD.template_code
            OR NEW.symbol IS DISTINCT FROM OLD.symbol OR NEW.provider IS DISTINCT FROM OLD.provider) THEN
        RAISE EXCEPTION 'e-invoice % is at the provider: its transaction and series do not change', OLD.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_transaction';
    END IF;
    IF NEW.attempt < OLD.attempt THEN
        RAISE EXCEPTION 'e-invoice %: attempts only count up', OLD.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_transaction';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_guard
    BEFORE UPDATE OR DELETE ON invoicing.einvoice
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_guard();

-- An invoice starts as a draft (an adjustment or replacement too), not yet at any provider.
CREATE FUNCTION invoicing.einvoice_starts_as_draft() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    original invoicing.einvoice%ROWTYPE;
BEGIN
    IF NEW.status <> 'DRAFT' OR NEW.adjusted THEN
        RAISE EXCEPTION 'e-invoice % is drawn up as a draft, not %', NEW.code, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_starts_as_draft';
    END IF;
    IF NEW.original_einvoice_id IS NOT NULL THEN
        SELECT * INTO original FROM invoicing.einvoice WHERE id = NEW.original_einvoice_id FOR SHARE;
        -- TT 91 Điều 10: only an invoice in force is adjusted or replaced, for the same order and buyer.
        IF original.status <> 'ISSUED' OR original.order_id <> NEW.order_id OR original.customer_id <> NEW.customer_id THEN
            RAISE EXCEPTION 'e-invoice % changes an invoice that is not an issued invoice of the same order', NEW.code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_original_in_force';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_starts_as_draft
    BEFORE INSERT ON invoicing.einvoice
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_starts_as_draft();

-- Lines belong to a draft: written with it, changed while it is one, never after.
CREATE FUNCTION invoicing.einvoice_line_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    invoice_id     UUID;
    invoice_status TEXT;
    invoice_type   TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        invoice_id := OLD.einvoice_id;
    ELSE
        invoice_id := NEW.einvoice_id;
    END IF;
    SELECT status, type INTO invoice_status, invoice_type FROM invoicing.einvoice WHERE id = invoice_id FOR SHARE;
    IF invoice_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'the lines of an e-invoice change only while it is a draft (it is %)', invoice_status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_draft_only';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.einvoice_id <> OLD.einvoice_id THEN
        RAISE EXCEPTION 'a line stays on its e-invoice'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_draft_only';
    END IF;
    -- An original invoices what was delivered: nothing below zero. An adjustment (E4) may reduce (TT 91 Điều 10 khoản 6 điểm c).
    IF TG_OP <> 'DELETE' AND invoice_type = 'ORIGINAL' AND NEW.quantity < 0 THEN
        RAISE EXCEPTION 'an original e-invoice has no line below zero'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_sign';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_line_guard
    BEFORE INSERT OR UPDATE OR DELETE ON invoicing.einvoice_line
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_line_guard();

-- The totals are the lines added up, and an invoice has lines. Checked when the transaction commits: the application
-- writes an invoice and its lines together.
CREATE FUNCTION invoicing.einvoice_totals_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    invoice_id UUID;
    invoice    invoicing.einvoice%ROWTYPE;
    line_count INTEGER;
    net        NUMERIC;
    tax        NUMERIC;
BEGIN
    -- One function for both tables, so each branch names only the columns its own row has.
    IF TG_TABLE_NAME = 'einvoice' THEN
        invoice_id := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        invoice_id := OLD.einvoice_id;
    ELSE
        invoice_id := NEW.einvoice_id;
    END IF;
    SELECT * INTO invoice FROM invoicing.einvoice WHERE id = invoice_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT count(*), COALESCE(sum(amount), 0), COALESCE(sum(tax_amount), 0) INTO line_count, net, tax
      FROM invoicing.einvoice_line WHERE einvoice_id = invoice_id;
    IF line_count = 0 THEN
        RAISE EXCEPTION 'e-invoice % has no lines', invoice.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_has_lines';
    END IF;
    IF invoice.subtotal <> net OR invoice.tax_total <> tax THEN
        RAISE EXCEPTION 'e-invoice %: totals % + % are not its lines'' % + %', invoice.code, invoice.subtotal,
            invoice.tax_total, net, tax
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_totals';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_einvoice_totals
    AFTER INSERT OR UPDATE ON invoicing.einvoice
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_totals_check();

CREATE CONSTRAINT TRIGGER tg_einvoice_line_totals
    AFTER INSERT OR UPDATE OR DELETE ON invoicing.einvoice_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_totals_check();

-- Calls to the provider ----------------------------------------------------------------------------------------------
CREATE TABLE invoicing.einvoice_provider_call
(
    id             UUID         NOT NULL,
    einvoice_id    UUID         NOT NULL,
    provider       VARCHAR(32)  NOT NULL,
    transaction_id VARCHAR(64)  NOT NULL,
    operation      VARCHAR(16)  NOT NULL,
    outcome        VARCHAR(16)  NOT NULL,
    http_status    INTEGER,
    error_code     VARCHAR(64),
    request_hash   VARCHAR(64),
    response_hash  VARCHAR(64),
    called_at      TIMESTAMPTZ  NOT NULL,
    duration_ms    BIGINT       NOT NULL,

    CONSTRAINT pk_einvoice_provider_call PRIMARY KEY (id),
    CONSTRAINT fk_einvoice_provider_call_einvoice FOREIGN KEY (einvoice_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT ck_einvoice_provider_call_operation CHECK (operation IN ('SUBMIT', 'QUERY', 'DOWNLOAD')),
    CONSTRAINT ck_einvoice_provider_call_outcome CHECK (outcome IN ('ACCEPTED', 'DUPLICATE', 'PENDING', 'ISSUED',
                                                                    'REJECTED', 'NOT_FOUND', 'DOWNLOADED', 'TIMEOUT',
                                                                    'ERROR')),
    CONSTRAINT ck_einvoice_provider_call_hashes CHECK ((request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$')
        AND (response_hash IS NULL OR response_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_einvoice_provider_call_duration CHECK (duration_ms >= 0)
);

CREATE INDEX ix_einvoice_provider_call_einvoice ON invoicing.einvoice_provider_call (einvoice_id, called_at);

COMMENT ON TABLE invoicing.einvoice_provider_call IS
    'WF-05 E2 (kltn-docs 21 BR-10): one row per call to the e-invoice provider - hashes only, never a payload, a secret or a signed link.';

CREATE FUNCTION invoicing.append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME
        USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_' || TG_TABLE_NAME || '_append_only';
END
$$;

CREATE TRIGGER tg_einvoice_provider_call_append_only
    BEFORE UPDATE OR DELETE ON invoicing.einvoice_provider_call
    FOR EACH ROW EXECUTE FUNCTION invoicing.append_only();

-- "Invoice drawn up wrongly" notices (E4) ------------------------------------------------------------------------------
CREATE TABLE invoicing.einvoice_error_notice
(
    id               UUID         NOT NULL,
    einvoice_id      UUID         NOT NULL,
    -- Mẫu 04/SS-HĐĐT; the tax authority answers with Mẫu 01/TB-SSĐT (TT 91 Điều 10 khoản 4).
    form_code        VARCHAR(16)  NOT NULL DEFAULT '04/SS-HDDT',
    reason           VARCHAR(500) NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    sent_at          TIMESTAMPTZ,
    response_code    VARCHAR(64),
    response_message VARCHAR(500),
    responded_at     TIMESTAMPTZ,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_einvoice_error_notice PRIMARY KEY (id),
    CONSTRAINT fk_einvoice_error_notice_einvoice FOREIGN KEY (einvoice_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT ck_einvoice_error_notice_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_einvoice_error_notice_status CHECK (status IN ('DRAFT', 'SENT', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_einvoice_error_notice_sent CHECK ((status = 'DRAFT') = (sent_at IS NULL)),
    CONSTRAINT ck_einvoice_error_notice_response CHECK ((status IN ('ACCEPTED', 'REJECTED')) = (responded_at IS NOT NULL))
);

CREATE INDEX ix_einvoice_error_notice_einvoice ON invoicing.einvoice_error_notice (einvoice_id);

COMMENT ON TABLE invoicing.einvoice_error_notice IS
    'WF-05 E2 (place kept for E4; TT 91 Điều 10 khoản 1 điểm a): a 04/SS-HĐĐT notice that an issued invoice names the buyer wrongly, and the tax authority''s answer; no new invoice.';

-- Only an invoice that was issued is notified as drawn up wrongly.
CREATE FUNCTION invoicing.einvoice_error_notice_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM invoicing.einvoice WHERE id = NEW.einvoice_id AND status IN ('ISSUED', 'REPLACED')) THEN
        RAISE EXCEPTION 'only an issued e-invoice is notified as drawn up wrongly'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_error_notice_issued';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_error_notice_guard
    BEFORE INSERT ON invoicing.einvoice_error_notice
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_error_notice_guard();

-- The sandbox provider's books ---------------------------------------------------------------------------------------
CREATE TABLE invoicing.sandbox_number
(
    template_code VARCHAR(1) NOT NULL,
    symbol        VARCHAR(6) NOT NULL,
    last_number   INTEGER    NOT NULL DEFAULT 0,

    CONSTRAINT pk_sandbox_number PRIMARY KEY (template_code, symbol),
    CONSTRAINT ck_sandbox_number CHECK (last_number BETWEEN 0 AND 99999999)
);

CREATE TABLE invoicing.sandbox_issuance
(
    transaction_id     VARCHAR(64)  NOT NULL,
    state              VARCHAR(16)  NOT NULL,
    template_code      VARCHAR(1)   NOT NULL,
    symbol             VARCHAR(6)   NOT NULL,
    invoice_number     INTEGER,
    tax_authority_code VARCHAR(64),
    lookup_code        VARCHAR(64),
    rejection_code     VARCHAR(64),
    rejection_message  VARCHAR(500),
    -- The invoice as the sandbox "signed" it: the XML and the PDF it hands back for an issued transaction. And the
    -- SHA-256 of what was submitted, by which a second submission under the same transaction id is told from a
    -- different invoice.
    xml_document       TEXT,
    pdf_document       BYTEA,
    request_hash       VARCHAR(64)  NOT NULL,
    received_at        TIMESTAMPTZ  NOT NULL,
    -- When the simulated signing and the tax authority's answer are done: before it the transaction is PROCESSING.
    ready_at           TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_sandbox_issuance PRIMARY KEY (transaction_id),
    CONSTRAINT uk_sandbox_issuance_number UNIQUE (template_code, symbol, invoice_number),
    CONSTRAINT ck_sandbox_issuance_state CHECK (state IN ('ISSUED', 'REJECTED')),
    CONSTRAINT ck_sandbox_issuance_outcome CHECK (
        (state = 'ISSUED' AND invoice_number BETWEEN 1 AND 99999999 AND tax_authority_code IS NOT NULL
            AND lookup_code IS NOT NULL AND rejection_code IS NULL AND xml_document IS NOT NULL
            AND pdf_document IS NOT NULL)
        OR (state = 'REJECTED' AND invoice_number IS NULL AND rejection_code IS NOT NULL AND xml_document IS NULL
            AND pdf_document IS NULL))
);

COMMENT ON TABLE invoicing.sandbox_issuance IS
    'WF-05 E2: the sandbox e-invoice provider''s own record of each transaction it accepted - what a real provider keeps on its side. No legal value.';

-- Permissions --------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), r.resource || ':' || a.action, r.resource, a.action, 0, NOW(), 'flyway'
FROM (VALUES
          ('einvoices',         'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE,EXPORT'),
          ('einvoice-settings', 'READ,UPDATE')
     ) AS r(resource, actions)
CROSS JOIN LATERAL unnest(string_to_array(r.actions, ',')) AS a(action)
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, resource, actions) AS (
    VALUES
        -- kltn-docs 21 §2, BR-02: the accountant reads and corrects drafts, issues (APPROVE), and exports the register.
        ('ACCOUNTANT',    'einvoices',         'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE,EXPORT'),
        ('ACCOUNTANT',    'einvoice-settings', 'READ'),
        -- Plan §1.4: the Sales Manager only reads.
        ('SALES_MANAGER', 'einvoices',         'VIEW_PAGE,READ'),
        -- Holds every permission (V20260929000200); the provider connection is the administrator's to configure (21 §2).
        ('SYSTEM_ADMIN',  'einvoices',         'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE,EXPORT'),
        ('SYSTEM_ADMIN',  'einvoice-settings', 'READ,UPDATE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission
        ON permission.resource = wanted.resource
       AND permission.action = ANY (string_to_array(wanted.actions, ','))
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
