-- =============================================================================
-- Fulfillment: pick lines, packages and their contents, shipment tracking events (docs 07-09).
-- Module: fulfillment   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase G
--
-- The live fulfillment tables are headers only: a pick knows its order, not which stock it takes
-- from where; a pack knows its pick, not what went into which box; a shipment has one status and
-- no history. The flows need all three:
--   pick_line       docs 07 step 2/6: allocation "at a specific bin and lot", scanned per stop,
--                   short picks recorded per line.
--   package         docs 08 step 7/9: one order can ship as several parcels, each with its own
--                   weight, size and tracking number. `pack` stays the packing TASK.
--   package_line    what is inside each parcel (BR-01: verified 100% before PACKED).
--   shipment_event  docs 09 step 6: carrier webhooks/polls, the tracking timeline. Append-only.
-- The live tables are not altered.
-- =============================================================================


CREATE TABLE fulfillment.pick_line
(
    id               UUID         NOT NULL,
    pick_id          UUID         NOT NULL,
    order_line_id    UUID         NOT NULL,
    -- The hold it consumes; NULL only for a re-allocation after the original hold was released.
    reservation_id   UUID,
    sku              VARCHAR(64)  NOT NULL,
    location_code    VARCHAR(64)  NOT NULL,
    lot_number       VARCHAR(64),
    sequence_no      INTEGER      NOT NULL DEFAULT 0,
    allocated_qty    INTEGER      NOT NULL,
    picked_qty       INTEGER      NOT NULL DEFAULT 0,
    short_qty        INTEGER      NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    picked_by        UUID,
    picked_at        TIMESTAMPTZ,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_pick_line PRIMARY KEY (id),
    CONSTRAINT fk_pick_line_pick FOREIGN KEY (pick_id) REFERENCES fulfillment.pick (id) ON DELETE CASCADE,
    CONSTRAINT fk_pick_line_order_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_line_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_line_item FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_line_location FOREIGN KEY (location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_line_picked_by FOREIGN KEY (picked_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_pick_line_qty CHECK (allocated_qty > 0 AND picked_qty >= 0 AND short_qty >= 0),
    -- BR-04: never pick more than allocated; picked + short accounts for at most the allocation.
    CONSTRAINT ck_pick_line_within_allocation CHECK (picked_qty + short_qty <= allocated_qty),
    CONSTRAINT ck_pick_line_status CHECK (status IN ('OPEN', 'PICKED', 'SHORT', 'CANCELLED')),
    CONSTRAINT ck_pick_line_picked CHECK (status <> 'PICKED'
        OR (picked_qty = allocated_qty AND picked_by IS NOT NULL AND picked_at IS NOT NULL)),
    CONSTRAINT ck_pick_line_short CHECK (status <> 'SHORT' OR short_qty > 0)
);

CREATE INDEX ix_pick_line_pick ON fulfillment.pick_line (pick_id, sequence_no);
CREATE INDEX ix_pick_line_order_line ON fulfillment.pick_line (order_line_id);
CREATE INDEX ix_pick_line_reservation ON fulfillment.pick_line (reservation_id);
CREATE INDEX ix_pick_line_location ON fulfillment.pick_line (location_code);


CREATE TABLE fulfillment.package
(
    id               UUID           NOT NULL,
    pack_id          UUID           NOT NULL,
    shipment_id      UUID,
    package_no       INTEGER        NOT NULL,
    weight_kg        NUMERIC(10, 3),
    length_cm        NUMERIC(10, 2),
    width_cm         NUMERIC(10, 2),
    height_cm        NUMERIC(10, 2),
    -- Dimensional weight as the carrier computes it (volume / divisor); what the carrier bills.
    dim_weight_kg    NUMERIC(10, 3),
    tracking_number  VARCHAR(128),
    label_url        VARCHAR(500),
    status           VARCHAR(16)    NOT NULL DEFAULT 'OPEN',
    sealed_at        TIMESTAMPTZ,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_package PRIMARY KEY (id),
    CONSTRAINT uk_package_no UNIQUE (pack_id, package_no),
    CONSTRAINT fk_package_pack FOREIGN KEY (pack_id) REFERENCES fulfillment.pack (id) ON DELETE RESTRICT,
    CONSTRAINT fk_package_shipment FOREIGN KEY (shipment_id)
        REFERENCES fulfillment.shipment (id) ON DELETE RESTRICT,
    CONSTRAINT ck_package_no CHECK (package_no > 0),
    CONSTRAINT ck_package_measures CHECK ((weight_kg IS NULL OR weight_kg > 0)
        AND (length_cm IS NULL OR length_cm > 0) AND (width_cm IS NULL OR width_cm > 0)
        AND (height_cm IS NULL OR height_cm > 0) AND (dim_weight_kg IS NULL OR dim_weight_kg > 0)),
    CONSTRAINT ck_package_status CHECK (status IN ('OPEN', 'SEALED', 'VOIDED')),
    -- A sealed parcel has been weighed and measured (docs 08 step 5).
    CONSTRAINT ck_package_sealed CHECK (status <> 'SEALED' OR (sealed_at IS NOT NULL
        AND weight_kg IS NOT NULL AND length_cm IS NOT NULL AND width_cm IS NOT NULL
        AND height_cm IS NOT NULL))
);

CREATE INDEX ix_package_shipment ON fulfillment.package (shipment_id);
CREATE UNIQUE INDEX uk_package_tracking ON fulfillment.package (tracking_number)
    WHERE tracking_number IS NOT NULL AND status <> 'VOIDED';


CREATE TABLE fulfillment.package_line
(
    id               UUID         NOT NULL,
    package_id       UUID         NOT NULL,
    order_line_id    UUID         NOT NULL,
    quantity         INTEGER      NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_package_line PRIMARY KEY (id),
    CONSTRAINT uk_package_line_pair UNIQUE (package_id, order_line_id),
    CONSTRAINT fk_package_line_package FOREIGN KEY (package_id)
        REFERENCES fulfillment.package (id) ON DELETE CASCADE,
    CONSTRAINT fk_package_line_order_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE RESTRICT,
    CONSTRAINT ck_package_line_qty CHECK (quantity > 0)
);

CREATE INDEX ix_package_line_order_line ON fulfillment.package_line (order_line_id);


CREATE TABLE fulfillment.shipment_event
(
    id               UUID         NOT NULL,
    shipment_id      UUID         NOT NULL,
    status           VARCHAR(24)  NOT NULL,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    source           VARCHAR(16)  NOT NULL,
    -- The carrier's own event id, so a webhook delivered twice is stored once.
    carrier_event_id VARCHAR(128),
    location_text    VARCHAR(255),
    note             VARCHAR(1000),
    raw_payload      JSONB,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_shipment_event PRIMARY KEY (id),
    CONSTRAINT fk_shipment_event_shipment FOREIGN KEY (shipment_id)
        REFERENCES fulfillment.shipment (id) ON DELETE CASCADE,
    CONSTRAINT ck_shipment_event_status CHECK (status IN ('LABEL_CREATED', 'READY_TO_DISPATCH',
        'HANDED_OVER', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERY_FAILED', 'RETURNING', 'DELIVERED',
        'RETURNED', 'EXCEPTION', 'CANCELLED')),
    CONSTRAINT ck_shipment_event_source CHECK (source IN ('CARRIER_WEBHOOK', 'CARRIER_POLL', 'MANUAL')),
    CONSTRAINT ck_shipment_event_payload CHECK (raw_payload IS NULL OR jsonb_typeof(raw_payload) = 'object')
);

CREATE INDEX ix_shipment_event_timeline ON fulfillment.shipment_event (shipment_id, occurred_at);
CREATE UNIQUE INDEX uk_shipment_event_carrier ON fulfillment.shipment_event (shipment_id, carrier_event_id)
    WHERE carrier_event_id IS NOT NULL;

CREATE TRIGGER tg_shipment_event_append_only BEFORE UPDATE OR DELETE ON fulfillment.shipment_event
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('fulfillment.shipment', 'shipment_id');


-- -----------------------------------------------------------------------------
-- Cross-row rules: a pick line picks a line of the pick's own order, of that line's SKU; a
-- package holds lines of the packed order only (BR-04: one parcel, one order).
-- -----------------------------------------------------------------------------
CREATE FUNCTION fulfillment.check_pick_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM fulfillment.pick p
          JOIN ordering.order_line l ON l.order_id = p.order_id
         WHERE p.id = NEW.pick_id AND l.id = NEW.order_line_id AND l.sku = NEW.sku) THEN
        RAISE EXCEPTION 'pick line must pick a line of the pick''s own order, with that line''s sku'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_pick_line_check BEFORE INSERT OR UPDATE OF pick_id, order_line_id, sku
    ON fulfillment.pick_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_pick_line();


CREATE FUNCTION fulfillment.check_package_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
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
$$;

CREATE TRIGGER tg_package_line_check BEFORE INSERT OR UPDATE ON fulfillment.package_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_line();
