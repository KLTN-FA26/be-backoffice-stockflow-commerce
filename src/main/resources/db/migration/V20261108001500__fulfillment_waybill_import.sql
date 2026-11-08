-- =============================================================================
-- Waybill numbers imported from a file (kltn-docs 09 §4.4 "Nhập hàng loạt từ file", BR-25; E18; QD-17; team plan
-- 3-3).
--
--   * fulfillment.waybill_import - one file imported: who, when, the file (kept in the 'waybill-imports' category, CSV
--     UTF-8 from the system's template), and how many of its lines were applied and how many refused. Written once,
--     when the file is applied; append-only.
--   * fulfillment.waybill_import_row - each line as it was written and what it came to: NEW, OVERWRITE (the load had
--     another number), SAME, or ERROR with its reason ("dòng lỗi giữ trong kết quả nhập kèm lý do"). Only the valid
--     lines are applied (E18), and a line overwriting a number only when the preview showed it and the dispatcher
--     confirmed it (BR-25; OVERWRITE_NOT_CONFIRMED otherwise). Append-only.
--   * fulfillment.load_waybill_file - the imported file is kept as a waybill file of every load a line changed (09
--     §4.4 step 4): the file itself when all its lines are one warehouse's, otherwise the extract of that warehouse's
--     lines, so no load shows another warehouse's lines. One stored file for several loads: the key is unique per load,
--     not across the table; a row names the import it came from; the key may be a waybill import's.
--   * fulfillment-waybill-imports - READ (the template, past imports) and CREATE (preview and apply a file): the
--     dispatcher, the warehouse manager and the administrator, whose versions move (ADR-0008).
-- =============================================================================

-- 1. The import and its lines -----------------------------------------------------------------------
CREATE TABLE fulfillment.waybill_import
(
    id               UUID         NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    storage_key      VARCHAR(512) NOT NULL,
    uploaded_by      UUID         NOT NULL,
    uploaded_at      TIMESTAMPTZ  NOT NULL,
    rows_total       INTEGER      NOT NULL,
    rows_applied     INTEGER      NOT NULL,
    rows_rejected    INTEGER      NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_waybill_import PRIMARY KEY (id),
    CONSTRAINT fk_waybill_import_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES identity.app_user (id),
    CONSTRAINT uk_waybill_import_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_waybill_import_key CHECK (storage_key LIKE 'waybill-imports/%'),
    CONSTRAINT ck_waybill_import_name CHECK (btrim(file_name) <> ''),
    -- A file of 1 to 1,000 lines (WaybillImportFile.MAX_ROWS), each applied or refused.
    CONSTRAINT ck_waybill_import_rows CHECK (rows_total BETWEEN 1 AND 1000 AND rows_applied >= 0
        AND rows_rejected >= 0 AND rows_applied + rows_rejected = rows_total)
);

CREATE INDEX ix_waybill_import_uploaded ON fulfillment.waybill_import (uploaded_at DESC, id DESC);
CREATE INDEX ix_waybill_import_uploaded_by ON fulfillment.waybill_import (uploaded_by);

CREATE TABLE fulfillment.waybill_import_row
(
    id               UUID         NOT NULL,
    import_id        UUID         NOT NULL,
    line_no          INTEGER      NOT NULL,
    load_code        VARCHAR(64),
    waybill_no       VARCHAR(255),
    vehicle_plate    VARCHAR(255),
    driver_name      VARCHAR(255),
    driver_phone     VARCHAR(255),
    load_id          UUID,
    outcome          VARCHAR(16)  NOT NULL,
    problem          VARCHAR(32),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_waybill_import_row PRIMARY KEY (id),
    CONSTRAINT fk_waybill_import_row_import FOREIGN KEY (import_id) REFERENCES fulfillment.waybill_import (id),
    CONSTRAINT fk_waybill_import_row_load FOREIGN KEY (load_id) REFERENCES fulfillment.dispatch_load (id),
    CONSTRAINT uk_waybill_import_row_line UNIQUE (import_id, line_no),
    CONSTRAINT ck_waybill_import_row_line CHECK (line_no >= 2),
    CONSTRAINT ck_waybill_import_row_outcome CHECK (outcome IN ('NEW', 'OVERWRITE', 'SAME', 'ERROR')),
    CONSTRAINT ck_waybill_import_row_problem CHECK ((outcome = 'ERROR') = (problem IS NOT NULL)
        AND (problem IS NULL OR problem IN ('MISSING_FIELDS', 'INVALID_WAYBILL', 'INVALID_VEHICLE_DETAILS',
            'LOAD_NOT_FOUND', 'LOAD_OUT_OF_SCOPE', 'LOAD_REPEATED', 'LOAD_NOT_BOOKED', 'LOAD_CANCELLED', 'LOAD_LEFT',
            'WAYBILL_FROM_CARRIER_API', 'DUPLICATE_IN_FILE', 'DUPLICATE_IN_CARRIER', 'OVERWRITE_NOT_CONFIRMED'))),
    -- An applied line names its load; a refused one may not have found any.
    CONSTRAINT ck_waybill_import_row_load CHECK (outcome = 'ERROR' OR (load_id IS NOT NULL AND waybill_no IS NOT NULL))
);

CREATE INDEX ix_waybill_import_row_load ON fulfillment.waybill_import_row (load_id) WHERE load_id IS NOT NULL;

CREATE TRIGGER trg_waybill_import_append_only
    BEFORE UPDATE OR DELETE ON fulfillment.waybill_import
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();
CREATE TRIGGER trg_waybill_import_row_append_only
    BEFORE UPDATE OR DELETE ON fulfillment.waybill_import_row
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- 2. The imported file as a load's waybill file -----------------------------------------------------
ALTER TABLE fulfillment.load_waybill_file
    ADD COLUMN waybill_import_id UUID,
    ADD CONSTRAINT fk_load_waybill_file_import FOREIGN KEY (waybill_import_id)
        REFERENCES fulfillment.waybill_import (id),
    DROP CONSTRAINT uk_load_waybill_file_storage_key,
    ADD CONSTRAINT uk_load_waybill_file_load_key UNIQUE (load_id, storage_key),
    DROP CONSTRAINT ck_load_waybill_file_key,
    ADD CONSTRAINT ck_load_waybill_file_key CHECK (storage_key LIKE 'documents/%'
        OR (storage_key LIKE 'waybill-imports/%' AND waybill_import_id IS NOT NULL));

-- The orphan sweep and a repeated browser upload still look a file up by its key.
CREATE INDEX ix_load_waybill_file_storage_key ON fulfillment.load_waybill_file (storage_key);
CREATE INDEX ix_load_waybill_file_import ON fulfillment.load_waybill_file (waybill_import_id)
    WHERE waybill_import_id IS NOT NULL;

-- 3. Who imports ------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'fulfillment-waybill-imports:READ', 'fulfillment-waybill-imports', 'READ', 0, NOW(),
        'flyway'),
       (gen_random_uuid(), 'fulfillment-waybill-imports:CREATE', 'fulfillment-waybill-imports', 'CREATE', 0, NOW(),
        'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission
        ON permission.code IN ('fulfillment-waybill-imports:READ', 'fulfillment-waybill-imports:CREATE')
     WHERE role.code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
