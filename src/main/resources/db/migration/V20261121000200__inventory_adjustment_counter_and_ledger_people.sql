-- =============================================================================
-- WF-05 fix plan, section 8: the counter of a cycle count's variance is a fact of the adjustment, and the stock
-- ledger names everyone behind a posted adjustment (kltn-docs 20 §4.2, §4.3, BR-05; SCRUM-145, SCRUM-146).
--
-- Why.
--   * "The person who counted the line does not decide its variance" was a rule of the application only: the counter
--     was read from the count line when the adjustment was decided, and the table knew only that the requester does
--     not decide (ck_stock_adjustment_four_eyes). A decision written past the application would break it unseen.
--   * A posted adjustment's ledger line named only who approved it (actor_id). Who asked, and who counted, were one
--     join and two tables away - and the ledger is the record that is kept.
--
-- What this adds:
--   1. inventory.stock_adjustment.counted_by: who counted the line a COUNT_VARIANCE comes from. Backfilled from the
--      count line. ck_stock_adjustment_counted_by_count: only a count's variance has a counter.
--      ck_stock_adjustment_counter_not_decider: decided_by <> counted_by. A decided adjustment whose decider did count
--      the line (written before the application refused it) keeps no counter rather than block the migration: the
--      count line still says who counted;
--   2. inventory.stock_movement.requested_by and counted_by, on ADJUSTMENT lines only
--      (ck_stock_movement_adjustment_people), backfilled from the adjustment each line posts. The ledger is
--      append-only, so its trigger stands aside for the backfill and for nothing else.
--
-- Nothing is archived (QD-1).
-- =============================================================================

-- 1. The counter on the adjustment --------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_adjustment ADD COLUMN counted_by UUID;

-- The adjustment's own triggers judge a request against the carton as it stands now; this only copies a name onto rows
-- decided long ago, so they stand aside for it.
ALTER TABLE inventory.stock_adjustment DISABLE TRIGGER USER;

UPDATE inventory.stock_adjustment a
   SET counted_by = l.counted_by
  FROM inventory.cycle_count_line l
 WHERE l.id = a.cycle_count_line_id
   AND l.counted_by IS NOT NULL
   AND a.decided_by IS DISTINCT FROM l.counted_by;

ALTER TABLE inventory.stock_adjustment ENABLE TRIGGER USER;

ALTER TABLE inventory.stock_adjustment
    ADD CONSTRAINT fk_stock_adjustment_counted_by FOREIGN KEY (counted_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_stock_adjustment_counted_by_count CHECK (counted_by IS NULL OR cycle_count_line_id IS NOT NULL),
    -- Four eyes, the counter's side (20 BR-05): whoever counted the line neither approves nor rejects its variance.
    ADD CONSTRAINT ck_stock_adjustment_counter_not_decider CHECK (
        decided_by IS NULL OR counted_by IS NULL OR decided_by <> counted_by);

COMMENT ON COLUMN inventory.stock_adjustment.counted_by IS
    'Who counted the cycle count line this COUNT_VARIANCE comes from; null for an adjustment asked by hand. Neither '
    'they nor requested_by may decide it (ck_stock_adjustment_counter_not_decider, ck_stock_adjustment_four_eyes).';

-- 2. The people on the ledger line ----------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_movement ADD COLUMN requested_by UUID;
ALTER TABLE inventory.stock_movement ADD COLUMN counted_by UUID;

ALTER TABLE inventory.stock_movement DISABLE TRIGGER tg_stock_movement_append_only;

UPDATE inventory.stock_movement m
   SET requested_by = a.requested_by,
       counted_by = a.counted_by
  FROM inventory.stock_adjustment a
 WHERE m.movement_type = 'ADJUSTMENT'
   AND m.reference_type = 'STOCK_ADJUSTMENT'
   AND m.reference_id = a.id;

ALTER TABLE inventory.stock_movement ENABLE TRIGGER tg_stock_movement_append_only;

ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT fk_stock_movement_requested_by FOREIGN KEY (requested_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_stock_movement_counted_by FOREIGN KEY (counted_by) REFERENCES identity.app_user (id),
    -- Only the posting of an adjustment names who asked and who counted; a counter comes with a requester.
    ADD CONSTRAINT ck_stock_movement_adjustment_people CHECK (
        (requested_by IS NULL AND counted_by IS NULL)
        OR (movement_type = 'ADJUSTMENT' AND requested_by IS NOT NULL));

COMMENT ON COLUMN inventory.stock_movement.requested_by IS
    'On an ADJUSTMENT line: who asked for the adjustment it posts. actor_id is who approved it, so the line names both '
    'pairs of eyes (20 BR-05). Null on every other line.';
COMMENT ON COLUMN inventory.stock_movement.counted_by IS
    'On an ADJUSTMENT line posting a cycle count''s variance: who counted the line. Null otherwise.';

-- A line posting a stock adjustment names its requester, and its counter when the adjustment has one - the same people
-- the adjustment names. Checked on insert: the ledger is append-only, so an inserted line is the line for good.
CREATE FUNCTION inventory.check_stock_movement_adjustment_people() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    adjustment RECORD;
BEGIN
    IF NEW.movement_type <> 'ADJUSTMENT' OR NEW.reference_type <> 'STOCK_ADJUSTMENT' THEN
        RETURN NEW;
    END IF;
    SELECT a.requested_by, a.counted_by, a.adjustment_number INTO adjustment
      FROM inventory.stock_adjustment a WHERE a.id = NEW.reference_id;
    IF NOT FOUND THEN
        RETURN NEW;   -- the reference is checked elsewhere; nothing to compare with here
    END IF;
    IF NEW.requested_by IS DISTINCT FROM adjustment.requested_by
        OR NEW.counted_by IS DISTINCT FROM adjustment.counted_by THEN
        RAISE EXCEPTION 'ledger line of adjustment %: it names who requested and who counted the adjustment (20 BR-05)',
            adjustment.adjustment_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_stock_movement_adjustment_people BEFORE INSERT ON inventory.stock_movement
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_movement_adjustment_people();
