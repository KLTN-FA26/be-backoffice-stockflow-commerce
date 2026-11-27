-- A product's own image: one per product, shown on the storefront's lists and category pages (owner decision
-- 2026-11-27, ADR-0012). The product page itself still leads with its variants' images (decision D5 stands for them:
-- product.media, many per variant, published four-eyes); this image is the product's face in a grouping, and is
-- shown as soon as the product is published, with no second person.
--
-- One image is a column set on the product row rather than a table: "at most one" is then the schema, not a unique
-- index to keep in step. The shape mirrors product.media's stored file: the private original (category
-- product-originals, claimed by the orphaned-file sweep through ProductMediaFileReferences), its SHA-256, and the
-- display renditions as a JSON array of {edge, width, height, file{key, ...}} (product-renditions, copied to the CDN
-- prefix product-images when the product is published).
--
-- Nothing holds rows to carry over: every product starts without one.

ALTER TABLE product.products
    ADD COLUMN image_key           varchar(500),
    ADD COLUMN image_original_name varchar(255),
    ADD COLUMN image_content_type  varchar(100),
    ADD COLUMN image_size_bytes    bigint,
    ADD COLUMN image_checksum      varchar(64),
    ADD COLUMN image_stored_at     timestamptz,
    ADD COLUMN image_renditions    jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN image_alt_text      varchar(255);

-- All of the image or none of it: an image is a stored original with at least one display rendition; no image is no
-- column set at all, alt text and renditions included. COALESCE(..., false) because a CHECK passes on NULL: without
-- it a NULL checksum or size would make the comparison NULL and slip through.
ALTER TABLE product.products
    ADD CONSTRAINT ck_products_image CHECK (
        (image_key IS NULL
            AND image_original_name IS NULL AND image_content_type IS NULL AND image_size_bytes IS NULL
            AND image_checksum IS NULL AND image_stored_at IS NULL AND image_alt_text IS NULL
            AND image_renditions = '[]'::jsonb)
        OR COALESCE(image_key LIKE 'product-originals/%'
            AND image_original_name IS NOT NULL AND image_content_type IS NOT NULL
            AND image_size_bytes > 0 AND image_stored_at IS NOT NULL
            AND image_checksum ~ '^[0-9a-f]{64}$'
            AND jsonb_typeof(image_renditions) = 'array' AND jsonb_array_length(image_renditions) > 0, false));

COMMENT ON COLUMN product.products.image_key IS
    'Storage key of the private original of the product''s own image (one per product), or null: its storefront card then shows its variants'' lead image';
COMMENT ON COLUMN product.products.image_renditions IS
    'Display renditions of the product''s image: [{edge, width, height, file{key, originalName, contentType, sizeBytes, storedAt}}]; copied to the CDN prefix while the product is published';
