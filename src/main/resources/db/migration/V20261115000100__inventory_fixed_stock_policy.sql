-- =============================================================================
-- One stock policy for every SKU (team plan 5-5; kltn-docs 01 BR-17, 03 BR-03).
--
-- Every SKU is lot-tracked, has no serial numbers and leaves stock first in, first out by the day it was received. An
-- item no longer carries a choice, so what held one goes:
--
--   1. inventory.enforce_sku_policy states the fixed policy for every SKU instead of reading the item's columns, which
--      it enforced only once an item was "configured" (policy_configured). For every stock row now:
--        * a live row (on_hand > 0) carries a lot number - 4C-1 (V20261111000110) gave every unit one, lot UNTRACED
--          for what had none, so the rows already comply;
--        * a row's identity (SKU, lot number, receipt time) never changes in place;
--        * a layer is never raised, except by units of its very receipt that the application names for that one
--          statement (stockflow.same_receipt_merge): new units are a new layer, or FIFO would make them as old as it;
--        * a receipt time is not in the future.
--      Undated layers stay allowed, as before for every SKU but a configured FIFO one: rows written before receipt
--      times were kept have none, and StockAllocator / CountVariance say how they are ordered (team plan 5-1).
--   2. inventory.check_receivable_item asks a lot of every goods receipt line (03 BR-03), not of a lot-tracked item's.
--   3. inventory.inventory_items loses lot_tracked, serial_tracked, removal_strategy (with ck_inventory_control_removal)
--      and policy_configured.
--   4. inventory.stock_item loses serial_number, with ck_stock_serial_quantity and uk_stock_item_live_serial; the two
--      unique layer keys are made again without it (uk_stock_item_sku_location_lot for loose rows,
--      uk_stock_item_package_layer for a carton's rows). No stock row has a serial number: no item was ever
--      serial-tracked in the demo data or the seed, so no two rows collapse into one key.
--
-- Kept, for their own packages: fulfillment.package_scan.serial_number (the packing station, team plan 4C-7) and
-- inventory.stock_damage_report.serial_number (damage reports, team plan 5-6). Both only ever held what stock_item did.
--
-- Nothing is archived and nothing stops the upgrade (owner's decision QD-1, 2026-11-10: the database is rebuilt on
-- deploy); the demo data of earlier migrations passes as it is. Written so that it survives the migrations of the
-- production line merged before it (V20261113000200 makes goods_receipt_lines.lot_number NOT NULL; nothing there
-- names a column dropped here).
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. The fixed policy, for every stock row
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION inventory.enforce_sku_policy()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Holds and consumption lock individual rows in id order. Taking a SKU-wide lock for them would invert that order
    -- when two reservations span different rows of the same SKU: nothing here concerns a row that only shrinks.
    IF TG_OP = 'UPDATE' AND NEW.sku IS NOT DISTINCT FROM OLD.sku
       AND NEW.received_at IS NOT DISTINCT FROM OLD.received_at
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND NEW.on_hand <= OLD.on_hand THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.sku, 701));
    IF TG_OP = 'UPDATE' THEN
        IF NEW.sku IS DISTINCT FROM OLD.sku OR NEW.received_at IS DISTINCT FROM OLD.received_at
           OR NEW.lot_number IS DISTINCT FROM OLD.lot_number THEN
            RAISE EXCEPTION 'Receipt identity is immutable; reconcile via a new stock layer' USING ERRCODE = '23514';
        END IF;
        IF NEW.on_hand <= OLD.on_hand THEN
            RETURN NEW;
        END IF;
        -- First in, first out (01 BR-17): replenishment is a new layer, or FIFO loses the age of the incoming units.
        -- Units of this very receipt moved in from another place (identity unchanged, checked just above) are not
        -- replenishment: the application names the row it merges into for that one statement.
        IF current_setting('stockflow.same_receipt_merge', true) IS DISTINCT FROM NEW.id::text THEN
            RAISE EXCEPTION 'FIFO replenishment requires a new receipt layer' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.on_hand = 0 THEN
        RETURN NEW;
    END IF;
    -- Every SKU is lot-tracked (01 BR-17): live stock carries its lot.
    IF nullif(btrim(NEW.lot_number), '') IS NULL THEN
        RAISE EXCEPTION 'Stock of % carries a lot number: every SKU is lot-tracked', NEW.sku USING ERRCODE = '23514';
    END IF;
    IF NEW.received_at > clock_timestamp() + interval '1 minute' THEN
        RAISE EXCEPTION 'Invalid receipt date' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION inventory.enforce_sku_policy() IS
    'The one stock policy of every SKU (kltn-docs 01 BR-17, team plan 5-5): live stock carries a lot, a row keeps its '
        'receipt identity, a layer grows only by its own receipt (stockflow.same_receipt_merge), no receipt time in '
        'the future. No serial numbers.';


-- ---------------------------------------------------------------------------------------------
-- 2. Every goods receipt line names its lot
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION inventory.check_receivable_item()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    item inventory.inventory_items%ROWTYPE;
BEGIN
    SELECT * INTO item FROM inventory.inventory_items WHERE id = NEW.inventory_item_id;
    IF item.weight_kg IS NULL OR item.length_cm IS NULL OR item.width_cm IS NULL OR item.height_cm IS NULL THEN
        RAISE EXCEPTION 'inventory item % has no weight/dimensions yet; complete it before receiving (BR-03)', item.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF nullif(btrim(NEW.lot_number), '') IS NULL THEN
        RAISE EXCEPTION 'inventory item % is lot-tracked; the receipt line needs a lot number', item.sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 3. The item's policy columns
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.inventory_items DROP CONSTRAINT ck_inventory_control_removal;

ALTER TABLE inventory.inventory_items
    DROP COLUMN lot_tracked,
    DROP COLUMN serial_tracked,
    DROP COLUMN removal_strategy,
    DROP COLUMN policy_configured;


-- ---------------------------------------------------------------------------------------------
-- 4. Serial numbers of stock
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_item DROP CONSTRAINT ck_stock_serial_quantity;
DROP INDEX inventory.uk_stock_item_live_serial;
DROP INDEX inventory.uk_stock_item_sku_location_lot;
DROP INDEX inventory.uk_stock_item_package_layer;

ALTER TABLE inventory.stock_item DROP COLUMN serial_number;

-- One loose layer per SKU, place, lot and receipt time; a carton holds one row per SKU, lot and receipt time.
CREATE UNIQUE INDEX uk_stock_item_sku_location_lot
    ON inventory.stock_item (sku, location_code, COALESCE(lot_number, ''), received_at) NULLS NOT DISTINCT
    WHERE stock_package_id IS NULL;
CREATE UNIQUE INDEX uk_stock_item_package_layer
    ON inventory.stock_item (stock_package_id, sku, COALESCE(lot_number, ''), received_at) NULLS NOT DISTINCT
    WHERE stock_package_id IS NOT NULL;
