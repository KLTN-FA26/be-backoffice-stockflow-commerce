-- =============================================================================
-- CONTRACT C3 - inventory: stock and putaway are of known inventory items.
-- NOT APPLIED YET. See README.md in this directory for how to activate it.
--
-- Run when: creating a variant creates its inventory item (docs 01 BR-07, 1:1), and every sku
-- present in inventory.stock_item and warehouse.putaway_task has one. Requires C1 first
-- (inventory_items.sku -> product.variants.sku is already enforced since V20260928003000).
-- =============================================================================

DO $$
DECLARE
    orphans BIGINT;
    check_sql TEXT;
BEGIN
    FOREACH check_sql IN ARRAY ARRAY[
        'inventory.stock_item.sku|SELECT count(*) FROM inventory.stock_item c WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items p WHERE p.sku = c.sku)',
        'warehouse.putaway_task.sku|SELECT count(*) FROM warehouse.putaway_task c WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items p WHERE p.sku = c.sku)'
    ] LOOP
        EXECUTE split_part(check_sql, '|', 2) INTO orphans;
        IF orphans > 0 THEN
            RAISE EXCEPTION 'C3: % row(s) in % have no inventory item', orphans, split_part(check_sql, '|', 1);
        END IF;
    END LOOP;
END $$;

ALTER TABLE inventory.stock_item
    ADD CONSTRAINT fk_stock_item_inventory_item FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT;
ALTER TABLE warehouse.putaway_task
    ADD CONSTRAINT fk_putaway_task_inventory_item FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS ix_putaway_task_sku ON warehouse.putaway_task (sku);
