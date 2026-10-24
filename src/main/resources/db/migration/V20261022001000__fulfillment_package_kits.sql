-- =============================================================================
-- Kits at the packing station (SCRUM-287 PR K; lead's decisions LP2-5, LP2-6 of 2026-10-22). Module: fulfillment
-- Docs: kltn-docs 08 step 5-9, BR-03, BR-04; 09 §4.2, BR-01, BR-03.
--
-- LP2-5: an inventory item with package_count N > 1 ships as a kit ("bộ") of N boxes - a table whose top and base
-- travel in separate cartons. LP2-6: the packer seals it as a kit: N parcels that share kit_group_id, numbered
-- kit_part_no 1..kit_part_count, opened for one unit of one order line (kit_order_line_id). Part 1 holds that unit
-- (its package_line); parts 2..N hold no goods, only the kit code. Each part is weighed and measured on its own
-- (08 BR-03). Load planning keeps a kit's parts together per vehicle by kit_group_id.
--
--   package.kit_*          all four null (an ordinary parcel) or all four set, with 2 <= kit_part_count and
--                          1 <= kit_part_no <= kit_part_count; one parcel per part number. Fixed when the kit is opened.
--   package.kit_override_reason  KIT-FORCE (lead's decision 2026-10-22): a kit is the default for an item that ships
--                          as several boxes; an ordinary parcel holds its units only with the packer's reason, kept
--                          here (and in the audit trail). Never on a kit part; never blank.
--   tg_package_kit_check   every part of a kit is of one packing session - one order (08 BR-04) - for a line of that
--                          order, and agrees on the kit's size and line.
--   tg_package_kit_fixed   a parcel's kit never changes after it was opened.
--   tg_package_line_kit_part  only part 1 of a kit holds goods (package_line), and only its kit's line.
--   tg_package_kit_ships_whole  deferred to commit: a kit leaves whole - once one of its parts is in a shipment, all
--                          its parts are, sealed, in that one shipment, part 1 with its goods (09 BR-01, BR-03; a
--                          partial shipment never splits a kit). Voided parts do not count: a kit is voided whole.
--                          The application checks it before it leaves a packing write (SET CONSTRAINTS ... IMMEDIATE)
--                          to answer a 409; WHEN keeps it off ordinary parcels.
-- Every refusal names its trigger (the message starts with it, USING CONSTRAINT carries it): the application maps it.
-- No grant changes: opening a kit is fulfillment-packing:UPDATE, as opening a parcel is.
-- =============================================================================

ALTER TABLE fulfillment.package
    ADD COLUMN kit_group_id      UUID,
    ADD COLUMN kit_part_no       SMALLINT,
    ADD COLUMN kit_part_count    SMALLINT,
    ADD COLUMN kit_order_line_id UUID,
    ADD COLUMN kit_override_reason VARCHAR(500);

ALTER TABLE fulfillment.package
    ADD CONSTRAINT fk_package_kit_order_line FOREIGN KEY (kit_order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_package_kit CHECK (
        (kit_group_id IS NULL AND kit_part_no IS NULL AND kit_part_count IS NULL AND kit_order_line_id IS NULL)
        OR (kit_group_id IS NOT NULL AND kit_part_no IS NOT NULL AND kit_part_count IS NOT NULL
            AND kit_order_line_id IS NOT NULL
            AND kit_part_count >= 2 AND kit_part_no >= 1 AND kit_part_no <= kit_part_count)),
    ADD CONSTRAINT ck_package_kit_override CHECK (kit_override_reason IS NULL
        OR (kit_group_id IS NULL AND btrim(kit_override_reason) <> ''));

CREATE UNIQUE INDEX uk_package_kit_part ON fulfillment.package (kit_group_id, kit_part_no);

COMMENT ON COLUMN fulfillment.package.kit_group_id IS
    'The kit (bộ) this parcel is a part of: one unit shipped as kit_part_count boxes (LP2-5/LP2-6); null otherwise';
COMMENT ON COLUMN fulfillment.package.kit_part_no IS 'Part n of the kit; part 1 holds the unit, the others no goods';
COMMENT ON COLUMN fulfillment.package.kit_part_count IS 'Parts of the kit: the item''s package_count when opened';
COMMENT ON COLUMN fulfillment.package.kit_order_line_id IS 'The order line whose one unit the kit holds (in part 1)';
COMMENT ON COLUMN fulfillment.package.kit_override_reason IS
    'KIT-FORCE: why this ordinary parcel holds units of an item that ships as several boxes (not as a kit)';


-- ----------------------------------------------------------------------------- one order, one line, one size
CREATE FUNCTION fulfillment.check_package_kit()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pack pa
                     JOIN fulfillment.pick pi ON pi.id = pa.pick_id
                     JOIN ordering.order_line l ON l.order_id = pi.order_id
                    WHERE pa.id = NEW.pack_id AND l.id = NEW.kit_order_line_id) THEN
        RAISE EXCEPTION 'tg_package_kit_check: a kit holds a line of its own order'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_kit_check';
    END IF;
    IF EXISTS (SELECT 1 FROM fulfillment.package pk
                WHERE pk.kit_group_id = NEW.kit_group_id AND pk.id <> NEW.id
                  AND (pk.pack_id <> NEW.pack_id OR pk.kit_part_count <> NEW.kit_part_count
                       OR pk.kit_order_line_id <> NEW.kit_order_line_id)) THEN
        RAISE EXCEPTION 'tg_package_kit_check: the parts of kit % are of one order and agree on its size and line',
            NEW.kit_group_id USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_kit_check';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_package_kit_check BEFORE INSERT ON fulfillment.package
    FOR EACH ROW WHEN (NEW.kit_group_id IS NOT NULL)
    EXECUTE FUNCTION fulfillment.check_package_kit();


-- ----------------------------------------------------------------------------- a kit is fixed when opened
CREATE FUNCTION fulfillment.refuse_package_kit_change()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'tg_package_kit_fixed: the kit of package % is fixed when it is opened', OLD.label_code
        USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_kit_fixed';
END
$$;

CREATE TRIGGER tg_package_kit_fixed BEFORE UPDATE ON fulfillment.package
    FOR EACH ROW WHEN (OLD.kit_group_id IS DISTINCT FROM NEW.kit_group_id
        OR OLD.kit_part_no IS DISTINCT FROM NEW.kit_part_no
        OR OLD.kit_part_count IS DISTINCT FROM NEW.kit_part_count
        OR OLD.kit_order_line_id IS DISTINCT FROM NEW.kit_order_line_id
        OR (OLD.kit_group_id IS NOT NULL AND OLD.pack_id IS DISTINCT FROM NEW.pack_id))
    EXECUTE FUNCTION fulfillment.refuse_package_kit_change();


-- ----------------------------------------------------------------------------- only part 1 holds goods
CREATE FUNCTION fulfillment.check_kit_part_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM fulfillment.package pk
                WHERE pk.id = NEW.package_id AND pk.kit_group_id IS NOT NULL
                  AND (pk.kit_part_no > 1 OR pk.kit_order_line_id <> NEW.order_line_id)) THEN
        RAISE EXCEPTION 'tg_package_line_kit_part: only part 1 of a kit holds goods, of its own line'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_line_kit_part';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_package_line_kit_part BEFORE INSERT OR UPDATE OF package_id, order_line_id
    ON fulfillment.package_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_kit_part_line();


-- ----------------------------------------------------------------------------- a kit ships whole
-- Deferred: completion writes the parts' shipment one row at a time; the kit is judged as the writes leave it.
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
        RAISE EXCEPTION 'tg_package_kit_ships_whole: kit % ships whole: all % parts sealed in one shipment, part 1 '
            'with its goods', NEW.kit_group_id, part_count
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_kit_ships_whole';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_package_kit_ships_whole AFTER INSERT OR UPDATE OF shipment_id, status
    ON fulfillment.package
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.kit_group_id IS NOT NULL)
    EXECUTE FUNCTION fulfillment.check_kit_ships_whole();
