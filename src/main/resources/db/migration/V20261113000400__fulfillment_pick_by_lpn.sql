-- =============================================================================
-- Picking by LPN only; put-back as whole cartons (team plan 4C-4; kltn-docs 07 §4.2 step 5, §4.4 step 9, §4.5 step 14,
-- BR-04, BR-05, BR-09, BR-10).
--
-- Since 4C-1/4C-2 every unit is in a whole carton and an allocation holds one carton. A pick line is that carton: it is
-- confirmed by scanning the line's location and the carton's own LPN (no SKU, no barcode, no quantity), a short pick is
-- reported per carton with a reason, and a cancelled order's carton goes back onto a bin whole, CONSUMED -> IN_STOCK
-- sealed (4C-2's transition), with one PUT_BACK ledger line naming it. This states the same in the database:
--
--   1. fulfillment.pick.status loses SHORT. No code ever wrote it (a short pick leaves the task PICKING and marks the
--      line); a row in it, which only a hand edit could make, is archived and goes back to PICKING.
--   2. pick_line.short_reason (NOT_FOUND | DAMAGED): why the picker could not take the carton (07 §4.4 step 9). A short
--      reported per carton is short of the whole line.
--   3. A line names its carton (lpn). See "Lines without an LPN" below.
--   4. A SHORT line says why: a reason, or the damage report that made it.
--   5. A put-back returns the whole carton the line took: put_back_qty = allocated_qty.
--   6. The ledger: a PUT_BACK of a pick line names the carton it goes back into (to_stock_package_id).
-- Rules 3-6 are CHECKs added NOT VALID and validated at once when no existing row breaks them, which is the case on a
-- database whose orders were all picked after 4C-1: a new or changed row is always checked, and only the history written
-- before (loose picks, partial picks of a carton) is spared.
--
-- Lines without an LPN. pick_line.lpn was NULL for a line of loose stock. Here:
--   * every line whose hold sits on a carton's stock row gets that carton's LPN, as V20261028003000 did - which is all
--     of them for orders allocated since 4C-1 boxed the live holds (a SHORT line keeps its NULL there, as 4C-1 named
--     OPEN lines only);
--   * an OPEN line still without one has no live hold to pick (released or consumed by something else): it is
--     CANCELLED - there is nothing on a shelf for it, and leaving it OPEN would keep its task from ever completing;
--   * it STOPS, naming them, instead of guessing, on a line that still has work to do and cannot do it by carton:
--       - an OPEN line whose live hold is loose (4C-1 boxed every live hold; finding one means the conversion was
--         bypassed);
--       - a SHORT line without an LPN that no damage report made: the coordinator reallocates it before the upgrade;
--       - a picked line of a cancelled task still waiting for its put-back that cannot go back as a whole carton - no
--         carton, a carton that is not CONSUMED, a hold that was not the whole carton, or units of it found damaged at
--         packing: put them back with the release before this one, which still books loose put-backs;
--   * the rest - loose lines picked, put back, cancelled or superseded before 4C-1 - are history: the ledger, damage
--     reports (fk RESTRICT) and packing of orders still in progress read them, so they are neither deleted nor given an
--     invented carton. Each line of the table that has no LPN at the start of this migration is archived as it was.
-- So lpn is not declared NOT NULL: rule 3 is the CHECK above, which a line made by a unit-level damage report may still
-- break - the SHORT line for damaged units nothing replaced holds them on a loose DAMAGED row in quarantine, with no
-- carton to name. Team plan 5-6 removes unit-level damage reports, and with them that exception, and then can make the
-- column NOT NULL once the history is archived.
--
-- Changes data: the rows of 1, the LPNs filled in and the OPEN lines cancelled, each archived first in
-- platform.legacy_archive.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 0. Stop before changing anything, naming what to finish first
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    problems TEXT;
BEGIN
    SELECT string_agg(format('task of order %s, line %s (%s, hold %s)', p.order_number, l.id, l.sku, l.reservation_id),
                      '; ' ORDER BY l.id)
      INTO problems
      FROM fulfillment.pick_line l
      JOIN fulfillment.pick p ON p.id = l.pick_id
      JOIN inventory.stock_reservation r ON r.id = l.reservation_id
      JOIN inventory.stock_item s ON s.id = r.stock_item_id
     WHERE l.status = 'OPEN' AND r.status = 'HELD' AND s.stock_package_id IS NULL;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261113000400: these open pick lines hold loose stock, which V20261111000110 should have put '
            'into cartons; a line is picked by its carton''s LPN (kltn-docs 07 BR-10). Box or release the stock first: %',
            problems;
    END IF;

    SELECT string_agg(format('task of order %s, line %s (%s)', p.order_number, l.id, l.sku), '; ' ORDER BY l.id)
      INTO problems
      FROM fulfillment.pick_line l
      JOIN fulfillment.pick p ON p.id = l.pick_id
     WHERE l.status = 'SHORT' AND l.damage_report_id IS NULL
       AND NOT EXISTS (SELECT 1
                         FROM inventory.stock_reservation r
                         JOIN inventory.stock_item s ON s.id = r.stock_item_id
                        WHERE r.id = l.reservation_id AND s.stock_package_id IS NOT NULL)
       AND l.lpn IS NULL;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261113000400: these short pick lines name no carton and their hold is on none; a short pick '
            'is reported and reallocated by carton (kltn-docs 07 BR-04, BR-09). Reallocate them first: %', problems;
    END IF;

    SELECT string_agg(format('task of order %s, line %s (%s x %s)', p.order_number, l.id,
                             l.picked_qty - l.packing_damaged_qty, l.sku), '; ' ORDER BY l.id)
      INTO problems
      FROM fulfillment.pick_line l
      JOIN fulfillment.pick p ON p.id = l.pick_id
      LEFT JOIN inventory.stock_reservation r ON r.id = l.reservation_id
      LEFT JOIN inventory.stock_item s ON s.id = r.stock_item_id
      LEFT JOIN inventory.stock_package c ON c.id = s.stock_package_id
     WHERE l.status = 'PICKED' AND p.status = 'CANCELLED' AND p.needs_put_back
       AND l.picked_qty > l.packing_damaged_qty
       AND (c.id IS NULL OR c.status <> 'CONSUMED' OR l.packing_damaged_qty > 0
            OR c.received_qty IS DISTINCT FROM r.quantity OR l.picked_qty <> r.quantity);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261113000400: these picked lines of cancelled orders wait for a put-back that cannot go '
            'back as one whole carton (kltn-docs 07 BR-05, §4.5 step 14); put them back with the release before this '
            'one: %', problems;
    END IF;
END $$;


-- ---------------------------------------------------------------------------------------------
-- 1. Archive what this changes, as it was
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'fulfillment.pick', p.id, to_jsonb(p) FROM fulfillment.pick p WHERE p.status = 'SHORT'
UNION ALL
SELECT 'fulfillment.pick_line', l.id, to_jsonb(l) FROM fulfillment.pick_line l WHERE l.lpn IS NULL;


-- ---------------------------------------------------------------------------------------------
-- 2. A task is never SHORT: its lines are (PickList.reportShort)
-- ---------------------------------------------------------------------------------------------
UPDATE fulfillment.pick
   SET status = 'PICKING', version = version + 1, last_modified_at = NOW(), last_modified_by = 'V20261113000400'
 WHERE status = 'SHORT';

ALTER TABLE fulfillment.pick DROP CONSTRAINT ck_pick_status;
ALTER TABLE fulfillment.pick
    ADD CONSTRAINT ck_pick_status CHECK (status IN ('PENDING', 'PICKING', 'PICKED', 'CANCELLED'));


-- ---------------------------------------------------------------------------------------------
-- 3. Every line its carton: filled in from the hold, or cancelled when there is nothing left to pick
-- ---------------------------------------------------------------------------------------------
UPDATE fulfillment.pick_line l
   SET lpn = c.lpn, version = l.version + 1, last_modified_at = NOW(), last_modified_by = 'V20261113000400'
  FROM inventory.stock_reservation r
  JOIN inventory.stock_item s ON s.id = r.stock_item_id
  JOIN inventory.stock_package c ON c.id = s.stock_package_id
 WHERE r.id = l.reservation_id
   AND l.lpn IS NULL;

UPDATE fulfillment.pick_line l
   SET status = 'CANCELLED', version = l.version + 1, last_modified_at = NOW(), last_modified_by = 'V20261113000400'
 WHERE l.status = 'OPEN' AND l.lpn IS NULL;


-- ---------------------------------------------------------------------------------------------
-- 4. Why a carton could not be taken
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.pick_line
    ADD COLUMN short_reason VARCHAR(16),
    ADD CONSTRAINT ck_pick_line_short_reason CHECK (short_reason IS NULL
        OR (short_reason IN ('NOT_FOUND', 'DAMAGED') AND short_qty = allocated_qty AND status IN ('SHORT', 'CANCELLED')));

COMMENT ON COLUMN fulfillment.pick_line.short_reason IS
    'kltn-docs 07 §4.4 step 9: why the picker could not take the line''s carton - NOT_FOUND (not at the bin) or DAMAGED '
    '(there, but not fit to ship). Set with the short; kept when the line is reallocated (CANCELLED).';
COMMENT ON COLUMN fulfillment.pick_line.lpn IS
    'The carton the line takes: the LPN of its hold''s stock row (tg_pick_line_carton), scanned to confirm the line and '
    'again to put it back. NULL only on loose history and on a SHORT line a unit-level damage report made '
    '(ck_pick_line_lpn).';


-- ---------------------------------------------------------------------------------------------
-- 5. The rules, for every new or changed row
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.pick_line
    ADD CONSTRAINT ck_pick_line_lpn CHECK (lpn IS NOT NULL OR damage_report_id IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_pick_line_short_reported CHECK (status <> 'SHORT' OR short_reason IS NOT NULL
        OR damage_report_id IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_pick_line_put_back_whole CHECK (status <> 'PUT_BACK' OR put_back_qty = allocated_qty) NOT VALID;

ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT ck_stock_movement_put_back_carton CHECK (movement_type <> 'PUT_BACK' OR reference_type <> 'PICK_LINE'
        OR to_stock_package_id IS NOT NULL) NOT VALID;

COMMENT ON CONSTRAINT ck_pick_line_lpn ON fulfillment.pick_line IS
    'kltn-docs 07 BR-04, BR-10: a pick line is one carton, named by its LPN. A SHORT line a unit-level damage report '
    'made is spared until team plan 5-6 removes such reports. NOT VALID where loose history exists.';
COMMENT ON CONSTRAINT ck_pick_line_short_reported ON fulfillment.pick_line IS
    'kltn-docs 07 §4.4 step 9: a short pick says why. NOT VALID where short lines without a reason exist.';
COMMENT ON CONSTRAINT ck_pick_line_put_back_whole ON fulfillment.pick_line IS
    'kltn-docs 07 BR-05, §4.5 step 14: a put-back returns the whole carton. NOT VALID where partial put-backs exist.';
COMMENT ON CONSTRAINT ck_stock_movement_put_back_carton ON inventory.stock_movement IS
    'kltn-docs 07 §4.5 step 14: a pick line''s put-back goes back into its carton. NOT VALID where loose put-backs exist.';

-- Validated at once where nothing written before breaks them, so a database without loose history checks every row.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE lpn IS NULL AND damage_report_id IS NULL) THEN
        ALTER TABLE fulfillment.pick_line VALIDATE CONSTRAINT ck_pick_line_lpn;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line
                    WHERE status = 'SHORT' AND short_reason IS NULL AND damage_report_id IS NULL) THEN
        ALTER TABLE fulfillment.pick_line VALIDATE CONSTRAINT ck_pick_line_short_reported;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE status = 'PUT_BACK' AND put_back_qty <> allocated_qty) THEN
        ALTER TABLE fulfillment.pick_line VALIDATE CONSTRAINT ck_pick_line_put_back_whole;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM inventory.stock_movement
                    WHERE movement_type = 'PUT_BACK' AND reference_type = 'PICK_LINE' AND to_stock_package_id IS NULL) THEN
        ALTER TABLE inventory.stock_movement VALIDATE CONSTRAINT ck_stock_movement_put_back_carton;
    END IF;
END $$;
