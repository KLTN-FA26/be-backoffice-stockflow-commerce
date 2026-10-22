-- =============================================================================
-- Transfer orders through packing and load planning (SCRUM-287 PR T; lead's decisions LP-9, LP2-1, LP2-8, LP-3,
-- LP-11; kltn-docs 10 §4 step 5, §5, BR-04; 09 §3 "Dữ liệu tối thiểu" (LoadPlan.source_type: đơn khách / TO), §4.2
-- step 1 and 5, BR-08, BR-09, BR-15; 08 step 5-9, BR-03). Modules: inventory (the transfer), fulfillment (the rest)
--
-- 10 step 5: the source warehouse packs the transfer (weight and size per parcel), plans the trip with Load Planning
-- when it goes by truck or container, loads by the layout, and Dispatch moves the stock into In-Transit. 09 §4.2 step 1
-- plans the parcels "của các đơn giao nguyên chuyến hoặc của MỘT lệnh chuyển kho": a TRANSFER plan holds the parcels of
-- one transfer order, whole, and nothing of a customer order (and the other way round).
--
--   stock_reservation       a hold belongs to an order, a production order OR a transfer order (10 step 4, BR-02/03:
--                           the source's stock is allocated to the transfer at picking - here when its packing
--                           completes, an unopened carton shipped as it is holding its own rows and lot). Built on
--                           #147's holds (V20261023000100): a transfer's hold is a hold on one stock row (bin and lot),
--                           like a production hold - never a SKU reservation, never an allocation of one, never
--                           expiring; released when picking is cancelled, consumed at dispatch.
--   transfer_order          gains delivery_mode (LP2-8: FULL_TRUCKLOAD by default for new transfers; PARCEL keeps the
--                           manual dispatch of SCRUM-327 - every transfer existing before this version is PARCEL), packed_at (its packing completed: ready for a load plan) and goods_left_at
--                           (goods of it first left on a plan's vehicle, before the last vehicle's signature moves it to
--                           IN_TRANSIT - from then picking is no longer cancelled, BR-04, as ordering.goods_left_at for
--                           orders since V20261022001400).
--   pack                    the packing session of a pick (an order) OR of a transfer order: pick_id becomes nullable,
--                           transfer_order_id is added, exactly one is set. One live session per transfer.
--   package                 transfer_order_id: a transfer's parcel (the same as its pack's - tg_package_transfer_check);
--                           a parcel belongs to a shipment OR a transfer, never both (ck_package_owner).
--                           kit_transfer_line_id: the transfer line a transfer's kit is opened for (kit_order_line_id
--                           for an order's); exactly one of the two on a kit part (ck_package_kit).
--                           stock_package_id: an unopened stock carton shipped as it is (LP2-8), only on a transfer's
--                           parcel, at most once among parcels not voided.
--   package_line,           order_line_id becomes nullable, transfer_line_id is added, exactly one is set. The triggers
--   package_scan            that keep a parcel to its own order and its lines within what the order wants learn the
--                           transfer: its own lines, never more than requested.
--   load_plan_package,      shipment_id becomes nullable, transfer_order_id is added, exactly one is set - the one the
--   dispatch_load_shipment, plan's source_type says (ORDER: shipments, TRANSFER: one transfer order). Their triggers check
--   dispatch_load_package   a transfer as they check a shipment: packed, picking, at the plan's / load's warehouse, as a
--                           full truckload, on the vehicles of one plan.
--
-- No permission changes: transfer packing is the packer's work (fulfillment-packing:UPDATE, fulfillment-packages:READ),
-- planning and dispatching it the dispatcher's (fulfillment-load-plans, fulfillment-dispatch), all seeded already - no
-- app_role.version moves.
-- =============================================================================


