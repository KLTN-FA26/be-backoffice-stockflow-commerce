-- The ledger names one carton per line, not one per side (owner decision 2026-11-26, ADR-0011, second step).
--
-- inventory.stock_movement kept two carton columns, from_stock_package_id and to_stock_package_id, from the time units
-- could be taken out of one carton and put into another. Stock is only ever whole sealed LPN cartons
-- (ck_stock_item_whole_cartons, ck_stock_package_standard_count): a line concerns exactly one carton, so the two
-- columns held the same carton, or one of them was NULL. Whether the carton arrived, moved or left is already said by
-- movement_type and by which of from_location_code / to_location_code is set (ck_stock_movement_shape). Written as
-- what it is:
--
--   inventory.stock_movement   from_stock_package_id, to_stock_package_id -> stock_package_id
--
-- A new line must name its carton (ck_stock_movement_names_carton). The CHECK is NOT VALID on purpose: lines of loose
-- stock from before everything was boxed, if a database somebody kept by hand has any, stay as they are; every line
-- written from now on is checked. No database built from the baseline has such a line (of the 19,352 ledger lines
-- looked at, none named no carton and none named two different ones).
--
-- With that rule the four CHECKs that each said "this kind of line names a carton" say nothing more and go:
-- ck_stock_movement_receipt_in_carton, ck_stock_movement_putaway_carton, ck_stock_movement_move_task_carton,
-- ck_stock_movement_put_back_carton. So does ck_stock_movement_package_side ("a side's carton needs that side's
-- location"): there is no side any more, and every line has a location (ck_stock_movement_shape).

-- ---------------------------------------------------------------------------------------------- 1. one column

-- Nothing is merged silently: a line that names two different cartons cannot be said in one column.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM inventory.stock_movement
                WHERE from_stock_package_id IS NOT NULL AND to_stock_package_id IS NOT NULL
                  AND from_stock_package_id <> to_stock_package_id) THEN
        RAISE EXCEPTION 'inventory.stock_movement has lines that name two different cartons; ADR-0011 step 2 keeps one '
            'carton per line' USING ERRCODE = 'check_violation';
    END IF;
END
$$;

DROP TRIGGER tg_stock_movement_package ON inventory.stock_movement;
DROP TRIGGER tg_stock_movement_manual_move ON inventory.stock_movement;

ALTER TABLE inventory.stock_movement ADD COLUMN stock_package_id uuid;

-- The ledger is append-only (tg_stock_movement_append_only); the backfill is the one write that is not a new line.
ALTER TABLE inventory.stock_movement DISABLE TRIGGER tg_stock_movement_append_only;

UPDATE inventory.stock_movement
   SET stock_package_id = COALESCE(to_stock_package_id, from_stock_package_id)
 WHERE to_stock_package_id IS NOT NULL OR from_stock_package_id IS NOT NULL;

ALTER TABLE inventory.stock_movement ENABLE TRIGGER tg_stock_movement_append_only;

-- The CHECKs and indexes that name a side go with the columns; those that are still rules come back in section 2.
DROP INDEX inventory.ix_stock_movement_from_package;
DROP INDEX inventory.ix_stock_movement_to_package;
DROP INDEX inventory.uk_stock_movement_return_receipt;

ALTER TABLE inventory.stock_movement
    DROP CONSTRAINT ck_stock_movement_package_side,
    DROP CONSTRAINT ck_stock_movement_receipt_in_carton,
    DROP CONSTRAINT ck_stock_movement_putaway_carton,
    DROP CONSTRAINT ck_stock_movement_move_task_carton,
    DROP CONSTRAINT ck_stock_movement_put_back_carton,
    DROP CONSTRAINT ck_stock_movement_return_receipt,
    DROP CONSTRAINT ck_stock_movement_return_refused,
    DROP CONSTRAINT ck_stock_movement_shape,
    DROP CONSTRAINT fk_stock_movement_from_package,
    DROP CONSTRAINT fk_stock_movement_to_package,
    DROP COLUMN from_stock_package_id,
    DROP COLUMN to_stock_package_id;

-- ---------------------------------------------------------------------------------------------- 2. its rules

ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT fk_stock_movement_package FOREIGN KEY (stock_package_id)
        REFERENCES inventory.stock_package(id) ON DELETE RESTRICT,
    -- The return receipt's line named its carton on the arriving side; a RECEIPT has only that side.
    ADD CONSTRAINT ck_stock_movement_return_receipt CHECK (
        reference_type <> 'GOODS_RECEIPT'
        OR (movement_type = 'RECEIPT' AND stock_package_id IS NOT NULL AND to_status = 'QUARANTINE')),
    -- The reversal named its carton on the leaving side; that it leaves is from_location_code set, to_location_code not.
    ADD CONSTRAINT ck_stock_movement_return_refused CHECK (
        reference_type <> 'RETURN_REQUEST'
        OR (movement_type = 'ADJUSTMENT' AND from_location_code IS NOT NULL AND to_location_code IS NULL
            AND stock_package_id IS NOT NULL AND from_status = 'QUARANTINE')),
    -- As in the baseline, word for word, but for the MOVE that stays in place: the boxing of loose stock
    -- (V20261111000110) is told by the carton the line names being its reference, where it read the arriving side.
    ADD CONSTRAINT ck_stock_movement_shape CHECK (
        CASE
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
                          OR (reference_type = 'STOCK_PACKAGE' AND NOT (stock_package_id IS DISTINCT FROM reference_id)))
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
            ELSE NULL::boolean
        END),
    ADD CONSTRAINT ck_stock_movement_names_carton CHECK (stock_package_id IS NOT NULL) NOT VALID;

