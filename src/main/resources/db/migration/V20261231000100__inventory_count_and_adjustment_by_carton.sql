-- Counts and adjustments say cartons, not units (owner decision 2026-11-26, ADR-0011, first step).
--
-- Stock is only ever whole LPN cartons of the SKU's standard count (ck_stock_item_whole_cartons,
-- ck_stock_package_standard_count), so the unit figures these two documents kept could take two values each: a count
-- line's expected_qty and counted_qty were 0 or the carton's standard count, an adjustment's quantity_delta plus or
-- minus it. They were a yes/no and a direction written as integers, with a trigger to refuse every other figure.
-- Written as what they are:
--
--   inventory.cycle_count_line   expected_qty -> booked (the books had the carton at its bin)
--                                counted_qty  -> found  (it was scanned there; NULL until counted)
--   inventory.stock_adjustment   quantity_delta -> direction (WRITE_OFF | RESTOCK)
--
-- How many units a carton stands for is read from the carton (inventory.stock_package.nominal_qty) by whoever needs
-- it - the posting of an approved adjustment, the ledger line it writes.
--
-- Both documents name their carton from now on (stock_package_id NOT NULL). Lines and adjustments of loose stock, from
-- before counts were by carton, are removed: no database holds any (the baseline boxed all loose stock, and every
-- database is rebuilt from the demo seed on deploy, owner decision QD-1), so the two DELETEs are there for a database
-- somebody kept by hand.

-- ---------------------------------------------------------------------------------------------- 1. loose history

-- An adjustment goes when it names no carton, or is the variance of a line that names none; then the loose lines.
DELETE FROM inventory.stock_adjustment a
 WHERE a.stock_package_id IS NULL
    OR EXISTS (SELECT 1 FROM inventory.cycle_count_line l
                WHERE l.id = a.cycle_count_line_id AND l.stock_package_id IS NULL);
DELETE FROM inventory.cycle_count_line WHERE stock_package_id IS NULL;

-- ---------------------------------------------------------------------------------------------- 2. count lines

DROP TRIGGER tg_cycle_count_line_carton ON inventory.cycle_count_line;
DROP TRIGGER tg_cycle_count_line_package ON inventory.cycle_count_line;

ALTER TABLE inventory.cycle_count_line
    ADD COLUMN booked boolean,
    ADD COLUMN found  boolean;

UPDATE inventory.cycle_count_line
   SET booked = expected_qty > 0,
       found  = CASE WHEN counted_qty IS NULL THEN NULL ELSE counted_qty > 0 END;

ALTER TABLE inventory.cycle_count_line
    DROP CONSTRAINT ck_cycle_count_line_counted,
    DROP CONSTRAINT ck_cycle_count_line_qty,
    DROP COLUMN expected_qty,
    DROP COLUMN counted_qty,
    ALTER COLUMN booked SET NOT NULL,
    ALTER COLUMN stock_package_id SET NOT NULL,
    ADD CONSTRAINT ck_cycle_count_line_counted CHECK (
        (found IS NULL AND counted_by IS NULL AND counted_at IS NULL)
        OR (found IS NOT NULL AND counted_by IS NOT NULL AND counted_at IS NOT NULL));

COMMENT ON COLUMN inventory.cycle_count_line.booked IS
    'Whether the books had the carton at this bin: when the line was planned, then read again under the carton''s lock '
    'when it is counted (kltn-docs 20 BR-09).';
COMMENT ON COLUMN inventory.cycle_count_line.found IS
    'Whether the carton was scanned at this bin; NULL until the line is counted. Never a number of units: the carton is '
    'not opened (20 BR-14).';

-- A line of loose stock was unique by bin, SKU and lot; there is none left to tell apart.
DROP INDEX inventory.uk_cycle_count_line_slot;

CREATE OR REPLACE FUNCTION inventory.check_cycle_count_line_carton() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- BR-12: at most 500 cartons on one count, lines found on the shelf included. The count row is locked by every
    -- writer of its lines (CycleCountRepository.findByIdForUpdate), so the tally cannot race.
    IF (SELECT count(*) FROM inventory.cycle_count_line WHERE cycle_count_id = NEW.cycle_count_id) >= 500 THEN
        RAISE EXCEPTION 'count % already has 500 lines; kltn-docs 20 BR-12 allows no more', NEW.cycle_count_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION inventory.check_cycle_count_line_carton() IS
    'kltn-docs 20 BR-12: a count has at most 500 lines. That a line names a carton and states it present or not found '
    '(BR-14) is the table itself since V20261231000100: stock_package_id NOT NULL, booked and found booleans.';

CREATE TRIGGER tg_cycle_count_line_carton BEFORE INSERT ON inventory.cycle_count_line
    FOR EACH ROW EXECUTE FUNCTION inventory.check_cycle_count_line_carton();

CREATE TRIGGER tg_cycle_count_line_package BEFORE INSERT OR UPDATE OF stock_package_id, sku, location_code
    ON inventory.cycle_count_line FOR EACH ROW EXECUTE FUNCTION inventory.check_document_stock_package();

