package com.stockflow.chat.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link Conversation}, its contexts and its log.
 *
 * <p>{@link #save} stores the conversation only. The log is appended separately ({@link #append}), after the messages
 * of the same use case are stored: a log line can name a message, and the database checks that it exists.</p>
 */
public interface ConversationRepository extends AggregateRepository<Conversation, UUID> {

    /** Reads under the row lock and refreshes: what every write of a conversation (or of a message in it) starts with. */
    Optional<Conversation> findByIdForUpdate(UUID id);

    /**
     * The customer's newest conversation about {@code ref} that has not ended (16 §4.1: reuse an open one); with a null
     * {@code ref}, the newest open one about nothing in particular.
     */
    Optional<Conversation> findOpenAbout(UUID customerId, ContextRef ref);

    /** The customer's conversations, any status, the most recently active first; only those about {@code about}. */
    Slice<Conversation> ofCustomer(UUID customerId, ContextRef about, int page, int size);

    /**
     * The staff's queue: first those put back because their agent's account stopped working (BR-S08), then the overdue
     * (a late first answer or a late resolution, BR-09), then the most recently active.
     */
    Slice<Conversation> inbox(InboxFilter filter, Instant now, int page, int size);

    /** Every conversation about {@code ref}, any customer, the newest first, at most {@code limit}. */
    List<Conversation> about(ContextRef ref, int limit);

    List<ConversationContext> contextsOf(UUID conversationId);

    Map<UUID, List<ConversationContext>> contextsOf(Collection<UUID> conversationIds);

    Optional<ConversationContext> findContext(UUID conversationId, ContextRef ref);

    Optional<ConversationContext> findContext(UUID conversationId, UUID contextId);

    ConversationContext attach(ConversationContext context);

    void append(List<LogEntry> entries);

    /** The conversation's log, the oldest first. */
    List<LogEntry> logOf(UUID conversationId);

    /** The customer's most recently active conversation that has not ended, about anything. */
    Optional<Conversation> newestOpenOf(UUID customerId);

    /** The customer's conversations that have not ended, in id order (the order they are locked in). */
    List<UUID> openOf(UUID customerId);

    /** The conversations that have not ended and are {@code agentId}'s, in id order (the order they are locked in). */
    List<UUID> openAssignedTo(UUID agentId);

    /**
     * Per agent with a conversation that has not ended: how many, and how many of them are overdue (BR-09, the queue's
     * own predicate). One grouped statement.
     */
    List<AgentLoad> openLoad(Instant now);

    /** One agent's open conversations (the managers' assignment board, sales-owner plan §10.4). */
    record AgentLoad(UUID agentId, long open, long overdue) {
    }

    /**
     * Records that the chat applied the owner change of {@code customerId} made at {@code changedAt} (BR-S06), unless
     * it did already.
     *
     * @return true the first time; false when that change was applied before (a redelivered event)
     */
    boolean recordOwnerChangeApplied(UUID customerId, Instant changedAt, UUID toUserId, Instant now);

    /** Conversations whose first answer is late and whose supervisors were not told yet (BR-09), at most {@code limit}. */
    List<UUID> firstResponseOverdueUnalerted(Instant now, int limit);

    /**
     * Conversations not resolved by their resolution target whose supervisors were not told yet (BR-09), at most
     * {@code limit}.
     */
    List<UUID> resolutionOverdueUnalerted(Instant now, int limit);

    /** Conversations waiting on the customer since before {@code silentSince}, at most {@code limit} (16 §5). */
    List<UUID> waitingOnCustomerSince(Instant silentSince, int limit);

    /** Resolved or auto-closed conversations whose reopen window ended by {@code now}, at most {@code limit}. */
    List<UUID> pastReopenWindow(Instant now, int limit);

    /**
     * The facts the supervisor's report counts (BR-09: first response, resolution, CSAT), of the conversations opened
     * in {@code [from, to)}, of one agent or all, at most {@code limit}.
     */
    List<ConversationFacts> openedBetween(Instant from, Instant to, UUID agentId, int limit);

    /** One conversation as the report counts it. */
    record ConversationFacts(UUID id, UUID agentId, Instant createdAt, Instant firstRespondedAt,
                             Instant firstResponseAlertedAt, Instant resolvedAt, Integer csatScore) {
    }
}
