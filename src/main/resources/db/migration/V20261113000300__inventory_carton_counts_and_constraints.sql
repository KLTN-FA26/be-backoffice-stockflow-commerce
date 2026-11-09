-- =============================================================================
-- Counts and adjustments by carton; the whole-carton rules stated tighter (team plan 4C-3; kltn-docs 20 §4.2, §4.3,
-- §4.6, §5.5, BR-04, BR-09, BR-10, BR-12, BR-14, BR-18, BR-22).
--
-- A count states each LPN carton present or not found at its bin, never a number of units (20 §4.2 b4, BR-14); its
-- variance is one COUNT_VARIANCE adjustment of a whole carton. A person asks for an adjustment by LPN: write a carton
-- off whole, or restock a LOST carton whole (§4.3). The code says so first (CycleCount, StockAdjustment,
-- StockPackageContents); this says it again in the database:
--
--   1. inventory.stock_package:
--        * unopened is dropped. A carton in stock is never opened (V20261111000200), so the flag said nothing an
--          IN_STOCK status does not, and a LOST carton found again kept it FALSE and was never allocated (4C-2 review).
--          The values are archived. check_stock_package_transition and check_stock_package_contents lose their
--          unopened rules: an IN_STOCK carton holds exactly received_qty.
--        * received_qty is the standard count: ck_stock_package_standard_count holds every RECEIVING and IN_STOCK
--          carton to nominal_qty (NULL while not yet counted) - on INSERT too, which the transition trigger did not
--          see. A RECEIVING carton of a draft counted before V20261111000200 at another figure is set back to not
--          counted (archived): the draft counts it again (StockPackage.count assigns the standard). Closed cartons
--          (CONSUMED, LOST, VOID) keep the figure they were counted in at; one off its standard cannot come back
--          into stock (the CHECK refuses it on the way back, StockPackage.restock / found first).
--        * inventory.stock_package_balance is rewritten: one row per carton (UNIQUE(stock_package_id), below), with
--          its stock row's id and stock status, and no unopened. Nor an expiry date: the stock row has it while it
--          lasts (team plan 5-1 drops it), and the view does not tie itself to a column on its way out.
--   2. inventory.stock_item:
--        * lot_number and lot_id NOT NULL, lot_number not blank (20 BR-22: no stock without a lot) - for every row
--          of every SKU, which is what "enforce_sku_policy requires a lot" in the team plan asks; the function's own
--          lot clause (lot-tracked SKUs only) can no longer fire, and the function is left as it is: team plan 5-1
--          rewrites it without the expiry columns and 5-5 drops the policy, and a third rewrite here would undo
--          whichever of them ran first. Rows with no lot are emptied rows of the history (V20261111000110 boxed every
--          live unit with a lot): they are archived and relabelled lot 'UNTRACED' (open question C45), on the SKU's
--          UNTRACED lot as V20261111000110 made them (of their expiry, while lots have one). The code writes
--          'UNTRACED' wherever goods arrive with no lot.
--        * uk_stock_item_package_layer (one row per carton, SKU, lot, serial and receipt) is replaced by
--          uk_stock_item_package: one row per carton (20 BR-18). Cartons closed by V20261111000110 may have kept
--          several emptied rows; all but one are archived and detached (stock_package_id NULL). The ledger names
--          the cartons on its own lines, so no history is lost.
--   3. inventory.cycle_count_line: a line written from now on names a carton and records its units whole - book and
--      found are 0 or the carton's standard count (tg_cycle_count_line_carton). Lines of the history keep what they
--      say. A counted line of an open count whose figure is neither is set back to not counted (archived): it is
--      scanned again.
--   4. inventory.stock_adjustment: a carton's adjustment moves the whole carton (quantity_delta = +/- nominal_qty)
--      for one of the six reasons of BR-04, and a COUNT_VARIANCE names a carton (tg_stock_adjustment_whole_carton,
--      on INSERT: pending ones of the history are decided as they were asked, and approval refuses a partial one).
--      One adjustment of a carton waits for approval at a time (uk_stock_adjustment_pending_package).
--
-- Not here, on purpose (deviation from the team plan, recorded in reports/4C-3.md): stock_item.stock_package_id
-- stays NULLable. Loose rows are still written by the put-back of a pick line (team plan 4C-4), a customer return
-- (receiveReturn, 4D-2) and damage reports (5-6), and the emptied loose rows of the history are named by holds, pick
-- lines and damage reports. The NOT NULL lands with the last of those packages; the loose layer index
-- (uk_stock_item_sku_location_lot) stays with it.
--
-- Stops, naming what to finish first, instead of guessing:
--   * an open count (PLANNED / IN_PROGRESS) with a loose line: submit or cancel it first;
--   * a carton with two adjustments waiting for approval: decide one;
--   * a live stock row (on_hand > 0) with no lot;
--   * an IN_STOCK carton off its standard count, or a carton with two rows holding stock.
--
-- Order: 1. stops; 2. archive; 3. counts and cartons set back; 4. stock_package; 5. stock_item; 6. count lines;
-- 7. adjustments; 8. NOTICE.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Stops
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    found_text TEXT;
BEGIN
    SELECT string_agg(DISTINCT c.count_number, ', ') INTO found_text
      FROM inventory.cycle_count c JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
     WHERE c.status IN ('PLANNED', 'IN_PROGRESS') AND l.stock_package_id IS NULL;
    IF found_text IS NOT NULL THEN
        RAISE EXCEPTION 'V20261113000300: open cycle counts with loose lines: %; submit or cancel them first', found_text;
    END IF;

    SELECT string_agg(p.lpn, ', ') INTO found_text
      FROM inventory.stock_package p
     WHERE (SELECT count(*) FROM inventory.stock_adjustment a
             WHERE a.stock_package_id = p.id AND a.status = 'PENDING_APPROVAL') > 1;
    IF found_text IS NOT NULL THEN
        RAISE EXCEPTION 'V20261113000300: cartons with more than one adjustment awaiting approval: %; decide them first',
            found_text;
    END IF;

    SELECT string_agg(s.id::text, ', ') INTO found_text
      FROM inventory.stock_item s
     WHERE s.on_hand > 0 AND NULLIF(btrim(s.lot_number), '') IS NULL;
    IF found_text IS NOT NULL THEN
        RAISE EXCEPTION 'V20261113000300: stock rows holding units with no lot: %; relabel them first', found_text;
    END IF;

    SELECT string_agg(p.lpn, ', ') INTO found_text
      FROM inventory.stock_package p
     WHERE p.status = 'IN_STOCK' AND p.received_qty IS DISTINCT FROM p.nominal_qty;
    IF found_text IS NOT NULL THEN
        RAISE EXCEPTION 'V20261113000300: cartons in stock off their standard count: %', found_text;
    END IF;

    SELECT string_agg(p.lpn, ', ') INTO found_text
      FROM inventory.stock_package p
     WHERE (SELECT count(*) FROM inventory.stock_item s WHERE s.stock_package_id = p.id AND s.on_hand > 0) > 1;
    IF found_text IS NOT NULL THEN
        RAISE EXCEPTION 'V20261113000300: cartons with more than one stock row holding units: %', found_text;
    END IF;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 2. Archive what changes
