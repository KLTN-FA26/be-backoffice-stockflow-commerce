-- =============================================================================
-- Audit of PR #109, decisions of 2026-10-17: what a cancellation may keep depends on how far the order got.
--
-- ordering.customer_order.printing_started_at - the first print run of the order, in house or sent to a subcontractor.
--   Before it a cancellation is refunded in full and keeps nothing; from it Sales may keep an amount agreed with the
--   customer, with its reason and detail, above what was paid (the rest is owed) but never above the order total.
-- ordering.customer_order.packed_at - a package of the order was completed. From here the order is not cancelled: it
--   goes through a return (03 §9).
-- Both are written in the producing module's own transaction under the order's row lock, so a cancellation is
-- ordered with them.
--
-- Backfill from history only - nothing is invented: the earliest PRINTING / SUBCONTRACTED transition of any production
-- order of the sales order, and the packed_at of a PACKED package. Orders with neither stay NULL.
-- The CHECKs are the rules again: an agreed amount needs printing to have started and is at most the order total.
-- Agreed amounts exist only since V20261017000430 (this branch), so no deployed row can fail them.
-- =============================================================================

ALTER TABLE ordering.customer_order
    ADD COLUMN printing_started_at TIMESTAMPTZ,
    ADD COLUMN packed_at           TIMESTAMPTZ;

UPDATE ordering.customer_order o
   SET printing_started_at = first_run.started
  FROM (SELECT po.sales_order_id, min(h.changed_at) AS started
          FROM production.production_order po
          JOIN production.production_order_status_history h ON h.production_order_id = po.id
         WHERE po.sales_order_id IS NOT NULL AND h.to_status IN ('PRINTING', 'SUBCONTRACTED')
         GROUP BY po.sales_order_id) first_run
 WHERE first_run.sales_order_id = o.id;

UPDATE ordering.customer_order o
   SET packed_at = packed.at
  FROM (SELECT pk.order_id, min(p.packed_at) AS at
          FROM fulfillment.pick pk
          JOIN fulfillment.pack p ON p.pick_id = pk.id
         WHERE p.status = 'PACKED' AND p.packed_at IS NOT NULL
         GROUP BY pk.order_id) packed
 WHERE packed.order_id = o.id;

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_fee_after_printing
        CHECK (cancellation_fee_amount IS NULL OR printing_started_at IS NOT NULL),
    ADD CONSTRAINT ck_order_fee_within_total
        CHECK (cancellation_fee_amount IS NULL OR cancellation_fee_amount <= total_amount);

COMMENT ON COLUMN ordering.customer_order.printing_started_at IS
    'First print run of the order (in house or subcontracted): from here a cancellation may keep an agreed fee.';
COMMENT ON COLUMN ordering.customer_order.packed_at IS
    'A package of the order was completed: from here the order is returned, not cancelled.';
