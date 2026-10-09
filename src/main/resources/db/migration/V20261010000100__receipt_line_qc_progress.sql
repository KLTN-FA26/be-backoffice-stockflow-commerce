-- =============================================================================
-- Receiving, continued (SCRUM-435): what V20260929000250 left to the application and should not.
--
--   * Where a QC-required line's goods are now. Docs 03 step 7: the warehouse staff moves them from
--     the RECEIVING area to the QUALITY_CONTROL area; BR-08: QC decides only on goods that are there.
--     The line records the QC location, when, and by whom. A warehouse.move_task with origin
--     RECEIPT_QC may also point at the line (the task screen, warehouse module); the line is the
--     fact receiving itself relies on.
--   * BR-05: a confirmed receipt is not edited. Its lines keep what was counted; only the QC move
--     columns may still change, and lines are neither added nor removed.
--   * BR-08 in the database: an inspection needs the line's goods in the QC area first.
-- =============================================================================


ALTER TABLE procurement.goods_receipt_lines
    ADD COLUMN qc_location_id UUID,
    ADD COLUMN moved_to_qc_at TIMESTAMPTZ,
    ADD COLUMN moved_to_qc_by UUID,
    ADD CONSTRAINT fk_goods_receipt_lines_qc_location FOREIGN KEY (qc_location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_goods_receipt_lines_moved_by FOREIGN KEY (moved_to_qc_by) REFERENCES identity.app_user (id),
    -- All three or none, and only on a line received under the 3-step flow.
    ADD CONSTRAINT ck_goods_receipt_lines_qc_move CHECK (
        num_nonnulls(qc_location_id, moved_to_qc_at, moved_to_qc_by) IN (0, 3)
        AND (qc_location_id IS NULL OR qc_required));

CREATE INDEX ix_goods_receipt_lines_qc_location
    ON procurement.goods_receipt_lines (qc_location_id) WHERE qc_location_id IS NOT NULL;


-- The QC location is a QUALITY_CONTROL area of the receipt's warehouse, and a line moves to QC only
-- once its receipt is confirmed (before that, nothing has been counted in).
CREATE FUNCTION procurement.check_goods_receipt_line_qc_move()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.qc_location_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM procurement.goods_receipts r
                    WHERE r.id = NEW.receipt_id AND r.status NOT IN ('DRAFT', 'CANCELLED')) THEN
        RAISE EXCEPTION 'receipt line moves to QC only once its receipt is confirmed'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM warehouse.area a
                     JOIN warehouse.storage_location s ON s.id = a.location_id
                     JOIN procurement.goods_receipts r ON r.warehouse_id = s.warehouse_id
                    WHERE a.location_id = NEW.qc_location_id AND a.type = 'QUALITY_CONTROL'
                      AND r.id = NEW.receipt_id) THEN
        RAISE EXCEPTION 'QC location of a receipt line must be a QUALITY_CONTROL area of the receipt''s warehouse'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_goods_receipt_lines_qc_move BEFORE INSERT OR UPDATE OF qc_location_id
    ON procurement.goods_receipt_lines
    FOR EACH ROW EXECUTE FUNCTION procurement.check_goods_receipt_line_qc_move();


-- BR-05. A line of a receipt past DRAFT keeps what was counted. Adding or removing a line is refused
-- too, except a delete cascading from the receipt itself (only a draft can be deleted that way, as
-- the receipt's own foreign keys from the stock ledger and invoices hold every other one).
CREATE FUNCTION procurement.check_goods_receipt_line_frozen()
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
       AND NEW.expiry_date IS NOT DISTINCT FROM OLD.expiry_date THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'receipt is %: its lines keep what was counted (BR-05)', receipt_status
        USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER tg_goods_receipt_lines_frozen BEFORE INSERT OR UPDATE OR DELETE
    ON procurement.goods_receipt_lines
    FOR EACH ROW EXECUTE FUNCTION procurement.check_goods_receipt_line_frozen();


-- BR-08: QC decides on goods that are in the QC area.
CREATE FUNCTION procurement.check_qc_inspection_moved()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM procurement.goods_receipt_lines l
                    WHERE l.id = NEW.receipt_line_id AND l.moved_to_qc_at IS NOT NULL) THEN
        RAISE EXCEPTION 'QC inspects only goods already moved to the QC area (BR-08)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_qc_inspections_moved BEFORE INSERT ON procurement.qc_inspections
    FOR EACH ROW EXECUTE FUNCTION procurement.check_qc_inspection_moved();
