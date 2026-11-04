-- =============================================================================
-- The over-receipt tolerance (BR-04) is checked on the receipt lines as they end up, not row by row.
-- Module: procurement   Issue: GitHub #125, Jira SCRUM-473
--
-- check_goods_receipt_line (V20260928002400) checked the tolerance in a BEFORE trigger on each row:
-- the PO line's total over the other rows as they were at that moment, plus this one. A recount that
-- moves quantity between lines of one PO line - 8 + 2 becomes 2 + 8 - passes through a moment where
-- one row is already new and the other still old (8 + 8), and was refused although what it ends up
-- with is within tolerance. Draft lines are now recounted in place, keeping their id, because stock
-- packages point at them (V20261020000100, GoodsReceiptRepositoryAdapter), so a recount is a set of
-- UPDATEs whose order nothing should have to care about.
--
-- The tolerance is a rule about the PO line's total, so it is checked once that total is final: a
-- DEFERRABLE INITIALLY DEFERRED constraint trigger. The rest of check_goods_receipt_line - the line
-- belongs to the receipt's PO, receives the ordered item, into a location of the receiving warehouse -
-- is about the row alone and stays immediate, and so does its FOR UPDATE on the PO line: it holds
-- concurrent receipts of one PO line apart from the first write until commit, and the deferred check,
-- run after that wait, reads the other transaction's lines.
--
-- GoodsReceiptRepositoryAdapter runs SET CONSTRAINTS ... IMMEDIATE once a save is flushed, so a
-- refusal still arrives at the statement, as the 409 it always was, rather than at commit (a 500).
-- =============================================================================

CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt procurement.goods_receipts%ROWTYPE;
    line    procurement.purchase_order_lines%ROWTYPE;
BEGIN
    SELECT * INTO receipt FROM procurement.goods_receipts WHERE id = NEW.receipt_id;
    SELECT * INTO line FROM procurement.purchase_order_lines WHERE id = NEW.po_line_id FOR UPDATE;

    IF line.po_id IS DISTINCT FROM receipt.po_id THEN
        RAISE EXCEPTION 'receipt line is for a line of another PO' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.inventory_item_id IS DISTINCT FROM line.inventory_item_id THEN
        RAISE EXCEPTION 'received item differs from the ordered item of PO line %', line.line_no
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM warehouse.storage_location s
                    WHERE s.id = NEW.location_id AND s.warehouse_id = receipt.warehouse_id) THEN
        RAISE EXCEPTION 'receipt location is not in the receiving warehouse' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;


-- BR-04: what the PO line has received on receipts that are not cancelled stays within the
-- supplier's tolerance. Run at commit (or at SET CONSTRAINTS ... IMMEDIATE) for every row written,
-- on the totals as they then are.
CREATE FUNCTION procurement.check_goods_receipt_line_tolerance()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    line      procurement.purchase_order_lines%ROWTYPE;
    tolerance NUMERIC;
    received  NUMERIC;
BEGIN
    SELECT * INTO line FROM procurement.purchase_order_lines WHERE id = NEW.po_line_id;
    IF NOT FOUND THEN
        RETURN NULL; -- the foreign key reports it
    END IF;
    SELECT COALESCE(s.over_receipt_tolerance, 0) INTO tolerance
      FROM procurement.purchase_orders p JOIN procurement.suppliers s ON s.id = p.supplier_id
     WHERE p.id = line.po_id;
    SELECT COALESCE(SUM(l.received_qty), 0) INTO received
      FROM procurement.goods_receipt_lines l
      JOIN procurement.goods_receipts r ON r.id = l.receipt_id
     WHERE l.po_line_id = NEW.po_line_id AND r.status <> 'CANCELLED';

    IF received > line.ordered_qty * (1 + tolerance / 100) THEN
        RAISE EXCEPTION 'PO line % would be received % of % ordered, beyond the % %% tolerance (BR-04)',
            line.line_no, received, line.ordered_qty, tolerance
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_goods_receipt_lines_tolerance
    AFTER INSERT OR UPDATE OF po_line_id, receipt_id, received_qty ON procurement.goods_receipt_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION procurement.check_goods_receipt_line_tolerance();

COMMENT ON FUNCTION procurement.check_goods_receipt_line_tolerance() IS
    'BR-04 over-receipt tolerance of a PO line, on its final totals; deferred to commit.';
