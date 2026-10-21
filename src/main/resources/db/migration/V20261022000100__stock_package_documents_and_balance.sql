-- =============================================================================
-- Stock adjustments and count lines name the carton they are about; a carton never holds more than was
-- counted into it; one row per carton to read.
-- Module: inventory   Issue: GitHub #142 (SCRUM-473, schema of #125)
--
-- 1. inventory.stock_adjustment and inventory.cycle_count_line named their stock by (SKU, location, lot) -
--    an adjustment also by receipt time (V20261017000420, for the same kind of ambiguity). A row in a carton is
--    keyed by the carton (V20261020000200), so one bin may hold a loose layer and several cartons of the very
--    same receipt, and those columns match all of them: a request is refused as ambiguous or lands on any one,
--    and its approval, which looks the stock up again, may land on another. stock_package_id names the carton;
--    NULL is loose stock, as before. Checked when it is set, as a putaway task's carton is (V20261020000500): of
--    the document's SKU, at its location, and a carton that can hold stock (IN_STOCK, or LOST for a count or a
--    FOUND adjustment that finds it again). Not re-checked on every later update: a pending adjustment whose
--    carton was emptied meanwhile is still the adjustment that was asked, and its approval refuses it.
--
--    A count has one line per carton: #125 decided that an unopened carton is confirmed by scanning it, its
--    count at receipt trusted. uk_cycle_count_line_slot kept one line per (count, bin, SKU, lot); it now does so
--    for loose lines only, and a carton is counted once per count.
--
-- 2. check_stock_package_contents (V20261020000200) gains rule 5: an IN_STOCK carton holds no more than was
--    counted into it. The unopened rule already pins an unopened one to exactly that; an opened one could be
--    filled past it (a put-back, a FOUND adjustment), beyond what the box can hold. Units found above it are
--    loose stock. The body is V20261020000200's, unchanged, with rule 5 added.
--
-- 3. inventory.stock_package_balance: one row per carton with what its rows hold - the lot, on hand, reserved -
--    for the LPN lookup, the stock-by-bin screen and reports. A view, not columns: a carton holds no quantity
--    of its own (V20261020000100) and a copy would drift.
--
-- 4. The comment on inventory.stock_item says what a row is now.
-- =============================================================================


