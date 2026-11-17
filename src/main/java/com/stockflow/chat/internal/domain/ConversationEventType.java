package com.stockflow.chat.internal.domain;

/**
 * The consequential actions of a conversation, logged with who did them and on which context (kltn-docs 16 BR-01).
 * Mapped {@code EnumType.STRING}; {@code ck_conversation_event_type} holds the same list, package 3's included (cards,
 * CSAT, merging, the SLA alerts), so that package adds no migration.
 */
public enum ConversationEventType {
    OPENED,
    /** Opened by a customer writing after the reopen window of the conversation it continues. */
    CONTINUED,
    CONTEXT_ATTACHED,
    /** No agent was routed: the conversation waits in the queue. */
    QUEUED,
    /** An agent took an unassigned conversation. */
    CLAIMED,
    /** The system or a supervisor gave an unassigned conversation to an agent. */
    ASSIGNED,
    /** The conversation went from one agent to another. */
    TRANSFERRED,
    STATUS_CHANGED,
    /** The agent's first answer (16 BR-09: first response time). */
    FIRST_RESPONSE,
    /** A staff member wrote an internal note (who, and whom it mentions). */
    INTERNAL_NOTE,
    MESSAGE_EDITED,
    MESSAGE_DELETED,
    CARD_SENT,
    CARD_ACTION,
    CARD_STATE_CHANGED,
    CSAT_SUBMITTED,
    MERGED,
    FIRST_RESPONSE_OVERDUE,
    RESOLUTION_OVERDUE
}
