-- =============================================================================
-- Packing station (G4-5, SCRUM-278/281/282; kltn-docs 08 §4-6, 09 §3 and §5).
-- Module: fulfillment
--
--   pack           the packing session of a task: who started it and who completed it (08 §5 Packer).
--   package        a parcel: its label code (08 step 6-7), package type, the rotatable flag defaulted from
--                  its SKUs (01 rotatable, E13) and the fragile flag (01 storage class FRAGILE), who sealed it,
--                  when it was voided (re-opened work never disappears).
--   package_scan   append-only: every scan that put goods into a parcel or took them out, with the lot or
--                  serial scanned (08 step 2). Its id is the scanner's scanId, so a retried scan is stored once.
--   package_line   stays the running total per parcel and order line; a trigger refuses packing more of a line
--                  than the order still wants (08 BR-01, stated in the database as well).
--   shipment       the 09 §5 lifecycle (READY_TO_DISPATCH ... CANCELLED) replaces PENDING/DISPATCHED; one
--                  order may have several shipments (split shipments, G4-Q3), each with its own number, packages,
--                  warehouse, delivery mode, COD amount (09 BR-04) and the handover / outcome facts G4-2a fills.
--   shipment_event gains who recorded a manual step (09 BR-02).
-- =============================================================================


-- ----------------------------------------------------------------------------- pack
ALTER TABLE fulfillment.pack
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN started_by UUID,
    ADD COLUMN packed_by  UUID,
    -- The request id of a completion a design check put on hold: the same request again gets the same answer.
    ADD COLUMN held_request_id UUID,
    ADD CONSTRAINT fk_pack_started_by FOREIGN KEY (started_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_pack_packed_by FOREIGN KEY (packed_by) REFERENCES identity.app_user (id);

-- Packs completed before the session existed were completed by the task's assignee, the only one allowed to.
UPDATE fulfillment.pack k
   SET started_at = COALESCE(p.picked_at, k.created_at),
       started_by = p.assigned_user_id,
       packed_by  = CASE WHEN k.status = 'PACKED' THEN p.assigned_user_id END
  FROM fulfillment.pick p
 WHERE p.id = k.pick_id AND k.status IN ('PACKING', 'ON_HOLD', 'PACKED');

ALTER TABLE fulfillment.pack
    ADD CONSTRAINT ck_pack_started CHECK (status NOT IN ('PACKING', 'ON_HOLD', 'PACKED') OR started_at IS NOT NULL),
    ADD CONSTRAINT ck_pack_packed CHECK (status <> 'PACKED' OR packed_at IS NOT NULL);


-- ----------------------------------------------------------------------------- shipment
ALTER TABLE fulfillment.shipment DROP CONSTRAINT ck_shipment_status;
UPDATE fulfillment.shipment SET status = 'READY_TO_DISPATCH' WHERE status = 'PENDING';
UPDATE fulfillment.shipment SET status = 'HANDED_OVER' WHERE status = 'DISPATCHED';

ALTER TABLE fulfillment.shipment
    ADD COLUMN code           VARCHAR(48),
    ADD COLUMN request_id     UUID,
    ADD COLUMN warehouse_id   UUID,
    ADD COLUMN delivery_mode  VARCHAR(16)    NOT NULL DEFAULT 'PARCEL',
    ADD COLUMN package_count  INTEGER        NOT NULL DEFAULT 1,
    ADD COLUMN cod_amount     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN partial        BOOLEAN        NOT NULL DEFAULT FALSE,
    ADD COLUMN partial_note   VARCHAR(500),
    ADD COLUMN ready_at       TIMESTAMPTZ,
    ADD COLUMN ready_by       UUID,
    ADD COLUMN handed_over_at TIMESTAMPTZ,
    ADD COLUMN handed_over_by UUID,
    ADD COLUMN outcome_at     TIMESTAMPTZ,
    ADD COLUMN outcome_by     UUID,
    ADD COLUMN failure_reason VARCHAR(24),
    ADD COLUMN failure_note   VARCHAR(500),
    ADD COLUMN cancelled_at   TIMESTAMPTZ,
    ADD COLUMN cancel_reason  VARCHAR(500);

-- Rows written before: the warehouse their pick was made in (else the order's), a number per order in the order
-- they were made, their parcels counted, and the times their timeline already has.
UPDATE fulfillment.shipment s
   SET warehouse_id = COALESCE(
           (SELECT p.warehouse_id FROM fulfillment.pack k JOIN fulfillment.pick p ON p.id = k.pick_id
             WHERE k.id = s.pack_id),
           (SELECT o.warehouse_id FROM ordering.customer_order o WHERE o.id = s.order_id)),
       ready_at = s.created_at,
       package_count = GREATEST(1, (SELECT COUNT(*) FROM fulfillment.package pk
                                     WHERE pk.shipment_id = s.id AND pk.status <> 'VOIDED')),
       handed_over_at = CASE WHEN s.status IN ('HANDED_OVER', 'DELIVERED', 'RETURNED')
                             THEN COALESCE(s.dispatched_at, s.created_at) END,
       outcome_at = CASE WHEN s.status IN ('DELIVERED', 'RETURNED')
                         THEN COALESCE((SELECT MAX(e.occurred_at) FROM fulfillment.shipment_event e
                                         WHERE e.shipment_id = s.id AND e.status = s.status),
                                       s.last_modified_at, s.dispatched_at, s.created_at) END;
UPDATE fulfillment.shipment s
   SET code = o.order_number || '-S' || n.seq
  FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY created_at, id) AS seq
          FROM fulfillment.shipment) n,
       ordering.customer_order o
 WHERE n.id = s.id AND o.id = s.order_id;

