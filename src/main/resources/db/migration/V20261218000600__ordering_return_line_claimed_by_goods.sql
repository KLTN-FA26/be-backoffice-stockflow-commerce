-- WF-05 BE-21 (audit R-4; with Q-4): units already back in the warehouse are never claimed again. What a return line
-- "claims" of its order line was read off the money stage - asked for, approved, refunded - so a return refunded 6 of
-- the 10 units that came back (or refused all of them, Q-4) left those units claimable by a new return, whose loose line
-- could then refund them a second time. It is now read off the goods as well (ReturnLine.claimedUnits):
--   REQUESTED                               max(asked for, back)
--   AWAITING_RETURN, RECEIVED, SETTLEMENT_PENDING  max(approved, back)
--   RESOLVED                                max(refunded, back)
--   anything else                           back (a withdrawn or expired claim whose goods came anyway)
-- The row being written counts the same way.

CREATE OR REPLACE FUNCTION ordering.check_return_request_line() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    deliverable          INTEGER;
    claimed              INTEGER;
    the_shipment         UUID;
    on_shipment          INTEGER;
    claimed_on_shipment  INTEGER;
    open_on_shipment     BOOLEAN;
    own_claim            INTEGER;
BEGIN
    SELECT l.quantity - l.cancelled_quantity, r.shipment_id INTO deliverable, the_shipment
      FROM ordering.order_line l
      JOIN ordering.return_request r ON r.order_id = l.order_id
     WHERE l.id = NEW.order_line_id AND r.id = NEW.return_request_id
       FOR UPDATE OF l;
    IF deliverable IS NULL THEN
        RAISE EXCEPTION 'return line must be a line of the returned order' USING ERRCODE = 'check_violation';
    END IF;
    -- ReturnLine.claimedUnits over every other return of the line, whatever shipment it is against: no line gives back
    -- more than it delivered.
    SELECT COALESCE(SUM(ordering.return_line_claimed(r.status, x.quantity, x.approved_quantity, x.received_quantity,
                                                     x.refund_quantity)), 0),
           COALESCE(SUM(CASE WHEN r.shipment_id IS DISTINCT FROM the_shipment THEN 0
                             ELSE ordering.return_line_claimed(r.status, x.quantity, x.approved_quantity,
                                                               x.received_quantity, x.refund_quantity) END), 0),
           COALESCE(bool_or(r.shipment_id IS NOT DISTINCT FROM the_shipment
                            AND r.status IN ('REQUESTED', 'AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING')), FALSE)
      INTO claimed, claimed_on_shipment, open_on_shipment
      FROM ordering.return_request_line x
      JOIN ordering.return_request r ON r.id = x.return_request_id
     WHERE x.order_line_id = NEW.order_line_id AND x.return_request_id <> NEW.return_request_id;
    -- One open return per order line and shipment (per order line, for an order delivered without shipments).
    IF TG_OP = 'INSERT' AND open_on_shipment THEN
        RAISE EXCEPTION 'order line % already has an open return on this delivery', NEW.order_line_id
            USING ERRCODE = 'check_violation';
    END IF;
    -- BR-RMA-002: goods printed to the customer's design come back for a named defect only (ReturnReason.isNamedFault).
    IF TG_OP = 'INSERT' AND NEW.custom_printed AND EXISTS (SELECT 1 FROM ordering.return_request r
                                                           WHERE r.id = NEW.return_request_id
                                                             AND r.reason_code IN ('CHANGED_MIND', 'OTHER')) THEN
        RAISE EXCEPTION 'printed goods come back for a named defect only (BR-RMA-002)' USING ERRCODE = 'check_violation';
    END IF;
    -- This line's own claim, the same way: what was approved (asked for, before approval), or what came back if more.
    own_claim := GREATEST(COALESCE(NEW.approved_quantity, NEW.quantity), COALESCE(NEW.received_quantity, 0));
    IF claimed + own_claim > deliverable THEN
        RAISE EXCEPTION 'would claim % of the % unit(s) delivered', claimed + own_claim, deliverable
            USING ERRCODE = 'check_violation';
    END IF;
    IF the_shipment IS NOT NULL THEN
        SELECT sl.quantity INTO on_shipment FROM ordering.order_shipment_line sl
         WHERE sl.shipment_id = the_shipment AND sl.order_line_id = NEW.order_line_id;
        IF on_shipment IS NULL THEN
            RAISE EXCEPTION 'return line: the shipment delivered nothing of order line %', NEW.order_line_id
                USING ERRCODE = 'check_violation';
        END IF;
        IF claimed_on_shipment + own_claim > on_shipment THEN
            RAISE EXCEPTION 'would claim % of the % unit(s) this delivery brought', claimed_on_shipment + own_claim,
                on_shipment USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- ReturnLine.claimedUnits, the one place the database says it.
CREATE FUNCTION ordering.return_line_claimed(p_status varchar, p_requested integer, p_approved integer,
                                             p_received integer, p_refunded integer) RETURNS integer
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT CASE
               WHEN p_status = 'REQUESTED' THEN GREATEST(p_requested, COALESCE(p_received, 0))
               WHEN p_status IN ('AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING')
                   THEN GREATEST(COALESCE(p_approved, 0), COALESCE(p_received, 0))
               WHEN p_status = 'RESOLVED' THEN GREATEST(COALESCE(p_refunded, 0), COALESCE(p_received, 0))
               ELSE COALESCE(p_received, 0)
           END
$$;
