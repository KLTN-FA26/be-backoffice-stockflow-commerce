-- =============================================================================
-- Per-unit damage reports go (team plan 5-6; kltn-docs 07 §4.4 step 9, 08 §4.2 step 4, 20 §4.8 and BR-21; removal
-- summary line 12: "hỏng thì xử lý nguyên thùng"; owner's decision QD-1 of 10/11: nothing is archived, nothing stops
-- the upgrade).
--
-- Damaged goods are handled whole, by carton: a picker reports the line's carton short with reason DAMAGED and the
-- coordinator reallocates it (4C-4), the check station removes a damaged carton and it goes whole into quarantine
-- (4C-6), and the warehouse manager releases a quarantined carton to a bin or writes it off with an approved DAMAGED
-- adjustment (4D-1). The unit-level damage report - on a shelf, at a pick line's bin, at the packing bench - with its
-- photos, its four-eyes decision (write off, back to the supplier, restore) and the hand-over to the supplier goes with
-- its code. What it left:
--
--   1. An impaired allocation: units of an order found damaged that nothing replaced, still HELD (on the loose DAMAGED
--      row in quarantine, or on the loose row a restore put them on), behind a SHORT pick line that names no carton.
--      A pick line is a carton (07 BR-04, BR-10), so that line cannot stay a line to reallocate: the hold is released
--      (reason DAMAGED; the units stay where they are, unheld) and the line is CANCELLED, short of its units for reason
--      DAMAGED. Its order keeps its INVENTORY hold, with nothing left to reallocate: the coordinator resumes it, and it
--      goes on without those units, or cancels it. QD-1 rebuilds the database on deploy, so no live order is expected
--      in this state; the cleanup only keeps the upgrade from stopping.
--   2. A picked line whose carton was found damaged at packing (packing_damaged_qty, the whole carton since 4C-4): its
--      units went back into quarantine and what replaced them has lines of its own. It is CANCELLED, so the check
--      station never receives it and no put-back waits for it - which is what packing_damaged_qty told both until now.
--   3. fulfillment.pick_line loses damaged_qty, damage_report_id (and its deferrable key) and packing_damaged_qty. The
--      CHECKs that named them are restated without them; tg_pick_line_carton_removed fires on the status alone.
--      ck_pick_line_lpn becomes "every line names its carton", ck_pick_line_put_back "a put-back returns what the line
--      picked" and ck_pick_line_short_reported "a SHORT line says why" - each added NOT VALID and validated at once
--      where no row written before breaks it (loose history from before 4C-1, or a line of 1), as V20261113000400 did.
--   4. inventory.stock_damage_supplier_shipment, stock_damage_disposition, stock_damage_photo and stock_damage_report
--      with their triggers, and the functions behind them.
--   5. Permission resource inventory-damage-reports: grants deleted, every holder's role version advanced (ADR-0008),
--      permission rows deleted.
--   6. Event publications of StockDamageReported / StockDamageDisposed not yet completed: deleted (no listener left).
--
-- Kept on purpose:
--   * Stock rows: DAMAGED units in quarantine stay on the books where the reports put them, unheld; the warehouse
--     manager writes them off with an approved DAMAGED adjustment, as a quarantined carton (20 §4.8 step 3).
--   * The ledger: stock_movement keeps the reference type DAMAGE_REPORT, the movement type SUPPLIER_RETURN and the
--     PUT_BACK from nowhere of a DAMAGE_REPORT in its CHECKs - lines written by damage reports are history (their
--     reference_id names a row this drops; the ledger has no key to it).
--   * StockStatus DAMAGED, ReleaseReason DAMAGED, ShortPickReason DAMAGED: live values, not the report's.
--
-- Photos on the object store (outside Flyway). The reports' photos are objects under qc-evidence/ (FileCategory
-- QC_EVIDENCE), with display copies under evidence-copies/qc-evidence/. Goods-receipt photos share the prefix, so it is
-- never deleted wholesale. Once this has run nothing claims a damage photo any more: the nightly orphaned-file sweep
-- (OrphanedFileSweeper, stockflow.storage.orphan-sweep.*) reports each one older than the grace period, and deletes it
-- with its copies when stockflow.storage.orphan-sweep.delete=true. To remove them at once instead, list the keys BEFORE
-- migrating - SELECT storage_key FROM inventory.stock_damage_photo - and delete each key and its two copies on the
-- bucket by hand. With versioning on, a delete is a delete marker, and a version under Object Lock stays for its
-- retention.
--
-- Changes data: 1 and 2 above, the dropped tables and columns, the permission rows and the publications.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Impaired allocations: the hold released, the carton-less SHORT line cancelled
-- ---------------------------------------------------------------------------------------------
WITH impaired AS (
    SELECT l.id, l.reservation_id
      FROM fulfillment.pick_line l
     WHERE l.status = 'SHORT' AND l.lpn IS NULL AND l.damage_report_id IS NOT NULL
), released AS (
    UPDATE inventory.stock_reservation r
       SET status = 'RELEASED', release_reason = 'DAMAGED', closed_at = NOW()
      FROM impaired i
     WHERE r.id = i.reservation_id AND r.status = 'HELD'
    RETURNING r.stock_item_id, r.quantity
), unheld AS (
    UPDATE inventory.stock_item s
       SET reserved = s.reserved - x.quantity, version = s.version + 1, last_modified_at = NOW(),
           last_modified_by = 'V20261114000400'
      FROM (SELECT stock_item_id, sum(quantity) AS quantity FROM released GROUP BY stock_item_id) x
     WHERE s.id = x.stock_item_id
    RETURNING s.id
)
UPDATE fulfillment.pick_line l
   SET status = 'CANCELLED', short_reason = 'DAMAGED', version = l.version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261114000400'
  FROM impaired i
 WHERE l.id = i.id;


