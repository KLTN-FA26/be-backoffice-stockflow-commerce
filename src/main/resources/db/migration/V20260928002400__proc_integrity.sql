-- =============================================================================
-- Procurement: append-only history and cross-row rules, as triggers.
-- Module: procurement   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase R
--
-- Single-column foreign keys prove a row exists, not that it belongs to the same purchase order.
-- These triggers are the second statement of rules the aggregates enforce first (CLAUDE.md §7).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Append-only history. UPDATE is always refused. DELETE is refused too, EXCEPT as the cascade
--    of deleting a parent (a draft PO thrown away takes its history with it): by the time a
--    cascaded delete reaches the child, the parent row is already gone.
--
--    Arguments: pairs of (parent table, column in this row pointing at the parent's id).
-- -----------------------------------------------------------------------------
CREATE FUNCTION platform.append_only()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    i INTEGER := 0;
    parent_exists BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' OR TG_NARGS = 0 THEN
        RAISE EXCEPTION '%.% is append-only', TG_TABLE_SCHEMA, TG_TABLE_NAME
            USING ERRCODE = 'check_violation';
    END IF;
    WHILE i < TG_NARGS LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s WHERE id = ($1 ->> %L)::uuid)',
                       TG_ARGV[i], TG_ARGV[i + 1])
           INTO parent_exists USING to_jsonb(OLD);
        IF NOT parent_exists THEN
            RETURN OLD;                  -- cascade from a deleted parent
        END IF;
        i := i + 2;
    END LOOP;
    RAISE EXCEPTION '%.% is append-only: rows leave only with their parent', TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'check_violation';
END
$$;

COMMENT ON FUNCTION platform.append_only() IS
    'BEFORE UPDATE OR DELETE trigger: refuses both, except a DELETE cascading from a parent named in the arguments.';

CREATE TRIGGER tg_purchase_order_revisions_append_only
    BEFORE UPDATE OR DELETE ON procurement.purchase_order_revisions
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('procurement.purchase_orders', 'po_id');
CREATE TRIGGER tg_purchase_order_line_revisions_append_only
    BEFORE UPDATE OR DELETE ON procurement.purchase_order_line_revisions
    FOR EACH ROW EXECUTE FUNCTION platform.append_only(
        'procurement.purchase_order_lines', 'po_line_id',
        'procurement.purchase_order_revisions', 'po_revision_id');
CREATE TRIGGER tg_purchase_order_approvals_append_only
    BEFORE UPDATE OR DELETE ON procurement.purchase_order_approvals
    FOR EACH ROW EXECUTE FUNCTION platform.append_only(
        'procurement.purchase_order_revisions', 'po_revision_id');
CREATE TRIGGER tg_purchase_order_events_append_only
    BEFORE UPDATE OR DELETE ON procurement.purchase_order_events
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('procurement.purchase_orders', 'po_id');

CREATE TRIGGER tg_suppliers_immutable BEFORE UPDATE ON procurement.suppliers
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('code');
CREATE TRIGGER tg_purchase_orders_immutable BEFORE UPDATE ON procurement.purchase_orders
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('po_number', 'supplier_id', 'warehouse_id');


-- -----------------------------------------------------------------------------
-- 2. A PO's pointers and lines stay inside that PO: the active/pending revision and a line's
--    revision are revisions OF THIS PO.
-- -----------------------------------------------------------------------------
CREATE FUNCTION procurement.check_po_revision_pointers()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (NEW.active_revision_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM procurement.purchase_order_revisions r
             WHERE r.id = NEW.active_revision_id AND r.po_id = NEW.id))
       OR (NEW.pending_revision_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM procurement.purchase_order_revisions r
             WHERE r.id = NEW.pending_revision_id AND r.po_id = NEW.id)) THEN
        RAISE EXCEPTION 'PO % points at a revision of another PO', NEW.po_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

-- Deferred like the foreign keys it complements: the revision may be inserted after the header.
CREATE CONSTRAINT TRIGGER tg_purchase_orders_revision_pointers
    AFTER INSERT OR UPDATE OF active_revision_id, pending_revision_id ON procurement.purchase_orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION procurement.check_po_revision_pointers();


CREATE FUNCTION procurement.check_po_line_revision()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.po_revision_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM procurement.purchase_order_revisions r
         WHERE r.id = NEW.po_revision_id AND r.po_id = NEW.po_id) THEN
        RAISE EXCEPTION 'PO line % points at a revision of another PO', NEW.line_no
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_purchase_order_lines_revision BEFORE INSERT OR UPDATE OF po_revision_id, po_id
    ON procurement.purchase_order_lines
    FOR EACH ROW EXECUTE FUNCTION procurement.check_po_line_revision();


CREATE FUNCTION procurement.check_po_line_revision_pair()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM procurement.purchase_order_lines l
          JOIN procurement.purchase_order_revisions r ON r.po_id = l.po_id
         WHERE l.id = NEW.po_line_id AND r.id = NEW.po_revision_id) THEN
        RAISE EXCEPTION 'line revision joins a line and a revision of different POs'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_purchase_order_line_revisions_pair BEFORE INSERT
    ON procurement.purchase_order_line_revisions
    FOR EACH ROW EXECUTE FUNCTION procurement.check_po_line_revision_pair();


