-- The carton is the stock (owner decision 2026-11-26/27, ADR-0011, step 3d).
--
-- One LPN carton (inventory.stock_package) had exactly one inventory.stock_item row (uk_stock_item_package), holding
-- on_hand in {0, nominal_qty}, reserved in {0, on_hand}, a stock status, the lot and the FIFO date: the carton's state
-- under another name, kept equal to the carton by check_stock_package_contents at commit - except "reserved", which
-- nothing reconciled with inventory.stock_reservation. The row is folded into the carton and goes:
--
--   inventory.stock_package       stock_status   what stock_item.status was, set exactly while the carton is IN_STOCK
--                                 lot_id         the carton's lot; required once it has been in stock, then frozen
--                                 received_at    the FIFO date; NULL is "not known" and sorts last, frozen with the lot
--   inventory.stock_reservation   stock_package_id replaces stock_item_id: an allocation names its carton
--   inventory.stock_item          dropped, with on_hand and reserved
--   inventory.stock_package_balance (view)   dropped: it joined the two
--
-- A carton holds no quantity: in stock it stands for its nominal_qty, out of stock for nothing. "Is this carton taken"
-- is read from its live allocation, the one HELD row of inventory.stock_reservation that names it
-- (uk_stock_reservation_live_carton), not from a second counter.
--
-- Which statuses are a live allocation. A row of stock_reservation on a carton is HELD (the carton is taken),
-- CONSUMED (it was picked), RELEASED or EXPIRED (given up); ALLOCATED is only ever the status of a SKU-level hold whose
-- cartons were chosen (ck_stock_reservation_kind). So live = stock_package_id IS NOT NULL AND status = 'HELD'.
--
-- No sku and no lot_number on the carton: the SKU is its item's (inventory_item_id -> inventory.inventory_items), the
-- lot number its lot's (lot_id -> inventory.stock_lot). cycle_count_line and stock_adjustment keep their sku and
-- lot_number snapshots (owner decision; a later step).
--
-- Backfill, from the carton's one row:
--   stock_status  the row's status where the carton is IN_STOCK, else NULL. The stale status that the empty rows of
--                 CONSUMED and LOST cartons carried is not kept (ADR-0011: a carton out of stock has no stock status);
--                 what a carton gets when it comes back is said by the operation that brings it back.
--   lot_id        the row's lot, whatever the carton's status: it has been in stock.
--   received_at   the row's, NULL staying NULL: an undated carton sorts last, as StockAllocator ordered its row. Not
--                 filled from created_at, which would reorder stock already on the shelf.
--   stock_reservation.stock_package_id   the carton of the row the reservation was on.
-- Measured on the demo seed (db/migration + db/demo): 589 rows = 585 in a carton, one each, + 4 loose, empty, held by
-- nothing; 177 cartons IN_STOCK (145 AVAILABLE, 32 QUARANTINE; 75 undated), 407 CONSUMED whose empty row still said
-- AVAILABLE, 1 LOST with QUARANTINE; 410 allocations, all CONSUMED, none on a loose row; no row with reserved > 0.
--
-- TRAP. fk_reservation_stock_item is ON DELETE CASCADE: dropping stock_item before the reservations are repointed would
-- delete every allocation that fulfillment.pick_line, ordering.order_line_reservation, ordering.delivery_batch_hold and
-- ordering.delivery_failure_case_hold reference. They are repointed first, the foreign key and the column dropped, and
-- only then the table.
--
-- Nothing live is guessed. The migration refuses to run, naming up to 10 rows, over
--   a carton that has been in stock (IN_STOCK, CONSUMED, LOST) with no stock row: nobody can tell its lot;
--   a HELD reservation on a stock row with no carton, on a carton that is not in stock, or on one whose stock is not
--   AVAILABLE; two HELD reservations on one carton: "reserved" was never reconciled with these rows, so they are looked
--   at here once;
--   a closed reservation on a stock row with no carton that something still references.
-- Deleted, not converted (QD-1): loose stock rows (empty since V20261117000100, ck_stock_item_whole_cartons) and the
-- closed reservations on them that nothing references - loose history of before the baseline.
--
-- Readers of stock_item, on_hand, reserved and stock_reservation.stock_item_id: looked for in pg_proc.prosrc, pg_views,
-- pg_matviews, pg_constraint, pg_trigger, pg_index and pg_depend of a database with every migration applied (Postgres
-- does not track what a function body mentions). Every one is rewritten or dropped below:
--   dropped    inventory.stock_package_balance (view); every trigger, CHECK and index of stock_item;
--              inventory.check_stock_item_package, check_stock_package_contents, check_stock_package_rows,
--              resolve_stock_item_lot, enforce_sku_policy, refuse_new_expired_stock,
--              refuse_opened_return_carton_on_sale, check_stock_item_returned_carton, check_allocation_whole_carton
--   rewritten  inventory.check_sku_reservation_covered, check_stock_reservation_sku,
--              check_stock_reservation_warehouse, check_stock_movement_manual_move, resolve_stock_movement_lot,
--              item_in_use, check_stock_package_transition; fulfillment.check_pick_line_carton;
--              procurement.check_return_receipt_carton; warehouse.check_putaway_task_package,
--              warehouse.check_staging_area; the CHECKs, triggers and indexes of stock_reservation that named
--              stock_item_id
-- No other view, function, CHECK or index of any schema mentions them.
--
-- What is still a rule, and where it is stated now (each also in the aggregate, StockPackage):
--   whole cartons                       nothing to state: there is no quantity
--   one live allocation per carton      uk_stock_reservation_live_carton
--   an allocation is one carton of its hold's SKU, order, warehouse and count
--                                       ck_stock_reservation_kind; tg_stock_reservation_sku, _warehouse, _carton
--   only free AVAILABLE stock is allocated, never out of a STAGING area
--                                       tg_stock_reservation_carton, which locks the carton before reading it
--   a held carton stays AVAILABLE       tg_stock_package_held_available (deferred: see below)
--   stock status exactly while in stock ck_stock_package_stock_status
--   a carton that has been in stock has a lot; lot and FIFO date frozen
--                                       ck_stock_package_lot; tg_stock_package_transition; tg_stock_package_lot
--   an opened return carton is never AVAILABLE          ck_stock_package_opened_return
--   a returned carton stays QUARANTINE while its return is PENDING or the customer's
--                                       ck_stock_package_returned_in_quarantine
--   a lot with a number, no new EXPIRED stock, no receipt date in the future  tg_stock_package_lot
--   SKU-level holds covered per count   check_sku_reservation_covered, over cartons
--   FIFO                                the lot and the receipt date never change, and a carton that comes back keeps
--                                       them. "Replenishment needs a new layer" and the same-receipt merge marker
--                                       (stockflow.same_receipt_merge) are gone: nothing can grow.

