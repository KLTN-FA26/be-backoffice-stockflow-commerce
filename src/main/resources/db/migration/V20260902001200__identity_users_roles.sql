-- =============================================================================
-- Identity: users, roles, permissions and their join tables.
-- Module: identity   ERD: id__*
--
-- identity-service owns these; every other module learns permissions via the JWT, not by reading
-- these tables. Table names are app_user / app_role because USER and ROLE are reserved words in
-- PostgreSQL. All FKs stay within the identity schema.
-- =============================================================================

CREATE TABLE identity.app_user
(
    id               UUID          NOT NULL,
    username         VARCHAR(100)  NOT NULL,
    email            VARCHAR(320)  NOT NULL,
    password_hash    VARCHAR(200)  NOT NULL,
    full_name        VARCHAR(200),
    status           VARCHAR(32)   NOT NULL,
    last_login_at    TIMESTAMPTZ,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED'))
);

CREATE TABLE identity.app_role
(
    id               UUID          NOT NULL,
    code             VARCHAR(64)   NOT NULL,
    name             VARCHAR(200)  NOT NULL,
    description      VARCHAR(1000),

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_app_role PRIMARY KEY (id),
    CONSTRAINT uk_app_role_code UNIQUE (code)
);

CREATE TABLE identity.permission
(
    id               UUID          NOT NULL,
    code             VARCHAR(128)  NOT NULL,
    resource         VARCHAR(64),
    action           VARCHAR(64),
    description      VARCHAR(500),

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_permission PRIMARY KEY (id),
    CONSTRAINT uk_permission_code UNIQUE (code)
);

CREATE TABLE identity.user_role
(
    id               UUID          NOT NULL,
    user_id          UUID          NOT NULL,
    role_id          UUID          NOT NULL,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_user_role PRIMARY KEY (id),
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES identity.app_role (id) ON DELETE CASCADE
);

CREATE TABLE identity.role_permission
(
    id               UUID          NOT NULL,
    role_id          UUID          NOT NULL,
    permission_id    UUID          NOT NULL,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_role_permission PRIMARY KEY (id),
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES identity.app_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES identity.permission (id) ON DELETE CASCADE
);
