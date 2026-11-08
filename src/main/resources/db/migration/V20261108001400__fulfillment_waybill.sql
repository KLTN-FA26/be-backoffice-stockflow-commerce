-- =============================================================================
-- The carrier's waybill number on each load, its history, its files, and the handover it is required for (kltn-docs
-- 09 §4.4, BR-18, BR-23, BR-24, BR-28; team plan 3-2).
--
--   * fulfillment.dispatch_load.waybill_no - one number per vehicle, recorded once a carrier is booked (typed now,
--     imported from a file in 3-3, pushed by the carrier's webhook in 3-5, returned by its booking API in 3-4).
--       - uk_dispatch_load_waybill: unique within its carrier among loads not CANCELLED (E19: two carriers may use the
--         same number; a cancelled load's number may be reused).
--       - ck_dispatch_load_waybill_carrier: a number belongs to the booked carrier, so it goes when the booking does.
--       - ck_dispatch_load_dispatched_waybill: a load leaves only with its number (BR-23). NOT VALID: loads that left
--         before the rule existed carry none and are not made up a number; every load dispatched from now on is held.
--   * fulfillment.waybill_change - every change of a load's number: old, new (null when the booking was cancelled and
--     the number went with it), where it came from (MANUAL, FILE, WEBHOOK, API), who, when and why. Append-only.
--     A correction after the vehicle left says why (BR-24); the application checks that, since a row does not know
--     whether its load had left.
--   * fulfillment.load_waybill_file - the carrier's waybill documents kept with a load (PDF, image, Excel; BR-28,
--     optional). Stored in the 'documents' category; customers never see them.
--   * fulfillment-waybill-corrections:UPDATE - correcting a number after the vehicle left (BR-24: warehouse manager
--     only). Before that, recording it is the dispatcher's loading work (fulfillment-dispatch:UPDATE). SYSTEM_ADMIN
--     holds it, as every permission; both roles move their version (ADR-0008).
--
-- No existing row changes: loads booked before this have no number until one is recorded.
-- =============================================================================

-- 1. The number on the load -------------------------------------------------------------------------
ALTER TABLE fulfillment.dispatch_load
    ADD COLUMN waybill_no VARCHAR(64);

ALTER TABLE fulfillment.dispatch_load
    ADD CONSTRAINT ck_dispatch_load_waybill_no CHECK (waybill_no IS NULL OR waybill_no ~ '^[!-~]{1,64}$'),
    ADD CONSTRAINT ck_dispatch_load_waybill_carrier CHECK (waybill_no IS NULL OR carrier_id IS NOT NULL);

ALTER TABLE fulfillment.dispatch_load
    ADD CONSTRAINT ck_dispatch_load_dispatched_waybill CHECK (status <> 'DISPATCHED' OR waybill_no IS NOT NULL)
        NOT VALID;

CREATE UNIQUE INDEX uk_dispatch_load_waybill ON fulfillment.dispatch_load (carrier_id, waybill_no)
    WHERE waybill_no IS NOT NULL AND status <> 'CANCELLED';

-- 2. Its history ------------------------------------------------------------------------------------
CREATE TABLE fulfillment.waybill_change
(
    id             UUID         NOT NULL,
    load_id        UUID         NOT NULL,
    old_waybill_no VARCHAR(64),
    new_waybill_no VARCHAR(64),
    source         VARCHAR(16)  NOT NULL,
    changed_by     UUID,
    changed_at     TIMESTAMPTZ  NOT NULL,
    reason         VARCHAR(500),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_waybill_change PRIMARY KEY (id),
    CONSTRAINT fk_waybill_change_load FOREIGN KEY (load_id) REFERENCES fulfillment.dispatch_load (id),
    CONSTRAINT fk_waybill_change_changed_by FOREIGN KEY (changed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_waybill_change_source CHECK (source IN ('MANUAL', 'FILE', 'WEBHOOK', 'API')),
    -- A change changes something; a webhook is nobody, everything else is someone.
    CONSTRAINT ck_waybill_change_changes CHECK (old_waybill_no IS DISTINCT FROM new_waybill_no),
    CONSTRAINT ck_waybill_change_who CHECK ((source = 'WEBHOOK') = (changed_by IS NULL)),
    CONSTRAINT ck_waybill_change_reason CHECK (reason IS NULL OR btrim(reason) <> '')
);

CREATE INDEX ix_waybill_change_load ON fulfillment.waybill_change (load_id, changed_at, id);
CREATE INDEX ix_waybill_change_changed_by ON fulfillment.waybill_change (changed_by) WHERE changed_by IS NOT NULL;

CREATE TRIGGER trg_waybill_change_append_only
    BEFORE UPDATE OR DELETE ON fulfillment.waybill_change
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- 3. Its files --------------------------------------------------------------------------------------
CREATE TABLE fulfillment.load_waybill_file
(
    id           UUID         NOT NULL,
    load_id      UUID         NOT NULL,
    storage_key  VARCHAR(512) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    uploaded_by  UUID         NOT NULL,
    uploaded_at  TIMESTAMPTZ  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_load_waybill_file PRIMARY KEY (id),
    CONSTRAINT fk_load_waybill_file_load FOREIGN KEY (load_id) REFERENCES fulfillment.dispatch_load (id),
    CONSTRAINT fk_load_waybill_file_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES identity.app_user (id),
    -- One row per stored file: the orphan sweep and a repeated browser upload both find a file by its key.
    CONSTRAINT uk_load_waybill_file_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_load_waybill_file_key CHECK (storage_key LIKE 'documents/%'),
    CONSTRAINT ck_load_waybill_file_name CHECK (btrim(file_name) <> ''),
    CONSTRAINT ck_load_waybill_file_size CHECK (size_bytes > 0)
);

CREATE INDEX ix_load_waybill_file_load ON fulfillment.load_waybill_file (load_id, uploaded_at, id);
CREATE INDEX ix_load_waybill_file_uploaded_by ON fulfillment.load_waybill_file (uploaded_by);

-- 4. Who corrects it after the vehicle left ---------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'fulfillment-waybill-corrections:UPDATE', 'fulfillment-waybill-corrections', 'UPDATE', 0,
        NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission ON permission.code = 'fulfillment-waybill-corrections:UPDATE'
     WHERE role.code IN ('WAREHOUSE_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
