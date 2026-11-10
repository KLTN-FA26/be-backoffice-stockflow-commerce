-- =============================================================================
-- Quarantine and release of whole cartons (team plan 4D-1; kltn-docs 20 §4.8, BR-01, BR-14, BR-21; 05 BR-01, BR-14,
-- §4.4).
--
-- A warehouse manager quarantines a whole carton found faulty after it was received: it goes to a QUARANTINE area, its
-- stock becomes QUARANTINE, one STATUS_CHANGE ledger line records it; no four eyes, because nothing changes in quantity
-- (20 §4.8, decided 2026-11-05). Only a free carton: one held for an order is reallocated first (BR-21). Releasing it
-- opens a putaway task whose source is QUARANTINE_RELEASE; the carton stays QUARANTINE until that task puts it into a
-- bin, which makes it AVAILABLE (05 §4.4, BR-14). Quarantined goods leave stock only through an approved adjustment
-- (BR-21: DAMAGED, by LPN). Each rule names the code that states it first.
--
--   1. warehouse.putaway_task.source: RECEIPT (a goods-receipt line's carton; the line is named) or QUARANTINE_RELEASE
--      (a released carton; no receipt line, a carton always) - 05 BR-01, the only two sources of a putaway task.
--      ck_putaway_task_source is rewritten for it. The legacy source, a return-request line (return_request_line_id,
--      never written by the application: the old return flow put goods away by reclassifying them), goes: its rows are
--      archived whole in platform.legacy_archive and deleted, then the column. The transfer source went with transfers
--      (V20261108000300). The source never changes once set. PutawayTask.open / releaseFromQuarantine.
--   2. A QUARANTINE_RELEASE task is opened only for a carton in stock whose units are all QUARANTINE
--      (check_putaway_task_package, on insert). CartonQuarantineService.release (STOCK_PACKAGE_NOT_QUARANTINED).
--   3. Quarantined stock is held for no order (ck_stock_item_quarantine_unheld): only a free carton is quarantined, and
--      quarantined stock is not allocated (20 BR-01, BR-21). StockOperationsServiceImpl.quarantine (STOCK_PACKAGE_HELD),
--      StockStatus.isReservable.
--   4. The quarantine's ledger line is a STATUS_CHANGE from where the carton stood to the QUARANTINE area (20 §4.8 step
--      1, BR-16: a STATUS_CHANGE is a quarantine or a release). ck_stock_movement_shape let a STATUS_CHANGE name one place
--      only; it now also lets a STOCK_PACKAGE line into QUARANTINE name two, because the carton moves while its status
--      changes and the ledger says both in one line. Every other branch is V20261111000110's.
--   5. Quarantined stock leaves QUARANTINE only by an approved adjustment (STOCK_ADJUSTMENT) or by its release's putaway
--      (PUTAWAY_TASK, to AVAILABLE); a line that keeps it QUARANTINE at another place (a move inside quarantine) is
--      free (ck_stock_movement_quarantine_exit). The references of the old flows that could take stock out of
--      quarantine (QC_INSPECTION, RETURN_REQUEST_LINE: receiving QC and customer returns, gone with team plan 4D-3/4D-4)
--      are allowed so the history and the return flow still standing until then pass.
--
-- Check 3 is validated, and the migration stops naming the rows when a QUARANTINE row holds units: left NOT VALID,
-- the check would still refuse every later update of such a row (a pick decrementing its hold included), so the row
-- is to be repaired first. Check 5 is on the append-only ledger, whose rows are never updated, so it is added NOT
-- VALID and validated only when no line written before breaks it.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. warehouse.putaway_task.source
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'warehouse.putaway_task', t.id, to_jsonb(t) FROM warehouse.putaway_task t
 WHERE t.return_request_line_id IS NOT NULL;
DELETE FROM warehouse.putaway_task WHERE return_request_line_id IS NOT NULL;

