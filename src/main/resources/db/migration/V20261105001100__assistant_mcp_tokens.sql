-- Personal tokens for the assistant's MCP endpoint (design §7, ADR-0013), and the permission that opens MCP per role.
--
-- An AI client (Claude Code, Claude Desktop, Cursor) is set up once and runs for weeks; the sign-in token lives eight
-- hours and is the user's whole session. A personal token is made for one client, accepted on the MCP endpoint only,
-- shown once and stored as its SHA-256, and revoked on its own. It says only whose it is: what it may read is the
-- user's grants at each call, so a role revoked or an account locked applies without anyone revoking the token.

CREATE TABLE assistant.mcp_token
(
    id               UUID          NOT NULL,
    owner_user_id    UUID          NOT NULL,
    -- What the user called it ("Claude Code on my laptop"), to tell their tokens apart.
    name             VARCHAR(80)   NOT NULL,
    -- SHA-256 of the whole token, hex. The token itself is never stored: a copy of this table opens nothing.
    token_hash       VARCHAR(64)   NOT NULL,
    -- The token's last four characters, shown in the list so its owner recognises it.
    token_hint       VARCHAR(4)    NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    -- Moved at most once a minute: a client calling many tools does not write a row per call.
    last_used_at     TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_mcp_token PRIMARY KEY (id),
    CONSTRAINT fk_mcp_token_owner FOREIGN KEY (owner_user_id) REFERENCES identity.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT uk_mcp_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_mcp_token_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_mcp_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mcp_token_hint CHECK (token_hint ~ '^[A-Za-z0-9_-]{4}$'),
    -- Ninety days at most, as McpTokens.MAX_LIFETIME: a forgotten token stops on its own. In hours, as Java counts
    -- them: '90 days' would follow the session's time zone across a daylight-saving change.
    CONSTRAINT ck_mcp_token_lifetime CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '2160 hours'),
    CONSTRAINT ck_mcp_token_revoked CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT ck_mcp_token_used CHECK (last_used_at IS NULL OR last_used_at >= created_at)
);

-- A user's tokens, newest first, and the count of their live ones (McpTokens.MAX_LIVE).
CREATE INDEX ix_mcp_token_owner ON assistant.mcp_token (owner_user_id, created_at DESC);
-- The retention job's sweep of tokens dead for a long time.
CREATE INDEX ix_mcp_token_expires ON assistant.mcp_token (expires_at);

-- assistant-mcp:CREATE opens the MCP endpoint and the token endpoints. It grants no data: every tool still checks its
-- caller, as in the chat. Opt-in: only SYSTEM_ADMIN, which holds every permission, holds it. A token lives up to
-- ninety days and sends what its tools read to whichever model provider the user's client uses (AQ-01, AQ-17), so
-- which roles may make one is the business's decision, made in the role matrix, not a default switched on with MCP.
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'assistant-mcp:CREATE', 'assistant-mcp', 'CREATE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

-- Grants are cached per role version (ADR-0008): the role is advanced only when it gained the row.
WITH added AS (INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
               SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
               FROM identity.app_role role
               JOIN identity.permission permission ON permission.code = 'assistant-mcp:CREATE'
               WHERE role.code = 'SYSTEM_ADMIN'
               ON CONFLICT (role_id, permission_id) DO NOTHING
               RETURNING role_id)
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE id IN (SELECT role_id FROM added);
