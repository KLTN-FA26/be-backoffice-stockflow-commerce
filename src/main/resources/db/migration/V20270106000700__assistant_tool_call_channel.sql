-- =============================================================================
-- Assistant: tool runs outside a conversation (docs/assistant-design.md §5.5, §7, §9).
-- Module: assistant
--
-- An MCP client's tools/call and the "today" card's reads ran the same tools under the same guards as the chat,
-- but were written to the log only: a tool_call needed a conversation and a message. Now every run is a row, with
-- the channel it came through and the user it ran for, so one table answers what the assistant read, for whom, and
-- how. A chat run still belongs to its conversation and its assistant turn; an MCP or today run belongs to none; a
-- background (PROACTIVE) run may belong to the conversation it opened for its user, or to none.
--
-- user_id is backfilled from the conversation's owner for the rows already there, then required. Standalone rows
-- leave with the retention job, by age, as conversations do.
-- =============================================================================

ALTER TABLE assistant.tool_call
    ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'CHAT',
    ADD COLUMN user_id UUID;

UPDATE assistant.tool_call t
SET user_id = c.owner_user_id
FROM assistant.conversation c
WHERE c.id = t.conversation_id;

ALTER TABLE assistant.tool_call
    ALTER COLUMN user_id SET NOT NULL,
    ALTER COLUMN channel DROP DEFAULT,
    ALTER COLUMN conversation_id DROP NOT NULL,
    ALTER COLUMN message_id DROP NOT NULL,
    ADD CONSTRAINT fk_tool_call_user FOREIGN KEY (user_id)
        REFERENCES identity.app_user (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_tool_call_channel CHECK (channel IN ('CHAT', 'MCP', 'TODAY', 'PROACTIVE')),
    -- A conversation and its turn come together or not at all.
    ADD CONSTRAINT ck_tool_call_turn CHECK ((conversation_id IS NULL) = (message_id IS NULL)),
    -- A chat run has its turn; an MCP or today run has none.
    ADD CONSTRAINT ck_tool_call_channel_turn CHECK (
        (channel = 'CHAT' AND conversation_id IS NOT NULL)
        OR (channel IN ('MCP', 'TODAY') AND conversation_id IS NULL)
        OR channel = 'PROACTIVE');

-- The retention job's delete of standalone rows, oldest first.
CREATE INDEX ix_tool_call_standalone ON assistant.tool_call (created_at) WHERE conversation_id IS NULL;
-- What one user's tools read, newest first: the audit question this table answers.
CREATE INDEX ix_tool_call_user ON assistant.tool_call (user_id, created_at);
