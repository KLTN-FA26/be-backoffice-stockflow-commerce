-- =============================================================================
-- Assistant: the back-office AI assistant's conversations (ADR-0010, docs/assistant-design.md §2).
-- Module: assistant
--
-- A conversation belongs to the one user who opened it and is read with scope OWN. Its turns are
-- stored as the model saw them: message.content is the provider's content blocks VERBATIM, because the
-- model's thinking blocks are only valid when the history is resent unchanged (design §3.4). Every tool
-- call is kept with its arguments, outcome and the result the model received - what the assistant was
-- told is as auditable as what it answered.
--
-- message and tool_call are append-only: written once, never updated. They leave only with their
-- conversation, which the retention job deletes after stockflow.assistant.retention-days.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS assistant;


CREATE TABLE assistant.conversation
(
    id               UUID          NOT NULL,
    owner_user_id    UUID          NOT NULL,
    -- The first question, cut to fit: what the conversation list shows. Null until one is asked.
    title            VARCHAR(120),
    -- How many messages it holds; the next message's sequence.
    message_count    INTEGER       NOT NULL DEFAULT 0,
    last_activity_at TIMESTAMPTZ   NOT NULL,
    -- Set while a question is being answered, so a second one in the same conversation is refused
    -- instead of interleaving its turns with the first. A lease, not a flag: the reset runs in a
    -- finally that a killed JVM never reaches, so a claim older than the lease counts as free.
    answering_since  TIMESTAMPTZ,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_conversation PRIMARY KEY (id),
    CONSTRAINT fk_conversation_owner FOREIGN KEY (owner_user_id)
        REFERENCES identity.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversation_title CHECK (title IS NULL OR length(btrim(title)) BETWEEN 1 AND 120),
    CONSTRAINT ck_conversation_message_count CHECK (message_count >= 0),
    -- A conversation with messages has had its first question, and so a title.
    CONSTRAINT ck_conversation_titled CHECK (message_count = 0 OR title IS NOT NULL)
);

-- "My conversations, most recent first" and the retention sweep.
CREATE INDEX ix_conversation_owner_activity ON assistant.conversation (owner_user_id, last_activity_at DESC);
CREATE INDEX ix_conversation_activity ON assistant.conversation (last_activity_at);


CREATE TABLE assistant.message
(
    id                 UUID          NOT NULL,
    conversation_id    UUID          NOT NULL,
    sequence           INTEGER       NOT NULL,
    -- USER: a question. ASSISTANT: one model turn (text, tool calls, or both). TOOL_RESULTS: what the tools
    -- answered, sent back to the model as the next user turn.
    role               VARCHAR(16)   NOT NULL,
    -- What a person reads: the question, or the answer. Null for tool results and for an assistant turn
    -- that only called tools.
    text               TEXT,
    -- The turn exactly as the model saw it.
    content            JSONB         NOT NULL,
    stop_reason        VARCHAR(32),
    model              VARCHAR(64),
    input_tokens       INTEGER,
    output_tokens      INTEGER,
    cache_read_tokens  INTEGER,
    cache_write_tokens INTEGER,

    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_message PRIMARY KEY (id),
    CONSTRAINT uk_message_sequence UNIQUE (conversation_id, sequence),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES assistant.conversation (id) ON DELETE CASCADE,
    CONSTRAINT ck_message_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL_RESULTS')),
    CONSTRAINT ck_message_sequence CHECK (sequence >= 0),
    -- A question is never empty and never longer than the request allows.
    CONSTRAINT ck_message_question CHECK (role <> 'USER' OR length(btrim(text)) BETWEEN 1 AND 4000),
    CONSTRAINT ck_message_tool_results_text CHECK (role <> 'TOOL_RESULTS' OR text IS NULL),
    -- Only a model turn has a model, a stop reason and token usage.
    CONSTRAINT ck_message_model_turn CHECK ((role = 'ASSISTANT') = (model IS NOT NULL AND stop_reason IS NOT NULL)),
    CONSTRAINT ck_message_usage CHECK (role = 'ASSISTANT'
        OR (input_tokens IS NULL AND output_tokens IS NULL AND cache_read_tokens IS NULL
            AND cache_write_tokens IS NULL)),
    CONSTRAINT ck_message_tokens CHECK (coalesce(input_tokens, 0) >= 0 AND coalesce(output_tokens, 0) >= 0
        AND coalesce(cache_read_tokens, 0) >= 0 AND coalesce(cache_write_tokens, 0) >= 0)
);

-- The per-user daily cap counts today's questions.
CREATE INDEX ix_message_questions ON assistant.message (created_at) WHERE role = 'USER';


CREATE TABLE assistant.tool_call
(
    id              UUID          NOT NULL,
    conversation_id UUID          NOT NULL,
    -- The assistant turn that asked for it.
    message_id      UUID          NOT NULL,
    -- The model's id for the call, unique within its turn.
    call_id         VARCHAR(100)  NOT NULL,
    tool_name       VARCHAR(64)   NOT NULL,
    arguments       JSONB         NOT NULL,
    outcome         VARCHAR(16)   NOT NULL,
    -- The result exactly as the model received it, cut at 20,000 characters.
    result          TEXT          NOT NULL,
    duration_ms     INTEGER       NOT NULL,

    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_tool_call PRIMARY KEY (id),
    CONSTRAINT uk_tool_call_call UNIQUE (message_id, call_id),
    CONSTRAINT fk_tool_call_conversation FOREIGN KEY (conversation_id)
        REFERENCES assistant.conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_tool_call_message FOREIGN KEY (message_id)
        REFERENCES assistant.message (id) ON DELETE CASCADE,
    CONSTRAINT ck_tool_call_outcome CHECK (outcome IN ('OK', 'DENIED', 'NOT_FOUND', 'INVALID', 'FAILED')),
    CONSTRAINT ck_tool_call_name CHECK (tool_name ~ '^[A-Za-z][A-Za-z0-9_]{0,63}$'),
    CONSTRAINT ck_tool_call_duration CHECK (duration_ms >= 0),
    CONSTRAINT ck_tool_call_result CHECK (length(result) <= 20000)
);

CREATE INDEX ix_tool_call_conversation ON assistant.tool_call (conversation_id);