-- ---------------------------------------------------------------------------------------------

-- Rows relabelled lot UNTRACED: emptied, with no lot (the stops refused a live one). With their expiry while stock
-- rows have one (team plan 5-1 drops it; this runs either side of that).
DO $$
BEGIN
    EXECUTE format($q$
        CREATE TEMPORARY TABLE c3_relabel ON COMMIT DROP AS
        SELECT s.id, s.sku, %s AS expiry_date, NULL::UUID AS lot_id
          FROM inventory.stock_item s
         WHERE NULLIF(btrim(s.lot_number), '') IS NULL$q$,
        CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                            AND table_name = 'stock_item' AND column_name = 'expiry_date')
             THEN 's.expiry_date' ELSE 'NULL::DATE' END);
END
$$;

-- Rows detached from their carton: every row of a carton but the one kept - the row holding its units, or else its
-- first row by id.
CREATE TEMPORARY TABLE c3_detach ON COMMIT DROP AS
SELECT s.id
  FROM inventory.stock_item s
 WHERE s.stock_package_id IS NOT NULL
   AND s.id <> (SELECT k.id FROM inventory.stock_item k
                 WHERE k.stock_package_id = s.stock_package_id
                 ORDER BY (k.on_hand > 0) DESC, k.id LIMIT 1);

