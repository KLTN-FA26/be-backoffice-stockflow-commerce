-- =============================================================================
-- Product information management: products, their attributes, and variants (= SKUs) - EXPAND.
-- Module: product   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase P
--
-- Two-level model (decision D1): a variant IS the sellable SKU; there is no separate sku table.
-- Physical and logistics data (weight, dimensions, packaging, storage class, lot/serial tracking,
-- unit of measure, barcode) is NOT here: it belongs to inventory.inventory_items, 1:1 with the
-- variant's sku (docs 01, BR-07/BR-08). A product carries selling content only.
--
-- products.brand (free text) from the old table is deliberately not carried over: brand_id is the
-- one source of truth (decision D2). See V20260928001000 for the conventions.
-- =============================================================================


CREATE TABLE product.products
(
    id                UUID         NOT NULL,
    brand_id          UUID,
    code              VARCHAR(50)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    name_en           VARCHAR(300),
    slug              VARCHAR(255) NOT NULL,
    short_description TEXT,
    description       TEXT,
    description_en    TEXT,
    tax_class         VARCHAR(32),
    kind              VARCHAR(16)  NOT NULL DEFAULT 'STANDARD',
    status            VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    rejection_reason  TEXT,
    seo_title         VARCHAR(255),
    seo_description   TEXT,
    submitted_by      UUID,
    submitted_at      TIMESTAMPTZ,
    approved_by       UUID,
    approved_at       TIMESTAMPTZ,
    published_at      TIMESTAMPTZ,
    discontinued_at   TIMESTAMPTZ,

    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT uk_products_code UNIQUE (code),
    CONSTRAINT uk_products_slug UNIQUE (slug),
    CONSTRAINT fk_products_brand FOREIGN KEY (brand_id) REFERENCES product.brands (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_products_submitted_by FOREIGN KEY (submitted_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_products_approved_by FOREIGN KEY (approved_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_products_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,49}$'),
    CONSTRAINT ck_products_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_products_tax_class
        CHECK (tax_class IS NULL OR tax_class IN ('STANDARD', 'REDUCED', 'EXEMPT')),
    CONSTRAINT ck_products_kind CHECK (kind IN ('STANDARD', 'CUSTOMIZABLE')),
    CONSTRAINT ck_products_status CHECK (status IN
        ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'PUBLISHED', 'INACTIVE', 'DISCONTINUED')),
    -- BR-PRD-003: the approver is never the person who submitted.
    CONSTRAINT ck_products_approver_not_submitter
        CHECK (approved_by IS NULL OR submitted_by IS NULL OR approved_by <> submitted_by),
    -- A status is only reachable with the evidence of the step that led to it.
    CONSTRAINT ck_products_submitted
        CHECK (status <> 'PENDING_APPROVAL' OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_products_approved CHECK (status NOT IN ('APPROVED', 'ACTIVE', 'PUBLISHED')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_products_published CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL),
    CONSTRAINT ck_products_discontinued
        CHECK (status <> 'DISCONTINUED' OR discontinued_at IS NOT NULL)
);

CREATE INDEX ix_products_status_published ON product.products (status, published_at);
CREATE INDEX ix_products_brand ON product.products (brand_id);


CREATE TABLE product.product_categories
(
    id               UUID        NOT NULL,
    product_id       UUID        NOT NULL,
    category_id      UUID        NOT NULL,
    is_primary       BOOLEAN     NOT NULL DEFAULT FALSE,

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_product_categories PRIMARY KEY (id),
    CONSTRAINT uk_product_categories_pair UNIQUE (product_id, category_id),
    CONSTRAINT fk_product_categories_product FOREIGN KEY (product_id)
        REFERENCES product.products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_categories_category FOREIGN KEY (category_id)
        REFERENCES product.categories (id) ON DELETE RESTRICT
);

CREATE INDEX ix_product_categories_category ON product.product_categories (category_id);
-- One primary category per product: breadcrumbs and the storefront URL are built from it.
CREATE UNIQUE INDEX uk_product_categories_primary ON product.product_categories (product_id)
    WHERE is_primary;


-- An attribute used by a product, in one role: VARIANT_AXIS (colour, size - each variant picks one
-- option) or DESCRIPTIVE (material, style - describes the product as a whole).
CREATE TABLE product.product_attributes
(
    id               UUID        NOT NULL,
    product_id       UUID        NOT NULL,
    attribute_id     UUID        NOT NULL,
    role             VARCHAR(16) NOT NULL,
    sort_order       INTEGER     NOT NULL DEFAULT 0,

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_product_attributes PRIMARY KEY (id),
    CONSTRAINT uk_product_attributes_pair UNIQUE (product_id, attribute_id),
    CONSTRAINT fk_product_attributes_product FOREIGN KEY (product_id)
        REFERENCES product.products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_attributes_attribute FOREIGN KEY (attribute_id)
        REFERENCES product.attributes (id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_attributes_role CHECK (role IN ('VARIANT_AXIS', 'DESCRIPTIVE'))
);

CREATE INDEX ix_product_attributes_attribute ON product.product_attributes (attribute_id);


-- The options a VARIANT_AXIS offers on this product (the product sells grey and beige, not every
-- colour in the dictionary). Role and attribute consistency: V20260928001300.
CREATE TABLE product.product_attribute_options
(
    id                   UUID        NOT NULL,
    product_attribute_id UUID        NOT NULL,
    attribute_value_id   UUID        NOT NULL,

    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_product_attribute_options PRIMARY KEY (id),
    CONSTRAINT uk_product_attribute_options_pair UNIQUE (product_attribute_id, attribute_value_id),
    CONSTRAINT fk_product_attribute_options_attr FOREIGN KEY (product_attribute_id)
        REFERENCES product.product_attributes (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_attribute_options_value FOREIGN KEY (attribute_value_id)
        REFERENCES product.attribute_values (id) ON DELETE RESTRICT
);

CREATE INDEX ix_product_attribute_options_value ON product.product_attribute_options (attribute_value_id);


-- The value of a DESCRIPTIVE attribute: a dictionary value (SELECT / MULTISELECT / COLOR, one row
-- per selected value) or exactly one typed scalar (TEXT / NUMBER / BOOLEAN).
CREATE TABLE product.product_descriptive_values
(
    id                   UUID           NOT NULL,
    product_attribute_id UUID           NOT NULL,
    attribute_value_id   UUID,
    value_text           TEXT,
    value_number         NUMERIC(18, 4),
    value_boolean        BOOLEAN,

    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_product_descriptive_values PRIMARY KEY (id),
    CONSTRAINT uk_product_descriptive_values_pair UNIQUE (product_attribute_id, attribute_value_id),
    CONSTRAINT fk_product_descriptive_values_attr FOREIGN KEY (product_attribute_id)
        REFERENCES product.product_attributes (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_descriptive_values_value FOREIGN KEY (attribute_value_id)
        REFERENCES product.attribute_values (id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_descriptive_values_one
        CHECK (num_nonnulls(attribute_value_id, value_text, value_number, value_boolean) = 1)
);

-- A scalar attribute has one value, not several. (The pair above cannot say this: its second
-- column is NULL for a scalar, and NULLs are distinct in a unique constraint.)
CREATE UNIQUE INDEX uk_product_descriptive_values_scalar
    ON product.product_descriptive_values (product_attribute_id) WHERE attribute_value_id IS NULL;


-- A variant is the sellable SKU. attribute_signature is the canonical, sorted "AXIS=VALUE|..."
-- string of its axis options, so two variants of one product can never have the same combination.
CREATE TABLE product.variants
(
    id                  UUID         NOT NULL,
    product_id          UUID         NOT NULL,
    sku                 VARCHAR(64)  NOT NULL,
    name                VARCHAR(255) NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    is_default          BOOLEAN      NOT NULL DEFAULT FALSE,
    attribute_signature VARCHAR(512) NOT NULL,
    position            INTEGER      NOT NULL DEFAULT 0,
    obsoleted_at        TIMESTAMPTZ,

    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_variants PRIMARY KEY (id),
    CONSTRAINT uk_variants_sku UNIQUE (sku),
    CONSTRAINT uk_variants_combination UNIQUE (product_id, attribute_signature),
    CONSTRAINT fk_variants_product FOREIGN KEY (product_id) REFERENCES product.products (id)
        ON DELETE RESTRICT,
    -- The same alphabet as inventory.stock_item.sku today (SOFA-3S-GREY).
    CONSTRAINT ck_variants_sku CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$'),
    CONSTRAINT ck_variants_status CHECK (status IN ('DRAFT', 'ACTIVE', 'BLOCKED', 'OBSOLETE')),
    CONSTRAINT ck_variants_obsoleted CHECK ((status = 'OBSOLETE') = (obsoleted_at IS NOT NULL))
);

CREATE INDEX ix_variants_product_status ON product.variants (product_id, status);
-- One default variant per product: what a listing shows before the shopper picks options.
CREATE UNIQUE INDEX uk_variants_default ON product.variants (product_id) WHERE is_default;


CREATE TABLE product.variant_attribute_values
(
    id                          UUID        NOT NULL,
    variant_id                  UUID        NOT NULL,
    product_attribute_option_id UUID        NOT NULL,

    version                     BIGINT      NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                  VARCHAR(100),
    last_modified_at            TIMESTAMPTZ,
    last_modified_by            VARCHAR(100),

    CONSTRAINT pk_variant_attribute_values PRIMARY KEY (id),
    CONSTRAINT uk_variant_attribute_values_pair UNIQUE (variant_id, product_attribute_option_id),
    CONSTRAINT fk_variant_attribute_values_variant FOREIGN KEY (variant_id)
        REFERENCES product.variants (id) ON DELETE CASCADE,
    CONSTRAINT fk_variant_attribute_values_option FOREIGN KEY (product_attribute_option_id)
        REFERENCES product.product_attribute_options (id) ON DELETE RESTRICT
);

CREATE INDEX ix_variant_attribute_values_option
    ON product.variant_attribute_values (product_attribute_option_id);
