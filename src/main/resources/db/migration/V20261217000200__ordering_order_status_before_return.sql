-- =============================================================================
-- WF-05 BE-40 (L-14, R-13): a claim that ends without goods or money takes the order back to the status it was opened
-- from, not always to DELIVERED. A late claim on a COMPLETED order, refused, left it DELIVERED: completable again,
-- correctable, holdable, completed a second time with a new completed_at (17 BR-19: Completed is immutable).
--
--   * status_before_return: the status the claim was opened from (Order.requestReturn), kept while the order is
--     RETURN_REQUESTED or RETURNED by a claim, cleared off the return path. RETURNED / PARTIALLY_FULFILLED appear there
--     when a refund the order was refunded by is voided while a further claim is open (BE-52): the claim then ends in
--     waiting for that refund again, not refunded.
--   * remainder_closed_at (D23, BE-02): when the rest of the order was last closed - one of the moments the order
--     counts as delivered in full from (Order.deliveredInFullAt).
--   * edges RETURN_REQUESTED -> COMPLETED / REFUNDED / PARTIALLY_REFUNDED (the origin), and -> DELIVERY_FAILED /
--     PARTIALLY_FULFILLED: a delivery of the order corrected to a failure while the claim was open (BE-46) is not
--     undone by the claim ending - the order goes where its shipments say.
--
-- Stated twice: Order.requestReturn / withdrawReturn / leaveReturnPath, OrderStatus.canTransitionTo, and here.
-- Rows already on the return path keep a null origin and go back as before (DELIVERED, or COMPLETED once completed).
-- =============================================================================

ALTER TABLE ordering.customer_order ADD COLUMN status_before_return VARCHAR(32);
ALTER TABLE ordering.customer_order ADD COLUMN remainder_closed_at TIMESTAMPTZ;

ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_status_before_return CHECK (
    status_before_return IS NULL
    OR (status IN ('RETURN_REQUESTED', 'RETURNED')
        AND status_before_return IN ('DELIVERED', 'COMPLETED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'RETURNED',
                                     'PARTIALLY_FULFILLED')
        AND (status_before_return <> 'COMPLETED' OR completed_at IS NOT NULL)
        AND (status_before_return NOT IN ('REFUNDED', 'PARTIALLY_REFUNDED') OR refunded_at IS NOT NULL)));

COMMENT ON COLUMN ordering.customer_order.status_before_return IS
    'WF-05 BE-40: while RETURN_REQUESTED or RETURNED by a claim, the status the claim was opened from - where the order '
    'goes back when its claims end without money. Null off the return path.';
COMMENT ON COLUMN ordering.customer_order.remainder_closed_at IS
    'WF-05 D23 / BE-02: when the rest of the order was last closed; counts towards when it was delivered in full.';

INSERT INTO ordering.order_status_transition (from_status, to_status)
VALUES ('RETURN_REQUESTED', 'COMPLETED'), ('RETURN_REQUESTED', 'REFUNDED'), ('RETURN_REQUESTED', 'PARTIALLY_REFUNDED'),
       ('RETURN_REQUESTED', 'DELIVERY_FAILED'), ('RETURN_REQUESTED', 'PARTIALLY_FULFILLED')
ON CONFLICT DO NOTHING;