-- ---------------------------------------------------------------------------------------------
-- 2. Picked lines whose carton went to quarantine from the packing bench
-- ---------------------------------------------------------------------------------------------
-- packing_damaged_qty goes to 0 in the same statement: ck_pick_line_packing_damaged, still in force here, allows units
-- damaged at packing only on a PICKED or PUT_BACK line, and the column is dropped in 3 anyway.
UPDATE fulfillment.pick_line
   SET status = 'CANCELLED', packing_damaged_qty = 0, version = version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261114000400'
 WHERE status = 'PICKED' AND packing_damaged_qty > 0;


-- ---------------------------------------------------------------------------------------------
-- 3. The pick line's damage columns, and the rules that named them
-- ---------------------------------------------------------------------------------------------
-- The trigger names packing_damaged_qty in its column list and its WHEN: it would block the drop.
DROP TRIGGER IF EXISTS tg_pick_line_carton_removed ON fulfillment.pick_line;

-- Dropped by name first: a CHECK naming a dropped column would otherwise go silently with it.
ALTER TABLE fulfillment.pick_line
    DROP CONSTRAINT IF EXISTS ck_pick_line_damaged,
    DROP CONSTRAINT IF EXISTS ck_pick_line_packing_damaged,
    DROP CONSTRAINT IF EXISTS ck_pick_line_packing_damaged_whole,
    DROP CONSTRAINT IF EXISTS ck_pick_line_within_allocation,
    DROP CONSTRAINT IF EXISTS ck_pick_line_put_back,
    DROP CONSTRAINT IF EXISTS ck_pick_line_lpn,
    DROP CONSTRAINT IF EXISTS ck_pick_line_short_reported,
    DROP CONSTRAINT IF EXISTS fk_pick_line_damage_report;

DROP INDEX IF EXISTS fulfillment.ix_pick_line_damage_report;

ALTER TABLE fulfillment.pick_line
    DROP COLUMN IF EXISTS damaged_qty,
    DROP COLUMN IF EXISTS damage_report_id,
    DROP COLUMN IF EXISTS packing_damaged_qty;

-- BR-04: picked and short together account for at most the allocation (every row keeps it: damaged units were a third
-- share of it).
ALTER TABLE fulfillment.pick_line ADD CONSTRAINT ck_pick_line_within_allocation
    CHECK (picked_qty + short_qty <= allocated_qty);

ALTER TABLE fulfillment.pick_line
    -- 07 BR-04, BR-10: a pick line is one carton, named by its LPN. PickLine.open.
    ADD CONSTRAINT ck_pick_line_lpn CHECK (lpn IS NOT NULL) NOT VALID,
    -- 07 §4.4 step 9: a short pick says why. PickList.reportShort.
    ADD CONSTRAINT ck_pick_line_short_reported CHECK (status <> 'SHORT' OR short_reason IS NOT NULL) NOT VALID,
    -- 07 BR-05: a put-back returns what the line picked, and something. PickLine.putBack.
    ADD CONSTRAINT ck_pick_line_put_back CHECK ((status = 'PUT_BACK') = (put_back_at IS NOT NULL)
        AND (status <> 'PUT_BACK' OR (put_back_qty = picked_qty AND put_back_qty > 0 AND put_back_by IS NOT NULL
                                      AND put_back_location_code IS NOT NULL))) NOT VALID;

