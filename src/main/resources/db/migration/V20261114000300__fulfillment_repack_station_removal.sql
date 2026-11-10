-- =============================================================================
-- The repack station goes (team plan 4C-7; kltn-docs 08 BR-02: the LPN carton is the shipping carton, nothing is
-- packed again; owner's decision QD-1 of 10/11: nothing is archived, nothing stops the upgrade).
--
-- Since 4C-6 a fulfillment task's parcels are the cartons it picked, checked and labelled at the check station; the
-- repack station's endpoints refused every write, and with 4C-7 its code is gone. What it left:
--
--   1. Parcels still OPEN at the repack station (pick_line_id IS NULL) never shipped and never will: deleted, with
--      their contents. Sealed and voided parcels stay - shipments, loads and their history name them - with no
--      contents on record (ParcelContents reads cartons only).
--   2. fulfillment.package_line and fulfillment.package_scan, with their triggers and functions
--      (check_package_line_quantity, check_kit_part_line).
--   3. fulfillment.package: the parcel type, the fragile flag and its override, the kit (group, part, count, order
--      line, override reason), the outdated-label mark and the unused tracking number, with the kit triggers
--      (check_package_kit, check_kit_ships_whole, refuse_package_kit_change) and the CHECKs on them; ck_package_kind is restated without the
--      columns it named. A new row is a shipping carton (tg_package_is_carton).
--   4. ordering.customer_order.packing_started_at: the repack station's "packing started", replaced since 4C-6 by
--      first_labeled_at for the cancellation lock (kltn-docs 08 BR-04).
-- =============================================================================


-- 1. Parcels never sealed
DELETE FROM fulfillment.package_scan s
 USING fulfillment.package p
 WHERE p.id = s.package_id AND p.pick_line_id IS NULL AND p.status = 'OPEN';
DELETE FROM fulfillment.package_line l
 USING fulfillment.package p
 WHERE p.id = l.package_id AND p.pick_line_id IS NULL AND p.status = 'OPEN';
DELETE FROM fulfillment.package WHERE pick_line_id IS NULL AND status = 'OPEN';


-- 2. Parcel contents and scans
DROP TABLE IF EXISTS fulfillment.package_scan;
DROP TABLE IF EXISTS fulfillment.package_line;
DROP FUNCTION IF EXISTS fulfillment.check_package_line_quantity();
DROP FUNCTION IF EXISTS fulfillment.check_kit_part_line();


-- 3. What only a repack parcel had
DROP TRIGGER IF EXISTS tg_package_kit_check ON fulfillment.package;
DROP TRIGGER IF EXISTS tg_package_kit_ships_whole ON fulfillment.package;
DROP TRIGGER IF EXISTS tg_package_kit_fixed ON fulfillment.package;
DROP FUNCTION IF EXISTS fulfillment.refuse_package_kit_change();
DROP FUNCTION IF EXISTS fulfillment.check_package_kit();
DROP FUNCTION IF EXISTS fulfillment.check_kit_ships_whole();

ALTER TABLE fulfillment.package
    DROP COLUMN IF EXISTS package_type,
    DROP COLUMN IF EXISTS fragile,
    DROP COLUMN IF EXISTS fragile_overridden,
    DROP COLUMN IF EXISTS kit_group_id,
    DROP COLUMN IF EXISTS kit_part_no,
    DROP COLUMN IF EXISTS kit_part_count,
    DROP COLUMN IF EXISTS kit_order_line_id,
    DROP COLUMN IF EXISTS kit_override_reason,
    DROP COLUMN IF EXISTS label_outdated_at,
    DROP COLUMN IF EXISTS tracking_number;

-- Dropping package_type and kit_group_id took ck_package_kind with them: the two kinds of row, as 4C-6 states them.
ALTER TABLE fulfillment.package DROP CONSTRAINT IF EXISTS ck_package_kind;
ALTER TABLE fulfillment.package ADD CONSTRAINT ck_package_kind CHECK (
    (pick_line_id IS NULL AND lpn IS NULL AND status IN ('OPEN', 'SEALED', 'VOIDED') AND checked_at IS NULL
        AND labeled_at IS NULL AND removed_at IS NULL AND label_version = 0)
    OR (pick_line_id IS NOT NULL AND lpn IS NOT NULL AND status IN ('TO_CHECK', 'CHECKED', 'LABELED', 'REMOVED')
        AND sealed_at IS NULL AND voided_at IS NULL));
COMMENT ON CONSTRAINT ck_package_kind ON fulfillment.package IS
    'A shipping carton of the check station (team plan 4C-6), or a parcel the repack station sealed or voided before '
    '4C-7 removed it (history only: no new one is written).';


-- No parcel is packed any more: a new row is a shipping carton (PackageJpaEntity.carton).
CREATE FUNCTION fulfillment.check_package_is_carton() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.pick_line_id IS NULL THEN
        RAISE EXCEPTION 'package %: parcels are no longer packed; a shipping carton names its pick line (08 BR-02)',
            NEW.label_code USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_package_is_carton
    BEFORE INSERT ON fulfillment.package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_is_carton();

-- 4. Packing started
ALTER TABLE ordering.customer_order DROP COLUMN IF EXISTS packing_started_at;
