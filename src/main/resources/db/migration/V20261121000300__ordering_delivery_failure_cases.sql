-- =============================================================================
-- WF-05 fix plan, sections 2 and 3 (DS-05b): a shipment the carrier brought back puts its order ON_HOLD for
-- DELIVERY_FAILED, the return receipt of that shipment opens a delivery failure case, and Sales decide it - deliver the
-- goods again, or cancel what was not delivered (kltn-docs 09 §4.3, 03 §4.2, 17 §4.8, 15 §4.6).
--
-- Why. Until now a returned shipment moved its order on by itself: PARTIALLY_FULFILLED, or RETURNED when everything came
-- back - before any goods were received (the receipt needs the shipment RETURNED first), with no settlement and no way
-- on: the order stayed RETURNED for good (WF-05 audit, DS-05b). The goods are printed to the customer's design and
-- are the customer's own SKU, so they are sold to nobody else and a refund is not the default: what happens next is a
-- decision of Sales under the contract.
--
-- The order of things from here: the carrier reports the shipment back -> shipment RETURNED -> order ON_HOLD
-- (DELIVERY_FAILED) -> the return receipt takes the cartons into quarantine -> the case is OPEN -> Sales choose:
--   (a) REDELIVERY: the goods are held for the order again as they are put away, released to the warehouse, picked,
--       checked and handed over as a new shipment (REDELIVERED). A trip that failed because the customer refused the
--       goods or gave a wrong address is charged to the customer as a fee item (failed_trip_fee, fee_installment_id);
--       any other reason is the carrier's to answer for (carrier_claim) and charges nothing;
--   (b) CANCELLED: what was not delivered is cancelled through the closure of the rest with its agreed fee (4B-9);
--       what was collected above the goods delivered and the fee is refundable through refund_record.
--
-- What this adds:
--   1. the hold reason DELIVERY_FAILED (order and order_hold), and the edges SHIPPED -> ON_HOLD and
--      DELIVERY_FAILED -> ON_HOLD: a shipment comes back while the others are with the carrier or have their outcome;
--   2. the cancellation reason code DELIVERY_FAILED, for closing the rest after a failed delivery;
--   3. ordering.delivery_failure_case, its cartons and its holds;
--   4. payment: the fee item FAILED_TRIP_FEE - an installment with no plan row, paid like one, counted by no
--      settlement as money collected for goods; and a fee on a closure of the rest once goods of the order came back,
--      not only once mass printing started (they exist either way);
--   5. fulfillment.pick.redelivery_case_id: the pick that picks a case's goods again - a second pick of an order and
--      batch that were picked once already.
--
-- No backfill: an order already RETURNED or PARTIALLY_FULFILLED by a returned shipment stays as it is (QD-1: the
-- database is rebuilt on deploy). Nothing is archived.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. The hold for a failed delivery
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_hold_reason;
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_hold_reason CHECK (
    (status = 'ON_HOLD') = (hold_reason IS NOT NULL)
    AND (hold_reason IS NULL OR hold_reason IN ('CREDIT', 'PAYMENT', 'ADDRESS', 'INVENTORY', 'CUSTOMER', 'FRAUD',
                                                'OTHER', 'DELIVERY_FAILED')));

ALTER TABLE ordering.order_hold DROP CONSTRAINT ck_order_hold_reason;
ALTER TABLE ordering.order_hold ADD CONSTRAINT ck_order_hold_reason CHECK (
    reason IN ('CREDIT', 'DESIGN_MISMATCH', 'PAYMENT', 'ADDRESS', 'INVENTORY', 'CUSTOMER', 'FRAUD', 'OTHER',
               'DELIVERY_FAILED'));

-- A shipment comes back while every unit is shipped or closed: the order is SHIPPED (others still with the carrier) or
-- DELIVERY_FAILED (all have an outcome). From PARTIALLY_FULFILLED, PACKED and PICKING the edge is there already.
INSERT INTO ordering.order_status_transition (from_status, to_status)
VALUES ('SHIPPED', 'ON_HOLD'), ('DELIVERY_FAILED', 'ON_HOLD')
ON CONFLICT DO NOTHING;