-- ---------------------------------------------------------------------------------------------- 1. nothing live is guessed

DO $$
DECLARE
    odd TEXT;
BEGIN
    SELECT string_agg(line, '; ') INTO odd
      FROM (SELECT p.lpn || ' (' || p.status || ')' AS line
              FROM inventory.stock_package p
             WHERE p.status NOT IN ('RECEIVING', 'VOID')
               AND NOT EXISTS (SELECT 1 FROM inventory.stock_item s WHERE s.stock_package_id = p.id)
             ORDER BY p.lpn
             LIMIT 10) x;
    IF odd IS NOT NULL THEN
        RAISE EXCEPTION 'stock_package: carton(s) that have been in stock have no stock row, so no lot: %; the carton '
            'becomes the stock (ADR-0011) - give each its row, or void it, before migrating', odd
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT string_agg(line, '; ') INTO odd
      FROM (SELECT r.id || ' of order ' || r.order_id || ' on '
                   || CASE WHEN p.id IS NULL THEN 'stock row ' || r.stock_item_id || ', which is in no carton'
                           WHEN p.status <> 'IN_STOCK' THEN 'carton ' || p.lpn || ', which is ' || p.status
                           WHEN s.status <> 'AVAILABLE' THEN 'carton ' || p.lpn || ', whose stock is ' || s.status
                           ELSE 'carton ' || p.lpn || ', which another live hold names too' END AS line
              FROM inventory.stock_reservation r
              JOIN inventory.stock_item s ON s.id = r.stock_item_id
              LEFT JOIN inventory.stock_package p ON p.id = s.stock_package_id
             WHERE r.status = 'HELD'
               AND (p.id IS NULL OR p.status <> 'IN_STOCK' OR s.status <> 'AVAILABLE'
                    OR EXISTS (SELECT 1 FROM inventory.stock_reservation o
                                WHERE o.stock_item_id = r.stock_item_id AND o.status = 'HELD' AND o.id <> r.id))
             ORDER BY r.reserved_at, r.id
             LIMIT 10) x;
    IF odd IS NOT NULL THEN
        RAISE EXCEPTION 'stock_reservation: live hold(s) are not the one allocation of an AVAILABLE carton in stock: %; '
            'a live allocation is what says a carton is taken (ADR-0011) - release these rows before migrating', odd
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT string_agg(line, '; ') INTO odd
      FROM (SELECT r.id || ' (' || r.status || ') on stock row ' || r.stock_item_id AS line
              FROM inventory.stock_reservation r
              JOIN inventory.stock_item s ON s.id = r.stock_item_id
             WHERE s.stock_package_id IS NULL AND r.status <> 'HELD'
               AND (EXISTS (SELECT 1 FROM fulfillment.pick_line x WHERE x.reservation_id = r.id)
                    OR EXISTS (SELECT 1 FROM ordering.order_line_reservation x WHERE x.reservation_id = r.id)
                    OR EXISTS (SELECT 1 FROM ordering.delivery_batch_hold x WHERE x.reservation_id = r.id)
                    OR EXISTS (SELECT 1 FROM ordering.delivery_failure_case_hold x WHERE x.reservation_id = r.id)
                    OR EXISTS (SELECT 1 FROM inventory.stock_adjustment x WHERE x.reservation_id = r.id)
                    OR EXISTS (SELECT 1 FROM inventory.stock_reservation x WHERE x.allocated_from_id = r.id))
             ORDER BY r.reserved_at, r.id
             LIMIT 10) x;
    IF odd IS NOT NULL THEN
        RAISE EXCEPTION 'stock_reservation: closed hold(s) on loose stock are still referenced: %; loose history is '
            'deleted with its stock rows (ADR-0011, QD-1) - delete what references these rows before migrating', odd
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------- 2. the carton takes its row's state

-- The commit-time check of a carton against its rows goes first: it reads the table being folded away.
DROP TRIGGER tg_stock_package_rows ON inventory.stock_package;

ALTER TABLE inventory.stock_package
    ADD COLUMN stock_status VARCHAR(32),
    ADD COLUMN lot_id UUID,
    ADD COLUMN received_at TIMESTAMPTZ;

UPDATE inventory.stock_package p
   SET stock_status = CASE WHEN p.status = 'IN_STOCK' THEN s.status END,
       lot_id = s.lot_id,
       received_at = s.received_at
  FROM inventory.stock_item s
 WHERE s.stock_package_id = p.id;

-- ---------------------------------------------------------------------------------------------- 3. an allocation names its carton

-- Loose history first (the header says which rows, and that nothing references them).
DELETE FROM inventory.stock_reservation r
 USING inventory.stock_item s
 WHERE s.id = r.stock_item_id AND s.stock_package_id IS NULL;

