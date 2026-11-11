-- =============================================================================
-- Transfers between warehouses leave the system (kltn-docs 07/17): a Production Order delivers straight to the warehouse
-- that ships, so stock never changes warehouse. Transfer orders, their holds, their packing (transfer packs) and their
-- load plans, vehicles and loads are gone from the code; this removes what the database kept for them. Every row
-- dropped, and every value of a column dropped, is archived whole in platform.legacy_archive first.
--
-- Kept on purpose:
--   * moves inside one warehouse (move tasks, StockOperations.move) - kltn-docs 11 is still in scope;
--   * the TRANSFER_OUT / TRANSFER_IN movement types and the TRANSFER_ORDER_LINE ledger reference in the append-only
--     ledger's CHECKs: history rows carry them;
--   * load_plan.source_type and load_estimate.source_type, now ORDER only.
-- A transfer already dispatched took its goods out of stock (TRANSFER_OUT) and was never received anywhere: nothing of
-- it is left in stock to bring back. One still being picked releases its holds here.
--
-- Order, so no step meets a reference a later step removes:
--   1. archive;
--   2. dispatch loads and load plans of transfers, then transfer parcels and packing sessions (children first);
--   3. transfer holds, stock_item.reserved recomputed; transfer putaway tasks, estimates, number sequence; cycle counts
--      triggered by a transfer discrepancy (none is expected) become MANUAL;
--   4. the SQL functions that read transfer tables are rewritten, the triggers that list transfer columns recreated;
--   5. foreign keys into the transfer tables, then the tables and their guard functions;
--   6. the transfer columns, the NOT NULLs they relaxed restored, and the CHECKs that named them recreated;
--   7. the transfer-order permissions, advancing app_role.version of every role that held one.
-- No event publication needs cleaning: no event class or listener went with transfers.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Archive
-- ---------------------------------------------------------------------------------------------
-- What the steps below delete, selected before anything moves.
CREATE TEMPORARY TABLE transfer_plans ON COMMIT DROP AS
SELECT id FROM fulfillment.load_plan WHERE source_type = 'TRANSFER';

CREATE TEMPORARY TABLE transfer_loads ON COMMIT DROP AS
SELECT l.id
FROM fulfillment.dispatch_load l
JOIN fulfillment.load_plan_vehicle v ON v.id = l.load_plan_vehicle_id
WHERE v.load_plan_id IN (SELECT id FROM transfer_plans)
UNION
SELECT load_id FROM fulfillment.dispatch_load_shipment WHERE transfer_order_id IS NOT NULL;

CREATE TEMPORARY TABLE transfer_packages ON COMMIT DROP AS
SELECT id FROM fulfillment.package WHERE transfer_order_id IS NOT NULL OR kit_transfer_line_id IS NOT NULL;

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.transfer_order', t.id, to_jsonb(t) FROM inventory.transfer_order t
UNION ALL SELECT 'inventory.transfer_order_line', t.id, to_jsonb(t) FROM inventory.transfer_order_line t
UNION ALL SELECT 'fulfillment.dispatch_load_unload', t.id, to_jsonb(t) FROM fulfillment.dispatch_load_unload t
          WHERE t.transfer_order_id IS NOT NULL OR t.load_id IN (SELECT id FROM transfer_loads)
UNION ALL SELECT 'fulfillment.dispatch_load_package', t.id, to_jsonb(t) FROM fulfillment.dispatch_load_package t
          WHERE t.transfer_order_id IS NOT NULL OR t.load_id IN (SELECT id FROM transfer_loads)
UNION ALL SELECT 'fulfillment.dispatch_load_shipment', t.id, to_jsonb(t) FROM fulfillment.dispatch_load_shipment t
          WHERE t.transfer_order_id IS NOT NULL OR t.load_id IN (SELECT id FROM transfer_loads)
UNION ALL SELECT 'fulfillment.dispatch_load', t.id, to_jsonb(t) FROM fulfillment.dispatch_load t
          WHERE t.id IN (SELECT id FROM transfer_loads)
