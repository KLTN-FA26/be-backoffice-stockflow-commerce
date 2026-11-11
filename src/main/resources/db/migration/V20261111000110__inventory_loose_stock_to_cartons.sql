-- =============================================================================
-- Every unit in stock is in a whole LPN carton (kltn-docs 20 BR-14, BR-18, BR-22, §4.6; 01 BR-03; team plan 4C-1).
--
-- The warehouse keeps, moves, picks and counts whole cartons only; nothing lies loose and no carton is opened. The
-- stock written before held loose units and opened, partly emptied cartons. This puts what is on hand into whole
-- cartons of each SKU's carton unit, so the code that still handles loose units can be removed after it (4C-2).
--
-- The carton unit of a SKU: its one active unit that is not the base unit, or the default shipping one among several
-- (01 BR-03: one carton spec per SKU). Every SKU with stock, or with units reserved for an order, must have one.
--
-- What becomes a carton. Live stock (on_hand > 0) that is not already one whole carton: loose rows, and the rows of an
-- IN_STOCK carton that is not one row holding exactly the SKU's carton count (opened and short, counted in short,
-- over-full, spread over several rows, or made in another unit than the SKU's carton unit). Units are pooled by
-- (location, SKU, lot number, status, serial, expiry) - lot 'UNTRACED' where there was none - and boxed in the SKU's
-- carton unit. Units of an opened, short carton are pooled like loose units, the "phần dư" of the team plan: what
-- they and the loose units beside them make up is boxed, and only what is left over is written off (QD-5).
--   * a live hold of whole cartons is boxed first, a carton per carton it holds: the hold keeps its id on the first and
--     holds one carton; each further carton gets a hold of its own for the same order, allocated from the same SKU
--     reservation, named by the same order lines (ordering.order_line_reservation). An open pick line of the hold
--     picks the first carton, by LPN, as tg_pick_line_carton requires; each new hold gets an open pick line like it;
--   * the free units then fill cartons oldest received first (unknown receipt dates last, as StockAllocator orders
--     them); a carton takes the receipt date and the lot row of its oldest unit;
--   * what is left over, less than a carton, is written off (QD-5): one DATA_CORRECTION adjustment per stock row it
--     comes from, POSTED, requested and approved by the disabled stand-ins the legacy import introduced (nobody can
--     sign in as them), with its ADJUSTMENT ledger line. The adjustments are listed in a NOTICE.
-- A carton made here is IN_STOCK, sealed (unopened), counted in at its standard count, at the place its units were,
-- numbered LPN-yyyyMMdd-nnnnnn from the day's LPN sequence like a carton labelled at receiving. Each portion of a stock
-- row that went into it has a ledger line: MOVE, from the row's place (and old carton) to the same place into the new
-- carton, of the carton's lot, reference STOCK_PACKAGE = that carton, its reason naming the stock row the units came
-- from (archived as it was). ck_stock_movement_shape now lets a MOVE stay in place for exactly that reference; the
-- reference type is new (StockMovement.ReferenceType.STOCK_PACKAGE). The emptied rows' units add up to the units
-- boxed and written off, row by row.
--
-- What stays a carton. An IN_STOCK carton of the SKU's carton count holding it in one row keeps its LPN; if it was
-- opened, or counted in above its standard, it is sealed again at its standard count (it holds a whole carton: 20
-- §5.5 knows no opened carton). If its row has no lot it is relabelled 'UNTRACED' in place, with a MOVE line from the
-- carton into itself naming the carton.
--
-- Lots (20 BR-22, open question C45). A unit with no lot number is of lot 'UNTRACED', origin UNTRACED: it cannot be
-- traced to a production order. One such lot per SKU (and expiry, as a lot row has one): found if it exists, made
-- otherwise. A stock_lot row is never renamed - the ledger lines written before name it as it was - so a goods
-- receipt's lot with no number keeps its receipt and its history; where its units went is the conversion's ledger
-- lines, whose reasons name the stock rows. Units written off keep the lot they had: they never went into a carton.
--
-- Stops, naming what to finish first, instead of guessing:
--   * a SKU with stock or units reserved for an order, and no carton unit;
--   * serial-numbered stock to box in a carton of more than one unit;
--   * a live hold that is not whole cartons: on stock to box, a hold that is not a number of cartons; on a carton kept,
--     a hold of part of it or a second hold on it; a hold of several cartons that a damage report names, or whose open
--     pick line was partly worked (07 BR-03: an order is given whole cartons);
--   * a live SKU reservation that is not a number of cartons (20 §5.6: reservations count cartons);
--   * a document still to act on stock this changes: an adjustment awaiting approval of loose stock or of a carton this
--     changes, a cycle count not completed counting such stock, a putaway task not done for loose stock or for a
--     carton this closes, a damage report still to decide whose damaged units are to be boxed.
--
-- Kept on purpose:
--   * the emptied stock rows (on_hand 0) and the closed cartons: holds, pick lines and the ledger name them;
--   * live SKU reservations stay as they are: units written off can leave one larger than what is left of its SKU, and
--     its order's release then finds too few cartons (INSUFFICIENT_STOCK, until 4C-2 allocates what there is). A NOTICE
--     names such SKUs;
--   * inventory_items.lot_tracked: enforce_sku_policy now lets any SKU's stock carry a lot (every unit has one), and
--     still requires one of a lot-tracked SKU. The policy columns go in team plan 5-5.
--
-- Order: 1. ledger CHECKs; 2. enforce_sku_policy; 3. what changes, and the stops; 4. archive; 5. cartons planned;
-- 6. lots; 7. stock rows emptied, cartons, holds, write-offs, closed and resealed cartons, relabelled rows written;
-- 8. the result checked; 9. NOTICE.
-- Every stock row, carton, hold and pick line changed is archived in platform.legacy_archive first.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Ledger: a MOVE may stay in place when it puts units into the carton it names
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_reference;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_reference CHECK (reference_type IN
    ('GOODS_RECEIPT_LINE', 'QC_INSPECTION', 'PUTAWAY_TASK', 'PICK_LINE', 'PACKAGE', 'MOVE_TASK', 'TRANSFER_ORDER_LINE',
     'STOCK_ADJUSTMENT', 'CYCLE_COUNT_LINE', 'RETURN_REQUEST_LINE', 'ORDER', 'PRODUCTION_ORDER', 'EXPIRY_SWEEP',
     'DAMAGE_REPORT', 'STOCK_PACKAGE'));

