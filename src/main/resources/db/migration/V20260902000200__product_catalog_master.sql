-- =============================================================================
-- Product: the product master — categories, products, variants, SKUs, print config.
-- Module: product   Aggregates: Category, Product, Variant, Sku, PrintConfig   ERD: prod__*
--
-- product-service owns these (docs/business-design/02-data-ownership.md). catalog and inventory
-- keep read-only replicas built from events. All FKs here stay within the product schema.
-- =============================================================================

CREATE TABLE product.category
(
    id               UUID         NOT NULL,
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    -- Self-reference for a category tree. Same schema, so a FK is allowed.
    parent_id        UUID,
    description      VARCHAR(1000),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_category PRIMARY KEY (id),
    CONSTRAINT uk_category_code UNIQUE (code),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES product.category (id)
);


CREATE TABLE product.product
(
    id               UUID          NOT NULL,
    code             VARCHAR(64)   NOT NULL,
    name             VARCHAR(300)  NOT NULL,
    category_id      UUID,
    description      VARCHAR(2000),
    status           VARCHAR(32)   NOT NULL,
    -- Whether this product can carry a custom print / 3D design (cups, packaging).
    customizable     BOOLEAN       NOT NULL DEFAULT FALSE,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT uk_product_code UNIQUE (code),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES product.category (id),
    CONSTRAINT ck_product_status CHECK (status IN ('DRAFT', 'ACTIVE', 'DISCONTINUED'))
);

CREATE INDEX ix_product_category ON product.product (category_id) WHERE category_id IS NOT NULL;


CREATE TABLE product.variant
(
    id               UUID         NOT NULL,
    product_id       UUID         NOT NULL,
    name             VARCHAR(200) NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_variant PRIMARY KEY (id),
    CONSTRAINT fk_variant_product FOREIGN KEY (product_id) REFERENCES product.product (id) ON DELETE CASCADE
);

CREATE INDEX ix_variant_product ON product.variant (product_id);


CREATE TABLE product.sku
(
    id               UUID         NOT NULL,
    variant_id       UUID         NOT NULL,
    -- The SKU string used everywhere else (inventory, order lines). One word per concept: "sku".
    code             VARCHAR(64)  NOT NULL,
    barcode          VARCHAR(64),
    lot_tracked      BOOLEAN      NOT NULL DEFAULT FALSE,
    unit_of_measure  VARCHAR(16)  NOT NULL DEFAULT 'EACH',

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_sku PRIMARY KEY (id),
    CONSTRAINT uk_sku_code UNIQUE (code),
    CONSTRAINT fk_sku_variant FOREIGN KEY (variant_id) REFERENCES product.variant (id) ON DELETE CASCADE
);

CREATE INDEX ix_sku_variant ON product.sku (variant_id);


CREATE TABLE product.print_config
(
    id               UUID          NOT NULL,
    product_id       UUID          NOT NULL,
    -- JSON print specification (surfaces, print methods, constraints). Stored as text for now.
    spec             VARCHAR(4000),
    model_3d_url     VARCHAR(500),
    active           BOOLEAN       NOT NULL DEFAULT TRUE,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_print_config PRIMARY KEY (id),
    CONSTRAINT fk_print_config_product FOREIGN KEY (product_id) REFERENCES product.product (id) ON DELETE CASCADE
);

CREATE INDEX ix_print_config_product ON product.print_config (product_id);

COMMENT ON TABLE product.product IS 'Product master. Owned by the product module.';
COMMENT ON TABLE product.sku IS 'The sellable/stockable unit. Its code is the SKU used across modules.';
