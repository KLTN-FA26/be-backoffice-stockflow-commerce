-- =============================================================================
-- A receipt over the PO line's over-receipt tolerance waits for the warehouse manager (BR-RCP-001).
-- Module: procurement   Issue: GitHub #176, Jira SCRUM-477 (owner decision OVER-RCPT, 2026-10-29)
--
-- docs 03 step 5: "nhận thừa ngoài dung sai: cần Warehouse Manager duyệt ngoại lệ hoặc từ chối phần vượt";
-- docs 05 BR-RCP-001: beyond the tolerance the receipt cannot be posted without warehouse-manager approval.
-- Until now the service refused the count outright (OVER_RECEIPT_TOLERANCE) and BR-04 was a trigger that refused
-- the same on the lines of every receipt that was not cancelled.
--
-- The model: confirming a count that goes beyond the tolerance does not post it. The receipt moves
-- DRAFT -> PENDING_APPROVAL with nothing counted in (no stock, no PO progress, no events); a user holding
-- procurement-goods-receipts:APPROVE, who is neither whoever counted it nor whoever asked for the approval,
-- then either approves it (the whole quantity is posted) or rejects the excess (the lines are cut back to the
-- tolerance and the receipt is posted). PENDING_APPROVAL can also be cancelled, like a DRAFT.
--
-- What changes in the database:
--   1. the status and the columns that remember who asked, who decided, what and why (four eyes stated twice);
--   2. BR-04 is checked when a receipt is POSTED, not on each line of a draft: a draft is a worksheet and may count
--      more than the tolerance, which is what the approval is for. Receipts waiting for approval count for nothing
--      until they are posted, and a receipt whose excess was approved is exempt - once, on the way in;
--   3. the lines of a PENDING_APPROVAL receipt may be cut back (the rejection of the excess), like a draft's;
--   4. the manager's grant.
-- =============================================================================

-- 1. Status and approval record.
ALTER TABLE procurement.goods_receipts DROP CONSTRAINT ck_goods_receipts_status;
ALTER TABLE procurement.goods_receipts DROP CONSTRAINT ck_goods_receipts_confirmed;

ALTER TABLE procurement.goods_receipts
    ADD COLUMN approval_requested_at TIMESTAMPTZ,
    ADD COLUMN approval_requested_by UUID,
    ADD COLUMN approval_decision     VARCHAR(16),
    ADD COLUMN approval_decided_at   TIMESTAMPTZ,
    ADD COLUMN approval_decided_by   UUID,
    ADD COLUMN approval_reason       VARCHAR(500),
    ADD CONSTRAINT fk_goods_receipts_approval_requested_by FOREIGN KEY (approval_requested_by)
        REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_goods_receipts_approval_decided_by FOREIGN KEY (approval_decided_by)
        REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_goods_receipts_status CHECK (status IN
        ('DRAFT', 'PENDING_APPROVAL', 'CONFIRMED', 'IN_QC', 'IN_PUTAWAY', 'CLOSED', 'CANCELLED')),
    -- Every state past the count was confirmed by someone, at a time - except the two that never reached it.
    ADD CONSTRAINT ck_goods_receipts_confirmed CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED')
        OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    ADD CONSTRAINT ck_goods_receipts_approval_requested CHECK
        ((approval_requested_at IS NULL) = (approval_requested_by IS NULL)),
    -- Waiting: asked for, and not decided.
    ADD CONSTRAINT ck_goods_receipts_approval_pending CHECK (status <> 'PENDING_APPROVAL'
        OR (approval_requested_at IS NOT NULL AND approval_decision IS NULL)),
    ADD CONSTRAINT ck_goods_receipts_approval_decision CHECK
        (approval_decision IS NULL OR approval_decision IN ('APPROVED', 'EXCESS_REJECTED')),
    -- A decision is all or nothing, has a reason and a decider, and ends the wait.
    ADD CONSTRAINT ck_goods_receipts_approval_decided CHECK (
        (approval_decision IS NULL AND approval_decided_at IS NULL AND approval_decided_by IS NULL
            AND approval_reason IS NULL)
        OR (approval_decision IS NOT NULL AND approval_decided_at IS NOT NULL AND approval_decided_by IS NOT NULL
            AND approval_requested_at IS NOT NULL AND approval_reason IS NOT NULL AND btrim(approval_reason) <> ''
            AND status NOT IN ('DRAFT', 'PENDING_APPROVAL'))),
    -- Four eyes: neither whoever started the receipt nor whoever asked for the approval decides it.
    ADD CONSTRAINT ck_goods_receipts_four_eyes CHECK (approval_decided_by IS NULL
        OR (approval_decided_by <> received_by AND approval_decided_by <> approval_requested_by));