-- Every branch is V20261101030000's but MOVE: a MOVE still changes place, unless it boxes units into the carton its
-- reference names (a STOCK_PACKAGE line of this migration) - then it may stay where they were. IS NOT DISTINCT FROM,
-- not =: a line naming no carton to box into would make = NULL, and a CHECK that is NULL passes.
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
        THEN from_location_code = to_location_code
             AND from_status IS NOT NULL AND to_status IS NOT NULL AND from_status <> to_status
    END);


-- ---------------------------------------------------------------------------------------------
-- 2. enforce_sku_policy: every unit carries a lot (20 BR-22), so a lot no longer violates the policy of a SKU that is
--    not lot-tracked. Everything else is V20261017000450's.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION inventory.enforce_sku_policy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p inventory.inventory_items%ROWTYPE; receipt_day date;
BEGIN
    -- Holds/consumption already lock individual rows in id order. Taking a SKU-wide lock here
    -- would invert that order when two reservations span different rows of the same SKU.
    IF TG_OP='UPDATE' AND NEW.sku IS NOT DISTINCT FROM OLD.sku
       AND NEW.received_at IS NOT DISTINCT FROM OLD.received_at
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND NEW.serial_number IS NOT DISTINCT FROM OLD.serial_number
       AND NEW.expiry_date IS NOT DISTINCT FROM OLD.expiry_date
       AND NEW.on_hand <= OLD.on_hand THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.sku, 701));
    SELECT * INTO p FROM inventory.inventory_items WHERE sku=NEW.sku;
    IF NOT FOUND OR NOT p.policy_configured THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN
        IF NEW.sku IS DISTINCT FROM OLD.sku OR NEW.received_at IS DISTINCT FROM OLD.received_at
           OR NEW.lot_number IS DISTINCT FROM OLD.lot_number OR NEW.serial_number IS DISTINCT FROM OLD.serial_number
           OR NEW.expiry_date IS DISTINCT FROM OLD.expiry_date THEN
            RAISE EXCEPTION 'Receipt identity is immutable; reconcile via a new stock layer' USING ERRCODE='23514';
        END IF;
        IF NEW.on_hand <= OLD.on_hand THEN RETURN NEW; END IF;
        -- Replenishment must be a new layer; otherwise FIFO loses the age of incoming units. Units of this
        -- very receipt moved in from another location (identity unchanged, checked just above) are not
        -- replenishment: the application names the row it merges into for that one statement.
        IF p.removal_strategy='FIFO'
           AND current_setting('stockflow.same_receipt_merge', true) IS DISTINCT FROM NEW.id::text THEN
            RAISE EXCEPTION 'FIFO replenishment requires a new receipt layer' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.on_hand = 0 THEN RETURN NEW; END IF;
    IF (p.lot_tracked AND nullif(trim(NEW.lot_number),'') IS NULL)
       OR (p.serial_tracked AND (nullif(trim(NEW.serial_number),'') IS NULL OR NEW.on_hand > 1))
       OR (NOT p.serial_tracked AND NEW.serial_number IS NOT NULL)
       OR (p.expiry_tracked AND NEW.expiry_date IS NULL)
       OR (NOT p.expiry_tracked AND NEW.expiry_date IS NOT NULL)
       OR (p.removal_strategy='FIFO' AND NEW.received_at IS NULL) THEN
        RAISE EXCEPTION 'Stock does not satisfy configured SKU policy' USING ERRCODE='23514';
    END IF;
    receipt_day := COALESCE((NEW.received_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                           (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date);
    IF NEW.received_at > clock_timestamp() + interval '1 minute' OR NEW.expiry_date < receipt_day
       OR NEW.expiry_date < (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
       OR (p.max_shelf_life_days IS NOT NULL AND (NEW.received_at IS NULL OR NEW.expiry_date > receipt_day + p.max_shelf_life_days)) THEN
        RAISE EXCEPTION 'Invalid receipt/expiry date' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;


-- ---------------------------------------------------------------------------------------------
-- 3. What changes, and what has to be finished first
-- ---------------------------------------------------------------------------------------------

-- The carton unit of every SKU with stock or with units reserved for an order: its one active packing unit, or the
-- default shipping one among several. NULL when it has none.
CREATE TEMPORARY TABLE c1_unit ON COMMIT DROP AS
SELECT i.sku, i.id AS item_id, u.id AS unit_id, u.factor
  FROM inventory.inventory_items i
  LEFT JOIN LATERAL (SELECT cu.id, cu.factor
                       FROM inventory.uom_conversions cu
                      WHERE cu.inventory_item_id = i.id AND cu.active AND NOT cu.is_base
                        AND (cu.default_shipping
                             OR (SELECT count(*) FROM inventory.uom_conversions o
                                  WHERE o.inventory_item_id = i.id AND o.active AND NOT o.is_base) = 1)
                      ORDER BY cu.default_shipping DESC
                      LIMIT 1) u ON TRUE
 WHERE i.sku IN (SELECT sku FROM inventory.stock_item WHERE on_hand > 0
                 UNION SELECT sku FROM inventory.stock_reservation WHERE status = 'HELD');

DO $$
DECLARE
    problems TEXT;
BEGIN
    SELECT string_agg(sku, ', ' ORDER BY sku) INTO problems FROM c1_unit WHERE unit_id IS NULL;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these SKUs have stock or units reserved for an order but no carton unit '
            '(exactly one active unit other than the base unit, or a default shipping one among several - kltn-docs '
            '01 BR-03): %. Declare the carton unit first.', problems;
    END IF;
END $$;

-- IN_STOCK cartons, and whether each already is one whole carton of its SKU: made at the SKU's carton count, holding
-- it in one live row.
CREATE TEMPORARY TABLE c1_carton ON COMMIT DROP AS
SELECT p.id, p.lpn, p.nominal_qty, p.received_qty, p.unopened, p.location_code,
       p.nominal_qty = u.factor
           AND count(s.id) FILTER (WHERE s.on_hand > 0) = 1
           AND COALESCE(sum(s.on_hand), 0) = u.factor AS whole
  FROM inventory.stock_package p
  JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
  JOIN c1_unit u ON u.sku = i.sku
  LEFT JOIN inventory.stock_item s ON s.stock_package_id = p.id
 WHERE p.status = 'IN_STOCK'
 GROUP BY p.id, u.factor;

-- Live rows to box: loose, or in a carton that is not whole.
CREATE TEMPORARY TABLE c1_row ON COMMIT DROP AS
SELECT s.id, s.sku, s.location_code, s.status, s.lot_number, s.lot_id, s.expiry_date, s.received_at, s.serial_number,
       s.on_hand, s.reserved, s.stock_package_id, s.created_at,
       CASE WHEN NULLIF(btrim(s.lot_number), '') IS NULL THEN 'UNTRACED' ELSE s.lot_number END AS lot_key
  FROM inventory.stock_item s
  LEFT JOIN c1_carton c ON c.id = s.stock_package_id
 WHERE s.on_hand > 0
   AND (s.stock_package_id IS NULL OR NOT COALESCE(c.whole, FALSE));

-- Live rows of whole cartons that have no lot: relabelled in place.
CREATE TEMPORARY TABLE c1_relabel ON COMMIT DROP AS
SELECT s.id, s.sku, s.location_code, s.status, s.lot_id, s.expiry_date, s.on_hand, s.stock_package_id
  FROM inventory.stock_item s
  JOIN c1_carton c ON c.id = s.stock_package_id AND c.whole
 WHERE s.on_hand > 0 AND NULLIF(btrim(s.lot_number), '') IS NULL;

-- Cartons this changes: emptied and closed (not whole), relabelled or resealed (whole).
CREATE TEMPORARY TABLE c1_changed_carton ON COMMIT DROP AS
SELECT c.id, NOT c.whole AS closed
  FROM c1_carton c
 WHERE NOT c.whole
    OR c.id IN (SELECT stock_package_id FROM c1_relabel)
    OR (c.whole AND (NOT c.unopened OR c.received_qty IS DISTINCT FROM c.nominal_qty));

-- The live holds on stock to box, in cartons.
CREATE TEMPORARY TABLE c1_hold ON COMMIT DROP AS
SELECT h.id, h.stock_item_id AS row_id, h.quantity, u.factor, h.quantity / u.factor AS cartons
  FROM inventory.stock_reservation h
  JOIN c1_row r ON r.id = h.stock_item_id
  JOIN c1_unit u ON u.sku = r.sku
 WHERE h.status = 'HELD';

DO $$
DECLARE
    problems TEXT;
BEGIN
    SELECT string_agg(format('%s x %s at %s holds %s, its live holds %s', s.on_hand, s.sku, s.location_code,
                             s.reserved, COALESCE(h.quantity, 0)), '; ') INTO problems
      FROM inventory.stock_item s
      LEFT JOIN (SELECT stock_item_id, sum(quantity) AS quantity FROM inventory.stock_reservation
                  WHERE status = 'HELD' AND stock_item_id IS NOT NULL GROUP BY stock_item_id) h
             ON h.stock_item_id = s.id
     WHERE s.on_hand > 0 AND s.reserved <> COALESCE(h.quantity, 0);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: the reserved count of these stock rows is not their live holds; repair them '
            'first: %', problems;
    END IF;

    SELECT string_agg(format('%s %s at %s (%s per carton)', r.sku, r.serial_number, r.location_code, u.factor), '; ')
      INTO problems
      FROM c1_row r JOIN c1_unit u ON u.sku = r.sku
     WHERE r.serial_number IS NOT NULL AND u.factor <> 1;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: serial-numbered stock cannot go into a carton of several units: %', problems;
    END IF;

    SELECT string_agg(format('order %s hold %s: %s x %s at %s, a carton is %s', COALESCE(r.order_number, '-'), h.id,
                             h.quantity, c.sku, c.location_code, h.factor), '; ' ORDER BY h.id)
      INTO problems
      FROM c1_hold h
      JOIN inventory.stock_reservation r ON r.id = h.id
      JOIN c1_row c ON c.id = h.row_id
     WHERE h.quantity % h.factor <> 0;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these holds of loose or opened stock are not a number of whole cartons, and '
            'an order is given whole cartons (kltn-docs 07 BR-03); release them first (the order loses that stock, as '
            'a short pick would): %', problems;
    END IF;

    SELECT string_agg(format('order %s hold %s', COALESCE(r.order_number, '-'), h.id), '; ' ORDER BY h.id)
      INTO problems
      FROM c1_hold h
      JOIN inventory.stock_reservation r ON r.id = h.id
     WHERE h.cartons > 1
       AND (EXISTS (SELECT 1 FROM inventory.stock_damage_report d
                     WHERE h.id IN (d.reservation_id, d.impaired_reservation_id))
            OR EXISTS (SELECT 1 FROM inventory.stock_adjustment a
                        WHERE a.reservation_id = h.id AND a.status IN ('PENDING_APPROVAL', 'APPROVED'))
            OR EXISTS (SELECT 1 FROM fulfillment.pick_line l
                        WHERE l.reservation_id = h.id AND l.status = 'OPEN'
                          AND (l.picked_qty > 0 OR l.damaged_qty > 0 OR l.packing_damaged_qty > 0)));
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these holds of several cartons are named by a damage report or an adjustment, '
            'or were partly picked, and cannot be split into a hold per carton; settle them first: %', problems;
    END IF;

    SELECT string_agg(format('order %s hold %s: %s of carton %s (%s)', COALESCE(h.order_number, '-'), h.id,
                             h.quantity, c.lpn, c.nominal_qty), '; ' ORDER BY h.id)
      INTO problems
      FROM inventory.stock_reservation h
      JOIN inventory.stock_item s ON s.id = h.stock_item_id
      JOIN c1_carton c ON c.id = s.stock_package_id AND c.whole
     WHERE h.status = 'HELD'
       AND (h.quantity <> s.on_hand
            OR EXISTS (SELECT 1 FROM inventory.stock_reservation o
                        WHERE o.stock_item_id = s.id AND o.status = 'HELD' AND o.id <> h.id));
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these holds take part of a carton, and an order is given whole cartons '
            '(kltn-docs 07 BR-03); release them first: %', problems;
    END IF;

    SELECT string_agg(format('order %s reservation %s: %s x %s, a carton is %s', COALESCE(h.order_number, '-'), h.id,
                             h.quantity, h.sku, u.factor), '; ' ORDER BY h.id)
      INTO problems
      FROM inventory.stock_reservation h
      JOIN c1_unit u ON u.sku = h.sku
     WHERE h.status = 'HELD' AND h.stock_item_id IS NULL AND h.quantity % u.factor <> 0;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these reservations are not a number of whole cartons, and a reservation counts '
            'cartons (kltn-docs 20 §5.6); release or change them first: %', problems;
    END IF;

    SELECT string_agg(a.adjustment_number, ', ' ORDER BY a.adjustment_number) INTO problems
      FROM inventory.stock_adjustment a
     WHERE a.status IN ('PENDING_APPROVAL', 'APPROVED')
       AND (a.stock_package_id IS NULL OR a.stock_package_id IN (SELECT id FROM c1_changed_carton));
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these adjustments await a decision on loose stock or on a carton this '
            'changes; approve, reject or withdraw them first: %', problems;
    END IF;

    SELECT string_agg(DISTINCT cc.count_number, ', ' ORDER BY cc.count_number) INTO problems
      FROM inventory.cycle_count cc
      JOIN inventory.cycle_count_line l ON l.cycle_count_id = cc.id
     WHERE cc.status IN ('PLANNED', 'IN_PROGRESS')
       AND (l.stock_package_id IS NULL OR l.stock_package_id IN (SELECT id FROM c1_changed_carton));
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these cycle counts still count loose stock or a carton this changes; '
            'submit or cancel them first: %', problems;
    END IF;

    SELECT string_agg(format('%s (%s x %s)', t.id, t.quantity, t.sku), ', ' ORDER BY t.id) INTO problems
      FROM warehouse.putaway_task t
     WHERE t.status IN ('PENDING', 'IN_PROGRESS')
       AND (t.stock_package_id IS NULL
            OR t.stock_package_id IN (SELECT id FROM c1_changed_carton WHERE closed));
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these putaway tasks move loose stock or a carton this empties; finish or '
            'cancel them first: %', problems;
    END IF;

    SELECT string_agg(d.report_number, ', ' ORDER BY d.report_number) INTO problems
      FROM inventory.stock_damage_report d
     WHERE d.damaged_stock_item_id IN (SELECT id FROM c1_row)
       AND d.quantity > COALESCE((SELECT sum(x.quantity) FROM inventory.stock_damage_disposition x
                                   WHERE x.damage_report_id = d.id), 0);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: the damaged units of these damage reports, still to decide, are loose; '
            'decide them first: %', problems;
    END IF;
END $$;


-- ---------------------------------------------------------------------------------------------
-- 4. Archive what changes, as it was
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.stock_item', s.id, to_jsonb(s) FROM inventory.stock_item s
 WHERE s.id IN (SELECT id FROM c1_row UNION SELECT id FROM c1_relabel)
UNION ALL
SELECT 'inventory.stock_package', p.id, to_jsonb(p) FROM inventory.stock_package p
 WHERE p.id IN (SELECT id FROM c1_changed_carton)
UNION ALL
SELECT 'inventory.stock_reservation', h.id, to_jsonb(h) FROM inventory.stock_reservation h
 WHERE h.id IN (SELECT id FROM c1_hold)
UNION ALL
SELECT 'fulfillment.pick_line', l.id, to_jsonb(l) FROM fulfillment.pick_line l
 WHERE l.status = 'OPEN' AND l.reservation_id IN (SELECT id FROM c1_hold);

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), the customization templates and print areas as they were before templates got a model '
        '(V20261108000600), design statuses renamed when customers began designing (V20261108000800), the staff '
        'design workflow''s assignments, its designs left waiting for confirmation and its permission '
        '(V20261108001000), the parcel carriers'' status before they were retired (V20261108001300), and the stock '
        'rows, cartons, holds and open pick lines as they were before loose stock went into cartons '
        '(V20261111000110). Read-only history.';


-- ---------------------------------------------------------------------------------------------
-- 5. Cartons, planned
-- ---------------------------------------------------------------------------------------------

-- The rows a carton is filled from are taken oldest received first, unknown dates last (StockAllocator's order).
ALTER TABLE c1_row ADD COLUMN ord BIGINT, ADD COLUMN held INT NOT NULL DEFAULT 0, ADD COLUMN free INT,
                   ADD COLUMN start_unit BIGINT, ADD COLUMN new_lot_id UUID;
UPDATE c1_row r SET ord = o.ord
  FROM (SELECT id, row_number() OVER (ORDER BY received_at NULLS LAST, expiry_date NULLS LAST, created_at, id) AS ord
          FROM c1_row) o
 WHERE o.id = r.id;
UPDATE c1_row r SET held = h.quantity
  FROM (SELECT row_id, sum(quantity)::INT AS quantity FROM c1_hold GROUP BY row_id) h
 WHERE h.row_id = r.id;
UPDATE c1_row SET free = on_hand - held;
-- Where the free units of a row start in its pool: (location, SKU, lot, status, serial, expiry).
UPDATE c1_row r SET start_unit = s.start_unit
  FROM (SELECT id, COALESCE(sum(free) OVER (PARTITION BY location_code, sku, lot_key, status, serial_number,
                                                         expiry_date
                                            ORDER BY ord ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0)
                       AS start_unit
          FROM c1_row) s
 WHERE s.id = r.id;

CREATE TEMPORARY TABLE c1_pool ON COMMIT DROP AS
SELECT r.location_code, r.sku, r.lot_key, r.status, r.serial_number, r.expiry_date, u.item_id, u.unit_id, u.factor,
       sum(r.free)::BIGINT AS free
  FROM c1_row r JOIN c1_unit u ON u.sku = r.sku
 GROUP BY r.location_code, r.sku, r.lot_key, r.status, r.serial_number, r.expiry_date, u.item_id, u.unit_id, u.factor;

-- A carton per carton of each live hold (the first keeps the hold, each further one gets a hold of its own), then one
-- per full carton of each pool.
CREATE TEMPORARY TABLE c1_new ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, gen_random_uuid() AS row_id, h.id AS hold_id, piece,
       CASE WHEN piece = 0 THEN h.id ELSE gen_random_uuid() END AS carton_hold_id, r.id AS first_row_id,
       NULL::BIGINT AS carton_no, r.location_code, r.sku, r.lot_key, r.status, r.serial_number, r.expiry_date,
       u.item_id, u.unit_id, u.factor
  FROM c1_hold h
  JOIN c1_row r ON r.id = h.row_id
  JOIN c1_unit u ON u.sku = r.sku
  CROSS JOIN LATERAL generate_series(0, h.cartons - 1) AS piece;
INSERT INTO c1_new
SELECT gen_random_uuid(), gen_random_uuid(), NULL, NULL, NULL, NULL, k, p.location_code, p.sku, p.lot_key, p.status,
       p.serial_number, p.expiry_date, p.item_id, p.unit_id, p.factor
  FROM c1_pool p, generate_series(0, p.free / p.factor - 1) AS k;

-- A pooled carton covers free units [k * factor, (k + 1) * factor) of its pool; its oldest unit names its identity.
CREATE TEMPORARY TABLE c1_portion ON COMMIT DROP AS
SELECT n.id AS carton_id, n.first_row_id AS row_id, n.factor AS quantity
  FROM c1_new n
 WHERE n.hold_id IS NOT NULL
UNION ALL
SELECT n.id, r.id,
       (LEAST(r.start_unit + r.free, (n.carton_no + 1) * n.factor) - GREATEST(r.start_unit, n.carton_no * n.factor))::INT
  FROM c1_new n
  JOIN c1_row r ON r.location_code = n.location_code AND r.sku = n.sku AND r.lot_key = n.lot_key
               AND r.status = n.status AND r.serial_number IS NOT DISTINCT FROM n.serial_number
               AND r.expiry_date IS NOT DISTINCT FROM n.expiry_date
 WHERE n.hold_id IS NULL
   AND r.free > 0
   AND r.start_unit < (n.carton_no + 1) * n.factor
   AND r.start_unit + r.free > n.carton_no * n.factor;
UPDATE c1_new n
   SET first_row_id = (SELECT p.row_id FROM c1_portion p JOIN c1_row r ON r.id = p.row_id
                        WHERE p.carton_id = n.id ORDER BY r.ord LIMIT 1)
 WHERE n.hold_id IS NULL;

-- Less than a carton left in a pool: written off from the rows it is in (QD-5).
CREATE TEMPORARY TABLE c1_writeoff ON COMMIT DROP AS
SELECT gen_random_uuid() AS adjustment_id, r.id AS row_id,
       (r.start_unit + r.free - GREATEST(r.start_unit, (p.free / p.factor) * p.factor))::INT AS quantity
  FROM c1_row r
  JOIN c1_pool p ON p.location_code = r.location_code AND p.sku = r.sku AND p.lot_key = r.lot_key
                AND p.status = r.status AND p.serial_number IS NOT DISTINCT FROM r.serial_number
                AND p.expiry_date IS NOT DISTINCT FROM r.expiry_date
 WHERE r.free > 0 AND r.start_unit + r.free > (p.free / p.factor) * p.factor;


-- ---------------------------------------------------------------------------------------------
-- 6. Lots: the SKU's lot 'UNTRACED' (one per expiry) for every carton labelled with it
-- ---------------------------------------------------------------------------------------------
CREATE TEMPORARY TABLE c1_untraced ON COMMIT DROP AS
SELECT DISTINCT x.sku, x.expiry_date, NULL::UUID AS lot_id, FALSE AS made
  FROM (SELECT r.sku, r.expiry_date FROM c1_new n JOIN c1_row r ON r.id = n.first_row_id WHERE r.lot_key = 'UNTRACED'
        UNION SELECT sku, expiry_date FROM c1_relabel) x;
UPDATE c1_untraced t
   SET lot_id = (SELECT l.id FROM inventory.stock_lot l
                  WHERE l.sku = t.sku AND l.lot_number = 'UNTRACED' AND l.origin_type = 'UNTRACED'
                    AND l.expiry_date IS NOT DISTINCT FROM t.expiry_date
                  ORDER BY l.created_at, l.id LIMIT 1);
UPDATE c1_untraced SET lot_id = gen_random_uuid(), made = TRUE WHERE lot_id IS NULL;
INSERT INTO inventory.stock_lot (id, sku, lot_number, expiry_date, origin_type, version, created_at, created_by)
SELECT lot_id, sku, 'UNTRACED', expiry_date, 'UNTRACED', 0, NOW(), 'V20261111000110' FROM c1_untraced WHERE made;

UPDATE c1_row r
   SET new_lot_id = CASE WHEN r.lot_key = 'UNTRACED'
                         THEN (SELECT t.lot_id FROM c1_untraced t
                                WHERE t.sku = r.sku AND t.expiry_date IS NOT DISTINCT FROM r.expiry_date)
                         ELSE r.lot_id END;


-- ---------------------------------------------------------------------------------------------
-- 7. Written
-- ---------------------------------------------------------------------------------------------

-- The checks of section 3 hold as long as nothing writes in between, and the policy this migration copies stock under
-- is the one the stock already had: the receipt checks of enforce_sku_policy (dates, FIFO receipt) are for stock
-- arriving, not for units changing boxes. The transition guard refuses resealing a whole carton that was opened.
ALTER TABLE inventory.stock_item DISABLE TRIGGER trg_stock_sku_policy;
ALTER TABLE inventory.stock_package DISABLE TRIGGER tg_stock_package_transition;

-- Every unit of a boxed row goes into a carton or is written off, so the row is emptied - first: a carton of one may
-- carry the row's serial number, which one live row at a time may carry (uk_stock_item_live_serial).
UPDATE inventory.stock_item s
   SET on_hand = 0, reserved = 0, version = s.version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261111000110'
  FROM c1_row r
 WHERE r.id = s.id;

-- LPNs: the next numbers of today's sequence, as LpnSequence hands them out.
CREATE TEMPORARY TABLE c1_lpn ON COMMIT DROP AS
SELECT n.id, row_number() OVER (ORDER BY n.location_code, n.sku, n.lot_key, n.hold_id NULLS LAST, n.piece,
                                         n.carton_no) AS no
  FROM c1_new n;
ALTER TABLE c1_lpn ADD COLUMN lpn VARCHAR(30);
DO $$
DECLARE
    day  DATE := (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;
    n    BIGINT := (SELECT count(*) FROM c1_lpn);
    last BIGINT;
BEGIN
    IF n = 0 THEN
        RETURN;
    END IF;
    INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
    VALUES ('LPN', day, n)
    ON CONFLICT (document_type, sequence_date)
        DO UPDATE SET last_value = platform.document_sequence.last_value + n
    RETURNING last_value INTO last;
    UPDATE c1_lpn SET lpn = 'LPN-' || to_char(day, 'YYYYMMDD') || '-'
                            || CASE WHEN last - n + no < 1000000 THEN lpad((last - n + no)::TEXT, 6, '0')
                                    ELSE (last - n + no)::TEXT END;
END $$;

INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status, unopened, version, created_at, created_by)
SELECT n.id, l.lpn, n.item_id, n.unit_id, n.factor, n.factor, n.location_code, 'IN_STOCK', TRUE, 0, NOW(),
       'V20261111000110'
  FROM c1_new n JOIN c1_lpn l ON l.id = n.id;

INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, version,
                                  created_at, created_by, received_at, serial_number, stock_package_id, lot_id)
SELECT n.row_id, n.sku, n.location_code, n.lot_key, n.expiry_date, n.factor,
       CASE WHEN n.hold_id IS NULL THEN 0 ELSE n.factor END, n.status, 0, NOW(), 'V20261111000110', r.received_at,
       n.serial_number, n.id, r.new_lot_id
  FROM c1_new n JOIN c1_row r ON r.id = n.first_row_id;

-- One MOVE per portion: from where the units were (and their old carton) into the new carton, in place, of the
-- carton's lot; the reason names the stock row they came from.
INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, reason,
                                      occurred_at, created_by, from_stock_package_id, to_stock_package_id, lot_id)
SELECT gen_random_uuid(), 'MOVE', n.sku, n.lot_key, n.location_code, n.location_code, p.quantity, n.status, n.status,
       'STOCK_PACKAGE', n.id, 'Gom vào thùng ' || l.lpn || ' từ dòng tồn ' || r.id || ' (V20261111000110)', NOW(),
       'V20261111000110', r.stock_package_id, n.id, f.new_lot_id
  FROM c1_portion p
  JOIN c1_new n ON n.id = p.carton_id
  JOIN c1_lpn l ON l.id = n.id
  JOIN c1_row r ON r.id = p.row_id
  JOIN c1_row f ON f.id = n.first_row_id;

-- The holds follow their cartons: a hold of several cartons keeps the first and hands the others to holds of their
-- own, for the same order and SKU reservation, named by the same order lines.
INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity,
                                         reserved_at, expires_at, status, sku, allocated_from_id, order_number)
SELECT n.carton_hold_id, n.row_id, h.order_id, h.root_request_id, gen_random_uuid(), n.factor, h.reserved_at,
       h.expires_at, 'HELD', h.sku, h.allocated_from_id, h.order_number
  FROM c1_new n JOIN inventory.stock_reservation h ON h.id = n.hold_id
 WHERE n.piece > 0;
UPDATE inventory.stock_reservation h
   SET stock_item_id = n.row_id, quantity = n.factor
  FROM c1_new n
 WHERE n.hold_id = h.id AND n.piece = 0;
INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id)
SELECT olr.order_line_id, n.carton_hold_id
  FROM c1_new n JOIN ordering.order_line_reservation olr ON olr.reservation_id = n.hold_id
 WHERE n.piece > 0;

