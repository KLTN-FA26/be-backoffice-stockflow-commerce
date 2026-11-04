-- =============================================================================
-- G3-2 (SCRUM-239/246/247): the order lifecycle of kltn-docs 17 §5, split shipments (decision G4-Q3, 2026-10-19),
-- hold / resolve (G3-Q9), and the state machine written a second time, here, where no code path can skip it.
--
-- 1. Statuses. IN_FULFILMENT goes: an order whose package was completed becomes PACKED, any other PICKING. New:
--    PICKING, PARTIALLY_FULFILLED, PACKED, DELIVERY_FAILED, RETURN_REQUESTED, REFUNDED, PARTIALLY_REFUNDED, CLOSED.
--    No PAYMENT_FAILED (G3-Q2). ordering.order_status_history is append-only and keeps the IN_FULFILMENT it recorded;
--    the application reads it as PICKING.
-- 2. Hold on the order: why (hold_reason), where it goes back (status_before_hold), the coordinator's note. Backfilled
--    from the open order_hold rows: CREDIT goes nowhere back (credit approval decides it); any other hold back to
--    the warehouse step the order had reached (PACKED / PICKING when it has a pick task; DESIGN_MISMATCH is raised at
--    packing), else CONFIRMED; an ON_HOLD order without an open hold (none expected) gets OTHER, with a note saying
--    so. order_hold gains its note and who raised it.
-- 3. Milestones (shipped / delivered / completed / refunded / closed) and the failed-delivery reason, backfilled from
--    the history where it has them, else from last_modified_at.
-- 4. Split shipments: ordering.order_shipment(+_line) - which units each shipment carried, its outcome, what its
--    delivery billed; order_line.cancelled_quantity - units staff closed out of a partly fulfilled order;
--    customer_order.billed_amount / receivable_paid_amount - what deliveries billed and what of paid_amount paid those
--    receivables, so the credit exposure counts a split order once; remainder_refund_amount. payment.receivable gets
--    shipment_id: one receivable per delivered shipment. fulfillment creates shipments only from G4-5, so
--    order_shipment.id carries no foreign key yet - G4-2a adds it with the shipments it records.
-- 5. ordering.order_status_transition holds the edges of OrderStatus.canTransitionTo (OrderLifecycleIntegrationTest and
--    OrderLifecycleMigrationUpgradeTest compare the two); tg_order_status_transition refuses an UPDATE of status along any other edge, and leaving
--    ON_HOLD anywhere but where it was held from (or CANCELLED; a credit hold to CONFIRMED / PENDING_PAYMENT).
--    INSERTs are not restricted: the application places orders as DRAFT / PENDING_PAYMENT / CONFIRMED / ON_HOLD, and
--    fixtures and imports insert the state they describe. A later migration that must rewrite statuses disables the
--    trigger in its own file.
-- No grant changes: hold, resolve and delivery outcomes use sales-orders:APPROVE (ORDER_COORDINATOR), closing a
-- remainder sales-order-cancellations:UPDATE - both seeded - so no app_role.version moves.
-- =============================================================================

-- ---- 2a. columns first: the status backfill below reads them ---------------------------------------------------
ALTER TABLE ordering.customer_order
    ADD COLUMN hold_reason             VARCHAR(24),
    ADD COLUMN status_before_hold      VARCHAR(32),
    ADD COLUMN hold_note               VARCHAR(1000),
    ADD COLUMN shipped_at              TIMESTAMPTZ,
    ADD COLUMN delivered_at            TIMESTAMPTZ,
    ADD COLUMN delivery_failure_reason VARCHAR(24),
    ADD COLUMN completed_at            TIMESTAMPTZ,
    ADD COLUMN refunded_at             TIMESTAMPTZ,
    ADD COLUMN closed_at               TIMESTAMPTZ,
    ADD COLUMN billed_amount           NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN receivable_paid_amount  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN remainder_refund_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

ALTER TABLE ordering.order_hold
    ADD COLUMN note      VARCHAR(1000),
    ADD COLUMN raised_by UUID;

-- ---- 1. statuses --------------------------------------------------------------------------------------------------
ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_status;