-- ----------------------------------------------------------------------------- inventory.transfer_order
-- Transfers that exist already keep travelling as they did - dispatched by hand (PARCEL): an APPROVED or PICKING one
-- must not be stranded behind a packing screen it was never planned for (review of #164). New ones default to
-- FULL_TRUCKLOAD (LP2-8), set just below.
ALTER TABLE inventory.transfer_order
    ADD COLUMN delivery_mode VARCHAR(16) NOT NULL DEFAULT 'PARCEL',
    ADD COLUMN packed_at     TIMESTAMPTZ,
    ADD COLUMN goods_left_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_transfer_order_delivery_mode CHECK (delivery_mode IN ('PARCEL', 'FULL_TRUCKLOAD')),
    -- Packed and gone only once it is being picked (10 §5: Approved -> Picking -> In Transit).
    ADD CONSTRAINT ck_transfer_order_packed CHECK (packed_at IS NULL
        OR status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CANCELLED')),
    ADD CONSTRAINT ck_transfer_order_goods_left CHECK (goods_left_at IS NULL
        OR status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CANCELLED'));

ALTER TABLE inventory.transfer_order ALTER COLUMN delivery_mode SET DEFAULT 'FULL_TRUCKLOAD';

COMMENT ON COLUMN inventory.transfer_order.delivery_mode IS
    'LP2-8: FULL_TRUCKLOAD (packed, load-planned, dispatched at the last vehicle''s signature) or PARCEL (manual dispatch)';
COMMENT ON COLUMN inventory.transfer_order.packed_at IS 'Its packing completed: ready for a load plan (10 step 5)';
COMMENT ON COLUMN inventory.transfer_order.goods_left_at IS
    'Goods of it first left on a load plan''s vehicle; from then picking is not cancelled (10 BR-04)';


-- ----------------------------------------------------------------------------- stock_reservation: a third owner
ALTER TABLE inventory.stock_reservation
    ADD COLUMN transfer_order_id UUID,
    ADD CONSTRAINT fk_stock_reservation_transfer_order FOREIGN KEY (transfer_order_id)
        REFERENCES inventory.transfer_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_stock_reservation_transfer_pinned CHECK (transfer_order_id IS NULL OR expires_at IS NULL);
ALTER TABLE inventory.stock_reservation DROP CONSTRAINT ck_stock_reservation_owner;
ALTER TABLE inventory.stock_reservation
    ADD CONSTRAINT ck_stock_reservation_owner CHECK (num_nonnulls(order_id, production_order_id, transfer_order_id) = 1);
CREATE INDEX ix_stock_reservation_transfer_order ON inventory.stock_reservation (transfer_order_id)
    WHERE transfer_order_id IS NOT NULL;
-- #147 (V20261023000100): a transfer's hold is a hold on one stock row, like a production hold - never a SKU
-- reservation (those are a customer order's checkout holds) and never an allocation of one.
ALTER TABLE inventory.stock_reservation DROP CONSTRAINT ck_stock_reservation_kind;
ALTER TABLE inventory.stock_reservation ADD CONSTRAINT ck_stock_reservation_kind CHECK (
    (stock_item_id IS NOT NULL AND status <> 'ALLOCATED')
    OR (stock_item_id IS NULL AND order_id IS NOT NULL AND production_order_id IS NULL AND transfer_order_id IS NULL
        AND allocated_from_id IS NULL AND status <> 'CONSUMED'));


-- ----------------------------------------------------------------------------- pack
ALTER TABLE fulfillment.pack
    ALTER COLUMN pick_id DROP NOT NULL,
    ADD COLUMN transfer_order_id UUID,
    ADD CONSTRAINT fk_pack_transfer_order FOREIGN KEY (transfer_order_id) REFERENCES inventory.transfer_order (id),
    ADD CONSTRAINT ck_pack_task CHECK ((pick_id IS NULL) <> (transfer_order_id IS NULL));

CREATE UNIQUE INDEX uk_pack_transfer_live ON fulfillment.pack (transfer_order_id)
    WHERE transfer_order_id IS NOT NULL AND status <> 'CANCELLED';


-- ----------------------------------------------------------------------------- package
ALTER TABLE fulfillment.package
    ADD COLUMN transfer_order_id    UUID,
    ADD COLUMN kit_transfer_line_id UUID,
    ADD COLUMN stock_package_id     UUID,
    ADD CONSTRAINT fk_package_transfer_order FOREIGN KEY (transfer_order_id)
        REFERENCES inventory.transfer_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_package_kit_transfer_line FOREIGN KEY (kit_transfer_line_id)
        REFERENCES inventory.transfer_order_line (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_package_stock_package FOREIGN KEY (stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_package_owner CHECK (shipment_id IS NULL OR transfer_order_id IS NULL),
    ADD CONSTRAINT ck_package_stock_package CHECK (stock_package_id IS NULL OR transfer_order_id IS NOT NULL);

ALTER TABLE fulfillment.package DROP CONSTRAINT ck_package_kit;
ALTER TABLE fulfillment.package ADD CONSTRAINT ck_package_kit CHECK (
    (kit_group_id IS NULL AND kit_part_no IS NULL AND kit_part_count IS NULL AND kit_order_line_id IS NULL
        AND kit_transfer_line_id IS NULL)
    OR (kit_group_id IS NOT NULL AND kit_part_no IS NOT NULL AND kit_part_count IS NOT NULL
        AND (kit_order_line_id IS NULL) <> (kit_transfer_line_id IS NULL)
        AND kit_part_count >= 2 AND kit_part_no >= 1 AND kit_part_no <= kit_part_count));

CREATE INDEX ix_package_transfer_order ON fulfillment.package (transfer_order_id) WHERE transfer_order_id IS NOT NULL;
-- An unopened carton ships once (LP2-8).
CREATE UNIQUE INDEX uk_package_stock_package_live ON fulfillment.package (stock_package_id)
    WHERE stock_package_id IS NOT NULL AND status <> 'VOIDED';

COMMENT ON COLUMN fulfillment.package.transfer_order_id IS
    'The transfer order this parcel carries (its pack''s); never together with shipment_id';
COMMENT ON COLUMN fulfillment.package.kit_transfer_line_id IS 'The transfer line whose one unit a transfer''s kit holds';
COMMENT ON COLUMN fulfillment.package.stock_package_id IS
    'LP2-8: the unopened stock carton shipped as it is in this parcel (a transfer''s only)';

-- A transfer's parcel is of its pack's transfer; an order's parcel names none.
CREATE FUNCTION fulfillment.check_package_transfer()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.transfer_order_id IS DISTINCT FROM (SELECT pa.transfer_order_id FROM fulfillment.pack pa
                                                WHERE pa.id = NEW.pack_id) THEN
        RAISE EXCEPTION 'tg_package_transfer_check: package % is of its packing session''s transfer order, or none',
            NEW.label_code USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_transfer_check';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_package_transfer_check BEFORE INSERT OR UPDATE OF transfer_order_id, pack_id
    ON fulfillment.package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_transfer();

-- One order or one transfer, one line, one size per kit (V20261022001000, now for transfers too).
CREATE OR REPLACE FUNCTION fulfillment.check_package_kit()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.kit_order_line_id IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM fulfillment.pack pa
                         JOIN fulfillment.pick pi ON pi.id = pa.pick_id
                         JOIN ordering.order_line l ON l.order_id = pi.order_id
                        WHERE pa.id = NEW.pack_id AND l.id = NEW.kit_order_line_id) THEN
            RAISE EXCEPTION 'tg_package_kit_check: a kit holds a line of its own order'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_kit_check';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM fulfillment.pack pa
                        JOIN inventory.transfer_order_line l ON l.transfer_order_id = pa.transfer_order_id
                       WHERE pa.id = NEW.pack_id AND l.id = NEW.kit_transfer_line_id) THEN
        RAISE EXCEPTION 'tg_package_kit_check: a kit holds a line of its own transfer order'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_kit_check';
    END IF;
    IF EXISTS (SELECT 1 FROM fulfillment.package pk
                WHERE pk.kit_group_id = NEW.kit_group_id AND pk.id <> NEW.id
                  AND (pk.pack_id <> NEW.pack_id OR pk.kit_part_count <> NEW.kit_part_count
                       OR pk.kit_order_line_id IS DISTINCT FROM NEW.kit_order_line_id
                       OR pk.kit_transfer_line_id IS DISTINCT FROM NEW.kit_transfer_line_id)) THEN
        RAISE EXCEPTION 'tg_package_kit_check: the parts of kit % are of one order (or transfer) and agree on its size and line',
            NEW.kit_group_id USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_kit_check';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER tg_package_kit_fixed ON fulfillment.package;
CREATE TRIGGER tg_package_kit_fixed BEFORE UPDATE ON fulfillment.package
    FOR EACH ROW WHEN (OLD.kit_group_id IS DISTINCT FROM NEW.kit_group_id
        OR OLD.kit_part_no IS DISTINCT FROM NEW.kit_part_no
        OR OLD.kit_part_count IS DISTINCT FROM NEW.kit_part_count
        OR OLD.kit_order_line_id IS DISTINCT FROM NEW.kit_order_line_id
        OR OLD.kit_transfer_line_id IS DISTINCT FROM NEW.kit_transfer_line_id
        OR (OLD.kit_group_id IS NOT NULL AND OLD.pack_id IS DISTINCT FROM NEW.pack_id))
    EXECUTE FUNCTION fulfillment.refuse_package_kit_change();


-- ----------------------------------------------------------------------------- package_line, package_scan
ALTER TABLE fulfillment.package_line
    ALTER COLUMN order_line_id DROP NOT NULL,
    ADD COLUMN transfer_line_id UUID,
    ADD CONSTRAINT fk_package_line_transfer_line FOREIGN KEY (transfer_line_id)
        REFERENCES inventory.transfer_order_line (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_package_line_source CHECK ((order_line_id IS NULL) <> (transfer_line_id IS NULL)),
    ADD CONSTRAINT uk_package_line_transfer_pair UNIQUE (package_id, transfer_line_id);

CREATE INDEX ix_package_line_transfer_line ON fulfillment.package_line (transfer_line_id)
    WHERE transfer_line_id IS NOT NULL;

ALTER TABLE fulfillment.package_scan
    ALTER COLUMN order_line_id DROP NOT NULL,
    ADD COLUMN transfer_line_id UUID,
    ADD CONSTRAINT fk_package_scan_transfer_line FOREIGN KEY (transfer_line_id)
        REFERENCES inventory.transfer_order_line (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_package_scan_source CHECK ((order_line_id IS NULL) <> (transfer_line_id IS NULL));

-- 08 BR-04: a parcel holds lines of its own order - or of its own transfer order.
CREATE OR REPLACE FUNCTION fulfillment.check_package_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.order_line_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM fulfillment.package pk
              JOIN fulfillment.pack pa ON pa.id = pk.pack_id
              JOIN fulfillment.pick pi ON pi.id = pa.pick_id
              JOIN ordering.order_line l ON l.order_id = pi.order_id
             WHERE pk.id = NEW.package_id AND l.id = NEW.order_line_id) THEN
            RAISE EXCEPTION 'a package holds lines of its own order only'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1 FROM fulfillment.package pk
          JOIN inventory.transfer_order_line l ON l.transfer_order_id = pk.transfer_order_id
         WHERE pk.id = NEW.package_id AND l.id = NEW.transfer_line_id) THEN
        RAISE EXCEPTION 'a package holds lines of its own transfer order only'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

-- 08 BR-01: never more of a line than the order still wants - or than the transfer requests.
CREATE OR REPLACE FUNCTION fulfillment.check_package_line_quantity()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    packed  BIGINT;
    allowed INTEGER;
BEGIN
    IF NEW.order_line_id IS NOT NULL THEN
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
    ELSE
        SELECT COALESCE(SUM(pl.quantity), 0) INTO packed
          FROM fulfillment.package_line pl
          JOIN fulfillment.package pk ON pk.id = pl.package_id
         WHERE pl.transfer_line_id = NEW.transfer_line_id AND pk.status <> 'VOIDED';
        SELECT l.requested_qty INTO allowed FROM inventory.transfer_order_line l WHERE l.id = NEW.transfer_line_id;
        IF packed > allowed THEN
            RAISE EXCEPTION 'transfer line % would be packed % times; the transfer requests %', NEW.transfer_line_id,
                packed, allowed USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER tg_package_line_quantity ON fulfillment.package_line;
CREATE TRIGGER tg_package_line_quantity AFTER INSERT OR UPDATE OF quantity, order_line_id, transfer_line_id
    ON fulfillment.package_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_line_quantity();

-- LP2-6: only part 1 of a kit holds goods, of its own (order or transfer) line.
CREATE OR REPLACE FUNCTION fulfillment.check_kit_part_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM fulfillment.package pk
                WHERE pk.id = NEW.package_id AND pk.kit_group_id IS NOT NULL
                  AND (pk.kit_part_no > 1 OR pk.kit_order_line_id IS DISTINCT FROM NEW.order_line_id
                       OR pk.kit_transfer_line_id IS DISTINCT FROM NEW.transfer_line_id)) THEN
        RAISE EXCEPTION 'tg_package_line_kit_part: only part 1 of a kit holds goods, of its own line'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_line_kit_part';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER tg_package_line_kit_part ON fulfillment.package_line;
CREATE TRIGGER tg_package_line_kit_part BEFORE INSERT OR UPDATE OF package_id, order_line_id, transfer_line_id
    ON fulfillment.package_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_kit_part_line();


-- ----------------------------------------------------------------------------- load_plan_package
ALTER TABLE fulfillment.load_plan_package
    ALTER COLUMN shipment_id DROP NOT NULL,
    ADD COLUMN transfer_order_id UUID,
    ADD CONSTRAINT fk_load_plan_package_transfer_order FOREIGN KEY (transfer_order_id)
        REFERENCES inventory.transfer_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_load_plan_package_source CHECK ((shipment_id IS NULL) <> (transfer_order_id IS NULL));

CREATE INDEX ix_load_plan_package_transfer ON fulfillment.load_plan_package (transfer_order_id) WHERE active;

-- BR-01 / BR-09 / LP2-1: a sealed, measured parcel - of a full-truckload shipment waiting for dispatch at the plan's
-- warehouse (an ORDER plan), or of the one packed transfer order being picked at the plan's warehouse (a TRANSFER plan).
CREATE OR REPLACE FUNCTION fulfillment.check_load_plan_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    p fulfillment.package%ROWTYPE;
    s fulfillment.shipment%ROWTYPE;
    t inventory.transfer_order%ROWTYPE;
    plan fulfillment.load_plan%ROWTYPE;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO p FROM fulfillment.package WHERE id = NEW.package_id;
    SELECT * INTO plan FROM fulfillment.load_plan WHERE id = NEW.load_plan_id;
    IF p.status <> 'SEALED' OR p.weight_kg IS NULL OR p.weight_kg <= 0 OR p.length_cm IS NULL OR p.length_cm <= 0
        OR p.width_cm IS NULL OR p.width_cm <= 0 OR p.height_cm IS NULL OR p.height_cm <= 0
        OR p.shipment_id IS DISTINCT FROM NEW.shipment_id OR p.transfer_order_id IS DISTINCT FROM NEW.transfer_order_id
        THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: package % is not a sealed, measured parcel of its shipment or '
            'transfer', p.label_code USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    IF (plan.source_type = 'TRANSFER') <> (NEW.transfer_order_id IS NOT NULL) THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: load plan % is a % plan; package % is not', plan.code,
            plan.source_type, p.label_code USING ERRCODE = 'check_violation',
            CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    IF NEW.shipment_id IS NOT NULL THEN
        SELECT * INTO s FROM fulfillment.shipment WHERE id = NEW.shipment_id;
        IF s.status <> 'READY_TO_DISPATCH' OR s.delivery_mode <> 'FULL_TRUCKLOAD'
            OR s.warehouse_id <> plan.warehouse_id THEN
            RAISE EXCEPTION 'tg_load_plan_package_check: shipment % is not a full truckload waiting for dispatch at '
                'the plan''s warehouse', s.code USING ERRCODE = 'check_violation',
                CONSTRAINT = 'tg_load_plan_package_check';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO t FROM inventory.transfer_order WHERE id = NEW.transfer_order_id;
    IF t.status <> 'PICKING' OR t.packed_at IS NULL OR t.delivery_mode <> 'FULL_TRUCKLOAD'
        OR t.from_warehouse_id <> plan.warehouse_id THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: transfer % is not a packed full truckload being picked at the '
            'plan''s warehouse', t.transfer_number USING ERRCODE = 'check_violation',
            CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    -- 09 §4.2 step 1: the parcels of ONE transfer order.
    IF EXISTS (SELECT 1 FROM fulfillment.load_plan_package o
                WHERE o.load_plan_id = NEW.load_plan_id AND o.active AND o.id <> NEW.id
                  AND o.transfer_order_id IS DISTINCT FROM NEW.transfer_order_id) THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: load plan % holds another transfer order', plan.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER tg_load_plan_package_check ON fulfillment.load_plan_package;
CREATE TRIGGER tg_load_plan_package_check BEFORE INSERT OR UPDATE OF active, shipment_id, transfer_order_id
    ON fulfillment.load_plan_package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_load_plan_package();


-- ----------------------------------------------------------------------------- dispatch_load_shipment / _package
ALTER TABLE fulfillment.dispatch_load_shipment
    ALTER COLUMN shipment_id DROP NOT NULL,
    ADD COLUMN transfer_order_id UUID,
    ADD CONSTRAINT fk_dispatch_load_shipment_transfer FOREIGN KEY (transfer_order_id)
        REFERENCES inventory.transfer_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_dispatch_load_shipment_source CHECK ((shipment_id IS NULL) <> (transfer_order_id IS NULL)),
    -- The target of fk_dispatch_load_package_transfer, as uk_dispatch_load_shipment is of the shipment's.
    ADD CONSTRAINT uk_dispatch_load_transfer UNIQUE (load_id, transfer_order_id);

CREATE INDEX ix_dispatch_load_shipment_transfer ON fulfillment.dispatch_load_shipment (transfer_order_id) WHERE active;

ALTER TABLE fulfillment.dispatch_load_package
    ALTER COLUMN shipment_id DROP NOT NULL,
    ADD COLUMN transfer_order_id UUID,
    ADD CONSTRAINT ck_dispatch_load_package_source CHECK ((shipment_id IS NULL) <> (transfer_order_id IS NULL)),
    -- A transfer's parcel is loaded only with its transfer on the manifest.
    ADD CONSTRAINT fk_dispatch_load_package_transfer FOREIGN KEY (load_id, transfer_order_id)
        REFERENCES fulfillment.dispatch_load_shipment (load_id, transfer_order_id) ON DELETE RESTRICT;

CREATE INDEX ix_dispatch_load_package_transfer ON fulfillment.dispatch_load_package (transfer_order_id)
    WHERE transfer_order_id IS NOT NULL;

-- 09 §4.1, BR-01 as before (V20261022001400); a transfer goes on a vehicle of its load plan only: packed, being picked,
-- leaving from the load's warehouse as a full truckload, and on the vehicles of ONE plan (LP-3).
CREATE OR REPLACE FUNCTION fulfillment.check_load_shipment()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    s fulfillment.shipment%ROWTYPE;
    t inventory.transfer_order%ROWTYPE;
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
    IF NEW.transfer_order_id IS NOT NULL THEN
        SELECT * INTO t FROM inventory.transfer_order WHERE id = NEW.transfer_order_id;
        IF t.status <> 'PICKING' OR t.packed_at IS NULL THEN
            RAISE EXCEPTION 'transfer % is %, not packed for dispatch', t.transfer_number, t.status
                USING ERRCODE = 'check_violation';
        END IF;
        IF l.mode <> 'FULL_TRUCKLOAD' OR t.delivery_mode <> 'FULL_TRUCKLOAD'
            OR t.from_warehouse_id <> l.warehouse_id THEN
            RAISE EXCEPTION 'transfer % does not belong on load %', t.transfer_number, l.code
                USING ERRCODE = 'check_violation';
        END IF;
        SELECT v.load_plan_id INTO plan_id FROM fulfillment.load_plan_vehicle v WHERE v.id = l.load_plan_vehicle_id;
        IF EXISTS (SELECT 1 FROM fulfillment.dispatch_load_shipment o
                     JOIN fulfillment.dispatch_load ol ON ol.id = o.load_id
                     LEFT JOIN fulfillment.load_plan_vehicle ov ON ov.id = ol.load_plan_vehicle_id
                    WHERE o.transfer_order_id = NEW.transfer_order_id AND o.active AND o.load_id <> NEW.load_id
                      AND ov.load_plan_id IS DISTINCT FROM plan_id) THEN
            RAISE EXCEPTION 'transfer % is on a load of another plan than load %', t.transfer_number, l.code
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
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
$$;

-- 09 BR-01: a parcel loaded is sealed and belongs to the shipment - or the transfer - it is loaded with.
CREATE OR REPLACE FUNCTION fulfillment.check_load_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    p fulfillment.package%ROWTYPE;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO p FROM fulfillment.package WHERE id = NEW.package_id;
    IF p.status <> 'SEALED' OR p.shipment_id IS DISTINCT FROM NEW.shipment_id
        OR p.transfer_order_id IS DISTINCT FROM NEW.transfer_order_id THEN
        RAISE EXCEPTION 'package % is not a sealed parcel of shipment % / transfer %', p.label_code, NEW.shipment_id,
            NEW.transfer_order_id USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.dispatch_load_shipment ls
                    JOIN fulfillment.dispatch_load l ON l.id = ls.load_id
                   WHERE ls.load_id = NEW.load_id AND ls.active
                     AND ls.shipment_id IS NOT DISTINCT FROM NEW.shipment_id
                     AND ls.transfer_order_id IS NOT DISTINCT FROM NEW.transfer_order_id
                     AND l.status IN ('PLANNED', 'VEHICLE_BOOKED', 'LOADING')) THEN
        RAISE EXCEPTION 'package % is not on an open manifest with its shipment or transfer', p.label_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
