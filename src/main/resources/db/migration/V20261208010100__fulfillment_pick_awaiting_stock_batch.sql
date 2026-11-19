-- kltn-docs 07 §4.1 steps 1-2, §4.3 step 7; 17 §4.4 steps 15-16 (owner decision RTM-AWAIT-1118, 2026-11-19): the part
-- held of an order AWAITING_STOCK is picked before the order is released - to the staging area, or to dispatch when
-- Sales allowed a partial shipment - and the same task is topped up as the rest arrives. The task picks the order's
-- next delivery batch, which is released only once it is held in full (17 §4.2 step 5), so a pick may name a batch of
-- its order not released yet while the order awaits stock, and only then; any other pick still names a released batch.
-- An UPDATE of the pick (its delivery_batch_id or order_id) is checked the same way, as before.
CREATE OR REPLACE FUNCTION fulfillment.check_pick_delivery_batch() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.delivery_batch_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM ordering.delivery_batch_release r
         WHERE r.delivery_batch_id = NEW.delivery_batch_id AND r.order_id = NEW.order_id) AND NOT EXISTS (
        SELECT 1 FROM ordering.customer_order o
          JOIN ordering.sales_contract c ON c.order_id = o.id AND c.status <> 'CANCELLED'
          JOIN ordering.delivery_batch b ON b.contract_id = c.id
         WHERE o.id = NEW.order_id AND b.id = NEW.delivery_batch_id
           AND o.status = 'AWAITING_STOCK' AND o.released_at IS NULL) THEN
        RAISE EXCEPTION 'pick % of order %: batch % is not a released batch of that order, nor one awaiting stock',
            NEW.id, NEW.order_id, NEW.delivery_batch_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
