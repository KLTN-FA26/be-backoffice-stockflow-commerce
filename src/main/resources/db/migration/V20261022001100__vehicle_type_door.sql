-- =============================================================================
-- Optional door opening of a vehicle type (SCRUM-287 load planning; lead decision LP3-DOOR, 22/10). Module: fulfillment
--
-- The placement solver (a separate PR) refuses a carton that cannot pass through the door of the vehicle, even though
-- it would fit inside. A door is smaller than the inner cargo space: a 20DC container is about 2 393 mm high inside but
-- its door opening is about 2 280 mm high.
--
--   door_width_mm, door_height_mm   the clear door opening, mm. Both optional (NULL = "no door limit": the carton only
--                                   has to fit the inner dimensions, as before). Each is more than 0 when set, and
--                                   never larger than the inner width / inner height of the same vehicle type.
--                                   Each may be set on its own: a NULL side puts no limit on that side.
--
-- Backfill of the container presets, typical ISO 1496-1 / ISO 668 door openings of general-purpose containers, as in
-- the specification sheets of the large shipping lines (e.g. Maersk, Hapag-Lloyd "dry container" sheets):
--   20DC, 40DC   about 2 340 mm wide x 2 280 mm high
--   40HC, 45HC   about 2 340 mm wide x 2 585 mm high   (high cube)
-- These are editable defaults like the other preset figures (the warehouse manager corrects them). The trucks and the
-- pickup are left NULL: a box truck usually opens the full width, which the inner dimensions already cover.
--
-- The preset trigger of V20261022000700 guards only code, kind and the preset flag, so a preset's door stays editable.
-- =============================================================================

ALTER TABLE fulfillment.vehicle_type
    ADD COLUMN door_width_mm  INTEGER,
    ADD COLUMN door_height_mm INTEGER;

ALTER TABLE fulfillment.vehicle_type
    ADD CONSTRAINT ck_vehicle_type_door_width CHECK (door_width_mm IS NULL OR door_width_mm > 0),
    ADD CONSTRAINT ck_vehicle_type_door_height CHECK (door_height_mm IS NULL OR door_height_mm > 0),
    ADD CONSTRAINT ck_vehicle_type_door_width_fits CHECK (door_width_mm IS NULL OR door_width_mm <= inner_width_mm),
    ADD CONSTRAINT ck_vehicle_type_door_height_fits CHECK (door_height_mm IS NULL OR door_height_mm <= inner_height_mm);

UPDATE fulfillment.vehicle_type
SET door_width_mm = 2340, door_height_mm = 2280
WHERE is_preset AND code IN ('20DC', '40DC');

UPDATE fulfillment.vehicle_type
SET door_width_mm = 2340, door_height_mm = 2585
WHERE is_preset AND code IN ('40HC', '45HC');
