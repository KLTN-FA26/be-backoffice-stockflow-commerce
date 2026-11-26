-- A reservation counts cartons, not units (owner decision 2026-11-26/27, ADR-0011, step 3c).
--
-- inventory.stock_reservation holds two kinds of row, and both counted units in "quantity":
--
--   a SKU-level hold   (stock_item_id IS NULL)   an order holds stock of a SKU at a warehouse, on no carton yet
--   an allocation      (stock_item_id set)       one carton given to the order at release
--
-- Stock is whole sealed cartons, so the units of a hold were always a number of cartons times the count of those
-- cartons, and the units of an allocation were always its carton's nominal_qty (check_allocation_whole_carton).
-- Written as what they are:
--
--   inventory.stock_reservation   cartons           how many cartons the row is; an allocation is always 1
--                                 units_per_carton  the count of the cartons the row is about
--                                 quantity          dropped: cartons * units_per_carton, for whoever needs units
--                                 expires_at        dropped: no hold has carried a deadline since V20261117000300
--
-- Which cartons a hold wants is said by their count, not by a packing-unit id (ADR-0011, "Decisions for the rest of
-- step 3"): retired packing units of one item may share a factor, so an id would be ambiguous where the count is not.
-- A hold of cartons of 1000 is covered, and later served, only by cartons whose stock_package.nominal_qty is 1000.
-- stock_item (one row per carton, on_hand / reserved in units) is not touched: it is folded into stock_package in
-- step 3d, and stock_item_id is repointed to the carton there.
--
-- Backfill.
--   A row on a stock row is one carton of that carton's nominal_qty (410 allocations of the demo seed looked at:
--   every one's quantity is its carton's nominal_qty, none sits on a row without a carton).
--   A SKU-level hold takes its count from, in this order: the order line it is held for (order_line_reservation,
--   delivery_batch_hold, delivery_failure_case_hold -> order_line.units_per_carton), the cartons its allocations took
--   (an ALLOCATED hold is no longer on its line: the line keeps the allocations' ids), the SKU's active packing unit
--   (uom_conversions, one active carton per item). cartons = quantity / that count (20 SKU-level holds of the demo
--   seed looked at: every one a whole number of cartons of 1000).
--
-- Nothing live is guessed. The migration refuses to run, naming up to 10 rows, over
--   a HELD row on a stock row that has no carton, or whose quantity is not its carton's nominal_qty;
--   a HELD SKU-level hold whose count cannot be resolved, or whose quantity is not a whole number of such cartons.
-- History (a closed row: ALLOCATED, CONSUMED, RELEASED, EXPIRED) keeps nothing anybody acts on, so it is converted
-- by rule instead, and keeps its units exactly (cartons * units_per_carton = the old quantity), so nothing read back
-- from it says more was held than ever was: a closed row whose quantity is a whole number of its resolved count
-- (on a carton: exactly that carton) is those cartons; any other - part of a carton, a row without a carton, a hold
-- with no count to resolve or not a whole number of cartons - is one "carton" of its own quantity. No database built
-- from the baseline has any of these.
--
-- Deadlines. V20261117000300 closed every live hold that carried one and nothing has set expires_at since; a hand-kept
-- database that still has one is closed here the same way (EXPIRED when past, else RELEASED, the units given back to
-- the stock row) before the column goes. EXPIRED stays a status: rows of before keep it as history.
--
-- Readers of the two columns: looked for in pg_proc.prosrc, pg_views, pg_matviews, pg_constraint, pg_trigger, pg_index
-- and pg_depend of a database with every migration applied (Postgres does not track what a function body mentions).
--   quantity    ck_stock_reservation_quantity; the WHEN of tg_stock_reservation_soft_atp_update and the column list of
--               tg_stock_reservation_whole_carton; inventory.check_sku_reservation_covered (SUM(quantity)) and
--               inventory.check_allocation_whole_carton (NEW.quantity) - both rewritten below.
--   expires_at  ck_stock_reservation_allocation, ck_stock_reservation_expired_had_deadline, ix_stock_reservation_expiry.
-- No view reads either, and no function of ordering, fulfillment, procurement or warehouse does:
-- fulfillment.check_pick_line_carton, ordering.check_delivery_batch_hold_reservation and inventory.item_in_use name the
-- table for its ids, order and stock row only, and are left as they are.

-- ---------------------------------------------------------------------------------------------- 1. what each row becomes

-- Worked out once, before anything is written, so that every row of the table is rewritten exactly once below: a row
-- this transaction already wrote queues its deferred foreign keys again (fk_stock_reservation_order is INITIALLY
-- DEFERRED), and ALTER TABLE refuses to run over queued checks.
--   lapsing  a live hold that still carries a deadline: closed in step 3, so it is history for what follows
--   live     a HELD row that stays HELD
CREATE TEMP TABLE reservation_in_cartons ON COMMIT DROP AS
SELECT r.id, r.sku, r.warehouse_code, r.stock_item_id, r.quantity, r.reserved_at,
       (r.status = 'HELD' AND r.expires_at IS NULL) AS live,
       (r.status = 'HELD' AND r.expires_at IS NOT NULL) AS lapsing,
       p.lpn AS carton_lpn,
       p.nominal_qty AS carton_count,
       CASE WHEN r.stock_item_id IS NOT NULL THEN p.nominal_qty
            -- the order line it is held for, else the cartons its allocations took, else the active packing unit
            ELSE coalesce(line.units_per_carton, taken.units_per_carton, unit.factor) END AS units_per_carton
  FROM inventory.stock_reservation r
  LEFT JOIN inventory.stock_item s ON s.id = r.stock_item_id
  LEFT JOIN inventory.stock_package p ON p.id = s.stock_package_id
  LEFT JOIN (SELECT x.reservation_id, min(ol.units_per_carton) AS units_per_carton
               FROM (SELECT reservation_id, order_line_id FROM ordering.order_line_reservation
                     UNION SELECT reservation_id, order_line_id FROM ordering.delivery_batch_hold
                     UNION SELECT reservation_id, order_line_id FROM ordering.delivery_failure_case_hold) x
               JOIN ordering.order_line ol ON ol.id = x.order_line_id
              WHERE ol.units_per_carton > 0
              GROUP BY x.reservation_id
             HAVING count(DISTINCT ol.units_per_carton) = 1) line ON line.reservation_id = r.id
  LEFT JOIN (SELECT a.allocated_from_id, min(ap.nominal_qty) AS units_per_carton
               FROM inventory.stock_reservation a
               JOIN inventory.stock_item ai ON ai.id = a.stock_item_id
               JOIN inventory.stock_package ap ON ap.id = ai.stock_package_id
              WHERE a.allocated_from_id IS NOT NULL
              GROUP BY a.allocated_from_id
             HAVING count(DISTINCT ap.nominal_qty) = 1) taken ON taken.allocated_from_id = r.id
  -- uk_uom_conversions_one_carton: at most one active packing unit per item
  LEFT JOIN (SELECT i.sku, u.factor
               FROM inventory.inventory_items i
               JOIN inventory.uom_conversions u ON u.inventory_item_id = i.id AND u.active) unit ON unit.sku = r.sku;

-- ---------------------------------------------------------------------------------------------- 2. nothing live is guessed

DO $$
DECLARE
    odd TEXT;
BEGIN
    SELECT string_agg(line, '; ') INTO odd
      FROM (SELECT c.id || ' of ' || c.quantity || ' on '
                   || coalesce('carton ' || c.carton_lpn || ' of ' || c.carton_count, 'stock row ' || c.stock_item_id
                               || ', which is in no carton') AS line
              FROM reservation_in_cartons c
             WHERE c.live AND c.stock_item_id IS NOT NULL
               AND (c.carton_count IS NULL OR c.carton_count <> c.quantity)
             ORDER BY c.reserved_at, c.id
             LIMIT 10) x;
    IF odd IS NOT NULL THEN
        RAISE EXCEPTION 'stock_reservation: live hold(s) on a stock row are not one whole carton: %; a hold on a '
            'carton is that carton (ADR-0011) - release or settle these rows before migrating', odd
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT string_agg(line, '; ') INTO odd
      FROM (SELECT c.id || ' of ' || c.quantity || ' ' || c.sku || coalesce(' at ' || c.warehouse_code, '')
                   || coalesce(' in cartons of ' || c.units_per_carton, ', whose carton count is not known') AS line
              FROM reservation_in_cartons c
             WHERE c.live AND c.stock_item_id IS NULL
               AND (c.units_per_carton IS NULL OR c.quantity % c.units_per_carton <> 0)
             ORDER BY c.reserved_at, c.id
             LIMIT 10) x;
    IF odd IS NOT NULL THEN
        RAISE EXCEPTION 'stock_reservation: live hold(s) are not a whole number of cartons: %; a hold counts cartons '
            '(ADR-0011) - release these holds, or give their SKU its packing unit, before migrating', odd
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------- 3. the columns

ALTER TABLE inventory.stock_reservation
    ADD COLUMN cartons INTEGER,
    ADD COLUMN units_per_carton INTEGER;

-- A hold on a stock row that lapses below gives its units back to the row, as V20261117000300 did.
UPDATE inventory.stock_item s
   SET reserved = GREATEST(s.reserved - x.quantity, 0), version = s.version + 1, last_modified_at = now()
  FROM (SELECT stock_item_id, sum(quantity) AS quantity FROM reservation_in_cartons
         WHERE lapsing AND stock_item_id IS NOT NULL GROUP BY stock_item_id) x
 WHERE s.id = x.stock_item_id;

-- The BEFORE triggers of the table check what a row names (its SKU, its warehouse, its order); the rewrite changes
-- none of that, and the two AFTER triggers that read quantity are replaced below.
ALTER TABLE inventory.stock_reservation DISABLE TRIGGER USER;

-- Every row, once. On a stock row: one carton of its carton's count. A SKU-level hold: the count resolved above, and
-- as many cartons as its quantity is of it (the checks above hold every live row to that). History that fits neither
-- (the header says which) is one carton of its own quantity, so its units stay what they were. A live hold with a
-- deadline is closed: EXPIRED when past, else RELEASED.
UPDATE inventory.stock_reservation r
   SET units_per_carton = k.units_per_carton,
       cartons = r.quantity / k.units_per_carton,
       status = CASE WHEN NOT c.lapsing THEN r.status WHEN r.expires_at <= now() THEN 'EXPIRED' ELSE 'RELEASED' END,
       release_reason = CASE WHEN NOT c.lapsing THEN r.release_reason
                             WHEN r.expires_at <= now() THEN 'RESERVATION_EXPIRED' ELSE 'MANUAL_OVERRIDE' END,
       closed_at = CASE WHEN c.lapsing THEN now() ELSE r.closed_at END
  FROM reservation_in_cartons c
  JOIN LATERAL (SELECT CASE WHEN c.units_per_carton IS NOT NULL AND c.quantity % c.units_per_carton = 0
                             AND (c.stock_item_id IS NULL OR c.quantity = c.units_per_carton)
                            THEN c.units_per_carton ELSE c.quantity END AS units_per_carton) k ON TRUE
 WHERE c.id = r.id;

ALTER TABLE inventory.stock_reservation ENABLE TRIGGER USER;

-- ---------------------------------------------------------------------------------------------- 4. the old shape goes

DROP TRIGGER tg_stock_reservation_soft_atp_update ON inventory.stock_reservation;
DROP TRIGGER tg_stock_reservation_whole_carton ON inventory.stock_reservation;
DROP INDEX inventory.ix_stock_reservation_expiry;

-- ck_stock_reservation_allocation said "an allocation has an order and no deadline": order_id is NOT NULL on every
-- row and the deadline is gone, so nothing of it is left to say.
ALTER TABLE inventory.stock_reservation
    DROP CONSTRAINT ck_stock_reservation_quantity,
    DROP CONSTRAINT ck_stock_reservation_allocation,
    DROP CONSTRAINT ck_stock_reservation_expired_had_deadline,
    DROP COLUMN quantity,
    DROP COLUMN expires_at,
    ALTER COLUMN cartons SET NOT NULL,
    ALTER COLUMN units_per_carton SET NOT NULL,
    ADD CONSTRAINT ck_stock_reservation_cartons CHECK (cartons > 0),
    ADD CONSTRAINT ck_stock_reservation_units_per_carton CHECK (units_per_carton > 0),
    -- A row on a stock row is that row's carton: an allocation is one carton, never two.
    ADD CONSTRAINT ck_stock_reservation_one_carton CHECK (stock_item_id IS NULL OR cartons = 1);

COMMENT ON COLUMN inventory.stock_reservation.cartons IS
    'How many cartons the row is: any number for a SKU-level hold, always 1 for a row on a stock row (an allocation '
    'is one carton, ck_stock_reservation_one_carton). Its units are cartons * units_per_carton (ADR-0011).';
COMMENT ON COLUMN inventory.stock_reservation.units_per_carton IS
    'The count of the cartons the row is about. A SKU-level hold wants cartons of this count (from '
    'ordering.order_line.units_per_carton) and is covered and served only by cartons whose '
    'inventory.stock_package.nominal_qty equals it; an allocation carries its carton''s nominal_qty '
    '(tg_stock_reservation_whole_carton).';

-- ---------------------------------------------------------------------------------------------- 5. the rules, in cartons

-- SKU-level holds are covered by free cartons OF THEIR COUNT, over the whole system and at the hold's warehouse (20
-- BR-17). It was "SUM(quantity) of HELD SKU-level holds <= SUM(on_hand - reserved) of AVAILABLE stock", in units, which
-- let cartons of 500 cover a hold that only cartons of 1000 can serve. A free carton: an AVAILABLE stock row that holds
-- its carton (on_hand > 0) with nothing of it held (reserved = 0). Everything else is as it was: the SKU's advisory
-- lock (the one every hold and allocation of the SKU takes), every AVAILABLE row wherever it stands, the warehouse read
-- from the first part of the location code.
CREATE OR REPLACE FUNCTION inventory.check_sku_reservation_covered() RETURNS trigger AS $$
DECLARE
    free BIGINT;
    held BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.sku, 701));
    SELECT count(*) INTO free
      FROM inventory.stock_item s
      JOIN inventory.stock_package p ON p.id = s.stock_package_id
     WHERE s.sku = NEW.sku AND s.status = 'AVAILABLE' AND s.on_hand > 0 AND s.reserved = 0
       AND p.nominal_qty = NEW.units_per_carton;
    SELECT COALESCE(SUM(cartons), 0) INTO held
      FROM inventory.stock_reservation
     WHERE sku = NEW.sku AND stock_item_id IS NULL AND status = 'HELD' AND units_per_carton = NEW.units_per_carton;
    IF held > free THEN
        RAISE EXCEPTION 'SKU reservations of % (% carton(s) of %) exceed its free available cartons of that count (%)',
            NEW.sku, held, NEW.units_per_carton, free USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.warehouse_code IS NOT NULL THEN
        SELECT count(*) INTO free
          FROM inventory.stock_item s
          JOIN inventory.stock_package p ON p.id = s.stock_package_id
         WHERE s.sku = NEW.sku AND s.status = 'AVAILABLE' AND s.on_hand > 0 AND s.reserved = 0
           AND p.nominal_qty = NEW.units_per_carton
           AND split_part(s.location_code, '-', 1) = NEW.warehouse_code;
        SELECT COALESCE(SUM(cartons), 0) INTO held
          FROM inventory.stock_reservation
         WHERE sku = NEW.sku AND stock_item_id IS NULL AND status = 'HELD' AND units_per_carton = NEW.units_per_carton
           AND warehouse_code = NEW.warehouse_code;
        IF held > free THEN
            RAISE EXCEPTION 'SKU reservations of % at % (% carton(s) of %) exceed its free available cartons of that '
                'count there (%) (20 BR-17)', NEW.sku, NEW.warehouse_code, held, NEW.units_per_carton, free
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END
$$ LANGUAGE plpgsql;

