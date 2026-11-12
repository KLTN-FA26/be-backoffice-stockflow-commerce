-- =============================================================================
-- Delivery outcomes from a file, and the defences of a webhook that is not signed (WF-05 fix plan items 5 and 6;
-- kltn-docs 09 §4.3, §4.5, BR-26, BR-27).
--
--   * fulfillment.delivery_outcome_import / _row - one file of delivery outcomes a warehouse manager applied: who, when,
--     the file's name and digest, and each line as it was written with what the check made of it (ACCEPTED, or ERROR
--     with its reason). Written once, before the lines are applied; append-only. The file itself is not kept: every
--     line is here, and every accepted line is a carrier event.
--   * fulfillment.carrier_event.source - WEBHOOK (the carrier called) or FILE (a line of an imported file). A FILE event
--     names its import and line, one event per line, and goes through the same decision as a webhook: a repeat is
--     DUPLICATE, an older report is STALE (BR-27), so a file applied twice changes nothing.
--   * result UNVERIFIED - a carrier that does not sign its webhooks (Ahamove) reported a final outcome (delivered,
--     failed, cancelled) that its own API, asked back, does not confirm. Not applied; the warehouse manager's error
--     list, resolved by hand like the rest of it.
--   * fulfillment.carrier.webhook_auth_value_next_sealed - a second credential the webhook header may carry while the
--     first is being replaced at the carrier (API_BOOKING only): both hold until the new one is promoted.
--   * fulfillment-delivery-outcome-imports - READ (the template, past imports) and CREATE (preview and apply a file):
--     the warehouse manager and the administrator, whose versions move (ADR-0008).
-- =============================================================================

-- 1. A second webhook credential while the first is rotated ----------------------------------------
ALTER TABLE fulfillment.carrier
    ADD COLUMN webhook_auth_value_next_sealed TEXT,
    -- Only beside a first credential, of a carrier whose webhooks carry one; sealed like every secret.
    ADD CONSTRAINT ck_carrier_webhook_auth_next CHECK (webhook_auth_value_next_sealed IS NULL
        OR (connection_type = 'API_BOOKING' AND webhook_auth_value_sealed IS NOT NULL
            AND webhook_auth_value_next_sealed ~ '^v1:[A-Za-z0-9+/]+={0,2}$'));

-- 2. The import and its lines -----------------------------------------------------------------------
CREATE TABLE fulfillment.delivery_outcome_import
(
    id               UUID         NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_sha256      VARCHAR(64)  NOT NULL,
    uploaded_by      UUID         NOT NULL,
    uploaded_at      TIMESTAMPTZ  NOT NULL,
    rows_total       INTEGER      NOT NULL,
    rows_accepted    INTEGER      NOT NULL,
    rows_rejected    INTEGER      NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_delivery_outcome_import PRIMARY KEY (id),
    CONSTRAINT fk_delivery_outcome_import_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_delivery_outcome_import_name CHECK (btrim(file_name) <> ''),
    CONSTRAINT ck_delivery_outcome_import_digest CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    -- A file of 1 to 1,000 lines (DeliveryOutcomeFile.MAX_ROWS), each accepted or refused.
    CONSTRAINT ck_delivery_outcome_import_rows CHECK (rows_total BETWEEN 1 AND 1000 AND rows_accepted >= 0
        AND rows_rejected >= 0 AND rows_accepted + rows_rejected = rows_total)
);

CREATE INDEX ix_delivery_outcome_import_uploaded ON fulfillment.delivery_outcome_import (uploaded_at DESC, id DESC);
CREATE INDEX ix_delivery_outcome_import_uploaded_by ON fulfillment.delivery_outcome_import (uploaded_by, uploaded_at DESC);

