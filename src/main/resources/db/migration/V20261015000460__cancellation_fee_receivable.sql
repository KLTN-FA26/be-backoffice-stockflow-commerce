-- =============================================================================
-- An agreed cancellation amount above the money received is owed (decision Q6a, 2026-10-12); below it, the
-- rest is refunded through the existing refund process (Q6b); equal, nothing more happens.
--
-- ordering.customer_order.cancellation_fee_amount - the whole amount agreed. cancellation_retained_amount keeps
--   meaning "kept of the money received", now LEAST(agreed, paid), so refunds and ck_order_cancellation_retained
--   work unchanged. Rows written under V20261015000430 (agreed <= paid) get their amount from what was kept.
-- payment.receivable.kind CANCELLATION_FEE - the shortfall, one per order like any receivable. A cancelled order
--   is never delivered, so no CREDIT_ORDER receivable exists or follows for it: the sale is not billed twice.
-- =============================================================================

ALTER TABLE ordering.customer_order ADD COLUMN cancellation_fee_amount NUMERIC(19, 4);

UPDATE ordering.customer_order SET cancellation_fee_amount = cancellation_retained_amount
 WHERE cancellation_fee_reason IS NOT NULL;

ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_cancellation_fee;
ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_cancellation_fee CHECK (
        (cancellation_fee_reason IS NULL) = (cancellation_fee_detail IS NULL)
        AND (cancellation_fee_reason IS NULL) = (cancellation_fee_amount IS NULL)
        AND (cancellation_fee_amount IS NULL
             OR (status = 'CANCELLED' AND cancellation_fee_amount > 0
                 AND cancellation_retained_amount = LEAST(cancellation_fee_amount, paid_amount))));

ALTER TABLE payment.receivable DROP CONSTRAINT ck_receivable_kind;
ALTER TABLE payment.receivable ADD CONSTRAINT ck_receivable_kind CHECK (kind IN ('CREDIT_ORDER', 'CANCELLATION_FEE'));
