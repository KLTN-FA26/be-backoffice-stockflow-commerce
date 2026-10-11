-- =============================================================================
-- Ordering: CONFIRMED instead of PAID, money received, and cancellations that reach the rest of the
-- system (SCRUM-460). Docs: kltn-docs 15 §4-5 (BR-02, BR-05), 17 §2, §4.4, §4.5 (BR-03), §5.
--
-- 1. kltn-docs 17 §5 has one state for "may go ahead": Confirmed — paid in full (prepaid), deposit
--    received, or credit within the limit. PAID becomes CONFIRMED; how much was paid is an amount
--    (paid_amount, paid_in_full_at) from which the payment status of 15 §5.2 is derived.
--    Orders past PENDING_PAYMENT on PREPAID terms were confirmed by a captured payment for their
--    total, so they are backfilled as paid in full; deposit orders as their deposit.
--    ordering.order_status_history is append-only and keeps the PAID it recorded.
-- 2. Every cancellation names a reason code; a fee may be kept from the money received for work
--    already done (15 BR-05: never more than was paid).
-- 3. ordering.order_payment: one row per PaymentCaptured counted, so a redelivered event is counted
--    once. ordering.order_cancellation_request: a customer's request once the order has gone too far
--    to cancel on their own; one pending per order.
-- 4. Followers of OrderCancelled: fulfillment picks and packs can be CANCELLED (a picked order's goods
--    must be put back); payment voids what was not paid and asks for a refund of the rest, once per
--    payment.
-- =============================================================================

-- ---- 1. CONFIRMED and the money received -------------------------------------
ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_status;

UPDATE ordering.customer_order SET status = 'CONFIRMED' WHERE status = 'PAID';

ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_status CHECK (status IN
    ('DRAFT', 'PENDING_PAYMENT', 'CONFIRMED', 'IN_PRODUCTION', 'READY_TO_FULFILL', 'IN_FULFILMENT', 'ON_HOLD',
     'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELLED', 'RETURNED'));

ALTER TABLE ordering.customer_order
    ADD COLUMN paid_amount     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN paid_in_full_at TIMESTAMPTZ;

UPDATE ordering.customer_order
SET paid_amount = total_amount,
    paid_in_full_at = COALESCE(last_modified_at, placed_at)
WHERE payment_term = 'PREPAID'
  AND status IN ('CONFIRMED', 'IN_PRODUCTION', 'READY_TO_FULFILL', 'IN_FULFILMENT', 'ON_HOLD',
                 'SHIPPED', 'DELIVERED', 'COMPLETED', 'RETURNED');

UPDATE ordering.customer_order
SET paid_amount = deposit_required
WHERE payment_term = 'DEPOSIT' AND deposit_received_at IS NOT NULL;

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_paid_amount CHECK (paid_amount >= 0),
    ADD CONSTRAINT ck_order_paid_in_full CHECK (paid_in_full_at IS NULL OR paid_amount >= total_amount),
    -- kltn-docs 15 BR-02: confirmed only on the money its terms require.
    ADD CONSTRAINT ck_order_confirmed_basis CHECK (status <> 'CONFIRMED'
        OR (payment_term = 'PREPAID' AND paid_in_full_at IS NOT NULL)
        OR (payment_term = 'DEPOSIT' AND deposit_received_at IS NOT NULL)
        OR payment_term = 'CREDIT');

-- ---- 2. how an order was cancelled -------------------------------------------
ALTER TABLE ordering.customer_order
    ADD COLUMN cancellation_reason_code     VARCHAR(32),
    ADD COLUMN cancellation_retained_amount NUMERIC(19, 4);

UPDATE ordering.customer_order
SET cancellation_reason_code = CASE
        WHEN cancellation_reason LIKE 'PAYMENT_FAILED%' THEN 'PAYMENT_FAILED'
        WHEN cancellation_reason LIKE 'RESERVATION_EXPIRED%' THEN 'PAYMENT_NOT_RECEIVED'
        WHEN cancellation_reason LIKE 'CUSTOMER_REQUEST%' THEN 'CUSTOMER_REQUEST'
        ELSE 'OTHER' END,
    cancellation_retained_amount = 0
WHERE status = 'CANCELLED';

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_cancellation_code CHECK ((status = 'CANCELLED') = (cancellation_reason_code IS NOT NULL)),
    ADD CONSTRAINT ck_order_cancellation_code_values CHECK (cancellation_reason_code IN
        ('CUSTOMER_REQUEST', 'PAYMENT_NOT_RECEIVED', 'PAYMENT_FAILED', 'OUT_OF_STOCK', 'SUSPECTED_FRAUD',
         'CREDIT_REJECTED', 'DUPLICATE_ORDER', 'PRODUCTION_ISSUE', 'OTHER')),
    -- kltn-docs 15 BR-05: what is kept never exceeds what was paid.
    ADD CONSTRAINT ck_order_cancellation_retained CHECK (cancellation_retained_amount IS NULL
        OR (status = 'CANCELLED' AND cancellation_retained_amount >= 0
            AND cancellation_retained_amount <= paid_amount));

