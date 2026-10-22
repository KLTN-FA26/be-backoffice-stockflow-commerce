-- =============================================================================
-- Kits at the packing station (SCRUM-287 PR K; lead's decisions LP2-5, LP2-6 of 2026-10-22). Module: fulfillment
-- Docs: kltn-docs 08 step 5-9, BR-03, BR-04; 09 §4.2, BR-01, BR-03.
--
-- LP2-5: an inventory item with package_count N > 1 ships as a kit ("bộ") of N boxes - a table whose top and base
-- travel in separate cartons. LP2-6: the packer seals it as a kit: N parcels that share kit_group_id, numbered
-- kit_part_no 1..kit_part_count. Part 1 holds the unit (its package_line); parts 2..N hold no goods, only the kit
-- code. Each part is weighed and measured on its own (08 BR-03). Load planning keeps a kit's parts together per
-- vehicle by kit_group_id.
--
--   package.kit_group_id / kit_part_no / kit_part_count   all three null (an ordinary parcel) or all three set, with
--                          2 <= kit_part_count and 1 <= kit_part_no <= kit_part_count; one parcel per part number.
--   check_package_kit      every part of a kit is of one packing session - one order (08 BR-04) - and agrees on the
--                          kit's size.
--   check_kit_part_line    only part 1 of a kit holds goods (package_line).
--   check_kit_ships_whole  deferred to commit: a kit leaves whole - once one of its parts is in a shipment, all its
--                          parts are, sealed, in that one shipment, part 1 with its goods (09 BR-01, BR-03; a partial
--                          shipment never splits a kit). Voided parts do not count: a kit is voided whole.
-- No grant changes: opening a kit is fulfillment-packing:UPDATE, as opening a parcel is.
-- =============================================================================

ALTER TABLE fulfillment.package
    ADD COLUMN kit_group_id   UUID,
    ADD COLUMN kit_part_no    SMALLINT,
    ADD COLUMN kit_part_count SMALLINT;

ALTER TABLE fulfillment.package
    ADD CONSTRAINT ck_package_kit CHECK (
        (kit_group_id IS NULL AND kit_part_no IS NULL AND kit_part_count IS NULL)
        OR (kit_group_id IS NOT NULL AND kit_part_no IS NOT NULL AND kit_part_count IS NOT NULL
            AND kit_part_count >= 2 AND kit_part_no >= 1 AND kit_part_no <= kit_part_count));

CREATE UNIQUE INDEX uk_package_kit_part ON fulfillment.package (kit_group_id, kit_part_no);

COMMENT ON COLUMN fulfillment.package.kit_group_id IS
    'The kit (bộ) this parcel is a part of: one unit shipped as kit_part_count boxes (LP2-5/LP2-6); null otherwise';
COMMENT ON COLUMN fulfillment.package.kit_part_no IS 'Part n of the kit; part 1 holds the unit, the others no goods';
COMMENT ON COLUMN fulfillment.package.kit_part_count IS 'Parts of the kit: the item''s package_count when opened';


-- ----------------------------------------------------------------------------- one order, one size
CREATE FUNCTION fulfillment.check_package_kit()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.kit_group_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM fulfillment.package pk
                WHERE pk.kit_group_id = NEW.kit_group_id AND pk.id <> NEW.id
                  AND (pk.pack_id <> NEW.pack_id OR pk.kit_part_count <> NEW.kit_part_count)) THEN
        RAISE EXCEPTION 'the parts of kit % are of one order and agree on its size', NEW.kit_group_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_package_kit_check BEFORE INSERT OR UPDATE OF kit_group_id, kit_part_no, kit_part_count, pack_id
    ON fulfillment.package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_kit();


-- ----------------------------------------------------------------------------- only part 1 holds goods
CREATE FUNCTION fulfillment.check_kit_part_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM fulfillment.package pk WHERE pk.id = NEW.package_id AND pk.kit_part_no > 1) THEN
        RAISE EXCEPTION 'only part 1 of a kit holds goods' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_package_line_kit_part BEFORE INSERT OR UPDATE OF package_id ON fulfillment.package_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_kit_part_line();


-- ----------------------------------------------------------------------------- a kit ships whole
-- Deferred: completion writes the parts' shipment one row at a time; the kit is judged as the transaction leaves it.
CREATE FUNCTION fulfillment.check_kit_ships_whole()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    parts      INTEGER;
    shipped    INTEGER;
    shipments  INTEGER;
    sealed     INTEGER;
    part_count INTEGER;
BEGIN
    IF NEW.kit_group_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT count(*), count(shipment_id), count(DISTINCT shipment_id), count(*) FILTER (WHERE status = 'SEALED'),
           max(kit_part_count)
      INTO parts, shipped, shipments, sealed, part_count
      FROM fulfillment.package
     WHERE kit_group_id = NEW.kit_group_id AND status <> 'VOIDED';
    IF shipped = 0 THEN
        RETURN NULL;
    END IF;
    IF shipped <> parts OR shipments <> 1 OR sealed <> parts OR parts <> part_count
        OR NOT EXISTS (SELECT 1 FROM fulfillment.package pk
                         JOIN fulfillment.package_line pl ON pl.package_id = pk.id
                        WHERE pk.kit_group_id = NEW.kit_group_id AND pk.kit_part_no = 1 AND pk.status <> 'VOIDED') THEN
        RAISE EXCEPTION 'kit % ships whole: all % parts sealed in one shipment, part 1 with its goods',
            NEW.kit_group_id, part_count USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_package_kit_ships_whole AFTER INSERT OR UPDATE OF shipment_id, status
    ON fulfillment.package
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_kit_ships_whole();
