-- =============================================================================
-- Warehouse: warehouses, their locations, putaway tasks and slotting rules.
-- Module: warehouse   Aggregates: Warehouse, Location, PutawayTask, SlottingRule   ERD: wh__*
--
-- warehouse-service owns these. inventory keeps only the location code as a replica. goods_receipt_id
-- on putaway_task references procurement across schemas, so it is a plain UUID (no FK).
-- =============================================================================

CREATE TABLE warehouse.warehouse
(
    id               UUID         NOT NULL,
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    address_line     VARCHAR(255),
    city             VARCHAR(120),
    status           VARCHAR(32)  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_warehouse PRIMARY KEY (id),
    CONSTRAINT uk_warehouse_code UNIQUE (code),
    CONSTRAINT ck_warehouse_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);


CREATE TABLE warehouse.location
(
    id               UUID         NOT NULL,
    warehouse_id     UUID         NOT NULL,
    -- The location code (e.g. HCM-A-01-02-B) that inventory stores against each stock row.
    code             VARCHAR(64)  NOT NULL,
    zone             VARCHAR(32),
    aisle            VARCHAR(32),
    rack             VARCHAR(32),
    level            VARCHAR(32),
    bin              VARCHAR(32),
    type             VARCHAR(32)  NOT NULL,
    -- Golden zone: knee-to-shoulder heights where picking is fastest (ubiquitous language).
    golden_zone      BOOLEAN      NOT NULL DEFAULT FALSE,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_location PRIMARY KEY (id),
    CONSTRAINT uk_location_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT fk_location_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT ck_location_type CHECK (type IN ('PICK', 'BULK', 'STAGING', 'QUARANTINE'))
);

CREATE INDEX ix_location_warehouse ON warehouse.location (warehouse_id);


CREATE TABLE warehouse.putaway_task
(
    id                 UUID         NOT NULL,
    -- Cross-schema reference to procurement.goods_receipt: plain UUID, no FK.
    goods_receipt_id   UUID,
    sku                VARCHAR(64)  NOT NULL,
    quantity           INTEGER      NOT NULL,
    target_location_id UUID,
    status             VARCHAR(32)  NOT NULL,

    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_putaway_task PRIMARY KEY (id),
    CONSTRAINT fk_putaway_target_location
        FOREIGN KEY (target_location_id) REFERENCES warehouse.location (id),
    CONSTRAINT ck_putaway_quantity CHECK (quantity > 0),
    CONSTRAINT ck_putaway_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'CANCELLED'))
);

CREATE INDEX ix_putaway_status ON warehouse.putaway_task (status);


CREATE TABLE warehouse.slotting_rule
(
    id               UUID         NOT NULL,
    warehouse_id     UUID         NOT NULL,
    name             VARCHAR(200) NOT NULL,
    -- JSON criteria (velocity band, category, weight) held as text for now.
    criteria         VARCHAR(2000),
    priority         INTEGER      NOT NULL DEFAULT 0,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_slotting_rule PRIMARY KEY (id),
    CONSTRAINT fk_slotting_rule_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id)
);

CREATE INDEX ix_slotting_rule_warehouse ON warehouse.slotting_rule (warehouse_id);

COMMENT ON TABLE warehouse.location IS 'A storage location. Its code is replicated into inventory.';
