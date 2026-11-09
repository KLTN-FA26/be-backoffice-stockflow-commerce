-- =============================================================================
-- Storage class gone; a bin's capacity is its computed volume, and a carton must fit the bin it goes into
-- (kltn-docs 06 BR-01, BR-19, storage class removed 2026-11-05; 05 BR-03, BR-04, BR-13; 07 BR-12; 11 BR-10;
-- team plan 5-4).
--
-- 1. Storage class. No SKU, shelf, bin or area has one any more: which bin takes a carton is decided by the carton's
--    size alone. Dropped, after archiving every value in platform.legacy_archive:
--      warehouse.storage_location.storage_class (with ix_storage_location_putaway, which was keyed by it),
--      warehouse.shelf.default_storage_class, warehouse.bin.storage_class_override,
--      inventory.inventory_items.storage_class.
--    The two carrier flags of an item that nothing read - requires_adult_signature and shipping_restriction_note -
--    go with it (ItemLogistics loses all three).
--
-- 2. Capacity. warehouse.storage_location.capacity_units (a count of base units, typed in) is dropped, archived
--    first. A bin's capacity is its volume, width x length of the bin x the clear height (usable_height) of its shelf
--    level, computed where it is read and never stored: a stored copy would have to follow every change of a bin's
--    size and its level's height (06 BR-19: volume is never typed in). The map unit is the metre, so the product is
--    m³. Weight stays as declared (max_weight).
--
-- 3. Clear height required. A shelf level is declared with its usable_height from now on (06 BR-19, §4.1 step 6):
--    ck_shelf_level_usable_height_required, NOT VALID - every row written from now on must carry one, while a legacy
--    level without one stays as it is (its bins have no volume and no height limit until somebody edits the level,
--    06 §4.4, §4.6). The service refuses the same (Shelf.addLevel / updateLevel).
--
-- 4. A carton fits its bin (06 BR-01, decided 2026-11-05): tg_stock_package_fits_bin refuses a carton coming into a
--    bin (inserted there, or its location_code changed to it) unless its length, width and height - its carton
--    unit's, in centimetres - are at most the bin's length, width and its level's clear height (in metres), turned
--    on the horizontal (length for width) when its item is rotatable. A carton not fully measured, or a level without
--    a clear height, is not judged on what is unknown. An area takes any carton. The service checks the same first
--    (CartonFit, for putaway, put-back and every move), so this is the last line, not the error users see.
--    Cartons already standing in a bin they do not fit are not touched: the rule is checked when a carton moves.
--
-- 5. OVERFLOW. The area type exists since V20260928000100 (ck_area_type); nothing changes in the schema. A
--    warehouse manager creates one like any storage area (POST /api/v1/warehouses/{id}/areas, type OVERFLOW); a
--    putaway completes into it when no bin takes the carton (05 BR-13).
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1, 2. Archive what is dropped
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'warehouse.storage_location', l.id,
       jsonb_build_object('id', l.id, 'location_code', l.location_code, 'storage_class', l.storage_class,
                          'capacity_units', l.capacity_units)
FROM warehouse.storage_location l
UNION ALL
SELECT 'warehouse.shelf', s.id,
       jsonb_build_object('id', s.id, 'code', s.code, 'default_storage_class', s.default_storage_class)
FROM warehouse.shelf s
UNION ALL
SELECT 'warehouse.bin', b.id,
       jsonb_build_object('id', b.id, 'code', b.code, 'storage_class_override', b.storage_class_override)
FROM warehouse.bin b
WHERE b.storage_class_override IS NOT NULL
UNION ALL
SELECT 'inventory.inventory_items', i.id,
       jsonb_build_object('id', i.id, 'sku', i.sku, 'storage_class', i.storage_class,
                          'requires_adult_signature', i.requires_adult_signature,
                          'shipping_restriction_note', i.shipping_restriction_note)
FROM inventory.inventory_items i;

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), the customization templates and print areas as they were before templates got a model '
        '(V20261108000600), design statuses renamed when customers began designing (V20261108000800), the staff '
        'design workflow''s assignments, its designs left waiting for confirmation and its permission '
        '(V20261108001000), the parcel carriers'' status before they were retired (V20261108001300), the stock '
        'rows, cartons, holds and open pick lines as they were before loose stock went into cartons '
        '(V20261111000110), and the storage classes, unit capacities and carrier flags of locations, shelves, bins '
        'and items dropped when capacity became a computed volume (V20261112000100: row_data holds the id and the '
        'dropped columns only). Read-only history.';


