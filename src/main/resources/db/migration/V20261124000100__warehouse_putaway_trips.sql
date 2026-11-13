-- =============================================================================
-- Putaway trips: several cartons on one cart, put away along one route (kltn-docs 05 §4.5, §5, BR-15, BR-16, BR-17;
-- 06 §4.4 step 6). Module: warehouse   Jira: SCRUM-531 / SCRUM-573
--
-- A putaway task stays one carton (05 BR-08). A trip is the cartons a clerk has on the cart:
--   LOADING      cartons are scanned onto the cart; no bin assigned, nothing held;
--   IN_PROGRESS  "start": every carton on the cart is assigned a bin (or an OVERFLOW area), the space is held and
--                the stops are ordered;
--   COMPLETED    every carton on the cart was put away;
--   CLOSED       closed with cartons still on it: they go back to the source area and their tasks stay PENDING.
--
-- What is stored and what is not:
--   * the bin assigned to a carton and its stop number are stored: they are the trip's plan, and the next trip's
--     assignment reads them as space already taken (BR-16);
--   * the volume and weight a hold takes are not stored: they are the carton's, read from its carton spec whenever
--     the space of a bin is worked out;
--   * the stand points and the distances of the route are not stored either (06 §4.4 step 6: "không lưu").
--
-- A hold is not a lock (BR-16): the hard rules are checked again when the bin is scanned. A hold ends when
-- hold_released_at is set - the carton was put away, went to another bin, the trip ended, or the trip saw no scan for
-- the hold window (30 minutes by default, stockflow.putaway.trip-hold-minutes; PutawayTripHoldSweeper).
--
-- Rules stated here as well as in the aggregate (PutawayTrip):
--   * one open trip per clerk (BR-15): uk_putaway_trip_open_operator;
--   * a task is on at most one open cart (BR-15): uk_putaway_trip_item_on_cart;
--   * a trip is started exactly when it is, or was, IN_PROGRESS; it is ended exactly when COMPLETED or CLOSED.
--
-- Permissions: a new resource, warehouse-putaway-trips. READ and UPDATE are the clerk's (as warehouse-putaway-tasks);
-- APPROVE is closing somebody else's trip, the warehouse manager's (05 §2, §4.5 step 17).
-- =============================================================================

