-- =============================================================================
-- WF-05 audit round 3, LM-1: the completion hold and the refund branch (decision Q-17 = a).
--
-- V20261223000100 refused RETURNED -> PARTIALLY_REFUNDED while an order's completion is held, but the Java side asked
-- the hold of a carrier-ended order only when it was PARTIALLY_FULFILLED (DeliveryFailureService.followRefunds). A held
-- order the carrier brought back whole - its one delivery corrected to a failure while held, then returned - came to
-- that edge RETURNED, and the refund listener, the nightly step and the return receipt's own transaction all rolled
-- back on this trigger, every time, until Sales released the hold.
--
-- The Java side now asks the hold of every carrier-ended order (followRefunds), as a customer's return's path does
-- (ReturnRequestService.followReturns): a held order enters the refund branch by no path, to PARTIALLY_REFUNDED or to
-- REFUNDED. Stated a second time here, for both destinations: RETURNED -> REFUNDED joins the edges refused while held.
-- The exemption is unchanged - a claim opened from the refund branch (status_before_return REFUNDED /
-- PARTIALLY_REFUNDED, BE-40) goes back where it was, which is no new entry into the branch.
--
-- Additive: no row changes. The function is replaced; tg_order_completion_hold_respected keeps calling it.
-- =============================================================================

CREATE OR REPLACE FUNCTION ordering.order_completion_hold_respected() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status
        AND (NEW.status IN ('COMPLETED', 'CLOSED')
             OR (OLD.status = 'PARTIALLY_FULFILLED' AND NEW.status = 'PARTIALLY_REFUNDED')
             OR (OLD.status = 'RETURNED' AND NEW.status IN ('PARTIALLY_REFUNDED', 'REFUNDED')
                 AND COALESCE(OLD.status_before_return, '') NOT IN ('REFUNDED', 'PARTIALLY_REFUNDED')))
        AND EXISTS (SELECT 1 FROM ordering.order_completion_hold h
                     WHERE h.order_id = NEW.id AND h.released_at IS NULL) THEN
        RAISE EXCEPTION 'order % is not % while its completion is held', NEW.order_number, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_completion_hold_respected';
    END IF;
    RETURN NEW;
END
$$;