UNION ALL SELECT 'fulfillment.load_plan_package', t.id, to_jsonb(t) FROM fulfillment.load_plan_package t
          WHERE t.transfer_order_id IS NOT NULL OR t.load_plan_id IN (SELECT id FROM transfer_plans)
UNION ALL SELECT 'fulfillment.load_placement', t.id, to_jsonb(t) FROM fulfillment.load_placement t
          WHERE t.load_plan_id IN (SELECT id FROM transfer_plans)
UNION ALL SELECT 'fulfillment.load_plan_vehicle', t.id, to_jsonb(t) FROM fulfillment.load_plan_vehicle t
          WHERE t.load_plan_id IN (SELECT id FROM transfer_plans)
UNION ALL SELECT 'fulfillment.load_plan_vehicle_type', t.id, to_jsonb(t) FROM fulfillment.load_plan_vehicle_type t
          WHERE t.load_plan_id IN (SELECT id FROM transfer_plans)
UNION ALL SELECT 'fulfillment.load_plan', t.id, to_jsonb(t) FROM fulfillment.load_plan t
          WHERE t.id IN (SELECT id FROM transfer_plans)
UNION ALL SELECT 'fulfillment.package_scan', t.id, to_jsonb(t) FROM fulfillment.package_scan t
          WHERE t.transfer_line_id IS NOT NULL OR t.package_id IN (SELECT id FROM transfer_packages)
UNION ALL SELECT 'fulfillment.package_line', t.id, to_jsonb(t) FROM fulfillment.package_line t
          WHERE t.transfer_line_id IS NOT NULL OR t.package_id IN (SELECT id FROM transfer_packages)
UNION ALL SELECT 'fulfillment.package', t.id, to_jsonb(t) FROM fulfillment.package t
          WHERE t.id IN (SELECT id FROM transfer_packages)
UNION ALL SELECT 'fulfillment.pack', t.id, to_jsonb(t) FROM fulfillment.pack t WHERE t.transfer_order_id IS NOT NULL
UNION ALL SELECT 'inventory.stock_reservation', t.id, to_jsonb(t) FROM inventory.stock_reservation t
          WHERE t.transfer_order_id IS NOT NULL
UNION ALL SELECT 'warehouse.putaway_task', t.id, to_jsonb(t) FROM warehouse.putaway_task t
          WHERE t.transfer_order_line_id IS NOT NULL
UNION ALL SELECT 'fulfillment.load_estimate', t.id, to_jsonb(t) FROM fulfillment.load_estimate t
          WHERE t.source_type = 'TRANSFER'
UNION ALL SELECT 'inventory.cycle_count.trigger_type', t.id,
                 jsonb_build_object('id', t.id, 'trigger_type', t.trigger_type)
          FROM inventory.cycle_count t WHERE t.trigger_type = 'TRANSFER_DISCREPANCY'
-- The permissions and their grants (step 7).
UNION ALL SELECT 'identity.permission', t.id, to_jsonb(t) FROM identity.permission t
          WHERE t.code LIKE 'inventory-transfer-orders:%'
UNION ALL SELECT 'identity.role_permission', t.id, to_jsonb(t) FROM identity.role_permission t
          WHERE t.permission_id IN (SELECT id FROM identity.permission WHERE code LIKE 'inventory-transfer-orders:%');

-- The transfer-order number sequence, keyed by its type and day (no id of its own).
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'platform.document_sequence', gen_random_uuid(), to_jsonb(t) FROM platform.document_sequence t
WHERE t.document_type = 'TO';

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200) and of transfers between warehouses (V20261108000300), '
        'as they were. Read-only history.';


