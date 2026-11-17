package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationContext;
import com.stockflow.chat.internal.domain.ConversationEventType;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.chat.internal.domain.InboxFilter;
import com.stockflow.chat.internal.domain.Slice;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.payment.api.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The staff's side of the chat in the back office (kltn-docs 16 §4): the queue, a conversation with the customer beside
 * it, answering, internal notes, taking and handing conversations on, and the moves of 16 §5.
 *
 * <p>Every method starts with {@link ChatCallers#staff}, which refuses a portal account before anything is read or
 * validated: the CUSTOMER role holds the same {@code chat-conversations} permission for its own side. Any staff member
 * with the permission may look at any conversation ("người khác ở chế độ xem"); only the agent in charge, or a chat
 * supervisor, answers the customer and moves it along - the aggregate's rule.</p>
 */
@Service
@Transactional
public class StaffChatService {

    private static final int MAX_MENTIONS = 20;

    private final ChatCallers callers;
    private final ConversationRepository conversations;
    private final ConversationWriter writer;
    private final ChatReading reading;
    private final ContextResolver contexts;
    private final AgentDirectory agents;
    private final ChatPresence presence;
    private final ChatViews views;
    private final CustomerService customers;
    private final ChatBroadcaster broadcaster;
    private final ChatProperties properties;
    private final ChatCards cards;
    private final ChatMessageRepository messages;
    private final CannedResponses canned;
    private final PaymentService payments;
    private final Clock clock;

    public StaffChatService(ChatCallers callers, ConversationRepository conversations, ConversationWriter writer,
                            ChatReading reading, ContextResolver contexts, AgentDirectory agents,
                            ChatPresence presence, ChatViews views, CustomerService customers,
                            ChatBroadcaster broadcaster, ChatProperties properties, ChatCards cards,
                            ChatMessageRepository messages, CannedResponses canned, PaymentService payments,
                            Clock clock) {
        this.cards = cards;
        this.messages = messages;
        this.canned = canned;
        this.payments = payments;
        this.callers = callers;
        this.conversations = conversations;
        this.writer = writer;
        this.reading = reading;
        this.contexts = contexts;
        this.agents = agents;
        this.presence = presence;
        this.views = views;
        this.customers = customers;
        this.broadcaster = broadcaster;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * What the queue is asked for.
     *
     * @param mine       only the caller's own conversations
     * @param unassigned only those nobody has
     * @param overdue    only those whose first answer or resolution is late (BR-09)
     */
    public record InboxQuery(Set<ConversationStatus> statuses, boolean mine, boolean unassigned, boolean overdue,
                             UUID customerId, ContextRef about, int page, Integer size) {
    }

    /** The queue: the overdue first (a late first answer or resolution, BR-09), then the most recently active. */
    @Transactional(readOnly = true)
    public PageResponse<ChatConversationView> inbox(CurrentUser user, InboxQuery query) {
        Actor staff = callers.staff(user);
        int size = properties.pageSize(query.size(), 20);
        int page = Math.max(0, query.page());
        Slice<Conversation> slice = conversations.inbox(new InboxFilter(query.statuses(),
                query.mine() ? user.userId() : null, query.unassigned(), query.overdue(), query.customerId(),
                query.about()), clock.instant(), page, size);
        return PageResponse.of(views.rows(slice.items(), user.userId(), staff), page, size, slice.total());
    }

    /**
     * Sales open a conversation with a customer themselves (16 §4.1 step 1), about a document of that customer
     * (BR-02), and have it: it is assigned to the opener when they answer customers.
     */
    public ChatConversationView open(CurrentUser user, UUID customerId, String subject, ContextRef about,
                                     String text, String clientMessageId) {
        Actor staff = callers.staff(user);
        if (customerId == null || customers.findById(customerId).isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        List<ContextResolver.Resolved> resolved = about == null ? List.of()
                : List.of(contexts.requireOwnedBy(about, customerId, false));
        UUID assignTo = agents.answersCustomers(staff.userId()) ? staff.userId() : null;
        Conversation c = writer.open(customerId, subject, staff, resolved, assignTo, null);
        if (text != null && !text.isBlank()) {
            reply(c, staff, text, clientMessageId, null);
        }
        return views.detail(c, user.userId(), staff);
    }

    @Transactional(readOnly = true)
    public ChatConversationView get(CurrentUser user, UUID conversationId) {
        Actor staff = callers.staff(user);
        return views.detail(find(conversationId), user.userId(), staff);
    }

    /** Messages for the staff: internal notes included. */
    @Transactional(readOnly = true)
    public ChatMessagePage messages(CurrentUser user, UUID conversationId, Long before, Long since, Integer limit) {
        callers.staff(user);
        return reading.page(find(conversationId), Audience.STAFF, before, since, limit);
    }

    /**
     * A text to the customer, by the agent in charge or a supervisor; the first one stops the first-response clock.
     *
     * @param contextId the conversation's context the answer is about; null for none in particular
     */
    public ChatMessageView reply(CurrentUser user, UUID conversationId, String text, String clientMessageId,
                                 UUID contextId) {
        return reply(user, conversationId, text, clientMessageId, contextId, null);
    }

    /**
     * {@link #reply(CurrentUser, UUID, String, String, UUID)}, or a saved reply sent with its variables filled from
     * this conversation ({@code cannedResponseId}; {@code text} is then left out).
     */
    public ChatMessageView reply(CurrentUser user, UUID conversationId, String text, String clientMessageId,
                                 UUID contextId, UUID cannedResponseId) {
        Actor staff = callers.staff(user);
        Conversation c = writer.lock(conversationId);
        String said = text;
        if (cannedResponseId != null) {
            user.require(ChatCallers.CANNED_RESPONSES, com.stockflow.common.security.Action.READ);
            said = canned.render(staff, cannedResponseId, c);
        }
        return views.message(reply(c, staff, said, clientMessageId, contextId).message());
    }

    /**
     * An internal note (16 BR-07: never shown to the customer), naming the colleagues it asks (16 §4.6 step 16: the
     * accountant, the warehouse manager...). Any staff member may write one, at any time; each one mentioned is told.
     */
    public ChatMessageView note(CurrentUser user, UUID conversationId, String text, List<UUID> mentions,
                                String clientMessageId, UUID contextId) {
        Actor staff = callers.staff(user);
        Set<UUID> named = mentioned(mentions);
        Conversation c = writer.lock(conversationId);
        UUID about = requireContext(c, contextId);
        ConversationWriter.Posted posted = writer.post(c, staff, clientMessageId, text,
                conversation -> conversation.acceptInternalNote(staff, clock.instant()),
                seq -> ChatMessage.internalNote(Identifiers.newId(), c.id(), seq, staff, text, named,
                        CustomerChatService.blankToNull(clientMessageId), about, clock.instant()));
        ChatMessageView view = views.message(posted.message());
        if (!posted.replayed()) {
            c.logMessageAction(ConversationEventType.INTERNAL_NOTE, staff, posted.message().id(), about, null,
                    named.isEmpty() ? null : "{\"mentions\":" + named.stream().map(id -> "\"" + id + "\"").toList()
                            + "}", clock.instant());
            conversations.append(c.pullLog());
            named.stream().filter(id -> !id.equals(staff.userId())).forEach(id -> broadcaster.toUser(id,
                    new ChatEvent(ChatEvent.MESSAGE_CREATED, c.id(), view, clock.instant())));
        }
        return view;
    }

    /** The caller takes a conversation nobody has (16 §4.1 step 2). */
    public ChatConversationView claim(CurrentUser user, UUID conversationId) {
        Actor staff = callers.staff(user);
        Conversation c = writer.lock(conversationId);
        UUID before = c.assignedAgentId();
        c.claim(staff, clock.instant());
        writer.save(c, before);
        return views.detail(c, user.userId(), staff);
    }

    /**
     * Hands the conversation to {@code agentId}: a supervisor assigning one from the queue, or the agent in charge (or
     * a supervisor) transferring it - with its contexts, notes and history, which are the conversation's.
     *
     * @throws BusinessException {@code CHAT_AGENT_NOT_AVAILABLE} when {@code agentId} does not answer customers
     */
    public ChatConversationView assign(CurrentUser user, UUID conversationId, UUID agentId, String note) {
        Actor staff = callers.staff(user);
        if (!agents.answersCustomers(agentId)) {
            throw new BusinessException(ErrorCode.CHAT_AGENT_NOT_AVAILABLE);
        }
        Conversation c = writer.lock(conversationId);
        UUID before = c.assignedAgentId();
        c.handTo(staff, agentId, CustomerChatService.blankToNull(note), clock.instant());
        writer.save(c, before);
        return views.detail(c, user.userId(), staff);
    }

    /** A move of 16 §5 asked by the agent in charge or a supervisor: waiting, escalating (with why), resolving. */
    public ChatConversationView changeStatus(CurrentUser user, UUID conversationId, ConversationStatus target,
                                             String reason) {
        Actor staff = callers.staff(user);
        Conversation c = writer.lock(conversationId);
        c.changeStatus(staff, target, reason, clock.instant(), properties.reopenWindow());
        writer.save(c, c.assignedAgentId());
        if (target == ConversationStatus.RESOLVED && c.csatSubmittedAt() == null) {
            askForRating(c);
        }
        return views.detail(c, user.userId(), staff);
    }

    /**
     * Sales attach a document of the conversation's customer to it (BR-02: never another customer's). Attaching one
     * already there changes nothing.
     */
    public ChatConversationView attachContext(CurrentUser user, UUID conversationId, ContextRef ref) {
        Actor staff = callers.staff(user);
        if (ref == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name the document: a kind and an id");
        }
        Conversation c = writer.lock(conversationId);
        contexts.requireOwnedBy(ref, c.customerId(), false);
        if (conversations.findContext(c.id(), ref).isEmpty()) {
            boolean first = conversations.contextsOf(c.id()).isEmpty();
            ConversationContext attached = conversations.attach(new ConversationContext(Identifiers.newId(), c.id(),
                    ref, first, staff.userId(), clock.instant()));
            c.contextAttached(staff, attached.id(), clock.instant());
            writer.save(c, c.assignedAgentId());
        }
        return views.detail(c, user.userId(), staff);
    }

    public ChatReadMarkerView markRead(CurrentUser user, UUID conversationId, Long seq) {
        Actor staff = callers.staff(user);
        return reading.markRead(writer.lock(conversationId), staff, seq);
    }

    /** The conversation's log (16 BR-01), the oldest first. */
    @Transactional(readOnly = true)
    public List<ChatLogEntryView> log(CurrentUser user, UUID conversationId) {
        callers.staff(user);
        return views.log(conversations.logOf(find(conversationId).id()));
    }

    /**
     * The agent's badge: in the conversations in their charge, how many have something unread and how many messages;
     * and how many wait in the queue for someone to take them.
     */
    @Transactional(readOnly = true)
    public StaffUnread unread(CurrentUser user) {
        callers.staff(user);
        ChatMessageRepository.UnreadTotal mine = messages.unreadTotal(user.userId(), Audience.STAFF, null,
                user.userId());
        long queued = conversations.inbox(new InboxFilter(java.util.Set.of(ConversationStatus.NEW,
                ConversationStatus.QUEUED), null, true, false, null, null), clock.instant(), 0, 1).total();
        return new StaffUnread(mine.conversations(), mine.messages(), queued);
    }

    /** The staff badge: unread in the caller's conversations, and the unassigned queue. */
    public record StaffUnread(long conversations, long messages, long queued) {
    }

    /** Who answers customers, by name, the online ones first: the assign and transfer dialog. */
    @Transactional(readOnly = true)
    public List<ChatAgentView> agents(CurrentUser user) {
        callers.staff(user);
        Set<UUID> ids = agents.agents();
        Map<UUID, String> names = agents.names(ids);
        return ids.stream().map(id -> new ChatAgentView(id, names.get(id), presence.isOnline(id)))
                .sorted(Comparator.comparing(ChatAgentView::online).reversed()
                        .thenComparing(agent -> agent.name() == null ? "" : agent.name()))
                .toList();
    }

    /**
     * The CSAT survey card of a resolved conversation (16 §4.6 step 18), replacing one an earlier resolution left
     * unanswered; the customer rates on it, once ({@link ChatCardActions}).
     */
    private void askForRating(Conversation c) {
        for (ChatMessage open : messages.openCardsIn(c.id(), CardKind.CSAT_SURVEY)) {
            cards.update(open.id(), CardState.SUPERSEDED, null, Actor.system(), null, null);
        }
        com.fasterxml.jackson.databind.node.ObjectNode payload = com.fasterxml.jackson.databind.node.JsonNodeFactory
                .instance.objectNode().put("conversationId", c.id().toString()).put("scale", 5);
        cards.postInto(c, null, CardKind.CSAT_SURVEY, payload, "Anh/chị hài lòng với hỗ trợ lần này chứ?",
                Actor.system());
    }

    /**
     * BR-06: an account number reaches the customer only on the system's transfer card. A reply that spells out the
     * company's own account is refused - the agent sends the card instead
     * ({@code POST .../cards/transfer-details}), which carries the right account, amount and content.
     */
    private void refuseTypedAccount(String text) {
        if (text == null) {
            return;
        }
        String digits = text.replaceAll("[\\s.-]", "");
        payments.companyBankAccount().map(account -> account.accountNumber().replaceAll("\\s", ""))
                .filter(account -> account.length() >= 6 && digits.contains(account))
                .ifPresent(account -> {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Send the transfer details card instead"
                            + " of typing the account number (16 BR-06)");
                });
    }

    private ConversationWriter.Posted reply(Conversation c, Actor staff, String text, String clientMessageId,
                                            UUID contextId) {
        refuseTypedAccount(text);
        UUID about = requireContext(c, contextId);
        return writer.post(c, staff, clientMessageId, text,
                conversation -> conversation.acceptAgentMessage(staff, clock.instant()),
                seq -> ChatMessage.text(Identifiers.newId(), c.id(), seq, staff, text,
                        CustomerChatService.blankToNull(clientMessageId), about, clock.instant()));
    }

    /** A context id named by a message must be one of this conversation's. */
    private UUID requireContext(Conversation c, UUID contextId) {
        if (contextId == null) {
            return null;
        }
        return conversations.findContext(c.id(), contextId).map(ConversationContext::id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONTEXT_NOT_FOUND));
    }

    /** The staff a note mentions: existing accounts, none of them a customer's portal account. */
    private Set<UUID> mentioned(List<UUID> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return Set.of();
        }
        Set<UUID> named = new LinkedHashSet<>(mentions);
        named.remove(null);
        if (named.size() > MAX_MENTIONS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A note mentions at most " + MAX_MENTIONS);
        }
        Map<UUID, String> known = agents.names(named);
        for (UUID id : named) {
            if (!known.containsKey(id) || customers.findByUserId(id).isPresent()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only staff are mentioned in a note");
            }
        }
        return Set.copyOf(named);
    }

    private Conversation find(UUID conversationId) {
        return conversations.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND));
    }
}
