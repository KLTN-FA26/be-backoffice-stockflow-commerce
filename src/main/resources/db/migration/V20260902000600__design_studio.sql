-- =============================================================================
-- Design: customer design drafts and the immutable confirmed snapshots.
-- Module: design   ERD: design__*
--
-- design-service owns these. order stores only a snapshot id + checksum (never a copy: a copied
-- snapshot can silently diverge from what the customer approved). customer_id/product_id are
-- cross-module references (plain UUID, no FK).
-- =============================================================================

CREATE TABLE design.design_draft
(
    id               UUID          NOT NULL,
    customer_id      UUID          NOT NULL,
    product_id       UUID          NOT NULL,
    name             VARCHAR(200),
    spec             VARCHAR(4000),
    status           VARCHAR(32)   NOT NULL,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_design_draft PRIMARY KEY (id),
    CONSTRAINT ck_design_draft_status
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'))
);

CREATE INDEX ix_design_draft_customer ON design.design_draft (customer_id);

CREATE TABLE design.design_snapshot
(
    id               UUID          NOT NULL,
    draft_id         UUID          NOT NULL,
    -- Checksum of the immutable artifact; order stores this to detect divergence.
    checksum         VARCHAR(128)  NOT NULL,
    artifact_url     VARCHAR(500)  NOT NULL,
    confirmed_at     TIMESTAMPTZ   NOT NULL,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_design_snapshot PRIMARY KEY (id),
    CONSTRAINT uk_design_snapshot_checksum UNIQUE (checksum),
    CONSTRAINT fk_design_snapshot_draft
        FOREIGN KEY (draft_id) REFERENCES design.design_draft (id)
);
