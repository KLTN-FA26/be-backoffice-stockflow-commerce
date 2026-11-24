-- WF-05 audit round 2, returns: the database half of the rules ReturnRequest states for goods coming back.
--
--  1. H1 (Q-4): the customer's choice for goods of a return that refunded nothing - SEND_BACK at their cost, or
--     DESTROY_ON_BEHALF with their written consent (the note names the record) - kept on the return by Sales. Only a
--     RESOLVED return that refunded no unit has one. And once a return is RESOLVED, none of its cartons still waits for
--     the decision: refunded units make them the company's, none makes them the customer's
--     (V20261223000300, CartonReturns.settleOwnership in the same transaction).
--  2. M2 / L-3 (Q-11): a withdrawn-after-approval or expired return takes a parcel only while its approved units are
--     still its own - no other return claimed them since - and never on a CLOSED order. Its draft receipt then counts as
--     its claim again (return_line_claimed, draft open), so no new return can take those units while the parcel is
--     counted. Without it the receipt was confirmed, the goods stocked, and the return's line refused for ever by
--     check_return_request_line (more claimed than delivered): the event was retried at every restart and nobody was
--     refunded.
--  3. BE-23 / L4 (M-3): while a parcel is counted on a draft receipt, the return is not withdrawn, expired, marked
--     complete, proposed or resolved - the aggregate says so (RETURN_RECEIPT_DRAFT_OPEN), now the schema too.

-- ---------------------------------------------------------------------------------------------------------------- 1

ALTER TABLE ordering.return_request
    ADD COLUMN custody_choice varchar(24),
    ADD COLUMN custody_choice_note varchar(500),
    ADD COLUMN custody_choice_by uuid,
    ADD COLUMN custody_choice_at timestamptz;

ALTER TABLE ordering.return_request
    ADD CONSTRAINT fk_return_request_custody_choice_by FOREIGN KEY (custody_choice_by)
        REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_return_request_custody_choice CHECK (
        custody_choice IS NULL
        OR (custody_choice IN ('SEND_BACK', 'DESTROY_ON_BEHALF') AND status = 'RESOLVED'
            AND NOT vat_adjustment_required AND custody_choice_by IS NOT NULL AND custody_choice_at IS NOT NULL
            AND (custody_choice <> 'DESTROY_ON_BEHALF' OR custody_choice_note IS NOT NULL)));

COMMENT ON COLUMN ordering.return_request.custody_choice IS 'WF-05 Q-4: a return that refunded nothing leaves the goods the customer''s; Sales ask them and record SEND_BACK (at their cost) or DESTROY_ON_BEHALF (their written consent, named in custody_choice_note). The warehouse manager carries it out on the cartons.';

CREATE FUNCTION ordering.check_return_request_goods_settled() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    unsettled TEXT;
BEGIN
    SELECT string_agg(p.lpn || ' ' || p.return_ownership, ', ' ORDER BY p.lpn) INTO unsettled
      FROM inventory.stock_package p
     WHERE p.return_request_id = NEW.id
       AND (p.return_ownership = 'PENDING'
            OR (NEW.vat_adjustment_required AND p.return_ownership LIKE 'CUSTOMER\_%')
            OR (NOT NEW.vat_adjustment_required AND p.return_ownership = 'COMPANY'));
    IF unsettled IS NOT NULL THEN
        RAISE EXCEPTION 'return % is RESOLVED but its carton(s) % do not follow its refund (WF-05 Q-4)', NEW.rma_number,
            unsettled USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_return_request_goods_settled AFTER UPDATE OF status ON ordering.return_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.status = 'RESOLVED' AND OLD.status IS DISTINCT FROM 'RESOLVED')
    EXECUTE FUNCTION ordering.check_return_request_goods_settled();

-- ---------------------------------------------------------------------------------------------------------------- 2

-- ReturnLine.claimedUnits(status, draftOpen): a withdrawn or expired return whose parcel is being counted claims what
-- it approved again - the parcel will bring it back to RECEIVED. The five-argument form stays (no draft).
CREATE FUNCTION ordering.return_line_claimed(p_status varchar, p_requested integer, p_approved integer,
                                             p_received integer, p_refunded integer, p_draft_open boolean)
    RETURNS integer
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT CASE
               WHEN p_draft_open AND p_status IN ('CANCELLED', 'EXPIRED')
                   THEN GREATEST(COALESCE(p_approved, 0), COALESCE(p_received, 0))
               ELSE ordering.return_line_claimed(p_status, p_requested, p_approved, p_received, p_refunded)
           END
