-- =============================================================================
-- Ordering: releasing an order to production (SCRUM-422 / 423). Docs: kltn-docs 17-order-management,
-- 19-production §4.1; BE 03-state-machines §9; BR-PRD-06/08/09.
--
--   IN_PRODUCTION     released by the Order Coordinator with lines to print; leaves only once every
--                     production order of the order has completed (BR-PRD-06).
--   READY_TO_FULFILL  everything is printed and in stock; fulfilment takes it on from here, as it
--                     takes a PAID stock-only order (03 §9).
--
-- The payment term is a fact of the order, copied when it is placed: a customer whose terms change
-- next month keeps this order on the terms it was sold on. Only what release needs is added here
-- (BR-PRD-09: a deposit order reaches production once the deposit is in). Credit limits and
-- receivables are SCRUM-427/431 and come with their own migration.
-- =============================================================================

ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_status;
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_status CHECK (status IN
    ('DRAFT', 'PENDING_PAYMENT', 'PAID', 'IN_PRODUCTION', 'READY_TO_FULFILL', 'IN_FULFILMENT', 'ON_HOLD',
     'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELLED', 'RETURNED'));

ALTER TABLE ordering.customer_order
    ADD COLUMN payment_term        VARCHAR(16)    NOT NULL DEFAULT 'PREPAID',
    ADD COLUMN deposit_required    NUMERIC(19, 4),
    ADD COLUMN deposit_received_at TIMESTAMPTZ,
    -- Release: the warehouse the order is produced and shipped from, when, and by whom.
    ADD COLUMN warehouse_id        UUID,
    ADD COLUMN released_at         TIMESTAMPTZ,
    ADD COLUMN released_by         UUID,
    ADD CONSTRAINT ck_order_payment_term CHECK (payment_term IN ('PREPAID', 'DEPOSIT', 'CREDIT')),
    -- BR-PAY-002: a deposit order states how much must arrive before production; other terms do not.
    ADD CONSTRAINT ck_order_deposit CHECK (
        (payment_term = 'DEPOSIT' AND deposit_required IS NOT NULL AND deposit_required > 0
             AND deposit_required <= total_amount)
     OR (payment_term <> 'DEPOSIT' AND deposit_required IS NULL AND deposit_received_at IS NULL)),
    ADD CONSTRAINT ck_order_released CHECK ((released_at IS NULL) = (released_by IS NULL)),
    ADD CONSTRAINT ck_order_released_warehouse CHECK (released_at IS NULL OR warehouse_id IS NOT NULL),
    -- Production starts only from a release. IN_FULFILMENT is not covered: orders reached it through
    -- fulfilment's own release before this column existed, and still may for stock-only orders.
    ADD CONSTRAINT ck_order_release_status
        CHECK (status NOT IN ('IN_PRODUCTION', 'READY_TO_FULFILL') OR released_at IS NOT NULL),
    -- BR-PRD-09: a deposit order reaches production only once the deposit is in.
    ADD CONSTRAINT ck_order_deposit_before_production
        CHECK (status <> 'IN_PRODUCTION' OR payment_term <> 'DEPOSIT' OR deposit_received_at IS NOT NULL),
    ADD CONSTRAINT fk_order_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_order_released_by FOREIGN KEY (released_by)
        REFERENCES identity.app_user (id);

CREATE INDEX ix_customer_order_warehouse ON ordering.customer_order (warehouse_id) WHERE warehouse_id IS NOT NULL;


-- What the order module learns from production, through events rather than by reading the production
-- schema (one schema per module). Both are written by listeners and keyed by the producer's id, so an
-- event delivered twice is recorded once.

-- SampleApproved: a customer approved the sample of a design on a blank. Release checks it for every
-- new design it sends to print (BR-PRD-08).
CREATE TABLE ordering.approved_sample
(
    sample_request_id  UUID         NOT NULL,
    customer_id        UUID         NOT NULL,
    design_snapshot_id UUID         NOT NULL,
    blank_sku          VARCHAR(64)  NOT NULL,
    approved_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_approved_sample PRIMARY KEY (sample_request_id),
    CONSTRAINT fk_approved_sample_request FOREIGN KEY (sample_request_id)
        REFERENCES production.sample_request (id) ON DELETE RESTRICT,
    CONSTRAINT fk_approved_sample_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_approved_sample_snapshot FOREIGN KEY (design_snapshot_id)
        REFERENCES design.design_snapshot (id) ON DELETE RESTRICT
);

CREATE INDEX ix_approved_sample_design ON ordering.approved_sample (design_snapshot_id, blank_sku);

-- ProductionCompleted: one row per finished production order of an order line. A line split between
-- the print shop and a subcontractor finishes in several rows; it is done when their good units cover
-- what was ordered (BR-PRD-06).
CREATE TABLE ordering.order_line_production
(
    production_order_id UUID         NOT NULL,
    order_line_id       UUID         NOT NULL,
    good_quantity       INTEGER      NOT NULL,
    completed_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_order_line_production PRIMARY KEY (production_order_id),
    CONSTRAINT fk_order_line_production_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_line_production_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE CASCADE,
    CONSTRAINT ck_order_line_production_quantity CHECK (good_quantity >= 0)
);

CREATE INDEX ix_order_line_production_line ON ordering.order_line_production (order_line_id);
