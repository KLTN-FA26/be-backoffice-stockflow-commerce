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

-- The stock package holds the task's SKU. A task is given a stock package that can still be put away:
-- RECEIVING (waiting in the receiving area) or IN_STOCK (moved again). A VOID, CONSUMED or LOST one
-- holds nothing, and a task for it would also hold uk_putaway_task_open_package for nothing. Checked
-- when the stock package is set, not on every later update: a task completed after its carton was
-- emptied by a pick is still a task that was right when it was made.
CREATE FUNCTION warehouse.check_putaway_task_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    pkg_status VARCHAR(16);
    newly_set  BOOLEAN;
BEGIN
    SELECT p.status INTO pkg_status
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE p.id = NEW.stock_package_id AND i.sku = NEW.sku;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'putaway task for % names a stock package of another SKU', NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        newly_set := TRUE;
    ELSE
        newly_set := NEW.stock_package_id IS DISTINCT FROM OLD.stock_package_id;
    END IF;
    IF newly_set AND pkg_status NOT IN ('RECEIVING', 'IN_STOCK') THEN
        RAISE EXCEPTION 'putaway task for % names a stock package that is %', NEW.sku, pkg_status
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