-- ---------------------------------------------------------------------------------------------
-- 2. Loads, plans, parcels and packing sessions of transfers
-- ---------------------------------------------------------------------------------------------
-- A parcel on a vehicle refers to its manifest line, which refers to its load: children first.
DELETE FROM fulfillment.dispatch_load_unload
WHERE transfer_order_id IS NOT NULL OR load_id IN (SELECT id FROM transfer_loads);
DELETE FROM fulfillment.dispatch_load_package
WHERE transfer_order_id IS NOT NULL OR load_id IN (SELECT id FROM transfer_loads);
DELETE FROM fulfillment.dispatch_load_shipment
WHERE transfer_order_id IS NOT NULL OR load_id IN (SELECT id FROM transfer_loads);
DELETE FROM fulfillment.dispatch_load WHERE id IN (SELECT id FROM transfer_loads);

-- A calculated plan places its parcels: a placement refers to its parcel's plan row (RESTRICT) and to its vehicle, so
-- the placements go first. The vehicle types go with the plan (ON DELETE CASCADE).
DELETE FROM fulfillment.load_placement WHERE load_plan_id IN (SELECT id FROM transfer_plans);
DELETE FROM fulfillment.load_plan_package
WHERE transfer_order_id IS NOT NULL OR load_plan_id IN (SELECT id FROM transfer_plans);
DELETE FROM fulfillment.load_plan_vehicle WHERE load_plan_id IN (SELECT id FROM transfer_plans);
DELETE FROM fulfillment.load_plan WHERE id IN (SELECT id FROM transfer_plans);

-- Package scans are append-only (rows leave only with their parent) and their parcel is RESTRICT: the guard is lifted
-- for this one delete, as V20261023000200 did for the ledger. The scans are archived above.
ALTER TABLE fulfillment.package_scan DISABLE TRIGGER tg_package_scan_append_only;
DELETE FROM fulfillment.package_scan
WHERE transfer_line_id IS NOT NULL OR package_id IN (SELECT id FROM transfer_packages);
ALTER TABLE fulfillment.package_scan ENABLE TRIGGER tg_package_scan_append_only;

-- Their contents go with them (ON DELETE CASCADE); then the packing sessions.
DELETE FROM fulfillment.package WHERE id IN (SELECT id FROM transfer_packages);
DELETE FROM fulfillment.pack WHERE transfer_order_id IS NOT NULL;


-- ---------------------------------------------------------------------------------------------
-- 3. Holds, tasks, estimates, numbers
-- ---------------------------------------------------------------------------------------------
CREATE TEMPORARY TABLE transfer_held_rows ON COMMIT DROP AS
SELECT DISTINCT stock_item_id AS id FROM inventory.stock_reservation
WHERE transfer_order_id IS NOT NULL AND stock_item_id IS NOT NULL;

DELETE FROM inventory.stock_reservation WHERE transfer_order_id IS NOT NULL;

-- stock_item.reserved follows what is still HELD on the rows that held transfer units.
UPDATE inventory.stock_item s
SET reserved         = COALESCE((SELECT SUM(r.quantity) FROM inventory.stock_reservation r
                                  WHERE r.stock_item_id = s.id AND r.status = 'HELD'), 0),
    version          = s.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE s.id IN (SELECT id FROM transfer_held_rows);

DELETE FROM warehouse.putaway_task WHERE transfer_order_line_id IS NOT NULL;
DELETE FROM fulfillment.load_estimate WHERE source_type = 'TRANSFER';
DELETE FROM platform.document_sequence WHERE document_type = 'TO';

UPDATE inventory.cycle_count SET trigger_type = 'MANUAL' WHERE trigger_type = 'TRANSFER_DISCREPANCY';


-- ---------------------------------------------------------------------------------------------
-- 4. Functions and triggers
-- ---------------------------------------------------------------------------------------------
-- An item is in use when a document names it; transfer lines no longer exist.
CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id uuid, p_sku character varying)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $function$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_package WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.move_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM catalog.pricing_rule WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM procurement.supplier_items WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.replenishment_proposal_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM catalog.customer_price WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_reservation WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku = p_sku)
$function$;

