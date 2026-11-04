ALTER TABLE product.product_image ADD COLUMN renditions JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE product.product_image ADD CONSTRAINT ck_product_renditions_array CHECK (jsonb_typeof(renditions) = 'array');
CREATE TABLE product.product_gallery (
    id UUID PRIMARY KEY REFERENCES product.product(id),
    working_items JSONB NOT NULL DEFAULT '[]'::jsonb,
    published_items JSONB NOT NULL DEFAULT '[]'::jsonb,
    edited_by UUID,
    approved_by UUID,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),
    CONSTRAINT ck_gallery_working CHECK (jsonb_typeof(working_items) = 'array' AND jsonb_array_length(working_items) <= 20),
    CONSTRAINT ck_gallery_published CHECK (jsonb_typeof(published_items) = 'array' AND jsonb_array_length(published_items) <= 20)
);