-- An open pick line of a hold picks its first carton, by LPN; each further carton gets an open pick line like it.
INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, lot_number,
                                   sequence_no, allocated_qty, status, lpn, whole_carton, version, created_at,
                                   created_by)
SELECT gen_random_uuid(), pl.pick_id, pl.order_line_id, n.carton_hold_id, pl.sku, pl.location_code, pl.lot_number,
       pl.sequence_no, n.factor, 'OPEN', l.lpn, TRUE, 0, NOW(), 'V20261111000110'
  FROM c1_new n
  JOIN c1_lpn l ON l.id = n.id
  JOIN fulfillment.pick_line pl ON pl.reservation_id = n.hold_id AND pl.status = 'OPEN'
 WHERE n.piece > 0;
UPDATE fulfillment.pick_line pl
   SET lpn = l.lpn, whole_carton = TRUE, allocated_qty = n.factor, version = pl.version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261111000110'
  FROM c1_new n JOIN c1_lpn l ON l.id = n.id
 WHERE pl.reservation_id = n.hold_id AND n.piece = 0 AND pl.status = 'OPEN';

-- The write-offs: the disabled stand-ins of V20261011000200 ask and approve (nobody can sign in as them).
INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at, created_by)
SELECT md5('legacy:user:import')::uuid, 'system.legacy-import', 'legacy-import@system.invalid', '!no-login',
       'DISABLED', 0, NOW(), 'legacy:import'
 WHERE EXISTS (SELECT 1 FROM c1_writeoff)
