-- TT-4: an order is not CLOSED while the seller still owes the customer a refund.
--
-- The nightly closing (OrderSettlementService.close) now also refuses an order whose refund is not made in full
-- (RefundProgress.outstanding > 0); that is a check in code and needs nothing here. What needs the schema is the other
-- half: a refund record voided after the order moved on to REFUNDED / PARTIALLY_REFUNDED takes the order back to
-- RETURNED (Order.refundReopened), because it is again waiting for its refund. Before this, a void left the order
-- REFUNDED and the nightly run closed it thirty days later with the money still owed - out of the order's sight for
-- good (BR-05).
--
-- From RETURNED the order moves forward again exactly as before, once the refund is recorded again: the return
-- request's or the failed delivery's followRefunds, on the refund recorded or the nightly run. refunded_at is kept
-- when the order goes back (ck_order_refunded_at asks nothing of RETURNED, and Order.refunded sets it anew), so no
-- constraint changes.
--
-- A CLOSED order is never reopened (CLOSED has no edge out, BR-05): a void after closing leaves the order as it is, and
-- the debt shows on the accountant's list of refunds still to make (GET /api/v1/refunds?outstanding=true), which is
-- payment's and does not depend on the order's status. A CANCELLED order is untouched too.
--
-- OrderStatus.canTransitionTo holds the same two edges; OrderLifecycleIntegrationTest#triggerRefusesJumps checks that
-- this table and OrderStatus.edges() are equal.

INSERT INTO ordering.order_status_transition (from_status, to_status)
VALUES ('REFUNDED', 'RETURNED'), ('PARTIALLY_REFUNDED', 'RETURNED')
ON CONFLICT DO NOTHING;

COMMENT ON TABLE ordering.order_status_transition IS
    'The edges of OrderStatus.canTransitionTo; tg_order_status_transition refuses any other change of status. '
    'REFUNDED / PARTIALLY_REFUNDED -> RETURNED (TT-4): a refund record of the order was voided, so it waits for its '
    'refund again; CLOSED and CANCELLED have no edge out.';