UPDATE ordering.customer_order o
   SET status = CASE WHEN o.packed_at IS NOT NULL OR EXISTS (
                         SELECT 1 FROM fulfillment.pick p JOIN fulfillment.pack k ON k.pick_id = p.id
                          WHERE p.order_id = o.id AND k.status = 'PACKED') THEN 'PACKED' ELSE 'PICKING' END
 WHERE o.status = 'IN_FULFILMENT';

ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_status CHECK (status IN
    ('DRAFT', 'PENDING_PAYMENT', 'CONFIRMED', 'IN_PRODUCTION', 'READY_TO_FULFILL', 'PICKING', 'PARTIALLY_FULFILLED',
     'PACKED', 'ON_HOLD', 'SHIPPED', 'DELIVERY_FAILED', 'DELIVERED', 'RETURN_REQUESTED', 'RETURNED', 'REFUNDED',
     'PARTIALLY_REFUNDED', 'COMPLETED', 'CANCELLED', 'CLOSED'));

-- ---- 2b. hold backfill ---------------------------------------------------------------------------------------------
-- ADDRESS_VERIFICATION (test data) is an ADDRESS hold; reasons the code never wrote cannot be told apart: OTHER,
-- keeping the old word in the note.
UPDATE ordering.order_hold SET reason = 'ADDRESS' WHERE reason = 'ADDRESS_VERIFICATION';
UPDATE ordering.order_hold
   SET note = 'Reason before V20261021000100: ' || reason, reason = 'OTHER'
 WHERE reason NOT IN ('CREDIT', 'DESIGN_MISMATCH', 'PAYMENT', 'ADDRESS', 'INVENTORY', 'CUSTOMER', 'FRAUD', 'OTHER');

-- Where a hold goes back: nowhere for credit (approval decides); to the warehouse step for an order the warehouse
-- has a task for (PACKED once its package was completed, else PICKING); to CONFIRMED for one it has not.
UPDATE ordering.customer_order o
   SET hold_reason = h.reason,
       status_before_hold = CASE
           WHEN h.reason = 'CREDIT' THEN NULL
           WHEN o.packed_at IS NOT NULL OR EXISTS (SELECT 1 FROM fulfillment.pick p JOIN fulfillment.pack k
                                                     ON k.pick_id = p.id
                                                  WHERE p.order_id = o.id AND k.status = 'PACKED') THEN 'PACKED'
           WHEN h.reason = 'DESIGN_MISMATCH' OR EXISTS (SELECT 1 FROM fulfillment.pick p
                                                         WHERE p.order_id = o.id AND p.status <> 'CANCELLED')
               THEN 'PICKING'
           ELSE 'CONFIRMED' END,
       hold_note = h.note
  FROM (SELECT DISTINCT ON (order_id) order_id, reason, note FROM ordering.order_hold
         WHERE resolved_at IS NULL ORDER BY order_id, raised_at DESC) h
 WHERE h.order_id = o.id AND o.status = 'ON_HOLD';

UPDATE ordering.customer_order o
   SET hold_reason = 'OTHER',
       status_before_hold = CASE WHEN EXISTS (SELECT 1 FROM fulfillment.pick p
                                               WHERE p.order_id = o.id AND p.status <> 'CANCELLED')
                                 THEN 'PICKING' ELSE 'CONFIRMED' END,
       hold_note = 'On hold without an open hold record before V20261021000100'
 WHERE o.status = 'ON_HOLD' AND o.hold_reason IS NULL;

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_hold_reason CHECK ((status = 'ON_HOLD') = (hold_reason IS NOT NULL)
        AND (hold_reason IS NULL OR hold_reason IN
            ('CREDIT', 'DESIGN_MISMATCH', 'PAYMENT', 'ADDRESS', 'INVENTORY', 'CUSTOMER', 'FRAUD', 'OTHER'))),
    -- Back to where it was held from; only a credit hold has nowhere (credit approval decides it).
    ADD CONSTRAINT ck_order_hold_return CHECK ((status_before_hold IS NULL OR status = 'ON_HOLD')
        AND (status <> 'ON_HOLD' OR hold_reason = 'CREDIT' OR status_before_hold IS NOT NULL)
        AND (status_before_hold IS NULL OR status_before_hold IN
            ('CONFIRMED', 'IN_PRODUCTION', 'READY_TO_FULFILL', 'PICKING', 'PARTIALLY_FULFILLED', 'PACKED'))),
    ADD CONSTRAINT ck_order_hold_note CHECK (hold_note IS NULL OR status = 'ON_HOLD');

