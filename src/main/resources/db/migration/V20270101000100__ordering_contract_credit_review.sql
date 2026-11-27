-- =============================================================================
-- A contract whose credit check fails waits for accounting before it is sent (kltn-docs 15 BR-11 as changed on
-- 2026-11-26, decision QĐ-B of the credit plan).
--
-- Until now sending a contract that pays after delivery checked the customer's credit and only warned: a customer with
-- something overdue, or past the limit with this contract, was sent it anyway, and accounting decided only once it was
-- signed - by holding the order (ON_HOLD CREDIT) and, refusing, cancelling it. The owner decided on 2026-11-26 that
-- accounting decides before the customer sees it: a contract whose check is OVERDUE or OVER_LIMIT is not sent but waits
-- for the accountant. Approved, it is sent at once; refused, it stays a DRAFT for Sales to change the payment plan
-- (more before delivery, less after) and send again. Nothing is cancelled.
--
-- The contract stays a DRAFT while it waits, so everything that hides a draft from the customer hides it with no change:
-- ordering.sales_contract.credit_review says it waits, and only a DRAFT may (ck_sales_contract_credit_review). Each
-- submission is one row of ordering.contract_credit_review, tied to the contract's version as Sales sent it and keeping
-- the figures the accountant decided on - the limit, what the customer owed, what this contract adds after delivery and
-- what was overdue - as they were: a receipt recorded later does not rewrite why a contract was approved. At signing the
-- check runs again; a contract approved before it was sent passes if it is no worse than what was approved, and that
-- check names the approval (order_credit_check.pre_send_review_id). At most one waits per contract; whoever decides is
-- not whoever submitted (ck_contract_credit_review_four_eyes).
--
-- Who decides is who decides an order held for credit: sales-order-credit-holds:APPROVE, already the accountant's - no
-- grant changes, so no app_role.version moves.
-- =============================================================================

ALTER TABLE ordering.sales_contract
    ADD COLUMN credit_review character varying(16) DEFAULT 'NONE' NOT NULL,
    ADD CONSTRAINT ck_sales_contract_credit_review_state CHECK (credit_review IN ('NONE', 'PENDING')),
    ADD CONSTRAINT ck_sales_contract_credit_review CHECK (credit_review = 'NONE' OR status = 'DRAFT');

COMMENT ON COLUMN ordering.sales_contract.credit_review IS
    'PENDING while a draft waits for accounting to approve the customer''s credit before it is sent (15 BR-11); NONE otherwise.';

CREATE TABLE ordering.contract_credit_review (
    id uuid NOT NULL,
    contract_id uuid NOT NULL,
    contract_version bigint NOT NULL,
    outcome character varying(16) NOT NULL,
    check_outcome character varying(16) NOT NULL,
    credit_limit numeric(19,4) NOT NULL,
    exposure numeric(19,4) NOT NULL,
    after_delivery numeric(19,4) NOT NULL,
    overdue_amount numeric(19,4) NOT NULL,
    submitted_at timestamp with time zone NOT NULL,
    submitted_by uuid NOT NULL,
    decided_at timestamp with time zone,
    decided_by uuid,
    note character varying(500),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    created_by character varying(100),
    last_modified_at timestamp with time zone,
    last_modified_by character varying(100),
    CONSTRAINT pk_contract_credit_review PRIMARY KEY (id),
    CONSTRAINT fk_contract_credit_review_contract FOREIGN KEY (contract_id)
        REFERENCES ordering.sales_contract (id) ON DELETE CASCADE,
    CONSTRAINT fk_contract_credit_review_submitted_by FOREIGN KEY (submitted_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_contract_credit_review_decided_by FOREIGN KEY (decided_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_contract_credit_review_outcome
        CHECK (outcome IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_contract_credit_review_check_outcome CHECK (check_outcome IN ('OVERDUE', 'OVER_LIMIT')),
    CONSTRAINT ck_contract_credit_review_contract_version CHECK (contract_version >= 0),
    CONSTRAINT ck_contract_credit_review_amounts
        CHECK (credit_limit >= 0 AND exposure >= 0 AND after_delivery > 0 AND overdue_amount >= 0),
    -- What the outcome says of the figures: OVER_LIMIT is past the limit with nothing overdue (an overdue customer is
    -- OVERDUE whatever the limit, 15 BR-11).
    CONSTRAINT ck_contract_credit_review_over_limit
        CHECK (check_outcome <> 'OVER_LIMIT' OR (exposure + after_delivery > credit_limit AND overdue_amount = 0)),
    CONSTRAINT ck_contract_credit_review_overdue CHECK (check_outcome <> 'OVERDUE' OR overdue_amount > 0),
    CONSTRAINT ck_contract_credit_review_decided
        CHECK ((outcome = 'PENDING') = (decided_at IS NULL) AND (decided_at IS NULL) = (decided_by IS NULL)),
    CONSTRAINT ck_contract_credit_review_note
        CHECK (outcome NOT IN ('APPROVED', 'REJECTED') OR (note IS NOT NULL AND btrim(note) <> '')),
    CONSTRAINT ck_contract_credit_review_four_eyes
        CHECK (outcome NOT IN ('APPROVED', 'REJECTED') OR decided_by <> submitted_by)
);

COMMENT ON TABLE ordering.contract_credit_review IS
    'One submission of a contract to accounting before it is sent (15 BR-11): the credit figures as decided on.';

CREATE UNIQUE INDEX uk_contract_credit_review_pending ON ordering.contract_credit_review (contract_id)
    WHERE outcome = 'PENDING';
CREATE INDEX ix_contract_credit_review_contract ON ordering.contract_credit_review (contract_id, submitted_at);
CREATE INDEX ix_contract_credit_review_waiting ON ordering.contract_credit_review (submitted_at)
    WHERE outcome = 'PENDING';

-- The check at signing that passed on an approval given before sending names it (decision 2 of the plan, 2026-11-26).
-- Only a failed check needs one: a check within the limit needs no approval.
ALTER TABLE ordering.order_credit_check
    ADD COLUMN pre_send_review_id uuid,
    ADD CONSTRAINT fk_order_credit_check_pre_send_review FOREIGN KEY (pre_send_review_id)
        REFERENCES ordering.contract_credit_review (id),
    ADD CONSTRAINT ck_order_credit_check_pre_send_review
        CHECK (pre_send_review_id IS NULL OR outcome IN ('OVER_LIMIT', 'OVERDUE'));

COMMENT ON COLUMN ordering.order_credit_check.pre_send_review_id IS
    'The approval given before the contract was sent that let this failed check at signing through (15 BR-11); null otherwise.';