CREATE TABLE fulfillment.delivery_outcome_import_row
(
    id               UUID         NOT NULL,
    import_id        UUID         NOT NULL,
    line_no          INTEGER      NOT NULL,
    waybill_no       VARCHAR(255),
    outcome          VARCHAR(255),
    failure_reason   VARCHAR(500),
    reported_at      VARCHAR(255),
    recipient        VARCHAR(255),
    note             VARCHAR(500),
    shipment_code    VARCHAR(255),
    load_id          UUID,
    verdict          VARCHAR(16)  NOT NULL,
    problem          VARCHAR(32),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_delivery_outcome_import_row PRIMARY KEY (id),
    CONSTRAINT fk_delivery_outcome_import_row_import FOREIGN KEY (import_id)
        REFERENCES fulfillment.delivery_outcome_import (id),
    CONSTRAINT fk_delivery_outcome_import_row_load FOREIGN KEY (load_id) REFERENCES fulfillment.dispatch_load (id),
    CONSTRAINT uk_delivery_outcome_import_row_line UNIQUE (import_id, line_no),
    CONSTRAINT ck_delivery_outcome_import_row_line CHECK (line_no >= 2),
    CONSTRAINT ck_delivery_outcome_import_row_verdict CHECK (verdict IN ('ACCEPTED', 'ERROR')),
    CONSTRAINT ck_delivery_outcome_import_row_problem CHECK ((verdict = 'ERROR') = (problem IS NOT NULL)
        AND (problem IS NULL OR problem IN ('MISSING_FIELDS', 'TEXT_TOO_LONG', 'INVALID_OUTCOME', 'INVALID_TIME',
            'TIME_IN_FUTURE', 'REASON_REQUIRED', 'WAYBILL_NOT_FOUND', 'WAYBILL_AMBIGUOUS', 'LOAD_NOT_DISPATCHED',
            'SHIPMENT_NOT_ON_LOAD', 'DUPLICATE_IN_FILE', 'OUTCOME_NOT_ALLOWED'))),
    -- An accepted line found its load; a refused one may not have found any.
    CONSTRAINT ck_delivery_outcome_import_row_load CHECK (verdict = 'ERROR'
        OR (load_id IS NOT NULL AND waybill_no IS NOT NULL))
);

CREATE INDEX ix_delivery_outcome_import_row_load ON fulfillment.delivery_outcome_import_row (load_id)
    WHERE load_id IS NOT NULL;

CREATE TRIGGER trg_delivery_outcome_import_append_only
    BEFORE UPDATE OR DELETE ON fulfillment.delivery_outcome_import
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();
CREATE TRIGGER trg_delivery_outcome_import_row_append_only
    BEFORE UPDATE OR DELETE ON fulfillment.delivery_outcome_import_row
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- 3. The event log: where an event came from, and the result of a check that failed ------------------
ALTER TABLE fulfillment.carrier_event
    ADD COLUMN source              VARCHAR(8) NOT NULL DEFAULT 'WEBHOOK',
    ADD COLUMN outcome_import_id   UUID,
    ADD COLUMN outcome_import_line INTEGER,
    ADD CONSTRAINT fk_carrier_event_outcome_import FOREIGN KEY (outcome_import_id)
        REFERENCES fulfillment.delivery_outcome_import (id),
    ADD CONSTRAINT ck_carrier_event_source CHECK (source IN ('WEBHOOK', 'FILE')),
    -- A line of a file names its import and line; a webhook call names neither.
    ADD CONSTRAINT ck_carrier_event_import CHECK ((source = 'FILE') = (outcome_import_id IS NOT NULL)
        AND (outcome_import_id IS NULL) = (outcome_import_line IS NULL)
        AND (outcome_import_line IS NULL OR outcome_import_line >= 2)),
    -- A line becomes an event only once its load is found, and it was sent by someone signed in.
    ADD CONSTRAINT ck_carrier_event_file CHECK (source <> 'FILE'
        OR (signature_valid AND carrier_id IS NOT NULL AND load_id IS NOT NULL)),
    DROP CONSTRAINT ck_carrier_event_result,
    ADD CONSTRAINT ck_carrier_event_result CHECK (result IN ('RECEIVED', 'APPLIED', 'DUPLICATE', 'STALE', 'IGNORED',
        'UNMATCHED', 'INVALID_SIGNATURE', 'NEEDS_REVIEW', 'UNVERIFIED')),
    -- What the carrier's own API did not confirm was a webhook's report about a load, and says what did not agree.
    ADD CONSTRAINT ck_carrier_event_unverified CHECK (result <> 'UNVERIFIED'
        OR (source = 'WEBHOOK' AND load_id IS NOT NULL AND result_note IS NOT NULL)),
    DROP CONSTRAINT ck_carrier_event_resolvable,
    ADD CONSTRAINT ck_carrier_event_resolvable CHECK (resolved_at IS NULL
        OR result IN ('RECEIVED', 'UNMATCHED', 'INVALID_SIGNATURE', 'NEEDS_REVIEW', 'UNVERIFIED'));

