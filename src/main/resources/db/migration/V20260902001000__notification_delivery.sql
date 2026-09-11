-- =============================================================================
-- Notification: message templates, per-user channel preferences, delivery log.
-- Module: notification   (OrderNotificationListener already reacts to events; this adds persistence)
--
-- user_id is a cross-module reference to identity (plain UUID, no FK).
-- =============================================================================

CREATE TABLE notification.template
(
    id               UUID          NOT NULL,
    code             VARCHAR(64)   NOT NULL,
    channel          VARCHAR(16)   NOT NULL,
    subject          VARCHAR(300),
    body             VARCHAR(4000) NOT NULL,
    locale           VARCHAR(10)   NOT NULL DEFAULT 'vi',
    active           BOOLEAN       NOT NULL DEFAULT TRUE,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_template PRIMARY KEY (id),
    CONSTRAINT uk_template_code_locale UNIQUE (code, locale),
    CONSTRAINT ck_template_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH'))
);

CREATE TABLE notification.preference
(
    id               UUID          NOT NULL,
    user_id          UUID          NOT NULL,
    channel          VARCHAR(16)   NOT NULL,
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_preference PRIMARY KEY (id),
    CONSTRAINT uk_preference_user_channel UNIQUE (user_id, channel),
    CONSTRAINT ck_preference_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH'))
);

CREATE TABLE notification.delivery_log
(
    id               UUID          NOT NULL,
    template_code    VARCHAR(64),
    channel          VARCHAR(16)   NOT NULL,
    recipient        VARCHAR(320)  NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    error            VARCHAR(1000),
    sent_at          TIMESTAMPTZ,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_delivery_log PRIMARY KEY (id),
    CONSTRAINT ck_delivery_log_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    CONSTRAINT ck_delivery_log_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX ix_delivery_log_recipient ON notification.delivery_log (recipient);
