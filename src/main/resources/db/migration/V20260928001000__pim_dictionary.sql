-- =============================================================================
-- Product information management: the dictionaries (brands, categories, attributes) - EXPAND.
-- Module: product   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase P
--
-- New tables beside the old ones. product.category, product.product, product.variant, product.sku,
-- product.print_config and the image/gallery tables stay until the product module's code moves
-- over; ddl-auto=validate would refuse to boot otherwise. The new names are plural, so nothing
-- collides. The old tables go in db/pending/C1__contract_product.sql.
--
-- Conventions every table below follows (plan §1):
--   * id is application-assigned (UUIDv7, Identifiers.newId()); no DEFAULT on it.
--   * version / created_at / created_by / last_modified_at / last_modified_by are the BaseEntity
--     columns. created_at and version carry defaults so a child entity that does not extend
--     BaseEntity can still insert.
--   * enums are VARCHAR + CHECK, because every entity maps them @Enumerated(STRING). A native
--     Postgres enum would fail Hibernate's schema validation.
--   * a many-to-many table has its own id plus a unique pair, never a composite primary key.
-- =============================================================================


CREATE TABLE product.brands
(
    id               UUID         NOT NULL,
    code             VARCHAR(50)  NOT NULL,
    name             VARCHAR(255) NOT NULL,
    slug             VARCHAR(255) NOT NULL,
    logo_url         TEXT,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_brands PRIMARY KEY (id),
    CONSTRAINT uk_brands_code UNIQUE (code),
    CONSTRAINT uk_brands_slug UNIQUE (slug),
    CONSTRAINT ck_brands_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,49}$'),
    CONSTRAINT ck_brands_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);


-- A tree. path is the materialised chain of codes from the root ("/LIVING/SOFA"), so a subtree is
-- one "path LIKE" query; depth 0 is a root and only a root has no parent.
CREATE TABLE product.categories
(
    id               UUID         NOT NULL,
    parent_id        UUID,
    code             VARCHAR(50)  NOT NULL,
    name             VARCHAR(255) NOT NULL,
    slug             VARCHAR(255) NOT NULL,
    path             TEXT         NOT NULL,
    depth            INTEGER      NOT NULL DEFAULT 0,
    sort_order       INTEGER      NOT NULL DEFAULT 0,
    image_url        TEXT,
    seo_title        VARCHAR(255),
    seo_description  TEXT,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uk_categories_code UNIQUE (code),
    CONSTRAINT uk_categories_slug UNIQUE (slug),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES product.categories (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_categories_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,49}$'),
    CONSTRAINT ck_categories_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_categories_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_categories_root CHECK ((parent_id IS NULL) = (depth = 0)),
    CONSTRAINT ck_categories_depth CHECK (depth BETWEEN 0 AND 10),
    CONSTRAINT ck_categories_path CHECK (path LIKE '/%')
);

CREATE INDEX ix_categories_parent ON product.categories (parent_id);
CREATE INDEX ix_categories_path ON product.categories (path text_pattern_ops);


CREATE TABLE product.attributes
(
    id               UUID         NOT NULL,
    code             VARCHAR(50)  NOT NULL,
    name             VARCHAR(255) NOT NULL,
    data_type        VARCHAR(16)  NOT NULL,
    unit             VARCHAR(20),
    is_filterable    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_searchable    BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order       INTEGER      NOT NULL DEFAULT 0,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_attributes PRIMARY KEY (id),
    CONSTRAINT uk_attributes_code UNIQUE (code),
    CONSTRAINT ck_attributes_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_]{0,49}$'),
    CONSTRAINT ck_attributes_data_type
        CHECK (data_type IN ('TEXT', 'NUMBER', 'BOOLEAN', 'SELECT', 'MULTISELECT', 'COLOR'))
);


-- The allowed values of a SELECT / MULTISELECT / COLOR attribute.
CREATE TABLE product.attribute_values
(
    id               UUID          NOT NULL,
    attribute_id     UUID          NOT NULL,
    code             VARCHAR(50)   NOT NULL,
    label            VARCHAR(255)  NOT NULL,
    value_number     NUMERIC(18, 4),
    hex_color        VARCHAR(7),
    sort_order       INTEGER       NOT NULL DEFAULT 0,
    is_active        BOOLEAN       NOT NULL DEFAULT TRUE,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_attribute_values PRIMARY KEY (id),
    CONSTRAINT uk_attribute_values_code UNIQUE (attribute_id, code),
    CONSTRAINT fk_attribute_values_attribute FOREIGN KEY (attribute_id)
        REFERENCES product.attributes (id) ON DELETE RESTRICT,
    CONSTRAINT ck_attribute_values_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,49}$'),
    CONSTRAINT ck_attribute_values_hex CHECK (hex_color IS NULL OR hex_color ~ '^#[0-9A-F]{6}$')
);


-- Which attributes a category's products are expected to carry, and in which role by default.
CREATE TABLE product.category_attributes
(
    id               UUID        NOT NULL,
    category_id      UUID        NOT NULL,
    attribute_id     UUID        NOT NULL,
    default_role     VARCHAR(16) NOT NULL,
    is_required      BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_order       INTEGER     NOT NULL DEFAULT 0,

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_category_attributes PRIMARY KEY (id),
    CONSTRAINT uk_category_attributes_pair UNIQUE (category_id, attribute_id),
    CONSTRAINT fk_category_attributes_category FOREIGN KEY (category_id)
        REFERENCES product.categories (id) ON DELETE CASCADE,
    CONSTRAINT fk_category_attributes_attribute FOREIGN KEY (attribute_id)
        REFERENCES product.attributes (id) ON DELETE RESTRICT,
    CONSTRAINT ck_category_attributes_role CHECK (default_role IN ('VARIANT_AXIS', 'DESCRIPTIVE'))
);

CREATE INDEX ix_category_attributes_attribute ON product.category_attributes (attribute_id);
