-- =============================================================================
-- What carriers report through their webhooks (kltn-docs 09 §4.5, BR-22, BR-26, BR-27; E15, E20, E21, E22, E25; team
-- plan 3-5).
--
--   * fulfillment.carrier_event - every call made to a carrier's webhook, kept before anything is done with it (BR-27
--     "mọi sự kiện lưu nhật ký"): the body as received (the carrier's echo of our own API key masked), when it came,
--     whether its credential held, what it reported (the kind, the carrier's reference, the time the carrier gives),
--     the load and shipment it was matched to, and what came of it:
--       RECEIVED          - stored, being handled (one left so is a crash in between: the list shows it);
--       APPLIED           - it changed the load or the shipment, or recorded a milestone;
--       DUPLICATE         - the same report again (BR-27): recorded once;
--       STALE             - older than what was last applied to the same load or shipment, or about an order the load
--                           no longer has (BR-27);
--       IGNORED           - nothing in it for the system (a status the mapping does not use);
--       UNMATCHED         - no load of this carrier to match, or a load that is cancelled (BR-22);
--       INVALID_SIGNATURE - its signature or credential did not hold (09 §4.5 step 3), or it named no carrier we know;
--       NEEDS_REVIEW      - not valid for the load's or shipment's state (09 §4.5 step 7).
--     The last three are the warehouse manager's error list (step 8): resolved by hand, with a note, never applied
--     again. Once decided, a result never changes, nor does what was received (fulfillment.carrier_event_guard).
--   * fulfillment.tracking_milestone - the vehicle's journey as the carrier reports it (09 §4.5 step 9): picked up, on
--     its way, returning, returned; one of each per load, the first report kept. A shipment's delivery is its own
--     shipment_event step.
--
-- New permission fulfillment-carrier-events (READ, UPDATE): the warehouse manager and the administrator.
-- =============================================================================

-- 1. The event log ------------------------------------------------------------------------------------
CREATE TABLE fulfillment.carrier_event
(
    id                  UUID          NOT NULL,
    carrier_id          UUID,
    carrier_code        VARCHAR(64)   NOT NULL,
    received_at         TIMESTAMPTZ   NOT NULL,
    payload             TEXT          NOT NULL,
    signature_valid     BOOLEAN       NOT NULL,
    event_kind          VARCHAR(24),
    carrier_reference   VARCHAR(128),
    carrier_reported_at TIMESTAMPTZ,
    dedupe_key          VARCHAR(64),
    warehouse_id        UUID,
    load_id             UUID,
    shipment_id         UUID,
    result              VARCHAR(20)   NOT NULL,
    result_note         VARCHAR(1000),
    decided_at          TIMESTAMPTZ,
    resolved_by         UUID,
    resolved_at         TIMESTAMPTZ,
    resolution_note     VARCHAR(1000),

    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_carrier_event PRIMARY KEY (id),
    CONSTRAINT fk_carrier_event_carrier FOREIGN KEY (carrier_id) REFERENCES fulfillment.carrier (id),
    CONSTRAINT fk_carrier_event_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT fk_carrier_event_load FOREIGN KEY (load_id) REFERENCES fulfillment.dispatch_load (id),
    CONSTRAINT fk_carrier_event_shipment FOREIGN KEY (shipment_id) REFERENCES fulfillment.shipment (id),
    CONSTRAINT fk_carrier_event_resolved_by FOREIGN KEY (resolved_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_carrier_event_result CHECK (result IN ('RECEIVED', 'APPLIED', 'DUPLICATE', 'STALE', 'IGNORED',
        'UNMATCHED', 'INVALID_SIGNATURE', 'NEEDS_REVIEW')),
    CONSTRAINT ck_carrier_event_kind CHECK (event_kind IS NULL OR event_kind IN ('WAYBILL', 'DRIVER_ASSIGNED',
        'DRIVER_RELEASED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'DELIVERY_FAILED', 'RETURNING', 'RETURNED',
        'ORDER_CANCELLED', 'ORDER_COMPLETED', 'OTHER')),
    CONSTRAINT ck_carrier_event_code CHECK (carrier_code ~ '^[!-~]{1,64}$'),
    CONSTRAINT ck_carrier_event_payload CHECK (octet_length(payload) <= 65536),
    -- A call whose credential did not hold is the error list's, whatever it says; nothing is matched for it.
    CONSTRAINT ck_carrier_event_signature CHECK (signature_valid OR (result = 'INVALID_SIGNATURE' AND load_id IS NULL)),
    CONSTRAINT ck_carrier_event_unknown_carrier CHECK (carrier_id IS NOT NULL OR result = 'INVALID_SIGNATURE'),
    -- What was applied, repeated or outdated was about a load; a shipment is always on one.
    CONSTRAINT ck_carrier_event_matched CHECK (result NOT IN ('APPLIED', 'DUPLICATE', 'STALE') OR load_id IS NOT NULL),
    CONSTRAINT ck_carrier_event_unmatched CHECK (result <> 'UNMATCHED' OR load_id IS NULL OR result_note IS NOT NULL),
    CONSTRAINT ck_carrier_event_shipment CHECK (shipment_id IS NULL OR load_id IS NOT NULL),
    CONSTRAINT ck_carrier_event_warehouse CHECK (load_id IS NULL OR warehouse_id IS NOT NULL),
    CONSTRAINT ck_carrier_event_decided CHECK ((result = 'RECEIVED') = (decided_at IS NULL)),
    -- Only the error list is resolved, once, by someone, with a note.
    CONSTRAINT ck_carrier_event_resolution CHECK ((resolved_at IS NULL) = (resolved_by IS NULL)
        AND (resolved_at IS NULL) = (resolution_note IS NULL)),
    CONSTRAINT ck_carrier_event_resolvable CHECK (resolved_at IS NULL
        OR result IN ('RECEIVED', 'UNMATCHED', 'INVALID_SIGNATURE', 'NEEDS_REVIEW')),
    CONSTRAINT ck_carrier_event_resolution_note CHECK (resolution_note IS NULL OR btrim(resolution_note) <> '')
);

-- Two copies of one report racing: one of them applies (BR-27).
CREATE UNIQUE INDEX uk_carrier_event_applied ON fulfillment.carrier_event (dedupe_key) WHERE result = 'APPLIED';
CREATE INDEX ix_carrier_event_dedupe ON fulfillment.carrier_event (dedupe_key) WHERE dedupe_key IS NOT NULL;
-- The error list, open items first.
CREATE INDEX ix_carrier_event_errors ON fulfillment.carrier_event (received_at DESC)
    WHERE result IN ('RECEIVED', 'UNMATCHED', 'INVALID_SIGNATURE', 'NEEDS_REVIEW') AND resolved_at IS NULL;
CREATE INDEX ix_carrier_event_received ON fulfillment.carrier_event (received_at DESC);
-- The last report applied to a load or a shipment, for telling an outdated one (BR-27).
CREATE INDEX ix_carrier_event_load ON fulfillment.carrier_event (load_id, carrier_reported_at)
    WHERE load_id IS NOT NULL;
CREATE INDEX ix_carrier_event_shipment ON fulfillment.carrier_event (shipment_id, carrier_reported_at)
    WHERE shipment_id IS NOT NULL;
CREATE INDEX ix_carrier_event_carrier ON fulfillment.carrier_event (carrier_id, received_at DESC);
CREATE INDEX ix_carrier_event_warehouse ON fulfillment.carrier_event (warehouse_id, received_at DESC);
CREATE INDEX ix_carrier_event_resolved_by ON fulfillment.carrier_event (resolved_by);

-- What was received never changes, a decided result never changes, a resolution is written once, nothing is deleted.
CREATE FUNCTION fulfillment.carrier_event_guard()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'fulfillment.carrier_event is never deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.carrier_id IS DISTINCT FROM OLD.carrier_id OR NEW.carrier_code IS DISTINCT FROM OLD.carrier_code
        OR NEW.received_at IS DISTINCT FROM OLD.received_at OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.signature_valid IS DISTINCT FROM OLD.signature_valid THEN
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

CREATE TRIGGER trg_carrier_event_guard
    BEFORE UPDATE OR DELETE ON fulfillment.carrier_event
    FOR EACH ROW EXECUTE FUNCTION fulfillment.carrier_event_guard();

-- 2. The journey --------------------------------------------------------------------------------------
CREATE TABLE fulfillment.tracking_milestone
(
    id                  UUID          NOT NULL,
    load_id             UUID          NOT NULL,
    milestone           VARCHAR(16)   NOT NULL,
    carrier_reported_at TIMESTAMPTZ   NOT NULL,
    source              VARCHAR(16)   NOT NULL,
    carrier_event_id    UUID,
    recorded_at         TIMESTAMPTZ   NOT NULL,

    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_tracking_milestone PRIMARY KEY (id),
    CONSTRAINT fk_tracking_milestone_load FOREIGN KEY (load_id) REFERENCES fulfillment.dispatch_load (id),
    CONSTRAINT fk_tracking_milestone_event FOREIGN KEY (carrier_event_id) REFERENCES fulfillment.carrier_event (id),
    CONSTRAINT uk_tracking_milestone UNIQUE (load_id, milestone),
    CONSTRAINT ck_tracking_milestone_kind CHECK (milestone IN ('PICKED_UP', 'IN_TRANSIT', 'RETURNING', 'RETURNED')),
    CONSTRAINT ck_tracking_milestone_source CHECK (source IN ('CARRIER_WEBHOOK', 'MANUAL')),
    CONSTRAINT ck_tracking_milestone_event CHECK ((source = 'CARRIER_WEBHOOK') = (carrier_event_id IS NOT NULL))
);

CREATE INDEX ix_tracking_milestone_event ON fulfillment.tracking_milestone (carrier_event_id);

CREATE TRIGGER trg_tracking_milestone_append_only
    BEFORE UPDATE OR DELETE ON fulfillment.tracking_milestone
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- 3. Who reads and resolves the error list --------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'fulfillment-carrier-events:READ', 'fulfillment-carrier-events', 'READ', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'fulfillment-carrier-events:UPDATE', 'fulfillment-carrier-events', 'UPDATE', 0, NOW(),
        'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission
        ON permission.code IN ('fulfillment-carrier-events:READ', 'fulfillment-carrier-events:UPDATE')
     WHERE role.code IN ('WAREHOUSE_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
