-- =============================================================================
-- Inventory: inter-warehouse transfer orders (docs 10).
-- Module: inventory   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase G
--
-- Stock leaves the source warehouse on dispatch and is "in transit, owned by the TO" until the
-- destination counts it in (BR-03, BR-06). The line holds the three quantities that story needs
-- - requested, shipped, received (+ damaged) - so a discrepancy is shipped - received - damaged,
-- visible per line (BR-05). Receiving at the destination is on the TO line itself, not a
-- procurement goods receipt: there is no purchase order and no supplier.
-- =============================================================================


CREATE TABLE inventory.transfer_order
(
    id                UUID           NOT NULL,
    transfer_number   VARCHAR(30)    NOT NULL,
    from_warehouse_id UUID           NOT NULL,
    to_warehouse_id   UUID           NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    reason            VARCHAR(16),
    expected_date     DATE,
    carrier           VARCHAR(120),
    tracking_number   VARCHAR(128),
    shipping_cost     NUMERIC(18, 2),
    submitted_by      UUID,
    submitted_at      TIMESTAMPTZ,
    approved_by       UUID,
    approved_at       TIMESTAMPTZ,
    dispatched_by     UUID,
    dispatched_at     TIMESTAMPTZ,
    received_at       TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    close_reason      VARCHAR(500),
    cancel_reason     VARCHAR(500),

    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_transfer_order PRIMARY KEY (id),
    CONSTRAINT uk_transfer_order_number UNIQUE (transfer_number),
    CONSTRAINT fk_transfer_order_from FOREIGN KEY (from_warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_order_to FOREIGN KEY (to_warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_order_submitted_by FOREIGN KEY (submitted_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_transfer_order_approved_by FOREIGN KEY (approved_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_transfer_order_dispatched_by FOREIGN KEY (dispatched_by) REFERENCES identity.app_user (id),
    -- BR-01: moving stock inside one warehouse is a move task (docs 11), not a transfer order.
    CONSTRAINT ck_transfer_order_warehouses CHECK (from_warehouse_id <> to_warehouse_id),
    CONSTRAINT ck_transfer_order_status CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED',
        'PICKING', 'IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'COMPLETED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_transfer_order_reason
        CHECK (reason IS NULL OR reason IN ('REBALANCING', 'DEMAND', 'CAMPAIGN', 'OVERFLOW', 'OTHER')),
    CONSTRAINT ck_transfer_order_cost CHECK (shipping_cost IS NULL OR shipping_cost >= 0),
    CONSTRAINT ck_transfer_order_four_eyes
        CHECK (approved_by IS NULL OR submitted_by IS NULL OR approved_by <> submitted_by),
    CONSTRAINT ck_transfer_order_approved CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_transfer_order_dispatched CHECK (status NOT IN
        ('IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'COMPLETED', 'CLOSED')
        OR (dispatched_by IS NOT NULL AND dispatched_at IS NOT NULL)),
    -- BR-04: cancel only before the goods left the source; afterwards only close.
    CONSTRAINT ck_transfer_order_cancelled CHECK (status <> 'CANCELLED'
        OR (cancel_reason IS NOT NULL AND dispatched_at IS NULL)),
    CONSTRAINT ck_transfer_order_closed CHECK (status NOT IN ('COMPLETED', 'CLOSED') OR closed_at IS NOT NULL),
    CONSTRAINT ck_transfer_order_short_close CHECK (status <> 'CLOSED' OR close_reason IS NOT NULL)
);

CREATE INDEX ix_transfer_order_from ON inventory.transfer_order (from_warehouse_id, status);
CREATE INDEX ix_transfer_order_to ON inventory.transfer_order (to_warehouse_id, status);


CREATE TABLE inventory.transfer_order_line
(
    id                UUID          NOT NULL,
    transfer_order_id UUID          NOT NULL,
    line_no           INTEGER       NOT NULL,
    sku               VARCHAR(64)   NOT NULL,
    lot_number        VARCHAR(64),
    requested_qty     INTEGER       NOT NULL,
    shipped_qty       INTEGER       NOT NULL DEFAULT 0,
    received_qty      INTEGER       NOT NULL DEFAULT 0,
    damaged_qty       INTEGER       NOT NULL DEFAULT 0,
    received_by       UUID,
    discrepancy_note  VARCHAR(1000),

    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_transfer_order_line PRIMARY KEY (id),
    CONSTRAINT uk_transfer_order_line_no UNIQUE (transfer_order_id, line_no),
    CONSTRAINT fk_transfer_order_line_order FOREIGN KEY (transfer_order_id)
        REFERENCES inventory.transfer_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_transfer_order_line_item FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_order_line_received_by FOREIGN KEY (received_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_transfer_order_line_no CHECK (line_no > 0),
    CONSTRAINT ck_transfer_order_line_qty CHECK (requested_qty > 0 AND shipped_qty >= 0
        AND received_qty >= 0 AND damaged_qty >= 0),
    CONSTRAINT ck_transfer_order_line_shipped CHECK (shipped_qty <= requested_qty),
    -- A destination surplus means the source miscounted: that is an adjustment at the source, not
    -- stock appearing from nowhere at the destination.
    CONSTRAINT ck_transfer_order_line_received CHECK (received_qty + damaged_qty <= shipped_qty),
    CONSTRAINT ck_transfer_order_line_discrepancy CHECK (
        received_qty + damaged_qty = shipped_qty OR received_qty + damaged_qty = 0
        OR discrepancy_note IS NOT NULL)
);

CREATE INDEX ix_transfer_order_line_item ON inventory.transfer_order_line (sku);


-- Putaway at the destination (docs 10 step 9) is a putaway task sourced from a TO line.
ALTER TABLE warehouse.putaway_task
    ADD COLUMN transfer_order_line_id UUID,
    ADD CONSTRAINT fk_putaway_task_transfer_line FOREIGN KEY (transfer_order_line_id)
        REFERENCES inventory.transfer_order_line (id) ON DELETE RESTRICT;
