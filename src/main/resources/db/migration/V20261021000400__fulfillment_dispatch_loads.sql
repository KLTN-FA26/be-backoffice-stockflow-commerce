-- =============================================================================
-- Loading and handover, parcel branch (G4-2a, SCRUM-288/292/293; kltn-docs 09 §3, §4.1, §5, BR-01..BR-05).
-- Module: fulfillment
--
--   carrier                 the declared carriers a shipment is handed to (09 §3 "chọn từ danh sách hãng đã khai báo:
--                           tên, liên hệ"). Seeded with the four the business uses; the warehouse manager edits them.
--   dispatch_load           one handover to one carrier's vehicle: a manifest (09 §4.1 step 3). The parcel branch opens
--                           it LOADING when the driver arrives and closes it DISPATCHED when both sides sign. The FTL
--                           branch (G4-1/G4-2b) creates it PLANNED from a confirmed load plan, books the vehicle
--                           (VEHICLE_BOOKED) and loads it the same way; its columns are here so it plugs in without a
--                           second table: mode, load_plan_vehicle_id (its foreign key comes with the load plan).
--   dispatch_load_shipment  the shipments on the manifest: the list the scans are checked against (§4.1 step 3,
--                           "đối chiếu danh sách"), with the COD each one was handed over with (BR-04).
--   dispatch_load_package   the parcels scanned onto the vehicle; a scan id makes a retried scan harmless.
--   shipment                gains its carrier, the load that handed it over, and the COD its label was last printed
--                           with (09 BR-04: the label, the manifest and the order agree, or the handover is refused).
--                           whole_order_handover: the order knows the shipment only as the whole order (no
--                           ordering.order_shipment row) - shipments handed over before loads existed (migrated by
--                           V20261021000300 from DISPATCHED), and parcels packed before parcels recorded their contents,
--                           handed over as the whole order; their outcomes are the order's own.
--                           A full-truckload shipment never carries COD (lead decision 2026-10-21: FTL orders may not
--                           use COD until G3-4 decides how their balance is collected; 09 §1 assumption).
-- No grant changes: fulfillment-shipments (VIEW_PAGE, READ, CREATE, UPDATE) is seeded for warehouse staff, warehouse
-- managers and order coordinators (V20260903000100), so no app_role.version moves.
-- =============================================================================


-- ----------------------------------------------------------------------------- carrier
CREATE TABLE fulfillment.carrier
(
    id               UUID         NOT NULL,
    code             VARCHAR(32)  NOT NULL,
    name             VARCHAR(120) NOT NULL,
    contact_name     VARCHAR(120),
    contact_phone    VARCHAR(32),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_carrier PRIMARY KEY (id),
    CONSTRAINT uk_carrier_code UNIQUE (code),
    CONSTRAINT ck_carrier_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
    CONSTRAINT ck_carrier_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_carrier_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- Contacts are left for the warehouse manager to fill in: the system does not guess a carrier's numbers.
INSERT INTO fulfillment.carrier (id, code, name, created_at, created_by) VALUES
    (md5('carrier:GHN')::uuid, 'GHN', 'Giao Hàng Nhanh', NOW(), 'flyway'),
    (md5('carrier:GHTK')::uuid, 'GHTK', 'Giao Hàng Tiết Kiệm', NOW(), 'flyway'),
    (md5('carrier:VTP')::uuid, 'VTP', 'Viettel Post', NOW(), 'flyway'),
    (md5('carrier:JTE')::uuid, 'JTE', 'J&T Express', NOW(), 'flyway');

-- Carriers written as free text on shipments before the catalogue (fulfillment.shipment.carrier): the four above by
-- code, name or a common spelling (case-insensitive); any other name gets its own entry so no shipment loses its
-- carrier.
CREATE TEMPORARY TABLE carrier_alias (alias, code) ON COMMIT DROP AS
VALUES ('ghn', 'GHN'), ('giao hàng nhanh', 'GHN'), ('giao hang nhanh', 'GHN'),
       ('ghtk', 'GHTK'), ('giao hàng tiết kiệm', 'GHTK'), ('giao hang tiet kiem', 'GHTK'),
       ('vtp', 'VTP'), ('viettel post', 'VTP'), ('viettelpost', 'VTP'), ('viettel', 'VTP'),
       ('jte', 'JTE'), ('j&t', 'JTE'), ('j&t express', 'JTE'), ('jt express', 'JTE'), ('jnt', 'JTE'),
       ('j & t', 'JTE'), ('j & t express', 'JTE');

INSERT INTO fulfillment.carrier (id, code, name, created_at, created_by)
SELECT md5('carrier:legacy:' || n.name)::uuid, 'L' || upper(substr(md5(n.name), 1, 8)), n.name, NOW(), 'flyway'
  FROM (SELECT DISTINCT ON (lower(btrim(carrier))) btrim(carrier) AS name FROM fulfillment.shipment
         WHERE carrier IS NOT NULL AND btrim(carrier) <> '' ORDER BY lower(btrim(carrier)), btrim(carrier)) n
 WHERE NOT EXISTS (SELECT 1 FROM carrier_alias a WHERE a.alias = lower(n.name))
   AND NOT EXISTS (SELECT 1 FROM fulfillment.carrier c
                    WHERE lower(c.name) = lower(n.name) OR lower(c.code) = lower(n.name));


-- ----------------------------------------------------------------------------- dispatch_load
CREATE TABLE fulfillment.dispatch_load
(
    id                     UUID           NOT NULL,
    -- LD-yyyyMMdd-nnnn from platform.document_sequence ('LD'): the manifest number.
    code                   VARCHAR(30)    NOT NULL,
    request_id             UUID           NOT NULL,
    warehouse_id           UUID           NOT NULL,
    mode                   VARCHAR(16)    NOT NULL DEFAULT 'PARCEL',
    carrier_id             UUID,
    -- FTL only: the vehicle of the confirmed load plan (G4-1 adds the foreign key).
    load_plan_vehicle_id   UUID,
    vehicle_plate          VARCHAR(20),
    driver_name            VARCHAR(120),
    driver_phone           VARCHAR(32),
    status                 VARCHAR(16)    NOT NULL,
    booked_at              TIMESTAMPTZ,
    booked_by              UUID,
    loading_started_at     TIMESTAMPTZ,
    loading_started_by     UUID,
    dispatched_at          TIMESTAMPTZ,
    dispatched_by          UUID,
    -- Who signed for the carrier (the driver) and the reference of the signed paper manifest or its scan.
    carrier_representative VARCHAR(120),
    signature_ref          VARCHAR(255),
    handover_note          VARCHAR(500),
    shipment_count         INTEGER        NOT NULL DEFAULT 0,
    package_count          INTEGER        NOT NULL DEFAULT 0,
    cod_total              NUMERIC(19, 4) NOT NULL DEFAULT 0,
    cancelled_at           TIMESTAMPTZ,
    cancelled_by           UUID,
    cancel_reason          VARCHAR(500),

    version                BIGINT         NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by             VARCHAR(100),
    last_modified_at       TIMESTAMPTZ,
    last_modified_by       VARCHAR(100),

    CONSTRAINT pk_dispatch_load PRIMARY KEY (id),
    CONSTRAINT uk_dispatch_load_code UNIQUE (code),
    CONSTRAINT uk_dispatch_load_request UNIQUE (request_id),
    CONSTRAINT fk_dispatch_load_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT fk_dispatch_load_carrier FOREIGN KEY (carrier_id) REFERENCES fulfillment.carrier (id),
    CONSTRAINT fk_dispatch_load_booked_by FOREIGN KEY (booked_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_dispatch_load_loading_by FOREIGN KEY (loading_started_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_dispatch_load_dispatched_by FOREIGN KEY (dispatched_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_dispatch_load_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_dispatch_load_mode CHECK (mode IN ('PARCEL', 'FULL_TRUCKLOAD')),
    CONSTRAINT ck_dispatch_load_status CHECK (status IN ('PLANNED', 'VEHICLE_BOOKED', 'LOADING', 'DISPATCHED',
        'CANCELLED')),
    -- FTL loads come from a load plan's vehicle; parcel loads never do and start LOADING (09 BR-08).
    CONSTRAINT ck_dispatch_load_ftl_vehicle CHECK ((mode = 'FULL_TRUCKLOAD') = (load_plan_vehicle_id IS NOT NULL)),
    CONSTRAINT ck_dispatch_load_parcel_flow CHECK (mode = 'FULL_TRUCKLOAD'
        OR status IN ('LOADING', 'DISPATCHED', 'CANCELLED')),
    CONSTRAINT ck_dispatch_load_carrier CHECK (status IN ('PLANNED', 'CANCELLED') OR carrier_id IS NOT NULL),
    CONSTRAINT ck_dispatch_load_booked CHECK (status <> 'VEHICLE_BOOKED' OR booked_at IS NOT NULL),
    CONSTRAINT ck_dispatch_load_loading CHECK (status NOT IN ('LOADING', 'DISPATCHED') OR loading_started_at IS NOT NULL),
    -- 09 §4.1 step 3: a dispatched load was signed by both sides, for at least one parcel.
    CONSTRAINT ck_dispatch_load_dispatched CHECK ((status = 'DISPATCHED') = (dispatched_at IS NOT NULL)
        AND (status <> 'DISPATCHED' OR (dispatched_by IS NOT NULL AND carrier_representative IS NOT NULL
            AND vehicle_plate IS NOT NULL AND shipment_count > 0 AND package_count > 0))),
    CONSTRAINT ck_dispatch_load_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)),
    CONSTRAINT ck_dispatch_load_totals CHECK (shipment_count >= 0 AND package_count >= 0 AND cod_total >= 0)
);

CREATE INDEX ix_dispatch_load_warehouse_status ON fulfillment.dispatch_load (warehouse_id, status, created_at);
CREATE INDEX ix_dispatch_load_carrier ON fulfillment.dispatch_load (carrier_id);
-- One live load per vehicle of a load plan (G4-1).
CREATE UNIQUE INDEX uk_dispatch_load_plan_vehicle ON fulfillment.dispatch_load (load_plan_vehicle_id)
    WHERE load_plan_vehicle_id IS NOT NULL AND status <> 'CANCELLED';


-- ----------------------------------------------------------------------------- shipment: carrier and load
ALTER TABLE fulfillment.shipment
    ADD COLUMN carrier_id      UUID,
    ADD COLUMN load_id         UUID,
    ADD COLUMN whole_order_handover BOOLEAN NOT NULL DEFAULT FALSE,
    -- 09 BR-04: what the label of its parcels was last printed with, and when (the label endpoint records a reprint).
    ADD COLUMN label_cod_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN label_printed_at TIMESTAMPTZ;

UPDATE fulfillment.shipment s
   SET carrier_id = COALESCE(
           (SELECT c.id FROM carrier_alias a JOIN fulfillment.carrier c ON c.code = a.code
             WHERE a.alias = lower(btrim(s.carrier))),
           (SELECT c.id FROM fulfillment.carrier c
             WHERE lower(c.name) = lower(btrim(s.carrier)) OR lower(c.code) = lower(btrim(s.carrier))
             ORDER BY c.created_at LIMIT 1))
 WHERE s.carrier IS NOT NULL AND btrim(s.carrier) <> '';

-- The label of a shipment made before this was printed with the COD the shipment holds.
UPDATE fulfillment.shipment SET label_cod_amount = cod_amount;
ALTER TABLE fulfillment.shipment ADD CONSTRAINT ck_shipment_label_cod CHECK (label_cod_amount >= 0);

-- Handed over before loads existed: no manifest names them, and the order knows them only as the whole order.
UPDATE fulfillment.shipment SET whole_order_handover = TRUE
 WHERE status IN ('HANDED_OVER', 'DELIVERED', 'DELIVERY_FAILED', 'RETURNED');

ALTER TABLE fulfillment.shipment
    ADD CONSTRAINT fk_shipment_carrier FOREIGN KEY (carrier_id) REFERENCES fulfillment.carrier (id),
    ADD CONSTRAINT fk_shipment_load FOREIGN KEY (load_id) REFERENCES fulfillment.dispatch_load (id),
    -- 09 §4.1 step 3: a shipment leaves on a signed manifest, which names its carrier.
    ADD CONSTRAINT ck_shipment_handed_over_load CHECK (status NOT IN ('HANDED_OVER', 'DELIVERED', 'DELIVERY_FAILED',
        'RETURNED') OR whole_order_handover OR (load_id IS NOT NULL AND carrier_id IS NOT NULL AND handed_over_by IS NOT NULL)),
    ADD CONSTRAINT ck_shipment_load_handed_over CHECK (load_id IS NULL OR handed_over_at IS NOT NULL),
    -- Lead decision 2026-10-21: a full truckload carries no COD.
    ADD CONSTRAINT ck_shipment_ftl_no_cod CHECK (delivery_mode = 'PARCEL' OR cod_amount = 0);

CREATE INDEX ix_shipment_load ON fulfillment.shipment (load_id);
CREATE INDEX ix_shipment_carrier ON fulfillment.shipment (carrier_id, status);


-- ----------------------------------------------------------------------------- dispatch_load_shipment
CREATE TABLE fulfillment.dispatch_load_shipment
(
    id               UUID           NOT NULL,
    load_id          UUID           NOT NULL,
    shipment_id      UUID           NOT NULL,
    -- False once taken off the manifest before the handover (the row stays: who took it off and when).
    active           BOOLEAN        NOT NULL DEFAULT TRUE,
    added_at         TIMESTAMPTZ    NOT NULL,
    added_by         UUID           NOT NULL,
    removed_at       TIMESTAMPTZ,
    removed_by       UUID,
    -- 09 BR-04 at the handover: what the label was printed with, and what the carrier collects (the order's figure).
    label_cod_amount NUMERIC(19, 4),
    cod_amount       NUMERIC(19, 4),

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_dispatch_load_shipment PRIMARY KEY (id),
    CONSTRAINT uk_dispatch_load_shipment UNIQUE (load_id, shipment_id),
    CONSTRAINT fk_dispatch_load_shipment_load FOREIGN KEY (load_id)
        REFERENCES fulfillment.dispatch_load (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dispatch_load_shipment_shipment FOREIGN KEY (shipment_id)
        REFERENCES fulfillment.shipment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dispatch_load_shipment_added_by FOREIGN KEY (added_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_dispatch_load_shipment_removed_by FOREIGN KEY (removed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_dispatch_load_shipment_removed CHECK (active = (removed_at IS NULL)),
    CONSTRAINT ck_dispatch_load_shipment_cod CHECK ((label_cod_amount IS NULL OR label_cod_amount >= 0)
        AND (cod_amount IS NULL OR cod_amount >= 0))
);

-- A shipment is on one manifest at a time (09 edge "quét nhầm chuyến").
CREATE UNIQUE INDEX uk_dispatch_load_shipment_active ON fulfillment.dispatch_load_shipment (shipment_id) WHERE active;


-- ----------------------------------------------------------------------------- dispatch_load_package
CREATE TABLE fulfillment.dispatch_load_package
(
    id               UUID         NOT NULL,
    load_id          UUID         NOT NULL,
    package_id       UUID         NOT NULL,
    shipment_id      UUID         NOT NULL,
    -- The scanner's id of the last scan that put it on: the same scan again is answered as before.
    scan_id          UUID         NOT NULL,
    loaded_at        TIMESTAMPTZ  NOT NULL,
    loaded_by        UUID         NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    removed_at       TIMESTAMPTZ,
    removed_by       UUID,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_dispatch_load_package PRIMARY KEY (id),
    CONSTRAINT uk_dispatch_load_package UNIQUE (load_id, package_id),
    CONSTRAINT uk_dispatch_load_package_scan UNIQUE (scan_id),
    CONSTRAINT fk_dispatch_load_package_load FOREIGN KEY (load_id)
        REFERENCES fulfillment.dispatch_load (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dispatch_load_package_package FOREIGN KEY (package_id)
        REFERENCES fulfillment.package (id) ON DELETE RESTRICT,
    -- A parcel is loaded only with its shipment on the manifest.
    CONSTRAINT fk_dispatch_load_package_shipment FOREIGN KEY (load_id, shipment_id)
        REFERENCES fulfillment.dispatch_load_shipment (load_id, shipment_id) ON DELETE RESTRICT,
    CONSTRAINT fk_dispatch_load_package_loaded_by FOREIGN KEY (loaded_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_dispatch_load_package_removed_by FOREIGN KEY (removed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_dispatch_load_package_removed CHECK (active = (removed_at IS NULL))
);

-- A parcel is on one vehicle at a time.
CREATE UNIQUE INDEX uk_dispatch_load_package_active ON fulfillment.dispatch_load_package (package_id) WHERE active;
CREATE INDEX ix_dispatch_load_package_shipment ON fulfillment.dispatch_load_package (shipment_id);


-- ----------------------------------------------------------------------------- the rules, a second time
-- 09 §4.1, BR-01: only a shipment waiting for dispatch goes on a manifest - of the load's warehouse, its delivery mode,
-- and its carrier (or none chosen yet) - and only while the load is open.
CREATE FUNCTION fulfillment.check_load_shipment()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    s fulfillment.shipment%ROWTYPE;
    l fulfillment.dispatch_load%ROWTYPE;
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
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_dispatch_load_shipment_check BEFORE INSERT OR UPDATE OF active
    ON fulfillment.dispatch_load_shipment
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_load_shipment();

-- 09 BR-01: a parcel loaded is sealed (weighed, measured, labelled) and belongs to the shipment it is loaded with.
CREATE FUNCTION fulfillment.check_load_package()
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
    IF p.status <> 'SEALED' OR p.shipment_id IS DISTINCT FROM NEW.shipment_id THEN
        RAISE EXCEPTION 'package % is not a sealed parcel of shipment %', p.label_code, NEW.shipment_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.dispatch_load_shipment ls
                    JOIN fulfillment.dispatch_load l ON l.id = ls.load_id
                   WHERE ls.load_id = NEW.load_id AND ls.shipment_id = NEW.shipment_id AND ls.active
                     AND l.status IN ('PLANNED', 'VEHICLE_BOOKED', 'LOADING')) THEN
        RAISE EXCEPTION 'package % is not on an open manifest with its shipment', p.label_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_dispatch_load_package_check BEFORE INSERT OR UPDATE OF active
    ON fulfillment.dispatch_load_package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_load_package();
