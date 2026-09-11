-- =============================================================================
-- The platform schema: cross-cutting infrastructure tables.
--
-- These belong to no business module. Idempotency, audit and the scheduler lock are applied to
-- every module's endpoints and jobs by shared infrastructure, so putting them in inventory's or
-- order's schema would give that module ownership of something it does not own - and would make
-- "which module writes this table" unanswerable the first time somebody asked.
--
-- The rule from V20260901000100 still holds: no business module reads or writes these tables
-- directly. They are touched only by com.stockflow.common.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS platform;


-- -----------------------------------------------------------------------------
-- HTTP idempotency.
--
-- One row per (Idempotency-Key, caller). The UNIQUE constraint below is not a safety net - it IS
-- the mechanism. Two duplicate requests racing on two threads both find no row and both try to
-- insert; the database is the only thing that can let exactly one through. Any application-level
-- "check then insert" loses that race under precisely the load this protects against.
-- -----------------------------------------------------------------------------
CREATE TABLE platform.idempotency_record
(
    id                  UUID         NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    -- The user id, or the literal 'anonymous'. Keys are namespaced per caller because a key is
    -- often derived from a business reference (an order number), and two clients must not be able
    -- to read each other's stored responses by guessing one.
    caller              VARCHAR(64)  NOT NULL,
    -- SHA-256 hex of method + path + body. Detects a key reused for a DIFFERENT request, which
    -- must be refused (422) rather than answered with the first request's response - replaying it
    -- would report success for work that never happened.
    -- VARCHAR, not CHAR: Hibernate maps a String to varchar, and ddl-auto=validate reports a
    -- bpchar column as a type mismatch. The same trap cost the ordering migration a fix.
    request_fingerprint VARCHAR(64)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    response_status     INTEGER,
    -- TEXT, not a bounded VARCHAR: an API response has no natural length limit and a truncated
    -- body would replay as invalid JSON. Retention is what bounds the table, not the column.
    response_body       TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    completed_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_idempotency_record PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_key_caller UNIQUE (idempotency_key, caller),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    -- A completed record must carry the response it is meant to replay; an in-progress one must
    -- not pretend to have one.
    CONSTRAINT ck_idempotency_completed CHECK (
        (status = 'IN_PROGRESS' AND completed_at IS NULL AND response_status IS NULL)
     OR (status = 'COMPLETED'   AND completed_at IS NOT NULL AND response_status IS NOT NULL))
);

-- Drives the hourly purge.
CREATE INDEX ix_idempotency_expires ON platform.idempotency_record (expires_at);

COMMENT ON TABLE platform.idempotency_record IS
    'HTTP idempotency keys and the responses to replay. Written only by IdempotencyFilter.';


-- -----------------------------------------------------------------------------
-- Audit trail (BRD 3.19.3).
--
-- Append-only. There is no update path in the application and none should be added: an audit entry
-- that can be edited is not an audit entry. The only writes are inserts and the retention purge.
-- -----------------------------------------------------------------------------
CREATE TABLE platform.audit_log
(
    id                UUID         NOT NULL,
    -- Null when the system acted: a scheduled job, a migration, an event listener.
    actor_id          UUID,
    -- Denormalised on purpose. Joining to the user table later would show today's name, and a
    -- renamed or deleted user would make the entry unreadable. An audit record must stay true to
    -- the moment it describes.
    actor_name        VARCHAR(100),
    action            VARCHAR(20)  NOT NULL,
    resource_type     VARCHAR(64)  NOT NULL,
    resource_id       VARCHAR(128),
    outcome           VARCHAR(10)  NOT NULL,
    -- Ties the entry back to the request's log lines and trace.
    correlation_id    VARCHAR(64),
    client_address    VARCHAR(45),                      -- 45 = longest IPv6 textual form
    details           TEXT,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    -- Stored, not recomputed at purge time. If the classification of an action ever changes, rows
    -- already written keep the retention they were created with - otherwise a reclassification
    -- could delete last year's login records, which is exactly the data an investigation needs.
    security_relevant BOOLEAN      NOT NULL,

    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_audit_action CHECK (action IN
        ('CREATE', 'UPDATE', 'DELETE', 'APPROVE', 'REJECT', 'EXPORT',
         'TRANSITION', 'LOGIN', 'LOGOUT', 'GRANT', 'REVOKE'))
);

-- "What did this user do" - the first question in any investigation.
CREATE INDEX ix_audit_actor_time ON platform.audit_log (actor_id, occurred_at DESC);
-- "Who touched this order" - the second one.
CREATE INDEX ix_audit_resource ON platform.audit_log (resource_type, resource_id);
-- Drives the retention purge and every time-range report.
CREATE INDEX ix_audit_occurred ON platform.audit_log (occurred_at);
-- Lets a failing log line be joined to the actions it produced.
CREATE INDEX ix_audit_correlation ON platform.audit_log (correlation_id)
    WHERE correlation_id IS NOT NULL;

COMMENT ON TABLE platform.audit_log IS
    'Append-only record of business actions. Never updated; purged on retention only.';


-- -----------------------------------------------------------------------------
-- ShedLock: stops a @Scheduled job running on more than one instance.
--
-- Column names and types are ShedLock's - do not rename them. The table is here rather than in a
-- module schema for the same reason as the others: the scheduler is shared infrastructure.
--
-- Postgres rather than Redis deliberately: Redis runs with allkeys-lru and no persistence, so
-- under memory pressure it may evict a lock key and let two instances run the same job. See
-- SchedulerLockConfig.
-- -----------------------------------------------------------------------------
CREATE TABLE platform.shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,

    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE platform.shedlock IS
    'Owned by ShedLock. One row per scheduled job name; do not write to it from application code.';
