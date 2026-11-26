-- Whether a carton may be turned belongs to the catalogue variant (owner decision Q9 of 2026-11-26).
--
-- inventory.inventory_items.rotatable was copied onto every customer's SKU at design approval with the carton
-- (ItemSpecificationsImpl.copy), and with the carton now the variant's (V20261231000700) the item has nothing left to
-- say about it. product.variants.rotatable takes the value of the variant's own item; inventory reads it through
-- inventory :: spi (no cross-schema JOIN in application code), and the bin-fit trigger reads it directly.

ALTER TABLE product.variants ADD COLUMN rotatable boolean NOT NULL DEFAULT true;

UPDATE product.variants v
   SET rotatable = i.rotatable
  FROM inventory.inventory_items i
 WHERE i.sku = v.sku;

COMMENT ON COLUMN product.variants.rotatable IS 'Whether a carton of this variant may be turned any of 6 ways when a truck is loaded, and on the horizontal to fit a bin (kltn-docs 01, 06 BR-01; owner decision Q9). Every SKU of the variant follows it (V20261231000800).';

CREATE OR REPLACE FUNCTION inventory.check_stock_package_fits_bin() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
    SELECT u.length_cm / 100, u.width_cm / 100, u.height_cm / 100, v.rotatable
      INTO c_length, c_width, c_height, c_turnable
      FROM inventory.uom_conversions u
      JOIN product.variants v ON v.id = u.variant_id
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

ALTER TABLE inventory.inventory_items DROP COLUMN rotatable;
