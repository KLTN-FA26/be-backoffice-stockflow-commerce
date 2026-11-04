-- =============================================================================
-- The customer's agreement to take a print line short (review 2026-10-25 of S6). Module: ordering. Schema owner
-- review: Tu.
--
-- 1. Production completing a job short lowers what the order is worth and refunds the difference. That is a sale's
--    decision, made with the customer, so Sales (sales-order-cancellations:UPDATE, as for closing a remainder) records
--    it on the line first: at most how many units the customer goes without, how the agreement was given, who and
--    when. The warehouse manager's short completion closes at most that many (Order.closeProductionShortfall);
--    shortfall_closed_quantity counts them, within the agreement and within the line's closed units.
-- 2. A cancellation after such a closure keeps and refunds out of the money still the order's - what was paid, less
--    the refunds closures asked for and the closures' fees it paid - so the refund the closure asked for is not paid
--    out a second time (Order.cancel). Before S6 no cancellable order had any of those, so every row stays valid.
-- =============================================================================

ALTER TABLE ordering.order_line
    ADD COLUMN shortfall_agreed_quantity INTEGER,
    ADD COLUMN shortfall_agreed_note     VARCHAR(500),
    ADD COLUMN shortfall_agreed_by       UUID,
    ADD COLUMN shortfall_agreed_at       TIMESTAMPTZ,
    ADD COLUMN shortfall_closed_quantity INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_order_line_shortfall_agreed_by FOREIGN KEY (shortfall_agreed_by)
        REFERENCES identity.app_user (id),
    -- All four, or none; a print line; the customer keeps at least one unit.
    ADD CONSTRAINT ck_order_line_shortfall_agreement CHECK (
        (shortfall_agreed_quantity IS NULL) = (shortfall_agreed_note IS NULL)
        AND (shortfall_agreed_quantity IS NULL) = (shortfall_agreed_by IS NULL)
        AND (shortfall_agreed_quantity IS NULL) = (shortfall_agreed_at IS NULL)
        AND (shortfall_agreed_quantity IS NULL
             OR (design_snapshot_id IS NOT NULL AND shortfall_agreed_quantity > 0
                 AND shortfall_agreed_quantity < quantity AND length(trim(shortfall_agreed_note)) > 0))),
    ADD CONSTRAINT ck_order_line_shortfall_closed CHECK (shortfall_closed_quantity >= 0
        AND shortfall_closed_quantity <= cancelled_quantity
        AND shortfall_closed_quantity <= COALESCE(shortfall_agreed_quantity, 0));

ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_cancellation_fee;
ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_cancellation_fee CHECK (
        (cancellation_fee_reason IS NULL) = (cancellation_fee_detail IS NULL)
        AND (cancellation_fee_reason IS NULL) = (cancellation_fee_amount IS NULL)
        AND (cancellation_fee_amount IS NULL
             OR (status = 'CANCELLED' AND cancellation_fee_amount > 0
                 AND cancellation_retained_amount = LEAST(cancellation_fee_amount,
                     GREATEST(0, paid_amount - remainder_refund_amount - remainder_fee_retained_amount
                                 - remainder_fee_paid_amount)))));

COMMENT ON COLUMN ordering.order_line.shortfall_agreed_quantity IS
    'S6: at most this many units of the print line the customer agreed with Sales to go without; production completes the line short within it.';
COMMENT ON COLUMN ordering.order_line.shortfall_closed_quantity IS
    'Of cancelled_quantity, the units production closed completing the line short (S6).';