-- 1. Documents name the carton --------------------------------------------------------------------------
ALTER TABLE inventory.stock_adjustment
    ADD COLUMN stock_package_id UUID,
    ADD CONSTRAINT fk_stock_adjustment_package FOREIGN KEY (stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT;
CREATE INDEX ix_stock_adjustment_package ON inventory.stock_adjustment (stock_package_id)
    WHERE stock_package_id IS NOT NULL;
COMMENT ON COLUMN inventory.stock_adjustment.stock_package_id IS
    'The carton whose contents are adjusted; NULL for loose stock.';

ALTER TABLE inventory.cycle_count_line
    ADD COLUMN stock_package_id UUID,
    ADD CONSTRAINT fk_cycle_count_line_package FOREIGN KEY (stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT;
COMMENT ON COLUMN inventory.cycle_count_line.stock_package_id IS
    'The carton this line counts; NULL for a loose layer.';

DROP INDEX inventory.uk_cycle_count_line_slot;
CREATE UNIQUE INDEX uk_cycle_count_line_slot ON inventory.cycle_count_line
    (cycle_count_id, location_code, sku, COALESCE(lot_number, ''))
    WHERE stock_package_id IS NULL;
CREATE UNIQUE INDEX uk_cycle_count_line_package ON inventory.cycle_count_line (cycle_count_id, stock_package_id)
    WHERE stock_package_id IS NOT NULL;


-- The carton a document names is of its SKU, at its location, and can hold stock. Shared by both tables, which
-- have sku and location_code. The carton's row is read without a lock: what is checked (its item, its
-- location, its status) changes only under the lock order of V20261020000100, which a document's writer takes
-- before it writes a document about a carton.
CREATE FUNCTION inventory.check_document_stock_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    pkg_sku      VARCHAR(64);
    pkg_location VARCHAR(64);
    pkg_status   VARCHAR(16);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.stock_package_id IS NOT DISTINCT FROM OLD.stock_package_id
       AND NEW.sku IS NOT DISTINCT FROM OLD.sku AND NEW.location_code IS NOT DISTINCT FROM OLD.location_code THEN
        RETURN NEW;
    END IF;
    SELECT i.sku, p.location_code, p.status INTO pkg_sku, pkg_location, pkg_status
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE p.id = NEW.stock_package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    IF pkg_sku <> NEW.sku THEN
        RAISE EXCEPTION '% names a carton of %, not %', TG_TABLE_NAME, pkg_sku, NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF pkg_location <> NEW.location_code THEN
        RAISE EXCEPTION '% at % names a carton at %', TG_TABLE_NAME, NEW.location_code, pkg_location
            USING ERRCODE = 'check_violation';
    END IF;
    IF pkg_status NOT IN ('IN_STOCK', 'LOST') THEN
        RAISE EXCEPTION '% names a carton that is %', TG_TABLE_NAME, pkg_status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_adjustment_package
    BEFORE INSERT OR UPDATE OF stock_package_id, sku, location_code ON inventory.stock_adjustment
    FOR EACH ROW WHEN (NEW.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_document_stock_package();
CREATE TRIGGER tg_cycle_count_line_package
    BEFORE INSERT OR UPDATE OF stock_package_id, sku, location_code ON inventory.cycle_count_line
    FOR EACH ROW WHEN (NEW.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_document_stock_package();


-- 2. A carton holds no more than was counted into it -------------------------------------------------------
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
           count(DISTINCT COALESCE(s.lot_number, '')) FILTER (WHERE s.on_hand > 0),
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
    IF pkg.status = 'IN_STOCK' AND held = 0 THEN
        RAISE EXCEPTION 'stock package % is in stock but empty; an emptied one is CONSUMED', pkg.lpn
            USING ERRCODE = 'check_violation';
    END IF;
    IF pkg.status <> 'IN_STOCK' AND held > 0 THEN
        RAISE EXCEPTION 'stock package % is % but holds %', pkg.lpn, pkg.status, held
            USING ERRCODE = 'check_violation';
    END IF;
    IF pkg.status = 'IN_STOCK' AND pkg.unopened AND held <> pkg.received_qty THEN
        RAISE EXCEPTION 'stock package % is unopened with % counted in, but holds %; a changed one is opened',
            pkg.lpn, pkg.received_qty, held USING ERRCODE = 'check_violation';
    END IF;
    -- Rule 5 (V20261022000100): never more than was counted into it; units above that are loose stock.
    IF pkg.status = 'IN_STOCK' AND held > pkg.received_qty THEN
        RAISE EXCEPTION 'stock package % was counted in at % and cannot hold %; the rest is loose stock',
            pkg.lpn, pkg.received_qty, held USING ERRCODE = 'check_violation';
    END IF;
END
$$;

COMMENT ON FUNCTION inventory.check_stock_package_contents(UUID) IS
    'The five rules between a stock package and its stock rows; run at commit by the constraint triggers.';


-- 3. One row per carton to read -----------------------------------------------------------------------
-- The lot and expiry are those of its rows holding stock (one lot at most, rule 2); a carton holding nothing
-- shows none. on_hand and reserved are summed over its rows; available is what an order could still take.
CREATE VIEW inventory.stock_package_balance AS
SELECT p.id                                                        AS stock_package_id,
       p.lpn,
       i.sku,
       p.inventory_item_id,
       p.uom_conversion_id,
       u.unit_of_measure,
       p.nominal_qty,
       p.received_qty,
       p.location_code,
       p.status,
       p.unopened,
       max(s.lot_number) FILTER (WHERE s.on_hand > 0)              AS lot_number,
       max(s.expiry_date) FILTER (WHERE s.on_hand > 0)             AS expiry_date,
       COALESCE(sum(s.on_hand), 0)                                 AS on_hand,
       COALESCE(sum(s.reserved), 0)                                AS reserved,
       COALESCE(sum(s.on_hand - s.reserved) FILTER (WHERE s.status = 'AVAILABLE'), 0) AS available,
       p.goods_receipt_line_id,
       p.closed_at
  FROM inventory.stock_package p
  JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
  JOIN inventory.uom_conversions u ON u.id = p.uom_conversion_id
  LEFT JOIN inventory.stock_item s ON s.stock_package_id = p.id
 GROUP BY p.id, i.sku, u.unit_of_measure;

COMMENT ON VIEW inventory.stock_package_balance IS
    'One row per carton: its label, unit, place and status, and what its stock rows hold. Read-only.';


-- 4. What a stock row is ----------------------------------------------------------------------------------
COMMENT ON TABLE inventory.stock_item IS
    'Stock row: one quantity of a SKU at one location, of one lot and receipt time, in one status - loose '
    '(stock_package_id NULL) or the contents of one carton. on_hand and reserved are counted in the base unit.';
