-- =============================================================================
-- A quote priced too low waits for the Sales Manager (kltn-docs 14 BR-10 as changed on 2026-11-26).
--
-- Until now Sales priced a quote freely and the Sales Manager was told only after it was sent, when a line was more than
-- 10% below the customer's own price (decision D-1, 2026-11-25, QuoteDiscountAlerts). The owner decided on 2026-11-26
-- that the Sales Manager approves before the customer sees it: when Sales sends a quote, each price break of each line is
-- measured against its reference - the customer's own price for the SKU, else the standard price of its variant
-- (V20261230000100) - and a quote with a break more than the threshold below it, or with a line that has no reference,
-- is held for approval instead of sent. Approved, it is sent at once; rejected, it goes back to Sales with the reason.
--
-- The quote stays a DRAFT while it waits, so everything that hides a draft from the customer (the portal, the document
-- list, the chat cards, the history) hides it with no change: ordering.quote.price_review says it waits, and only a
-- DRAFT may (ck_quote_price_review). Each submission is one row of ordering.quote_price_review, with what the approver
-- decided on - every break, its reference and how far below it - kept as it was then: a standard price changed later
-- does not rewrite why a quote was approved. At most one waits per quote; whoever approves or rejects is not whoever
-- submitted (ck_quote_price_review_four_eyes, as a product approval, 01 §2).
--
-- sales-quote-price-approvals:APPROVE is the Sales Manager's and the System Admin's. Every role that gained a grant
-- advances app_role.version in the same transaction (ADR-0008, CLAUDE.md §5).
-- =============================================================================

ALTER TABLE ordering.quote
    ADD COLUMN price_review character varying(16) DEFAULT 'NONE' NOT NULL,
    ADD CONSTRAINT ck_quote_price_review_state CHECK (price_review IN ('NONE', 'PENDING')),
    ADD CONSTRAINT ck_quote_price_review CHECK (price_review = 'NONE' OR status = 'DRAFT');

COMMENT ON COLUMN ordering.quote.price_review IS
    'PENDING while a draft waits for the Sales Manager to approve its prices (14 BR-10); NONE otherwise.';

CREATE TABLE ordering.quote_price_review (
    id uuid NOT NULL,
    quote_id uuid NOT NULL,
    revision integer NOT NULL,
    outcome character varying(16) NOT NULL,
    threshold_percent numeric(5,2) NOT NULL,
    lines jsonb NOT NULL,
    submitted_at timestamp with time zone NOT NULL,
    submitted_by uuid NOT NULL,
    decided_at timestamp with time zone,
    decided_by uuid,
    reason character varying(500),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    created_by character varying(100),
    last_modified_at timestamp with time zone,
    last_modified_by character varying(100),
    CONSTRAINT pk_quote_price_review PRIMARY KEY (id),
    CONSTRAINT fk_quote_price_review_quote FOREIGN KEY (quote_id) REFERENCES ordering.quote (id) ON DELETE CASCADE,
    CONSTRAINT fk_quote_price_review_submitted_by FOREIGN KEY (submitted_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_quote_price_review_decided_by FOREIGN KEY (decided_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_quote_price_review_outcome
        CHECK (outcome IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_quote_price_review_revision CHECK (revision >= 1),
    CONSTRAINT ck_quote_price_review_threshold CHECK (threshold_percent >= 0 AND threshold_percent < 100),
    CONSTRAINT ck_quote_price_review_lines CHECK (jsonb_typeof(lines) = 'array'),
    CONSTRAINT ck_quote_price_review_decided
        CHECK ((outcome = 'PENDING') = (decided_at IS NULL) AND (decided_at IS NULL) = (decided_by IS NULL)),
    CONSTRAINT ck_quote_price_review_reason CHECK (outcome <> 'REJECTED' OR reason IS NOT NULL),
    CONSTRAINT ck_quote_price_review_four_eyes
        CHECK (outcome NOT IN ('APPROVED', 'REJECTED') OR decided_by <> submitted_by)
);

COMMENT ON TABLE ordering.quote_price_review IS
    'One submission of a quote''s prices to the Sales Manager (14 BR-10): every break with its reference, as decided on.';

CREATE UNIQUE INDEX uk_quote_price_review_pending ON ordering.quote_price_review (quote_id)
    WHERE outcome = 'PENDING';
CREATE INDEX ix_quote_price_review_quote ON ordering.quote_price_review (quote_id, submitted_at);
CREATE INDEX ix_quote_price_review_waiting ON ordering.quote (created_at) WHERE price_review = 'PENDING';

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-quote-price-approvals:APPROVE', 'sales-quote-price-approvals', 'APPROVE',
        'Approve or reject the prices of a quote held for approval (14 BR-10)', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH grantee (role_code) AS (
    VALUES ('SYSTEM_ADMIN'), ('SALES_MANAGER')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
      JOIN identity.app_role r ON r.code = g.role_code
      JOIN identity.permission p ON p.code = 'sales-quote-price-approvals:APPROVE'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