-- One event per line of a file.
CREATE UNIQUE INDEX uk_carrier_event_import_line ON fulfillment.carrier_event (outcome_import_id, outcome_import_line)
    WHERE outcome_import_id IS NOT NULL;

-- The error list, open items first: UNVERIFIED is on it.
DROP INDEX fulfillment.ix_carrier_event_errors;
CREATE INDEX ix_carrier_event_errors ON fulfillment.carrier_event (received_at DESC)
    WHERE result IN ('RECEIVED', 'UNMATCHED', 'INVALID_SIGNATURE', 'NEEDS_REVIEW', 'UNVERIFIED')
        AND resolved_at IS NULL;

-- Where an event came from is part of what was received: it never changes either.
CREATE OR REPLACE FUNCTION fulfillment.carrier_event_guard()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'fulfillment.carrier_event is never deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.carrier_id IS DISTINCT FROM OLD.carrier_id OR NEW.carrier_code IS DISTINCT FROM OLD.carrier_code
        OR NEW.received_at IS DISTINCT FROM OLD.received_at OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.signature_valid IS DISTINCT FROM OLD.signature_valid OR NEW.source IS DISTINCT FROM OLD.source
        OR NEW.outcome_import_id IS DISTINCT FROM OLD.outcome_import_id
        OR NEW.outcome_import_line IS DISTINCT FROM OLD.outcome_import_line THEN
        RAISE EXCEPTION 'What a carrier sent is kept as it came' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.result <> 'RECEIVED' AND (NEW.result IS DISTINCT FROM OLD.result
        OR NEW.result_note IS DISTINCT FROM OLD.result_note OR NEW.load_id IS DISTINCT FROM OLD.load_id
        OR NEW.shipment_id IS DISTINCT FROM OLD.shipment_id OR NEW.event_kind IS DISTINCT FROM OLD.event_kind
        OR NEW.dedupe_key IS DISTINCT FROM OLD.dedupe_key OR NEW.decided_at IS DISTINCT FROM OLD.decided_at) THEN
        RAISE EXCEPTION 'A carrier event is decided once and never applied again (09 BR-27)'
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.resolved_at IS NOT NULL AND (NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
        OR NEW.resolved_by IS DISTINCT FROM OLD.resolved_by
        OR NEW.resolution_note IS DISTINCT FROM OLD.resolution_note) THEN
        RAISE EXCEPTION 'A resolved carrier event stays resolved as it was' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- 4. Who imports outcomes ---------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'fulfillment-delivery-outcome-imports:READ', 'fulfillment-delivery-outcome-imports', 'READ',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'fulfillment-delivery-outcome-imports:CREATE', 'fulfillment-delivery-outcome-imports',
        'CREATE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission
        ON permission.code IN ('fulfillment-delivery-outcome-imports:READ',
                               'fulfillment-delivery-outcome-imports:CREATE')
     WHERE role.code IN ('WAREHOUSE_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
