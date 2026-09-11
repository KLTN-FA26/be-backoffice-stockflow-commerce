-- =============================================================================
-- Payment: payments taken against orders, and refunds reversing them.
-- Module: payment   ERD: pay__*
--
-- payment-service owns these; order replicates only the status via events. order_id is a
-- cross-module reference (plain UUID, no FK). refund->payment stays within the payment schema.
-- =============================================================================

CREATE TABLE payment.payment
(
    id               UUID          NOT NULL,
    order_id         UUID          NOT NULL,
    amount           NUMERIC(18, 2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'VND',
    method           VARCHAR(32)   NOT NULL,
    status           VARCHAR(32)   NOT NULL,
    provider_ref     VARCHAR(128),
    captured_at      TIMESTAMPTZ,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT ck_payment_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_method CHECK (method IN ('CARD', 'BANK_TRANSFER', 'COD', 'EWALLET')),
    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'CAPTURED', 'FAILED', 'REFUNDED'))
);

CREATE INDEX ix_payment_order ON payment.payment (order_id);

CREATE TABLE payment.refund
(
    id               UUID          NOT NULL,
    payment_id       UUID          NOT NULL,
    amount           NUMERIC(18, 2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'VND',
    reason           VARCHAR(255),
    status           VARCHAR(32)   NOT NULL,
    refunded_at      TIMESTAMPTZ,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_refund PRIMARY KEY (id),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment.payment (id),
    CONSTRAINT ck_refund_amount CHECK (amount >= 0),
    CONSTRAINT ck_refund_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_refund_payment ON payment.refund (payment_id);