-- Counted lines of open counts whose figure is not a whole carton of their carton: scanned again.
CREATE TEMPORARY TABLE c3_uncount ON COMMIT DROP AS
SELECT l.id
  FROM inventory.cycle_count_line l
  JOIN inventory.cycle_count c ON c.id = l.cycle_count_id
  JOIN inventory.stock_package p ON p.id = l.stock_package_id
 WHERE c.status IN ('PLANNED', 'IN_PROGRESS')
   AND (l.expected_qty NOT IN (0, p.nominal_qty) OR l.counted_qty NOT IN (0, p.nominal_qty));

-- Cartons of drafts counted at another figure than their standard: not counted any more.
CREATE TEMPORARY TABLE c3_recount ON COMMIT DROP AS
SELECT p.id
  FROM inventory.stock_package p
 WHERE p.status = 'RECEIVING' AND p.received_qty IS NOT NULL AND p.received_qty <> p.nominal_qty;

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.stock_package.unopened', p.id,
       jsonb_build_object('id', p.id, 'lpn', p.lpn, 'status', p.status, 'unopened', p.unopened)
  FROM inventory.stock_package p
UNION ALL SELECT 'inventory.stock_package', p.id, to_jsonb(p)
  FROM inventory.stock_package p WHERE p.id IN (SELECT id FROM c3_recount)
UNION ALL SELECT 'inventory.stock_item', s.id, to_jsonb(s)
  FROM inventory.stock_item s WHERE s.id IN (SELECT id FROM c3_relabel UNION SELECT id FROM c3_detach)
UNION ALL SELECT 'inventory.cycle_count_line', l.id, to_jsonb(l)
  FROM inventory.cycle_count_line l WHERE l.id IN (SELECT id FROM c3_uncount);


-- ---------------------------------------------------------------------------------------------
-- 3. Count lines and draft cartons set back to not counted
-- ---------------------------------------------------------------------------------------------
UPDATE inventory.cycle_count_line l
   SET counted_qty = NULL, counted_by = NULL, counted_at = NULL,
       expected_qty = CASE WHEN p.status = 'IN_STOCK' THEN p.nominal_qty ELSE 0 END,
       version = l.version + 1, last_modified_at = NOW(), last_modified_by = 'V20261113000300'
  FROM inventory.stock_package p
 WHERE p.id = l.stock_package_id AND l.id IN (SELECT id FROM c3_uncount);

UPDATE inventory.stock_package p
   SET received_qty = NULL, version = p.version + 1, last_modified_at = NOW(), last_modified_by = 'V20261113000300'
 WHERE p.id IN (SELECT id FROM c3_recount);


-- ---------------------------------------------------------------------------------------------
-- 4. stock_package: no unopened; the standard count; the balance view
-- ---------------------------------------------------------------------------------------------
DROP VIEW inventory.stock_package_balance;
DROP TRIGGER tg_stock_package_transition ON inventory.stock_package;
DROP TRIGGER tg_stock_package_rows ON inventory.stock_package;
ALTER TABLE inventory.stock_package DROP CONSTRAINT ck_stock_package_unopened;
ALTER TABLE inventory.stock_package DROP COLUMN unopened;

-- V20261111000200's lifecycle, without the opened / sealed rules: nothing is opened, so nothing is sealed again.
CREATE OR REPLACE FUNCTION inventory.check_stock_package_transition()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'RECEIVING' AND NEW.status IN ('IN_STOCK', 'VOID'))
        OR (OLD.status = 'IN_STOCK' AND NEW.status IN ('CONSUMED', 'LOST'))
        OR (OLD.status = 'CONSUMED' AND NEW.status = 'IN_STOCK')
        OR (OLD.status = 'LOST' AND NEW.status = 'IN_STOCK')) THEN
        RAISE EXCEPTION 'stock package % cannot go from % to %', NEW.lpn, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.received_qty IS DISTINCT FROM OLD.received_qty AND OLD.status <> 'RECEIVING' THEN
        RAISE EXCEPTION 'stock package % was counted in at %; a later difference is an adjustment', NEW.lpn,
            OLD.received_qty USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION inventory.check_stock_package_transition() IS
    'Carton lifecycle (kltn-docs 20 §5.5): RECEIVING->IN_STOCK|VOID, IN_STOCK->CONSUMED|LOST, CONSUMED|LOST->IN_STOCK; '
    'counted only while RECEIVING. The standard count is ck_stock_package_standard_count.';

