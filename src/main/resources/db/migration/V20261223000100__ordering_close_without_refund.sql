-- =============================================================================
-- WF-05 audit round 2: settlement, closing and the completion hold.
--
-- 1. Closing without a refund (decision Q-4 / BE-45; audit r2 contract M-6). An order the carrier's returns or losses
--    ended - the whole order (RETURNED, nothing delivered) or part of it (PARTIALLY_FULFILLED, the rest delivered) -
--    whose settlements made nothing refundable used to go to PARTIALLY_REFUNDED with 0 đồng refunded ("Hoàn một phần"
--    for a refund that never happened). It now stays RETURNED / PARTIALLY_FULFILLED and closes from there once the
--    closing delay has passed since its deliveries ended (Order.closeWithoutRefund, OrderSettlementService.close).
--    New edges RETURNED -> CLOSED and PARTIALLY_FULFILLED -> CLOSED (tg_order_status_transition reads the table).
--
--    Stated twice: Order.closeWithoutRefund / Order.endedByCarrier, and check_order_closed_without_refund below, which
--    refuses those two edges unless
--      * a shipment of the order ended undelivered (units closed: brought back and received, or lost);
--      * no shipment is still on its way, to be decided, or on its way back with its receipt not confirmed;
--      * from RETURNED, no shipment was DELIVERED (a customer's return makes an order RETURNED with goods delivered:
--        its claims decide where it goes, never this edge);
--      * no settlement of the order made money refundable (payment.order_settlement.refundable_amount): such an order
--        is the refund branch's (REFUNDED / PARTIALLY_REFUNDED -> CLOSED).
--    The completion hold (Q-17) refuses -> CLOSED already (order_completion_hold_respected).
--
-- 2. The completion hold and a customer's return (Q-17 = a; audit r2 lifecycle L-1). The hold refused
--    PARTIALLY_FULFILLED -> PARTIALLY_REFUNDED only, so a held order a customer's return made RETURNED still went
--    PARTIALLY_REFUNDED once refunded. Now also RETURNED -> PARTIALLY_REFUNDED, unless the claim was opened from the
--    refund branch (status_before_return REFUNDED / PARTIALLY_REFUNDED: it goes back where it was, BE-40). Stated
--    twice: ReturnRequestService.followReturns and DeliveryFailureService.followRefunds (completionHeld), and here.
--
-- Additive: no row changes. Orders that went to PARTIALLY_REFUNDED with nothing refunded before stay as they are
-- (the VPS database is rebuilt before the defence, decision Q-13).
-- =============================================================================

INSERT INTO ordering.order_status_transition (from_status, to_status)
VALUES ('RETURNED', 'CLOSED'), ('PARTIALLY_FULFILLED', 'CLOSED')
ON CONFLICT DO NOTHING;

CREATE FUNCTION ordering.check_order_closed_without_refund() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status = 'CLOSED' AND OLD.status IN ('RETURNED', 'PARTIALLY_FULFILLED') THEN
        IF NOT EXISTS (SELECT 1 FROM ordering.order_shipment s
                        WHERE s.order_id = NEW.id AND s.units_closed_at IS NOT NULL)
            OR EXISTS (SELECT 1 FROM ordering.order_shipment s
                        WHERE s.order_id = NEW.id AND s.status <> 'DELIVERED' AND s.units_closed_at IS NULL) THEN
            RAISE EXCEPTION 'order % is closed from % only once the carrier''s returns and losses ended it, nothing '
                'still on its way', NEW.order_number, OLD.status
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_closed_without_refund';
        END IF;
        IF OLD.status = 'RETURNED' AND EXISTS (SELECT 1 FROM ordering.order_shipment s
                                                WHERE s.order_id = NEW.id AND s.status = 'DELIVERED') THEN
            RAISE EXCEPTION 'order % is RETURNED by a customer''s return; its claims decide where it goes',
                NEW.order_number USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_closed_without_refund';
        END IF;
        IF EXISTS (SELECT 1 FROM payment.order_settlement ps
                    WHERE ps.order_id = NEW.id AND ps.refundable_amount > 0) THEN
            RAISE EXCEPTION 'order %: a settlement made money refundable; it closes from the refund branch',
                NEW.order_number USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_closed_without_refund';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_order_closed_without_refund
    BEFORE UPDATE OF status ON ordering.customer_order
    FOR EACH ROW EXECUTE FUNCTION ordering.check_order_closed_without_refund();

CREATE OR REPLACE FUNCTION ordering.order_completion_hold_respected() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status
        AND (NEW.status IN ('COMPLETED', 'CLOSED')
             OR (OLD.status = 'PARTIALLY_FULFILLED' AND NEW.status = 'PARTIALLY_REFUNDED')
             OR (OLD.status = 'RETURNED' AND NEW.status = 'PARTIALLY_REFUNDED'
                 AND COALESCE(OLD.status_before_return, '') NOT IN ('REFUNDED', 'PARTIALLY_REFUNDED')))
        AND EXISTS (SELECT 1 FROM ordering.order_completion_hold h
                     WHERE h.order_id = NEW.id AND h.released_at IS NULL) THEN
        RAISE EXCEPTION 'order % is not % while its completion is held', NEW.order_number, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_completion_hold_respected';
    END IF;
    RETURN NEW;
END
$$;
