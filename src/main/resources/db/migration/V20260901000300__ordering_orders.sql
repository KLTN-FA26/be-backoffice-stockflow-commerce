-- =============================================================================
-- Ordering: customer orders and their lines.
-- Module: order   Aggregate: Order   BRD 3.14, 3.17
--
-- The schema is called "ordering" rather than "order" because ORDER is a reserved word in SQL and
-- a schema by that name would have to be quoted in every single statement. The module is still
-- named order; only the schema carries the workaround.
-- =============================================================================

CREATE TABLE ordering.customer_order
(
    id                  UUID           NOT NULL,
    order_number        VARCHAR(32)    NOT NULL,
    customer_id         UUID           NOT NULL,
    request_id          UUID           NOT NULL,
    status              VARCHAR(32)    NOT NULL,

    -- Denormalised sum of the lines, recomputed on every save. The orders list screen and every
    -- revenue report would otherwise join and aggregate to display one number per row.
    total_amount        NUMERIC(19, 4) NOT NULL,
    currency            VARCHAR(3)     NOT NULL,

    placed_at           TIMESTAMPTZ    NOT NULL,
    cancellation_reason VARCHAR(500),

    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL,
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_customer_order PRIMARY KEY (id),
    CONSTRAINT uk_order_number UNIQUE (order_number),
    -- Checkout idempotency, enforced where it cannot be raced past.
    CONSTRAINT uk_order_request UNIQUE (request_id),
    CONSTRAINT ck_order_total_non_negative CHECK (total_amount >= 0),
    CONSTRAINT ck_order_status CHECK (status IN
        ('DRAFT', 'PENDING_PAYMENT', 'PAID', 'IN_FULFILMENT', 'SHIPPED',
         'DELIVERED', 'COMPLETED', 'CANCELLED', 'RETURNED')),
    -- A cancelled order must record why. Anything else is an audit finding waiting to happen.
    CONSTRAINT ck_order_cancellation_reason
        CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
);

CREATE INDEX ix_order_customer ON ordering.customer_order (customer_id, placed_at DESC);
CREATE INDEX ix_order_status_placed ON ordering.customer_order (status, placed_at);

COMMENT ON COLUMN ordering.customer_order.request_id IS
    'Idempotency key from the client; a resubmitted checkout returns the original order.';


CREATE TABLE ordering.order_line
(
    id                 UUID           NOT NULL,
    order_id           UUID           NOT NULL,
    sku                VARCHAR(64)    NOT NULL,
    quantity           INTEGER        NOT NULL,
    -- VARCHAR(3), not CHAR(3): Hibernate maps a String to varchar, and ddl-auto=validate
    -- rejects a bpchar column as a type mismatch.
    -- Snapshot, not a lookup. The price the customer agreed to is a fact about this order and
    -- must not move when the price list is edited next week.
    unit_price         NUMERIC(19, 4) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    design_snapshot_id UUID,

    CONSTRAINT pk_order_line PRIMARY KEY (id),
    CONSTRAINT fk_order_line_order
        FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id) ON DELETE CASCADE,
    CONSTRAINT ck_order_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_line_unit_price CHECK (unit_price >= 0)
);

CREATE INDEX ix_order_line_order ON ordering.order_line (order_id);
CREATE INDEX ix_order_line_sku ON ordering.order_line (sku);


-- The stock holds backing one order line.
--
-- A child table rather than a reservation_id column, because one line is frequently drawn from
-- more than one lot - ten units as six from an older lot plus four from a newer one - and
-- inventory returns one hold per lot. A single column would keep the first and strand the rest:
-- cancelling the order would release six units and leave four held until they expired half an
-- hour later, which reads to everyone involved as stock going missing.
--
-- reservation_id references inventory.stock_reservation with no FOREIGN KEY, by the cross-schema
-- rule. The PRIMARY KEY over both columns is what stops the same hold being attached twice.
CREATE TABLE ordering.order_line_reservation
(
    order_line_id  UUID NOT NULL,
    reservation_id UUID NOT NULL,

    CONSTRAINT pk_order_line_reservation PRIMARY KEY (order_line_id, reservation_id),
    CONSTRAINT fk_line_reservation_line
        FOREIGN KEY (order_line_id) REFERENCES ordering.order_line (id) ON DELETE CASCADE
);

-- Answers "which order line is holding this reservation" for the reconciliation report.
CREATE INDEX ix_line_reservation_reservation
    ON ordering.order_line_reservation (reservation_id);


-- One row per day, incremented atomically to produce SO-20260907-000431.
-- A plain sequence cannot reset daily, and SELECT max(...) + 1 loses the race between two
-- simultaneous checkouts.
CREATE TABLE ordering.order_number_sequence
(
    sequence_date DATE   NOT NULL,
    last_value    BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_order_number_sequence PRIMARY KEY (sequence_date),
    CONSTRAINT ck_order_number_sequence_value CHECK (last_value >= 0)
);

COMMENT ON TABLE ordering.order_number_sequence IS
    'Daily counter for human-readable order numbers. Gaps are acceptable; duplicates are not.';
