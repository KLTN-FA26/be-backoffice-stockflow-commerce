-- =============================================================================
-- CONTRACT C3 - inventory: stock and putaway are of known inventory items.
-- Activated from db/pending by the legacy-tables removal: the code no longer maps the old tables,
-- and V20261011000200 carried their rows over (and archived them in platform.legacy_archive).
-- The orphan check below stops with the table and the row count instead of failing half way.
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
