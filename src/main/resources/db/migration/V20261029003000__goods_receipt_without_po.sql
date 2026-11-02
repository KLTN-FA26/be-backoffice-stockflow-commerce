-- =============================================================================
-- A goods receipt without a purchase order (blind receipt, BR-RCP-002).
-- Module: procurement   Issue: GitHub #177, Jira SCRUM-478 (owner decisions BLIND-RCPT, NOPO-SUPPLIER, NOPO-ATTACH,
--                       NOPO-REASON of 29/10)
--
-- kltn-docs 03, edge cases: goods arriving without a PO (urgent receipt, samples, gifts) are received as a "Receipt
-- không PO" with a reason; it needs approval; a PO can be attached afterwards if there is one.
--
-- The model:
--   * a blind receipt has no po_id / po_revision_id and no PO line on its lines: its lines name the item directly;
--   * the SUPPLIER is required (it replaces what the PO says) and so is the REASON (free text);
--   * it never posts without the warehouse manager's approval: DRAFT -> PENDING_APPROVAL (always) -> approved, which
--     posts it exactly as a normal receipt, or rejected, which cancels the whole receipt with the manager's reason.
--     Same approval record and four eyes as the over-tolerance approval (V20261029002000);
--   * the PO attached later is a REFERENCE only (referenced_po_id): it adds nothing to the PO's received quantity,
--     has no tolerance and no effect on three-way matching - which is why it is not po_id, the column everything
--     that reads "received against this PO" joins on. It must be a PO of the same supplier.
-- =============================================================================

