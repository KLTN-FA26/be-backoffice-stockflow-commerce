-- WF-05 TH-1 (owner decisions 2026-11-18; kltn-docs 17 §4.10; Luật Thương mại 2005 Điều 44, 313, 318): a return is
-- taken per delivered shipment. Each delivery already has its own handover record and its own e-invoice; a partial
-- delivery is handled per delivery (Điều 313), and the period to complain runs from that delivery, or as the contract
-- agreed (Điều 318). Before this a return waited for the whole order to be DELIVERED and its window ran from the last
-- delivery, so goods of batch 1 could not be claimed while batch 2 was still on its way.
--
--  1. ordering.return_request.shipment_id: the delivered shipment the return is against. NULL only for an order
--     delivered without shipments (before split shipments), which keeps the whole-order rule. Checked by
--     check_return_request_shipment: a shipment of the same order, DELIVERED when the return is opened, never changed;
--     and no NULL on an order that has shipments.
--  2. check_return_request_line: the units of a line are bounded by what THAT shipment delivered of it, less what the
--     other returns on the same shipment line claim, and one open return per (order line, shipment). The whole-line
--     bound stays as a second check, so a return of the old kind (no shipment) still counts against the line.
--  3. ck_return_request_four_eyes: a return whose goods came back with nothing usable in them (counted empty) is
--     closed without refund by one person - RECEIVED -> RESOLVED with a settlement of 0. No money moves, so there is
--     nothing for a second person to approve; any refund still needs two.
--  4. ordering.sales_contract.return_window_days: the complaint period agreed in the contract (Điều 318), written on
--     new contracts from the configured default and frozen with the rest once the contract is sent. NULL on contracts
--     drawn up before: the configured default applies.

-- ---------------------------------------------------------------------------------------------------------- 1

ALTER TABLE ordering.return_request ADD COLUMN shipment_id uuid;

ALTER TABLE ordering.return_request
    ADD CONSTRAINT fk_return_request_shipment FOREIGN KEY (shipment_id) REFERENCES ordering.order_shipment (id)
        ON DELETE RESTRICT;

CREATE INDEX ix_return_request_shipment ON ordering.return_request (shipment_id) WHERE shipment_id IS NOT NULL;

COMMENT ON COLUMN ordering.return_request.shipment_id IS 'The delivered shipment the return is against (WF-05 TH-1): its units bound the claim, its delivery opens the window, its goods come back on the return receipt, its installments and its e-invoice take the refund first. NULL only for an order delivered without shipments.';

-- A return opened before this on an order delivered in exactly one shipment was against that shipment: say so, so it
-- reads like every return from now on. One opened on an order of several shipments stays NULL - which shipment it
-- meant is not recorded anywhere - and keeps the whole-order rule.
UPDATE ordering.return_request r
   SET shipment_id = (SELECT s.id FROM ordering.order_shipment s WHERE s.order_id = r.order_id)
 WHERE r.shipment_id IS NULL
   AND (SELECT count(*) FROM ordering.order_shipment s WHERE s.order_id = r.order_id) = 1
   AND EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = r.order_id AND s.status = 'DELIVERED');

CREATE FUNCTION ordering.check_return_request_shipment() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    shipment_order  uuid;
    shipment_status varchar(24);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.shipment_id IS DISTINCT FROM OLD.shipment_id THEN
            RAISE EXCEPTION 'return %: the shipment it is against does not change', OLD.rma_number
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.shipment_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = NEW.order_id) THEN
            RAISE EXCEPTION 'return %: order % was delivered in shipments; a return names the one it is against',
                NEW.rma_number, NEW.order_number USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    SELECT s.order_id, s.status INTO shipment_order, shipment_status
      FROM ordering.order_shipment s WHERE s.id = NEW.shipment_id;
    IF NOT FOUND THEN
        RETURN NEW; -- fk_return_request_shipment reports it
    END IF;
    IF shipment_order IS DISTINCT FROM NEW.order_id THEN
        RAISE EXCEPTION 'return %: the shipment is not one of order %', NEW.rma_number, NEW.order_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF shipment_status <> 'DELIVERED' THEN
        RAISE EXCEPTION 'return %: the shipment is %, not DELIVERED (only goods received come back)',
            NEW.rma_number, shipment_status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_return_request_shipment BEFORE INSERT OR UPDATE OF shipment_id ON ordering.return_request
    FOR EACH ROW EXECUTE FUNCTION ordering.check_return_request_shipment();

