-- =============================================================================
-- Ordering: an amount agreed with the customer, kept when an order is cancelled after its printing started
-- (decision Q2, 2026-10-12; BR-PRD-10, kltn-docs 17 §4.4).
--
-- Sales enters the amount; nothing computes it. It is kept in cancellation_retained_amount, the column the
-- percentage of V20261013000300 already fills - so OrderCancelled.refundableAmount (paid - kept) and the
-- refunds payment raises from it work unchanged, nothing is counted twice, and nothing is charged: the
-- amount is at most what was paid (ck_order_cancellation_retained, 15 BR-05). These two columns say why and
-- what was agreed; they exist only with an agreed amount.
-- =============================================================================

ALTER TABLE ordering.customer_order
    ADD COLUMN cancellation_fee_reason VARCHAR(500),
    ADD COLUMN cancellation_fee_detail VARCHAR(2000);

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_cancellation_fee CHECK (
        (cancellation_fee_reason IS NULL) = (cancellation_fee_detail IS NULL)
        AND (cancellation_fee_reason IS NULL
             OR (status = 'CANCELLED' AND cancellation_retained_amount > 0)));