-- An order held for a failed delivery is held from PARTIALLY_FULFILLED - part of it did not reach the customer - and
-- that is where it goes back to once Sales decided (ck_order_hold_return allows it already).
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_delivery_failed_hold CHECK (
    hold_reason IS DISTINCT FROM 'DELIVERY_FAILED' OR status_before_hold = 'PARTIALLY_FULFILLED');


-- ---------------------------------------------------------------------------------------------
-- 2. The cancellation reason
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_cancellation_code_values;
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_cancellation_code_values CHECK (
    cancellation_reason_code IN ('CUSTOMER_REQUEST', 'PAYMENT_NOT_RECEIVED', 'PAYMENT_FAILED', 'OUT_OF_STOCK',
                                 'SUSPECTED_FRAUD', 'CREDIT_REJECTED', 'DUPLICATE_ORDER', 'PRODUCTION_ISSUE',
                                 'REPLACEMENT_WITHDRAWN', 'DELIVERY_FAILED', 'OTHER'));


-- ---------------------------------------------------------------------------------------------
-- 3. Delivery failure cases
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ordering.delivery_failure_case (
    id                     UUID          NOT NULL,
    order_id               UUID          NOT NULL,
    shipment_id            UUID          NOT NULL,
    delivery_batch_id      UUID,
    receipt_id             UUID          NOT NULL,
    receipt_number         VARCHAR(40)   NOT NULL,
    warehouse_id           UUID          NOT NULL,
    failure_reason         VARCHAR(24)   NOT NULL,
    failure_note           VARCHAR(500),
    status                 VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    opened_at              TIMESTAMPTZ   NOT NULL,
    decided_by             UUID,
    decided_at             TIMESTAMPTZ,
    decision_note          VARCHAR(1000),
    failed_trip_fee        NUMERIC(19, 4),
    fee_installment_id     UUID,
    carrier_claim          BOOLEAN       NOT NULL DEFAULT FALSE,
    released_at            TIMESTAMPTZ,
    released_by            UUID,
    redelivery_shipment_id UUID,
    redelivered_at         TIMESTAMPTZ,
    closed_at              TIMESTAMPTZ,
    closed_by              UUID,
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_delivery_failure_case PRIMARY KEY (id),
    -- A shipment comes back once, on one receipt.
    CONSTRAINT uk_delivery_failure_case_shipment UNIQUE (shipment_id),
    CONSTRAINT uk_delivery_failure_case_receipt UNIQUE (receipt_id),
    CONSTRAINT uk_delivery_failure_case_redelivery UNIQUE (redelivery_shipment_id),
    CONSTRAINT ck_delivery_failure_case_reason CHECK (failure_reason IN ('CUSTOMER_ABSENT', 'REFUSED',
                                                                         'WRONG_ADDRESS', 'LOST', 'DAMAGED', 'OTHER')),
    CONSTRAINT ck_delivery_failure_case_status CHECK (status IN ('OPEN', 'REDELIVERY', 'REDELIVERED', 'CANCELLED')),
    -- OPEN waits for Sales; decided, it says who, when and why.
    CONSTRAINT ck_delivery_failure_case_decided CHECK (
        (status = 'OPEN') = (decided_at IS NULL)
        AND (decided_at IS NULL) = (decided_by IS NULL)
        AND (decided_at IS NULL) = (decision_note IS NULL)
        AND (decision_note IS NULL OR btrim(decision_note) <> '')),
    -- The failed trip is charged to the customer only when the customer made it fail (REFUSED, WRONG_ADDRESS), and
    -- then it is: a redelivery of such a case has its fee, whole dong, on a fee item. Any other reason is the carrier's
    -- (carrier_claim) and charges nothing. A case cancelled without a redelivery has neither.
    CONSTRAINT ck_delivery_failure_case_fee CHECK (
        (failed_trip_fee IS NULL) = (fee_installment_id IS NULL)
        AND (failed_trip_fee IS NULL OR (failed_trip_fee > 0 AND failed_trip_fee = TRUNC(failed_trip_fee)
                                         AND failure_reason IN ('REFUSED', 'WRONG_ADDRESS') AND status <> 'OPEN'))
        AND (NOT carrier_claim OR (failure_reason NOT IN ('REFUSED', 'WRONG_ADDRESS') AND status <> 'OPEN'
                                   AND failed_trip_fee IS NULL))
        AND (status NOT IN ('REDELIVERY', 'REDELIVERED')
             OR (failure_reason IN ('REFUSED', 'WRONG_ADDRESS')) = (failed_trip_fee IS NOT NULL))
        AND (status NOT IN ('REDELIVERY', 'REDELIVERED')
             OR (failure_reason NOT IN ('REFUSED', 'WRONG_ADDRESS')) = carrier_claim)),
    -- Released to the warehouse only once Sales decided to deliver again; delivered again only once released.
    CONSTRAINT ck_delivery_failure_case_released CHECK (
        (released_at IS NULL) = (released_by IS NULL)
        AND (released_at IS NULL OR status <> 'OPEN')),
    CONSTRAINT ck_delivery_failure_case_redelivered CHECK (
        (status = 'REDELIVERED') = (redelivery_shipment_id IS NOT NULL)
        AND (status = 'REDELIVERED') = (redelivered_at IS NOT NULL)
        AND (status <> 'REDELIVERED' OR released_at IS NOT NULL)
        AND (redelivery_shipment_id IS NULL OR redelivery_shipment_id <> shipment_id)),
    -- Closed once delivered again or cancelled; closed_by is who cancelled, or who signed the new shipment over.
    CONSTRAINT ck_delivery_failure_case_closed CHECK (
        (status IN ('REDELIVERED', 'CANCELLED')) = (closed_at IS NOT NULL)
        AND (closed_by IS NULL OR closed_at IS NOT NULL)
        AND (status <> 'CANCELLED' OR closed_by IS NOT NULL)),
    CONSTRAINT fk_delivery_failure_case_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_failure_case_shipment FOREIGN KEY (shipment_id)
        REFERENCES ordering.order_shipment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_failure_case_batch FOREIGN KEY (delivery_batch_id)
        REFERENCES ordering.delivery_batch (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_failure_case_receipt FOREIGN KEY (receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_failure_case_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_failure_case_decided_by FOREIGN KEY (decided_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_delivery_failure_case_released_by FOREIGN KEY (released_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_delivery_failure_case_closed_by FOREIGN KEY (closed_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_delivery_failure_case_fee_item FOREIGN KEY (fee_installment_id)
        REFERENCES payment.installment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_failure_case_redelivery FOREIGN KEY (redelivery_shipment_id)
        REFERENCES ordering.order_shipment (id) ON DELETE RESTRICT
);
CREATE INDEX ix_delivery_failure_case_order ON ordering.delivery_failure_case (order_id);
CREATE INDEX ix_delivery_failure_case_open ON ordering.delivery_failure_case (opened_at)
    WHERE status IN ('OPEN', 'REDELIVERY');
COMMENT ON TABLE ordering.delivery_failure_case IS
    'WF-05 fix plan, sections 2-3 (09 §4.3, 17 §4.8): a shipment the carrier brought back, received on a return '
    'receipt, waiting for Sales to deliver it again (REDELIVERY -> REDELIVERED) or cancel what was not delivered '
    '(CANCELLED). Its lines are the shipment''s (ordering.order_shipment_line).';
COMMENT ON COLUMN ordering.delivery_failure_case.failed_trip_fee IS
    'What the customer pays for the trip that failed, whole dong: only for REFUSED or WRONG_ADDRESS, owed on the fee '
    'item fee_installment_id (payment.installment, milestone FAILED_TRIP_FEE).';
COMMENT ON COLUMN ordering.delivery_failure_case.carrier_claim IS
    'The trip failed for a reason that is not the customer''s: nothing is charged, and the cost is claimed from the '
    'carrier outside the system.';

-- The cartons that came back on the receipt, as it listed them: what Sales see when they decide.
CREATE TABLE ordering.delivery_failure_case_carton (
    case_id    UUID         NOT NULL,
    lpn        VARCHAR(64)  NOT NULL,
    sku        VARCHAR(64)  NOT NULL,
    lot_number VARCHAR(64),
    quantity   INTEGER      NOT NULL,
    condition  VARCHAR(500),
    CONSTRAINT pk_delivery_failure_case_carton PRIMARY KEY (case_id, lpn),
    CONSTRAINT ck_delivery_failure_case_carton_quantity CHECK (quantity > 0),
    CONSTRAINT fk_delivery_failure_case_carton_case FOREIGN KEY (case_id)
        REFERENCES ordering.delivery_failure_case (id) ON DELETE CASCADE
);
COMMENT ON TABLE ordering.delivery_failure_case_carton IS
    'The cartons received back for a delivery failure case, with the condition the warehouse noted (03 §4.2 step 10). '
    'A copy for Sales to decide by; the cartons themselves are inventory''s. Written once.';

-- The holds taken for a case: the goods that came back, held for their order again and for no one else.
CREATE TABLE ordering.delivery_failure_case_hold (
    reservation_id UUID        NOT NULL,
    case_id        UUID        NOT NULL,
    order_line_id  UUID        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_delivery_failure_case_hold PRIMARY KEY (reservation_id),
    CONSTRAINT fk_delivery_failure_case_hold_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_delivery_failure_case_hold_case FOREIGN KEY (case_id)
        REFERENCES ordering.delivery_failure_case (id) ON DELETE CASCADE,
    CONSTRAINT fk_delivery_failure_case_hold_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE CASCADE
);
CREATE INDEX ix_delivery_failure_case_hold_case ON ordering.delivery_failure_case_hold (case_id);
COMMENT ON TABLE ordering.delivery_failure_case_hold IS
    'The holds taken for a delivery failure case (a SKU reservation, or its allocations after release): the units of '
    'the returned shipment held for the order again, out of everyone else''s ATP. A line the returned shipment carried.';

-- The rules the application keeps, kept again here: a case is opened for a RETURNED shipment of its order, on the
-- CLOSED return receipt of that shipment, at the warehouse the receipt names, with the shipment's batch and failure
-- reason; what it is never changes; it moves OPEN -> REDELIVERY -> REDELIVERED, or to CANCELLED from OPEN or
-- REDELIVERY; it is kept.
CREATE FUNCTION ordering.check_delivery_failure_case() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    shipment RECORD;
    receipt  RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'delivery failure case % of order %: cases are kept', OLD.id, OLD.order_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT s.order_id, s.status, s.delivery_batch_id, s.failure_reason INTO shipment
          FROM ordering.order_shipment s WHERE s.id = NEW.shipment_id;
        IF NOT FOUND OR shipment.order_id <> NEW.order_id OR shipment.status <> 'RETURNED' THEN
            RAISE EXCEPTION 'delivery failure case of order %: shipment % is not a returned shipment of that order',
                NEW.order_id, NEW.shipment_id USING ERRCODE = 'check_violation';
        END IF;
        -- A shipment written before failure reasons were recorded has none: its case says OTHER.
        IF shipment.delivery_batch_id IS DISTINCT FROM NEW.delivery_batch_id
            OR coalesce(shipment.failure_reason, 'OTHER') <> NEW.failure_reason THEN
            RAISE EXCEPTION 'delivery failure case of order %: it carries its shipment''s delivery batch and failure reason',
                NEW.order_id USING ERRCODE = 'check_violation';
        END IF;
        SELECT r.receipt_type, r.status, r.return_shipment_id, r.customer_order_id, r.warehouse_id, r.receipt_number
          INTO receipt FROM procurement.goods_receipts r WHERE r.id = NEW.receipt_id;
        IF NOT FOUND OR receipt.receipt_type <> 'RETURN' OR receipt.status <> 'CLOSED'
            OR receipt.return_shipment_id IS DISTINCT FROM NEW.shipment_id
            OR receipt.customer_order_id IS DISTINCT FROM NEW.order_id
            OR receipt.warehouse_id IS DISTINCT FROM NEW.warehouse_id
            OR receipt.receipt_number IS DISTINCT FROM NEW.receipt_number THEN
            RAISE EXCEPTION 'delivery failure case of order %: receipt % is not the closed return receipt of shipment %',
                NEW.order_id, NEW.receipt_number, NEW.shipment_id USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'delivery failure case of order %: it is opened OPEN, for Sales to decide', NEW.order_id
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.shipment_id IS DISTINCT FROM OLD.shipment_id
        OR NEW.delivery_batch_id IS DISTINCT FROM OLD.delivery_batch_id OR NEW.receipt_id IS DISTINCT FROM OLD.receipt_id
        OR NEW.receipt_number IS DISTINCT FROM OLD.receipt_number OR NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
        OR NEW.failure_reason IS DISTINCT FROM OLD.failure_reason OR NEW.failure_note IS DISTINCT FROM OLD.failure_note
        OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
        RAISE EXCEPTION 'delivery failure case % of order %: what it is about does not change', OLD.id, OLD.order_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('REDELIVERED', 'CANCELLED') THEN
        RAISE EXCEPTION 'delivery failure case % of order %: it is % and does not change', OLD.id, OLD.order_id,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'OPEN' AND NEW.status IN ('REDELIVERY', 'CANCELLED'))
        OR (OLD.status = 'REDELIVERY' AND NEW.status IN ('REDELIVERED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'delivery failure case % of order %: % -> % is not a transition', OLD.id, OLD.order_id,
            OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
    -- The decision and the release are made once.
    IF OLD.decided_at IS NOT NULL AND (NEW.decided_at IS DISTINCT FROM OLD.decided_at
            OR NEW.decided_by IS DISTINCT FROM OLD.decided_by) THEN
        RAISE EXCEPTION 'delivery failure case % of order %: it was decided on %; that does not change', OLD.id,
            OLD.order_id, OLD.decided_at USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.failed_trip_fee IS NOT NULL AND (NEW.failed_trip_fee IS DISTINCT FROM OLD.failed_trip_fee
            OR NEW.fee_installment_id IS DISTINCT FROM OLD.fee_installment_id) THEN
        RAISE EXCEPTION 'delivery failure case % of order %: the fee of the failed trip does not change', OLD.id,
            OLD.order_id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.released_at IS NOT NULL AND (NEW.released_at IS DISTINCT FROM OLD.released_at
            OR NEW.released_by IS DISTINCT FROM OLD.released_by) THEN
        RAISE EXCEPTION 'delivery failure case % of order %: it was released on %; that does not change', OLD.id,
            OLD.order_id, OLD.released_at USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.redelivery_shipment_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM ordering.order_shipment s
             WHERE s.id = NEW.redelivery_shipment_id AND s.order_id = NEW.order_id) THEN
        RAISE EXCEPTION 'delivery failure case % of order %: its redelivery is a shipment of that order', OLD.id,
            OLD.order_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_delivery_failure_case BEFORE INSERT OR UPDATE OR DELETE ON ordering.delivery_failure_case
    FOR EACH ROW EXECUTE FUNCTION ordering.check_delivery_failure_case();

CREATE TRIGGER tg_delivery_failure_case_carton_append_only
    BEFORE UPDATE OR DELETE ON ordering.delivery_failure_case_carton
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- A hold of a case stands for a line the returned shipment carried.
CREATE FUNCTION ordering.check_delivery_failure_case_hold() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ordering.delivery_failure_case c
                     JOIN ordering.order_shipment_line l ON l.shipment_id = c.shipment_id
                    WHERE c.id = NEW.case_id AND l.order_line_id = NEW.order_line_id) THEN
        RAISE EXCEPTION 'hold % of delivery failure case %: its returned shipment carried nothing of line %',
            NEW.reservation_id, NEW.case_id, NEW.order_line_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_delivery_failure_case_hold BEFORE INSERT OR UPDATE ON ordering.delivery_failure_case_hold
    FOR EACH ROW EXECUTE FUNCTION ordering.check_delivery_failure_case_hold();


-- ---------------------------------------------------------------------------------------------
-- 4. Payment: the fee item of a failed trip, and a fee on a closure once goods came back
-- ---------------------------------------------------------------------------------------------
ALTER TABLE payment.installment DROP CONSTRAINT ck_installment_milestone;
ALTER TABLE payment.installment DROP CONSTRAINT ck_installment_seq;
ALTER TABLE payment.installment DROP CONSTRAINT ck_installment_fee_item;

ALTER TABLE payment.installment
    ADD CONSTRAINT ck_installment_milestone CHECK (milestone IN ('ON_SIGNING', 'BEFORE_DELIVERY', 'AFTER_DELIVERY',
                                                                'CANCELLATION_FEE', 'FAILED_TRIP_FEE')),
    -- A plan row is 1-20 of its contract; a fee item comes after them, 21-40 (uk_installment_seq keeps one per number).
    ADD CONSTRAINT ck_installment_seq CHECK (
        (milestone IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE') AND seq BETWEEN 21 AND 40)
        OR (milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE') AND seq BETWEEN 1 AND 20)),
    -- A fee item is no plan row; it is due from the day it is opened (15 §5.1 "bắt đầu từ Due"), tied to no delivery
    -- batch, never lowered by a closure, and never cancelled: the fee is what is still owed whatever became of the order.
    ADD CONSTRAINT ck_installment_fee_item CHECK (
        (milestone IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE')) = (contract_installment_id IS NULL)
        AND (milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE')
             OR (due_days = 0 AND reached_at IS NOT NULL AND delivery_batch_id IS NULL AND reduced_amount = 0
                 AND status <> 'CANCELLED')));

COMMENT ON COLUMN payment.installment.contract_installment_id IS
    'The signed plan row it was made from; null only for a fee item - CANCELLATION_FEE (15 §4.6 step 18) or '
    'FAILED_TRIP_FEE (the trip of a refused or wrongly addressed delivery) - which has none.';

-- A fee item of a failed trip is the fee of one delivery failure case, of its order: checked at commit, when the case
-- that owes it is written. (assert_fee_item does the same for a cancellation fee item and its settlement.)
CREATE FUNCTION payment.check_delivery_failure_fee_item() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.milestone = 'FAILED_TRIP_FEE' AND NOT EXISTS (
            SELECT 1 FROM ordering.delivery_failure_case c
             WHERE c.fee_installment_id = NEW.id AND c.order_id = NEW.order_id AND c.failed_trip_fee = NEW.amount) THEN
        RAISE EXCEPTION 'fee item % of contract %: no delivery failure case of its order owes it', NEW.seq,
            NEW.contract_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tg_installment_delivery_failure_fee AFTER INSERT ON payment.installment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payment.check_delivery_failure_fee_item();

-- The money a settlement counts as collected is the money paid for goods: never a fee item's, whichever fee it is.
-- And a fee is kept on a closure once goods of the order exist: mass printing started (17 BR-11), or a shipment of it
-- came back - goods made to the customer's design, whatever the production order recorded. Otherwise as
-- V20261114000100.
CREATE OR REPLACE FUNCTION payment.check_order_settlement()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    o         RECORD;
    collected NUMERIC;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'settlement % of order %: a settlement is kept as it was made', OLD.kind, OLD.order_number
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT co.order_number, co.customer_id, co.currency, co.total_amount, co.closed_amount, co.printing_started_at,
           c.contract_number, c.status AS contract_status, c.order_id AS contract_order_id
      INTO o
      FROM ordering.customer_order co
      JOIN ordering.sales_contract c ON c.id = NEW.contract_id
     WHERE co.id = NEW.order_id;
    IF NOT FOUND OR o.contract_order_id <> NEW.order_id OR o.contract_status NOT IN ('SIGNED', 'COMPLETED') THEN
        RAISE EXCEPTION 'settlement of order %: contract % is not its signed one', NEW.order_number, NEW.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.customer_id IS DISTINCT FROM o.customer_id OR NEW.order_number IS DISTINCT FROM o.order_number
        OR NEW.contract_number IS DISTINCT FROM o.contract_number OR NEW.currency IS DISTINCT FROM o.currency THEN
        RAISE EXCEPTION 'settlement of order %: it is that order''s, of its customer, in its currency', o.order_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.fee_amount > 0 AND o.printing_started_at IS NULL AND NOT (NEW.kind = 'REMAINDER_CLOSED' AND EXISTS (
            SELECT 1 FROM ordering.order_shipment s WHERE s.order_id = NEW.order_id AND s.status = 'RETURNED')) THEN
        RAISE EXCEPTION 'settlement of order %: no fee before mass printing started (17 BR-11)', o.order_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.kind = 'ORDER_CANCELLED' AND NEW.fee_amount > o.total_amount - o.closed_amount THEN
        RAISE EXCEPTION 'settlement of order %: a fee of % is above what the order is worth (17 §4.8 step 30)',
            o.order_number, NEW.fee_amount USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.kind = 'REMAINDER_CLOSED' AND NEW.fee_amount > NEW.closed_amount THEN
        RAISE EXCEPTION 'settlement of order %: a fee of % is above the goods closed (17 §4.8 step 33)',
            o.order_number, NEW.fee_amount USING ERRCODE = 'check_violation';
    END IF;
    -- 4D-3: a return's refund settles the return of this order whose refund was proposed at this value.
    IF NEW.kind = 'GOODS_RETURNED' AND NOT EXISTS (
            SELECT 1 FROM ordering.return_request r
             WHERE r.id = NEW.source_id AND r.order_id = NEW.order_id
               AND r.status IN ('SETTLEMENT_PENDING', 'RESOLVED') AND r.settlement_amount = NEW.closed_amount) THEN
        RAISE EXCEPTION 'settlement of order %: goods returned are settled for a return of the order whose refund is '
            'proposed at %', o.order_number, NEW.closed_amount USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(SUM(i.paid_amount), 0) INTO collected
      FROM payment.installment i
     WHERE i.order_id = NEW.order_id AND i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE');
    IF collected <> NEW.collected_amount THEN
        RAISE EXCEPTION 'settlement of order %: % collected on record, its installments were paid % (15 BR-14)',
            o.order_number, NEW.collected_amount, collected USING ERRCODE = 'check_violation';
    END IF;
    -- 15 BR-14: what the order's settlements make refundable, this one with them, never exceeds what was collected.
    IF NEW.refundable_amount + (SELECT COALESCE(SUM(s.refundable_amount), 0) FROM payment.order_settlement s
                                 WHERE s.order_id = NEW.order_id) > collected THEN
        RAISE EXCEPTION 'settlement of order %: more to refund than the % collected (15 BR-14)', o.order_number,
            collected USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$function$;

-- A receipt that paid a fee item is voided whatever its order became: no settlement counted that money.
CREATE OR REPLACE FUNCTION payment.check_payment_receipt_void()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    settled VARCHAR;
BEGIN
    IF NEW.status = 'VOIDED' AND OLD.status = 'RECORDED' THEN
        SELECT s.order_number INTO settled
          FROM payment.payment_receipt_line l
          JOIN payment.installment i ON i.id = l.installment_id
          JOIN payment.order_settlement s ON s.order_id = i.order_id
         WHERE l.receipt_id = NEW.id AND i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE')
           AND OLD.recorded_at <= s.settled_at
           AND (s.kind = 'ORDER_CANCELLED' OR s.surplus_amount > 0)
         LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'payment receipt %: order % was settled on what it paid (15 §4.4 step 13)', OLD.reference,
                settled USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

-- A closure takes off its own order's installments, never a fee item - of either kind.
CREATE OR REPLACE FUNCTION payment.check_installment_reduction() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    settlement  RECORD;
    installment RECORD;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'installment reduction %: kept as written', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    SELECT s.kind, s.order_id, s.order_number INTO settlement FROM payment.order_settlement s
     WHERE s.id = NEW.settlement_id;
    SELECT i.order_id, i.milestone, i.seq INTO installment FROM payment.installment i WHERE i.id = NEW.installment_id;
    -- A closure of the rest, or goods returned and refunded (4D-3), takes off its own order's installments.
    IF settlement.kind NOT IN ('REMAINDER_CLOSED', 'GOODS_RETURNED') OR installment.order_id <> settlement.order_id
        OR installment.milestone IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE') THEN
        RAISE EXCEPTION 'order %: a closure takes off its own order''s installments, never a fee item (15 §4.6 step 19)',
            settlement.order_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;


-- ---------------------------------------------------------------------------------------------
-- 5. The pick that picks a case's goods again
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.pick ADD COLUMN redelivery_case_id UUID;
ALTER TABLE fulfillment.pick ADD CONSTRAINT fk_pick_redelivery_case FOREIGN KEY (redelivery_case_id)
    REFERENCES ordering.delivery_failure_case (id) ON DELETE RESTRICT;
-- One pick per order and batch, as before, and one more per redelivery (NULLS NOT DISTINCT: an order without a schedule
-- keeps its one pick, plus one per redelivery).
ALTER TABLE fulfillment.pick DROP CONSTRAINT uk_fulfillment_pick_order_batch;
ALTER TABLE fulfillment.pick ADD CONSTRAINT uk_fulfillment_pick_order_batch
    UNIQUE NULLS NOT DISTINCT (order_id, delivery_batch_id, redelivery_case_id);
COMMENT ON COLUMN fulfillment.pick.redelivery_case_id IS
    'The delivery failure case whose goods this pick picks again (WF-05 fix plan, section 2); null for the first pick '
    'of an order or batch. The case is released for redelivery, of the pick''s order and batch.';

CREATE FUNCTION fulfillment.check_pick_redelivery_case() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    the_case RECORD;
BEGIN
    IF NEW.redelivery_case_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT c.order_id, c.delivery_batch_id, c.status, c.released_at INTO the_case
      FROM ordering.delivery_failure_case c WHERE c.id = NEW.redelivery_case_id;
    IF NOT FOUND OR the_case.order_id <> NEW.order_id
        OR the_case.delivery_batch_id IS DISTINCT FROM NEW.delivery_batch_id THEN
        RAISE EXCEPTION 'pick % of order %: case % is not a delivery failure case of that order and batch', NEW.id,
            NEW.order_id, NEW.redelivery_case_id USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND (the_case.status <> 'REDELIVERY' OR the_case.released_at IS NULL) THEN
        RAISE EXCEPTION 'pick % of order %: its delivery failure case is not released for redelivery', NEW.id,
            NEW.order_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_pick_redelivery_case BEFORE INSERT OR UPDATE OF redelivery_case_id, order_id, delivery_batch_id
    ON fulfillment.pick
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_pick_redelivery_case();