-- -----------------------------------------------------------------------------
-- 3. Receiving (docs 03). A receipt is against a revision of its own PO, into the PO's
--    warehouse, and only once the supplier has confirmed. Each line receives the PO line's item,
--    into a location of that warehouse, and never beyond BR-04's tolerance:
--        received (all non-cancelled receipts) <= ordered * (1 + tolerance / 100)
--    The PO line is locked FOR UPDATE first, so two receipts posted at the same moment cannot
--    both pass the sum check on stale totals.
-- -----------------------------------------------------------------------------
CREATE FUNCTION procurement.check_goods_receipt()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    po procurement.purchase_orders%ROWTYPE;
BEGIN
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

CREATE TRIGGER tg_goods_receipts_check BEFORE INSERT OR UPDATE OF po_id, po_revision_id, warehouse_id
    ON procurement.goods_receipts
    FOR EACH ROW EXECUTE FUNCTION procurement.check_goods_receipt();


CREATE FUNCTION procurement.check_goods_receipt_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt   procurement.goods_receipts%ROWTYPE;
    line      procurement.purchase_order_lines%ROWTYPE;
    tolerance NUMERIC;
    received  NUMERIC;
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

    SELECT COALESCE(s.over_receipt_tolerance, 0) INTO tolerance
      FROM procurement.purchase_orders p JOIN procurement.suppliers s ON s.id = p.supplier_id
     WHERE p.id = line.po_id;
    SELECT COALESCE(SUM(l.received_qty), 0) INTO received
      FROM procurement.goods_receipt_lines l
      JOIN procurement.goods_receipts r ON r.id = l.receipt_id
     WHERE l.po_line_id = NEW.po_line_id AND r.status <> 'CANCELLED' AND l.id <> NEW.id;

    IF received + NEW.received_qty > line.ordered_qty * (1 + tolerance / 100) THEN
        RAISE EXCEPTION 'PO line % would be received % of % ordered, beyond the % %% tolerance (BR-04)',
            line.line_no, received + NEW.received_qty, line.ordered_qty, tolerance
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_goods_receipt_lines_check BEFORE INSERT OR UPDATE ON procurement.goods_receipt_lines
    FOR EACH ROW EXECUTE FUNCTION procurement.check_goods_receipt_line();


-- QC never inspects more than was received on the line, and quarantines inside the warehouse.
CREATE FUNCTION procurement.check_qc_inspection()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    line      procurement.goods_receipt_lines%ROWTYPE;
    inspected NUMERIC;
BEGIN
    SELECT * INTO line FROM procurement.goods_receipt_lines WHERE id = NEW.receipt_line_id FOR UPDATE;
    SELECT COALESCE(SUM(q.quantity), 0) INTO inspected
      FROM procurement.qc_inspections q
     WHERE q.receipt_line_id = NEW.receipt_line_id AND q.id <> NEW.id;
    IF inspected + NEW.quantity > line.received_qty THEN
        RAISE EXCEPTION 'QC would inspect % of % received', inspected + NEW.quantity, line.received_qty
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.target_location_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM warehouse.storage_location s
          JOIN procurement.goods_receipts r ON r.warehouse_id = s.warehouse_id
         WHERE s.id = NEW.target_location_id AND r.id = line.receipt_id) THEN
        RAISE EXCEPTION 'QC target location is not in the receiving warehouse' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_qc_inspections_check BEFORE INSERT OR UPDATE ON procurement.qc_inspections
    FOR EACH ROW EXECUTE FUNCTION procurement.check_qc_inspection();


-- -----------------------------------------------------------------------------
-- 4. Invoicing (docs 04): an invoice is from the PO's supplier, its lines bill lines of that PO,
--    and a referenced receipt line received that same PO line.
-- -----------------------------------------------------------------------------
CREATE FUNCTION procurement.check_supplier_invoice()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.po_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM procurement.purchase_orders p WHERE p.id = NEW.po_id AND p.supplier_id = NEW.supplier_id) THEN
        RAISE EXCEPTION 'invoice % is not from the supplier of its PO', NEW.invoice_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_supplier_invoices_check BEFORE INSERT OR UPDATE OF po_id, supplier_id
    ON procurement.supplier_invoices
    FOR EACH ROW EXECUTE FUNCTION procurement.check_supplier_invoice();


CREATE FUNCTION procurement.check_supplier_invoice_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    invoice procurement.supplier_invoices%ROWTYPE;
    line    procurement.purchase_order_lines%ROWTYPE;
BEGIN
    SELECT * INTO invoice FROM procurement.supplier_invoices WHERE id = NEW.invoice_id;
    SELECT * INTO line FROM procurement.purchase_order_lines WHERE id = NEW.po_line_id;
    IF invoice.po_id IS NOT NULL AND line.po_id <> invoice.po_id THEN
        RAISE EXCEPTION 'invoice line bills a line of another PO' USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM procurement.purchase_orders p
                    WHERE p.id = line.po_id AND p.supplier_id = invoice.supplier_id) THEN
        RAISE EXCEPTION 'invoice line bills a PO of another supplier' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.receipt_line_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM procurement.goods_receipt_lines g
         WHERE g.id = NEW.receipt_line_id AND g.po_line_id = NEW.po_line_id) THEN
        RAISE EXCEPTION 'invoice line refers to a receipt of another PO line' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_supplier_invoice_lines_check BEFORE INSERT OR UPDATE
    ON procurement.supplier_invoice_lines
    FOR EACH ROW EXECUTE FUNCTION procurement.check_supplier_invoice_line();
