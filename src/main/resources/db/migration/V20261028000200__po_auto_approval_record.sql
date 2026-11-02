-- What an automatic approval records (SCRUM-124 rule 1, owner spec of 21/10): a decision of its own, AUTO_APPROVED,
-- and with it the limit that allowed it - which approval_limits row, how much it allowed, and the role it belongs to -
-- as columns an audit can filter and join on, not as text in the reason. A limit row is closed or replaced over time
-- (effective_to), and the approval records are append-only (V20260928002400), so what allowed the decision has to be
-- written on the decision itself.
--
-- limit_holder_id is whose limit it was. It is the submitter for an ordinary purchase order; for a subcontract order
-- raised by splitting a production order it is the person who split (SCRUM-124 rule 6), who need not be the one who
-- submits it. The anti-splitting check (rule 4) adds up the automatic approvals of one holder and one supplier.
--
-- The limit columns carry no foreign keys on purpose: approval_limits rows are replaced as roles are reorganised, and
-- the approval record must outlive them.

ALTER TABLE procurement.purchase_order_approvals
    ADD COLUMN limit_id        UUID,
    ADD COLUMN limit_amount    NUMERIC(18, 2),
    ADD COLUMN limit_role_id   UUID,
    ADD COLUMN limit_holder_id UUID;

ALTER TABLE procurement.purchase_order_approvals
    DROP CONSTRAINT ck_purchase_order_approvals_decision,
    DROP CONSTRAINT ck_purchase_order_approvals_reason;

ALTER TABLE procurement.purchase_order_approvals
    ADD CONSTRAINT ck_purchase_order_approvals_decision
        CHECK (decision IN ('APPROVED', 'AUTO_APPROVED', 'REJECTED', 'WITHDRAWN')),
    -- A rejection or a withdrawal says why; an approval, by a person or by the limit, need not.
    ADD CONSTRAINT ck_purchase_order_approvals_reason
        CHECK (decision IN ('APPROVED', 'AUTO_APPROVED') OR reason IS NOT NULL),
    -- An automatic approval names the limit that allowed it, in full; no other decision carries one.
    ADD CONSTRAINT ck_purchase_order_approvals_limit
        CHECK (CASE WHEN decision = 'AUTO_APPROVED'
                    THEN limit_id IS NOT NULL AND limit_amount IS NOT NULL AND limit_amount > 0
                         AND limit_role_id IS NOT NULL AND limit_holder_id IS NOT NULL
                    ELSE limit_id IS NULL AND limit_amount IS NULL AND limit_role_id IS NULL
                         AND limit_holder_id IS NULL END);

-- The anti-splitting lookup: automatic approvals of one holder, newest first.
CREATE INDEX ix_purchase_order_approvals_auto
    ON procurement.purchase_order_approvals (limit_holder_id, decision_at)
    WHERE decision = 'AUTO_APPROVED';

COMMENT ON COLUMN procurement.purchase_order_approvals.limit_id IS
    'AUTO_APPROVED only: the approval_limits row in force that allowed it (no FK: the record outlives the row).';
COMMENT ON COLUMN procurement.purchase_order_approvals.limit_amount IS
    'AUTO_APPROVED only: the amount that row allowed, in the purchase order''s currency.';
COMMENT ON COLUMN procurement.purchase_order_approvals.limit_role_id IS
    'AUTO_APPROVED only: the role whose limit was used (the highest among the holder''s roles).';
COMMENT ON COLUMN procurement.purchase_order_approvals.limit_holder_id IS
    'AUTO_APPROVED only: whose limit it was - the submitter, or the person who split the production order for a subcontract order.';

-- The timeline gets the matching event.
ALTER TABLE procurement.purchase_order_events
    DROP CONSTRAINT ck_purchase_order_events_action;
ALTER TABLE procurement.purchase_order_events
    ADD CONSTRAINT ck_purchase_order_events_action CHECK (action IN ('CREATED', 'SUBMITTED', 'APPROVED',
        'AUTO_APPROVED', 'REJECTED', 'WITHDRAWN', 'CONFIRMED', 'REVISED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED',
        'SHORT_CLOSED', 'FORCE_CLOSED', 'CANCELLED'));
