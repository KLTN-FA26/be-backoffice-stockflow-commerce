-- =============================================================================
-- Procurement: the SUBCONTRACT purchase order and the print subcontractor (SCRUM-434).
-- Docs: kltn-docs 19-production §4.4, 02-purchase-order BR-10; plan 2026-10-06 §8.
--
-- Part of an ORDER production order can be printed by an outside shop: the warehouse manager splits
-- it into a child production order with mode SUBCONTRACTED, and the system raises a SUBCONTRACT
-- purchase order for it. Same lifecycle, approval limits, receipt and 3-way match as a standard PO;
-- what differs is stated here:
--   * one SUBCONTRACT PO per child production order, and one line on it (the finished item);
--   * its quantity changes only through that flow (a supplement on a resend), never by hand;
--   * it goes to a supplier flagged as a print subcontractor, who carries a loss tolerance for the
--     blanks reconciliation (BR-17; default 2 %, open question B14).
--
-- On the NEW purchase-order tables: new code is written against them (DB rule 5), and goods receipts
-- (SCRUM-435) already are.
-- =============================================================================

ALTER TABLE procurement.suppliers
    ADD COLUMN is_print_subcontractor BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN loss_tolerance_percent NUMERIC(5, 2) NOT NULL DEFAULT 2,
    ADD CONSTRAINT ck_suppliers_loss_tolerance CHECK (loss_tolerance_percent BETWEEN 0 AND 100);

ALTER TABLE procurement.purchase_orders
    ADD COLUMN po_type             VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN production_order_id UUID,
    ADD CONSTRAINT ck_purchase_orders_type CHECK (po_type IN ('STANDARD', 'SUBCONTRACT')),
    -- A SUBCONTRACT PO always belongs to the production order it subcontracts, a standard one never.
    ADD CONSTRAINT ck_purchase_orders_subcontract_link
        CHECK ((po_type = 'SUBCONTRACT') = (production_order_id IS NOT NULL)),
    ADD CONSTRAINT fk_purchase_orders_production_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE RESTRICT;

-- One live SUBCONTRACT PO per production order; a cancelled one may be replaced.
CREATE UNIQUE INDEX uk_purchase_orders_production_order
    ON procurement.purchase_orders (production_order_id)
    WHERE production_order_id IS NOT NULL AND status <> 'CANCELLED';

-- The type and the link are facts of the order, like its supplier and warehouse.
CREATE TRIGGER tg_purchase_orders_subcontract_immutable BEFORE UPDATE ON procurement.purchase_orders
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('po_type', 'production_order_id');

-- And the production order points back at its PO.
ALTER TABLE production.production_order
    ADD CONSTRAINT fk_production_order_purchase_order FOREIGN KEY (purchase_order_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE RESTRICT;


-- The cross-row rules: a SUBCONTRACT PO goes to a print subcontractor, for a SUBCONTRACTED
-- production order of the same warehouse, and has exactly one line.
CREATE FUNCTION procurement.check_subcontract_po()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.po_type <> 'SUBCONTRACT' THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM procurement.suppliers s
                    WHERE s.id = NEW.supplier_id AND s.is_print_subcontractor) THEN
        RAISE EXCEPTION 'SUBCONTRACT PO % must go to a print subcontractor', NEW.po_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM production.production_order p
                    WHERE p.id = NEW.production_order_id AND p.mode = 'SUBCONTRACTED'
                      AND p.warehouse_id = NEW.warehouse_id) THEN
        RAISE EXCEPTION 'SUBCONTRACT PO % must belong to a SUBCONTRACTED production order of its warehouse',
            NEW.po_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_purchase_orders_subcontract BEFORE INSERT ON procurement.purchase_orders
    FOR EACH ROW EXECUTE FUNCTION procurement.check_subcontract_po();


CREATE FUNCTION procurement.check_subcontract_po_single_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM procurement.purchase_orders p
                WHERE p.id = NEW.po_id AND p.po_type = 'SUBCONTRACT')
       AND EXISTS (SELECT 1 FROM procurement.purchase_order_lines l
                    WHERE l.po_id = NEW.po_id AND l.id <> NEW.id) THEN
        RAISE EXCEPTION 'a SUBCONTRACT PO has exactly one line' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_purchase_order_lines_subcontract BEFORE INSERT ON procurement.purchase_order_lines
    FOR EACH ROW EXECUTE FUNCTION procurement.check_subcontract_po_single_line();