ALTER TABLE warehouse.putaway_task DROP CONSTRAINT ck_putaway_task_source;
ALTER TABLE warehouse.putaway_task DROP CONSTRAINT fk_putaway_task_return_line;
ALTER TABLE warehouse.putaway_task DROP COLUMN return_request_line_id;

-- Every remaining task names its receipt line (the old CHECK said exactly one source, and the other is gone). The
-- default is the source of every task written before; the application always names it.
ALTER TABLE warehouse.putaway_task ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'RECEIPT';
ALTER TABLE warehouse.putaway_task ADD CONSTRAINT ck_putaway_task_source CHECK (
       (source = 'RECEIPT' AND goods_receipt_line_id IS NOT NULL)
    OR (source = 'QUARANTINE_RELEASE' AND goods_receipt_line_id IS NULL AND stock_package_id IS NOT NULL));
COMMENT ON COLUMN warehouse.putaway_task.source IS
    'Why the task exists (05 BR-01): RECEIPT - a carton of a confirmed goods-receipt line; QUARANTINE_RELEASE - a carton '
    'a warehouse manager released from quarantine. Fixed once set.';
COMMENT ON COLUMN warehouse.putaway_task.goods_receipt_line_id IS
    'The goods-receipt line of a RECEIPT task; NULL for a QUARANTINE_RELEASE task.';

CREATE TRIGGER tg_putaway_task_source_fixed BEFORE UPDATE OF source ON warehouse.putaway_task
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('source');


-- ---------------------------------------------------------------------------------------------
-- 2. check_putaway_task_package: V20261020000500's, plus the QUARANTINE_RELEASE carton
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION warehouse.check_putaway_task_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    pkg_status VARCHAR(16);
    newly_set  BOOLEAN;
BEGIN
    SELECT p.status INTO pkg_status
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE p.id = NEW.stock_package_id AND i.sku = NEW.sku;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'putaway task for % names a stock package of another SKU', NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        newly_set := TRUE;
    ELSE
        newly_set := NEW.stock_package_id IS DISTINCT FROM OLD.stock_package_id;
    END IF;
    IF newly_set AND pkg_status NOT IN ('RECEIVING', 'IN_STOCK') THEN
        RAISE EXCEPTION 'putaway task for % names a stock package that is %', NEW.sku, pkg_status
            USING ERRCODE = 'check_violation';
    END IF;
    IF newly_set AND NEW.source = 'QUARANTINE_RELEASE' THEN
        IF pkg_status <> 'IN_STOCK'
           OR NOT EXISTS (SELECT 1 FROM inventory.stock_item s
                           WHERE s.stock_package_id = NEW.stock_package_id AND s.on_hand > 0)
           OR EXISTS (SELECT 1 FROM inventory.stock_item s
                       WHERE s.stock_package_id = NEW.stock_package_id AND s.on_hand > 0
                         AND s.status <> 'QUARANTINE') THEN
            RAISE EXCEPTION 'a quarantine release puts away a carton in stock whose units are all QUARANTINE'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 3. Quarantined stock is held for no order
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_item ADD CONSTRAINT ck_stock_item_quarantine_unheld
    CHECK (status <> 'QUARANTINE' OR reserved = 0) NOT VALID;
COMMENT ON CONSTRAINT ck_stock_item_quarantine_unheld ON inventory.stock_item IS
    'Kltn-docs 20 BR-01, BR-21: only a free carton is quarantined, and quarantined stock is never allocated.';


