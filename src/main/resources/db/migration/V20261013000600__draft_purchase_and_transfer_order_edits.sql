-- =============================================================================
-- SCRUM-462 / SCRUM-390: a draft purchase order or transfer order can be changed, and only a draft.
--
-- kltn-docs 02 §5: DRAFT is "đang soạn, sửa tự do"; from submission on, what was ordered changes only
-- through a revision (BR-02). SCRUM-390: "đổi kho chỉ được làm khi PO còn ở DRAFT". kltn-docs 10 §5:
-- a rejected transfer goes back to DRAFT for the planner to change.
--
-- 1. purchase_orders.warehouse_id was immutable; it may now change while a STANDARD order is DRAFT
--    (a SUBCONTRACT order's warehouse is its production order's, BR-10).
-- 2. A line's content (item, quantity, price, tax, description) changes only while its order is
--    DRAFT. The SUBCONTRACT split resizes a draft's line, which this still allows.
-- 3. A line taken off a draft that was already frozen in a revision (submitted, then rejected) is
--    WITHDRAWN rather than deleted: deleting it would cascade into the rejected revision's lines and
--    erase what the approver saw. Only such a line may be WITHDRAWN; a line never frozen is deleted.
-- 4. A transfer order's warehouses, reason and date, and its lines' SKU, lot and quantity, change
--    only while it is DRAFT.
-- =============================================================================

-- 1 -------------------------------------------------------------------------
DROP TRIGGER tg_purchase_orders_immutable ON procurement.purchase_orders;
CREATE TRIGGER tg_purchase_orders_immutable BEFORE UPDATE ON procurement.purchase_orders
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('po_number', 'supplier_id');

CREATE FUNCTION procurement.guard_po_warehouse_change()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT (OLD.status = 'DRAFT' AND NEW.status = 'DRAFT' AND OLD.po_type = 'STANDARD') THEN
        RAISE EXCEPTION 'PO % is % (%): its receiving warehouse changes only while a standard order is DRAFT',
            OLD.po_number, OLD.status, OLD.po_type USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_purchase_orders_warehouse_draft_only BEFORE UPDATE OF warehouse_id ON procurement.purchase_orders
    FOR EACH ROW WHEN (OLD.warehouse_id IS DISTINCT FROM NEW.warehouse_id)
    EXECUTE FUNCTION procurement.guard_po_warehouse_change();

-- 2 -------------------------------------------------------------------------
CREATE FUNCTION procurement.guard_po_line_content_change()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    po_status VARCHAR(20);
BEGIN
    SELECT status INTO po_status FROM procurement.purchase_orders WHERE id = NEW.po_id;
    IF po_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'line % of a % PO: what was ordered changes through a revision (BR-02)',
            OLD.line_no, po_status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_purchase_order_lines_draft_content
    BEFORE UPDATE OF inventory_item_id, uom, ordered_qty, unit_price, tax_rate, discount_rate, note
    ON procurement.purchase_order_lines
    FOR EACH ROW WHEN (OLD.inventory_item_id IS DISTINCT FROM NEW.inventory_item_id
                       OR OLD.uom IS DISTINCT FROM NEW.uom
                       OR OLD.ordered_qty IS DISTINCT FROM NEW.ordered_qty
                       OR OLD.unit_price IS DISTINCT FROM NEW.unit_price
                       OR OLD.tax_rate IS DISTINCT FROM NEW.tax_rate
                       OR OLD.discount_rate IS DISTINCT FROM NEW.discount_rate
                       OR OLD.note IS DISTINCT FROM NEW.note)
    EXECUTE FUNCTION procurement.guard_po_line_content_change();

-- 3 -------------------------------------------------------------------------
ALTER TABLE procurement.purchase_order_lines DROP CONSTRAINT ck_purchase_order_lines_status;
ALTER TABLE procurement.purchase_order_lines ADD CONSTRAINT ck_purchase_order_lines_status
    CHECK (status IN ('OPEN', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED', 'WITHDRAWN'));
ALTER TABLE procurement.purchase_order_lines ADD CONSTRAINT ck_purchase_order_lines_withdrawn
    CHECK (status <> 'WITHDRAWN' OR po_revision_id IS NOT NULL);

COMMENT ON CONSTRAINT ck_purchase_order_lines_withdrawn ON procurement.purchase_order_lines IS
    'WITHDRAWN = taken off a draft after a revision froze it; kept so that revision keeps its lines.';

-- 4 -------------------------------------------------------------------------
CREATE FUNCTION inventory.guard_transfer_order_draft_header()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'transfer % is %: its warehouses, reason and date change only while DRAFT',
            OLD.transfer_number, OLD.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_transfer_order_draft_header
    BEFORE UPDATE OF from_warehouse_id, to_warehouse_id, reason, expected_date ON inventory.transfer_order
    FOR EACH ROW WHEN (OLD.from_warehouse_id IS DISTINCT FROM NEW.from_warehouse_id
                       OR OLD.to_warehouse_id IS DISTINCT FROM NEW.to_warehouse_id
                       OR OLD.reason IS DISTINCT FROM NEW.reason
                       OR OLD.expected_date IS DISTINCT FROM NEW.expected_date)
    EXECUTE FUNCTION inventory.guard_transfer_order_draft_header();

CREATE FUNCTION inventory.guard_transfer_order_draft_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    order_status VARCHAR(20);
BEGIN
    SELECT status INTO order_status FROM inventory.transfer_order WHERE id = OLD.transfer_order_id;
    IF order_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'line % of a % transfer: SKU, lot and quantity change only while DRAFT',
            OLD.line_no, order_status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_transfer_order_line_draft_content
    BEFORE UPDATE OF sku, lot_number, requested_qty ON inventory.transfer_order_line
    FOR EACH ROW WHEN (OLD.sku IS DISTINCT FROM NEW.sku
                       OR OLD.lot_number IS DISTINCT FROM NEW.lot_number
                       OR OLD.requested_qty IS DISTINCT FROM NEW.requested_qty)
    EXECUTE FUNCTION inventory.guard_transfer_order_draft_line();
