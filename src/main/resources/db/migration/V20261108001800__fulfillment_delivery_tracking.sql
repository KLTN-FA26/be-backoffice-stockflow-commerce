-- =============================================================================
-- Following a delivery, and correcting one the carrier reported wrongly (kltn-docs 09 §4.3 steps 2 and 5, §4.5 step 9,
-- BR-28; 17 BR-27; E16, E17; team plan 3-6).
--
--   * fulfillment.delivery_outcome_correction - a shipment's outcome corrected by the warehouse manager because the
--     carrier reported it wrongly (09 §4.3 step 5, E17): DELIVERED ↔ DELIVERY_FAILED while the order is not completed,
--     with a reason, who and when. Append-only. A shipment corrected by hand takes no carrier report for its outcome any
--     more: the next one goes to the error list (E17).
--   * fulfillment.shipment_pod_file - the proof of delivery kept with a shipment (BR-28, E16: optional; customers never
--     see it). Stored in the 'documents' category.
--   * ordering.order_status_transition: DELIVERED → DELIVERY_FAILED, the order following a delivery corrected by hand
--     (17 §5.1: "chỉ khi Quản lý kho sửa tay kèm lý do vì hãng báo sai, đơn chưa hoàn tất").
--   * New permission fulfillment-delivery-corrections (UPDATE): the warehouse manager and the administrator.
-- =============================================================================

-- 1. Corrections ---------------------------------------------------------------------------------------
CREATE TABLE fulfillment.delivery_outcome_correction
(
    id             UUID          NOT NULL,
    shipment_id    UUID          NOT NULL,
    from_status    VARCHAR(24)   NOT NULL,
    to_status      VARCHAR(24)   NOT NULL,
    failure_reason VARCHAR(32),
    failure_note   VARCHAR(1000),
    reason         VARCHAR(500)  NOT NULL,
    corrected_by   UUID          NOT NULL,
    corrected_at   TIMESTAMPTZ   NOT NULL,

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_delivery_outcome_correction PRIMARY KEY (id),
    CONSTRAINT fk_delivery_outcome_correction_shipment FOREIGN KEY (shipment_id) REFERENCES fulfillment.shipment (id),
    CONSTRAINT fk_delivery_outcome_correction_by FOREIGN KEY (corrected_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_delivery_outcome_correction_statuses CHECK (from_status IN ('DELIVERED', 'DELIVERY_FAILED')
        AND to_status IN ('DELIVERED', 'DELIVERY_FAILED') AND from_status <> to_status),
    -- Corrected to a failure: why it failed (OTHER with a note, as a failure recorded by hand).
    CONSTRAINT ck_delivery_outcome_correction_failure CHECK ((to_status = 'DELIVERY_FAILED') = (failure_reason IS NOT NULL)
        AND (failure_reason IS NULL OR failure_reason IN ('CUSTOMER_ABSENT', 'REFUSED', 'WRONG_ADDRESS', 'LOST',
            'DAMAGED', 'OTHER'))
        AND (failure_reason IS DISTINCT FROM 'OTHER' OR btrim(coalesce(failure_note, '')) <> '')),
    CONSTRAINT ck_delivery_outcome_correction_reason CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_delivery_outcome_correction_shipment ON fulfillment.delivery_outcome_correction (shipment_id,
    corrected_at);
CREATE INDEX ix_delivery_outcome_correction_by ON fulfillment.delivery_outcome_correction (corrected_by);

CREATE TRIGGER trg_delivery_outcome_correction_append_only
    BEFORE UPDATE OR DELETE ON fulfillment.delivery_outcome_correction
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- 2. Proof of delivery ------------------------------------------------------------------------------
CREATE TABLE fulfillment.shipment_pod_file
(
    id           UUID         NOT NULL,
    shipment_id  UUID         NOT NULL,
    storage_key  VARCHAR(512) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    uploaded_by  UUID         NOT NULL,
    uploaded_at  TIMESTAMPTZ  NOT NULL,

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_shipment_pod_file PRIMARY KEY (id),
    CONSTRAINT fk_shipment_pod_file_shipment FOREIGN KEY (shipment_id) REFERENCES fulfillment.shipment (id),
    CONSTRAINT fk_shipment_pod_file_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES identity.app_user (id),
    -- One row per stored file: the orphan sweep and a repeated browser upload both find a file by its key.
    CONSTRAINT uk_shipment_pod_file_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_shipment_pod_file_key CHECK (storage_key LIKE 'documents/%'),
    CONSTRAINT ck_shipment_pod_file_name CHECK (btrim(file_name) <> ''),
    CONSTRAINT ck_shipment_pod_file_size CHECK (size_bytes > 0)
);

CREATE INDEX ix_shipment_pod_file_shipment ON fulfillment.shipment_pod_file (shipment_id, uploaded_at, id);
CREATE INDEX ix_shipment_pod_file_uploaded_by ON fulfillment.shipment_pod_file (uploaded_by);

-- 3. The order follows a delivery corrected by hand -------------------------------------------------
INSERT INTO ordering.order_status_transition (from_status, to_status)
VALUES ('DELIVERED', 'DELIVERY_FAILED')
ON CONFLICT DO NOTHING;

-- 4. Who corrects -----------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'fulfillment-delivery-corrections:UPDATE', 'fulfillment-delivery-corrections', 'UPDATE', 0,
        NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission ON permission.code = 'fulfillment-delivery-corrections:UPDATE'
     WHERE role.code IN ('WAREHOUSE_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
