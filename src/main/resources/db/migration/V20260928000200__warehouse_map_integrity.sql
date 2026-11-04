-- =============================================================================
-- Warehouse map: the rules a CHECK cannot express, as triggers.
-- Module: warehouse   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase W
--
-- Every rule here is also the aggregate's job (CLAUDE.md §7: state every invariant twice). These
-- are the backstop against a manual UPDATE or a second code path, and each one was a hole shown
-- by an adversarial INSERT during the warehouse QA round (docs/business-design/db-design/2026-09-27-*.md §2).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. A reusable "this column never changes once it has a value" trigger.
--
--    Lives in platform because it is infrastructure, not a module's rule; every module may attach
--    it. NULL -> value is allowed so an expand step can add a nullable column and fill it later.
--    to_jsonb() is how a generic trigger reads a column named at CREATE TRIGGER time.
-- -----------------------------------------------------------------------------
CREATE FUNCTION platform.forbid_update_of()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    col TEXT;
    old_value TEXT;
BEGIN
    FOREACH col IN ARRAY TG_ARGV LOOP
        old_value := to_jsonb(OLD) ->> col;
        IF old_value IS NOT NULL AND old_value IS DISTINCT FROM (to_jsonb(NEW) ->> col) THEN
            RAISE EXCEPTION '%.%.% is immutable once set (was %)',
                TG_TABLE_SCHEMA, TG_TABLE_NAME, col, old_value
                USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION platform.forbid_update_of() IS
    'BEFORE UPDATE trigger: the columns named as trigger arguments may go from NULL to a value, never change after.';


-- BR-13: the prefix, codes and level index are inside every location code and document number.
CREATE TRIGGER tg_warehouse_immutable BEFORE UPDATE ON warehouse.warehouse
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('prefix', 'map_unit');
CREATE TRIGGER tg_zone_immutable BEFORE UPDATE ON warehouse.zone
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('warehouse_id');
CREATE TRIGGER tg_shelf_immutable BEFORE UPDATE ON warehouse.shelf
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('warehouse_id', 'code');
CREATE TRIGGER tg_shelf_level_immutable BEFORE UPDATE ON warehouse.shelf_level
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('shelf_id', 'level_index');
CREATE TRIGGER tg_bin_immutable BEFORE UPDATE ON warehouse.bin
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('level_id', 'location_id', 'code');
CREATE TRIGGER tg_area_immutable BEFORE UPDATE ON warehouse.area
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('warehouse_id', 'location_id', 'code');
CREATE TRIGGER tg_storage_location_immutable BEFORE UPDATE ON warehouse.storage_location
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('warehouse_id', 'kind', 'location_code');


-- -----------------------------------------------------------------------------
-- 2. A bin's storage location must be a BIN in the bin's own warehouse, and its code must be
--    prefix-shelf-level-bin. Without this a bin could point at an AREA row, or at a location of
--    another warehouse, and the unique index on location_id would not notice.
--
--    The code check is skipped while the warehouse has no prefix yet (expand period, see
--    V20260928000100); contract C2 makes prefix NOT NULL, after which it always runs.
-- -----------------------------------------------------------------------------
CREATE FUNCTION warehouse.check_bin_location()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    loc    warehouse.storage_location%ROWTYPE;
    wh_id  UUID;
    prefix TEXT;
    shelf_code TEXT;
    level_no   INTEGER;
BEGIN
    SELECT * INTO loc FROM warehouse.storage_location WHERE id = NEW.location_id;
    SELECT s.warehouse_id, w.prefix, s.code, l.level_index
      INTO wh_id, prefix, shelf_code, level_no
      FROM warehouse.shelf_level l
      JOIN warehouse.shelf s ON s.id = l.shelf_id
      LEFT JOIN warehouse.warehouse w ON w.id = s.warehouse_id
     WHERE l.id = NEW.level_id;

    IF loc.kind IS DISTINCT FROM 'BIN' THEN
        RAISE EXCEPTION 'bin % must point at a storage_location of kind BIN, got %', NEW.code, loc.kind
            USING ERRCODE = 'check_violation';
    END IF;
    IF loc.warehouse_id IS DISTINCT FROM wh_id THEN
        RAISE EXCEPTION 'bin % and its storage_location are in different warehouses', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF prefix IS NOT NULL
       AND loc.location_code IS DISTINCT FROM prefix || '-' || shelf_code || '-' || level_no || '-' || NEW.code THEN
        RAISE EXCEPTION 'bin location_code must be %, got %',
            prefix || '-' || shelf_code || '-' || level_no || '-' || NEW.code, loc.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_bin_location BEFORE INSERT OR UPDATE ON warehouse.bin
    FOR EACH ROW EXECUTE FUNCTION warehouse.check_bin_location();


CREATE FUNCTION warehouse.check_area_location()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    loc    warehouse.storage_location%ROWTYPE;
    prefix TEXT;
BEGIN
    IF NEW.location_id IS NULL THEN
        RETURN NEW;                       -- NON_STORAGE, see ck_area_storage
    END IF;
    SELECT * INTO loc FROM warehouse.storage_location WHERE id = NEW.location_id;
    SELECT w.prefix INTO prefix FROM warehouse.warehouse w WHERE w.id = NEW.warehouse_id;

    IF loc.kind IS DISTINCT FROM 'AREA' THEN
        RAISE EXCEPTION 'area % must point at a storage_location of kind AREA, got %', NEW.code, loc.kind
            USING ERRCODE = 'check_violation';
    END IF;
    IF loc.warehouse_id IS DISTINCT FROM NEW.warehouse_id THEN
        RAISE EXCEPTION 'area % and its storage_location are in different warehouses', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF prefix IS NOT NULL AND loc.location_code IS DISTINCT FROM prefix || '-' || NEW.code THEN
        RAISE EXCEPTION 'area location_code must be %, got %', prefix || '-' || NEW.code, loc.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_area_location BEFORE INSERT OR UPDATE ON warehouse.area
    FOR EACH ROW EXECUTE FUNCTION warehouse.check_area_location();


-- -----------------------------------------------------------------------------
-- 3. A shelf's zone must belong to the shelf's warehouse. A composite foreign key would say this,
--    but the team chose single-column keys only (QA notes §1); this trigger is the replacement.
-- -----------------------------------------------------------------------------
CREATE FUNCTION warehouse.check_shelf_zone()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.zone_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM warehouse.zone z WHERE z.id = NEW.zone_id AND z.warehouse_id = NEW.warehouse_id) THEN
        RAISE EXCEPTION 'shelf % is assigned to a zone of another warehouse', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_shelf_zone BEFORE INSERT OR UPDATE OF zone_id, warehouse_id ON warehouse.shelf
    FOR EACH ROW EXECUTE FUNCTION warehouse.check_shelf_zone();


-- -----------------------------------------------------------------------------
-- 4. No orphan storage location: at commit, every storage_location is owned by exactly one bin or
--    one storage area. Deferred, because the location row has to be inserted before the bin that
--    points at it; the check runs when the whole aggregate is in.
-- -----------------------------------------------------------------------------
CREATE FUNCTION warehouse.check_storage_location_owned()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM warehouse.storage_location WHERE id = NEW.id)
       AND NOT EXISTS (SELECT 1 FROM warehouse.bin WHERE location_id = NEW.id)
       AND NOT EXISTS (SELECT 1 FROM warehouse.area WHERE location_id = NEW.id) THEN
        RAISE EXCEPTION 'storage_location % is owned by no bin and no area', NEW.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_storage_location_owned
    AFTER INSERT ON warehouse.storage_location
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION warehouse.check_storage_location_owned();