COMMENT ON COLUMN inventory.stock_movement.stock_package_id IS
    'The carton the line is about: stock is whole sealed cartons, so a line is one carton, and movement_type with '
    'from_location_code / to_location_code says whether it arrived, moved or left (ADR-0011). Required on every line '
    'written since V20261231000200.';
COMMENT ON CONSTRAINT ck_stock_movement_names_carton ON inventory.stock_movement IS
    'ADR-0011: a ledger line names its carton. It replaces the CHECKs that said so kind by kind (kltn-docs 03 BR-12 a '
    'receipt, 05 BR-08 a putaway, 11 BR-04 a move by hand, 07 §4.5 a put-back). NOT VALID: loose lines before 4C-2 are '
    'history.';
COMMENT ON CONSTRAINT ck_stock_movement_return_receipt ON inventory.stock_movement IS
    'Kltn-docs 03 BR-14: a return receipt brings each carton back whole into QUARANTINE, one RECEIPT line per carton.';
COMMENT ON CONSTRAINT ck_stock_movement_return_refused ON inventory.stock_movement IS
    'WF-05 Q-4: a return refunded nothing, so the stock-in of its carton is reversed - the whole carton out of '
    'quarantine, naming the return request. The goods are the customer''s, held for them.';

-- A carton's history, newest first, is one index where it was two.
CREATE INDEX ix_stock_movement_package ON inventory.stock_movement (stock_package_id, occurred_at)
    WHERE stock_package_id IS NOT NULL;
-- One RECEIPT line per carton of a return receipt (03 BR-14).
CREATE UNIQUE INDEX uk_stock_movement_return_receipt ON inventory.stock_movement (reference_id, stock_package_id)
    WHERE reference_type = 'GOODS_RECEIPT';

-- ---------------------------------------------------------------------------------------------- 3. who read a side

-- Postgres does not track what a function body mentions: these three read the columns that are gone.

-- It looped over the two sides; there is one carton to check. Same two refusals.
CREATE OR REPLACE FUNCTION inventory.check_stock_movement_package() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    pkg_sku      VARCHAR(64);
    pkg_location VARCHAR(64);
BEGIN
    SELECT i.sku, p.location_code INTO pkg_sku, pkg_location
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE p.id = NEW.stock_package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    IF pkg_sku <> NEW.sku THEN
        RAISE EXCEPTION 'a % movement names a stock package of %', NEW.sku, pkg_sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF pkg_location IS DISTINCT FROM NEW.from_location_code
       AND pkg_location IS DISTINCT FROM NEW.to_location_code THEN
        RAISE EXCEPTION 'a movement from % to % names a stock package at %', NEW.from_location_code,
            NEW.to_location_code, pkg_location USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION inventory.check_stock_movement_package() IS
    'The carton a ledger line names is of the line''s SKU and stands at one of the line''s two places (where it was, or '
    'where the line put it: the carton is saved before its line).';

