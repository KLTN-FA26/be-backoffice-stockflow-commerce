-- =============================================================================
-- How each carrier talks to the system (kltn-docs 09 §3, §4.1 step 2, BR-21, BR-29; team plan 3-1).
--
--   * fulfillment.carrier gains its connection settings:
--       - connection_type: MANUAL (no webhook: waybill numbers typed or imported, outcomes recorded by hand),
--         WEBHOOK_LOAD (the carrier pushes events naming the internal load code, signed with a shared secret) or
--         API_BOOKING (the system creates and cancels the carrier's order, the carrier pushes order and stop statuses
--         carrying a credential in a header, since it signs nothing). Every existing carrier is MANUAL: that is how
--         they have been used so far.
--       - webhook_enabled: whether its webhooks are accepted now - off while a carrier misbehaves, the settings kept.
--         Never on for MANUAL.
--       - the three secrets, *_sealed: the webhook signing secret, the credential expected in the webhook header and
--         the API key. Sealed by the application (AES-256-GCM, bound to the carrier and the column), never stored in
--         clear and never shown again once saved (09 §4.1). The format check keeps a clear value out by mistake.
--       - webhook_auth_header, api_environment, api_account (Ahamove: the registered phone number the token is asked
--         for): what API_BOOKING needs besides its secrets.
--       - failure_reason_map: the carrier's wording of a failed delivery to the internal reason (09 §4.3 step 3);
--         wording missing from it is OTHER with the carrier's words as the note (E22).
--     What each kind of connection needs is ck_carrier_connection_*; a setting a connection does not use is cleared
--     by the application when the kind changes.
--   * fulfillment.carrier_service_code: an API_BOOKING carrier's service code per vehicle type and province - the
--     carrier sells a truck size per city (09 §3, E24: in-town only). province_code is ours, the code the order's
--     shipping address and the warehouse carry; carrier_city_code is the carrier's own id for that city (Ahamove
--     city_id, e.g. SGN), which its service list is asked by.
--   * The four parcel carriers V20261021000400 seeded (GHN, GHTK, VTP, JTE) become INACTIVE: parcel shipping is gone
--     (V20261108000400) and these are courier networks, not truck hirers. Not deleted - old shipments and loads still
--     name them. Their status before is archived.
--
-- No permission changes: the warehouse manager keeps the carrier catalogue with fulfillment-carriers:UPDATE, and
-- reading it stays fulfillment-shipments:READ.
-- =============================================================================

-- 1. Connection settings -------------------------------------------------------------------------
ALTER TABLE fulfillment.carrier
    ADD COLUMN connection_type           VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN webhook_enabled           BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN webhook_secret_sealed     TEXT,
    ADD COLUMN webhook_auth_header       VARCHAR(64),
    ADD COLUMN webhook_auth_value_sealed TEXT,
    ADD COLUMN api_environment           VARCHAR(16),
    ADD COLUMN api_account               VARCHAR(64),
    ADD COLUMN api_key_sealed            TEXT,
    ADD COLUMN failure_reason_map        JSONB       NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE fulfillment.carrier
    ADD CONSTRAINT ck_carrier_connection_type CHECK (connection_type IN ('MANUAL', 'WEBHOOK_LOAD', 'API_BOOKING')),
    ADD CONSTRAINT ck_carrier_connection_manual CHECK (connection_type <> 'MANUAL' OR NOT webhook_enabled),
    -- A carrier that signs its events needs the secret they are signed with.
    ADD CONSTRAINT ck_carrier_connection_webhook_load CHECK (connection_type <> 'WEBHOOK_LOAD'
        OR webhook_secret_sealed IS NOT NULL),
    -- A carrier the system books with needs its account and key for the calls, and the header credential its
    -- unsigned webhooks are told apart by.
    ADD CONSTRAINT ck_carrier_connection_api_booking CHECK (connection_type <> 'API_BOOKING'
        OR (api_environment IS NOT NULL AND api_account IS NOT NULL AND api_key_sealed IS NOT NULL
            AND webhook_auth_header IS NOT NULL AND webhook_auth_value_sealed IS NOT NULL)),
    ADD CONSTRAINT ck_carrier_api_environment CHECK (api_environment IS NULL
        OR api_environment IN ('STAGING', 'PRODUCTION')),
    ADD CONSTRAINT ck_carrier_api_account CHECK (api_account IS NULL OR api_account ~ '^[!-~]{3,64}$'),
    ADD CONSTRAINT ck_carrier_webhook_auth_header CHECK (webhook_auth_header IS NULL
        OR webhook_auth_header ~ '^[A-Za-z0-9-]{1,64}$'),
    -- Sealed values only: a clear secret pasted in by hand fails here.
    ADD CONSTRAINT ck_carrier_secrets_sealed CHECK (
        (webhook_secret_sealed IS NULL OR webhook_secret_sealed ~ '^v1:[A-Za-z0-9+/]+={0,2}$')
        AND (webhook_auth_value_sealed IS NULL OR webhook_auth_value_sealed ~ '^v1:[A-Za-z0-9+/]+={0,2}$')
        AND (api_key_sealed IS NULL OR api_key_sealed ~ '^v1:[A-Za-z0-9+/]+={0,2}$')),
    -- An object of wording -> one of the six internal reasons (09 §4.3 step 3), at most 100 entries.
    -- (jsonb_path_* without _tz are immutable, so they may sit in a CHECK; a subquery may not.)
    ADD CONSTRAINT ck_carrier_failure_reason_map CHECK (jsonb_typeof(failure_reason_map) = 'object'
        AND NOT jsonb_path_exists(failure_reason_map,
            '$.* ? (@ != "CUSTOMER_ABSENT" && @ != "REFUSED" && @ != "WRONG_ADDRESS" && @ != "LOST" && @ != "DAMAGED" && @ != "OTHER")')
        AND jsonb_array_length(jsonb_path_query_array(failure_reason_map, '$.keyvalue()')) <= 100);

