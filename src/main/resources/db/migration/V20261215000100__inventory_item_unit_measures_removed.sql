-- An inventory item no longer carries any physical measure of its own (kltn-docs 01 §3, BR-03, C21, chốt lại
-- 2026-11-22). Every measure the business uses is the carton's, on the item's one carton unit in uom_conversions:
-- its size and full weight are what putaway, the capacity board, the shipping label and load planning read.
--
-- Two groups of columns go:
--   * weight_kg, length_cm, width_cm, height_cm - one base unit (one cup). The docs listed them as optional and
--     nothing in the business read them; the code read them only as a stand-in when a carton had no weight or size,
--     and as the "item unit" level of the packing rule chain, which 09 §4 step 129 does not have (a SKU with no
--     carton spec is listed as missing, nothing stands in for it).
--   * package_weight_kg, package_length_cm, package_width_cm, package_height_cm, package_count, pack_size - the
--     shipped box of the parcel era (SCRUM-287) and its kit of package_count boxes; kits are gone (glossary §8) and
--     pack_size was read by nothing.
--
-- check_receivable_item refused a receipt line while the ITEM had no unit weight or size, measures the docs never
-- required (C21), and let through a carton with none, which they do require (01 BR-03: a carton spec is its unit,
-- its size and its full weight). It now asks the line's CARTON for its size and weight - on a line received against a
-- production order only: a carton that comes back on a return receipt is taken whatever its spec says. Its body named
-- weight_kg, which Postgres does not track in function bodies: rewrite it first. The lot check it also held stays.

CREATE OR REPLACE FUNCTION inventory.check_receivable_item() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    item_sku inventory.inventory_items.sku%TYPE;
    carton   inventory.uom_conversions%ROWTYPE;
BEGIN
    SELECT sku INTO item_sku FROM inventory.inventory_items WHERE id = NEW.inventory_item_id;
    IF NEW.production_order_line_id IS NOT NULL THEN
        SELECT * INTO carton FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
        IF carton.length_cm IS NULL OR carton.width_cm IS NULL OR carton.height_cm IS NULL
            OR carton.weight_kg IS NULL THEN
            RAISE EXCEPTION 'the carton % of % has no size or weight yet; complete its carton spec before receiving (01 BR-03)',
                carton.unit_of_measure, item_sku USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF nullif(btrim(NEW.lot_number), '') IS NULL THEN
        RAISE EXCEPTION 'inventory item % is lot-tracked; the receipt line needs a lot number', item_sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

ALTER TABLE inventory.inventory_items
    DROP CONSTRAINT ck_inventory_items_weight,
    DROP CONSTRAINT ck_inventory_items_size,
    DROP CONSTRAINT ck_inventory_items_package_weight,
    DROP CONSTRAINT ck_inventory_items_package_size,
    DROP CONSTRAINT ck_inventory_items_package_count,
    DROP CONSTRAINT ck_inventory_items_pack_size,
    DROP COLUMN weight_kg,
    DROP COLUMN length_cm,
    DROP COLUMN width_cm,
    DROP COLUMN height_cm,
    DROP COLUMN package_weight_kg,
    DROP COLUMN package_length_cm,
    DROP COLUMN package_width_cm,
    DROP COLUMN package_height_cm,
    DROP COLUMN package_count,
    DROP COLUMN pack_size;