-- ---------------------------------------------------------------------------------------------
-- 1, 2. Drop
-- ---------------------------------------------------------------------------------------------
DROP INDEX warehouse.ix_storage_location_putaway;
-- The putaway candidates of a warehouse, as WarehouseService.putawayCandidates reads them.
CREATE INDEX ix_storage_location_putaway ON warehouse.storage_location (warehouse_id)
    WHERE is_putaway_target AND status = 'ACTIVE' AND kind = 'BIN';

ALTER TABLE warehouse.storage_location
    DROP CONSTRAINT ck_storage_location_class,
    DROP CONSTRAINT ck_storage_location_capacity,
    DROP COLUMN storage_class,
    DROP COLUMN capacity_units;

ALTER TABLE warehouse.shelf
    DROP CONSTRAINT ck_shelf_storage_class,
    DROP COLUMN default_storage_class;

ALTER TABLE warehouse.bin
    DROP CONSTRAINT ck_bin_storage_class,
    DROP COLUMN storage_class_override;

ALTER TABLE inventory.inventory_items
    DROP CONSTRAINT ck_inventory_items_storage_class,
    DROP COLUMN storage_class,
    DROP COLUMN requires_adult_signature,
    DROP COLUMN shipping_restriction_note;


-- ---------------------------------------------------------------------------------------------
-- 3. A level is declared with its clear height
-- ---------------------------------------------------------------------------------------------
ALTER TABLE warehouse.shelf_level
    ADD CONSTRAINT ck_shelf_level_usable_height_required CHECK (usable_height IS NOT NULL) NOT VALID;
COMMENT ON CONSTRAINT ck_shelf_level_usable_height_required ON warehouse.shelf_level IS
    'kltn-docs 06 BR-19: a level carries its clear height, which bin volumes are computed from. NOT VALID on '
        'purpose: legacy levels without one stay until edited (06 §4.6); every row written from V20261112000100 on '
        'must have one.';


-- ---------------------------------------------------------------------------------------------
-- 4. A carton fits the bin it goes into
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION inventory.check_stock_package_fits_bin() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    c_length   NUMERIC;
    c_width    NUMERIC;
    c_height   NUMERIC;
    c_turnable BOOLEAN;
    b_width    NUMERIC;
    b_length   NUMERIC;
    b_height   NUMERIC;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.location_code IS NOT DISTINCT FROM OLD.location_code THEN
        RETURN NEW;
    END IF;
    SELECT b.width, b.length, lv.usable_height
      INTO b_width, b_length, b_height
      FROM warehouse.storage_location l
      JOIN warehouse.bin b ON b.location_id = l.id
      JOIN warehouse.shelf_level lv ON lv.id = b.level_id
     WHERE l.location_code = NEW.location_code;
    IF NOT FOUND THEN
        RETURN NEW;  -- an area, or a code the foreign keys of the stock rows will judge
    END IF;
    SELECT u.length_cm / 100, u.width_cm / 100, u.height_cm / 100, i.rotatable
      INTO c_length, c_width, c_height, c_turnable
      FROM inventory.uom_conversions u
      JOIN inventory.inventory_items i ON i.id = NEW.inventory_item_id
     WHERE u.id = NEW.uom_conversion_id;
    IF c_length IS NULL OR c_width IS NULL OR c_height IS NULL THEN
        RETURN NEW;  -- not fully measured: nothing to judge it by
    END IF;
    IF b_height IS NOT NULL AND c_height > b_height THEN
        RAISE EXCEPTION 'Carton % is taller than the clear height of bin %', NEW.lpn, NEW.location_code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_stock_package_fits_bin';
    END IF;
    IF (c_length <= b_length AND c_width <= b_width)
        OR (c_turnable AND c_width <= b_length AND c_length <= b_width) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Carton % does not fit bin %', NEW.lpn, NEW.location_code
        USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_stock_package_fits_bin';
END;
$$;
COMMENT ON FUNCTION inventory.check_stock_package_fits_bin() IS
    'kltn-docs 06 BR-01, 11 BR-10: a carton coming into a bin fits it dimension by dimension, turned on the '
        'horizontal when its item is rotatable. The service (CartonFit) checks first; this is the last line.';

CREATE TRIGGER tg_stock_package_fits_bin
    BEFORE INSERT OR UPDATE OF location_code ON inventory.stock_package
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_fits_bin();
