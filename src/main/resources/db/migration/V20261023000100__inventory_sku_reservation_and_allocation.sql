-- =============================================================================
-- SCRUM-152: reservation and allocation are two steps again (docs 01 "Reserved" / "Allocated",
-- 03 §6, 04c F-FUL step 02, BR-STK-006).
--
-- Until now a checkout placed its hold on stock items: it chose a bin and a lot by FEFO at checkout,
-- before anybody knew which warehouse would ship the order. From this version:
--
--   * checkout writes a SKU reservation: a stock_reservation row with stock_item_id NULL, holding
--     quantity of a SKU and nothing else, with the checkout deadline;
--   * releasing the order to a warehouse allocates it: the planner chooses pickable bins of that
--     warehouse by expiry, then by real receipt time, writes one stock_reservation row per bin and lot
--     (stock_item_id set, allocated_from_id = the SKU reservation, never expiring) and turns the SKU
--     reservation ALLOCATED;
--   * a pick line consumes exactly one allocation (uk_pick_line_reservation_live, unchanged).
--
-- One table for both on purpose. ordering.order_line_reservation, fulfillment.pick_line,
-- production.production_material_issue, production.production_subcontract_hold and
-- inventory.stock_adjustment all keep their foreign key to stock_reservation (id), and a SKU
-- reservation id an order line stored stays a valid reference after it is allocated.
--
-- Existing rows are not touched beyond the new sku column: every one of them sits on a stock item
-- (a production hold, a print hold, or a checkout hold of an order placed before this version). Such a
-- checkout hold already names its bin and lot and stays valid for picking as it is; releasing its order
-- allocates nothing (there is no SKU reservation to allocate). No data is reset.
--
-- Available to promise becomes, per SKU:
--     sum(on_hand - reserved) of AVAILABLE, unexpired stock  -  sum(quantity) of HELD SKU reservations
-- stock_item.reserved keeps counting every hold on the row (allocations included), so an allocation
-- moves units from the second term into the first and leaves the figure unchanged.
-- =============================================================================

-- ---- 1. the SKU on every row, and stock_item_id optional ------------------------------------------
ALTER TABLE inventory.stock_reservation ADD COLUMN sku VARCHAR(64);

UPDATE inventory.stock_reservation r
   SET sku = s.sku
  FROM inventory.stock_item s
 WHERE s.id = r.stock_item_id;

ALTER TABLE inventory.stock_reservation
    ALTER COLUMN sku SET NOT NULL,
    ALTER COLUMN stock_item_id DROP NOT NULL,
    ADD COLUMN allocated_from_id UUID,
    ADD CONSTRAINT fk_stock_reservation_sku FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_stock_reservation_allocated_from FOREIGN KEY (allocated_from_id)
        REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT;

-- ---- 2. the two kinds of row -----------------------------------------------------------------------
ALTER TABLE inventory.stock_reservation DROP CONSTRAINT ck_stock_reservation_status;
ALTER TABLE inventory.stock_reservation ADD CONSTRAINT ck_stock_reservation_status
    CHECK (status IN ('HELD', 'ALLOCATED', 'CONSUMED', 'RELEASED', 'EXPIRED'));

-- A SKU reservation is a customer order's checkout hold: never a production order's, never itself
-- an allocation, and never consumed - its units are consumed through the allocations it became.
-- ALLOCATED is the state of a SKU reservation only.
ALTER TABLE inventory.stock_reservation ADD CONSTRAINT ck_stock_reservation_kind CHECK (
    (stock_item_id IS NOT NULL AND status <> 'ALLOCATED')
    OR (stock_item_id IS NULL AND order_id IS NOT NULL AND production_order_id IS NULL
        AND allocated_from_id IS NULL AND status <> 'CONSUMED'));

-- An allocation is an order's, and does not expire (04c X-RESV).
ALTER TABLE inventory.stock_reservation ADD CONSTRAINT ck_stock_reservation_allocation
    CHECK (allocated_from_id IS NULL OR (order_id IS NOT NULL AND expires_at IS NULL));

COMMENT ON COLUMN inventory.stock_reservation.stock_item_id IS
    'The stock row held; NULL for a SKU reservation (checkout hold on a SKU, no location, SCRUM-152).';
COMMENT ON COLUMN inventory.stock_reservation.sku IS
    'The SKU held. For a row on a stock item it is that item''s SKU (tg_stock_reservation_sku).';