ON CONFLICT DO NOTHING;
INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at, created_by)
SELECT md5('legacy:user:approval')::uuid, 'system.legacy-approval', 'legacy-approval@system.invalid', '!no-login',
       'DISABLED', 0, NOW(), 'legacy:import'
 WHERE EXISTS (SELECT 1 FROM c1_writeoff)
ON CONFLICT DO NOTHING;

ALTER TABLE c1_writeoff ADD COLUMN adjustment_number VARCHAR(30);
DO $$
DECLARE
    day  DATE := (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;
    n    BIGINT := (SELECT count(*) FROM c1_writeoff);
    last BIGINT;
BEGIN
    IF n = 0 THEN
        RETURN;
    END IF;
    INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
    VALUES ('ADJ', day, n)
    ON CONFLICT (document_type, sequence_date)
        DO UPDATE SET last_value = platform.document_sequence.last_value + n
    RETURNING last_value INTO last;
    UPDATE c1_writeoff w
       SET adjustment_number = 'ADJ-' || to_char(day, 'YYYYMMDD') || '-'
                               || CASE WHEN last - n + o.no < 10000 THEN lpad((last - n + o.no)::TEXT, 4, '0')
                                       ELSE (last - n + o.no)::TEXT END
      FROM (SELECT row_id, row_number() OVER (ORDER BY row_id) AS no FROM c1_writeoff) o
     WHERE o.row_id = w.row_id;
END $$;

INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, lot_number, quantity_delta,
                                        reason_code, note, status, requested_by, decided_by, decided_at, posted_at,
                                        version, created_at, created_by, received_at, stock_package_id)
