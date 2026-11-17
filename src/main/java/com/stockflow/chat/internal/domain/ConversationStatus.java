package com.stockflow.chat.internal.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The eleven states of a conversation and the moves between them, kltn-docs 16 §5. Mapped {@code EnumType.STRING}.
 *
 * <p>The move table is stated here and again in {@code chat.check_conversation_lifecycle()} (V20261124000200), the
 * database's last word on it; the two must agree, and {@code ConversationStatusTest} sweeps every pair. Who may make a
 * move - the customer, the agent, the supervisor, the system - is {@link Conversation}'s business, not this table's.</p>
 */
public enum ConversationStatus {

    /** Just opened; no agent yet. */
    NEW,
    /** Waiting for an agent: none was routed, or out of hours, or the supervisor is to assign one. */
    QUEUED,
    /** An agent has it and has not answered yet. */
    ASSIGNED,
    /** Both sides are talking. */
    ACTIVE,
    /** The agent waits for the customer (a design to resubmit, a quote to accept, a contract to send back...). */
    WAITING_ON_CUSTOMER,
    /** The agent waits for another team (accounting, the warehouse, the Sales Manager). */
    WAITING_ON_INTERNAL,
    /** Beyond the agent's authority (a discount over their limit, compensation): with the chat supervisor. */
    ESCALATED,
    /** Dealt with. The customer may still write again until the reopen window ends. */
    RESOLVED,
    /** The customer wrote again after it was resolved or auto-closed. */
    REOPENED,
    /** Closed because the customer stayed silent too long; reopens like a resolved one. */
    AUTO_CLOSED,
    /** Closed for good: the reopen window passed. A later word of the customer is a new conversation. */
    CLOSED;

    private static final Map<ConversationStatus, Set<ConversationStatus>> MOVES = Map.ofEntries(
            Map.entry(NEW, EnumSet.of(QUEUED, ASSIGNED)),
            Map.entry(QUEUED, EnumSet.of(ASSIGNED)),
            Map.entry(ASSIGNED, EnumSet.of(ACTIVE)),
            Map.entry(ACTIVE, EnumSet.of(WAITING_ON_CUSTOMER, WAITING_ON_INTERNAL, ESCALATED, RESOLVED)),
            Map.entry(WAITING_ON_CUSTOMER, EnumSet.of(ACTIVE, AUTO_CLOSED)),
            Map.entry(WAITING_ON_INTERNAL, EnumSet.of(ACTIVE)),
            Map.entry(ESCALATED, EnumSet.of(ACTIVE, RESOLVED)),
            Map.entry(RESOLVED, EnumSet.of(REOPENED, CLOSED)),
            Map.entry(REOPENED, EnumSet.of(ACTIVE)),
            Map.entry(AUTO_CLOSED, EnumSet.of(REOPENED, CLOSED)),
            Map.entry(CLOSED, EnumSet.noneOf(ConversationStatus.class)));

    /** The states this one may move to (16 §5); empty for CLOSED. */
    public Set<ConversationStatus> next() {
        return Set.copyOf(MOVES.get(this));
    }

    public boolean canMoveTo(ConversationStatus target) {
        return target != null && MOVES.get(this).contains(target);
    }

    /** Resolved, auto-closed or closed: nobody is working on it. */
    public boolean isEnded() {
        return this == RESOLVED || this == AUTO_CLOSED || this == CLOSED;
    }

    /** The states that always have an agent on them ({@code ck_conversation_agent}). */
    public boolean needsAgent() {
        return this == ASSIGNED || this == ACTIVE || this == WAITING_ON_CUSTOMER || this == WAITING_ON_INTERNAL
                || this == ESCALATED;
    }

    /** The states a conversation can be born in ({@code check_conversation_lifecycle} on INSERT). */
    public boolean isInitial() {
        return this == NEW || this == QUEUED || this == ASSIGNED;
    }
}
