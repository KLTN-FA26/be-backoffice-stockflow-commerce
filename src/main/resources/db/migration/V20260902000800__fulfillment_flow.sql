-- =============================================================================
-- Fulfillment: waves, picks, packs and shipments.
-- Module: fulfillment   ERD: ful__*
--
-- fulfillment-service owns these; order replicates only status via events (PickCompleted,
-- ShipmentDispatched). order_id is a cross-module reference (plain UUID). The wave->pick->pack->
-- shipment chain stays within the fulfillment schema.
-- =============================================================================

CREATE TABLE fulfillment.wave
(
    id               UUID         NOT NULL,
    code             VARCHAR(64)  NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    released_at      TIMESTAMPTZ,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_wave PRIMARY KEY (id),
    CONSTRAINT uk_wave_code UNIQUE (code),
    CONSTRAINT ck_wave_status CHECK (status IN ('PLANNED', 'RELEASED', 'COMPLETED'))
);

CREATE TABLE fulfillment.pick
(
    id               UUID         NOT NULL,
    wave_id          UUID,
    order_id         UUID         NOT NULL,
    status           VARCHAR(32)  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_pick PRIMARY KEY (id),
    CONSTRAINT fk_pick_wave FOREIGN KEY (wave_id) REFERENCES fulfillment.wave (id),
    CONSTRAINT ck_pick_status CHECK (status IN ('PENDING', 'PICKING', 'PICKED', 'SHORT'))
);

CREATE INDEX ix_pick_order ON fulfillment.pick (order_id);
CREATE INDEX ix_pick_wave ON fulfillment.pick (wave_id) WHERE wave_id IS NOT NULL;

CREATE TABLE fulfillment.pack
(
    id               UUID         NOT NULL,
    pick_id          UUID         NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    packed_at        TIMESTAMPTZ,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_pack PRIMARY KEY (id),
    CONSTRAINT fk_pack_pick FOREIGN KEY (pick_id) REFERENCES fulfillment.pick (id),
    CONSTRAINT ck_pack_status CHECK (status IN ('PENDING', 'PACKING', 'PACKED'))
);

CREATE TABLE fulfillment.shipment
(
    id               UUID         NOT NULL,
    pack_id          UUID,
    order_id         UUID         NOT NULL,
    carrier          VARCHAR(120),
    tracking_number  VARCHAR(128),
    status           VARCHAR(32)  NOT NULL,
    dispatched_at    TIMESTAMPTZ,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_shipment PRIMARY KEY (id),
    CONSTRAINT fk_shipment_pack FOREIGN KEY (pack_id) REFERENCES fulfillment.pack (id),
    CONSTRAINT ck_shipment_status CHECK (status IN ('PENDING', 'DISPATCHED', 'DELIVERED', 'RETURNED'))
);

CREATE INDEX ix_shipment_order ON fulfillment.shipment (order_id);
