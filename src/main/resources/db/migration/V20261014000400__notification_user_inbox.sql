-- =============================================================================
-- In-app inbox: the bell (SCRUM-147, plan G5a-3 S1a). One row per recipient per message.
--
-- Other modules post through notification.api.Inbox; the owner reads, marks read and clears it through
-- /api/v1/notifications/me. Nothing is stored here that the posting module did not generate: codes,
-- ids and a short system title, never text a user typed (no free text in notifications, hotfix #99).
--
-- dedupe_key is what keeps the bell from becoming spam: posting the same key to the same recipient
-- twice is a no-op (INSERT ... ON CONFLICT DO NOTHING in the repository, UNIQUE here for the
-- concurrent case). The key is the poster's to choose, e.g. "EXPIRING:<warehouse>:<VN date>".
--
-- Read messages are purged after 90 days (InboxPurgeJob); unread ones are kept.
--
-- Permission notification-notifications (VIEW_PAGE, READ, UPDATE) was seeded in V20260903000100 and
-- granted to CUSTOMER only. It guards /notifications/me, where every query is "recipient_id = caller",
-- so every role that signs in to a screen with a bell gets it: the 11 staff roles. SYSTEM_ADMIN
-- already holds it (V20260929000200); it is granted again below so this file is correct on its own.
-- Grants are cached per role version (ADR-0008): each role that gains rows is advanced.
-- =============================================================================

CREATE TABLE notification.user_notification (
    id           UUID          NOT NULL,
    recipient_id UUID          NOT NULL,
    category     VARCHAR(40)   NOT NULL,
    severity     VARCHAR(8)    NOT NULL,
    title        VARCHAR(200)  NOT NULL,
    body         VARCHAR(1000),
    link         VARCHAR(300),
    source_type  VARCHAR(40),
    source_id    VARCHAR(100),
    dedupe_key   VARCHAR(200)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    read_at      TIMESTAMPTZ,
    CONSTRAINT pk_user_notification PRIMARY KEY (id),
    -- A deleted account takes its messages with it: they are the person's, and nobody else reads them.
    CONSTRAINT fk_user_notification_recipient FOREIGN KEY (recipient_id)
        REFERENCES identity.app_user (id) ON DELETE CASCADE,
    CONSTRAINT uk_user_notification_dedupe UNIQUE (recipient_id, dedupe_key),
    CONSTRAINT ck_user_notification_severity CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    CONSTRAINT ck_user_notification_text CHECK (btrim(category) <> '' AND btrim(title) <> '' AND btrim(dedupe_key) <> ''),
    -- The bell turns a link into an in-app route: a path of this application, never another site.
    CONSTRAINT ck_user_notification_link CHECK (link IS NULL OR (link LIKE '/%' AND link NOT LIKE '//%')),
    CONSTRAINT ck_user_notification_read CHECK (read_at IS NULL OR read_at >= created_at)
);

-- The bell badge and the "unread" tab: newest unread first, only the rows still unread.
CREATE INDEX ix_user_notification_unread ON notification.user_notification (recipient_id, created_at DESC, id DESC)
    WHERE read_at IS NULL;
-- The full list.
CREATE INDEX ix_user_notification_recipient ON notification.user_notification (recipient_id, created_at DESC, id DESC);
-- The purge: read messages older than the retention.
CREATE INDEX ix_user_notification_read_at ON notification.user_notification (read_at)
    WHERE read_at IS NOT NULL;

COMMENT ON TABLE notification.user_notification IS
    'In-app inbox, one row per recipient. Posted through notification.api.Inbox; read only by its recipient.';
COMMENT ON COLUMN notification.user_notification.dedupe_key IS
    'Chosen by the poster; the same key for the same recipient is posted once.';

-- ---- grants: the staff roles --------------------------------------------------
WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
    FROM identity.app_role role
    JOIN identity.permission permission
      ON permission.resource = 'notification-notifications'
     AND permission.action IN ('VIEW_PAGE', 'READ', 'UPDATE')
    WHERE role.code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'INVENTORY_PLANNER', 'QC_STAFF', 'SALES_STAFF',
                        'ORDER_COORDINATOR', 'ECOMMERCE_ADMIN', 'PROCUREMENT_STAFF', 'ACCOUNTANT',
                        'PRODUCTION_STAFF', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE id IN (SELECT role_id FROM granted);