ALTER TABLE inventory.stock_reservation ADD COLUMN stock_package_id UUID;

-- The BEFORE triggers of the table check what a row names through stock_item_id; they are replaced below.
ALTER TABLE inventory.stock_reservation DISABLE TRIGGER USER;

UPDATE inventory.stock_reservation r
   SET stock_package_id = s.stock_package_id
  FROM inventory.stock_item s
 WHERE s.id = r.stock_item_id;

ALTER TABLE inventory.stock_reservation ENABLE TRIGGER USER;

-- The deletes above queued the deferred foreign keys that reference a reservation; ALTER TABLE refuses to run over
-- queued checks, so they are checked now.
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

DO $$
DECLARE
    lost BIGINT;
BEGIN
    SELECT count(*) INTO lost FROM inventory.stock_reservation
     WHERE stock_item_id IS NOT NULL AND stock_package_id IS NULL;
    IF lost > 0 THEN
        RAISE EXCEPTION 'stock_reservation: % row(s) on a stock row found no carton', lost
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------- 4. the old shape goes

DROP VIEW inventory.stock_package_balance;

DROP TRIGGER tg_stock_reservation_sku ON inventory.stock_reservation;
DROP TRIGGER tg_stock_reservation_warehouse ON inventory.stock_reservation;
DROP TRIGGER tg_stock_reservation_soft_atp_insert ON inventory.stock_reservation;
DROP TRIGGER tg_stock_reservation_soft_atp_update ON inventory.stock_reservation;
DROP TRIGGER tg_stock_reservation_whole_carton ON inventory.stock_reservation;
DROP INDEX inventory.ix_stock_reservation_sku_held;
DROP INDEX inventory.ix_stock_reservation_warehouse_held;

-- The foreign key first, by name: it is the ON DELETE CASCADE of the header's trap.
ALTER TABLE inventory.stock_reservation
    DROP CONSTRAINT fk_reservation_stock_item,
    DROP CONSTRAINT ck_stock_reservation_kind,
    DROP CONSTRAINT ck_stock_reservation_warehouse,
    DROP CONSTRAINT ck_stock_reservation_one_carton,
    DROP COLUMN stock_item_id;

-- Its triggers, CHECKs and six indexes go with it.
DROP TABLE inventory.stock_item;

DROP FUNCTION inventory.check_stock_item_package();
DROP FUNCTION inventory.check_stock_package_rows();
DROP FUNCTION inventory.check_stock_package_contents(uuid);
DROP FUNCTION inventory.resolve_stock_item_lot();
DROP FUNCTION inventory.enforce_sku_policy();
DROP FUNCTION inventory.refuse_new_expired_stock();
DROP FUNCTION inventory.refuse_opened_return_carton_on_sale();
DROP FUNCTION inventory.check_stock_item_returned_carton();
DROP FUNCTION inventory.check_allocation_whole_carton();

-- ---------------------------------------------------------------------------------------------- 5. the carton's own rules

ALTER TABLE inventory.stock_package
    ADD CONSTRAINT fk_stock_package_lot FOREIGN KEY (lot_id) REFERENCES inventory.stock_lot (id) ON DELETE RESTRICT,
    -- The values stock_item.status allowed; EXPIRED and IN_PRODUCTION are history (no new EXPIRED: tg_stock_package_lot).
    ADD CONSTRAINT ck_stock_package_stock_status_value CHECK (stock_status IN
        ('INBOUND', 'AVAILABLE', 'QUARANTINE', 'BLOCKED', 'DAMAGED', 'EXPIRED', 'IN_PRODUCTION')),
    -- Owner decision: a carton has a stock status exactly while it is in stock.
    ADD CONSTRAINT ck_stock_package_stock_status CHECK ((status = 'IN_STOCK') = (stock_status IS NOT NULL)),
    -- A carton that has been in stock has its lot (kltn-docs 20 BR-22: every unit carries a lot).
    ADD CONSTRAINT ck_stock_package_lot CHECK (status IN ('RECEIVING', 'VOID') OR lot_id IS NOT NULL),
    -- WF-05 TH-4: a carton that came back opened holds fewer units than its label; it is written off, never sold again.
    -- (Was tg_stock_item_opened_return and tg_stock_item_opened_return_insert.)
    ADD CONSTRAINT ck_stock_package_opened_return
        CHECK (return_opened_at IS NULL OR stock_status IS DISTINCT FROM 'AVAILABLE'),
    -- WF-05 Q-4: a carton back on a return whose refund is undecided, or that is the customer's, is not the company's
    -- stock: while it is in stock it is in quarantine. (Was tg_stock_item_returned_carton.)
    ADD CONSTRAINT ck_stock_package_returned_in_quarantine
        CHECK (stock_status IS NULL OR stock_status = 'QUARANTINE' OR return_ownership IS NULL
               OR return_ownership = 'COMPANY');

COMMENT ON TABLE inventory.stock_package IS
    'One physical carton with its own LPN, and the stock itself (ADR-0011, V20261230000600): while it is IN_STOCK it '
    'stands for nominal_qty units of its item in stock_status, of lot lot_id, received at received_at. It holds no '
    'quantity; whether it is taken is its live allocation in inventory.stock_reservation.';
COMMENT ON COLUMN inventory.stock_package.stock_status IS
    'INBOUND, AVAILABLE, QUARANTINE, ... - set exactly while status is IN_STOCK (ck_stock_package_stock_status). A '
    'carton that comes back into stock is given its status by the operation that brings it back.';
COMMENT ON COLUMN inventory.stock_package.lot_id IS
    'The carton''s lot: written when it goes into stock, required from then on (ck_stock_package_lot) and never '
    'changed (tg_stock_package_transition). Its lot number is inventory.stock_lot.lot_number.';