ALTER TABLE ordering.order_hold
    ADD CONSTRAINT ck_order_hold_reason CHECK (reason IN
        ('CREDIT', 'DESIGN_MISMATCH', 'PAYMENT', 'ADDRESS', 'INVENTORY', 'CUSTOMER', 'FRAUD', 'OTHER')),
    ADD CONSTRAINT ck_order_hold_other_note CHECK (reason <> 'OTHER' OR note IS NOT NULL),
    ADD CONSTRAINT fk_order_hold_raised_by FOREIGN KEY (raised_by) REFERENCES identity.app_user (id);

-- ---- 3. milestones --------------------------------------------------------------------------------------------------
UPDATE ordering.customer_order o
   SET shipped_at = COALESCE((SELECT max(h.occurred_at) FROM ordering.order_status_history h
                               WHERE h.order_id = o.id AND h.to_status = 'SHIPPED'), o.last_modified_at, o.placed_at)
 WHERE o.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED', 'RETURNED');

UPDATE ordering.customer_order o
   SET delivered_at = COALESCE((SELECT max(h.occurred_at) FROM ordering.order_status_history h
                                 WHERE h.order_id = o.id AND h.to_status = 'DELIVERED'), o.last_modified_at, o.placed_at)
 WHERE o.status IN ('DELIVERED', 'COMPLETED');

