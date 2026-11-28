-- =============================================================================
-- The shortage choice card "Đơn thiếu N thùng" (kltn-docs 17 §4.14 steps 55-60, §5.6, BR-33; 16 §4.7, BR-15; 07 §4.4
-- step 11). Module: order (the card), chat (its message kind).
--
-- Why. An order held INVENTORY because a carton is missing and the shipping warehouse cannot replace it waited for Sales
-- to "decide with the customer" by hand. Now Sales send a card the system draws from the order - the batch, the SKUs,
-- the missing cartons, the four ways on (wait for production, reduce the cartons, ship part and close the rest, cancel)
-- with what each means for money and date - and the customer presses one. The press is the evidence of consent; Sales
-- carry it out later with the operations that exist (BE-2 and after).
--
-- Lifecycle (§5.6):  SENT --customer chooses--> ANSWERED --Sales execute--> EXECUTED
--                    SENT | ANSWERED --a newer card, or nothing left to decide--> SUPERSEDED | EXPIRED
-- One live card (SENT or ANSWERED) per order and delivery batch: uk_shortage_card_live (16 BR-15, 17 §4.14 step 60).
-- The card does not expire by time (D47): only by what happens to the order.
--
-- Every rule the aggregate states is stated again here as a CHECK (BE rule "every invariant twice").
-- =============================================================================

