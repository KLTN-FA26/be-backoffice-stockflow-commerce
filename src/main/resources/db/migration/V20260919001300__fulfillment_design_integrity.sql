ALTER TABLE fulfillment.pick
    ADD COLUMN assigned_user_id UUID,
    ADD COLUMN assigned_at TIMESTAMPTZ,
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN picked_at TIMESTAMPTZ;

ALTER TABLE fulfillment.pick
    ADD CONSTRAINT uk_fulfillment_pick_order UNIQUE (order_id);

ALTER TABLE fulfillment.pack DROP CONSTRAINT ck_pack_status;
ALTER TABLE fulfillment.pack
    ADD CONSTRAINT ck_pack_status CHECK (status IN ('PENDING', 'PACKING', 'ON_HOLD', 'PACKED')),
    ADD CONSTRAINT uk_fulfillment_pack_pick UNIQUE (pick_id);

CREATE TABLE fulfillment.design_verification
(
    id                  UUID        NOT NULL,
    pack_id             UUID        NOT NULL,
    order_id            UUID        NOT NULL,
    order_line_id       UUID        NOT NULL,
    snapshot_id         UUID        NOT NULL,
    expected_checksum   VARCHAR(64) NOT NULL,
    actual_checksum     VARCHAR(64) NOT NULL,
    result              VARCHAR(16) NOT NULL,
    verified_by         UUID        NOT NULL,
    verified_at         TIMESTAMPTZ NOT NULL,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL,
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),
    CONSTRAINT pk_fulfillment_design_verification PRIMARY KEY (id),
    CONSTRAINT fk_design_verification_pack FOREIGN KEY (pack_id) REFERENCES fulfillment.pack (id),
    CONSTRAINT ck_design_verification_result CHECK (result IN ('MATCH', 'MISMATCH'))
);

CREATE INDEX ix_design_verification_pack_time
    ON fulfillment.design_verification (pack_id, verified_at DESC);

CREATE FUNCTION fulfillment.reject_design_verification_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'fulfillment.design_verification is append-only';
END;
$$;

CREATE TRIGGER trg_design_verification_immutable
    BEFORE UPDATE OR DELETE ON fulfillment.design_verification
    FOR EACH ROW EXECUTE FUNCTION fulfillment.reject_design_verification_mutation();

-- QC needs narrowly-scoped access to package evidence; order coordinators may create package work.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission
  ON permission.code IN ('fulfillment-packages:READ', 'fulfillment-packages:CREATE',
                         'fulfillment-pick-lists:READ')
WHERE role.code = 'QC_STAFF'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.code = 'fulfillment-packages:CREATE'
WHERE role.code = 'ORDER_COORDINATOR'
ON CONFLICT (role_id, permission_id) DO NOTHING;
