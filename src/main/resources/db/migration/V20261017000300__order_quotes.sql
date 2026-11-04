-- =============================================================================
-- Ordering: B2B quotes (SCRUM-426 / 448). Docs: 03-state-machines §18, 04c F-CART steps 01-07,
-- 05-business-rules BR-ORD-007/008, plan 2026-10-06 §2 (D2, D4).
--
-- A quote offers a known customer printed blanks: per line a blank SKU, the confirmed design to print
-- and a print specification, with quantity tiers (the lowest tier is the MOQ) and a payment term, valid
-- until a date. The customer accepts it on the portal - only once every new design has a sample they
-- approved - and an accepted quote becomes exactly one sales order.
--
--   DRAFT -> SENT -> ACCEPTED -> CONVERTED          SENT -> CHANGES_REQUESTED -> DRAFT (revision + 1)
--   SENT -> EXPIRED (validity ends)                  DRAFT -> CANCELLED
--
-- Prices are VND per unit, whole dong (order lines are VND too). Every invariant the aggregate checks is
-- stated again here as a CHECK. The commercial content of every revision that was sent is kept on its
-- history row (offer, JSONB), so "what did the customer accept" survives later revisions.
--
-- Replaces the single-line design quote of PR #38 (commit 01bcf6e: design_quote / design_quote_offer /
-- design_quote_issue), which never reached develop's tree; its rules carry over (customer assent apart
-- from issuance, immutable accepted terms, request-id idempotent creation, derived expiry).
-- =============================================================================

