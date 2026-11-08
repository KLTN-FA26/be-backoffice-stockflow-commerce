-- =============================================================================
-- Booking a vehicle through a carrier's API (kltn-docs 09 §4.2 step 6, BR-29, E24, E26; team plan 3-4; Ahamove API
-- v3).
--
--   * fulfillment.carrier_pickup_point - where an API_BOOKING carrier picks up at one warehouse: the address written
--     the way the carrier reads it ("[số nhà] [đường], [phường], [quận], [thành phố], Việt Nam"), who hands the goods
--     over, and the province it is in. The province picks the carrier's service code for the load's vehicle type
--     (carrier_service_code) and every drop-off must be in it (E24: in-town only). One per carrier and warehouse.
--   * fulfillment.carrier_booking - one order created at the carrier for a load (BR-29: one vehicle, one order;
--     the pickup is the warehouse, each shipment a drop-off): the stops in the order the dispatcher confirmed, the
--     pickup time asked for, the service code, and what came of it.
--       REQUESTED - claimed, the call under way (one per load at a time: uk_carrier_booking_live);
--       BOOKED    - the carrier's order id, which is the load's waybill number, and its tracking link;
--       FAILED    - the carrier refused, so nothing was created there; the load stays PLANNED (also a claim
--                   released by hand once found not to have created an order);
--       UNCONFIRMED - the call did not come back (no answer, a 5xx, a crash): the carrier may have created the
--                   order. It holds the load until the dispatcher, having checked the carrier's portal for an
--                   order bearing the load's code, cancels the booking, which releases it;
--       CANCELLED - cancelled at the carrier (the booking was cancelled, or the load called off);
--       CANCEL_FAILED - the load was called off and the carrier refused to cancel (the driver already picked up):
--                   the dispatcher settles it with the carrier directly (BR-29).
--   * fulfillment.carrier_api_call - every call to a carrier's API (create, cancel, read an order, list services,
--     token), its
--     outcome and how long it took (BR-29 "mọi lần gọi API hãng lưu nhật ký"). No credential is ever stored: a token
--     call keeps no body. Append-only.
--
--   * fulfillment.waybill_change: a change from source API may name nobody - the system dropping the carrier's
--     order when the load it was made for changed (a shipment taken off).
--
-- No permission changes: booking stays fulfillment-dispatch:UPDATE, configuring a carrier fulfillment-carriers:UPDATE.
-- =============================================================================

-- 1. Pickup points -----------------------------------------------------------------------------------
CREATE TABLE fulfillment.carrier_pickup_point
(
    id               UUID         NOT NULL,
    carrier_id       UUID         NOT NULL,
    warehouse_id     UUID         NOT NULL,
    province_code    VARCHAR(20)  NOT NULL,
    address          VARCHAR(500) NOT NULL,
    contact_name     VARCHAR(120),
    contact_phone    VARCHAR(32),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_carrier_pickup_point PRIMARY KEY (id),
    CONSTRAINT fk_carrier_pickup_point_carrier FOREIGN KEY (carrier_id) REFERENCES fulfillment.carrier (id),
    CONSTRAINT fk_carrier_pickup_point_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT uk_carrier_pickup_point UNIQUE (carrier_id, warehouse_id),
    CONSTRAINT ck_carrier_pickup_point_province CHECK (province_code ~ '^[!-~]{1,20}$'),
    CONSTRAINT ck_carrier_pickup_point_address CHECK (btrim(address) <> '')
);

CREATE INDEX ix_carrier_pickup_point_warehouse ON fulfillment.carrier_pickup_point (warehouse_id);