$$;

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
    -- more than it delivered. A closed return with a parcel on a draft claims what it approved (V20261223000310).
    SELECT COALESCE(SUM(ordering.return_line_claimed(r.status, x.quantity, x.approved_quantity, x.received_quantity,
                                                     x.refund_quantity, r.open_draft_receipt_id IS NOT NULL)), 0),
           COALESCE(SUM(CASE WHEN r.shipment_id IS DISTINCT FROM the_shipment THEN 0
                             ELSE ordering.return_line_claimed(r.status, x.quantity, x.approved_quantity,
                                                               x.received_quantity, x.refund_quantity,
                                                               r.open_draft_receipt_id IS NOT NULL) END), 0),
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

-- ReturnRequest.draftReceiptOpened: a parcel is counted for a return of an order that is not CLOSED (L-3: the order
-- would stay CLOSED while the return reopened and settled money), and for a closed return only while what it approved
-- is still free on its lines and its delivery.
CREATE FUNCTION ordering.check_return_request_draft_opened() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    order_status         VARCHAR(32);
    line                 RECORD;
    deliverable          INTEGER;
    claimed              INTEGER;
    on_shipment          INTEGER;
    claimed_on_shipment  INTEGER;
    own_claim            INTEGER;
BEGIN
    SELECT o.status INTO order_status FROM ordering.customer_order o WHERE o.id = NEW.order_id;
    IF order_status = 'CLOSED' THEN
        RAISE EXCEPTION 'return %: order is CLOSED, goods arriving now are kept in custody (WF-05 Q-11)', NEW.rma_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status NOT IN ('CANCELLED', 'EXPIRED') THEN
        RETURN NEW;
    END IF;
    FOR line IN SELECT x.order_line_id, x.quantity, x.approved_quantity, x.received_quantity
                  FROM ordering.return_request_line x WHERE x.return_request_id = NEW.id LOOP
        SELECT l.quantity - l.cancelled_quantity INTO deliverable FROM ordering.order_line l
         WHERE l.id = line.order_line_id FOR UPDATE OF l;
        SELECT COALESCE(SUM(ordering.return_line_claimed(r.status, x.quantity, x.approved_quantity,
                                                         x.received_quantity, x.refund_quantity,
                                                         r.open_draft_receipt_id IS NOT NULL)), 0),
               COALESCE(SUM(CASE WHEN r.shipment_id IS DISTINCT FROM NEW.shipment_id THEN 0
                                 ELSE ordering.return_line_claimed(r.status, x.quantity, x.approved_quantity,
                                                                   x.received_quantity, x.refund_quantity,
                                                                   r.open_draft_receipt_id IS NOT NULL) END), 0)
          INTO claimed, claimed_on_shipment
          FROM ordering.return_request_line x
          JOIN ordering.return_request r ON r.id = x.return_request_id
         WHERE x.order_line_id = line.order_line_id AND x.return_request_id <> NEW.id;
        own_claim := GREATEST(COALESCE(line.approved_quantity, line.quantity), COALESCE(line.received_quantity, 0));
        IF claimed + own_claim > deliverable THEN
            RAISE EXCEPTION 'return %: its approved units are claimed by another return now; receive the goods on that '
                'one (WF-05 Q-11)', NEW.rma_number USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.shipment_id IS NOT NULL THEN
            SELECT sl.quantity INTO on_shipment FROM ordering.order_shipment_line sl
             WHERE sl.shipment_id = NEW.shipment_id AND sl.order_line_id = line.order_line_id;
            IF claimed_on_shipment + own_claim > COALESCE(on_shipment, 0) THEN
                RAISE EXCEPTION 'return %: its approved units of this delivery are claimed by another return now; '
                    'receive the goods on that one (WF-05 Q-11)', NEW.rma_number USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END LOOP;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_return_request_draft_opened BEFORE UPDATE OF open_draft_receipt_id ON ordering.return_request
    FOR EACH ROW WHEN (NEW.open_draft_receipt_id IS NOT NULL AND OLD.open_draft_receipt_id IS NULL)
    EXECUTE FUNCTION ordering.check_return_request_draft_opened();

-- ---------------------------------------------------------------------------------------------------------------- 3

CREATE FUNCTION ordering.check_return_request_draft_holds() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status IN ('CANCELLED', 'EXPIRED', 'RESOLVED', 'SETTLEMENT_PENDING')
       OR (OLD.status = 'AWAITING_RETURN' AND NEW.status = 'RECEIVED') THEN
        RAISE EXCEPTION 'return % moves to % while a parcel of it is counted on draft receipt % (WF-05 BE-23)',
            NEW.rma_number, NEW.status, NEW.open_draft_receipt_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_return_request_draft_holds BEFORE UPDATE OF status ON ordering.return_request
    FOR EACH ROW WHEN (NEW.open_draft_receipt_id IS NOT NULL AND NEW.status IS DISTINCT FROM OLD.status)
    EXECUTE FUNCTION ordering.check_return_request_draft_holds();
