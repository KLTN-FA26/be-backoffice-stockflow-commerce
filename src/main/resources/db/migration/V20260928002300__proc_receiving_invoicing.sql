-- =============================================================================
-- Procurement: goods receipts, QC inspections and supplier invoices - EXPAND.
-- Module: procurement   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase R
--
-- A receipt is always against a PO revision (docs 03): what was approved is what is received
-- against. Cross-row rules (the line's item is the PO line's item, the receipt stays inside the
-- over-receipt tolerance, QC never inspects more than was received) are in V20260928002400.
-- =============================================================================


CREATE TABLE procurement.goods_receipts
(
    id               UUID         NOT NULL,
    receipt_number   VARCHAR(30)  NOT NULL,
    po_id            UUID         NOT NULL,
    po_revision_id   UUID         NOT NULL,
    warehouse_id     UUID         NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    received_at      TIMESTAMPTZ  NOT NULL,
    received_by      UUID         NOT NULL,
    posted_at        TIMESTAMPTZ,
    posted_by        UUID,
    delivery_note    VARCHAR(100),
    note             TEXT,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_goods_receipts PRIMARY KEY (id),
    CONSTRAINT uk_goods_receipts_number UNIQUE (receipt_number),
    CONSTRAINT fk_goods_receipts_po FOREIGN KEY (po_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipts_revision FOREIGN KEY (po_revision_id)
        REFERENCES procurement.purchase_order_revisions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipts_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipts_received_by FOREIGN KEY (received_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_goods_receipts_posted_by FOREIGN KEY (posted_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_goods_receipts_status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    CONSTRAINT ck_goods_receipts_posted
        CHECK (status <> 'POSTED' OR (posted_by IS NOT NULL AND posted_at IS NOT NULL))
);

CREATE INDEX ix_goods_receipts_po ON procurement.goods_receipts (po_id);
CREATE INDEX ix_goods_receipts_warehouse ON procurement.goods_receipts (warehouse_id);
CREATE INDEX ix_goods_receipts_status ON procurement.goods_receipts (status, received_at);


CREATE TABLE procurement.goods_receipt_lines
(
    id                UUID           NOT NULL,
    receipt_id        UUID           NOT NULL,
    po_line_id        UUID           NOT NULL,
    inventory_item_id UUID           NOT NULL,
    -- Where the goods were set down on arrival (usually a RECEIVING area); putaway moves them on.
    location_id       UUID           NOT NULL,
    received_qty      NUMERIC(18, 3) NOT NULL,
    lot_number        VARCHAR(64),
    expiry_date       DATE,
    note              VARCHAR(255),

    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_goods_receipt_lines PRIMARY KEY (id),
    CONSTRAINT fk_goods_receipt_lines_receipt FOREIGN KEY (receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE CASCADE,
    CONSTRAINT fk_goods_receipt_lines_po_line FOREIGN KEY (po_line_id)
        REFERENCES procurement.purchase_order_lines (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_lines_location FOREIGN KEY (location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT,
    CONSTRAINT ck_goods_receipt_lines_qty CHECK (received_qty > 0)
);

CREATE INDEX ix_goods_receipt_lines_receipt ON procurement.goods_receipt_lines (receipt_id);
CREATE INDEX ix_goods_receipt_lines_po_line ON procurement.goods_receipt_lines (po_line_id);
CREATE INDEX ix_goods_receipt_lines_item ON procurement.goods_receipt_lines (inventory_item_id);
-- One line per PO line and lot on a receipt. COALESCE because NULLs are distinct in a unique index
-- and an untracked item has no lot: without it the same PO line could be received twice on one
-- receipt.
CREATE UNIQUE INDEX uk_goods_receipt_lines_lot
    ON procurement.goods_receipt_lines (receipt_id, po_line_id, COALESCE(lot_number, ''));


CREATE TABLE procurement.qc_inspections
(
    id                 UUID           NOT NULL,
    receipt_line_id    UUID           NOT NULL,
    outcome            VARCHAR(16)    NOT NULL,
    quantity           NUMERIC(18, 3) NOT NULL,
    target_location_id UUID,
    reason             VARCHAR(500),
    inspected_by       UUID           NOT NULL,
    inspected_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_qc_inspections PRIMARY KEY (id),
    CONSTRAINT fk_qc_inspections_line FOREIGN KEY (receipt_line_id)
        REFERENCES procurement.goods_receipt_lines (id) ON DELETE CASCADE,
    CONSTRAINT fk_qc_inspections_location FOREIGN KEY (target_location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT,
    CONSTRAINT fk_qc_inspections_inspected_by FOREIGN KEY (inspected_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_qc_inspections_outcome CHECK (outcome IN ('ACCEPTED', 'QUARANTINE', 'REJECTED')),
    CONSTRAINT ck_qc_inspections_qty CHECK (quantity > 0),
    -- Quarantined goods must be somewhere known; a rejection must say why.
    CONSTRAINT ck_qc_inspections_quarantine
        CHECK (outcome <> 'QUARANTINE' OR target_location_id IS NOT NULL),
    CONSTRAINT ck_qc_inspections_reason CHECK (outcome = 'ACCEPTED' OR reason IS NOT NULL)
);

CREATE INDEX ix_qc_inspections_line ON procurement.qc_inspections (receipt_line_id);
CREATE INDEX ix_qc_inspections_outcome ON procurement.qc_inspections (outcome);


CREATE TABLE procurement.supplier_invoices
(
    id               UUID           NOT NULL,
    invoice_number   VARCHAR(64)    NOT NULL,
    supplier_id      UUID           NOT NULL,
    po_id            UUID,
    status           VARCHAR(16)    NOT NULL DEFAULT 'RECEIVED',
    currency         VARCHAR(3)     NOT NULL DEFAULT 'VND',
    invoice_date     DATE           NOT NULL,
    due_date         DATE,
    subtotal         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    tax_total        NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_amount     NUMERIC(18, 2) NOT NULL DEFAULT 0,
    file_url         TEXT,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_supplier_invoices PRIMARY KEY (id),
    -- The supplier's own number: unique per supplier, not globally.
    CONSTRAINT uk_supplier_invoices_number UNIQUE (supplier_id, invoice_number),
    CONSTRAINT fk_supplier_invoices_supplier FOREIGN KEY (supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_supplier_invoices_po FOREIGN KEY (po_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE RESTRICT,
    CONSTRAINT ck_supplier_invoices_status
        CHECK (status IN ('RECEIVED', 'MATCHED', 'DISPUTED', 'VOID', 'PAID')),
    CONSTRAINT ck_supplier_invoices_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_supplier_invoices_dates CHECK (due_date IS NULL OR due_date >= invoice_date),
    CONSTRAINT ck_supplier_invoices_amounts CHECK (subtotal >= 0 AND tax_total >= 0
        AND total_amount = subtotal + tax_total)
);

CREATE INDEX ix_supplier_invoices_po ON procurement.supplier_invoices (po_id);
CREATE INDEX ix_supplier_invoices_status ON procurement.supplier_invoices (status);


CREATE TABLE procurement.supplier_invoice_lines
(
    id               UUID           NOT NULL,
    invoice_id       UUID           NOT NULL,
    po_line_id       UUID           NOT NULL,
    receipt_line_id  UUID,
    invoiced_qty     NUMERIC(18, 3) NOT NULL,
    unit_price       NUMERIC(18, 2) NOT NULL,
    tax_rate         NUMERIC(5, 2)  NOT NULL DEFAULT 0,
    line_total       NUMERIC(18, 2) NOT NULL DEFAULT 0,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_supplier_invoice_lines PRIMARY KEY (id),
    CONSTRAINT fk_supplier_invoice_lines_invoice FOREIGN KEY (invoice_id)
        REFERENCES procurement.supplier_invoices (id) ON DELETE CASCADE,
    CONSTRAINT fk_supplier_invoice_lines_po_line FOREIGN KEY (po_line_id)
        REFERENCES procurement.purchase_order_lines (id) ON DELETE RESTRICT,
    CONSTRAINT fk_supplier_invoice_lines_receipt_line FOREIGN KEY (receipt_line_id)
        REFERENCES procurement.goods_receipt_lines (id) ON DELETE RESTRICT,
    CONSTRAINT ck_supplier_invoice_lines_qty CHECK (invoiced_qty > 0),
    CONSTRAINT ck_supplier_invoice_lines_price CHECK (unit_price >= 0),
    CONSTRAINT ck_supplier_invoice_lines_tax CHECK (tax_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_supplier_invoice_lines_total CHECK (line_total >= 0)
);

CREATE INDEX ix_supplier_invoice_lines_invoice ON procurement.supplier_invoice_lines (invoice_id);
CREATE INDEX ix_supplier_invoice_lines_po_line ON procurement.supplier_invoice_lines (po_line_id);
CREATE INDEX ix_supplier_invoice_lines_receipt_line ON procurement.supplier_invoice_lines (receipt_line_id);