-- Every part of a kit is of one packing session - one order (08 BR-04) - for a line of that order.
CREATE OR REPLACE FUNCTION fulfillment.check_package_kit()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
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
                       OR pk.kit_order_line_id IS DISTINCT FROM NEW.kit_order_line_id)) THEN
        RAISE EXCEPTION 'tg_package_kit_check: the parts of kit % are of one order and agree on its size and line',
            NEW.kit_group_id USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_kit_check';
    END IF;
    RETURN NEW;
END
$function$;

-- A package holds lines of its own order only.
CREATE OR REPLACE FUNCTION fulfillment.check_package_line()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM fulfillment.package pk
          JOIN fulfillment.pack pa ON pa.id = pk.pack_id
          JOIN fulfillment.pick pi ON pi.id = pa.pick_id
          JOIN ordering.order_line l ON l.order_id = pi.order_id
         WHERE pk.id = NEW.package_id AND l.id = NEW.order_line_id) THEN
        RAISE EXCEPTION 'a package holds lines of its own order only'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$function$;

-- An order line is never packed more often than the order still wants it.
CREATE OR REPLACE FUNCTION fulfillment.check_package_line_quantity()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    packed  BIGINT;
    allowed INTEGER;
BEGIN
    -- Parcels voided, and parcels of a shipment that came back (RETURNED: its units may ship again), hold nothing.
    SELECT COALESCE(SUM(pl.quantity), 0) INTO packed
      FROM fulfillment.package_line pl
      JOIN fulfillment.package pk ON pk.id = pl.package_id
      LEFT JOIN fulfillment.shipment s ON s.id = pk.shipment_id
     WHERE pl.order_line_id = NEW.order_line_id AND pk.status <> 'VOIDED'
       AND (s.id IS NULL OR s.status <> 'RETURNED');
    SELECT l.quantity - l.cancelled_quantity INTO allowed
      FROM ordering.order_line l WHERE l.id = NEW.order_line_id;
    IF packed > allowed THEN
        RAISE EXCEPTION 'order line % would be packed % times; the order wants %', NEW.order_line_id, packed,
            allowed USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$function$;

-- Only part 1 of a kit holds goods, of its own line.
CREATE OR REPLACE FUNCTION fulfillment.check_kit_part_line()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
BEGIN
    IF EXISTS (SELECT 1 FROM fulfillment.package pk
                WHERE pk.id = NEW.package_id AND pk.kit_group_id IS NOT NULL
                  AND (pk.kit_part_no > 1 OR pk.kit_order_line_id IS DISTINCT FROM NEW.order_line_id)) THEN
        RAISE EXCEPTION 'tg_package_line_kit_part: only part 1 of a kit holds goods, of its own line'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_line_kit_part';
    END IF;
    RETURN NEW;
END
$function$;

-- A plan takes a sealed, measured parcel of a full-truckload shipment waiting for dispatch at its warehouse.
CREATE OR REPLACE FUNCTION fulfillment.check_load_plan_package()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    p fulfillment.package%ROWTYPE;
    s fulfillment.shipment%ROWTYPE;
    plan fulfillment.load_plan%ROWTYPE;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO p FROM fulfillment.package WHERE id = NEW.package_id;
    SELECT * INTO plan FROM fulfillment.load_plan WHERE id = NEW.load_plan_id;
    IF p.status <> 'SEALED' OR p.weight_kg IS NULL OR p.weight_kg <= 0 OR p.length_cm IS NULL OR p.length_cm <= 0
        OR p.width_cm IS NULL OR p.width_cm <= 0 OR p.height_cm IS NULL OR p.height_cm <= 0
        OR p.shipment_id IS DISTINCT FROM NEW.shipment_id THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: package % is not a sealed, measured parcel of its shipment',
            p.label_code USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    SELECT * INTO s FROM fulfillment.shipment WHERE id = NEW.shipment_id;
    IF s.status <> 'READY_TO_DISPATCH' OR s.delivery_mode <> 'FULL_TRUCKLOAD'
        OR s.warehouse_id <> plan.warehouse_id THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: shipment % is not a full truckload waiting for dispatch at '
            'the plan''s warehouse', s.code USING ERRCODE = 'check_violation',
            CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    RETURN NEW;
