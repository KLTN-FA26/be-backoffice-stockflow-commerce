ALTER TABLE ordering.order_line ADD COLUMN design_checksum VARCHAR(64);
ALTER TABLE ordering.order_line ADD CONSTRAINT ck_order_design_checksum CHECK
    (design_checksum IS NULL OR (design_snapshot_id IS NOT NULL AND design_checksum ~ '^[0-9a-f]{64}$'));
-- Legacy order lines remain readable; checksum is populated only by verified snapshot use cases.
