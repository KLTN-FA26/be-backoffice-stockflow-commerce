-- =============================================================================
-- Procurement: suppliers, purchase orders, goods receipt, QC, supplier invoices.
-- Module: procurement   ERD: proc__*
--
-- procurement-service owns all of these. sku on po_line and product references are cross-module,
-- so they are plain VARCHAR/UUID columns with no FK. FKs below stay within the procurement schema.
-- =============================================================================

CREATE TABLE procurement.supplier
(
    id               UUID         NOT NULL,
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    email            VARCHAR(320),
    phone            VARCHAR(32),
    tax_code         VARCHAR(32),
    status           VARCHAR(32)  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_supplier PRIMARY KEY (id),
    CONSTRAINT uk_supplier_code UNIQUE (code),
    CONSTRAINT ck_supplier_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);


CREATE TABLE procurement.purchase_order
(
    id               UUID          NOT NULL,
    po_number        VARCHAR(64)   NOT NULL,
    supplier_id      UUID          NOT NULL,
    status           VARCHAR(32)   NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'VND',
    total_amount     NUMERIC(18, 2) NOT NULL DEFAULT 0,
    expected_at      DATE,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_purchase_order PRIMARY KEY (id),
    CONSTRAINT uk_purchase_order_number UNIQUE (po_number),
    CONSTRAINT fk_purchase_order_supplier
        FOREIGN KEY (supplier_id) REFERENCES procurement.supplier (id),
    CONSTRAINT ck_purchase_order_total CHECK (total_amount >= 0),
    CONSTRAINT ck_purchase_order_status
        CHECK (status IN ('DRAFT', 'APPROVED', 'SENT', 'PARTIALLY_RECEIVED',
                          'CLOSED', 'CLOSED_SHORT', 'CANCELLED'))
);

CREATE INDEX ix_purchase_order_supplier ON procurement.purchase_order (supplier_id);


CREATE TABLE procurement.po_line
(
    id                 UUID          NOT NULL,
    purchase_order_id  UUID          NOT NULL,
    -- Cross-module reference to product.sku by its code. Plain column, no FK.
    sku                VARCHAR(64)   NOT NULL,
    description        VARCHAR(300),
    quantity_ordered   INTEGER       NOT NULL,
    quantity_received  INTEGER       NOT NULL DEFAULT 0,
    unit_price         NUMERIC(18, 2) NOT NULL DEFAULT 0,

    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL,
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_po_line PRIMARY KEY (id),
    CONSTRAINT fk_po_line_order
        FOREIGN KEY (purchase_order_id) REFERENCES procurement.purchase_order (id) ON DELETE CASCADE,
    CONSTRAINT ck_po_line_qty_ordered CHECK (quantity_ordered > 0),
    CONSTRAINT ck_po_line_qty_received CHECK (quantity_received >= 0)
);

CREATE INDEX ix_po_line_order ON procurement.po_line (purchase_order_id);


CREATE TABLE procurement.goods_receipt
(
    id                 UUID         NOT NULL,
    purchase_order_id  UUID         NOT NULL,
    receipt_number     VARCHAR(64)  NOT NULL,
    received_at        TIMESTAMPTZ  NOT NULL,
    status             VARCHAR(32)  NOT NULL,

    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_goods_receipt PRIMARY KEY (id),
    CONSTRAINT uk_goods_receipt_number UNIQUE (receipt_number),
    CONSTRAINT fk_goods_receipt_order
        FOREIGN KEY (purchase_order_id) REFERENCES procurement.purchase_order (id),
    CONSTRAINT ck_goods_receipt_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX ix_goods_receipt_order ON procurement.goods_receipt (purchase_order_id);


CREATE TABLE procurement.qc_result
(
    id                 UUID         NOT NULL,
    goods_receipt_id   UUID         NOT NULL,
    outcome            VARCHAR(32)  NOT NULL,
    quantity_passed    INTEGER      NOT NULL DEFAULT 0,
    quantity_failed    INTEGER      NOT NULL DEFAULT 0,
    notes              VARCHAR(1000),
    inspected_at       TIMESTAMPTZ,

    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_qc_result PRIMARY KEY (id),
    CONSTRAINT fk_qc_result_receipt
        FOREIGN KEY (goods_receipt_id) REFERENCES procurement.goods_receipt (id) ON DELETE CASCADE,
    CONSTRAINT ck_qc_outcome CHECK (outcome IN ('PASS', 'FAIL', 'PARTIAL')),
    CONSTRAINT ck_qc_qty_passed CHECK (quantity_passed >= 0),
    CONSTRAINT ck_qc_qty_failed CHECK (quantity_failed >= 0)
);

CREATE INDEX ix_qc_result_receipt ON procurement.qc_result (goods_receipt_id);


CREATE TABLE procurement.supplier_invoice
(
    id                 UUID          NOT NULL,
    supplier_id        UUID          NOT NULL,
    purchase_order_id  UUID,
    invoice_number     VARCHAR(64)   NOT NULL,
    amount             NUMERIC(18, 2) NOT NULL DEFAULT 0,
    currency           VARCHAR(3)    NOT NULL DEFAULT 'VND',
    status             VARCHAR(32)   NOT NULL,

    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL,
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_supplier_invoice PRIMARY KEY (id),
    CONSTRAINT uk_supplier_invoice_number UNIQUE (invoice_number),
    CONSTRAINT fk_supplier_invoice_supplier
        FOREIGN KEY (supplier_id) REFERENCES procurement.supplier (id),
    CONSTRAINT fk_supplier_invoice_order
        FOREIGN KEY (purchase_order_id) REFERENCES procurement.purchase_order (id),
    CONSTRAINT ck_supplier_invoice_amount CHECK (amount >= 0),
    CONSTRAINT ck_supplier_invoice_status
        CHECK (status IN ('RECEIVED', 'MATCHED', 'DISPUTED', 'VOID', 'PAID'))
);

CREATE INDEX ix_supplier_invoice_supplier ON procurement.supplier_invoice (supplier_id);

COMMENT ON TABLE procurement.purchase_order IS 'A purchase order to a supplier. Owned by procurement.';
COMMENT ON TABLE procurement.goods_receipt IS 'Receipt of goods against a PO; quantities replicate to inventory.';
