-- =============================================================================
-- Identity: why an administrator locked, unlocked, disabled or enabled an account (SCRUM-474 LOCK-REASON,
-- owner decision 29/10/2026). Until now the audit trail showed "an account changed status" and nothing
-- else: no reason, and lock and disable looked the same. Blocking a customer already requires a reason
-- (17/10); this brings the staff accounts to the same rule.
--
-- identity.user_status_change  one row per administrator action on an account's status, with the reason
--   (5-500 characters after trimming, required by the CHECK as the second statement of the service rule),
--   the statuses before and after, and who did it. The automatic lock after too many wrong passwords is
--   not an administrator's decision and writes no row. Rows are never updated or deleted
--   (platform.append_only, V20260928002400). Accounts that were locked or disabled before this migration
--   simply have no history yet; nothing is invented for them.
--
-- No new permission: reading the history needs identity-users:READ, and the four actions keep
-- identity-users:UPDATE, so no grant and no app_role.version change is needed.
-- =============================================================================

CREATE TABLE identity.user_status_change
(
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    action           VARCHAR(16)  NOT NULL,
    from_status      VARCHAR(32)  NOT NULL,
    to_status        VARCHAR(32)  NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    -- The administrator. The username is copied on purpose: an audit record stays true to the moment it
    -- describes (same reasoning as platform.audit_log.actor_name). actor_id is null only when no caller exists
    -- (security switched off, a script).
    actor_id         UUID,
    actor_username   VARCHAR(320) NOT NULL,
    changed_at       TIMESTAMPTZ  NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_user_status_change PRIMARY KEY (id),
    CONSTRAINT fk_user_status_change_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_status_change_actor FOREIGN KEY (actor_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_user_status_change_action CHECK (action IN ('LOCK', 'UNLOCK', 'DISABLE', 'ENABLE')),
    CONSTRAINT ck_user_status_change_from CHECK (from_status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_user_status_change_to CHECK (to_status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_user_status_change_reason CHECK (char_length(btrim(reason)) BETWEEN 5 AND 500
        AND reason = btrim(reason))
);

-- The history of one account, newest first, and "the latest change of each account on this page".
CREATE INDEX ix_user_status_change_user ON identity.user_status_change (user_id, changed_at DESC, id DESC);

CREATE TRIGGER tg_user_status_change_append_only
    BEFORE UPDATE OR DELETE ON identity.user_status_change
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('identity.app_user', 'user_id');

COMMENT ON TABLE identity.user_status_change IS
    'Administrator lock / unlock / disable / enable of an account, with who and why. Append-only.';