CREATE TRIGGER tg_stock_package_transition BEFORE UPDATE OF status, received_qty ON inventory.stock_package
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_transition();

ALTER TABLE inventory.stock_package ADD CONSTRAINT ck_stock_package_standard_count CHECK (
    status NOT IN ('RECEIVING', 'IN_STOCK') OR received_qty IS NULL OR received_qty = nominal_qty);
COMMENT ON CONSTRAINT ck_stock_package_standard_count ON inventory.stock_package IS
    'kltn-docs 03 BR-13, 20 BR-18: a carton is counted, and is in stock, only at its standard count.';

-- V20261022000100's rules, the unopened one gone: an IN_STOCK carton holds exactly what was counted into it - every
-- carton in stock is whole (20 BR-14).
CREATE OR REPLACE FUNCTION inventory.check_stock_package_contents(package_id UUID)
    RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    pkg       inventory.stock_package%ROWTYPE;
    item_sku  VARCHAR(64);
    wrong_sku INTEGER;
    elsewhere INTEGER;
    lots      INTEGER;
    held      BIGINT;
BEGIN
    SELECT * INTO pkg FROM inventory.stock_package WHERE id = package_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT sku INTO item_sku FROM inventory.inventory_items WHERE id = pkg.inventory_item_id;

    SELECT count(*) FILTER (WHERE s.sku <> item_sku),
           count(*) FILTER (WHERE s.on_hand > 0 AND s.location_code <> pkg.location_code),
           count(DISTINCT s.lot_number) FILTER (WHERE s.on_hand > 0),
           COALESCE(sum(s.on_hand), 0)
      INTO wrong_sku, elsewhere, lots, held
      FROM inventory.stock_item s
     WHERE s.stock_package_id = package_id;

    IF wrong_sku > 0 THEN
        RAISE EXCEPTION 'stock package % holds stock of another SKU than %', pkg.lpn, item_sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF elsewhere > 0 THEN
        RAISE EXCEPTION 'stock package % is at % but some of its stock is not', pkg.lpn, pkg.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    IF lots > 1 THEN
        RAISE EXCEPTION 'stock package % holds more than one lot', pkg.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF pkg.status = 'IN_STOCK' AND held <> pkg.received_qty THEN
        RAISE EXCEPTION 'stock package % was counted in at % but holds %; a carton in stock is whole', pkg.lpn,
            pkg.received_qty, held USING ERRCODE = 'check_violation';
    END IF;
    IF pkg.status <> 'IN_STOCK' AND held > 0 THEN
        RAISE EXCEPTION 'stock package % is % but holds %', pkg.lpn, pkg.status, held
            USING ERRCODE = 'check_violation';
    END IF;
END
$$;

COMMENT ON FUNCTION inventory.check_stock_package_contents(UUID) IS
    'A stock package and its stock row agree: its SKU, its place, one lot, and exactly its count while IN_STOCK, '
    'nothing otherwise. Run at commit by the constraint triggers.';

CREATE CONSTRAINT TRIGGER tg_stock_package_rows
    AFTER INSERT OR UPDATE OF location_code, status, received_qty ON inventory.stock_package
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_rows();

-- One row per carton (section 5), so no grouping: the carton and its row, side by side. A carton never put in stock
-- (RECEIVING, VOID) has no row yet.
CREATE VIEW inventory.stock_package_balance AS
SELECT p.id                                                     AS stock_package_id,
       p.lpn,
       i.sku,
       p.inventory_item_id,
       p.uom_conversion_id,
       u.unit_of_measure,
       p.nominal_qty,
       p.received_qty,
       p.location_code,
       p.status,
       s.id                                                     AS stock_item_id,
       s.status                                                 AS stock_status,
       s.lot_number,
       COALESCE(s.on_hand, 0)                                   AS on_hand,
       COALESCE(s.reserved, 0)                                  AS reserved,
       CASE WHEN s.status = 'AVAILABLE' THEN s.on_hand - s.reserved ELSE 0 END AS available,
       p.goods_receipt_line_id,
       p.closed_at
  FROM inventory.stock_package p
  JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
  JOIN inventory.uom_conversions u ON u.id = p.uom_conversion_id
  LEFT JOIN inventory.stock_item s ON s.stock_package_id = p.id;

COMMENT ON VIEW inventory.stock_package_balance IS
    'One row per LPN carton with its stock row: lot, stock status, on hand, reserved, available (team plan 4C-3).';


