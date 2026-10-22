-- =============================================================================
-- Load plans of full-truckload shipping (SCRUM-287 PR D / SCRUM-391; kltn-docs 09 §3 "Dữ liệu tối thiểu cần lưu",
-- §4.2, §5 "Trạng thái Kế hoạch chuyến xe", BR-09..BR-15). Module: fulfillment (tables), identity (grants)
--
--   load_plan               the plan: DRAFT -> PLANNED -> CONFIRMED -> DISPATCHED, or CANCELLED (09 §5). Code
--                           LP-yyyyMMdd-nnnn from platform.document_sequence ('LP'); request_id makes creating one
--                           idempotent. source_type ORDER only for now (TRANSFER comes with PR T, LP-9).
--                           selection_mode SINGLE_TYPE | FEWEST_VEHICLES (lead's decision LP-6).
--                           BR-13: unplaced parcels are acknowledged (unplaced_acknowledged_*) before CONFIRMED; any
--                           change to what is unplaced clears the acknowledgement (the service does it).
--   load_plan_vehicle_type  the vehicle types the dispatcher allows the plan to use (09 §4.2 step 1). The catalogue is
--                           global (LP-8); available_count is read at calculation time and checked within this plan
--                           only (BR-12).
--   load_plan_package       the parcels in the plan (LP-5: whole shipments are chosen; LP2-1: only Packed parcels,
--                           BR-09). active = held by this plan; a parcel is held by at most ONE plan
--                           (uk_load_plan_package_active, BR-09: "chỉ thuộc tối đa một kế hoạch chưa Cancelled").
--                           unplaced_reason: why a held parcel has no place (the solver's reason, or MANUAL when the
--                           dispatcher put it on the tray); null when placed or before a calculation.
--                           release_reason: why it stopped being held - INPUTS_CHANGED (taken out of the inputs),
--                           LEFT_BEHIND (unplaced at confirmation: back to the dispatch area for a later trip, 09 §4.2
--                           step 3), PLAN_CANCELLED, ORDER_CANCELLED (09 edge "đơn bị huỷ khi kiện đã nằm trong kế
--                           hoạch").
--   load_plan_vehicle       one vehicle of the plan. Its type's figures are COPIED at calculation time (code, kind,
--                           inner size, payload, gusset, door): the catalogue may change later (LP-8), the placements
--                           were computed and are checked against these. A recalculation retires the vehicles it
--                           replaces (active = false) instead of deleting them, because a cancelled dispatch load may
--                           still name one; seq is unique among the active ones.
--   load_placement          where a parcel sits (09 §3 LoadPlacement): mm from the inner-left floor corner by the front
--                           wall, x across, y up, z toward the door; w/h/d the placed sizes along x/y/z; orientation
--                           0-5 the solver's index (Orientation: LWH, HWL, LHW, WHL, HLW, WLH). The full BR-10 rules
--                           (98 % support, no overlap, gussets, payload, door, kits on one vehicle) are checked by
--                           PlacementValidator before anything is written (BR-11); tg_load_placement_check is the net
--                           for what a single row can show: the parcel is held by the plan, the vehicle is an active one
--                           of the plan, the placed sizes are the parcel's own sizes and the box is inside the vehicle.
--   dispatch_load           gains its foreign key to load_plan_vehicle (the column came with V20261021000400).
--                           Confirming a plan opens one PLANNED dispatch load per vehicle (09 §4.2 step 4-5); booking,
--                           loading and the per-vehicle handover are PR E (LP-3).
--
-- Parcels in a plan are frozen: tg_package_in_load_plan refuses voiding, re-opening, re-measuring or moving a parcel
-- to another shipment while a plan holds it (the application answers 409 PACKAGE_IN_LOAD_PLAN first).
--
-- Sizes: a parcel is measured in cm with two decimals (fulfillment.package.*_cm); the planner works in whole mm, so a
-- side is ceil(cm x 10) - rounded up, never under-stated. Both the service and the trigger below use that rule.
--
-- Grants (permission codes, never role names; ADR-0008). 09 §2: the Dispatch Staff (warehouse staff) and the warehouse
-- manager plan and confirm; the order coordinator follows the plans of his orders:
--   fulfillment-load-plans:VIEW_PAGE, :READ      WAREHOUSE_STAFF, WAREHOUSE_MANAGER, ORDER_COORDINATOR, SYSTEM_ADMIN
--   fulfillment-load-plans:CREATE, :UPDATE       WAREHOUSE_STAFF, WAREHOUSE_MANAGER, SYSTEM_ADMIN
-- A role that does not exist in a database is skipped (the joins), as in V20261022000700.
-- =============================================================================


-- ----------------------------------------------------------------------------- load_plan
CREATE TABLE fulfillment.load_plan
(
    id                        UUID         NOT NULL,
    code                      VARCHAR(30)  NOT NULL,
    request_id                UUID         NOT NULL,
    warehouse_id              UUID         NOT NULL,
    source_type               VARCHAR(16)  NOT NULL DEFAULT 'ORDER',
    selection_mode            VARCHAR(24)  NOT NULL DEFAULT 'SINGLE_TYPE',
    status                    VARCHAR(16)  NOT NULL,
    note                      VARCHAR(500),
    -- The last calculation (whole plan) and what the solver reported about it (LoadPlanMeta, trimmed).
    calculated_at             TIMESTAMPTZ,
    calculated_by             UUID,
    solver_meta               JSONB,
    -- BR-13.
    unplaced_acknowledged_at  TIMESTAMPTZ,
    unplaced_acknowledged_by  UUID,
    unplaced_ack_note         VARCHAR(500),
    confirmed_at              TIMESTAMPTZ,
    confirmed_by              UUID,
    reopened_at               TIMESTAMPTZ,
    reopened_by               UUID,
    reopen_reason             VARCHAR(500),
    dispatched_at             TIMESTAMPTZ,
    cancelled_at              TIMESTAMPTZ,
    cancelled_by              UUID,
    cancel_reason             VARCHAR(500),
    -- Touched by every change, so the version moves even when only child rows changed (optimistic locking).
    revised_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    version                   BIGINT       NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                VARCHAR(100),
    last_modified_at          TIMESTAMPTZ,
    last_modified_by          VARCHAR(100),

    CONSTRAINT pk_load_plan PRIMARY KEY (id),
    CONSTRAINT uk_load_plan_code UNIQUE (code),
    CONSTRAINT uk_load_plan_request UNIQUE (request_id),
    CONSTRAINT fk_load_plan_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT fk_load_plan_calculated_by FOREIGN KEY (calculated_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_load_plan_ack_by FOREIGN KEY (unplaced_acknowledged_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_load_plan_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_load_plan_reopened_by FOREIGN KEY (reopened_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_load_plan_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_load_plan_source CHECK (source_type IN ('ORDER', 'TRANSFER')),
    CONSTRAINT ck_load_plan_mode CHECK (selection_mode IN ('SINGLE_TYPE', 'FEWEST_VEHICLES')),
    CONSTRAINT ck_load_plan_status CHECK (status IN ('DRAFT', 'PLANNED', 'CONFIRMED', 'DISPATCHED', 'CANCELLED')),
    CONSTRAINT ck_load_plan_calculated CHECK (status IN ('DRAFT', 'CANCELLED') OR calculated_at IS NOT NULL),
    CONSTRAINT ck_load_plan_confirmed CHECK (status NOT IN ('CONFIRMED', 'DISPATCHED')
        OR (confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL)),
    CONSTRAINT ck_load_plan_dispatched CHECK ((status = 'DISPATCHED') = (dispatched_at IS NOT NULL)),
    CONSTRAINT ck_load_plan_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)),
    CONSTRAINT ck_load_plan_ack CHECK ((unplaced_acknowledged_at IS NULL) = (unplaced_acknowledged_by IS NULL))
);

CREATE INDEX ix_load_plan_warehouse_status ON fulfillment.load_plan (warehouse_id, status, created_at);


-- ----------------------------------------------------------------------------- load_plan_vehicle_type
CREATE TABLE fulfillment.load_plan_vehicle_type
(
    id               UUID        NOT NULL,
    load_plan_id     UUID        NOT NULL,
    vehicle_type_id  UUID        NOT NULL,
    sort_order       INTEGER     NOT NULL DEFAULT 0,

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_load_plan_vehicle_type PRIMARY KEY (id),
    CONSTRAINT uk_load_plan_vehicle_type UNIQUE (load_plan_id, vehicle_type_id),
    CONSTRAINT fk_load_plan_vehicle_type_plan FOREIGN KEY (load_plan_id)
        REFERENCES fulfillment.load_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_load_plan_vehicle_type_type FOREIGN KEY (vehicle_type_id)
        REFERENCES fulfillment.vehicle_type (id) ON DELETE RESTRICT
);


-- ----------------------------------------------------------------------------- load_plan_package
CREATE TABLE fulfillment.load_plan_package
(
    id               UUID        NOT NULL,
    load_plan_id     UUID        NOT NULL,
    package_id       UUID        NOT NULL,
    shipment_id      UUID        NOT NULL,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    unplaced_reason  VARCHAR(24),
    added_at         TIMESTAMPTZ NOT NULL,
    added_by         UUID,
    released_at      TIMESTAMPTZ,
    release_reason   VARCHAR(24),

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_load_plan_package PRIMARY KEY (id),
    CONSTRAINT uk_load_plan_package UNIQUE (load_plan_id, package_id),
    CONSTRAINT fk_load_plan_package_plan FOREIGN KEY (load_plan_id)
        REFERENCES fulfillment.load_plan (id) ON DELETE RESTRICT,
    CONSTRAINT fk_load_plan_package_package FOREIGN KEY (package_id)
        REFERENCES fulfillment.package (id) ON DELETE RESTRICT,
    CONSTRAINT fk_load_plan_package_shipment FOREIGN KEY (shipment_id)
        REFERENCES fulfillment.shipment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_load_plan_package_added_by FOREIGN KEY (added_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_load_plan_package_unplaced CHECK (unplaced_reason IS NULL OR unplaced_reason IN
        ('TOO_LARGE', 'TOO_HEAVY', 'NO_VEHICLE_AVAILABLE', 'NO_SPACE', 'SEARCH_CUT_OFF', 'MANUAL')),
    CONSTRAINT ck_load_plan_package_release CHECK (release_reason IS NULL OR release_reason IN
        ('INPUTS_CHANGED', 'LEFT_BEHIND', 'PLAN_CANCELLED', 'ORDER_CANCELLED')),
    CONSTRAINT ck_load_plan_package_released CHECK (active = (released_at IS NULL)
        AND active = (release_reason IS NULL))
);

-- BR-09: a parcel belongs to at most one plan that is not cancelled. A plan releases its parcels when it is cancelled
-- (and its left-behind parcels when it is confirmed), so "held" is exactly "active".
CREATE UNIQUE INDEX uk_load_plan_package_active ON fulfillment.load_plan_package (package_id) WHERE active;
CREATE INDEX ix_load_plan_package_shipment ON fulfillment.load_plan_package (shipment_id) WHERE active;


-- ----------------------------------------------------------------------------- load_plan_vehicle
CREATE TABLE fulfillment.load_plan_vehicle
(
    id               UUID           NOT NULL,
    load_plan_id     UUID           NOT NULL,
    seq              INTEGER        NOT NULL,
    active           BOOLEAN        NOT NULL DEFAULT TRUE,
    vehicle_type_id  UUID           NOT NULL,
    -- The type as it was when the plan was calculated (see the header).
    vehicle_code     VARCHAR(32)    NOT NULL,
    vehicle_kind     VARCHAR(16)    NOT NULL,
    inner_length_mm  INTEGER        NOT NULL,
    inner_width_mm   INTEGER        NOT NULL,
    inner_height_mm  INTEGER        NOT NULL,
    max_payload_kg   NUMERIC(10, 3) NOT NULL,
    gusset_mm        INTEGER        NOT NULL DEFAULT 0,
    door_width_mm    INTEGER,
    door_height_mm   INTEGER,
    used_volume_mm3  BIGINT         NOT NULL DEFAULT 0,
    used_weight_kg   NUMERIC(12, 3) NOT NULL DEFAULT 0,
    volume_fill_pct  NUMERIC(7, 2)  NOT NULL DEFAULT 0,
    weight_fill_pct  NUMERIC(7, 2)  NOT NULL DEFAULT 0,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_load_plan_vehicle PRIMARY KEY (id),
    -- The target of the composite key of load_placement: a placement's vehicle is of the placement's plan.
    CONSTRAINT uk_load_plan_vehicle_plan UNIQUE (id, load_plan_id),
    CONSTRAINT fk_load_plan_vehicle_plan FOREIGN KEY (load_plan_id)
        REFERENCES fulfillment.load_plan (id) ON DELETE RESTRICT,
    CONSTRAINT fk_load_plan_vehicle_type FOREIGN KEY (vehicle_type_id)
        REFERENCES fulfillment.vehicle_type (id) ON DELETE RESTRICT,
    CONSTRAINT ck_load_plan_vehicle_seq CHECK (seq >= 1),
    CONSTRAINT ck_load_plan_vehicle_kind CHECK (vehicle_kind IN ('CONTAINER', 'TRUCK', 'PICKUP')),
    CONSTRAINT ck_load_plan_vehicle_size CHECK (inner_length_mm > 0 AND inner_width_mm > 0 AND inner_height_mm > 0
        AND max_payload_kg > 0 AND gusset_mm >= 0
        AND (door_width_mm IS NULL OR door_width_mm > 0) AND (door_height_mm IS NULL OR door_height_mm > 0)),
    CONSTRAINT ck_load_plan_vehicle_used CHECK (used_volume_mm3 >= 0 AND used_weight_kg >= 0
        AND volume_fill_pct >= 0 AND weight_fill_pct >= 0)
);

CREATE UNIQUE INDEX uk_load_plan_vehicle_seq ON fulfillment.load_plan_vehicle (load_plan_id, seq) WHERE active;

ALTER TABLE fulfillment.dispatch_load
    ADD CONSTRAINT fk_dispatch_load_plan_vehicle FOREIGN KEY (load_plan_vehicle_id)
        REFERENCES fulfillment.load_plan_vehicle (id) ON DELETE RESTRICT;


-- ----------------------------------------------------------------------------- load_placement
CREATE TABLE fulfillment.load_placement
(
    id                   UUID        NOT NULL,
    load_plan_id         UUID        NOT NULL,
    load_plan_vehicle_id UUID        NOT NULL,
    package_id           UUID        NOT NULL,
    x_mm                 INTEGER     NOT NULL,
    y_mm                 INTEGER     NOT NULL,
    z_mm                 INTEGER     NOT NULL,
    w_mm                 INTEGER     NOT NULL,
    h_mm                 INTEGER     NOT NULL,
    d_mm                 INTEGER     NOT NULL,
    orientation          SMALLINT    NOT NULL,

    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_load_placement PRIMARY KEY (id),
    -- A parcel has one place in a plan.
    CONSTRAINT uk_load_placement_package UNIQUE (load_plan_id, package_id),
    CONSTRAINT fk_load_placement_vehicle FOREIGN KEY (load_plan_vehicle_id, load_plan_id)
        REFERENCES fulfillment.load_plan_vehicle (id, load_plan_id) ON DELETE CASCADE,
    CONSTRAINT fk_load_placement_package FOREIGN KEY (load_plan_id, package_id)
        REFERENCES fulfillment.load_plan_package (load_plan_id, package_id) ON DELETE RESTRICT,
    CONSTRAINT ck_load_placement_position CHECK (x_mm >= 0 AND y_mm >= 0 AND z_mm >= 0),
    CONSTRAINT ck_load_placement_size CHECK (w_mm > 0 AND h_mm > 0 AND d_mm > 0),
    CONSTRAINT ck_load_placement_orientation CHECK (orientation BETWEEN 0 AND 5)
);

CREATE INDEX ix_load_placement_vehicle ON fulfillment.load_placement (load_plan_vehicle_id);


-- ----------------------------------------------------------------------------- the rules, a second time
-- BR-01 / BR-09 / LP2-1: a parcel taken into a plan is sealed, measured and weighed, of a full-truckload shipment of
-- the plan's warehouse that waits for dispatch.
CREATE FUNCTION fulfillment.check_load_plan_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    p fulfillment.package%ROWTYPE;
    s fulfillment.shipment%ROWTYPE;
    plan_warehouse UUID;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO p FROM fulfillment.package WHERE id = NEW.package_id;
    SELECT * INTO s FROM fulfillment.shipment WHERE id = NEW.shipment_id;
    SELECT warehouse_id INTO plan_warehouse FROM fulfillment.load_plan WHERE id = NEW.load_plan_id;
    IF p.status <> 'SEALED' OR p.shipment_id IS DISTINCT FROM NEW.shipment_id OR p.weight_kg IS NULL
        OR p.weight_kg <= 0 OR p.length_cm IS NULL OR p.length_cm <= 0 OR p.width_cm IS NULL OR p.width_cm <= 0
        OR p.height_cm IS NULL OR p.height_cm <= 0 THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: package % is not a sealed, measured parcel of shipment %',
            p.label_code, NEW.shipment_id USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    IF s.status <> 'READY_TO_DISPATCH' OR s.delivery_mode <> 'FULL_TRUCKLOAD' OR s.warehouse_id <> plan_warehouse THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: shipment % is not a full truckload waiting for dispatch at the '
            'plan''s warehouse', s.code USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_load_plan_package_check BEFORE INSERT OR UPDATE OF active
    ON fulfillment.load_plan_package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_load_plan_package();

-- What one placement row can show by itself (the rest is PlacementValidator's, BR-10/BR-11).
CREATE FUNCTION fulfillment.check_load_placement()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v fulfillment.load_plan_vehicle%ROWTYPE;
    p fulfillment.package%ROWTYPE;
    placed INTEGER[];
    measured INTEGER[];
BEGIN
    SELECT * INTO v FROM fulfillment.load_plan_vehicle WHERE id = NEW.load_plan_vehicle_id;
    IF NOT v.active THEN
        RAISE EXCEPTION 'tg_load_placement_check: vehicle % of the plan was replaced', v.seq
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_placement_check';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.load_plan_package lp
                    WHERE lp.load_plan_id = NEW.load_plan_id AND lp.package_id = NEW.package_id AND lp.active) THEN
        RAISE EXCEPTION 'tg_load_placement_check: package % is not held by the plan', NEW.package_id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_placement_check';
    END IF;
    SELECT * INTO p FROM fulfillment.package WHERE id = NEW.package_id;
    SELECT array_agg(s ORDER BY s) INTO placed FROM unnest(ARRAY[NEW.w_mm, NEW.h_mm, NEW.d_mm]) AS s;
    SELECT array_agg(s ORDER BY s) INTO measured
      FROM unnest(ARRAY[ceil(p.length_cm * 10)::int, ceil(p.width_cm * 10)::int, ceil(p.height_cm * 10)::int]) AS s;
    IF placed <> measured THEN
        RAISE EXCEPTION 'tg_load_placement_check: placed sizes % are not the sizes % of package %', placed, measured,
            p.label_code USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_placement_check';
    END IF;
    IF NEW.x_mm + NEW.w_mm > v.inner_width_mm OR NEW.y_mm + NEW.h_mm > v.inner_height_mm
        OR NEW.z_mm + NEW.d_mm > v.inner_length_mm THEN
        RAISE EXCEPTION 'tg_load_placement_check: package % stands out of vehicle %', p.label_code, v.seq
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_placement_check';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_load_placement_check BEFORE INSERT OR UPDATE
    ON fulfillment.load_placement
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_load_placement();

-- A parcel held by a plan is frozen: not voided, re-opened, re-measured or moved to another shipment (BR-09, BR-14).
-- The order-cancellation path releases it from the plan first; the packing station answers 409 PACKAGE_IN_LOAD_PLAN
-- before it gets here.
CREATE FUNCTION fulfillment.guard_package_in_load_plan()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status OR NEW.shipment_id IS DISTINCT FROM OLD.shipment_id
        OR NEW.length_cm IS DISTINCT FROM OLD.length_cm OR NEW.width_cm IS DISTINCT FROM OLD.width_cm
        OR NEW.height_cm IS DISTINCT FROM OLD.height_cm OR NEW.weight_kg IS DISTINCT FROM OLD.weight_kg
        OR NEW.rotatable IS DISTINCT FROM OLD.rotatable THEN
        IF EXISTS (SELECT 1 FROM fulfillment.load_plan_package lp WHERE lp.package_id = OLD.id AND lp.active) THEN
            RAISE EXCEPTION 'tg_package_in_load_plan: package % is in a load plan', OLD.label_code
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_in_load_plan';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_package_in_load_plan BEFORE UPDATE
    ON fulfillment.package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.guard_package_in_load_plan();


-- ----------------------------------------------------------------------------- permissions
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'fulfillment-load-plans:' || a.action, 'fulfillment-load-plans', a.action, 0, NOW(),
       'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE', 'UPDATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('WAREHOUSE_STAFF',   'VIEW_PAGE,READ,CREATE,UPDATE'),
          ('WAREHOUSE_MANAGER', 'VIEW_PAGE,READ,CREATE,UPDATE'),
          ('ORDER_COORDINATOR', 'VIEW_PAGE,READ')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'fulfillment-load-plans'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
  AND permission.resource = 'fulfillment-load-plans'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ADR-0008: role-keyed permission caches must change generation in this same transaction.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ORDER_COORDINATOR', 'SYSTEM_ADMIN');
