package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.BusinessHours;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationContext;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.chat.internal.domain.LogEntry;
import com.stockflow.chat.internal.domain.ReadMarkers;
import com.stockflow.customer.api.CustomerService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the chat's views from the aggregates, a list at a time: one query per kind of fact (names, last messages,
 * unread counts, contexts) for a whole page, never one per row.
 *
 * <p>What the customer may see is decided by the {@link Audience} handed in, which the message repository applies in
 * its queries; the staff-only fields of a conversation (the escalation reason, the moves open to the caller) are only
 * filled for the staff.</p>
 */
@Component
public class ChatViews {

    /** The moves the staff ask for by hand (Conversation#changeStatus); the others happen. */
    private static final Set<ConversationStatus> ASKED_FOR = EnumSet.of(ConversationStatus.ACTIVE,
            ConversationStatus.WAITING_ON_CUSTOMER, ConversationStatus.WAITING_ON_INTERNAL,
            ConversationStatus.ESCALATED, ConversationStatus.RESOLVED);

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ReadMarkers markers;
    private final ContextResolver contexts;
    private final AgentDirectory agents;
    private final ChatPresence presence;
    private final CustomerService customers;
    private final ObjectMapper json;
    private final ChatProperties properties;
    private final Clock clock;

    public ChatViews(ConversationRepository conversations, ChatMessageRepository messages, ReadMarkers markers,
                     ContextResolver contexts, AgentDirectory agents, ChatPresence presence,
                     CustomerService customers, ObjectMapper json, ChatProperties properties, Clock clock) {
        this.properties = properties;
        this.conversations = conversations;
        this.messages = messages;
        this.markers = markers;
        this.contexts = contexts;
        this.agents = agents;
        this.presence = presence;
        this.customers = customers;
        this.json = json;
        this.clock = clock;
    }

    /** Messages as {@code audience} sees them; the caller read them through the same audience. */
    public List<ChatMessageView> messages(List<ChatMessage> page) {
        Map<UUID, String> names = agents.names(page.stream().map(ChatMessage::senderId).toList());
        return page.stream().map(m -> message(m, names)).toList();
    }

    public ChatMessageView message(ChatMessage m) {
        return message(m, agents.names(m.senderId() == null ? List.of() : List.of(m.senderId())));
    }

    /**
     * List rows of conversations, for {@code viewerId} (whose unread counts they show).
     *
     * @param staff the staff member looking, or null for the customer
     */
    public List<ChatConversationView> rows(List<Conversation> page, UUID viewerId, Actor staff) {
        if (page.isEmpty()) {
            return List.of();
        }
        Audience audience = staff == null ? Audience.CUSTOMER : Audience.STAFF;
        List<UUID> ids = page.stream().map(Conversation::id).toList();
        Map<UUID, ChatMessage> last = messages.lastOf(ids, audience);
        Map<UUID, Long> unread = viewerId == null ? Map.of() : messages.unread(ids, viewerId, audience);
        Map<UUID, List<ConversationContext>> about = conversations.contextsOf(ids);
        Map<UUID, String> customerNames = customers.fullNames(page.stream().map(Conversation::customerId)
                .collect(Collectors.toSet()));
        Set<UUID> people = new HashSet<>();
        page.forEach(c -> people.add(c.assignedAgentId()));
        last.values().forEach(m -> people.add(m.senderId()));
        Map<UUID, String> names = agents.names(people);
        Instant now = clock.instant();
        return page.stream().map(c -> {
            ChatMessage newest = last.get(c.id());
            List<ChatConversationView.Context> rows = about.getOrDefault(c.id(), List.of()).stream()
                    .map(x -> new ChatConversationView.Context(x.id(), x.ref().kind(), x.ref().id(), x.primary(), null,
                            null, x.attachedAt())).toList();
            return view(c, staff, name(customerNames, c.customerId()), name(names, c.assignedAgentId()), now,
                    newest == null ? null : message(newest, names), unread.getOrDefault(c.id(), 0L), rows, null);
        }).toList();
    }

    /** A conversation's page: its contexts with their numbers and statuses, and everyone's read markers. */
    public ChatConversationView detail(Conversation c, UUID viewerId, Actor staff) {
        ChatConversationView row = rows(List.of(c), viewerId, staff).get(0);
        List<ChatConversationView.Context> about = new ArrayList<>();
        for (ConversationContext x : conversations.contextsOf(c.id())) {
            Optional<ContextResolver.Resolved> found = contexts.find(x.ref());
            about.add(new ChatConversationView.Context(x.id(), x.ref().kind(), x.ref().id(), x.primary(),
                    found.map(ContextResolver.Resolved::label).orElse(null),
                    found.map(ContextResolver.Resolved::status).orElse(null), x.attachedAt()));
        }
        List<ChatReadMarkerView> read = markers.of(c.id()).stream()
                .map(m -> new ChatReadMarkerView(c.id(), m.userId(), m.readerType(), m.seq(), m.at())).toList();
        return new ChatConversationView(row.id(), row.customerId(), row.customerName(), row.subject(), row.status(),
                row.nextStatuses(), row.assignedAgentId(), row.assignedAgentName(), row.agentOnline(),
                row.escalationReason(), row.firstResponseDueAt(), row.firstRespondedAt(), row.firstResponseOverdue(),
                row.reopenUntil(), row.continuedFromId(), row.createdAt(), row.lastMessageAt(), row.lastSeq(),
                row.unread(), row.lastMessage(), about, read, row.withinBusinessHours(), row.expectedResponseAt(),
                row.resolvedAt(), row.canRate(), row.csatScore(), row.csatComment(), row.csatSubmittedAt());
    }