END
$function$;

-- A shipment on a manifest waits for dispatch, belongs on the load, and is on no other plan's load.
CREATE OR REPLACE FUNCTION fulfillment.check_load_shipment()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    s fulfillment.shipment%ROWTYPE;
    l fulfillment.dispatch_load%ROWTYPE;
    plan_id UUID;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO l FROM fulfillment.dispatch_load WHERE id = NEW.load_id;
    IF l.status NOT IN ('PLANNED', 'VEHICLE_BOOKED', 'LOADING') THEN
        RAISE EXCEPTION 'load % is %', l.code, l.status USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO s FROM fulfillment.shipment WHERE id = NEW.shipment_id;
    IF s.status <> 'READY_TO_DISPATCH' THEN
        RAISE EXCEPTION 'shipment % is %, not waiting for dispatch', s.code, s.status USING ERRCODE = 'check_violation';
    END IF;
    IF s.warehouse_id <> l.warehouse_id OR s.delivery_mode <> l.mode THEN
        RAISE EXCEPTION 'shipment % does not belong on load %', s.code, l.code USING ERRCODE = 'check_violation';
    END IF;
    IF s.carrier_id IS NOT NULL AND l.carrier_id IS NOT NULL AND s.carrier_id <> l.carrier_id THEN
        RAISE EXCEPTION 'shipment % goes with another carrier than load %', s.code, l.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF l.mode = 'FULL_TRUCKLOAD' THEN
        SELECT v.load_plan_id INTO plan_id FROM fulfillment.load_plan_vehicle v WHERE v.id = l.load_plan_vehicle_id;
        IF EXISTS (SELECT 1 FROM fulfillment.dispatch_load_shipment o
                     JOIN fulfillment.dispatch_load ol ON ol.id = o.load_id
                     LEFT JOIN fulfillment.load_plan_vehicle ov ON ov.id = ol.load_plan_vehicle_id
                    WHERE o.shipment_id = NEW.shipment_id AND o.active AND o.load_id <> NEW.load_id
                      AND ov.load_plan_id IS DISTINCT FROM plan_id) THEN
            RAISE EXCEPTION 'shipment % is on a load of another plan than load %', s.code, l.code
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$function$;

-- A parcel on a vehicle is a sealed parcel of a shipment on that load's open manifest.
CREATE OR REPLACE FUNCTION fulfillment.check_load_package()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    p fulfillment.package%ROWTYPE;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO p FROM fulfillment.package WHERE id = NEW.package_id;
    IF p.status <> 'SEALED' OR p.shipment_id IS DISTINCT FROM NEW.shipment_id THEN
        RAISE EXCEPTION 'package % is not a sealed parcel of shipment %', p.label_code, NEW.shipment_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.dispatch_load_shipment ls
                    JOIN fulfillment.dispatch_load l ON l.id = ls.load_id
                   WHERE ls.load_id = NEW.load_id AND ls.active AND ls.shipment_id = NEW.shipment_id
                     AND l.status IN ('PLANNED', 'VEHICLE_BOOKED', 'LOADING')) THEN
        RAISE EXCEPTION 'package % is not on an open manifest with its shipment', p.label_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$function$;

-- Triggers whose column list or WHEN names a transfer column depend on it: recreated without it before the drop.
DROP TRIGGER tg_package_transfer_check ON fulfillment.package;
DROP FUNCTION fulfillment.check_package_transfer();