-- ---------------------------------------------------------------------------------------------- 3. adjustments

DROP TRIGGER tg_stock_adjustment_package ON inventory.stock_adjustment;

ALTER TABLE inventory.stock_adjustment ADD COLUMN direction varchar(16);

UPDATE inventory.stock_adjustment
   SET direction = CASE WHEN quantity_delta < 0 THEN 'WRITE_OFF' ELSE 'RESTOCK' END;

ALTER TABLE inventory.stock_adjustment
    DROP CONSTRAINT ck_stock_adjustment_delta,
    DROP CONSTRAINT ck_stock_adjustment_reservation_scrap,
    DROP CONSTRAINT ck_stock_adjustment_writes_down,
    DROP COLUMN quantity_delta,
    ALTER COLUMN direction SET NOT NULL,
    ALTER COLUMN stock_package_id SET NOT NULL,
    ADD CONSTRAINT ck_stock_adjustment_direction CHECK (direction IN ('WRITE_OFF', 'RESTOCK')),
    ADD CONSTRAINT ck_stock_adjustment_reservation_scrap CHECK (
        reservation_id IS NULL OR (direction = 'WRITE_OFF' AND reason_code = 'SCRAP')),
    ADD CONSTRAINT ck_stock_adjustment_writes_down CHECK (
        reason_code NOT IN ('SCRAP', 'SAMPLE') OR direction = 'WRITE_OFF');

COMMENT ON COLUMN inventory.stock_adjustment.direction IS
    'WRITE_OFF takes the whole carton out of stock, RESTOCK puts a LOST carton back whole (kltn-docs 20 §4.3, BR-14). '
    'The units it stands for are the carton''s nominal_qty.';

CREATE OR REPLACE FUNCTION inventory.check_stock_adjustment_whole_carton() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.reason_code NOT IN ('DAMAGED', 'LOST', 'FOUND', 'DATA_CORRECTION', 'OTHER', 'COUNT_VARIANCE') THEN
        RAISE EXCEPTION 'adjustment % of a carton gives reason %; kltn-docs 20 BR-04 lists six', NEW.adjustment_number,
            NEW.reason_code USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION inventory.check_stock_adjustment_whole_carton() IS
    'kltn-docs 20 §4.3, BR-04: a new adjustment gives one of the six reasons. That it names a carton and takes it whole '
    '(BR-14) is the table itself since V20261231000100: stock_package_id NOT NULL and a direction, no quantity.';

CREATE TRIGGER tg_stock_adjustment_package BEFORE INSERT OR UPDATE OF stock_package_id, sku, location_code
    ON inventory.stock_adjustment FOR EACH ROW EXECUTE FUNCTION inventory.check_document_stock_package();

-- ---------------------------------------------------------------------------------------------- 4. who read the delta

-- Postgres does not track what a function body mentions: this one read quantity_delta.
CREATE OR REPLACE FUNCTION ordering.check_production_order_write_off() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    po         RECORD;
    adjustment RECORD;
    taken_by   TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'production order write-off %: the write-offs a replacement makes up are kept as written',
            OLD.stock_adjustment_id USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.code, p.kind, p.status, p.replaces_production_order_id INTO po
      FROM ordering.production_order p WHERE p.id = NEW.production_order_id;
    IF po.kind IS DISTINCT FROM 'REPLACEMENT' OR po.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'production order %: only a replacement names write-offs, while it is a draft (17 BR-31)',
            po.code USING ERRCODE = 'check_violation';
    END IF;
    SELECT a.reason_code::TEXT AS reason, a.status::TEXT AS status, a.direction::TEXT AS direction, a.sku,
           a.adjustment_number,
           EXISTS (SELECT 1 FROM inventory.stock_lot l
                    WHERE l.production_order_id = po.replaces_production_order_id AND l.sku = a.sku
                      AND l.lot_number = a.lot_number) AS of_replaced_lot
      INTO adjustment
      FROM inventory.stock_adjustment a WHERE a.id = NEW.stock_adjustment_id;
    IF adjustment.reason IS DISTINCT FROM 'DAMAGED' OR adjustment.status IS DISTINCT FROM 'POSTED'
        OR adjustment.direction IS DISTINCT FROM 'WRITE_OFF'
        OR adjustment.sku IS DISTINCT FROM NEW.sku OR NOT adjustment.of_replaced_lot THEN
        RAISE EXCEPTION 'production order %: % is not a posted DAMAGED write-off of a carton of %''s lot (17 BR-31)',
            po.code, coalesce(adjustment.adjustment_number, NEW.stock_adjustment_id::TEXT), NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.code INTO taken_by
      FROM ordering.production_order_write_off w
      JOIN ordering.production_order p ON p.id = w.production_order_id
     WHERE w.stock_adjustment_id = NEW.stock_adjustment_id AND w.production_order_id <> NEW.production_order_id
       AND p.status <> 'CANCELLED'
     LIMIT 1;
    IF taken_by IS NOT NULL THEN
        RAISE EXCEPTION 'production order %: write-off % is already made up by %', po.code,
            adjustment.adjustment_number, taken_by USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