-- ---------------------------------------------------------------------------------------------
-- 5. stock_item: a lot on every row; one row per carton
-- ---------------------------------------------------------------------------------------------

-- The SKU's lot UNTRACED (of the expiry, while lots have one), as V20261111000110 finds or makes it: the lot trigger
-- wants a row's lot to match its lot number and expiry.
CREATE TEMPORARY TABLE c3_untraced (sku VARCHAR(64), expiry_date DATE, lot_id UUID) ON COMMIT DROP;
DO $$
DECLARE
    lot_expiry BOOLEAN := EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                                    AND table_name = 'stock_lot' AND column_name = 'expiry_date');
BEGIN
    EXECUTE format($q$
        UPDATE c3_relabel r
           SET lot_id = (SELECT l.id FROM inventory.stock_lot l
                          WHERE l.sku = r.sku AND l.lot_number = 'UNTRACED' AND l.origin_type = 'UNTRACED' %s
                          ORDER BY l.created_at, l.id LIMIT 1)$q$,
        CASE WHEN lot_expiry THEN 'AND l.expiry_date IS NOT DISTINCT FROM r.expiry_date' ELSE '' END);
    INSERT INTO c3_untraced
    SELECT DISTINCT sku, CASE WHEN lot_expiry THEN expiry_date END, NULL::UUID FROM c3_relabel WHERE lot_id IS NULL;
    UPDATE c3_untraced SET lot_id = gen_random_uuid();
    IF lot_expiry THEN
        EXECUTE $q$
            INSERT INTO inventory.stock_lot (id, sku, lot_number, expiry_date, origin_type, version, created_at,
                                             created_by)
            SELECT lot_id, sku, 'UNTRACED', expiry_date, 'UNTRACED', 0, NOW(), 'V20261113000300' FROM c3_untraced$q$;
    ELSE
        INSERT INTO inventory.stock_lot (id, sku, lot_number, origin_type, version, created_at, created_by)
        SELECT lot_id, sku, 'UNTRACED', 'UNTRACED', 0, NOW(), 'V20261113000300' FROM c3_untraced;
    END IF;
END
$$;
UPDATE c3_relabel r SET lot_id = t.lot_id
  FROM c3_untraced t
 WHERE r.lot_id IS NULL AND t.sku = r.sku AND t.expiry_date IS NOT DISTINCT FROM r.expiry_date;

-- The policy trigger refuses a changed lot ("receipt identity is immutable"); these rows hold nothing, so no unit's
-- identity changes. The lot trigger checks the new lot matches.
ALTER TABLE inventory.stock_item DISABLE TRIGGER trg_stock_sku_policy;
UPDATE inventory.stock_item s
   SET lot_number = 'UNTRACED', lot_id = r.lot_id, version = s.version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261113000300'
  FROM c3_relabel r
 WHERE r.id = s.id;
ALTER TABLE inventory.stock_item ENABLE TRIGGER trg_stock_sku_policy;

-- A row the lot trigger never resolved (none is expected): resolved now, as on insert.
UPDATE inventory.stock_item SET lot_id = NULL WHERE lot_id IS NULL;

UPDATE inventory.stock_item s
   SET stock_package_id = NULL, version = s.version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261113000300'
 WHERE s.id IN (SELECT id FROM c3_detach);

ALTER TABLE inventory.stock_item ALTER COLUMN lot_number SET NOT NULL;
ALTER TABLE inventory.stock_item ALTER COLUMN lot_id SET NOT NULL;
ALTER TABLE inventory.stock_item ADD CONSTRAINT ck_stock_item_lot CHECK (btrim(lot_number) <> '');
COMMENT ON CONSTRAINT ck_stock_item_lot ON inventory.stock_item IS
    'kltn-docs 20 BR-22: every unit carries a lot; a unit with no known lot is of lot UNTRACED (C45).';

DROP INDEX inventory.uk_stock_item_package_layer;
CREATE UNIQUE INDEX uk_stock_item_package ON inventory.stock_item (stock_package_id)
    WHERE stock_package_id IS NOT NULL;
COMMENT ON INDEX inventory.uk_stock_item_package IS
    'kltn-docs 20 BR-18: a carton is one stock row; put back or found again, it fills the same row.';

COMMENT ON COLUMN inventory.stock_item.stock_package_id IS
    'The carton this row is the contents of (one row per carton). NULL only for loose rows still written by a '
    'put-back, a customer return or a damage report, and for emptied rows of the history.';