-- ---- 3. payments counted, cancellation requests ------------------------------
CREATE TABLE ordering.order_payment
(
    payment_id  UUID           NOT NULL,
    order_id    UUID           NOT NULL,
    amount      NUMERIC(19, 4) NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    captured_at TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_order_payment PRIMARY KEY (payment_id),
    CONSTRAINT fk_order_payment_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT ck_order_payment_amount CHECK (amount > 0)
);

CREATE INDEX ix_order_payment_order ON ordering.order_payment (order_id);

CREATE TABLE ordering.order_cancellation_request
(
    id               UUID          NOT NULL,
    order_id         UUID          NOT NULL,
    requested_by     UUID,
    reason_code      VARCHAR(32)   NOT NULL,
    note             VARCHAR(1000),
    requested_at     TIMESTAMPTZ   NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    decided_by       UUID,
    decided_at       TIMESTAMPTZ,
    decision_note    VARCHAR(1000),
    retained_percent NUMERIC(5, 2),

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_order_cancellation_request PRIMARY KEY (id),
    CONSTRAINT fk_order_cancellation_request_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_cancellation_request_requested_by FOREIGN KEY (requested_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT fk_order_cancellation_request_decided_by FOREIGN KEY (decided_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_order_cancellation_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_order_cancellation_request_reason CHECK (reason_code IN
        ('CUSTOMER_REQUEST', 'PAYMENT_NOT_RECEIVED', 'PAYMENT_FAILED', 'OUT_OF_STOCK', 'SUSPECTED_FRAUD',
         'CREDIT_REJECTED', 'DUPLICATE_ORDER', 'PRODUCTION_ISSUE', 'OTHER')),
    CONSTRAINT ck_order_cancellation_request_other CHECK (reason_code <> 'OTHER' OR note IS NOT NULL),
    CONSTRAINT ck_order_cancellation_request_decided
        CHECK ((status = 'PENDING') = (decided_by IS NULL AND decided_at IS NULL)),
    CONSTRAINT ck_order_cancellation_request_rejected CHECK (status <> 'REJECTED' OR decision_note IS NOT NULL),
    CONSTRAINT ck_order_cancellation_request_retained CHECK (retained_percent IS NULL
        OR (status = 'APPROVED' AND retained_percent >= 0 AND retained_percent <= 100))
);

CREATE UNIQUE INDEX uk_order_cancellation_request_pending
    ON ordering.order_cancellation_request (order_id) WHERE status = 'PENDING';
CREATE INDEX ix_order_cancellation_request_status
    ON ordering.order_cancellation_request (status, requested_at DESC);

-- ---- 4. followers of OrderCancelled -------------------------------------------
ALTER TABLE fulfillment.pick DROP CONSTRAINT ck_pick_status;
ALTER TABLE fulfillment.pick
    ADD CONSTRAINT ck_pick_status CHECK (status IN ('PENDING', 'PICKING', 'PICKED', 'SHORT', 'CANCELLED')),
    -- Picked goods of a cancelled order are off their bins: someone must put them back (17 BR-03).
    ADD COLUMN needs_put_back BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_pick_put_back CHECK (NOT needs_put_back OR status = 'CANCELLED');

ALTER TABLE fulfillment.pack DROP CONSTRAINT ck_pack_status;
ALTER TABLE fulfillment.pack
    ADD CONSTRAINT ck_pack_status CHECK (status IN ('PENDING', 'PACKING', 'ON_HOLD', 'PACKED', 'CANCELLED'));

ALTER TABLE payment.payment DROP CONSTRAINT ck_payment_status;
ALTER TABLE payment.payment
    -- CANCELLED: a payment still awaited when its order was cancelled; nothing to refund.
    ADD CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'CAPTURED', 'FAILED', 'REFUNDED', 'CANCELLED'));

ALTER TABLE payment.refund
    ADD COLUMN order_id UUID,
    ADD COLUMN source   VARCHAR(24),
    ADD CONSTRAINT fk_refund_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_refund_source CHECK (source IS NULL OR source IN ('ORDER_CANCELLED', 'RETURN', 'MANUAL')),
    ADD CONSTRAINT ck_refund_order_cancelled CHECK (source IS DISTINCT FROM 'ORDER_CANCELLED' OR order_id IS NOT NULL);

-- One refund per payment of a cancelled order: a redelivered OrderCancelled asks once.
CREATE UNIQUE INDEX uk_refund_order_cancelled ON payment.refund (payment_id) WHERE source = 'ORDER_CANCELLED';
CREATE INDEX ix_refund_order ON payment.refund (order_id) WHERE order_id IS NOT NULL;