CREATE TABLE ordering.shortage_card (
    id                       UUID          NOT NULL,
    order_id                 UUID          NOT NULL,
    delivery_batch_id        UUID          NOT NULL,
    customer_id              UUID          NOT NULL,
    status                   VARCHAR(16)   NOT NULL,
    -- What the card offered, frozen at send: the options and, in amounts, the money and date figures each showed.
    options_offered          VARCHAR(32)[] NOT NULL,
    missing_cartons          INTEGER       NOT NULL,
    amounts                  JSONB         NOT NULL,
    -- D48: the expected delivery date of the wait option; staff alone see where it came from.
    expected_delivery_date   DATE,
    expected_date_source     VARCHAR(24),
    sales_note               VARCHAR(500),
    sent_by                  UUID          NOT NULL,
    sent_at                  TIMESTAMPTZ   NOT NULL,
    chosen_option            VARCHAR(32),
    chosen_by                UUID,
    chosen_at                TIMESTAMPTZ,
    customer_note            VARCHAR(500),
    execution_started_at     TIMESTAMPTZ,
    executed_by              UUID,
    executed_at              TIMESTAMPTZ,
    ended_reason             VARCHAR(32),
    superseded_by            UUID,
    chat_message_id          UUID,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_shortage_card PRIMARY KEY (id),
    CONSTRAINT fk_shortage_card_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shortage_card_batch FOREIGN KEY (delivery_batch_id)
        REFERENCES ordering.delivery_batch (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shortage_card_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shortage_card_sent_by FOREIGN KEY (sent_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_shortage_card_chosen_by FOREIGN KEY (chosen_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_shortage_card_executed_by FOREIGN KEY (executed_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_shortage_card_chat_message FOREIGN KEY (chat_message_id)
        REFERENCES chat.message (id) ON DELETE SET NULL,
    -- Deferred: the old card is marked superseded before the new one is inserted, because the live index allows one.
    CONSTRAINT fk_shortage_card_superseded_by FOREIGN KEY (superseded_by)
        REFERENCES ordering.shortage_card (id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,

    CONSTRAINT ck_shortage_card_status CHECK (status IN ('SENT', 'ANSWERED', 'EXECUTED', 'SUPERSEDED', 'EXPIRED')),
    CONSTRAINT ck_shortage_card_options CHECK (
        cardinality(options_offered) BETWEEN 1 AND 4
        AND options_offered <@ ARRAY['WAIT_FOR_PRODUCTION', 'REDUCE_CARTONS', 'SHIP_PART_CLOSE_REST',
                                     'CANCEL_ORDER']::VARCHAR[]),
    CONSTRAINT ck_shortage_card_missing CHECK (missing_cartons > 0),
    CONSTRAINT ck_shortage_card_amounts CHECK (jsonb_typeof(amounts) = 'array'),
    -- D48: the wait option carries its expected date, and says where it came from.
    CONSTRAINT ck_shortage_card_expected_date CHECK (
        ('WAIT_FOR_PRODUCTION' = ANY (options_offered)) = (expected_delivery_date IS NOT NULL)
        AND (expected_delivery_date IS NULL) = (expected_date_source IS NULL)
        AND (expected_date_source IS NULL OR expected_date_source IN ('PRODUCTION_ORDER', 'STANDARD_LEAD_TIME'))),
    CONSTRAINT ck_shortage_card_sales_note CHECK (sales_note IS NULL OR btrim(sales_note) <> ''),
    CONSTRAINT ck_shortage_card_customer_note CHECK (customer_note IS NULL OR btrim(customer_note) <> ''),
    CONSTRAINT ck_shortage_card_option_value CHECK (chosen_option IS NULL OR chosen_option IN (
        'WAIT_FOR_PRODUCTION', 'REDUCE_CARTONS', 'SHIP_PART_CLOSE_REST', 'CANCEL_ORDER')),
    -- The customer's press is the evidence: who, when, which of the offered options. Once made it is kept for good,
    -- whichever way the card ends (a card can expire after the press).
    CONSTRAINT ck_shortage_card_chosen CHECK (
        (chosen_option IS NULL) = (chosen_by IS NULL)
        AND (chosen_option IS NULL) = (chosen_at IS NULL)
        AND (chosen_option IS NULL OR chosen_option = ANY (options_offered))
        AND (customer_note IS NULL OR chosen_option IS NOT NULL)),
    CONSTRAINT ck_shortage_card_sent CHECK (status <> 'SENT' OR chosen_option IS NULL),
    CONSTRAINT ck_shortage_card_answered CHECK (status <> 'ANSWERED' OR chosen_option IS NOT NULL),
    CONSTRAINT ck_shortage_card_executed CHECK (
        (status = 'EXECUTED') = (executed_at IS NOT NULL)
        AND (executed_at IS NULL) = (executed_by IS NULL)
        AND (status <> 'EXECUTED' OR chosen_option IS NOT NULL)),
    CONSTRAINT ck_shortage_card_execution_started CHECK (
        execution_started_at IS NULL OR chosen_option IS NOT NULL),
    CONSTRAINT ck_shortage_card_ended CHECK (
        (status IN ('SUPERSEDED', 'EXPIRED')) = (ended_reason IS NOT NULL)
        AND (ended_reason IS NULL OR ended_reason IN ('REALLOCATED', 'HOLD_RESOLVED_ELSEWHERE', 'ORDER_CANCELLED',
                                                      'REMAINDER_CLOSED', 'DECIDED_WITHOUT_CARD', 'REPLACED'))
        AND ((status = 'SUPERSEDED') = (superseded_by IS NOT NULL))
        AND (status <> 'SUPERSEDED' OR ended_reason = 'REPLACED')
        AND (superseded_by IS NULL OR superseded_by <> id))
);

-- One live card per batch of an order (16 BR-15, 17 §4.14 step 60).
CREATE UNIQUE INDEX uk_shortage_card_live ON ordering.shortage_card (order_id, delivery_batch_id)
    WHERE status IN ('SENT', 'ANSWERED');
CREATE INDEX ix_shortage_card_order ON ordering.shortage_card (order_id, sent_at DESC);

COMMENT ON TABLE ordering.shortage_card IS
    'The card "Đơn thiếu N thùng" (17 §4.14, BR-33): sent by Sales into the customer''s chat for an order held INVENTORY '
    'by missing cartons; the customer chooses one of the offered options (the evidence of consent), Sales execute it. '
    'Never expires by time (D47).';
COMMENT ON COLUMN ordering.shortage_card.amounts IS
    'The money and date figures each offered option showed, frozen when the card was sent (the same array as the chat '
    'card''s options).';
COMMENT ON COLUMN ordering.shortage_card.expected_delivery_date IS
    'D48: when the wait option says delivery resumes; with the batch''s window, the evidence to move it (BR-28).';
COMMENT ON COLUMN ordering.shortage_card.expected_date_source IS
    'PRODUCTION_ORDER: the expected completion of an open production order covering the missing cartons; '
    'STANDARD_LEAD_TIME: the day sent plus the standard lead time. Shown to staff only.';
COMMENT ON COLUMN ordering.shortage_card.execution_started_at IS
    'SHIP_PART_CLOSE_REST: Sales started it (step 1); the card then expires only if the order is cancelled.';
COMMENT ON COLUMN ordering.shortage_card.chat_message_id IS
    'The chat message the card was posted as, to keep it in step; null until posted.';

-- The cartons the card is short of, per order line, frozen when it was sent: what the customer saw.
CREATE TABLE ordering.shortage_card_line (
    id                UUID         NOT NULL,
    card_id           UUID         NOT NULL,
    order_line_id     UUID         NOT NULL,
    sku               VARCHAR(64)  NOT NULL,
    sku_name          VARCHAR(255),
    batch_cartons     INTEGER      NOT NULL,
    available_cartons INTEGER      NOT NULL,
    missing_cartons   INTEGER      NOT NULL,
    units_per_carton  INTEGER      NOT NULL,

    CONSTRAINT pk_shortage_card_line PRIMARY KEY (id),
    CONSTRAINT uk_shortage_card_line UNIQUE (card_id, order_line_id),
    CONSTRAINT fk_shortage_card_line_card FOREIGN KEY (card_id)
        REFERENCES ordering.shortage_card (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shortage_card_line_order_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT,
    CONSTRAINT ck_shortage_card_line_cartons CHECK (
        batch_cartons > 0 AND available_cartons >= 0 AND missing_cartons > 0 AND units_per_carton > 0
        AND available_cartons + missing_cartons = batch_cartons)
);
CREATE INDEX ix_shortage_card_line_order_line ON ordering.shortage_card_line (order_line_id);

COMMENT ON TABLE ordering.shortage_card_line IS
    'One order line the shortage card is short of: its cartons in the batch, those available and those missing, '
    'frozen when the card was sent.';

-- The chat shows the card as a message of a kind of its own.
ALTER TABLE chat.message DROP CONSTRAINT ck_message_card_kind;
ALTER TABLE chat.message ADD CONSTRAINT ck_message_card_kind CHECK (card_kind IS NULL OR card_kind IN ('QUOTE_REQUEST',
    'QUOTE', 'CONTRACT', 'PAYMENT_INFO', 'PAYMENT_REMINDER', 'SAMPLE_APPROVAL', 'INVOICE', 'ORDER_STATUS',
    'PRODUCTION_PROGRESS', 'CSAT_SURVEY', 'SHORTAGE_CHOICE'));
