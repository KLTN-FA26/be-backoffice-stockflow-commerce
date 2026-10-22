-- =============================================================================
-- The load estimate of full-truckload load planning (SCRUM-287 PR EST; design v2 §2-§5, lead decisions LP2-1 to
-- LP2-5, LP2-10). Module: fulfillment (table), identity (grants)
--
--   load_estimate   an ADVISORY 3D load plan worked out from a released sales order's lines before anything is
--                   packed (LP2-1): each line's cartons come from the item master data through the packaging-spec
--                   resolver (default shipping unit -> largest packing unit -> item box x package_count -> item unit),
--                   and the placement solver lays them in the chosen vehicle types. It never confirms a plan and
--                   never books a vehicle: kltn-docs 09 BR-09 allows only Packed parcels in a real load plan
--                   (PR D), which is planned again from the sealed parcels.
--
--   source_type / source_id   what was estimated: ORDER (a sales order) now; TRANSFER (a transfer order, docs 10) is
--                             reserved for PR T and refused by the API until then. No foreign key: the id names a row of
--                             ordering or of inventory depending on the type.
--   warehouse_id              the order's warehouse when it was estimated: the data scope (BR-SEC-002) of the row.
--   vehicle_type_ids          JSONB array of the vehicle types the estimate was allowed to use.
--   mode                      the selection mode, SINGLE_TYPE ("Một loại") or FEWEST_VEHICLES ("Ít xe nhất").
--   status                    CURRENT | SUPERSEDED. At most one CURRENT row per source (uk_load_estimate_current):
--                             estimating again supersedes the previous estimate in the same transaction, and the old
--                             rows stay for history.
--   lines                     JSONB: per order line, the resolver's rule level, unit, factor, carton count, partial
--                             carton, carton size (mm) and weight, rotatable flag and kit parts.
--   layout                    JSONB: {vehicles: [Layout], unplaced: [...], meta: {...}} - the Layout shape the 3D viewer
--                             renders (FE #71), shared with the real load plans of PR D.
--   warnings                  JSONB: what the resolver skipped or noticed per line.
-- =============================================================================

CREATE TABLE fulfillment.load_estimate
(
    id               UUID         NOT NULL,
    source_type      VARCHAR(16)  NOT NULL,
    source_id        UUID         NOT NULL,
    warehouse_id     UUID,
    vehicle_type_ids JSONB        NOT NULL DEFAULT '[]'::jsonb,
    mode             VARCHAR(20)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    lines            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    layout           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    warnings         JSONB        NOT NULL DEFAULT '[]'::jsonb,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_load_estimate PRIMARY KEY (id),
    CONSTRAINT ck_load_estimate_source_type CHECK (source_type IN ('ORDER', 'TRANSFER')),
    CONSTRAINT ck_load_estimate_mode CHECK (mode IN ('SINGLE_TYPE', 'FEWEST_VEHICLES')),
    CONSTRAINT ck_load_estimate_status CHECK (status IN ('CURRENT', 'SUPERSEDED')),
    CONSTRAINT ck_load_estimate_vehicle_type_ids CHECK (jsonb_typeof(vehicle_type_ids) = 'array'),
    CONSTRAINT ck_load_estimate_lines CHECK (jsonb_typeof(lines) = 'array'),
    CONSTRAINT ck_load_estimate_layout CHECK (jsonb_typeof(layout) = 'object'),
    CONSTRAINT ck_load_estimate_warnings CHECK (jsonb_typeof(warnings) = 'array')
);

-- One current estimate per source; superseded ones are history.
CREATE UNIQUE INDEX uk_load_estimate_current ON fulfillment.load_estimate (source_type, source_id)
    WHERE status = 'CURRENT';

CREATE INDEX ix_load_estimate_source ON fulfillment.load_estimate (source_type, source_id, created_at DESC);

-- ----------------------------------------------------------------------------- permissions
-- Grants (permission codes, never role names; ADR-0008):
--   fulfillment-load-estimates:CREATE, :READ   order coordinator (chooses the delivery mode at release), warehouse
--                                              manager, SYSTEM_ADMIN
-- There is no dispatcher role in the role catalogue (dispatch staff are WAREHOUSE_STAFF, who load what was really
-- packed, PR D/E): an administrator may tick these for another role in the matrix screen. A role that does not exist in
-- a database is skipped (the joins), exactly as in V20261022000200.
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'fulfillment-load-estimates:' || a.action, 'fulfillment-load-estimates', a.action, 0, NOW(),
       'flyway'
FROM unnest(ARRAY['CREATE', 'READ']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('ORDER_COORDINATOR', 'CREATE,READ'),
          ('WAREHOUSE_MANAGER', 'CREATE,READ')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'fulfillment-load-estimates'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
  AND permission.resource = 'fulfillment-load-estimates'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ADR-0008: role-keyed permission caches must change generation in this same transaction.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('ORDER_COORDINATOR', 'WAREHOUSE_MANAGER', 'SYSTEM_ADMIN');