COMMENT ON COLUMN inventory.stock_package.received_at IS
    'The FIFO date: when the carton was received. NULL is "not known" and sorts last. Written when the carton goes '
    'into stock and never changed; a carton that comes back keeps it.';

-- What a release reads: the AVAILABLE cartons of an item (free ones are those no live allocation names).
CREATE INDEX ix_stock_package_available ON inventory.stock_package (inventory_item_id)
    WHERE stock_status = 'AVAILABLE';
CREATE INDEX ix_stock_package_lot ON inventory.stock_package (lot_id) WHERE lot_id IS NOT NULL;

-- The lot is the carton's SKU's and has a lot number; stock is never newly EXPIRED (20 BR-13); nothing is received in the future. (Was
-- resolve_stock_item_lot, refuse_new_expired_stock and the date check of enforce_sku_policy. The application names
-- the lot: there is no lot_number on the carton to resolve one from.)
CREATE FUNCTION inventory.check_stock_package_lot() RETURNS trigger AS $$
DECLARE
    lot_sku    VARCHAR(64);
    lot_number VARCHAR(64);
    item_sku   VARCHAR(64);
BEGIN
    IF NEW.lot_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.lot_id IS DISTINCT FROM OLD.lot_id) THEN
        SELECT l.sku, l.lot_number INTO lot_sku, lot_number FROM inventory.stock_lot l WHERE l.id = NEW.lot_id;
        SELECT sku INTO item_sku FROM inventory.inventory_items WHERE id = NEW.inventory_item_id;
        IF lot_sku IS DISTINCT FROM item_sku THEN
            RAISE EXCEPTION 'stock package % holds % but lot % is of %', NEW.lpn, item_sku, NEW.lot_id, lot_sku
                USING ERRCODE = 'check_violation';
        END IF;
        -- Every SKU is lot-tracked (01 BR-17): stock carries a lot number ('UNTRACED' when none is known). The
        -- stock row said so by a CHECK and its policy trigger; stock_lot.lot_number stays nullable for old lots.
        IF nullif(btrim(lot_number), '') IS NULL THEN
            RAISE EXCEPTION 'stock package % needs a lot number: lot % has none, and every SKU is lot-tracked',
                NEW.lpn, NEW.lot_id USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.stock_status = 'EXPIRED' AND (TG_OP = 'INSERT' OR OLD.stock_status IS DISTINCT FROM 'EXPIRED') THEN
        RAISE EXCEPTION 'stock package % cannot become EXPIRED: stock has no expiry date (20 BR-13)', NEW.lpn
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.received_at > clock_timestamp() + interval '1 minute'
       AND (TG_OP = 'INSERT' OR NEW.received_at IS DISTINCT FROM OLD.received_at) THEN
        RAISE EXCEPTION 'stock package %: invalid receipt date %', NEW.lpn, NEW.received_at
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_stock_package_lot
    BEFORE INSERT OR UPDATE OF lot_id, stock_status, received_at ON inventory.stock_package
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_lot();

-- The transitions as they were, the count as it was (V20261230000300), and now: the lot and the FIFO date are written
-- when the carton goes into stock and never after. A carton that comes back (CONSUMED or LOST -> IN_STOCK) keeps both,
-- so FIFO never loses the age of stock; an undated carton stays undated rather than be dated on the shelf.
CREATE OR REPLACE FUNCTION inventory.check_stock_package_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'RECEIVING' AND NEW.status IN ('IN_STOCK', 'VOID'))
        OR (OLD.status = 'IN_STOCK' AND NEW.status IN ('CONSUMED', 'LOST'))
        OR (OLD.status = 'CONSUMED' AND NEW.status = 'IN_STOCK')
        OR (OLD.status = 'LOST' AND NEW.status = 'IN_STOCK')) THEN
        RAISE EXCEPTION 'stock package % cannot go from % to %', NEW.lpn, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.counted_at IS DISTINCT FROM OLD.counted_at AND OLD.status <> 'RECEIVING' THEN
        RAISE EXCEPTION 'stock package % is %: a carton is counted in only while it is being received', NEW.lpn,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF (NEW.lot_id IS DISTINCT FROM OLD.lot_id OR NEW.received_at IS DISTINCT FROM OLD.received_at)
       AND OLD.status <> 'RECEIVING' THEN
        RAISE EXCEPTION 'stock package % is %: its lot and its receipt date are written when it goes into stock, and '
            'never changed (FIFO)', NEW.lpn, OLD.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER tg_stock_package_transition ON inventory.stock_package;
CREATE TRIGGER tg_stock_package_transition
    BEFORE UPDATE OF status, counted_at, lot_id, received_at ON inventory.stock_package
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_transition();