SELECT w.adjustment_id, w.adjustment_number, r.location_code, r.sku, r.lot_number, -w.quantity, 'DATA_CORRECTION',
       'Phần dư không đủ một thùng khi gom tồn lẻ vào thùng LPN (V20261111000110, QD-5)', 'POSTED',
       md5('legacy:user:import')::uuid, md5('legacy:user:approval')::uuid, NOW(), NOW(), 0, NOW(), 'V20261111000110',
       r.received_at, r.stock_package_id
  FROM c1_writeoff w JOIN c1_row r ON r.id = w.row_id;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, reason, actor_id,
                                      occurred_at, created_by, from_stock_package_id, lot_id)
SELECT gen_random_uuid(), 'ADJUSTMENT', r.sku, r.lot_number, r.location_code, NULL, w.quantity, r.status, r.status,
       'STOCK_ADJUSTMENT', w.adjustment_id, 'DATA_CORRECTION', md5('legacy:user:approval')::uuid, NOW(),
       'V20261111000110', r.stock_package_id, r.lot_id
  FROM c1_writeoff w JOIN c1_row r ON r.id = w.row_id;

-- A carton that was not whole is empty now.
UPDATE inventory.stock_package p
   SET status = 'CONSUMED', unopened = FALSE, closed_at = NOW(), version = p.version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261111000110'
  FROM c1_changed_carton c
 WHERE c.id = p.id AND c.closed;