-- 2. Orders at the carrier ---------------------------------------------------------------------------
CREATE TABLE fulfillment.carrier_booking
(
    id               UUID          NOT NULL,
    load_id          UUID          NOT NULL,
    carrier_id       UUID          NOT NULL,
    service_code     VARCHAR(64)   NOT NULL,
    stops            JSONB         NOT NULL,
    order_time       TIMESTAMPTZ,
    status           VARCHAR(16)   NOT NULL,
    carrier_order_id VARCHAR(64),
    tracking_link    VARCHAR(500),
    requested_by     UUID          NOT NULL,
    requested_at     TIMESTAMPTZ   NOT NULL,
    booked_at        TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    cancel_reason    VARCHAR(500),
    cancel_attempts  INTEGER       NOT NULL DEFAULT 0,
    last_error       VARCHAR(1000),

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_carrier_booking PRIMARY KEY (id),
    CONSTRAINT fk_carrier_booking_load FOREIGN KEY (load_id) REFERENCES fulfillment.dispatch_load (id),
    CONSTRAINT fk_carrier_booking_carrier FOREIGN KEY (carrier_id) REFERENCES fulfillment.carrier (id),
    CONSTRAINT fk_carrier_booking_requested_by FOREIGN KEY (requested_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_carrier_booking_status CHECK (status IN ('REQUESTED', 'BOOKED', 'FAILED', 'UNCONFIRMED',
        'CANCELLED', 'CANCEL_FAILED')),
    CONSTRAINT ck_carrier_booking_stops CHECK (jsonb_typeof(stops) = 'array' AND jsonb_array_length(stops) >= 1),
    -- A booked order has the carrier's id and when; a failed or unconfirmed call says why; a cancelled one when.
    CONSTRAINT ck_carrier_booking_booked CHECK (status IN ('REQUESTED', 'FAILED', 'UNCONFIRMED')
        OR (carrier_order_id IS NOT NULL AND booked_at IS NOT NULL)),
    CONSTRAINT ck_carrier_booking_failed CHECK (status NOT IN ('FAILED', 'UNCONFIRMED', 'CANCEL_FAILED')
        OR last_error IS NOT NULL),
    CONSTRAINT ck_carrier_booking_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_carrier_booking_attempts CHECK (cancel_attempts >= 0)
);

-- One order in flight, held or possibly held per load: two dispatchers booking at once create one order, not two,
-- and a call that did not come back is not followed by a second order until someone checked.
CREATE UNIQUE INDEX uk_carrier_booking_live ON fulfillment.carrier_booking (load_id)
    WHERE status IN ('REQUESTED', 'BOOKED', 'UNCONFIRMED');
CREATE UNIQUE INDEX uk_carrier_booking_order ON fulfillment.carrier_booking (carrier_id, carrier_order_id)
    WHERE carrier_order_id IS NOT NULL;
CREATE INDEX ix_carrier_booking_load ON fulfillment.carrier_booking (load_id, requested_at DESC);
CREATE INDEX ix_carrier_booking_open ON fulfillment.carrier_booking (status)
    WHERE status IN ('REQUESTED', 'BOOKED', 'CANCEL_FAILED');
CREATE INDEX ix_carrier_booking_requested_by ON fulfillment.carrier_booking (requested_by);

-- 3. The call log -----------------------------------------------------------------------------------
CREATE TABLE fulfillment.carrier_api_call
(
    id               UUID          NOT NULL,
    carrier_id       UUID          NOT NULL,
    booking_id       UUID,
    operation        VARCHAR(16)   NOT NULL,
    outcome          VARCHAR(16)   NOT NULL,
    http_status      INTEGER,
    request_body     TEXT,
    response_body    TEXT,
    error            VARCHAR(1000),
    duration_ms      INTEGER       NOT NULL,
    called_at        TIMESTAMPTZ   NOT NULL,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_carrier_api_call PRIMARY KEY (id),
    CONSTRAINT fk_carrier_api_call_carrier FOREIGN KEY (carrier_id) REFERENCES fulfillment.carrier (id),
    CONSTRAINT fk_carrier_api_call_booking FOREIGN KEY (booking_id) REFERENCES fulfillment.carrier_booking (id),
    CONSTRAINT ck_carrier_api_call_operation CHECK (operation IN ('TOKEN', 'SERVICES', 'BOOK', 'CANCEL', 'STATUS')),
    CONSTRAINT ck_carrier_api_call_outcome CHECK (outcome IN ('OK', 'REFUSED', 'NO_ANSWER')),
    -- A token call carries the API key and answers with the token: neither body is ever kept.
    CONSTRAINT ck_carrier_api_call_token_body CHECK (operation <> 'TOKEN'
        OR (request_body IS NULL AND response_body IS NULL)),
    CONSTRAINT ck_carrier_api_call_answered CHECK ((outcome = 'NO_ANSWER') = (http_status IS NULL)),
    CONSTRAINT ck_carrier_api_call_duration CHECK (duration_ms >= 0)
);

CREATE INDEX ix_carrier_api_call_booking ON fulfillment.carrier_api_call (booking_id, called_at)
    WHERE booking_id IS NOT NULL;
CREATE INDEX ix_carrier_api_call_carrier ON fulfillment.carrier_api_call (carrier_id, called_at DESC);

CREATE TRIGGER trg_carrier_api_call_append_only
    BEFORE UPDATE OR DELETE ON fulfillment.carrier_api_call
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- 4. Waybill history ---------------------------------------------------------------------------------
-- The carrier's order dropped by the system when its load changed names nobody, as a webhook's change does.
ALTER TABLE fulfillment.waybill_change DROP CONSTRAINT ck_waybill_change_who;
ALTER TABLE fulfillment.waybill_change ADD CONSTRAINT ck_waybill_change_who CHECK (
    (source = 'WEBHOOK' AND changed_by IS NULL) OR (source IN ('MANUAL', 'FILE') AND changed_by IS NOT NULL)
    OR source = 'API');