-- A held carton stays AVAILABLE: a carton with a live allocation is not quarantined, blocked, written off or picked
-- away from under it (was ck_stock_item_quarantine_unheld, and the on_hand/reserved CHECKs for the rest). Staging keeps
-- its held carton AVAILABLE, so it passes. Checked at commit, not on the statement: the application changes the carton
-- and closes its allocation in one flush, in an order it does not choose (a pick consumes the hold and the carton, a
-- count's shortage gives the hold up and writes the carton off), and the rule is about what is committed. The row is
-- read again, as it stands at commit. Concurrency: an allocation locks the carton row before it reads the status
-- (tg_stock_reservation_carton), and this fires for the transaction that changed the carton - whichever commits second
-- sees the other.
CREATE FUNCTION inventory.check_stock_package_held_available() RETURNS trigger AS $$
DECLARE
    now_row inventory.stock_package%ROWTYPE;
    held_by UUID;
BEGIN
    SELECT * INTO now_row FROM inventory.stock_package WHERE id = NEW.id;
    IF NOT FOUND OR now_row.stock_status IS NOT DISTINCT FROM 'AVAILABLE' THEN
        RETURN NULL;
    END IF;
    SELECT r.order_id INTO held_by FROM inventory.stock_reservation r
     WHERE r.stock_package_id = NEW.id AND r.status = 'HELD';
    IF FOUND THEN
        RAISE EXCEPTION 'stock package % is held for order % and cannot be %: release or reallocate its hold first',
            now_row.lpn, held_by, coalesce(now_row.stock_status, now_row.status) USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tg_stock_package_held_available
    AFTER UPDATE OF stock_status, status ON inventory.stock_package
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_held_available();

-- ---------------------------------------------------------------------------------------------- 6. the reservation's rules, over the carton

ALTER TABLE inventory.stock_reservation
    ADD CONSTRAINT fk_stock_reservation_package FOREIGN KEY (stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT,
    -- The two kinds of row. An allocation names its carton and is that one carton, never ALLOCATED (that is its SKU
    -- reservation's status); a SKU-level hold names no carton, was allocated from nothing, and is never CONSUMED.
    ADD CONSTRAINT ck_stock_reservation_kind CHECK (
           (stock_package_id IS NOT NULL AND status <> 'ALLOCATED' AND cartons = 1)
        OR (stock_package_id IS NULL AND allocated_from_id IS NULL AND status <> 'CONSUMED')),
    -- 20 BR-17: only a SKU-level hold is held at a warehouse; an allocation is where its carton is.
    ADD CONSTRAINT ck_stock_reservation_warehouse CHECK (warehouse_code IS NULL OR stock_package_id IS NULL);

-- "Is this carton taken": at most one live allocation names it. The third layer against a double allocation, after
-- the SKU's advisory lock every allocator takes and the carton's row lock.
CREATE UNIQUE INDEX uk_stock_reservation_live_carton ON inventory.stock_reservation (stock_package_id)
    WHERE stock_package_id IS NOT NULL AND status = 'HELD';
-- The carton loads its reservations, and the foreign key is checked, by this.
CREATE INDEX ix_stock_reservation_package ON inventory.stock_reservation (stock_package_id)
    WHERE stock_package_id IS NOT NULL;
CREATE INDEX ix_stock_reservation_sku_held ON inventory.stock_reservation (sku)
    WHERE stock_package_id IS NULL AND status = 'HELD';
CREATE INDEX ix_stock_reservation_warehouse_held ON inventory.stock_reservation (sku, warehouse_code)
    WHERE stock_package_id IS NULL AND status = 'HELD' AND warehouse_code IS NOT NULL;

COMMENT ON COLUMN inventory.stock_reservation.stock_package_id IS
    'The carton an allocation took; NULL for a SKU-level hold. While the row is HELD the carton is taken '
    '(uk_stock_reservation_live_carton): this is the only record of it.';
COMMENT ON COLUMN inventory.stock_reservation.cartons IS
    'How many cartons the row is: any number for a SKU-level hold, always 1 for an allocation '
    '(ck_stock_reservation_kind). Its units are cartons * units_per_carton (ADR-0011).';
COMMENT ON COLUMN inventory.stock_reservation.units_per_carton IS
    'The count of the cartons the row is about. A SKU-level hold wants cartons of this count (from '
    'ordering.order_line.units_per_carton) and is covered and served only by cartons whose '
    'inventory.stock_package.nominal_qty equals it; an allocation carries its carton''s nominal_qty '
    '(tg_stock_reservation_carton).';
COMMENT ON TABLE inventory.stock_reservation IS
    'What orders hold of stock, in cartons (ADR-0011). A SKU-level hold (stock_package_id NULL) is "cartons" cartons '
    'of "units_per_carton" of a SKU at a warehouse, on no carton yet; an allocation (stock_package_id and '
    'allocated_from_id set) is one carton given to the order at release, and while it is HELD that carton is taken. '
    'Units, where somebody needs them, are cartons * units_per_carton. No hold carries a deadline.';

-- The SKU a row holds is its carton's item's; an allocation holds the SKU of the reservation it was made from, for
-- the same order. As it was, read from the carton instead of the stock row.
CREATE OR REPLACE FUNCTION inventory.check_stock_reservation_sku() RETURNS trigger AS $$
DECLARE
    item_sku   VARCHAR(64);
    parent     inventory.stock_reservation%ROWTYPE;
BEGIN
    IF NEW.stock_package_id IS NOT NULL THEN
        SELECT i.sku INTO item_sku
          FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
         WHERE p.id = NEW.stock_package_id;
        IF NEW.sku IS NULL THEN
            NEW.sku := item_sku;
        ELSIF NEW.sku IS DISTINCT FROM item_sku THEN
            RAISE EXCEPTION 'reservation % holds % but stock package % is %', NEW.id, NEW.sku, NEW.stock_package_id,
                item_sku USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.allocated_from_id IS NOT NULL THEN
        SELECT * INTO parent FROM inventory.stock_reservation WHERE id = NEW.allocated_from_id;
        IF parent.sku IS DISTINCT FROM NEW.sku OR parent.order_id IS DISTINCT FROM NEW.order_id THEN
            RAISE EXCEPTION 'allocation % must hold the SKU of reservation % for the same order', NEW.id,
                NEW.allocated_from_id USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_stock_reservation_sku
    BEFORE INSERT OR UPDATE OF stock_package_id, sku, allocated_from_id ON inventory.stock_reservation
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_reservation_sku();

-- 20 BR-17: a hold stays at its warehouse, and an allocation takes a carton of the warehouse its reservation is held at.
CREATE OR REPLACE FUNCTION inventory.check_stock_reservation_warehouse() RETURNS trigger AS $$
DECLARE
    parent_warehouse VARCHAR;
    carton_location  VARCHAR;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.warehouse_code IS DISTINCT FROM OLD.warehouse_code THEN
        RAISE EXCEPTION 'reservation %: the warehouse it is held at does not change (20 BR-17)', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.allocated_from_id IS NOT NULL AND NEW.stock_package_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR NEW.stock_package_id IS DISTINCT FROM OLD.stock_package_id
            OR NEW.allocated_from_id IS DISTINCT FROM OLD.allocated_from_id) THEN
        SELECT r.warehouse_code INTO parent_warehouse FROM inventory.stock_reservation r
         WHERE r.id = NEW.allocated_from_id;
        IF parent_warehouse IS NOT NULL THEN
            SELECT p.location_code INTO carton_location FROM inventory.stock_package p
             WHERE p.id = NEW.stock_package_id;
            IF split_part(carton_location, '-', 1) <> parent_warehouse THEN
                RAISE EXCEPTION 'allocation %: reservation % is held at %, the stock is at % (20 BR-17)', NEW.id,
                    NEW.allocated_from_id, parent_warehouse, carton_location USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_stock_reservation_warehouse
    BEFORE INSERT OR UPDATE OF warehouse_code, allocated_from_id, stock_package_id ON inventory.stock_reservation
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_reservation_warehouse();

-- An allocation takes a free AVAILABLE carton in stock, of the count it carries, never one standing in a STAGING area
-- (kltn-docs 07 BR-03, 20 BR-20). The carton's row is locked BEFORE its status is read: without the lock a quarantine
-- and an allocation of the same carton, each reading what the other has not committed yet, would both pass under READ
-- COMMITTED. That the carton has no other live allocation is uk_stock_reservation_live_carton. (Replaces
-- check_allocation_whole_carton, which compared the allocation with its row's on_hand.)
CREATE FUNCTION inventory.check_stock_reservation_carton() RETURNS trigger AS $$
DECLARE
    carton inventory.stock_package%ROWTYPE;
BEGIN
    PERFORM 1 FROM inventory.stock_package WHERE id = NEW.stock_package_id FOR NO KEY UPDATE;
    SELECT * INTO carton FROM inventory.stock_package WHERE id = NEW.stock_package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- fk_stock_reservation_package reports it
    END IF;
    IF carton.stock_status IS DISTINCT FROM 'AVAILABLE' THEN
        RAISE EXCEPTION 'stock package % is %; only AVAILABLE stock is allocated', carton.lpn,
            coalesce(carton.stock_status, carton.status) USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.units_per_carton <> carton.nominal_qty THEN
        RAISE EXCEPTION 'allocation % is of a carton of %, but carton % it is on holds %; an allocation is its carton',
            NEW.id, NEW.units_per_carton, carton.lpn, carton.nominal_qty USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM warehouse.area a JOIN warehouse.storage_location l ON l.id = a.location_id
                WHERE a.type = 'STAGING' AND l.location_code = carton.location_code) THEN
        RAISE EXCEPTION 'stock package % stands in staging area %; stock there is an order''s already, never '
            'allocated (20 BR-20)', carton.lpn, carton.location_code USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- When the row becomes a live allocation of a carton: made HELD on it, or (never by the application) made HELD again
-- or moved to another carton. Not on every write of a HELD row: a staged carton keeps its allocation while it stands
-- in the staging area.
CREATE TRIGGER tg_stock_reservation_carton_insert
    BEFORE INSERT ON inventory.stock_reservation
    FOR EACH ROW
    WHEN (NEW.stock_package_id IS NOT NULL AND NEW.status = 'HELD')
    EXECUTE FUNCTION inventory.check_stock_reservation_carton();

CREATE TRIGGER tg_stock_reservation_carton_update
    BEFORE UPDATE OF status, stock_package_id, units_per_carton ON inventory.stock_reservation
    FOR EACH ROW
    WHEN (NEW.stock_package_id IS NOT NULL AND NEW.status = 'HELD'
          AND (OLD.status <> 'HELD' OR NEW.stock_package_id IS DISTINCT FROM OLD.stock_package_id
               OR NEW.units_per_carton <> OLD.units_per_carton))
    EXECUTE FUNCTION inventory.check_stock_reservation_carton();

-- SKU-level holds are covered by free cartons OF THEIR COUNT, over the whole system and at the hold's warehouse (20
-- BR-17), as V20261230000500 wrote it; a free carton is now an AVAILABLE carton no live allocation names (it was an
-- AVAILABLE stock row with on_hand > 0 and reserved = 0). Everything else is as it was: the SKU's advisory lock (the
-- one every hold and allocation of the SKU takes, InventoryPolicyRepository.lock), every AVAILABLE carton wherever it
-- stands, the warehouse read from the first part of the location code.
CREATE OR REPLACE FUNCTION inventory.check_sku_reservation_covered() RETURNS trigger AS $$
DECLARE
    free BIGINT;
    held BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.sku, 701));
    SELECT count(*) INTO free
      FROM inventory.stock_package p
      JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = NEW.sku AND p.stock_status = 'AVAILABLE' AND p.nominal_qty = NEW.units_per_carton
       AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation a
                        WHERE a.stock_package_id = p.id AND a.status = 'HELD');
    SELECT COALESCE(SUM(cartons), 0) INTO held
      FROM inventory.stock_reservation
     WHERE sku = NEW.sku AND stock_package_id IS NULL AND status = 'HELD' AND units_per_carton = NEW.units_per_carton;
    IF held > free THEN
        RAISE EXCEPTION 'SKU reservations of % (% carton(s) of %) exceed its free available cartons of that count (%)',
            NEW.sku, held, NEW.units_per_carton, free USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.warehouse_code IS NOT NULL THEN
        SELECT count(*) INTO free
          FROM inventory.stock_package p
          JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
         WHERE i.sku = NEW.sku AND p.stock_status = 'AVAILABLE' AND p.nominal_qty = NEW.units_per_carton
           AND split_part(p.location_code, '-', 1) = NEW.warehouse_code
           AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation a
                            WHERE a.stock_package_id = p.id AND a.status = 'HELD');
        SELECT COALESCE(SUM(cartons), 0) INTO held
          FROM inventory.stock_reservation
         WHERE sku = NEW.sku AND stock_package_id IS NULL AND status = 'HELD'
           AND units_per_carton = NEW.units_per_carton AND warehouse_code = NEW.warehouse_code;
        IF held > free THEN
            RAISE EXCEPTION 'SKU reservations of % at % (% carton(s) of %) exceed its free available cartons of that '
                'count there (%) (20 BR-17)', NEW.sku, NEW.warehouse_code, held, NEW.units_per_carton, free
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_stock_reservation_soft_atp_insert
    AFTER INSERT ON inventory.stock_reservation
    FOR EACH ROW
    WHEN (NEW.stock_package_id IS NULL AND NEW.status = 'HELD')
    EXECUTE FUNCTION inventory.check_sku_reservation_covered();

