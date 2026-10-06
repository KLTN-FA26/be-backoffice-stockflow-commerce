-- =============================================================================
-- Product information management: media and print-on-demand templates - EXPAND.
-- Module: product   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase P
--
-- Media and templates hang off the VARIANT, not the product (decision D5): the grey sofa and the
-- beige sofa have different photos, and a template's print area depends on the exact model.
--
-- media keeps the stored-file columns of the old product.product_image (PR #29: object storage
-- key, checksum, renditions, idempotent upload key), so the upload pipeline moves over unchanged.
-- The old working/published gallery JSON becomes one row per image with is_published.
-- =============================================================================


CREATE TABLE product.media
(
    id               UUID         NOT NULL,
    variant_id       UUID         NOT NULL,
    kind             VARCHAR(16)  NOT NULL DEFAULT 'IMAGE',
    -- Either an external url, or a file this system stored (storage_key + its metadata).
    url              TEXT,
    storage_key      VARCHAR(500),
    original_name    VARCHAR(255),
    content_type     VARCHAR(100),
    size_bytes       BIGINT,
    checksum         VARCHAR(64),
    stored_at        TIMESTAMPTZ,
    renditions       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    upload_key       VARCHAR(300),
    alt_text         VARCHAR(255),
    sort_order       INTEGER      NOT NULL DEFAULT 0,
    is_primary       BOOLEAN      NOT NULL DEFAULT FALSE,
    is_published     BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at     TIMESTAMPTZ,
    published_by     UUID,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_media PRIMARY KEY (id),
    CONSTRAINT uk_media_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_media_variant FOREIGN KEY (variant_id) REFERENCES product.variants (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_media_published_by FOREIGN KEY (published_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_media_kind CHECK (kind IN ('IMAGE', 'VIDEO', 'MODEL_3D')),
    CONSTRAINT ck_media_source CHECK (
        (storage_key IS NULL AND url IS NOT NULL)
        OR (storage_key IS NOT NULL AND original_name IS NOT NULL AND content_type IS NOT NULL
            AND size_bytes IS NOT NULL AND size_bytes > 0 AND stored_at IS NOT NULL)),
    CONSTRAINT ck_media_checksum CHECK (checksum IS NULL OR checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_media_renditions CHECK (jsonb_typeof(renditions) = 'array'),
    CONSTRAINT ck_media_published CHECK (NOT is_published OR published_at IS NOT NULL)
);

CREATE INDEX ix_media_variant_order ON product.media (variant_id, sort_order);
CREATE UNIQUE INDEX uk_media_primary ON product.media (variant_id) WHERE is_primary;
-- A retried upload with the same key lands on the same row instead of storing the file twice.
CREATE UNIQUE INDEX uk_media_upload_key ON product.media (upload_key) WHERE upload_key IS NOT NULL;


-- Print-on-demand: what a customer may put on a customizable variant (docs 12).
CREATE TABLE product.customization_templates
(
    id                   UUID         NOT NULL,
    variant_id           UUID         NOT NULL,
    code                 VARCHAR(50)  NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    template_2d_url      TEXT         NOT NULL,
    model_3d_url         TEXT,
    min_dpi              INTEGER      NOT NULL DEFAULT 150,
    color_mode           VARCHAR(8)   NOT NULL DEFAULT 'CMYK',
    allowed_file_formats TEXT[]       NOT NULL,
    max_file_size_mb     INTEGER      NOT NULL,
    max_colors           INTEGER,
    allowed_font_codes   TEXT[],
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,

    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_customization_templates PRIMARY KEY (id),
    CONSTRAINT uk_customization_templates_code UNIQUE (variant_id, code),
    CONSTRAINT fk_customization_templates_variant FOREIGN KEY (variant_id)
        REFERENCES product.variants (id) ON DELETE CASCADE,
    CONSTRAINT ck_customization_templates_dpi CHECK (min_dpi > 0),
    CONSTRAINT ck_customization_templates_color_mode CHECK (color_mode IN ('RGB', 'CMYK')),
    CONSTRAINT ck_customization_templates_formats CHECK (cardinality(allowed_file_formats) > 0),
    CONSTRAINT ck_customization_templates_size CHECK (max_file_size_mb > 0),
    CONSTRAINT ck_customization_templates_colors CHECK (max_colors IS NULL OR max_colors > 0)
);


-- A printable region of a template, in millimetres on the 2D template.
CREATE TABLE product.print_areas
(
    id               UUID           NOT NULL,
    template_id      UUID           NOT NULL,
    code             VARCHAR(50)    NOT NULL,
    name             VARCHAR(255)   NOT NULL,
    x_mm             NUMERIC(10, 2) NOT NULL,
    y_mm             NUMERIC(10, 2) NOT NULL,
    width_mm         NUMERIC(10, 2) NOT NULL,
    height_mm        NUMERIC(10, 2) NOT NULL,
    safe_margin_mm   NUMERIC(10, 2) NOT NULL DEFAULT 0,
    bleed_mm         NUMERIC(10, 2) NOT NULL DEFAULT 0,
    shape_json       JSONB,
    uv_mapping_json  JSONB,
    sort_order       INTEGER        NOT NULL DEFAULT 0,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_print_areas PRIMARY KEY (id),
    CONSTRAINT uk_print_areas_code UNIQUE (template_id, code),
    CONSTRAINT fk_print_areas_template FOREIGN KEY (template_id)
        REFERENCES product.customization_templates (id) ON DELETE CASCADE,
    CONSTRAINT ck_print_areas_position CHECK (x_mm >= 0 AND y_mm >= 0),
    CONSTRAINT ck_print_areas_size CHECK (width_mm > 0 AND height_mm > 0),
    CONSTRAINT ck_print_areas_margins CHECK (safe_margin_mm >= 0 AND bleed_mm >= 0
        AND 2 * safe_margin_mm < LEAST(width_mm, height_mm))
);
