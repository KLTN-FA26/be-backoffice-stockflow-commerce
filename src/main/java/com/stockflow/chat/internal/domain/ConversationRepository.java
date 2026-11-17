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
     * The staff's queue: the overdue first (a late first answer or a late resolution, BR-09), then the most recently
     * active.
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

    /**
     * The agents who looked after the customer, the most recent first, at most {@code limit}, leaving out
     * {@code except} (16 §4.1 step 2.2: "Sale đã chăm sóc khách gần nhất").
     */
    List<UUID> recentAgentsOf(UUID customerId, UUID except, int limit);

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
