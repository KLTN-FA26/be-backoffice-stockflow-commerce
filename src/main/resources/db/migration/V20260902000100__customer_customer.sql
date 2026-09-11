-- =============================================================================
-- Customer: profiles, addresses and segments.
-- Module: customer   Aggregates: Customer, Segment, Address   ERD: cust__*
--
-- FKs here stay WITHIN the customer schema, which ADR-0005 allows. The only rule is that no FK
-- crosses INTO another module's schema; segment_id and customer_id below are same-schema.
-- =============================================================================

CREATE TABLE customer.segment
(
    id               UUID         NOT NULL,
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    description      VARCHAR(1000),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_segment PRIMARY KEY (id),
    CONSTRAINT uk_segment_code UNIQUE (code)
);

COMMENT ON TABLE customer.segment IS
    'A marketing/pricing grouping a customer can belong to. Owned by the customer module.';


CREATE TABLE customer.customer
(
    id               UUID         NOT NULL,
    full_name        VARCHAR(200) NOT NULL,
    email            VARCHAR(320) NOT NULL,
    phone            VARCHAR(32),
    -- Same-schema reference to customer.segment. Nullable: a customer need not be segmented.
    segment_id       UUID,
    status           VARCHAR(32)  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_customer PRIMARY KEY (id),
    CONSTRAINT uk_customer_email UNIQUE (email),
    CONSTRAINT fk_customer_segment
        FOREIGN KEY (segment_id) REFERENCES customer.segment (id),
    CONSTRAINT ck_customer_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED'))
);

CREATE INDEX ix_customer_segment ON customer.customer (segment_id) WHERE segment_id IS NOT NULL;

COMMENT ON TABLE customer.customer IS
    'A customer profile. Owned by the customer module; order snapshots name + address at checkout.';


CREATE TABLE customer.address
(
    id               UUID         NOT NULL,
    -- Same-schema reference to customer.customer.
    customer_id      UUID         NOT NULL,
    type             VARCHAR(32)  NOT NULL,
    recipient_name   VARCHAR(200),
    phone            VARCHAR(32),
    line1            VARCHAR(255) NOT NULL,
    line2            VARCHAR(255),
    ward             VARCHAR(120),
    district         VARCHAR(120),
    city             VARCHAR(120) NOT NULL,
    province         VARCHAR(120),
    country          VARCHAR(2)   NOT NULL DEFAULT 'VN',
    postal_code      VARCHAR(20),
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_address PRIMARY KEY (id),
    CONSTRAINT fk_address_customer
        FOREIGN KEY (customer_id) REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT ck_address_type CHECK (type IN ('SHIPPING', 'BILLING'))
);

CREATE INDEX ix_address_customer ON customer.address (customer_id);

COMMENT ON TABLE customer.address IS
    'A shipping or billing address belonging to one customer.';
