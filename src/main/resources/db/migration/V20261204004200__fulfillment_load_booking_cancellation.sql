-- =============================================================================
-- A cancelled vehicle booking stays on record (kltn-docs 09 §4.2 step 6: "thông tin đặt xe (hãng, mã vận đơn, biển số,
-- tài xế) bị xoá khỏi chuyến, vẫn lưu trong lịch sử"; RTM 09A-125).
--
--   * fulfillment.load_booking_cancellation - one row per booking taken off a load, written as it is cleared: the
--     carrier, waybill number, plate and driver the load had, when it was booked and by whom, and when, by whom and
--     why the booking was cancelled. Every path that cancels a booking writes it: by hand, a carrier order cancelled
--     through the API, the carrier cancelling its own order, a shipment taken off a load booked on a carrier order.
--     Append-only: the load row itself keeps only the last cancellation's time and reason.
-- =============================================================================

CREATE TABLE fulfillment.load_booking_cancellation
(
    id               UUID         NOT NULL,
    load_id          UUID         NOT NULL,
    carrier_id       UUID         NOT NULL,
    waybill_no       VARCHAR(64),
    vehicle_plate    VARCHAR(20),
    driver_name      VARCHAR(120),
    driver_phone     VARCHAR(32),
    booked_at        TIMESTAMPTZ,
    booked_by        UUID,
    cancelled_at     TIMESTAMPTZ  NOT NULL,
    -- Null when the system cancelled it (the carrier's webhook, a shipment taken off), as booking_cancelled_by is.
    cancelled_by     UUID,
    reason           VARCHAR(500) NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_load_booking_cancellation PRIMARY KEY (id),
    CONSTRAINT fk_load_booking_cancellation_load FOREIGN KEY (load_id) REFERENCES fulfillment.dispatch_load (id),
    CONSTRAINT fk_load_booking_cancellation_carrier FOREIGN KEY (carrier_id) REFERENCES fulfillment.carrier (id),
    CONSTRAINT fk_load_booking_cancellation_booked_by FOREIGN KEY (booked_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_load_booking_cancellation_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES identity.app_user (id),
    -- A booking is cancelled with a reason (DispatchLoad#cancelBooking), as ck_dispatch_load_booking_cancel says.
    CONSTRAINT ck_load_booking_cancellation_reason CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_load_booking_cancellation_load ON fulfillment.load_booking_cancellation (load_id, cancelled_at, id);
CREATE INDEX ix_load_booking_cancellation_carrier ON fulfillment.load_booking_cancellation (carrier_id);
CREATE INDEX ix_load_booking_cancellation_booked_by ON fulfillment.load_booking_cancellation (booked_by)
    WHERE booked_by IS NOT NULL;
CREATE INDEX ix_load_booking_cancellation_cancelled_by ON fulfillment.load_booking_cancellation (cancelled_by)
    WHERE cancelled_by IS NOT NULL;