CREATE TABLE warehouse.putaway_trip
(
    id                UUID        NOT NULL,
    warehouse_id      UUID        NOT NULL,
    operator_id       UUID        NOT NULL,
    status            VARCHAR(16) NOT NULL,
    -- The source area (RECEIVING or QUARANTINE) of the last carton scanned onto the cart: where the route starts.
    start_location_id UUID,
    started_at        TIMESTAMPTZ,
    last_activity_at  TIMESTAMPTZ NOT NULL,
    closed_at         TIMESTAMPTZ,
    closed_by         UUID,

    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL,
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_putaway_trip PRIMARY KEY (id),
    CONSTRAINT fk_putaway_trip_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_putaway_trip_operator FOREIGN KEY (operator_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_putaway_trip_start_location FOREIGN KEY (start_location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT,
    CONSTRAINT fk_putaway_trip_closed_by FOREIGN KEY (closed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_putaway_trip_status CHECK (status IN ('LOADING', 'IN_PROGRESS', 'COMPLETED', 'CLOSED')),
    -- A cart still being loaded has not left; one on its way, or that put everything away, has.
    CONSTRAINT ck_putaway_trip_started CHECK ((status <> 'LOADING' OR started_at IS NULL)
        AND (status NOT IN ('IN_PROGRESS', 'COMPLETED') OR started_at IS NOT NULL)),
    CONSTRAINT ck_putaway_trip_closed CHECK ((status IN ('COMPLETED', 'CLOSED')) = (closed_at IS NOT NULL)
        AND (closed_by IS NULL OR status = 'CLOSED'))
);

COMMENT ON TABLE warehouse.putaway_trip IS
    'A clerk''s cart of cartons to put away in one go (kltn-docs 05 §4.5): LOADING, IN_PROGRESS, COMPLETED or CLOSED.';
COMMENT ON COLUMN warehouse.putaway_trip.last_activity_at IS
    'The last scan of the trip. Its holds are released when this is older than the hold window (05 BR-16).';

-- 05 BR-15: a clerk has one open trip.
CREATE UNIQUE INDEX uk_putaway_trip_open_operator ON warehouse.putaway_trip (operator_id)
    WHERE status IN ('LOADING', 'IN_PROGRESS');
CREATE INDEX ix_putaway_trip_warehouse_status ON warehouse.putaway_trip (warehouse_id, status);

CREATE TABLE warehouse.putaway_trip_item
(
    id                   UUID        NOT NULL,
    trip_id              UUID        NOT NULL,
    putaway_task_id      UUID        NOT NULL,
    -- ON_CART on the cart; PLACED put away; RETURNED taken off the cart (its task is PENDING again).
    status               VARCHAR(16) NOT NULL,
    assigned_location_id UUID,
    stop_sequence        INTEGER,
    hold_released_at     TIMESTAMPTZ,
    loaded_at            TIMESTAMPTZ NOT NULL,
    placed_at            TIMESTAMPTZ,

    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL,
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_putaway_trip_item PRIMARY KEY (id),
    CONSTRAINT uk_putaway_trip_item_task UNIQUE (trip_id, putaway_task_id),
    CONSTRAINT fk_putaway_trip_item_trip FOREIGN KEY (trip_id)
        REFERENCES warehouse.putaway_trip (id) ON DELETE CASCADE,
    CONSTRAINT fk_putaway_trip_item_task FOREIGN KEY (putaway_task_id)
        REFERENCES warehouse.putaway_task (id) ON DELETE RESTRICT,
    CONSTRAINT fk_putaway_trip_item_location FOREIGN KEY (assigned_location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT,
    CONSTRAINT ck_putaway_trip_item_status CHECK (status IN ('ON_CART', 'PLACED', 'RETURNED')),
    CONSTRAINT ck_putaway_trip_item_placed CHECK ((status = 'PLACED') = (placed_at IS NOT NULL)),
    CONSTRAINT ck_putaway_trip_item_stop CHECK (stop_sequence IS NULL OR stop_sequence >= 1),
    -- Nothing is held for a carton no longer on the cart.
    CONSTRAINT ck_putaway_trip_item_hold CHECK (status = 'ON_CART' OR assigned_location_id IS NULL
        OR hold_released_at IS NOT NULL)
);

COMMENT ON TABLE warehouse.putaway_trip_item IS
    'A carton on a putaway trip''s cart: the bin it was assigned, its stop on the route and whether its space is held.';
COMMENT ON COLUMN warehouse.putaway_trip_item.hold_released_at IS
    'When the space held at assigned_location_id was given back; NULL while it is held (05 BR-16).';

-- 05 BR-15: a task is on one open cart at most.
CREATE UNIQUE INDEX uk_putaway_trip_item_on_cart ON warehouse.putaway_trip_item (putaway_task_id)
    WHERE status = 'ON_CART';
-- The live holds of a bin, read whenever another carton is suggested or assigned a bin.
CREATE INDEX ix_putaway_trip_item_hold ON warehouse.putaway_trip_item (assigned_location_id)
    WHERE status = 'ON_CART' AND assigned_location_id IS NOT NULL AND hold_released_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'warehouse-putaway-trips:' || a.action, 'warehouse-putaway-trips', a.action, 0, NOW(),
       'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'UPDATE', 'APPROVE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('WAREHOUSE_MANAGER', 'all'),
          ('WAREHOUSE_STAFF',   'VIEW_PAGE,READ,UPDATE'),
          ('SYSTEM_ADMIN',      'all')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'warehouse-putaway-trips'
 AND (g.actions = 'all' OR permission.action = ANY (string_to_array(g.actions, ',')))
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ADR-0008: role-keyed permission caches must change generation in this same transaction.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF', 'SYSTEM_ADMIN');