DROP TRIGGER tg_package_kit_fixed ON fulfillment.package;
CREATE TRIGGER tg_package_kit_fixed BEFORE UPDATE ON fulfillment.package
    FOR EACH ROW
    WHEN (OLD.kit_group_id IS DISTINCT FROM NEW.kit_group_id OR OLD.kit_part_no IS DISTINCT FROM NEW.kit_part_no
          OR OLD.kit_part_count IS DISTINCT FROM NEW.kit_part_count
          OR OLD.kit_order_line_id IS DISTINCT FROM NEW.kit_order_line_id
          OR (OLD.kit_group_id IS NOT NULL AND OLD.pack_id IS DISTINCT FROM NEW.pack_id))
    EXECUTE FUNCTION fulfillment.refuse_package_kit_change();

DROP TRIGGER tg_package_line_quantity ON fulfillment.package_line;
CREATE TRIGGER tg_package_line_quantity AFTER INSERT OR UPDATE OF quantity, order_line_id ON fulfillment.package_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_line_quantity();

DROP TRIGGER tg_package_line_kit_part ON fulfillment.package_line;
CREATE TRIGGER tg_package_line_kit_part BEFORE INSERT OR UPDATE OF package_id, order_line_id ON fulfillment.package_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_kit_part_line();

DROP TRIGGER tg_load_plan_package_check ON fulfillment.load_plan_package;
CREATE TRIGGER tg_load_plan_package_check BEFORE INSERT OR UPDATE OF active, shipment_id ON fulfillment.load_plan_package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_load_plan_package();


-- ---------------------------------------------------------------------------------------------
-- 5. The transfer tables
-- ---------------------------------------------------------------------------------------------
-- The parcel-on-vehicle key into the manifest's transfer pair goes before the pair's unique key does.
ALTER TABLE fulfillment.dispatch_load_package DROP CONSTRAINT fk_dispatch_load_package_transfer;
ALTER TABLE fulfillment.dispatch_load_shipment DROP CONSTRAINT fk_dispatch_load_shipment_transfer;
ALTER TABLE fulfillment.dispatch_load_unload DROP CONSTRAINT fk_dispatch_load_unload_transfer;
ALTER TABLE fulfillment.load_plan_package DROP CONSTRAINT fk_load_plan_package_transfer_order;
ALTER TABLE fulfillment.pack DROP CONSTRAINT fk_pack_transfer_order;
ALTER TABLE fulfillment.package DROP CONSTRAINT fk_package_transfer_order;
ALTER TABLE fulfillment.package DROP CONSTRAINT fk_package_kit_transfer_line;
ALTER TABLE fulfillment.package_line DROP CONSTRAINT fk_package_line_transfer_line;
ALTER TABLE fulfillment.package_scan DROP CONSTRAINT fk_package_scan_transfer_line;
ALTER TABLE inventory.stock_reservation DROP CONSTRAINT fk_stock_reservation_transfer_order;
ALTER TABLE warehouse.putaway_task DROP CONSTRAINT fk_putaway_task_transfer_line;

DROP TABLE inventory.transfer_order_line;
DROP TABLE inventory.transfer_order;
DROP FUNCTION inventory.guard_transfer_order_draft_header();
DROP FUNCTION inventory.guard_transfer_order_draft_line();


-- ---------------------------------------------------------------------------------------------
-- 6. Columns and CHECKs
-- ---------------------------------------------------------------------------------------------
-- The CHECKs, indexes and unique keys naming these columns go with them.
ALTER TABLE inventory.stock_reservation DROP COLUMN transfer_order_id;
ALTER TABLE warehouse.putaway_task DROP COLUMN transfer_order_line_id;
ALTER TABLE fulfillment.pack DROP COLUMN transfer_order_id;
ALTER TABLE fulfillment.package
    DROP COLUMN transfer_order_id,
    DROP COLUMN kit_transfer_line_id,
    DROP COLUMN stock_package_id;
ALTER TABLE fulfillment.package_line DROP COLUMN transfer_line_id;
ALTER TABLE fulfillment.package_scan DROP COLUMN transfer_line_id;
ALTER TABLE fulfillment.load_plan_package DROP COLUMN transfer_order_id;
ALTER TABLE fulfillment.dispatch_load_shipment DROP COLUMN transfer_order_id;
ALTER TABLE fulfillment.dispatch_load_package DROP COLUMN transfer_order_id;
ALTER TABLE fulfillment.dispatch_load_unload DROP COLUMN transfer_order_id;