ALTER TABLE fulfillment.shipment
    ALTER COLUMN code SET NOT NULL,
    ALTER COLUMN warehouse_id SET NOT NULL,
    ALTER COLUMN ready_at SET NOT NULL,
    ADD CONSTRAINT uk_shipment_code UNIQUE (code),
    ADD CONSTRAINT uk_shipment_request UNIQUE (request_id),
    ADD CONSTRAINT fk_shipment_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    ADD CONSTRAINT fk_shipment_ready_by FOREIGN KEY (ready_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_shipment_handed_over_by FOREIGN KEY (handed_over_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_shipment_outcome_by FOREIGN KEY (outcome_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_shipment_status CHECK (status IN ('READY_TO_DISPATCH', 'HANDED_OVER', 'DELIVERED',
        'DELIVERY_FAILED', 'RETURNED', 'CANCELLED')),
    ADD CONSTRAINT ck_shipment_delivery_mode CHECK (delivery_mode IN ('PARCEL', 'FULL_TRUCKLOAD')),
    ADD CONSTRAINT ck_shipment_amounts CHECK (package_count > 0 AND cod_amount >= 0),
    -- 09 §5: a shipment that left was handed over; one with an outcome has it recorded (BR-02: who, in the domain -
    -- rows from before the lifecycle have no recorder).
    ADD CONSTRAINT ck_shipment_handed_over CHECK (status NOT IN ('HANDED_OVER', 'DELIVERED', 'DELIVERY_FAILED',
        'RETURNED') OR handed_over_at IS NOT NULL),
    ADD CONSTRAINT ck_shipment_outcome CHECK (status NOT IN ('DELIVERED', 'DELIVERY_FAILED', 'RETURNED')
        OR outcome_at IS NOT NULL),
    -- The reason stays after a retry delivered it, so the history keeps why it failed (as ordering.order_shipment).
    ADD CONSTRAINT ck_shipment_failure CHECK ((status <> 'DELIVERY_FAILED' OR failure_reason IS NOT NULL)
        AND (failure_reason IS NULL OR failure_reason IN
            ('CUSTOMER_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'LOST', 'DAMAGED', 'OTHER'))),
    ADD CONSTRAINT ck_shipment_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    -- A shipment of part of the order says why (split shipments, G4-Q3; 08 edge "partial ship").
    ADD CONSTRAINT ck_shipment_partial CHECK (NOT partial OR partial_note IS NOT NULL);

CREATE INDEX ix_shipment_pack ON fulfillment.shipment (pack_id);
CREATE INDEX ix_shipment_warehouse_status ON fulfillment.shipment (warehouse_id, status);

ALTER TABLE fulfillment.shipment_event
    ADD COLUMN recorded_by UUID,
    ADD CONSTRAINT fk_shipment_event_recorded_by FOREIGN KEY (recorded_by) REFERENCES identity.app_user (id);


-- ----------------------------------------------------------------------------- package
ALTER TABLE fulfillment.package
    ADD COLUMN label_code           VARCHAR(48),
    ADD COLUMN package_type         VARCHAR(16) NOT NULL DEFAULT 'CARTON',
    ADD COLUMN rotatable            BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN rotatable_overridden BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN fragile              BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN fragile_overridden   BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN sealed_by            UUID,
    ADD COLUMN voided_at            TIMESTAMPTZ,
    ADD COLUMN voided_by            UUID;

-- The label code is the parcel's number on its label, what loading scans (SCRUM-445): PKG-<order>-<n>.
UPDATE fulfillment.package pk
   SET label_code = 'PKG-' || p.order_number || '-' || pk.package_no,
       voided_at = CASE WHEN pk.status = 'VOIDED' THEN COALESCE(pk.last_modified_at, pk.created_at) END
  FROM fulfillment.pack k
  JOIN fulfillment.pick p ON p.id = k.pick_id
 WHERE k.id = pk.pack_id;

ALTER TABLE fulfillment.package
    ALTER COLUMN label_code SET NOT NULL,
    ADD CONSTRAINT uk_package_label_code UNIQUE (label_code),
    ADD CONSTRAINT fk_package_sealed_by FOREIGN KEY (sealed_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_package_voided_by FOREIGN KEY (voided_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_package_type CHECK (package_type IN ('CARTON', 'BAG', 'PALLET', 'CRATE', 'OTHER')),
    ADD CONSTRAINT ck_package_voided CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL)),
    -- Only a sealed parcel ships (09 BR-01: weighed, measured, labelled); voided ones keep their history.
    ADD CONSTRAINT ck_package_shipped_sealed CHECK (shipment_id IS NULL OR status IN ('SEALED', 'VOIDED'));

CREATE INDEX ix_package_pack ON fulfillment.package (pack_id, status);


-- Packs completed before shipments existed (the old completion, the test-data seed): PACKED, their sealed parcels in
-- no shipment. Each gets the shipment that completion would make now - READY_TO_DISPATCH, <order>-S<n> - so the
-- handover (G4-2a) has something to hand over. Its COD amount is what #135 would fix for the whole order at the
-- handover: the balance not yet paid of an ON_DELIVERY deposit order (09 BR-04), 0 otherwise. A pack completed with
-- no parcel at all keeps none: there is nothing measured to hand over, and its order ships through the whole-order
-- handover (OrderService.markShipped, G4-Q5).
CREATE TEMPORARY TABLE packed_without_shipment ON COMMIT DROP AS
SELECT k.id AS pack_id, p.order_id, p.warehouse_id, COALESCE(k.packed_at, k.created_at) AS ready_at,
       k.packed_by AS ready_by, gen_random_uuid() AS shipment_id,
       (SELECT COUNT(*) FROM fulfillment.package pk
         WHERE pk.pack_id = k.id AND pk.status = 'SEALED' AND pk.shipment_id IS NULL) AS parcels
  FROM fulfillment.pack k
  JOIN fulfillment.pick p ON p.id = k.pick_id
 WHERE k.status = 'PACKED'
   AND NOT EXISTS (SELECT 1 FROM fulfillment.shipment s
                    WHERE s.pack_id = k.id AND s.status NOT IN ('CANCELLED', 'RETURNED'))
   AND EXISTS (SELECT 1 FROM fulfillment.package pk
                WHERE pk.pack_id = k.id AND pk.status = 'SEALED' AND pk.shipment_id IS NULL);

INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, delivery_mode, package_count,
                                  cod_amount, ready_at, ready_by, created_at, created_by)
SELECT w.shipment_id, w.pack_id, w.order_id, 'READY_TO_DISPATCH',
       o.order_number || '-S' || (1 + (SELECT COUNT(*) FROM fulfillment.shipment s WHERE s.order_id = w.order_id)),
       w.warehouse_id, 'PARCEL', w.parcels,
       CASE WHEN o.payment_term = 'DEPOSIT' AND o.balance_policy = 'ON_DELIVERY'
            THEN GREATEST(o.total_amount - o.paid_amount
                          - COALESCE((SELECT SUM(l.cancelled_quantity * l.unit_price) FROM ordering.order_line l
                                       WHERE l.order_id = o.id), 0), 0)
            ELSE 0 END,
       w.ready_at, w.ready_by, w.ready_at, 'flyway'
  FROM packed_without_shipment w
  JOIN ordering.customer_order o ON o.id = w.order_id;

UPDATE fulfillment.package pk SET shipment_id = w.shipment_id
  FROM packed_without_shipment w
 WHERE pk.pack_id = w.pack_id AND pk.status = 'SEALED' AND pk.shipment_id IS NULL;

INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source, note, recorded_by, created_at,
                                        created_by)
SELECT gen_random_uuid(), w.shipment_id, 'READY_TO_DISPATCH', w.ready_at, 'MANUAL',
       'Packed before shipments were recorded (V20261021000300)', w.ready_by, NOW(), 'flyway'
  FROM packed_without_shipment w;


-- ----------------------------------------------------------------------------- package_scan
CREATE TABLE fulfillment.package_scan
(
    id               UUID         NOT NULL,
    package_id       UUID         NOT NULL,
    order_line_id    UUID         NOT NULL,
    scanned_code     VARCHAR(64)  NOT NULL,
    lot_number       VARCHAR(64),
    serial_number    VARCHAR(100),
    -- Positive: put into the parcel; negative: taken out again before it was sealed.
    quantity         INTEGER      NOT NULL,
    scanned_by       UUID         NOT NULL,
    scanned_at       TIMESTAMPTZ  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_package_scan PRIMARY KEY (id),
    CONSTRAINT fk_package_scan_package FOREIGN KEY (package_id)
        REFERENCES fulfillment.package (id) ON DELETE RESTRICT,
    CONSTRAINT fk_package_scan_order_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT,
    CONSTRAINT fk_package_scan_scanned_by FOREIGN KEY (scanned_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_package_scan_quantity CHECK (quantity <> 0),
    -- One serial is one unit.
    CONSTRAINT ck_package_scan_serial CHECK (serial_number IS NULL OR quantity IN (1, -1))
);

CREATE INDEX ix_package_scan_package ON fulfillment.package_scan (package_id, scanned_at);
CREATE INDEX ix_package_scan_order_line ON fulfillment.package_scan (order_line_id);

CREATE TRIGGER tg_package_scan_append_only BEFORE UPDATE OR DELETE ON fulfillment.package_scan
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('fulfillment.package', 'package_id');

-- BR-04 (one parcel, one order): the same rule as package_line.
CREATE TRIGGER tg_package_scan_check BEFORE INSERT ON fulfillment.package_scan
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_line();


-- ----------------------------------------------------------------------------- package_line
-- 08 BR-01, stated twice: the parcels not voided (nor come back) never hold more of an order line than the order
-- still wants.
CREATE FUNCTION fulfillment.check_package_line_quantity()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
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
        RAISE EXCEPTION 'order line % would be packed % times; the order wants %', NEW.order_line_id, packed, allowed
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_package_line_quantity AFTER INSERT OR UPDATE OF quantity, order_line_id
    ON fulfillment.package_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_line_quantity();