COMMENT ON COLUMN inventory.stock_reservation.allocated_from_id IS
    'The SKU reservation this allocation was made from at release (F-FUL step 02), kept through a reallocation after a short pick.';
COMMENT ON TABLE inventory.stock_reservation IS
    'Holds on stock. stock_item_id NULL: a SKU reservation (soft, expires). Otherwise a hold on one stock row: an allocation, a production hold, or a checkout hold made before SCRUM-152.';
COMMENT ON COLUMN inventory.stock_item.reserved IS
    'Denormalised sum of the HELD stock_reservation rows on this stock item (allocations and production holds); SKU reservations are not on any row.';

-- ---- 3. consistency the columns cannot state alone -------------------------------------------------
CREATE FUNCTION inventory.check_stock_reservation_sku()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    item_sku   VARCHAR(64);
    parent     inventory.stock_reservation%ROWTYPE;
BEGIN
    IF NEW.stock_item_id IS NOT NULL THEN
        SELECT sku INTO item_sku FROM inventory.stock_item WHERE id = NEW.stock_item_id;
        IF NEW.sku IS NULL THEN
            NEW.sku := item_sku;
        ELSIF NEW.sku IS DISTINCT FROM item_sku THEN
            RAISE EXCEPTION 'reservation % holds % but stock item % is %', NEW.id, NEW.sku, NEW.stock_item_id, item_sku
                USING ERRCODE = 'check_violation';
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
$$;

CREATE TRIGGER tg_stock_reservation_sku
    BEFORE INSERT OR UPDATE OF stock_item_id, sku, allocated_from_id ON inventory.stock_reservation
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_reservation_sku();

-- The no-oversell safety net for SKU reservations (BR-STK-001). The application checks the exact rule
-- under the SKU's advisory lock (expired lots excluded); this checks a looser one - on-hand of every
-- AVAILABLE row, expired or not - so it can never refuse what the application allowed, and still
-- refuses a reservation that no stock at all covers, whatever code path wrote it. Same advisory lock as
-- the application (hashtextextended(sku, 701)), so two writers are checked one after the other.
-- Only when a hold grows: pinning, re-arming or closing a reservation is never refused because stock
-- shrank meanwhile (a write-off, an expiry) - that is what the stock alerts report.
CREATE FUNCTION inventory.check_sku_reservation_covered()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    free BIGINT;
    held BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.sku, 701));
    SELECT COALESCE(SUM(on_hand - reserved), 0) INTO free
      FROM inventory.stock_item WHERE sku = NEW.sku AND status = 'AVAILABLE';
    SELECT COALESCE(SUM(quantity), 0) INTO held
      FROM inventory.stock_reservation WHERE sku = NEW.sku AND stock_item_id IS NULL AND status = 'HELD';
    IF held > free THEN
        RAISE EXCEPTION 'SKU reservations of % (%) exceed its unheld available stock (%)', NEW.sku, held, free
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER tg_stock_reservation_soft_atp_insert
    AFTER INSERT ON inventory.stock_reservation
    FOR EACH ROW WHEN (NEW.stock_item_id IS NULL AND NEW.status = 'HELD')
    EXECUTE FUNCTION inventory.check_sku_reservation_covered();

CREATE TRIGGER tg_stock_reservation_soft_atp_update
    AFTER UPDATE ON inventory.stock_reservation
    FOR EACH ROW WHEN (NEW.stock_item_id IS NULL AND NEW.status = 'HELD'
                       AND (OLD.status <> 'HELD' OR NEW.quantity > OLD.quantity OR NEW.sku <> OLD.sku))
    EXECUTE FUNCTION inventory.check_sku_reservation_covered();

-- ---- 4. the queries the new flow runs constantly ---------------------------------------------------
-- Available to promise subtracts the live SKU reservations of every SKU it is asked about.
CREATE INDEX ix_stock_reservation_sku_held ON inventory.stock_reservation (sku)
    WHERE stock_item_id IS NULL AND status = 'HELD';
-- The allocations of a SKU reservation, for the lot trace and the reallocation after a short pick.
CREATE INDEX ix_stock_reservation_allocated_from ON inventory.stock_reservation (allocated_from_id)
    WHERE allocated_from_id IS NOT NULL;
