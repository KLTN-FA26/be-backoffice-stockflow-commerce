-- =============================================================================
-- Warehouse: the 2D map model (docs 06, BR-06..BR-14) - EXPAND step.
-- Module: warehouse   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase W
--
-- Supersedes the migration proposed in PR #28 (V20260918000100), which can no longer be applied:
-- its version is below V20260919001700 and Flyway does not run out of order. The model differs
-- from #28 in one place, deliberately: a stock location is its own row (storage_location) that a
-- bin or a storage area points at, instead of a location_code column repeated on bin and area.
-- One table means one foreign key target for inventory.stock_item, putaway, picking and moves.
--
-- EXPAND ONLY. WarehouseJpaEntity and LocationJpaEntity on develop still map warehouse.code,
-- address_line, city and warehouse.location, and ddl-auto=validate refuses to boot if a mapped
-- column disappears. So the new columns arrive nullable next to the old ones, and the old ones go
-- in db/pending/C2__contract_warehouse.sql once the new entities are merged.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Warehouse: the map frame. The prefix is part of every location code and document number of
--    the warehouse; the map unit gives every coordinate its meaning. Both are immutable once set
--    (BR-13, trigger in V20260928000200).
-- -----------------------------------------------------------------------------
ALTER TABLE warehouse.warehouse
    ADD COLUMN prefix         VARCHAR(10),
    ADD COLUMN address        VARCHAR(500),
    ADD COLUMN return_address VARCHAR(500),
    ADD COLUMN map_unit       VARCHAR(8),
    ADD COLUMN map_width      NUMERIC(10, 3),
    ADD COLUMN map_height     NUMERIC(10, 3),
    ADD CONSTRAINT uk_warehouse_prefix   UNIQUE (prefix),
    ADD CONSTRAINT ck_warehouse_prefix   CHECK (prefix ~ '^[A-Z0-9]{1,10}$'),
    ADD CONSTRAINT ck_warehouse_map_unit CHECK (map_unit IN ('M')),
    ADD CONSTRAINT ck_warehouse_map_size CHECK (map_width > 0 AND map_height > 0),
    -- A half-defined frame is worse than none: every coordinate on the map would be unreadable.
    ADD CONSTRAINT ck_warehouse_map_complete CHECK (
        (map_unit IS NULL AND map_width IS NULL AND map_height IS NULL)
        OR (map_unit IS NOT NULL AND map_width IS NOT NULL AND map_height IS NOT NULL));


-- -----------------------------------------------------------------------------
-- 2. Zone: an optional display grouping of shelves. Carries no storage rule.
-- -----------------------------------------------------------------------------
CREATE TABLE warehouse.zone
(
    id               UUID         NOT NULL,
    warehouse_id     UUID         NOT NULL,
    name             VARCHAR(100) NOT NULL,
    color            VARCHAR(7),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_zone PRIMARY KEY (id),
    CONSTRAINT uk_zone_warehouse_name UNIQUE (warehouse_id, name),
    CONSTRAINT fk_zone_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT ck_zone_color CHECK (color ~ '^#[0-9A-F]{6}$')
);