UPDATE ordering.customer_order o
   SET completed_at = COALESCE((SELECT max(h.occurred_at) FROM ordering.order_status_history h
                                 WHERE h.order_id = o.id AND h.to_status = 'COMPLETED'), o.last_modified_at, o.placed_at)
 WHERE o.status = 'COMPLETED';

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_shipped_at CHECK (status NOT IN
        ('SHIPPED', 'DELIVERY_FAILED', 'DELIVERED', 'RETURN_REQUESTED', 'COMPLETED') OR shipped_at IS NOT NULL),
    ADD CONSTRAINT ck_order_delivered_at CHECK (status NOT IN ('DELIVERED', 'RETURN_REQUESTED', 'COMPLETED')
        OR delivered_at IS NOT NULL),
    ADD CONSTRAINT ck_order_delivery_failure CHECK ((status = 'DELIVERY_FAILED') = (delivery_failure_reason IS NOT NULL)
        AND (delivery_failure_reason IS NULL OR delivery_failure_reason IN
            ('CUSTOMER_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'LOST', 'DAMAGED', 'OTHER'))),
    ADD CONSTRAINT ck_order_completed_at CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL),
    ADD CONSTRAINT ck_order_refunded_at CHECK (status NOT IN ('REFUNDED', 'PARTIALLY_REFUNDED') OR refunded_at IS NOT NULL),
    ADD CONSTRAINT ck_order_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    ADD CONSTRAINT ck_order_billing CHECK (billed_amount >= 0 AND billed_amount <= total_amount
        AND receivable_paid_amount >= 0 AND receivable_paid_amount <= paid_amount
        AND remainder_refund_amount >= 0 AND remainder_refund_amount <= paid_amount);

-- ---- 4. split shipments --------------------------------------------------------------------------------------------
ALTER TABLE ordering.order_line
    ADD COLUMN cancelled_quantity INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_order_line_cancelled_quantity CHECK (cancelled_quantity >= 0 AND cancelled_quantity <= quantity);

CREATE TABLE ordering.order_shipment
(
    id               UUID           NOT NULL,
    order_id         UUID           NOT NULL,
    status           VARCHAR(24)    NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    billed_amount    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    handed_over_at   TIMESTAMPTZ    NOT NULL,
    handed_over_by   UUID,
    outcome_at       TIMESTAMPTZ,
    outcome_by       UUID,
    failure_reason   VARCHAR(24),
    failure_note     VARCHAR(500),

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_order_shipment PRIMARY KEY (id),
    CONSTRAINT fk_order_shipment_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_shipment_handed_over_by FOREIGN KEY (handed_over_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_order_shipment_outcome_by FOREIGN KEY (outcome_by) REFERENCES identity.app_user (id),
    -- kltn-docs 09 §5 from the handover on: the order learns of a shipment when it leaves.
    CONSTRAINT ck_order_shipment_status CHECK (status IN ('HANDED_OVER', 'DELIVERED', 'DELIVERY_FAILED', 'RETURNED')),
    CONSTRAINT ck_order_shipment_amounts CHECK (amount > 0 AND billed_amount >= 0 AND billed_amount <= amount),
    -- Returned goods are never billed; only a delivered shipment bills.
    CONSTRAINT ck_order_shipment_billed CHECK (status = 'DELIVERED' OR billed_amount = 0),
    CONSTRAINT ck_order_shipment_outcome CHECK (status = 'HANDED_OVER' OR outcome_at IS NOT NULL),
    -- The reason stays after a retry delivered it, so the history keeps why it failed (09 BR-02).
    CONSTRAINT ck_order_shipment_failure CHECK ((status <> 'DELIVERY_FAILED' OR failure_reason IS NOT NULL)
        AND (failure_reason IS NULL OR failure_reason IN
            ('CUSTOMER_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'LOST', 'DAMAGED', 'OTHER')))
);

CREATE INDEX ix_order_shipment_order ON ordering.order_shipment (order_id, handed_over_at);

COMMENT ON COLUMN ordering.order_shipment.id IS
    'fulfillment.shipment id; the foreign key comes with G4-2a, when fulfillment records shipments';

CREATE TABLE ordering.order_shipment_line
(
    shipment_id   UUID    NOT NULL,
    order_line_id UUID    NOT NULL,
    quantity      INTEGER NOT NULL,

    CONSTRAINT pk_order_shipment_line PRIMARY KEY (shipment_id, order_line_id),
    CONSTRAINT fk_order_shipment_line_shipment FOREIGN KEY (shipment_id)
        REFERENCES ordering.order_shipment (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_shipment_line_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT,
    CONSTRAINT ck_order_shipment_line_quantity CHECK (quantity > 0)
);

CREATE INDEX ix_order_shipment_line_line ON ordering.order_shipment_line (order_line_id);

-- Receivables: one per delivered shipment of a split order; a whole-order delivery and a cancellation fee stay one
-- per order (shipment_id NULL).
ALTER TABLE payment.receivable ADD COLUMN shipment_id UUID;
ALTER TABLE payment.receivable DROP CONSTRAINT uk_receivable_order;
CREATE UNIQUE INDEX uk_receivable_order_shipment ON payment.receivable
    (order_id, COALESCE(shipment_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- What deliveries billed, and which money paid those receivables: until now one receivable per delivered order.
UPDATE ordering.customer_order o
   SET billed_amount = r.amount,
       receivable_paid_amount = LEAST(r.paid_amount, o.paid_amount)
  FROM payment.receivable r
 WHERE r.order_id = o.id AND r.kind = 'CREDIT_ORDER';

-- Refunds of the rest of a partly fulfilled order closed by staff: once per captured payment and closure (an order
-- may be closed twice: once, then again after a failed shipment came back), so a redelivered event asks nothing twice
-- and a second closure is not swallowed by the first.
ALTER TABLE payment.refund DROP CONSTRAINT ck_refund_source;
ALTER TABLE payment.refund
    ADD COLUMN remainder_closure_id UUID,
    ADD CONSTRAINT ck_refund_source CHECK (source IS NULL OR source IN
        ('ORDER_CANCELLED', 'ORDER_REMAINDER_CLOSED', 'RETURN', 'MANUAL')),
    ADD CONSTRAINT ck_refund_remainder_closure CHECK
        ((source IS NOT DISTINCT FROM 'ORDER_REMAINDER_CLOSED') = (remainder_closure_id IS NOT NULL));
CREATE UNIQUE INDEX uk_refund_order_remainder_closed ON payment.refund (payment_id, remainder_closure_id)
    WHERE source = 'ORDER_REMAINDER_CLOSED';

-- ---- 5. the state machine, a second time -------------------------------------------------------------------------
CREATE TABLE ordering.order_status_transition
(
    from_status VARCHAR(32) NOT NULL,
    to_status   VARCHAR(32) NOT NULL,
    CONSTRAINT pk_order_status_transition PRIMARY KEY (from_status, to_status),
    CONSTRAINT ck_order_status_transition_change CHECK (from_status <> to_status)
);

COMMENT ON TABLE ordering.order_status_transition IS
    'The edges of OrderStatus.canTransitionTo; tg_order_status_transition refuses any other change of status.';

INSERT INTO ordering.order_status_transition (from_status, to_status) VALUES
    ('DRAFT', 'PENDING_PAYMENT'),
    ('DRAFT', 'CONFIRMED'),
    ('DRAFT', 'ON_HOLD'),
    ('DRAFT', 'CANCELLED'),
    ('PENDING_PAYMENT', 'CONFIRMED'),
    ('PENDING_PAYMENT', 'CANCELLED'),
    ('CONFIRMED', 'IN_PRODUCTION'),
    ('CONFIRMED', 'READY_TO_FULFILL'),
    ('CONFIRMED', 'ON_HOLD'),
    ('CONFIRMED', 'CANCELLED'),
    ('IN_PRODUCTION', 'READY_TO_FULFILL'),
    ('IN_PRODUCTION', 'ON_HOLD'),
    ('IN_PRODUCTION', 'CANCELLED'),
    ('READY_TO_FULFILL', 'PICKING'),
    ('READY_TO_FULFILL', 'PACKED'),
    ('READY_TO_FULFILL', 'ON_HOLD'),
    ('READY_TO_FULFILL', 'CANCELLED'),
    ('PICKING', 'PACKED'),
    ('PICKING', 'PARTIALLY_FULFILLED'),
    ('PICKING', 'ON_HOLD'),
    ('PICKING', 'CANCELLED'),
    ('PARTIALLY_FULFILLED', 'PACKED'),
    ('PARTIALLY_FULFILLED', 'SHIPPED'),
    ('PARTIALLY_FULFILLED', 'DELIVERY_FAILED'),
    ('PARTIALLY_FULFILLED', 'DELIVERED'),
    ('PARTIALLY_FULFILLED', 'RETURNED'),
    ('PARTIALLY_FULFILLED', 'ON_HOLD'),
    ('PACKED', 'SHIPPED'),
    ('PACKED', 'PARTIALLY_FULFILLED'),
    ('PACKED', 'ON_HOLD'),
    ('ON_HOLD', 'CONFIRMED'),
    ('ON_HOLD', 'PENDING_PAYMENT'),
    ('ON_HOLD', 'IN_PRODUCTION'),
    ('ON_HOLD', 'READY_TO_FULFILL'),
    ('ON_HOLD', 'PICKING'),
    ('ON_HOLD', 'PARTIALLY_FULFILLED'),
    ('ON_HOLD', 'PACKED'),
    ('ON_HOLD', 'CANCELLED'),
    ('SHIPPED', 'DELIVERED'),
    ('SHIPPED', 'DELIVERY_FAILED'),
    ('SHIPPED', 'PARTIALLY_FULFILLED'),
    ('DELIVERY_FAILED', 'DELIVERED'),
    ('DELIVERY_FAILED', 'RETURNED'),
    ('DELIVERY_FAILED', 'PARTIALLY_FULFILLED'),
    ('DELIVERED', 'COMPLETED'),
    ('DELIVERED', 'RETURN_REQUESTED'),
    ('RETURN_REQUESTED', 'RETURNED'),
    ('RETURN_REQUESTED', 'DELIVERED'),
    ('RETURNED', 'REFUNDED'),
    ('RETURNED', 'PARTIALLY_REFUNDED'),
    ('REFUNDED', 'CLOSED'),
    ('PARTIALLY_REFUNDED', 'CLOSED'),
    ('COMPLETED', 'CLOSED');

CREATE FUNCTION ordering.enforce_order_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ordering.order_status_transition t
                    WHERE t.from_status = OLD.status AND t.to_status = NEW.status) THEN
        RAISE EXCEPTION 'Order % cannot move from % to %', OLD.order_number, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'ON_HOLD' AND NEW.status <> 'CANCELLED'
       AND NEW.status IS DISTINCT FROM OLD.status_before_hold
       AND NOT (OLD.hold_reason = 'CREDIT' AND NEW.status IN ('CONFIRMED', 'PENDING_PAYMENT')) THEN
        RAISE EXCEPTION 'Order % leaves its hold for %, not for % where it was held from', OLD.order_number,
            NEW.status, COALESCE(OLD.status_before_hold, 'a credit decision')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_order_status_transition
    BEFORE UPDATE OF status ON ordering.customer_order
    FOR EACH ROW EXECUTE FUNCTION ordering.enforce_order_transition();
