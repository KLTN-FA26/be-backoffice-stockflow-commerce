-- Preserve legacy external URLs. New uploads store a key, never a signed URL.
ALTER TABLE product.product ADD COLUMN media_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE product.product_image
    ALTER COLUMN url DROP NOT NULL,
    ADD COLUMN storage_key VARCHAR(500),
    ADD COLUMN original_name VARCHAR(255),
    ADD COLUMN content_type VARCHAR(100),
    ADD COLUMN size_bytes BIGINT,
    ADD COLUMN stored_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_product_image_storage CHECK (
        (storage_key IS NULL AND url IS NOT NULL) OR
        (storage_key IS NOT NULL AND url IS NULL AND original_name IS NOT NULL
         AND content_type IS NOT NULL AND size_bytes IS NOT NULL AND size_bytes > 0
         AND stored_at IS NOT NULL));
CREATE UNIQUE INDEX uk_product_image_storage_key ON product.product_image(storage_key)
    WHERE storage_key IS NOT NULL;
