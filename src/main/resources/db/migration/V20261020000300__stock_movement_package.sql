-- =============================================================================
-- Stock movement ledger: which stock package the stock left and which it went into.
-- Module: inventory   Issue: GitHub #125, Jira SCRUM-473
--
-- A stock package's history is then its ledger rows: received into it, put away with it, picked out
-- of it, moved with it. A side's stock package belongs to that side's location, so one without a
-- location on its side is refused (a RECEIPT has no source, a PICK no target: V20260928005000's shape
-- check). Moving a whole stock package names it on both sides; emptying it onto a bin names it as the
-- source only. The ledger's reference_type 'PACKAGE' stays the shipped parcel (fulfillment.package).
-- Adding the columns does not touch tg_stock_movement_append_only: no row is updated.
-- =============================================================================

ALTER TABLE inventory.stock_movement
    ADD COLUMN from_stock_package_id UUID,
    ADD COLUMN to_stock_package_id   UUID,
    ADD CONSTRAINT fk_stock_movement_from_package FOREIGN KEY (from_stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_stock_movement_to_package FOREIGN KEY (to_stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_stock_movement_package_side CHECK (
        (from_stock_package_id IS NULL OR from_location_code IS NOT NULL)
        AND (to_stock_package_id IS NULL OR to_location_code IS NOT NULL));

CREATE INDEX ix_stock_movement_from_package ON inventory.stock_movement (from_stock_package_id, occurred_at)
    WHERE from_stock_package_id IS NOT NULL;
CREATE INDEX ix_stock_movement_to_package ON inventory.stock_movement (to_stock_package_id, occurred_at)
    WHERE to_stock_package_id IS NOT NULL;

-- A stock package named on either side is of the movement's SKU and is at one of its two locations.
-- Not "at that side's location": moving a whole stock package names it on both sides, and the ledger
-- row may be written before or after the stock package's own location_code is updated, so at that
-- moment it is at the source or at the target. What is refused is a carton of another SKU, or one at
-- a bin the movement never touched. The ledger is append-only (tg_stock_movement_append_only), so a
-- wrong row could never be corrected afterwards.
CREATE FUNCTION inventory.check_stock_movement_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    side_package UUID;
    pkg_sku      VARCHAR(64);
    pkg_location VARCHAR(64);
BEGIN
    FOREACH side_package IN ARRAY ARRAY[NEW.from_stock_package_id, NEW.to_stock_package_id] LOOP
        CONTINUE WHEN side_package IS NULL;
        SELECT i.sku, p.location_code INTO pkg_sku, pkg_location
          FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
         WHERE p.id = side_package;
        CONTINUE WHEN NOT FOUND; -- the foreign key reports it
        IF pkg_sku <> NEW.sku THEN
            RAISE EXCEPTION 'a % movement names a stock package of %', NEW.sku, pkg_sku
                USING ERRCODE = 'check_violation';
        END IF;
        IF pkg_location IS DISTINCT FROM NEW.from_location_code
           AND pkg_location IS DISTINCT FROM NEW.to_location_code THEN
            RAISE EXCEPTION 'a movement from % to % names a stock package at %', NEW.from_location_code,
                NEW.to_location_code, pkg_location USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_movement_package BEFORE INSERT ON inventory.stock_movement
    FOR EACH ROW WHEN (NEW.from_stock_package_id IS NOT NULL OR NEW.to_stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_movement_package();

COMMENT ON COLUMN inventory.stock_movement.from_stock_package_id IS 'The stock package the stock left, if any.';
COMMENT ON COLUMN inventory.stock_movement.to_stock_package_id IS 'The stock package the stock went into, if any.';
