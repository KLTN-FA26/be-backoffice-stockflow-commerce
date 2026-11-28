-- =============================================================================
-- Contract content, terms review, revisions and the signing deadline (contract e-signing plan of 2026-11-28, API
-- contract HOP-DONG-API.md "Contract content (BE-A)" and "Lifecycle"). Module: ordering.
--
--   * CUSTOMER_SIGNED: the customer signed on the portal and the company has not. Its content is locked like SENT's;
--     the order still reacts only to SIGNED. Edges (also in SalesContractStatus): DRAFT>SENT|CANCELLED,
--     SENT>CUSTOMER_SIGNED|SIGNED|CANCELLED|DRAFT (DRAFT = the customer asked for changes, revision + 1),
--     CUSTOMER_SIGNED>SIGNED|CANCELLED, SIGNED>COMPLETED|LIQUIDATED.
--   * Content: special terms, the penalty / tolerance / warranty overrides (null = the configured default), the seller's
--     and the buyer's signers. non_standard_terms is what the contract was judged to be when it was last edited against
--     the configured defaults: special terms always make it so, an override only when it differs from the default.
--   * Terms review (QĐ-4): a non-standard draft is approved by a Sales Manager before it can be sent. Stated here as
--     "a contract that left the draft while non-standard was APPROVED"; the service and the aggregate say it first.
--   * Revisions: ordering.sales_contract_revision keeps the PDF each revision was sent as, with the customer's change
--     request; sign_deadline_notified_at makes the nightly "deadline passed" notice once per revision.
--   * sign_by, customer_signed_at, drawn_up_by (the four-eyes rule of the company signature needs who drew it up).
--
-- check_order_contract is rewritten: an order waits in PENDING_CONTRACT for a contract that is SENT or CUSTOMER_SIGNED.
-- It was not touched by the earlier contract migrations, so nothing else mentions the new status.
-- =============================================================================

ALTER TABLE ordering.sales_contract
    ADD COLUMN special_terms text,
    ADD COLUMN penalty_percent integer,
    ADD COLUMN quantity_tolerance_percent integer,
    ADD COLUMN warranty_months integer,
    ADD COLUMN seller_signer_name character varying(200),
    ADD COLUMN seller_signer_title character varying(200),
    ADD COLUMN seller_signer_authorization_ref character varying(100),
    ADD COLUMN buyer_signer_name character varying(200),
    ADD COLUMN buyer_signer_title character varying(200),
    ADD COLUMN non_standard_terms boolean DEFAULT false NOT NULL,
    ADD COLUMN revision integer DEFAULT 1 NOT NULL,
    ADD COLUMN terms_review character varying(16) DEFAULT 'NONE' NOT NULL,
    ADD COLUMN terms_review_note character varying(500),
    ADD COLUMN terms_review_requested_by uuid,
    ADD COLUMN terms_review_requested_at timestamp with time zone,
    ADD COLUMN terms_reviewed_by uuid,
    ADD COLUMN terms_reviewed_at timestamp with time zone,
    ADD COLUMN sign_by date,
    ADD COLUMN customer_signed_at timestamp with time zone,
    ADD COLUMN drawn_up_by uuid;

