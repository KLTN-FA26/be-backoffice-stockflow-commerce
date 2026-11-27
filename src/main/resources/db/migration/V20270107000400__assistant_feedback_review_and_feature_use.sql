-- =============================================================================
-- Assistant: reviewing the answers people marked wrong, and counting which assistant features are used
-- (docs/assistant-work-features-fe-contract.md §5; docs/assistant-design.md §7, AQ-16).
-- Module: assistant (tables), identity (permissions and grants) for assistant. Written with the module, as
-- V20270106000200 was (the repository owner's decision of 2026-10-24).
--
-- 1. assistant.answer_feedback gains who rated, by role: the roles and the warehouses of the person when they gave
--    the verdict. The System Admin turns a wrong answer into a golden-set case (src/test/resources/assistant/
--    golden-set.json), whose persona is a role and its warehouses; the rater's identity is not needed for that and
--    is not shown. NULL for verdicts given before this migration: their roles were never recorded, and a guess would
--    put the case under the wrong persona.
--
-- 2. assistant.feature_use counts how often each assistant feature was used, per feature key, role and business day
--    (Asia/Ho_Chi_Minh). Aggregates only: no user id, no content, nothing that says who asked what. FeatureUse keeps
--    the counts in memory and adds them here about once a minute, so a request never waits on this table; each flush
--    adds to the day's row (INSERT ... ON CONFLICT DO UPDATE), which also makes two instances flushing at once add up
--    instead of overwriting each other. Rows older than 400 days are deleted by the assistant's retention job: a year
--    of use with the same weeks of the year before to compare against.
--
-- 3. Two permissions, READ only:
--    - assistant-feedback:READ, the answers marked down with their questions, answers, tools and comments: other
--      people's questions, which can name customers. SYSTEM_ADMIN only (AQ-16 asked for exactly this resource).
--    - assistant-usage:READ, the counts above: SYSTEM_ADMIN and SALES_MANAGER, who decide which assistant features the
--      business keeps paying for.
--    Grants are cached per role version (ADR-0008, CLAUDE.md §5): a role is advanced in this transaction only when it
--    gained a row.
-- =============================================================================

ALTER TABLE assistant.answer_feedback
    -- The rater's roles when they rated, by code, '+'-joined in the order of the Role enum
    -- ('SALES_STAFF+SALES_MANAGER'); OTHER for a role created at run time, which has no code of its own here.
    ADD COLUMN rater_roles      VARCHAR(200),
    -- The warehouses the rater was limited to, by prefix, ','-joined; empty for a rater not limited by warehouse.
    ADD COLUMN rater_warehouses VARCHAR(500),
    ADD CONSTRAINT ck_answer_feedback_rater_roles
        CHECK (rater_roles IS NULL OR rater_roles ~ '^[A-Z][A-Z_]*(\+[A-Z][A-Z_]*)*$'),
    ADD CONSTRAINT ck_answer_feedback_rater_warehouses
        CHECK (rater_warehouses IS NULL OR rater_warehouses ~ '^([A-Za-z0-9_-]+(,[A-Za-z0-9_-]+)*)?$'),
    -- Both or neither: a rater's roles say nothing without their warehouses, and the reverse.
    ADD CONSTRAINT ck_answer_feedback_rater
        CHECK ((rater_roles IS NULL) = (rater_warehouses IS NULL));

-- The review list: one verdict, newest first, within a period.
CREATE INDEX ix_answer_feedback_verdict_given
    ON assistant.answer_feedback (verdict, (coalesce(last_modified_at, created_at)) DESC);

CREATE TABLE assistant.feature_use
(
    -- One of FeatureUse's keys: chat, today, next-steps, reply-draft, error-explanation, ...
    feature_key VARCHAR(40)  NOT NULL,
    -- The users' roles, as rater_roles above.
    role        VARCHAR(200) NOT NULL,
    -- The business day of the uses, in Asia/Ho_Chi_Minh.
    day         DATE         NOT NULL,
    uses        BIGINT       NOT NULL,

    CONSTRAINT pk_feature_use PRIMARY KEY (day, feature_key, role),
    CONSTRAINT ck_feature_use_key CHECK (feature_key ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    CONSTRAINT ck_feature_use_role CHECK (role ~ '^[A-Z][A-Z_]*(\+[A-Z][A-Z_]*)*$'),
    -- A row exists because something was used that day: never zero, never negative.
    CONSTRAINT ck_feature_use_uses CHECK (uses > 0)
);

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'assistant-feedback:READ', 'assistant-feedback', 'READ', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'assistant-usage:READ', 'assistant-usage', 'READ', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH added AS (INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
               SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
               FROM identity.app_role role
               JOIN identity.permission permission
                 ON (permission.code = 'assistant-feedback:READ' AND role.code = 'SYSTEM_ADMIN')
                 OR (permission.code = 'assistant-usage:READ' AND role.code IN ('SYSTEM_ADMIN', 'SALES_MANAGER'))
               ON CONFLICT (role_id, permission_id) DO NOTHING
               RETURNING role_id)
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE id IN (SELECT role_id FROM added);
