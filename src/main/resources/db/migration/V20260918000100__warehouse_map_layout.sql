-- =============================================================================
-- Warehouse: replace the flat location table with the 2D warehouse map model.
-- Module: warehouse   Aggregates: Warehouse, Zone, Shelf (ShelfLevel, Bin), Area, Boundary
-- SCRUM-89 (WBS 3.5.1), GitHub #19. Business rules: docs repo, module 06, BR-06 -> BR-14.
--
-- A FORWARD migration on purpose. V20260902000300 created the flat model and has been on main
-- and develop since 2026-09-11, so any developer database started since then has applied it;
-- editing it would fail Flyway's checksum validation there. Applied migrations are never edited
-- (see V20260903000100).
--
-- Stock can sit in a Bin or in a storage Area, so both carry a location_code: the string
-- inventory.stock_item.location_code stores and a scanner reads. It is assembled once when the
-- location is created and never changes (BR-13), which is why it can be stored rather than
-- recomputed. Every part of it is [A-Z0-9], so the "-" separators are unambiguous: a bin code has
-- three of them, an area code one, and two different locations can never join into the same
-- string.
--
-- Geometry (inside the map, no overlap - BR-06/BR-07) is NOT a CHECK here: an exclusion
-- constraint cannot span shelf and area, and Postgres's && treats two touching boxes as
-- overlapping. The application enforces it under a row lock on the warehouse.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 0. This migration assumes nothing was ever written to the old model: no endpoint or seed has
--    written to these tables. If that ever stops being true, stop here rather than drop data.
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM warehouse.location)
        OR EXISTS (SELECT 1 FROM warehouse.warehouse)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE target_location_id IS NOT NULL) THEN
        RAISE EXCEPTION 'V20260918000100 expects the old warehouse model to be empty; migrate its data by hand first';
    END IF;
END $$;


-- -----------------------------------------------------------------------------
-- 1. Retire the flat location table.
-- -----------------------------------------------------------------------------
ALTER TABLE warehouse.putaway_task DROP CONSTRAINT fk_putaway_target_location;

COMMENT ON COLUMN warehouse.putaway_task.target_location_id IS
    'A Bin or a storage Area, so no single foreign key fits. Settled in SCRUM-92.';

DROP TABLE warehouse.location;


-- -----------------------------------------------------------------------------
-- 2. Warehouse: the map frame. Altered in place rather than recreated, so the foreign key from
--    slotting_rule stays as it is.
-- -----------------------------------------------------------------------------
ALTER TABLE warehouse.warehouse RENAME COLUMN code TO prefix;
ALTER TABLE warehouse.warehouse RENAME CONSTRAINT uk_warehouse_code TO uk_warehouse_prefix;
ALTER TABLE warehouse.warehouse ALTER COLUMN prefix TYPE VARCHAR(10);

ALTER TABLE warehouse.warehouse
    DROP COLUMN address_line,
    DROP COLUMN city,
    ADD COLUMN address        VARCHAR(500)  NOT NULL,
    ADD COLUMN return_address VARCHAR(500),
    ADD COLUMN map_unit       VARCHAR(8)    NOT NULL,
    ADD COLUMN map_width      NUMERIC(10, 3) NOT NULL,
    ADD COLUMN map_height     NUMERIC(10, 3) NOT NULL,
    -- Prefix and map unit never change once created (BR-13): the prefix is inside every location
    -- code and document number of the warehouse, the unit gives every coordinate its meaning.
    ADD CONSTRAINT ck_warehouse_prefix   CHECK (prefix ~ '^[A-Z0-9]{1,10}$'),
    ADD CONSTRAINT ck_warehouse_map_unit CHECK (map_unit IN ('M')),
    ADD CONSTRAINT ck_warehouse_map_size CHECK (map_width > 0 AND map_height > 0);


-- -----------------------------------------------------------------------------
-- 3. Zone: an optional display grouping of shelves and areas. Carries no storage rule.
-- -----------------------------------------------------------------------------
CREATE TABLE warehouse.zone
(
    id               UUID         NOT NULL,
    warehouse_id     UUID         NOT NULL,
    name             VARCHAR(100) NOT NULL,
    color            VARCHAR(7),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_zone PRIMARY KEY (id),
    CONSTRAINT uk_zone_warehouse_name UNIQUE (warehouse_id, name),
    CONSTRAINT fk_zone_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT ck_zone_color CHECK (color ~ '^#[0-9A-F]{6}$')
);


