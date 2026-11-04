-- =============================================================================
-- Procurement: replenishment proposals (MRP suggestions that become purchase orders) - EXPAND.
-- Module: procurement   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase R
--
-- The foreign keys from a proposal line to the purchase order it became are added in
-- V20260928002200, after purchase_orders exists.
-- =============================================================================


CREATE TABLE procurement.replenishment_proposals
(
    id               UUID        NOT NULL,
    code             VARCHAR(30) NOT NULL,
    warehouse_id     UUID        NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'DRAFT_PROPOSAL',
    origin           VARCHAR(8)  NOT NULL DEFAULT 'MRP',
    run_date         DATE        NOT NULL,
    reviewed_by      UUID,
    reviewed_at      TIMESTAMPTZ,
    converted_by     UUID,
    converted_at     TIMESTAMPTZ,
    note             TEXT,

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_replenishment_proposals PRIMARY KEY (id),
    CONSTRAINT uk_replenishment_proposals_code UNIQUE (code),
    CONSTRAINT fk_replenishment_proposals_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_replenishment_proposals_reviewed_by FOREIGN KEY (reviewed_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT fk_replenishment_proposals_converted_by FOREIGN KEY (converted_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_replenishment_proposals_status
        CHECK (status IN ('DRAFT_PROPOSAL', 'REVIEWED', 'CONVERTED', 'REJECTED')),
    CONSTRAINT ck_replenishment_proposals_origin CHECK (origin IN ('MRP', 'MANUAL')),
    CONSTRAINT ck_replenishment_proposals_reviewed CHECK (status NOT IN ('REVIEWED', 'REJECTED')
        OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CONSTRAINT ck_replenishment_proposals_converted
        CHECK (status <> 'CONVERTED' OR (converted_by IS NOT NULL AND converted_at IS NOT NULL))
);

CREATE INDEX ix_replenishment_proposals_warehouse ON procurement.replenishment_proposals (warehouse_id);
CREATE INDEX ix_replenishment_proposals_status ON procurement.replenishment_proposals (status, run_date);


CREATE TABLE procurement.replenishment_proposal_lines
(
    id                    UUID           NOT NULL,
    proposal_id           UUID           NOT NULL,
    inventory_item_id     UUID           NOT NULL,
    on_hand_qty           NUMERIC(18, 3) NOT NULL DEFAULT 0,
    on_order_qty          NUMERIC(18, 3) NOT NULL DEFAULT 0,
    reserved_qty          NUMERIC(18, 3) NOT NULL DEFAULT 0,
    reorder_point         NUMERIC(18, 3) NOT NULL,
    suggested_qty         NUMERIC(18, 3) NOT NULL,
    suggested_supplier_id UUID,
    converted             BOOLEAN        NOT NULL DEFAULT FALSE,
    converted_po_id       UUID,
    converted_po_line_id  UUID,

    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(100),
    last_modified_at      TIMESTAMPTZ,
    last_modified_by      VARCHAR(100),

    CONSTRAINT pk_replenishment_proposal_lines PRIMARY KEY (id),
    CONSTRAINT uk_replenishment_proposal_lines_item UNIQUE (proposal_id, inventory_item_id),
    CONSTRAINT fk_replenishment_proposal_lines_proposal FOREIGN KEY (proposal_id)
        REFERENCES procurement.replenishment_proposals (id) ON DELETE CASCADE,
    CONSTRAINT fk_replenishment_proposal_lines_supplier FOREIGN KEY (suggested_supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE SET NULL,
    CONSTRAINT ck_replenishment_proposal_lines_qty CHECK (on_hand_qty >= 0 AND on_order_qty >= 0
        AND reserved_qty >= 0 AND reorder_point >= 0 AND suggested_qty > 0),
    -- "converted" is a flag the screen filters on; it must agree with the link it summarises.
    CONSTRAINT ck_replenishment_proposal_lines_converted
        CHECK (converted = (converted_po_id IS NOT NULL)),
    CONSTRAINT ck_replenishment_proposal_lines_po_line
        CHECK (converted_po_line_id IS NULL OR converted_po_id IS NOT NULL)
);

CREATE INDEX ix_replenishment_proposal_lines_item ON procurement.replenishment_proposal_lines (inventory_item_id);
CREATE INDEX ix_replenishment_proposal_lines_supplier ON procurement.replenishment_proposal_lines (suggested_supplier_id);
CREATE INDEX ix_replenishment_proposal_lines_po ON procurement.replenishment_proposal_lines (converted_po_id);
CREATE INDEX ix_replenishment_proposal_lines_po_line ON procurement.replenishment_proposal_lines (converted_po_line_id);