-- ---------------------------------------------------------------------------------------------
-- 6. Count lines: a carton, whole
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION inventory.check_cycle_count_line_carton()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    standard INTEGER;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.stock_package_id IS NOT DISTINCT FROM OLD.stock_package_id
       AND NEW.expected_qty IS NOT DISTINCT FROM OLD.expected_qty
       AND NEW.counted_qty IS NOT DISTINCT FROM OLD.counted_qty THEN
        RETURN NEW;
    END IF;
    IF NEW.stock_package_id IS NULL THEN
        RAISE EXCEPTION 'count line % names no carton; a count states cartons present or not found', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT nominal_qty INTO standard FROM inventory.stock_package WHERE id = NEW.stock_package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    IF NEW.expected_qty NOT IN (0, standard) OR NEW.counted_qty NOT IN (0, standard) THEN
        RAISE EXCEPTION 'count line % records % expected and % found of a carton of %; a carton is counted whole',
            NEW.id, NEW.expected_qty, NEW.counted_qty, standard USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_cycle_count_line_carton BEFORE INSERT OR UPDATE OF stock_package_id, expected_qty, counted_qty
    ON inventory.cycle_count_line
    FOR EACH ROW EXECUTE FUNCTION inventory.check_cycle_count_line_carton();

COMMENT ON FUNCTION inventory.check_cycle_count_line_carton() IS
    'kltn-docs 20 §4.2, BR-14: a count line written since V20261113000300 names a carton and records it whole '
    '(0 or its standard count, booked and found). Lines of the history keep what they say.';


-- ---------------------------------------------------------------------------------------------
-- 7. Adjustments: a carton, whole, for a reason of BR-04
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION inventory.check_stock_adjustment_whole_carton()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    standard INTEGER;
BEGIN
    IF NEW.reason_code = 'COUNT_VARIANCE' AND NEW.stock_package_id IS NULL THEN
        RAISE EXCEPTION 'adjustment % is a count variance of no carton; a count states cartons', NEW.adjustment_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.stock_package_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.reason_code NOT IN ('DAMAGED', 'LOST', 'FOUND', 'DATA_CORRECTION', 'OTHER', 'COUNT_VARIANCE') THEN
        RAISE EXCEPTION 'adjustment % of a carton gives reason %; kltn-docs 20 BR-04 lists six', NEW.adjustment_number,
            NEW.reason_code USING ERRCODE = 'check_violation';
    END IF;
    SELECT nominal_qty INTO standard FROM inventory.stock_package WHERE id = NEW.stock_package_id;
    IF FOUND AND abs(NEW.quantity_delta) <> standard THEN
        RAISE EXCEPTION 'adjustment % moves % of a carton of %; a carton is adjusted whole', NEW.adjustment_number,
            NEW.quantity_delta, standard USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_adjustment_whole_carton BEFORE INSERT ON inventory.stock_adjustment
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_adjustment_whole_carton();

COMMENT ON FUNCTION inventory.check_stock_adjustment_whole_carton() IS
    'kltn-docs 20 §4.3, BR-04, BR-14: an adjustment asked since V20261113000300 writes a carton off whole or restocks '
    'it whole, for one of the six reasons; a count variance names its carton. On INSERT only: pending adjustments of '
    'the history are decided as they were asked.';

CREATE UNIQUE INDEX uk_stock_adjustment_pending_package ON inventory.stock_adjustment (stock_package_id)
    WHERE stock_package_id IS NOT NULL AND status = 'PENDING_APPROVAL';
COMMENT ON INDEX inventory.uk_stock_adjustment_pending_package IS
    'One adjustment of a carton waits for approval at a time: a second would decide the same carton twice.';


-- ---------------------------------------------------------------------------------------------
-- 8. What changed
-- ---------------------------------------------------------------------------------------------
DO $$
BEGIN
    RAISE NOTICE 'V20261113000300: % stock rows relabelled lot UNTRACED (% lots made), % rows detached from their '
        'carton, % open count lines and % draft cartons set back to not counted',
        (SELECT count(*) FROM c3_relabel), (SELECT count(*) FROM c3_untraced), (SELECT count(*) FROM c3_detach),
        (SELECT count(*) FROM c3_uncount), (SELECT count(*) FROM c3_recount);
END
$$;