-- -----------------------------------------------------------------------------
-- 3. Storage location: every place stock can sit, bin or storage area. inventory.stock_item,
--    putaway, picking and moves all point here (by location_code or id).
--
--    kind + location_code format: a bin is prefix-shelf-level-bin (HCM-A01-2-B), an area is
--    prefix-area (HCM-QC01). Every part is [A-Z0-9], so the "-" separators are unambiguous and two
--    locations can never assemble into the same string.
--
--    DBML v9 drew warehouse_id here (and on shelf) without a foreign key, to keep the diagram
--    tidy. It is one anyway (plan, D-W1): a *_id column the database does not check is the one
--    kind of reference the QA round kept finding broken.
-- -----------------------------------------------------------------------------
CREATE TABLE warehouse.storage_location
(
    id                UUID           NOT NULL,
    warehouse_id      UUID           NOT NULL,
    kind              VARCHAR(8)     NOT NULL,
    location_code     VARCHAR(64)    NOT NULL,
    storage_class     VARCHAR(32)    NOT NULL,
    capacity_units    INTEGER,
    max_weight        NUMERIC(10, 3),
    is_pickable       BOOLEAN        NOT NULL,
    is_putaway_target BOOLEAN        NOT NULL,
    status            VARCHAR(32)    NOT NULL,

    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_storage_location PRIMARY KEY (id),
    CONSTRAINT uk_storage_location_code UNIQUE (location_code),
    CONSTRAINT fk_storage_location_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT ck_storage_location_kind CHECK (kind IN ('BIN', 'AREA')),
    CONSTRAINT ck_storage_location_code CHECK (
        (kind = 'BIN'  AND location_code ~ '^[A-Z0-9]{1,10}-[A-Z0-9]{1,20}-[1-9][0-9]?-[A-Z0-9]{1,20}$')
        OR (kind = 'AREA' AND location_code ~ '^[A-Z0-9]{1,10}-[A-Z0-9]{1,20}$')),
    CONSTRAINT ck_storage_location_class
        CHECK (storage_class IN ('NORMAL', 'COLD', 'HAZMAT', 'FRAGILE', 'OVERSIZE')),
    CONSTRAINT ck_storage_location_capacity CHECK (capacity_units IS NULL OR capacity_units > 0),
    CONSTRAINT ck_storage_location_max_weight CHECK (max_weight IS NULL OR max_weight > 0),
    CONSTRAINT ck_storage_location_status
        CHECK (status IN ('ACTIVE', 'BLOCKED', 'MAINTENANCE', 'INACTIVE'))
);

CREATE INDEX ix_storage_location_putaway ON warehouse.storage_location (warehouse_id, storage_class)
    WHERE is_putaway_target AND status = 'ACTIVE';


-- -----------------------------------------------------------------------------
-- 4. Shelf -> ShelfLevel -> Bin: one aggregate; shelf's version guards the whole tree.
--    Coordinates are in the warehouse's map unit. (x, y) is the top-left corner of the footprint
--    AFTER rotation; rotation is a quarter turn, so every footprint is axis-aligned. Geometry
--    (inside the map, no overlap - BR-06/07) is enforced by the application under a row lock on
--    the warehouse: no exclusion constraint can span shelf and area.
-- -----------------------------------------------------------------------------
CREATE TABLE warehouse.shelf
(
    id                    UUID           NOT NULL,
    warehouse_id          UUID           NOT NULL,
    zone_id               UUID,
    code                  VARCHAR(20)    NOT NULL,
    name                  VARCHAR(200)   NOT NULL,
    description           VARCHAR(1000),
    x                     NUMERIC(10, 3) NOT NULL,
    y                     NUMERIC(10, 3) NOT NULL,
    width                 NUMERIC(10, 3) NOT NULL,
    length                NUMERIC(10, 3) NOT NULL,
    rotation              INTEGER        NOT NULL,
    is_obstacle           BOOLEAN        NOT NULL,
    -- Pick faces are in the shelf's own frame (north = its top edge before rotation).
    pick_north            BOOLEAN        NOT NULL,
    pick_east             BOOLEAN        NOT NULL,
    pick_south            BOOLEAN        NOT NULL,
    pick_west             BOOLEAN        NOT NULL,
    default_storage_class VARCHAR(32)    NOT NULL,
    status                VARCHAR(32)    NOT NULL,

    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(100),
    last_modified_at      TIMESTAMPTZ,
    last_modified_by      VARCHAR(100),

    CONSTRAINT pk_shelf PRIMARY KEY (id),
    CONSTRAINT uk_shelf_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT fk_shelf_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_shelf_zone FOREIGN KEY (zone_id) REFERENCES warehouse.zone (id),
    CONSTRAINT ck_shelf_code CHECK (code ~ '^[A-Z0-9]{1,20}$'),
    CONSTRAINT ck_shelf_position CHECK (x >= 0 AND y >= 0),
    CONSTRAINT ck_shelf_size CHECK (width > 0 AND length > 0),
    CONSTRAINT ck_shelf_rotation CHECK (rotation IN (0, 90, 180, 270)),
    CONSTRAINT ck_shelf_storage_class
        CHECK (default_storage_class IN ('NORMAL', 'COLD', 'HAZMAT', 'FRAGILE', 'OVERSIZE')),
    CONSTRAINT ck_shelf_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'MAINTENANCE', 'INACTIVE'))
);

