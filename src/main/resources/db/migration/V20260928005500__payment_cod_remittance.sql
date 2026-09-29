-- =============================================================================
-- Payment: COD remittance reconciliation (docs 09 step 10, docs 15).
-- Module: payment   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase G
--
-- A carrier collects cash on delivery and pays it over in batches, minus its fees. The accountant
-- matches each batch against the shipments it covers (docs 09 BR-04: the collected amount must
-- equal what the order still owed). An order is "Completed" only once its COD money is in.
-- Permission resource: payment-cod-remittances (existing).
-- =============================================================================


CREATE TABLE payment.cod_remittance
(
    id                 UUID           NOT NULL,
    remittance_number  VARCHAR(30)    NOT NULL,
    carrier            VARCHAR(120)   NOT NULL,
    statement_ref      VARCHAR(128),
    period_from        DATE           NOT NULL,
    period_to          DATE           NOT NULL,
    currency           VARCHAR(3)     NOT NULL DEFAULT 'VND',
    expected_amount    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    fee_amount         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    received_amount    NUMERIC(18, 2),
    status             VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    received_at        TIMESTAMPTZ,
    reconciled_by      UUID,
    reconciled_at      TIMESTAMPTZ,
    note               VARCHAR(1000),

    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_cod_remittance PRIMARY KEY (id),
    CONSTRAINT uk_cod_remittance_number UNIQUE (remittance_number),
    CONSTRAINT fk_cod_remittance_reconciled_by FOREIGN KEY (reconciled_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_cod_remittance_period CHECK (period_to >= period_from),
    CONSTRAINT ck_cod_remittance_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_cod_remittance_amounts CHECK (expected_amount >= 0 AND fee_amount >= 0
        AND (received_amount IS NULL OR received_amount >= 0)),
    CONSTRAINT ck_cod_remittance_status
        CHECK (status IN ('PENDING', 'RECEIVED', 'RECONCILED', 'DISPUTED')),
    CONSTRAINT ck_cod_remittance_received CHECK (status = 'PENDING'
        OR (received_amount IS NOT NULL AND received_at IS NOT NULL)),
    CONSTRAINT ck_cod_remittance_reconciled CHECK (status <> 'RECONCILED'
        OR (reconciled_by IS NOT NULL AND reconciled_at IS NOT NULL))
);

CREATE INDEX ix_cod_remittance_carrier ON payment.cod_remittance (carrier, period_from);


CREATE TABLE payment.cod_remittance_line
(
    id               UUID           NOT NULL,
    remittance_id    UUID           NOT NULL,
    shipment_id      UUID           NOT NULL,
    payment_id       UUID,
    cod_amount       NUMERIC(18, 2) NOT NULL,
    remitted_amount  NUMERIC(18, 2) NOT NULL,
    fee_amount       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    match_status     VARCHAR(16)    NOT NULL,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_cod_remittance_line PRIMARY KEY (id),
    -- A shipment's cash is paid over once; a second statement listing it is a carrier error.
    CONSTRAINT uk_cod_remittance_line_shipment UNIQUE (shipment_id),
    CONSTRAINT fk_cod_remittance_line_remittance FOREIGN KEY (remittance_id)
        REFERENCES payment.cod_remittance (id) ON DELETE CASCADE,
    CONSTRAINT fk_cod_remittance_line_shipment FOREIGN KEY (shipment_id)
        REFERENCES fulfillment.shipment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cod_remittance_line_payment FOREIGN KEY (payment_id)
        REFERENCES payment.payment (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cod_remittance_line_amounts
        CHECK (cod_amount >= 0 AND remitted_amount >= 0 AND fee_amount >= 0),
    CONSTRAINT ck_cod_remittance_line_status CHECK (match_status IN ('MATCHED', 'SHORT', 'OVER', 'MISSING')),
    CONSTRAINT ck_cod_remittance_line_match CHECK (CASE match_status
        WHEN 'MATCHED' THEN remitted_amount + fee_amount = cod_amount
        WHEN 'SHORT'   THEN remitted_amount + fee_amount < cod_amount
        WHEN 'OVER'    THEN remitted_amount + fee_amount > cod_amount
        WHEN 'MISSING' THEN remitted_amount = 0
        END)
);

CREATE INDEX ix_cod_remittance_line_remittance ON payment.cod_remittance_line (remittance_id);
CREATE INDEX ix_cod_remittance_line_payment ON payment.cod_remittance_line (payment_id);
