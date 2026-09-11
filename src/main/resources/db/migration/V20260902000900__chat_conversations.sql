-- =============================================================================
-- Chat: customer conversations and their messages.
-- Module: chat   ERD: chat__*
--
-- chat-service owns these. customer_id / order_id / assigned_agent_id are cross-module references
-- (plain UUID, no FK). message->conversation stays within the chat schema.
-- =============================================================================

CREATE TABLE chat.conversation
(
    id                 UUID         NOT NULL,
    customer_id        UUID         NOT NULL,
    order_id           UUID,
    subject            VARCHAR(300),
    status             VARCHAR(32)  NOT NULL,
    assigned_agent_id  UUID,

    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_conversation PRIMARY KEY (id),
    CONSTRAINT ck_conversation_status CHECK (status IN ('OPEN', 'ASSIGNED', 'CLOSED'))
);

CREATE INDEX ix_conversation_customer ON chat.conversation (customer_id);

CREATE TABLE chat.message
(
    id                 UUID         NOT NULL,
    conversation_id    UUID         NOT NULL,
    sender_type        VARCHAR(32)  NOT NULL,
    sender_id          UUID,
    body               VARCHAR(4000) NOT NULL,
    sent_at            TIMESTAMPTZ  NOT NULL,

    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_message PRIMARY KEY (id),
    CONSTRAINT fk_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES chat.conversation (id) ON DELETE CASCADE,
    CONSTRAINT ck_message_sender_type CHECK (sender_type IN ('CUSTOMER', 'AGENT', 'SYSTEM'))
);

CREATE INDEX ix_message_conversation ON chat.message (conversation_id);
