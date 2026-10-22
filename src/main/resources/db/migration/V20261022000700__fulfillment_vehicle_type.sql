-- =============================================================================
-- Vehicle-type catalogue for full-truckload load planning (SCRUM-287 PR B; kltn-docs 09 §3 "Danh mục loại xe (Vehicle
-- Type)", §4.2, BR-12). Module: fulfillment (table), identity (grants)
--
--   vehicle_type   a kind of truck, pickup or container a load plan may use (lead decisions LP-6, LP-8, LP-12,
--                  22/10: one global catalogue that holds trucks as well as containers): its inner cargo space
--                  (length x width x height in mm, the box the placement solver fills), the gusset size gusset_mm,
--                  the maximum payload (kg), how many are available (BR-12: a plan never uses more vehicles of a
--                  type than this - checked within a plan only) and a reference rental price (09 §4.2 "giá thuê ...
--                  chỉ lưu để tham khảo": the planner does not choose by price).
--                  kind: CONTAINER | TRUCK | PICKUP (the documents name no kinds; they say "xe tải / container").
--                  The presets (is_preset) are EDITABLE DEFAULTS, not measurements: the four ISO containers and the
--                  common Vietnamese trucks below. The warehouse manager corrects them for the vehicles the business
--                  really hires and declares any other type with its own inner dimensions and payload (09 §3). A
--                  preset cannot be deleted and its code and kind never change (the trigger below), because load
--                  plans will reference the type by id and the documents name the presets by code.
--
-- gusset_mm (09 BR-10 "chừa các góc gia cố"), as the solver being ported places it (nesting-standalone,
-- solver_3d_kit.py all_gussets / solver_3d.py _gusset_placements): a gusset is a CUBE whose edge is gusset_mm,
-- reserved in the upper corners; cargo is routed around it and it is never counted as load. Two cubes sit at the
-- FRONT-end top corners (z = 0, the end the layout is measured from; x = 0 and x = width - gusset, y = height -
-- gusset). A box whose inner length is 12 500 mm or more (a 45' container) gets instead FOUR cubes, at the two
-- 40'-post columns of each side wall (the posts are 12 032 mm apart, centred in the length), and no front cubes.
-- The solver's default size is 150 mm, and it drops a gusset that does not fit (width < 2 x size or height < size),
-- so the table refuses one: 2 x gusset_mm < inner_width_mm and gusset_mm < inner_height_mm. 0 = no gusset.
-- Containers are seeded with 150 mm (the ISO corner castings stick out of the roof corners). Trucks and the pickup
-- are seeded with 0: a truck box has no ISO corner castings, and an open pickup bed has no ceiling.
--
-- Sources of the preset figures (rounded; approximate defaults, to be checked against the vehicles actually hired):
--   * ISO containers 20DC / 40DC / 40HC / 45HC: ISO 668 / ISO 1496-1 general-purpose ("dry") containers - the usual
--     inner dimensions and maximum payload printed in the specification sheets of the large shipping lines (e.g.
--     Maersk, Hapag-Lloyd "dry container" sheets). The payload is the maximum gross mass minus the tare, so it differs a
--     little between manufacturers.
--   * Vietnamese box trucks (thùng kín): the typical cargo-box sizes listed by dealers for the common models of each
--     class - Suzuki Carry / Kia Frontier (1.25 t), Isuzu QKR / Hyundai N250 (2.5 t), Isuzu NMR / NPR (5 t), Isuzu
--     FRR / FVR and Hyundai HD (8 t: boxes of about 7.2-7.8 m are listed; 7 400 mm taken), Isuzu FVM / Hyundai HD
--     3-axle (15 t). Box sizes differ by a few centimetres between makers and body builders.
--   * Pickup (bán tải): the bed of a double-cab pickup of the Ford Ranger / Isuzu D-Max class is about 1 500 mm long
--     and 1 560 mm wide at the widest, but the wheel arches leave only about 1 140 mm between them: the USABLE width
--     is seeded (a crate wider than that does not fit flat), and the height is the side-wall height (about 500 mm).
--
--   code        kind       inner L x W x H (mm)       max payload (kg)   gusset (mm)
--   20DC        CONTAINER  5 898 x 2 352 x 2 393      28 200             150
--   40DC        CONTAINER  12 032 x 2 352 x 2 393     26 700             150
--   40HC        CONTAINER  12 032 x 2 352 x 2 698     26 500             150   (high cube: 9 ft 6 in)
--   45HC        CONTAINER  13 556 x 2 352 x 2 698     27 700             150   (high cube; 4 corner-post cubes)
--   PICKUP      PICKUP     1 500 x 1 140 x 500          800               0   (usable width between the wheel arches)
--   TRUCK-1T25  TRUCK      3 100 x 1 600 x 1 600      1 250               0
--   TRUCK-2T5   TRUCK      4 300 x 1 800 x 1 800      2 500               0
--   TRUCK-5T    TRUCK      5 200 x 2 050 x 2 050      5 000               0
--   TRUCK-8T    TRUCK      7 400 x 2 350 x 2 300      8 000               0
--   TRUCK-15T   TRUCK      9 300 x 2 350 x 2 400     15 000               0
--
-- available_count of a preset is seeded 10: how many vehicles the business can get is its own matter (the warehouse
-- manager sets it), and a 0 would make the presets unusable until someone edits them. The rental price is left
-- empty: the system does not guess prices.
--
-- Grants (permission codes, never role names; ADR-0008):
--   fulfillment-vehicle-types:VIEW_PAGE, :READ   warehouse staff, warehouse manager, order coordinator, SYSTEM_ADMIN
--   fulfillment-vehicle-types:UPDATE             warehouse manager, SYSTEM_ADMIN
-- A role that does not exist in a database is skipped (the joins), exactly as in V20261022000200.
-- =============================================================================

CREATE TABLE fulfillment.vehicle_type
(
    id               UUID           NOT NULL,
    code             VARCHAR(32)    NOT NULL,
    name             VARCHAR(120)   NOT NULL,
    kind             VARCHAR(16)    NOT NULL,
    inner_length_mm  INTEGER        NOT NULL,
    inner_width_mm   INTEGER        NOT NULL,
    inner_height_mm  INTEGER        NOT NULL,
    -- Edge of the reinforcement cubes kept free in the top corners (BR-10; see the header); 0 = none.
    gusset_mm        INTEGER        NOT NULL DEFAULT 0,
    max_payload_kg   NUMERIC(10, 3) NOT NULL,
    available_count  INTEGER        NOT NULL DEFAULT 0,
    -- Reference only (09 §4.2); VND.
    rental_price     NUMERIC(19, 4),
    is_preset        BOOLEAN        NOT NULL DEFAULT FALSE,
    status           VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_vehicle_type PRIMARY KEY (id),
    CONSTRAINT uk_vehicle_type_code UNIQUE (code),
    CONSTRAINT ck_vehicle_type_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
    CONSTRAINT ck_vehicle_type_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_vehicle_type_kind CHECK (kind IN ('CONTAINER', 'TRUCK', 'PICKUP')),
    CONSTRAINT ck_vehicle_type_length CHECK (inner_length_mm > 0),
    CONSTRAINT ck_vehicle_type_width CHECK (inner_width_mm > 0),
    CONSTRAINT ck_vehicle_type_height CHECK (inner_height_mm > 0),
    CONSTRAINT ck_vehicle_type_gusset CHECK (gusset_mm >= 0),
    -- The solver drops a gusset that does not fit, so one is refused here.
    CONSTRAINT ck_vehicle_type_gusset_fits CHECK (2 * gusset_mm < inner_width_mm AND gusset_mm < inner_height_mm),
    CONSTRAINT ck_vehicle_type_payload CHECK (max_payload_kg > 0),
    CONSTRAINT ck_vehicle_type_available CHECK (available_count >= 0),
    CONSTRAINT ck_vehicle_type_rental_price CHECK (rental_price IS NULL OR rental_price >= 0),
    CONSTRAINT ck_vehicle_type_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ix_vehicle_type_status ON fulfillment.vehicle_type (status, name);

-- A preset is never deleted and keeps its code, its kind and its preset flag, whoever writes (the API, a script, psql).
CREATE FUNCTION fulfillment.vehicle_type_guard_preset() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.is_preset THEN
            RAISE EXCEPTION 'vehicle type % is a preset and cannot be deleted', OLD.code USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.code <> OLD.code THEN
        RAISE EXCEPTION 'the code of vehicle type % cannot change', OLD.code USING ERRCODE = '23514';
    END IF;
    IF OLD.is_preset AND NEW.kind <> OLD.kind THEN
        RAISE EXCEPTION 'the kind of preset vehicle type % cannot change', OLD.code USING ERRCODE = '23514';
    END IF;
    IF NEW.is_preset <> OLD.is_preset THEN
        RAISE EXCEPTION 'vehicle type % cannot become or stop being a preset', OLD.code USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_vehicle_type_guard_preset
    BEFORE UPDATE OR DELETE ON fulfillment.vehicle_type
    FOR EACH ROW EXECUTE FUNCTION fulfillment.vehicle_type_guard_preset();

INSERT INTO fulfillment.vehicle_type (id, code, name, kind, inner_length_mm, inner_width_mm, inner_height_mm,
                                      gusset_mm, max_payload_kg, available_count, is_preset, created_at, created_by)
VALUES (md5('vehicle-type:20DC')::uuid, '20DC', 'Container 20'' Dry (20DC)', 'CONTAINER', 5898, 2352, 2393, 150, 28200, 10, TRUE, NOW(), 'flyway'),
       (md5('vehicle-type:40DC')::uuid, '40DC', 'Container 40'' Dry (40DC)', 'CONTAINER', 12032, 2352, 2393, 150, 26700, 10, TRUE, NOW(), 'flyway'),
       (md5('vehicle-type:40HC')::uuid, '40HC', 'Container 40'' High Cube (40HC)', 'CONTAINER', 12032, 2352, 2698, 150, 26500, 10, TRUE, NOW(), 'flyway'),
       (md5('vehicle-type:45HC')::uuid, '45HC', 'Container 45'' High Cube (45HC)', 'CONTAINER', 13556, 2352, 2698, 150, 27700, 10, TRUE, NOW(), 'flyway'),
       (md5('vehicle-type:PICKUP')::uuid, 'PICKUP', 'Pickup truck (xe bán tải)', 'PICKUP', 1500, 1140, 500, 0, 800, 10, TRUE, NOW(), 'flyway'),
       (md5('vehicle-type:TRUCK-1T25')::uuid, 'TRUCK-1T25', 'Box truck 1.25 t (xe tải 1,25 tấn)', 'TRUCK', 3100, 1600, 1600, 0, 1250, 10, TRUE, NOW(), 'flyway'),
       (md5('vehicle-type:TRUCK-2T5')::uuid, 'TRUCK-2T5', 'Box truck 2.5 t (xe tải 2,5 tấn)', 'TRUCK', 4300, 1800, 1800, 0, 2500, 10, TRUE, NOW(), 'flyway'),
       (md5('vehicle-type:TRUCK-5T')::uuid, 'TRUCK-5T', 'Box truck 5 t (xe tải 5 tấn)', 'TRUCK', 5200, 2050, 2050, 0, 5000, 10, TRUE, NOW(), 'flyway'),
       (md5('vehicle-type:TRUCK-8T')::uuid, 'TRUCK-8T', 'Box truck 8 t (xe tải 8 tấn)', 'TRUCK', 7400, 2350, 2300, 0, 8000, 10, TRUE, NOW(), 'flyway'),
       (md5('vehicle-type:TRUCK-15T')::uuid, 'TRUCK-15T', 'Box truck 15 t (xe tải 15 tấn)', 'TRUCK', 9300, 2350, 2400, 0, 15000, 10, TRUE, NOW(), 'flyway');

-- ----------------------------------------------------------------------------- permissions
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'fulfillment-vehicle-types:' || a.action, 'fulfillment-vehicle-types', a.action, 0, NOW(),
       'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'UPDATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('WAREHOUSE_STAFF',   'VIEW_PAGE,READ'),
          ('WAREHOUSE_MANAGER', 'VIEW_PAGE,READ,UPDATE'),
          ('ORDER_COORDINATOR', 'VIEW_PAGE,READ')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'fulfillment-vehicle-types'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
  AND permission.resource = 'fulfillment-vehicle-types'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ADR-0008: role-keyed permission caches must change generation in this same transaction.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ORDER_COORDINATOR', 'SYSTEM_ADMIN');