COMMENT ON CONSTRAINT ck_pick_line_lpn ON fulfillment.pick_line IS
    'kltn-docs 07 BR-04, BR-10: a pick line is one carton, named by its LPN. NOT VALID where loose history exists '
    '(lines from before 4C-1, or the carton-less lines V20261114000400 cancelled).';
COMMENT ON CONSTRAINT ck_pick_line_short_reported ON fulfillment.pick_line IS
    'kltn-docs 07 §4.4 step 9: a short pick says why. NOT VALID where short lines without a reason exist.';
COMMENT ON CONSTRAINT ck_pick_line_put_back ON fulfillment.pick_line IS
    'kltn-docs 07 BR-05: a put-back returns what the line picked. NOT VALID where partial put-backs of before 4C-4 exist.';
COMMENT ON COLUMN fulfillment.pick_line.lpn IS
    'The carton the line takes: the LPN of its hold''s stock row (tg_pick_line_carton), scanned to confirm the line and '
    'again to put it back. NULL only on loose history (ck_pick_line_lpn).';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE lpn IS NULL) THEN
        ALTER TABLE fulfillment.pick_line VALIDATE CONSTRAINT ck_pick_line_lpn;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE status = 'SHORT' AND short_reason IS NULL) THEN
        ALTER TABLE fulfillment.pick_line VALIDATE CONSTRAINT ck_pick_line_short_reported;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line
                    WHERE (status = 'PUT_BACK') <> (put_back_at IS NOT NULL)
                       OR status = 'PUT_BACK' AND (put_back_qty <> picked_qty OR put_back_qty <= 0
                                                   OR put_back_by IS NULL OR put_back_location_code IS NULL)) THEN
        ALTER TABLE fulfillment.pick_line VALIDATE CONSTRAINT ck_pick_line_put_back;
    END IF;
END $$;

-- 4C-6: a carton live at the check station is not put back (V20261113000700's trigger, without the packing damage the
-- station's predecessor reported).
CREATE TRIGGER tg_pick_line_carton_removed
    BEFORE UPDATE OF status ON fulfillment.pick_line
    FOR EACH ROW WHEN (NEW.status = 'PUT_BACK' AND OLD.status IS DISTINCT FROM 'PUT_BACK')
    EXECUTE FUNCTION fulfillment.check_pick_line_carton_removed();


-- ---------------------------------------------------------------------------------------------
-- 4. The damage reports
-- ---------------------------------------------------------------------------------------------
DROP TABLE IF EXISTS inventory.stock_damage_supplier_shipment;
DROP TABLE IF EXISTS inventory.stock_damage_disposition;
DROP TABLE IF EXISTS inventory.stock_damage_photo;
DROP TABLE IF EXISTS inventory.stock_damage_report;

DROP FUNCTION IF EXISTS inventory.check_stock_damage_supplier_shipment();
DROP FUNCTION IF EXISTS inventory.check_stock_damage_disposition();
DROP FUNCTION IF EXISTS inventory.check_stock_damage_report_photo();
DROP FUNCTION IF EXISTS inventory.check_stock_damage_report_after_pick();


-- ---------------------------------------------------------------------------------------------
-- 5. Permission resource inventory-damage-reports
-- ---------------------------------------------------------------------------------------------
WITH revoked AS (
    DELETE FROM identity.role_permission rp
     USING identity.permission p
     WHERE p.id = rp.permission_id AND p.resource = 'inventory-damage-reports'
    RETURNING rp.role_id
)
-- Grants are cached per role version (ADR-0008): advance every role whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM revoked);

DELETE FROM identity.permission WHERE resource = 'inventory-damage-reports';


-- ---------------------------------------------------------------------------------------------
-- 6. Event publications of removed contracts
-- ---------------------------------------------------------------------------------------------
DELETE FROM event_publication
 WHERE completion_date IS NULL
   AND event_type IN ('com.stockflow.contracts.StockDamageReported', 'com.stockflow.contracts.StockDamageDisposed');
