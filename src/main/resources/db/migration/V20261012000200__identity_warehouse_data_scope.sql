-- =============================================================================
-- Identity: warehouse data scope (SCRUM-457, BR-SEC-002 "warehouse staff may only act on the
-- warehouses they are assigned to").
--
-- app_role.data_scope   which rows a holder of the role may reach. A user's scope is the broadest of
--                       their roles' (a warehouse clerk who is also a planner plans across
--                       warehouses). Until now RoleAuthorizationCache answered ALL for everyone.
--                       The four roles that work on a warehouse floor become WAREHOUSE.
-- user_warehouse        the warehouses a staff member is assigned to. Resolved on the server per
--                       request (ADR-0008), never put in the token: a change applies on the next
--                       request.
--
-- Nobody loses access on deploy: every account already holding a WAREHOUSE role is assigned every
-- existing warehouse here, which is exactly what they could reach before. Administrators narrow it
-- from user management afterwards. A clerk created after this migration starts with none.
--
-- The roles' versions move so cached grants (keyed by role + version) are reloaded with the scope.
-- =============================================================================

ALTER TABLE identity.app_role
    ADD COLUMN data_scope VARCHAR(16) NOT NULL DEFAULT 'ALL',
    ADD CONSTRAINT ck_app_role_data_scope CHECK (data_scope IN ('OWN', 'WAREHOUSE', 'ALL'));

UPDATE identity.app_role
SET data_scope = 'WAREHOUSE', version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'QC_STAFF', 'PRODUCTION_STAFF');

CREATE TABLE identity.user_warehouse
(
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    warehouse_id     UUID         NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_user_warehouse PRIMARY KEY (id),
    CONSTRAINT uk_user_warehouse UNIQUE (user_id, warehouse_id),
    CONSTRAINT fk_user_warehouse_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    -- Cross-schema reference as a real foreign key (ADR-0007).
    CONSTRAINT fk_user_warehouse_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id)
);

CREATE INDEX ix_user_warehouse_warehouse ON identity.user_warehouse (warehouse_id);

INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at, created_by)
SELECT gen_random_uuid(), holder.user_id, w.id, 0, NOW(), 'flyway'
FROM (SELECT DISTINCT ur.user_id
      FROM identity.user_role ur
      JOIN identity.app_role r ON r.id = ur.role_id
      WHERE r.data_scope = 'WAREHOUSE') holder
CROSS JOIN warehouse.warehouse w
ON CONFLICT (user_id, warehouse_id) DO NOTHING;
