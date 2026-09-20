ALTER TABLE product.product_image ADD COLUMN upload_key VARCHAR(300), ADD COLUMN checksum VARCHAR(64);
ALTER TABLE product.product_image ADD CONSTRAINT ck_product_image_checksum CHECK
    (checksum IS NULL OR checksum ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX uk_product_upload_key ON product.product_image(product_id, upload_key) WHERE upload_key IS NOT NULL;