-- -----------------------------------------------------------------------------
-- 4. Shelf -> ShelfLevel -> Bin: one aggregate. Only shelf is versioned; its levels and bins
--    are written through it and its version guards the whole tree.
--
--    Coordinates are in the warehouse's map unit. (x, y) is the top-left corner of the footprint
--    AFTER rotation; rotation is a quarter turn, so every footprint is axis-aligned.
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
    created_at            TIMESTAMPTZ    NOT NULL,
    created_by            VARCHAR(100),
    last_modified_at      TIMESTAMPTZ,
    last_modified_by      VARCHAR(100),

    CONSTRAINT pk_shelf PRIMARY KEY (id),
    CONSTRAINT uk_shelf_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT fk_shelf_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT fk_shelf_zone FOREIGN KEY (zone_id) REFERENCES warehouse.zone (id),
    CONSTRAINT ck_shelf_code CHECK (code ~ '^[A-Z0-9]{1,20}$'),
    CONSTRAINT ck_shelf_position CHECK (x >= 0 AND y >= 0),
    CONSTRAINT ck_shelf_size CHECK (width > 0 AND length > 0),
    CONSTRAINT ck_shelf_rotation CHECK (rotation IN (0, 90, 180, 270)),
    CONSTRAINT ck_shelf_storage_class
        CHECK (default_storage_class IN ('NORMAL', 'COLD', 'HAZMAT', 'FRAGILE', 'OVERSIZE')),
    CONSTRAINT ck_shelf_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'MAINTENANCE', 'INACTIVE'))
);


CREATE TABLE warehouse.shelf_level
(
    id            UUID           NOT NULL,
    shelf_id      UUID           NOT NULL,
    -- Part of every bin's location code, so it never changes and levels are never renumbered.
    level_index   INTEGER        NOT NULL,
    elevation     NUMERIC(10, 3),
    usable_height NUMERIC(10, 3),
    max_weight    NUMERIC(10, 3),

    CONSTRAINT pk_shelf_level PRIMARY KEY (id),
    CONSTRAINT uk_shelf_level_shelf_index UNIQUE (shelf_id, level_index),
    CONSTRAINT fk_shelf_level_shelf FOREIGN KEY (shelf_id) REFERENCES warehouse.shelf (id),
    CONSTRAINT ck_shelf_level_index CHECK (level_index BETWEEN 1 AND 99),
    CONSTRAINT ck_shelf_level_measures CHECK (
        (elevation IS NULL OR elevation >= 0)
        AND (usable_height IS NULL OR usable_height > 0)
        AND (max_weight IS NULL OR max_weight > 0))
);

COMMENT ON COLUMN warehouse.shelf_level.elevation IS 'Height of the level floor, in the map unit.';
COMMENT ON COLUMN warehouse.shelf_level.usable_height IS 'Clear height, in the map unit. NULL = not checked.';
COMMENT ON COLUMN warehouse.shelf_level.max_weight IS 'Load limit in kilograms. NULL = not checked.';


CREATE TABLE warehouse.bin
(
    id                     UUID           NOT NULL,
    level_id               UUID           NOT NULL,
    code                   VARCHAR(20)    NOT NULL,
    -- prefix-shelf-level-bin, e.g. HN-A01-2-03. Assembled at creation, never changed (BR-10/13).
    location_code          VARCHAR(64)    NOT NULL,
    description            VARCHAR(1000),
    -- Relative to the shelf's own frame, so moving the shelf never touches its bins.
    x                      NUMERIC(10, 3) NOT NULL,
    y                      NUMERIC(10, 3) NOT NULL,
    width                  NUMERIC(10, 3) NOT NULL,
    length                 NUMERIC(10, 3) NOT NULL,
    rotation               INTEGER        NOT NULL,
    capacity_units         INTEGER,
    is_pickable            BOOLEAN        NOT NULL,
    is_putaway_bin         BOOLEAN        NOT NULL,
    type                   VARCHAR(32)    NOT NULL,
    storage_class_override VARCHAR(32),
    status                 VARCHAR(32)    NOT NULL,

    CONSTRAINT pk_bin PRIMARY KEY (id),
    CONSTRAINT uk_bin_level_code UNIQUE (level_id, code),
    CONSTRAINT uk_bin_location_code UNIQUE (location_code),
    CONSTRAINT fk_bin_level FOREIGN KEY (level_id) REFERENCES warehouse.shelf_level (id),
    CONSTRAINT ck_bin_code CHECK (code ~ '^[A-Z0-9]{1,20}$'),
    CONSTRAINT ck_bin_location_code
        CHECK (location_code ~ '^[A-Z0-9]{1,10}-[A-Z0-9]{1,20}-[1-9][0-9]?-[A-Z0-9]{1,20}$'),
    CONSTRAINT ck_bin_position CHECK (x >= 0 AND y >= 0),
    CONSTRAINT ck_bin_size CHECK (width > 0 AND length > 0),
    CONSTRAINT ck_bin_rotation CHECK (rotation IN (0, 90, 180, 270)),
    CONSTRAINT ck_bin_capacity CHECK (capacity_units IS NULL OR capacity_units > 0),
    CONSTRAINT ck_bin_type CHECK (type IN ('STANDARD', 'PALLET', 'SMALL_PARTS')),
    CONSTRAINT ck_bin_storage_class CHECK (storage_class_override IS NULL
        OR storage_class_override IN ('NORMAL', 'COLD', 'HAZMAT', 'FRAGILE', 'OVERSIZE')),
    CONSTRAINT ck_bin_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'MAINTENANCE', 'INACTIVE'))
);