    public List<ChatLogEntryView> log(List<LogEntry> entries) {
        Set<UUID> people = new HashSet<>();
        entries.forEach(e -> {
            people.add(e.actorId());
            people.add(e.targetUserId());
        });
        Map<UUID, String> names = agents.names(people);
        return entries.stream().map(e -> new ChatLogEntryView(e.id(), e.type(), e.actorType(), e.actorId(),
                name(names, e.actorId()), e.at(), e.from(), e.to(), e.contextId(), e.messageId(), e.targetUserId(),
                name(names, e.targetUserId()), e.note(), parse(e.detailJson()))).toList();
    }

    private ChatConversationView view(Conversation c, Actor staff, String customerName, String agentName,
                                      Instant now, ChatMessageView newest, long unread,
                                      List<ChatConversationView.Context> about, List<ChatReadMarkerView> read) {
        Conversation.Snapshot s = c.snapshot();
        BusinessHours hours = properties.businessHours();
        Set<ConversationStatus> next = null;
        if (staff != null) {
            next = EnumSet.noneOf(ConversationStatus.class);
            boolean allowed = s.status() != ConversationStatus.ESCALATED || staff.supervisor();
            for (ConversationStatus target : s.status().next()) {
                if (allowed && ASKED_FOR.contains(target)) {
                    next.add(target);
                }
            }
            next = Set.copyOf(next);
        }
        long lastSeq = staff != null ? s.lastMessageSeq() : newest == null ? 0 : newest.seq();
        Instant lastAt = staff != null ? s.lastMessageAt() : newest == null ? null : newest.sentAt();
        return new ChatConversationView(s.id(), s.customerId(), customerName, s.subject(), s.status(), next,
                s.assignedAgentId(), agentName, presence.isOnline(s.assignedAgentId()),
                staff == null ? null : s.escalationReason(), s.firstResponseDueAt(), s.firstRespondedAt(),
                c.firstResponseOverdueAt(now), s.reopenUntil(), s.continuedFromId(), s.createdAt(), lastAt, lastSeq,
                unread, newest, about, read, hours.isOpenAt(now), expectedResponseAt(c, hours, now), s.resolvedAt(),
                s.status() == ConversationStatus.RESOLVED && s.csatSubmittedAt() == null, s.csatScore(),
                staff == null ? null : s.csatComment(), s.csatSubmittedAt());
    }

    private ChatMessageView message(ChatMessage m, Map<UUID, String> names) {
        boolean deleted = m.deletedAt() != null;
        ChatMessage.Card card = m.card();
        return new ChatMessageView(m.id(), m.conversationId(), m.seq(), m.kind(), m.visibility(), m.senderType(),
                m.senderId(), name(names, m.senderId()), deleted ? null : m.body(), m.clientMessageId(), m.contextId(),
                card == null ? null : card(card, deleted),
                deleted ? List.of() : m.attachments().stream().map(a -> new ChatMessageView.Attachment(a.id(),
                        a.originalName(), a.contentType(), a.sizeBytes(), a.width(), a.height())).toList(),
                m.mentions(), m.sentAt(), m.editedAt(), deleted);
    }

    /**
     * When the customer can expect Sales (16 §4.1 step 3: shown outside business hours): the first answer's due time
     * while none came; otherwise, while the conversation goes on and Sales are not at work, their next opening.
     */
    static Instant expectedResponseAt(Conversation c, BusinessHours hours, Instant now) {
        if (c.status().isEnded()) {
            return null;
        }
        if (c.firstRespondedAt() == null && c.firstResponseDueAt() != null) {
            return c.firstResponseDueAt();
        }
        return hours.isOpenAt(now) ? null : hours.nextOpening(now);
    }

    private ChatMessageView.Card card(ChatMessage.Card card, boolean deleted) {
        JsonNode payload = deleted ? null : parse(card.payloadJson());
        CardActions.Available actions = CardActions.of(card.kind(), card.state(), payload);
        return new ChatMessageView.Card(card.kind(), card.state(), payload, card.updatedAt(), actions.customer(),
                actions.staff());
    }

    private JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException malformed) {
            return null;
        }
    }

    /** A name from a lookup that may be an immutable map, which refuses a null key. */
    private static String name(Map<UUID, String> names, UUID id) {
        return id == null ? null : names.get(id);
    }
}
