-- =============================================================================
-- Purchase order and receipt lines record the unit they were entered in.
-- Module: procurement   Issue: GitHub #125, Jira SCRUM-473 (and docs 01 BR-10)
--
-- Goods are ordered and received by the carton. A line keeps counting in the base unit
-- (ordered_qty, received_qty: every check, tolerance and ledger row reads those), and now also
-- names the unit it was entered in (uom_conversion_id). The existing text column uom is unchanged:
-- it is the base unit's code, copied from the item when the line is made (ProcurementServiceImpl),
-- and stays the unit ordered_qty is counted in.
--
--   PO line:      5 CARTON of 1,000 →  uom_conversion_id = CARTON, unit_qty 5, ordered_qty 5000.
--   Receipt line: received in CARTON →  uom_conversion_id = CARTON, received_qty what was counted
--                 (4,980 when one carton was short). How many cartons, and what each held, are the
--                 line's stock packages (V20261020000100), not a column here: received_qty need
--                 not be a whole number of cartons.
--
-- The factor is not copied onto the line: V20261020000600 freezes a unit's factor once a document
-- uses it, so the unit's factor IS the factor the line was converted with. A whole number of units
-- is ordered (BR-09: the factor is an integer and there is no part of a carton), and
-- ordered_qty = unit_qty × factor is checked by a trigger, since the factor is in another table.
-- The unit is one of the line item's units: single-column key plus that trigger (ADR-0007 §4).
--
-- No backfill. A line written before this migration has no unit and was entered in the base unit;
-- NULL says so. Back-filling would also have to update frozen rows (append-only line revisions,
-- confirmed receipt lines), which their triggers rightly refuse.
--
-- A PO line revision freezes the line, so it carries the unit too. The code that writes revisions
-- (PurchaseOrderRepositoryAdapter, SubcontractOrderStoreAdapter) must copy uom_conversion_id and
-- unit_qty from the line once lines record a unit; an amendment is matched against them below.
-- =============================================================================


-- 1. Purchase order lines and their frozen revisions -------------------------------------------------
ALTER TABLE procurement.purchase_order_lines
    ADD COLUMN uom_conversion_id UUID,
    ADD COLUMN unit_qty          INTEGER,
    ADD CONSTRAINT fk_purchase_order_lines_unit FOREIGN KEY (uom_conversion_id)
        REFERENCES inventory.uom_conversions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_purchase_order_lines_unit
        CHECK ((uom_conversion_id IS NULL) = (unit_qty IS NULL) AND (unit_qty IS NULL OR unit_qty > 0));

ALTER TABLE procurement.purchase_order_line_revisions
    ADD COLUMN uom_conversion_id UUID,
    ADD COLUMN unit_qty          INTEGER,
    ADD CONSTRAINT fk_purchase_order_line_revisions_unit FOREIGN KEY (uom_conversion_id)
        REFERENCES inventory.uom_conversions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_purchase_order_line_revisions_unit
        CHECK ((uom_conversion_id IS NULL) = (unit_qty IS NULL) AND (unit_qty IS NULL OR unit_qty > 0));

CREATE INDEX ix_purchase_order_lines_unit ON procurement.purchase_order_lines (uom_conversion_id)
    WHERE uom_conversion_id IS NOT NULL;
CREATE INDEX ix_purchase_order_line_revisions_unit ON procurement.purchase_order_line_revisions (uom_conversion_id)
    WHERE uom_conversion_id IS NOT NULL;

COMMENT ON COLUMN procurement.purchase_order_lines.uom_conversion_id IS
    'The unit the line was entered in; NULL = the base unit (lines before V20261020000400).';
COMMENT ON COLUMN procurement.purchase_order_lines.unit_qty IS
    'How many of that unit; ordered_qty = unit_qty × the unit''s factor.';
COMMENT ON COLUMN procurement.purchase_order_line_revisions.uom_conversion_id IS
    'The unit of the line as this revision froze it.';
COMMENT ON COLUMN procurement.purchase_order_line_revisions.unit_qty IS
    'How many of that unit, as frozen.';

-- The unit is one of the line item's units, and the base-unit quantity is the unit quantity times
-- its factor. Shared by the line and its revision, which have the same columns.
--
-- A line is not newly put in a retired unit: ordering is choosing a unit, and a retired one "cannot
-- be the default purchase unit any more" (V20261019000300). A line already in it keeps it, and so do
-- its revisions (a frozen copy), so an approved amendment of an old line still goes through.
--
-- The unit's advisory lock is taken SHARED before the unit is read, so a change of its factor, item or
-- name cannot commit alongside this line (see check_stock_package_created, V20261020000100).
CREATE FUNCTION procurement.check_po_line_unit()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    unit     inventory.uom_conversions%ROWTYPE;
    new_unit BOOLEAN;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(inventory.unit_lock_key(NEW.uom_conversion_id));
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    IF unit.inventory_item_id <> NEW.inventory_item_id THEN
        RAISE EXCEPTION 'unit % is not a unit of the line''s item', unit.unit_of_measure
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_TABLE_NAME = 'purchase_order_lines' AND NOT unit.active THEN
        IF TG_OP = 'INSERT' THEN
            new_unit := TRUE;
        ELSE
            new_unit := NEW.uom_conversion_id IS DISTINCT FROM OLD.uom_conversion_id;
        END IF;
        IF new_unit THEN
            RAISE EXCEPTION 'unit % is retired; order in the unit that replaced it', unit.unit_of_measure
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.ordered_qty <> NEW.unit_qty::NUMERIC * unit.factor THEN
        RAISE EXCEPTION '% % of % is %, not %', NEW.unit_qty, unit.unit_of_measure, unit.factor,
            NEW.unit_qty::NUMERIC * unit.factor, NEW.ordered_qty USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_purchase_order_lines_unit
    BEFORE INSERT OR UPDATE OF inventory_item_id, uom_conversion_id, unit_qty, ordered_qty
    ON procurement.purchase_order_lines
    FOR EACH ROW WHEN (NEW.uom_conversion_id IS NOT NULL)
    EXECUTE FUNCTION procurement.check_po_line_unit();