CREATE INDEX ix_shelf_zone ON warehouse.shelf (zone_id);


CREATE TABLE warehouse.shelf_level
(
    id               UUID           NOT NULL,
    shelf_id         UUID           NOT NULL,
    -- Part of every bin's location code, so it never changes and levels are never renumbered.
    level_index      INTEGER        NOT NULL,
    elevation        NUMERIC(10, 3),
    usable_height    NUMERIC(10, 3),
    max_weight       NUMERIC(10, 3),

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_shelf_level PRIMARY KEY (id),
    CONSTRAINT uk_shelf_level_shelf_index UNIQUE (shelf_id, level_index),
    CONSTRAINT fk_shelf_level_shelf FOREIGN KEY (shelf_id) REFERENCES warehouse.shelf (id),
    CONSTRAINT ck_shelf_level_index CHECK (level_index BETWEEN 1 AND 99),
    CONSTRAINT ck_shelf_level_measures CHECK (
        (elevation IS NULL OR elevation >= 0)
        AND (usable_height IS NULL OR usable_height > 0)
        AND (max_weight IS NULL OR max_weight > 0))
);


CREATE TABLE warehouse.bin
(
    id                     UUID           NOT NULL,
    level_id               UUID           NOT NULL,
    location_id            UUID           NOT NULL,
    code                   VARCHAR(20)    NOT NULL,
    description            VARCHAR(1000),
    -- Relative to the shelf's own frame, so moving the shelf never touches its bins.
    x                      NUMERIC(10, 3) NOT NULL,
    y                      NUMERIC(10, 3) NOT NULL,
    width                  NUMERIC(10, 3) NOT NULL,
    length                 NUMERIC(10, 3) NOT NULL,
    rotation               INTEGER        NOT NULL,
    type                   VARCHAR(32)    NOT NULL,
    storage_class_override VARCHAR(32),

    version                BIGINT         NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by             VARCHAR(100),
    last_modified_at       TIMESTAMPTZ,
    last_modified_by       VARCHAR(100),

    CONSTRAINT pk_bin PRIMARY KEY (id),
    CONSTRAINT uk_bin_level_code UNIQUE (level_id, code),
    CONSTRAINT uk_bin_location UNIQUE (location_id),
    CONSTRAINT fk_bin_level FOREIGN KEY (level_id) REFERENCES warehouse.shelf_level (id),
    CONSTRAINT fk_bin_location FOREIGN KEY (location_id) REFERENCES warehouse.storage_location (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_bin_code CHECK (code ~ '^[A-Z0-9]{1,20}$'),
    CONSTRAINT ck_bin_position CHECK (x >= 0 AND y >= 0),
    CONSTRAINT ck_bin_size CHECK (width > 0 AND length > 0),
    CONSTRAINT ck_bin_rotation CHECK (rotation IN (0, 90, 180, 270)),
    CONSTRAINT ck_bin_type CHECK (type IN ('STANDARD', 'PALLET', 'SMALL_PARTS')),
    CONSTRAINT ck_bin_storage_class CHECK (storage_class_override IS NULL
        OR storage_class_override IN ('NORMAL', 'COLD', 'HAZMAT', 'FRAGILE', 'OVERSIZE'))
);


-- -----------------------------------------------------------------------------
-- 5. Area: floor space that is not a shelf. A storage area (every type but NON_STORAGE) is a
--    stock location in its own right; a NON_STORAGE area (office, aisle) is never one.
-- -----------------------------------------------------------------------------
CREATE TABLE warehouse.area
(
    id               UUID           NOT NULL,
    warehouse_id     UUID           NOT NULL,
    location_id      UUID,
    code             VARCHAR(20)    NOT NULL,
    type             VARCHAR(32)    NOT NULL,
    name             VARCHAR(200)   NOT NULL,
    x                NUMERIC(10, 3) NOT NULL,
    y                NUMERIC(10, 3) NOT NULL,
    width            NUMERIC(10, 3) NOT NULL,
    length           NUMERIC(10, 3) NOT NULL,
    rotation         INTEGER        NOT NULL,
    is_obstacle      BOOLEAN        NOT NULL,
    status           VARCHAR(32)    NOT NULL,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_area PRIMARY KEY (id),
    CONSTRAINT uk_area_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT uk_area_location UNIQUE (location_id),
    CONSTRAINT fk_area_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT fk_area_location FOREIGN KEY (location_id) REFERENCES warehouse.storage_location (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_area_code CHECK (code ~ '^[A-Z0-9]{1,20}$'),
    CONSTRAINT ck_area_position CHECK (x >= 0 AND y >= 0),
    CONSTRAINT ck_area_size CHECK (width > 0 AND length > 0),
    CONSTRAINT ck_area_rotation CHECK (rotation IN (0, 90, 180, 270)),
    CONSTRAINT ck_area_type CHECK (type IN
        ('RECEIVING', 'QUARANTINE', 'PACKING', 'DISPATCH', 'OVERFLOW', 'NON_STORAGE')),
    CONSTRAINT ck_area_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'MAINTENANCE', 'INACTIVE')),
    -- IS NOT NULL on both sides: "x = NULL" is NULL and a NULL CHECK passes.
    CONSTRAINT ck_area_storage CHECK (
        (type = 'NON_STORAGE' AND location_id IS NULL)
        OR (type <> 'NON_STORAGE' AND location_id IS NOT NULL))
);


