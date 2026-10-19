-- =============================================================================
-- Production: goods a subcontractor delivered, counted once per goods receipt (SCRUM-441; 04c F-PROD G7/G9).
--
-- GoodsReceiptConfirmed may be delivered twice (the event publication registry retries), and a SUBCONTRACT
-- purchase order may be received in parts. Each receipt of a subcontracted child's PO is counted onto the
-- child once: the receipt id is the key.
-- =============================================================================

CREATE TABLE production.production_subcontract_receipt
(
    receipt_id          UUID          NOT NULL,
    production_order_id UUID          NOT NULL,
    quantity            INTEGER       NOT NULL,
    received_at         TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_production_subcontract_receipt PRIMARY KEY (receipt_id),
    CONSTRAINT fk_production_subcontract_receipt_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_production_subcontract_receipt_receipt FOREIGN KEY (receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_production_subcontract_receipt_quantity CHECK (quantity > 0)
);

CREATE INDEX ix_production_subcontract_receipt_order ON production.production_subcontract_receipt (production_order_id);
