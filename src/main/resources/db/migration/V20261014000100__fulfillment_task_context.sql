-- =============================================================================
-- SCRUM-466: a fulfillment task carries its order number and the warehouse it is picked in.
--
-- Before this the task knew only order_id, so the warehouse screens had to read GET /orders (a
-- permission the warehouse roles do not hold) to show a number, and nothing said which warehouse a
-- task belonged to: a manager of warehouse A saw every task, and any warehouse user could be
-- assigned work in any warehouse (BR-SEC-002, kltn-docs 07 §4 steps 5-6).
--
--   order_number  denormalised from ordering.customer_order, like the pick's other cross-module
--                 facts: a task list is shown without a call to another module per row.
--   warehouse_id  the warehouse the goods are picked from: the order's own warehouse once released;
--                 for a stock-only order picked straight from CONFIRMED, where its holds are.
--
-- Written in the application (the aggregate takes both in its constructor) and here (NOT NULL, a real
-- foreign key to warehouse.warehouse): a task nobody can place in a warehouse is a defect to refuse.
-- =============================================================================

ALTER TABLE fulfillment.pick
    ADD COLUMN order_number VARCHAR(32),
    ADD COLUMN warehouse_id UUID,
    ADD CONSTRAINT fk_pick_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id);

-- 1. Tasks of a released order: its number and its warehouse.
UPDATE fulfillment.pick p
   SET order_number = o.order_number,
       warehouse_id = o.warehouse_id
  FROM ordering.customer_order o
 WHERE o.id = p.order_id;

-- 2. Tasks of an order picked straight from CONFIRMED have no order warehouse: take it from where the
--    first hold of the order sits (reservation -> stock item -> storage location -> warehouse).
UPDATE fulfillment.pick p
   SET warehouse_id = (SELECT sl.warehouse_id
                         FROM inventory.stock_reservation r
                         JOIN inventory.stock_item si ON si.id = r.stock_item_id
                         JOIN warehouse.storage_location sl ON sl.location_code = si.location_code
                        WHERE r.order_id = p.order_id
                        ORDER BY r.reserved_at, r.id
                        LIMIT 1)
 WHERE p.warehouse_id IS NULL;

-- 3. Fail loudly, naming the rows, rather than guess: a task whose order is gone or whose holds were
--    never placed has no warehouse we can honestly give it. Fix those rows, then run the migration again.
DO $$
DECLARE
    orphans TEXT;
BEGIN
    SELECT string_agg(id::text, ', ') INTO orphans
      FROM (SELECT id FROM fulfillment.pick WHERE order_number IS NULL OR warehouse_id IS NULL ORDER BY id LIMIT 20) rows;
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'SCRUM-466: fulfillment.pick rows with no order number or warehouse (first 20): %', orphans;
    END IF;
END $$;

ALTER TABLE fulfillment.pick
    ALTER COLUMN order_number SET NOT NULL,
    ALTER COLUMN warehouse_id SET NOT NULL;

-- The warehouse manager's queue: one warehouse's tasks by status, newest first.
CREATE INDEX ix_pick_warehouse_status ON fulfillment.pick (warehouse_id, status, created_at DESC);

COMMENT ON COLUMN fulfillment.pick.order_number IS
    'ordering.customer_order.order_number at the time the task was created; shown on pick lists.';
COMMENT ON COLUMN fulfillment.pick.warehouse_id IS
    'The warehouse the goods are picked from: the order''s warehouse once released, else where its holds sit.';
