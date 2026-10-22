-- =============================================================================
-- A customer's cancellation request closes by itself once the order can no longer be cancelled (E2E QA 2026-10-21).
-- Module: ordering
--
-- A request waits for Sales. If the order is packed (its first package completed: from then on it is not cancelled,
-- decision 2026-10-17), handed over, delivered or cancelled meanwhile, approving it can only fail
-- (ORDER_NOT_CANCELLABLE) and the customer saw "pending" forever. The order service now closes such a request as
-- EXPIRED, with no decider and a decision note the customer reads; this adds the status and closes the requests that
-- are already in that state. EXPIRED is a closed status like APPROVED / REJECTED: decided_at set, decided_by NULL
-- (the system), which ck_order_cancellation_request_decided already allows.
-- =============================================================================

ALTER TABLE ordering.order_cancellation_request DROP CONSTRAINT ck_order_cancellation_request_status;
ALTER TABLE ordering.order_cancellation_request
    ADD CONSTRAINT ck_order_cancellation_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'));

UPDATE ordering.order_cancellation_request r
   SET status        = 'EXPIRED',
       decided_at    = NOW(),
       decision_note = CASE WHEN o.status = 'CANCELLED' THEN 'Closed: the order was cancelled.'
                            ELSE 'Closed: the order is '
                                 || CASE WHEN o.status IN ('DRAFT', 'PENDING_PAYMENT', 'CONFIRMED', 'IN_PRODUCTION',
                                                           'READY_TO_FULFILL', 'PICKING', 'ON_HOLD')
                                         THEN 'PACKED' ELSE o.status END
                                 || ' and can no longer be cancelled; ask for a return instead.' END,
       version          = r.version + 1,
       last_modified_at = NOW(),
       last_modified_by = 'V20261021001200'
  FROM ordering.customer_order o
 WHERE o.id = r.order_id
   AND r.status = 'PENDING'
   AND (o.packed_at IS NOT NULL
        OR o.status NOT IN ('DRAFT', 'PENDING_PAYMENT', 'CONFIRMED', 'IN_PRODUCTION', 'READY_TO_FULFILL', 'PICKING',
                            'ON_HOLD'));

COMMENT ON COLUMN ordering.order_cancellation_request.status IS
    'PENDING until Sales decides (APPROVED / REJECTED), or EXPIRED: closed by the system because the order was packed, '
    'shipped, delivered or cancelled meanwhile (decided_by NULL, decision_note says why).';
