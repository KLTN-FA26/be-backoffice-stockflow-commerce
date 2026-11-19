-- =============================================================================
-- WF4 09B, after the handover (kltn-docs 09 §4.2 step 9, §4.3, §4.5, §5.3, BR-14):
--
--  1. fulfillment.load_plan_package.release_reason takes REMOVED_FROM_VEHICLE (09 §5.3, decided 2026-11-05): a parcel
--     taken off a vehicle being loaded, through its controlled cancel - damaged, or its order's installment before
--     delivery unpaid - leaves the plan and waits at the dispatch area for another one (G5, 09A-159).
--  2. fulfillment.shipment_pod_file keeps a proof of delivery the carrier's webhook sent (09 §4.3 step 2, §4.5 step 7:
--     "Giao thành công (kèm POD nếu có) ... POD đính kèm"): uploaded by nobody, naming the carrier event instead; one
--     file per event and shipment (G7).
--  3. fulfillment.failure_reason_correction: the failure reason of a failed or returned shipment corrected by the
--     warehouse manager once the real one is known (09 edge "hoàn hàng khi còn Handed Over ... Quản lý kho sửa lý do
--     thất bại nếu biết lý do thật"): before and after, why, who, when. Append-only (G12).
-- =============================================================================

-- ---------------------------------------------------------------------------------------------------------- 1

ALTER TABLE fulfillment.load_plan_package DROP CONSTRAINT ck_load_plan_package_release;
ALTER TABLE fulfillment.load_plan_package ADD CONSTRAINT ck_load_plan_package_release CHECK (release_reason IS NULL
    OR release_reason IN ('INPUTS_CHANGED', 'LEFT_BEHIND', 'PLAN_CANCELLED', 'ORDER_CANCELLED', 'VEHICLE_DROPPED',
                          'REMOVED_FROM_VEHICLE'));

-- ---------------------------------------------------------------------------------------------------------- 2

ALTER TABLE fulfillment.shipment_pod_file ALTER COLUMN uploaded_by DROP NOT NULL;
ALTER TABLE fulfillment.shipment_pod_file ADD COLUMN carrier_event_id uuid;
ALTER TABLE fulfillment.shipment_pod_file
    ADD CONSTRAINT fk_shipment_pod_file_carrier_event FOREIGN KEY (carrier_event_id)
        REFERENCES fulfillment.carrier_event (id),
    -- Either a person uploaded it or a carrier event sent it, never both or neither.
    ADD CONSTRAINT ck_shipment_pod_file_source CHECK ((uploaded_by IS NULL) <> (carrier_event_id IS NULL)),
    ADD CONSTRAINT uk_shipment_pod_file_carrier_event UNIQUE (carrier_event_id, shipment_id);

COMMENT ON COLUMN fulfillment.shipment_pod_file.carrier_event_id IS
    'The carrier webhook event whose proof of delivery this is (09 §4.5 step 7); null for one a person uploaded.';

-- ---------------------------------------------------------------------------------------------------------- 3

CREATE TABLE fulfillment.failure_reason_correction (
    id               uuid                     NOT NULL,
    shipment_id      uuid                     NOT NULL,
    from_reason      varchar(32)              NOT NULL,
    from_note        varchar(500),
    to_reason        varchar(32)              NOT NULL,
    to_note          varchar(500),
    reason           varchar(500)             NOT NULL,
    corrected_by     uuid                     NOT NULL,
    corrected_at     timestamp with time zone NOT NULL,
    version          bigint                   NOT NULL DEFAULT 0,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    created_by       varchar(100),
    last_modified_at timestamp with time zone,
    last_modified_by varchar(100),
    CONSTRAINT pk_failure_reason_correction PRIMARY KEY (id),
    CONSTRAINT ck_failure_reason_correction_reasons CHECK (
        from_reason IN ('CUSTOMER_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'LOST', 'DAMAGED', 'OTHER')
        AND to_reason IN ('CUSTOMER_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'LOST', 'DAMAGED', 'OTHER')
        AND (to_reason <> 'OTHER' OR btrim(coalesce(to_note, '')) <> '')),
    CONSTRAINT ck_failure_reason_correction_changed CHECK (
        from_reason <> to_reason OR from_note IS DISTINCT FROM to_note),
    CONSTRAINT ck_failure_reason_correction_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT fk_failure_reason_correction_shipment FOREIGN KEY (shipment_id) REFERENCES fulfillment.shipment (id),
    CONSTRAINT fk_failure_reason_correction_by FOREIGN KEY (corrected_by) REFERENCES identity.app_user (id)
);

CREATE INDEX ix_failure_reason_correction_shipment
    ON fulfillment.failure_reason_correction (shipment_id, corrected_at);
CREATE INDEX ix_failure_reason_correction_by ON fulfillment.failure_reason_correction (corrected_by);

CREATE TRIGGER trg_failure_reason_correction_append_only BEFORE DELETE OR UPDATE
    ON fulfillment.failure_reason_correction FOR EACH ROW EXECUTE FUNCTION platform.append_only();

COMMENT ON TABLE fulfillment.failure_reason_correction IS
    'A failed or returned shipment''s failure reason corrected by the warehouse manager once the real one is known '
    '(kltn-docs 09 edge "hoàn hàng khi còn Handed Over"). Append-only.';