-- tg_stock_reservation_soft_atp_insert reads no dropped column and stays; the update trigger asked "did the hold
-- grow", which is now "more cartons, or cartons of another count".
CREATE TRIGGER tg_stock_reservation_soft_atp_update
    AFTER UPDATE ON inventory.stock_reservation
    FOR EACH ROW
    WHEN (NEW.stock_item_id IS NULL AND NEW.status = 'HELD'
          AND (OLD.status <> 'HELD' OR NEW.cartons > OLD.cartons OR NEW.units_per_carton <> OLD.units_per_carton
               OR NEW.sku <> OLD.sku))
    EXECUTE FUNCTION inventory.check_sku_reservation_covered();

-- An allocation is its carton. That it is ONE carton is ck_stock_reservation_one_carton; that the count it carries is
-- its carton's is checked here (it was "its quantity is its row's on_hand", the same fact in units: a carton in stock
-- holds exactly its nominal_qty, check_stock_package_contents). As before, a row in no carton is not judged here:
-- ck_stock_item_whole_cartons already keeps such a row from holding anything.
CREATE OR REPLACE FUNCTION inventory.check_allocation_whole_carton() RETURNS trigger AS $$
DECLARE
    carton_lpn   VARCHAR;
    carton_count INTEGER;
BEGIN
    SELECT p.lpn, p.nominal_qty INTO carton_lpn, carton_count
      FROM inventory.stock_item s
      JOIN inventory.stock_package p ON p.id = s.stock_package_id
     WHERE s.id = NEW.stock_item_id;
    IF FOUND AND NEW.units_per_carton <> carton_count THEN
        RAISE EXCEPTION 'allocation % is of a carton of %, but carton % it is on holds %; an allocation is its carton',
            NEW.id, NEW.units_per_carton, carton_lpn, carton_count USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_stock_reservation_whole_carton
    AFTER INSERT OR UPDATE OF cartons, units_per_carton, status, stock_item_id ON inventory.stock_reservation
    FOR EACH ROW
    WHEN (NEW.allocated_from_id IS NOT NULL AND NEW.status = 'HELD' AND NEW.stock_item_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_allocation_whole_carton();

COMMENT ON TABLE inventory.stock_reservation IS
    'What orders hold of stock, in cartons (ADR-0011, V20261231000500). A SKU-level hold (stock_item_id NULL) is '
    '"cartons" cartons of "units_per_carton" of a SKU at a warehouse, on no carton yet; an allocation (stock_item_id '
    'and allocated_from_id set) is one carton given to the order at release. Units, where somebody needs them, are '
    'cartons * units_per_carton. No hold carries a deadline.';