-- ---------------------------------------------------------------------------------------------------------- 2

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
BEGIN
    SELECT l.quantity - l.cancelled_quantity, r.shipment_id INTO deliverable, the_shipment
      FROM ordering.order_line l
      JOIN ordering.return_request r ON r.order_id = l.order_id
     WHERE l.id = NEW.order_line_id AND r.id = NEW.return_request_id
       FOR UPDATE OF l;
    IF deliverable IS NULL THEN
        RAISE EXCEPTION 'return line must be a line of the returned order' USING ERRCODE = 'check_violation';
    END IF;
    -- ReturnLine.claimedUnits: asked for, approved while open, refunded once resolved - over every other return of
    -- the line, whatever shipment it is against: no line gives back more than it delivered.
    SELECT COALESCE(SUM(CASE
                            WHEN r.status = 'REQUESTED' THEN x.quantity
                            WHEN r.status IN ('AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING')
                                THEN x.approved_quantity
                            WHEN r.status = 'RESOLVED' THEN x.refund_quantity
                            ELSE 0 END), 0),
           COALESCE(SUM(CASE
                            WHEN r.shipment_id IS DISTINCT FROM the_shipment THEN 0
                            WHEN r.status = 'REQUESTED' THEN x.quantity
                            WHEN r.status IN ('AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING')
                                THEN x.approved_quantity
                            WHEN r.status = 'RESOLVED' THEN x.refund_quantity
                            ELSE 0 END), 0),
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
    IF claimed + COALESCE(NEW.approved_quantity, NEW.quantity) > deliverable THEN
        RAISE EXCEPTION 'would claim % of the % unit(s) delivered',
            claimed + COALESCE(NEW.approved_quantity, NEW.quantity), deliverable USING ERRCODE = 'check_violation';
    END IF;
    IF the_shipment IS NOT NULL THEN
        SELECT sl.quantity INTO on_shipment FROM ordering.order_shipment_line sl
         WHERE sl.shipment_id = the_shipment AND sl.order_line_id = NEW.order_line_id;
        IF on_shipment IS NULL THEN
            RAISE EXCEPTION 'return line: the shipment delivered nothing of order line %', NEW.order_line_id
                USING ERRCODE = 'check_violation';
        END IF;
        IF claimed_on_shipment + COALESCE(NEW.approved_quantity, NEW.quantity) > on_shipment THEN
            RAISE EXCEPTION 'would claim % of the % unit(s) this delivery brought',
                claimed_on_shipment + COALESCE(NEW.approved_quantity, NEW.quantity), on_shipment
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------------------- 3

ALTER TABLE ordering.return_request DROP CONSTRAINT ck_return_request_four_eyes;
ALTER TABLE ordering.return_request
    ADD CONSTRAINT ck_return_request_four_eyes CHECK (settled_by IS NULL OR settled_by <> proposed_by
                                                      OR (status = 'RESOLVED' AND settlement_amount = 0));

-- ---------------------------------------------------------------------------------------------------------- 4

ALTER TABLE ordering.sales_contract ADD COLUMN return_window_days integer;

ALTER TABLE ordering.sales_contract
    ADD CONSTRAINT ck_sales_contract_return_window_days CHECK (return_window_days IS NULL
                                                               OR (return_window_days > 0 AND return_window_days <= 365));

COMMENT ON COLUMN ordering.sales_contract.return_window_days IS 'The complaint period agreed in the contract, in calendar days from each delivery (Luật Thương mại 2005 Điều 318; WF-05 TH-1). Written from the configured default when the contract is drawn up; NULL on contracts drawn up before, which read the configured default.';

CREATE OR REPLACE FUNCTION ordering.check_sales_contract_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.contract_number IS DISTINCT FROM OLD.contract_number THEN
        RAISE EXCEPTION 'sales contract %: a contract keeps its number and stays with its order', OLD.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'DRAFT' AND (NEW.seller_name, NEW.seller_tax_code, NEW.seller_address, NEW.seller_representative,
            NEW.seller_representative_title, NEW.buyer_name, NEW.buyer_tax_code, NEW.buyer_address,
            NEW.buyer_representative, NEW.buyer_representative_title, NEW.free_sample_rounds, NEW.sent_at,
            NEW.sent_by, NEW.sent_document_key, NEW.return_window_days)
        IS DISTINCT FROM (OLD.seller_name, OLD.seller_tax_code, OLD.seller_address, OLD.seller_representative,
            OLD.seller_representative_title, OLD.buyer_name, OLD.buyer_tax_code, OLD.buyer_address,
            OLD.buyer_representative, OLD.buyer_representative_title, OLD.free_sample_rounds, OLD.sent_at,
            OLD.sent_by, OLD.sent_document_key, OLD.return_window_days)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change (14 BR-14)', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('SIGNED', 'COMPLETED') AND (NEW.signed_on, NEW.signed_file_key, NEW.signed_file_name,
            NEW.signed_at, NEW.signed_by)
        IS DISTINCT FROM (OLD.signed_on, OLD.signed_file_key, OLD.signed_file_name, OLD.signed_at, OLD.signed_by)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change (14 BR-14)', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('CANCELLED', 'COMPLETED') AND (NEW.cancelled_at, NEW.cancelled_by, NEW.cancellation_reason,
            NEW.completed_at)
        IS DISTINCT FROM (OLD.cancelled_at, OLD.cancelled_by, OLD.cancellation_reason, OLD.completed_at)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change', OLD.contract_number, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('SENT', 'CANCELLED'))
         OR (OLD.status = 'SENT' AND NEW.status IN ('SIGNED', 'CANCELLED'))
         OR (OLD.status = 'SIGNED' AND NEW.status = 'COMPLETED')) THEN
        RAISE EXCEPTION 'sales contract %: % -> % is not a transition (14 §5.2)', OLD.contract_number, OLD.status,
            NEW.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
