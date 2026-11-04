-- =============================================================================
-- Receiving: the 2-step / 3-step inbound flow (docs 03 Receipt, 05 Putaway, 06 Map, 01 Product),
-- KLTN-FA26/docs commit 084365d. Decided 2026-10-09: the schema follows the docs.
--
--   3 steps (SKU with qc_required): supplier → RECEIVING → QUALITY_CONTROL → bin
--   2 steps (no QC):                supplier → RECEIVING → bin
--
-- What the docs needed and the schema did not have:
--   * an Area type QUALITY_CONTROL, distinct from QUARANTINE: QC happens in QUALITY_CONTROL;
--     QUARANTINE holds what QC put on hold or rejected;
--   * the "QC required on receipt" flag on the inventory item, and its snapshot on the receipt
--     line (changing the flag later does not change which flow an existing receipt follows);
--   * stock states INBOUND (received, not yet put away: RECEIVING or QUALITY_CONTROL) and BLOCKED
--     (rejected, waiting to go back to the supplier);
--   * receipt states CONFIRMED → IN_QC → IN_PUTAWAY → CLOSED (docs 03 §5.1). POSTED becomes
--     CONFIRMED; no row and no code used procurement.goods_receipts yet, so nothing is renamed
--     under anyone's feet;
--   * the "move to QC" task (docs 03 §5.3): a warehouse.move_task with origin RECEIPT_QC that
--     points at the receipt line it moves.
-- =============================================================================


-- 1. Map: the QC area.
ALTER TABLE warehouse.area DROP CONSTRAINT ck_area_type;
ALTER TABLE warehouse.area ADD CONSTRAINT ck_area_type CHECK (type IN
    ('RECEIVING', 'QUALITY_CONTROL', 'QUARANTINE', 'PACKING', 'DISPATCH', 'OVERFLOW', 'NON_STORAGE'));


-- 2. Inventory item: QC required on receipt (docs 01). Default off: the 2-step flow.
ALTER TABLE inventory.inventory_items
    ADD COLUMN qc_required BOOLEAN NOT NULL DEFAULT FALSE;


-- 3. Stock states. INBOUND and BLOCKED are never reservable (application: StockStatus).
ALTER TABLE inventory.stock_item DROP CONSTRAINT ck_stock_item_status;
ALTER TABLE inventory.stock_item ADD CONSTRAINT ck_stock_item_status CHECK (status IN
    ('INBOUND', 'AVAILABLE', 'QUARANTINE', 'BLOCKED', 'DAMAGED', 'EXPIRED'));

ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_status;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_status CHECK (
    (from_status IS NULL OR from_status IN ('INBOUND', 'AVAILABLE', 'QUARANTINE', 'BLOCKED', 'DAMAGED', 'EXPIRED'))
    AND (to_status IS NULL OR to_status IN ('INBOUND', 'AVAILABLE', 'QUARANTINE', 'BLOCKED', 'DAMAGED', 'EXPIRED')));


-- 4. Receipt lifecycle (docs 03 §5.1).
ALTER TABLE procurement.goods_receipts DROP CONSTRAINT ck_goods_receipts_status;
ALTER TABLE procurement.goods_receipts DROP CONSTRAINT ck_goods_receipts_posted;
ALTER TABLE procurement.goods_receipts DROP CONSTRAINT fk_goods_receipts_posted_by;
ALTER TABLE procurement.goods_receipts RENAME COLUMN posted_at TO confirmed_at;
ALTER TABLE procurement.goods_receipts RENAME COLUMN posted_by TO confirmed_by;
ALTER TABLE procurement.goods_receipts
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD CONSTRAINT fk_goods_receipts_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_goods_receipts_status CHECK (status IN
        ('DRAFT', 'CONFIRMED', 'IN_QC', 'IN_PUTAWAY', 'CLOSED', 'CANCELLED')),
    -- Every state past DRAFT was confirmed by someone, at a time; only DRAFT can be cancelled
    -- (BR-05: a confirmed receipt is corrected by reversal or adjustment, never deleted).
    ADD CONSTRAINT ck_goods_receipts_confirmed CHECK (status IN ('DRAFT', 'CANCELLED')
        OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    ADD CONSTRAINT ck_goods_receipts_cancelled CHECK (status <> 'CANCELLED' OR confirmed_at IS NULL),
    ADD CONSTRAINT ck_goods_receipts_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL));

-- The line keeps the flag it was received under (3 steps or 2), whatever the item says later.
ALTER TABLE procurement.goods_receipt_lines
    ADD COLUMN qc_required BOOLEAN NOT NULL DEFAULT FALSE;


-- 5. The "move to QC" task is a move task that knows its receipt line (docs 03 §5.3, BR-08).
ALTER TABLE warehouse.move_task
    ADD COLUMN receipt_line_id UUID,
    ADD CONSTRAINT fk_move_task_receipt_line FOREIGN KEY (receipt_line_id)
        REFERENCES procurement.goods_receipt_lines (id) ON DELETE RESTRICT;
ALTER TABLE warehouse.move_task DROP CONSTRAINT ck_move_task_origin;
ALTER TABLE warehouse.move_task ADD CONSTRAINT ck_move_task_origin
    CHECK (origin IN ('REPLENISHMENT', 'RESLOTTING', 'MANUAL', 'RECEIPT_QC'));
ALTER TABLE warehouse.move_task ADD CONSTRAINT ck_move_task_receipt_qc
    CHECK ((origin = 'RECEIPT_QC') = (receipt_line_id IS NOT NULL));
CREATE INDEX ix_move_task_receipt_line ON warehouse.move_task (receipt_line_id) WHERE receipt_line_id IS NOT NULL;

-- A QC move goes from a RECEIVING area to a QUALITY_CONTROL area of the receipt's warehouse, and
-- only for a line received under the 3-step flow (BR-07). The generic location check of
-- check_move_task_locations still applies.
CREATE FUNCTION warehouse.check_move_task_receipt_qc()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.origin <> 'RECEIPT_QC' THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM procurement.goods_receipt_lines l
                     JOIN procurement.goods_receipts r ON r.id = l.receipt_id
                    WHERE l.id = NEW.receipt_line_id AND l.qc_required AND r.warehouse_id = NEW.warehouse_id) THEN
        RAISE EXCEPTION 'QC move task % must belong to a QC-required line received in its warehouse', NEW.task_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM warehouse.area a WHERE a.location_id = NEW.from_location_id AND a.type = 'RECEIVING')
       OR NOT EXISTS (SELECT 1 FROM warehouse.area a WHERE a.location_id = NEW.to_location_id AND a.type = 'QUALITY_CONTROL') THEN
        RAISE EXCEPTION 'QC move task % must go from a RECEIVING area to a QUALITY_CONTROL area', NEW.task_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_move_task_receipt_qc BEFORE INSERT OR UPDATE OF origin, receipt_line_id, from_location_id,
    to_location_id, warehouse_id ON warehouse.move_task
    FOR EACH ROW EXECUTE FUNCTION warehouse.check_move_task_receipt_qc();
