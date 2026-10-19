-- =============================================================================
-- Putaway by the carton: a putaway task may name the stock package it puts away.
-- Module: warehouse   Issue: GitHub #125, Jira SCRUM-473
--
-- The warehouse puts goods away a carton at a time: scan the label, scan the bin. A task with a
-- stock package moves that stock package; one without moves loose stock, as before. quantity stays
-- the base-unit count (here: what the stock package holds), so nothing that reads it changes. The
-- slotting suggestion (SCRUM-91, not built yet) is meant to size such a load from the packing unit's
-- dimensions and weight (inventory.uom_conversions), not from one base unit's.
--
-- One open task per stock package: two tasks putting it in two places cannot both be right.
-- =============================================================================

ALTER TABLE warehouse.putaway_task
    ADD COLUMN stock_package_id UUID,
    ADD CONSTRAINT fk_putaway_task_package FOREIGN KEY (stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT;

CREATE INDEX ix_putaway_task_package ON warehouse.putaway_task (stock_package_id)
    WHERE stock_package_id IS NOT NULL;
CREATE UNIQUE INDEX uk_putaway_task_open_package ON warehouse.putaway_task (stock_package_id)
    WHERE stock_package_id IS NOT NULL AND status IN ('PENDING', 'IN_PROGRESS');

-- The stock package holds the task's SKU.
CREATE FUNCTION warehouse.check_putaway_task_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM inventory.stock_package p
                     JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
                    WHERE p.id = NEW.stock_package_id AND i.sku = NEW.sku) THEN
        RAISE EXCEPTION 'putaway task for % names a stock package of another SKU', NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_putaway_task_package BEFORE INSERT OR UPDATE OF stock_package_id, sku
    ON warehouse.putaway_task
    FOR EACH ROW WHEN (NEW.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION warehouse.check_putaway_task_package();

COMMENT ON COLUMN warehouse.putaway_task.stock_package_id IS 'The stock package this task puts away; NULL for loose stock.';
