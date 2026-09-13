-- SCRUM-74 (WBS 3.1.3.1) — physical logistics data on the product master, needed for
-- shipping-rate/carton-assignment calculations later (Sprint 5 packing). Nullable: a product can
-- exist before its dimensions are known, same treatment brand/tax_class got in
-- V20260903000300__product_master_data_fields.sql.

ALTER TABLE product.product
    ADD COLUMN weight_kg  NUMERIC(10, 3),
    ADD COLUMN length_cm  NUMERIC(10, 2),
    ADD COLUMN width_cm   NUMERIC(10, 2),
    ADD COLUMN height_cm  NUMERIC(10, 2);

ALTER TABLE product.product
    ADD CONSTRAINT ck_product_weight_positive
        CHECK (weight_kg IS NULL OR weight_kg > 0);

ALTER TABLE product.product
    ADD CONSTRAINT ck_product_dimensions_positive
        CHECK ((length_cm IS NULL OR length_cm > 0)
           AND (width_cm IS NULL OR width_cm > 0)
           AND (height_cm IS NULL OR height_cm > 0));
