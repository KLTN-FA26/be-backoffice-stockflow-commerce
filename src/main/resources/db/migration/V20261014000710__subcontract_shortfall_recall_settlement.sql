-- =============================================================================
-- Subcontracting, the rest of SCRUM-433/441 (03 §16, 04c F-PROD G9-G10, BR-PRD-12, 16, 17).
--
-- 1. A subcontractor's shortfall may go back to the print shop (03 §16: "splits the gap into a new in-house
--    child"), and a subcontracted order cancelled while its sales order still wants the goods is replaced by an
--    in-house one. Both are ORDER rows with a parent that are printed IN_HOUSE, so the old "a child is always
--    subcontracted" check gives way to: a child is an ORDER. production_order.origin says why a child exists:
--    SPLIT (part sent out, BR-PRD-12), SHORTFALL (part of a subcontractor's quantity taken back to the print
--    shop), REPLACEMENT (the whole of a cancelled subcontracted order). Existing children are SPLITs.
-- 2. Blanks recalled from a subcontractor without a goods receipt (the subcontractor gives the work back, the
--    order is cancelled): production_subcontract_recall, one row per request, returned blanks back to stock,
--    spoilt ones written off as SUBCONTRACT_LOSS through the adjustment approval (BR-PRD-15: they leave the
--    subcontractor's location only through a receipt or a reasoned adjustment).
-- 3. BR-PRD-17 / G10: procurement.subcontract_settlement, one per SUBCONTRACT purchase order, written when its
--    production order ends - the basis the subcontract invoice is matched against: units that passed QC are
--    paid, units that failed it are not; blanks lost above the subcontractor's tolerance need the warehouse
--    manager's approval and are deducted; a fee agreed for work cancelled after it was sent out is added.
--    The deduction per blank is entered by the approver with a reason (decision QA1: never the supplier's current
--    price or the blanks' purchase price); it is recorded, not paid.
-- 4. A SUBCONTRACT order already approved gets more quantity (a resend after a QC shortfall) through an
--    AMENDMENT revision approved again (BR-02): the line's quantity may change when the line moves to an
--    AMENDMENT revision of its own order, not otherwise.
-- =============================================================================

-- 1 ---------------------------------------------------------------------------------------------------------
ALTER TABLE production.production_order DROP CONSTRAINT ck_production_order_child_subcontracted;
ALTER TABLE production.production_order ADD COLUMN origin VARCHAR(16);
UPDATE production.production_order SET origin = 'SPLIT' WHERE parent_id IS NOT NULL;
ALTER TABLE production.production_order ADD CONSTRAINT ck_production_order_origin CHECK (
    (parent_id IS NULL AND origin IS NULL)
    OR (parent_id IS NOT NULL AND type = 'ORDER' AND origin IN ('SPLIT', 'SHORTFALL', 'REPLACEMENT')));
CREATE INDEX ix_production_order_parent ON production.production_order (parent_id) WHERE parent_id IS NOT NULL;

COMMENT ON COLUMN production.production_order.origin IS
    'Why a child exists: SPLIT (sent out, BR-PRD-12), SHORTFALL (a subcontractor''s gap back to the print shop), '
    'REPLACEMENT (in-house replacement of a cancelled subcontracted order). NULL on a root.';

-- 2 ---------------------------------------------------------------------------------------------------------
CREATE TABLE production.production_subcontract_recall
(
    id                  UUID          NOT NULL,
    production_order_id UUID          NOT NULL,
    request_id          UUID          NOT NULL,
    returned_blanks     INTEGER       NOT NULL,
    spoilt_blanks       INTEGER       NOT NULL,
    return_location     VARCHAR(64),
    note                VARCHAR(1000),
    recorded_at         TIMESTAMPTZ   NOT NULL,
    recorded_by         UUID          NOT NULL,

    CONSTRAINT pk_production_subcontract_recall PRIMARY KEY (id),
    CONSTRAINT uk_production_subcontract_recall_request UNIQUE (request_id),
    CONSTRAINT fk_production_subcontract_recall_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_production_subcontract_recall_by FOREIGN KEY (recorded_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_subcontract_recall_quantities
        CHECK (returned_blanks >= 0 AND spoilt_blanks >= 0 AND returned_blanks + spoilt_blanks > 0)
);
CREATE INDEX ix_production_subcontract_recall_order ON production.production_subcontract_recall (production_order_id);

-- 3 ---------------------------------------------------------------------------------------------------------
CREATE TABLE procurement.subcontract_settlement
(
    po_id                 UUID           NOT NULL,
    production_order_id   UUID           NOT NULL,
    status                VARCHAR(20)    NOT NULL,
    currency              VARCHAR(3)     NOT NULL,
    unit_price            NUMERIC(18, 2) NOT NULL,
    -- QC (BR-PRD-17: units failing QC are not paid)
    received_quantity     INTEGER        NOT NULL,
    passed_quantity       INTEGER        NOT NULL,
    failed_quantity       INTEGER        NOT NULL,
    payable_amount        NUMERIC(18, 2) NOT NULL,
    -- Blanks (SUPPLIED_BLANKS; zeros for FULL_SERVICE)
    blanks_sent           INTEGER        NOT NULL,
    blanks_used           INTEGER        NOT NULL,
    blanks_returned       INTEGER        NOT NULL,
    blanks_lost           INTEGER        NOT NULL,
    tolerance_percent     NUMERIC(5, 2)  NOT NULL,
    tolerance_blanks      INTEGER        NOT NULL,
    excess_loss           INTEGER        NOT NULL,
    deduction_per_blank   NUMERIC(18, 2),
    deduction_amount      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    -- A fee agreed with the subcontractor for work cancelled after it was sent out (BR-PRD-10 by analogy)
    cancellation_fee      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    cancellation_fee_reason VARCHAR(1000),
    net_payable           NUMERIC(18, 2) NOT NULL,
    settled_at            TIMESTAMPTZ    NOT NULL,
    settled_by            UUID,
    approved_at           TIMESTAMPTZ,
    approved_by           UUID,
    approval_note         VARCHAR(1000),
    version               BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT pk_subcontract_settlement PRIMARY KEY (po_id),
    CONSTRAINT fk_subcontract_settlement_po FOREIGN KEY (po_id) REFERENCES procurement.purchase_orders (id),
    CONSTRAINT fk_subcontract_settlement_production_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id),
    CONSTRAINT fk_subcontract_settlement_settled_by FOREIGN KEY (settled_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_subcontract_settlement_approved_by FOREIGN KEY (approved_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_subcontract_settlement_status CHECK (status IN ('AWAITING_APPROVAL', 'FINAL')),
    CONSTRAINT ck_subcontract_settlement_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_subcontract_settlement_quantities CHECK (
        received_quantity >= 0 AND passed_quantity >= 0 AND failed_quantity >= 0
        AND passed_quantity + failed_quantity <= received_quantity
        AND blanks_sent >= 0 AND blanks_used >= 0 AND blanks_returned >= 0 AND blanks_lost >= 0
        AND blanks_used + blanks_returned + blanks_lost <= blanks_sent
        AND tolerance_blanks >= 0 AND excess_loss = GREATEST(blanks_lost - tolerance_blanks, 0)),
    CONSTRAINT ck_subcontract_settlement_amounts CHECK (
        unit_price >= 0 AND payable_amount = passed_quantity * unit_price
        AND deduction_amount >= 0 AND cancellation_fee >= 0
        AND net_payable = payable_amount - deduction_amount + cancellation_fee),
    -- BR-PRD-17: a loss above tolerance is approved, with the deduction the approver entered, before it is final.
    CONSTRAINT ck_subcontract_settlement_approval CHECK (
        (excess_loss = 0 AND status = 'FINAL' AND deduction_amount = 0 AND approved_by IS NULL)
        OR (excess_loss > 0 AND status = 'AWAITING_APPROVAL' AND approved_by IS NULL AND deduction_amount = 0)
        OR (excess_loss > 0 AND status = 'FINAL' AND approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND approval_note IS NOT NULL AND deduction_per_blank IS NOT NULL
            AND deduction_amount = excess_loss * deduction_per_blank)),
    CONSTRAINT ck_subcontract_settlement_fee_reason CHECK (cancellation_fee = 0 OR cancellation_fee_reason IS NOT NULL),
    CONSTRAINT uk_subcontract_settlement_production_order UNIQUE (production_order_id)
);

COMMENT ON TABLE procurement.subcontract_settlement IS
    'BR-PRD-17 / G10: what a SUBCONTRACT order owes once its production order ended - QC-passed units paid, '
    'failed not, blanks lost above tolerance deducted after warehouse-manager approval, agreed cancellation fee.';

-- 4 ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION procurement.guard_po_line_content_change()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    po_status VARCHAR(20);
BEGIN
    SELECT status INTO po_status FROM procurement.purchase_orders WHERE id = NEW.po_id;
    IF po_status = 'DRAFT' THEN
        RETURN NEW;
    END IF;
    -- An approved amendment (BR-02): the line moves to an AMENDMENT revision of its own order with the
    -- content that revision froze.
    IF NEW.po_revision_id IS DISTINCT FROM OLD.po_revision_id AND EXISTS (
        SELECT 1 FROM procurement.purchase_order_revisions r
          JOIN procurement.purchase_order_line_revisions lr ON lr.po_revision_id = r.id AND lr.po_line_id = NEW.id
         WHERE r.id = NEW.po_revision_id AND r.po_id = NEW.po_id AND r.kind = 'AMENDMENT'
           AND lr.ordered_qty = NEW.ordered_qty AND lr.unit_price = NEW.unit_price
           AND lr.inventory_item_id = NEW.inventory_item_id) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'line % of a % PO: what was ordered changes through a revision (BR-02)',
        OLD.line_no, po_status USING ERRCODE = 'check_violation';
END
$$;
