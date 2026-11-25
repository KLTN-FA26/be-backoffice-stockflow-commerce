-- =============================================================================
-- Four tables no code reads or writes and no current flow needs (issue #419):
--
--   warehouse.move_task         multi-step move tasks by loose quantity (replenishment, re-slotting). A move is a
--                               manual whole-carton move recorded at once, with no task (kltn-docs 11 §1); move tasks
--                               are a later phase (C2, C24). MoveReference.MOVE_TASK, the ledger's reference type of
--                               that manual move, stays, and so does ck_stock_movement_move_task_carton.
--   fulfillment.wave            wave picking is a later phase (kltn-docs 07, C17); fulfillment.pick.wave_id was never
--                               set, so it goes with its key and index.
--   notification.template       starter stubs nothing injected: e-mails are composed in code, and
--   notification.preference     delivery_log.template_code is a plain code naming what was composed; notifications go
--                               by role at send time, with no per-user settings (kltn-docs 00 §6).
--
-- No other table has a foreign key into any of them. The only rows anywhere are the demo seed's templates and
-- preferences, which go on purpose (owner decision QD-1: no archive). warehouse.slotting_rule stays until #426.
--
-- inventory.item_in_use is rewritten first, without warehouse.move_task: Postgres does not track a SQL function
-- body's dependencies, so the drop would succeed and the function fail at its next call.
-- =============================================================================

CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id uuid, p_sku character varying) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_package WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM ordering.production_order_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM catalog.customer_price WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE blank_sku = p_sku OR sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_reservation WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku = p_sku)
$$;

DROP TRIGGER tg_move_task_locations ON warehouse.move_task;
DROP FUNCTION warehouse.check_move_task_locations();
DROP TABLE warehouse.move_task;

ALTER TABLE fulfillment.pick DROP COLUMN wave_id;   -- fk_pick_wave and ix_pick_wave go with it
DROP TABLE fulfillment.wave;

DROP TABLE notification.template;
DROP TABLE notification.preference;
