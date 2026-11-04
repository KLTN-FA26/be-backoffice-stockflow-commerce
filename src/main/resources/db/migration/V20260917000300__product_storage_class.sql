-- Storage class per SKU (docs/warehouse/01-product-creation, 06-warehouse-map-slotting): decides
-- which kind of bin the goods may be put away into. Kept on the product row for now, next to the
-- other logistics data; the docs place it on Inventory Item, which is 1:1 with the SKU.

ALTER TABLE product.product
    ADD COLUMN storage_class VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

-- Rows that already carry a shipping flag start with the matching storage class; hazmat wins when
-- both are set because it is the stricter storage condition.
UPDATE product.product SET storage_class = 'OVERSIZE' WHERE oversized;
UPDATE product.product SET storage_class = 'HAZMAT' WHERE hazmat;

ALTER TABLE product.product
    ADD CONSTRAINT ck_product_storage_class
        CHECK (storage_class IN ('NORMAL', 'COLD', 'HAZMAT', 'FRAGILE', 'OVERSIZE'));