CREATE TRIGGER tg_stock_reservation_soft_atp_update
    AFTER UPDATE ON inventory.stock_reservation
    FOR EACH ROW
    WHEN (NEW.stock_package_id IS NULL AND NEW.status = 'HELD'
          AND (OLD.status <> 'HELD' OR NEW.cartons > OLD.cartons OR NEW.units_per_carton <> OLD.units_per_carton
               OR NEW.sku <> OLD.sku))
    EXECUTE FUNCTION inventory.check_sku_reservation_covered();

-- ---------------------------------------------------------------------------------------------- 7. the other readers

-- A carton held for an order is not moved by hand (kltn-docs 11 BR-05): "held" was stock_item.reserved > 0.
CREATE OR REPLACE FUNCTION inventory.check_stock_movement_manual_move() RETURNS trigger AS $$
DECLARE
    held_lpn VARCHAR(30);
BEGIN
    SELECT p.lpn INTO held_lpn
      FROM inventory.stock_package p
     WHERE p.id = NEW.stock_package_id
       AND EXISTS (SELECT 1 FROM inventory.stock_reservation r
                    WHERE r.stock_package_id = p.id AND r.status = 'HELD');
    IF FOUND THEN
        RAISE EXCEPTION 'stock package % is held for an order; it is not moved by hand', held_lpn
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- A ledger line that names no lot takes its carton's, when the carton's lot carries the line's lot number (it was
-- looked up among the stock rows of the line's two places); one that names a lot names a lot of its SKU and number.
CREATE OR REPLACE FUNCTION inventory.resolve_stock_movement_lot() RETURNS trigger AS $$
BEGIN
    IF NEW.lot_id IS NULL THEN
        SELECT p.lot_id INTO NEW.lot_id
          FROM inventory.stock_package p JOIN inventory.stock_lot l ON l.id = p.lot_id
         WHERE p.id = NEW.stock_package_id AND l.sku = NEW.sku
           AND l.lot_number IS NOT DISTINCT FROM NEW.lot_number;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM inventory.stock_lot l WHERE l.id = NEW.lot_id AND l.sku = NEW.sku
                         AND l.lot_number IS NOT DISTINCT FROM NEW.lot_number) THEN
            RAISE EXCEPTION 'ledger line % moves % lot % but names lot %', NEW.id, NEW.sku, NEW.lot_number,
                NEW.lot_id USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- An item with a carton is in use: the line that asked stock_item goes, stock_package already answers.
CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id uuid, p_sku character varying) RETURNS boolean
    LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_package WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM ordering.production_order_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM catalog.customer_price WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE blank_sku = p_sku OR sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_reservation WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku = p_sku)
$$;

-- A pick line names the carton of its hold (07 BR-10): the hold names its carton directly now.
CREATE OR REPLACE FUNCTION fulfillment.check_pick_line_carton() RETURNS trigger AS $$
DECLARE
    expected VARCHAR(30);
BEGIN
    SELECT p.lpn INTO expected
      FROM inventory.stock_reservation r
      LEFT JOIN inventory.stock_package p ON p.id = r.stock_package_id
     WHERE r.id = NEW.reservation_id;
    IF NEW.lpn IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'pick line % must name carton %, the carton of its hold; it names %',
            NEW.id, coalesce(expected, '(none: a hold on no carton)'), coalesce(NEW.lpn, '(none)')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- A quarantine release puts away a carton in stock that is QUARANTINE: read from the carton, not from its rows.
CREATE OR REPLACE FUNCTION warehouse.check_putaway_task_package() RETURNS trigger AS $$
DECLARE
    pkg_status   VARCHAR(16);
    pkg_stock    VARCHAR(32);
    newly_set    BOOLEAN;
BEGIN
    SELECT p.status, p.stock_status INTO pkg_status, pkg_stock
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
    IF newly_set AND NEW.source = 'QUARANTINE_RELEASE' AND pkg_stock IS DISTINCT FROM 'QUARANTINE' THEN
        RAISE EXCEPTION 'a quarantine release puts away a carton in stock whose units are all QUARANTINE'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- An area does not turn into, or out of, a staging area while it holds stock: cartons in stock standing there.