COMMENT ON COLUMN procurement.goods_receipts.approval_requested_at IS
    'When the count went beyond the PO line''s over-receipt tolerance and was sent to the warehouse manager (BR-RCP-001).';
COMMENT ON COLUMN procurement.goods_receipts.approval_decision IS
    'APPROVED: the whole count was posted. EXCESS_REJECTED: the lines were cut back to the tolerance and posted.';


-- 2. BR-04 at posting. The old trigger (V20261020000800) refused any line of a receipt that was not cancelled; a
-- draft may now count beyond the tolerance, so the rule moves to the moment goods are counted in.
DROP TRIGGER tg_goods_receipt_lines_tolerance ON procurement.goods_receipt_lines;
DROP FUNCTION procurement.check_goods_receipt_line_tolerance();

CREATE FUNCTION procurement.check_goods_receipt_tolerance()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    po_line  RECORD;
    received NUMERIC;
BEGIN
    -- The manager approved the excess of this receipt; later receipts of the line still count it.
    IF NEW.approval_decision = 'APPROVED' THEN
        RETURN NULL;
    END IF;
    FOR po_line IN
        SELECT DISTINCT pl.id, pl.line_no, pl.ordered_qty, COALESCE(s.over_receipt_tolerance, 0) AS tolerance
          FROM procurement.goods_receipt_lines gl
          JOIN procurement.purchase_order_lines pl ON pl.id = gl.po_line_id
          JOIN procurement.purchase_orders p ON p.id = pl.po_id
          JOIN procurement.suppliers s ON s.id = p.supplier_id
         WHERE gl.receipt_id = NEW.id
    LOOP
        SELECT COALESCE(SUM(l.received_qty), 0) INTO received
          FROM procurement.goods_receipt_lines l
          JOIN procurement.goods_receipts r ON r.id = l.receipt_id
         WHERE l.po_line_id = po_line.id AND r.status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED');
        IF received > po_line.ordered_qty * (1 + po_line.tolerance / 100) THEN
            RAISE EXCEPTION 'PO line % would be received % of % ordered, beyond the % %% tolerance, without the manager''s approval (BR-04)',
                po_line.line_no, received, po_line.ordered_qty, po_line.tolerance
                USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

COMMENT ON FUNCTION procurement.check_goods_receipt_tolerance() IS
    'BR-04 / BR-RCP-001: what the PO line has received on posted receipts stays within the supplier''s tolerance unless the manager approved the excess; checked when a receipt is posted, deferred to commit.';

-- Deferred, like the trigger it replaces: the service runs SET CONSTRAINTS ... IMMEDIATE after each save so a
-- refusal arrives at the statement (a 409) rather than at commit (a 500). Only the move into a posted state is
-- checked: later status changes of a receipt (QC, putaway, closing) must not be refused because a receipt posted
-- afterwards, with the manager's approval, took the line past its tolerance.
CREATE CONSTRAINT TRIGGER tg_goods_receipts_tolerance_insert
    AFTER INSERT ON procurement.goods_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED'))
    EXECUTE FUNCTION procurement.check_goods_receipt_tolerance();

CREATE CONSTRAINT TRIGGER tg_goods_receipts_tolerance
    AFTER UPDATE OF status ON procurement.goods_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IN ('DRAFT', 'PENDING_APPROVAL')
                       AND NEW.status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED'))
    EXECUTE FUNCTION procurement.check_goods_receipt_tolerance();


-- 3. A receipt waiting for approval can be cut back (the rejection of the excess); a posted one keeps its count (BR-05).
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line_frozen()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt_status VARCHAR(16);
BEGIN
    SELECT status INTO receipt_status FROM procurement.goods_receipts
     WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt_status IS NULL OR receipt_status IN ('DRAFT', 'PENDING_APPROVAL') THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    IF TG_OP = 'UPDATE'
       AND NEW.receipt_id = OLD.receipt_id AND NEW.po_line_id = OLD.po_line_id
       AND NEW.inventory_item_id = OLD.inventory_item_id AND NEW.location_id = OLD.location_id
       AND NEW.received_qty = OLD.received_qty AND NEW.qc_required = OLD.qc_required
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND NEW.expiry_date IS NOT DISTINCT FROM OLD.expiry_date
       AND NEW.uom_conversion_id IS NOT DISTINCT FROM OLD.uom_conversion_id THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'receipt is %: its lines keep what was counted (BR-05)', receipt_status
        USING ERRCODE = 'check_violation';
END
$$;


-- 4. Who decides: the warehouse manager. The permission has been in the catalogue since the seed; no role held it.
-- Grants are cached per role version (ADR-0008): advance only the role whose grants changed.
WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission ON permission.code = 'procurement-goods-receipts:APPROVE'
     WHERE role.code = 'WAREHOUSE_MANAGER'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
