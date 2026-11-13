package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationContext;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.chat.internal.domain.ReadMarkers;
import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;

/**
 * The steps every write to a conversation shares, so each use case states only what is its own: open a conversation
 * (contexts, routing, log), post a message into a locked one (the retry check, numbering, storing, the log, the
 * sender's read marker, the pushes), save a change and tell the clients.
 *
 * <p>Called inside the use case's transaction, never on its own. The order of the stores matters and is kept here:
 * the conversation, then the message, then the log - a log line names the message, and the database checks it exists.
 * </p>
 */
@Component
public class ConversationWriter {

    /** A message stored, or the earlier attempt of a retried send ({@code replayed}). */
    public record Posted(ChatMessage message, boolean replayed) {
    }

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ReadMarkers markers;
    private final ConversationRouter router;
    private final ChatBroadcaster broadcaster;
    private final ChatViews views;
    private final CustomerService customers;
    private final ChatProperties properties;
    private final Clock clock;

    public ConversationWriter(ConversationRepository conversations, ChatMessageRepository messages,
                              ReadMarkers markers, ConversationRouter router, ChatBroadcaster broadcaster,
                              ChatViews views, CustomerService customers, ChatProperties properties, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.markers = markers;
        this.router = router;
        this.broadcaster = broadcaster;
        this.views = views;
        this.customers = customers;
        this.properties = properties;
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /** The conversation under its row lock; {@code CHAT_CONVERSATION_NOT_FOUND} when there is none. */
    public Conversation lock(UUID conversationId) {
        return conversations.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND));
    }

    /** The customer's conversation under its row lock: another customer's answers the same 404 as none. */
    public Conversation lockOwn(UUID conversationId, UUID customerId) {
        Conversation c = lock(conversationId);
        if (!c.customerId().equals(customerId)) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        }
        return c;
    }

    /**
     * Opens a conversation of {@code customerId} about {@code about} (the primary first, already checked against the
     * customer, BR-02), routes it, and logs both.
     *
     * @param assignTo        the agent it goes to straight away (Sales opening it themselves); null to route it
     * @param continuedFromId the ended conversation it continues; null for a fresh one
     */
    public Conversation open(UUID customerId, String subject, Actor opener, List<ContextResolver.Resolved> about,
                             UUID assignTo, UUID continuedFromId) {
        Instant now = clock.instant();
        UUID orderId = about.isEmpty() || about.get(0).ref().kind() != ContextKind.ORDER ? null
                : about.get(0).ref().id();
        Conversation c = Conversation.open(Identifiers.newId(), customerId, subject, orderId, opener, continuedFromId,
                now, properties.firstResponseTarget());
        c.route(assignTo != null ? assignTo : router.agentFor(c, about).orElse(null), now);
        conversations.save(c);
        for (int i = 0; i < about.size(); i++) {
            ConversationContext attached = conversations.attach(new ConversationContext(Identifiers.newId(), c.id(),
                    about.get(i).ref(), i == 0, opener.userId(), now));
            c.contextAttached(opener, attached.id(), now);
        }
        conversations.append(c.pullLog());
        changed(c, null);
        return c;
    }

    /**
     * Posts a message into {@code c}, which the caller locked and checked the caller may write to.
     *
     * <p>A retried send - the same client id, by the same person, saying the same - answers with the message the first
     * attempt stored and changes nothing; the same client id for anything else is {@code CHAT_CLIENT_MESSAGE_ID_REUSED}
     * and says nothing about the earlier message, which may be someone else's.</p>
     *
     * @param accept numbers the message on the conversation (and applies what the message does to its state)
     * @param build  the message, given its number
     */
    public Posted post(Conversation c, Actor sender, String clientMessageId, String text,
                       ToLongFunction<Conversation> accept, LongFunction<ChatMessage> build) {
        String clientId = clientMessageId == null || clientMessageId.isBlank() ? null : clientMessageId.strip();
        Optional<ChatMessage> earlier = messages.findByClientMessageId(c.id(), clientId);
        if (earlier.isPresent()) {
            ChatMessage first = earlier.get();
            if (Objects.equals(first.senderId(), sender.userId()) && first.senderType() == sender.type()
                    && first.sameText(text)) {
                return new Posted(first, true);
            }
            throw new BusinessException(ErrorCode.CHAT_CLIENT_MESSAGE_ID_REUSED);
        }
        ConversationStatus before = c.status();
        UUID agentBefore = c.assignedAgentId();
        long seq = accept.applyAsLong(c);
        ChatMessage message = build.apply(seq);
        conversations.save(c);
        ChatMessage stored = messages.save(message);
        conversations.append(c.pullLog());
        if (sender.type() != SenderType.SYSTEM) {
            markers.advance(c.id(), sender.userId(), sender.type(), seq, stored.sentAt());
        }
        broadcaster.message(ChatEvent.MESSAGE_CREATED, views.message(stored), customerAccounts(c));
        if (before != c.status() || !Objects.equals(agentBefore, c.assignedAgentId())) {
            changed(c, agentBefore);
        } else {
            broadcaster.inbox(new ChatEvent(ChatEvent.CONVERSATION_UPDATED, c.id(), staffRow(c), clock.instant()));
        }
        return new Posted(stored, false);
    }

    /** Saves a change of the conversation that posted no message (a claim, an assignment, a status), and tells. */
    public void save(Conversation c, UUID agentBefore) {
        conversations.save(c);
        conversations.append(c.pullLog());
        changed(c, agentBefore);
    }

    /**
     * Tells the clients the conversation changed: both sides on its topic (the customer's view of it, with nothing
     * staff-only), the staff queue, and the agent it went to on their own queue.
     */
    public void changed(Conversation c, UUID agentBefore) {
        Instant now = clock.instant();
        broadcaster.conversation(c.id(), new ChatEvent(ChatEvent.CONVERSATION_UPDATED, c.id(),
                views.rows(List.of(c), null, null).get(0), now));
        ChatEvent staff = new ChatEvent(ChatEvent.CONVERSATION_UPDATED, c.id(), staffRow(c), now);
        broadcaster.inbox(staff);
        if (c.assignedAgentId() != null && !c.assignedAgentId().equals(agentBefore)) {
            broadcaster.toUser(c.assignedAgentId(), staff);
        }
    }

    /** The customer's portal account, told of every message on its own queue. */
    public List<UUID> customerAccounts(Conversation c) {
        return customers.findById(c.customerId()).map(CustomerSummary::userId).filter(Objects::nonNull)
                .map(List::of).orElse(List.of());
    }

    private ChatConversationView staffRow(Conversation c) {
        return views.rows(List.of(c), null, Actor.agent(new UUID(0L, 0L), false)).get(0);
    }
}
