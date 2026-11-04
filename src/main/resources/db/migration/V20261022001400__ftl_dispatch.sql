-- =============================================================================
-- Full-truckload dispatch per vehicle (SCRUM-287 PR E / SCRUM-392; kltn-docs 09 §4.2 step 5, §5, BR-14, BR-15;
-- lead's decisions LP-3, LP-11, LP2-1 and the PR E guidance of the #161 review). Module: fulfillment
--
-- A confirmed load plan's vehicle is a dispatch load (V20261022001300): PLANNED -> VEHICLE_BOOKED -> LOADING ->
-- DISPATCHED. Confirming the plan puts on each vehicle's manifest the shipments whose parcels are placed on it; one
-- shipment may span several vehicles of the plan (LP-3) and is handed over at its LAST vehicle's signature. A parcel
-- still sits on one vehicle only (uk_dispatch_load_package_active is kept).
--
--   dispatch_load_shipment  gains mode, the load's delivery mode (backfilled from the load; filled from it on insert by
--                           tg_dispatch_load_shipment_mode, so writers that do not name it keep working).
--                           uk_dispatch_load_shipment_active now holds for PARCEL manifests only: an FTL shipment may be
--                           on several vehicles - of ONE plan (tg_dispatch_load_shipment_check refuses another plan's).
--   dispatch_load           gains the last booking cancellation (the vehicle went back to PLANNED before loading; the
--                           dispatcher books again). booked_at/booked_by came with V20261021000400; a full truckload
--                           booked, loading or gone was booked (ck_dispatch_load_ftl_booked).
--   dispatch_load_unload    parcels to take off a vehicle being loaded because their order was cancelled (09 edge "đơn
--                           bị huỷ ... đã xếp lên xe → dỡ kiện", BR-14 "dỡ kiện về khu DISPATCH"): the vehicle is not
--                           handed over while one is pending (the service answers 409 LOAD_UNLOAD_PENDING first;
--                           tg_dispatch_load_unload_pending is the net). The goods go back through the pick's put-back
--                           queue (08, SCRUM-275) as for any cancelled order.
--
--   ordering.customer_order  gains goods_left_at: goods of the order first left on a load plan's vehicle, before
--                           the shipment is handed over at its last vehicle (LP-3). From then the order is no longer
--                           cancelled, only its remainder closed or returned (review of #162, lead's #150 rule).
--   package                 gains label_outdated_at: the parcel moved to another shipment (left-behind parcels split
--                           off when the plan's first vehicle starts loading) - its label names the old shipment and
--                           n/N, so it is reprinted before it is scanned onto a vehicle (409 LABEL_OUTDATED).
--
-- No permission changes: booking, loading, unloading and the booking cancellation are the dispatcher's loading work,
-- fulfillment-dispatch:UPDATE (seeded for WAREHOUSE_STAFF / WAREHOUSE_MANAGER / SYSTEM_ADMIN, 09 §2 "xác nhận đã đặt
-- xe, giám sát xếp hàng"), so no app_role.version moves.
-- =============================================================================


-- ----------------------------------------------------------------------------- dispatch_load_shipment: mode
ALTER TABLE fulfillment.dispatch_load_shipment ADD COLUMN mode VARCHAR(16);

UPDATE fulfillment.dispatch_load_shipment ls
   SET mode = l.mode
  FROM fulfillment.dispatch_load l
 WHERE l.id = ls.load_id;

ALTER TABLE fulfillment.dispatch_load_shipment
    ALTER COLUMN mode SET NOT NULL,
    ADD CONSTRAINT ck_dispatch_load_shipment_mode CHECK (mode IN ('PARCEL', 'FULL_TRUCKLOAD'));

-- A parcel manifest still holds a shipment alone (09 edge "quét nhầm chuyến"); a full truckload may span the vehicles
-- of its plan (LP-3).
DROP INDEX fulfillment.uk_dispatch_load_shipment_active;
CREATE UNIQUE INDEX uk_dispatch_load_shipment_active ON fulfillment.dispatch_load_shipment (shipment_id)
    WHERE active AND mode = 'PARCEL';
CREATE INDEX ix_dispatch_load_shipment_active ON fulfillment.dispatch_load_shipment (shipment_id) WHERE active;

CREATE FUNCTION fulfillment.fill_load_shipment_mode()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    load_mode VARCHAR(16);
BEGIN
    SELECT mode INTO load_mode FROM fulfillment.dispatch_load WHERE id = NEW.load_id;
    IF NEW.mode IS NULL THEN
        NEW.mode := load_mode;
    ELSIF NEW.mode IS DISTINCT FROM load_mode THEN
        RAISE EXCEPTION 'tg_dispatch_load_shipment_mode: a manifest row is of its load''s mode (%)', load_mode
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_dispatch_load_shipment_mode';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_dispatch_load_shipment_mode BEFORE INSERT OR UPDATE OF mode, load_id
    ON fulfillment.dispatch_load_shipment
    FOR EACH ROW EXECUTE FUNCTION fulfillment.fill_load_shipment_mode();

-- 09 §4.1, BR-01 as before; a full truckload's shipment is on the vehicles of one plan only (LP-3).
CREATE OR REPLACE FUNCTION fulfillment.check_load_shipment()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    s fulfillment.shipment%ROWTYPE;
    l fulfillment.dispatch_load%ROWTYPE;
    plan_id UUID;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO s FROM fulfillment.shipment WHERE id = NEW.shipment_id;
    SELECT * INTO l FROM fulfillment.dispatch_load WHERE id = NEW.load_id;
    IF s.status <> 'READY_TO_DISPATCH' THEN
        RAISE EXCEPTION 'shipment % is %, not waiting for dispatch', s.code, s.status USING ERRCODE = 'check_violation';
    END IF;
    IF l.status NOT IN ('PLANNED', 'VEHICLE_BOOKED', 'LOADING') THEN
        RAISE EXCEPTION 'load % is %', l.code, l.status USING ERRCODE = 'check_violation';
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


-- ----------------------------------------------------------------------------- dispatch_load: booking
ALTER TABLE fulfillment.dispatch_load
    ADD COLUMN booking_cancelled_at  TIMESTAMPTZ,
    ADD COLUMN booking_cancelled_by  UUID,
    ADD COLUMN booking_cancel_reason VARCHAR(500),
    ADD CONSTRAINT fk_dispatch_load_booking_cancelled_by FOREIGN KEY (booking_cancelled_by)
        REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_dispatch_load_booking_cancel CHECK ((booking_cancelled_at IS NULL) = (booking_cancel_reason IS NULL)),
    -- 09 §4.2 step 5: a vehicle of a plan is booked before it is loaded.
    ADD CONSTRAINT ck_dispatch_load_ftl_booked CHECK (mode = 'PARCEL'
        OR status NOT IN ('VEHICLE_BOOKED', 'LOADING', 'DISPATCHED') OR booked_at IS NOT NULL);


-- ----------------------------------------------------------------------------- dispatch_load_unload
CREATE TABLE fulfillment.dispatch_load_unload
(
    id               UUID         NOT NULL,
    load_id          UUID         NOT NULL,
    package_id       UUID         NOT NULL,
    shipment_id      UUID         NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    requested_at     TIMESTAMPTZ  NOT NULL,
    -- Taken off the vehicle by the dispatcher (scanned off); null while pending.
    unloaded_at      TIMESTAMPTZ,
    unloaded_by      UUID,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_dispatch_load_unload PRIMARY KEY (id),
    CONSTRAINT uk_dispatch_load_unload UNIQUE (load_id, package_id),
    CONSTRAINT fk_dispatch_load_unload_load FOREIGN KEY (load_id)
        REFERENCES fulfillment.dispatch_load (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dispatch_load_unload_package FOREIGN KEY (package_id)
        REFERENCES fulfillment.package (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dispatch_load_unload_shipment FOREIGN KEY (shipment_id)
        REFERENCES fulfillment.shipment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dispatch_load_unload_by FOREIGN KEY (unloaded_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_dispatch_load_unload_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_dispatch_load_unload_done CHECK ((unloaded_at IS NULL) = (unloaded_by IS NULL))
);

CREATE INDEX ix_dispatch_load_unload_pending ON fulfillment.dispatch_load_unload (load_id) WHERE unloaded_at IS NULL;

-- A vehicle with parcels of a cancelled order still on board does not leave.
CREATE FUNCTION fulfillment.check_load_unloaded()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status = 'DISPATCHED' AND OLD.status IS DISTINCT FROM 'DISPATCHED'
        AND EXISTS (SELECT 1 FROM fulfillment.dispatch_load_unload u
                     WHERE u.load_id = NEW.id AND u.unloaded_at IS NULL) THEN
        RAISE EXCEPTION 'tg_dispatch_load_unload_pending: load % still carries parcels to unload', NEW.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_dispatch_load_unload_pending';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_dispatch_load_unload_pending BEFORE UPDATE OF status
    ON fulfillment.dispatch_load
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_load_unloaded();


-- ----------------------------------------------------------------------------- goods left, labels to reprint
ALTER TABLE ordering.customer_order ADD COLUMN goods_left_at TIMESTAMPTZ;
ALTER TABLE fulfillment.package ADD COLUMN label_outdated_at TIMESTAMPTZ;
