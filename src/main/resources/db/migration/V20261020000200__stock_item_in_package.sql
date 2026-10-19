-- =============================================================================
-- Stock in a stock package: a stock row may sit in a carton (inventory.stock_package, V20261020000100).
-- Module: inventory   Issue: GitHub #125, Jira SCRUM-473
--
-- stock_package_id NULL is loose stock, as every row was before. A stock package's contents are its
-- rows: usually one, more for serials or stock of another receipt time put back into it. What is left
-- in it is the sum of their on_hand.
--
-- The stock package is part of the stock row's identity, so two of them with the same SKU and lot on
-- one bin stay two rows, and moving one is an UPDATE of its rows' location_code: the rows keep their
-- id, and the reservations that point at them (stock_reservation.stock_item_id) stay valid.
--
-- What must hold between a stock package and its rows spans two tables and is only true at the end of
-- a transaction (a move updates the stock package and its rows one statement at a time), so it is
-- checked by DEFERRABLE INITIALLY DEFERRED constraint triggers, at commit:
--
--   1. every row has the stock package's SKU and sits where the stock package is;
--   2. all its rows are of one lot (a supplier carton is one SKU, one lot);
--   3. one RECEIVING, VOID, CONSUMED or LOST holds nothing; one IN_STOCK holds something;
--   4. an unopened one in stock holds exactly what was counted into it.
--
-- A violation surfaces at commit, as a 500: the application checks first and answers with an error
-- code; these are what a missed path or a direct UPDATE cannot get past.
--
-- The checks read rows without locking them. That is safe only because every writer takes the lock
-- order of V20261020000100: the SKU advisory lock, the stock package row FOR UPDATE, then its stock
-- rows in id order. Two transactions changing rows of one stock package are then serialised on it;
-- without that lock, each commit-time check would see the other's rows as they were, and both could
-- pass (write skew).
--
-- Before any code writes stock_package_id, every lookup that finds a stock layer by SKU, location,
-- lot and receipt time (StockOperationsServiceImpl.findLayer / findOne, picking, transfers,
-- adjustments, counts) must match the stock package too: the key now allows a loose row and rows
-- of several stock packages with otherwise equal values on one bin.
-- =============================================================================


ALTER TABLE inventory.stock_item
    ADD COLUMN stock_package_id UUID,
    ADD CONSTRAINT fk_stock_item_package FOREIGN KEY (stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT;

-- The natural key (V20260930001000), now per stock package. NULLS NOT DISTINCT: loose stock (NULL) is
-- still one row per SKU, location, lot, serial and receipt time.
DROP INDEX inventory.uk_stock_item_sku_location_lot;
CREATE UNIQUE INDEX uk_stock_item_sku_location_lot
    ON inventory.stock_item (sku, location_code, COALESCE(lot_number, ''), COALESCE(serial_number, ''),
                             received_at, stock_package_id)
    NULLS NOT DISTINCT;

CREATE INDEX ix_stock_item_package ON inventory.stock_item (stock_package_id)
    WHERE stock_package_id IS NOT NULL;

COMMENT ON COLUMN inventory.stock_item.stock_package_id IS
    'The stock package this stock is in; NULL for loose stock.';


CREATE FUNCTION inventory.check_stock_package_contents(package_id UUID)
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
           count(*) FILTER (WHERE s.location_code <> pkg.location_code),
           count(DISTINCT COALESCE(s.lot_number, '')),
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
END
$$;


CREATE FUNCTION inventory.check_stock_item_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.stock_package_id IS NOT NULL THEN
        PERFORM inventory.check_stock_package_contents(NEW.stock_package_id);
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.stock_package_id IS NOT NULL
       AND (TG_OP = 'DELETE' OR OLD.stock_package_id IS DISTINCT FROM NEW.stock_package_id) THEN
        PERFORM inventory.check_stock_package_contents(OLD.stock_package_id);
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_stock_item_package_insert
    AFTER INSERT ON inventory.stock_item
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_item_package();
CREATE CONSTRAINT TRIGGER tg_stock_item_package_update
    AFTER UPDATE OF stock_package_id, sku, location_code, lot_number, on_hand ON inventory.stock_item
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.stock_package_id IS NOT NULL OR OLD.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_item_package();
CREATE CONSTRAINT TRIGGER tg_stock_item_package_delete
    AFTER DELETE ON inventory.stock_item
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_item_package();


CREATE FUNCTION inventory.check_stock_package_rows()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM inventory.check_stock_package_contents(NEW.id);
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_stock_package_rows
    AFTER INSERT OR UPDATE OF location_code, status, unopened, received_qty ON inventory.stock_package
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_rows();

COMMENT ON FUNCTION inventory.check_stock_package_contents(UUID) IS
    'The four rules between a stock package and its stock rows; run at commit by the constraint triggers.';