-- 2. Service codes ---------------------------------------------------------------------------------
CREATE TABLE fulfillment.carrier_service_code
(
    id                UUID        NOT NULL,
    carrier_id        UUID        NOT NULL,
    vehicle_type_id   UUID        NOT NULL,
    province_code     VARCHAR(20) NOT NULL,
    carrier_city_code VARCHAR(32) NOT NULL,
    service_code      VARCHAR(64) NOT NULL,

    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_carrier_service_code PRIMARY KEY (id),
    CONSTRAINT fk_carrier_service_code_carrier FOREIGN KEY (carrier_id) REFERENCES fulfillment.carrier (id),
    CONSTRAINT fk_carrier_service_code_vehicle_type FOREIGN KEY (vehicle_type_id)
        REFERENCES fulfillment.vehicle_type (id),
    -- One service per truck size per city: booking a load picks exactly one.
    CONSTRAINT uk_carrier_service_code UNIQUE (carrier_id, vehicle_type_id, province_code),
    CONSTRAINT ck_carrier_service_code_province CHECK (province_code ~ '^[!-~]{1,20}$'),
    CONSTRAINT ck_carrier_service_code_city CHECK (carrier_city_code ~ '^[!-~]{1,32}$'),
    CONSTRAINT ck_carrier_service_code_service CHECK (service_code ~ '^[!-~]{1,64}$')
);

CREATE INDEX ix_carrier_service_code_vehicle_type ON fulfillment.carrier_service_code (vehicle_type_id);

-- 3. The parcel carriers ---------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'fulfillment.carrier.status', c.id, jsonb_build_object('id', c.id, 'code', c.code, 'status', c.status)
FROM fulfillment.carrier c
WHERE c.id IN (md5('carrier:GHN')::uuid, md5('carrier:GHTK')::uuid, md5('carrier:VTP')::uuid,
               md5('carrier:JTE')::uuid)
  AND c.status = 'ACTIVE';

UPDATE fulfillment.carrier
SET status = 'INACTIVE', version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE id IN (md5('carrier:GHN')::uuid, md5('carrier:GHTK')::uuid, md5('carrier:VTP')::uuid, md5('carrier:JTE')::uuid)
  AND status = 'ACTIVE';

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), the customization templates and print areas as they were before templates got a model '
        '(V20261108000600), design statuses renamed when customers began designing (V20261108000800), the staff '
        'design workflow''s assignments, its designs left waiting for confirmation and its permission '
        '(V20261108001000), and the parcel carriers'' status before they were retired (V20261108001300). '
        'Read-only history.';
