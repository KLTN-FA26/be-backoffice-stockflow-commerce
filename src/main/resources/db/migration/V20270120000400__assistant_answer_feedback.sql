-- =============================================================================
-- Assistant: what a person thought of an answer (docs/assistant-design.md §7, FEAT-8 of the 28-29/10 review).
-- Module: assistant
--
-- One verdict per answer, up or down, with an optional reason in the user's words; given again, it replaces the
-- first. Written only by the owner of the conversation, through
-- POST /api/v1/assistant/conversations/{conversationId}/messages/{messageId}/feedback, and read back with the
-- transcript. The message it rates stays append-only: the verdict is a row of its own.
--
-- It leaves with its answer: the foreign key to assistant.message cascades, and a message cascades with its
-- conversation, so the retention job's delete of an idle conversation takes its feedback too (design §5.5, AQ-03).
-- Nothing else reads it yet: whether an administrator may read other staff's questions and verdicts, and whether a
-- verdict may outlive its conversation, are AQ-16, undecided; until then neither is possible.
--
-- What the database cannot check, the service does before it writes: the message is an answer (an ASSISTANT turn
-- holding text) of a conversation the caller owns, and the caller is that owner.
-- =============================================================================

CREATE TABLE assistant.answer_feedback
(
    id               UUID          NOT NULL,
    -- The answer rated.
    message_id       UUID          NOT NULL,
    -- Who rated it: the owner of the conversation, the only one who may.
    owner_user_id    UUID          NOT NULL,
    verdict          VARCHAR(8)    NOT NULL,
    -- Typed by the user, optional; stripped, never blank.
    reason           VARCHAR(500),

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_answer_feedback PRIMARY KEY (id),
    -- One verdict per answer; the index behind it also serves the cascade from assistant.message.
    CONSTRAINT uk_answer_feedback_message UNIQUE (message_id),
    CONSTRAINT fk_answer_feedback_message FOREIGN KEY (message_id)
        REFERENCES assistant.message (id) ON DELETE CASCADE,
    CONSTRAINT fk_answer_feedback_owner FOREIGN KEY (owner_user_id)
        REFERENCES identity.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_answer_feedback_verdict CHECK (verdict IN ('UP', 'DOWN')),
    CONSTRAINT ck_answer_feedback_reason CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500)
);
