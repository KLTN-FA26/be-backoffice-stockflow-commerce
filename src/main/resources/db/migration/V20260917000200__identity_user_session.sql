-- Version note: numbered just above develop's latest (V20260917000100) on purpose. The warehouse (#28),
-- media (#29) and customer (#30) PRs still open carry higher versions; a lower number here would make
-- Flyway refuse them, without out-of-order, on any database that already applied this one.
--
-- Server-side record of every signed-in session, so a token can be ended before it expires.
-- Module: identity   Aggregate: none (a child of app_user, written through IdentityService)
--
-- A JWT is a snapshot: alone, nothing can end it before `exp`. Each login now inserts a row here and
-- puts its id in the token (claims `sid` and `jti`); the resource server accepts the token only while
-- the row exists, is not revoked and has not expired. Logout, "log out my other devices", a password
-- change and a role change all work by setting revoked_at.
--
-- Rows outlive their token by a day for support ("was I signed in from there?") and are purged by
-- SessionPurgeJob. Tokens issued before this migration carry no `sid` and are accepted until they
-- expire on their own.
CREATE TABLE identity.user_session
(
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    issued_at        TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    revoked_at       TIMESTAMPTZ,
    revoked_reason   VARCHAR(32),
    client_address   VARCHAR(45),
    user_agent       VARCHAR(255),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_user_session PRIMARY KEY (id),
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_user_session_lifetime CHECK (expires_at > issued_at),
    -- Stated twice (CLAUDE.md): a revoked session always says why, a live one never does.
    CONSTRAINT ck_user_session_revocation CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT ck_user_session_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('LOGOUT', 'LOGOUT_OTHERS', 'PASSWORD_CHANGED', 'ROLE_CHANGED'))
);

-- "The sessions of this user that are still usable": the list screen and every bulk revoke.
CREATE INDEX ix_user_session_user_live ON identity.user_session (user_id) WHERE revoked_at IS NULL;
-- The purge job.
CREATE INDEX ix_user_session_expires ON identity.user_session (expires_at);

COMMENT ON TABLE identity.user_session IS
    'One row per issued token. The token''s sid/jti claim is this id; revoked_at ends it early.';