-- ---------------------------------------------------------------------------------------------
-- 4. ck_stock_movement_shape: a quarantine's STATUS_CHANGE names where the carton was and the QUARANTINE area
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_shape;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_shape CHECK (CASE
    WHEN movement_type IN ('RECEIPT', 'TRANSFER_IN', 'RETURN_IN')
        THEN from_location_code IS NULL AND to_location_code IS NOT NULL
    WHEN movement_type IN ('PICK', 'TRANSFER_OUT', 'SCRAP', 'CONSUMPTION', 'SUPPLIER_RETURN')
        THEN from_location_code IS NOT NULL AND to_location_code IS NULL
    WHEN movement_type = 'PUTAWAY'
        THEN from_location_code IS NOT NULL AND to_location_code IS NOT NULL
             AND from_location_code <> to_location_code
    WHEN movement_type = 'MOVE'
        THEN from_location_code IS NOT NULL AND to_location_code IS NOT NULL
             AND (from_location_code <> to_location_code
                  OR (reference_type = 'STOCK_PACKAGE' AND to_stock_package_id IS NOT DISTINCT FROM reference_id))
    WHEN movement_type = 'PUT_BACK'
        THEN to_location_code IS NOT NULL
             AND ((reference_type IN ('PICK_LINE', 'ORDER', 'DAMAGE_REPORT') AND from_location_code IS NULL)
                  OR (from_location_code IS NOT NULL AND from_location_code <> to_location_code))
    WHEN movement_type IN ('ADJUSTMENT', 'COUNT_CORRECTION')
        THEN num_nonnulls(from_location_code, to_location_code) = 1
    WHEN movement_type = 'STATUS_CHANGE'
        THEN from_location_code IS NOT NULL AND to_location_code IS NOT NULL
             AND from_status IS NOT NULL AND to_status IS NOT NULL AND from_status <> to_status
             AND (from_location_code = to_location_code
                  OR (reference_type = 'STOCK_PACKAGE' AND to_status = 'QUARANTINE'))
    END);


-- ---------------------------------------------------------------------------------------------
-- 5. Quarantined stock leaves QUARANTINE only by an approved adjustment or its release's putaway
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_quarantine_exit CHECK (
       from_status IS DISTINCT FROM 'QUARANTINE'
    OR (to_location_code IS NOT NULL AND to_status = 'QUARANTINE')
    OR reference_type = 'STOCK_ADJUSTMENT'
    OR (reference_type = 'PUTAWAY_TASK' AND to_status = 'AVAILABLE')
    OR reference_type IN ('QC_INSPECTION', 'RETURN_REQUEST_LINE')) NOT VALID;
COMMENT ON CONSTRAINT ck_stock_movement_quarantine_exit ON inventory.stock_movement IS
    'Kltn-docs 20 BR-21, 05 BR-14: QUARANTINE stock leaves quarantine by an approved adjustment or by the putaway of '
    'its release; QC_INSPECTION and RETURN_REQUEST_LINE are the old flows (team plan 4D-3/4D-4).';


-- ---------------------------------------------------------------------------------------------
-- Validate 3 and 5 where nothing breaks them
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    broken BIGINT;
BEGIN
    SELECT count(*) INTO broken FROM inventory.stock_item WHERE status = 'QUARANTINE' AND reserved > 0;
    IF broken > 0 THEN
        RAISE EXCEPTION 'V20261112000200: % QUARANTINE stock row(s) hold units for an order (%); release those holds first',
            broken, (SELECT string_agg(id::text, ', ') FROM (SELECT id FROM inventory.stock_item
                      WHERE status = 'QUARANTINE' AND reserved > 0 ORDER BY id LIMIT 20) held);
    END IF;
    ALTER TABLE inventory.stock_item VALIDATE CONSTRAINT ck_stock_item_quarantine_unheld;

    SELECT count(*) INTO broken FROM inventory.stock_movement
     WHERE from_status = 'QUARANTINE'
       AND NOT (to_location_code IS NOT NULL AND to_status = 'QUARANTINE')
       AND reference_type <> 'STOCK_ADJUSTMENT'
       AND NOT (reference_type = 'PUTAWAY_TASK' AND to_status = 'AVAILABLE')
       AND reference_type NOT IN ('QC_INSPECTION', 'RETURN_REQUEST_LINE');
    IF broken = 0 THEN
        ALTER TABLE inventory.stock_movement VALIDATE CONSTRAINT ck_stock_movement_quarantine_exit;
    ELSE
        RAISE NOTICE 'ck_stock_movement_quarantine_exit left NOT VALID: % ledger line(s) took stock out of quarantine',
            broken;
    END IF;
END
$$;