-- What every row has again now that a transfer cannot stand in for it.
ALTER TABLE inventory.stock_reservation ALTER COLUMN order_id SET NOT NULL;
ALTER TABLE fulfillment.pack ALTER COLUMN pick_id SET NOT NULL;
ALTER TABLE fulfillment.package_line ALTER COLUMN order_line_id SET NOT NULL;
ALTER TABLE fulfillment.package_scan ALTER COLUMN order_line_id SET NOT NULL;
ALTER TABLE fulfillment.load_plan_package ALTER COLUMN shipment_id SET NOT NULL;
ALTER TABLE fulfillment.dispatch_load_shipment ALTER COLUMN shipment_id SET NOT NULL;
ALTER TABLE fulfillment.dispatch_load_package ALTER COLUMN shipment_id SET NOT NULL;
ALTER TABLE fulfillment.dispatch_load_unload ALTER COLUMN shipment_id SET NOT NULL;

-- A hold on a stock item, or a SKU reservation of an order (ck_stock_reservation_owner went with its column: order_id
-- is NOT NULL now).
ALTER TABLE inventory.stock_reservation
    ADD CONSTRAINT ck_stock_reservation_kind CHECK (
        (stock_item_id IS NOT NULL AND status <> 'ALLOCATED')
        OR (stock_item_id IS NULL AND allocated_from_id IS NULL AND status <> 'CONSUMED'));
COMMENT ON COLUMN inventory.stock_item.reserved IS
    'Units of this row held by HELD reservations: allocations and older checkout holds (SKU reservations sit on no row).';

ALTER TABLE warehouse.putaway_task
    ADD CONSTRAINT ck_putaway_task_source CHECK (num_nonnulls(goods_receipt_line_id, return_request_line_id) = 1);

ALTER TABLE fulfillment.package
    ADD CONSTRAINT ck_package_kit CHECK (
        (kit_group_id IS NULL AND kit_part_no IS NULL AND kit_part_count IS NULL AND kit_order_line_id IS NULL)
        OR (kit_group_id IS NOT NULL AND kit_part_no IS NOT NULL AND kit_part_count IS NOT NULL
            AND kit_order_line_id IS NOT NULL
            AND kit_part_count >= 2 AND kit_part_no >= 1 AND kit_part_no <= kit_part_count));

ALTER TABLE fulfillment.load_plan DROP CONSTRAINT ck_load_plan_source;
ALTER TABLE fulfillment.load_plan ADD CONSTRAINT ck_load_plan_source CHECK (source_type IN ('ORDER'));
ALTER TABLE fulfillment.load_estimate DROP CONSTRAINT ck_load_estimate_source_type;
ALTER TABLE fulfillment.load_estimate ADD CONSTRAINT ck_load_estimate_source_type CHECK (source_type IN ('ORDER'));

ALTER TABLE inventory.cycle_count DROP CONSTRAINT ck_cycle_count_trigger;
ALTER TABLE inventory.cycle_count
    ADD CONSTRAINT ck_cycle_count_trigger CHECK (trigger_type IN ('SCHEDULED', 'SHORT_PICK', 'MOVE_DISCREPANCY', 'MANUAL'));


-- ---------------------------------------------------------------------------------------------
-- 7. Permissions
-- ---------------------------------------------------------------------------------------------
-- role_permission first, advancing the version of every role that loses a grant in the same statement: deleting the
-- permission rows first would cascade the grants away without moving any version.
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE p.id = rp.permission_id AND p.code LIKE 'inventory-transfer-orders:%'
    RETURNING rp.role_id)
UPDATE identity.app_role r
SET version          = r.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE r.id IN (SELECT role_id FROM lost);

DELETE FROM identity.permission WHERE code LIKE 'inventory-transfer-orders:%';
