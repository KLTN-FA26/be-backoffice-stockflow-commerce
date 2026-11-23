-- =============================================================================
-- WF-05 BE-45 / X-8 (Order.returnsEndedWithoutRefund): every return of a RETURNED order ended and none of them settled
-- anything - refused, nothing usable came back, or nothing paid to give back (Q-4). "Partially refunded" would be
-- false: the order goes back to the delivery path - COMPLETED when it had completed before the claim, else DELIVERED,
-- from where it completes and closes as an order nothing was returned from. History reason
-- RETURN_SETTLED_WITHOUT_REFUND.
--
-- RETURNED -> DELIVERY_FAILED / PARTIALLY_FULFILLED: the same, for an order one of whose deliveries was corrected to a
-- failure while its goods were back (BE-46) - it goes where its shipments say.
--
-- Stated twice: OrderStatus.canTransitionTo and here (tg_order_status_transition reads this table). Additive: orders
-- that went RETURNED -> PARTIALLY_REFUNDED with nothing refunded before stay as they are.
-- =============================================================================

INSERT INTO ordering.order_status_transition (from_status, to_status)
VALUES ('RETURNED', 'DELIVERED'), ('RETURNED', 'COMPLETED'), ('RETURNED', 'DELIVERY_FAILED'),
       ('RETURNED', 'PARTIALLY_FULFILLED')
ON CONFLICT DO NOTHING;
