-- =============================================================================
-- SCRUM-267 / 274 / 275: a pick is confirmed line by line with a scan, the stock is deducted when the
-- line is confirmed, and goods picked for an order cancelled afterwards are put back (kltn-docs 07
-- step 6, Output, BR-04, BR-05; 17 §4.5 step 15).
--
--   pick_line   gains what the confirmation and the put-back record: when the hold was consumed, what
--               was scanned, and where, by whom and when the goods went back. A new status PUT_BACK.
--               Every rule is also in the aggregate (fulfillment.internal.domain.PickList):
--                 * PICKED means picked in full by someone at some time AND, for a line that consumes a
--                   hold, deducted: a line cannot say PICKED while the stock was never taken off the
--                   books (in-process consumption, decision G4 §6 #7);
--                 * PUT_BACK means everything picked went back onto a known bin, by someone, at a time;
--                 * one live line per hold, so generating the lines twice cannot pick a hold twice.
--   stock_movement  the ledger accepts a put-back that comes from nowhere: the goods were deducted when
--               they were picked, so they return to a bin without a source location. Still only from
--               a pick line, and a pick line deducts once and is put back once (unique index).
--
-- Data: nothing in the application wrote pick_line or a PICK_LINE ledger row before this, so the
-- tightened checks hold for every existing row. No permission changes.
-- =============================================================================

-- ---- 1. pick_line: confirmation and put-back --------------------------------
ALTER TABLE fulfillment.pick_line
    ADD COLUMN consumed_at            TIMESTAMPTZ,
    ADD COLUMN scanned_location_code  VARCHAR(64),
    ADD COLUMN scanned_code           VARCHAR(64),
    ADD COLUMN put_back_qty           INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN put_back_location_code VARCHAR(64),
    ADD COLUMN put_back_by            UUID,
    ADD COLUMN put_back_at            TIMESTAMPTZ,
    ADD CONSTRAINT fk_pick_line_put_back_location FOREIGN KEY (put_back_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_pick_line_put_back_by FOREIGN KEY (put_back_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_pick_line_put_back_qty CHECK (put_back_qty >= 0 AND put_back_qty <= picked_qty);

ALTER TABLE fulfillment.pick_line DROP CONSTRAINT ck_pick_line_status;
ALTER TABLE fulfillment.pick_line ADD CONSTRAINT ck_pick_line_status
    CHECK (status IN ('OPEN', 'PICKED', 'SHORT', 'CANCELLED', 'PUT_BACK'));

-- Lines written before this file (the generated test seed loads PICKED lines on dev and deployed databases;
-- the application wrote none) whose hold was consumed: the deduction happened when the hold closed. A
-- PICKED line whose hold was never consumed has no honest consumed_at: fail loudly, naming the rows.
UPDATE fulfillment.pick_line pl
   SET consumed_at = COALESCE(r.closed_at, pl.picked_at)
  FROM inventory.stock_reservation r
 WHERE r.id = pl.reservation_id
   AND r.status = 'CONSUMED'
   AND pl.status = 'PICKED'
   AND pl.consumed_at IS NULL;

DO $$
DECLARE
    orphans TEXT;
BEGIN
    SELECT string_agg(id::text, ', ') INTO orphans
      FROM (SELECT id FROM fulfillment.pick_line
             WHERE status = 'PICKED' AND reservation_id IS NOT NULL AND consumed_at IS NULL
             ORDER BY id LIMIT 20) rows;
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'SCRUM-267: PICKED pick lines whose hold was never consumed (first 20): %', orphans;
    END IF;
END $$;

-- Picked (and later put back) means picked in full, by someone, at a time - and deducted.
ALTER TABLE fulfillment.pick_line DROP CONSTRAINT ck_pick_line_picked;
ALTER TABLE fulfillment.pick_line ADD CONSTRAINT ck_pick_line_picked CHECK (status NOT IN ('PICKED', 'PUT_BACK')
    OR (picked_qty = allocated_qty AND picked_by IS NOT NULL AND picked_at IS NOT NULL
        AND (reservation_id IS NULL OR consumed_at IS NOT NULL)));

-- Put back means all of it, onto a bin, by someone; and only a put-back has a put-back time.
ALTER TABLE fulfillment.pick_line ADD CONSTRAINT ck_pick_line_put_back CHECK (
    (status = 'PUT_BACK') = (put_back_at IS NOT NULL)
    AND (status <> 'PUT_BACK' OR (put_back_qty = picked_qty AND put_back_by IS NOT NULL
                                  AND put_back_location_code IS NOT NULL)));

-- One live pick line per hold: generating a task's lines twice, or two requests generating them at
-- once, cannot pick the same hold twice. A cancelled line frees its hold for a re-allocation.
CREATE UNIQUE INDEX uk_pick_line_reservation_live ON fulfillment.pick_line (reservation_id)
    WHERE reservation_id IS NOT NULL AND status <> 'CANCELLED';

-- The put-back queue: cancelled picks whose goods are still off their bins.
CREATE INDEX ix_pick_put_back ON fulfillment.pick (warehouse_id, created_at) WHERE needs_put_back;

COMMENT ON COLUMN fulfillment.pick_line.consumed_at IS
    'When the hold behind the line was deducted (inventory.stock_movement PICK, reference PICK_LINE).';
COMMENT ON COLUMN fulfillment.pick_line.scanned_location_code IS 'The location code the picker scanned.';
COMMENT ON COLUMN fulfillment.pick_line.scanned_code IS 'The SKU or barcode the picker scanned.';
COMMENT ON COLUMN fulfillment.pick_line.put_back_location_code IS
    'The bin the goods of a cancelled order went back onto (docs 07 BR-05).';


-- ---- 2. the ledger: a put-back from a pick line has no source location ---------
-- Every other branch is the one V20260928005000 created, unchanged; PUT_BACK leaves the
-- PUTAWAY/MOVE group for a branch of its own.
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_shape;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_shape CHECK (CASE
    WHEN movement_type IN ('RECEIPT', 'TRANSFER_IN', 'RETURN_IN')
        THEN from_location_code IS NULL AND to_location_code IS NOT NULL
    WHEN movement_type IN ('PICK', 'TRANSFER_OUT', 'SCRAP')
        THEN from_location_code IS NOT NULL AND to_location_code IS NULL
    WHEN movement_type IN ('PUTAWAY', 'MOVE')
        THEN from_location_code IS NOT NULL AND to_location_code IS NOT NULL
             AND from_location_code <> to_location_code
    -- deducted when picked, so back onto a bin from nowhere; or moved back from a staging location
    WHEN movement_type = 'PUT_BACK'
        THEN to_location_code IS NOT NULL
             AND ((reference_type = 'PICK_LINE' AND from_location_code IS NULL)
                  OR (from_location_code IS NOT NULL AND from_location_code <> to_location_code))
    WHEN movement_type IN ('ADJUSTMENT', 'COUNT_CORRECTION')
        THEN num_nonnulls(from_location_code, to_location_code) = 1
    WHEN movement_type = 'STATUS_CHANGE'
        THEN from_location_code = to_location_code
             AND from_status IS NOT NULL AND to_status IS NOT NULL AND from_status <> to_status
    END);

-- A pick line deducts once and is put back once, whatever happens to the application's own check.
CREATE UNIQUE INDEX uk_stock_movement_pick_line ON inventory.stock_movement (movement_type, reference_id)
    WHERE reference_type = 'PICK_LINE';
