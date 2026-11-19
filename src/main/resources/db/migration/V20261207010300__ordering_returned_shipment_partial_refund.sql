-- =============================================================================
-- RTM-RETURNED-PARTIAL (owner 19/11; kltn-docs 17 §5.1 edge added by docs PR #42): an order one shipment of which came
-- back while the others were delivered stays PARTIALLY_FULFILLED; once the refund of the part that came back is
-- recorded it moves straight to PARTIALLY_REFUNDED, never through RETURNED. OrderStatus.canTransitionTo has the edge;
-- tg_order_status_transition reads this table. A refund record of it voided takes it back to PARTIALLY_FULFILLED, the
-- status it was refunded from (as a voided refund takes an order refunded from RETURNED back to RETURNED, TT-4).
-- =============================================================================

INSERT INTO ordering.order_status_transition (from_status, to_status)
VALUES ('PARTIALLY_FULFILLED', 'PARTIALLY_REFUNDED'), ('PARTIALLY_REFUNDED', 'PARTIALLY_FULFILLED')
ON CONFLICT DO NOTHING;