ALTER TABLE procurement.goods_receipts
    ALTER COLUMN po_id DROP NOT NULL,
    ALTER COLUMN po_revision_id DROP NOT NULL,
    ADD COLUMN supplier_id       UUID,
    ADD COLUMN blind_reason      VARCHAR(500),
    ADD COLUMN referenced_po_id  UUID,
    ADD CONSTRAINT fk_goods_receipts_supplier FOREIGN KEY (supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_goods_receipts_referenced_po FOREIGN KEY (referenced_po_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_goods_receipts_po_pair CHECK ((po_id IS NULL) = (po_revision_id IS NULL)),
    -- Against a PO: the PO says who the supplier is. Without one: the supplier and the reason are required.
    ADD CONSTRAINT ck_goods_receipts_blind CHECK (
        (po_id IS NOT NULL AND supplier_id IS NULL AND blind_reason IS NULL AND referenced_po_id IS NULL)
        OR (po_id IS NULL AND supplier_id IS NOT NULL AND blind_reason IS NOT NULL AND btrim(blind_reason) <> '')),
    -- BR-RCP-002: a blind receipt is posted only with the manager's approval.
    ADD CONSTRAINT ck_goods_receipts_blind_approved CHECK (po_id IS NOT NULL
        OR status IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED') OR COALESCE(approval_decision, '') = 'APPROVED');

CREATE INDEX ix_goods_receipts_supplier ON procurement.goods_receipts (supplier_id) WHERE supplier_id IS NOT NULL;
CREATE INDEX ix_goods_receipts_referenced_po ON procurement.goods_receipts (referenced_po_id)
    WHERE referenced_po_id IS NOT NULL;

COMMENT ON COLUMN procurement.goods_receipts.referenced_po_id IS
    'A PO attached to a blind receipt afterwards (BR-RCP-002): a reference only, never counted as received against it.';

-- A decision on a blind receipt that rejects it cancels the whole receipt (REJECTED); rejecting only the excess
-- belongs to receipts against a PO.
ALTER TABLE procurement.goods_receipts DROP CONSTRAINT ck_goods_receipts_approval_decision;
ALTER TABLE procurement.goods_receipts
    ADD CONSTRAINT ck_goods_receipts_approval_decision CHECK (approval_decision IS NULL
        OR approval_decision IN ('APPROVED', 'EXCESS_REJECTED', 'REJECTED')),
    ADD CONSTRAINT ck_goods_receipts_rejected CHECK (approval_decision IS DISTINCT FROM 'REJECTED'
        OR (po_id IS NULL AND status = 'CANCELLED')),
    ADD CONSTRAINT ck_goods_receipts_excess_rejected CHECK (approval_decision IS DISTINCT FROM 'EXCESS_REJECTED'
        OR po_id IS NOT NULL);
COMMENT ON COLUMN procurement.goods_receipts.approval_decision IS
    'APPROVED: the whole count was posted. EXCESS_REJECTED: lines cut back to the tolerance and posted (receipt against a PO). REJECTED: a blind receipt refused, cancelled.';


-- Lines of a blind receipt name the item, not a PO line.
ALTER TABLE procurement.goods_receipt_lines ALTER COLUMN po_line_id DROP NOT NULL;
-- One line per item and lot on a blind receipt (the PO-line index below it ignores a null PO line).
CREATE UNIQUE INDEX uk_goods_receipt_lines_blind_lot
    ON procurement.goods_receipt_lines (receipt_id, inventory_item_id, COALESCE(lot_number, ''))
    WHERE po_line_id IS NULL;


-- The header rules: a receipt against a PO is as before; a blind receipt names no PO, and the PO it references
-- belongs to its supplier.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    po procurement.purchase_orders%ROWTYPE;
BEGIN
    IF NEW.referenced_po_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM procurement.purchase_orders p
         WHERE p.id = NEW.referenced_po_id AND p.supplier_id = NEW.supplier_id) THEN
        RAISE EXCEPTION 'receipt % references a PO of another supplier', NEW.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.po_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO po FROM procurement.purchase_orders WHERE id = NEW.po_id;
    IF NOT EXISTS (SELECT 1 FROM procurement.purchase_order_revisions r
                    WHERE r.id = NEW.po_revision_id AND r.po_id = NEW.po_id) THEN
        RAISE EXCEPTION 'receipt % is against a revision of another PO', NEW.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.warehouse_id <> po.warehouse_id THEN
        RAISE EXCEPTION 'receipt % is into another warehouse than PO %', NEW.receipt_number, po.po_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND po.status NOT IN ('CONFIRMED', 'PARTIALLY_RECEIVED') THEN
        RAISE EXCEPTION 'PO % is %, goods can be received only on a CONFIRMED PO', po.po_number, po.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER tg_goods_receipts_check ON procurement.goods_receipts;
CREATE TRIGGER tg_goods_receipts_check BEFORE INSERT OR UPDATE OF po_id, po_revision_id, warehouse_id, supplier_id,
    referenced_po_id ON procurement.goods_receipts
    FOR EACH ROW EXECUTE FUNCTION procurement.check_goods_receipt();


-- The line rules (V20261020000800): a line of a receipt against a PO belongs to that PO and receives its item; a line
-- of a blind receipt has no PO line, and a PO line has no place on a blind receipt. Both land in a location of the
-- receiving warehouse.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt procurement.goods_receipts%ROWTYPE;
    line    procurement.purchase_order_lines%ROWTYPE;
BEGIN
    SELECT * INTO receipt FROM procurement.goods_receipts WHERE id = NEW.receipt_id;
    IF (NEW.po_line_id IS NULL) <> (receipt.po_id IS NULL) THEN
        RAISE EXCEPTION 'a receipt line has a PO line exactly when its receipt is against a PO'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.po_line_id IS NOT NULL THEN
        SELECT * INTO line FROM procurement.purchase_order_lines WHERE id = NEW.po_line_id FOR UPDATE;
        IF line.po_id IS DISTINCT FROM receipt.po_id THEN
            RAISE EXCEPTION 'receipt line is for a line of another PO' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.inventory_item_id IS DISTINCT FROM line.inventory_item_id THEN
            RAISE EXCEPTION 'received item differs from the ordered item of PO line %', line.line_no
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM warehouse.storage_location s
                    WHERE s.id = NEW.location_id AND s.warehouse_id = receipt.warehouse_id) THEN
        RAISE EXCEPTION 'receipt location is not in the receiving warehouse' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