-- "The carton moved" was the leaving side; a MOVE has both places, so the carton the line names is the one that left.
CREATE OR REPLACE FUNCTION inventory.check_stock_movement_manual_move() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    held_lpn VARCHAR(30);
BEGIN
    SELECT p.lpn INTO held_lpn
      FROM inventory.stock_package p
     WHERE p.id = NEW.stock_package_id
       AND EXISTS (SELECT 1 FROM inventory.stock_item s WHERE s.stock_package_id = p.id AND s.reserved > 0);
    IF FOUND THEN
        RAISE EXCEPTION 'stock package % is held for an order; it is not moved by hand', held_lpn
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_movement_package BEFORE INSERT ON inventory.stock_movement
    FOR EACH ROW WHEN (NEW.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_movement_package();

CREATE TRIGGER tg_stock_movement_manual_move BEFORE INSERT ON inventory.stock_movement
    FOR EACH ROW WHEN (NEW.movement_type = 'MOVE' AND NEW.reference_type = 'MOVE_TASK'
                       AND NEW.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_movement_manual_move();

-- The latest definition (V20261223000320), with one change: "received into the area" was the carton on the arriving
-- side of a line into the area; it is the carton the line names, the line still being one into the area
-- (to_location_code = area_code).
CREATE OR REPLACE FUNCTION procurement.check_return_receipt_closed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    area_code VARCHAR(64);
    cartons   BIGINT;
    entered   BIGINT;
    loose     BIGINT;
    received  BIGINT;
    missing   TEXT;
    unmarked  TEXT;
    unowned   TEXT;
BEGIN
    SELECT location_code INTO area_code FROM warehouse.storage_location WHERE id = NEW.quarantine_location_id;
    SELECT count(*), count(*) FILTER (WHERE stock_entered) INTO cartons, entered
      FROM procurement.goods_receipt_return_cartons WHERE receipt_id = NEW.id;
    SELECT count(*) INTO loose FROM procurement.goods_receipt_return_loose_lines WHERE receipt_id = NEW.id;
    IF cartons = 0 AND loose = 0 THEN
        RAISE EXCEPTION 'return receipt % is closed with nothing received', NEW.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT string_agg(c.lpn, ', ' ORDER BY c.lpn) INTO missing
      FROM procurement.goods_receipt_return_cartons c
     WHERE c.receipt_id = NEW.id AND c.stock_entered
       AND NOT EXISTS (SELECT 1 FROM inventory.stock_movement m
                        WHERE m.reference_type = 'GOODS_RECEIPT' AND m.reference_id = NEW.id
                          AND m.stock_package_id = c.stock_package_id AND m.to_location_code = area_code);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt % is closed but carton(s) % were not received into %', NEW.receipt_number,
            missing, area_code USING ERRCODE = 'check_violation';
    END IF;
    -- Loose units and opened cartons have no stock entry: only the cartons that entered stock are in the ledger.
    SELECT count(*) INTO received FROM inventory.stock_movement
     WHERE reference_type = 'GOODS_RECEIPT' AND reference_id = NEW.id;
    IF received <> entered THEN
        RAISE EXCEPTION 'return receipt % puts % carton(s) into stock but the ledger received %', NEW.receipt_number,
            entered, received USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 Q-18: an opened carton stays out of stock, and is marked so it is never taken back again.
    SELECT string_agg(c.lpn, ', ' ORDER BY c.lpn) INTO unmarked
      FROM procurement.goods_receipt_return_cartons c
      JOIN inventory.stock_package p ON p.id = c.stock_package_id
     WHERE c.receipt_id = NEW.id AND NOT c.stock_entered
       AND (p.return_opened_at IS NULL OR p.status <> 'CONSUMED');
    IF unmarked IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt %: opened carton(s) % are not kept out of stock as opened', NEW.receipt_number,
            unmarked USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 Q-4 (H1): a sealed carton of a return request waits for its refund decision; one a carrier brought back is
    -- the company's.
    SELECT string_agg(c.lpn, ', ' ORDER BY c.lpn) INTO unowned
      FROM procurement.goods_receipt_return_cartons c
      JOIN inventory.stock_package p ON p.id = c.stock_package_id
     WHERE c.receipt_id = NEW.id AND c.stock_entered
       AND (CASE WHEN NEW.return_request_id IS NULL THEN p.return_ownership IS NOT NULL
                 ELSE p.return_request_id IS DISTINCT FROM NEW.return_request_id
                      OR p.return_ownership IS DISTINCT FROM 'PENDING' END);
    IF unowned IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt %: carton(s) % do not wait for the return''s refund decision as they came back '
            '(WF-05 Q-4)', NEW.receipt_number, unowned USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 Q-18 add-on: a receipt with an opened carton or a loose line is confirmed by someone who counted nothing
    -- on it.
    IF (loose > 0 OR EXISTS (SELECT 1 FROM procurement.goods_receipt_return_cartons c
                              WHERE c.receipt_id = NEW.id AND c.counted_qty < c.quantity))
       AND (EXISTS (SELECT 1 FROM procurement.goods_receipt_return_cartons c
                     WHERE c.receipt_id = NEW.id AND NEW.confirmed_by IN (c.scanned_by, c.counted_by))
            OR EXISTS (SELECT 1 FROM procurement.goods_receipt_return_loose_lines l
                        WHERE l.receipt_id = NEW.id AND l.recorded_by = NEW.confirmed_by)) THEN
        RAISE EXCEPTION 'return receipt %: confirmed by who counted it; a receipt with opened cartons or loose units is '
            'confirmed by someone else (WF-05 Q-18)', NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 BE-23: the return is told the draft is closed in the same transaction.
    IF NEW.return_request_id IS NOT NULL AND EXISTS (SELECT 1 FROM ordering.return_request r
                                                      WHERE r.id = NEW.return_request_id
                                                        AND r.open_draft_receipt_id = NEW.id) THEN
        RAISE EXCEPTION 'return receipt % is closed but its return still counts it as an open draft',
            NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 M1: and it has taken the receipt's goods, in the same transaction - never after a commit a refund decision
    -- could slip into.
    IF NEW.return_request_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.return_request_receipt t
                                                          WHERE t.goods_receipt_id = NEW.id
                                                            AND t.return_request_id = NEW.return_request_id) THEN
        RAISE EXCEPTION 'return receipt % is closed but its return has not taken its goods', NEW.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;