-- A whole carton is sealed at its standard count; one with no lot is of lot 'UNTRACED'.
UPDATE inventory.stock_package p
   SET unopened = TRUE, received_qty = p.nominal_qty, version = p.version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261111000110'
  FROM c1_changed_carton c
 WHERE c.id = p.id AND NOT c.closed AND (NOT p.unopened OR p.received_qty IS DISTINCT FROM p.nominal_qty);

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, reason,
                                      occurred_at, created_by, from_stock_package_id, to_stock_package_id, lot_id)
SELECT gen_random_uuid(), 'MOVE', r.sku, 'UNTRACED', r.location_code, r.location_code, r.on_hand, r.status, r.status,
       'STOCK_PACKAGE', r.stock_package_id,
       'Lô UNTRACED cho dòng tồn ' || r.id || ', không ghi lô (V20261111000110, C45)', NOW(), 'V20261111000110',
       r.stock_package_id, r.stock_package_id, t.lot_id
  FROM c1_relabel r
  JOIN c1_untraced t ON t.sku = r.sku AND t.expiry_date IS NOT DISTINCT FROM r.expiry_date;
UPDATE inventory.stock_item s
   SET lot_number = 'UNTRACED', lot_id = t.lot_id, version = s.version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261111000110'
  FROM c1_relabel r
  JOIN c1_untraced t ON t.sku = r.sku AND t.expiry_date IS NOT DISTINCT FROM r.expiry_date
 WHERE s.id = r.id;