CREATE TABLE ordering.quote
(
    id                  UUID          NOT NULL,
    quote_number        VARCHAR(30)   NOT NULL,
    -- Idempotency of creation: a retried "create quote" names the same request and gets the same quote.
    request_id          UUID          NOT NULL,
    customer_id         UUID          NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    revision            INTEGER       NOT NULL DEFAULT 1,
    payment_term        VARCHAR(16)   NOT NULL,
    -- DEPOSIT only: the share of the total due before production; null takes the customer's default.
    deposit_percent     NUMERIC(5, 2),
    valid_until         TIMESTAMPTZ   NOT NULL,
    terms               VARCHAR(2000),
    sent_at             TIMESTAMPTZ,
    sent_by             UUID,
    responded_at        TIMESTAMPTZ,
    responded_by        UUID,
    customer_feedback   VARCHAR(2000),
    cancellation_reason VARCHAR(500),
    converted_order_id  UUID,
    converted_at        TIMESTAMPTZ,

    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_quote PRIMARY KEY (id),
    CONSTRAINT uk_quote_number UNIQUE (quote_number),
    CONSTRAINT uk_quote_request UNIQUE (request_id),
    -- One quote, one order (BR "accepted quote -> sales order" once).
    CONSTRAINT uk_quote_converted_order UNIQUE (converted_order_id),
    CONSTRAINT fk_quote_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_quote_sent_by FOREIGN KEY (sent_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_quote_responded_by FOREIGN KEY (responded_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_quote_converted_order FOREIGN KEY (converted_order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT ck_quote_status CHECK (status IN
        ('DRAFT', 'SENT', 'CHANGES_REQUESTED', 'ACCEPTED', 'EXPIRED', 'CANCELLED', 'CONVERTED')),
    CONSTRAINT ck_quote_revision CHECK (revision >= 1),
    CONSTRAINT ck_quote_payment_term CHECK (payment_term IN ('PREPAID', 'DEPOSIT', 'CREDIT')),
    CONSTRAINT ck_quote_deposit CHECK (deposit_percent IS NULL
        OR (payment_term = 'DEPOSIT' AND deposit_percent > 0 AND deposit_percent < 100)),
    CONSTRAINT ck_quote_validity CHECK (valid_until > created_at),
    -- Everything after DRAFT was sent at some point, by someone.
    CONSTRAINT ck_quote_sent CHECK (status IN ('DRAFT', 'CANCELLED')
        OR (sent_at IS NOT NULL AND sent_by IS NOT NULL)),
    -- An answer says who gave it and when; asking for changes says what to change.
    CONSTRAINT ck_quote_response CHECK (status NOT IN ('CHANGES_REQUESTED', 'ACCEPTED', 'CONVERTED')
        OR (responded_at IS NOT NULL AND responded_by IS NOT NULL)),
    CONSTRAINT ck_quote_feedback CHECK (status <> 'CHANGES_REQUESTED' OR customer_feedback IS NOT NULL),
    CONSTRAINT ck_quote_cancelled CHECK ((status = 'CANCELLED') = (cancellation_reason IS NOT NULL)),
    CONSTRAINT ck_quote_converted CHECK ((status = 'CONVERTED')
        = (converted_order_id IS NOT NULL AND converted_at IS NOT NULL))
);

CREATE INDEX ix_quote_customer ON ordering.quote (customer_id, created_at DESC);
-- The expiry sweep: sent quotes past their validity.
CREATE INDEX ix_quote_sent_validity ON ordering.quote (valid_until) WHERE status = 'SENT';


CREATE TABLE ordering.quote_line
(
    id                  UUID          NOT NULL,
    quote_id            UUID          NOT NULL,
    line_no             INTEGER       NOT NULL,
    blank_sku           VARCHAR(64)   NOT NULL,
    design_snapshot_id  UUID          NOT NULL,
    -- How it is printed: colours, position, method - what the design file alone does not say.
    print_spec          VARCHAR(2000),
    -- What Sales proposes; the customer may accept another quantity at or above the MOQ.
    quantity            INTEGER       NOT NULL,
    accepted_quantity   INTEGER,
    accepted_unit_price NUMERIC(19, 4),

    CONSTRAINT pk_quote_line PRIMARY KEY (id),
    CONSTRAINT uk_quote_line_no UNIQUE (quote_id, line_no),
    CONSTRAINT fk_quote_line_quote FOREIGN KEY (quote_id) REFERENCES ordering.quote (id) ON DELETE CASCADE,
    CONSTRAINT fk_quote_line_sku FOREIGN KEY (blank_sku)
        REFERENCES product.variants (sku) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_quote_line_snapshot FOREIGN KEY (design_snapshot_id)
        REFERENCES design.design_snapshot (id) ON DELETE RESTRICT,
    CONSTRAINT ck_quote_line_no CHECK (line_no >= 1),
    CONSTRAINT ck_quote_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_quote_line_accepted CHECK ((accepted_quantity IS NULL) = (accepted_unit_price IS NULL)),
    CONSTRAINT ck_quote_line_accepted_values CHECK (accepted_quantity IS NULL
        OR (accepted_quantity > 0 AND accepted_unit_price > 0 AND accepted_unit_price = TRUNC(accepted_unit_price)))
);

CREATE INDEX ix_quote_line_quote ON ordering.quote_line (quote_id);


-- Price breaks: from min_quantity units up, this unit price. The lowest min_quantity is the line's MOQ.
CREATE TABLE ordering.quote_price_tier
(
    quote_line_id UUID           NOT NULL,
    min_quantity  INTEGER        NOT NULL,
    unit_price    NUMERIC(19, 4) NOT NULL,

    CONSTRAINT pk_quote_price_tier PRIMARY KEY (quote_line_id, min_quantity),
    CONSTRAINT fk_quote_price_tier_line FOREIGN KEY (quote_line_id)
        REFERENCES ordering.quote_line (id) ON DELETE CASCADE,
    CONSTRAINT ck_quote_price_tier_quantity CHECK (min_quantity > 0),
    CONSTRAINT ck_quote_price_tier_price CHECK (unit_price > 0 AND unit_price = TRUNC(unit_price))
);


-- Every transition. A SENT row carries the offer as sent (lines, tiers, term, validity) so a later
-- revision cannot rewrite what the customer saw.
CREATE TABLE ordering.quote_status_history
(
    id          UUID          NOT NULL,
    quote_id    UUID          NOT NULL,
    revision    INTEGER       NOT NULL,
    from_status VARCHAR(20),
    to_status   VARCHAR(20)   NOT NULL,
    reason      VARCHAR(2000),
    offer       JSONB,
    changed_at  TIMESTAMPTZ   NOT NULL,
    changed_by  UUID,

    CONSTRAINT pk_quote_status_history PRIMARY KEY (id),
    CONSTRAINT fk_quote_status_history_quote FOREIGN KEY (quote_id) REFERENCES ordering.quote (id) ON DELETE CASCADE,
    CONSTRAINT fk_quote_status_history_by FOREIGN KEY (changed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_quote_status_history_offer CHECK (to_status <> 'SENT' OR offer IS NOT NULL)
);

CREATE INDEX ix_quote_status_history_quote ON ordering.quote_status_history (quote_id, changed_at);


-- production.sample_request.quote_id was reserved for this table (V20261010000200). The sample API refused
-- every quote id until now, so a database has none and the key is validated at once. Should a row carry
-- one written by hand, it can only point at nothing (this table is new): the key then stays NOT VALID -
-- still enforced for every new row - and tools/db/orphan_check.sql reports the row to clean before
-- db/pending/C0 validates keys, rather than this migration failing a deploy.
ALTER TABLE production.sample_request
    ADD CONSTRAINT fk_sample_request_quote FOREIGN KEY (quote_id)
        REFERENCES ordering.quote (id) ON DELETE RESTRICT NOT VALID;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM production.sample_request WHERE quote_id IS NOT NULL) THEN
        ALTER TABLE production.sample_request VALIDATE CONSTRAINT fk_sample_request_quote;
    ELSE
        RAISE NOTICE 'fk_sample_request_quote left NOT VALID: sample requests name quotes that do not exist';
    END IF;
END $$;


-- Who may do what with quotes. Resource sales-quotes, declared by the order module's QuoteController.
--   SALES_STAFF        drafts, revises, sends, cancels and converts (CREATE, UPDATE)
--   ORDER_COORDINATOR  follows quotes (READ) and converts an accepted one (sales-quote-conversions:CREATE); it
--                      never edits a quote (decision 2026-10-17)
--   sales-quote-conversions:CREATE - converting an accepted quote: SALES_STAFF, ORDER_COORDINATOR, SYSTEM_ADMIN
--   CUSTOMER           reads the quotes sent to them and answers (APPROVE) - the answer is the
--                      customer's own (03 §18: accept is "Customer (portal)"), so no staff role holds it
--   SYSTEM_ADMIN       everything, as for every permission (V20260929000200)
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'sales-quotes:' || a.action, 'sales-quotes', a.action, 0, NOW(), 'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE', 'UPDATE', 'APPROVE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('SALES_STAFF',       'VIEW_PAGE,READ,CREATE,UPDATE'),
          ('ORDER_COORDINATOR', 'VIEW_PAGE,READ'),
          ('CUSTOMER',          'VIEW_PAGE,READ,APPROVE'),
          ('SYSTEM_ADMIN',      'VIEW_PAGE,READ,CREATE,UPDATE,APPROVE')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'sales-quotes'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-quote-conversions:CREATE', 'sales-quote-conversions', 'CREATE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.code = 'sales-quote-conversions:CREATE'
WHERE role.code IN ('SALES_STAFF', 'ORDER_COORDINATOR', 'SYSTEM_ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('SALES_STAFF', 'ORDER_COORDINATOR', 'CUSTOMER', 'SYSTEM_ADMIN');
