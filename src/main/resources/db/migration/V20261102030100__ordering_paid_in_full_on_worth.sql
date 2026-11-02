-- =============================================================================
-- Follow-up of #196 (S6), owner decision SHORT-LIMITS 2026-11-02. Module: ordering. Schema owner review: Tu.
--
-- An order whose units were closed (the rest of a split order, a print line completed short) is worth less than its
-- total, and the customer owes no more than that. "Paid in full" compared the money with the original total
-- (ck_order_paid_in_full), so an order paid what it is now worth read PARTIALLY_PAID with an amount due of 0. The
-- order now compares with its worth (Order.billable, plus the fees closures agreed) and re-evaluates when a closure
-- lowers it.
--
-- closed_amount  the value of the units closed out of the order (cancelled_quantity x unit_price over its lines),
--                kept on the order because a CHECK sees one row only. Maintained by the order on every save;
--                backfilled below from the lines for the orders closed before this migration.
--
-- Data: every existing row keeps satisfying the new check (closed_amount is what the old check ignored: it only
-- widens it), and the backfill leaves closed_amount <= total_amount, as cancelled_quantity <= quantity per line.
-- =============================================================================

ALTER TABLE ordering.customer_order
    ADD COLUMN closed_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

UPDATE ordering.customer_order o
   SET closed_amount = c.closed
  FROM (SELECT l.order_id, SUM(l.cancelled_quantity * l.unit_price) AS closed
          FROM ordering.order_line l
         WHERE l.cancelled_quantity > 0
         GROUP BY l.order_id) c
 WHERE c.order_id = o.id;

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_closed_amount CHECK (closed_amount >= 0 AND closed_amount <= total_amount);

ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_paid_in_full;
ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_paid_in_full CHECK (paid_in_full_at IS NULL OR paid_amount >= total_amount - closed_amount);

COMMENT ON COLUMN ordering.customer_order.closed_amount IS
    'Value of the units closed out of the order (a closed remainder, a production shortfall): total_amount less what the order is worth now. "Paid in full" compares the money with total_amount - closed_amount.';