-- -----------------------------------------------------------------------------
-- 6. Boundary: a wall or a door segment, for rendering and walking distance (BR-09). A wall is
--    never passable and has no open/closed state; a door always has one.
-- -----------------------------------------------------------------------------
CREATE TABLE warehouse.boundary
(
    id                 UUID           NOT NULL,
    warehouse_id       UUID           NOT NULL,
    type               VARCHAR(32)    NOT NULL,
    start_x            NUMERIC(10, 3) NOT NULL,
    start_y            NUMERIC(10, 3) NOT NULL,
    end_x              NUMERIC(10, 3) NOT NULL,
    end_y              NUMERIC(10, 3) NOT NULL,
    is_passable        BOOLEAN        NOT NULL,
    operational_status VARCHAR(32),

    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_boundary PRIMARY KEY (id),
    CONSTRAINT fk_boundary_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT ck_boundary_type CHECK (type IN ('WALL', 'DOOR')),
    CONSTRAINT ck_boundary_kind CHECK (
        (type = 'WALL' AND NOT is_passable AND operational_status IS NULL)
        OR (type = 'DOOR' AND operational_status IS NOT NULL
            AND operational_status IN ('OPEN', 'CLOSED'))),
    CONSTRAINT ck_boundary_position
        CHECK (start_x >= 0 AND start_y >= 0 AND end_x >= 0 AND end_y >= 0),
    CONSTRAINT ck_boundary_length CHECK (start_x <> end_x OR start_y <> end_y)
);

CREATE INDEX ix_boundary_warehouse ON warehouse.boundary (warehouse_id);


-- -----------------------------------------------------------------------------
-- 7. Putaway task: which receipt line it puts away. Nullable until procurement writes the new
--    goods_receipt_lines (contract C4 adds the foreign key and NOT NULL).
-- -----------------------------------------------------------------------------
ALTER TABLE warehouse.putaway_task
    ADD COLUMN goods_receipt_line_id UUID;


COMMENT ON TABLE warehouse.storage_location IS
    'Every place stock can sit: one row per bin and per storage area. What inventory points at.';
COMMENT ON TABLE warehouse.bin IS
    'Smallest storage slot on a shelf level. Its stock location is warehouse.storage_location.';
COMMENT ON TABLE warehouse.area IS
    'Floor space that is not a shelf. Storage areas own a storage_location; NON_STORAGE ones do not.';
