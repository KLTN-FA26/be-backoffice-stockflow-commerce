-- =============================================================================
-- RTM-RETURNED (owner 18-19/11; kltn-docs 09 §4.3 step 4, 17 §5.1): a shipment that comes back closes its units - they
-- are sent again only on a new order. A delivery batch whose shipment came back is therefore done, and the next batch
-- is released and handed over as after any batch handed over in full: the release check of
-- ordering.check_delivery_batch_of_order counts the returned shipment's units too, as
-- DeliveryBatchService.nextToRelease does. Nothing else in the function changes.
-- =============================================================================

CREATE OR REPLACE FUNCTION ordering.check_delivery_batch_of_order() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    the_batch   RECORD;
    the_order   RECORD;
    previous      UUID;
    bad_seq       INTEGER;
    wanted_units  BIGINT;
    shipped_units BIGINT;
    starts_on    DATE;
BEGIN
    IF NEW.delivery_batch_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT b.id, b.seq, b.window_start, b.window_end, c.order_id, c.status AS contract_status, c.contract_number
      INTO the_batch
      FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
     WHERE b.id = NEW.delivery_batch_id;
    IF NOT FOUND OR the_batch.order_id <> NEW.order_id OR the_batch.contract_status NOT IN ('SIGNED', 'COMPLETED') THEN
        RAISE EXCEPTION 'delivery batch % is not of the signed contract of order %', NEW.delivery_batch_id, NEW.order_id
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_TABLE_NAME = 'delivery_batch_hold' THEN
        -- A hold stands for a line the batch carries.
        IF NOT EXISTS (SELECT 1 FROM ordering.delivery_batch_line l
                        WHERE l.delivery_batch_id = NEW.delivery_batch_id AND l.order_line_id = NEW.order_line_id) THEN
            RAISE EXCEPTION 'hold % of order %: batch % carries no cartons of line %', NEW.reservation_id, NEW.order_id,
                the_batch.seq, NEW.order_line_id USING ERRCODE = 'check_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'delivery_batch_release' THEN
        SELECT o.shipping_warehouse_id INTO the_order FROM ordering.customer_order o WHERE o.id = NEW.order_id;
        IF NEW.seq <> the_batch.seq THEN
            RAISE EXCEPTION 'release of batch %: seq % is not the batch''s %', NEW.delivery_batch_id, NEW.seq,
                the_batch.seq USING ERRCODE = 'check_violation';
        END IF;
        IF the_order.shipping_warehouse_id IS DISTINCT FROM NEW.warehouse_id THEN
            RAISE EXCEPTION 'release of batch % of order %: not at the order''s shipping warehouse (17 BR-22)',
                the_batch.seq, NEW.order_id USING ERRCODE = 'check_violation';
        END IF;
        IF the_batch.seq > 1 THEN
            SELECT b.id INTO previous FROM ordering.delivery_batch b
              JOIN ordering.sales_contract c ON c.id = b.contract_id
             WHERE c.order_id = NEW.order_id AND c.status IN ('SIGNED', 'COMPLETED') AND b.seq = the_batch.seq - 1;
            -- Handed over in full: the units of its shipments reach the units it carries (its cartons in pieces, a line
            -- without a carton count as one piece per carton) - as DeliveryBatchService.nextToRelease. A shipment that
            -- came back counts (RTM-RETURNED, owner 18-19/11): its units were closed, not owed again on this order.
            SELECT coalesce(sum(l.cartons * coalesce(ol.units_per_carton, 1)), 0) INTO wanted_units
              FROM ordering.delivery_batch_line l JOIN ordering.order_line ol ON ol.id = l.order_line_id
             WHERE l.delivery_batch_id = previous;
            SELECT coalesce(sum(sl.quantity), 0) INTO shipped_units
              FROM ordering.order_shipment s JOIN ordering.order_shipment_line sl ON sl.shipment_id = s.id
             WHERE s.delivery_batch_id = previous;
            IF NOT EXISTS (SELECT 1 FROM ordering.delivery_batch_release r WHERE r.delivery_batch_id = previous)
                OR shipped_units < wanted_units OR wanted_units = 0 THEN
                RAISE EXCEPTION 'batch % of order % is released once batch % is handed over in full (% of % units, 17 BR-29)',
                    the_batch.seq, NEW.order_id, the_batch.seq - 1, shipped_units, wanted_units
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME = 'order_shipment' THEN
        IF NOT EXISTS (SELECT 1 FROM ordering.delivery_batch_release r WHERE r.delivery_batch_id = NEW.delivery_batch_id)
        THEN
            RAISE EXCEPTION 'a shipment of order % names batch %, which is not released', NEW.order_id, the_batch.seq
                USING ERRCODE = 'check_violation';
        END IF;
        -- 09 BR-04: not handed over before the first day of the batch's window in force.
        starts_on := coalesce((SELECT w.new_window_start FROM ordering.delivery_batch_window_change w
                                   WHERE w.delivery_batch_id = NEW.delivery_batch_id
                                   ORDER BY w.changed_at DESC, w.id DESC LIMIT 1), the_batch.window_start);
        IF TG_OP = 'INSERT' AND starts_on IS NOT NULL
            AND (NEW.handed_over_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date < starts_on THEN
            RAISE EXCEPTION 'batch % of order % is handed over from %, its window in force (09 BR-04)', the_batch.seq,
                NEW.order_id, starts_on USING ERRCODE = 'check_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'delivery_batch_window_change' THEN
        IF EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.delivery_batch_id = NEW.delivery_batch_id) THEN
            RAISE EXCEPTION 'batch % of order %: a carton of it was handed over; its window no longer moves (17 BR-28)',
                the_batch.seq, NEW.order_id USING ERRCODE = 'check_violation';
        END IF;
        -- The customer's consent in a chat message: one the customer sent, in their own conversation, about this order
        -- or about none (DeliveryBatchService.check, through chat's ConsentMessages).
        IF NEW.evidence_message_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM chat.message m
              JOIN chat.conversation v ON v.id = m.conversation_id
              JOIN ordering.customer_order o ON o.id = NEW.order_id
             WHERE m.id = NEW.evidence_message_id AND m.sender_type = 'CUSTOMER' AND v.customer_id = o.customer_id
               AND (v.order_id IS NULL OR v.order_id = NEW.order_id)) THEN
            RAISE EXCEPTION 'order %: chat message % is not the customer''s own word in their conversation (17 BR-28)',
                NEW.order_id, NEW.evidence_message_id USING ERRCODE = 'check_violation';
        END IF;
        -- The windows in force after this change, batch by batch: none starts before the batch before it.
        WITH in_force AS (
            SELECT b.seq,
                   coalesce(CASE WHEN b.id = NEW.delivery_batch_id THEN NEW.new_window_start END,
                            (SELECT w.new_window_start FROM ordering.delivery_batch_window_change w
                              WHERE w.delivery_batch_id = b.id ORDER BY w.changed_at DESC, w.id DESC LIMIT 1),
                            b.window_start) AS window_start
              FROM ordering.delivery_batch b
              JOIN ordering.sales_contract c ON c.id = b.contract_id
             WHERE c.order_id = NEW.order_id AND c.status IN ('SIGNED', 'COMPLETED')
        )
        SELECT later.seq INTO bad_seq
          FROM in_force later JOIN in_force earlier ON earlier.seq = later.seq - 1
         WHERE later.window_start < earlier.window_start
         LIMIT 1;
        IF bad_seq IS NOT NULL THEN
            RAISE EXCEPTION 'order %: batch % would start before batch % (17 BR-28); move the later batches too',
                NEW.order_id, bad_seq, bad_seq - 1 USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
