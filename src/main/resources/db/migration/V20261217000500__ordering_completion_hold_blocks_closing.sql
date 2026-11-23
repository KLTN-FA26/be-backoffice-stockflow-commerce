-- =============================================================================
-- WF-05 BE-05, decision Q-17 = a (L-3, L-I3): Sales' completion hold ("Giữ hoàn tất") protects a disputed delivery
-- while it is verified. It was checked on one edge only - DELIVERED -> COMPLETED - so a held order still went
-- PARTIALLY_FULFILLED -> PARTIALLY_REFUNDED (after which its deliveries could no longer be corrected) and was CLOSED for
-- good thirty days later with the hold open.
--
-- The hold now keeps the order from every edge that finalises it: -> COMPLETED (as before), -> CLOSED, and
-- PARTIALLY_FULFILLED -> PARTIALLY_REFUNDED. Stated twice: OrderSettlementService.close / Order.close (held),
-- DeliveryFailureService.followRefunds, SettlementCandidatesAdapter.settledBy, and this trigger.
-- =============================================================================

CREATE OR REPLACE FUNCTION ordering.order_completion_hold_respected() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status
        AND (NEW.status IN ('COMPLETED', 'CLOSED')
             OR (OLD.status = 'PARTIALLY_FULFILLED' AND NEW.status = 'PARTIALLY_REFUNDED'))
        AND EXISTS (SELECT 1 FROM ordering.order_completion_hold h
                     WHERE h.order_id = NEW.id AND h.released_at IS NULL) THEN
        RAISE EXCEPTION 'order % is not % while its completion is held', NEW.order_number, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_completion_hold_respected';
    END IF;
    RETURN NEW;
END
$$;
