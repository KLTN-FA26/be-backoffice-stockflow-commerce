-- =============================================================================
-- The chat follows the customer's Sales owner (sales-owner plan 2026-11-28 §2.3, BR-S05, S06, S08; kltn-docs 16 §4.1,
-- §5, BR-12..BR-14). Module: chat
--
--   chat.conversation.requeued_at   a conversation whose agent's account was locked or disabled goes back to the queue,
--                                   flagged: it is shown first, before the overdue ones (16 §5, BR-14). Set only by that
--                                   move, cleared as soon as someone has the conversation again.
--   chat.check_conversation_lifecycle()  16 §5 now has the move back to the queue from every open state with an agent
--                                   (ASSIGNED, ACTIVE, WAITING_ON_CUSTOMER, WAITING_ON_INTERNAL, ESCALATED, REOPENED),
--                                   and only flagged: no other path empties an open conversation of its agent.
--   chat.sales_owner_change_applied the owner changes the chat has applied (CustomerSalesOwnerChanged, keyed by the
--                                   customer and the change's time): a redelivered event finds its row and does nothing,
--                                   so the open conversations are not moved twice nor the customer told twice.
-- =============================================================================

ALTER TABLE chat.conversation
    ADD COLUMN requeued_at TIMESTAMPTZ,
    -- The flag is a place in the queue: once someone has the conversation it means nothing (Conversation#giveTo).
    ADD CONSTRAINT ck_conversation_requeued CHECK (requeued_at IS NULL OR status = 'QUEUED');

COMMENT ON COLUMN chat.conversation.requeued_at IS
    'BR-S08 / 16 BR-14: when the conversation went back to the queue because its agent''s account was locked or '
    'disabled - shown first in the queue. Null otherwise, and again once someone has it.';

-- 16 §5, as V20261124000300 wrote it, plus the move back to the queue (agent locked or disabled): from every open state
-- that has an agent, flagged, and only flagged. Without the flag the message is the old one, so the sweep of
-- tools/db/qa/97_chat.sql still finds its 18 moves; tools/db/qa/99_chat_sales_owner.sql sweeps the flagged six.
CREATE OR REPLACE FUNCTION chat.check_conversation_lifecycle() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('NEW', 'QUEUED', 'ASSIGNED') THEN
            RAISE EXCEPTION 'conversation %: a conversation starts NEW, QUEUED or ASSIGNED, not % (16 §5)', NEW.id,
                NEW.status USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.continued_from_id IS DISTINCT FROM OLD.continued_from_id
        OR NEW.opened_by IS DISTINCT FROM OLD.opened_by THEN
        RAISE EXCEPTION 'conversation %: a conversation keeps its customer, who opened it and what it continues', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.last_message_seq < OLD.last_message_seq THEN
        RAISE EXCEPTION 'conversation %: the message sequence does not go back (% -> %)', OLD.id, OLD.last_message_seq,
            NEW.last_message_seq USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = OLD.status THEN
        IF OLD.status = 'CLOSED' AND NEW.assigned_agent_id IS DISTINCT FROM OLD.assigned_agent_id THEN
            RAISE EXCEPTION 'conversation %: a closed conversation is not handed to anyone', OLD.id
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'NEW' AND NEW.status IN ('QUEUED', 'ASSIGNED'))
         OR (OLD.status = 'QUEUED' AND NEW.status = 'ASSIGNED')
         OR (OLD.status = 'ASSIGNED' AND NEW.status = 'ACTIVE')
         OR (OLD.status = 'ACTIVE' AND NEW.status IN ('WAITING_ON_CUSTOMER', 'WAITING_ON_INTERNAL', 'ESCALATED',
                                                      'RESOLVED'))
         OR (OLD.status = 'WAITING_ON_CUSTOMER' AND NEW.status IN ('ACTIVE', 'AUTO_CLOSED'))
         OR (OLD.status = 'WAITING_ON_INTERNAL' AND NEW.status = 'ACTIVE')
         OR (OLD.status = 'ESCALATED' AND NEW.status IN ('ACTIVE', 'RESOLVED'))
         OR (OLD.status = 'RESOLVED' AND NEW.status IN ('REOPENED', 'CLOSED'))
         OR (OLD.status = 'REOPENED' AND NEW.status = 'ACTIVE')
         OR (OLD.status = 'AUTO_CLOSED' AND NEW.status IN ('REOPENED', 'CLOSED'))
         -- BR-S08: the agent's account stopped working; back to the queue, flagged.
         OR (OLD.status IN ('ASSIGNED', 'ACTIVE', 'WAITING_ON_CUSTOMER', 'WAITING_ON_INTERNAL', 'ESCALATED',
                            'REOPENED')
             AND NEW.status = 'QUEUED' AND OLD.assigned_agent_id IS NOT NULL AND NEW.requeued_at IS NOT NULL)) THEN
        RAISE EXCEPTION 'conversation %: % -> % is not a transition (16 §5)', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TABLE chat.sales_owner_change_applied
(
    customer_id UUID        NOT NULL,
    -- CustomerSalesOwnerChanged.at: the change's key together with the customer.
    changed_at  TIMESTAMPTZ NOT NULL,
    to_user_id  UUID        NOT NULL,
    applied_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_sales_owner_change_applied PRIMARY KEY (customer_id, changed_at),
    CONSTRAINT fk_sales_owner_change_applied_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sales_owner_change_applied_to_user FOREIGN KEY (to_user_id) REFERENCES identity.app_user (id)
);

CREATE INDEX ix_sales_owner_change_applied_to_user ON chat.sales_owner_change_applied (to_user_id);

COMMENT ON TABLE chat.sales_owner_change_applied IS
    'BR-S06 / 16 BR-14: the customers'' owner changes whose open conversations the chat moved (and whose customer it '
    'told); one row per (customer, change time), so a redelivered CustomerSalesOwnerChanged changes nothing.';