ALTER TABLE ordering.sales_contract
    ADD CONSTRAINT fk_sales_contract_terms_review_requested_by FOREIGN KEY (terms_review_requested_by)
        REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_sales_contract_terms_reviewed_by FOREIGN KEY (terms_reviewed_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_sales_contract_drawn_up_by FOREIGN KEY (drawn_up_by) REFERENCES identity.app_user (id);

-- The status: CUSTOMER_SIGNED has a sent PDF and no company signature (signed_at stays null for it).
ALTER TABLE ordering.sales_contract DROP CONSTRAINT ck_sales_contract_status;
ALTER TABLE ordering.sales_contract ADD CONSTRAINT ck_sales_contract_status CHECK (
    status IN ('DRAFT', 'SENT', 'CUSTOMER_SIGNED', 'SIGNED', 'COMPLETED', 'CANCELLED', 'LIQUIDATED'));
ALTER TABLE ordering.sales_contract DROP CONSTRAINT ck_sales_contract_sent;
ALTER TABLE ordering.sales_contract ADD CONSTRAINT ck_sales_contract_sent CHECK (
    (sent_at IS NULL) = (sent_by IS NULL)
    AND (sent_at IS NULL) = (sent_document_key IS NULL)
    AND (status <> 'DRAFT' OR sent_at IS NULL)
    AND (status NOT IN ('SENT', 'CUSTOMER_SIGNED', 'SIGNED', 'COMPLETED', 'LIQUIDATED') OR sent_at IS NOT NULL));

-- Content.
ALTER TABLE ordering.sales_contract
    ADD CONSTRAINT ck_sales_contract_special_terms CHECK (
        special_terms IS NULL OR (btrim(special_terms) <> '' AND char_length(special_terms) <= 4000)),
    ADD CONSTRAINT ck_sales_contract_penalty_percent CHECK (penalty_percent IS NULL OR penalty_percent BETWEEN 0 AND 8),
    ADD CONSTRAINT ck_sales_contract_tolerance_percent CHECK (
        quantity_tolerance_percent IS NULL OR quantity_tolerance_percent BETWEEN 0 AND 10),
    ADD CONSTRAINT ck_sales_contract_warranty_months CHECK (warranty_months IS NULL OR warranty_months BETWEEN 0 AND 36),
    ADD CONSTRAINT ck_sales_contract_signers CHECK (
        (seller_signer_name IS NULL OR btrim(seller_signer_name) <> '')
        AND (seller_signer_title IS NULL OR btrim(seller_signer_title) <> '')
        AND (seller_signer_authorization_ref IS NULL OR btrim(seller_signer_authorization_ref) <> '')
        AND (buyer_signer_name IS NULL OR btrim(buyer_signer_name) <> '')
        AND (buyer_signer_title IS NULL OR btrim(buyer_signer_title) <> '')
        -- A title or an authorization says who is signing: no name, no title and no paper.
        AND (seller_signer_name IS NOT NULL
             OR (seller_signer_title IS NULL AND seller_signer_authorization_ref IS NULL))
        AND (buyer_signer_name IS NOT NULL OR buyer_signer_title IS NULL)),
    ADD CONSTRAINT ck_sales_contract_non_standard CHECK (special_terms IS NULL OR non_standard_terms),
    ADD CONSTRAINT ck_sales_contract_revision CHECK (revision >= 1);

-- Terms review (QĐ-4).
ALTER TABLE ordering.sales_contract
    ADD CONSTRAINT ck_sales_contract_terms_review_state CHECK (
        terms_review IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED')),
    -- Only a non-standard contract is ever put in front of the Sales Manager, and it waits or is refused as a draft.
    ADD CONSTRAINT ck_sales_contract_terms_review CHECK (
        (terms_review = 'NONE' OR non_standard_terms)
        AND (terms_review <> 'PENDING' OR status = 'DRAFT')
        AND (terms_review <> 'REJECTED' OR status IN ('DRAFT', 'CANCELLED'))),
    ADD CONSTRAINT ck_sales_contract_terms_review_decided CHECK (
        (terms_review IN ('APPROVED', 'REJECTED')) = (terms_reviewed_by IS NOT NULL)
        AND (terms_reviewed_by IS NULL) = (terms_reviewed_at IS NULL)
        AND (terms_review IN ('APPROVED', 'REJECTED') OR terms_review_note IS NULL)
        AND (terms_review <> 'REJECTED' OR (terms_review_note IS NOT NULL AND btrim(terms_review_note) <> ''))
        AND (terms_review_note IS NULL OR btrim(terms_review_note) <> '')),
    ADD CONSTRAINT ck_sales_contract_terms_review_requested CHECK (
        (terms_review_requested_by IS NULL) = (terms_review_requested_at IS NULL)
        AND (terms_review <> 'PENDING' OR terms_review_requested_by IS NOT NULL)
        AND (terms_review <> 'NONE' OR terms_review_requested_by IS NULL)),
    -- What the customer is sent and signs as non-standard was approved: the database's half of the rule the aggregate
    -- states when it is sent (an edit of an approved draft resets the approval, so it cannot drift).
    ADD CONSTRAINT ck_sales_contract_sent_terms_approved CHECK (
        status NOT IN ('SENT', 'CUSTOMER_SIGNED', 'SIGNED', 'COMPLETED', 'LIQUIDATED')
        OR NOT non_standard_terms OR terms_review = 'APPROVED');

-- The signing deadline and the customer's signature.
ALTER TABLE ordering.sales_contract
    ADD CONSTRAINT ck_sales_contract_sign_by CHECK (
        sign_by IS NULL
        OR (status <> 'DRAFT' AND sent_at IS NOT NULL
            AND sign_by >= (sent_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)),
    ADD CONSTRAINT ck_sales_contract_customer_signed CHECK (
        (status <> 'CUSTOMER_SIGNED' OR customer_signed_at IS NOT NULL)
        -- The signature and the state change together: a contract still SENT has none, one signed by both keeps it.
        AND (customer_signed_at IS NULL OR (status NOT IN ('DRAFT', 'SENT') AND sent_at IS NOT NULL
                                            AND customer_signed_at >= sent_at)));

COMMENT ON COLUMN ordering.sales_contract.special_terms IS
    'The article "Thoả thuận khác" of this contract only; null when there is none (at most 4000 characters).';
COMMENT ON COLUMN ordering.sales_contract.penalty_percent IS
    'The penalty for a breach, 0..8 (Luật Thương mại 2005 Điều 301); null = stockflow.order.contract.penalty-percent.';
COMMENT ON COLUMN ordering.sales_contract.quantity_tolerance_percent IS
    'Quantity tolerance of a printed run, 0..10; null = stockflow.order.contract.quantity-tolerance-percent.';
COMMENT ON COLUMN ordering.sales_contract.warranty_months IS
    'Warranty of the print in months, 0..36; null = stockflow.order.contract.warranty-months.';
COMMENT ON COLUMN ordering.sales_contract.non_standard_terms IS
    'Whether the contract deviated from the configured standard terms when it was last edited (special terms, or an '
    'override that differs from the default): such a contract is approved by a Sales Manager before it is sent.';
COMMENT ON COLUMN ordering.sales_contract.revision IS
    'Starts at 1; a change request of the customer reopens the contract as the next revision.';
COMMENT ON COLUMN ordering.sales_contract.terms_review IS
    'NONE, PENDING (waiting for a Sales Manager), APPROVED or REJECTED: only for a non-standard draft.';
COMMENT ON COLUMN ordering.sales_contract.sign_by IS
    'The day the customer should have signed by; set when sent, extended by Sales while SENT. Nothing is cancelled when it passes.';
COMMENT ON COLUMN ordering.sales_contract.customer_signed_at IS
    'When the customer signed (CUSTOMER_SIGNED); the company signs after, and SIGNED is when it is in force.';
COMMENT ON COLUMN ordering.sales_contract.drawn_up_by IS
    'The account that drew the contract up; it may not sign for the company (four eyes). Null on contracts drawn up before.';

CREATE INDEX ix_sales_contract_status ON ordering.sales_contract (status);
CREATE INDEX ix_sales_contract_overdue_to_sign ON ordering.sales_contract (sign_by) WHERE status = 'SENT';
CREATE INDEX ix_sales_contract_terms_pending ON ordering.sales_contract (created_at) WHERE terms_review = 'PENDING';
CREATE INDEX ix_sales_contract_created ON ordering.sales_contract (created_at DESC, id);

-- Revisions: what each revision was sent as, and what the customer asked for after reading it.
CREATE TABLE ordering.sales_contract_revision (
    contract_id uuid NOT NULL,
    revision integer NOT NULL,
    sent_document_key character varying(512) NOT NULL,
    sent_at timestamp with time zone NOT NULL,
    sent_by uuid NOT NULL,
    change_request_reason character varying(2000),
    change_requested_at timestamp with time zone,
    change_requested_by uuid,
    sign_deadline_notified_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    created_by character varying(100),
    last_modified_at timestamp with time zone,
    last_modified_by character varying(100),
    CONSTRAINT pk_sales_contract_revision PRIMARY KEY (contract_id, revision),
    CONSTRAINT fk_sales_contract_revision_contract FOREIGN KEY (contract_id)
        REFERENCES ordering.sales_contract (id) ON DELETE CASCADE,
    CONSTRAINT fk_sales_contract_revision_sent_by FOREIGN KEY (sent_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_sales_contract_revision_change_requested_by FOREIGN KEY (change_requested_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_sales_contract_revision_number CHECK (revision >= 1),
    CONSTRAINT ck_sales_contract_revision_change CHECK (
        (change_request_reason IS NULL) = (change_requested_at IS NULL)
        AND (change_request_reason IS NULL) = (change_requested_by IS NULL)
        AND (change_request_reason IS NULL OR char_length(btrim(change_request_reason)) BETWEEN 10 AND 2000)
        AND (change_requested_at IS NULL OR change_requested_at >= sent_at))
);

COMMENT ON TABLE ordering.sales_contract_revision IS
    'The PDF each revision of a contract was sent as, with the customer''s change request that ended it (if any).';

-- Written once: the PDF that was sent and who sent it, and a change request once made.
CREATE TRIGGER tg_sales_contract_revision_kept BEFORE UPDATE ON ordering.sales_contract_revision
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('contract_id', 'revision', 'sent_document_key', 'sent_at',
        'sent_by', 'change_request_reason', 'change_requested_at', 'change_requested_by', 'sign_deadline_notified_at');

-- Contracts sent before revisions existed are their own revision 1.
INSERT INTO ordering.sales_contract_revision (contract_id, revision, sent_document_key, sent_at, sent_by, created_at,
                                              created_by)
SELECT k.id, 1, k.sent_document_key, k.sent_at, k.sent_by, k.sent_at, 'flyway'
  FROM ordering.sales_contract k
 WHERE k.sent_at IS NOT NULL;

-- The order waits for a contract that is sent and not yet signed by the company.
CREATE OR REPLACE FUNCTION ordering.check_order_contract() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    the_order    UUID;
    order_status VARCHAR(32);
    order_number VARCHAR(32);
    contract     VARCHAR(16);
BEGIN
    -- IF, not CASE: PL/pgSQL resolves every field a CASE names on NEW, and each table has only one of the two.
    IF TG_TABLE_NAME = 'customer_order' THEN
        the_order := NEW.id;
    ELSE
        the_order := NEW.order_id;
    END IF;
    SELECT o.status, o.order_number INTO order_status, order_number
      FROM ordering.customer_order o WHERE o.id = the_order;
    SELECT c.status INTO contract
      FROM ordering.sales_contract c WHERE c.order_id = the_order AND c.status <> 'CANCELLED';
    IF contract IN ('SIGNED', 'COMPLETED') AND order_status IN ('DRAFT', 'PENDING_CONTRACT') THEN
        RAISE EXCEPTION 'Order % has a signed contract, but is still % (14 §4.3 step 15)', order_number,
            order_status USING ERRCODE = 'check_violation';
    END IF;
    IF order_status = 'PENDING_CONTRACT' AND (contract IS NULL OR contract NOT IN ('SENT', 'CUSTOMER_SIGNED')) THEN
        RAISE EXCEPTION 'Order % waits for its contract to be signed, but has no contract sent (14 §5.3)',
            order_number USING ERRCODE = 'check_violation';
    END IF;
    IF contract IN ('SENT', 'CUSTOMER_SIGNED') AND order_status <> 'PENDING_CONTRACT' THEN
        RAISE EXCEPTION 'Order % has a contract sent for signing, but is % (14 §5.3)', order_number, order_status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION ordering.check_sales_contract_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    reopened boolean := OLD.status = 'SENT' AND NEW.status = 'DRAFT';
BEGIN
    IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.contract_number IS DISTINCT FROM OLD.contract_number THEN
        RAISE EXCEPTION 'sales contract %: a contract keeps its number and stays with its order', OLD.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.drawn_up_by IS DISTINCT FROM OLD.drawn_up_by THEN
        RAISE EXCEPTION 'sales contract %: who drew it up does not change', OLD.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    -- The revision moves only when the customer's change request reopens a sent contract, and by one.
    IF NEW.revision IS DISTINCT FROM OLD.revision
       AND NOT (reopened AND NEW.revision = OLD.revision + 1) THEN
        RAISE EXCEPTION 'sales contract %: revision % -> % is not a change request (only a sent contract reopens)',
            OLD.contract_number, OLD.revision, NEW.revision USING ERRCODE = 'check_violation';
    END IF;
    IF reopened AND NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'sales contract %: reopened for changes, it is the next revision', OLD.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'DRAFT' AND (NEW.seller_name, NEW.seller_tax_code, NEW.seller_address, NEW.seller_representative,
            NEW.seller_representative_title, NEW.buyer_name, NEW.buyer_tax_code, NEW.buyer_address,
            NEW.buyer_representative, NEW.buyer_representative_title, NEW.free_sample_rounds, NEW.return_window_days,
            NEW.special_terms, NEW.penalty_percent, NEW.quantity_tolerance_percent, NEW.warranty_months,
            NEW.seller_signer_name, NEW.seller_signer_title, NEW.seller_signer_authorization_ref,
            NEW.buyer_signer_name, NEW.buyer_signer_title, NEW.non_standard_terms)
        IS DISTINCT FROM (OLD.seller_name, OLD.seller_tax_code, OLD.seller_address, OLD.seller_representative,
            OLD.seller_representative_title, OLD.buyer_name, OLD.buyer_tax_code, OLD.buyer_address,
            OLD.buyer_representative, OLD.buyer_representative_title, OLD.free_sample_rounds, OLD.return_window_days,
            OLD.special_terms, OLD.penalty_percent, OLD.quantity_tolerance_percent, OLD.warranty_months,
            OLD.seller_signer_name, OLD.seller_signer_title, OLD.seller_signer_authorization_ref,
            OLD.buyer_signer_name, OLD.buyer_signer_title, OLD.non_standard_terms)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change (14 BR-14)', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    -- What was sent stays as it was, except that a change request takes the contract back (the revision row keeps it).
    IF OLD.status <> 'DRAFT' AND NOT reopened AND (NEW.sent_at, NEW.sent_by, NEW.sent_document_key)
        IS DISTINCT FROM (OLD.sent_at, OLD.sent_by, OLD.sent_document_key)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change (14 BR-14)', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    -- A review is decided on a draft: once the contract is out, what was approved stays.
    IF OLD.status <> 'DRAFT' AND (NEW.terms_review, NEW.terms_review_note, NEW.terms_review_requested_by,
            NEW.terms_review_requested_at, NEW.terms_reviewed_by, NEW.terms_reviewed_at)
        IS DISTINCT FROM (OLD.terms_review, OLD.terms_review_note, OLD.terms_review_requested_by,
            OLD.terms_review_requested_at, OLD.terms_reviewed_by, OLD.terms_reviewed_at)
    THEN
        RAISE EXCEPTION 'sales contract %: the terms review of a % contract does not change', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    -- The deadline is set when it is sent, moved while it is SENT, cleared when it is reopened.
    IF NEW.sign_by IS DISTINCT FROM OLD.sign_by
       AND NOT (OLD.status = 'SENT' OR (OLD.status = 'DRAFT' AND NEW.status = 'SENT')) THEN
        RAISE EXCEPTION 'sales contract %: the signing deadline of a % contract does not change', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.customer_signed_at IS DISTINCT FROM OLD.customer_signed_at AND OLD.status <> 'SENT' THEN
        RAISE EXCEPTION 'sales contract %: the customer signs a sent contract, once', OLD.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('SIGNED', 'COMPLETED', 'LIQUIDATED') AND (NEW.signed_on, NEW.signed_file_key,
            NEW.signed_file_name, NEW.signed_at, NEW.signed_by)
        IS DISTINCT FROM (OLD.signed_on, OLD.signed_file_key, OLD.signed_file_name, OLD.signed_at, OLD.signed_by)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change (14 BR-14)', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('CANCELLED', 'COMPLETED', 'LIQUIDATED') AND (NEW.cancelled_at, NEW.cancelled_by,
            NEW.cancellation_reason, NEW.completed_at, NEW.liquidated_at)
        IS DISTINCT FROM (OLD.cancelled_at, OLD.cancelled_by, OLD.cancellation_reason, OLD.completed_at,
            OLD.liquidated_at)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change', OLD.contract_number, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('SENT', 'CANCELLED'))
         OR (OLD.status = 'SENT' AND NEW.status IN ('CUSTOMER_SIGNED', 'SIGNED', 'CANCELLED', 'DRAFT'))
         OR (OLD.status = 'CUSTOMER_SIGNED' AND NEW.status IN ('SIGNED', 'CANCELLED'))
         OR (OLD.status = 'SIGNED' AND NEW.status IN ('COMPLETED', 'LIQUIDATED'))) THEN
        RAISE EXCEPTION 'sales contract %: % -> % is not a transition (14 §5.2)', OLD.contract_number, OLD.status,
            NEW.status USING ERRCODE = 'check_violation';
    END IF;
    -- L-I4: COMPLETED says goods were delivered; LIQUIDATED that none were.
    IF NEW.status = 'COMPLETED' AND NOT ordering.order_delivered_something(NEW.order_id) THEN
        RAISE EXCEPTION 'sales contract %: its order delivered nothing - it is liquidated, not completed (Q-6)',
            OLD.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = 'LIQUIDATED' AND ordering.order_delivered_something(NEW.order_id) THEN
        RAISE EXCEPTION 'sales contract %: its order delivered goods - it is completed, not liquidated (Q-6)',
            OLD.contract_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