CREATE OR REPLACE FUNCTION warehouse.check_staging_area() RETURNS trigger AS $$
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
       AND EXISTS (SELECT 1 FROM inventory.stock_package p
                    WHERE p.location_code = loc.location_code AND p.status = 'IN_STOCK') THEN
        RAISE EXCEPTION 'area % (%) holds stock; it does not turn % a staging area while it does', NEW.code,
            loc.location_code, CASE WHEN NEW.type = 'STAGING' THEN 'into' ELSE 'out of' END
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- A carton on a return receipt is named as it was shipped (03 BR-03): its lot is the carton's lot's number now, where
-- it was its stock row's. Nothing else of the function changes.
CREATE OR REPLACE FUNCTION procurement.check_return_receipt_carton() RETURNS trigger AS $$
DECLARE
    receipt          procurement.goods_receipts%ROWTYPE;
    pkg              RECORD;
    request_shipment UUID;
    approved         BIGINT;
BEGIN
    SELECT * INTO receipt FROM procurement.goods_receipts WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt.receipt_type IS DISTINCT FROM 'RETURN' THEN
        RAISE EXCEPTION 'cartons are scanned on a RETURN receipt only' USING ERRCODE = 'check_violation';
    END IF;
    IF receipt.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'return receipt % is %: its cartons are kept as they were confirmed', receipt.receipt_number,
            receipt.status USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    -- A writer that says nothing of the count took the carton whole: what it was before counted_qty existed. NOT NULL
    -- is checked after this trigger, so it still refuses an explicit NULL on an update.
    IF TG_OP = 'INSERT' AND NEW.counted_qty IS NULL THEN
        NEW.counted_qty := NEW.quantity;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.counted_by IS NULL THEN
        NEW.counted_by := NEW.scanned_by;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.stock_entered IS NULL THEN
        NEW.stock_entered := NEW.counted_qty = NEW.quantity;
    END IF;
    -- On a draft, whether the carton will enter stock follows its count: a recount (by the service or straight in the
    -- database) moves both together, so ck_goods_receipt_return_cartons_stock_entered never sees them disagree.
    IF TG_OP = 'UPDATE' THEN
        NEW.stock_entered := NEW.counted_qty = NEW.quantity;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.receipt_id IS DISTINCT FROM OLD.receipt_id
        OR NEW.stock_package_id IS DISTINCT FROM OLD.stock_package_id OR NEW.lpn IS DISTINCT FROM OLD.lpn
        OR NEW.sku IS DISTINCT FROM OLD.sku OR NEW.lot_number IS DISTINCT FROM OLD.lot_number
        OR NEW.quantity IS DISTINCT FROM OLD.quantity OR NEW.scanned_by IS DISTINCT FROM OLD.scanned_by) THEN
        RAISE EXCEPTION 'return receipt %: only the condition and the count inside a scanned carton are changed',
            receipt.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.lpn, i.sku, p.nominal_qty, p.counted_at, p.return_opened_at, p.return_ownership, l.lot_number,
           p.lot_id
      INTO pkg
      FROM inventory.stock_package p
      JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
      LEFT JOIN inventory.stock_lot l ON l.id = p.lot_id
     WHERE p.id = NEW.stock_package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- fk_goods_receipt_return_cartons_package reports it
    END IF;
    IF pkg.lpn <> NEW.lpn OR pkg.sku <> NEW.sku OR pkg.counted_at IS NULL OR pkg.nominal_qty IS DISTINCT FROM NEW.quantity
       OR pkg.lot_id IS NULL OR pkg.lot_number IS DISTINCT FROM NEW.lot_number THEN
        RAISE EXCEPTION 'return receipt %: carton % is named as it was shipped - its LPN, SKU, count and lot (BR-03)',
            receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND pkg.return_opened_at IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt %: carton % came back opened on an earlier return receipt (WF-05 Q-18)',
            receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND pkg.return_ownership IN ('CUSTOMER_HELD', 'CUSTOMER_DESTROYED') THEN
        RAISE EXCEPTION 'return receipt %: carton % is the customer''s, held for them or destroyed on their behalf '
            '(WF-05 Q-4)', receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line l JOIN fulfillment.pick k ON k.id = l.pick_id
                    WHERE k.order_id = receipt.customer_order_id AND l.lpn = NEW.lpn
                      AND l.picked_qty > l.put_back_qty) THEN
        RAISE EXCEPTION 'return receipt %: carton % was not shipped on its order (BR-14)', receipt.receipt_number,
            NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF receipt.return_request_id IS NOT NULL THEN
        IF TG_OP = 'INSERT' THEN
            -- WF-05 TH-1: a return against a delivery takes back that delivery's cartons only.
            SELECT r.shipment_id INTO request_shipment FROM ordering.return_request r
             WHERE r.id = receipt.return_request_id;
            IF request_shipment IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM fulfillment.package p JOIN fulfillment.pick_line l ON l.id = p.pick_line_id
                     WHERE p.shipment_id = request_shipment AND p.status = 'LABELED' AND l.lpn = NEW.lpn) THEN
                RAISE EXCEPTION 'return receipt %: carton % was not delivered on the shipment the return is against',
                    receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
            END IF;
        END IF;
        -- WF-05 Q-11 (BE-22, M3, L6): only goods the return approved, and never above it - on the scan and on a
        -- recount alike. Units beyond are the customer's, kept in custody on the return request.
        IF TG_OP = 'INSERT' OR NEW.counted_qty > OLD.counted_qty THEN
            approved := procurement.return_units_approved(receipt.return_request_id, NEW.sku);
            IF approved = 0 OR procurement.return_units_received(receipt.return_request_id, NEW.sku, NEW.id, NULL)
                               + NEW.counted_qty > approved THEN
                RAISE EXCEPTION 'return receipt %: carton % holds % the return did not approve, or more of it than is '
                    'still to come back: it is kept in custody on the return request (WF-05 Q-11)',
                    receipt.receipt_number, NEW.lpn, NEW.sku USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;