-- -----------------------------------------------------------------------------
-- 5. Area: floor space that is not a shelf. A storage area (every type but NON_STORAGE) is a
--    stock location in its own right, hence its location_code (prefix-area, e.g. HN-RCV01).
-- -----------------------------------------------------------------------------
CREATE TABLE warehouse.area
(
    id               UUID           NOT NULL,
    warehouse_id     UUID           NOT NULL,
    zone_id          UUID,
    code             VARCHAR(20)    NOT NULL,
    location_code    VARCHAR(64)    NOT NULL,
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
    created_at       TIMESTAMPTZ    NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_area PRIMARY KEY (id),
    CONSTRAINT uk_area_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT uk_area_location_code UNIQUE (location_code),
    CONSTRAINT fk_area_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT fk_area_zone FOREIGN KEY (zone_id) REFERENCES warehouse.zone (id),
    CONSTRAINT ck_area_code CHECK (code ~ '^[A-Z0-9]{1,20}$'),
    CONSTRAINT ck_area_location_code CHECK (location_code ~ '^[A-Z0-9]{1,10}-[A-Z0-9]{1,20}$'),
    CONSTRAINT ck_area_position CHECK (x >= 0 AND y >= 0),
    CONSTRAINT ck_area_size CHECK (width > 0 AND length > 0),
    CONSTRAINT ck_area_rotation CHECK (rotation IN (0, 90, 180, 270)),
    CONSTRAINT ck_area_type CHECK (type IN
        ('RECEIVING', 'QUARANTINE', 'PACKING', 'DISPATCH', 'OVERFLOW', 'NON_STORAGE')),
    CONSTRAINT ck_area_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'MAINTENANCE', 'INACTIVE'))
);


-- -----------------------------------------------------------------------------
-- 6. Boundary: a wall or a door segment. Used for rendering and, later, for walking distance
--    (BR-09). A wall is never passable and has no open/closed state; a door always has one.
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
    created_at         TIMESTAMPTZ    NOT NULL,
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_boundary PRIMARY KEY (id),
    CONSTRAINT fk_boundary_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT ck_boundary_type CHECK (type IN ('WALL', 'DOOR')),
    -- IS NOT NULL is not redundant: "NULL IN (...)" is NULL, and a CHECK that evaluates to NULL
    -- passes - without it a door with no status would be accepted.
    CONSTRAINT ck_boundary_kind CHECK (
        (type = 'WALL' AND NOT is_passable AND operational_status IS NULL)
        OR (type = 'DOOR' AND operational_status IS NOT NULL
            AND operational_status IN ('OPEN', 'CLOSED'))),
    CONSTRAINT ck_boundary_position CHECK (start_x >= 0 AND start_y >= 0 AND end_x >= 0 AND end_y >= 0),
    CONSTRAINT ck_boundary_length CHECK (start_x <> end_x OR start_y <> end_y)
);

CREATE INDEX ix_boundary_warehouse ON warehouse.boundary (warehouse_id);

COMMENT ON TABLE warehouse.bin IS
    'Smallest storage slot on a shelf level. Its location_code is what inventory stores.';
COMMENT ON TABLE warehouse.area IS
    'Floor space that is not a shelf. Storage areas are stock locations too.';