CREATE TRIGGER tg_purchase_order_line_revisions_unit BEFORE INSERT
    ON procurement.purchase_order_line_revisions
    FOR EACH ROW WHEN (NEW.uom_conversion_id IS NOT NULL)
    EXECUTE FUNCTION procurement.check_po_line_unit();

-- The unit is part of what was ordered (BR-02): it changes while the PO is a draft, or by an
-- approved amendment that froze it. Same function as V20261017000710, the unit added to the match.
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
           AND lr.inventory_item_id = NEW.inventory_item_id
           AND lr.uom_conversion_id IS NOT DISTINCT FROM NEW.uom_conversion_id
           AND lr.unit_qty IS NOT DISTINCT FROM NEW.unit_qty) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'line % of a % PO: what was ordered changes through a revision (BR-02)',
        OLD.line_no, po_status USING ERRCODE = 'check_violation';
END
$$;

DROP TRIGGER tg_purchase_order_lines_draft_content ON procurement.purchase_order_lines;
CREATE TRIGGER tg_purchase_order_lines_draft_content
    BEFORE UPDATE OF inventory_item_id, uom, ordered_qty, unit_price, tax_rate, discount_rate, note,
        uom_conversion_id, unit_qty
    ON procurement.purchase_order_lines
    FOR EACH ROW WHEN (OLD.inventory_item_id IS DISTINCT FROM NEW.inventory_item_id
                       OR OLD.uom IS DISTINCT FROM NEW.uom
                       OR OLD.ordered_qty IS DISTINCT FROM NEW.ordered_qty
                       OR OLD.unit_price IS DISTINCT FROM NEW.unit_price
                       OR OLD.tax_rate IS DISTINCT FROM NEW.tax_rate
                       OR OLD.discount_rate IS DISTINCT FROM NEW.discount_rate
                       OR OLD.note IS DISTINCT FROM NEW.note
                       OR OLD.uom_conversion_id IS DISTINCT FROM NEW.uom_conversion_id
                       OR OLD.unit_qty IS DISTINCT FROM NEW.unit_qty)
    EXECUTE FUNCTION procurement.guard_po_line_content_change();


-- 2. Goods receipt lines ----------------------------------------------------------------------------
ALTER TABLE procurement.goods_receipt_lines
    ADD COLUMN uom_conversion_id UUID,
    ADD CONSTRAINT fk_goods_receipt_lines_unit FOREIGN KEY (uom_conversion_id)
        REFERENCES inventory.uom_conversions (id) ON DELETE RESTRICT;

CREATE INDEX ix_goods_receipt_lines_unit ON procurement.goods_receipt_lines (uom_conversion_id)
    WHERE uom_conversion_id IS NOT NULL;

COMMENT ON COLUMN procurement.goods_receipt_lines.uom_conversion_id IS
    'The unit the goods were received in; NULL = the base unit. The cartons are the line''s stock packages.';

-- The unit is one of the received item's units. A line is newly put in a retired unit only when its
-- PO line was ordered in it: goods ordered before the supplier changed its carton still arrive in the
-- old one, and a retired unit "stays for the documents that already used it" (V20261019000300). A
-- line already in a unit keeps it when the unit is retired later. The unit's advisory lock is taken
-- SHARED, as in check_po_line_unit.
CREATE FUNCTION procurement.check_goods_receipt_line_unit()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    unit     inventory.uom_conversions%ROWTYPE;
    new_unit BOOLEAN;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(inventory.unit_lock_key(NEW.uom_conversion_id));
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    IF unit.inventory_item_id <> NEW.inventory_item_id THEN
        RAISE EXCEPTION 'receipt line: the unit is not a unit of the received item'
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        new_unit := TRUE;
    ELSE
        new_unit := NEW.uom_conversion_id IS DISTINCT FROM OLD.uom_conversion_id;
    END IF;
    IF new_unit AND NOT unit.active
       AND NOT EXISTS (SELECT 1 FROM procurement.purchase_order_lines
                        WHERE id = NEW.po_line_id AND uom_conversion_id = NEW.uom_conversion_id) THEN
        RAISE EXCEPTION 'receipt line: unit % is retired; only goods ordered in it are still received in it',
            unit.unit_of_measure USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_goods_receipt_lines_unit
    BEFORE INSERT OR UPDATE OF inventory_item_id, uom_conversion_id ON procurement.goods_receipt_lines
    FOR EACH ROW WHEN (NEW.uom_conversion_id IS NOT NULL)
    EXECUTE FUNCTION procurement.check_goods_receipt_line_unit();

-- BR-05: a confirmed receipt's lines keep what was counted, the unit it was counted in included.
-- Same function as V20261010000100, the unit added to what must not change.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line_frozen()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt_status VARCHAR(16);
BEGIN
    SELECT status INTO receipt_status FROM procurement.goods_receipts
     WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt_status IS NULL OR receipt_status = 'DRAFT' THEN
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
