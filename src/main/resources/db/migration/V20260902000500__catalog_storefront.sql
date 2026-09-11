-- =============================================================================
-- Catalog: the storefront read side — published entries, promotions, pricing rules.
-- Module: catalog   ERD: (storefront projection)
--
-- catalog-service owns categories/PLP-PDP/SEO and keeps read replicas of product + ATP built from
-- events. sku and segment_id are cross-module references (plain columns, no FK).
-- =============================================================================

CREATE TABLE catalog.catalog_entry
(
    id               UUID          NOT NULL,
    sku              VARCHAR(64)   NOT NULL,
    title            VARCHAR(300)  NOT NULL,
    slug             VARCHAR(200)  NOT NULL,
    description      VARCHAR(2000),
    price            NUMERIC(18, 2),
    currency         VARCHAR(3)    NOT NULL DEFAULT 'VND',
    -- Replicated available-to-promise for the product page; may be a second stale (a replica).
    atp              INTEGER,
    published        BOOLEAN       NOT NULL DEFAULT FALSE,
    seo_title        VARCHAR(300),
    seo_description  VARCHAR(500),

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_catalog_entry PRIMARY KEY (id),
    CONSTRAINT uk_catalog_entry_sku UNIQUE (sku),
    CONSTRAINT uk_catalog_entry_slug UNIQUE (slug)
);


CREATE TABLE catalog.promotion
(
    id               UUID          NOT NULL,
    code             VARCHAR(64)   NOT NULL,
    name             VARCHAR(200)  NOT NULL,
    type             VARCHAR(32)   NOT NULL,
    value            NUMERIC(18, 2) NOT NULL,
    starts_at        TIMESTAMPTZ,
    ends_at          TIMESTAMPTZ,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_promotion PRIMARY KEY (id),
    CONSTRAINT uk_promotion_code UNIQUE (code),
    CONSTRAINT ck_promotion_type CHECK (type IN ('PERCENTAGE', 'FIXED_AMOUNT'))
);


CREATE TABLE catalog.pricing_rule
(
    id               UUID          NOT NULL,
    name             VARCHAR(200)  NOT NULL,
    -- Optional targeting: a specific sku and/or customer segment (cross-module, plain columns).
    sku              VARCHAR(64),
    segment_id       UUID,
    price            NUMERIC(18, 2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'VND',
    priority         INTEGER       NOT NULL DEFAULT 0,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_pricing_rule PRIMARY KEY (id),
    CONSTRAINT ck_pricing_rule_price CHECK (price >= 0)
);

CREATE INDEX ix_pricing_rule_sku ON catalog.pricing_rule (sku) WHERE sku IS NOT NULL;
