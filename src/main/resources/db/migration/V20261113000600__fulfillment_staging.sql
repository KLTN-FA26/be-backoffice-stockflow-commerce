-- =============================================================================
-- The staging area (STAGING) and the pick destination (team plan 4C-5; kltn-docs 07 §1, §4.1-§4.3, §4.5 steps 13-14,
-- §5, BR-07, BR-08, BR-11; 17 §4.4, BR-08; 20 §4.9, BR-20; 06 BR-11, BR-18, BR-22; 11 BR-09).
--
-- A pick task takes its cartons to one of two places, chosen by the warehouse manager while the task is PENDING:
--   * DISPATCH (the default): each carton is deducted when its LPN is scanned (CONSUMED), as before;
--   * STAGING: each carton is only moved, whole, from its bin to the task's staging area (one MOVE ledger line naming
--     the pick line); its allocation stays HELD on it, so the order still owns it and nobody else can promise it. The
--     line is STAGED, the task STAGED once every line is. Stock leaves the books only when the manager moves the whole
--     task to dispatch: every STAGED line is deducted there (PICK), and the task is PICKED. A cancelled order's STAGED
--     cartons go back to a bin by a MOVE, nothing having been deducted.
--
-- What this states in the database (each rule is first in the code named after it):
--   1. warehouse.area: STAGING is an area type; PACKING is gone - picking takes cartons straight to the dispatch area,
--      where they are checked and labelled (06 BR-22). An old PACKING area becomes a DISPATCH area, keeping its code and
--      location (the ledger and the pick lines refer to them). A STAGING area's location is never
--      pickable nor a putaway target (06 BR-18, BR-12: stock arrives there only by picking). An area holding stock does
--      not turn into a STAGING area nor out of one: its stock would change meaning under it. Area, the trigger.
--   2. inventory.stock_movement: stock moves into or out of a STAGING area only by picking - a MOVE naming a pick line
--      (stage, unstage) - and leaves it by a PICK (the move to dispatch); a count may still correct it (20 §4.9 step 4).
--      No other movement touches it (11 BR-09, 20 BR-20). StockOperationsServiceImpl.requireMovableBetween.
--      A pick line writes one MOVE out of each place it is moved from (uk_stock_movement_pick_line_move): staged out of
--      its bin once, put back out of the staging area once. The other movements stay one per pick line and type.
--   3. fulfillment.pick: destination (DISPATCH | STAGING), the staging area for a STAGING task, STAGED as a status,
--      and when / by whom a staged task was moved to dispatch. Destination and assignee change only while PENDING
--      (07 BR-08). PickJpaEntity.
--   4. fulfillment.pick_line: STAGED as a status, with when and where the carton was staged. A STAGED line is picked
--      in full by someone, not deducted; a line put back from staging was never deducted either. PickLine.
--
-- Changes data: the PACKING areas become DISPATCH areas. Nothing is archived and nothing stops the upgrade: the owner's
-- decision QD-1 (2026-11-10) rebuilds the database on deploy, so only the demo data of earlier migrations has to pass.
-- Every pick already written is a DISPATCH task (the column's default), which is what it was.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Area types: STAGING in, PACKING merged into DISPATCH
-- ---------------------------------------------------------------------------------------------
UPDATE warehouse.area
   SET type = 'DISPATCH', version = version + 1, last_modified_at = NOW(), last_modified_by = 'V20261113000600'
 WHERE type = 'PACKING';

ALTER TABLE warehouse.area DROP CONSTRAINT ck_area_type;
ALTER TABLE warehouse.area ADD CONSTRAINT ck_area_type CHECK (type IN
    ('RECEIVING', 'QUALITY_CONTROL', 'QUARANTINE', 'DISPATCH', 'STAGING', 'OVERFLOW', 'PRODUCTION', 'NON_STORAGE'));

-- A STAGING area is never where a picker takes stock from, nor where a putaway puts it (06 BR-12, BR-18); and an area
-- holding stock does not change into or out of STAGING: stock there is held for an order and out of ATP, stock
-- elsewhere is neither (20 BR-20).
CREATE FUNCTION warehouse.check_staging_area()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    loc warehouse.storage_location%ROWTYPE;
BEGIN
    IF NEW.location_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO loc FROM warehouse.storage_location WHERE id = NEW.location_id;
    IF NEW.type = 'STAGING' AND (loc.is_pickable OR loc.is_putaway_target) THEN
        RAISE EXCEPTION 'staging area % (%) is neither pickable nor a putaway target', NEW.code, loc.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND (OLD.type = 'STAGING') <> (NEW.type = 'STAGING')
       AND EXISTS (SELECT 1 FROM inventory.stock_item s WHERE s.location_code = loc.location_code AND s.on_hand > 0) THEN
        RAISE EXCEPTION 'area % (%) holds stock; it does not turn % a staging area while it does', NEW.code,
            loc.location_code, CASE WHEN NEW.type = 'STAGING' THEN 'into' ELSE 'out of' END
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_area_staging BEFORE INSERT OR UPDATE OF type, location_id ON warehouse.area
    FOR EACH ROW EXECUTE FUNCTION warehouse.check_staging_area();

CREATE FUNCTION warehouse.check_staging_location_flags()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (NEW.is_pickable OR NEW.is_putaway_target)
       AND EXISTS (SELECT 1 FROM warehouse.area a WHERE a.location_id = NEW.id AND a.type = 'STAGING') THEN
        RAISE EXCEPTION 'location % is a staging area: neither pickable nor a putaway target', NEW.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_storage_location_staging BEFORE UPDATE OF is_pickable, is_putaway_target
    ON warehouse.storage_location
    FOR EACH ROW EXECUTE FUNCTION warehouse.check_staging_location_flags();


-- ---------------------------------------------------------------------------------------------
-- 2. The ledger: a staging area is entered and left only by picking
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION inventory.check_staging_movement()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT ((NEW.movement_type = 'MOVE' AND NEW.reference_type = 'PICK_LINE')
            OR NEW.movement_type IN ('PICK', 'ADJUSTMENT', 'COUNT_CORRECTION'))
       AND EXISTS (SELECT 1
                     FROM warehouse.area a
                     JOIN warehouse.storage_location l ON l.id = a.location_id
                    WHERE a.type = 'STAGING'
                      AND l.location_code IN (NEW.from_location_code, NEW.to_location_code)) THEN
        RAISE EXCEPTION 'a % (reference %) cannot touch staging area %: stock enters and leaves it only by picking '
            '(kltn-docs 11 BR-09, 20 BR-20)', NEW.movement_type, NEW.reference_type,
            coalesce(NEW.to_location_code, NEW.from_location_code) USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_movement_staging BEFORE INSERT ON inventory.stock_movement
    FOR EACH ROW EXECUTE FUNCTION inventory.check_staging_movement();

-- One movement of each type per pick line, as before - but MOVE twice: staged out of the bin, and, the order cancelled,
-- put back out of the staging area. Each place is left once.
DROP INDEX inventory.uk_stock_movement_pick_line;
CREATE UNIQUE INDEX uk_stock_movement_pick_line ON inventory.stock_movement (movement_type, reference_id)
    WHERE reference_type = 'PICK_LINE' AND movement_type <> 'MOVE';
CREATE UNIQUE INDEX uk_stock_movement_pick_line_move ON inventory.stock_movement (reference_id, from_location_code)
    WHERE reference_type = 'PICK_LINE' AND movement_type = 'MOVE';


-- ---------------------------------------------------------------------------------------------
-- 3. The task: destination, staging area, STAGED, moved to dispatch
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.pick
    ADD COLUMN destination           VARCHAR(16) NOT NULL DEFAULT 'DISPATCH',
    ADD COLUMN staging_location_code VARCHAR(64),
    ADD COLUMN staged_at             TIMESTAMPTZ,
    ADD COLUMN moved_to_dispatch_at  TIMESTAMPTZ,
    ADD COLUMN moved_to_dispatch_by  UUID,
    ADD CONSTRAINT fk_pick_staging_location FOREIGN KEY (staging_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_pick_moved_to_dispatch_by FOREIGN KEY (moved_to_dispatch_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_pick_destination CHECK (destination IN ('DISPATCH', 'STAGING')),
    -- A STAGING task names the staging area its cartons go to; a DISPATCH task names none.
    ADD CONSTRAINT ck_pick_staging_location CHECK ((destination = 'STAGING') = (staging_location_code IS NOT NULL)),
    -- Moved to dispatch: only a staged STAGING task, by someone, once.
    ADD CONSTRAINT ck_pick_moved_to_dispatch CHECK ((moved_to_dispatch_at IS NULL) = (moved_to_dispatch_by IS NULL)
        AND (moved_to_dispatch_at IS NULL OR (destination = 'STAGING' AND staged_at IS NOT NULL))),
    -- STAGED: a STAGING task whose cartons are all at the staging area, not moved to dispatch yet.
    ADD CONSTRAINT ck_pick_staged CHECK (status <> 'STAGED'
        OR (destination = 'STAGING' AND staged_at IS NOT NULL AND moved_to_dispatch_at IS NULL));

ALTER TABLE fulfillment.pick DROP CONSTRAINT ck_pick_status;
ALTER TABLE fulfillment.pick
    ADD CONSTRAINT ck_pick_status CHECK (status IN ('PENDING', 'PICKING', 'STAGED', 'PICKED', 'CANCELLED'));

-- 07 BR-08: the assignee and the destination change only while the task is PENDING; and the staging area named is a
-- STAGING area of the task's own warehouse.
CREATE FUNCTION fulfillment.check_pick_pending_edits()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.status <> 'PENDING'
       AND (NEW.assigned_user_id IS DISTINCT FROM OLD.assigned_user_id
            OR NEW.destination IS DISTINCT FROM OLD.destination
            OR NEW.staging_location_code IS DISTINCT FROM OLD.staging_location_code) THEN
        RAISE EXCEPTION 'pick % is %: its assignee and destination change only while it is PENDING', NEW.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.staging_location_code IS NOT NULL AND (TG_OP = 'INSERT'
            OR NEW.staging_location_code IS DISTINCT FROM OLD.staging_location_code)
       AND NOT EXISTS (SELECT 1
                         FROM warehouse.storage_location l
                         JOIN warehouse.area a ON a.location_id = l.id
                        WHERE l.location_code = NEW.staging_location_code
                          AND l.warehouse_id = NEW.warehouse_id
                          AND a.type = 'STAGING') THEN
        RAISE EXCEPTION 'pick % names % as its staging area; it is not a staging area of warehouse %', NEW.id,
            NEW.staging_location_code, NEW.warehouse_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_pick_pending_edits BEFORE INSERT OR UPDATE ON fulfillment.pick
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_pick_pending_edits();

COMMENT ON COLUMN fulfillment.pick.destination IS
    'kltn-docs 07 §1: where picked cartons go - DISPATCH (deducted when scanned) or STAGING (moved, held for the order).';
COMMENT ON COLUMN fulfillment.pick.staging_location_code IS
    'The STAGING area of the task''s warehouse its cartons are moved to; set only for a STAGING task.';
COMMENT ON COLUMN fulfillment.pick.moved_to_dispatch_at IS
    'kltn-docs 07 §4.3 step 8: when the warehouse manager moved the staged task to dispatch, deducting its cartons.';


-- ---------------------------------------------------------------------------------------------
-- 4. The line: STAGED
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.pick_line
    ADD COLUMN staged_at             TIMESTAMPTZ,
    ADD COLUMN staged_location_code  VARCHAR(64),
    ADD CONSTRAINT fk_pick_line_staged_location FOREIGN KEY (staged_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    -- Staged: when and where together; a STAGED line is not deducted (consumed_at NULL) until the task moves.
    ADD CONSTRAINT ck_pick_line_staged CHECK ((staged_at IS NULL) = (staged_location_code IS NULL)
        AND (status <> 'STAGED' OR (staged_at IS NOT NULL AND consumed_at IS NULL)));

ALTER TABLE fulfillment.pick_line DROP CONSTRAINT ck_pick_line_status;
ALTER TABLE fulfillment.pick_line ADD CONSTRAINT ck_pick_line_status
    CHECK (status IN ('OPEN', 'STAGED', 'PICKED', 'SHORT', 'CANCELLED', 'PUT_BACK'));

-- Picked, staged, or put back means the whole carton taken, by someone, at a time. PICKED is deducted; a line staged and
-- never deducted may be STAGED, or PUT_BACK from the staging area (07 §4.5 step 14).
ALTER TABLE fulfillment.pick_line DROP CONSTRAINT ck_pick_line_picked;
ALTER TABLE fulfillment.pick_line ADD CONSTRAINT ck_pick_line_picked CHECK (status NOT IN ('PICKED', 'STAGED', 'PUT_BACK')
    OR (picked_qty = allocated_qty AND picked_by IS NOT NULL AND picked_at IS NOT NULL
        AND (reservation_id IS NULL OR consumed_at IS NOT NULL OR (status <> 'PICKED' AND staged_at IS NOT NULL))));

COMMENT ON COLUMN fulfillment.pick_line.staged_at IS
    'kltn-docs 07 §4.2 step 5: when the carton was moved to the staging area (MOVE, reference PICK_LINE), not deducted.';
COMMENT ON COLUMN fulfillment.pick_line.staged_location_code IS
    'The staging area the carton was moved to; kept after the task moved to dispatch or the carton was put back.';