-- The deferred carton checks run now, so the two guards can be enabled again on tables with no pending events. The
-- rest of this migration only reads; Flyway commits it on its own (spring.flyway.group is off).
SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE inventory.stock_item ENABLE TRIGGER trg_stock_sku_policy;
ALTER TABLE inventory.stock_package ENABLE TRIGGER tg_stock_package_transition;


-- ---------------------------------------------------------------------------------------------
-- 8. The result: nothing loose, every carton whole and labelled with a lot, every hold whole cartons, every emptied
--    row's units boxed or written off
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    problems TEXT;
BEGIN
    SELECT string_agg(format('%s x %s at %s', on_hand, sku, location_code), '; ') INTO problems
      FROM inventory.stock_item WHERE on_hand > 0 AND stock_package_id IS NULL;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: stock is still loose: %', problems;
    END IF;

    SELECT string_agg(p.lpn, ', ') INTO problems
      FROM inventory.stock_package p
      JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
      JOIN c1_unit u ON u.sku = i.sku
     WHERE p.status = 'IN_STOCK'
       AND (NOT p.unopened OR p.received_qty IS DISTINCT FROM p.nominal_qty OR p.nominal_qty <> u.factor
            OR (SELECT count(*) FROM inventory.stock_item s
                 WHERE s.stock_package_id = p.id AND s.on_hand > 0 AND s.on_hand = p.nominal_qty
                   AND NULLIF(btrim(s.lot_number), '') IS NOT NULL) <> 1
            OR (SELECT count(*) FROM inventory.stock_item s WHERE s.stock_package_id = p.id AND s.on_hand > 0) <> 1);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these cartons are not one sealed carton of the SKU''s carton count, of one '
            'lot: %', problems;
    END IF;

    SELECT string_agg(s.id::TEXT, ', ') INTO problems
      FROM inventory.stock_item s
     WHERE s.reserved <> COALESCE((SELECT sum(h.quantity) FROM inventory.stock_reservation h
                                    WHERE h.stock_item_id = s.id AND h.status = 'HELD'), 0)
        OR (s.reserved > 0 AND s.reserved <> s.on_hand);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: these stock rows are held other than as one whole carton: %', problems;
    END IF;

    SELECT string_agg(r.id::TEXT, ', ') INTO problems
      FROM c1_row r
     WHERE r.on_hand <> COALESCE((SELECT sum(p.quantity) FROM c1_portion p WHERE p.row_id = r.id), 0)
                      + COALESCE((SELECT sum(w.quantity) FROM c1_writeoff w WHERE w.row_id = r.id), 0);
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000110: the units of these stock rows were not all boxed or written off: %',
            problems;
    END IF;
