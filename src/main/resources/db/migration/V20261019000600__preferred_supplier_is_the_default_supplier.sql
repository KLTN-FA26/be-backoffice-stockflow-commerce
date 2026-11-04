-- =============================================================================
-- One source for "the supplier we normally buy this item from": inventory_items.default_supplier_id.
-- Module: inventory / procurement
--
-- kltn-docs 01 §3 puts "Nhà cung cấp mặc định, giá mua tham chiếu, lead time, MOQ" on the Inventory Item, and
-- 02 §7 reads "NCC mặc định" from module 01. The supplier catalog (SCRUM-77) also kept a preferred flag per
-- catalog row (supplier_items.is_preferred), a second copy of the same fact that the two screens could set
-- to different suppliers. The catalog's "preferred" is now read from the item's default supplier, and
-- setting it writes the item (procurement -> inventory :: api), so the flag goes.
--
-- Carry-over: an item with no default supplier takes its preferred catalog row's supplier. Where both are
-- set and differ, the item's default stands (the docs' source); the count is reported.
-- =============================================================================

DO $$
DECLARE
    differing BIGINT;
BEGIN
    SELECT count(*) INTO differing
      FROM procurement.supplier_items s
      JOIN inventory.inventory_items i ON i.id = s.inventory_item_id
     WHERE s.is_preferred AND i.default_supplier_id IS NOT NULL AND i.default_supplier_id <> s.supplier_id;
    IF differing > 0 THEN
        RAISE NOTICE '% item(s) had a preferred catalog supplier other than their default supplier; the default stands',
            differing;
    END IF;
END $$;

UPDATE inventory.inventory_items i
   SET default_supplier_id = s.supplier_id,
       version = i.version + 1,
       last_modified_at = CURRENT_TIMESTAMP
  FROM procurement.supplier_items s
 WHERE s.inventory_item_id = i.id
   AND s.is_preferred
   AND i.default_supplier_id IS NULL;

-- uk_supplier_items_preferred goes with the column.
ALTER TABLE procurement.supplier_items DROP COLUMN is_preferred;