END $$;


-- ---------------------------------------------------------------------------------------------
-- 9. What was done
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    line RECORD;
BEGIN
    RAISE NOTICE 'V20261111000110: % cartons made (% of them for holds, % holds added for holds of several cartons), '
        '% stock rows emptied, % cartons closed, % cartons sealed again, % rows relabelled UNTRACED, % lots UNTRACED '
        'added',
        (SELECT count(*) FROM c1_new), (SELECT count(*) FROM c1_new WHERE hold_id IS NOT NULL),
        (SELECT count(*) FROM c1_new WHERE piece > 0),
        (SELECT count(*) FROM c1_row), (SELECT count(*) FROM c1_changed_carton WHERE closed),
        (SELECT count(*) FROM c1_changed_carton c JOIN c1_carton w ON w.id = c.id
          WHERE NOT c.closed AND (NOT w.unopened OR w.received_qty IS DISTINCT FROM w.nominal_qty)),
        (SELECT count(*) FROM c1_relabel), (SELECT count(*) FROM c1_untraced WHERE made);
    FOR line IN SELECT w.adjustment_number, r.sku, r.location_code, r.lot_number, w.quantity
                  FROM c1_writeoff w JOIN c1_row r ON r.id = w.row_id ORDER BY w.adjustment_number LOOP
        RAISE NOTICE 'V20261111000110: written off % x % at % (lot %), less than a carton: %', line.quantity,
            line.sku, line.location_code, COALESCE(line.lot_number, '-'), line.adjustment_number;
    END LOOP;
    FOR line IN SELECT h.sku, sum(h.quantity) AS held,
                       COALESCE((SELECT sum(s.on_hand - s.reserved) FROM inventory.stock_item s
                                  WHERE s.sku = h.sku AND s.status = 'AVAILABLE'), 0) AS free
                  FROM inventory.stock_reservation h
                 WHERE h.status = 'HELD' AND h.stock_item_id IS NULL
                 GROUP BY h.sku
                HAVING sum(h.quantity) > COALESCE((SELECT sum(s.on_hand - s.reserved) FROM inventory.stock_item s
                                                    WHERE s.sku = h.sku AND s.status = 'AVAILABLE'), 0) LOOP
        RAISE NOTICE 'V20261111000110: % is reserved % but % is free to allocate', line.sku, line.held, line.free;
    END LOOP;
END $$;
